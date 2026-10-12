# QA 24b code review: windows that lock again, the parent-PIN vault, unseen boots, safe mode

QA, 2026-10-11. Branch `relock-24b` at `6905ffbf` on main `2481de32`: de4bd538 (S1a), e519df85 (S1b), 9dc0cbf2
(S2), 31a6bcd0 (S3), the fixes 0168dd45 and 6905ffbf, and the docs bbee1dda. Also branch `relock-24b-s4` at
`ff5c1cb9` (S4, gated on device check 6). Read against `24b-override-relock.md` (body, design QA, decisions,
"Open questions: resolved", implementation status and deviations), its base `24-parent-pin-hardening.md`, and the
three `CLAUDE.md`s. [AOSP] means checked in `android14-release` sources (frameworks/base, Settings,
PackageInstaller, CertInstaller, Connectivity, Wi-Fi).
Threat model: a motivated kid with the phone in hand, no adb, and a full parent-PIN window now and then.

Launcher paths below are under `launcher/app/src/main/java/com/kidslauncher/mdm/`.

**Builds (the branch's worktree):**
- Launcher, `testDebugUnitTest assembleDebug assembleRelease -PwarningsAsErrors=true`:
  - green, 848 tests, 0 failures;
  - the only warning is the existing kapt source-version line;
  - `checkReleaseHasNoDebugHook` covers `PinVaultKeyReceiver`.
- Server:
  - `cargo test` passes 504 tests;
  - `fmt --check` is clean;
  - `clippy --all-targets` gives 14 warnings, all in code the branch doesn't touch.
- `relock-24b-s4`, targeted runs:
  - server `cargo test hardening|kid_lock|device_api`: 14 + 9 + 29 passed;
  - launcher `HardeningTest`: 8 passed.
- Three probe tests confirmed Medium 2, Low 1 and Low 2. They have been deleted.

**Verdict:** the engine is in good shape.
- The marker, `gen` and the snapshot logic hold.
- `check` never blocks, and the lock order is clean.
- The timer covers reboot, a time change, Doze and process death.
- The vault's verify and lockout carry-over are sound. The server side and the contract are tight.

Three ways remain to keep, or get back, a fully open phone:
- **High 1:** a device admin can't be hidden.
- **Medium 1:** the sync's relock can skip the closing `apply()`.
- **Medium 2:** an imposter that isn't allowlisted is never blocked.

The Lows are fail-open corners, report accuracy and PWA wording.

Counts: **1 High, 2 Medium, 12 Low.**

## High

1. **High: a sideloaded app made a device admin in a window can't be hidden or suspended, so it survives the
   relock with its accessibility service.**
   - **Where:**
     - `server/AppEnforcer.kt:372` and `:382` (`setPackagesSuspended`/`setApplicationHidden` in `apply()`);
     - `server/WindowRelock.kt:445` (only reported);
     - design §1.5's "Device admin apps" row ("a non-allowlisted or blocked package is hidden, and DPMS drops a
       removed package's admin [device]").
   - **What AOSP says:**
     - `PackageManagerService.setApplicationHiddenSettingAsUser` returns false with "Not hiding package …: has
       active device admin".
     - `SuspendPackageHelper` refuses with "has an active device admin".
     - `DeletePackageHelper` refuses the uninstall (`DELETE_FAILED_DEVICE_POLICY_MANAGER`).
     - `DevicePolicyManagerService.removeActiveAdmin` needs `MANAGE_DEVICE_ADMINS` (signature) or the admin
       itself. A device owner has no API to deactivate another app's admin.
     - Settings' `DeviceAdminAdd` has no device-owner or restriction check, so activation works in a window
       [device: confirm on the Jelly Star].
   - **Scenario:**
     1. During a pause the kid sideloads an APK with a `DeviceAdminReceiver` and an accessibility service that
        declares `isAccessibilityTool="true"`. The kid activates both in Settings.
     2. At the relock, `apply()` can't hide or suspend it (both calls are refused and only logged).
        `setPermittedAccessibilityServices(allowlist)` is refused because that service is enabled (DPMS
        `checkPackagesInPermittedListOrSystem`). So the list stays `null` (all permitted).
     3. The report says `admins_enabled`, `accessibility_enabled` and `failures: [permitted_lists]`. The PWA
        shows "Still on: accessibility service x; device admin x" with no advice.
     4. Outside windows the service keeps running. It is exempt from `setAccessibilityDataSensitive`, so it reads
        the parent PIN the next time anyone types it on our keypad. From then on the kid opens windows at will.
     5. The same shield keeps a sideloaded IME or notification listener alive. A device admin also gets
        `lockNow`. Since Android 14, `resetPassword` is refused for every non-DO admin.
   - **Fix (needs a user decision; a device owner can only report this):**
     - Correct the design row.
     - The relock reports it as its own finding ("can't be hidden: it is a device admin").
     - The relock denies the app's runtime permissions (`setPermissionGrantState(..., DENIED)` works on other
       apps) to reduce what it can do.
     - The PWA says plainly: "x is a device admin, so the phone can't hide or stop it, and its accessibility
       service can read the screen, including the parent PIN. Remove it: in a pause, Settings > Security > Device
       admin apps > x > Deactivate, then uninstall it. Then change the parent PIN."
     - **Consider:** a PWA-started pause (a server command, no PIN on the phone), so the parent never types the
       PIN on a phone that has a foreign accessibility service on. Or refuse the on-phone parent PIN while a
       non-allowlisted, non-system accessibility service is enabled outside a window.

