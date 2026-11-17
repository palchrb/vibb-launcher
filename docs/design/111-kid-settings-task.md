# Design note: every built-in "app" behaves as a native app (Innstillinger as a task of its own)

**Revision 2 (2026-11-17)** takes all of design QA (`docs/review/111-kid-settings-task-design-qa.md`: 0 H, 1 M, 8 L):
- M1: the open/close animation is now the user's choice between (a) and (b) (§2.3, Q1).
- L1-L8: folded in where they belong.
- Q2 is gone: it is a design decision now (§1).

The user, 2026-11-17: "barnets innstillinger 'app' ikke oppfører seg som en app; den er ikke på recent apps, den oppfører
seg ikke som feks kalender og meldinger. fikser du det? Alle 'apper' vi legger inn fra vibb one skal oppføre seg som
native apper."

Precedents:
- Dagen (b3094f31b).
- Meldinger (`ui/messages/*`, `.messages`).
- The phone book (aa6369bce, with its code QA `docs/review/qa-phonebook-task-code.md`, `docs/notes/launcher/calls.md`
  "The phone book as a task of its own", and `docs/testing/emulator.md` §6av).

This note follows them. Where it departs from them, it says why.

## 1. Survey: what the kid opens as an app

Sources read:
- the manifest;
- Home's tiles and swipes (`HomeGridAdapter`, `HomeSwipe`);
- the drawer;
- the shade's Snarveier (`shortcuts/`);
- every `PendingIntent.getActivity`;
- the lock glance.

The only callers of `KidSettingsActivity` are `HomeGridAdapter:120` and `HomeActivity:398`.

| Page | Opened from | Today | Verdict |
|---|---|---|---|
| Telefonbok (`PhoneBookActivity` + Light; the entry `PhoneBookEntryActivity`) | Tile, glance, the time-rule screen's row, missed-call notification, `tel:` | Own `.phonebook` task | **App, done** (2026-11-16) |
| Meldinger (list, thread, `PictureActivity`; `MessagesEntryActivity`) | Tile, notification, the time-rule screen's row | Own `.messages` task | **App, done** |
| Kalender/Dagen (day, event, edit + Light) | Tile, glance, reminder, the time-rule screen's row | Own `.day` task | **App, done** (2026-10-27) |
| **Innstillinger** (`ui/kidsettings/KidSettingsActivity`) | Home's last tile, swipe left on Home | Home's task, `excludeFromRecents`; Home's `clearTaskOnLaunch` clears it | **App: this note** |
| Wi-Fi networks, Bluetooth devices (`ui/quickcontrols/*`), the ringtone/message-sound picker (`KidSoundPickerActivity`), the Snarveier picker (`ShortcutsPickerActivity`) | Only Kid Settings' rows | Home's task, one class each (R9) | **Sub-pages of Innstillinger**: they move into its task, as an event page lives in Dagen's |
| The kid's picture (`calls/KidPhotoActivity`) | The contact card | Joins the caller's task | Sub-page, leave |
| The app list (drawer, `AppListActivity`) | Swipe up | Home's task | **Decided: part of Home**, as every launcher's drawer is |
| Parent Settings (`SettingsActivity`, `LegalInfoActivity`) | The drawer's code-gated button, the setup notification | Home's task, parent PIN, `excludeFromRecents` | **Decided: stays out of Recents.** Its gate ends in `onStop` and its screenshots are off, so a Recents card would weaken its security, not just change the look |
| The time-rule screen, `RulePauseActivity`, PIN lock, boot cover, VoIP wake, `InCallActivity` | Rules, system events | Home's task or own excluded tasks | System-ish, leave |
| Trampolines: the two entries, `ComposeActivity`, `UpLinkActivity`, `SettingsSinkActivity`, provisioning/compliance, `PlayLinkBlockedActivity`, `ShortcutAirplaneActivity` (`taskAffinity=""`) | Outside intents, the shade | `noHistory`, excluded | Leave |
| Wi-Fi sign-in (`CaptivePortalEntry`, `PortalWindowActivity`) | The system's notification, the Wi-Fi page's row | Joins the caller's task | System-ish, leave (§2.2) |
| Vibb music | Tile, Snarveier | Its own package | Native already |

**Only Innstillinger is left, together with its four sub-pages.** No `PendingIntent`, notification, Snarveier cell or
trampoline names any of those five, and each sub-page is started only from Kid Settings.

## 2. The change

