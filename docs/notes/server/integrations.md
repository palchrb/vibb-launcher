# Integrations behind one boundary (the pilot: map links, design 110)

Decided by the user on 2026-11-17 ("Dette høres bra ut, la oss gjøre det slik"): an integration - something this
server does with an outside service for the phones - lives inside one boundary from its first day. Map links (design
110) is the first; Spotify, Storytel, Sonos and NRK move in later, each adding to the trait only what it needs.

## The shape

- **Code**: `server/src/integrations/<name>/`, behind the Cargo feature `<name>` (in `default`, so a normal build has
  it; `cargo build --no-default-features` leaves every integration out, and `server-ci.yml`'s build job checks that
  it compiles so). Nothing outside `src/integrations/` names it, with two stated exceptions (code QA L7): the status
  report's wire field (`StatusReportRequest.map_links`, raw `Value`, and its name in `status_heartbeat::REPORT_KEYS`) -
  the report is one struct the heartbeat expands, and a flattened map there would change how every other field
  deserializes; and the core's snapshot tests (`policy_json_keys_snapshot`, the migrations' order, the app lock's
  router walk), which pin the default build. A featureless build compiles; its tests aren't run.
- **The trait** (`src/integrations/mod.rs`, `Integration`): `name()`, `device_routes()` (merged into the device API
  behind the device token, `device_routes.rs`), `admin_routes()` (merged into the admin router behind the session,
  `main.rs`), `health()` - and four hooks with do-nothing defaults: `policy()` (its top-level policy key, flattened
  into `PolicyResponse.integrations`), `intake()` (its part of a status report, from `StatusReportRequest`), `setup()`
  (its part of design 114's setup steps, `SetupPart`: the need rule's inputs it turns on and its steps' readings;
  `Integrations::setup` joins them for every `setup_status` caller) and
  `apps_group()` (its group on Device › Apps, rendered by the integration, given the page's design 114 warning lines
  for its own section; the page prints `integration_groups` after
  App push and knows nothing else).
- **No capability**: the trait's `policy` takes no capabilities (map links' key is always sent, the user's 2026-11-18
  rule); a later integration that needs a claim adds the parameter.
- **State**: `AppState.integrations` (`Integrations`), one field per integration under its feature, each with its own
  client, gate, cache and limits. The tests build it with `Integrations::with_source(fetch)` (the canned fetch).
- **Tables**: its own migration and its own table names (`map_link_devices`, `map_link_lookups`; migration 0159). No
  column on a core table.
- **Contract**: its own folder, `server/testdata/<name>/` - `contract.json` byte-identical in
  `launcher/app/src/test/resources/<name>/`, `server.json` the server's alone. Both CI workflows' path filters name the
  folder.
- **Words**: its keys in the catalogs under its own namespace (`map_links.*`), its template in
  `templates/partials/<name>_group.html` (in `i18n::tests::MOVED`); `i18n`'s key scans read `src/integrations/` and
  each integration's folder (add a new one's folder there).
- **Health** (for C2, design 116): `Integration::health()` gives `Health { last_ok, last_error: (at, code) }` from
  memory; `Integrations::health()` lists every one by name. C2 builds its generic rows on it - the integration keeps no
  Attention row of its own.

## Map links (design 110)

- `links.rs`: `short_links`, `long_hosts`, `feature_ids` (the contract's rows), what a long link says about a lookup,
  and the S2 cell id the `far` check compares.
- `preview.rs`: the lookup's answer read along checked paths and judged (`found`, `disagree`, `consent`, `http`,
  `format`, `echo`, `range`, `far`, `text`, `blocked` - Google's wall -, plus `unreachable` and `busy` from `resolve.rs`).
- `resolve.rs`: `POST /api/devices/map-links/resolve`, the hops, the lookup, the 5 s deadline, the gate (`busy` at
  once for a third caller), 30 resolves per phone an hour, 100 calls out an hour and 10 in any 10 minutes, an hour's pause
  after Google's wall, the 10-minute cache, the counter.
- `page.rs`: the Apps group, its line (`server.json`'s `pwa_lines`; none while design 114's map steps are left - their
  warning lines say it, §2.6) and the save (`#map-links`). `setup_part` (`mod.rs`) is rows 15-16's input and readings.
- No capability gates `map_links` in the policy (the user, 2026-11-18: no backwards compatibility on the phones yet).
- The cache keeps a short link's long link and each place under its own key: a lookup that got no answer is asked
  again on the next tap (code QA M2).
- Not built here: the Settings hub's 7-day counts by outcome (§8.4) - the counter is there for C2's generic health
  page to show.

## The Apple Wi-Fi lookup (design 125 step 2)

The second integration: `src/integrations/wifi_lookup/`, the feature `wifi_lookup`, migration 0163
(`wifi_lookup_devices`, `wifi_lookup_aps`), the contract folder `testdata/wifi_lookup/` (`server.json` written by
`scripts/wifi-lookup-fixtures.py`). It added two hooks to the trait, both with do-nothing defaults:
`location_group()` (its group on the phone's Location page, `handlers::locate::show_location_settings` prints
`integration_groups` after the location settings) and `prune()` (`Prune::Sweep` from `retention::run_pruning`,
`Prune::DeviceDeleted` from `devices::delete_device`). `music_net::Window` (the server-wide rolling count) moved out of
map links for both to use.

- `codec.rs`: the request and the answer by hand (A8) - GrapheneOS's format (MIT, its notice in the file and
  `NOTICE.md`), pinned by `server.json`'s golden hex; the answer read along one path with bounded varints, checked
  lengths, groups refused, at most 200 APs and 256 KB, unknown and out-of-range positions as unknown.
