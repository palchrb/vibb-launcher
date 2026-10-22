# 62 - Several parent accounts in one family (design)

Status: revised 2026-10-22 from its design QA (`docs/review/qa-62-design.md`, Q62-1..16; the last section lists what
changed). Server only. Base: `62-parent-accounts-assessment.md` ("A §n"). `S/` = `server/src/`, lines are `main` at
`6e999bac`.

**The user's decisions (2026-10-22):** "enkelt opprette multi-admins, mor og far, med egen konto".
1. All accounts are equal: no owner role. Any account adds, resets or removes another, except the last ready one.
2. The invite is a one-time password (OTP), shown once, valid 72 h, behind the inviter's re-entered password. No
   invite link.
3. Display names ("Mamma", "Pappa"), and "by <name>" on commands and in the security log.
4. Notify Me About and the inbox's read state are per parent; the events stay the family's.
5. A removal is soft. The two latent bugs (A §1 items 1-2) are fixed in step 1. No launcher or music change.

**Trade-off (separated parents):** either parent, with their own password and 2FA, can remove or reset the other.
The one locked out comes back through the server's shell: `reset-2fa <user>` also restores a removed account (§2),
or through a backup restore. No role protects one parent from the other.

**Release:** steps 1-5 ship in one `server-v*` release. 0088 can still meet several accounts (the user may run a
`main` build between steps), so it copies to every account that isn't removed.

## 1. Data model

**`0087_parent_accounts.sql` (step 1):**

```sql
ALTER TABLE admin_users ADD COLUMN display_name TEXT;          -- NULL reads as the username
ALTER TABLE admin_users ADD COLUMN invited_by INTEGER REFERENCES admin_users(id);
ALTER TABLE admin_users ADD COLUMN invite_expires_at TEXT;     -- an OTP's onboarding ends by then; NULL once ready
ALTER TABLE admin_users ADD COLUMN removed_at TEXT;            -- soft remove: the row and its username stay
ALTER TABLE device_commands ADD COLUMN requested_by INTEGER;   -- admin_users.id; no FK, history outlives a removal
```

- `AdminUser` gains the four columns and `fn name(&self) -> &str` (display name, else username); `DeviceCommand`
  gains `requested_by: Option<i64>`. Both read with `SELECT *`.
- **Ready** = `removed_at IS NULL AND must_change_password = 0 AND totp_enabled = 1`. The guards count ready accounts,
  and a Parents row's state derives from it (§3).
- Every time column is written in SQL (`datetime('now', '+72 hours')`), never as a Rust RFC 3339 value, which
  compares as text against `datetime('now')` and would expire up to a day late.

**`0088_parent_reads_and_prefs.sql` (step 5):**

```sql
CREATE TABLE notification_prefs (                -- Notify Me About, per account; no row = every switch on
    admin_id INTEGER PRIMARY KEY REFERENCES admin_users(id) ON DELETE CASCADE,
    push_pin_used INTEGER NOT NULL DEFAULT 1, push_pin_lockout INTEGER NOT NULL DEFAULT 1,
    push_foreign_access INTEGER NOT NULL DEFAULT 1, push_unseen_boot INTEGER NOT NULL DEFAULT 1,
    push_low_battery INTEGER NOT NULL DEFAULT 1, push_phone_off INTEGER NOT NULL DEFAULT 1,
    push_phone_on INTEGER NOT NULL DEFAULT 1);
INSERT INTO notification_prefs (admin_id, push_pin_used, push_pin_lockout, push_foreign_access,
    push_unseen_boot, push_low_battery, push_phone_off, push_phone_on)
  SELECT a.id, s.push_pin_used, s.push_pin_lockout, s.push_foreign_access, s.push_unseen_boot,
    s.push_low_battery, s.push_phone_off, s.push_phone_on
  FROM admin_users a, notification_settings s WHERE a.removed_at IS NULL;
DROP TABLE notification_settings;
CREATE TABLE parent_event_reads (                -- a row = this account has read this event
    admin_id INTEGER NOT NULL REFERENCES admin_users(id) ON DELETE CASCADE,
    event_id INTEGER NOT NULL REFERENCES parent_events(id) ON DELETE CASCADE,
    PRIMARY KEY (admin_id, event_id)) WITHOUT ROWID;
INSERT INTO parent_event_reads (admin_id, event_id)
  SELECT a.id, e.id FROM admin_users a, parent_events e
  WHERE a.removed_at IS NULL AND e.read_at IS NOT NULL;
DROP INDEX idx_parent_events_unread;
ALTER TABLE parent_events DROP COLUMN read_at;
```

