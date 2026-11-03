# QA 75 (code, server side): weather on the lock glance, step 1

Branch `lock-weather` at 4fa6bfdb against `main` (`git diff main...lock-weather`), server side: migration 0122,
`weather.rs`, `weather_cache.rs`, `handlers/weather.rs`, `handlers/weather_page.rs`, `config.rs` (`MET_CONTACT`),
`retention.rs`, `locate.rs`, `music_net.rs`/`net.rs`, `device_weather.html` and the phone page's row, i18n,
`testdata/weather.json` and `testdata/weather/`, `server-ci.yml`, DEPLOY.md, the env examples and the notes. Read
against design 75 (incl. §12) and the server design QA (`qa-75-design-server.md`).

Run here: `cargo fmt --check` clean, `cargo clippy --all-targets` clean, `cargo test` 1699 passed, 1 failed:
`tests::time_rules::the_switch_per_rule_migration_keeps_every_phones_rules` (a panic inside a sqlx worker, "index out
of bounds: the len is 10 but the index is 10", under the full suite's load). It passed 3 times out of 3 on its own and
touches no weather code or table; a flake in a migration test, not this branch's. Worth a backlog line if it recurs.

**Verdict: ready to merge once M2 is fixed; M1 wants the architect's nod (it touches §3.2's rule) and can follow.** No High. MET's terms, the
privacy rules, the auth and the source order hold; the rule table, the windows and the dusk are right (the dusk
checked here against a second algorithm). **0 High, 2 Medium, 6 Low.**

## High

None.

## Medium

**M1. The card's current period has no symbol on a cell's first day** (`src/weather.rs:463-466`, `periods`). A
period's symbol is the `sym6` of the hour containing its start, and its temperature that of the hour containing its
middle. MET's series begins at the current UTC hour, so the start of the period now running is always in the past:
it exists only when the cell was fetched before that period began. The contract's own `answer` shows it - at 15:20
the afternoon (the highlighted `current: 1`) is `{"symbol": null, "temp": 7}`, and the morning is all `null`.
Scenario: a parent turns the weather on at 10:30; the card's highlighted Morning column has neither symbol nor degrees
(09:00 is gone) until tomorrow. The same after every Location Off erase (the zone's cell refetched), after 48 h unasked,
and on every new cell (a day at grandma's: the current column always empty). **Fix:** when the hour at the start (or
the middle) is missing, fall back to the period's first hour we have (its `sym6`, else its `sym`; its `temp`); a
period with none of its hours stays all `null`. It changes the contract's `answer` (rewrite with
`WEATHER_CONTRACT_WRITE=1`) and wants one `periods` vector ("first fetch at 15:20"). A design touch (§3.2's rule), so
the architect's nod first.

**M2. An unreadable 200 is retried before MET's `Expires`** (`src/weather_cache.rs:290-306`; the nowcast's at
`:479-482`). A 200/203 whose body doesn't parse, or is cut at 1 MB (`truncated`), or fails to serialise goes to
`failed(.., "unreadable", ..)`: 5, 10, 20, 40 min by failures - while MET said, in that very answer, not to ask before
`Expires` (~30 min). The nowcast's unreadable body retries in 2 min against a 3-min `Expires`. Scenario: MET changes
the `complete` shape or a field type; every install re-asks every cell at +5, +15, +35 min - exactly the "repeat
before Expires" MET's terms forbid, from the shared User-Agent. **Fix:** for an answered 200/203 that we can't use,
`next = max(next_after(response), now + backoff)` (and the nowcast `max(expires floor, 2 min)`); the error code and
the old hours as now. One assertion in `failures_back_off_and_keep_the_old_hours` (a canned 200 `"<html>"` with
`Expires` 45 min → `next_fetch_at` ≥ 45 min).

## Low

- **L1. The Off erase can be undone by work in flight** (`src/weather_cache.rs:586-610`, `:506-511`, `:202-219`). A
  Kartverket lookup spawned just before the parent sets Location Off writes its `weather_places` row after
  `erase` (`store_place` is an upsert); a nowcast fill in flight re-inserts its map entry; and a phone's ask that
  straddles the erase reads its row back with `fetch_one` → `RowNotFound` → 500. Scenario: a phone's first pull in a
  new cell, the parent switches Off seconds later - the cell's place name survives the erase for up to the location
  retention, and into the next backup. **Fix:** an erase generation (`AtomicU64` bumped by `erase`, read when a
  lookup or nowcast fill starts, its write skipped when it moved); `forecast()` treats a missing row after the wait as
  `unavailable`.
