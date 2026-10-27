# handy (repo `palchrb/vibb-launcher`)

A kid's phone setup: an Android launcher that is also the Device Owner agent, and the self-hosted
admin server it talks to. One repo since 2026-10-05; both parts were separate forks before and were
imported with full history (`git subtree`), so old commit hashes in the docs still resolve.

## Layout

- `launcher/` - Android app, called **Vibb One** for users (debug "Vibb One (debug)", design 34 P11) - Kotlin,
  Gradle; applicationId `me.vibb.launcher`, debug `me.vibb.launcher.debug`, since 2026-10-06 - the Kotlin
  namespace/class names stay `com.kidslauncher.mdm` and the release asset stays `kids-launcher-mdm.apk`. Was the
  `kids-launcher-mdm` fork. Details: `launcher/CLAUDE.md`.
- `server/` - admin server + device API (Rust/Axum/SQLite). Was the `kid-phone-server` fork.
  Details: `server/CLAUDE.md`, deployment `server/DEPLOY.md` (Pi + systemd, and the Docker image
  from `server/Dockerfile`).
- `docs/notes/server/` - the server's area notes, moved out of `server/CLAUDE.md` (which keeps the core and an index)
- `music/` - the Vibb music app (Kotlin, Compose, Media3; applicationId `me.vibb.music`, debug
  `me.vibb.music.debug`), designs 20/21/21b, Sonos 26a, Storytel 26b, Spotify 26c. Details: `music/CLAUDE.md`.
- `docs/design/`, `docs/review/` - design notes and reviews, one per step. Historical: `S` =
  `kid-phone-server` = `server/`, `L` = `kids-launcher-mdm` = `launcher/`, "branch `handy`" = what is
  now `main` here. `docs/testing/emulator.md` - the emulator test loop. `docs/setup/` - setup
  runbooks (`google-account.md`: the phone's Google account for Play, backup to Google off;
  `android-settings.md`: Android's settings an app can't set back - Touch feedback on, the "Modify system settings" grant).
