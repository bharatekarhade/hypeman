-- Packs and clips: the pack module.
CREATE TABLE packs
(
    id            uuid PRIMARY KEY,
    -- Null for default packs; FK added once the creators table exists.
    creator_id    uuid,
    slug          text        NOT NULL UNIQUE,
    title         text        NOT NULL,
    description   text        NOT NULL,
    photo_key     text,
    product_id    text UNIQUE,
    price_tier    text,
    is_free       boolean     NOT NULL,
    is_explicit   boolean     NOT NULL,
    status        text        NOT NULL DEFAULT 'DRAFT'
        CHECK (status IN ('DRAFT', 'IN_REVIEW', 'PUBLISHED', 'REJECTED', 'REMOVED')),
    featured_rank int,
    created_at    timestamptz NOT NULL DEFAULT now(),
    updated_at    timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE clips
(
    id             uuid PRIMARY KEY,
    pack_id        uuid        NOT NULL REFERENCES packs (id) ON DELETE CASCADE,
    role           text        NOT NULL CHECK (role IN ('START', 'REMINDER', 'NAG', 'CONGRATS')),
    caption        text        NOT NULL,
    source_key     text,
    video_key      text,
    audio_key      text,
    alarm_key      text,
    thumb_key      text,
    duration_ms    int,
    status         text        NOT NULL DEFAULT 'UPLOADING'
        CHECK (status IN ('UPLOADING', 'PROCESSING', 'READY', 'FAILED')),
    failure_reason text,
    created_at     timestamptz NOT NULL DEFAULT now(),
    updated_at     timestamptz NOT NULL DEFAULT now(),
    -- One clip per role in each pack; also indexes clips by pack.
    UNIQUE (pack_id, role)
);

CREATE TRIGGER packs_set_updated_at BEFORE UPDATE ON packs
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();
CREATE TRIGGER clips_set_updated_at BEFORE UPDATE ON clips
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();
