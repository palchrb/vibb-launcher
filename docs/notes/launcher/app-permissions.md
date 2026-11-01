# Launcher notes: per-app permissions and the background connection

Moved from launcher/CLAUDE.md on 2026-11-01.

## Background connection (design 19b phase 1, `docs/design/19b-doze-exemption.md`, at the monorepo root)

- Every status report sends `battery_optimization_exempt` (`PowerManager.isIgnoringBatteryOptimizations`, left out when unreadable; `PolicyResponseCompatTest`). The manifest declares `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` (`PushManifestTest`) - without it Android's dialog finishes silently.
- The PIN-gated Settings' "Background connection" row (`server/BatteryExemption.kt`, `BatteryExemptionTest`) opens Android's dialog only when it resolves to an unsuspended system package that isn't ours (`dialogTarget`) - on a managed phone that is during "Pause all restrictions"; otherwise it says to pause first. Never Android Settings' battery page: it shows a device owner as "Unrestricted" whatever the truth. On return the state is read again and a change requests a sync. Phase 2 (a prompt in QR provisioning) only if the Jelly Star run shows it matters.
- **Two more rows on the same path** (2026-10-24, branch `write-settings-row`; no adb step for any grant): the battery row's resolution is the shared `systemPageIntent` (`MATCH_SYSTEM_ONLY` + `dialogTarget`, `BatteryExemption.kt`), which on a managed phone resolves only during the pause or the override (the window's plan unhides Android's Settings and pins nothing). "Modify system settings" (`server/WriteSettingsAccess.kt`, `WriteSettingsAccessTest`), always shown under "Background connection": `ACTION_MANAGE_WRITE_SETTINGS` with `package:` + our id, then the plain action (an `ActivityNotFoundException` tries the next, all failing = "no page"); the tap opens only when `canWrite` is known false, a change on return requests a sync, and every report sends `write_settings_granted` (`StatusReportRequest.writeSettingsGranted`, `null` left out; the server's About This Phone row, migration 0090). "Fingerprint" (`server/FingerprintEnrol.kt`, `FingerprintEnrolTest`), under it: `ACTION_BIOMETRIC_ENROLL` for `BIOMETRIC_STRONG`, then `ACTION_FINGERPRINT_ENROLL`; shown only by `fingerprintRowShown(AndroidLock.fingerprintLockState, FEATURE_FINGERPRINT)` - we own Android's live credential, its `android_pin_rev` is known (not changed on the phone) and android mode is requested (not the rollback's escrow) - and its state line says registered / not registered only from `canAuthenticate(BIOMETRIC_STRONG)` (`USE_BIOMETRIC` in the manifest). Both rows' access is the pure `systemPageAccess`: "no page" only once `AppEnforcer.lastEnforcedOverride` says the window's plan is applied, "a moment" before; the fragment re-reads the rows on resume, when the pause starts and after each `apply()` while shown (`AppEnforcer.addAppliedListener`). "Media on the lock screen" ("Medier på låseskjermen", `server/MediaLockScreen.kt`, `MediaLockScreenTest`, branch `home-round`), under "Fingerprint", always shown: `ACTION_SOUND_SETTINGS` through `systemPageIntent` with `systemPageAccess`'s states - no public action opens the Media sub-page, so the summary names the way ("Lyd og vibrasjon › Medier › Vis medier på låseskjermen av"); no state line: `media_controls_lock_screen` has no `@Readable` (added in Android 13, after S's bulk annotation), so SettingsProvider refuses it to any app targeting S+, a device owner included (qa-home-round-code Q-1) - the summary is the action line alone (`mediaLockText`), a tap always opens the page, and `MediaLockScreenTest` pins that the file reads no `Settings.Secure`. Android 13+ shows its media card on SystemUI's own face whatever our keyguard features say (`docs/setup/android-settings.md`).
- **The first-launch reset never de-enrols** (design 59d §8.17): `Application.initRest`'s `resetPreferences` clears the whole default prefs file (token, server URL and cached policy included), and only Home's first interactive start sets `internal.started_before`; so it runs only by the pure `firstLaunchResetDue(startedBefore, enrolled, tokenStored)` (`preferences/FirstLaunchReset.kt`, `FirstLaunchResetTest`) - no mark, no `mdm.enrolled`, no token stored (`DeviceToken.present()`: sealed or not, readable or not - design r10). A phone enrolled before Home ever started (QR provisioning, Settings opened through `APPLICATION_PREFERENCES`) keeps its preferences.

## Per-app permissions: camera off, the special accesses approved on the phone (design 68, `docs/design/68-app-permissions.md`, at the monorepo root)

