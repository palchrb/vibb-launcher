# 87b - The Automatic tile in the PWA's preview

Status: addendum to design 87 (`87-app-logos.md`), 2026-11-23, branch `auto-tile`. The owner's report and choice
(2026-11-23): on an app's page "Vises som" › **Automatisk** previewed a letter on slate (a grey "B" for Bitwarden)
while the page's head showed the real logo; the phone's Automatic is "Vibb style" (the monochrome layer in white on
the app's tone, else the app's own logo), which the PWA can't draw. "vi går for 1": **the phone uploads the tile it
draws for Automatic beside the logo**, and the preview shows it. This reverses design 87's R2-M6 (the Automatic
preview kept a placeholder) - now it is the phone's own drawing.

## 1. The report

`installed_apps[].auto_tile` (string, optional) beside `logo`: the SHA-256 (64 lowercase hex) of the Automatic tile's
PNG. It rides only with a `logo` on the same app: the server keeps it only when that app's `logo` is valid
(`cap_installed_apps`, `lenient_text` like `logo` - a malformed one is no tile, never a refused report), and the
launcher sends it only with one. No new capability, no new answer key: the tile's hash goes into the same
`wanted_logos` list and up the same `POST /api/devices/app-logos/{hash}` - same checks, same 128 px PNG, same
re-encode, same store.

## 2. The render

`KidAvatars.drawVibbMono` - Home's own function for `IconRender.VibbMono`, with Home's `toneOf` - at design 87's
`size_px` (128), from the drawable the logo is drawn from (the fixed light configuration at 640 dpi, design 87
R2-M3). The state is `iconRender(null, hasMonochrome)`: the parent's choice is never applied (the tile is what
Automatic would draw). It matches Home's tone except while the phone is in dark mode and the app's adaptive background
uses night-qualified colours: the tile is drawn from the light configuration at 640 dpi, Home from the live one.

**An app without a monochrome layer** draws `drawOwnLogo` on Home's Automatic, i.e. exactly the logo. The launcher
then reports **`auto_tile` equal to `logo`** - the logo's bytes reused, nothing drawn twice -, so the server stores
nothing new and asks for nothing new (one file, two names). No flag is needed.

## 3. Re-render

The tile is drawn together with the logo, under the logo's cache key (versionCode, lastUpdateTime,
`Build.FINGERPRINT`, `LOGO_RENDER_VERSION`): an app update or a system update redraws both. `LOGO_RENDER_VERSION`
goes to 2, so every phone draws every app once more and reports the pair. A tile over `max_bytes` is not reported
(the logo still is); a tile whose file is gone redraws the app, as a missing logo file does.

## 4. Limits

A phone names up to two files per app now. `max_per_phone` (`MAX_LOGOS_PER_PHONE`) 300 -> **600**, the store's
`MAX_LOGO_FILES` 4,000 -> **8,000**. The byte caps stay (12 MB a phone, 64 MB in all): a tile is a flat tone and a
white glyph, a few KB *(inference)*, and the byte caps are what bound a hostile phone. `max_wanted` (40 an answer)
and the 300 uploads a day stay: a fresh phone of ~100 apps fills in about four syncs instead of three.

## 5. The server

Migration **0168** `device_app_logos.tile_hash TEXT` (nullable) and its index. A report's app with a `logo` sets the
row's pair to exactly (`logo`, `auto_tile` or NULL); an app without `logo` keeps the row; a package gone drops it.
The tile counts everywhere a logo does: the wanted list (each app's logo, then its tile), the upload's "this phone
names it", the phone's share, `is_named` for serving and pruning, `prune_all`, a deleted phone's hashes. The catalog
keeps no tile (`tracked_apps.logo_hash` stays the logo alone).

## 6. The PWA

- **The phone's app page** (`device_app.html` › `app_display_group.html`): `AppDisplayForm.auto_tile` - this phone's
  tile for the package when its file is stored; for an app this phone hasn't installed (a ticked catalog app), the
  tile most phones name, like the logo's fallback. A part of Vibb One (Meldinger) never has one.
- **The catalog app's page** (`tracked_app_detail.html`): the tile most phones name for the package (a tie to the
  most recent), whose file is stored; else the letter.
- The preview: under Automatisk the tile `<img data-preview-auto>` over the slate (class `peek-logo`, the same 56 px
  fill as the logo, no layout change); under Logo the logo; the letter only when no phone has sent a tile.
  `static/app-display.js` switches it with the segments as it does the logo. No new words, no new form or save.

## 7. The contract (`app_logos.json`, both copies)

New keys `tile_png_base64` / `tile_png_sha256` (a 128 px white square on a slate disc, drawn for the test);
`report`'s Drawing app carries `auto_tile`, and a third app, `org.example.plain`, carries the logo's hash as both
(no monochrome layer); `answer` wants the logo and the tile; `answers` gains `tile_stored` (204); `max_per_phone` is
600. The server's `the_contract` posts `report`, uploads both PNGs and checks the tile row; the launcher's
`AppLogosTest` encodes `report` exactly and `AppLogosCacheTest` the pair rules. `intake_limits` keeps `auto_tile`.
