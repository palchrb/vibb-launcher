# QA cleanup round: package rename, monitoring removal, retention, crash reports, qa-11b fixes, smoke test (fbbe6501..13bf7e74)

Reviewed 2026-10-06 on `master`, code only (nothing changed). L = `launcher/app/src/main/java/com/kidslauncher/mdm/`, S = `server/`. H = fix before anyone runs it on a real phone; M = fix in this round; L = fix or record.

## Findings
1. **H, `scripts/smoke-test.sh:98-115, 208-211, 313-332`: without an emulator console the script places real calls and reports passes.** `console()` only fails on a `^KO` reply. It ignores `adb emu`'s exit status and its `error: ...` text, so `console_ok=1` on a physical phone (which the header at :3 invites) or whenever `adb emu` can't reach a console. `adb emu` always connects to the adb client's own loopback, so this also happens with `ADB="adb -H vm"` and no token. The script then sends real `ACTION_CALL`s to `+4799999999` and `91234567` (+47 9123 4567 on a Norwegian SIM). `gsm cancel` is a no-op, so nothing hangs them up. "outgoing call ... is stopped", "call notification cleared" and "no call screen" PASS vacuously. Reproduced with a fake adb: both `am start ... tel:` calls were made, and the run ended 7 PASS / 4 FAIL. **Fix:** make `console` fail on a non-zero `adb emu` status (`PIPESTATUS`) or when the reply has no `OK` line. Run the call sections only when `getprop ro.kernel.qemu` is 1 (or with an explicit `REAL_PHONE=1` plus your own numbers). Reject short or emergency-looking numbers in the env overrides. Fall back to `input keyevent KEYCODE_ENDCALL` to hang up.
2. **M, `scripts/smoke-test.sh:317-326, 339-349`: the blocked-outgoing and one-call checks pass on silence alone.** Each passes when no new modem call shows up after 4 s. A failed `am start`, an intent that never reached Telecom, or `DISALLOW_OUTGOING_CALLS` all pass too. **Fix:** after `logcat_clear`, require Telecom's `REQUEST_DISCONNECT, Canceled from Call Redirection Service` or our `KidInCallService` line "Disconnecting a not-allowed outgoing call". For one-call, require the `second (outgoing )?call` line, which is currently only printed.
3. **L, `scripts/smoke-test.sh:127/251/294, 288-289, 138-145`: three checks are looser than the doc says.** (a) `top_is InCallActivity` also matches the system dialer's `com.android.incallui.InCallActivity`, so "our call screen in front" still passes after the dialer role is lost. (b) The `KidInCallService ... Rejecting` fallback passes "screened out" when screening failed open and the call already reached the in-call service. (c) When logcat has no `FILTERING_COMPLETED`, the `dumpsys telecom` fallback takes the last such line of any earlier call, so a stale `Reject` gives a false PASS. **Fix:** match `$PKG/com.kidslauncher.mdm.calls.InCallActivity`. Count only `KidCallScreening` (report the fallback as a FAIL). Drop the dumpsys fallback. The real line `FILTERING_COMPLETED, [Reject, logged, mCallBlockReason = 1, ...` is matched correctly by `Reject` (`BLOCK_REASON_CALL_SCREENING_SERVICE` = 1 per android-36).
4. **L, `docs/testing/emulator.md:104-107`, `scripts/smoke-test.sh:24-25, 93-95, 52`: the remote setup is unsafe or doesn't work.** `adb -a nodaemon server` gives anyone on the network an unauthenticated shell on the emulator. `CONSOLE_HOST=vm` would send the console token in cleartext, and the emulator console normally listens on loopback only, so the documented command most likely just SKIPs. The header says "without -H in ADB, `adb emu`", but the code never checks for `-H`. The token itself is never echoed (it goes through the `printf` builtin, and replies are filtered), which is fine. Screenshots go to `./smoke-<date>`, which isn't gitignored. **Fix:** document only `ssh -L 5037:127.0.0.1:5037 -L 5554:127.0.0.1:5554 vm` with `CONSOLE_HOST=127.0.0.1`, and refuse a non-loopback `CONSOLE_HOST`. Use TCP (never `adb emu`) whenever `ADB` has `-H`. Add `smoke-*/` to `.gitignore`.
5. **M, rename rollout, `S/DEPLOY.md:113-115`, `S/src/config.rs:113-121`: neither release order is safe as documented.** (a) Launcher builds before 865ab6f8 (every phone still on `com.kidslauncher.mdm`, such as the Moto on v0.7.9) install whatever APK the `is_launcher` row serves. The old `MdmSyncWorker` passes it straight to `installSilently`, with no row-package or `pendingApkCheck`. So the first `launcher-v*` built as `me.vibb.launcher` is silently installed as a second app on every phone not yet re-provisioned. (b) As soon as the server is updated, an `.env` still holding the old default is read as `me.vibb.launcher/...`, so the provisioning QR fails until a renamed build is `releases/latest`. **Fix:** spell out the order in DEPLOY.md: re-provision the old phones (or disable the launcher row) first, then tag the renamed launcher, then update the server.
6. **L, L `calls/BlockedCallLog.kt:28-32`: the prune deletes more than our own blocked calls.** It deletes every `BLOCKED_TYPE` row older than 30 days, including other block reasons such as the system blocklist (`BLOCK_REASON_BLOCKED_NUMBER` = 3). It also trusts the wall clock: a clock set more than 30 days ahead deletes every existing blocked row. Outgoing (emergency) calls, missed and rejected rows are never touched, and 30 days is far outside the callback window. **Fix:** add `block_reason = 1 AND call_screening_component_name = <our KidCallScreeningService>` to the selection. Use `min(now, newest call-log DATE) - 30 d` as the cutoff.
7. **L, `S/src/handlers/calls.rs:345-357`, `S/src/retention.rs:65-73`: the downgrade warning softens after 30 days.** The "launcher stopped reporting that it enforces calls" warning depends on some older `device_status` row having the capability. Status pruning deletes those rows after 30 days, and a downgraded (tampered) launcher then reads as "does not enforce calls yet. Update the launcher." **Fix:** keep a `devices.had_call_policy` flag. Also fix the stale comment at `S/src/handlers/device_api.rs:803-807`, which still names the removed `run_location_pruning` and the 30-day limit.

