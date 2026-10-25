# Code QA: branch `pwa-fixes-a` (security review M1 + M2, the summary's reload after Lock again / Unlock)

Reviewer: code QA, 2026-10-25. Base `1b71c21b` (server 0.48.0), head `7d4c0b79` (4 commits: `2ccab9f1` M1,
`684bd13d` M2, `e03af4d3` the PWA reload, `7d4c0b79` PLAN). Reviewed against the implementer's brief, the security
review (`docs/review/security-review-2026-10-21-server.md` §M1, §M2), `CLAUDE.md` and `server/CLAUDE.md`.

## Summary

The three parts do what the brief asks, and the main paths are well tested. M1's explicit deletes (new password,
Reset Two-Factor Login) are right and pinned. The per-subscription epoch closes the race between a subscribe in
flight and a recovery action. The migration is safe. The backup prune is careful shell: NUL-separated names, `rm`
limited to `snapshots/<basename>` and the two patterns, regular files only, `10#` for leading zeros, 0 keeps all.
I checked it in a sandbox with names holding spaces and newlines, `-backup-x.zip`, the values `abc`, `014`, `3 `,
`99999`, a missing line and a symlinked `snapshots/`, and it did the right thing every time. The reload predicate is
pure and only reached on GET (`render_device` has one caller, the summary's GET; `attention.rs` builds the card only
for counts).

There are two Medium findings. Q-1: a subscription the epoch check has doomed still shows as "on" until the next
event's send pass. That is exactly the case of the flagged decision (a re-auth lockout), and a probe test confirmed
it. Q-2: the new reload drops the "Sent. …" flash after 3 s. Four mutations survived (Q-3..Q-6).

Suites: server 1294 passed, 0 failed; `cargo fmt --check` clean; `cargo clippy --all-targets` has the 13 known
warnings and no new ones; jstest 148/148.

## Findings

### Q-1 (Medium): a subscription from a signed-out session still shows as "on" until the next event is sent

