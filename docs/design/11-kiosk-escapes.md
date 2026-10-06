# Step 11: kiosk escapes - our own update, other apps' notifications

L paths under `launcher/app/src/main/java/com/kidslauncher/mdm/`, S under `server/src/`. Builds on 09 (kiosk app block) and 10 (PIN lock).
AOSP `main` (android.googlesource.com, read 2026-10-05/06), classes abbreviated by initials: PF PackageFreezer, AMS, AE AppErrors, CE
ComputerEngine, PMS, SPH SuspendPackageHelper, LTC LockTaskController, ATS ActivityTaskSupervisor, AS ActivityStarter, ASI
ActivityStartInterceptor, RWC RootWindowContainer, BAL BackgroundActivityStartController, DPMS, NMS/NSC NotificationManagerService/
NotificationShellCmd, NLS NotificationListenerService, PI PackageInstaller, FH Settings FallbackHome. **Evidence** (Pixel 7 / Android 16
Play emulator, kiosk LOCKED, `adb install -r` of L): 08:31:11 `START cat=[HOME] cmp=nexuslauncher/.NexusLauncherActivity`, 08:31:25
`nexuslauncher/com.android.quickstep.RecentsActivity`, install done 08:32; "set a screen lock" nags, one tap opened "something new",
a `BlockedAppActivity` task is in history.

