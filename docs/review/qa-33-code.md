# QA 33 (code) - the Locate map's tiles through the server (OSM tile proxy)

Reviewer: code QA, 2026-10-13. Branch `osm-tile-proxy`, one commit, `7a4806a8` ("Server: the Locate map's tiles
through the server (design 33)"). I read it against:
- the root and `server/CLAUDE.md`;
- `docs/design/33-osm-tile-proxy.md`;
- OSM's tile usage policy as published on 2026-10-13 (<https://operations.osmfoundation.org/policies/tiles/>);
- the code it builds on: `music_net::PublicResolver`, `retention::run_pruning`, `AppState`, `build_admin_router`
  (where the route sits), `security::require_full_auth`, `admin_auth::admin_headers`, `ADMIN_CSP` and
  `no_store_path`, `device_routes()`, the session layer (tower-sessions 0.14: it saves only a modified session, so a
  tile costs two reads and no write), and the vendored Leaflet 1.9.4.

I read all of `server/src/tiles.rs` and `server/src/tests/tiles.rs`, and the diff of every other file. Line numbers
are `7a4806a8`'s. No product code was changed: the mutations below were temporary and reverted, and `git status` was
clean before this file was written.

**Verdict: sound and safe to merge; a small fix round, mostly about how the proxy behaves when OSM doesn't answer,
plus an honest account of the OSM policy.** The security surface is closed:
- **No SSRF.** The URL is built from three checked `u32`s on a fixed host. The client has no proxy, the public-only
  resolver, no redirects, no `Referer` and no cookie store.
- **No traversal.** The cache path is three formatted integers. The coordinate check (`TileKey::parse`, `:72-87`)
  allows digits only, at most 7 of them, `z <= 19` and `x, y < 2^z`.
- **Auth.** The route is inside `admin_routes`, behind `require_full_auth`. It is absent from `device_routes()` and
  from the phone listener, and tests pin both.
- **Bounds.** The body is capped at 1 MB, by `Content-Length` and while streaming. There are 2 upstream fetches at
  a time. The rate buckets are keyed by a session id that only a full login creates. The bucket lock recovers from
  poisoning.
- **Failures leak nothing.** The 429/502 answers are empty. Transport errors drop the URL (`without_url`), and
  upstream header text in the log is ASCII cut to 500 chars.
- **CSP.** `img-src 'self' data:` loses nothing: Leaflet's markers are under `/static/leaflet/images`, and no other
  template names OSM.

What needs fixing:
- **M1:** the docs say the server follows OSM's tile usage policy. The policy as published today says the opposite
  on two points: OSM doesn't recommend such a proxy, and a proxy must not strip the `Referer`. Every install also
  shares one non-contactable identity. The user's referrer decision can stand, but the docs must say so, and an
  operator needs a way out if OSM blocks the shared User-Agent.
- **L1/L2:** the upstream fetch is tied to the browser's request. A stale tile waits for OSM (10-20 s each, two at a
  time, when OSM is unreachable), nothing backs off after a failure, and a request Leaflet cancels throws away a
  fetch OSM already served.

Counts: 0 High, 1 Medium, 5 Low, 4 Nits.

## What I ran (own target dir `server-target-qa33`, under the shared build lock)

- **On `7a4806a8`, unchanged:**
  - `cargo test`: **693 passed, 0 failed**;
  - `cargo fmt --check`: clean;
  - `cargo clippy --all-targets`: no warning in a touched file. The 13 warnings are all pre-existing, in
    `models.rs`, `dns_engine.rs`, `handlers/devices.rs` and `storytel.rs`.
- **One request to OSM**, `GET https://tile.openstreetmap.org/0/0/0.png` with an identifying UA, to see what it
  sends. The answer was `200`, `image/png`, with:
  - `cache-control: max-age=521381, stale-while-revalidate=604800, stale-if-error=604800`;
  - `etag: "..."`, `age: 196116`, `expires: ...`;
  - HTTP/2, and no `Last-Modified`.

  So in practice the 7-day floor sets the freshness, revalidation is by ETag, and both upstream fetches share one
  h2 connection (well inside "2 connections").