- The old table and column are dropped, not left unused: a missed reader fails a test loudly.
- Retention's age-based `DELETE` of `parent_events` cascades to the reads (`foreign_keys(true)`, `S/main.rs:1477`).
- `migrations_73_to_86_in_order` extends to 0088.
- **Existing installs need no action:** the one account keeps everything; its name reads as the username until set;
  0088 gives it the family's switches and read state. An older backup restored later migrates at the next start.

## 2. The latent bugs, validation, the CLI (step 1)

**The shared password file (A §1.1):**
- The bootstrap keeps `data/initial-admin-password` (`deploy/image-smoke.sh` and DEPLOY.md read that name).
- `reset-2fa <user>` writes `data/initial-admin-password-<id>`. `startup::new_password()` (the 24 characters) is split
  out of `new_password_file(dir, name)`.
- The forced change deletes `initial-admin-password-<own id>`, and `initial-admin-password` only when
  `invited_by IS NULL` (an OTP never goes to a file, so that account is the bootstrap one).

**`reset-2fa <user>`** (the shell is the root of trust): as today, plus `invite_expires_at = NULL`, and on a removed
account it also clears `removed_at` (restoring it, with its username) and seeds its reads as Add does (§4.1). It logs
`totp_reset` or, for a removed one, `parent_restored`. DEPLOY.md says both, the new file name, and that a second
parent's tailnet login goes into `ADMIN_TAILSCALE_USERS` on servers that set it.

**Stale text:** `templates/account.html:89` and `docs/notes/server/connectivity.md:81,98` name
`data/initial-admin-password` for the reset; they name the per-id file.

**The `:` panic (A §1.2):** `totp_for_secret` passes `username.replace(':', "_")` as the label. `generate` uses the
secret alone, so no existing authenticator entry changes.

**Usernames (`security::valid_username`, `^[a-z0-9._-]{1,32}$`):**
- `ADMIN_USERNAME` at bootstrap (an empty table only): trimmed, then refused with a clear message unless already
  valid. Never lowercased, since login is an exact match.
- The Add form trims and lowercases, then validates; its answer shows the username.

**Display names:** trimmed, 1-32 characters, no control or Unicode format characters (category Cf: bidi overrides,
zero-width), so a "by" line can't be spoofed. Not unique; the Parents list also shows the username.

**The self-service writes get the actor condition (Q62-2).** The five `admin_users` writes the signed-in account makes
for itself get `AND session_epoch = ?session_epoch AND removed_at IS NULL`; no row means `signed_out_response`:
- the forced change (`S/handlers/auth.rs:444-460`), which also gets `AND password_hash = ?hash_it_verified`, so of two
  sessions on one OTP the first wins and the second is signed out;
- `update_account_password` (`:742-759`), `reset_totp` (`:832-847`), `setup_2fa_form` (`:510`), `setup_2fa_verify`
  (`:579`).

A request held past the middleware therefore can't undo a Reset, or write a hash or a TOTP secret back onto a removed
row. `login` reads `AND removed_at IS NULL`; `verify_2fa` the same (only totp-enabled accounts reach it);
`load_active_admin` adds `AND (invite_expires_at IS NULL OR invite_expires_at > datetime('now'))`.

## 3. The Parents section

On Account (`/account#parents`), not a page of its own: `["account", ..]` is already no-store, and the re-auth forms
are there. The hub row and the page's `<h1>` (`account.html:12`) become "Account & Parents". A "Name" group above
Password sets your own display name (no re-auth; PRG; logs `display_name_changed`).

**Rows, every account not removed, state from "ready":**

| Account | Sub-line |
|---|---|
| You | "Mamma · mamma · You" |
| Ready | "Signed in 14:02 · from 10.0.0.3" (its newest `login_success`) |
| Not ready, before `invite_expires_at` | "Waiting · until Fri 14:02", plus "· signed in 14:02 from 10.0.0.3" after an OTP sign-in |
| Not ready, past it | "One-time password expired" |
| Not ready, no expiry (a CLI reset) | "Waiting for sign-in" |

