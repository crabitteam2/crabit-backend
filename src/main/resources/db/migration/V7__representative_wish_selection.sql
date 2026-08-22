CREATE TABLE representative_wish_selection (
    account_id UUID PRIMARY KEY,
    wish_id UUID NOT NULL,
    CONSTRAINT fk_representative_selection_account
        FOREIGN KEY (account_id) REFERENCES card_balance_account (id)
        ON DELETE CASCADE DEFERRABLE,
    CONSTRAINT fk_representative_selection_wish_account
        FOREIGN KEY (wish_id, account_id) REFERENCES wish (id, account_id)
        ON DELETE CASCADE DEFERRABLE
);

INSERT INTO representative_wish_selection (account_id, wish_id)
SELECT account.id, (array_agg(wish.id ORDER BY wish.id))[1]
FROM card_balance_account account
JOIN wish
  ON wish.account_id = account.id
 AND wish.deleted_at IS NULL
 AND wish.state IN ('IN_PROGRESS', 'AMOUNT_REACHED')
WHERE account.closed_at IS NULL
GROUP BY account.id
HAVING count(*) = 1;

CREATE FUNCTION validate_representative_wish_state(target_account_id UUID)
RETURNS VOID
LANGUAGE plpgsql
AS $$
DECLARE
    account_closed_at TIMESTAMPTZ;
    selected_wish_id UUID;
    active_wish_count BIGINT;
    only_active_wish_id UUID;
BEGIN
    SELECT closed_at
    INTO account_closed_at
    FROM card_balance_account
    WHERE id = target_account_id;

    IF NOT FOUND THEN
        RETURN;
    END IF;

    SELECT wish_id
    INTO selected_wish_id
    FROM representative_wish_selection
    WHERE account_id = target_account_id;

    SELECT count(*), (array_agg(id ORDER BY id))[1]
    INTO active_wish_count, only_active_wish_id
    FROM wish
    WHERE account_id = target_account_id
      AND deleted_at IS NULL
      AND state IN ('IN_PROGRESS', 'AMOUNT_REACHED');

    IF account_closed_at IS NOT NULL THEN
        IF selected_wish_id IS NOT NULL THEN
            RAISE EXCEPTION 'A closed Card Balance Account cannot retain a Representative Wish'
                USING ERRCODE = '23514';
        END IF;
        RETURN;
    END IF;

    IF selected_wish_id IS NOT NULL
       AND NOT EXISTS (
           SELECT 1
           FROM wish
           WHERE id = selected_wish_id
             AND account_id = target_account_id
             AND deleted_at IS NULL
             AND state IN ('IN_PROGRESS', 'AMOUNT_REACHED')
       ) THEN
        RAISE EXCEPTION 'A Representative Wish must be an active nondeleted Wish of its Card Balance Account'
            USING ERRCODE = '23514';
    END IF;

    IF active_wish_count = 1
       AND selected_wish_id IS DISTINCT FROM only_active_wish_id THEN
        RAISE EXCEPTION 'An open Card Balance Account with exactly one active Wish must select it as representative'
            USING ERRCODE = '23514';
    END IF;
END;
$$;

CREATE FUNCTION validate_representative_selection_row()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    PERFORM validate_representative_wish_state(COALESCE(NEW.account_id, OLD.account_id));
    IF TG_OP = 'UPDATE' AND NEW.account_id IS DISTINCT FROM OLD.account_id THEN
        PERFORM validate_representative_wish_state(OLD.account_id);
    END IF;
    RETURN NULL;
END;
$$;

CREATE FUNCTION validate_representative_selection_wish()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    PERFORM validate_representative_wish_state(COALESCE(NEW.account_id, OLD.account_id));
    IF TG_OP = 'UPDATE' AND NEW.account_id IS DISTINCT FROM OLD.account_id THEN
        PERFORM validate_representative_wish_state(OLD.account_id);
    END IF;
    RETURN NULL;
END;
$$;

CREATE FUNCTION validate_representative_selection_account()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    PERFORM validate_representative_wish_state(COALESCE(NEW.id, OLD.id));
    RETURN NULL;
END;
$$;

CREATE FUNCTION select_first_active_wish()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    IF NEW.deleted_at IS NULL
       AND NEW.state IN ('IN_PROGRESS', 'AMOUNT_REACHED')
       AND EXISTS (
           SELECT 1 FROM card_balance_account
           WHERE id = NEW.account_id AND closed_at IS NULL
       )
       AND (
           SELECT count(*) FROM wish
           WHERE account_id = NEW.account_id
             AND deleted_at IS NULL
             AND state IN ('IN_PROGRESS', 'AMOUNT_REACHED')
       ) = 1 THEN
        INSERT INTO representative_wish_selection (account_id, wish_id)
        VALUES (NEW.account_id, NEW.id)
        ON CONFLICT (account_id) DO NOTHING;
    END IF;
    RETURN NULL;
END;
$$;

CREATE TRIGGER select_first_active_wish_after_insert
AFTER INSERT ON wish
FOR EACH ROW EXECUTE FUNCTION select_first_active_wish();

CREATE FUNCTION clear_representative_wish_on_account_close()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    DELETE FROM representative_wish_selection WHERE account_id = NEW.id;
    RETURN NULL;
END;
$$;

CREATE TRIGGER clear_representative_wish_after_account_close
AFTER UPDATE OF closed_at ON card_balance_account
FOR EACH ROW
WHEN (OLD.closed_at IS NULL AND NEW.closed_at IS NOT NULL)
EXECUTE FUNCTION clear_representative_wish_on_account_close();

CREATE CONSTRAINT TRIGGER ck_representative_selection_row_final_state
AFTER INSERT OR UPDATE OR DELETE ON representative_wish_selection
DEFERRABLE INITIALLY DEFERRED
FOR EACH ROW EXECUTE FUNCTION validate_representative_selection_row();

CREATE CONSTRAINT TRIGGER ck_representative_selection_wish_final_state
AFTER INSERT OR UPDATE OR DELETE ON wish
DEFERRABLE INITIALLY DEFERRED
FOR EACH ROW EXECUTE FUNCTION validate_representative_selection_wish();

CREATE CONSTRAINT TRIGGER ck_representative_selection_account_final_state
AFTER INSERT OR UPDATE OR DELETE ON card_balance_account
DEFERRABLE INITIALLY DEFERRED
FOR EACH ROW EXECUTE FUNCTION validate_representative_selection_account();
