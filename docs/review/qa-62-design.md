# QA 62 - design: several parent accounts (`docs/design/62-parent-accounts.md`)

Design QA, 2026-10-22, against `f3-run` at 6e999bac. `S/` = `server/src/`. Every file:line in the note was checked;
they hold (`admin_auth.rs:592`, `main.rs:1477`, `auth.rs:461`, `push.rs:329`, `device_api.rs:381`, the 5 `Bell::load`
callers, the 4 `device_commands` inserts, `migrations_73_to_86_in_order`, `a_held_request_cant_bring_the_session_back`).
Claims confirmed: the OTP is 24 chars of a 57-letter alphabet (~140 bits, `startup.rs:330`); totp-rs 5.7.2 refuses `:`
only in `TOTP::new`'s URL fields and `generate` uses the secret alone, so `:` -> `_` changes no existing
authenticator entry (and a `:` name could never finish setup: `setup_2fa_form` panicked first); `["account", ..]` is
no-store; the push marker is already scoped by `s.admin_id` (`push.rs:867-871`); `subscribe` moves a shared
endpoint to the signing-in account; no admin SSE stream exists (the only SSE is the phones'), so "unread per account"
is the bell, the inbox, the app badge and the push payload. The guard design itself (last-ready in the `UPDATE`,
actor-is-current as `EXISTS`) is sound under SQLite's single writer. Counts: **High 1, Medium 3, Low 9, Nit 3.**

## High

**Q62-1 - Withdraw's `DELETE` also matches a reset account.** §4.2 withdraws when `invite_expires_at IS NOT NULL AND
must_change_password = 1 AND invited_by IS NOT NULL`. §4.3's Reset sets exactly that on any invited account (it
keeps `invited_by`, sets `must_change_password = 1` and a new `invite_expires_at`), and §3 shows such a row as
"Waiting…" with "Withdraw…". So Withdraw on a reset Pappa hard-deletes a real parent:
- `device_commands.requested_by` dangles (no FK by design), so "· Pappa" disappears from his past commands;
- the username is freed. A later "pappa" inherits the old `time_lifts.created_by` (username text, `lifts.rs:119`)
  and the security log's rows, now under the new person's display name;
- if Pappa had invited anyone, `invited_by` (FK, no `ON DELETE`, `foreign_keys(true)`) makes the `DELETE` fail: 500.

**Fix:** drop the `DELETE` path. "Withdraw…" is the label of Remove on a waiting row, the same soft statement and
guard. The mistyped username stays taken, which costs nothing (removed rows aren't listed). This also drops a route
and a test. If freeing a name is wanted later, it needs a marker that the row never finished onboarding (a column set
at the first forced change), not the current condition.

## Medium

**Q62-2 - The existing self-service writes lack the actor-is-current condition, and a held request undoes a Reset.**
§4 puts `EXISTS (... id = ?actor AND session_epoch = ?epoch AND removed_at IS NULL)` only on the new statements. The
target's own writes are all `WHERE id = ?` and hand the session the `RETURNING` epoch:
- forced change `auth.rs:444-460`;
- `update_account_password` `:742-759`;
- `reset_totp` `:832-847`;
- `setup_2fa_form` `:510`, `setup_2fa_verify` `:579`.

This is the class `a_held_request_cant_bring_the_session_back` closed for design 59. Consequences:
- **Reset is undone.** Pappa's password change passes the middleware, Mamma's Reset commits, then Pappa's `UPDATE`
  writes his own hash over the OTP and bumps the epoch, and his session takes the new epoch. He keeps a live session,
  the OTP is dead, and Mamma's Reset reported success. The window is one Argon2 run, and the attacker it is meant
  against holds the session and the password.
- **"The first parent to finish owns the account" (§4.1, §8) is false.** Two sessions on the same OTP both verify
  against the old hash, and the *last* `UPDATE` wins.
- **Remove:** a held forced change or `setup_2fa_*` writes a password hash or TOTP secret back onto a removed row.
  `removed_at` still blocks the session, but §8's "removed rows hold no password hash and no TOTP secret" (backups)
  no longer holds.

**Fix (note §4, step 1):**
- Add `AND session_epoch = ?session_epoch AND removed_at IS NULL` to those five statements. No row means
  `signed_out_response`.
- The forced change also gets `AND password_hash = ?hash_it_verified` (compare-and-swap), so the first one wins.
- Tests: Reset against a held password change, and two OTP sessions changing at once (exactly one keeps a session).

**Q62-3 - Reset's answer breaks on reload and Back.** `reveal.js` does `replaceState(null, "", location.href)`
(`static/reveal.js:21`). For Reset, `location.href` is `/account/parents/{id}/reset`, which has no GET route (§3 adds
only `GET /account/parents`). A reload, or Back then Forward, is a 405 page in the PWA. **Fix:** route
`GET /account/parents/{id}/reset` to the account page too (it is no-store under `["account", ..]`). Or post Reset to
`/account/parents` with `id` and `op=reset`. Add a browser-pass step: reload after Reset.

**Q62-4 - The recovery story contradicts itself.** The trade-off paragraph says a locked-out parent "gets back in
through the CLI (`reset-2fa <user>`)", but §2 makes `reset-2fa` refuse a removed account. So a parent removed by the
other has no way back except a backup restore. The CLI reset also leaves `invite_expires_at` (`admin_auth.rs:441-445`
gets no new column), so on a reset invitee whose 72 h ran out, the new file password answers "expired".

**Fix:**
- `reset-2fa` on a removed account restores it: clear `removed_at` and write the per-id file. The shell is the root
  of trust anyway, and this is the "no role protects one parent" escape hatch.
- `reset-2fa` always sets `invite_expires_at = NULL`.
- Say both in the trade-off paragraph and in DEPLOY.md.

## Low

**Q62-5 - 0088 assumes one account, but steps 2-4 make several before it.** The cross joins
(`FROM admin_users a, notification_settings s` / `..., parent_events e`) also give prefs and read rows to removed
accounts, which §4.2 says Remove deletes. §1's "copies … to that account" is wrong once step 2 has shipped.

**Fix:**
- `WHERE a.removed_at IS NULL` in both inserts.
- Name the columns in `INSERT INTO notification_prefs (admin_id, …)`.
- State the release plan: steps 1-5 ship in one `server-v*`. If they don't, say that between steps 2 and 5 both
  parents share Notify Me About and the read state.

**Q62-6 - Two readers are missing from §5's table.**
- The inbox's count after marking (`notifications.rs:257-260`: `SUM(?1 IS NULL OR device_id = ?1), COUNT(*) …
  WHERE read_at IS NULL`, the gear's `data-unread` and the badge).
- `send_one`'s count (`push.rs:672-675`). "It joins already" is not quite true: `Due`/`DueRow` (`push.rs:386-411`)
  carry no `admin_id`, so `s.admin_id` must be added to the due query.

Every `read_at` reader: `parent_events.rs:852`; `notifications.rs:205, 251, 259, 516, 540`; `push.rs:673, 904`. Every
`notification_settings` reader: `push.rs:338`; `notifications.rs:489, 561`. The column drop makes a missed one fail
loudly, as intended.

**Q62-7 - The "expired" message names the inviter.** For a reset account that is the wrong parent, and for the
bootstrap account `invited_by` is NULL. **Fix:** "This one-time password has expired - ask for a new one", with no
lookup. Also, §9 step 1 says expired-invite accounts get "the unknown-name message", which contradicts §4.1; the
test should expect the expired message.

**Q62-8 - A half-onboarded account has no expiry and no row state.** The forced change clears `invite_expires_at`.
After that, an invitee who never sets up 2FA keeps a password-only account forever. Its row is neither "Waiting" nor
signed in, and the Withdraw label doesn't apply to it. **Fix:** clear `invite_expires_at` in `setup_2fa_verify`, not
at the forced change. The hash change (with Q62-2's compare-and-swap) already spends the OTP, the expiry then covers
the whole onboarding, and the row reads "Waiting…" until the account is ready. Derive the row state from "ready".

**Q62-9 - The Argon2 and time details to pin.**
- The OTP's hash must not run on an async worker. Use `state.passwords.change(password, &actor.password_hash, &otp)`
  then `record_reauth`, as `update_account_password` does (`auth.rs:709-722`): one gated job. Add and Reset do this.
- Write `invite_expires_at` in SQL as `datetime('now', '+72 hours')`. A Rust RFC 3339 value (`…T…Z`) compares as
  text against `datetime('now')` (`… …`) and expires up to a day late.

**Q62-10 - The Add form teaches password managers a wrong pair.** A username text field plus
`autocomplete="current-password"` makes Safari and Chrome offer to save "pappa" with Mamma's password. **Fix:**
- name the new field `new_username` with `autocomplete="off"`;
- add a hidden `<input autocomplete="username" value="{{ own username }}">` before the password field.

**Q62-11 - Lowercasing at bootstrap breaks the first sign-in.** Login stays an exact match, so a fresh
`ADMIN_USERNAME=Mamma` is stored as `mamma` and "Mamma" is refused. **Fix:** bootstrap only trims, and refuses a
name that isn't already valid, with the clear message. The Add form may lowercase, because its answer shows the
username. Existing installs are untouched either way: bootstrap runs only on an empty table (`security.rs:273-279`).

**Q62-12 - The race tests don't race.**
- Through HTTP, both requests queue on the `PasswordGate` (one Argon2 at a time), so `tokio::join!` almost always
  runs the second `UPDATE` after the first commits. Test the guarded statements as functions
  (`remove_parent(db, actor, epoch, target)`) joined on two pool connections. Also keep the held-request test.
- Add tests for Q62-1 (Withdraw or Remove of a reset account keeps the row), Q62-2 (both variants), Q62-3 (GET of
  the reset path is 200) and Q62-8.

**Q62-13 - Step 6 (`settings_saved`) is an extra, and its rule is shaky.**
- "2xx/3xx = saved" logs a refusal rendered with 200, as some handlers do (e.g. `auth.rs:397-407`).
- `/music` is family settings, but it is on the skip list.

Move it to PLAN's backlog as a proposal. That removes about 15 note lines and a step.

## Nit

**Q62-14 - Less code.**
- `verify_2fa` needs only `removed_at IS NULL`, not the expiry filter: only totp-enabled accounts reach it.
- Fold the cap of 8 into the `INSERT … WHERE (SELECT COUNT(*) …) < 8`, so it is atomic.
- Fold `<ready>` into the actor-is-current `EXISTS`, so "the actor stays ready" (§4.3) follows from the statement,
  not from `require_full_auth`.

**Q62-15 - Stale text and small gaps.**
- `templates/account.html:89` and `docs/notes/server/connectivity.md:81,98` name `data/initial-admin-password` for
  the reset.
- `jstest/reveal.test.js` needs `.secret-once`.
- The page `<h1>` is still "Account & Password" (`account.html:12`) next to the hub's "Account & Parents".
- Display names: also refuse Unicode format characters (bidi overrides), or "by" lines can be spoofed.
- §8 relies on the inviter seeing an OTP sign-in. The waiting row should show the last one ("Waiting · signed in
  14:02 from 10.0.0.3"), not only "Waiting for first sign-in".

**Q62-16 - `reveal.js` clears on `visibilitychange`.** A parent who switches to Messages to type the OTP comes back
to an empty field. The line should say "Copy it before you leave this page", and the browser pass should include that
step. Without JS, `.pin-digits`' one-minute CSS hide (`style.css:1681-1693`) needs a `.secret-once` twin, or the
class reuses `.pin-digits`.

## Trimming the note (474 lines)

The substance fits in about 340 lines:
- **§8 (~40 lines)** restates §4.1-§4.3 ("one use", "never logged", "what a removed account can do"). Keep only what
  is new: enumeration, exposure, locks per account.
- **§6 step 6 (~15 lines)** goes to the backlog (Q62-13).
- **§7** fits in 3 lines.
- **§3's route table and its bullets** say the anchors twice.
- **§9** can list the test names instead of prose.
- **§11's step 1 cell** repeats §2.
- **§12 "None"** can go.
