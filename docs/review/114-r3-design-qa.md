# Design QA: 114 revision 3 (the setup hold and the guided setup)

Read-only QA of `docs/design/114-setup-status.md` at f7fa57434 (branch `design-114-r3`), against the code on `main` at
f13b79cbd. Paths: `L/` = `launcher/app/src/main/java/com/kidslauncher/mdm/`, `S/` = `server/src/`. Ranked H (blocks
the build or breaks a settled rule), M (wrong or incomplete; fix in the note before that step is built), L (tidy).

What checked out (no finding):
- **The hold's start point.** `Enrollment.enroll` (`L/server/Enrollment.kt`) is the one enrolment: the QR's
  `applyProvisioningExtras` (`L/server/Provisioning.kt`, both Android's provisioning and the in-app scanner),
  Settings' typed code (`SettingsFragmentLauncher.enrollWithServer`) and `ReEnrolDialog` all call it. The "never
  applied" test is the one `SettingsActivity` uses (`policyEverApplied() || kidModePolicy` set, line 78), and
  `policy_ever_applied` is set only by an apply (`MdmSyncWorker`) and cleared only by `FirstLaunchReset`, which never
  runs on an enrolled phone. So `ReEnrolDialog` and a recovery code never start a hold, and a child can't make one.
- **Extending or staying.** The hold rides on `timedWindowActive` (elapsed + boot count + wall clock both ways), so a
  reboot, a clock change and the ceiling all end it; `start_pause` while held is a no-op; the PIN pause can't be
  started while a window is open. The server's `paused_at` exception is bounded by the phone's own end and by
  `reported_at + SETUP_HOLD_MAX_MS`. `device_status` is a new row per report (`device_api.rs` intake), so a report
  without `hold_until_ms` clears the column by itself.
- **What the pause lifts.** Home stays Vibb's in every pause (`enforceDefaultHome` before the lift), the call rules and
  the hardening (factory reset, unknown sources) are never lifted, the mobile-network settings lock is
  (`mobileSettingsLocked(value, overrideActive)`), and the relock after the hold checks new apps, CA certificates and
  proxies as after any pause. Apps installed from Play during the hold pass the relock (`relockBlockDecision`).
- **The time rules and lock task.** `TimeRulesRuntime.currentLock(..., overrideActive)` gives no lock in the hold; the
  kiosk plan is `null` under the pause, so `reconcileKioskMode` stops lock task and Android's pages open
  (`systemPageAccess`'s WAIT covers the apply still on its way).
- **The pause's other readers** of the 2 hours are exactly the three the note names (`RestrictionsPause.isActive`,
  `TimeRulesRuntime.windowEnds`, `adoptedStartMs`) plus `ParentPin.windowDuration(PAUSE)`, which correctly stays.
- The Android actions and API levels in §2.2 and §7.3 are right for minSdk 34; `OnBackPressedCallback` is the right
  Back on 16 at targetSdk 36; both starts of the flow (Home's `onResume`, a notification tap) are foreground starts.

---

## H1. Row 14 `default_apps` can't be done from its screen, and its reading never clears

- **Where:** §2.2 row 14, §6.2.2's "Åpne", §5.6's `setup.how.default_apps` ("Velg dem tilbake.").
- **The failure:** while calls or SMS are managed and Vibb holds the role, `DISALLOW_CONFIG_DEFAULT_APPS` is set
  (`lockDefaultAppsFor`, `L/sms/SmsRole.kt:55`), and the calls part of `apply` is never lifted by a pause. Only design
  98's default-apps window lifts it (`AssistantSetup.assistantPickOpen`). So the flow's "Åpne" on
  `ACTION_MANAGE_DEFAULT_APPS_SETTINGS` lands on a page Android greys out. Worse, the reading itself is stale:
  `default_apps_changed` is written only by `openPickWindow` (cleared) and by `afterApply` when a pick window closes
  (`L/assistant/AssistantSetup.kt:121-180`). A role the parent puts back, or HOME that the DO re-seats, stays in the
  list until the next assistant window, and with the role already held the assistant step isn't a screen any more.
  Revision 3 makes this a **required, counted** step that can't be hidden (§5.3). Once it is raised, the summary stays
  orange for good, and the parent has no path in the flow or the PWA to clear it.
- **Fix:** read row 14 live. Recheck the stored entries at each read and drop an entry once it is back: `home` =
  `ROLE_HOME` held; `browser` = the `https` resolver equals the snapshot's. `call_screening`/`call_redirection` leave
  row 14 altogether, since row 13 reads and counts the same roles. "Åpne" runs inside design 98's pick window, opened
  **without** a new snapshot (e.g. `openPickWindow(keepSnapshot = true)`), so the comparison stays against the original
  holders and opening the page doesn't clear the step by itself. If that's too much for this round, make row 14
  `optional` until it is read live. Add a `needed` row (role back → not missing) and a gate row (calls managed,
  browser moved in the assistant window, the flow's step, the page usable, the step gone).

## M1. The hold's start snapshot can remove the screen lock set in Android's setup wizard

- **Where:** §6.1.1 (the hold starts in `Enrollment.enroll`), §6.1.3 1 ("Ferdig" → `lockAgain`).
- **The failure:** `RestrictionsPause.start` → `WindowRelock.opened` takes the window's start snapshot, including
  `secure = deviceSecure`, and activates the reset token. The relock's credential step removes a credential set
  during the window: `credentialAction(secureAtStart = false, secureNow = true, tokenUsable, androidRequested)` →
  `REMOVE` (`L/server/WindowRelockPlan.kt:138`). It is skipped only while the policy asks for Android's lock, and a
  new phone's `kid_keyguard` is `own` (migration 0085) until the parent switches it. If the QR's enrolment
  (`onProfileProvisioningComplete`) runs before the wizard's screen-lock step, then a PIN the parent sets in the
  wizard is silently removed at "Ferdig", and `credential_removed` shows in the PWA's security log. Today there's no
  window at enrolment, so this doesn't happen.
- **Fix:** a hold's start snapshot stores `secure = null`. That is the adopted-window rule ("unknown: never removed"),
  so the hold never removes a credential, and the PWA's own warning about a credential the kid set stays the guard.
  Keep the token's early activation (useful: `set_kid_pin` then works over a wizard PIN). Gate row on the Jelly Star:
  set a screen lock in the wizard, then "Ferdig": the lock stays. Record when the enrolment runs relative to the
  wizard's steps.

## M2. Home as the flow loses the run on every Home press

- **Where:** §6.2.1 (`HomeActivity.onResume` starts the flow; `singleTop`, not exported), §6.1.5 ("Senere"/"Hopp
  over" last one run).
- **The failure:** `HomeActivity` is `singleTask` with `clearTaskOnLaunch` (manifest, `.ui.HomeActivity`). If the
  flow is started into Home's task, each Home press clears it, and `onResume` starts a fresh run. Pressing Home is the
  usual way back from Android's page after "Åpne". The run then goes back to its first screen, and steps put off with
  "Senere" or "Hopp over" come back. Two smaller gaps:
  - the note doesn't say where in `onResume` the start goes. Before `gate()`, it would start over the PIN lock's night
    ground. Before `startWork()`, it skips the first-launch work;
  - "Ferdig" → `lockAgain` → Home can race. If the hold flag is cleared off the UI thread (`lockAgainAsync`), Home's
    `onResume` can still see the hold and start the flow again.
- **Fix:**
  - the flow gets a task of its own (`taskAffinity="${applicationId}.setup"`, as the phone book's), and Home brings
    that task to the front instead of starting a new instance;
  - the run's skips and position live in a process-level holder for the hold's length, not in the activity;
  - the start sits in `onResume` after `startWork()`, `gate()` and `redirectToLockScreenIfLocked()`, in place of
    `resumeContent()`;
  - "Ferdig" calls `lockAgain` (synchronous: it only takes a lock and clears prefs) before it finishes into Home.

  Gate row 9 adds: Åpne → Android's page → Home: the same screen, the skips kept.

## M3. The Wait screen can trap the parent

- **Where:** §6.2.1 ("Before the first policy the flow shows 'Et øyeblikk …'"), §6.2.2's Wait row (no buttons).
- **The failure:** in the hold Home is the flow and Back does nothing. If the first sync never lands (the server
  unreachable, the tailnet down, a 5xx), the phone stays on "Et øyeblikk …" with no button for up to the ceiling.
  Vibb's own Settings is open on such a phone (`SettingsGate`: `OPEN` until a policy is applied), but the flow offers
  no way there. This contradicts §6.2.2's own "Nothing can trap the parent".
- **Fix:** only the conditional rows need the policy (§2.3: rows 9-13, 15-17, 20, 21 and the ticks). Run the `always`
  rows at once (3-8, 14, 18, 19, 22), and let the rest join when the policy lands. Show Wait only when nothing is
  runnable, and after ~30 s give it two quiet buttons: "Prøv igjen" (`SyncRunner.request`) and "Ferdig". Add a
  `SetupFlowTest` row (no policy: the `always` steps, not Wait).

## M4. The notification permission at setup time

- **Where:** §6.1.2 (the hold opens through `WindowRelock.opened`), §6.4 (the setup notification in the hold), §6.2.1.
- **The failure:** POST_NOTIFICATIONS is fixed by the DO only in two cases. With the dialer role's set
  (`ownPermissionsToFix`), which needs calls managed and the first policy. Or at a relock
  (`WindowRelock`'s `regrantNotifications`). Otherwise Home's first start asks with Android's dialog
  (`HomeActivity.startWork` → `requestNotificationPermission`). In the hold:
  - `opened` posts the "Lås igjen" notification once, at enrolment, before any grant, and doesn't post it again;
  - the first Home both asks for the permission and, new, starts the flow at once, so the dialog is covered or
    dismissed.

  On a phone with calls unmanaged, the hold then has neither the "Lås igjen" notification nor §6.4's setup
  notification. A parent in Play or on Android's pages sees no sign that the phone is open. This is Android 13+
  behaviour, so it holds on 14-17 alike.
- **Fix:** the hold's start self-grants POST_NOTIFICATIONS (`QuickControls.fixOwnPermission`, as the relock does)
  **before** `WindowRelock.opened`. Home skips `requestNotificationPermission` while Vibb is device owner. Gate row:
  calls unmanaged, a fresh enrolment, both notifications show.

## M5. Question 2's trade-off is stated wrong: Home as the flow guides, it doesn't contain

- **Where:** top question 2; §6.1.3's last paragraph ("an unfinished phone handed to the child shows the setup
  screens, not an open Home").
- **The failure:** in the hold the child can still get anywhere:
  - every step's "Åpne" lands in Android's Settings, and App info › Åpne opens any app;
  - the shade and Recents work (no lock task), and a notification opens its app.

  So the flow as Home keeps no one out. The PWA's red row and the ceiling are the hold's safety. What the capture
  does block is the setup work that needs other apps:
  - Play installs: the chat app, and Organic Maps/CoMaps, without which rows 15-16 never apply;
  - the chat app's login, and so UnifiedPush's registration (`app_push`);
  - Element X's channels, without which row 22's "Åpne" (the app list) has nothing to set.

  The note's answer is "Ferdig", then the PWA's "Åpne for oppsett", which is a second relock round on day one.
- **Fix:** keep the recommendation (yes) but change the shape and the words:
  - add one optional screen "Apper" before `message_banners`: "Åpne" starts the Play Store, never counted, no tick.
    Play is open in the hold anyway, and Play installs pass the relock;
  - reword question 2 and §6.1.3: Home as the flow steers the parent and makes an unfinished phone obvious, but it
    isn't a lock;
  - name where the parent re-opens after "Ferdig" with nothing missing. §5.1's open group shows only while a step is
    missing, so it is the quick sheet's "Åpne for oppsett".

## M6. The hold's opener isn't pinned, and the duration flag's writes aren't specified

- **Where:** §6.1.2 (`startHold`, the flag `restrictions_paused_hold`, `durationMs()`).
- **The failure:**
  - `ParentPinCallersTest` pins `RestrictionsPause.start(` to `ParentPin.kt` and `MdmSyncWorker.kt`. The token
    `RestrictionsPause.startHold(` doesn't match it, so the one property the hold's security rests on (only a fresh
    enrolment opens it) would be unpinned. `RestrictionsPause`'s doc ("can only be turned on through the parent
    PIN's gate") also becomes false;
  - the note says `clear()` clears the flag, but not that `start()` writes it false. A flag left set while
    `restrictionsPaused` is false would give the next 2-hour PIN pause the hold's 12 hours. No path leaves it today,
    but `durationMs()` reads it unconditionally.
- **Fix:**
  - `ParentPinCallersTest` asserts `callers("RestrictionsPause.startHold(") == setOf("Enrollment.kt")`;
  - the doc names the hold's basis: the one-shot enrolment code on a phone that never applied a policy;
  - `start()` writes the flag false and `startHold()` true, inside the same `lift`, before `restrictionsPaused(true)`;
  - a `RestrictionsPauseTest` row: a hold ended, then a PIN pause lasts 2 hours.

## L1. "The date and time aren't locked during the hold" is wrong for most phones

- **Where:** §6.1.3 3.
- `EnforcementPlan.lockDateTime = appsManaged || callState.managed || smsMode != UNMANAGED || timeRulesSet`
  (`L/server/EnforcementPlan.kt:288`, "Not lifted by an override then"). With calls or SMS managed, the clock stays
  locked in the hold. The conclusion (don't re-arm the hold at boot, fail closed) holds either way: fix the reason
  ("the elapsed clock restarts, and the wall clock isn't locked on every phone").

## L2. Two notifications during the hold

- **Where:** §6.1.2, §6.4.
- `WindowRelock.opened` posts the pause's ongoing "Åpent til … · Lås igjen", and §6.4's "Fullfør oppsettet av Vibb
  One" shows in every window. Say which the hold shows. Suggested: both, as in a PWA pause, but the window
  notification's body tap opens the flow during the hold. Or say explicitly that two are fine.

## L3. `setup.how.phone` points at a notification that is often not there

- **Where:** §5.6's first row of the ⓘ table.
- The notification shows only while a window is open (§6.4). Outside one, the way in is parent Settings' "Oppsett".
- **Fix:** «Foreldreinnstillinger › Oppsett på telefonen.» / "Parent settings › Setup on the phone."

## L4. One state, two words; one word, two meanings

- **Where:** §5.6 `setup.value.done` "Ferdig"; §6.6 `settings_setup_complete` "Fullført"; the flow's button "Ferdig".
- The PWA says "Ferdig" where the phone says "Fullført" for the same state, and "Ferdig" is also the button that turns
  the restrictions on.
- **Fix:** use "Fullført" / "Complete" for the state on both sides, and keep "Ferdig" for the button only.

## L5. `setup.background_data` names Data Saver, while step `background` is the battery

- **Where:** §3.1 `report`, §4.1.
- Row 6 `data_saver` reads `setup.background_data`, and row 7 `background` reads `battery_optimization_exempt`.
- **Fix:** rename the report key `data_saver` (true = Vibb isn't cut), so each key matches its step.

## L6. The server compares the phone's wall clock

- **Where:** §6.1.4.
- `hold_until_ms` is the phone's `System.currentTimeMillis()` + 12 h. If the phone's clock was behind at the start
  and never corrected, the PWA shows the phone locked while it is still open.
- **Fix (cheap):** the report also sends `hold_left_ms`, and the server uses `min(setup_hold_until_ms, reported_at +
  hold_left_ms, reported_at + SETUP_HOLD_MAX_MS)`. Or say the skew is accepted.

## L7. The tick route's edges

- **Where:** §4.4.
- Its neighbours each carry a `DefaultBodyLimit` (`S/device_routes.rs`): give it one (256 bytes).
- Name the phone's action for 401/403. Suggested: `retry`, since a re-enrolment clears the set with the other
  enrolment state.
- Say whether the phone's tick is in the settings log with "på telefonen".

## L8. The parent's hours in the hold count as the child's screen time

- **Where:** §6.2.
- `ScreenTimeTracker` counts in a pause, and `FREE_SCREENS` (`L/timerules/ScreenTime.kt:186`) lists `SettingsActivity`
  but not the flow. A budget set during setup starts the child's first day part-used.
- **Fix:** add `SetupFlowActivity` to `FREE_SCREENS`. Android's pages still count: say so, or don't accrue during the
  hold.

## L9. A reboot inside Android's setup wizard ends the hold before the flow is seen

- **Where:** §6.1.3 3, §6.1.5.
- A wizard that installs an update and restarts ends the hold. The first Home is then the child's, and the flow can't
  be opened on the phone (no Parent PIN yet). It fails closed, which is right.
- **Fix:** §6.1.5 names this case, and the way on is the PWA's "Åpne for oppsett". Add it to the Jelly Star's owed
  checks.

## L10. Decide "fresh" before the token is stored

- **Where:** §6.1.1.
- Read `policy_ever_applied` and the cached policy at the top of `Enrollment.enroll`, before `DeviceToken.set`. A
  backstop sync or the stream can't then apply a policy between the token's commit and the check.

## L11. §2.3 and row 1 disagree on whether the phone sees `parent_pin`

- **Where:** §2.3, §2.2 row 1.
- §2.3 says "The phone can't see `server` steps", while row 1 says the phone reads the PIN hash from its policy, and
  the flow's PIN screen depends on that.
- **Fix:** §2.3 says the phone reads `parent_pin` from its policy, and `device_owner` is its own reading.

---

## The two questions

1. **The hold's ceiling: 4 hours, not 12.** The costs aren't symmetric:
   - too short costs one tap: the PWA's "Åpne for oppsett" (2 hours), or a PIN pause from the flow's "Not open"
     screen;
   - too long costs an open phone, with no filter, no time rules and every app. 12 hours covers a night: a phone set
     up at 20:00 and handed over unfinished in the morning is open until 08:00, on its way to school.

   The wizard, the flow and app installs fit in 1-2 hours, so 4 hours (twice the pause) leaves room.
2. **Home is the flow: yes, with M5's change.** It steers the parent and makes an unfinished phone obvious, which is
   worth it. But it doesn't keep a child out, so the note should stop presenting it as a guard. And it needs the
   "Apper" screen (the Play Store), or the most common setup work (installing and logging into the chat app) forces a
   "Ferdig" and a second opening on day one. Do it together with M2 (its own task) and M3 (no dead end before the
   first policy), or capturing Home makes the setup worse rather than better.
