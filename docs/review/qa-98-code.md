# Code QA: branch `design-98` (Flymodus, the rule screen's "···" sheet, Foreldrekode)

Reviewed 2026-11-15, read-only: `199508777..c120b2301` (16 commits; 199508777 is the merge of main into the branch).
Spec: `docs/design/98-airplane-mode-assistant.md` revision 4 with §13's decisions D1-D13; the design QAs in
`docs/review/qa-98-design.md`; the builder's run in `docs/testing/emulator.md` §6am. No build, no tests, no emulator.
"Verified" = read in the code and traced end to end; "inferred" = depends on Android behaviour not run here.

**Result: 0 High, 1 Medium, 5 Low.**

## What was checked and holds

Lock safety of the route (focus 1), verified:

- Every write of the lock-task list goes through `LockTaskChrome` (`routeWindow` sets the `routePin` input and runs one
  `applyNow`; the route never calls `setLockTaskPackages`). `lockTaskWhileLocked` adds Settings and disables the status
  bar only on an unlocked kiosk pass (`LockTaskHelpers.kt:203-204`); a LOCKED pass, a SOS-narrowed pass and the kiosk-off
  LOCKED pass all ignore it. Closing writes the list first (Settings out) and then the status bar (`chromeWriteOrder`).
- Steps 4-7 run under `synchronized(AppEnforcer)` (the same monitor as `@Synchronized apply`); the lock order is always
  AppEnforcer then LockTaskChrome, as in `apply` -> `applyPlan`, so no new deadlock. `kioskOn` is read under the monitor.
- `runRouteWindow`'s `finally` unpins, re-hides (with the return value checked, a retry, then an `apply()`), hides the
  session and clears the DE marker last; every `LockTaskChrome` write path catches its own exceptions, so `routeWindow`
  can't throw half-way and leave `routePin` set.
- The DE marker is written with `commit()` before the pin, read at `initRest` (own thread) and at the top of every
  `performMdmSync`; the put-back runs `apply()`, whose package loop re-reads `isApplicationHidden` per package, so Settings
  is hidden again and the plan's list replaces the platform's.
- The session acts only on the per-request 128-bit nonce, once; a late or foreign `onShow` hides. The 10 s cool-down,
  the single `running` flag and the busy rows bound repeats. A `plan == null` pass refuses the route (`timeout`).
- Off (`DISALLOW_AIRPLANE_MODE` set then cleared in a `finally`) can only leave the phone *more* locked on a crash.
- The default-apps lift: `lockDefaultAppsFor(..) && !assistantPickOpen` in both writers; `assistantPickOpen` needs the
  stored window active by `BootClock` **and** the pause/override, so a reboot or the pause's end closes it at the next
  apply; the closing apply re-takes dialer/SMS, re-seats HOME and compares the snapshot. See L1 for the one gap.

Foreldrekode (focus 2), verified: only `ParentPin.check(RULE_PAUSE, ..)` stores (`PhonePauses.addFromGate`, after a
MATCH, under the shared `ParentLockoutState`); the plan is re-run at the gate; only rules in force are paused, each to its
own end, a copy with its `copy_of`; `updateLiftRecords`/`activeRuleLifts`/`activeLiftIds` skip `phone_key` lifts, so a
reboot, the clock set back or a pruned entry lifts nothing whatever the policy says; `timedWindowActive` never ends a
window later; the post runs before the policy GET and the GET's start is taken after it; the alarm gets the pause's end
through `PhonePauses.expiries`; `phone_lifts_v1` holds no PIN material. See M1 for the one way a pause outlives the
parent's choice.

Server (focus 3), verified: the route is inside `authed_routes` (bearer) with a 2 KiB limit; the checks match §4.4.5;
select-then-insert inside `BEGIN IMMEDIATE` **plus** `ON CONFLICT (device_id, phone_key, rule_id) WHERE phone_key IS NOT
NULL DO NOTHING` is correct and idempotent (a retry inserts nothing, answers the same ids, never touches
`ended_early_at`, writes no second security line); `twin_lifts` copies `phone_key` onto the copy's twin (`..l.clone()`),
so the launcher never turns a twin into a `LiftRecord`; migrations 0146/0147 follow 0145; bit 64 is in
`DEFAULT_QUICK_CONTROLS` and kept by the Home form unless `airplane_row` is posted; every PWA line, the Oppsett card,
About This Phone and the Home row are behind `airplane_mode_v1`; `clean` matches §5.2.

