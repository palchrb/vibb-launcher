# QA/security code review: step 1 (own build + fail closed)

Reviewer: QA/security, 2026-10-04. Diffs: S `test-harness..handy`, L `build-without-tsnet..handy`.
Spec: 01-own-build.md (incl. decisions + status) and qa-01-02.md (01 criteria). Both suites run here and pass:
server `cargo test` 29/29, launcher `testDebugUnitTest` green. **[inferred]** = not proven in this code (AOSP/GitHub
behaviour or a device test is needed). Everything else was checked in code. Severity: blocker / should-fix / note.

## Findings

1. **blocker. The system dialer is now a free keypad on every managed phone.** L `EnforcementPlan.kt:253-259` puts
   the dialer in `neverRestrict` for every allowlist, and `AppEnforcer.kt:137-153` unsuspends and unhides it.
   `AppFilter.kt:24` hides only suspended apps, so the Phone icon appears on Home. Nothing sets `DISALLOW_OUTGOING_CALLS`
   (grep: no `DISALLOW_OUTGOING_CALLS`/`DISALLOW_SMS` in `app/src/main`). Upstream suspended a dialer the parent had not
   allowlisted. After step 1 the kid can call any number, run MMI/USSD and change call forwarding, and anyone can call
   the kid. Decision 1 did ask for "never suspended", but the same decision also says the keypad must not be a bypass.
   **Fix (in step 1, not 02):** when managed (`allowlist != null`, no override) and the dialer is not on the allowlist,
   set `DISALLOW_OUTGOING_CALLS` (the platform still allows emergency calls) and leave the dialer out of `AppFilter`.
   Clear the restriction when unmanaged or under override. Add a unit test on the plan (a `restrictOutgoingCalls` flag).
   Device test: 112 still connects, and `*21*…#`/USSD from a `tel:` link is refused **[inferred: device]**. Until this
   lands, step 1 must not reach a kid's phone.
2. **should-fix [inferred: AOSP]. Leaving the dialer out of `kioskPackages` does not keep it out of kiosk.**
   `LockTaskController.isEmergencyCallIntent` exempts any task whose component package is
   `getSystemDialerPackage()`, whenever `LOCK_TASK_FEATURE_KEYGUARD` is set. The launcher now always sets it
   (`EnforcementPlan.kt:254`). So `VIEW tel:` from an allowlisted app reaches the keypad in kiosk anyway. The comments at
   `EnforcementPlan.kt:238-242` and `AppEnforcer.kt` claim otherwise. **Fix:** rely on #1's restriction, correct the
   comments, and add this path to the T6/T10 device checks.
3. **should-fix. Known gap: an override or pause ends open while the cache is unusable.** `MdmSyncWorker.kt:140`,
   `:501` and `SettingsFragmentLauncher.kt:232` do nothing on `KeepCurrentState`. `OfflineOverride.activate`/`apply(null)`
   has already lifted every suspension, kiosk, VPN, sideload and browser lock. The phone then stays fully open, with
   `lockReason` stuck at NONE (bedtime is off too), until a fresh policy is accepted. This is unlikely: in practice it
   needs a DTO-incompatible cache after a launcher update, plus an offline phone or a failing server. It is reported as
   `cache_corrupt`. **Fix:** in the same `commit()` as the cache (`storeAcceptedPolicy`), persist a minimal
   last-enforced plan (allowlist as a string set, `kioskDesired`, `lockTaskFeatures`). Use it as the fallback when an
   override or pause has ended, and at the latest when `Corrupt` is seen; if that is missing too, use `allowlist=[]`.
   Add a `choosePolicy` test for it.
4. **should-fix. Setting the clock back extends a pause indefinitely.** `RestrictionsPause.kt:14-15` rejects only an
   end time more than 2 h ahead. Setting the clock back by less than the remaining time, again and again, keeps
   `now < pausedUntil`. During a pause the Settings app is unsuspended, and no `DISALLOW_CONFIG_DATE_TIME` is set anywhere,
   so the kid can do this. A good sync does not end a pause. `OfflineOverride.kt:46` has no guard at all (pre-existing,
   but it is bounded by the next good sync). The test "setting the clock back doesn't lengthen a pause"
   (`RestrictionsPauseTest`) covers only the >2 h case, so its name overstates what it checks. **Fix:** also store
   `SystemClock.elapsedRealtime()` at the start and end the pause when either clock says 2 h have passed (a reboot ends
   it). Apply the same rule to the override. Add a test where the clock is set back within the window.
5. **should-fix (server, outside the diff, same class as §3.1). The heartbeat bootstrap fails open on a DB error.**
   `device_api.rs:459-470` reads the existing allowlist as `.ok().flatten().flatten().is_some()`, so a read error
   (SQLITE_BUSY after the 5 s timeout) counts as "not set". The parent's allowlist is then overwritten with every
   installed app, permanently. The read and the write are also a TOCTOU against `toggle_app`. **Fix:** one statement,
   `UPDATE device_policy SET allowlist_json=? … WHERE device_id=? AND allowlist_json IS NULL`, and log the error.
   Related: `devices.rs:607-620` `remove_from_allowlist` treats a read error or corrupt JSON as `[]` and silently does
   nothing, so an "unchecked" app stays allowed; `add_to_allowlist` (`:572`) overwrites a corrupt list. Return an error
   to the admin instead.
