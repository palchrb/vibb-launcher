# Kid Phone Server — Project Context for Claude Code

The parent's admin server and the phones' API for the Vibb setup - the sole backend of the launcher in
`../launcher/` (`me.vibb.launcher`, Kotlin namespace `com.kidslauncher.mdm`, networking in its `app/.../server/`),
which speaks only this server's own routes under `/api/devices/*`; no other MDM server or protocol. The root
`CLAUDE.md` has the layout and the release rules; deployment is `DEPLOY.md`. This file is the core every server task
needs; the area notes are listed in "Where the rest is" below - read only the ones your task touches.

## About the developer

No prior programming experience - build features directly rather than explaining Rust concepts, but loop them in on real architecture/schema/UX decisions and explain the *why* in plain language.

## Stack, build and run

- Rust, Axum, SQLite via `sqlx` (WAL mode); Askama templates (server-rendered, no JS build step, no frontend
  framework); `tower-sessions` for the parent's sessions (`argon2`, mandatory TOTP or a passkey); the parent PWA
  **Vibb Phone** (design 31) is plain HTML + `static/style.css` + small scripts. Detail: `docs/notes/server/stack-and-deploy.md`.
- **Build from the monorepo** (design 133): the binary compiles in `../contracts/shipped/launcher-assets/dns/doh_hosts.txt`
  and the tests read `../contracts/`, so the server builds only from a whole checkout of the repo, never from a copy of
  `server/` alone; a Dockerfile that compiles must use the repo root as its context (`contracts/README.md`).
- **Templates are compiled into the binary, and the dev server is a plain `cargo run`** (no `cargo watch`): after a
  template or static-file change kill the running `cargo`/`kid_phone_server` and start it again, or you test stale
  HTML/CSS.
- **`/static/*` is cached hard**: `partials/head.html` links `style.css` with `?v=N` - bump `N` when the
  stylesheet changes. `tests::shell::style_css_has_the_shape_browsers_read` catches a lost `}`.
- **axum's `Form` can't read repeated same-named fields** (checkbox groups) into a `Vec`: take `Bytes` and parse
  with `form_urlencoded`, as `handlers::devices::update_policy` does.
- **Never remove `rustls::crypto::ring::default_provider().install_default()` from `main`**: without it (or without a
  root store) every outbound TLS call fails silently (`docs/notes/server/gotchas.md`).

## Tests

`cargo test` runs in-process tests in `src/tests/` (and in CI): `build_admin_router` (split out of `main()` for this; `TestApp::device_router()` for the phone listener) is driven directly with `tower::ServiceExt::oneshot`, against a fresh migrated SQLite file in a temp dir per test - no network, no background tasks. `TestApp` (`src/tests/mod.rs`) has helpers to create and enroll a device and send JSON requests with or without a bearer token; handlers extracting `ConnectInfo` get a fixed loopback address. A test that documents a known bug is marked `#[ignore = "..."]` until the fix lands - run `cargo test -- --ignored` to see them fail. Admin handlers can be called directly with `TestApp::state` (skipping the session/2FA middleware), or through the real middleware: `TestApp::admin_cookie()` seeds an onboarded admin with a fixed TOTP secret, logs in via `/login` + `/auth/verify-2fa` (with a TOTP step not used yet - a code works once) and returns the session cookie for `request_form` (urlencoded POSTs) and `get_page`; `admin_cookie_as(username, name)` does the same for another ready account of the family (design 62). Requests come from `LOOPBACK_PEER`; `send_via(router, peer, request)` sends from any peer to the admin router or `device_router()` (the phone listener), `with_env(&[...])` builds the app with other listener settings. `policy_json_keys_snapshot` is the contract with the launcher's `PolicyResponse` DTO - update both sides together.

## Rules every server change keeps

- **The server never acts on its host** (the user, 2026-11-04: "PWA skal ikke ha rettigheter til å gjøre noenting som
  helst på host", "jeg ønsker å gjøre ting på host selv"). No root helper, no request file, no update/restart/apt/
  Tailscale/reboot/format button, no OS or app-update schedule, no read of the host's state (apt, `tailscale version`,
  `/var/run/reboot-required`, `/sys/block`, mounts): Settings › Software Updates shows versions only and how the owner
  updates (`handlers::updates`). (Since 0.67.0, C1; the old root helpers: `docs/notes/server/gotchas.md`, "History".) Don't add a way back: a new thing the host should do is the owner's,
  documented in DEPLOY.md.
- **No POST may make the page jump to the top - always return to the edited card** (the user, 2026-10-06: "det må
  fikses overalt"). Every form that stays on its page posts and redirects to its own group's `#fragment` (an element the
  answer still has); `static/scroll-restore.js`
  (in `partials/head.html`, which every page template includes) keeps the place, and auto-saving controls are
  `data-autosave` (`static/autosave.js`). Redirect tests use `TestApp::assert_lands`;
  `pages_restore_scroll_after_auto_save` checks every template. In full: `docs/notes/server/pwa.md`, "Fix round 2026-10-06".
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
- **Fail closed** (`docs/notes/server/fail-closed.md`): what a phone sends is stored within limits and never reaches
  the PWA as HTML or script; `GET /api/devices/policy` never answers with a default policy (an error is an empty 500,
  the launcher keeps its cache); allowlist writes never guess (one transaction, an error writes nothing); a catalog
  app is pinned to its package and signing key and a release never allows anything by itself; a release asking for
  more permissions waits for the parent; an F-Droid index is used only under its pinned key; root scripts take only
  signed release files and never read `data/`; every copy of the database goes through `backups::snapshot`.
