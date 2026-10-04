# 02: Calls (default dialer, allowlists, screening) and the SMS switch

Status: design, 2026-10-04. Builds on [01-own-build.md](01-own-build.md): `build_policy`, `PolicyGate`,
`computeEnforcementPlan`, `ServerJson.kt` and `policyState` come from there.
`L` = `kids-launcher-mdm` (Kotlin paths relative to `app/src/main/java/com/kidslauncher/mdm/`), `S` = `kid-phone-server`.
Tags:
- **[verified]**: checked in AOSP source (`aosp-mirror/platform_frameworks_base`, `main`) or in our code during this design.
- **[belief]**: unverified.
- **[device]**: must be tested on the Jelly Star (Android 16, GMS).

## 1. Verified platform facts this design rests on

| Fact | Source |
|---|---|
| `DevicePolicyManager.setDefaultDialerApplication(String)`: DO only. It throws `IllegalArgumentException` if the package lacks the dialer activities/services, and does nothing without `FEATURE_TELEPHONY`. API 34 = our minSdk | DevicePolicyManager.java [verified] |
| ROLE_DIALER requires an `ACTION_DIAL` activity (plain + `tel:`) and an `InCallService` with `BIND_INCALL_SERVICE`, meta-data `android.telecom.IN_CALL_SERVICE_UI=true`, and must **not** be `exported="false"`. If the binding returns null, Telecom falls back to the preloaded dialer | InCallService.java class doc [verified] |
| **"The preloaded dialer will ALWAYS be used when the user places an emergency call, even if your app fills ROLE_DIALER."** The default dialer should use `TelecomManager.placeCall` for emergency calls too | InCallService.java [verified] |
| The default dialer's `CallScreeningService` sees incoming calls. `respondToCall` is honoured only for `DIRECTION_INCOMING`. There is a 5 s budget, after which the framework ignores the response, so the call goes through | CallScreeningService.java [verified] |
| Screening only gets calls **not in the user's contacts unless READ_CONTACTS is granted**, and **never** gets calls with restricted/unknown/unavailable/payphone presentation (withheld numbers) | CallScreeningService.onScreenCall doc [verified] |
| `TelephonyManager.isEmergencyNumber(String)`: no permission. Throws `IllegalStateException` if telephony is unavailable and `UnsupportedOperationException` without `FEATURE_TELEPHONY_CALLING` | TelephonyManager.java [verified] |
| `TelecomManager.getSystemDialerPackage()`: public, nullable | TelecomManager.java [verified] |
| Background activity starts are allowed for the **device owner** and for the **home app** | BackgroundActivityStartController.java:1043,1076 [verified] |
| In lock task, the system dialer's activities, `ACTION_CALL_EMERGENCY` and the emergency keypad are allowed **only if `LOCK_TASK_FEATURE_KEYGUARD` is set** | LockTaskController.java:387-470 [verified]. Our server always sets KEYGUARD with kiosk (S `devices.rs:189-194`) |
| `DISALLOW_OUTGOING_CALLS`: "Emergency calls are still permitted". `DISALLOW_SMS`: no sending or receiving SMS (DO: global) | UserManager.java [verified] |
| `Call.reject(int)`, `Call.disconnect()`, `Call.Details.getCallDirection()`, `getHandlePresentation()`; `InCallService.setMuted`, `requestCallEndpointChange` (API 34; `setAudioRoute` is deprecated) | Call.java, InCallService.java [verified] |
| `TelecomManager.isInEmergencyCall()` is `@SystemApi`, so we cannot use it | TelecomManager.java:2794 [verified] |

Consequences:
- **Two enforcement layers.** Screening is the primary filter. `InCallService.onCallAdded` is the backstop: it handles
  withheld numbers, the 5 s timeout, and outgoing calls placed by other apps.
- The **system dialer must stay usable** (not hidden, not suspended, lock-task-permitted) whenever calls are managed,
  because it renders every emergency call.

## 2. Launcher

### 2.1 Package `calls/` (new)

Pure, JVM-testable code. No `android.*` imports:
- `PhoneNumbers.kt`: `normalize(raw: String, defaultCc: String): Normalized?`, where `Normalized` is `E164(String)` or
  `Short(String)`. Rules, shared with the server (§3.4):
  1. Strip ` -.()/` and NBSP.
  2. A leading `00` becomes `+`.
  3. `+` followed by 7-15 digits is E.164.
  4. 3-6 digits with no `+` is `Short` (112, 1881, voicemail). It is matched only exactly.
  5. Anything else that is all digits is national: drop one leading trunk `0`, then prefix `+defaultCc`. For Norway, numbers
     never start with 0, so `91234567` becomes `+4791234567`.
  6. Any other character (`*`, `#`, letters) gives `null`. MMI/USSD codes are therefore never allowlisted.

  Matching is exact equality of normalized values. There is no suffix matching: a loose last-N-digits match would let
  foreign numbers through. Android's `PhoneNumberUtils.compare` is not used. It is not available in JVM tests, and its
  loose semantics are what we want to avoid.
