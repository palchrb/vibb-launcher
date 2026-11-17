# Design 114: the phone setup status

Status: design, 2026-11-17. Built after consolidation round C1 and alongside the PWA copy round (design 112 + the words
audit). Inputs: the inventory `docs/review/2026-11-17-setup-status-inventory.md` (its tables A1-A7, the overlaps B1-B20,
the gaps C1-C12), PLAN.md's entry "A setup checklist for the one-time steps on a phone" with the user's calls of
2026-11-17, design 98 §3.3/§6.2 (`docs/design/98-airplane-mode-assistant.md`), design 107, `docs/setup/android-settings.md`,
the root `CLAUDE.md` "Words in the UI". Paths: `L/` = `launcher/app/src/main/java/com/kidslauncher/mdm/`, `S/` =
`server/src/`, `T/` = `server/templates/`; line numbers on `main` at bbb35266b (code unchanged since the inventory's
70904fae3).

Items marked **ASK** are the user's to decide; everything else follows the user's settled calls.

## 1. Goals

1. **One list, grown from design 98's two.** The phone's `setupStepsLeft` (L/assistant/AssistantSetupPlan.kt:68-82, 5
   kinds) and the PWA's `airplane_mode::setup_steps` (S/airplane_mode.rs:332-424, 6 kinds) are replaced by one step
   table in one shared contract, `server/testdata/setup_steps.json`. No third list.
2. **Three layers.** What the Device Owner can do it does and checks (and shows only when it failed). What needs a
   person is one section on the phone, in setup mode, with a direct path to Android's own page per step. What no app
   can read is ticked once by the parent in the PWA.
