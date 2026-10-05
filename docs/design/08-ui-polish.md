# Step 8: UI polish - Home without clock, coloured icons, Nunito, kid settings, curated wallpapers

Target: mockups `Main`, `PhoneBook`, `ContactCard`, `KidSettings` (.dc.html, 320×568 dp; CSS px = dp, font px = sp).
Today (emulator screenshot): big clock+date, apps' own icons in white circles, system font, fixed 60 dp tiles, no
Phone book tile. S = kid-phone-server, L = kids-launcher-mdm (branch `handy`). Every guarantee of 01-07 stays (see
"Guarantees"). Claims: [verified: source] or [needs device test].

## 1. Home (L)

- **Remove clock/date**: `HomeActivity.kt:137-143` and `activity_home.xml:19-38`. Time stays in the status bar (lock
  task keeps `LOCK_TASK_FEATURE_SYSTEM_INFO`, S `devices.rs:202` [verified: source]). Top: root keeps
  `fitsSystemWindows` (background is drawn under the bar, content padded), `paddingTop` 12→6 dp, contacts row margin
  16→14 dp (mockup: 6 px pad, 18 px gap minus 4 px), grid gap 18 dp below the contacts.
- **Status bar**: already transparent (`styles.xml:31-36`); targetSdk 36 enforces edge-to-edge, `statusBarColor` is
  ignored on API 35+ [verified: Android 15 behaviour changes]. Icons follow the wallpaper ink (§3):
  `WindowCompat.getInsetsController(window, root).isAppearanceLightStatusBars = (ink == dark)` in Home, PhoneBook,
  KidSettings `onResume`.
- **Grid sizing from width** (pure `gridMetrics(contentWidthDp, cols)` in `HomeModel.kt`): mockup 288 dp content,
  3 cols, gaps 14 dp row / 8 dp column, icon 60 dp, label 13 sp. `scale = contentWidthDp/288`;
  `icon = clamp(round(60*scale), 48, 76)` but ≤ `cellWidth-6`; label `13*scale` clamped 12..15 sp; badge 24 dp.
  `HomeGridAdapter.kt:67` and `item_kid_tile.xml:13-24` take the values (set LayoutParams in `onCreateViewHolder`);
  `ItemDecoration` for the gaps; `gridColumns` (`HomeModel.kt:12`) unchanged (3, 4 if the policy says 4).
- **Icons as coloured circles** (`KidAvatars.roundAppIcon`, `KidAvatars.kt:81-103`), cache key += size:
  1. `AdaptiveIconDrawable.getMonochrome()` (API 33; minSdk 34 → always callable) [verified: API reference] non-null
     → circle filled with `tileColor(seed)`, monochrome layer drawn with the same 1.5× bounds as the foreground
     today (`KidAvatars.kt:88-92`), tinted white (`setTint(WHITE)`, `SRC_IN`).
  2. Seed colour: `androidx.palette` on a 48 px render of the full icon - **already a dependency**
     (`app/build.gradle.kts:225`, unused so far): vibrant → dominant swatch → `#868E96`. Pure
     `tileColor(argb)`: keep hue, lower HSL lightness in 0.04 steps until contrast with white ≥ 3.0 (WCAG; glyph is
     a large graphic), saturation capped at 0.75 - the mockup's #0DBD8B/#7950F2/#F76707 family.
  3. No monochrome layer (most non-Google apps) → full adaptive icon filling the circle (current path). Legacy bitmap
     icon → circle in `tileColor(seed)` with the icon inset 1/8 (replaces white `kid_icon_fallback`).
  Palette runs in the existing off-main `refreshApps` pass (`HomeActivity.kt:376-397`), never in `onBind`.
