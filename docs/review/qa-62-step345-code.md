# QA 62 steps 3-5 - code (`design-62-step345`, `6ff8bd48` on `b27316a8`)

2026-10-22. Server only. Inputs: `docs/design/62-parent-accounts.md` (§3-§6, §11, "Steps 1-2 as built", "Steps 3-5 as
built"), `qa-62-design.md`, `qa-62-revision-check.md`, `qa-62-step12-code.md`, `git diff b27316a8..6ff8bd48` (44 files,
+1479/-300).

**Result: no High. 1 Medium, 2 Low (behaviour), 1 Low (tests).** Nothing allows a takeover or enumeration. A removed
account can do nothing, the emergency call reaches every active parent, and 0088 migrates cleanly. `requested_by`
never reaches the device API. Fix P62b-1 before the release (one `AND`). The rest can go in the same fix round.

## Runs

- `cargo test` (full, once): **1196 passed, 0 failed**. `cargo fmt --check`: clean.
- 14 mutations (table below): 10 killed by the committed suite, **4 survive it**, and each of the 4 is killed by a
  QA probe (P62b-4). Every mutation and probe was reverted. The probes were not committed; they are kept in the QA
  scratchpad (`62b-qa/qa_probe.rs`, `web_push_probe.diff`).
- No browser pass. That is the user's (§10). Step 5 there would show P62b-1.

## Checked and sound

- **Step 5 readers:** I found no `read_at` or `notification_settings` anywhere in `src/`, `templates/` or `static/`.
  The inbox count binds `?1` = admin (`UNREAD`) and `?2` = device, in that order. `read_page`'s and Activity's
  `row_from(4)` comes after `?1..?3`, with the admin bound last. All five `Bell::load` callers pass the signed-in
  admin. `mark_opened` inserts through the delivery's own subscription, and `opened` first checks
  `s.admin_id = ?`. The `clicked` receipt goes through the same `mark_opened`. The badge counts through the
  subscription's parent (M11).
- **Push filter:** `JOIN admin_users ... a.removed_at IS NULL`, `LEFT JOIN notification_prefs`, `COALESCE(p.col, 1)`.
  A parent with no row gets every switch on, and the emergency call only passes the removed filter (M1, M2, M10).
  Remove and Reset delete the account's subscriptions, and their deliveries cascade (M13). `save_settings` upserts
  only for an account that isn't removed.
- **0088:** removed accounts get no copies (M14). `DROP COLUMN` needs SQLite 3.35+. The binary bundles it
  (`libsqlite3-sys` 0.30.1, SQLite 3.46, statically linked into the musl tarball and the image), and 0012/0017/0073
  already drop columns. No trigger, view or other index names `read_at`. A pre-0088 backup restored later migrates at
  the next start, behind `pre_migration_backup`, like any older backup.
- **Reset/Remove:** both have `id != actor` (Reset in Rust and SQL; Remove through the "another ready" guard) and the
  actor condition. A refusal is only answered to a re-authenticated parent. The OTP is only in the POST answer: under
  `["account", ..]` no-store, `data-post-answer`, `reveal.js` when `otp` is set, never in tracing or
  `security_events` (`reset_shows_a_new_otp_and_ends_the_sessions` checks the log). Self-removal answers `/login`
  after the same guard. The route is admin-only and the answer is right, since the session has just ended.
- **Step 4:** `requested_by` is set by the four inserts (`queue_command`, `queue_own_command`, `queue_install`,
  `queue_pin_command`) and is `None` from a status report. `pending_command` is built from named columns, and its
  keys are pinned `{id, command, requested_at}` (M9). `policy_json_keys_snapshot` alone would not catch the leak,
  because its `pending_command` is null. `Names` covers removed accounts, and `several` counts every row, as §6 says.
- CSRF is the shared `csrf_guard` layer. Every confirm posts to `#parents`, and `Gone` goes to
  `?parents=gone#parents`. The restore confirm has its line.

