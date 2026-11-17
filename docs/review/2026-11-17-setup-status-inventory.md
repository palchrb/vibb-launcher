# Phone setup status: inventory (read-only, 2026-11-17)

Scope: everything Vibb One needs on a phone to work as meant (PWA in control on mobile data too); what the Device
Owner (DO) does itself, what needs a human and the on-phone path today, what the report carries, what the PWA shows;
then the pieces that already overlap, the gaps, and where the one place should live. Paths are relative to
`/workspace/handy`; `L/` = `launcher/app/src/main/java/com/kidslauncher/mdm/`, `S/` = `server/src/`, `T/` =
`server/templates/`. Line numbers are on `main` at 70904fae3.

**The biggest finding first:** a setup status already exists, twice, and only covers part of the steps.
- Phone: design 98's "Fullfør oppsett" - `AssistantSetup.stepsLeft` (L/assistant/AssistantSetup.kt:274-291) over the
  pure `setupStepsLeft` (L/assistant/AssistantSetupPlan.kt:68-82: battery, write settings, assistant, Wi-Fi in airplane
  mode, call filter), a LOW notification "Fullfør oppsettet av Vibb One · N steg igjen" while the phone is open for
  setup (`refreshNotice`, :294-327, channel `launcher:setup`), whose tap opens the PIN-gated Settings scrolled to
  "Bakgrunnstilkobling" (L/ui/settings/launcher/SettingsFragmentLauncher.kt:571-575). The rows themselves are scattered
  in one flat list (L/../res/xml/preferences.xml:48-94), not a section.
- PWA: design 98's "Oppsett" card on the summary (T/device_detail.html:160-185; `airplane_mode::setup_steps`,
  S/airplane_mode.rs:323-425; `SetupCard::of` :441-448, built in S/handlers/devices.rs:2435): background, write
  settings, assistant, Wi-Fi in airplane mode, default apps changed, call filter; "Åpne for oppsett" (start-pause,
  `back=setup`) and "Ikke nå" (`POST /devices/{id}/setup-card/hide`, S/main.rs:1217, stores the set in
  `device_policy.setup_card_hidden`). Only for a launcher with `airplane_mode_v1`, and it lives in the airplane module.