Other accounts' rows have **Reset…** ("New One-Time Password…" when not ready) and **Remove…** ("Withdraw…" when not
ready): the same two actions, only the labels differ. Your own row has neither.

**Add Parent…:** a confirm with `new_username` (`autocomplete="off"`), name, a hidden
`<input autocomplete="username" value="{own username}">` and your password (`autocomplete="current-password"`), so a
password manager never pairs "pappa" with Mamma's password.

| Route | Does | Answer |
|---|---|---|
| `GET /account/parents`, `GET /account/parents/{id}/reset` | The Account page (the same handler) | reveal.js's reload lands here |
| `POST /account/parents` | Add (§4.1) | The page with the OTP, `#parents` |
| `POST /account/parents/{id}/reset` | Reset (§4.3) | The page with the OTP, `#parents` |
| `POST /account/parents/{id}/remove` | Remove / Withdraw (§4.2) | PRG `/account?parents=removed#parents` |
| `POST /account/name` | Your display name | PRG `/account?changed=name#name` |

A refusal (wrong password, bad or taken username, the cap, the guard) renders Account (400) with its confirm open and
the reason under the field, as `reset_totp` does. No answer jumps to the top.

**The OTP answer:**
- a POST answer (`mark_post_answer`); it loads `static/reveal.js`, whose `forget` also clears `.secret-once`
  (`jstest/reveal.test.js` covers it). Its history entry becomes a GET of the POST's path, which exists for both;
- `.secret-once` gets a twin of `.pin-digits`' CSS hide (`style.css:1681-1693`), after 5 minutes;
- next to it a Copy button and: "Copy it before you leave this page - it's shown only once. It works until Fri 14:02;
  Pappa sets his own password and two-factor login at the first sign-in." A lost one: Reset the row.

## 4. Flows and their guards

**Re-auth and the OTP hash in one gated job:** Add and Reset call
`state.passwords.change(password, &actor.password_hash, &otp)` and then `record_reauth`, as `update_account_password`
does; Remove calls `admin_auth::reauth`. So no Argon2 runs on an async worker, a busy gate answers 429, and the third
wrong password signs the actor out. A wrong one also logs `parent_change_refused` ("add" / "reset pappa" /
"remove pappa").

**Actor condition** in every new statement (`<actor ok>`):
`EXISTS (SELECT 1 FROM admin_users WHERE id = ?actor AND session_epoch = ?actor_epoch AND <ready>)`. SQLite runs
writes one at a time, so this is the race guard, and "the actor is ready" follows from the statement itself.

### 4.1 Add

```sql
INSERT INTO admin_users (username, display_name, password_hash, must_change_password, totp_enabled,
    invited_by, invite_expires_at)
  SELECT ?, ?, ?otp_hash, 1, 0, ?actor, datetime('now', '+72 hours')
  WHERE <actor ok> AND (SELECT COUNT(*) FROM admin_users WHERE removed_at IS NULL) < 8
```

- In the same transaction (from step 5): `INSERT INTO parent_event_reads SELECT ?new, id FROM parent_events`.
- `UNIQUE` (removed names included): "That username is taken." No row: the cap's message, or signed out.
- The OTP is `startup::new_password()` (24 of 57 letters, about 140 bits), only in that response body. Log
  `parent_added` (detail "pappa (Pappa)").

**First sign-in: the existing onboarding, unchanged** (password change, then mandatory 2FA):
- A correct OTP past `invite_expires_at`: "This one-time password has expired - ask for a new one." Only someone who
  knows the password sees it.
- A login on a not-ready account logs `login_success` with detail "one-time password".
- The forced change spends the OTP (the hash compare-and-swap, §2). `setup_2fa_verify` clears `invite_expires_at`,
  so the expiry covers the whole onboarding.

### 4.2 Remove (and Withdraw, its label on a not-ready row)

