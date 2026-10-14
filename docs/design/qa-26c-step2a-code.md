# QA 26c step 2a code review - the Spotify listing, the modes and the sweep check (2026-10-13)

Scope: branch `music-3-spotify`, `ca2b454..0fc42a1` (one commit) under `server/`: `src/spotify.rs`, `src/music_sweep.rs`,
`src/music.rs`, `src/music_net.rs`, `src/tests/music_spotify.rs`, `src/tests/music.rs`, `src/tests/music_sweep.rs`,
`testdata/spotify/` (ten new Web API fixtures), and the step 2a status in design 26c (`handlers/music_api.rs` is not
touched). Read against design 26c (§1.1, §3.1, §3.3-§3.6, §4.1, §9.3, §10, §11 step 2a, the design QA fix round, the
step 1 status and its fix round, the step 2a status with its eleven numbered deviations and two spec errors), root and
`server/CLAUDE.md`, design 27 §1 (the art pass) and the patterns it extends (Storytel's check, `finish` keeping the
last good list, `restamp_versions`, `device_listing`, `flag_reports`, `without_url`, the request budget). Spotify's
Web API reference read here on 2026-10-13 (Get Playlist Items, Get Artist's Albums, Get Album, Get Album Tracks, Get
Show Episodes, Get Track, Get Playlist, the February 2026 dev-mode migration guide). Line numbers are `0fc42a1`'s.

Build, here (Rust 1.99, `server/`): `cargo fmt --check` clean; `cargo clippy --all-targets` 12 warnings, all in
`models.rs` (9), `dns_engine.rs` (2), `handlers/devices.rs` (1) - none new; `cargo test` twice: 714 passed, 1 failed
both times - the pre-existing, time-dependent `tests::parent_pin::a_new_pin_ends_the_notices_but_not_the_list`, as
in step 1's QA. Every Spotify test green both runs. `music_library_snapshot`, `music_listing_snapshot` and
`policy_json_keys_snapshot` pass unchanged; `Cargo.toml`/`Cargo.lock`, `migrations/` and the shared `testdata/music_*.json`
untouched. Probe (a throwaway test, reverted): Medium 1's failure path, run over `CannedFetch`.