- **Device-facing API** (`docs/notes/server/device-api.md`): plain JSON, no envelope; every route is declared in
  `src/device_routes.rs`, never in `main.rs`. A change to the API and its contract file in `contracts/` go in one
  commit with the launcher's side; `policy_json_keys_snapshot` is the contract with the launcher's `PolicyResponse`
  DTO - update both sides together. A key the launcher adds to a report's `TimeState`/`CallState` is dropped until
  `device_api::TIME_STATE_FIELDS`/`CALL_STATE_FIELDS` list it.

## Migrations

**Migration numbers 0058-0060 are skipped on `main`.** Never fill
them: sqlx would apply them out of order on a 0.24 database, and `startup::pre_migration_backup` (`MAX(version) <
latest`) would take no backup first. A new migration takes the next number after the highest one on `main`. The same
hazard holds for any migration that reaches a server after a higher one has run: never deploy a build that carries a
higher one without the lower.
**A migration file is frozen from its first commit on a pushed branch, comments included**: sqlx stores a checksum of
the whole file and refuses to start (`VersionMismatch`) when an applied migration's text changed. Later notes go into
`docs/notes/server/data-model.md` or the design doc; a dev database that ran an earlier text needs a fresh `data/` (qa-26a-merge27-code L2).
Every migration follows the one before it: sqlx applies them in number order, and `migrations_from_73_in_order` checks
every file from 0073 on. The pairs a server must get in that order (the hazard above): 0070 before 0071 before 0072,
0073 before 0074, 0078 before 0079, 0122 before 0123, 0181 before 0182. What a file adds is its name and its header comment; the tables in
`docs/notes/server/data-model.md` name the migration of the columns they list. The next free number is 0189 (the
recent ones: data-model.md, "Recent migrations").

## Where the rest is

Area notes, moved verbatim from this file on 2026-10-19 and 2026-11-27; read only the ones your task touches. A code
comment that cites a heading of "server/CLAUDE.md" finds it in one of these.

