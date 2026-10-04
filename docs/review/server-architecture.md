# kid-phone-server: architecture review (fork palchrb/kid-phone-server @ f9e4e96, v0.18.6)

Read-only review. I did not run cargo or the server. All file:line references are relative to
`/workspace/handy/kid-phone-server` unless prefixed with `launcher:` (= `/workspace/handy/kids-launcher-mdm`).
"Unverified" marks anything I inferred but did not check against source or a running system.

## 1. Architecture map

**Shape.** A single binary crate (no `lib.rs`), about 6.3k lines of Rust. `src/main.rs` (437 lines) builds the pool, runs
migrations, bootstraps the admin, wires every route by hand, spawns 7 background tasks, and serves.
- `src/models.rs`: DB row structs (`sqlx::FromRow`) and device wire DTOs in one file.
- `src/security.rs`: hashing, TOTP, lockout and IP bans, plus the three auth middlewares.
- `src/dns_engine.rs`: compiled blocklist held in memory (`Arc<RwLock<..>>`).
- `src/handlers/*.rs`: one module per feature (17). Each mixes SQL, business logic and Askama structs.
  There is no service or repository layer.
- `AppState` (`main.rs:20-35`) holds the `db` pool, `dns_compiled`, and `command_notify` (a `broadcast::Sender<i64>`).

**Routing and auth** (`main.rs:117-427`). Five routers are merged:
- `public_routes`: `/login`, `/auth/verify-2fa`, `/sw.js`.
- `onboarding_routes`: change-password, setup-2fa and logout, behind `require_session` (`security.rs:352`).
- `admin_routes`: everything else in the UI, behind `require_full_auth` (`security.rs:372`). This middleware
  forces the password change and TOTP setup before letting a request through. It puts `CurrentAdmin` into extensions.
- `device_public_routes`: `POST /api/devices/enroll`.
- `device_authed_routes`: `/api/devices/*`, behind `require_device_token` (`security.rs:400`). It SHA-256s the
  bearer token, looks up `devices.token_hash` and inserts `AuthedDevice`.

The session layer (`tower-sessions` with the SQLite store) wraps the whole app, including the device API
(harmless, but wasted work). There is a single admin role and no per-admin scoping.

**Schema after migrations 0001-0020** (only the relevant parts):
- `devices`: id, name, enrollment_code and expiry, `token_hash` UNIQUE, enrolled_at, last_seen_at.
- `device_policy`: one row per device, keyed on `device_id` with ON DELETE CASCADE. Columns:
  - `allowlist_json` (a JSON array in a TEXT column)
  - six `*_minutes` schedule overrides and `custom_schedule_enabled`
  - `kiosk_desired` (always 1)
  - `lock_task_features`
  - `override_pin_hash` and `override_pin_salt`
  - `quick_controls_mask`
  - `vpn_filter_enabled`
  - `updated_at`

  This is the natural home for new per-device scalar toggles. `wifi_mode`, `bluetooth_mode` and the Tailscale
  columns were dropped (0012, 0017).
- `global_schedule`, `dns_filter_settings`, `provisioning_settings`: singletons (`CHECK (id = 1)`).
- `device_status`: an append-only heartbeat (`installed_apps_json`, `app_version`, `app_version_code`,
  `offline_override_used`). It is never pruned (I found no prune task, unverified beyond a grep).
- `tracked_apps` (catalog) plus `device_tracked_apps` (per-device opt-in join). `device_pending_uninstalls` and
  `device_install_progress` are device-scoped queues.
- `device_commands`: the Find My Device queue. `device_locations` is pruned at 30 days.
- DNS tables: `dns_blocklists`, `dns_custom_domains` (`device_id` nullable means global),
  `device_blocklist_overrides`, `device_dns_events`.
- `device_journal_entries` and `device_browser_history_entries`: upserted on `(device_id, remote_id)`.
- `admin_users`, `security_events`, `banned_ips`. `launcher_releases` is dead (retired, never dropped).

**Policy assembly.** `GET /api/devices/policy` is handled by `device_api::policy` (`src/handlers/device_api.rs:64-192`). It:
1. loads `device_policy`;
2. parses `allowlist_json`;
3. resolves the schedule (device override or `global_schedule`);
4. pops the oldest undelivered `device_commands` row and marks it delivered inside this GET (`:129-151`);
5. reads the DNS upstream;
6. hashes `dns_filter_version` (`:199-232`);
7. lists pending uninstalls;
8. returns `PolicyResponse` (`src/models.rs:243-280`).

