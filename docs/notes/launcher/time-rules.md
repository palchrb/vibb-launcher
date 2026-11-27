# Launcher notes: time rules and screen time

Moved from launcher/CLAUDE.md on 2026-11-01.

## Time rules, screen time, lifts, location policy (handy step 6, design `docs/design/06-time-rules.md`, at the monorepo root)

- **Model** (pure, `timerules/TimeRules.kt`, `TimeRulesTest`): `PolicyResponse.timePolicy` = named rules (`kind` school/bedtime/custom, `callsAllowed`, `exemptApps`, `days` = 7 windows Monday first; `start>end` ends next day, `start==end` = 24 h), `dailyBudgetMinutes` (7, null = unlimited) and the parent's `lifts`. Evaluated on local date-times (DST-safe). Fail closed: `days.size != 7` = always active, an out-of-range window = its whole day, unknown kind = custom, a wrong-size budget = 0. No `time_policy` means no rules. `KidModeEnforcer` is now only the bridge (`timePolicyOf`, `lockNow`); `LockReason` gained SCHOOL and RULE (SCREEN_TIME now means the budget is used up).
- **Lock** (`decideTimeLock`): override PIN/pause -> none; active un-lifted rules -> strongest kind (school > bedtime > custom), calls only if all allow, usable apps = intersection of exempt apps; else used-up budget -> SCREEN_TIME with calls and the contacts' messaging apps (`messagingAppPackages`). `computeEnforcementPlan(lockUsableApps, ruleBlocksCalls)` spares and pins usable apps (allowlist still applies) and restricts outgoing calls for a no-calls rule. `TimeLock.key()` (pref `lock_key`) makes a change of rule/apps/calls re-apply and re-render even when the reason stays.
- **Kalender during a rule, the one usable thing after the PIN** (PLAN 2026-11-02, server 0119): `TimeRule.calendarAllowed`
  (default false), `TimeLock.calendarUsable = active.all { it.calendarAllowed }`
  (never under the used-up budget alone), folded into `key()` (`|calendar` only when on, so other keys stay as they
  were); `calendarUsableUnder(lock)`. Kalender's three pages (`DayActivity`, `EventActivity`, `EventEditActivity` - "Ny
  avtale" too, the user's word) pass `DayActivity.redirectIfLocked` when it is (`calendarUsableNow`: the snapshot's lock,
  an error = the time-rule screen), and watch the lock while in front (`LockWatch`, the prefs' `lock_reason`/`lock_key`):
  a rule without Kalender beginning sends them to the time-rule screen. The glance's reminder row (`glanceUsableUnderLock`,
  `GlanceTarget.Day`) reads it, and the time-rule screen has a **Kalender** row (Home's tile glyph and colour,
  `day_title`) after the phone book while `calendarUsable` and `CalendarStore.tileShown()` - Home's own condition for its
  tile, re-rendered on the store's changes (code QA L2) - (`timeLockRows(.., calendarLabel)`,
  `LockRow.Calendar`, opens `DayActivity.intent`; Back comes back). `lockEnd` treats it as a usable app (its time counts
  against the budget, Q-6). **After an unlock** (`PinLockRuntime.afterUnlock`, every unlock of Android's lock)
  the time-rule screen is marked (`LockActivity.markUnlock`, elapsed realtime) as it starts; its next `onResume` takes the
  mark once (`takeUnlockMark`, fresh within `UNLOCK_OPEN_MS` = 10 s and the PIN lock not LOCKED) and, unless the glance
  handed it a fresh row (`GlanceOpener.handOverPending()`, read before `openHandedOver`, by its freshness rule
  `glanceHandOverFresh` - code QA L1), opens the card's only row as its tap would (`unlockOpenRow`: only Kalender or an
  openable app; the phone book never opens by itself - the coordinator's decision on QA N5, a bedtime with calls would
  land every night unlock in it - but counts as a row, so the phone book and one app keep the card; Emergency call never
  counts). "Ny avtale"'s two fields have no selection, share, "process text" or smart-action menu and no text
  classifier (`ui/NoTextActions`, shared with the re-enrol code field; code QA M1), always - under a rule they would
  have opened the dialer or a browser. Back returns to the time-rule screen; Back/Home/other resumes don't
  open it again. Reminder ping stays silent under every rule, Recents unchanged. `TimeRulesTest`, `TimeLockModelTest`,
  `LockGlanceTest`, `DayTaskTest`, `PolicyResponseCompatTest`.
