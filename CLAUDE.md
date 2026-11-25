# handy (repo `palchrb/vibb-launcher`)

A kid's phone setup: an Android launcher that is also the Device Owner agent, and the self-hosted
admin server it talks to. Old commit hashes in the docs still resolve.

## Layout

- `launcher/` - Android app, called **Vibb One** for users (debug "Vibb One (debug)", design 34 P11) - Kotlin,
  Gradle; applicationId `me.vibb.launcher`, debug `me.vibb.launcher.debug`, since 2026-10-06 - the Kotlin
  namespace/class names stay `com.kidslauncher.mdm` and the release asset stays `kids-launcher-mdm.apk`. Details: `launcher/CLAUDE.md`.
- `server/` - admin server + device API (Rust/Axum/SQLite).
  Details: `server/CLAUDE.md`, deployment `server/DEPLOY.md` (Pi + systemd, and the Docker image
  from `server/Dockerfile`).
- `contracts/` - the files the server, the launcher and the music app read once (design 133; `contracts/README.md`,
  "Build and test" below)
- `docs/notes/server/` - the server's area notes, moved out of `server/CLAUDE.md` (which keeps the core and an index)
- `docs/notes/launcher/` - the launcher's area notes, moved out of `launcher/CLAUDE.md` on 2026-11-01 (which keeps the core
  and an index "Where the rest is"; read only the notes your task touches)
