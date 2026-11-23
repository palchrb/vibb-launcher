-- A one-time notice in the PWA the parent has dismissed, by its key (`handlers::updates`): today only
-- `image_script_signed`, Software Updates' line on a Docker install that update-image.sh must be replaced once. The
-- server can't see the host's script, so the line stays until dismissed; a missing row shows it.
CREATE TABLE dismissed_notices (
    key TEXT PRIMARY KEY NOT NULL,
    dismissed_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ', 'now'))
);
