# 126 - Privacy round, part 1

Status: **design, revision 2** (2026-11-22; revision 2 takes the design QA's M1-M5 and L1-L9, §13). Asked for and approved by the user on 2026-11-22 (A3, A4, A5, A7, B1-B4,
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
| A3 | Chrome sends no metrics, browsing data or sign-in to Google | Six keys in Chrome's managed config, on every managed phone, the role's lockdown or not | nothing | no - not a restriction on use | S, launcher |
| A5 | Block 2G | `DISALLOW_CELLULAR_2G` (34), a per-phone switch on the Mobilnett page, on by default; recommended pending §11: lifted by itself while the phone is abroad | "Blokker 2G" beside the mobile-network lock, its (i); a quiet "Av i utlandet" while lifted abroad | **yes** - §3.4, the one place this note differs from the brief | M, both + `mobile_network.json` |
| A7 | A fresh MAC on open Wi-Fi - a new address after 4 h away, at the DHCP lease, and at least daily; **not** on every connection | `WifiConfiguration.RANDOMIZATION_NON_PERSISTENT` on the open networks Vibb adds | nothing | n/a - a network setting, not a restriction | S, launcher |
| B1-B4 | Four Google switches the phone can't set or read | Four optional parent-ticked setup steps (design 114) | quiet rows under "Valgfritt" | n/a | M, both + contract |
| C1 | "Reklame og sporing" on for new phones | A per-phone list override written when the phone is created | the list on, on the new phone's Web Filter page | as the filter (yes) | S, server |

One question for the user (§11): whether "Blokker 2G" stays on abroad (a) or lifts itself there (b, recommended, designed in
§3.6 and built as the default unless the user picks (a)). **Answered 2026-11-22**: (a) by default, (b) as a per-phone
opt-in, "Tillat 2G i utlandet" (§11).

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

One possible cost on 15+ (assumed, to be checked): Android 15's content protection (`setContentProtectionPolicy`, Play
Protect's on-device scam detection in apps) runs over content capture's plumbing, so `DISALLOW_CONTENT_CAPTURE` may turn
it off too. It sends nothing off the phone, so losing it is a small safety cost, not a privacy gain; the emulator check
reads `dumpsys content_capture` on 37 for the content-protection service (§10). If it goes, the note says so and the
user hears it with the gate's result; the restriction stays (the user's A4).

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
call. `BrowserSignin` and `MetricsReportingEnabled` are not refreshed while Chrome runs: they take hold at Chrome's
next cold start. With the login role on, the role's first tightening hides and shows Chrome, which stops it, so the
next window starts it cold. A ticked Chrome, or a Chrome with no role, picks them up at its next cold start - Android
ending it in the background, or a reboot. Vibb doesn't stop a running free app for this: the keys cost nothing the kid
sees, and a short delay leaks only until then.

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
SIMs) shows greyed, "controlled by admin" (assumed from AOSP Settings; checked in the gate, with the Mobilnett lock off,
§10).

What the phone can't see: whether its modem honours it. Telephony applies it through the allowed-network-types reason
`ENABLE_2G`, which needs radio HAL 1.6+; the public API has no way to read that (`ALLOWED_NETWORK_TYPES_REASON_ENABLE_2G`
and `CAPABILITY_USES_ALLOWED_NETWORK_TYPES_BITMASK` are hidden; verified against `api-versions.xml`). On a modem that
can't, Android hides its own switch and nothing happens. The Jelly Star's MediaTek on Android 16 is assumed to support
it (the gate looks for the greyed switch). The report says what Vibb asked of Android (§3.6.4), never whether the modem
obeys.

### 3.2 Policy, storage and the contract