- **L2. `lookup_failed` is never pruned or erased** (`src/weather_cache.rs:58`, `:596-601`). Each failed Kartverket
  lookup adds the cell for the life of the process; `erase` clears only the nowcasts. A small in-memory list of cells
  that outlives Location Off. **Fix:** `erase` clears `lookup_failed` (and `looking_up`'s finished entries go anyway);
  drop entries older than `LOOKUP_RETRY_MINUTES` at each insert.
- **L3. A cell outlives a 1-day location history** (`src/retention.rs:477-485`, `CELL_HOURS = 48`). With every phone at
  `location_retention_days = 1` (one of the four choices) fixes go after a day, the cells (a 2 km trail with their last
  ask) after two. D13: the cache "lives no longer than the fixes it came from". **Fix:** prune cells at `MIN(48 h, the
  longest retention)` - in practice only the 1-day case changes.
- **L4. The log test misses the usual leak** (`src/tests/weather.rs:714-717`). It checks a log call's text only up to
  the first `"`, so a format argument after the message (`warn!("cell {}", cell.query())`) or an inline capture
  (`warn!("weather: {url}")`) passes - the two ways a coordinate would most likely get in. **Fix:** check the whole call
  up to its closing `);`, the message's `{...}` captures included.
- **L5. Missing tests for two real risks.** (a) The stampede: every test asks one phone at a time, so "one fetch per
  cell" is pinned only sequentially; the code is right by reading (one lock, the row re-read inside it). Two
  concurrent asks with `fetch.delay(300)` → `met_asks == 1` pins it. (b) Kartverket's edges from §7.2: a cell outside
  the Norway box (no request, `NULL` stored at once) and the 5000 m pass after an empty 2000 m one.
- **L6. DEPLOY.md's `MET_CONTACT` row** (`server/DEPLOY.md:454`): "'Weather on the lock screen' above" - the section is
  below the table; "3-100 printable characters" - printable **ASCII** (`ola.ø@...` is ignored with a log line, as the
  config test pins). Say so in the row, the section and both env examples.

## Verified OK

- **MET's terms.** `next_fetch_at = max(now + (Expires − Date), now + 30 min) + 0-120 s` (MET's clock never compared
  with ours; the test with a year-off `Date`); `If-Modified-Since` = the stored `Last-Modified` verbatim; a 304 moves
  `checked_at` and keeps the hours; 203 served and warned once a day; 429 `Retry-After` as seconds or an HTTP date, at
  least 10 min, doubling to 2 h; 403 6 h with an `error!`; others 5-60 min; the old hours always served. HTTPS only,
  two decimals, gzip (reqwest's). User-Agent `vibb-server/<CARGO_PKG_VERSION> github.com/<SERVER_RELEASE_REPO>` plus
  `MET_CONTACT` (3-100 printable ASCII, no quotes, trimmed, logged when bad), on MET's requests only; Kartverket keeps
  the neutral `vibb/1.0` (tested); the one `warn!` while `MET_CONTACT` is unset; `net.rs`'s doc names the exception.
- **One fetch at a time.** One `tokio::sync::Mutex` for forecasts and one for nowcasts, the row (the map entry)
  re-read inside; the phone waits 6 s (nowcast 3 s) on a spawned task that carries on and fills the row. MET has its
  own `Gated` client, never `music_fetch`'s. Nowcast is fetched only by `/weather/now`; Kartverket only from `place`,
  spawned and not awaited, one in flight per cell, a failure retried after 1 h, 2 + 2 pages, Norway box only.
- **Privacy.** The cell (`lat_i = round(lat/0.02)`, `lon_i = round(lon/0.04)` wrapped, latitude clamped) is made in
  the handler before anything is stored or sent; MET's and Kartverket's URLs and the cache hold only the integers'
  two-decimal form; the answers carry no coordinates (tested); every log line names a row id, a device id or a code.
  Location Off: `update_location_policy` reads the old mode and erases both tables and the nowcasts only on the change
  to Off (H1 of the design QA, tested both ways: a second Off and the hourly prune leave them). `location_mode` has no
  other writer. Retention by age only: cells 48 h, places at the longest `location_retention_days` (tested at 13/15
  days). No step-3 code anywhere (no `Cell::parse`, no 0123, no cell from the phone).
- **Source order (D5).** A real fix (`COALESCE(mock, 0) = 0`), at most 6 h old and not in the future, only while
  Location is `interval`; else the weather zone's centre (`device_zones.id` **and** `device_id` - another phone's zone
  never answers), in every mode; else `no_position`. Tested with mock, 7 h, future, On request, Off and the
  fix-over-zone order (the Trondheim cell).