## Medium

1. **Medium: the sync's relock skips `apply()` even when the sync's own `apply()` ran with the window still open.**
   - **Where:**
     - `server/MdmSyncWorker.kt:149`, `:160` and `:164`;
     - `server/WindowRelock.kt:213` (`fromSync = true`, always);
     - `server/WindowRelockStore.kt:239`;
     - `server/WindowRelockPlan.kt:315` (`applyNeeded`, whose doc assumes "with no window open").
   - **Scenario:**
     1. A pause has 2 s left. The kid taps "Sync now" in the same launcher Settings screen that holds the pause.
        An SSE nudge or the backstop timer at the wrong moment does the same.
     2. The sync's `AppEnforcer.apply(fromSync = true)` reads `isActive()` itself, sees the pause and releases
        everything: no kiosk, nothing hidden, no VPN, sideloading allowed, no tethering restriction, lists
        `null`.
     3. The pause ends during that apply, which takes seconds. `relockInSync` finds the relock due.
        `applyNeeded(fromSync = true, false, false)` is false, so step 3 is skipped and recorded as a success.
        Then the marker is cleared.
     4. `reevaluateLockReasonFromCache` doesn't re-apply: with no time rule active the lock key stays `"NONE"`
        (`timerules/TimeRules.kt:305`).
     5. The boundary alarm then finds no marker. The phone stays fully open until the next sync's `apply()`:
        30 min with SSE up (`push/SyncSchedule.kt:17`), 15 min when it's down.
     6. The same happens offline, when an override expires during the backstop's cached `apply()`.
   - **Confirmed by reading.** The skip itself is pinned by `WindowRelockPlanTest` "in the sync, apply runs again
     only when the credential or a block changed".
   - **Fix:**
     - **Simplest:** always run step 3 in the relock. That is one extra `apply()` per window, so drop
       deviation 4.
     - **Or:** `AppEnforcer` records whether its last pass saw a window open (a volatile set from its own
       `overrideActive`), and the sync's relock skips only when that pass was a closed one.
     - Add a test.