- `docs/notes/music/` - the music app's area notes, moved out of `music/CLAUDE.md` on 2026-11-01 (the same pattern)
- `docs/plan/plan-archive.md` - PLAN.md's history, moved out on 2026-11-01: the fork decision, repos, early status,
  phases, the old fix batches and the older "Done" entries; PLAN.md keeps the queue, what was asked for and not settled,
  the Done entries that ship in the next release, and the open questions
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
  Spotify test fixtures; design 26c §9, `docs/testing/emulator.md` §6l), `fake-mmsc.py` (a fake MMS server for the emulator, design 77 step 3b: an
  m-notification-ind as hex for the debug launcher's `MmsDebugReceiver`, m-retrieve-confs with a small JPEG, an m-send-conf
  for every send; standard library only, `--selftest`; `docs/testing/emulator.md` §6v "step 3b"), `fake-audio.py` (a fake podcast host of
  silent episodes with Range; `--vary` serves another length per request, design 30's changed-source check; §6f;
  `--feed N` an RSS feed of N items with their own pictures the server judges `own`, `--hls K` item K as HLS, design
  39's list pictures and order flip; §6q), `pwa-startup-images.py` (the PWA's iPhone start-up PNGs and head.html's
  links from `server/testdata/pwa_startup_screens.json`; `--selftest`; `docs/notes/server/pwa.md`, "Start-up splash"),
  `yr-symbols.py` (the launcher's weather symbols, design 75 §4.1: yr's darkmode SVGs at a pinned commit, checked by
  one SHA-256 over a manifest, converted to `res/drawable/weather_*.xml` with `WeatherSymbols.kt` and the names;
  `--selftest`, no network, also converts the vendored `scripts/yr-symbols-11.svg` to exactly the committed
  `weather_11.xml`; Python's standard library, run with `-I`; run by `launcher.yml`'s always-run changes job),
  `fake-portal.py` (a fake captive portal for design 102's Wi-Fi sign-in window: one server answering the portal's
  name and another site's, with modes - a 302 portal, meta refresh, script, a soft portal, a hotspot that 302s to a
  search page, a Google walled garden, a full-page iframe, a same-site redirector, a form whose POST 302s to another
  site, a reached site's POSTs that 302 and 307 to a third site; standard library, `--selftest`, run by `launcher.yml`'s always-run changes job; `docs/testing/emulator.md`
  §6ap),
  `music-splash.py` (the music app's launch splash drawables: the Vibb mark read from the launcher's Home splash, "vibb
  music" from Nunito's outlines; `--selftest`), `music-icon.py` (the music app's icon and notification small icon, the
  user's drawing `vibb-music-e2`: the Vibb mark's outer three rings read from the launcher's `ic_launcher_foreground.xml`,
  each with its own scale and stroke, and a round-cornered play triangle, its geometry from the drawing's SVG; `--selftest` checks the outer ring inside the
  safe zone), `up-vapid-vectors.py` (design 108b's VAPID vectors `server/testdata/unified_push_vapid.json` and the two public keys of the
  contract's `body_vapid` rows, from a throwaway key derived from a fake phrase; pure Python, public data only),
  `webauthn-rs256-vector.py` (the server's RS256 passkey vector
  `server/testdata/webauthn/rs256.json`, made once with a throwaway key - public data only; design 74), `emoji-list.py`
  (the launcher's `assets/sms/emoji-16.0.txt` from Unicode's `emoji-test.txt` 16.0, its SHA-256 pinned; design 81),
  `fake-homeserver.py` (a fake Matrix homeserver and Web Push sender for design 108's emulator gate: the gateway's
  discovery, Synapse-shaped `event_id_only` notifies, Web-Push-like bytes to `/up/` with TTL/Urgency/Topic, `--burst`;
  every request to the phone listener with the endpoint's host as `Host`; standard library, `--selftest` against a
  loopback recorder, run by `server-ci.yml`'s always-run changes job; `docs/testing/emulator.md` §6ay),
  `wifi-lookup-fixtures.py` (design 125 step 2: the server's `server/testdata/wifi_lookup/server.json` - the golden
  request for Apple's Wi-Fi location service, fake Apple answers with what the server must read from each, the failure
  histories behind the orange line - from its own protobuf encoder; documentation MACs, made-up coordinates, never a
  real capture; standard library, `--write`, `--selftest` run by `server-ci.yml`'s always-run changes job),
  `mmsc-hosts.py` (the server's `server/testdata/mmsc_hosts.txt`, design 83 §3: each operator's MMS server hosts from
  AOSP's `apns-full-conf.xml` at a pinned commit and SHA-256, in two classes - unasked and hints; its `UNASKED` stays
  `"none"` until design 77's step 0; `--selftest`, no network, converts `scripts/mmsc-hosts-sample.xml` and checks the
  committed list's lines against the SHA-256 the script holds; run by `server-ci.yml`'s always-run changes job),
  `grapheneos-boot-keys.py` (design 129 §9: the server's `server/src/attestation/grapheneos_boot_keys.json` from
  GrapheneOS's signed `attestation.json`, its OpenSSH signature checked with `ssh-keygen -Y verify` against the signer
  the script pins, an older `timestamp` refused; `--selftest`, no network, a vendored sample signed by a throwaway key
  in `scripts/grapheneos-boot-keys-sample/`; run by `server-ci.yml`'s always-run changes job), `device-key-vectors.py`
  (design 129 §7.1: the crypto rows of `contracts/core/device_key.json` from a throwaway P-256 key derived from a fake
  phrase - public data only, pure Python; `--check` run by `server-ci.yml`'s always-run changes job),
  `emulator-attestation-vectors.py` (design 129b §7: the `emulator_keys` profile's chains and rows in
  `server/testdata/attestation/emulator/`, from throwaway keys and made-up values in the emulator gate's shape, never a
  capture; pure Python; `--check` run by `server-ci.yml`'s always-run changes job).
- `PLAN.md` - the plan and status, and the fix backlog ("Fix backlog": keep it current as items come in and are done).
- `.github/` - CI for both parts (see below).
- `kids-launcher-mdm/`, `kid-phone-server/` (gitignored, if present) - the old standalone clones.
  Don't edit them; work in `launcher/` and `server/`.

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
Without `app/libs/tsnet.aar` the debug build falls
back to a stub and a release build fails (security review L13; `-PrequireTsnet=true` fails any build). No Firebase config is needed (the
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

Files both sides read live once in `contracts/` (`core/` server and launcher, `integrations/<key>/`, `music/` what
the music app reads, `shipped/` compiled into a release build); `contracts/README.md` lists each with its design,
writer and readers. Gradle reads the roots as test resources (the AIDL and the DoH asset as main sources), the
server through `contract!` / `contracts::path`. A change to the API and its contract file go in one commit; a new
contract gets a README row (the server's test checks). The policy JSON shape stays pinned by
`policy_json_keys_snapshot` and `PolicyResponseCompatTest`.

## CI and releases

- `.github/workflows/launcher.yml`, `server-ci.yml` and `music.yml` run on every push/PR, but their build job
  (`launcher-build`, `server-build`, `music-build`) only runs when its own directory or workflow changed
  (all three also on their files in `contracts/` - `launcher.yml` and `server-ci.yml` on the whole folder,
  `music.yml` on `contracts/music/` and `contracts/shipped/bridge-aidl/`; `BuildChainTest` and
  `tests::deploy_scripts` check the filters cover them; `launcher.yml` also on `.github/actions/build-tsnet/`,
  `music/gradle/`, music's manifest and `music.yml`, and `scripts/yr-symbols.py` with its vendored
  `yr-symbols-11.svg`, `scripts/fake-portal.py` (the changes job runs both `--selftest`s always) and
  `scripts/material-symbols.sh`; `music.yml`
  also on `server/testdata/spotify/`, `server/testdata/storytel/`, `scripts/fake-sonos.py`,
  `scripts/fake-storytel.py`, `scripts/fake-spotify.py`, `scripts/fake-audio.py`, `scripts/music-splash.py`
  (with the launcher's `splash_vibb_breathe.xml` it draws from), `scripts/music-icon.py` (with the launcher's
  `ic_launcher_foreground.xml` it reads the rings from) and `scripts/sonos-ws-probe.py` (its capture
  scrubber), whose `--selftest`s it runs after the build; `server-ci.yml`'s build job also on the launcher's
  `ui/home/HomeModel.kt` (the avatar palette the PWA mirrors), `app/build.gradle.kts` and `calendar/CalendarStore.kt`
  (the server's tests read them), `scripts/wifi-lookup-fixtures.py` (the changes job
  runs its `--selftest` always), design 129's scripts, and
  `scripts/mmsc-hosts.py` with its `scripts/mmsc-hosts-sample.xml` (the changes job runs its `--selftest` always, and `server/deploy/update-image.sh --selftest`, after a bounded `apt-get install minisign`: the signed image statement's checks with a throwaway key, the image update's release pick and version rules, DEPLOY.md "Automatic, verified updates", with `deploy/handy-update.service`/`.timer`);
  `server-ci.yml`'s
  `server-image` job only when `server/Dockerfile`, `server/.dockerignore`, `server/deploy/image-smoke.sh`, `server/deploy/distrusted-roots.txt` or
  the workflow changed). The server's Rust (`toolchain: 1.99.0`) is pinned in `server-ci.yml` and
  `server-release.yml` (security review L9) - move it on deliberately, both files at once. Both build the static musl
  binaries natively, each architecture on its own runner (`aarch64-unknown-linux-musl` on `ubuntu-24.04-arm`,
  `x86_64-unknown-linux-musl` on `ubuntu-24.04`; plain `cargo` with `musl-tools`, no `cross`, no QEMU; since 2026-10-25).
  Require the always-running gate jobs `launcher-ci`, `server-ci` and `music-ci` in
  branch protection, not the build jobs.
- `launcher-vX.Y.Z` (or `launcher-vX.Y.Z-rc.N`, a prerelease) on a `main` commit -> signed APK
  release (`kids-launcher-mdm.apk`), versionCode `X*10_000_000 + Y*10_000 + Z*10 + N` (N = the RC number
  1..8, 9 for the release: every build its own code, `scripts/release-version.sh`; X <= 199, Y and Z <= 999).
  The first tag under it is `launcher-v0.36.0-rc.4` (360004), and the script refuses a lower one; the launcher and
  the server read any tag by it (`launcher_tag_codes.json`). Only
  stable launcher releases become GitHub's "latest", so
  `releases/latest/download/kids-launcher-mdm.apk` is the provisioning QR's URL.
- `music-vX.Y.Z` (or `-rc.N`) on a `main` commit -> `vibb-music.apk` + `.sha256`, signed with the
  launcher's key in the `release` environment (its deployment rule must allow `music-v*` tags too; the same
  `RELEASE_CERT_SHA256` check), versionCode as the launcher's (the same script; the first tag under it is
  `music-v0.5.0-rc.4`, 50004; the server reads the music app's version from its code by it), never "latest". Deploy a server with the music
  catalog row (0.21.0+) before the first stable `music-v*` (design 21 QA #11).
- `server-vX.Y.Z` (must equal `server/Cargo.toml`'s version) on a `main` commit -> fmt + tests,
  then the aarch64 and x86_64 tarballs and their `.sha256`, never "latest". Since 0.24.0 the tarballs and `update.sh` are signed
  with minisign, since 0.92.0 also `update-image.sh` and the image statement `handy-server-image.txt` (the index's
  digest and the tag; `update-image.sh` checks it before an image runs), all before any image is tagged
  (`server-release-sign`, in the `release-server` environment - the server's alone, tag rule `server-v*`; `release`
  stays the launcher's and music's -, its `SERVER_MINISIGN_KEY` secret; the public key pinned in
  `server/deploy/*.sh`; server/DEPLOY.md, "Release signing") - no secret, no release. The server's update check and `install.sh`/`update.sh` find it by tag
  (the scripts take the tarball for `uname -m`; the update check reads only the tag).
  Since 0.23.0 (design 22) the same run builds the multi-arch image `ghcr.io/<owner>/handy-server`,
  each architecture natively on its own runner (`ubuntu-24.04`, `ubuntu-24.04-arm`; no QEMU) from that architecture's
  tarball, pushes it by digest (untagged),
  smoke-tests it there; a keyless job plans the index, the sign job signs its digest, and the merge job checks the
  signed statement and the digest before it tags `X.Y.Z`, and `latest` when it is
  the highest `server-v*` tag; the GitHub release is published last. The GHCR package must be made public once by
  the owner (server/DEPLOY.md, "Docker"). `workflow_dispatch` is always a dry run, on a tag ref too.

## Licensing

Everything is GPL-3.0 (or later) - see `LICENSE` and `NOTICE.md`. All new code is GPL-3.0; any
third-party code or asset brought in must be GPL-compatible (the launcher's app-list code stays MIT,
`launcher/LICENSE-MIT-UPSTREAM`; Nunito is OFL 1.1; the app icons from Material Symbols are Apache-2.0,
`scripts/material-symbols.sh`). New GPL code doesn't go into the MIT directories (`ui/list/`, `apps/`).
