# Code QA: design 95 step 95-T2 (the heartbeat report, gzip)

Branch `traffic-2`, commits c8e71d11 and d491064e against main 072d146e. Read-only review of the code; no local
`cargo test`. CI on the branch's head (workflow_dispatch runs 43519381015 Launcher, 43519377186 Server CI) is green.

## Verdict

The core design is sound. The server expands a heartbeat into the kept body and runs the one unchanged
`status_intake`. Because of that, the expanded body is byte-for-byte the body the phone would have sent, provided two
things hold: the phone's SHA-256 covers every field outside `HEARTBEAT_FIELDS`, and both sides use the same list. Both
hold at this commit, and the contract test pins the two lists to each other. So today:

- no stale non-heartbeat field can be expanded;
- no event can be double-raised or lost compared with the old behaviour, where every sync sent an identical full
  report.

The one real hazard is that the list is not negotiated across versions (M1). Two smaller issues come with it: the kept
body is stored raw (M2), and the docs are out of date (L1). None of these blocks a merge today. M1 should be fixed
before any change to `HEARTBEAT_FIELDS`, and 95-S is the next step that touches the report.

## Medium

**M1. `HEARTBEAT_FIELDS` is a cross-version contract with no negotiation. A skew gives a stale value (one way) or a
field that flaps to absent (the other way).** Verified by reading; the failure itself is inferred, because no deployed
pair has differing lists yet.
`server/src/status_heartbeat.rs:31` (`HEARTBEAT_FIELDS`), `:89` (`without_heartbeat_fields`), `:105` (`overlay`);
`launcher/app/src/main/java/com/kidslauncher/mdm/server/StatusReports.kt:39`.

The phone hashes and heartbeats by its own list. The server keeps and overlays by its own. `status_heartbeat.json` pins
the two lists equal only within one commit. The server (Watchtower) and the launcher (tags) roll out independently, and
the `server_version` check guards only against a body kept by another server version, never against another launcher
version. Design 95 §3.5 already names one more heartbeat field (`sink`), and step 95-S will add to the report.

- **Launcher list ⊃ server list (new launcher, older server).** Take a field F in the launcher's list only. The hash
  leaves F out. The full report carries F, and the server keeps F in the body because F is not in its list. Each
  heartbeat carries the new F, but `overlay` takes only the server's list, so the expanded report has the **kept, stale
  F** for up to 6 h. Example: a launcher with `sink` in its list against server 0.78. The parent sees the
  Vibb-only-data state of the last full report, and any event keyed on F's change fires late or never.
- **Server list ⊃ launcher list (new server, older launcher).** Take a field F in the server's list only. The launcher
  hashes F, so a change to F does send a full report. The server keeps the body *without* F. The launcher's heartbeat
  does not carry F, because its list lacks it. So every expanded report has **F absent**: each heartbeat row stores F
  as unknown, and the next full report has it again. That flapping feeds any previous-row diff on F (a "changed" event
  per flip), and the PWA shows "unknown" between full reports.

Fix: make a mismatch fall back to full reports. The cheapest option is that the heartbeat names its list
(`"fields": [...]`, or a list version), and `take` answers `{"full_report": true}` unless it equals the server's list.
Alternatively, the policy carries the server's list and the launcher heartbeats only while it equals its own. Either
way, add a contract row with a differing list and the full-report answer.

**M2. The kept body is the raw, unsanitised report. It bypasses M7/L10's "stored only within limits / known fields
only", and it is kept before the intake has taken the report.** Verified.
`server/src/status_heartbeat.rs:89` and `:145`; `server/src/handlers/device_api.rs:1316` (keep, called before
`status_intake` at `:1318`).

`device_report_base.body_json` is whatever the token holder posted, up to `DEVICE_BODY_LIMIT` (512 KiB inflated):

- unknown top-level keys;
- uncapped `installed_apps`;
- `lock_state`, `call_state`, `update_fence` and the other blobs before `sanitize_lock_state` / `known_fields` /
  `sanitize_*` run.

