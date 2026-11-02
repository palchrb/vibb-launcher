# Code QA - `passkeys-3` @ 2177a962, design 74 step 3 (the app lock): server enforcement and routes

Scope:
- `security::admin_or_why`'s `Why::Locked` and `lock::check`.
- `lock::EXEMPT` and the router walk.
- `next` (`lock::local_next`, `/lock`, Sign Out -> `/login?next=`, the passkey sign-in).
- `POST /lock/options` and `POST /lock`, `POST /account/lock`.
- The `__Host-handy-lock` cookie and `carry`.
- The server's `used_at`, and losing the last passkey.

The client side and the idle clock are the other reviewer's. I reviewed by reading the diff and did not run cargo.

**Verdict: mergeable after small fixes - 0 High, 0 Medium, 2 Low, 4 Nit.** The enforcement is server-side and fails
closed:
- Every request through `require_full_auth`, `require_session` or `/api/session` goes through `lock::check` after
  the sign-in checks.
- An unreadable or unknown lock time reads as 1 minute.
- A lock-on session with no `used_at` is locked.
- A request with no `MatchedPath` is never exempt.
- `/api/session` passes default `Facts` (never exempt, never a use), so it answers 423.
- A locked navigation answers 303 `/lock?next=` and carries `next` only for GET/HEAD. Anything else answers 423 with
  `no-store`.

I found no route that reads a session without passing the check, no open redirect, and no way to use a passkey proof
for an unlock or the other way round.

## Low

### L1 - A new sign-in in a locked session keeps the old lock, which may be impossible to open
`src/handlers/auth.rs` (`login` cycles the id but keeps the data; `verify_2fa`), `src/handlers/passkeys.rs` `login`,
`src/lock.rs` `carry`.
- What happens: a sign-in that completes in an existing session keeps all of its data. `carry` only *sets* the lock,
  and only when the lock cookie is present and the parent has a passkey here. Otherwise the old `lock_minutes` and
  the old `used_at` stay.
- Scenario:
  1. Mamma's PWA session on the family tablet is locked.
  2. Pappa opens `/login` in it (the address, or a bookmark) and signs in with his password and code.
  3. `verify_2fa` makes the session his, but `lock_minutes`/`used_at` are still Mamma's. The next request is due:
     `/lock`.
  4. The signed-in parent is now Pappa, and he has no passkey here, so the lock screen says "no passkey for this
     address". He can only Sign Out and sign in again; the second time it works, because Sign Out flushes the session.
  5. The same happens to the same parent signing in by password while the lock cookie is gone.
- Impact: it can't be used to get past the lock (it needs a full sign-in) and it can't loop, but a fresh sign-in
  shouldn't be locked.
- Fix: at every sign-in completion (`verify_2fa`, the passkey `login`, the not-yet-ready `login` branch), call
  `lock::set(&session, 0)` before `carry`. Test: a locked session, then a password sign-in with no cookie -> not
  locked.

### L2 - The router walk can't see a route added outside `build_admin_router`'s own `.route(` calls, and probes POST-only exempt routes with a GET
`src/tests/app_lock.rs:799` (`every_route_is_locked_or_exempt`).
- What it does: it parses `main.rs`'s text for `.route(` inside `build_admin_router`. That covers the public,
  onboarding, lock, admin and host groups (host routes are merged in tests: `DEPLOY_KIND` defaults to systemd) and
  the JSON routes.
- Gap 1: a later `.merge(some_module::routes())`, `.nest(...)` or `.route_service(...)` would go unseen, and so would
  a session route added to `device_routes` (merged with `ADMIN_DEVICE_API`). The test would still pass.
- Gap 2: exempt routes are probed only by a GET. For `/lock/options` and `/logout` (POST-only) that GET is a 405,
  which passes as "not locked" whatever the middleware does. `past_its_time_the_app_is_locked` does cover their POSTs.