### 2.1 Manifest and launch path

- **Kid Settings in the manifest.** `KidSettingsActivity` gets:
  - `android:taskAffinity="${applicationId}.kidsettings"`;
  - `launchMode="singleTop"`;
  - no `excludeFromRecents`;
  - `exported="false"` and `configChanges` kept;
  - `android:theme="@style/KidSettingsTheme"` (§2.3).
- **The four sub-pages in the manifest.** Each gets the same `.kidsettings` affinity (Dagen's pattern). Drop
  `excludeFromRecents` from both pickers (L5): it meant something only in Home's task, and whether a flagged top
  activity hides a card differs between versions and launchers.
- **Sub-page starts** stay plain in-task `startActivity` calls, with no flags.
- **One builder.** `KidSettingsActivity.intent(context)` is the component plus `FLAG_ACTIVITY_NEW_TASK`, with no action,
  data, extras or `CLEAR_TOP`. Home's tile and the swipe both use it.
  - An open task comes back as it was, with a sub-page on top if one was open.
  - The base intent never drifts, because nothing forwards into the root, so the phone book's M1 can't stack a
    second Kid Settings.
- **Recents card.** `setTaskDescription` in `onCreate`: the label `kid_settings_title` ("Innstillinger"/"Settings",
  the tile's name) and the icon `R.mipmap.ic_launcher`, as the three precedents have.
- **Back** from Kid Settings' root finishes it and Home shows. That finish is a non-root locked task's, so lock task
  doesn't block it. The card stays: AOSP keeps an emptied task in Recents unless `autoRemoveFromRecents` is set.
  - b3094f31b's commit message says Dagen stays "in Recents until Back". That is inexact, but no note repeats it, so
    nothing needs fixing (L8).
- **The Home key** no longer clears Kid Settings (`clearTaskOnLaunch`). Kid Settings' task goes to the back, and the
  animation is §2.3's choice.
- **`HomeFront.bring`** (a pause ending, the lock leaving, a role change, an update) used to clear Kid Settings through
  singleTask. Now Home comes forward and the card stays.
- **No outside app joins the task.** Nothing in the five files starts another package (`KidScreensEscapeTest`), so the
  phone book's H1 can't happen here. The only other activity that can join is our own Wi-Fi sign-in (§2.2).

### 2.2 Gating, rules, a stale task

**One gate for the four sub-pages (L1, L6).** Add the pure `subPageGate(lockReason, section, page) -> Redirect |
Finish | Open`, with rows in `KidSettingsModelTest`.
- It replaces the pickers' two copies of `redirectIfLocked`/`finishIfNotAllowed`, and the checks the Wi-Fi and
  Bluetooth pages lack today:
  - Neither page checks the rule. In Home's task that was harmless: the time-rule screen and Home's clear covered them.
  - The Wi-Fi page doesn't check the mask either. `wifiPageAllowed` goes next to `bluetoothPageAllowed`.
- When each page runs it:
  - **first thing in `onStart`, before any radio work** (Location, Wi-Fi scan, Bluetooth discovery), so a card brought
    back under a rule or after the mask changed never switches a radio on just before it leaves;
  - again in `onResume`;
  - on a `lockReason` change;
  - on `AppEnforcer`'s applied listener.
- What each outcome does:
  - `Redirect` runs `LockActivity.start`, then `finish()`.
  - `Finish` runs `finish()`.

**Kid Settings itself** has no parent gate. Its doc: "Nothing here is PIN-gated because nothing here changes a rule".
- Its model is the mask, the wallpapers and the "shares location" line. It is read again on every resume and every
  policy store.
- It already redirects to the time-rule screen under any rule (`onResume`, and the `lockReason` listener).
- Pause and Foreldrekode: `lockReason` is NONE then, as today. Screen time is unchanged: Kid Settings is not in
  `FREE_SCREENS`.

**The Wi-Fi page's Location (L1).** Today `WifiNetworksActivity` turns system Location on in `onCreate` and off only in
`onDestroy`. Home's clear used to destroy the page; in its own task it would sit stopped with Location on.
- The fix is the pure `wifiLocationStep(event, enabledNow, byUs, polling)`, with rows:
  - `onStart`, after the gate: Location on (if it was off, marked as ours), then the scan.
  - `onStop`: drop the connect poll first (`handler.removeCallbacksAndMessages`, `connectingSsid = null`, no toast).
    Otherwise a connect that worked ends as "couldn't connect" once Location is off, for example connect, then "Logg
    på" at once on a hotel's captive network. Then Location off if it is ours.
- The Bluetooth page stops discovery in `onStop`, not `onDestroy`.
- Residue as today: a process death with the Wi-Fi page in front leaves Location on.

**The Bluetooth page's paused rows (L3).** `dropUnoffered()` runs only from the applied listener, and that listener is
removed in `onStop`. So a page left in the card during a restrictions pause comes back afterwards still listing
non-audio devices with "Pair". The tap re-checks (`onPairTapped` → `pairOffered`), so nothing can be paired, but the
rows are dead. Call `dropUnoffered()` in `onStart`, after the gate, and when `loadBonded` returns.

**The Wi-Fi sign-in inside the card (L4).** `CaptivePortalEntry.start` from the Wi-Fi page has no `NEW_TASK`, so the
entry and the window join Kid Settings' task. Under a rule, Recents' "Innstillinger" card can then resume the window
(`:portal`, no rule check) directly.
- This is allowed: design 102 §3.5 (D4) permits the window under a time rule, and the entry's `Forward` already brings
  an open window forward.
- The parent-code keypad in the entry finishes in `onStop`, so a card can never resume a gate that was already passed.
- The window ends itself (validated, 10 minutes, budget).
- **Keep it as is.** Had the window to be kept out of the card, the entry would be started with `NEW_TASK` and would
  land in Home's task, at the cost of Back from the window landing on Home.

### 2.3 Look, animation and starting window

**Light twin.** Kid Settings stays night in both looks: design 78 lets only pages follow the look, and it is a
`UIObjectActivity`. So it needs **no Light twin and no `PageKind` row**. It must **not** go into `closesTask`, because
the look is chosen in it.

**Background.** Kid Settings no longer needs Android's wallpaper behind it. Since qa-launcher-small-a-code Q-1 it draws
its own full copy (`WallpaperGround.apply(ownGround = true)` → `GroundKind.FULL`).

**M1: the user's choice (Q1).** When the opening and the closing window both carry `FLAG_SHOW_WALLPAPER`, as Home's
does, WM Shell picks the **wallpaper-intra** transition instead of the task animation. `lock-and-glance.md`:87 measured
this once already. Meldinger, Dagen and the phone book are opaque, so theirs is the task (wallpaper open/close) kind.
The build takes whichever path the user picks.

- **(a) Keep the wallpaper window.**
  - `KidSettingsTheme` = `launcherBaseTheme` + `backgroundWallpaper`'s items (`windowShowWallpaper`, transparent
    `windowBackground`, transparent bars) + `windowSplashScreenBackground` = `@color/kid_ground` (L2).
  - `showsSystemWallpaper()` stays true.
  - From Home: no starting window (AOSP skips one for a wallpaper theme while a wallpaper target is visible), and
    roughly today's wallpaper-intra motion.
  - It does **not** look like Meldinger. The card and Back still work as an app's.
  - `HomeGroundTest` is unchanged.
- **(b) Make Kid Settings opaque, like Meldinger** (recommended by the coordinator).
  - `KidSettingsTheme` = `launcherBaseTheme` + `KidPageNightTheme`'s five window literals: `windowBackground` and
    `windowSplashScreenBackground` = `@color/kid_ground`, `windowLightStatusBar`/`windowLightNavigationBar` false,
    `enforceNavigationBarContrast` true. Plus transparent bar colours, so the content's wallpaper copy runs under the
    bars as it does now.
  - `showsSystemWallpaper()` → false (`UIObject` then applies `Background.SOLID`). The ground stays
    `WallpaperGround.apply(ownGround = true)`, unchanged.
  - `HomeGroundTest`'s list of "screens that show Android's wallpaper" becomes `HomeActivity` alone. Its Q-1 pin on
    Kid Settings' `ownGround = true` stays.
  - Result: Meldinger's open and close task animation from the tile and the swipe, and Home slides it away. Android
    draws its starting window: a frame or two of night ground before the kid's photo or colour.
  - A custom slide for the swipe alone could be a follow-up, and only under (b).

**Starting windows on a Recents restore (L2, L8).** Shell's `PhoneStartingWindowTypeAlgorithm` returns a **splash**
for `!processRunning`, or for a task switch with `!activityCreated`, before it considers a snapshot. So:
- A card brought back after a process death, or a warm start (activity destroyed, process alive), gets the top
  activity's splash: `windowSplashScreenBackground` with the Vibb icon. That holds for both (a) and (b). (a)'s
  "no starting window" covers only a launch from Home with the wallpaper visible.
- For Kid Settings itself the splash is `kid_ground`, correct under (b) and defined under (a).
- For a sub-page on top it is `KidPageTheme`'s `kid_page_ground` in the **phone's** night mode. That is a wrong-look
  flash when the phone's theme and the kid's look differ, which is what design 78 R3's twins exist to avoid.
  **Accepted in words:** it takes a stopped sub-page left in the card, a dead process or destroyed activity, and a phone
  theme opposite to the kid's look. It is one splash before the right page. Twins for the four (`PageKind` rows, four
  empty classes) are planned only if the user objects after row 6.

