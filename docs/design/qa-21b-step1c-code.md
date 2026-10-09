# QA 21b step 1c code review - 01527432, cb61212c (2026-10-09)

Scope: `git diff 121dcd69 cb61212c` (the sweep, server 0.22.0, and its docs) against design 21b: the body, the QA
review, the decisions, the user's answer to open question 1 and the step 1c status with its deviations. Paths below
are under `server/` unless they say otherwise. 0ff256dd (the "cheaper change checks" follow-up) landed after the
scope; notes for it are at the end.

Build: a worktree of cb61212c. `cargo test` 348 passed, `cargo fmt --check` clean, `cargo clippy --all-targets` 17
warnings, the same 17 as before (none in the new files). Findings 1-5 were reproduced with temporary probe tests on
canned answers (not committed): a flagged feed that then 404s (1), an empty psapi page on a listed podcast (2), an
entry whose every item expired (3), two flags an hour apart (4), and 20 MB feeds of nested tags and of tiny items
(5, `VmHWM` from `/proc/self/status`). PWA: a forked run of the implementer's local harness (`e2e21b`, a local feed
server, no real network) against this commit's binary at 360 px, light and dark. Not tested on the Pi or a phone.

## High

1. **High: a flagged RSS entry whose check fails is checked again every few seconds, for ever.**
   - Where:
     - `src/music_sweep.rs:450-459`: `schedule_one` makes a flagged entry due now (`Why::Rework`), whatever its
       `failures`; `recheck_only` (:452) is NRK-only, so an RSS entry always takes the full check.
     - `:1200-1204`: `run_check` clears the flags only in the in-memory plan.
     - `:1999-2003`: `finish` writes no item rows for a failed check of a listed entry, so the flags stay set.
     - `:2220-2224` and `:2164-2168`: the 20-checks-per-pass cap ends the pass, and `run` starts the next one 1 s later.
   - Scenario: a host retires a feed; the feed and its enclosures answer 404. A phone's download fails with
     `http_404` at the current version, so `flag_reports` flags the item and wakes the sweeper. Each check GETs the
     feed unconditionally, fails with `http_404`, keeps the flag, and `due()` returns the entry again at once.
     - Probe: one `run_pass` made 20 feed GETs, `failures` reached 21, and the entry was still due.
     - Cost: about one GET every 4 s, about 20,000 a day to that host plus a log line each, until the parent deletes
       the entry. A feed that is too big costs up to 20 MB per GET.
     - Every failure other than a network error loops this way: a 4xx, a refused redirect to a private address (§2.7's
       lapsed domain), `not_feed`, `no_items` and `too_big`.
     - Network errors, 5xx answers and timeouts trip the breaker instead: 3 GETs every 15 minutes in place of the
       backoff.
   - This breaks the load rule and QA design #5, which was accepted as "the flag is cleared after any attempt". No
     test drives `run_pass` with a flag and a failing source. The counting test (see "Checked and fine") calls
     `check_entry` directly, so it can't see this.
   - Suggested fix:
     - In `finish`, when a failed check had flagged items, still write `recheck = 0, rechecked_at = now` for those
       keys (one UPDATE); the other rows stay as the last good list.
     - In `schedule_one`, let a flag make an entry due only while `failures == 0`; after a failure the entry waits
       for its backoff like any other.
     - Add a `run_pass` test with a flagged RSS entry whose feed answers 404: one request, then nothing until the
       backoff.

## Medium

2. **Medium: one empty or 404 psapi page turns a listed podcast into the RSS fallback's few episodes, and the rest
   stay `gone` for good.**
   - Where:
     - `src/music_sweep.rs:1261-1264` and `:1281-1284`: in `walk`, a 404/410 or no valid stub on page 1 means
       "nothing".
     - `:1413-1431`: `podcast_list` takes the fallback at every check, not only at a first fill.
     - `:1862-1865`: `merge_feed` marks every item the fallback lacks `gone`.
     - `:1626-1643`: nothing re-resolves a `gone` item afterwards. Only expired `ok` items and flags are re-asked,
       and a `gone` item has `url: null`, so no phone can report it.
   - Scenario (probe, canned): psapi lists a podcast with 15 episodes. At one check, psapi's first page answers `{}`
     or 404, while podkast.nrk.no's RSS answers (the recorded fixture, 5 items).
     - The check succeeds: 10 episodes become `gone`, the version moves and the phones are nudged.
     - The phones hide the 10 they hadn't downloaded and prune the downloaded ones outside the keep set.
     - 12 h later psapi answers normally: the walk stops at the first known key (1 request) and the 10 stay `gone`.
     - With a real 100-episode podcast, 95 episodes are lost until the parent deletes and re-adds the entry.
   - §2.4's keep-last-good doesn't apply here, because nothing failed.
   - Suggested fix:
     - Use the fallback only for a first fill or for a list that is already in fallback. For a list psapi built, an
       empty or 404 first page is a failed check (`not_found`, keep the last good list).
     - A fallback merge doesn't mark missing items `gone`: the fallback is a short rolling window by nature.
     - Test: a listed podcast, one empty page, psapi back, and all 15 episodes still `ok`.