- `scripts/` - `dev-rebuild.sh` (pull, build + install the debug launcher, run the server),
  `release-version.sh` (a `launcher-v`/`music-v` tag -> versionName, versionCode, prerelease: both apps' release
  workflows use it; `--selftest`, run by both workflows' always-run changes job and before every tag build),
  `push-all.sh` (push the current branch), `smoke-test.sh` (adb/emulator-console smoke test of lock and
  calls, remote-capable; `docs/testing/emulator.md` §5b), `fake-sonos.py` (fake Sonos speakers for the
  music app on the emulator, answering from the music app's Sonos test fixtures; §6h), `fake-storytel.py` (a fake
  Storytel - the login, the asset call's 302, a CDN of silent books - answering from the music app's Storytel test
  fixtures; §6i), `fake-spotify.py` (a fake Spotify - the accounts service with PKCE, the Web API the server and the
  phone use, a stub Web Playback SDK playing a tone - answering from `server/testdata/spotify/` and the music app's
  Spotify test fixtures; design 26c §9, `docs/testing/emulator.md` §6l), `fake-audio.py` (a fake podcast host of
  silent episodes with Range; `--vary` serves another length per request, design 30's changed-source check; §6f;
  `--feed N` an RSS feed of N items with their own pictures the server judges `own`, `--hls K` item K as HLS, design
  39's list pictures and order flip; §6q), `pwa-startup-images.py` (the PWA's iPhone start-up PNGs and head.html's
  links from `server/testdata/pwa_startup_screens.json`; `--selftest`; `docs/notes/server/pwa.md`, "Start-up splash"),
  `music-splash.py` (the music app's launch splash drawables: the Vibb mark read from the launcher's Home splash, "vibb
  music" from Nunito's outlines; `--selftest`).
- `PLAN.md` - the plan and status, and the fix backlog ("Fix backlog": keep it current as items come in and are done).
- `.github/` - CI for both parts (see below).
- `kids-launcher-mdm/`, `kid-phone-server/` (gitignored, if present) - the old standalone clones.
  Don't edit them; work in `launcher/` and `server/`.

## Build and test

Server (from `server/`): `cargo test`, `cargo fmt --check`, `cargo clippy --all-targets`.

Launcher (from `launcher/`, JDK 17, `local.properties` with `sdk.dir=...`):
`./gradlew assembleDebug assembleRelease testDebugUnitTest`. Music app (from `music/`, the same JDK and
`local.properties`): `./gradlew assembleDebug assembleRelease testDebugUnitTest -PwarningsAsErrors=true`.
Without `app/libs/tsnet.aar` the debug build falls
back to a stub and a release build fails (security review L13; `-PrequireTsnet=true` fails any build). No Firebase config is needed: FCM was removed in design 19 (the
SSE stream is the only nudge), and `assembleRelease` fails if Firebase or Play services comes back on the
release classpath.

Gradle build chain (security review L9): both wrappers pin the distribution (`distributionSha256Sum`), CI validates
both `gradle-wrapper.jar`s (`gradle/actions/wrapper-validation`, pinned by commit) before Gradle runs, and both apps
verify every dependency's SHA-256 against `gradle/verification-metadata.xml` (no signatures; sources/javadoc jars
trusted for the IDE). A new or bumped dependency or plugin fails the build until its checksums are added: run
`./gradlew --write-verification-metadata sha256 --continue help build` (launcher: with `libs/tsnet.aar` present) and
review the diff - new entries are trust on first use, so compare them with the publisher's `.sha1`/`.sha256` where it
has them. CI's aapt2 (`com.android.tools.build:aapt2:<AGP>-<build>:linux`) is never resolved on an aarch64 machine
with the `aapt2FromMavenOverride`: an AGP bump needs its entry by hand (resolve it in a throwaway build, as on
2026-10-11).

Shared between the two and checked by tests on both sides: `server/testdata/phone_vectors.json` and
`launcher/app/src/test/resources/phone_vectors.json` must be identical (server `phone::tests`,
launcher `PhoneNumbersTest`). The policy JSON shape is pinned on both sides
(`policy_json_keys_snapshot` / `PolicyResponseCompatTest`). A change to the API goes into both
directories in the same commit. The music library and an entry's episode list are pinned by
`server/testdata/music_library.json` and `server/testdata/music_listing.json` (`music_library_snapshot`,
`music_listing_snapshot`, regenerated with `MUSIC_LIBRARY_SNAPSHOT_WRITE=1` / `MUSIC_LISTING_SNAPSHOT_WRITE=1`); the
music app keeps byte-identical copies in `music/app/src/test/resources/` (with `music_icons.json`;
`ContractFilesTest`), and a change goes into both in one commit. The launcher-music bridge's AIDL
(`*/app/src/main/aidl/me/vibb/bridge/`) and the names both apps spell (`bridge_contract.json` in both apps'
test resources) are byte-identical in `launcher/` and `music/` (`MusicPlanTest`, `BridgeTest`); new methods are
appended. `server/testdata/music_state.json` is every `music_state` field: the server keeps them all, the launcher
keeps the music app's, the music app sends exactly those. Spotify's host list `server/testdata/spotify_hosts.json`
(design 26c §6.2; provisional until the DNS capture) is byte-identical in both apps' test resources
(`spotify::tests::hosts_match_the_contract`, `ContractFilesTest`, `MusicPlanTest`); a change goes into all three in
one commit, with each side's constants (`HOST_SUFFIXES`/`EXACT_HOSTS`, `SPOTIFY_HOST_SUFFIXES`/`SPOTIFY_EXACT_HOSTS`).
The launcher's status body with `time_state.zone` (design 34 P13) is `server/testdata/time_status_report.json`,
byte-identical in `launcher/app/src/test/resources/`: the launcher's `TimeStateZoneTest` encodes exactly it, the
server's `devices_list::a_launcher_report_with_its_zone_shows_the_rules_end` posts it, and both compare the copies.
The launcher's status body with `dns_upstream` (design 36 §4.2) is `server/testdata/dns_upstream_status.json`,
byte-identical in `launcher/app/src/test/resources/`: the launcher's `DnsUpstreamStatusTest` encodes exactly it, the
server's `custom_dns::dns_upstream_status_contract` posts it, and both compare the copies. The policy's `dns_upstream`
is pinned by `policy_json_keys_snapshot` and the launcher's `PolicyResponseCompatTest`.
The versionCode a launcher release tag was built with is `server/testdata/launcher_tag_codes.json` (tag -> code or null),
byte-identical in `launcher/app/src/test/resources/`: the launcher's `SelfUpdatePlanTest` (``a release tag's
versionCode is the shared contract's``, ``the tag codes are identical to the server's copy``) and the server's
`install_now::tests::launcher_tags_read_as_the_shared_contract` / `launcher_tag_codes_match_the_launchers_copy` read
every `codes` row and compare the copies; the server's `device_pages::tests::music_versions_read_as_the_shared_contract`
reads the `music_codes` rows (the music app's version from its code), and `scripts/release-version.sh --selftest`
checks both lists' rows against what the workflows build.
The launcher's `POST /api/devices/power` body (design 38 §2; since 2026-10-25 its `off` with the shutdown's last stored fix,
`location`) is `server/testdata/power_report.json`, byte-identical in
`launcher/app/src/test/resources/`: the launcher's `PowerEventsTest` encodes exactly it, the server's
`power::a_launcher_report_raises_both` posts it (its instants moved to the last hour), and both compare the copies.
The launcher's status body with `extra_locations` and `zone_watch` (design 64 step 2) is
`server/testdata/zone_status_report.json`, byte-identical in `launcher/app/src/test/resources/`: the launcher's
`ZoneReportTest` encodes exactly it, the server's `zone_watch::a_launcher_report_raises_the_arrival` posts it (its
instants moved to the last minutes), and both compare the copies. The policy's `location_policy.zones` is pinned by
`policy_json_keys_snapshot` and the launcher's `PolicyResponseCompatTest`. `server/testdata/zone_vectors.json` stays the
server's alone: the phone judges no zone (design 64 decision 1).
The launcher's status body with `installed_apps[].version_code`/`version_name` (2026-10-25, the `app_updated` of every
checked catalog app) is `server/testdata/installed_apps_report.json`, byte-identical in
`launcher/app/src/test/resources/`: the launcher's `SyncScheduleTest` encodes exactly it, the server's
`app_updates::the_installed_apps_contract_raises_app_updated` posts it, and both compare the copies.
The launcher's `POST /api/devices/time-request` body and the three answers (design r13 §2.5) are
`server/testdata/time_request.json`, byte-identical in `launcher/app/src/test/resources/`: the launcher's
`TimeRequestsTest` encodes exactly its `request` and decodes each answer, the server's
`time_request::the_contract_answers` posts it (its instant moved to a minute ago) and produces every answer row, and
both compare the copies.
Dagen's title rules (design 69 §3.4) are `server/testdata/calendar_title_rules.json` (`{"titles": [{rules, calendar,
title, description?, shown}]}`, `shown: null` = hidden), byte-identical in `launcher/app/src/test/resources/`: the
server's `calendar::tests::the_title_rules_vectors` and the launcher's `TitleRulesTest` run every row and compare the
copies. The launcher's status body with `calendar` (design 69 §4.2) is `server/testdata/calendar_status_report.json`,
byte-identical there too: the launcher's `CalendarReportTest` encodes exactly it, the server's
`calendar::a_launcher_report_lists_the_calendars` posts it, and both compare the copies. The policy's `calendar` is pinned
by `policy_json_keys_snapshot` and the launcher's `PolicyResponseCompatTest`.
The answers of `POST /api/devices/music/storytel/token` (design 58 §2.2) are `server/testdata/storytel_token_answers.json`,
byte-identical in both apps' test resources: the server's `music_storytel::the_token_route_answers_the_shared_contract`
produces every row, the launcher's `StorytelTokenCourierTest` and the music app's `StorytelBridgeTokenTest` read each,
and all three compare the copies (`MusicPlanTest`, `ContractFilesTest`); a change goes into the three in one commit.
`scripts/fake-storytel.py` reads its login and bookshelf from `server/testdata/storytel/` (`fake_login_*.json`,
`aes_vectors.json`, `bookshelf.json`; the server's `fake_login_fixtures_match`) and answers its `/fake/jwt` (the phone
alone, design 58 §5.6) from `storytel_token_answers.json`'s rows; since design 58 step 2 the music app has no login
fixtures (the phone never logs in).
The launcher's built-in secure-DNS hosts `launcher/app/src/main/assets/dns/doh_hosts.txt` (design 67 §2.2) are
byte-identical in `server/testdata/doh_hosts.txt`, which the server compiles in for the Allowed Domains notice
(`dns_server::is_builtin_doh_host`): the server's `dns_server::tests::doh_hosts_match_the_launchers_asset` and the
launcher's `DnsFilterEngineTest` compare the copies; a change goes into both in one commit.
The calendar links' contract (design 72 §3) is `server/testdata/calendar_feeds.json`, byte-identical in
`launcher/app/src/test/resources/`: `feed` (the document `GET /api/devices/calendar/feeds/{uid}` serves for
`server/testdata/calendar_feeds/contract.ics`), `keys` (a link's calendar key), `policy` (a `calendar.feeds` row),
`report` (a status report's `calendar.feeds` row), `zone` and `provider` (the Events rows the phone writes in that
zone). The server's `calendar_feeds::tests::the_contract` produces `feed` and `keys` (`CALENDAR_FEEDS_CONTRACT_WRITE=1`
rewrites them), `intake_limits` keeps `report`'s keys; the launcher's `CalendarFeedsTest` maps `feed` to exactly
`provider`, checks the key and decodes and encodes the rows; both compare the copies. The policy's `calendar.feeds` is
pinned by `policy_json_keys_snapshot` and the launcher's `PolicyResponseCompatTest`.

## CI and releases

- `.github/workflows/launcher.yml`, `server-ci.yml` and `music.yml` run on every push/PR, but their build job
  (`launcher-build`, `server-build`, `music-build`) only runs when its own directory or workflow changed
  (`launcher.yml` also on `.github/actions/build-tsnet/` and the music files its tests read: the bridge, the
  shared snapshots, the three `spotify_hosts.json`, `server/testdata/time_status_report.json`,
  `dns_upstream_status.json`, `power_report.json`, `zone_status_report.json`, `launcher_tag_codes.json`,
  `time_request.json`, `installed_apps_report.json` and the three `calendar_*.json`, `music/gradle/` and `music.yml`, and the server's and music's `storytel_token_answers.json`, and `server/testdata/doh_hosts.txt`; `music.yml` also on the shared
  `server/testdata/music_*.json` and `spotify_hosts.json`, the launcher's copy of the bridge AIDL,
  `bridge_contract.json` and `spotify_hosts.json`, `server/testdata/spotify/`, `scripts/fake-sonos.py`,
  `scripts/fake-storytel.py`, `scripts/fake-spotify.py`, `scripts/fake-audio.py` and `scripts/music-splash.py`
  (with the launcher's `splash_vibb_breathe.xml` it draws from), whose `--selftest`s it runs after the build, the server's and the launcher's `storytel_token_answers.json` and `server/testdata/storytel/`;
  `server-ci.yml`'s build job also on the apps' `spotify_hosts.json` and `storytel_token_answers.json` copies and
  the launcher's `time_status_report.json`,
  `dns_upstream_status.json`, `power_report.json`, `zone_status_report.json`, `launcher_tag_codes.json`,
  `time_request.json`, `installed_apps_report.json` and the three `calendar_*.json`, and the launcher's `assets/dns/doh_hosts.txt`;
  `server-ci.yml`'s
  `server-image` job only when `server/Dockerfile`, `server/.dockerignore`, `server/deploy/image-smoke.sh` or
  the workflow changed). The server's Rust (`toolchain: 1.99.0`) is pinned in `server-ci.yml` and
  `server-release.yml` (security review L9) - move it on deliberately, both files at once. Both build the static musl
  binaries natively, each architecture on its own runner (`aarch64-unknown-linux-musl` on `ubuntu-24.04-arm`,
  `x86_64-unknown-linux-musl` on `ubuntu-24.04`; plain `cargo` with `musl-tools`, no `cross`, no QEMU; since 2026-10-25).
  Require the always-running gate jobs `launcher-ci`, `server-ci` and `music-ci` in
  branch protection, not the build jobs.
- `launcher-vX.Y.Z` (or `launcher-vX.Y.Z-rc.N`, a prerelease) on a `main` commit -> signed APK
  release (`kids-launcher-mdm.apk`), versionCode `X*10_000_000 + Y*10_000 + Z*10 + N` (N = the RC number
  1..8, 9 for the release: every build its own code, `scripts/release-version.sh`; X <= 199, Y and Z <= 999).
  In force since 2026-10-23: the first tag under it is `launcher-v0.36.0-rc.4` (360004), and the script refuses a
  lower one; every tag before it was built as `X*1_000_000 + Y*1_000 + Z` with an RC one less (every RC of a version shared a
  code), below every new code, and the launcher and the server still read those tags so
  (`launcher_tag_codes.json`). Only
  stable launcher releases become GitHub's "latest", so
  `releases/latest/download/kids-launcher-mdm.apk` is the provisioning QR's URL. The release build no
  longer reads the `HANDY_FCM_*` repository variables (design 19); they can go once a `launcher-v*` tag on
  design 19's launcher commit or later has been built (an older tag would still check them).
- `music-vX.Y.Z` (or `-rc.N`) on a `main` commit -> `vibb-music.apk` + `.sha256`, signed with the
  launcher's key in the `release` environment (its deployment rule must allow `music-v*` tags too; the same
  `RELEASE_CERT_SHA256` check), versionCode as the launcher's (the same script; the first tag under today's rule
  is `music-v0.5.0-rc.4`, 50004, the old rule built every tag up to `music-v0.5.0-rc.3`, and the server reads the
  music app's version from its code by that cutover), never "latest". Deploy a server with the music
  catalog row (0.21.0+) before the first stable `music-v*` (design 21 QA #11).
- `server-vX.Y.Z` (must equal `server/Cargo.toml`'s version) on a `main` commit -> fmt + tests,
  then the aarch64 and x86_64 tarballs and their `.sha256`, never "latest". Since 0.24.0 the tarballs and `update.sh` are signed
  with minisign before any image is pushed (`server-release-sign`, in the `release` environment, whose deployment
  rule must allow `server-v*` tags too; its `SERVER_MINISIGN_KEY` secret; the public key pinned in
  `server/deploy/*.sh`; server/DEPLOY.md, "Release signing") - no secret, no release. The server's update check and `install.sh`/`update.sh` find it by tag
  (the scripts take the tarball for `uname -m`; the update check reads only the tag).
  Since 0.23.0 (design 22) the same run builds the multi-arch image `ghcr.io/<owner>/handy-server`,
  each architecture natively on its own runner (`ubuntu-24.04`, `ubuntu-24.04-arm`; no QEMU) from that architecture's
  tarball, pushes it by digest,
  smoke-tests it there, then joins the two digests and tags `X.Y.Z`, and `latest` when it is
  the highest `server-v*` tag; the GitHub release is published last. The GHCR package must be made public once by
  the owner (server/DEPLOY.md, "Docker"). `workflow_dispatch` is always a dry run, on a tag ref too.

## Licensing

Everything is GPL-3.0 (or later) - see `LICENSE` and `NOTICE.md`. All new code is GPL-3.0; any
third-party code or asset brought in must be GPL-compatible (the launcher's app-list code stays MIT,
`launcher/LICENSE-MIT-UPSTREAM`; Nunito is OFL 1.1; the app icons from Material Symbols are Apache-2.0,
`scripts/material-symbols.sh`). New GPL code doesn't go into the MIT directories (`ui/list/`, `apps/`).