- **Mutation runs** (`cargo test --bin kid_phone_server tiles`, 18 tests). One build per row without a batch letter; rows with
  the same letter ran as one build, each with its own expected test, so a mutation that survived is still visible:

  | Batch | Mutation (`server/src/tiles.rs`) | Result |
  |---|---|---|
  | - | coordinate bound `x < side && y < side` -> `<=` (`:86`) | red: `coordinates_follow_the_zoom`, `bad_coordinates_are_404_without_a_fetch` |
  | - | PNG signature check off (`:415`) | red: `an_answer_that_isnt_a_png_is_refused` |
  | - | content-type check off (`:403`) | red: the same |
  | - | rate: `tokens >= 1.0` -> `>= 0.0` (`:342`) | red: `a_session_over_its_rate_gets_cached_tiles_only` |
  | A | prune loop `total <= limit` -> `<` (`:613`) | red: `the_cache_bound_prunes_the_least_recently_fetched_tiles` |
  | A | clock skew guard dropped from `is_fresh` (`:238`) | red: `a_stamp_far_in_the_future_is_stale` |
  | A | a 304 forgets the old ETag (`:432`) | red: `a_stale_tile_is_revalidated_and_a_304_renews_it` |
  | A | `UPSTREAM_PARALLEL` 2 -> 3 (`:42`) | red: `requests_for_one_tile_fetch_it_once_and_osm_sees_two_at_most` |
  | B | over the rate with a stale copy -> 429 (`:458-461`) | red: `a_session_over_its_rate_gets_cached_tiles_only` |
  | B | no re-read under the stripe lock (`:452-456`) | red: `requests_for_one_tile_...` (6 fetches, not 1) |
  | B | route moved from `admin_routes` to `public_routes` (`main.rs`) | red: `a_signed_out_request_never_reaches_osm` |
  | B | temp files pruned at any age (`:582`) | red: `the_cache_bound_prunes_...` |
  | C | refill no longer capped at `burst` (`:328`) | **survives** |
  | C | full buckets never evicted (`:334`) | **survives** |
  | C | streaming 1 MB cap off in `HttpTiles` (`:212`) | **survives** |
  | C | `.referer(false)` dropped (`:155`) | **survives** |
  | C | prune order by the PNG's mtime instead of the sidecar's (`:593-595`) | red, but by chance (L5) |

  Batches A and B: each turned exactly its expected tests red, 4 of 4. Batch C: only the prune test went red. Its
  failure is at the "kept" assertion, which is the prune-order mutation. `git status` after the runs: only this
  file.

## Medium

### M1. The docs claim OSM-policy compliance that the published policy contradicts, and every install shares one identity with no way out if OSM blocks it

**Where:**
- `server/src/tiles.rs:1-6` (module doc), `:49-54` (`USER_AGENT`) and `:155` (`.referer(false)`);
- `docs/design/33-osm-tile-proxy.md:14-16`;
- `server/CLAUDE.md:81` ("per OSM's tile usage policy");
- `server/DEPLOY.md:607` ("as OSM's tile usage policy asks").

**What the policy says** (fetched 2026-10-13, quoted):
- "We generally **do not recommend** putting your own caching proxy in front of `tile.openstreetmap.org`. If you do,
  it **must**: Set a clear, contactable **User-Agent** identifying the organisation/service. Honour server caching
  headers or the minimum 7-day TTL rule above."
- "For websites, ensure the `Referer` header is present and accurate end-to-end. If you proxy tile requests through
  your servers or a CDN, do not **strip or blank the `Referer`**."
- Under the don'ts: "Strip `Referer` on web traffic or tunnel all clients behind a single, anonymous identity."

**What the code does:**
- It sends no `Referer`. That is deliberate (the user's decision 2026-10-13: the tailnet hostname and device ids
  never reach a third party).
- Its UA is `VibbPhoneServer/<version> (self-hosted; +https://github.com/palchrb/vibb-launcher)` for **every**
  install. It names the software, not the operator, so it is not "contactable" in the policy's sense.

**Failure:**
1. One install misbehaves: a parent's script pans the map, a bug loops, or the install sits behind a shared
   address OSM is already unhappy with.
2. OSM's only lever on our side is the shared UA. It blocks it (the policy: "blocked without notice").
3. From then on every Vibb server in every family gets a 403 for every tile not in its cache. That is a 502 to
   Leaflet: a grey map on the Locate page, the page a parent opens when a child is missing.
4. The host is a constant (`TileKey::url`, `:89-94`), so an operator can't point the map anywhere else without a
   new release.

**Fix:**
- **Docs:** say what is true. The proxy honours OSM's caching, connection and bulk rules and identifies the
  software. By the user's decision it sends no `Referer`, which the policy asks proxies not to strip, and OSM
  doesn't recommend proxies at all. Same wording in the module doc, the design note, `server/CLAUDE.md` and
  `DEPLOY.md`, so the risk is known and the decision is on record.
