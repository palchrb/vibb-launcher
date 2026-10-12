# 27 - Vibb music: per-episode art and the Now Playing carousel (buildable spec)

Architect, 2026-10-12. Sources: design 20 (decision sections), 21 (§4, step statuses), 21b (§1-§6 and its QA), 25 (the
lock's tile), 26 (coordination), the code on `main` (`acc8938d`), and palchrb/vibb **branch `pipewire`** (MIT):
`pi/vibb/content.py` (`_pick_image`, `_episode_stub`, `_series_image`, `_parse_feed`, `_episode_image_file`,
`_download_image`, `_sync_episode_images`, `_image_local_or_remote`, `prune_cache`), `pi/ui.py` (`render_now`,
`artwork_async`, `episode_art`, `_art_disk`, `handle_carousel`, `NOW_RETURN_S`), `pi/daemon.py` (/status art), and the
tests `covers.py`, `ui_cover.py`, `ui_episode_row_art.py`, `wake_mpv_art_local.py`, `now_playing.py`,
`media_track_art.py`. Mockup: `docs/design/mockups/vibb-music/Main.dc.html` (the player view; v18).

**The request (user, 2026-10-12):** "Download per-episode album art and also show a carousel in Now Playing. Some
podcasts have per-episode album art, others don't, and we must handle that. vibb on the Pi already does this, at
least for NRK podcasts."

**User decision (2026-10-12):** a podcast without per-episode art looks exactly as today: one cover (the series art)
and no carousel. The carousel is only for entries whose episodes really have their own art. §3.1 has the rule for
mixed feeds.

## 0. What vibb does, and what is new

**vibb has per-episode art. It has no Now Playing carousel.**
- **Art (ported):**
  - NRK episodes take `_pick_image(squareImage or image)`, the series takes its own `squareImage`. An RSS item takes
    `itunes:image` and otherwise the channel image. `media:thumbnail` is not read.
  - The art of the episodes kept offline is fetched at every sync. It is stored as a 300 px JPEG (q85) next to the
    audio, once. Episodes that aren't kept are fetched lazily when shown.
  - Now Playing shows the episode's own art. While that loads it keeps the previous picture of the same entry (no
    flash). Offline, or with no art of its own, it shows the series cover.
  - The episode list's highlighted row shows its own art, else the series cover.
  - There is no duplicate check against the series image.
- **Carousel:** vibb's only carousel is the browse carousel, one tile per entry with the series cover. Flipping is a
  preview, and A plays. Now Playing is a single cover, with B/Y as previous/next.
