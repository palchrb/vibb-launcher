# 21b - Vibb music: the server sweeps the sources (delta against 21)

Architect, 2026-10-09. The user decisions below override design 21 (and its QA decisions) where they conflict; the rest
of 21 stands. Sources: 21 (§1-§6, QA review, decisions, step 1 status), 21a, the step-1 code (`music.rs`,
`handlers/music{,_api}.rs`, 0049), palchrb/vibb `main` (MIT): `pi/vibb/content.py` (`_catalog`, `_new_episodes`,
`_psapi_episodes`, `_episode_stub`, `_manifest_url`, `_pick_image`, `_parse_feed`, `_feed_episode_id`, `sync_feed`) and
`pi/vibb/library.py` (`_cache_sweeper`, `SYNC_INTERVAL_S`, `SYNC_STAGGER_S`, `SYNC_DELAY_S`, `SWEEP_STAMP`,
`_mark_new_seen`). The behaviour is reimplemented in Rust (GPL-3.0) and credited in NOTICE.md (added in step 1c with
the first ported code) and in `music_sweep.rs`'s header. psapi answers recorded on 2026-10-09 confirm the fields named
below: episodes `episodeId`, `date`, `durationInSeconds`, `usageRights.to.date`; manifest
`playable.assets[0].{url, format, mimeType, encrypted}`.

**User decisions (2026-10-09)**
1. The server is the brain for metadata. It verifies a link on add, lists every NRK/RSS entry, fetches its cover and
   sweeps with the Pi's logic: incremental, sequential, the catalog TTL. It never loads NRK more than one Pi does, and
   one sweep serves all phones. A change nudges only the phones that have the entry ticked.
2. NRK audio URLs are stable, as on the Pi, so the phone never resolves them. The phone reports a failing URL and the
   server re-resolves it.
3. The phone downloads audio straight from the source. The server never proxies or stores NRK/RSS audio.
4. Storytel stays on the phone (phase 2).
5. Positions stay on the phone. Keep/prune (the newest N plus the item in progress) runs there, on the server's list.
6. The sweep cadence is a family-wide PWA setting: every 1, 3, 6 (default, the Pi) or 12 or 24 hours. "Check now"
   stays, rate-limited.

**Moves to the server**:
- 21 §4.3: psapi/RSS listing, manifests, feed parsing, tracking prefixes and the RSS fallback.
- 21 §4.5: the 6 h listing sync.
- New: the PWA cards.

**Stays on the phone**: downloads, keep/prune, Nytt/NY, positions, playback, Storytel.

## 1. Server data, migration `0050_music_sweep.sql`
New tables and an index only. 0049 is never edited.
- **`music_settings`** (singleton, like `call_settings`): `sweep_hours` INTEGER NOT NULL DEFAULT 6, CHECK IN
  (1, 3, 6, 12, 24).
- **`music_listings`**: one row per NRK/RSS entry, written by the entry's first check (own files have none).
  - `entry_id` PK -> `music_entries` ON DELETE CASCADE.
  - `title`: the source's own name (psapi `series.titles.title`, or the RSS channel title).
  - `cover_url`, `cover_hash` (§2.6).
  - `etag`, `last_modified` (RSS).
  - `fallback` (0/1): the list came from NRK's RSS fallback.
  - `version`: 16 hex of SHA-256 over the listing JSON without its version (QA #4's rule). NULL = never listed.
  - The stamps:
    - `listed_at`: the last success (vibb `fetched_at`).
    - `checked_at`: the last attempt.
    - `requested_at`: the last Check now.
  - `error`, `error_at`, `failures` (consecutive).
  - `requests`: how many the last check made.
  - Times are UTC `datetime('now')` text, as in 0049. **No stored due time**: it is derived (§2.2).
- **`music_items`**: PK (`entry_id` -> CASCADE, `key`). Columns:
  - `seq`: 0 = the oldest; this is the listing order.
  - `title`.
  - `url`: NULL = not playable at the source now.
  - `hls` (0/1), `duration_ms`, `art_url`, `published_at`, `available_until`, `resolved_at`.
  - `recheck` (0/1, §2.5).
- **Item keys**, exactly as 21 and vibb define them:
  - NRK: the episode id, i.e. the last segment of `_links.self.href` (vibb `_episode_stub`; the same as
    `episodeId`). For `serie/<slug>/<programId>` it is the programme id.
  - RSS: `sha1(guid)[:12]`, else `sha1(enclosure url as written in the feed)[:12]` (21 §4.3; vibb `_feed_episode_id`).
- **Caps**:
  - NRK: the newest 100 (vibb `MAX_EPISODES`, QA #9).
  - RSS: the newest 1000. vibb keeps all of them; 20 §2.1's 200 would cut the start off a serial feed that is played
    oldest first.
  - A listing is at most 1 MB (`MAX_LISTING_BYTES`). Titles are cut to 200 characters, and an item whose URL is longer
    than 2000 characters is skipped.
- **Covers**: `photos::MUSIC_COVERS` also references `music_listings.cover_hash`. A lost cover is forgotten together
  with its `cover_url`, so the next check fetches it again.

## 2. The sweeper (`server/src/music_sweep.rs`)
One task, spawned in `main.rs` and never in `app()`. Tests drive `due(now)` and `check_entry(now)` with a fake clock
and the canned `music_fetch`. `AppState.music_sweep` holds a `Notify` (`wake()`) and the id of the entry being checked.

### 2.1 The load rule
- **One source request in flight server-wide.** Every request goes through one paced gate, including the parent's
  add check. Request starts are at least 200 ms apart, and entries are 4 s apart (vibb `SYNC_STAGGER_S`). vibb resolves
  manifests 8 at a time; the server resolves them one by one.
- **Steady state** per check:
  - NRK: one `episodes?sort=desc` page, which stops at the first known key.
  - RSS: one conditional GET. The Pi refetches the whole feed on every sweep and every menu open.
  - Manifests only for new episodes. The show root and the cover only on the first fill or when the image URL
    changes.
- **Bounds**: 10 s per request (`FETCH_TIMEOUT`) and 30 s per image. A check that passes 250 requests or 10 min has
  failed (§2.4).
- **Cost of a first fill**: the same as the Pi's first sweep (a 100-episode podcast = root + 2 pages + 100
  manifests), done sequentially instead of 8 wide. 21a's import of N entries means N first fills in a row.
- **One sweep serves all phones**; phones never list a source.
- **At the default 6 h the server matches the Pi.** A shorter setting is the parent's explicit choice, and the PWA
  says it costs more requests (§3).
- **Logging**: each check logs `music sweep: entry 12 "Abels tårn": 1 request, 0 new` and stores `requests`.

### 2.2 Cadence and stamps
- **Intervals from the setting `I`** (`music_settings.sweep_hours`):
  - RSS: every `I`.
  - NRK: every `max(I, min(2·I, 12 h))`. That is the Pi's rule (a 6 h sweep with a 12 h `CATALOG_TTL_S` re-lists NRK
    every other sweep), shortened for shorter settings.

  | Setting | 1 h | 3 h | 6 h (default, the Pi) | 12 h | 24 h |
  |---|---|---|---|---|---|
  | RSS | 1 h | 3 h | 6 h | 12 h | 24 h |
  | NRK | 2 h | 6 h | 12 h | 12 h | 24 h |