- `CallRules.kt`:
  ```kotlin
  data class CallRules(val callsEnabled: Boolean, val smsEnabled: Boolean, val defaultCc: String,
                       val inbound: Set<String>, val outbound: Set<String>, val home: List<HomeContact>)
  sealed interface CallPolicyState { data object Unmanaged; data class Managed(val rules: CallRules); data object UnknownFailClosed }
  enum class Verdict { ALLOW, BLOCK }
  fun decideOutgoing(raw: String, state: CallPolicyState, overrideActive: Boolean, isEmergency: Boolean): Verdict
  fun decideIncoming(raw: String?, presentationAllowed: Boolean, state: CallPolicyState, overrideActive: Boolean,
                     nowMs: Long, lastEmergencyCallMs: Long?): Verdict
  fun isEmergency(raw: String, platform: (String) -> Boolean?): Boolean   // platform == null/throws -> static list
  ```
  Order for outgoing: emergency, then `Unmanaged`, then override, then `UnknownFailClosed`, then `!callsEnabled`, then
  outbound set. The first rule that applies decides: emergency, `Unmanaged` and override give ALLOW, everything after
  them gives BLOCK unless the number is in the outbound set.
  Order for incoming: `Unmanaged`, then override, then PSAP window (60 min after an emergency call), then
  `UnknownFailClosed`, then `!callsEnabled`, then withheld (`!presentationAllowed || raw == null`), then inbound set.
  `Unmanaged`, override and the PSAP window give ALLOW, the next three give BLOCK, and the inbound set decides the rest.
  The static emergency list `{112, 110, 113, 911, 999, 000, 08, 118}` is ORed with the platform answer, so a telephony
  failure can never block 112.
- `CallPolicyStore.kt` (Android side, thin): `@Volatile var state: CallPolicyState`.
  - `refresh()` derives the state from `PolicyGate.decodeCached`:
    - `Ok(policy)` with `callPolicy == null`: `Unmanaged`.
    - `Ok` with a policy: `Managed`.
    - `Corrupt`, or `Absent` while the pref `calls_managed_last=true`: `UnknownFailClosed`.
  - It is refreshed:
    - by `performMdmSync` after an accepted fetch;
    - by `Application.onCreate`;
    - by the screening/InCall services in `onCreate` if the state is still unset.

  Screening must answer within 5 s, so it reads memory only. There is no JSON decode per call.

Android components (manifest entries in §2.3):
- `KidCallScreeningService`: `onScreenCall(details)`.
  - If `details.callDirection != DIRECTION_INCOMING`, return without responding (outgoing calls are ignored).
  - Else respond with `decideIncoming(details.handle?.schemeSpecificPart, true, ...)`.
  - BLOCK sends `CallResponse.Builder().setDisallowCall(true).setRejectCall(true).setSkipNotification(true)`.
  - `setSkipCallLog` is ignored for non-system screeners [verified], so blocked calls stay in the system log as BLOCKED.
    That is fine.
- `KidInCallService : InCallService`: keeps a `calls` list and starts `InCallActivity`. `onCallAdded(call)`:
  - **Incoming, `STATE_RINGING`**: recompute `decideIncoming` with
    `presentationAllowed = details.handlePresentation == PRESENTATION_ALLOWED`. On BLOCK, call
    `call.reject(Call.REJECT_REASON_DECLINED)` and do not show any UI. This catches withheld numbers and screening
    timeouts.
  - **Outgoing**: `decideOutgoing`. On BLOCK, call `call.disconnect()` and show a toast "Not allowed". This catches
    `ACTION_CALL` from other apps and the system dialer used directly.
  - Otherwise post a `Notification.CallStyle` (incoming: `forIncomingCall`, ongoing: `forOngoingCall`) with a
    full-screen intent, **and** call `startActivity(InCallActivity, FLAG_ACTIVITY_NEW_TASK)` directly. The direct start
    is allowed because we are DO and HOME [verified]. It does not depend on heads-up or full-screen intents, which lock
    task or `USE_FULL_SCREEN_INTENT` policy may suppress [device].
  - Record `lastEmergencyCallMs` (a commit()ed pref) when `isEmergency(number)` holds for any call we see. Emergency
    calls are rendered by the preloaded dialer, so we may not see them at all. The PSAP window therefore also queries
    `CallLog.Calls` for an outgoing emergency number in the last 60 min. That needs `READ_CALL_LOG`, which the dialer
    role grants [belief, device].
  - We do not declare `IN_CALL_SERVICE_RINGING`, so Telecom plays the ringtone and handles ringer mode and DND. Risk: a
    withheld-number call may ring for a fraction of a second before the backstop rejects it [device].
