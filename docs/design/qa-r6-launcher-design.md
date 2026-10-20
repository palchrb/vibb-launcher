# Design QA - launcher round 6 (41 flashlight, 42 clock on the lock, r6 small items)

Reviewed on `main` at 711747e0 (2026-10-20): `docs/design/41-flashlight.md`, `docs/design/42-lock-clock.md`,
`docs/design/r6-launcher-small-items.md` (working tree, including the §2 addition "the tile shows the time"), against
PLAN ("Design 41", "Design 42", "Launcher: an inbox event asks for a sync at once", "Play/pause flicker on the lock's
music tile") and the launcher code. Paths below are under `launcher/app/src/` unless they start with `docs/` or `PLAN`.
No Gradle run; reading only.

Note for all three: there is no `GlanceFitTest`. `glanceFit` and the page's XML model are tested in
`test/.../lock/LockGlanceTest.kt` (the pure fit, plus the layout test that pins the XML: `fixed` 106, `tileFull` 211,
`tileCompact` 90 at :376-400, the expected fits at 473/497 dp, widths at 320/360/411 dp, the child order at :168-175).

## 41 - flashlight

**41-1 (High) - the camera-policy lift reopens design 25b's lock bypass, for up to 10 minutes with the screen off.**
`CameraLockPlan.kt:116-129`: SystemUI's `canCameraGestureBeLaunched` refuses the double-press gesture only because the
camera is policy-disabled; "where the policy is off and the gesture's camera is suspended ... that crash remains
possible" (system_server throws on the suspended camera, SystemUI restarts, whatever comes up shows over the lock).
§1.5 lifts the policy for exactly the time the torch is lit, and §1.4 keeps it lit through screen-off - the moment the
gesture is used. A one-shot device check (§4 step 1) can't prove the absence of a crash that depends on timing. Also,
from AOSP's `CameraService` as I know it, the device-policy camera check sits in the connect path
(`validateConnectLocked` -> `isCameraDisabled` -> `ERROR_DISABLED`) and `setTorchMode` has no such check, so the
torch probably works under the policy on AOSP-based builds - "most likely fails" is probably wrong; an OEM build may
differ, which is unknown until the probe. Fix: don't build the lift. Run §4 step 0 first; if the torch lights under
the policy, nothing more is needed; if it fails with `CAMERA_DISABLED`, hide the lock's button (Home's tile still
works) and bring the result back to the user. A lift that ends at screen-off is the only lift variant worth putting
to them, and that means the user would have to give up the "never off at screen-off" rule for a torch lit from the lock.

**41-2 (Medium) - the lift as specified can't work in one step anyway.** `CameraLock.syncCameraPolicy`
(`CameraLock.kt:315-326`) runs on the `camera-lock` executor and caches `cameraPolicy`. `Torch.on()` would have to
queue the lift, wait for the write, then retry `setTorchMode`. The policy is written again from every
`onLockChanged`/`refreshTargets` pass, and §1.5's `cameraPolicyDisabled(locked, call, torchLit)` needs `torchLit`
kept in the same volatile way as `PinLockRuntime.callForCamera` (:815). It is also unknown whether re-setting
`setCameraDisabled(true)` turns off a lit torch. This finding only matters if 41-1 is overruled. Fix: drop it with
the lift, or specify the order (lift -> await -> retry) and test it.

**41-3 (Medium) - "a call's begin" names the wrong hook.** `KidInCallService` sees only the calls our dialer
handles. The launcher's full call-edge source is `MusicCalls.init/evaluate` (`main/.../music/MusicCalls.kt:37-52`):
`VoipCalls.addListener` + `OngoingCalls.addListener` + `AudioManager.addOnModeChangedListener`, with
`musicCallActive(voip, ourCall, systemCall)` (`MusicMediaPlan.kt:142`). `VoipPhase` != NONE includes a ringing
VoIP call, which matches PLAN's "when a call comes in". `PinLockRuntime.callForCamera` isn't updated on an
audio-mode-only change. Fix: turn the torch off on the false->true edge of the same three sources, ideally through a
small shared call-edge helper so MusicCalls and Torch don't drift.

