# Server notes: launcher-ui

Moved verbatim from server/CLAUDE.md on 2026-10-19; the core file's 'Where the rest is' lists every note.

## Launcher UI, contact photos, airplane mode (handy step 5, design `docs/design/05-ui-photos-i18n.md`, at the monorepo root)

- **Contact photos** (`src/photos.rs`, migration `0024`): upload per contact on the calls page (`POST /devices/{id}/contacts/{cid}/photo`, multipart field `photo`, route body limit ~10 MB; remove: `.../photo/remove`), scoped to the device (404 otherwise) but stored on the global `contacts` row (`photo_hash`), so every device with the contact gets it and is nudged. Never stored as sent: decoded with the pure-Rust `image` crate (already in the tree via `totp-rs`'s QR feature; we enable `jpeg`/`webp`; format sniffed from the bytes, limits 8000 px / 256 MiB), EXIF orientation applied, centre-cropped square, shrunk to ≤ 512 px, re-encoded JPEG q85 - which drops all metadata (EXIF/GPS). Decoding is capped at 40 MP / 8000 px a side / 128 MiB, only the ≤ 512 px crop is copied and rotated, and `photos::process_limited` runs one upload at a time on a blocking thread. Store + `photo_hash` commit and `prune`/`recover_missing` share one file lock (`photos::lock_files`), so a prune can't delete a just-stored photo (QA step 5 #2/#3). Files: `AppState.photo_dir` (`data/contact_photos/<sha256>.jpg`, a temp dir in tests). `photos::prune` deletes unreferenced files after replace/remove/contact remove/device delete. Backups include the photos (`contact_photos/<hash>.jpg` in the zip); the root restore script still swaps only the DB, and at startup `photos::recover_missing` extracts missing referenced photos from `data/backups/*.zip` (hash checked) and sets `photo_hash = NULL` for any it can't find. Admin thumbnails: `/contact-photos/{hash}` (session).
- **Device API**: `call_policy.contacts[].photo` (hash or null); `GET /api/devices/contact-photos/{hash}` (bearer) serves it only when a contact on *this* device has that hash (invalid hash, other device's contact, missing file: 404).
- **`launcher_ui`** (always sent): `{language: "system"|"nb"|"en", home_columns: 3|4, clock_format: "system"|"24"|"12", wallpapers: [...]}` (wallpapers since step 8, clock_format since design 34 P12) from `device_policy.launcher_language`/`home_columns`, set on the phone's Home Screen page (`/devices/{id}/home`, design 31 step 5; `POST /devices/{id}/launcher`, one form, 400 on unknown values). The launcher applies the language with the per-app locale API. Server text stays English (PWA i18n is a later step).
- **Hardening**: ninth switch `disallow_airplane_mode`, **default off** (the family travels).
- **Status** `notification_listener_enabled` (`device_status` column): `false` shows "Notification access off" on Home Screen (the adb command behind its ⓘ) and an Attention row (no device-owner API grants notification-listener access).
- **Clock format** (design 34 P12, migration `0075_clock_format.sql`): `device_policy.clock_format` `'system'|'24'|'12'` (default `'system'`, CHECK), the "Clock" select (Follow the phone / 24 h / 12 h) in Home Screen's launcher form, sent as `launcher_ui.clock_format` (always; `models::clock_format` maps anything stored that isn't one of `CLOCK_FORMATS` to "system"). The launcher writes Android's `TIME_12_24`, so the status bar and every Vibb clock show the same. `POST /devices/{id}/launcher` without `clock_format` (a page cached before the field) **keeps** the stored value (`COALESCE`); only a present unknown value is a 400 (qa-34-design L10). Status `clock_format_applied` (`device_status` column, true/false/NULL; NULL = "Follow the phone" or an older launcher): `false` while the policy asks for 24 h or 12 h shows "Clock format not applied" above the Language group (`device_pages::clock_format_note`, the adb grant `appops set ... WRITE_SETTINGS allow` and the Settings path behind its ⓘ); no Attention row. Tests: `tests::launcher_ui::clock_format_*`, `the_clock_format_column_takes_only_its_three_values` (the CHECK), `device_pages` (the form, the redirect table), `policy_json_keys_snapshot`'s `ui_keys`, `music_spotify::migrations_73_to_81_in_order` (the migration order).
- The device page's latest status breaks `reported_at` ties by `id` (two reports in one second).

## Wallpapers (handy step 8, design `docs/design/08-ui-polish.md`, at the monorepo root)

- **Data** (migration `0028`): `wallpapers` (`kind` color/gradient/image, `colors` JSON hex list, `image_hash`, `label`, `sort`, `builtin_key` for the six built-ins navy/forest/plum/green/sky/sunset - navy is id 1 -, `lock_screen` opt-in per photo) and `device_wallpapers` (cascades on device and wallpaper delete). Every device gets the built-ins (existing rows in the migration, new ones by the `devices_get_builtin_wallpapers` trigger); **uploads start on no device** until ticked in the Wallpapers group of the phone's Home Screen (`POST /devices/{id}/wallpapers`, repeated `wallpaper=<id>`, an unknown id is a 400 and nothing changes; back to `/devices/{id}/home`).
- **Uploads** (`/wallpapers` page under Settings, `handlers::wallpapers`): `photos::process(bytes, Shape::Portrait)` - same sniffing and limits as contact photos, the centred crop is computed on the **upright** size (EXIF 5-8 swap width/height) so it commutes with the orientation applied afterwards; cut to 9:20, shrunk to ≤ 1080×2400, never enlarged, JPEG q85 (q75/q65 if over 3 MB, else 400). Contact photos use `Shape::Square` (unchanged result).
- **Storage**: `photos::Store` - `CONTACT_PHOTOS` (`data/contact_photos`) and `WALLPAPERS` (`data/wallpapers`, `AppState.wallpaper_dir`) each with its own lock, prune (only its own directory - never share a dir: prune deletes every file the table doesn't reference), backup zip prefix (`wallpapers/<hash>.jpg`) and `recover_missing` at startup (a wallpaper whose image is in no backup is deleted). Deleting an upload (`POST /wallpapers/{id}/delete`; built-ins: 400) cascades, prunes and nudges the devices that had it.
- **The lock's background** (design 25, "Lock background", migration `0069`): `device_lock_wallpaper` (one row per
  device at most, composite key onto `device_wallpapers`, so unticking or deleting it falls back by itself) -> the policy's
  `launcher_ui.lock_wallpaper` (id or null = the lock shows Home's wallpaper, always sent). `POST /devices/{id}/lock-wallpaper`
  (`wallpaper` = "" or a ticked id, else 400; security event `lock_wallpaper_changed`; back to `/devices/{id}/lock#lock-wallpaper`). The tick save
  deletes only the unticked `device_wallpapers` rows (a delete-all would clear the lock choice by the cascade). Tests:
  `the_lock_wallpaper_is_homes_unless_a_ticked_one_is_chosen`.
- **Softer built-ins** (design 34 P8, migration `0076_softer_wallpapers.sql`, qa-34-design M1/N2): `green` is now a
  `gradient` `["#2A4A4F","#101820"]` "Fjord" (it was a `color` row; `kind` changed in the same UPDATE, since
  `checked_colours()` wants two colours for a gradient - a `color` row with two drops out of the policy), `sky`
  `["#34406A","#14141F"]` "Evening sky", `sunset` `["#5A3A4A","#1A1420"]` "Dusk". Rows matched by `builtin_key`, ids,
  keys and sort kept, so every tick, lock choice and kid's pick stays (softened). The policy's shape is unchanged; an
  older launcher draws the new colours from the policy (its label strings are its own). Tests:
  `every_builtin_row_passes_to_policy_after_all_migrations`, `the_softened_builtins_keep_their_ids_and_keys` (also the
  PWA's two grids), `every_phone_gets_the_builtins_with_every_key`.
- **Fix round (qa-08-code.md #8)**: a failing wallpaper query is logged and the policy goes out with `wallpapers: []` (never a 500 for time rules/calls); `Store::add_to_zip` skips a file that vanished between `read_dir` and the read (a prune during a backup).
- **Wire**: `launcher_ui.wallpapers` - always a list, every key always present (`id, kind, colors, image, label, builtin_key, lock_screen`), in `sort` order; a row that doesn't validate is left out with a warning. `GET /api/devices/wallpapers/{hash}` (bearer) only for a wallpaper ticked for *this* device (else 404, like contact photos). Tests: `src/tests/wallpapers.rs`.

## App names and icons (design 14, `docs/design/14-app-display.md`, migration `0044_app_display.sql`)

- **Two levels**: a catalog default (`tracked_apps.display_label/display_icon/display_color`, by the row's package
  name; the catalog's admin name is never used) and a per-phone row (`device_app_display`, PK device + package,
  cascades with the device, <= 200 per phone) that replaces the default **as a whole** - also for Play and
  preinstalled apps outside the catalog. A phone row with neither label nor icon = Vibb style there (design 34); it is kept
  only while a default exists. Saving exactly the default, or nothing without one, keeps no row.
- **Wire**: `launcher_ui.app_display` - always a list (`policy_json_keys_snapshot`), `{package_name, label|null,
  icon|null, color}` resolved per phone (`app_display::policy_app_display`): unknown icons left out, unknown colours
  `auto`, entries with nothing left dropped; a failing query sends `[]` and logs (cosmetic, like wallpapers).
- **Forms** (`partials/app_display_form.html`, `AppDisplayForm`): every allowed app with a valid package (not the
  launcher) under its row on the phone's Apps page (`POST /devices/{id}/apps/display#app-<package>`, `action`
  save/reset (an older page's own/catalog = reset), back to `/devices/{id}/apps#app-<package>`, nudges) and the catalog page (`POST /apps/tracked/{id}/display`, back to `?open=display#display`, open, nudges every
  phone). Validation (`app_display::validate`): label trimmed, 1-20 chars (`chars().count()`), no control chars;
  icon empty or known; colour `auto` or known; package per Android's grammar, never Play core. Refused: 400 with the
  phone's Apps page (the form's action carries `#app-<package>`, so the answer lands on the row) or the catalog page, that form open, the values kept, the error by the field and that field `autofocus`ed (the
  POST path differs, so scroll-restore can't help - the focus keeps the place). `static/app-display.js` previews
  live (the tile in the phone's resolved colour; `auto` previews slate).
- **Icons and colours**: `testdata/app_icons.json` (16 Material Symbols, 6 colours with design 14's seed and the
  tile the launcher draws - since design 34 one of its six tones), byte-identical to the launcher's test copy;
  `scripts/material-symbols.sh` (root) fetches the
  SVGs at a pinned commit with SHA-256s and writes `static/app-icons/<key>.svg` + `LICENSE.txt` (Apache-2.0), the
  launcher's vectors and both tables (`src/app_icons.rs`, generated, rustfmt-clean - don't edit). Tests check the
  table against the JSON, a glyph per icon with its "Converted from" line, and white >= 4.5:1 on every tile, none
  green or red by hue.
- **Three icon states** (design 34 P1, `docs/design/34-launcher-polish.md`, QA H1; no migration): **Vibb style** (the
  default - no row, or `icon` NULL; the phone draws the app's monochrome glyph white on its tone), **the app's own
  logo** (`icon = 'own'`, `app_display::OWN`, stored and sent with colour `auto`; the phone draws the real icon in its
  own colours) and **a chosen glyph** (design 14). `own` is not in `ICONS`/`app_icons.json`: `validate` and
  `sanitized` accept it beside the glyphs. `DisplayValues::is_empty` (label and icon `None`, "nothing to send") is what
  `keep_row` and `policy_app_display` decide on; `is_own` is the logo. A catalog default may be `own`. The form's Icon
  radios are "Vibb style" (`value=""`), "The app's own logo" (`value="own"`, `static/app-display.js` disables the colour
  fieldset; the preview's tile says "Vibb style" / "Its own logo" on `OWN_PREVIEW`) and the 16 glyphs; the colour keys
  keep their names in the database and read as their tones in the PWA (`color_label`: peach Rose, plum Plum, sky
  Blue, sunset Indigo, night Teal, grey Slate; `auto` "The app's tone", previewed slate `AUTO_PREVIEW`). The one
  secondary button is **Reset** (`action=reset`, shown only with something to reset - `can_reset`: the phone's own row, or a stored catalog default; an older page's `catalog` does the same, its `own` keeps its old meaning, an empty row - qa-34-s1-code L1, N1): it deletes the phone's
  row (the phone then follows the catalog default, else Vibb style) or clears the catalog default. Vibb style with no
  name on a phone with a catalog default keeps an empty row (Vibb style there). An older launcher drops `own` as an
  unknown icon (with no label the entry is gone - today's look).
- Tests: `app_display::tests`, `src/tests/app_display.rs` (`the_apps_own_logo_is_stored_sent_and_shown`).

## The launcher is "Vibb One" (design 34 step 12, P11, `docs/design/34-launcher-polish.md`)

- **Every PWA text that names the launcher says "Vibb One"** (About This Phone's "Vibb One" row and the device page's
  "About This Phone · Vibb One 0.23.6", "Vibb One's Settings", Attention titles such as "Vibb One isn't the Home app",
  `provision_qr.html`'s setup text, "Vibb One didn't install that app", the clock warning's Settings path "›
  Vibb One ›" - pinned by `provisioning` and `launcher_ui` tests -, the security log's details "Vibb One's Settings
  opened." and "start(s) without Vibb One running", ...). **Not renamed**: "Vibb Phone" (the PWA), "Vibb style" (the icon state), "Vibb music"/"Vibb
  Musikk" (the music app and its catalog row), "Import from Vibb", "(from Vibb)", the Vibb box/Pi (the vibb music
  project), "Vibb night" (a wallpaper), the Vibb logo/mark, the manifest's "Vibb phones", the 2FA issuer "Kids
  Device MDM" (it only labels new authenticator entries; a change would make a second entry look like another
  account), and every package, the admin component and the `kids-launcher-mdm.apk` asset (the QR and the catalog
  match by package/`is_launcher`, never by name).
- **Migration `0077_vibb_one.sql`**: `UPDATE tracked_apps SET name = 'Vibb One' WHERE is_launcher = 1 AND name IN
  ('Vibb', 'Kids Launcher')` - the two default names (the mockups' and the old runbook's; the startup seed of
  2026-10-15 names a row it creates "Vibb One" - "Apps tab"/"global catalog" bullets above). Any other
  name is the parent's and stays; nothing reads the name. Test:
  `tracked_apps::the_launcher_row_becomes_vibb_one_only_from_a_default_name` (an old database both ways: both
  defaults renamed, a parent's name, "vibb" in another case and non-launcher rows kept).