- `cache.rs`: the sealed cache (A5) - HMAC-SHA256 ids and AES-256-GCM rows, both keys expanded from `K` by HKDF
  under their own labels (`data/keys/wifi_lookup`, made at the first lookup, not at start; a new key empties the
  table), months only, a positive row asked again after three months, a negative until the calendar month it was asked
  in ends, the caps (200,000 / 20,000, the least recently
  used out), the lifetime `CACHE_LIFETIME_MONTHS` (24, the user's Q6) and the emptying when no phone with both the
  switch and location on is left (a phone's delete, `Prune::DeviceDeleted`; its location turned off,
  `Prune::LocationOff` from `locate::update_location_policy`; the hourly sweep too, for rows a lookup in flight
  wrote after that).
- **With location off, the lookup is off for that phone** (`is_usable`: the switch on and `location_mode` not `off`):
  the route answers `409 off` (cells too) and the policy's `wifi_lookup.on` is false, so the phone stops asking
  (`contract.json`'s `off_location`). The Location page's switch keeps the parent's choice.
- `route.rs`: `POST /api/devices/wifi-lookup` (A4) - the body checked by hand, the switch, 30 lookups per phone an hour
  (in memory), the answers (`200 {aps, unknown, retry_after_s?}` - only asked BSSIDs, each with `fresh_months_left`
  and never a month; `409 off`; `429 limit|busy` and `503 unavailable` with `retry_after_s` only when the cache
  answered none of them), one Apple call at a time (a second waits 3 s and reads the cache again once the call is its
  own, a third is busy), 60 calls an hour and 10 in any 10 minutes, each phone its share of the hour's calls
  (`route::phone_share`: 60 split between the phones the lookup is usable for, never under 10; in memory; only a
  request that calls Apple counts; past it the phone is answered from the cache alone, as over the server-wide limit), GrapheneOS's throttle (8 surrounding after an answer of 17), the pauses (5 min, then 1 h doubling
  to 24 h; a non-200 or an unreadable answer starts at the hour; a 200 resets) and their log line.
- **The proxy** (`WIFI_LOOKUP_PROXY`, `Proxy::parse`: `socks5h://host:port` only): `SourceRequest::proxy` makes
  `HttpSource` build a one-off client with only that proxy, fresh random SOCKS credentials and `music_net::PROXIED`
  (10 s) to connect and in all; a failure there is the call's (class `proxy`), never a direct call. A setting that
  isn't a proxy address (`proxy_setting`) and a key file the server can't read (`key`) are `Stop` at once: the orange
  line shows without the 24-h grace. reqwest's `socks` feature is hyper-util's own SOCKS client in 0.13: no new crate.
- `page.rs`: the Location page's group (A1; "Oppslag hos Apple" since design 125b) - the switch, the ⓘ (and "The lookups come from the server's own
  address." while no proxy is set), the orange "Apple lookups aren't working" while `integration_health` raises its row
  (key `wifi_lookup`, scope `""`: `Transient` failures past a 24-h grace, or `Stop` at the day-long pause step) and the
  switch is on. The Attention row is `Title::WifiLookup`, mapped to the phones with the switch on (`FOR_DEVICE`).
- **No lookup log** (A6): no device id, BSSID or coordinate in a log line (a source test reads every tracing call in
  the folder), no time or phone in the table; the per-phone counts live in memory. Each lookup logs at debug; the
  hourly sweep logs the hour's counts at info (`Counts`).
- **What a device token still learns** (A6, code QA M3): the route answers only the BSSIDs it is asked for, and only
  how many months their rows stay fresh, so a token holder can learn whether a BSSID it names is in the cache and
  roughly how fresh - never when or by which phone it was asked. The surrounding APs Apple sends are cached and never
  answered.
- Not built (A10): rows sent to a phone ahead of time, GrapheneOS's RANSAC estimator.

## Apple's cell lookup (design 125b, `docs/design/125b-apple-cell-lookup.md`)

The same integration, route, sealed cache and limits; no migration. The PWA's switch is one for both: "Oppslag hos
Apple" / "Lookups at Apple", its (i) naming the mobile network too (C13).

- `cells.rs`: a cell's **wire form** (`Cell::parse`: `<radio>:<mcc>:<mnc>:<area>:<cid>`, `gsm|wcdma|lte|nr`, MCC three
  digits, MNC two or three, no leading zeros, each radio's ranges with 0 and the all-ones fills cut - `cell_forms`) and
  its **canonical form** (MCC and MNC as numbers, `lte:242:1:4711:12345678`: the cache's id and the seal's AAD, so
  Apple's numeric MNC meets the phones' `01` and two spellings are one row); the cell call (GrapheneOS's towers 1, 25,
  27 - WCDMA in SCDMA's fields -, 29, each standard's count `max(1, (total - 19) x its share)`, `total` 25 or 4 while
  throttled, `meta`; pinned by `server.json`'s `golden_cells`) and its answer (fields 1, 22, 23, 24; field 2 and 21
  skipped, never cached; a tower missing a part, negative or out of range left out; accuracy 1..50,000 m; 200 towers;
  the first of duplicates wins; `apple_cells`). `codec.rs` shares its writer and bounded reader with it.
- `route.rs`: a body is `{"bssids"}` or `{"cells"}` (1..12), never both. **No cache oracle** (the user, 2026-11-24): a
  cells 200 is `{"cells": [{cell, lat, lng, acc_m}], "unknown": [cell]}` - no age, no cached mark, each asked string
  echoed; any cells request while Apple can't be asked (paused, over the server-wide limit) is `503 unavailable` with
  its wait, cached or not, and so is an uncached cell's failed call. **Before the cache is read** (code QA H1, M1)
  every cells request takes the admit permit (held to the answer time, `lookup_cells` hands it back), the gate and one
  slot of the 60/10 limits, cached or not; no permit or the gate's wait run out is `503` with 900
  (`CELL_BUSY_WAIT`) - there is no `busy` for cells. The lookup runs in a task of its own; the 200 or 503 leaves
  **exactly** `cell_answer_after` after the request arrived (`mod::cell_answer_after`: the gate's wait + the Apple
  call's whole deadline - `music_net::PROXIED` through the proxy, `Kind::AppleWps` direct - + `CELL_ANSWER_MARGIN`'s
  1 s: 14 s proxied, 9 s direct; code QA M2); a task that hasn't answered by then gives `503` (900) and goes on to fill
  the cache. The **abuse brake**: 1000 cells (canonical, distinct per request) a phone a UTC day (`CellDays`, memory
  only), past it `429 limit` with `retry_after_s` to midnight; checked and counted under one lock with the hour's 30
  asked inside it (`try_add`, code QA L1), so a refused request takes neither. The cell call has its own throttle (10
  towers -> 10 s at 4) and shares the gate, the pauses, `integration_health` and the 60/10 call limits with the Wi-Fi
  call. The sweep line gains `cells=` and `cell_calls=`.
- **What is left of an oracle** (C12, code QA L2): an Apple call that fails during one request with an uncached cell
  (503 where an all-cached one got 200, at the same time). The limits, the gate and the permit no longer depend on the
  cache.
- **Sealing protects cell rows less**: cell identities are enumerable from public lists, so whoever holds both the
  database and `K` can open every cell row cheaply; the database or a backup alone still reveals nothing. The cache
  over two years shows the family's trips coarsely, not only its places.
- Tests (`tests.rs`): the contract's `cell_answers` through the real route (the same body from the cache and from
  Apple, each at the answer time - `CELL_ANSWER_AFTER_IN_TESTS`, 700 ms, over the tests' 300-ms gate; the day cap's
  wait to midnight; `cells_busy` with the gate held), a held gate and two lookups in flight answering cached and
  uncached alike and the permit held to the answer time (H1), a cached request taking a limit slot and a full window
  answering both alike (M1), an Apple call past the answer time answering 503 then and filling the cache (M2), the day
  brake under concurrent requests (L1),
  `golden_cells`, every `apple_cells` row, `cell_forms` and the rules, a row sealed under its canonical form and found
  from Apple's numeric MNC, the surrounding towers cached and an AP in a cell answer never, the cell throttle apart from
  the Wi-Fi one, the source test (no cell identity in a tracing line).
