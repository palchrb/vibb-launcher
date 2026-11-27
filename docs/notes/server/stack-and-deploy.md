# Server notes: stack and deploy

Moved from server/CLAUDE.md on 2026-11-27 (the tech stack in full, releases and config); the core file's 'Where the rest is' lists every note.

## Tech stack

Deliberately mirrors `board-game-tracker` (another self-hosted Pi app by the same developer) since it already solved the boring-but-essential infrastructure for this exact hardware target:

- **Language:** Rust, **Web framework:** Axum, **Database:** SQLite via `sqlx` (WAL mode)
- **Templating:** Askama (compile-time-checked server-rendered HTML, no JS build step, no frontend framework) - templates are compiled into the binary, so editing one requires a rebuild, not just a file save. A plain `cargo run` dev server (no `cargo watch`) needs to be manually killed and restarted after any template/static change.
- **Sessions/auth:** `tower-sessions` + `tower-sessions-sqlx-store`, `argon2` for the admin password, mandatory TOTP 2FA or a passkey (design 74: an own WebAuthn verifier on `ring`, `src/webauthn.rs`) (a session ends `SESSION_INACTIVITY_DAYS`, default 30, unused, and `SESSION_MAX_DAYS`, default 90, after its sign-in - security review L6, `docs/notes/server/connectivity.md`)
- **UI:** the parent PWA is **Vibb Phone** (design 31, `docs/design/31-pwa-redesign.md`; the 2FA issuer stays "Kids Device MDM"), mobile-first bottom tab bar (Devices / Apps / Locate / Music / Settings, `templates/partials/tab_bar.html`: each page sets `{% let tab = "..." %}` before including it - Device › Apps alone from a `device_pages::Tab` field, Apps when opened from the Apps tab (`?from=`, `docs/notes/server/pwa.md`) - and the server marks that tab `aria-current="page"`; an app shell (design 43): the document never scrolls, `main.container` does and the bar floats over its bottom, absolute in the body (2026-10-22) - no fixed box - and `static/scroll-restore.js` keeps the place in `main`, on Back, reload and the band's back link too (`docs/notes/server/pwa.md`, "The app shell"); since design 84 every page in the shell opens on one night band written by `templates/partials/band.html` alone (the page's only `<h1>`: a tab's with the mark and the bell last, a page under one with a back chevron whose target is only spoken, a phone's ten pages with the phone's small picture, the four editors' with Save and staying in place) and holds its content in a `.sheet` (`docs/notes/server/pwa.md`, "The night band on every page"); Time Rules, Web Filter (`/dns`) and Blocked Activity are rows of the Settings hub, `handlers::settings_hub`, whose values come only from the database - never the network), iOS-style grouped-list CSS (`static/style.css`: tokens for light and dark, rows, the switch as a checkbox plus a sibling `<span class="knob">`, ⓘ, badges, Attention levels, flash, confirm; browser floor Chrome/WebView 80 and iOS Safari 14, no `:has()` or nesting), installable PWA (`static/manifest.webmanifest`, minimal install-only service worker at `templates/sw.js` - no offline page caching, deliberately, since this app handles session cookies and device bearer tokens), light/dark theming via `prefers-color-scheme` CSS variables (no manual toggle)
- **Words (i18n):** `src/i18n.rs` - `Lang { En, Nb }` per request (an axum extractor, `Lang::of_request`): `?lang=en|nb` for
  that request only (never stored; any other value ignored), else the account's `admin_users.lang` (0104, from the
  `CurrentAdmin` in the extensions - no DB read), else `PWA_DEFAULT_LANG` (`en`|`nb`, `ForkConfig::pwa_default_lang`;
  unset = skipped), else `Accept-Language` (`nb`/`nn`/`no` → Norwegian), else English;
  `require_full_auth` stores the first signed-in request's language (the env default when set, else `Accept-Language`) once while the column is NULL, and
  Settings › Account › Language changes it (`POST /account/language`). `t(lang, key)` (a missing key reads as the key,
  logged), `t_params` (one pass), `t_count` (`.one`/`.other`, `Lang::plural_form`), `i18n::error(lang, status, key)`
  (a plain-text answer), over `src/i18n/en.json` and `nb.json` compiled in; every template struct has `lang: Lang`
  and `impl Words` (`i18n::words!`; `self.t`/`self.tp`/`self.tc`), every page `<html lang="{{ lang.code() }}">`
  (since slice 3e; the sign-in pages too, by `PWA_DEFAULT_LANG`/`Accept-Language` before an account). `i18n::tests` keeps both catalogs' keys and parameters equal, the plural pairs, no markup, and every used key
  present. Since slice 2 the times (`display_time::Zone` carries the `Lang`, `time.*`), the parent's events
  (`events.<kind>.*`, worded when read; pushes in the subscriber's language) and the security log (`security.event.<id>`
  labels, `security::Detail` words in `security_events.params`, 0107) are in the reader's language too. **No new
  user-facing word is hardcoded** (the user, 2026-10-26): new pages go through it (since slice 3e of round S-i18n,
  design 70, every template is worded and scanned, `MOVED`; since slice 4 the words Rust builds too - helpers take
  `lang` or return keys, `i18n::number` for decimals, `i18n::error` for every answer (`no_status_answers_with_literal_words`),
  and `tests::i18n_pages::nb_pages_have_no_english` reads every page of a seeded family in Norwegian;
  `docs/notes/server/pwa.md`, "Words").
