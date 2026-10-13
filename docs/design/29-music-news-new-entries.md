# 29 - Vibb music: a new entry is news too (buildable spec)

Architect, 2026-10-13. Sources: design 21 §4.5 (NY, Nytt, the notification), 21b §6, 26b §3.2-§3.4 (Storytel is
visible on the phone once downloaded), and the code on `main` (`3292cd32`): `music/.../logic/NewItems.kt`,
`sync/Sync.kt` (`applyListing`, `markSeen`, the prune), `sync/NewNotifier.kt`, `data/Snapshot.kt` (`visible`,
`forRenderer`), `data/MusicDb.kt` (Room 2), `model/Library.kt`, `bridge/BridgeSync.kt` + `BridgeConfig.kt`
(`nothingTicked`, `EMPTY_LIBRARY`), `logic/PlayOrder.kt`, `logic/Offline.kt`, `logic/Storytel.kt`, and the server's
`state_key` / `new_own_key` (`server/src/music.rs`).

**The user's decision (final, 2026-10-13):** "N nye i Musikk", the NY badge and the Nytt tray also count something the
parent **adds** to the phone's library: a new podcast or NRK series, a Storytel book or series, an own-files entry,
anything. Today only new items in an entry the phone already knows count.

## 0. Today, and the change

- `markNewSeen(seen, listed)` (a port of vibb `pi/vibb/library.py` `_mark_new_seen`): an entry's first listing
  (`seen == null`) acknowledges every key; later listings put their unseen keys in the tray (`addToTray`, newest
  first, `NEWS_MAX` 30). `Sync.applyListing` passes the listing's keys and `newsEligible` (a url, or a Storytel book);
  own entries pass their files' keys on every sync. An entry the library no longer names loses its `seen` rows and
  its tray items (`pruneTray`). So a new show or book never reaches the tray.
- The notification, the bell and NY count only **visible** tray items (`NewNotifier.count`, `UiModel`); the notifier
  reads the phone's view (`forRenderer(false)`), so a Storytel book counts once downloaded and an own file once
  copied. `Sync.run` and the download job call `NewNotifier.update`. The launcher's badge counts `setNumber`.
- **The change:** an entry the phone has **never known** gets **one** item in the tray at its first listing. Every
  rule after that is unchanged.
- **This deviates from vibb.** On the Pi a new show's first listing is acknowledged like any other. The port
  (`markNewSeen` and `NewItemsTest` 1-8) stays exactly as it is, and the new rule sits beside it. `NewItems.kt`'s
  header says so: "Design 29 adds `knownAfter`, `newEntryItem` and `entryNews`, which are not in vibb: a new entry
  contributes one item." `NOTICE.md` needs no change, because it names what is ported and this part is ours.

## 1. Decisions

1. **One item per new entry, never its back catalogue.** A new 38-episode podcast is "1 ny". The item is **the first
   eligible item in the entry's play order** (`playOrder`, so the parent's order setting counts). That is the item a
   cover tap starts when there is no bookmark, so the cover tap and the Nytt tap agree, and playing it clears it
   (`playedFromTray`).
   - **NRK podkast / RSS** (newest first): the newest episode that has a url. A withdrawn newest episode is skipped.
   - **NRK serie** (oldest first): **episode 1**, not the newest. A story starts at its start, and a Nytt tap on the
     newest episode would put the kid at its last chapter.
   - **Storytel series**: book 1 in reading order. That is also the book the download plan fetches first (no
     bookmark), so it is the first to become visible. A single-book entry contributes its one book.
   - **Own files**: the first file in the entry's order.
   - "Eligible" is today's rule: `newsEligible` for listings (a url, or any Storytel book), and every file for own
     entries.
