# QA 29 design review - a new entry is news too (2026-10-13)

Scope: `docs/design/29-music-news-new-entries.md` at `fe36b626` (branch `design-29`), read against the root and music
`CLAUDE.md`, design 21 §4.5, 21b (§2.3 the sweep's budget, `pending` items) and 26b (§3.3 the download order), and checked
against the code it builds on: `music/.../logic/{NewItems,PlayOrder,Storytel,Bookmarks}.kt`, `sync/{Sync,Inbox,
NewNotifier,Downloads,DownloadPlan}.kt`, `data/{MusicDb,Snapshot}.kt`, `model/Library.kt`, `bridge/{BridgeSync,
BridgeConfig}.kt`, `playback/PlaybackService.kt`, `ui/{UiModel,MusicViewModel}.kt`, `app/schemas/`; the server's
`state_key`/`new_own_key` and `library_for` (`server/src/music.rs`), `music_entries` (`migrations/0049_music.sql`),
`set_device_entry` and `upload_files` (`handlers/music.rs`), `build_listing` and the walks (`music_sweep.rs`),
`testdata/music_library.json`; the launcher's badge (`badges/BadgeCounts.kt`). Line numbers are `fe36b626`'s. QA wrote
no product code.

**Verdict: buildable after a fix round.** The shape is right and small: a grow-only set of entry keys with a baseline,
one item per new entry chosen by the play order, no new visibility rule, the vibb port untouched. The server does send
a stable per-entry key, the launcher's badge counts `setNumber`, and an auto migration 2 -> 3 is the right tool. But
two of the spec's "never a flood" claims fail:
- **H1:** a phone whose music app is installed before the parent ticks takes its baseline from the first sync, and the
  PWA saves one tick at a time with a nudge each. Setting up a new phone floods.
- **H2:** `seen` and the tray are keyed by entry id. Ids come back after a restore and start again on a new server.
  The key set doesn't fix that, and test `d29 11` writes the flood down as the rule.

The Mediums are a wrong item for a reversed Storytel series (M1), a notification that waits for the whole download
job (M2), the tray's read-modify-write races (M3), tests that miss the wiring where floods come from (M4), and the
back catalogue that trickles in after the first listing (M5). Counts: 2 High, 5 Medium, 5 Low.

## High

1. **H1: the baseline is taken mid-setup, so a freshly set-up phone floods** (§1.3, §1.4 "`nothingTicked`", §8.4,
   §7 "Nothing ticked").
   - **Evidence.** A tick in the PWA is "one auto-saving tick" per entry, and each one nudges the phone
     (`server/src/handlers/music.rs:3637-3700`: `set_device_entry`, `nudge(&state, &[id])` at `:3699`). On the
     phone a nudge is a sync after a 15 s settle (`Sync.kt:60`, `:71-82`). The launcher installs the music app from
     its catalog; nothing stops the parent from enabling the app first and ticking afterwards.
   - **Failure.** The parent enables the music app on a new phone (nothing ticked, `EMPTY_LIBRARY`, no baseline), then
     ticks 20 entries over two minutes. The first sync sees 1-3 of them, and that is the baseline (§1.3). Every later
     sync adds the next ticks as pending, so a phone that was never used gets "17 nye i Musikk" and 17 NY badges. That
     is the flood the user ruled out ("A fresh install must never flood"). The same happens when entries are added
     over the first days, before the kid has the phone. §8.4 assumes "the first ticks" arrive together, but they come
     one request at a time.
   - **Fix.** The baseline stays open while the phone has **never played anything**: no row in `positions` and none
     in `bookmarks`. Both are wiped with `known_entries` and kept across an upgrade, and every play writes them (the
     15 s save). `knownAfter(known, named, open)`: while `open`, every named key is added as known and not pending.
     - Fresh install, `pm clear` and setup: no news until the kid has listened once.
     - Upgrade: positions exist, so the first library is the baseline, as now.
     - "Nothing ticked, then ticks" on a used phone: unchanged.

     State it in §1.3 and §8.4 (it replaces §8.4's narrower rule). Tests:
     - `d29 16 ticks that arrive over several syncs before the first play are all baseline`;
     - `d29 17 after the first play a new key is pending`.

     Device check: in §7's "Fresh install", tick the entries one at a time after installing.

2. **H2: `seen` and the tray hang off the entry id, so a reused id floods** (§1.3 "Keys, not ids", §1.4 "even across a
   rebuilt server with the same shows", test `d29 11`).
   - **Evidence.** `seen` and `news` are keyed by `entryId` (`MusicDb.kt:44-48`). `markSeen` reads the seen set by id
     (`Sync.kt:191`). The prune drops an id's seen rows only when the library no longer names that **id**
     (`Sync.kt:135-140`). Ids come back after a database restore (`qa-21-step1-code.md:112-121`; DEPLOY.md
     "Restoring") and start again at 1 on a new server: re-enrolling with a new code is the documented recovery path
     (design 22 "Re-enrolling", without a factory reset).
   - **Failure.** Before: id 7 = podcast A, whose 80 keys are in `seen`. After a re-enrolment to a rebuilt server,
     id 7 = podcast B (a known key or a new one). B's first listing meets A's seen set, so every one of B's eligible
     keys is fresh, and the tray fills to `NEWS_MAX` with B's back catalogue: "30 nye". A pending B gets exactly this
     from `d29 11` ("a pending entry that already has a seen set gets today's rule"). The only way a pending key can
     have a seen set is an id that belonged to another entry, so that test pins the flood. Stale tray items under a
     reused id can also turn visible: own-file keys are `f<file id>` and file ids restart too.

     This predates design 29. But §1.3-§1.4 claim the key set makes a server switch and a restore safe, and the
     orchestrator's list names both.
   - **Fix.** Record the id with the key: `KnownEntryRow(entryKey, pending, entryId)`.
     - At each parsed library, before the inbox loop, an id whose recorded key differs from the library's key for that
       id loses its per-id state as if its entry were removed: its `seen` rows, its tray items and its item errors.
       Then update the row's id.
     - The listing and items need nothing: the new server's version differs, so they are pulled and re-applied, and
       download rows whose item keys aren't listed are pruned by the plan.
     - One pure function `reassignedIds(rows, lib): Set<Long>` in `NewItems.kt`.
     - `d29 11` becomes "a pending entry whose id carried another key's seen set is acknowledged and gives one item"
       (so after the fix it is unreachable).
     - Add `d29 18 a server switch with the same shows under new ids adds nothing`.

## Medium

1. **M1: a Storytel series set to `newest_first` gets an item the phone may never download** (§1.1 "Storytel series:
   book 1 in reading order. That is also the book the download plan fetches first", test `d29 6` "`order =
   newest_first` reverses").
   - **Evidence.** The parent can set the order of a Storytel entry (`handlers/music.rs:1854-1858`: only own entries
     keep theirs). `newEntryItem` takes the first item in play order (`playOrder` reverses, `PlayOrder.kt:23-30`).
     The keep set is the first `cache` books in **reading order**, and the downloads go in reading order whatever
     the order setting (`logic/Storytel.kt:55-77`, `sync/DownloadPlan.kt:56-62`).
   - **Failure.**
     - With `cache` 3 of 10, the item is book 10, which is never kept and never downloaded. It is never visible, so
       the new series never notifies.
     - With `cache` -1 it is downloaded last, perhaps days later.

     §1.1's sentence holds only for the natural order.
   - **Fix.** For Storytel the item is the first book in reading order (the listing's first, the first one the plan
     fetches) whatever `order` is. Change `d29 6`'s Storytel half to "`newest_first` still gives book 1". The
     cover-tap/Nytt agreement then holds for the natural order only; say so.

2. **M2: a downloaded book counts only when the whole download job ends** (§1.2 "the download job's
   `NewNotifier.update` posts it", §2's table "once 111 is downloaded, not before", §7 Storytel step 4).
   - **Evidence.** `DownloadWorker.doWork` calls `NewNotifier.update` only on the network-wait exit and after the
     whole loop (`Downloads.kt:151`, `:163`). Each book goes through `run.fetchOne(w)` (`:155`) with no update after
     it.
   - **Failure.** A new series with `cache` -1 (the fixture's entry 5, `music_library.json:81`) downloads book 1 and
     then every other book (several hundred MB each) before "1 ny" appears. WorkManager stops a job after 10 min,
     and a cancelled worker never reaches `:163`, so the notification waits for the next sync (a nudge, or the 6 h
     backstop). §7's check can't see this: 222 and 333 are 5 MB, so the job ends seconds after 111.
   - **Fix.** After each `fetchOne` that leaves a done copy of an item in the tray, call `NewNotifier.update` (one
     snapshot read; it returns early when the count is unchanged). In §7 step 4 use `--books
     111:300,222:300,333:300` and expect the notification while 222 is still downloading.

3. **M3: the tray's read-modify-write races the player, "Fjern alle" and a second sync** (§4 `applySeen`; the
   orchestrator's "races between the sync and playback", "transaction boundaries").
   - **Evidence.** §4's `applySeen(..., news: List<NewsRow>?, ...)` gets a tray computed from a read made **outside**
     the transaction, as `markSeen` does today (`Sync.kt:196-197`). Three other writers can run at the same time:
     - `startedPlaying` reads the tray, filters it and replaces it (`PlaybackService.kt:1183-1193`, on the service's
       writer, not the sync's thread);
     - "Fjern alle" clears it (`MusicViewModel.kt:91-95`);
     - the 6 h backstop is a second unique work running the same `SyncWorker` (`Sync.kt:87-91`). Nothing serialises
       it with a nudged `music-sync`: `reportLock` covers only the report file.
   - **Failure.**
     - The kid taps the new entry's Nytt row while the sync is applying another listing: the item comes back, and the
       notification says "2 nye" for something just played.
     - "Fjern alle" during a sync is undone.
     - Two syncs at once: one run's tray write drops the other's new item, so a new entry is missed.
   - **Fix.**
     - `applySeen` takes the fresh keys, reads `news()` and runs `addToTray` **inside** its `@Transaction`.
     - `startedPlaying` uses the existing single-statement `deleteNews(entryId, key)` (`MusicDb.kt:168`) instead of a
       read and a replace.
     - A process-wide `Mutex` around `Sync.run`'s body: both works run in the app's process.
     - §4's rationale is half the story. Clearing `pending` without the seen rows would **miss** the item at the next
       listing (seen null, not pending). Say both directions.

4. **M4: the tests miss the wiring where floods and misses come from** (§3 `d29 13`, `d29 15`; §4 "`Sync.kt` `run`").
   - **Evidence.** All the decisions that matter at run level live only in `Sync.kt`, which has no JVM test:
     - the baseline taken **before** the inbox loop;
     - `pending = rows' pending keys + added`;
     - a used-up key leaving the run's set;
     - the own-entries loop passing `pending`.

     `d29 13` passes `pending = false` by hand, so it passes even if `Sync` computed `pending` as "every key not
     known before this run" (which floods at the baseline sync). `d29 12` exercises only the existing
     `addToTray`/`playedFromTray`/`pruneTray`, and `d29 15` is a text pin.
   - **Fix.** Move the run's bookkeeping into `NewItems.kt`:
     - `knownAfter(rows: Map<String, Boolean>?, named, open)` returns the rows to insert **and** the run's pending
       set (with H1's `open` and H2's ids);
     - one pure `newsRun(...)` chains it with `entryNews` and `addToTray` over a list of listings.

     `Sync` only reads and writes rows. Run-level tests over it, each over several syncs:
     - fresh install with listings over three syncs;
     - setup ticks one at a time (H1);
     - same shows under new ids (H2);
     - nothing ticked, then a re-tick plus one new show;
     - a pending entry whose first listing fails (`bad_listing`) and arrives at the next sync.

5. **M5: the back catalogue still trickles in after a new entry's first listing** (§1.1 "never its back catalogue",
   §1.5 "Later items of a once-new entry are ordinary news", §2's table "1 ny").
   - **Evidence.**
     - **NRK/RSS:** `build_listing` leaves out `pending` items (`music_sweep.rs:826-829`). An episode whose manifest
       failed stays `pending` and is retried at most 10 a check (21b lines 207-209). A first fill cut by the check's
       budget (250 requests or 10 min) is published partial (`music_sweep.rs:3149-3156`, 21b §2.3).
     - **Own files:** each upload is its own request (`upload_files`), and a parent who uploads an album in two
       goes, or a tick or nudge that lands between them, splits it over two libraries.
   - **Failure.** A new 100-episode podcast whose fill had 6 transient manifest failures shows "1 ny", then "7 nye"
     when the 6 old episodes resolve. A 12-track album uploaded as 7 + 5 gives "6 nye". These are back-catalogue
     items, which the user's decision excludes. The vibb port already does this to a known entry's first listing,
     but §2 promises "1 ny".
   - **Fix (the orchestrator's choice).**
     - **(a)** A short settling window: within 24 h of a new entry's item joining the tray (`known_entries.newAt`),
       that entry's further fresh keys are acknowledged, not added. The entry still shows NY through its one item. A
       genuinely new episode in those 24 h is the cost.
     - **(b)** Accept it, and §2's table and §1.5 say "a first fill the server finishes later, or files uploaded in
       several goes, count as ordinary news".

     (a) is about 5 lines in `entryNews` plus one column. Either way, add a test.

## Low

1. **L1: "episode 1" is not always the NRK serie's item** (§1.1, §2's table, §8.1, §7 "A new NRK series"). The
   library's `order` is the server's **effective** order: `auto` on a serie whose kept end is `newest` becomes
   `newest_first` (`music.rs:800-806`). That is a capped serie or the RSS fallback (`desired_end`,
   `music_sweep.rs:435-447`; `:1818`). Then the rule (first in play order, which is right) gives the newest episode.
   **Fix:** "episode 1, or the newest when the server keeps a long serie's newest end". Use a short serie in §7.

2. **L2: "two entries with one key give one item"** (§4) can't happen: `music_entries.key` is `NOT NULL UNIQUE`
   (`0049_music.sql:37`) and immutable (no `UPDATE` sets it). Keep the behaviour as a free consequence of the per-run
   pending set, but drop the sentence or mark it defensive. No test for it.

3. **L3: two consequences §1.4 doesn't state.**
   - A key that was pending and removed **before its first listing**, then ticked again, still gives "1 ny" (its mark
     was never used). That is right (the kid never had it), but §1.4's "Removed and re-added (same key): not new"
     reads as if it never happens.
   - On a new server, own-files entries uploaded again get new random keys (`new_own_key`, `music.rs:299-301`). Each
     counts once, and their positions are gone.

   Say both in §1.4.

4. **L4: a new entry whose chosen item never plays never shows as new.** The cases are a series whose book 1 is
   `no_audio`, or an own entry whose first file is `missing` on the server and never copied. §1.2 accepts this for a
   book, but for a new entry it means the whole entry never gets NY or a notification, while books 2..N play. Accept
   it and say so in §1.2, or choose the item among the eligible ones not marked permanently failed. Accepting is
   fine.

5. **L5: §7 device checks.** "Fresh install" should tick one at a time (H1). The Storytel check needs large books
   (M2). Add a restore or re-enrolment check (H2): note the ids, restore an older server backup or point the phone at
   a fresh server with the same shows, and expect no notification. "A new own-files entry: Upload 2 more and expect
   '3 nye'" holds only after the first item is unplayed and M5 (a) is not taken. Make it conditional on M5.

## Checked and fine

- **The entry key.** `library_for` sends `key: e.key` for every entry (`music.rs:888`). `key` is vibb's `state_key`:
  the NRK podkast slug, else `sha1(target)[:12]` (`music.rs:281-295`). An own-files entry's key is
  `own-<12 random hex>` (`:299-301`). The column is `NOT NULL UNIQUE`, set at insert, never updated, and `target` is
  `UNIQUE`. A re-created show gets the same key, and a Storytel series and a book of it have different ones. The
  fixture has one key per source (`testdata/music_library.json`: `abels_taarn`, `40201d4c16fc`, `own-5c0ffee1d0e5`,
  `7ac843e9f5d4`).
- **The §0 claims about today.**
  - `markNewSeen` is as described, an empty listing is a no-op, and `addToTray` gives the next `seq`, newest first,
    capped at 30 (`NewItems.kt:36-50`).
  - `newsEligible` is a url or any Storytel book (`:21-22`). `applyListing` passes it (`Sync.kt:184`). Own entries
    pass all their files' keys on every sync (`Sync.kt:129-131`), from the library, not the copies, so an own
    entry's item is chosen before its files are copied and shows once they are.
  - The prune drops seen rows and tray items of ids the library no longer names, by `entryIds` (raw ids, so an
    unreadable entry keeps them) (`Sync.kt:135-140`).
  - `NewNotifier.count` counts visible tray items on `forRenderer(false)` (`NewNotifier.kt:35-42`). NY counts
    visible ones (`UiModel.kt:53`). The launcher's badge sums `number` (`BadgeCounts.kt:30`).
- **The order inside a sync.** `BridgeSync.pull` writes the library, then pulls the listings the **new** library names
  (`BridgeSync.kt:31-37`, `:52-58`). So a new entry's first listing can arrive in the same sync as its key, and
  §4's "before the inbox loop" is the right place. A `bad_listing` or a crash before `file.delete()` leaves the version
  unapplied or the inbox file in place, so it is re-pulled or re-applied with the pending mark persisted.
- **The baseline on fresh install, `pm clear`, reinstall and upgrade.** `allowBackup="false"` (`AndroidManifest.xml:29`).
  The library file and Room are wiped together, and there's no destructive fallback in `MusicDb.get`. On upgrade the
  new table is empty and the first sync takes the baseline. `EMPTY_LIBRARY` names nothing, so it neither takes nor
  moves it (but see H1).
- **Room.** An `AutoMigration(from = 2, to = 3)` without a spec is right for a new table with no data: there is
  nothing to back-fill, and an empty set is the "no baseline" state. `exportSchema = true` (`MusicDb.kt:261`),
  `room.schemaLocation` is set (`app/build.gradle.kts:114-115`), and `1.json` and `2.json` are committed, so a phone
  still on version 1 chains 1 -> 2 (re-applying listings, whose seen sets exist, so nothing is new) -> 3.
- **Storytel's download order without a bookmark**: book 1 first (`storytelWanted`, `logic/Storytel.kt:74-77`), and
  nothing counts before its copy, since `visible()` on the phone needs it (`Snapshot.kt:180-187`). That holds for the
  natural order; see M1 for the reversed one.
- **A cover tap with no bookmark starts the first item in play order** (`rotateToBookmark`, `Bookmarks.kt:43`), so the
  cover tap and the Nytt row agree for NRK, RSS and own entries.
- **Not over-engineered.** It is three pure functions, one table and one DAO transaction. The persisted `pending`
  flag is needed: it is what tells a pending entry from a known entry whose seen rows went with a removal.

## Outside this design (for a separate fix)

- After a server restore that reuses own-file ids, the phone keeps a copy under the old file's id.
  `pullFiles` skips any id it holds (`BridgeSync.kt:77`), and the prune keeps copies whose id the library still names
  (`Sync.kt:144`). So a reused file id plays the **old** audio and the right file is never fetched. Comparing the
  copy's recorded SHA-256 with the library's would catch it. This is the same id-reuse root as H2.
