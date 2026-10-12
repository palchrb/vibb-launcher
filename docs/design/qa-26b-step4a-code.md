# QA (code): design 26b step 4a - a Storytel book's cover as its listing item's `art` hash (2026-10-12)

Reviewed: `git diff c431b61..f0aa3de` on `music-2-storytel-4a` (`acaf179` code, tests, docs; `f0aa3de` status), in
full. Read against root and `server/CLAUDE.md`, 26b §2.4, §2.7, §5.5, §8 step 4a, the "Merge of music-1b-sonos with
design 27" section and "Step 4a implementation status", `qa-26b-merge27-code.md` (on `music-2-storytel` at `a9dcd3c`),
design 27 and `qa-27-code.md` for the art pass's rules, and vibb `pi/vibb/storytel.py` (`normalize_shelf`,
`entries_for`, `sync`). Server only. No product code changed here; one scratch test was run and reverted (below).

## Runs (this worktree)

- `cargo test` (`CARGO_TARGET_DIR=/home/user/vibb-launcher/server/target`): **660 passed, 0 failed** (the
  time-dependent `parent_pin` passed this run). Matches the status section's count.
- `cargo fmt --check`: clean. `cargo clippy --all-targets`: the same 12 warnings (`models.rs`, `dns_engine.rs`,
  `handlers/devices.rs`), none in a file this step touches.
- `git diff --stat c431b61..f0aa3de -- server/migrations/ server/testdata/ music/ launcher/`: empty (no migration, no
  contract file, no music or launcher code).
- Scratch test (appended to `music_storytel.rs`, run alone, then `git checkout`; `git status` clean): Medium 1's
  failure path, reproduced - see there.

## Findings

