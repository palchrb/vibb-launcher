# 29 - Vibb music: a new entry is news too (buildable spec)

Architect, 2026-10-13. Revised the same day after the design QA (`docs/design/qa-29-design.md`; what changed is under
"Design QA" at the end).

Sources:
- design 21 §4.5 (NY, Nytt, the notification), 21b §2.3 and §6, 26b §3.2-§3.4 (on the phone a Storytel book is visible
  once downloaded);
- the code on `main` (`3292cd32`):
  - `music/.../logic/NewItems.kt`, `logic/PlayOrder.kt`, `logic/Offline.kt`, `logic/Storytel.kt`;
  - `sync/Sync.kt` (`applyListing`, `markSeen`, the prune), `sync/NewNotifier.kt`, `sync/Downloads.kt`;
  - `data/Snapshot.kt` (`visible`, `forRenderer`), `data/MusicDb.kt` (Room 2), `data/Store.kt`;
  - `model/Library.kt`, `bridge/BridgeSync.kt` and `BridgeConfig.kt` (`nothingTicked`, `EMPTY_LIBRARY`);
  - `playback/LocalRenderer.kt` (`writePlace`), `playback/PlaybackService.kt` (`startedPlaying`);
- on the server: `state_key` and `new_own_key` (`server/src/music.rs`), `music_entries.key` (`NOT NULL UNIQUE`,
  `migrations/0049_music.sql`), `set_device_entry` (one tick, one nudge) and `build_listing`.

**The user's decision (final, 2026-10-13):** "N nye i Musikk", the NY badge and the Nytt tray also count something the
parent **adds** to the phone's library: a new podcast or NRK series, a Storytel book or series, an own-files entry,
anything. Today only new items in an entry the phone already knows count.

## 0. Today, and the change

**Today:**
- `markNewSeen(seen, listed)` (a port of vibb `pi/vibb/library.py` `_mark_new_seen`) acknowledges every key of an
  entry's first listing (`seen == null`). Later listings put their unseen keys in the tray (`addToTray`: newest first,
  `NEWS_MAX` 30).
- `Sync.applyListing` passes `newsEligible` (a url, or any Storytel book). Own entries pass their files' keys on every
  sync.
- When the library no longer names an entry, its `seen` rows and tray items go (`pruneTray`).
- So a new show or book never reaches the tray.

**What counts:** the notification, the bell and NY count only **visible** tray items (`NewNotifier.count`, `UiModel`).
The notifier reads the phone's view (`forRenderer(false)`): a Storytel book counts once downloaded and an own file once
copied. The launcher's badge counts `setNumber`.

**The change:** an entry the phone has **never known**, added after the baseline, puts **one** item in the tray. For
24 h after that item joins the tray, more items of that entry are acknowledged, not added. After that, every rule is
as today.

**This deviates from vibb.** On the Pi a new show's first listing is acknowledged like any other. The port
(`markNewSeen`, `NewItemsTest` 1-8) stays exactly as it is, and the new rules sit beside it. `NewItems.kt`'s header
says so: "Design 29's functions (`newsBefore`, `newsAfter`, `entryNews`, `newEntryItem`) are not in vibb: a new entry
contributes one item." `NOTICE.md` needs no change: it names what is ported, and this part is ours.

## 1. Decisions

### 1.1 One item per new entry: the first item that can play

A new 38-episode podcast is "1 ny".

**The candidates** are the entry's eligible items, minus those known not to play:
- eligible: `newsEligible` for a listing, and every file of an own entry;
- not playing: a Storytel book with a `no_audio` item error (`storytelPermanentFailure`), and an own file the library
  marks `missing` that has no copy on the phone.