6. **should-fix. `update.sh` can leave the server stopped.** `:47` uses `curl -sSL` without `-f`, so a 404 or GitHub
   error page is saved as the tarball. The service is stopped at `:50`, before the tarball is extracted or checked. Any
   later failure under `set -e` exits with the service down and no trap: `tar` on the HTML at `:74`, a `cp` in the backup
   on a full disk at `:62-67`. The phones keep their cache (they fail closed), but commands, locate and policy changes
   stop. **Fix:** `curl -fsSL`, extract and check that `kid_phone_server` exists in `mktemp -d` before
   `systemctl stop`, and use `trap 'systemctl start kid-phone-server' ERR` after the stop.
7. **should-fix. Backup pruning can delete the only good rollback point.** `update.sh:70` keeps the newest 3 before it
   knows whether the new version starts. After a bad update (migrations already run), each retry backs up the migrated
   DB and the broken binary. Three retries evict the pre-migration copy that DEPLOY.md's rollback needs. **Fix:** prune
   only after `systemctl is-active` succeeds, or never delete the newest backup whose binary differs from the installed one.
8. **should-fix. The runbook still puts the release secrets in repo scope.** 01-own-build.md §1.1 (lines 19-21) runs
   `gh secret set … -R palchrb/kids-launcher-mdm` with no `--env release`. Repo secrets can be read by any workflow
   run on any pushed branch, which bypasses the `release` environment's reviewer (`android.yml:143`). Also, if the
   environment does not exist, GitHub creates it on first use with no protection **[inferred: GitHub behaviour]**.
   **Fix:** `gh secret set --env release …` in §1.1. Create the environment before the first tag.
9. **note. The master-ancestry check is advisory.** `android.yml:74-85` runs from the tagged commit's own workflow, so
   anyone who can push could tag a branch commit whose workflow drops the check. The real gates are the environment
   reviewer and the tag rule. Add a tag ruleset that restricts who may create `v*` tags. The reviewer must check that
   the tag commit is on master.
10. **note. CI supply chain.** The action SHAs (`android.yml:42,44,59,146,215`, `action.yml:13`) could not be checked
    against their tags here (no network). Check them once with `gh api repos/<o>/<r>/git/ref/tags/<tag>`. `setup-go`
    caches by default, so `release-build` restores a GOCACHE that `go mod verify` does not cover. Set `cache: false`
    for the release path. The APK that gets signed is only as trustworthy as `release-build`'s Gradle and Maven chain;
    splitting into jobs protects the key, not the APK contents.
11. **note. A server 500 is invisible to the parent.** The launcher maps non-2xx to `UNREACHABLE`, and `policyState`
    then says `"ok"` (`PolicyGate.kt:612-616`). The device page parses a corrupt allowlist as empty (`devices.rs:276-280`)
    and shows no warning. A device stuck on 500s therefore looks healthy. Report `"server_error"` for 5xx, or show
    `build_policy`'s error on the device page.
12. **note (pre-existing, outside the diff, but it still fails open).** The launcher sets no `DISALLOW_FACTORY_RESET`,
    `DISALLOW_DEBUGGING_FEATURES`, `DISALLOW_CONFIG_DATE_TIME` or `DISALLOW_SAFE_BOOT`. The first heartbeat bootstraps
    the allowlist to every controllable app, Android Settings included (`device_api.rs:452-485`). Without a server PIN,
    the launcher's Settings is open to anyone (comment at `SettingsFragmentLauncher.kt`, pause handler), so the server
    URL can be re-pointed. Track these in PLAN.

## Checked and fine

- `build_policy` (S `device_api.rs:108-216`): missing row, corrupt allowlist and every DB read are `?` and give an empty
  500. A NULL allowlist stays null. The command is popped last with one `UPDATE … RETURNING`. The tests cover missing
  row, corrupt JSON, three dropped tables, no command consumed on a 500, 4 concurrent polls delivering once, and the key
  snapshot with a non-null check. `create_device` runs in a transaction, and a test injects a failure with a trigger.
- `PolicyGate`: QA #11 (`policy_ever_applied` set in the same `commit()` as the cache, Absent+applied =
  `KeepCurrentState`, Corrupt+null = REJECT_SUSPECT) and #12 (`fresh_decode_failed`) are implemented and tested. The
  PIN hash, commands and uninstalls are skipped for a rejected policy. A new package fails closed on `KeepCurrentState`.
- Pause: PIN required, unavailable without a PIN, shares the lockout, ends after 2 h (except for #4), reported.
- `install.sh`/`update.sh` validate `KPS_REPO` before the `sed` into `actions.sh`. The backup dir is root-owned 0700
  outside `data/`. The unit has `PrivateTmp=true`, so the fixed `/tmp` tarball path cannot be planted by the service user.
- CI: secrets appear only in `release-sign` (no checkout, no Gradle). The keystore is removed in an `always()` step.
  Workflow `permissions: contents: read`, and only `publish` writes. Strict tag regex, RC as a prerelease with
  `makeLatest: false`. Checks for cert digest, debuggable, and package name. No `pull_request_target`.
