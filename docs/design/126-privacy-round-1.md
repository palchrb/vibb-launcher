# 126 - Privacy round, part 1

Status: **design, revision 1** (2026-11-22). Asked for and approved by the user on 2026-11-22 (A3, A4, A5, A7, B1-B4,
C1). Not in this round (decided elsewhere): per-app network and per-app permission switches (design 127, Nett),
auto-reboot and deny-by-default (declined), HeliBoard and the per-app DNS report (later). Read against `main`
b75371c22. Nothing here was built or run: every "verified" below is read in the code, in AOSP's sources
(`android14/15/16-release` and 17 of `UserManager`, `DevicePolicyManagerService` 14/16, Wi-Fi's `WifiConfigManager`
16), in Chromium's policy templates, or in the SDK's `api-versions.xml` (platform 37.0). "Assumed" is marked where it
is, and each assumption has a line in the emulator or Jelly Star check (§10).

## 0. Short answer

| Item | What | Mechanism | Parent sees | Pause lifts it | Size |
|---|---|---|---|---|---|
| A4 | No screen content to Google's on-device AI or the assistant | `DISALLOW_CONTENT_CAPTURE`, `DISALLOW_CONTENT_SUGGESTIONS` (29+), `DISALLOW_ASSIST_CONTENT` (35+, gated) | nothing (every phone, no switch) | no - hardening | S, launcher |
| A3 | Chrome sends no metrics, browsing data or sign-in to Google | Five keys in Chrome's managed config, on every managed phone, the role's lockdown or not | nothing | no - not a restriction on use | S, launcher |
| A5 | Block 2G | `DISALLOW_CELLULAR_2G` (34), a per-phone switch in Advanced › Restrictions, on by default | "Blokker 2G" in its own group, its (i) | **yes** - see §3.4, the one place this note differs from the brief | M, both |
| A7 | A fresh MAC on open Wi-Fi | `WifiConfiguration.RANDOMIZATION_NON_PERSISTENT` on the open networks Vibb adds | nothing | n/a - a network setting, not a restriction | S, launcher |
| B1-B4 | Four Google switches the phone can't set or read | Four optional parent-ticked setup steps (design 114) | quiet rows under "Valgfritt" | n/a | M, both + contract |
| C1 | "Reklame og sporing" on for new phones | A per-phone list override written when the phone is created | the list on, on the new phone's Web Filter page | as the filter (yes) | S, server |

One question for the user (§11): whether "Blokker 2G" stays on abroad.

## 1. A4 - no screen content for AI

### 1.1 Mechanism (verified)

- `UserManager.DISALLOW_CONTENT_CAPTURE` (`no_content_capture`, API 29) and `DISALLOW_CONTENT_SUGGESTIONS`
  (`no_content_suggestions`, API 29): "a device owner and a profile owner can set this restriction. When it is set by a
  device owner ... it disables the primary user's screen from being captured for artificial intelligence purposes" /
  "from receiving content suggestions for selections based on the contents of their screen". Identical in the 14, 15,
  16 and 17 sources.
- `DISALLOW_ASSIST_CONTENT` (`no_assist_content`) is **API 35** (`api-versions.xml`: `since="35"`; absent from
  `um-android14-release.java`): "assist content is disallowed from being sent to a privileged app such as the Assistant
  app ... screenshots and information about an app ... When it is set by a device owner, it disables the assist
  contextual data on the entire device." Present unchanged in 16 and 17.
- On 14 an unknown key from a device owner is dropped silently (`DevicePolicyManagerService` 14, `setUserRestriction`:
  `if (!UserRestrictionsUtils.isValidRestriction(key)) return;` on the device-owner path), so nothing breaks there, but
  the code still gates on `Build.VERSION.SDK_INT >= 35` (the constant is inlined, so lint's NewApi doesn't see it; the
  gate is the rule, `CLAUDE.md` "Supported Android").
- Vibb's own assistant (design 98, Flymodus) already reads nothing: `VibbAssistantService` calls
  `setDisabledShowContext(SHOW_WITH_ASSIST or SHOW_WITH_SCREENSHOT)` and every `VibbSessionService.onHandleAssist*` is
  empty (verified). So the restriction changes nothing for Vibb as the assistant; it matters on a phone where Gemini or
  Google holds the role (Flymodus off).

### 1.2 Where it goes