2. **Medium: an app sideloaded under a name that isn't allowlisted at the relock is only reported, never
   blocked, and shows as soon as the parent ticks that name.**
   - **Where:**
     - `server/WindowRelockPlan.kt:167` (`if (!allowlisted) return … REPORT`) and `:227`;
     - the PWA line `server/src/window_relock.rs:238`.
   - **Scenario:**
     1. In a pause the kid sideloads a fake `com.whatsapp`, signed with any key. It isn't allowlisted.
     2. At the relock it is hidden by `apply()` and reported in `blocked`. The device page says "Blocked:
        com.whatsapp … Apps not ticked under Apps stay hidden until you tick them." That invites exactly the next
        step.
     3. The parent ticks WhatsApp, meaning the real one. `relock_blocked` doesn't hold it, so `apply()` unhides
        the fake, pins it in kiosk, and puts it on the permitted accessibility and keyboard lists.
     4. The next window's snapshot contains the fake, so no later relock sees a change.
     5. This is design QA #1's imposter, through the parent's own tick.
   - **Confirmed by a probe test** (`signerCheck`, then `effectiveAllowlist` and `computeEnforcementPlan` with the
     name allowlisted, then the next relock).
   - **Fix:**
     - A package that is new or re-signed in the window and whose installer isn't Play or us goes into
       `relock_blocked` whatever the allowlist says (`expected = null`; it clears when it comes back from
       Play/us).
     - Play installs that aren't allowlisted stay report-only.
     - The PWA then says "Blocked (installed from outside Play): x - tick it under Apps and press "Allow these
       apps" only if it is yours". The `allow_button` rule already fits.

## Low

1. **Low: a Keystore failure while accepting a policy drops the tag, so the next re-wrap resets a running
   lockout.**
   - **Where:** `server/PinVault.kt:153-156`. `remove(TAG)` runs on `hmac(create = true)` failing twice. At
     `:163-166`, the next `accept` has no `previousTag` to carry over.
   - **Scenario:**
     1. A 24 h lockout is running.
     2. A sync hits two Keystore errors in a row, or a key that is there but unusable (`create = true` doesn't
        help then).
     3. The next sync makes a new key, a new tag and a new fingerprint. `rewriteFingerprint` doesn't match the
        stored one, so `prepareParentState` sees a "new PIN" and the kid gets 5 fresh tries at level 1.
   - **How likely:** rare, and the kid can't trigger it without root. `verify`'s failure path keeps the tag, so it
     is fine.
   - **Confirmed by a probe test.**
   - **Fix:** keep `TAG` (only set `REWRAP`) when the wrap fails in `accept`, as `verify` does. Or keep the
     previous fingerprint in its own key until a re-wrap has carried it over. Add the test.

2. **Low: a signer check that throws still clears the marker and deletes the snapshot.**
   - **Where:** `server/WindowRelockStore.kt:231-236` and `:268`. Only a throwing `apply()` keeps `pending`.
   - **Scenario:**
     1. `getInstalledPackages(GET_SIGNING_CERTIFICATES)` throws once at the relock (a dead binder, an OEM bug).
     2. The relock completes with `failures: [signer_check]`, and the snapshot goes.
     3. A same-name imposter installed in that window is never compared again (the next window snapshots it as
        is).
   - The same fail-open applies when the start snapshot couldn't be taken (`WindowRelock.kt:160-165`, then
     `snapshot == null` skips everything).
   - **Confirmed by a probe test.**
   - **Fix:**
     - Treat a `signer_check` failure like `apply`: keep `pending` and the snapshot, and the next trigger runs
       again.
     - Without a snapshot, block every allowlisted non-system package whose installer isn't Play or us and whose
       `lastUpdateTime` is after the window's start.

