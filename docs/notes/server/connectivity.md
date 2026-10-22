# Server notes: connectivity

Moved verbatim from server/CLAUDE.md on 2026-10-19; the core file's 'Where the rest is' lists every note.

## Connectivity and exposure (design 22, `docs/design/22-connectivity-and-docker.md`, server 0.23.0)

- **Two listeners** (S1, `main::serve`, one `try_join!`): `BIND_ADDR` (default `127.0.0.1:3100`) =
  `build_admin_router` (admin, onboarding, public routes, `/static`, the device API unless `ADMIN_DEVICE_API=off`,
  `/healthz`, empty-404 fallback), and `DEVICE_BIND_ADDR` (unset = none) = `build_device_router` (`device_routes()`
  + `/healthz` + empty-404 fallback; no session layer, no `/static`). `ADMIN_DEVICE_API=off` without `DEVICE_BIND_ADDR` refuses to
  start (QA 22 Low 2). `/login` and `/auth/verify-2fa` take 4 KiB bodies.
  Equal addresses refuse to start; one exposure line is logged (`NetConfig::exposure_line`). SIGTERM/Ctrl-C: graceful
  stop, `CommandStreams::close_all` ends every SSE stream (each stream merges its close signal, a `watch`), 8 s grace.
- **`src/net.rs`** (`AppState.net`, `NetConfig::from_vars`): `BIND_ADDR`, `DEVICE_BIND_ADDR`, `ADMIN_DEVICE_API`
  (on), `ADMIN_PUBLIC` (off), `TRUSTED_PROXIES` (`127.0.0.1,::1`; IPs or CIDRs, a bad entry is dropped, never
  widened), `ADMIN_TAILSCALE_USERS`. `NetConfig::client`: `X-Forwarded-For` only from a trusted peer, walked from the
  right skipping trusted entries (all trusted: the left-most); an entry that isn't an address stops the walk and
  makes the client unknown (`Client::ip` is `None`, keyed `unknown via <peer>` by `Client::key`) - never the peer,
  which is the proxy (QA 22 H1); IPv4-mapped peers are canonical.
  `limit_key`: IPv4 address or IPv6 /64. Login uses it (the old `security::client_ip` took the left-most XFF entry
  from anyone - §0 #1).
- **Admin gate** (`src/gate.rs`, outermost layer of the admin router): passes loopback and tailnet
  (100.64.0.0/10, fd7a:115c:a1e0::/48) clients and a trusted peer without XFF; an unknown client and any forward
  from an untrusted peer are refused (QA 22 H1); `Tailscale-Funnel-Request` is refused;
  `ADMIN_PUBLIC=on` passes everyone. `ADMIN_TAILSCALE_USERS`: admin pages also need a listed `Tailscale-User-Login`,
  believed only from a tailnet client; `/api/devices/*` skips that check (tagged phones have no login). `/healthz`
  (`SELECT 1`, "ok") skips every guard. Refused: empty 403 + `admin_refused` through `AppState.audit`
  (`src/audit.rs`: one row per kind and client key per minute, counts flushed into the detail every 30 s by
  `audit::run_flusher`; at most `ROWS_PER_MINUTE` = 60 such rows per kind and minute across all keys, the rest
  counted into one summary row - QA 22 M3).
- **Device edge** (S2, on both listeners, in `device_routes()`): `limits::device_edge` (a request without a valid token -
  looked up in memory - takes one of 64 slots, 503 past it; a valid token takes none, QA 22 M2; 30 s timeout except the SSE stream and file downloads; `Cache-Control` `no-store` unless the route set
  one, `private, no-cache` for an `ETag` route, `private` added to anything else; `nosniff`; CSP `default-src 'none';
  sandbox`), a 512 KiB body limit (enroll reads its own body: at most 4 KiB, within 5 s, inside its cap of 8). `security::require_device_token`: the token is looked up in
  `AppState.tokens` (`TokenIndex`, `token_hash -> id`, rebuilt at start and by `refresh` after enroll and delete -
  never a DB query per request); `AuthedDevice` now carries only `DeviceRef { id }`. A bad or missing token counts
  against the client's failure bucket (`limits`, key `net::limit_key`: 30 per 10 min, then 429 for 10 min) and a
  throttled `device_auth_failed`; a valid token never touches it: its own bucket (600, 2/s), then an in-flight slot
  (`route_class`: APK/music file downloads 4, others 8, `policy`/`status`/`command-result`/`command-receipt` and the SSE stream always
  admitted - the stream is bounded by its cap of 2, QA 22 Low 3),
  held until the response body ends (`limits::hold_until_done`). Maps capped at 10k keys. Last access per phone
  (when, tailnet/loopback/outside, the last 5 outside IPs) in memory, About This Phone's "Network" row (`Via::label`, the IPs behind its ⓘ).
  `CommandStreams`: 2 live streams per phone (a third closes the oldest), `close_device` on delete and re-enroll.
  Warnings: a wide bind with only loopback trusted (startup), an untrusted peer sending XFF (logged once,
  `Limits::exposure_warning` for the Connection page).
- **Enrollment** (S3, `src/enrollment.rs`, migration `0052_enrollment_codes.sql`): one live code per phone, stored
  only as SHA-256 of the normalised code (upper-case, no spaces/dashes) in `devices.enrollment_code_hash` with
  `enrollment_code_kind` - `qr` (26 chars base32, 30 min, only in the setup QR) or `typed` (8 chars of
  `TYPED_ALPHABET`, 15 min, shown as ABCD-EFGH); the plaintext column is cleared and never written (open codes died
  with the migration). A new device has no code; "New Setup Code" (`POST /devices/{id}/regenerate-code`) renders
  `enroll_code.html` once (`no-store`), Connect (`/devices/{id}/provision`) makes a QR code. `enroll`: not code-shaped -> 401;
  typed-shaped -> 429 while paused or the client's failure bucket is full, 401 uncounted with no typed code live, a
  miss counts toward the bucket and `Enrollment::typed_failed` (20 an hour server-wide while a typed code is live
  pause typed codes until the hour ends: `enroll_typed_paused`, the summary's Connect This Phone, Connect and the code
  page say so and offer the QR); QR-shaped always
  checked. At most 8 enrollments at once (`Enrollment::gate`, taken before the body is read; the body then has 5 s). Use = one `UPDATE ... WHERE
  enrollment_code_hash = ? AND not expired RETURNING`; then the token index, streams, `device_enrolled` (with IP).
  Failures: throttled `enroll_failed`. Advanced › Connection (`/devices/{id}/advanced#connection`, the device page's
  "Access" card until design 31 step 3; a phone not set up has the summary's Connect This Phone group instead): the live
  code's kind and local expiry, New Setup Code (re-enroll: disconnects the enrolled phone), Set Up with QR Code,
  Disconnect Phone… (a `<details>` confirm with the required box; `POST /devices/{id}/revoke`; `token_hash = NULL`,
  index, streams closed, `access_revoked`; back to `/devices/{id}/advanced?notice=…#connection`, the flash in the group).
- **Admin auth** (S4, `src/admin_auth.rs`, migration `0053_admin_auth.sql`; no step-up, user decision U2): argon2
  (verify and change) runs in `spawn_blocking` behind `AppState.passwords` (`PasswordGate`: 1 running, 4 queued, else
  429), an unknown username verifies a dummy hash. Wrong passwords never lock the account (only the per-IP ban, now
  keyed by `net::limit_key`; unban is `POST /security/unban` with an `ip` field since a key can be a /64). TOTP:
  `check_totp` accepts a step within +-1 that is later than `admin_users.totp_last_step` (stored in the same UPDATE;
  setup too); `take_totp_attempt` (one conditional `UPDATE ... RETURNING`, taken *before* the code is checked, so parallel
  requests can't slip past it - QA 22 H2; locked = no code checked): 5 attempts lock 15 min, each further lock doubles
  (`lock_count`) up to 24 h, a lock running out resets nothing, only a complete login does (`reset_after_login`); a correct password on a locked
  account says so (`login_locked` event). The pending state (`pending_admin_id` + `pending_at`) lasts 5 min. After a
  complete login with earlier failures the session carries a banner (`login_warning`, a short orange row on Devices
  and the full sentence on Account, `POST /account/dismiss-warning`, back to the page it was on); a signed-in session
  also sees wrong codes entered since its sign-in and the lock (`auth::live_warning`, from the `AdminUser` row - QA 22
  Low 9), and a password change lifts the counter and the lock. Sessions: `cycle_id()` after each step; the
  session stores `session_epoch`, and a password change, a 2FA reset (asks for the current password - user
  decision on QA 22 M5; other sessions) and `POST
  /account/sign-out-everywhere` (all) bump it (`load_active_admin` compares); cookie `__Host-handy` (Secure, Path=/,
  HttpOnly, Strict) or `handy` with `INSECURE_COOKIES` (`security::session_layer`); `SESSION_INACTIVITY_DAYS`
  (`NetConfig`, default 30). `kid_phone_server reset-2fa <user>` (`main::run_command`) clears TOTP, lock and counter,
  bumps the epoch and replaces the password with a new one in `data/initial-admin-password-<id>` (QA 22 M4; one file per
  account since design 62, which also restores a removed account and clears a one-time password's expiry). `admin_headers`: HSTS, nosniff, Referrer-Policy, X-Frame-Options, COOP, Permissions-Policy,
  CSP phase 1 (`ADMIN_CSP`, inline JS still allowed until S7); HTML `private, no-cache`, `no_store_path` (locations
  JSON, backups, provision, the code page, provisioning settings, account, auth; since design 35 the inbox) `no-store`. `csrf_guard` (unsafe
  methods, not `/api/devices/`, not `/notifications/receipt` - the push receipt's token is its proof): `Sec-Fetch-Site` same-origin/none, else `Origin` host[:port] = `Host` (or
  `X-Forwarded-Host` from a trusted peer), neither header passes; refused = 403 + throttled `csrf_refused`.
  Provisioning: `GET /devices/{id}/provision` (Connect) is the form only, `POST` makes the QR (Wi-Fi password never in a URL);
  the Tailscale key is write-only (`settings::key_hint`, empty keeps, `clear_tailscale_auth_key` removes).
  `security_events` pruned after 180 days (`retention::prune`). The TOTP secret stays in the DB (QA b, DEPLOY.md).
- **Docker and startup** (D1, `src/startup.rs`, `Dockerfile`, `deploy/image-smoke.sh`): `DEPLOY_KIND` (`NetConfig`,
  systemd default, the image sets docker). Startup for every kind: `check_data_writable` ("data/ is not writable by
  uid N", exit 1); `apply_restore_request` (the container's restore: `data/restore_request` written by
  `backups::restore_in_container`, which exits 3 a second later for the restart policy; startup deletes the marker
  first, checks the name/zip/`kidphone.db` like `actions.sh` (at most `RESTORE_MAX_BYTES`, 1 GiB), copies the DB +
  wal/shm to `backups/prerestore-<ts>/` (`PRERESTORE_KEEP` = 3, the new one always kept), swaps, and `undo_restore` puts it back if `try_connect_db` fails;
  `backup_restored` event); `try_connect_db` = `connect_db` without the panic, taking
  `<db dir>/backups/pre-migrate-<from>-<to>.db` (`VACUUM INTO`, 3 kept, a failure stops the start) when migrations
  are pending on an existing DB (`MIGRATOR` static); `bootstrap_admin` takes `ADMIN_PASSWORD`, else
  `ADMIN_PASSWORD_FILE`, else generates `data/initial-admin-password` (0600; the log names the file; deleted by the
  first account's forced password change); `ADMIN_USERNAME` (only read while `admin_users` is empty) is trimmed and must
  be `security::valid_username`, else the start stops with the reason (design 62 §2). Logging defaults to `info` without `RUST_LOG` (tracing-subscriber's own default was
  errors only, so the startup lines never reached the journal). Subcommands (`main::run_command`): `healthcheck`
  (`/healthz` on `BIND_ADDR`, 0.0.0.0 -> 127.0.0.1, :: -> ::1, 3 s), `selftest-tls` (api.github.com through
  reqwest/rustls, any status passes), `reset-2fa <user>`. In docker: the root-helper routes (`/update/*`,
  `/system/*`, `/backups/format-drive`) aren't registered, the live mirror and app-update scheduler aren't
  spawned, Software Updates (`updates::page`, pure) says "Version X is out" with "Set `HANDY_VERSION=X` in `.env`, then
  `docker compose pull && docker compose up -d`" behind its ⓘ, no OS/Tailscale/installer groups, and Backups has no
  external drive or mirror.
  Image: distroless static nonroot (uid 65532) pinned by digest, a busybox stage seeds `data/` and `data/keys/`
  (0700) owned by 65532, `BIND_ADDR=127.0.0.1:3100`, `HEALTHCHECK` = `healthcheck`. CI `server-image` (only when the
  Dockerfile, `.dockerignore`, the smoke script or the workflow change) and the release's `server-release-image`
  (push by digest, smoke amd64 + arm64 under QEMU, then tag `X.Y.Z`, and `latest` only when X.Y.Z is the highest
  `server-v*` tag, provenance attestation, release published last; `workflow_dispatch` = dry run, on a tag ref too:
  `RELEASE` needs a push event) run `deploy/image-smoke.sh`.
- Tests: `src/tests/docker.rs` (the pre-migration backup once and 3 kept, from a real older DB; the restore marker
  with a good zip, bad names, a zip without a DB, and a DB that won't open put back; the healthcheck; the Docker
  Updates page; the host routes 404 and the drive features hidden in docker), `startup::tests`.
- Tests: `src/tests/admin_auth.rs` (the lock and its doubling, the banner, no password lock, a code works once,
  pending expiry, the epoch on password change/2FA reset/sign out everywhere, `reset-2fa`, 20 parallel logins while
  an SSE keepalive keeps its 1 s beat, CSRF, headers and cache rules, the cookie, the write-only key, pruning, IPv6
  bans by /64), `admin_auth::tests`. `TestApp::next_totp_code` picks a step not used yet; `admin_cookie` uses it.
  `src/tests/enrollment.rs` (QR once, no plaintext stored, a race of 8 gives one token, expiry, typed
  failures without a live typed code count for nothing, the pause with QR codes still enrolling after 200 junk QR
  codes, the shared failure bucket, New code once and replacing, revoke, the setup QR's kind), `enrollment::tests`.
  `src/tests/edge.rs` (a valid token behind a full failure bucket, IPv6 /64, throttled events; policy and
  command-result past full slots; a stream holds its slot; a third stream closes the first, delete ends the rest and
  the token; re-enroll retires the old token; 413s and headers on both listeners; last access on the page),
  `limits::tests`. `src/tests/listeners.rs` (no `"/api/devices` literal in `main.rs`; the device router 404s every admin path,
  sets no cookie and answers every route it reads from `device_routes.rs`; `ADMIN_DEVICE_API=off`; the gate matrix
  and its throttled event; `/healthz` past every guard; shutdown ends streams on both listeners), `net::tests`,
  `gate::tests`, `streams::tests`.

## Parent accounts (design 62 steps 1-2, `docs/design/62-parent-accounts.md`)

- **Several equal accounts in one family** (migration `0087_parent_accounts.sql`): `admin_users.display_name` (NULL
  reads as the username, `AdminUser::name`), `invited_by`, `invite_expires_at` (a one-time password's onboarding ends
  then; written in SQL, `datetime('now', '+72 hours')`), `removed_at` (a soft remove: the row and its username stay,
  `password_hash = '!removed'`, no 2FA). **Ready** = not removed, own password set, 2FA on (`AdminUser::ready`).
- **Usernames** `security::valid_username` (`[a-z0-9._-]{1,32}`): Add lowercases and trims; `ADMIN_USERNAME` is only
  trimmed (sign-in is an exact match) and refused at the first start when invalid (`bootstrap_username`); existing
  installs are untouched. Add refuses a name whose `lower(username)` is taken, removed ones included. **Display
  names** `security::display_name`: trimmed, 1-32 characters, no control, line-break or Unicode format (Cf)
  character, so a "by" line can't be spoofed. The TOTP label replaces `:` with `_` (it panicked the setup before).
- **Sessions:** `load_active_admin` reads only rows with `removed_at IS NULL` and an unexpired `invite_expires_at`;
  `login` and `verify_2fa` read `removed_at IS NULL` (a removed name takes the dummy hash, the same message). A right
  password past the expiry says "This invitation has expired - ask for a new one." (`login_expired`); a password-only
  sign-in of an account that must change its password logs `login_success` with "one-time password".
- **The five self-service writes** (the forced change, `update_account_password`, `reset_totp`, `setup_2fa_form`,
  `setup_2fa_verify`) carry `AND session_epoch = ? AND removed_at IS NULL`; no row = signed out. The forced change
  (`admin_auth::change_first_password`) also compares the hash it verified, so of two sessions on one one-time
  password the first wins; `setup_2fa_form` writes a secret only onto an account without one (`COALESCE`). The
  2FA setup (GET and POST) sends an account that must still change its password to `/auth/change-password` first,
  and `invite_expires_at` is cleared only by the write that makes the account ready (`setup_2fa_verify` when the
  password is changed, `change_first_password` when 2FA is on; qa-62-step12-code P62-1). A request held past a
  Reset or a removal writes nothing.
- **Files:** `reset-2fa <user>` writes `data/initial-admin-password-<id>`, clears `invite_expires_at` and restores a
  removed account (`parent_restored`) - also past Add's cap of 8, which its line then says (P62-3: the shell must stay
  the way back); the forced change deletes its own `-<id>` file, and `initial-admin-password` only for the first
  account (`invited_by IS NULL`).
- **Account's Name and Parents groups** (`handlers::parents`, the statements in `src/parents.rs`): your display name
  (`POST /account/name`, PRG `#name`, `display_name_changed`); the Parents rows (every account not removed: "You",
  "Signed in 14:02 · from …", "Waiting · until Sun 20:51" plus the one-time password's sign-in, "One-time password
  expired", "Waiting for sign-in"); **Add Parent…** (`POST /account/parents`: `new_username` with
  `autocomplete="off"`, the name, a hidden own-username field and your password; the one-time password's hash comes
  from the same gated `PasswordGate::change` job as the password check, then `record_reauth`; a wrong one logs
  `parent_change_refused` "add"; the `INSERT` carries the actor condition, the cap of 8 and the taken check); the
  answer is the page itself (no-store, `data-post-answer`) with the password in `.secret-once` blocks, a Copy button
  and `static/reveal.js` (a reload is a GET of `/account/parents`, it is removed when the page is left, the CSS hides
  it after 5 minutes and reveal.js removes it then too, so Copy ends with it - P62-4); `parent_added` "pappa (Pappa)".
  **Reset…** ("New One-Time Password…" on a not-ready row; `POST /account/parents/{id}/reset`, step 3): your password
  and the new one-time password's hash in one gated job, `parents::reset_parent` (§4.3's statement, never yourself, the
  target's push subscriptions in the same transaction), `parent_reset`, the answer shown as Add's ("New one-time
  password for Pappa"), its reload the GET of the same path. **Remove…** ("Withdraw…" on a not-ready row) is the soft Remove
  (`POST /account/parents/{id}/remove`, `admin_auth::reauth`, the guarded `UPDATE` + its push subscriptions in one
  `BEGIN IMMEDIATE` transaction with its prefs and reads, `parent_removed`, its Spotify login in progress dropped
  (`spotify::Accounts::forget_pending`), PRG `/account?parents=removed#parents`); the only ready account can't remove
  itself, and your own row has no action (the route still answers yourself by the same guard, then `/login`). The
  Backups restore confirm says "Parent accounts come back as they were in this backup."
- **"By <name>"** (step 4): `device_commands.requested_by` (0087) is set by Locate's commands, Unlock/Lock again/Allow
  these apps (`window_relock`), Install Now and the Child PIN's queue rule after a parent's save (`kid_lock::
  queue_pin_command(.., Some(admin))`; a status report's queue passes `None`). Locate's Requests and the summary's
  Activity add "· Pappa" (`parents::Names::requester`) once the family has more than one account (removed ones count,
  their names stay); never in the policy (`pending_command` is still `{id, command, requested_at}`). Lifts show the
  creator's display name (`Names::of_username`), the security log "Mamma (mamma) · 10.0.0.3" (`Names::logged`). `GET /account/parents/{id}/reset` serves Account for step 3's Reset answer. Every refusal
  renders Account (400) with its form open and the reason under the field, at `#parents`.
- Tests: `src/tests/parents.rs`, `admin_auth::reset_2fa_from_the_command_line_clears_the_lock_and_the_totp`,
  `jstest/reveal.test.js`.
