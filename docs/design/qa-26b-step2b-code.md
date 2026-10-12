# QA 26b step 2b - code review (the listing, the gate, the cards, the import, the contract)

Scope: branch `music-2-storytel`, `0854ed9..c2864bf` (`fca26c4` server, `70a332f` the shared-contract commit,
`6e36ea2` wording, `c2864bf` status), read in full against `26b-storytel.md` §0, §1.1-1.2, §2.4-2.7, §7, §8 step
2b, §9, §13 (M3, L12, L14), step 2a's status and fix round, `qa-26b-step2a-code.md` (its note for 2b) and vibb
`pipewire` `1fcadac` (`storytel.py` `sync`/`bookshelf_cached`/`write_shelf`/`newest_book_id`, `library.py` 87-89 and
862-883, `content.py` 334-340, 365-376, 500-509, `tests/storytel_{expand,sync,routes}.py`).

Runs (this review): server `cargo test` **571 passed, 0 failed** (`parent_pin` passed this time); `cargo clippy
--all-targets` 12 warnings, all in `models.rs`, `devices.rs`, `dns_engine.rs` (pre-existing, none in a 2b file);
`cargo fmt --check` clean. Music `./gradlew testDebugUnitTest -PwarningsAsErrors=true --rerun-tasks` 413 of 413;
launcher `./gradlew testDebugUnitTest` 900 of 900. `MUSIC_LIBRARY_SNAPSHOT_WRITE=1 cargo test music_library_snapshot`
regenerates a file byte-identical to the committed one (diffed in the scratchpad, file restored);
`cmp server/testdata/music_library.json music/app/src/test/resources/music_library.json` identical;
`git diff --stat 0854ed9..c2864bf -- server/migrations/` empty; `music_listing.json`, `music_state.json`, the AIDL and
`bridge_contract.json` untouched.