3. **Medium: when every item of a listed NRK entry is withdrawn, the check fails as `no_items` and throws its
   re-resolves away. The items never become `gone` and are asked about again at every backoff step.**
   - Where:
     - `src/music_sweep.rs:1990-1995`: no `ok` item and nothing untried means `NoItems`.
     - `:1999-2003`: a failed check of a listed entry writes no item rows.
     - `:1624-1662`: `resolve` re-asks every expired `ok` item whose in-memory `rechecked_at` is older than 24 h.
   - Scenario (probe): a julekalender-style entry of 24 episodes whose rights all end on one date.
     - The next check makes 1 page request and 24 manifest requests. All answer `nonPlayable`, so every item is
       `gone` in the plan and the check ends as `no_items`.
     - Nothing is written: `rechecked_at` stays NULL, and the items stay `ok` with dead URLs.
     - The backoff runs the check again at +15 min, +1 h, +4 h and then every interval, 25 requests each time (probe:
       5 checks of 25 requests, `rechecked_at` never written).
   - This breaks two hard rules:
     - one re-resolve per item a day, with a 300-a-day budget that never sees these requests;
     - rule 5: withdrawn items stay with `url: null`.
   - Meanwhile the phones keep showing episodes that won't play, and the card says "Last check failed: no
     episodes". Only a phone's report gets out of this: a flag makes the next check recheck-only, and that one
     writes.
   - Suggested fix: for an entry that has been listed before, a check that leaves every item `gone` is a successful
     listing. The version moves and the phones get `url: null`. Keep `no_items` for a first fill and for a feed
     without enclosures. This follows hard rule 5 over §2.4's "no `ok` item = `no_items`".

4. **Medium: the whole-entry re-resolve only fires when two flags are pending at the same moment (user decision 5).**
   - Where: `src/music_sweep.rs:1149-1166` counts the items with `recheck = 1` and a `reported_at` within 24 h.
     Each flag wakes the sweeper, and the check that follows within seconds clears it (`:1200-1204`).
   - Scenario (probe):
     - The kid streams r_1, gets a 403, and r_1 is re-resolved alone.
     - An hour later r_2 gets a 403 and is re-resolved alone.
     - `full_recheck_at` stays NULL.
   - Only two failures in the same status report trigger it, and that is the only case the test covers. Streams
     fail one at a time, so for streams "two or more distinct items in 24 h" never fires.
   - Suggested fix: count the distinct items with `reported_at` in the last 24 h (the column already holds it), not
     only those still flagged.

