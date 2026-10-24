-- Pro subscription periods, one row per Apple transaction.
-- users.pro_until is worked out from these, so they can
-- arrive in any order and a refund can't be undone.
CREATE TABLE pro_transactions
(
    id                      uuid PRIMARY KEY,
    user_id                 uuid        NOT NULL
        REFERENCES users (id) ON DELETE CASCADE,
    -- One per subscription period.
    transaction_id          text        NOT NULL UNIQUE,
    original_transaction_id text        NOT NULL,
    product_id              text        NOT NULL,
    environment             text        NOT NULL
        CHECK (environment IN
          ('PRODUCTION', 'SANDBOX', 'XCODE')),
    purchased_at            timestamptz NOT NULL,
    expires_at              timestamptz NOT NULL,
    -- Once set, never cleared.
    revoked_at              timestamptz,
    created_at              timestamptz NOT NULL DEFAULT now(),
    updated_at              timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX pro_transactions_user_id
    ON pro_transactions (user_id);

CREATE TRIGGER pro_transactions_set_updated_at
    BEFORE UPDATE ON pro_transactions
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();