```sql
UPDATE admin_users SET removed_at = datetime('now'), password_hash = '!removed', totp_secret = NULL,
  totp_enabled = 0, totp_last_step = NULL, reauth_wrong = 0, invite_expires_at = NULL,
  session_epoch = session_epoch + 1
WHERE id = ?target AND removed_at IS NULL
  AND EXISTS (SELECT 1 FROM admin_users WHERE id != ?target AND <ready>) AND <actor ok>
```

- `'!removed'` isn't a PHC string. The same transaction deletes the account's push subscriptions (deliveries
  cascade), and from step 5 its prefs and reads. A new `spotify.forget_pending(username)` drops its Spotify login in
  progress. Log `parent_removed`.
- No row: "Pappa is the only parent who can sign in - add another parent first", or signed out.
- Always soft, so a mistyped username stays taken (removed rows aren't listed). History keeps the name:
  `requested_by`, `time_lifts.created_by` and the security log's username text.
- Removing yourself: the same guard, then `/login`.

### 4.3 Reset another parent

```sql
UPDATE admin_users SET password_hash = ?otp_hash, must_change_password = 1, totp_secret = NULL,
  totp_enabled = 0, totp_last_step = NULL, failed_login_attempts = 0, locked_until = NULL, lock_count = 0,
  reauth_wrong = 0, invite_expires_at = datetime('now', '+72 hours'), session_epoch = session_epoch + 1
WHERE id = ?target AND id != ?actor AND removed_at IS NULL AND <actor ok>
```

The same transaction deletes the target's push subscriptions (design 35 §7). Log `parent_reset`. The target's held
requests fail their own epoch condition (§2), so the Reset can't be undone.

## 5. Notify Me About and the inbox, per parent (step 5)

**Push filter** (`push::queue`, `S/push.rs:329`; the column name stays compiled in):

```sql
INSERT INTO push_deliveries (...) SELECT s.id, ?, 'event', X'', ? FROM push_subscriptions s
  JOIN admin_users a ON a.id = s.admin_id LEFT JOIN notification_prefs p ON p.admin_id = s.admin_id
  WHERE s.state = 'active' AND a.removed_at IS NULL AND COALESCE(p.{column}, 1) = 1
```

The emergency call has no switch (only the removed filter). `settings_page`/`save_settings` read and write the
actor's row (`INSERT ... ON CONFLICT (admin_id) DO UPDATE`); the footer says "Just for you - each parent chooses their
own." The categories stay as they are.

**Read model:** unread for me = no `parent_event_reads (me, event)`. `parent_events::unread_count(db, admin_id)` is
`SELECT COUNT(*) FROM parent_events e WHERE NOT EXISTS (SELECT 1 FROM parent_event_reads r WHERE r.admin_id = ? AND
r.event_id = e.id)`. Every reader of `read_at` and `notification_settings` changes:

| Where | Change |
|---|---|
| `Bell::load(db, admin_id)` (`parent_events.rs:852`) | The 5 callers (devices, settings_hub, tracked_apps, locate, music) take `CurrentAdmin` |
| `ROW_COLUMNS` (`notifications.rs:205`; inbox and the summary's Activity) | A `ROW_FROM` const adds `LEFT JOIN parent_event_reads r ON r.event_id = e.id AND r.admin_id = ?`; unread is `r.event_id IS NULL` |
| The inbox's marking (`:251`) and its count after (`:259`, the gear's `data-unread` and the badge) | `INSERT OR IGNORE ... SELECT ?admin, value FROM json_each(?)`; the count per device and in all, for the admin |
| `open` (`:516`), `read_all` (`:540`) | The same insert, one id / `SELECT ?admin, id FROM parent_events WHERE (?d IS NULL OR device_id = ?d)` |
| `push::mark_opened` (`push.rs:904`; the marker and the receipt) | Inserts `(s.admin_id, event)` through the delivery's subscription |
| `send_one`'s payload count (`push.rs:673`) | `unread_count` for the subscription's admin: the due query adds `s.admin_id` to `DueRow`/`Due` (`push.rs:386-411`) |
| `read_switches` (`notifications.rs:489`), `save_settings` (`:561`), `push.rs:338` | `notification_prefs` for the admin |

## 6. "By <name>" (step 4)

- `requested_by = actor.id` in the four inserts: `locate::queue_command` (its `admin: &str` becomes the `AdminUser`),
  `window_relock`, `install_now` and `kid_lock`'s `set_kid_pin` queue rule (callers pass `Option<i64>`; the phone's
  report path passes `None`, so a server-queued command has no "by").
