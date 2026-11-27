# handy (repo `palchrb/vibb-launcher`)

A kid's phone setup: an Android launcher that is also the Device Owner agent, and the self-hosted
admin server it talks to. Old commit hashes in the docs still resolve.

## Layout

- `launcher/` - Android app **Vibb One** (Kotlin; applicationId `me.vibb.launcher`, debug `me.vibb.launcher.debug`;
  the Kotlin namespace stays `com.kidslauncher.mdm`, the release asset `kids-launcher-mdm.apk`). `launcher/CLAUDE.md`.
- `server/` - admin server + device API (Rust/Axum/SQLite). `server/CLAUDE.md`, deployment `server/DEPLOY.md`.
- `music/` - the Vibb music app (Kotlin, Compose, Media3; `me.vibb.music`, debug `me.vibb.music.debug`). `music/CLAUDE.md`.
- `contracts/` - the files the server, the launcher and the music app read once (design 133; `contracts/README.md`).
- `docs/notes/{server,launcher,music}/` - each part's area notes; its CLAUDE.md keeps the core and an index
  "Where the rest is" - read only the notes your task touches. `docs/notes/ci.md` - CI, the Gradle build chain and
  the release pipelines in detail.
- `docs/design/`, `docs/review/` - design notes and reviews, one per step. In old ones `S` = `server/`, `L` =
  `launcher/`, "branch `handy`" = `main`.