- Migration `0165_block_2g.sql` (built first; design 127's `app_denials` moves to 0166):
  `ALTER TABLE device_policy ADD COLUMN block_2g INTEGER NOT NULL DEFAULT 1;` - on for every phone, existing ones too
  (the user: "on by default"; only emulators run Vibb, `CLAUDE.md` "No backwards compatibility").
- Policy: a new top-level `block_2g: bool`, beside `mobile_settings_locked` - **not** inside `hardening`, whose launcher
  DTO says "never lifted by the override or pause" (A5 is, §3.4). Pinned by `policy_json_keys_snapshot`
  (`S/tests/device_api.rs`) and `PolicyResponseCompatTest`.
- **Contract: `server/testdata/mobile_network.json`** (byte-identical in `launcher/app/src/test/resources/`, one commit,
  both sides), the round Mobilnett's file, which already pins `mobile_settings_locked`:
  - `policy`: every row gains `block_2g` (a new phone `true`, a save of the switch off `false`); the server's
    `tests::mobile_network::the_contract` saves it through `POST /devices/{id}/mobile` (`group=block-2g`) and produces
    each row; `MobileNetworkTest` decodes it.
  - `cellular_2g_blocked` (new): `{value, override, kept, blocked}` - the rule of §3.6.2 row by row (`value` `true`/`false`
    /`null`, `override` the pause/override/"Åpne for oppsett", `kept` `home`/`abroad`/`null`); `MobileNetworkTest` runs
    each through `cellular2gBlocked`.
  - `where_kept` (new): `{last, now, kept}` - §3.6.1's stickiness, run through `where2gKept`.
  - `report` gains `mobile_data.cellular_2g` (§3.6.4) and `clean_mobile` its rows (a known word kept, anything else
    dropped); the server's `tests::mobile_network` posts it and checks what is stored, `intake_limits` keeps the key.
  - CI: nothing new - the file is already in both workflows' path filters.
- Launcher: `PolicyResponse.block2g: Boolean? = null`; the pure rules in `MobileNetwork.kt` beside
  `mobileSettingsLocked`; applied in `AppEnforcer` right after `applyMobileSettingsLock` with `setRestriction(dpm, admin,
  UserManager.DISALLOW_CELLULAR_2G, ...)`, whether or not the phone is managed in hardening's sense (the parent's
  explicit per-phone switch, as roaming). Unenrolled or no policy: cleared.

### 3.3 The PWA: the Mobilnett page

Device › Mobilnett (`T/device_mobile.html`, `S/handlers/mobile_page.rs`) gets a fourth section, `#block-2g`, after
`#mobile-lock` (the two switches with the same lift rule side by side, under "Roaming"): one auto-saving form,
`group=block-2g`, a switch `on` (missing = off), a new arm in `mobile_page::change` (`"block-2g" => ("block_2g",
ChangeValue::Flag(..), "block-2g")`); the save answers on `#block-2g` (no jump to the top, `scroll-restore.js`). Not on
Advanced: the switch is about the mobile network and lifts with the pause, like its neighbours there.

| key | nb | en |
|---|---|---|
| `mobile.block_2g.label` | Blokker 2G | Block 2G |
| `mobile.block_2g.info` ((i), with (b), recommended) | 2G kan avlyttes med falske basestasjoner. Der det bare finnes 2G, virker ikke vanlige samtaler. Nødsamtaler går alltid. Av i utlandet og under pause. | 2G can be intercepted by fake base stations. Where there's only 2G, ordinary calls don't work. Emergency calls always work. Off abroad and during a pause. |
| `mobile.block_2g.info` ((i), if the user picks (a)) | 2G kan avlyttes med falske basestasjoner. Der det bare finnes 2G, og i utlandet uten 4G-samtaler, virker ikke vanlige samtaler. Nødsamtaler går alltid. Oppheves under pause. | 2G can be intercepted by fake base stations. Where there's only 2G, and abroad without 4G calling, ordinary calls don't work. Emergency calls always work. Lifted during a pause. |
| `mobile.block_2g.abroad` (a quiet value row under the switch, (b) only, §3.6.4) | Av i utlandet | Off abroad |

Checked against "Words in the UI": a two-word label, no sub-line; the why and the cost behind the (i) alone; the
parent's choice (on or off) is never orange and has no line on any other page; "Av i utlandet" is a quiet state value
(the `lock_lines` row style, not orange), shown only while the phone says it is lifted abroad.

### 3.4 Pause: **lifted** (not hardening) - a decision against the brief's guess

