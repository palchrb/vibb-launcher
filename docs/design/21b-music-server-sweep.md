# 21b - Vibb music: the server sweeps the sources (delta against 21)

Architect, 2026-10-09; revised the same day after QA's design review and the user's answers (both at the end; the body
already follows them). The user decisions override design 21 (and its QA decisions) where they conflict; the rest of
21 stands. Sources: 21 (§1-§6, QA review, decisions, step 1 status), 21a, the step-1 code (`music.rs`,
`handlers/music{,_api}.rs`, `photos.rs`, 0049), palchrb/vibb `main` (MIT): `pi/vibb/content.py` (`_catalog`,
`_new_episodes`, `_psapi_episodes`, `_episode_stub`, `_manifest_url`, `_pick_image`, `_parse_feed`, `_feed_episode_id`,
`sync_feed`) and `pi/vibb/library.py` (`_cache_sweeper`, `SYNC_INTERVAL_S`, `SYNC_STAGGER_S`, `SYNC_DELAY_S`,
`SWEEP_STAMP`, `_mark_new_seen`). The behaviour is reimplemented in Rust (GPL-3.0) and credited in NOTICE.md (added in
step 1c with the first ported code) and in `music_sweep.rs`'s header. psapi answers recorded on 2026-10-09 confirm the
fields named below: episodes `episodeId`, `date`, `durationInSeconds`, `usageRights.to.date`; manifest
`playable.assets[0].{url, format, mimeType, encrypted}`.

**User decisions (2026-10-09)**
1. The server is the brain for metadata. It verifies a link on add, lists every NRK/RSS entry, fetches its cover and
   sweeps with the Pi's logic: incremental, sequential, the catalog TTL. At the default setting it never loads NRK more
   than one Pi does, and one sweep serves all phones. A change nudges only the phones that have the entry ticked.
2. NRK audio URLs are stable, as on the Pi, so the phone never resolves them. The phone reports a failing URL (download
   or stream) and the server re-resolves it, stores the renewed URL and sends it to every phone with the entry.
3. The phone downloads audio straight from the source. The server never proxies or stores NRK/RSS audio.
4. Storytel stays on the phone (phase 2).
5. Positions stay on the phone. Keep/prune (the newest N plus the item in progress) runs there, on the server's list. A
   copy of an episode the source withdraws keeps playing, as on the Pi.
6. The sweep cadence is a family-wide PWA setting: every 1, 3, 6 (default, the Pi), 12 or 24 hours; NRK follows it
   (§2.2). "Check now" stays, rate-limited, and may bypass NRK's 12 h TTL.
7. Feeds on the home LAN or the tailnet are allowed; a hop from a public address to a private one is refused.

**Moves to the server**:
- 21 §4.3: psapi/RSS listing, manifests, feed parsing, tracking prefixes and the RSS fallback.
- 21 §4.5: the 6 h listing sync.
- New: the PWA cards.

**Stays on the phone**: downloads, keep/prune, Nytt/NY, positions, playback, Storytel.

## 1. Server data, migration `0050_music_sweep.sql`
New tables and an index only. 0049 is never edited. Every stamp is UTC text written from the sweeper's `now`
parameter, never `datetime('now')`, so the tests' fake clock drives them.
- **`music_settings`** (singleton, like `call_settings`): `sweep_hours` INTEGER NOT NULL DEFAULT 6, CHECK IN
  (1, 3, 6, 12, 24).
- **`music_check_requests`** (`at`): one row per Check now, for the hourly and daily limits. Rows older than a day are
  deleted on insert.
