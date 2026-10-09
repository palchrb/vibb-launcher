-- Vibb music, phase 1 (design docs/design/21-vibb-music-phase1.md at the monorepo root, with its QA
-- review and decisions): the server holds the library *definition* and the parent's own uploads. The
-- phone fetches NRK and RSS from the source; this server never proxies or stores them.
--
-- Only new tables and new columns with defaults, plus two guarded catalog updates at the end: safe on
-- an existing database (nothing is dropped or rewritten, no existing row changes meaning).

-- The kid's category tiles (Alle + these). icon/color are keys of testdata/music_icons.json;
-- default_kind marks the category a new entry gets by its source (music: own files and Spotify,
-- audiobook: Storytel, podcast: NRK and RSS). A category in use can't be deleted (the foreign key
-- below, and the handler says so first).
CREATE TABLE music_categories (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    name TEXT NOT NULL,
    icon TEXT NOT NULL,
    color TEXT NOT NULL,
    sort INTEGER NOT NULL DEFAULT 0,
    default_kind TEXT CHECK (default_kind IS NULL OR default_kind IN ('music', 'audiobook', 'podcast'))
);
INSERT INTO music_categories (name, icon, color, sort, default_kind) VALUES
    ('Musikk', 'music_note', 'rose', 10, 'music'),
    ('Eventyr', 'star', 'gold', 20, NULL),
    ('Lydbøker', 'menu_book', 'terracotta', 30, 'audiobook'),
    ('Podkast', 'mic', 'teal', 40, 'podcast');

-- One library entry (a cover in the kid's carousel). `target` is the normalised source URL (unique;
-- NULL for own files), `key` vibb's state_key (the NRK podkast slug, else sha1(target)[:12];
-- "own-<id>" for own files) - the music app keys positions and downloads by it. `cache`: -1 all,
-- 0 none, 1-100 the newest N (own files: always all). Storytel and Spotify are refused until their
-- phase, but the column already allows them.
CREATE TABLE music_entries (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    name TEXT NOT NULL,
    category_id INTEGER NOT NULL REFERENCES music_categories(id),
    source TEXT NOT NULL CHECK (source IN ('nrk', 'rss', 'own', 'storytel', 'spotify')),
    target TEXT UNIQUE,
    key TEXT NOT NULL UNIQUE,
    play_order TEXT NOT NULL DEFAULT 'auto' CHECK (play_order IN ('auto', 'newest_first', 'oldest_first')),
    cache INTEGER NOT NULL DEFAULT 5 CHECK (cache = -1 OR (cache >= 0 AND cache <= 100)),
    resume INTEGER NOT NULL DEFAULT 1,
    cover_hash TEXT,
    sort INTEGER NOT NULL DEFAULT 0,
    created_at TEXT NOT NULL DEFAULT (datetime('now'))
);

-- The parent's own uploads, one row per audio file of an own-files entry. `path` is relative to
-- data/music_files/ ("<entry id>/<random>.<ext>"); the audio is never in a backup, so a file that
-- isn't on disk (a restore on another box) is flagged `missing` and still listed - the phones keep
-- their copy (QA #3). Tags come from the file (lofty); `art_hash` is its embedded picture as a
-- 512 px JPEG in the music cover store. `sort` is track order when every file has a track number,
-- else file-name order (vibb).
CREATE TABLE music_files (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    entry_id INTEGER NOT NULL REFERENCES music_entries(id) ON DELETE CASCADE,
    path TEXT NOT NULL,
    original_name TEXT NOT NULL,
    size INTEGER NOT NULL,
    sha256 TEXT NOT NULL,
    title TEXT,
    artist TEXT,
    album TEXT,
    track_no INTEGER,
    duration_ms INTEGER,
    art_hash TEXT,
    sort INTEGER NOT NULL DEFAULT 0,
    missing INTEGER NOT NULL DEFAULT 0,
    created_at TEXT NOT NULL DEFAULT (datetime('now'))
);
CREATE INDEX music_files_entry ON music_files(entry_id);

-- Which entries each phone has (the device page's Music card).
CREATE TABLE device_music_entries (
    device_id INTEGER NOT NULL REFERENCES devices(id) ON DELETE CASCADE,
    entry_id INTEGER NOT NULL REFERENCES music_entries(id) ON DELETE CASCADE,
    PRIMARY KEY (device_id, entry_id)
);

-- Per phone: downloads and streaming may use mobile data (default off); the volume cap in percent
-- (NULL = off, the default; the PWA offers 90/80/70/60); this phone gets the family's Storytel login.
ALTER TABLE device_policy ADD COLUMN music_mobile_data INTEGER NOT NULL DEFAULT 0;
ALTER TABLE device_policy ADD COLUMN music_volume_cap_pct INTEGER;
ALTER TABLE device_policy ADD COLUMN music_storytel INTEGER NOT NULL DEFAULT 0;

-- The family's Storytel login, AES-256-GCM with the server's own key (MUSIC_SECRET_KEY or its key
-- file outside data/), so a database backup holds no usable password. `key_fingerprint` names the
-- key it was sealed with (also in the AAD): another key reads as "enter it again". `generation` goes
-- up by one on every save and clear; the phones compare it with `!=`.
CREATE TABLE music_storytel (
    id INTEGER PRIMARY KEY CHECK (id = 1),
    ciphertext BLOB,
    nonce BLOB,
    key_fingerprint TEXT,
    saved_at TEXT,
    generation INTEGER NOT NULL DEFAULT 0
);
INSERT INTO music_storytel (id) VALUES (1);

-- What the phone reports about music (`music_state`, known fields only).
ALTER TABLE device_status ADD COLUMN music_state_json TEXT;

-- A catalog row follows only releases whose tag starts with this (NULL: every release except the
-- monorepo's server-v* and music-v* ones). The launcher's row must never pick up vibb-music.apk.
ALTER TABLE tracked_apps ADD COLUMN release_tag_prefix TEXT;

-- The launcher row of the monorepo: only launcher-v* releases, and only its own APK when no filter
-- was set. Only where the cached release already is a launcher-v* one (QA #10): a row watching
-- another repo or tag scheme is left alone.
UPDATE tracked_apps
SET release_tag_prefix = 'launcher-v',
    asset_pattern = CASE
        WHEN asset_pattern IS NULL OR trim(asset_pattern) = '' THEN '^kids-launcher-mdm\.apk$'
        ELSE asset_pattern
    END
WHERE is_launcher = 1 AND substr(latest_release_tag, 1, 10) = 'launcher-v';

-- The music app's catalog row, from the same repo (music-v* releases, vibb-music.apk, stable only),
-- named "Musikk" with the music note on peach on the kid's phone (design 14 defaults). Only next to
-- such a launcher row, and only once. Phones get it when the parent selects it on a phone's page.
INSERT INTO tracked_apps (name, package_name, source_type, github_repo, asset_pattern,
                          include_prereleases, release_tag_prefix, display_label, display_icon,
                          display_color)
SELECT 'Vibb Musikk', 'me.vibb.music', 'github', github_repo, '^vibb-music\.apk$', 0, 'music-v',
       'Musikk', 'music_note', 'peach'
FROM tracked_apps
WHERE is_launcher = 1 AND release_tag_prefix = 'launcher-v'
  AND NOT EXISTS (SELECT 1 FROM tracked_apps
                  WHERE package_name IN ('me.vibb.music', 'me.vibb.music.debug')
                     OR release_tag_prefix = 'music-v')
ORDER BY id
LIMIT 1;
