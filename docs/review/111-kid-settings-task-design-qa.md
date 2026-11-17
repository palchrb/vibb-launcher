# Design QA: every built-in "app" behaves as a native app (Innstillinger as a task of its own)

Reviewed: `design-native-apps.md` against main (253dba1bf): the manifest, `KidSettingsActivity`, `KidSettingsModel`,
`WifiNetworksActivity`, `BluetoothDevicesActivity`, the two pickers, `KidPageActivity`, `PageLook`, `LauncherLocale(s)`,
`HomeActivity` (the swipe, `onNewIntent`, `closeOverlays`), `HomeFront`, `LockActivity.start`, `TimeRulesRuntime`,
`WindowRelock`, `CaptivePortalEntry`/`PortalWindowActivity`/`PortalRuntime`, `VibbSessionService`/`AirplaneRoute`,
`ShortcutAirplaneActivity`, `ScreenTime.FREE_SCREENS`, `KidScreensEscapeTest`, `PageManifestTest`, the precedents
(b3094f31b, aa6369bce with `docs/review/qa-phonebook-task-code.md`, e490362f0), `docs/notes/launcher/ui-polish.md` (R9,
the wallpaper drawing), `lock-and-glance.md` (the shell's wallpaper-intra transition), `logins.md` and design 102 §3.5.
Nothing was built or run.

Counts: **0 High, 1 Medium, 8 Low.**

## What holds

- **The survey is complete.** The only callers of `KidSettingsActivity` are `HomeGridAdapter:120` and
  `HomeActivity:398`. No `PendingIntent`, notification, Snarveier cell or trampoline names Kid Settings or any of its
  four sub-pages, and each sub-page is started only from Kid Settings (the pickers' own `start()` take the activity as
  their context, so the start stays in the task). `ShortcutAirplaneActivity` has `taskAffinity=""`, so Flymodus from the
  shade never touches the new task.
- **Lock task.** Lock task is engaged only by Home's `onResume` and by the PIN lock's own fallback, so `.kidsettings`
  can't become `mLockTaskModeTasks[0]` except through the crash-loop residue that `kiosk-escapes-and-updates.md` already
  names for every page task. A new task of our permitted package joins as `.day`/`.messages`/`.phonebook` do. A Back at
  Kid Settings' root is a non-root locked task's finish, not `activityBlockedFromFinish`. Home's `stopLockTask` (the
  kiosk going off) runs `clearLockedTask` on the root, which `performClearTaskForReuse`s every other locked task. Kid
  Settings' card is emptied then, as Dagen's is, and that is harmless.
- **The phone book's M1 (base intent drift) does not apply.** The one builder is component plus `NEW_TASK`, with no
  action, data or extras, and no forward rewrites the root's intent. A tile tap with a sub-page on top is filter-equal
  to the base intent, so the task comes forward as it was and nothing stacks.
- **The phone book's H1 (an outside app inside our task)** does not apply to an outside package: nothing in the five
  files starts one. The only other activity that can join the task is our own Wi-Fi sign-in (L4).
- **Parent gating.** Kid Settings shows no parent-only data and has no gate. The model is the mask, the wallpapers and
  the "shares location" line. The Bluetooth page's one pause-gated power, pairing any device class during a
  restrictions pause, is checked again at the tap (`onPairTapped` → `pairOffered`), so a stale card can't pair after
  the pause (L3 is about what it shows). The sign-in entry's parent-code keypad finishes in `onStop`, so a card can never
  resume a passed gate.