0 High, 1 Medium, 1 Low. The code does what step 4a's plan says, and all of `qa-26b-merge27-code`'s M1 and L1-L4 were
addressed. The Medium is a premise of the plan (and of that QA's verdict 6 g), not an implementation slip: design 27's
"series picture" judging does not carry over to books.

### Medium 1 - A book judged `same` shows the entry cover, which is not always that book's picture: after the shelf's first-seen book changes, with a parent-set cover, or with templated covers, a book shows another book's cover

- **Where:** `server/src/music_sweep.rs:2399` (`cover_candidate = group.cover`, the first-seen book's,
  `storytel.rs:435`), `:2888-2900` (step 1: a book whose `art_url` is the cover's URL is `same`), `:2904`, `:2975`
  (steps 2/4: within `SAME_PICTURE_BITS` 5 of any reference print is `same`), `:1253` (`new_cover`: an old cover's
  print joins `old_prints`, up to 3), `:2764` (`rejudge`: a `same` whose print is still in the references stays
  `same`), `:2665` (`twins`). The phone shows `artHash ?: entry.cover`, and `entry.cover` is the parent's cover or the
  listing's (`LibEntry`, 26b §2.7).
- **Evidence:** for NRK/RSS, `same` means "this episode's picture is the series picture", so showing the entry cover
  shows the same picture. For Storytel the "series cover" is just one book's cover (the first-seen book on the shelf,
  vibb `normalize_shelf`), so `same` means "show whatever the entry cover is now" - and that can move away from the
  book. Reproduced with a scratch test (fixture shelf, then the same books with book 1 first - `shelf_without(&[])`,
  whose sorted keys put `160661` first - and Check now 25 h later):
  - before: books `[(own, h1), (same, None), (None, None)]`, the listing's cover = book 2's picture (`2-model.jpg`);
  - after: `[(same, None), (same, None), (None, None)]`, the listing's `cover_url` = `1.jpg`, its `cover_hash` = `h1`
    (book 1's picture), the served `art` all null. Book 1 correctly falls back to the (now its own) cover, but book 2
    stays `same` (its print is in `old_prints`) and now shows **book 1's** cover, in the carousel, the player, Nytt
    and on the lock's tile. It stays so: `old_prints` keeps 3 earlier covers, and a `same` row is never due again.
- **Failure paths:**
  1. The shelf's first-seen book of a series changes - its `items` order is Storytel's (vibb never re-downloads
     `cover.jpg`, so vibb doesn't care); if Storytel lists the most recently added book first, every book the parent
     adds to a series moves the "series cover" to that book. A book removed and re-added has the same effect (its new
     row is fetched and judged against `old_prints`).
  2. A parent-set entry cover (`e.cover_hash`): the one `same` book shows the parent's picture while every other book
     shows its own - for a podcast that is "the series picture", for a series of books it is one book with the wrong
     face.
  3. Templated series covers (one layout, another number or title per book): a cover within 5 bits of the first-seen
     book's is `same` (shows that book's cover), two within 2 bits with the same tone are `twins` (one hash for both).
     dHash on a 9x8 grey thumbnail barely sees a changed digit, so this is plausible for kids' series; after 5 such
     `same` in a row the backlog turns off and only new books are tried for a month. (Not reproducible without real
     covers - the "Per-book covers" device check should include such a series.)
  - 26b §7 row 2 ("per-book art beats the series cover") is vibb's rule: `entries_for` always uses `<id>.jpg` when it
    exists. 4a meets it only while the entry cover happens to be the book's own picture.
- **Fix:** for a `Link::Storytel` entry, judge no book against the series: every fetched cover is `own` - in
  `art_pass`, an empty reference set for Storytel (`let refs = if storytel { Vec::new() } else { plan.art.refs() }`,
  so `rejudge` turns today's `same` rows untried once, and they are fetched again), step 1 skipped, and `twins` off
  (each book's cover URL is its own id's, so a real duplicate is rare and a wrong merge is visible). Cost: one more
  request per series (the first-seen book's cover; or reuse `found`'s processed JPEG as that book's `own` file without
  a request). Tests: `the_sweep_lists_a_series_like_a_feed` - `items[1]` becomes `own` with a hash (served byte for
  byte), requests 3; `a_check_keeps_...`/`the_art_pass_reads_...` follow; a new case: the shelf's first book changes
  and book 2 keeps its own hash (the scratch test above, inverted). Docs: §5.5's and §8 4a's "a book whose cover is
  the series' is `same`", `server/CLAUDE.md`'s Storytel bullet, the status section's "The judging"/"The twin merge"/
  "The backlog" lines, the "Per-book covers" device check ("the series' book shows the series cover" -> its own; add a
  series with templated covers). If the user prefers to keep `same` (one request fewer, the entry cover shown for the
  first book), at least drop `old_prints` for Storytel (a `same` book then re-judges when the cover moves) and record
  failure paths 2 and 3 as known limits.

### Low 1 - `server/CLAUDE.md` says the card's count line adds "· N with episode art"; a Storytel card never does

- **Where:** `server/CLAUDE.md:735`; `server/src/handlers/music.rs:2918-2926` (the Storytel arm returns "N books"
  before the `with_art` part).
- **Evidence:** the omission is deliberate (status section, "Not done") and fine on the card, but the module doc states
  it unconditionally.
- **Fix:** "... adds '· N with episode art' (not on a Storytel card: 'N books')". Or add the part to the Storytel arm
  ("· N with their own cover") - one line, the `with_art` count already includes Storytel rows.

## Verdicts

1. **`art_candidate(item, link)` - OK.** `music_sweep.rs:2591-2594`: `ok` + `art_url` + (`url` or a `Link::Storytel`).
   The link decides; an NRK/RSS `ok` item without a URL stays out of `ART_WINDOW` (asserted for both a feed and a
   podcast). `pending`/`gone` never qualify for any link (asserted for feed and Storytel, with and without a URL). A
   withdrawn book has no row (the list *is* the shelf's group), ebook-only rows never reach the list, `not_on_shelf`
   fails the check before the pass; `no_audio` is a phone-side item error, not a server state, so such a book's cover
   is fetched like any listed book's - right, it is listed.
2. **`storytel_check` carry-over - OK.** `:2378-2392`: a kept book is `plan.items[at].clone()` with `state` "ok",
   `title`, `url` None, `hls` false, `duration_ms`, `art_url` (the shelf's cover, `media_url_ok`) and `attempts` 0 set
   over it. Survive: `key`, `first_seen_at`, every `art_*` (`art_hash`, `art_from`, `art_state`, `art_print`,
   `art_tone`, `art_at`, `art_fetched_at`, `art_fails`), and `published_at`/`available_until`/`reported_at`/
   `rechecked_at`/`recheck` - all inert for Storytel (always None/false: `flag_reports` excludes Storytel, the shelf
   has no dates). `Item` has no error or seen/news field; news is by key (`plan.new_items`). `position` looks up by key
   in the old list, which isn't touched until the `collect`, so a reordered shelf maps each book to its own old row.
   Duplicates never reach it: `normalize_shelf`'s `seen` set is shelf-wide (`storytel.rs:389`). A book that leaves
   and comes back is a fresh `Item::new` (its old row was dropped with the list): one refetch, counted new - 2b's rule,
   harmless. The failed-cover test pins it (a rebuilt row resets `art_fails` and is due at once).
3. **Order - OK.** `:2840-2847`: explicit `newest_first`/`oldest_first` for every link; `auto` per link - Storytel
   oldest first (not tied to `keep_end`, which is always `first` for Storytel and only cuts the end), an NRK series as
   before, podcast/RSS newest first. `the_art_pass_reads_a_series_in_reading_order` with `set_art_per_check(1)` fetches
   `1.jpg` and not `3.jpg` in check 1 (newest first would take book 3 first; book 2 is step 1 and costs no get), then
   `3.jpg` in check 2 - it does show book 1 before book 3. No test of an explicit order on Storytel (the arm is shared;
   not worth one).
4. **The art pass on books - OK as design 27 applies it, except Medium 1.** Budget: shared `ART_PER_CHECK` 20 /
   `ART_PER_DAY` 400; a 50-book series fills over 3 checks, once; a big first fill can take a day's share before
   podcasts' new episodes, the same as adding a big NRK series - acceptable, no Storytel cap needed. 404: one try a
   day, at most three (`art_due`; M1's test pins day 1 and the second try; the cap is design 27's tested rule).
   Query-only change: adopted with no request (tested, hash and version unchanged). Path change: not within
   `ART_MOVED_RETRY_DAYS` (7, design 27 code QA M1's rule against feeds renaming pictures at every fetch) of the
   judged result, then once; new hash, old file pruned, listing restamped, phone nudged (tested). A lost file:
   `recover_missing` forgets it, `restamp_versions` stores the version from the rows, the next check refetches and the
   version returns (tested). The 16:9 reference: never set for Storytel (`alt_candidate` None), no request. The `same`
   judging, the twin merge and the backlog: Medium 1. Series with no cover but books with covers: no references,
   every book `own` - right.
5. **The gate - OK.** `music.rs:1132-1152`: `concat!` of the query and `storytel_gate!()`, three placeholders
   (`d.device_id`, `i.art_hash`, the gate's `p.device_id`), three binds in that order. `a_books_cover_is_served_only_
   behind_the_gate`: ticked + switch on 200; switch on but unticked 404; ticked but switch off 404; after
   `/music/storytel/clear` (which only clears the login - listings, items and files stay, so the gate alone answers)
   404. The gate is one `EXISTS` query before any file read, and every refusal is the same bare 404 (`music_api.rs:
   145-163`); the only timing difference is "in this phone's library but the file is missing", which the phone
   already knows. No oracle across the gate.
6. **`restamp_versions` and other source filters - OK.** `music_sweep.rs:964` takes Storytel. Remaining `'nrk'`/
   `"nrk"` in the four files: `music_sweep.rs:420` (`interval`, NRK's longer cadence), `:482` (`refill`, NRK's kept
   end), `:546` (`recheck_only`, NRK manifests) - NRK-only by nature, Storytel doesn't belong; `music.rs:204, 216,
   308-309, 321` (link parsing and labels) - not art; `:590, :937`, `music.rs:780, 1100, 1110, 1142`,
   `handlers/music.rs:2913, 3134, 3259` all include Storytel; `photos.rs` and `handlers/music_api.rs` have none
   (`MUSIC_ART`'s referenced/forget SQL is source-free). The card's `with_art` count is source-free too (shown only
   for NRK/RSS, Low 1).
7. **Tests - OK.** They assert behaviour: request lists and counts per URL, `art_state`/`art_hash` per book, the served
   listing's `art` and version, the cover route's bytes against the hash, the stored version against the served one,
   pruned files, `report.changed`/`phones`. Read against the claimed mutations: rebuilding from `Item::new` breaks
   `kept == first` (`art_at` restamped), the failed-cover test (fails reset, due at once) and the lost-cover/series
   tests' row equalities; `auto` newest first fetches `3.jpg` in check 1; no gate serves `off` and the cleared login.
   Dropping `'storytel'` from `restamp_versions` breaks the lost-cover test's `stored.version == restamped` (not in the
   implementer's list, but it bites). Nothing passes with the feature removed. `shelf_covers` edits the fixture as text
   (re-serialising sorts keys and would make book 1 first-seen, moving the series cover - documented in its doc
   comment); the insert lands after `{"type": "abook", ` before more keys, so the result is valid JSON (`normalize_shelf`
   parses it in three tests), and `unwrap()`s make a fixture format change fail loudly. `the_sweep_lists_a_series_like_
   a_feed`: requests 2 (cover, then `1.jpg`), `(own, same) = (1, 1)`, `items[0]` hash stored and served byte for
   byte, `items[1]`/`items[2]` null - as `qa-26b-merge27-code` L2 asked.
8. **Hygiene - OK (Low 1).** fmt clean, clippy the same 12, no migration or contract file touched. `server/CLAUDE.md`'s
   Storytel bullet and the `art_in_library` sentence are accurate (Medium 1 changes the "`same`" sentence). The PWA
   card's missing "N with episode art" for Storytel is acceptable. The "Per-book covers" device check is the right
   place for the real covers (L3); see Medium 1 for what it should add. §6i's upgrade check is sensible (positions and
   bookmarks across Room 1 -> 2).
9. **`qa-26b-merge27-code` - all addressed.** M1: the kept book is the old row, pinned by the failed-cover test; "a
   second check keeps the hashes" kept and described as pinning the result. L1: `restamp_versions` widened, with a
   Storytel restore test. L2: the series test asserts `items[0]` gains the hash, `items[1]` stays null, the requests
   follow. L3: the emulator line dropped for a device check. L4: the `VersionMismatch(71)` note is in the merge
   section. Verdict 6 c (key on the link) and d (Storytel oldest first) done; 6 g (keep `same`) is what Medium 1
   questions.

## Not worth fixing

- `plan.position` is a linear scan per book (O(n^2) per check) - a series has tens of books.
- No Storytel case for an explicit `newest_first` order in the art pass; the arm is shared with feeds and unchanged.
- A cover missing from one shelf read drops the book's hash (and the file is pruned) and refetches it when it comes
  back - design 27's rule for feeds, the same here.
- §6i's upgrade line says the listings come back "with the hashes"; on the emulator the fake shelf's Storytel listing
  has none (L3), so only NRK/RSS hashes are re-applied there - the check is about positions and bookmarks anyway.
- `attempts: 0` is set explicitly over the kept row; it was always 0 for a book (never `pending`).
- `server/CLAUDE.md:670` still says step 2a leaves Storytel out of the library and the sweep - stale since 2b, not this
  step's (noted by earlier QA).