Contracts (focus 4), verified: `airplane_mode.json`, `rule_pause.json` and `lock_now.json` are byte-identical in both
copies; both workflows' path filters and the root `CLAUDE.md` list the two new files; `rule_pause`, `lifts[].phone_key`
and `rules[].copy_of` are pinned in `policy_json_keys_snapshot` and `PolicyResponseCompatTest`; the launcher-only rows
(`phone_answers`, `plans`, `adopt`, `rows`, `sheet`, `home_line`) are read by the launcher's tests.

The exported edge receiver (focus 5): `Intent.ACTION_AIRPLANE_MODE_CHANGED` is `android.intent.action.AIRPLANE_MODE`,
declared `<protected-broadcast>` in the framework manifest on every release this app supports (Android 14-16 included),
so only system-privileged senders (here `com.android.phone`, which `RECEIVER_NOT_EXPORTED` filters out, as the builder
measured) can send it. Even a spoofed edge would only write a period and count down the route's latch; `await()` re-reads
`AIRPLANE_MODE_ON` itself. No finding.

i18n and the PWA (focus 6), verified: every new launcher string has en and nb (only `lock_more_glyph` is in
`donottranslate.xml`); every new server key is in both `en.json` and `nb.json`; no hard-coded UI text in the new layouts
or templates; no "Spotify"; every new form lands on its anchor (`#quick-controls`, `#oppsett`, and the not-enrolled
branch of the summary has its own `#oppsett` for the QR page's button); no new phone sub-page, so no bell.

## Medium

### M1. "Avslutt nå" on one rule of a two-rule phone pause doesn't end it on the phone

`phonePauseLifts` (`launcher/.../timerules/RulePause.kt:133-144`) decides per **key**: once acked, the entry keeps every
one of its rules lifted while the newest policy carries *any* lift with that key. `PausePolicyView`
(`RulePause.kt:75`, built in `PhonePauses.policyAccepted`, `PhonePauses.kt:103-109`) stores only the set of keys, so
the phone can't tell which of the pause's rows the parent ended. The server writes one `time_lifts` row per rule, and the
PWA's "Avslutt nå" (`handlers/lifts.rs:191`, `end_lift`) ends one row at a time.

Scenario: Lekser (20:00-21:30) and Leggetid (21:00-07:00) are both in force at 21:10. Pappa uses the parent code on
Emma's phone: "Sett Lekser og Leggetid på pause", 1 time. The post lands, rows A (Lekser, to 21:30) and B (Leggetid, to
22:10), both with key K. At 21:20 Mamma taps "Avslutt nå" on Leggetid in the PWA; the server nudges, the phone syncs,
the policy holds A (key K) but not B. `phonePauseLifts` sees K -> Leggetid stays paused until 22:10. The PWA shows
Leggetid's pause as ended (and the report's `lifts_active` no longer lists B, since `activeLiftIds` matches per rule), so
the parent believes the rule is back while the phone stays open for 50 more minutes. After 21:30 A expires on the server,
but nothing nudges the phone then, so B's pause can run to its full end.

Not caught by the contract: every `adopt` row has a single rule.

Fix: keep `(key, rule_id)` pairs in `PausePolicyView` (the twin carries the copy's negative id and the base row the copied
id, and the entry holds both, so a direct per-id match works), and after the ack lift a rule only while the policy still
carries a lift with that key **for that rule id** (before the ack, or with no policy after it, as now). Add an `adopt`
row "two rules, one ended in the app -> only the other lifted" in both copies of `rule_pause.json`.

## Low

### L1. The default-apps window and the Wi-Fi step share one deadline alarm, so one deadline overwrites the other

`AssistantSetup.scheduleDeadline` (`launcher/.../assistant/AssistantSetup.kt:254-263`) always uses
`PendingIntent.getBroadcast(context, 0, Intent(..SetupDeadlineReceiver).setAction(ACTION_SETUP_DEADLINE), ..)`, and
`setExactAndAllowWhileIdle` with an equal PendingIntent replaces the earlier alarm. `openPickWindow` and `startWifiStep`
both call it.

