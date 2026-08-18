CREATE INDEX idx_ledger_event_account_history
    ON ledger_event (account_id, occurred_at DESC, id DESC);

CREATE INDEX idx_ledger_effect_wish_history
    ON ledger_wish_effect (account_id, wish_id, event_id);

CREATE INDEX idx_adjustment_case_event_history
    ON balance_adjustment_case_event (account_id, event_id);