- `InCallActivity` (Views):
  - `setShowWhenLocked(true)`, `setTurnScreenOn(true)`. It does not dismiss the keyguard: the kid answers from the lock
    screen like a stock phone.
  - Layout: name (from `CallRules.home` or the contacts list, else the number), status/timer, and 4 large buttons:
    Answer (incoming only), Hang up/Decline, Speaker, Mute.
  - Speaker uses `requestCallEndpointChange` with the `TYPE_SPEAKER` endpoint from `onAvailableCallEndpointsChanged`.
    Mute uses `setMuted`.
  - A `PROXIMITY_SCREEN_OFF_WAKE_LOCK` is held while a call is active.
  - Video calls are answered as audio (`VideoProfile.STATE_AUDIO_ONLY`).
  - `launchMode="singleTask"`, `taskAffinity=":call"`, `excludeFromRecents`. Our package is always in the lock-task
    packages (`AppEnforcer.kt:215`), so it can start while pinned [verified in code].
- `DialerActivity`: the `DIAL` handler.
  - It shows only the outbound contacts plus three emergency buttons (112 police, 110 fire, 113 ambulance). There is no
    free keypad.
  - On `tel:` data: if `decideOutgoing` gives ALLOW, show "Call X?" and confirm; else show "This number is not allowed".
  - All calls go through `TelecomManager.placeCall(Uri.fromParts("tel", n, null), Bundle())`, emergency calls included
    [verified recommendation].
- Home contact buttons:
  - New `ui/minimalist/ContactsHomeAdapter.kt`, placed before the apps adapter with a `ConcatAdapter` in
    `HomeActivity.kt:81-83`.
  - Rows come from `CallRules.home` (contacts with `show_on_home && outbound`). Each row is a large text row in the same
    style as `list_apps_row_variant_text`, with a phone glyph.
  - Tapping a row shows a confirm dialog ("Ring Mamma?"), then `placeCall`.
  - Refreshed from the existing pref listener (`HomeActivity.kt:46-60`) when `kidModePolicy` changes.
  - Hidden when the state is not `Managed` or `callsEnabled=false`.

### 2.2 Enforcement additions (`AppEnforcer` + `computeEnforcementPlan` from 01)

New inputs to the plan: `systemDialer: String?` (`TelecomManager.getSystemDialerPackage()`) and
`callsManaged: Boolean`. Rules, all unit-tested:
- `systemDialer` is **never hidden** (today it is hidden when not allowlisted, `AppEnforcer.kt:104-130`).
- When `callsManaged`, `systemDialer` is **never suspended** and is added to `kioskPackages`. That keeps the emergency
  in-call UI working even without the KEYGUARD feature.
- When unmanaged, the old behaviour is kept (it may be suspended if not allowlisted) for upstream compatibility.
  Flagged in the open questions.

New steps in `apply()`, after the kiosk step, all following the "liftable by override" rule (L `CLAUDE.md:117`):
- `applyDialerRole(state)`. A pref `dialer_role_taken_by_us` remembers that we took the role.
  - **Managed**: if not `RoleManager.isRoleHeld(ROLE_DIALER)`, call `dpm.setDefaultDialerApplication(packageName)` and set
    `dialer_role_taken_by_us`. On an exception, set `callState.lastError`. On the next `HomeActivity.onResume`, launch
    `RoleManager.createRequestRoleIntent(ROLE_DIALER)` once per day as the fallback (the parent taps Accept).
  - **Unmanaged**: if we hold the role and `dialer_role_taken_by_us` is set, hand the role back with
    `setDefaultDialerApplication(systemDialer)` and clear the flag. An old server (field absent) therefore never leaves
    the phone with our dialer.
  - **Override/pause**: keep the role, because the UI still works. `CallRules` returns ALLOW instead.