3. **Low: `relock_blocked` is re-evaluated only at a relock.**
   - **Where:**
     - `server/WindowRelockPlan.kt:201-237` (the only pruning);
     - `server/AppEnforcer.kt:1113` (new installs consult the set as is).
   - **Scenario A:**
     1. The parent sees "Blocked: org.example.chat", has the fake removed (the PWA's uninstall), and pushes the
        real app from the catalog, or the kid installs it in install mode.
     2. Outside a window there is no relock, so the real app is hidden on arrival and stays hidden until "Allow
        these apps" or the next window.
   - **Scenario B:** an install whose session completes between step 2 (signer check) and the sideload
     restriction in step 3 escapes the check. The new-package path then treats an allowlisted name as allowed.
     This is a tight manual race.
   - **Fix:** in the new-package handler while no window is open, drop a block whose `blockStays` is false. Block
     an allowlisted package that was added or replaced and whose installer isn't Play or us.

4. **Low: the CA and proxy resets remove everything, also what was there before the window, and also on
   calls-only phones.**
   - **Where:**
     - `server/WindowRelock.kt:428-432` (`uninstallAllUserCaCerts`);
     - `:400-426` (every saved network's proxy);
     - `runRelock` doesn't look at the allowlist.
   - **Scenario:**
     - A school's TLS-inspection CA, installed by the parent before 24b (or at any time on a calls-only phone,
       where Settings is always open), goes at every relock.
     - A school proxy set on a Wi-Fi network goes the same way.
     - On calls-only phones this protects nothing: the kid can re-add both at any time. That conflicts with QA
       #4's "calls-only phones aren't restricted beyond calls".
   - **Fix:**
     - Snapshot the user CA aliases (and the per-network proxies, if location allows) in `opened()`.
     - At the relock, remove only what was added: `uninstallCaCert(admin, cert)` per new certificate.
     - Skip both resets while `allowlist == null`.
     - This also removes the pre-S1a device check (question 1 below).

5. **Low: the window's alarm is armed only after the starter's `apply()` succeeds.**
   - **Where:** `server/OfflineOverride.kt:71-79` and `server/RestrictionsPause.kt:121-133`.
     `TimeRuleAlarm.schedule` is in the same `try` as `AppEnforcer.apply`.
   - **Scenario:** that `apply()` throws once. No alarm is set for the window's end, so the phone stays open past
     it while the screen is off. The relock then happens at the next screen-on (asynchronously, so the kid has a
     few seconds) or at the next sync (up to 30 min).
   - **Fix:** schedule the alarm in `opened()` right after the lift, independent of `apply()` (or in a
     `finally`).

6. **Low: every process start counts as `boot`, and Home isn't brought to the front then.**
   - **Where:** `Application.kt:303` and `server/WindowRelockStore.kt:265`.
   - **Scenario:**
     1. The launcher process dies in a window (a crash or LMK) and restarts after the window's end.
     2. The relock runs with trigger `boot` (the PWA says "after a restart") and `processStart = true`.
     3. The kid stays inside the allowlisted app they were in, outside lock task, until they go Home. Hidden apps
        are killed anyway.
   - **Fix:** pass `BOOT` only when `BootWitness` saw a new `BOOT_COUNT` in this process. Otherwise use a
     `process_start` handling (or `TIMER`) that brings Home.

7. **Low: on a phone that isn't device owner, the window notification is posted but never cancelled.**
   - **Where:**
     - `server/WindowRelock.kt:155` (posted unconditionally);
     - `:218` (`relock()` returns early when not device owner, so `finish` never cancels it).
   - **Scenario:** an enrolled phone whose device-owner provisioning failed keeps "Open with the parent code
     until 18:40 · Lock again" forever. "Lock again" then does nothing visible.
   - **Fix:** don't post it when not device owner, or cancel the notifications in `relock()` before the
     device-owner return.

8. **Low: `proxy_left` is worded as mobile data, and can be a false positive right after a Wi-Fi reset.**
   - **Where:** `server/WindowRelock.kt:441` checks every network (Wi-Fi included), but `server/src/window_relock.rs:255`
     says "a proxy on mobile data".
   - **Scenario:**
     - A Wi-Fi proxy that `updateNetwork` couldn't clear shows as "a proxy on mobile data".
     - Right after a successful clear, the connected network's `LinkProperties` may not have updated yet, so the
       relock reports one cleared and one "left" [device].
   - **Fix:**
     - Report which transport (`wifi`/`cellular`), or word it "a proxy on a network (mobile data or Wi-Fi)".
     - Read `proxy_left` after a short settle, or skip Wi-Fi networks whose config was just cleared.

9. **Low: the CA count is taken before the removal and never checked.**
   - **Where:** `server/WindowRelock.kt:429-430`. `CertificateMonitor.uninstallCaCerts` swallows `RemoteException`
     [AOSP].
   - **Scenario:** KeyChain fails to bind. The report says "removed 2 certificates" and the certificates stay.
   - **Fix:** re-read `getInstalledCaCerts` afterwards. Report the difference, and anything left as a failure.

10. **Low: the PWA keeps the "Blocked" warning and "Allow these apps" for 7 days after the parent allowed them, and
    never shows `end_windows`'s answer.**
    - **Where:**
      - `server/src/handlers/window_relock.rs:48-56`, where the notes come from the last relock report, which
        `allow_blocked` doesn't change;
      - the result `ended: pause` / `nothing was open` is stored but not shown.
    - **Scenario:**
      - The parent presses "Allow these apps", reloads, and sees the same warning and button. It looks as if
        nothing happened.
      - A command lost to a rejected policy (marked delivered when served) looks the same as one that worked.
    - **Fix:**
      - Hide the button and add "(allowed {time})" once an `allow_blocked` newer than the report's `at_ms` has
        been acknowledged.
      - Show the last `end_windows` result under the button.

11. **Low: the vault's one-time migration can run on the main thread.**
    - **Where:** `server/ParentPin.kt:71-73`. `status()` calls `vault(app)`, which runs `migrate` (Keystore
      keygen, an HMAC and three commits) once per process. `status` is called from `SettingsActivity` and
      `ParentPinDialog.opening` on the main thread.
    - **Scenario:** right after the update, offline, the parent opens Settings before any sync. A keygen in the TEE
      plus the commits freezes the UI for a few hundred ms [device]. It isn't an ANR.
    - **Fix:** run the migration from `Application.initRest` on IO, or from the first sync. `status` then only
      reads.

12. **Low: the unseen-boots advice uses today's safe-mode switch, not the one in force at the boot.**
    - **Where:**
      - `server/src/boot_witness.rs:40-44`;
      - `handlers/devices.rs` passes `policy.hardening.disallow_safe_boot`.
    - **Scenario:** a safe-mode boot happens while the switch is off. The parent then turns it on, or S4's
      migration flips every device. For up to 30 days the page says "Safe mode is blocked, so it was probably a
      start cut short", although it was safe mode.
    - **Fix:** say "was probably a start cut short" only when `last_at_ms` is after the switch was turned on (keep
      `disallow_safe_boot_set_at_ms`). Otherwise give both explanations.

## Deviations: verdicts

1. **Migration numbers: must change at merge.** Main's uncommitted `0062_catalog_app_pins.sql` (M10) takes 0062.
   The renumbering is safe (question 2).
2. **`opened()` lifts under the marker lock: right, and needed.** It closes the "marker without its window" race.
   `if (!lifted) lift()` keeps a correct PIN working when the marker fails. Adoption then covers it.
3. **Adoption of a marker-less window: fine.** Also needed for an `apply()`-durability gap after "Lock again":
   the flags are `apply()`, the marker is `commit()`.
4. **The sync's relock skips `apply()` unless steps 1-2 changed something: wrong as built.** It assumes the
   sync's `apply()` ran with the window closed (Medium 1).
5. **No Home at a process start: fine for a real boot.** Not for a crash or LMK restart (Low 6).
6. **Signers match on a shared certificate: fine.** `SelfUpdate.signers` uses the signing-certificate history.
   A lineage containing the old certificate needs the old key, so it can't be forged. Matches the self-update's
   rule.
7. **`relock.blocked` includes new apps that aren't allowlisted: fine to report.** Combined with "report only",
   it is Medium 2.
8. **The self-granted fine-location permission stays granted: acceptable.** Revoking a runtime permission kills
   the process. Note that a phone with the location policy "off" now holds it after the first relock.
9. **`failures` names: fine.** The same nine on both sides.
10. **The tethering and list tests in `EnforcementPlanTest`: fine.**
11. **Do Not Disturb in the existing line, in the warning style: fine.**
12. **The vault carries a lockout over at a re-wrap of the same salt: right.** It misses an accept-time key
    failure (Low 1). The shared `POLICY_CACHE_LOCK` is correct: no lock is ever taken in the reverse order.
13. **Debug-only `PinVaultKeyReceiver`: fine.** It is behind `DUMP`, in `src/debug`, and checked absent from
    release.

## The two questions

**1. Does `uninstallAllUserCaCerts` remove certificates installed for Wi-Fi (an EAP network)? No. Wi-Fi
certificates are separate from the user trust store.**
- **What the call removes** [AOSP]: `DevicePolicyManager.uninstallAllUserCaCerts` sends
  `new TrustedCertificateStore().userAliases()` to `DPMS.uninstallCaCerts`. That calls
  `CertificateMonitor.uninstallCaCerts`, which calls `KeyChain.deleteCaCertificate` for each alias. So it removes
  only the user trust store (`/data/misc/user/0/cacerts-added`), the certificates Settings installs as "CA
  certificate" (VPN and apps).
- **Where Wi-Fi certificates go** [AOSP]:
  - Settings > Install a certificate > "Wi-Fi certificate" runs CertInstaller with
    `CERTIFICATE_USAGE_WIFI`, so `mUid = Process.WIFI_UID`.
  - `hasOnlyVpnAndAppsTrustAnchors()` is then false, so the certificate goes through
    `com.android.credentials.INSTALL` with `EXTRA_INSTALL_AS_UID = WIFI_UID` into the Wi-Fi keystore namespace,
    not into KeyChain's trust store.
  - A network an app or device owner adds with `WifiEnterpriseConfig.setCaCertificate` is stored by the Wi-Fi
    stack in the same Wi-Fi namespace.
  - The EAP dialog's "CA certificate" choices are "Use system certificates", "Do not validate", "Trust on first
    use" and the Wi-Fi namespace's certificates. User-trusted CAs aren't offered.
- So **an EAP network keeps working** after a relock.
- **The real, narrower risk:** a CA installed as "CA certificate" for something else.
  - A school's TLS-inspecting filter or a private web server: Chrome and apps that opt in through a network
    security config trust user CAs.
  - The launcher doesn't depend on one: it has no `networkSecurityConfig` and targets 36. Neither does the music
    app.
  - The code removes all of them at every relock, also ones installed before the window (Low 4).
- **The device check is still worth doing for that case.** With Low 4's fix (remove only the CAs added in the
  window) it becomes unnecessary.

