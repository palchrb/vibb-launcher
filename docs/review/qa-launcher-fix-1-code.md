# QA (code): fix round `launcher-fix-1`

Branch `launcher-fix-1`, one commit 7af16c319 on `main` 703c1f9ce. It adds the exported `SettingsSinkActivity`
(the gear bug), `ShortcutWindowTheme` (black bars around Snarveier's Flymodus window) and drops the shade header's sub
text, with `SettingsSinkTest`, `ShortcutWindowTest`, `hardening.md`, `kid-settings.md` and `docs/testing/emulator.md`
§6ar. I reviewed it on 2026-11-16 by reading only (`git diff main...7af16c319`). I ran no Gradle, no emulator and no
device, pushed nothing and fixed nothing.

Counts: **0 High, 2 Medium, 5 Low.**

## Summary

- **The sink is safe to export.** Its body is `super.onCreate`, one log line and `finish()`. It reads no extras,
  data, clip data or selector, starts nothing, sets no result and logs only one of its own action strings. An
  explicit start by another app does nothing except create and destroy one activity. The sink cannot act as a
  trampoline, because it starts nothing and so lends no background-launch privilege. It cannot be hijacked: it has a
  null affinity, never stays alive and has no `allowTaskReparenting`. It is not `showWhenLocked`, so the keyguard
  can't show it. It is in no Recents list (`excludeFromRecents`, `noHistory`). It has no filter outside the seven
  `android.settings.*` notification actions and has no `<data>`.
- **`taskAffinity=""` is load-bearing, and it is pinned.** `TaskStackBuilder` adds `NEW_TASK | CLEAR_TASK |
  TASK_ON_HOME` to the first intent. With the package's own affinity, `CLEAR_TASK` would have cleared Home's task.
  `SettingsSinkTest` pins the empty affinity.
- **The lock task is unchanged.** The sink is in our own package, which is always in the lock-task list
  (`allowlist = []` pins our package). It therefore starts inside the kiosk, never outside it, and it adds no package.
  While LOCKED, the PIN lock's lock-task features drop NOTIFICATIONS and `setStatusBarDisabled(true)` holds, so no
  shade can reach the sink over the PIN lock. That matters because `TASK_ON_HOME` would land on Home, and the PIN
  lock is its own task. In the kiosk-unlocked state, the gear from an allowed app lands on Home afterwards. That
  matches stock behaviour with Settings, which also returns to Home.
- **The other lifecycle code is unaffected.** `Theme.NoDisplay` with `finish()` in `onCreate` is legal on 14-17: the
  activity is never resumed or started. The lifecycle callbacks in `ScreenTimeTracker`, `PinLockRuntime` and
  `GlanceOpener` only act on resume, pause and destroy, so they never count the sink.
- **Priority -1 is the right sign.** A non-privileged app can't set a priority above 0. `chooseBestActivity` returns
  the first match without a chooser when the top two priorities differ. With a visible Settings at 0 or 1 above the
  sink at -1, Settings opens with no chooser. That is the restrictions pause and "Åpne for oppsett", and §6ar's pause
  row confirms it on the emulator. L2 covers the tie.
- **No API above 34 is used.** Every new theme attribute is API 30 or lower (`windowLayoutInDisplayCutoutMode`,
  `enforce*Contrast`). `overrideActivityTransition` (34) was already there.
- **Design 98's route is unchanged in code.** The window is still translucent, not floating, has its own task and
  still waits for first focus. The theme changes only what is drawn, and §6ar shows the route turning airplane mode on
  in 1220 ms.
- **The tests pin the main points:** the filter equals `ACTIONS`, the attributes, a body that only finishes, the
  theme's items and the header. The gaps are in L1.

## High

None.

## Medium

### M1. The Flymodus window likely sets the bar icons to white over a light Home

`ShortcutWindowTheme`'s parent is the dark `Theme.Material.NoActionBar`, and the theme sets no
`windowLightStatusBar` or `windowLightNavigationBar`. WindowManager takes the bars' appearance from the topmost
full-screen app window that overlaps them, translucent windows included (`DisplayPolicy`'s status bar appearance
regions). The window is `MATCH_PARENT` and draws the bar backgrounds, so for its life (about 1.2-5 s, through the
voice activity) the status icons and the gesture handle become white. Home sets them from the wallpaper's ink
(`WallpaperGround`: `isAppearanceLightStatusBars = state.ink.darkInk`), so on a light wallpaper the icons flip to
white and nearly disappear, then flip back when the window closes. With the old theme this was hidden behind the
black bars. §6ar measured only black rows, on Home's ground, and never looked at icon colour.

**Fix:**
1. On `jelly36`, set a light wallpaper (dark ink) and record Flymodus from the panel.
2. If the icons flip, take one of these two fixes:
   - In `ShortcutAirplaneActivity.onCreate`, copy Home's current appearance. For example, keep the last `darkInk`
     that `WallpaperGround` applied in a `@Volatile` field. Then call
     `window.insetsController?.setSystemBarsAppearance(flags, APPEARANCE_LIGHT_STATUS_BARS or APPEARANCE_LIGHT_NAVIGATION_BARS)`.
     This needs API 30, so no gate.
   - Or make the window claim no bars at all with `window.setLayout(1, 1)` in `onCreate`. A window that isn't full
     screen draws no bar backgrounds and sets no appearance. Re-run the focus gate (design 98's 1 s, §6am) if you
     choose this one.
3. Pin the choice in `ShortcutWindowTest`.

### M2. Checked on Android 16 only, but both fixes depend on the Android version

The standing rule (root `CLAUDE.md`, 2026-11-16) says an emulator gate that depends on the version runs on the lowest
and the highest version it touches, and that the note names those versions. §6ar ran on `jelly36` (API 36) only, and
`hardening.md` names no version. Both fixes depend on the version:

- **The theme.** Edge-to-edge is enforced from API 35 for target 35+. On Android 14 the decor still lays content
  inside the bars and paints its own bar-background views with `statusBarColor` and `navigationBarColor`. Transparent
  should be right there too, but that is a different code path from the one measured.
- **The sink's action list.** It comes from one release's `StatusBarNotificationActivityStarter`. The other intents
  that `NotificationGutsManager` sends through the same `startNotificationGutsIntent` path, such as
  `startAppDetailsSettingsActivity` (`APPLICATION_DETAILS_SETTINGS`) and `startAppOpsSettingsActivity`
  (`MANAGE_APP_OVERLAY_PERMISSION`), would crash SystemUI the same way wherever a release can reach them. Android
  16's notification bundles and anything new in 17 can add more.

**Fix:**
1. Run §6ar's Before, gear, Manage, History, pause and Flymodus rows on an API 34 image, and on the highest image
   available (37 if one exists).
2. For the 14 and 17 tags, grep AOSP's `StatusBarNotificationActivityStarter` and `NotificationGutsManager` for every
   intent that reaches `startNotificationGutsIntent`, `startHistoryIntent` or `startSettingsIntent`.
3. For each one that is reachable with the kiosk unlocked, add its action to `SettingsSink.ACTIONS` and the manifest.
   An action with a `package:` URI needs a second filter with `<data android:scheme="package"/>`, and its test has to
   allow that.
4. Name the versions checked in `hardening.md` and in §6ar's setup.

## Low

### L1. The tests leave three gaps

- **The base class.** `SettingsSinkTest` doesn't pin it. An `AppCompatActivity` (or any theme-checking base) under
  `Theme.NoDisplay` throws in `onCreate`, and the sink runs in the Device Owner's own HOME process. Any shade tap
  would then crash the kiosk. **Fix:** assert that `class SettingsSinkActivity : Activity()` is in the source.
- **The other risky attributes.** The test pins only the five expected ones. **Fix:** also assert that
  `showWhenLocked`, `turnScreenOn`, `launchMode`, `allowTaskReparenting`, `lockTaskMode`, `directBootAware` and
  `process` are absent. Then a later edit can't quietly put the sink over the keyguard, keep it alive or move it out
  of the lock-task rules.
- **The body scan.** It bans `.data` but not `clipData`, `selector` or `getBundle`. **Fix:** ban `intent` everywhere
  in the source except the one `intent?.action` use.
- **The theme-copy check.** `ShortcutWindowTest` looks for a qualified copy of the theme only in `values-*/styles.xml`.
  **Fix:** scan every XML file under `res/values-*`.

### L2. A tie at priority -1 would show a chooser

`chooseBestActivity` skips the chooser only when the top two priorities differ. While Settings is hidden, any other
handler of one of these actions at -1 (an OEM component or an allowed app) brings up `ResolverActivity`. That is an
`android` activity in a new task under lock task. The 1 in §6ar is the emulator's Pixel Settings. The Jelly Star's
own Settings (Android 16, Unihertz) wasn't queried.

**Fix:**
- Use a much lower priority, such as `-999`. Below 0 changes nothing against Settings and makes a tie unlikely.
  Update the test.
- On the Jelly Star, run `cmd package query-activities --brief -a <action>` for each action, once in the pause and
  once with Settings hidden. Record that Settings is at 0 or above and the sink is the lowest.

### L3. `windowAnimationStyle @null` is credited with a fix it probably didn't make

`ShortcutAirplaneActivity.onCreate` already called `overrideActivityTransition(OPEN/CLOSE, 0, 0)` before this commit,
yet the black rows still slid down. So the slide was not the activity's own transition, and under shell transitions
`@null` very likely changes nothing. The transparent bars are the fix. Even so, the styles.xml comment says the bars
slid "with its exit animation", `kid-settings.md` says "no window animation", and `ShortcutWindowTest` pins `@null`
as if it mattered.

**Fix:**
- Reword the comment and the note: the bars were the legacy bar backgrounds, and they are gone because they are
  transparent.
- Keep `@null` as harmless, but drop it from the test's list of what the fix needs.

### L4. Other translucent windows may show the same black bars

`MessagesEntryActivity` (a message notification's tap), `ComposeActivity`, `VoipWakeActivity` (over the PIN lock),
`ProvisioningModeActivity` and `PolicyComplianceActivity` still use `Theme.Translucent.NoTitleBar`. The kid-facing
ones finish fast but may still flash the bars. **Fix:**
1. Record a message notification's tap and a VoIP wake on `jelly36` with `adb emu screenrecord`.
2. If they flash, move those windows to `ShortcutWindowTheme`.
3. `PageManifestTest` pins `MessagesEntryActivity`'s theme literally, so update it with the move.

### L5. `hardening.md` doesn't say where the kid ends up after the gear

`TaskStackBuilder`'s `TASK_ON_HOME` sends the kid to Home after the sink, not back to the allowed app they were in.
It is also why a sink tap can't reach Home over the PIN lock: the shade is blocked there (see the Summary). A later
change to the PIN lock's lock-task features (adding NOTIFICATIONS while LOCKED) would turn this into a way past the
lock. **Fix:** add two sentences to `hardening.md` naming both points, so that change is caught in review.

## Checked and fine

- The manifest filter equals `SettingsSink.ACTIONS`, in order, with category DEFAULT and no data. All seven action
  strings are the platform's.
- `configChanges="uiMode"` is there for `PageManifestTest`'s rule that every activity has it. It is harmless here.
- No caller of the launcher's own code resolves these actions. `UpdateFence` and the helper resolution query
  `ACTION_SETTINGS`, which the sink doesn't answer, so nothing in the launcher now resolves to the sink by mistake.
- The removed `shortcuts_subtext` has no other references. No older doc or gate still expects "• Snarveier".
- The docs (`hardening.md`, `kid-settings.md`, §6ar) match the code, except the points in M2, L3 and L5.