**The item, per source:**
- **NRK podkast / RSS:** the first candidate in **play order** (`playOrder`, the library's effective `order`). That is
  the newest episode with a url, and a withdrawn newest episode is skipped.
- **NRK serie:** episode 1, or the newest episode when the server keeps a long serie's newest end. The server then
  sends `order: newest_first` (`music.rs:800-806`), and play order follows it.
- **Own files:** the first file in play order.
- **Storytel, series or book:** the first candidate in **reading order** (the listing's order), whatever `order` says.
  That book is in the keep set and is the first the plan downloads without a bookmark (`storytelKeepSet`,
  `storytelWanted`). With a reversed order, play order's first book (book 10 of 10 with `cache` 3) would never be
  downloaded and so would never show.

**What a tap plays:** for the natural orders this item is the one a cover tap starts with no bookmark, and playing it
by any path clears it (`playedFromTray`). For a Storytel series set to `newest_first`, the Nytt row plays book 1 and
the cover tap starts the newest book.

**If the chosen item stops being able to play** (Storytel answers `no_audio` at its download), the next sync puts the
next candidate in its place: the same tray slot and `seq`, with no new notification of its own.
- If no candidate is left, the item leaves the tray. The entry then shows no NY and gives no notification; nothing in
  it can play on the phone anyway.
- An entry with no candidate at its first listing (everything withdrawn, `missing` or `no_audio`) keeps its pending
  mark, and its first listing with a candidate gives the item.

### 1.2 It shows when it can play: no new visibility rule

The tray holds the item from its first listing on. The notification, the bell and NY show it once it is visible on the
phone, exactly as for a new episode today:
- an NRK or RSS item at once, because it streams (offline it says "Ikke lastet ned - trenger Wi-Fi");
- a Storytel book once downloaded;
- an own file once copied.

The download job updates the notification **after each finished item that is in the tray**, not only when the job
ends. A series with `cache` -1 would otherwise wait for every book, and a job stopped at WorkManager's 10 minutes would
wait for the next sync.

### 1.3 "The parent added it" = a key the phone never knew, after the baseline closed

**The known set** is persisted, **grow-only** and holds entry **keys** (vibb's `state_key`). It lives in Room next to
`seen` and `news`, so it is wiped with them.
- Keys are `NOT NULL UNIQUE`, set when the entry is created and never changed.
- A re-created show has the same key, because the key is the slug or the hash of its target.
- An own entry has a random `own-` key.
- The kid's positions hang off the key too.
- Every row also records the **entry id** the library last gave that key (§1.4, "Reused ids").

**The baseline is open until the phone has played something.** Concretely, until a position or a bookmark has ever
been stored: every renderer's save goes through `writePlace`, and both rows survive an upgrade. While the baseline is
open, every key the library names becomes known, and none is pending. Once a play is stored, the baseline closes for
good. That is a sticky flag in Room, because `clearState` and the bookmark delete could later empty both tables.
After it closes, each parsed library marks its unknown keys **pending**.

Why it is safe and leaves no hole:
- **Setup is never news.** The PWA saves one tick at a time and nudges the phone each time, so a phone set up over
  minutes or days sees its library arrive in many syncs, all absorbed (QA H1).
- **A phone where the kid never plays keeps the baseline open for ever.** Nothing is lost: "N nye" exists to bring the
  kid back to the app, and a kid who has never played has every entry in front of them as new on their first visit
  anyway. From the first stored play on, every entry the parent adds counts.
- **The only gap is an entry added between the kid's first look and first play.** It isn't news, which matches what
  the kid sees: the whole library is new to them.
- **Considered and rejected: "the kid opened the app".** The parent often opens it while setting up, so it would close
  the baseline mid-setup.

**A pending key** gives its one item at its first listing that has a candidate (§1.1). It stays pending until then:
for example a new NRK entry the server hasn't listed yet, an own entry created before its files are uploaded, or a
listing that failed (`bad_listing`) and arrives at the next sync.

**The keys come from every entry the document names, usable or not** (`Library.keyById`, alongside `entryIds`). An
entry an older app can't read (a future source) is known like any other key, so the app update that makes it readable
can't flood.

### 1.4 Never a flood

- **Fresh install, cleared app data, reinstall** (`allowBackup="false"`): Room is empty and nothing has been played, so
  every sync until the first play only adds to the baseline.
- **Upgrade to this version:** positions exist and the new tables are empty. The first sync closes the baseline and
  takes it from that sync's library.
- **Unmanaged, then managed again, or re-enrolment:** keys are stable, so only genuinely new shows count.
- **`nothingTicked` / `EMPTY_LIBRARY`:** a library that names no entry changes nothing. Ticking again after "nothing
  ticked" adds only keys the phone never knew.
- **Removed and re-added (the same key):** not new. The known set only grows. An untick and re-tick in the PWA, by
  mistake or not, must not notify, and the kid's positions come back with the key. The entry's `seen` rows went at
  the removal, so its first listing acknowledges everything, as today. Two exceptions:
  - A key that was **pending and removed before its first listing** still gives its item when ticked again. Its mark
    was never used, and the kid never had the show.
  - On a **new server**, own-files entries uploaded again get new random keys. Each counts once, and their positions
    are gone.
- **Reused ids (QA H2).** `seen`, `news`, `items`, `listings` and `item_errors` are keyed by entry id. Ids come back
  after a server database restore and start again at 1 on a new server.
  - At every parsed library, **before the listings are applied**, an id the library now gives a different key from the
    one recorded for it is **reset**. That is the same as removing the entry: its listing, items, seen rows, tray items
    and item errors go.
  - Its download rows stay. Copies of items the new listing doesn't name are pruned by the plan once that listing is
    applied (`prunable`; an entry without a parsed listing keeps what it has).
  - The row of the key that now has that id records it, and any other row holding the id drops it.
  - So a reused id starts from `seen == null`. A show the phone knew under another id gives nothing, and a new show
    gives one item.
  - Ids recorded before this version don't exist, so the first sync after the upgrade can't detect a reuse that
    happened earlier. The next one can.
- **A first fill that trickles in, or files uploaded in several goes (QA M5):** a new entry's further fresh keys within
  `NEW_ENTRY_SETTLE_MS` (24 h) of its item joining the tray are acknowledged, not added. Examples are back-catalogue
  episodes whose manifest lookup failed (the server publishes them later) and a second upload batch. The entry still
  shows NY through its one item. The cost is a genuinely new episode in those 24 h. From 24 h on, fresh keys count
  as today.

### 1.5 The existing rules stay

`markNewSeen`, `addToTray` (the item gets the next `seq`, so it is newest), `playedFromTray`, `pruneTray`, `NEWS_MAX`,
"Fjern alle", the swipe-to-hide count rule and the notification's look are unchanged.

### 1.6 The tray's writers are serialised (QA M3)

Nobody reads the tray, changes it and writes it back across a gap any more:
- The sync's tray write reads `news()` **inside** its `@Transaction`.
- `PlaybackService.startedPlaying` uses the existing single-statement `deleteNews(entryId, key)` instead of a read
  and a `replaceNews`.
- "Fjern alle" is one `clearNews()` already.
- A process-wide `Mutex` (`Sync.runLock`) holds `Sync.run`'s whole body.

The mutex matters because `music-sync` (the nudge, the start) and `music-backstop` (the 6 h `PeriodicWorkRequest`)
are different unique works running the same `SyncWorker`. WorkManager can therefore run both at once in the app's one
process, and today only the report file is locked (`reportLock`).

The seen rows, the tray, the pending mark and the chosen item move in one transaction. Without that, a crash would
cause one of two faults:
- **A mark that fires later:** the seen rows are written but the mark isn't cleared, and a re-tick after a removal
  fires it.
- **A missed item:** the mark is cleared but the seen rows aren't written, so the next listing finds `seen == null`
  with no mark.

### 1.7 No server or launcher change

The library already names every entry with its id and key. The launcher's badge already counts `setNumber`. The
contract snapshots are untouched.

## 2. Behaviour (what the kid sees; the phone has been played on unless noted)

| Case | Today | Design 29 |
|---|---|---|
| Parent ticks a new 38-episode podcast | nothing | "1 ny i Musikk" (the newest episode) after the sync that applies its listing |
| Its first fill finishes 6 failed episodes the next hour | 6 new | nothing more (the 24 h window) |
| A new episode of it 3 days later | 1 new | 1 new |
| New short NRK serie (natural order) | nothing | "1 ny" for episode 1 |
| New Storytel series, books 111, 222, 333 (`cache` -1) | nothing | "1 ny" as soon as 111 is downloaded, while 222 downloads |
| Same, Storytel refuses 111 (`no_audio`) | nothing | the item becomes 222 at the next sync and shows once 222 is downloaded |
| New own-files entry, 7 files, then 5 more an hour later | nothing, then 5 | "1 ny" (the first file) once copied, nothing more |
| New episode in a known podcast | 1 new | 1 new (unchanged) |
| Fresh install, 20 entries ticked one by one over a day, never played | nothing | nothing (baseline open) |
| Then the kid plays, and the parent ticks a new show | nothing | "1 ny" |
| Upgrade to this version | nothing | nothing (baseline from the first library) |
| Nothing ticked, then 3 old entries re-ticked + 1 brand-new show | nothing | "1 ny" (the new show only) |
| Untick a show, sync, tick it again | nothing | nothing |
| Re-enrolment to a rebuilt server, same shows under other ids | up to "30 nye" (reused ids' seen sets) | nothing |

## 3. Pure functions (`logic/NewItems.kt`) and tests

```kotlin
/** How long after a new entry's item joins the tray its further fresh keys are acknowledged, not added (§1.4). */
const val NEW_ENTRY_SETTLE_MS = 24 * 60 * 60 * 1000L

/** One row of `known_entries`. [entryId]: the id the library last gave this key (null = none now). [pending]: added
 * after the baseline, item not given yet. [item]: the new-entry item given. [newAt]: when it joined the tray. */
data class KnownEntry(val key: String, val entryId: Long?, val pending: Boolean, val item: String? = null, val newAt: Long? = null)

/** One entry's keys as the news rules see them: listed oldest first (own files in their order), [eligible]
 * (null = all), [unplayable] (§1.1: `no_audio` books, `missing` own files without a copy). */
data class Listed(val entry: Entry, val oldestFirst: List<String>, val eligible: Set<String>?, val unplayable: Set<String>)

/** A new entry's item: the first candidate in reading order for Storytel, in play order otherwise; null = none. */
fun newEntryItem(l: Listed): String?

/** One entry's listing seen. Empty listing: nothing changes. [pending]: the whole listing is acknowledged and
 * newEntryItem joins (the mark is used only when there is one). Else markNewSeen; fresh eligible keys join, unless
 * [settling] (inside the 24 h window), then they are only acknowledged. */
data class EntryNews(val news: List<String>, val seen: Set<String>?, val item: String?)
fun entryNews(l: Listed, seen: Set<String>?, pending: Boolean, settling: Boolean): EntryNews

/** Before the listings are applied (Sync phase A). [named]: Library.keyById. [played]: a position or bookmark row
 * exists. A library naming nothing changes nothing. Returns the rows after, the sticky closed flag after, and the
 * ids to reset (§1.4, "Reused ids"). */
data class Before(val known: List<KnownEntry>, val closed: Boolean, val reset: Set<Long>)
fun newsBefore(known: List<KnownEntry>, closed: Boolean, named: Map<Long, String>, played: Boolean): Before

/** After the listings are applied (Sync phase B), inside one transaction. [applied]: entries whose listing was applied
 * this run, plus every own entry. [current]: every library entry with a parsed listing (for the re-pick).
 * [seen]: the seen sets of [applied]. Returns the rows, the tray and the seen keys to insert. */
data class After(val known: List<KnownEntry>, val tray: List<NewsItem>, val seenAdded: Map<Long, Set<String>>)
fun newsAfter(
    known: List<KnownEntry>, tray: List<NewsItem>, seen: Map<Long, Set<String>>,
    applied: List<Listed>, current: Map<Long, Listed>, now: Long,
): After
```

**Rules inside:**
- `settling` is `newAt != null && 0 <= now - newAt < NEW_ENTRY_SETTLE_MS`. A clock that runs backwards falls back to
  today's rule.
- `newsAfter` handles an entry's pending mark and window by its key, with `entryId == entry.id`.
- **The re-pick:** a row whose `item` is in the tray and now in its entry's `unplayable` gets
  `newEntryItem(current[id])` in the same tray slot, or leaves the tray when there is none.
- A used mark leaves the run's pending set at once.

**Tests**, in `NewItemsTest` after the port's 1-8. They are named `d29 n` because they are not vibb ports. The
run-level ones use a test helper `sync(state, library, played, listings, now)` that calls `newsBefore`, applies the
resets, then calls `newsAfter`, in `Sync.run`'s order.

**Baseline and keys (`newsBefore`):**
- `d29 1 the first library that names an entry is the baseline - nothing is pending`
- `d29 2 a library naming no entry neither takes nor moves the baseline`
- `d29 3 ticks over several syncs before the first play are all baseline` (QA H1)
- `d29 4 after the first play a key the phone never knew is pending, once`
- `d29 5 the baseline stays closed once a play was stored, even with no rows left`
- `d29 6 an upgrade with positions takes the baseline from its first library`
- `d29 7 a key taken off and ticked again is not pending - the set only grows; a pending one stays pending`
- `d29 8 an id that now carries another key is reset, and a key under a new id moves`. The old holder drops the id. A
  library naming an id for the first time resets nothing.

**The item (`newEntryItem`):**
- `d29 9 a new podcast's item is its newest playable episode`. The withdrawn newest is skipped, and `oldest_first`
  gives the first.
- `d29 10 an NRK serie and an own entry give their first item - the effective order counts`. A serie with
  `newest_first` gives the newest.
- `d29 11 a Storytel series gives book 1 in reading order whatever its order` (QA M1; replaces the reversal half of
  the old `d29 6`).
- `d29 12 an item that can't play is skipped - no_audio, a missing own file without a copy - and none gives null`
  (QA L4).

**One entry (`entryNews`):**
- `d29 13 a pending entry's first listing adds exactly one item, a known entry's none`. 38 keys give one item, and
  all 38 are seen.
- `d29 14 an empty listing, or one with no candidate, keeps the mark; the next with a candidate gives the item`
- `d29 15 a pending entry with a seen set still gives one item and acknowledges the rest`. This replaces the old
  `d29 11`, which pinned today's rule (the flood). After the reset it is a defence, not a path.
- `d29 16 the 24 h window: fresh keys at NEW_ENTRY_SETTLE_MS - 1 are acknowledged, at NEW_ENTRY_SETTLE_MS they join,
  and a clock before newAt counts as today` (QA M5)

**Re-pick and run level (`newsAfter`, `sync`):**
- `d29 17 an item that turns no_audio is replaced in its slot by the next candidate, or leaves when none is left`
- `d29 18 a fresh install whose listings arrive over three syncs adds nothing`
- `d29 19 setup ticks one at a time, then a play, then a new show: one item` (QA H1)
- `d29 20 a server switch with the same shows under new ids adds nothing` (QA H2)
- `d29 21 a reused id: podcast A's 80 seen keys don't make the new show B's back catalogue new - B gives one item`
  (QA H2)
- `d29 22 nothing ticked, then a re-tick and one new show: one item`
- `d29 23 a pending entry whose first listing fails gives its item when the listing arrives at the next sync`
- `d29 24 an own entry uploaded in two batches an hour apart is one item; a batch 25 h later counts each file`
  (QA M5)
- `d29 25 the new entry's item is newest in the tray, leaves when played and goes with its entry`
- `d29 26 two entries applied in one run never take one mark twice`

**Parse and schema:**
- `d29 27 keyById names every entry's id and key, an unreadable one too` (`LibraryParser.parse` with a `spotify`
  entry)
- `d29 28 the schema is exported - version 3 adds known_entries and news_baseline by an auto migration`, in the style
  of `EpisodeArtTest`'s version 2 test.

## 4. The Android glue (thin)

**`model/Library.kt`:** `Library.keyById: Map<Long, String> = entries.associate { it.id to it.key }`. The parser fills
it from the raw entries array (every object with a numeric `id` and a string `key`, usable or not), next to `rawIds`.

**`data/MusicDb.kt`:** version 3, `AutoMigration(from = 2, to = 3)` (new tables only, no spec), and
`app/schemas/me.vibb.music.data.MusicDb/3.json` committed.
- `@Entity(tableName = "known_entries") KnownEntryRow(@PrimaryKey entryKey: String, entryId: Long?, pending:
  Boolean, item: String?, newAt: Long?)`. It is never pruned: a few hundred short keys at most.
- `@Entity(tableName = "news_baseline") NewsBaselineRow(@PrimaryKey id: Int = 1, closed: Boolean)`. A missing row
  means open.
- DAO:
  - `knownEntries()`, `newsBaseline()`;
  - `anyPlayed()`: `SELECT EXISTS(SELECT 1 FROM positions) OR EXISTS(SELECT 1 FROM bookmarks)`;
  - `seenOf(ids)`;
  - two `@Transaction` methods that run the pure functions inside:
    - `newsBeforeTx(named)` reads the rows and `anyPlayed`, calls `newsBefore`, writes the rows and the flag, and
      for each reset id deletes its listing, items, seen, news and item errors;
    - `newsAfterTx(applied, current, now)` reads the rows, `news()` and `seenOf`, calls `newsAfter`, replaces the
      rows and the tray, and inserts the seen rows.

**`sync/Sync.kt` `run`:**
- The whole body runs under `runLock.withLock`.
- At the top of `if (lib != null)` comes `newsBeforeTx(lib.keyById)`, **before** the inbox loop.
- The loop applies listings as today, minus `markSeen`, and collects a `Listed` per applied listing. Its inbox files
  are deleted **after** `newsAfterTx`, so a crash in between re-applies them; the seen rows make that idempotent.
- Then every own entry joins `applied` (keys in `filesOf` order).
- `current` is built from `store.snapshot()` (`listed`, `onPhone`), `newsEligible` and the item errors.
- `newsAfterTx` runs before the prune, and `markSeen` goes.

**`sync/Downloads.kt` (QA M2):** after each `run.fetchOne(w)`, if `(w.entry.id, w.item.key)` is in `dao.news()`, call
`NewNotifier.update(snapshot)`. It returns early while the count is unchanged.

**`playback/PlaybackService.kt` (QA M3):** in `startedPlaying`, `if (store.db.deleteNews(entryId, key) > 0)
NewNotifier.update(...)`. `playedFromTray` stays as the rule's documented form and its test.

**Nothing else changes:** `NewNotifier`, the UI, the server and the launcher are untouched.

**Docs in the same change:**
- `music/CLAUDE.md`, a "Rules that are easy to break" bullet: "A new entry is news (design 29, not vibb): a key the
  phone never knew, once a play has been stored, puts one item (the first that can play; Storytel: book 1) in Nytt at
  its first listing, with a 24 h settle; known keys only grow; an id that changes key is reset like a removed entry;
  the sync runs under `Sync.runLock`."
- design 21 §4.5's "New" line gets "(and design 29: a new entry's one item)".
- `docs/testing/emulator.md` §6f: "the first listing of an entry is never new" becomes "...of an entry known at the
  baseline (closed by the first play)", and the checks of §7 are added.
- `PLAN.md`'s status line.

## 5. Files

- `music/app/src/main/java/me/vibb/music/logic/NewItems.kt` (header and the functions)
- `model/Library.kt`
- `data/MusicDb.kt`
- `sync/Sync.kt`
- `sync/Downloads.kt`
- `playback/PlaybackService.kt`
- `app/schemas/me.vibb.music.data.MusicDb/3.json` (generated)
- `app/src/test/java/me/vibb/music/logic/NewItemsTest.kt`
- the docs above
- step 4: `bridge/BridgeSync.kt`, `sync/Inbox.kt`, `data/Store.kt`, `logic/OwnFiles.kt` (new) and `OwnFilesTest`

Nothing in `server/` or `launcher/`, and no shared contract file.

## 6. Steps (one implementer, one sitting)

1. **Logic and tests.** The functions, `Library.keyById`, and `d29 1`-`d29 27`. Green on
   `./gradlew testDebugUnitTest -PwarningsAsErrors=true`.
2. **Room and the glue.** The entities, migration and DAO, `3.json`, `Sync.kt` (mutex, both phases), `Downloads.kt`,
   `PlaybackService.kt`, and `d29 28`. Green on the full
   `./gradlew assembleDebug assembleRelease testDebugUnitTest -PwarningsAsErrors=true`.
3. **Docs** as in §4, then the device checks of §7.
4. **Own-file copies after an id reuse** (the QA's "outside this design"; the same id-reuse root as H2; separate
   commit).
   - **Today:** after a server restore, an own-file id can name another file. `pullFiles` skips any id it holds
     (`BridgeSync.kt:77`), and the prune keeps copies whose id the library still names (`Sync.kt:144`). So the kid
     hears the old audio and the right file is never fetched.
   - **What the phone keeps:** today it stores no hash. The copy is checked once on arrival (`Inbox.putOwnFile`:
     size and SHA-256 against the library) and kept as `noBackupFilesDir/own/<id>`.
   - **The change:**
     - `Inbox.putOwnFile` also writes the checked SHA-256 to `own/<id>.sha256` after the rename. `ownCopies()`
       ignores the sidecar already, because the name isn't a number.
     - The prune deletes a sidecar with its copy.
     - In `pullFiles`, a held copy whose recorded hash differs from the library's `sha256` is deleted with its sidecar
       and fetched again.
     - A copy without a sidecar (held before this version) is hashed once and its sidecar written.
   - **The pure rule:** `logic/OwnFiles.kt` `ownCopyVerdict(libSha: String, heldSha: String?): Keep | Refetch |
     Hash`.
   - **Tests** (`OwnFilesTest`): "a matching copy is kept", "a copy whose recorded hash differs is fetched again",
     "a copy without a record is hashed once, then judged".
   - **Results:** the report's `held` (`BridgeSync.push`) names only kept copies, so the launcher fetches the new file.
     Positions of `f<id>` stay keyed by the entry key, as today.
   - **Device check:** replace an own file's copy on the emulator with another file's bytes (`run-as`, keeping its
     name). The next sync logs the mismatch, fetches the right file and plays it.

## 7. Device checks (emulator, docs/testing/emulator.md §6f with the launcher's bridge, §6i for Storytel)

**Setup:** grant `POST_NOTIFICATIONS` (§6f), and watch
`adb shell dumpsys notification --noredact | grep -A3 me.vibb.music` and the launcher's Home badge. Except in the
first check, play any item for 20 s first: the stored position closes the baseline.

- **Fresh install, no flood:**
  1. Uninstall the debug music app, install it with nothing ticked, then tick at least 6 entries **one at a time**
     (podcasts, an NRK serie, an own entry, the Storytel entry), letting a sync run between ticks.
  2. Expect no notification, an empty Nytt and no NY.
  3. Repeat after `adb shell pm clear me.vibb.music.debug`.
  4. Then play something for 20 s and tick one more new show: "1 ny".
- **A new NRK serie** (a short one, natural order):
  1. Tick it in the PWA, wait until the server has listed it, and sync the launcher.
  2. After the music app's sync, expect exactly one notification, "1 ny i Musikk", and Home's badge at 1. Nytt holds
     one row, episode 1, and its tile shows NY.
  3. Tap the row: episode 1 plays, and the notification and NY go.
- **A new podcast:** the same with a 30+ episode NRK podkast. Expect "1 ny" and the newest episode.
- **A new Storytel series** (§6i, `--books 111:300,222:300,333:300`):
  1. Take the baseline with a library without entry 5, then play something.
  2. Push the library with entry 5 and its listing. While 111 downloads, expect no notification and no tile.
  3. Expect "1 ny i Musikk" with book 111 in Nytt **while 222 is still downloading** (QA M2).
  4. Push a library without entry 5, then with it again: no notification (the same key).
  5. With `--locked 111`: once 111 is `no_audio`, the next sync makes 222 the item, and "1 ny" comes once 222 is
     downloaded.
- **A new own-files entry:** create it and upload 3 files. Expect "1 ny" (the first file) once copied. Upload 2 more
  within the hour: still "1 ny".
- **Nothing ticked:** untick everything (the app empties), then tick two of the old entries and one brand-new show.
  Expect "1 ny" only.
- **Re-enrolment / restore (QA H2):**
  1. Note the entry ids in the PWA, back up the server, delete two entries and create other shows (they take new
     ids), then restore the backup.
  2. Or point the phone at a fresh server with the same shows (design 22, "Re-enrolling").
  3. Either way, expect no notification, and no old show's back catalogue in Nytt.
- **Unchanged:** a new episode in a known podcast (outside a 24 h window) still adds one. A swipe hides the
  notification until the count changes. "Fjern alle" cancels it. Tapping a Nytt row while a sync runs doesn't bring
  the row back.

## 8. Defaults chosen (veto if wrong)

1. **The item is the first that can play in play order,** not the newest. For an NRK serie in natural order that is
   episode 1, and for Storytel always book 1 in reading order. The brief's device check expected "the newest episode"
   for an NRK series.
2. **An NRK or RSS item counts at once,** because it streams, like a new episode today. Storytel and own files wait for
   their copy. The brief expected the NRK case to wait for a download.
3. **Re-ticking an entry the phone has known (the same key) is not news,** unless it was still pending.
4. **The baseline stays open until a play has been stored.** Everything ticked before the kid first listens is setup.
5. **The 24 h settle hides a genuinely new episode of a new entry in its first day.**

## Design QA (2026-10-13, `docs/design/qa-29-design.md`; every finding accepted, revised as follows)

**High:**
- **H1, setup floods:** the baseline stays open until a position or bookmark has ever been stored, and closing it is
  sticky (`news_baseline`). §1.3 justifies it: a never-played phone, the gap, the rejected "app opened" signal. §1.4,
  §2 and §8.4 follow. Tests `d29 3`, `d29 5`, `d29 6`, `d29 19`. §7's fresh-install check ticks one at a time.
- **H2, reused ids:** `known_entries` records the entry id. `newsBefore` resets an id whose key changed (its listing,
  items, seen, tray and item errors) before the listings are applied. The old `d29 11` (today's rule for a pending
  entry with a seen set) is replaced by `d29 15` (one item, the rest acknowledged), and `d29 8`, `d29 20` and `d29 21`
  are added. §7 adds a restore / re-enrolment check.

**Medium:**
- **M1, Storytel's item:** always book 1 in reading order. §1.1 says the cover-tap agreement holds only for the
  natural order. Test `d29 11`.
- **M2, the notification after each item:** `Downloads.kt` updates after each finished item that is in the tray.
  §7's Storytel check uses 300 MB books and expects the notification while 222 downloads.
- **M3, the races:** the tray is read inside the transaction, `startedPlaying` uses `deleteNews`, and `Sync.runLock`
  holds the whole run. The 6 h backstop is a second unique work (`music-backstop`) that can overlap `music-sync`, and
  the mutex serialises them. §1.6 gives both crash directions.
- **M4, wiring untested:** the bookkeeping is in the pure `newsBefore` / `newsAfter`, and `Sync` only reads and writes
  rows inside two transactions. Run-level tests `d29 18`-`d29 26` go through a `sync` helper over several syncs: fresh
  install, setup ticks, a server switch, a reused id, nothing ticked, a failed first listing, two batches, the tray
  rules, one mark per run.
- **M5, trickle:** `NEW_ENTRY_SETTLE_MS` (24 h) from the item joining the tray (`known_entries.newAt`). Inside it, a
  new entry's fresh keys are acknowledged. Test `d29 16` covers both edges and a clock before `newAt`, and `d29 24`
  covers two batches an hour apart and one 25 h later. §2 and §7 updated.

**Low:**
- **L1:** an NRK serie gives episode 1, or the newest when the server sends `newest_first`. Test `d29 10`. §7 uses a
  short serie.
- **L2:** the "two entries with one key" sentence is dropped (keys are unique). One mark per run stays as a defence
  (`d29 26`).
- **L3:** §1.4 states the pending-then-removed exception and the new-server own entries.
- **L4:** the item is the first candidate that can play: `no_audio` books and `missing` own files without a copy are
  skipped. A later `no_audio` re-picks in the same slot, and with no candidate left the item leaves the tray (§1.1).
  Tests `d29 12`, `d29 14`, `d29 17`.
- **L5:** §7 is revised: ticks one at a time, a play before each addition check, large books, the restore check, and
  the own-files check reads "still 1 ny" under M5.

**Outside the design (orchestrator):** own-file copies after an id reuse are step 4. A held copy whose recorded
SHA-256 differs from the library's is fetched again. The hash is recorded in an `own/<id>.sha256` sidecar, and a copy
without one is hashed once.
