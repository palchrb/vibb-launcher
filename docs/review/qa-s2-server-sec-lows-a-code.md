# QA (code) - round S2, server security Lows (`server-sec-lows-a`, 1adaca47..6c6693ec, server 0.55.0)

## Verdict

**Fix first, then merge.** No High. Two Mediums leave two of the ten items evadable: a LAN feed's pictures still reach
any LAN or loopback address through a redirect (L2), and one phone-sent security line (`mock_location_ignored`)
bypasses the hourly budget (L1). Both fixes are small. The seven Lows are about legitimate phones and parents, and two
of them concern the emergency call. Suite green (1382), fmt/clippy clean, jstest 190/190. The browser pass found no
CSP violation on 31 pages. 20 of 21 mutations were caught.

## Findings

### Medium

**M1 (L2). A LAN feed's art can leave "its own host and port" by a redirect, and the LAN judgement is not pinned.**
`music_sweep::lan_art` (`music_sweep.rs:2428`) checks only the picture URL's host and port. The fetch then goes
through the `Reach::Any` client, whose `redirect_policy` (`music_net.rs:343-357`) checks private targets only for
`Reach::Public`. A 302 from the feed's host therefore goes to `127.0.0.1:3100`, the router or `100.100.100.100`.
`feed_is_lan` resolves the name once, and reqwest resolves it again on its own (no `resolve_to_addrs`: the note in
`docs/notes/server/music.md` says so deliberately). A rebinding name that answers private first and public second is
therefore judged LAN at the add and at every check, while it serves an attacker's RSS. The review's blind SSRF stays
open (the parent has to add the feed). **Fix:** for LAN art, use a client without redirects, or a policy that follows
only to the same host and port. Pin `Reach::Any` requests to the addresses `feed_is_lan` judged.

**M2 (L1). `mock_location_ignored` is written outside the phone's line budget.** `zones::mock_rise` inserts its line
straight into `security_events` (`zones.rs:816`). A token that alternates `zone_watch.mock_ignored` 1, 0, 1, 0 gets a
line on every other report: a lower count is taken silently and resets `mock_logged`. The rate limit is the only bound
on that, so L1's "a phone's lines are bounded" doesn't hold. **Fix:** write the line through
`security::record_phone_event` on the same transaction (its text already starts `device {id}:`).

### Low

**L-a (L1). The budget counts parent lines.** `record_phone_event` counts every `security_events` row whose detail
starts with `device {id}:` (`security.rs:166`). At least 15 parent-action lines use that prefix too: `locate.rs:1848`,
`lifts.rs:140`, `devices.rs:2519`, `music.rs:4805`, `wallpapers.rs:480`, ... An hour of setup on one phone (autosave
pages) uses up the phone's 60. A real `app_installed` or `parent_pin_lockout` line is then dropped, and a
`phone_lines_dropped` row blames the phone. **Fix:** add `AND username IS NULL AND ip_address IS NULL` (phone lines
carry neither) to the count.

**L-b (L1). The emergency call: exactly what is suppressed.** (1) A call dated more than 5 min past the server's clock
is dropped for good (`parent_events.rs:921`): once the clock catches up, `previous` already holds that time, so
`at > before` is false and it is never announced (needs a phone clock 5+ min fast at the call; rare with auto time).
(2) The 4th emergency-call event of a phone in a rolling hour is stored without a push (`parent_events.rs:858`), the
21st not at all. **Fix:** clamp a future call to `now` (the 20/h cap bounds a flood); exempt `EmergencyCall` from
`URGENT_PUSHES_AN_HOUR` or give it its own cap of about 10.