- **Evidence:**
  - `push::forget_stale` runs only inside `send_due` (`server/src/push.rs:461`). `send_due` runs at start, when an
    event wakes it, or when a retry is due (`run_sender`, at most an hour apart).
  - The readers ignore the epoch:
    - `push::subscriptions` (`push.rs:1115`, Notification Settings' device list and its health)
    - `push::active_endpoint_hashes` (`push.rs:1213`, This Device's `data-subscribed`, `handlers/notifications.rs:333`)
    - the `MAX_SUBSCRIPTIONS` cap count in `subscribe` (`handlers/notifications.rs:862-869`)
  - New password, Reset Two-Factor Login, Sign Out Everywhere, a parent's Reset (`parents.rs:215-219`), Remove and the
    CLI all delete the rows outright, so they are not affected.
  - These do not delete: the re-auth lockout (three wrong passwords, `admin_auth::record_reauth`, `admin_auth.rs:204`)
    and its DB-error sign-outs (`:196`, `:221`). After them the rows stay until an event arrives.
- **Probe (temporary test, reverted):**
  - Steps: subscribe, `bump_epoch`, sign in again, GET `/notifications/settings`.
  - Result: `data-subscribed` still contains the old endpoint's hash. This Device says notifications are on, and the
    device list shows the phone.
  - The next event's pass then deletes the row without a word, so that event, an emergency call too, reaches nobody.
    The parent has no reason to turn notifications on again. Stale rows also count toward the 10-device cap until
    then.
- **Fix (either works; the first is smaller):**
  - (a) In `record_reauth`'s signed-out path (just before `session.flush()` at `admin_auth.rs:225`), call
    `crate::push::forget_subscriptions(db, admin.id)`, as Sign Out Everywhere does. Every deliberate sign-out then
    deletes at once, and `forget_stale` stays the backstop for the race.
  - (b) Also, or instead, make the readers treat an older epoch as gone. Add
    `AND s.session_epoch >= (SELECT a.session_epoch FROM admin_users a WHERE a.id = s.admin_id)` to `subscriptions`,
    `active_endpoint_hashes` and the cap count, or call `push::forget_stale` at the top of the Notification Settings
    handler and of `subscribe`'s transaction.
  - Add a test: after three wrong passwords at Show Child PIN, sign in again. Notification Settings lists no device
    and `data-subscribed` lacks the hash.

### Q-2 (Medium): after Lock again or Unlock the "Sent. …" flash vanishes after 3 s

- **Evidence:**
  - The press redirects to `/devices/{id}?notice=start_pause_sent#quick` (`handlers/window_relock.rs:259`), and the
    landing page now loads `auto-reload.js`.
  - Its install drops every `notice=` from the address at once, not only a refusal's: the filter at
    `static/auto-reload.js:47` keeps any part whose name isn't `notice`/`app`.
  - The first reload, 3 s later, renders without `RelockCard::notice`. "Sent. The phone unlocks for 2 hours within
    seconds when online." was the only confirmation. Before this branch it stayed until the parent left the page.
  - With the phone offline, the page reloads for 2 minutes looking as if nothing was sent, and invites a second press.
    The tests never look at the flash after a reload.
- **Fix:** keep the confirmation while the page waits. For example, when `wait_for_phone` holds and the newest
  command is unanswered, set `RelockCard::notice` from that command (`flash("start_pause_sent")` /
  `flash("end_windows_sent")`), or show a calm "Waiting for the phone…" line in `#quick`. Extend
  `the_summary_reloads_until_the_phone_shows_lock_again_or_unlock`: the reloaded page (no `?notice=`) still says
  "Sent." while unanswered. Don't change `auto-reload.js`'s dropping (other pages rely on it, `jstest`).

### Q-3 (Low): Lock again's Play install mode branch is not tested (mutation survived)

- **Evidence:**
  - Every Lock again case in `window_relock::tests::the_summary_waits_for_the_phone_after_lock_again_or_unlock`
    (`window_relock.rs:1263`) passes `paused == open`.
  - Replacing `open` with `paused` in the `END_WINDOWS` branch (`window_relock.rs:894`) passes every test. That
    mutation ignores Play install mode, the one way `open` differs.
- **Fix:** add `(vec![answered(lock.clone(), true)], false, true, true)`, which is install mode still on at the newest
  report, and `(…, false, false, false)` is already there.

### Q-4 (Low): the integration test's 2-minute stop passes for the wrong reason; the 2 minutes are pinned nowhere

- **Evidence:**
  - `tests::window_relock::the_summary_reloads_until_the_phone_shows_lock_again_or_unlock`
    (`tests/window_relock.rs:1005`) moves only the newest command back 2 minutes.
  - That makes the Lock again pressed seconds earlier (answered, reported) the "newer of the two", so
    `waiting_for_phone` is false whatever the window.
  - `WAIT_FOR_PHONE_MS = 3 * 60 * 1000` survives every test. The unit test's boundary cases are written with the
    constant itself (`window_relock.rs:1323`).
- **Fix:**
  - In the integration test, move every command of the device back (`WHERE device_id = ?`), or end with a fresh phone.
  - In the unit test, assert `WAIT_FOR_PHONE_MS == 120_000`, or use literals for the boundary cases.

### Q-5 (Low): migration 0095's backfill is not tested (mutation survived)

- **Evidence:**
  - Dropping the `UPDATE` in `migrations/0095_push_session_epoch.sql:7` passes the whole suite (1294).
  - Without it, every existing subscription of an account whose epoch is above 0 gets 0. That is any parent who ever
    changed a password, signed out everywhere or was reset, so on most real servers those rows are silently deleted
    at the first send pass after the update.
  - The migration is right today, but nothing keeps it right.
- **Fix:** a test in the pattern of `tests/parents.rs:1647`:
  - Run the migrations up to 94.
  - Insert an admin with `session_epoch = 3` and a subscription.
  - Apply 95.
  - Assert the row has 3 and is sent to.

### Q-6 (Low): `backup_sync.sh`'s fallback for an invalid `RETENTION_COUNT` is untested and differs from the server's

- **Evidence:**
  - Replacing the fallback (`deploy/install.sh:816`, the same in `update.sh`) with `RETENTION_COUNT=0` passes every
    test. The test only runs `0` and `2`.
  - The behaviour also differs from the server's:
    - An unparseable value: `BackupScheduleConfig::retention_count` (`handlers/backups.rs:106-112`) returns `None`,
      so the server prunes nothing and keeps all, while the drive keeps 14.
    - A value with spaces (`3 `): the server trims and keeps 3; the drive keeps 14.
  - The server writes the file itself, clamped to 0..=3650, so only a hand-edited file differs. The brief asked for
    14, so the behaviour is as briefed.
- **Fix:**
  - Add a garbage-value case to `the_backup_sync_keeps_as_many_on_the_drive_as_the_server`: 16 zips and `abc` leave
    the newest 14.
  - Optionally say in the script's comment that an invalid value keeps 14 on the drive although the server then keeps
    all. Or treat it as 0 (keep all) to match the server; the coordinator's call.

### Q-7 (Low): the drive is pruned also while "Back up to external drive" is off

- **Evidence:** the prune block (`deploy/install.sh:827-830`) is outside the `EXTERNAL_COPY_ENABLED` check. A parent
  who turned the copy off but left the drive mounted still has its old `snapshots/` cut to the count at every run.
- **Assessment:** this is the privacy-preferred choice (the retention windows hold there too) and matches DEPLOY's
  "The external drive keeps as many as the server". It is just not stated. **Fix:** one clause in DEPLOY.md's new
  bullet: "also while the copy is off, as long as the drive is mounted".

### Q-8 (Nit): the comments and log line name the wrong cases for `forget_stale`

- **Evidence:**
  - `push.rs:315-317` (`forget_subscriptions`' doc), `notifications.md:104`, and the comment of
    `a_subscription_from_a_signed_out_session_is_not_sent_to` (`tests/web_push.rs:1192`, "a parent's Reset … which
    delete nothing themselves") say a parent's Reset reaches the subscriptions only through `forget_stale`. But
    `parents::reset_parent` deletes them in its own transaction (`parents.rs:215-219`).
  - `forget_stale`'s log line (`push.rs:340`) lists "a new password, a 2FA reset or Sign Out Everywhere". All three
    delete explicitly. What actually reaches `forget_stale` is a re-auth lockout, a DB-error sign-out, or a subscribe
    racing a recovery action.
- **Fix:** name those in the doc, the note, the test comment and the log line.

### Checked, no finding

- **Migration:**
  - `ADD COLUMN … NOT NULL DEFAULT 0` plus a correlated backfill is safe on existing rows. `admin_id` is a NOT NULL FK,
    so the `COALESCE(…, 0)` only covers orphans.
  - `migrations_73_to_95_in_order` names 95 and its description, and the docs' references to the old test name are
    all renamed.
  - Rows from sessions signed out before the update keep living (unknowable), as the migration comment says.
- **SQL:**
  - `forget_stale`'s deliveries go with it: `push_deliveries.subscription_id … ON DELETE CASCADE` (0078), and the pool
    runs with `foreign_keys(true)` (`main.rs:1509`). The new test checks for orphans.
  - The upsert writes `excluded.session_epoch`, so a re-subscribe refreshes the epoch.
  - `admin.session_epoch` is the account's, equal to the session's (`security.rs:418`).
  - A subscribe that authenticated before a concurrent bump writes the old epoch and is deleted at the next pass:
    the safe direction. No race lets a row get a newer epoch than its session.
  - A subscription "made during a re-auth lockout" can only be one in flight at the lockout, so the same applies
    (plus Q-1's listing lag).
  - `forget_stale` failing aborts the pass (`?`), like the `UPDATE` after it, so it fails closed. The extra
    `DELETE` per pass is negligible next to that `UPDATE`.
- **`forget_subscriptions` in `update_account_password` and `reset_totp`:** called only after the `UPDATE`
  succeeded. If it fails (only logged), the epoch check still deletes this session's row too, which is now older.
- **Forced first password change (`change_first_password`):** in practice it reaches no subscription. Every path that
  sets `must_change_password` (invite, a parent's Reset, the CLI) has already deleted them.
- **Shell:**
  - The quoting is right throughout.
  - `-printf '%f\0'` with `sort -z`/`tail -z`/`read -d ''` handles spaces and newlines.
  - `LC_ALL=C sort -z -r` matches Rust's byte order for `list_backup_filenames`/`startup` (zero-padded `{:04}`
    versions, `-uploaded-` zips counted on both sides).
  - `rm -f -- "$EXTERNAL_MOUNT/snapshots/$name"` can only name a top-level entry, and the drive is root's alone (700).
  - No mount means an early `exit 0`.
  - A symlinked `snapshots/` is deleted by the earlier `! -type f ! -type d` sweep before the prune; the `-L` guard is
    belt and braces. The sandbox confirmed its target is untouched.
  - `RETENTION_COUNT` is read with the same `timeout 5 grep -m1` as the other keys; under `pipefail` a missing key
    falls to `|| true`.
  - The heredocs in `install.sh` and `update.sh` are identical (`update_sh_writes_the_same_root_scripts_as_install_sh`).
  - No `WATCHER_SCHEMA_VERSION` bump is needed: `update.sh` rewrites the root scripts on every update.
- **Template and predicate:**
  - The script loads only under `{% if relock.wait_for_phone %}`.
  - The flag is false with no command, for `allow_blocked`, after a cancel, a refusal (`succeeded = false`) or 2
    minutes, and once the newest report shows the effect. An older launcher's answer without a flag counts as success.
  - Two presses in one second tie on `requested_ms`; `max_by_key` takes the last, and the rows are ordered by id.
- **`auto-reload.js`:** the change is comment-only (`git diff` shows 2 comment lines), so `?v=4` stays right.
- **User rules:**
  - No save jumps to the top: the reload keeps the place (`keepPlace`) and the hash, and pauses while a `<details>`
    is open, and the press still lands on `#quick`.
  - The fixtures are fake (`QK1-fake-…`, `correct horse battery staple`).
  - The texts are English and plain.
  - Nothing changed in `launcher/` or `music/`.
- **Docs and PLAN:**
  - True apart from Q-7 (the unstated case) and Q-8 (the wrong examples).
  - DEPLOY's "about 14 days past its window" is right.
  - The Backups page sentence is hidden under Docker, where there is no drive sync.

## The flagged decision: should the epoch also end notifications after a re-auth lockout, a parent's Reset and the forced first change?

**Recommendation: keep one epoch (the implementer's choice), on the condition that Q-1 is fixed.** No separate push
epoch.

- **What the epoch decides:** a parent's Reset, Remove, the CLI, a new password, Reset 2FA and Sign Out Everywhere
  already delete the subscriptions explicitly. The forced first change has none to reach (above). So the epoch only
  adds two things: the re-auth lockout (and its DB-error sign-outs), and the race of a subscribe in flight during a
  recovery action.
- **Why notifications should end on a lockout:**
  - Three wrong passwords at a re-auth form are the strongest sign the codebase has that someone holds a session
    without knowing the password, which is exactly M1's attacker.
  - With a separate push epoch bumped only by the recovery actions, an attacker who turned notifications on with a
    stolen cookie and then tripped the lockout would keep receiving the kid's events. That is the leak M1 closes.
  - It matches the wording every sign-out uses ("every other browser and phone is signed out").
- **The cost is a nuisance, not a new attack:** someone with a session can already end every device's notifications
  with Sign Out Everywhere. The parent's own phones lose notifications too, but that is the same as after Sign Out
  Everywhere.
- **What makes the cost acceptable is Q-1:** today the deletion is invisible. After a lockout the parent signs in
  again, sees "on", and loses the next event, an emergency call too. Delete at the lockout (Q-1 fix a) so the page
  shows "off" at once. Optionally make the sign-in page after `reauth_signed_out` say "notifications were turned
  off".
- **A second epoch costs more:** another column, a second bump at six sites, and drift between the two for no gain
  in safety.

## Mutation pass

Each mutation was applied alone, the named filter (or the full suite) run, then the file restored (`git checkout`).
The worktree is clean afterwards.

| # | Mutation | Result | Caught by |
|---|---|---|---|
| 1 | drop `forget_subscriptions` in `reset_totp` | caught | `web_push::a_new_password_and_a_2fa_reset_hold_no_subscription` |
| 2 | drop `forget_subscriptions` in `update_account_password` | caught | same |
| 3 | `forget_stale`: `session_epoch <` → `>` | caught | `web_push::a_subscription_from_a_signed_out_session_is_not_sent_to` |
| 4 | drop `forget_stale` from `send_due` | caught | same |
| 5 | `subscribe` binds epoch 0 | caught | same |
| 6 | `waiting_for_phone` ignores a refusal | caught | unit `the_summary_waits_…` + integration `the_summary_reloads_…` |
| 7 | `WAIT_FOR_PHONE_MS` = 3 min | **NOT CAUGHT** (Q-4) | - |
| 8 | 2-minute boundary `>=` → `>` | caught | unit `the_summary_waits_…` |
| 9 | Lock again judged by `paused` (install mode ignored) | **NOT CAUGHT** (Q-3) | - |
| 10 | ignore `cancelled` | caught | unit `the_summary_waits_…` |
| 11 | an unanswered command doesn't wait | caught | unit + integration |
| 12 | template loads `auto-reload.js` always | caught | integration `the_summary_reloads_…` |
| 13 | script keeps N+1 | caught | `deploy_scripts::the_backup_sync_keeps_as_many_on_the_drive_as_the_server` |
| 14 | script prunes `backup-*` (wrong pattern) | caught | same |
| 15 | script sorts ascending (keeps the oldest) | caught | same |
| 16 | script: 0 no longer keeps all | caught | same |
| 17 | script: an invalid value falls back to 0, not 14 | **NOT CAUGHT** (Q-6) | - |
| 18 | script: no `-type f` | caught | same (the `backup-dir.zip` directory) |
| 19 | migration 0095 without the backfill (full suite) | **NOT CAUGHT** (Q-5) | - |

Score: 15 of 19 caught. All four survivors are test gaps in otherwise correct code.

## Suite results (worktree `qa-pwa-fixes-a`, `CARGO_TARGET_DIR=~/.cache/handy-target/qa-pwa-fixes-a`)

- `cargo test`: 1294 passed, 0 failed.
- `cargo fmt --check`: clean.
- `cargo clippy --all-targets`: 13 warnings, all the known ones (models.rs dead fields, `dns_engine.rs` doc list,
  `devices.rs:1324` `sort_by_key`, `storytel.rs` `chunks_exact`). No new ones.
- `node --test jstest/*.test.js`: 148 passed.

## Verdict

**Merge after fixes:**

- Q-1 and Q-2 (Medium).
- The test gaps Q-3..Q-6 (Low, each a few lines).
- Q-7's clause and Q-8's wording (cheap).

Nothing needs a redesign.