- **Identity per operator:** an optional `.env` value, e.g. `MAP_TILES_CONTACT=mailto:...` or a URL (validated as
  visible ASCII, at most 100 chars), appended to the UA: `VibbPhoneServer/0.x (self-hosted; +https://github.com/...;
  contact ...)`. Without it, the UA stays as now. This lets OSM tell installs apart and reach the operator, as the
  policy's "contactable" asks, without a `Referer`.
- **A way out:** an optional `MAP_TILES_URL` template (https only, `{z}/{x}/{y}` placeholders, fixed at startup)
  for another provider or a self-hosted renderer. The CSP needs no change since the browser still asks `/tiles/`.
  Leaflet's attribution would then need to follow the provider; a single "Map data © OpenStreetMap contributors"
  stays right for any OSM-based provider.
- Ask the user whether they want either knob now. The doc correction is needed regardless.

## Low

### L1. A stale tile waits for OSM, and nothing backs off after a failure: with OSM unreachable the map loads at two tiles per 10-20 s

**Where:** `server/src/tiles.rs:443-507` (`Tiles::get`): the stripe lock (`:450`), the token (`:457`), the
semaphore (`:468-475`) and the fetch. The client's timeouts are at `:156-157`.

**What happens:**
- A stale tile is served only after OSM answers or the fetch fails. With OSM unreachable by a dropped route
  (`DEPLOY.md:606` now tells operators to open an outbound firewall for it), each attempt costs the 10 s connect
  timeout. A stalled transfer costs up to 20 s.
- The semaphore lets 2 through at a time, and the same-stripe requests queue behind the lock.
- Every stale tile has to try. A failure writes nothing, so the next view tries again.

The Locate page is opened rarely. After a week every cached tile is stale, so a visit during an outage means a
typical 20-30-tile view fills in over roughly 2-3 minutes. That is on the page a parent uses to find a child.

**The same shape for a block:** after OSM answers 403 (M1) or 429, every view of a tile not in the cache asks OSM
again at browsing pace, and each answer spends a rate token. A server that keeps asking after a block is what the
policy's "may block access" is about.

**Fix:**
- **Serve stale at once when OSM allows it, and revalidate in the background.** OSM sends
  `stale-while-revalidate=604800` and `stale-if-error=604800`. Store them in `TileMeta`. A tile within
  `fresh + swr` is served at once, and a spawned task (deduplicated on the stripe lock) revalidates it.
- **A server-wide backoff in `Tiles`:** an `AtomicI64` "no upstream until" set after a network error, a timeout, a
  403, a 429 or a 5xx. Start at 1 min and double up to 1 h; honour `Retry-After`. Reset it on any 200 or 304. While
  it runs, a stale copy is served and a missing tile gets 502 at once, with no request, no token and no wait.
- **Tests:** with `CannedTiles::delay`, a stale tile answers before the delay ends. After one `Err`, the next missing
  tile gets 502 with `count()` unchanged.

### L2. A request Leaflet cancels drops the upstream fetch half-way: OSM's work and the token are spent, nothing is cached

**Where:** `server/src/tiles.rs:468-487`. The fetch, `judge` and `store` run inside the request's future.

**What happens:**
- Leaflet 1.9.4 cancels unloaded tiles all the time. `_abortLoading` on every zoom, and `_removeTile` for tiles
  panned out of view, set `img.src` to an empty image. The browser then cancels the request: it closes the
  HTTP/1.1 connection, or `tailscale serve` passes on the HTTP/2 reset.
- hyper 1.11 notices EOF on a busy HTTP/1 connection (`mid_message_detect_eof`, `allow_half_close` false by
  default) and drops the connection, and with it the handler future.
- If the future was inside `self.fetch.fetch(...)`, OSM has served (or is serving) the tile and the token is gone,
  but nothing is stored. Viewing that area again fetches it again.
- A zoom-in, zoom-out sequence over an uncached area does this for every tile that was in flight.
- If the drop lands between the two `write_atomic`s, the new PNG is left with the old sidecar. That is harmless:
  stale validators get a 200.

**Fix:**
- Run the upstream part as a detached task that finishes whatever the request does: `tokio::spawn` the
  fetch + judge + store, holding the stripe guard (`Arc<tokio::sync::Mutex<()>>` + `lock_owned()`) and the permit
  (`Arc<Semaphore>` + `acquire_owned()`), and have `get` await its `JoinHandle`.