**L-c (L4). 200 reports a day is reachable by a legitimate phone.** "Regularly" every 10 min is 144 backstop syncs;
every SSE nudge (a parent's save), zone crossing or series check (`ZoneWatch.kt:177/184`, `ZoneSeries.kt:147`), time
request or install adds one, and every sync posts a report (`SyncRunner` has no minimum gap). Past 200 the reports
between the 200th and the newest collapse into one: the pages read the newest, but `power`'s "newest report before the
boot" (`device_api.rs:2346`) can then read a row hours old. No test guards "the newest `call_policy_v1` row is never
removed" (M13 survived). **Fix:** 500 a day, or the review's rule (a new row only when changed or N minutes passed);
a test that fills 200 rows and then sends a report without the capability after one with it.

**L-d (L3). The ±1 day clamp misdates a phone's queued events.** `BlockedEventLog` keeps up to 200 events across
offline time, and the server keeps them 7 days (`DNS_LOG_RETENTION_DAYS`). An event from 3 days ago shows as "1 day
ago" (`models.rs:1599`). **Fix:** clamp to `[received - DNS_LOG_RETENTION_DAYS, received + 5 min]`.

**L-e (L8). An old saved `http://` address still goes into the QR, silently.** `provisioning::server_url`
(`provisioning.rs:30`) doesn't apply `config::server_url_refusal`, and Phone Setup's GET shows no warning; only the next
save refuses it. An `.env` `PHONE_SERVER_URL=http://<LAN>` is now dropped with a log line only. **Fix:** treat a
refused saved value as unset in `server_url` (the page shows its banner) or show the refusal on GET; a release note.

**L-f (L10). Nothing pins the field tables to the contract.** My check of `server/testdata/*.json`: only
`time_status_report.json` has a `time_state`; all 10 of its keys are in `TIME_STATE_FIELDS` with matching shapes. The
other three files carry neither object, and no contract file has a `call_state`. The tables match
`StatusReportRequest.kt`'s `TimeState`/`CallState` key by key today, but no test fails when the contract gains a key
the table lacks (`a_launcher_report_with_its_zone_shows_the_rules_end` reads only `zone`). **Fix:** that test asserts
the stored `time_state_json` equals the contract's; a `call_state` in a shared contract file; one line in the root
`CLAUDE.md` shared-contract paragraph: a new `TimeState`/`CallState` key is dropped until `device_api::*_FIELDS`
lists it - both in one commit.

**L-g (L2). "Public for good" has no way back.** One public answer for a NAS name makes the feed public forever, and
the music app then refuses its audio. That answer can come from split-horizon DNS, or from a Pi-hole restart that falls
back to a public resolver. The only remedy is removing and re-adding the entry. **Fix:** say so on the entry's card
when `lan` turned false, or let a parent's "Check now" judge it again.

### Nit
- `last_error` is clipped to 100 (`device_api.rs:1112`) while the launcher keeps 300 (`AppEnforcer.kt:766`). The
  exception's message is what gets cut. Use 300.
- `intake_limits.rs:61`: the `battery_exempt` test's doc comment now sits on `dns_events_are_stored_checked` (the new
  tests were inserted between it and its fn).
- The template scan (`admin_auth.rs:1508`) misses `onclick = "..."` (spaces around `=`). Trim around `=`.
- The refused `server_url`'s `autofocus` never fires in Chromium, because the answer's URL has `#setup` (a target
  element suppresses autofocus). The error is visible next to the field (screenshot), so this is harmless.
- `session_check`'s doc says "nothing written". It now writes `signed_in_at` into a pre-0.55 session.
- `server/CLAUDE.md`'s core names neither `SESSION_MAX_DAYS` nor the storage budget. The area notes, `DEPLOY.md` and
  `.env.example` do.
- When a budget's count query fails, the request gets 0 rows left (`zones.rs` `unwrap_or_else(.. 0)`, `device_api.rs`
  dns `unwrap_or(0)`): a transient DB error drops the fixes. Log the error and store them instead.
- `Pruned.failed` reaches only the log, not the Updates page. That is acceptable, but say it in the note.

## Verified (no finding)
- **L1:** counters in the DB by `created_at` (survive a restart), keyed by device id, not IP; `phone_lines_dropped`
  counts itself (at most 61 rows a rolling hour); urgent kinds: `EmergencyCall`, `PinLockout`, `ForeignAccess`.
- **L2:** `is_lan` excludes `0.0.0.0/8`, `::`, mapped `0.x`; public never turns LAN; one lookup per LAN feed check.
- **L3:** `checked` can't panic; unparseable time = arrival; page orders by parsed time; 200-item/512 KiB caps unchanged.
- **L4:** rolling 24 h by server `received_at`, per phone; crash eviction by `reported_at` (re-reports refresh it); the
  About page's list is unaffected.
- **L5:** `script-src 'self'` on every page; `music_spotify::page_csp` derives from `ADMIN_CSP`; `style-src`/`img-src` unchanged.
- **L6:** the sign-in time is set at both steps (`auth.rs:192/367`), stored in the session row (a restore drops sessions
  anyway); a pre-0.55 session starts its clock at the next request (no mass sign-out); `TooOld` flushes to `/login`,
  which lands on Devices like every sign-out (not new); push subscriptions follow the epoch only.