The response is plain snake_case JSON with no envelope and no version field. The launcher decodes it with
`ignoreUnknownKeys = true` and a SnakeCase naming strategy (`launcher:app/src/main/java/com/kidslauncher/mdm/server/MdmApi.kt:36-39`).
Every field in `launcher:.../dto/PolicyResponse.kt` has a default. Large data already follows a
"version token in the policy, fetch the separate endpoint only when it changes" pattern
(`dns_filter_version` → `GET /api/devices/dns-blocklist`). Reuse that pattern for the music library.

**SSE nudge** (`device_api.rs:608-619`). Each `GET /api/devices/commands/stream` subscribes to the global
`broadcast::channel(64)` (`main.rs:106`) and filters it to its own device id. It emits a payload-free
`data: command` event, with a keep-alive every 15 s. Senders:
- `devices::toggle_app` (`devices.rs:491`)
- `devices::update_policy` (`devices.rs:708`)
- `locate::queue_command` (`locate.rs:117`)
- `schedules::save_device_schedule` (`schedules.rs:191`)

Not nudged: global schedule saves (`schedules.rs:137-159`) and all DNS changes. Those wait for the next poll.
Lagged receivers drop events silently (`_ => None`), which is acceptable because the poll is the real delivery path.

**Templates and forms.** Askama templates live in `templates/`, and every page includes `partials/head.html` and
`partials/app_header.html`. The bottom nav already has 6 tabs (`app_header.html:6-26`).

There are no CSRF tokens. Forms are plain POST followed by a redirect (PRG). Checkboxes are parsed through
`Form<HashMap<String,String>>`, so a missing key means false (`devices.rs:605-643`). Some controls auto-submit
with an inline `onchange="this.form.submit()"` (`device_detail.html:78`). Askama escapes HTML by default; the only
`|safe` is the QR SVG (`provision_qr.html:44`).

`device_detail.html` already contains several cards plus one big "Save changes" form that posts to `update_policy`.
Sub-features (journal, browser history, schedule) are separate pages linked from a card. Follow that for calls.

## 2. Code quality verdict

The code is functional and heavily commented. The comments explain the *why* well (more than most hobby code), but
they are very verbose: `CLAUDE.md` is 50 KB. The structure is "big handlers that talk to SQL directly".

- **Handler size.** `devices::view_device` is about 186 lines (`devices.rs:223`). `device_api::status` is about 184
  lines and does five unrelated things (`device_api.rs:354-538`): heartbeat insert, uninstall GC, allowlist bootstrap,
  package-name *guessing* heuristic, location. `device_api::policy` is about 128 lines and `update_policy` about 110.
  `main.rs` is one 400-line function. None of these is unmanageable, but none is unit-testable as written.
- **Duplication.**
  - `add_to_allowlist` and `remove_from_allowlist` (`devices.rs:527-594`) are near copies.
  - The `DevicePolicy` default with `vpn_filter_enabled: true` is duplicated (`device_api.rs:75`, `devices.rs:242`).
  - The `.render().unwrap()` boilerplate appears 31 times.
  - `fetch_optional(..).await.ok().flatten()` is repeated everywhere.
  - "Read latest `installed_apps_json` and parse" appears 3 times.