5. **Medium: a feed of deeply nested tags makes one parse take about 460 MB, which OOM-kills the whole server on the
   Pi Zero 2 W, and it does so again at every backoff step.**
   - Where: `src/music_sources.rs:385-387` and `:442-467`. `check_end_names` is off, the parser pushes one heap
     `String` per open tag onto its own stack, quick-xml keeps its own stack of names too, and there is no depth
     limit.
   - Scenario (probe): 20 MB of `<a>` after one valid item. `VmHWM` went from 28 MB to 488 MB (the heap use is the
     same in a release build) and the parse took 8.7 s.
     - On the Pi (512 MB) the kernel kills the server, and with it the phones' policy and status API.
     - The start stamp backs the entry off, and the kill comes again at +15 min, +1 h, +4 h and then every interval.
     - A lapsed or taken-over feed domain (§2.7's own scenario) can serve this. A real feed nests fewer than 10
       levels.
     - For comparison, a 20 MB feed of 357,000 tiny items peaked at about 150 MB, which is survivable.
   - Suggested fix: stop at a depth of 32 and answer `not_feed`. Track only the names the parser reads (the parent
     of `title` and `url`) instead of a `String` per level. Add a test with 20 MB of nesting.

## Low

6. **Low:** a phone's flag that lands during a check is lost. `finish` rewrites every item row from the plan read
   at the check's start (`src/music_sweep.rs:1968-1971`, `:2016-2045`), so `recheck` and `reported_at` go back to 0
   and the phone has to report again at its next sync. Fix: keep the flags of rows whose `reported_at` is newer than
   the check's start.
7. **Low:** flags left over when the 300-a-day budget runs out mid-check are cleared and stamped as re-resolved
   without a request (`src/music_sweep.rs:1644-1647` with `:1201-1204`). They don't "wait for the next day" (§2.5),
   and they count against the budget. Fix: clear only the flags whose item got a manifest.
8. **Low:** a recheck whose manifest answers 404/410 keeps the old URL (`src/music_sweep.rs:1321`, `:1655-1661`),
   so an episode NRK unpublished stays `ok` and the phones fail and re-report it daily. The deviation note speaks of
   "can't reach psapi", but a 404/410 is an answer. Fix: treat 404/410 like `NotPlayable` (`gone`).
9. **Low:** the add check follows a public feed's redirect to a LAN or tailnet address, because it uses
   `Reach::Any` for every pasted feed (`src/music.rs:408`). The first sweep check then fixes the target as public
   and refuses that redirect, so the entry is added but never lists. Fix: classify the target the way `rss_check`
   does before the add check's GET.
10. **Low:** changing an NRK serie's play order doesn't wake the sweeper (`src/handlers/music.rs:1350-1378`). The
    library sends the new order at once, but the refill of the window waits until the next pass, up to the
    setting's interval. Fix: `wake()` after a save that changes `play_order`.
11. **Low:** a source cover stored by a check whose entry was deleted meanwhile is never pruned. It is stored at
    `src/music_sweep.rs:1958-1966`, and `:1978-1981` rolls back without a prune. Fix: prune on that path too.
12. **Low:** `DEPLOY.md:213` says a `kill -9` mid-fill "neither loops nor starts over". A fill is one transaction,
    so after the 15-minute backoff it starts over, as §2.3 designs it. Fix the text.
13. **Low:** `public_media`'s DNS lookups have no timeout and don't count toward the check's 10-minute budget
    (`src/music_sweep.rs:956-973`, `src/music_net.rs:407-417`). A feed whose enclosures sit on many hosts with a
    dead name server holds the sweeper, and every Check now, for about 10 s per host. Fix: a 5 s
    `tokio::time::timeout` per lookup, and `spent()` checked before it.
14. **Low (note for step 2):** the enclosure check is a one-time lookup by the server
    (`src/music_sweep.rs:966-970`). The phone resolves the name itself, so a rebinding name (public to the server,
    private to the phone) still sends the phone to a LAN address, and so does a name the server can't resolve
    (kept on `Err`). The server side can't close this; the music app could refuse private answers for a public
    entry.
15. **Low:** one `item_errors` or `downloads` row with a wrong JSON type (say `"entry": "3"`) drops the phone's
    whole `music_state` (`src/music.rs:1319`), so the device card loses its Storytel and library lines too. Fix:
    read the arrays as `Vec<Value>` and keep the rows that parse.
16. **Low:** the redirect rule allows 4 redirects, not 5. reqwest's `previous()` includes the first URL, so `>= 5`
    (`src/music_net.rs:282`) refuses the fifth redirect; reqwest's own `limited(n)` uses `> n`.
17. **Low:** durations aren't checked. An `itunes:duration` of "inf", "1e300" or "-5", or a negative
    `durationInSeconds`, reaches the phones as `i64::MAX` or as a negative `duration_ms`
    (`src/music_sources.rs:100-103`, `:330-340`). Fix: keep finite values from 0 to a few days, else None.
18. **Low:** right after Check now, the card can show the old "Checked 2 min ago" with `data-busy=0`, so the
    script stops polling and the card never updates (the PWA run hit this 2 times out of 2). `SweepView::load` reads
    the listing rows after the check's start stamp (`failures + 1`, no `error` yet, so the entry is neither due nor
    failed) and reads `music_sweep.current()` last (`src/handlers/music.rs:2165`), after a fast check has ended. On
    a feed whose check then failed, the card still said "Checked 1 min ago" until a reload. Fix: read `current`
    first, and show "Checking…" (busy) while `checked_at` is later than both `listed_at` and `error_at` and less
    than 10 minutes old (the age limit covers a check cut off by a crash).
