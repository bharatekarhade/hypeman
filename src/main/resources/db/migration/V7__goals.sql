-- Goals, synced from the app so they follow the user to a new phone. The app makes each goal's id; the server keys
-- rows by (user, that id), so two accounts that once shared a phone can both hold the same goal.
CREATE TABLE goals
(
    id           uuid PRIMARY KEY,
    user_id      uuid        NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    -- The app's id for the goal.
    goal_id      uuid        NOT NULL,
    -- Null once the goal is deleted: a deleted goal keeps only its id, so other devices learn it's gone.
    title        text CHECK (char_length(title) BETWEEN 1 AND 80),
    sort_index   integer,
    reminder_at  timestamptz,
    started_at   timestamptz,
    completed_at timestamptz,
    -- "pack:<uuid>", "mine:<uuid>" or "default"; null for the default motivator.
    motivator_id text CHECK (char_length(motivator_id) <= 100),
    -- When the app made the change. The newest change wins, whatever order changes arrive in.
    changed_at   timestamptz NOT NULL,
    deleted_at   timestamptz,
    created_at   timestamptz NOT NULL DEFAULT now(),
    -- The app pulls everything updated since its last sync.
    updated_at   timestamptz NOT NULL DEFAULT now(),
    UNIQUE (user_id, goal_id),
    CHECK ((deleted_at IS NULL) = (title IS NOT NULL AND sort_index IS NOT NULL))
);

CREATE INDEX goals_user_id_updated_at_idx ON goals (user_id, updated_at);

CREATE TRIGGER goals_set_updated_at BEFORE UPDATE ON goals
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();