- **Hosting target:** Raspberry Pi Zero 2 W (or similar aarch64 board), or any x86_64 Linux box: every `server-v*` tag builds the static `aarch64-unknown-linux-musl` and `x86_64-unknown-linux-musl` binaries, each natively on a runner of its own architecture (root `.github/workflows/server-release.yml`, plain `cargo build --target`, `musl-tools`; no `cross` since 2026-10-25), and publishes a tarball for each - `deploy/install.sh` downloads the newest `server-v*` release's tarball for `uname -m` and sets up a systemd service

## Releases and config

This is `server/` in the `palchrb/vibb-launcher` monorepo. The repo publishes
both components: the launcher from `launcher-vX.Y.Z` tags (stable ones are GitHub's "latest" release) and this server
from `server-vX.Y.Z` tags (`.github/workflows/server-release.yml` at the root: tag must be on master/main and match
`Cargo.toml`; published with makeLatest false). So the release check Software Updates shows (`handlers::system_update`) and
`install.sh`/`update.sh` look up the newest stable `server-vX.Y.Z` in the releases *list*, never `releases/latest`,
and the tracked-app sync skips `server-v*` releases. Everything that names a GitHub repo or a launcher build is configuration,
not code: `src/config.rs` (`ForkConfig`, `AppState.config`) reads `SERVER_RELEASE_REPO`, `LAUNCHER_ADMIN_COMPONENT`,
`LAUNCHER_APK_URL`, `LAUNCHER_SIGNATURE_CHECKSUM` and `PHONE_SERVER_URL` (0.54.0: the setup QR's server address
while Phone Setup has none saved - the saved one wins, `provisioning::server_url`) and `PHONE_RECOVERY_URL` (design 76's fix round: the tailnet recovery address, plain http to a Tailscale IP only, `server_tls::recovery_url`) once at startup, with defaults pointing at the monorepo
(a set value is read as written); migration 0031 repoints a catalog row watching `palchrb/kids-launcher-mdm`. The
checksum has no default: without it the provisioning page shows a banner and no QR. An
invalid value is logged and treated as unset. The root-run deploy scripts never read `.env` (it's writable by the
service user): `install.sh`/`update.sh` take `KPS_REPO=owner/repo` (validated). `update.sh` downloads with `curl -f` and unpacks/checks the release before stopping the
service, restarts it from an `ERR` trap if anything fails after the stop, copies the DB and the old binary to
`/var/backups/kid-phone-server/` before every swap, and prunes old backups only once the new version is running
(rollback steps in DEPLOY.md). Production launchers are the release build, package `me.vibb.launcher` (renamed from
`com.kidslauncher.mdm` on 2026-10-06: migration 0037 renames the launcher row's package; the Kotlin class names keep
`com.kidslauncher.mdm`)
(no `.debug` suffix), from normal GitHub releases with the stable asset name `kids-launcher-mdm.apk` - so the
launcher's catalog row has `include_prereleases` **off** (release candidates are prereleases). The server creates that
row at startup when no row is the launcher, watching `LAUNCHER_APK_URL`'s repo (`tracked_apps::ensure_launcher_row`,
the "global catalog" bullet below).

**Build from the monorepo** (design 133): the binary compiles in `../contracts/shipped/launcher-assets/dns/doh_hosts.txt`
and the tests read `../contracts/`, so the server builds only from a whole checkout of the repo, never from a copy of
`server/` alone; a Dockerfile that compiles must use the repo root as its context (`contracts/README.md`).