**2. Are 24b's migrations order-independent, so renumbering at merge is safe? Yes.**
- **What each one touches:**
  - `0062_window_relock.sql` adds `device_status.relock_json`.
  - `0063_unseen_boots.sql` adds `device_status.unseen_boots_count` and `_last_at_ms`.
  - Both are nullable `ADD COLUMN`s with no backfill and no dependency on each other or on main's
    `0062_catalog_app_pins.sql`, which only adds `tracked_apps.signer_sha256`.
  - S4's `0064_safe_boot_default.sql` needs only `device_policy.disallow_safe_boot` (0023) and
    `security_events` (0003; `username`/`ip_address` nullable, `created_at` defaulted). It is independent of the
    others too.
- **Renumber to:** main's 0062 first, then 24b's two as 0063/0064, and S4's as the next free number when it
  merges.
- **One caveat:** sqlx (`sqlx::migrate!`, no `ignore_missing`) keys `_sqlx_migrations` by version and
  checksum. A database that has already run the branch's numbering will refuse to start after the renumber
  (checksum mismatch at 62). A re-run would also fail on the existing columns, since SQLite has no
  `ADD COLUMN IF NOT EXISTS`. That applies to a dev server or a test Pi. Renumber before any such database runs
  it, or repair that database by hand.
- **Expect textual conflicts with main's uncommitted M10 work.** It touches the same files:
  `PolicyResponseCompatTest.kt`, `device_api.rs`, `main.rs`, `models.rs`, `tests/mod.rs` and
  `device_detail.html`.
  - M10's `TrackedAppUpdate.signer_sha256` is meant to feed `signerCheck(lockedSigners = …)`, which is still
    empty.
  - Both sides use SHA-256 of the DER certificate. M10 pins the current signer, which is always in the history
    the launcher compares against.

