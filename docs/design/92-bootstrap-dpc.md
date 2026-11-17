# 92 - A bootstrap DPC that hands Device Owner to Vibb One

Status: research and design note, 2026-11-13, on `main` at 16553601, branch `design-92`. Nothing is built. PLAN's
"QR setup past Play Protect: a small bootstrap DPC" (asked 2026-11-13, "Sjekk gjerne ut den nr 3 løsningen da!").
Launcher, server and CI; no API change to existing routes, one new admin-extras key.

## Verdict

**Don't build it yet. Run two cheap device tests first, and build only if the first one fails and the other passes.**

- **The Android side works.** AOSP's `transferOwnership` is atomic, it is journaled across reboots, it keeps every
  policy, and it asks almost nothing of Vibb One: one tag in `device_admin.xml` and one callback. No signer match is
  required. A half-finished handoff can't leave the phone without an owner (§3). If we build it, it is about **M**.
- **Nothing shows it gets past Play Protect, and that is the whole point of it.** The evidence that the provisioning
  block depends on permissions is mixed (§1):
  - Some developers got through by removing SMS and notification-listener permissions.
  - Others were still blocked with a near-empty app.
  - Google's own unmodified Test DPC, with none of the four "sensitive" permissions, was blocked in September 2026
    until Google said "use the latest build". That looks like an allowlist of app identities, not a permission rule.

  The bootstrap also has a second risk. Its whole job is to download an app with SMS permissions and install it
  silently. That is the textbook "dropper" pattern Play Protect hunts for, and Google's DPC approval rules name
  "auto-installing apps ... without express and informed prior consent" as not permitted.
- **We have never seen our own QR blocked.** The one real GMS attempt (a Moto G Play 2024, before 2026-10-06) failed
  before the launcher had its `GET_PROVISIONING_MODE`/`ADMIN_POLICY_COMPLIANCE` activities. muster issue #89 shows
  that a DPC without those activities fails at exactly that step and resets the phone, with no Play Protect dialog.
  So that failure most likely wasn't Play Protect at all.

The order (§8):

1. **T1:** the current QR on a real GMS phone. It's needed anyway before the kid's phone is set up. If it passes,
   this design is shelved.
2. **T2:** today on the kid's Jelly Star, which is already Device Owner and needs no reset. Vibb One installs a fresh
   SMS app (Fossify Messages from GitHub) through the catalog. If Play Protect blocks that, option 3 is dead at its
   second step.
3. **T3, only if T1 is blocked:** a throwaway permission-free DPC through the QR. If that is blocked too, option 3 is
   dead at its first step.

The Play Protect appeal for Vibb One costs nothing. If T1 is blocked, file it in parallel (D2).

Confidence:

- **High** on the AOSP mechanics: read in `android16-release` (§3).
- **Low** on Play Protect: no official description of the check exists, and the reports contradict each other.
- **Medium** that T2 passes. DO installs of catalog apps without sensitive permissions already work on the Jelly Star.
  Google describes the sideload block as aimed at browsers, messaging apps and file managers, but nobody has reported
  a DO install of an SMS app either way.

## 1. What Play Protect checks at provisioning (question 1)

**What Google has published:**

- Android Enterprise Help 16694822 ("approved DPCs"): "Only DPCs verified and approved by Android Enterprise are
  permitted" during enrolment. Android Enterprise "evaluates all DPCs to block unauthorized or malicious apps with
  dangerous permissions", and the device shows "App blocked to protect your device".
  - Approval requires following the Mobile Unwanted Software and PHA policies.
  - Its not-permitted list includes "pushing, preloading, or auto-installing apps or services without the express and
    informed prior consent" of users, plus monitoring or eavesdropping outside enterprise management, and device
    financing locks.
  - https://support.google.com/work/android/answer/16694822
- Play Protect warning developer guidance: "You can only provision new devices with Android Enterprise methods using
  an approved Device Policy Controller (DPC)". An unapproved DPC "will be blocked during enrollment".
  - The same page describes the separate **internet-sideloading block**. An app from a browser, messaging app or file
    manager that declares `RECEIVE_SMS`, `READ_SMS`, `BIND_NOTIFICATION_LISTENER_SERVICE` or
    `BIND_ACCESSIBILITY_SERVICE` is blocked automatically "in select markets".
  - It adds that apps from the same sources with the same permissions are also blocked when a device is enrolled into
    enterprise management.
  - The dialog text it gives, "This app can request access to sensitive data. This can increase the risk of identity
    theft or financial fraud", is the same text reported for the provisioning block.
  - The appeal form is linked there: https://support.google.com/googleplay/android-developer/contact/protectappeals.
  - https://developers.google.com/android/play-protect/warning-dev-guidance
