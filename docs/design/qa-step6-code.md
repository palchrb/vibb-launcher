# QA step 6 code review: time rules, screen time, lifts, alarm, location

Reviewed S `2efa112`/`c86a49e` and L `e06c549`/`e1661d7` against 06-time-rules.md and the guarantees in 01-05. Tests
re-run: server 100/100 and launcher 234/234 pass. "Inferred" = read from the code only, not tried on a device.

## Findings

1. **High: calls and VoIP make all screen time free.** `ScreenTimeTracker.kt:78-81`: counting stops whenever
   `OngoingCalls` isn't empty or the audio mode is IN_CALL/IN_COMMUNICATION, whatever app is in front. Bypass: call an
   allowed contact (or start an Element/Signal call, or open a WebRTC/voice-chat page or game, since any app can set
   MODE_IN_COMMUNICATION), press Home and use any allowed app. The budget never runs out. Fix: remove the in-call
   exclusion. Our in-call screen is already excluded as one of our own activities (`resumedOwnActivities`). If VoIP
   time must be free, exclude it only while the VoIP app is in front, and add a test for that rule.
2. **Medium (inferred): time is not counted while our activity is resumed and another app is visible.**
   `ScreenTimeTracker.kt:51,81`: this covers picture-in-picture video over Home, split screen with the phone book, and
   overlay windows or bubbles. Kiosk probably blocks PiP and split screen, but kiosk is off when it isn't wanted or the
   allowlist is null. Fix: exclude only LockActivity, the phone book, in-call and Settings, not Home; don't exclude while
   `isInMultiWindowMode`; set `DISALLOW_CREATE_WINDOWS` while a budget is set. Check PiP on the device (checklist 5).
3. **Medium: the clock and time zone can be changed in some states, which defeats rules and budget.** Rules are
   evaluated on the plain wall clock (`TimeRulesRuntime.kt:136`, by design, because the clock is supposed to be
   locked). But `lockDateTime = appsManaged || callState.managed` (`EnforcementPlan.kt:163`) is false in two cases:
   (a) allowlist null with calls unmanaged ("bedtime only" phones), and (b) during an override or pause while calls
   are unmanaged. Also, `applyDateTimeLock` (`AppEnforcer.kt:428-439`) turns on automatic time but never automatic
   time zone. A zone changed during an override therefore stays after the lock returns and shifts every rule by
   hours. Fix: lock date/time whenever the time policy has any rule or budget, override or not, and call
   `setAutoTimeZoneEnabled(true)` too. Add an EnforcementPlan test.
4. **Medium (decision): the override PIN or pause lifts school's call block, which contradicts 02 and the server UI.**
   `TimeRules.kt:169` returns UNLOCKED and `TimeRulesRuntime.kt:153` passes `overrideActive()`, so during school with
   the PIN, allowed contacts can call again. Conflicts:
   - 02-calls.md:477 (binding): "Override PIN and pause never open calls".
   - device_detail.html:267 says the PIN lifts "the schedule ... (not calls or phone hardening)".

   The PIN belongs to the parent, so the risk is limited, but either keep `callsBlocked` independent of the
   override (the app lock still lifts), or get sign-off and update 02, the PIN text and checklist 9.
5. **Medium: "School (nothing but emergency calls)" is not true when calls are unmanaged.** Incoming calls ring (gap
   noted in 06), and neither the rule form (`schedules.html:16`) nor the device card warns. Fix: show "incoming calls
   aren't blocked: calls are unmanaged" on the device card or rule list while a calls-off rule applies to such a device.
6. **Low: an unreadable ledger resets usage to 0 and forgets the applied budget lifts** (`TimeRulesRuntime.kt:61-69`).
   That refills the day and re-applies every budget lift still being delivered. This fails open, unlike the rest of
   the step. Fix: on a decode error keep today as exhausted (or keep the previous day/ids in a second key), and log
   it in the status report.
7. **Low: location "off" is not fully off, and the locate answer isn't shown.**
   - With the CONFIG_LOCATION hardening switch on, system location stays forced on (`AppEnforcer.kt:322`) while the UI
     says "Never (location off)". Reword the label ("the launcher never takes a fix") or note the hardening switch.
   - The launcher's honest answer ("location is off for this device" / "no location fix") isn't surfaced: after 60 s
     the page says "may be offline or indoors" (`device_locate.html:176`). Show the locate command's result there.
   - A failed fresh fix falls back to the cached one (`LocateCommands.kt:155`). That is honest, because the age is
     reported and shown, so no fix is needed.
8. **Low: the device card's lift status uses the server's expiry.** `devices.rs:382` shows "Active until ... (UTC)"
   even after a reboot or a late delivery ended the lift on the phone. Use `time_state.lifts_active` for the "Active"
   label.
9. **Low: some parent changes are missing from the security log.** Location-policy, rule and budget edits
   (`locate.rs:259`, schedules.rs create/update/delete/save) aren't recorded, though lifts are. Add events.
10. **Low (tests): the risky parts are untested.**
    - No unit tests for the tracker's counting conditions (would have caught #1/#2).
    - No tests for `TimeRulesStore.callsBlockedNow` with sources NONE/BOOT/CE, or for `effectiveState()` wiring (only
      the pure `withTimeRule`).
    - No unauthenticated-access tests for the 5 new admin routes (they are behind `require_full_auth`, `main.rs:441`,
      verified by reading).
    - Extract `appInUse` as a pure function and test it.

## Verified OK

- Migration matches the old lock minute by minute, against an old-evaluator oracle on both sides. The server runs it
  once per row in one transaction. Legacy fields are frozen but still sent (old launcher keeps the old, stricter
  schedule). The launcher converts an old server's windows itself.
- A policy without `time_policy` after one was seen is rejected. Malformed rules, budgets and lifts fail closed.
  `LastEnforcedPlan` keeps the rules (without lifts). Corrupt stored data is a 500 on the server.
- School and the call path: screening, redirection, in-call and the phone book use `effectiveState()`. Emergency
  calls and the PSAP callback window still pass, and `DISALLOW_OUTGOING_CALLS` is set. Before the first unlock the DE
  copy is used, and a missing copy means blocked. Messaging apps are suspended unless exempt. Exempt apps still follow
  the allowlist.
- Lifts:
  - Admin-only, rule id checked against the device's effective rules.
  - Bounded by `min(expires_at, minutes)` on elapsed realtime and boot count, so a reboot ends a lift.
  - Budget lifts apply once per id.
  - A clock set back can't refill the budget or stretch a lift.
- Missed alarm: calls are decided live, so screen-off is safe. The lock is re-checked on screen-on, user-present,
  Home/Lock resume and the 5-min sync backstop while awake, and on time/zone change and boot. DST is handled and tested.
