# Launcher notes: time rules and screen time

Moved from launcher/CLAUDE.md on 2026-11-01.

## Time rules, screen time, lifts, location policy (handy step 6, design `docs/design/06-time-rules.md`, at the monorepo root)

- **Model** (pure, `timerules/TimeRules.kt`, `TimeRulesTest`): `PolicyResponse.timePolicy` = named rules (`kind` school/bedtime/custom, `callsAllowed`, `exemptApps`, `days` = 7 windows Monday first; `start>end` ends next day, `start==end` = 24 h), `dailyBudgetMinutes` (7, null = unlimited) and the parent's `lifts`. Evaluated on local date-times (DST-safe). Fail closed: `days.size != 7` = always active, an out-of-range window = its whole day, unknown kind = custom, a wrong-size budget = 0. An older server's weekday/weekend/bedtime windows are converted by `legacyTimePolicy` (same algorithm as the server's migration; checked minute-by-minute against the old `KidModeEnforcer` over a week). `KidModeEnforcer` is now only the bridge (`timePolicyOf`, `lockNow`); `LockReason` gained SCHOOL and RULE (SCREEN_TIME now means the budget is used up).
- **Lock** (`decideTimeLock`): override PIN/pause -> none; active un-lifted rules -> strongest kind (school > bedtime > custom), calls only if all allow, usable apps = intersection of exempt apps; else used-up budget -> SCREEN_TIME with calls and the contacts' messaging apps (`messagingAppPackages`). `computeEnforcementPlan(lockUsableApps, ruleBlocksCalls)` spares and pins usable apps (allowlist still applies) and restricts outgoing calls for a no-calls rule. `TimeLock.key()` (pref `lock_key`) makes a change of rule/apps/calls re-apply and re-render even when the reason stays.
- **Kalender during a rule, the one usable thing after the PIN** (PLAN 2026-11-02, server 0119): `TimeRule.calendarAllowed`
  (default false: an older server keeps Kalender closed), `TimeLock.calendarUsable = active.all { it.calendarAllowed }`
  (never under the used-up budget alone), folded into `key()` (`|calendar` only when on, so other keys stay as they
  were); `calendarUsableUnder(lock)`. Kalender's three pages (`DayActivity`, `EventActivity`, `EventEditActivity` - "Ny
  avtale" too, the user's word) pass `DayActivity.redirectIfLocked` when it is (`calendarUsableNow`: the snapshot's lock,
  an error = the time-rule screen), and watch the lock while in front (`LockWatch`, the prefs' `lock_reason`/`lock_key`):
  a rule without Kalender beginning sends them to the time-rule screen. The glance's reminder row (`glanceUsableUnderLock`,
  `GlanceTarget.Day`) reads it, and the time-rule screen has a **Kalender** row (Home's tile glyph and colour,
  `day_title`) after the phone book while `calendarUsable` and `CalendarStore.tileShown()` - Home's own condition for its
  tile, re-rendered on the store's changes (code QA L2) - (`timeLockRows(.., calendarLabel)`,
  `LockRow.Calendar`, opens `DayActivity.intent`; Back comes back). `lockEnd` treats it as a usable app (its time counts
  against the budget, Q-6). **After an unlock** (`PinLockRuntime.afterUnlock`, own mode and every Android-mode unlock)
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
- **Meldinger during a rule** (design 77 §6.7, step 2): reachable where the rule allows calls, as the phone book
  (`messagesUsableUnder`; the used-up budget keeps calls). The time-rule screen has a **Meldinger** row
  (`LockRow.Messages`, after the phone book, while `lock.callsAllowed` and Home shows the tile - `SmsHome.tileShown()`,
  so not while the parent keeps Meldinger off Home; with the parent's name and icon for it, design 82) that never opens by
  itself after an unlock; the three message screens check at resume and watch the lock (`LockWatch` with
  `MessagesScreens.redirectIfLocked`). Messages that come in under a rule without calls are stored and notified
  silently (step 1), and the gate refuses a send then (`TIME_RULE`: "Du kan ikke sende meldinger nå").
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
  row is read-only ("Ikke forstyrr er på", design 18) and the lock reminder ping stays quiet. Capability
  `rule_silence_v1`. A reboot mid-rule keeps the rule as Android stored it until the first re-check after unlock.
  Device checks: Settings › Modes (Android 15+) - whether the kid can switch a `TYPE_MANAGED` rule (and the plain
  fallback) off, and that the replacement then takes; whether a clock starts its ringing screen other than through
  the full-screen intent under a fully silent rule.
- **School and calls**: the call services and phone book use `CallPolicyStore.effectiveState()` (`withTimeRule`: managed rules act as calls-off - emergency numbers and the callback window only). `TimeRulesStore` holds the enforced time policy in memory (refreshed with `CallPolicyStore`), mirrors only the no-calls windows to DE (`boot_call_blocks`, `v:1`; missing/unreadable = blocked before the first unlock, lifts/override unknown then); after unlock `TimeRulesRuntime.callsBlockedNow` (lifts + override aware) answers. Unmanaged calls: only outgoing is restricted.
- **Screen time** (`ScreenTime.kt` pure, `ScreenTimeTest`; `ScreenTimeTracker`, `TimeRulesRuntime`): counted while interactive, keyguard unlocked, no call (our list or audio mode in call/communication/ringtone) and none of our activities resumed; events only (screen on/off, user present, activity lifecycle, audio-mode listener), a 60 s checkpoint and a one-shot budget timer while counting. Ledger in CE prefs `time_rules_state` (`commit()`): within a boot the trusted clock is anchor + elapsed realtime - set back is ignored, forward moves the date but carries usage; reset only when elapsed time passes midnight or a new boot is on a later date. Budget lifts add minutes once per id to the day first seen; rule lifts use `timedWindowActive` from first sight (`LiftRecord`, a reboot ends them) and stop when the server stops sending them.
- **Timing**: no 60 s polling anywhere. `TimeRuleAlarm` sets one `setExactAndAllowWhileIdle` alarm (`USE_EXACT_ALARM`; inexact fallback) at the next boundary (rule edges over 8 days, midnight with a budget, lift ends, override/pause end) on the non-exported `TimeRuleAlarmReceiver` (security review S-L7; the exported `TimeRuleReceiver` takes only `TIME_SET`, `TIMEZONE_CHANGED`, `BOOT_COMPLETED`) and screen-on/user-present call `TimeRulesRuntime.recheck` (accrue, re-evaluate, show LockActivity if a lock began and no call, re-arm). Every accepted sync posts a re-check too.
- **Policy acceptance**: a response without `time_policy` after one was seen (pref `time_policy_seen`, kept by `resetPreferences`, or the cached policy) is REJECT_SUSPECT; `LastEnforcedPlan.timePolicy` keeps rules + budget (no lifts).
- **Status**: `timeState` (day, used/budget/extra minutes, active rule, calls blocked, lock reason, active lift ids) and capability `time_rules_v1`. The server's Device › Time shows it (design 31 step 9). Since design 34 P13 it also carries `zone` (`TimeState.zone: String? = null`), the phone's IANA zone id: `TimeRulesRuntime.snapshot(.., zone = zone())` reads `ZoneId.systemDefault()` once and counts the ledger's day (`ledger`, `loadLedger`) and the rules' local time in it, the `Snapshot` keeps it, and the pure `timeStateOf(snap, lockReason)` sends `snap.zone.id` (qa-34-s13-code L1: never a second read); no capability, no guard (the server's `TimeReport::parse` is the guard). The server shows a rule's end ("Leggetid until 07:00") on the Devices list, the summary and Device › Time only when it equals Phone Setup's zone. `TimeStateZoneTest` pins the encoded JSON against the shared contract `time_status_report.json` (test resources, byte-identical with `../server/testdata/`, which the server's end-to-end test posts; both sides compare the copies), the report's zone being the snapshot's (a zone that isn't the machine's), and that `snapshot`/`ledger`/`loadLedger`/`timeStateOf` read no zone of their own. On the emulator set Phone Setup's zone to `adb shell getprop persist.sys.timezone` (automatic time zone is forced, so the network's zone wins).
- **Location policy** (`server/LocationPolicies.kt`, `LocationPolicyTest`): `PolicyResponse.locationPolicy` off / on_request / interval (>= 10 min); `null` (older server) keeps the old 10-minute throttle. Off never touches LocationManager or the permissions; a `locate` command (Update Location on the server's Locate tab) takes a fresh fix and is answered after the status report with the fix's accuracy and age. Security review P-L2/P-L3 (round L4b): only `locate` forces a fix, never `ring`; a fix that had to switch system Location on switches it back off afterwards (in a `finally`; a mark committed before the switch - `location_switched_on_by_fix` - lets the next sync switch it off when the process died mid-fix, `restoreAfterDeadFix`, QA L2) unless the CONFIG_LOCATION hardening switch or the zone watch needs it on (`locationStaysOn`, `locationBackOff`); a cached or last-known fix older than the policy's interval (at least 10 min; "on_request" has its 30 min default) is never reported (`cachedFixMaxAgeMs`, `cachedFixReportable` - the zone watch's still re-send excepted, design 64 Q-3); mode "off" deletes the cached and the kept fix (`LocateCommands.clearCachedFix`). The server already stores an identical re-sent fix once (`zones::store_fix`: no row when one with the same `captured_at` and coordinates exists).
- **Fix round (qa-step6-code.md)**: screen time counts whenever the screen is on and unlocked except while one of our free screens (`FREE_SCREENS`: lock screen, phone book, our in-call screen, Settings - not Home) is in front and not sharing the screen; calls elsewhere count (pure `screenTimeCounts`). `DISALLOW_CREATE_WINDOWS` while a budget is set (`EnforcementPlan.restrictCreateWindows`, lifted by override). Date/time stay locked and automatic time zone is forced whenever the time policy has any rule or budget, override or not, calls managed or not. An unreadable ledger fails closed (`unreadableLedger`: today used up, delivered budget lifts counted as applied, `time_state.ledger_unreadable`). Location "off" switches system location off (`systemLocationTarget`; CONFIG_LOCATION then keeps it off). `callsBlockedFor` is the pure per-source call-path answer (`TimeRulesStoreTest`).
- **Not device-tested**: exact-alarm delivery in Doze, audio-mode call detection on the Jelly Star, the BFU call block (checklist in 06-time-rules.md).