- `docs/notes/server/stack-and-deploy.md` - Tech stack in full (the PWA shell, `style.css`'s tokens and browser floor, the service worker, the hosting target); Releases and config (`ForkConfig`'s env, the release lookup, `install.sh`/`update.sh`, the launcher's catalog row)
- `docs/notes/server/fail-closed.md` - Fail closed (the status report's limits, the catalog's package and key pins, update holds, the F-Droid repo key, the policy never a default, `policy_state`, allowlist writes, `create_device`); phone data never as HTML, root takes only signed files, root never reads `data/`, no session leaves the database (`backups::snapshot`), every backup checked
- `docs/notes/server/gotchas.md` - Non-obvious gotchas: axum's `Form` and repeated fields, `/static` caching and `style.css`'s shape test, the dev server, rustls's provider and root store, the forced keyguard bit, QR provisioning on a GMS phone; history of removed code
- `docs/notes/server/data-model.md` - Data model: every table and its migrations; recent migrations
- `docs/notes/server/device-api.md` - Device-facing API: every route under `/api/devices/*`, its shapes and answers
- `docs/notes/server/architecture.md` - Architecture
- `docs/notes/server/calls-and-contacts.md` - Calls & SMS; Emulator fixes; Calls & Contacts; The emergency menu (design 73); SMS in Vibb One, steps 1 and 4 (design 77); The kid's contact request (design 77 §14); The MMSC list and the parent's allow (design 83); The Matrix ID belongs to the contact (0161)
- `docs/notes/server/hardening-and-pins.md` - Hardening; The Child PIN; Parent PIN; Windows that lock again, unseen boots; The lock's glance page; Android's lock screen (design 59; phase D, design 132: the only lock)
- `docs/notes/server/time-rules.md` - Time rules, screen time, location policy; Time Rules and Device › Time Rules (a switch per rule, 0115); Bedtime (design 66); A rule turned on now (design 93, 0142)
- `docs/notes/server/launcher-ui.md` - Launcher UI, contact photos, airplane mode; Wallpapers; App names and icons; The launcher is "Vibb One"; Lock screen weather (design 75: Lock Screen & PINs › Weather, the routes, `weather.rs`, `weather_cache.rs`, MET's rules)
- `docs/notes/server/play-and-updates.md` - SSE only; Play; Kiosk escapes; Catalog downloads; App downloads: Wi-Fi only, resumable; Install Now (design 37); F-Droid source; Update holds and the per-app wait (design 90: `catalog_holds.rs`, the permissions in `apk.rs`, the held release, Approve/Keep/Update Now); A release that needs a newer server (design 140: `assets/vibb/min_server`, the `server` hold, its release at start)
- `docs/notes/server/dns.md` - How phones get their filter; A custom DNS server for the Web Filter
- `docs/notes/server/music.md` - Vibb music library; Vibb music: the server sweep; Vibb music: Storytel on the server; Vibb music: Spotify on the server; Vibb music: episode art
- `docs/notes/server/notifications.md` - Phone off and on (design 38); Notifications to the parent, step 1: events and the inbox; Notifications to the parent, step 2: Web Push; Zone events (design 64); Requests for more time (design r13); Push wording (2026-10-25); App update notices (2026-10-25); Adding the app to the Home Screen again; The first opening offers notifications (2026-11-01); Push never lost silently (2026-11-04: the sync route and re-subscribe on open, the kept browser, every deletion traced, gone rows warned)
- `docs/notes/server/connectivity.md` - Connectivity and exposure; Parent accounts; Passkeys (design 74: `webauthn.rs`, `passkeys.rs`, the challenge store, `PASSKEY_RP_ID`/`PASSKEY_ORIGINS`); Passkey only (step 2: the switch, the switch-off page, the sign-in page without passwords); The app lock (step 3: `lock.rs`, `Why::Locked` and `lock::EXEMPT`, `/lock`, `static/app-lock.js`); Leave Tailnet; The phones' certificate check (design 76: `server_tls.rs`, `device_server_tls`, Advanced › Certificate check); The saved Wi-Fi network (0162: sealed, `provisioning::qr_password`)
- `docs/notes/server/pwa.md` - Fix round 2026-10-06; Fix round 1; The PWA's times, connection state and Attention; About This Phone and Advanced; Lock Screen & PINs; Home Screen and Web Filter; Device › Apps; Device › Music; The phone's summary; The Apps tab; The Music tab; The Locate tab; The rest of Settings (and the Security Log's `settings_saved`); Add Phone, sign-in, the tab pages; Connecting (reconnect.js, `/ping`, `handyReconnect`); Tab-switch timing (`nav-timing.js`, `?timing=1`); Pull down to refresh (`pull-refresh.js`); The vibb identity (direction D: tokens, the floating tab bar, Nunito, icons); Locate: zones (design 64); A phone's own picture (server round C); Every phone's own colour (design 85 step 1: `devices::AVATAR_TONES`, `avatar_tone`); Locate's "Alle" view (design 85 step 2: `locate::Seen`, `phones.json`, Update All, `static/locate-all.js`); Field focus and the filled button (picks A and 3); The night band on every page (design 84: `partials/band.html`, the sheet, the editors' band and flash); The apps' own logos (design 87: `app_logos.rs`, the tiles, `/app-logos/`); Quick Actions as three tiles, Åpne for oppsett on Avansert (design 93)
- `docs/notes/server/retention-and-crashes.md` - Retention; Crash reports; What the server collects
- `docs/notes/server/unified-push.md` - App push (design 108): the push routes on the phone listener and their edge, the answers, who pays and the ban, the store and its tombstones, the `up` nudge, the policy's `unified_push` and the allowlist prune, Settings › App push and Device › Apps' group
- `docs/notes/server/integrations.md` - Integrations behind one boundary (`src/integrations/`, the `Integration` trait, a Cargo feature each, own tables and contract folder); the pilot, map links (design 110): the resolve route, the place lookup and its checks, the counter, Device › Apps' "Map links" group; the Apple Wi-Fi lookup (design 125 step 2): the sealed cache, the route, the proxy, the Location page's group
- `docs/notes/server/zones.md` - Safe zones, step 1 (design 64): the fix stored once, the two-fix rule, zone events as key + parameters, retention, the security log; step 2: the policy's zones and the 30-min floor, `extra_locations` stored before one judging, `zone_watch` and the mock count; the pages are in pwa.md, "Locate: zones"; a zone's confirmed Wi-Fi (design 138: `zone_networks`, the page's Bekreft, the strike intake, the policy's `networks`)
- `docs/notes/server/logins.md` - Wi-Fi sign-in and Chrome only for logins (design 102): migration 0150, `logins.rs`, the events, Device › Sign-in, Advanced's `#wifi-signin`
- `docs/notes/server/setup-status.md` - The phone setup status (design 114): the step table and rule, migration 0157, the setup hold's `paused_at`, Device › Oppsett, the tick routes, the warning lines, the Parent PIN before a code
- `docs/notes/server/device-key.md` - The hardware-bound phone key, S1 (design 129): `src/attestation/` (the chain, the pins, Google's status list, the GrapheneOS list), `src/device_key/` (the routes, the key index, the signature at the edge, the signature clock, `Vibb-Time`), migration 0174, About This Phone's quiet value, the contract
- `docs/notes/server/integration-health.md` - Integration health (design 116 part B): the store, the classes, `raised`, `for_device`, and how a boundary integration (design 110) feeds it
- `docs/notes/server/calendar.md` - Dagen (design 69): the policy's `calendar`, the report's `calendar` and its sanitizer, the title rules' grammar, Device › Calendar and the rule editor's preview, `app_display.on_home`, the i18n layer's first pages; the calendar links (design 72: the model, `ics.rs`, the fetch, the endpoint, the page)
