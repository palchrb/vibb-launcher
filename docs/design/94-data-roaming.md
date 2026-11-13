# Design 94: data roaming from the PWA - off and locked, or the phone's choice

Status: design, 2026-11-13. Both directories (§9). Migration `0143_data_roaming.sql` (design 93 takes 0142 on
`design-93`; recheck at merge). Built **after design 93 merges** (§9.1 lists the rebase points). Size **S**.

## 1. What and why

The user (2026-11-13, "Burde vi kunne slå av dataroaming i pwa?", then "Do it!"): a per-phone choice in the PWA,
**"Dataroaming: Av (låst)"** (the default) or **"Telefonens valg"**. Today nothing on the phone sets it; only catalog
downloads and the music app wait while roaming (`AppDownloadPlan.kt`, `MusicPlan.kt`).

- **Av (låst)**: the device owner sets `UserManager.DISALLOW_DATA_ROAMING`. Android turns data roaming off on every
  SIM and greys out its switch (§2).
- **Telefonens valg**: the restriction is cleared. Android's own switch decides. Clearing it **never turns roaming
  on**: Android leaves the setting where it was (§2.2).
- **The pause and the parent override lift it** (the standing rule, `launcher/CLAUDE.md` "Standing rule on
  restrictions"). Lifting only unlocks the switch.
- **The status report says when the phone is roaming**, and where. The PWA shows "Roamer i Sverige" on the summary
  and in the setting's group.

**Ship and plane networks.** Maritime and in-flight networks (Telenor Maritime, AeroMobile, OnAir and the like; the
international MCC **901**) are always roaming for every home SIM. They are expensive **even inside the EEA**: the EU
roaming regulation (2022/612) leaves non-terrestrial networks outside roam-like-at-home. A phone on a ferry to
Denmark or Germany can attach to the ship's network while the coast's network is still in range. "Av (låst)" covers
these networks too, because they are roaming. The PWA names them separately (§6.2), because the country is empty
there.

## 2. AOSP: what `DISALLOW_DATA_ROAMING` does

Read in `android14-release` and `android15-release`. They are the same where it matters; our phones are API 34+.

### 2.1 Who can set it, since when

- `UserManager.DISALLOW_DATA_ROAMING = "no_data_roaming"`, **since API 24** (`android-7.0.0_r1`
  `core/java/android/os/UserManager.java`: "This can only be set by device owners"). The 15 javadoc says a device
  owner can set it, or a profile owner on the primary user, or a profile owner of an organisation-owned profile on
  the parent. **Set by a device owner, it applies globally.**
- `services/core/java/com/android/server/pm/UserRestrictionsUtils.java` (15): the restriction is in
  `MAIN_USER_ONLY_RESTRICTIONS` (~l.179), so a profile owner can set it on the main user only (`canProfileOwnerChange`
  ~l.502). It is in `PROFILE_OWNER_ORGANIZATION_OWNED_PARENT_GLOBAL_RESTRICTIONS` (~l.292). `isGlobal` (~l.565) makes
  a device owner's setting global. We are the device owner, so our setting applies to the whole phone.

### 2.2 What setting it does, and what clearing it does

`UserRestrictionsUtils.applyUserRestriction` (15 ~l.656; 14 ~l.585), `case DISALLOW_DATA_ROAMING`: **only when it is
set** (`newValue == true`), it writes `Settings.Global.DATA_ROAMING<subId> = "0"` for every **active** subscription
(`getActiveSubscriptionInfoList`) and the bare `DATA_ROAMING = "0"`. **Clearing it writes nothing.** The comment above
the switch gives the reason: "we don't know the original value". So a lift unlocks and never turns roaming on. The
user's rule holds by Android's own design, and we add nothing for it.

`applyUserRestriction` runs only when the restriction **changes** (`applyUserRestrictions` compares the old and new
bundles). Setting it again while it is already set does nothing.

### 2.3 The lock: nobody can turn it back on while it is set

- `UserRestrictionsUtils.isSettingRestrictedForUser` (15 ~l.895): any `DATA_ROAMING*` write of anything but `"0"`
  counts as restricted while `DISALLOW_DATA_ROAMING` is set. There is **no `SYSTEM_UID` exemption** here, unlike the
  location and DNS cases above it.
- `packages/SettingsProvider/.../SettingsProvider.java` `mutateGlobalSetting` (15 ~l.1525): a restricted write
  `return false`, which refuses it **silently**. This covers adb (`settings put global data_roaming1 1`), the Settings
  app, and telephony itself. One example is `DataSettingsManager.setDefaultDataRoamingEnabled` after a carrier config
  change, when a carrier's default is "roaming on" (`data/DataSettingsManager.java` ~l.578).
- **Android's Settings switch**: `packages/apps/Settings/res/xml/mobile_network_settings.xml`, `button_roaming_key`,
  is a `RestrictedSwitchPreference` with `settings:userRestriction="no_data_roaming"`. It shows as disabled by the
  admin, and a tap opens Android's "controlled by admin" dialog. `RoamingPreferenceController.update` (~l.137) leaves a
  switch disabled by the admin alone.

### 2.4 Two gaps the launcher must close (or state)

1. **A roaming data session that is already running is not torn down at once.** In the data stack from Android 13
   on, nothing watches `DATA_ROAMING` in Settings:
   - `DataSettingsManager` observes only `DEVICE_PROVISIONED` and `DEVICE_PROVISIONING_MOBILE_DATA_ENABLED`
     (15 ~l.294).
   - The tear-down for `ROAMING_DISABLED` runs only through `onDataRoamingEnabledChanged`, a callback fired by
     `DataSettingsManager.setDataRoamingEnabledInternal` (its own setter, which Settings' switch uses through
     `TelephonyManager.setDataRoamingEnabled`). The restriction's direct Settings write never fires it
     (`DataNetworkController.java` ~l.955, ~l.2047).
   - A **new** data network is refused at once: its setup reads the setting (`evaluateNetworkRequest`, ~l.1662). An
     existing one goes at its next re-evaluation: a service-state or TAC change, a RAT change, the end of a voice
     call, a retry, or SIM loaded (`DataEvaluationReason`).
   - No public device-owner API forces it sooner. `TelephonyManager.setDataRoamingEnabled` needs
     `MODIFY_PHONE_STATE`. `DevicePolicyManager.setGlobalSetting(DATA_ROAMING)` is on the allow list (DPMS ~l.707),
     but it is the same Settings write.
   - So: with "Av" set before the trip (the default) no roaming session ever starts. The gap is only a session that
     started while unlocked (a pause, or "Telefonens valg") and is still running when the lock comes back. It ends at
     the first cell or area change. A phone sitting still in one hotel room can keep it for a while. **The report
     shows it** (§5: `locked` with `data_active`), and the PWA says how to end it now: airplane mode on and off. We
     state this gap; we do not hide it. To check on the emulator (§8).
2. **A SIM put in after the lock was set has no `DATA_ROAMING<subId>` key.**
   - `GlobalSettingsHelper.getBoolean(..., default)` (`telephony/GlobalSettingsHelper.java` ~l.43) then reads the
     carrier's default (`isDefaultDataRoamingEnabled`: `ro.com.android.dataroaming` or carrier config). Some carriers
     default it to **on**.
   - Telephony's own attempt to write that default is refused (§2.3). But the absent key still *reads* as on.
   - **Fix:** on every `apply()` while the lock should be on, the launcher reads `isDataRoamingEnabled` for each
     active subscription. If any reads `true` (possible only from an absent key), it **clears and re-adds** the
     restriction. The re-add runs `applyUserRestriction` again, which writes `"0"` for every active SIM. Nothing can
     turn roaming on between the two calls: the clear writes nothing. Pure decision `dataRoamingRelock(...)`.

### 2.5 What the phone can read, and the permissions

All of these are in `TelephonyManager` (15), on `createForSubscriptionId(SubscriptionManager.getDefaultDataSubscriptionId())`:

- `isNetworkRoaming()`, `getNetworkCountryIso()` (lowercase ISO-3166 alpha-2 or `""`) and `getNetworkOperator()`
  (MCC+MNC): no permission.
- `isDataRoamingEnabled()`: `ACCESS_NETWORK_STATE`, `READ_PHONE_STATE` or `READ_BASIC_PHONE_STATE`. The manifest has
  the first two.
- `getServiceState()` needs location as well, so it is **not used**.
- MCC 901 has no row in `MccTable` (its table ends at 750), so the country is `""` on a ship or plane network. The
  server reads 901 from `network` (§6.2).

## 3. The policy

Top-level `data_roaming`, **always sent**: `"off"` (Av, låst) or `"phone"` (Telefonens valg).

- **Server**: `device_policy.data_roaming TEXT NOT NULL DEFAULT 'off' CHECK (data_roaming IN ('off','phone'))`
  (migration 0143). `DevicePolicy.data_roaming`, `PolicyResponse.data_roaming`. Pinned by
  `policy_json_keys_snapshot`.
- **Launcher**: `PolicyResponse.dataRoaming: String? = null` (`PolicyResponseCompatTest`). The pure rule, in
  `server/EnforcementPlan.kt` beside `bluetoothSharingRestricted`:

  ```kotlin
  fun dataRoamingLocked(value: String?, overrideActive: Boolean): Boolean = when (value) {
      null, "phone" -> false          // an older server never set it; the phone's choice
      else -> !overrideActive         // "off", and fail closed for a value this build doesn't know
  }
  ```

  It holds whether or not the phone is "managed" in hardening's sense. The parent's choice is explicit and per
  phone, and roaming costs money on a calls-only phone too.
- `AppEnforcer.apply()` sets it with `setRestriction(..., UserManager.DISALLOW_DATA_ROAMING, locked)` next to
  `DISALLOW_BLUETOOTH_SHARING`, then the relock check of §2.4.2.
- `LastEnforcedPlan` gains `dataRoaming: String = "off"`. A fallback keeps the lock, and an older record without the
  field fails closed, like the rest of the fallback.
- **No server-side lift.** `overrideActive` (`OfflineOverride.isActive() || RestrictionsPause.isActive()`) is the one
  lift. That includes the pause the parent starts from the PWA ("Åpne for oppsett" since design 93).

## 4. The capability

`data_roaming_v1` (`server::data_roaming::CAPABILITY`, the launcher's `DATA_ROAMING_CAPABILITY`, appended to
`STATUS_CAPABILITIES`). The phone claims it with the report field. Until the latest report claims it, the PWA's group
says the phone does not do this yet (§6.1). An older launcher ignores the policy key (`ignoreUnknownKeys`).

## 5. The report

`StatusReportRequest.dataRoaming`, sent in every report by a launcher with the capability:

```json
"data_roaming": {"locked": true, "roaming": true, "country": "se", "network": "24001", "enabled": false, "data_active": false}
```

- `locked`: `UserManager.hasUserRestriction(DISALLOW_DATA_ROAMING)`, as Android has it now.
- `roaming`: `isNetworkRoaming()` on the default data SIM. `false` with no SIM or no service.
- `country`, `network`: the network's country and MCC+MNC, **only while `roaming`**, otherwise `null`. No home
  location leaves the phone this way, and `""` becomes `null`.
- `enabled`: the data SIM's `isDataRoamingEnabled()`, or `null` when it can't be read (no SIM, an exception).
- `data_active`: the default network is cellular and not `NET_CAPABILITY_NOT_ROAMING`, which is the
  `AppDownloads.kt` read. This means roaming data is in use now. With `locked` it is §2.4.1's gap.

**Server**: `data_roaming::Report::clean`, re-serialised field by field:

- `country` must be `[a-z]{2}`, `network` must be `[0-9]{5,6}`, otherwise `null`.
- Booleans that aren't booleans become the field's safe reading: `false`, and `null` for `enabled`.
- A malformed object becomes `NULL`; the report is never refused.
- Stored in `device_status.data_roaming_json` (0143). Absent means an older launcher (`NULL`).
- `intake_limits` keeps its keys.

## 6. The PWA

### 6.1 Where the setting lives: Advanced › Mobile network

The device's Innstillinger list has no network page. The Wi-Fi and Bluetooth rows on Home Screen › Quick Controls are
the kid's own buttons. Advanced › Restrictions holds the hardening switches that **only the server lifts**, and this
one is lifted by the pause. So it gets **its own section on Advanced, `#data-roaming`, right after `#restrictions`**
(after design 93's `#setup`), with its own form, footer and flash. (Open question 2 has the alternative.)

- **Row "Dataroaming"** / "Data roaming": a `select.pick` with `data-autosave` (the `device_home.html` pattern). The
  options are **"Av (låst)" / "Telefonens valg"** (en "Off (locked)" / "Phone's choice").
- **The save**: `POST /devices/{id}/data-roaming` (full auth, `data_roaming=off|phone`).
  - An unknown value is refused with a flash in the group, and nothing is written. An unknown device is 404.
  - Every save writes a `settings_log` line and sends a `command_notify` nudge.
  - It lands on `/devices/{id}/advanced#data-roaming`. With JS, `scroll-restore.js` keeps the exact position, so **a
    save never jumps to the top** (pinned by the redirect table in `tests::device_pages`). Without JS, the
    `<noscript>` Save button does the same.
- **The phone's state** (a `note_row` under the picker, from the latest report; typed `data_roaming::State`, words
  through the catalogs):

  | Report | nb | en |
  |---|---|---|
  | no capability, phone reported | Virker etter at Vibb One er oppdatert | Works after Vibb One is updated |
  | `locked` | Av og låst på telefonen | Off and locked on the phone |
  | choice `off`, not `locked` (a pause) | Låst opp mens telefonen er åpnet for oppsett | Unlocked while the phone is open for setup |
  | choice `phone`, `enabled` true / false | Telefonens valg: på / av | Phone's choice: on / off |
  | `roaming` (an extra row) | Roamer i Sverige | Roaming in Sweden |
  | `locked` and `data_active` (warning, §2.4.1) | Bruker fortsatt roamingdata fra før låsen. Slå flymodus på og av for å stoppe det. | Still using roaming data from before the lock. Turn airplane mode on and off to stop it. |

- **Footer**: "Av slår av dataroaming og låser Androids bryter. Skips- og flynett er alltid roaming og kan koste mye,
  også i EØS." / "Off turns data roaming off and locks Android's switch. Ship and plane networks are always roaming
  and can cost a lot, even in the EEA."
- **ⓘ**: Åpne for oppsett lifts the lock and does not turn roaming on. Calls and SMS abroad are not affected. An MMS
  waits for Wi-Fi or home (design 77's engine retries it).

### 6.2 "Roamer i Sverige": the words and where they show

`data_roaming::words(lang, report)`:

| Case | nb | en | Tone |
|---|---|---|---|
| `network` starts with `901` | Roamer på et skips- eller flynett | Roaming on a ship or plane network | Warn |
| `country` in `data_roaming::NAMED` | Roamer i {land} | Roaming in {country} | Plain inside the EEA, Warn outside |
| any other `country` | Roamer (US) | Roaming (US) | Warn |
| `roaming`, no country | Roamer | Roaming | Warn |

- `NAMED` holds the EEA (EU 27, IS, LI, NO) plus `gb` and `ch`. Their names are in the catalogs as
  `data_roaming.country.<cc>`, en and nb. A code outside the list is shown in upper case. There is no country table to
  maintain beyond the 32 rows.
- **The summary's header** (`phone_status::header`): the second line gains this part last ("Test siden 22:19 · 80 % ·
  Roamer i Sverige"), from `Facts::roaming`. It shows only on the in-touch lines; a phone that is off, offline or
  without contact keeps its first line alone (a stale place is not a fact). The Devices row stays as it is.
- Advanced's state row (§6.1) uses the same words.

## 7. The lift rules, in one place

| State | Restriction | Android's switch |
|---|---|---|
| `"off"`, no pause or override | set | off on every SIM, greyed out, re-locked if a new SIM reads on (§2.4.2) |
| `"off"`, pause or override running | cleared | as it was (off). The kid or parent may turn it on in Settings during the pause |
| pause or override ends | set again | `"0"` written again. A session started in the pause runs until the next re-evaluation (§2.4.1) |
| `"phone"` | cleared | as it was; never turned on by us |
| older server (key absent) | cleared | as it was |
| a value this build doesn't know | set (fail closed) | off |

## 8. Tests (lean)

### 8.1 The contract file

`server/testdata/data_roaming.json`, byte-identical in `launcher/app/src/test/resources/`:

```json
{
  "capability": "data_roaming_v1",
  "policy": [{"data_roaming": "off"}, {"data_roaming": "phone"}],
  "locked": [
    {"value": "off", "override": false, "locked": true},
    {"value": "off", "override": true, "locked": false},
    {"value": "phone", "override": false, "locked": false},
    {"value": null, "override": false, "locked": false},
    {"value": "later", "override": false, "locked": true},
    {"value": "later", "override": true, "locked": false}
  ],
  "report": {"data_roaming": {"locked": true, "roaming": true, "country": "se", "network": "24001", "enabled": false, "data_active": false}},
  "clean": [
    {"in": {"locked": true, "roaming": true, "country": "SE1", "network": "9011", "enabled": "yes", "data_active": false},
     "stored": {"locked": true, "roaming": true, "country": null, "network": null, "enabled": null, "data_active": false}}
  ]
}
```

- **Server** `tests::data_roaming::the_contract`: saves each choice through the PWA route and produces each `policy`
  row from the served policy. It posts `report` and checks what is stored. It runs the `clean` rows and compares the
  copies.
- **Launcher** `DataRoamingTest`: decodes each `policy` row into `PolicyResponse`, runs `dataRoamingLocked` over every
  `locked` row, encodes `report` exactly from the DTO, checks `capability` is in `STATUS_CAPABILITIES`, and compares
  the copies.
- The workflows' path lists (`launcher.yml`, `server-ci.yml`) and the root `CLAUDE.md` contract paragraph gain the
  file.

### 8.2 Other tests

- **Snapshots**: `policy_json_keys_snapshot` and `PolicyResponseCompatTest` gain `data_roaming`.
- **Launcher unit tests**:
  - `dataRoamingRelock` has three rows: locked with one SIM reading on means relock; locked and all off means no
    relock; unlocked means never.
  - `LastEnforcedPlan` round trip: one assert that it keeps `dataRoaming`, and that an old record reads `"off"`.
- **Server unit tests** (`data_roaming::tests`): §6.2's four word cases, the EEA tone, and the `901` prefix.
- **Server page tests**: the Advanced group's state rows (no capability, locked, unlocked in a pause, the warning), the
  summary part, and the redirect row for `#data-roaming`.

### 8.3 On the emulator and the phone

- **Emulator** (`docs/testing/emulator.md`; the console's `gsm data roaming` / `gsm voice roaming`):
  - with "Av" set: Settings' switch is greyed out and data stays down;
  - start a pause, turn roaming on, end the pause: check §2.4.1's gap, with `dumpsys telephony` showing the data
    network until a re-evaluation;
  - check that the report and the summary line read right.
- **Jelly Star**: the switch shows "controlled by admin". Also check the second-SIM case (§2.4.2) if an eSIM is to
  hand; otherwise mark it "[needs device test]".

## 9. Steps

1. **Both directories, one commit** (the root `CLAUDE.md`: an API change goes into both directories at once):
   - migration 0143;
   - the policy key and its intake;
   - the report intake and storage (`data_roaming.rs`);
   - the launcher's DTOs, `dataRoamingLocked`, `apply()`, the relock check, the report, `LastEnforcedPlan` and the
     capability;
   - the contract file, the workflow path lists, and the root `CLAUDE.md` paragraph.
2. **Server PWA**: the Advanced section and its save route, the summary part, en and nb, and
   `docs/notes/server/pwa.md` plus `hardening-and-pins.md` (a pointer).
3. **Launcher notes** (`docs/notes/launcher/hardening.md`: the restriction and its lift; and the standing-rule list
   needs no exception), then the emulator check.

### 9.1 Rebase points after design 93

- **Migration**: ours is 0143; 0142 is 93's.
- **`policy_json_keys_snapshot`** (93 changes the time-rule DTOs; we add one top-level key) and
  **`PolicyResponseCompatTest`**: re-take the snapshot after the rebase.
- **`StatusCapabilities.kt`**: both append; keep both.
- **`device_advanced.html`**: 93 puts `#setup` first; our section goes after `#restrictions`.
- **`device_detail.html` and `tests::summary`**: 93 rewrites the summary. Our change is in `phone_status::header`,
  plus whatever field 93's template reads.
- **i18n `en.json`/`nb.json`, `CLAUDE.md`, `launcher.yml`, `server-ci.yml`**: both add lines; keep both.
- **Wording**: 93 renames the pause "Åpne for oppsett", and §6.1's words use that name.

## 10. Risks

- **§2.4.1**: a roaming session started while unlocked can outlive the relock. We show it and give the fix (airplane
  on and off); we don't claim a cut that Android doesn't make.
- **§2.4.2**: a new SIM's absent key. Handled by the relock check at the next `apply()` (sync, the pause's end, a
  policy). Between the SIM going in and that check, the carrier default rules.
- **The roaming flag is the carrier's**: carrier config can mark a national-roaming partner as home, or the other way
  round. `getNetworkCountryIso` comes from `LocaleTracker` and can lag a border crossing. The words are "at the last
  report".
- **MMS abroad** needs mobile data, so with "Av" it waits. SMS and voice roaming are not touched (voice roaming costs
  too outside the EEA; that is out of scope).
- **Existing phones become "Av" at the upgrade** (the column default; open question 1). A phone abroad that day loses
  roaming data until the parent picks "Telefonens valg".

## 11. Open questions

1. **Existing phones get "Av (låst)" at the upgrade.** That is the user's default, applied to every phone, not only
   new ones. Recommended: yes, with a line in the release notes. This is a visible change for a family abroad on
   upgrade day.
2. **Placement**: its own section on Advanced (recommended, §6.1), or a row in Home Screen › Quick Controls beside
   Wi-Fi and Bluetooth (seen more often, but that group is the kid's buttons).
3. **A push when the phone starts roaming** (or attaches to a 901 network)? Not asked for. Recommended: not now; the
   summary line is enough.