- The two lists are computed separately (Kotlin and Rust) with no shared contract; they already differ (the server
  has "default apps changed", the phone doesn't count it).

The design should grow these two into the one place, not add a third.

Legend for the table: **Req** = R required for Vibb to work as meant / C required only when a feature is on / O
optional polish. **DO** = what the Device Owner does itself. **Human** = the on-phone path today ("pause" = the
PIN-gated Settings during "Sett alle begrensninger på pause" or the PWA's "Åpne for oppsett"; both open Android's pages
through `systemPageIntent`, L/server/BatteryExemption.kt:66, which resolves only in a window). **Rep** = status report
field. **PWA** = where it shows today.

## A. Requirements

### A1. Core

| Requirement | Req | Auto by DO? | Human path today | Reported? | Shown in PWA | Gap |
|---|---|---|---|---|---|---|
| Device Owner | R | n/a (QR provisioning or `dpm set-device-owner`) | QR page (T/provision_qr.html) | **No** explicit field; `remote_wipe_ready` is `null` when not DO (L/server/LocateCommands.kt:632-643) | Nothing | A non-DO launcher reports and looks fine; nothing says "not device owner" |
| Enrolled, server URL, token | R | n/a | Settings: server URL, scan setup QR, enrol (preferences.xml:22-42) | Implicit (reports at all) | Summary `#access` card, connection dot | - |
| Server URL rule / certificate check (design 76) | R | Yes (pins from policy, `ServerPins`) | Settings "Serversertifikat" row explains recovery (preferences.xml:27-29) | `server_tls` | Advanced `#server-tls` (T/device_advanced.html:341) | - (server-side state, keep there) |
| Tailnet (tsnet) when the server is tailnet-only | C | Yes (QR carries the key) | Settings Tailscale key row | `tailnet_joined` | Only as "Leave Tailnet" on Advanced (S/handlers/device_pages.rs:1004) | Not a step unless the server URL is a tailnet IP; leave as is |
| Home app (ROLE_HOME) | R | Yes: `addPersistentPreferredActivity` HOME (L/server/AppEnforcer.kt:1804-1815, called :350, :1760) | none needed | `update_fence.home_role_held` | Attention `NotHomeApp` -> Advanced `#updates` (S/attention.rs:425) | - |
| Lock task / kiosk, hardening, private DNS, auto time | R | Yes (AppEnforcer; `setGlobalPrivateDnsModeOpportunistic` :1541) | none | `kiosk_engaged`, `policy_state` | Attention `PolicyProblem` -> About `#software` | - |
| Always-on VPN (own package; lockdown only in the Bare Vibb sink) | R | Yes: `setAlwaysOnVpnPackage` (L/server/MobileNetworkRuntime.kt:146-161) | none | Shape only (`mobile_data.sink`); **tunnel up/down not reported** | Mobilnett page lines | "Filter on, tunnel down" is C2's (PLAN:55), not setup |
| Parent PIN (gates the phone's Settings, so every phone-side step) | R | Server-side | PWA Lock Screen & PINs `#parent-pin` | Server's own | Red card before setup (T/device_detail.html:79-84); Attention `NoParentPin` -> `/lock#parent-pin` | Belongs first in the setup list: without it no phone step can be done |
| Child PIN / Android lock (android mode), reset token | C | Yes when the phone had no credential (`ensureResetToken`, L/server/WindowRelock.kt:252-260) | none on phone | `lock_state.keyguard/pin_rev/reset_token` | Lock Screen notes `NoResetToken`, `PinNotChildPin` (S/kid_lock.rs:955-971); Attention `ChildLockOff`/`ChildPinChanged` | Keep on Lock page; link |
| Phone Setup's time zone = the phone's | C (rule ends shown) | Phone forces auto zone | PWA Settings › Telefonoppsett | `time_state.zone` | Silently no end time | Server config, not a phone step |

### A2. Roles

| Requirement | Req | Auto by DO? | Human path today | Reported? | Shown in PWA | Gap |
|---|---|---|---|---|---|---|
| Dialer role (calls managed) | C | Yes: `setDefaultDialerApplication` (L/server/AppEnforcer.kt:979-1010) | none | `call_state.dialer_role_held` | Calls page + Attention `Calls(NotPhoneApp/AnyoneCanCall)` (S/handlers/calls.rs:1846-1849) | - |
| SMS role (SMS managed) | C | Yes: `setDefaultSmsApplication` + RoleManager check (AppEnforcer.kt:1052-1090) | none | `sms_state` role | Attention `Calls(SmsNotOurs)` (calls.rs:1862) | - |
| Call screening + call redirection roles (D10) | C (calls managed) | **No** (no DO API) | Settings "Samtalefilter" in a pause: `createRequestRoleIntent` (L/assistant/AssistantSetup.kt:197-202; row SettingsFragmentLauncher.kt:775-788) | `call_state.screening_role_held`, `redirection_role_held` | Oppsett card "Samtalefilter"; Attention `Calls(NoRedirection)` | Said twice (card + Attention) |
| Digital assistant role (Flymodus route) | C (bit 64) | **No** | Settings "Digital assistent" in a pause: `ACTION_VOICE_INPUT_SETTINGS` (AssistantSetup.kt:193-194; row :758-764) | `airplane_mode.assistant` | Oppsett card; About › Software "Digital assistent" with steps (device_pages.rs:717-728); `#airplane-state` role-lost line | Said three times |
| Wi-Fi kept in airplane mode | C (bit 64 + role) | **No** (a stored observation) | Settings "Wi-Fi i flymodus" in a pause (AssistantSetup.kt:210-245) | `airplane_mode.wifi_kept` | Oppsett card; About "Wi-Fi i flymodus" (device_pages.rs:729-741) | - |
| Default apps moved during a setup window (browser, screening, Home) | C | Partly (dialer/SMS/Home taken back; `movedRoles` AssistantSetupPlan.kt:101) | none (an ack) | `airplane_mode.default_apps_changed` | Oppsett card "Standardapper endret" | Not counted in the phone's `setupStepsLeft` |
| Login browser "role" (Chrome for logins, design 102) | O | Yes (managed config bundle, `BrowserConfig`) | Chrome must be installed (Play) | `login_browser` | Device › Sign-in page | Keep there |
| Captive-portal handler, UP link handler | C | Yes (`addPersistentPreferredActivity`, AppEnforcer.kt:1778, :1798) | none | `captive_portal` | Sign-in page | - |

### A3. Runtime permissions (Vibb One's own)

| Requirement | Req | Auto by DO? | Human path today | Reported? | Shown in PWA | Gap |
|---|---|---|---|---|---|---|
| Phone, call log, contacts | C (calls) | Yes: `selfGrantPermission` while managed (AppEnforcer.kt:835-846, list :60-67); POLICY_FIXED before a role change (`fixOwnPermissions` :1188) | none | `call_state.call_log_readable` | Attention `Calls(CallLogUnreadable)` | - |
| SMS/MMS/WAP, phone numbers | C (SMS) | Via the SMS role + `fixOwnPermissions` (AppEnforcer.kt:1063-1078) | none | `sms_state` | Calls page | - |
| Calendar read/write | C (Dagen) | Yes (L/calendar/CalendarStore.kt:163-164) | none | `calendar.readable` | Device › Calendar | - |
| Camera (QR scanner) | C | Yes (SettingsFragmentLauncher.kt:940) | none | No | - | - |
| Bluetooth connect/scan | C | Yes, when the page opens (L/server/QuickControls.kt:137, :697) | none | No | - | - |
| Nearby Wi-Fi devices (13+) | C | Yes, when the Wi-Fi page scans (QuickControls.kt:417) | none | No | - | - |
| **Fine/coarse location** (Find my phone, zones, **Wi-Fi scan results, the portal window's SSID**) | R for Wi-Fi page and portal; C for Locate | **Only while the location policy isn't Off** (L/server/LocateCommands.kt:105-125: "Off: no permission grants") or zones are watched (L/zones/ZoneFences.kt:97-108); the Wi-Fi config path grants it and puts it back (WindowRelock.kt:223-244) | none | **No** (fine not reported) | Nothing | Emulator gate 2026-11-17: `startScan … has no location permission`, Wi-Fi page empty, portal `network` null (docs/testing/2026-11-17-r2r3-swipe-gate.md:131-133; emulator.md:4584-4586) |
| Background location | C (zones) | Yes, same calls; comment says unconfirmed for DO (LocateCommands.kt:126-130) | none | `zone_watch.background_location` (L/zones/ZoneWatch.kt:358) | Locate's watch line "no background" (S/handlers/locate.rs:351-360), only with zones + Regularly | - |
| Post notifications (13+) | R | Dialog at Home (L/ui/HomeActivity.kt:470); POLICY_FIXED while a role is held and at relock (WindowRelock.kt:692-693) | Android's page via the 107 row | `notification_channels.all_off` | About › Lyd › Varsler; Attention `NotificationsOff` | - |
| Music app's notifications + BT connect | C | Yes (L/music/MusicSync.kt:62-77) | none | No | - | - |

### A4. Special accesses and app-ops (no DO API)

| Requirement | Req | Auto by DO? | Human path today | Reported? | Shown in PWA | Gap |
|---|---|---|---|---|---|---|
| **Notification listener** (badges, music card, lock glance, VoIP/glance reads) | R | **No** | **adb only**: `cmd notification allow_listener` (L/badges/BadgeStore.kt:31-37; PWA's fix text S/i18n/nb.json:1717) | `notification_listener_enabled` (L/server/MdmSyncWorker.kt:536) | Home Screen warning (T/device_home.html:19, S/handlers/device_pages.rs:1518-1530); Attention `NoNotificationAccess` -> `/home#layout` (S/attention.rs:428) | **No on-phone path** - breaks the "no adb beyond QR" rule |
| Battery optimisation exemption (SSE stream while dozing) | R | No (`setApplicationExemptions` needs a role permission a plain DO lacks - to confirm) | Settings "Bakgrunnstilkobling" -> Android's dialog in a pause (preferences.xml:48-51; L/server/BatteryExemption.kt:33-60) | `battery_optimization_exempt` | Oppsett card; About › Software "Batterioptimalisering" (device_pages.rs:677-692) | - |
| Modify system settings (touch row, clock format) | C (bit 16 or a clock format) | No | Settings "Endre systeminnstillinger" in a pause (preferences.xml:53-57; L/server/WriteSettingsAccess.kt:69) | `write_settings_granted` | Oppsett card; About row with steps (device_pages.rs:693-713); Home Screen clock warning (device_pages.rs:1541); kid side "Kan ikke endres på denne telefonen ennå" | Steps text said in 3 places |
| Exact alarms | R | Install-time (`USE_EXACT_ALARM`, manifest :101); callers check `canScheduleExactAlarms` | none | No | - | - |
| Full-screen intent (in-call screen over the lock) | R (calls) | Install-time default for a sideloaded app / dialer holder; **never checked** (`canUseFullScreenIntent` not called; L/calls/CallNotifications.kt) | none | No (only other apps' `voip_fsi_denied`) | Lock page `CallsCantRing` for VoIP apps | Unverified on 14-17; cheap to report |
| Usage access, overlay | - | Not declared by Vibb; n/a | - | - | - | - |
| Other apps' special accesses (design 68) | C per app | No | Settings "Tilgang for apper" category in a pause (preferences.xml:117-121) | `installed_apps[].access` | Device › Apps sub-line + Permissions details | Per-app, not phone setup: link |

### A5. Network: the PWA in control on mobile data

| Requirement | Req | Auto by DO? | Human path today | Reported? | Shown in PWA | Gap |
|---|---|---|---|---|---|---|
| **Android's mobile-data switch on** | R (approved 2026-11-17, PLAN:46) | **No** (`setDataEnabled` needs MODIFY_PHONE_STATE); the settings lock then keeps it as it is | Words only: "under Åpne for oppsett: Innstillinger › Nettverk og internett › SIM-kort › Mobildata" (S/i18n/nb.json:1142) | `mobile_data.enabled` (L/server/MobileNetworkRuntime.kt:350-351) | Summary `#mobile-state` grey line (S/mobile_network.rs:353-361, `warn: false`); Mobilnett row value | Approved to go orange + Attention, not built; no direct row on the phone |
| Roaming switch on (only when travelling and allowed) | C | No (`setGlobalSetting(DATA_ROAMING)` is on DPMS's list but writes the same key; design 94 §2) | Words only (nb.json:1181, :1183) | `data_roaming.enabled` | Mobilnett page, summary warnings while roaming | Only a step before a trip; leave on Mobilnett |
| Data Saver / per-app background data | R on cellular | No | none | **No** (`getRestrictBackgroundStatus` not read) | Nothing | Unchecked: could silence Vibb on cellular |
| Mobile-network settings lock | R | Yes (`DISALLOW_CONFIG_MOBILE_NETWORKS`, MobileNetworkRuntime.kt:260-262) | - | `mobile_data.settings_locked` | Mobilnett | - |

### A6. Notifications and other apps

| Requirement | Req | Auto by DO? | Human path today | Reported? | Shown in PWA | Gap |
|---|---|---|---|---|---|---|
| Vibb One's own channels on (design 107) | R (Meldinger/Tapte anrop while managed) | Locked while the dialer role is held | Settings "Varsler fra Vibb One" in a pause, shown only while off (preferences.xml:59-64; SettingsFragmentLauncher.kt:791-818) | `notification_channels` | About › Lyd › Varsler (device_pages.rs:631-645); Attention `NotificationsOff` -> `/about#sound` | - |
| Banners for message apps whose channel is Default (Element X) | O | No (another app's channels) | Manual, docs only (docs/setup/android-settings.md "Banners …") | No - but the listener already reads each post's `Ranking.importance` (L/badges/GlanceReader.kt:104-109) | Nothing | Detectable after the first message; no row |
| Map links via GeoShare: Play services' "Open supported links" off (design 110) | O | No | Manual, docs + adb | No | Nothing | Design 110 owns it |
| Vibb music installed, signed, bound | C (music entries on) | Install via catalog; grants (MusicSync.kt) | none | `music_state` | Attention `MusicCantDeliver`/`MusicUnreachable` -> Music page | Keep on Music |
| Google account for Play | O | No (and `set-device-owner` refuses a phone with one) | In a pause (docs/setup/google-account.md §3) | **No** | Nothing | Optional; not readable without account visibility |
| Backup to Google off | R | Yes (`setBackupServiceEnabled(false)`, L/server/BackupService.kt:39) | - | `backup_service_enabled` | Attention `GoogleBackup` -> Advanced `#restrictions` | - |
| Do Not Disturb off | - (state, not setup) | - | - | `interruption_filter` | Attention `DoNotDisturb` | Not a setup step |

### A7. Android's own settings no app can read or write

| Requirement | Req | Auto? | Human path today | Reported? | Shown | Gap |
|---|---|---|---|---|---|---|
| Touch feedback on (hidden `HAPTIC_FEEDBACK_INTENSITY`, 13+) | O | No | docs only | No (not readable) | Nothing | Manual tick only |
| Ring + notification vibration on (hidden intensities, 13+) | O (the user: "så lenge telefonen alltid vibrerer") | No | docs only | No | Nothing | Manual tick only |
| Lock clock one line (`lockscreen_use_double_line_clock`) | O | No | docs only | No | Nothing | Manual tick only |
| No media card on Android's lock (`media_controls_lock_screen`, no `@Readable`) | O | No | Settings "Medier på låseskjermen" row, always shown (preferences.xml:89-94) | No | Nothing | Row exists, state unknowable |
| Fingerprint (android mode) | O | No | Settings "Fingeravtrykk" in a pause (preferences.xml:82-87) | No | Nothing | Optional row; fine as is |

Android 14-17 differences the code has: Android 17 never hides or suspends Settings (docs/notes/launcher/hardening.md:69-79), so
`systemPageAccess`'s "pause first" still holds through lock task, not hiding; Android 16 reserves `android.app.*`
channels (skipped in 107's reader); the hidden haptic/vibration intensities and the media-on-lock key are 13+ (all
supported versions); NEARBY_WIFI_DEVICES is 13+; full-screen intent's runtime check is 14+.

## B. What already overlaps (verdict: keep / fold / link)

1. **Summary "Oppsett" card** (T/device_detail.html:160-185, S/airplane_mode.rs:315-448) - **FOLD, as the seed.** It is
   already the "steps the phone needs" list with Åpne for oppsett. Move it out of `airplane_mode` into its own module
   (e.g. `setup_status.rs`), drop the `airplane_mode_v1` gate (C1 removes capability gates anyway), widen its steps.
2. **Phone "Fullfør oppsett" notification + `setupStepsLeft`** (AssistantSetup.kt:274-327, AssistantSetupPlan.kt:68-82) -
   **FOLD, as the phone seed.** Same list, one entry point; widen it to every phone-side step.
3. **Phone Settings' grant rows** (preferences.xml:48-94: Bakgrunnstilkobling, Endre systeminnstillinger, Varsler fra
   Vibb One, Digital assistent, Wi-Fi i flymodus, Samtalefilter, Fingeravtrykk, Medier på låseskjermen) - **FOLD** into
   one `PreferenceCategory` "Fullfør oppsett" at the top of Settings, missing steps first, the notification's tap
   scrolling to it (today it scrolls to one row, :574). The row mechanics (`systemPageIntent`, `systemPageAccess`, the
   gate kept open by `ownPageOpening`) stay as they are.
4. **Attention rows that are setup steps** (S/attention.rs:226-300): `NoNotificationAccess`, `NotificationsOff`,
   `Calls(NoRedirection)` (overlaps "Samtalefilter"), the coming "Mobildata er av" - **FOLD** into one orange Attention
   row "Oppsett: N steg igjen" -> the setup status (the PLAN's "one orange line … nothing when all is done"). Keep as
   their own rows: alarms that are regressions or security (`RestrictionsPaused`, `ForeignAccess`, `PolicyProblem`,
   `NotHomeApp`, `BootCoverNotShown`, `GoogleBackup`, `NoParentPin`, the lock and music ones). `NoParentPin` also
   appears as the setup list's first, server-side step (link, not a second row).
5. **About This Phone › Software/Lyd rows** (device_pages.rs:631-741: Batterioptimalisering, Endre systeminnstillinger,
   Digital assistent, Wi-Fi i flymodus, Varsler) - **KEEP as state, LINK for the steps.** About says what is; the
   how-to text (`device.write_settings_steps`, `device.airplane.assistant_steps`) moves to the setup status and the
   rows' ⓘ points there (say it once).
6. **Summary `#mobile-state`** (T/device_detail.html:133-143, S/mobile_network.rs:342-376) - **FOLD** "Mobildata er
   slått av på telefonen" into the setup status (the approved orange line); **KEEP** the roaming and Bare Vibb lines
   on Mobilnett.
7. **Summary `#airplane-state`** (T/device_detail.html:146-158) - **KEEP** the period and emergency lines; the
   "role lost" line is the assistant step - **FOLD**.
8. **Avansert `#setup` "Åpne for oppsett"** (T/device_advanced.html:21, S/handlers/quick_actions.rs:614) and Quick
   actions' `#setup-open` (T/device_detail.html:214-226) - **KEEP** as the action and the "open until" state; the
   setup status's button posts the same `start-pause` with `back=setup`.
9. **QR page "Åpne for oppsett nå"** (T/provision_qr.html:76-83) - **KEEP**, lands on the setup status (`#oppsett`).
10. **Launcher check** (design 86, T/partials/launcher_check.html, on Telefonoppsett and the QR page) - **KEEP
    separate**: it is pre-enrolment and server-wide (the APK a new phone gets), not a phone's state. Link from the
    setup status only when it reports refused/older.
11. **Home Screen warnings** (T/device_home.html:19-22): notification access - **FOLD** (setup step); clock format -
    **KEEP** as the consequence where the choice is made, linking to the write-settings step.
12. **Lock Screen & PINs notes** (S/kid_lock.rs:955-980) - **KEEP**; Parent PIN **LINK** as step 0.
13. **Device › Apps › Permissions** (design 68; docs/notes/server/pwa.md:438-496) and the phone's "Tilgang for apper"
    category - **KEEP** per app; **LINK** ("N apper venter på godkjenning") only if the design wants it counted.
14. **Calls page call report** (S/handlers/calls.rs:1827-1872) - **KEEP**; the role steps already appear in the card.
15. **Locate's watch line** (S/handlers/locate.rs:351-360) - **KEEP**; the location-permission step lives in setup.
16. **Mobilnett page** (`/devices/{id}/mobile`) - **KEEP**; the roaming switch stays a trip step there.
17. **Advanced `#server-tls`, Sign-in page, Music page** - **KEEP**; their health belongs to C2's per-integration
    Attention row, not setup.
18. **Kid-side Innstillinger** (KidSettingsActivity; touch row read-only text, L/../res/values-nb/strings.xml:353) -
    **KEEP**; the kid gets no setup path, only the honest read-only line.
19. **docs/setup/android-settings.md, google-account.md** - **FOLD** their per-phone checklist into the setup status
    as "can't be checked" items the parent ticks; the docs stay as the reference the ⓘ links to.
20. **Settings "App push" diagnostics, Play install mode, pause switch** (preferences.xml:96-113) - **KEEP**, not setup.

## C. Gaps, ranked

1. **Notification listener has no on-phone path** (adb only: BadgeStore.kt:31-37, nb.json:1717). Required (badges, the
   music card, the glance). Fix: a setup row opening `ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS` (API 30+, with
   our component) through `systemPageIntent` in a pause, falling back to `ACTION_NOTIFICATION_LISTENER_SETTINGS`;
   already reported and warned, so only the phone row and the step are missing.
2. **The launcher's own location permission depends on the Locate setting** (LocateCommands.kt:105-125,
   ZoneFences.kt:97-108). With Location "Off" the Wi-Fi page's scan and the portal window's SSID fail (seen today on
   `jelly36`). Not reported, not shown. A design choice for the user (privacy: P-L3's "Off means nothing"): grant FINE
   to Vibb itself always (it never reports a fix while Off), or keep it tied and show the step; either way report it.
3. **Android's mobile-data switch off** is reported but shows grey and has no row on the phone. Approved to be orange
   with Attention (PLAN:46); needs a phone row (in a pause, Android's SIM page via `systemPageIntent`; no DO API to turn
   it on) and the step in both lists.
4. **Two step lists, no contract.** `setupStepsLeft` (5 kinds) and `setup_steps` (6 kinds) already disagree; the
   notification undercounts. Fix: one `server/testdata/setup_steps.json` (ids, gating, report fields, state rules), the
   phone computing and reporting the steps it can read (`setup: [{id, state}]`), the server adding its own (parent PIN,
   ticks) - the usual shared-contract pattern.
5. **Device Owner not reported explicitly.** Cheap: `device_owner: bool` in the report, an alarm-level step.
6. **Data Saver / background-data restriction unread.** `ConnectivityManager.getRestrictBackgroundStatus()` needs no
   permission; report it, step when restricted.
7. **Unreadable Android settings tracked nowhere** (touch feedback, ring/notification vibration, one-line lock clock,
   media on lock, Play services' links): the PWA has no list, the phone has rows for only two. Fix: "can't be checked"
   items the parent ticks once in the PWA (stored server-side per phone), each with its path; phone rows opening the
   right Android page where one exists (`ACTION_SOUND_SETTINGS`, the media row already does).
8. **Banners for message apps** (Element X at Default): detectable from the listener's `Ranking.importance`
   (GlanceReader.kt:104-109) for an allowed app's posts; a per-app row could open `ACTION_CHANNEL_NOTIFICATION_SETTINGS`
   for that app in a pause. Today docs only.
9. **Full-screen intent for Vibb itself unchecked** (14+): report `canUseFullScreenIntent()`; a step only if false.
10. **The assistant / call-filter / write-settings steps are said 2-3 times** (card, About ⓘ, Attention, Home Screen);
    the words audit (docs/review/2026-11-17-pwa-words-audit.md) wants it said once.
11. **The setup card hides behind `airplane_mode_v1`** and "Ikke nå" hides the whole set; optional items need a
    per-item "skip", required ones none.
12. **Google account presence unknown** (optional; Play installs show it indirectly). Leave out unless asked.

## Proposal: the one place

**PWA.** One sub-page of the phone, **Device › Oppsett** (`/devices/{id}/setup`, a row next to Om telefonen/Avansert,
its value "3 steg igjen" or "Ferdig"), built from one module (`setup_status.rs`, the old `airplane_mode::setup_steps`
moved and widened):
- top: "Åpne for oppsett" (the existing `start-pause`, `back=setup`), or "Åpnet til 17:10 · Lås igjen" while open;
- "Gjør på telefonen": the missing human steps in order - Foreldrekode (server, links to `/lock#parent-pin`),
  Varseltilgang, Mobildata, Bakgrunnstilkobling, Plassering (if gap 2 keeps it a step), Endre systeminnstillinger,
  Digital assistent, Wi-Fi i flymodus, Samtalefilter, Varsler fra Vibb One, Standardapper endret (an ack) - each one
  line, its path behind the ⓘ;
- "Sjekk selv" (can't be read): touch feedback, vibration, lock clock, media on lock, banners per message app, Play
  services' links - each ticked once by the parent, optional ones skippable;
- what the DO does itself appears only when it failed (not DO, not Home, VPN not set), else nothing.
On the summary: no card - **one orange Attention row "Oppsett: N steg igjen" -> `/devices/{id}/setup`**, nothing when
done (replaces the Oppsett card and the folded Attention rows of B4; About keeps its state rows without the how-to).

**Phone.** One `PreferenceCategory` **"Fullfør oppsett"** first in the PIN-gated Settings, holding only the missing
steps (the existing rows moved in, plus Varseltilgang, Mobildata, the manual items' Android pages), each tap opening
Android's own page through `systemPageIntent` with the existing `systemPageAccess` states ("sett på pause først", "et
øyeblikk", "ingen side"). The design-98 notification stays the entry point and counts the same list; its tap scrolls to
the category. Shown in full during a pause / "Åpne for oppsett"; outside one the category shows only the count and
"Åpne for oppsett fra appen" (Android's pages don't open then).

**Shared.** `server/testdata/setup_steps.json`, byte-identical in `launcher/app/src/test/resources/`: step ids, which
policy bits gate each, the report field each reads, the states; the phone reports `setup` (its readable steps), the
server adds its own and the ticks. Moves into it: `setupStepsLeft`, `setup_steps`, `SetupCard`, the hide route
(becomes per-item skip), `notification_access_note`'s step half, `mobile_network`'s data-off line, the About rows' step
texts. Sequenced after C1 (no capability gates) and alongside the copy round (design 112 + the words audit), as PLAN
already says.