- **Due** is a pure function of the stored stamps, the setting and `now`. An entry is due when any of these holds:
  - it has no `music_listings` row (a new or imported entry);
  - `requested_at > checked_at` (Check now);
  - one of its items has `recheck` and `checked_at` is at least 1 h old (§2.5);
  - after a success, `listed_at` plus its source's interval has passed;
  - after a failure, `checked_at` plus `min(15 min · 4^(failures-1), I)` has passed.
- **Restart-safe**: the stamps live per entry in the database (vibb's `SWEEP_STAMP`, but per entry), so a restart
  re-sweeps nothing. A new setting takes effect at the next sweep (the save calls `wake()`) with no stored times to
  rewrite. The first pass runs 60 s after start (vibb `SYNC_DELAY_S`).
- **Order**: entries never checked (in carousel order), then Check now, then rechecks, then the longest overdue. The
  loop sleeps until the next due time (at most `I`) or a `wake()`, then works through everything due, one entry at a
  time.
- **`wake()` is called after**: `add_link`'s insert, 21a's import commit, Check now, a flagged recheck, and a saved
  setting.
- **Per-entry cadence: out.** One setting covers it, and Check now covers a feed the parent is waiting for.
- **What is swept**:
  - `nrk` and `rss` entries, whether or not a phone has them ticked (a Pi sweeps its whole library).
  - Never `own`.
  - `storytel` stays on the phone (phase 2).
  - `spotify` is phase 3 (vibb's oEmbed cover would go here).

### 2.3 One check
A check writes nothing until it has succeeded. Then one transaction stores the items, the listing row and the version.
- **NRK podkast `<slug>`**
  - First fill:
    - root `/radio/catalog/podcast/<slug>` (title and `series.squareImage`; 404/410 = `not_found`);
    - then `episodes?page=1&pageSize=50&sort=desc`, following `_links.next`, up to 100 stubs (QA #9);
    - then the manifests, oldest first.
  - If psapi lists nothing: use `podkast.nrk.no/program/<slug>.rss` as RSS (vibb) and set `fallback`. A fallback list
    tries psapi first at every check, and a non-empty answer replaces the list with a first fill.
  - Incremental: walk `sort=desc` to the first known key, fetch manifests for the new episodes, append them, and drop
    the oldest beyond 100.
- **NRK serie `<slug>`**: the same, with `series` and `manifest/program`. The first fill walks `sort=asc`, because a
  serial story starts at episode 1 (QA #9).
- **NRK `serie/<slug>/<programId>`**:
  - First fill: walk the manifest plus `/playback/metadata/program/<id>` along `_links.next` (vibb `_series`, at
    most 100).
  - Incremental: continue from the last item's `_links.next`. That is one request when nothing is new.
- **A stub** gives: the key, `titles.title`, `date`, `durationInSeconds`, the smallest `squareImage` of at least 300 px
  (vibb `_pick_image`), and `usageRights.to.date` (-> `available_until`).
- **A manifest** (vibb `_manifest_url`):
  - Use `playable.assets[0].url` unless it is `encrypted`. `hls` is set when `format` is HLS or the path ends in
    `.m3u8`.
  - No `playable` (rights, geo-block, not out yet): the episode is not stored, so a newer one is asked for again at the
    next check (vibb).
  - A network error or a 5xx fails the whole check.
  - Once an item is past `available_until`, it gets `url` NULL at the next check.
- **RSS**:
  - GET with `If-None-Match`/`If-Modified-Since`. A 304 means unchanged.
  - A 200 is read up to 20 MB. `MAX_FEED_BYTES` is raised from 5 MB, for the add check too, because of long back
    catalogues.
  - The sweep and the add check share one parser, `quick-xml` (MIT). It is a pull parser, so memory stays about the
    size of the body. It decodes the five entities and numeric references only and refuses a DTD.
  - Items need an enclosure URL (vibb `_parse_feed`). The newest 1000 are taken in feed order (vibb: feeds list newest
    first) and stored oldest first.
  - Channel image: `itunes:image@href`, else `image/url`. Per item: `guid`, `title`, `pubDate`, `itunes:duration`
    (s, m:ss or h:mm:ss), `itunes:image`.
  - Tracking prefixes are stripped from the `url` the phones get (podtrac, chtbl, pdst.fm, op3.dev; 21 §4.3), because
    the DNS filter may block them. The key uses the enclosure as written.
  - The feed replaces the list. Unchanged keys keep their rows.
- **Result**: one function builds the listing (§4.2) from the rows.
  - A new version is stored, then `nudge(phones_with_entry)`.
  - The same version moves only the stamps, with no nudge.

### 2.4 Keep the last good list
- **A failure** leaves the items, version and cover alone. It sets:
  - `error`: `not_found`, `not_feed`, `no_items`, `http_<code>`, `network`, `timeout` or `too_big`;
  - `error_at`;
  - `failures` + 1, which drives the backoff.
- A listing that would be empty counts as `no_items`. A success clears all of this.
- Phones keep what they have. A never-listed entry has `items: null` (§4.1).
- A failed cover fetch never fails the check. The old cover stays, and a missing one is tried again at the next check.

### 2.5 Re-resolve on a phone's report
- **Flagging**: an `item_errors` row (§4.3) flags its item when all of these hold:
  - it names an entry the phone has ticked and a key in that entry's list;
  - its code is anything other than `network`;
  - the item's `resolved_at` is at least 24 h old.

  The flag sets `recheck` = 1 and calls `wake()`.
- **The re-resolve**: the entry's next check (at most 1 h after its last one, §2.2) handles flagged items first.
  - NRK: one manifest per item, at most 10 per check.
  - RSS: the feed check itself; a 304 means the URL is the same.

  Afterwards `recheck` = 0 and `resolved_at` = now.
- **Outcomes**:
  - A new URL -> a new version -> a nudge.
  - The same URL -> nothing; the phone keeps its own backoff.
  - No `playable` -> `url` NULL.
- For NRK, the incremental listing runs too only if its interval has passed or on Check now.
- So an item costs at most one manifest a day, however many phones report it.

### 2.6 Covers
- **Source**: the show image (NRK `series.squareImage`, the smallest of at least 512 px; the RSS channel image).
- **Pipeline**: GET of at most 10 MB (`photos::MAX_UPLOAD_BYTES`) within 30 s -> `photos::process_limited(.., Square)`
  (a 512 px JPEG with metadata dropped) -> `MUSIC_COVERS` (stored under its lock, then pruned) ->
  `music_listings.cover_hash`.
- **When**: on the first fill and whenever the URL changes. vibb fetches it once.
- **Public addresses only**: a URL taken from a feed or from psapi must resolve to a public address, redirects
  included. That excludes loopback, private, link-local, 100.64/10 (which includes the tailnet) and ULA, because the
  server sits on the home LAN. The parent's own pasted link is still fetched as given, as in step 1.
- **Which cover wins**: the library entry's `cover` is the parent's cover, else this one.
- **Serving**: no new route is needed. The PWA uses `/music-covers/{hash}`; the phones use
  `/api/devices/music/covers/{hash}`, scoped by the library's `covers`.
- **Episode art** stays a URL that the phone fetches lazily (20 §2.3).

## 3. PWA
English, light and dark, 360 px wide without horizontal scroll (long titles take one line with an ellipsis,
`min-width: 0`). Every form redirects to its anchor, so nothing ever jumps to the top.
- **Sweep setting** (`/music#sweep`, a small card above the library):
  - "Check for new episodes: every 1 / 3 / 6 / 12 / 24 hours". The select submits `onchange` to `POST /music/sweep`
    -> `#sweep`, logs the event `music_sweep_saved` and calls `wake()`.
  - Hint: "6 hours is what the Vibb Pi uses (NRK podcasts every 12 hours). Shorter means more requests to NRK and the
    podcast sites. "Check now" on an entry checks it at once."
- **Library cards** (`/music#entries`, one card per entry, `partials/music_card.html`):
  - **Head**:
    - a 64 px cover: the parent's, else the source's, else the category tile;
    - the name, with the source's title under it when the two differ;
    - "NRK podcast · Podkast · on 2 phones";
    - "42 episodes · newest: <title> · 9 Oct". Own-files entries keep "N files".
  - **Status**, one of:
    - "Checked 2 h ago";
    - "Checking…";
    - "Waiting for the first check (3 ahead)";
    - "Last check failed 9 Oct 14:02: the site answered 404. The phones keep the list from 8 Oct. Next try 14:30.";
    - for an entry never listed: "Couldn't list it: NRK doesn't know that podcast - check the link. Next try 14:30."

    The texts come from `CheckError::message` and `entry_error_text`.
  - **Check now** (`POST /music/entries/{id}/check` -> `#entry-<id>`): allowed once per entry per 15 min and 12 times
    an hour in all. When it isn't allowed, the card shows "Check again after 14:20" instead of the button.
  - **Offline**: the `cache` select (None/1/3/5/10/20/All) as a one-field form. It submits `onchange` to
    `POST /music/entries/{id}/offline` -> `#entry-<id>`, logs `music_entry_saved` and nudges the phones that have the
    entry. It is hidden for own files, which are always all offline.
  - **Per phone**, for each phone with the entry ticked, from its `music_state`:
    - "Ella: 5 of 5 offline";
    - "Max: 2 of 5 · waiting for Wi-Fi";
    - "Lea: 1 download failing (the source answered 403)";
    - "Ola: not reported yet".

    "(as of 9 Oct 14:02)" is added when the report is more than 1 h old.
  - **Entry page**: `/music/entries/{id}` shows the same status block and Check now.
- **Updating in place**: while any card says Checking or Waiting, `static/music-cards.js` fetches
  `GET /music/cards?ids=…` (admin; returns those cards' HTML) every 3 s for at most 5 min and swaps the cards. Scroll
  anchoring keeps the view still.

## 4. Device API and contract (21 §1.3 delta)
### 4.1 Library
- Each entry gains `items`: its listing version (16 hex), or null for own files and for NRK/RSS entries never listed.
- `items` is part of the hashed body. A new episode therefore changes the library version of exactly the phones that
  have that entry, and the policy's `library_version` plus the existing nudge carry the change.
- `cover` falls back to the source's cover.
- The field adds about 30 bytes per entry, so `MAX_LIBRARY_BYTES`/`tick_fits` (QA #4) and the policy shape are
  unchanged.

### 4.2 `GET /api/devices/music/entries/{id}/items`
- **Access**: bearer, scoped to the phone's ticks (anything else is a 404). It is also a 404 while the entry has never
  been listed.
- **Caching**: `ETag` = version, `If-None-Match` -> 304, gzip, `Cache-Control: no-cache`, using the library handler's
  helpers.
- **Body**: `{v:1, entry, version, items:[{key, title, url, hls, duration_ms, art}]}`, oldest first. `url: null` =
  not available at the source now.
- **Pinned** by `music_listing_snapshot` in `server/testdata/music_listing.json`, kept byte-identical in the music
  app's test resources.
- **Why a separate document**: 200 entries with up to 100-1000 items each would blow QA #4's 3 MB limit. This way a new
  episode moves one listing (a few KB gzipped) plus the small library, not everything.

### 4.3 Status `music_state` (known fields only, as in 21)
- **`item_errors: [{entry, item, error}]`**, at most 50: downloads of the listed `url` that failed.
  - `item` is a key matching `[A-Za-z0-9_-]{1,64}`.
  - `error` is `http_<code>`, `not_found`, `bad_media` or `network`. `network` never triggers a re-resolve.
- **`downloads: [{entry, have, want, waiting}]`**, at most 200: the items on the phone against what the keep rule
  wants. `waiting` is `wifi`, `storage`, `roaming` or null.
- **`entry_errors` stays**, for the app's own trouble with an entry (`bad_listing`). 21's source error codes are now
  the server's.
- Still never positions or "now playing".

### 4.4 Nudge
- The existing SSE `nudge` goes to `phones_with_entry` after a new version or cover. Check now nudges only when
  something changed.
- On the phone: policy -> library (304 or new) -> the changed listings -> the bridge nudge.

### 4.5 Shared tests
- `music_library_snapshot` (now with `items`) and the new `music_listing_snapshot`. Both files are byte-identical in
  `music/` and compared like `phone_vectors.json`.
- `policy_json_keys_snapshot` and `PolicyResponseCompatTest` are unchanged.
- Sanitizer tests cover the two new status fields.
- An API change goes into both directories in one commit (CLAUDE.md).

## 5. Bridge and launcher (21 §2/§3 delta)
- **AIDL** adds `openListing(long entryId, String haveVersion)`. It returns a read-only pfd, or null when the listing
  is unchanged or absent. The file stays byte-identical in both apps.
- **`reportState(Bundle)`** carries `item_errors` and `downloads`. The launcher maps the known keys into `music_state`
  and drops the rest.
- **`MusicSync`** now also reads `entries[].{id, items}`. After the library:
  - For every entry whose `items` differs from its stored listing's version: GET with If-None-Match, at most 2 MB,
    checking `v == 1` and that `version` is the one asked for. Store it atomically as
    `filesDir/music/items/<entry>.json`.
  - Delete any listing the library no longer names.
  - A failure keeps the old file and is retried at every sync (the comparison is local).
  - Restrictions and the bridge nudge come once, after the library and the listings.
- The launcher never talks to NRK or RSS hosts.

## 6. Music app (21 §4 delta)
- **Gone**:
  - from §4.3: the psapi/RSS listing, manifests, the app's own re-resolve of a failing URL, feed ETags, tracking
    prefixes, the RSS fallback, and their fixture tests (moved to the server);
  - from §4.5: the 6 h listing poll and the listing refresh at start.

  The app makes no requests to a source except to download or stream audio and to fetch episode art.
- **Sync**: bridge (library, listings, covers, files, login, restrictions) -> downloads -> prune -> `reportState`. It
  runs on the nudge (15 s settle), at start, and as a 6 h backstop that reads only the bridge.
- **Stays**:
  - listings in Room, filled from the bridge files; playback never waits for the network;
  - downloads from `url` (`hls` picks HLS and QA #2's CacheKeyFactory) as WorkManager jobs (QA #1);
  - the download gate: NOT_METERED unless `mobile_data`, storage not low, paused while roaming.
- **Keep/prune** (vibb, 21 §4.5), on the server's list:
  - Keep the newest N (the last N of the list), all for -1, none for 0, plus the bookmarked item and the next one in
    play order.
  - Items gone from the list and removed entries are deleted, but only after a listing has been parsed. A missing or
    unreadable listing prunes nothing (QA #3).
- **`url: null`**: the item is never downloaded. A copy already on the phone stays and plays; without one, the item is
  hidden. An NRK/RSS entry with `items: null` is hidden from the carousel until it is listed.
- **Failures**: a failed download goes into `item_errors`. It is retried with WorkManager's backoff, and at once when
  its `url` changes.
- **NY/Nytt** come from listing changes (`_mark_new_seen`: an entry's first listing acknowledges everything; later,
  unseen keys join Nytt). Unchanged from 21.

## 7. Steps (21 §6 delta)
- **21a** (being built) needs nothing from the sweep, because the sweeper finds entries that have no listing. Two small
  follow-ups:
  - 1c adds one `wake()` after the import's commit;
  - 21a's "a dead feed shows up as the phone's entry error" becomes the card's first-check error.
- **1c, the server sweep** (server only, the next server minor version, before step 2):
  - 0050 and `music_sweep.rs`, with the psapi/RSS parsers and recorded answers in `server/testdata/music_sources/`.
  - `Fetch` gains:
    - conditional request headers and the response's `ETag`/`Last-Modified`;
    - a size limit and timeout per call;
    - the paced gate and the public-address check.
  - Covers; `items` in the library; the listing route and its snapshot.
  - The new `music_state` fields and the recheck flag.
  - The sweep setting, the cards, Check now, the offline select and in-place updates.
  - `quick-xml`; NOTICE.md (vibb, MIT); `server/CLAUDE.md`; `music.rs`'s module doc ("the add check is the server's
    only fetch" goes).
  - **Checks on the Pi**:
    - entries: a real NRK podkast, an NRK serie, a `serie/<slug>/<programId>` and two RSS feeds;
    - for those: the first fill, the logged request counts and the 304s;
    - restarts and the setting: a restart sweeps nothing, and a setting change takes effect at the next sweep;
    - the Check now limit;
    - a 21a import filled in the background;
    - a 404 feed keeping its list;
    - an NRK serie's HLS URL from the day before still plays (if not, the re-resolve carries series);
    - `curl` of the listing (ETag/304);
    - the cards on a phone, light and dark.
- **Step 2** shrinks: the §4.3 sources are gone, and the debug loader takes a library plus listings (the snapshots or
  `curl`).
- **Step 3** adds the listing courier, `openListing` and the new `reportState` fields.
- **Step 4** is unchanged.
- The deploy order is unchanged: this server goes out before any stable `music-v*`.

## 8. Tests (`src/tests/music_sweep.rs`, `music_sweep::tests`; a counting fake fetcher, a fake clock)
- **Parsing**, on recorded answers:
  - psapi: root, desc and asc pages with `_links.next`, MP3 and HLS manifests, `nonPlayable`, `encrypted`;
  - RSS: itunes and classic images, with and without guid, tracking prefixes, items without an enclosure, CDATA,
    entities, a refused DTD, a 20 MB feed.
- **Listing**:
  - first fill and incremental: exactly the new episodes get manifest calls, and the walk stops at the first known
    key;
  - the 100/1000 caps, serie asc, the `programId` walk, the RSS fallback and its replacement, 304.
- **Keep last good**: a failure at each step (root, page, a manifest mid-batch, timeout, too big, empty) leaves rows
  and version alone, sets error and backoff, and sends no nudge.
- **Cadence**:
  - the due rules for every setting (the table in §2.2), new entries, the backoff, Check now and recheck;
  - a fresh sweeper over recent stamps makes zero requests;
  - a setting change re-times due entries with no stored time rewritten;
  - `wake()` after add, import and a setting save;
  - a simulated day of 200 entries at 6 h stays within the Pi bound;
  - never two requests in flight.
- **Covers**: fetched once and again on a new URL; pruning keeps listing covers; a lost one is refetched; the parent's
  cover wins; a private address is refused.
- **Versions and nudges**: the same list gives the same version and no nudge. A change nudges only the phones with the
  entry and changes only their library version.
- **The listing route**: scoping, 404 before the first listing, ETag/304, gzip, the snapshot; the library snapshot
  with `items`.
- **`music_state`**:
  - the new fields are kept, clamped or dropped;
  - `item_errors` flag a recheck at most once per 24 h, and never for `network`, unknown keys or unticked entries;
  - a recheck that finds a new URL bumps the version, the same URL doesn't, and no `playable` gives null;
  - an `available_until` in the past gives null.
- **PWA**: the setting (values, the hint, the redirect), every card state, the per-phone lines, the Check now limits,
  the offline select's redirect and nudge, the cards route, 360 px without horizontal scroll in light and dark.
- **Launcher (step 3)**: only changed listings are fetched, an old one is kept on failure, and gone ones are deleted.
- **Music app (step 2)**: the shared listing file, keep/prune from it, null URLs, NY from listing changes,
  `item_errors`.

## 9. Open questions
1. **Check now and NRK's catalog TTL.** The Pi never re-lists NRK inside its 12 h TTL, but Check now would: one
   request, at most once per 15 min per entry and 12 an hour in all. **Recommend yes**: without it, Check now does
   nothing for NRK for up to 12 h.
2. **Withdrawn episodes** (`url` null: the rights ended, or the manifest has nothing playable). Either a copy already
   on the phone keeps playing, as on the Pi, or it is deleted. **Recommend keeping it**, as the Pi does.

## QA review (design)

QA, 2026-10-09. Read against 21 (§1-§4, §6, QA, decisions), 21a, step 1 (`music.rs`, `handlers/music{,_api}.rs`,
`photos.rs`, 0049, `music_library.json`), vibb `main` (`content.py`, `library.py`) and live answers today (psapi
radioteatret `sort=asc`/`desc`, a program and a podcast manifest, podkast.nrk.no's abels_taarn RSS). **Buildable** on
step 1: 0049 stays untouched, `MUSIC_COVERS` takes one more reference, `etag_matches`/`accepts_gzip` serve the listing,
and `LibEntry` takes `items`. The one plumbing gap is the clock (#6). **Conflicts with 21**: #1 (decision 1), #3 (QA
#9's point), #8 (§4.5 "newest N by publication") and #9 (Q2).

**Load, computed** (30 NRK podkast + 10 RSS entries; a steady check is 1 request, plus 1 manifest per new episode):

| Setting | NRK every | NRK req/day | RSS req/day | Total | Against the Pi (60 NRK + 40 RSS) |
|---|---|---|---|---|---|
| 1 h | 2 h | 360 | 240 | 600 | NRK 6× |
| 3 h | 6 h | 120 | 80 | 200 | NRK 2× |
| 6 h | 12 h | 60 | 40 | 100 | equal |
| 12 h | 12 h | 60 | 20 | 80 | below |
| 24 h | 24 h | 30 | 10 | 40 | below |

- **A 21a import of those 40**: 30 × (root + 2 pages + 100 manifests) = 3,090 psapi requests, plus 30 gfx covers and
  10 × (feed + cover). That is about 3,140 requests, the same as the Pi's first sweep (which runs 8 wide). At >= 200 ms
  a request and 4 s an entry it takes >= 13 min, and 20-30 min at realistic psapi latency. A
  `serie/<slug>/<programId>` entry costs up to 200 (a manifest and a metadata call per item).
- **Check now**: 12 an hour in all, so up to 288 extra checks a day (4.8× the Pi's 60 NRK requests).
- **Rechecks** (§2.5): one manifest per item a day. 30 entries × keep 5 = 150 a day from one phone that misreports
  (2.5× the Pi).

1. **High - the cadence breaks decision 1 and the user's NRK bound.** Whatever its sweep interval, the Pi never
   re-lists NRK within `CATALOG_TTL_S` (12 h). §2.2 re-lists NRK every 2 h at the 1 h setting (6×) and every 6 h at
   3 h (2×). Fix: NRK every `max(I, 12 h)`, i.e. 12/12/12/12/24. The setting then shortens only RSS, which the Pi
   refetches at every sweep and menu open anyway. Hint: "NRK podcasts are checked every 12 hours at most, as on the
   Pi". Check now (Q1) is then the only NRK exception. **User decision**, with the worst case above (288 a day).
2. **High - one failed manifest fails the whole check, for good.** §2.3 fails the check on a network error or 5xx in
   any manifest and writes nothing. A first fill is 103 requests, so at a 0.5 % transient error rate 40 % of first
   fills fail and start over. Take a manifest that keeps answering 5xx (one broken programme): the entry stays
   `items: null`, hidden on every phone, and re-runs 103 requests at each backoff step. That is 7 tries (~720 requests)
   on day 1, then ~410 a day: 7× the Pi's NRK budget for all 30 entries. vibb skips such an episode (`_manifest_url`
   -> None) and keeps the rest. Fix:
   - a manifest that fails (network, 5xx, 4xx) skips its item for this check;
   - keep the skipped stubs as pending and retry at most 10 per later check;
   - fail the check only when the root, a listing page or the feed fails;
   - a check that reaches 250 requests or 10 min commits what it has resolved, so a retry continues instead of
     starting over.
3. **High - a serie's `sort=asc` first fill is undone at its next check.** Checked today: radioteatret's `sort=asc`
   starts in 1993, `sort=desc` in 2018. A serie with more than 100 episodes keeps episodes 1-100 at the first fill. 12 h
   later the `sort=desc` walk finds no known key, takes 100 "new" episodes (2 pages + 100 manifests) and drops "the
   oldest beyond 100". The list is now the newest 100, and the phone prunes the story's start, including the episode
   the kid is in. The same happens to `serie/<slug>/<programId>` at its cap, and vibb has the same flaw (`_catalog`
   asc, then `_new_episodes` desc). Fix: a list that starts at the beginning (serie, programId) never evicts from the
   front. The incremental check adds only while the list is below the cap; at the cap the card says "first 100 of N
   episodes" (raising the serie cap instead is a user call). Test with a 150-episode serie.
4. **High - SSRF through the feed itself.** §2.6 checks addresses only for covers. The feed URL is "fetched as given",
   and reqwest follows up to 10 redirects to any host (and an environment proxy unless `.no_proxy()`). The sweeper
   re-fetches the feed unattended every `I`, from inside the LAN and the tailnet, and 21a's targets never even had an
   add check. Scenario: a podcast domain lapses and someone re-registers it. Its feed now 302s to
   `http://192.168.1.1/cgi-bin/...` or to a `100.x` tailnet peer, and the home server GETs that every hour, for ever.
   Fix: one client for every source fetch (add check, sweep, covers):
   - a `dns_resolver` that drops non-public answers, checked at connect time, so DNS rebinding has no gap;
   - a redirect policy that re-checks IP-literal hosts, allows http/https only and at most 5 hops;
   - `.no_proxy()`;
   - refuse 0/8, 10/8, 100.64/10, 127/8, 169.254/16, 172.16/12, 192.0.0/24, 192.168/16, 198.18/15, 224/3, `::`,
     `::1`, fc00::/7 (with the tailnet's fd7a:115c:a1e0::/48), fe80::/10 and ff00::/8, and check ::ffff:0:0/96 and
     64:ff9b::/96 by their embedded IPv4.

   A LAN feed then gets "only public addresses work" (a user call, if LAN feeds matter).
5. **Medium - phone reports can loop and break the bound.** Three cases:
   - Behind a captive portal, every download gets HTML -> `bad_media` -> every wanted item is flagged: 150 manifests
     a day for 30 entries.
   - `recheck` is cleared "afterwards". If the check fails (say the feed now 404s), the flag stays, and "recheck and
     `checked_at` >= 1 h" makes the entry due every hour for ever, past the failure backoff.
   - A phone with a stale list reports a URL the server has already replaced.

   Fix: only `http_401/403/404/410` flag; `recheck` is cleared after any attempt; at most one recheck per entry a day
   and ~20 manifests a day server-wide; `item_errors` carry the listing `version` the phone used, and reports against
   an older version are ignored.
6. **Medium - restart-safe, but not crash-safe.** Nothing is stamped until a check ends. If the server restarts
   mid-check (or the Zero runs out of memory on a 20 MB feed), the entry is due again 60 s after the start, so a crash
   loop refetches the source every minute and a first fill starts over. A Check now pressed during a check is lost,
   because `checked_at` ends up later than `requested_at`. Fix:
   - at the start of a check, in its own write, upsert the row with `checked_at` = now (a new entry keeps `version`
     NULL);
   - the due rule reads `version IS NULL` plus the backoff instead of "no row";
   - stamps are written from the `now` parameter, not `datetime('now')` (§1), or §8's fake clock can't drive them.
7. **Medium - duplicate or empty keys fail an entry for good.** Some feeds have an empty `<guid/>`, or one guid on
   several items (common in hand-made and WordPress feeds); all those items hash to one key. Also, a psapi episode
   published between two `sort=desc` pages repeats a stub. Either way the insert hits the `(entry_id, key)` primary
   key, the check fails, and the entry stays dark. Fix: trim the guid and treat an empty one as missing; dedupe,
   keeping the first (newest) occurrence; check NRK ids against `[A-Za-z0-9_-]{1,64}` before they become keys and
   manifest paths.
8. **Medium - "feeds list newest first" is wrong for serial feeds.** Some feeds list oldest first (`itunes:type`
   serial, audiobook feeds). "The newest 1000 in feed order, stored oldest first" stores such a list upside down: the
   phone keeps the oldest N as "the newest", and NY marks the wrong end. Fix: order by `pubDate` when nearly every
   item has one (ties in document order), else take document order as newest first. Test an oldest-first feed.
9. **Medium - items that leave the source delete the kid's copy.** §6 deletes "items gone from the list". That also
   hits RSS rolling windows (podkast.nrk.no's own RSS has 3 items today, and many hosts keep only the last 10-50),
   the NRK and RSS caps, and an episode a host pulls. The bookmarked item and its download vanish mid-story, while a
   withdrawn NRK item (`url: null`, Q2) keeps playing. Fix:
   - an RSS item missing from a fresh feed stays in the listing with `url: null` (merge, don't replace);
   - the cap drops the oldest `url: null` items first;
   - keep/prune never deletes the bookmarked item or a downloaded `url: null` item;
   - Q2: keep the copy, as recommended.
10. **Medium - the in-place card updates move the page.** iOS Safari has long lacked scroll anchoring, so don't rely
    on it. A card above the view that grows from "Waiting…" to three lines pushes the view down every 3 s. Swapping
    whole cards also replaces an open offline select or a focused button. Fix:
    - swap only the status block (`#entry-<id> .music-status`), and never a card that holds `document.activeElement`;
    - before a swap, note the first visible card's top, then `scrollBy` the difference;
    - give the status a fixed two-line height.
11. **Low - the sweeper loop.**
    - The gate is per request and FIFO (`tokio::sync::Mutex` is fair), so an add check waits for one request (at most
      30 s, for a cover), not for a whole first fill.
    - Check now and the newest add come before an import's backlog. Otherwise an add right after a 40-entry import
      waits 20-30 min.
    - Re-read `due(now)` after each entry and wake with `notify_one`, so a wake during a pass isn't lost.
    - The final transaction checks that the entry still exists. If it was deleted mid-check, drop the result and
      write no error row.
12. **Low - time and size limits.**
    - At 10 s overall, a 20 MB feed needs ~16 Mbit/s, so a big feed on a slow host fails as `timeout` for ever. Use a
      10 s connect, 30 s without data and 120 s overall for feeds.
    - Caps per kind: psapi page 2 MB, manifest 256 KB, image 10 MB, feed 20 MB.
    - A feed without validators is a full GET at every check (20 MB × 24 a day at 1 h). Enable reqwest's `gzip` (the
      cap counts decoded bytes), and skip the parse when the body's SHA-256 is unchanged.
    - `CheckError::TooBig` still says 5 MB.
13. **Low - item URLs reach the phone unchecked.** An enclosure or `itunes:image` of `file:`, `content:` or `data:`
    would be opened by Media3's `DefaultDataSource` on the phone. Keep only http/https URLs without userinfo for `url`
    and `art`, and cap `art` at 2000 characters like `url`.
14. **Low - the listing limits.** The design doesn't say what happens past `MAX_LISTING_BYTES`, and failing the
    check would darken the entry. 1000 items with long tracking URLs pass 1 MB. Fix: drop the oldest items until it
    fits, and say so on the card. Per phone, 200 ticked 1 MB listings (~200 MB over tsnet, stored twice on the phone)
    are outside QA #4's limit: give `tick_fits` a listing budget, or accept it in the doc.
15. **Low - covers.**
    - Hold the `MUSIC_COVERS` lock from writing the file to committing `cover_hash` (photos' rule). Otherwise a
      parent's prune in between deletes the new file.
    - NRK's incremental check never reads the root, so it never sees a new show image or title. Re-read the root
      once a week (1 request).
    - A signed cover URL whose query changes at every fetch would mean up to 10 MB at every check: refetch at most
      once a day.
16. **Low - NRK details.**
    - podkast.nrk.no's RSS `guid` is the episode id (`l_…`, checked today). In fallback, use it as the key, not
      `sha1(guid)`, so the keys (and with them positions, downloads and Nytt) survive psapi's return.
    - NRK sometimes extends `usageRights.to`. Re-resolve the manifest before setting `url` NULL (it counts as a
      recheck).
    - psapi sends 9999 (and 2100) for no end date: read any year >= 2100 as "always".
17. **Low - outages.**
    - With NRK down, 30 entries fail one by one at 10 s each, and the backoff (15 min, 1 h, 4 h) retries faster than
      the Pi's 6 h. Add a per-host breaker: after 3 network/5xx failures in a pass, defer that host's remaining
      entries without counting a failure.
    - Cap the backoff at the source's interval, not `I`: at 6 h a failing NRK entry is retried every 6 h, while a
      healthy one is checked every 12 h.
    - After 7 days of failures, try once a day.
18. **Low - unticked entries.** The Pi sweeps only its own library's entries, and only those with `cache != 0`. §2.2
    sweeps every NRK/RSS entry, ticked or not, so a 200-entry import with 40 ticked costs 5× for lists no phone
    gets. Fix: an unticked entry gets its first fill, then a 24 h cadence (the card stays roughly current); ticked
    entries follow the setting, and a tick wakes the sweeper.
19. **Low - the parser.**
    - "Refuses a DTD" also refuses RSS 0.91's Netscape `<!DOCTYPE>`, which vibb's ElementTree accepts. Ignore a
      DOCTYPE without an internal subset, and refuse only internal subsets (entity bombs).
    - Decode a non-UTF-8 feed by its XML declaration (quick-xml's `encoding` feature, MIT).
    - Store a parser version, and send `If-None-Match`/`If-Modified-Since` only for a listing the current parser
      built. Otherwise a parser fix never reaches a feed that keeps answering 304.
20. **Low - contract.**
    - The launcher requires `version` to equal the one it asked for, so a check that lands between the library GET
      and the listing GET fails that sync. Accept any newer version.
    - `openListing` returns null both for "unchanged" and "absent". Return a status that tells them apart.
    - Append new AIDL methods at the end.
    - Add `music_listing.json` to the `music.yml`/`launcher.yml` path filters and to the root CLAUDE.md's
      shared-files list.
    - Nudge once per sweep pass, not once per entry, so a phone refetches its library (up to 3 MB) once per pass.
    - `downloads` rows carry the applied listing `version`, so the card can say "has an older list" for a phone
      stuck on a failed fetch.
21. **Low - PWA.**
    - The card drops step 1's up/down and Edit buttons. Keep them.
    - Check now on `/music/entries/{id}` redirects back to that page, not to `/music`.
    - Card times say UTC like the rest of the PWA (or use one local zone everywhere).
    - The 12-an-hour count needs its own record: `requested_at` holds only an entry's last press.
    - Cap `GET /music/cards?ids=` at 200 ids, and build the cards with a few aggregate queries (200 cards on a Pi
      Zero).
    - §3's hint follows #1.
