-- Moderation (AD-06).

-- Set when an admin bans the user: signed out everywhere, can't sign in again, and their yells are taken down.
ALTER TABLE users ADD COLUMN banned_at timestamptz;

-- Every admin action. Append-only, so no updated_at.
CREATE TABLE audit_log
(
    id         uuid PRIMARY KEY,
    actor      text        NOT NULL,
    action     text        NOT NULL,
    target     text        NOT NULL,
    detail     text,
    created_at timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX audit_log_created_at_idx ON audit_log (created_at);
