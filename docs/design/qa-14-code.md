# QA 14 code: app names and icons (design 14 parts of `399a9041`, `275823c5`)

I checked these in a worktree at `275823c5`. Server: `cargo test` 247 passed and `cargo fmt --check` is clean. Launcher: the `appdisplay.*`, `PolicyResponseCompatTest` and `ui.home.*` unit tests passed. No problems in these areas:
- **Policy decode:** a raw `JsonElement` read field by field, with compat cases for null, wrong type, unknown icon and not a list. The snapshot always has `app_display` as a list.
- **Resolution and validation:** the phone's row replaces the catalog default as a whole, then the kid's rename, then the app's label (in `getCustomLabel`). Validation covers the package grammar, 1-20 characters, no control characters, known icon and colour keys, and at most 200 rows.
- **Icon set and licences:** the shared JSON equals both runtime tables, with a glyph per key. White on every tile is at least 3:1 (lowest: peach, 3.02). The Apache-2.0 notices are on each file, in `assets/licenses`, in `static/app-icons/LICENSE.txt` and in `NOTICE.md`. No display code is left in the MIT `apps/` directory.
- **Server and PWA:** migration 0044 is safe (constant `ADD COLUMN` defaults; the device delete cascades). Saves redirect to `#app-<package>` or `#display`, and a refused save returns 400 with `autofocus`. There are no forms for Play core or the launcher, and the layout fits a phone.

1. **Low: two reads of the parent's choice for one tile.** In `ui/home/KidAvatars.kt:154`/`:157`, `renderAppIcon` reads `AppDisplay.map` twice: once in `iconKey` → `displayOf`, then in `displayOf` again. If a sync lands between the two reads, the new choice's bitmap is stored under the old key. If the parent later switches back to the old choice, Home shows the wrong tile (QA #2). Fix: call `displayOf(key)` once and pass the result to both `appIconKey` and the render.
2. **Low: the kid's Rename is skipped in two places.** `ui/LockActivity.kt:193` and `calls/ContactSheet.kt:98` use `AppDisplay.label(pkg) ?: PackageManager label`. An app the kid renamed, but the parent didn't, keeps its own name on the time-rule screen and in "Melding åpnes i …". Home and the drawer show the kid's name, and design §6 asks for `displayLabel(parent, kidRename, appLabel)` in both places. Fix: add one `shownLabel(context, pkg)` helper that uses the loaded app's `getCustomLabel`, else parent name, else `PackageManager` label.
3. **Low: decode on the main thread.** `Application.kt:270` calls `AppDisplay.refresh(reload = false)`, which decodes the whole cached policy on the main thread at every cold start. That is a second decode next to `CallPolicyStore`'s. Fix: call it first in `loadApps()`'s `Dispatchers.Default` coroutine (`:334`), before `getApps`, so the first list still sees the map.
4. **Low: PWA label limit and small text.** In `partials/app_display_form.html:22`, `maxlength="20"` counts UTF-16 units, but the server and the launcher count code points. Labels with more than 10 emoji are cut without a warning. Fix: drop `maxlength`, since the 400 keeps the value. Separately, the "its own icon" text (0.65rem, white on #868E96, 3.3:1) is below 4.5:1. Fix: make it larger or darken the grey behind it.

## Fixes (2026-10-06)

All four fixed; none skipped.
1. **Fixed.** `KidAvatars.renderAppIcon` reads `displayOf(key)` once and passes that one choice to both the cache key
   (a private `iconKey(..., display)`) and the render.
2. **Fixed.** `AppDisplay.shownLabel(context, package)` = `displayLabel(parent name, the kid's rename of that package
   (from `getCustomAppNames`), PackageManager label)`. `LockActivity`'s usable-app buttons (sorted by it) and the
   contact sheet's "Melding åpnes i …" use it.
3. **Fixed.** `AppDisplay.refresh` for the process start moved off the main thread: the first `loadApps()` runs it
   on its `Dispatchers.Default` coroutine before `getApps` (once per process, `appDisplayLoaded`), so the first
   list still sees the map. Later changes still come from the sync's refresh.
4. **Fixed.** `maxlength` is gone from the name field (the server and launcher count characters; a refused save keeps
   the value). With no glyph the preview tile is `app_display::OWN_PREVIEW` #5C6370 (white text about 6:1; the server
   test checks >= 4.5:1 and that `app-display.js` uses the same value), and the text is 0.75rem. `style.css?v=5`,
   `app-display.js?v=2`.

Tests: server 250 (`cargo fmt --check` clean, clippy 17 as before), launcher 640 unit tests and `assembleDebug` with
`-PwarningsAsErrors=true`.
