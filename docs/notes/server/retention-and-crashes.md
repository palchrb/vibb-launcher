# Server notes: retention-and-crashes

Moved verbatim from server/CLAUDE.md on 2026-10-19; the core file's 'Where the rest is' lists every note.

## Retention (cleanup 2026-10-06, `src/retention.rs`, migration `0039_retention.sql`)

- **Blocked-domain log**: `device_policy.dns_log_enabled`, **default off** (the migration deleted every existing
  entry), sent as `PolicyResponse.dns_log_enabled` (always; the launcher records and reports only while it is on).
  `POST /api/devices/dns-events` stores nothing for a phone with the log off (204, an older launcher still sends).
  Switch "Keep a log" on the phone's Web Filter page (design 31 step 5; `POST /dns/log/{device_id}`, back to `/devices/{id}/filter`, auto-saving checkbox, missing = off, 404 unknown
  device, nudges, security event `dns_log_changed`); off deletes the phone's log at once. Entries are kept
  `DNS_LOG_RETENTION_DAYS` = 7 (by `received_at`).
- **Location history**: `device_policy.location_retention_days` (1/7/14/30, default 7; was a fixed 30), Locate tab
  `POST /devices/{id}/location-retention` (400 other values, 404, security event `location_retention_changed`,
  prunes at once). By `received_at` (server time); the newest fix per phone is always kept (Find My Device's "last
  seen").
- **Status history** (`device_status`: screen time in `time_state_json`, call state, app lists): 30 days, the newest
  report per phone kept, and the newest one with `call_policy_v1` (the calls warning "stopped reporting that it
  enforces calls" rests on it, qa-cleanup #7). There is no server-side blocked-call log; the phone deletes blocked calls from its call log
  after 30 days (launcher `calls/BlockedCallLog`).
- **Commands** (`device_commands`, design 48 §1.6): `COMMAND_DAYS` = 30 by `requested_at`, counted in
  `Pruned.commands` - except one that may still reach its phone: never delivered, never cancelled, never acknowledged
  and not in `models::EXPIRING_COMMANDS` (the pop's rule in SQL). So an undelivered `lock`, `wipe`, `end_windows`,
  `allow_blocked` or `install_update` waits at any age (a lost phone back weeks later is still locked or wiped); a
  cancelled or answered one goes like the rest. The summary's Activity week is a query filter, not retention.
- `retention::run_pruning` (spawned from `main` only) runs `prune` at startup and hourly - it replaced the old
  `run_dns_event_pruning` (60 days) and `run_location_pruning` (30 days). Connections use `secure_delete`. Since
  0.55.0 (security review L9) every statement of the pass runs on its own: one that fails is logged with its table
  and named in `Pruned.failed`, the rest are pruned all the same (`prune` returns `Pruned`, no error;
  `cleanup::a_failing_table_leaves_the_rest_of_the_pass`).
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
