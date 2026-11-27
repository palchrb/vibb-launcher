# CI, the build chain and the release pipelines

Moved here from the root `CLAUDE.md` on 2026-11-27; the root keeps the core (tags -> releases, versionCode, "latest",
signing). The scripts themselves: `scripts/README.md`.

## Which job runs when

- `.github/workflows/launcher.yml`, `server-ci.yml` and `music.yml` run on every push/PR, but their build job
  (`launcher-build`, `server-build`, `music-build`) only runs when its own directory or workflow changed
  (all three also on their files in `contracts/` - `launcher.yml` and `server-ci.yml` on the whole folder,
  `music.yml` on `contracts/music/` and `contracts/shipped/bridge-aidl/`; `BuildChainTest` and
  `tests::deploy_scripts` check the filters cover them).
- `launcher.yml` also on `.github/actions/build-tsnet/`, `music/gradle/`, music's manifest and `music.yml`, and
  `scripts/yr-symbols.py` with its vendored `yr-symbols-11.svg`, `scripts/fake-portal.py` (the changes job runs both
  `--selftest`s always) and `scripts/material-symbols.sh`.
- `music.yml` also on `server/testdata/spotify/`, `server/testdata/storytel/`, `scripts/fake-sonos.py`,
  `scripts/fake-storytel.py`, `scripts/fake-spotify.py`, `scripts/fake-audio.py`, `scripts/music-splash.py`
  (with the launcher's `splash_vibb_breathe.xml` it draws from), `scripts/music-icon.py` (with the launcher's
  `ic_launcher_foreground.xml` it reads the rings from) and `scripts/sonos-ws-probe.py` (its capture
  scrubber), whose `--selftest`s it runs after the build.
- `server-ci.yml`'s build job also on the launcher's `ui/home/HomeModel.kt` (the avatar palette the PWA mirrors),
  `app/build.gradle.kts` and `calendar/CalendarStore.kt` (the server's tests read them),
  `scripts/wifi-lookup-fixtures.py` (the changes job runs its `--selftest` always), design 129's scripts, and
  `scripts/mmsc-hosts.py` with its `scripts/mmsc-hosts-sample.xml` (the changes job runs its `--selftest` always, and
  `server/deploy/update-image.sh --selftest`, after a bounded `apt-get install minisign`: the signed image
  statement's checks with a throwaway key, the image update's release pick and version rules, DEPLOY.md "Automatic,
  verified updates", with `deploy/handy-update.service`/`.timer`).
- `server-ci.yml`'s `server-image` job only when `server/Dockerfile`, `server/.dockerignore`,
  `server/deploy/image-smoke.sh`, `server/deploy/distrusted-roots.txt` or the workflow changed.
- Require the always-running gate jobs `launcher-ci`, `server-ci` and `music-ci` in branch protection, not the build
  jobs.

## The server's toolchain

The server's Rust (`toolchain: 1.99.0`) is pinned in `server-ci.yml` and `server-release.yml` (security review L9) -
move it on deliberately, both files at once. Both build the static musl binaries natively, each architecture on its
own runner (`aarch64-unknown-linux-musl` on `ubuntu-24.04-arm`, `x86_64-unknown-linux-musl` on `ubuntu-24.04`; plain
`cargo` with `musl-tools`, no `cross`, no QEMU; since 2026-10-25).

## Gradle build chain

Security review L9: both wrappers pin the distribution (`distributionSha256Sum`), CI validates both
`gradle-wrapper.jar`s (`gradle/actions/wrapper-validation`, pinned by commit) before Gradle runs, and both apps
verify every dependency's SHA-256 against `gradle/verification-metadata.xml` (no signatures; sources/javadoc jars
trusted for the IDE). A new or bumped dependency or plugin fails the build until its checksums are added: run
`./gradlew --write-verification-metadata sha256 --continue help build` (launcher: with `libs/tsnet.aar` present) and
review the diff - new entries are trust on first use, so compare them with the publisher's `.sha1`/`.sha256` where it
has them. CI's aapt2 (`com.android.tools.build:aapt2:<AGP>-<build>:linux`) is never resolved on an aarch64 machine
with the `aapt2FromMavenOverride`: an AGP bump needs its entry by hand (resolve it in a throwaway build, as on
2026-10-11).

## Launcher and music releases

- `launcher-vX.Y.Z` (or `-rc.N`): the versionCode scheme's first tag is `launcher-v0.36.0-rc.4` (360004), and
  `scripts/release-version.sh` refuses a lower one; the launcher and the server read any tag by it
  (`launcher_tag_codes.json`).
- `music-vX.Y.Z` (or `-rc.N`): signed with the launcher's key in the `release` environment (its deployment rule must
  allow `music-v*` tags too; the same `RELEASE_CERT_SHA256` check); the scheme's first tag is `music-v0.5.0-rc.4`,
  50004; the server reads the music app's version from its code by it. Deploy a server with the music catalog row
  (0.21.0+) before the first stable `music-v*` (design 21 QA #11).

## Server releases

`server-vX.Y.Z` (must equal `server/Cargo.toml`'s version) on a `main` commit -> fmt + tests, then the aarch64 and
x86_64 tarballs and their `.sha256`, never "latest". Since 0.24.0 the tarballs and `update.sh` are signed with
minisign, since 0.92.0 also `update-image.sh` and the image statement `handy-server-image.txt` (the index's digest
and the tag; `update-image.sh` checks it before an image runs), all before any image is tagged
(`server-release-sign`, in the `release-server` environment - the server's alone, tag rule `server-v*`; `release`
stays the launcher's and music's -, its `SERVER_MINISIGN_KEY` secret; the public key pinned in `server/deploy/*.sh`;
server/DEPLOY.md, "Release signing") - no secret, no release. The server's update check and `install.sh`/`update.sh`
find it by tag (the scripts take the tarball for `uname -m`; the update check reads only the tag).

Since 0.23.0 (design 22) the same run builds the multi-arch image `ghcr.io/<owner>/handy-server`, each architecture
natively on its own runner (`ubuntu-24.04`, `ubuntu-24.04-arm`; no QEMU) from that architecture's tarball, pushes it
by digest (untagged), smoke-tests it there; a keyless job plans the index, the sign job signs its digest, and the
merge job checks the signed statement and the digest before it tags `X.Y.Z`, and `latest` when it is the highest
`server-v*` tag; the GitHub release is published last. The GHCR package must be made public once by the owner
(server/DEPLOY.md, "Docker"). `workflow_dispatch` is always a dry run, on a tag ref too.