## Findings

### P62b-1 (Medium) - the inbox's delivery line is still the family's

- **Evidence:** `handlers::notifications::delivery_lines` (`:694`) reads every `push_deliveries` row of the shown
  events, joined to `push_subscriptions` with no `s.admin_id` condition. `delivery_line` then words them: "Delivered
  to iPhone · Safari", "Opened on …", "Pushed to 2 devices · opened on 1", "Not sent". Probe
  `qa_probe_inbox_delivery_line_is_family_wide` fails on `6ff8bd48`. Pappa has `unseen_boot` off and gets no push,
  but his inbox row reads `Delivered to iPhone · Safari`, which is Mamma's delivery.
- **Scenario:** browser step 5 ("only the parent with it on gets the push"). The parent with the switch off reads
  that it was delivered to "iPhone · Safari" and concludes the switch doesn't work. In the other direction, Mamma's
  row says "Opened on iPhone · Safari" (Pappa's tap) while it still has her unread dot, and "Not sent" can be the
  other parent's failure. Both parents usually have the same label, so nothing on the row tells them apart.
- **Fix:** take the viewer: `delivery_lines(db, admin_id, ids)` with `AND s.admin_id = ?`, so each parent sees their
  own devices, as Getting Notifications already does. No push for this parent then means no line. Keep the probe
  as a test, and add a row to §5's table.

### P62b-2 (Low) - Reset's answer reads the target again and can lose the audit line

- **Evidence:** `handlers::parents::reset`, `Reset::Done`, calls `target_of` again after `reset_parent` has
  committed. `Ok(None)` (removed in between) or a DB error returns a bare 500, before `record_security_event(
  "parent_reset")` and before the OTP is shown.
- **Scenario:** Oma removes Pappa while Mamma's Reset of him commits. The Reset happened (new hash, epoch bumped,
  subscriptions gone), but `/security` never says so. If the second read fails on a DB error instead, Pappa is locked
  out with an OTP nobody saw and no log line, and Mamma only sees a bare 500 page.
- **Fix:** `RETURNING invite_expires_at, username, display_name` in `reset_parent` (`Reset::Done { expires_at,
  username, name }`). Log first, then render from what the statement returned, with no second read.

### P62b-3 (Low) - Reset leaves the parent's Spotify login in progress

- **Evidence:** Remove calls `state.spotify.forget_pending(target_username)` and Reset doesn't. The callback
  (`music_spotify::callback`) is session-free by design (a `SameSite=Strict` admin cookie), so a pending login
  completes from the browser that started it, holding only its `spotify_login` cookie, for `PENDING_TTL` (10 min).
- **Scenario:** Pappa's unlocked phone is taken, and Mamma uses Reset precisely to end that browser's access. If a
  Spotify login was started there in the last 10 minutes, the holder can still finish it with their own Spotify
  account.
- **Fix:** `state.spotify.forget_pending(&target.username)` in `Reset::Done`, with a line in
  `reset_shows_a_new_otp_and_ends_the_sessions` (`pending_count`). §4.3's "every session ended" then covers it. The
  same applies to sign out everywhere; that belongs on the backlog and is not this step.

### P62b-4 (Low, tests) - four guards no committed test pins; the race tests run in one order

