# handy (repo `palchrb/vibb-launcher`)

A kid's phone setup: an Android launcher that is also the Device Owner agent, and the self-hosted
admin server it talks to. One repo since 2026-10-05; both parts were separate forks before and were
imported with full history (`git subtree`), so old commit hashes in the docs still resolve.

## Layout

- `launcher/` - Android app (Kotlin, Gradle; applicationId `me.vibb.launcher`, debug `me.vibb.launcher.debug`,
  since 2026-10-06 - the Kotlin namespace/class names stay `com.kidslauncher.mdm`). Was the
  `kids-launcher-mdm` fork. Details: `launcher/CLAUDE.md`.
- `server/` - admin server + device API (Rust/Axum/SQLite). Was the `kid-phone-server` fork.
  Details: `server/CLAUDE.md`, deployment `server/DEPLOY.md`.
- `docs/design/`, `docs/review/` - design notes and reviews, one per step. Historical: `S` =
  `kid-phone-server` = `server/`, `L` = `kids-launcher-mdm` = `launcher/`, "branch `handy`" = what is
  now `main` here. `docs/testing/emulator.md` - the emulator test loop. `docs/setup/` - setup
  runbooks (`google-account.md`: the phone's Google account for Play, backup to Google off).
- `scripts/` - `dev-rebuild.sh` (pull, build + install the debug launcher, run the server),
  `push-all.sh` (push the current branch), `smoke-test.sh` (adb/emulator-console smoke test of lock and
  calls, remote-capable; `docs/testing/emulator.md` §5b).
- `PLAN.md` - the plan and status.
- `.github/` - CI for both parts (see below).
- `kids-launcher-mdm/`, `kid-phone-server/` (gitignored, if present) - the old standalone clones.
  Don't edit them; work in `launcher/` and `server/`.

## Build and test

Server (from `server/`): `cargo test`, `cargo fmt --check`, `cargo clippy --all-targets`.

Launcher (from `launcher/`, JDK 17, `local.properties` with `sdk.dir=...`):
`./gradlew assembleDebug assembleRelease testDebugUnitTest`. Without `-PrequireTsnet=true` a missing
`app/libs/tsnet.aar` falls back to a stub. No Firebase config is needed: FCM was removed in design 19 (the
SSE stream is the only nudge), and `assembleRelease` fails if Firebase or Play services comes back on the
release classpath.

Shared between the two and checked by tests on both sides: `server/testdata/phone_vectors.json` and
`launcher/app/src/test/resources/phone_vectors.json` must be identical (server `phone::tests`,
launcher `PhoneNumbersTest`). The policy JSON shape is pinned on both sides
(`policy_json_keys_snapshot` / `PolicyResponseCompatTest`). A change to the API goes into both
directories in the same commit.

## CI and releases

- `.github/workflows/launcher.yml` and `server-ci.yml` run on every push/PR, but their build job
  (`launcher-build`, `server-build`) only runs when its own directory or workflow changed
  (`launcher.yml` also on `.github/actions/build-tsnet/`). Require the always-running gate jobs
  `launcher-ci` and `server-ci` in branch protection, not the build jobs.
- `launcher-vX.Y.Z` (or `launcher-vX.Y.Z-rc.N`, a prerelease) on a `main` commit -> signed APK
  release (`kids-launcher-mdm.apk`), versionCode `X*1_000_000 + Y*1_000 + Z` (RC: minus 1). Only
  stable launcher releases become GitHub's "latest", so
  `releases/latest/download/kids-launcher-mdm.apk` is the provisioning QR's URL. The release build no
  longer reads the `HANDY_FCM_*` repository variables (design 19); they can go once a `launcher-v*` tag on
  design 19's launcher commit or later has been built (an older tag would still check them).
- `server-vX.Y.Z` (must equal `server/Cargo.toml`'s version) on a `main` commit -> fmt + tests,
  then the aarch64 tarball and its `.sha256`, never "latest". The server's update check and `install.sh`/`update.sh` find it by tag.

## Licensing

Everything is GPL-3.0 (or later) - see `LICENSE` and `NOTICE.md`. All new code is GPL-3.0; any
third-party code or asset brought in must be GPL-compatible (the launcher's app-list code stays MIT,
`launcher/LICENSE-MIT-UPSTREAM`; Nunito is OFL 1.1; the app icons from Material Symbols are Apache-2.0,
`scripts/material-symbols.sh`). New GPL code doesn't go into the MIT directories (`ui/list/`, `apps/`).