The comment at `device_api.rs:1380` ("only the known fields are kept, so no unlock times or PIN material can be stored
whatever a launcher sends") is no longer true of the database as a whole. For today's launcher the content mirrors what
`device_status` stores, and nothing in it is a secret (I checked the DTO: hashes, flags, counts, package names; logos
are hashes). The practical leak is therefore small. But this is a regression of a stated security property, and the row
is written even when `status_intake` then refuses the report.

Fix:

- cap the kept body (for example 64 KiB; over the cap, keep nothing, so that phone gets no heartbeats);
- drop the top-level keys `StatusReportRequest` does not know (harmless, because the typed intake ignores them anyway);
- keep only after `status_intake` answered 2xx.

## Low

**L1. The design's text still specifies the copy-row heartbeat.** Verified. Two places contradict the build:

- `docs/design/95-connectivity-control.md:650-658` (§3.5): `report_hash` column, "inserts a copy of that row", "runs
  none of the previous-row diffs", heartbeat fields `battery`/`time_state`/`lock_reason`/`data_roaming`/`sink`;
- `:869` (§8 step 2's sub-bullet "Server: `report_hash`, the heartbeat path (copy the row, no diffs, `full_report`)").

§8's "Decided in the build" paragraph describes the expansion correctly, but the bullets under it and §3.5 say the
opposite. Fix: mark §3.5's server bullet as superseded, or rewrite it, and correct the §8 sub-bullet.

**L2. Kept bodies past `MAX_AGE_HOURS` are never used and never pruned.** Verified.
`server/src/status_heartbeat.rs:44`, `server/src/retention.rs`. A phone that goes quiet keeps its raw body (M2)
indefinitely, until its next full report or its deletion. The latest `device_status` row also stays, but sanitised.
Fix: a `DELETE FROM device_report_base WHERE stored_at < datetime('now','-7 hours')` in the retention pass.

**L3. Tests that would not fail if the behaviour broke.** Verified by reading.

- `server/src/tests/status_heartbeat.rs:216` ("Without a token nothing is inflated"). Request decompression is lazy:
  the body is only inflated when the extractor reads it. `require_device_token` never reads the body, so this
  assertion returns 401 whatever the layer order. The order is correct by construction (a method-router layer inside
  `Router::layer(require_device_token)`), so this is only a weak test. To make it real, assert on something only an
  inflate would cause, or drop the claim from the comment.
- No server test for the `server_version` or `MAX_AGE_HOURS` refusals: removing `b.server_version = ?` or the age
  clause (`status_heartbeat.rs:123-124`) fails nothing.
- `StatusReportsTest` never covers a heartbeat's 4xx (ack dropped) or 5xx/throw (ack kept), or a changed `owner`
  (token or server URL). Removing `ack.owner == owner` (`StatusReports.kt:94`) or the `in 400..499` clear (`:155`)
  fails nothing.

**L4. No server-side off switch.** Inferred. `status_heartbeat: true` and `status_gzip: true` are constants
(`device_api.rs:606-607`). If expansion misbehaves in the field, the only remedy is a release. A config or env switch
that sends `false` would turn every launcher back to plain full reports at its next policy fetch, and costs one line.
This is optional.

## Checked and found fine

- **Hash scope and canonical JSON.** `reportHash` covers `reportJson(report)` minus the list. The full body is that same
  `JsonObject` plus `hash`, and the server keeps exactly that minus `hash` and the list. Differing key order or app
  order can only cost a full report, never make different reports hash equal. serde_json's round-trip of the kept body
  is exact for every type in the DTO.
- **Every `StatusReportRequest` field against the list.**
  - Fields that move between idle syncs (`time_state`, `battery`, `server_tls.seen.at_ms`, `location`,
    `extra_locations`, `zone_watch`) are all heartbeat fields.
  - The rest are event timestamps or state that changes only with the event: `boot_cover.last_*_at_ms`,
    `dns_upstream.since_ms`, `lock_state.backoff_until_ms`, `call_state.last_emergency_call_at`, `music_state`'s
    byte counts while downloading. Each changes the hash and sends a full report.
  - `offline_override_used` is cleared after a 2xx, so it changes the hash too.
- **Events (app installs, zones, power and battery, emergency call, unseen boots, foreign access, the Child PIN
  re-queue, wanted logos).** The expanded body equals the body the phone would have sent, so the intake sees exactly
  what it saw before this branch, when every sync was a full report. Zones and `server_tls.outside` ride in the
  heartbeat, and the launcher drops them only after the 2xx, as before.
- **Re-enrollment.** The launcher's `Enrollment` clears the ack, `owner` hashes the URL and token, and the server checks
  `stored_at >= enrolled_at`. Both columns use SQLite's `datetime('now')` format (`device_api.rs:101`), so the string
  comparison is sound.
- **Deletion, a second phone, a server upgrade.** Deletion: `ON DELETE CASCADE`, with `foreign_keys(true)` in
  `main.rs:1932`. A second phone: one row per `device_id`. A server upgrade: `server_version` gives one `full_report`
  round trip.
- **Privacy of the kept body.** The location-bearing fields (`location`, `extra_locations`, `zone_watch`,
  `time_state.zone`, `data_roaming.country`) are all heartbeat fields, so none is kept (but see M2 for the raw rest).
- **Ack logic.** A lost 2xx on a full report leaves the old ack, whose hash differs or whose 6 h has passed, so the next
  sync is full. A lost 2xx on a heartbeat leaves the ack, and the next heartbeat expands or gets `full_report`. At worst
  a sync costs one extra round trip; the phone can never be stuck sending heartbeats the server can't expand. Clock
  skew only shortens the window (a negative age means a full report; the server's 7 h is independent of the phone's
  6 h).
- **Compatibility.**
  - New launcher, older server: no policy key, so no hash, no heartbeat, no gzip.
  - Older launcher, new server: no hash, so the kept row is deleted each report.
  - A heartbeat that reached an older server anyway is a 422, because `kiosk_engaged` is required, and the launcher's
    4xx path clears the ack.
- **Decompression.** `RequestDecompressionLayer` sits on the status route alone, inside `require_device_token`. It
  inflates only an exact `gzip`, removes `Content-Length`, and refuses other encodings with 415. Axum's
  `DefaultBodyLimit` applies to the inflated stream, and the bomb test, which would be a 400 without the layer, gets
  its 413. The launcher sends `Content-Encoding` only under the policy's `status_gzip`, and only over 1 KiB.
- **BREACH.** `gzip_answers` covers `/apps` (ids, names, tags, relative URLs, SHA-256s), `/dns-blocklist` (categories
  and domains) and `/weather*`. None carries a secret or a token. The policy, enroll, tokens, the stream, images and
  APKs are untouched. On the admin listener, the outer `CompressionLayer` skips an answer that already has
  `Content-Encoding` (tower-http 0.7 `future.rs:70`), and its predicate needs `Compressible` anyway, so nothing is
  double-gzip'd.
- **Contract and CI.** `status_heartbeat.json` is byte-compared on both sides, both lists are pinned to it, and the
  launcher pins the hash literal. Both workflows' path filters name the file.