- **Home's clears, one by one.**
  - Home pressed (`clearTaskOnLaunch`): replaced by the task going to the back.
  - A time rule starting (`TimeRulesRuntime:302`, `WindowRelock:733`): `LockActivity.start` goes to Home's task, and
    Kid Settings' resume redirect, plus the new one on the Wi-Fi/Bluetooth pages, covers a card brought back.
  - `HomeFront.bring` (a pause ending, the lock leaving, a role change, an update): singleTask used to clear Kid Settings
    out of Home's task. Now Home comes in front and the card stays. Nothing in the card depends on the pause, apart from
    L3.
  - `closeOverlays`: Home's dialogs only, never Kid Settings.
  - The look: `closesTask` must keep Kid Settings out, and does. With Kid Settings in front `pages.started` is 0, so the
    change runs at once, and no sub-page of its task is alive then.
  - The language: covered by `languageClosesTask`.
  - The PIN lock's LOCKED edge: Recents is off while LOCKED, the lock leaves through Home, and `closeWindow("lock")`
    ends a sign-in window.
- **Flymodus.** The voice activity is started by `startVoiceActivity` in a task of its own. When it finishes, focus
  goes to the next locked task in z-order, which is now Kid Settings' task instead of Home's task with Kid Settings on
  top. The outcome is the same, and row 5 checks it.
- **Screen time** is unchanged: `FREE_SCREENS` doesn't list Kid Settings, today or after the change.
- **"The card stays" after Back** is right: AOSP removes an emptied task with `!REMOVE_FROM_RECENTS` unless
  `autoRemoveFromRecents` is set. b3094f31b's "in Recents until Back" is the inexact one.

## Medium

### M1. The wallpaper theme makes Kid Settings' open and close the shell's wallpaper-intra transition, not Meldinger's task animation (§2.1, §2.3, §4 row 2, Q1)

Kid Settings' window has `FLAG_SHOW_WALLPAPER`. Today it gets the flag at runtime (`showsSystemWallpaper() = true` →
`Background.TRANSPARENT` = `backgroundWallpaper`), and with §2.3 it gets it from the manifest too. Home's window has it
as well. When the opening and the closing change both carry the flag, WM Shell picks the wallpaper-intra animations
(`getWallpaperTransitType` → `WALLPAPER_TRANSITION_INTRA_OPEN/CLOSE` → `wallpaperIntraOpen*`/`wallpaperIntraClose*`)
ahead of the task-open and task-close animations. The project has measured this once already:
`lock-and-glance.md`:87 sets `FLAG_SHOW_WALLPAPER` on the glance precisely so that its leave becomes "the shell's
wallpaper-intra transition". Meldinger, Dagen and the phone book are opaque (`KidPageNightTheme`), so their transition
from Home is the wallpaper-close/open kind, a different animation.

As a result, §2.1's "Android's task-close animation slides it away" and row 2's "Home slides it away as it does
Meldinger" are probably false. Today's in-task switch between Home and Kid Settings is already intra-wallpaper, so
the tile and the swipe may well look exactly as they do now. Q1's premise, that the swipe changes from "today's
in-Home transition" to "the system's app-open animation", may therefore not hold either. Animation is one of the
user's two complaints, along with Recents.

Kid Settings no longer needs Android's wallpaper behind it: since qa-launcher-small-a-code Q-1 it draws its own full
copy (`WallpaperGround.apply(ownGround = true)` → `GroundKind.FULL` always). The flag now serves only to suppress the
starting window (§2.3), and that is exactly what produces the intra transition.

Fix: make it an explicit choice, and put it to the user in place of Q1, since it is a visible cost:
- (a) **Keep the wallpaper theme.** No starting window, but the intra transition, roughly today's motion. Row 2 then
  checks for the intra animation, not Meldinger's.
- (b) **Make Kid Settings opaque.** `showsSystemWallpaper()` false and a `KidSettingsTheme` with an opaque night ground
  and `windowSplashScreenBackground`, as `KidPageNightTheme` has. That gives the same task animation as Meldinger, with
  the precedents' starting window (the night ground) shown for a frame or two before the kid's photo or colour.

