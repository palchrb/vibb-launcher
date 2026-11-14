# Design QA: Flymodus through the Digital assistant role (`docs/design/98-airplane-mode-assistant.md`)

Checked against `design-98` (82abae187, = `main` aa06ce543 + the design). Read: root `CLAUDE.md`, `launcher/CLAUDE.md`,
`docs/notes/launcher/hardening.md`, `lock-and-glance.md` ("Always in lock task while LOCKED"), `home-and-photos.md`
(16d, the recents provider), `kiosk-escapes-and-updates.md` (the notification rule), `calls.md` (the roles, the
callback window); the launcher's `server/AppEnforcer.kt` (`apply`, `applyDialerRole`, `applySmsRole`,
`enforceDefaultHome`, `reseatPreferredActivities`, `enforceCameraOnPackage`), `lock/LockTaskChrome.kt`,
`sms/SmsRole.kt` (`lockDefaultAppsFor`), `calls/DialerRole.kt`, `server/MdmSyncWorker.kt` (`performMdmSync`'s order),
`server/WriteSettingsAccess.kt`, `ui/settings/launcher/SettingsFragmentLauncher.kt` (the grant rows),
`server/SelfUpdate.kt` (the ECBM check), `ui/kidsettings/KidSettingsModel.kt`; the server's `handlers/devices.rs`
(`QUICK_CONTROL_*`, the Home form's SQL), `parent_events.rs` (`emergency_call`), `handlers/window_relock.rs`
(`start_pause`), `testdata/status_heartbeat.json`, `migrations/` (`main` ends at 0145). The measurements in the
scratchpad `d98/` (the probe's `Cmd`, `ProbeVIS`, `ProbeSessionService`, `vis.xml`, the screenshots) and `cell-off/`.

**Verdict: the route is sound and the measurements hold up; the plumbing around it needs work before the build.** The
assistant role, the marker, `setDisabledShowContext`, add-then-clear for Off and the onboarding through Android's own
page are the right shape, and M10 (no assist gesture under lock task) is a real finding that settles §9.2. The problems
are in how the route touches the kiosk: it writes the lock-task list past `LockTaskChrome`, the one writer the code
allows, and its half-second window is wider and more steerable than §2.2 says. The default-apps window leaves roles
that nothing takes back. The report-first step can't work as placed, and a kid can end up in airplane mode with no row
to leave it.

Counts: **1 High, 9 Medium, 8 Low.**

## Findings

### Q98-H1 High - the route writes the lock-task list past `LockTaskChrome`, and its `finally` undoes the LOCKED pass

`lock-and-glance.md`: "**`lock/LockTaskChrome` is now the only place that sets lock-task packages/features, the status
bar and `DISALLOW_CREATE_WINDOWS`** ... never set them anywhere else." §2.2 step 3 adds `com.android.settings` with its
own `setLockTaskPackages`, and step 6 writes back "the lock-task packages as `apply()` last wrote them". It does this
under `AppEnforcer`'s monitor, which doesn't serialise with `LockTaskChrome`. `PinLockRuntime` calls
`LockTaskChrome.refresh` on the main thread at every LOCKED edge, outside that monitor (`PinLockRuntime.kt:815,830`).
`VoipCalls` and the "sos" yield do the same.

Scenario: the kid taps Flymodus and presses power at once, or the screen times out during step 2's 2 s. The LOCKED
pass writes the kiosk list without the recents provider (qa-16d-code #1) and without Settings, which also clears the
voice activity's task, so the route times out. Then the route's `finally` writes `apply()`'s list, which includes the
recents provider. Quickstep is now pinned while LOCKED, which is the Overview-over-the-lock escape 16d closed. It stays
pinned until the next chrome pass, which is the unlock. In the other order (step 3 after the LOCKED pass), Settings is
added to a LOCKED kiosk. `LockTaskChrome`'s latch and `plan` also no longer match the platform.

Change: make the route an input of `LockTaskChrome`, as `sosNarrowing` and `voipPackages()` are. Add a
`routePin: String?` under its monitor. `applyNow` adds it to the kiosk list only while not `chromeLocked` and the kiosk
is on, and the route sets and clears it through `refresh`. A LOCKED pass then leaves Settings out, and the route sees
its start blocked and ends with `timeout`. Step 6 then never writes the list itself. Add a `LockTaskHelpersTest` row:
`routePin` with `locked = true` leaves both Settings and the recents provider out. Add §12 test 2b: tap On, then power
at once, then `dumpsys activity lock_task` while LOCKED shows no recents provider and no Settings.

### Q98-M1 Medium - the half-second window is opened by the kid, can be repeated, and can be wider than half a second

§2.2 says "A kid can't time that". But the kid opens the window: a tap on the switch, then step 2's up to 2 s, then
Settings shown and pinned. `LOCK_TASK_FEATURE_NOTIFICATIONS` is in the default features (63), so the kid can pull the
shade during step 2 and tap a notification that targets Settings until one tap lands. A failed `PendingIntent` to a
hidden package does nothing, so the kid can tap again. The notification rule never cancels `android`/SystemUI
notifications (`kiosk-escapes-and-updates.md`). Examples: the USB "Charging this device via USB" notification (it opens
Settings' USB preferences and shows for as long as the phone is plugged into a computer), storage-low, and the one-time
"Wi-Fi stays on in airplane mode" notification, which §3.5 makes appear at exactly this moment. Normally the app block
turns those taps into BlockedAppActivity. During the window, Settings is pinned, so the real page opens. On/Off can be
repeated without limit, so the kid gets as many tries as they want.

The window is also not bounded at 0.3-0.5 s. M4 measured 270 ms from `showSession` to airplane mode on. It didn't
measure from unhide to hide (the probe ran unhide, route and hide as separate adb broadcasts). On a refusal or a slow
OEM, the bound is the 2 s deadline. If an OEM resolves `ACTION_VOICE_CONTROL_AIRPLANE_MODE` to an ordinary Settings
page instead of the translucent, self-finishing `AirplaneModeVoiceActivity`, the kid gets a full Settings page, pinned,
for 2 s.

Removing Settings from the list clears its tasks, and the hide kills it, so the window closes behind the kid (AOSP
`LockTaskController.updateLockTaskPackages` clears locked tasks that lost authorization). But that is unmeasured, and
it's the whole containment.

Change:
1. While `routePin` is set (H1), `LockTaskChrome` also disables the status bar (`setStatusBarDisabled(true)`, already
   its write for LOCKED). The shade then can't be pulled during the window, and one that is already open closes.
2. `startVoiceActivity` with an explicit component. First resolve the action with `MATCH_SYSTEM_ONLY`, and accept only
   `com.android.settings` with the voice category and a translucent theme. Anything else is `route_missing`, never a
   start.
3. A cool-down of 10 s between two turn-ons. That also bounds the reports a kid can cause with toggling.
4. Add device checks to §12 test 2: a Settings activity started during the window is gone after step 6, and the
   Jelly Star's resolved component and theme for the action.

### Q98-M2 Medium - process death or a reboot mid-route leaves Settings shown and pinned until an `apply()` that may be far off

"If the process dies between 3 and 6, the next `apply()` puts both back". `apply()` does diff against the live hidden
state (`isApplicationHidden`), so the end state is right. But the next `apply()` after a process start runs inside
`performMdmSync`, after the policy fetch (`MdmSyncWorker.kt:245`). On Wi-Fi with an unreachable server, that is the
connect and read timeouts, many seconds, with Settings unhidden and on the lock-task list. A reboot mid-route is worse:
both persist across the reboot, and nothing runs before unlock and the first sync.

Change: write a DE pref `airplane_route_open` (with the boot count) before step 3, and clear it in step 6. At process
start (`initRest`, before the sync) and in `BootCallReceiver`'s unlocked path, if it is set, run the put-back
synchronously on a background thread at once: hide Settings, clear `routePin`, refresh the chrome. `AirplaneRouteTest`
covers "the marker set at start -> put back before the first sync".

### Q98-M3 Medium - the report-first step can't send a report while the route holds `AppEnforcer`'s monitor

§2.2 runs under `AppEnforcer`'s monitor, and step 2 posts a status report "up to 2 s". The only path that posts a
report is `performMdmSync`. It fetches the policy and calls `AppEnforcer.apply()` (`@Synchronized`, line 245) before
`sendReport` (line 341). Under the route's monitor the sync blocks in `apply()`, the 2 s pass, and the radio goes off
before the report is sent. So the step fails every time, and the parent sees "not heard from", which the step was meant
to prevent. Even outside the monitor, a full sync with TLS, the GET, `apply()` and the POST rarely fits in 2 s.

Change: run step 2 before taking the monitor. Either use a report-only post (no policy GET, no `apply()`, the same
`StatusReports` hash state, under `syncMutex`), or run the full `performMdmSync` with a 5 s budget and the row's busy
line. Then take the monitor for steps 3-6 only. If the route fails after a `going_on` report went out, request a sync
at once (Q98-L2).

### Q98-M4 Medium - the default-apps window frees roles that nothing takes back, and HOME is assumed

§3.2 says the window ends and "`applyDialerRole` and `applySmsRole` take back a dialer or SMS role someone moved". Under
the lifted restriction, Android's Default apps page also offers these roles:

- **Caller ID & spam (`CALL_SCREENING`)**, and call redirection where Android shows it. Both are granted only with
  adb at provisioning (`calls.md`). Telecom binds the screening role holder's service ahead of the default phone app's,
  so another holder screens instead of `KidCallScreeningService`. Only the in-call re-check is left, after the phone
  has rung. No device-owner API takes it back. Home's prompt doesn't either: `rolePromptFor` never runs while we hold
  the dialer role. The server warns only when neither role is ours, so a lost screening role isn't even reported.
- **Home app.** Our persistent preferred HOME activity should still win when Home resolves. But `isRoleHeld(ROLE_HOME)`
  feeds the update fence's partial-fence logic (`UpdateFence.kt:511`, `home_role_held`), and §3.2 doesn't say what
  restores it. Re-seating the PPA (`HomePin`'s `reseatPreferredActivities`) likely moves the role back through PMS's
  `updateDefaultHomeNotLocked`. That is a device check, not a fact.
- **Browser.** It can't be taken back, and if the chosen browser isn't allowed, links fail after the window.
- SMS arriving during the window goes to whichever app took the SMS role (its RECEIVE_SMS is denied by policy), so it
  may never reach Vibb's engine.

Change:
1. Make the window as short as the step. `VibbAssistantService.onReady` runs in our process the moment the role
   becomes ours. End the window there (`apply()` at once), as well as on the activity result, the 5 minutes, the pause's
   end and a reboot.
2. Snapshot every exclusive role holder at the window's start: HOME, DIALER, SMS, CALL_SCREENING, CALL_REDIRECTION,
   BROWSER. At its end, compare. Re-seat the HOME PPA, and let the dialer and SMS passes run as designed. Report any
   other change (`airplane_mode` is the wrong field; a `default_apps_changed` list in the report), and show it in the
   PWA's Oppsett card.
3. Fix §3.2's claim to name what is and isn't taken back. Add §12 test 1b: during the window, choose another Home and
   another Caller ID app. After the window, Home is still ours (role and PPA) and the screening change is reported.

An opportunity: the same window, behind the same PIN, is an on-phone path to grant `CALL_SCREENING` (and redirection
where it is shown). Today those are adb-only, against "no adb beyond the QR". Worth a row in "Fullfør oppsett" if the
user wants it.

### Q98-M5 Medium - a kid can be left in airplane mode with no row to leave it

§2.3 says "a kid can always leave airplane mode". But §4's table hides the row whenever bit 64 is off. That is the
state of every existing phone (D1 changes only new ones), and of any phone where the parent turned it off.

Scenario: during Åpne for oppsett (kiosk off), the parent turns airplane mode on from Quick Settings for a flight. The
pause ends after 2 h, the kiosk returns, and Quick Settings is gone under lock task. Bit 64 is off, so there is no
row, and the phone is offline, so no policy change can arrive. The kid stays offline until 112, or until the parent
gets the phone and the PIN pause. Reboot doesn't help (M7). This exists today, and this design is the cheap fix for
it: Off needs neither the role nor Settings.

Change: a row whenever airplane mode is on, whatever the bit and the role: an Off-only switch with "Slå av flymodus" /
"Turn off airplane mode". Not while `DISALLOW_AIRPLANE_MODE` is set, since airplane mode can't be on then. Add contract
`rows` for `{mask without 64, on: true}` and `{role: false, on: true}`.

### Q98-M6 Medium - airplane mode under a time rule: the kid can't leave it, and calls the rule allows can't arrive

"Under a time rule Kid Settings isn't reachable, as today." With airplane mode on before Leggetid or Skole starts, the
phone stays unreachable for the whole rule. The parent's calls that the rule allows can't arrive, and the time-rule
screen offers no way out. The parent can't end it remotely either without kept Wi-Fi. This is a product question the
user hasn't been asked.

Change: a D8. Recommended: while airplane mode is on and the rule in force allows calls, the time-rule screen and the
lock glance show a "Slå av flymodus" row (§2.3, no Settings, no role). Alternatively, block a kid's turn-on within N
minutes of a rule's start. Either way, say it in §7.2.

### Q98-M7 Medium - the route skips Android's own emergency-callback guard

Settings' `AirplaneModeEnabler` refuses airplane mode during Emergency Callback Mode, and asks to leave ECM first. The
voice activity sets the global directly, so §2.2 turns it on during ECBM. A kid who called 112, maybe by mistake, can
turn airplane mode on again a minute later, and the emergency centre can't call back. D3's own reason ("a kid who just
called 112 should be reachable") argues for refusing it.

Change: step 1 also refuses while ECBM is on (the sticky `EMERGENCY_CALLBACK_MODE_CHANGED`, read as
`SelfUpdate.kt:260-266` already does). It also refuses while an emergency call is within the last 10 minutes
(`RECENT_EMERGENCY_CALL_MS`), and preferably while Vibb's own callback window is open. The row says "Etter en nødsamtale
kan ikke flymodus slås på ennå" / "Airplane mode can't be turned on right after an emergency call". Add a
`last_error` word `emergency` and a contract row.

### Q98-M8 Medium - the Wi-Fi setup step can leave the phone in airplane mode

§3.5 turns airplane mode on, opens the Wi-Fi panel, and ends airplane mode "on return". The return is an activity
result, which comes only when the panel finishes. If the parent presses Home (the kiosk is off during the pause), goes
on to other Settings pages, or the pause ends while the panel is open, nothing ends airplane mode. If Wi-Fi wasn't
turned on, the phone is offline after the pause, with no kid row if bit 64 is off (M5).

Change: store `wifi_step_open` (with a 5 min deadline, DE prefs, ends at reboot). End the step, read Wi-Fi, then run
§2.3, on whichever comes first: our Settings' `onResume`, the activity result, the deadline (an alarm), the pause's
end, or process start with the flag set. Add the case to §12 test 4.

### Q98-M9 Medium - the Home form drops bit 64 whenever its row isn't rendered

§6.1 shows the row only for a phone that reported `airplane_mode_v1`, and `update_policy` "sets bit 64 from it like
the five others". The form rebuilds the mask from the fields it gets and keeps only bit 32
(`COALESCE((quick_controls_mask & 32) | ?, …)`, `devices.rs:2924`). So any save of `section=quick`/`all` without the row
clears bit 64.

Scenario with D1: the parent adds a phone (mask with 64), sets up the Home page before the QR scan or before the new
launcher's first report, and saves. Bit 64 is gone, and the default D1 chose never reaches the kid.

Change: the form carries `airplane_row=1` only when it renders the row. Without it, the SQL keeps bit 64 as it keeps 32
(`& (32 | 64)`). With it, bit 64 comes from the switch. Add a server test: a save without the row keeps 64, and a save
with the row unchecked clears it, for both sections.

### Q98-L1 Low - the emergency line arrives after the push

§7.1 adds `airplane_off: true` to the existing `emergency_call` event "when the report's period ended within 10
minutes". The event is raised and pushed from the first report with `call_state.last_emergency_call_at`
(`device_api.rs:1643`). If that report doesn't yet carry the closed period (the edge receiver wrote after the report
was built), the line only reaches the inbox, never the push. Change: write `AirplaneLog`'s edge synchronously in the
receiver before `emergencyPlaced`'s sync is requested, and state the rule: the line is added in the same intake, or
updated later in the inbox only, with no second push.

### Q98-L2 Low - a failed turn-on leaves the PWA on "Flymodus på" for up to 30 minutes

After step 2's `going_on: true`, a refusal or timeout keeps the phone online. But only Off requests a sync (§4), so the
summary reads "Flymodus på siden 21:03" until the next sync, up to the 30 min backstop. Change: request a sync after
every failed turn-on, and make the server's "på" line from `going_on` time out after 10 minutes unless a period or
`on` confirms it.

### Q98-L3 Low - the contract's `rows` and `clean` miss states the design has

`rows` has `{mask, role, restricted, on, wifi_up, busy}`. It needs `last_error` (the "Fikk ikke slått på" state, with
its 1 min), the Off-only rows of M5, and M7's refusal. `clean` should also close or drop an open period (`off_ms: null`)
that isn't the last one, and drop `on_ms` in the future beyond the usual clock slack. Also: §4 says "a mask of only 64
is a card, not `NoneEnabled`", but with the role not held and airplane mode off that card has no row. Say what it
shows (the Q97-L1 pattern).

### Q98-L4 Low - the assist gesture during Åpne for oppsett is a stock gesture removed without asking

With Vibb as the assistant, long-press power (where set to the assistant), the corner swipe and a headset's assist
button open nothing while the kiosk is off (§2.1 hides the session). Before, they opened Google. The user's rule is that
stock gestures stay while the phone is unlocked, and any removal is asked about concretely. Change: name it in §13 as a
cost of D1 and the role, with what the parent loses during setup (Google Assistant / Gemini, Circle to Search), so the
user decides knowingly. Nothing to build.

### Q98-L5 Low - the privacy invariants should cover every way in, and the session needs no window

`AssistantInvariantsTest` lists `AssistStructure`, `AssistContent`, `requestAssist`, `getAssistData` and the
screenshot. Add the other APIs the role opens:

- `onHandleAssist(AssistState)` and `onHandleAssistSecondary` (all overloads overridden empty),
- `registerVisibleActivityCallback` / `VisibleActivityInfo`,
- `requestDirectActions` / `performDirectAction`,
- `setDisabledShowContext` never called with anything but both flags.

Call `setUiEnabled(false)` (API 29) in the session's `onCreate`, so no voice window is ever added, rather than an empty
content view that still takes touches for its lifetime. The recognizer declares no `RECORD_AUDIO`, and the launcher's
self-grant list must never gain it. The test can check the manifest.

### Q98-L6 Low - claims to mark as device checks

- **M10 at the keyguard:** "on a managed phone the kiosk is on, also under the keyguard, so the assist gesture never
  reaches Vibb". The keyguard shots (`m3-kg-*`) are M11, without lock task. The combination wasn't measured. It's
  harmless (the session hides), but §1.2's sentence should say it's inferred.
- **`Settings.Panel.ACTION_WIFI` on Android 16:** whether it is still a Settings panel or the full page, and whether a
  tap there sets `wifi_apm_state` (§1.1 measured Settings and Quick Settings, not the panel). Both are a §12 test 4
  check, and the Jelly Star's through the RC.
- **The Jelly Star's `AirplaneModeVoiceActivity`:** its component and theme (M1 point 2), not only its presence.
- **HOME role vs the PPA** under the window (M4).

### Q98-L7 Low - the Oppsett card nags and needs its anchors and English

- It lists every step on every phone. "Endre systeminnstillinger" matters only for bit 16 and the clock format, and
  "Digital assistent" only with bit 64. On existing phones it appears on every summary until "Ikke nå". List a step
  only when a feature that needs it is on.
- "Wi-Fi i flymodus" is optional (§3.5), and a phone that turns Wi-Fi off in airplane mode (`wifi_kept: false` with the
  OEM line) must count it as done.
- The card's two POSTs (`/start-pause` and "Ikke nå") must land on the card's anchor, never the top. Today
  `start_pause` lands on the summary's `#quick` group, where its flash is (`window_relock.rs:304-312`; there is no
  `back=summary` value, so anything not `advanced` lands there). The card needs its own `back=setup` -> `#oppsett`,
  with the flash shown in the card.
- Missing English: §6.2's "Flymodus ble slått av av en nødsamtale 14:02", §3.4's card sentence, and §6.1's ⓘ.

### Q98-L8 Low - smaller spec points

- §2.2 step 6: "as `apply()` last wrote them" is undefined after a process start with no `apply()` yet. H1's
  `routePin` removes the need.
- `setApplicationHidden`'s return value must be checked in step 6. On `false`, retry once, then request an `apply()`.
- §2.1: the VIS and the session service need `BIND_VOICE_INTERACTION`. The recognizer is exported with the
  `android.speech.RecognitionService` filter and no permission. "Both services" is ambiguous with three.
- §4: the kid's `requestSync` after Off should wait for the first validated network (a `NetworkCallback`, up to 60 s)
  rather than fire while the radio is still registering.

## Checked, no finding

- **Only our own code can start the route.** `showSession` is the VIS's own call. Third parties reach the session only
  through the system's assist entry points (`showSessionForActiveService` needs `ACCESS_VOICE_INTERACTION_SERVICE`,
  and `KEYCODE_ASSIST` injection needs `INJECT_EVENTS`). Those carry no marker, and §2.1 hides them. The one-use nonce
  is defence in depth, rightly.
- **Assist data:** M8 is measured with both of Android's switches on and from the assist gesture. The probe overrode
  only `onHandleAssist(AssistState)` and still got nothing, so the block is the system's, not the override's.
- **Off and the hardening switch:** consistent. Hardening isn't lifted by the pause or the override, the kid's row reads
  the restriction itself, and the transient add-then-clear can't interleave with `applyHardening` under the monitor.
  `applyHardening` writes the switch's value again on the next `apply()`, so a death between add and clear heals. No
  new standing restriction is added, so the standing rule isn't touched. The default-apps window is a lift, not a
  restriction.
- **A crash mid-route, end state:** `apply()` diffs hidden state against the platform (`AppEnforcer.kt:1395`), so Settings
  is hidden again. Only the delay is M2's finding.
- **The package callbacks:** `enforceCameraOnPackage` (`@Synchronized`) and `enforceOnNewPackage` for Settings' unhide
  either wait for the route's monitor or see `inFlight`. Either way they find Settings hidden again, and neither can
  unhide or pin it.
- **Migration 0146:** `main` ends at 0145 (`abroad`), as §6.3 says. **Bit 64** is free (`QUICK_CONTROL_*` 1-32 on the
  server, `Rows` up to `mobileData` on the launcher), and distinct from `lock_task_features`' bit 64.
- **The heartbeat:** `airplane_mode` isn't in `status_heartbeat.json`'s `heartbeat_fields`, so it's in the hash and any
  change sends a full report, `going_on` included.
- **Older launcher / older server:** as §6.4 says. An older launcher ignores bits above 32 (`controlsSection`), and an
  older intake drops the unknown key.
- **No adb beyond the QR:** the role's on-phone path is Android's page behind the parent PIN, and adb is only a
  runbook shortcut.
- **Navigation:** the kid's part is a plain switch, and nothing in the route depends on gesture or 3-button navigation.

## Revision 3

Checked against `design-98` 54a046bb5 (the diff from 9a9f77fe9 only: §4.1's rule paragraph, §4.2, §4.3, §4.4, §5.2's
new rows, §5.3, 0147, §7.2, §10, build steps 6-8, §12 tests 13-15, D8, D11, D12). Read: `docs/notes/launcher/time-rules.md`,
`lock-and-glance.md`, `hardening.md`; the launcher's `server/ParentPin.kt`, `ParentPinLockout.kt`, `RestrictionsPause.kt`
(`timedWindowActive`, `reapply`), `OfflineOverride.kt`, `timerules/ScreenTime.kt` (`LiftRecord`, `updateLiftRecords`,
`activeRuleLifts`, `liftExpiries`), `TimeRulesRuntime.kt` (`liftRecords`, `snapshot`, `recheck`), `server/MdmSyncWorker.kt`
(the sync's order, `reevaluateLockReasonFromCache`), `server/EnforcementPlan.kt`, `ui/LockActivity.kt`,
`ParentPinCallersTest`; the server's `handlers/lifts.rs` (`record_lift`, `end_lift`, `LiftState::of_running_pause`,
`today`), `time_rules.rs` (`RULE_LIFT_MINUTES`, `valid_lift`), `lock_now.rs` (`twin_lifts`), `parent_pin.rs` (`Entry`),
`migrations/` (ends at 0145).

**Verdict: the airplane-vs-rules part is right; the rule pause needs one more pass before the build.** §4.1/§7.2 leave
no trap: the sheet and Home's line always offer Off, On is never refused for a rule, and the parent's switch still wins.
The sheet's placement is safe (`LockActivity.start` puts the PIN lock back on top, `TimeRuleShown`, so the "···" is never
reachable over LOCKED). The rule pause has the right shape (a `time_lifts` row from the phone, matched by key), but its
PIN gate is split from its window, its choices aren't the PWA's, and it lives in two clock sources (`phone_lifts_v1`
and `lift_records`) whose hand-over leaks both ways: a reboot-ended pause comes back, and an "Avslutt nå" before the
adoption is never honoured. Its start also doesn't re-arm the boundary alarm.

Counts: **0 High, 5 Medium, 8 Low.**

### Q98-R3-M1 Medium - the PIN and the pause are two steps: the window starts outside the gate

`ParentPin.check` starts every PIN window itself, after the `unlocked` event is committed (`windowDuration`/`startWindow`),
and `ParentPinCallersTest` pins "only the gate starts a pause or install mode". §4.4 checks the PIN first (step 1) and
stores the pause later from the choices screen (step 3). So a caller outside the gate writes the pause, the `unlocked`
event goes out with `until = null` (the parent's push can't say "til 21:40"), and a PIN typed and then cancelled reads
as a PIN use with nothing behind it.

Scenario: the parent types the PIN, hands the phone back and the screen times out on the choices screen. The PIN lock
comes over it; the kid unlocks with their own PIN and finds the choices still open, then picks the longest one.

**Fix:** choose first, then the PIN, as Settings' pause does. "Sett på pause" on the choices opens the keypad, and
`check(RULE_PAUSE, pin, plan)` stores the pause in `startWindow` from a `rulePausePlan` re-run at that instant (the rule
may have ended meanwhile), with the event's `until`. Extend `ParentPinCallersTest`: only `ParentPin.kt` writes
`phone_lifts_v1`. The choices and the keypad close on `onStop`/screen-off.

### Q98-R3-M2 Medium - the choices aren't the PWA's, and the server's check is undefined

§4.4 step 2 offers 30 min, 1 h, 2 h and "Til 07:00", and says "these are the PWA's own choices"; §15 and D12 record the
user's decision as "in the PWA's choices". The PWA's are `RULE_LIFT_MINUTES = [15, 30, 60, 120]` (`time_rules.rs:25`,
`valid_lift`), with no "until". "Til 07:00" on an overnight Leggetid is a 10-hour pause from an offline PIN, five times
anything the parent can give from the PWA. With 25 minutes of a rule left, the design offers only "Til", where the PWA
would offer 15 min. The server's "checks the body field by field" names no bound on `minutes`/`until_ms`: reusing
`valid_lift` answers "Til" with a 400, which §4.4 doesn't map to a phone action (retry forever, or drop).

**Fix:** take exactly `RULE_LIFT_MINUTES`, each offered only when it ends by the rule's end. Drop "Til", or put it to the
user as a new choice beyond the PWA's. Server: `minutes ∈ RULE_LIFT_MINUTES`, `until_ms - started_at_ms` equal to
`minutes` within the clock slack, `started_at_ms` not in the future and not older than `minutes`. Any 4xx ends the
retries, like a refusal. Contract rows for the refused bodies.

### Q98-R3-M3 Medium - a pause ended by a reboot comes back once the lift arrives (two clock sources)

`updateLiftRecords` keeps a record only for an id it knows, and **starts a new record now** for any rule lift it hasn't
seen (`ScreenTime.kt:131`). `liftRecords` runs it in every `snapshot` and in `callsBlockedNow`, with whatever policy the
caller holds, and an unreadable `lift_records` also starts every lift afresh. §4.4 hands the pause over by "adoption"
into `lift_records`, but nothing stops `updateLiftRecords` from seeing a `phone_key` lift first.

Scenario: a pause from 21:10 to 23:10, offline. The kid reboots at 21:30, so the pause ends and the rule is back. Then
airplane mode goes off: the queued post goes, the server inserts the row, and the next policy carries lift 77
(`phone_key` K). Any snapshot that sees lift 77 before `reconcilePhoneLifts` does (the call path, a screen-on re-check),
or any reconcile that finds K already pruned from `phone_lifts_v1`, creates a fresh record for this boot. Leggetid is
lifted again until 23:10, which contradicts §4.4 ("a reboot ends it") and test 14. The twin lift for a design-93 copy
(`twin_lifts`, `id = -lift.id`) arrives the same way.

**Fix:** one clock source. `updateLiftRecords` never creates a record for a lift with `phone_key`. Such a lift lifts
only through the `phone_lifts_v1` entry with that key (its own window, `timedWindowActive`), and only while the policy
still carries it after the ack (R3-M4). An unknown key, or a dead entry, lifts nothing. Keep each entry until its
`untilWallMs` has passed by the clock slack, and bound the list. A dead pause still posts, so the parent sees it, but
the phone never revives it. Add an `adopt` row to the contract: "reboot, then the policy with the rows -> not lifted".

### Q98-R3-M4 Medium - "Avslutt nå" before the phone adopts the pause is never honoured

§4.4 marks the pause `acked` only when a policy carries its rows, and "before the ack, a policy without them changes
nothing". The sync fetches the policy **first** and runs the uploads after the report (`MdmSyncWorker`: `fetchPolicy`
at :128, `sendStatus` at :442, `TimeRequests.upload` at :478). So the sync that posts the pause always holds an older
policy, and the adoption waits for the nudge's sync, or the backstop when SSE is down.

Scenario: the PIN-use push reaches the parent, who opens the PWA. The pause is there (the post was accepted), and the
parent taps "Avslutt nå". The phone's next policy lacks the rows, but the pause isn't acked, so nothing changes. The
phone keeps the pause to its end, while the PWA says "Avsluttet 21:14".

**Fix:** ack on the post's `200 accepted` (store `lift_ids`), or on a policy carrying the key, whichever comes first.
After that, a policy whose GET **started** after the ack and lacks the rows ends the pause. Post the queue **before**
the policy GET in the sync, so the same sync adopts it (see R3-L2). A retried post must not revive an ended row: use
`ON CONFLICT DO NOTHING` and return the existing ids. Add an `adopt` row: "accepted, ended in the PWA before any policy
carried it -> ended".

### Q98-R3-M5 Medium - starting the pause doesn't re-arm the boundary alarm; a 30-minute pause runs on while the screen stays on

§4.4 step 3 runs only `reevaluateLockReasonFromCache`. That re-applies enforcement, but nothing in it or in
`AppEnforcer.apply` calls `TimeRuleAlarm.schedule`. Only `recheck` (`TimeRulesRuntime.kt:300`), a sync and
`RestrictionsPause.reapply` re-arm it. The armed alarm is still the boundary from before the pause (the rule's end,
07:00), so the pause's end gets no alarm even though `liftExpiries` would compute it.

Scenario: offline (the common case here), the parent pauses Leggetid for 30 min, and the kid starts a video. With no
sync and no screen-on, nothing re-checks at 21:40, so the rule returns only at the next screen-off/on. The anchor
notice (`AnchorRefresh`) and the glance card also keep the old state.

**Fix:** step 3 (and the reconcile that ends a pause) calls `TimeRulesRuntime.recheck` off the main thread, as
`TimeRequests` and the pause do (`TimeRuleAlarm.schedule`, `AnchorRefresh.request`). `liftExpiries` takes the active
phone pauses. Test 14 gains "pause 30 min offline, keep the screen on: the rule is back at the minute".

### Q98-R3-L1 Low - "its own escalating lockout" reads as a second lockout

§4.4 step 1 says `RULE_PAUSE` gets "its own escalating lockout". In the code there is one state per PIN fingerprint
(`ParentLockoutState`, "lock the parent PIN everywhere"), shared by every entry. That is right, and it gives the kid no
extra tries. A builder reading "its own" could add a per-entry store, which would give five more tries per 15 min.
**Fix:** "the shared lockout (`ParentLockoutState`, one per PIN): tries here count against every entry". Add a
`ParentPinStoreTest` row with four wrong tries at `PIN_LOCK` and a fifth at `RULE_PAUSE` -> locked out.

### Q98-R3-L2 Low - the report right after the post marks the new pause "Avsluttet på telefonen"

`LiftState::of_running_pause` reads a lift as `EndedOnPhone` when a report newer than the row's `created_at` lacks its
id in `lifts_active`. If the post goes "before the status report" in the same sync (after the policy GET), the report
arrives after the insert, without the server id. The PWA then shows the fresh pause as ended on the phone until the
nudge's sync adopts it. **Fix:** as in R3-M4, post before the policy GET, or put the 200's `lift_ids` into
`lifts_active` while the pause is active.

### Q98-R3-L3 Low - a design-93 copy is paused locally by its own id, but the server lifts the rule too

The phone pauses `{-7}` (the copy), and the server maps it to rule 3 and serves lift `rule 3` plus its twin `-7`
(`twin_lifts`). Before adoption a scheduled Leggetid that starts during the pause locks, which is §4.4's "never another
rule that starts during the pause". After adoption it is lifted, so the lock flips at the sync. **Fix:** pick one
meaning. Either the phone pauses the copy's rule as the PWA does (the server returns the rule ids it wrote, and the
entry takes them), or the server writes only the copy's row. Add a `plans` row for it.

