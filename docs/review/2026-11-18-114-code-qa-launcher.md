# Design 114 code QA: the launcher half (and the shared contracts)

Read-only review of `git diff main...design-114-build -- launcher/ server/testdata/setup_steps.json
server/testdata/restrictions_pause.json` (e9ec0c213, 3c43ab5fa, fbd250523, 4bdbd99e2) against design 114 rev 3/3b and
its two QA reviews. Paths: `L/` = `launcher/app/src/main/java/com/kidslauncher/mdm/`. Line numbers are on
`design-114-build`.

**Counts: 0 H, 1 M, 9 L.**

## The lockout question (the user's concern): nothing found

I traced every way out of the hold, looking for a phone left locked without a Parent PIN or stuck open:
- **Before the first policy** (the Wait screen's "Ferdig" after 30 s, the 4 h ceiling, or a restart while the server
  is unreachable), `currentPolicyDecision()` is `Apply(null)`: `policy_ever_applied` is false and nothing is cached
  (`PolicyGate.kt` `choosePolicy`). The phone comes out **unmanaged and open**, not on `NOTHING_ALLOWED_FALLBACK`. So
  a phone without a PIN is never locked down. Once the first policy lands, the PIN arrives in the same sync (the vault's
  `acceptPolicy`), and the server's 3b rule makes no QR without one.
- **Stuck on:** every reader goes through `isActive()` → `BootClock.isActive(windowStart(), durationMs())`, which
  needs both clocks and the same boot count. `clear()` resets the hold flag. `start()` writes it false, so a later
  PIN pause can't inherit 4 h. The end alarm uses `pauseDurationMs(hold)` (`TimeRulesRuntime` windowEndMs). I found
  no path that keeps it past 4 h of elapsed time.
- **Starting on an enrolled phone:** `setupHoldStarts` needs device owner, `!policy_ever_applied` and no cached
  policy. It is decided before `DeviceToken.set` (`Enrollment.kt`), and `ParentPinCallersTest` pins `startHold(` to
  `Enrollment.kt`. A re-enrolment of a managed phone never starts it.
- **Lock task after the end:** "Ferdig" calls `lockAgain` synchronously (it only takes the marker lock and clears
  prefs), then `finish()`. Home's `onResume` then sees no hold and runs `reconcileKioskMode()`/`resumeContent()`.
  This is the same path as Home's banner "Lås igjen". The hold's start snapshot has `secure = null`, so a wizard PIN
  is never removed.

One residual risk is by design, not a code finding. The phone keeps no guard of its own if the PIN is removed in the PWA
while the hold lasts. It relies on the server's 3b rule and the PWA (design §6.2.3).

## M

### M1. The progress line is always "k av k"
`L/setup/SetupSteps.kt:269-273` (`flowProgress`), called at `L/setup/SetupFlowActivity.kt:139,153`.
- `total = runKeys.size`, and `runKeys` is `SetupRun.shown()`. `compute()` marks only the current first screen as
  shown.
- So the first screen reads "1 av 1", the next "2 av 2", and so on. The line never says how many steps are ahead,
  which is its only job.
- The test hides it: `SetupFlowTest` `the progress counts the run's screens` overrides the total with
  `.let { it.first to 3 }`.

Fix: count the steps ahead as well.
```kotlin
val keys = runKeys + remaining.map { it.key }
val total = keys.size.coerceAtLeast(1)
val done = total - remaining.size
return (done + 1).coerceIn(1, total) to total
```
Then drop the test's override.

## L

### L1. The hold's start snapshot reads Wi-Fi proxies before Vibb holds fine location
`L/server/RestrictionsPause.kt:149-162` → `WindowRelock.startState` → `withWifiConfigs` (`WindowRelock.kt:227-241`).
- `withWifiConfigs` no longer grants FINE itself (§7.1). It relies on `AppEnforcer.apply`.
- `startHold` opens the window, and so takes the snapshot, **before** its `reapply`. On a fresh phone no apply has run
  yet, so FINE isn't granted when the proxies are read.
