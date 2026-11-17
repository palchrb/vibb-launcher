# Maintenance-cost audit, 2026-11-17

The user's question (2026-11-17): have we built a fragile house of cards with a high maintenance cost by pulling so many
services into Vibb One and the server, and have we made too many suboptimal choices? Keep Storytel and Spotify, but
maybe with a plugin approach for that kind of integration.

Read-only audit of `main` at 402db7d58. Method: the four CLAUDE.md files, PLAN.md, the area notes, the code's sizes,
and `git log` since 2026-10-05 (the monorepo import). Nothing was built or run.

**The short answer.** No, it is not a house of cards. The core (policy, enforcement, time rules, calls, the catalog)
fails closed, is pinned by contract files on both sides, and is mostly pure rules under JVM and Rust tests. But it has
grown wide very fast: about 35 feature areas in six weeks, for one family, on a target phone that the kid does not use
every day yet. Each area adds to a matrix of Android 14-17 × two lock modes × mixed server and launcher versions. The
cost comes from that breadth, from five places that depend on undocumented behaviour (Storytel, Spotify's terms and
SDK, Google's map endpoint, SystemUI and keyguard behaviour, the assistant-role airplane route), and from bookkeeping
that grows with every feature (contract copies, compatibility flags, docs). Before new features, consolidate. A
plugin boundary for the third-party integrations is worth building in small steps.

## 0. The numbers

| | Production | Tests | Notes |
|---|---:|---:|---|
| Launcher (`launcher/`, Kotlin + 7.2k vendored Java) | 110.9k | 54.7k (2,750 `@Test`) | 1 Gradle module; no instrumented tests (`src/androidTest` absent) |
| Server (`server/src`, Rust) | 122.2k | 133.3k (102.5k in `src/tests` + 30.8k inline) | + templates 9.2k, static JS/CSS 13.2k, i18n 2×4.7k |
| Music app (`music/`, Kotlin) | 32.3k | 25.7k (1,272 `@Test`) | |
| Migrations | 148 files, 3.0k lines (0001-0152; 0153-0154 on `up-r2r3-server`) | | |
| Shared contract files | 33 JSON files duplicated byte for byte in `server/testdata/` and `launcher/app/src/test/resources/` (+ 3 in `music/`) | | each pair has its own compare test |
| Capability flags the launcher claims | 65 (`*_v1`/`_v2`) | | 137 "older server/launcher" references in the server's Rust, 118 in the launcher's Kotlin |
| Docs | 585 Markdown files, 16.5 MB (`docs/design` 278 files, `docs/review` 148); CLAUDE.md files 181 KB | | 350 `qa-*` review files |
| Commits since 2026-10-05 | 3,915 (2,320 without merges and PLAN-only commits) | | 543 subjects say fix/bug/crash; **~395 of them (73 %) are QA fix rounds**, not field bugs |

Direct dependencies are few: the server has 41 crates (`server/Cargo.toml`), the launcher about 20 libraries (AndroidX,
Retrofit/OkHttp, kotlinx.serialization, pcap4j 1.8.2, dnsjava, zxing, a kapt preference generator), and the music
app uses Media3, Room, WorkManager and Compose. Every dependency is verified by checksum (`verification-metadata.xml`,
`Cargo.lock`), the toolchains and actions are pinned, and releases are signed. The dependency tree is not the problem.

## 1. Inventory

Lines = production lines (tests in brackets), from a path-to-area map over the current tree
(not committed; areas overlap slightly). Churn = commits since 2026-10-05 that touch the
area (merges and PLAN-only commits left out). Fix = how many of those commits say fix/bug/crash in the subject; about
three in four of them are QA fix rounds, so read this as a churn signal, not a field-bug count.

Dependency kinds: **S** = an official or stable spec, **U** = an unofficial or undocumented API, **P** = sensitive
to the Android version or platform behaviour, **G** = Google-, Pixel- or OEM-specific.
Detection: **Att** = a row in the PWA's Attention list (`server/src/attention.rs`), **Ev** = a parent event
(`parent_events.rs`), **Page** = a line on the feature's own PWA page, **Crash** = the launcher's crash reports.

| Area | Lines | Churn / fix | External dependencies | How it fails · how we'd notice | Tests | Verdict |
|---|---:|---:|---|---|---|---|
| Core: policy, sync, enforcement, status, device pages | 38.7k (17.7k) | 592 / 151 | P: DPM, lock task, package suspension | **Closed** (`PolicyGate`, `LastEnforcedPlan`, "nothing allowed, kiosk on") · Att `PolicyProblem`, `NotHomeApp`, Crash | pure plans, `policy_json_keys_snapshot` + `PolicyResponseCompatTest` | **KEEP**, plus a compatibility-floor sweep (§5 R4) |
| Push transport: SSE, backstop, TLS pins, tsnet | 4.9k (2.6k) | 70 / 18 | S: SSE; Tailscale tsnet via gomobile (Go + NDK in every CI build) | Soft: falls back to the 30-minute backstop; pins closed, with a recovery address · Ev `PushOff`, `ServerTlsOff`; Online/Away/Offline | `server_tls_report.json`, `status_heartbeat.json` | **KEEP**; **ISOLATE** tsnet (the family uses public endpoints) as a build flavour |
| Time rules, lifts, lock-now, rule pause, extra-time requests | 17.5k (9.6k) | 173 / 53 | P: lock task, suspension | **Closed**, offline · Ev `ExtraTimeRequest`, pages | pure rules; `lock_now`, `rule_pause`, `time_request` contracts | **KEEP** |
| Lock: glance over Android's keyguard, keyguard cover, boot cover | 15.9k (11.8k) | 251 / 89 | **P+G**: SystemUI, keyguard, the status bar, `BOOT_COUNT`; the emulator runs Pixel SystemUI, the Jelly Star runs Unihertz's | Mixed: the boot cover is hard, the glance soft; false alarms happen (the queued false "boot cover not shown") · Att `BootCoverNotShown`, `ChildLockOff`, `UnseenRestarts` | pure `*Plan.kt`; emulator gates only (4,996-line `docs/testing/emulator.md`) | **KEEP, SIMPLIFY** after phase D; the most fragile part we own (§2 #1) |
| Legacy own PIN lock | 6.6k (0.9k) + server `kid_lock.rs` | 128 / 50 | P: an overlay competing with the keyguard | Holds less well (the user, 2026-11-16) | pure + emulator | **FINISH**: phase D removes it |
| VoIP calls over the lock | 1.1k (0.7k) | 13 / 4 | Element X's call notification | Soft | pure plan | **KEEP** |
| Calls, phone book, emergency menu | 13.4k (8.3k) | 120 / 35 | P: Telecom roles (screening, redirection, InCallService), direct boot | Closed (screening fails open after 5 s by design) · Att `Calls(CallProblem)`, 12 kinds | `phone_vectors`, `emergency_numbers` | **KEEP** |
| SMS/MMS engine + vendored AOSP PDU codec | 21.6k, of which 7.2k vendored (7.8k) | 60 / 14 | P: default SMS role, telephony; operator MMSCs (`mmsc_hosts.txt` from AOSP's APN list) | Closed (held messages go to the parent) · Ev `SmsHeld`, `MmscHost`; Att `MessagesHidden` | `sms_contract`, `sms_rules`, PDU fuzz (5,000 mutations per PDU) | **KEEP**, scope frozen: no mautrix bridge without a new decision |
| Contact requests | 1.1k (1.3k) | 9 / 4 | none | Ev `ContactRequest` | `contact_request.json` | **KEEP** |
| Dagen, calendar, CalDAV two-way, calendar links | 16.9k (8.2k) | 63 / 17 | S: CalDAV/ICS (plus each server's quirks); P: CalendarProvider | Soft · a page line; **no Att** for a failing write-back or link | 4 calendar contracts, `fake_dav` | **KEEP** Dagen; **ISOLATE** CalDAV write-back and links (optional, off by default in a product) |
| Weather (MET) | 4.2k (2.6k) | 20 / 8 | S: api.met.no (its terms: User-Agent, caching) | Soft: no weather line · **nothing** | `weather.json` | **KEEP**; behind the integration seam later (cheap) |
| Zones, Locate, power events | 8.8k (6.7k) | 120 / 40 | P: location, shutdown broadcasts | Soft · Ev `ZoneArrived/Left`, `LowBattery`, `PhoneOff/On` | `zone_status_report`, `power_report` | **KEEP** ("Følg meg hjem" builds here) |
| DNS filter (VPN), custom DNS, Mobilnett | 7.5k (3.5k) | 59 / 15 | P: always-on VpnService; pcap4j 1.8.2 (an old release, low activity), dnsjava; blocklist feeds | Filter shape: lockdown **off**, so a dead tunnel means unfiltered DNS (open for filtering); the sink fails closed; feeds fail closed per feed · Page lines only, **no Att** | `doh_hosts`, `dns_upstream_status`, `mobile_network` | **KEEP**; add an Att row for "filter on, tunnel down" |
| App catalog: GitHub + F-Droid, holds, self-update, Install Now, logos, display | 23.8k (11.1k) | 169 / 36 | S: GitHub Releases API, F-Droid index v2 + JAR signature; P: PackageInstaller | Closed (signer pins, holds, the update fence) · Ev `AppUpdateAvailable/Updated`; a source's sync error only on its page | `launcher_tag_codes`, `installed_apps_report`, `app_logos` | **KEEP**; **SIMPLIFY** the legacy (old tag rule, `same_code_update_v1`) at the compatibility floor |
| App permissions | 0.7k | 12 / 4 | P | Closed | pure | **KEEP** |
| Wi-Fi sign-in window + Chrome only for logins | 3.5k (0.8k) | 5 / 2 (new) | P: captive portal detection, the browser role; arbitrary portal HTML | Closed (code required, unknown documents refused) · Ev `WifiSigninWide/Budget` | `login_windows`, `login_rules`, `fake-portal.py` | **ISOLATE**: a workaround for lock task; off by default |
| UnifiedPush distributor + Matrix gateway | 5.0k (2.3k), R2/R3 in flight | 8 / 2 (new) | S: the UnifiedPush and Matrix push gateway specs; P: the 5 s foreground raise (FGS rules) | Soft: Google stays Element X's manual fallback · the App push page; **no Att** | `unified_push.json`, `up_rules.json` | **ISOLATE** (opt-in); stop at R3 |
| Web Push to parents, notifications | 4.2k (4.1k) | 68 / 13 | S: RFC 8030/8291/8292; G: Apple's Web Push for installed PWAs | Soft · delivery health, Ev `PushOff` | Rust tests | **KEEP** |
| Admin auth, passkeys, app lock | 11.2k (8.9k) | 130 / 32 | S: WebAuthn, TOTP; G: iOS Safari | Closed · security events | `passkeys`, `admin_auth`, `passkey_only` tests | **KEEP**; for a product, pick one sign-in model |
| Parent PIN on the phone | 1.1k (1.0k) | 12 / 3 | none | Closed with lockout · Ev `PinLockout` | pure | **KEEP** |
| Backups | 1.1k | 23 / 3 | none | Unknown until a restore | few | **KEEP**; add a restore drill |
| Snarveier | 1.1k (0.8k) | 13 / 5 | P: notification shade | Soft | `shortcuts.json` | **KEEP** |
| Airplane mode through the assistant role | 2.1k (1.0k) | 13 / 4 | **P+G**: `AirplaneModeVoiceActivity`, the assistant role, a lift of `DISALLOW_CONFIG_DEFAULT_APPS`, OEM `apm_enhancement_enabled` | Soft (the row shows `last_error`; "Off" works without the role) · the row only | `airplane_mode.json` | **ISOLATE**: off by default, removable (bit 64 already gates it) |
| Notification channels | 0.7k (0.5k) | 3 / 2 | P | Soft · About › Sound | `notification_channels.json` | **KEEP** |
| Home, kid Settings, photos, wallpapers, look | 16.7k (11.3k) | 294 / 101 | P+G: fonts, sizes, OEM | Soft · user reports | emulator screenshots | **KEEP**; the churn is UI polish |
| PWA shell (templates, static JS/CSS, i18n) | 33.1k (11.0k) | 467 / 125 | G: iOS standalone PWA (the iOS 27 status-bar blur, reported twice) | Soft · user reports | `jstest`, page tests | **KEEP**; freeze the polish |
| Music: the launcher's bridge and courier | 4.2k (2.1k) | 61 / 20 | own AIDL | Soft | `bridge_contract.json` | **SIMPLIFY**: provider-neutral (§4) |
| Music: server core, sweep, RSS, NRK, own files, import | 17.8k (11.5k) | 127 / 37 | S: RSS; **U**: NRK's psapi; `lofty` tags | Soft · Att `MusicFailing`, `MusicUnreachable` | `music_library`/`music_listing` snapshots | **KEEP**; NRK behind the source seam |
| Music: Spotify (server + app) | 13.4k (10.6k) | 137 / 37 | S: Web API (dev-mode quota, User Management); **U-ish**: the Web Playback SDK loaded unpinned from `sdk.scdn.co` into a WebView; **terms**: the Developer Policy forbids products aimed at children and mixing with other services' streams (`docs/testing/spotify-spike.md` §1) | Soft per entry; hard for the feature if the client ID is revoked · 5 Att kinds (`MusicSpotify*`) | `fake-spotify.py`, `spotify_hosts.json` × 3 | **ISOLATE**; never in a product build |
| Music: Storytel (server + app) | 4.9k (3.6k) | 34 / 12 | **U**: the login with storytel-player's AES key and IV (`26b-storytel.md`), `login.action`, the asset call's 302; the family's password on the server | Soft: downloaded books keep playing, new ones stop; account risk · Att `MusicStorytelRefused` | `fake-storytel.py`, `storytel_token_answers.json` × 3 | **ISOLATE** |
| Music: Sonos (app) | 10.6k (8.5k) | 60 / 20 | **U**: UPnP/SOAP/SSDP (de facto stable, undocumented by Sonos), the speaker's WebSocket, an exact DIDL ("Ikke endre noe i didl!!") | Soft: plays on the phone instead · **nothing** on the server | `fake-sonos.py`, pure controller | **ISOLATE** (a module) |
| Music: app core, player, UI | 17.8k (10.2k) | 138 / 50 | S: Media3, Room, WorkManager | Soft · the music state in the report | pure logic | **KEEP** |
| Map links (design 110, not built) | 0 | design only | **U**: Google's `/maps/preview/place` by feature id, short-link redirects; P: geo intents to Organic Maps/CoMaps | Soft: a fallback search; the design has a breakage counter (§8.3-8.4) | planned recorded answers | **ISOLATE from day one**: the pilot integration (§4) |
| Migrations | 3.0k | 152 / 30 | sqlx checksums | Hard on a checksum mismatch (a dev DB had to be recreated for 0153) | `cleanup.rs`, `sms.rs` migrate old → new | **KEEP**; squash only before a second installation |
| Docs and process | 16.5 MB | 1,770 commits | | | | **SIMPLIFY** (§5 R6) |

## 2. The parts that are actually fragile, ranked

The score is probability × cost × maintenance hours, each 1-3, judged from the churn, the dependency kind and what a
failure does to the kid or the parent.

| # | Part | P | Cost | Hours | Why |
|---|---|:-:|:-:|:-:|---|
| 1 | **Lock: glance, keyguard cover, boot cover** (`launcher/.../lock/`, 15.9k + 6.6k legacy) | 3 | 3 | 3 | The highest churn in the launcher (251 commits, 89 fix-labelled, plus 128 for the legacy lock). It rests on SystemUI behaviour, which differs between the Pixel image (`jelly36`) and Unihertz's build. It is tested only by emulator gates. The Android 17 emulator does not run on the VM. The Jelly Star is not yet the kid's daily phone, and 14 device checks are owed in PLAN. Every monthly patch and OS update can move it, and a failure either locks the kid out or opens the phone. |
| 2 | **Storytel** | 3 | 2 | 2 | It uses a reverse-engineered login with the official app's AES key, against an API Storytel can change in any app release. When it breaks, downloaded books keep playing, which softens it, but there is a real risk to the family's account. Att `MusicStorytelRefused` makes a break visible. |
| 3 | **Spotify** | 2 | 2 | 3 | 13.4k lines and 137 commits: the most code per feature of any integration. The SDK script changes in place (`26c-spotify.md`: "a pin would break playback at its next release"). The dev-mode quota and User Management apply, and the Developer Policy allows a revoked client ID at any time. It is well instrumented (five Att kinds and a full fake). |
| 4 | **SMS/MMS engine** | 2 | 3 | 2 | 21.6k lines on the default SMS role and the operators' MMSCs. Messages are held, not lost, and the parent sees `SmsHeld`. Changes to Android 17's telephony and to the operators' APNs are the risk. The vendored codec is the solid part. |
| 5 | **Airplane mode through the assistant role** | 3 | 1 | 2 | It uses a Settings activity meant for voice assistants, a PIN-gated lift of a restriction, and an OEM flag. Design 98 §10 (Risks) itself names an OEM leaving out `AirplaneModeVoiceActivity`. When it breaks, only the row stops working. |
| 6 | **Map links (design 110)** | 3 | 1 | 1 | Undocumented, but the design already has the fallback and a counter. Cheap if it is built isolated, a nuisance if it is wired into Meldinger's core. |
| 7 | **DNS filter tunnel** | 1 | 3 | 1 | Lockdown is off in the filter shape, so a dead tunnel means unfiltered DNS with no Attention row. It has been rare so far, but it fails silently. pcap4j is an old library. |
| 8 | **Wi-Fi sign-in window / Chrome for logins** | 2 | 1 | 2 | Every captive portal is different HTML, and the browser role behaves differently per Android version. It fails closed, so the kid simply can't sign in. |
| 9 | **PWA on iOS** | 2 | 1 | 2 | Standalone-PWA quirks per iOS version (the iOS 27 blur, reported twice). Only cosmetic. |
| 10 | **Mixed-version compatibility** | 2 | 2 | 2 | 65 capability flags and about 255 "older server/launcher" branches. A wrong default in an old-path branch is the kind of bug that tests rarely cover, because nobody runs the old pair. |

Not on the list: the policy and enforcement core, calls, time rules, the catalog's signature checks. §6 says why.

## 3. Suboptimal choices in hindsight

| Choice | What we'd do instead | Change now? |
|---|---|---|
| **Breadth before the target phone is in use.** About 35 areas in six weeks; the Jelly Star is not yet the kid's daily phone, and the device checks are owed. | Run the core for weeks on the real phone before adding integrations. | **Yes**: a feature freeze (§5) until the Jelly Star has run a soak period. |
| **Two lock modes in parallel** (step 10's own PIN lock, then design 59's Android keyguard). | Android's keyguard from the start. | **Yes**: phase D, already approved. |
| **Contract files copied and compared** (33 pairs plus 3 music copies, a compare test per pair, CI path filters listed by hand in `launcher.yml`'s 20-line comment and `server-ci.yml`, and about 200 lines of the root CLAUDE.md that every agent session loads). | One `contracts/` directory that both builds read in place (Gradle `test.resources.srcDir("../../contracts")`, Rust `include_str!` from `CARGO_MANIFEST_DIR/../contracts`). | **Yes**: cheap and mechanical, and it removes about 36 files, about 40 tests and most of the hand-kept lists. |
| **Mixed-version support for a fleet of one or two phones** (65 flags, `older_server` rows in the contracts). | A compatibility floor: the server states a minimum launcher, releases go server first, and code below the floor is deleted. | **Yes**, as a sweep repeated every few releases. |
| **Integrations wired into core files.** Spotify sits in `DnsFilterEngine.classifyDomain`, `EnforcementPlan`, `PortalWindowActivity`, `MdmSyncWorker` and 25 server Rust files; Storytel's token courier is in the launcher; the music kinds are in the core `attention::Kind` enum. | Generic seams: a server-sent DNS exemption list, a provider-neutral credential courier, an `Integration` trait. | **Yes, in steps** (§4). |
| **Platform workarounds as first-class features** (airplane mode through the assistant role, the Wi-Fi sign-in window, Chrome for logins, the glance over SystemUI). | Accept what lock task takes away, or ship each workaround as an optional extra with its own off switch and health row. | **Partly**: isolate them and turn them off by default; keep the glance. |
| **tsnet in every launcher build** (Go, NDK and gomobile in CI, a stub for local builds), while the family uses public endpoints. | A build flavour. | **Later**: keep it for the product's relay idea, behind a flavour. |
| **An in-launcher SMS engine** (21.6k lines) rather than a locked-down stock or Fossify app. | Defensible: per-contact rules and holds need the engine. | **No**, but freeze its scope. |
| **The process on every item** (design, design QA, revision, build, code QA, fix round, emulator gate; 350 QA files, 73 % of fix commits are QA rounds). | The full chain for enforcement, security and contracts; a lighter path for UI copy and polish. | **Yes, for UI work**: the full chain stays for the core. |
| **Docs as the spec** (16.5 MB; notes such as `pwa.md` 275 KB and `calls.md` 132 KB; the CLAUDE.md files 181 KB). | Tests as the spec, short notes per area of "why" and "don't", and CLAUDE.md files that only point to the notes. | **Yes**: a trim round (R6). |
| **154 migrations in six weeks.** | Normal for sqlx; each one runs once. | **No**: squash to a baseline only before a second installation exists. |

## 4. A plugin approach for third-party integrations

**What counts as an integration:** Storytel, Spotify, Sonos, NRK, the Google/Apple map-link resolver, and, cheaper
and optional, MET weather and CalDAV write-back and calendar links. They are things that talk to someone else's
service, often an undocumented one, and the phone works without them.

**What a plugin is in this codebase (realistic):**

- **Server: compile-time modules, not runtime plugins.** Rust has no stable ABI, so `dylib` plugins are not a
  practical option. WASM (wasmtime) would add a large runtime and a sandboxing project for five integrations. Do
  this instead:
  - `server/src/integrations/<key>/`, each implementing one trait in `integrations/mod.rs`:
    ```rust
    pub trait Integration: Send + Sync {
        fn key(&self) -> &'static str;                        // "storytel", "spotify", "maps", "met"
        fn routes(&self) -> Router<AppState>;                 // its PWA and device routes, under its own prefix
        async fn health(&self, db: &Db) -> Health;            // last ok, last error class, since: one generic Att kind
        async fn policy(&self, db: &Db, device: DeviceId) -> Option<Value>; // policy.integrations.<key>
        fn dns_exempt_hosts(&self) -> &'static [&'static str]; // replaces the hard-coded Spotify exemption
    }
    pub trait MusicSource: Integration { /* parse_target, list(entry), playable_on(renderer), credential(device) */ }
    ```
  - A registry built at startup from `#[cfg(feature = "...")]` entries. Cargo features `storytel`, `spotify` and
    `maps-google`, on by default for the family build. A product build uses `--no-default-features --features
    core-integrations` (NRK, RSS, MET, CalDAV). CI runs `cargo check --no-default-features` and `cargo clippy` for
    that build, so the feature-off build does not rot. The tests stay behind their own features.
  - A per-installation runtime switch (a settings row) on top, so a broken integration can be turned off from the
    PWA without a release.
  - **An out-of-process connector** (a small binary or container speaking a fixed HTTP contract, for example
    `/credential`, `/shelf`, `/asset` for Storytel) is the one form that lets a product ship **zero** code for
    unofficial APIs, with users installing community connectors themselves, as in Home Assistant. It costs a second
    process, auth between the two and a versioned contract. Build it only if the product happens; the trait above is
    the same seam, so moving to it later is mechanical.
- **Migrations:** keep **one** sqlx sequence. Separate migrators per feature would let feature-off and feature-on
  databases drift apart. The integration tables are already mostly in their own migrations (0072 Storytel, 0074, 0108
  and 0131 Spotify). Name new ones `<key>_*`. A disabled integration leaves its tables empty.
- **Contracts:** `contracts/core/` and `contracts/integrations/<key>/`, so an integration's contracts move and get
  deleted with it. The core contract knows only generic keys: `policy.integrations.<key>`, `dns_exempt_hosts`, and
  a provider credential answer.
- **Launcher:** it should know no integration by name.
  - Spotify's hosts come from the policy as `dns_exempt_hosts`. That removes `isSpotifyHost`, `musicSpotify()` in
    `DnsFilterEngine`/`MdmSyncWorker`/`PortalWindowActivity`, and the launcher's copy of `spotify_hosts.json`.
  - `StorytelTokenCourier` becomes `MusicCredentialCourier(provider)` on a generic `POST
    /api/devices/music/credential/{provider}`. The AIDL is append-only, so add `getProviderCredential(provider)` and
    keep the old method until the compatibility floor passes it.
- **Music app:** the renderer axis (`LocalRenderer`/`SonosRenderer`/`SpotifyRenderer`) is already the right seam for
  output. Add a `SourceProvider` interface for sources (the `model.Source` enum today), and move `spotify/`,
  `storytel/` and `sonos/` into Gradle modules (`:provider-spotify`, ...). Flavours: `family` (all of them) and `core`
  (none). Their fakes and fixtures move with them.
- **Attention:** the music-specific kinds (`MusicSpotify*`, `MusicStorytelRefused`) become
  `Integration { key, problem }` rows that an integration's `health()` fills. The integrations without a row today
  (MET, CalDAV, calendar links, Sonos, NRK) get one for free.

**What it buys.** When an unofficial API breaks, the fix stays inside one module (`integrations/storytel/` plus
`:provider-storytel`), and no core file, core contract or core migration changes. A product build can leave out the
code whose terms forbid it (Spotify's kid clause; Storytel's reverse-engineered login). Each integration's health
shows the same way. CI and agent sessions on core work never load those modules.

**What it costs.** Roughly two to three weeks of rounds spread over the steps below. One more CI check (the
feature-off build). A small amount of indirection (a trait and a registry) in code that is concrete today. The policy
moves to `integrations.<key>` with the usual compatibility path.

**The migration path, in small steps** (each one stands alone and can be merged by itself):

1. **I1: health and register** (server only, S). A generic `Integration` Attention kind fed by each outside call's
   last result, and the register in §5. No code moves.
2. **I2: generic DNS exemption** (both directories, S). `policy.dns_exempt_hosts` from the server; the launcher drops
   the Spotify names. One contract changes; three `spotify_hosts.json` copies become one, on the server.
3. **I3: provider-neutral credential courier** (both apps and the server, S-M). Storytel goes through it, and Spotify
   tokens later.
4. **I4: the `integrations/` module and trait, with design 110 as the pilot** (server, M). The map-link resolver is
   built inside it from day one, so the pattern is proven on new code.
5. **I5: move Storytel, then Spotify, behind the trait and cargo features** (server, M each). Add the feature-off CI
   check.
6. **I6: music app provider modules and flavours** (music, M).
7. **I7 (product only): an out-of-process connector**, if the product happens.

## 5. Consolidation plan: before "Husk!" and "Følg meg hjem"

These are ordered, small rounds; each one can be merged by itself. In parallel, as a gate rather than a round: the
**Jelly Star soak**. The kid uses it daily for two weeks, the owed device checks in PLAN are done, and only bugs are
fixed.

| Round | What | Size | Payoff |
|---|---|---|---|
| **R1: Make failure visible** | (a) The dependency risk register below, committed as `docs/plan/dependency-register.md`. (b) One generic Attention kind, `Integration{key, problem}`, fed by each outside call's last success and last error: GitHub/F-Droid sync, MET, CalDAV write-back, calendar links, the NRK/RSS sweep, Storytel, Spotify and the UP gateway. (c) A new Att row for "filter on, tunnel down" (`dns_upstream` missing while `vpn_filter_enabled`). (d) A restore drill for backups. | S (server) + a small launcher check for (c) | Every silent failure in §1's "nothing"/"Page" cells gets an Attention row; this is I1. |
| **R2: Phase D, remove the own PIN lock** | Every phone in android mode; delete `PinLock*`, `PinBackoff`, `HomePin`, `kid_lock.rs`'s own-mode paths, the `own` branches in `AndroidLock`/`GlancePanel`, the setting, the docs and `pin_lock_v1`. | M (both) | About 6.6k production and 0.9k test lines go. One lock mode halves the lock's test matrix and the emulator gates, and it removes the mode the user says holds less well. |
| **R3: One contracts directory** | Move the 33 shared pairs and the music copies into `contracts/` (`core/`, `integrations/<key>/`), read in place by Gradle and Rust. Delete the copies and the compare tests. CI filters on `contracts/**`. The root CLAUDE.md's contract section becomes one paragraph plus `contracts/README.md`. | S-M (mechanical) | About 36 files and about 40 tests go. The workflow comments and filters stop growing with each feature. The root CLAUDE.md shrinks by about 40 %, which is context every agent session pays for. |
| R4: Compatibility floor | The server states a minimum launcher and music version (refused below it, with an Att row). Delete the `older_server` rows and branches, and the legacy flags below the floor (`same_code_update_v1`, the old tag rule's write path, `OfflineOverride`'s leftover window, the pre-design-58 Storytel path). | M (both) | A share of the 255 compatibility branches and of the 65 flags goes; fewer paths that nobody runs. |
| R5: Integration seams | I2, I3, I4 (design 110 built as the pilot), then I5. | M | Spotify and Storytel out of the core files, and a product build becomes possible. |
| R6: Isolate the workarounds; trim the docs | Airplane mode through the assistant role, the Wi-Fi sign-in window and Chrome for logins become off by default, each with a health row. tsnet behind a flavour. The CLAUDE.md files cut to pointers. Notes longer than 50 KB (`pwa.md`, `calls.md`, `lock-and-glance.md`, `notifications.md`) split into "rules" and "history". A light process path for UI-only items. | S-M | Fewer surprises per OS update, a faster CI, and a cheaper context for every session. |

**Gate for new features after R3:** a new feature names its row in the register and its health signal, or it waits.

### The dependency risk register (format)

One row per outside dependency, kept current like the queue: updated in the commit that adds or changes the
dependency, and re-verified at every launcher RC and every major Android or iOS update.

| ID | Dependency | Kind | Used by | Breaks how | Fails | Detected by | Canary / fake | Kill switch | Last verified |
|---|---|---|---|---|---|---|---|---|---|
| D1 | Storytel login + asset API | U | music: Storytel | a new AES key, an endpoint, the 302 | soft (copies play) | Att `MusicStorytelRefused` | `fake-storytel.py`; R1 health | the integration off in the PWA | 2026-11-?? (rc) |
| D2 | Spotify Web API + Web Playback SDK + dev-mode terms | S/U, terms | music: Spotify | the SDK changes in place, quota, client ID revoked | soft / feature hard | Att `MusicSpotify*` | `fake-spotify.py` | as D1 | |
| D3 | Google `/maps/preview/place` | U | map links | format, consent page, refusal | soft (fallback search) | design 110's counter → Att | recorded answers | the resolver off | |
| D4 | SystemUI / keyguard behaviour | P+G | lock, glance, boot cover | an OS update or OEM build | hard (locked out or open) | Att `BootCoverNotShown`, `ChildLockOff`; Crash | emulator gates 14/16/17 + Jelly Star | none (core) | |
| D5 | `AirplaneModeVoiceActivity` + the assistant role | P+G | Flymodus | an OEM removes it, an Android change | soft | the row's `last_error` | emulator §6 | bit 64 off | |
| D6 | Default SMS role, operator MMSC/APN | P | SMS/MMS | Android telephony, the operator | closed (held) | Ev `SmsHeld`, `MmscHost` | `fake-mmsc.py` | `sms_mode` | |
| D7 | Sonos UPnP/SOAP + WebSocket | U | music: rooms | firmware | soft (phone plays) | none, until R1 | `fake-sonos.py` | the rooms off | |
| D8 | NRK psapi | U | music: NRK | the API | soft | Att `MusicFailing` | snapshots | per entry | |
| D9 | api.met.no | S | weather | its terms, its format | soft | none, until R1 | `weather.json` | the weather off | |
| D10 | CalDAV servers | S | Dagen write-back | server quirks | soft | none, until R1 | `fake_dav` | per calendar | |
| D11 | GitHub Releases / F-Droid index | S | catalog | rate limit, format | closed (holds) | page; R1 | fixtures | per app | |
| D12 | VpnService always-on, pcap4j, dnsjava | P / an old library | DNS filter | an Android VPN change, a library bug | open for filtering | none, until R1 (c) | `DnsFilterEngineTest` | filter switch | |
| D13 | Tailscale tsnet / gomobile | S | transport (unused by the family) | Go/NDK/gomobile bumps | CI build fails | CI | stub | flavour (R6) | |
| D14 | Apple Web Push + iOS standalone PWA | G | parents' push, the PWA | an iOS release | soft | Ev `PushOff`; user | none | — | |
| D15 | Element X (provider setting, call notifications) | third-party app | UP, VoIP over the lock, Message | an Element X release | soft | none | `fake-homeserver.py` | UP off | |

## 6. What not to worry about

- **The policy fails closed, and that is proven, not hoped.** `PolicyGate.kt` (pure, `PolicyGateTest`) rejects a
  suspect fresh policy, falls back to `LastEnforcedPlan`, and to "nothing allowed, kiosk on" if that is unreadable;
  every report carries `policyState`, shown as Att `PolicyProblem`. `ServerJson` has no `coerceInputValues`, and
  `policy_json_keys_snapshot` and `PolicyResponseCompatTest` pin the shape on both sides.
- **The rules are pure and tested in rows.** Time rules, lock-now, rule pause, calls, zones, the enforcement plan, the
  SMS receive and send rules, the UP rules and the boot-cover plan are Android-free functions with JVM tests (2,750
  launcher `@Test`, 1,272 music), and the server has more test lines (133k) than production lines (122k). The 33
  contracts make a one-sided API change fail in CI.
- **Field bugs are rare relative to the size.** Of 543 fix-labelled commits, about 395 are planned QA fix rounds.
  PLAN and its archive hold 18 entries marked REPORTED over six weeks; about half concern the PWA on iOS or UI.
- **The supply chain is tight.** A small dependency tree, checksum verification on both Gradle builds, pinned Rust
  and Gradle, actions pinned by SHA, APKs signed in a protected environment with a certificate check, and server
  tarballs signed with minisign. Three security reviews have been done (2026-10-10, three waves on 2026-10-21).
- **Failures mostly surface.** The Attention list (30+ kinds), the parent events (23 kinds) and the crash reports
  cover the core, the calls and the music. R1 closes the remaining gaps.
- **The integrations already have fakes.** `fake-spotify.py`, `fake-storytel.py`, `fake-sonos.py`, `fake-mmsc.py`,
  `fake-portal.py`, `fake-homeserver.py` and `fake_dav` let every integration run on the emulator without an account,
  so a broken upstream can be reproduced against a recorded answer.
- **The vendored PDU codec** is AOSP code, kept diffable, hardened (H1-H17) and fuzzed.
- **Operations are cheap.** One static binary or image and SQLite. The phone never serves anything (battery and
  attack surface).
- **154 migrations** is bookkeeping, not risk: they run once, and the tests migrate old databases forward
  (`tests/cleanup.rs`, `tests/sms.rs`).
