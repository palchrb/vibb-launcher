# QA 62 steps 1-2 - code (`design-62-step12`, `bec294ad` on `c5412faf`)

2026-10-22. Server only. Inputs: `docs/design/62-parent-accounts.md` (incl. "Steps 1-2 as built"),
`qa-62-design.md`, `qa-62-revision-check.md`, `git diff c5412faf..bec294ad` (34 files, +2344/-175).

**Result: no High, no Medium. 1 Low (behaviour), 1 Low (tests), 2 Nits.** Merge after P62-1 and P62-2, or merge now
and do both in step 3.

## Runs

- `cargo test` (full, once): **1177 passed, 0 failed**. `cargo fmt --check`: clean. `node --test jstest/`: 139 passed.
- 14 mutations (table below): 9 killed by the committed suite, **5 survive it**, all 5 killed by the QA probe tests
  (P62-2). Every mutation reverted; the probes were not committed (kept in the QA scratchpad, `probe.rs`).
- Browser: this machine has Playwright's Chromium 1140 only (`~/.local/opt/pw/browsers`). I did not repeat the
  implementer's Chromium pass; **Safari (WebKit) and Firefox stay unchecked**, including the `replaceState` reload of
  the Add answer - the revision check asked for both. That is for the user's browser pass (§10, steps 2 and 6).

## Findings

### P62-1 (Low) - 2FA set up before the forced password change ends the one-time password's expiry

- **Evidence:** `/auth/setup-2fa` (GET and POST) sits behind `require_session` only (`main.rs`, `onboarding_routes`),
  so a session that still has to change its password can reach it. `setup_2fa_verify` (`handlers/auth.rs`) writes
  `totp_enabled = 1, invite_expires_at = NULL` without checking `must_change_password`. Probe
  `qa_setup_2fa_before_the_password_change`: Pappa signs in with the OTP, opens `/auth/setup-2fa` (200), enters a
  code - the row is `must_change_password = 1, totp_enabled = 1, invite_expires_at = NULL`, and the OTP still signs in
  (`/login` -> `/auth/verify-2fa`) with no end date.
- **Scenario:** the invitee types `/auth/setup-2fa` (or has it open in a second tab) and enrolls before setting a
  password, then stops. The OTP - sitting in the chat it was sent through - is now a password with no expiry (2FA still
  needed). Mamma's row reads "Waiting for sign-in" (the CLI-reset label), not "Waiting · until …"; the answer page's
  promise "It works until Sun 20:51" no longer holds. Not a takeover: whoever enrolls needed the OTP within the 72 h.
- **Fix:** clear the expiry only when the account becomes ready, in both writes:
  `invite_expires_at = CASE WHEN must_change_password = 0 THEN NULL ELSE invite_expires_at END` in
  `setup_2fa_verify`, and the same with `totp_enabled = 1` in `change_first_password` (2FA first, password second).
  Simpler alternative: `setup_2fa_form`/`setup_2fa_verify` redirect to `/auth/change-password` while
  `must_change_password` (the order §4.1 assumes). Keep the probe as a test.

### P62-2 (Low) - five guards no committed test pins

These mutations pass all 1177 committed tests (checked against `tests::parents`, `admin_auth`, `settings_pages`,
`android_lock_page`, the only modules touching these paths):

| Mutation | Survives because |
|---|---|
| M3 `load_active_admin` without `removed_at IS NULL` (N1) | Remove also bumps the epoch; no test has a removed row with its old epoch |
| M5 Add's `INSERT` without `actor_ok` | No test calls `add_parent` with a stale or not-ready actor |
| M11 Remove without `DELETE FROM push_subscriptions` | No test has a subscription on the removed account |
| M12 `reset_totp` without its epoch condition | The five-writes tests cover the forced change, `update_account_password` and the 2FA setup only |
| M14 Remove's `UPDATE` without `actor_ok` | Same as M5 |

- **Scenario:** a later edit drops one of these and nothing fails: a removed account restored by a backup or a bug
  gets its sessions back (M3), a held Add or Remove from a signed-out/removed actor writes (M5/M14), a removed
  parent's phone keeps getting pushes (M11), a held "Reset 2FA" undoes another parent's Reset (M12).
