-- Run once, after migrations, with the non-web DB administrator.
-- This deliberately fails if the role exists. It never rotates a live credential.
-- Assign a generated password and LOGIN separately through a private channel.
BEGIN;
CREATE ROLE crabit_demo_app NOLOGIN NOINHERIT NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS;
GRANT USAGE ON SCHEMA public TO crabit_demo_app;
GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA public TO crabit_demo_app;
GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA public TO crabit_demo_app;
REVOKE INSERT, UPDATE, DELETE ON flyway_schema_history,
    demo_simulation_dataset, demo_simulation_account, demo_simulation_persona,
    demo_simulation_cash_event, demo_simulation_application FROM crabit_demo_app;
-- SELECT FOR UPDATE requires an UPDATE privilege; lifecycle writes stay administrative.
GRANT UPDATE(revision) ON demo_simulation_dataset TO crabit_demo_app;
GRANT UPDATE(card_funds, cash_sequence) ON demo_simulation_account TO crabit_demo_app;
GRANT INSERT ON demo_simulation_cash_event TO crabit_demo_app;
-- Do not grant membership in crabit_demo_manager or EXECUTE on all functions.
-- Existing ordinary immutable-history triggers continue to apply to this role.
COMMIT;