- If `getConfiguredNetworks` returns an empty list instead of throwing, the snapshot records "no proxies" rather than
  "unknown". The new `catch (SecurityException)` only covers the throwing case. The relock's `clearProxies(atStart = {})`
  would then treat the provisioning Wi-Fi's proxy (QR extras can set one) as added during the window and clear it.
- This is rare, since it needs a proxy in the QR's Wi-Fi.

Fix: in `startHold`, self-grant `OWN_LOCATION_PERMISSIONS` next to the POST_NOTIFICATIONS grant, before
`WindowRelock.opened`.

### L2. The assistant and call-filter "Åpne" still clear the default-apps list
`L/assistant/AssistantSetup.kt:116-122`; `SetupFlowActivity.kt:311-319`.
- Rows 11 and 13 open the pick window with `keepSnapshot = false`. That writes a new snapshot and
  `storeDefaultAppsChanged(emptyList())`.
- A browser moved in an earlier window (reachable from the assistant page's Default apps) is then measured against the
  new holder. It drops off the list and `KEY_MOVED_FROM` goes.
- Row 14 then reads done while the role is still moved. This is the H1 guarantee, "the comparison stays against the
  original holders", broken by the flow's own earlier rows.

Fix: in `openPickWindow`, keep `movedFrom()` and the list whenever the stored list is non-empty, whichever step opens
the window. A fresh snapshot is only for a phone with nothing listed.

### L3. A browser that started at the chooser can never be put back
`L/assistant/AssistantSetupPlan.kt` `stillMoved`, `"browser" -> now.browser != before.browser`.
- When `before.browser` is `"android"` (the chooser, no default) and the window set a default, Android's Default apps
  page usually has no "none" choice for the browser.
- Row 14 then stays missing. It is required and can't be hidden, so the PWA stays orange for good.

Fix: treat `before.browser == "android"` as put back once `now.browser` is any installed browser the policy allows.
Alternatively, don't list `browser` in `movedRoles` when `before` was the chooser.

### L4. The pending page step is lost when the activity is recreated
`SetupFlowActivity.kt:57-63` (`openedStep`, `callFilterQueue`, `wifiPanelShown`/`wifiPanelPaused`).
- These are plain fields. The old Settings rows had one launcher per step; the single `pageLauncher` depends on
  `openedStep`.
- After process death, or a config change outside `orientation|screenSize|uiMode` (density, font scale), the result
  arrives with `openedStep == null`.
- The pick window or Wi-Fi step then isn't ended at once. Airplane mode stays on for up to the 5-minute deadline
  (`SetupDeadlineReceiver`), and the second call-filter role isn't asked.

Fix: save `openedStep?.key`, `wifiPanelShown` and the queued roles in `onSaveInstanceState` and restore them.

### L5. Home skips its content even when the flow failed to start
`L/ui/HomeActivity.kt:561-566`.
- `startFromHome` swallows any exception, and `onResume` returns anyway.
- If the start throws (it shouldn't on 14-17), Home stays without `resumeContent()` for the whole hold.

Fix: have `startFromHome` return a Boolean and `return` only on success.

### L6. A flow left behind an Android page outlives the hold
`SetupFlowActivity.kt:73-80,103-117`.
- `windowListener` is registered only while resumed. If the hold ends (ceiling, the PWA's "Lås igjen") while the
  parent is on Android's page, the flow isn't finished.
- Back from the page then lands on "Not open" with "Sett på pause i 2 t" instead of closing into Home. Not a trap:
  Back finishes it, since the hold is over.

Fix: in `onResume`, `if (heldRun && !RestrictionsPause.holdActive()) { SetupRun.reset(); finish(); return }`.

### L7. The phone reads `parent_pin` from the vault on every apply but never uses it (deviation 5)
`L/setup/SetupReadings.kt:118`.
- Reading `ParentPin.status().configured` is right: since 24b the cached policy has no hash, and `configured` is true
  also for `foreignAccess` and `untilSync`, which is the right meaning.
- But the reading feeds nothing on the phone: `setupAlarm`/`setupRow` have no caller in main, and the step is never a
  screen.
- `readings()` runs inside `AppEnforcer.apply` (`afterApply` → `refreshNotice` → `count`) while a window is open.
  `ParentPin.status` is `@Synchronized` and "may wait for a running check", which is PBKDF2 at about 0.5 s. I found no
  lock-order deadlock (ParentPin's lock never waits on the apply), only wasted waiting.

Fix: drop `parent_pin` from `readings()`, or read it only where a caller needs it.

### L8. Deviation 6: the two added lines
- **Row 8's dialer line** (`setup_how_vibb_notifications_role`, 17 words in nb) shows whenever Vibb is the dialer,
  whether or not any switch is grey. Shorter, same content: "Grå bryter? Sett Anrop og SMS til «Ikke styrt» i
  foreldreappen, og tilbake etterpå." / "Switch grey? Set Calls and SMS to “Not managed” in the parent app, and back
  afterwards." It still says the state and the fix once. It is acceptable as built.
- **Row 17's line** "Legg til et fingeravtrykk." / "Add a fingerprint." is short. It nearly restates the title
  "Fingeravtrykk" (Words in the UI: "no sentence that repeats the label above it"), but `SetupStepsTest` requires a
  line for every screen. Keep it, or give it the path the other rows use, e.g. "Sikkerhet › Fingeravtrykk."

### L9. Smaller points
- **Clock corrections (fail-closed, by design):** `timedWindowActive` ends the hold when the wall clock moves back by
  more than the time elapsed since the start (`untilWall - now > 4 h`), for example an NTP or NITZ correction in the
  first minutes after enrolment. It fails closed, and the PWA's "Åpne for oppsett" recovers. Worth a line in the
  emulator gate's owed checks; no code change.
- **Done steps can't be revisited on the phone:** the flow shows only missing steps. The old Settings rows let a parent
  redo a done one, e.g. change Wi-Fi in airplane mode from kept, re-pick the assistant or enrol another fingerprint.
  `wifi_kept`, once stored, has no on-phone way back. This is accepted by §6.3, but it is a loss to note in the area note.
- **`onListenerConnected`/`onListenerDisconnected` request a sync on every listener rebind**
  (`BadgeListenerService.kt`), which includes every process start. `SyncRunner` coalesces, so the cost is only an
  extra sync per process start. Request only when the state differs from the last reported
  `notification_listener_enabled`.

## Checked and fine

- **Contracts:**
  - `server/testdata/setup_steps.json` and `restrictions_pause.json` are byte-identical with the launcher's copies
    (same SHA-256), and both tests compare the copies.
  - `SetupStepsTest` reads `steps` (every field), `features` (keys), `report`/`report_no_hold` (exact, key order),
    `policy`/`policy_none`, `tick.request`/`answers`/`phone`, and every one of the 24 `needed` rows (missing, count,
    alarm, setup_row). `features`' page/anchor and `clean` are the server's.
  - `RestrictionsPauseTest` checks `setup_hold_max_ms` and `pauseDurationMs`.
  - The phone's `inputs()` matches the server's `inputs_of`: calls not `unmanaged`, `kid_lock.keyguard == "android"`,
    the clock format, bits 8/16/64, map links off until 110.
  - `channelsOrange` reproduces 107's `pages.orange` row by row (`NotificationChannelsTest`).
- **Readings, Android 14-17:**
  - Every API is ≤ 34: `canUseFullScreenIntent` and `ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT` (34),
    `isDataEnabledForReason` (31, with `READ_BASIC_PHONE_STATE` added, a normal permission on 33+),
    `getRestrictBackgroundStatus`, `getDefaultDataSubscriptionId` and `isDeviceOwnerApp`.
  - Each read is in `runCatching`/`read{}` and gives `null` when unreadable.
  - The page actions are the right constants, and every page goes through `systemPageIntent` (`MATCH_SYSTEM_ONLY`)
    with no `NEW_TASK`, so `startActivityForResult` returns on the page's real end.
- **Location:** `AppEnforcer.apply` self-grants FINE and COARSE only, skipping when already granted; BACKGROUND stays
  with Locate and `ZoneFences`; no code ever denies FINE/COARSE. `LocationPolicyTest` pins it, and the time-rules
  note is updated.
- **Its own task:**
  - `singleTask` + `taskAffinity=.setup` + `excludeFromRecents`, not exported.
  - Home's start is a foreground start by the Home role holder, so the app-switch delay and 15's ASM allow it.
  - With `singleTask`'s clear-top, a Home press from Android's page finishes the page and delivers its result, as
    designed. Back is an `OnBackPressedCallback`, so predictive back on 16 works.
- **Process death:** the hold lives in prefs and Home brings the flow back. The run's skips are lost, as designed.
  `lastEnforcedOverride` being `null` in a new process doesn't block "Åpne": pages resolve in a window, so
  CAN_OPEN doesn't need it.
- **The 30 s Wait:** it shows only with nothing runnable before the first policy, gets "Prøv igjen" and "Ferdig" after
  30 s, and survives pause/resume (`waitSinceMs`).
- **Ticks:** pending in device-protected prefs; posted before the policy GET; dropped on 2xx/400/413 or when the policy
  carries the key; retried on 401/403/5xx/no answer; cleared at enrolment.
- **Notification dialogs over the flow:** Home skips `requestNotificationPermission` on a device owner, and
  `startHold` fixes POST_NOTIFICATIONS before `opened`. `promptForCallRoleIfNeeded` lives in `resumeContent()`,
  which the hold skips. The window notification's body tap opens the flow only while the hold lasts.
- **The hold and other windows:**
  - A `start_pause` during the hold leaves it as it is ("in setup").
  - `adoptedStartMs` covers 4 h.
  - Screen time doesn't accrue in the hold, and the flow is in `FREE_SCREENS`.
  - The report hash is JSON-wide, so `setup` changes send a full report. `hold_left_ms` changes every report, so the
    hold's reports are all full, which is harmless for 4 h.

---

## Resolved (2026-11-18, on `design-114-build`)

| Finding | Resolution |
|---|---|
| M1 | RESOLVED. `flowProgress` counts the steps ahead, so the first of seven reads "1 av 7". `SetupFlowTest` no longer overrides the total. |
| L1 | RESOLVED. `startHold` self-grants `OWN_LOCATION_PERMISSIONS` next to POST_NOTIFICATIONS, before `WindowRelock.opened`. |
| L2 | RESOLVED. `openPickWindow` keeps `movedFrom()` and the list whenever a role is listed, whichever step opens the window. |
| L3 | RESOLVED. `movedRoles` never lists `browser` when the window started at the chooser (`"android"`). |
| L4 | RESOLVED. `openedStep`, the Wi-Fi panel's flags, `heldRun` and the queued role requests are saved in `onSaveInstanceState` and restored. |
| L5 | RESOLVED. `startFromHome` returns whether it started, and Home returns only on success. |
| L6 | RESOLVED. `onResume` finishes the flow when the run was the hold's and the hold is over. |
| L7 | RESOLVED. `parent_pin` is no longer read on the phone: nothing there used it. |
| L8 | RESOLVED. The dialer line is the shorter "Grå bryter? Sett Anrop og SMS til «Ikke styrt» i foreldreappen, og tilbake etterpå." (en likewise). The fingerprint screen has no line, since its name says it (`SetupStepsTest` exempts it). |
| L9 | RESOLVED. The listener's connect and disconnect ask for a sync only when the state differs from the last one seen (`SetupReadings.listenerChanged`). The area note names the steps that can't be redone from the flow and the clock correction's fail-closed end. `docs/testing/emulator.md` §6bb owes the check. |
