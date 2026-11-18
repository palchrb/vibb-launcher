# Design 114: the phone setup status

Status: design, 2026-11-17; revision 2, 2026-11-18 (design QA `docs/review/113-114-design-qa.md` applied, the user's
answers of 2026-11-17 folded in); **revision 3, 2026-11-18**: the guided setup on the phone (the user's call of
2026-11-18, approved before the build) and the answers to revision 2's two questions; **its design QA applied the
same day** (`docs/review/114-r3-design-qa.md`: H1, M1-M6, L1-L11; table "Revision 3 QA applied" at the end);
**revision 3b, 2026-11-18**: the user's two decisions on revision 3 and the answers to its two questions (below).
Built after consolidation round C1 (its gate steps 12-16) and on top of the PWA copy round (design 113, merged at f13b79cbd). Inputs: the inventory
`docs/review/2026-11-17-setup-status-inventory.md` (its tables A1-A7, the overlaps B1-B20, the gaps C1-C12), PLAN.md's
entry "A setup checklist for the one-time steps on a phone" with the user's calls of 2026-11-17 and 2026-11-18, design
98 §3.3/§6.2 (`docs/design/98-airplane-mode-assistant.md`), design 107, design 110 (`docs/design/110-map-links.md`, not
built), design 113, design 115 (merged), `docs/setup/android-settings.md`, the root `CLAUDE.md` "Words in the UI".
Paths: `L/` = `launcher/app/src/main/java/com/kidslauncher/mdm/`, `S/` = `server/src/`, `T/` = `server/templates/`.
Revision 3's references are on `main` at f13b79cbd; revision 2 named functions and keys, not lines (QA L6).

## Revision 3b (2026-11-18): the user's two decisions

Checked against `main` at f01b5a70e.
- **The Parent PIN before the QR (new §5.8).** The Connect page (`/devices/{id}/provision`) makes no QR and no setup
  code for a phone without its Parent PIN (the PIN is per phone: `device_policy.override_pin_hash`); it shows one
  orange line linking to Lock Screen & PINs, and the save lands back on the QR. So no phone enrols without a Parent PIN,
  and the hold's ceiling can never leave a phone locked with no PIN on it: the lockout the user raised ("vi må passe på
  at vi ikke låser oss ute om man ikke har fått satt foreldre pin før de 4 timene går ut"). The flow's `parent_pin`
  screen goes, with no fallback (§6.2.3). Where it went: §1, §2.2 row 1, §2.3, §5.1, §5.2, §5.6, §5.8, §6.1.3, §6.1.5,
  §6.2.2, §6.2.3, §6.6, §8, §9, §10, §11 (row 2, the setup, row 16).
- **No "Apper" screen.** Apps are installed from the PWA (the user: "primært er jo planen å installere det meste via pwa
  da"); every app is open in the hold, so the chat app the PWA installed can be logged into there. QA M5's "Apper" screen
  goes: the summary below, question 2, §6.1.2, §6.2.2, §6.2.4, §6.5, §6.6 (`setup_flow_apps*`), §7.3, §9, §10, §11
  (row 9d), §12, the QA table's M5 entry.
- **Both questions answered** (the user: "ok, høres bra ut!"): the ceiling is 4 hours, and Home leads to the flow, as
  guidance.
- **`NotDeviceOwner`'s place:** after `EasyParentPin`, since the branch `choices-2` (approved) removes `AccessRevoked`
  (§5.2, §10, the revision 2 table's M4).

## Revision 3 in short (what changed against revision 2)

- **The guided setup (new, §6.1-§6.2).** A fresh enrolment starts a **setup hold**: the restrictions pause, held until
  the parent taps "Ferdig" on the phone, ended also by the PWA's "Lås igjen", by a restart and by a ceiling. While it
  holds, Home leads to the setup flow (guidance, not a lock): one step a screen, each with its direct way to Android's
  page, optional steps skippable, "Ferdig" turning the restrictions on. The flow can be opened again later. (Revision
  3b: apps come from the PWA, not from a flow screen.)
- **Parent Settings (§6.3):** revision 2's "Fullfør oppsett" category with one row per step is gone. Parent Settings'
  main screen carries one row, "Oppsett" with "3 steg igjen" (orange) or "Fullført" (quiet), which opens the flow. The
  step rows that sit there today move into the flow.
- **The notification (§6.4)** counts the same list as before and opens the flow, not Settings.
- **The pause's longest length (§6.1.4):** `restrictions_pause.json` gains `setup_hold_max_ms`; the server's
  `paused_at` keeps a held phone open up to it. The report's `setup` gains `hold_until_ms`.
- **Parent-ticked steps can be ticked on the phone (§4.4):** the flow's "Gjort" posts the tick
  (`POST /api/devices/setup/ticks`), so the parent who just did it on the phone doesn't tick it again in the PWA.
- **The PWA while the phone is in setup (§5.2):** the pause's red Attention row reads "I oppsett, uten begrensninger"
  and leads to Device › Oppsett's open group, which says until when and offers "Lås igjen"; `Setup(n)` waits until the
  hold ends. The QR page's "Åpne for oppsett nå" goes: the phone opens itself.
- **Revision 2's questions answered:** message banners are ticked by hand (the PWA or the flow's "Gjort"), detection
  later (§2.5); the adb commands leave the PWA, kept only in `docs/setup/` and `docs/testing/` for the emulator (§5.7).
- The map steps' condition (design 110's switch on **and** Organic Maps or CoMaps installed) is confirmed by the user
  (§2.3, unchanged).

## Questions for the user (answered 2026-11-18)

Two choices with a cost the parent would see. Both answered by the user: "ok, høres bra ut!". Everything is decided.

1. **How long a setup hold may last when "Ferdig" is never tapped** (§6.1.3). The hold ends on "Ferdig", on the PWA's
   "Lås igjen" and on a restart; the ceiling is the backstop for a phone that is put away or handed to the child
   unfinished. The costs aren't equal: too short costs one tap (the PWA's "Åpne for oppsett" for 2 hours more, or the
   parent PIN on the phone); too long leaves an unfinished phone open, with no filter, no time rules and every app (12
   hours would cover a night: set up at 20:00, handed over unfinished, open until 08:00 on the way to school). Android's
   setup, the flow and the app installs fit in 1-2 hours. Recommended: 4 hours (twice the ordinary pause).
   **ANSWERED: 4 hours.** The Parent PIN is set before the QR (revision 3b, §5.8), so the ceiling never leaves a phone
   locked without one.
2. **Home leads to the setup flow while the hold lasts** (§6.2.1). Pressing Home brings the setup screens back instead
   of the child's Home until "Ferdig". It steers the parent through the steps and makes an unfinished phone obvious, but
   it is guidance, not a lock: Android's pages, the shade and Recents still reach other apps, and the PWA's red row and
   the ceiling are what keep the hold safe. Apps are installed from the PWA, and every app is open in the hold, so the
   chat app and the map app can be logged into there (revision 3b: no app screen in the flow). Recommended: yes.
   **ANSWERED: yes, as guidance.**

## 1. Goals

1. **One list, grown from design 98's two.** The phone's `setupStepsLeft` (L/assistant/AssistantSetupPlan.kt, 5
   kinds) and the PWA's `airplane_mode::setup_steps` (6 kinds) are replaced by one step table in one shared contract,
   `server/testdata/setup_steps.json`. No third list.
2. **Three layers.** What the Device Owner can do it does and checks (and shows only when it failed). What needs a
   person is done in the phone's guided setup, one step a screen, with a direct path to Android's own page per step.
   What no app can read is ticked once by the parent, in the flow ("Gjort") or in the PWA.
3. **Guided setup after the QR (revision 3).** A fresh phone comes up open, in the setup flow, and the parent's
   "Ferdig" turns the restrictions on. The flow can be run again from one row in parent Settings.