### Q98-R3-L4 Low - two rules, "the latest end", and the budget under the rule

- With two rules in force, one `untilWallMs` covers both, and "Til" is the latest end. The earlier rule's id stays
  lifted past its own end, so a later window of that rule the same night is lifted too. "A choice past the rule's end"
  doesn't say which rule. **Fix:** each rule id's own end, `min(choice, that rule's occurrence end)`, not `lock.until`
  (which may chain into a rule that starts later). One server row per rule already allows that.
- With the budget used up as well, pausing the rule leaves the budget's lock, so the screen stays and Home doesn't
  open (§4.4 step 3 says it does). **Fix:** the choices' line says "Skjermtiden er brukt opp" / "Screen time is used
  up" then, or the row isn't offered (D11's reasoning). Add a `sheet` row.

### Q98-R3-L5 Low - the server row's details

- `created_by = 'phone_pin'` goes through `Names::of_username` (the Today list's "by", the activity page). A parent
  named `phone_pin` collides with it. **Fix:** leave `created_by` null, word the sub-line from `phone_key IS NOT NULL`,
  and record a `time_lift` security event with a phone detail, as `record_lift` does.
- `created_at` is the post's time, so an offline pause posted 90 min late reads "made 22:40". **Fix:** store the pause's
  start, bounded by now.
- `stale` leaves no trace beyond the PIN event. **Fix:** store it as an already-over row, so the Today list shows
  "Leggetid på pause 21:10-21:40 (foreldrekoden)", and answer accepted.
- A 404 from a rolled-back server isn't named. **Fix:** retry until `until_ms`, then drop it, as `stale` does.

### Q98-R3-L6 Low - "a PIN hash is cached" is no longer a state

Since design 24b the hash never reaches the cache: the vault holds it, and `ParentPin.status(..).configured` is the
test. **Fix:** the row shows when `configured`. The keypad already handles `untilSync`, `foreignAccess` and the
lockout.

### Q98-R3-L7 Low - English strings missing

Only Norwegian is given for: the sheet's "Foreldrekode", the choices' title ("Sett Leggetid på pause", two rules),
"Sett på pause", "Til 07:00", the PWA's sub-line from the inbox entry ("Regelskjermen") and the Time page's ⓘ.
**Fix:** add the English for each in §4.3, §4.4 and §4.4's PWA side.

### Q98-R3-L8 Low - test 14's clock expectation is wrong, and two cases are missing

"Set the clock back an hour during a pause: it still ends on time" is wrong. `timedWindowActive` ends the pause **at
once** when `untilWallMs - now > durationMs`. Also, Date/time is locked while the time policy has any rule
(`hardening`), so on the emulator only adb can do it. **Fix:** "ends at once, never later". Add to test 14:
- R3-M3's "reboot during the pause, then turn airplane mode off: the rule stays";
- R3-M4's "Avslutt nå before the phone's next sync: the rule is back after it".

### Checked, no finding (revision 3)

- **The sheet under LOCKED:** `LockActivity.start` puts the PIN lock back on top, so the "···" is behind it while
  LOCKED. In Android-keyguard mode (design 59) `LockActivity` isn't shown when locked. A sheet left open over a
  screen-off is the kid's own rows (R3-M1 covers the parent's choices). Back stays swallowed (`LockActivity`'s
  callback) except to close the sheet.
- **Flymodus under a rule:** Settings is never suspended (`setPackagesSuspended` refuses it, M3 of the measurements),
  so the time lock's suspension doesn't break the route. `routePin` and the hide are as in revision 2. The translucent
  voice activity only pauses `LockActivity`, so `onStart`'s re-evaluation doesn't re-front over it.
- **No airplane trap:** with airplane mode on under a rule, the sheet's row (shown whenever airplane mode is on) and
  Home's line both offer Off (§2.3, no role, no Settings). On is never refused for a rule, and "Blokker flymodus"
  still ends and refuses it. The boarding case works under a rule.
- **Brute force:** `check` counts each try before PBKDF2 under one lock with one shared state, so the new entry adds a
  place to type the PIN but no tries (R3-L1 is the wording).
- **Migration numbers:** `main` ends at 0145, so 0146 and 0147 are free. `twin_lifts` clones the row (`..l.clone()`), so
  the twin carries `phone_key` and adoption can match it.
- **PWA:** no new page and no bell. "Avslutt nå" is the existing `end_lift` (rule target, any creator), and it lands
  on its anchor as today.