- **Auth.** Both device routes sit in `device_routes()` behind the bearer and read only the asking phone's policy, fix
  and zone; 20/60 asks an hour per phone with `Retry-After`; no token → 401. The PWA routes are in the admin router
  under `require_full_auth` (and the app lock's router walk); the zone save accepts only this phone's zone (another
  phone's, an unknown id, junk → 400, nothing written); 404 for an unknown phone; logs only on a change, by the zone's
  name. Saves redirect to `/devices/{id}/weather#weather-lock` / `#weather-zone` with `data-autosave` and the
  `<noscript>` button - the save never jumps to the top (`assert_lands` in the test). The notes above the group are
  handled by `scroll-restore.js`'s "a warning came in above it" rule.
- **Zones.** `weather_zone_id ... ON DELETE SET NULL`, the delete nudges the phone (R1, tested); 0122's comment and
  `docs/notes/server/zones.md` warn a future `device_zones` rebuild (R2). A deleted or foreign zone never shows as
  selected (`view` filters by the phone's list).
- **The rules.** The window `[max(07:00, the current hour), 18:00)` today, tomorrow's `[07:00, 18:00)` from 18:00; the
  forms (`now` only at ≥ 07:00, `today` before, `from` with the hour, `tomorrow` drops it); the nine rules in order,
  the day's kind with its ties, the chance arm only on a rain day, `maybe_snow` only on a snow day, missing values as
  nothing, feels-like falling back to the temperature, the high from the window. Every row of `weather.json`'s 27
  `words` vectors (DST days, 17:59/18:00, 23:30, 00:10, rain stopped by 16:30) runs; the design's 8 mutations are each
  caught by an existing vector or test.
- **The dusk.** NOAA after the date's solar noon, `at`/`never`/`all_day`; recomputed here with the Astronomical
  Almanac's low-precision formula (a different algorithm): Oslo 2026-11-03 17:02, Oslo 2026-06-21 → 00:29 on the 22nd,
  Tromsø 2026-12-21 13:53 (noon −3.1°), Trondheim midsummer never, Longyearbyen noon −11.7° → all day. All match.
- **Time zone and answers.** `phone_zone` (renamed from `extra_time_day_zone`, the time-request use unchanged): the
  newest report's `time_state.zone`, else Phone Setup's. Four entries from the UTC hour containing now, `unavailable`
  when none or no hours stored; degrees never −0; `days` for each card date; `checked_at_ms` from the last 200/304.
- **Contract.** `server/testdata/weather.json` is byte-identical to the launcher's copy; the server's
  `the_contract_is_identical_to_the_launchers_copy` and the launcher's `WeatherContractTest` both compare;
  `the_contract` rewrites only computed parts. `launcher_ui.lock_weather` is in `policy_json_keys_snapshot` (false for a
  new phone); `weather_zone_id` never in the policy. `server-ci.yml` gains the launcher's copy; `launcher.yml` the
  server's and the script.
- **i18n.** Every new string (`device.page.weather`, `device.weather.*` incl. the footer and the links,
  `security.event.lock_weather*`, `security.detail.lock_weather*`) is in en.json and nb.json; the row's value uses
  `common.on/off`; `device_weather.html` is in `MOVED`; "Jevnlig" matches Locate's nb word.
- **Docs.** DEPLOY.md (the env row, the section with the privacy sentence, backups), both env examples,
  `server/CLAUDE.md`'s route line, `docs/notes/server/launcher-ui.md` and `zones.md`, NOTICE.md (MET, Kartverket with
  its Geonorge entry, the symbols), `testdata/weather/README.md` (sources, licence, trims).
- **Lean tests.** No duplicated test worth removing; the two integration files are each one behaviour per test. The
  gaps are L4 and L5.
