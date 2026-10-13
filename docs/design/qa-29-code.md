# QA 29 (code) - a new entry is Nytt news, and own-file copies after an id reuse

Reviewer: code QA, 2026-10-13. Branch `design-29-impl`, `73787e7d..4655f8d3` (4 commits: `1eee7975` the rules,
`22aea0c3` Room 3 and the glue, `402ef47d` the docs, `4655f8d3` own-file copies). Read against
`docs/design/29-music-news-new-entries.md` (the spec, its Design QA section and the implementation status),
`qa-29-design.md`, the root and music `CLAUDE.md`, and the code it touches: `logic/{NewItems,OwnFiles}.kt`,
`sync/{Sync,Downloads,Inbox,NewNotifier}.kt`, `data/{MusicDb,Store,Snapshot}.kt`, `model/Library.kt`,
`bridge/{BridgeSync,Bridge}.kt`, `playback/PlaybackService.kt`, `app/schemas/` 1-3. On the server side I checked
`music_entries`/`music_files` (`0049_music.sql`), the listing version (`music_sweep.rs:900-907`) and the check budget
(`:63-67`). On the launcher side I checked `reportState`'s handling of `held_files` (`MusicBridgeService.kt:91-111`).
Line numbers are `4655f8d3`'s. No product code was changed: the mutations and probe tests below were temporary and
are reverted, and `git status` was clean before this file was written.

**Verdict: a fix round first.** The rules are small and close to the spec. The Room migration, the mutex and the
one-transaction tray writes are right, and step 4 does what it says. But one of the spec's fresh-install promises
fails in the code:
- **H1:** while the baseline is open, an entry's later listings still put their new keys in Nytt. A phone that is set
  up over days, or sits on the shelf, fills the tray to "30 nye" before anyone has played anything.
- **M1:** three decisions in the Sync/DAO glue can be undone without any test going red. One of them brings back QA
  H2's flood.

Counts: 1 High, 1 Medium, 6 Low.

## What I ran (under the shared build lock, `--no-daemon --max-workers=2`)

- **The full suite on `4655f8d3`, unchanged:** `assembleDebug assembleRelease testDebugUnitTest lintDebug
  -PwarningsAsErrors=true`. Result: BUILD SUCCESSFUL, **607 tests, 0 failures**. Lint reported 0 errors and 40
  warnings, the same as the implementer's count.
- **Mutation runs.** Each mutation was a temporary product-code edit, run against the tests named, then reverted:

  | Mutation | Tests run | Result |
  |---|---|---|
  | D: re-pick guard `if (item !in l.unplayable) continue` removed (`NewItems.kt:196`) | `NewItemsTest` | **survives** (only my probe T2 went red) |
  | I: `newsBefore` marks keys pending in the closing sync (`pending = closed \|\| played`) | `NewItemsTest`, `GuardsTest` | red: `d29 1`, `6`, `20`, `23`, `24`, `25` |
  | H: `newsAfterTx` moved after `pruneNews` (`Sync.kt`) | (same run) | red: `GuardsTest` design 29's wiring |
  | A: `val current = newsListed(...)` moved above the inbox loop | whole suite | **survives** |
  | B: own entries dropped from `applied` | whole suite | **survives** |
  | C: `deleteSeen(id)` and `deleteNewsOf(id)` removed from the reset in `newsBeforeTx` | whole suite | **survives** |
  | E: `push`'s `held` without the record check | whole suite | **survives** |
  | F: `pullFiles` back to "held = skip" | whole suite | **survives** |
  | G: `putOwnFile` writes no record | whole suite | **survives** |
  | K: `keyById` from the usable entries only (no `rawKeys`) | whole suite | red: `d29 27` |

  A, B, C, E, F, G and K ran together in one whole-suite run. The only red test was K's `d29 27`.
- **Probe tests.** Two temporary tests in `NewItemsTest`, written with the spec's own `sync` helper, then deleted:
  - **T1** (H1): red on the unchanged code.
  - **T2** (L1): green on the unchanged code, red under mutation D.

## High

### H1. An open baseline still adds an entry's later keys to Nytt, so setup and shelf time fill the tray before the first play

- **Where.** `entryNews` (`NewItems.kt:117-126`) and `newsAfter` (`:162-205`) only know two cases:
  - a pending mark gives one item;
  - every other entry gets `markNewSeen` plus the tray, unless `settling` is set.

  `settling` is set only by a new entry's own 24 h window (`:175`). `newsAfterTx` (`MusicDb.kt:250-260`) never reads
  `news_baseline`. So "the baseline is open" stops new **entries** from counting. It does not stop new **keys** in an
  entry the phone already knows.
