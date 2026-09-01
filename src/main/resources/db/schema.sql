DROP TABLE IF EXISTS ledger_entries;
DROP TABLE IF EXISTS transfers;
DROP TABLE IF EXISTS accounts;
DROP TABLE IF EXISTS users;

CREATE TABLE users(
    id BIGSERIAL PRIMARY KEY,
    username VARCHAR(50) NOT NULL UNIQUE,
    email VARCHAR(255) NOT NULL UNIQUE,
    password_hash VARCHAR(255) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE accounts(
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT NOT NULL REFERENCES users(id),
    account_number VARCHAR(20) NOT NULL UNIQUE,
    balance_minor BIGINT NOT NULL DEFAULT 0,
    currency VARCHAR(3) NOT NULL DEFAULT 'INR',
    account_type VARCHAR(10) NOT NULL DEFAULT 'CUSTOMER',
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT balance_non_negative CHECK (account_type = 'SYSTEM' OR balance_minor >= 0),
    CONSTRAINT account_type_valid CHECK (account_type IN ('CUSTOMER', 'SYSTEM'))
);

CREATE TABLE transfers(
    id BIGSERIAL PRIMARY KEY,
    initiated_by BIGINT NOT NULL REFERENCES users(id),
    amount_minor BIGINT NOT NULL,
    description VARCHAR(255),
    status VARCHAR(20) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT amount_positive CHECK (amount_minor > 0)
);

CREATE TABLE ledger_entries(
    id BIGSERIAL PRIMARY KEY,
    transfer_id BIGINT NOT NULL REFERENCES transfers(id),
    account_id BIGINT NOT NULL REFERENCES accounts(id),
    amount_minor BIGINT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_ledger_entries_account ON ledger_entries(account_id);
CREATE INDEX idx_accounts_user ON accounts(user_id);

CREATE OR REPLACE FUNCTION check_transfer_balances()
RETURNS TRIGGER AS $$
DECLARE
    entry_sum BIGINT;
BEGIN 
    SELECT COALESCE(SUM(amount_minor), 0) INTO entry_sum FROM ledger_entries WHERE transfer_id = NEW.transfer_id;

    IF entry_sum <> 0 THEN
        RAISE EXCEPTION 'Ledger entries for transfer % do not balance (sum = %)',
            NEW.transfer_id, entry_sum;
    END IF;
    RETURN NULL;
END;

$$ LANGUAGE plpgsql;

CREATE CONSTRAINT TRIGGER trg_entries_balance
    AFTER INSERT ON ledger_entries
    DEFERRABLE INITIALLY DEFERRED 
    FOR EACH ROW
    EXECUTE FUNCTION check_transfer_balances();