Three new entries in `HardeningRestriction` (`L/server/Hardening.kt`), launcher-only like `SET_WALLPAPER` (no server
switch, `value` always `null`, default on): `CONTENT_CAPTURE(true)`, `CONTENT_SUGGESTIONS(true)`, `ASSIST_CONTENT(true)`.
`hardeningPlan` sets them while `hardeningManaged` and clears them unmanaged, as every hardening entry. The enum stays
pure; `AppEnforcer.userManagerKey()` becomes `String?` and answers `null` for `ASSIST_CONTENT` below 35, which
`applyHardening` and `clearHardening` skip (one `minSdk` field on the enum, `ASSIST_CONTENT(true, minSdk = 35)`, read by a
pure `hardeningKeyApplies(restriction, sdk)` that `HardeningTest` covers).

### 1.3 Pause: not lifted (hardening)

They block only data flows from the screen to Google's services; nothing the parent does in a pause or in "Åpne for
oppsett" needs them, and lifting them would let Android System Intelligence capture the screens of a setup. So they
join `hardeningPlan`'s never-lifted set (the second exception to the standing rule, docs/notes/launcher/hardening.md).

### 1.4 Cost

Assumed, from what the services do on a Pixel: Overview's "Select"/text and image actions on a recent app, Circle to
Search and "ask about this screen" stop; Smart Reply in notifications (the notification assistant, not content capture)
and the keyboard are untouched. On the Jelly Star the content-capture service may not be configured at all (then it
costs nothing and protects nothing until an update adds one). Nothing the kid uses today.

## 2. A3 - Chrome's privacy keys

### 2.1 What exists (verified)

`L/server/BrowserConfig.kt` owns every browser's managed config. `browserConfigPlan(filterOn, lockdownHosts,
installed)` (`L/logins/LoginBrowser.kt`) gives each installed listed browser design 67's `DnsOverHttpsMode = off` while
the filter is on, and Chrome, while the login role's lockdown is in force (design 102/119, not in a pause), the lockdown
`chromeLockdown(hosts)`: `URLBlocklist`/`URLAllowlist`, `IncognitoModeAvailability 1`, `BrowserSignin 0`,
`DefaultSearchProviderEnabled false`, `SearchSuggestEnabled false`, `ContextualSearchEnabled false`,
`NTPContentSuggestionsEnabled false`, `PromotionalTabsEnabled false`, the dinosaur, `TosDialogBehavior 2`,
`DownloadRestrictions 3`, passwords and cards off, printing off, SafeSearch, YouTube restrict, dev tools off.

### 2.2 The keys (each checked against Chromium's policy template)

| Key | Value | Android | What it stops | What it costs |
|---|---|---|---|---|
| `MetricsReportingEnabled` | `false` | 110+ (verified) | usage and crash reports to Google | Google gets no crash reports from this Chrome |
| `UrlKeyedAnonymizedDataCollectionEnabled` | `false` | 70+ (verified) | "Make searches and browsing better": the URLs visited, sent to Google | nothing visible |
| `NetworkPredictionOptions` | `2` (never) | 38+ (verified) | preloading pages and DNS ahead of a tap (with Google's prefetch proxy) | a page starts loading on the tap, not before |
| `BrowserSignin` | `0` (off) | 70+ (verified) | signing in to Chrome - and with it Sync - with the phone's Google account, which is the family's Play account (`docs/setup/google-account.md`) | no Chrome sign-in or Sync; signing in to Google *sites* still works, so logins are unaffected (the template says so) |
| `SearchSuggestEnabled` | `false` | 30+ (verified) | each keystroke in the address bar sent to the search engine | no suggestions while typing |
| `SafeBrowsingProtectionLevel` | `1` (standard) | **assumed** 87+ (not in the research folder; the gate reads it in `chrome://policy`) | Enhanced protection, which sends the URLs visited to Google; Standard (hash prefixes) stays | none; an older Chrome ignoring it is harmless |

Not used, and why:
- `SyncDisabled`: **not on Android** (`future_on: android`, verified); `BrowserSignin 0` already makes Sync unavailable.
- `BlockThirdPartyCookies`: a login page's third-party frames (an SSO's silent token, a captcha's score) can need them,
  and the lockdown's `URLBlocklist` already stops every frame from a host not allowed; the DNS list (C1) takes the rest.
  Breaking a login is the one thing A3 must not do.
- `PrivacySandbox*`: deprecated in the template (verified); Google retired the Privacy Sandbox.
- `DefaultGeolocationSetting`, `TranslateEnabled`: no Google telemetry on their own; left alone to keep the set small.

### 2.3 Where they go