- **Probe T1.** A fresh phone (`played = false` in every sync). Podcast `a` lists 40 episodes and an own entry lists 7
  files. At the next sync they list 100 episodes and 12 files.
  - Result: `p.closed == false`.
  - The tray holds **30** items: `f12, f11, f10, f9, f8, a100 ... a76`.
  - `NewNotifier` counts visible items, and the episodes stream, so that is "30 nye i Musikk" and Home's badge at 30.
    Nobody has played anything.
- **Real paths.**
  - **An own album uploaded in two goes during setup** gives "5 nye". Each multipart `upload_files` request nudges
    the phone once.
  - **Shelf time.** The phone is set up, then waits a week or two before the kid gets it. §1.3 says the baseline then
    stays open "for ever". Every daily podcast's new episodes go into the tray as ordinary news, so the kid's first
    look is "30 nye". The spec gives the reason this shouldn't happen ("a kid who has never played has every entry in
    front of them as new"), but the code doesn't act on it.
  - **A show ticked during setup whose first fill finishes later.** Manifest failures stay `pending` and are retried 10
    per check (`music_sweep.rs:66`), and a budget-cut fill is committed partial (`:63`). The rest of its back
    catalogue then counts as news. QA M5's 24 h window covers only pending entries.
- **What it breaks.**
  - §1.3 "Setup is never news".
  - §1.4 "Fresh install ... every sync until the first play only adds to the baseline".
  - §2's row "Fresh install, 20 entries ticked one by one over a day, never played: nothing".
  - The user's rule that a fresh install never floods.
- **Why the tests miss it.** `d29 18` and `d29 19` list each entry exactly once, so `markNewSeen` never sees a second
  listing.
- **Fix (small).**
  - `newsAfter(..., open: Boolean)`, with `settling = open || <the window>`. While the baseline is open (still open
    after phase A), every listing is only acknowledged.
  - `newsAfterTx` reads `newsBaselineClosed()` inside its transaction and passes `open = it != true`.
  - Add T1 as `d29 29 while the baseline is open a known entry's later keys are acknowledged, not added`. Add a second
    half: after the first play, a new key counts again.
  - Optional, the same shape: give **every** entry a 24 h window from its first listing. That means setting `newAt`
    on its row when `seen == null`. It also covers a setup show whose fill finishes just after the first play, and a
    re-ticked show's trickle.
  - Update §1.3 and §2 to say so.

## Medium

### M1. Three decisions in the Sync/DAO glue are unpinned, and removing one brings QA H2's flood back

QA M4 moved the bookkeeping into the pure `newsBefore`/`newsAfter`, and those are well tested. The code that feeds
them is not. Each mutation below survives all 607 tests:

- **(A) `current` built before the inbox loop** (`Sync.kt:152` moved above `:130`). The snapshot then predates this
  run's listings, with two effects:
  - **A first listing applied this run has no `listingVersions` entry,** so `applied` drops it. Its seen rows and a
    pending entry's item are never written. The next sync doesn't apply it again (no inbox file). So a new show stays
    silent until its listing changes again.
  - **A known entry is judged on its old items,** so every new episode reaches Nytt one listing late.
- **(B) own entries dropped from `applied`** (`Sync.kt:153`). Without them there is no "Nytt spor" and no new
  own-entry item.
- **(C) the reset without `deleteSeen(id)`/`deleteNewsOf(id)`** (`MusicDb.kt:234-240`). A reused id keeps the old
  show's seen set, so the new show's back catalogue fills the tray. That is exactly QA H2.
  - `d29 20` and `d29 21` can't see it: the test helper does its own reset (`NewItemsTest`'s `sync`, `seen -
    before.reset`), so the DAO's copy of the rule is never exercised.

**Fix.** In `GuardsTest`'s design 29 wiring test (`GuardsTest.kt:603`):
- Add `"val current = newsListed(lib, store)"` to the order list, between the inbox loop and `newsAfterTx`.
- Pin the `applied` line verbatim.
- Pin the five deletes of `newsBeforeTx`'s reset loop. Alternatively, move them into one DAO `resetEntry(id)` and pin
  that.

## Low

### L1. The re-pick's guard is untested, and without it a new podcast's item hops to every newer episode