- Fix:
  - In the walk, assert that `build_admin_router`'s `.merge(`/`.nest` targets are exactly the known set
    (public/onboarding/lock/admin/host routes, `device_routes`, the `/static` service), and that every path in
    `device_routes.rs` starts with `/api/devices/`.
  - Probe each exempt route with the method it has (`/lock/options`' POST should be 200 or 404, never 423).

## Nit

- N1 `src/handlers/lock.rs` `unlock` - `app_unlock_failed`/`app_unlocked` store `ceremony.client` (the describe
  string) as `ip_address`, while the sign-in events store the limit key (`ceremony.client_key`). Neither is
  ban-counted, so this only reads differently in the Security Log. Use the same form as the sign-in events.
- N2 `src/main.rs` `/lock`'s 4 KiB limit - the largest assertion step 1 allows (RSA-4096, a 512-byte id; "fits
  4 KiB" in `the_bodies_keep_to_their_limits`) plus a `next` of up to 512 bytes, urlencoded (up to about 1.5 KB),
  can exceed it (413). Give `/lock` 6 KiB.
- N3 `src/lock.rs` `locked_response` - a locked form POST loses its body (accepted, D3.4) and also its page: the
  unlock lands on Devices. Carrying the `Referer`'s path (same origin, through `local_next`) would bring the parent
  back to the form.
- N4 Changing `PASSKEY_RP_ID` leaves lock-on sessions that can't be opened (no passkey for the new RP ID). Sign Out
  gets the parent out and nothing loops, because `carry` doesn't apply there. Worth a line in DEPLOY.md's
  `PASSKEY_RP_ID` row ("…and an app lock asks for a new sign-in").

For the client-side reviewer: older Safari sends no `Sec-Fetch-Mode`, so `Facts::navigation` falls back to `Accept`
containing `text/html`. A script's fetch that asks for HTML (if any script does) then gets 303 -> the `/lock` page's
HTML instead of 423. Check that no script swaps such an answer into the page.

## Checked and fine

- **The `next` rule** (`lock::local_next`, `next_is_a_local_path_only`):
  - What it requires: one leading `/`, never `//` or `/\`, no backslash anywhere, printable ASCII only (no space,
    tab, CR, LF, non-ASCII), at most 512 bytes, and never `/lock`, `/login`, `/logout`, `/auth/*`.
  - Encoded variants (`/%2F%2Fevil`, `/%5Cevil`) stay paths on this origin. `@` is harmless after a leading `/`.
  - It is checked at every use: `/lock`'s query, `locked_response`, `POST /lock`'s answer, the logout POST ->
    `/login?next=`, the login page and `POST /auth/passkey`.
  - The password sign-in ignores `next`. The unlock answers JSON `{next}` and the page navigates with it.
  - I found no GET route that acts in a way that matters (backup download, `?push=` read mark, the switch-off page's
    session-only secret), so a crafted `next` gains nothing.
- **`POST /lock/options` and `POST /lock`:**
  - The challenge is `Kind::Unlock`, bound to session, account, epoch, RP ID and route `/lock`.
  - `signed_in_assertion` requires the signed-in parent's own credential, with the same checks as the re-auth. The
    kinds keep unlock and re-auth challenges apart in both directions.
  - Failures write `app_unlock_failed` (and `passkey_cloned` for a counter) and count in `reauth_wrong` via
    `record_reauth`. The third ends every session (401 `signed_out`).
  - Success runs `cycle_id`, moves `used_at`, and leaves `SIGNED_IN_AT_KEY` untouched (D3.7). `app_unlocked` is merged
    per parent and hour by one `UPDATE`.
  - The lock routes' own `require_full_auth` keeps them signed-in-only, `EXEMPT` keeps them reachable while locked,
    and CSRF covers both POSTs.
- **`POST /account/lock`:**
  - It needs a passkey for this RP ID and a non-empty assertion: `Proof::Passkey` at `/account/lock` (in
    `reauth_route`), with `NeedsPasskey` handled. Off and changes need it too.
  - A bad assertion counts. It logs `app_lock_set` "5 min" / "Off" and redirects (PRG) to `#app-lock`.
- **The cookie:** `__Host-handy-lock=<minutes>; Path=/; Max-Age=34560000; HttpOnly; SameSite=Strict; Secure`, with no
  Domain (`handy-lock` without Secure under `INSECURE_COOKIES`). Only one of the lock's times is read. Off writes
  `Max-Age=0`.
- **`carry`** runs at `verify_2fa` and the passkey sign-in, only with `has_here` (a passkey for this RP ID).
  - Losing the last passkey here (`passkeys::remove` -> `off_without_passkey`) turns this session's lock off and
    deletes the cookie. The removal's epoch ended the other sessions.
  - A Reset or `reset-2fa` ends the target's sessions and deletes their passkeys, so a later sign-in can't carry a lock
    it can't open. Nothing loops.
- **`used_at` on the server:**
  - It moves only for a user-activated navigation (`Sec-Fetch-Mode: navigate` plus `Sec-Fetch-User: ?1`), which a
    script can't forge, or an unsafe method. It is written only once it has moved 30 s, except the beacon
    (`/api/used`, not exempt, 423 when locked), which always writes.
  - Due at `used_at + minutes*60 + 60`. Exempt routes are neither checked nor counted.
  - The only scripted POSTs are user-driven (`calendar-preview.js`, `passkey.js`) or exempt (the service worker's
    receipt), so nothing keeps a session "used" by itself.
  - Of the 30-s skip and the beacon's minute, the page still locks first.
- **What a locked session can still reach:**
  - The admin side has no SSE or long poll; the only stream is the device API's. So nothing opened before the lock
    keeps serving after it.
  - JSON, images and downloads behind the session are 423.
  - The lock page is `no-store` (`["lock", ..]`) and shows nothing private.
  - A `?push=` receipt is kept in `next` and handled after the unlock (D3.6).
- **i18n:** 2094 keys in each file, identical sets.
- **Tests:** they cover D3.1–D3.5 and D3.7 and §7 step 3's server list: past its time, only a use moves it, the
  minute's slack, the parent's own passkey, three bad unlocks, the cookie with a sign-in that can open it, the last
  passkey turning it off, and the router walk. Missing: L1's case.

---

## Fix round (branch `passkeys-3`)

| # | State | What |
|---|---|---|
| L1 | fixed | `lock::carry` clears the session's lock first at every sign-in completion (`verify_2fa`, the passkey sign-in); the not-ready password sign-in clears it too. Test `a_new_sign_in_in_a_locked_session_starts_unlocked`. |
| L2 | fixed | The router walk pins the router's `merge`/`nest_service` targets, checks every `device_routes.rs` path is `/api/devices/*`, and probes each exempt route by its own methods (Sign Out and the code step with a locked session of their own; `/lock/options` must be 200; the main session still locked at the end). |
| N1 | fixed | `app_unlocked`/`app_unlock_failed` (and an unlock's `passkey_cloned`) store the limit key, as the sign-in events. |
| N2 | fixed | `/lock` takes 6 KiB (`LOCK_FORM_LIMIT`). |
| N3 | fixed | A locked form POST's `next` is its `Referer`'s path and query when it names this host (`Host` or `X-Forwarded-Host`), through `local_next`. Test `a_locked_form_comes_back_to_its_page`. |
| N4 | fixed | DEPLOY.md's `PASSKEY_RP_ID` row: changing it makes a lock-on browser ask for a new sign-in. |
| old Safari | checked | Only calendar-preview.js swaps fetched HTML in (a POST with `Accept: */*`, never a navigation by `Facts`); it now also refuses a redirected answer. music-cards.js asks JSON. |
