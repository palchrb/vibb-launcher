# Design 68: per-app permissions - camera off per app, and the special accesses "approved on the phone"

Round B1 (PLAN.md, "Round B1 - per-app permissions"; `docs/plan/2026-10-25-queue-rounds.md`, "Round B1"), branch
`app-permissions`, launcher and server. Short note: the policy keys, the silent-grant vs "approve on the phone" split,
the rows by `systemPageIntent`, never adb.

## 1. Why

- **Camera off per app**, and a permissions line per app in the PWA (approved 2026-10-25).
- **Media management for Immich** (the user: "kunne tenke meg å kunne slå den på for å rydde opp etter lokal
  opplasting"), plus Modify system settings and usage access: special accesses a device owner can't grant. Each one is
  "approve on the phone" through a Settings row that opens Android's page for that app. Never adb.
- **Alarms & reminders for Etar** (2026-10-26, `jellystar2`: "prøver å starte etar nå men får beskjed om at den
  feiler ... jeg satt restrictions på pause og da gikk det"): Etar sends the kid to Android's Settings, which is hidden
  while the phone is managed, so its first start fails. A pause let it through, and the grant stays.

Non-goals: granting a sensor permission silently ("camera always on"), microphone/location per app, "Display over other
apps" (§2.2), a catalog-wide default (§7 Q3).

## 2. The model: one row per phone and package, two kinds of control

### 2.1 Silent: camera off (a runtime permission, `setPermissionGrantState`)

What Android allows (AOSP `DevicePolicyManager.setPermissionGrantState` javadoc, main; the same text since S):
- It sets the grant state of a **runtime** permission only: DEFAULT (the user decides), DENIED (denied, "and the user
  cannot manage it through the UI"), GRANTED. Since Android S the sensor permissions (fine/coarse/background location,
  **CAMERA**, RECORD_AUDIO, ACTIVITY_RECOGNITION, BODY_SENSORS) are restricted: a profile owner may only **deny** them,
  and a device owner may still grant them **unless** provisioning carried
  `EXTRA_PROVISIONING_SENSORS_PERMISSION_GRANT_OPT_OUT`. Then it may only deny them too. A refused grant fails silently
  for an admin targeting < 35. **For our target 36 it throws `SecurityException`.**
- "Setting the grant state to DEFAULT does not revoke the permission. It retains the previous grant, if any."
- Decision: **camera off = DENIED, camera back = DEFAULT; we never GRANT a sensor.** Denying is allowed for every admin
  kind on API 34-36, and the Jelly Star runs Android 16 (design 56; our `minSdk` is 34). So the Android 12+ sensor rule
  never bites this round, and neither does the opt-out (our QR provisioning doesn't send it - no mention in `launcher/`
  or `server/`). The global `setCameraDisabled` stays the PIN lock's alone (`lock/CameraLockPlan.kt`
  `cameraPolicyDisabled`): there is no parent "camera off" hardening switch today, and that switch would cover every app.
- Going back: DENIED revoked the grant, so DEFAULT leaves the app without the camera but asking again. The kid's
  dialog decides (the permission controller is already a lock-task helper).
- A DENIED write revokes the camera of a running app, and Android kills the app's process on a revoke (L2's
  `phoneStateGrants` rule: never revoke during a call). So **while a call is live (ours, Telecom's, VoIP) a new DENIED
  waits for the next `apply()` after the call** (§3.2).
- The state is readable for any package: `getPermissionGrantState` and `PackageManager.checkPermission`.

### 2.2 Approve on the phone: special accesses (app-ops a device owner can't grant)

All four permissions are `signature|...|appop` in the core manifest (main), so a third-party app holds them only through
its app-op, and only Android's own page can set it. The DPM has nothing for them: `setPermissionGrantState` is
runtime-only, and `setApplicationExemptions` needs `MANAGE_DEVICE_POLICY_APP_EXEMPTIONS`, a role permission a device
owner lacks; its exemptions are standby/suspension/power ones anyway.

| Access (wire key) | Permission, op | Who needs it in this family | Request intent (`package:` data, then the plain action) |
|---|---|---|---|
| Media management (`manage_media`) | `MANAGE_MEDIA`, `android:manage_media` | Immich (`app.alextran.immich`): delete after upload without a prompt per file (its manifest declares it) | `ACTION_REQUEST_MANAGE_MEDIA` (API 31; "Optionally, the data URI can specify the package") |
| Alarms & reminders (`exact_alarm`) | `SCHEDULE_EXACT_ALARM`, `android:schedule_exact_alarm` | Etar (`ws.xsoh.etar`, declares only SCHEDULE_EXACT_ALARM) | `ACTION_REQUEST_SCHEDULE_EXACT_ALARM` (API 31; result OK when granted) |
| Modify system settings (`write_settings`) | `WRITE_SETTINGS`, `android:write_settings` | none known; the user's list. Vibb's own row stays as it is | `ACTION_MANAGE_WRITE_SETTINGS` (the row we have, now per package) |
| Usage access (`usage_access`) | `PACKAGE_USAGE_STATS`, `android:get_usage_stats` | none known; the user's list | `ACTION_USAGE_ACCESS_SETTINGS`: the SDK says "Input: Nothing", but AOSP Settings' `AppUsageAccessSettingsActivity` takes `package:` (`UsageAccessDetails`); the plain list is the fallback |

- **Etar's case is the app's choice**: `USE_EXACT_ALARM` (API 33, `normal`, granted at install) is meant for calendar
  and alarm apps. Fossify Calendar and Fossify Clock declare it and need no row; Etar declares only
  `SCHEDULE_EXACT_ALARM`. On Android 14+ that is denied by default for targets >= 33 (`AlarmManager`
  `SCHEDULE_EXACT_ALARM_DENIED_BY_DEFAULT`, `@EnabledSince(TIRAMISU)`). An app that holds `USE_EXACT_ALARM` gets no
  exact-alarm row.
- **Not offered**: Display over other apps. It lets an app draw over Home and the kiosk, and the only app that declares
  it here is Fossify Clock, which doesn't need it on 14+. Battery "Unrestricted" too: every family app asks for it
  itself, and that works in a pause. Full-screen notifications are §7 Q2.
- Immich, Element X and WhatsApp need none of the four. Element X and WhatsApp do request CAMERA, so they get the
  camera switch. **Immich's manifest asks for no camera, so Immich gets no camera switch.**

### 2.3 Can the launcher read another app's app-op? Yes

AOSP `AppOpsService.checkOperation` (main) runs `validateOpRequest(.., shouldVerifyUid = false, ..)`. It checks the
op only if the op is `restrictRead`, and only `ACCESS_RESTRICTED_SETTINGS` is. It checks the package's visibility
(`filterAppAccess`), and we hold `QUERY_ALL_PACKAGES`. It never runs `verifyIncomingUid`, which is where
`UPDATE_APP_OPS_STATS` would be needed. So **`AppOpsManager.unsafeCheckOpRawNoThrow(op, uid, package)` needs no
`GET_APP_OPS_STATS`** for these four. The rule, pure: `MODE_ALLOWED` = granted. `MODE_IGNORED`/`MODE_ERRORED` = not
granted. `MODE_DEFAULT` = not granted, because a third-party app never holds the signature/appop permission behind it.
The one exception: `exact_alarm` for an app targeting < 33, where `AlarmManagerService.hasScheduleExactAlarmInternal`
counts DEFAULT as allowed. Step 1's emulator probe (§6) checks each op against Android's own switch. An op whose
reading disagrees ships as `unknown`, and its row shows until the parent has tapped it once (PLAN's fallback).

## 3. The policy contract

### 3.1 Policy: `app_permissions` (always a list; `policy_json_keys_snapshot` + `PolicyResponseCompatTest`)

```json
"app_permissions": [{"package_name": "com.whatsapp", "camera": "deny"}]
```

- One entry per package the parent switched away from the default on this phone. Today that means `camera: "deny"`;
  later keys are additive. **The phone works out which special accesses an app wants from its manifest**
  (`requestedPermissions`). The server sends none of them, since the parent has no switch for them.
- Server: table `device_app_permissions` (device_id cascade, package_name, `camera` 'deny', `updated_at`; PK device +
  package; at most 200 per phone; no row = default; the next migration number, 0102 today). The PWA's form is the
  only writer (§4). A failing query is a 500 for the whole policy (fail closed, as the allowlist is). It is never `[]`
  in place of the rows: a lost list would hand the camera back.
- Launcher: `PolicyResponse.appPermissions: JsonElement?` is read field by field by the pure `appPermissionsOf`, so
  nothing in it can fail the policy. An entry without a valid package is dropped, and any `camera` other than `"deny"`
  is the default. **A missing or non-list key (an older server, a `Fallback`) means "touch nothing"**: no write at all.
  A denial lives in Android's own grant state, so it survives a lost cache. `LastEnforcedPlan` doesn't carry it.

### 3.2 The launcher's apply (L2's per-app grant pattern, exactly)

- Pure in `server/EnforcementPlan.kt`: `cameraGrants(requested, ownPackage, denied: Set<String>?, windowOpen,
  callLive, current: Map<String, Int>): Map<String, Int>`. The targets are third-party requesters of `CAMERA`, never
  our own package (as `callPermissionTargets`). For each: DENIED when it is in `denied` and no window is open, else
  DEFAULT. While `callLive`, a target that would go to DENIED from another state is left out (kept as it is). With
  `denied == null` the result is empty.
- `AppEnforcer.applyCallPermissions`' neighbour `applyCameraPermissions` reuses the same `requested` map and
  `setGrantState` (`grantWrite`: written only when it differs - re-setting re-notifies, launcher CLAUDE.md
  `setPermissionGrantState` gotcha). `enforceOnNewPackage` (install) and `onPackageChanged` (update) run the same rule
  for that one package, so a new install or an update that adds CAMERA is denied before its first start.
- **Lifted by the override and the pause** (the standing rule, launcher CLAUDE.md "every restriction ... liftable";
  §7 Q1). The window's plan writes DEFAULT, and the relock's `apply()` writes DENIED again. The call deferral applies
  to that too.

### 3.3 Status: `installed_apps[].access` (contract `installed_apps_report.json`, both dirs)

- Only the keys the app declares: `camera` (`granted` | `denied` | `admin_denied` - the grant state DENIED) and each
  of `manage_media`, `exact_alarm`, `write_settings`, `usage_access` (`granted` | `denied` | `unknown`). `exact_alarm`
  is left out for an app that holds `USE_EXACT_ALARM`. The object is left out when it would be empty.
- The server keeps known keys and values only, within the M7 caps (`cap_installed_apps`). Capability
  `app_permissions_v1`: without it the PWA says "Update Vibb One to manage this" and offers no switch.
- **One contract commit, both directories**: `installed_apps_report.json` gains `access` on its first row (the
  launcher's `SyncScheduleTest` encodes it, the server's `app_updates::the_installed_apps_contract_raises_app_updated`
  posts it and also checks the stored `access`). The policy snapshot gains `app_permissions` on both sides. The
  launcher DTO, the server's table and its policy builder go in the same commit.

## 4. What the parent and the phone show

### 4.1 PWA, Device › Apps (`device_apps.html`, `partials/device_app_row.html`)

- An allowed app with something to say gets a second sub-line under its state:
  "Camera off · Media management: approve on the phone". The parts are, in order: `Camera off` (the switch is set; or
  "Camera off · waiting for the phone" until the report reads `admin_denied`), then per declared access
  `<name>: approved` / `<name>: approve on the phone` / `<name>: unknown`. No line when nothing applies.
- Under the row, next to "Name and icon", a `<details>` "Permissions". Camera is a switch (only when the report says
  the app requests the camera), with its help text: "Off: <app> can't use the camera. Parts that need it - a video
  call, a QR scan - stop working." Each declared access is one line, with the way to approve it while it isn't:
  "On the phone: Settings (parent code) › Pause all restrictions › App access › Immich: Media management".
  `POST /devices/{id}/apps/permissions` (`package_name`, `camera` = `deny`/`default`, the hidden `from`) autosaves,
  returns to `#app-<package>` (the `apps_url` rule), **never jumps to the top** (the PWA rule), and nudges the phone.
  It is refused at its row (`?notice=&app=`) for a package outside the grammar, Play's core or Vibb. Unknown values
  are a 400. Tests extend `device_pages::apps_forms_return_to_their_rows`.

### 4.2 The phone: rows in the PIN-gated Settings, under "Pause all restrictions"

- There is no separate pause screen. The rows go in a new category "App access" / nb "Tilgang for apper", next to the
  existing "Modify system settings" row (`SettingsFragmentLauncher`), parent-only behind the Settings gate. One row
  per allowed app (the effective allowlist: relock blocks out; every third-party app while unmanaged), installed and
  not suspended by policy, and per declared access whose state is `denied` (or `unknown` and not tapped yet). The title
  is "<label>: Media management" / "Alarms & reminders" / "Modify system settings" / "Usage access", in nb
  "Medieadministrasjon" / "Alarmer og påminnelser" / "Endre systeminnstillinger" / "Tilgang til bruksdata" (Android's
  own nb names - check them on the emulator in nb). The category is hidden when it has no rows.
- Pure `server/AppAccess.kt`: `accessState(...)` (§2.3), `accessRows(...)` and `accessPageIntents(kind, package)`.
  The page intents go through `systemPageIntent` (`MATCH_SYSTEM_ONLY` + `dialogTarget`): the package page first, then
  the plain action. They resolve only in the pause or the override (the window's plan unhides Android's Settings).
  The summaries are `writeSettingsRowState`'s states through the same pure `systemPageAccess`: CAN_OPEN "Not allowed -
  tap, turn on <app>'s switch, then come back", PAUSE_FIRST (today's text), WAIT "the pause is starting, a moment...",
  NO_PAGE ("no page" only once `lastEnforcedOverride` says the plan is applied), UNKNOWN "Can't be read here - tap to
  open the page". On return the state is read again, and a change requests a sync, as the write-settings row does.
  Re-read on resume, at the pause's start and after each `apply()` (`addAppliedListener`).
- `KidScreensEscapeTest` stays green: the new intents live in `server/` and are reached only from the PIN-gated
  fragment. No kid surface names them.

## 5. Edge cases

- **The app updates and drops or adds a permission**: rows and `access` follow the manifest at the next read. The
  camera rule targets requesters only, so a server row for an app that no longer asks is inert, and the PWA hides its
  switch. An added CAMERA is denied in `onPackageChanged` (§3.2). An uninstall clears Android's grant state, and the
  reinstall's `enforceOnNewPackage` denies again.
- **The pause ends while Android's page is open**: as with today's write-settings row. The end's `apply()` hides
  Android's Settings again, and `setApplicationHidden(true)` kills its process (launcher CLAUDE.md, "The launcher
  can't end another app's process"). The relock's step 6 brings Home to the front (same file, "The relock"). A switch
  flipped before that is already written (an app-op write takes effect at once). One not flipped stays `denied`, and
  the row is still there next time. Vibb's own Settings asks for the parent PIN again after any trip out (its gate ends
  at `onStop`). The result's re-read and the sync request run regardless.