- `applyCallPermissions(managed && !override)`:
  - For each installed **non-system** package other than ours whose `requestedPermissions` contain `CALL_PHONE` or
    `ANSWER_PHONE_CALLS`, set `setPermissionGrantState(..., DENIED)`. Only call it when the current state differs, to
    avoid the re-notify gotcha (L `CLAUDE.md:67`). When lifted, set it back to `DEFAULT`.
  - The same step is added to `enforceOnNewPackage`.
  - Our own package is self-granted `READ_CONTACTS` (so screening sees every number, see §1), `CALL_PHONE` and
    `READ_PHONE_STATE`, via `QuickControls.selfGrantPermission` (`QuickControls.kt:121-143`).
  - System apps are left alone: denying them is risky and the InCallService backstop covers them.
- `applyCallRestrictions(state, override)`:
  - `DISALLOW_SMS` = managed and `!smsEnabled` and `!override`.
  - `DISALLOW_OUTGOING_CALLS` = managed and `!callsEnabled` and `!override`. This is an OS-level backstop if our
    InCallService ever fails; emergency calls remain permitted [verified doc].
  - Unmanaged clears both, like `clearRadioRestrictions`.
- Later, after the role is confirmed held on the device: `DISALLOW_CONFIG_DEFAULT_APPS`. PLAN phase 5, a separate task.
  [device]: check that the DPM setter still works with this restriction set.
- Our app drawer (`ui/list/`) hides `systemDialer` while managed. In lock task it can still be reached only by Telecom's
  emergency path.

### 2.3 Manifest (`app/src/main/AndroidManifest.xml`)

```xml
<uses-permission android:name="android.permission.CALL_PHONE"/>
<uses-permission android:name="android.permission.READ_PHONE_STATE"/>
<uses-permission android:name="android.permission.READ_CONTACTS"/>
<uses-permission android:name="android.permission.READ_CALL_LOG"/>
<uses-permission android:name="android.permission.USE_FULL_SCREEN_INTENT"/>
<uses-permission android:name="android.permission.WAKE_LOCK"/>
<activity android:name=".calls.DialerActivity" android:exported="true">
  <intent-filter><action android:name="android.intent.action.DIAL"/><category android:name="android.intent.category.DEFAULT"/></intent-filter>
  <intent-filter><action android:name="android.intent.action.DIAL"/><category android:name="android.intent.category.DEFAULT"/>
    <data android:scheme="tel"/></intent-filter>
</activity>
<activity android:name=".calls.InCallActivity" android:exported="false" android:launchMode="singleTask"
  android:taskAffinity=":call" android:excludeFromRecents="true" android:showWhenLocked="true" android:turnScreenOn="true"/>
<service android:name=".calls.KidInCallService" android:exported="true"
  android:permission="android.permission.BIND_INCALL_SERVICE">
  <meta-data android:name="android.telecom.IN_CALL_SERVICE_UI" android:value="true"/>
  <intent-filter><action android:name="android.telecom.InCallService"/></intent-filter>
</service>
<service android:name=".calls.KidCallScreeningService" android:exported="true"
  android:permission="android.permission.BIND_SCREENING_SERVICE">
  <intent-filter><action android:name="android.telecom.CallScreeningService"/></intent-filter>
</service>
```
`exported="true"` is safe: the `permission` attribute means only Telecom can bind. Without the DIAL filters or the
InCallService, `setDefaultDialerApplication` throws [verified].

### 2.4 DTOs

`dto/PolicyResponse.kt` gets `val callPolicy: CallPolicy? = null`. The JSON key is `call_policy` (SnakeCase).
```kotlin
@Serializable data class CallPolicy(val callsEnabled: Boolean = false, val smsEnabled: Boolean = false,
    val defaultCountryCode: String = "47", val contacts: List<PolicyContact> = emptyList())
@Serializable data class PolicyContact(val id: Long, val name: String, val number: String,
    val inbound: Boolean = false, val outbound: Boolean = false, val showOnHome: Boolean = false)
```
- Absent means unmanaged, so a new launcher talking to an old server keeps today's behaviour.
- Defaults *inside* a present object deny, because "managed" means deny by default.

`dto/StatusReportRequest.kt` gets:
- `capabilities: List<String> = listOf("call_policy_v1")`;
- `callState: CallState? = null`, where `CallState(dialerRoleHeld: Boolean, defaultDialer: String?,
  systemDialer: String?, smsRestricted: Boolean, outgoingRestricted: Boolean, lastError: String?)`.

### 2.5 Before first unlock after reboot (BFU)

The app is not `directBootAware` (L `CLAUDE.md:41`). Before the first unlock, Telecom cannot bind our InCallService or our
screening service, so incoming calls are shown **unscreened by the preloaded dialer** [belief, high confidence; device].
v1 accepts and documents this gap: a phone that rebooted and is not yet unlocked rings for anyone.

