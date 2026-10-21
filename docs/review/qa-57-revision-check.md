# Revision check - 57 Only the call's own screen over the PIN lock (usage access)

Checked on `main` at 1ba6735a (2026-10-21): `docs/design/57-voip-call-screen-over-lock.md` (revised, committed)
against `docs/review/qa-57-design.md` (20 findings), the user's decisions (Q-A to Q-E, Q1, Q2, the generic IN_CALL
rule, mandatory usage access, no manifest `showWhenLocked`) and PLAN's Fix backlog entries on S-M4 and design 57.
Nothing was built or run. AOSP was not re-fetched: I trusted the note's AOSP lines where the QA had already checked
them, and only re-read the launcher code the revision newly cites or changes.

Paths: `L/` = `launcher/app/src/main/java/com/kidslauncher/mdm/`. "§" = the note's section.

## 1. The 20 findings, checked in the body

"Fixed" means the body (not only the closing table) now says it, in a place an implementer will read.

| # | Sev | Status | Evidence in the body |
|---|---|---|---|
| 57-1 | High | **Fixed, but see N1** | §0.3 "The guarantee is **activity-level**"; §0.5 + §2.3 `neverTrusted`: "the package's launcher activities (every `MAIN`/`LAUNCHER` activity and `getLaunchIntentForPackage`'s component, resolved through aliases ...)"; §10 step 1 item 1: "If either reaches the chats **without** a new RESUMED (in place), stop and go back to the user"; §9 tests "a launcher activity (and an alias's target) is never trusted" and the mutation "drop the never-trusted guard". The `covered` half of the guard is persistent and contradicts Element's own flow (N1). |
| 57-2 | High | **Fixed** (see N6) | §2.5: "`SendMark(RING_SCREEN \| ANSWER \| REOPEN)` sets `expect = send + VOIP_SCREEN_LEARN_MS`; while it runs the re-front check yields"; "One decider ... `CallScreenWatch.verdictNow` (the last verdict if younger than `VOIP_SCREEN_POLL_MS`, else one query)". Table rows `expecting = true -> true`, `COVER -> false`. §9: "covered + expecting -> Yield, covered + expired -> Refront". |
| 57-3 | High | **Partial** (N2) | (b) is fixed: §5b "false only when **our own** wake, FSI, answer or reopen start ... No phase clause", test and mutation in §9. `coverVoipCall` "not while a probable SOS is marked" (§3.2). The watcher's own SOS detection (§2.4 row 5) sits behind rows 3/4 and can lose the race. |
| 57-4 | High | **Fixed** | §2.1 table: `overLock = false` -> `callPhase`/`callPackage` "verdict's phase / pinned"; "`pinnedPackage` is `callPackage` - the kiosk-off pin follows the call, never the gate"; "Without access the call app stays pinned (kiosk off) while its call may live". `LockTaskChrome.voipPackages` is in the `callPhase` row. Code check: `VoipCalls.eligible` (`L/lock/VoipCalls.kt:226`) does not depend on access, so the verdict, and with it `pinned`, exist without it. §9 "`callPackage` kept without access. **Mutation**: the pin read from the lock phase." |
| 57-5 | High | **Fixed** | §1 "The op is reset for a package that doesn't declare the permission"; §7.4 two releases ("Prep release ... Gating release ... Released only once every phone ... reports `usage_access_granted: true`"); §3.1 manifest + "A manifest test keeps it"; §10.6 and §13 turn old check 6 into a confirmation; §6 runbook "only on a build that declares `PACKAGE_USAGE_STATS`". |
| 57-6 | High | **Fixed** | §0.4 + §2.3: trust only from our sends; "reset per ring (`ringId`, QA 57-6: reset per ring, not per record)"; no pending/"ring ended" confirmation remains (grep: none). §9 "Decline on an app's own ring screen -> its main activity resumed -> COVER (no 'ring ended' confirmation exists)"; mutation "reset per record instead of per ring". |
| 57-7 | Med | **Fixed** | §2.4 `CallFold`: `resumed`/`visible` sets, `strayResumed`, dedupe "by timestamp, type and class", DESTROYED (24) queried (§3.2), window "never ... before the lock or the call", fold reset rules. §9: "a call screen resumed 60 s ago with no new events -> ALLOW; two resumed classes ... -> COVER; RESUMED(chat) then RESUMED(call) in one window -> COVER; DESTROYED without PAUSED; no event before the window start". |
| 57-8 | Med | **Partial** (N3) | §2.4 rows 3/4 ("paused-not-stopped ALLOW only in lock task"), §2.6 `lockTaskOverCall`, §5c "so the flash stays until the kid taps its OK ... the watcher does not end it". But §2.6 fires only when the lock resumes over the call, so a remembered or FSI-trusted screen never gets lock task, and its trigger query can lose a race. |
| 57-9 | Med | **Fixed** | §3.2 "Failures fail closed ... after `VOIP_WATCH_FAIL_LIMIT` (3, ~1.5 s) in a row the verdict is COVER, logged once"; §9 "three throwing polls -> COVER. **Mutation**: no limit." The API 34 path is gone (Q-C). |
| 57-10 | Med | **Fixed** | §2.4 "`since` = the later of the lock leaving the front and our last send; the wait is `VOIP_SCREEN_LEARN_MS` (3 s) after a send, `VOIP_EVENT_LAG_MS` (1 s) otherwise"; §3.2 `voipWatchDue(..., ownOthersResumed)`; §9 the 3 s learn test and the `voipWatchDue` table with "our wake window up" mutation. |
| 57-11 | Med | **Fixed** | §0.7 Android 15+ only; §2.1 `voipOverLock(granted, sdkInt) = granted == true && sdkInt >= 35`; §3.2 "Below API 35 the watcher never runs"; §9 source scan "reached only behind `voipOverLock` (`SDK_INT >= 35`)"; §7.1/§7.2 `voip_over_lock_unsupported` and the Android 15 note. |
| 57-12 | Low | **Fixed** | §2.1 read-site table names `PinLockRuntime` `:522`, `:350`, `:742`, `:1070`, `:630`, `:1665`, `PinLockActivity.kt:366/:550`, `KeyguardCover :298`, `ringId`/`ringingPackage` (`VoipCalls.kt:97-99`, `:199`); "Every read site, with a source scan in `VoipCallPlanTest`"; §9 "every row of §2.1's table". All cited lines opened and correct. The one wrong placement is N4. |
| 57-13 | Low | **Fixed** | §2.1 table: "the camera (`updateCallForCamera` `:822-827`, QA 57-13)" in the `callPhase` row. Code: `L/lock/PinLockRuntime.kt:823` reads `VoipCalls.phase`. |
| 57-14 | Low | **Fixed** | §5d: `target` = `callPermissionTargets` apps that request READ_PHONE_STATE (Q-E); "**A runtime revoke kills the app's process**: releasing while a WhatsApp call is on ... ends that call"; §9 mutations "release as `[DEFAULT]` alone; the order reversed (`[DEFAULT, DENIED]`)". Small ambiguity: N12. |
| 57-15 | Low | **Fixed** | §6 "resolves `ACTION_USAGE_ACCESS_SETTINGS` with `package:<ours>` first, else the plain action (... a `package:` intent never matches the list's filter)"; "tap the toggle, OK, then App info > ... > Allow restricted settings, then the toggle again"; §6 last bullet + check 8 for the OEM trusted-installer dependence. |
| 57-16 | Low | **Fixed** | §3.1 "`MODE_ALLOWED`, or `MODE_DEFAULT` with `checkSelfPermission(PACKAGE_USAGE_STATS)` granted (so `adb shell pm grant ...` works too)"; the listener "posts the re-read to the main thread". |
| 57-17 | Low | **Fixed** | §3.2 "on its own thread (`call-screen`, QA 57-17 ...)", "the verdict is posted to the main thread", "one line per verdict **change**". |
| 57-18 | Low | **Fixed** (nit) | §7.2 "only when the lock is active **and** VoIP can ring at all: calls unmanaged, or managed with `calls_enabled`"; "`Level::Action` like `Kind::NoNotificationAccess` ... no parent notification" (code check: `attention.rs:28-33` has only `Alarm`/`Action`, and `NoNotificationAccess` is Action, so the QA's "Warning" was wrong and the note is right); §2.8 and check 10 describe the kiosk-on FSI flash. `voip_usage_v1` is still never read by the server (N9). |
| 57-19 | Low | **Fixed** | §2.7 `automatedStart` for `PinLockRuntime.show`, `VoipWakeActivity.intent`, `LockActivity.start`, `HomeFront.intent` + a source scan (all four start sites exist: `VoipWakeActivity.kt:51`, `ui/LockActivity.kt:287-291`, `HomeFront.kt:39`); §5e "**Separate hardening, not the fix for 101**" and "The deliverable is the diagnostic". |
| 57-20 | Nit | **Fixed** | §1 cites `ElementCallActivity.kt:209-211` and `EnforcementPlan.kt:246` (both verified in code: the denial is the `denyCallPermissions = callState.managed` line); the privacy-dashboard claim is only in §13; the answered §13 item is gone. |

Result: 17 fixed, 3 partial (57-3, 57-8, and 57-1's `covered` half, which turns out to be a new contradiction, N1).

## 2. The user's decisions

| Decision | In the note | Verdict |
|---|---|---|
| Q-A: launcher-activity call screens don't ring over the lock | §0.5, §2.3 `neverTrusted`, §9. Fine. What the kid sees for such an app (does the FSI still go out and flash the chats for up to one poll?) is not said: N10. | Correct |
| Q-B: only screens our own sends opened; FSI-opened stays trusted through the answer | §0.4, §2.3 rule 1 "A class trusted by the FSI stays trusted through the answer and the call (WhatsApp's one `VoipActivityV2`)". Decline -> COVER tested. | Correct |
| Remembered call screens per app, keyed on version and signer | §0.6, §3.3 (`"<package>\|<class>\|<versionCode>\|<signerSha256>"`, dropped on update/signer change/package callbacks), tests. Contradicted by the `covered` rule for Element (N1); "oldest dropped" in a string set (N8). | Correct in intent, broken for Element X (N1) |
| Q-C: Android 15+ only | §0.7, §2.1, §7. | Correct |
| Q-D: kiosk off, start lock task over the call once its screen is seen | §0.8, §2.6. Only on the path where the lock has already covered the call; see N3. | Partial |
| Q-E: READ_PHONE_STATE only for apps whose Phone group the denial greys | §5d `target`. | Correct |
| Q1: quiet ring without usage access | §2.8, §0.2, check 10. The per-ring bookkeeping contradicts §2.1: N4. | Correct in intent |
| Q2: accept the revoke | §0.12, §5d. | Correct |
| Generic IN_CALL replaces the Element channel rule | §2.2; `ELEMENT_CALL_CHANNEL` goes; Element's measured shape still counts; ranking for the reopen and `ringCallServiceUp`; tests in §9. In `voipExemption` (`L/lock/VoipCallPlan.kt:377-379`) an IN_CALL notice only matters for the record's package, after a ring, and after the grace only with audio `MODE_IN_COMMUNICATION`, so the wider rule adds little exposure. | Correct |
| Usage access mandatory | §0.1, no fallback. | Correct |
| Manifest `showWhenLocked` not used | §1 "Measured ...: Manifest `showWhenLocked` is no shortcut". | Correct |

## 3. New issues introduced by the revision

### High

**N1 - `covered_v1` makes Element X's call screen untrustable, so the design's own Element flow fails.**
- §2.4: "A COVER whose cause is a stray RESUMED adds that class to `covered` (§3.3)".
- §2.3: `neverTrusted` includes "any class in the package's `covered` set"; §3.3: "Never a never-trusted class, never a class in `covered_v1`"; `covered_v1` is persisted.
- §2.3's Element walk-through: the kid answers on `IncomingCallActivity`, "`ElementCallActivity` comes up untrusted, the lock covers it". By §2.4 that COVER puts `ElementCallActivity` into `covered`.
- The same walk-through then says Svar "so `ElementCallActivity` resumes as our send's - trusted and remembered". Under `neverTrusted` it is not learnable, and §9 tests exactly that ("a `covered` class is never learned by the button").
- Result: Element's call screen is never trusted, the lock covers it on every call, and the `covered_v1` entry survives until an app update. §0.6's "from the second call on, answering on Element X's own ring screen needs no extra tap" and §10 check 7 cannot be met.
- The QA asked for something narrower: "a class that already caused a COVER **in this ring**" never learned **by the button** (57-1).
- Fix: scope `covered` to the reopen path (a `REOPEN` send never learns a covered class); let `ANSWER` and `RING_SCREEN` sends learn it and remove it from `covered_v1`; or keep `covered` per ring. Then re-state the §9 tests and mutation so they cover both.

**N2 - The watcher's SOS row loses a race against rows 3 and 4, and cannot see the SOS app.**
- The query is package-filtered (§3.2, `setPackageNames`), so the watcher never sees the SOS activity. It sees only the call screen going PAUSED, then STOPPED.
- Row 4 (nothing resumed, trusted class in `visible`, no lock task: COVER) and row 3 come before row 5 (a trusted class STOPPED within `SOS_GESTURE_WINDOW_MS` of a screen-on, an SOS app present).
- With the kiosk off, the PAUSED-not-yet-STOPPED interval is the first thing a poll can see after the gesture. Row 4 matches, COVER is chosen, and `coverVoipCall`'s guard ("not while a probable SOS is marked") does not hold because the mark is only set by row 5.
- That puts the lock over the SOS countdown, which is what QA 57-3 and the user's "Emergency ... always win over VoIP" forbid. The kiosk-on side is safer: row 3 allows the PAUSED state and row 5 follows at STOPPED.
- Fix: evaluate the SOS test first. Let row 5 match on PAUSED or STOPPED within the window of a screen-on with an SOS app present, or hold rows 3/4 for a short debounce when a screen-on was inside `SOS_GESTURE_WINDOW_MS`. Add `CallScreenPlanTest` for "PAUSED only, screen-on 1 s ago, SOS app present -> SOS".
- Related, Low: row 5 needs a screen-on edge (`lastScreenOnElapsed`, as `sosGestureLikely` does today, `L/lock/RefrontPolicy.kt:219-220`). If the gesture leaves the screen continuously on, the call screen is covered with no SOS mark. Name the assumption or add check 12's case.

### Medium

**N3 - Q-D is met only when the lock has already covered the call, and its trigger query races the stop.**
- §2.6 starts lock task from the lock's `onResume`. If the call screen is trusted (the FSI-opened or a remembered class), `voipLockYields` is true at ScreenOff (IN_CALL, STALE, `trustedKnown`), so the lock is never shown over a call answered while unlocked. No cover, no lock-task start. Recents and split screen stay usable over the call: QA 57-8's scenario, unchanged for the common case from the second call on.
- §10 check 11 ("a call answered while unlocked, then locked: the lock covers it and starts lock task") therefore holds only for an untrusted screen.
- When the lock does cover, the one query at `onResume` is made right after the call screen was told to pause. `lastCallEvent` is then PAUSED (STOPPED comes a handler hop or more later, and the table's "PAUSED ... none -> no" has no retry). Result: no lock task, silently.
- Fix: say what starts lock task for a trusted screen (for example, at ScreenOff with the kiosk off and no lock task running, treat the call as `voipYields = false` once so the lock covers, starts lock task, and the kid taps "Tilbake"); and re-query on STOPPED/after a short delay, with a bounded retry, instead of one read at `onResume`. Test: "PAUSED at the read, STOPPED 300 ms later -> start".

**N4 - The quiet ring's per-ring bookkeeping reads the lock phase that is NONE without access (§2.1 vs §2.8).**
- §2.1 puts `ringId`/`ringingPackage` and the power-button silence (`screenOffSilencesRing`, `L/lock/PinLockRuntime.kt:742`) in the `phase` (lock) row. `VoipCalls.ringId` derives from `phase == RINGING` (`L/lock/VoipCalls.kt:97-99`).
- §2.8's `voipQuietRingWanted` needs `silenced` for this ring and runs on `callPhase`. Without access `phase` is NONE, so `ringId` is `null` and the power button cannot silence the quiet ring. `Avvis` does not exist without the card.
- Fix: a `callRingId`/`callRingingPackage` on `callPhase` for `VoipRinger` and `:742`; list them in the §2.1 table and the read-site scan.

**N5 - Row 8 is unreachable, or, if checked first, defeats the 3 s wait.**
- Read top to bottom, "no trusted class at all in an IN_CALL ring" is only reachable when nothing is resumed (row 6/7) or something is (row 1), so it never fires.
- Read as a pre-check, it covers at once on the first poll after "Tilbake til samtalen" or Svar, before the app's first RESUMED has arrived. That is the cold start of 57-10 again, for any call with no trusted class.
- Fix: delete it, or state that it applies only after the wait and when `expecting` is false.

**N6 - A watcher COVER does not end the expectation.** §2.5's first non-NONE row yields for 3 s after a send regardless of the verdict. If the watcher covers at t+1 s (a stray from the reopen) and the lock pauses again before t+3 s, the loop yields instead of re-fronting. Say that a COVER clears `expect`, and that unlock, `VoipEnded` and a new ring clear it too. Test: "send, stray at 1 s, COVER, lock pause at 2 s -> Refront".

### Low

- **N7 - The Android 14 note is false during the prep release.** `voip_over_lock_unsupported` is reported from the prep release (§7.1/§7.4.1), but nothing is gated yet. §7.2's Android 14 sentence ("Calls ring quietly while the phone is locked and open after unlocking") has no prep variant, unlike the usage-access note. Give it the `gated` condition or send the field only from the gating release.
- **N8 - §3.3 details.**
  - "At most 32 entries each, oldest dropped" on a string set: a `StringSet` has no order. Store a timestamp or an ordered list, and pin it in the prefs-file test.
  - "The first signer" of `GET_SIGNING_CERTIFICATES` is ambiguous with a signing-certificate history. Say how a rotation is handled (fail closed: drop).
  - `neverTrusted` is read "at the ring's start, off the main thread", while the first RESUMED can come ~300 ms after the FSI. State that the learn waits for the read, and a missing read means "not trusted".
- **N9 - Contract hygiene.**
  - `voip_usage_v1` is still never read by the server (57-18 asked: use it or drop it). Only `voip_gated_v1` is.
  - The status body gets no byte-identical fixture, unlike `time_status_report.json`, `dns_upstream_status.json` and `power_report.json`. CLAUDE.md's pattern pins the launcher and server sides to one file, and the path lists in `launcher.yml` and `server-ci.yml` carry it. With `PolicyResponseCompatTest` and `step10` as two separate tests, the keys can drift. The "one commit in both directories" rule is stated (§7.1, §11 step 2) and fine.
- **N10 - Q-A's kid-visible behaviour is unspecified.** For an app whose ring screen is its launcher activity, the FSI send still goes out; its first RESUMED is never trusted, so the app's main screen flashes for one poll before the card. The note should say whether the ring is skipped (quiet ring only) or the flash is accepted, and Svar for such an app can never work through the card.
- **N11 - A failed API (flag-off 15 build) is a permanent silent COVER.** The fail streak (§3.2) turns every call into a cover with no report to the parent. A `voip_over_lock_unsupported`-style signal for "the query failed N times" would let the PWA say so.
- **N12 - (d)'s table.** "GRANTED (ours)" cannot be told from a grant the kid gave; Q2 accepts that for targets. Say that the revoke applies only to packages in the `callPermissionTargets` list, never to other apps.

## 4. Specific checks asked for

- **3 s "expecting" window against the re-front loop.** Consistent with the 3 s wait: both come from the same `VOIP_SCREEN_LEARN_MS`, the lock's 200 ms pause check yields instead of racing the app's start, and the watcher is the decider afterwards. Open: what ends the window early (N6) and row 8 (N5).
- **SOS precedence.** The yield order is kept (`voipCall` before `sosFlow`, `L/lock/RefrontPolicy.kt:86-92`); (b) is correct; the cover guard is correct. The watcher's detection has the race in N2.
- **Kiosk-off lock-task order (cover, then lock task, then "Tilbake").** The order is right and explains why PiP is not entered (NO_USER_ACTION lock + STOPPED check). It is incomplete in two ways (N3).
- **Pin following the call without access.** Consistent: `eligible` is access-independent (`VoipCalls.kt:226`), `voipFacing` keeps `callPackage`, `pinnedPackage` is `callPackage`. The quiet ring's ring id is the one thing that still reads the lock phase (N4).
- **Remembered-screens keys.** The key `package|class|versionCode|signer` is sound and class names with `$` do not clash with `|`. Contradicted for Element by `covered` (N1); ordering and signer details in N8.
- **Two-release rollout and the contract.** Ordered correctly; `launcher-v0.34.0` and `server-v0.33.0` are the current tags, so `launcher-v0.35.0`/`server-v0.34.0` then `launcher-v0.36.0` are the next ones; the server's fields, words and Attention row ship in the prep commit with the launcher's. Open: N7 (prep wording) and N9 (fixture, `voip_usage_v1`).

## 5. Code spot-checks

All opened and correct (line numbers as cited): `L/lock/PinLockRuntime.kt:350, 522, 630, 742, 823, 1070, 1285-1286, 1395-1403, 1665`; `L/lock/PinLockActivity.kt:366` (the `voipListener`); `L/lock/KeyguardCover.kt:298`; `L/lock/LockTaskRoot.kt:50` (`voipCall && !kioskOn`); `L/lock/RefrontPolicy.kt:86, 200, 219-220`; `L/lock/PinLockState.kt:177` (`showVoipCall`); `L/lock/VoipCallPlan.kt:22, 344, 377-379` (the Element channel rule, `voipExemption`, `continueCall`); `L/lock/VoipCalls.kt:97-101, 226, 387-391`; `L/lock/LockTaskChrome.kt:148-151`; `L/server/LockTaskHelpers.kt:188-201`; `L/server/AppEnforcer.kt:635-670`; `L/server/EnforcementPlan.kt:246, 275-276`; `L/server/OwnPermissions.kt:52-55`; `L/server/dto/StatusReportRequest.kt:252-267`; `L/server/BatteryExemption.kt:25-38`; `L/timerules/ScreenTimeTracker.kt:74` (the `exempt` flag only matters when `pinLocked`, so the lock-phase column is right); `L/ui/LockActivity.kt:287-291`; `L/lock/HomeFront.kt:39`; `L/lock/VoipWakeActivity.kt:51`; `server/src/kid_lock.rs:409-460`; `server/src/attention.rs:28-33, 586`. The note's one stale reference: §1's "on `main` 20320912" is older than the current head, which is only a label.

## Verdict

**Not ready: two blocking items, then ready.**
- **N1** (Element X's call screen can never be trusted, because `covered_v1` is persistent and blocks the answer and FSI sends). Scope `covered` to the reopen path or to the ring, and fix the §2.3 walk-through, §3.3 and the §9 test and mutation to match.
- **N2** (the SOS row loses to rows 3/4 with the kiosk off, so the lock can cover Emergency SOS). Put the SOS test first and match it on PAUSED.

N3 to N6 should go into the note in the same pass, since each is a few lines and changes what the code is built to do. N7 to N12 can be handled in code QA.