- **A camera-off app that breaks without the camera**: a policy-fixed denial makes `requestPermissions` return denied
  at once, with no dialog. A well-behaved app (WhatsApp's camera button, Element X's QR scan) says so. A careless one
  may crash at the camera call. That is the parent's choice, and the help text says what it does (§4.1). The PIN
  lock's global camera policy is untouched.
- **A denial during a call**: it waits (§3.2). The pause's end during a video call too.
- **Android 12+ sensor grants**: never reached (we only deny or reset). If a later round adds "always allowed", it
  must handle the opt-out and the target-35 `SecurityException`.
- **An op unreadable on some build**: `unknown`, and the row shows until tapped once (CE prefs `app_access_tapped`,
  package + kind, cleared when the state reads `granted`).

## 6. Steps (branch `app-permissions`, one round; sizes) and checks

1. **Contract commit, both dirs** (S): the migration, the policy builder and its snapshot, `PolicyResponse.appPermissions`
   + `PolicyResponseCompatTest`, `installed_apps_report.json` with `access` (byte-identical copies), the status intake's
   cap, capability `app_permissions_v1`. Before it: **the emulator probe** - for each of the four ops, read
   `unsafeCheckOpRawNoThrow` for an app that has the access and one that doesn't, against Settings' switch.
2. **Launcher camera** (S): `cameraGrants` + `applyCameraPermissions` + the install/update path; `EnforcementPlanTest`.
3. **Launcher readback** (S): `accessState` + `InstalledApp.access` in `collectInstalledApps`; `AppAccessTest`.
4. **Launcher rows** (M): `accessRows`, the page intents, the fragment's category, en + nb strings
   (`TranslationsTest`); `AppAccessTest`.