The follow-up (separate task, not v1):
- Mark only the two services and `InCallActivity` as `directBootAware`.
- Mirror `CallRules` into device-protected storage (`createDeviceProtectedStorageContext()`, its own small JSON pref).
- Guard `Application.onCreate` (`Application.kt:105-179`) with `UserManager.isUserUnlocked()`. Today it reads
  credential-encrypted prefs, which throws before unlock, and the crash handler calls `exitProcess(1)` (`:110`).

This is too risky to ship blind; it needs device iteration.

### 2.6 Old servers / no policy

| Situation | Behaviour |
|---|---|
| `call_policy` absent (upstream server, or calls not managed) | `Unmanaged`: role not taken (released if we took it), no screening effect, no restrictions, no home buttons. Today's allowlist/suspend behaviour applies to the system dialer, but it is never hidden |
| Never synced (fresh enrollment) | `Unmanaged` |
| Cache corrupt, or managed before and now absent | `UnknownFailClosed`: only emergency calls and the PSAP window |
| Override PIN or pause active | ALLOW everything (standing rule). Role kept, restrictions cleared |

## 3. Server

### 3.1 Migration `migrations/00NN_calls.sql`

Take the next free number at merge time. Today that is 0021, or 0022 if 01's `policy_state` takes 0021.
```sql
ALTER TABLE device_policy ADD COLUMN calls_managed INTEGER NOT NULL DEFAULT 0; -- 0: no call_policy sent
ALTER TABLE device_policy ADD COLUMN calls_enabled INTEGER NOT NULL DEFAULT 1;
ALTER TABLE device_policy ADD COLUMN sms_enabled   INTEGER NOT NULL DEFAULT 1;
CREATE TABLE contacts (                          -- global address book: siblings share grandparents
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  name TEXT NOT NULL CHECK (length(name) BETWEEN 1 AND 60),
  phone_number TEXT NOT NULL UNIQUE,             -- normalized (E.164 or short code), see 3.4
  created_at TEXT NOT NULL DEFAULT (datetime('now')));
CREATE TABLE device_contacts (                   -- mirrors device_tracked_apps
  device_id  INTEGER NOT NULL REFERENCES devices(id)  ON DELETE CASCADE,
  contact_id INTEGER NOT NULL REFERENCES contacts(id) ON DELETE CASCADE,
  allow_inbound  INTEGER NOT NULL DEFAULT 1,
  allow_outbound INTEGER NOT NULL DEFAULT 1,
  show_on_home   INTEGER NOT NULL DEFAULT 1,
  sort_order     INTEGER NOT NULL DEFAULT 0,
  PRIMARY KEY (device_id, contact_id));
CREATE TABLE call_settings (id INTEGER PRIMARY KEY CHECK (id = 1),
  default_country_code TEXT NOT NULL DEFAULT '47' CHECK (default_country_code GLOB '[1-9]*'));
INSERT INTO call_settings (id) VALUES (1);
ALTER TABLE device_status ADD COLUMN capabilities_json TEXT;
ALTER TABLE device_status ADD COLUMN call_state_json TEXT;
```
This follows the review (§3 of server-architecture.md), with these changes:
- a `call_settings` singleton for the default country code (no hardcoded +47);
- a name length check;
- status columns for capabilities and applied state.

SMS sits under `calls_managed`. One "manage calls & SMS" switch keeps the semantics simple.

### 3.2 Models (`src/models.rs`)

- `DevicePolicy` (`:26-49`) gets `calls_managed`, `calls_enabled`, `sms_enabled: bool`. After 01 there is no
  `Default`-based fallback left, so `derive(Default)` producing `false` only matters in tests.
- Row structs `Contact`, `DeviceContactRow` (join result: id, name, phone_number, flags, sort_order), `CallSettings`.
- Wire types `CallPolicy { calls_enabled, sms_enabled, default_country_code, contacts: Vec<PolicyContact> }` and
  `PolicyContact { id, name, number, inbound, outbound, show_on_home }`.
- `PolicyResponse` (`:243-280`) gets `#[serde(skip_serializing_if = "Option::is_none")] pub call_policy: Option<CallPolicy>`.
- `StatusReportRequest` (`:292-306`) gets `#[serde(default)] capabilities: Vec<String>` and
  `#[serde(default)] call_state: Option<serde_json::Value>`. Both are stored as JSON text in `status()`
  (`device_api.rs:354-375` INSERT).