Whichever is chosen, row 2 must record the transition type actually seen (`dumpsys` or a `screenrecord` compared with
Meldinger's), on both 34 and 36. The swipe's direction question in Q1 only matters once (b) is chosen.

## Low

### L1. Location on `onStart`/`onStop` needs two more rules (§2.2 "Location")
- **The connect poll outlives `onStop`.** `pollForConnection` keeps posting scans on `handler` for up to 15 s. With
  Location off, the scans can't see the network as connected (scan results empty, SSID redacted), so a connect that
  succeeded ends in "couldn't connect, check the password" over whatever the kid went to. The main case is the
  hotel's open captive network of design 102: connect, then tap "Logg på" at once. The entry covers the page, `onStop`
  turns Location off, and the false toast lands on the sign-in. Fix: in `onStop`, drop the poll
  (`handler.removeCallbacksAndMessages`, `connectingSsid = null`, no toast), or keep Location on until a running poll
  ends.
- **Order under a rule or a removed mask.** The Location switch-on and the scan belong after the mask check (`onStart`)
  and after the rule check (`onResume` today). Otherwise a card brought back under a rule turns Location on and starts a
  scan, and Bluetooth discovery (`BluetoothDevicesActivity.onStart` → `scan()` on the first start), just before it
  redirects. Put both checks first, in `onStart`, and return before any radio work.

Both are better as a pure step function with rows (`wifiLocationStep(event, enabledNow, byUs, polling)`) than as source
pins (L6).

### L2. A process-death restore from Recents draws the splash, not the snapshot, on 12+ (§2.3, row 6)
Shell's `PhoneStartingWindowTypeAlgorithm` returns a splash for `!processRunning`, or for a task switch with
`!activityCreated`, before it considers a snapshot. So a card brought back after a process death (or with the
activity destroyed in a live process) gets a splash: the manifest theme's `windowSplashScreenBackground` with the Vibb
icon. For a sub-page that is `KidPageTheme`'s `kid_page_ground` in the **phone's** night mode, the wrong-look flash
design 78 R3 built the twins to avoid. The design treats it as a fallback that might not be needed. Expect it instead:
row 6 should record it as the likely outcome on both versions, and the design should either accept it in words (rare:
a stopped sub-page card in a dead process) or plan the four twins.

For Kid Settings itself, `KidSettingsTheme` has a transparent `windowBackground` and no
`windowSplashScreenBackground`, so a splash drawn when there is no wallpaper target (§2.3's own rule: that branch adds
`FLAG_SHOW_WALLPAPER` to a starting window) gets an undefined ground. Give the theme an explicit
`windowSplashScreenBackground` (the night ground), and the icon if wanted. Also say in §2.3 that a warm start
(activity destroyed, process alive) takes the same path as a process death.

### L3. The Bluetooth page keeps the pause's "any device" rows across a card's return (§2.2)
`dropUnoffered` runs only from the applied listener, and that listener is removed in `onStop`. A Bluetooth page left in
the card during a restrictions pause comes back after the pause with non-audio devices still listed with "Pair". The tap
re-checks and drops them, so nothing can be paired, but the kid sees rows that do nothing. Before, Home's clear
destroyed the page. Fix: call `dropUnoffered()` in `onStart` after `finishIfNotAllowed()` (and on `loadBonded`'s
return).

### L4. The Wi-Fi sign-in window can be the card's top under a rule (§2.2 "Parent code inside the task")
`CaptivePortalEntry.start` from the Wi-Fi page has no `NEW_TASK`, so the entry and then `PortalWindowActivity` (in
`:portal`, no rule check) join the caller's task. Under a rule, Recents' "Innstillinger" card then resumes the WebView
directly. The Wi-Fi page under it never resumes, so its new redirect doesn't run until the window ends (≤10 min, the
budget, validation). Today Home's clear made the window unreachable once the time-rule screen was up.

Design 102 §3.5 (D4) allows the window under a time rule, and the entry's `Forward` already brings an open window
forward (`REORDER_TO_FRONT`), so this is not a policy breach. The design should say so, citing D4, instead of "have
code QA confirm". If the user wants the old behaviour, start the entry with `NEW_TASK` (the package affinity, so it
lands in Home's task as Home's line does). The cost is that Back from the window then lands on Home.