## Verified OK

**Windows and timers:**
- The relock is due only with the marker set and no window open, checked under `WindowRelockStore.LOCK`.
- `complete()` clears `pending` only with `gen` unchanged and no `apply()` failure, so a window opened
  mid-relock keeps its marker and the first snapshot.
- Findings add up across re-runs.
- `check` only `trySend`s to one conflated consumer under `relockMutex`, which `relockInSync` shares.
- Lock order: `ParentPin` → `LOCK`, and `ParentPin` → `POLICY_CACHE_LOCK`. Nothing takes them the other way, so
  there is no deadlock with `syncMutex` or `AppEnforcer`'s monitor.
- The alarm:
  - `nextBoundary` includes the window ends without a time policy, at min(wall end, now + elapsed left);
  - RTC_WAKEUP exact while-idle (`USE_EXACT_ALARM`);
  - re-armed on TIME_SET, TIMEZONE, BOOT and every `recheck`.
- A clock moved forward ends the window at TIME_SET. A clock moved back can't extend it (elapsed time).
- A reboot ends every window (`BOOT_COUNT`), and `initRest` and `BOOT_COMPLETED` relock.
- Process death: the alarm and the committed marker survive.

**Resets:**
- `DISALLOW_CONFIG_TETHERING` untethers: `Tethering.UserRestrictionActionListener` calls `untetherAll()` [AOSP].
- `setPermitted*` refuse a list that leaves out an enabled non-system service, and system ones are always
  permitted [AOSP]. They are set after hiding, only on change, and only while `allowlist != null`.