- No Android Enterprise blog post announced it. An Android Enterprise blog post (2025-12-24) says the help article "appeared out of thin air"
  and that enforcement began "earlier this year" (2025): https://bayton.org/blog/2025/12/the-dpc-allowlist/.

**Who can appeal:** anyone. A Google team member (2026-01-08) wrote: "Any developer can submit an appeal to
have their DPC allowlisted. There is no requirement to be AER, or to be an Android Enterprise partner."

- Appeals get no answer: "All appeal decisions are final and you will not receive a response".
- There is no SLA. Appeals "sit with the Play Protect team" (Community Manager, 2026-08-06).
- One developer appealed four or more times over two months and was still blocked.
- Threads:
  https://www.androidenterprise.community/android-enterprise-general-discussions-3/google-play-protect-s-new-policy-for-custom-dpc-1550,
  https://www.androidenterprise.community/android-enterprise-general-discussions-3/play-protect-blocking-custom-dpc-app-during-6-tap-qr-provisioning-appeal-submitted-2909

**Do only the DPC's own permissions count? The evidence (all unverified forum reports):**

| When | Who | DPC | Result |
| --- | --- | --- | --- |
| 2025-09-08/18 | two developers (thread 1271) | removed "all the service and permissions related to NOTIFICATION_LISTENER and SMS" | **passed** QR |
| 2026-01-22/28 | one developer (same thread) | "very limited permissions", then a new package name, a package checksum, "a minimal no-code sample app" | **blocked** on Android 15/16 |
| 2026-08-19 | muster (quadseven/muster #89) | `INTERNET`, `RECEIVE_BOOT_COMPLETED`, `SET_WALLPAPER`; own key | **passed** on a Pixel 6a |
| 2026-09-19 | Test DPC 9.0.12/9.0.13, Google-signed, unmodified (testdpc #283) | no SMS, call log, notification listener or accessibility; it does have usage stats, `QUERY_ALL_PACKAGES`, location, `MANAGE_ACCOUNTS` | **blocked**; Google then said "use the latest build"; one user then reported it working |
| 2025-05 | thread 1271 | unknown | blocked on Samsung Android 14 and up, same APK passed on Android 13 |

Sources:
https://www.androidenterprise.community/android-enterprise-general-discussions-3/play-protect-blocking-custom-dpc-apps-how-to-get-approval-or-alternatives-1271,
https://github.com/quadseven/muster/issues/89, https://github.com/googlesamples/android-testdpc/issues/283,
https://www.androidenterprise.community/android-enterprise-general-discussions-3/play-protect-blocks-google-s-test-dpc-9-0-12-during-qr-provisioning-app-blocked-to-protect-your-device-3061

What we can read from this:

- The check runs on Google's servers and changes over time. It is not in AOSP: ManagedProvisioning only downloads,
  checks the checksum and installs. The block is the package verifier answering the install (§2).
- Permissions are one input. Identity (package, signer, APK hash) and reputation are probably others. Some
  permission-light DPCs pass and some don't.
- **Unverified, and not answerable without a device:**
  - whether the check differs between QR, NFC and `afw#` (all reports are QR);
  - whether it applies to a DPC installed from Play (the reports are all non-Play downloads; Play-distributed DPCs go
    through Play review instead);
  - whether it covers apps the admin-integrated flow (`GET_PROVISIONING_MODE`) installs later. No report covers that.
- `adb shell dpm set-device-owner` goes through none of this. The APK is already installed, and adb installs are only
  verified when `PACKAGE_VERIFIER_INCLUDE_ADB` is set (`VerifyingSession.isAdbVerificationEnabled`). The emulator test
  of 2026-11-12 saw Play Protect allow an adb install of rc.6. But the project rule is "no adb step beyond the QR".

## 2. What happens to the APK the Device Owner installs later (question 2)

**The package verifier runs for a DO install.**

- `VerifyingSession.isVerificationEnabled` (frameworks/base, `services/core/java/com/android/server/pm/`) skips
  verification only in four cases:
  - adb installs (subject to the setting above);
  - the hidden `INSTALL_DISABLE_VERIFICATION` flag;
  - instant apps installed by the verifier;
  - `mOriginInfo.mExisting`.
- There is no device-owner exemption. Play Protect, as the required verifier, sees every `PackageInstaller` commit
  of the bootstrap and can reject it, which shows as `INSTALL_FAILED_VERIFICATION_FAILURE` or `STATUS_FAILURE_BLOCKED`.
- The DO can't switch the verifier off either. `setGlobalSetting` allows only `ADB_ENABLED`, `ADB_WIFI_ENABLED`,
  `AUTO_TIME`, `AUTO_TIME_ZONE`, `DATA_ROAMING`, `USB_MASS_STORAGE_ENABLED`, `WIFI_SLEEP_POLICY`,
  `STAY_ON_WHILE_PLUGGED_IN`, `WIFI_DEVICE_OWNER_CONFIGS_LOCKDOWN` and the private-DNS pair
  (`DevicePolicyManagerService`, `GLOBAL_SETTINGS_ALLOWLIST`).

**Whether the verifier then blocks or warns is Google's server-side decision. What we know:**

- **The sideload block for sensitive permissions** began as a pilot: Singapore, then Brazil, Hong Kong, India, Kenya,
  Nigeria, the Philippines, South Africa, Thailand, Vietnam and Malaysia.
  - Google's February 2026 report says enhanced fraud protection now covers "185 markets"
    (https://blog.google/security/keeping-google-play-android-app-ecosystem-safe-2025/), with no list of them.
    **Norway is probably covered. Unverified.**
  - It targets installs from "internet-sideloading sources (web browsers, messaging apps or file managers)". Google says
    nothing about an app that installs through a `PackageInstaller` session as Device Owner. **Unverified both ways.**
  - Today the Jelly Star's Vibb One already installs catalog apps (Organic Maps, the music app) and its own updates
    this way. None of those is a fresh install of a package with SMS permissions. T2 settles it.
- **Developer verification** (Android, enforced from 2026-09-30 in Brazil, Indonesia, Singapore and Thailand,
  globally in 2027) is a later, separate gate.
  - Google says apps distributed on managed devices through an organisation's store "won't need to complete the
    verification requirements" (https://support.google.com/android-developer-console/answer/16561738).
  - An Android Enterprise FAQ reads this as an indefinite exemption for APKs an EMM agent pushes
    (https://bayton.org/android/android-enterprise-faq/google-play-developer-verification-enterprise/).
  - Whether the DPC itself, installed by the QR, needs a verified developer is not said. It hits the bootstrap and
    Vibb One alike, so it isn't a reason for or against option 3 (D9).
- **A later Play Protect scan** can still flag an installed app ("harmful app" notification, permissions revoked,
  the app disabled). That risk is the same with or without the bootstrap. Vibb One stays the DO package, so it can't
  be uninstalled. Whether Play services can disable it is unverified (T7).
- **`transferOwnership` itself is not checked by anything Google.** It is a pure `system_server` call (§3); no
  verifier or Play Protect hook is on the path.

**Restricted settings (Enhanced Confirmation Mode, Android 15+).** A side effect, not a block:

- `EnhancedConfirmationService.isPackageEcmGuarded` (packages/modules/Permission, `service/java/com/android/ecm/`)
  marks an app as guarded if:
  - its install source is `LOCAL_FILE` or `DOWNLOADED_FILE`; or
  - its installer is neither preinstalled nor a trusted installer, whenever the device lists any trusted installers
    (GMS builds presumably do).
- The bootstrap is neither, so **a bootstrap-installed Vibb One is guarded from the first minute**, while a
  QR-installed one starts unguarded (ManagedProvisioning is preinstalled).
- Guarded settings include the SMS and dialer roles, notification access, usage access and `SYSTEM_ALERT_WINDOW`.
- The role grant the DO makes through `setDefaultSmsApplication` / `setDefaultDialerApplication` goes through
  `RoleControllerServiceImpl.onAddRoleHolder`, which checks only `isPackageQualifiedAsUser`. The ECM check
  (`Role.getApplicationRestrictionIntentAsUser`) is the UI's. So the roles should still be granted (T6).
- Notification access, which design 68 has the parent approve on the phone in a pause, then needs "Allow restricted
  settings" in App info first. Vibb One's own self-update already makes it the installer of record, so this step is
  likely there today anyway (`docs/notes/launcher/home-and-photos.md`, "sideloaded apps get 'restricted setting'").

## 3. `transferOwnership` mechanics (question 3)

Read in `android16-release`: `DevicePolicyManagerService.transferOwnership` and the helpers below,
`ActiveAdmin.transfer`, `Owners.transferDeviceOwnership`, `DeviceAdminInfo`, `DevicePolicyManager.transferOwnership`'s
javadoc. The API exists since API 28; minSdk 34 is fine.

**Who may call:** only the current default Device Owner, or a Profile Owner, for its own `admin`
(`checkCallAuthorization(isDefaultDeviceOwner || isProfileOwner)`). A DO can only become a DO.

**What the target must have:**

- **Its own package.** `admin` and `target` may not share a package name.
- **Installed in the calling user.** `findAdmin` → `getReceiverInfo` throws "Unknown admin" otherwise, so Vibb One
  must be installed first.
- **A receiver guarded by `BIND_DEVICE_ADMIN`.** Vibb One's is.
- **Internal storage, not an instant app, not being removed** (`checkActiveAdminPrecondition`).
- **`<support-transfer-ownership/>`** inside `<device-admin>` in its `android.app.device_admin` XML (parsed by
  `DeviceAdminInfo`, which requires the tag to be empty). Otherwise "Provided target does not support ownership
  transfer."
- **Not necessarily already active.** The javadoc says the call throws if "`target` is not an active admin", but the
  code doesn't check that: `transferActiveAdminUncheckedLocked` moves the old `ActiveAdmin` entry onto the target.
  Test DPC transfers to an inactive target the same way. **To confirm on a device (T4).**
- **No signer match.** Nothing compares the two packages' certificates. The bootstrap has to do that check itself (§5).

**Atomicity:**

1. Before the switch, `prepareTransfer` writes the bundle and a metadata journal
   (`TransferOwnershipMetadataManager`).
2. `transferDeviceOwnershipLocked` moves the admin and the owner record.
3. `postTransfer` deletes the journal.

If the phone dies between steps 1 and 3, `revertTransferOwnershipIfNecessaryLocked` runs at the next boot and
**transfers back to the source**. So the phone always has exactly one owner: the bootstrap, or Vibb One.

**What carries over:**

- **Everything the admin held.** `ActiveAdmin.transfer` only swaps `info` (the target's `DeviceAdminInfo`) and keeps
  the object: password policy, camera, keyguard features, user restrictions held in `ActiveAdmin`, and so on.
- **The policy engine's policies.** `mDevicePolicyEngine.transferPolicies(oldAdmin, newAdmin)` moves those keyed by
  `EnforcingAdmin`, such as lock task and restrictions under the policy-engine flags.
- **Per-user data**, which isn't keyed by admin: lock task packages without the engine, affiliation IDs, the
  delegation map, persistent preferred activities in PackageManager.
- **The rest:** SIM subscription group ownership (`transferSubscriptionOwnership`), the password owner UID, and the
  DO type and organisation-owned flag (`Owners.transferDeviceOwnership`).
- **Vibb One's own `uses-policies` take effect at once.** The new `info` is Vibb One's, so its list (wipe-data,
  disable-camera…) counts immediately, without the "counts after one restart" caveat that `device_admin.xml` notes for
  a policy added by an update.

The bootstrap sets almost nothing, so in practice very little moves.

**Callbacks on the target:**

- `ACTION_DEVICE_ADMIN_ENABLED` (`onEnabled`; Vibb One's starts `AppLoadHold.provisioning`).
- `ACTION_TRANSFER_OWNERSHIP_COMPLETE` with `EXTRA_TRANSFER_OWNERSHIP_ADMIN_EXTRAS_BUNDLE`
  (`onTransferOwnershipComplete(context, bundle)`). It is an explicit component broadcast, so no intent-filter entry
  is needed.
- `ACTION_DEVICE_OWNER_CHANGED` to everyone.
- `ACTION_AFFILIATED_PROFILE_TRANSFER_OWNERSHIP_COMPLETE` only concerns a profile owner's transfer on an affiliated
  user. It doesn't apply here.

**What is lost:**

- Only the source's admin status. After the call the bootstrap is an ordinary app: no longer DO, no longer an active
  admin, no longer protected from uninstall.
- **It can't remove itself without a prompt** (a non-DO uninstall needs user confirmation), but the new DO can:
  Vibb One's `AppInstaller.uninstallSilently`.
- **The bundle is not deleted.** It is kept in `/data/system/transfer-ownership-parameters.xml` and readable by the
  new owner via `getTransferOwnershipBundle()` until the DO is cleared (`deleteTransferOwnershipBundleLocked` runs only
  on revert or clear). That file is system-only. It is also a retry source for the enrolment (§4).

**During or after `ADMIN_POLICY_COMPLIANCE`:**

- `transferOwnership` doesn't look at the provisioning state at all, so AOSP allows it at any time once the DPC is DO.
- Whether the setup wizard or Google's provisioning role holder minds is not knowable from AOSP. Its finalization
  still sends `PROFILE_PROVISIONING_COMPLETE` to the provisioned admin component, which is the bootstrap's.
- So the design transfers **in the bootstrap's `onProfileProvisioningComplete`**, the last step of provisioning, and
  only installs during compliance (§4). T4 also tries the transfer inside compliance, to know whether it would be
  allowed.

## 4. The design

### Components

- **`bootstrap/`**, "Vibb One setup" (applicationId `me.vibb.setup`; debug `me.vibb.setup.debug`).
  - A second Gradle module in `launcher/` (`:bootstrap`), so it shares the wrapper, `verification-metadata.xml` and CI.
    GPL-3.0.
  - No UI library, no Kotlin coroutines library: platform APIs and `HttpURLConnection`. Target size about 100 KB.
  - **Manifest:** `INTERNET`, `ACCESS_NETWORK_STATE`, `RECEIVE_BOOT_COMPLETED` and nothing else. No
    `REQUEST_INSTALL_PACKAGES` (a DO installs without it, as Vibb One does today), no `QUERY_ALL_PACKAGES`, no services
    beyond a `JobService` for retries. Not a HOME app.
  - `SetupAdminReceiver`: `BIND_DEVICE_ADMIN`, `device_admin_setup.xml` with an empty `<uses-policies/>`. It must not
    declare `<support-transfer-ownership/>`, so nothing can transfer to it later.
  - `ProvisioningModeActivity`: as Vibb One's (`chooseProvisioningMode`, fully managed only). It hands the admin
    extras on.
  - `ComplianceActivity`: a calm, single screen, "Setting up Vibb One…", with a progress line, nb and en. Its job is
    described in the flow below.
  - `SetupAdminReceiver.onProfileProvisioningComplete`: does the transfer.
  - A retry `JobService`, for when the install didn't finish in compliance.
  - The pure parts in one file with unit tests: URL check, signer and package check, the state machine.
- **Vibb One:**
  - `device_admin.xml` gains `<support-transfer-ownership/>`; `AdminPoliciesTest` learns the tag.
  - `MdmDeviceAdminReceiver.onTransferOwnershipComplete(context, bundle)`:
    `ProvisioningExtras.fromAdminExtrasBundle(bundle)` → `applyProvisioningExtras`, under `goAsync`, the same code
    `onProfileProvisioningComplete` runs. Then it uninstalls `me.vibb.setup` silently.
  - At every start until the first policy: if `getTransferOwnershipBundle()` holds extras and no enrolment has
    succeeded, try again. On every sync: if `me.vibb.setup` is installed and Vibb One is DO, remove it.
  - The status report gains `setup_via: "bootstrap" | "qr" | null`. Optional, D7.
- **Server:**
  - `.env` `LAUNCHER_SETUP_MODE=direct|bootstrap` (default `direct`), with `BOOTSTRAP_APK_URL`, `BOOTSTRAP_COMPONENT`
    and `BOOTSTRAP_SIGNATURE_CHECKSUM` beside the `LAUNCHER_*` three.
  - In bootstrap mode, the QR names the bootstrap, and `AdminExtras` gains `launcher_apk_url` (the `LAUNCHER_APK_URL`
    the preflight checked). The launcher's own `ProvisioningExtras` ignores the new key.
  - The preflight (design 86) checks **both** APKs:
    - the bootstrap against the QR's checksum and package;
    - Vibb One against the launcher checksum, package `me.vibb.launcher` and minSdk, with the version line as today.
  - Phone Setup's row says which mode the QR is in.

### The flow, step by step

1. The parent scans the QR as today. Android, or Google's provisioning role holder, downloads `vibb-setup.apk`,
   checks its checksum, installs it, asks `GET_PROVISIONING_MODE` (fully managed) and sets it as Device Owner.
   Locale, time zone, Wi-Fi and `LEAVE_ALL_SYSTEM_APPS_ENABLED` are applied by the platform from the QR's top-level
   extras, as today. None of this depends on the DPC.
2. `ADMIN_POLICY_COMPLIANCE` → `ComplianceActivity`:
   1. It reads `launcher_apk_url` from the admin extras. It refuses anything but `https://`, and falls back to the
      compiled-in GitHub `releases/latest/download/kids-launcher-mdm.apk` when the key is absent.
   2. It downloads to `noBackupFilesDir`: redirects followed (https only, at most 5), a size cap of 150 MB, a stall
      timeout.
   3. It checks the file **before** any install. `getPackageArchiveInfo(GET_SIGNING_CERTIFICATES)` must give package
      `me.vibb.launcher` and a signer whose SHA-256 is the **compiled-in release certificate**. Debug builds pin the
      debug certificate and package.
   4. It installs silently with `PackageInstaller` (`MODE_FULL_INSTALL`, `USER_ACTION_NOT_REQUIRED`), as
      `AppInstaller.installSilently` does.
   5. On success it finishes `RESULT_OK`.
   6. On any failure it shows the reason in one line with "Try again". After 3 tries, or 5 minutes, it finishes
      `RESULT_OK` anyway and leaves the rest to the retry job (step 5).
3. `onProfileProvisioningComplete` (bootstrap):
   1. If Vibb One is installed and passes the same signer check, it calls
      `dpm.transferOwnership(setupAdmin, ComponentName("me.vibb.launcher", "com.kidslauncher.mdm.server.MdmDeviceAdminReceiver"), extras)`.
      The bundle is the QR's admin extras unchanged: `server_url`, `tailscale_auth_key`, `enrollment_code`.
   2. It then checks `dpm.isDeviceOwnerApp("me.vibb.launcher")`. The javadoc says success is judged that way.
4. Vibb One gets `onEnabled`, then `onTransferOwnershipComplete(bundle)`:
   1. `applyProvisioningExtras`: the server URL, the tailnet join, `Enrollment.enroll`, a sync request.
   2. Its first policy makes it Home (`AppEnforcer`), takes the SMS and dialer roles, and so on, exactly as after a
      direct QR.
   3. It uninstalls `me.vibb.setup`.
5. **Retry job** (only if step 2 or 3 didn't finish):
   - The bootstrap stays DO and schedules a job with network required, backoff 1, 5 and 15 minutes, then hourly. The
     job repeats step 2's download, check and install, then step 3's transfer.
   - It shows one ongoing notification: "Finishing Vibb One setup - needs internet", with "Try now".
   - `RECEIVE_BOOT_COMPLETED` re-arms the job.
6. In the meantime the phone is a normal phone: the stock launcher is Home, and the bootstrap sets no lock task and no
   restriction. Nothing about the kid's phone is locked down until Vibb One is owner. Today's direct QR leaves the same
   gap until the first policy.

### Failure cases and recovery

| Case | What happens | The phone is |
| --- | --- | --- |
| The QR's bootstrap is blocked by Play Protect | Provisioning ends, "Reset" (as today) | factory fresh; option 3 failed (T3) |
| No network after Wi-Fi setup, GitHub down, 404 | Compliance retries, then `RESULT_OK`; the retry job takes over | DO = bootstrap, stock Home, usable |
| The APK fails the signer or package check | Refused, the file deleted, the reason shown; retried later (the server may be fixed) | as above |
| The install is rejected by the verifier (Play Protect) | The status message is shown and logged; retried hourly in case Google changes its mind | as above; option 3 failed (T2) |
| `transferOwnership` throws (tag missing, an old Vibb One) | Caught; the bootstrap stays DO; the retry job installs the newer APK and tries again | as above |
| Reboot or crash mid-transfer | AOSP's journal reverts to the bootstrap at boot; the retry job transfers again | DO = bootstrap |
| Transfer done, enrolment fails | Vibb One is DO but not enrolled: the same state as a failed auto-enrol today. Vibb One retries from `getTransferOwnershipBundle()` at each start; Settings stays open before the first policy (the in-app QR scanner) | DO = Vibb One |
| Transfer done, the bootstrap's uninstall fails | A harmless leftover (no admin, no permissions); removed at the next sync | DO = Vibb One |
| The parent gives up | The bootstrap's screen and notification offer "Reset phone" (`wipeDevice`, behind a confirm). This is the only way out while it is DO, and it loses nothing: the phone was just reset | factory fresh |

The rules behind this table:

- There is never "no owner". AOSP makes the transfer atomic, and the bootstrap never calls `clearDeviceOwnerApp`.
- There is never a brick. The bootstrap never takes Home, lock task or restrictions.
- The bootstrap never hands ownership to anything but `me.vibb.launcher` signed with the pinned certificate.

## 5. Where the APK comes from and how it is verified (question 5)

**Source.**

- **Default:** the admin extra `launcher_apk_url`, written by the server from `LAUNCHER_APK_URL`, so the version the
  preflight showed the parent is the version installed.
- **Fallback:** the compiled-in `https://github.com/palchrb/vibb-launcher/releases/latest/download/kids-launcher-mdm.apk`.
- D6 picks between these and a URL pinned to the bootstrap's own release.

**Trust.**

- The bootstrap pins two things: the release certificate's SHA-256 (the repo variable `RELEASE_CERT_SHA256`, public
  data, baked in at build time) and the package name.
- The `.sha256` asset proves nothing beyond the download, since it comes from the same origin. It is not used.
- After the install Android itself enforces the signer for every update, and Vibb One's `pendingApkCheck` keeps
  guarding self-updates.

**Preflight.**

- Design 86's check, run twice (§4).
- The checksum in the QR becomes the bootstrap's. If the bootstrap is signed with the launcher's key (D4), it is
  the same value as `LAUNCHER_SIGNATURE_CHECKSUM` today, and only the package and URL change.

**Release.**

- `launcher.yml`'s release job builds `:bootstrap` alongside the app.
- `launcher-release-sign` signs both with the same keystore in the `release` environment, with the same
  `RELEASE_CERT_SHA256` and not-debuggable checks.
- `launcher-release-publish` adds `vibb-setup.apk` and its `.sha256` to the release, so
  `releases/latest/download/vibb-setup.apk` is as permanent as the launcher's.
- versionCode: by `scripts/release-version.sh`, as the launcher's.
- No new secret. A separate key (D4) would be one more secret in the `release` environment, never in the repo.
- The alternative is the bootstrap's own `bootstrap-v*` tags (D5). The bootstrap would then change rarely, which
  matters if Google's allowlist turns out to key on the APK's hash.

## 6. Alternatives (question 6)

- **Play Protect appeal for Vibb One itself.** Free and parallel, but slow, with no answer and no SLA. It may be
  refused for an SMS-capable DPC under the "monitoring" rule, though a parental-control DO is enterprise-like
  management. Best value, so do it whenever T1 is blocked (D2).
- **adb `set-device-owner` as the main path.** It works on any GMS phone and needs nothing built. But it breaks the
  "no adb step beyond the QR" rule, and needs a computer, USB debugging and no accounts on the phone. Kept as the
  fallback (D8).
- **The dangerous permissions in a separate package granted later** (a "Vibb Messages" companion holding the SMS role
  and the notification listener). Worse than option 3:
  - The launcher **is** the SMS app, the dialer, the in-call screen and the glance reader (designs 25, 77, 80).
  - Splitting means a cross-process API for all of that: an L to XL refactor.
  - The companion's install still meets T2's question, the same one option 3 meets.
- **Publish the DPC on Google Play.** Play's SMS/call-log policy and its DPC rules make an SMS-and-dialer DPC
  unlikely to pass. A Play-distributed DPC also changes the update model. No.
- **Google's Android Device Policy (AMAPI) as the DPC.** Gives up our own DO, which is the whole point of the
  launcher. No.
- **A different provisioning trigger (NFC, `afw#`, zero-touch).** NFC and `afw#` presumably meet the same check
  (unverified). Zero-touch needs a reseller and a supported device, and the Jelly Star isn't one. No.
- **The in-app QR scanner (GrapheneOS path).** Needs DO first. Not an answer on GMS.

## 7. Cost

**M** in all, if built. Agent time, not counting the device tests:

| Part | Size |
| --- | --- |
| `:bootstrap` module: manifest, receiver, two provisioning activities, compliance screen nb/en, download + check + install + transfer + retry job, pure-part tests | M- |
| Vibb One: `<support-transfer-ownership/>`, `onTransferOwnershipComplete` → enrol, retry from the stored bundle, remove the bootstrap, `AdminPoliciesTest` | S |
| Server: setup mode, three `.env` keys, `launcher_apk_url` admin extra, two-APK preflight, Phone Setup wording, tests | S+ |
| CI/release: build, sign and publish the second APK, release-version selftest, verification metadata | S |
| Docs: DEPLOY.md, the launcher notes, `docs/testing/emulator.md` (§4's flow can run on the emulator with the wizard's intent as root, as on 2026-11-12) | S |

**The probe (T3) is S** on its own: a throwaway app with the two provisioning activities and a receiver, signed with
a throwaway key, served from a GitHub prerelease. It needs one factory reset per run.

## 8. Device tests (a real GMS phone; none can run on the emulator)

- **T1 - today's QR**, on the phone the kid will get (Jelly Star, Android 16 OTA first) or a spare GMS phone (D1).
  Record the dialog's exact text with a photo. If it's blocked, file the appeal (D2) and go to T3.
- **T2 - a DO install of an SMS app, no reset.**
  - On the Jelly Star with Vibb One as DO, add Fossify Messages' GitHub release (`RECEIVE_SMS`, `READ_SMS`) to the
    catalog and let Vibb One install it.
  - Watch for an install failure, a Play Protect dialog or notification, and the app being disabled within 48 hours.
  - Remove it afterwards. Optional: a second test APK with a `NotificationListenerService`.
- **T3 - the permission-free probe DPC through a QR** (only if T1 is blocked). Blocked → option 3 is dead. Passes →
  go on to T4 once the user says build.
- **T4 - the full flow:**
  - the bootstrap QR; the install in compliance; the transfer on `PROFILE_PROVISIONING_COMPLETE`;
  - `dumpsys device_policy` shows `me.vibb.launcher` as DO;
  - enrolment from the bundle; the bootstrap gone; Vibb One Home after the first policy;
  - locale, time zone and system apps as with T1;
  - once, the transfer inside compliance instead, to learn whether the wizard or role holder objects.
- **T5 - failures:**
  - Wi-Fi off after setup: the retry job and notification, then success when Wi-Fi returns;
  - a wrong-signer APK at `launcher_apk_url`: refused;
  - power off right after "Transferring…": the owner after boot;
  - "Reset phone".
- **T6 - after the transfer:**
  - the uses-policies at once: `lockNow`, the camera, wipe declared (not run);
  - the SMS and dialer roles via DPM on a bootstrap-installed (ECM-guarded) Vibb One;
  - notification access in a pause: is "Allow restricted settings" needed first?
- **T7 - Play Protect over the next days:** any "harmful app" notification for Vibb One or the bootstrap, any
  permission auto-revoke.
- **T8 - Vibb One's next self-update** after the bootstrap is gone (installer of record null) installs as usual.

## 9. Open decisions for the user

- **D1 - Where T1 and T3 run.** On the Jelly Star before it goes to the kid (each run is a factory reset; the probe is
  test-key signed, so it must never stay on the kid's phone), or on a spare or borrowed GMS phone with Android 14 or
  newer. Proposal: a spare phone if one is at hand, else the Jelly Star before the final setup.
- **D2 - Appeal.** If T1 is blocked, file the Play Protect appeal for `me.vibb.launcher` at once, whatever else is
  done. Proposal: yes.
- **D3 - Gate.** Build option 3 only if T1 is blocked **and** T2 and T3 pass. Proposal: yes.
- **D4 - The bootstrap's key.** The launcher's release key (no new secret, same QR checksum, shares Vibb One's
  reputation for better or worse), or its own key (one more secret in the `release` environment). Proposal: the
  launcher's key, and switch only if T3 shows a difference.
- **D5 - Release vehicle.** An asset in every `launcher-v*` release (always in step), or own `bootstrap-v*` tags (a
  stable APK hash). Proposal: own tags, rarely cut.
- **D6 - Which Vibb One it installs.** The server's `launcher_apk_url` (preflight-checked, may be a prerelease), with
  GitHub latest stable as fallback. Proposal: as written.
- **D7 - Both QR modes kept.** `direct` stays the default for non-GMS and GrapheneOS phones; `bootstrap` is a Phone
  Setup choice, and the status report says which was used. Proposal: yes.
- **D8 - If everything fails.** adb `set-device-owner` as the documented fallback despite the "no adb beyond the QR"
  rule, until an appeal lands. The user's call.
- **D9 - Developer verification (2027).** Register a developer account (the free limited-distribution kind covers up
  to 20 devices) for `me.vibb.launcher` and the music app before it reaches Norway. Not urgent, same for both designs.