## 1. Platform facts
| Fact | Source |
|---|---|
| Every install freezes the package and kills its processes (`setDontKillApp` exists only for split adds in `MODE_INHERIT_EXISTING`) | PF:86-91, PI:3300-3305 [verified: AOSP] |
| The kill is a force-stop: our activities finish, the lock-task root task goes, removing the root clears all locked tasks, lock task stops | AMS:1823-1833, 4337; LTC:552-586 [verified: AOSP] |
| Home is then resolved anew: the DO persistent-preferred HOME is skipped but **not removed** while our component is unknown (the role's ordinary preferred HOME is deleted as "dangling"); a frozen package can't start; no HOME at all = `wtf` "No home screen found" | CE:3517-3570, 3376-3396, 3640; PMS:4628; RWC:1489-1513 [verified: AOSP]; frozen-vs-unknown timing [needs device test] |
| Nothing re-enters lock task by itself: auto-start exists only for `lockTaskMode="if_whitelisted"` roots or while already LOCKED; HomeActivity has none (`AndroidManifest.xml:104-118`), we call `startLockTask` in `HomeActivity.onResume` (`ui/HomeActivity.kt:300,360-377`) and `PinLockActivity` (`lock/PinLockActivity.kt:156-170`) | ATS:891-896, LTC:794-801 [verified: AOSP] |
| Survives the kill (DPMS/PMS state): lock-task packages+features, DO suspensions, hidden apps, user restrictions, `setStatusBarDisabled`, persistent HOME; DPMS ignores a replacing PACKAGE_REMOVED | DPMS:1257-1263 [verified: AOSP]; lock-task list after update [needs device test] |
| `setStatusBarDisabled` = shade, notification icons/heads-up, search, quick settings - **not** Home/Recents | DPMS:693-700 [verified: AOSP] |
| `MY_PACKAGE_REPLACED` cold-starts us: `Application` runs `PinLockRuntime.init` (ProcessStart: lock shown if LOCKED and screen on) and the anchor service; a DO may start activities from the background | L `Application.kt:278-282`, `lock/PinLockRuntime.kt:91-131`; BAL:1075-1078 [verified: AOSP] |
| A DO can suspend anything but the ROLE_HOME holder, installer/uninstaller/verifier, default dialer, permission controller, protected pkgs; hiding refuses admins, `android`, protected | SPH:504-570, PMS:5941-5992 [verified: AOSP] |
| A suspended package still resolves; its start becomes the admin-support dialog (Settings), checked **before** lock task | ASI:215-223, 307-357 [verified: AOSP] |
| `FallbackHome` (Settings, priority -1000) polls every 500 ms, finishes once another Home resolves - gone when Settings is hidden (not allowlisted, `server/EnforcementPlan.kt:157`) | FH:168-181 [verified: AOSP] |
| A crash-looping third-party Home loses only its *ordinary* preferred entry; our DO persistent HOME stays, so the stock launcher is never the fallback | AE:961-967, CE:3517-3570 [verified: AOSP] |
| In lock task: new task of a non-pinned pkg refused (toast); with the BLOCK bit any start of a non-pinned pkg (also a notification tap) → `BlockedAppActivity`; pinned helpers start freely; recents allowed with OVERVIEW | AS:2237-2245, ASI:364-378, LTC:380-431 [verified: AOSP] |
| Server kiosk features always include NOTIFICATIONS and OVERVIEW; PIN lock LOCKED strips NOTIFICATIONS/HOME/OVERVIEW | S `handlers/devices.rs:203-208`; L `server/LockTaskHelpers.kt:95-101` |
| Listener `cancelNotification`: refuses only `FLAG_ONGOING_EVENT` (+ lifetime-extended), fires the app's `deleteIntent`, notes app-op RAPID_CLEAR right after a post; needs our bound listener (access via adb only, `launcher/CLAUDE.md:70`) | NMS:5297-5365, 5475-5486; NLS:723-741 [verified: AOSP] |
| Suspended pkg → its notifications hidden; hidden pkg (PACKAGE_REMOVED) → cancelled | NMS:2043-2047, 2105-2111 [verified: AOSP] |
| `InstallConstraints` (app not foreground/interacting, device idle) can't help: we are HOME with an FGS | PI:5064-5111 [verified: AOSP]; [needs device test] |

## 2. Escape A - our package being replaced
Timeline: `session.commit()` (`server/AppInstaller.kt:89`) → freeze + kill → lock task ends, Home resolves to the stock launcher (app
drawer with every non-hidden app, its long-press menus, wallpaper picker, quickstep Recents - the 08:31:25 line) → install + dexopt (~50 s
on the emulator) → `MY_PACKAGE_REPLACED` → our process: PIN lock if LOCKED and screen on, otherwise **nothing brings Home back** until the
kid presses Home (`server/PackageReplacedReceiver.kt:34-40` only starts services). Shade is open unless the PIN lock was LOCKED (its
`setStatusBarDisabled(true)` persists). Same for `adb install -r` and, briefly, for a crash of our process.

| Option | Closes | Cost / risk |
|---|---|---|
| a. Commit the self-update only with the screen off ≥ 30 s, no call, no emergency flow (screen off already = LOCKED when the PIN lock is active) | most of the window: the kid must wake the phone within ~1 min | update waits for the next screen-off |
| b. **Update fence**: just before commit `setStatusBarDisabled(true)` + suspend every other HOME-capable package (MAIN+HOME, minus ours and Settings) | shade, Home, drawer, Recents during the window | quickstep (gesture nav, Recents) lives in the stock launcher pkg on Pixel (`nexuslauncher`) and stock Unihertz (`com.android.launcher3`?): suspension keeps its service but intercepts its RecentsActivity [needs device test]; refused if ROLE_HOME isn't ours |
| c. Suspend other HOMEs permanently while managed | also adb installs, crashes | the quickstep risk always, also kiosk off - rejected until A3 shows it harmless |
| d. Hide other HOMEs | as c; HOME → nothing | PACKAGE_REMOVED kills quickstep's binding; hiding non-launchable system pkgs is the dev-device boot-loop class (`server/AppEnforcer.kt:59-104`) - rejected |
| e. Bring Home to front on `MY_PACKAGE_REPLACED` | the tail after install | none (DO is BAL-exempt) |
| f. `lockTaskMode="if_whitelisted"` on HomeActivity | auto lock task at every Home launch | with kiosk off the PIN lock pins ours (`LockTaskHelpers.kt:126-141`): Home would root a lock task that `PinLockActivity.stopLockTask` (`PinLockActivity.kt:396-404`) doesn't end - deferred |

**Recommendation: a + b + e.**
1. New `server/UpdateFence.kt`: pure `fencePlan(homeCandidates, own, settingsPkg, alreadySuspended)` (tested) + glue. Suspends only
   packages not already suspended, persists them + start time + tag in CE prefs (`commit()`), sets the status bar off, reports
   `update_fence: {state, unsuspendable}` in status (S stores known fields, capped, like `lock_state`). Never touches lock-task packages
   (removing ours clears the task, LTC:776-788); nothing needs re-setting after the update (DPMS kept packages and features).
2. `server/MdmSyncWorker.kt:392-470`: the launcher APK is downloaded as today, kept as `pendingSelfUpdate` (attempt marker cleared so it
   isn't re-downloaded) and committed when (a) holds - at sync time, or 30 s after SCREEN_OFF via the process-wide receiver
   (`lock/PinLockRuntime.kt:99-108`) and `SyncRunner`, cancelled by SCREEN_ON. Fence right before `session.commit()`; a throw releases it.
3. Release: `PackageReplacedReceiver` starts `HomeActivity` (as `AppEnforcer.kt:544-555`) when the kiosk is on and no call - Home
   `startLockTask`s, ProcessStart puts the PIN lock on top; `UpdateFence.release` once Home or the lock resumed in lock task (backstop
   2 min), then `LockTaskChrome.refresh`. Install failure (`server/AppInstallReceiver.kt:41-73`) releases at once; a process start finding
   a fence > 10 min old releases it. Only fence-suspended packages are unsuspended (not in `controllablePackages`; `apply()` never would).
4. `adb install -r` stays unfenced (dev only; say so in `docs/testing/emulator.md`). Risks: partial fence (ROLE_HOME not ours) = today,
   reported; a kid waking the phone mid-window sees the app it was in (task survives, unlocked) but Home/Recents/shade are fenced and
   the lock returns at process start; quickstep oddities only while fenced (screen off).

**Never brick.** If the new build can't start, nothing changes versus today: the persistent HOME already pins our crashing Home (AE
leaves it), so the stock launcher never was a recovery path, and the fence can't outlive a running new process. The parent keeps every
DPMS policy, incoming calls via Telecom's fallback to the system in-call UI [needs device test], 112 from the power menu / emergency
dialer, recovery-mode wipe. Remote fix-forward needs a sync, but tsnet starts only from Home's first `onResume` (`Application.kt:294-301`),
so a Home crash likely cuts server reach [needs device test]. Follow-ups: A5 as a release gate; a server canary (one device first, the
rest once it reports healthy); start tsnet from the anchor service if Home hasn't resumed 2 min after `MY_PACKAGE_REPLACED`.

## 3. Escape B - other apps' notifications
| State (default features) | Shade | Tap → non-pinned package | Tap → pinned helper |
|---|---|---|---|
| Kiosk, unlocked, BLOCK on | yes | `BlockedAppActivity` (the history entry) | opens: PermissionController (Safety Center, permission manager), phone, Telecom, system dialer, DocumentsUI, picker, cell broadcast (`LockTaskHelpers.kt:17-37`) |
| Kiosk, BLOCK off | yes | new task refused + toast | opens, and its in-task starts into others (Safety Center → Settings if visible) open too |
| PIN lock LOCKED | no | - | - |
| Kiosk off, managed | yes | opens whatever isn't suspended/hidden: GMS, SetupWizard, Wellbeing, wallpaper picker | opens |
| Update window | yes unless LOCKED / fenced | opens whatever isn't suspended/hidden | opens |

"Something new" was either `BlockedAppActivity` itself or a Safety Center nag (PermissionController, pinned) opening Safety Center in
kiosk, whose "Screen lock" entry then hit `BlockedAppActivity` [needs device test: B1/B3]. Likely sources [needs device test]:
`com.google.android.gms` (screen lock, theft protection, Find My Device, Play Protect; never restricted `play/PlayPolicy.kt:33`, never
pinned); `com.google.android.permissioncontroller` (AOSP `com.android.permissioncontroller`: Safety Center issues, unused-app permission
removal; pinned, unsuspendable); `com.google.android.setupwizard`, `...apps.restore` ("finish setting up" incl. screen lock),
`...apps.wellbeing`, `...settings.intelligence` (no launcher icon → never touched); `com.android.vending` (suspended → hidden, unless
unsuspendable as verifier); Settings, Tips, Google app (launcher icon → hidden unless allowlisted); `android`, SystemUI (keep).

**Mitigation (recommended): generic rule in our listener.** `badges/BadgeListenerService.kt:17-23` calls a pure, tested
`nagToCancel(info, policy)` on post and on connect, only while managed: cancel a **clearable** notification unless its package is ours,
allowlisted, essential (system dialer, Telecom, `com.android.phone`, cell broadcast, clock app, SystemUI, `android`, IMEs) or its category
is CALL / MISSED_CALL / ALARM / STOPWATCH. All nags above fall out of "not allowlisted, not essential" with no per-OEM list (the list is
B5's oracle). Status gets per-package counts (package + channel, no text). Limits: ongoing ones stay (lock task still blocks their taps);
nothing while unbound (no access - S warns; our process dead mid-update - the fence's status-bar block covers it); a heads-up may flash
first; the app sees a dismissal and may re-post (cancel again, log rate-limited); RAPID_CLEAR is only noted. Not now: suspending
non-launchable "suggestion" packages (outside `controllablePackages`' safety rule) unless their nags prove non-clearable. Residual:
Safety Center via the pinned PermissionController (needed for permission dialogs) stays reachable if opened otherwise.

## 4. Device test checklist (emulator over adb; kiosk LOCKED, BLOCK on unless noted)
- [ ] A1 Baseline (H = `-a android.intent.action.MAIN -c android.intent.category.HOME`): `adb shell cmd package query-activities --brief H`;
  `cmd package resolve-activity --brief H` (= ours); `cmd role get-role-holders android.app.role.HOME`; `dumpsys activity activities | grep
  -E "mLockTaskModeState|mLockTaskPackages"`.
- [ ] A2 Repro: `adb logcat -v time | grep -E "START u0|LockTask|Force stopping|kidslauncher"`; poll `adb shell "dumpsys activity activities
  | grep -m1 mLockTaskModeState; dumpsys package frozen"` each 1 s; `adb install -r app-debug.apk`; meanwhile `input keyevent KEYCODE_HOME`,
  `KEYCODE_APP_SWITCH`, `cmd statusbar expand-notifications`, `adb exec-out screencap -p > w.png`. Record kill → stock launcher → install
  end → our Home/lock times and what is reachable. Repeat with the PIN lock LOCKED: `KEYCODE_SLEEP`, install, `KEYCODE_WAKEUP`.
- [ ] A3 Fence pre-test (no code): `adb shell pm suspend com.google.android.apps.nexuslauncher`, then A2 - what does HOME show? In both nav modes (`cmd overlay enable com.android.internal.systemui.navbar.gestural` / `...threebutton`):
  `input swipe 540 2350 540 1200 250`, Recents, Back, kiosk on and off; `pm unsuspend` after. Same on the Jelly Star (its launcher pkg).
- [ ] A4 After implementation: publish a bumped versionCode; screen on → no commit; `KEYCODE_SLEEP` → commit after 30 s (logcat
  `UpdateFence`); `dumpsys package com.google.android.apps.nexuslauncher | grep suspended` true during, false after; Home/lock in lock task
  ≤ 5 s after `MY_PACKAGE_REPLACED`; ROLE_HOME and HOME resolution as in A1; mismatched-signature APK → fence released at once.
- [ ] A5 Crash (snapshot first): build throwing in `HomeActivity.onCreate` over a good one - screen, `adb emu gsm call 4781549300`, power-menu Emergency, `dumpsys activity activities`; recover by `adb install -r` of a good build.
- [ ] B1 Identify nags: `adb shell dumpsys notification --noredact | grep -E "NotificationRecord|android.title=|contentIntent|flags="`;
  `cmd notification list`, `cmd notification get <key>` → package, channel, clearable, PendingIntent creator/type.
- [ ] B2 Non-pinned target: `cmd package resolve-activity --brief -a android.intent.action.SET_WALLPAPER` → C; `cmd notification post -t Nag
  -c activity -n C nag1 test` (explicit `-n`: NSC adds `xyz:` data to data-less intents); shade, tap → `BlockedAppActivity`; BLOCK off →
  toast; kiosk off → opens. `dumpsys activity recents | grep -E "realActivity|BlockedApp"`.
- [ ] B3 Pinned helper: as B2 with `-a android.safetycenter.action.SAFETY_CENTER` → Safety Center opens in kiosk?; its "Screen lock" →
  `BlockedAppActivity`. B4 PIN lock LOCKED: `cmd statusbar expand-notifications` does nothing. B5 after the listener rule: B2's
  notification gone ≤ 1 s, B1's nags gone, an allowed call / missed call / alarm notification stays.
Questions: 1. Self-update only after ≥ 30 s screen-off (or only at night)? 2. Generic cancel rule (recommended) or a fixed list?

## Decisions after QA review (qa-11-design.md), 2026-10-06

All QA H/M findings are binding: the fence is recorded before it acts and released by one
pure, idempotent check run at every process start, boot, install result, apply() and an alarm
(our package replaced / session finished or gone / new boot), versioned so later builds can
always release older fences; the fence is an input to LockTaskChrome (no cached status-bar
state that skips re-enable); release as soon as our Home or lock is in front; apply() must not
unsuspend fenced packages while the fence is active; the downloaded APK is kept (not re-fetched
every sync). Notification auto-cancel: generic rule for non-allowlisted packages with an
essential-exemption resolved at runtime (emergency/cell broadcast incl. GMS earthquake alerts,
calls, alarms, battery/system, our own; never full-screen alerts), re-post budget then snooze,
privacy-safe logging (channel ids, capped counts only). adb installs are unfenced (dev only).
Product defaults (proposed, pending user confirmation): self-update window at night 02–05
with screen off and no call; if an update has waited >24 h, any 30 s screen-off with no call.

## Implementation status (2026-10-06)

Done on `master` (not pushed). L: `./gradlew assembleDebug assembleRelease testDebugUnitTest -PwarningsAsErrors=true`
green (485 unit tests); S: `cargo test` (190), `fmt --check`, `clippy --all-targets` (no new warnings). Both server switches default
**off** (A3 / B5 first). Product defaults implemented as proposed, pending the user's confirmation: window 02:00-05:00
local, screen off >= 30 s, no call; overdue after 24 h (2 min on debug builds, for A4 on the emulator).

| Part | Commit | What |
|---|---|---|
| L | `3bd1fb55` | Pure `server/UpdateFencePlan.kt`: `fencePlan` (exclusions of finding 6, controllable, already suspended), versioned record (`update_fence` prefs, keys pinned, unknown `v` still releases), write-ahead `runFence`, `runRelease`, the release rule `fenceRelease`, `suspendTarget`; `lockTaskWhileLocked(fenced)` + `StatusBarLatch`; `UpdateFenceTest`, `PinLockTaskTest` cases |
| L | `865ab6f8` | Pure `server/SelfUpdatePlan.kt`: pending-APK state machine, `pendingApkCheck` (size, SHA-256, our package, downgrade/same version), `updateWindowDecision` (DST-aware window), `commitCheckDelayMs`; `SelfUpdatePlanTest` |
| L | `5224b48b` | Pure `badges/NotificationRule.kt`: `nagVerdict`, `RepostBudget` (3/min, then snooze 1 h), `NagCounts` (<= 20, ids <= 64), `nagLogDue`; `NotificationRuleTest` |
| L | `7fadac42` | Glue: `UpdateFence` (fence before `commit()` under `AppEnforcer`'s lock, checks at process start/boot, install results, `apply()`, Home/lock in front, screen-off, `MY_PACKAGE_REPLACED`, backstop alarm), `LockTaskChrome` owns the status bar with the fence as input, `SelfUpdate` (pending APK in `noBackupFilesDir`, screen state, window alarm), `MdmSyncWorker` (download-and-keep, commit as the sync's last step), `AppInstaller(beforeCommit/commitFailed)`, `AppInstallReceiver` (one `goAsync`, fence input, Home after a post-kill failure), `PackageReplacedReceiver` (Home, then the lock), `NotificationRuleRuntime` + `BadgeListenerService`, DTOs, capability `kiosk_escapes_v1` |
| S | `95e7b8d8` | Migration `0036`, switches `update_fence`/`notification_auto_cancel` (always sent, card + `POST /devices/{id}/kiosk-escapes`), `update_fence`/`notification_cancels` sanitized and capped (`src/kiosk_escapes.rs`), card texts; `tests/step11.rs` |
| docs | (this commit) | launcher/server `CLAUDE.md`, `docs/testing/emulator.md` §6d (adb installs unfenced, A1-A5, B1-B5), this section |

How the QA findings were met: **#1** write-ahead record (planned set + session id + our `lastUpdateTime` + boot count,
then only what the platform suspended); one pure `fenceRelease` run at process start (= boot), every install result
(any status, any app), `apply()`, Home/lock in front, screen-off, `MY_PACKAGE_REPLACED` and an alarm armed at fencing;
unmanaged / not device owner / switch off release. **#2** own prefs file `update_fence` with `v`, keys and the v1 parse
pinned by `UpdateFenceTest`, unknown `v` releases `planned` + `suspended`; CLAUDE.md rule "release code is never
removed". **#3** the committing process never releases while its session is open (`committingHere`); release only
when replaced, gone, failed, rebooted or expired. **#4** `statusBarDisabled = locked || fenced` in
`lockTaskWhileLocked`, `UpdateFence` never calls `setStatusBarDisabled`, the latch is invalidated on every fence/release.
**#5** `fencePlan` excludes controllable packages, a release leaves now-controllable ones to `apply()`, `apply()` keeps
fence-held packages suspended, fence and release take `AppEnforcer`'s lock. **#6** every exclusion has a test row; the
switch defaults off until A3 on the Jelly Star. **#7** release once Home or the lock is in front (lock task not
required) or the screen is off, at the latest 2 min after the replacement (wall time - also with a crash-looping Home).
**#8** gate: no live call (`OngoingCalls.hasLiveCall` incl. CONNECTING, Telecom or audio mode), no emergency (lock's
emergency flow, callback window, an emergency call < 10 min ago, ECBM); the unscreened-call gap is documented
(CLAUDE.md, emulator A2 variant). **#9** Home first, then `PinLockRuntime.showIfLocked` (posted, so the lock ends on
top), also after a failure in a restarted process. **#10** APK in `noBackupFilesDir`, `TrackedAppState.pending`
(newer tag replaces, withdrawn/installed drops), size + SHA-256 + `getPackageArchiveInfo` package/versionCode before
fencing, commit inside `performMdmSync` (sync mutex). **#11** essentials resolved at runtime (every `SMS_CB_RECEIVED`
receiver plus the AOSP/module/Google cell-broadcast names, dialers, emergency dialer, Telecom, SMS app while SMS is on,
clock app, IMEs, `android`, SystemUI), never a full-screen or insistent notification, alert-like channels kept (GMS
earthquake alerts), allowed = allowlist + messaging apps + a time rule's usable apps; server switch default off until
B5. **#12** 3 cancels per (package, channel) a minute, then `snoozeNotification(1 h)`; nothing while the policy is
unknown; decisions from a cache resolved in `apply()`. **#13** `NotificationFacts` has no text fields (tested),
`Notification.getChannelId()`, no keys/tags in logs, rate-limited lines, status <= 20 entries / 64-char ids, the server
re-serializes; the card's privacy text names the listener ("App badges"). **#14** adb installs unfenced
(emulator.md), a stale fence released by the `lastUpdateTime` rule (tested).

Known limits / not done: a call in the ~1 min install window rings unscreened (system in-call UI); a third-party
launcher the parent allowlisted stays usable during the window (enforcement owns it); nothing is cancelled while the
listener has no access (server warns) and a heads-up may flash before the cancel; RAPID_CLEAR app-ops are only noted;
non-launchable "suggestion" packages are not suspended; the design's follow-ups (A5 as a release gate, a server canary
rollout, tsnet from the anchor if Home hasn't resumed) are not built.

Device checks: A1-A5 and B1-B5 above, with the QA device acceptance items, are written out in
`docs/testing/emulator.md` §6d [needs device test].
