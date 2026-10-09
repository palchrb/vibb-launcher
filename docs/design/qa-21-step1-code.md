# QA 21 step 1 + 21a code review - de00c64f, 05ab9fac, fd0eb1b1, 2f088f4a, ed22e6f0 (2026-10-09)

Scope: `git diff e2d7c1ec ed22e6f0 -- server launcher NOTICE.md scripts` against design 21 (§1, §6 step 1, the QA
review, the decisions and the step 1 status) and 21a (with its implementation status). Paths below are under `server/`
unless they say otherwise.

Build: a worktree of ed22e6f0. Server: `cargo test` 292 passed, `cargo fmt --check` clean, `cargo clippy --all-targets`
17 warnings, the same 17 as e2d7c1ec (no new ones). Launcher: `./gradlew --no-daemon testDebugUnitTest
-PwarningsAsErrors=true` succeeds (732 tests, 0 failures). PWA: the implementer's rendered pages at 360 px in headless
Chromium, light and dark, plus the same pages with longer names (finding 4). A release-mode timing probe of
`music::device_library` (finding 3; not committed). Not tested on the Pi or a phone.

## High

1. **High: the server can't write its music key file on an installed Pi, so Storytel is off on every install.**
   `music_secret::load` falls back to `music-secret.key` in the working directory (`src/music_secret.rs:22`,
   `src/main.rs:157-169`) and creates it with `create_new` (`src/music_secret.rs:110-122`). The systemd unit that
   `deploy/install.sh` writes has `ProtectSystem=strict` and `ReadWritePaths=$INSTALL_DIR/data` (`deploy/install.sh:267-269`),
   so `/opt/kid-phone-server` is read-only for the service. No step-1 commit changes the deploy scripts.
   Scenario: a Pi updated to 0.21.0 with `update.sh` has an old `.env` without `MUSIC_SECRET_KEY` (QA #10's case),
   and so does a fresh `install.sh`, whose generated `.env` doesn't set it either. At every start the open fails with
   EROFS, the server logs "Storytel logins are off: can't create music-secret.key: Read-only file system", and
   `music_key` is `None`. The Storytel card shows "Storytel is off" and the form is gone (`templates/music.html:185-186`).
   The page then says to "let the server write its key file", which it can't. `DEPLOY.md:179-183` says the server
   writes it at its first start. The tests pass because `TestApp` injects a key (`src/tests/mod.rs`) and
   `music_secret::tests` write into a temp dir. QA #10's fix doesn't work in production.
   Suggested fix: default the key file to a path the unit can write that is still outside every backup, e.g.
   `data/keys/music-secret.key` in a 0700 directory. The backup zip takes only the DB snapshot and the three image
   stores (`handlers/backups.rs` `build_backup_zip`), and `backup_sync.sh` copies only `data/backups/` and
   `live_mirror.db`, so a key there is in no backup. Keep `MUSIC_SECRET_KEY` and `MUSIC_SECRET_KEY_FILE` as they are,
   and correct DEPLOY.md and the card's text. Changing the unit to add a second `ReadWritePaths` would need
   `install.sh` to be re-run, because `update.sh` doesn't rewrite the unit. Having root generate the key into `.env`
   goes against "the root-run scripts never read `.env`". Add a test that `load` reports a clear error for a
   read-only directory, and a DEPLOY smoke step that checks the startup log line.

## Medium

2. **Medium: uploading a missing own file again adds a duplicate track instead of restoring it.** Missing files
   (a restore without the audio) keep their row with `missing = 1`, so the phones keep their copy (QA #3). The entry
   page says "upload it again to restore it" (`templates/music_entry.html:103`), and so does DEPLOY.md ("upload
   them again to restore them", `DEPLOY.md:185`). `upload_files` always inserts a new row
   (`src/handlers/music.rs:1648-1675`) and never looks for a missing row with the same SHA-256 in the entry. The
   stored path is `<entry>/<random>.<ext>`, so the parent can't put the file back by hand either.
   Scenario: a Pi rebuilt from a backup has "Bilturen" with 12 missing files. The parent follows the hint and uploads
   the 12 files. The entry now has 24 rows, and every phone's library lists 24 tracks: the 12 old ones (still on the
   phone, `missing`) and 12 new ones with the same hashes. The album plays every song twice until the parent deletes
   the 12 missing rows one by one. Plain double uploads of the same file are also never caught.
   Suggested fix: in `upload_files`, after hashing, look up a row in this entry with the same `sha256`. If that row
   is `missing`, move the file to that row's path (or update `path`), clear `missing` and the tags, and keep its `id`,
   so the phones' `id:sha` handover marks stay valid. If the row isn't missing, refuse the file as "already in this
   entry" and delete the temp file. Add a test for the restore-then-reupload path.

3. **Medium: the library is rebuilt from scratch for every policy poll, every cover request and every import tick,
   the last inside the import's write transaction.** `music::policy_music` calls `device_library` on every
   `GET /api/devices/policy` (`src/music.rs:890-896`). It reads every ticked entry and file, then serialises the whole
   library twice (body and document) and hashes it. `music_api::cover` builds the whole library to check one hash
   (`src/handlers/music_api.rs:107-113`), so a phone that prefetches N covers costs N full builds. `tick_fits` builds
   it again for each (phone, entry) pair (`src/music.rs:866-874`), and the import calls it in a loop after its first
   INSERT (`src/handlers/music_import.rs:353, 456-478`). So the import holds SQLite's write lock for phones × imported
   entries × one library build.
   Measured (release build, this sandbox's aarch64 server CPU, which is several times faster than a Pi Zero 2 W): an
   own-files entry with 2,000 files is a library of 627,209 bytes and one build takes 46 ms (198 ms in a debug build).
   Scenario: a phone has a 2,000-track own-files entry, and the parent imports 60 Vibb entries ticked on 3 phones. The
   import makes 180 builds with the write lock held, about 8 s here and several times that on the Pi. During
   that time every phone's status POST and the admin's session writes wait for `busy_timeout` (5 s) and then fail.
   `device_api::status` drops a failed INSERT silently. The same phone prefetching its 50 covers costs 50 builds (2.3 s of CPU here).
   This also matters for 21b: once the server keeps feed metadata, building and hashing one document per request
   won't scale.
   Suggested fix: keep a library revision (a counter in a singleton row, bumped in the same statement or transaction
   as every write that changes any library), and cache `(device, revision) -> Library` in `AppState`. Answer the
   cover check with one scoped query (`cover_hash = ? AND entry ticked` UNION `art_hash = ? AND the file's entry
   ticked`). In the import, compute each phone's current size once before the write transaction and add per-entry
   deltas, or tick everything and then take back what doesn't fit, with one build per phone. For 21b, keep episode
   listings out of this document (a per-entry listing with its own ETag), so the 3 MB guard and the version stay
   about the library's definition.

## Low

4. **Low: the entry page scrolls sideways at 360 px when the entry's name has one long word.** The `.page-header`
   flex row puts the `<h1>` and "Back to the library" side by side (`templates/music_entry.html:18-21`), and the h1
   can't shrink below its longest word. Measured with the implementer's rendered pages in Chromium at 360 px:
   "Godnatthistorier" gives a page 403 px wide, and "Sommerferieeventyrsamling" 618 px. "Barnetimen" and
   "Radioteatret" fit. Norwegian compounds of 14 characters or more are common, and RSS titles become names
   automatically. Smaller cases: an upload problem with an underscore file name ("01_Hjulene_paa_bussen_Barnekoret.mp3:
   not an audio file ...", `music_entry.html:84-88`) gives 369 px. A device-card warning with a 60-character
   one-word entry name (`templates/device_detail.html:267-269`) gives 593 px. Light and dark behave the same.
   Suggested fix: `.page-header h1 { min-width: 0; overflow-wrap: anywhere; }` (and `flex-wrap: wrap` on
   `.page-header`), plus `overflow-wrap: anywhere` on `.error`. Both rules are global in `static/style.css`; bump its
   `?v=`. A render test at 360 px with a long one-word name would pin it.

5. **Low: an upload can fill the data disk.** The 1 GB floor is checked once before each file
   (`src/handlers/music.rs:1587-1593`) and never while it streams (`receive`, `:1461-1492`).
   Scenario: with 1.1 GB free, a 1 GB audiobook passes the check and leaves about 0.1 GB. Or two browser tabs each
   upload a 900 MB file with 1.5 GB free: both pass, and the card fills until a write fails with ENOSPC. The SQLite
   database, its WAL and the session store share that card.
   Suggested fix: in `receive`, re-check `free_bytes` every ~64 MB and stop with `ReceiveError::Disk` (the temp file
   is deleted on drop) once free space would fall below the floor, e.g. `free - remaining < MIN_FREE_BYTES` with
   `Content-Length` when the part has one. Alternatively, keep one upload at a time with a semaphore, like photos.

6. **Low: the count limits are checked outside a transaction.** These limits are read first and written in a
   separate statement:
   - `add_link`/`add_own` count entries (`src/handlers/music.rs:505-512`, `:591-598`) and then insert (`:540`, `:607`);
   - `upload_files` counts an entry's files once at the start (`:1554-1562`);
   - `set_device_entry` runs `tick_fits` and then inserts (`:2049-2071`).

   Two concurrent requests (two tabs, a double tap on a slow Pi) can both pass. Example: two uploads into an entry
   with 290 files reach 300+. Two quick ticks on a phone at 2.9 MB can pass 3 MB together. The import does this
   properly, inside its transaction.
   Suggested fix: do the check and the write in one `BEGIN IMMEDIATE` transaction (or an `INSERT ... SELECT ... WHERE
   (SELECT COUNT(*) ...) < ?`), as the import does.

7. **Low: restoring an older backup leaves own files orphaned and reuses ids.** A restore swaps in only the database.
   Own files uploaded after the backup stay in `data/music_files/<id>/` with no row. Nothing ever removes them:
   `flag_missing_files` only goes from rows to disk (`src/music.rs:1021-1054`), and only a delete of that entry id
   removes the directory. The restored `sqlite_sequence` hands out the same entry and file ids again.
   Scenario: Monday's backup ends at entry 6. On Tuesday the parent adds own entry 7 (2 GB, key `own-7`) and the kid
   listens. On Wednesday Monday's backup is restored. The 2 GB stay on the card and count against the 1 GB floor
   (finding 5). The next own-files entry is id 7 again, so it gets key `own-7` and the same directory. The music app
   keys positions and downloads by entry key, so the new album can pick up the old one's bookmarks.
   Suggested fix: at startup, list `music_files/<entry>/` files that no row references. Log their total size on the
   Music page ("N files, X GB on disk that no entry uses") with a "delete" button rather than deleting them silently,
   because the pre-restore DB copy might be put back. Make the own key unique over time, e.g. `own-<random 12 hex>`
   chosen at insert, instead of `own-<id>`. That is a contract change, so do it before the music app exists.

8. **Low: the device card's anchors land at the card's top, not at the control.** `set_device_entry` and
   `save_device_settings` redirect to `/devices/{id}#music` (`src/handlers/music.rs:2092`, `:2141`). A fragment turns
   off scroll-restore (`static/scroll-restore.js` `restoreTarget`), so the window always lands on the card's heading.
   The 320 px tick list (`.app-checkbox-list`) starts again from its top.
   Scenario: the volume cap and the "Storytel on this phone" switch sit below the tick list, about 550-760 px under
   the card's heading. After saving the Storytel switch on a 740 px phone screen, the switch is just below the fold.
   After ticking entry 30 of 40, the list shows entries 1-8 again. `delete_file` → `#files` (`:1785`) does the same
   on an entry with 150 files. It's not a jump to the page top, but the parent loses their place on every save.
   Suggested fix: give the settings form `id="music-settings"` and each tick row `id="music-entry-<id>"`, and
   redirect to those. Have `delete_file` redirect to the next file's `#file-<id>`.

9. **Low: a half-written key file locks Storytel off for good.** `create_key_file` opens with `create_new` and then
   writes (`src/music_secret.rs:110-129`).
   Scenario: a crash, a power cut (common on a Pi) or a full disk between the open and the write leaves an empty or
   partial file. Every later start reads it, fails with "is not 32 bytes of base64" (`:101-103`) and never makes a
   new one. Only deleting the file by hand helps, and nothing tells the parent that.
   Suggested fix: write `<file>.tmp` (0600), fsync, rename into place, and fsync the directory. Treat an empty file
   as absent. Keep refusing a non-empty invalid one, because that could be a real key that got mangled.

10. **Low: the add check refuses real podcast feeds over 5 MB.** `HttpFetch` reads the whole body up to
   `MAX_FEED_BYTES` and fails with `TooBig` past it (`src/music.rs:48`, `:343-359`), only to find a `<channel>`
   title and one `<enclosure url=`. Long-running podcasts with full show notes can exceed 5 MB, and the parent then
   can't add them at all ("That feed is over 5 MB - not added."). The spec says "<= 5 MB", so this follows the text.
   Suggested fix: stop reading once `rss_title` can decide (a channel title before the first item plus one item with
   an enclosure), or check only the first 5 MB and accept a feed that already qualifies within it. 21b moves feed
   parsing to the server, and it will meet the same feeds.

## Test gaps that matter

- Nothing exercises the key's default location under the shipped unit (finding 1). A DEPLOY smoke step that greps
  the startup log for "Storytel logins are off" would have caught it.
- No re-upload of a missing file (finding 2), and no double upload of the same file.
- Hostile audio beyond "not audio": a truncated MP4 with a huge atom size, or an ID3 `APIC` frame that claims 100 MB.
  Add a test that pins "refused, temp file gone, server alive". lofty 0.25.4's 16 MiB `allocation_limit` and
  `spawn_blocking` (a panic means refused; the release profile unwinds) make this safe today. Nothing pins it.
- No import against a phone that already has a large own-files library (finding 3).
- No `delete_entry` while an upload into that entry is in flight. By reading the code it's fine: the rename fails
  or the INSERT hits the foreign key and the file is removed. Nothing pins it.

## Notes for 21b (not findings)

- `music::Fetch` returns only the status and body. Server sweeps need conditional GETs (ETag, If-Modified-Since) and
  must see permanent redirects, so the trait needs request and response headers. `target` is UNIQUE and `key` is
  stored separately, so a sweep that updates `target` after a 301 keeps the phones' keys.
- `rss_title`/`xml_text`/`find_tag` answer "is this a feed?" well enough. Don't build episode metadata on them: use a
  real XML parser.
- `music_entries.cover_hash` is the parent's override. Server-fetched source art needs its own column, plus the
  extra `MUSIC_COVERS` `referenced_sql`/`forget_sql` entries (now a list, so that's easy).
- `music_state.entry_errors` comes from the phone. With server-side verification the device card will need to tell
  the two apart.

## Checked and fine

- **Migration 0049 on an existing DB:** only new tables and defaulted columns; every NOT NULL column has a default.
  The guarded UPDATE touches only `is_launcher = 1` rows whose cached tag starts with `launcher-v`, and keeps a
  non-blank filter. The INSERT runs only next to such a row, only once, and copies its repo. `music_storytel`
  seeds id 1. Tested on a 0048 DB with data (monorepo row, other app, fork row on `pre-release`). `DevicePolicy`,
  `DeviceStatus` and `TrackedApp` read the new columns by name. The 26-column status INSERT matches.
- **The catalog row fix:** `release_allowed` never takes `server-v*`. A prefix takes only its own tags, and no
  prefix never takes `music-v*` (`src/handlers/tracked_apps.rs:186-195`). The launcher's blank filter becomes
  `^kids-launcher-mdm\.apk$` and the music row uses `^vibb-music\.apk$`, stable only. The list call uses
  `per_page=100`, which is fine with interleaved music RCs. Unit test `launcher_and_music_rows_keep_to_their_own_releases`.
  The prefix is validated, editable, and errors land by the field.
- **Storytel crypto:** AES-256-GCM (aes-gcm 0.11), a fresh 96-bit `OsRng` nonce per seal, AAD
  `storytel-v1:<fingerprint>`. The fingerprint is compared before opening, and another key or a changed byte means
  `None` → 503. The fields are never prefilled or echoed on a 400. The login never reaches an event or a log; the
  test scans the DB files after a checkpoint. The device route is `no-store`: 404 when the switch is off or nothing
  is stored, 503 without a key or with another key. The generation goes up on save and clear, and the policy sends 0
  unless the switch is on and a login is stored. The key file is created 0600 with `create_new`, and looser
  permissions are tightened on read.
- **Uploads:** each file is streamed to `<random>.part`, hashed on the way, at most 1 GB per file and 4 GB per
  request. Stored names are random, and `file_path` refuses `..`, absolute paths and odd characters. The browser's
  name is cut to its last component, without control characters. lofty sniffs by content, in `spawn_blocking`.
  Embedded art goes through `photos::process` (10 MB cap, decode limits, re-encode, no metadata). `TempFile` drop
  removes the temp file, and `.part` files are swept at startup.
- **Cover pipeline:** store and DB update happen under the `MUSIC_COVERS` lock, with a prune after. Covers are in
  the backup zip under `music_covers/` and recovered at startup (a lost one is forgotten from both tables).
- **21a tampering:** the hidden document is re-read by the same `parse` with the same caps. `plan` runs again
  inside the transaction against the current library (targets, keys, 200). Category and phone ids are checked
  against the DB. Malformed `row`/`phone`/`category_*` values give a 400 with the preview, and nothing is stored.
  Body limits are 1 MB + 64 KB and 4 MB. The fake fetcher records no call. `tick_fits` runs on the transaction's
  connection, so it sees the uncommitted inserts.
- **Admin-only and CSRF:** every `/music*` and `/devices/{id}/music*` route is in `admin_routes` behind
  `require_full_auth`, and a test posts each one without a session and checks nothing changed. The session cookie
  is the same as on every other page (tower-sessions 0.14's default `SameSite=Strict`). The new templates have no `|safe`, the
  hidden `doc` is attribute-escaped, and entry links are only ever http(s).
- **Device API scoping:** the library holds this phone's ticks only (test). A cover must be a hash in this phone's
  library. A file must be joined through `device_music_entries` with `source = 'own'`, and `missing` gives a 404.
  Storytel follows the phone's own switch. Bearer auth is on all four routes.
- **Contract:** `music` is always in the policy and `null` on a query error (test), and the policy never fails.
  Server `policy_json_keys_snapshot` (keys plus the fresh-phone object) and launcher `PolicyResponseCompatTest`
  (key set; null, missing and odd shapes; cache round trip) agree. The DTO is a raw nullable `JsonElement`.
  `testdata/music_library.json` is the served bytes pretty-printed. `files[].missing` is the documented addition.
  The version is 16 hex of SHA-256 without `version` (QA #4). The ETag equals the version. `If-None-Match`
  (list, weak, `*`) gives a 304 with the ETag. gzip comes with `Vary`. The library is a 404 while nothing is ticked,
  with a `null` version in the policy.
- **Nudges:** each write nudges exactly the phones whose library or policy changes:
  - an entry edit, cover or file change: the phones with that entry;
  - a category edit: the phones with entries in it;
  - a move: every phone with ticks, since all sorts are renumbered;
  - Storytel: the phones with the switch on;
  - ticks and settings: that phone;
  - the import: the ticked phones;
  - a delete: the phones read before the delete;
  - a new entry or category: nothing.
- **Deletes:** an entry delete cascades to ticks and files (foreign keys are on, `src/main.rs:739`), removes the
  directory, prunes covers and nudges the phones read beforehand. A category in use is refused by the handler and
  by the foreign key. A file delete removes the row and the file, re-sorts and prunes. AUTOINCREMENT stops id and
  directory reuse, except after a restore (finding 7).
- **Missing files:** they are flagged at startup and on a 404 while serving, listed with `missing: true`, never
  dropped, and served as a 404.
- **PWA:** the text is English (Norwegian only in the seeded category names). Every form redirects to its anchor or
  its own path. A refused save re-renders with the values and autofocuses the field (the categories' `<details>`
  open). The import lands at `#import`. Baseline pages at 360 px have no horizontal scroll in light or dark.
- **Licensing:** the music icons come from the pinned Material Symbols commit with hashes, carry a "Converted from"
  line each, and have `LICENSE.txt` and a NOTICE.md entry. White on every tile colour is at least 3:1 (lowest: gold
  at 3.2:1).
- **Tests and lint:** server 292 passed, fmt clean, clippy 17 = 17; launcher 732 passed with
  `-PwarningsAsErrors=true`.

## Fixes (implementer, 2026-10-09)

All ten findings and the test gaps are fixed: server `cargo test` 302 passed, fmt clean, clippy 17 (no new ones).
1. The key file defaults to `data/keys/music-secret.key` (0700 directory made by the server), the one place the unit
   can write; no backup copies it (zip: DB + image stores; live mirror and external drive: the DB and `data/backups/`;
   `update.sh`: DB files). `MUSIC_SECRET_KEY` and `MUSIC_SECRET_KEY_FILE` stay. A read-only directory is a clear error
   naming the file (test); the card text, DEPLOY.md and a startup line ("sealed with ...") are corrected, and DEPLOY.md
   has a `journalctl` smoke check.
2. An upload with the SHA-256 of a `missing` file of the entry is put back at that row's path (same id; its art is
   made again when lost); the same content otherwise is refused as already in the entry (`keep_upload`).
3. Migration `0050_music_library_revision.sql`: triggers on the four library tables move one revision;
   `music::cached_library` keeps each phone's library under it (built in one read transaction). Policy, library route
   and device card use it; covers are one scoped query; the import builds each phone once plus one confirming build
   (per-entry size estimate, then take back from the end). Tests count builds. For 21b: its listings table needs the
   same triggers (or `bump_library_revision`), and its migration becomes 0051.
4. `.page-header` wraps, its `h1` and `.error`/checkbox-row text break long words (`style.css?v=6`); checked in
   Chromium at 360 px, light and dark, with the cases above (no horizontal scroll). The Storytel switch's label, which
   broke into columns when no login was saved, is one span now.
5. `receive` re-checks the free space every 64 MB (`DiskGuard`) and stops below 1 GB.
6. The 200-entry and 300-file limits are the INSERT's own `WHERE`; a tick's size check and insert share one
   `BEGIN IMMEDIATE`.
7. Own keys are `own-<12 random hex>`. Files no row names are listed on `/music` ("Files no entry uses", count and
   size, files younger than 10 minutes left out) with a delete button; nothing is deleted by itself.
8. Ticks return to `#music-entry-<id>` (a refused tick shows its notice by that row), the settings to
   `#music-settings`, a file delete to the next file's `#file-<id>`.
9. The key file is written through `<file>.tmp` (0600, fsync), renamed, the directory synced; an empty file is made
   again, a non-empty invalid one is refused with "fix it, or remove it".
10. **Bounded read**, not a streaming parse: `Fetch::get(url, limit)` returns the first `limit` bytes and `truncated`.
    The add check only needs the channel title and one enclosure, which a feed puts first, so a feed is judged by its
    first 5 MB and long feeds are accepted; one whose first 5 MB hold no episode is refused with that reason. The
    sweeper (21b) can call the same trait with its own limit and see whether it got the whole feed; episode parsing
    should still get a real XML parser there (QA's note).

Test gaps: key in a read-only directory (`music_secret` test), re-upload and double upload, hostile MP4/ID3 audio
(refused, nothing left, server goes on), an import against a 2,000-file library (build count), an upload into an
entry deleted meanwhile (`keep_upload`, both orders).