- Test: start `get` with `CannedTiles::delay(200)`, drop it after 50 ms (`tokio::time::timeout`), wait 300 ms, and
  assert that the tile is on disk and `count() == 1`.
- With L1's background revalidation this is one mechanism.

### L3. A cached tile is never checked when read: a 0-byte or torn PNG left by a power cut is served as fresh for 7-30 days

**Where:**
- `server/src/tiles.rs:350-359` (`read_cached`): no check of the bytes;
- `:362-377` (`write_atomic`): write and rename, no `sync`.

**What happens:**
- On ext4 (the Pi's SD card), `auto_da_alloc` flushes a file's data before a rename only when the rename
  *replaces* an existing file. A tile written for the first time is renamed over nothing.
- So after a power cut (the Pi has no battery, and pulling the plug is how a Pi Zero is "shut down") the directory
  entry can survive with 0 bytes or a torn file. The sidecar is written right after, so it is usually just as new.
- The server then serves an empty or broken `image/png` with `private, max-age=86400` until the sidecar goes stale:
  7 days at least, and up to 30. The browser caches the broken copy for a day on top.

**Fix:**
- In `read_cached`, treat a PNG that doesn't start with `PNG_SIGNATURE` as missing. Delete it and its sidecar, so
  the request fetches it again. That costs nothing: the bytes are already in memory.
- Optionally check that the length is at least 8 + 25 (signature + IHDR) or that the data ends with `IEND`.
- `sync_data` before the rename is the stronger fix, but it is costly on SD cards and not needed with the check.
- Test: a 0-byte `1/0/0.png` with a fresh sidecar is fetched again, not served.

### L4. One unreadable directory stops the whole prune, so the 200 MB bound quietly stops holding

**Where:** `server/src/tiles.rs:568` and `:572`: `std::fs::read_dir(z_dir.path())?` and
`std::fs::read_dir(x_dir.path())?`.

**What happens:**
- Any error reading a `z` or `x` directory returns `Err` from `prune_cache`, and nothing is pruned that hour. The
  error is logged at `retention.rs:149-150`.
- **Transient case:** the docs say "deleting `data/tiles` is always safe" (`DEPLOY.md:609`, `server/CLAUDE.md`). An
  operator's `rm -rf data/tiles/15` racing the walk gives `NotFound`, and that hour is skipped.
- **Permanent case:** a directory the service user can't read (a copy restored as root, an `EACCES` after a
  filesystem error) fails every hour. The cache then grows without bound, at up to `300 + 1800/h` fetches per
  session.

**Fix:**
- Skip what can't be read, as the loop already does for `file.metadata()` (`:574`):
  `let Ok(x_dirs) = std::fs::read_dir(z_dir.path()) else { continue };` (and the same for the files). Log once per
  run if any were skipped.
- Optionally remove a `z`/`x` directory left empty after pruning (Nit N4).
- Test: a `chmod 000` subdirectory still lets the rest prune (skip the test when it runs as root).

### L5. The real fetcher and three of the cache's rules are untested: mutating them changes no test

**Where:**
- `server/src/tiles.rs:148-221`: `HttpTiles` is only built in `main`, and its host is a constant.
- `:326-347`: the rate's refill cap and bucket eviction.
- `:590-595`: the prune order by the sidecar's mtime.
- `server/src/tests/tiles.rs:517-574`.

**What happens:** the survivors in the table above (and the prune row) are all of these:
- **`HttpTiles` has no test at all.** That covers the UA on the wire, the missing `Referer`, `If-None-Match` and
  `If-Modified-Since` being sent, the refused redirect, and the two 1 MB caps (`Content-Length` at `:204-210`, the
  chunk loop at `:211-217`). The 1 MB test (`tests/tiles.rs:377-405`) drives `judge` through `CannedTiles`'s
  `too_large` flag. So removing the streaming cap, which is the guard against an endless body, keeps every test
  green, and so does dropping `.referer(false)`.