19. **Low (step 1, found here):** a phone named with one long word widens every entry page. With a 54-character
    name the page measured 599 px at 360. The phones list's link (`templates/music_entry.html:164`) has no
    `overflow-wrap: anywhere`.

**Simplifications.** Existing machinery that prevents no real failure for one family:

20. **Low:** the per-host breaker: `Breaker`, `Sweep.paused`, `Stop::Deferred`, `CheckReport.deferred`, the pauses
    in `next_wait` and the restore of `failures` in `finish` (`src/music_sweep.rs:180-203`, `:1945-1948`,
    `:2176-2197`, `:2262-2269`). Without it, an NRK outage costs one failed request per NRK entry, and then each
    entry backs off on its own (15 min, 1 h, 4 h, the interval), which is about the same number of requests.
    Today its only real effect is bounding the network case of finding 1. Suggest removing it once finding 1 is
    fixed. It is QA design #17's own proposal, accepted, so this needs the architect's nod.
21. **Low:** the 32 MB per-phone listing budget: `TickFit::ListingsTooBig`, `listing_bytes`, the `lists_too_big`
    notice and the device-card warning (`src/music.rs:884-925`, `src/handlers/music.rs:2628-2631`, `:2785-2797`,
    `:2886`). Reaching it takes about a hundred feeds at the 1,000-item cap ticked on one phone. QA design #14
    allowed stating the size in the doc instead; suggest that.
22. **Low:** the `music_items` revision triggers (`migrations/0051_music_sweep.sql:116-129`). The library sees items
    only through `music_listings.version`, which has its own trigger. These fire on every item row instead: about
    2,000 revision UPDATEs when a 1,000-item feed gains one episode, plus one on every phone flag. Each fire throws
    away every phone's cached library. Suggest dropping them while 0.22.0 is unreleased and 0051 can still be
    edited.
23. **Low:** `music::bump_library_revision` is dead code behind `#[allow(dead_code)]` (`src/music.rs:845-852`),
    because 21b used triggers instead. Remove it.

## Deviations: verdicts

- **`encoding_rs` before quick-xml, instead of quick-xml's `encoding` feature**: acceptable. It is the same library,
  and the BOM and declaration handling is tested.
- **`keep_end`, `item_count` and `bytes` columns; `capped` at either end**: acceptable.
- **The user's answer read narrowly** (the window follows the play order for an NRK serie only; `auto` keeps the
  newest when the serie has more than 100 episodes and is then sent as `newest_first`; a switch when the whole serie
  fits only moves `keep_end`; a full oldest-first serie makes no request): acceptable, and it matches the user
  answer's own reading. The one gap is finding 10, a missing wake.
- **A fill stopped by the budget or the breaker commits without `no_items`; a first fill whose every manifest
  failed is `no_items` but keeps its stubs**: acceptable and tested. Finding 3 is the listed-entry case this misses.
- **The breaker counts a 5xx only from a root, page or feed, and pauses a broken host for 15 minutes in memory**:
  acceptable as built. See finding 20 on whether to keep the breaker at all.
- **A lost source cover keeps its `cover_url`, so it is fetched again without reading the root**: acceptable; it is
  refetched at the next check that is more than a day after the last try.
- **The status block has a `min-height` instead of a fixed height**: acceptable. The swap's `scrollBy` compensates
  for growth (the PWA spot-check confirms it).
- **URLs the rules drop are stored `gone`; a recheck that can't reach psapi leaves the URL**: acceptable for network
  errors and 5xx answers. A 404/410 is not "can't reach" (finding 8).
- **The recheck budget counts items with `rechecked_at` in 24 h, the items of an RSS recheck included**: acceptable
  as a rule. Findings 3 and 7 are the paths where the count is wrong.
- **The offline select logs `music_entry_saved` while the entry form logs `music_entry_changed`**: acceptable.
- **0.22.0 rather than 0.21.0**: either works. This is the user's call and doesn't block anything.

## Step 1 fixes: spot-check (qa-21-step1-code.md, cb094dbc)

All ten fixes still hold at cb61212c:
1. The key file defaults to `data/keys/music-secret.key` (`src/music_secret.rs:25`).
2. Re-uploading a missing file restores its row; a duplicate is refused (`keep_upload`, `src/handlers/music.rs:1649`,
   `:1708`, `:1914`).
3. The library revision and `cached_library` are used by the policy, the library route and the device card; 0051's
   listing triggers move the revision; covers are checked with one scoped query that now includes listing covers.