Verdict: **good to go after one Medium fix** (the sweep's stale-shelf fallback); the Lows are small. The gate is
complete and tight, the contract commit is right, every §7 row mapped to 2b is asserted.

## Findings

### M1 (Medium) - the sweep's shelf read falls back to the kept shelf, so a refused login or an outage still shows "Checked" on the entry card

`music_sweep.rs:2008` reads `state.storytel.shelf_counted(state, max_age)`, which is `Account::shelf`: on *any* fetch
error it returns the last good shelf of this generation (`storytel.rs:1030`, vibb's `bookshelf_cached` "stale beats
dead"). The cooldown-first rule (`music_sweep.rs:1997`, QA 2a's note) only covers the hour after a refusal is stamped;
the refusal itself, and every later one, still comes back as `Ok(stale shelf)`.

Failure path (a family with one series, the default `I` = 6 h, a password changed in Storytel's app): the check at
T runs after the last cooldown has expired; the JWT (1 h) has expired too, so `fetch_shelf` logs in, Storytel refuses,
`record(Refused)` stamps the cooldown - and `read_shelf` returns the kept shelf. The check succeeds: `failures` 0, the
error cleared, `listed_at` moved, the card says "Checked just now". The next check is at T + 6 h, again after the
hour: the same. The entry card **never** shows `login_refused` while the process runs - exactly what §2.4's rule was
written to prevent ("otherwise a kept shelf would hide the refusal until a restart or a save"). With several
entries the first of each pass reads "Checked", the rest "Storytel refused the login" (inconsistent). Likewise
offline: Check now (`shelf(0)`) with Storytel unreachable "succeeds" with the stale shelf. Only `#storytel`'s status
line tells the truth. The phones are unaffected (the list is the same either way).

The fallback buys the sweep nothing: a failed check already keeps the last good list (21b §2.4, `finish`). Fix: give
the sweep a read that doesn't fall back - e.g. `shelf_counted` returns the fetch error alongside the stale groups
(`(Result<..>, sent, Option<StorytelError> /* fell back */)`) or takes a `stale_ok: bool` - and in `storytel_check`
fail the check with that error's code (`login_refused`, `network`, `http_503`, ...) when the read fell back. The
picker keeps the vibb behaviour (it already reads fresh via `bookshelf`). Test: a kept shelf older than 300 s, the
cooldown expired, the JWT expired (`advance` > 1 h), the login answered 401 -> the check fails `login_refused` with one
login request, the list kept; and Check now with the shelf URL failing -> `network`, not "Checked". (Side effect to
accept: in an outage each Storytel entry of a pass tries once; with the gate's pacing that is a few requests.)

### L1 (Low) - saving a login doesn't wake the Storytel entries that failed for want of one

`save_storytel` (`handlers/music.rs:1116`; accepted/unreachable branch, around 1206) seals, keeps the probe's
session and nudges the phones, but neither wakes the sweep nor makes the failing Storytel entries due. An entry
imported before the login was saved (the natural order for a family moving from the Pi: import first) has failed
`storytel_off` since; after the save it waits its backoff - up to `I` (6 h), and 24 h once it has failed for a week.
The same after a refused password is corrected (`login_refused`, `storytel_key`). Meanwhile the phones already get the
entry (the gate opens) with `items: null`. Fix: after a stored save, make every Storytel entry whose last error is
`storytel_off`/`login_refused`/`storytel_key`/`no_jwt` due now (e.g. treat `error_at < saved_at` as due in
`schedule_one` for Storytel, or reset those listings' backoff in the save's transaction) and `music_sweep.wake()`.
Test: import a Storytel row without a login (`storytel_off`), save the login, `run_pass` lists it.

### L2 (Low) - the library card's per-phone line for a gated phone says "nothing on it reported yet"; a login error says "The phones keep the list"

`SweepView::phone_lines` (`handlers/music.rs:2945-3005`) doesn't know the gate: a phone with the entry ticked but its
Storytel switch off (or no login saved) gets "Ella: nothing on it reported yet" forever (or "has an older list" from
before the switch went off), and the card counts it in "on 2 phones". The device card has the note (§2.3), the library
card - where the parent looks at the entry - doesn't. And `status` (`:2809`) prints "Last check failed ...: no
Storytel login on this server. The phones keep the list from 12 Oct." - after a clear the phones have *dropped* the
entry (§2.7). Fix: load the gated phones in `SweepView::load` (`device_policy.music_storytel = 0` or no ciphertext)
and give a Storytel entry's gated phone "Ella: not sent - Storytel is off on this phone" / "... no Storytel login is
saved"; for `storytel_off`/`storytel_key` drop "The phones keep the list" (say "The phones don't get it until a login
is saved"). One assertion each in `library_gates_storytel`.

### L3 (Low) - a Storytel entry's per-phone item errors use the feed wording

`phone_lines` (`handlers/music.rs:2997`) words the first failing item with `entry_error_text`, so a phone's transient
mint failure (`Failed` -> `http_503`, `network`; §4.1) reads "the source answered 503" / "network" on a Storytel card
whose own status line says "Storytel answered 503" (`sweep_error_text`, deviation 2). Fix: `sweep_error_text(&entry.source,
&first.error)` there (it falls through to `entry_error_text` for every other code, `no_audio` included).

### L4 (Low) - an imported Storytel target has no length bound

`storytel::parse_target`/`id_ok` (`storytel.rs:128-150`) accept any number of `[A-Za-z0-9_-]`; `parse_link` caps a
pasted link at 2000 characters, and `normalize_shelf` skips consumable ids beyond 64 (§2.1). Since 2b the import
(`music_import.rs` `classify`) takes `storytel:` targets with no bound, so a hand-edited Vibb file (up to
`MAX_IMPORT_BYTES` = 1 MB) can store a ~1 MB target that every music page renders and every ticked phone's library
carries. Admin-only, so Low. Fix: `id_ok` requires `1..=64` characters (the shelf never yields more, so nothing real
is refused); one row in `storytel::tests::targets` and in `storytel_targets_import_as_storytel_entries`.

### L5 (Low) - test gaps behind four rules (each passes with its rule removed)

- Deviation 5 (a shelf duration of 0 is NULL, `music_sweep.rs:2026`): no fixture book has `durationInMilliseconds`
  0 or absent, so `(book.duration_ms > 0).then_some(..)` -> `Some(book.duration_ms)` passes every test. Add a book
  without a duration to the shelf in `the_sweep_lists_a_series_like_a_feed` (via `shelf_without`'s sibling) and
  assert `duration_ms: null` in the served listing.
- `cover_in_library`'s first branch (a parent's own cover on a Storytel entry, `music.rs:1099`): `library_gates_storytel`
  only fetches the listing's cover (third branch). Upload a cover to the series and assert 404 for `other` too.
- The logins counted into the check's budget (deviation 1): `report.requests` is asserted only where no login happens
  (`1`, `0`); assert `2` after the hour in `a_refused_login_fails_the_check_without_a_request` and in
  `one_shelf_serves_a_pass` (first entry `2`, the others `0`).
- `sweep_error_text`'s `network`, `timeout` and `http_<code>` arms (deviation 2) aren't asserted (only `too_big`); one
  line each in `storytel_entries_have_their_own_link_and_label`.

### L6 (Low, hygiene) - the sweep's credit line doesn't name what `storytel_check` ports

`music_sweep.rs:32-35` credits vibb's `content.py` and `library.py`; `storytel_check` ports `storytel.py`'s `sync`
(minus the downloads) and `write_shelf`, named only in the function's doc. Add `pi/vibb/storytel.py` (`sync`,
`write_shelf`) to the "Ported in behaviour" sentence (`storytel.rs`'s header already credits the module; `NOTICE.md` is
step 5's).

## The implementer's choices (the status lists 7, the brief said 8 - the seven, plus `Account::shelf_counted` counted with 1)

| # | Choice | Verdict |
|---|---|---|
| 1 | The shelf read runs through `Account`; the budget is checked before it and its 0-4 requests added after (`shelf_counted`, a per-session counter) | Accept. The counter's delta is taken under the session lock, so a concurrent picker read can't leak into it; a check can exceed its 250-request budget by at most 4. But see M1: the read must not fall back for the sweep. Test gap in L5. |
| 2 | `sweep_error_text(source, code)` beside `entry_error_text` | Accept - `too_big`/`network`/`timeout`/`http_*` speak of "the feed"/"the site" otherwise. Every code a Storytel check can produce has a text on both card paths (listed: `sweep_error_text`; never listed: `CheckError::Storytel(..).message()`): `not_on_shelf`, `storytel_off`, `storytel_key`, `login_refused`, `no_jwt`, `bad_shelf`, `too_big`, `timeout`, `network`, `http_<code>`; `no_audio` and `login` on the phone lines. Extend it to the phone lines (L3). |
| 3 | "N books" without the "newest:" part | Accept - a series is read in order; vibb's `newest_book_id` only feeds the NY badge (phase 1's per-key Nytt on the phone). |
| 4 | The device card's two notes under the switch, only with ticked Storytel entries; `refused` a warning, `none` a line | Accept. Escaped by Askama, no secret. The library card lacks the same knowledge (L2). |
| 5 | A shelf duration of 0 is NULL; titles cut at 200; the listing title is the group's name | Accept (vibb's `or 0` is "unknown"; a 0-long book would read as finished). Untested (L5). |
| 6 | The import doesn't look at the shelf | Accept - it is what §2.6 says ("Nothing is fetched: the sweep lists it"). 4c's worry doesn't apply: the import is behind the admin session and CSRF, the target is grammar-checked, and a target is only ever *compared* with the shelf's groups (`storytel_check`), never put into a request (the phone mints from listed consumable ids). Bound the id's length (L4). |
| 7 | The snapshot's Storytel entry in Lydbøker (three categories) | Accept - where Add puts it; `ContractFilesTest` follows. |

**The spec problems listed**: (1) §2.4 puts `too_big` among the Storytel texts of a function whose `too_big` is the
feed's - agree (choice 2). (2) `ContractFilesTest`'s `"cache": -1` -> `-2` replacement now hides the Storytel series
too - agree, harmless: `entryIds` names 5 and the assertion `entries == [1]` would fail if it stayed visible. (3) §8's
"entry count now four" is the phone's four (1, 2, 3, 5) - agree. **Two more**: §2.1/§2.4 should say the sweep's read
does not fall back to the kept shelf (21b already keeps the last good list; M1); §2.3's per-phone lines don't cover a
ticked phone the gate excludes (L2).

## Checked and fine

- **The gate (§2.7)**: `storytel_gate!()` is `concat!`ed into `LIBRARY_ENTRIES` (binds phone, extra, phone - the one
  caller `library_for` binds all three), `device_listing` (tick join + gate) and both entry branches of
  `cover_in_library` (binds counted: 8 `?`, 8 binds); the own-files branch can't hold Storytel. The phone-facing routes
  are exactly library, listing, cover, file (own only) and `storytel` (switch-gated since phase 1) - a phone with the
  switch off gets 404 for a guessed entry id or cover hash; one that never ticked it already did. `flag_reports`
  excludes Storytel. The revision moves on the switch (both ways, `AFTER UPDATE OF music_storytel ... IS NOT`; the
  device page's save is an `UPDATE`, a new device's row is inserted with 0) and on the login's presence (save from
  NULL, clear to NULL; the singleton row exists since 0049), so `cached_library` rebuilds and the policy's
  `library_version` moves -> the phones re-sync and prune. `clear_storytel` drops the entries for every phone
  (`library_gates_storytel`, `clear_drops_storytel_from_libraries`: both phones 404 on library/listing/cover; the tick
  stays). Removing the gate from any of the three queries fails `library_gates_storytel`.
- **The sweep**: `schedule`'s `IN` takes `storytel`, `schedule_one`/`check_entry` go through `entry_link` (grammar,
  never `parse_link`); `interval` gives Storytel `I`; `refill`/`recheck_only` stay NRK's. `storytel_check`: the
  credentials and the cooldown first (`login_refused`, zero requests - the test fails without the rule: the kept JWT
  would fetch), then `shelf(300 s)` or `shelf(0)` for Check now (`mode.requested`), the group by target, `not_on_shelf`
  keeps the list (the plan's items untouched -> no write). The list: `seq` = the group's reading order, key =
  `model.id` (fixture: format ids `fmt-*` differ), `state 'ok'`, `url` NULL, `hls` 0, `art_url` filtered by
  `media_url_ok`, `first_seen_at` kept; a book that leaves the shelf leaves the list and the version (and comes back
  with the same version); locked and geo books listed; `keep_end 'first'` -> `finish`'s and `device_listing`'s
  anchor; `capped`/`fallback` off, `lan` false; the group's cover through 21b's `cover()` (`Reach::Public`,
  `public_media`, once a day). `build_listing` drops "url-less first" from the anchor's far end - for Storytel every
  item is url-less, which is the same order. A sqlx error inside `credentials` becomes the check code `database`
  (below).
- **`url: null` on phase-1 phones**: `Sync.applyListing` stores the rows and makes only url items news;
  `DownloadPlan` wants only `KeepItem(key, url != null)` (`item.url!!` only after that filter);
  `Snapshot.visibleKeys` shows a url-less item only with a copy, which a Storytel book can't have before step 3; the
  launcher's `libraryHead` passes every non-`own` entry's `items`. So a listed Storytel book stays hidden and is never
  fetched; the card reads "0 of 0 books on the phone" until step 3.
- **The contract commit `70a332f`**: one commit with the snapshot (both copies byte-identical, regenerated exactly),
  the server's `music_library_snapshot` and `library_lan_only_for_a_lan_rss_feed`, music's `ContractFilesTest`
  (version, categories, sources, `entryIds`, the entry's target/items/cache/resume/lan/cover, covers, lan and
  sonos-room counts), the launcher's `MusicPlanTest` (`head.listings[5] == "00ff00ff00ff00ff"` and
  `listingsToFetch` returns `[5]` - the courier carries the Storytel listing). `OfflineTest`'s `"cache": 3` hits only
  entry 2; `BookmarksTest`'s `"resume": true,\n` removal now also strips entry 5's, which defaults on - harmless.
  The entry key `7ac843e9f5d4` is vibb's `cache_key_for` sha1[:12] of the target. The import-test edits sit in
  `fca26c4` with the import code, so each commit is consistent.