It is a hardening switch in spirit, but its failure is "no network": a phone in a 2G-only valley can't reach the
server, so the PWA's switch can't reach it either. The pause and the offline override are the on-phone ways out (the
parent's PIN, no network needed), so they lift it. The protection lost during a pause is small and temporary. Same rule
as `DISALLOW_DATA_ROAMING` and `DISALLOW_CONFIG_MOBILE_NETWORKS` (the standing rule; "Åpne for oppsett" lifts it too).

### 3.5 Cost

- Where the operator's voice still falls back to 2G (CSFB) and the phone has no working VoLTE on that network, **calls
  fail** with 2G blocked; SMS over LTE keeps working. In Norway 3G is gone and 2G is being shut down (assumed, from the
  operators' announcements), so at home a phone that calls today already uses VoLTE; the risk is roaming, where VoLTE
  roaming is often not offered and 2G is the fallback. §3.6 takes that away; (a) keeps it.
- Emergency calls are never affected (verified, AOSP's own words).
- Nothing else: data on 2G is too slow to matter.

### 3.6 Abroad: lifted by itself (option (b), recommended, the default pending §11)

**Decision (the user, 2026-11-22)**: 2G stays blocked abroad too by default. "Tillat 2G i utlandet" (en "Allow 2G
abroad"), a per-phone switch under "Blokker 2G" on Mobilnett, shown only while it is on, off by default (migration
0167, the policy's top-level `allow_2g_abroad: bool`, always sent; `group=block-2g-abroad`, saving on `#block-2g`),
turns this section's lift on; off, the phone never lifts the block abroad (`abroadLifts2g(kept, allowAbroad)`; a missing
key is off). The pause still lifts it as §3.4 says. Its cost behind the group's (i) only, never orange (a parent's
choice): "Uten dette kan vanlige samtaler feile i noen land." / "Without it, ordinary calls may fail in some
countries."; `mobile.block_2g.info` ends "Oppheves under pause." / "Lifted during a pause." The service-state listener
runs only while both switches are on. Contract: `mobile_network.json`'s `policy` rows carry `allow_2g_abroad`, and
every `cellular_2g_blocked` row an `allow_abroad`.

#### 3.6.1 Where, kept through "unknown"

`whereOf` (`MobileNetwork.kt`, design 97 §4.1) answers `UNKNOWN` in airplane mode, with no registered network or out of
service. That is exactly the state of a phone abroad with 2G blocked in a 2G-only area, so the lift can't follow
`whereOf` alone - it would block again the moment the 2G-only area made the phone lose service. So the 2G rule keeps
the last known answer:

- `where2gKept(last: Where?, now: Where): Where?` (pure): `now` when it is `HOME` or `ABROAD`; otherwise `last`. Only
  a real registration (home or roaming) moves it. **No border fallback** (code QA H1): revision 2 read the cells'
  country without a registration (`getNetworkCountryIso()`) as abroad, but a fake foreign-coded LTE cell could then lift
  the block - the very downgrade attack it exists for - and keep it lifted. The cost: a phone that crosses from home
  straight into a 2G-only area abroad gets no ordinary calls until it registers on a non-2G network somewhere
  (emergency calls still work; a pause on the phone lifts it).
- The kept value lives in device-protected prefs (`block_2g_where`), so a reboot abroad keeps it; `null` (never read)
  counts as home. It moves only on a `HOME`/`ABROAD` read, never on `UNKNOWN`.

#### 3.6.2 The rule

`cellular2gBlocked(value: Boolean?, overrideActive: Boolean, kept: Where?): Boolean =
value == true && !overrideActive && kept != Where.ABROAD`. A missing key sets nothing (as `mobileSettingsLocked`).

#### 3.6.3 When it is read

- In every `AppEnforcer` pass (the sync, policy changes, the 15-minute backstop), as now for the roaming lock.
- On a change of service state: a `TelephonyCallback.ServiceStateListener` (API 31; Vibb holds READ_PHONE_STATE and
  fine location, so the state isn't sanitised) registered while `block_2g` is on, on the default data subscription
  (registered again when that changes), which re-reads `whereNow` and re-applies the restriction only when
  `cellular2gBlocked` changes (a `setRestriction` only on a change, as the other restrictions), under the one lock the
  pass takes. A re-block within 2 min of an abroad lift waits out the 2 min (a border flip-flop). No listener exists in
  the launcher today (verified); this is the first. Android 14-17 alike.

#### 3.6.4 What each sees

- **The kid**: nothing. Calls work abroad; nothing is said.
- **The parent**: the switch stays on (it is the parent's choice). The report's `mobile_data` gains `cellular_2g`:
  `"blocked"` (the restriction set), `"abroad"` (lifted by §3.6), `"lifted"` (a pause, the override or a setup window),
  `"off"` (the switch off or no policy). While the latest report says `"abroad"`, the Mobilnett page shows the quiet
  row "Av i utlandet" under the switch; nothing else anywhere (no Attention, no orange: it is the rule working).
- **Cost of (b)**: no 2G protection abroad - where fake base stations are as likely as at home.

#### 3.6.5 Option (a), if the user picks it

On everywhere: §3.6 isn't built (`cellular2gBlocked` without `kept`, no listener, no `cellular_2g` in the report, the
(a) row of §3.3's copy). Its real limits: a pause needs the parent with the phone and its PIN; the PWA reaches a phone
abroad only over data (Wi-Fi or roaming data, which "I utlandet: Av" stops); and nothing tells the parent that a call
failed because of 2G - the kid only sees a call that doesn't go through.

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
| B2 | Settings › Google › All services › Ads › Delete advertising ID (one path, the copy's; the gate settles it and both change together) | `com.google.android.gms.settings.ADS_PRIVACY` [verify], then `android.settings.PRIVACY_SETTINGS` |
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
| `play_protect_upload` | Play Protect sender da ikke ukjente apper til Google. Skanningen blir på. | Play Protect then sends no unknown apps to Google. Scanning stays on. |

| key | how nb | how en |
|---|---|---|
| `number_verification` | Google › Alle tjenester › Bekreftelse av telefonnummer: av. Helst før SIM-kortet settes inn. | Google › All services › Phone number verification: off. Best before the SIM goes in. |
| `ad_id` | Google › Alle tjenester › Annonser › Slett annonse-ID. | Google › All services › Ads › Delete advertising ID. |
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
`create_device`'s transaction (`S/handlers/devices.rs:588`) (`insert_device_sql`, then `INSERT INTO device_policy ...`), as the notification filter
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
rewarded ads stop working. It also lists attribution and deep-link hosts - Branch (`api.branch.io`, per-app
`*.app.link` hosts) and AppsFlyer (`t.appsflyer.com`, `events.appsflyer.com`; verified in the research copy of the
list) - so some apps' invite, password-reset or "open in the app" links that go through them fail or land on a web page,
and **the parent sees nothing**: the Blocked Activity log is off by default (when on, it is deduplicated per domain for
10 min). The way out exists: Allowed Domains (global, or scoped to one phone) takes a host out of every list
(`dns_engine.rs` removes the allow entries from each compiled list - verified; an exact host, so `api.branch.io`, not a
pattern), or the parent turns the list off for that phone on its Web Filter page; finding which host needs the log
switched on. No new copy.

## 7. Pause, override and setup windows - every item

| Item | Lifted by the pause, "Åpne for oppsett" and the offline override? | Why |
|---|---|---|
| A4 | No (hardening, `hardeningPlan`) | restricts no use; would only let screen content out during setup |
| A3 | No (written while managed) | restricts no use |
| A5 | **Yes** (standing rule); with (b) also lifted by itself abroad | its failure is "no network", which only an on-phone lift can undo |
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
| 5. A5 | M | both, one commit | migration `0165_block_2g.sql`, `S/models.rs` (policy field), `S/handlers/device_api.rs` (the policy), `S/handlers/mobile_page.rs` (`group=block-2g`, the `cellular_2g` value row), `T/device_mobile.html` (`#block-2g`), `S/mobile_network.rs` (`clean_mobile` keeps `cellular_2g`), `S/i18n/{nb,en}.json`, `S/tests/mobile_network.rs` (the contract's `policy` and `report` rows, the save, the quiet row only on `abroad`), `policy_json_keys_snapshot`; `server/testdata/mobile_network.json` + the launcher's copy; `L/server/dto/PolicyResponse.kt`, `L/server/MobileNetwork.kt` (`cellular2gBlocked`, `where2gKept`), `L/server/MobileNetworkRuntime.kt` (the kept where, the `ServiceStateListener`, the report's `cellular_2g`), `L/server/AppEnforcer.kt`, `MobileNetworkTest` (`cellular_2g_blocked`, `where_kept`, `report`), `PolicyResponseCompatTest`; `docs/notes/launcher/hardening.md` (the Mobilnett list), the server's Mobilnett note. With (a): without the listener, the kept where and `cellular_2g` - S-M |
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
   `no_assist_content` (on 34 not, and no error in the log); a pause leaves them; unmanaging clears them. On 37 (a Play
   image), `dumpsys content_capture` before and after: whether the content-protection (scam detection) service stops
   (§1.4) - written down either way. On 37, Flymodus on and off through Vibb's assistant (design 98) still works with
   the three restrictions set.
2. A5: first switch the Mobilnett lock ("Lås mobilnett i Androids innstillinger") off for the phone, since
   `DISALLOW_CONFIG_MOBILE_NETWORKS` hides the SIM page; then Settings › Network › SIMs › "Allow 2G" greyed and off (if
   the emulator's modem doesn't support it, the switch is absent - note it; `dumpsys user` still lists `no_cellular_2g`,
   and `dumpsys phone` shows the allowed network types for the `enable_2g` reason without GSM). The PWA's switch off
   clears it, on sets it; a pause and the offline override clear it, their end sets it again. With (b): the Mobilnett
   round's roaming setup (`docs/testing/emulator.md`, the private image with a Norwegian SIM, `CIMI 242010000000000`,
   then `gsm data roaming` / `gsm voice roaming`) → `cellular_2g` `abroad`, the restriction gone, "Av i utlandet" on the Mobilnett page; `gsm data unregistered`/`gsm voice
   unregistered` (unknown) keeps it lifted; back to the home MCC → blocked again, the row gone; a reboot while abroad
   keeps it lifted.
3. A7: join the emulator's open `AndroidWifi` from the kid's Wi-Fi page; `dumpsys wifi` shows that config with
   `macRandomizationSetting` NON_PERSISTENT; a secured network added the same way keeps AUTO/PERSISTENT. An open network
   first saved in Android's Settings (during a window) reconnects from the kid's page through its saved id - not refused
   as "admin updating their own network" - and keeps Android's setting. A secured network's Connect stays off while the
   password field is empty (code QA L2).
4. A3: in a pause, `chrome://policy` shows the six keys with status OK (`SafeBrowsingProtectionLevel` included, else
   it leaves the set); the role's login window opens a login and a captcha (design 119's gate rows) after it; a ticked
   Chrome shows the privacy keys only.
5. C1: add a phone in the PWA: its Web Filter page shows the ads list on, the earlier phone's unchanged, the hub counts
   one list; with the list's switch on for every phone, a new phone gets no override.
6. B: Device › Oppsett lists the four under Valgfritt, quiet; ticking moves each to Avkrysset; on the phone each
   "Åpne" opens its page on the Play image (which Play-services actions resolve is written into the contract).

Jelly Star check (before the release that carries it, on a test phone):
- A5: "Allow 2G" greyed (the Mobilnett lock off for the look); **a call out and a call in on the kid's SIM with 2G
  blocked** (VoLTE in use); a trip abroad if one comes first.
- B: each path and Unihertz's nb labels; the copy table follows what the phone says.
- A4: `dumpsys content_capture` names a service or none (whether it protects anything there).

## 11. Question for the user

**Blokker 2G i utlandet.** With 2G blocked, a phone abroad on a network that carries visitors' calls over 2G (no 4G
calling for roaming guests, common outside the big operators) can't make or take ordinary calls there; SMS, data and
emergency calls still work, and nothing tells the kid or the parent why a call failed.

- (a) **On everywhere.** Full protection abroad too. To call there, the parent switches it off in the PWA - which reaches
  the phone only over data (Wi-Fi, or roaming data if "Roaming" allows it) - or starts a pause on the phone, which needs
  the parent there with the PIN. A failed call isn't shown as a 2G problem anywhere.
- (b) **Lifted by itself abroad** (recommended, §3.6). Vibb already tells home from abroad (design 97); it keeps the last
  answer while the phone has no network, so a 2G-only area abroad still gets calls once the phone has registered there
  on a non-2G network; one that crosses from home straight into a 2G-only area gets no ordinary calls until it does
  (emergency calls still work; a pause on the phone lifts it). The kid sees nothing; the parent sees
  a quiet "Av i utlandet" on Mobilnett while it is lifted. Cost: no 2G protection abroad.

Built as (b) unless the user answers (a); (a) is (b) minus §3.6 and changes one line of copy.

**Answer (2026-11-22)**: (a) by default; (b) as an opt-in per phone, "Tillat 2G i utlandet", off by default (§3.6).

## 12. Not in this design

Per-app network and permission switches (design 127), HeliBoard, the per-app DNS report, `setContentProtectionPolicy`
(35, Play Protect's on-device scam detection - not a data flow to cut), `BlockThirdPartyCookies` (§2.2), a report of
whether the modem obeys the 2G block (§3.1).

## 13. QA revision 2 (2026-11-22)

The design QA's findings (0 H, 5 M, 9 L), each taken:

| Finding | Handled |
|---|---|
| M1 migration number | `0165_block_2g.sql`, the next free one on `main` when built (`migrations_from_73_in_order` allows no gap); design 127's moves to 0166 (§3.2, §9) |
| M2 `mobile_settings_locked` has contract rows | `block_2g` goes into `mobile_network.json`'s `policy` rows, with new `cellular_2g_blocked` and `where_kept` row sets and the report's `cellular_2g`, both copies in one commit (§3.2) |
| M3 the switch's page | Mobilnett, `#block-2g` after `#mobile-lock`, `group=block-2g` in `mobile_page::change`; Advanced unchanged (§3.3) |
| M4 the (i) | the QA's nb text for (a), and its (b) form "Av i utlandet og under pause", en to match (§3.3) |
| M5 / §11 | the question rewritten with both sides' real limits; (b) designed in full - the kept where through `UNKNOWN`, a border fallback on the cells' country, a service-state listener, contract rows, the report's `cellular_2g`, the quiet "Av i utlandet" - and recommended as the default pending the answer; (a) in two lines (§3.6, §11) |
| L1 five vs six keys | six (§0) |
| L2 keys not refreshed live | `BrowserSignin` and `MetricsReportingEnabled` take hold at Chrome's next cold start; a ticked or role-less Chrome at its next one (§2.3) |
| L3 Safe Browsing's level | kept as a gate check (§10, step 4) |
| L4 the SIM page is hidden by the lock | the gate turns the Mobilnett lock off first, or reads `dumpsys phone` (§10) |
| L5 Advanced's footer | moot with M3 |
| L6 the function's name | `create_device` (`S/handlers/devices.rs:588`) (§6.1) |
| L7 B2's path | one path, "Google › Alle tjenester › Annonser", in both the table and the copy; the gate settles it (§5.2, §5.4) |
| L8 B1 with the SIM in, B4's claim, scam detection | B1 "Helst før SIM-kortet settes inn"; B4 says what the switch does, no claim about Vibb One; A4's possible loss of content protection on 15+ and its gate check (§1.4, §5.4, §10) |
| L9 C1's silent breakage; A7's "per connection" | C1: Branch/AppsFlyer links, the parent sees nothing with the log off, Allowed Domains is the way out (§6.3); A7: §0 says when the address changes |

Code QA of the build (2026-11-22, H1): the border fallback of §3.6.1 is dropped - a fake foreign-coded cell could lift
the block. Only a real registration moves the kept where; a phone that crosses straight into a 2G-only area abroad gets no
ordinary calls until it registers on a non-2G network (emergency calls still work; a pause lifts it). The build also holds
a re-block for 2 min after an abroad lift (a border flip-flop toggles the modem once), and the migration is 0165.
