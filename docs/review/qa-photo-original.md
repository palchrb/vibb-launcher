# Code QA: photo-original (contact photos keep a downscaled original for a re-crop)

Branch `photo-original` (7f7bc4e7, 07c79cd7) against main, `git diff main...photo-original`: migration 0123,
`photos.rs` (`process_contact`, `View`, `view_square`, `recut`, `CONTACT_ORIGINALS`), `calls.rs` (`upload_photo`,
`commit_photo`, `view_photo_original`), `backups.rs`, `main.rs` (state, route), `device_calls.html`, `photo-crop.js`
v2, both i18n catalogs, the tests and the notes. Read-only on code.

## Setup

- Worktree `/workspace/wt-photo-original`. I read the whole diff and, around it, `photos::Store` (prune, `add_to_zip`,
  `recover_missing`, `extract_from_backups`), `path_for`/`is_valid_hash`, `build_admin_router`/`build_device_router`,
  `admin_auth::admin_headers`/`compress_when`, `templates/sw.js`, `startup::pre_migration_backup`, `device_api.rs`'s
  photo route, `device_avatar.rs`, and lock-weather's 0122 and its `migrations_from_73_in_order`.
- `node --test server/jstest/`: **302 passed, 0 failed**.
- `cargo test` (no emulator running, 27 GB free, under `~/build.lock`, `CARGO_TARGET_DIR=~/cargo-targets/photo-original`):
  **1679 passed, 0 failed** (589 s).
- A throwaway probe, in a copy of `server/` in my scratchpad, not on the branch. I made a 1200x800 JPEG with an EXIF APP1
  (orientation 6, Make "SecretCam", Model, BodySerialNumber, a GPS IFD with lat/long, and an IFD1 thumbnail: a second
  JPEG carrying a `THUMBMARK` COM), plus an XMP APP1 with GPS, an ICC APP2 and a COM segment. I ran it through
  `process_contact` and `recut` and walked the markers:
  - The kept original is **800x1200** (upright). Its markers before SOS are only APP0 (JFIF), DQT, SOF0 and DHT.
    None of `Exif`, `GPS`, `SecretCam`, `SERIAL`, `THUMBMARK`, the XMP namespace, `ICC_PROFILE` or the comment is
    left anywhere in the file, and there is one SOI (the thumbnail is gone).
  - The square and the re-cut square came out the same way.
  - The encoder path explains it: `encode_jpeg` (`photos.rs:367`) is `JpegEncoder::new_with_quality(..).encode_image(rgb)`
    on a fresh `RgbImage`. It never calls `set_exif_metadata` or `set_icc_profile`, and the decoder's metadata never
    reaches the pixels.
  - The same probe gave `original_size(4000,4000) = (2048,2048)` and `original_size(300,8000) = (300,8000)` (L3), and
    showed that a no-op re-cut changes the square's hash (L1).

## Counts

**0 H, 1 M, 8 L.**

## M

### M1. The photo refusals are English-only (`PhotoError::message`), now on more paths

`server/src/photos.rs:228-243` (`PhotoError::message`, six English literals). It is used at `server/src/handlers/calls.rs:1571`
(the new `refusal` closure), `:1585`, `:1603` and `:1618`; `wallpapers.rs:118`, `:133`, `:151` and `:162`; `music.rs:2843`;
and `device_avatar.rs:62` (plus the literal "Couldn't save the picture..." at `device_avatar.rs:59`).

Scenario: a parent with the PWA in Norsk picks a 48 MP PNG or an HEIC renamed `.jpg`. Under the photo it says "That photo
has too many pixels (at most 40 megapixels and 8000 pixels a side)." The new `device.calls.crop.*` lines right beside
it are in Norwegian. The project rule is nb + en on every path. This branch adds another use (`ContactPhotoError::Photo`
-> `err.message()`), so this is the moment to fix it.

Fix: replace `message()` with `PhotoError::key() -> &'static str` and look the key up with `t(lang, ..)` at each call
site (all of them have `lang`; `device_avatar::error_text` needs it passed in). Keys to add to both catalogs:

