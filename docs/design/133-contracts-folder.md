# Design 133: One `contracts/` folder both builds read (C4, audit R3)

Status: design, 2026-11-25, revision 2 (design QA: 0 H, 2 M, 6 L, all taken - §11). No code. Base: `main` at `311bd1019`. Source: the maintenance audit's R3
(`docs/review/2026-11-17-maintenance-audit.md` §5: "Move the 33 shared pairs and the music copies into `contracts/`
(`core/`, `integrations/<key>/`), read in place by Gradle and Rust. Delete the copies and the compare tests. CI filters
on `contracts/**`. The root CLAUDE.md's contract section becomes one paragraph plus `contracts/README.md`.") The audit
counted 33 pairs on 2026-11-17; the tree now holds more (§1).

## 0. Summary

- Every file that two or three builds read is kept once, under `contracts/` at the repo root. The copies in
  `launcher/app/src/test/resources/`, `music/app/src/test/resources/`, `launcher/app/src/main/assets/dns/`, both apps'
  `src/main/aidl/` and the shared files in `server/testdata/` are deleted.
- Four roots, chosen by who reads and whether it ships (§2): `contracts/core/` (server and launcher),
  `contracts/integrations/<key>/` (server and launcher, one integration's), `contracts/music/` (everything the music app
  reads; the server and the launcher may read it too) and `contracts/shipped/` (compiled into a release build: the
  bridge AIDL and the built-in secure-DNS hosts). Everything outside `shipped/` is test data only.
- Gradle reads the roots as extra test-resource source directories, so every `getResource("x.json")` stays as it is
  (§3.2). Rust reads them through one macro and one path function rooted at `CARGO_MANIFEST_DIR/../contracts` (§3.1).
- 48 data files and 3 AIDL files stay; 52 copies go (49 data, 3 AIDL): 51 files instead of 103. 72 copy-compare tests go (32 server,
  38 launcher, 2 music); 19 tests lose their compare lines and keep the rest (§6).
- Three guards replace them: a README test (every file under `contracts/` has a row, every row a file), a shadow test
  in each app (no file name exists both in a root and in the module's own `src/test/resources/`) and the CI filter
  tests (each workflow's filter matches every file it reads) (§6.2).
- The CI filters lose about 50 alternatives each and become `contracts/` (launcher, server) and
  `contracts/(music|shipped/bridge-aidl)/` (music) (§5). They also close two gaps today's filters have (§5.1).
- The Docker image is unaffected: it only copies the release binary (§4). One production read moves outside the
  server crate: `dns_server.rs`'s `include_str!` of the DoH host list (§4.1).
- Eight commits, each green on all three CI builds (§8). The root CLAUDE.md's 46 KB contract section becomes about
  2 KB, and `contracts/README.md` holds one table row per file (§7).

## 1. Inventory (grep-verified on `311bd1019`)

Method: `sha256sum` over every file in `server/`, `launcher/`, `music/` and `scripts/` (build output excluded), grouped
by hash; then `grep` for every test that reads a copy at another side's path (`../../server/testdata/`,
`../launcher/app/src/test/resources/`, `repo("...")`). Byte-identical groups that are not contracts (licence texts,
fonts, Gradle wrappers, glyph drawables, icon SVGs, the attestation captures in `scripts/` and `server/testdata/`) are
out of scope and listed in §1.6.

### 1.1 Server and launcher pairs (40)

Today: `server/testdata/<f>` and `launcher/app/src/test/resources/<f>`. The launcher reads its copy through the class
loader (`getResource("<f>")`); the server through `include_str!` or `std::fs::read` at `testdata/<f>`.

| File | Server compare test (goes) | Launcher compare test (goes) |
|---|---|---|
| `airplane_mode.json` | `tests::airplane_mode::the_contract_is_identical_to_the_launchers_copy` | `AirplaneModeTest` |
| `app_access.json` | `app_permissions::tests::the_contract` (compare lines only) | `AppAccessContractTest` |
| `app_icons.json` | `app_display::tests::the_table_is_the_shared_json` (compare lines only) | `AppGlyphsTest` |
| `app_logos.json` | `tests::app_logos::the_contract` (compare lines only) | `AppLogosTest` |
| `blocked_numbers.json` | `blocked_numbers::tests::the_copies_are_identical` | `BlockedNumbersTest` |
| `calendar_feeds.json` | `calendar_feeds::tests::the_contract_is_identical_to_the_launchers_copy` | `CalendarFeedsTest` |
| `calendar_kid_events.json` | `calendar_kid_events::tests::the_contract_is_identical_to_the_launchers_copy` | `KidCaldavTest` |
| `calendar_status_report.json` | `tests::calendar::the_contract_is_identical_to_the_launchers_copy` | `CalendarReportTest` |
| `calendar_title_rules.json` | `calendar::tests::the_vectors_are_identical_to_the_launchers_copy` | `TitleRulesTest` |
| `clock_skew.json` | `tests::clock_skew::the_contract_matches_the_launchers_copy` | `ClockSkewContractTest` |
| `contact_request.json` | `tests::contact_requests::the_contact_request_contract_matches_the_launchers_copy` | `ContactRequestsTest` |
| `device_key.json` | `tests::device_key::the_contract_matches_the_launchers_copy` | `DeviceKeyContractTest` |
| `dns_upstream_status.json` | `tests::custom_dns::the_dns_status_contract_matches_the_launchers_copy` | `DnsUpstreamStatusTest` |
| `emergency_numbers.json` | `emergency::tests::the_contract_matches_the_launchers_copy` | `EmergencyMenuTest` |
| `install_failure.json` | `install_failure::tests::the_contract_is_identical_to_the_launchers_copy` | `InstallFailureTest` |
| `installed_apps_report.json` | `tests::app_updates::the_installed_apps_contract_matches_the_launchers_copy` | `SyncScheduleTest` (compare lines only) |
| `launcher_tag_codes.json` | `install_now::tests::launcher_tag_codes_match_the_launchers_copy` | `SelfUpdatePlanTest` |
| `leave_tailnet.json` | `tests::leave_tailnet::the_leave_tailnet_contract_matches_the_launchers_copy` | `SecurityLowsBTest` (compare lines only) |
| `lock_now.json` | `lock_now::tests::the_contract_is_identical_to_the_launchers_copy` | `LockNowTest` |
| `login_windows.json` | `tests::logins::the_contract` (compare lines only) | `LoginWindowsTest` |
| `mobile_network.json` | `tests::mobile_network::the_contract` (compare lines only) | `MobileNetworkTest` |
| `notification_channels.json` | `tests::notification_channels::the_contract_is_identical_to_the_launchers_copy` | `NotificationChannelsTest` |
| `notification_listeners.json` | `notification_listeners::tests::the_contract_is_identical_to_the_launchers_copy` | `SystemReadersTest` |
| `phone_vectors.json` | `phone::tests::vectors_match_the_launchers_copy` | `PhoneNumbersTest` |
| `power_report.json` | `tests::power::the_power_contract_matches_the_launchers_copy` | `PowerEventsTest` |
| `restrictions_pause.json` | `models::paused_at_tests::the_longest_pause_is_the_shared_contracts` (compare lines only) | `RestrictionsPauseTest` (compare lines only) |
| `rule_pause.json` | `tests::rule_pause::the_contract_is_identical_to_the_launchers_copy` | `RulePauseTest` |
| `server_tls_report.json` | `tests::server_tls::the_contract_matches_the_launchers_copy` | `ServerPinsTest` |
| `server_url_vectors.json` | `config::tests::the_server_url_vectors` (compare lines only) | `ServerTransportTest` |
| `setup_steps.json` | `setup_status::tests::the_copies_are_identical` | `SetupStepsTest` |
| `shortcuts.json` | `tests::shortcuts::the_contract_is_identical_to_the_launchers_copy` | `ShortcutsTest` |
| `sms_contract.json` | `sms::tests::the_contract_matches_the_launchers_copy` | `SmsContractTest` |
| `status_heartbeat.json` | `tests::status_heartbeat::the_contract_matches_the_launchers_copy_and_the_constants` (compare lines only) | `StatusReportsTest` (compare lines only) |
| `time_request.json` | `tests::time_request::the_time_request_contract_matches_the_launchers_copy` | `TimeRequestsTest` |
| `time_status_report.json` | `tests::devices_list::the_time_report_contract_matches_the_launchers_copy` | `TimeStateZoneTest` |
| `unified_push.json` | `tests::unified_push::the_contract_is_identical_to_the_launchers_copy` | `UnifiedPushContractTest` |
| `weather.json` | `weather::tests` (the `LAUNCHER_COPY` compare) | `WeatherContractTest` |
| `zone_status_report.json` | `tests::zone_watch::the_zone_contract_matches_the_launchers_copy` | `ZoneReportTest` |
| `map_links/contract.json` | `integrations::map_links::tests::the_contract_is_the_launchers` | `MapLinksContractTest` |
| `wifi_lookup/contract.json` | `integrations::wifi_lookup::tests::the_contract_is_the_launchers` | `WifiLookupContractTest` |

Where a launcher class is named without "compare lines only", its one test named "... identical to the server's copy"
or "the two copies ... are identical" goes; the class stays. `app_icons.json` is in neither the launcher's nor the
server's CI filter today (§5.1); `scripts/material-symbols.sh` writes both copies.

### 1.2 Music files (7)

| File | Copies today | Readers | Compare tests (go) |
|---|---|---|---|
| `music_library.json` | `server/testdata/`, `music/.../test/resources/` | server `tests::music`, music `ContractFilesTest`, launcher `MusicPlanTest` (server copy in place), `scripts/fake-spotify.py` | music `ContractFilesTest` (one loop, 5 files) |
| `music_listing.json` | server, music | server `tests::music_sweep`, music, launcher `MusicPlanTest` (in place) | as above |
| `music_icons.json` | server, music | server `music_icons.rs` (generated), music `ContractFilesTest`, `scripts/material-symbols.sh` | as above |
| `music_state.json` | `server/testdata/` only | server `music::tests`; launcher `MusicPlanTest` and music `BridgeTest` read it in place | none (already one copy) |
| `spotify_hosts.json` | server, launcher, music | server `spotify::tests::hosts_match_the_contract`, launcher `MusicPlanTest`, music `SpotifyHostsTest` | server `hosts_match_the_contract` (compare loop only), launcher `MusicPlanTest` "Spotify's host list is the music app's and the server's", music `ContractFilesTest` |
| `storytel_token_answers.json` | server, launcher, music | server `tests::music_storytel`, launcher `StorytelTokenCourierTest`, music `StorytelBridgeTokenTest`, `scripts/fake-storytel.py` | server `the_token_route_answers_the_shared_contract` (compare lines only), launcher `MusicPlanTest` "Storytel's token answers are the server's and the music app's", music `ContractFilesTest` |
| `bridge_contract.json` | launcher, music | launcher `MusicPlanTest`, music `BridgeTest` | launcher and music "the bridge's names match the shared contract" (compare lines only) |

### 1.3 Files a release build compiles in (4)

| File | Copies today | Production reader | Compare tests (go) |
|---|---|---|---|
| `doh_hosts.txt` | `launcher/app/src/main/assets/dns/`, `server/testdata/` | launcher APK asset `dns/doh_hosts.txt` (`DnsFilterEngine`), server `dns_server.rs` `include_str!` (`BUILTIN_DOH_HOSTS`) | server `dns_server::tests::doh_hosts_match_the_launchers_asset`, launcher `DnsFilterEngineTest` (compare lines only) |
| `IMusicBridge.aidl`, `ISpotifyTokenCallback.aidl`, `IStorytelTokenCallback.aidl` | `launcher/` and `music/` `app/src/main/aidl/me/vibb/bridge/` | both apps' AIDL compile | launcher `MusicPlanTest` and music `BridgeTest` "the bridge's AIDL is byte-identical in both apps" (music's goes; the launcher's keeps its method-order check) |

### 1.4 Writers

| Writer | Writes today | After |
|---|---|---|
| `LOCK_NOW_CONTRACT_WRITE=1` (`lock_now::tests`) | `server/testdata/lock_now.json` | `contracts/core/lock_now.json` |
| `CALENDAR_FEEDS_CONTRACT_WRITE=1` (`calendar_feeds::tests`) | `server/testdata/calendar_feeds.json` | `contracts/core/calendar_feeds.json` |
| `CALENDAR_KID_EVENTS_CONTRACT_WRITE=1` (`calendar_kid_events::tests`) | `calendar_kid_events.json` and the server-only `calendar_kid_events/<uid>.ics` | the JSON in `contracts/core/`; the `.ics` stay in `server/testdata/` |
| `WEATHER_CONTRACT_WRITE=1` (`weather::tests`) | `server/testdata/weather.json` | `contracts/core/weather.json` |
| `MUSIC_LIBRARY_SNAPSHOT_WRITE=1` (`tests::music`) | `server/testdata/music_library.json` | `contracts/music/music_library.json` |
| `MUSIC_LISTING_SNAPSHOT_WRITE=1` (`tests::music_sweep`) | `server/testdata/music_listing.json` | `contracts/music/music_listing.json` |
| `scripts/device-key-vectors.py --write` / `--check` | `server/testdata/device_key.json` | `contracts/core/device_key.json` |
| `scripts/material-symbols.sh` | reads `server/testdata/app_icons.json`, `music_icons.json`; copies them to launcher and music; generates `app_icons.rs`, `music_icons.rs` and the apps' Kotlin | reads `contracts/core/app_icons.json`, `contracts/music/music_icons.json`; the two `shutil.copy` lines go |

Today each writer writes the server copy and a person copies it to the other side (the compare test fails until then).
After the move a writer's output is the only copy.

### 1.5 Scripts that read a contract

`scripts/release-version.sh` (`launcher_tag_codes.json`, its `--selftest` runs in all three changes jobs),
`scripts/fake-storytel.py` (`storytel_token_answers.json`), `scripts/fake-spotify.py` (`music_library.json`),
`scripts/material-symbols.sh` (above), `scripts/device-key-vectors.py` (above). Each changes one path constant in the
step that moves its file. The other scripts in `scripts/` read server-only or music-only fixtures and are unchanged.

### 1.6 Out of scope

- Server-only fixtures stay in `server/testdata/`: `zone_vectors.json`, `unified_push_gateway.json`,
  `unified_push_vapid.json`, `mmsc_hosts.txt` (compiled in by `mmsc_hosts.rs`, server-only), `map_links/server.json`,
  `wifi_lookup/server.json`, `calendar_feeds/contract.ics`, `calendar_kid_events/*.ics`, `attestation/`, `weather/`,
  `storytel/`, `spotify/`, `webauthn/`, `fdroid/`, `apk/`, `pwa_startup_screens.json`, and the rest.
- Launcher-only rules stay in `launcher/app/src/test/resources/`: `sms_rules.json`, `login_rules.json`,
  `up_rules.json`, `app_access_rules.json`, `clock_rules.json`, `map_link_rules.json`, `wifi_places.json`,
  `sms_reactions.json`, `server_pins/`.
- Music-only fixtures stay in `music/app/src/test/resources/`: `sonos/`, `spotify/`, `storytel/`.
- Byte-identical non-contracts: licence texts, Nunito, the Gradle wrappers, glyph drawables, icon SVGs, the two
  attestation captures. They are separate artefacts that happen to be equal; none has a compare test.
- Source-reading cross checks that are not data files stay as they are: `calendar::tests::kid_local_key_is_the_launchers`,
  `catalog_holds::tests::phone_min_sdk_is_the_launchers_min_sdk`, `tests::calls_simpler::the_avatar_palette_is_the_launchers`,
  `BuildChainTest`'s reads of `music/`.

## 2. Folder layout

```
contracts/
  README.md                       one row per file: path, design, writer, readers
  core/                           server <-> launcher (38 files, flat, today's names)
    airplane_mode.json ... zone_status_report.json
  integrations/                   server <-> launcher, one folder per integration (design 110's boundary)
    map_links/contract.json
    wifi_lookup/contract.json
  music/                          what the music app reads; server and launcher may read it too (7 files)
    bridge_contract.json  music_icons.json  music_library.json  music_listing.json
    music_state.json  spotify_hosts.json  storytel_token_answers.json
  shipped/                        compiled into a release build - the only root that is
    bridge-aidl/me/vibb/bridge/   IMusicBridge.aidl  ISpotifyTokenCallback.aidl  IStorytelTokenCallback.aidl
    launcher-assets/dns/          doh_hosts.txt
```

Decisions:

- **Names stay.** Each root is a class-loader root, so `getResource("phone_vectors.json")` and
  `getResource("map_links/contract.json")` resolve exactly as today. No Kotlin read changes.
- **`music/` is its own root** so the music app's test classpath and its CI filter take only what it reads.
  `spotify_hosts.json` and `storytel_token_answers.json` stay there even though they belong to integrations: the
  music app reads them by their bare names, and moving them under `integrations/` is audit round I5's business.
- **`shipped/` is separate** so no test root ever lands in an APK and no shipped file is mistaken for test data. Its
  sub-folders mirror the source-set type that reads them (`aidl`, `assets`), so the package path and the asset path
  (`dns/doh_hosts.txt`, `DOH_HOSTS_ASSET`) are unchanged.
- **A new contract**: a file under the root of its readers, a README row, both sides' tests read it in place. A new
  integration's contract goes to `integrations/<key>/contract.json` (design 110's pattern; `integrations/mod.rs`'s doc
  comment changes to say so).
- One root per reader-set, no deeper nesting: 38 files in `core/` are easier to find than a split by feature, and the
  README groups them.

## 3. How each build reads it

### 3.1 Server (Cargo)

A new module `src/contracts.rs`, declared `#[cfg(test)] mod contracts;` in `main.rs` - the whole module is test-only:

```rust
/// A contract file's text, compiled in: `crate::contracts::contract!("core/weather.json")`.
macro_rules! contract {
    ($p:literal) => { include_str!(concat!(env!("CARGO_MANIFEST_DIR"), "/../contracts/", $p)) };
}
pub(crate) use contract;

/// A contract file's path, for the tests that read or write it at run time.
pub(crate) fn path(p: &str) -> std::path::PathBuf {
    std::path::Path::new(env!("CARGO_MANIFEST_DIR")).join("../contracts").join(p)
}
```

`pub(crate) use contract;` re-exports the macro by path, as `i18n.rs` does with `pub(crate) use words;`; callers write
`crate::contracts::contract!(...)` (or import it) and never depend on textual macro order.

- Every `include_str!("../testdata/<f>")` / `"../../testdata/<f>"` of a moved file becomes
  `crate::contracts::contract!("core/<f>")`
  (about 45 sites); every `std::fs::read("testdata/<f>")` and `CARGO_MANIFEST_DIR.join("testdata/<f>")` becomes
  `contracts::path("core/<f>")` (about 20 sites, the four `*_WRITE` writers among them). A run-time path no longer
  depends on the test's working directory.
- `dns_server.rs` (production): `const BUILTIN_DOH_HOSTS: &str = include_str!("../../contracts/shipped/launcher-assets/dns/doh_hosts.txt");`
  - a literal `include_str!` relative to the source file; it does not use the test-only module.
- rustc records every `include_str!` target in the dep-info, so `cargo test` and `cargo build` rebuild when a contract
  changes, inside the crate or not. No `build.rs` is needed.
- `server/Cargo.toml` has no `include`/`exclude`, and nothing runs `cargo package` or `cargo publish`; a crate that
  reads above its directory is fine here (the tests already `include_str!` `../../../.github/workflows/*.yml`).

### 3.2 Launcher and music (Gradle, JVM unit tests)

Both apps' unit tests are plain JVM tests (no Robolectric; music sets only `unitTests.isReturnDefaultValues`). In
`app/build.gradle.kts`:

```kotlin
// launcher/app/build.gradle.kts
val contracts = rootProject.layout.projectDirectory.dir("../contracts")
android {
    sourceSets {
        getByName("test").resources.srcDirs(contracts.dir("core"), contracts.dir("integrations"), contracts.dir("music"))
        getByName("main").aidl.srcDir(contracts.dir("shipped/bridge-aidl"))
        getByName("main").assets.srcDir(contracts.dir("shipped/launcher-assets"))
    }
}
// music/app/build.gradle.kts
    getByName("test").resources.srcDir(contracts.dir("music"))
    getByName("main").aidl.srcDir(contracts.dir("shipped/bridge-aidl"))
```

- A source-directory copy task is not used: `srcDir` is what Gradle and Android Studio already understand, it has no
  generated output to go stale, and it keeps the class-loader names.
- `rootProject.layout.projectDirectory` is resolved at configuration time without reading any file, so the
  configuration cache (on in both `gradle.properties`) stays valid; a changed contract invalidates only the task
  inputs (`processDebugUnitTestJavaRes`, `testDebugUnitTest`), as a changed copy does today.
- The tests that read a file by repo path switch to the class loader: `MusicPlanTest` and `BridgeTest`
  (`music_library.json`, `music_listing.json`, `music_state.json`), and `DnsFilterEngineTest` reads
  `contracts/shipped/launcher-assets/dns/doh_hosts.txt` by path (an asset is not on the test classpath). A by-path read
  is not a task input, so `build.gradle.kts` declares the directory one: `tasks.withType<Test>().configureEach {
  inputs.dir(contracts.dir("shipped/launcher-assets")).withPathSensitivity(PathSensitivity.RELATIVE) }`, and a changed
  list re-runs the test instead of reading as up to date. The same applies to `contracts.dir("shipped/bridge-aidl")`
  for the launcher's AIDL method-order check, which also reads by path. The launcher's
  AIDL method-order check reads `contracts/shipped/bridge-aidl/...` by path.
- Both modules' unit tests already run with `launcher/app/` (or `music/app/`) as the working directory and already
  read `../../server/...`; nothing new about reaching outside the module.

### 3.3 Gradle verification and caching

- `gradle/verification-metadata.xml`: unchanged. A source directory is not a dependency; no artifact is resolved.
- The Gradle build cache is off in both projects (`org.gradle.caching` unset); CI's `setup-java` `cache: gradle`
  caches dependencies and the wrapper only. Nothing to change.
- File-system watching covers the project directory only; files under `../contracts` are hashed at each build
  instead. With about 50 small files this is noise.
- `BuildChainTest` is unaffected by the move itself and gains the filter rows of §6.2.
- The release APK carries the same bytes: `assets/dns/doh_hosts.txt` and the AIDL-generated classes are produced from
  identical input.

## 4. Docker and CI build contexts

- `server/Dockerfile` copies only `dist/<arch>/kid_phone_server`, `dist/static`, `dist/NOTICE.md`, `dist/LICENSES` and
  `deploy/distrusted-roots.txt`; `.dockerignore` sends nothing else. No Rust is compiled in the image, so no contract
  is read there. Unchanged.
- The binary is built by `cargo build --release` in `server-ci.yml` (`server-build`, and `server-image-context`, which
  builds the binary the image job's context holds) and `server-release.yml` (`server-release-build`), each after a full
  `actions/checkout` (the only `sparse-checkout` in the workflows is `server-release-scripts`, which uploads
  `server/deploy/` scripts and builds nothing). `scripts/dev-rebuild.sh` builds from the repo. All three see
  `contracts/`.
- The launcher and music release builds (`launcher-release-build`, `music-release-build`) use full checkouts too.

### 4.1 The one production read outside the crate

`BUILTIN_DOH_HOSTS` is the only contract the server binary compiles in (`mmsc_hosts.txt` is server-only and stays).
After the move the server crate cannot be built from a copy of `server/` alone. Nothing does that today; the rule is
written into `contracts/README.md` and `server/CLAUDE.md`: the server builds from the monorepo checkout, and a future
Dockerfile that compiles must use the repo root as its context. The alternative - keeping the DoH list in
`server/testdata/` and having the launcher read it from there - was rejected: the launcher's APK would then ship a file
from the server's test folder.

## 5. CI path filters

The three changes jobs' `grep -qE` alternatives today list every copy by name (launcher.yml's filter line is about
1.6 KB, with a matching comment block of about 20 lines). After:

Each workflow's whole filter after step 8, literally (the `grep -qE` pattern; the non-contract alternatives are
today's, unchanged):

`launcher.yml` (`launcher-changes`) - it reads all four roots:

```
^(launcher/|contracts/|\.github/workflows/launcher\.yml$|\.github/workflows/music\.yml$|\.github/actions/build-tsnet/|music/gradle/|music/app/src/main/AndroidManifest\.xml$|scripts/yr-symbols(\.py|-11\.svg)$|scripts/fake-portal\.py$)
```

`server-ci.yml` (`server-changes`, the build pattern) - it reads `core/`, `integrations/`, `music/` and
`shipped/launcher-assets/`; the AIDL is the one file it doesn't read, and an extra server run on an AIDL change is
cheaper than a second rule:

```
^(server/|contracts/|\.github/workflows/server-ci\.yml$|launcher/app/src/main/java/com/kidslauncher/mdm/ui/home/HomeModel\.kt$|scripts/mmsc-hosts(\.py|-sample\.xml)$|scripts/wifi-lookup-fixtures\.py$|scripts/grapheneos-boot-keys(\.py|-sample/.*)$|scripts/(device-key|emulator-attestation)-vectors\.py$|scripts/emulator-attestation-captures/)
```

`server-ci.yml`'s image pattern is unchanged.

`music.yml` (`music-changes`) - `server/testdata/spotify/` and `server/testdata/storytel/` stay: they are not
contracts, but `scripts/fake-spotify.py` and `scripts/fake-storytel.py` read them and their `--selftest`s run after the
build:

```
^(music/|contracts/(music|shipped/bridge-aidl)/|\.github/workflows/music\.yml$|server/testdata/spotify/|server/testdata/storytel/|scripts/fake-sonos\.py$|scripts/fake-storytel\.py$|scripts/fake-spotify\.py$|scripts/fake-audio\.py$|scripts/music-splash\.py$|launcher/app/src/main/res/drawable/splash_vibb_breathe\.xml$|scripts/music-icon\.py$|launcher/app/src/main/res/drawable/ic_launcher_foreground\.xml$)
```

Removed: every `server/testdata/<contract>`, `launcher/app/src/test/resources/<contract>`,
`music/app/src/test/resources/<contract>`, `launcher/app/src/main/aidl/`, `music/app/src/main/aidl/`,
`launcher/app/src/main/assets/dns/doh_hosts.txt` and `server/testdata/doh_hosts.txt` alternative. The comment blocks at
the top of `launcher.yml` and in `server-ci.yml` shrink to one sentence ("and `contracts/`, which both sides read"), and
`server-build`'s comment "The full checkout matters: phone::tests compares testdata/phone_vectors.json with the
launcher's copy" says the tests read `../contracts/`.

### 5.1 Gaps the change closes

- `app_icons.json`: neither `launcher.yml` lists `server/testdata/app_icons.json` nor `server-ci.yml` lists the
  launcher's copy, so a one-sided edit runs only one compare. One copy and `contracts/` close it.
- `music_icons.json`: `server-ci.yml` does not list music's copy.
- `phone_vectors.json`: neither `launcher.yml` lists `server/testdata/phone_vectors.json` nor `server-ci.yml` the
  launcher's copy.

## 6. Tests

### 6.1 What goes and what stays

| | Server | Launcher | Music | Total |
|---|---|---|---|---|
| Copy-compare tests deleted | 32 (§1.1's server column without "compare lines only", and `doh_hosts_match_the_launchers_asset`) | 38 (§1.1's 36 classes' compare test, `MusicPlanTest`'s Storytel and Spotify compares) | 2 (`ContractFilesTest` "the shared files are identical to the server's copies"; `BridgeTest` "the bridge's AIDL is byte-identical in both apps" - the launcher keeps the one method-order check of the one file) | 72 |
| Tests trimmed (compare lines out, the rest stays) | 11: `app_permissions::the_contract`, `app_display::the_table_is_the_shared_json`, `tests::app_logos::the_contract`, `tests::logins::the_contract`, `tests::mobile_network::the_contract`, `config::the_server_url_vectors`, `models::the_longest_pause_is_the_shared_contracts`, `tests::status_heartbeat::..._and_the_constants`, `tests::music_storytel::the_token_route_answers_the_shared_contract`, `spotify::hosts_match_the_contract`, `weather::tests`' compare | 7: `RestrictionsPauseTest`, `SecurityLowsBTest`, `StatusReportsTest`, `SyncScheduleTest`, `DnsFilterEngineTest`, `MusicPlanTest` "the bridge's names" and its AIDL test (method order kept) | 1: `BridgeTest` "the bridge's names" | 19 |

(Exact counts are re-taken in each step; the tables in §1 are the list.)

Everything that gives a contract its meaning stays: each side encodes, decodes and runs every row it ran before
(`the_contract`, `PolicyResponseCompatTest`, `policy_json_keys_snapshot`, the `*_WRITE` producers, the vector runners).
Only "is my copy the other side's copy" goes, because there is no other copy.

### 6.2 New guards (three, small)

1. **README test** (server, `contracts::tests::every_file_has_a_row`): every file under `contracts/` is named in
   `contracts/README.md`'s table, and every row names an existing file. The server's CI runs on any `contracts/` change,
   so a file added without a row fails there.
2. **Shadow test** (launcher `BuildChainTest`, music `ContractFilesTest`): no name in a contract root also exists in
   the module's own `src/test/resources/` (nor, for the launcher, under `src/main/assets/dns/`, nor either app's
   `src/main/aidl/me/vibb/bridge/`). A leftover or re-added copy would otherwise shadow the contract on the class
   path. Gradle's resource merge may already fail on a duplicate, but the test does not rely on that.
