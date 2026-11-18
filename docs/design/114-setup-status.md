# Design 114: the phone setup status

Status: design, 2026-11-17; **revision 2, 2026-11-18**: design QA applied (`docs/review/113-114-design-qa.md`, the 114
part: H1, M1-M4, L1-L7) and the user's answers of 2026-11-17 folded into the tables (Revision 2, at the end). Two
questions remain for the user (below). Built after consolidation round C1 and on top of the PWA copy round (design 113,
branch `copy-round-113`, built, waiting for the user's look). Inputs: the inventory
`docs/review/2026-11-17-setup-status-inventory.md` (its tables A1-A7, the overlaps B1-B20, the gaps C1-C12), PLAN.md's
entry "A setup checklist for the one-time steps on a phone" with the user's calls of 2026-11-17, design 98 §3.3/§6.2
(`docs/design/98-airplane-mode-assistant.md`), design 107, design 110 (`docs/design/110-map-links.md`, not built),
design 113 (on `copy-round-113`), design 115 (merged), `docs/setup/android-settings.md`, the root `CLAUDE.md` "Words in
the UI". Paths: `L/` = `launcher/app/src/main/java/com/kidslauncher/mdm/`, `S/` = `server/src/`, `T/` = `server/templates/`.
Revision 1's line numbers are on `main` at bbb35266b; revision 2 names functions and keys where the copy round moves
code (QA L6), and its own references are on `main` at 3752baaad and `origin/copy-round-113` at ae483f41f.

## Questions for the user

Two choices with a cost the parent would see. The build waits for both answers; until then neither is assumed.

1. **Message banners: tick by hand now, or detect later** (§2.5). Chat apps such as Element X may show a new message
   only in the notification list and not pop up on the screen, and Vibb can't read that setting today, so the parent
   would tick "Bannere for meldinger" once by hand on Device › Oppsett (a quiet, optional step), and a later round
   could let the phone notice it per app after that app's first message. **Recommended: tick it by hand now, detect it
   later.**
2. **The adb commands leave the PWA** (§5.7). The PWA's fix texts for notification access, the call filter, battery
   optimisation, "Modify system settings" and the web filter's notification access today end with an adb command to
   paste on a computer; with this design each of those steps has a button on the phone instead, so the commands would
   leave the PWA and stay only in the setup docs (the words audit had kept the notification-access one). **Recommended:
   yes, remove them from the PWA and keep them in `docs/setup/android-settings.md`.**

## 1. Goals

1. **One list, grown from design 98's two.** The phone's `setupStepsLeft` (L/assistant/AssistantSetupPlan.kt, 5
   kinds) and the PWA's `airplane_mode::setup_steps` (6 kinds) are replaced by one step table in one shared contract,
   `server/testdata/setup_steps.json`. No third list.
2. **Three layers.** What the Device Owner can do it does and checks (and shows only when it failed). What needs a
   person is one section on the phone, in setup mode, with a direct path to Android's own page per step. What no app
   can read is ticked once by the parent in the PWA.
3. **The PWA:** a page Device › Oppsett, one orange "Oppsett: N steg igjen" on the phone's summary while required steps
   remain (nothing when they're done), and one short orange line at each choice that a missing step stops, linking to
   the step on Device › Oppsett (the user's call, 2026-11-17).
4. **The phone:** a "Fullfør oppsett" section at the top of the parent Settings, listing only the missing steps; the
   design-98 notification counts the same list.
5. **No adb beyond the QR.** Every step a person does has an on-phone path. That includes notification access, which
   today has only an adb path (L/badges/BadgeStore.kt; `device.home.no_notification_access_fix`).
6. **Report what is unreported today:** Device Owner itself, Android's own mobile-data switch (the user's reason, not
   the carrier's), Data Saver, Vibb's full-screen intent, and Vibb's own location permission.
7. **Vibb's own fine and coarse location permission is always granted by the Device Owner.** Today the grant follows
   Locate. Background location keeps following Locate and the zones (the user's answer to ASK 6).

Non-goals: the roaming switch stays a trip step on Mobilnett. Tailnet, the Child PIN, the Home role, backup, other apps'
special accesses (design 68) and the music app keep their own pages and Attention rows (inventory B10-B17). Google
account presence stays out (inventory C12).

## 2. The steps

### 2.1 Fields

Every step has:
- `key`;
- `need`: `required` (counted, orange) or `optional` (listed quietly, never counted). A required step can never be
  hidden or skipped (the user's answer to ASK 4); it leaves the list only when done;
- `read_by`:
  - `phone`: the phone reports a reading;
  - `server`: the server's own data;
  - `parent`: the parent ticks it once in the PWA. Android won't let an app read it.
- `when`: when the step applies (§2.3);
- `summary`:
  - `count`: in the summary's N;
  - `own_row`: it keeps its own Attention row and is not counted, so the summary says it once;
- `level`: `action` (orange) or `alarm` (red);
- `features`: the PWA choices it stops (§5.4); the column of §2.2 (QA L1);
- `phone_page`: the Android actions the phone row opens, in order. `null` means no on-phone page;
- `names`: nb/en.

Order in the table is the order everywhere: the PWA page, the phone section, the contract.

### 2.2 The table

"Reading" is what decides done/missing; "absent" means not applicable on this phone (the step is left out), `null`
means unreadable (shown nowhere, never counted). Versions: minSdk 34, so every API below exists on 14-17 without a gate
unless the row says otherwise.

| # | key | need | read_by | when | summary / level | features | Reading (done when) | On-phone path (in a pause / Åpne for oppsett) | nb name / en name |
|---|---|---|---|---|---|---|---|---|---|
| 1 | `parent_pin` | required | server | always | own_row / alarm | - | a Parent PIN is stored (today's `NoParentPin` fact) | none: the PWA's `/lock#parent-pin` (without it the phone's Settings doesn't open at all, launcher/CLAUDE.md "Settings is PIN-gated") | Foreldre-PIN / Parent PIN |
| 2 | `device_owner` | required | phone | always | own_row / alarm | - | new `setup.device_owner`: `DevicePolicyManager.isDeviceOwnerApp(packageName)` | none: the phone is reset and set up with the QR (`T/provision_qr.html`) | Enhetseier / Device owner |
| 3 | `location` | required | phone | always | count / action | `locate`, `wifi_signin` | new `setup.location`: `checkSelfPermission(ACCESS_FINE_LOCATION) == GRANTED`. The DO grants it at every apply (§7.1), so missing means the grant failed | `ACTION_APPLICATION_DETAILS_SETTINGS` `package:<ours>` (Permissions › Location) | Posisjonstilgang / Location access |
| 4 | `notification_access` | required | phone | always | count / action | `badges`, `glance`, `music_card`, `notification_filter` | `notification_listener_enabled` (`MdmSyncWorker.statusReport`, `BadgeStore.accessGranted`) | `ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS` with `EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME` = `<ours>/com.kidslauncher.mdm.badges.BadgeListenerService` (API 30), then `ACTION_NOTIFICATION_LISTENER_SETTINGS` | Varseltilgang / Notification access |
| 5 | `mobile_data` | required | phone | always | count / action | `mobile_choices` | new `setup.mobile_data`: `TelephonyManager.isDataEnabledForReason(DATA_ENABLED_REASON_USER)` on the default data subscription (API 31; QA L4: the user's own switch, not a carrier, thermal or policy cut). Absent with no default data subscription (`MobileNetworkRuntime.dataTelephony` null) | `ACTION_NETWORK_OPERATOR_SETTINGS` (the SIM's Mobile network page, "Mobildata"), then `Settings.Panel.ACTION_INTERNET_CONNECTIVITY`; the settings lock is lifted in a pause (`mobileSettingsLocked`, L/server/MobileNetwork.kt) | Mobildata / Mobile data |
| 6 | `data_saver` | required | phone | always | count / action | `mobile_choices`, `app_push` | new `setup.background_data`: `ConnectivityManager.getRestrictBackgroundStatus() != RESTRICT_BACKGROUND_STATUS_ENABLED` (Data Saver on and Vibb not exempt). No permission needed | `ACTION_IGNORE_BACKGROUND_DATA_RESTRICTIONS_SETTINGS` `package:<ours>` (Vibb One's "Unrestricted data" switch) | Datasparing / Data Saver |
| 7 | `background` | required | phone | always | count / action | `app_push` | `battery_optimization_exempt` | the existing dialog, `BatteryExemption.dialogIntent` | Bakgrunnstilkobling / Background connection |
| 8 | `vibb_notifications` | required | phone | always | count / action | - | design 107's orange rule over `notification_channels` (its `pages` rows' `orange`) | the existing `NotificationChannels.pageIntents` | Varsler fra Vibb One / Vibb One notifications |
| 9 | `full_screen_intent` | required | phone | `calls_managed` | count / action | `calls` | new `setup.full_screen_intent`: `NotificationManager.canUseFullScreenIntent()` (API 34) | `ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT` `package:<ours>` (API 34) | Fullskjermvarsler / Full-screen notifications |
| 10 | `write_settings` | required | phone | `write_settings_used` | count / action | `touch`, `clock_format` | `write_settings_granted` | the existing `WriteSettingsAccess.pageIntents` | Endre systeminnstillinger / Modify system settings |
| 11 | `assistant` | required | phone | `airplane` | count / action | `airplane` | `airplane_mode.assistant` | the existing window + `ACTION_VOICE_INPUT_SETTINGS` (`AssistantSetup`) | Digital assistent / Digital assistant |
| 12 | `wifi_airplane` | required | phone | `airplane_role` | count / action | `airplane` | `airplane_mode.wifi_kept != null` (design 98 §6.2: an OEM "turns off" counts as done) | the existing Wi-Fi step (`AssistantSetup`) | Wi-Fi i flymodus / Wi-Fi in airplane mode |
| 13 | `call_filter` | required | phone | `calls_managed` | count / action | `calls` | `call_state.screening_role_held` and `redirection_role_held` both true | the existing role requests (`AssistantSetup`) | Samtalefilter / Call filter |
| 14 | `default_apps` | required | phone | always | count / action | - | `airplane_mode.default_apps_changed` empty | `ACTION_MANAGE_DEFAULT_APPS_SETTINGS` (new; today the step has no path, only words) | Standardapper / Default apps |
| 15 | `map_play_links` | required | phone | `map_links` | count / action | `map_links` | design 110's `map_links.play_services_links == false` | `ACTION_APP_OPEN_BY_DEFAULT_SETTINGS` `package:com.google.android.gms` (API 31) | Lenker i Play-tjenester / Play services' links |
| 16 | `map_vibb_links` | required | phone | `map_links` | count / action | `map_links` | design 110's `map_links.vibb_hosts.selected >= selectable` | `ACTION_APP_OPEN_BY_DEFAULT_SETTINGS` `package:<ours>` (API 31) | Vibb Ones lenker / Vibb One's links |
| 17 | `fingerprint` | optional | phone | `android_lock` | count / action (never counted: optional) | - | new `setup.fingerprint`: `FingerprintEnrol.enrolled`; absent without a sensor | the existing `FingerprintEnrol.pageIntents` | Fingeravtrykk / Fingerprint |
| 18 | `vibration` | optional | parent | always | count / action (never counted: optional) | - | ticked | `ACTION_SOUND_SETTINGS` | Vibrasjon / Vibration |
| 19 | `touch_feedback` | optional | parent | always | count / action | - | ticked | `ACTION_SOUND_SETTINGS` | Vibrasjon ved trykk / Touch feedback |
| 20 | `lock_clock` | optional | parent | `android_lock` | count / action | - | ticked | `ACTION_DISPLAY_SETTINGS` (Display › Lock screen; no public lock-screen action - checked in the gate) | Klokke på én linje / One-line clock |
| 21 | `media_on_lock` | optional | parent | `android_lock` | count / action | - | ticked | the existing `MediaLockScreen.pageIntents` (`ACTION_SOUND_SETTINGS`) | Medier på låseskjermen / Media on the lock screen |
| 22 | `message_banners` | optional | parent | always | count / action | - | ticked (**open question 1**: ticked now or detected later, §2.5) | `ACTION_MANAGE_APPLICATIONS_SETTINGS` (the app list; the channel is the app's) | Bannere for meldinger / Message banners |

Rows 1-16 are required and rows 17-22 optional (the user made `vibration` optional, 2026-11-17), so no optional row
ever turns the summary orange. A fresh phone with nothing done shows at most 12 counted steps (rows 3-14; 1 and 2 have
their own rows, 15-16 apply only with design 110's switch and a map app).

Rows 15-16 are design 110's two one-time steps (110 §6.2), here instead of 110's own "Kartlenker" group (the user's
answer to ASK 3). Their readings come from 110's `map_links` report: until 110 is built they are absent and the steps
never show. 110's third row, "Gi Google Play-tjenester lenkene tilbake" (§6.3), stays 110's own: it isn't a setup step.

Steps that are **not** in the table, and why:
- the roaming switch: a trip step, kept on Mobilnett (design 97; 113's orange `switch_line` says it there);
- POST_NOTIFICATIONS: the DO fixes it (`WindowRelock`); a revoke shows up as row 8;
- the Home role, lock task, the VPN, backup and private DNS: done by the DO, and they already have Attention rows
  (`NotHomeApp`, `PolicyProblem`, `GoogleBackup`);
- Data Saver's cut of *other* apps while Vibb is exempt (design 115 §9: Element X can't fetch on mobile data then):
  row 6 is Vibb's own exemption only. Another app's background data is that app's, and Bare Vibb is Vibb's way of
  cutting apps off mobile data.

### 2.3 `when`: the need rule

Both sides run the same rule from the same inputs: the policy (the server from `device_policy`, the phone from its
cached policy) and, for `map_links` only, the installed apps. That way a step shows up on the PWA the moment the parent
turns on the feature that needs it. Example: tick Flymodus and "Digital assistent" appears at that switch at once,
before the phone reports.

| `when` | applies when |
|---|---|
| `always` | always |
| `calls_managed` | `calls_mode` not `unmanaged` (`DevicePolicy::calls_managed`; `CallPolicyStore.callsState.managed`). Calls Off included, on purpose (QA L5): Vibb still holds the dialer and the screening role then, rejects every call, and an emergency callback rings through Vibb's in-call screen over the lock (`KidInCallService`'s callback window) |
| `write_settings_used` | `quick_controls_mask` bit 16, or the clock format isn't "system" (`AssistantSetupPlan`; `airplane_mode::setup_steps`) |
| `airplane` | bit 64 |
| `airplane_role` | bit 64 and the `assistant` reading done |
| `android_lock` | `kid_lock.keyguard` (Android's PIN lock). C3 (phase D) makes it always true; the condition then goes |
| `map_links` | design 110's `map_links.on` in the policy **and** Organic Maps or CoMaps installed (`app.organicmaps`, `app.comaps.fdroid`, `app.comaps`; 110 §7.1's targets). The installed part is the one report-derived input: the server reads the latest report's `installed_apps_json` packages, the phone the same package list it reports as `installed_apps`, so both see the same set. A policy without `map_links` (before 110) reads as off |

`map_links` asks for both because the user's condition (the steps only while a map app is installed) is not the switch
alone, and the switch is needed too: with it off, step 15 would send short links to a browser or nowhere (110 §6.3).

A step is **missing** when it applies, its reading is present and is `false`, or (for `parent` steps) it isn't
ticked. **N** is the number of missing `required` steps with `summary: count`. The phone and the PWA compute the same
N from the same report. The phone can't see `server` steps, but both of those are `own_row`, so they're never in N.

**The summary row** `Setup(n)` is raised when N > 0 and the `device_owner` reading is not `false` (QA M4): a phone that
isn't device owner is reset and set up with the QR, which redoes every step, so its red row stands alone. The page and
the phone section still list every missing step then; nothing is hidden.

### 2.4 Old phones and fresh phones

- **No capability, and no old capability gate.** C1 removes every claim and gate and keeps the mechanism (the report's
  `capabilities` field, its column and the server's one helper). 114 needs no claim of its own: a phone whose report
  has no `setup` yet (a launcher from before 114, or before its first report on the new build) has those readings
  absent, so those steps aren't shown, and the rest come from fields it already sends; the policy's `setup` is always
  sent, and a launcher that doesn't know it ignores it. 114 reads no `*_v1` claim anywhere (§9).
- On the user's phones, already set up, the deploy shows no `parent` step as orange: all of them are optional now. They
  sit in "Valgfritt" until ticked.

### 2.5 Open question 1: message banners, detected or ticked

Inventory C8: the listener already reads each post's `Ranking.importance` (L/badges/GlanceReader.kt). A later round
could report, per allowed app that posted, whether its message channel is below High. That would make a phone-read step
per app ("Bannere: Element X"), opening `ACTION_APP_NOTIFICATION_SETTINGS` for that package. **Recommended:** tick it
now, detect it in a later round. Detection needs a post first, so it can't help on a fresh phone, and it adds a per-app
list to the contract. The answer is asked (Questions for the user, 1); row 22 is built as the answer says.

### 2.6 Design 110's two steps (decided)

The user's answer to ASK 3: taken, with the condition that the steps apply only while Organic Maps or CoMaps is
installed. They are rows 15-16 (§2.2) with `when: map_links` (§2.3). What changes in 110 when it is built:
- its PIN-gated "Kartlenker" group keeps only the §6.3 row; its two step rows are this section's rows;
- its Apps group's two orange "Ett steg igjen på telefonen …" lines become 114's warning lines at `#map-links` (§5.4);
  its other lines (`missing`, `hidden`, the lookup line, "Klar") stay 110's;
- its report keeps `play_services_links` and `vibb_hosts`, which these rows read;
- its migration renumbers (QA L2: 0155 is `catalog_app_logo` on main).

110's note is revised when it is built (after C2); this note's contract carries rows 15-16 from the start, with no
reading until then.

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
     "level": "action", "features": ["badges", "glance", "music_card", "notification_filter"],
     "phone_page": ["android.settings.NOTIFICATION_LISTENER_DETAIL_SETTINGS",
                    "android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS"],
     "names": {"nb": "Varseltilgang", "en": "Notification access"}},
    {"key": "mobile_data", "need": "required", "read_by": "phone", "when": "always", "summary": "count",
     "level": "action", "features": ["mobile_choices"],
     "phone_page": ["android.settings.NETWORK_OPERATOR_SETTINGS", "android.settings.panel.action.INTERNET_CONNECTIVITY"],
     "names": {"nb": "Mobildata", "en": "Mobile data"}},
    {"key": "map_play_links", "need": "required", "read_by": "phone", "when": "map_links", "summary": "count",
     "level": "action", "features": ["map_links"],
     "phone_page": ["android.settings.APP_OPEN_BY_DEFAULT_SETTINGS"],
     "names": {"nb": "Lenker i Play-tjenester", "en": "Play services' links"}},
    {"key": "vibration", "need": "optional", "read_by": "parent", "when": "always", "summary": "count",
     "level": "action", "features": [], "phone_page": ["android.settings.SOUND_SETTINGS"],
     "names": {"nb": "Vibrasjon", "en": "Vibration"}}
  ],
  "features": [
    {"key": "mobile_choices", "page": "mobile", "anchor": "mobile-data"},
    {"key": "airplane", "page": "home", "anchor": "quick-controls"},
    {"key": "touch", "page": "home", "anchor": "quick-controls"},
    {"key": "clock_format", "page": "home", "anchor": "layout"},
    {"key": "badges", "page": "home", "anchor": "layout"},
    {"key": "glance", "page": "lock", "anchor": "lock-screen"},
    {"key": "music_card", "page": "music", "anchor": "on-phone"},
    {"key": "notification_filter", "page": "advanced", "anchor": "updates"},
    {"key": "calls", "page": "calls", "anchor": "settings"},
    {"key": "app_push", "page": "apps", "anchor": "app-push"},
    {"key": "map_links", "page": "apps", "anchor": "map-links"},
    {"key": "locate", "page": "locate", "anchor": "location"},
    {"key": "wifi_signin", "page": "logins", "anchor": "logins-wifi"}
  ],
  "report": {"device_owner": true, "mobile_data": true, "location": true, "background_data": false,
             "full_screen_intent": true, "fingerprint": null},
  "policy": {"ticked": ["vibration", "media_on_lock"]},
  "needed": [
    {"name": "fresh phone, calls managed, Flymodus on",
     "inputs": {"mask": 64, "clock_format": "system", "calls_managed": true, "android_lock": true,
                "map_links_on": false, "map_app_installed": false},
     "readings": {"parent_pin": true, "device_owner": true, "location": true, "notification_access": false,
                  "mobile_data": false, "data_saver": true, "background": false, "vibb_notifications": true,
                  "full_screen_intent": true, "assistant": false, "call_filter": false, "default_apps": true,
                  "fingerprint": false},
     "ticked": [],
     "missing": ["notification_access", "mobile_data", "background", "assistant", "call_filter",
                 "fingerprint", "vibration", "touch_feedback", "lock_clock", "media_on_lock", "message_banners"],
     "count": 5, "alarm": false, "setup_row": true},
    {"name": "map links on and Organic Maps installed: both steps",
     "inputs": {"mask": 0, "clock_format": "system", "calls_managed": false, "android_lock": true,
                "map_links_on": true, "map_app_installed": true},
     "readings": {"parent_pin": true, "device_owner": true, "map_play_links": false, "map_vibb_links": false},
     "ticked": ["vibration", "touch_feedback", "lock_clock", "media_on_lock", "message_banners"],
     "missing": ["map_play_links", "map_vibb_links"], "count": 2, "alarm": false, "setup_row": true},
    {"name": "map links on, no map app installed: no step", "...": "..."},
    {"name": "map app installed, switch off: no step", "...": "..."},
    {"name": "no default data subscription: mobile_data absent", "...": "..."},
    {"name": "not device owner: own alarm, steps listed, no setup row", "...": "..."},
    {"name": "unreadable reading (null) never counts", "...": "..."},
    {"name": "wifi_airplane only once the role is held", "...": "..."},
    {"name": "calls Off: full_screen_intent and call_filter apply", "...": "..."}
  ],
  "clean": [
    {"in": {"device_owner": true, "location": "yes", "extra": 1}, "out": {"device_owner": true}},
    {"in": {"fingerprint": null, "mobile_data": false}, "out": {"fingerprint": null, "mobile_data": false}},
    {"in": [], "out": null}
  ]
}
```

- **`steps`:** the table in §2.2, every field. The order is the display order.
- **`features`:** where each feature's warning line goes (§5.4).
- **`report`:** the exact `setup` object of a status report (§4.1).
- **`policy`:** the policy's `setup` (§4.3).
- **`needed`:** the shared rows. `inputs` are the need rule's inputs (§2.3), each side mapping its own sources to them
  (`map_app_installed` from the installed packages). `readings` is a flat map from step key to `true`/`false`/`null`,
  with absent keys absent. Each side first maps its sources (§2.2's "Reading" column) to these maps with its own code,
  then runs the one rule: `missing` in table order, `count`, `alarm` (an `own_row` alarm step missing) and `setup_row`
  (§2.3: N > 0 and `device_owner` not `false`).
- **`clean`:** the server's field-by-field cleaning. It keeps booleans and `null` (QA L3: a reading Android refused stays
  `null`, as on the phone), drops unknown keys and other types. A non-object becomes `null`.

The wording that only the PWA uses (warning lines, ⓘ texts) lives in `S/i18n/{nb,en}.json`. The phone's row
summaries live in `strings.xml`. Only `names` are shared, and each side's test checks its own strings against them.

## 4. The report, the intake, the policy

### 4.1 The status report's `setup`

The phone sends only readings no other field carries. The rest stay in the fields that already carry them
(`notification_listener_enabled`, `battery_optimization_exempt`, `write_settings_granted`, `airplane_mode`,
`call_state`, `notification_channels`, design 110's `map_links`, `installed_apps`), so nothing is reported twice.
`mobile_data.enabled` (design 94/95) stays as it is for Mobilnett's own lines; `setup.mobile_data` is the user's switch
alone (§2.2 row 5).

```json
"setup": {"device_owner": true, "mobile_data": true, "location": true, "background_data": true,
          "full_screen_intent": true, "fingerprint": false}
```

- Built by a new `L/setup/SetupReadings.kt` (`report(context)`, each read in `runCatching`, `null` when unreadable)
  and added to `MdmSyncWorker.statusReport`.
- `mobile_data` is sent only with a default data subscription, `fingerprint` only with a sensor. `full_screen_intent`
  is always sent; the need rule decides.
- `isDataEnabledForReason` needs `READ_PHONE_STATE` or `READ_BASIC_PHONE_STATE`. The manifest gains
  `READ_BASIC_PHONE_STATE` (a normal permission on 33+, so granted at install on 14-17), so the reading works also while
  calls are unmanaged and the DO hasn't granted the phone permissions.
- A full-report field, not a heartbeat field (QA L7): it changes rarely, and the hash covers it, as design 98 §5.1 chose
  for `airplane_mode`. `status_heartbeat.json`'s `heartbeat_fields` don't list it; `StatusReportsTest` and
  `tests::status_heartbeat` need no new row (a heartbeat expands into the stored full report, `setup` included).
- What prompts a fresh report: Settings' `onResume` after Android's page (today's pattern, the rows re-read on resume),
  and the existing broadcasts. The listener's `onListenerConnected`/`onListenerDisconnected` and
  `ACTION_RESTRICT_BACKGROUND_CHANGED` (registered at runtime) each request a sync (`SyncRunner.request(context,
  "setup")`), so the PWA clears within seconds of the parent's tap.

### 4.2 Server intake

- `S/setup_status.rs::clean(value) -> Option<String>`, called beside the other cleaners in `device_api`'s status intake.
  It is stored in a new `device_status.setup_json TEXT`.
- `intake_limits` keeps the `report` row's keys.
- Migration `0157_setup_status.sql` (QA L2: the next free number on main at 3752baaad; 0156 is design 115's `up_held`.
  Whichever of 114 and 110 merges second renumbers):
  - `ALTER TABLE device_status ADD COLUMN setup_json TEXT`;
  - `CREATE TABLE device_setup_ticks (device_id INTEGER NOT NULL REFERENCES devices(id) ON DELETE CASCADE, step TEXT
    NOT NULL, ticked_at TEXT NOT NULL, PRIMARY KEY (device_id, step))`.
  - `device_policy.setup_card_hidden` loses its reader and stays as a dead column. Migrations are kept (C1's rule).

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
   `quick_actions::setup_card`. One ⓘ by the header gives the phone's way in.
2. **"På telefonen" / "On the phone"** (`#phone`): the missing required `phone` and `server` steps, in table order. Each
   row is `id="step-<key>"`: the name and, where the state has a detail, a short value (row 8: the channels' names;
   row 14: the moved roles, `setup.role.*`). An ⓘ holds what to do on Android's page.
   - `parent_pin` is a link row to `/lock#parent-pin`.
   - `device_owner` is red, and its ⓘ says to reset and use the QR (link to `/devices/{id}/provision`).
3. **"Sjekk selv" / "Check by hand"** (`#check`): the unticked `parent` steps with `need: required`. Empty since
   revision 2 (every parent step is optional); the group stays in the template for a later required one and shows only
   when it has rows.
4. **"Valgfritt" / "Optional"** (`#optional`): the missing optional steps, quiet: `fingerprint` (phone) and the
   unticked parent steps. A parent step's row is a checkbox that saves by itself (`POST /devices/{id}/setup/ticks`,
   `step`, `on`; with JS `scroll-restore.js`, without JS a `<noscript>` Save that lands on `#step-<key>`, so a save never
   jumps to the top). Each row has an ⓘ with Android's path.
5. **Ticked** steps sit in one closed `<details>` "Avkrysset (4)" / "Ticked (4)" at the end, where they can be unticked.
6. Nothing missing: one quiet line, "Alt er gjort" / "All done". No report yet: "Venter på telefonen" / "Waiting for
   the phone".

No step can be hidden or skipped (the user's answer to ASK 4). A required step that can't be done on this phone (row
state NO_PAGE) still counts: the parent sees it, and the ⓘ says Android has no page for it.

### 5.2 The summary

- **The Attention row:** a new `attention::Kind::Setup(usize)`, level Action, title "Oppsett: N steg igjen" / "Setup: N
  steps left" (plural), target `/devices/{id}/setup`, key `setup`. It's raised by §2.3's `setup_row`, placed first
  among the Action rows, `read_from_report` true, and counts once in the Devices list's badge.
- **Device owner (QA M4):** a new `Kind::NotDeviceOwner`, key `not-device-owner`, level Alarm, placed after
  `AccessRevoked`, `read_from_report` true, title "Vibb One styrer ikke telefonen" / "Vibb One doesn't manage the phone",
  target `/devices/{id}/setup#step-device_owner`. It is raised when the latest report's `setup.device_owner` is `false`
  (absent raises nothing). While it is raised, `Setup(n)` is not (§2.3). Every other Attention kind is unchanged: each
  says a real state of the phone, and folding them is not this design's.
- **`parent_pin`** keeps today's `NoParentPin`.
- **The sub-page row:** "Oppsett" in the phone's page list before "Om telefonen". Its quiet value is "3 igjen" / "3
  left", or "Ferdig" / "Done". It isn't orange, because the Attention row already says it.
- **Gone from the summary:**
  - the Oppsett card (`device_detail.html`'s `data-setup-step` card, `SetupCard`) with "Ikke nå"; design 113 changed only
    its words (113 QA L8), and those keys go with it (§5.6);
  - design 113's `Kind::MobileDataOff` (QA H1): its kind, key `mobile-data-off`, title `device.attention.mobile_data_off`
    and `Facts::mobile_data_off`. Step 5 counts in `Setup(n)` instead, so mobile data off is said once and counts once
    in the badge. (113 already removed the summary's `#mobile-state` data-off line and the Sections row's «Mobildata
    av»; nothing else of it is left on the summary);
  - `#airplane-state`'s `role-lost` line (`airplane_mode::summary_lines`, `device.summary.airplane.role_lost`): the
    assistant step counts in `Setup(n)`. The emergency line stays. `assistant_held_before` loses its reader and goes.
- **Moved:** the un-enrolled phone's `#oppsett` flash goes to the page's `#open`.

### 5.3 "Ikke nå" goes (decided)

The user's answer to ASK 4: taken. Required steps can't be hidden; optional steps are never orange, so they need no
hide. The route `POST /devices/{id}/setup-card/hide`, its button and `device.summary.setup.not_now`/`.hidden` go. A
parent who decides against a required step (say Data Saver) keeps an orange line; that is the rule.

### 5.4 The warnings at each choice

A step that is missing and applies shows **one short orange line at the top of each section its `features` name**
(the user's call: "si ifra" at the choice that depends on it). The line is a link,
`<a class="attention attention-action" href="/devices/{id}/setup#step-<key>">` with the title and the chevron only, from
one partial, `T/partials/setup_warning.html`: no ⓘ inside the link (design 113 QA M7), the how is on Device › Oppsett.
It's fed by `setup_status::warnings(page)`, which returns the lines for the page's anchors. A section with two missing
steps shows two lines; a step warns once per page (QA M1: on Mobilnett `mobile_data` warns at `#mobile-data` only).

| Page › section | Choice there | Steps that warn |
|---|---|---|
| Mobilnett › `#mobile-data` | Mobildata: På / Bare Vibb | `mobile_data`, `data_saver` |
| Home Screen › `#quick-controls` | Flymodus (bit 64); Berøringslyder og vibrasjon (bit 16) | `assistant`, `wifi_airplane`; `write_settings` |
| Home Screen › `#layout` | the clock format; the badges | `write_settings`; `notification_access` |
| Lock Screen › `#lock-screen` | the glance | `notification_access` |
| Music › `#on-phone` | the music card | `notification_access` |
| Advanced › `#updates` | Fjern varsler (the notification filter) | `notification_access` |
| Calls › `#settings` | Calls managed | `call_filter`, `full_screen_intent` |
| Apps › `#app-push` | App push | `background`, `data_saver` |
| Apps › `#map-links` (design 110) | Kartlenker | `map_play_links`, `map_vibb_links` |
| Locate › `#location` | Location | `location` |
| Sign-in › `#logins-wifi` | Wi-Fi sign-in | `location` |

On Home `#quick-controls` and `#layout` a step that warns for two features of one section shows once.

These lines replace (QA M1; named by key and function, QA L6):
- Home: `device_pages::notification_access_note` (`device.home.no_notification_access`, `_sub`, `_fix`) and
  `device_pages::clock_format_note` (`device.home.clock_not_applied`, `_fix`);
- Home's Flymodus row: `airplane_mode::home_sub` loses its role part (`device.home.airplane_no_role`, orange since 113)
  and its Wi-Fi part (`device.home.airplane_offline`); the row keeps «Blokkert under Begrensninger» alone;
- Lock: `lock_glance::Note::NoNotificationAccess` and `NoNotificationAccessButMessages`
  (`device.lock.glance.no_access`, `_messages`, both `_sub`s);
- Advanced: `kiosk_escapes::EscapeNote::FilterWithoutAccess` (`device.advanced.escape.filter_no_access`, and its adb-only
  `_sub`);
- Calls: `calls::Finding::NoRedirection` (`device.calls.finding.no_redirection`, `_fix`);
- Mobilnett: `mobile_network::card`'s `data-off` data line (`mobile.state.data_off`, orange since 113, and its ⓘ
  `mobile.state.data_off_sub`, whose how-to becomes `setup.how.mobile_data`);
- design 110's two "Ett steg igjen på telefonen …" lines, when 110 is built.

### 5.5 About This Phone

The state rows stay; their how-to text goes (say it once). Each row for a missing step becomes a link (chevron) to
`/devices/{id}/setup#step-<key>`, with no detail text. This covers Batterioptimalisering (`device.about.battery_sub`),
Endre systeminnstillinger (`device.write_settings_steps`; `device.about.write_settings_sub` stays as About's own use,
113), Digital assistent (`device.airplane.assistant_steps`), Wi-Fi i flymodus, and Varsler.

### 5.6 Copy (checked against "Words in the UI")

Each heading or name is a word or two; each warning line says the state in a few words; the how is behind the ⓘ on
Device › Oppsett alone; nothing is said twice on a page.

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
| `map_play_links` | Play-tjenester tar kartlenkene | Play services takes the map links |
| `map_vibb_links` | Vibb One åpner ikke alle kartlenker | Vibb One doesn't open every map link |

The `mobile_data` and `assistant` lines keep design 113's words (`mobile.state.data_off`, `device.home.airplane_no_role`),
so the parent sees the same sentence after 114 as after 113.

The ⓘ texts (`setup.how.<key>`): what to do on Android's page, a few words. Where design 113 moved a step's consequence
behind the card's ⓘ, it stays first in the panel as `setup.why.<key>`, moved unchanged (`background` from
`device.summary.setup.background_sub`, `wifi_airplane` from `.wifi_sub`, `call_filter` from `.call_filter_sub`,
`assistant` from `.assistant_sub`, `default_apps` from `.default_apps_sms`, shown only when SMS moved). Android's own
labels are to be checked on the Jelly Star (Unihertz's nb strings) in the gate. adb: open question 2 (§5.7).

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
| `map_play_links` | Åpne som standard: slå av Åpne støttede linker. | Open by default: turn off Open supported links. |
| `map_vibb_links` | Åpne som standard: slå på Åpne støttede linker, og kryss av alle. | Open by default: turn on Open supported links, and tick them all. |
| `vibration` | Lyd og vibrasjon › Vibrasjon og haptikk: ringevibrasjon og varselvibrasjon på. | Sound & vibration › Vibration & haptics: ring and notification vibration on. |
| `touch_feedback` | Lyd og vibrasjon › Vibrasjon og haptikk: berøringsrespons på. | Sound & vibration › Vibration & haptics: touch feedback on. |
| `lock_clock` | Skjerm › Låseskjerm: dobbeltlinjet klokke av. | Display › Lock screen: double-line clock off. |
| `media_on_lock` | Lyd og vibrasjon › Medier: vis medier på låseskjermen av. | Sound & vibration › Media: show media on lock screen off. |
| `message_banners` | Appen › Varsler › meldingskanalen › Vis på skjermen. | The app › Notifications › the message channel › Pop on screen. |

Plus:
- `setup.summary` ("Oppsett: {n} steg igjen" / "Setup: {n} steps left", with a singular), `setup.page.title` ("Oppsett"
  / "Setup"), `setup.value.left` ("{n} igjen" / "{n} left"), `setup.value.done` ("Ferdig" / "Done");
- the group headers ("På telefonen" / "On the phone", "Sjekk selv" / "Check by hand", "Valgfritt" / "Optional"),
  `setup.ticked` ("Avkrysset ({n})" / "Ticked ({n})"), `setup.all_done` ("Alt er gjort" / "All done"),
  `setup.no_report` ("Venter på telefonen" / "Waiting for the phone");
- `device.attention.not_device_owner` ("Vibb One styrer ikke telefonen" / "Vibb One doesn't manage the phone");
- `setup.step.<key>`, the names of §2.2.

Gone with the card and the folds: every `device.summary.setup.*` key but the roles, which move to `setup.role.*`
(`home`, `dialer`, `sms`, `call_screening`, `call_redirection`, `browser`); `device.summary.setup.footer` (replaced by
`setup.how.phone`); `device.attention.mobile_data_off`; `device.summary.airplane.role_lost`; the keys §5.4 and §5.5 name.

### 5.7 Open question 2: the adb lines leave the PWA

The texts this design replaces carry an adb shortcut:
- `device.about.battery_sub` and `device.write_settings_steps`;
- `device.home.no_notification_access_fix` (the words audit's KEEP: "the adb command in `no_notification_access_fix`,
  behind the (i)"; design 113 kept it behind the ⓘ);
- `device.calls.finding.no_redirection_fix`;
- `device.advanced.escape.filter_no_access_sub` (QA M1: adb only, "Grant it with adb before setting the phone up").

**Recommended:** none move to the new ⓘ texts. The on-phone path is the way ("no adb beyond the QR"), and the words
rule asks for few words. The adb lines stay in the runbook `docs/setup/android-settings.md` and in
`docs/testing/emulator.md`. The cost: a parent with USB debugging on loses a copy-paste in the PWA, and the audit's
KEEP is overruled. The answer is asked (Questions for the user, 2):
- **yes**: the five keys go with the texts that hold them;
- **no**: each becomes the last line of its step's ⓘ on Device › Oppsett (`setup.adb.<key>` for `background`,
  `write_settings`, `notification_access`, `call_filter`; the filter's case is `notification_access`'s), unchanged.

## 6. The phone

### 6.1 The section "Fullfør oppsett"

- **Where:** a `PreferenceCategory` `settings_setup` first in `res/xml/preferences.xml`. It holds one row per step key
  and is hidden while empty.
- **Moved in:** today's rows Bakgrunnstilkobling, Endre systeminnstillinger, Varsler fra Vibb One, Digital assistent,
  Wi-Fi i flymodus, Samtalefilter, Fingeravtrykk and Medier på låseskjermen. Their keys and click handlers stay
  (`SettingsFragmentLauncher`).
- **New rows:** `device_owner` (not tappable), `location`, `notification_access`, `mobile_data`, `data_saver`,
  `full_screen_intent`, `default_apps`, `map_play_links`, `map_vibb_links` (no reading until design 110),
  `vibration`, `touch_feedback`, `lock_clock` and `message_banners`.
- **Visibility:** a row shows only while its step is missing, by `setupMissing(...)` (§6.3): required steps first, then
  optional ones, in table order.
  - Today's own rules (`assistantRowShown`, `screeningRowShown`, the notification row's `notificationsRowText != null`,
    the fingerprint row's own conditions) are folded into the contract's `when` plus the reading.
  - The media row is no longer "always shown": it shows until ticked in the PWA.
- **Row mechanics unchanged:** `systemPageIntent` (L/server/BatteryExemption.kt), `systemPageAccess` with its states
  (L/server/WriteSettingsAccess.kt), `ownPageOpening`, `openFirstPage`, and the assistant's and the call filter's pick
  window.
- **New page lists:** each new step gets a `pageIntents(context)` beside the existing ones, built from §2.2's actions
  and made explicit by `systemPageIntent`. `SetupStepsTest` checks their actions against the contract's `phone_page`.
- **Order on screen:** the section is first. The pause switch "Sett alle begrensninger på pause" stays where it is,
  because a row outside the window says "Sett på pause først".

Row summaries (`setup_row_*`, the generic states replacing each row's own set):

| state | nb | en |
|---|---|---|
| CAN_OPEN | the step's short how (as §5.6's ⓘ, e.g. "Slå på Tillat varseltilgang.") | the same in en |
| PAUSE_FIRST | Sett på pause først | Pause first |
| WAIT | Et øyeblikk … | One moment … |
| NO_PAGE | Finnes ikke på denne telefonen | Not on this phone |
| `device_owner` | Sett opp telefonen på nytt med QR-koden | Set the phone up again with the QR code |
| a `parent` step, CAN_OPEN | the how, then "Kryss av i foreldreappen etterpå." | the how, then "Tick it in the parent app afterwards." |

This replaces the long summaries such as `settings_assistant_open` (three sentences) and `settings_mdm_write_settings_*`.
The kid never sees these rows (the section is behind the parent PIN). No adb on the phone either way.

### 6.2 The notification

`AssistantSetup.refreshNotice` keeps everything:
- the channel `launcher:setup` (LOW, secret on the lock screen);
- the title "Fullfør oppsettet av Vibb One";
- `setup_notice_steps` "N steg igjen";
- it shows only while the window is open.

The changes:
- `stepsLeft` becomes `SetupSteps.count(readings, inputs, ticked)`, the shared rule, so its N equals the PWA's for the
  same report.
- Its tap scrolls to `settings_setup` instead of Bakgrunnstilkobling.
- It is refreshed after every apply (as today) and on Settings' resume.

### 6.3 Code

- **`L/setup/SetupSteps.kt` (pure, new):**
  - the `SetupStep` enum: the contract's keys, need, `read_by`, `when`, summary, level, features, page actions, string
    ids;
  - `SetupInputs` (mask, clock format, calls managed, android lock, map links on, map app installed);
  - `setupMissing(inputs, readings, ticked): List<SetupStep>`, `setupCount(...)` and `setupRow(...)`.
  - It absorbs `setupStepsLeft` and `screeningRowShown` (AssistantSetupPlan.kt); `assistantRowShown` folds into
    `when: airplane`.
- **`L/setup/SetupReadings.kt` (new):**
  - `readings(context): Map<String, Boolean?>`, the live reads behind §2.2's "Reading" column, off the main thread;
  - `report(context)` for §4.1;
  - `mapAppInstalled(context)` from the same package list the report's `installed_apps` uses.
  - It reuses `BadgeStore.accessGranted`, `BatteryExemption.exempt`, `WriteSettingsAccess.granted`,
    `AirplaneRuntime.roleHeld`/`wifiKept`/`defaultAppsChanged`, `CallSystem.*RoleHeld`, `FingerprintEnrol.enrolled`,
    and `MobileNetworkRuntime.dataTelephony` (for `isDataEnabledForReason`); design 110's report fields once built.
  - It adds `NotificationChannels`' orange rule (`channelsOrange`, design 107 `pages` rows; today only the server runs
    them).
- **`PolicyResponse.setup`**, decoded with a default of `null` meaning none ticked. The default is a fail-safe, not
  compat.

## 7. What changes elsewhere on the phone

### 7.1 Vibb's own location permission, always (decided)

- Today:
  - `LocateCommands.currentLocation` grants FINE/COARSE/BACKGROUND only when the policy isn't Off;
  - the zones grant them while watched (`ZoneFences`);
  - the Wi-Fi config path grants FINE and puts its state back (`WindowRelock.withWifiConfigs`).
- With Locate Off, Android refuses Wi-Fi scan results (`startScan … has no location permission`,
  docs/testing/2026-11-17-r2r3-swipe-gate.md), and the sign-in window's network name is `null`. On Android 13-17
  `getScanResults` still needs FINE location (NEARBY_WIFI_DEVICES doesn't cover it).
- **New:** `AppEnforcer.apply` self-grants FINE and COARSE on every apply while device owner, beside
  `applyCallPermissions`, through `QuickControls.selfGrantPermission` (written only when the state differs).
- **BACKGROUND stays as today**: it follows Locate and the zones (the user's answer to ASK 6). It isn't needed for the
  Wi-Fi page or the sign-in window, which run in the foreground.
- **Privacy:** "Off" still never touches LocationManager and never takes a fix. The rule in
  docs/notes/launcher/time-rules.md changes from "Off never touches LocationManager or the permissions" to "Off never
  touches LocationManager; FINE/COARSE are always Vibb's (the Wi-Fi page and the sign-in window need them); BACKGROUND
  follows Locate". The system Location switch is unchanged: the Wi-Fi page turns it on and off itself
  (`WifiNetworksActivity`).
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

`BadgeStore`'s comment loses "adb at provisioning". adb stays in the runbook only.

### 7.3 Android 14-17

- Android 17 never hides or suspends Settings (docs/notes/launcher/hardening.md). `systemPageIntent`'s `plannedOff`
  check keeps rows at "Sett på pause først" there too.
- `canUseFullScreenIntent` and its page are API 34, so there's no gate. `isDataEnabledForReason` and
  `ACTION_APP_OPEN_BY_DEFAULT_SETTINGS` are API 31, `READ_BASIC_PHONE_STATE` API 33: no gate.
- `ACTION_MANAGE_DEFAULT_APPS_SETTINGS`, `ACTION_IGNORE_BACKGROUND_DATA_RESTRICTIONS_SETTINGS` and
  `ACTION_NETWORK_OPERATOR_SETTINGS` are long public, but their targets differ by OEM. That's why the gate checks the
  Jelly Star's names.
- The Internet panel (`Settings.Panel.ACTION_INTERNET_CONNECTIVITY`) hands over to SystemUI's dialog on 12+, so it is
  only the fallback for row 5.
- Android 17 is owed: there's no emulator for it here. Check it on the first 17 phone.

## 8. What folds in, moves or goes

Named by function and key, not line (QA L6: the copy round moves both).

| Today | Where | Becomes |
|---|---|---|
| `setupStepsLeft`, `screeningRowShown`, `assistantRowShown` | `L/assistant/AssistantSetupPlan.kt` | `SetupSteps.setupMissing/setupCount` (§6.3) |
| `AssistantSetup.stepsLeft` | `L/assistant/AssistantSetup.kt` | `SetupSteps.count` over `SetupReadings` |
| the notification's tap target | `SettingsFragmentLauncher` | scrolls to `settings_setup` |
| the scattered grant rows | `preferences.xml` | the category `settings_setup`, rows driven by `setupMissing` |
| `updateAssistantRows`'s visibility rules | `SettingsFragmentLauncher` | the contract's `when` + readings; the click handlers stay |
| the location grants tied to Locate | `LocateCommands.currentLocation`, `ZoneFences`, `WindowRelock.withWifiConfigs` | FINE/COARSE always in `AppEnforcer.apply` (§7.1); BACKGROUND unchanged |
| `airplane_mode::SetupStep`, `setup_steps`, `setup_key`, `SetupCard` | `S/airplane_mode.rs` | `S/setup_status.rs` (`missing`, `page`, `warnings`, `clean`); the airplane module keeps its report and lines |
| the Oppsett card (113: words only) | `device_detail.html`; `devices.rs`'s `SetupCard::of` | the Attention row `Setup(n)` + Device › Oppsett |
| "Ikke nå" | `device_pages`' setup-card hide handler and route; `settings_log`'s entry; `device_policy.setup_card_hidden` | gone (§5.3); a new logged route `/devices/{id}/setup/ticks` |
| `back=setup` → `/devices/{id}#oppsett` | `handlers/window_relock.rs` | → `/devices/{id}/setup#open`; the QR page's "Åpne for oppsett nå" lands there |
| Attention `NoNotificationAccess`, `NotificationsOff`, `Calls(NoRedirection)` | `S/attention.rs`; `calls.rs`' call problems | folded into `Setup(n)` |
| Attention `MobileDataOff` (design 113) | `S/attention.rs` (`Kind::MobileDataOff`, `Facts::mobile_data_off`, `facts_of`); `device.attention.mobile_data_off` | folded into `Setup(n)` (QA H1): the kind, its key, its title and the fact go |
| the summary's `role-lost` line | `airplane_mode::summary_lines`, `assistant_held_before`; `device.summary.airplane.role_lost` | folded into `Setup(n)` |
| Attention `NoParentPin` | `S/attention.rs` | kept (row 1 is `own_row`) |
| - | - | new `Kind::NotDeviceOwner` (§5.2) |
| Mobilnett's orange `data-off` line (113) | `mobile_network::card`; `mobile.state.data_off`, `_sub` | §5.4 warning line at `#mobile-data` |
| Home's notification-access and clock notes | `device_pages::notification_access_note`, `clock_format_note`; `device_home.html` | §5.4 warning lines |
| Home's Flymodus sub-line role and Wi-Fi parts | `airplane_mode::home_sub`; `device.home.airplane_no_role`, `airplane_offline` | §5.4 warning lines; the row keeps "Blokkert" |
| the glance's notification-access notes | `lock_glance::Note::NoNotificationAccess*`; `device.lock.glance.no_access*` | §5.4 warning line |
| the escape filter's notification-access note | `kiosk_escapes::EscapeNote::FilterWithoutAccess`; `device.advanced.escape.filter_no_access*` | §5.4 warning line (feature `notification_filter`) |
| Calls' `NoRedirection` finding + adb fix | `calls::Finding::NoRedirection` | §5.4 warning line |
| About's how-to details | `device_pages`' About rows (battery, write settings, assistant, Wi-Fi, notifications) | state rows that link to the step (§5.5) |
| design 110's "Kartlenker" step rows and its two "Ett steg igjen" lines | design 110 (not built) | rows 15-16 and their warning lines (§2.6) |
| `docs/setup/android-settings.md` | | stays the reference; each section names its step key; no per-phone checklist elsewhere |
| i18n | the keys named in §5.2-§5.7 | replaced by `setup.*` (§5.6); dead keys removed |

## 9. Build order

**Before:**
- **C1 merged, all of it** (branch `c1-compat-out`; steps 1-5 are there on 2026-11-18, the capability gates are steps
  12-16). C1 removes every claim and gate; for this design that means:
  - `airplane_mode::report` reads `airplane_mode_json` without the `airplane_mode_v1` check (C1 inventory A-16);
  - the Home page's Flymodus row and About's assistant/Wi-Fi rows show for every phone (A-16);
  - `takes_commands` (`window_relock_v1`, `quick_actions`) goes (A-14);
  - 113's `MobileDataOff` fact loses its `mobile_data_v1` condition (it goes here anyway);
  - `airplane_mode.json`'s `capability` row and every `older_server` row go;
  - design 98 §6.4 ("An older launcher, an older server") is void.

  This design reads no `*_v1` claim, adds no "older launcher" branch for a report without `setup` and no "older server"
  branch for a policy without `setup` (§2.4). It needs no capability of its own; the kept mechanism stays unused by it.
  Without C1's gate steps, `setup_steps` would still hide the assistant/Wi-Fi steps from a launcher without
  `airplane_mode_v1`, so 114 waits for them rather than adding one more flag.
- **Design 113 merged** (`copy-round-113`, built and code-QA'd, waiting for the user's look). 114 builds on its state:
  the `MobileDataOff` kind it folds, the card words it deletes, the orange Mobilnett and Flymodus lines it replaces, and
  its link-card rule (no ⓘ inside an `<a>`).
- **Design 115** is merged: migration 0156 is taken (§4.2); its app-push hold needs nothing from this design, and
  Data Saver's cut of other apps stays outside it (§2.2).
- **Both questions answered** (top of the note).

**Steps** (one branch, the contract and both sides' encode/decode in one commit, per the API rule):

1. **Contract + pure rules (S + L, one commit).**
   - `server/testdata/setup_steps.json` and its launcher copy;
   - `S/setup_status.rs` (table, `missing`, `setup_row`, `clean`) with its tests;
   - `L/setup/SetupSteps.kt` with `SetupStepsTest`;
   - `PolicyResponse.setup` and the policy's `setup`;
   - the report's `setup` (`SetupReadings.report`), `READ_BASIC_PHONE_STATE`, and the intake + migration 0157;
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
   - `Kind::Setup` and `Kind::NotDeviceOwner`, and the folds: `NoNotificationAccess`, `NotificationsOff`,
     `Calls(NoRedirection)`, `MobileDataOff`, the summary's `role-lost` line;
   - the warning partial on the eleven sections of §5.4 (`#map-links` waits for 110) and the notes it replaces;
   - About's links;
   - the card, the hide route and the dead i18n removed;
   - `back=setup`'s new target.
   - Size: M.
4. **Code QA → fix all → emulator gate (§11, after the user's OK) → merge.** PLAN's queue entry is marked built in
   the merge commit.

Design 110, built after C2, fills rows 15-16's readings and the `#map-links` section, and drops its own two step rows
and lines (§2.6).

## 10. Tests

**Shared (`setup_steps.json`), both sides compare the copies.**

**Server.**
- `setup_status::tests::the_contract` runs:
  - every `needed` row through `missing`/`count`/`setup_row`, the `map_links` rows included;
  - the `steps` table against the module's own constants (key, need, `read_by`, `when`, summary, level, features);
  - every `features` row against a known page and anchor;
  - every `names` against `setup.step.<key>` in nb.json and en.json;
  - every `clean` row (`null` kept).
- `tests::setup_status`:
  - posts `report` and checks `setup_json` stored, with a report whose readings make every step missing;
  - renders `/devices/{id}/setup` and checks the groups, the rows' `id`s and the open group;
  - posts a tick, checking the stored row, the redirect to `#step-<key>`, the `settings_log` entry, the policy's
    `setup.ticked` and the row moved into "Avkrysset";
  - unticks;
  - checks the summary's `Setup(n)` row: its title, its level, that it isn't raised at N = 0, and nothing for an
    un-enrolled phone;
  - checks mobile data off raises `Setup(1)` and no `mobile-data-off` row beside it, and counts once in the Devices
    list's badge (QA H1);
  - checks `setup.device_owner: false` raises `NotDeviceOwner` (Alarm, after `AccessRevoked`) and no `Setup` row, while
    the page still lists the steps (QA M4);
  - checks the summary has no `role-lost` line with bit 64 on and the role lost;
  - checks the `map_links` need from the stored `installed_apps_json`: switch on and Organic Maps installed, switch on
    and no map app, map app and switch off;
  - for every `features` row, renders its page with the step missing (one line linking to `/setup#step-<key>`, no ⓘ
    inside it) and done (no line); on Mobilnett `mobile_data` shows once (QA M1);
  - checks the replaced notes are gone: Home's two notes, the Flymodus row's role and Wi-Fi parts (the row keeps
    "Blokkert"), the glance's `no_access*`, the escape filter's `filter_no_access`, Calls' `NoRedirection`, Mobilnett's
    `data-off`;
  - checks About's rows link and carry no how-to;
  - checks `back=setup` lands on `/setup#open`.
- `intake_limits` keeps `report`'s keys. `policy_json_keys_snapshot` gets `setup`. `status_heartbeat.json` and its
  tests are unchanged (QA L7: `setup` is a full-report field).
- **Removed or rewritten:** `tests/airplane_mode.rs`' hide-route test; the attention tests of the folded kinds,
  `MobileDataOff` included, rewritten for `Setup(n)`; `tests/airplane_mode.rs::the_home_form_keeps_bit_64_without_its_row`'s
  `no_role`/offline texts (113's added orange check moves to the warning line); 113's mobile-network test rows that
  assert `data-attention="mobile-data-off"` and the page's `data-off` line, now the setup line.
- `i18n::tests::every_key_is_used` covers the deleted keys; grep each after the build (113 QA L5).

**Launcher.**
- `SetupStepsTest` checks:
  - `SetupStep` (keys, order, fields) against `steps`;
  - each step's string in `values` and `values-nb` against `names`;
  - each step's `pageIntents` actions against `phone_page` (pure: the action lists live beside the builders);
  - every `needed` row through `setupMissing`/`setupCount`/`setupRow`;
  - `report` encoded exactly from a `SetupReport`;
  - `policy` decoded;
  - the copies compared.
- `NotificationChannelsTest` also runs 107's `pages` rows' `orange` through `channelsOrange`.
- `AssistantSetupTest` loses `setupStepsLeft`'s rows (moved).
- `PolicyResponseCompatTest` decodes `setup`.
- A pure `setupRowText(step, access)` is tested for every step × `SystemPageAccess`.
- `LocationPolicyTest` gets a row: Off takes no fix, and the plan's own grants include FINE/COARSE but not BACKGROUND.

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
| 2 | Notification access: `cmd notification disallow_listener …` | `Setup(n)` orange; Home `#layout`, Lock `#lock-screen`, Music `#on-phone` and Advanced `#updates` show the line. In a pause the phone row opens the listener detail page; Allow; back: the row is gone and the PWA clears within seconds. Not greyed as a restricted setting (34 and 36; record otherwise, §7.2) |
| 3 | Mobile data: `svc data disable` | Step 5 orange in `Setup(n)` and no second Attention row; Mobilnett's line once, at `#mobile-data`. Outside a pause the row says "Sett på pause først" and a tap opens nothing (`mLockTaskModeState=LOCKED`). In a pause it opens the SIM page (record if it falls back to the panel); on; cleared. Record that `svc data disable` reads as the user's reason |
| 4 | Data Saver: `cmd netpolicy set restrict-background true` | Step 6; the row opens Vibb One's Unrestricted data switch; on; cleared. Record whether lock task or the mobile-network lock stops the parent's Data Saver toggle outside a pause |
| 5 | Full-screen intent: `appops set <pkg> USE_FULL_SCREEN_INTENT deny` | Step 9 on the Calls page; the row opens Android's page; allow; an incoming call (`adb emu gsm call`) shows Vibb's call screen over the lock |
| 6 | Location with Locate Off | After an apply, `dumpsys package <pkg>` shows FINE/COARSE granted (policy-fixed) and BACKGROUND not; the Wi-Fi page lists networks; `fake-portal.py`'s window reports its SSID; no `LocationManager` request in the log, no location indicator |
| 7 | Not device owner (`compat34` only: a fresh debug install enrolled without `dpm set-device-owner`) | `setup.device_owner` false; the red `NotDeviceOwner` row; no `Setup` row; Oppsett lists the steps |
| 8 | Ticks | Tick Vibrasjon in the PWA: the policy carries it, the phone row goes, it moves to "Avkrysset"; untick brings it back under "Valgfritt"; the save stays at `#step-vibration`; N never changes (optional) |
| 9 | The notification | Open for setup: "Fullfør oppsettet av Vibb One · N steg igjen" with N equal to the PWA's; a tap goes to the PIN, then Settings at the section; Lås igjen: gone |
| 10 | Design 98's rows inside the section | Digital assistent, Wi-Fi i flymodus and Samtalefilter work as in §6am (regression); the summary has no `role-lost` line |
| 11 | Standardapper | After a call-filter window that moved the browser, the row names it and opens Android's Default apps page |
| 12 | The eye (`jelly36` only) | Screenshots of Device › Oppsett, the summary row and the phone section, in nb and en, for the user |

Not run: Android 17 (no emulator), the Jelly Star (owed: Unihertz's page names in §5.6, the SIM page, the
restricted-setting check), and rows 15-16 (owed with design 110's gate).

## 12. The ASK list

1. **`vibration` required or optional** (§2.2 row 18). ANSWERED: optional.
2. **Message banners: tick now, detect later** (§2.5). OPEN: Questions for the user, 1. Recommended: tick now.
3. **Design 110's two map-link steps become rows in this table** (§2.6). ANSWERED: yes, only while Organic Maps or
   CoMaps is installed (rows 15-16, `when: map_links`).
4. **"Ikke nå" goes**, so required steps can't be hidden (§5.3). ANSWERED: yes.
5. **The adb shortcuts leave the PWA's texts** and stay in the runbook (§5.7). OPEN: Questions for the user, 2.
   Recommended: yes.
6. **BACKGROUND location stays tied to Locate/zones**, while FINE/COARSE are always granted (§7.1). ANSWERED: tied.

## The user's answers (2026-11-17, 21:50 CET)

- ASK 1 (vibration on calls and messages): OPTIONAL, not required.
- ASK 3 (design 110's two map-link steps in this list): TAKEN, with the user's condition - the two steps apply only
  while Organic Maps or CoMaps is installed on the phone (the user: "Helst bør denne greia bare spørres om, om man
  faktisk har installert comaps eller organic maps"): `when: map_app_installed` (read from the report's installed apps),
  not the map-link switch alone. (A Device Owner can't make the change itself: Play services holds the links VERIFIED,
  a persistent preferred activity loses to it, and changing another app's link state needs a permission it lacks -
  design 110 §6.2.)
- ASK 4 ("Ikke nå" goes, no hiding of a required step): TAKEN.
- ASK 6 (background location): follows Locate and zones, as today; fine and coarse always granted.
- ASK 2 and ASK 5: not asked; the recommendations stand unless the user says otherwise (message banners ticked now and
  detected later; the adb shortcuts leave the PWA's texts and stay in the runbooks). Revision 2 (design QA M3): both are
  asked before the build, at the top of the note.

## Revision 2 (2026-11-18)

The design QA (`docs/review/113-114-design-qa.md`, 114 part) and the user's answers, applied; checked against `main` at
3752baaad and `origin/copy-round-113` at ae483f41f.

| Finding | What changed |
|---|---|
| **H1** `MobileDataOff` counted twice beside `Setup(n)` | §5.2 and §8 fold design 113's `Kind::MobileDataOff` into `Setup(n)` (the kind, its key, its title and `Facts::mobile_data_off` go); §9 step 3 and §10 test "no `mobile-data-off` row beside `Setup(1)`, one badge count". §5.2's old "`#mobile-state`'s line goes" dropped: 113 already removed it and the Sections row's «Mobildata av» |
| **M1** lines said twice, fold list incomplete | §5.4's "These lines replace" and §8 now also fold: `airplane_mode::home_sub`'s role and Wi-Fi parts (the row keeps "Blokkert"), the glance's `no_access*` notes, `kiosk_escapes`' `FilterWithoutAccess` (new feature `notification_filter`, Advanced › `#updates`; its adb-only text joins §5.7's list), Mobilnett's 113 `data-off` line; `mobile_data` warns once on Mobilnett (`#mobile-data`; the `roaming` feature is gone). Also folded on the same ground: the summary's `role-lost` line (inventory B7) |
| **M2** the user's answers not in the table and contract | Row `vibration` optional; the "apart from `vibration`" sentence gone; the sample's `vibration` is `optional` and `needed[0]` recounted (6 → 5); rows 15-16 `map_play_links`/`map_vibb_links` with `when: map_links`, a report-derived input stated in §2.3 (the server's `installed_apps_json`, the phone's reported package list), `needed` rows for it; §2.6 decided; §12 marks each answer |
| **M3** ASK 2 and ASK 5 not put to the user | "Questions for the user" at the top, each with one plain sentence and the recommended answer; §2.5 and §5.7 name the audit's KEEP conflict and both outcomes; the build waits for the answers (§9) |
| **M4** `device_owner`'s alarm row has no kind | `Kind::NotDeviceOwner`, key `not-device-owner`, Alarm, after `AccessRevoked`, `read_from_report` true; while raised, `Setup(n)` isn't (§2.3 `setup_row`, a contract field); the page still lists every step; other kinds unchanged |
| **L1** no `features` column | Added to §2.2; the contract sample follows |
| **L2** stale migration number | `0157_setup_status.sql` (0155 `catalog_app_logo`, 0156 design 115's `up_held`); 110 renumbers at its build |
| **L3** `clean` loses `null` | `clean` keeps `null`; a `clean` row says so |
| **L4** `isDataEnabled` false for carrier/thermal reasons | Row 5 reads a new `setup.mobile_data` = `isDataEnabledForReason(DATA_ENABLED_REASON_USER)`, with `READ_BASIC_PHONE_STATE` in the manifest; `mobile_data.enabled` stays for Mobilnett; the `sim` field is gone (`mobile_data` absent without a data subscription) |
| **L5** calls Off requires the call steps | Confirmed and stated in §2.3: under Off Vibb holds the roles and an emergency callback rings through its in-call screen |
| **L6** line references go stale with 113 | §5.4, §5.5, §8 and §9 name functions and keys; the header says which commits the rest point at |
| **L7** heartbeat | §4.1 and §10: a full-report field, `status_heartbeat.json` unchanged |

Also in revision 2:
- **The user's answers:** vibration optional (row 18, `#optional`, the `#check` group empty for now); required steps never
  hidden (§2.1, §5.1, §5.3); background location follows Locate (§1 goal 7, §7.1); the map-link steps only while
  Organic Maps or CoMaps is installed (§2.3 `map_links`, which also needs 110's switch on - the user's "not the switch
  alone", and with the switch off step 15 would send short links nowhere, 110 §6.3); per-feature warnings link to
  Device › Oppsett (§1 goal 3, §5.4).
- **Design 113 (not merged):** 114 is built on it (§9). Its `MobileDataOff` is folded (H1); its card's words go with the
  card and the step consequences it moved behind the card's ⓘ move to the step's ⓘ as `setup.why.<key>` (§5.6); its
  orange `data-off` and `airplane_no_role` lines become setup warning lines with the same words; its link-card rule (no
  ⓘ inside an `<a>`, 113 QA M7) is the warning partial's.
- **Design 115 (merged):** migration 0156 taken; Data Saver's cut of other apps (115 §9) is named as outside this design
  (§2.2).
- **C1:** 114 depends on no old capability gate and adds none (§2.4, §9); it waits for C1's gate steps (12-16), which
  remove `airplane_mode_v1` and `window_relock_v1`.
- **Rows renumbered:** the map-link rows are 15-16, so fingerprint and the parent steps are 17-22.