| Mutation | Survives because | Probe that kills it |
|---|---|---|
| M4b Remove keeps `parent_event_reads` | `remove_ends_sessions_and_push_keeps_history` raises no event, so Pappa has 0 reads either way | `qa_probe_remove_drops_reads` (Pappa reads the inbox first) |
| M5 `reset_parent` without `actor_ok` | No test resets with a stale actor. `reset_vs_remove_one_wins` always runs the Reset first (tokio::join! polls it first, and the second `BEGIN IMMEDIATE` waits), so "removed actor resets" is never reached | `qa_probe_stale_actor_reset_writes_nothing` (`session_epoch - 1` -> `SignedOut`, hash unchanged) |
| M7 `open` reads for every parent | `/notifications/{id}/open` is only tested with one parent | `qa_probe_open_is_each_parents` |
| M8 Mark All for every parent | In `inbox_read_is_per_parent`, Mamma has already read everything when Pappa marks all | `qa_probe_read_all_is_each_parents` (Mamma marks all, Pappa's count stays) |

- **Scenario:** a later edit drops one of these and nothing fails. The worst is M5: a Reset held past the actor's own
  removal or Reset would lock the other parent out, with no ready account left.
- **Fix:** add the four probes (all pass on `6ff8bd48`). In both race tests, also run the pair in the other order
  (sequentially is enough, since the guard is the statement). "Do they really race" then becomes "both orders hold".
  The design's §9 claim that the join "races" them describes only one interleaving.

## Mutations

| # | Mutation | Result |
|---|---|---|
| M1 | `push::queue` without `a.removed_at IS NULL` | killed: `web_push::push_follows_each_parents_switches` |
| M2 | `COALESCE(p.col, 1) = 1` -> `p.col = 1` | killed: 12 `web_push` tests, `push_follows_each_parents_switches` among them |
| M3 | Add without `read_everything` | killed: `parents::inbox_read_is_per_parent` |
| M4 | Remove without the prefs and reads deletes | killed: `parents::remove_ends_sessions_and_push_keeps_history` |
| M4b | Remove without the reads delete only | **survives**; probe kills (P62b-4) |
| M5 | `reset_parent` without the actor condition | **survives**; probe kills (P62b-4) |
| M6 | `Names::requester` ignores the >1 check | killed: `command_by_shows_with_two_accounts`, `security_log_shows_display_names` |
| M7 | the inbox `open` reads for every parent | **survives**; probe kills (P62b-4) |
| M8 | Mark All for every parent | **survives**; probe kills (P62b-4) |
| M9 | `requested_by` in `pending_command` | killed: `command_by_shows_with_two_accounts` (not by `policy_json_keys_snapshot`) |
| M10 | the emergency call behind a switch (`push_low_battery`) | killed: `push_follows_each_parents_switches`, `push_tap_reads_for_that_parent_and_badges_their_count` |
| M11 | the push badge counts reads by anyone | killed: `push_tap_reads_for_that_parent_and_badges_their_count` |
| M12 | `mark_opened` reads for every parent | killed: `push_tap_reads_for_that_parent_and_badges_their_count` |
| M13 | Reset keeps the push subscriptions | killed: `parents::reset_shows_a_new_otp_and_ends_the_sessions` |
| M14 | 0088 seeds reads for removed accounts | killed: `parents::migration_0088_copies_to_active_accounts` |

## Fixes

- P62b-1: fixed in 8936828c - `delivery_lines(db, admin_id, ..)` with `AND s.admin_id = ?`; the probe is
  `tests::web_push::inbox_delivery_line_is_each_parents` (Pappa's row names nothing, Mamma's names hers);
  `a_tap_on_a_notification_marks_it_opened` now expects this parent's "Opened on …", never "Pushed to 2 devices".
- P62b-2: fixed in 8936828c - `Reset::Done { expires_at, username, name }` from the statement's `RETURNING`;
  `parent_reset` is logged before the answer renders; no second read, no bare 500.
- P62b-3: fixed in 8936828c - Reset calls `spotify.forget_pending` for the target
  (`reset_shows_a_new_otp_and_ends_the_sessions` checks `pending_count`).
- P62b-4: fixed in 8936828c - `open_is_each_parents`, `read_all_is_each_parents`,
  `stale_actor_reset_writes_nothing`, `remove_drops_reads`; `remove_each_other_leaves_one` and
  `reset_vs_remove_one_wins` run their join in both polling orders, and `the_pairs_in_both_orders` runs each pair
  sequentially in both orders.
