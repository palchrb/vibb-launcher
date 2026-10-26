# Code QA: round S3, server security Lows - the privacy half (`server-sec-lows-b`)

6f5c1d07 (on main 2e1335b5), server 0.57.0, against security review 2026-10-21 P1-P8. No code changed by this QA.

## Verdict

- **Fix first (one decision, one doc cut), then merge.** The code does what it claims and every claim is tested.
- The real question: P1's "the newest fix ages out too" goes beyond the review and costs Locate a lost phone's last
  position after the window (default 7 days). The owner decides (M1, recommendation below).
- `pwa.md` carries the P8 paragraph five times, four in unrelated sections (L1).
- 0 High, 1 Medium, 4 Low, 4 Nit. 1396 tests pass locally, fmt and clippy clean, jstest 190/190; CI green.
- 13 mutations, all killed (one, m8b, only by the label test - L3).

## Findings

### Medium

- **M1. P1: a lost phone's last position goes after the window** (`retention.rs:256-262`, the newest-fix exception
  removed; `device_locate.html:48`). Default `location_retention_days` is 7 (`retention.rs:16`). A phone that dies,
  is switched off or is out of reach for 8 days shows "No position in the last 7 days." - the shutdown fix of design
  38 / 2026-10-25, built for exactly this, is gone too. The review asked for something narrower: no fix stored while
  Off (built) and the history erased *when the parent turns location Off* (not built: `locate.rs:1880-1895` changes
  the mode and deletes nothing, so the Off phone's history still waits up to its window). Fix: see the recommendation.

### Low

- **L1. The P8 paragraph is pasted into five sections of `pwa.md`** (lines 245 Lock Screen, 349 Apps, 412 Music,
  458 the summary, 598 Locate). Keep the one at 598 (Locate), delete the other four.
- **L2. P2's boot race is real but small, and has a cheap fix** (`retention.rs:274-279` vs `device_api.rs:2357-2363`).
  A phone off for more than 48 h: its pre-off report is kept only while it is the newest. Its first report after the
  boot makes it prunable; an hourly pass landing before the power report costs `phone_on` its "last report" (time,
  battery). The same happens whenever the power report is held longer (server down at boot, the phone's retry). Fix:
  count the 48 h from the phone's own newest report instead of from now
  (`reported_at < datetime((SELECT MAX(reported_at) FROM device_status s WHERE s.device_id = device_status.device_id), '-48 hours')`):
  a returning phone keeps its pre-off rows for 48 h of its own reporting; a silent phone keeps at most 48 h.
- **L3. The window test misses a kind bound before the call** (`tests/cleanup.rs:876-883`: literals in the 5 lines
  from `record_phone_event(` on). `parent_pin.rs:294-298` picks its kind in a `let` above the call; renaming
  `"parent_pin_lockout"` there passes `every_phone_line_kind_has_its_window` (m8b) - only the label test fails, and
  once the new kind gets its label (the obvious next step) it keeps 180 days unnoticed. Fix: scan 6 lines above the
  call too, or give `record_phone_event` a typed kind (`enum PhoneLine`, `fn wire()`) whose variants the test lists.
- **L4. `architecture.md:29` still says "the newest fix always kept"**; fix with M1's outcome.

### Nit

- **N1.** "No position in the last 7 days." also greets a phone never located and one whose location is Off (the Off
  warning sits above it): "No position yet." when the phone has never sent one would read better.
- **N2.** The UA test (`net.rs:466-496`) scans `.user_agent(` calls only: a new `Client::builder()` without one is not
  caught. Harmless today (reqwest then sends no `User-Agent`, `push.rs:84` does so), but say so in its doc.
- **N3.** The Security Log footer (`security.html:65`) states the windows but not the 24 h username clearing; one
  clause ("a wrong sign-in's typed name a day").
- **N4.** A cached fix re-sent under "Regularly" (same `captured_at`, no `still`) ages out and is stored again by
  the next report; only a phone without a new fix for a week - harmless, a line in `retention-and-crashes.md`.

## P1 recommendation (the owner decides)

Keep the **newest fix per phone beyond the window while the phone's location is not Off**, shown with its age
("Last seen 9 days ago", plus "Last position before the phone went off" when it is the shutdown fix), and erase **all**
of a phone's fixes - the newest too - and its `phone_off` positions the moment the parent chooses Off (the location
policy save runs the pass, as the retention save does; the prune's keeper skips `location_mode = 'off'`). Argument:
Locate exists for a lost phone, and a lost phone is precisely the one that stops reporting - it dies, is switched
off, or sits in a drawer at a friend's - and the parent often learns of it late (a holiday, a phone kept at school).
After eight days the implementer's reading leaves nothing; the one row it would keep is the single most useful
location record the server has, and it is one row, not a trail. The privacy cost the review named was keeping it
*after location was turned off* and keeping it without the parent's say; under this rule the parent's Off removes
it at once, and while location is on the parent has chosen to locate the phone. The implementer's reading is
defensible as "the window means the window" and is simpler to explain; if kept, the Locate line should at least
tell the parent to raise the window (14/30 days) for a phone that may be lost.

## P2: every reader of `device_status`

| Reader | Reads | 48 h safe |
|---|---|---|
| About This Phone "Last full report" (`device_pages.rs:498`), Attention, the summary | newest | yes (kept) |
| Status intake's comparisons: `app_updated`, new apps, install mode, foreign access, unseen boots (`device_api.rs:1272-1280`) | newest = previous | yes |
| Calls report / "stopped enforcing" (`calls.rs:627`, `:717`), `kid_lock.rs:96` | newest; any `call_policy_v1` row | yes (that row is kept) |
| `last_capabilities`, `last_zone_watch` (`device_api.rs:1852`, `zones.rs:877`) | newest since enrollment | yes |
| Power off line on the summary (`devices.rs:1431`) | `MAX(reported_at)` | yes |
| Power route "newest before boot" (`device_api.rs:2358`) | newest before the boot | race, L2 |
| Budget `replace_over_budget` (`retention.rs:133-145`) | the previous row, rolling 24 h | yes (inside 48 h) |
| Apps, music, DNS filter, schedules, window relock, locate's wipe-ready | newest | yes |
| Backups / restore | the whole DB; a restore's older rows go at the next pass | yes |

A weekend off (60 h) is fine: the pre-off report stays the newest until the phone reports again (L2 aside).

## P4, P5, P8

- **P4**: RFC 8292 §2.1: `sub` "MAY" be included and "SHOULD" be a `mailto:` or `https:` URI. Apple's documented
  `BadJwtToken` causes include "the JWT subject claim isn't a URL or mailto:"; reports of rejections are for
  unreachable addresses (`admin@localhost`). `https://github.com/palchrb/vibb-launcher` is a real public URL, and an
  `https:` origin was what Apple took before. `subject_of` (`push.rs:283-292`) accepts only `mailto:`/`https://`,
  `env.example` and DEPLOY document it. Keys and the JWT cache (`aud`, `sub`) unchanged: subscriptions keep working.
- **P5**: 9 `.user_agent(` sites, all `net::USER_AGENT` (dns_engine, fdroid, system_update, tracked_apps x2,
  music_net x2, startup, plus the test client); the push transport sets none. Storytel's server client never had a
  special UA (it was the generic "kid-phone-server ..." string), so its login is not at risk; the phone's
  `VibbMusic/<version>` (design 26b L8) is the music app's own and untouched.
- **P8, the Referer fact**: the tile layer sets `referrerPolicy: 'strict-origin-when-cross-origin'` (`locate.js:134`)
  on each tile `<img>`; the page stays `same-origin`. A cross-origin HTTPS tile request therefore carries
  `Referer: https://<pwa-host>/` - the origin alone (the tailnet name), never the path or `?device=`. The tile URL
  carries z/x/y (the area looked at), plus the parent's IP and UA. OSM's tile policy: "Browsers are expected to send
  a valid `Referer` header", "Do not set a restrictive Referrer-Policy", "do not strip or blank the `Referer`" -
  design 33 (2026-10-13) recorded the 403 "Access blocked". `pwa.md:598` says all this correctly. Accepted is right.

## P6, P7

- Windows as stated: `time_lifts` 90 days after `expires_at` (`retention.rs:421-430`), `banned_ips` at expiry (no
  escalation reads an old ban, `security.rs:222-256`), `device_install_progress` past the shared 10 min
  (`devices.rs:1233-1237` reads the same constant). Formats match (`datetime()` text on both sides).
- Phone kinds: every `record_phone_event*` call (`device_api.rs:1434,1682,1709`, `zones.rs:840`,
  `window_relock.rs:362,382,426`, `parent_pin.rs:300`, `music_api.rs:276`, and `phone_lines_dropped` inside
  `security.rs:176`) is in `PHONE_LOG_KINDS`, `PIN_LOG_KINDS` or the deliberate 180-day `storytel_login_sent`.
  Parent kinds (`settings_saved`, `location_*_changed`, sign-ins) keep 180.
- The username clearing (`retention.rs:325-335`) touches `login_failed` only, counted from the event's own
  `created_at`; nothing reads a `login_failed` username (`login_warning` reads `totp_failed`, the IP ban counts by
  IP). The RSS line logs `feed at <host>` (`music.rs:1015-1023`); old rows stay as they are (180 days).

## Docs and version

Each item has its line in `retention-and-crashes.md`, `zones.md`, `notifications.md`, `connectivity.md`,
`play-and-updates.md`; `server/CLAUDE.md` Data model (device_status 48 h, security_events, device_locations) updated;
`VAPID_SUBJECT` in DEPLOY.md and `env.example`. Cargo.toml and Cargo.lock 0.57.0. Stale: L1, L4.

## Tests and mutations

Local at 6f5c1d07: `cargo test` 1396 passed, 0 failed; fmt and clippy clean; jstest 190/190. CI run 40396718339:
success. Each mutation applied alone, its tests run, the file restored (worktree clean afterwards).

| Mutation | Test | Result |
|---|---|---|
| m1 `takes_fixes` always true | `locations::no_fix_is_stored_while_location_is_off`, `power::location_off_keeps_no_shutdown_position` | killed |
| m1b the power route's Off check removed (intake's stays) | `power::location_off_keeps_no_shutdown_position` (the `phone_off` params) | killed |
| m2 the newest-fix keeper put back | `cleanup::pruning_keeps_only_what_retention_allows`, `a_failing_table_leaves_the_rest_of_the_pass` | killed |
| m2b `resent_at` ignored by the prune | `cleanup::pruning_follows_what_each_row_describes` | killed |
| m3 status history 720 h (30 days) | `cleanup::pruning_keeps_only_what_retention_allows` | killed |
| m4 `sub` falls back to the PWA origin | `web_push::a_push_carries_its_headers_...`, `an_http_origin_is_no_jwt_subject` | killed |
| m5 dns_engine back to the old UA | `net::tests::every_client_says_the_one_neutral_user_agent` | killed |
| m6 `banned_ips` never pruned | `cleanup::pruning_follows_what_each_row_describes` | killed |
| m7 `app_installed` out of `PHONE_LOG_KINDS` (180 days) | `every_phone_line_kind_has_its_window`, `pruning_follows_...` | killed |
| m8 a new phone kind at a call (`phone_relock_findings_v2`) | `every_phone_line_kind_has_its_window`, `admin::every_written_event_has_a_label` | killed |
| m8b a new kind in `parent_pin.rs`'s `let` | only `admin::every_written_event_has_a_label` | killed (L3) |
| m9 the username cleared for every kind | `cleanup::pruning_follows_what_each_row_describes` | killed |
| m10 `LIFT_DAYS` = 0 | `cleanup::pruning_follows_what_each_row_describes` | killed |
