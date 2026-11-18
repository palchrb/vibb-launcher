# Integrations behind one boundary (the pilot: map links, design 110)

Decided by the user on 2026-11-17 ("Dette høres bra ut, la oss gjøre det slik"): an integration - something this
server does with an outside service for the phones - lives inside one boundary from its first day. Map links (design
110) is the first; Spotify, Storytel, Sonos and NRK move in later, each adding to the trait only what it needs.

## The shape

- **Code**: `server/src/integrations/<name>/`, behind the Cargo feature `<name>` (in `default`, so a normal build has
  it; `cargo build --no-default-features` leaves every integration out). Nothing outside `src/integrations/` names it.
- **The trait** (`src/integrations/mod.rs`, `Integration`): `name()`, `device_routes()` (merged into the device API
  behind the device token, `device_routes.rs`), `admin_routes()` (merged into the admin router behind the session,
  `main.rs`), `health()` - and three hooks with do-nothing defaults: `policy()` (its top-level policy key, flattened
  into `PolicyResponse.integrations`), `intake()` (its part of a status report, from `StatusReportRequest`) and
  `apps_group()` (its group on Device › Apps, rendered by the integration; the page prints `integration_groups` after
  App push and knows nothing else).
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
  `format`, `echo`, `range`, `far`, `text`, plus `unreachable` and `busy` from `resolve.rs`).
- `resolve.rs`: `POST /api/devices/map-links/resolve`, the hops, the lookup, the 5 s deadline, the gate (`busy` at
  once for a third caller), 30 resolves per phone an hour, 100 calls out an hour, the 10-minute cache, the counter.
- `page.rs`: the Apps group, its line (`server.json`'s `pwa_lines`) and the save (`#map-links`).
- No capability gates `map_links` in the policy (the user, 2026-11-18: no backwards compatibility on the phones yet).
- Not built here: the Settings hub's 7-day counts by outcome (§8.4) - the counter is there for C2's generic health
  page to show.
