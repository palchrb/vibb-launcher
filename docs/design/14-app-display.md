# 14 - The parent names an app and picks its icon on the kid's launcher

User request (2026-10-06): Element X should show as "Chat" with a speech-bubble icon.

## Today (checked in the code)

- Labels: `AbstractDetailedAppInfo.getCustomLabel` = the kid's rename (µLauncher's `apps.custom_names`, long-press
  -> Rename on Home and in the drawer) or the app's label. Home (`HomeActivity.refreshApps`), the drawer
  (`AppsRecyclerAdapter`) and the sort order (`AppFilter`) use it. `LockActivity.renderApps` and
  `ContactSheet.appLabel` ("Melding åpnes i Element X") read `PackageManager` directly.
- Icons: Home draws `KidAvatars.renderAppIcon` (white monochrome layer on `tileColor`, cache key app + size + dpi,
  8 MB in-memory LRU, off the main thread only); the drawer draws the raw `getIcon`. Home's label is one line,
  13 sp, ellipsized: about 10 letters fit.

## Design

1. **Server, per device.** Table `device_app_display` (device_id FK cascade, package_name, label NULL, icon_key
   NULL, color_key NOT NULL DEFAULT 'auto', updated_at; PK device + package). Per device and by package name, so
   preinstalled and Play apps work too (not only catalog rows), and two kids can differ. A row with neither a label
   nor an icon is deleted.
2. **PWA.** Device page, Apps card: each allowed row (not the launcher, not Play core) gets a `<details>` "Name and
   icon": a live preview tile (the same glyphs as SVG, served by the server), text field, icon radio grid, colours,
   "Use the app's own". `POST /devices/{id}/apps/display` -> redirect to `#app-<n>` + scroll restore (no jump).
   Help text: "Shown on the home screen, in the app list, on the lock screen and in the contact card. The app's own
   notifications, its screens and Android's settings and permission dialogs still say 'Element X' - Android doesn't
   let a launcher change those."
3. **Validation** (server; 400 keeps the entered values, error by the field): package name per Android grammar
   (>= 2 dot-separated segments, <= 255); label trimmed, empty = no label override, 1-20 characters
   (`chars().count()`), no control characters; icon a known key or empty; colour `auto` or a known key; <= 200 rows
   per device.
