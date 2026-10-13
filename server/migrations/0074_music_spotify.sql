-- Spotify (design 26c §2-§3). The family's own developer app (bring your own: the server ships no
-- client ID; a PKCE client ID is public, so it is stored in the clear), the Spotify accounts the
-- parent logged in (each refresh token sealed with the music key, AAD spotify-v1:<fp>:<user>), the
-- account a phone plays as (NULL = Spotify off), and a listing's Spotify state. music_entries.source
-- already allows 'spotify' (0049); a Spotify item is state 'ok' with url NULL like a Storytel book (0072).
-- Written as 0073 on the branch `music-3-spotify` (main at 0072); renumbered 0074 at the merge of main
-- (571f86f) on 2026-10-13: main's 0073 is design 30's music_data_and_storage. If 0073 reaches a server
-- after this one ran, sqlx applies it out of order and `startup::pre_migration_backup` (MAX(version)
-- only) takes no backup first: design 30's server ships before or together with this one.
CREATE TABLE music_spotify_app (
    id INTEGER PRIMARY KEY CHECK (id = 1),
    client_id TEXT NOT NULL,
    redirect_uri TEXT NOT NULL,
    saved_at TEXT NOT NULL
);
CREATE TABLE music_spotify_accounts (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    spotify_user TEXT NOT NULL UNIQUE,
    display_name TEXT NOT NULL,
    ciphertext BLOB NOT NULL,
    nonce BLOB NOT NULL,
    key_fingerprint TEXT NOT NULL,
    scopes TEXT NOT NULL,
    authorized_at TEXT NOT NULL,
    generation INTEGER NOT NULL DEFAULT 1,
    refresh_ok_at TEXT,
    refresh_error TEXT,
    refresh_error_at TEXT,
    needs_login INTEGER NOT NULL DEFAULT 0 CHECK (needs_login IN (0, 1))
);
ALTER TABLE device_policy ADD COLUMN music_spotify_account INTEGER REFERENCES music_spotify_accounts(id);
ALTER TABLE music_listings ADD COLUMN spotify_mode TEXT CHECK (spotify_mode IN ('list', 'context'));
ALTER TABLE music_listings ADD COLUMN spotify_snapshot TEXT;
ALTER TABLE music_listings ADD COLUMN spotify_items_at TEXT;
ALTER TABLE music_listings ADD COLUMN spotify_reader INTEGER;
ALTER TABLE music_listings ADD COLUMN spotify_skipped INTEGER NOT NULL DEFAULT 0;
-- A phone's library holds Spotify entries only while it plays as an account (§3.6), and says each
-- entry's mode: both move the cached libraries.
CREATE TRIGGER device_policy_spotify_revision AFTER UPDATE OF music_spotify_account ON device_policy
WHEN OLD.music_spotify_account IS NOT NEW.music_spotify_account
BEGIN UPDATE music_library_revision SET revision = revision + 1 WHERE id = 1; END;
CREATE TRIGGER music_listings_spotify_mode_revision AFTER UPDATE OF spotify_mode ON music_listings
WHEN OLD.spotify_mode IS NOT NEW.spotify_mode
BEGIN UPDATE music_library_revision SET revision = revision + 1 WHERE id = 1; END;