- **Fix:** add the QA probes (each kills its mutation; all pass on `bec294ad`):
  `removed_session_without_epoch_bump_is_out` (set `removed_at` by SQL, `GET /devices` -> `/login`);
  `stale_actor_statements_write_nothing` (`add_parent`/`remove_parent` with `session_epoch - 1` -> `SignedOut`, no
  row added or removed); `remove_deletes_push_subscriptions` (only the target's go);
  `held_reset_totp_cant_undo_a_reset` (`held(.., "/account/reset-2fa", ..)` past `reset_by_sql`: `/login`, the row
  untouched). Design §9's `remove_ends_sessions_and_push_keeps_history` and
  `held_request_of_a_removed_actor_changes_nothing` are step 3's, but the delete and the actor conditions shipped in
  step 2. A sixth probe, `removed_actor_session_post_writes_nothing` (Mamma removed by Pappa, then her Add and Name
  POSTs -> `/login`, nothing written), passes and is worth keeping too.

### P62-3 (Nit) - `reset-2fa` restores past the cap of 8

`admin_auth::reset_2fa` clears `removed_at` with no count check, so a family of 8 gets a 9th account that isn't
removed; Add then says "at most 8" until two go. The shell is the root of trust and the design wants the restore, so
this is cosmetic. Fix: say it in the CLI's line ("the family now has 9 parents"), or accept and note it in the design.

### P62-4 (Nit) - with JS, Copy still copies after the 5-minute hide

`.secret-once` hides by `visibility` after 300 s (the no-JS fallback), but with JS the Copy button stays and
`copySecret` reads `textContent`, which is still there. Fix (optional): `setTimeout(function () { forget(doc); },
300000)` in `install`, so both paths end at 5 minutes.

## Checked and fine

- **`actor_ok!`** in both statements, ready included; the cap and the `lower(username)` check are in the one
  `INSERT ... SELECT` (SQLite takes the write lock at the statement's start, so two Adds can't both pass the cap);
  `UNIQUE` violations map to Taken. N2 is pinned by the "Mamma" case (M4 killed).
- **`remove_parent`:** the other-ready guard counts accounts other than the target, so a waiting target is always
  removable by a ready actor and self-removal needs another ready account (M6 killed); `BEGIN IMMEDIATE` with the
  pool's 5 s `busy_timeout`, so remove-vs-remove serializes and the loser's `actor_ok` fails; the subscription delete
  is in the transaction (a failure drops `tx` = rollback); the reason (SignedOut / Gone / LastReady) is read after the
  rollback. Self-removal signs out.
- **The five self-service writes** each sign out on no row and write nothing; the forced change compares the verified
  hash (M1 killed); `setup_2fa_form`'s `COALESCE … RETURNING` gives two tabs one secret (M13 killed); `set_name` has the
  same guard. `login`, `verify_2fa` and `load_active_admin` skip removed rows; a removed name takes the dummy hash.
- **The OTP:** only in the 200 answer body (no `Location`, no file, no `tracing` call names it, not in
  `security_events` - M8 killed); `/account/*` is `no-store` (`no_store_path`); `data-post-answer`; `reveal.js`
  `replaceState` -> `GET /account/parents` (served); `forget` clears `.secret-once` and hides Copy (M7 killed - though
  via the fake's exact selector string, so design 59's tests fail with it); Copy is `hidden` without JS and the
  `.edit-body > .copy-secret[hidden]` rule beats `.button-small`'s display.
- **Validation:** usernames `[a-z0-9._-]{1,32}` after trim+lowercase; display names 1-32 chars, `is_control` plus the
  Cf list (checked against Unicode 15.1 Cf: complete) and U+2028/2029. `bootstrap_admin` returns before reading
  `ADMIN_USERNAME` when `admin_users` has a row, so an existing install with "Admin" or "a b" still starts.
- **0087** is five nullable `ADD COLUMN`s: an existing account reads as ready with no expiry; nothing to backfill.
- **Files:** `reset-2fa` writes `initial-admin-password-<id>`; the forced change deletes its own file, and the shared
  one only when `invited_by IS NULL` (`cli_reset_writes_its_own_file_and_keeps_others`). DEPLOY.md and the Account
  ⓘ name the per-id file; `image-smoke.sh` doesn't read the reset's file.
- **`reset-2fa` restoring a removed account:** a `main::run_command` subcommand only (needs a shell as the service
  user or `docker exec`); no HTTP route reaches it (`POST /account/reset-2fa` is the self-service TOTP reset, behind
  full auth and the password). Logged `parent_restored`. Matches the design's trade-off.
- **CSRF/limits:** the three POSTs are on the admin router under `csrf_guard`; Add and Remove run Argon2 in the
  `PasswordGate` (429 when busy) and count wrong passwords (`record_reauth`, three sign out); Name is a cheap write.
- **Anchors:** every form posts to `#name` or `#parents`; refusals render at their group with the form open (400);
  PRG targets keep the fragment. No answer lands at the top.

## Mutation table

| # | Mutation | Result |
|---|---|---|
| M1 | `change_first_password` without `AND password_hash = ?` | killed: `two_otp_sessions_one_keeps_its_session` |
| M2 | `update_account_password` without the epoch condition | killed: `held_password_change_cant_undo_a_reset` |
| M3 | `load_active_admin` without `removed_at IS NULL` | **survives**; probe `qa_removed_session_without_epoch_bump_is_out` |
| M4 | Add without the `lower(username)` taken check | killed: `withdraw_keeps_the_name_taken` |
| M5 | Add without `actor_ok` | **survives**; probe `qa_stale_actor_statements_write_nothing` |
| M6 | Remove without the other-ready guard | killed: `withdraw_keeps_the_name_taken` |
| M7 | `reveal.js` `forget` without `.secret-once` | killed: `reveal.test.js` (5 of 9) |
| M8 | The OTP in `parent_added`'s detail | killed: `the_otp_is_shown_once_and_never_stored` |
| M9 | Add's cap bound to 1000 | killed: `cap_of_eight` |
| M10 | A removed account's session on a POST (Remove's epoch bump and `load_active_admin`'s `removed_at` both dropped) | killed: `withdraw_keeps_the_name_taken` |
| M11 | Remove without the push-subscription delete | **survives**; probe `qa_remove_deletes_push_subscriptions` |
| M12 | `reset_totp` without its epoch condition | **survives**; probe `qa_held_reset_totp_cant_undo_a_reset` |
| M13 | `setup_2fa_form` without `COALESCE` | killed: `held_setup_2fa_writes_nothing_on_a_removed_row` |
| M14 | Remove without `actor_ok` | **survives**; probe `qa_stale_actor_statements_write_nothing` |