5. **Server PWA** (M): the sub-line, the `<details>`, the handler (validate, write, nudge), `device_pages` tests.
6. The docs: launcher/CLAUDE.md, `docs/notes/server/pwa.md` "Device › Apps", PLAN.

The round is M overall: a launcher RC and a server minor.

**Tests (lean):** `cameraGrants` (deny, back to default, window open, call live, no key = empty, our own and system
apps out); `accessState`'s mode table (DEFAULT, exact alarm < 33, `USE_EXACT_ALARM` held); `accessRows` (which apps,
which rows, the tapped fallback); the page intents' order; the contract copies; the server's validation and redirects.
QA mutations: about 10.

**Emulator checks:**
- Element X allowlisted, camera off: `dumpsys package io.element.android.x` shows `CAMERA: granted=false` with
  `POLICY_FIXED`, and Settings' camera switch is locked by the admin (note the exact words). Back to default: the flag
  is gone and the app asks.
- Camera off during an Element X call: not written until the call ends.
- Immich: the PWA says "Media management: approve on the phone". The row says to pause first. In the pause it opens
  Android's Media management page for `app.alextran.immich`. Turned on and back: the row is gone, and the report says
  `granted`.
- Etar: the row "Etar: Alarms & reminders" is approved in a pause. After the pause, **Etar's first start works with no
  pause**. Fossify Calendar has no row.
- The pause ends with Immich's page open: the page goes, Home comes to the front, and the row is still there.
- `me.vibb.music.debug` has no row and no camera switch (it declares none of them).

## 7. Open questions for the user (defaults in force until answered)

1. **Does "Pause all restrictions" lift "camera off"?** Default: **yes**, by the standing rule. The relock denies
   again, which closes the app if it is open, unless a call is live.
2. **A fifth access, "Full-screen notifications"** (`USE_FULL_SCREEN_INTENT`, `ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT`
   + package, API 34, readable the same way): the launcher already reports `lock_state.voip_fsi_denied` when a VoIP
   app's ring lost its full-screen intent. Default: **yes, include it**, shown only for an app that declares it and
   lacks it.
3. **A catalog-wide "camera off on every phone"** on the app's catalog page? Default: **no**, per phone only, like the
   allowlist switch.