- **The prune order is pinned only by chance.** The prune-order mutation went red here, but only because the
  five PNGs, written within a few ms, got equal mtimes (the kernel's coarse timestamps), so the sort fell back to
  directory order. `write_tile` writes the PNGs in the same order as their sidecars' ages. On a filesystem with fine
  timestamps, sorting by the PNG's mtime prunes the same three tiles and the mutation survives. The rule that a 304
  renewal (which rewrites only the sidecar) keeps a tile is therefore not really tested.
- **The refill cap** (`.min(self.burst)`) **and the eviction of full buckets** (`MAX_BUCKETS`) are never reached
  (the rate test uses `per_sec = 0`).

**Fix:**
- Give `HttpTiles` a test-only constructor with a base URL and a resolver: `HttpTiles::with_base(base, resolver)`,
  where `PublicResolver` stays the default. Drive it against a loopback hyper server, as `music_net::tests` does for
  the address rules. Assert the request's headers (UA, no `Referer`, the validators), that a 302 is an `Err`/non-200
  without following, and both caps: a `Content-Length: 2000000`, and a chunked body past 1 MB that is cut with
  `too_large`.
- In `write_tile`, give the PNGs mtimes in the reverse order of their sidecars.
- A rate test with `per_sec > 0` that sleeps past a full refill and checks that the bucket holds `burst`, not more.
  A unit test that fills `MAX_BUCKETS` with full buckets and sees them dropped.

## Nits

- **N1. 7 days is the policy's fallback, not its minimum, and `Age` is ignored.**
  - The policy says "Honour server caching headers or the minimum 7-day TTL rule" and "If your cache cannot read
    them, cache each tile for at least 7 days". The docs and `MIN_FRESH`'s comment (`tiles.rs:37`, design
    `:35`) call 7 days "the policy's minimum".
  - Holding a tile 7 days when OSM said `max-age=521381` (6 days) is fine, since OSM's own
    `stale-while-revalidate=604800` covers it. Reword it as "at least 7 days (OSM allows serving stale for a
    week)".
  - `fresh_secs` (`:243-256`) also ignores `Age` (196116 s in the sample) and `Expires`. With the 7-day floor
    this matters only for a `max-age` above 7 days.
- **N2. The answer to the browser has no validator.**
  - The answer is `private, max-age=86400` with no `ETag` (`tiles.rs:525-533`), so after a day the browser
    downloads every tile again in full.
  - Send the sidecar's ETag (or a hash of the bytes) and answer a matching `If-None-Match` with 304. It is a few
    lines and saves the Pi's uplink on every later visit.
- **N3. The fast path reads the PNG twice.**
  - `get` reads the whole PNG before the lock even when the tile turns out stale (`:444`), and then again under
    the lock (`:451`).
  - Read the sidecar first and the PNG only when it is fresh.
- **N4. The rate bound is per session only, and empty directories are never removed.**
  - The per-session rate (`300 + 1800/h`) has no server-wide ceiling. Only a signed-in parent reaches it, so this
    is not a threat, but a daily server-wide cap with one log line (e.g. 5000 upstream fetches a day) would make
    "no bulk downloading" a property of the server rather than of each session.
  - Empty `z/x` directories stay forever after pruning. That is bounded by the x values ever fetched, and
    harmless.

## Checked and fine

- **Concurrency of reads and writes.**
  - The unlocked fast path can read a new PNG with an old sidecar, or an old PNG with a new sidecar. The first
    goes to the locked path; the second serves the previous picture once. Both are harmless.
  - The same-tile serialisation re-reads the cache under the lock (pinned by the `relock_read` mutation).
  - `write_atomic`'s temp names are unique per process and counter, and the prune skips them for an hour.
- **Pruning racing serving.**
  - A prune that deletes a tile mid-request makes the next read a miss.
  - A 304 renewal that lands after its tile was pruned leaves a sidecar without a PNG, which the next prune
    removes.
  - `DirEntry::file_type`/`metadata` don't follow symlinks, so a symlink in the cache is never counted or pruned
    through.
- **304 handling.**
  - A 304 renews `fetched` and the freshness and keeps the old validators when it has none of its own (pinned).
  - A 304 without a cached copy is unusable. That can't happen anyway, since no validators are sent without one.
- **Clock.**
  - A stamp more than a day ahead is stale (pinned by the unit test).
  - A clock that was behind at fetch time only makes a tile revalidate sooner.
  - A huge `fresh_secs` in a corrupt sidecar casts to a negative number and counts as stale.
- **Headers.**
  - `admin_headers` adds CSP/HSTS/etc. and keeps the handler's `Cache-Control` (not HTML, not a no-store path).
  - Validators sent upstream come from `to_str()`-checked header values, so they can't inject a header.
- **Backups.** Nothing zips or copies `data/tiles`.
  - `install.sh`/`update.sh` only `chmod` it (a `find` over at most 200 MB of small files: seconds).
  - Docker keeps it in the data volume.
- **Phone listener.** `the_device_router_serves_nothing_but_the_device_api` now lists `/tiles/1/0/0.png`, and
  `the_phone_listener_has_no_tiles` sends a valid admin cookie to the device router and gets an empty 404.
