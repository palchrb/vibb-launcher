# Code QA (security) part B - `passkeys-1` @ 5cccaeaa, design 74 step 1: the web flows

Scope: `handlers/passkeys.rs`, `admin_auth` (`Proof`, `reauth`, `reauth_and_hash`, `reset_2fa`), the passkey sign-in's
completion, `parents` (Reset/Remove deleting passkeys), `AdminUser::ready` / `ready_sql!()`, `security.rs`
(`BAN_COUNTED`, `session_check`, `require_full_auth`), retention, the Security Log filter, `parent_events`
PasskeyAdded, the templates, `static/passkey.js`, i18n, style.css, the docs. `webauthn.rs` and the verifier half of
`passkeys.rs` belong to part A.

**Verdict: fix round needed - 0 High, 3 Medium, 7 Low, 7 Nit.** The security core around the verifier holds. Every
POST goes through `csrf_guard`. Re-auth challenges are bound to session, account, epoch, RP ID, kind and the exact
route, and each is used once, so I found no re-auth bypass and no way to replay a proof on another route. The
sign-in cycles the session id and stores the account, its epoch and `SIGNED_IN_AT_KEY`, and it removes the pending
keys. The readiness predicate is used everywhere it should be (I found nothing still on the old condition). Nobody
can remove or use another parent's passkey. All three ways back (Reset, soft Remove, the shell) delete the passkeys
in their transaction. The Mediums: one feature is broken (Add Passkey's "Confirm with passkey"), and the ban
counting has two problems.

## Medium

### M1 - Add Passkey's "Confirm with passkey" always fails (route mismatch); untested
`static/passkey.js:232` (`reauth` posts `route: routeOf(win, form)`), `templates/account.html` add form
`action="/account/passkeys#add-passkey"`, `src/passkeys.rs:248` `reauth_route`, `src/handlers/passkeys.rs:411`.
- Scenario: a parent with a passkey on this host opens "Add Passkey…" to add a security key or a second device
  and taps "Confirm with passkey". `routeOf` gives `/account/passkeys`, which `reauth_route` refuses on purpose (the
  unit test `the_reauth_routes` lists it as bad). The answer is 400 "The passkey didn't work. Nothing was changed."
  Even with a challenge, `add_options` takes the proof at `ceremony.route` = `/account/passkeys/options`, so the
  route binding can't match. The password still works, but D1.2 and §6.1 ("the re-auth: password field and/or
  'Bekreft med passkey'") are broken, and so is the docs' claim ("a passkey's add and removal" take the passkey
  re-auth).
- Fix: for a `[data-passkey-add]` form, use `/account/passkeys/options` as the route (for example a
  `data-reauth-route` attribute on the form, which `routeOf` reads first). Add a router test: add a second passkey with
  a passkey re-auth (options -> reauth-options for `/account/passkeys/options` -> `/account/passkeys/options` with
  `assertion` -> register). Add a JS test for the add form's route.