- **`music_listings`**: one row per NRK/RSS entry, upserted when its first check starts (own files have none).
  - `entry_id` PK -> `music_entries` ON DELETE CASCADE.
  - The source:
    - `title`: its own name (psapi `series.titles.title`, or the RSS channel title);
    - `lan` (0/1): the target resolved to a private address at the first check; never changed afterwards (§2.7);
    - `fallback` (0/1): the list came from NRK's RSS fallback;
    - `capped` (0/1): a list anchored at the start has reached its cap and the source has more;
    - `cut` (0/1): trimmed to `MAX_LISTING_BYTES`.
  - The cover and root: `cover_url`, `cover_hash`, `cover_at` (§2.6); `root_at` (NRK root last read).
  - RSS: `etag`, `last_modified`, `body_sha256`, `parser` (the parser version that built the list).
  - `version`: 16 hex of SHA-256 over the listing JSON without its version (QA #4's rule). NULL = never listed.
  - The stamps:
    - `listed_at`: the last successful listing (vibb `fetched_at`);
    - `checked_at`: the start of the last check;
    - `requested_at`: the last Check now;
    - `full_recheck_at`: the last whole-entry re-resolve (§2.5).
  - Errors: `error`, `error_at`, `failing_since`, and `failures`. `failures` is incremented when a check starts and
    zeroed by a success, so a crash mid-check counts as a failure.
  - `requests`: how many the last check made.
- **`music_items`**: PK (`entry_id` -> CASCADE, `key`). Columns:
  - `seq`: 0 = the oldest; this is the listing order.
  - `state`:
    - `ok`: it has a URL;
    - `pending`: a stub whose manifest isn't resolved yet; never sent to phones;
    - `gone`: no URL now (not playable, rights ended, or it left the feed); sent with `url: null`.
  - `title`, `url`, `hls` (0/1), `duration_ms`, `art_url`, `published_at`.
  - `available_until`: NULL = always; psapi's years of 2100 and later read as always.
  - `first_seen_at`, `attempts` (failed manifests), `reported_at` (the last accepted phone report), `rechecked_at`
    (the last re-resolve), `recheck` (0/1, §2.5).
- **Item keys**, as 21 and vibb define them:
  - NRK: the episode id, i.e. the last segment of `_links.self.href` (vibb `_episode_stub`; the same as
    `episodeId`). For `serie/<slug>/<programId>` it is the programme id. It must match `[A-Za-z0-9_-]{1,64}` before it
    becomes a key or a manifest path; otherwise the stub is skipped.
  - RSS: `sha1(guid)[:12]`, with the guid trimmed and an empty one treated as missing; else `sha1(enclosure url as
    written in the feed)[:12]` (21 §4.3; vibb `_feed_episode_id`).
  - NRK's RSS fallback uses the guid itself as the key, because podkast.nrk.no's guid is the episode id. Keys,
    positions and downloads therefore survive psapi's return.
  - A key seen twice in one answer (a repeated guid, or a stub repeated across two `sort=desc` pages) keeps its first,
    newest, occurrence.
- **Caps**:
  - NRK podkast keeps the newest 100 (vibb `MAX_EPISODES`, QA #9) and RSS the newest 1000 (vibb keeps all). The cap
    drops the oldest `gone` items first, then the oldest.
  - NRK serie and `serie/<slug>/<programId>` are anchored at the start and never evict from the front. A check adds
    episodes only while the list is below 100; at 100 it sets `capped` (QA #3; open question 1).
  - A listing is at most 1 MB (`MAX_LISTING_BYTES`). Past that, items are dropped from the end opposite the anchor
    (`gone` first) until it fits, and `cut` is set and shown on the card. Titles are cut to 200 characters.
  - Item `url` and `art`: http or https only, no userinfo, at most 2000 characters; anything else is NULL.
  - Per phone, `tick_fits` also adds up the ticked entries' listings, with a budget of 32 MB
    (`MAX_LISTINGS_BYTES_PER_PHONE`). Growth past it later only warns on the device card; it never unticks.
- **Covers**: `photos::MUSIC_COVERS` also references `music_listings.cover_hash`. A lost cover is forgotten together
  with its `cover_url`, so the next check fetches it again.

## 2. The sweeper (`server/src/music_sweep.rs`)
One task, spawned in `main.rs` and never in `app()`. Tests drive `due(now)` and `check_entry(now)` with a fake clock
and the canned `music_fetch`. `AppState.music_sweep` holds a `Notify` and the id of the entry being checked. `wake()`
is `notify_one`, which keeps a wake that arrives during a pass.

### 2.1 The load rule
- **One source request in flight server-wide**, through one fair (FIFO) gate that every source fetch uses, the add check
  included. An add check therefore waits for at most one request, not a whole first fill. Request starts are at least
  200 ms apart and entries 4 s apart (vibb `SYNC_STAGGER_S`). vibb resolves manifests 8 at a time.
- **Steady state** per check:
  - NRK: one `episodes?sort=desc` page;
  - RSS: one conditional GET (the Pi refetches the whole feed on every sweep and every menu open);
  - manifests only for new or pending episodes;
  - the NRK root on the first fill and once a week (title and image);
  - a cover only when its URL changes, at most once a day.
- **Limits per request kind** (all: a 10 s connect timeout and 30 s without data):

  | Request | Size | Overall |
  |---|---|---|
  | psapi root or page | 2 MB | 30 s |
  | manifest or metadata | 256 KB | 15 s |
  | feed | 20 MB, decoded | 120 s |
  | image | 10 MB | 60 s |

  reqwest has `gzip` on (the cap counts decoded bytes) and the client rules of §2.7.
- **A check's budget**: 250 requests or 10 min. At the limit, what has been resolved is committed, the rest stays
  `pending`, and the entry continues at the next free slot. Reaching the budget is not a failure.
- **Per-host breaker**: 3 network or 5xx failures from one host in a pass defer that host's remaining entries to the
  next pass, without counting a failure. An NRK outage then costs 3 requests, not 30 timeouts.
- **Against the Pi** (QA's table: 30 NRK + 10 RSS entries, about 100 requests a day at 6 h):
  - 6 h (the default): equal;
  - 3 h: about 2× the Pi's NRK requests;
  - 1 h: about 6× (the user's choice, §2.2);
  - 12 h and 24 h: below.

  A first fill costs what the Pi's first sweep costs (a 100-episode podcast = root + 2 pages + 100 manifests),
  sequentially instead of 8 wide. A 21a import of 40 entries is about 3,140 requests over 20-30 min.
- **Extra requests, capped**:
  - Check now: one request when nothing is new. At most once per 15 min per entry, 12 an hour and 48 a day in all.
  - Rechecks (§2.5): at most one per item a day, one whole-entry re-resolve per entry a day, and 300 manifests a day
    server-wide.
- **One sweep serves all phones**; phones never list a source.
- **Logging**: each check logs `music sweep: entry 12 "Abels tårn": 1 request, 0 new` and stores `requests`.

### 2.2 Cadence and stamps
- **Intervals** from the setting `I` (`music_settings.sweep_hours`), for entries ticked on at least one phone:
  - RSS: every `I`.
  - NRK: every `max(I, min(2·I, 12 h))`. At 6 h that is the Pi's rule (a 6 h sweep with a 12 h `CATALOG_TTL_S`). The
    user chose to let shorter settings shorten NRK too.
  - An entry ticked on no phone gets its first fill, then every 24 h; the Pi sweeps only its own library. A tick wakes
    the sweeper.

  | Setting | 1 h | 3 h | 6 h (default, the Pi) | 12 h | 24 h |
  |---|---|---|---|---|---|
  | RSS | 1 h | 3 h | 6 h | 12 h | 24 h |
  | NRK | 2 h | 6 h | 12 h | 12 h | 24 h |
  | Ticked on no phone | 24 h | 24 h | 24 h | 24 h | 24 h |

- **Due** is a pure function of the stamps, the setting, the ticks and `now`. In order of priority:
  1. Check now: `requested_at > checked_at`.
  2. Never listed (`version` NULL) and `failures` = 0: a new or imported entry. Newest `created_at` first, then
     carousel order, so a single add doesn't wait behind an import's backlog.
  3. A flagged item (§2.5), or `pending` items never tried (an interrupted fill): at the next free slot.
  4. After a success: `listed_at` + the interval. After a failure: `checked_at` + min(15 min · 4^(failures-1), the
     interval). After 7 days of failures (`failing_since`): once a day.
- **Restart- and crash-safe**:
  - A check starts with its own write, upserting the row with `checked_at` = now and `failures` + 1.
  - The end write zeroes `failures` after a successful listing, restores the old value after a check with no listing
    part (a recheck only), and keeps it after a failure.
  - So a crash or an out-of-memory mid-check backs off like a failure instead of looping.
  - A Check now pressed during a check (`requested_at` later than that start) runs once more afterwards.
  - Nothing else about time is stored, so a new setting applies at the next sweep (the save calls `wake()`) and nothing
    has to be rewritten.
  - The first pass runs 60 s after start (vibb `SYNC_DELAY_S`).
- **The loop**: sleep until the next due time (at most `I`) or a `wake()`. Then repeatedly take the most urgent due
  entry, check it and re-read `due(now)`, until nothing is due.
- **`wake()` is called after**: `add_link`'s insert, 21a's import commit, a tick on the device card, Check now, a
  flagged item, and a saved setting.
- **Per-entry cadence: out.** The one setting plus Check now cover it.
- **What is swept**:
  - `nrk` and `rss` entries.
  - Never `own`.
  - `storytel` stays on the phone (phase 2).
  - `spotify` is phase 3 (vibb's oEmbed cover would go here).

### 2.3 One check
Apart from the starting stamp, a check writes in one transaction at the end (or when its budget is reached). That
transaction first checks that the entry still exists. If it was deleted mid-check, the result is dropped and no row is
written.
- **NRK podkast `<slug>`**:
  - First fill: root `/radio/catalog/podcast/<slug>` (title and `series.squareImage`; 404/410 = `not_found`), then
    `episodes?page=1&pageSize=50&sort=desc`, following `_links.next`, up to 100 stubs (QA #9), then the manifests,
    oldest first.
  - If psapi lists nothing: use `podkast.nrk.no/program/<slug>.rss` as RSS (vibb) and set `fallback`. Every check of a
    fallback list asks psapi first. Since the fallback's keys equal psapi's (§1), psapi's return continues the same
    list.
  - Incremental: walk `sort=desc` to the first known key (in any state), resolve manifests for the new episodes,
    append them, then apply the cap (§1).
- **NRK serie `<slug>`**: the same with `series` and `manifest/program`, except:
  - the first fill walks `sort=asc`, because a serial story starts at episode 1 (QA #9);
  - the list never evicts from the front. An incremental check (`sort=desc` to the first known key) appends new
    episodes only while the list is below 100; at 100 the walk stops and `capped` is set. vibb has the flaw QA #3
    describes: its next check replaces a long series' start with the newest 100.
- **NRK `serie/<slug>/<programId>`**:
  - First fill: walk the manifest plus `/playback/metadata/program/<id>` along `_links.next` (vibb `_series`, at
    most 100).
  - Incremental: continue from the last item's `_links.next`, only while the list is below 100. That is one request
    when nothing is new.
- **A stub** gives: the key, `titles.title`, `date`, `durationInSeconds`, the smallest `squareImage` of at least 300 px
  (vibb `_pick_image`), and `usageRights.to.date` (-> `available_until`).
- **A manifest** (vibb `_manifest_url`): use `playable.assets[0].url` unless it is `encrypted`. `hls` is set when
  `format` is HLS or the path ends in `.m3u8`. Outcomes:
  - a URL -> `ok`;
  - no `playable` (rights, geo-block, not out yet), a network error, a 4xx or a 5xx -> the item stays `pending` with
    `attempts` + 1, and the check goes on (QA #2). Items that are pending after a failure are retried at most 10 per
    check, and after 14 days pending an item becomes `gone`.
- **Only a failing root, listing page or feed fails the check.**
- **Expiry**: an `ok` item past `available_until` is re-resolved first, because NRK sometimes extends rights; this
  counts toward the recheck budget. Still playable -> a new `available_until`; otherwise `gone`.
- **RSS**:
  - **Fetch**: GET with `If-None-Match`/`If-Modified-Since`. The validators are sent only when the stored list was
    built by the current `parser`, so a parser fix reaches a feed that keeps answering 304. A 304, or a 200 whose body
    SHA-256 equals `body_sha256` (a feed without validators), means unchanged.
  - **Size**: up to 20 MB (`MAX_FEED_BYTES`, raised from 5 MB for the add check too; `CheckError::TooBig`'s text
    follows).
  - **Parser**: one for the sweep and the add check, `quick-xml` (MIT). It is a pull parser, so memory stays about the
    size of the body, and its `encoding` feature decodes a non-UTF-8 feed by its declaration.
    - A DOCTYPE without an internal subset is ignored (RSS 0.91); one with an internal subset is refused (entity
      bombs).
    - Only the five entities and numeric references are decoded.
  - **Items**: only those with an enclosure URL (vibb `_parse_feed`).
  - **Direction**: if most adjacent pairs of dated items (`pubDate`) ascend in document order, the feed lists oldest
    first (serial and audiobook feeds); otherwise newest first (vibb). Undated items keep their place in the document.
  - **Merge, don't replace**:
    - items still in the feed are updated;
    - new keys are appended as the newest;
    - an item missing from a fresh feed becomes `gone` but stays in the list, so a rolling window of 10 episodes
      doesn't delete the kid's place (QA #9);
    - a `gone` item that comes back is `ok` again;
    - then the cap applies.
  - **Fields**: the channel image is `itunes:image@href`, else `image/url`. Per item: `guid`, `title`, `pubDate`,
    `itunes:duration` (s, m:ss or h:mm:ss), `itunes:image`.
  - **Tracking prefixes** (podtrac, chtbl, pdst.fm, op3.dev; 21 §4.3) are stripped from the `url` the phones get. The
    key uses the enclosure as written.
- **Result**: one function builds the listing (§4.2) from the rows.
  - A new version is stored, and the entry's phones (`phones_with_entry`) join the pass's nudge set.
  - The same version moves only the stamps.
- **Nudge** once per pass, or after 5 min of a long pass if phones are waiting. A phone then refetches its library
  once, not once per entry.

### 2.4 Keep the last good list
- **A failed check** (root, page or feed; timeout; too big; no items) leaves the items, version and cover alone. It
  sets:
  - `error`: `not_found`, `not_feed`, `no_items`, `http_<code>`, `network`, `timeout` or `too_big`;
  - `error_at`;
  - `failing_since`, unless it is already set;
  - the backoff (§2.2).
- A list that would end up with no `ok` item counts as `no_items`. A success clears all of this.
- Phones keep what they have. A never-listed entry has `items: null` (§4.1).
- A failed cover fetch never fails the check. The old cover stays, and a missing one is tried again at most once a
  day.

### 2.5 Re-resolve on a phone's report (user decision 2)
- **Reports**: `item_errors` (§4.3) come from failed downloads and failed streams. A row flags its item only if all of
  these hold:
  - it names an entry the phone has ticked, a key in that entry's list, and the listing `version` that is current. A
    phone on an older list may be reporting a URL the server has already replaced.
  - its code is `http_401`, `http_403`, `http_404` or `http_410`. Never `network`, and not `bad_media` either, because
    a captive portal answers every download with HTML.
  - the item's `rechecked_at` is NULL or at least 24 h old.

  Flagging sets `recheck` = 1 and `reported_at` = now and calls `wake()`. The entry is handled at the sweeper's next
  free slot, typically within minutes.
- **One item**: NRK re-resolves it with one manifest. RSS refetches the feed unconditionally (no validators, no
  body-hash skip). After any attempt, successful or not, `recheck` = 0 and `rechecked_at` = now, so a failing feed
  can't keep the entry due.
- **The whole entry**: when two or more distinct items of one entry were flagged within 24 h (the source has probably
  changed), every item of the entry is re-resolved, paced and sequential like any check. This happens at most once per
  entry per 24 h (`full_recheck_at`).
  - NRK: one manifest per item, `gone` items included.
  - RSS: the unconditional feed GET.
- **Outcomes**:
  - Renewed URLs are stored on the server, bump the version and are nudged to every phone with the entry.
  - The same URL changes nothing; the phone keeps its own backoff.
  - No `playable` -> `gone`.
- **Bounds**: one re-resolve per item a day, one whole-entry re-resolve per entry a day, and 300 recheck manifests a day
  server-wide (three whole NRK entries). Past the server-wide budget, flags wait for the next day.

### 2.6 Covers
- **Source**: NRK `series.squareImage` (the smallest of at least 512 px); the RSS channel image.
- **Pipeline**: GET (10 MB, 60 s) -> `photos::process_limited(.., Square)` (a 512 px JPEG with metadata dropped) ->
  `MUSIC_COVERS`. The store's lock is held from writing the file to committing `cover_hash` (the rule in `photos.rs`),
  and the store is pruned after that.
- **When**:
  - on the first fill;
  - when the URL changes, at most once a day (a signed URL may change its query at every fetch);
  - NRK's weekly root read picks up a new image or title.
- **Which cover wins**: the library entry's `cover` is the parent's cover, else this one.
- **Serving**: no new route. The PWA uses `/music-covers/{hash}`; the phones use `/api/devices/music/covers/{hash}`,
  scoped by the library's `covers`.
- **Episode art** stays a URL that the phone fetches lazily (20 §2.3).

### 2.7 Addresses (user decision 7)
- **One client for every source fetch** (add check, sweep, covers):
  - `.no_proxy()`;
  - at most 5 redirects, http and https only;
  - a `dns_resolver` that checks every answer at connect time, so DNS rebinding has no gap.
- **Private ranges**: 0/8, 10/8, 100.64/10, 127/8, 169.254/16, 172.16/12, 192.0.0/24, 192.168/16, 198.18/15, 224/3,
  `::`, `::1`, fc00::/7 (which includes the tailnet's fd7a:115c:a1e0::/48), fe80::/10 and ff00::/8. ::ffff:0:0/96 and
  64:ff9b::/96 are judged by their embedded IPv4.
- **What the parent entered or imported may be private**, so a feed on the home LAN or the tailnet works. The first
  check records where the target resolved (`lan`), and that never changes. A public target therefore stays
  public-only, and a lapsed domain re-registered to point at `192.168.1.1` is refused.
- **A hop from public to private is refused.** For a public entry (`lan` = 0):
  - the resolver drops private answers, which also covers redirects;
  - URLs taken from its feed (enclosures, item and channel images) are dropped when the host is a private IP literal
    or resolves only to private addresses (looked up once per host per check). Such a `url` or `art` becomes NULL, and
    such a cover isn't fetched.

  A LAN entry (`lan` = 1) may use both kinds of address.
- **NRK** (psapi, gfx.nrk.no) is always public-only.

## 3. PWA
English, light and dark, 360 px wide without horizontal scroll (long titles take one line with an ellipsis,
`min-width: 0`). Times say "UTC", like the tracked-app page. Every form redirects to its anchor, so nothing ever jumps
to the top.
- **Sweep setting** (`/music#sweep`, a small card above the library):
  - "Check for new episodes: every 1 / 3 / 6 / 12 / 24 hours". The select submits `onchange` to `POST /music/sweep`
    -> `#sweep`, logs the event `music_sweep_saved` and calls `wake()`.
  - Help text: "6 hours is what the Vibb Pi uses (NRK podcasts every 12 hours). Shorter means more requests to NRK and
    the podcast sites: about 2× the Pi at 3 hours and 6× at 1 hour. Entries no phone has are checked once a day.
    "Check now" on an entry checks it at once."
  - Help text: "Feeds on your home network or tailnet work. A public feed can never send the server or the phones to a
    private address."
- **Library cards** (`/music#entries`, one per entry, `partials/music_card.html`):
  - **Head**:
    - a 64 px cover: the parent's, else the source's, else the category tile;
    - the name, with the source's title under it when the two differ;
    - "NRK podcast · Podkast · on 2 phones", with ", home network" for a LAN feed;
    - "42 episodes · newest: <title> · 9 Oct". Add "first 100 episodes (the series has more)" when `capped`, and
      "list cut to the newest 812 (size limit)" when `cut`. Own-files entries keep "N files".
    - Step 1's up/down and Edit buttons stay.
  - **Status** (`.music-status`, a fixed height of two lines), one of:
    - "Checked 2 h ago";
    - "Checking…";
    - "Waiting for the first check (3 ahead)";
    - "Last check failed 9 Oct 14:02 UTC: the site answered 404. The phones keep the list from 8 Oct. Next try 14:30
      UTC.";
    - for an entry never listed: "Couldn't list it: NRK doesn't know that podcast - check the link. Next try 14:30
      UTC."

    The texts come from `CheckError::message` and `entry_error_text`.
  - **Check now** (`POST /music/entries/{id}/check`): it returns to the page it came from, `/music#entry-<id>` or
    `/music/entries/{id}`. Allowed once per entry per 15 min, 12 times an hour and 48 a day in all
    (`music_check_requests`). When it isn't allowed, the card shows "Check again after 14:20 UTC" instead of the
    button.
  - **Offline**: the `cache` select (None/1/3/5/10/20/All) as a one-field form. It submits `onchange` to
    `POST /music/entries/{id}/offline` -> `#entry-<id>`, logs `music_entry_saved` and nudges the phones with the entry.
    It is hidden for own files.
  - **Per phone**, for each phone with the entry ticked, from its `music_state`:
    - "Ella: 5 of 5 offline";
    - "Max: 2 of 5 · waiting for Wi-Fi";
    - "Lea: 1 failing (the source answered 403)";
    - "Ola: has an older list" (its `downloads` version isn't the current one);
    - "Ida: not reported yet".

    "(as of 9 Oct 14:02 UTC)" is added when the report is more than 1 h old.
  - **Entry page**: `/music/entries/{id}` shows the same status block and Check now.
- **Updating in place**: while any card says Checking or Waiting, `static/music-cards.js` fetches
  `GET /music/cards?ids=…` every 3 s for at most 5 min. The route is admin-only, takes at most 200 ids, is built with a
  few aggregate queries, and returns each card's status block. The script:
  - swaps only `#entry-<id> .music-status`, and never a card that holds `document.activeElement`;
  - before a swap, notes the top of the first visible card and `scrollBy`s the difference afterwards, because iOS
    Safari lacks scroll anchoring.

## 4. Device API and contract (21 §1.3 delta)
### 4.1 Library
- Each entry gains `items`: its listing version (16 hex), or null for own files and for NRK/RSS entries never listed.
- `items` is part of the hashed body. A new episode therefore changes the library version of exactly the phones that
  have the entry, and the policy's `library_version` plus the nudge carry it.
- `cover` falls back to the source's cover.
- The field adds about 30 bytes per entry, so `MAX_LIBRARY_BYTES` is unchanged. `tick_fits` gains the listing budget
  (§1). The policy shape is unchanged.

### 4.2 `GET /api/devices/music/entries/{id}/items`
- **Access**: bearer, scoped to the phone's ticks (anything else is a 404). It is also a 404 while the entry has never
  been listed.
- **Caching**: `ETag` = version, `If-None-Match` -> 304, gzip, `Cache-Control: no-cache`, using the library handler's
  helpers.
- **Body**: `{v:1, entry, version, items:[{key, title, url, hls, duration_ms, art}]}`, oldest first. It holds the `ok`
  and `gone` items (`url: null` = not available at the source now); `pending` items are left out.
- **Pinned** by `music_listing_snapshot` in `server/testdata/music_listing.json`, kept byte-identical in the music app.
- **Why a separate document**: 200 entries with up to 100-1000 items each would blow QA #4's 3 MB limit. This way a new
  episode moves one listing (a few KB gzipped) plus the small library, not everything.

### 4.3 Status `music_state` (known fields only, as in 21)
- **`item_errors: [{entry, version, item, error}]`**, at most 50: a failed download or stream of the listed `url`.
  - `item` matches `[A-Za-z0-9_-]{1,64}`.
  - `error` is `http_<code>`, `bad_media` or `network`. Only 401, 403, 404 and 410 flag (§2.5).
- **`downloads: [{entry, version, have, want, waiting}]`**, at most 200: the listing version applied, the items on the
  phone against what the keep rule wants, and `waiting` (`wifi`, `storage`, `roaming` or null).
- **`entry_errors` stays**, for the app's own trouble with an entry (`bad_listing`). 21's source error codes are now
  the server's.
- Still never positions or "now playing".

### 4.4 Nudge
- The existing SSE `nudge` goes once per pass (§2.3) to the phones whose library changed.
- On the phone: policy -> library (304 or new) -> the changed listings -> the bridge nudge.

### 4.5 Shared files and tests
- `music_library_snapshot` (now with `items`) and the new `music_listing_snapshot`. Both files are byte-identical in
  `music/` and compared like `phone_vectors.json`.
- 1c lists both files in the root CLAUDE.md's shared files. The `music.yml`/`launcher.yml` path filters take them when
  those workflows get music code.
- `policy_json_keys_snapshot` and `PolicyResponseCompatTest` are unchanged. Sanitizer tests cover the new status
  fields.
- An API change goes into both directories in one commit.

## 5. Bridge and launcher (21 §2/§3 delta)
- **AIDL**, appended after 21's methods (transaction codes follow the order):
  - `listingVersion(long entryId)` -> the stored version, or null when there is none;
  - `openListing(long entryId)` -> a read-only pfd.

  The music app compares the versions itself. The file stays byte-identical in both apps.
- **`reportState(Bundle)`** carries `item_errors` and `downloads`. The launcher maps the known keys into
  `music_state` and drops the rest.
- **`MusicSync`** now also reads `entries[].{id, items}`. After the library:
  - For every entry whose `items` differs from its stored listing's version: GET with If-None-Match, at most 2 MB,
    checking `v == 1`.
  - Store whatever version comes back, atomically, as `filesDir/music/items/<entry>.json`. Hashes have no order, and a
    check that lands between the two GETs is settled by the nudge that follows it.
  - Delete any listing the library no longer names.
  - A failure keeps the old file and is retried at every sync.
  - Restrictions and the bridge nudge come once, after the library and the listings.
- The launcher never talks to NRK or RSS hosts.

## 6. Music app (21 §4 delta)
- **Gone**:
  - from §4.3: the psapi/RSS listing, manifests, the app's own re-resolve of a failing URL, feed ETags, tracking
    prefixes, the RSS fallback, and their fixture tests (all moved to the server);
  - from §4.5: the 6 h listing poll and the listing refresh at start.

  The app requests nothing from a source except audio (downloads and streams) and episode art.
- **Sync**: bridge (library, listings, covers, files, login, restrictions) -> downloads -> prune -> `reportState`. It
  runs on the nudge (15 s settle), at start, and as a 6 h backstop that reads only the bridge.
- **Stays**:
  - listings in Room, filled from the bridge; playback never waits for the network;
  - downloads from `url` (`hls` picks HLS and QA #2's CacheKeyFactory) as WorkManager jobs (QA #1);
  - the download gate: NOT_METERED unless `mobile_data`, storage not low, paused while roaming.
- **Keep/prune** (vibb, 21 §4.5), on the server's list:
  - Keep the newest N (the last N of the list), all for -1, none for 0, plus the bookmarked item and the next one in
    play order.
  - A kept item whose `url` became null keeps its copy and plays it, as on the Pi.
  - The bookmarked item is never deleted.
  - Items outside the keep set are pruned as before, whether or not their `url` is null. Otherwise a daily feed would
    grow without bound. Removed entries are pruned too.
  - Prune runs only after a listing has been parsed (QA #3).
- **`url: null` without a copy**: the item is hidden. An NRK/RSS entry with `items: null` is hidden from the carousel
  until it is listed.
- **Failures**: a failed download or stream (an HTTP status from the source, or media that isn't audio) goes into
  `item_errors` with the listing version, at most once per item per sync. Downloads retry with WorkManager's backoff,
  and at once when the item's `url` changes.
- **NY/Nytt** come from listing changes (`_mark_new_seen`). Unchanged from 21.

## 7. Steps (21 §6 delta)
- **21a** needs one `wake()` after the import's commit (user decision); 1c adds it if 21a lands first. 21a's "a dead
  feed shows up as the phone's entry error" becomes the card's first-check error.
- **1c, the server sweep** (server only, the next server minor version, before step 2):
  - 0050 and `music_sweep.rs`, with the psapi/RSS parsers and recorded answers in `server/testdata/music_sources/`.
  - The one source client: §2.7, the limits per request kind, gzip, and the fair gate.
  - Covers; `items` in the library; the listing route and its snapshot.
  - The new `music_state` fields and the rechecks.
  - The sweep setting, the cards, Check now, the offline select and the in-place status.
  - `quick-xml` (with `encoding`) and reqwest's `gzip`.
  - NOTICE.md (vibb, MIT); the root and server CLAUDE.md; `music.rs`'s module doc.
  - **Checks on the Pi**:
    - entries: a real NRK podkast, an NRK serie of more than 100 episodes, a `serie/<slug>/<programId>`, two RSS feeds
      (one oldest first, one with a rolling window) and a LAN feed;
    - for those: the first fill, the logged request counts and the 304s;
    - robustness: a restart, and a kill -9 mid-fill that neither loops nor starts over;
    - a setting change, and the Check now limits;
    - a 21a import filled in the background;
    - a 404 feed keeping its list;
    - a public feed that redirects to a LAN address, refused;
    - an NRK serie's HLS URL from the day before still plays (if not, the re-resolve carries series);
    - `curl` of the listing (ETag/304);
    - the cards on a phone in light and dark, iOS Safari included.
- **Step 2** shrinks: the §4.3 sources are gone, and the debug loader takes a library plus listings (the snapshots or
  `curl`).
- **Step 3** adds the listing courier, the two AIDL methods and the new `reportState` fields.
- **Step 4** is unchanged.
- The deploy order is unchanged: this server goes out before any stable `music-v*`.

## 8. Tests (`src/tests/music_sweep.rs`, `music_sweep::tests`; a counting fake fetcher, a fake clock)
- **Parsing**, on recorded answers:
  - psapi: root, desc and asc pages with `_links.next`, MP3 and HLS manifests, `nonPlayable`, `encrypted`, a bad id, a
    stub repeated across pages, the years 9999 and 2100;
  - RSS: itunes and classic images; a guid, an empty guid, a duplicate guid, none; tracking prefixes; items without an
    enclosure; `file:`, `data:` and userinfo URLs; CDATA and entities; a Netscape DOCTYPE accepted and an internal
    subset refused; ISO-8859-1; oldest-first and newest-first feeds; 20 MB; NRK fallback guids.
- **Listing**:
  - first fill and incremental: only new and pending items get manifest calls, and the walk stops at the first known
    key;
  - a manifest failing mid-fill skips its item and the check succeeds; at most 10 pending retries a check, and `gone`
    after 14 days;
  - the budget commits its progress and the next check continues;
  - the caps: podkast newest, a front-anchored serie (with a 150-episode serie), `programId`, RSS dropping `gone`
    first;
  - the merge: a rolling window keeps old items as `gone`, and a returning one is `ok` again;
  - fallback -> psapi keeps its keys;
  - 304 and the body hash; a new parser version bypasses the validators; the `MAX_LISTING_BYTES` cut.
- **Keep last good**: root, page and feed failures, a timeout, too big, no items. Each leaves rows and version alone,
  sets the error and backoff, and sends no nudge.
- **Cadence**:
  - the table for every setting; ticked on no phone = 24 h; a tick wakes the sweeper;
  - the priority order: Check now, a single add before an import's backlog, rechecks;
  - the backoff capped at the interval, and daily after 7 days;
  - the start stamp: a crash backs off, and a Check now during a check runs again;
  - a fresh sweeper over recent stamps makes zero requests;
  - a setting change re-times due entries without rewriting anything;
  - a `wake()` during a pass is kept; an entry deleted mid-check writes nothing;
  - the host breaker;
  - a simulated day of 200 entries at 6 h stays within the Pi bound;
  - never two requests in flight, and an add check waits for at most one request.
- **Addresses**:
  - a LAN target is allowed and fixed as `lan`;
  - a public target that redirects or rebinds to each private range is refused, mapped IPv4 forms included;
  - enclosure and image URLs from a public feed that point at private hosts are dropped;
  - no proxy is used.
- **Covers**: fetched once; again on a new URL, at most daily; the weekly NRK root read; the lock held until the
  commit; pruning keeps listing covers; a lost one is refetched; the parent's cover wins.
- **Versions and nudges**: the same list gives the same version and no nudge. A change nudges, once per pass, only the
  phones with the entry.
- **Rechecks**:
  - only 401/403/404/410 reports at the current version flag;
  - one per item per 24 h;
  - two items within 24 h -> a whole-entry re-resolve, at most once per entry a day;
  - the server-wide budget of 300 a day;
  - the flag is cleared after any attempt;
  - a new URL bumps the version and nudges, the same URL doesn't, and no `playable` gives `gone`;
  - expiry re-resolves first.
- **Routes and status**: listing scoping, 404 before the first listing, ETag/304, gzip, both snapshots; `tick_fits`
  with the listing budget; the sanitizer for the new fields.
- **PWA**:
  - the setting and its help texts;
  - every card state (capped, cut, LAN) and the per-phone lines, "has an older list" included;
  - the Check now limits (per entry, per hour, per day) and its redirect from both pages;
  - the offline select;
  - the cards route (200 ids) and a status swap that keeps the view still;
  - 360 px in light and dark.
- **Launcher (step 3)**: only changed listings are fetched, any returned version is stored, an old one is kept on
  failure, gone ones are deleted, and `listingVersion`/`openListing` behave.
- **Music app (step 2)**: the shared listing; keep/prune (a withdrawn copy that is kept still plays, and the bookmark is
  never deleted); hidden null items; NY; `item_errors` from both downloads and streams.

## 9. Open question
1. **An NRK serie with more than 100 episodes** (Radioteatret's list starts in 1993). Today's rule keeps the first 100,
   so a story plays from its start, and the card says so. The alternative: the window follows the entry's play order,
   so "Newest first" keeps the newest 100, and changing the order refills the list. **Recommend the alternative**:
   series past 100 episodes are mostly anthologies, and a parent who picks "Newest first" wants recent episodes.

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

## Decisions after QA review (2026-10-09)

**User decisions**
1. **Check now may bypass NRK's 12 h TTL**, with the existing limits (15 min per entry, 12 an hour). The architect adds
   a daily cap of 48 against QA's worst case of 288 a day.
2. **A downloaded copy of a withdrawn episode keeps playing**, as on the Pi. Items that drop out of a feed stay with
   `url: null` and are not deleted.
3. **NRK follows the PWA setting**, `max(I, min(2I, 12 h))`, chosen over a fixed 12 h floor. The default 6 h equals the
   Pi. The PWA states the cost: about 2× the Pi at 3 h and 6× at 1 h.
4. **Feeds on the home LAN and the tailnet are allowed.** A hop from a public address to a private one is refused, and
   the PWA says so in one line (§2.7).
5. **Failing links**:
   - streams report too, with the same codes, and `network` never counts;
   - a flagged item is handled at the next free slot, at most one re-resolve per item per 24 h;
   - two or more distinct items in 24 h trigger a re-resolve of the whole entry, at most once per entry per 24 h;
   - renewed URLs are stored on the server and reach every phone with the entry (§2.5).
6. **21a calls `wake()`** after its import's commit.

**Findings**
1. **High, cadence: rejected by the user** (decision 3). NRK follows the setting; the PWA states the cost.
2. **High, one failed manifest: accepted.** A failing manifest leaves its item `pending` (at most 10 retries per check,
   `gone` after 14 days); only a root, page or feed fails a check; reaching the budget commits progress (§2.1, §2.3).
3. **High, a serie's start undone: accepted.** Serie and `programId` lists never evict from the front, and `capped`
   shows on the card (§1, §2.3). Which window a long serie should keep is open question 1.
4. **High, SSRF: accepted, changed by user decision 4.**
   - QA's client is used for every source fetch: connect-time resolver, the ranges, redirect rules, no proxy.
   - A target the parent entered or imported may be private. A hop from public to private is refused, and a target's
     class is fixed at its first check, which covers QA's lapsed-domain case (§2.7).
5. **Medium, report loops: accepted, changed.**
   - Accepted: only 401/403/404/410 at the current version flag, and the flag is cleared after any attempt.
   - Changed: instead of one recheck per entry a day, the limits are one per item a day plus one whole-entry re-resolve
     per entry a day (user decision 5), and 300 manifests a day server-wide (§2.5).
6. **Medium, crash safety: accepted.** The start stamp with `failures` + 1, `version IS NULL` in the due rule, and
   stamps from `now` (§1, §2.2).
7. **Medium, duplicate or empty keys: accepted.** Trimmed guids, duplicates keep the newest, NRK ids are checked (§1).
8. **Medium, serial feeds: changed.** The direction comes from the dated items (the majority of adjacent pairs) instead
   of sorting by `pubDate`, so undated items keep their place (§2.3).
9. **Medium, items leaving the source: accepted with one change.**
   - Accepted: the merge with `gone`, the cap dropping `gone` items first, the bookmark never deleted, and a kept
     withdrawn copy that still plays.
   - Changed: a downloaded `url: null` item outside the keep set is still pruned. Otherwise a daily feed's copies would
     grow without bound (§2.3, §6).
10. **Medium, in-place updates: accepted.** Status-only swaps, never the focused card, `scrollBy` compensation, a fixed
    height (§3).
11. **Low, the loop: accepted.** The fair gate; single adds and Check now go before an import's backlog; `due(now)` is
    re-read after each entry; `notify_one`; the existence check (§2.1-§2.3).
12. **Low, time and size: accepted.** Sizes and timeouts per request kind, gzip, the body hash, the TooBig text (§2.1,
    §2.3).
13. **Low, item URLs: accepted.** http/https only, no userinfo, at most 2000 characters for `url` and `art` (§1).
14. **Low, listing limits: accepted.** The listing cut, shown on the card as `cut`, and a 32 MB per-phone listing
    budget in `tick_fits` (§1, §4.1).
15. **Low, covers: accepted.** The lock held until the commit, a weekly NRK root read, covers at most once a day
    (§2.6).
16. **Low, NRK details: accepted.** Fallback keys = NRK's guid, expiry re-resolves first, years of 2100 and later mean
    always (§1, §2.3).
17. **Low, outages: accepted.** The per-host breaker, the backoff capped at the source's interval, once a day after 7
    days of failures (§2.1, §2.2).
18. **Low, unticked entries: accepted.** A first fill, then every 24 h; a tick wakes the sweeper (§2.2).
19. **Low, the parser: accepted.** A DOCTYPE without an internal subset is ignored, `encoding` is on, and the parser
    version gates the validators (§2.3).
20. **Low, contract: accepted, two changed.**
    - Changed: any version the server returns is stored, because hashes have no order.
    - Changed: `listingVersion` plus `openListing` instead of a status code.
    - Accepted as proposed: AIDL methods appended; the shared files listed; a nudge per pass with a 5 min flush;
      `downloads` carry the version (§2.3, §4, §5).
21. **Low, PWA: accepted.** The buttons stay; Check now returns to its page; times say "UTC" like the tracked-app page;
    `music_check_requests`; at most 200 ids with aggregate queries; the hint follows user decision 3 (§3).

## User answer to open question 1 (2026-10-09)
**The window follows the play order** ("Ja, følg rekkefølgen! Default er nyeste først gjerne"). "Newest first" keeps the
newest 100, "Oldest first" the first 100, and changing the order refills the list. The default (`auto`) for a capped NRK
serie (more than 100 episodes) is **newest first**, so a new anthology entry gets recent episodes. Read narrowly on
purpose: a serie of at most 100 episodes, a serial story, keeps vibb's oldest-first `auto`, and podkast/RSS are newest
first already. The PWA card says which 100 are kept. (If the user meant every entry, only `auto`'s table changes.)

## Step 1c implementation status (2026-10-09)

Implemented on `main` as server 0.22.0: migration `0051_music_sweep.sql` (0050 was already the library revision),
`src/music_net.rs` (the one client and gate), `src/music_sources.rs` (psapi and RSS parsers, recorded answers in
`server/testdata/music_sources/`), `src/music_sweep.rs` (cadence, checks, rechecks, covers, the listing, the loop),
the listing route, `items` and the cover fallback in the library, the new `music_state` fields and the flags, the
`#sweep` setting, the cards (`partials/music_card.html`), Check now, the offline select, `GET /music/cards` with
`static/music-cards.js`, the entry page's `#status`, `wake()` after an add, the 21a import's commit, a tick, Check now,
a flag and the setting. NOTICE.md credits vibb (MIT). Tests: `src/tests/music_sweep.rs` and the modules' own, all on
canned answers (no test touches the network), including a simulated day of 30 NRK + 10 RSS entries per setting that
makes exactly QA's counts (600/200/100/80/40). The PWA was checked in headless Chromium at 360 px, light and dark,
against a real server and a local LAN feed server: the error, "Checking…" and "Waiting…" states, a capped and cut
series, the per-phone lines, an in-place swap that keeps the first visible card still, Check now and the selects
returning to their card. The Pi checks of §7 are still to do (DEPLOY.md lists them).

**Deviations and readings**
- `encoding_rs` decodes a non-UTF-8 feed (by BOM, else its XML declaration) before `quick-xml` parses it, instead of
  `quick-xml`'s `encoding` feature (the same library underneath; the parser then works on `&str`).
- `music_listings` has three columns §1 doesn't list: `keep_end` (`newest`/`first`, which 100 an NRK list keeps),
  `item_count` and `bytes` (the cards and the 32 MB per-phone budget). `capped` means "the source has more than the
  list keeps" at either end (the card says "first 100" or "the newest 100").
- The user's answer is read narrowly, as written: the window follows the play order for an NRK **serie** only; a
  podkast keeps its newest 100 whatever its order. An `auto` serie keeps the newest 100 when it has more than 100
  episodes, and the library then sends its `order` as `newest_first` (no new field; the phone needn't know the
  window). Switching between orders when the whole series fits only moves `keep_end`, no refill. A full
  oldest-first serie makes no request at all at a check.
- A fill stopped by the budget or the breaker commits its items without counting `no_items`. A first fill whose every
  manifest failed counts `no_items` but keeps the stubs with their attempts, so the retry continues (QA #2).
- The breaker counts network errors from any request but a 5xx only from a root, page or feed: a manifest's 5xx is
  that programme's trouble (QA #2's broken programme would otherwise defer every NRK entry). A broken host's entries
  wait 15 minutes (kept in memory), so the next pass doesn't retry it at once.
- A lost source cover keeps its `cover_url` in `music_listings` (only `cover_hash` is forgotten), so the next check
  fetches it again without re-reading the NRK root.
- The status block is at least two lines (`min-height`), not a fixed height: a long error text may take three or four
  lines at 360 px rather than being cut, and the swap's `scrollBy` keeps the view still when it grows.
- Items whose URL the address or URL rules drop are stored `gone` (`url: null`). A recheck that can't reach psapi
  leaves the item's URL as it was (only "not playable" makes it `gone`).
- The recheck budget counts items with `rechecked_at` in the last 24 h: NRK manifests, expiry re-resolves and the
  items of an RSS recheck (one feed GET).
- The offline select logs `music_entry_saved` (§3); the entry page's form still logs `music_entry_changed`.

**Open for the user**: none blocking. The version is 0.22.0 ("the next server minor version", §7), although 0.21.0
was never released - shipping both as 0.21.0 is a one-line change if preferred.