2. **It shows when it can play: no new visibility rule.** The tray holds the item from the first listing on. The
   notification, the bell and NY show it once it is visible on the phone, exactly as for a new episode today:
   - an NRK or RSS item at once, because it streams (like a new episode today; offline it says "Ikke lastet ned -
     trenger Wi-Fi");
   - a Storytel book once downloaded (the download job's `NewNotifier.update` posts it);
   - an own file once copied.

   An item that never becomes visible (say, a book Storytel refuses, `no_audio`) stays in the tray uncounted, as a
   new book does today. It leaves the tray by `NEWS_MAX`, "Fjern alle" or when its entry is removed.
3. **"The parent added it" = an entry key the phone has never known, after a baseline.**
   - **Known set:** a persisted, **grow-only** set of entry **keys** (vibb's `state_key`), stored in Room next to
     `seen` and `news`, so it is wiped with them.
   - **Baseline:** while the set is empty there is none. The first parsed library that names at least one entry
     becomes the baseline: all its keys are known and none is added.
   - **After the baseline:** each parsed library adds its unknown keys, marked **pending**. A pending entry
     contributes its one item at its first non-empty listing.
   - **Keys, not ids.** A key is the target's slug or hash, and for own entries a random `own-` key. Ids come back
     after a server database restore (qa-21-step1-code #7) and start again on a new server, so an id-based set could
     flood or miss. The positions hang off the key too.
   - **The library that arrives in several syncs** is fine. Entries in the baseline whose listings arrive later are
     known and not pending, so their first listing acknowledges everything as today. A pending entry stays pending
     until a non-empty listing arrives: a new NRK entry the server hasn't listed yet, or an own entry created before
     its files are uploaded.
   - **The keys come from every entry the document names, usable or not** (`Library.entryKeys`, like `entryIds`). An
     entry an older app can't read (a future source) is known at the baseline. Its key is never "added" by the app
     update that makes it readable, so that update can't flood.
4. **Never a flood.**
   - **Fresh install, app data cleared, reinstall** (`allowBackup="false"`): the Room database is empty, so the first
     library is the baseline.
   - **Upgrade to this version:** the new table is empty, so the first sync after the upgrade takes the baseline.
   - **Unmanaged, then managed again:** keys are stable across re-enrolment and even across a rebuilt server with the
     same shows, so only genuinely new shows count.
   - **`nothingTicked` / `EMPTY_LIBRARY`:** a library that names no entry neither takes nor moves the baseline. A
     phone set up with nothing ticked therefore takes its baseline from the first ticks, and those are setup, not
     news. Ticking again after "nothing ticked" adds only keys never known.
   - **Removed and re-added (same key):** not new. The known set only grows. An untick and re-tick in the PWA, by
     mistake or not, must not notify, and the kid's positions come back with the key, so it is the kid's old show.
     The entry's `seen` rows went at the removal, so its first listing acknowledges everything as today.
   - **A show deleted and re-created** has the same key (the same target), so it is not new either. A new own-files
     entry always has a new key.
5. **The existing rules stay.** `markNewSeen`, `addToTray` (the item gets the next `seq`, so it is newest),
   `playedFromTray`, `pruneTray`, `NEWS_MAX`, "Fjern alle", the swipe-to-hide count rule and the notification's look
   are unchanged. Later items of a once-new entry are ordinary news. Example: an own entry's files uploaded in a
   second batch each count, as "Nytt spor" does today.
6. **No server or launcher change.** The library already names every entry with its key. The launcher's badge
   already counts `setNumber`. The contract snapshots are untouched.

## 2. Behaviour (what the kid sees)

| Case | Today | Design 29 |
|---|---|---|
| Parent ticks a new 38-episode podcast | nothing | "1 ny i Musikk" (the newest episode) after the sync that applies its listing |
| New NRK serie (oldest first) | nothing | "1 ny" for episode 1 |
| New Storytel series, books 111, 222, 333 | nothing | "1 ny" once 111 is downloaded, not before |
| New own-files entry (12 files, one upload) | nothing | "1 ny" (the first file) once copied |
| Then 2 more files uploaded to it | 2 new | 2 new (unchanged) |
| New episode in a known podcast | 1 new | 1 new (unchanged) |
| Fresh install / data cleared / app upgrade, 20 entries | nothing | nothing (baseline) |
| Nothing ticked, then 3 old entries re-ticked + 1 brand-new show | nothing | "1 ny" (the new show only) |
| Untick a show, sync, tick it again | nothing | nothing |

## 3. Pure functions (`logic/NewItems.kt`) and tests

```kotlin
/** The known entry keys after a parsed library naming [named]: (known after, keys added now). [known] null = no
 * baseline yet. A library naming nothing changes nothing; the first naming something is the baseline; after it,
 * unknown keys are added. The set only grows. */
data class KnownUpdate(val known: Set<String>?, val added: Set<String>)
fun knownAfter(known: Set<String>?, named: Set<String>): KnownUpdate

/** A new entry's one tray item: the first of [eligible] (null = all) in [entry]'s play order; [oldestFirst] as listed. */
fun newEntryItem(entry: Entry, oldestFirst: List<String>, eligible: Set<String>?): String?

/** One entry's listing (or own files) seen: the keys for the tray, the seen set after, and whether a pending mark is
 * used up. Empty [listed]: nothing changes, a pending mark stays. [seen] null: everything is acknowledged
 * (markNewSeen) and, if [pending], newEntryItem joins the tray. [seen] set: today's rule (fresh keys in [eligible]).
 * A pending mark is used up by any non-empty listing. */
data class EntryNews(val news: List<String>, val seen: Set<String>?, val pendingDone: Boolean)
fun entryNews(entry: Entry, seen: Set<String>?, listed: List<String>, eligible: Set<String>?, pending: Boolean): EntryNews
```

`entryNews` calls `markNewSeen` and never changes it. Tests go in `NewItemsTest`, after the port's 1-8, named `d29 n`
(they are not vibb ports):

- `d29 1 the first library that names an entry is the baseline - nothing is added`
- `d29 2 a library naming no entry neither takes nor moves the baseline`: null + {} gives null, and known + {} gives
  known with nothing added.
- `d29 3 after the baseline an unknown key is added once`: the same library again adds nothing.
- `d29 4 a key taken off and ticked again is not added - the set only grows`
- `d29 5 a new podcast contributes its newest playable episode`: RSS with the newest url null takes the one before it;
  `order = oldest_first` takes the first.
- `d29 6 a new NRK serie, own entry and Storytel series contribute their first item`: the Storytel book is eligible
  with `url == null`, and `order = newest_first` reverses.
- `d29 7 a pending entry's first listing adds exactly one item, a known entry's none`: 38 keys give news of size 1,
  seen = all 38, and `pendingDone`.
- `d29 8 an empty listing keeps the entry pending`
- `d29 9 after its first listing a new entry is ordinary`: the next listing with 2 new keys gives 2 items.
- `d29 10 a first listing with nothing playable uses up the mark with no item`
- `d29 11 a pending entry that already has a seen set gets today's rule and is used up`
- `d29 12 the new entry's item is newest in the tray, leaves when played and goes with its entry` (`addToTray`,
  `playedFromTray`, `pruneTray`)
- `d29 13 a fresh install whose listings arrive over three syncs adds nothing`: `knownAfter` and then `entryNews` with
  `pending = false`.
- `d29 14 entryKeys names every entry's key, an unreadable one too`: `LibraryParser.parse` with a `spotify` entry.
- `d29 15 the schema is exported - version 3 adds known_entries by an auto migration`: `3.json` and the
  `AutoMigration(from = 2, to = 3)` line, in the style of `EpisodeArtTest`'s version 2 test.

## 4. The Android glue (thin)

- **`model/Library.kt`:** `Library.entryKeys: Set<String> = entries.map { it.key }.toSet()`. The parser fills it from
  the raw entries array (every object's `key` string, usable or not), next to `rawIds`.
- **`data/MusicDb.kt`:**
  - `@Entity(tableName = "known_entries") data class KnownEntryRow(@PrimaryKey val entryKey: String, val pending:
    Boolean)`.
  - The database goes to `version = 3` with `AutoMigration(from = 2, to = 3)` (a new table, no spec), and the
    exported `app/schemas/me.vibb.music.data.MusicDb/3.json` is committed.
  - DAO: `knownEntries()`, `insertKnown(rows)` (IGNORE), and one `@Transaction applySeen(entryId, entryKey,
    newSeen: List<SeenRow>, news: List<NewsRow>?, pendingDone: Boolean)`, which inserts the seen rows, replaces the
    tray when `news != null` and clears `pending` for the key. The seen set, the tray and the mark then move together,
    so a crash can't leave a mark that fires on a later re-tick.
  - `known_entries` is never pruned: a few hundred short keys at most.
- **`sync/Sync.kt` `run`:**
  - At the top of `if (lib != null)`, before the inbox loop (so the baseline sync's listings see it), compute
    `knownAfter(rows' keys or null if none, lib.entryKeys)`, insert the new keys with `pending = key in added`, and
    keep `pending = rows' pending keys + added` for this run.
  - `markSeen(store, entry, listed, eligible, pending)` takes the `Entry`, calls `entryNews(entry, before, listed,
    eligible, entry.key in pending)`, adds `news` with `addToTray` and writes through `applySeen`.
  - A used-up key leaves the run's `pending` set, so two entries with one key give one item.
  - The own-entries loop passes each entry's file keys in `filesOf` order.
  - Nothing else changes. `NewNotifier`, `PlaybackService`, the UI, the server and the launcher are untouched.
- **Docs in the same change:**
  - `music/CLAUDE.md`, a "Rules that are easy to break" bullet: "A new entry is news (design 29): an entry key the
    phone never knew, after the baseline, puts one item (the first in play order) in Nytt at its first listing; the
    known set only grows; the first library naming an entry is the baseline. Not vibb."
  - Design 21 §4.5's "New" line: "(and design 29: a new entry's first item)".
  - `docs/testing/emulator.md` §6f's sentence "the first listing of an entry is never new" becomes "of an entry
    known at the first library"; add the checks of §7 below.
  - `PLAN.md`'s status line.

## 5. Files

`music/app/src/main/java/me/vibb/music/logic/NewItems.kt` (header + 3 functions), `model/Library.kt`,
`data/MusicDb.kt`, `sync/Sync.kt`, `app/schemas/me.vibb.music.data.MusicDb/3.json` (generated),
`app/src/test/java/me/vibb/music/logic/NewItemsTest.kt`, and the docs listed above. No `server/`, `launcher/` or
shared contract file.

## 6. Steps (one implementer, one sitting)

1. **Logic and tests.** The three functions, `Library.entryKeys`, and tests `d29 1`-`d29 14`. Green on
   `./gradlew testDebugUnitTest -PwarningsAsErrors=true`.
2. **Room and the sync.** The entity, migration and DAO, `3.json`, the `Sync.kt` glue, and test `d29 15`. Green on the
   full `./gradlew assembleDebug assembleRelease testDebugUnitTest -PwarningsAsErrors=true`.
3. **Docs**, as in §4, then the device checks.

## 7. Device checks (emulator, docs/testing/emulator.md §6f with the launcher's bridge, §6i for Storytel)

Grant `POST_NOTIFICATIONS` (§6f) and watch `adb shell dumpsys notification --noredact | grep -A3 me.vibb.music` and
the launcher's Home badge.

- **Fresh install, no flood:** uninstall the debug music app, tick at least 5 entries in the PWA (podcasts, an own
  entry), install it and let the bridge sync and the downloads finish. Expect no notification, an empty Nytt and no
  NY. Repeat after `adb shell pm clear me.vibb.music.debug`.
- **A new NRK series:** tick a new NRK serie in the PWA, wait until the server has listed it, then sync the launcher.
  After the music app's sync, expect exactly one notification, "1 ny i Musikk", with Home's badge at 1. Nytt holds
  one row: **episode 1**, not the newest. Its tile shows NY. Tapping the row plays episode 1, and the notification
  and NY go.
- **A new podcast:** the same, with a 30+ episode NRK podkast. Expect "1 ny" and the newest episode.
- **A new Storytel series:** follow §6i with `--books 111:300,222:5,333:5`.
  1. Take the baseline with a library without entry 5.
  2. Push the library with entry 5 and its listing.
  3. While 111 downloads, expect no notification and no tile.
  4. Once 111 has landed, expect "1 ny i Musikk" with book 111 in Nytt.
  5. Then a library without entry 5, and with it again (the bookmark check of §6i): no notification (same key).
- **A new own-files entry:** create it and upload 3 files in one go. Expect "1 ny" (the first file) once copied.
  Upload 2 more and expect "3 nye".
- **Nothing ticked:** untick everything (the app empties), then tick two of the old entries and one brand-new show.
  Expect "1 ny" only.
- **Unchanged:** a new episode in a known podcast still adds one. A swipe hides the notification until the count
  changes. "Fjern alle" cancels it.

## 8. Defaults chosen (veto if wrong)

1. The item is the first in play order (episode 1 of an NRK serie, book 1 of a Storytel series), not the newest. The
   brief's device check expected "the newest episode" for an NRK series, and this rule gives episode 1.
2. An NRK or RSS item counts at once, because it streams, like a new episode today. Only Storytel and own files wait
   for their copy. The brief expected the NRK case to wait for a download.
3. Re-ticking an entry the phone has known (the same key) is not news.
4. On a phone whose first ticks come after the app is installed with nothing ticked, those first ticks are the
   baseline, not news.
