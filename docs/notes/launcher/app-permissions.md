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
  the default; missing or not a list - a `Fallback` - is `null` = touch nothing; not in
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
- **Kamera, Posisjon, Mikrofon, Kontakter** (design 127 step 2, `docs/design/127-app-page.md` §4.1; the camera
  mechanism above, widened - its rules all stand, renamed): `AccessKey` (`server/EnforcementPlan.kt`; `CAMERA`,
  `LOCATION` - fine, coarse, background -, `MICROPHONE`, `CONTACTS` - read and write; an app asks for a key when it
  requests any of its permissions, and each requested one is denied). `appPermissionsOf` is now
  `Map<String, Set<AccessKey>>?` (`[{package_name, camera?, location?, microphone?, contacts?}]`, each `"deny"`; an entry
  with nothing known off dropped; `null` still touches nothing). The pure `accessGrants` works per (package,
  permission) (`Grant`) over `accessTargets`: third-party apps but ours and never `ACCESS_NEVER` (Vibb One and Vibb
  music, either build) while signed with the launcher's own certificate (`MusicTrust.sameSigner`; one of those names
  with another signer gets every key it asks for denied, policy or none, until a window); DENIED while the key is off outside a window, else DEFAULT, **never GRANT**, only on a change;
  while a call is live a permission the app holds waits (`AccessPlan.waiting` -> `AppEnforcer.accessDeferred`,
  `BootHome.callMayHaveEnded` through `deferredAccessApplyDue`). `AppEnforcer.accessPass` (after the loop, hidden apps
  skipped) and `enforceAccessOnPackage` (every install and package change, under `apply`'s lock) replace the camera's.
  A DENIED write's `false` or throw is recorded per (package, key) in memory (`AppEnforcer.accessRefused`,
  `accessRefusedAfter`: a pass that works on the key and doesn't leave it waiting clears it). The report
  (`AppAccessReader.report`, `accessReports`/`grantReport`): each key the app asks for, `refused`, else `admin_denied`
  when every requested permission is DENIED, else `requested`; none for `ACCESS_NEVER`. The pause and the override
  lift all four (the standing rule). Contract `app_access.json` (`AppAccessContractTest`), the launcher's own rows
  `app_access_rules.json` (`AppAccessRulesTest`). Not device-tested: design 127's step 0 probe and step 4 gate.
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
  say nothing a parent can change; the code QA's L2): the grant keys it asks for (since design 127 step 2: `camera`,
  `location`, `microphone`, `contacts`, each `requested` | `admin_denied` | `refused`), then each declared access; left
  out when empty. Contract
  `installed_apps_report.json` (`SyncScheduleTest`).
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

## Vibb One's own notifications turned off (design 107, `docs/design/107-blocked-channels.md`)

- **The report**: every full report carries `notification_channels` (`notify/NotificationChannels.kt`, contract
  `notification_channels.json`, `NotificationChannelsTest`): `all_off`
  (`NotificationManagerCompat.areNotificationsEnabled()` false) and `blocked`, the table's keys (`ReportedChannel`,
  each channel's id without `launcher:`) of the channels at `IMPORTANCE_NONE` or in a blocked group, in the table's
  order. One read at each report build (`NotificationChannelsReader.read`: `notificationChannels`,
  `notificationChannelGroups`, the app switch; the pure `channelsReport`); any failure leaves the field out (the PWA
  then shows nothing). Snarveier counts only as the channel in use (`ShortcutsRuntime.currentChannelId`); an older
  `.N` id, an unknown id and Android 16's reserved `android.app.*` channels are skipped; a channel that doesn't exist is
  not off. Silent (LOW) channels aren't reported (Q6). Not a heartbeat field: a change is in the hash, so it goes as a
  full report.
- **The role lock**: while Vibb holds the dialer role (calls managed) every `launcher:` channel is locked
  (`NON_BLOCKABLE_DEFAULT_ROLES`), so the long-press sheet can't turn one off. **SMS managed with calls "Ikke styrt"
  takes only the SMS role, which locks nothing** (gate §6ax step 3): there the kid can turn Meldinger off from the
  sheet, and the PWA names it (the feature counts). The report also matters on an unmanaged phone, after a failed role
  take, for a block that survives the role (§6ax step 3), another SystemUI and Android 17. While the dialer role is
  held, the row's "Trykk for å slå på" also says to hand the role back first (`settings_notifications_open_role`):
  Android's page greys an off switch then.
- **Settings' "Vibb One notifications" row** (`settings_mdm_notifications`, after "Modify system settings"; the same
  page-row pattern): shown only while a channel other than Snarveier's, or the whole app, is off (the same reader, off
  the main thread; `notificationsRowText`). Its summary names them in the phone's words, quoted, then by
  `SystemPageAccess`: "Trykk for å slå på", "Sett alle begrensninger på pause først", the wait and no-page words. The
  page is `ACTION_APP_NOTIFICATION_SETTINGS` with `EXTRA_APP_PACKAGE` through `systemPageIntent`, so in the kiosk
  nothing resolves and a tap opens nothing; in the pause Android's page opens, and the return asks for a sync when the
  read changed. Re-read on resume, after a tap, at the pause's start and after each apply (`updateSystemPageRows`).
  Vibb never raises a channel the kid turned off (Q4): no remote re-enable.
- **A stale block under the role** (gate §6ax step 3, Android 14 and 16): a channel turned off while the role was away stays
  at NONE when Vibb takes the dialer role again, now locked - and Android's page then greys its switch too, so it can't
  be turned on in a pause either. The fix is to hand the role back first (calls and SMS to "Ikke styrt" in the PWA),
  turn it on in the pause, and manage them again; the PWA's ⓘ says so.
