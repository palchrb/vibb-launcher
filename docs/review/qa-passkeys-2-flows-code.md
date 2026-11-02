# Code QA - `passkeys-2` @ c04fd933, design 74 step 2: the "Kun passkey" flows

Scope: the switch, the readiness predicate's passkey-only arm and `admin_select!()`, the refusals while it is on,
`/login`'s dummy-hash path, the last passkey's removal, the ways back, the switch-off page, the re-auth forms of a
passkey-only parent, the sign-in page without passwords, i18n, PRG anchors and tests. I reviewed by reading the diff
(`git diff main...passkeys-2`) and did not run cargo. The challenge pool, `/56` and crowd changes in `passkeys.rs`
and `net.rs` belong to the crypto/limits half and I only skimmed them.

**Verdict: fix round needed - 0 High, 1 Medium, 3 Low, 3 Nit.**

**The security of the flows holds.**
- Nothing turns passkey-only on or off without a passkey assertion. The assertion's challenge is bound to the route:
  `/account/passkey-only` and `/account/password-sign-in` were added to `reauth_route`.
- The ≥1-passkey guard is part of the switch's `UPDATE` itself. The last passkey's guard is part of the `DELETE`, in
  `BEGIN IMMEDIATE`.
- No password or TOTP path can write while the flag is on: every handler refuses first, and every writing `UPDATE`
  also carries `passkey_only = 0`.
- A passkey-only name at `/login` takes the unknown-name path (`login_hash` -> `None` -> the dummy hash, the same
  refusal). Its log line is in the same `INSERT`.
- Every way back clears the flag in its own statement: Reset, soft Remove, and `reset-2fa`/`reset-sign-in`.
- `admin_select!()` is used at every place that reads an `AdminUser`. I grepped every `AdminUser` decode: `security`
  `load_active_admin`, `passkeys::sign_in`, `parents::rows`, `handlers::parents::target_of`, and `/login` and
  `verify_2fa` in `handlers/auth`, plus 3 test helpers. No `SELECT * FROM admin_users` is left; a missed one would
  fail to decode anyway, since `passkey_count` has no default.
- `ready_sql!()`'s `admin_users.id` correlation resolves to the nearest `admin_users` everywhere it is used. None of
  those queries aliases the table, and the nested `admin_passkeys` inside the passkey `DELETE` binds to the inner
  scope.

## Medium

### M1 - A passkey-only parent's re-auth forms still take the password path: a submit without a passkey counts as a wrong password, and three of them sign out everywhere
- Where: `src/admin_auth.rs` `Proof::of` / `reauth` / `reauth_and_hash` are unchanged; `static/passkey.js` `installAdd`
  / `add()`; `templates/account.html` (the password fields and submit buttons are only `hidden`).
- Scenario 1, Add Passkey:
  1. A passkey-only parent opens "Add Passkey…", types a name and presses Enter.
  2. `installAdd`'s submit handler runs `add()`, which posts `/account/passkeys/options` with an empty
     `current_password` and an empty `assertion`.
  3. `Proof::of("", "")` is `Password("")`. `verify("", "!passkey")` is false, so `reauth_wrong` goes up by 1.
  4. The parent, who has no password, reads "Wrong password…".
- Scenario 2, Add Parent: the same happens with Enter in the username or name field. The submit button is hidden but
  not disabled, so an implicit submit still sends the form.
- Three in a row end every session and every device's notifications (`record_reauth`). Nothing can be broken into
  this way, since the password path can never succeed. But the server still accepts and counts a proof that can't
  exist, and the page's text is wrong. This is exactly the "password path still accepted server-side" you asked
  about. It applies to every re-auth form a passkey-only parent can reach: Add Passkey, Add Parent, Reset…,
  Withdraw/Remove, a passkey's removal and Show Child PIN.
- Fix (server):
  1. In `admin_auth::reauth` and `reauth_and_hash`, when `admin.passkey_only` and the proof is `Password`, answer a
     new `Reauth::NeedsPasskey` without counting anything. That matches what `passkey_only_on` already does
     ("passkeys.only.needs_passkey").
  2. Each form then shows "Bekreft med passkey. Ingenting ble endret." under its field.
- Fix (JS): for a passkey-only form, make submit (and Enter) start the passkey re-auth instead of sending, for
  example with a `data-passkey-only-form` attribute.
- Test: a passkey-only actor's Add Parent / Show Child PIN / add-options with an empty assertion -> no
  `reauth_wrong`, the needs-passkey line. Also cover the passkey-only actor's re-auth forms succeeding by passkey;
  only Reset/Remove *of* a passkey-only parent by a password parent is covered today.

## Low

### L1 - Removing the last passkey for this address while passkey-only gives no warning
`templates/account.html` (the removal confirm), `src/passkeys.rs` `remove`. The `DELETE` guards the account's
total count. A passkey-only parent with one passkey for `handy.example.com` and one for the tailnet name can
therefore remove the `handy.example.com` one. This session keeps going, but once it ends (90 days, sign-out,
another removal) they can't sign in at this address again and nothing told them.
- Fix: when `passkey_only` and the row is the last for its `rp_id`, the confirm adds "…then you can only sign in
  at <other host>". No server refusal is needed.