**41-4 (Medium) - the lock button's layout and fit claims don't match the XML or the tests.**
`view_lock_glance.xml`: `lock_glance_emergency` is a direct child of the vertical root, a centred 48 dp text link
(:317-331). There is no "bottom row". Putting the button "beside" it means wrapping both in a row. That changes
`LockGlanceTest`'s order test (`root.kids().takeLast(2)` = unlock, emergency, :168-175), the `fixed` measurement
(`withMargins(lock_glance_emergency)`, `GlancePanel.kt:467` and the test's :48/:83-88), and the centring of
"Nødsamtale". A button ≤ 48 dp beside the link adds nothing to `fixed`, so "the fixed height includes the button" is
wrong as a test. Fix: name the container (e.g. a `FrameLayout` with "Nødsamtale" centred and the 48 dp torch at the
end), measure the container in `fixed`, keep 106 dp, and update the order/fixed tests in `LockGlanceTest`, not a
`GlanceFitTest`.

**41-5 (Low) - Home.** `GridTile` is sealed (`ui/home/HomeModel.kt:24-33`). The tile should be `data class
Flashlight(val lit: Boolean)` so `homeGrid(..., torch: TorchState?)` carries the state. `HomeGridAdapter`'s two
`when`s need it (:75, :139), and `renderGrid()` (`ui/HomeActivity.kt:740`) is the cheap re-render for the callback.
Give it a TalkBack `stateDescription` ("på"/"av"), because it is the only tile that toggles instead of opening.
`ui/home/` is GPL code, outside the MIT directories (`ui/list/`, `apps/`), so it's fine there.

**41-6 (Low) - the timer and the restart.** Use `setExactAndAllowWhileIdle` (the launcher holds `USE_EXACT_ALARM`,
`AndroidManifest.xml:92`, as `TimeRuleAlarm.kt:46` does). `setAndAllowWhileIdle` can run minutes late in Doze, which
is likely with a lit phone in a pocket. Cancel the alarm on every off path. CameraService turns off a torch whose
client binder died (to my knowledge, `handleTorchClientBinderDied`), so "off at a process restart" is the platform's
doing: the restart only re-reads it through the callback.

Verdict 41: **needs a revision** (41-1: no lift. Settle 41-3 and 41-4 in the note, then the rest can go into the
brief).

## 42 - the clock on the lock

**42-1 (High) - the data source is unverified, and AOSP's clock doesn't post it.** §1.1 rests on
`EXTRA_SHOW_CHRONOMETER` / `EXTRA_CHRONOMETER_COUNT_DOWN` / `when`. AOSP DeskClock's `TimerNotificationBuilder` and
the stopwatch notification draw their chronometer in a custom `RemoteViews` (`DecoratedCustomViewStyle`,
`setChronometer`/`setChronometerCountDown` on a view), without `setUsesChronometer`, often without a content title or
text. If Google Clock or the Jelly Star's clock does the same, `ClockReader` finds nothing, and the only place the
base lives is a foreign view, which the standing rule forbids reading or rendering. Fix: add a §4 step 0 before any
code. On the emulator and the Jelly Star, run `adb shell dumpsys notification --noredact` with a running timer, a
paused timer and a stopwatch, and record the extras, `when`, the actions (titles, `isActivity`/`isService`/
`isBroadcast`) and whether the content view is custom. Then fix the reader's rules to what is actually there, or
stop and report to the user.

**42-2 (High) - firing the actions: the sender's options and the activity case.** All the existing sends use
`VisibleSender.options()` (`lock/VisibleSender.kt`), which grants our BAL privilege (ALLOW_IF_VISIBLE on 36, ALLOWED
on 34/35). We are the device owner and HOME, and the lock's window is visible, so an action `PendingIntent` sent that
way may start the clock's activity over the LOCKED page without the keypad. Copying the existing pattern gives
exactly that. The trampoline rules (Android 12+) only cover the shade's notification-tap token. A listener's own
`send()` gets no token, so the clock app's own exemptions decide: e.g. a privileged system clock with
`START_ACTIVITIES_FROM_BACKGROUND` can still start one. Fix: offer an action only when `!actionIntent.isActivity`
(API 31+, minSdk 34). Send it from its own file (not `GlancePanel`, whose guard forbids `.send(`,
`LockGlanceGuardTest.kt:231-243`) with `ActivityOptions.makeBasic().setPendingIntentBackgroundActivityStartMode
(MODE_BACKGROUND_ACTIVITY_START_DENIED)`. Add a guard test that pins that single send, the `isActivity` filter and the
system-clock package. Also unknown: whether a `getService`/`getForegroundService` action sent by us (not by the
system, so no temp allowlist) starts the clock's service while the clock is in the background. It may silently do
nothing. Add it to §4, with "no button" as the fallback.

**42-3 (Medium) - the listener doesn't route these notifications to `GlanceReader`.** `GlanceReader.read` drops
every package the parent didn't tick (`badges/GlanceReader.kt:90`), and `glanceCandidate` drops every ongoing or
non-clearable one (`badges/GlanceRows.kt:104-108`). So "the lock's listener reads it already for the glance rows"
(PLAN, §1.1) is false. `LockGlanceGuardTest` also pins `GlanceReader`'s identifiers and forbids `actions`,
`getString`, `creatorPackage` there (:59-158), and requires the listener to read nothing itself (:350-360). Fix: a
separate `ClockReader` called from `BadgeListenerService`'s posted/removed/connected/disconnected the way
`VoipCallReader` is, with its own allowlist guard test, filtering on `systemClockPackage` first. That value is
`PinLockRuntime`'s private one (`lock/PinLockRuntime.kt:1315`), so it needs to be exposed.

**42-4 (Medium) - `glanceFit` has no term or candidates for a second tile.** `GlanceHeights` has one tile
(`tileFull`/`tileCompact`), and the candidates choose one size (`lock/LockGlance.kt:151-186`). The "planned
time-rule card" term doesn't exist. Fix: specify the new term and the candidate order with both tiles up (e.g. the
music tile's compact form first, then fewer rows, then the timer tile without its buttons). Add it to the
`LockGlanceTest` model and layout tests, including 320 dp at the largest font scale.

**42-5 (Medium) - the tile's tap target.** `GlanceTarget` is Conversation | MissedCalls, and the opener's contract is
one send + two starts (`LockGlanceGuardTest.kt:284-302`). The clock notice isn't in `GlanceStore`, so
`GlanceStore.contentIntent` can't capture it. Fix: name the target (e.g. `GlanceTarget.App(pkg)`, intent from
`ClockReader`, else the launch intent through the existing start), keep the opener's counts, and add the case to
`glanceUsableUnderLock` (whether the clock app is usable under a time rule).