Scenario: in the PIN-gated Settings during Åpne for oppsett, the parent taps Samtalefilter at 18:00 (window to 18:05),
backs out of Android's role dialog, and taps "Wi-Fi i flymodus" at 18:03 (alarm moved to 18:08). Nothing ends the
default-apps window at 18:05: `DISALLOW_CONFIG_DEFAULT_APPS` stays lifted until the next `apply()` (at worst the 18:08
alarm), although `assistantPickOpen` is false from 18:05. In the other order the Wi-Fi step's own end moves later, so
airplane mode stays on past its 5 minutes. Bounded by the pause and by any other apply.

Fix: one request code per deadline (or schedule the earlier of the open deadlines each time).

### L2. A kid's turn-on can be recorded as `on_by: "other"`, and the parent's Wi-Fi step as `"kid"`

`AirplaneRoute.turnOn` writes `recordOnStart(now)` (`AirplaneRoute.kt:208`) **before** step 2's report-only post, which
may take its whole `REPORT_BUDGET_MS = 5_000` (`:39`; it waits for `syncMutex`, so any running sync delays it). The edge
then reads `onByOf(lastOnStartMs, now)` with `KID_ON_WINDOW_MS = 5_000` (`AirplaneMode.kt:74`, `:119`). A post that takes
more than ~4.7 s (a sync in progress, a slow network) puts the edge past the window and the period becomes `other`. The
other way round, `AssistantSetup.startWifiStep` runs the same `turnOn`, so the parent's setup step is recorded as `kid`
(§5.1 says the §3.5 step is `other`). Report-only; nothing on the pages reads `on_by` today.

Fix: keep `recordOnStart` for the cool-down, but stamp the attribution start right before the window (after step 3), and
pass the caller (kid or setup) into the attribution.

### L3. Call-log queries on the main thread at every render of the Flymodus row, also when the row is hidden

`AirplaneRuntime.emergencyNow` (`AirplaneRuntime.kt:103-107`) runs `CallSystem.lastEmergencyCallMs` (a 7-day outgoing
call-log query) and `callbackWindowUntil` (another call-log query) plus a sticky-broadcast registration. It is called
on the main thread by `KidSettingsActivity.renderAirplane` (`KidSettingsActivity.kt:427`, *before* `airplaneRow`
decides whether the row shows, so on every Kid Settings render of every phone) and `RuleSheetDialog.render`
(`RuleSheetDialog.kt:85`, which `LockActivity`'s airplane listener calls at every `AirplaneRuntime.changed()`). While
the row is `COOLDOWN` or `FAILED` (up to 60 s) each render also posts another 2 s re-render (`KidSettingsActivity.kt:451`,
`RuleSheetDialog.kt:104`), and every listener-driven render starts one more chain, so the queries multiply. Jank, and
StrictMode disk-read hits in debug; no wrong state.

Fix: read `emergencyNow` only when the row is about to show and airplane mode is off, off the main thread or cached for
a few seconds, and keep a single pending re-render (`removeCallbacks` before `postDelayed`).

### L4. After a process death mid-route, the pre-apply chrome keeps Settings pinned, LOCKED passes included

Until the put-back's `apply()` hands over a plan, every `LockTaskChrome` pass is `applyFallback`
(`LockTaskChrome.kt:283-319`), which takes the kiosk list from `dpm.getLockTaskPackages` - still holding Settings from
the dead route - and keeps it (`fallbackKioskPackages`, `LockTaskHelpers.kt:231-232`), also on a LOCKED pass
(`kioskPackages - recentsPin`, `LockTaskHelpers.kt:211-213`). `PinLockRuntime` runs such passes at the screen-off and
unlock edges right after a restart. Settings is unhidden as well until the same apply. Bounded by the put-back (its own
thread at `initRest`) and the start sync's apply, so seconds; listed because the route's invariant is "never pinned
while LOCKED".

Fix: in `applyFallback`, drop `SETTINGS_PACKAGE` from the platform's list while `routePin == null` (Settings is never a
kiosk package of the plan), or at least while the DE marker is set.

### L5. A pause made offline can be pruned before it is ever posted