4. **The PWA:** a page Device › Oppsett, one orange "Oppsett: N steg igjen" on the phone's summary while required steps
   remain (nothing when they're done), and one short orange line at each choice that a missing step stops, linking to
   the step on Device › Oppsett (the user's call, 2026-11-17).
5. **The phone's parent Settings stays plain:** one row, "Oppsett", not a list of steps (the user, 2026-11-18). The
   design-98 notification counts the same list.
6. **No adb beyond the QR.** Every step a person does has an on-phone path in the flow. That includes notification
   access, which today has only an adb path (L/badges/BadgeStore.kt; `device.home.no_notification_access_fix`). The
   PWA carries no adb command (the user's answer to ASK 5).
7. **Report what is unreported today:** Device Owner itself, Android's own mobile-data switch (the user's reason, not
   the carrier's), Data Saver, Vibb's full-screen intent, Vibb's own location permission, and the setup hold.
8. **Vibb's own fine and coarse location permission is always granted by the Device Owner.** Today the grant follows
   Locate. Background location keeps following Locate and the zones (the user's answer to ASK 6).

Non-goals: the roaming switch stays a trip step on Mobilnett. Tailnet, the Child PIN, the Home role, backup, other apps'
special accesses (design 68) and the music app keep their own pages and Attention rows (inventory B10-B17). Google
account presence stays out (inventory C12; the account is added in Android's setup wizard, before the flow). Setting the
Parent PIN on the phone stays out: the PIN is the PWA's, and the QR page asks for it first (revision 3b, §5.8), so the
flow never meets a phone without one (§6.2.3). Installing apps from the flow stays out too: apps come from the PWA's Apps
page (revision 3b).

## 2. The steps

### 2.1 Fields

Every step has:
- `key`;
- `need`: `required` (counted, orange) or `optional` (listed quietly, never counted). A required step can never be
  hidden in the PWA (the user's answer to ASK 4); it leaves the list only when done. In the flow it can be put off
  ("Senere"), which changes nothing: it is still counted (§6.2.2);
- `read_by`:
  - `phone`: the phone reports a reading;
  - `server`: the server's own data;
  - `parent`: the parent ticks it once, in the flow or in the PWA. Android won't let an app read it.
- `when`: when the step applies (§2.3);
- `summary`:
  - `count`: in the summary's N;
  - `own_row`: it keeps its own Attention row and is not counted, so the summary says it once;
- `level`: `action` (orange) or `alarm` (red);
- `features`: the PWA choices it stops (§5.4); the column of §2.2 (QA L1);
- `phone_page`: the Android actions the flow's "Åpne" opens, in order. `null` means no on-phone page;
- `names`: nb/en.

Order in the table is the order everywhere: the flow, the PWA page, the contract.

### 2.2 The table

"Reading" is what decides done/missing; "absent" means not applicable on this phone (the step is left out), `null`
means unreadable (shown nowhere, never counted). Versions: minSdk 34, so every API below exists on 14-17 without a gate
unless the row says otherwise.

| # | key | need | read_by | when | summary / level | features | Reading (done when) | The flow's "Åpne" (in the hold or a pause) | nb name / en name |
|---|---|---|---|---|---|---|---|---|---|
| 1 | `parent_pin` | required | server | always | own_row / alarm | - | a Parent PIN is stored (today's `NoParentPin` fact); the phone reads it as its cached policy's PIN hash | none: the PWA's `/devices/{id}/lock#parent-pin`, set before the QR (revision 3b, §5.8). Never a flow screen (§6.2.3) | Foreldre-PIN / Parent PIN |
| 2 | `device_owner` | required | phone | always | own_row / alarm | - | new `setup.device_owner`: `DevicePolicyManager.isDeviceOwnerApp(packageName)` | none: the phone is reset and set up with the QR (`T/provision_qr.html`). Never a flow screen: without Device Owner there is no hold and no flow | Enhetseier / Device owner |
| 3 | `location` | required | phone | always | count / action | `locate`, `wifi_signin` | new `setup.location`: `checkSelfPermission(ACCESS_FINE_LOCATION) == GRANTED`. The DO grants it at every apply (§7.1), so missing means the grant failed | `ACTION_APPLICATION_DETAILS_SETTINGS` `package:<ours>` (Permissions › Location) | Posisjonstilgang / Location access |
| 4 | `notification_access` | required | phone | always | count / action | `badges`, `glance`, `music_card`, `notification_filter` | `notification_listener_enabled` (`MdmSyncWorker.statusReport`, `BadgeStore.accessGranted`) | `ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS` with `EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME` = `<ours>/com.kidslauncher.mdm.badges.BadgeListenerService` (API 30), then `ACTION_NOTIFICATION_LISTENER_SETTINGS` | Varseltilgang / Notification access |
| 5 | `mobile_data` | required | phone | always | count / action | `mobile_choices` | new `setup.mobile_data`: `TelephonyManager.isDataEnabledForReason(DATA_ENABLED_REASON_USER)` on the default data subscription (API 31; QA L4: the user's own switch, not a carrier, thermal or policy cut). Absent with no default data subscription (`MobileNetworkRuntime.dataTelephony` null) | `ACTION_NETWORK_OPERATOR_SETTINGS` (the SIM's Mobile network page, "Mobildata"), then `Settings.Panel.ACTION_INTERNET_CONNECTIVITY`; the settings lock is lifted in the hold and a pause (`mobileSettingsLocked`, L/server/MobileNetwork.kt) | Mobildata / Mobile data |
| 6 | `data_saver` | required | phone | always | count / action | `mobile_choices`, `app_push` | new `setup.data_saver` (QA L5: named after its step): `ConnectivityManager.getRestrictBackgroundStatus() != RESTRICT_BACKGROUND_STATUS_ENABLED` (Data Saver on and Vibb not exempt). No permission needed | `ACTION_IGNORE_BACKGROUND_DATA_RESTRICTIONS_SETTINGS` `package:<ours>` (Vibb One's "Unrestricted data" switch) | Datasparing / Data Saver |
| 7 | `background` | required | phone | always | count / action | `app_push` | `battery_optimization_exempt` | the existing dialog, `BatteryExemption.dialogIntent` | Bakgrunnstilkobling / Background connection |
| 8 | `vibb_notifications` | required | phone | always | count / action | - | design 107's orange rule over `notification_channels` (its `pages` rows' `orange`) | the existing `NotificationChannels.pageIntents` | Varsler fra Vibb One / Vibb One notifications |
| 9 | `full_screen_intent` | required | phone | `calls_managed` | count / action | `calls` | new `setup.full_screen_intent`: `NotificationManager.canUseFullScreenIntent()` (API 34) | `ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT` `package:<ours>` (API 34) | Fullskjermvarsler / Full-screen notifications |
| 10 | `write_settings` | required | phone | `write_settings_used` | count / action | `touch`, `clock_format` | `write_settings_granted` | the existing `WriteSettingsAccess.pageIntents` | Endre systeminnstillinger / Modify system settings |
| 11 | `assistant` | required | phone | `airplane` | count / action | `airplane` | `airplane_mode.assistant` | the existing window + `ACTION_VOICE_INPUT_SETTINGS` (`AssistantSetup`) | Digital assistent / Digital assistant |
| 12 | `wifi_airplane` | required | phone | `airplane_role` | count / action | `airplane` | `airplane_mode.wifi_kept != null` (design 98 §6.2: an OEM "turns off" counts as done) | the existing Wi-Fi step (`AssistantSetup`) | Wi-Fi i flymodus / Wi-Fi in airplane mode |
| 13 | `call_filter` | required | phone | `calls_managed` | count / action | `calls` | `call_state.screening_role_held` and `redirection_role_held` both true | the existing role requests (`AssistantSetup`) | Samtalefilter / Call filter |
| 14 | `default_apps` | required | phone | always | count / action | - | `airplane_mode.default_apps_changed` empty, **read live** (QA H1, §2.2.1): each stored entry leaves once its role is back | `ACTION_MANAGE_DEFAULT_APPS_SETTINGS`, opened inside design 98's pick window without a new snapshot (§2.2.1; outside it `DISALLOW_CONFIG_DEFAULT_APPS` greys the page) | Standardapper / Default apps |
| 15 | `map_play_links` | required | phone | `map_links` | count / action | `map_links` | design 110's `map_links.play_services_links == false` | `ACTION_APP_OPEN_BY_DEFAULT_SETTINGS` `package:com.google.android.gms` (API 31) | Lenker i Play-tjenester / Play services' links |
| 16 | `map_vibb_links` | required | phone | `map_links` | count / action | `map_links` | design 110's `map_links.vibb_hosts.selected >= selectable` | `ACTION_APP_OPEN_BY_DEFAULT_SETTINGS` `package:<ours>` (API 31) | Vibb Ones lenker / Vibb One's links |
| 17 | `fingerprint` | optional | phone | `android_lock` | count / action (never counted: optional) | - | new `setup.fingerprint`: `FingerprintEnrol.enrolled`; absent without a sensor | the existing `FingerprintEnrol.pageIntents` | Fingeravtrykk / Fingerprint |
| 18 | `vibration` | optional | parent | always | count / action (never counted: optional) | - | ticked | `ACTION_SOUND_SETTINGS` | Vibrasjon / Vibration |
| 19 | `touch_feedback` | optional | parent | always | count / action | - | ticked | `ACTION_SOUND_SETTINGS` | Vibrasjon ved trykk / Touch feedback |
| 20 | `lock_clock` | optional | parent | `android_lock` | count / action | - | ticked | `ACTION_DISPLAY_SETTINGS` (Display › Lock screen; no public lock-screen action - checked in the gate) | Klokke på én linje / One-line clock |
| 21 | `media_on_lock` | optional | parent | `android_lock` | count / action | - | ticked | the existing `MediaLockScreen.pageIntents` (`ACTION_SOUND_SETTINGS`) | Medier på låseskjermen / Media on the lock screen |
| 22 | `message_banners` | optional | parent | always | count / action | - | ticked (decided: by hand now, detected later, §2.5) | `ACTION_MANAGE_APPLICATIONS_SETTINGS` (the app list; the channel is the app's) | Bannere for meldinger / Message banners |

Rows 1-16 are required and rows 17-22 optional (the user made `vibration` optional, 2026-11-17), so no optional row
ever turns the summary orange. A fresh phone with nothing done shows at most 12 counted steps (rows 3-14; 1 and 2 have
their own rows, 15-16 apply only with design 110's switch and a map app).

Rows 15-16 are design 110's two one-time steps (110 §6.2), here instead of 110's own "Kartlenker" group (the user's
answer to ASK 3). Their readings come from 110's `map_links` report: until 110 is built they are absent and the steps
never show. 110's third row, "Gi Google Play-tjenester lenkene tilbake" (§6.3), stays 110's own: it isn't a setup step.

#### 2.2.1 Row 14 read live (QA H1)

While calls or SMS are managed and Vibb holds the role, `DISALLOW_CONFIG_DEFAULT_APPS` is set (`lockDefaultAppsFor`,
L/sms/SmsRole.kt), and no pause lifts it; only design 98's pick window does (`AssistantSetup.assistantPickOpen`). And
`default_apps_changed` was written only when a pick window opened or closed, so a role put back stayed listed. As a
required step that can't be hidden, that would keep the summary orange for good. So:
- **The reading is live:** `AssistantSetup.defaultAppsChanged(context)` checks the stored entries at each read (the
  report's `airplane_mode.default_apps_changed`, the flow, the count) and drops an entry once it is back: `home` when
  Vibb holds `ROLE_HOME`; `browser` when the `https` resolver equals the snapshot's; `dialer` and `sms` when
  `TelecomManager.getDefaultDialerPackage` / `Telephony.Sms.getDefaultSmsPackage` equal the snapshot's. A dropped entry
  is removed from the store, so the server reads the same list.
- **`call_screening` and `call_redirection` leave row 14**: row 13 reads and counts the same roles.
- **"Åpne" runs inside design 98's pick window**, opened without a new snapshot (`openPickWindow(keepSnapshot = true)`),
  so the page is usable, the comparison stays against the original holders, and opening the page doesn't clear the
  step by itself. The window closes as today (its deadline, or the parent's return).

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

`map_links` asks for both because the user's condition (the steps only while a map app is installed, confirmed
2026-11-18: "OK at kartsteg bare vises om det er installert") is not the switch alone, and the switch is needed too:
with it off, step 15 would send short links to a browser or nowhere (110 §6.3).

A step is **missing** when it applies, its reading is present and is `false`, or (for `parent` steps) it isn't
ticked. **N** is the number of missing `required` steps with `summary: count`. The phone and the PWA compute the same
N from the same report. Of the `own_row` steps, the phone reads `parent_pin` from its cached policy (the PIN hash) and
`device_owner` is its own reading (QA L11); neither is ever in N, and neither is a flow screen (§6.2.2, §6.2.3).

**The summary row** `Setup(n)` is raised when N > 0 and the `device_owner` reading is not `false` (QA M4): a phone that
isn't device owner is reset and set up with the QR, which redoes every step, so its red row stands alone. The page and
the phone's flow still list every missing step then; nothing is hidden. (The server also holds `Setup(n)` back while
the phone is in the setup hold, §5.2; that is the summary's presentation, not the shared rule.)

### 2.4 Old phones and fresh phones

- **No capability, and no old capability gate.** C1 removes every claim and gate and keeps the mechanism (the report's
  `capabilities` field, its column and the server's one helper). 114 needs no claim of its own: a phone whose report
  has no `setup` yet (a launcher from before 114, or before its first report on the new build) has those readings
  absent, so those steps aren't shown, and the rest come from fields it already sends; the policy's `setup` is always
  sent, and a launcher that doesn't know it ignores it. 114 reads no `*_v1` claim anywhere (§9).
- **Phones already set up** get no hold and no flow by themselves: the hold starts only at a fresh enrolment (§6.1.1).
  Their remaining steps are in the Settings row and the PWA. On the user's phones no `parent` step is orange: all of
  them are optional. They sit in "Valgfritt" until ticked.
- **A launcher from before 114 on a phone being set up** behaves as today (no hold; the PWA's "Åpne for oppsett" opens
  the 2-hour pause). The QR page always offers the release build of the day, so this happens only with a stale APK.

### 2.5 Message banners: ticked by hand (decided)

The user's answer to ASK 2 (2026-11-18): "det er helt ok, dette er jo samme som på en vanlig telefon". Row 22 is a
`parent` step: the flow's "Gjort" or the PWA's tick. A later round could detect it (inventory C8: the listener already
reads each post's `Ranking.importance`, L/badges/GlanceReader.kt) and make it a phone-read step per app ("Bannere:
Element X", opening `ACTION_APP_NOTIFICATION_SETTINGS` for that package); detection needs a post first, so it can't help
on a fresh phone, and it adds a per-app list to the contract. Not in this design.

### 2.6 Design 110's two steps (decided)

The user's answer to ASK 3: taken, with the condition that the steps apply only while Organic Maps or CoMaps is
installed. They are rows 15-16 (§2.2) with `when: map_links` (§2.3). What changes in 110 when it is built:
- its PIN-gated "Kartlenker" group keeps only the §6.3 row; its two step rows are this table's rows, and on the phone
  they are flow screens;
- its Apps group's two orange "Ett steg igjen på telefonen …" lines become 114's warning lines at `#map-links` (§5.4);
  its other lines (`missing`, `hidden`, the lookup line, "Klar") stay 110's;
- its report keeps `play_services_links` and `vibb_hosts`, which these rows read;
- its migration renumbers (QA L2: 0155 is `catalog_app_logo` on main).

110's note is revised when it is built (after C2); this note's contract carries rows 15-16 from the start, with no
reading until then.

## 3. The contracts

### 3.1 `server/testdata/setup_steps.json`

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
  "report": {"device_owner": true, "mobile_data": true, "location": true, "data_saver": false,
             "full_screen_intent": true, "fingerprint": null, "hold_until_ms": 1763496000000,
             "hold_left_ms": 12600000},
  "report_no_hold": {"device_owner": true, "mobile_data": true, "location": true, "data_saver": true,
                     "full_screen_intent": true},
  "policy": {"ticked": ["vibration", "media_on_lock"]},
  "tick": {
    "request": {"step": "touch_feedback"},
    "answers": [
      {"name": "ticked", "body": {"step": "touch_feedback"}, "status": 204},
      {"name": "already ticked", "body": {"step": "vibration"}, "status": 204},
      {"name": "not a parent step", "body": {"step": "location"}, "status": 400,
       "answer": {"error": "not_a_parent_step"}},
      {"name": "unknown key", "body": {"step": "nope"}, "status": 400, "answer": {"error": "not_a_parent_step"}}
    ],
    "phone": [
      {"status": 204, "action": "done"}, {"status": 400, "action": "done"}, {"status": 401, "action": "retry"},
      {"status": 403, "action": "retry"}, {"status": 413, "action": "done"}, {"status": 500, "action": "retry"},
      {"status": null, "action": "retry"}
    ]
  },
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
    {"name": "calls Off: full_screen_intent and call_filter apply", "...": "..."},
    {"name": "no Parent PIN: parent_pin missing and alarm, not counted", "...": "..."},
    {"name": "a moved role put back: default_apps done", "...": "..."}
  ],
  "clean": [
    {"in": {"device_owner": true, "location": "yes", "extra": 1}, "out": {"device_owner": true}},
    {"in": {"fingerprint": null, "mobile_data": false}, "out": {"fingerprint": null, "mobile_data": false}},
    {"in": {"device_owner": true, "hold_until_ms": 1763496000000, "hold_left_ms": 600000},
     "out": {"device_owner": true, "hold_until_ms": 1763496000000, "hold_left_ms": 600000}},
    {"in": {"hold_until_ms": -5, "hold_left_ms": -1}, "out": {}},
    {"in": {"hold_until_ms": "soon"}, "out": {}},
    {"in": [], "out": null}
  ]
}
```

- **`steps`:** the table in §2.2, every field. The order is the display order.
- **`features`:** where each feature's warning line goes (§5.4).
- **`report`, `report_no_hold`:** the exact `setup` object of a status report (§4.1), held and not.
- **`policy`:** the policy's `setup` (§4.3).
- **`tick`:** the phone's tick (§4.4): the exact request body, each answer's status and body, and the phone's action
  for each status (`done` drops it from the pending set, `retry` keeps it; `null` is no answer; 401/403 retry, since a
  re-enrolment clears the set with the other enrolment state).
- **`needed`:** the shared rows. `inputs` are the need rule's inputs (§2.3), each side mapping its own sources to them
  (`map_app_installed` from the installed packages). `readings` is a flat map from step key to `true`/`false`/`null`,
  with absent keys absent. Each side first maps its sources (§2.2's "Reading" column) to these maps with its own code,
  then runs the one rule: `missing` in table order, `count`, `alarm` (an `own_row` alarm step missing) and `setup_row`
  (§2.3: N > 0 and `device_owner` not `false`).
- **`clean`:** the server's field-by-field cleaning. It keeps booleans and `null` for the readings (QA L3: a reading
  Android refused stays `null`, as on the phone), keeps `hold_until_ms` and `hold_left_ms` only as non-negative
  integers, and drops
  unknown keys and other types. A non-object becomes `null`.

The wording that only the PWA uses (warning lines, ⓘ texts) lives in `S/i18n/{nb,en}.json`. The phone's flow words live
in `strings.xml`. Only `names` are shared, and each side's test checks its own strings against them.

### 3.2 `server/testdata/restrictions_pause.json` gains the hold's ceiling

```json
{
  "pause_max_ms": 7200000,
  "setup_hold_max_ms": 14400000
}
```

`setup_hold_max_ms` is 4 hours (question 1, the design QA's view; the number changes here and nowhere else). The server's
`models::paused_at_tests::the_longest_pause_is_the_shared_contracts` checks `SETUP_HOLD_MAX_MS` beside
`RESTRICTIONS_PAUSE_MAX_MS`, and the launcher's `RestrictionsPauseTest` checks `SETUP_HOLD_DURATION_MS`; both compare
the copies, as today.

## 4. The report, the intake, the policy

### 4.1 The status report's `setup`

The phone sends only readings no other field carries. The rest stay in the fields that already carry them
(`notification_listener_enabled`, `battery_optimization_exempt`, `write_settings_granted`, `airplane_mode`,
`call_state`, `notification_channels`, design 110's `map_links`, `installed_apps`), so nothing is reported twice.
`mobile_data.enabled` (design 94/95) stays as it is for Mobilnett's own lines; `setup.mobile_data` is the user's switch
alone (§2.2 row 5).

```json
"setup": {"device_owner": true, "mobile_data": true, "location": true, "data_saver": true,
          "full_screen_intent": true, "fingerprint": false, "hold_until_ms": 1763496000000,
          "hold_left_ms": 12600000}
```

- Built by a new `L/setup/SetupReadings.kt` (`report(context)`, each read in `runCatching`, `null` when unreadable)
  and added to `MdmSyncWorker.statusReport`.
- `mobile_data` is sent only with a default data subscription, `fingerprint` only with a sensor. `full_screen_intent`
  is always sent; the need rule decides.
- `hold_until_ms` and `hold_left_ms` are sent only while the setup hold lasts: its wall-clock end and the time left by
  the elapsed clock at the report (§6.1; QA L6: the server needs no trust in the phone's wall clock, §6.1.4). The
  report's `restrictions_paused` is `true` then, as for any pause.
- `isDataEnabledForReason` needs `READ_PHONE_STATE` or `READ_BASIC_PHONE_STATE`. The manifest gains
  `READ_BASIC_PHONE_STATE` (a normal permission on 33+, so granted at install on 14-17), so the reading works also while
  calls are unmanaged and the DO hasn't granted the phone permissions.
- A full-report field, not a heartbeat field (QA L7): it changes rarely, and the hash covers it, as design 98 §5.1 chose
  for `airplane_mode`. The hold's start and end change the hash, so each sends a full report. `status_heartbeat.json`'s
  `heartbeat_fields` don't list it; `StatusReportsTest` and `tests::status_heartbeat` need no new row (a heartbeat
  expands into the stored full report, `setup` included).
- What prompts a fresh report: the flow's `onResume` after Android's page, a "Gjort", "Ferdig", and the existing
  broadcasts. The listener's `onListenerConnected`/`onListenerDisconnected` and `ACTION_RESTRICT_BACKGROUND_CHANGED`
  (registered at runtime) each request a sync (`SyncRunner.request(context, "setup")`), so the PWA clears within
  seconds of the parent's tap.

### 4.2 Server intake

- `S/setup_status.rs::clean(value) -> Option<String>`, called beside the other cleaners in `device_api`'s status intake.
  It is stored in a new `device_status.setup_json TEXT`, and the hold's end in `device_status.setup_hold_until_ms
  INTEGER`: `min(hold_until_ms, reported_at + hold_left_ms)` (read by `paused_at`, §6.1.4, without parsing JSON). A
  report without the hold leaves the column `NULL` (each report is a new row).
- `intake_limits` keeps the `report` row's keys.
- Migration `0157_setup_status.sql` (QA L2: the next free number on main at f13b79cbd; 0156 is design 115's `up_held`.
  Whichever of 114 and 110 merges second renumbers):
  - `ALTER TABLE device_status ADD COLUMN setup_json TEXT`;
  - `ALTER TABLE device_status ADD COLUMN setup_hold_until_ms INTEGER`;
  - `CREATE TABLE device_setup_ticks (device_id INTEGER NOT NULL REFERENCES devices(id) ON DELETE CASCADE, step TEXT
    NOT NULL, ticked_at TEXT NOT NULL, by_phone INTEGER NOT NULL DEFAULT 0, PRIMARY KEY (device_id, step))`.
  - `device_policy.setup_card_hidden` loses its reader and stays as a dead column. Migrations are kept (C1's rule).

### 4.3 The policy's `setup`

- A new top-level `setup: {"ticked": [keys]}`, always sent. With it the phone leaves ticked steps out of its flow and
  its count.
- Pinned by `policy_json_keys_snapshot` and `PolicyResponseCompatTest`.
- A tick's save (the PWA's or the phone's) sends the usual SSE nudge.

### 4.4 The phone's tick: `POST /api/devices/setup/ticks` (new)

The flow's "Gjort" on a `parent` step (§6.2.2).
- Device auth, body `{"step": "<key>"}` (the contract's `tick.request`), with a `DefaultBodyLimit` of 256 bytes like
  its neighbours in `S/device_routes.rs` (QA L7).
- `204` when the key is a `parent` step of the table: `INSERT OR IGNORE` with `by_phone = 1` (a step already ticked
  stays as it is, still `204`). `400 {"error": "not_a_parent_step"}` for anything else. The phone never unticks; the PWA
  does (§5.1).
- The SSE nudge follows, so the policy's `setup.ticked` carries it at the next sync.
- One `settings_log` entry, as the PWA's tick, with the phone as its actor: "Vibrasjon avkrysset på telefonen" /
  "Vibration ticked on the phone".
- On the phone: the key goes into a small pending set (`SetupTicks`, prefs) at the tap and the row leaves the flow at
  once; the set is posted at the tap and again at each sync until an answer says `done` (the contract's `tick.phone`), or
  until the policy carries the key.
- Who may tick: anything holding the phone in the flow, which is the parent in the hold or in a pause. A child who ticks
  an optional step changes nothing that counts, and the parent sees it under "Avkrysset" in the PWA and can untick it.
  Required steps are never `parent` steps (§2.2), so no tick can hide one.

## 5. The PWA

### 5.1 Device › Oppsett (`GET /devices/{id}/setup`, `T/device_setup.html`)

A sub-page like Mobilnett, with the band "Oppsett". Built by `setup_status::page(lang, policy, latest, ticks, open)`.
Top to bottom:

1. **Open group** (`#open`):
   - **In the setup hold** (`setup_hold_until_ms` in the future and `paused_at`): "I oppsett til 07:10" / "Setting up
     until 07:10" and "Lås igjen" (the existing `end_windows`). Shown whatever is missing.
   - Otherwise, while a `phone`/`parent` step is missing: the existing "Åpne for oppsett i 2 t"
     (`POST /devices/{id}/start-pause`, `back=setup`), or, while open, "Åpnet til 17:10" with "Lås igjen". This reuses
     `quick_actions::setup_card`. One ⓘ by the header gives the phone's way in (`setup.how.phone`, §5.6).
   - With nothing missing the group isn't shown; a parent who wants the phone open again (an Android page to change
     after "Ferdig") uses the quick sheet's "Åpne for oppsett", as today (QA M5). Apps need no opening: the PWA's Apps
     page installs and allows them (revision 3b).
2. **"På telefonen" / "On the phone"** (`#phone`): the missing required `phone` and `server` steps, in table order. Each
   row is `id="step-<key>"`: the name and, where the state has a detail, a short value (row 8: the channels' names;
   row 14: the moved roles, `setup.role.*`). An ⓘ holds what to do on Android's page.
   - `parent_pin` is a link row to `/devices/{id}/lock#parent-pin` (only for a PIN removed after the enrolment, §5.8).
   - `device_owner` is red, and its ⓘ says to reset and use the QR (link to `/devices/{id}/provision`).
3. **"Sjekk selv" / "Check by hand"** (`#check`): the unticked `parent` steps with `need: required`. Empty since
   revision 2 (every parent step is optional); the group stays in the template for a later required one and shows only
   when it has rows.
4. **"Valgfritt" / "Optional"** (`#optional`): the missing optional steps, quiet: `fingerprint` (phone) and the
   unticked parent steps. A parent step's row is a checkbox that saves by itself (`POST /devices/{id}/setup/ticks`,
   `step`, `on`; with JS `scroll-restore.js`, without JS a `<noscript>` Save that lands on `#step-<key>`, so a save never
   jumps to the top). Each row has an ⓘ with Android's path.
5. **Ticked** steps sit in one closed `<details>` "Avkrysset (4)" / "Ticked (4)" at the end, where they can be unticked,
   whether they were ticked here or on the phone.
6. Nothing missing: one quiet line, "Alt er gjort" / "All done". No report yet: "Venter på telefonen" / "Waiting for
   the phone".

No step can be hidden (the user's answer to ASK 4). A required step that can't be done on this phone (the flow's
NO_PAGE) still counts: the parent sees it, and the ⓘ says Android has no page for it.

### 5.2 The summary

- **The Attention row:** a new `attention::Kind::Setup(usize)`, level Action, title "Oppsett: N steg igjen" / "Setup: N
  steps left" (plural), target `/devices/{id}/setup`, key `setup`. It's raised by §2.3's `setup_row`, placed first
  among the Action rows, `read_from_report` true, and counts once in the Devices list's badge. **Not raised while the
  phone is in the setup hold**: the parent is in the flow then, and the hold's own row says setup isn't finished.
- **The setup hold (revision 3):** the existing `Kind::RestrictionsPaused` (Alarm, kept) takes the title
  `device.attention.setup_hold` "I oppsett, uten begrensninger" / "Setting up, no restrictions" and the target
  `/devices/{id}/setup#open` while the latest report's hold is on (`paused_at` and `setup_hold_until_ms` in the future).
  An ordinary pause keeps today's title and target. One row either way, one count in the badge.
- **Device owner (QA M4):** a new `Kind::NotDeviceOwner`, key `not-device-owner`, level Alarm, placed after
  `EasyParentPin` (`S/attention.rs`'s order; `AccessRevoked`, which sat between them, is removed by the branch
  `choices-2` and no longer exists), `read_from_report` true, title "Vibb One styrer ikke telefonen" / "Vibb One doesn't manage the phone",
  target `/devices/{id}/setup#step-device_owner`. It is raised when the latest report's `setup.device_owner` is `false`
  (absent raises nothing). While it is raised, `Setup(n)` is not (§2.3). Every other Attention kind is unchanged: each
  says a real state of the phone, and folding them is not this design's.
- **`parent_pin`** keeps today's `NoParentPin` (raised in the hold too: without it the phone's Settings won't open
  after "Ferdig"). Since revision 3b a phone enrols only with its PIN set (§5.8), so the row is for a PIN removed in the
  PWA later (allowed while no Child PIN is set) and for phones set up before 3b.
- **The quick sheet's** "Lås igjen" ends the hold as it ends a pause (`end_windows`, unchanged); its open line uses the
  hold's end (`quick_actions::setup_until` reads `setup_hold_until_ms` first, then today's `start_pause` answer + 2 h).
- **The sub-page row:** "Oppsett" in the phone's page list before "Om telefonen". Its quiet value is "3 igjen" / "3
  left", "I oppsett" / "Setting up" during the hold, or "Fullført" / "Complete" (QA L4: the phone's word for the same
  state; "Ferdig" is only the phone's button). It isn't orange, because the Attention row
  already says it.
- **Gone from the summary:**
  - the Oppsett card (`device_detail.html`'s `data-setup-step` card, `SetupCard`) with "Ikke nå"; design 113 changed only
    its words (113 QA L8), and those keys go with it (§5.6);
  - design 113's `Kind::MobileDataOff` (QA H1): its kind, key `mobile-data-off`, title `device.attention.mobile_data_off`
    and `Facts::mobile_data_off`. Step 5 counts in `Setup(n)` instead, so mobile data off is said once and counts once
    in the badge;
  - `#airplane-state`'s `role-lost` line (`airplane_mode::summary_lines`, `device.summary.airplane.role_lost`): the
    assistant step counts in `Setup(n)`. The emergency line stays. `assistant_held_before` loses its reader and goes.
- **Moved:** the un-enrolled phone's `#oppsett` flash goes to the page's `#open`.
- **The QR page** (`T/provision_qr.html`): the "Åpne for oppsett nå" form (`data-row="open-setup"`, design 98 D2) and
  `devices.qr.open_setup` go: a phone set up with the QR opens itself in the hold. "Ferdig" (`devices.code.done`) stays.
  The page makes no QR and no setup code without the phone's Parent PIN (revision 3b, §5.8).

### 5.3 "Ikke nå" goes (decided)

The user's answer to ASK 4: taken. Required steps can't be hidden; optional steps are never orange, so they need no
hide. The route `POST /devices/{id}/setup-card/hide`, its button and `device.summary.setup.not_now`/`.hidden` go. A
parent who decides against a required step (say Data Saver) keeps an orange line; that is the rule. (This is not a
choice the PWA offers, so "A choice the parent made is never a warning" doesn't cover it: an Android switch Vibb needs
is left off.)

### 5.4 The warnings at each choice

A step that is missing and applies shows **one short orange line at the top of each section its `features` name**
(the user's call: "si ifra" at the choice that depends on it). The line is a link,
`<a class="attention attention-action" href="/devices/{id}/setup#step-<key>">` with the title and the chevron only, from
one partial, `T/partials/setup_warning.html`: no ⓘ inside the link (design 113 QA M7), the how is on Device › Oppsett.
It's fed by `setup_status::warnings(page)`, which returns the lines for the page's anchors. A section with two missing
steps shows two lines; a step warns once per page (QA M1: on Mobilnett `mobile_data` warns at `#mobile-data` only).
The lines are said only for a choice that is on: a step applies only by its `when` (§2.3), so a feature the parent
turned off raises nothing.

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
labels are to be checked on the Jelly Star (Unihertz's nb strings) in the gate. No adb line anywhere (§5.7).

| key | nb | en |
|---|---|---|
| group header (`setup.how.phone`) | Foreldreinnstillinger › Oppsett på telefonen. | Parent settings › Setup on the phone. |
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
  / "Setup"), `setup.value.left` ("{n} igjen" / "{n} left"), `setup.value.done` ("Fullført" / "Complete"),
  `setup.value.hold` ("I oppsett" / "Setting up");
- `setup.open.hold` ("I oppsett til {at}" / "Setting up until {at}");
- `device.attention.setup_hold` ("I oppsett, uten begrensninger" / "Setting up, no restrictions");
- the group headers ("På telefonen" / "On the phone", "Sjekk selv" / "Check by hand", "Valgfritt" / "Optional"),
  `setup.ticked` ("Avkrysset ({n})" / "Ticked ({n})"), `setup.all_done` ("Alt er gjort" / "All done"),
  `setup.no_report` ("Venter på telefonen" / "Waiting for the phone");
- `device.attention.not_device_owner` ("Vibb One styrer ikke telefonen" / "Vibb One doesn't manage the phone");
- `setup.step.<key>`, the names of §2.2;
- no new key for §5.8: the Connect page's line is today's `device.summary.no_parent_pin` ("Velg en foreldre-PIN før
  oppsettet" / "Set a Parent PIN before setup"), the same words as the un-enrolled summary's line.

Gone with the card and the folds: every `device.summary.setup.*` key but the roles, which move to `setup.role.*`
(`home`, `dialer`, `sms`, `call_screening`, `call_redirection`, `browser`); `device.summary.setup.footer` (replaced by
`setup.how.phone`); `device.attention.mobile_data_off`; `device.summary.airplane.role_lost`; `devices.qr.open_setup`;
the keys §5.4, §5.5 and §5.7 name.

### 5.7 The adb lines leave the PWA (decided)

The user's answer to ASK 5 (2026-11-18): "Trengs de adb-kommandoene? planen vår er vel heller guidet setup på
telefonen som gjør det samme der hvor det trengs, og at ting helst gjøres via vibb launcher setup?" Taken: the five
texts that carry an adb shortcut go with the texts this design replaces, and none moves to the new ⓘ texts:
- `device.about.battery_sub` and `device.write_settings_steps`;
- `device.home.no_notification_access_fix` (the words audit's KEEP is overruled by the user's answer);
- `device.calls.finding.no_redirection_fix`;
- `device.advanced.escape.filter_no_access_sub` (QA M1: adb only).

The commands stay where the emulator needs them: `docs/setup/android-settings.md` (each section names its step key)
and `docs/testing/emulator.md`. `BadgeStore`'s comment loses "adb at provisioning" (§7.2).

### 5.8 The Parent PIN before the QR (revision 3b, decided)

The user (2026-11-18): "vi må passe på at vi ikke låser oss ute om man ikke har fått satt foreldre pin før de 4 timene
går ut".

**The lockout it closes.** Every way out of the hold locks the phone (§6.1.3): "Ferdig", the PWA's "Lås igjen", a
restart, the ceiling. A phone locked with no Parent PIN has no way in on the phone itself: parent Settings refuses
(`SettingsGate`'s `REFUSE_NO_PIN`), and the pause, Play install mode and the time lock's code all need the PIN. Only the
PWA could open it, and only while the phone reaches the server; a phone that doesn't (the server address wrong, the
tailnet down, a Wi-Fi it can't rejoin) would need a factory reset. With the PIN required before the QR, the PIN reaches
the phone with its first policy, the same sync that makes it managed, so a managed phone always has it, and its Settings
and pause work offline. And while the phone reaches the server, the PWA's "Åpne for oppsett" and the PIN save always
work without anyone touching the phone.

**Per phone.** The Parent PIN is each phone's own (`device_policy.override_pin_hash`, saved on that phone's Lock Screen &
PINs, `handlers::parent_pin::update_parent_pin`); nothing is global. So the rule is: **no setup code, QR or typed, is
made for a phone without its Parent PIN.**

- **The Connect page** (`GET`/`POST /devices/{id}/provision`, `handlers::provisioning::provision_form`/`provision_qr`,
  `T/provision_qr.html`; Add Phone's steps 2-3 and Advanced's "Set Up with QR Code"): with no PIN, `#qr` holds one orange
  link row, `device.summary.no_parent_pin` (`data-warning="no-parent-pin"`), to
  `/devices/{id}/lock?back=provision#parent-pin`, and nothing else; `#setup-code` isn't shown (said once). The Phone
  Setup notes above it (`#server-setup`, the launcher check) stay. With the PIN, the page is as today.
- **Server-side too, not only hidden:** `provision_qr` makes no code without the PIN and answers the page with the line
  (200, as `missing_checksum` does); `handlers::devices::regenerate_code` ("New Setup Code", posted from Connect, the
  un-enrolled summary and Advanced › Connection) makes none and redirects to `/devices/{id}/provision#qr`. Advanced ›
  Connection and the un-enrolled summary show the same line in place of the button. A recovery code for a managed phone
  whose PIN was removed needs the PIN set first: one save in the PWA, nothing on the phone.
- **The save lands back, never at the top:** the lock page passes `?back=provision` into the Parent PIN form as a hidden
  `back`; `update_parent_pin` reads it (only `provision`; any other value ignored): a saved PIN redirects to
  `/devices/{id}/provision#qr`, where the QR's form now is (the form says it worked; no flash); a refusal (not 6-10
  digits, the Child PIN) stays at `/devices/{id}/lock?back=provision&notice=<code>#parent-pin` with its flash and the
  `back` kept. `clear_pin` ignores `back`.
- **The un-enrolled summary's line** (`device_detail.html`'s `no-parent-pin`, today only while `managed_without_pin`)
  shows for an un-enrolled phone whenever its PIN is unset, linking with `?back=provision`, so the summary and Connect
  say the same.

**The flow's `parent_pin` screen goes** (§6.2.3). A PIN removed later is the parent's own act in the PWA, which says it
red (`NoParentPin`, §5.2); the flow can't set it, so a screen would only stand between the parent and the steps.

## 6. The phone

### 6.1 The setup hold

#### 6.1.1 When it starts

- **At a fresh enrolment only:** in `Enrollment.enroll` (both QR paths - Android's provisioning through
  `applyProvisioningExtras` and the in-app scanner - and Settings' typed code all come through it). Whether the phone
  is fresh is decided **at the top of `enroll`, before `DeviceToken.set`** (QA L10), so a backstop sync or the stream
  can't apply a policy between the token's commit and the check: device owner, `policy_ever_applied` false and no cached
  `kidModePolicy` (`SettingsActivity`'s own test of a fresh phone). The hold starts in the success path, before the
  "enrolled" sync request.
- Pure: `setupHoldStarts(deviceOwner, policyEverApplied)` (`L/setup/SetupHold.kt`).
- A re-enrolment of a managed phone (`ReEnrolDialog`, recovery with a new code) never starts it: `policy_ever_applied`
  never goes back (only `FirstLaunchReset` clears it, never on an enrolled phone). So the child can't make one: a new
  hold needs a factory reset (blocked while device owner, and a reset ends device owner) and the parent's one-shot QR
  or code. That is the hold's basis, in place of the parent PIN that every other pause needs (QA M6), and it is pinned:
  `ParentPinCallersTest` asserts `callers("RestrictionsPause.startHold(") == setOf("Enrollment.kt")`, beside today's
  pin of `RestrictionsPause.start(`. `RestrictionsPause`'s doc comment says so ("the parent PIN's gate, or the hold at a
  fresh enrolment").
- Today's order is kept: the first policy comes with the "enrolled" sync, the first report seeds the allowlist, and the
  second policy is managed. The difference: those applies run inside the hold, so the phone isn't pinned in the middle
  of Android's setup wizard or before the flow (today the kiosk comes on within seconds of the enrolment).

#### 6.1.2 What it is

The hold **is the restrictions pause**, started with a longer end. Everything that treats the pause treats the hold
(the releases in `AppEnforcer.apply`, the lock decision, the time rules, the mobile-network settings lock, the relock at
its end with its checks of new apps, CA certificates and proxies, the report's `restrictions_paused`); Home stays
Vibb's (`enforceDefaultHome`), and the call rules and the hardening stay in force, as in every pause.
- `RestrictionsPause.startHold(context)`, in this order:
  1. **POST_NOTIFICATIONS self-granted** (`QuickControls.fixOwnPermission`, as the relock's `regrantNotifications`)
     **before** `WindowRelock.opened` (QA M4): otherwise, on a phone with calls unmanaged, the window's "Lås igjen"
     notification is posted once before any grant and never again, and Home's first start asks with Android's dialog,
     which the flow would cover. `HomeActivity.startWork` skips `requestNotificationPermission` while Vibb is device
     owner (the DO grants it).
  2. `WindowRelock.opened(context, WindowKind.PAUSE, start)` with `BootClock.windowStart(SETUP_HOLD_DURATION_MS)`, its
     start snapshot taken with **`secure = null`** (QA M1): the adopted-window rule, "unknown: never removed". The hold
     may start before Android's setup wizard reaches its screen-lock step; with `secure = false` the relock at "Ferdig"
     would remove the PIN the parent set there (`credentialAction(secureAtStart = false, secureNow = true, ...)` →
     `REMOVE`, while the policy's `kid_keyguard` is still `own`). The PWA's own warning about a credential set in a
     window stays the guard. The reset token's early activation stays (so `set_kid_pin` works over a wizard PIN).
  3. Inside the same `lift`, before `restrictionsPaused(true)`: the flag `restrictions_paused_hold` (a new
     `LauncherPreferences` entry) written `true`. `start()` writes it `false` at the same point (QA M6), so a later
     2-hour PIN pause can never inherit the hold's length; `clear()` clears it too.
- `RestrictionsPause.durationMs()`: `SETUP_HOLD_DURATION_MS` while the flag is set, else
  `RESTRICTIONS_PAUSE_DURATION_MS`. Its users: `isActive`, `TimeRulesRuntime`'s window end, and `adoptedStartMs`
  (`maxOf` over the hold too, so a relock after an adopted hold checks the apps installed in its whole length).
  `ParentPin.windowDuration(PAUSE)` stays 2 hours: a PIN pause is never a hold.
- A `start_pause` command (the PWA's "Åpne for oppsett") while the hold lasts leaves the hold as it is and answers
  success ("in setup"): a 2-hour pause would shorten it.
- `SETUP_HOLD_DURATION_MS` = `restrictions_pause.json`'s `setup_hold_max_ms` (§3.2).
- **Notifications (QA L2):** the hold shows both of a window's notifications, as a PWA pause does: the window's ongoing
  "Åpent til … · Lås igjen" and §6.4's "Fullfør oppsettet av Vibb One". During the hold the window notification's body
  tap opens the flow; its "Lås igjen" action is unchanged.
- **Screen time (QA L8):** the parent's setup time isn't the child's. `ScreenTimeTracker` doesn't accrue while the hold
  lasts (Android's pages and every app opened in it included), and `SetupFlowActivity` joins `FREE_SCREENS`
  (L/timerules/ScreenTime.kt) for the flow run later in a pause. Android's pages opened from a re-entered flow count as
  any app in a pause does.

#### 6.1.3 How it ends (safety)

Every way out locks the phone; none keeps it open longer:
1. **"Ferdig"** in the flow: `WindowRelock.lockAgain(context, RelockTrigger.LOCK_AGAIN)` (the phone's own lock-again;
   no new trigger name), called synchronously before the flow finishes into Home (QA M2: it only takes a lock and
   clears prefs, so Home's `onResume` can't still see the hold). The child tapping it only locks the phone.
2. **The PWA's "Lås igjen"** (`end_windows`, unchanged): `lockAgain(COMMAND)`.
3. **A restart:** the boot count ends it, as every window (`timedWindowActive`; `RelockTrigger.BOOT`). Not re-armed at
   boot: the elapsed clock restarts, and the wall clock isn't locked on every phone (QA L1: `lockDateTime` keeps the
   clock locked in every pause when calls or SMS are managed, apps are managed or time rules are set, but a phone with
   none of these has it free). Fail closed, as the pause.
4. **The ceiling:** `setup_hold_max_ms` (4 hours, question 1, answered) by the elapsed clock and the wall clock, as the
   pause's two hours.

None of these leaves a phone locked without a Parent PIN: the PIN is set before the QR (revision 3b, §5.8) and arrives
with the first policy.

While it lasts, the PWA shows it red (§5.2) with "Lås igjen" beside it; those and the ceiling are the hold's safety.
Home leading to the flow (§6.2.1, question 2) steers the parent and makes an unfinished phone obvious, but it isn't a
lock (QA M5): every step's "Åpne" lands in Android's Settings, where App info › Åpne opens any app, and the shade and
Recents work, since there is no lock task in the hold.

#### 6.1.4 The server's longest pause

`DeviceStatus::paused_at` (2026-11-04) calls a paused report older than two hours over, for a phone that went silent.
For a held phone that would show the phone locked while it is open. The exception, in `models.rs`:
- with `setup_hold_until_ms` stored (§4.2: `min(hold_until_ms, reported_at + hold_left_ms)`): paused while
  `now < min(setup_hold_until_ms, reported_at + SETUP_HOLD_MAX_MS)`;
- without it: today's rule.
`SETUP_HOLD_MAX_MS` is checked against `restrictions_pause.json` (§3.2). A report that says the hold ends later than the
ceiling allows is cut by the ceiling, so a phone can't hold the PWA's "open" state longer than the phone itself can.
`hold_left_ms` comes from the elapsed clock (QA L6), so a phone whose wall clock was behind when the hold started still
shows open for as long as it is.

#### 6.1.5 A restart in the middle

- The hold ends (§6.1.3), the phone comes up managed (kiosk, suspensions, the time rules), Home is the child's Home.
- The flow's run (its position and skips, §6.2.2) goes with the process; the steps themselves are read live, so
  nothing that was done is lost.
- The PWA: the hold's row goes at the next report; `Setup(n)` shows what is left; Device › Oppsett offers "Åpne for
  oppsett i 2 t". On the phone the window brings the setup notification, whose tap opens the flow (§6.4); parent
  Settings' "Oppsett" row opens it too, behind the Parent PIN the phone has had since its first policy (§5.8).
- **A restart inside Android's setup wizard** (QA L9; a wizard that installs an update and restarts) ends the hold
  before the flow was seen: the first Home is the child's. It fails closed, which is right; the way on is the PWA's
  "Åpne for oppsett", whose window brings the notification into the flow, or, once the first policy has landed, parent
  Settings' "Oppsett" row with the Parent PIN (revision 3b). On the Jelly Star's owed list (§11).

### 6.2 The flow (`L/setup/SetupFlowActivity.kt`)

#### 6.2.1 Where it is shown

- **In the hold, Home leads to the flow** (question 2, answered: yes, as guidance):
  - **Its own task** (QA M2): `taskAffinity="${applicationId}.setup"`, `launchMode="singleTask"`, as the phone book's.
    `HomeActivity` is `singleTask` with `clearTaskOnLaunch`, so a flow inside Home's task would be cleared by every
    Home press, which is the usual way back from Android's page. Home brings the flow's task to the front instead of
    starting a new one.
  - **Where in `HomeActivity.onResume`:** after `startWork()`, `gate()` and `redirectToLockScreenIfLocked()`, in place
    of `resumeContent()`, while `RestrictionsPause.holdActive()`. So the first-launch work runs, and the flow never
    starts over a lock screen.
  - Back does nothing while held (an `OnBackPressedCallback`: on Android 16 with targetSdk 36 `onBackPressed` isn't
    called, predictive back goes through the dispatcher). The first Home after Android's setup wizard is therefore the
    flow.
  - It guides; it doesn't contain (§6.1.3).
- **From parent Settings' row** "Oppsett" (§6.3), behind Settings' PIN gate. Back finishes it, and Settings is under it.
- **From the setup notification** (§6.4), and during the hold from the window notification's body (§6.1.2), which show
  only while a window is open; no PIN then, see §6.2.4.
- Not exported; `excludeFromRecents`. When the hold ends while the flow is in front, the flow finishes and Home is the
  child's (it listens to `WindowRelock`'s window changes).

#### 6.2.2 The screens

One step a screen, in table order, over the steps that are missing and apply (§2.3), read live
(`SetupReadings.readings`) and ticked from the cached policy plus the pending ticks (§4.4). `parent_pin` and
`device_owner` are never screens (§6.2.3). A step done elsewhere in the meantime leaves the run.

**The run** - its position and its "Senere"/"Hopp over" skips - lives in a process-level holder (`SetupRun`) for the
hold's length (QA M2), not in the activity, so Home, Android's page and back again return to the same screen with the
skips kept. Re-entered from Settings, a new run starts; the holder is dropped when the window ends.

**Before the first policy** (QA M3): only the conditional rows need the policy (§2.3: rows 9-13, 15-17, 20, 21, and the
ticks). The `always` rows (3-8, 14, 18, 19, 22) run at once; the rest join the run, in table
order, when the policy lands. The Wait screen shows only when nothing is runnable, and after 30 seconds it gets two
quiet buttons, "Prøv igjen" (`SyncRunner.request`) and "Ferdig", so a phone whose first sync never lands (the server
unreachable, the tailnet down, a 5xx) can't trap the parent in the hold.

| Screen | Shows | Buttons |
|---|---|---|
| Wait (nothing runnable yet) | "Et øyeblikk …" | none for 30 s, then "Prøv igjen", "Ferdig" |
| Not open (re-entered outside a window) | the title "Oppsett" | "Sett på pause i 2 t" (the parent PIN through `ParentPin.check(PAUSE)`, as Settings' switch), then the steps |
| A required step | the name; its how (one line); "2 av 7" quietly at the top | "Åpne" (primary), "Senere" |
| An optional step read by the phone (`fingerprint`) | the same | "Åpne", "Hopp over" |
| A `parent` step | the same | "Åpne", "Gjort" (ticks it, §4.4), "Hopp over" |
| A step whose page is missing on this phone (NO_PAGE) | the name; "Finnes ikke på denne telefonen" | "Senere" / "Hopp over" |
| The end | "Klar" when N = 0, else "{n} steg igjen" with the left required steps as rows (a tap goes back to that step); one line "Ferdig slår på begrensningene." | "Ferdig" |

- **No app screen** (revision 3b; QA M5's "Apper" screen superseded): apps are installed from the PWA's Apps page
  (`/devices/{id}/apps`), which allows them, and every app is open in the hold, so the chat app can be logged into
  there and Organic Maps/CoMaps, once installed, brings rows 15-16 into the run (they join it live, as any step that
  starts to apply). Row 22's page has nothing to set until the chat app has posted, which is why it is last. An app
  installed some other way in the hold meets the relock's check as today (`relockBlockDecision`).
- "Åpne" opens the step's `phone_page` with today's mechanics: `systemPageIntent` (L/server/BatteryExemption.kt),
  `systemPageAccess` and its states (L/server/WriteSettingsAccess.kt), `ownPageOpening`, `openFirstPage`, and design 98's
  windows for the assistant, Wi-Fi in airplane mode, the call filter and the default apps (`AssistantSetup`, with their
  deadlines; row 14 without a new snapshot, §2.2.1). The states map to the screen: CAN_OPEN enables "Åpne"; WAIT shows
  "Et øyeblikk …"; NO_PAGE the row above; PAUSE_FIRST can't happen inside a window and outside one the flow starts at
  "Not open".
- **Back from Android's page** (`onResume`, by Back or Home): the step is read again; done moves to the next screen,
  not done stays (no extra words: the line already says what to do). The restricted-setting line (§7.2) is added to row
  4's how only if the gate finds the switch greyed.
- **"Senere" and "Hopp over"** move on and last the run; the step is still missing, a required one still counted (the
  PWA's orange and the Settings row say so). Nothing can trap the parent: every screen has a way on, and "Ferdig" is
  always reachable.
- **"Ferdig"** ends the window if one is open (the hold or a pause; §6.1.3) and closes the flow. Entered from Settings
  without a window, it only closes.
- The look is the parent's, in the launcher's calm style (memory "Launcher look"), Nunito, the Settings screens'
  colours, insets handled (edge-to-edge is enforced on 15+ at targetSdk 36).

#### 6.2.3 The Parent PIN in the flow (revision 3b: no screen)

Row 1 is never a flow screen, and there is no fallback screen. The QR page needs the phone's Parent PIN before it makes
a code (§5.8), so a phone enrolled after 3b has the PIN from its first policy, and phones set up before 3b get no hold
(§2.4). A PIN removed in the PWA later is said red there (`NoParentPin`, §5.2); the flow can't set it (a non-goal, §1),
so a waiting screen would only stand between the parent and the steps. Revision 3's screen ("Sett den i
foreldreappen.", moving on when the policy carried the hash) goes with its string.

#### 6.2.4 Who may open it

The flow opens Android's pages only for Vibb's own grants, and only inside a window (outside one
every "Åpne" is unavailable and the flow starts at "Not open", whose button asks for the parent PIN). Inside a window
the phone is open anyway. So the flow itself needs no PIN in the hold or from the notification; from Settings it sits
behind Settings' gate. Its only lasting acts are a tick of an optional step (§4.4) and "Ferdig", which locks.

### 6.3 Parent Settings: one row

- **Where:** one `Preference` `settings_setup`, first in `res/xml/preferences.xml`, title "Oppsett" / "Setup".
- **Summary:** "{n} steg igjen" / "{n} steps left" in the orange text colour while N > 0 (the shared rule's count, §2.3);
  "Fullført" / "Complete" quietly when N = 0 (optional steps never change it). A tap opens the flow.
- **Moved out of the main screen into the flow:** today's rows Bakgrunnstilkobling (`settings_mdm_background_connection`),
  Endre systeminnstillinger (`settings_mdm_write_settings`), Varsler fra Vibb One (`settings_mdm_notifications`),
  Digital assistent (`settings_mdm_assistant`), Wi-Fi i flymodus (`settings_mdm_wifi_apm`), Samtalefilter
  (`settings_mdm_call_filter`), Fingeravtrykk (`settings_mdm_fingerprint`) and Medier på låseskjermen
  (`settings_mdm_media_lock`). Their click handlers move with them (`SettingsFragmentLauncher` → the flow's step
  screens); their long summaries (`settings_assistant_open`, `settings_mdm_write_settings_*` and the rest) go.
- **Stay:** the pause switch "Sett alle begrensninger på pause", Play install mode, app push (`settings_mdm_unified_push`),
  the server rows, the app access category. A step lost later (a role taken back) shows as "1 steg igjen" on this row.

### 6.4 The notification

`AssistantSetup.refreshNotice` keeps:
- the channel `launcher:setup` (LOW, secret on the lock screen);
- the title "Fullfør oppsettet av Vibb One";
- `setup_notice_steps` "N steg igjen";
- it shows only while a window is open (the hold included).

The changes:
- `stepsLeft` becomes `SetupSteps.count(readings, inputs, ticked)`, the shared rule, so its N equals the PWA's for the
  same report.
- Its tap opens the flow (§6.2.4), no longer the PIN and Settings (`EXTRA_SETTINGS_SECTION` goes).
- It is refreshed after every apply (as today), on the flow's resume and after a tick.

### 6.5 Code

- **`L/setup/SetupSteps.kt` (pure, new):**
  - the `SetupStep` enum: the contract's keys, need, `read_by`, `when`, summary, level, features, page actions, string
    ids;
  - `SetupInputs` (mask, clock format, calls managed, android lock, map links on, map app installed);
  - `setupMissing(inputs, readings, ticked): List<SetupStep>`, `setupCount(...)` and `setupRow(...)`;
  - `flowScreens(missing, skipped, windowOpen, policyKnown)`: the run's screens in order (the table of §6.2.2: the
    `always` rows before the first policy, the Wait screen only with nothing runnable, no `parent_pin` or
    `device_owner` screen), and `flowButtons(step)`;
  - it absorbs `setupStepsLeft` and `screeningRowShown` (AssistantSetupPlan.kt); `assistantRowShown` folds into
    `when: airplane`.
- **`L/setup/SetupReadings.kt` (new):**
  - `readings(context): Map<String, Boolean?>`, the live reads behind §2.2's "Reading" column, off the main thread;
  - `report(context)` for §4.1 (with `hold_until_ms` from `RestrictionsPause`);
  - `mapAppInstalled(context)` from the same package list the report's `installed_apps` uses.
  - It reuses `BadgeStore.accessGranted`, `BatteryExemption.exempt`, `WriteSettingsAccess.granted`,
    `AirplaneRuntime.roleHeld`/`wifiKept`/`defaultAppsChanged`, `CallSystem.*RoleHeld`, `FingerprintEnrol.enrolled`,
    and `MobileNetworkRuntime.dataTelephony` (for `isDataEnabledForReason`); design 110's report fields once built.
  - It adds `NotificationChannels`' orange rule (`channelsOrange`, design 107 `pages` rows; today only the server runs
    them).
- **`L/setup/SetupHold.kt` (pure, new):** `setupHoldStarts`; the hold's start and duration live in
  `RestrictionsPause` (§6.1.2).
- **`L/setup/SetupTicks.kt` (new):** the pending set and its post (§4.4), flushed by `performMdmSync` before the policy
  fetch; pure `tickAction(status)` from the contract's `tick.phone`.
- **`L/setup/SetupFlowActivity.kt` (new):** the screens; `res/layout/activity_setup_flow.xml`; its own task (§6.2.1).
- **`L/setup/SetupRun.kt` (new):** the process-level holder of the run's position and skips (§6.2.2).
- **`AssistantSetup.defaultAppsChanged`** read live and `openPickWindow(keepSnapshot)` (§2.2.1).
- **`PolicyResponse.setup`**, decoded with a default of `null` meaning none ticked. The default is a fail-safe, not
  compat.

### 6.6 Copy on the phone (`strings.xml`, nb + en)

Few words: a name, one line, the buttons.

| key | nb | en |
|---|---|---|
| `settings_setup_title` | Oppsett | Setup |
| `settings_setup_left` (plural) | {n} steg igjen | {n} steps left |
| `settings_setup_complete` | Fullført | Complete |
| `setup_flow_progress` | {i} av {n} | {i} of {n} |
| `setup_flow_open` | Åpne | Open |
| `setup_flow_later` | Senere | Later |
| `setup_flow_skip` | Hopp over | Skip |
| `setup_flow_did_it` | Gjort | Done |
| `setup_flow_finish` | Ferdig | Finish |
| `setup_flow_wait` | Et øyeblikk … | One moment … |
| `setup_flow_retry` | Prøv igjen | Try again |
| `setup_flow_no_page` | Finnes ikke på denne telefonen | Not on this phone |
| `setup_flow_pause` | Sett på pause i 2 t | Pause for 2 h |
| `setup_flow_ready` | Klar | Ready |
| `setup_flow_finish_line` | Ferdig slår på begrensningene. | Finish turns the restrictions on. |
| `setup_how_<key>` | §5.6's ⓘ text for the key | the same in en |
| `setup_step_<key>` | §2.2's names (checked against `names`) | the same |

"Ferdig slår på begrensningene." is the one line that explains: it says what the button does, which can't be undone on
the phone without the parent PIN, so it is said once, on the screen where it is pressed. The kid never sees these
screens in a finished setup.

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
  `applyCallPermissions`, through `QuickControls.selfGrantPermission` (written only when the state differs). In the
  hold too, so step 3 is normally done before the flow's first screen.
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
  - If the gate finds the switch greyed, the step's how gets one more line: "Appinfo › ⋮ › Tillat begrensede
    innstillinger" / "App info › ⋮ › Allow restricted settings".
  - That App info page is reachable by the same `systemPageIntent` (`ACTION_APPLICATION_DETAILS_SETTINGS`).

`BadgeStore`'s comment loses "adb at provisioning". adb stays in the runbook only.

### 7.3 Android 14-17

- **The flow's intents** are the table's: `canUseFullScreenIntent` and `ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT` are
  API 34, so there's no gate. `isDataEnabledForReason` and `ACTION_APP_OPEN_BY_DEFAULT_SETTINGS` are API 31,
  `READ_BASIC_PHONE_STATE` API 33: no gate. `ACTION_MANAGE_DEFAULT_APPS_SETTINGS`,
  `ACTION_IGNORE_BACKGROUND_DATA_RESTRICTIONS_SETTINGS` and `ACTION_NETWORK_OPERATOR_SETTINGS` are long public, but
  their targets differ by OEM. That's why the gate checks the Jelly Star's names. The role requests (assistant, call
  screening and redirection) are `RoleManager.createRequestRoleIntent` on 14-17 alike.
- **The Internet panel** (`Settings.Panel.ACTION_INTERNET_CONNECTIVITY`) hands over to SystemUI's dialog on 12+, so it
  is only the fallback for row 5.
- **Starting the flow:** from Home's `onResume` (a foreground start; the flow's own task brought to the front) and from
  a notification tap (a user tap), so Android 14's and 15's background-activity-start limits don't apply; no start from a receiver or the enrolment.
- **Back:** Android 16 at targetSdk 36 runs predictive back and no longer calls `onBackPressed`; the hold's no-op Back is
  an `OnBackPressedCallback`, which works on 14-17 alike.
- **Insets:** edge-to-edge is enforced on 15+ at targetSdk 35+; the flow pads by `WindowInsets` as the launcher's other
  screens do. On 14 the same code is a no-op.
- **The hold's clocks:** `Settings.Global.BOOT_COUNT` and `elapsedRealtime` as the pause, on 14-17 alike.
- **POST_NOTIFICATIONS** is a runtime permission on 13+, so the hold's self-grant before its window opens (§6.1.2, QA
  M4) is needed on 14-17 alike.
- **Android 17** never hides or suspends Settings (docs/notes/launcher/hardening.md): outside a window the flow starts
  at "Not open" by the window state, not by whether Settings resolves, so 17 behaves as the others. Android 17 is owed:
  there's no emulator for it here. Check it on the first 17 phone.
- **No adb beyond the QR:** every step has the flow's "Åpne" or the PWA (the Parent PIN). GrapheneOS, which has no
  6-tap QR provisioning, still needs adb to make Vibb One device owner (the QR page's ⓘ says so today); that is the QR's
  own step, not a setup step, and the in-app scanner's enrolment starts the hold as any other.

## 8. What folds in, moves or goes

Named by function and key, not line (QA L6).

| Today | Where | Becomes |
|---|---|---|
| `setupStepsLeft`, `screeningRowShown`, `assistantRowShown` | `L/assistant/AssistantSetupPlan.kt` | `SetupSteps.setupMissing/setupCount` (§6.5) |
| `AssistantSetup.stepsLeft` | `L/assistant/AssistantSetup.kt` | `SetupSteps.count` over `SetupReadings` |
| the notification's tap target (PIN + Settings, `EXTRA_SETTINGS_SECTION`) | `AssistantSetup.refreshNotice`; `SettingsFragmentLauncher` | the flow (§6.4) |
| the scattered grant rows in parent Settings | `preferences.xml`; `SettingsFragmentLauncher`'s handlers and `updateAssistantRows` | the flow's screens; one row `settings_setup` (§6.3) |
| the 2-hour pause at a fresh enrolment (none: the kiosk comes on at once) | `Enrollment.enroll` | the setup hold (§6.1) |
| `RESTRICTIONS_PAUSE_DURATION_MS` read directly | `RestrictionsPause.isActive`, `TimeRulesRuntime`, `adoptedStartMs` | `RestrictionsPause.durationMs()` (§6.1.2) |
| `default_apps_changed` written only at a pick window | `AssistantSetup` (`openPickWindow`, `afterApply`) | read live; screening and redirection out of row 14; the window opened without a new snapshot (§2.2.1) |
| Home's notification-permission dialog | `HomeActivity.startWork` → `requestNotificationPermission` | skipped while device owner; the hold's start grants it (§6.1.2) |
| `FREE_SCREENS` | `L/timerules/ScreenTime.kt` | gains `SetupFlowActivity`; no accrual in the hold (§6.1.2) |
| `paused_at`'s two-hour rule | `S/models.rs` | the hold's exception (§6.1.4) |
| the location grants tied to Locate | `LocateCommands.currentLocation`, `ZoneFences`, `WindowRelock.withWifiConfigs` | FINE/COARSE always in `AppEnforcer.apply` (§7.1); BACKGROUND unchanged |
| `airplane_mode::SetupStep`, `setup_steps`, `setup_key`, `SetupCard` | `S/airplane_mode.rs` | `S/setup_status.rs` (`missing`, `page`, `warnings`, `clean`); the airplane module keeps its report and lines |
| the Oppsett card (113: words only) | `device_detail.html`; `devices.rs`'s `SetupCard::of` | the Attention row `Setup(n)` + Device › Oppsett |
| "Ikke nå" | `device_pages`' setup-card hide handler and route; `settings_log`'s entry; `device_policy.setup_card_hidden` | gone (§5.3); a new logged route `/devices/{id}/setup/ticks`, and the phone's `/api/devices/setup/ticks` |
| `back=setup` → `/devices/{id}#oppsett` | `handlers/window_relock.rs` | → `/devices/{id}/setup#open` |
| the QR page's "Åpne for oppsett nå" | `T/provision_qr.html` (`data-row="open-setup"`); `devices.qr.open_setup` | gone: the phone opens itself (§5.2) |
| a setup code (QR or typed) made for a phone without a Parent PIN | `handlers::provisioning::provision_qr`, `handlers::devices::regenerate_code`; `T/provision_qr.html`, `device_detail.html`, `device_advanced.html` | refused; one orange line `device.summary.no_parent_pin` to the PIN (§5.8) |
| the Parent PIN save lands on Lock Screen & PINs only | `handlers::parent_pin::update_parent_pin` | `back=provision` lands a saved PIN on `/devices/{id}/provision#qr` (§5.8) |
| the un-enrolled summary's `no-parent-pin` line only while `managed_without_pin` | `device_detail.html`; `devices.rs` | whenever the PIN is unset on an un-enrolled phone (§5.8) |
| Attention `RestrictionsPaused`'s one title | `S/attention.rs` | the hold's title and target while held (§5.2) |
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
| the PWA's adb commands | the five keys of §5.7 | gone; `docs/setup/android-settings.md` and `docs/testing/emulator.md` keep them |
| design 110's "Kartlenker" step rows and its two "Ett steg igjen" lines | design 110 (not built) | rows 15-16 and their warning lines (§2.6) |
| `docs/setup/android-settings.md` | | stays the reference; each section names its step key; no per-phone checklist elsewhere |
| i18n | the keys named in §5.2-§5.7 | replaced by `setup.*` (§5.6); dead keys removed |

## 9. Build order

**Before:**
- **C1 merged, all of it** (branch `c1-compat-out`; the capability gates are its steps 12-16). C1 removes every claim
  and gate; for this design that means:
  - `airplane_mode::report` reads `airplane_mode_json` without the `airplane_mode_v1` check (C1 inventory A-16);
  - the Home page's Flymodus row and About's assistant/Wi-Fi rows show for every phone (A-16);
  - `takes_commands` (`window_relock_v1`, `quick_actions`) goes (A-14);
  - 113's `MobileDataOff` fact loses its `mobile_data_v1` condition (it goes here anyway);
  - `airplane_mode.json`'s `capability` row and every `older_server` row go;
  - design 98 §6.4 ("An older launcher, an older server") is void.

  This design reads no `*_v1` claim, adds no "older launcher" branch for a report without `setup` and no "older server"
  branch for a policy without `setup` (§2.4). It needs no capability of its own; the kept mechanism stays unused by it.
- **Design 113** is merged (f13b79cbd): 114 builds on its `MobileDataOff` kind, the card words it deletes, the orange
  Mobilnett and Flymodus lines it replaces, and its link-card rule (no ⓘ inside an `<a>`).
- **Design 115** is merged: migration 0156 is taken (§4.2); its app-push hold needs nothing from this design, and
  Data Saver's cut of other apps stays outside it (§2.2).
- **Both questions answered** (top of the note, 2026-11-18: "ok, høres bra ut!"): the ceiling is 4 hours, Home leads to
  the flow.

**Steps** (one branch, the contracts and both sides' encode/decode in one commit, per the API rule):

1. **Contracts + pure rules (S + L, one commit).**
   - `server/testdata/setup_steps.json` and its launcher copy; `restrictions_pause.json`'s `setup_hold_max_ms` (both
     copies);
   - `S/setup_status.rs` (table, `missing`, `setup_row`, `clean`) with its tests; `SETUP_HOLD_MAX_MS` and `paused_at`'s
     exception;
   - `L/setup/SetupSteps.kt` (with `flowScreens`) and `SetupHold.kt` with `SetupStepsTest`, `SetupFlowTest`;
   - `PolicyResponse.setup` and the policy's `setup`;
   - the report's `setup` (`SetupReadings.report`, `hold_until_ms`), `READ_BASIC_PHONE_STATE`, and the intake +
     migration 0157;
   - the phone's tick route and `SetupTicks`;
   - `launcher.yml` and `server-ci.yml` path filters and the root `CLAUDE.md`'s contract paragraphs get the new file and
     the new key.
   - Size: M.
2. **The launcher** (L):
   - the setup hold (`RestrictionsPause.startHold` with the notification grant first and `secure = null`, the flag's
     writes, `durationMs`, `Enrollment.enroll`'s fresh check before the token, `start_pause` while held, no screen-time
     accrual, the window notification's tap);
   - the flow (`SetupFlowActivity` in its own task, `SetupRun`, Home's start while held, the screens (no `parent_pin`
     screen) and the policy-free start, the existing windows from its steps);
   - row 14 read live and its page inside the pick window (§2.2.1);
   - parent Settings' one row, the moved rows' handlers into the flow, their long summaries gone;
   - the notification on `setupCount`, tapping into the flow;
   - FINE/COARSE always (§7.1);
   - the sync triggers (§4.1);
   - the strings (nb + en).
   - Size: L.
3. **The server** (S):
   - the page and its route, the open group's hold state;
   - the tick route (logged);
   - `Kind::Setup` (held back in the hold), `Kind::NotDeviceOwner`, `RestrictionsPaused`'s hold title, and the folds:
     `NoNotificationAccess`, `NotificationsOff`, `Calls(NoRedirection)`, `MobileDataOff`, the summary's `role-lost` line;
   - the warning partial on the eleven sections of §5.4 (`#map-links` waits for 110) and the notes it replaces;
   - About's links;
   - the Parent PIN before any setup code (§5.8): Connect's line, `provision_qr` and `regenerate_code` refusing, the
     save's `back=provision`, the un-enrolled summary's line;
   - the card, the hide route, the QR page's open-setup form, the adb texts and the dead i18n removed;
   - `back=setup`'s new target.
   - Size: M.
4. **Code QA → fix all → emulator gate (§11, after the user's OK) → merge.** PLAN's queue entry is marked built in
   the merge commit.

Design 110, built after C2, fills rows 15-16's readings and the `#map-links` section, and drops its own two step rows
and lines (§2.6).

## 10. Tests

**Shared (`setup_steps.json`, `restrictions_pause.json`), both sides compare the copies.**

**Server.**
- `setup_status::tests::the_contract` runs:
  - every `needed` row through `missing`/`count`/`setup_row`, the `map_links` rows included;
  - the `steps` table against the module's own constants (key, need, `read_by`, `when`, summary, level, features);
  - every `features` row against a known page and anchor;
  - every `names` against `setup.step.<key>` in nb.json and en.json;
  - every `clean` row (`null` kept, `hold_until_ms` kept only as a non-negative integer).
- `models::paused_at_tests`: `SETUP_HOLD_MAX_MS` against the contract; a held report stays paused past two hours and
  until its `setup_hold_until_ms`, never past `reported_at + SETUP_HOLD_MAX_MS`; a report whose `hold_until_ms` lies
  past `reported_at + hold_left_ms` (the phone's clock off) ends at the latter (QA L6); a report without the hold keeps
  the two-hour rule.
- `tests::setup_status`:
  - posts `report` and checks `setup_json` and `setup_hold_until_ms` stored, with a report whose readings make every
    step missing;
  - renders `/devices/{id}/setup` and checks the groups, the rows' `id`s and the open group (the hold's "I oppsett til"
    with "Lås igjen"; without the hold, "Åpne for oppsett");
  - posts a tick, checking the stored row, the redirect to `#step-<key>`, the `settings_log` entry, the policy's
    `setup.ticked` and the row moved into "Avkrysset"; unticks;
  - posts every `tick.answers` row to `/api/devices/setup/ticks` and checks the status, the body, the stored
    `by_phone`, the `settings_log` entry "på telefonen" and the policy; a 257-byte body is refused (413; QA L7);
  - checks the summary's `Setup(n)` row: its title, its level, that it isn't raised at N = 0, nor during the hold, and
    nothing for an un-enrolled phone;
  - checks the hold raises `RestrictionsPaused` with the hold's title and target, and an ordinary pause keeps today's;
  - checks mobile data off raises `Setup(1)` and no `mobile-data-off` row beside it, and counts once in the Devices
    list's badge (QA H1);
  - checks `setup.device_owner: false` raises `NotDeviceOwner` (Alarm, after `EasyParentPin`) and no `Setup` row, while
    the page still lists the steps (QA M4);
  - checks the summary has no `role-lost` line with bit 64 on and the role lost;
  - checks the `map_links` need from the stored `installed_apps_json`: switch on and Organic Maps installed, switch on
    and no map app, map app and switch off;
  - for every `features` row, renders its page with the step missing (one line linking to `/setup#step-<key>`, no ⓘ
    inside it) and done (no line); on Mobilnett `mobile_data` shows once (QA M1);
  - checks the replaced notes are gone: Home's two notes, the Flymodus row's role and Wi-Fi parts (the row keeps
    "Blokkert"), the glance's `no_access*`, the escape filter's `filter_no_access`, Calls' `NoRedirection`, Mobilnett's
    `data-off`; and that no rendered page carries `adb `;
  - checks About's rows link and carry no how-to;
  - checks `back=setup` lands on `/setup#open`, and the QR page has no `open-setup` row.
- `tests::provisioning` (revision 3b, §5.8): without a Parent PIN, `GET /provision` shows the `no-parent-pin` line
  linking to `/devices/{id}/lock?back=provision#parent-pin` and no form; `POST /provision` and `POST /regenerate-code`
  make no enrollment code (no row stored); the un-enrolled summary and Advanced › Connection show the line, not the
  button. With the PIN, as today; the existing provisioning tests seed one.
- `tests::parent_pin`: a save with `back=provision` redirects to `/devices/{id}/provision#qr`; a refused save with it
  stays at `lock?back=provision&notice=…#parent-pin`; an unknown `back` and `clear_pin` land as today.
- `intake_limits` keeps `report`'s keys. `policy_json_keys_snapshot` gets `setup`. `status_heartbeat.json` and its
  tests are unchanged (QA L7: `setup` is a full-report field).
- **Removed or rewritten:** `tests/airplane_mode.rs`' hide-route test; the attention tests of the folded kinds,
  `MobileDataOff` included, rewritten for `Setup(n)`; `tests/airplane_mode.rs::the_home_form_keeps_bit_64_without_its_row`'s
  `no_role`/offline texts (113's orange check moves to the warning line); 113's mobile-network test rows that assert
  `data-attention="mobile-data-off"` and the page's `data-off` line, now the setup line; the QR page's open-setup test.
- `i18n::tests::every_key_is_used` covers the deleted keys; grep each after the build (113 QA L5).

**Launcher.**
- `SetupStepsTest` checks:
  - `SetupStep` (keys, order, fields) against `steps`;
  - each step's name and how in `values` and `values-nb` (names against `names`);
  - each step's `pageIntents` actions against `phone_page` (pure: the action lists live beside the builders);
  - every `needed` row through `setupMissing`/`setupCount`/`setupRow`;
  - `report` and `report_no_hold` encoded exactly from a `SetupReport`;
  - `policy` decoded; every `tick.phone` row through `tickAction`; `tick.request` encoded exactly;
  - the copies compared.
- `SetupFlowTest` (pure, the launcher's alone): `flowScreens` for a fresh phone in the hold (table order, no
  `parent_pin` and no `device_owner` screen, even with the PIN hash missing), with skips, with a step done
  meanwhile, outside a window ("Not open" first), with no policy (the `always` rows, not Wait; QA M3), with nothing
  runnable ("Wait", its buttons after 30 s), N = 0 and N > 0 end screens; `flowButtons` per need and `read_by`; `setupHoldStarts` for each of device
  owner × `policy_ever_applied`.
- `RestrictionsPauseTest`: `SETUP_HOLD_DURATION_MS` against the contract; `durationMs` with and without the flag;
  `timedWindowActive` over the hold's length (on at 3 h 59 min, off at 4 h, off after a boot-count change, off when
  the wall clock says longer than the ceiling remains); `adoptedStartMs` reaches back the hold's length; a hold ended,
  then a PIN pause lasts 2 hours (QA M6: `start()` writes the flag false).
- `ParentPinCallersTest`: `RestrictionsPause.startHold(` is called from `Enrollment.kt` alone (QA M6).
- `AssistantSetupTest`: `defaultAppsChanged` drops `home`, `browser`, `dialer` and `sms` once back and never lists
  `call_screening`/`call_redirection` (QA H1); the pick window opened with `keepSnapshot` keeps the stored entries.
- `ScreenTimeTest`: no accrual while the hold lasts; `SetupFlowActivity` in `FREE_SCREENS` (QA L8).
- The hold's start: the snapshot's `secure` is `null` (QA M1), POST_NOTIFICATIONS is granted before `opened` (QA M4),
  and the fresh-phone check runs before `DeviceToken.set` (QA L10), each as a pure plan row in `SetupFlowTest`.
- `NotificationChannelsTest` also runs 107's `pages` rows' `orange` through `channelsOrange`.
- `AssistantSetupTest` loses `setupStepsLeft`'s rows (moved).
- `PolicyResponseCompatTest` decodes `setup`.
- `LocationPolicyTest` gets a row: Off takes no fix, and the plan's own grants include FINE/COARSE but not BACKGROUND.

## 11. Emulator gate (`docs/testing/emulator.md`, a new section)

Needs the user's OK first (memory "Emulator: ask first"). It runs on **`compat34`** (API 34, `-wipe-data`) and
**`jelly36`** (API 36, restored clean; a fresh enrolment needs a wiped image or a restore taken before enrolment). Each
row runs on both unless it says otherwise. Setup:
- a server of the branch;
- the phone "Testtelefon" (never a real name) and a test Parent PIN, set before the code (Connect needs it, §5.8;
  row 2 checks the page without it first);
- the branch's debug launcher made device owner the emulator's way (`docs/testing/emulator.md`), then enrolled with a
  setup code in Settings (the hold starts at any fresh enrolment, §6.1.1; the 6-tap QR needs a real phone);
- calls and SMS managed, bit 64 on.

| # | Row | Pass |
|---|---|---|
| 1 | Fresh enrolment | The hold starts: no lock task (`mLockTaskModeState=NONE`), Home leads to the flow; the report carries `hold_until_ms` and `hold_left_ms`; the PWA's summary shows "I oppsett, uten begrensninger" (red) and no `Setup` row; Device › Oppsett's open group says "I oppsett til …" with "Lås igjen" |
| 2 | Connect without a Parent PIN (revision 3b; run before row 1, the PWA only) | Connect shows "Velg en foreldre-PIN før oppsettet" alone: no QR form, no "New Setup Code"; the line opens Lock Screen & PINs at Parent PIN; save: back on Connect at `#qr` with the QR form, not at the top; the flow then shows no Foreldre-PIN screen |
| 3 | Notification access | The step's "Åpne" opens the listener detail page; Allow; back: the next screen; the PWA clears within seconds. Not greyed as a restricted setting (34 and 36; record otherwise, §7.2) |
| 4 | Mobile data (`svc data disable` first) | The step opens the SIM page (record if it falls back to the panel); on; next. Record that `svc data disable` reads as the user's reason |
| 5 | Data Saver (`cmd netpolicy set restrict-background true` first) | The step opens Vibb One's Unrestricted data switch; on; next |
| 6 | Full-screen intent (`appops set <pkg> USE_FULL_SCREEN_INTENT deny` first) | The step opens Android's page; allow; after "Ferdig", an incoming call (`adb emu gsm call`) shows Vibb's call screen over the lock |
| 7 | Design 98's steps in the flow | Digital assistent, Wi-Fi i flymodus and Samtalefilter work as in §6am (regression), from the flow's screens |
| 8 | Skips and ticks | "Senere" on a required step: it stays counted in the PWA after "Ferdig"; "Gjort" on Vibrasjon: the PWA lists it under "Avkrysset" within seconds; "Hopp over" on Fingeravtrykk: still in "Valgfritt" |
| 9 | Back and Home in the hold | Back does nothing; Home brings the flow back (34 and 36: predictive back on 36). "Åpne" → Android's page → Home: the same screen, with an earlier "Senere" and "Hopp over" still skipped (QA M2) |
| 9a | Default apps (QA H1; calls managed, the browser moved in the assistant window first) | Row 14 is a screen; "Åpne" opens Android's Default apps page, usable (not greyed); choose the browser back; back in the flow the step is gone, and the PWA's count drops |
| 9b | Notifications at the hold's start (QA M4; calls unmanaged, a fresh enrolment) | No permission dialog over the flow; both the window's "Åpent til … · Lås igjen" and "Fullfør oppsettet av Vibb One" show; the window notification's tap opens the flow |
| 9c | No first policy (QA M3; the server stopped before the enrolment's sync) | The `always` steps run at once; with them done, Wait shows "Prøv igjen" and "Ferdig" after 30 s |
| 9d | Apps from the PWA in the hold (revision 3b) | A catalog app turned on on the PWA's Apps page installs during the hold and opens from Android's app info; no app screen in the flow; the app is still allowed after "Ferdig"'s relock |
| 10 | "Ferdig" | Lock task on, the hold's row gone, `Setup(n)` shows what is left; Home is the child's |
| 11 | Restart in the middle (a second fresh enrolment) | After `adb reboot` the phone comes up locked, Home is the child's; the PWA's hold row goes at the next report; "Åpne for oppsett" in the PWA: the notification shows N equal to the PWA's, its tap opens the flow without a PIN |
| 12 | The PWA ends the hold (a third fresh enrolment) | "Lås igjen" on Device › Oppsett: the phone locks, the flow closes, Home is the child's |
| 13 | Parent Settings' row | PIN → Settings: the first row "Oppsett · N steg igjen"; the flow opens at "Sett på pause i 2 t"; the PIN; the steps; "Ferdig" ends the pause. With N = 0 the row reads "Fullført"; none of the moved rows is on Settings' main screen |
| 14 | Location with Locate Off | After an apply, `dumpsys package <pkg>` shows FINE/COARSE granted (policy-fixed) and BACKGROUND not; the Wi-Fi page lists networks; `fake-portal.py`'s window reports its SSID; no `LocationManager` request in the log, no location indicator |
| 15 | Not device owner (`compat34` only: a fresh debug install enrolled without device owner) | No hold, no flow; `setup.device_owner` false; the red `NotDeviceOwner` row; no `Setup` row; Oppsett lists the steps |
| 16 | The eye (`jelly36` only) | Screenshots of the flow (a step, the end), parent Settings' row, Connect without a Parent PIN, Device › Oppsett in the hold and after, in nb and en, for the user |

Not run: the hold's ceiling (four hours; `RestrictionsPauseTest` covers it), Android 17 (no emulator), the Jelly Star
(owed: the 6-tap QR into the hold; when the enrolment runs relative to the wizard's steps, and a screen lock set in the
wizard still there after "Ferdig" (QA M1); a wizard restart ending the hold before the flow, and the PWA's way on (QA
L9); Unihertz's page names in §5.6, the SIM page, the restricted-setting check), and rows
15-16 of the table (owed with design 110's gate).

## 12. The ASK list

1. **`vibration` required or optional** (§2.2 row 18). ANSWERED: optional.
2. **Message banners: tick now, detect later** (§2.5). ANSWERED 2026-11-18: ticked by hand now.
3. **Design 110's two map-link steps become rows in this table** (§2.6). ANSWERED: yes, only while design 110's switch
   is on and Organic Maps or CoMaps is installed (rows 15-16, `when: map_links`); confirmed 2026-11-18.
4. **"Ikke nå" goes**, so required steps can't be hidden (§5.3). ANSWERED: yes.
5. **The adb shortcuts leave the PWA's texts** (§5.7). ANSWERED 2026-11-18: yes; kept only in the docs for the emulator.
6. **BACKGROUND location stays tied to Locate/zones**, while FINE/COARSE are always granted (§7.1). ANSWERED: tied.
7. **The setup hold's ceiling** (§6.1.3). ANSWERED 2026-11-18: 4 hours.
8. **Home leads to the flow during the hold, as guidance, not a lock** (§6.2.1). ANSWERED 2026-11-18: yes; apps come
   from the PWA, no app screen (revision 3b).

## The user's answers

**2026-11-17, 21:50 CET:**
- ASK 1 (vibration on calls and messages): OPTIONAL, not required.
- ASK 3 (design 110's two map-link steps in this list): TAKEN, with the user's condition - the two steps apply only
  while Organic Maps or CoMaps is installed on the phone (the user: "Helst bør denne greia bare spørres om, om man
  faktisk har installert comaps eller organic maps"): read from the report's installed apps, together with 110's
  switch. (A Device Owner can't make the change itself: Play services holds the links VERIFIED, a persistent preferred
  activity loses to it, and changing another app's link state needs a permission it lacks - design 110 §6.2.)
- ASK 4 ("Ikke nå" goes, no hiding of a required step): TAKEN.
- ASK 6 (background location): follows Locate and zones, as today; fine and coarse always granted.

**2026-11-18:**
- ASK 2: "det er helt ok, dette er jo samme som på en vanlig telefon" - message banners ticked by hand now; detection
  later.
- ASK 5: "Trengs de adb-kommandoene? planen vår er vel heller guidet setup på telefonen som gjør det samme der hvor det
  trengs, og at ting helst gjøres via vibb launcher setup?" - the adb commands leave the PWA; kept in `docs/setup/` for
  the emulator.
- ASK 3's condition: "OK at kartsteg bare vises om det er installert".
- The guided setup (revision 3): "Spm er jo hvorvidt man etter QR kan bli guidet gjennom oppsettet før man i det hele
  tatt har satt på sperrene etc? på telefonen?", then "Jeg er ok med at du legger det inn før 114 bygges", and its
  shape: "så må vi bare passe på så vi ikke gjør den parent settings på telefonen helt uoversiktlig med alt av guidet
  setup ... Kanskje det er en slags submeny eller noe man starter opp som kjører gjennom på egen hånd, og så viser
  hovedskjerm på parent settings heller bare at man har gjennomført setup eller noe mer som 1 linje."
- Revision 3's two questions (ceiling 4 hours; Home leads to the flow, as guidance): "ok, høres bra ut!".
- The Parent PIN before the QR (revision 3b, §5.8): "vi må passe på at vi ikke låser oss ute om man ikke har fått satt
  foreldre pin før de 4 timene går ut" - the QR page asks for the PIN first, and the flow's PIN screen goes.
- No "Apper" screen (revision 3b): "primært er jo planen å installere det meste via pwa da" - apps are installed from
  the PWA; every app is open in the hold for the logins.

## Revision 2 (2026-11-18)

The design QA (`docs/review/113-114-design-qa.md`, 114 part) and the user's answers, applied; checked against `main` at
3752baaad and `origin/copy-round-113` at ae483f41f.

| Finding | What changed |
|---|---|
| **H1** `MobileDataOff` counted twice beside `Setup(n)` | §5.2 and §8 fold design 113's `Kind::MobileDataOff` into `Setup(n)` (the kind, its key, its title and `Facts::mobile_data_off` go); §9 step 3 and §10 test "no `mobile-data-off` row beside `Setup(1)`, one badge count". §5.2's old "`#mobile-state`'s line goes" dropped: 113 already removed it and the Sections row's «Mobildata av» |
| **M1** lines said twice, fold list incomplete | §5.4's "These lines replace" and §8 now also fold: `airplane_mode::home_sub`'s role and Wi-Fi parts (the row keeps "Blokkert"), the glance's `no_access*` notes, `kiosk_escapes`' `FilterWithoutAccess` (new feature `notification_filter`, Advanced › `#updates`; its adb-only text joins §5.7's list), Mobilnett's 113 `data-off` line; `mobile_data` warns once on Mobilnett (`#mobile-data`; the `roaming` feature is gone). Also folded on the same ground: the summary's `role-lost` line (inventory B7) |
| **M2** the user's answers not in the table and contract | Row `vibration` optional; the "apart from `vibration`" sentence gone; the sample's `vibration` is `optional` and `needed[0]` recounted (6 → 5); rows 15-16 `map_play_links`/`map_vibb_links` with `when: map_links`, a report-derived input stated in §2.3 (the server's `installed_apps_json`, the phone's reported package list), `needed` rows for it; §2.6 decided; §12 marks each answer |
| **M3** ASK 2 and ASK 5 not put to the user | "Questions for the user" at the top, each with one plain sentence and the recommended answer; §2.5 and §5.7 name the audit's KEEP conflict and both outcomes; the build waits for the answers (§9) |
| **M4** `device_owner`'s alarm row has no kind | `Kind::NotDeviceOwner`, key `not-device-owner`, Alarm, after `AccessRevoked` (revision 3b: after `EasyParentPin`, `AccessRevoked` being gone, §5.2), `read_from_report` true; while raised, `Setup(n)` isn't (§2.3 `setup_row`, a contract field); the page still lists every step; other kinds unchanged |
| **L1** no `features` column | Added to §2.2; the contract sample follows |
| **L2** stale migration number | `0157_setup_status.sql` (0155 `catalog_app_logo`, 0156 design 115's `up_held`); 110 renumbers at its build |
| **L3** `clean` loses `null` | `clean` keeps `null`; a `clean` row says so |
| **L4** `isDataEnabled` false for carrier/thermal reasons | Row 5 reads a new `setup.mobile_data` = `isDataEnabledForReason(DATA_ENABLED_REASON_USER)`, with `READ_BASIC_PHONE_STATE` in the manifest; `mobile_data.enabled` stays for Mobilnett; the `sim` field is gone (`mobile_data` absent without a data subscription) |
| **L5** calls Off requires the call steps | Confirmed and stated in §2.3: under Off Vibb holds the roles and an emergency callback rings through its in-call screen |
| **L6** line references go stale with 113 | §5.4, §5.5, §8 and §9 name functions and keys; the header says which commits the rest point at |
| **L7** heartbeat | §4.1 and §10: a full-report field, `status_heartbeat.json` unchanged |

## Revision 3 (2026-11-18)

The guided setup and the answers to revision 2's questions; checked against `main` at f13b79cbd (design 113 merged).

| Where | What changed |
|---|---|
| Top | "Revision 3 in short"; revision 2's two questions answered and replaced by the hold's ceiling and Home in the hold |
| §1 | Goals 2, 3, 5 and 6 reworded for the flow; the Google account and the Parent PIN on the phone named as non-goals |
| §2.1, §2.2 | "Senere" in the flow changes nothing for a required step; the path column is the flow's "Åpne"; row 22 decided |
| §2.4 | Phones already set up get no hold; a stale launcher behaves as today |
| §2.5 | Decided: ticked by hand (the PWA or the flow's "Gjort") |
| §3 | `setup_steps.json` gains `report_no_hold`, `hold_until_ms`, `tick` and `clean` rows; `restrictions_pause.json` gains `setup_hold_max_ms` (§3.2) |
| §4 | `hold_until_ms` in the report; `device_status.setup_hold_until_ms`; `device_setup_ticks.by_phone`; the phone's tick route (§4.4) |
| §5.1, §5.2 | The open group's hold state; `Setup(n)` held back in the hold; `RestrictionsPaused`'s hold title and target; the quick sheet's end from the hold; the QR page's "Åpne for oppsett nå" goes |
| §5.6, §5.7 | The hold's keys; `setup.how.phone` without the PIN; the adb texts removed (decided) |
| §6 | Rewritten: the setup hold (§6.1: start, what it is, how it ends, the server's exception, a restart), the flow (§6.2), parent Settings' one row instead of the category (§6.3), the notification into the flow (§6.4), the code (§6.5), the phone's copy (§6.6) |
| §7.3 | The flow's Android 14-17 points: starts, predictive back, insets, the clocks, Android 17, GrapheneOS |
| §8-§11 | The new rows, steps, tests and gate rows; the launcher step grows to L |

## Revision 3 QA applied (2026-11-18)

`docs/review/114-r3-design-qa.md` (at 287ae1fef), every finding applied; the two questions take the QA's view as the
recommendation and are still asked (answered in revision 3b).

| Finding | Where it went |
|---|---|
| **H1** row 14 can't be done, its reading never clears | §2.2 row 14 and new §2.2.1 (read live, entries dropped once back; screening/redirection left to row 13; "Åpne" inside design 98's pick window without a new snapshot); §6.2.2; §6.5; §8; §3.1 `needed` row; §10 `AssistantSetupTest`; gate row 9a |
| **M1** the hold's snapshot can remove the wizard's screen lock | §6.1.2 step 2 (`secure = null`, the token's early activation kept); §10; the Jelly Star's owed checks (§11) |
| **M2** Home loses the run on every Home press | §6.2.1 (own task, `taskAffinity` `.setup`; the start's place in `onResume`); §6.2.2 (`SetupRun`); §6.1.3 1 (`lockAgain` synchronous before Home); §6.1.5; §6.5; gate row 9 |
| **M3** the Wait screen can trap the parent | §6.2.2 (the `always` rows before the first policy, Wait only with nothing runnable, "Prøv igjen" and "Ferdig" after 30 s); §6.6; §10 `SetupFlowTest`; gate row 9c |
| **M4** POST_NOTIFICATIONS at setup time | §6.1.2 step 1 (self-grant before `opened`; Home skips the dialog while device owner); §7.3; §8; §10; gate row 9b |
| **M5** question 2 stated wrong: guidance, not containment | Question 2 and "Revision 3 in short" reworded; §6.1.3's last paragraph; §6.2.1; §5.1 names the quick sheet's "Åpne for oppsett" after "Ferdig". The "Apper" screen to the Play Store is superseded by revision 3b: installs from the PWA |
| **M6** the hold's opener unpinned, the flag's writes unspecified | §6.1.1 (the basis, `ParentPinCallersTest`, the doc comment); §6.1.2 step 3 (`start()` false, `startHold()` true, in the same `lift`); §10 |
| **L1** the clock is locked on most phones | §6.1.3 3 (the reason fixed; the conclusion stands) |
| **L2** two notifications in the hold | §6.1.2 "Notifications" (both; the window notification's tap opens the flow); §6.2.1 |
| **L3** `setup.how.phone` points at a notification often absent | §5.6 «Foreldreinnstillinger › Oppsett på telefonen.» |
| **L4** "Ferdig" for a state and a button | §5.2 and §5.6: the state is "Fullført" / "Complete" on both sides; "Ferdig" is the button only |
| **L5** `setup.background_data` names Data Saver | renamed `setup.data_saver` (§2.2 row 6, §3.1, §4.1) |
| **L6** the server compares the phone's wall clock | `hold_left_ms` in the report (§3.1, §4.1); the stored end `min(hold_until_ms, reported_at + hold_left_ms)` (§4.2, §6.1.4); §10 |
| **L7** the tick route's edges | §4.4 (256-byte `DefaultBodyLimit`, the `settings_log` entry "på telefonen"); §3.1 `tick.phone` (401/403 retry, 413 done); §10 |
| **L8** the parent's setup time counts as screen time | §6.1.2 "Screen time" (no accrual in the hold; `SetupFlowActivity` in `FREE_SCREENS`); §8; §10 |
| **L9** a wizard restart ends the hold before the flow | §6.1.5 (named; the PWA's "Åpne for oppsett" is the way on); the Jelly Star's owed checks (§11) |
| **L10** decide "fresh" before the token is stored | §6.1.1 (at the top of `Enrollment.enroll`, before `DeviceToken.set`); §10 |
| **L11** §2.3 and row 1 disagree | §2.3: the phone reads `parent_pin` from its policy, `device_owner` is its own reading |
| Question 1 | 4 hours recommended (§3.2's `setup_hold_max_ms` 14400000, §6.1.3, §10, §11, §12); answered: 4 hours |
| Question 2 | Home leads to the flow, as guidance, with M2/M3 built with it (§6.2.1, §12); answered: yes |

## Build notes (launcher, 2026-11-18)

Built on branch `design-114-build`; the launcher side's code notes are `docs/notes/launcher/setup.md`.
- **Versions checked:** against the Android 14 (API 34) and 16 (API 36) platform APIs the code calls; every API used
  is API 34 or lower (`canUseFullScreenIntent` and `ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT` 34,
  `isDataEnabledForReason` and `ACTION_APP_OPEN_BY_DEFAULT_SETTINGS` 31, `READ_BASIC_PHONE_STATE` 33), so there is no
  version gate and lint's NewApi has nothing to flag; predictive back (16 at targetSdk 36) goes through an
  `OnBackPressedCallback`; edge-to-edge (15+) by `fitsSystemWindows`. Not run on a device yet: the emulator gate
  (§11, `docs/testing/emulator.md` §6bb) waits for the user's OK; Android 17 is owed (no emulator).
- **`write_settings_used`** reads bit 8 (Sound) too, beside bit 16 and the clock format: the kid's ringtone picks write
  `Settings.System`, and the server's `airplane_mode::write_settings_needed` already counted it. The contract's
  `needed` rows pin it.
- **The Parent PIN reading on the phone** comes from the vault (`ParentPin.status().configured`): since design 24b the
  cached policy carries no PIN hash.
- **The flow's position** is the first missing step not put off; `SetupRun` keeps the skips, the steps shown (for
  "2 av 7") and the steps whose page is missing for the run.
- **Row 8 while Vibb is the dialer:** Android greys an off channel's switch (design 107's gate §6ax), so the step's
  line adds "Er en bryter grå: sett Anrop og SMS til «Ikke styrt» i foreldreappen først, og tilbake etterpå." (the old
  Settings row's words, shortened).
- **Row 17's line** (`setup_how_fingerprint`, "Legg til et fingeravtrykk." / "Add a fingerprint."): §5.6 has no ⓘ text
  for it; the phone's screen needs one line.
- **The restricted-setting line of §7.2** isn't built: it is added only if the gate finds the switch greyed.
- **Removed with the moved Settings rows:** their pure row-state helpers (`assistantRowState`, `wifiApmRowState`,
  `writeSettingsRowState`, `batteryRowState`, `fingerprintStateText`/`ActionText`, `mediaLockText`,
  `notificationsRowText`, `setupStepsLeft`, `screeningRowShown`, `assistantRowShown`), their strings and tests.
- **The approved small fix of this round:** `settings_mdm_server_url_tailnet_http` keeps the state ("Vanlig http,
  bare gjennom tailnettet.") and loses the advice sentence (docs/review/2026-11-18-choices-not-warnings.md).
