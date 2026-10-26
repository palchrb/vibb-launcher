# Server notes: retention-and-crashes

Moved verbatim from server/CLAUDE.md on 2026-10-19; the core file's 'Where the rest is' lists every note.

## Retention (cleanup 2026-10-06, `src/retention.rs`, migration `0039_retention.sql`)

- **Blocked-domain log**: `device_policy.dns_log_enabled`, **default off** (the migration deleted every existing
  entry), sent as `PolicyResponse.dns_log_enabled` (always; the launcher records and reports only while it is on).
  `POST /api/devices/dns-events` stores nothing for a phone with the log off (204, an older launcher still sends).
  Since 0.55.0 (security review L3) each event is checked (`DnsEventReport::checked`, the wire shape unchanged): the
  domain only as a lowercased hostname of at most 253 characters (labels of 1-63 letters, digits, `-`, `_`; anything
  else skipped), the category without control characters and at most 64, `blocked_at` as RFC 3339 clamped to the
  log's 7 days before the arrival (an offline phone's queue keeps its times, QA L-d) and 5 minutes after it, stored as UTC `...T...mmmZ` (the arrival when it doesn't parse;
  `intake_limits::dns_events_are_stored_checked`).
  Switch "Keep a log" on the phone's Web Filter page (design 31 step 5; `POST /dns/log/{device_id}`, back to `/devices/{id}/filter`, auto-saving checkbox, missing = off, 404 unknown
  device, nudges, security event `dns_log_changed`); off deletes the phone's log at once. Entries are kept
  `DNS_LOG_RETENTION_DAYS` = 7 (by `received_at`).
- **Location history**: `device_policy.location_retention_days` (1/7/14/30, default 7; was a fixed 30), Locate tab
  `POST /devices/{id}/location-retention` (400 other values, 404, security event `location_retention_changed`,
  prunes at once). By `received_at` (server time), or a still fix's last re-send (`resent_at`, design 64 Q-3: the
  phone's position as of that report). Since 0.57.0 (security review P1) the newest fix ages out too (before, it was
  kept for good, also after location was turned off): a phone with nothing left reads "No position in the last 7
  days." on Locate (the phone's own window, "day" for 1). **Nothing is stored while the phone's location is Off**
  (`zones::takes_fixes` in `zones::intake` - a report's `location` and `extra_locations` - and in the power route:
  the shutdown's position is neither a fix nor in the `phone_off`'s parameters; a phone without a policy row reads
  as "Only when asked", a read that fails stores nothing; `locations::no_fix_is_stored_while_location_is_off`,
  `power::location_off_keeps_no_shutdown_position`). "Only when asked" keeps taking every fix the phone sends:
  the launcher sends one only for an Update Location, at low battery and at shutdown (2026-10-25), each one asked
  for - the review's "only within a window after a `locate`" would drop the last two, so it wasn't built.
- **Status history** (`device_status`: screen time in `time_state_json`, call state, app lists): **48 hours** since
  0.57.0 (`STATUS_HISTORY_HOURS`; was 30 days, which nothing read - security review P2), the newest report per phone
  kept, and the newest one with `call_policy_v1` (the calls warning "stopped reporting that it enforces calls" rests
  on it, qa-cleanup #7). Every page, Attention, the intake's comparisons (`app_updated`, new apps, foreign access,
  unseen boots) and the policy read the newest report; the storage budget counts a rolling 24 hours of them, hence
  two days. The power route's "newest report before the boot" (a start with no shutdown seen) is the newest kept
  until a first report after a long-off phone's start and the prune pass meet before its power report: accepted. There is no server-side blocked-call log; the phone deletes blocked calls from its call log
  after 30 days (launcher `calls/BlockedCallLog`).