- Activity and Locate's Requests add "· Mamma" (the requester's `name()`, a removed one's too) when
  `SELECT COUNT(*) FROM admin_users` > 1; a one-parent family sees no change.
- Lifts (`time_lifts.created_by`, "· <username>" on Device and Time) and `/security` ("Mamma (mamma) · 10.0.0.3") show
  display names through one username-to-name map per page. Every logged admin action already names its account; the
  unnamed events are the phones' and the system's.
- Settings saves stay unlogged; the cheap middleware form goes to PLAN's backlog (the report has its line).

## 7. The device side

No device route reads `admin_users`, and `pending_command` is built from four named columns
(`S/handlers/device_api.rs:381`), so `requested_by` can't leak. `policy_json_keys_snapshot` and
`PolicyResponseCompatTest` stay unchanged: no contract file, no launcher or music release.

## 8. Security, beyond §4

- **OTP:** about 140 bits behind the IP ban and the gate. Its exposure is the CLI reset's (QA 22 M4, accepted); the
  inviter sees an OTP sign-in on the waiting row and can Reset or Withdraw. It is never in tracing, a security event,
  a URL or a file: only its Argon2 hash, no-store, cleared by reveal.js.
- **Enumeration:** login unchanged (unknown and removed names take the dummy hash, same message). "Taken" only for a
  re-authenticated parent; "expired" only after a correct password.
- **Stolen session:** can't add, reset or remove without the password. **A removed account** signs in to nothing, its
  sessions end at their next request, and its held writes find no row (§2).
- **Locks** (2FA, `reauth_wrong`) are per account; the IP ban per IP (A §3). CSRF is the shared `csrf_guard`.
- **Backups:** removed rows hold no hash and no secret. A backup from before a removal brings the account back; the
  restore confirm says "Parent accounts come back as they were in this backup."

## 9. Tests

`TestApp::admin_cookie_as(username, name)` signs in a second ready account. The race tests call the guarded
statements as functions (`remove_parent(db, actor, epoch, target)`, `reset_parent`, `change_first_password`) joined on
two pool connections; over HTTP the password gate would serialize them.

- Step 1: `valid_username_rule` (" Mamma " at Add -> `mamma`; `:`, `/`, inner space, 33 chars, empty refused;
  bootstrap `Mamma` refused); `colon_in_name_makes_a_code`; `cli_reset_writes_its_own_file_and_keeps_others`;
  `cli_reset_restores_a_removed_account`; `cli_reset_clears_the_expiry`; `removed_login_reads_as_unknown`;
  `expired_otp_says_expired`; `expired_onboarding_session_ends`.
- Step 1 (Q62-2): `held_password_change_cant_undo_a_reset`; `two_otp_sessions_one_keeps_its_session`;
  `held_setup_2fa_writes_nothing_on_a_removed_row`.
- Steps 2-3 (`src/tests/parents.rs`): `add_needs_the_password`; `otp_shown_once_never_logged`;
  `invitee_onboards_with_the_otp`; `expiry_clears_at_2fa_setup`; `reset_answer_reloads_as_get` (200);
  `withdraw_a_reset_parent_keeps_the_row`; `remove_ends_sessions_and_push_keeps_history`; `removed_name_stays_taken`;
  `last_ready_account_cant_be_removed`; `remove_each_other_leaves_one` (race); `reset_vs_remove_one_wins` (race);
  `held_request_of_a_removed_actor_changes_nothing`; `cap_of_eight`.
- Step 4: `command_by_shows_with_two_accounts`; `security_log_shows_display_names`; `policy_json_keys_snapshot`
  unchanged.
- Step 5: `push_follows_each_parents_switches` (emergency to both, removed none); `inbox_read_is_per_parent`;
  `push_tap_reads_for_that_parent`; `new_parent_starts_at_zero`; `payload_count_is_the_recipients`;
  `migration_0088_copies_to_active_accounts`; the existing `notifications`, `web_push`, `summary`, `power` and `shell`
  tests that touch `read_at`/`notification_settings` adjusted.