| key | en (today's text) |
|---|---|
| `photo.error.empty` | Choose a photo first. |
| `photo.error.too_large` | That photo is too big (at most 10 MB). |
| `photo.error.unsupported_type` | Use a JPEG, PNG or WebP photo. |
| `photo.error.too_many_pixels` | That photo has too many pixels (at most 40 megapixels and 8000 pixels a side). |
| `photo.error.undecodable` | That photo couldn't be read - try another one. |
| `photo.error.too_detailed` | That photo is too detailed to send to a phone (over 3 MB as a wallpaper) - try another one. |
| `photo.error.save_failed` | Couldn't save the picture - nothing was changed. Try again. (`device_avatar.rs:59`) |

`too_large` and `too_many_pixels` should take `{mb}` / `{mp}` / `{side}` from the constants rather than spell them
out. The tests that match these strings (`launcher_ui.rs`, `wallpapers.rs`, `avatars.rs`) then read them through `t()`.

## L

### L1. A no-op "Adjust crop" (or the first adjust at the same view) changes the square's hash and re-sends it to every phone

`server/src/photos.rs:552-553` against `:558-563`. `process_contact` cuts the first square from `rgb`, the pixels
*before* the q90 encode. `recut` cuts from the *decoded* q90 original. The same view therefore gives a different JPEG
(probe: `same square false`).

Scenario: the parent opens Adjust crop, looks, and presses Save without moving. A new `photo_hash` is committed,
`photo_saved` nudges every phone with the contact (`calls.rs:1513-1515`), and each phone downloads the "new" photo.

Fix (either one; the first is enough):
- In the re-cut branch, when the parsed view's `text()` equals the stored `photo_view`, redirect to the row with no
  commit and no nudge.
- Or cut the first square from `decode(&original.jpeg)` as well: one extra 3 MP decode, about 50 ms, and the first cut
  and every re-cut at a view are then byte-identical.

### L2. A kept original missing on disk fails silently in the PWA and is reported as "replaced meanwhile"

`server/static/photo-crop.js:418-428` and `server/src/handlers/calls.rs:1632-1634`.

Scenario: an original's file is gone while the server runs (a manual cleanup, a disk error; `recover_missing` runs only
at startup):
- The page still offers "Adjust crop" on `/contact-photos/original/<hash>`. The `<img>` gets a 404, `open()`'s reject
  branch closes the dialog and focuses the button, and nothing tells the parent why.
- A crafted re-cut gets `device.calls.crop.photo_changed`, which is not what happened.
- It stays so until a restart.

Fix:
- In the reject branch, when `from.original` is set, retry as the pre-0123 adjust on the stored square
  (`/contact-photos/{photo}`; the template can carry it as `data-photo-square`).
- On the server, when the file is unreadable, forget the original (`CONTACT_ORIGINALS.forget_sql` with the hash, under
  `lock_files()`) and answer with its own line (say `device.calls.crop.original_lost`).

### L3. "At most 2048×1536" is not what `original_size` does: a square photo is kept at 4.2 MP and a banner up to 8000 px long

`server/src/photos.rs:53-55` (`ORIGINAL_PIXELS` "The original's pixels at most") and `:380-388`. The same claim is in
the migration comment `0123_contact_photo_original.sql:4-5` and in `launcher-ui.md`.

`scale = max(by_long, min(by_short, by_area))`, so the pixel cap only bounds the panorama branch. The probe gives
`original_size(4000,4000) = (2048,2048)`, 4.19 MP, above the 3.15 MP "at most". A 300x8000 banner is kept whole at
8000 px long (2.4 MP). Memory and disk are fine either way (at most about 17 MB RGBA after the decode), so this is about
the documentation matching the code, not a defect.

Fix: either reword ("the long side at most 2048; a panorama's short side kept up to 1024 as long as the area stays under
3.1 MP") or apply the area cap last: `scale = by_long.max(by_short.min(by_area)).min(by_area).min(1.0)` (a square
photo becomes 1774x1774). Mirror that in `originalSize` and its pinned rows. Rewording is simpler.

### L4. Backups grow by an order of magnitude, and the privacy line doesn't mention them

`server/src/handlers/backups.rs:227` and `photos.rs:113-124`. The originals are added `Stored` (uncompressed, as JPEG).

Numbers: a 3 MP q90 photo is about 0.5-1.5 MB, against about 30-60 KB for a square. Thirty contacts with photos come to
about 30 MB per zip instead of about 1.5 MB. With the default `DEFAULT_RETENTION_COUNT = 14` that is a few hundred MB in
`data/backups/`, and `backup_sync.sh` copies every zip to the external drive. That is acceptable on the user's VM, but
noticeable on the Pi/SD-card install that `DEPLOY.md` still documents.

`device.calls.photo_privacy` says the server keeps the copy, but not that every backup zip, and so the external drive,
holds it too.

Fix:
- One sentence in `DEPLOY.md`'s backup section on the size.
- Optionally append ", and in its backups" to the privacy line in both catalogs.
- If size matters later: `recover_missing` already searches every zip newest-first, so originals could go only into
  the newest few zips. Not needed now.

