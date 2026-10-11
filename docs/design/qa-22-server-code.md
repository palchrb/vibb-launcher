# QA 22 server code review - S0-S4, D1, R1 (2026-10-11)

Scope: design 22's server series on `main`, S0 `5c93c3c0` to R1 `112b68b3`, plus the test fix `83392c17`:
`git diff 5c93c3c0^ 83392c17 -- server .github`. The brief's `99d88c61^` base leaves out S0 and S1, which landed
before `99d88c61`; they are included here. Judged against design 22 (the body, the QA review, the decisions, and the
server implementation status with its deviations), the user's answers, `docs/review/security-review-2026-10-10.md`,
`CLAUDE.md`, `server/CLAUDE.md` and `server/DEPLOY.md`. Paths are under `server/` unless they say otherwise. Line
numbers are at `83392c17`; `main` has moved on since (the security wave, from `06cd48d7`).

Build: a worktree of `83392c17`.
- `cargo test`: 431 passed. `cargo fmt --check`: clean.
- `cargo clippy --all-targets`: 15 warnings (17 at `99d88c61`), none new.
- `node --test jstest/*.test.js`: 7 passed.
- H1, H2 and M1 were reproduced with temporary probe tests (not committed): requests through the real admin router,
  and the real `PasswordGate`.
- Not run: no Docker daemon here, so the Dockerfile, `deploy/image-smoke.sh` and both workflows were read, not run.
  Nothing on the Pi or a phone.

Counts: 2 High, 5 Medium (one needs a user decision), 9 Low, 7 Simplification.

## High

