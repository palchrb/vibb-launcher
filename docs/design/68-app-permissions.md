# Design 68: per-app permissions - camera off per app, and the special accesses "approved on the phone"

Revised 2026-10-26 after `docs/review/qa-68-design.md` (every finding applied).

Round B1 (PLAN.md; `docs/plan/2026-10-25-queue-rounds.md`), branch `app-permissions`, launcher and server. The policy
keys, the silent-grant vs "approve on the phone" split, the rows by `systemPageIntent`, never adb.

## 1. Why

- **Camera off per app**, and a permissions line per app in the PWA (approved 2026-10-25).
- **Media management for Immich** (the user: "å rydde opp etter lokal opplasting"), plus Modify system settings and
  usage access: special accesses a device owner can't grant. "Approve on the phone" through a Settings row that opens
  Android's page for that app. Never adb.
- **Alarms & reminders for Etar** (2026-10-26, `jellystar2`): Etar sends the kid to Settings, hidden while the phone is
  managed, so its first start fails; a pause let it through and the grant stayed.

Non-goals: granting a sensor silently, microphone/location per app, "Display over other apps" (§2.2), a catalog-wide
default (§7 Q3).

## 2. The model: one row per phone and package, two kinds of control

### 2.1 Silent: camera off (a runtime permission, `setPermissionGrantState`)

