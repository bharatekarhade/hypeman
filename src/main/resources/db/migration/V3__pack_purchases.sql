-- Pack purchases: packs bought one at a time.
-- Pro is users.pro_until and needs no table.
CREATE TABLE pack_purchases
(
    id                      uuid PRIMARY KEY,
    user_id                 uuid        NOT NULL
        REFERENCES users (id) ON DELETE CASCADE,
    pack_id                 uuid        NOT NULL
        REFERENCES packs (id),
    -- Apple's id for the purchase, the same
    -- across restores and devices.
    original_transaction_id text        NOT NULL UNIQUE,
    environment             text        NOT NULL
        CHECK (environment IN
          ('PRODUCTION', 'SANDBOX', 'XCODE')),
    purchased_at            timestamptz NOT NULL,
    -- Set on a refund or revocation; the pack
    -- locks again.
    revoked_at              timestamptz,
    created_at              timestamptz NOT NULL DEFAULT now(),
    updated_at              timestamptz NOT NULL DEFAULT now(),
    UNIQUE (user_id, pack_id)
);

CREATE TRIGGER pack_purchases_set_updated_at
    BEFORE UPDATE ON pack_purchases
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();