### 3.3 Handlers (`src/handlers/calls.rs`, registered in `admin_routes`, `main.rs:157`)

Every write runs in a transaction and returns 500 on error. There are no `.ok()` calls. Each write then calls
`state.command_notify.send(id)` and redirects to `/devices/{id}/calls`.

| Route | Action |
|---|---|
| `GET /devices/{id}/calls` | render `device_calls.html` |
| `POST /devices/{id}/calls/settings` | one form with `managed`, `calls_enabled` and `sms_enabled` checkboxes, all in the same form, so "missing = false" is correct |
| `POST /devices/{id}/contacts` | `name`, `number`. Normalize (3.4). Invalid input re-renders with an error (400). `INSERT ... ON CONFLICT(phone_number) DO UPDATE SET name=excluded.name RETURNING id`, then `INSERT OR IGNORE` into `device_contacts` with defaults 1/1/1 and `sort_order = max+1` |
| `POST /devices/{id}/contacts/{cid}` | flags `inbound`, `outbound` and `show_on_home` in one auto-submitting form |
| `POST /devices/{id}/contacts/{cid}/remove` | detach. `DELETE FROM contacts WHERE id=? AND NOT EXISTS (SELECT 1 FROM device_contacts WHERE contact_id=?)` |
| `POST /settings/calls` | `default_country_code`, 1-3 digits |

Policy (`build_policy` from 01): if `calls_managed`, query
`SELECT c.id, c.name, c.phone_number, dc.* FROM device_contacts dc JOIN contacts c ON c.id = dc.contact_id WHERE dc.device_id = ? ORDER BY dc.sort_order, c.name`
plus `call_settings`, with `?` on errors. If not managed, `call_policy: None`.

### 3.4 Number normalization (shared contract)

`src/phone.rs`: `pub fn normalize(raw: &str, default_cc: &str) -> Result<String, PhoneError>`, with exactly the rules
in §2.1. Both sides test the same vector table. Copy it verbatim into `src/phone.rs` tests and `PhoneNumbersTest.kt`.

| input (cc 47 unless noted) | output |
|---|---|
| `+47 912 34 567`, `0047 91234567`, `91234567`, `912 34 567`, `(+47) 91-23-45-67` | `+4791234567` |
| `+46 70 123 45 67`; `070-123 45 67` (cc 46) | `+46701234567` |
| `112`, `1881` | `112`, `1881` (short) |
| `""`, `+47`, `*21*91234567#`, `abc`, `+1234567890123456` (16 digits) | error |

The server stores the normalized value, so the launcher normalizes only the incoming number.

### 3.5 Templates (follow existing patterns)

- `device_detail.html`: a new card "Calls & SMS" after the Schedule card (`:39-43`), in the same style as Conversations
  (`:45-49`). It shows a one-line summary ("Managed - 4 contacts" / "Not managed") and a
  `<a class="button-small" href="/devices/{id}/calls">`.
- `device_calls.html` (with `partials/head.html` and `partials/app_header.html`), made of cards:
  1. **Warning card** when the latest `device_status.capabilities_json` lacks `call_policy_v1`: "This phone's launcher
     does not enforce calls yet". When `call_state_json.dialer_role_held == false` or `last_error` is set: "Phone app
     role not active: …".
  2. **Settings**: a single `<form>` with three `checkbox-row`s and `onchange="this.form.submit()"`, as in the Apps card
     (`device_detail.html:72-83`). Fixed text: "Emergency numbers (112, 110, 113) always work."
  3. **Contacts**: one auto-submitting `<form>` per contact (`inbound`/`outbound`/`home` checkboxes) and a small Remove form.
  4. **Add contact**: name and number inputs, plus an error line.
  5. Footer: the default country code form.

### 3.6 Tests (TestApp, `src/tests/calls.rs`)

The harness only has bearer/JSON requests today (`src/tests/mod.rs:69-112`). Add:
- `TestApp::admin_cookie()`. It seeds `admin_users` with `must_change_password=0` and TOTP enabled with a fixed secret,
  POSTs the `/login` form, then POSTs `/auth/verify-2fa` with `security::totp_for_secret(..).generate_current()`
  (`security.rs:215`), and returns the `Set-Cookie`.
- `request_form(method, uri, cookie, &[(k, v)])`.