## 10. Browser pass (the user's, on the PWA)

1. Upgrade with one account: bell count and Notify Me About as before.
2. Set your name. Add "Pappa": a wrong password errors at the confirm with no jump; a right one shows the OTP at
   `#parents`. Copy works; switch apps and back - it is gone; reload and Back show the page without it.
3. In another browser, sign in as pappa with the OTP: the row reads "Waiting · … · signed in 14:02 from …". Set a
   password and 2FA: the row reads "Signed in …".
4. Pappa locks a phone: Activity "· Pappa", the security log "Pappa (pappa)".
5. Different Notify Me About per parent, a (fake) low battery: only the parent with it on gets the push. A reads the
   inbox; B's bell still counts.
6. Reset Pappa, then reload the answer page (no 405). Pappa is signed out at the next tap; the new OTP works.
7. Withdraw a waiting invite; remove Pappa (signed out, name kept on old rows); removing yourself as the last ready
   account is refused.
8. iPhone PWA: every confirm opens and answers in place, the tab bar clear of the fields.

## 11. Steps (one implementer sitting each; steps 1-5 ship in one server release)

| # | Step | Size |
|---|---|---|
| 1 | §2 whole and 0087; `admin_cookie_as` | S |
| 2 | Name and Parents groups; Add with the OTP answer (reveal.js `.secret-once`, its CSS hide, Copy); the GET routes; the first-sign-in changes; hub row and `<h1>`; events and labels (`parent_added`, `parent_change_refused`, `display_name_changed`) | M |
| 3 | Reset and Remove/Withdraw with the guards; push cleanup; `spotify.forget_pending`; `parent_reset`, `parent_removed`, `parent_restored`; the restore confirm's line; the race tests | M |
| 4 | §6 | S |
| 5 | 0088 and §5; the seed at Add and CLI restore; Remove's prefs and reads cleanup | M |

Not in this design: an orange Devices row about account changes (A §2.7); a Parent PIN per parent (A §2.6); several
families (design 63); settings saves in the security log (backlog).

## QA fixes (`docs/review/qa-62-design.md`)

- Q62-1 (High): Withdraw is Remove's label on a not-ready row, the same soft statement; no `DELETE` (§4.2).
- Q62-2: the five self-service writes carry `session_epoch` + `removed_at IS NULL`; the forced change compares the
  verified hash (§2); three tests.
- Q62-3: `GET /account/parents/{id}/reset` serves Account, so the Reset answer reloads to a GET (§3); a test and
  browser step 6.
- Q62-4: `reset-2fa` restores a removed account and always clears the expiry; the trade-off and DEPLOY.md say so.
- Q62-5: 0088 names its columns and skips removed accounts; steps 1-5 ship in one release.
- Q62-6: §5's table lists every `read_at`/`notification_settings` reader, the inbox count and `Due`'s `admin_id`.
- Q62-7: the expired message names no one; the step 1 test expects it.
- Q62-8: `setup_2fa_verify` clears `invite_expires_at`; row states derive from "ready" (§3).
- Q62-9: the OTP hash runs in `passwords.change` before `record_reauth`; times are written in SQL.
- Q62-10: `new_username` with `autocomplete="off"` and a hidden own-username field.
- Q62-11: bootstrap only trims and refuses an invalid name; only Add lowercases.
- Q62-12: the race tests call the statements on two connections; tests added for Q62-1/2/3/8.
- Q62-13: step 6 moved to PLAN's backlog.
- Q62-14: `verify_2fa` reads only `removed_at`; the cap is in the `INSERT`; `<ready>` is in the actor condition.
- Q62-15: stale reset-file text, `reveal.test.js`, the `<h1>`, Cf characters in names, and the waiting row's OTP
  sign-in.
- Q62-16: "Copy it before you leave this page", browser step 2, and `.secret-once`'s CSS hide.
- Trimmed: §12 dropped, §8 keeps only what §4 doesn't say, the anchors once, §9 as test names, §7 in three lines.

## Steps 1-2 as built (2026-10-22, branch `design-62-step12`)

Notes for steps 3-5 and the code QA; `docs/notes/server/connectivity.md`, "Parent accounts", has the whole of it.

