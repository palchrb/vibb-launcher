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