- **L7:** the detail is the fixed "wrong current password" (no password, no length).
- **L8:** `https`, or `http` to IPv4 100.64.0.0/10; tailnet IPv6 isn't accepted and isn't needed (MagicDNS uses https).
  The emulator loop types `http://10.0.2.2:3100` on the phone (`docs/testing/emulator.md` §4, line 202): unaffected.
- **L9:** every former `?` is gone, the statements are straight-line (no loop), each failure is logged per table.

## L4 arithmetic (fixes, 3000 a rolling day)
There is no 30 s interval: the shortest is `LOCATION_INTERVALS` 10 min, and at least 30 min with zones. A day:
"Regularly" 10 min = 144; zones' 30-min heartbeat 48 + series at most 60 (`SERIES_CHECKS_PER_DAY`); crossings and their
confirmations a few dozen; one per parent Locate and per shutdown; `extra_locations` add no rows (re-sends, deduped by
`store_fix`). A heavy day is about 300-400, so 3000 gives about 8x headroom (even a 3-min series all day, 480, fits).

## Tests, lint, CI
Local (qa-s2 target): `cargo test` 1382 passed, 0 failed; `fmt --check` clean; `clippy --all-targets` no warnings;
`node --test jstest/` 190 pass (with `rule-kind.test.js`). CI `server-ci.yml` run 40357599173 on the branch: success.

## Mutations (21, each reverted; worktree clean) - 20 caught, 1 survived
- `a_flood_of_lockouts_is_bounded` caught: M1 line cap removed, M2 61st line logged, M17 urgent push cap removed,
  M18 20/h event cap removed.
- `a_lan_feed_is_judged_at_every_check_and_its_art_stays_home` caught: M3 `0.0.0.0` LAN, M20 `lan_art` without the
  host/port check, M21 the LAN judgement fixed at the first check again.
- Caught one each: M4 `blocked_at` unclamped (`dns_events_are_stored_checked`); M5 over-budget report appended and M6
  crash hashes unbounded (`a_phones_storage_has_a_budget`); M7 `onclick=` on the Type select and M8 `'unsafe-inline'`
  back (`no_template_has_inline_script`); M9 `TooOld` not returned (`a_session_ends_after_its_absolute_lifetime`);
  M10 `http://10.x` accepted (`config::tests::invalid_values_are_treated_as_unset`; the form test has no 10.x case);
  M11 first failure ends the pass (`a_failing_table_leaves_the_rest_of_the_pass`); M12a unknown key kept and M12b
  wrong-typed key kept (`call_and_time_state_keep_their_known_keys`); M14 the 5-minute future check removed
  (`an_emergency_call_is_new_only_once`); M16 `extra_minutes` dropped (`time_state_is_stored_and_shown`); M19 `zone`
  dropped (`a_launcher_report_with_its_zone_shows_the_rules_end`).
- **Survived: M13**, the call-enforcement keeper removed from `replace_over_budget` - the full suite stays green (L-c).

## Browser pass (Chromium 1140 headless, 390 px, light; WebKit is not installed, so no Safari run)
Dev binary on :3197 (`INSECURE_COOKIES`, fresh `data/`); sign-in through the password change and TOTP; one phone
added and enrolled through the API, with a report carrying a location, `call_state` and `time_state`. JS on, all 200:
- Devices; the phone's summary, Time, Lock, Apps, Music, About, Advanced, Filter, Home, Calls, Provision.
- Apps; Locate (Leaflet: 7 panes, the marker drawn) and Locate for the phone; Music, Music Settings, Music Cards.
- Settings hub and every row: Account, Backups, Web Filter, Blocked Activity, Notifications and their settings, Time
  Rules, New Rule, Security Log, Phone Setup, Software Updates, Wallpapers.

`script-src 'self'` was served on every page. Console errors (6, no CSP violation): the sign-in page's expected 401
from `/api/session`; 405 on four POST-only paths I had guessed as pages (`/devices/1/zones`, `/contacts`,
`/wallpapers`, `/settings/calls`); the expected 400 of the refused Phone Setup save.

The rule editor's Type with JS on: calls start off with the default School, stay off on School, turn on with Custom.
With JS off the editor, the summary and Locate render (200). Phone Setup with `http://192.168.1.5` lands on
`/settings/provisioning#setup` (400): the error under the field, the value kept, the field 155 px from the top,
nothing saved (focus: see Nit). Dark mode on the summary and Phone Setup: tokens correct, nothing unreadable.