3. **The PWA:** a page Device › Oppsett, one orange "Oppsett: N steg igjen" on the phone's summary while required steps
   remain (nothing when they're done), and one short orange line at each choice that a missing step stops, linking to
   the step.
4. **The phone:** a "Fullfør oppsett" section at the top of the parent Settings, listing only the missing steps; the
   design-98 notification counts the same list.
5. **No adb beyond the QR.** Every step a person does has an on-phone path. That includes notification access, which
   today has only an adb path (L/badges/BadgeStore.kt:31-37; S/i18n/nb.json:1717).
6. **Report what is unreported today:** Device Owner itself, Data Saver, Vibb's full-screen intent, and Vibb's own
   location permission.
7. **Vibb's own location permission is always granted by the Device Owner.** Today the grant follows Locate.

Non-goals: the roaming switch stays a trip step on Mobilnett. Tailnet, the Child PIN, the Home role, backup, other apps'
special accesses (design 68) and the music app keep their own pages and Attention rows (inventory B10-B17). Google
account presence stays out (inventory C12).

## 2. The steps

### 2.1 Fields

Every step has:
- `key`;
- `need`: `required` (counted, orange) or `optional` (listed quietly, never counted);
- `read_by`:
  - `phone`: the phone reports a reading;
  - `server`: the server's own data;
  - `parent`: the parent ticks it once in the PWA. Android won't let an app read it.
- `when`: when the step applies, from the policy (§2.3);
- `summary`:
  - `count`: in the summary's N;
  - `own_row`: it keeps its own Attention row and is not counted, so the summary says it once;
- `level`: `action` (orange) or `alarm` (red);
- `features`: the PWA choices it stops (§5.4);
- `phone_page`: the Android actions the phone row opens, in order. `null` means no on-phone page;
- `names`: nb/en.

Order in the table is the order everywhere: the PWA page, the phone section, the contract.

### 2.2 The table

"Reading" is what decides done/missing; "absent" means not applicable on this phone (the step is left out), `null`
means unreadable (shown nowhere, never counted). Versions: minSdk 34, so every API below exists on 14-17 without a gate
unless the row says otherwise.

| # | key | need | read_by | when | summary / level | Reading (done when) | On-phone path (in a pause / Åpne for oppsett) | nb name / en name |
|---|---|---|---|---|---|---|---|---|
| 1 | `parent_pin` | required | server | always | own_row / alarm | a Parent PIN is stored (today's `NoParentPin` fact) | none: the PWA's `/lock#parent-pin` (without it the phone's Settings doesn't open at all, launcher/CLAUDE.md "Settings is PIN-gated") | Foreldre-PIN / Parent PIN |
| 2 | `device_owner` | required | phone | always | own_row / alarm | new `setup.device_owner`: `DevicePolicyManager.isDeviceOwnerApp(packageName)` | none: the phone is reset and set up with the QR (`T/provision_qr.html`) | Enhetseier / Device owner |
| 3 | `location` | required | phone | always | count / action | new `setup.location`: `checkSelfPermission(ACCESS_FINE_LOCATION) == GRANTED`. The DO grants it at every apply (§7.1), so missing means the grant failed | `ACTION_APPLICATION_DETAILS_SETTINGS` `package:<ours>` (Permissions › Location) | Posisjonstilgang / Location access |
| 4 | `notification_access` | required | phone | always | count / action | `notification_listener_enabled` (L/server/MdmSyncWorker.kt:536, `BadgeStore.accessGranted`) | `ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS` with `EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME` = `<ours>/com.kidslauncher.mdm.badges.BadgeListenerService` (API 30), then `ACTION_NOTIFICATION_LISTENER_SETTINGS` | Varseltilgang / Notification access |
| 5 | `mobile_data` | required | phone | always | count / action | `mobile_data.enabled` (L/server/MobileNetworkRuntime.kt:351); absent when the new `setup.sim` is false (no default data subscription, `dataTelephony` :284-288) | `ACTION_NETWORK_OPERATOR_SETTINGS` (the SIM's Mobile network page, "Mobildata"), then `Settings.Panel.ACTION_INTERNET_CONNECTIVITY`; the settings lock is lifted in a pause (`mobileSettingsLocked`, L/server/MobileNetwork.kt:100) | Mobildata / Mobile data |
| 6 | `data_saver` | required | phone | always | count / action | new `setup.background_data`: `ConnectivityManager.getRestrictBackgroundStatus() != RESTRICT_BACKGROUND_STATUS_ENABLED` (Data Saver on and Vibb not exempt). No permission needed | `ACTION_IGNORE_BACKGROUND_DATA_RESTRICTIONS_SETTINGS` `package:<ours>` (Vibb One's "Unrestricted data" switch) | Datasparing / Data Saver |
| 7 | `background` | required | phone | always | count / action | `battery_optimization_exempt` (MdmSyncWorker.kt:566) | the existing dialog, `BatteryExemption.dialogIntent` (L/server/BatteryExemption.kt:56-60) | Bakgrunnstilkobling / Background connection |
| 8 | `vibb_notifications` | required | phone | always | count / action | design 107's orange rule over `notification_channels` (its `pages` rows' `orange`) | the existing `NotificationChannels.pageIntents` (L/notify/NotificationChannels.kt:166) | Varsler fra Vibb One / Vibb One notifications |
| 9 | `full_screen_intent` | required | phone | `calls_managed` | count / action | new `setup.full_screen_intent`: `NotificationManager.canUseFullScreenIntent()` (API 34) | `ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT` `package:<ours>` (API 34) | Fullskjermvarsler / Full-screen notifications |
| 10 | `write_settings` | required | phone | `write_settings_used` | count / action | `write_settings_granted` (MdmSyncWorker.kt:578) | the existing `WriteSettingsAccess.pageIntents` (L/server/WriteSettingsAccess.kt:75) | Endre systeminnstillinger / Modify system settings |
| 11 | `assistant` | required | phone | `airplane` | count / action | `airplane_mode.assistant` | the existing window + `ACTION_VOICE_INPUT_SETTINGS` (L/assistant/AssistantSetup.kt:193-194) | Digital assistent / Digital assistant |
| 12 | `wifi_airplane` | required | phone | `airplane_role` | count / action | `airplane_mode.wifi_kept != null` (design 98 §6.2: an OEM "turns off" counts as done) | the existing Wi-Fi step (AssistantSetup.kt:210-245) | Wi-Fi i flymodus / Wi-Fi in airplane mode |
| 13 | `call_filter` | required | phone | `calls_managed` | count / action | `call_state.screening_role_held` and `redirection_role_held` both true | the existing role requests (AssistantSetup.kt:197-202) | Samtalefilter / Call filter |
| 14 | `default_apps` | required | phone | always | count / action | `airplane_mode.default_apps_changed` empty | `ACTION_MANAGE_DEFAULT_APPS_SETTINGS` (new; today the step has no path, only words) | Standardapper / Default apps |
| 15 | `fingerprint` | optional | phone | `android_lock` | count / action (never counted: optional) | new `setup.fingerprint`: `FingerprintEnrol.enrolled` (L/server/FingerprintEnrol.kt:63-67); absent without a sensor | the existing `FingerprintEnrol.pageIntents` (:73-82) | Fingeravtrykk / Fingerprint |
| 16 | `vibration` | **ASK** (recommended: required) | parent | always | count / action | ticked | `ACTION_SOUND_SETTINGS` | Vibrasjon / Vibration |
| 17 | `touch_feedback` | optional | parent | always | count / action | ticked | `ACTION_SOUND_SETTINGS` | Vibrasjon ved trykk / Touch feedback |
| 18 | `lock_clock` | optional | parent | `android_lock` | count / action | ticked | `ACTION_DISPLAY_SETTINGS` (Display › Lock screen; no public lock-screen action - checked in the gate) | Klokke på én linje / One-line clock |
| 19 | `media_on_lock` | optional | parent | `android_lock` | count / action | ticked | the existing `MediaLockScreen.pageIntents` (`ACTION_SOUND_SETTINGS`, L/server/MediaLockScreen.kt:34-35) | Medier på låseskjermen / Media on the lock screen |
| 20 | `message_banners` | optional | parent | always | count / action | ticked (**ASK**: detection, §2.5) | `ACTION_MANAGE_APPLICATIONS_SETTINGS` (the app list; the channel is the app's) | Bannere for meldinger / Message banners |

Rows 1-14 are required, so a fresh phone with nothing done shows at most 12 counted steps (1 and 2 have their own
rows). Rows 15-20, apart from `vibration`, never turn the summary orange.

Steps that are **not** in the table, and why:
- the roaming switch: a trip step, kept on Mobilnett (design 97);
- POST_NOTIFICATIONS: the DO fixes it (WindowRelock.kt:692-693); a revoke shows up as row 8;
- the Home role, lock task, the VPN, backup and private DNS: done by the DO, and they already have Attention rows
  (`NotHomeApp`, `PolicyProblem`, `GoogleBackup`);
- design 110's two map-link steps: they join this table when design 110 is built (§2.6).

### 2.3 `when`: the need rule

Both sides run the same rule from the policy they hold (the server from `device_policy`, the phone from its cached
policy). That way a step shows up on the PWA the moment the parent turns on the feature that needs it. Example: tick
Flymodus and "Digital assistent" appears at that switch at once, before the phone reports.

| `when` | applies when |
|---|---|
| `always` | always |
| `calls_managed` | `calls_mode` managed (`DevicePolicy::calls_managed`, S/models.rs:461; `CallPolicyStore.callsState.managed`) |
| `write_settings_used` | `quick_controls_mask` bit 16, or the clock format isn't "system" (AssistantSetup.kt:280; airplane_mode.rs:358-359) |
| `airplane` | bit 64 |
| `airplane_role` | bit 64 and the `assistant` reading done |
| `android_lock` | `kid_lock.keyguard` (Android's PIN lock). C3 (phase D) makes it always true; the condition then goes |

A step is **missing** when it applies, its reading is present and is `false`, or (for `parent` steps) it isn't
ticked. **N** is the number of missing `required` steps with `summary: count`. The phone and the PWA compute the same
N from the same report. The phone can't see `server` steps, but both of those are `own_row`, so they're never in N.

### 2.4 Old phones and fresh phones

- There's no capability flag: after C1 the server and launcher ship together (§9). A phone whose report has no `setup`
  yet (before its first report on the new build) has those readings absent: those steps aren't shown, and the rest
  come from fields it already sends.
- On the user's phones, already set up, the deploy shows the `parent` required steps as missing (`vibration` if
  required) until ticked. A one-time tick.

### 2.5 ASK: message banners, detected or ticked

Inventory C8: the listener already reads each post's `Ranking.importance` (L/badges/GlanceReader.kt:104-109). A later
round could report, per allowed app that posted, whether its message channel is below High. That would make a
phone-read step per app ("Bannere: Element X"), opening `ACTION_APP_NOTIFICATION_SETTINGS` for that package.
**Recommended:** tick it now, detect it in a later round. Detection needs a post first, so it can't help on a fresh
phone, and it adds a per-app list to the contract.

### 2.6 ASK: design 110's two steps

Design 110 §7 gives the PIN-gated Settings its own group "Kartlenker" with two steps: Play services' "Open supported
links" off, and Vibb's links on. Both are one-time steps on Android's pages. **Recommended:** when 110 is built they
become rows 21-22 of this table (`when: map_links`, `read_by: phone` as 110 §6.3 reads them, the Mobilnett-style
warning at the Apps page's map-link switch), not a separate group. The "give the links back" row (110 §6.3) stays
110's own row, because it isn't a setup step. That changes 110's approved layout.

## 3. The contract `server/testdata/setup_steps.json`

Byte-identical in `launcher/app/src/test/resources/`. The usual shared-contract pattern; C4 later moves it into
`contracts/` with the rest.

```json
{
  "steps": [
    {"key": "parent_pin", "need": "required", "read_by": "server", "when": "always", "summary": "own_row",
     "level": "alarm", "features": [], "phone_page": null,
     "names": {"nb": "Foreldre-PIN", "en": "Parent PIN"}},
    {"key": "notification_access", "need": "required", "read_by": "phone", "when": "always", "summary": "count",
     "level": "action", "features": ["badges", "glance", "music_card"],
     "phone_page": ["android.settings.NOTIFICATION_LISTENER_DETAIL_SETTINGS",
                    "android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS"],
     "names": {"nb": "Varseltilgang", "en": "Notification access"}},
    {"key": "mobile_data", "need": "required", "read_by": "phone", "when": "always", "summary": "count",
     "level": "action", "features": ["mobile_choices", "roaming"],
     "phone_page": ["android.settings.NETWORK_OPERATOR_SETTINGS", "android.settings.panel.action.INTERNET_CONNECTIVITY"],
     "names": {"nb": "Mobildata", "en": "Mobile data"}},
    {"key": "vibration", "need": "required", "read_by": "parent", "when": "always", "summary": "count",
     "level": "action", "features": [], "phone_page": ["android.settings.SOUND_SETTINGS"],
     "names": {"nb": "Vibrasjon", "en": "Vibration"}}
  ],
  "features": [
    {"key": "mobile_choices", "page": "mobile", "anchor": "mobile-data"},
    {"key": "roaming", "page": "mobile", "anchor": "abroad"},
    {"key": "airplane", "page": "home", "anchor": "quick-controls"},
    {"key": "touch", "page": "home", "anchor": "quick-controls"},
    {"key": "clock_format", "page": "home", "anchor": "layout"},
    {"key": "badges", "page": "home", "anchor": "layout"},
    {"key": "glance", "page": "lock", "anchor": "lock-screen"},
    {"key": "music_card", "page": "music", "anchor": "on-phone"},
    {"key": "calls", "page": "calls", "anchor": "settings"},
    {"key": "app_push", "page": "apps", "anchor": "app-push"},
    {"key": "locate", "page": "locate", "anchor": "location"},
    {"key": "wifi_signin", "page": "logins", "anchor": "logins-wifi"}
  ],
  "report": {"device_owner": true, "sim": true, "location": true, "background_data": false,
             "full_screen_intent": true, "fingerprint": null},
  "policy": {"ticked": ["vibration", "media_on_lock"]},
  "needed": [
    {"name": "fresh phone, calls managed, Flymodus on",
     "policy": {"mask": 64, "clock_format": "system", "calls_managed": true, "android_lock": true},
     "readings": {"parent_pin": true, "device_owner": true, "location": true, "notification_access": false,
                  "mobile_data": false, "data_saver": true, "background": false, "vibb_notifications": true,
                  "full_screen_intent": true, "assistant": false, "call_filter": false, "default_apps": true,
                  "fingerprint": false},
     "ticked": [],
     "missing": ["notification_access", "mobile_data", "background", "assistant", "call_filter", "vibration",
                 "fingerprint", "touch_feedback", "lock_clock", "media_on_lock", "message_banners"],
     "count": 6, "alarm": false},
    {"name": "no SIM: mobile_data absent", "...": "..."},
    {"name": "not device owner: own row, not counted", "...": "..."},
    {"name": "unreadable reading (null) never counts", "...": "..."},
    {"name": "wifi_airplane only once the role is held", "...": "..."}
  ],
  "clean": [
    {"in": {"device_owner": true, "location": "yes", "extra": 1}, "out": {"device_owner": true}},
    {"in": [], "out": null}
  ]
}
```

- **`steps`:** the table in §2.2, every field. The order is the display order.
- **`features`:** where each feature's warning line goes (§5.4).
- **`report`:** the exact `setup` object of a status report (§4.1).
- **`policy`:** the policy's `setup` (§4.3).
- **`needed`:** the shared rows. `readings` is a flat map from step key to `true`/`false`/`null`, with absent keys
  absent. Each side first maps its sources (§2.2's "Reading" column) to this map with its own code, then runs the one
  rule: `missing` in table order, `count`, and `alarm` (an `own_row` alarm step missing).
- **`clean`:** the server's field-by-field cleaning. It drops unknown keys and non-booleans. A non-object becomes
  `null`.

The wording that only the PWA uses (warning lines, ⓘ texts) lives in `S/i18n/{nb,en}.json`. The phone's row
summaries live in `strings.xml`. Only `names` are shared, and each side's test checks its own strings against them.

## 4. The report, the intake, the policy

### 4.1 The status report's `setup`

The phone sends only readings no other field carries. The rest stay in the fields that already carry them
(`notification_listener_enabled`, `battery_optimization_exempt`, `write_settings_granted`, `mobile_data`,
`airplane_mode`, `call_state`, `notification_channels`), so nothing is reported twice.

```json
"setup": {"device_owner": true, "sim": true, "location": true, "background_data": true,
          "full_screen_intent": true, "fingerprint": false}
```

- Built by a new `L/setup/SetupReadings.kt` (`report(context)`, each read in `runCatching`, `null` when unreadable)
  and added to `statusReport` (MdmSyncWorker.kt:524-614).
- `fingerprint` is sent only with a sensor. `full_screen_intent` is always sent; the need rule decides.
- A full-report field, not a heartbeat field: it changes rarely, and the hash covers it. This is design 98 §5.1's
  choice for `airplane_mode`.
- What prompts a fresh report: Settings' `onResume` after Android's page (today's pattern, the rows re-read on resume),
  and the existing broadcasts. The listener's `onListenerConnected`/`onListenerDisconnected` and
  `ACTION_RESTRICT_BACKGROUND_CHANGED` (registered at runtime) each request a sync (`SyncRunner.request(context,
  "setup")`), so the PWA clears within seconds of the parent's tap.

### 4.2 Server intake

- `S/setup_status.rs::clean(value) -> Option<String>`, called beside the other cleaners (S/handlers/device_api.rs:1517-1545).
  It is stored in a new `device_status.setup_json TEXT`.
- `intake_limits` keeps the `report` row's keys.
- Migration `01xx_setup_status.sql` (the next free number at build time; design 110 reserves 0155):
  - `ALTER TABLE device_status ADD COLUMN setup_json TEXT`;
  - `CREATE TABLE device_setup_ticks (device_id INTEGER NOT NULL REFERENCES devices(id) ON DELETE CASCADE, step TEXT
    NOT NULL, ticked_at TEXT NOT NULL, PRIMARY KEY (device_id, step))`.
  - `device_policy.setup_card_hidden` (S/models.rs:138) loses its reader and stays as a dead column. Migrations are
    kept (C1's rule).

### 4.3 The policy's `setup`

- A new top-level `setup: {"ticked": [keys]}`, always sent. With it the phone leaves ticked rows out of its section and
  its count.
- Pinned by `policy_json_keys_snapshot` and `PolicyResponseCompatTest`.
- A tick's save sends the usual SSE nudge.

## 5. The PWA

### 5.1 Device › Oppsett (`GET /devices/{id}/setup`, `T/device_setup.html`)

A sub-page like Mobilnett, with the band "Oppsett". Built by `setup_status::page(lang, policy, latest, ticks, open)`.
Top to bottom:

1. **Open group** (`#open`, shown while a `phone`/`parent` step is missing): the existing "Åpne for oppsett i 2 t"
   (`POST /devices/{id}/start-pause`, `back=setup`), or, while open, "Åpnet til 17:10" with "Lås igjen". This reuses
   `quick_actions::setup_card` (S/handlers/quick_actions.rs:598-650). One ⓘ by the header gives the phone's way in.
2. **"På telefonen" / "On the phone"** (`#phone`): the missing required `phone` and `server` steps, in table order. Each
   row is `id="step-<key>"`: the name and, where the state has a detail, a short value (row 8: the channels' names;
   row 14: the moved roles). An ⓘ holds what to do on Android's page.
   - `parent_pin` is a link row to `/lock#parent-pin`.
   - `device_owner` is red, and its ⓘ says to reset and use the QR (link to `/devices/{id}/provision`).
3. **"Sjekk selv" / "Check by hand"** (`#check`): the unticked `parent` steps. Each row is a checkbox that saves by
   itself (`POST /devices/{id}/setup/ticks`, `step`, `on`; with JS `scroll-restore.js`, without JS a `<noscript>` Save
   that lands on `#step-<key>`, so a save never jumps to the top). Each row has an ⓘ with Android's path.
4. **"Valgfritt" / "Optional"** (`#optional`): missing `optional` `phone` steps (fingerprint), quiet.
5. **Ticked** steps sit in one closed `<details>` "Avkrysset (4)" / "Ticked (4)" at the end, where they can be unticked.
6. Nothing missing: one quiet line, "Alt er gjort" / "All done". No report yet: "Venter på telefonen" / "Waiting for
   the phone".

A required step that can't be done on this phone (row state NO_PAGE) still counts: the parent sees it, and the ⓘ says
Android has no page for it.

### 5.2 The summary

- **The Attention row:** a new `attention::Kind::Setup(n)`, level Action, title "Oppsett: N steg igjen" / "Setup: N steps
  left" (plural), target `/devices/{id}/setup`. It's raised when N > 0, placed first among the Action rows, and counts
  once in the Devices list's badge. `device_owner` missing raises its own alarm row, "Vibb One styrer ikke telefonen" /
  "Vibb One doesn't manage the phone" → `/devices/{id}/setup#step-device_owner`. `parent_pin` keeps today's
  `NoParentPin`.
- **The sub-page row:** "Oppsett" in the phone's page list before "Om telefonen" (T/device_detail.html:571). Its quiet
  value is "3 igjen" / "3 left", or "Ferdig" / "Done". It isn't orange, because the Attention row already says it.
- **Gone from the summary:** the Oppsett card (T/device_detail.html:160-185) with "Ikke nå", and `#mobile-state`'s
  "Mobildata er slått av på telefonen" (S/mobile_network.rs:353-361), which becomes the step. The roaming and Bare Vibb
  lines stay.
- **Moved:** the un-enrolled phone's `#oppsett` flash (T/device_detail.html:110-115) goes to the page's `#open`.

### 5.3 ASK: "Ikke nå" goes

Today the parent can hide the card until the needed set changes (`POST /devices/{id}/setup-card/hide`,
S/handlers/device_pages.rs:1666-1700). With the user's rule ("one orange line while steps remain, nothing when
done"), a required step can't be hidden. Optional steps are never orange, so they need no hide. **Recommended:** the
route, its button and `device.summary.setup.not_now`/`.hidden` go. The visible cost is that a parent who decides
against a required step (say Data Saver) keeps an orange line. The alternative is a per-step "Hopp over" on required
steps too, stored like a tick.

### 5.4 The warnings at each choice

A step that is missing and applies shows **one short orange line at the top of each section its `features` name**. The
line is an `<a class="attention attention-action" href="/devices/{id}/setup#step-<key>">` with the chevron, from one
partial, `T/partials/setup_warning.html`. It's fed by `setup_status::warnings(page)`, which returns the lines for the
page's anchors. A section with two missing steps shows two lines.

| Page › section | Choice there | Steps that warn |
|---|---|---|
| Mobilnett › `#mobile-data` | Mobildata: På / Bare Vibb | `mobile_data`, `data_saver` |
| Mobilnett › `#abroad` | Roaming | `mobile_data` |
| Home Screen › `#quick-controls` | Flymodus (bit 64); Berøringslyder og vibrasjon (bit 16) | `assistant`, `wifi_airplane`; `write_settings` |
| Home Screen › `#layout` | the clock format; the badges | `write_settings`; `notification_access` |
| Lock Screen › `#lock-screen` | the glance | `notification_access` |
| Music › `#on-phone` | the music card | `notification_access` |
| Calls › `#settings` | Calls managed | `call_filter`, `full_screen_intent` |
| Apps › `#app-push` | App push | `background`, `data_saver` |
| Locate › `#location` | Location | `location` |
| Sign-in › `#logins-wifi` | Wi-Fi sign-in | `location` |

These lines replace:
- the Home notes `notification_access_note` and `clock_format_note` (S/handlers/device_pages.rs:1518-1559;
  T/device_home.html:19-23);
- the Calls page's `Finding::NoRedirection` (S/handlers/calls.rs:1409-1416), with its adb fix text;
- the Mobilnett page's own data-off line from `card.data_lines` (T/device_mobile.html, `mobile.state.data_off`).

### 5.5 About This Phone

The state rows stay; their how-to text goes (say it once). Each row for a missing step becomes a link (chevron) to
`/devices/{id}/setup#step-<key>`, with no detail text. This covers Batterioptimalisering, Endre systeminnstillinger,
Digital assistent and Wi-Fi i flymodus (S/handlers/device_pages.rs:677-741), and Varsler (:631-645).

### 5.6 Copy (checked against "Words in the UI")

Warning lines (`setup.warn.<key>`):

| key | nb | en |
|---|---|---|
| `location` | Vibb One mangler posisjonstilgang | Vibb One has no location access |
| `notification_access` | Varseltilgang er av | Notification access is off |
| `mobile_data` | Mobildata er av på telefonen | Mobile data is off on the phone |
| `data_saver` | Datasparing stopper Vibb på mobildata | Data Saver stops Vibb on mobile data |
| `background` | Bakgrunnstilkobling er av | Background connection is off |
| `full_screen_intent` | Anrop vises ikke over låsen | Calls don't show over the lock |
| `write_settings` | Vibb One kan ikke endre systeminnstillinger | Vibb One can't modify system settings |
| `assistant` | Vibb One er ikke digital assistent | Vibb One isn't the digital assistant |
| `wifi_airplane` | Wi-Fi i flymodus er ikke satt opp | Wi-Fi in airplane mode isn't set up |
| `call_filter` | Samtalefilteret er ikke valgt | The call filter isn't chosen |

The ⓘ texts (`setup.how.<key>`): what to do on Android's page, a few words. Android's own labels are to be checked on
the Jelly Star (Unihertz's nb strings) in the gate. Nothing about adb (§5.7).

| key | nb | en |
|---|---|---|
| group header (`setup.how.phone`) | Trykk på varselet «Fullfør oppsettet av Vibb One» på telefonen, og skriv foreldre-PIN-en. | Tap the notification «Finish setting up Vibb One» on the phone, and type the parent PIN. |
| `device_owner` | Tilbakestill telefonen, og sett den opp med QR-koden. | Reset the phone, and set it up with the QR code. |
| `location` | Tillatelser › Posisjon › Tillat. | Permissions › Location › Allow. |
| `notification_access` | Slå på Tillat varseltilgang. | Turn on Allow notification access. |
| `mobile_data` | Slå på Mobildata. | Turn on Mobile data. |
| `data_saver` | Slå på Ubegrenset databruk. | Turn on Unrestricted data. |
| `background` | Velg Tillat. | Choose Allow. |
| `vibb_notifications` | Slå på varslene som er av. | Turn on the notifications that are off. |
| `full_screen_intent` | Slå på Tillat fullskjermvarsler. | Turn on Allow full-screen notifications. |
| `write_settings` | Slå på Tillat. | Turn on Allow. |
| `assistant` | Velg Vibb One, og trykk OK. | Choose Vibb One, then OK. |
| `wifi_airplane` | Slå på Wi-Fi i panelet. | Turn on Wi-Fi in the panel. |
| `call_filter` | Velg Vibb One begge gangene. | Choose Vibb One both times. |
| `default_apps` | Velg dem tilbake. | Choose them back. |
| `vibration` | Lyd og vibrasjon › Vibrasjon og haptikk: ringevibrasjon og varselvibrasjon på. | Sound & vibration › Vibration & haptics: ring and notification vibration on. |
| `touch_feedback` | Lyd og vibrasjon › Vibrasjon og haptikk: berøringsrespons på. | Sound & vibration › Vibration & haptics: touch feedback on. |
| `lock_clock` | Skjerm › Låseskjerm: dobbeltlinjet klokke av. | Display › Lock screen: double-line clock off. |
| `media_on_lock` | Lyd og vibrasjon › Medier: vis medier på låseskjermen av. | Sound & vibration › Media: show media on lock screen off. |
| `message_banners` | Appen › Varsler › meldingskanalen › Vis på skjermen. | The app › Notifications › the message channel › Pop on screen. |

Plus `setup.summary` ("Oppsett: {n} steg igjen" / "Setup: {n} steps left", with a singular), `setup.page.title`
("Oppsett" / "Setup"), the group headers, `setup.ticked` ("Avkrysset ({n})" / "Ticked ({n})"), `setup.all_done` and
`setup.no_report`. The role words `device.summary.setup.role.*` move to `setup.role.*`.

### 5.7 ASK: the adb lines leave the PWA

The texts this design replaces carry an adb shortcut:
- `device.about.battery_sub` and `device.write_settings_steps` (nb.json:1695-1697);
- `device.home.no_notification_access_fix` (:1717);
- `device.calls.finding.no_redirection_fix` (:1841).

**Recommended:** none move to the new ⓘ texts. The on-phone path is the way ("no adb beyond the QR"), and the words
rule asks for few words. The adb lines stay in the runbook `docs/setup/android-settings.md` and in
`docs/testing/emulator.md`. The cost: a parent with USB debugging on loses a copy-paste in the PWA.

## 6. The phone

### 6.1 The section "Fullfør oppsett"

- **Where:** a `PreferenceCategory` `settings_setup` first in `res/xml/preferences.xml`. It holds one row per step key
  and is hidden while empty.
- **Moved in:** today's rows Bakgrunnstilkobling, Endre systeminnstillinger, Varsler fra Vibb One, Digital assistent,
  Wi-Fi i flymodus, Samtalefilter, Fingeravtrykk and Medier på låseskjermen (preferences.xml:47-94). Their keys and
  click handlers stay (SettingsFragmentLauncher.kt:300-400).
- **New rows:** `device_owner` (not tappable), `location`, `notification_access`, `mobile_data`, `data_saver`,
  `full_screen_intent`, `default_apps`, `vibration`, `touch_feedback`, `lock_clock` and `message_banners`.
- **Visibility:** a row shows only while its step is missing, by `setupMissing(...)` (§6.3): required steps first, then
  optional ones, in table order.
  - Today's own rules (`assistantRowShown`, `screeningRowShown`, the notification row's `notificationsRowText != null`,
    the fingerprint row's own conditions) are folded into the contract's `when` plus the reading.
  - The media row is no longer "always shown" (preferences.xml:89-94): it shows until ticked in the PWA.
- **Row mechanics unchanged:** `systemPageIntent` (L/server/BatteryExemption.kt:66-81), `systemPageAccess` with its
  states (L/server/WriteSettingsAccess.kt:22-37), `ownPageOpening`, `openFirstPage`, and the assistant's and the call
  filter's pick window.
- **New page lists:** each new step gets a `pageIntents(context)` beside the existing ones, built from §2.2's actions
  and made explicit by `systemPageIntent`. `SetupStepsTest` checks their actions against the contract's `phone_page`.
- **Order on screen:** the section is first. The pause switch "Sett alle begrensninger på pause" stays where it is,
  because a row outside the window says "Sett på pause først".

Row summaries (`setup_row_*`, the generic states replacing each row's own set):

| state | nb | en |
|---|---|---|
| CAN_OPEN | the step's short how (as §5.6's ⓘ, e.g. "Slå på Tillat varseltilgang") | the same in en |
| PAUSE_FIRST | Sett på pause først | Pause first |
| WAIT | Et øyeblikk … | One moment … |
| NO_PAGE | Finnes ikke på denne telefonen | Not on this phone |
| `device_owner` | Sett opp telefonen på nytt med QR-koden | Set the phone up again with the QR code |
| a `parent` step, CAN_OPEN | the how, then "Kryss av i foreldreappen etterpå." | the how, then "Tick it in the parent app afterwards." |

This replaces the long summaries such as `settings_assistant_open` (values-nb/strings.xml:831: three sentences) and
`settings_mdm_write_settings_*` (:471-474). On the kid's phone fewer words still: the kid never sees these rows (the
section is behind the parent PIN).

### 6.2 The notification

`AssistantSetup.refreshNotice` (L/assistant/AssistantSetup.kt:294-325) keeps everything:
- the channel `launcher:setup` (LOW, secret on the lock screen);
- the title "Fullfør oppsettet av Vibb One";
- `setup_notice_steps` "N steg igjen";
- it shows only while the window is open.

The changes:
- `stepsLeft` (:274-291) becomes `SetupSteps.count(readings, policy, ticked)`, the shared rule, so its N equals the
  PWA's for the same report.
- Its tap scrolls to `settings_setup` instead of Bakgrunnstilkobling (SettingsFragmentLauncher.kt:571-575).
- It is refreshed after every apply (as today, :186) and on Settings' resume.

### 6.3 Code

- **`L/setup/SetupSteps.kt` (pure, new):**
  - the `SetupStep` enum: the contract's keys, need, `read_by`, `when`, summary, level, page actions, string ids;
  - `SetupPolicy` (mask, clock format, calls managed, android lock);
  - `setupMissing(policy, readings, ticked): List<SetupStep>` and `setupCount(...)`.
  - It absorbs `setupStepsLeft` and `screeningRowShown` (AssistantSetupPlan.kt:59-82); `assistantRowShown` (:40)
    folds into `when: airplane`.
- **`L/setup/SetupReadings.kt` (new):**
  - `readings(context): Map<String, Boolean?>`, the live reads behind §2.2's "Reading" column, off the main thread;
  - `report(context)` for §4.1.
  - It reuses `BadgeStore.accessGranted`, `BatteryExemption.exempt`, `WriteSettingsAccess.granted`,
    `AirplaneRuntime.roleHeld`/`wifiKept`/`defaultAppsChanged`, `CallSystem.*RoleHeld`, `FingerprintEnrol.enrolled`,
    and `MobileNetworkRuntime.mobileDataReport().enabled`.
  - It adds `NotificationChannels`' orange rule (`channelsOrange`, design 107 `pages` rows; today only the server runs
    them).
- **`PolicyResponse.setup`**, decoded with a default of `null` meaning none ticked. Phones and the server ship together
  after C1; the default is a fail-safe, not compat.

## 7. What changes elsewhere on the phone

### 7.1 Vibb's own location permission, always

- Today:
  - `LocateCommands.currentLocation` grants FINE/COARSE/BACKGROUND only when the policy isn't Off (L/server/LocateCommands.kt:103-125);
  - the zones grant them while watched (L/zones/ZoneFences.kt:97-108);
  - the Wi-Fi config path grants FINE and puts its state back (L/server/WindowRelock.kt:223-244).
- With Locate Off, Android refuses Wi-Fi scan results (`startScan … has no location permission`,
  docs/testing/2026-11-17-r2r3-swipe-gate.md:131-133), and the sign-in window's network name is `null`. On Android
  13-17 `getScanResults` still needs FINE location (NEARBY_WIFI_DEVICES doesn't cover it).
- **New:** `AppEnforcer.apply` self-grants FINE and COARSE on every apply while device owner, beside
  `applyCallPermissions` (L/server/AppEnforcer.kt:835-846), through `QuickControls.selfGrantPermission` (written only
  when the state differs).
- **BACKGROUND stays as today** (Locate/zones). **ASK:** grant it always too. It isn't needed for the Wi-Fi page or the
  sign-in window, which run in the foreground. Recommended: no.
- **Privacy:** "Off" still never touches LocationManager and never takes a fix. The rule in
  docs/notes/launcher/time-rules.md:118 changes from "Off never touches LocationManager or the permissions" to "Off
  never touches LocationManager; FINE/COARSE are always Vibb's (the Wi-Fi page and the sign-in window need them)". The
  system Location switch is unchanged: the Wi-Fi page turns it on and off itself (WifiNetworksActivity.kt:118, :184).
- **Removed:** `WindowRelock.withWifiConfigs` drops its FINE grant-and-put-back. Its location-switch half stays.
- **Reported** as `setup.location` (row 3).

### 7.2 Notification access on the phone

Row 4 is the new on-phone path. Since this applies to 14-17, the gate checks two things:
- **The detail page:** `ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS` resolves to a system Settings activity (or its
  fallback does) on 14 and 16.
- **Android's "restricted setting":** Android 13+ greys the switch for an app installed outside a session-based
  installer, and Android 15 widened that. Vibb One is installed by provisioning and updated through PackageInstaller
  sessions, so it shouldn't be restricted.
  - If the gate finds the switch greyed, the row's how gets one more line: "Appinfo › ⋮ › Tillat begrensede
    innstillinger" / "App info › ⋮ › Allow restricted settings".
  - That App info page is reachable by the same `systemPageIntent` (`ACTION_APPLICATION_DETAILS_SETTINGS`).

`BadgeStore.kt:31-34`'s comment loses "adb at provisioning". adb stays in the runbook only.

### 7.3 Android 14-17

- Android 17 never hides or suspends Settings (docs/notes/launcher/hardening.md:69-79). `systemPageIntent`'s
  `plannedOff` check (L/server/BatteryExemption.kt:33-34) keeps rows at "Sett på pause først" there too.
- `canUseFullScreenIntent` and its page are API 34, so there's no gate.
- `ACTION_MANAGE_DEFAULT_APPS_SETTINGS`, `ACTION_IGNORE_BACKGROUND_DATA_RESTRICTIONS_SETTINGS` and
  `ACTION_NETWORK_OPERATOR_SETTINGS` are long public, but their targets differ by OEM. That's why the gate checks the
  Jelly Star's names.
- The Internet panel (`Settings.Panel.ACTION_INTERNET_CONNECTIVITY`) hands over to SystemUI's dialog on 12+, so it is
  only the fallback for row 5.
- Android 17 is owed: there's no emulator for it here. Check it on the first 17 phone.

## 8. What folds in, moves or goes

| Today | Where | Becomes |
|---|---|---|
| `setupStepsLeft`, `screeningRowShown`, `assistantRowShown` | L/assistant/AssistantSetupPlan.kt:40, 59-82 | `SetupSteps.setupMissing/setupCount` (§6.3) |
| `AssistantSetup.stepsLeft` | L/assistant/AssistantSetup.kt:274-291 | `SetupSteps.count` over `SetupReadings` |
| the notification's tap target | SettingsFragmentLauncher.kt:571-575 | scrolls to `settings_setup` |
| the scattered grant rows | preferences.xml:47-94 | the category `settings_setup`, rows driven by `setupMissing` |
| `updateAssistantRows`'s visibility rules | SettingsFragmentLauncher.kt:753-790 | the contract's `when` + readings; the click handlers stay |
| the location grants tied to Locate | LocateCommands.kt:103-125, ZoneFences.kt:97-108, WindowRelock.kt:223-244 | FINE/COARSE always in `AppEnforcer.apply` (§7.1) |
| `airplane_mode::SetupStep`, `setup_steps`, `setup_key`, `SetupCard` | S/airplane_mode.rs:313-448 | `S/setup_status.rs` (`missing`, `page`, `warnings`, `clean`); the airplane module keeps its report and lines |
| the Oppsett card | T/device_detail.html:160-185; S/handlers/devices.rs:1136-1137, 2435 | the Attention row `Setup(n)` + Device › Oppsett |
| "Ikke nå" | S/handlers/device_pages.rs:1666-1700; S/main.rs:1217; S/settings_log.rs:65; `device_policy.setup_card_hidden` | gone (§5.3); a new logged route `/devices/{id}/setup/ticks` |
| `back=setup` → `/devices/{id}#oppsett` | S/handlers/window_relock.rs:311-314 | → `/devices/{id}/setup#open`; the QR page's "Åpne for oppsett nå" (T/provision_qr.html:76-83) lands there |
| Attention `NoNotificationAccess`, `NotificationsOff`, `Calls(NoRedirection)` | S/attention.rs:260-261, 330-331, 375-377, 417, 428; S/handlers/calls.rs:1851, 1895 | folded into `Setup(n)` |
| Attention `NoParentPin` | S/attention.rs | kept (row 1 is `own_row`) |
| the summary's "Mobildata er slått av på telefonen" | S/mobile_network.rs:353-361 | step 5 (the approved orange + Attention, PLAN's copy-round entry) |
| Home's notification-access and clock notes | S/handlers/device_pages.rs:1518-1559; T/device_home.html:19-23 | §5.4 warning lines |
| Calls' `NoRedirection` finding + adb fix | S/handlers/calls.rs:1409-1416 | §5.4 warning line |
| About's how-to details | S/handlers/device_pages.rs:631-645, 677-741 | state rows that link to the step (§5.5) |
| `device_pages.rs:1626-1631`'s `airplane_mode_v1` gate on Home's Flymodus row | | C1 removes it (§9) |
| `docs/setup/android-settings.md` | | stays the reference; each section names its step key; no per-phone checklist elsewhere |
| i18n | nb/en.json:1695-1697, 1715-1717, 1841, 3956, 4535-4554 | replaced by `setup.*` (§5.6); dead keys removed |

## 9. Build order

**Before:**
- **C1 merged.** C1 removes the capability gates, and for design 98 that means:
  - `airplane_mode::report` (S/airplane_mode.rs:170-175) reads `airplane_mode_json` without
    `has_capability(.., "airplane_mode_v1")`;
  - the Home page's Flymodus row (S/handlers/device_pages.rs:1626-1631) and About's assistant/Wi-Fi rows (:715-741)
    show for every phone;
  - `takes_commands` (`window_relock_v1`, S/handlers/quick_actions.rs:621) goes;
  - `airplane_mode.json`'s `capability` row and every `older_server` row go;
  - design 98 §6.4 ("An older launcher, an older server") is void.

  This design leans on that. It adds **no** `setup_status_v1` capability, no "older launcher" branch for a report
  without `setup`, and no "older server" branch for a policy without `setup`. Without C1, `setup_steps` would still
  hide the assistant/Wi-Fi steps from a launcher without `airplane_mode_v1`. If C1 slips, this waits for it rather
  than adding one more flag.
- **The copy round** (design 112 + the words audit + Mobilnett) merged first, or built in the same window. It touches
  the same About, Home and Mobilnett texts, and §5.6 follows its rules.

**Steps** (one branch, the contract and both sides' encode/decode in one commit, per the API rule):

1. **Contract + pure rules (S + L, one commit).**
   - `server/testdata/setup_steps.json` and its launcher copy;
   - `S/setup_status.rs` (table, `missing`, `clean`) with its tests;
   - `L/setup/SetupSteps.kt` with `SetupStepsTest`;
   - `PolicyResponse.setup` and the policy's `setup`;
   - the report's `setup` (`SetupReadings.report`) and the intake + migration;
   - `launcher.yml` and `server-ci.yml` path filters and the root `CLAUDE.md`'s contract paragraph get the new file.
   - Size: M.
2. **The launcher** (L):
   - the `settings_setup` category and the moved rows;
   - the new rows and their page lists;
   - the notification on `setupCount`;
   - FINE/COARSE always (§7.1);
   - the sync triggers (§4.1);
   - the strings (nb + en).
   - Size: M.
3. **The server** (S):
   - the page and its route;
   - the tick route (logged);
   - `Kind::Setup` and the folds;
   - the warning partial on the ten sections of §5.4;
   - About's links;
   - the card, the hide route and the dead i18n removed;
   - `back=setup`'s new target.
   - Size: M.
4. **Code QA → fix all → emulator gate (§11, after the user's OK) → merge.** PLAN's queue entry is marked built in
   the merge commit.

Design 110, built after C2, then adds its rows 21-22 to the contract (§2.6, if the ASK says so).

## 10. Tests

**Shared (`setup_steps.json`), both sides compare the copies.**

**Server.**
- `setup_status::tests::the_contract` runs:
  - every `needed` row through `missing`/`count`;
  - the `steps` table against the module's own constants (key, need, `read_by`, `when`, summary, level, features);
  - every `features` row against a known page and anchor;
  - every `names` against `setup.step.<key>` in nb.json and en.json;
  - every `clean` row.
- `tests::setup_status`:
  - posts `report` and checks `setup_json` stored, with a report whose readings make every step missing;
  - renders `/devices/{id}/setup` and checks the groups, the rows' `id`s and the open group;
  - posts a tick, checking the stored row, the redirect to `#step-<key>`, the `settings_log` entry, the policy's
    `setup.ticked` and the row moved into "Avkrysset";
  - unticks;
  - checks the summary's `Setup(n)` row: its title, its level, that it isn't raised at N = 0, `device_owner`'s alarm
    row with no `Setup` duplicate, and nothing for an un-enrolled phone;
  - for every `features` row, renders its page with the step missing (one line linking to `/setup#step-<key>`) and
    done (no line);
  - checks About's rows link and carry no how-to;
  - checks `back=setup` lands on `/setup#open`.
- `intake_limits` keeps `report`'s keys. `policy_json_keys_snapshot` gets `setup`.
- **Removed:** `tests/airplane_mode.rs:373-389` (the hide route) and the attention tests of the folded kinds, which are
  rewritten for `Setup(n)`.

**Launcher.**
- `SetupStepsTest` checks:
  - `SetupStep` (keys, order, fields) against `steps`;
  - each step's string in `values` and `values-nb` against `names`;
  - each step's `pageIntents` actions against `phone_page` (pure: the action lists live beside the builders);
  - every `needed` row through `setupMissing`/`setupCount`;
  - `report` encoded exactly from a `SetupReport`;
  - `policy` decoded;
  - the copies compared.
- `NotificationChannelsTest` also runs 107's `pages` rows' `orange` through `channelsOrange`.
- `AssistantSetupTest` loses `setupStepsLeft`'s rows (moved).
- `PolicyResponseCompatTest` decodes `setup`.
- A pure `setupRowText(step, access)` is tested for every step × `SystemPageAccess`.
- `LocationPolicyTest` gets a row: Off takes no fix, and the plan's own grants include FINE/COARSE.

## 11. Emulator gate (`docs/testing/emulator.md`, a new section)

Needs the user's OK first (memory "Emulator: ask first"). It runs on **`compat34`** (API 34, `-wipe-data`) and
**`jelly36`** (API 36, restored clean). Each row runs on both unless it says otherwise. Setup:
- a server of the branch;
- the phone "Testtelefon" (never a real name) and a test parent code;
- the branch's debug launcher as Device Owner, enrolled;
- calls and SMS managed, bit 64 on.

| # | Row | Pass |
|---|---|---|
| 1 | No parent PIN yet | Oppsett lists Foreldre-PIN first (link); the summary shows `NoParentPin` and no `Setup` row for it |
| 2 | Notification access: `cmd notification disallow_listener …` | `Setup(n)` orange; Home `#layout`, Lock `#lock-screen` and Music `#on-phone` show the line. In a pause the phone row opens the listener detail page; Allow; back: the row is gone and the PWA clears within seconds. Not greyed as a restricted setting (34 and 36; record otherwise, §7.2) |
| 3 | Mobile data: `svc data disable` | Step 5 orange; Mobilnett's two lines. Outside a pause the row says "Sett på pause først" and a tap opens nothing (`mLockTaskModeState=LOCKED`). In a pause it opens the SIM page (record if it falls back to the panel); on; cleared |
| 4 | Data Saver: `cmd netpolicy set restrict-background true` | Step 6; the row opens Vibb One's Unrestricted data switch; on; cleared. Record whether lock task or the mobile-network lock stops the parent's Data Saver toggle outside a pause |
| 5 | Full-screen intent: `appops set <pkg> USE_FULL_SCREEN_INTENT deny` | Step 9 on the Calls page; the row opens Android's page; allow; an incoming call (`adb emu gsm call`) shows Vibb's call screen over the lock |
| 6 | Location with Locate Off | After an apply, `dumpsys package <pkg>` shows FINE/COARSE granted (policy-fixed); the Wi-Fi page lists networks; `fake-portal.py`'s window reports its SSID; no `LocationManager` request in the log, no location indicator |
| 7 | Not device owner (`compat34` only: a fresh debug install enrolled without `dpm set-device-owner`) | `setup.device_owner` false; the red row; no `Setup` count for it |
| 8 | Ticks | Tick Vibrasjon in the PWA: the policy carries it, the phone row goes, N drops on both sides; untick brings it back; the save stays at `#step-vibration` |
| 9 | The notification | Open for setup: "Fullfør oppsettet av Vibb One · N steg igjen" with N equal to the PWA's; a tap goes to the PIN, then Settings at the section; Lås igjen: gone |
| 10 | Design 98's rows inside the section | Digital assistent, Wi-Fi i flymodus and Samtalefilter work as in §6am (regression) |
| 11 | Standardapper | After a call-filter window that moved the browser, the row names it and opens Android's Default apps page |
| 12 | The eye (`jelly36` only) | Screenshots of Device › Oppsett, the summary row and the phone section, in nb and en, for the user |

Not run: Android 17 (no emulator) and the Jelly Star (owed: Unihertz's page names in §5.6, the SIM page, the
restricted-setting check).

## 12. The ASK list

1. **`vibration` required or optional** (§2.2 row 16). Recommended: required. The user said "så lenge telefonen alltid
   vibrerer", and Android 13+ hides both intensities from every app. Required means orange until ticked once.
2. **Message banners: tick now, detect later** (§2.5). Recommended: tick now. Detection from `Ranking.importance` per
   app comes in a later round.
3. **Design 110's two map-link steps become rows in this table**, replacing 110's own "Kartlenker" group (§2.6).
   Recommended: yes.
4. **"Ikke nå" goes**, so required steps can't be hidden (§5.3). Recommended: yes. The alternative is a per-step skip.
5. **The adb shortcuts leave the PWA's texts** and stay in the runbook (§5.7). Recommended: yes.
6. **BACKGROUND location stays tied to Locate/zones**, while FINE/COARSE are always granted (§7.1). Recommended: keep
   it tied.

## The user's answers (2026-11-17, 21:50 CET)

- ASK 1 (vibration on calls and messages): OPTIONAL, not required.
- ASK 3 (design 110's two map-link steps in this list): ASKED BACK - the user: "er jo fort noe man kanskje installerer
  etterhvert og ikke ved første setup av en ny telefon?" - answered that a step only applies once its feature is in use
  (the map steps only once map links are on / a map app is chosen); waiting on the user's word.
- ASK 4 ("Ikke nå" goes, no hiding of a required step): TAKEN.
- ASK 6 (background location): follows Locate and zones, as today; fine and coarse always granted.
- ASK 2 and ASK 5: not asked; the recommendations stand unless the user says otherwise (message banners ticked now and
  detected later; the adb shortcuts leave the PWA's texts and stay in the runbooks).