### M2 - `GET /auth/passkey/options` changes state on a GET that `csrf_guard` lets through: any web page can ban the family from signing in
`src/handlers/passkeys.rs:72-145` (the `passkey_started` insert and the ban), `src/main.rs:822` (`get(...)`),
`src/admin_auth.rs:734` (`csrf_guard` passes every GET).
- Scenario: the parent opens any web page (an ad, a link in a mail, a forum) on a phone or laptop on the LAN or
  tailnet. The page has 16 `<img src="https://handy.example.com/auth/passkey/options">`. The browser sends them
  with `Sec-Fetch-Site: cross-site`. The admin gate passes them (the peer is the parent's own address), CSRF never
  looks at a GET, and each request writes a counted `passkey_started` row. The 16th bans the address for an hour:
  `/login` and `/auth/passkey` answer 429 for everyone behind that NAT or address. The page can do it again every
  hour. Before this branch only a POST counted, and `csrf_guard` refuses a cross-site POST.
- Fix: make the options call a POST (`fetch` in `passkey.js` can send one, and `csrf_guard` then refuses cross-site
  calls). Or refuse it unless `Sec-Fetch-Site` is `same-origin`/`none` (or absent with a same-origin `Origin`): run
  `csrf_check` on this route. Add a router test: a cross-site options call writes no row and gets 403.

### M3 - Each sign-in page load counts against the ban, so the password budget is roughly halved
`static/passkey.js:178-217` (the conditional get fetches options on every load where
`isConditionalMediationAvailable()` is true: Chrome, Edge, Safari, Firefox 119+), `src/handlers/passkeys.rs:95`,
`:196` (taken back only on a passkey success), `src/security.rs:590`.
- Scenario: two parents behind one home IPv4. Each `/login` render (first load, and the page shown again after
  every wrong password or failed passkey) writes a `passkey_started` row.
  - A wrong password is now 2 counted rows (`login_failed` plus the re-rendered page's options call).
  - A failed passkey try is 2 as well: its `passkey_refused`, and its `passkey_started`, which is never taken back
    because `Taken` is lost when `sign_in` fails.
  - A successful password sign-in never takes back its own row.
  - About 7 typos or reloads in 15 minutes ban the household for an hour.
  - The ban's reason and the `ip_banned` line then say "16 failed sign-ins", which is untrue.
  - The design's "each options call counts" was meant to protect the challenge store. It wasn't meant to spend the
    login budget on page loads that already have a challenge.
- Fix (either):
  - Count an options call only when its session holds no live, unspent login challenge. Hand the same live
    challenge back to the same session, so a reload or the conditional get plus the button cost one count.
  - Or store the attempt id in the pre-session (`PRE_SIGN_IN_KEY` = the row id instead of `true`) and delete it on
    every end of that session's ceremony: a passkey success, a passkey failure (it writes its own counted row), or a
    password sign-in success.

  Either way, don't word the ban as "failed sign-ins" for these rows. Add a test: a page load followed by a password
  sign-in leaves no counted row, and one failed passkey try counts once.

## Low

### L1 - A passkey sign-in ignores an active TOTP lock and clears its escalation
`src/handlers/passkeys.rs:204-206` (`reset_after_login`: `locked_until = NULL, lock_count = 0`).
- Scenario: someone has Mamma's password and is guessing TOTP codes. The account locks and `lock_count` makes each
  lock longer. Mamma signs in by passkey, as she now does every day. The lock and its escalation reset at once, and
  the guesser gets a fresh budget straight away instead of waiting out a growing lock. `verify_2fa` refuses during
  a lock, so with codes this never happened. The login warning does tell Mamma, which is good.
- Fix: on a passkey sign-in, reset `failed_login_attempts` and `reauth_wrong` but keep `locked_until`/`lock_count`
  while the lock runs (the passkey is unaffected, since the lock only gates the TOTP step). Or record this as
  intended in design 74 D1.5.

### L2 - One global challenge cap for signed-out and signed-in ceremonies
`src/passkeys.rs:86-105` (`MAX_CHALLENGES` 1000, `PER_SESSION` does nothing for sessions with no cookie).
- Scenario: with `ADMIN_PUBLIC=on`, an attacker rotates IPv6 /64s (a /48 has 65 536). Each /64 issues 15
  signed-out challenges before its ban, so the store fills in seconds. Every passkey ceremony, including a
  signed-in parent's re-auth and add, then answers 503 for 5 minutes, and the attacker can keep that up.
- Fix: keep a share for signed-in kinds (for example signed-out `login` at most 500), or cap live signed-out
  challenges per limit key.

### L3 - Mixed-language sign-in page (the implementer's decision)
`templates/login.html` keeps "Username", "Password", "Sign In", "Incorrect username or password." in English, while
the divider, the button and the passkey red line follow the browser's language. On a Norwegian phone that reads
"Sign In / eller / Logg inn med passkey" and "Passkeyen virket ikke…" next to "Incorrect username or password.".
- Judgement: a visible seam for the 2-3 weeks until slice 3e. Translating the page's 6 strings now is cheap and
  takes the seam away. If 3e is close, keep it, but put the item on 3e's list.

### L4 - Reset/Withdraw on a waiting row deletes passkeys without saying so
`templates/account.html` (`reset_passkeys` only in the not-`waiting` branch), `src/parents.rs:349`
(" · N passkeys" only on ready rows).
- Scenario: Pappa resets his own two-factor login (Account › Reset Two-Factor Login). He keeps his passkeys
  (D1.3b) but shows as waiting to Mamma. Mamma's "New One-Time Password…" or "Withdraw…" deletes them
  (`reset_parent`/`remove_parent`), and the confirm doesn't say it.
- Fix: show the line whenever `p.passkeys > 0`, in both branches.

### L5 - A wrong password at a passkey's add or removal is logged as "Passkey refused"
`src/handlers/passkeys.rs:337-355` (`log_wrong` writes `passkey_refused` for the password path).
- The Security Log then says "Passkey refused · Adding a passkey · wrong current password", although no passkey
  was tried. The other forms write their own `*_refused` events (`password_change_refused`, `totp_reset_refused`,
  `parent_change_refused`). No ban effect (`ip` is NULL).
- Fix: give the password path its own event (for example `passkey_change_refused`, with a label) and keep
  `passkey_refused` for passkey failures.

### L6 - Signed out mid-flow, the JS shows "didn't work" instead of going to sign in
`static/passkey.js:117-129` (`post`), `:162-167` (`fetchOptions`).
- `require_full_auth` answers a signed-out fetch with a redirect to `/login`. `fetch` follows it, gets 200 HTML,
  `res.json()` fails, and the result is `{ok: true, json: {}}`. Then `requestOptions({})` / `creationOptions({})`
  throw a TypeError and the parent sees the generic failure. Only `add_options`'s own `{signed_out:true}` is
  handled.
- Fix: treat `res.redirected`, or a non-JSON content type, as signed out and call `location.assign("/login")`.

### L7 - A passkey the add accepts may never be able to sign in (4 KiB limit)
`src/main.rs:827` (`/auth/passkey` 4 KiB) against the registration bounds (credential id up to 1023 bytes, RSA up to
8192 bits).
- An assertion with a 1023-byte id (twice as base64url) and an RSA-8192 signature is about 4.7 KB urlencoded, so
  the sign-in gets 413 while the add (8 KiB) accepted the credential. Real authenticators rarely come near this.
- Fix: give `/auth/passkey` the 8 KiB limit, or refuse ids over about 512 bytes at registration.

## Nit

- N1 `src/handlers/passkeys.rs:111` - `PRE_SIGN_IN_KEY` stays in the session after a password sign-in (harmless;
  remove it in `login`/`verify_2fa`, or not at all).
- N2 `static/passkey.js:200-209` - once the button has aborted the conditional get, a cancelled modal prompt doesn't
  restart it, so autofill stops offering passkeys until the page reloads.
- N3 `static/passkey.js:161` - options are cached 4 minutes, but the server's TTL and the `timeout` the browser gets
  are 5 minutes. A prompt opened at 3:59 and finished more than a minute later fails. Pass the time left as
  `timeout`, or cache for about 60 s.
- N4 `src/passkeys.rs:597-610` - `(None, Some(row))` logs `not_ready` also for an unknown user handle on a ready
  account's credential: the code should be `user_handle` when the row's account is ready.
- N5 `templates/login.html` + `passkey.js` - on the banned (429) page the conditional fetch gets 429 too and shows
  the banned line a second time in `[data-passkey-error]`.
- N6 `src/handlers/passkeys.rs:110` - `session.id().is_none()` is read before the record loads. With a stale cookie
  (its record gone) the challenge is bound to a dead id, and the response then drops the cookie, so the first try
  fails. `GET /login` normally clears such a cookie first, so the window is narrow; `session.is_empty().await`
  before the check closes it.
- N7 design 74 D1.5 says "the `next` page or Devices", but there is no `next` anywhere (`verify_2fa` lands on `/`
  too). Landing on Devices is right; fix the design's line.

## The implementer's decisions

- **Hidden ban rows** (`passkey_started` in `security_events`, filtered from the Security Log, pruned after a day):
  the mechanism is fine and cheap, and the filter in `handlers/admin.rs:153` is the only reader that needs it (the
  others filter by type or username). But see M2 (a GET can write them cross-site) and M3 (what counts, and the
  ban's wording).
- **10-minute pre-session for signed-out options**: fine. tower-sessions applies the layer's expiry again at the
  next save, so the 10 minutes bind only that response's cookie and record, which is all that's needed. The sign-in
  sets the normal lifetime, cycles the id and writes `SIGNED_IN_AT_KEY`, so the 90-day clock starts.
- **The wrong-2FA warning kept after a passkey sign-in**: agree. Whoever typed those codes knew the password, and
  the passkey sign-in resets the count, so this is the last chance to tell the parent. Note L1 alongside it.
- **A passkey sign-in always lands on Devices**: agree (matches `verify_2fa`, there is no `next`); see N7.
- **Mixed-language sign-in page until slice 3e**: acceptable, but cheap to avoid; see L3.

## Checked and fine

- CSRF: every passkey POST (`/auth/passkey`, `/account/passkeys/*`) is behind `csrf_guard` (form-urlencoded, no
  JSON bodies). Login CSRF is blocked as well, by the session-bound challenge.
- Re-auth binding: each `Proof::of` caller (`/account/password`, `/account/reset-2fa`, `/account/parents`,
  `/{id}/reset`, `/{id}/remove`, `/account/passkeys/options`, `/account/passkeys/{id}/remove`, `/devices/{id}/lock`)
  takes the challenge at `ceremony.route` = its own path. `reauth_route` issues only for those paths. A take spends
  the challenge whether it matches or not.
- `reauth_and_hash` with a passkey: the assertion, then `record_reauth`, then the gated hash only on `Right`. The
  password path is unchanged (one gated job).
- Sign-in: ready by `ready_sql!()` (removed, invited, expired invite, forced change, 2FA off all refused). The
  user handle is required and the credential must be that account's. Then `cycle_id`, pending keys removed, account,
  epoch, `SIGNED_IN_AT_KEY`, the warning, `login_success` "passkey · name" with the IP. Banned keys get 429 on both
  routes.
- `ready()` gained `invite_expires_at.is_none()`. No statement leaves a ready-looking account with an invite (the
  onboarding writes clear it, and so did the older ones). `require_full_auth`'s new branch and `session_check` use
  it. Nothing still uses the old `must_change_password = 0 AND totp_enabled = 1`. `actor_ok!`, Remove's "another
  ready parent", `passkeys::add`/`remove`/`user_id` and the sign-in all use the macro.
- Between parents: removal is `WHERE id = ? AND admin_id = ?`, a re-auth's credential must be the signed-in parent's,
  and the add options carry only your own exclude list.
- The ways back: `reset_parent`, `remove_parent` and `reset_2fa` (with the `reset-sign-in` alias) delete passkeys
  inside their transaction. Removal moves the epoch in the same `BEGIN IMMEDIATE`, this session keeps it, and push
  subscriptions go.
- PRG and anchors: add -> `#passkey-<id>`, remove/gone -> `#passkeys`, refusals render in place at the form's
  anchor, `check_forms` covers the page.
- `passkey.js`: ES5 with promises, no inline script (CSP `script-src 'self'`, `connect-src 'self'`), `textContent`
  only, base64url round trip, the conditional get's abort ignored through `signal.aborted`, the re-auth password
  emptied and no longer required.
- PasskeyAdded: no switch (always pushed), every parent's inbox, target `/account#parents`, worded from parameters.
- i18n: 1821 keys in each file, identical sets. Every passkey key the code uses exists. The Norwegian reads
  naturally and uses "passkey" throughout; the design's sentences are used word for word. Logs carry codes and
  names only, no secrets. style.css v71 is bumped in `head.html`. DEPLOY.md, connectivity.md and server/CLAUDE.md
  are current, apart from M1's claim about the add.

## Test coverage against design §7, step 1

Present: add behind the re-auth plus the push to every parent; the sign-in skips 2FA and logs; removed, invited,
expired and forced-change accounts refused; another parent's passkey refused; a null user handle with
allowCredentials passes and a wrong one fails; removal ends the other sessions; Reset, Remove and `reset_2fa`
delete passkeys; the options and bad assertions count in the ban, 429 when banned; `reauth_wrong` and three
strikes; parallel takes and parallel counters; another session's, route's or epoch's challenge refused; a forged
Host; the button at a named host with no passkeys.

Missing:
1. Add with a passkey re-auth (would have caught M1).
2. Password change, Reset… and Withdraw/Remove by passkey. Only Show PIN, Add Parent, reset-2fa and passkey
   removal are covered.
3. Removing another parent's passkey id: nothing deleted, `passkey_gone`.
4. A challenge of another kind through the router (covered only by the `Challenges` unit test).
5. The `reset-sign-in` dispatch in `run_command`.
6. The 4 KiB and 8 KiB body limits.
7. The session after a passkey sign-in: cookie lifetime, and `SIGNED_IN_AT_KEY` so that `SESSION_MAX_DAYS` ends it.
8. `require_full_auth`'s new `!ready()` branch and `/api/session` for that state.
9. A cross-site options call (after M2's fix) and M3's counting.
10. JS: the add flow (options -> create -> submit, `InvalidStateError` -> "exists") and the conditional-abort path.

---

## Revision check - fix round 8ce305da (on 5cccaeaa)

Checked by reading `git diff 5cccaeaa..8ce305da` and its new tests; I didn't run cargo.

**Verdict: mergeable. No High or Medium remain.** Every B finding is fixed or deliberately left (L3). The new
counting can't be dodged, and I found nothing the fix broke. 4 new Nits, plus one process note (P1).

### Each finding
| # | State | How it was fixed |
|---|---|---|
| M1 | fixed | `data-reauth-route="/account/passkeys/options"` on the add form, which `routeOf` reads first. Router test `a_second_passkey_is_added_behind_a_passkey`, JS test for the route and the add flow. |
| M2 | fixed | `POST /auth/passkey/options` (4 KiB) goes through `csrf_guard`; a GET gets 405. Test `a_cross_site_options_call_writes_nothing` (cross-site 403, no row). |
| M3 | fixed | One `passkey_started` row per pre-session (its id in `PRE_SIGN_IN_KEY`). A session with a live challenge gets that challenge back with no new row. The row is deleted at the next `/auth/passkey` or `/login` POST. A wrong password costs 1, a failed passkey try 1 (its refusal). The ban's words count failures and starts separately. Test `the_sign_in_page_costs_nothing_once_its_ceremony_ends`. |
| L1 | fixed | `reset_after_passkey` resets `reauth_wrong`, and `failed_login_attempts` only when no lock was ever taken; `lock_count`/`locked_until` stay. This matches `take_totp_attempt`'s "after the first lock every attempt locks again". Side effect, which I think is right: Devices' "someone knows your password" row stays until a TOTP sign-in or a password change (the password change resets it, line 1107). |
| L2 | fixed (part A's M2) | Two pools. The sign-in pool (500) drops its oldest and allows 3 per client; the signed-in pool (500, 8 per session) can't be filled from outside. |
| L3 | left open (decision) | The sign-in page is still mixed-language until slice 3e. Keep it on 3e's list. |
| L4 | fixed | "…'s passkeys are removed too." on the waiting row's New One-Time Password… and on Withdraw/Remove. |
| L5 | fixed | `passkey_change_refused` (labelled, en/nb) for a wrong password at add or removal. |
| L6 | fixed | `post()` treats `redirected` or `text/html` as signed out and goes to `/login`. |
| L7 | fixed (part A) | Registration caps the id at 512 bytes and RSA at 4096; `the_bodies_keep_to_their_limits` shows the largest assertion fits 4 KiB. |
| N1 | fixed | The key now holds the row id and is removed when the ceremony ends. |
| N2 | fixed | The conditional get restarts after a cancelled or failed button prompt. |
| N3 | fixed | Options are cached for 60 s, and the server returns the live challenge anyway. |
| N4 | fixed | `user_handle` when the credential's owner is ready. |
| N5 | fixed | The conditional get fails silently. |
| N6 | fixed | `session.is_empty().await` loads the record before the check. |
| N7 | fixed | The design says "Devices (a `next` page comes with step 3)". |
| §7 tests | added | Password change, Reset and Withdraw by passkey; another parent's passkey id; `reset-sign-in`; the body limits; the session's lifetime and `SIGNED_IN_AT_KEY`; the `!ready()` branch with `/api/session`; the flood; JS add, signed-out and abort paths. |

### The questions asked
- **Many pre-sessions, one point each?** Each cookieless session still costs one counted row and 15 ban the
  address, the same cap as before. Reusing one session costs nothing and adds no challenge (`live_sign_in`). Rows can
  only be deleted through the session's own server-written id, and every POST that deletes one writes its own
  counted row (`login_failed`, `passkey_refused`). The only exception is `/login`'s Busy path, which writes nothing
  (it takes its own row back as before). Reaching Busy means saturating argon2, and each of those `/login` POSTs
  writes its `login_failed` row first, so this gains nothing. **No dodge.**
- **A password guesser who never posts `/login`?** Guessing a password needs `/login`, and every attempt writes
  `login_failed` before argon2 runs (unchanged). Guessing passkeys needs `/auth/passkey`, which writes
  `passkey_refused`. The options call isn't a guessing channel at all; it only grows the store, and the pools bound
  that.
- **The POST under `csrf_guard` from the iOS standalone PWA?** It's a same-origin `fetch` POST, like
  `/account/passkeys/reauth-options` already was. iOS 16.4+ sends `Sec-Fetch-Site: same-origin`; older WebKit
  sends `Origin`, which `csrf_check` compares with Host or the trusted `X-Forwarded-Host`. The handler takes no
  `Form`, so the empty form-encoded body is fine. A setup where this is refused (a proxy rewriting Host without
  trusted XFH) already refuses every form in the PWA. Manual QA on an iPhone stays in design §7's list, as before.

### New Nits
- RN1 `handlers/passkeys.rs:118` - when a session's challenge expired (page open for 5+ minutes) or was evicted
  (a 4th session from the same client), the new row overwrites `PRE_SIGN_IN_KEY` and the old row is orphaned. It
  then counts until the 15-minute window ends. Delete the previous id before inserting.
- RN2 `passkey.js` `startConditional` - a conditional get started when the page loads keeps its challenge forever
  in the browser. A parent who comes back to an open sign-in page after more than 5 minutes and picks the autofill
  passkey gets "didn't work" and a counted refusal. This was there before the fix round. Restart the conditional get
  when its options are about 4.5 minutes old, or on `visibilitychange`.
- RN3 `passkey.js` `signedOut` - any `text/html` answer counts as signed out, including `csrf_guard`'s 403 page. A
  signed-in parent behind a Host-rewriting proxy would then be sent to `/login` instead of seeing the error. Edge
  case; checking `res.redirected` or `status === 401` alone, and treating HTML only together with a redirect, would
  be tighter.
- RN4 `SIGN_IN_PER_CLIENT = 3`: a household NAT with 4+ sign-in pages open within 5 minutes evicts the oldest
  session's challenge, so its next try fails once. Acceptable; noted only.

### P1 - `0116_passkeys.sql` changed after it was pushed
The first version is on `origin/passkeys-1` (5cccaeaa). The fix round added
`CREATE UNIQUE INDEX admin_users_webauthn_user_id`. The migration's own header says "Frozen from its first push".
Nothing is merged or tagged, so this is fine before the merge. But any database that already ran the first version
(a dev or emulator server started on this branch) fails sqlx's checksum at the next start. Reset that database, or
delete its `_sqlx_migrations` row for 116. Say so when it's merged.
