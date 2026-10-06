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