4. **Icon set**, shared like `phone_vectors.json`: `server/testdata/app_icons.json` ==
   `launcher/app/src/test/resources/app_icons.json` (icon keys -> Material Symbol name, colour keys -> seed hex),
   checked identical on both sides. 16 icons: chat, mail, photo_camera, photo_library, music_note, play_circle,
   sports_esports, menu_book, school, calculate, map, calendar_month, alarm, public, partly_cloudy_day, star.
   Material Symbols Rounded, filled, 24 px grid, Apache-2.0 (GPL-3.0-compatible). `scripts/material-symbols.sh`
   fetches them at a pinned commit with SHA-256s (like `nunito-static.sh`) and writes the launcher's
   `res/drawable/app_glyph_<key>.xml` and the server's SVGs. NOTICE.md and the Open Source Licenses screen carry
   Apache-2.0; a `FontLicenseTest`-style test checks glyphs and notice. Colours: Vibb palette and built-in wallpaper
   seeds (peach #F0A884, plum, sky, sunset, night lift, grey) through `tileColor` (white >= 3:1). No green or red:
   those mean call/answer and hang-up/emergency.
5. **Policy.** `launcher_ui.app_display`: always a list, every key present -
   `{package_name, label: str|null, icon: str|null, color: str}`. A failing query sends `[]` and logs (cosmetic,
   like wallpapers). Launcher DTO `PolicyAppDisplay`, all fields defaulted. Pure `appDisplayMap` drops what it can't
   use field by field: an unknown icon (newer server) keeps the label, a bad label keeps the icon, an unknown colour
   = auto. It never fails the policy decode (`PolicyResponseCompatTest`: present, absent, unknown icon;
   `policy_json_keys_snapshot`).
6. **Where it applies.** `AppDisplay` holds the map in memory, refreshed after each accepted sync from the `Ok` cache.
   - Label: `displayLabel(parent, kidRename, appLabel)`, the parent's first. It feeds `getCustomLabel` (Home, drawer,
     sort), `LockActivity`'s usable-app buttons, the contact sheet's "Melding åpnes i Chat" and our install
     notifications ("Installerer Chat" when the catalog row's package has a label). Long-press Rename is hidden for
     an app the parent named. Kid Settings lists no apps; the in-call screen shows no app names. Badges are keyed by
     package and follow the tile.
   - Icon: `KidAvatars.renderSymbolIcon` draws a `tileColor(seed)` circle with the white glyph at about 55%. The
     seed is the colour key, or with `auto` the app's own seed as today. The drawer shows that rendered tile (made
     off the main thread like Home's), never the app's own icon.
7. **Can't apply:** the app's own notifications (shade header, small icon), its UI and splash, Recents, the share
   sheet/chooser, permission dialogs, the "app paused" dialog, BlockedAppActivity, Android Settings, Play.
8. **Cache key.** `iconKey` = `"$key|$sizePx|$densityDpi"` + `"|g=<icon>|c=<colour>"` when overridden. A changed
   override is a new key and the old bitmap ages out of the LRU. `auto` keeps the app key in it (per-app seed). The
   cache is in memory only, so new glyph art in a later build needs no version.

## Checks

- Launcher: `appDisplayMap` (each invalid field alone), `displayLabel` precedence, `iconKey` with and without
  override, Rename hidden, the shared JSON identical and every key has a drawable, compat test, `TranslationsTest`.
- Server: validation 400s, keys always present, cascade on device delete, POST redirect anchor, snapshot.
- Emulator: Element X -> "Chat" + chat on peach. Check Home, drawer, lock-screen app list and contact sheet, and
  that Element X's own notification still says "Element X".

## Open questions

1. Per device only, or also a default on the catalog row that applies to every device unless overridden?
2. Should the kid keep the inherited long-press Rename/Hide at all? Proposed: Rename only for apps the parent
   hasn't named.
3. Are the 16 icons and 6 colours right? Anything missing (e.g. a Vibb glyph)?

## QA review (launcher code, `ServerJson`, `policy_json_keys_snapshot`, Material Symbols repo `master`)

1. **High - one bad entry fails the whole policy.** `ServerJson` has no `coerceInputValues`: a `null` or wrong type in
   any field fails `PolicyResponse`. Make every `PolicyAppDisplay` field nullable `= null` (colour too) or decode a
   `JsonArray` that `appDisplayMap` reads field by field; compat cases `{"color":null}`, `{"label":5}`. Snapshot:
   `ui_keys` gains `app_display`, always a list.
2. **Medium - stale tiles.** One `iconKey(key, size, display)` for `cachedAppIcon` and `renderAppIcon`, or a lookup
   finds the old bitmap. Load `AppDisplay` from the cached policy at process start; a changed map re-runs Home's
   `refreshApps` and the drawer sort (today only `custom_names` changes do, Application.kt:118).
3. **Medium - colours.** `tileColor` darkens a seed until white >= 3:1 (peach #F0A884 becomes #E4763F), so a PWA
   preview of the seed is wrong. Put the resolved tile hex in the JSON (launcher test `tileColor(seed) == tile`, server
   test contrast >= 3:1); `auto` previews as neutral grey, "the app's own colour".
4. **Medium - not a phone_vectors pattern.** Those are test vectors; here both sides need runtime tables. The script
   writes a Rust const and a Kotlin map; each side asserts its table equals the shared JSON, with a glyph per key.
5. **Low - script and licence.** The SVGs are `viewBox="0 -960 960 960"` (checked; all 16 exist in rounded fill1 24px):
   viewport 960 + `<group android:translateY="960">`. Apache-2.0 into GPL-3.0 is fine; per §4(b) each converted file
   says "converted from Material Symbols <name>, Apache-2.0"; ship the licence text in `assets/licenses/` (no NOTICE).
6. **Low - names.** Keep the kid's stored Rename (it comes back when the parent clears the label). `LockActivity` sorts
   by package; sort by the display label. Two launcher activities in one package both get the override (accept).
7. **Open questions:** per device only for now; Rename only for apps the parent hasn't named; the set is fine.

## Decisions after QA review

All QA findings 1-6 accepted. User answers (2026-10-06), overriding QA's "per device only":
- **Both levels.** The catalog row gets optional "show on the kid's phone as" label + icon + colour, a default for every
  device that has the app (the catalog's own admin name is not used for this). The device's app list can override it
  per device, also for Play apps that aren't in the catalog. The policy carries the resolved per-device result, so the
  launcher sees one `launcher_ui.app_display` list either way.
- The kid's long-press Rename stays only for apps the parent hasn't named (the stored kid name comes back when the
  parent clears the label, per QA #6).
- The 16 icons and 6 colours stand; add more later on request.

## Implementation status (2026-10-06)

Done on `master` (not pushed). S: `cargo test` green (247, 11 new), `cargo fmt --check` clean on these files,
`cargo clippy --all-targets` 17 warnings as before. L: `./gradlew testDebugUnitTest assembleDebug
-PwarningsAsErrors=true` green (614 unit tests at `399a9041`, 13 new; 616 with the follow-up below). Not run on a device or the emulator.

| Part | Commit | What |
|---|---|---|
| S + L | `399a9041` | S: migration `0044_app_display.sql`, `src/app_display.rs` (validation, resolution, the form), generated `src/app_icons.rs`, `launcher_ui.app_display`, the two forms and routes, `partials/app_display_form.html`, `static/app-display.js`, `static/app-icons/`. L: `AppDisplay`/`appDisplayMap`/`displayLabel`/`appIconKey`, `AppGlyphs`, the glyph vectors, `KidAvatars.renderSymbolIcon`, labels in `getCustomLabel`, the time-rule screen, the contact sheet and the install notification, Rename hidden, the Apache licence. `scripts/material-symbols.sh`, `testdata/app_icons.json` (+ launcher copy) |
| L | (this commit) | the display code moved out of the MIT `apps/` directory into `appdisplay/`; `PolicyResponseCompatTest` without another agent's unfinished design-17 lines that `399a9041` had picked up; docs: this section, CLAUDE.md files, `NOTICE.md` (with the design-13 code review fixes) |

How the design, QA #1-#6 and the decisions were met:
- **Both levels (decision)**: the catalog row's optional "Show on the kid's phones as" (label, icon, colour; the
  catalog's admin name is never used) is the default for every phone with the app, by package name; the phone's row
  (`device_app_display`, also for Play and preinstalled apps) replaces it **as a whole**. The device form starts from
  what the phone shows now; "Use the app's own" (kept as an empty row only while a default exists) and "Follow the
  catalog" (deletes the phone's row) are buttons. Saving exactly the default keeps no row, so the phone follows later
  catalog changes. The policy carries the resolved list.
- **§2 PWA**: every allowed app with a valid package (not the launcher, never Play core) gets a `<details>` "Name and
  icon" in a "Names and icons on the phone" block under the Apps card's list (the list itself is a 320 px scroll box,
  too small for the icon grid). Saves redirect to `#app-<package>` / `#display`; a refused save is a 400 with that
  form open, the values kept, the error by the field and that field focused (the POST path differs from the page's, so
  scroll-restore can't restore it - the focus keeps the place). The help text is as designed.
- **§3 Validation** as designed (Android package grammar, label 1-20 `chars()`, no control characters, known
  keys, <= 200 rows per phone; 400 keeps the values).
- **§4, QA #3-#5**: `testdata/app_icons.json` holds the 16 icons and the 6 colours with seed and tile (peach #F0A884 ->
  #E4763F, plum #4A2545, sky #1C7ED6 -> #1E7ED4, sunset #F76707 -> #DE6C20, night #1C1A2A, grey #868E96). Both sides
  have runtime tables generated by `scripts/material-symbols.sh` (pinned `google/material-design-icons` commit, SHA-256
  per SVG and for the licence) and test them against the JSON; the launcher also tests `tileColor(seed) == tile`, the
  server white >= 3:1 and "not green, not red". Vectors use viewport 960 + `translateY="960"`; every converted file
  says "Converted from Material Symbols <name> ... Apache License 2.0" (§4(b)); the licence text ships in
  `assets/licenses/Apache-2.0.txt` (licences screen) and `server/static/app-icons/LICENSE.txt`; `NOTICE.md` lists it.
  `auto` previews as grey in the PWA.
- **§5, QA #1**: `launcher_ui.app_display` is always a list with every key; the launcher keeps it as a raw
  `JsonElement` and `appDisplayMap` reads it field by field - `{"color":null}`, `{"label":5}`, an unknown icon, a
  non-list all decode (`PolicyResponseCompatTest`); the snapshot's `ui_keys` has `app_display`.
- **§6, QA #2, #6**: `AppDisplay` loads at process start and after each accepted sync; a change reloads the app list.
  Label precedence parent > kid > app; the time-rule screen sorts by the shown name; Rename hidden only for a named
  app, the kid's stored name kept. Icons: `renderSymbolIcon` (glyph 55 %, tile colour or the app's own seed through
  `tileColor`), one cache key (`appIconKey`) for lookup and render. The drawer has no icons (a text list), so only the
  name applies there; Kid Settings and the call screen show no app names.
- **§7** unchanged: the app's own notifications, screens, Recents, chooser, permission/"app paused" dialogs,
  BlockedAppActivity, Settings and Play still show the app's own name and icon.

Open device checks (none run yet):
- Element X -> "Chat" with the chat glyph on peach: Home, the drawer's name and sort, the time-rule screen's app
  button, the contact sheet's "Melding åpnes i Chat"; Element X's own notification still says "Element X".
- Long-press on a named app has no Rename; clearing the name on the server brings the kid's old rename back.
- A changed icon or colour re-renders Home at the next sync without a restart.
- The PWA preview and radio grids on a phone-sized screen, light and dark.
