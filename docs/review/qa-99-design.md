# Design QA: Snarveier, the kid's quick panel in the shade (`docs/design/99-snarveier.md`)

Checked against `design-99` (631e74067, = `main` c7ae25a33 + the design). Read: root `CLAUDE.md`, `launcher/CLAUDE.md`,
`docs/notes/launcher/kiosk-escapes-and-updates.md` (the notification rule), `lock-and-glance.md` (LOCKED in lock task,
the faces), `music-bridge.md` ("Sessions", "Time rules"); design 98 §2, §4, §7, §9.2 and its `docs/testing/emulator.md`
§6am results; the launcher's `lock/LockTaskChrome.kt`, `server/LockTaskHelpers.kt` (`lockTaskWhileLocked`),
`lock/StatusBarHealPlan.kt`, `lock/KeyguardCoverPlan.kt`, `server/QuickControls.kt`, `ui/kidsettings/KidSettingsModel.kt`
(`controlsSection`), `timerules/TimeAskReceiver.kt`, `timerules/RuleSilence.kt`, `torch/Torch.kt`, `lock/CameraLockPlan.kt`,
`music/LockSessionPick.kt`, `Application.kt` (`initRest`), the manifest (the two `specialUse` services); the server's
`handlers/devices.rs` (`QUICK_CONTROL_*`, `DEFAULT_QUICK_CONTROLS`, the Home form's `kept_bits`), `handlers/mobile_page.rs`
(bit 32's own writer), `templates/device_home.html`, `i18n/*.json`, `migrations/` (`main` ends at 0147). The probe and
its 41 screenshots and `uiautomator` dumps in the scratchpad `d99/` (`probe/src/.../N.java`, `out/*.png|xml`).

**Verdict: the measurements hold up and the shape is right, but one safety premise inherited from design 98 is false
in exactly the case this design creates, and D1 is put to the user as narrower than it is.** S2 (the first notification
always unfolds, a fold is forgotten on close) is backed by `t4a`-`t4e`; S4/S5 (only the colorized FGS escapes the
bundle and sits on top) by `ag5`, `v2`, `t6b`; S10's 195 dp by `v2` (292 px); S14 by `kg1`. The `t3*` frames don't back
S2 (another notification was first there), but `t4*` do. Items, gates and the server side follow design 97/98's
patterns closely; bit 128 is free, 0148 is the next migration, and `controlsSection` ignores unknown bits, so an older
launcher shows no empty card. "Spotify" appears nowhere the kid sees it.

Counts: **1 High, 2 Medium, 11 Low.**

## Findings

### Q99-H1 High - the route's "status bar disabled" does nothing under the kiosk, and Snarveier starts the route with the shade open (§4.1 "Flymodus from the shade", short version, §2.2)

§4.1: "It closes the shade: the route disables the status bar for its window ... which collapses an open shade." That
is design 98 §2.2 step 4 (`lockTaskWhileLocked`'s `routePin` branch: `statusBarDisabled = true`, written by
`LockTaskChrome.writeStatusBar` -> `dpm.setStatusBarDisabled`). But in lock task DPMS only *records*
`setStatusBarDisabled`: the repo says so itself (`lock/StatusBarHealPlan.kt:12`: "`setStatusBarDisabled` can't force
that change in lock task: DPMS only records it there (LockTaskController owns the status bar during lock task)";
`LockTaskChrome.writeStatusBar`: "Blocks the shade and quick settings *outside* lock task"). AOSP's
`setStatusBarDisabled` skips `setStatusBarDisabledInternal` while `getLockTaskModeState() != NONE`. The kiosk is lock
task, so the route's window leaves the shade as it is. Design 98's §6am run measured `mDisabled1` only *after* the
window ("back to 0"), never during it.

Until now that was latent: the route ran from Kid Settings or the rule screen's sheet, with the shade closed. Snarveier
starts it **from the open shade, with the kid's finger in it**. For the window, Settings is on the lock-task list and
the shade still shows every notification, including the ones that open Settings: Android's own "Wi-Fi stays on in
airplane mode" notice, which is posted *during* the window (design 98 §3.5 counted on the disable for exactly this),
and the "Security & privacy · Set a screen lock" nag that `t3b`-`t3d` show in the shade under lock task. A tap
opens Settings pinned. Step 7's put-back (Settings off the list clears its tasks, the hide kills it) contains it, but
that containment is still an open device check (design 98 §12 test 2c), and it's the mitigation Q98-M1 relied on. The
same wrong mechanism is in §2.2's "Vibb's PIN lock: the shade can't open, because `LockTaskChrome` disables the
status bar while LOCKED". The conclusion holds there, but the reason is that `featuresWhileLocked` drops
`LOCK_TASK_FEATURE_NOTIFICATIONS`.

**Fix:** in `lockTaskWhileLocked`'s `routePin` branch, also drop `LOCK_TASK_FEATURE_NOTIFICATIONS` from the features
for the window. `LockTaskController` then sets `DISABLE_EXPAND`, SystemUI collapses an open shade, and the window
can't reopen it. Keep `statusBarDisabled = true` for the kiosk-off case, where it does act. Pin it in the
`LockTaskHelpers` test. §10 test 5: `dumpsys statusbar` *during* the window shows `DISABLE_EXPAND`, the shade closes,
and a tap on a Settings notification posted in the window starts nothing. Correct design 98 §2.2 and §3.5 and §2.2 here.
This changes merged code, so make it step 0 of this build, or a small fix round before it.

### Q99-M1 Medium - the tap-time re-check doesn't name its inputs, and Musikk's gate rests on a wrong premise (§2.1, §4.1, §4.5)

§2.1 says the receiver "re-reads the cached policy, the kid's picks and the platform state". Three gaps:

- **Musikk under a rule.** §4.5: "Musikk has no session while the music app is outside the rule's usable apps, so it is
  dimmed then." That isn't how it works. `apply()` *pauses* the playing sessions before it suspends a package
  (`music-bridge.md`, "Time rules"), and the paused session stays registered. The existing surfaces don't rely on the
  session being gone. They gate on `lockAppUsable` (`LockSessionPick.kt:87`: "never while suspended (a time rule, the
  used-up budget, the camera lock)") and `MusicSessions.usable`. A Musikk tap built on "a session exists" sends play to
  a paused session of an app the rule made unusable. Whether a suspended app can make sound then depends on the
  platform, and a rule must not rest on that. Also say whose session the item drives: Vibb music only, or the glance's
  pick of any app.
- **LOCKED in a cold process.** A tap can reach a process that is just starting (a broadcast to a dead or restarting
  launcher process runs `Application.onCreate` first, and parts of init are posted). The design doesn't say what the receiver reads as "LOCKED" before
  `PinLockRuntime` and the time-rule snapshot are initialised. It must refuse while either is unknown.
- **The time rule.** The model takes `(policy, picks, platform)`. Mer tid and Musikk depend on the rule in force
  (`TimeRulesRuntime.snapshot`), and the design doesn't list that input.

**Fix:** one pure `shortcutTapAllowed(key, modelNow, lockKnown, locked, keyguardLocked, musicUsable)` in `ShortcutsTest`.
Its inputs: `currentPolicyDecision()`, `PinLockRuntime.mode`, `TimeRulesRuntime.snapshot`, `MusicSessions.usable` /
`lockAppUsable` for Musikk, and `KeyguardManager.isKeyguardLocked` (Q99-L1). Fail closed when an input is unknown. Make
Musikk's "not usable" row read "the music app isn't usable now (a rule, the camera lock)", not "no session".

### Q99-M2 Medium - D1 presents "narrow by default" as impossible, but an app-side toggle gives it (§1 "What this means", §11 D1)

The short version says "no API changes it", and D1's "Not possible: the row by default with the grid on a tap". That
holds only if the fold has to be *Android's* fold. The app chooses what it posts, and S2 + S12 make an app-side toggle
work:

- Post the row only, with no big view (S11: when first, 98 dp, at the full 220 dp width).
- Add a small "Navn" / "⋯" cell. 220 / 7 is about 31 dp per cell, still wider than D1 a's folded 30 dp cells.
- A tap on that cell reposts with the grid as the big view. S2: the first notification shows expanded, also across
  updates. S12: the shade stays open on a broadcast tap. So the grid appears in place.
- Back to the row-only post at screen off, after N seconds, or after the next item tap. An app can't see the shade
  close, so that is the one cost.

That is the user's own wish ("smale ... som standard", the grid "ved å trykke"), so it belongs in D1 as option c with
its cost: a seventh cell, a reset that is a proxy for the shade closing, and one repost per tap. D1 a also needs one
honest line: the fold lasts only while the shade is open, so with a the kid will practically never see the row. a is
"always the grid".

### Q99-L1 Low - "Never on the lock screen" isn't true on a Swipe keyguard (§2.2)

`VISIBILITY_SECRET` hides only on a *secure* keyguard (SystemUI's public mode needs a secure method). The ANDROID face
disables secure notifications altogether (`ANDROID_KEYGUARD_FEATURES`), and DISABLED has no keyguard. But
`KeyguardMode.UNTOUCHED` (managed without a kid PIN) leaves the phone's own keyguard, which can be Swipe. There
Snarveier shows if the lock screen shows silent notifications, and its broadcasts fire without an unlock. S14 measured
only a PIN. **Fix:** the receiver refuses while `isKeyguardLocked` (Q99-M1), and §10 test 4 adds a Swipe keyguard.

### Q99-L2 Low - the PWA copy gives the kid a pronoun (§5.2, §8, D8)

"Hun kan vise dem igjen" / "She can show them again". The server's `nb.json` and `en.json` have no kid pronoun anywhere,
and a boy gets "Hun". **Fix:** "Emma kan vise dem igjen under Innstillinger › Snarveier." / "Emma can show them again
under Settings › Shortcuts." (the name twice). In the design's own prose (§8, D8), use "the kid" or "their".

### Q99-L3 Low - the listeners live only while the service runs, but whether it runs depends on what they watch (§2.3, §2.4)

`refresh()` hears platform changes only through receivers that `ShortcutsService` registers while it runs. A panel
whose picks are all absent never starts until the next `apply()`. Examples: only Flymodus with the role not held, then
airplane mode on (the Off-only item); only Mer tid before a budget day. `MusicSessions` watches sessions only while
Home or the lock shows a surface (`music-bridge.md`, "Sessions"), so Musikk's peach goes stale over other apps.
**Fix:** `ShortcutsRuntime` keeps the few receivers from `initRest` (the HOME process lives anyway), and
`MusicSessions` gets a "panel posted" watch reason.

### Q99-L4 Low - `START_STICKY` and the single-owner guard pull against each other (§2.4)

A sticky restart calls `onStartCommand(null)` outside `ShortcutsRuntime`'s start, so the "one owner" guard (the
`VpnForeground` race) has a second entry. `initRest` re-decides at every process start anyway, and the launcher is
HOME. **Fix:** `START_NOT_STICKY`, or `onStartCommand` that always asks `ShortcutsRuntime` and then either calls
`startForeground` or stops.

### Q99-L5 Low - the receiver hands work to the service's executor (§2.1)

A tap that lands while the service is stopping, or before it is created, has no executor. **Fix:** `goAsync()` and an
app-level single executor (`AirplaneRoute` already runs one request at a time off main).

### Q99-L6 Low - three foreground services, not two (§9, §2.1)

`KidVpnService` is also a `specialUse` foreground service in the same process (manifest), as S4 itself notes ("the
anchor's, the VPN's"). Correct §9's count. The cost conclusion stands.

### Q99-L7 Low - Android's per-package update rate is shared (§2.3)

NMS's ~5 enqueues a second is per package. At 4 posts a second Snarveier can make Android drop the anchor's or the
VPN's updates in a burst. **Fix:** coalesce to one post a second (a disc that lags 1 s is fine), or at most 2 a second.

### Q99-L8 Low - grouping with the launcher's own alerting and call notifications not measured (§1 S4, §10 test 1)

The probe had one alerting "Mamma" and two silent notifications. The launcher also posts Meldinger (several threads),
missed calls and, as the dialer, a CallStyle notification, which shares the colorized top section. **Fix:** §10 test 1
with three Meldinger threads, a missed call and an ongoing call. Check that the panel stays apart and in what order the
call and the panel sit.

### Q99-L9 Low - the default order and the examples disagree, and the default reshuffles itself (§4.2, §4.3, §5.2, D4)

The default is Lommelykt, Lyd, Flymodus. §4.2's sub-line, §5.2's example and the probe read Lommelykt, Flymodus, Lyd.
With `null` picks, the panel changes on its own when the assistant step completes: Flymodus enters and Lysstyrke
leaves. **Fix:** one order everywhere, and a sentence in D4 that the default panel follows the role.

### Q99-L10 Low - a dimmed item's reason is nowhere under a rule (§3.3, D10)

"Kid Settings' row says why", but Kid Settings is unreachable under a rule, and Mer tid and Musikk have no row there.
Add that to D10's trade-off, or put the reason in the content description (TalkBack at least).

### Q99-L11 Low - "both gestures and buttons" (the user's rule) isn't in the tests (§4.2, §10)

The shade opens the same way in both navigation modes, and the picker uses buttons, not dragging, which is good. The
screen-size matrix's S1 (a row under the 3-button bar) is the risk on the picker page. **Fix:** the picker pads its
last row by `KidInsets`' bar inset. §10 runs test 1 and the picker in gesture and 3-button mode on `jelly36`.

## Answers to the brief's questions

- **Lock safety.** No item opens an activity, and every item is one the kid already has (Kid Settings, Home, the
  lock, the anchor) under the same bit. Flymodus goes through design 98's `AirplaneRoute` with its refusals: LOCKED
  through the chrome, the 10 s cool-down, and ECBM + 10 min after a 112 call. A rule is never weakened: Lyd is dimmed
  under a silent rule's automatic DND (`RuleSilence`), and Mobildata only restricts. The exceptions are Q99-H1 (the
  route with an open shade) and Q99-M1 (Musikk, cold LOCKED). The PendingIntents are immutable and explicit, they
  carry only the key, and the receiver re-derives everything. After a policy change or a reboot a stale one acts only
  if the current model allows it.
- **Notification rule, kiosk, FGS.** `BadgeListenerService` and the badges skip our package. `specialUse` isn't on
  Android 15's boot-start list. A device owner is exempt from the background FGS start limits and from the FGS Task
  Manager's Stop. `FOREGROUND_SERVICE_SPECIAL_USE` is already declared.
- **Liftable, migration, older sides.** The panel adds no restriction (§4.4). Bit 128 follows the Q98-M9 keep exactly.
  0148 is free. `controlsSection` ignores unknown bits. An older launcher keeps its Home tile. This launcher with a
  server older than 0148 loses Home's tile, with only the lock's torch left (release order, §6.3: acceptable).
- **PWA.** It uses `#quick-controls` and the staying POST (no jump to the top), has no bell on the sub-page, has nb and
  en, and puts `shortcuts.json` in both directories with both workflows' path lists. Only Q99-L2's wording needs a
  change.
- **D-list.** D2-D11 are sound. D1 needs option c (Q99-M2). D9 could say plainly that the panel makes Wi-Fi,
  Bluetooth, Lyd, Lysstyrke and Mobildata reachable under a rule, where Kid Settings isn't today. It weakens nothing,
  but it is new.