- **New here, so not a vibb port:**
  - the Now Playing carousel (§3), where a swipe switches episode (user decision 1);
  - the server doing the fetching and resizing (§1, in place of the Pi's sync);
  - the "same picture as the series cover" check (§1.3), which the carousel rule needs.

**This replaces** design 20 §2.3's and 21b §2.6's "episode art stays a URL that the phone fetches lazily" (step 2
never built that: `PlayItem` drops `art`). The server fetches it instead, because:
- RSS art is often a 1500-3000 px original of several MB, and the server cuts it to 512 px once for every phone.
- §2.7's address guard already runs there.
- The phone never opens a new network path.
- One sweep serves all phones (21b decision 1).

## 1. Server

### 1.1 Source and fallback (`music_sources.rs`, `music_sweep.rs`)
- **NRK podkast and serie:**
  - The stub's `squareImage`, else its 16:9 `image` (centre-cropped), as today.
  - The pick is now **the smallest variant of at least 512 px** (vibb picks 300; we store 512 px, the same rule as
    the series cover in §2.6). An episode that uses the series picture then has the series cover's URL, so nothing is
    fetched for it (§1.3).
  - Stored `art_url`s from the old 300 px pick stay until NRK sends the stub again. The print check (§1.3) catches
    them.
- **NRK `serie/<slug>/<programId>`:** the metadata walk has no image (vibb sets `image: None`), so there is none.
- **RSS and NRK's RSS fallback:** the item's `itunes:image@href` (§2.7's `feed_url` filter applies). Not
  `media:thumbnail` or `media:content`, as in vibb.
- **The fallback is the phone's job.** An item with no art of its own gets `art: null`, and the phone shows the
  entry's `cover`: the parent's cover, else the source's (21b §4.1). vibb's RSS `img or chan_img` gives the same
  picture.

### 1.2 Data (migration `00NN_music_episode_art.sql`, numbered after the final rebase; today the next is 0068)
New columns only:
- **`music_items`:**
  - `art_hash` TEXT: the 512 px JPEG's SHA-256 when the art is the item's own.
  - `art_from` TEXT: the `art_url` that the art fields describe.
  - `art_state` TEXT CHECK IN (`own`, `same`, `failed`):
    - `own`: own art, stored in `art_hash`;
    - `same`: the same picture as the series cover;
    - `failed`: the fetch or decode failed.
  - `art_print` INTEGER: a 64-bit dHash of the stored JPEG.
  - `art_at` TEXT: the last attempt.
  - `art_fails` INTEGER NOT NULL DEFAULT 0.
  - Index: `ON music_items(art_hash) WHERE art_hash IS NOT NULL`.
- **`music_listings`:**
  - `cover_print` INTEGER: the source cover's dHash, made when the cover is stored, or read from the stored file at
    the next check, without a fetch.
  - `art_backlog` INTEGER NOT NULL DEFAULT 1 (§1.4).
- **The `photos::MUSIC_ART` store**, in `data/music_art`:
  - `referenced_sql` = `SELECT DISTINCT art_hash FROM music_items WHERE art_hash IS NOT NULL`;
  - `forget_sql` sets `art_hash`, `art_state`, `art_from` and `art_print` to NULL, so a lost file is fetched again.
  - **It is not backed up.** It can be rebuilt, and thousands of pictures would swell every zip. `backups.rs` doesn't
    add it.
  - `recover_missing` runs at startup like the other stores. After a restore it finds no zip entry and forgets the
    rows, and the budget (§1.4) fills them in again.
  - Its own lock. `AppState.music_art_dir`.

### 1.3 One item's art: fetch and resize
`art_url` set and `art_from != art_url` (never tried for this URL), or `failed` with `art_fails < 3` and `art_at` more
than 24 h ago. In order:
1. **`art_url` equals the listing's `cover_url`:** `same`. No request.
2. **Another item (any entry) already has a result for this `art_from`:** take its `art_hash` and `art_print`, then
   judge `own`/`same` against this entry's `cover_print`. No request.
3. **Otherwise one GET** through the gate:
   - `Kind::Image` (10 MB, 60 s), `Reach::Public` unless the entry is `lan`, the same `public_media` check as covers;
   - then `photos::process_limited(.., Square)`: a 512 px JPEG at q85, metadata dropped. That is the same pipeline
     as covers, and vibb's `_download_image` at 512 instead of 300.
4. **`photos::print(jpeg) -> i64`:** a 9x8 grayscale dHash. Hamming distance ≤ 5 from `cover_print` means `same`
   and nothing is stored. Otherwise `own`, and the file goes to `MUSIC_ART`. The store lock is held from writing to
   the commit (the rule in `photos.rs`), as for covers.
   - The print check catches the series picture under another URL, size or re-encode: a cache-busting `?ts=`, NRK's
     300 vs 600 px variants.
   - A byte compare would miss all of those.
5. **Failure** (network, 4xx/5xx, too big, undecodable): `failed`, `art_fails` + 1. It never fails the check, and
   the item shows the series cover.
6. **A new series cover** (a new `cover_print`): every `own`/`same` item of the entry is judged again from its
   stored print. No requests.

### 1.4 When, and the cost ("not more than the Pi")
- **Where:** in `check_entry` after the listing, the manifests and the cover (`cover_print` is needed first). It
  counts toward the check's 250-request budget. Results are committed in `finish` with the items.
- **Order:** the entry's **play order** (21 §4.4: newest first for podcasts, from episode 1 for an oldest-first
  serie), which is what the kid reaches first. New items of this check come first. Only `ok` items, and only the
  first **200 in play order** (`ART_WINDOW`). Items past that, and `gone` items, keep what they have and get nothing
  new.
- **Budgets:**
  - at most **20 image GETs per check** (`ART_PER_CHECK`);
  - at most **400 a day server-wide** (`ART_PER_DAY`, counted from `art_at`, like the recheck budget).
  - Steps 1-2 and step 6 cost no request.
- **Entries without own art** (`art_backlog`):
  - If an entry's first 5 fetched images are all `same`, `art_backlog` = 0. Then only the new items of each check
    are tried (one GET each, if their URL is new).
  - The first `own` result sets it back to 1.
  - This keeps a feed whose every item points at the channel picture under a fresh URL from costing 200 GETs.
- **Cost against one Pi:**
  - **Steady state:** at most one image GET per new episode with a URL not seen before. The Pi fetches every kept
    episode's image once per Pi (default depth 5, or 50 on old Pis), plus lazy fetches whenever art is shown. So
    the server costs the same or less, once for all phones.
  - **The backfill is a one-time cost:**
    - Example: 30 NRK entries × 100 episodes is at most 3,000 GETs to `gfx.nrk.no` (NRK's image CDN, not psapi).
      At 20 per check and 400 a day that spreads over at least 8 days.
    - Most of it disappears in steps 1-2 (podcasts without own art share the series URL from the 512 pick on).
    - Bandwidth is about 50-300 KB per NRK variant, so at most about 0.5 GB in all, once.
- **Storage on the Pi:**
  - A 512 px JPEG is about 40-70 KB. Expect ~150 MB at most for 30 NRK entries with full own art. The 1000-item RSS
    worst case is capped by `ART_WINDOW`.
  - Pruned with the items: `MUSIC_ART.prune` after every `finish` that changed an art reference, and after an entry
    is deleted (21b's cascade drops its items).
- **Logging:** the check's log line gains `, art 7 (3 own, 4 same)`, and `requests` counts the image GETs.

### 1.5 The listing contract (both copies byte-identical, one commit)
- **Items:** `art` changes meaning. It was the source URL; it is now **the 64-hex hash of the item's own 512 px
  JPEG, or null**. Null means none, the series picture, not fetched yet, or failed. This matches the library's
  `files[].art`. The phones never needed the URL.
  - Fields and order are unchanged: `{key, title, url, hls, duration_ms, art}`. Regenerate
    `server/testdata/music_listing.json` with `MUSIC_LISTING_SNAPSHOT_WRITE=1`, with an own-art hash on one item and
    `null` on the other, and copy it byte for byte to `music/app/src/test/resources/` (`ContractFilesTest`). The
    music app's parser change (§2.1) goes into the same commit.
  - New art changes a listing's version, and that is nudged once per pass (21b §2.3). The phones fetch the small
    listing again; nothing else moves.
  - **Compatibility:**
    - An older music app reads `art` through `safeUrl` and gets null for a hash, which is harmless.
    - A newer app on an older server rejects a URL as not a hash, which is also harmless.
  - The library document is unchanged (`music_library.json` stays). Episode art is **not** in `covers`, so the
    launcher doesn't fetch it all.
- **Serving:** `GET /api/devices/music/covers/{hash}` (no new route): first `cover_in_library`, else the new
  `art_in_library(device, hash)`, which checks that some ticked entry has a listed item with this `art_hash`.
  `art_in_library` reads from `music_art_dir`, and anything else is a 404.
- **PWA:** the card's count line adds "· 31 with episode art" when there are any. The sweep help text adds "Episode
  pictures are fetched a few at a time (at most 400 a day)." Nothing else; no scroll changes.

## 2. Phone

### 2.1 Data (`music/`)
- **`model/Listing.kt`:** `art` is kept only when it matches `[0-9a-f]{64}`, else null.
- **`ItemRow`:** gains `artHash` (Room version +1 with an `@AutoMigration` and the schema exported; pick the version
  after the final rebase, since the phase 1b-3 session may bump it too).
- **`applyListing`** stores it, and `buildSnapshot` puts it in `PlayItem.artHash`, which now means "the item's own
  art" (an own file's embedded art, or an episode's).
- From then on **every surface that already does `item.artHash ?: entry.cover` gets episode art**: the library's
  now-playing bar, Nytt rows, search hits, the session's artwork (the media notification), and through
  `setNowPlaying`/the session the launcher's Home card and the lock's tile (design 25). The launcher's UI doesn't
  change.

### 2.2 Which art is on the phone, and when (vibb `_sync_episode_images` + the lazy fetch)
- **The music app wants:** pure `logic/EpisodeArt.kt`, `wantedArt(...)`. Only own-art hashes (`artHash != null`) not
  already in `filesDir/music/covers/`, deduplicated, at most 600, in two lists:
  - **`now`, at most 5:** the now-playing queue's current item and its two neighbours on each side, wrapping as
    previous/next do. This is the carousel's view, and vibb's lazy fetch of what is shown.
  - **`later`:** the art of every item in an entry's keep set (`keepSet`: the newest N, the bookmarked item and the
    next one), which is vibb's "art for the episodes kept offline"; then the Nytt tray's items, for their mini
    covers.
- **The courier:** a new AIDL method, **appended** to `IMusicBridge.aidl` in both apps (byte-identical; `BridgeTest`,
  `MusicPlanTest`): `oneway void wantArt(in String[] now, in String[] later);`
  - An older launcher ignores the unknown oneway transaction, and the app then shows series covers, as today.
  - **Launcher, `MusicBridgeService.wantArt`:**
    - checks the caller, keeps only 64-hex hashes, caps the total at 600;
    - stores both lists atomically in `MusicStore` (`filesDir/music/want_art.json`);
    - when one of them isn't on disk, calls `MusicCourier.request` with the cached policy's music part.
  - **Launcher, `MusicCourier`'s new `syncArt`**, after `syncCovers`:
    - fetches each missing hash with `server.cover(hash)` (the same route, at most 1 MB, hash-checked like a cover):
      - all of `now` on any network;
      - `later` only while the active network is NOT_METERED or the policy's `mobile_data` is on. This is the
        audio's download gate (21 §4.5), since the kept art goes with the kept audio.
    - New art means the existing `BRIDGE_CHANGED` nudge.
    - `syncCovers` stops deleting files that are in the want set. `syncArt` deletes art in neither the library's
      `covers` nor the want set.
    - So the launcher holds an episode picture only until the music app has copied it: the next `wantArt` leaves
      that picture out, because it is held.
- **Music app, `BridgeSync.pullCovers`:**
  - also copies every wanted hash the launcher has (`openCover`, hash-checked as today);
  - then sends `wantArt` at the end of the sync (next to `push`).
- **`LiveBridge`** also sends it from the player on a queue start or an item change, debounced 2 s, and only when the
  `now` list has something missing and differs from the last one sent.
- The phone itself never opens a connection for art.

### 2.3 Cache and prune (vibb: kept art lives next to the audio; `prune_cache`)
- One directory, `filesDir/music/covers/<hash>`, for covers and episode art (both are content-addressed 512 px JPEGs).
- **At each sync, after the pull, delete** every file that is neither in the library's `covers` nor in the want
  set's universe (the same rule, held files included). Fixes the gap that the app never pruned covers.
  - So a kept episode's picture stays as long as its audio.
  - A neighbour's picture goes once the kid has moved on and it isn't kept.
- Worst case about 600 × 60 KB = 36 MB. Typical (30 entries at depth 5) about 210 pictures, about 12 MB. They count
  in the report's `cache_bytes`.
- `PlaybackService`'s ≤ 300 px copies in `cacheDir/art/` stay a cache, as today.

### 2.4 Offline and the fallback
- **A kept episode** has its picture, fetched with its audio (vibb `covers.py` 6: "per-episode artwork once, only for
  kept episodes").
- **Anything else** shows the entry's cover until its picture arrives, and stays that way offline (vibb
  `_image_local_or_remote` and `render_now`'s `artwork_local`).
- **No art of its own** (`art: null`): the entry's cover, always.
- **No flash** (vibb `addf84eb`, `ui_cover.py` 4b):
  - `Cover` gains `fallback: String?`. It draws `coverBitmap(art) ?: coverBitmap(fallback)`, and the category
    gradient only when neither has decoded.
  - An episode picture still decoding therefore shows the series cover, never the glyph.
  - `artOrCover(item, entry, has)` in `EpisodeArt.kt` picks the hash for the session and for `setNowPlaying`: the
    item's own art when the file exists, else the entry's cover.
- **The launcher's surfaces:**
  - `setNowPlaying` looks up `artOrCover` every time it reports (each item change and the 15 s save), so the Home
    card and the lock's tile get a late-arriving picture within 15 s.
  - The media session's `artworkUri` is fixed when the queue's `MediaItem`s are built. Art that lands later shows up
    in the notification at the next queue build (a cover tap, a list pick, a restart).
  - `activeFor`'s art map becomes lazy (`artFor(hash)` at build and report time) instead of all hashes up front.

## 3. The Now Playing carousel

### 3.1 When it shows (user decisions 2026-10-12, QA H4; vibb has no carousel)
- **Pure `showsCarousel(queue, cover)` in `EpisodeArt.kt`,** over `NowView.queue` (the visible items, in the queue's
  order) and the listing's hashes (not over which files are on the phone, so the layout never flickers while pictures
  arrive). The carousel shows when the queue has more than one item and its pages show **at least two different
  pictures**, each page being the item's own art or else the entry's cover (the fallback counts as one picture).
- **Otherwise the player is exactly today's:** one cover, the item's own art or else the entry's cover.
- **Cases:**
  - A podcast where a single episode has its own picture: two pictures, so the carousel. Episodes without art show
    the series cover in it (mockup: "Askeladden").
  - A podcast without episode art: one picture, today's player.
  - An own-files compilation with a cover per track gets the carousel (vibb `70a22d4e` "every song wears its own
    cover"); an album whose songs all carry one cover doesn't, and its single cover is the track's art.
  - **Later phases:** Storytel series with one cover per book (vibb `entries_for`) can set `artHash` and get it too.
- **Server backfill:** the art for the first items in play order arrives first (§1.4), so an entry switches from no
  carousel to carousel at most once, early.

### 3.2 Behaviour (the phone's idiom: the music app's own library carousel, and swipe-to-skip on a Now Playing cover)
- **The pages are the queue**, `NowView.queue` in its order: rotated to the bookmark, or the shuffled order when
  shuffle is on, the same order the list shows. It wraps like previous/next (`nextAction`). It uses virtual pages, like
  the library (`carouselPageCount`/`carouselIndex`/`carouselStartPage`), so two items wrap too.
- **Swipe: switches to that episode (user decision 1).**
  - When the pager settles on another page after the user's drag or fling, the player goes there:
    `actions.skipTo(key, forward)` with the direction of travel.
  - It is the same as previous/next, except that a swipe back always goes to the previous item. The "previous
    restarts after 5 s" rule is for the button: on a swipe, the kid sees the picture she picked.
  - The item starts at its own position when resume is on (`startPos`, as next). Paused stays paused, playing goes on
    playing.
  - A fling across several pages is **one** skip, to where it landed.
- **Tap:**
  - a tap on a neighbour turns to it (animated) and switches;
  - a tap on the centre picture is play/pause, the same as the big button (vibb's main press in Now Playing; in the
    library a tap on a cover plays).
- **An unplayable target** (not downloaded and no Wi-Fi): the service skips on to the next playable item in that
  direction and shows "Ikke lastet ned - trenger Wi-Fi", as next does. The pager follows the player's index.
- **The pager follows the player:**
  - When the player's index changes for any other reason (the buttons, a headset, auto-advance, a list pick, Nytt,
    search), the pager animates one page, or jumps when it is further.
  - A new queue (a cover tap, a list pick, the shuffle toggle) re-keys the pager, which jumps straight to the current
    item.
  - While its own skip is pending, the pager isn't pulled back: a swipe is a `PlayRequest` (`logic/Switch.kt`, QA H3),
    shown while `requestStands`; it ends as soon as the player moves, to the target or elsewhere.
- **Motion**, exactly as the library's (21 §4.2):
  - the finger is followed, then a no-bounce spring snap (stiffness 700);
  - neighbours scale 1 → 0.733 and fade 1 → 0.45 with the page offset, pulled in so their near edge stays put;
  - with animations off everything jumps;
  - no gesture-exclusion rects, so the screen edges stay the system's back gesture.
- **Text:** the marquee title and "Episode N av M" under it follow the page the pager is landing on (cross-fade
  200 ms, keyed by item), which is the playing item once it settles.
- **TalkBack:**
  - centre "Pause: <title>" / "Spill: <title>";
  - neighbours "Forrige: <title>" / "Neste: <title>" (the existing `previous_entry`/`next_entry` strings);
  - the pager's scroll actions do the same as a swipe.
- **Unchanged:**
  - the seek bar: its own 44 dp row, drag and tap, seek on release. The carousel has no seek;
  - the control row, Liste, Sov and "Spill av på";
  - the list's rows (number badges; vibb's 56 px row art isn't ported, since our list has no highlighted row).
- **Sleep timer:** a swipe is no pause, so it doesn't cancel the timer. The tap on the centre picture is a pause, and
  cancels it as the button does. The fade and the pause at the end are unchanged.
- **After a restart, and `EXTRA_RESUME`:**
  - The player opens on the restored item (pager initial page, no animation).
  - A swipe on the restored, paused queue goes through `restoreThen(THEN_SKIP_TO)`, like next: it stays paused,
    and nothing ever starts by itself.
  - The Home card's `EXTRA_RESUME` works as today (honoured once). The carousel only shows where that resume lands.
- **The launcher:** Home's card, the lock's tile (design 25) and the media notification show the current episode's
  picture (§2.4). They get no carousel.
- **No preview state:** nothing to time out, so vibb's `NOW_RETURN_S` snap-back has nothing to undo.

### 3.3 Layout (pure `Fit.kt`; `FitTest` at 320, 360 and 411 dp)
- The carousel replaces only the cover box (the `weight(1f)` box that today centres a cover of at most 224 dp). The
  header, title, seek line, the v12/v15 control row (`controlRow`; round buttons `requiredSize`, never squeezed),
  and the bottom bar are unchanged.
- **Size:** `playerArt(widthDp, roomDp, carousel)` = min(224, the box's height, width − 2 × (8 + 36)) with the
  carousel, else min(224, height) as today. Pages are `A` wide and 8 dp apart; `contentPadding` = (width − A)/2.
  `playerPeek` = (width − A)/2 − 8 is always ≥ 36 dp and never more than the scaled neighbour (0.733·A).

| Width (inside 16 dp sides) | Box height | A | Peek each side |
|---|---|---|---|
| 320 (288) - Jelly Star, ~154 dp of room | 154 | 154 | 59 |
| 320 (288) - mockup v16, 568 tall | ≥ 200 | 200 | 36 (36 + 8 + 200 + 8 + 36 = 288) |
| 360 (328) | ≥ 224 | 224 | 44 |
| 411 (379) | ≥ 224 | 224 | 69 |

- Radii: the centre is 26 dp (as today's cover). The neighbours' outer corners are cut by the screen sides, like the
  library's peeks.
- `FitTest`:
  - the row (2 peeks + 2 gaps + A) equals the width at every size;
  - A ≤ the room;
  - the peek ≥ 36;
  - the control row's existing cases still hold.

## 4. Coordination (the phase 1b-3 session works in `music/` on Sonos, Storytel and Spotify)
The change is local; the shared hot spots are small and named here.

**Files touched:**
- **Server:**
  - `src/music_sources.rs`: the 512 pick;
  - `src/music_sweep.rs`: the art pass, the listing's `art` = hash, the log line;
  - `src/photos.rs`: `MUSIC_ART`, `print`;
  - `src/music.rs`: `art_in_library`;
  - `src/handlers/music_api.rs`: the cover route's second store;
  - `src/main.rs`: `music_art_dir`, startup recovery;
  - `src/handlers/music.rs`: the card's count line and help text, plus its template;
  - `migrations/00NN_music_episode_art.sql`;
  - `src/tests/music_sweep.rs`, `src/tests/music.rs`;
  - `testdata/music_listing.json`;
  - `CLAUDE.md` ("Vibb music library");
  - `DEPLOY.md`: `data/music_art` isn't backed up.
- **Launcher:**
  - `app/src/main/aidl/me/vibb/bridge/IMusicBridge.aidl` (appended);
  - `music/MusicBridgeService.kt`, `MusicStore.kt`, `MusicSync.kt` (`syncArt`, `syncCovers`' keep rule);
  - `MusicPlan.kt` (pure `artToFetch` / `artToDelete`);
  - the tests `MusicPlanTest` (including its listing-version pin, updated with the listing snapshot - QA M4),
    `BridgeTest`.
- **Music app:**
  - the same AIDL;
  - `model/Listing.kt`, `model/PlayItem.kt`;
  - `data/MusicDb.kt` (+ `schemas/`), `data/Snapshot.kt`, `data/Store.kt` (prune helper);
  - `logic/EpisodeArt.kt` (new);
  - `sync/Sync.kt`, `bridge/BridgeSync.kt`, `bridge/Bridge.kt` (`LiveBridge.wantArt`);
  - `playback/PlaybackService.kt`: the `SKIP_TO` command and `THEN_SKIP_TO`, the lazy art map, `setNowPlaying`'s art;
  - `ui/EpisodeCarousel.kt` (new);
  - `ui/PlayerScreen.kt`: **only** the cover box becomes `PlayerArt(now, …)`;
  - `ui/Widgets.kt` (`Cover`'s fallback);
  - `ui/LibraryScreen.kt`, `ui/ListScreens.kt`: the `Cover` call sites pass the fallback;
  - `ui/MainActivity.kt`: `skipTo` in the actions;
  - `ui/MusicRoot.kt` (wiring), `ui/UiModel.kt` (`NowView.carousel`), `ui/Fit.kt`;
  - `res/values*/strings.xml` if a label is new;
  - the tests;
  - `src/debug/` feed: listings may carry art hashes, and `covers/` already takes them.

**Overlaps with the 1b-3 session:**
- `PlayerScreen`: one block. The output picker is not touched.
- `PlaybackService`: one custom command next to next/previous. Sonos must route `SKIP_TO` as it routes next (QA M7 e).
- The merged `music-cover-instant` work (`logic/Switch.kt`, `NowView.cover`, `PrefetchCovers`, QA H3): a swipe is a
  `PlayRequest`; `PrefetchCovers` stays for the single-cover look.
- The AIDL: if both sessions append, the later merge goes last, in both copies.
- The Room version.
- `MainActivity`'s actions interface: one method.

Rebase on `main` before the last test run. The Storytel phase can reuse `PlayItem.artHash` for per-book covers.

## 5. Steps (one implementer; each step green on its own, with its tests)
1. **Server + the listing contract** (one commit with the music app's `Listing.kt` parse and the copied
   `music_listing.json`): §1.
   - Tests (counting fake fetcher, fake clock):
     - the 512 pick on the recorded psapi pages;
     - RSS `itunes:image` per item;
     - step 1 (`cover_url` equal: no request);
     - step 2 (a URL seen in another entry: no request, judged per entry);
     - the print (the series picture as a 300 px variant and as a re-encode: `same`; a different picture: `own`;
       Hamming at 5 and 6);
     - failure, retry after a day, giving up after 3, and a new URL resets;
     - 20 per check, 400 a day, play order first, `ART_WINDOW`, `gone` gets nothing new;
     - `art_backlog` off after 5 `same` and back on with an `own`;
     - a new cover re-judges with no requests;
     - the lock held until the commit;
     - prune after `finish` and after a delete;
     - not in the backup zip;
     - `recover_missing` forgets, and the next check fetches again;
     - the cover route serves art only for a ticked entry's listed item (404 otherwise, another phone, `pending`);
     - the listing snapshot;
     - a simulated day of 30 NRK + 10 RSS entries stays within the budgets.
   - Checks on the Pi: a real NRK podcast with episode art, one without (no carousel later), an RSS feed with per-item
     art, the log lines and `requests`, `du -sh data/music_art` after a day.
2. **The courier** (launcher + the music app's bridge part + the same AIDL in both): §2.2-§2.3.
   - Tests:
     - `wantArt` validation and caps;
     - `artToFetch` (`now` always, `later` only unmetered or with mobile data);
     - `artToDelete` (keeps library covers and the want set);
     - `syncCovers` no longer deletes wanted art;
     - the AIDL identity;
     - `wantedArt` (keep set, ±2 wrapping, Nytt, held ones left out, 600 cap, `now` first);
     - the music app's prune (only on a parsed library; a kept item's art stays);
     - an older launcher (no `wantArt`) leaves series covers.
3. **The player** (music app UI + service): §2.4, §3.
   - Tests:
     - `showsCarousel` (all own; one own picture among 300 shows it; none doesn't; an identical-picture album
       doesn't; one item; own files);
     - `artOrCover`;
     - the pager rules as pure functions (settle → one skip with its direction, a fling = one skip, the follow on an
       outside index change, re-key on a new queue, pending suppression);
     - `SKIP_TO` (resume position, paused stays paused, the unplayable skip-on, swipe back never restarts);
     - `setNowPlaying` picks up late art;
     - `FitTest` for §3.3;
     - `SourceScanTest`: no new screens or links.

## 6. Device checks (emulator 320x569 dp nb + en, 360 and 411 dp; then the Jelly Star)
- **The two looks:**
  - an NRK podcast with episode art (carousel, its pictures, an episode without art showing the series cover);
  - an NRK podcast without episode art, and an audiobook-like serie: exactly today's player;
  - an own-files compilation.
- **Swipe:** one page, a fling over three (one skip), a tap on each neighbour, swipe back at 2 min into an item (no
  restart), a swipe onto an undownloaded episode in airplane mode (skips on, message).
- **The pager follows:** the buttons, BT headset next, auto-advance at an item's end, a list pick, the shuffle toggle
  on a compilation (jumps, current stays centred).
- **Sleep:** set 15 min, swipe (still running), tap the picture (pauses and cancels).
- **Restart:** kill the app, then Home's card with `EXTRA_RESUME` (opens on the item, paused), then swipe (stays
  paused).
- **Offline:** airplane mode, open a kept episode (its picture) and an unkept neighbour (series cover, no glyph
  flash).
- **Arrival:** Wi-Fi on, the neighbour's picture arrives within a sync. On mobile data without the switch, the `now`
  pictures arrive and the kept ones wait.
- **The launcher:** Home's card and the lock's tile show the episode picture within 15 s of it landing. The
  notification shows it after the next queue start.
- **Measure:** `du` of the app's `covers/` after a day (≈ the keep set), and the launcher's `covers/` (only library
  covers plus what is in flight).
- **Gestures:** gesture-nav back from both screen edges over the carousel still goes back. TalkBack over the
  carousel. Animations off.

## 7. Defaults chosen (veto if wrong)
1. The server fetches and resizes (512 px). The phone never fetches art from a source (replaces 20 §2.3 / 21b §2.6).
2. An episode's own picture wins over the parent's entry cover in the player. The parent's cover stays the library
   tile and the fallback. vibb has no parent override for episodes.
3. The budgets: 20 a check, 400 a day, the first 200 episodes in play order. Older episodes past 200 show the series
   cover.
4. A tap on the centre picture is play/pause.
5. Own-file entries follow the same carousel rule.

## 8. Open questions for the user (answered: see "User decisions" below)
1. **What does a swipe do?**
   - **Recommendation: it switches to that episode at once,** as swiping the cover does in phone music players. The
     place is kept (resume), so a mistaken swipe costs one swipe back. A preview would leave the title, seek line and
     buttons referring to a different episode than the picture.
   - The alternative is like the library and vibb's flip: a swipe only previews, a tap on the picture plays it, and it
     snaps back to what is playing after 10 s.
2. **Mixed feeds:** is "at least half of the first 10 episodes in play order have their own picture, and at least two
   different pictures" the right line for showing the carousel? Below it the player looks as today, and the odd
   episode with its own picture still shows it.
   - **Recommendation: yes.** It follows what the kid plays first, and doesn't flip back and forth.

## User decisions (2026-10-12)

1. **A swipe switches episode** (the recommendation). The episode plays at its saved place, title and seek bar
   follow the picture, and a tap on the centre is play/pause.
2. **Mixed feeds: the carousel shows as soon as at least one episode has its own picture.** This replaces the
   "at least half of the first 10, at least two different pictures" rule. Episodes without art show the series
   cover in the carousel. An entry where no episode has its own picture looks exactly as today: one cover, no
   carousel (user, earlier the same day).

## Design QA (2026-10-12)

Reviewer: design QA, against `main` `6b4ecf65`, palchrb/vibb branch `pipewire` (`pi/vibb/content.py`, `pi/ui.py`), and
the in-flight `music-cover-instant` work (worktree `wt-mfix`, uncommitted, read only). The user decisions above are fixed
and were not re-opened. Nothing was built.

**Verified, no change needed:**
- The vibb claims in §0: `_pick_image(want=300)`, `_parse_feed` (item `itunes:image`, else channel image; no
  `media:thumbnail`), `_download_image` (300 px, q85), `_sync_episode_images` (kept episodes only),
  `render_now` (keeps the same target's previous picture, then the collection cover), and no Now Playing carousel.
- Compatibility: an older app's `safeUrl` turns a 64-hex hash into null, and the new `[0-9a-f]{64}` filter turns a
  URL into null. A oneway AIDL call that an older launcher doesn't know returns false in `onTransact`, and the caller
  sees no error.
- Sleep timer: `goTo` only seeks, so a swipe doesn't cancel the timer. A tap on the centre is a
  `USER_REQUEST` pause, so it calls `setSleep(0)` as the button does.
- Headset buttons, auto-advance and list picks all change `exo.currentMediaItemIndex`, so "the pager follows the
  player" covers them. `restoreThen` plus a key-based skip keeps a restored queue paused.
- The cover route's scoping as written (a ticked entry, a listed item) is sound. See L1 for the exact SQL.
- The numbers in the §3.3 table are right for the sizes listed (see L2 for the cases they miss).

### High

**H1. After the app update the stored listings are never re-applied, so phones that already exist get no episode art.**
- **Scenario:**
  - The server ships first, and its listings carry hashes. The old app applies them and drops `art`.
  - The new app's Room migration adds `ItemRow.artHash` as NULL. `listings.version` still equals the launcher's, so
    `listingsWanted` (`BridgeConfig.kt:88`) fetches nothing.
  - Every entry whose listing doesn't change again keeps `artHash = null` for good. That covers a finished NRK serie,
    a podcast on a break, and an entry whose backfill ended before the update. Those entries never get a carousel.
- **Fix:**
  - Use an `AutoMigrationSpec` whose `onPostMigrate` runs `UPDATE listings SET version = ''`. The next sync then pulls
    and re-applies every listing from the launcher's copy, with no server traffic.
  - Don't delete the rows. Prune runs only on parsed listings, and an empty version is never served.
- **Test:** a migration test (Room `MigrationTestHelper` from `schemas/1.json`), and `listingsWanted` with
  `applied = ""`.

**H2. The live Home card, the lock's tile and the notification show the session's artwork, which is fixed when the
queue is built. §2.4's claim that they update "within 15 s" is wrong for a live session.**
- **Scenario:**
  - `MusicSessions.reading.art` comes from the platform metadata bitmap, and `MusicHomeCard`/`MusicLockPanel` use
    it while live. The `setNowPlaying` ART bytes reach only the snapshot card (`snapshotArt`), so the 15 s report
    changes nothing on a live card.
  - The `now` neighbours' pictures land about 20 s after a queue start. When the kid swipes or auto-advances to them,
    their `MediaItem` still carries the series cover. So the lock tile and the notification show the series cover for
    nearly every episode that isn't kept.
  - Also, `mediaItem()` runs on the main thread (`start`, `toggleShuffle`). The "lazy `artFor(hash)` at build time"
    would decode 512 px JPEGs there, one per queue item.
- **Fix:**
  - On `onMediaItemTransition`, and when a wanted hash for the current item lands, make its ≤ 300 px copy on `writer`.
  - Then call `exo.replaceMediaItem(index, item.buildUpon().setMediaMetadata(meta with the new artworkUri))`.
    Media3 1.7.1 swaps metadata without interrupting playback when `localConfiguration` is unchanged (progressive and
    HLS sources support `canUpdateMediaItem`).
  - At build time, use only the ≤ 300 px copies that already exist, else the entry cover's copy. Never decode on main.
- **Test:** a pure `artOrCover` test, plus a device check: swipe to an unkept episode, wait for its picture, and check
  that the lock tile and the notification change without a queue restart.

**H3. The in-flight `music-cover-instant` change overlaps the player part, and §4 doesn't mention it.**
- **Scenario:**
  - That branch adds `logic/Switch.kt` (`PlayRequest`, `requestStands`, 3 s hold, `skipSwitch`), `NowView.cover` /
    `itemTitle` / `itemKnown`, `PrefetchCovers` and `loadCover` in `Widgets.kt`, and it changes `PlayerScreen`'s cover
    box.
  - §3.2's own "pending skip, at most 1.5 s" would be a second mechanism for the same thing: show the target before
    the service publishes it. Both edit the same lines in `PlayerScreen`, `UiModel` and `Widgets`.
- **Fix:**
  - Implement design 27 after that branch merges, and rebase onto it.
  - A swipe is a `PlayRequest` for the item key, handled like `skipSwitch` with the explicit target. "Pending" becomes
    `requestStands`, which also ends as soon as the player moves elsewhere. That fixes the unplayable-target case,
    where the index never equals the swiped target and the 1.5 s rule would snap back late.
  - `NowView.cover` becomes `artOrCover` with the fallback.
  - Per user decision 1, the seek bar follows the picture: show the landing item's saved place (`storedPosMs` and the
    item's duration) while the request stands.
  - `PrefetchCovers` is redundant with the pager's pre-composed pages; keep it only for the single-cover look.
- **Spec:** add both branches to §4's overlap list.

**H4. Under the literal "at least one episode has its own picture" rule, an album shows a carousel of identical
pictures.**
- **Scenario:**
  - An own-files album where every track embeds the same front cover. The upload pipeline gives identical bytes, so
    every track has the same `art` hash.
  - An own entry's `cover` is the parent's cover or null (`music.rs:805`), so `artHash != entry.cover` holds for
    every track.
  - The carousel shows, and every page is the same picture. The superseded rule ("at least 2 different pictures")
    prevented exactly this, and §3.1's own example ("an album whose tracks all embed the same picture doesn't") now
    contradicts the decision.
  - The same happens for an RSS feed whose items all point at one non-channel picture. See M2.
- **Fix (consistent with the decision):**
  - The carousel shows when the queue has more than one item and its pages show at least two different pictures,
    counting the fallback cover as one.
  - For a podcast, one own episode picture plus the series cover is two pictures, so the carousel shows: exactly the
    user's rule.
  - For an album with one shared picture there is one, so the player looks as today, and the single cover is the
    track's art, as vibb's "every song wears its own cover".
  - Evaluate over `NowView.queue` (visible items), not the raw listing.
- **User question Q1 below.**

### Medium

**M1. The body of the spec and the mockup still carry the superseded rule and the open questions.**
- **Where:**
  - §3.1 ("at least half of the first 10 ... 2 different pictures");
  - §0 ("open question 1"), §3.2 ("recommended; open question 1"), §3.1 ("Open question 2");
  - the §5 tests ("half; a stray picture");
  - the mockup's `hasEpisodeArt` and its comment (`Main.dc.html` ~391);
  - the header's "v16", where the mockup is v18 (README).
- **Scenario:** an implementer follows §3.1 and the §5 tests and ships the rule the user rejected.
- **Fix:**
  - Rewrite §3.1 to the decision (with H4's counting), and drop the §3.2 preview fallback paragraph.
  - Update the §5 tests: one own picture among 300 shows the carousel; none doesn't; an identical-picture album
    doesn't.
  - Update the mockup's `hasEpisodeArt`. Its `stepWrap` also resets the position to 0, while the decision says
    "at its saved place".

**M2. The single `cover_print` reference turns every misjudged picture into an entry-wide carousel.**
- **Background:** under "at least one", a single `own` that is really the series picture gives the whole entry a
  carousel.
- **Cases the spec misses:**
  - (a) **No `cover_print`.** An RSS feed without a channel image, or a cover fetch that failed: every item with art
    is `own`, including N copies of one picture under fresh URLs.
  - (b) **NRK's two series pictures.** A stub without `squareImage` falls back to the 16:9 `image`. Centre-cropped,
    that is the series banner, and its dHash differs from the square cover, so it is judged `own`.
  - (c) **A rebrand.**
    - A new series cover makes step 6 re-judge. The back catalogue's episode pictures, which are the old series art,
      now differ and flip to `own`. The carousel appears with the old logo on every old episode.
    - Worse, step 6 says "no requests", but a `same` item stored no file, so becoming `own` needs a GET.
- **Fix:**
  - Keep a small set of reference prints per entry instead of one:
    - the current cover's;
    - the series' other picture (its 16:9 `image`, centre-cropped, fetched once with the cover, one GET);
    - up to 3 previous covers' prints.
  - Within distance 5 of any reference print means `same`.
  - Step 6:
    - an `own` item that now matches → `same` (drop `art_hash`; the prune removes the file);
    - a `same` item that no longer matches → untried (`art_from = NULL`), so the budget fetches it.
  - Case (a) with no reference print: H4's "at least two different pictures" already stops N copies of one picture.
    Additionally, deduplicate by print within an entry: an `own` result within distance 5 of an `own` print already
    stored for the entry reuses that `art_hash`. The phone then sees one hash.
- **Tests:**
  - the 16:9 fallback judged `same`;
  - a rebrand keeps old-art items `same`;
  - the print deduplication;
  - a feed without a channel image with 10 identical item pictures: one hash, no carousel.

**M3. The NRK back catalogue stays on the 300 px pick for good.**
- **Scenario:**
  - The routine `podcast_list` walk stops at the first known key (`walk`, `music_sweep.rs` ~1265), so a known stub is
    never re-read. "Until NRK sends the stub again" never happens for existing episodes.
  - Their `art_url` is the 300 px variant. The variants differ in the middle of the gfx path (fixture
    `psapi_podcast_episodes_desc.json`), so no URL rewrite is possible.
  - `process` never enlarges, so every existing episode picture is stored at 300 px. That is soft at 224 dp on a
    2.6× screen, and step 1's URL match never fires for those items.
- **Fix:**
  - Add a one-time art refresh, flagged by the migration (`music_listings.art_refresh = 1` for NRK entries).
  - The next check walks the episode pages in full (≤ 2-3 psapi pages for 100 items, inside the 250 budget) and only
    updates `art_url` of known keys. It doesn't add or drop items.
  - Then clear the flag. The cost is at most 3 requests per NRK entry, once.
- **Test:** the refresh updates `art_url` and resets `art_from`. The routine walk is unchanged.

**M4. The launcher also pins the listing snapshot.**
- **Scenario:** `MusicPlanTest.kt:90` asserts `listingVersionOf(server/testdata/music_listing.json) ==
  "33e4fb66e77b8d7f"`. Regenerating the snapshot in step 1 changes that version, so `launcher-build` fails. That job
  runs on that file (`launcher.yml` path filter).
- **Fix:** step 1's single commit also updates `MusicPlanTest`'s expected version. Say so in §1.5, §5 step 1 and §4's
  file list.

**M5. A picture that lands while its slot is on screen never shows.**
- **Scenario:**
  - `coverBitmap`'s `produceState` is keyed only on the hash.
  - When the file is missing, it settles on null and never runs again.
  - `Store.snapshots()` doesn't change when a cover file arrives (`Inbox.putCover` writes a file, not a row).
  - So the centre picture, the bar and the notification stay on the series cover until the slot leaves the
    composition, even long after the copy landed. §6's "arrives within a sync" would fail on the device.
- **Fix:**
  - Add a `Store.coversVersion: StateFlow<Long>`, bumped by `putCover` and by the prune.
  - `coverBitmap` keys its producer on `(hash, coversVersion)` while its state for that hash is null.
  - The same signal lets `PlaybackService` refresh the current item's artwork (H2).
- **Test:** pure, in `CoversTest`: a missing file, then present, decodes once.

**M6. The launcher can re-request failing art on every sync and every item change.**
- **Scenario:**
  - `wantArt` is sent at the end of every music sync and on item changes, and each call runs the courier. Every
    wanted hash that 404s is fetched again, up to 600 GETs a run. A hash 404s when:
    - its item was capped out or unticked;
    - the server forgot it after a restore (`recover_missing`), before the listing change reaches the phone;
    - it fails the hash check.
  - On mobile data without the switch, every call also starts a courier run that can't fetch the `later` list.
- **Fix:**
  - `MusicStore` keeps a per-hash failure time, skipped for 24 h after 2 failures (as `OWN_FILE_HASH_BACKOFF_MS`).
  - At most 50 art GETs per run, `now` first.
  - `MusicBridgeService.wantArt` calls `MusicCourier.request` only when a hash is fetchable now: not backed off, and
    `later` only on an allowed network.
  - Pure `artToFetch(now, later, have, failures, unmetered, mobileData, max)` in `MusicPlan`, with tests.

**M7. Gaps in the pager and service contract.**
- **Gaps:**
  - (a) "The pager follows the player" must never animate while the pager is dragged or flinging (`isScrollInProgress`
    or a drag interaction). Otherwise the result of the first of two quick swipes yanks the page from under the
    finger.
  - (b) A "new queue re-keys the pager" must mean that the queue's key list changed. That includes a listing apply
    that drops a visible item mid-play: `NowView.queue` is `mapNotNull`, so `n` changes and the virtual page math
    breaks.
  - (c) `SKIP_TO` must carry `ARG_ENTRY` and `ARG_KEY` and be ignored unless the entry is the active (or restored)
    one. A stale pager can swipe after a Nytt tap or the Home card switched entries.
  - (d) Add `SKIP_TO` to `Commands.ALL` (`onConnect` accepts only those), and decide whether it is in `PLAYS`. It
    isn't: like next, it doesn't start playback.
  - (e) Sonos (1b-3) must route it like next.
- **Tests:** add these to the pure pager rules in §5 step 3.

### Low

**L1. Server sweep details.**
- (a) **The day count.** `ART_PER_DAY` "counted from `art_at`" also counts steps 1, 2 and 6, which stamp an attempt
  without a request. Count only GETs: a separate `art_fetched_at`, or count rows where a fetch happened.
- (b) **Budget skips.** A GET skipped for the check's budget or its 600 s (`Got::Skipped`) leaves the item untried:
  don't set `art_from`.
- (c) **Step 2 from a `same` item.** That item stored no file, so when the other entry judges it `own` it needs the
  GET. Only copy `art_hash` when it is set.
- (d) **An index.** Add one on `music_items(art_from)` (or `art_url`) for step 2's lookup across entries.
- (e) **`art_backlog = 0`** stops older episodes' own art for good. With "at least one" that can hide a carousel the
  entry would have. Accept and say so, or retry the backlog once a month.
- (f) **`art_in_library` SQL.** Join `device_music_entries`, require `e.source IN ('nrk','rss')`,
  `i.state != 'pending'` and `music_listings.version IS NOT NULL`, then serve from `music_art_dir`. Check
  `cover_in_library` first, as written.

**L2. Layout.**
- (a) **The neighbours' pull.** It must be `A·(1−0.733)/2` (20.6 dp at A = 154, 29.9 at 224), not the library's
  fixed 24 dp.
- (b) **"Peek ≤ 0.733·A" doesn't always hold.** It fails when `A < (W−16)/2.466`: 110 dp at 288 and 147 dp at 379.
  That happens with a large font or display size, where the cover box shrinks: the neighbour then shows whole with an
  empty strip beside it.
  - FitTest should include a short box (e.g. 120 dp).
  - Below `A < 96 dp` fall back to the single cover.
  - Draw pages with |offset| > 1 at alpha 0.
- (c) The control row and the bottom bar don't change, so the 288 dp rule and `requiredSize` hold.

**L3. Memory and caches.**
- `bitmaps` is an `LruCache` of 24 entries counted as items. Each 512 px ARGB bitmap is 1 MiB, so up to 24 MiB, with
  5 pager pages composed. That is fine on the Jelly Star, but:
  - decode at the size shown (`inSampleSize` to ≥ A × density; 154 dp × 1.5 = 231 px decodes at 256 px) or as
    `RGB_565`;
  - size the cache in bytes.
- `cacheDir/art/` (the ≤ 300 px copies) is never pruned and now grows with every episode picture. Prune it with the
  same rule as `covers/`.

**L4. The want set, end to end.**
- The player's `LiveBridge.wantArt` must send both lists from `wantedArt` on the current snapshot. A `now`-only call
  would let the launcher delete `later` pictures still in flight.
- The music app's prune runs after `applyListing`, and only on a parsed library.
- `MusicStore.clearAll` and the `head == null` branch also clear `want_art.json`.
- Keep the last ~20 pictures shown, in a small recent set in the want universe, so that swiping back and forth
  doesn't fetch the same neighbours again after every sync.
- `now` on any network also covers roaming. That's fine at ~60 KB a picture, but say it.

**L5. Strings and TalkBack.**
- "Pause: %s" and "Spill: %s" are new strings, in nb and en; `play_entry` is "Spill %s" without the colon.
- Test that TalkBack's scroll forward and backward land on one page and skip once.
- Include an animations-off device check with TalkBack on.

### Questions for the user

- **Q1 (H4):** some music albums have the same cover embedded in every song. Should they show the carousel, with the
  same picture on every page?
  - **Recommendation: no.** The carousel shows when at least two different pictures appear in it, and the series
    cover counts as one.
  - A podcast where just one episode has its own picture still gets the carousel, as decided.
  - An album with one shared cover looks as today.

No other real user questions: M2, M3 and M6 are design fixes with obvious defaults.

## Decisions after the design QA (orchestrator, 2026-10-12)

- **All 16 findings are accepted** as the QA proposes. H3 is resolved: `music-cover-instant` is merged (5d37cbf1), so a
  swipe is a `PlayRequest` through its `requestStands` rule, with no separate pending-skip.
- **Q1, decided from the user's own rule** ("no per-episode art looks as today"): the carousel shows when its pages show
  **at least two different pictures**, with the series cover counting as one. An album whose songs all carry the same
  cover looks as today. A podcast where a single episode has its own picture still gets the carousel, as decided.

## Implementation status (2026-10-12)

Built on branch `music-episode-art-27`, rebased onto `main` `e3b61813`. Not pushed, not merged. Nothing was run on a
device or the emulator.

**Commits:**
- `3ac53f38` Step 1 (server and the listing contract). Migration `0070_music_episode_art.sql`. The listing
  snapshot, the music app's copy, `Listing.kt`'s 64-hex filter, `ContractFilesTest` and the launcher's
  `MusicPlanTest` version pin (`a8146790d70b89a3`, QA M4) are in this one commit.
- `9c0d18ae` Server docs: `server/CLAUDE.md` ("Vibb music: episode art") and `server/DEPLOY.md` (`data/music_art` isn't
  backed up, the Pi checks).
- `9608c40c` The flaky `flags_are_kept_until_their_item_is_asked`: it flagged after a fixed 30 ms while a check ran,
  so under load the flag landed before the check read its rows, or after it committed. It now waits for the check's
  first request (`CannedFetch::in_flight()`).
- `26d03e1f` Step 2 (the courier): `items.artHash` (Room 2, the `ReapplyListings` auto migration, QA H1), the want set
  (`logic/EpisodeArt.kt`, `sync/ArtPlan.kt`), `wantArt` appended to the AIDL in both apps, `bridge_contract.json`'s
  `want_art` caps, the launcher's `syncArt`/`artToFetch`/`artToDelete` and failure backoff (QA M6), the music app's
  prune of covers and small copies (QA L3, L4).
- `c117f3bc` The launcher nudges the music app once when a new want set names pictures it already holds.
- `40713096` Step 3 (the player): `PlayerArt`/`EpisodeCarousel`, `SKIP_TO` and `THEN_SKIP_TO`, `Cover`'s fallback,
  `Store.coversVersion` (QA M5), the lazy art map and `replaceMediaItem` (QA H2), the mockup's rule and saved place,
  and this spec's body (QA M1).
- `cd32ab3f` The server's art pass skips items without a source or picture URL (a Storytel part is `ok` with
  `url` NULL), and the covers version moves atomically.

**Deviations and choices:**
- QA H1's migration test: the music app has no Room testing or Robolectric, and no dependency was added. The test runs
  `ReapplyListings.onPostMigrate` against a recording `SupportSQLiteDatabase` proxy and checks the exported
  `schemas/.../2.json` and `listingsWanted` with an empty version. KSP validates the auto migration at build time.
- The want set: library covers are left out (they come their own way). The prune's universe is capped with the lists
  (600) plus the 20 recent pictures. The recent set is the current item and its neighbours, noted on each item change
  (`recent_art.txt`).
- The launcher: a network error doesn't count toward a picture's backoff, only an HTTP error or a wrong hash does.
  No network: nothing is fetchable, so `wantArt` starts no courier run. `now` goes on any network, roaming included
  (about 60 KB a picture, QA L4).
- The pager's "pending" hold is the `PlayRequest` (QA H3). For quick swipes, `PlayRequest.via` lets the player pass
  through an earlier swipe's target without ending the later request. The pager also records the index shown when it
  skipped, so it isn't pulled back before the request shows.
- Decoding (QA L3): covers decode at the size the player's 224 dp cover needs (`inSampleSize`), and the bitmap cache
  counts bytes (16 MiB).
- `PlayerScreen` takes `numberOf(item)` in place of `number`, so "Episode N av M" follows the landing page.
- Server-side choices are in the step 1 report: `art_fetched_at`, the reference-print columns, and the monthly backlog
  retry (QA L1 e). The server version stays 0.24.0.

**Tests and checks:**
- Server: `cargo test` 611 passed. `cargo fmt --check` is clean. `cargo clippy --all-targets` gives 12 warnings, none
  in the new code.
- Launcher: `testDebugUnitTest assembleDebug assembleRelease -PwarningsAsErrors=true` passed (1010 tests), release
  built with the stub `tsnet.aar`.
- Music app: `testDebugUnitTest assembleDebug assembleRelease lint -PwarningsAsErrors=true` passed (185 tests). Lint
  has nothing new.
- The PWA was checked headless at 360 px in light and dark: the count line is cut off with "…", and nothing scrolls
  sideways.

**Device checks still to do:** all of §6, plus QA H2's lock-tile check and L5's TalkBack and animations-off checks. On
the Pi, the checks are in `server/DEPLOY.md`. Sonos (phase 1b-3) must route `SKIP_TO` as it routes next.
