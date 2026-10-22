# 62 - Several parent accounts for one family: an assessment

Assessment only (2026-10-22). Nothing is built. Scope: **one family, several parent logins** (both parents, each
with their own username, password, 2FA and sessions, managing the same phones). Several families is a separate
assessment (PLAN.md:431-438 has the earlier notes).

`S/` = `server/src/`, `M/` = `server/migrations/`. Line numbers are today's `main` (70583aa9).

## 1. Today's model

**One row in practice, many rows in the schema.** `admin_users` has had an autoincrement id and a `UNIQUE` username
since the first migration (`M/0001_init.sql:1-7`). Nothing in the schema or the queries limits it to one row. But
nothing can create a second row either:

- `bootstrap_admin` creates the first account only while the table is empty (`S/security.rs:266-279`, insert at
  `:316`). There's no self-registration and no "add account" route (the `/account` routes are the signed-in account's
  own: `S/main.rs:1364-1376`).
- So every install has exactly one admin today, named `ADMIN_USERNAME` (default `admin`). The plan says "one family
  can share one admin login" (PLAN.md:432).

**Already per account (keyed on `admin_users.id`, works for N as is):**

| What | Where |
|---|---|
| Session: `admin_id` + `session_epoch` in the session, checked on every request | `S/security.rs:339-352`, `:399-419` |
| Password check, dummy hash for unknown names | `S/handlers/auth.rs:85-131`, `S/admin_auth.rs:69-110` |
| TOTP secret, replay guard (`totp_last_step`), the 2FA lock (`lock_count`, `locked_until`) | `S/admin_auth.rs:259-297`, `:323-344` |
| Re-auth counter `reauth_wrong` (3 wrong passwords end that account's sessions) | `S/admin_auth.rs:181-237`, `M/0086_reauth_wrong.sql` |
| Password change, 2FA reset, Sign Out Everywhere (each bumps that account's epoch) | `S/handlers/auth.rs:742-746`, `:832-834`, `:864-889` |
| "Wrong 2FA codes before this sign-in" warning (by username) | `S/admin_auth.rs:377-402` |
| Web Push subscriptions (`admin_id` FK, `ON DELETE CASCADE`, max 10 per account) | `M/0078_parent_events.sql:50-52`, `S/handlers/notifications.rs:829-851` |
| Spotify login in progress, bound to the username that started it | `S/spotify.rs:1267-1291`, `:1305` |
| CLI `reset-2fa <user>` | `S/main.rs:556-584`, `S/admin_auth.rs:425-478` |

Design 35 already thought about two parents: a browser's push subscription is dropped only when no account knows
it, "two parents on one family iPad" (`S/push.rs:1145-1150`), and a re-subscribe on a shared device moves the
endpoint to the account that signed in (`ON CONFLICT (endpoint) DO UPDATE SET admin_id`, `notifications.rs:846`).

**Family-wide (one value for the whole server, not per account):**

- Push fan-out goes to **every** active subscription, whatever the account (`S/push.rs:342-344`). Both parents get
  every push already.
- `notification_settings` (Notify Me About) is a singleton (`M/0078_parent_events.sql:30`).
- The inbox's read state is one column, `parent_events.read_at` (`M/0078_parent_events.sql:21`; set at
  `notifications.rs:251`, `:516`, `:540`). One parent reading an event clears the other's bell.
- The Parent PIN and the Child PIN are per **phone**, not per parent (`device_policy.override_pin_hash`,
  `kid_pin_*`; `docs/notes/server/hardening-and-pins.md:22`). The phone can't tell which parent typed it.
- Storytel is one family login (`music_storytel`, `CHECK (id = 1)`, `M/0049_music.sql:88-96`); Spotify accounts are
  the children's, not the parents' (`M/0074_music_spotify.sql:16`).
- Backups, time rules, catalog, contacts, DNS: all family data, no account column.

**Things that assume one account (small, but they bite with two):**

1. **One shared password file.** `reset-2fa` and the first admin both write `data/initial-admin-password`
   (`S/startup.rs:307`, `:325`), and **any** account's first-sign-in password change deletes it
   (`S/handlers/auth.rs:461-463`). With two accounts, resetting A and then B finishing its forced change deletes
   A's file.
2. **A username with `:` crashes the 2FA code.** `totp_for_secret` passes the username as the TOTP account name and
   `.expect`s (`S/security.rs:145-158`). totp-rs 5.7.2 refuses a `:` in it. Today only `ADMIN_USERNAME` could hit
   this. An invite form must validate names.
3. **Docs and the CLI hint say `reset-2fa admin`** (`server/DEPLOY.md:221-224`, `:266-268`, `:292`).
4. **`ADMIN_TAILSCALE_USERS`** is a list already (`S/net.rs:224-226`), but the second parent's tailnet login has to
   be added to it by hand on servers that set it. That's ops, not code.

Nothing anywhere does `LIMIT 1` or `id = 1` on `admin_users`. The `id = 1` singletons (`global_schedule`,
`music_storytel`, ...) are family settings, not accounts.

## 2. What changes for N accounts

### 2.1 Roles

Recommend **two roles: `owner` and `parent`**. Both run the phones the same way: every page, every command,
Erase Phone, Show Child PIN (with their own password re-auth). The owner can also:

- add, remove and reset parent accounts;
- restore a backup. A restore replaces the whole database, `admin_users` too (`server/CLAUDE.md`, "No admin
  session leaves the live database"). So a restore can revive a removed account, or bring back an older password.

All-equal is simpler, but then either parent can remove or reset the other. A compromised second account could
then lock the first parent out. With an owner, the worst a stolen `parent` session can do is what it can already
do to the phones. More than one owner is allowed, so an owner can promote the other parent. The last owner can't
be removed or demoted.

### 2.2 Adding an account (invite)

The first run doesn't change: `bootstrap_admin` creates the first account, as `owner`.

To add someone, the owner opens Settings › Parents › Add Parent… and types a username (validated:
`[a-z0-9._-]{1,32}`, no `:`) and a display name ("Mamma"). The owner then re-enters their own password
(`admin_auth::reauth`, the shared 3-strikes counter).

The server creates the row with `must_change_password = 1`, `totp_enabled = 0` and `invite_expires_at = now + 72h`.
It shows a generated 24-character one-time password **once**, on a `no_store` page. `/account/...` is already
`no_store` (`S/admin_auth.rs:592`).

The invitee signs in with it and goes through the existing forced onboarding: they set a new password, then
mandatory 2FA setup. `require_full_auth` already routes them there (`S/security.rs:406-409`), so there's no new
onboarding code. Login refuses a pending account whose `invite_expires_at` has passed.

Why a temporary password and not an invite link: it reuses the bootstrap path exactly, and adds no new
unauthenticated route. A link would add a public `/invite/<token>` page (token hashing, rate limit, CSRF rules,
picking a username) for the same trust: whoever holds the secret first becomes that parent.

### 2.3 Per account (most of it already is)

Password, 2FA, sessions, re-auth, the 2FA lock and push subscriptions are already per account (§1). New:

- `display_name`, shown in "by …" and on Parents.
- The Account page shows your role. Owners see the Parents list: the name, the role, "Waiting for first sign-in
  (expires …)" or the last sign-in, and Remove… / Reset… / Make Owner….

### 2.4 Removing an account

Recommend a **soft remove**: set `removed_at`, clear `password_hash` and `totp_secret`, bump `session_epoch`, and
delete the account's push subscriptions (`push::forget_subscriptions`, `S/push.rs:315`). Do it in one transaction
with the last-owner guard in the `UPDATE`'s `WHERE`, so two owners removing each other at once can't leave none.
`load_active_admin` and login skip removed rows.

Why soft: the username stays taken. The security log, `time_lifts.created_by` (`S/handlers/lifts.rs:117-127`) and
`login_warning` all key on the username **text** (`S/security.rs:74-91`, `S/admin_auth.rs:381-386`). Reusing a
removed parent's name would merge two people's history.

Removing yourself is allowed, unless you're the last owner.

### 2.5 Audit trail ("who did it")

- The **security log** already names the account on 39 of its 61 writes (`record_security_event(..,
  Some(&admin.username), ..)`), and `/security` shows it (`S/handlers/admin.rs:184`). Nothing to add there beyond
  the new account events: `parent_invited`, `parent_first_sign_in`, `parent_removed`, `parent_reset`,
  `parent_role_changed`. Each needs a label (the event-id test, `S/handlers/admin.rs:379-384`).
- **Activity and Locate's Requests** (the summary's history, `S/handlers/activity.rs`) show commands from
  `device_commands`, which has no "requested by" column (`M/0010_find_my_device.sql:23-31`). The username goes only
  to the security event (`S/handlers/locate.rs:1235`). Add `device_commands.requested_by` (admin id). Show "by Mamma"
  on the row only when the family has more than one active account. Do the same for lifts (`created_by` is there
  already).
- **Settings saves are mostly not logged at all.** For example: `tracked_apps.rs` and `calls.rs` write no event,
  and `toggle_app` doesn't even take `CurrentAdmin` (`S/handlers/devices.rs:1753`). "Who changed the allowlist"
  can't be answered today for one account either. Logging every save is an L job across ~40 handlers. Recommend not
  now (open question 6).

### 2.6 Parent PIN on the phone

Keep it **one PIN per phone, shared by the parents**. A PIN per parent would need the launcher to hold several
hashes. `override_pin_hash`/`_salt` is one value in the policy JSON, pinned by `policy_json_keys_snapshot` and
`PolicyResponseCompatTest`, so that would be a contract change. It would also need the phone's pin-events to say
whose PIN it was. That's a lot for little gain: the PIN is for being next to the phone, not for auditing.

### 2.7 Notifications

- Pushes already reach every account's devices (§1). Nothing to do.
- Recommend keeping Notify Me About and the inbox's read state **family-wide** for v1 (open question 5). Per
  parent would need `notification_settings` per account and a `parent_event_reads(admin_id, event_id)` table
  replacing `read_at`: M, touching the bell count, the inbox, `push_marker` and the Activity dot.
- New: tell the other accounts when an account is added, first signs in, is reset or is removed. The cheapest way
  is an orange row on Devices for the other accounts, like the existing `live_warning_row`
  (`S/handlers/auth.rs:925-960`), plus the security event. A push for it needs a non-device event kind:
  `parent_events.device_id` is `NOT NULL` (`M/0078_parent_events.sql:12`).

### 2.8 PWA state per account

There's nothing per account to move. The theme follows `prefers-color-scheme` with no toggle. The scroll and form
places live in `sessionStorage`/`window.name`, and Locate's span in `localStorage`
(`docs/notes/server/pwa.md:13-23`, `:576`). All of that is per **browser**. `last_opened_at` is per push
subscription. On a shared iPad, the parents sign out and in to switch, and the browser state is shared. That's
fine.

## 3. Security

- **Trust.** A second parent has full control of the children's phones: location history, Erase Phone, Show Child
  PIN. That's the point of the feature, and roles don't change it (§2.1).
- **The invite secret is the weak spot.** Whoever uses the one-time password first gets the account and sets their
  own 2FA. This is the same gap QA 22 M4 named for `reset-2fa` (`S/admin_auth.rs:421-424`). Mitigations, all
  cheap:
  - the password is shown once and expires in 72 h;
  - the owner sees "Waiting for first sign-in" and can Revoke;
  - the first sign-in writes `parent_first_sign_in` with the IP and raises the Devices row for the owner;
  - adding an account needs the owner's password re-entered, so a stolen owner session can't quietly add an
    attacker account.
- **Takeover through another account:**
  - A `parent` can't reset or remove anyone.
  - An owner can reset a parent. That replaces the password with a new one-time one shown on screen, clears 2FA,
    bumps the epoch and deletes push subscriptions, exactly like `reset_2fa` but without a shell. It needs the
    owner's password re-entered.
  - A parent can't reset an owner. An owner who loses their 2FA uses the CLI `reset-2fa <user>`, or a second owner
    resets them.
  - Account changes are written to the security log, which no handler deletes (only retention,
    `S/retention.rs:124`). A restore is owner-only.
- **Recovery flows after the change:**
  - Your own password change and 2FA reset: unchanged.
  - Owner resets a parent: new, as above.
  - CLI `reset-2fa <user>`: unchanged, but writes `initial-admin-password-<user>`, and the forced change deletes
    only the file for the account changing (fixes §1 item 1).
- **Rate limits:**
  - The 2FA lock and `reauth_wrong` are per account, so one parent's lock never blocks the other.
  - The IP ban (15 failures in 15 min, `S/security.rs:20-23`, `:105-139`) is per client IP. Two parents behind one
    NAT share it. That's acceptable, and the same as today with one account on two devices.
  - `PasswordGate` (1 running + 4 queued, `S/admin_auth.rs:30-33`) is global. That's fine for a handful of
    accounts.
- **Backups.** A backup holds every account's password hash and its unsealed TOTP secret (CLAUDE.md, "No admin
  session leaves the live database"). More accounts means more secrets in each backup. That doesn't change the
  model (backups are root-only on the drive). Restoring a backup taken before a removal revives that account. After
  a restore, every session ends already. Recommend a line in the restore confirm: "Parent accounts come back as they
  were in this backup".
- **Enumeration.** Unchanged: unknown names still verify against the dummy hash.

## 4. Migration and the launcher

One migration, **`0087_parent_accounts.sql`** (the next free number, CLAUDE.md "Data model"):

```sql
ALTER TABLE admin_users ADD COLUMN role TEXT NOT NULL DEFAULT 'parent' CHECK (role IN ('owner', 'parent'));
ALTER TABLE admin_users ADD COLUMN display_name TEXT;
ALTER TABLE admin_users ADD COLUMN invited_by INTEGER REFERENCES admin_users(id);
ALTER TABLE admin_users ADD COLUMN invite_expires_at TEXT;
ALTER TABLE admin_users ADD COLUMN removed_at TEXT;
UPDATE admin_users SET role = 'owner';            -- today's one account (every existing row) is the owner
ALTER TABLE device_commands ADD COLUMN requested_by INTEGER;   -- no FK: history outlives a removal
```

`bootstrap_admin` inserts `role = 'owner'` explicitly. An old backup restored later runs 0087 at the next start, so
its one account becomes the owner too. Rolling back to an older server binary is the usual sqlx refusal for an
unknown applied migration (`S/main.rs:1460`). The pre-migrate backup covers that, as for every migration.

**The launcher doesn't care, and must not.** Admin identity never crosses the device API: no device route reads
`admin_users`, and the policy JSON has no account field. `time_lifts.created_by` stays server-side (`TimeLiftRow`,
`S/time_rules.rs:105-115`, not in the policy). So there's no contract file, no `policy_json_keys_snapshot` change,
no launcher or music release, and no two-release ordering. Server-only.

## 5. Size and steps

| # | Step | Size |
|---|---|---|
| 1 | 0087; `role`/`display_name`/`removed_at` in `AdminUser`; login and `load_active_admin` skip removed and expired-invite rows; bootstrap = owner; a username validator (also for `ADMIN_USERNAME`, the `:` panic); `require_owner` helper; restore made owner-only | S |
| 2 | Settings › Parents (owner): list, Add Parent… (re-auth, the one-time password shown once, 72 h), Revoke invite, Make Owner…/Make Parent (last-owner guard); Account shows the role and display name; the new events + labels | M |
| 3 | Remove… and Reset… another parent (re-auth, one transaction, soft remove, push subscriptions gone, epoch bumped, the new one-time password on screen); `reset-2fa` writes a per-user file and the forced change deletes only its own; DEPLOY.md's `reset-2fa <user>` and `ADMIN_TAILSCALE_USERS` note | M |
| 4 | "by Mamma": `device_commands.requested_by` from every queueing handler (`locate::queue_command`, `window_relock`, `install_now`, `kid_lock`'s `set_kid_pin` - the four `INSERT INTO device_commands`), shown on Activity/Requests and lifts when >1 active account; the security log shows the display name | S |
| 5 | Tell the other accounts: an orange Devices row for "Ola was added / signed in for the first time / was reset / was removed" until dismissed | S |
| 6 | (optional, open question 5) per-account read state and Notify Me About | M |

Steps 1-5 make a **medium feature: two M steps and three S**, all in `server/`, with tests in `src/tests/`. That
includes a `TestApp` helper for a second signed-in account; today `admin_cookie()` seeds one. Step 6 adds another M.

**Contract changes: none.** Internal only: migration 0087, new admin routes (`/settings/parents...`, put under a
`no_store` path), new security-event ids.

## 6. Open questions for the user

1. **Roles:** owner + parent (recommended), or all equal (simpler, but either can lock out the other)?
2. **What's owner-only** besides managing accounts? Recommended: restoring a backup. Also deleting backups,
   software updates/reboot, Phone Setup (the Tailscale key)? Erase Phone stays for both.
3. **The invite:** a one-time password shown to the owner and handed over in person (recommended), or a link where
   the invitee picks their own username? And is 72 h a good expiry?
4. **Display names:** add "Mamma/Pappa" names (recommended), or just show the username?
5. **Inbox and Notify Me About:** family-wide as today (recommended for v1), or per parent (one parent reading an
   event doesn't clear the other's bell)?
6. **"Who changed it" for settings saves** (allowlist, time rules, contacts, catalog), beyond commands and the
   security log: wanted now (L, every handler), or later?
7. **An owner's lost 2FA:** is the CLI plus "a second owner can reset you" enough, or should a parent also be able
   to reset an owner?
8. **Separated parents:** the owner can remove the other parent. Is that acceptable, or should removal need both
   owners?