- The keyboard rule enabled ∩ (system ∪ effective allowlist) hides a sideloaded IME.
- Proxy reset as device owner [AOSP]:
  - `getConfiguredNetworks` passes `isTargetSdkLessThanQOrPrivileged` (`isAdmin`) once location and fine
    location are on;
  - `canModifyNetwork` and `canModifyProxySettings` allow the device owner;
  - `httpProxy = null` sets `ProxySettings.NONE`.
- **The credential:**
  - The token is set only when the phone isn't secure and the token isn't active. LockSettingsService then
    activates it at once.
  - The token is device-owner-only (0168dd45).
  - The credential is removed before `apply()`, so `configure` brings the kid's lock back.
  - An adopted window (`secureAtStart == null`) never removes one.
  - `credential_left` falls back correctly.

**The notification and the PWA path:**
- An ongoing notification from the device owner gets `FLAG_NO_DISMISS` (`NotificationManagerService.canBeNonDismissible`
  → `isEnterpriseExempted`) [AOSP].
- The receiver is not exported and the PendingIntent is explicit and immutable.
- The banner is our own view.
- `end_windows` / `allow_blocked`:
  - own insert, and `queue_command`'s delete skips them (`NOT IN`);
  - no CHECK constraint on `device_commands.command`;
  - delivered oldest first, with one follow-up sync each;
  - an older launcher ignores them, and the capability hides the buttons.

**Installer and signer rules:**
- Installer spoofing is closed on Android 14 [AOSP]:
  - `InstallStart` drops `EXTRA_INSTALLER_PACKAGE_NAME` unless the caller holds `INSTALL_PACKAGES`
    (b/236687884);
  - `PackageInstallerService` lets a session name another installer only with `INSTALL_PACKAGES`.
  - So "Play or us" holds.