### L5. The migration merge order is load-bearing and nothing enforces it; `server/CLAUDE.md` still says 0122 is next

`server/src/tests/music_spotify.rs:260-261`, `server/src/startup.rs:269-271` and `server/CLAUDE.md:278`.

With lock-weather merged first it comes out right, which I verified:
- The merge conflicts in the version array; resolve it to `(121, ..), (122, "lock weather"), (123, "contact photo original")`.
- A server at 0121 then gets `pre-migrate-0121-0123.db` before both run.
- A DB that ran only lock-weather (at 0122) gets `pre-migrate-0122-0123.db`.

The two hazards:
- **The other order.** If photo-original lands first and that `main` is run anywhere, the DB is at 0123. When 0122
  arrives, `pre_migration_backup` compares only `MAX(version)` (123 >= 123), so no backup is taken, and sqlx applies
  0122 out of order. That is harmless for 0122's content, but there is no way back.
- **A DB that has run this branch.** The user's dev DB during an emulator round, for example: going back to `main` or
  lock-weather without 0123 fails at startup (sqlx: applied migration 123 missing in the resolved migrations;
  `ignore_missing` is not set).

Fix:
- Merge only after lock-weather is on `main`, then rebase and add the `(122, ..)` row. The test as written fails on that
  rebase, which is the guard we want.
- Run this branch only on a throwaway DB until then.
- Set `server/CLAUDE.md:278` to "0122 lock weather, 0123 the kept original; the next free number is 0124".

### L6. Tests: the two partial-recovery cases are untested, and the metadata assert is weaker than the claim

`server/src/tests/launcher_ui.rs` (`backups_carry_photos_and_a_restore_gets_them_back`) and
`the_original_is_kept_and_the_square_cut_at_the_view` (the `!kept.windows(4).any(|w| w == b"Exif")` assert).

- The restore test covers "both files lost and in a backup". Two cases are not covered: an original in no backup (the
  original and view are forgotten, the square stays, and the page shows the pre-0123 adjust), and a square in no
  backup (all three columns cleared through `CONTACT_PHOTOS.forget_sql`). These are the consistency rules the branch
  depends on. Two more rows in the existing test, about 10 lines, would cover them.
- `jpeg_with_exif` is a one-entry IFD with "secret" text. My probe (GPS IFD, IFD1 thumbnail, XMP, ICC, COM) passes, so
  this is about pinning the behaviour, not a bug. A lean pin: walk the kept original's markers up to SOS and assert that
  only APP0/DQT/SOF/DHT appear (about 12 lines), which covers every APPn and COM at once.

### L7. The picked file now goes up whole, and a file over 10 MB is still refused although the new copy path could rescue it

`server/static/photo-crop.js:622` (`file.size > maxBytes` -> `plainUpload`) against `:452-457` (the copy path).

v1 uploaded a 512 px square of about 100 KB. v2 sends the picked file itself (up to 10 MB) and the server decodes it in
full: over a mobile Tailscale link, and with a decode that takes seconds on a Pi (one at a time, `PROCESSING`). That is a
fair trade for a real original.

But a 12 MB 48 MP iPhone JPEG, which the browser decodes fine, still goes up raw and comes back "too big (at most
10 MB)", although the over-limit copy at the original's size (about 1-2 MB) would be accepted.

Fix: when `file.size > maxBytes`, open the dialog anyway and on Save always take the copy path (`overLimits(..) ||
file.size > maxBytes`). Fall back to `plainUpload` only when the browser can't decode the file.

### L8. A deep zoom now stores less than 512 px

`server/static/photo-crop.js:408-409` and `server/src/photos.rs:573`.

The zoom limit is now in the kept original's pixels, and the square is never enlarged. At the 4x maximum on a 4:3 photo
the square is 1536 / 4 = **384 px**. v1 cut it from the up-to-8 MP work canvas, which gave 612 -> 512. The result is
still above the 228 px floor (the 76 dp circle at 3x), so the Home circle is unaffected. Only a phone surface that draws
the photo larger than about 128 dp (the in-call screen, the contact sheet) would see the difference.

Fix: none needed if no surface is that big. Otherwise lower `MAX_ZOOM` for picked photos to `min(kept) / 512` (and keep
230 as the floor only for the pre-0123 adjust).

## Verified, no finding