4. `.page-header` wraps and its `h1` breaks long words (`static/style.css:424-440`).
5. `DiskGuard` re-checks the free space every 64 MB during an upload (`src/handlers/music.rs:1554`, `:1594-1610`).
6. The entry and file limits are part of the INSERT's `WHERE`; a tick still checks and writes in one
   `BEGIN IMMEDIATE`, now with the listing budget (`:2866`).
7. Own keys are `own-<12 hex>` (`src/music.rs:268`); `orphan_files` is listed with a delete button.
8. Ticks redirect to `#music-entry-<id>`, the settings to `#music-settings`, and a file delete to the next
   `#file-<id>`.
9. The key file is written through `.tmp`, synced, renamed, and its directory synced; an empty file is made again.
10. The add check reads a bounded prefix, now 20 MB through `music_net`, and `feed_title` accepts a truncated feed
    whose prefix holds an item.

## Checked and fine

- **The load rules, in code**:
  - One request at a time: `Gated` holds a fair `tokio::sync::Mutex` through the whole body read, and the add check
    and the sweep share it (tested).
  - The cadence: `interval()` gives 1/3/6/12/24 for RSS and 2/6/12/12/24 for NRK, 24 h for an entry no phone has.
  - Check now: once per entry per 15 minutes, 12 an hour, 48 a day, and it runs a full check, which bypasses NRK's
    TTL.
  - Incremental: manifests only for new stubs, at most 10 pending retries, flags and expiries.
  - Rechecks: once per item a day (`flag_reports`' `rechecked_at` condition), a whole entry once a day, 300 a day
    (apart from findings 1, 3 and 7).
  - Nothing re-sweeps on a restart: the stamps are durable, and a check that was cut off backs off as a failure.
- **The counting test** (`simulate_day`, 600/200/100/80/40) measures what it claims: the real `schedule()`/`due()`
  and `check_entry` under a fake clock over (t0, t0 + 24 h], 12 or 24 checks per entry as in QA's table, and an exact
  count of requests. It doesn't run the runner (`run_pass`, the 1 s re-pass, the breaker) or include failures,
  flags, Check now or covers. That is why finding 1 is invisible to it.
- **No audio proxy**: no route serves NRK or RSS audio; the device API serves own files only.
- **Nudges**: `phones_with_entry` after a version or cover change, once per pass or every 5 minutes; the offline
  select nudges the entry's phones; a tick nudges that phone.
- **SSRF, server side**:
  - The public client filters every DNS answer at connect time (no rebinding gap), and IP literals are checked
    before the request and at every redirect, mapped and NAT64 forms included.
  - `no_proxy`, http and https only.
  - NRK is always public, and psapi `_links.next` is accepted only on psapi.
  - `lan` is fixed at the first check from the target's own answers, and covers of a public entry go through the
    public client.
- **Hostile XML**: an internal DTD subset is refused, and quick-xml never expands entities anyway. Only the five
  entities and numeric references are decoded. A cut-off body keeps the complete items.
- **Withdrawn items** are stored `gone` and sent with `url: null` (RSS merge, NRK `NotPlayable`).
- **The window**: a serie's window follows `oldest_first`/`newest_first`, `auto` is newest only when capped, a
  podcast keeps its newest 100, and `serie/<slug>/<programId>` is anchored at the start.
- **Migration 0051**: new tables, an index and triggers only; the setting row is seeded. Existing NRK and RSS
  entries have no listing row, so they are first-filled once after the update (documented). A new version moves the
  library revision, the library's `items`, and the ETag (tested).
- **Concurrency**: an entry deleted mid-check writes nothing (start stamp FK check, exists check inside
  `BEGIN IMMEDIATE`); there is a single sweeper task; a shutdown mid-check rolls the transaction back and backs off.
  Timestamps are UTC text from `now`.
- **Resource limits**: per-kind size caps count decoded bytes; reqwest's overall timeout covers the body;
  `read_timeout` is 30 s; covers go through `photos::process_limited`; a 1,000-item listing is cut at 1 MB from the
  end opposite its anchor (tested).
- **Contract**: the listing route is scoped to the phone's ticks, a 404 before the first listing, with ETag, 304,
  gzip, `no-cache` and `Vary`. `music_listing.json` is pinned. The library's `items` is null for own files. The
  sanitizer keeps known fields only, capped at 50 and 200, and drops unknown keys. The policy shape is unchanged.
- **PWA** (360 x 740, light and dark, against this commit's binary):
  - `/music` stays 360 px wide with a 61-character one-word name, a 200-character source title without spaces,
    failed, never-listed, capped and cut cards, and long per-phone lines.
  - Every form returns to its own anchor: Check now to `#entry-<id>` or to `#status` (in view), the offline select
    to its card, the setting to `#sweep`.
  - The in-place swap kept the first visible card still while three cards above it grew, and a card whose select
    had the focus was left alone until the blur.
  - The text is English and the times say UTC.
  - Contrast is at least about 4.5:1 for the busy and error colours in both themes.
  - Problems: findings 18 and 19.
- **Docs**: NOTICE.md credits vibb (MIT); the root and server CLAUDE.md and DEPLOY.md describe the sweep.

## Notes for the fix round (0ff256dd's follow-up, not findings)

- A routine NRK check with `pageSize=5`: psapi's `_links.next` then continues in pages of 5. "Continue with the
  normal 50-item pages" means asking for `page=1&pageSize=50` again (`walk`'s `seen` dedupes); `page=2&pageSize=50`
  would skip items 6-50.
- Conditional psapi requests: `walk` and `Job::page` treat anything other than 200 as a failure today, so a 304
  must become "unchanged" there first. Finding 2's empty-page rule applies to the small page too.

## Fixes (2026-10-09)

All findings fixed except Low 14 (below); tests in `src/tests/music_sweep.rs` unless named.
- **1**: a flag makes an entry due only while `failures` is 0; a failed check still clears (and stamps) the flags of
  the items it asked about. `a_flagged_feed_that_fails_is_asked_once_then_backs_off` drives `run_pass`; the load
  runs now go through `run_pass` with a fake clock (`Sweep::now`), and `a_day_with_failing_sources_backs_off` adds a
  flagged 404 feed and a failing NRK page to a simulated day.
- **2**: the fallback only for a first fill or a list already in fallback; otherwise an empty/404 page fails the check
  (`not_found`); a fallback merge marks nothing `gone` (`a_listed_podcast_survives_an_empty_psapi_page`).
- **3**: a listed entry whose every item is withdrawn is a successful listing with `url: null` (hard rule 5);
  `no_items` stays for a first fill (`a_listed_entry_whose_every_item_is_withdrawn_lists_them_gone`).
- **4**: the whole-entry re-resolve counts the items reported within 24 h, handled or not
  (`two_reports_an_hour_apart_re_resolve_the_whole_entry`).
- **5**: the RSS parser is bounded (first/last `keep` items, 8 KB of text per element, nesting past 64 = `not_feed`,
  a closing tag unwinds to its own name, a UTF-8 body isn't copied); the 20 MB cap stays
  (`music_sources::tests::hostile_and_huge_feeds_stay_bounded`).
- **6, 7, 8**: a flag that lands during a check survives its rewrite; flags past the day's budget stay unstamped;
  a re-resolve answered 404/410 is `gone` (`flags_are_kept_until_their_item_is_asked`).
- **9**: the add check classifies a pasted feed like the first sweep check (`music_sweep::feed_is_lan`)
  (`the_add_check_judges_a_feed_like_the_sweep`). **10**: a saved change of play order wakes the sweeper.
  **11**: a cover stored for an entry deleted mid-check is pruned (`a_cover_for_an_entry_deleted_mid_check_is_pruned`).
  **12**: DEPLOY.md's `kill -9` line corrected. **13**: media-host lookups wait at most 5 s and stop when the check's
  budget is spent. **15**: `music_state` lists are read row by row (`music::tests`). **16**: five redirects are
  followed, a sixth isn't (`music_net::tests`). **17**: durations outside 0-7 days are dropped
  (`music_sources::tests::durations_are_checked`). **18**: the cards read `current` first and show "Checking…" while a
  check's start stamp is newer than its end and less than 10 minutes old
  (`the_card_says_checking_while_a_check_runs`; PWA: Check now on a listed feed whose check then failed updated in
  place to the error, no reload). **19**: the entry page's phone list wraps (PWA: 360 px with a 56-character
  one-word phone name, light and dark).
- **20-23**: accepted by the architect - the breaker, the listing budget, the `music_items` triggers and
  `bump_library_revision` are gone; see 21b's status ("After QA's code review").
- **Not fixed - 14**: a phone resolving a rebinding name itself is the music app's to refuse (step 2); the server
  can't see the phone's DNS answers.
- The follow-up "cheaper change checks" is in too: a routine NRK check reads 5 episodes first
  (`a_routine_nrk_check_starts_with_five_episodes`); psapi sends no validators today.