### L2 - The switch's "other addresses" warning only looks at the passkeys' RP IDs
`handlers/auth.rs` `several_rp_ids: rp_ids > 1`, `passkeys.only.hosts`. Design D4.3 asks for the warning "when the
server has seen other hosts (the hosts of the account's sessions and passkeys)". A parent who also signs in by
password at the tailnet name (no passkey there) isn't told they lose that sign-in when they switch on. Sessions
don't store their host today.
- Fix: either record the host at sign-in (`login_success` already has the IP) and include it, or show the hosts
  line always when the parent has passkeys at only one RP ID ("…you'll sign in only at <host>"). Or accept this and
  amend D4.3.

### L3 - `reset-2fa`/`reset-sign-in`'s terminal line doesn't say passkey-only was turned off
`admin_auth::reset_2fa` returns "Two-factor login for X is reset…" for a passkey-only account, which had neither a
password nor two-factor login. The flag and the passkeys are cleared correctly, and the passkey count is said.
- Fix: one clause, "passkey only is off,", when the flag was on (read it in the same transaction).

## Nit

- N1 `i18n/{en,nb}.json` `passkeys.only.failed`, `password_on.failed`: " - " where the other new strings use "–".
- N2 `handlers/auth.rs` `LoginView::load` runs one extra query on every `/login` and `verify_2fa` POST. Cheap and
  needed for the re-render; noted only.
- N3 `handlers/passkeys.rs` `password_sign_in`: after `switch_off` returns `None` ("switched off elsewhere"),
  `PASSWORD_ON_SECRET_KEY` stays in the session. It is harmless (never written to the account), but remove it on
  that path too.

## Checked and fine

- **The switch (`passkeys::switch_on`).** It is one `UPDATE` that:
  - sets `passkey_only = 1` and `password_hash = '!passkey'`;
  - clears the TOTP (`totp_secret`, `totp_enabled`, `totp_last_step`) and the TOTP lock and its counters;
  - moves `session_epoch + 1`.

  Its guards are the epoch, not removed, `passkey_only = 0`, `ready_sql!()`, and `(SELECT COUNT(*) …) >= 1`, all
  inside the statement. Why nothing was written is read only afterwards. The handler then:
  - requires a non-empty assertion, refusing without counting;
  - uses `Proof::Passkey` only, at the route `/account/passkey-only`;
  - keeps the new epoch in this session and ends push subscriptions;
  - logs `passkey_only_on` and redirects (PRG) to `#passkey-only`.

  `the_switch_counts_its_passkey_in_the_statement` covers the race.
- **The predicate.**
  - `ready()`: passkey-only arm `passkey_count >= 1`.
  - `ready_sql!()`: `EXISTS` on `admin_passkeys`.
  - `require_full_auth` never sends a passkey-only account to setup-2fa; a passkey-only account with no passkey gets
    no session.
  - `/api/session` and the passkey sign-in use the same predicate.
- **The refusals while on.**
  - The handlers back out to `#passkey-only`: `change_password_form`/`change_password`, `setup_2fa_form`/
    `setup_2fa_verify`, `update_account_password`, `reset_totp`.
  - The `UPDATE`s also carry `passkey_only = 0`: `change_first_password`, `setup_2fa`'s two, the account password,
    and the 2FA reset.
- **`/login`.** A passkey-only name goes through the same dummy-hash argon2 and the same message. The `login_failed`
  row carries "passkey only" in its single pre-argon2 `INSERT`. The lock and invite paths come after `valid`, so
  they are unreachable for such a name.
- **The last passkey.** The `DELETE`'s own `NOT (passkey_only = 1 AND count <= 1)` in `BEGIN IMMEDIATE`; `Last` is
  read only after it. The confirm shows no form for the last one and the D2.3 line when going down to one with no
  other parent.
- **The ways back.** `reset_parent`, `remove_parent` and `reset_2fa` set `passkey_only = 0` in their statement and
  delete the passkeys in the same transaction (step 1).
- **The switch-off page.**
  1. The TOTP secret lives only in this session until the write; the page is no-store via `/account/..`.
  2. The order: the password length, the match, the code checked read-only (`totp_step`), and then the assertion,
     spent last. A typo spends no challenge and counts nothing.
  3. The hash is made behind the gate.
  4. One `UPDATE` sets the hash, secret, `totp_enabled` and `totp_last_step` (the used code can't sign in again;
     tested), guarded by epoch, not removed and `passkey_only = 1`.
  5. It logs `passkey_only_off` and redirects (PRG) to `#password`. Passkeys stay and no session ends, as D2.5 says.
- **The sign-in page without passwords.** It needs at least one passkey-only parent, and no not-removed parent
  with the flag off unless that parent's one-time password has expired. A pending invite or Reset brings the form
  back, and so does `reset-2fa`'s state (`invite_expires_at` NULL), so the shell's way back always gets a password
  form. There is no auto-start and the autofill is off. A browser without passkeys sees the D6.4 line; an IP host
  sees "don't work at this address". `POST /login` answers as before.
- **i18n.** 2069 keys in each file, identical sets. The Norwegian is natural, the D2.1/D2.3 sentences are
  word-for-word, and "Kun passkey" is used consistently.
- **Tests.** Every item of §7 step 2 is there: the switch's statement guard with a raced removal, the dummy hash,
  the refusals, the ways back, the last passkey, the one-write switch-off, the predicate, and the page without
  passwords. Missing: M1's cases (a passkey-only *actor*'s re-auth forms, by passkey and with an empty proof).