**The sub-pages' one class (L8).** They stay one class (`KidPageTheme`, the applied look). `PageManifestTest`'s
`oneClass` and `ui-polish.md`'s R9 paragraph get a new reason: not "home-type, so Android draws no starting window",
but "an in-task start in a running process gets none; a Recents restore gets the splash above".

**A look change while a sub-page is stopped in the card (L8).** This happens only through a change that waited for
Home's resume (`onHomeResumed` → `finishLivePages`, which finishes it; the card then comes back on Kid Settings). A
change made from Kid Settings in front runs at once, with no sub-page alive (`pages.started` is 0).

### 2.4 Lock task, locale, process death, Flymodus

- **Kiosk.**
  - Our package is lock-task permitted, so `.kidsettings` joins lock task. Lock task is engaged only by Home's
    `onResume` (and the PIN lock's fallback), so Home stays `mLockTaskModeTasks[0]` (`stateNotNeeded`, e490362f0).
  - The crash-loop residue that `kiosk-escapes-and-updates.md` names covers this task as it covers every page task; its
    list of page tasks gains `.kidsettings`.
  - When the kiosk goes off, `clearLockedTask` empties the card, as it does Dagen's. Harmless.
- **Language.** `languageClosesTask` gains `KidSettingsActivity` (rows in `LauncherLocaleTest`), so the label is always
  the current language's.
- **Process death.** Kid Settings rebuilds everything in `onResume`. The hidden-apps list's open state comes back
  closed (accepted).
- **Flymodus.** `startVoiceActivity` runs in a task of its own. When it ends, focus goes to the next locked task, now
  Kid Settings' own. The outcome is the same as today.

### 2.5 Tests and notes

Tests follow the phone book's M2 lesson: rows, not source lines (L6).
- `KidSettingsModelTest`:
  - `subPageGate` rows (rule, mask, each page);
  - `wifiPageAllowed` rows;
  - `wifiLocationStep` rows.
- `LauncherLocaleTest` and `PageLookTest`: Kid Settings in `languageClosesTask` and not in `closesTask`.
- `KidSettingsTaskTest`, the manifest only:
  - the five affinities, and no other activity on `.kidsettings`;
  - no `excludeFromRecents` on any of the five;
  - Kid Settings' theme and its window items under the chosen path.
- Builder filter-equality: the builder is a pure function of the context, so a test asserts it carries no
  action/data/extras/`CLEAR_TOP`.
- The banned-starts list stays `KidScreensEscapeTest`'s, in its style.

Notes:
- `kid-settings.md`: a new section, "Innstillinger as a task of its own".
- `ui-polish.md`: R9 reworded (above).
- `kiosk-escapes-and-updates.md`: the list of page tasks.
- `launcher/CLAUDE.md`: the `LockActivity` bullet (Kid Settings checks the rule itself).
- `emulator.md`: a new §6az.

## 3. Android 14-17 (L7)

| | 14 | 15 | 16 (Jelly Star) | 17 |
|---|---|---|---|---|
| Recents card (`TaskDescription.Builder`, API 33) | yes | yes | yes | yes |
| Predictive back at the root (`enableOnBackInvokedCallback="true"`; Kid Settings registers no callback) | Classic close; back-to-home only with the developer option | Back-to-home preview by default | The same; target 36 never calls `onBackPressed`, and we use the dispatcher | Not read |
| Cross-task starts: a sub-page's `LockActivity.start` into Home's task | Allowed | Activity Security Model / BAL hardening: allowed only for the top task's uid. Same uid (ours), so it holds | Holds | Not read |
| Wallpaper-intra vs task transition (M1), starting-window types (L2) | Shell's algorithm; seen in rows 2 and 6 | Between the two gates | Rows 2 and 6 | Not read |
| Home's record kept at process death (`stateNotNeeded`) | Row 6 | | §6ai, row 6 | Not read |
| The language switch's refusal ("Can't change activity type once set") | Row 7 | | Row 7 | Not read |

The gate runs on 14 and 16: the lowest version and the Jelly Star's. 15 is covered by reading (above). **17's
behaviour changes have not been read for this note.** The build agent reads its task, back and lock-task sections and
names them in the notes. Rows 2, 3 and 6 on the 17 image are optional.

## 4. Emulator gate (§6az)

Setup as §6av: `jelly36` restored clean; the kiosk LOCKED and enrolled; rules `Lekser` (calls off) and `Kveld`; a
rule turned on with `rules-now`. `compat34` started with `-wipe-data -dns-server 8.8.8.8`.

| # | Check | How | API |
|---|---|---|---|
| 1 | Own task | Tile and swipe left; `dumpsys activity activities` shows `type=standard A=...kidsettings`; `dumpsys activity lock-task` shows Home first | 36, 34 |
| 2 | Open and close | Tile and the Home key, recorded (`screenrecord`, frames) and compared with Meldinger's recorded the same way. Record **the transition type seen** (wallpaper-intra or task) and the first frame. Under (b): Meldinger's animation and a frame or two of night ground. Under (a): no starting window | 36, 34 |
| 3 | Recents and Back | The card "Innstillinger" with the Vibb icon and the wallpaper in the thumbnail; a tap brings back the same instance. Wi-Fi page, Home, tile: the Wi-Fi page comes back. The card with a picker on top, and with the sign-in entry on top (L5). Back twice ends on Home and the card stays | 36, 34 |
| 4 | Location and the mask | Wi-Fi page, then Home: `settings get secure location_mode` is off; back on the page: on. A connect followed by an immediate Home gives no false "couldn't connect". The PWA takes the Wi-Fi bit away, then the card: Kid Settings without the row, the page gone, Location never turned on | 36, 34 |
| 5 | A rule, and Flymodus | Wi-Fi page, Home, `Lekser` on, then the card: the time-rule screen, the page finished, no scan started (logcat). The rule ended, then the card: Kid Settings. Flymodus on and off from Kid Settings: back on Kid Settings | 36, 34 (rule only) |
| 6 | Process death | Lys with the phone in dark theme; Bluetooth page, Home, `kill -9`, then the card. Expected: a splash in the phone's mode with the Vibb icon (L2). Record it; then the page is restored. Back twice ends on Home; the lock-task list shows Home first | 36, 34 |
| 7 | Language and look | The parent switches nb to en with the card in Recents, then Home: the switch goes through (the task closed). In Kid Settings, Lys: Meldinger's, Dagen's and the phone book's tasks close, Kid Settings' stays | 36, 34 |

## 5. Size and question

**Size: small.** About 200-300 lines with tests and notes:
- five manifest entries and a theme;
- a builder and its two callers, and `setTaskDescription`;
- `subPageGate` and `wifiLocationStep` with their rows;
- the Wi-Fi and Bluetooth lifecycle changes;
- one `languageClosesTask` line;
- under (b), `showsSystemWallpaper()` and `HomeGroundTest`.

The flow is one build, then code QA, then the 7-row gate (all seven on 36, six full and one in part on 34). No server
change, no contract file.

**Q1 - ANSWERED 2026-11-17: (b), and no wallpaper at all.** The user: "b er best. jeg trenger ikke bildebakgrunn her, bedre med vanlig fast bakgrunn". Innstillinger is opaque (path (b): Meldinger's task animation) and draws a plain solid ground, the night page's own colour, instead of its copy of the kid's wallpaper: `ownGround` off for Kid Settings and its four sub-pages, the window background and the splash ground the same solid colour, so the starting window already matches and the "frame or two of night ground" of (b) is no longer a visible cost. Path (a) and the wallpaper copy are dropped.

**Q1 - how Innstillinger opens and closes (M1).** (the question as asked)
- (a) Keep the wallpaper window: no starting window and roughly today's motion, but not Meldinger's animation.
- (b) Make it opaque: Meldinger's open and close animation, and Home slides it away, but a frame or two of night ground
  shows before the kid's wallpaper.

*Recommendation: (b)*, because the user asked for it to behave like Meldinger. Both paths are written out in §2.3.