- **Error handling: the main weakness.** There are 70 `.ok();` and 58 `unwrap_or_default()` calls, and only 11
  `tracing::warn/error` calls. Write failures are silent and the user still gets a success redirect.

  **Fail-open bug.** In `policy()` a DB error (for example `busy_timeout` exceeded on the Pi's SD card) becomes
  `None`, then `DevicePolicy::default()` (`device_api.rs:68-82`). That is `kiosk_desired=false, allowlist=None`,
  which the launcher treats as "no restrictions" (`launcher:.../AppEnforcer.kt:98,182`) **and caches**
  (`launcher:.../MdmSyncWorker.kt:406-417`). A non-2xx response would instead fall back to the cached policy.

  The same failure happens if `create_device`'s `device_policy` insert silently fails (`devices.rs:108-115`, `.ok()`).
  It also happens if `allowlist_json` fails to parse.

  This contradicts PLAN.md's "a server outage must never unlock anything". Fix it first (PR 2).
- **SQL patterns.** Everything uses runtime `sqlx::query`/`query_as` strings with `SELECT *` into `FromRow`. There
  are no `query!` macros (0 uses), so there is no compile-time checking. Binding is correct everywhere I looked, so I
  saw no SQL injection.
  - Transactions are used only in the journal and history uploads (`device_api.rs:708,836`).
  - The allowlist is a JSON blob updated read-modify-write without a transaction (`devices.rs:527-594`). Two quick
    auto-submitted toggles, or a toggle racing the heartbeat's `add_to_allowlist` (`device_api.rs:511`), can lose an
    update.
  - Command delivery is SELECT then UPDATE, not atomic (`device_api.rs:129-144`), and three client triggers can
    overlap. Use `UPDATE ... RETURNING`.
  - A GET with side effects (marking a command delivered) loses the command if the response never arrives.
- **Silent truncation.** `journal_upload` and `browser_history_upload` use `.take(200)` but return 204
  (`device_api.rs:712,840`). If the client ever sends more than 200, rows beyond 200 are lost for good, because the
  client advances its cursor. Return 413 or process everything.
- **Memory on a Pi Zero 2 W (512 MB).** `tracked_app_download` reads the whole APK into RAM (`device_api.rs:682`).
  Journal media and backup uploads allow 200 MB bodies buffered as `Bytes` (`main.rs:205,284,396`). Stream instead
  (`tower_http::services::ServeFile`, `Body::from_stream`).
- **Migration discipline.** This is good: sequential, small, commented, additive, and `DROP COLUMN` is used
  deliberately. One wart: `tracked_apps.package_name` is still `NOT NULL` and uses `''` as a sentinel
  (`models.rs:100-103`). Migrations are embedded (`sqlx::migrate!`, `main.rs:73`), so never edit an existing one.
  New fork migrations must take the next number, and upstream may take the same number. Coordinate (see risks).
- **Security posture.**
  - Good:
    - argon2 passwords (min 12 characters) and mandatory TOTP with a pending-session step (`auth.rs:129-148`).
    - Account lockout after 5 failures and an IP ban after 15 (`security.rs:17-28`).
    - Device tokens are 256-bit random and stored as SHA-256 (`security.rs:62-72`).
    - Enrollment codes are one-shot, 30-minute, 8 characters from a 31-symbol alphabet.
    - Default bind is `127.0.0.1:3100`.
    - The root watcher validates its own arguments (`deploy/install.sh:207-322`).
    - systemd sandboxing (`install.sh:130-134`).
  - CSRF: there are no tokens. Protection relies entirely on the session cookie's SameSite attribute.
    `tower-sessions` defaults to `SameSite=Strict`, HttpOnly and Secure, I believe. **Unverified**: the crate source
    was not available offline. Write a test that asserts the `Set-Cookie` attributes.
  - Session fixation: no `session.cycle_id()` on login or 2FA (`auth.rs:147,277`). Password change and TOTP reset do
    not invalidate other sessions (`auth.rs:511-528`, `auth.rs:537-551`).
  - TOTP has no replay protection (`auth.rs:239`).
  - `client_ip` trusts `X-Forwarded-For` unconditionally (`security.rs:77-85`), so any caller can dodge or frame IP bans.
  - **Threat-model gap.** The kid's phone is a tailnet peer, via embedded tsnet, and admin UI and device API share one
    port and host. Tailscale ACLs cannot separate them by path, so the kid can reach `/login`. Repeated wrong
    passwords lock the *parent* out for 15 minutes (`security.rs:172-200`, per-username lockout).
  - Rate limiting exists only on login. Enroll and the device API have none (acceptable given token entropy).
  - The device token never rotates; revocation means deleting the device.
  - `provisioning_settings.tailscale_auth_key` is stored in plaintext and embedded in the QR (`provisioning.rs:63-68`).
  - Supply chain: the root updater runs `curl .../siesta5787/kid-phone-server/master/deploy/update.sh | bash`
    (`install.sh:171-175`), with no checksum or signature on the release tarball. Upstream's `master` is effectively
    root on your Pi.

## 3. Adding calls/contacts (paired with launcher PR)

**Migration `0021_calls_contacts.sql`** (renumber at merge time if upstream moves):
```sql
ALTER TABLE device_policy ADD COLUMN calls_managed INTEGER NOT NULL DEFAULT 0; -- 0 = server says nothing
ALTER TABLE device_policy ADD COLUMN calls_enabled INTEGER NOT NULL DEFAULT 1;
ALTER TABLE device_policy ADD COLUMN sms_enabled   INTEGER NOT NULL DEFAULT 1;
CREATE TABLE contacts (                      -- global address book (siblings share grandparents)
  id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT NOT NULL,
  phone_number TEXT NOT NULL,                -- normalized: '+' and digits only
  created_at TEXT NOT NULL DEFAULT (datetime('now')), UNIQUE(phone_number));
CREATE TABLE device_contacts (               -- mirrors tracked_apps/device_tracked_apps
  device_id  INTEGER NOT NULL REFERENCES devices(id)  ON DELETE CASCADE,
  contact_id INTEGER NOT NULL REFERENCES contacts(id) ON DELETE CASCADE,
  allow_inbound  INTEGER NOT NULL DEFAULT 1,
  allow_outbound INTEGER NOT NULL DEFAULT 1,
  show_on_home   INTEGER NOT NULL DEFAULT 1,
  sort_order     INTEGER NOT NULL DEFAULT 0,
  PRIMARY KEY (device_id, contact_id));
```
- Use one contact row with in/out flags rather than two tables. That gives "separate allowlists" without duplicating
  names and numbers.
- `calls_managed` is essential for upstreamability. Existing installs see no behaviour change until a parent opts in.
- Emergency numbers stay hardcoded on the phone (always allowed) and are not server data.
- Number matching belongs on the phone (`PhoneNumberUtils`, with region awareness; check the exact API level on
  minSdk 34). The server only normalizes (strip spaces, dashes and parentheses; require a leading `+` or configure a
  default country code). Optionally add a `default_region` setting.

**Models** (`models.rs`):
- Add `calls_managed`, `calls_enabled` and `sms_enabled` to `DevicePolicy`. Note that `#[derive(Default)]` makes them
  `false`, so handle the defaults explicitly, like the existing `vpn_filter_enabled` hack.
- Add `Contact` and `DeviceContactRow` row structs.
- Wire types:
```rust
#[derive(Serialize)] pub struct CallPolicy { pub calls_enabled: bool, pub sms_enabled: bool,
                                             pub contacts: Vec<PolicyContact> }
#[derive(Serialize)] pub struct PolicyContact { pub id: i64, pub name: String, pub number: String,
                                                pub inbound: bool, pub outbound: bool, pub show_on_home: bool }
// PolicyResponse:
#[serde(skip_serializing_if = "Option::is_none")]
pub call_policy: Option<CallPolicy>,   // None <=> calls_managed = 0
```

**Handlers.** Add a new `src/handlers/calls.rs` and keep it out of `update_policy`, whose missing-checkbox-means-false
semantics would wipe settings if the forms were split.
- `GET /devices/{id}/calls` renders `templates/device_calls.html` with a settings form and a contact list.
- `POST /devices/{id}/calls/settings` saves managed, calls and SMS toggles.
- `POST /devices/{id}/contacts`: add new or attach existing.
- `POST /devices/{id}/contacts/{cid}`: in/out/home flags, auto-submit like `toggle_app`.
- `POST /devices/{id}/contacts/{cid}/remove`.
- Optional global `GET/POST /contacts`, but it is not needed in v1.

Every write ends with `state.command_notify.send(id)` and a redirect, and returns an error instead of `.ok()`.
Register the routes in `admin_routes` (`main.rs:143-350`). Add a "Calls & contacts" card to `device_detail.html` that
links to the page, as Journal does (`device_detail.html:45-55`).

**Policy endpoint.** Build `call_policy` in `policy()`, ideally inside a new pure-ish
`async fn build_policy(db, device_id) -> Result<PolicyResponse, sqlx::Error>`, so that errors become 500 and the
function can be tested (see PR 2).

**Backward compatibility:**
- Old launcher with new server: the unknown key is ignored (`ignoreUnknownKeys`). Nothing is enforced, so the UI must
  say so.
- New launcher with old server: the key is absent, the client default is `callPolicy: CallPolicy? = null`, and null
  means "unmanaged, do not touch the dialer". Never make the Kotlin default restrictive.
- Inside a non-null `CallPolicy`, missing lists mean empty (deny-by-default) only when managed.

**Versioning.** There is no policy version today. I recommend not introducing a global version number. Instead:
1. Keep changes additive with `Option` fields.
2. Add a `capabilities: Vec<String>` to `StatusReportRequest`, with `#[serde(default)]`, for example
   `["call_policy_v1"]`. Store it in a new `device_status.capabilities_json` column and show "this phone's launcher
   does not enforce calls yet" on the calls page.

   This beats `app_version_code` thresholds because fork and upstream version codes will diverge. For a breaking change,
   use a new field name (`call_policy_v2`), not a version number.

**Camera and permissions.**
- Add `device_policy.camera_disabled` and `screen_capture_disabled` columns, sent in a
  `device_controls: Option<DeviceControls>` field.
- Add a `device_app_permissions(device_id, package_name, permission, state CHECK IN ('default','granted','denied'), PK(...))`
  table plus a `device_policy.permission_policy` column (`prompt|auto_deny`), sent as `app_permissions: Option<Vec<..>>`.
- Render these on the existing per-app rows of the Apps card, or on a `/devices/{id}/permissions` sub-page.
- Hardening restrictions in PLAN.md are "always on", so they need no server field. Consider one admin-facing
  `maintenance_until` timestamp that temporarily relaxes `DISALLOW_DEBUGGING_FEATURES` for servicing.
- **Music library:** do not inline it. Add `music_library_version` (a hash) to the policy plus a
  `GET /api/devices/music-library` endpoint, same as the DNS list.

## 4. Tests

**Confirmed: there are none.** I found no `#[test]`, `#[tokio::test]` or `sqlx::test` in `src/`, and no `tests/`.
CI (`.github/workflows/ci.yml`) runs only `cargo build --locked` and `cargo fmt --check`. There is no clippy and no
`cargo test`.

**Proposed PR 1, "test harness", behaviour-neutral:**
1. Extract `pub fn build_app(state: AppState, session_store: SqliteStore, insecure_cookies: bool) -> Router` from
   `main.rs:102-427`. Keep the background tasks, bind and bootstrap in `main`.

   A `lib.rs` is optional. `#[cfg(test)] mod tests` inside the bin crate works with `cargo test`. Moving modules
   into `src/lib.rs` (`kid_phone_server::build_app`) enables `tests/*.rs` and is cleaner if upstream agrees.
2. Test DB helper:
   - `SqlitePoolOptions::new().max_connections(1).connect("sqlite::memory:")`. A single connection is required
     because each in-memory connection is a separate DB.
   - `sqlx::migrate!("./migrations").run(&pool)`.
   - `SqliteStore::new(pool.clone()).migrate()`.

   Alternatively use `#[sqlx::test(migrations = "./migrations")]`, which creates a throwaway SQLite file per test
   (needs the `migrate` feature, on by default in sqlx 0.8; unverified for this exact feature set).
3. Requests: `tower::ServiceExt::oneshot` (dev-deps: `tower = { features = ["util"] }`, `http-body-util`).
   - `login` and `verify_2fa` extract `ConnectInfo<SocketAddr>`, so add `.layer(MockConnectInfo(addr))` in tests.
   - Seed helpers:
     - `seed_admin`: insert with `totp_enabled=1`, a known secret and `must_change_password=0`; log in via POST
       `/login` and then `/auth/verify-2fa`, using `security::totp_for_secret(..).generate_current()`; carry the
       `Set-Cookie` header manually.
     - `seed_device`: insert `devices` and `device_policy` rows with a known token, and return the bearer.
4. First tests, picked to pin behaviour we are about to touch:
   - `policy` for a fresh device: kiosk true, allowlist from JSON, schedule resolved global vs override.
   - `pending_command` delivered exactly once.
   - `require_device_token` returns 401 without a token or with a bad one.
   - Admin routes redirect to `/login` without a session.
   - `toggle_app` adds to and removes from the allowlist and queues an uninstall.
   - `status` allowlist bootstrap only on the first heartbeat.
   - `Set-Cookie` attributes (SameSite, HttpOnly, Secure).
   - A snapshot of the `PolicyResponse` JSON keys, as a contract test against the launcher DTO.
5. CI: add `cargo test --locked` and `cargo clippy -- -D warnings` (clippy may need a cleanup pass first; consider
   making it non-blocking initially).

Gotchas: hard-coded relative `data/` paths (journal media, backups, `watcher_version`) are cwd-relative, so keep tests
away from those handlers or use a temp cwd. `dns_engine::compile_blocklist` hits the network, so do not call it in tests.

## 5. Deployment

- **Build.** `release.yml` runs on `v*` tags and checks that the `Cargo.toml` version equals the tag. It cross-builds
  `aarch64-unknown-linux-musl` with `cross` and packages the binary plus `static/` (templates are compiled in) into a
  GitHub Release tarball.
- **Install** (`deploy/install.sh`, run as root via curl|bash):
  - Creates a system user and puts everything in `/opt/kid-phone-server`.
  - Writes `.env` (0600) with a generated admin password and `BIND_ADDR=127.0.0.1:3100`.
  - Installs the systemd unit (`install.sh:114-137`), with the hardening noted above and `ReadWritePaths=data`.
  - Installs the root-side "watcher" units: a path unit on `data/update_requested`, plus scheduler and backup-sync
    timers. These execute a fixed action set: app update, restart, apt upgrade, tailscale update, reboot, format
    drive, restore backup.
  - `WATCHER_SCHEMA_VERSION` (`install.sh:25`) must match `security::REQUIRED_WATCHER_SCHEMA` (`security.rs:283`).
- **Config** (env): `ADMIN_USERNAME`, `ADMIN_PASSWORD` (first boot only), `DATABASE_URL`
  (default `sqlite://data/kidphone.db`), `BIND_ADDR`, `INSECURE_COOKIES`. Logging is `tracing_subscriber::fmt::init()`;
  `RUST_LOG` is presumably honoured via the `env-filter` feature (unverified). Data lives in `/opt/kid-phone-server/data`.
- **Exposure.** DEPLOY.md recommends `tailscale serve --bg --https=443 http://127.0.0.1:3100`, tailnet-only and not
  Funnel. DEPLOY.md:15 claims `http://<pi-tailscale-ip>:3100` works, but that contradicts the loopback bind and
  Secure cookies, so treat it as a doc bug. Whether `tailscale serve` sets `X-Forwarded-For` is unverified.
- **Fork blockers.** Upstream identity is hard-coded in:
  - `REPO` in `install.sh:14,171` and `update.sh:11`
  - `system_update.rs:30`
  - `security.rs:305`
  - `provisioning.rs:40-56`: the debug component `com.kidslauncher.mdm.debug`, upstream's debug-keystore cert
    checksum, and the upstream `app-debug.apk` URL

  As things stand, an "Update now" on our Pi installs **upstream's** build. Make these configurable (env or
  `provisioning_settings`) as an upstreamable PR. Note also that production devices run debug-signed launcher builds.