`newsAfter` re-picks only an item that has turned unplayable (`NewItems.kt:196`). Mutation D removes the guard, and
all of `d29 1-28` stay green. Without the guard:
- every sync whose listing changes the entry's first candidate moves the item;
- probe T2: new podcast `d` gives `d3`, and an hour later its listing adds `d4`. The tray then shows `d4`, not `d3`,
  in the old slot (`seq`).

**Fix:** add T2 as a `d29` test ("a podcast's item stays put when a newer episode arrives").

### L2. Step 4's glue has no test

`OwnFilesTest` covers only the pure `ownCopyVerdict`/`keepOwnCopy`. Each of the following survives the suite:
- `push`'s record check removed (E, `BridgeSync.kt:124`): the launcher never fetches a reused id's file;
- `pullFiles` back to skipping every held copy (F, `:86`): the old audio stays;
- `putOwnFile` without its record (G, `Inbox.kt:42`): every copy is hashed at every sync.

**Fix:** three `GuardsTest` pins, beside the existing `Inbox`/bridge pins.

### L3. `push` reports a copy that hasn't been judged yet as not held, so the launcher fetches it again

- **The cause.** `held` keeps only `OwnCopyVerdict.Keep` (`BridgeSync.kt:124`), so a copy without a record (`Hash`)
  is left out. The launcher replaces its handed-over marks with that list and fetches every file that isn't on it
  (`MusicBridgeService.kt:100-109`).
- **When it happens.** The first `pullFiles` after the upgrade records every copy. A report can still go out before
  that:
  - the download job's `Sync.writeReport` races the start sync;
  - a pull fails before `pullFiles` (an exception in `pullListings`/`pullCovers` ends the bridge block).
- **What follows.** The launcher downloads the phone's whole own-files library again (up to 1 GB a file) over the
  phone's connection. The ready copies are deleted only at a later report.
- **A loop.** When `recordOwnSha` keeps failing (it swallows the error, `Store.kt:129-131`; a full disk), the copy is
  hashed on every sync, never reported held, and the launcher keeps a ready copy of it on a disk that is already full.
- **Fix:** `held` excludes only `Refetch` (`!= OwnCopyVerdict.Refetch`). An unjudged copy is reported as before
  step 4, and `pullFiles` judges it at the next sync.

### L4. A Storytel item that turns `no_audio` waits for a sync that nothing triggers

The download job records `no_audio` and goes on to download 222, but only `Sync.run` re-picks. So with `cache -1`,
"1 ny" for 222 waits for the next nudge or the 6 h backstop, though 222 is already on the phone.
- The spec says "the next sync", so this is within the spec.
- But §7 / emulator §6f ("once 111 is `no_audio`, the next sync makes 222 the item") doesn't say what starts that sync.