- **Fixed tiles** (`GridTile` in `HomeModel.kt:17-22`): `PhoneBook` first (#37B24D, book glyph), apps, then new
  `GridTile.Settings` last (#868E96, gear) → opens `KidSettingsActivity`. `homeGrid(apps, showPhoneBook, badges)`
  appends Settings always.
- **Phone book tile rule**: today `showPhoneBook = !view.isEmpty` (`HomeActivity.kt:366-368`, `CallRules.kt:222-235`)
  hides it when calls are managed but off and no contact is an emergency number. New pure
  `showPhoneBookTile(state) = state !is CallPolicyState.Unmanaged` (Managed or UnknownFailClosed → always; the
  phone book itself shows "calls off"/112). **Screenshot finding**: the Pappa button comes from the same `view.home`
  as the tile flag, and `view.home ⊆ view.contacts`, so at HEAD a Home contact implies the tile - and "calls
  unmanaged" is ruled out (Unmanaged gives no contacts, `CallRules.kt:228`). The emulator APK is most likely older
  than `42d8e44`/`3f36a66` or built from another branch [needs device test: reinstall HEAD debug APK,
  `adb shell uiautomator dump` → look for "Phone book"]. Task 1 adds a JVM test tying both to one state.
- **Contacts row, several contacts** (pure `contactRow(count, contentWidthDp)`): 1-3 → 76 dp avatars, 28 dp gaps,
  centred (mockup); 4 → 64 dp, 16 dp gaps if it fits; more → 64 dp, horizontal scroll with the 5th half visible
  (peek says "more"). Badge positions scale with the avatar. Swipe-left conflicts with scrolling: the fling handler
  (`HomeActivity.kt:180-186`, row listener `:212-215`) opens kid settings only if the touch didn't start in the
  row or `!homeContactsScroll.canScrollHorizontally(1)` (same rule as the grid's swipe-up, `:169-173`).
- **Font Nunito** (OFL 1.1, Google Fonts are downloadable-only → bundle). Source: googlefonts/nunito v3.602 variable
  TTF (a copy is in the session scratchpad `vibb/pi/art/Nunito.ttf`, name table says OFL, wght 200-1000). Make
  static 600/700/800 with `fonttools varLib.instancer` → `res/font/nunito_semibold|bold|extrabold.ttf` + `nunito.xml`
  font-family (weights 600/700/800). Check OFL.txt has no Reserved Font Name before keeping the name
  [verify in repo OFL.txt]. Ship `OFL.txt` in `assets/licenses/` and list it in `LegalInfoActivity`. Theme: `Font`
  enum gets `NUNITO(R.style.fontNunito)`, `UIObject.kt:42` uses it; remove the 11 hard-coded
  `android:fontFamily="sans-serif*"` in `activity_home, item_kid_tile, item_kid_contact, activity_phone_book,
  sheet_contact, activity_lock`. Weights: names/titles 800, labels/rows 700, body 600 (mockup).

## 2. Kid settings (L) - replaces QuickControlsActivity

- New `ui/kidsettings/KidSettingsActivity` (mockup KidSettings): wallpaper as ground, 44 dp round back button
  (white 12 %), title 24 sp/800; "Background" 15 sp/800 + 4-col grid of 64 dp tiles, radius 12, 8 dp gaps,
  selected = 3 dp white border + check, others 1 dp white 25 %; caption 12 sp/700 70 % "Picked by mum and dad";
  "Connection and screen" card (white 8 %, radius 16): Wi-Fi/Bluetooth rows 52 dp with switch (track 46×28 dp,
  on #2B8A3E), "Manage networks/devices" link as today, brightness row 60 dp, slider accent #4DABF7. Rows and
  calls are the existing ones (`QuickControlsActivity.kt:55-107` → `QuickControls.kt`), moved, not rewritten.
- Opened by the Settings tile and by swipe-left (`HomeActivity.kt:184`). Delete `QuickControlsActivity` +
  layout + manifest entry (`AndroidManifest.xml:142`); `WifiNetworksActivity`/`BluetoothDevicesActivity` stay.
  Update CLAUDE.md:127. Nothing here changes rules: no PIN, no policy write, no link to parent Settings (that stays
  PIN-gated in the drawer). Wallpaper pick is local (§3).
- Pure `kidSettingsModel(isDeviceOwner, cached: CachedPolicy, wallpapers, pickedId)` → sections; the activity only
  renders it, and re-renders on the `kidModePolicy` pref change (today the mask is read once in `onCreate`).
- **"No switches" bug**: mask = `(cachedPolicy() as? CachedPolicy.Ok)?.policy?.quickControlsMask ?: 0`
  (`QuickControlsActivity.kt:51`). The server saves and sends the mask correctly (`devices.rs:1215-1224,1274`,
  `device_api.rs:226`, form `device_detail.html:262-274`) and the DTO maps it (`PolicyResponse.kt:58`, snake_case
  `ServerJson`) [verified: source]. So 0 means one of: (a) not device owner → empty text (`:46-49`); (b) cache
  `Absent` (no accepted policy yet) or `Corrupt` (`PolicyGate.kt:32-38`) - the phone then runs on
  `LastEnforcedPlan`, which has no mask; (c) the newest policy was **not accepted** (`judgeFresh`
  `REJECT_SUSPECT`, `PolicyGate.kt:81-101`), so the cache still holds the pre-tick policy; (d) the screen was open
  when the sync landed (no listener). Diagnose on the emulator: device page `policy_state` + `adb shell run-as
  com.kidslauncher.mdm.debug cat shared_prefs/*_preferences.xml | grep -o 'quick_controls_mask[^,]*'`
  [needs device test]. Fix in code: (d) listener; model distinguishes `NotOwner` / `NoPolicyYet` / `Unreadable` /
  `NoneEnabled` and logs `describe()` so (b)/(c) are visible; Corrupt keeps 0 (fail closed, kid convenience only).

## 3. Curated wallpapers (S + L)

- **S data** (`migrations/0028_wallpapers.sql`): `wallpapers(id, kind 'color'|'gradient'|'image', colors TEXT
  (JSON hex list), image_hash, label, sort, builtin)`, seeded with the mockup set: navy #14213D (default), forest
  #1E4D3A, plum #4A2545, green #2B8A3E, sky #1C7ED6→#14213D, sunset #F76707→#862E9C (160°).
  `device_wallpapers(device_id, wallpaper_id)`; a new device gets all built-ins.
- **Upload** (Wallpapers page; device page card with checkboxes): reuse `photos.rs` - generalise `process()`
  (`photos.rs:97-147`) with a `Shape` (`Square{512}` today, `Portrait{max 1080×2400}` = fit, no crop, shrink then
  `apply_orientation`). Same sniffing, 10 MB / 8000 px / 40 MP / 128 MiB decode limits, JPEG q85 (drops
  EXIF/GPS/XMP), `spawn_blocking`, one upload at a time. Own dir `data/wallpapers/` - **must not share
  `photo_dir`: `photos::prune` deletes every file no contact references** (`photos.rs:189-217`); own prune over
  `wallpapers.image_hash`, backups/recovery like photos (`backups.rs:214-229`).
- **Policy**: `launcher_ui.wallpapers: [{id, kind, colors, image, label}]` (allowed set, ordered, always sent);
  Kotlin `LauncherUi.wallpapers = emptyList()` default (old server → built-in navy). Route
  `GET /api/devices/wallpapers/{hash}` (bearer; 404 unless allowed for this device). Key snapshot test
  (`tests/device_api.rs:322`).
- **L cache**: `WallpaperCache.sync` after each accepted sync, modelled on `ContactPhotos.sync`
  (`ContactPhotos.kt:109`): ≤ 3 MB, SHA-256 = name, temp+rename, CE `filesDir/wallpapers/`. Kid's pick in CE prefs
  `kid_wallpaper_id`. Pure `effectiveWallpaper(allowed, pickedId, cachedHashes)` → pick if allowed and usable,
  else first usable allowed, else navy. Nothing to DE.
- **Apply**: Home/PhoneBook/KidSettings draw it themselves (root background: colour, `GradientDrawable` at 160°, or
  centre-cropped bitmap) - always works, no permission. Then `WallpaperApplier` (off main) renders the same at
  `currentWindowMetrics` size and calls `WallpaperManager.setBitmap(bmp, null, true, FLAG_SYSTEM or FLAG_LOCK)`
  (needs `SET_WALLPAPER`, a normal permission, to add to the manifest [verified: API reference]) only when pure
  `wallpaperApplyPlan(lastKey, wantKey, lastId, currentId)` says so; stores key + `getWallpaperId(FLAG_SYSTEM)`;
  Home `onResume` re-applies if the id changed behind our back. Recents/swipe-up animations then show the same
  image [needs device test: third-party launcher recents is the system's fallback recents].
- **Lock others out**: new `HardeningRestriction.SET_WALLPAPER(defaultOn = true)` (`Hardening.kt:12-31`, `value()`
  → `null`: launcher-only, no parent switch, set while managed, never lifted by override/pause, like the others).
  AOSP `WallpaperManagerService.isSetWallpaperAllowed` exempts the device/profile owner from
  `DISALLOW_SET_WALLPAPER` [verified: AOSP source; needs device test on Jelly Star]. If the test fails: drop the
  restriction and rely on the id check above.
- **Legibility** (pure, `ui/home/WallpaperInk.kt`): colours/gradients → relative luminance (gradient: max of
  stops); images → mean + std-dev luminance of a 32×57 downscale. Ink = white or #1B1B1F, whichever contrasts
  more; labels need ≥ 4.5:1: `scrimAlpha(lum, sd)` = smallest black (white-ink) alpha in 0..0.45 reaching it on
  `lum + sd`, images get ≥ 0.15. Ink drives labels, badge borders, status-bar icons; ink-dim = ink at 75 %.

## 4. Guarantees kept (01-07)
`onResume` order (`HomeActivity.kt:245-270`: lock reevaluation → kiosk → LockActivity redirect) unchanged; the
new activity is in our package (lock task allows it) and is behind LockActivity like Home. PIN gates: parent
Settings only via drawer; kid settings change nothing enforced. Calls: tile rule only widens visibility; tap/long-
press and 112 tile unchanged; direct boot untouched (no wallpaper/font file read before unlock - font is an APK
resource). Time rules/screen time: Home counting unchanged. Emergency: LockActivity/InCallActivity only get the font.

## 5. Tasks (in order, each with JVM tests)
1. Pure: `showPhoneBookTile`, `GridTile.Settings` in `homeGrid`, `gridMetrics`, `contactRow`, `tileColor`
   (`HomeModelTest`: Unmanaged/Managed-off/FailClosed, a Home contact ⇒ tile, 3/4 cols at 240/288/360 dp, contrast).
2. Font: instances + `nunito.xml`, OFL, theme, strip `sans-serif*`; `TranslationsTest` for new strings (nb/en).
3. Home: remove clock, spacing, metrics, contacts row, swipe guard, Settings tile, coloured icons.
4. `kidSettingsModel` + `KidSettingsActivity` (rows moved), swipe-left, delete QuickControls; mask diagnosis.
5. S: migration, `photos::process(Shape)`, wallpaper routes/pages/prune/backup, policy key; tests: portrait upload
   upright without EXIF, wrong type/too big → 400, device route only for allowed, prune keeps contact photos.
6. L: DTO compat test (old policy without `wallpapers`), `effectiveWallpaper`, `WallpaperCache` plan, picker.
7. `WallpaperInk` (+ tests: navy → white, #F4F1EA → dark, bright photo → scrim), `WallpaperApplier` +
   `wallpaperApplyPlan`, `SET_WALLPAPER` hardening (`HardeningTest` default on, cleared when unmanaged).

## 6. Screenshot acceptance (emulator 320×568 dp-ish AVD, nb and en; compare side by side with the mockup)
1. Home, 2 Home contacts, 3 cols, navy: no clock, light status icons, sizes/gaps within ±2 dp, Nunito.
2. Same with 4 cols; with 5 contacts (scroll + peek); with 1 contact.
3. Home with calls managed + off and no emergency contact: Phone book tile present; calls unmanaged: absent.
4. Icons: one app with monochrome (Clock/Phone-like Google app) = white glyph on colour; one without = full icon.
5. KidSettings with mask 7, 1, 0 (0: no card), and not provisioned; Wi-Fi toggle and slider work.
6. Each built-in wallpaper + one uploaded light photo + one dark photo on Home and KidSettings: legible labels.
7. After picking: press Home → recents/swipe-up animation and lock screen show the same image.
8. Settings → Wallpaper (or Photos "set as") on a managed phone is blocked; our own pick still applies.
9. PhoneBook and ContactCard screens unchanged except font; LockActivity at bedtime still comes up over Home.

## Decisions after QA review (qa-08-design.md), 2026-10-05

QA findings override this doc where they conflict. Binding:
- Photo wallpapers go on the HOME screen only; the lock screen gets the chosen colour/gradient
  unless the parent opts in per wallpaper. When a wallpaper is unassigned/deleted or the phone is
  unmanaged, the system wallpaper is replaced at once.
- Server wallpaper processing rotates first, then cover-fits/crops to the phone's aspect; same
  file lock, backup and recovery as contact photos.
- Icon rendering off the main thread (in refreshApps); draw only one full-screen bitmap (use the
  system wallpaper behind a transparent Home when it's ours); apply the wallpaper only when the
  choice changes, never on every resume; don't fight a refused setBitmap.
- Scrim/text colour chosen per wallpaper to reach 4.5:1 for labels (dark or light text).
- Quick Controls bug: also cover failed decode keeping the old cache and non-device-owner
  installs; add a test banning Settings/wallpaper intents from the kid screens.
- Pin the Nunito source URL + SHA-256; 48 dp touch targets.

## Implementation status (2026-10-05)

Done on branch `handy` in both repos (not pushed). Build/tests: S `cargo test` (144), `fmt --check`,
`clippy --all-targets` (no new warnings); L `assembleDebug assembleRelease testDebugUnitTest`.

**L (kids-launcher-mdm)**, one commit per task:
1. `HomeModel.kt`: `showPhoneBookTile` (managed or fail-closed → always; a Home contact implies the
   tile - JVM test over every state), `GridTile.Settings` last, `gridMetrics`, `contactRow` (76/28 →
   64/16 → scroll with a half-avatar peek; at 288 dp four contacts already scroll), `tileColor`
   (≥ 3:1 with white, grey #868E96 unchanged). `HomeModelTest`.
2. Nunito: `scripts/nunito-static.sh` (fonttools `varLib.instancer --static`, weights 600/700/800) from
   the variable font **v3.602** pinned in google/fonts:
   `https://raw.githubusercontent.com/google/fonts/604936664fd62c14271209b51f98e7f495dd1a3e/ofl/nunito/Nunito%5Bwght%5D.ttf`
   SHA-256 `bb55a5ca5c2042335b3991af27c4d0705d0ef41cac6164ac737fd8f2a1e85207` (identical to the
   scratchpad copy); OFL from the same commit (`.../ofl/nunito/OFL.txt`, SHA-256
   `580df76c95a1ec5ab878ceb25bb3d85c6a076804e9c970c8c6972aea775fdf65`), no Reserved Font Name, æøå in
   the cmap. Shipped as `assets/licenses/OFL.txt` and in Open Source Licenses (`FontLicenseTest`).
   Base theme `@font/nunito`; `sans-serif*` gone from layouts/styles (tested).
3. Home: clock/date removed, padding 6 dp, contacts 14 dp, grid 18 dp; grid sizes from
   `gridMetrics` + `GridGapDecoration`; contacts from `contactRow`; swipe-left guard for a scrollable
   row (raw coordinates); icons rendered with the palette in `refreshApps` (Default dispatcher), the
   adapter reads a bitmap map (QA #5; monochrome `mutate()`d; key = app + size + density).
4. `ui/kidsettings/KidSettingsActivity` + `kidSettingsModel`/`controlsSection` (NotOwner /
   NoPolicyYet / Unreadable / NoneEnabled / Rows, own nb/en text, logged as `KidSettings`), re-render
   on `kidModePolicy`, `QuickControlsActivity` deleted, 48 dp back buttons, `KidScreensEscapeTest`
   (QA #10). Switch rows are `SwitchCompat` with the label as their text (TalkBack).
6. DTO `LauncherUi.wallpapers`/`PolicyWallpaper` (compat test without it), pure `Wallpapers.kt`
   (`parseWallpapers`, `effectiveWallpaper`, `lockScreenFill`, `wallpaperCachePlan`, bounds and
   sample size), `WallpaperStore` (sync after every accepted sync, deletes images no longer allowed,
   one RGB_565 bitmap), picker in kid Settings (labels in nb/en for built-ins, "selected" announced).
7. `WallpaperInk` (symmetric scrim in sRGB blending, worst stop per ink, photos mean ± sd in linear
   light, min 0.15, cap 0.45 then label shadow), `WallpaperApplier` + `wallpaperApplyPlan` (managed
   only, device owner + `isSetWallpaperAllowed`, id 0 = failure, one attempt per key per day, reset to
   navy when unmanaged before the restriction is lifted), `HardeningRestriction.SET_WALLPAPER`
   (launcher-only). Home, phone book and kid Settings show the system wallpaper behind a transparent
   root when it is ours (`UIObject.showsSystemWallpaper`), else draw the fill themselves.

**S (kid-phone-server)**, task 5: migration `0028_wallpapers.sql` (six built-ins with `builtin_key`,
`device_wallpapers`, trigger gives new devices the built-ins, uploads start on no device,
`lock_screen` opt-in per photo); `photos::process(bytes, Shape)` - crop computed on the upright size
(EXIF 5-8 swap), then shrunk, then oriented; `Shape::Portrait` = centred 9:20, ≤ 1080×2400, never
enlarged, ≤ 3 MB (re-encoded at q75/q65 before giving up); `photos::Store` (`CONTACT_PHOTOS`,
`WALLPAPERS`: own lock, prune of its own dir only, zip prefix `wallpapers/`, `recover_missing` at
startup - a lost image deletes its wallpaper); routes `/wallpapers` (page, upload, lock-screen, delete),
`/wallpaper-images/{hash}`, `/devices/{id}/wallpapers`, device `GET /api/devices/wallpapers/{hash}`
(404 unless ticked for this device); `launcher_ui.wallpapers` in the policy and the key snapshot.
Tests `src/tests/wallpapers.rs`: EXIF-6 portrait and landscape upright at 1080×2400, small not
enlarged, bad type/oversize → 400 and nothing stored, route scoping, untick/delete cascade + nudge,
prune isolation both ways, backup entry + recovery.

**Deviations / decisions**: built-in labels come from the launcher's strings (nb/en) by
`builtin_key`; uploads use the parent's label. The lock-screen fallback for a photo is navy. The
contact badge ring stays the ground navy (not the ink). Unmanaged reset uses navy rather than
`WallpaperManager.clear()`.

**Open (needs a device)**: the Quick Controls "no switches" root cause on the emulator was **not**
recorded here - no emulator/adb in this environment; run the §2 diagnosis (`policy_state` on the device
page, prefs grep, the `KidSettings` log line, which now names the case) and write the cause down
before closing it. Everything under "[device]" in qa-08-design.md is untested.

### Fix round after qa-08-code.md (2026-10-05)
All nine items addressed (8 = S, the rest L; 9 = device checks, still open). L `ce1efcb`, `e3a66d9`,
`ebe801f`, `CLAUDE.md` commit; S `e38512e` + `CLAUDE.md` commit. L assemble + unit tests green; S 146
tests, fmt and clippy (25 = baseline) clean.
1. `hardeningClearSteps`: the wallpaper reset runs right before `DISALLOW_SET_WALLPAPER` is cleared,
   in the early clear pass and at the end of `apply()`; the restriction stays until the reset worked;
   each step exception-safe (pure order test).
2. Lock wallpaper set before home; `ApplyOutcome` + `partial_key` record a half-applied photo;
   `resetIfOurs` falls back to `WallpaperManager.clear`, empties the record only when it worked, and
   is retried on every pass.
3. `revokedImageShows`: a photo of ours no longer allowed/cached → `Apply` on every sync regardless of
   the daily backoff, and a reset when that apply fails (tests for revoked + failed today).
4. Thumbnails: tile-size centred squares (~75 KB), 8 MB LRU, one decode in flight per photo, failures
   not retried until the next sync, a separate thumbnail listener redraws only the picker.
5. Icons rendered from `constantState.newDrawable().mutate()` - the drawer's drawable is untouched.
6. `State.systemShowsOurs` computed on the store thread (refresh on each kid screen's resume and after
   every apply); `WallpaperGround` makes no binder call.
7. Escape guard extended to `HomeActivity`, `ui/home`, `PhoneBookActivity`/`PhoneBookAdapter` and
   string actions (`"android.settings.`, `SET_WALLPAPER`, wallpaper service, chooser); the long-press
   source-line check is kept (accepted as brittle).
8. S: wallpaper query error → policy with `wallpapers: []` + error log (test drops the table); backup
   skips a file deleted mid-run (dangling-link test). Note: the phone then treats the empty list as
   "navy only" and deletes its cached photos - the safe direction.
9. Still open: everything [device] (findings 1-3 to re-check with `adb shell dumpsys wallpaper` after
   unmanage and after a forced lock-set failure) and the Quick Controls root cause.

### Screenshot checklist (emulator ~320×568 dp, nb and en, side by side with the mockups)
- [ ] 1. Home, 2 Home contacts, 3 cols, navy: no clock, light status icons, sizes/gaps ±2 dp, Nunito.
- [ ] 2. Same with 4 cols; with 5 contacts (scroll + peek); with 1 contact; 4 contacts at 288 dp scroll.
- [ ] 3. Calls managed + off, no emergency contact: Phone book tile present; calls unmanaged: absent
      (`adb shell uiautomator dump`, look for "Phone book"/"Telefonbok").
- [ ] 4. Icons: an app with a monochrome layer = white glyph on its colour; one without = full icon;
      a legacy icon = inset on its colour.
- [ ] 5. KidSettings with mask 7, 1, 0 (0: no card, no heading), not provisioned (own text), no policy
      yet (own text); Wi-Fi toggle and slider work; a mask change on S updates the open screen.
- [ ] 6. Each built-in + one light photo + one dark photo + a busy mid-grey photo on Home, phone book
      and KidSettings: labels legible (4.5:1 sampled under labels), dark-ink wallpaper gives dark
      status-bar icons, TalkBack reads the selected wallpaper.
- [ ] 7. After picking: Home → recents/swipe-up and lock screen match (photo: lock screen navy unless
      "also on the lock screen" is ticked).
- [ ] 8. Settings → Wallpaper / Photos "set as wallpaper" blocked on a managed phone; our pick applies
      (`setBitmap` id ≠ 0 in the log); unticking the applied photo on S replaces it within one sync;
      unmanaging resets to navy, then lifts the restriction.
- [ ] 9. PhoneBook and ContactCard unchanged except font and wallpaper; LockActivity at bedtime comes
      up over Home, KidSettings and the Wi-Fi subscreen; 112 works.
- [ ] 10. Slow phone: Home cold start with 20 apps, no frame > 32 ms from icon work
      (`dumpsys gfxinfo`); heap with a photo wallpaper < 15 MB over the colour baseline.
