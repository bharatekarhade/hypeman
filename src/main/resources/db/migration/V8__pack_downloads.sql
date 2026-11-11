-- One row each time someone downloads a pack's clips, for the admin's per-pack numbers.
CREATE TABLE pack_downloads
(
    id            uuid PRIMARY KEY,
    pack_id       uuid        NOT NULL REFERENCES packs (id) ON DELETE CASCADE,
    -- Null once the user deletes their account: the download still counts, but no longer says who.
    user_id       uuid REFERENCES users (id) ON DELETE SET NULL,
    downloaded_at timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX pack_downloads_pack_id_idx ON pack_downloads (pack_id);
CREATE INDEX pack_downloads_user_id_idx ON pack_downloads (user_id) WHERE user_id IS NOT NULL;