- **The import**: `Skip::Storytel` gone; `classify` -> `parse_target` on the trimmed target (case-sensitive like
  vibb's `TARGET_RE`), else `BadLink` with the grammar in words; the candidate's cache 0 -> -1, others kept (the
  `-1 | 0..=100` filter as for every row); the preview's wording; the preview document round-trips.
- **Cards and pages**: `SweepView::card` ("N books", Storytel's choices with a stored 0 shown as all, a foreign value
  as "The first N (from Vibb)"), `check_now`, `/music/cards`, `save_offline` (vibb's choices, 0 -> -1, a stored other
  value kept, garbage 400), the entry page through the same card. All new text goes through Askama (autoescaped);
  no e-mail, password, JWT or URL with a token anywhere. 360 px from the CSS: the phone lines are `overflow-wrap:
  anywhere`, the actions row wraps, the offline select is `width: auto` with short labels, the device card's note is a
  plain `<p class="subtitle">`, the mobile-data label is a wrapping flex row; no fixed widths added. Saves redirect to
  the card's anchor as before.
- **Texts**: `item_error_code` takes `no_audio`, `Download.waiting` takes `login`; the sanitizer test covers both and a
  near-miss (`no_audio_x`). The L14 help text is the decided sentence. `storytel_key` has its text on both card
  paths.
- **§7 rows for 2b**, each asserted: expand 2 (per-book art), sync 1/1b (locked and geo listed), 6b (`one_shelf_serves_a_pass`:
  three entries, one login + one shelf), 7 (`music::tests::storytel_cache_zero_is_all` under that name: library,
  card select, entry save, import; a podcast keeps 0), routes 4's 2b half (`the_cards_show_the_phones_storytel`),
  routes 5's 2b half (the library loses the entries), and the new rows: `library_gates_storytel`, the 404s, reading
  order, url null, a book leaving, `not_on_shelf` keeping the list, `login_refused` without a request, the cover,
  `flag_reports` (a Storytel `http_403` would be flagged without the `NOT IN`), the import test, the sanitizer,
  the snapshot; after QA: L2's anchor (`keep_end` asserted), L5 (Check now one request), M3 (schedule via `run_pass`,
  the card's Check now, `/music/cards`, `save_offline`).
- **Hygiene**: no migration touched; fmt clean; clippy adds nothing; `music.rs`, `music_import.rs` and `storytel.rs`
  name vibb's `library.py` 87-89 where they port it.

## Not worth fixing

- The cooldown-first rule fails a check even while a JWT of the same generation is still valid (the bookshelf would
  answer): that state arises only when a forced re-login is refused after a 401, which already means the JWT is dead.
- A sqlx error while reading the credentials becomes the check code `database` ("database." on a never-listed card)
  instead of propagating like the other checks' DB errors: a failing database shows everywhere else first.
- `the_sweep_lists_a_series_like_a_feed`'s `matches!(parse_link("storytel:..."), Err(_) | Ok(Link::Rss { .. }))` is
  loose (it really is `Err`); `parse_link` has no Storytel arm to regress into.
- The device card's "N entries · kB" counts ticks (Storytel ones the gate excludes, and Spotify ones, pre-existing);
  the Storytel note beside it says why.
- During an outage each Storytel entry of a pass tries the shelf once (the kept shelf's age isn't refreshed by a
  failure) - vibb's per-entry subprocesses did the same, and the gate paces it.
- A sweep read holds the session lock for up to ~100 s in the worst case (two logins, two shelves at their timeouts),
  so a picker visit then may answer "Storytel didn't answer in time"; rare and self-explanatory.
