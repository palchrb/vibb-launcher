# QA 26c step 1 code review - the Spotify app, accounts, PKCE login and token cache (2026-10-13)

Scope: branch `music-3-spotify`, `2c95349..ec6e405` (one commit) under `server/`: `src/spotify.rs`,
`src/handlers/music_spotify.rs`, `src/music_secret.rs`, `src/music_net.rs`, `src/main.rs`, `src/admin_auth.rs`,
`templates/music_spotify.html`, `templates/music.html`, `migrations/0073_music_spotify.sql`, `testdata/spotify/`,
`src/tests/music_spotify.rs` (+ `tests/mod.rs`, `tests/music.rs`'s `CannedFetch::retry_after`), and the step 1 status
in design 26c. Read against design 26c (§0.3, §0.4, §1.1, §2, §3.6-§3.7, §7.1, §9.3, §10, §11 step 1, the design QA fix
round and the status's fourteen numbered deviations), root and `server/CLAUDE.md`, and the patterns it copies
(`storytel.rs`, `music_secret.rs`, `music_net`'s `without_url` and `.referer(false)`, `main::log_filter`, the gate
and `public_routes`, `admin_auth::{csrf_guard, admin_headers}`, the security events). Line numbers are `ec6e405`'s.

Build, here (Rust 1.99, `server/`): `cargo fmt --check` clean; `cargo clippy --all-targets` 12 warnings, all in
`models.rs` (9), `dns_engine.rs` (2), `handlers/devices.rs` (1) - none in a touched file; `cargo test` 693 passed, 0
failed (the time-dependent `parent_pin` test happened to pass this run). The 31 Spotify tests and
`seal_with_binds_the_associated_data` pass alone and in the suite; `policy_json_keys_snapshot`,
`music_library_snapshot`, `music_listing_snapshot` and `hardening_keys_snapshot` pass unchanged. No dependency added
(`Cargo.toml`/`Cargo.lock` untouched). Migration: 0073 is §3.7's block byte for byte (diffed), the next number after
`origin/main`'s 0072; applied with Python's SQLite 3.45 to a database at 0072 holding a phone: binding moves the
revision, a bound account can't be deleted and an unknown id can't be bound (FK with `foreign_keys(true)`), a deleted
id isn't reused (`AUTOINCREMENT`), a second app row fails its `CHECK`. Mutations (each reverted): consuming the pending
login on a missing cookie leaves every test green (Low 5); the log capture's first attempt misses lines in a parallel
run (Low 6).

Browser claims behind deviations 1-2, checked against the texts (MDN's sources and the rfc6265bis draft, fetched
here): a Strict cookie is withheld from "top-level navigations which are triggered from a cross-site document
context", and a redirect doesn't change the navigation's initiator, so §2.3's direct 303 from the callback to the
session page would arrive without the admin cookie; the done page's meta refresh is a new navigation from our own
document, so it carries it. MDN on `form-action`: "browser implementations of this aspect are inconsistent (e.g.,
Firefox 57 doesn't block the redirects whereas Chrome 63 does)" - widening it for the login form's 303 is needed in
Chrome.

Counts: no High, 1 Medium, 6 Low. The security core is right: no secret reaches a log, an event, a page, an error or
a `Debug` (every `tracing::` in the new code carries ids, codes and sqlx errors only; the error enum holds codes and
statuses), the sealed token is bound to its account, the verifier is unbiased RFC 7636, `state` is single use, an
unauthenticated caller can make the server send nothing, and the token cache, rotation and refusals do what §2.4 says.
The Medium is a login that can't work from a second address of the same server, with a misleading sentence.

## Medium

1. **Medium: a login started from another address of the server always ends "expired".**
   - Where: `src/handlers/music_spotify.rs:486-497` (the `spotify_login` cookie is host-only: no `Domain`),
     `:514-565` (the start uses the saved redirect URI whatever address the page was loaded from), `:638-647` (no
     cookie -> `Expired`), `src/spotify.rs:1008-1012` (the sentence).
   - §2.1 promises the opposite: "the authorize request always uses the saved one, so the parent can later open the
     PWA from another address without breaking logins". The cookie (QA L6, adopted as defence in depth) silently
     turned that into a hard rule: the cookie is set on the host the page came from, Spotify sends the browser back to
     the redirect URI's host, which never received it.
   - Failure path: the redirect saved as `https://pi.tail1234.ts.net/music/spotify/callback`; the parent later opens the
     PWA at another address that works for the session (Docker on the LAN at `http://pi.lan:8080` with
     `INSECURE_COOKIES`, a second https proxy name). "Log in with Spotify" -> Spotify's consent -> the callback on
     `pi.tail1234.ts.net` without the cookie -> "That login wasn't started in this browser, was already used, or took
     longer than 10 minutes - log in again." Logging in again repeats it forever; the done page then lands on a host
     where the parent may have no session either. Nothing tells the parent the address is the problem.
   - Required change: at `POST /music/spotify/login`, when the saved redirect is https and the request's host (the
     trusted proxy's `X-Forwarded-Host`, else `Host`; lower-cased, `:443` dropped - `suggested_redirect`'s rules) is
     known and isn't the redirect URI's host, don't start: a 400 by the button, "Spotify sends this login back to
     https://<host>: open this page there to log in", with the link `https://<host>/music/spotify#accounts`. The
     loopback redirect (the paste) is unaffected. Add "or was started at another address of this server" to the
     `expired` sentence. Tests: a start with another `Host` -> 400, nothing pending, no cookie; the redirect's host ->
     303 as now.

## Low

1. **Low: the paste uses up another admin's pending login before refusing it.**
   - Where: `src/handlers/music_spotify.rs:580-588`: `take_pending` removes the login, then the admin check returns
     `Expired`.
   - Deviation 3's binding is sound (the paste needs a session and passes the CSRF guard; another admin can't finish a
     login they didn't start), but the refusal destroys the other admin's login, unlike the callback's cookie check,
     which deviation 4 deliberately leaves in place. Only an admin can do it; it costs the other one a restart.
   - Fix: check the admin before taking it (`Accounts::take_pending_for(state, admin)`: find, compare, then remove).
     Test: after the refused paste, `someone-else`'s login still completes.

2. **Low: a rotated refresh token whose write failed is retried only at the next refresh.**
   - Where: `src/spotify.rs:1383-1395` returns the kept access token before looking at `slot.unsaved`; `:1476-1481`
     keeps it in memory.
   - §2.4 asks for exactly this, but nothing in step 1 (and only the hourly expiry later) triggers the next refresh; a
     restart in between loses the only copy, and if Spotify revoked the old token on rotation the account comes back
     `invalid_grant` -> `needs_login`: the parent has to log in again (recoverable, not bricked).
   - Fix: when `unsaved` holds a token of the row's generation, retry the write on every `access_token` call, the cached
     path included, and log the failure at `error` (still the account id only). Test: the trigger dropped, a cached
     call writes it without a token request.

3. **Low: a 401's forced refresh isn't tied to the token that got the 401.**
   - Where: `src/spotify.rs:1604` (`min_left = Duration::MAX`) and `:1615-1619`.
   - Two reads that get a 401 with the same token (2a's sweep and 2b's add check, or step 3's phones) each force a
     refresh: the second waits for the first under the lock, then refreshes again because no token satisfies `MAX`.
     With rotation that is a second rotation and a second write for nothing. 26b's code QA settled the same pattern on
     the phone (a 401 whose token another call has replaced takes the new one).
   - Fix: pass the rejected token (or its generation and expiry) into the forced call; under the lock, refresh only if
     the kept token is still that one. Test: two concurrent reads, both 401 once -> one token request.

4. **Low: an absurd `expires_in` panics the request.**
   - Where: `src/spotify.rs:688-692` takes any `u64 > 0`; `:1343` and `:1495-1500` add it to a `tokio::time::Instant`,
     whose `+` panics on overflow.
   - Only Spotify (or the loopback fake) can send it, so this is hygiene; a panic mid-refresh drops the request.
   - Fix: accept `1..=86_400` (else `bad_answer`), or clamp. A row in `token_answers_are_read`.

5. **Low: three claims the tests don't pin.**
   - `the_callback_needs_its_state_and_cookie` (`src/tests/music_spotify.rs:671-676`) says "a missing cookie didn't use
     the login up: the right browser still completes it...", then advances past `PENDING_TTL` and checks only the
     expiry. Mutation: taking the pending login out on a missing cookie leaves the suite green. Complete it with the
     right cookie before the 10 minutes (and the TTL test on a second login).
   - `/music/spotify/done` is pinned only for `login=ok` (`:200-216`); add a junk value (`login=%22%3E%3Cscript%3E`)
     -> `content="0;url=/music/spotify?login=expired#accounts"` - the code is right (a known-code allowlist), the pin
     keeps it so.
   - Low 1's other-admin case (above).

6. **Low: the log capture's retry is the normal path, and its probe accepts a partial capture.**
   - Where: `src/tests/music_spotify.rs:1209-1219`, the probe at `:1299-1306`.
   - Measured here (a counter on the miss, reverted): in `cargo test tests::music` (148 tests in parallel) the first
     attempt missed lines in 4 of 5 runs; the missing ones were the earliest (`spotify: a login started`, `an account
     logged in`), the later ones were seen. Calling `tracing::callsite::rebuild_interest_cache()` after `set_default`
     didn't change it. So the race is real (not hiding a product bug: every new log line carries ids and codes by
     construction, and none takes a secret field), and the retry is what makes the test pass in a parallel run. But the
     probe checks two callsites only: an attempt that captured those two and missed another (`a refresh failed`, `a
     login failed`) passes without having looked at it.
   - Fix: the probe requires every message this run must produce (the eight a full capture holds: `a login started`
     x3, `an account logged in` x2, `a login failed`, `access token refreshed`, `a refresh failed`), so a partial
     capture retries rather than passes; say in the comment that the first attempt usually misses in a parallel run.

## Confirmed

- **PKCE**: 64 characters of the 66-character alphabet, unbiased (bytes `< 198` taken `% 66`), `OsRng`; `s256` =
  base64url without padding of SHA-256 (RFC 7636 appendix B's vector asserted); `state` 128 bits hex; the cookie its
  hex SHA-256. The authorize query is §2.3's, in order, `form_urlencoded`, without `user-read-playback-state`.
- **Pending logins**: memory only, at most 8 (oldest dropped), 10 minutes on the test-movable clock, taken out on use
  (a replay is `expired`), with the admin, client ID and redirect URI of the start; an app changed since is `expired`
  (tested). A server restart loses them (by design).
- **The callback and login CSRF**: `public_routes` but behind the outer `gate::admin_gate`, the CSRF guard (GET is
  exempt, correctly) and `admin_headers` (`Referrer-Policy: same-origin`, so the code's URL is never a `Referer`). An
  unauthenticated caller can't create a `state` (only the session- and CSRF-guarded start does), so it can't bind its
  own Spotify account to the server; without the matching cookie nothing is asked of Spotify (tested: no request).
  The remaining path needs a leaked authorize URL (its `state`) and the parent's browser on the tailnet - the cookie's
  documented limit, not a finding. The code is checked (visible ASCII, 4096) and never logged: the gate and audit log
  paths only (`audit::clip(path)`, `request.uri().path()` everywhere).
- **The done page**: no session, no secret, the outcome from a fixed allowlist else `expired`, a fixed same-origin
  target - no open redirect, no reflection; `no-store`.
- **CSP (deviation 2)**: `page_csp` widens only `form-action`, only in `render()` (the Spotify page and its 400s), to
  `accounts_origin()`, which is Spotify's origin or the loopback override's; `admin_headers` keeps a handler's CSP and
  no other handler sets one (grep); `/music` keeps `ADMIN_CSP` (tested). The `replace` would silently no-op if
  `ADMIN_CSP`'s text changed - the test's `form-action 'self' https://accounts.spotify.com;` catches that.
- **Base overrides**: `loopback_base` takes `http`, host exactly `127.0.0.1` or `[::1]` after the URL parser's
  normalisation (so `127.1`/`0x7f000001` are 127.0.0.1 and `[0:0:0:0:0:0:0:1]` is `[::1]` - still loopback), a port, no
  userinfo/query/fragment/path; DNS names, `localhost`, `127.0.0.2`, `::ffff:127.0.0.1`, https and 10.0.2.2 are ignored
  with one warning each. `Reach::Any` per base, only with its own override; the URL is always built on that base
  (tested over CannedFetch: `Public` without, `Any` with).
- **Sealing**: `seal_with`/`open_with` with `spotify-v1:<fingerprint>:<spotify user>`; Storytel's `seal`/`open` are the
  same bytes as before (`storytel-v1:<fp>` through the new functions); another key, another user's AAD and a row moved
  to another user all fail to `key` (tested). A rotated token is sealed with the current key and fingerprint.
- **Tokens**: one `tokio::sync::Mutex` per account around the slot, held across the refresh only (never by the page,
  never a std mutex across an await); the kept token needs the row's generation and `min_left`; expiry on the monotonic
  clock (no wall-clock skew), `expires_in - 60 s`; `needs_login` -> `reauthorize` with no request; a rotated token
  written (`WHERE id AND generation`, so a concurrent new login or a Remove wins) before the access token is kept;
  `invalid_grant` marks the row and records `spotify_login_needed` once, `invalid_client` -> `app_refused` without
  `needs_login`, 429 no state change, 5xx/network `http_<n>`/`network`. The exchange's token is kept with `try_lock`
  (never waits on a refresh).
- **Reads**: `api_get` refuses during the quota pause without a request, a 401 refreshes and retries once, a 429 waits
  a `Retry-After` of at most 5 s once (any number parses as `u64`; a date or junk is `None`; nothing longer is ever
  slept), `QUOTA_EXCEEDED` (top level or inside `error`) sets 60 minutes and the token endpoint goes on; 404
  `not_found`; truncated `too_big` (64 KB `Kind::Token`, 20 s). `Retry-After` is cut to 500 characters in `HttpSource`.
- **The login's budget**: exchange + `/me` under one 45 s timeout, the gate's wait included; outcomes map as §2.3
  (`/me` 403 `not_registered`, 429/5xx `unreachable`, no refresh token `refused`), nothing stored on any failure
  (tested), one `spotify_login_failed` with the code only; a cancel records nothing. `/me`'s `id` 1-128 visible ASCII,
  the name without control characters, 60 characters, else the id.
- **The page**: every save 303s to its card's anchor; a refusal re-renders with the error by its field and
  `autofocus`; no fixed width (`.sp-uri`/`code` break anywhere, `input` `max-width: 100%`) - at 360 px by inspection
  only (no browser here). Clear, a new client ID and Remove each need their box (a 400 by it), unbind phones first in
  one transaction (the revision moves - tested), forget the cached tokens, and record their event; the client ID is
  shown whole (public) and by its last four.
- **Hygiene**: `cfg_attr(not(test), allow(dead_code))` only on the link grammar (2a's); file placement as
  `server/CLAUDE.md`'s pattern (a client module, a handler module, a test module, fixtures in `testdata/<source>/`); the
  repo has no per-file licence headers; vibb's grammar credited (MIT) in `spotify.rs`'s header; fixtures are
  `FAKE-ACCESS-<n>`/`FAKE-REFRESH-<n>`/`fakeparent`/`fakekid` only.
- **§10 coverage for step 1**: every server-pure and routes row that belongs to step 1 has a test (the listing
  parsers, `hosts_match_the_contract` and the token endpoint are 2a's and step 3's), and the fix round's step 1 rows
  (`Reach::Any` only with the override, the callback's 303 and cookie, the https-only suggestion, the quota pause, the
  scope string) are there.

## The fourteen deviations

(The status numbers fourteen; the log capture's retry is judged in Low 6.)

1. **The callback's 303 to `/music/spotify/done`**: accept - the Strict-cookie reasoning holds against rfc6265bis (see
   the top), and the code still never sits on a page's URL. Medium 1 is the cookie's other side.
2. **The page's own CSP**: accept; verified (MDN: Chrome enforces `form-action` on redirects), scoped as claimed.
   `admin_auth.rs` outside the file list is the right place.
3. **The paste bound to the admin**: accept; fix the order (Low 1).
4. **`off`, `error`, and `expired` for a missing cookie (the login left in place)**: accept; the "left in place" needs
   its test (Low 5) and `expired`'s sentence the other-address case (Medium 1).
5. **`LOGIN_BUDGET` 45 s**: accept (26b's probe budget; the callback's request waits at most that).
6. **`SourceResponse.retry_after`**: accept (needed; capped).
7. **`api_get` refuses during the quota pause**: accept; the quota is the developer account's, so one global pause is
   right.
8. **A token-endpoint 429 is `rate_limited`**: accept; step 3 maps it to `busy`.
9. **`refresh_error` codes**: accept.
10. **The exchange's access token kept; a new login clears `refresh_ok_at`**: accept.
11. **0073 is §3.7 whole**: accept; 2a/2b now build on frozen columns (`spotify_reader` without an FK - 2a must treat a
    removed reader's id as none, which `AUTOINCREMENT` makes safe).
12. **The grammar's edges**: accept (each refuses or canonicalises; nothing reaches a request in step 1).
13. **The page's details**: accept.
14. **Events**: accept (`spotify_app_saved` holds only the last four and the public redirect URI).

## What the fix round must do

- [ ] Medium 1: refuse a login start whose request host isn't the https redirect URI's host (400 by the button, a link
      to the right address); widen the `expired` sentence; two route tests.
- [ ] Low 1: check the admin before taking a pending login in the paste; test that the other admin's login survives.
- [ ] Low 2: retry an unsaved rotated token's write on every `access_token` call; log the failure at `error`; test.
- [ ] Low 3: tie a 401's forced refresh to the rejected token; test two concurrent 401s -> one refresh.
- [ ] Low 4: bound `expires_in` (`1..=86_400`, else `bad_answer`); a test row.
- [ ] Low 5: pin "left in place" (complete with the right cookie before the TTL) and the done page's junk `login`.
- [ ] Low 6: the capture probe requires all eight expected messages; note the parallel-run miss in the comment.
- [ ] Re-run `cargo fmt --check`, `cargo clippy --all-targets` (12, none new), `cargo test`.

## Notes for steps 2a, 2b and 3 (not findings)

- Step 3's token route calls `access_token` under the account's lock, which a refresh holds while it waits for the
  gate (a feed fetch can hold it for 120 s): wrap the route's call in its own timeout (the phone's retry beats a hung
  request), as the login's 45 s does.
- When nothing is configured but accounts remain (an environment client ID removed between restarts), the accounts
  card is hidden and only Clear removes them; 2b's binding select should still list them or the page should show the
  card whenever accounts exist.
- `server/CLAUDE.md`'s Spotify section (step 7) and `DEPLOY.md` (the main session) should say that a login must be
  started from the redirect URI's address (Medium 1's rule).

## Not worth fixing

- `/me`'s display name keeps Unicode format characters (bidi overrides): every sink is an escaped template or an event
  detail; cosmetic at most.
- A `/me` 429 reads "Spotify couldn't be reached": a retry is the right advice either way.
- `?login=ok` names the newest account, so a stale `?login=ok` URL opened later names whoever logged in last.
- `loopback_base` refuses `http://127.0.0.1:80` (the parser drops the default port): the fake never runs on 80.
- Two logins started in two tabs of one browser share the cookie, so the first one ends `expired`; the page's advice
  is one login at a time anyway (a private window has its own jar).
