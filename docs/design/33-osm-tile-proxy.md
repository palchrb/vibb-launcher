# 33 - Map tiles through the server (OSM tile proxy)

## Problem

The Locate page's map (`server/templates/device_locate.html`, vendored Leaflet) loaded its tiles straight from
`https://tile.openstreetmap.org/{z}/{x}/{y}.png`. The admin pages send `Referrer-Policy: same-origin`
(`admin_auth::admin_headers`), so the browser sent no `Referer` there, and OSM now answers such browser requests
with "403 Access blocked - App is not following the tile usage policy" on every tile.

## Decisions (the user's, 2026-10-13)

- **The referrer policy stays** `same-origin`: the PWA's URLs (device ids, the tailnet hostname) never go to a third
  party.
- **The server fetches and caches the tiles itself.** OSM sees only the server: no parent browser, no tailnet
  hostname, no `Referer`, no cookies. It identifies itself as the tile usage policy asks
  (<https://operations.osmfoundation.org/policies/tiles/>): `User-Agent: VibbPhoneServer/<version> (self-hosted;
  +https://github.com/palchrb/vibb-launcher)`.
- The map keeps the OSM attribution. The admin CSP drops `https://tile.openstreetmap.org` from `img-src`: tiles are
  same-origin now.

## Behaviour

- **Route**: `GET /tiles/{z}/{x}/{y}.png` on the admin listener only, behind `require_full_auth` (a signed-out
  request is sent to `/login` and never reaches OSM). Never on the phone listener. `z` 0..=19, `x`/`y` in
  `0..2^z`, digits only - anything else 404.
- **Answer**: `200 image/png`, `Cache-Control: private, max-age=86400`, `X-Content-Type-Options: nosniff`. 429 when
  the session is over its rate, 502 when OSM gave nothing usable and there is no cached copy.
- **Upstream** (`tiles::HttpTiles`): its own reqwest client (rustls, as everywhere), never the music `Gated` fetcher.
  The same discipline as `music_net`'s public reach: `no_proxy`, `music_net::PublicResolver` (a private DNS answer is
  never connected to), and no redirects at all (the host is fixed; a redirect is a failure). No `Referer`, no cookie
  store. 10 s connect, 20 s overall. Only a `200` whose `Content-Type` is `image/png`, whose body starts with the PNG
  signature and is at most 1 MB (the read stops past it) is kept.
- **Cache** (`data/tiles/{z}/{x}/{y}.png`, plus `{y}.json`: when it was fetched, how long it is fresh, `ETag`,
  `Last-Modified`), both written atomically (temp file + rename). A tile is **fresh** for OSM's `max-age`, at least
  7 days (the policy's minimum) and at most 30; a fresh tile is served without contacting OSM. A **stale** tile is
  revalidated with `If-None-Match`/`If-Modified-Since` when OSM gave validators; a `304` renews it. If OSM is
  unreachable or answers with anything unusable, the stale copy is served. A stamp more than a day in the future
  (the Pi has no RTC) counts as stale.
- **Bound**: at most 200 MB. `retention::run_pruning` (startup, hourly) calls `tiles::prune_cache`, which deletes the
  tiles least recently fetched or renewed (by the `.json`'s mtime) until the cache fits, plus orphaned sidecars and
  temp files.
- **Concurrency**: at most 2 upstream fetches server-wide (a semaphore). Requests for the same tile are serialised on
  one of 64 striped locks and re-read the cache after the wait, so a burst for one tile fetches it once.
- **Rate**: upstream fetches are limited per session (token bucket: 300 at once, then one every 2 s), so a scripted
  client can't bulk-download through us (the policy forbids bulk downloading). Cache hits are not counted. Over the
  rate: the stale copy if there is one, else 429.
- **Backups**: the cache is never backed up. Nothing copies `data/tiles` (backup zips hold the database, contact
  photos and wallpapers by their own prefixes; `backup_sync.sh` copies only the top of `data/backups/` and the live
  mirror). Deleting `data/tiles` is always safe.

## Files

- `server/src/tiles.rs` (new): `TileFetch` (the injectable fetcher), `HttpTiles`, `Tiles` (cache, locks, semaphore,
  limiter), `serve` (the handler), `prune_cache`.
- `server/src/main.rs`: `AppState.tiles`, the route in `admin_routes`.
- `server/src/music_net.rs`: `PublicResolver` shared (`pub(crate)`).
- `server/src/retention.rs`: the hourly cache bound.
- `server/src/admin_auth.rs`: `ADMIN_CSP` without the OSM host.
- `server/templates/device_locate.html`: `L.tileLayer('/tiles/{z}/{x}/{y}.png')`.
- `server/CLAUDE.md` ("Map tiles"), `server/DEPLOY.md` (the server now contacts tile.openstreetmap.org).

## Tests (`server/src/tests/tiles.rs`, a canned `TileFetch`; nothing touches the network)

Auth required; bad coordinates 404; a fetch stores the tile and a cache hit makes no upstream call; a stale tile is
revalidated with its validators and a 304 renews it; upstream down -> stale copy; a non-PNG answer (content type or
bytes) refused; the 1 MB cap; the phone listener 404; the per-session rate; the cache bound pruning the oldest; the
CSP no longer names OSM; the Locate page points Leaflet at `/tiles/`.
