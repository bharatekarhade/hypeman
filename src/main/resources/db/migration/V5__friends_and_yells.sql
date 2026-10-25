-- Friends and yells: the friend and yell modules.

-- A code a user shares to add a friend. One use; 8 characters with no look-alikes.
CREATE TABLE friend_invites
(
    id          uuid PRIMARY KEY,
    inviter_id  uuid        NOT NULL
        REFERENCES users (id) ON DELETE CASCADE,
    code        text        NOT NULL UNIQUE,
    expires_at  timestamptz NOT NULL,
    accepted_by uuid
        REFERENCES users (id) ON DELETE SET NULL,
    accepted_at timestamptz,
    created_at  timestamptz NOT NULL DEFAULT now(),
    updated_at  timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX friend_invites_inviter_id_idx ON friend_invites (inviter_id);

-- One row a pair, the smaller id first.
CREATE TABLE friendships
(
    user_a     uuid        NOT NULL
        REFERENCES users (id) ON DELETE CASCADE,
    user_b     uuid        NOT NULL
        REFERENCES users (id) ON DELETE CASCADE,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (user_a, user_b),
    CHECK (user_a < user_b)
);

CREATE INDEX friendships_user_b_idx ON friendships (user_b);

CREATE TABLE blocks
(
    blocker_id uuid        NOT NULL
        REFERENCES users (id) ON DELETE CASCADE,
    blocked_id uuid        NOT NULL
        REFERENCES users (id) ON DELETE CASCADE,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (blocker_id, blocked_id),
    CHECK (blocker_id <> blocked_id)
);

CREATE TABLE yells
(
    id          uuid PRIMARY KEY,
    sender_id   uuid        NOT NULL
        REFERENCES users (id) ON DELETE CASCADE,
    role        text        NOT NULL
        CHECK (role IN ('START', 'REMINDER', 'NAG', 'CONGRATS')),
    caption     text        NOT NULL,
    audio_key   text        NOT NULL,
    video_key   text,
    duration_ms int,
    status      text        NOT NULL DEFAULT 'UPLOADING'
        CHECK (status IN ('UPLOADING', 'READY', 'REMOVED')),
    -- Set when first sent; counts toward the free monthly cap.
    sent_at     timestamptz,
    created_at  timestamptz NOT NULL DEFAULT now(),
    updated_at  timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX yells_sender_id_sent_at_idx ON yells (sender_id, sent_at) WHERE sent_at IS NOT NULL;

CREATE TABLE yell_deliveries
(
    id           uuid PRIMARY KEY,
    yell_id      uuid        NOT NULL
        REFERENCES yells (id) ON DELETE CASCADE,
    recipient_id uuid        NOT NULL
        REFERENCES users (id) ON DELETE CASCADE,
    saved_at     timestamptz,
    hidden_at    timestamptz,
    created_at   timestamptz NOT NULL DEFAULT now(),
    updated_at   timestamptz NOT NULL DEFAULT now(),
    UNIQUE (yell_id, recipient_id)
);

-- The inbox.
CREATE INDEX yell_deliveries_recipient_id_created_at_idx ON yell_deliveries (recipient_id, created_at);

CREATE TABLE reports
(
    id          uuid PRIMARY KEY,
    reporter_id uuid        NOT NULL
        REFERENCES users (id) ON DELETE CASCADE,
    yell_id     uuid        NOT NULL
        REFERENCES yells (id) ON DELETE CASCADE,
    reason      text        NOT NULL
        CHECK (reason IN ('SPAM', 'HARASSMENT', 'HATE', 'SEXUAL', 'OTHER')),
    status      text        NOT NULL DEFAULT 'OPEN'
        CHECK (status IN ('OPEN', 'REMOVED', 'DISMISSED')),
    handled_at  timestamptz,
    created_at  timestamptz NOT NULL DEFAULT now(),
    updated_at  timestamptz NOT NULL DEFAULT now(),
    UNIQUE (reporter_id, yell_id)
);

-- The moderation queue.
CREATE INDEX reports_open_idx ON reports (created_at) WHERE status = 'OPEN';

CREATE TRIGGER friend_invites_set_updated_at BEFORE UPDATE ON friend_invites
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();
CREATE TRIGGER friendships_set_updated_at BEFORE UPDATE ON friendships
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();
CREATE TRIGGER blocks_set_updated_at BEFORE UPDATE ON blocks
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();
CREATE TRIGGER yells_set_updated_at BEFORE UPDATE ON yells
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();
CREATE TRIGGER yell_deliveries_set_updated_at BEFORE UPDATE ON yell_deliveries
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();
CREATE TRIGGER reports_set_updated_at BEFORE UPDATE ON reports
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();