- **The security log** (`security_events`): 180 days (`SECURITY_EVENT_DAYS`: sign-ins, refusals, enrollments, the
  parents' changes). Since 0.57.0 (security review P7) a phone's own lines go with what they describe:
  `PHONE_LOG_KINDS` (`app_installed`, `play_install_mode`, `boot_unseen`, `phone_app_blocked`,
  `phone_foreign_access`, `phone_relock_findings`, `mock_location_ignored`, `phone_lines_dropped`) after 30 days (an
  unseen boot's Attention window; the status they come from is 48 hours), `PIN_LOG_KINDS` (`parent_pin_used`,
  `parent_pin_lockout`) after `PIN_EVENT_DAYS` (90, as the PIN events); `storytel_login_sent` (the parent's credential
  handed to a phone) keeps 180. `cleanup::every_phone_line_kind_has_its_window` fails for a kind
  `record_phone_event` writes without one. A wrong sign-in's typed username is cleared after `SIGN_IN_NAME_HOURS`
  (24; a password typed into the name field), its line and address kept; `music_entry_added` names a feed by its host
  only (`feed at example.org`: a private feed's URL may carry a token). The Security Log's footer says the windows.
- **Three small tables** (security review P6, 0.57.0): `time_lifts` `LIFT_DAYS` (90, the inbox's) after the lift's
  `expires_at`; `banned_ips` once the ban expired (nothing reads an expired one; the security log keeps the ban's
  line); `device_install_progress` past `INSTALL_PROGRESS_MINUTES` (10, the window `devices::view_device` reads it
  in). `Pruned.lifts`/`bans`/`install_progress`; `cleanup::pruning_follows_what_each_row_describes`.
- **Commands** (`device_commands`, design 48 §1.6): `COMMAND_DAYS` = 30 by `requested_at`, counted in
  `Pruned.commands` - except one that may still reach its phone: never delivered, never cancelled, never acknowledged
  and not in `models::EXPIRING_COMMANDS` (the pop's rule in SQL). So an undelivered `lock`, `wipe`, `end_windows`,
  `allow_blocked` or `install_update` waits at any age (a lost phone back weeks later is still locked or wiped); a
  cancelled or answered one goes like the rest. The summary's Activity week is a query filter, not retention.
- `retention::run_pruning` (spawned from `main` only) runs `prune` at startup and hourly - it replaced the old
  `run_dns_event_pruning` (60 days) and `run_location_pruning` (30 days). Connections use `secure_delete`. Since
  0.55.0 (security review L9) every statement of the pass runs on its own: one that fails is logged with its table
  and named in `Pruned.failed` (the log only, no page shows it), the rest are pruned all the same (`prune` returns `Pruned`, no error;
  `cleanup::a_failing_table_leaves_the_rest_of_the_pass`).
- **A phone's storage budget** (0.55.0, security review L4, `retention::Budget`, `STATUS_ROWS_A_DAY`, `CRASH_HASHES`):
  what one token can add in a rolling day, far above what a phone sends. Past 500 status reports a day (the shortest location interval alone is 144; QA L-c) each new one
  replaces the one before it (`replace_over_budget`: the newest stays current, never the newest report with
  `call_policy_v1`); past 3000 fixes (`zones::intake`, the newest of a report that fit), 10 000 blocked domains or 500
  parent-PIN events a day the new ones aren't stored (logged); at most 50 crash hashes per phone, the oldest reported
  going first. A count that can't be read is logged and drops nothing. Security-log lines and inbox events have their own hourly budgets (L1). `intake_limits::a_phones_storage_has_a_budget`.
- Tests: `src/tests/cleanup.rs`.

## Crash reports (cleanup 2026-10-06, `src/crashes.rs`, migration `0040_device_crashes.sql`)

- `POST /api/devices/crashes` (bearer): `{crashes: [{hash, trace, count, first_at_ms, last_at_ms, app_version_code}]}`, at
  most 10 (else 400), 204 once stored (the launcher clears its copy only then), 500 on a DB error. `crashes::sanitize`
  keeps a report only with a 16-lowercase-hex hash and drops every trace line that isn't an exception class, `    at
  class.method(File:line)`, `Caused by: class` or `    ... N more` (so no free text - the launcher never sends
  messages), cuts the trace to 4000 chars and clamps counts/times. `device_crashes` is one row per phone and hash
  (counts add up), shown on About This Phone › Diagnostics (newest 5, each a row whose ⓘ has the trace), pruned 30
  days after the phone last reported it (`retention::prune`). It replaced upstream's on-phone crash screen.

## Removed monitoring (2026-10-06)

The conversation journal (`device_journal_entries`, media in `data/journal_media`, `/api/devices/journal*`, the
`/devices/{id}/journal` viewer) and browser history (`device_browser_history_entries`, `/api/devices/browser-history`,
`/devices/{id}/browser-history`) are gone: migration `0038_remove_monitoring.sql` drops the tables, startup deleted the
media directory (`retention::remove_journal_media`, removed with the other one-shot cleanups in 0.54.0), and connections run with `secure_delete` on so deleted rows are
overwritten. So is the MollySocket installer (`deploy/install_mollysocket.sh`) and the launcher's UnifiedPush
distributor it relied on (DEPLOY.md has the removal steps for an existing MollySocket). Principle: collect only what's
needed to manage the phone, delete on a schedule, never store notification or message content.
