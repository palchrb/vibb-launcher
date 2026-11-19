# Code QA: `brightness-sheet` (Snarveier's Lysstyrke opens a slider sheet)

Branch `brightness-sheet`: accbddf21 (the sheet) over main 74278177c, worktree `/workspace/handy-wt/brightness-sheet`.
QA's own commits on it: 8cfd25c70 (M1), 1e1214488 (M2), b1d080ec0 (lint), and after the coordinator's decisions
638aadd3c (L1) and 643ce89a1 (M3).

**What the branch does.** Tapping the sun in Snarveier now opens `shortcuts/BrightnessSheetActivity`, a small bottom
sheet with a slider. It replaces the cycle through three brightness steps (D5).

**The spec** (the user's mockup "Valg 1"):
- The sheet holds "Lysstyrke", a small sun, kid Settings' slider and a big sun, and nothing else.
- The light follows the drag. A tap outside or a drag down closes it.
- It must not be a way out of the kiosk.
- It must work on Android 14-17, in Lys and in Mørk.

**What was read:**
- `git diff main...brightness-sheet`
- `KidSheet.kt` and `KidSheetDialog`
- `ShortcutAirplaneActivity`
- `QuickControls.setBrightness`
- kid Settings' slider
- `lookOver`/`nightOver` and `PageLook`
- the manifest entry and the tests
- design 99 (§2.1, S14, Q99-L1, D5) and `docs/notes/launcher/kid-settings.md`

**Builds and tests.** `assembleDebug` and the touched tests (`*Shortcut*`, `KidPaletteTest`, `PageTokensScanTest`) were
run with `-PwarningsAsErrors=true` after each commit, and were green. The full run on b1d080ec0 was green too
(`assembleDebug testDebugUnitTest lintDebug`): 2840 tests, 0 failures, and lint passed (only the baseline's errors).
- **Lint (CI's failure, fixed in b1d080ec0):** the run on 1e1214488 failed on `UseAppTint` at the two suns in
  `sheet_brightness.xml`.
  - `app:tint` would not work here. The sheet is a platform `Activity`'s `Dialog`, inflated by the platform
    `LayoutInflater` with no AppCompat factory, so the attribute would be dropped and the white glyphs would stay white
    on Lys's white sheet.
  - So the suns keep `android:tint`, with `tools:ignore="UseAppTint"` and the reason in the layout.

**Result: 0 H, 3 M, 4 L.**
- M1 and M2 are fixed on the branch:
  - 8cfd25c70: the brightness sheet's empty track shows in Lys.
  - 1e1214488: kid sheets reach the screen's bottom on Android 14 too.
- M3 and L1 were fixed after the coordinator's decisions:
  - 638aadd3c: the sheet never opens over a keyguard (L1).
  - 643ce89a1: the sheet draws its own dim behind it (M3).
- The full `assembleDebug testDebugUnitTest lintDebug` run on 643ce89a1 was green: 2840 tests, 0 failures, lint passed
  (only the baseline's errors).

## Findings

### M1 (fixed, 8cfd25c70): the slider's empty track can't be seen in Lys

- **What the code does:** the day token `kid_page_track` was `#E6DDD8`.
- **Why it failed:** the platform `SeekBar` draws its empty track through `white_disabled_material`, at 30 % alpha.
  On the white sheet the token therefore landed as `#F9F6F5`, a contrast of about 1.06. On the emulator the empty part
  of the slider was invisible, so the kid could not see where the slider ends.
  - Mørk's `#66FFFFFF` lands as `#383644` on `#1C1A2A`, which is visible.
- **The fix:** the day token is now `#AC8E7D`, which lands on the intended `#E6DDD8` over white. This was checked in
  Lys on 16 and 14.

### M2 (fixed, 1e1214488): on Android 14 the sheet sits on a black band

- **What happens on 14:** the `KidSheetDialog` window fits above the navigation bar, and the dialog theme paints the bar
  black. The device's own `screencap` showed rows 818-853 as `#000000` under the sheet, which looks worst under Lys's
  white sheet.
  - This is the band the user had fixed on 2026-11-09 (29f5f70bd). It was fixed then only where Android draws edge to
    edge for apps targeting 35+, that is on 15 and later. The launcher targets 36.
- **The scope:** `setUpKidSheet` is shared, so the contact, emergency and photo sheets had the same band on 14.
- **The fix:**
  - `setUpKidSheet` calls `WindowCompat.setDecorFitsSystemWindows(window, false)`.
  - `KidSheetDialog` sets a transparent `navigationBarColor`, as `RuleSheetTheme` already does.
  - The content's bottom padding already took the bar's inset.
- **Checked on 14:** the sheet reaches row 853 in Mørk (`#1C1A2A`) and in Lys (`#FFFFFF`), and its bottom padding
  clears the gesture handle.
- **Checked on 16:** nothing changed there; Android 15+ was already edge to edge.
- **Not seen on the emulator:** the other three sheets on 14. This emulator's calls are unmanaged, so Vibb's phone book
  wasn't reachable. They use the same window code.

### M3 (fixed by decision, 643ce89a1): 3-button navigation in Lys, the bar's buttons are white on the white sheet

- **What happens:** with 3-button navigation and the Lys look, Back, Home and Recents are white over the white sheet.
  - On 16 they were invisible.
  - On 14 only a faint outline showed.
  - Gesture navigation is fine: the handle takes its colour from what is under it, and was dark grey on 14 and 16.
- **The causes:**
  - Android drops the light-bar flag of a window that dims what is behind it (`DisplayPolicy`'s
    `updateLightNavigationBarLw` clears it for a dimming window). A `WindowInsetsController` light-bar flag on the
    dialog was tried on 14 and did nothing.
  - Lys's page theme has `enforceNavigationBarContrast` off, so Android draws no scrim behind the buttons.
    `isNavigationBarContrastEnforced = true` was tried on 16 and drew no scrim either.
  - Both tries were reverted.
- **The ways out are visible changes:**
  - (a) Draw the sheet's own dim: a full-screen window without `DIM_BEHIND`, as the time-rule screen's
    `RuleSheetDialog` does, with the light-bar flag set in Lys.
  - (b) A dark band behind the buttons in Lys.
- **The decision:** (a), the sheet draws its own dim. That is also what the mockup shows.
- **The fix (643ce89a1):**
  - `setUpKidSheet(content, ownScrim = true)`. The window covers the screen and has no `DIM_BEHIND`. A scrim under
    the sheet dims the screen at the window dim's 50 %.
  - The scrim fades with the drag. A tap on it slides the sheet down. Back fades the window out. The sheet slides in
    from code.
  - The sheet asks for light navigation buttons in Lys (none in Mørk) and turns off the contrast scrim. With the scrim
    on, SystemUI shows a semi-transparent bar and ignores the light flag.
  - The status icons stay light on the dimmed top.
  - The other kid sheets keep the window dim.
- **Checked on 14 and 16, Mørk and Lys, gesture and 3-button navigation:**
  - The screen is dimmed behind the sheet, the status bar included.
  - The sheet reaches the bottom.
  - The slider writes the brightness.
  - A tap on the scrim, a drag down, Back and Home each close the sheet.
  - In Lys with 3-button navigation the buttons are dark on the white sheet. This was seen after a fresh boot in
    3-button mode. Changing the navigation mode at runtime leaves SystemUI's button tint stale, and showed white
    buttons until a restart.
  - The window's own flags are right:
    - Lys: `apr=LIGHT_NAVIGATION_BARS`, and SystemUI reads `mHasLightNavigationBar=true`.
    - Mørk: no appearance flag, and `mHasLightNavigationBar=false`.
- **What the emulators couldn't settle:** in Mørk with 3-button navigation the buttons were dark grey. Home itself
  showed the same dark-grey buttons, as a control. SystemUI on these emulators reported `mForceLightForScrim=true`
  after boot, which forces dark buttons on every window. That is SystemUI's state, not the sheet's. Owed: a look at the
  Jelly Star in Mørk with 3-button navigation.

### L1 (fixed by decision, 638aadd3c): the keyguard exemption and `showWhenLocked` are never reached

- **The question:** can brightness passing the tap check's keyguard gate open anything else on the lock screen?
  **No.**
  - The exemption covers only `ShortcutKey.BRIGHTNESS`.
  - A Lysstyrke broadcast is a logged no-op in `act`.
  - Every other key is still refused on a keyguard (`ShortcutsTest`).
  - The sheet has no intent, link or button out (a source test pins this).
- **On the emulator, Snarveier never shows on a Swipe keyguard** (14 and 16). This held even with "show silent
  notifications" on the lock screen (`lock_screen_show_silent_notifications 1`). The notification is
  `VISIBILITY_SECRET`, and SystemUI hides it there too.
- **A tap would unlock first anyway.** An activity `PendingIntent` tapped in the shade over a keyguard goes through
  SystemUI's dismiss-then-launch, so the sheet would never open over a keyguard.
- **The wrong premise:** `kid-settings.md`'s sentence "a Swipe keyguard shows the panel" (Q99-L1's premise) did not hold
  on either version.
- **The fix (638aadd3c):** both are gone. The tap check refuses Lysstyrke on a keyguard, as it refuses every item;
  the tests say so. The manifest entry has no `showWhenLocked`, and a test checks that. The note is updated.

### L2: Recents while the sheet is open

- The kiosk allows Overview, so Quickstep's Recents opens over the sheet.
- The sheet's activity stays `RESUMED` behind it and gets no card (it is out of Recents).
- Back from Recents went Home, with the sheet finished. A card tap stops and finishes it.
- Nothing new can be reached. This was the same on 14 and 16.

### L3: an empty task stays in Android's recents list

`dumpsys activity recents` keeps an empty task for the sheet (`Activities=[]`, `LOCK_TASK_AUTH_ALLOWLISTED`). It is
excluded from the Recents screen, as `ShortcutAirplaneActivity`'s is. No action is needed.

### L4: kid Settings' own slider has the same invisible track in Lys (not this branch)

- `activity_kid_settings.xml`'s slider has `progressBackgroundTint="#66FFFFFF"` in both looks.
- If that section is drawn on the white page in Lys, its empty track is white on white.
- This was found by reading only, not seen on the emulator. The sheet's `kid_page_track` could be used there too.

### Checked and fine

- **The manifest entry:**
  - `exported=false`, with no intent filter
  - `taskAffinity=""`, `excludeFromRecents`, `noHistory`
  - no `showWhenLocked` since 638aadd3c (L1), no `turnScreenOn` and no keyguard dismiss
  - the one-pixel window (`claimNoBars`), so the bars stay as the app in front left them
  - in lock task the sheet's task is `LOCK_TASK_AUTH_ALLOWLISTED`
- **Leaving:** `onStop` finishes the sheet. This was seen for Home, the screen off, the lock and Recents → Back.
  `onDestroy` dismisses the dialog with its listener cleared, and the tap thread's post checks
  `isFinishing`/`isDestroyed`. There are no receivers or observers. The removed brightness `ContentObserver` and its
  imports are gone.
- **The brightness write:** `QuickControls.setBrightness` uses the device owner's `setSystemSetting` (manual mode, then
  the level), the same path as kid Settings' slider, so no app-op is needed. Only `fromUser` changes write.
- **The tap check:** it is reused through `ShortcutsRuntime.verdict` on the tap thread. A refusal finishes the sheet
  before anything shows.
- **Removed code:**
  - `BrightnessStep`, `ShortcutItem.level` and `ShortcutsPlatform.brightness`
  - the four strings (`shortcut_desc_level`, `shortcut_level_*`)
  - the three step glyphs
  - Nothing references them any more. `scripts/material-symbols.sh` pins `light_mode` in their place.
- **Strings and accessibility:**
  - No new strings: the title and the slider's description are `shortcut_full_brightness` (Lysstyrke / Brightness).
  - The cell's description is the name alone, as the words rule asks.
  - The title is a heading, `labelFor` points at the slider, and the suns are not important for accessibility.
- **The look:** `lookOver` resolves the page tokens in the applied look. Mørk and Lys were both right on 14 and 16.

## Emulator rows

**The rig.**
- One emulator at a time under `flock -o ~/emulator.lock`. The user's `jellystar2` was not touched.
- The debug server 0.85.2 (`build-119`'s binary) on a copy of the 119 gate's data.
- The branch's debug launcher as Device Owner, enrolled with a typed code. Setup ended with Finish, so lock task was
  `LOCKED`. Snarveier was on (mask 239).
- The kid's picks: Lommelykt, Lyd, Wi-Fi, Lysstyrke.

**The emulators.**
- Android 16 is `jelly36`:
  - Before the run it did not match `~/kid-look-1-avd-pre-start`: another run had booted it at 18:12 UTC. It was
    restored first.
  - At the end it was restored again: 22 of 22 checksums OK, the same file list, `jelly36.ini` equal.
- Android 14 is `compat34`, started with `-wipe-data`.
- Android 17 was not run.

**Screenshots** are in the session scratchpad's `bsqa/shots/`, not in the repo.

| Row | Android 16 (`jelly36`) | Android 14 (`compat34`) |
|---|---|---|
| The panel's sun → the sheet, shade closed | **Pass.** "Brightness", the small sun, the slider at the current level (102), the big sun; dimmed behind | **Pass.** The dim shows in the device's `screencap`, not in the emulator's host frame (an emulator compositing artefact) |
| The slider moves the light | **Pass.** It reads 161 → 189 → 216 → 245 during one 3 s drag; mode manual | **Pass.** 146 → 192 → 238 |
| A tap outside / a drag down / Back closes it | **Pass** (Home on top, no sheet left) | **Pass** |
| Home | **Pass.** It finishes | **Pass** |
| Recents | L2: Recents opens; Back → Home, the sheet gone | The same |
| The screen off | **Pass.** It finishes; on wake the Swipe keyguard, no sheet | **Pass** |
| Over an app (Clock) | **Pass.** A tap outside → Clock in front again | Not run |
| A Swipe keyguard | L1: no Snarveier on the lock screen, even with silent notifications shown | The same |
| Mørk | **Pass** | **Pass** after M2 (before it: the black band) |
| Lys | **Pass** after M1 (before it: the empty track unseen) | **Pass** after M1 and M2 |
| 3-button navigation, Lys | **M3** at accbddf21 (the buttons invisible); **Pass** at 643ce89a1 (dark buttons after a fresh boot in 3-button mode) | **M3** at accbddf21 (the buttons faint); **Pass** at 643ce89a1 (after a SystemUI restart) |
| The own scrim (643ce89a1), Mørk and Lys, gesture and 3-button | **Pass:** the screen dimmed behind the sheet, the status bar included; a tap on the scrim, a drag down, Back and Home each close it; the slider writes. In Mørk with 3-button the buttons were dark grey, as on Home itself (SystemUI's `mForceLightForScrim`, M3) | **Pass**, the same |
| The picker's glyph (`light_mode`) | **Pass** | **Pass** |
| nb | The strings were read only (`Lysstyrke`): `set-app-locales` didn't move the managed launcher | Not run |
| TalkBack | Not run | Not run |
