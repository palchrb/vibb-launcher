# QA: design 68, per-app permissions (design note at main 5ff1310c)

**Verdict.** The plan holds up. Every Android fact it relies on checks out against AOSP main (the DPM javadoc, the
PermissionController's admin path, AppOpsService, the Settings manifest). Reading another app's app-op is backed by
the code, not just "works today". Nothing gives a kid's app more than the parent's switch allows. No High findings.
Four Mediums: the access-state rule misreads two real cases (M1), `access.camera` would show switches that do nothing
(M2), a deferred denial has nothing to wake it (M3), the PWA's `<details>` collapses after every autosave (M4). Fold
them in (a few lines each), then build.

Sources (2026-10-26, googlesource main; GitHub for the apps): DPM `DevicePolicyManager`, PCS `PermissionControllerServiceImpl`,
AOS/AOM `AppOpsService`/`AppOpsManager`, PMSI `PermissionManagerServiceImpl`, AMS `AlarmManagerService`, core/SetM manifests.

## Verified (no change needed)

- **1a**: the note is right; the brief's premise is not. A device owner may still **grant** sensor permissions unless
  provisioning sent `EXTRA_PROVISIONING_SENSORS_PERMISSION_GRANT_OPT_OUT` (DPM 1601-1615, the javadoc of
  `setPermissionGrantState` at 14251). Only a profile owner (or an opted-out device owner) is limited to denying. A
  refused grant throws for an admin targeting >= 35 (DPM 14209). Our QR sends no opt-out (no hit in launcher/, server/).
- **1b, 1c**: DENIED = `setPolicyFixed(true)` + `revokeRuntimePermissions(false, ..)`; DEFAULT = `setPolicyFixed(false)`
  only, no grant (PCS 591-597; DPM 14170): the app can ask again. A revoke kills (PMSI 1640-1652 -> `killUid`). VoIP is
  detectable (`VoipCalls.liveCall`, VoipCalls.kt:101, from the listener's call notifications); the trigger is M3.
- **1d**: `checkOperationImpl` (AOS 2890) calls `validateOpRequest(.., shouldVerifyUid = false, ..)` (2911-2912); the
  old path's `verifyIncomingOp` and `isIncomingPackageValid` (4554, 4577) check no uid either. `verifyIncomingUid`
  (4520, `UPDATE_APP_OPS_STATS`) is never reached. `opRestrictsRead` is set only on `ACCESS_RESTRICTED_SETTINGS` (AOM
  3052-3054). None of the four ops has a user restriction or is in `OPS_RESTRICTED_ON_SUSPEND` (AOS 247). AOM's
  `@RequiresPermission(GET_APP_OPS_STATS)` sits on the history/stats APIs, not on `checkOp*`. Keep the probe before
  step 1 and the `unknown` fallback as written.
- **1e**: SetM has a `package:` filter for all four: `AppUsageAccessSettingsActivity` -> `UsageAccessDetails` (2390),
  `AppWriteSettingsActivity` (4345), `AlarmsAndRemindersAppActivity` (4377), `AppMediaManagementAppsActivity` (4496);
  the plain actions are the fallbacks, resolving only while Settings is unhidden (the write-settings row's path).
- **1f**: Etar :33 declares `SCHEDULE_EXACT_ALARM` only (targetSdk 37). Fossify Calendar (:12, :14-16) and Clock
  (:10-12) declare `USE_EXACT_ALARM` + `SCHEDULE_EXACT_ALARM maxSdkVersion=32`. Immich :10 `MANAGE_MEDIA`, no CAMERA.
- **2, security**: no way back to the camera. Clear data needs Android's Settings (hidden outside the window, which
  lifts the denial anyway). A reinstall is denied again by `enforceOnNewPackage` within seconds (a grant in that gap is
  revoked, the app killed). An update keeps POLICY_FIXED; an added CAMERA is caught by `onPackageChanged`. The rows add
  no Settings surface: they open pages only in the pause or the override, which already unhide all of Settings, as
  today's write-settings row (launcher/CLAUDE.md 845-847); the gate ends at `onStop`. `QUERY_ALL_PACKAGES` is already
  declared (manifest:11), and we are not on Play. `access` tells only the parent's own server which apps the kid let
  use the camera.
- **3, 5**: the commit split is right. `cap_installed_apps` (device_api.rs:745-772) is the place for `access`; the
  capability goes in `STATUS_CAPABILITIES` (StatusCapabilities.kt:11) and as a server constant by `ZONES_CAPABILITY`
  (models.rs:690). An older launcher ignores the key (ServerJson.kt:16). Q1 (the pause lifts the denial) is right; the
  relock's DENIED kills only an app granted the camera in the pause: acceptable, worth one sentence.

## High: none

## Medium

- **M1. `accessState` reads `MODE_DEFAULT` as not granted, which is wrong in two real cases.** (a) An app on the
  device-idle allowlist (battery "Unrestricted", which §2.2 says every family app gets in a pause) may set exact alarms
  without the op: `canScheduleExactAlarms` returns `isExemptFromExactAlarmPermissionNoLock` (AMS 2668-2675, 2848), so a
  working Etar would show "approve on the phone" forever. (b) Holders by protection flag: `MANAGE_MEDIA` is
  `signature|appop|preinstalled`, `WRITE_SETTINGS` adds `preinstalled|pre23|role`, `PACKAGE_USAGE_STATS` adds
  `privileged|development` (core). An allowlisted preinstalled app holds the permission with the op at DEFAULT.
  Fix: ALLOWED = granted; DEFAULT = `PackageManager.checkPermission(perm, pkg)`, as the services' `PermissionChecker`
  does. `exact_alarm` is also granted when `isIgnoringBatteryOptimizations(pkg)` holds, or with DEFAULT on a target
  < 33 (AMS 2631-2652). Pin both in `AppAccessTest`.
- **M2. `access.camera` must cover only the camera targets.** `collectInstalledApps` also reports system apps
  (MdmSyncWorker.kt:1060-1080), but `cameraGrants` targets third-party apps only, like `callPermissionTargets`
  (EnforcementPlan.kt:275; AppEnforcer.kt:716-719). An allowlisted preinstalled camera or Google app would get a PWA
  switch that never takes effect, stuck on "waiting for the phone". Fix: report `camera` only for `cameraGrants`'
  targets, and show the switch only when the key is there. Add `GET_PERMISSIONS` to that `getPackageInfo` (today it
  passes `MATCH_UNINSTALLED_PACKAGES` only).
- **M3. A denial deferred for a call has nothing to wake it, and the predicate is unnamed.** No `apply()` runs when a
  call ends; the next is a nudge or the 30-minute backstop (SyncSchedule.kt:17). Fix: use `WindowRelock.liveCall`
  (ours, Telecom's with unknown as a call, VoIP, the emergency flow; WindowRelock.kt:602-603). On a deferral set a flag
  in memory and request an `apply()` from `BootHome.callMayHaveEnded` (BootHome.kt:101: our call's end, the VoIP
  phase's end, every screen-on). Defer only while the camera is granted (`checkPermission`): revoking an ungranted
  permission returns before the kill (PMSI 1640-1641), so most denials land at once even in a call.
- **M4. The Permissions `<details>` closes after every autosave.** Autosave reloads the page through `form.submit()`
  (autosave.js:1-3). `scroll-restore.js` keeps the scroll position, not `<details>` state, and the 303 goes to
  `#app-<package>`, the row, so the switch the parent just flipped folds out of sight. "Name and icon" handles this with
  `form.open` (app_display.rs:321). Fix: give the details its own anchor (`perms-<package>`, built like `app_anchor`,
  devices.rs:1762) and render it open when the 303 targets it, or put the camera switch in a plain row outside it.

## Low

- **L1.** §2.1 cites "L2's `phoneStateGrants` rule: never revoke during a call". L2's rule is "never revoke: reset to
  DEFAULT" (EnforcementPlan.kt:283-291), and the CALL_PERMISSIONS denial itself revokes with no call check
  (AppEnforcer.kt:723-727). The deferral is new; say so.
- **L2.** `applyCallPermissions` (AppEnforcer.kt:414) runs before the suspend/hide loop (441-466). The admin path reads
  the package with `getPackageInfo(pkg, GET_PERMISSIONS)` and no `MATCH_UNINSTALLED_PACKAGES` (PCS 189-196), so a
  hidden app probably can't be denied, and on the apply that unhides it the camera pass would come first.
  `setGrantState` also ignores the boolean result (734-741). Fix: run `applyCameraPermissions` after the loop, log a
  `false`, and add an emulator check for a denial on a hidden app.
- **L3.** `enforceOnNewPackage`'s grant writes sit in `if (CallPolicyStore.state.managed)` (AppEnforcer.kt:1250-1268);
  `onPackageChanged` applies only for `WindowRelock` (Application.kt:95-115). Put the camera rule outside both.
- **L4.** With `denied == null` nothing is written, even in a window, so on a `Fallback` phone (PolicyGate.kt:14-19)
  the pause can't lift a denial. Fix: in a window, write DEFAULT for every target that reads DENIED (we are the only
  admin), or state the gap as a residual.
- **L5.** Parse `access` with a `lenient_*` deserializer, as `version_code`/`version_name` are (models.rs:919-932): a
  fifth key (Q2) or a new value from a newer launcher is then dropped, never a refused report. Pin that in the contract
  test.
- **L6.** "Update Vibb One to manage this" (§3.3) has no row to appear on: an older launcher reports no `access`, so
  §4.1 shows no section. Show it once, above Device › Apps' allowed list, while the capability is missing.
- **L7.** A granted access loses its phone row (§4.2: rows for `denied`/`unknown` only), so the parent can't take
  Media management back from Vibb's Settings. Keep the row as "Approved - tap to change" (the same page).
- **L8.** Signal (the first row of `installed_apps_report.json`) declares `SCHEDULE_EXACT_ALARM` without
  `USE_EXACT_ALARM` (Signal manifest :64) and works without it, yet it would get "Alarms & reminders: approve on the
  phone" and a phone row. Use neutral words ("not approved"), with the way to approve only inside the details.
- **L9.** Help text: "Turning it off closes <app> if it is open" (any revoke kills, 1c - a WhatsApp upload too).
- **L10.** Q2: `USE_FULL_SCREEN_INTENT` is `normal|appop` (core), so DEFAULT means granted when the permission is held.
  "Readable the same way" holds only with M1's `checkPermission` rule.

## Nit

- **N1.** nb: Android says "Apper for medieadministrering" / "Tillat at appen administrerer medier", "Alarmer og
  påminnelser" (SettingsLib), "Endre systeminnstillingene" (our row: "Endre systeminnstillinger"), "Brukstilgang". Use
  "Medieadministrering" and "Brukstilgang", not "Medieadministrasjon" / "Tilgang til bruksdata".
- **N2.** S3 adds no migration today; still write "the next free number at merge" (+ `migrations_73_to_101_in_order`).
- **N3.** `unsafeCheckOpRawNoThrow` is deprecated on main (FlaggedApi, AOM 9080-9088); fine on 34-36, try/catch -> `unknown`.
- **N4.** The probe: name the oracle (`cmd appops get <pkg> <OP>` on `jelly36`), test apps for `write_settings`/
  `usage_access` (no family app declares them); repeat it on the Jelly Star.
- **N5.** `camera: granted|denied` is shown nowhere (§4.1 uses `admin_denied`): show it or send `requested|admin_denied`.
- **N6.** "Display over other apps: not offered" means no row only; a pause still lets anyone grant it, and it outlasts
  the relock (an existing pause property). A later round could report it read-only.
- **N7.** Length (229 lines; about 50 can go): §1's quotes (one line each); §2.1's restated javadoc (keep the decision
  and the citations); §2.2's "Who needs it" column (one sentence); §4.2's summary texts ("as `writeSettingsRowState`");
  §5's "pause ends with the page open" (point to the write-settings row); §7 Q2 (into §2.2's "Not offered").