### L5. `excludeFromRecents` left on the two pickers (§2.1, §2.5)
`KidSoundPickerActivity` and `ShortcutsPickerActivity` keep `android:excludeFromRecents="true"`. That meant something
only in Home's task. Recents keys exclusion on the base intent, but whether a top activity with the flag hides the card
has varied in AOSP and launchers, and the attribute buys nothing now. Drop it on both, so that §2.5's "no
`excludeFromRecents`" covers all five files. Row 3 should also check the card with a picker on top and with the
sign-in entry on top (the entry keeps the flag because the system can make it a root).

### L6. Test plan: avoid repeating the phone book's M2 (§2.5)
"The one builder used by both callers" and "every `startActivity` in the five files names one of ours" are
source-text scans (KidScreensEscapeTest's style is fine for the banned list). The behaviour that matters has no row:
- the Location step (L1);
- the sub-pages' gate order: rule, mask, then radios;
- the intent's filter-equality (M1 of the phone book).

Fix: pure functions with rows, for example `subPageGate(lockReason, section, page) -> Redirect | Finish | Open` (one
function for the four sub-pages, replacing three copies of `redirectIfLocked`/`finishIfNotAllowed`) and the Location
step. Keep the manifest test (affinities, no exclusion, theme).

### L7. Android versions (§3, §4)
Root rule: a version-dependent gate runs on the lowest and the highest version it touches.
- **Row 7 (language) runs only on 36.** The refusal it depends on ("Can't change activity type once set") is AOSP
  internals and is exactly the kind of check that differs by version. Run it on 34 too.
- **Row 4 (Location and the mask) runs only on 36.** DPM's `setLocationEnabled` and the scan's Location dependence are
  the same API on 34, but a single row on 34 is cheap.
- **15 and 17 rows marked "the same" without being read.** Name 15's Activity Security Model and BAL hardening:
  cross-task starts are allowed only for the top task's uid. The sub-pages' `LockActivity.start` into Home's task is the
  same uid, so it holds, but say so. Name the 17 behaviour changes that were read, or mark them unread, rather than
  "the same".

### L8. Notes to correct
- §2.3's "AOSP draws no starting window for a wallpaper theme while the wallpaper is visible" is right for a launch
  from Home. Say that it doesn't cover Recents with no wallpaper target, or the cold/warm starts (L2).
- §2.3's "a look change while a sub-page is stopped in Kid Settings' card" happens only through a change that waited
  for Home's resume (`onHomeResumed` → `finishLivePages`). From Kid Settings in front the change runs at once with no
  sub-page alive. Say which.
- `ui-polish.md`'s R9 paragraph gives the reason "home-type, so Android draws no starting window". After the move the
  real reason is "an in-task start in a running process gets none". Reword it rather than only renaming the task.
- b3094f31b's "in Recents until Back" (Dagen) contradicts §2.1's "the card stays", and §2.1 is the right one. Fix the
  Dagen line in `calls.md`/the day notes if it is repeated there.

## The two user questions

- **Q1 (the swipe's animation)** is right to ask, because it is a visible change (memory: visible-cost choices go to
  the user first). But it rests on a premise M1 puts in doubt. Replace it with M1's (a)/(b) choice: whether Kid
  Settings opens with today's wallpaper motion or with Meldinger's task animation and a short starting window. The
  swipe-only custom animation is a follow-up that makes sense only under (b).
- **Q2 (the drawer and the parent's Settings stay in Home)** is a design call; don't ask it. The parent's Settings must
  stay: its PIN gate ends in `onStop`, its Recents screenshots are off, and it is `excludeFromRecents`, so a Recents card
  would be a security regression, not a style choice. The drawer is part of every launcher's Home. State both in the
  note as decisions, with one line each.