A new `CHROME_PRIVACY: Map<String, Any>` in `LoginBrowser.kt` (the six rows above), written for Chrome's four packages
(`com.android.chrome`, `.beta`, `.dev`, `.canary` - the other listed browsers have their own policy names and get only
the DoH key) **whenever the phone is managed** (`hardeningManaged`), the role on or off, the lockdown in force or lifted,
the filter on or off. `browserConfigPlan` gains `managed: Boolean`: a Chrome package's bundle is `privacy` (when
managed) + the lockdown (when in force) + DoH (when the filter is on); `SearchSuggestEnabled` and `BrowserSignin` are
in both maps with the same value, so the merge is plain. An unmanaged phone gets none (the bundle is emptied as today).
The role's readback (`sameBundle`) compares the merged bundle, so nothing in `BrowserConfig.apply` changes beyond the
call. `BrowserSignin` has `dynamic_refresh: false`: it takes hold at Chrome's next start, which the role's first
tightening (Chrome hidden and shown) gives anyway.

### 2.4 Pause: not lifted

They restrict nothing anyone uses in Chrome; a pause that loosens the lockdown (Chrome a full browser for the parent)
keeps them. Unmanaging clears them.

### 2.5 Cost

A ticked Chrome (a free app, design 119) also gets no address-bar suggestions and no Chrome sign-in. That is the point
(the sign-in would be the family's Google account); said nowhere in the PWA, since it is not a choice.

## 3. A5 - block 2G

### 3.1 Mechanism (verified)

`UserManager.DISALLOW_CELLULAR_2G` (`no_cellular_2g`, **API 34**, so no gate): "can only be set by a device owner or a
profile owner of an organization-owned managed profile ... applies globally on the device". 16 and 17's text, identical:
"Cellular connectivity loss ... occurs if the device is in an area where only 2g networks are available. **Emergency
calls are an exception and are never impacted. The device will still scan for and connect to a 2g network for emergency
calls.**" 14's text says the same in other words ("will prevent the device from scanning for or connecting to 2g
networks, except in the case of an emergency"). Android 16's Advanced Protection uses the same restriction
(`AdvancedProtectionService`'s `FEATURE_ID_DISALLOW_CELLULAR_2G`). Android's own "Allow 2G" switch (Network & internet ›
SIMs) shows greyed, "controlled by admin" (assumed from AOSP Settings; checked in the gate).

What the phone can't see: whether its modem honours it. Telephony applies it through the allowed-network-types reason
`ENABLE_2G`, which needs radio HAL 1.6+; the public API has no way to read that (`ALLOWED_NETWORK_TYPES_REASON_ENABLE_2G`
and `CAPABILITY_USES_ALLOWED_NETWORK_TYPES_BITMASK` are hidden; verified against `api-versions.xml`). On a modem that
can't, Android hides its own switch and nothing happens. The Jelly Star's MediaTek on Android 16 is assumed to support
it (the gate looks for the greyed switch). So no report: the switch is the parent's, and the phone has nothing honest to
add.

### 3.2 Policy and storage

- Migration (next free number, today `0165_block_2g.sql`; design 127 may take it first):
  `ALTER TABLE device_policy ADD COLUMN block_2g INTEGER NOT NULL DEFAULT 1;` - on for every phone, existing ones too
  (the user: "on by default"; only emulators run Vibb, `CLAUDE.md` "No backwards compatibility").
- Policy: a new top-level `block_2g: bool`, like `mobile_settings_locked` - **not** inside `hardening`, whose launcher
  DTO says "never lifted by the override or pause" (A5 is, §3.4). Pinned by `policy_json_keys_snapshot`
  (`S/tests/device_api.rs`) and `PolicyResponseCompatTest`; both sides in one commit. No contract file: a bool with
  no rows, like `mobile_settings_locked`'s own key.
- Launcher: `PolicyResponse.block2g: Boolean? = null`; pure `cellular2gBlocked(value: Boolean?, overrideActive:
  Boolean) = value == true && !overrideActive` in `MobileNetwork.kt` beside `mobileSettingsLocked` (a missing key sets
  nothing, the same rule); applied in `AppEnforcer` right after `applyMobileSettingsLock` with `setRestriction(dpm,
  admin, UserManager.DISALLOW_CELLULAR_2G, ...)`, whether or not the phone is managed in hardening's sense (the parent's
  explicit per-phone switch, as roaming). Unenrolled/no policy: cleared.

### 3.3 The PWA

Advanced › Restrictions (`T/device_advanced.html`) gets a third group in the same form (so `update_hardening`'s "a
missing box is off" keeps working), after the debugging group: one switch, `block_2g`, and the group's own (i). Its own
group because the first group's footer says "En pause eller foreldre-PIN-en opphever ikke disse", which isn't true of
it. `update_hardening` binds `on("block_2g")`; the save lands on `#restrictions` as now (no jump to the top).

| key | nb | en |
|---|---|---|
| `device.advanced.block_2g` | Blokker 2G | Block 2G |
| `device.advanced.block_2g_info_label` (the (i)'s spoken name) | Om 2G | About 2G |
| `device.advanced.block_2g_info` | 2G kan avlyttes med falske basestasjoner. Nødsamtaler går alltid. Der det bare finnes 2G, eller i utlandet uten 4G-samtaler, kan telefonen miste dekning. En pause opphever den. | 2G can be intercepted by fake base stations. Emergency calls always work. Where there's only 2G, or abroad without 4G calling, the phone can lose service. A pause lifts it. |

Checked against "Words in the UI": a label of two words, no sub-line, the why and the cost behind the (i) alone; the
parent's choice (on or off) is never orange and has no line anywhere else.

### 3.4 Pause: **lifted** (not hardening) - a decision against the brief's guess

It is a hardening switch in spirit, but its failure is "no network": a phone in a 2G-only valley, or abroad on an
operator whose calls fall back to 2G, can't reach the server, so the PWA's switch can't reach it either. The pause and
the offline override are the on-phone ways out (the parent's PIN, no network needed), so they must lift it. The
protection lost during a pause is small and temporary. Same rule as `DISALLOW_DATA_ROAMING` and
`DISALLOW_CONFIG_MOBILE_NETWORKS` (the standing rule; "Åpne for oppsett" lifts it too).

### 3.5 Cost

- Where the operator's voice still falls back to 2G (CSFB) and the phone has no working VoLTE on that network, **calls
  fail** with 2G blocked; SMS over LTE keeps working. In Norway 3G is gone and 2G is being shut down (assumed, from the
  operators' announcements), so at home a phone that calls today already uses VoLTE; the risk is roaming, where VoLTE
  roaming is often not offered and 2G is the fallback. That is §11's question.
- Emergency calls are never affected (verified, AOSP's own words).
- Nothing else: data on 2G is too slow to matter.

## 4. A7 - a fresh MAC on open Wi-Fi

### 4.1 What the device owner can set (verified, `WifiConfigManager` 16)

- The kid joins Wi-Fi only through Vibb's own page (`KidSettingsActivity` › `WifiNetworksActivity` ›
  `QuickControls.connectToWifiNetwork`, which builds a `WifiConfiguration` and calls `addNetworkPrivileged`; Android's
  Quick Settings panel stays off in lock task, and Settings is suspended outside a window). So every network the kid
  joins is created by Vibb; one the parent joins in Android's Settings during a window is not (below).
- `WifiConfiguration.setMacRandomizationSetting(RANDOMIZATION_NON_PERSISTENT)` (API 33; the field and constants on
  `WifiConfiguration` since 33 in `api-versions.xml`) is accepted from a caller that is an admin and the network's
  creator: `addOrUpdateNetworkInternal` refuses a changed MAC setting unless the caller has NETWORK_SETTINGS, is the
  setup wizard, ... "or be an admin updating their own network" (`isAdmin(uid, packageName) && uid ==
  newInternalConfig.creatorUid`). A new network's creator is the caller, so Vibb's adds pass.
- **Not possible**: changing a network someone else created - one saved in Android's Settings during a setup window,
  the setup QR's network (created by ManagedProvisioning). Those keep Android's default.
- What `NON_PERSISTENT` does (`shouldUseNonPersistentRandomization`, `updateRandomizedMacIfNeeded`): a new random
  address before a connection when the old one has expired - 4 hours after the last disconnect
  (`NON_PERSISTENT_MAC_WAIT_AFTER_DISCONNECT_MS`), or at the DHCP lease (clamped 30 min to 24 h), and at least every
  24 h. **So not literally per connection**: a quick reconnect keeps the address; a return the next day gets a new one.
  Android's default for an open network (`RANDOMIZATION_AUTO`) is a *persistent* random address per SSID - not the
  hardware address, but the same one every visit, which a chain's hotspots can follow for months. It goes
  non-persistent by itself only on an OEM allowlist or with `config_wifiAllowNonPersistentMacRandomizationOnOpenSsids`
  after a captive-portal-free connection (verified), which a kid's phone can't count on.

### 4.2 Where it goes

`connectToWifiNetwork`: when the network is added open (`!secured`, the `KeyMgmt.NONE` branch), set
`macRandomizationSetting` to `RANDOMIZATION_NON_PERSISTENT` before `addNetworkPrivileged`, through a pure
`joinMacSetting(secured: Boolean): Int?` (`null` = leave Android's default) with its test. Secured networks keep
Android's persistent per-network address: a home or school network that sees a new device every day would fill its
DHCP table and its parental logs, and the privacy gain at home is nil. A network Vibb saved before this version keeps
its old setting (no backwards compatibility on the phones).

### 4.3 Pause, cost

Not a restriction: nothing to lift. Cost: a café's or hotel's sign-in page (design 102's window) may ask again after a
night away; a quick reconnect doesn't. The parent sees nothing; the kid sees only that.

Android 14-17: the API and the Wi-Fi rule are the same since 13 (read in 16; 14, 15 and 17 assumed - Wi-Fi is a
mainline module and the admin clause dates from 13; the gate checks 34 and 37's images).

## 5. B1-B4 - four optional setup steps

### 5.1 Can the phone read them? No, for all four - the parent ticks

| Step | What | Readable by Vibb? |
|---|---|---|
| B1 `number_verification` | Google's automatic phone-number verification off, before the SIM goes in | No. Play services' own state, no public API. |
| B2 `ad_id` | The advertising ID deleted | No. Only Play services' `AdvertisingIdClient`, which needs the Play services library (the release build refuses it, `CLAUDE.md`) and, at targetSdk 33+, the `com.google.android.gms.permission.AD_ID` permission - which a privacy app shouldn't declare. A hand-written binder to the ID service would still read zeros without that permission. |
| B3 `diagnostics` | Usage & diagnostics off | No. Play services' own state. |
| B4 `play_protect_upload` | Play Protect's "Improve harmful app detection" off (Play Protect's scanning stays on) | No. Play Protect keeps it in Play services; the old `Settings.Global` keys are hidden and unreadable at targetSdk 31+ (assumed, SettingsProvider's `@Readable` rule). |

So each is `need: optional, read_by: parent, when: always, summary: count, level: action, features: []` - design
114's optional parent steps (rows `vibration` ... `message_banners`): never counted, never orange, never a warning line,
listed quietly under "Valgfritt" on Device › Oppsett with a checkbox that saves itself and Android's path behind the
(i); on the phone's flow "Åpne", "Gjort", "Hopp over". `when: always`: a phone without Play services has nothing to do
here, but every phone this project supports has them (a `play_services` input isn't worth its plumbing).

### 5.2 Android 16 / the Jelly Star paths and the pages "Åpne" opens

Android's paths are **assumed** for the Jelly Star (Unihertz's Android 16 with Play services, near-AOSP Settings, so
Google's entries sit under Settings › Google rather than Pixel's Security & privacy) and are checked in the gate with
Unihertz's nb labels (§10); the copy follows what the gate finds.

| Step | Path (Jelly Star, assumed) | `phone_page` (tried in order; each through `systemPageIntent`, so only an unsuspended system package) |
|---|---|---|
| B1 | Settings › Google › All services › Phone number verification: off | `com.google.android.gms.settings.GOOGLE_SETTINGS` [verify], then `android.settings.SETTINGS` |
| B2 | Settings › Google › All services › Ads › Delete advertising ID | `com.google.android.gms.settings.ADS_PRIVACY` [verify], then `android.settings.PRIVACY_SETTINGS` |
| B3 | Settings › Google › ⋮ › Usage & diagnostics: off | `com.google.android.gms.settings.GOOGLE_SETTINGS` [verify], then `android.settings.SETTINGS` |
| B4 | Play Store › profile picture › Play Protect › ⚙ › Improve harmful app detection: off | `com.google.android.gms.settings.VERIFY_APPS_SETTINGS` [verify], then `android.settings.SECURITY_SETTINGS` |

"[verify]": the build resolves each Play-services action on the emulator's Play image (`cmd package query-activities
-a <action>`) and keeps only those that resolve to `com.google.android.gms` as an exported activity; the fallbacks are
public `Settings` actions. Play services and Settings are system packages, and the steps run in the setup hold, where
nothing is suspended, so `systemPageIntent` takes them. The table's `phone_page` lists are in the contract.

### 5.3 Contract: `server/testdata/setup_steps.json` (both copies, one commit)

- `steps`: four rows after `location_private`, before `vibration` (display order), e.g.
  `{"key": "number_verification", "need": "optional", "read_by": "parent", "when": "always", "summary": "count",
  "level": "action", "features": [], "phone_page": [...], "names": {"nb": "Nummerbekreftelse", "en": "Number
  verification"}}`.
- `needed`: every row's `missing` gains the four keys unless `ticked` has them (they are `always`); one new row with all
  four ticked and the rest as `needed[0]`, so the tick path is pinned. `count`, `alarm` and `setup_row` don't change in
  any row (optional) - the test that says so is the row.
- No `report`, `clean`, `features` or `policy` change: parent steps ride in the existing `setup.ticked` and
  `POST /api/devices/setup/ticks`.
- Server: `setup_status::STEPS` gains the rows (its test compares with the contract); i18n `setup.step.*`,
  `setup.how.*`, `setup.why.*`. Launcher: `SetupStep` gains four entries with `howRes`, `SetupPages.intents` the
  four (the first action that resolves, then the fallback), `strings.xml` nb + en.

### 5.4 Copy (checked against "Words in the UI")

Names (`setup.step.<key>`; the phone's flow title):

| key | nb | en |
|---|---|---|
| `number_verification` | Nummerbekreftelse | Number verification |
| `ad_id` | Annonse-ID | Advertising ID |
| `diagnostics` | Bruk og diagnostikk | Usage & diagnostics |
| `play_protect_upload` | Play Protect-deling | Play Protect sharing |

Behind the (i) on Device › Oppsett: first the why (`setup.why.<key>`), then the how (`setup.how.<key>`). The phone's
flow shows the how line only (`setup_how_<key>`), as every step.

| key | why nb | why en |
|---|---|---|
| `number_verification` | Google bekrefter telefonnummeret og knytter det til telefonen. Vibbs SMS og MMS trenger det ikke; RCS i Google Meldinger gjør det. | Google verifies the phone number and ties it to the phone. Vibb's SMS and MMS don't need it; RCS in Google Messages does. |
| `ad_id` | Uten ID kan ikke apper følge barnet mellom seg for reklame. | Without it, apps can't follow the child between them for ads. |
| `diagnostics` | Telefonen sender ikke bruksdata til Google. | The phone sends no usage data to Google. |
| `play_protect_upload` | Ellers sendes apper fra utenfor Play, som Vibb One, til Google. Skanningen blir på. | Otherwise apps from outside Play, such as Vibb One, are sent to Google. Scanning stays on. |

| key | how nb | how en |
|---|---|---|
| `number_verification` | Google › Alle tjenester › Bekreftelse av telefonnummer: av. Før SIM-kortet settes inn. | Google › All services › Phone number verification: off. Before the SIM goes in. |
| `ad_id` | Google › Annonser › Slett annonse-ID. | Google › Ads › Delete advertising ID. |
| `diagnostics` | Google › ⋮ › Bruk og diagnostikk: av. | Google › ⋮ › Usage & diagnostics: off. |
| `play_protect_upload` | Play Butikk › profilbildet › Play Protect › ⚙ › Forbedre gjenkjenning av skadelige apper: av. | Play Store › profile picture › Play Protect › ⚙ › Improve harmful app detection: off. |

Each a word or two as a name, the why one sentence of what changes, no orange, no warning line, nothing on any other
page. `docs/setup/google-account.md` gets the four in its checklist (B1 first: before the SIM).

### 5.5 Cost

B1: RCS chat in Google Messages (not used with Vibb's Meldinger) can't start; some Google sign-ins may ask for the number
by SMS instead. B2: none (apps get zeros; a new ID is made only if someone taps "Get new ID"). B3: none. B4: none
(scanning stays). All four are optional: the parent skips any of them freely.

## 6. C1 - "Reklame og sporing" on for new phones

### 6.1 What exists (verified)

The lists are global rows in `dns_blocklists` (migration 0009 seeded "Ads & tracking" = StevenBlack's unified hosts,
off); a list's `enabled` is every phone's default, and `device_blocklist_overrides (device_id, blocklist_id, enabled)` a
phone's own setting (`S/handlers/dns_filter.rs`, Web Filter's per-phone page). A seeded list is known by its name and
address together (`models::SEEDED_BLOCKLISTS`; a list can't be renamed). A new phone's defaults are written in
`devices::create`'s transaction (`insert_device_sql`, then `INSERT INTO device_policy ...`), as the notification filter
and Android's lock were made new-phone defaults (the comments there).

### 6.2 The change

In the same transaction, after `device_policy`: when the seeded ads list exists (name and address as
`SEEDED_BLOCKLISTS[0]`) and its `enabled` is 0, insert `device_blocklist_overrides (id, <its id>, 1)`. When the switch
is already on for every phone, nothing is written (an override equal to the switch would pin the phone against a later
change of the switch). Existing phones are untouched. A pure `ads_default_override(list: Option<(id, enabled)>) ->
Option<i64>` carries the rule for its test.

One small fix that follows: Settings' hub counts the lists on for every phone (`settings_hub.rs`: `dns_blocklists WHERE
enabled = 1`), so a family whose only new phone has the ads list would read "Ingen lister". It counts the lists in force
on at least one phone instead (the switch on, or an override on), so the hub says the state.

### 6.3 Pause, cost

The list is part of the Web Filter, which the pause and the override lift as today. Cost: StevenBlack's list blocks ad
and tracking hosts, so sponsored links in Google's results, some newsletter and SMS click-tracking links and an app's
rewarded ads stop working; the Blocked Activity log (when the parent switched it on - off by default - deduplicated per
domain for 10 min) will show those hits. The parent turns the list off for a phone on its Web Filter page. No new copy.

## 7. Pause, override and setup windows - every item

| Item | Lifted by the pause, "Åpne for oppsett" and the offline override? | Why |
|---|---|---|
| A4 | No (hardening, `hardeningPlan`) | restricts no use; would only let screen content out during setup |
| A3 | No (written while managed) | restricts no use |
| A5 | **Yes** (standing rule) | its failure is "no network", which only an on-phone lift can undo |
| A7 | n/a | a property of a network, set once when it is added |
| B1-B4 | n/a | Android's own switches, set by hand |
| C1 | Yes, with the filter | the filter is lifted as a whole |

## 8. Android 14-17

| Item | 14 (34) | 15 (35) | 16 (36) | 17 | Source |
|---|---|---|---|---|---|
| A4 capture, suggestions | yes | yes | yes | yes | UserManager 14-17 (verified) |
| A4 assist content | not available - skipped by the gate | yes | yes | yes | api-versions 35; UserManager 15-17 (verified) |
| A5 | yes | yes | yes | yes, same text | UserManager 14-17 (verified); the modem's support per phone (gate) |
| A7 | yes | yes | yes | yes | WifiConfigManager 16 read; 14/15/17 assumed (§4.3) |
| A3 | Chrome's own, per Chrome version | | | | Chromium templates |
| B | Play services' pages | | | | the gate on 36 and the Jelly Star |

The emulator gate runs on the lowest and highest image it touches: 34 (no assist-content) and 37.

## 9. Build steps

All six can be built in two branches at most (memory "Parallel worktrees"); the two cross-side steps touch the
snapshot and the setup contract, so they go one after the other on one branch.

| Step | Size | Side | Files |
|---|---|---|---|
| 1. A4 | S | launcher | `L/server/Hardening.kt` (three entries, `minSdk`, `hardeningKeyApplies`), `L/server/AppEnforcer.kt` (`userManagerKey` nullable, gated), `HardeningTest`; `docs/notes/launcher/hardening.md` |
| 2. A7 | S | launcher | `L/server/QuickControls.kt` (`connectToWifiNetwork`, pure `joinMacSetting`), a test beside the Wi-Fi page's tests; `docs/notes/launcher/kid-settings.md` |
| 3. A3 | S | launcher | `L/logins/LoginBrowser.kt` (`ChromePolicy` keys, `CHROME_PRIVACY`, `CHROME_PACKAGES`, `browserConfigPlan(managed)`), `L/server/BrowserConfig.kt` (passes `managed`), `login_rules.json` `bundles` rows (+ managed/unmanaged rows), `LoginBrowserTest`, `BrowserDohTest`; `docs/notes/launcher/logins.md` |
| 4. C1 | S | server | `S/handlers/devices.rs` (`create`), `S/handlers/settings_hub.rs` (the count), `S/models.rs` (the seeded ads list's lookup), tests (`tests/dns_blocklist.rs`: a new phone gets the override, an existing one not, none when the switch is on; the hub's count); `docs/notes/server/dns.md` |
| 5. A5 | M | both, one commit | migration `0165_block_2g.sql`, `S/models.rs` (policy field), `S/handlers/device_api.rs` (the policy), `S/handlers/devices.rs` (`update_hardening`), `T/device_advanced.html` (the group), `S/i18n/{nb,en}.json`, `S/tests/hardening.rs` (save on/off, default on for a new and an existing row), `policy_json_keys_snapshot`; `L/server/dto/PolicyResponse.kt`, `L/server/MobileNetwork.kt` (`cellular2gBlocked`), `L/server/AppEnforcer.kt`, `MobileNetworkTest` (the rule's four rows), `PolicyResponseCompatTest`; `docs/notes/launcher/hardening.md` (the Mobilnett list), `docs/notes/server/` (hardening note) |
| 6. B1-B4 | M | both, one commit | `server/testdata/setup_steps.json` + the launcher's copy, `S/setup_status.rs`, `S/i18n/{nb,en}.json`, `S/tests/setup_status.rs` (the four are listed under Valgfritt, never counted, tickable); `L/setup/SetupSteps.kt`, `L/setup/SetupPages.kt`, `L/res/values{,-nb}/strings.xml`, `SetupStepsTest`; `docs/notes/{server/setup-status,launcher/setup}.md`, `docs/setup/google-account.md`, `docs/testing/new-phone-model.md` |

Steps 1-3 are one launcher branch (three commits); step 4 a server branch; steps 5 and 6 one branch after them. CI's
path filters need nothing new (no new contract file).

## 10. Tests and checks

Unit tests: listed per step above. Each pure rule gets rows, never a mock of Android: `hardeningKeyApplies` (34/35/36
× each key), `hardeningPlan` with the three new entries (managed / unmanaged), `cellular2gBlocked` (true/false/null ×
override), `joinMacSetting` (secured / open), `browserConfigPlan` (managed × role × lockdown lifted × filter), the
server's new-phone override (switch off / on / list missing) and the hub's count.

Emulator check (`docs/testing/emulator.md`, a new section; 34 and 37 images, a Play image for B; asks first per memory
"Emulator: ask first"):
1. A4: after the first policy, `dumpsys user` lists `no_content_capture`, `no_content_suggestions`, and on 37
   `no_assist_content` (on 34 not, and no error in the log); a pause leaves them; unmanaging clears them.
2. A5: Settings › Network › SIMs › "Allow 2G" greyed and off (if the emulator's modem doesn't support it, the switch is
   absent - note it, the restriction still in `dumpsys user`); the PWA's switch off clears it, on sets it; a pause and
   the offline override clear it, their end sets it again.
3. A7: join the emulator's open `AndroidWifi` from the kid's Wi-Fi page; `dumpsys wifi` shows that config with
   `macRandomizationSetting` NON_PERSISTENT; a secured network added the same way keeps AUTO/PERSISTENT.
4. A3: in a pause, `chrome://policy` shows the six keys with status OK (`SafeBrowsingProtectionLevel` included, else
   it leaves the set); the role's login window opens a login and a captcha (design 119's gate rows) after it; a ticked
   Chrome shows the privacy keys only.
5. C1: add a phone in the PWA: its Web Filter page shows the ads list on, the earlier phone's unchanged, the hub counts
   one list; with the list's switch on for every phone, a new phone gets no override.
6. B: Device › Oppsett lists the four under Valgfritt, quiet; ticking moves each to Avkrysset; on the phone each
   "Åpne" opens its page on the Play image (which Play-services actions resolve is written into the contract).

Jelly Star check (before the release that carries it, on a test phone):
- A5: "Allow 2G" greyed; **a call out and a call in on the kid's SIM with 2G blocked** (VoLTE in use); the same abroad
  if a trip comes first.
- B: each path and Unihertz's nb labels; the copy table follows what the phone says.
- A4: `dumpsys content_capture` names a service or none (whether it protects anything there).

## 11. Question for the user

**Blokker 2G i utlandet.** With 2G blocked, a phone abroad on an operator that sends calls over 2G (no 4G calling for
visitors) can't make or take normal calls there; SMS, data and emergency calls still work. Choose:
- (a) **On everywhere** (recommended): the parent switches it off in the PWA for the trip, or a pause on the phone lifts
  it; the (i) says so.
- (b) **Lifted by itself abroad**: Vibb already knows home from abroad (design 97's `where`); the switch would protect
  only at home.

## 12. Not in this design

Per-app network and permission switches (design 127), HeliBoard, the per-app DNS report, `setContentProtectionPolicy`
(35, Play Protect's on-device scam detection - not a data flow to cut), `BlockThirdPartyCookies` (§2.2), a report of
the 2G state (§3.1).