**Verdict: good to merge after a short fix round.** Counts: 0 High, 2 Medium, 4 Low. The core is right: the parsers
read the documented shapes (`items[].item` with the old `track` as fallback, `is_local`, `is_playable`, `item: null`,
the paging object's `next`), every Web API URL is built from the canonical id and never from an answer's `next`,
the snapshot gate, the modes and their transitions, the reader order and memory, the quota pause, the budget with
the token refreshes counted, the short-link resolver and `follow: false`, the gate on the listing route, the restamp
and the per-pass art memo all do what §3 says, and nothing in a log, an error text or the served listing carries a
token, a URL or an account id. The two Mediums are a transient failure that throws a listed playlist into context
mode, and the artist option asking for a page size Spotify documents as invalid.

## Medium

1. **Medium: a 403 from one reader followed by any other failure from the next one puts a listed playlist into
   context mode and reports success.**
   - Where: `src/music_sweep.rs:2612-2631` (`SpotifyReads::first`: `refused` stays `true` from an earlier reader when a
     later one fails for another reason, `:2624` returns it with that flag), `:2776-2781` (the playlist's items:
     `Err(none) if none.refused` -> `spotify_context`), `:2947` (the artist option: the same pattern -> context).
   - The status and §3.4 promise "every failure keeps the last good list"; `first` only means "every reader may not
     see it" when every reader answered 403/404, but its `refused` flag says "at least one did".
   - Probe (over `CannedFetch`, reverted): a playlist listed in list mode with the kid as reader, then a Check now in
     which the kid's items read answers 403 and the parent's 429 `QUOTA_EXCEEDED`. Result: `error=None`,
     `spotify_mode = 'context'`, 0 items, the library revision moved - and the quota pause is set, so every Spotify
     check for the next hour fails `quota` and the playlist stays a context for at least that hour. The same with a
     503, a timeout, `rate_limited` or `bad_answer` on the second reader.
   - Failure path: the remembered reader loses access (the kid removed as a collaborator, the account's login moved
     to another user) or a first check where the kid's account is asked first, and the next reader's read hits any
     transient. The item rows are deleted (`finish` writes the empty list), so their first sightings and the art
     pass's results go (`MUSIC_ART.prune`), and when the list comes back every track is new again (`new_items`, the
     phones' NY/Nytt) and its pictures are fetched again over days.
   - Required change: in `first`, a failure that doesn't let the next reader try ends the read with `refused: false`
     (an unknown answer is not a refusal), so the caller fails the check with that error and `finish` keeps the list,
     the mode and the reader. Test: the probe above as a test (listed playlist, kid 403 then parent quota / 503 ->
     the check fails `quota` / `http_503`, mode, items and version unchanged, the revision not moved); and the artist
     option's 403-then-503 fails rather than falls back.

2. **Medium: the artist option asks `GET /artists/{id}/albums` with `limit=50`; Spotify's reference says at most 10.**
   - Where: `src/spotify.rs:1837-1845` (`Read::ArtistAlbums` takes `PAGE_LIMIT` = 50), `src/music_sweep.rs:2954`
     (the next page's offset steps by 50), `testdata/spotify/artist_albums.json` (`"limit": 50`).
   - Evidence: the Get Artist's Albums reference, read here on 2026-10-13: "limit integer The maximum number of items to
     return. Default: 5. Minimum: 1. Maximum: 10." (Get Playlist Items, Get Album Tracks and Get Show Episodes say
     "Maximum: 50", so the other reads are right; the migration guide names only Search's drop to 10.) The design's
     §3.1 table said `limit=50` - a spec error, fixed in the design text with this review.
   - Failure path: with `SPOTIFY_ARTIST_ALBUMS=1` every artist check gets Spotify's 400 for an invalid limit, which
     isn't a refusal, so the check fails `http_400` instead of listing or falling back. QA checklist item 18 - the
     user's test of whether the option works in dev mode - then answers "no" for the wrong reason. Should Spotify clamp
     instead of refusing, the 50-wide offsets skip albums 10-49, 60-99, ...
   - Required change: a page size of 10 for this read (its own constant; offsets by 10) and `MAX_ARTIST_ALBUM_PAGES`
     re-judged (10 pages are now 100 albums - enough; or, cheaper, read `include_groups=album` and `=single` separately,
     each newest first, two pages each, and merge the newest 20). The fixture's `limit` 10; a test that pages (a first
     page with `next`, the second asked at `offset=10`) and pins `capped` when pages are left.

## Low

1. **Low: an episode in a playlist is told apart only by `type`; the shape Spotify sends for it with
   `additional_types=track` is undocumented.**
   - Where: `src/spotify.rs:2032-2036` (`track` keeps any object whose `type` is `track`), `:2121-2133`;
     `testdata/spotify/playlist_items.json` (the episode as an `EpisodeObject`, `type: "episode"`).
   - The reference: `additional_types` = "item types that your client supports besides the default track type ...
     make sure that your client properly handles cases of new types in the future by checking against the type field".
     What an episode looks like when the client does *not* ask for episodes isn't documented; older answers converted it
     to a track-shaped object (with `"episode": true, "track": false`) [unverified]. If that shape says `type: "track"`,
     the episode is listed and the phone plays `spotify:track:<episode id>` - an `unplayable` the parent can't explain.
   - Fix: also leave out (and count) an item with `"episode": true`, or whose `uri` is present and doesn't start with
     `spotify:track:`; or ask `additional_types=track,episode`, so an episode always comes as the documented
     `EpisodeObject` and the `type` check is enough (§3.1's URL changes with it). A parser row for the track-shaped
     episode.

2. **Low: §10's 2a rows have four holes the tests don't pin.**
   - `no_items` on a first fill (a track entry whose track is `is_playable: false`, a playlist of only local files):
     `finish`'s new `!context` arm is pinned only from the context side; the list side and the card's sentence
     ("Couldn't list it: nothing in it can be played here") are not.
   - The metadata read moving to the next reader (`first` for the metadata: the kid 403, the parent 200 ->
     `meta_reader` = the parent); only the items read's turn is tested.
   - A failure on a later page (page 2 of the items answers 503): the list, mode and reader kept.
   - A track repeated across pages (only within one page is tested).
   - Fix: one test each (or rows in `failures_keep_the_last_good_list` and `a_403_playlist_goes_to_context_mode_and_back`).

3. **Low: two error codes reach the card as raw words.**
   - Where: `src/music.rs:1721-1747` (`sweep_error_text("spotify", ..)`), `src/spotify.rs:802` and `:807`.
   - `SpotifyError::Database` (a token mint that couldn't read its row) is `database`, and `FetchError::Refused`
     (a Web API host that resolved to a private address, `Reach::Public`) is `refused`; neither has a Spotify text, so
     `entry_error_text`'s fallback shows "Couldn't list it: database" / "refused". Rare, but the other Spotify codes all
     have sentences.
   - Fix: `("spotify", "database")` -> "this server couldn't read its Spotify login - it tries again later",
     `("spotify", "refused")` -> "Spotify's address pointed somewhere this server won't go"; rows in
     `spotify_entries_have_their_own_link_and_kind`.

4. **Low: `spotify_gate!()`'s SQL is one 200-character line with runs of ten spaces.**
   - Where: `src/music.rs:871-875`. A `\` line continuation was evidently joined: the SQL is right (whitespace), but it
     is the one literal in the file written so, and 2b pastes it into three more queries.
   - Fix: write it as `storytel_gate!()` is (`\` continuations, one clause per line).

## Confirmed

- **Parsers vs the reference**: Get Playlist Items' answer is a paging object whose `items[]` hold `added_at`,
  `added_by`, `is_local` and `item` ("oneOf TrackObject / EpisodeObject"; the response sample still carries the old
  `track` too - deviation 7 is harmless); `is_playable` is "part of the response when Track Relinking is applied"
  (i.e. with `market`), so `market=from_token` on every item read is what makes it appear; `linked_from` and
  `available_markets` are gone in dev mode and not read; local files have `id: null` and `is_local: true` (both
  checked, at the entry and the item); `item: null` is counted; `next` is only read as present/absent and the next
  URL is built from the offset (an answer can't steer a request). Album tracks (embedded first page + `/tracks` from
  offset 50), show episodes (newest first, `null` entries counted), `GET /tracks/{id}` and `GET /episodes/{id}` (its own
  picture, else the show's) match their references; page sizes 50 are within Playlist Items', Album Tracks' and Show
  Episodes' "Maximum: 50". The image pick (of at least 300 px the narrowest, else the widest; widthless mosaics count
  as 0; `media_url_ok`) and the titles (`"Name — A, B"`, an album's first artist, cut by `cut_title`) are §3.2/§3.4's.
- **Totals vs caps**: `total` is read but never trusted; the caps count kept, deduplicated items (1000 for a playlist,
  `capped` past it, the 21st page never asked; a show's newest 100, kept oldest first with `keep_end = 'newest'`); a
  page of nothing but left-out entries can't loop (`most_pages` = twice what `max` needs).
- **The readers**: `needs_login` rows out, the remembered reader first, a removed reader's id (no FK) ignored,
  `no_account`/`reauthorize` before any request; `try_next_reader` passes 403/404 and the account-level failures
  (`401` after the refresh, `reauthorize`, `key`, a removed account) and stops on everything that is the same for every
  account (the app refused, quota, rate limit, network, 5xx, bad answer) - right apart from Medium 1's flag.
- **The modes**: list -> context when every reader answers 403/404 on the items (deviation 4's 404 included), context
  -> list as soon as one may list (tested both ways, the revision moving through 0073's trigger); a context listing has
  no items, keeps the reader and the playlist's snapshot, `spotify_items_at` NULL, so the gate never skips a context
  playlist. An artist without the option is one request a day.
- **The snapshot gate**: list mode + the same `snapshot_id` + items read under 24 h ago + not Check now -> one request
  (tested: 1 request, version and `spotify_items_at` unchanged, `listed_at` moved); albums, tracks, episodes and
  artists daily by `interval(link, ..)`, playlists and shows at the setting, unticked daily, NRK unchanged.
- **Quota, rate limit, budget**: the pause checked before any request at the check's start and in every `api_get`; a
  `QUOTA_EXCEEDED` ends the pass's Spotify checks with no further request while RSS goes on (tested); a long
  `Retry-After` is `rate_limited` and backs off; `api_get_counted` counts every request including the token refreshes
  and the 401's second refresh (tested: 4 for refresh + 401 + refresh + read), and the budget is checked before each
  read (past it `timeout`).
- **Short links**: one hop at a time, `follow: false` through new clients with `Policy::none()` - the public one keeps
  `PublicResolver`, so a hop can't reach a private address even by DNS; at most 3 requests; each `Location` (cut at
  2001 characters, refused past 2000) resolved against the hop, https only, no userinfo, no port, only `spotify.link`,
  `spotify.app.link` (asked next) or `open.spotify.com` (parsed, never asked); the first URL comes from `parse_link`,
  which rebuilds it as `https://<host>/<code>`, so no pasted userinfo reaches a request. No token or cookie is on a hop
  (a plain `SourceRequest`). `music_net`'s client test pins the 3xx and the public rule.
- **The listing route's gate (deviation 1)**: `device_listing` takes `'spotify'` with `spotify_gate!()` (the phone
  bound to an existing account); unticked or unbound -> 404 (tested), bound -> the list or context's empty list.
  `cover_in_library` and `art_in_library` still list only `nrk`/`rss`/`own`/`storytel`, so no Spotify cover or art is
  served in 2a at all - the gate is complete for this step. `needs_login` doesn't drop it (§3.6).
- **Versions**: context mode's version from mode + title + cover URL, both written by the same `finish` that computes
  it (title kept on failure, `cover_url` only moved by a stored cover), so the route and `restamp_versions` rebuild it
  identically; a restore (pictures forgotten, URLs kept) restamps a list and leaves a context version as it was
  (tested). An emptied list (deviation 3) is listed and served empty with `build_listing`'s version rule; NRK, RSS and
  Storytel take the unchanged `build_listing` path (`spotify_mode` NULL).
- **`flag_reports`** skips Spotify (tested). `default_kind_for`/`default_category_for` (a show or episode to Podkast),
  `entry_link` canonical only, `parse_link`'s pointer for every Spotify form and not for look-alike hosts (tested).
- **Art**: `art_candidate` takes an `ok` Spotify item without a URL; `art_judged_alone` makes a playlist's, track's,
  album's (none listed) and the option's track pictures `own` with no references, a show's and an episode entry's
  judged as RSS (an episode entry's picture is its cover's URL: `same`, no request). The `tried` memo sits after steps
  1-2 and before the budgets, so a URL that failed or whose host was in trouble costs one request per pass; image
  requests are counted in `music_art_requests` only when sent, so memo hits don't eat `ART_PER_DAY`. RSS, NRK and
  Storytel art tests (`tests::music_art`, `tests::music_sweep`, `tests::music_storytel`) green both runs - no regression
  found; the memo changes one behaviour for them (a second item with a URL that just failed is marked failed without
  its own try) which is the point.
- **Security**: no new log line but a debug with the entry id; `CheckError::Spotify` holds a code; `SpotifyError`'s
  codes carry no URL (`FetchError` texts are `without_url`'s); the served listing holds keys (Spotify ids), titles,
  durations and art hashes - no account, reader or token; fixtures use `FAKE-ACCESS-<n>`, `fake…` ids and paths. Every
  Web API read and hop goes through `AppState.music_fetch`, `Reach::Public` unless the loopback override is set.
- **Hygiene**: no dependency; no migration (0073's columns used as frozen); `cfg_attr(not(test), allow(dead_code))`
  only on what 2b uses (`SpotifyRef::key`/`open_url`, `LinkError::message`, `ShortLinkError`, `resolve_short_link`,
  `SHORT_LINK_HOPS`); snapshots unchanged.

## The deviations

(The status numbers eleven.)

1. **`spotify_gate!()` defined in 2a and used by `device_listing`**: accept - without it a ticked unbound phone could
   read a Spotify listing in 2a's commit. Complete for 2a (see Confirmed).
2. **Context version from the cover's URL, not its hash**: accept; the reasoning about `recover_missing` is right and
   the restore test pins it.
3. **An emptied Spotify list listed and served empty**: accept; NRK/RSS/Storytel keep their old rule; a first fill
   with nothing is still `no_items` (its test is Low 2's).
4. **Items 404 for every reader is context mode too**: accept; Medium 1 is its transient side.
5. **The per-pass memo in two parts (step 2 + `tried`)**: accept; for every source, no regression found.
6. **The artist option's details**: accept text-compared dates - `YYYY`, `YYYY-MM` and `YYYY-MM-DD` are ISO prefixes, so
   any two dates of different years or months order right, and within one month a day-precise date sorts before the
   bare month (no better answer exists); dedupe of albums and tracks; `capped`. The page size is Medium 2.
7. **`track` read where `item` is absent**: accept (the reference's sample still carries both).
8. **The generic form's pointer as a sentence**: accept.
9. **`Kind::Page` for Web API reads and hops**: accept (2 MB / 30 s is ample for 50 full track objects).
10. **The playlist's metadata without `market`; the show's episodes from offset 0; the album's first 50 embedded**:
    accept (one extra request per show check, harmless).
11. **`key` and `no_items` texts**: accept; added to §3.4 with this review.

## The spec errors claimed

- **§4.1's `uriOf`**: right - an episode entry's one item is an episode, so `spotify:track:` would be unplayable.
  §4.1 now reads "a show's and an episode entry's items are `spotify:episode:<key>`" (fixed here; §5.10's
  `mediaItem` row already says "`spotify:track:<key>` / `spotify:episode:<key>`" without the rule). Step 3's
  `SpotifyRulesTest` should pin `uriOf(episode, k)`.
- **§3.4's missing `key` and `no_items` texts**: right; both sentences added to §3.4.
- **Found here**: §3.1's table (and §3.3's "pages of 50 albums") had `limit=50` for the artist's albums - fixed to 10
  (Medium 2).

## What the fix round must do

- [ ] Medium 1: in `SpotifyReads::first`, a failure that doesn't let the next reader try returns `refused: false`;
      tests: a listed playlist with the first reader 403 and the next quota / 503 fails with that code and keeps the
      list, mode, version and revision; the artist option's 403-then-503 fails rather than falls back.
- [ ] Medium 2: the artist albums read at `limit=10` (own constant, offsets by 10; re-judge the page cap or read the
      two groups apart); the fixture's `limit`; a paging test with `capped`.
- [ ] Low 1: leave out a playlist item with `"episode": true` or a non-`spotify:track:` `uri` (or ask
      `additional_types=track,episode`); a parser row.
- [ ] Low 2: tests for `no_items` on a first fill, the metadata read's next reader, a failing later page, a repeat
      across pages.
- [ ] Low 3: Spotify texts for `database` and `refused`.
- [ ] Low 4: `spotify_gate!()` written with continuations.
- [ ] Re-run `cargo fmt --check`, `cargo clippy --all-targets` (12, none new), `cargo test`.

## Notes for 2b, step 3 and later (not findings)

- `is_playable` and `restrictions` are judged in the **reader's** market and settings (`market=from_token`), not the
  phone's account: a parent's account lists an explicit track that a kid's account with the explicit filter can't
  play (the phone's `unplayable`, §5.8), and a kid reader leaves it out. Same-country family accounts make the market
  part moot; the explicit filter part is worth a line in the PWA's setup text (2b) or the QA checklist.
- 2b's add check should reuse `SpotifyReads::first` after Medium 1's fix, so the add and the sweep agree on what a
  refusal is.
- A track entry's one item fetches the same picture as the entry cover and stores it again as `own` (§3.5 says so):
  one request per track entry, ever - fine as specified.

## Not worth fixing

- `parse_playlist_meta` accepts `{}` (no name, no snapshot): the items read then decides; the title stays the old one.
- `Page.total` is read and only the tests look at it.
- An artist-option album answering 403 fails the whole check (only 404 is passed over): a dev-mode 403 there would be
  the endpoint's, not the album's, and failing is the honest answer.