## Status (fix round, 2026-10-06)
1. **Fixed.** Before any call the script requires `ro.kernel.qemu` or `ro.boot.qemu` = 1 **and** a console that
   answers `avd name` with OK (and the same AVD as `ro.boot.qemu.avd_name`, when the device reports one), and aborts
   otherwise. `console` fails on a non-zero `adb emu`/TCP status, an `error`/`KO` line, or no final `OK`. An EXIT
   trap (also after Ctrl+C) runs `gsm cancel` for every number the script called or dialled, then `KEYCODE_ENDCALL`
   if anything is left. `ALLOWED_NUMBER`/`UNKNOWN_NUMBER` must be E.164 with 8-15 digits and `ALLOWED_DIAL` 8-15
   digits; short or emergency-like overrides are refused. There is no `REAL_PHONE` mode. Tested against a
   simulated adb and console: not an emulator, no console, wrong token, other AVD, and SIGTERM mid-call.
2. **Fixed.** Blocked outgoing requires Telecom's "Canceled from Call Redirection Service", our new
   `KidCallRedirection` line "Cancelling a not-allowed outgoing call" or `KidInCallService` "Disconnecting a
   not-allowed outgoing call". One-call requires the "second (outgoing )?call" line. Silence is a FAIL.
3. **Fixed.** (a) `top_is` matches the exact `$PKG/com.kidslauncher.mdm.calls.InCallActivity` (and
   `.lock.PinLockActivity`). (b) Only `KidCallScreening` counts. A reject by the in-call service alone is a FAIL
   ("screening failed open"). (c) The `dumpsys telecom` fallback is gone. Only `FILTERING_COMPLETED` logged after
   the step's `logcat -c` counts.
4. **Fixed.** `adb -a` and `CONSOLE_HOST=vm` are gone from the doc and the script header. Remote use is an ssh
   tunnel (`-L 5038:127.0.0.1:5037 -L 5554:127.0.0.1:5554`) with `ADB="adb -H 127.0.0.1 -P 5038"`. A non-loopback
   `CONSOLE_HOST` is refused. With `-H`/`-P`/`-L` in `ADB`, the console is always TCP with `CONSOLE_TOKEN` (never
   `adb emu`). `smoke-*/` is gitignored.
5. **Fixed.** DEPLOY.md, "Rollout of the package rename": (1) re-provision the phones still on
   `com.kidslauncher.mdm`, or untick "Enabled" on the launcher row; (2) tag the renamed launcher as the stable
   "latest" release; (3) only then update the server. It also says the provisioning QR doesn't work until (2) is
   done (adb meanwhile).
6. **Fixed.** The delete also requires `block_reason` = 1 (call screening service) and
   `call_screening_component_name` = `<pkg>/com.kidslauncher.mdm.calls.KidCallScreeningService`. The cutoff is
   `min(now, newest call-log date) - 30 d`. There is no prune while the clock is earlier than the build's commit
   (`BuildConfig.GIT_COMMIT_TIME_MS`, from `git log -1 --format=%ct`). Tests: `BlockedCallRetentionTest`.
7. **Fixed (smaller variant).** No `had_call_policy` flag. Status pruning also keeps each phone's newest report
   with `call_policy_v1`, so `calls.rs`'s `EXISTS` check keeps finding it (test
   `pruning_keeps_the_last_report_that_enforced_calls`). The stale `run_location_pruning` comment in
   `device_api.rs` is fixed.

## Verified (no finding)
- **Rename:** no old-package string literal is left in launcher main. Provisioning, CI badging, `dev-rebuild.sh`, the FCM/Firebase notes and the role/listener/admin commands use `me.vibb.launcher`. The self-update guard (`MdmSyncWorker.kt:513-518`) and `pendingApkCheck` use `context.packageName`.
- **Migrations:** 0037 is safe (no unique index on `package_name`, only `is_launcher` rows are changed, and tested). 0038 drops tables nothing references. 0039 and 0040 are additive.
- **Removed features:** no route, nav link, template, setting, manifest entry or permission is left. Old launchers get a 404 on journal/history uploads, which is harmless. The policy JSON only gained `dns_log_enabled` (snapshot and compat tests updated). Old backups are covered in DEPLOY.md.
- **Retention:** the kept location row is the one the locate page shows (`locate.rs:142`, same `captured_at DESC, id DESC` order). `time_lifts` are not pruned. The newest status report per phone is kept.
- **Crash reports:** only class names and frames are sent (never a message). The server also drops any line that isn't a trace line. The crash card has no POST. The new POSTs (location retention, DNS log) redirect to their own path, so `scroll-restore.js` restores the scroll position.
- **Emergency calls:** the script builds no emergency number of its own and prints the emergency check as a manual step.
