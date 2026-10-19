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
- `music/` - the Vibb music app (Kotlin, Compose, Media3; applicationId `me.vibb.music`, debug
  `me.vibb.music.debug`), designs 20/21/21b, Sonos 26a, Storytel 26b, Spotify 26c. Details: `music/CLAUDE.md`.
- `docs/design/`, `docs/review/` - design notes and reviews, one per step. Historical: `S` =
  `kid-phone-server` = `server/`, `L` = `kids-launcher-mdm` = `launcher/`, "branch `handy`" = what is
  now `main` here. `docs/testing/emulator.md` - the emulator test loop. `docs/setup/` - setup
  runbooks (`google-account.md`: the phone's Google account for Play, backup to Google off).
- `scripts/` - `dev-rebuild.sh` (pull, build + install the debug launcher, run the server),
  `push-all.sh` (push the current branch), `smoke-test.sh` (adb/emulator-console smoke test of lock and
  calls, remote-capable; `docs/testing/emulator.md` §5b), `fake-sonos.py` (fake Sonos speakers for the
  music app on the emulator, answering from the music app's Sonos test fixtures; §6h), `fake-storytel.py` (a fake
  Storytel - the login, the asset call's 302, a CDN of silent books - answering from the music app's Storytel test
  fixtures; §6i), `fake-spotify.py` (a fake Spotify - the accounts service with PKCE, the Web API the server and the
  phone use, a stub Web Playback SDK playing a tone - answering from `server/testdata/spotify/` and the music app's
  Spotify test fixtures; design 26c §9, `docs/testing/emulator.md` §6l), `fake-audio.py` (a fake podcast host of
  silent episodes with Range; `--vary` serves another length per request, design 30's changed-source check; §6f).
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
The launcher's `POST /api/devices/power` body (design 38 §2) is `server/testdata/power_report.json`, byte-identical in
`launcher/app/src/test/resources/`: the launcher's `PowerEventsTest` encodes exactly it, the server's
`power::a_launcher_report_raises_both` posts it (its instants moved to the last hour), and both compare the copies.

## CI and releases

- `.github/workflows/launcher.yml`, `server-ci.yml` and `music.yml` run on every push/PR, but their build job
  (`launcher-build`, `server-build`, `music-build`) only runs when its own directory or workflow changed
  (`launcher.yml` also on `.github/actions/build-tsnet/` and the music files its tests read: the bridge, the
  shared snapshots, the three `spotify_hosts.json`, `server/testdata/time_status_report.json`,
  `dns_upstream_status.json` and `power_report.json`, `music/gradle/` and
  `music.yml`; `music.yml` also on the shared
  `server/testdata/music_*.json` and `spotify_hosts.json`, the launcher's copy of the bridge AIDL,
  `bridge_contract.json` and `spotify_hosts.json`, `server/testdata/spotify/`, `scripts/fake-sonos.py`,
  `scripts/fake-storytel.py`, `scripts/fake-spotify.py` and `scripts/fake-audio.py`, whose `--selftest`s it runs
  after the build; `server-ci.yml`'s build job also on the apps' `spotify_hosts.json` copies and the launcher's
  `time_status_report.json`, `dns_upstream_status.json` and `power_report.json`;
  `server-ci.yml`'s
  `server-image` job only when `server/Dockerfile`, `server/.dockerignore`, `server/deploy/image-smoke.sh` or
  the workflow changed). The server's Rust (`toolchain: 1.99.0`) and `cross` (`cross@0.2.5`) are pinned in
  `server-ci.yml` and `server-release.yml` (security review L9) - move them on deliberately, both files at once.
  Require the always-running gate jobs `launcher-ci`, `server-ci` and `music-ci` in
  branch protection, not the build jobs.
- `launcher-vX.Y.Z` (or `launcher-vX.Y.Z-rc.N`, a prerelease) on a `main` commit -> signed APK
  release (`kids-launcher-mdm.apk`), versionCode `X*1_000_000 + Y*1_000 + Z` (RC: minus 1). Only
  stable launcher releases become GitHub's "latest", so
  `releases/latest/download/kids-launcher-mdm.apk` is the provisioning QR's URL. The release build no
  longer reads the `HANDY_FCM_*` repository variables (design 19); they can go once a `launcher-v*` tag on
  design 19's launcher commit or later has been built (an older tag would still check them).
- `music-vX.Y.Z` (or `-rc.N`) on a `main` commit -> `vibb-music.apk` + `.sha256`, signed with the
  launcher's key in the `release` environment (its deployment rule must allow `music-v*` tags too; the same
  `RELEASE_CERT_SHA256` check), versionCode as the launcher's, never "latest". Deploy a server with the music
  catalog row (0.21.0+) before the first stable `music-v*` (design 21 QA #11).
- `server-vX.Y.Z` (must equal `server/Cargo.toml`'s version) on a `main` commit -> fmt + tests,
  then the aarch64 tarball and its `.sha256`, never "latest". Since 0.24.0 the tarball and `update.sh` are signed
  with minisign before any image is pushed (`server-release-sign`, in the `release` environment, whose deployment
  rule must allow `server-v*` tags too; its `SERVER_MINISIGN_KEY` secret; the public key pinned in
  `server/deploy/*.sh`; server/DEPLOY.md, "Release signing") - no secret, no release. The server's update check and `install.sh`/`update.sh` find it by tag.
  Since 0.23.0 (design 22) the same run builds the multi-arch image `ghcr.io/<owner>/handy-server`,
  pushes it by digest, smoke-tests it (amd64, arm64 under QEMU), then tags `X.Y.Z`, and `latest` when it is
  the highest `server-v*` tag; the GitHub release is published last. The GHCR package must be made public once by
  the owner (server/DEPLOY.md, "Docker"). `workflow_dispatch` is always a dry run, on a tag ref too.

## Licensing

Everything is GPL-3.0 (or later) - see `LICENSE` and `NOTICE.md`. All new code is GPL-3.0; any
third-party code or asset brought in must be GPL-compatible (the launcher's app-list code stays MIT,
`launcher/LICENSE-MIT-UPSTREAM`; Nunito is OFL 1.1; the app icons from Material Symbols are Apache-2.0,
`scripts/material-symbols.sh`). New GPL code doesn't go into the MIT directories (`ui/list/`, `apps/`).