1. **High: a malformed `X-Forwarded-For` from a trusted proxy makes the client "loopback", so the admin gate lets the
   internet in behind any proxy that appends to the header.**
   - Where:
     - `src/net.rs:341-349` (`None | Some(Err(())) => peer`): one entry that isn't an address makes the whole header
       resolve to the TCP peer. That peer is the trusted proxy itself, 127.0.0.1.
     - `src/gate.rs:62-64` passes any loopback client.
   - Scenario: a public site points at 3100 by mistake, which is the mistake the gate exists for (QA High 1). The
     proxy appends to the client's header instead of replacing it. That covers:
     - nginx with the usual `proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for`;
     - Apache's mod_proxy and HAProxy's `option forwardfor`;
     - Cloudflare, as a proxy or a Tunnel. DEPLOY names Cloudflare for the SSE timeout.

     The client sends `X-Forwarded-For: x`. The server gets `x, 203.0.113.7`, falls back to the peer and serves the
     admin. One header undoes §0 #1's walk.
     - Probe: `GET /login` from the peer 127.0.0.1, by the header sent:
       - `203.0.113.7`: 403.
       - These all give 200: `garbage, 203.0.113.7`; `, 203.0.113.7` (what nginx makes of a client's empty header);
         `unknown, 203.0.113.7`; `fe80::1%eth0, 203.0.113.7`.
     - The same request also counts as loopback in three places: the login ban key, the device limits (one shared key,
       `127.0.0.1`) and the device page's last access.
     - Caddy and `tailscale serve` replace the header, so DEPLOY's own setups are safe. Funnel is refused by its
       header.
   - A smaller hole of the same kind: a loopback proxy that is *not* in `TRUSTED_PROXIES` has its header ignored.
     - Two ways to get there: the list was set for a bridge gateway while Caddy runs on the host, or every entry was
       dropped as invalid.
     - Every client that proxy forwards is then 127.0.0.1, and passes. The server already sees this case
       (`Client::untrusted_forward`) but only uses it for a warning.
   - Fix:
     - In `NetConfig::client`, walk from the right and stop at the first entry that doesn't parse. If an untrusted
       address came before it, that address is the client (here `203.0.113.7`). Otherwise the client is unknown.
     - Model "unknown" explicitly, never as the peer.
     - Unless `ADMIN_PUBLIC` is on, the gate refuses an unknown client and any forward from an untrusted peer.
     - Limits key an unknown client apart from loopback.
     - Tests: a malformed entry, an empty entry, non-UTF-8 bytes, and an untrusted loopback proxy sending XFF. Each is
       refused by the gate.

2. **High: the 2FA lock doesn't bound guesses sent in parallel. 58 of 60 codes were checked against a lock of 5.**
   - Where: `src/handlers/auth.rs` takes three separate steps, with nothing serializing them:
     - `:244` reads the lock;
     - `:256` checks the code;
     - `:268` records the failure.

     `admin_auth::record_totp_failure` (`src/admin_auth.rs:206-224`) only skips while locked.
   - Scenario: someone who knows the password does one password step, then sends N codes at once on that pending
     session.
     - Every request that read "not locked" before the fifth failure was written gets its code checked.
     - A match completes the login: nothing checks the lock again after `Totp::Accepted`.
     - Every lock that runs out allows another burst (15 min, 30, ..., 24 h).
     - The IP ban check (`:219`) is racy in the same way, so one address is enough for a burst.
     - Probe: 60 parallel wrong codes on one pending session (multi-thread runtime) wrote 58 `totp_failed` rows, so 58
       codes were checked. The counter stopped at 5.
     - At 3 good codes in 10^6, bursts of a few thousand turn a phished password and a few days of locks into a real
       way into the admin. That holds in (b), where password and TOTP are all there is, and from any tailnet node in
       (a)/(c).
     - §3.2.1 and the M1 fix rest on this bound ("5 failures lock the account").
   - Fix (either one):
     - Take the attempt before checking: `UPDATE admin_users SET failed_login_attempts = failed_login_attempts + 1,
       lock_count = ..., locked_until = ... WHERE id = ? AND (locked_until IS NULL OR locked_until <=
       datetime('now')) RETURNING failed_login_attempts`.
       - No row means locked: refuse without checking the code.
       - Check the code only once the row is back.
       - A complete login resets the counter, as now.
     - Or serialize the TOTP step server-wide: a `tokio::sync::Mutex` held from the lock read to the failure write.
       It is a few milliseconds per attempt, for one family.
     - Test: 50 parallel wrong codes on one pending session; at most 5 are checked.

## Medium

1. **Medium: a cancelled login frees its argon2 slot while argon2 keeps running, so the gate bounds nothing.**
   - Where: `src/admin_auth.rs:81-86`. `_admitted` and `_slot` live in the request's future, and
     `spawn_blocking(work)` keeps running when that future is dropped.
   - Scenario:
     - hyper drops a handler's future when the client closes the connection mid-request. That is HTTP/1 with
       half-close off, axum's default (`hyper::proto::h1::conn::mid_message_detect_eof`).
     - A client posts `/login` and hangs up about 50 ms later. The slot frees at once and the argon2 run (about 1 s
       and 19 MiB) goes on. The next request starts another one.
     - Repeated, as many run at once as the blocking pool allows (512 threads). That uses up the CPU and the memory of
       a 512 MB Pi Zero 2 W, and takes the device API down with it (lock, locate).
     - This is §0 #8 again, without its old bound of 4.
     - A dropped request also never writes `login_failed`, so the IP ban never comes.
   - Probe:
     - One verify took 0.93 s. Two argon2 at once, outside the gate, took 1.38 s.
     - With one verify aborted 0.09 s into its run, the next took 1.03 s. It would take 1.76 s if the slot were held.
   - Reach: the admin listener only. Any client in (b); tailnet nodes otherwise.
   - Fix:
     - Take owned permits (`try_acquire_owned` for the queue of 5, `acquire_owned` for the run slot) and move both into
       the `spawn_blocking` closure. They are then released when argon2 ends, not when the request does.
     - Record the attempt before the verify, so a hang-up still counts toward the ban.
     - Test: abort a verify mid-run; a second one waits for it.

2. **Medium: slow enroll bodies hold the device listener's global slots, so one internet host can keep every phone at
   503.**
   - Where:
     - `src/limits.rs:411` takes a global slot (512) before anything else.
     - `:422` gives non-streaming routes 30 s.
     - `hold_until_done` keeps the slot until the response ends.
     - `enroll` (`src/handlers/device_api.rs:37`) reads its JSON body inside that time.
     - The enroll cap of 8 (`:76`) starts only after the body is read. That is the deviation "a slow body can't hold
       it"; the slow body holds a global slot instead.
   - Scenario (c):
     - One host opens about 512 connections. Each sends enroll headers, then its 4 KiB body a byte at a time.
     - Each holds a global slot for 30 s, so about 17 new connections a second keep the phone listener at 503 for
       every phone: policy, command-result and new SSE streams. Lock, locate and ring don't arrive.
     - That is QA High 2's kill switch, at 512 instead of 128.
     - Caddy streams request bodies, and `read_header` doesn't cover them. A body that never ends never reaches the
       failure bucket.
   - It also departs from the user's answer that valid device tokens skip the global limits: the 512 guard applies to
     them too.
   - Fix:
     - Valid tokens bypass the global guard. The token lookup is in memory and cheap, so do it first in `device_edge`.
     - Requests without a valid token get their own, smaller guard (say 64).
     - Enroll reads its body under a short timeout (5 s), inside its cap of 8.
     - Test: 600 stalled enroll bodies, then `policy` with a valid token gives 200.

3. **Medium: the throttled security events have no global budget, so a client with many addresses can fill the disk.**
   - Where: `src/audit.rs:38-87` writes one row per kind and limit key per minute. Every new key also scans the
     10k-key map (`evict_oldest`) under the mutex.
   - Scenario (c):
     - A client with many addresses sends bad tokens or junk codes to the public phone listener. An IPv6 /48 (free
       from a tunnel broker) is 65,536 /64 keys; a botnet works too.
     - Every new key is a new row. At about 1,000 requests a second that is about 86 million rows (about 15 GB) a day,
       kept 180 days, on the Pi's SD card, until the disk and the server are full.
     - Before S2/S3, a failed device auth or a failed enrollment wrote nothing.
     - `admin_refused` and `csrf_refused` behave the same on a misrouted admin.
   - Fix:
     - A budget per kind and minute across all keys (say 60 rows).
     - Past it, count into one summary row per kind and minute ("N more from M addresses").
     - Test: 10,000 failures from distinct /64s write at most the budget plus one row.

4. **Medium: `reset-2fa` hands the account to whoever signs in first.**
   - Where: `src/admin_auth.rs:300-325`; DEPLOY "Signing in and recovery".
   - Scenario:
     - The CLI exists for QA #4's lever: someone knows the password and keeps the account locked. One wrong code a day
       keeps a 24 h lock going.
     - The parent runs `reset-2fa admin`. It clears the TOTP, so the next password-only sign-in is a full login
       (`src/handlers/auth.rs:137-154`) and lands on 2FA setup.
     - The attacker, who is scripting sign-ins anyway, gets there first and enrolls his own authenticator.
     - The parent is locked out again until the next run of the CLI, which has the same race.
   - Fix:
     - `reset-2fa` also replaces the password. Generate one into `data/initial-admin-password` (0600,
       `must_change_password = 1`, as `bootstrap_admin` does) and print the path, or read a new one from stdin.
     - DEPLOY and the account page say so.
     - Test: after `reset-2fa`, the old password no longer signs in.

5. **Medium (user decision): "Reset 2FA" needs no password, and since S4 it also signs the parent out everywhere.**
   - Where: `src/handlers/auth.rs:674-694` (`POST /account/reset-2fa`). It asks for no password and, since S4, bumps
     the epoch (`signed_out_elsewhere`).
   - Scenario: a stolen session posts it. That is the threat model's parent phone with a live session, or an XSS while
     CSP keeps `'unsafe-inline'`.
     - The stolen session enrolls its own authenticator, and every other session (the parent's) ends at once.
     - The parent can't sign in without the new authenticator, and needs the server's CLI (and M4's fix).
     - Before S4, the parent's sessions at least survived.
     - Changing the password needs the current one; resetting 2FA doesn't.
   - The security review's M3 asked for password and TOTP here, and QA design #5 proposed the current password. The
     decisions dropped both together with the TOTP step-up (U2). Asking for the password isn't a TOTP step-up.
   - **User decision**: require the current password for "Reset 2FA" (one field, checked through `PasswordGate`), or
     keep it as it is.

## Low

1. **Low: `/login` stores the username as sent.**
   - `src/handlers/auth.rs:102` writes `form.username` as is into `login_failed`.
   - The admin listener has no body limit, so axum's Form default (2 MB) applies.
   - In (b) that needs no sign-in: 15 × 2 MB per key and hour before the ban, unbounded across IPv6 /64s. `/security`
     renders 200 such rows.
   - This is the M7 item "cut the username to about 64 characters", which isn't done.
   - Fix: cut to 64 characters in the event, and `DefaultBodyLimit::max(4096)` on `/login` and `/auth/verify-2fa`.
2. **Low: `ADMIN_DEVICE_API=off` without `DEVICE_BIND_ADDR` serves the device API nowhere.**
   - Every phone is cut off, and only the exposure line hints at it (`src/main.rs:1046`, `NetConfig::exposure_line`).
   - Fix: refuse to start, as for equal binds, or at least log an error.
3. **Low: the SSE stream shares its in-flight slots with downloads, checked before the stream cap.**
   - Where: `src/limits.rs:51-63, 157-165`. The SSE stream and downloads share 4 slots, checked in
     `require_device_token` before `CommandStreams::open` can replace the oldest stream.
   - Scenario: a phone changes networks twice while two downloads run (an APK, music files). Its two half-open
     streams stay counted until TCP gives up, about 15 min. The new stream gets 429 with `Retry-After: 30` instead of
     closing a stale one, and instant commands wait.
   - Fix: give SSE its own class, bounded by the stream cap (which closes the oldest); downloads keep their 4.
   - Test: 2 stale streams and 2 downloads; a new stream is admitted and the oldest is closed.
4. **Low: the Tailscale key docs contradict the user's M12 decision.**
   - Both still recommend a *reusable* key:
     - DEPLOY's "Tailscale keys for the phones" (`DEPLOY.md:158-164`);
     - Settings > Provisioning (`templates/provisioning_settings.html:12, 29`).
   - The user's M12 decision (security review, 2026-10-10) is one-time tagged keys, with the launcher deleting the key
     after the first connect.
   - Fix: align the wording now (tagged, pre-approved, one-time, and what that means for the single key in
     Provisioning settings), or record that the decision changed.
5. **Low: DEPLOY doesn't say what the gate can't see.**
   - `TRUSTED_PROXIES` (`DEPLOY.md:30`) says "IPs or CIDRs". Any client inside a trusted range can name a tailnet
     address in `X-Forwarded-For`, or send none and pass as a "local tool". DEPLOY should say: exact proxy addresses
     only, never a subnet with other hosts in it.
   - A TCP-level forwarder on the box sends no header, so every client it forwards is loopback and passes. Examples:
     socat, `ssh -L`, `tailscale serve --tls-terminated-tcp` / `funnel --tcp`, Caddy layer4. DEPLOY should say: never
     point one at 3100.
6. **Low: `.env.example` is stale.**
   - It has none of design 22's settings: `DEVICE_BIND_ADDR`, `ADMIN_DEVICE_API`, `ADMIN_PUBLIC`, `TRUSTED_PROXIES`,
     `ADMIN_TAILSCALE_USERS`, `SESSION_INACTIVITY_DAYS`, `ADMIN_PASSWORD_FILE`.
   - Next to `INSECURE_COOKIES` it still says HTTPS "via Tailscale Funnel or similar". That setting now also renames
     the cookie to `handy`.
7. **Low: the container's restore, and the new plain copies of the database.**
   - `src/startup.rs:98-112`: the restore extracts `kidphone.db` with no size limit. A crafted zip, uploaded from a
     stolen session, fills the data volume. M4 asked for a cap in `actions.sh`; add the same here (say 2 GB, or the
     free space).
   - The `backups/prerestore-*/` directories are never pruned. Each restore in the container adds a full copy.
   - `pre-migrate-*.db` and `prerestore-*/` are unzipped copies. They hold `tower_sessions` (live session ids) and the
     TOTP secret, written with the service's umask. Whatever the security wave does for M2 (umask, `chmod`, stripping
     sessions, the USB mirror) must cover them too.
8. **Low: on GHCR, `latest` moves to whatever server release ran last.**
   - Where: `.github/workflows/server-release.yml:154, 230-235`. Every `server-v*` run tags `latest`, including a
     patch on an older line.
   - A `workflow_dispatch` on a tag ref is a full release (`RELEASE` comes from the ref), not the dry run the header
     describes.
   - Fix:
     - Tag `latest` only when VERSION is the highest `server-v*` tag.
     - Derive `RELEASE` from `github.event_name == 'push'` as well.
9. **Low: two PWA nits.**
   - "Dismiss" on the 2FA banner always goes to `/devices` (`src/handlers/auth.rs:721-727`), from Account too. Return
     to the page it was sent from.
   - A parent with a live session isn't told that wrong 2FA codes keep the account locked. The banner only comes after
     a complete sign-in, which the lock prevents. Show the lock and the failure count on Devices and Account for live
     sessions too, so the password can be changed in time.

## Simplification

1. **`PasswordGate`**: an `AtomicUsize`, a `OnceLock<Semaphore>` and an `Admitted` guard. Two `Arc<Semaphore>`s
   (admission of 5 with `try_acquire_owned`, one run slot) do the same, and moving their owned permits into the
   closure is M1's fix.
2. **The `OnceLock<Arc<Semaphore>>` pattern** ("a `Semaphore` has no `Default`") is used three times:
   - `Limits::global`;
   - `Enrollment::gate`;
   - `PasswordGate::running`.

   A hand-written `Default` with plain fields is shorter.
3. **Duplicate helpers**:
   - two copies of `evict_oldest` (`src/limits.rs:452`, `src/audit.rs:112`);
   - two ways of cutting a path to 100 characters (`gate::truncate` and `chars().take(100)` in `security.rs` and
     `admin_auth.rs`).
4. **`Limits::last_access` keeps the public IPs twice**: a `VecDeque` and a `Vec` copy. Keep the deque and build the
   list on read.
5. **`AdminUser.failed_login_attempts` and `locked_until` are never read** (clippy's dead-code warning on
   `src/models.rs:11`), while `verify_2fa` queries the counter again. Read it from the struct, or drop the fields.
6. **`build_router` only calls `build_admin_router`.** The tests can call the latter.
7. **`ExposureWatch`'s second signal could go until S5 shows the warning.**
   - The signal: two phones behind one private address, in a map cleared at 64.
   - Nothing reads the warning before S5.
   - The untrusted-`X-Forwarded-For` signal already covers Docker and a missing proxy entry.

## Deviations: verdicts

From "Server implementation status (S0-S4, D1)" in design 22.

- **S0's `device_routes(state)` takes the state.** Agree.
- **`TRUSTED_PROXIES`, the walk and `ADMIN_TAILSCALE_USERS` came in S1.** Agree: the gate means nothing without them.
- **The Access card came in S2/S3.** Agree.
- **The gate covers the whole admin listener with an empty 403** (`/static`, unknown paths and the old device URLs).
  Agree.
- **`ADMIN_TAILSCALE_USERS` skips the login check on `/api/devices/*` but keeps the IP rule, and refuses loopback
  without a login.** Agree: it fails closed, and `/healthz` is exempt.
- **The Funnel refusal applies only while `ADMIN_PUBLIC` is off.** Agree.
- **The 512 guard and the 30 s timeout answer 503 with `Retry-After`.** Agree: for R5 an overload should be a transport
  failure, and a 503 is one.
- **Enroll's cap of 8 starts after the body is read.** Disagree: see M2.
- **A value that isn't code-shaped is refused at once and counts for nothing.** Agree.
- **Typed codes are shown as `ABCD-EFGH`.** Agree.
- **The typed pause counts UTC clock hours.** Agree. Across an hour boundary that is at most 2 × 19 guesses at one
  15-minute code.
- **"New code" still works during a pause** (the code page says it works after the pause). Agree.
- **A new device has no code** until "New code" or the QR page. Agree: the card says how to get one.
- **"One private IP": startup warns about a wide bind, run time logs an untrusted XFF or two phones on one private
  address once.** Agree for now (see Simplification 7). With H1's fix, the untrusted-forward case is also refused on
  the admin listener.
- **Throttled events: the first row is written at once, the count every 30 s.** Agree with the mechanism. The missing
  global budget is M3.
- **Login bans by IPv4 address or IPv6 /64; unban is a form field.** Agree.
- **A correct password on a locked account says so** (`login_locked`). Agree.
- **Lock k lasts 15 min × 2^(k-1), at most 24 h; a replayed code counts as a failure.** Agree. The bound itself is
  H2's subject.
- **The banner stays until dismissed or the password is changed.** Agree (see Low 9).
- **CSRF refuses `same-site`, and accepts `X-Forwarded-Host` from a trusted peer.** Agree.
- **`no-store` on the code page; the provision GET makes no code; without a checksum the POST makes none either.**
  Agree.
- **D1's restore: the prerestore copy keeps `-wal`/`-shm`, and the container exits 3.** Agree.
- **A failed pre-migration backup stops the start.** Agree. Note that on the Pi `update.sh` doesn't roll back a binary
  that won't start, so a full disk after an auto-update leaves the server down until someone frees space. The error
  line names the cause; that is enough.
- **The container doesn't register the root-helper routes.** Agree.
- **Logging defaults to `info`.** Agree: no request path logs at `info`.
- **CI's `server-image` job also runs on `.dockerignore` and the smoke script; `workflow_dispatch` is a dry run.**
  Agree, except a dispatch on a tag ref (Low 8).

## Security review items: verified

- **M1** (the parent can be locked out for good): fixed for its stated case, wrong passwords.
  - Wrong passwords never lock the account (code, and `wrong_passwords_never_lock_the_account`).
  - TOTP failures lock with doubling, and a lock running out resets nothing (a test).
  - Caveats: H2 (the bound fails under concurrency), M4 (the recovery CLI's race) and Low 9 (a live session isn't
    told).
  - Someone who knows the password can keep new sign-ins locked out. The design accepts that, with the CLI as the way
    out.
- **M3, part**: the `session_epoch` part is verified.
  - It is checked on every request (`load_active_admin`) and bumped by a password change, a 2FA reset (others) and
    "Sign out everywhere" (all, this one too). Tests cover all three.
  - Sessions from before the update carry no epoch and are dead.
  - Declined or open:
    - step-up (U2);
    - a password for the 2FA reset (M5);
    - an absolute session lifetime (not in design 22).
- **M7, part**: the device-side caps are verified.
  - Per token: a bucket of 600 at 2/s, and in-flight caps (8 general, 4 long, `policy`/`status`/`command-result`
    always admitted).
  - Per IP: a failure bucket (30 per 10 min).
  - Body limits of 512 KiB and 4 KiB.
  - The SSE cap of 2 per phone, closing the oldest.
  - `security_events` pruned after 180 days, and floods throttled (but see M3).
  - Open:
    - the username cap and a `/login` body limit (Low 1);
    - field caps on `installed_apps_json` and the rest;
    - paging on `/security`.
- **M12, part**: verified.
  - A separate phone listener (S1).
  - Hashed one-shot codes, used in one conditional `UPDATE ... RETURNING` (a race test with 8 phones).
  - No code on a GET.
  - Re-enrolling is an explicit admin POST ("New code", or Provision), logged as `enroll_code_created` and
    `device_enrolled` with the IP.
  - Revoke.
  - The Tailscale key part is open (Low 4).
- **L1**: X-Forwarded-For is now believed only from trusted peers, walked from the right, but the malformed fallback
  re-opens it (H1).
  - The unban button posts a form field: no path built from an IP.
  - Ban keys come from a parsed `IpAddr`.
- **L2**: fixed.
  - Replay: `totp_last_step`, stored in the same `UPDATE` that accepts the code, at setup too.
  - A correct password no longer resets the counter.
  - Recovery codes are replaced by the CLI (a decision).
- **L3**: fixed. `cycle_id()` after the password and after the TOTP.
- **L4, phase 1**: fixed.
  - The headers.
  - CSP with `'unsafe-inline'` until S7.
  - `__Host-handy` (Secure, HttpOnly, Strict, `Path=/`).
  - CSRF middleware on top of SameSite.
- **L11**: `spawn_blocking` behind a gate and a dummy verify with the same parameters, but the gate leaks on a
  cancelled request (M1).

## Checked and fine

- **The phone listener** serves `device_routes()`, `/healthz` and an empty 404, nothing else.
  - No session layer, no `/static`, no cookie. A test walks every admin path and every route in `device_routes.rs`.
- **No admin route sits under `/api/devices/`** (the no-literal test and the route list). So the gate's and CSRF's
  prefix exemptions cover only the bearer-token API.
  - axum doesn't normalise `..` or percent-encoding, so `/api/devices/../devices` is a 404.
  - A proxy that normalises makes it `/devices`, which is gated.
- **Addresses**:
  - IPv4-mapped peers and XFF entries are made canonical.
  - A bad `TRUSTED_PROXIES` entry is dropped, never widened.
  - IPv6 limit keys are /64s.
  - IPv6 zone ids and link-local peers are refused.
  - The Host header decides nothing except CSRF's Origin match, which can only refuse.
- **Tailscale headers**:
  - `Tailscale-Funnel-Request` is refused while the gate is on.
  - `Tailscale-User-Login` counts only from a tailnet IP, and DEPLOY's Caddy sites strip it.
- **Defaults**:
  - The Pi's `.env` binds `127.0.0.1:3100` (`deploy/install.sh:217`). So do the image's `ENV` and DEPLOY's host-network
    compose.
  - The bridge layout fails closed: the gateway is neither loopback nor tailnet.
  - The emulator doc covers the 403 for LAN clients.
- **Sessions and sign-in**:
  - The cookie flags, the plain `handy` name with `INSECURE_COOKIES`, and `SESSION_INACTIVITY_DAYS` (1-365).
  - The pending state lasts 5 minutes.
  - An unknown username verifies a dummy hash with the same parameters.
  - A correct password on a locked account isn't counted toward the ban.
- **TOTP replay**: one atomic `UPDATE` per accepted code; setup uses the same rule; `reset_totp` clears the last step
  along with the secret.
- **CSRF** covers every unsafe method on the admin listener, before the session is read.
  - The only exemptions are the device API (a bearer token, no cookie) and `/healthz`.
  - No GET changes state except the 2FA setup form writing a secret while 2FA isn't set up, behind SameSite=Strict.
- **Enrollment**:
  - The SHA-256 of the normalised code, under a unique partial index.
  - 0052 clears the plaintext.
  - Typed failures count only while a typed code is live; QR-shaped codes are always checked.
  - Re-enrolling retires the old token and closes its streams.
  - Revoke clears `token_hash` and refreshes the index *before* closing the streams, so no new stream slips in.
- **The token index** is rebuilt at start and after enroll, revoke and delete, with reloads serialized. Delete forgets
  the phone's limits.
- **Limits**:
  - A valid token never touches the IP bucket (a test).
  - Slots are held until the response body ends (a test with a stream).
  - `Cache-Control`, `nosniff` and the sandbox CSP are on device responses, tested on both listeners.
- **SSE**: a cap of 2 per phone, closing the oldest. Streams close on delete, revoke, re-enroll and shutdown (tests).
- **CSP phase 1 against the pages**:
  - Leaflet is local, and tiles come from `tile.openstreetmap.org`.
  - Every image and fetch is same-origin; the 2FA QR is a `data:` URL.
  - The service worker caches nothing.
  - Inline auto-save handlers and `scroll-restore.js` are allowed.
  - `form-action 'self'` holds, since every redirect is relative.
- **The PWA's scroll position**:
  - Revoke redirects to `#access`.
  - The provision POST returns to the same path, so `scroll-restore.js` keeps the position.
  - "New code" opens a page of its own.
  - All the new text is in English.
- **The Docker image**:
  - It runs as uid 65532 (`USER`, and checked by the smoke test).
  - distroless static and busybox are pinned by digest.
  - `data/` and `data/keys` (0700) are seeded as 65532; the binary and `static/` belong to root.
  - The read-only root works: SQLite's temp files go to `/tmp`, and every write is under `data/`.
  - `HEALTHCHECK` runs the binary (there is no shell), and the healthcheck maps `0.0.0.0` and `::`.
  - `selftest-tls` passes on any status; `.dockerignore` is an allow-list.
- **The pre-migration backup**:
  - It runs only with migrations pending on an existing database, before `migrate_legacy`.
  - It keeps 3, and no migration runs without it.
  - Tested from a real older database.
- **Migrations and compatibility**:
  - 0052 and 0053 only add columns and clear one, and tokens survive.
  - Old launchers keep syncing:
    - the policy JSON is unchanged in this series;
    - `ADMIN_DEVICE_API` is on by default;
    - `enroll` accepts any code string;
    - the body limits sit above the launcher's batches (5 crashes of 3,000 characters, 200 DNS events).
- **CI**:
  - Third-party actions are pinned by SHA, and nothing uses `pull_request_target`.
  - Write permissions exist only in the release's image and publish jobs, and the PR jobs get no secrets.
  - The image is pushed by digest, smoke-tested on both platforms, then tagged and attested; the release is published
    last, with `makeLatest: false`.
  - The `server-ci` gate includes `server-image`.
- **Test fixtures**: nothing matches a scanner's secret formats.
  - The Tailscale key is built from parts (`tskey-auth-TESTONLY-...`), plus a `tskey-auth-x`.
  - The TOTP secret is the well-known RFC/Google example, and the passwords are plain words.
  - A grep for AWS, GitHub, Slack, Google, Stripe and PEM private-key formats found nothing.
- **Not checked here**:
  - a real image build;
  - `docker image inspect` on an index digest under the containerd image store (`deploy/image-smoke.sh:53`).

  Run `server-release.yml`'s `workflow_dispatch` dry run on `main` before tagging 0.23.0.