- `docs/testing/emulator.md` - the emulator test loop. `docs/setup/` - setup runbooks (the phone's Google account,
  Android settings an app can't set back).
- `scripts/` - dev, release and fake-service scripts and asset generators; one entry each in `scripts/README.md`.
- `PLAN.md` - the plan, the queue and the fix backlog ("Fix backlog": keep it current as items come in and are done);
  its history and the Done entries already released are in `docs/plan/plan-archive.md`.
- `kids-launcher-mdm/`, `kid-phone-server/` (gitignored, if present) - the old standalone clones. Don't edit them.

## Words in the UI

The user (2026-11-17): "vi bør ikke være så wordy og over-explaining hele tiden". This holds for the PWA, the
launcher and the music app, in nb and en alike:
- A heading or a choice is a word or two ("Roaming": "På", "Av", "Bare Vibb"); no sub-line under a choice that
  restates or explains it.
- An explanation goes behind an (i) alone (the PWA's `<details class="info">`), with no "About ..." label beside it.
- A state line says the state, in a few words; how to change it goes behind the (i).
- Say it once: no footer that repeats the page, no sentence that repeats the label above it.
- Speak up only when something is wrong: a state that is as it should be shows nothing (or a quiet value), and
  what needs the parent - a switch that stops the page's controls working, a step left to do - is one short orange
  line, how to fix it behind the (i) (the user, 2026-11-17, of Android's roaming switch: "si ifra om denne IKKE er
  skrudd på", and "dette er et likt prinsipp som burde være over alt").
- A choice the parent made is never a warning (the user, 2026-11-18: "foreldre pin som ikke er satt - det er galt. men
  å velge å ikke bruke funksjon x eller y - som vi gir valg om, det er ikke galt"): what follows from a choice the PWA
  offers is at most a quiet note at that choice, behind its (i); Attention and orange lines are for what is wrong or undone.
- On the kid's phone fewer words still. A design note's copy table is checked against this before the build.

## Build and test

**Supported Android: 14 to 17** (the user, 2026-11-16: "alt fremover skal være 14-17 kompatibelt"). Both apps are minSdk 34 and every change must work on Android 14, 15, 16 and 17: an API above 34 behind a `Build.VERSION.SDK_INT` gate with a sane path on 14 (lint's NewApi in CI catches a missing gate, never suppress it), and a platform behaviour that differs between them (Device Owner, lock task, roles, notifications and Modes, VPN, telephony, location, WebView) checked against each version's behaviour changes. A design note names the versions it was checked on; an emulator gate that depends on the version runs on the lowest and the highest it touches. The Jelly Star runs Android 16 (Unihertz's update, from September 2026).

**No backwards compatibility on the phones yet** (the user, 2026-11-18: "Husk dette sånn genrelt ift backwards compatibility; det er enda ikke viktig" - only emulators run Vibb so far): no migration path for phone-side data or stored state and no code for older launcher/music versions; a new format drops or resets the old one. The server's database migrations still run (the user's server holds real data), and a release still ships the server first.

Server (from `server/`): `cargo test`, `cargo fmt --check`, `cargo clippy --all-targets`.

Launcher (from `launcher/`, JDK 17, `local.properties` with `sdk.dir=...`):
`./gradlew assembleDebug assembleRelease testDebugUnitTest`. Music app (from `music/`, the same JDK and
`local.properties`): `./gradlew assembleDebug assembleRelease testDebugUnitTest -PwarningsAsErrors=true`.
Without `app/libs/tsnet.aar` the debug build falls back to a stub and a release build fails (security review L13;
`-PrequireTsnet=true` fails any build). No Firebase config is needed (the SSE stream is the only nudge), and
`assembleRelease` fails if Firebase or Play services comes back on the release classpath.

Both apps verify every dependency's SHA-256 (`gradle/verification-metadata.xml`): a new or bumped dependency or
plugin fails the build until its checksums are added and reviewed - how, and the AGP/aapt2 catch:
`docs/notes/ci.md`, "Gradle build chain".

Files both sides read live once in `contracts/` (`core/` server and launcher, `integrations/<key>/`, `music/` what
the music app reads, `shipped/` compiled into a release build); `contracts/README.md` lists each with its design,
writer and readers. Gradle reads the roots as test resources (the AIDL and the DoH asset as main sources), the
server through `contract!` / `contracts::path`. A change to the API and its contract file go in one commit; a new
contract gets a README row (the server's test checks). The policy JSON shape stays pinned by
`policy_json_keys_snapshot` and `PolicyResponseCompatTest`.

## CI and releases

- `launcher.yml`, `server-ci.yml` and `music.yml` run on every push/PR; each build job runs only when its own files
  changed (its directory, workflow, `contracts/` files, `scripts/min-server.sh` for all three, and the files listed in `docs/notes/ci.md` - with the image
  pipeline's detail; a new script a workflow runs, or a file another part's test reads, goes into that filter). Branch protection
  requires the always-running gate jobs `launcher-ci`, `server-ci`, `music-ci`, not the build jobs. The server's Rust
  toolchain is pinned in `server-ci.yml` and `server-release.yml` - move both at once. No QEMU: each architecture
  builds on its own native runner.
- `launcher-vX.Y.Z` (or `-rc.N`, a prerelease) on a `main` commit -> signed APK release (`kids-launcher-mdm.apk`),
  versionCode `X*10_000_000 + Y*10_000 + Z*10 + N` (N = the RC number 1..8, 9 for the release: every build its own
  code, `scripts/release-version.sh`; X <= 199, Y and Z <= 999). Only stable launcher releases become GitHub's
  "latest", so `releases/latest/download/kids-launcher-mdm.apk` is the provisioning QR's URL.
- `music-vX.Y.Z` (or `-rc.N`) on a `main` commit -> `vibb-music.apk` + `.sha256`, signed with the launcher's key,
  versionCode as the launcher's, never "latest".
- Each launcher and music APK names the oldest server it needs (`assets/vibb/min_server`, `scripts/min-server.sh`,
  design 140): tag `server-vX.Y.Z` on the same commit as, or after, every app tag built while Cargo said `X.Y.Z`
  (`server-release.yml` refuses it otherwise; `docs/notes/ci.md`, "Server releases").
- `server-vX.Y.Z` (must equal `server/Cargo.toml`'s version) on a `main` commit -> fmt + tests, the tarballs
  (aarch64, x86_64) and the multi-arch image `ghcr.io/<owner>/handy-server`, never GitHub's "latest" (the image's
  `latest` tag goes to the highest `server-v*`). Signed with minisign in the `release-server` environment - no
  secret, no release (server/DEPLOY.md, "Release signing"). `workflow_dispatch` is always a dry run.

## Licensing

Everything is GPL-3.0 (or later) - see `LICENSE` and `NOTICE.md`. All new code is GPL-3.0; any
third-party code or asset brought in must be GPL-compatible (the launcher's app-list code stays MIT,
`launcher/LICENSE-MIT-UPSTREAM`; Nunito is OFL 1.1; the app icons from Material Symbols are Apache-2.0,
`scripts/material-symbols.sh`). New GPL code doesn't go into the MIT directories (`ui/list/`, `apps/`).