- **"Be om mer tid" on the time-rule screen** (design 100 step 3, `docs/design/100-one-vibb-notification.md` §8): the
  block `lock_ask` in `activity_lock.xml`, directly under the rule card and above the rows' card (14 dp, no card ground
  of its own). Pure `lockAsk(ruleInForce, budgetToday, windowOpen, enrolled, state, note)` (`ui/TimeLockModel.kt`,
  `TimeLockModelTest`): only on the used-up budget's screen - never while the screen names a rule (D4: more minutes
  don't open a rule, and bedtime mustn't invite a request every night), without a budget today, in the pause or the
  override, or before enrolment. Idle: the accent pill "Be om mer tid" (`anchor_ask_more_time`, 52 dp,
  `bg_lock_ask_fill`); time given without a request: its note and "Spør igjen"; Pending/Waiting: the note in a dim pill
  (`kid_lock_pill_dim`, text, not clickable - "Spurt kl. 14:02 · venter på svar", "· sendes når telefonen er på
  nett" from 15 s); Answered: the note ("Pappa svarte nei kl. 14:10", "Mamma ga deg 30 min") and the ringed "Spør
  igjen" (`lock_ask_again`, `bg_lock_ask_outline`) while under the day's three, else "Du har spurt tre ganger i dag."
  (`lock_ask_capped`; `TIME_REQUESTS_A_DAY == 3` is pinned beside it). The screen's note has no time limit (D12,
  `NoteSurface.SCREEN`; push-and-play.md, "The request"). The glue is `timerules/TimeAsk.kt`: `askNow` - the one
  request path, shared with the notice's action (`TimeAskReceiver`) and the Mer tid disc (`ShortcutsRuntime.askForTime`;
  nothing else calls `TimeRequests.ask`), `askStep` deciding again inside - and `lockAskFor(context, snap, ruleInForce)`
  (keeps `OfflineOverride` out of `LockActivity`). `LockActivity` follows the request through `AnchorNotice`'s
  listeners (`addListener` in `onStart`, removed in `onStop`): a changed time part or request state is posted to the
  main `Handler` whatever thread `refresh` ran on (Q100-H1: a Mer tid tap in the shade runs it on the tap thread), also
  while the anchor isn't running (`refreshNow` has no anchor check, Q100-L3); the listener only calls `render()`, which
  re-reads everything. The note's own changes (the 15-second mark, an answer's 30 minutes in the shade) are boundary
  instants (`noteChangeMs`), so `recheck` refreshes the notice and the screen follows. A "yes" unlocks: the screen
  finishes as today. **After the unlock (D6)**: while the block shows a button, `unlockOpenRow(.., askButton =
  shownAsk.hasButton)` opens nothing - two things to do, the screen stays. TalkBack: the pill is a button ("Be om mer
  tid, knapp", `ButtonRole`) only while clickable, and always focusable, so TalkBack's focus stays on it as it turns into
  the dim note after the tap (code QA L3); the three views are polite live regions, their text set only when it changed
  (render runs on many signals; code QA L2). The glance (the PIN lock's
  page) is unchanged: its card stays information only. Not emulator-checked yet: design 100 §11 check 8.
- **Meldinger during a rule** (design 77 §6.7, step 2): reachable where the rule allows calls, as the phone book
  (`messagesUsableUnder`; the used-up budget keeps calls). The time-rule screen has a **Meldinger** row
  (`LockRow.Messages`, after the phone book, while `lock.callsAllowed` and Home shows the tile - `SmsHome.tileShown()`,
  so not while the parent keeps Meldinger off Home; with the parent's name and icon for it, design 82) that never opens by
  itself after an unlock; the three message screens check at resume and watch the lock (`LockWatch` with
  `MessagesScreens.redirectIfLocked`). Messages that come in under a rule without calls are stored and, since
  2026-11-20, not notified until calls are allowed again, then once, quietly (`docs/notes/launcher/calls.md`, "No
  notification under a rule without calls"; the lock glance shows only what a rule allows: lock-and-glance.md, "Rows
  under a time rule"), and the gate refuses a send then (`TIME_RULE`: "Du kan ikke sende meldinger nå").
  Two system helpers stay reachable from a thread under a rule that allows calls (step 3b's code QA N9, and step 2's
  L6): the photo picker behind "+" (read-only, the kid's own gallery; it is a lock-task helper, never suspended) and the
  share sheet behind a picture's "Del" (only allowed apps resolve). The "+" shows only while the compose bar is open,
  so a rule without calls hides it; with calls allowed the parent may accept it or ask for the "+" to follow the rule.
- **A rule's Silent** (PLAN "an alarm ringing during school mode", the user 2026-11-02; server 0121; code QA fix round
  2026-11-02): `TimeRule.silent` (default false). The pure `timerules/RuleSilence.kt` (`RuleSilenceTest`):
  `ruleSilenceFor(lock)` from the lock's rules (the active, un-lifted ones - none under the override or the pause, so
  it is liftable; the used-up budget alone never silences) - no silent rule: none; any silent rule that isn't a
  bedtime: fully silent (no alarms, no media); only silent bedtimes: alarms and media sound (a bedtime's usable apps -
  an audiobook - keep their sound; nothing else plays under the lock). Calls always ring from anyone + repeat callers
  (QA L6, the coordinator's decision): who may call stays the call rules' - managed, `KidCallScreeningService` turns
  the rest away (the callback window's calls get through); unmanaged calls ring as before. **`timerules/ZenRuleRuntime.kt`**
  is the one place that owns a Do Not Disturb: one `AutomaticZenRule` (condition id
  `condition://<package>/rule-silence[/<fresh>]`, a priority-only `ZenPolicy` - system sounds, reminders, events,
  messages, conversations, priority channels off; intercepted notifications stay in the shade without full-screen,
  heads-up, lights or ambient; `TYPE_MANAGED` on Android 15+, plain if refused, an update keeps the type; its
  configuration activity is our PIN-gated Settings; name "Stille tidsregel"/"Silent time rule") switched with
  `setAutomaticZenRuleState`. Off = the condition false: Android hands back the kid's own state (their DND, a mode,
  nothing); nothing is saved or restored, never a manual DND, the ringer mode, master mute, `DISALLOW_ADJUST_VOLUME`
  or a suspended clock (`SoundModeTest` scans for them and allows the zen-rule calls only in this file).
  `follow(context, lock, decidedAtMs)` from `TimeRulesRuntime.recheck` (boundary alarm, screen on, boot, accepted
  policy, process start - so a process that died mid-rule is put right at its next start) and `AppEnforcer.apply`;
  an older decision than the last followed is dropped (`lockIsNewer`, QA L3). Each apply is `zenStep`: skip when
  nothing changed, off, add, on, or off-then-on (a forced re-assert, the first apply of a process - QA L2 - or a
  disabled rule: a user's switch-off stands until the condition has been false). After switching on it reads the
  rule back: one Android keeps disabled (Android 15+ ignores an app's re-enable of a user-disabled rule) is removed
  and added again under a fresh condition id (`zenRuleNeedsReplacing`, QA M2). Of duplicate rules (a lost add's
  retry) an enabled one is kept, the rest removed (`zenRulesKeptFirst`, the revision check's L-R1), and a switch-on
  clears a deactivation heard before (L-R2: its "activated" broadcast may never come); a "deactivated" within
  `OWN_OFF_ECHO_MS` (2 s) of our own switch-off of that rule is its echo, not the kid's (`ownOffEcho`, E7 code QA 5). A failed call leaves "applied"
  unknown, retried at the next follow. **Alarms** (QA H1): `ZenRuleRuntime.silencesAlarms()` (our rule on, alarms
  not allowed) makes the lock's `alarmRingingNow` false and `alarmScreenStep` NONE - an alarm nobody hears is no
  ringing alarm for the lock: no yield, the VoIP ring not held back, the silenced alarm's screen never re-sent (the
  platform suppresses its full-screen intent itself); read live, so a rule that ends mid-ring makes it ringing
  again. Find My Device's ring holds the rule off (`LocateCommands.ring` before the player starts;
  `stopRingAndRestore` puts it back, also from the ring's catch - QA M1). A runtime receiver (not exported) for
  `ACTION_AUTOMATIC_ZEN_RULE_STATUS_CHANGED` (Android 15+'s activated/deactivated of our rule noted) and
  `ACTION_INTERRUPTION_FILTER_CHANGED`, 1.5 s debounced, at most one off-and-on per 10 s (a later one waits): while
  it should be on and our rule isn't in force - gone, disabled, deactivated or off by Android 15+'s
  `getAutomaticZenRuleState` (QA L1: also with the kid's own DND on as well), or the filter ALL (Android 14) - off
  and on again; the kid's own DND is never touched. The VoIP ring (`VoipRinger`) counts our rule's calls before
  Android 16 (`letsCallsRing`; the policy it reads there is the manual one). The device owner has policy access;
  without it nothing happens. `MdmDeviceAdminReceiver.onDisabled` removes the rule. Under the rule the kid's sound
  row is read-only ("Ikke forstyrr er på", design 18) and the lock reminder ping stays quiet. A reboot mid-rule keeps the rule as Android stored it until the first re-check after unlock.
  Device checks: Settings › Modes (Android 15+) - whether the kid can switch a `TYPE_MANAGED` rule (and the plain
  fallback) off, and that the replacement then takes; whether a clock starts its ringing screen other than through
  the full-screen intent under a fully silent rule.
- **School and calls**: the call services and phone book use `CallPolicyStore.effectiveState()` (`withTimeRule`: managed rules act as calls-off - emergency numbers and the callback window only). `TimeRulesStore` holds the enforced time policy in memory (refreshed with `CallPolicyStore`), mirrors only the no-calls windows to DE (`boot_call_blocks`, `v:1`; missing/unreadable = blocked before the first unlock, lifts/override unknown then); after unlock `TimeRulesRuntime.callsBlockedNow` (lifts + override aware) answers. Unmanaged calls: only outgoing is restricted.
- **Screen time** (`ScreenTime.kt` pure, `ScreenTimeTest`; `ScreenTimeTracker`, `TimeRulesRuntime`): counted while interactive, keyguard unlocked, no call (our list or audio mode in call/communication/ringtone) and none of our activities resumed; events only (screen on/off, user present, activity lifecycle, audio-mode listener), a 60 s checkpoint and a one-shot budget timer while counting. Ledger in CE prefs `time_rules_state` (`commit()`): within a boot the trusted clock is anchor + elapsed realtime - set back is ignored, forward moves the date but carries usage; reset only when elapsed time passes midnight or a new boot is on a later date. Budget lifts add minutes once per id to the day first seen; rule lifts use `timedWindowActive` from first sight (`LiftRecord`, a reboot ends them) and stop when the server stops sending them.
- **Timing**: no 60 s polling anywhere. `TimeRuleAlarm` sets one `setExactAndAllowWhileIdle` alarm (`USE_EXACT_ALARM`; inexact fallback) at the next boundary (rule edges over 8 days, midnight with a budget, lift ends, override/pause end) on the non-exported `TimeRuleAlarmReceiver` (security review S-L7; the exported `TimeRuleReceiver` takes only `TIME_SET`, `TIMEZONE_CHANGED`, `BOOT_COMPLETED`) and screen-on/user-present call `TimeRulesRuntime.recheck` (accrue, re-evaluate, show LockActivity if a lock began and no call, re-arm). Every accepted sync posts a re-check too.
- **Policy acceptance**: a response without `time_policy` after one was seen (pref `time_policy_seen`, kept by `resetPreferences`, or the cached policy) is REJECT_SUSPECT; `LastEnforcedPlan.timePolicy` keeps rules + budget (no lifts).
- **Status**: `timeState` (day, used/budget/extra minutes, active rule, calls blocked, lock reason, active lift ids). The server's Device › Time shows it (design 31 step 9). Since design 34 P13 it also carries `zone` (`TimeState.zone: String? = null`), the phone's IANA zone id: `TimeRulesRuntime.snapshot(.., zone = zone())` reads `ZoneId.systemDefault()` once and counts the ledger's day (`ledger`, `loadLedger`) and the rules' local time in it, the `Snapshot` keeps it, and the pure `timeStateOf(snap, lockReason)` sends `snap.zone.id` (qa-34-s13-code L1: never a second read); no capability, no guard (the server's `TimeReport::parse` is the guard). The server shows a rule's end ("Leggetid until 07:00") on the Devices list, the summary and Device › Time only when it equals Phone Setup's zone. `TimeStateZoneTest` pins the encoded JSON against the shared contract `contracts/core/time_status_report.json` (which the server's end-to-end test posts), the report's zone being the snapshot's (a zone that isn't the machine's), and that `snapshot`/`ledger`/`loadLedger`/`timeStateOf` read no zone of their own. On the emulator set Phone Setup's zone to `adb shell getprop persist.sys.timezone` (automatic time zone is forced, so the network's zone wins).
- **Location policy** (`server/LocationPolicies.kt`, `LocationPolicyTest`): `PolicyResponse.locationPolicy` off / on_request / interval (>= 10 min); `null` reads as on_request (`enforcedLocationPolicy`). `LastEnforcedPlan` carries it (security review P-M1, 2026-11-18), so a fallback keeps the parent's choice, zones included; a plan without one (written before that) or `NOTHING_ALLOWED_FALLBACK` is on_request, never an interval. Off never touches LocationManager; FINE/COARSE are always Vibb's (design 114 §7.1: `AppEnforcer.apply` self-grants them, `OWN_LOCATION_PERMISSIONS` - the Wi-Fi page and the sign-in window need them), BACKGROUND follows Locate and the zones; a `locate` command (Update Location on the server's Locate tab) takes a fresh fix and is answered after the status report with the fix's accuracy and age. Security review P-L2/P-L3 (round L4b): only `locate` forces a fix, never `ring`; a fix that had to switch system Location on switches it back off afterwards (in a `finally`; a mark committed before the switch - `location_switched_on_by_fix` - lets the next sync switch it off when the process died mid-fix, `restoreAfterDeadFix`, QA L2) unless the CONFIG_LOCATION hardening switch or the zone watch needs it on (`locationStaysOn`, `locationBackOff`); a cached or last-known fix older than the policy's interval (at least 10 min; "on_request" has its 30 min default) is never reported (`cachedFixMaxAgeMs`, `cachedFixReportable` - the zone watch's still re-send excepted, design 64 Q-3); mode "off" deletes the cached and the kept fix (`LocateCommands.clearCachedFix`). The server already stores an identical re-sent fix once (`zones::store_fix`: no row when one with the same `captured_at` and coordinates exists).
- **Fix round (qa-step6-code.md)**: screen time counts whenever the screen is on and unlocked except while one of our free screens (`FREE_SCREENS`: lock screen, phone book, our in-call screen, Settings - not Home) is in front and not sharing the screen; calls elsewhere count (pure `screenTimeCounts`). `DISALLOW_CREATE_WINDOWS` while a budget is set (`EnforcementPlan.restrictCreateWindows`, lifted by override). Date/time stay locked and automatic time zone is forced whenever the time policy has any rule or budget, override or not, calls managed or not. An unreadable ledger fails closed (`unreadableLedger`: today used up, delivered budget lifts counted as applied, `time_state.ledger_unreadable`). Location "off" switches system location off (`systemLocationTarget`; CONFIG_LOCATION then keeps it off). `callsBlockedFor` is the pure per-source call-path answer (`TimeRulesStoreTest`). A ledger that can't be saved (storage full, 2026-11-25, the owner's choice) leaves the phone usable: SharedPreferences keeps the new value in memory when `commit()` fails, so the count keeps enforcing while the process runs, and a restart goes back to the last saved ledger, losing the time used since storage filled (accepted); `saveLedger` and `storeAcceptedPolicy` record their writes in `StorageWrites` (`StorageWritesTest`), and `storageFull()` is true while either's last write failed and the report sends `time_state.storage_full` (left out while false; `time_status_report.json` carries it). The server pushes "lagringen er full" at most once a day (event `storage_full`, its own switch) and shows one orange line on the summary's Attention and Device › Time, the fix behind the (i).
- **Not device-tested**: exact-alarm delivery in Doze, audio-mode call detection on the Jelly Star, the BFU call block (checklist in 06-time-rules.md).

## A rule turned on now: `until_ms` and `card_first` (design 93, `docs/design/93-lock-now-quick-actions.md` §9)

- **What the server sends**: a rule the parent turns on now ("Slå på nå") is one more entry in `time_policy.rules`, a
  copy of the rule with one window on today's weekday, a negative `id`, `until_ms` (its end as epoch ms) and
  `card_first: true`; every scheduled rule carries `until_ms: null` and `card_first: false`. A pause of that rule comes
  with a twin lift for the copy (`rule_id` the copy's). `TimeRule.untilMs` / `cardFirst`; the cache keeps them
  (`PolicyGate` drops only the lifts). Contract: `contracts/core/lock_now.json`, `LockNowTest`. Since design 123 the
  copy may be of a rule without days (served as itself too, never locking) or of a rule switched off for the phone
  (not served itself): `policy_on_demand`, `policy_off_rule`; no launcher code changed for it. The time-rule screen's
  title names such a copy by itself beside another rule in force (`pauseTitleRules`, design 123 QA L2).
- **`TimePolicy.inForceAt(nowMs)`** (`TimeRules.kt`): the policy without the rules whose `untilMs` is at or before now.
  `TimeRulesRuntime` applies it where it takes the policy for a decision - `snapshot()` (so `decideTimeLock`, the card,
  the silence, the suspension and the report all see the same rules), `callsBlockedNow()` and `nextBoundary()`, which
  also takes every future `untilMs` as a boundary (`untilInstants`): the lock is decided again at that instant even when
  the window's local end moved with a zone change. A copy cached a week offline never locks again. The phone's wall
  clock decides, as for every window. The boot call blocks keep the copy's window (stricter, only before the first
  unlock after a reboot).
- **`cardRule`** (`lock/RuleCard.kt`): among the lock's rules, the first with `cardFirst` whose own occurrence end is a
  certain unlock by `lockEnd` (which now takes the end to test, `end`, defaulting to `lock.until`); else the strongest
  rule with the lock's end, as before. `cardPick(snap)` feeds the glance's card (`ruleCardOf`), the time-rule screen's
  title and end (`LockActivity`) and the report's `active_rule_id`/`active_rule_name` (`timeStateOf`), so the card, the
  screen and the PWA's badge name the same rule. The lock itself - which rules, their intersection, the `LockReason`,
  calls, usable apps, Kalender, silence - is `decideTimeLock`'s, unchanged.

## Foreldrekode: the parent code's pause of the rule in force (design 98 §4.4)

From the time-rule screen's sheet: `RulePauseActivity` (the PWA's lengths only), then `ParentPin.check(RULE_PAUSE, ..,
minutes)`, which plans the pause again (`rulePausePlan`) and stores it in `phone_lifts_v1` (`PhonePauses.addFromGate`,
the only writer). A pause lifts only through its own entry and window (`phonePauseLifts`, merged in
`TimeRulesRuntime.ruleLifts`); a lift with `phone_key` never gets a `LiftRecord`. The queue posts each pause at the
start of every sync, before the policy GET; a policy after the ack without a rule's row (key and rule id, the twin's
negative id too) ends that rule's pause - "Avslutt nå" on one rule of two ends that one alone (code QA M1). An entry is
pruned only once posted (or 7 days old), so an offline pause still reaches the parent (L5). `rule_pause.json`.

## The clock during a rule (design 105, `docs/design/105-clock-during-a-rule.md`)

The bug: during Skole the kid opened Fossify Clock from Recents on the mode screen and kept it, because the allowed clocks
were pinned in the kiosk all rule long (bug 2026-11-02). The user's decision: the clock is usable during a rule only when
the parent ticks it on that rule (Leggetid too; no default tick).

- **The plan** (`computeEnforcementPlan`, `ruleLocked = lock.rules.isNotEmpty()` from `AppEnforcer`): while a rule
  locks with the kiosk on, the allowed clock apps not among the lock's usable apps leave `kioskPackages` and go to
  `EnforcementPlan.ruleClockPins`. They stay spared from suspension, so alarms ring. A ticked clock stays pinned; the
  used-up budget alone, the pause, the override and Foreldrekode are unchanged.
- **The chrome** (`lockTaskWhileLocked(ruleClockPins, dark)`): the kiosk-on LOCKED row adds the pins always, the
  not-LOCKED rows (the Flymodus route's included) only while `dark` (`ruleClocksIn`: Android's keyguard locked, or
  `clockDark` - the screen off, lit for less than `ALARM_SCREEN_SETTLE_MS`, or interactive with no screen-on noted
  since the last screen-off); the SOS-narrowed row and the kiosk off are unchanged. So the ringing screen still comes
  over the glance (LOCKED) and in the dark; unlocked and lit lock task refuses every way into the clock (Recents,
  a notification's content tap, `SHOW_ALARMS` - with the block bit on), and removing the package clears its task
  (AOSP `updateLockTaskPackages`, lock task LOCKED). `PinLockRuntime`'s screen receiver tells `LockTaskChrome.screenChanged`
  and runs a pass at a screen-off (unless LOCKED), one `ALARM_SCREEN_SETTLE_MS` after every screen-on and one at
  USER_PRESENT (each only while a rule keeps clocks out); `dark` and LOCKED
  are read under the monitor in every pass (`PowerManager.isInteractive`). Before the first apply the last pins come
  from the pref `rule_clock_pins` (`fallbackKioskPackages`: out unlocked and lit, in LOCKED or dark).
- **A ring while unlocked**: the time-rule screen's alarm row ("Alarmen ringer") shows the ringing notification's
  own non-activity actions as pills under it, one child of the card (two side by side, three or more stacked,
  ellipsized), with their titles; the screen redraws at every chrome pass (`alarmRowActions`; Fossify: Slumre a service, Avvis a
  broadcast), kept by `AlarmNoticeReader` next to the full-screen intent and sent through `ClockActions.stop` (a
  broadcast or a service, background starts denied) after the listener confirms the key is still active. While the
  clock is out of the list the screen never sends or opens its ringing screen (`pinnedClocks()`), and
  `lockRowPackages` never offers it without the listener. A clock with only activity actions gets no row: the shade,
  its own silence-after, or two power presses (LOCKED pins it and the glance sends the ringing screen).
- **Not device-tested**: the Jelly's system clock's action types (`dumpsys notification --noredact`), and Android 16's
  lock-task clearing on the Jelly (a `dumpsys` look after the release). Emulator: `docs/testing/emulator.md` §6au.

## The decisions' clock (2026-11-24, branch `trusted-time`)

Decisions use the server's time. `server/TrustedClock.kt` (pure rules in `TrustedClockPlan.kt`, `TrustedClockTest` over
`clock_rules.json`) is the one clock the time rules, screen time, the lifts and pauses, Play's update window, the
parent-PIN lockout and the boundary alarm read: `TimeRulesRuntime.clocks()` is `TrustedClock.clocks()`, whose `wallMs` is
the trusted time and `trust` how it is known (`server`, `gnss`, `carried`, `none`).
- **Anchors.** A server answer's `Date` (2xx or 304 through `createMdmApi`'s client, over a connection that passes the
  certificate check, the tailnet, or TLS Android validated while the check isn't enforced - a server whose issuer isn't
  in the built-in set, or the parent's off -; a debug build's plain http too), at the middle of the round trip plus half
  a second; none after a round trip over 30 s or with `Vibb-Clock: off`. Another checked time when the phone has one (a
  made-up location never counts); a server answer far from it in the same boot isn't taken. A stronger or newer anchor
  replaces the one in force.
- **Within a boot** the time is the anchor plus elapsed realtime: a wall clock set in the boot moves nothing.
- **Across a reboot** (another `BOOT_COUNT`, or elapsed behind the anchor) the first read (`Application.onCreate`, direct
  boot: device-protected prefs `trusted_clock`) makes a `carried` anchor: `max(wall - last offset, last checkpoint)`. The
  checkpoint (trusted time and the wall clock's offset) is kept at the snapshots, at most once a minute, and at
  `ACTION_SHUTDOWN`; while a carried time is open (below) a checkpoint keeps the last offset, so a set that lands
  before its `TIME_SET` is still seen as a move (gate 6bf (j)). A phone never anchored (before its first sync) reads the wall clock. When the wall clock at that first
  read is behind the checkpoint (a clock that lost its time while off), the boot's first forward wall-clock set is taken
  once (`timeSetAnchor`), so automatic time putting it right isn't ignored.
- **The ledger** (`ScreenTime.observe` with a trust other than `none`) follows the trusted day; a reset on a `carried` time
  keeps the day it replaced (`previous`), and the next `server`/`gnss` time that puts now in that day brings it back with
  its minutes and those used since. A reboot offline never locks the kid out; a clock moved before it never refills the
  day for good.
- **The alarm** (`TimeRuleAlarm`) is `ELAPSED_REALTIME_WAKEUP`: each edge converted with its own clock, the minimum taken
  after, a second late, floored to now plus a second (`alarmDeadline`).
- **Windows on the wall clock** bounded by elapsed realtime stay as they were (the override, the pause, install mode: they
  fail closed); their ends are moved onto the trusted clock for the alarm. The callback window, the last emergency call
  and Flymodus's emergency check are kept on Android's clock because the call log is stamped by it; a package's install
  time likewise (the update fence). The motion sensor's distrust, the zone series' daily budget, the refused login hosts
  and the legacy lock's backoff are on the trusted clock. `TrustedClockScanTest` pins every wall-clock read left in
  `timerules/ calls/ zones/ play/ assistant/ logins/ lock/` and the server files that decide by its line, and fails on a
  new or changed one.
- **The report** (`time_state`): `clock_skew_ms` (wall minus trusted: 0 within five minutes, then five-minute steps that
  change only a minute past a step's edge, so the heartbeat's hash stays), `clock_trust`, `unanchored_ms` (since the last
  `server`/`gnss` anchor, whole hours). `TIME_SET` re-checks the offset and asks for a sync when it moved over five minutes.
- **Fixes** carry `age_ms` from elapsed realtime (`fixAgeMs`; unknown across a reboot): the server stores the fix at the
  receipt minus it. The cached and kept fixes keep their elapsed time and boot.
- **Vibb's clock faces** (`ClockFormat.bindTrusted`: the time-rule screen's and the glance's time and date) show the trusted
  time as literal text while the offset is over five minutes, refreshed at each minute and bound again (the date too)
  at start and when Android's clock or zone is set; back within five minutes, the system's pattern again.
- **Not done:** Android's own clock is never set (the status bar and other apps keep the wall clock).

## App groups (design 139 part A, `docs/design/139-app-groups.md`)

- **The fold** (`timerules/AppGroups.kt`, pure, `AppGroupsTest`): §4.1 revision 5 - per package off / on / on-paused,
  the PAUSED class rule, the kill rule (`KILL_PAIR_MS`), pending STOPPEDs across reads, the device state as a state,
  the boot-count reset, the read window (`SETTLE_MS`), the first fold's 3 h lookback with the count off, the backward
  clock step's skip by key, the elapsed cap, the trusted day. The ledger (`ScreenTimeLedger`) keeps `groupUsedMs`,
  `groupExtraMinutes`, `appliedGroupLifts` and the `foldPoint` (with its `carry`), which runs on across midnight.
- **The glue** (`timerules/AppGroupUsage.kt`): one `queryEvents` in flight on its own thread, applied on the main thread
  through `TimeRulesRuntime.applyGroupFold` only while the ledger's point is the one the read started from. The access
  (`UsageAccess`, the app-op the usage service's way, `@Suppress("DEPRECATION")`) is read before and after each query
  (`readOutcome`); a read that throws closes the groups until one works (`readFailed`). Debug builds re-read each
  window at the next fold and log `late event`.
- **When** (`ScreenTimeTracker`): its own gate (interactive, keyguard down) before `appInUse`; on every `update` and the
  60 s checkpoint, one fold `SETTLE_MS` + 0.5 s after the screen off, and `groupRunsOut` at the least time left + 1 s.
  `afterFold` re-checks when the closed set changed, else re-arms the timer and the Q4 notice ("Spill: 3 min igjen",
  `AnchorNotice.refreshGroups`). The setup hold folds for state only.
- **The decision** (`timerules/AppGroupPolicy.kt`, `AppGroupStatesTest`): `groupStates` (open, no limit, used up, not
  today, can't count), `groupClosedApps`, group lifts once per id. `TimeRulesRuntime.snapshot` puts the closed apps on
  the lock (`TimeLock.groupClosed`, in its `key()`, so a change re-applies), less the live VoIP call's own package (D7;
  `VoipCalls`' pinned change re-checks), and out of `usableApps` (D8).
- **The plan** (`computeEnforcementPlan`'s `groupClosedApps`, `groupsSet`): suspended never hidden, out of the pins and
  the usable apps, never `neverRestrict`/alarms/ours; `groupOnly` for the dim; a group sets the date/time lock and
  `DISALLOW_CREATE_WINDOWS`. After an apply a group that just closed over the app on screen at the last fold brings
  Home (`AppGroupUsage.afterApply`).
- **What the kid sees** (`timerules/AppGroupTiles.kt`): `AppFilter` keeps a group-only app, Home's tile and the list's
  icon dim it, and `AppAction` opens the group's sheet (`sheet_app_group.xml`) instead of launching it.
- **The setup step** `usage_access` (`SetupStep.USAGE_ACCESS`, `StepWhen.APP_GROUPS`): Vibb One's own page with
  `package:`, then the list. The manifest declares `PACKAGE_USAGE_STATS` (`tools:ignore="ProtectedPermissions"`).