**42-6 (Low).** The cleaner is `cleanGlanceText(raw, max)` (`GlanceRows.kt:197`), not `GlanceRows.cleanText`. "The
first two actions" may give "Pause, +1 min" and no "Stop". Choose by a stable rule or show what the clock orders, and
say which. The alarm line should carry the weekday when the alarm is more than 24 h away ("Alarm tor 07:00"). The
ticking time text needs a fixed width with tabular figures, so the per-second update never asks the page for a
layout.

Verdict 42: **needs a revision** (42-1's probe first, then 42-2 to 42-5 in the note).

## r6 - the small items

**r6-1 (High) - §1's premise is partly wrong: two of the four requests already exist.**
`ForeignAccess.checkInBackground` already calls `SyncRunner.request(app, "foreign_access")` on a changed report
(`main/.../server/ForeignAccess.kt:85-91`). `BatteryStatus.watch` already requests on `ACTION_BATTERY_LOW` and
`ACTION_BATTERY_OKAY` (`BatteryStatus.kt:52-67`). "Not on OKAY" would remove an existing report edge. An unseen boot
is always a process start, and every start already runs a sync (`CommandListenerService.kt:125-127`); `record` runs
in `Application.onCreate` in direct boot (`Application.kt:177`). Fix: §1 shrinks to the parent PIN. Drop the boot
item, leave foreign access and battery as they are (the OKAY sync stays), and correct the PLAN entry's "today only
the emergency call".

**r6-2 (High) - the PIN request at `ParentPin.kt:246` fires before the verdict.** The save at :246 is design 24's
"count first": it commits a provisional `wrong` (or `lockout`) event before PBKDF2. On a match, :264 replaces it with
`unlocked` from the pre-attempt queue. A sync requested at :246 can upload the provisional `wrong` event, which would
alert the parent to a wrong PIN on every correct entry. `checkParentPin` is also the pure, store-injected step
(ParentPinStoreTest). Fix: request in the glue (`ParentPin.check`, the caller of `checkParentPin`), after the result
is final. Do it for `Ok`, `Wrong`, and a `LockedOut` that started a lockout now. Never do it for an already-running
lockout (:241, no event), so a kid hammering OK during a lockout causes no syncs. `SyncTriggers.pinEvent` should take
that result, not a constant `true`.

**r6-3 (Medium) - use `SyncRunner.request`, not `CommandListenerService.requestSync`.** Every existing trigger
(emergency `KidInCallService.kt:191-199`, foreign access, battery) goes through `SyncRunner.request`. It takes the
wake lock and the pending count (`push/SyncRunner.kt:40-52`). Calling `requestSync` directly loses both. Storm
check: `SyncCoalescer` folds requests during a run into one more run. Each `ACTION_SYNC`, though, runs
`checkStream(reason, restartAttempt = true)` (`CommandListenerService.kt:133-139, 176-183`), which cancels an
in-flight SSE connect and resets the backoff while the stream is down. With r6-2's "no event, no request", the
lockout bounds the PIN path to a handful per lockout. That is acceptable.

**r6-4 (High) - render-alone misses a tile whose height changes.** `lock_media_item` is `maxLines="2"`
(`view_lock_glance.xml:94-102`), and the entry line comes and goes (`MusicLockPanel.kt:129-134`). A track change from
a one-line to a two-line title grows the full tile by about a line. With §2 the fit only reruns when the tile's
presence flips, so the page overflows its fitted height. The vertical root then pushes "Lås opp" and "Nødsamtale"
past the bottom (the `Space` weight is all that gives). The emergency link must stay reachable. Fix: refit when the
presence flips or the shown tile's measured height changes: after `render()`, measure it at the page width and
compare with the last fit's term. A cheaper alternative is to reserve the tile's height (`minLines="2"`, the entry
line always laid out).

**r6-5 (Medium) - the time line changes the fit's pinned numbers, so "GlanceFitTest unchanged" is wrong.**
`LockGlanceTest` pins `tileFull` 211 / `tileCompact` 90 from the XML (:376-400), the model at :66-69 and the expected
fits at 473/497 dp. All of them move with the new line. Fix: re-derive them in the same commit, and add the line to
the 320 dp width checks. For the compact tile, "in the same line as its progress" leaves the 3 dp bar a sliver: at
320 dp the text column has 320-32-144 = 144 dp (`LockGlanceTest` :135-141), and "44:48 · 1:25:36" at 13 sp under the
largest (nonlinear, up to 200 %) font scale takes most of it. Put the compact tile's time line under its bar like the
full tile's (or show only the position there), and test that the bar keeps ≥ 120 dp. Give the line a fixed width
with tabular figures, so the per-second tick never relayouts the page (the root's layout listener refits only on a
height change, `GlancePanel.kt:119-121`, but a wrap_content width still asks for a layout every second).

**r6-6 (Low) - measure-only copies.** A detached `TextView` or `StaticLayout` must match the clock's font, weight,
font padding and the current font scale, or the model's 78.4/56 dp drift. Once r6-4's rule makes refits rare,
measuring the live views only at a refit (as today) is enough. If copies are kept, add a test that the copy measures
the same as the live view.

**r6-7 (Low) - scope.** PLAN's flicker entry also asks for "the same recording method on the music app's player
screen ... before touching it". §2 leaves it out. Add it as a device-check step, with no code this round.

Verdict r6: **approved with the Medium+ fixes folded into the brief** (r6-1 shrinks §1 to the parent PIN; r6-2,
r6-3, r6-4, r6-5 into the brief).

## Interactions

- 41's button sits in the bottom container (41-4) and 42's tile is a new fit term (42-4), so both change
  `LockGlanceTest`'s pinned model. Do them in one fit change, after r6 §2's time line (r6-5), so the numbers are
  re-derived once.
- r6-4's refit rule applies to 42's tile too: refit when it comes or goes, or when its height changes (the buttons
  appear or the paused text wraps). It never refits per tick.
- Standing rules: nothing above renders a foreign view, adds an API change or removes a stock gesture. 41's lift
  (41-1) and 42's sends (42-2) are the two places where the lock's "nothing opens without the keypad" contract is at
  risk.