`phonePausesAfterAdd` (`RulePause.kt:124-126`) drops every entry whose last end plus 5 minutes has passed, posted or not,
and `addFromGate` deletes their sync records with them (`PhonePauses.kt:57-63`). §4.4.3 also says "the dead pause is
still posted, so the parent sees that it happened".

Scenario: on a flight, airplane mode on without Wi-Fi, Pappa pauses Leggetid on Emma's phone at 21:10 for 15 minutes,
then again at 22:00. The second gate prunes the first entry (21:25 + 5 min < 22:00) before any network: the PWA's Today
list and Time page never show the first pause. Only the PIN event ("Regelskjermen til 21:25") reaches the parent.

Fix: prune an entry only once it is posted (or older than the server's 7-day bound), keeping the cap of 8 by dropping
the oldest posted ones first.

## Fixes

All six fixed on `design-98` (2026-11-15). Gradle was not run: `/home` had about 5.3 GB free, under the 7 GB the build
needs; the tests below were written but not run.

- **M1.** `PausePolicyView` keeps `(key, rule_id)` pairs (`PausePolicyLift`, built by the new pure `pausePolicyView`
  from the policy's lifts with a `phone_key`; the twin keeps the copy's negative id). After the ack,
  `phonePauseLifts` lifts a rule only while the newest policy still carries a lift with the pause's key **for that
  rule**; before the ack, or with no policy after it, as before. The ack itself still reads the keys
  (`PausePolicyView.keys`). `rule_pause.json` (both copies, byte-identical): the `adopt` rows' `policy` is now
  `{get_started_ms, lifts: [{key, rule_id}]}`, plus two rows - "two rules, one ended in the app: only the other lifted"
  and "a rule and its copy, both rows carried: both lifted". The server doesn't read `adopt`. `RulePauseTest` checks
  `pausePolicyView` against the contract's `policy`. Spec §4.4.3 and `docs/notes/launcher/time-rules.md` follow.
- **L1.** `AssistantSetup.scheduleDeadline` takes a request code: the default-apps window (9801) and the Wi-Fi step
  (9802) each have their own alarm, so neither moves the other.
- **L2.** The cool-down's start (`recordOnStart`) stays before the report-only post; the attribution has its own stamp
  (`AirplaneRuntime.recordKidOnStart`, `kid_on_start_ms`), written in the route's `show()` right before
  `showSession`, which the edge's `onByOf` reads. `AirplaneRoute.turnOn(context, by)`: the kid's rows pass `kid`
  (the default), `AssistantSetup.startWifiStep` passes `other`, which clears the stamp, so the setup step's edge is
  `other` (§5.1).
- **L3.** `AirplaneRuntime.rowState` computes the row from the stored error first and looks at the emergency refusal
  only when the row would show Off, the cool-down or a failure. That look (`emergencyForRow`) never queries on the
  main thread: it returns the last `emergencyNow` (at most 5 s old) and, when that is older or missing, reads it again
  on a thread of its own (one at a time) and redraws the rows if it changed. The route still reads `emergencyNow` fresh
  (background), so no turn-on rests on the cache. Kid Settings and the "···" sheet keep one pending 2 s redraw
  (`removeCallbacks` before `postDelayed`, and again in `onStop`).
- **L4.** `fallbackKioskPackages` drops `com.android.settings` from the platform's list: only the route's window pins
  Settings, and `routeWindow` needs a plan, so in a plan-less pass it is a window a process death left open. A LOCKED
  or unlocked chrome pass before the put-back's apply no longer keeps it pinned. `LockTaskHelpersTest` has the rows.
- **L5.** `phonePausesAfterAdd(entries, added, now, posted)` keeps an entry never posted until it is posted or its
  start is older than the server's 7 days (`PHONE_PAUSE_POST_MAX_AGE_MS`); over the cap of 8 the oldest posted entry
  goes first, then the oldest. `addFromGate` passes the posted keys from `phone_lifts_sync_v1`. `RulePauseTest` has
  the flight case and the cap order.

Settled by the user on 2026-11-15: the refusal is ECBM + 10 min, the callback window dropped ("112 10 min er greit"); the clock rule stays ("Per i dag klarer ikke barnet å stille klokken på tlf selv"). Before that, left as they are (open questions to the user): the post-112 refusal following the 60-minute callback window, and the
clock set back during a pause.