Tests:
- `policy_has_no_call_policy_when_unmanaged` (the key is absent from the JSON);
- `managed_policy_lists_contacts_with_flags_in_order`;
- `contact_number_is_normalized_on_add`, and that an invalid number gives 400 with no row;
- `same_number_on_two_devices_shares_contact`;
- `remove_detaches_and_deletes_orphan`;
- `settings_form_missing_checkbox_means_false`;
- `writes_nudge_device` (subscribe to `command_notify` before the POST);
- `status_stores_capabilities_and_call_state`;
- `calls_page_warns_without_capability`;
- `phone::normalize` vector table (unit);
- extend 01's key snapshot with `call_policy`.

## 4. Launcher unit tests (JVM, `app/src/test/.../calls/`)

- `PhoneNumbersTest`: the §3.4 table.
- `CallRulesTest`:
  - emergency is always ALLOW (Unmanaged, Managed-disabled, UnknownFailClosed);
  - the platform throwing still makes `112` an emergency;
  - outbound in set gives ALLOW and not in set gives BLOCK;
  - `+47`/national forms match;
  - inbound withheld gives BLOCK when managed;
  - inbound-only contact: outgoing gives BLOCK, incoming gives ALLOW;
  - `callsEnabled=false` blocks an allowlisted number;
  - the override allows everything;
  - the PSAP window allows a stranger at 59 min and not at 61 min;
  - `UnknownFailClosed` blocks non-emergency.
- `CallPolicyStateTest`: Ok+null gives Unmanaged; Ok+policy gives Managed; Corrupt gives UnknownFailClosed;
  Absent+`managedLast` gives UnknownFailClosed.
- `EnforcementPlanTest` additions: the system dialer is never hidden; never suspended when managed and in
  `kioskPackages`; suspendable when unmanaged and not allowlisted.
- `PolicyResponseCompatTest` additions: a blob without `call_policy` gives null; a blob with `call_policy: {}` gives a
  deny-default `CallPolicy`.

## 5. On-device checklist (Jelly Star, release build, SIM with a second phone "B" and a third "C")

Run each item with kiosk **on** and again with kiosk **off**, unless marked otherwise. Set B as a contact with in+out and C
as unknown.

1. After sync, the server shows `dialer_role_held=true` with no prompt (`setDefaultDialerApplication` works on this OEM
   build). Also check `adb shell cmd role get-role-holders android.app.role.DIALER`.
2. Home shows B's button. Tap it, confirm: the call connects, and the in-call UI shows speaker/mute/hang up working.
3. B calls the phone: it rings and our InCallActivity appears (a) on the lock screen with the screen off, (b) while the
   kid is inside another allowed app in kiosk, (c) on Home. Answer and decline both work.
4. C calls: it is rejected with no ring, B-side hears busy/voicemail, and nothing shows on the kid's phone. Repeat with C
   on **withheld number** (`#31#`): rejected by the backstop. Note any ring blip.
5. Allowed contact B calls with the number presented as `91234567` vs `+4791234567` (if the carrier varies): both match.
6. Outgoing to C: from DialerActivity via a `tel:` link in an allowed app, the "not allowed" message appears. With
   `adb shell am start -a android.intent.action.CALL -d tel:<C>` (adb has CALL_PHONE), the call is disconnected by the
   backstop. The system dialer opened via adb `am start` and dialling C is disconnected.
7. Emergency, **without placing a real call**:
   - `isEmergencyNumber("112")` is true (a debug log line at startup);
   - tapping 112 in DialerActivity shows the system's emergency UI;
   - cancel before dialing is completed if possible.

   A real emergency call test needs a test number. One option is `adb shell cmd phone emergency-number-test-mode -a <n>`
   [belief; verify what the carrier does with it first]. Never test against 112 itself.
8. Lock screen: the emergency button on the keyguard opens the system emergency dialer, with kiosk on.
9. SMS off: send an SMS from B (not received), and try to send from the phone's Messages app if allowlisted (blocked).
   SMS on: both work again.
10. `calls_enabled=false`: B's call is rejected, outgoing to B is blocked, and the home buttons are hidden.
11. Server stopped and phone rebooted: after unlock, rules still apply from cache (C rejected).
12. **BFU**: reboot, do not unlock, call from C. Record whether it rings (expected: yes, the v1 gap). Then unlock and
    repeat (rejected).
13. Old server: point the phone at an upstream server build (no `call_policy`). The dialer role is handed back to the
    system dialer and calls work normally.
14. Override PIN: C can call during the 2 h window, and the restriction returns after expiry/sync.
15. Install a third-party dialer-ish app with CALL_PHONE: its permission shows as denied ("blocked by admin").
16. Battery: no wakelock is held when idle (`dumpsys power | grep -i wake_lock`).

## 6. Ordered tasks