- **Camera off** (§3.2): `PolicyResponse.appPermissions` (raw JSON, read field by field by the pure `appPermissionsOf`
  in `server/EnforcementPlan.kt`: a list of `{package_name, camera: "deny"}`, a bad entry dropped, any other `camera`
  the default; missing or not a list - an older server, a `Fallback` - is `null` = touch nothing; not in
  `LastEnforcedPlan`: the denial lives in Android's grant state). Pure `cameraGrants` (`EnforcementPlanTest`): every
  third-party app but ours that requests `CAMERA` is `GRANT_STATE_DENIED` when the parent switched it off and no window
  is open, else `GRANT_STATE_DEFAULT` (the fix cleared, the kid's dialog decides) - **we never GRANT a sensor**, and the
  global `setCameraDisabled` stays the PIN lock's alone. Revoking a granted camera kills the app, so while a call is
  live (`WindowRelock.liveCall`, now `internal`) a denial for an app that holds the camera (`checkPermission`) waits
  (`CameraPlan.deferred` -> `AppEnforcer.cameraDeferred`, memory), and `BootHome.callMayHaveEnded` begins by applying
  again once no call is live; one that doesn't hold it is denied at once. With `null` nothing is written except in a
  window: every DENIED target goes to DEFAULT. **The pause and the override lift it** (the standing rule; design 68 Q1),
  the relock's apply denies again. `AppEnforcer.applyCameraPermissions` runs **after** the suspend/hide loop (a hidden
  app can't be read without `MATCH_UNINSTALLED_PACKAGES`; the pass that unhides it comes first), from the same read of
  the requested permissions as the call pass (`thirdPartyRequested`); writes go through `grantWrite` and
  `writeGrantState`, which logs a `false` from `setPermissionGrantState` (the call pass's too). `enforceCameraOnPackage`
  runs the rule for one package on every install (`enforceOnNewPackage`, outside its managed-calls branch, before it
  can hide the app) and every package change (`Application.onPackageChanged`, outside the relock branch), so an install
  or an update that adds CAMERA is denied before its first start; it takes `apply`'s lock (`@Synchronized`), so an
  install callback at a window's edge can't land between the window's DEFAULT and the next apply. A hidden target is
  skipped (Android can't grant-state a hidden package; the pass that unhides it comes first). The call-end wake's
  decision is the pure `deferredCameraApplyDue`.
- **The special accesses** (§2.2-§2.3, `server/AppAccess.kt`, `AppAccessTest`): `manage_media`, `exact_alarm`,
  `write_settings`, `usage_access`, `full_screen` (`SpecialAccess`: permission and app-op) - app-ops a device owner
  can't grant, approved only on Android's page for the app. `declaredAccesses` (only what the manifest declares; no
  `exact_alarm` for an app declaring `USE_EXACT_ALARM`); `AppAccessReader.states` reads another app's op with
  `unsafeCheckOpRawNoThrow` (deprecated in API 36, `@Suppress`ed; needs no GET_APP_OPS_STATS) through the pure
  `readAccess` (a read that throws is `unknown`) and `accessState`: ALLOWED granted, IGNORED/ERRORED denied, DEFAULT as `PackageManager.checkPermission` says,
  `exact_alarm` also granted when battery-exempt or at DEFAULT below target 33, any other mode or a throw `unknown`.
  The emulator probe (`docs/testing/emulator.md` §6v step 0, the gate before the launcher RC) checks each op against
  `cmd appops get`; an op that disagrees ships as `unknown`. Usage access is named as Android 16 names it: "App usage
  data" / "Data om appbruk".
- **Status** (§3.3): `InstalledApp.access` (`Map<String, String>?`, `collectInstalledApps` reads with
  `GET_PERMISSIONS`; `AppAccessReader.report`), for third-party apps but ours only (a system app's privileged grants
  say nothing a parent can change; the code QA's L2): `camera` (`requested` | `admin_denied` - the grant state DENIED)
  when it requests it, then each declared access; left out when empty. Contract
  `installed_apps_report.json` (`SyncScheduleTest`). Capability `app_permissions_v1` (`APP_PERMISSIONS_CAPABILITY`).
- **"App access" in the PIN-gated Settings** (§4.2, `SettingsFragmentLauncher.updateAccessRows`): the
  `PreferenceCategory` `settings_mdm_app_access` ("App access" / "Tilgang for apper"), last in `preferences.xml` (after
  "Pause all restrictions", so no other row falls under its header; hidden while empty), filled in code off the main
  thread: one row per allowed third-party app (`accessEligible`, the report's rule: never a system app - E4's M3; the
  effective allowlist, every one while unmanaged; not ours, not in `AppEnforcer.lastPlanSuspend`) and per declared
  access **in any state** (`accessRows`), titled
  "<app>: <access>". A tap opens Android's page for that app (`accessPageActions`: the `package:` page, then the plain
  action, each through `systemPageIntent` - so only in the pause or the override); the summary is `accessRowText` of
  the state and `systemPageAccess` ("Approved - tap to change" / "Approved - pause first to change it", "Not approved -
  tap, turn on <app>'s switch, then come back" - for `exact_alarm` "..., come back, then open <app> once while
  restrictions are paused" (Etar's first start outside a pause crashes on its own battery-exemption request, E4's M2) -,
  "Can't be read here - tap to open the page", pause first, a moment, no page). The Settings gate stays open across
  Android's page a row opened (`SettingsActivity.ownPageOpening`, pure `ownPageKeepsGate`: the stop within 3 s of the
  open, back within 10 minutes, once; E4's L3); any other leave asks again. Read again on resume, after each apply (`addAppliedListener`) and on return, where a changed state requests a
  sync (`app_access`). Display over other apps and battery "Unrestricted" get no row (§2.2). `KidScreensEscapeTest`
  stays green: the intents live in `server/`, behind the gate.
- Not device-tested: `docs/testing/emulator.md` §6v (the probe, Element X's camera, Immich, Etar) and the Jelly Star run.