- **0087** as §1. `AdminUser` gained `display_name`, `invited_by`, `invite_expires_at`, `name()` and `ready()`;
  `removed_at` is read in SQL only, and `DeviceCommand.requested_by` waits for step 4 (a field nothing reads would be a
  new dead-code warning).
- **Step 1** as §2, plus the revision check's N1 (`load_active_admin` reads `removed_at IS NULL`), N2 (Add's `INSERT`
  refuses a `lower(username)` that is taken), N5 ("This invitation has expired - ask for a new one.", logged
  `login_expired`) and N6 (`setup_2fa_form`'s write is `totp_secret = COALESCE(totp_secret, ?)` under the actor
  condition, returning the stored secret: one statement for "write only onto none" and "read what is there").
  `startup::new_password()` is split out; `startup::reset_password_file(id)` names `reset-2fa`'s file.
  `bootstrap_username` refuses an invalid `ADMIN_USERNAME` and the start stops. The forced change is
  `admin_auth::change_first_password` (epoch, not removed, the verified hash).
- **Step 2** as §3-§4.2 for Add and Withdraw. The statements are `parents::add_parent` and `parents::remove_parent`
  (the full §4.2 statement and its push-subscription delete in one `BEGIN IMMEDIATE` transaction; N3: why nothing
  happened is read afterwards - signed out, already gone, or the last ready account). Step 2 shows "Withdraw…" only
  on a not-ready row; the route takes any target, so **step 3 adds the "Remove…" label on ready rows, Reset, its
  route and the self-removal entry point, `spotify.forget_pending`, the restore confirm's line and the race tests**
  (`remove_each_other_leaves_one`, `reset_vs_remove_one_wins`, `held_request_of_a_removed_actor_changes_nothing`).
  The self-removal refusal ("You are the only parent who can sign in - add another parent first.") is said in the
  Parents group's flash, since your own row has no form. Both GET routes of §3 exist.
- **Layout:** Account's groups are Name, Password, Two-Factor Login, Sessions, Parents. A Parents row is the name
  with "username · state" under it. The one-time password is shown in 6 blocks of 4 (spans, so a copy has no
  spaces), "One-time password for Pappa · sign in as pappa", Copy (hidden without JS), and "Copy it before you leave
  this page - it's shown only once. It works until Sun 20:51; Pappa sets a new password and two-factor login at the
  first sign-in." The Add form's name is optional (empty reads as the username).
- **"· <name>"**: step 2 has no "by" line; the Parents list always shows names (it is the list of parents), so the
  one-account rule starts with step 4.
- **Browser check** (headless Chromium 1140 only - this machine has no WebKit or Firefox build, so Safari and
  Firefox are not checked; the reload relies on `replaceState`, as design 59's Show Child PIN): light and dark at
  390x844, every answer at its group, 0 text nodes below AA, the reload a `GET /account/parents` without the OTP,
  Back after leaving without it, Copy equals the OTP, three wrong passwords sign out, the invitee's first sign-in in a
  second browser, Withdraw.

**The code QA's fix round** (`docs/review/qa-62-step12-code.md`):
- P62-1: the 2FA setup redirects to `/auth/change-password` while `must_change_password` (the order §4.1 assumes),
  and the expiry is cleared only by the write that makes the account ready, in both writes (`setup_2fa_verify`:
  `CASE WHEN must_change_password = 0`; `change_first_password`: `CASE WHEN totp_enabled = 1`). Test
  `otp_session_sets_the_password_before_2fa`.
- P62-2: the guard tests `removed_session_without_epoch_bump_is_out`, `removed_actor_session_post_writes_nothing`,
  `stale_actor_statements_write_nothing`, `remove_deletes_push_subscriptions`, `held_reset_totp_cant_undo_a_reset`.
- P62-3: `reset-2fa` still restores past the cap of 8 (the shell is the way back for a removed parent, and a full
  family must not lock that out); its line says "The family now has 9 parents …", and Add refuses until enough are
  removed (`cap_of_eight`).
- P62-4: `reveal.js` removes the shown secret after 5 minutes, when the CSS hides it, so Copy ends with it
  (`reveal.js?v=3`; `jstest/reveal.test.js`).