`[local]` = verifiable with `cargo test` or `testDebugUnitTest`. `[device]` = only on the Jelly Star.

| # | Fork | Task | Verify |
|---|---|---|---|
| 1 | S | `phone.rs` normalize + vectors | [local] |
| 2 | S | Migration + models + `call_policy` in `build_policy` (unmanaged default) + key snapshot | [local] |
| 3 | S | TestApp `admin_cookie`/`request_form` helpers | [local] |
| 4 | S | `calls.rs` handlers + `device_calls.html` + detail card + tests | [local] + manual browser check via `cargo run` |
| 5 | S | Status `capabilities`/`call_state` storage + warning card | [local] |
| 6 | L | `calls/PhoneNumbers.kt`, `CallRules.kt` + tests (vectors from task 1) | [local] |
| 7 | L | DTOs (`CallPolicy`, status fields) + compat tests | [local] |
| 8 | L | `computeEnforcementPlan` dialer rules (never hide; managed: never suspend + kiosk) + tests | [local], then [device] check 8 |
| 9 | L | Manifest + `KidInCallService`/`InCallActivity`/`DialerActivity` (UI only, no rules) + role via DPM with RoleManager fallback; report `callState` | build [local]; checklist 1-3 [device] |
| 10 | L | Wire `CallPolicyStore` + outgoing rules + home buttons | checklist 2, 6 [device] |
| 11 | L | `KidCallScreeningService` + InCall backstop for incoming + PSAP window | checklist 4, 5, 7, 12 [device] |
| 12 | L | `applyCallPermissions` (deny CALL_PHONE, self-grant READ_CONTACTS) + `applyCallRestrictions` (SMS, outgoing) | checklist 9, 10, 15 [device] |
| 13 | L | Release role when unmanaged + old-server test | checklist 13 [device] |
| 14 | L | `DISALLOW_CONFIG_DEFAULT_APPS` once the role is held | [device] |
| 15 | L | (follow-up) direct-boot-aware call path (§2.5) | [device] |

Tasks 1-7 are independent of the device and can land first. Each later task is a buildable PR on its own.

## Key decisions

- Our own pure number normalizer with a server-configurable default country code, exact match, no suffix matching.
  The same vectors run on both sides.
- Two layers: CallScreeningService first, with the InCallService backstop for withheld numbers, timeouts and outgoing
  calls from any app.
- The system dialer is never hidden. When managed it is never suspended and is lock-task-permitted, because Android
  always uses it for emergency calls.
- `call_policy` absent means unmanaged and the role is returned. Corrupt cache, or previously managed, fails closed to
  emergency-only.
- The override PIN and pause lift call restrictions too (standing rule).

## Open questions for the user

1. Should the override PIN/pause really open calls to everyone (standing rule), or should calls stay allowlisted under
   override?
2. Is BFU unscreened ringing acceptable for v1, or is direct boot a blocker before giving him the phone?
3. Unmanaged devices: keep upstream's "suspend system dialer if not allowlisted" (an emergency UI risk), or never suspend
   it at all?
4. Home contact tap: confirm dialog (current design), or call immediately?
5. One "manage calls & SMS" switch, or separate managed flags for SMS?

## Decisions after QA review (qa-01-02.md) and user input, 2026-10-04

QA findings override this doc where they conflict. Binding for implementation:

- **Emergency buttons:** 112, 110 and 113 are built-in home-screen buttons, not contacts the
  parent can remove; emergency numbers are always allowed regardless of the allowlist.
- **Callback window after an emergency call** (blocker 2): opens only when
  `TelephonyManager.isEmergencyNumber` confirms the number AND the call connected; never from
  the static fallback list. The parent is notified via the status report.
- **`call_policy` always explicit** (blocker 3): the server always sends `managed: true|false`;
  once a phone has been managed, a response without the key is rejected and the last managed
  rules are kept. Unmanaging requires an explicit `managed: false`.
- **Before-first-unlock gap** (blocker 4): acceptable while developing; the direct-boot call
  path (task 15) is required before the phone is handed to the kid.
- **Outgoing calls are blocked before placement** with a `CallRedirectionService`; the
  in-call `disconnect()` stays as a backstop.
- **Override PIN and pause never open calls.** Call rules stay in force; they only lift app
  restrictions. (Proposed default, pending user confirmation.)
- **Voicemail, RCS, MMI/USSD codes:** handled as in qa-01-02.md (should-fix list).
- **Number matching:** ASCII digits only on both sides, `sip:`/`tel:` URIs parsed, one shared
  test-vector file used by both the Rust and Kotlin tests.