**Fix:** when the download job records a permanent failure for an item that is in the tray, `Sync.nudge(context)`.
Or the emulator check says to run a sync (the debug feed's broadcast).

### L5. A parent's test play during setup closes the baseline for good (a design question, not a code bug)

§8.4 closes the baseline at the first stored position or bookmark. A parent who ticks a few shows, plays one for 20 s
to check that the bridge works, then ticks the rest gets "1 ny" for each later tick: "15 nye", the shape of QA H1.
§1.3 rejected "the app was opened" for this reason, but a test play is just as common in setup.

**Suggestion:** this is §8's "veto if wrong". Ask the user. Two options:
- a line in the setup notes ("don't play anything until the library is set up");
- count a play only from the second calendar day after the first library.

### L6. Docs

- **emulator §6f "Re-enrolment / restore" (`emulator.md:544-547`).**
  - **No id is reused.** `music_entries.id` is `AUTOINCREMENT` (`0049_music.sql:32`). With "delete two entries,
    create other shows, restore the backup", the new shows take fresh ids, and the restored ones come back under ids
    whose rows had already dropped them (unticked). No reset happens, and the promised `adb logcat -s Sync` line
    never appears.
  - **"Either way no notification" is wrong.** The step's own new shows each give "1 ny" before the restore. On a
    fresh server, re-uploaded own-files entries get new keys and count once each (§1.4).
  - **A sequence that reuses an id:**
    1. Back up the server.
    2. Create show C (it takes id N) and let the phone sync. Expect "1 ny"; play it or clear it.
    3. Stop the server, restore the backup, start it, and create show E at once, before the phone syncs. E takes id N
       again.
    4. Expect "1 ny" (E's newest only) and the reset line naming N.
- **`music/CLAUDE.md:248`.** It says `pruneNews` is "one statement". It is a read and a replace inside one
  `@Transaction`. Say "one statement or one transaction".

## Verified OK

- **Fresh install, `pm clear`, the upgrade from Room 2, unmanaged and managed again, `nothingTicked`.**
  - The baseline and the pending marks behave as §1.3-§1.4 say (`d29 1-7`). The closing sync uses the flag from
    before the sync, so a key added in that sync is baseline (mutation I is caught).
  - An unmanaged app keeps its library (the pull returns early), so being managed again changes nothing.
  - The upgrade has positions, so its first library is the baseline. A used phone with no position rows stays open,
    which is the safe side.
  - (Floods from listings that grow are H1.)
- **A reused id, as the pure rule.**
  - `newsBefore` resets an id whose held key changed (`d29 8`, `20`, `21`).
  - An id a removed entry left behind has already been pruned by the sync that stopped naming it, so not detecting it
    there is safe.
  - The phone pulls listings before phase A, so the reset can drop a listing that was just compared. This is harmless:
    a listing's version is a hash of its body, and the body includes the entry id and items (`music_sweep.rs:900-907`).
    A different show under the same id always has another version and is pulled in the same sync.
- **The item for each source.**
  - Podcast: the newest with a url (`d29 9`).
  - NRK serie: the effective order, episode 1 or the newest (`d29 10`).
  - Storytel: book 1 in reading order whatever `order` says (`d29 11`).
  - Own files: first in play order.
  - The fallbacks: `no_audio` and `missing` without a copy are skipped, and with none left the result is null
    (`d29 12`). An episode's item error never makes it unplayable.
  - The re-pick keeps the slot's `seq` (`d29 17`).
- **The 24 h window.** `now - newAt in 0 until NEW_ENTRY_SETTLE_MS`: at `-1` the keys are acknowledged, at the limit
  they join, and with the clock behind `newAt` today's rule applies (`d29 16`). If the range were made inclusive,
  that test's third assertion would catch it (I checked this by reading the test, not by running it).
- **Concurrency.**
  - `Sync.runLock` is a process-wide `Mutex` around the whole run, and the app has one process (no `android:process`).
  - No path inside the run takes `runLock` again. `Sync.run` has one caller (`SyncWorker`).
  - Lock order: the run takes `reportLock` inside `runLock`. The download job takes only `reportLock`, and the player
    takes neither, so there is no cycle.
  - The waits are suspending: the binder bind and Storytel's 20 s wait are `withTimeoutOrNull`, and Room's suspend
    DAO calls run on IO. A cancelled worker leaves `lock()` cleanly, and Room rolls back a cancelled transaction.
  - Every tray write is a single statement (`deleteNews`, `clearNews`, `deleteNewsOf` inside phase A) or a DAO
    `@Transaction` that reads the tray inside it (`newsAfterTx`, `pruneNews`, `replaceNews`).
  - `startedPlaying` uses `deleteNews(...) > 0`.
  - The deferred inbox delete keeps a file the debug receiver rewrote mid-run. A crash between applying a listing and
    phase B applies it again, and the seen rows and the used mark make that idempotent.
- **Room.**
  - `2.json` → `3.json` differs only in the two new tables, the version and the identity hash.
  - `1.json` → `2.json` → `3.json` chain: a phone on version 1 runs `ReapplyListings`, then the new tables.
  - The keys: `known_entries` has `entryKey TEXT` PK, a nullable `entryId`, `pending NOT NULL`, nullable `item` and
    `newAt`. `news_baseline` has an `id` PK. No index is needed: one is read whole, the other is one row.
  - `newsBaselineClosed(): Boolean?` gives null with no row (open).
- **Step 4.**
  - The record is written with `writeAtomic` (temp file, then rename) after the copy's rename. A crash in between
    leaves a copy without a record, which is hashed once.
  - The name is `"$fileId.sha256"` from a `Long`, so there is no path traversal. `ownCopies()` ignores it, and
    `deleteOwnCopy` removes both.
  - The hash-once pass runs inside `Sync.run` on `Dispatchers.IO` (`withBridge` runs its block in the caller's
    context), never on the main thread. It costs a one-time read of the own files, at most 1 GB each. That is seconds
    on the phone's storage, far below WorkManager's 10 minutes, and it holds only `runLock`.
  - A mismatch deletes a copy that may be playing. The open file descriptor stays valid, so a seek or a Sonos range
    request can fail. But by then the copy is another file's audio under the new title, so dropping it is right.
  - A held copy is not deleted unless its record or its hash differs from the library's. The library's SHA-256 is the
    same string `Inbox` checked against on arrival, so a right copy can't mismatch.
  - Two leftovers are never pruned: a crash between the two deletes leaves an orphan `<id>.sha256`, and a crashed
    `writeAtomic` leaves `.tmp` files. Both are a few bytes, and both count in the report's `ownBytes`. Not worth a
    finding.
- **`GuardsTest`'s new pin.** It really pins the order: indices of five unique strings, all present, and sorted. Moving
  phase B after the prune turns it red (mutation H). It also pins `runLock`, `deleteNews` in the player and the
  download job's per-item update. The old pin's replacement (the `listedOf` line) is exact.
- **Docs.**
  - `music/CLAUDE.md`'s bullet and Store line, design 21 §4.5's line, `PLAN.md` and the spec's implementation status
    match the code. The status says 607 tests, which I reproduced.
  - emulator §6f: the `--books`/`--locked` options exist in `scripts/fake-storytel.py`, and the `run-as ... sh -c 'cp
    no_backup/own/...'` command works from `run-as`'s data directory.
  - The step-4 check's note is right: swapping only the bytes isn't caught, so the record has to be deleted.
  - The exceptions are L6.

## Fixes (implementer, 2026-10-13, on `design-29-impl` after `ac29cf23`)

Every finding fixed in one commit. After it: `assembleDebug assembleRelease testDebugUnitTest lintDebug
-PwarningsAsErrors=true` green, **614 tests, 0 failures**, lint 0 errors and the same 40 older warnings.

| Finding | Fix | Test that goes red without it |
|---|---|---|
| H1 open baseline still adds keys | `newsAfter(..., open)`: `settling = open \|\| <the window>`. `newsAfterTx` reads `newsBaselineClosed()` after phase A, inside its transaction. Spec §1.3 and §2 updated. The optional per-entry window was not taken | `d29 29` (probe T1, plus "after the first play a new key counts again") |
| M1 A `current` before the loop | `GuardsTest`'s order list now holds `val current = newsListed(lib, store)` and the `applied` line, between the loop and phase B, and `newsListed` is called once | design 29's wiring (mutation A: red) |
| M1 B own entries dropped | pure `newsApplied(appliedIds, entries, current)`, and Sync's call to it pinned | `d29 31` (B in the rule), the wiring pin (B2 at the call) |
| M1 C reset without seen/tray | the reset is one DAO `@Transaction resetEntry(id)`, and its five deletes are pinned verbatim | design 29's wiring (mutation C: red) |
| L1 re-pick guard untested | probe T2 as a test | `d29 30` (mutation D: red) |
| L2 step 4's glue | pure `heldOwnFiles` and `ownFetchPlan` (`OwnFilesTest`). `GuardsTest` pins the glue: `putOwnFile`'s record, `pullFiles` through the plan, `push` through `heldOwnFiles`, and the prune's `deleteOwnCopy` | E: OwnFilesTest "the report holds..."; E2, F2, G: the new `GuardsTest` "own-file copies"; F: OwnFilesTest "pullFiles fetches..." |
| L3 unjudged copy not held | `heldOwnFiles` leaves out only `Refetch`; an unjudged copy (`Hash`) is held | OwnFilesTest "the report holds a kept or unjudged copy" |
| L4 re-pick waits for the backstop | `ItemErrors.record` calls `Sync.nudge` when `repickWanted` (a permanent failure of a tray item) | `d29 32`, plus the `GuardsTest` pin of the call |
| L5 test play closes the baseline | kept (the user's decision). Spec §8.4 and emulator §6f say to tick everything first, then test-play | - (documented) |
| L6 docs | §6f's restore check is now QA's sequence that reuses an id, plus a separate fresh-server line. `music/CLAUDE.md` says "one statement or one transaction" and mentions the open baseline and `repickWanted` | - |

Mutation re-run, each on its own, against `NewItemsTest`, `OwnFilesTest` and `GuardsTest`, and reverted afterwards:
- A: red.
- B, B2 (B at Sync's call): red.
- C: red.
- D: red.
- E, E2 (E in `push`'s glue): red.
- F, F2 (`pullFiles` judging every copy as kept): red.
- G: red.