## 6. Top risks and recommended PR order

Risks, highest first:
1. **The fail-open policy on DB or parse errors** (section 2) can unlock a phone and poison its cache.
2. **The supply chain.** The root updater pipes upstream `master` into bash, the hardcoded upstream repo makes us
   silently revert to upstream, and nothing is signed.
3. **No tests,** with 70 silent `.ok()` writes, so regressions in the new call and permission features would be invisible.
4. **The kid is on the same tailnet as the admin UI.** That allows parent lockout via failed logins and spoofed
   `X-Forwarded-For`.
5. **Migration-number collisions** with upstream (embedded, sequential `sqlx::migrate!` fails on mismatched
   checksums or versions). Talk to upstream early, and keep fork-only migrations rare.
6. **Allowlist JSON races and non-atomic command delivery.**
7. **Pi Zero memory** with whole-file buffering of up to 200 MB.

Recommended PR order (each upstreamable unless noted; pair with launcher PRs only where the wire format changes):
1. **Test harness**: `build_app` extraction, in-memory DB helpers, about 8 baseline tests, `cargo test` in CI.
2. **Fail-closed policy**: `build_policy() -> Result`, 500 on DB error, atomic `UPDATE..RETURNING` command delivery,
   a transaction around the allowlist read-modify-write, and tests.
3. **Configurable upstream identity**: repo, launcher APK URL, component, checksum. A fork prerequisite, upstreamable
   as config.
4. **Device capabilities in the status report** (`capabilities_json`), plus a UI helper "launcher doesn't support X".
   Paired with a tiny launcher PR.
5. **Calls & contacts** (section 3). Paired.
6. **Camera, screen capture and per-app permission policy.** Paired.
7. **Security polish**: `cycle_id` on login, invalidate sessions on password or TOTP change, only trust
   `X-Forwarded-For` from loopback, TOTP replay guard, optional per-IP limit on the device API, streaming downloads.
8. **Music-library blob** (version hash plus endpoint), handy-specific framing but a generic mechanism.