AOSP `DevicePolicyManager.setPermissionGrantState` (main, checked 2026-10-26): runtime permissions only; DEFAULT (the
user decides), DENIED ("the user cannot manage it through the UI"), GRANTED. Sensor permissions are restricted since S:
a profile owner may only deny; a device owner may grant unless provisioning sent
`EXTRA_PROVISIONING_SENSORS_PERMISSION_GRANT_OPT_OUT` (our QR doesn't). A refused grant **throws `SecurityException` for
our target 36**. DEFAULT "does not revoke the permission".
- Decision: **camera off = DENIED, camera back = DEFAULT; we never GRANT a sensor.** Denying works for every admin kind
  on API 34-36 (`minSdk` 34; the Jelly Star runs 16). The global `setCameraDisabled` stays the PIN lock's alone
  (`lock/CameraLockPlan.kt` `cameraPolicyDisabled`).
- DENIED sets the policy fix and revokes (PermissionController `PermissionControllerServiceImpl`); DEFAULT only clears
  the fix: the app asks again and the kid's dialog decides (the permission controller is a lock-task helper).
- **Revoking a granted permission kills the app's process** (PMSI `killUid`); revoking one the app never held returns
  before the kill. L2's `phoneStateGrants` rule is "never revoke: reset to DEFAULT" (`EnforcementPlan.kt:283-291`), and
  the CALL_PERMISSIONS denial revokes with no call check (`AppEnforcer.kt:723-727`). **The call deferral (§3.2) is new
  here**: a DENIED for an app that holds the camera waits until no call is live; most apps don't hold it, so most
  denials land at once.

### 2.2 Approve on the phone: special accesses (app-ops a device owner can't grant)

The five permissions are `signature|...|appop` (the last `normal|appop`) in the core manifest, so a third-party app
holds them only through the app-op, and only Android's page sets it. The DPM has nothing: `setPermissionGrantState` is
runtime-only, `setApplicationExemptions` needs a role permission a device owner lacks.

| Access (wire key) | Permission, op | Needed here by | Request intent (`package:` data, then the plain action) |
|---|---|---|---|
| Media management (`manage_media`) | `MANAGE_MEDIA`, `android:manage_media` | Immich (`app.alextran.immich`) | `ACTION_REQUEST_MANAGE_MEDIA` (API 31) |
| Alarms & reminders (`exact_alarm`) | `SCHEDULE_EXACT_ALARM`, `android:schedule_exact_alarm` | Etar (`ws.xsoh.etar`) | `ACTION_REQUEST_SCHEDULE_EXACT_ALARM` (API 31) |
| Modify system settings (`write_settings`) | `WRITE_SETTINGS`, `android:write_settings` | none known; Vibb's own row stays | `ACTION_MANAGE_WRITE_SETTINGS` (the row we have, per package) |
| Usage access (`usage_access`) | `PACKAGE_USAGE_STATS`, `android:get_usage_stats` | none known | `ACTION_USAGE_ACCESS_SETTINGS` (SDK: "Input: Nothing"; Settings' `AppUsageAccessSettingsActivity` takes `package:`) |
| Full-screen notifications (`full_screen`, Q2) | `USE_FULL_SCREEN_INTENT`, `android:use_full_screen_intent` | a VoIP app whose ring lost it (`lock_state.voip_fsi_denied`) | `ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT` (API 34) |

- Settings has the `package:` page for all five; the plain actions are the fallbacks and resolve only while Settings is
  unhidden (the write-settings row's path). A row exists only for an app whose manifest **declares** the permission.
- **Etar is the app's choice**: `USE_EXACT_ALARM` (API 33, `normal`) is meant for calendar and alarm apps; Fossify
  Calendar and Clock declare it and get no exact-alarm row, Etar declares only `SCHEDULE_EXACT_ALARM` (denied by default
  for targets >= 33). Signal declares it without needing it and gets the row too, in neutral words (§4).
- **Not offered**: Display over other apps (it draws over Home and the kiosk; only Fossify Clock declares it, and it
  doesn't need it on 14+). That means no row only: a pause still lets anyone grant it, and it outlasts the relock (an
  existing pause property); a later round could report it read-only. Battery "Unrestricted" too: every family app asks
  for it itself, and that works in a pause.
- Immich, Element X and WhatsApp need none of the first four. Element X and WhatsApp request CAMERA and get the camera
  switch; **Immich's manifest asks for no camera, so no camera switch.**

### 2.3 Reading an access: the launcher can read another app's app-op

AOSP `AppOpsService.checkOperation` runs `validateOpRequest(.., shouldVerifyUid = false, ..)`: only the
`ACCESS_RESTRICTED_SETTINGS` op is read-restricted, package visibility is covered by `QUERY_ALL_PACKAGES`
(manifest:11), and `verifyIncomingUid` is never reached, so **`AppOpsManager.unsafeCheckOpRawNoThrow(op, uid, package)`
needs no `GET_APP_OPS_STATS`**. It is deprecated on main (FlaggedApi) but fine on 34-36; a throw is `unknown`. Pure
`accessState(mode, hasPermission, ignoresBatteryOpt, targetSdk, kind)`:
- `MODE_ALLOWED` = `granted`; `MODE_IGNORED`/`MODE_ERRORED` = `denied`.
- **`MODE_DEFAULT` = `PackageManager.checkPermission(permission, package) == PERMISSION_GRANTED`**, as the platform's
  `PermissionChecker` does: a privileged, preinstalled or role app holds the permission with the op at DEFAULT
  (`MANAGE_MEDIA` is `signature|appop|preinstalled`, `WRITE_SETTINGS` adds `preinstalled|pre23|role`,
  `PACKAGE_USAGE_STATS` `privileged|development`), and `USE_FULL_SCREEN_INTENT` is `normal|appop`: granted whenever
  declared.
- **`exact_alarm` is also `granted`** when `PowerManager.isIgnoringBatteryOptimizations(package)` holds
  (`AlarmManagerService.isExemptFromExactAlarmPermissionNoLock`: an app on the device-idle allowlist, as the family
  apps are after a pause, sets exact alarms without the op), and with DEFAULT for a target < 33
  (`hasScheduleExactAlarmInternal`).

Step 1's probe checks each op against `cmd appops get <package> <OP>` on `jelly36` (the oracle), with test apps for
`write_settings` and `usage_access` (no family app declares them), and is repeated on the Jelly Star. An op whose
reading disagrees ships as `unknown` (§4.2).

## 3. The policy contract

Design 69 (Dagen) adds `launcher_ui.background_apps` and touches the same Device › Apps rows (the "On Home" switch):
**68 merges first, 69 builds on it.**

### 3.1 Policy: `app_permissions` (always a list; `policy_json_keys_snapshot` + `PolicyResponseCompatTest`)

```json
"app_permissions": [{"package_name": "com.whatsapp", "camera": "deny"}]
```

- One entry per package the parent switched away from the default on this phone (today `camera: "deny"`; later keys are
  additive). **The phone works out which special accesses an app wants from its manifest** (`requestedPermissions`);
  the server sends none of them.
- Server: table `device_app_permissions` (device_id cascade, package_name, `camera` 'deny', `updated_at`; PK device +
  package; at most 200 per phone; no row = default; **the next free migration number at merge**, with
  `migrations_73_to_101_in_order`, `tests/music_spotify.rs:193`, moved up). The PWA's form is the only writer (§4). A
  failing query is a 500 for the whole policy (fail closed), never `[]` in place of the rows: a lost list would hand the
  camera back.
- Launcher: `PolicyResponse.appPermissions: JsonElement?`, read field by field by the pure `appPermissionsOf`, so
  nothing in it can fail the policy. An entry without a valid package is dropped; any `camera` but `"deny"` is the
  default. **A missing or non-list key (an older server, a `Fallback`) means "touch nothing"**, except in a window
  (§3.2). A denial lives in Android's grant state and survives a lost cache; `LastEnforcedPlan` doesn't carry it.

### 3.2 The launcher's apply (L2's per-app grant pattern, with three differences)

- Pure in `server/EnforcementPlan.kt`: `cameraGrants(requested, ownPackage, denied: Set<String>?, windowOpen, callLive,
  held: Set<String>, current: Map<String, Int>): Map<String, Int>`. Targets: **third-party (non-`FLAG_SYSTEM`)
  requesters of `CAMERA`**, never our package, as `callPermissionTargets` (`EnforcementPlan.kt:275`; the filter at
  `AppEnforcer.kt:714-717`). For each: DENIED when in `denied` and no window is open, else DEFAULT. **While `callLive`, a
  target going to DENIED from another state that is in `held` (holds the camera now, `checkPermission`) is left out**;
  one that doesn't hold it is denied at once. With `denied == null` nothing is written, **except in a window: every
  target reading DENIED goes to DEFAULT** (we are the only admin; a `Fallback` phone's pause must lift it).
- `applyCameraPermissions` is **a pass after the suspend/hide loop** (`AppEnforcer.kt:417-476`, before
  `lastEnforcedLockKey`), not beside `applyCallPermissions` (`:414`, before the loop): the admin path reads the package
  with `getPackageInfo(pkg, GET_PERMISSIONS)` and no `MATCH_UNINSTALLED_PACKAGES`, so a hidden app probably can't be
  denied, and the apply that unhides it must come first. It reuses `grantWrite` (written only when it differs;
  re-setting re-notifies) and **logs a `false` result** (`setGrantState`, `:734-741`, ignores the boolean).
- **Install and update**: `enforceCameraOnPackage` runs the rule for one package, **outside**
  `enforceOnNewPackage`'s `if (CallPolicyStore.state.managed)` (`:1250`; before its `if (!suspend) return`) and outside
  `onPackageChanged`'s `WindowRelock.onPackageInstalled` branch (`Application.kt:95-115`), so a new install or an update
  adding CAMERA is denied before its first start. A hidden new app is denied by the apply that unhides it.
- **A deferred denial is woken**: `WindowRelock.liveCall(app)` (`WindowRelock.kt:602`, made `internal`: ours, Telecom's
  with unknown as a call, VoIP, the emergency flow) decides; a deferral sets `CameraGrants.deferred` (memory), and
  `BootHome.callMayHaveEnded` (`BootHome.kt:101`: our call's end, the VoIP phase's end, every screen-on;
  `PinLockRuntime.kt:976, 1084, 1116`) begins with `if (deferred && !liveCall) { deferred = false; apply() on IO }`,
  before its own `waitingForCall` return. Without it the next apply is a nudge or the 30-minute backstop
  (`SyncSchedule.kt:17`).
- **Lifted by the override and the pause** (launcher CLAUDE.md, "every restriction ... liftable"; §7 Q1): the window's
  plan writes DEFAULT, the relock's `apply()` DENIED again; that DENIED kills only an app granted the camera in the
  pause. The call deferral applies to it too.

### 3.3 Status: `installed_apps[].access` (contract `installed_apps_report.json`, both dirs)

- Only the keys the app declares. `camera` (`requested` | `admin_denied` = the grant state is DENIED) **only for §3.2's
  targets**: `collectInstalledApps` also reports system apps (`MdmSyncWorker.kt:1066-1080`; its `getPackageInfo` gains
  `GET_PERMISSIONS`, today `MATCH_UNINSTALLED_PACKAGES` only), and an allowlisted preinstalled camera or Google app gets
  no key and no switch. Each of `manage_media`, `exact_alarm`, `write_settings`, `usage_access`, `full_screen`:
  `granted` | `denied` | `unknown` (§2.3); `exact_alarm` is left out for an app holding `USE_EXACT_ALARM`. An empty object
  is left out.
- The server keeps known keys and values only, within the M7 caps (`cap_installed_apps`, `device_api.rs:745-772`), and
  parses `access` with a **`lenient_*` deserializer** like `version_code`/`version_name` (`models.rs:919-932`): a new key
  or value from a newer launcher is dropped, never a refused report (the contract test posts one). Capability
  `app_permissions_v1` (launcher `STATUS_CAPABILITIES`, `StatusCapabilities.kt:11`; server constant beside
  `ZONES_CAPABILITY`, `models.rs:690`). An older launcher ignores the policy key (`ServerJson.kt:16`).
- **One contract commit, both directories**: `installed_apps_report.json` gains `access` on its first row (Signal; the
  launcher's `SyncScheduleTest` encodes it, the server's `app_updates::the_installed_apps_contract_raises_app_updated`
  posts it and checks the stored `access`), the policy snapshot gains `app_permissions` on both sides, and the launcher
  DTO, the table and the policy builder go in the same commit.

## 4. What the parent and the phone show

### 4.1 PWA, Device › Apps (`device_apps.html`, `partials/device_app_row.html`)

- An allowed app with something to say gets a second sub-line, in neutral words: "Camera off · Media management: not
  approved". Parts, in order: `Camera off` (server row set and the report reads `admin_denied`; "Camera off · waiting
  for the phone" while it reads `requested`), then per declared access `<name>: approved` / `not approved` / `unknown`.
  How to approve is only inside the details. No line when nothing applies.
- **Update note**: an older launcher reports no `access`. While the phone has reported its apps and lacks
  `app_permissions_v1` (`device_pages::has_capability`, `:438`), one `row-note` "Update Vibb One to manage apps'
  permissions" shows above the Allowed list (`device_apps.html`, before `<section id="allowed">`).
- Under the row, next to "Name and icon", a `<details>` "Permissions". The camera switch shows **only when the report
  has `access.camera`**, with its help: "Off: <app> can't use the camera - a video call or a QR scan stops working.
  Turning it off closes <app> if it was allowed the camera." Each declared access is one line, with the way to approve it
  while it isn't: "On the phone: Settings (parent code) › Pause all restrictions › App access › Immich: Media
  management".
- **The details stays open after a save.** Autosave reloads through `form.submit()` (`autosave.js:1-3`);
  `scroll-restore.js` keeps the scroll, not `<details>` state. "Name and icon" renders open from its form
  (`app_display_form.html:2`: `<details id="{{ form.anchor }}"{% if form.open %} open{% endif %}>`, `app_display.rs:321`)
  but only for a refused save, an answer rather than a redirect. Permissions get their own anchor `perms-<package>`
  (built like `app_anchor`, `devices.rs:1762`): the form's action is `/devices/{id}/apps/permissions#perms-<package>`,
  the 303 goes to `apps_url("perms=<package>", from, "perms-<package>")`, and `view_apps` (`device_pages.rs:1737`)
  renders that package's `<details>` with `open` when `?perms=` names it.
- `POST /devices/{id}/apps/permissions` (`package_name`, `camera` = `deny`/`default`, the hidden `from`) validates,
  writes, nudges the phone and **never jumps to the top** (the PWA rule). Refused at its row (`?notice=&app=`) for a
  package outside the grammar, Play's core or Vibb; unknown values are a 400. Tests extend
  `device_pages::apps_forms_return_to_their_rows` (`tests/device_pages.rs:1902`): the new form's fragment, the 303, the
  open details.

### 4.2 The phone: rows in the PIN-gated Settings, under "Pause all restrictions"

- A `PreferenceCategory` "App access" / nb "Tilgang for apper", added in code to the flat `res/xml/preferences.xml`
  after the "Modify system settings" row (`SettingsFragmentLauncher`), parent-only behind the Settings gate, hidden when
  empty. One row per allowed app (the effective allowlist: relock blocks out; every third-party app while unmanaged),
  installed and not suspended by policy, **and per declared access in any state**, so the parent can take an approval
  back. Titles "<label>: Media management" / "Alarms & reminders" / "Modify system settings" / "Usage access" /
  "Full-screen notifications"; nb "Medieadministrering" / "Alarmer og påminnelser" / "Endre systeminnstillinger" (our
  existing row) / "Brukstilgang" / (Android's nb name, checked on the emulator).
- Pure `server/AppAccess.kt`: `accessState(...)` (§2.3), `accessRows(...)`, `accessPageIntents(kind, package)`. The
  intents go through `systemPageIntent` (`BatteryExemption.kt:58`): the package page first, then the plain action; they
  resolve only in the pause or the override. Summaries are `writeSettingsRowState`'s states through `systemPageAccess`
  (`WriteSettingsAccess.kt:31`), plus: granted "Approved - tap to change" (not reachable: "Approved - pause first to
  change it"), not approved "Not approved - tap, turn on <app>'s switch, then come back", `unknown` "Can't be read here
  - tap to open the page" (the row stays). On return the state is read again and a change requests a sync; re-read on
  resume, at the pause's start and after each `apply()` (`addAppliedListener`, `AppEnforcer.kt:266`).
- `KidScreensEscapeTest` stays green (the intents live in `server/`, behind the PIN gate).

## 5. Edge cases

- **An app updates and drops or adds a permission**: rows and `access` follow the manifest at the next read; a server
  row for an app that no longer asks is inert and the PWA hides its switch. An added CAMERA is denied by
  `enforceCameraOnPackage`. A reinstall is denied again within seconds (a grant in that gap is revoked, the app killed).
- **The pause ends while Android's page is open**: as with today's write-settings row (launcher CLAUDE.md, "The relock",
  step 6): Settings is hidden again, Home comes to the front, a flipped switch is already written, an unflipped row is
  still there next time.
- **A camera-off app that breaks without the camera**: `requestPermissions` returns denied with no dialog; a careless
  app may crash. That is the parent's choice, and the help text says what it does.

## 6. Steps (branch `app-permissions`, one round; sizes) and checks

1. **Contract commit, both dirs** (S): the migration, the policy builder and snapshot, `PolicyResponse.appPermissions` +
   `PolicyResponseCompatTest`, `installed_apps_report.json` with `access` (byte-identical copies), the intake's cap and
   lenient parse, capability `app_permissions_v1`. Before it: **the emulator probe** (§2.3), each of the five ops for an
   app that has the access and one that doesn't.
2. **Launcher camera** (S): `cameraGrants`, `applyCameraPermissions` after the loop, `enforceCameraOnPackage`, the
   call-end wake; `EnforcementPlanTest`.
3. **Launcher readback** (S): `accessState`, `InstalledApp.access` in `collectInstalledApps`; `AppAccessTest`.
4. **Launcher rows** (M): `accessRows`, the intents, the category, en + nb strings (`TranslationsTest`).
5. **Server PWA** (M): the sub-line, the update note, the `<details>` with its anchor, the handler, `device_pages` tests.
6. The docs: launcher/CLAUDE.md, `docs/notes/server/pwa.md` "Device › Apps", PLAN.

M overall: a launcher RC and a server minor. **Tests (lean):** `cameraGrants` (deny, back to default,
window open, `denied == null` in a window, call live with and without the camera held, our own and system apps out);
`accessState`'s mode table (DEFAULT with and without the permission, exact alarm battery-exempt / target < 33 /
`USE_EXACT_ALARM`, full-screen); `accessRows` (granted rows kept); the intents' order; the contract copies and the
lenient parse; the server's validation and redirects. QA mutations: about 10.

**Emulator checks:**
- Element X, camera off: `dumpsys package io.element.android.x` shows `CAMERA: granted=false` with `POLICY_FIXED`, and
  Settings' camera switch is locked by the admin (note the exact words). Back to default: the flag is gone, the app asks.
- Camera off during an Element X call: waits for the call's end if it holds the camera, else written at once. A denial
  on a hidden app: written by the apply that unhides it. A preinstalled camera app: no `camera` key, no switch.
- Immich: the PWA says "Media management: not approved"; the row says to pause first; in the pause it opens Android's
  page for `app.alextran.immich`; turned on and back, the row reads "Approved - tap to change" and the report `granted`.
- Etar: "Etar: Alarms & reminders" approved in a pause; after it **Etar's first start works with no pause**. Fossify
  Calendar has no row. The pause ending with Immich's page open: Home comes to the front, the row stays.
- `me.vibb.music.debug`: no row, no camera switch.

## 7. Open questions for the user (defaults in force until answered)

1. **Does "Pause all restrictions" lift "camera off"?** Default: **yes**, by the standing rule.
2. **A fifth access, "Full-screen notifications"** (§2.2)? Default: **yes**, shown only for an app that declares it.
3. **A catalog-wide "camera off on every phone"**? Default: **no**, per phone only, like the allowlist switch.