- **An original can't reach a phone or a signed-out user.**
  - `/contact-photos/original/{hash}` is in `admin_routes`, behind `require_full_auth` and the app lock
    (`main.rs:1196-1201`; the lock's router walk keeps it locked). It is not in `device_routes`, and
    `build_device_router` has only `device_routes` + `/healthz` (`main.rs:809-814`; `tests::listeners` asserts the 404).
  - The device route reads only `photo_dir` and matches only `photo_hash` (`device_api.rs:2244`). No policy names an
    original (`policy_json_keys_snapshot` unchanged; the test greps the policy).
  - A phone's bearer on the admin route is redirected (tested).
  - Caching: `private, no-store` from `jpeg_file`, which `admin_headers` doesn't overwrite (`admin_auth.rs:975-990`).
    `compress_when` skips `image/jpeg`. The service worker has no fetch handler (`templates/sw.js:185`), so nothing
    caches it.
  - Backups go through the admin session only (download and restore are admin routes).
- **Path traversal and hash injection.** `path_for` requires 64 lowercase hex (`photos.rs:656-662`) before any
  filesystem or DB access. The handler then also requires a contact with a photo naming the hash. The `original` form
  field is never used as a path: it is only compared with the contact's own `photo_original_hash`, and the path is built
  from the DB value (`calls.rs:1624-1633`). It cannot point a contact at another contact's original. There is one
  household: every signed-in parent may edit every phone's contacts, and `contact_on_device` scopes the route to a
  contact on that device.
- **Two parents at once.**
  - An adjust re-checks the original under `lock_files()` before storing and committing (`calls.rs:1640-1663`). An
    upload or removal in another tab therefore turns a stale adjust into the `photo_changed` 400, and nothing changes.
  - An upload stores both files and commits all three columns in one hold (`:1673-1695`). A prune (which takes the lock
    per store) can't delete a file between store and commit.
  - Two adjusts on the same original: the last one wins, which is fine.
- **View validation.** `View::parse` takes only four finite numbers. `view_square` rejects non-positive sides, then
  out-of-bounds, then non-square, then too-small, each with 2 px slack, and clamps the result inside (`width - side`
  can't underflow, since `side <= short`). Extreme input (1e300) is `OutOfBounds`, not a panic. The slack is generous
  enough for the cropper's fractions (6 decimals, under 0.01 px on a 5017 px side) and still bounded.
- **Orientation before the view.** The view is judged on the upright `original_size` and cut after `apply_orientation`
  (`photos.rs:524-555`). The probe's EXIF-6 1200x800 came out as 800x1200, and the branch's own test cuts the correct
  half.
- **The re-cut maths.** The stored `View::of_square(..).text()` round-trips to the same pixel square (the probe's re-cut
  view text was identical). `cut_square` never enlarges and gives q85 like the old path.
- **Lifecycle.**
  - `photos::prune` covers both stores, and every caller goes through it (`calls.rs:1458`, `:1512`; `devices.rs:2955`).
  - `CONTACT_PHOTOS.forget_sql` clears all three columns. Recovery handles the squares first, then the originals.
  - `view_photo_original`, `photo_original` and the page's query all require `photo_hash IS NOT NULL`, so "an original
    without a square" is unreachable.
  - "A square without an original" (pre-0123, or a lost original) falls back to the old adjust, whose uploaded square
    becomes the original.
  - A restored pre-0123 DB leaves the originals unreferenced, and the next prune removes them.
  - `commit_photo` is the only writer of `photo_hash` (grep).
- **No jump.**
  - Every new refusal (view malformed, outside, not square, too small, photo changed) goes through `photo_error`: the
    400 page with the contact open and the line under the photo (tested for three of them).
  - Success is the same 303 to `?open=<id>#contact-<id>`, through the hooked `form.submit()`.
  - Only the store failure (500) and `db_error` are generic pages, as before.
  - Every JS path sets both hidden fields before it submits (`setHidden`), so a stale `view` or `original` can't ride
    along.
- **Resource use.**
  - Peak memory per upload is the decode (capped at 128 MiB) plus a thumbnail of at most about 17 MB RGBA, after which
    the decode is dropped. A re-cut is at most about 30 MB.
  - `PROCESSING` serialises uploads and re-cuts.
  - `MAX_ORIGINAL_BYTES` (8 MB) for recovery is above any 3-4 MP q90 file.
- **i18n of the new text.** All six new keys are in both catalogs, and the nb wording reads naturally.

Not a finding, but worth knowing: the ICC profile is dropped, as it always was for the squares. A Display-P3 iPhone
photo is kept as if it were sRGB (a little duller), and since the original now loses the profile too, later colour
management can't get it back. The `image` crate has no CMS, so leave it unless someone notices.