3. **Filter rows**: `BuildChainTest` already parses `launcher.yml`'s filter; it gains "the filter matches every file
   under `contracts/`" and "music.yml's filter matches every file under `contracts/music/` and
   `contracts/shipped/bridge-aidl/`". The server's `tests::deploy_scripts` gains "server-ci.yml's filter matches every
   file under `contracts/` but the AIDL".

Plus one release-side check: the launcher's existing release APK scan task (the debug-hooks scan, a dependency of
`assembleRelease`) also asserts `assets/dns/doh_hosts.txt` is in the APK, so a wrong `assets.srcDir` fails the build
instead of shipping a launcher without its built-in DoH blocks.

## 7. Documentation

- **Root `CLAUDE.md`**: the "Shared between the two and checked by tests on both sides" text (from "Shared between the
  two" to "## CI and releases", about 46 KB of the file's 67 KB) becomes one paragraph:

  > Files both sides read live once in `contracts/` (`core/` server and launcher, `integrations/<key>/`, `music/` what
  > the music app reads, `shipped/` compiled into a release build); `contracts/README.md` lists each with its design,
  > writer and readers. Gradle reads the roots as test resources (the AIDL and the DoH asset as main sources), the
  > server through `contract!` / `contracts::path`. A change to the API and its contract file go in one commit; a new
  > contract gets a README row (the server's test checks). The policy JSON shape stays pinned by
  > `policy_json_keys_snapshot` and `PolicyResponseCompatTest`.

  The CI section's filter description shrinks the same way. The per-contract prose (which keys each file holds, which
  rows each test runs) is not carried over: it is in each design note and in each test's doc comment, and the README
  row points at the design section.
- **`contracts/README.md`**: the layout rule of §2, the "build from the monorepo" rule of §4.1, and a table
  `file | design | written by | read by` (one row per file, at most two lines). Readers include scripts and release
  workflows: `launcher_tag_codes.json`'s row names the server, the launcher and `scripts/release-version.sh`, through
  which both the launcher's and the music app's release workflows read it (its launcher and music rows).
- **`server/CLAUDE.md`, `launcher/CLAUDE.md`, `music/CLAUDE.md`**: the sentences on byte-identical copies become
  pointers to `contracts/` (music's "Build and test" paragraph, launcher's "Release builds" mention of
  `launcher_tag_codes.json`, music's bridge rule "The AIDL is byte-identical with `launcher/`").
- **Doc comments in code** that say "byte-identical in the launcher's test resources" (123 files mention a copy) are
  reworded in the step that moves their file.
- **`docs/notes/`** (about 25 files) and `docs/testing/emulator.md`: paths updated in the last step. `docs/design/`
  and `docs/review/` are history and stay; the README has one line: `server/testdata/<f>` before 2026-11 is now
  `contracts/<root>/<f>`.

## 8. Step plan

Each step is one commit (2 and 3 may be two each), run with `cargo test`, `cargo fmt --check`, `cargo clippy
--all-targets`, both apps' `./gradlew assembleDebug assembleRelease testDebugUnitTest -PwarningsAsErrors=true`
before the push. A step that touches files on two sides triggers both builds, so CI checks each move from both ends.

1. **Scaffolding.** `contracts/README.md` (rules, empty table), `server/src/contracts.rs` (macro, `path`, the README
   test over whatever exists), the Gradle `srcDir` lines for the four roots (a missing directory is ignored), the
   three workflow filters gain `contracts/` alongside the old alternatives, the filter rows of §6.2. Nothing moves;
   green by construction.
2. **`core/`, first half** (`airplane_mode` to `leave_tailnet`, 18 files): `git mv server/testdata/<f>
   contracts/core/<f>`, `git rm` the launcher copy, the server reads through `contract!`/`contracts::path`, the
   compare tests go on both sides, doc comments updated, README rows added, `release-version.sh` and
   `device-key-vectors.py` paths, `material-symbols.sh`'s app-icon copy line. The writers (`CALENDAR_*_WRITE`) now
   write here.
3. **`core/`, second half** (`lock_now` to `zone_status_report`, 20 files), the same; `LOCK_NOW_` and
   `WEATHER_CONTRACT_WRITE` move.
4. **`integrations/`**: the two `contract.json`s; `integrations/mod.rs`'s doc on where a contract folder lives.
5. **`music/`**: the 7 files from server, launcher and music; `ContractFilesTest`'s compare becomes the shadow test;
   `MusicPlanTest` and `BridgeTest` read through the class loader; `MUSIC_*_SNAPSHOT_WRITE`, `fake-spotify.py`,
   `fake-storytel.py`, `material-symbols.sh`'s music copy line. `music.yml` keeps `server/testdata/spotify/` and
   `server/testdata/storytel/` (the fakes' own fixtures, not moved).
6. **`shipped/launcher-assets/`**: `doh_hosts.txt`, the launcher's `assets.srcDir`, `dns_server.rs`'s `include_str!`,
   the APK asset check, `DnsFilterEngineTest`'s path; `assembleRelease` and `unzip -l` on the APK checked by hand once.
7. **`shipped/bridge-aidl/`**: the three AIDL files, both apps' `aidl.srcDir`, the AIDL compares go, the launcher's
   method-order check reads the one file. Both apps' `assembleRelease` checked.
8. **Filters and docs**: the old alternatives leave the three filters (they name paths that no longer exist) and each
   becomes §5's literal pattern, the workflow comments shrink, the root `CLAUDE.md` section is replaced by §7's
   paragraph, the README table is checked complete. The per-file doc lines are already done by then (below).

**In every move step (2 to 7)**, for each file it moves, the same commit also changes: the file's own text that names
its location (an `about`/`_doc` key or comment inside it, where it has one); the doc comments in the code that read it
("byte-identical in the launcher's test resources" becomes "`contracts/core/<f>`"); the script strings that name it
(e.g. `scripts/device-key-vectors.py`'s docstring, usage line and `SystemExit` message with
`server/testdata/device_key.json`); and its lines in the root `CLAUDE.md`, the app `CLAUDE.md`s, `docs/notes/` and
`docs/testing/emulator.md`. Step 8 then only rewrites the root section as a whole, and no doc names a deleted path
between steps.

Steps 2 to 7 are independent of each other and could be reordered; 1 comes first and 8 last. Each is mechanical and
fits one review. Old filter alternatives stay until step 8 so a half-moved tree never skips a build.

## 9. Risks

| Risk | Mitigation |
|---|---|
| A wrong `srcDir` leaves a test without its file | Every reader already asserts its resource is non-null (`getResource(...)!!`); a missing root fails the reading test, not silently. |
| The DoH list missing from the release APK | The APK scan's asset assertion (§6.2) and step 6's manual `unzip -l`. |
| A stale copy re-added in a module shadows the contract | The shadow tests (§6.2). |
| A contract change skips a build | Filters on `contracts/` plus the filter-row tests; the old alternatives stay until the move is done. |
| Opening `launcher/` or `music/` alone in an IDE, or building `server/` from a copy of its folder | The external roots resolve as long as the repo is checked out whole, which every build does (§4). `contracts/README.md` and `server/CLAUDE.md` say so. The gitignored old standalone clones (`kids-launcher-mdm/`, `kid-phone-server/`) are not built. |
| `git log` of a moved file stops at the move | `git mv` keeps it findable with `--follow`; the README's one-line mapping covers prose that names old paths. |
| Branches in flight (worktrees) that edit a copy | They conflict at the merge on a deleted file; the fix is to apply the edit to `contracts/`. Steps are best merged when few branches touch contracts, and each step's commit message names the files it moved. |
| AGP or Kotlin warns about a source directory outside the module | `-PwarningsAsErrors` covers Kotlin compile warnings only; step 1 adds the `srcDir`s before anything moves, and its build with `-PwarningsAsErrors=true` shows any warning. |

## 10. Questions for the owner

None. The change has no visible cost on a phone, in the PWA or in a release; it moves test data and two build inputs,
and every build reads the same bytes as before.

## 11. Revision 2: the design QA

| QA | Taken |
|---|---|
| M1 | `music.yml` keeps `server/testdata/spotify/` and `server/testdata/storytel/`; §5 states each workflow's whole after-filter literally; §8 step 5. |
| M2 | §3.1: the whole `contracts` module is `#[cfg(test)]`, the macro re-exported with `pub(crate) use contract;` and called as `crate::contracts::contract!`; `dns_server.rs` keeps a literal `include_str!`. |
| L1 | §5.1 adds `phone_vectors.json`. |
| L2 | §0: 103 files today, 52 copies go (49 data, 3 AIDL), 51 stay. |
| L3 | §8: each move step also changes the file's own about/comment text and the script strings that name it (`device-key-vectors.py`). |
| L4 | §3.2: the by-path reads (`DnsFilterEngineTest`, the AIDL method order) get their `contracts/shipped/` directories as test task inputs. |
| L5 | §9: the tsnetStub example is dropped; step 1's build is the check. |
| L6 | §8: each moved file's `CLAUDE.md`/notes lines change in its own step, not in step 8. |
| Also | §4 names `server-image-context`; §7's README row for `launcher_tag_codes.json` names `release-version.sh` and the music release. |