- `PackageManager.setInstallerPackageName` needs the new installer's signature, so a block can't be cleared by
  relabelling.
- Hidden packages are in both snapshots (`MATCH_UNINSTALLED_PACKAGES`, `FLAG_INSTALLED` still set).

**The vault:**
- The HMAC-SHA256 key spec is valid: the digest is implied, with no auth and no StrongBox.
- The tag is compared with `MessageDigest.isEqual`.
- The try is counted before PBKDF2.
- A key failure answers `Unavailable(untilSync)` and undoes the try, so there is neither an oracle nor a free try.
- The migration rewrites the legacy fingerprint while the plain hash exists, redacts the cache under the cache
  lock, then deletes the plain fields. It is idempotent.
- `overridePinHash` is read only in `PinVault.kt` and the DTO (scan test).
- The server clears a PIN with SQL `NULL`, never `""`, so `accept`'s `""` short-circuit can't keep an old PIN
  today.
- The kid's PIN never touches the key.
- The new prefs (`window_relock` with the token, `pin_vault`, DE `boot_witness`) are covered by the backup
  exclusions, which exclude every domain.

**The keypad:**
- No `EditText` in any parent-PIN dialog.
- `FLAG_SECURE` is set before `show()`.
- `setAccessibilityDataSensitive` on the root and every key: API 34, and minSdk is 34.
- Up to 32 digits, 0-9 only.

**Device-owner API use:**
- Every platform call in `runRelock` is inside a step, and `opened()`'s token and notification are wrapped.
- `setRestriction` catches.
- No relock work runs on the main thread: the receiver, banner and Settings all use `lockAgainAsync`, and
  `check` hands off. The exception is Low 11.
- No user handles are involved.

**Server:**
- Both routes are in `admin_routes` (`require_full_auth`) behind the global Origin/Sec-Fetch `csrf_guard`.
  `unauthenticated_posts_queue_nothing` covers it.
- The redirect has no fragment, and the notice comes from a whitelist (`flash_text`). Askama escapes.
- `sanitize_relock` keeps only known triggers, windows and steps, valid package names, at most 8 per list,
  counts 0..1000 and times 1970..2100.
- `boot_witness::sanitize` clamps.
- `phone_relock_findings` fires only on a new `at_ms`, and `boot_unseen` only on a rise.

**Contract:**
- §1.7's literal is identical in `PolicyResponseCompatTest` and `window_relock::tests` / `src/tests/window_relock.rs`.
- `unseen_boots` keys are pinned on both sides.
- `policy_json_keys_snapshot` and the policy half of `PolicyResponseCompatTest` are unchanged.
- `phone_vectors.json` and the music contract files are untouched.

**Boot witness:**
- It lives in DE prefs and runs early in `onCreate`, also in direct boot. The boot-cover process returns before
  it.
- A first run counts nothing, -1 is skipped, the count never decreases, and it commits.

**S4:**
- The launcher default is `SAFE_BOOT(true)`.
- `insert_device_with_policy` is the only production insert, and it sets 1. `Hardening::default()` matches.
- The migration logs one `safe_boot_blocked_by_default` per flipped device, then flips them all.
- The card text matches §3.
- The kid-lock warning test is adjusted.
- It still waits for device check 6, as gated.

**Tests (the branch's own):**
- `WindowRelockPlanTest`, `RelockBlockTest`, `PinVaultTest`, `BootWitnessTest`, `WindowRelockCallersTest`,
  `EnforcementPlanTest` and `PolicyResponseCompatTest` cover §6.
- **Missing:**
  - Medium 1's case (the sync's `apply()` ran with the window open);
  - Medium 2 and Lows 1-3 (each now has a probe recipe above);
  - the alarm being armed when the starter's `apply()` throws.
