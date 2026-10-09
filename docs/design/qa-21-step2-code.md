# QA 21 step 2 code review - music app core (2026-10-09)

Scope: branch `music-step2`, 24f5ef0b..ac7ade62 on main 0ff256dd (`music/`, `.github/workflows/music.yml`, the
docs). Read against design 21 (§4, §5, §6 step 2, the QA decisions, the step 2 status and its deviations), 21b §4
and §6 with their decisions and the user's answer, design 20's GUI decisions, mockup `Main.dc.html` v12, the
CLAUDE.md files, and palchrb/vibb `main` (`pi/vibb/{bookmarks,library,content}.py`, `pi/player.py`, and the 13
ported `tests/*.py`). Paths below are under `music/app/src/main/java/me/vibb/music/` unless they say otherwise.

Build: a worktree of ac7ade62. `./gradlew --no-daemon testDebugUnitTest assembleDebug assembleRelease
-PwarningsAsErrors=true` passed: 106 tests, 0 failures. `checkReleaseHasNoGoogleServices` (119 modules),
`checkDebugHasDebugFeed` and `checkReleaseHasNoDebugFeed` ran. The release APK is 4.6 MB. There is no emulator here,
so nothing was run on a device. The Media3 claims in M2 were checked against the media3-session 1.7.1 bytecode in
the Gradle cache.

Counts: 1 High, 6 Medium, 19 Low, 4 Simplification.

## High

1. **High: a library element the app can't parse counts as removed, and its state is deleted for good.**
   - Where:
     - `model/Library.kt:143-160`: an entry is dropped for any field outside today's values: an unknown `order`, a
       `cache` outside -1..100, a `cover` or `items` of another shape, or an unknown `source`.
     - `model/Library.kt:131-133`: a file is dropped when it fails to decode or its entry was dropped.
     - `sync/Sync.kt:103-114` then treats every dropped id as removed:
       - it deletes the entry's listing and NY state;
       - `prunePositions`/`pruneBookmarks` delete the kid's positions and bookmark (:109-110);
       - it deletes own-file copies whose id isn't in the parsed `files` (:114);
       - `downloadPlan` prunes the entry's downloads (`removedEntries`, `sync/DownloadPlan.kt:32-33`).
     - With an empty `entries` list, `NOT IN ()` deletes every position.
   - Scenario: the app and the server are released separately, and an update to the music app can wait (21 §3).
     - Suppose a later server sends a new `order` value, a new cover hash format, or `cache: 200`. An older app then
       drops those entries. At its next sync it deletes the kid's place in a long audiobook, every download, and
       every own-file copy of the entry.
     - That includes the copies of files the server lists as `missing`. QA #3 made those the only copy left.
     - A parent who unticks an entry by mistake and ticks it again also loses its positions. vibb never deletes
       state files: `prune_cache` removes only cached audio.
     - QA #3 ("prune only what a parsed library dropped") is the same class of bug, and it was rated High.
   - Fix:
     - An entry or file counts as removed only if its id is absent from the raw `entries`/`files` arrays. Collect the
       ids from every object that has a numeric `id` before validating the rest.
     - Keep a malformed entry's state, and only hide the entry.
     - Degrade instead of dropping where you can: an unknown `order` becomes `auto`, a bad `cover` becomes null.
     - Never prune positions or bookmarks, as in vibb. The rows are tiny.
     - Never delete a copy of a file the server marks `missing`.
     - Test each case.

## Medium

1. **Medium: a download that once got a 4xx is never tried again while its URL stays the same.**
   - Where: `sync/Downloads.kt:100-103` (`permanentFailure`) and `sync/DownloadPlan.kt:43-47`. Nothing ever clears
     `failedUrl` except a later success, and there is never a later attempt.
   - Scenarios:
     - On holiday abroad, NRK answers 403 for a Norway-only programme (geo-block). Back home, it is never downloaded.
     - A CDN edge gives a 404 for an hour.
     - A 416 appears after the file shrank under a partial copy. Media3 treats a 416 only at the exact end as EOF.
   - 21b §2.5 says "The same URL changes nothing; the phone keeps its own backoff". After a re-resolve the server
     keeps the same URL, so this phone waits for ever. The PWA card shows "2 of 5 · waiting" with no end.
   - Fix:
     - Give a 4xx a slow per-item retry: `failedAt` plus attempts, retried after 24 h, then daily.
     - Retry at once on a new URL, as now.
     - Treat a 416 by dropping the partial cache and fetching from scratch once.

2. **Medium: a Bluetooth or headset "play" with nothing to resume probably crashes the app.**
   - Where:
     - `AndroidManifest.xml:44-50` uses the stock `androidx.media3.session.MediaButtonReceiver`.
     - `playback/PlaybackService.kt:234-251`: `onPlaybackResumption` fails when there is no stored queue. That
       happens before the first play, after a queue played to its end (`queueEnded` clears the row), or when the
       entry is gone.
   - What Media3 1.7.1 does:
     - `MediaButtonReceiver` calls `startForegroundService`.
     - After the failure, `MediaSessionImpl$1.onFailure` only logs and presses play on the empty player.
     - `MediaNotificationManager.shouldShowNotification` returns false for an empty timeline.
     - So `startForeground` is never called, and Android raises `ForegroundServiceDidNotStartInTimeException`.
     - Media3 1.5 added `MediaButtonReceiver.shouldStartForegroundService` for exactly this case (androidx/media
       #1528).
   - Scenario: a story ends. Later, the kid's headphones or a car kit sends AVRCP PLAY, which many cars do on
     connect. The result is "Musikk keeps stopping".
   - Fix:
     - Subclass the receiver and return false from `shouldStartForegroundService` when no now-playing row exists.
       Use a synchronous flag, such as a pref written together with the row.
     - Device check: press BT play after a queue has ended.

3. **Medium: choosing "Telefonen" sticks, so headphones plugged in later still get no sound.**
   - Where: `playback/PlaybackService.kt:518-537`. `setOutput(PHONE)` pins the built-in speaker with
     `setPreferredAudioDevice`. Only removing the chosen device resets it, and the speaker is never removed.
   - Scenario: the kid picks the phone while the BT headphones are on, or once by accident. That evening, wired or BT
     headphones connect, and the story keeps playing out loud on the speaker. Design 21 §4.6 says "until that device
     goes", which was written with BT in mind.
   - Fix: in `onAudioDevicesAdded`, when a BT, LE or wired output arrives while the phone speaker is pinned, drop the
     preference and return to system routing.

4. **Medium: new episodes can wait hours behind the single download job.**
   - Where:
     - `sync/Downloads.kt:73`: `KEEP` unless a URL changed.
     - `sync/Downloads.kt:106-132`: the plan is computed once when the job starts.
     - `:112`: a gate miss returns `Result.retry()`.
     - `:127`: any transient failure makes the whole job `retry()`, with exponential backoff from 1 minute up to
       WorkManager's 5 h cap.
   - Scenarios:
     - A sync (a nudge) while the job runs or sits in backoff is swallowed by `KEEP`. Its new items wait for the next
       sync: the 6 h backstop or another nudge.
     - One item that keeps failing (a 5xx, or a host that serves HTML for one file) pushes every later run of every
       entry out by up to 5 h.
     - A few Wi-Fi drops grow the backoff too, although the network constraint already holds the job back.
     - Kid sees: last night's episode still isn't on the phone for the morning car trip.
   - Fix:
     - Re-plan in a loop until no new item is wanted.
     - Track failures per item (see M1) and return `success()` instead of `retry()`.
     - Let the constraints, not the backoff, handle "no Wi-Fi".
     - Use `APPEND_OR_REPLACE`, or re-enqueue at the job's end when the plan changed.

5. **Medium: a listing with no readable items is applied as an empty list, which prunes irreplaceable copies.**
   - Where:
     - `model/Listing.kt:62`: a missing or non-array `items` becomes `[]`, and every item that fails to decode is
       dropped silently.
     - `sync/Sync.kt:130-135` replaces the Room listing with it.
     - Then `downloadPlan` prunes every copy except the bookmark.
   - Withdrawn (`url: null`) copies can't be fetched again, and the entry disappears from the carousel.
   - Scenario: the same version skew as H1. A server writes `duration_ms` as a float or renames `hls`. Every item
     fails `ItemDto`, and the phone deletes the kid's kept episodes of a podcast whose feed has since rolled them off.
   - QA #3: "a missing or unreadable listing must prune nothing".
   - Fix:
     - Refuse the listing (`bad_listing`, keep the old one) when `items` isn't an array.
     - Also refuse it when items were present but none (or, say, under half) survived parsing.
     - Test both cases.

6. **Medium: after a restart, the player's next, previous, seek and shuffle do nothing until play is pressed.**
   - Where:
     - `ui/MainActivity.kt:93-95`: these actions return unless `PlayerHub` is live.
     - `playback/PlaybackService.kt:454`: `toggleShuffle` returns without `active`.
   - When this happens: the service holds no queue after every restart, and also whenever it stopped while paused.
   - Scenario:
     - The kid opens the player from the restored "Spilles nå" bar.
     - They drag the seek line, and the thumb snaps back.
     - They press "next", and nothing happens.
     - They press shuffle, and it stays off.
     - Only play works.
   - Fix: let these commands restore the stored queue first, prepared and paused (`restore()` plus `setMediaItems`
     without `play()`), then act. Nothing auto-plays.

## Low

1. **Low: done marks depend on the listing's duration.**
   - Where: `playback/PlaybackService.kt:640-646`. `markDone` returns when the item's `durationMs` is null, which is
     common for RSS items without `itunes:duration` and for untagged own files.
   - The mark then relies on the 15 s save tick landing in the last 20 s. The tick is re-armed on every `isPlaying`
     change, so a buffer or a pause near the end skips it. The item then shows no ✓, and a list pick resumes it seconds
     before its end.
   - The bookmark moves only when the next item starts (`savePosition` in the transition). When the next item can't
     play (`:558-563`), the bookmark stays on the finished item, near its end.
   - Fix:
     - Keep the last known `exo.duration` per item in `Active`, and use it in `markDone` and `queueEnded`.
     - On an AUTO transition into an unplayable item, move the bookmark to the item that does play.

2. **Low: a queue played to its end keeps every partial position.**
   - vibb's `clear_state` (bookmarks.py:98-125, called at a natural queue end, player.py:826-827) drops the bookmark
     and all partial positions, and keeps only the done marks. 21 §4.4's "keeping done marks" says the same.
   - `queueEnded` (`playback/PlaybackService.kt:649-661`) deletes only the bookmark.
   - Kid sees: on a second run through a finished series, an episode they once skipped half-way resumes mid-way.
   - The header of `logic/Bookmarks.kt:4-5` claims `clear_state` is ported.
   - Fix: delete the entry's non-done positions at the queue end, and test it.

3. **Low: the resume overlap fires on unrelated later taps, and own-file audiobooks get the music beat.**
   - `faultAt` (`playback/PlaybackService.kt:663-678`) survives until the next play. An unplug, or a permanent focus
     loss, in the evening makes the next morning's cover tap on *another* entry start 8 s early
     (`RESUME_OVERLAP_LONG_MS`). The comment at :670 says "never after a plain tap".
   - `music = source == OWN` (:676): an own-files audiobook with resume on gets 1 s, not the 3 s clause. vibb's
     "music" is Spotify.
   - Fix:
     - Clear `faultAt` in `start()` and on any user-initiated play of another item.
     - Bound the overlap to the same item.
     - Use `!entry.resume` (or a kind) for "music".

4. **Low: a cover tap on the loaded resume-off entry continues it.**
   - Where: `playback/PlaybackService.kt:274-276` (deviation 6).
   - For a resume-off entry, the hint under that cover says "starter fra begynnelsen", and design 20 says "Other
     entries start from the beginning". The kid gets track 5 continuing instead.
   - Fix: continue only when `entry.resume`; for resume off, start the plan, or confirm the reading with the user
     (see the end).

5. **Low: the "N nye i Musikk" notification isn't posted again after a reboot or a late permission grant.**
   - Where: `sync/NewNotifier.kt:47`. The pref `news_posted` equal to N skips the post.
   - A reboot clears notifications, but the pref survives. The launcher's badge then stays off until the count
     changes.
   - The same happens when `POST_NOTIFICATIONS` is granted after the first post (step 3's DO grant), because
     `notify` dropped it silently.
   - Fix: compare with `NotificationManager.activeNotifications`. To honour "a swipe only hides it", record swipes
     with a `deleteIntent` instead of a pref.

6. **Low: HLS cache keys come from one process-wide map of directory to item, and the last writer wins.**
   - Where: `media/MediaCache.kt:41,51-58`.
   - Two HLS items whose playlists share a directory map to whichever item registered last. Both the queue and the
     download job register. The other item then misses its copy, and its segments are written under the wrong
     prefix: `remove()` deletes the wrong item's files and leaves orphans.
   - A resource outside the playlist's directory (absolute variant or segment URLs on another host) is keyed by its
     raw URL. That key is never matched by `remove()`, so the cache leaks under `NoOpCacheEvictor`, and it misses
     after a re-resolve.
   - NRK's recorded manifest is a per-programme directory (`.../mktt72550116/87d9034b-0.smil/muxed.m3u8`), so RSS
     HLS is where this bites.
   - Fix: give each item's data source its own `CacheKeyFactory` (S3).

7. **Low: a partial progressive copy is continued from a new URL.**
   - Where: `sync/Downloads.kt:114-118`. The new URL resumes with a Range request against bytes cached from the old
     URL under the same key. Nothing compares length or ETag.
   - A host migration that re-encodes the file (new ID3 size or bitrate) splices two files into one copy.
   - Fix: when a not-done row's `url` differs from the URL being fetched, `MediaCache.remove` the key first.

8. **Low: a "done" copy that isn't playable media is never repaired.**
   - `onPlayerError` (`playback/PlaybackService.kt:610`) skips error recording and repair for an item with a copy.
   - A 200 response that isn't audio (a JSON error, a truncated file, any content type but `text/html`) stays
     `done` and in the keep set for ever. The queue skips it with "Ikke lastet ned - trenger Wi-Fi".
   - Fix: on a `ParserException` from a local copy, delete the row and the cache, so the next sync fetches it again.

9. **Low: `item_errors` are never cleared except by a successful download.**
   - Where: `sync/Sync.kt:197-208`, cleared only at `sync/Downloads.kt:119`.
   - These rows stay in the report: a stream error on an item that later streams fine, an item that left its
     listing, an entry removed from the library. They are dropped only when 50 newer rows push them out.
   - The server ignores old versions, but the PWA card's "1 failing" lines can't age out on a stable listing.
   - Fix: drop an item's row when that item plays or downloads fine, when its listing version changes, or when its
     entry leaves the library.

10. **Low (hardening): playback lets a remote HLS playlist open `file:` or `content:` URIs.**
    - Where: `media/MediaCache.kt:101-105`. The NRK and RSS upstream is `DefaultDataSource`, which only own files
      need.
    - The address guard holds, but a hostile playlist can make the player read app-private files. It has no way to
      send them anywhere.
    - Fix: use `http(publicOnly)` as the upstream for NRK/RSS items, and `DefaultDataSource` only for own files
      (tag them).

11. **Low: work on the main thread, and loops that run while the screen is off.**
    - `MusicViewModel.kt:29` collects `store.snapshots()` on Main, so every DB change rebuilds the snapshot there,
      and `ownCopies()` lists a directory (`data/Store.kt:70`).
    - `artwork()` (`playback/PlaybackService.kt:396-411`) decodes and re-encodes JPEGs on Main for every queue item,
      in `mediaItem()`.
    - The `Store` constructor parses the library on Main.
    - `SharingStarted.Eagerly` (:29-30) keeps the snapshot rebuilding every 15 s during playback, even with the app
      in the background.
    - `livePosition` (`ui/MusicRoot.kt:159-168`) wakes Main twice a second while the activity is stopped.
    - Fix:
      - add `flowOn(Dispatchers.Default)`;
      - make the art on the writer thread and set it with `replaceMediaItem`;
      - use `WhileSubscribed(5_000)`;
      - tick only while the activity is started.

12. **Low: the seek thumb jumps back after a drag, and TalkBack can't use the seek line.**
    - Where: `ui/Widgets.kt:161`. `dragFrac` is cleared on release, so the thumb shows the old position until the
      next 500 ms poll.
    - `:156`: the seek line has only a content description: no `progressBarRangeInfo` or `setProgress`.
    - Fix: hold the dragged value until the live position comes within 1 s of it (or 1 s passes), and add slider
      semantics.

13. **Low: the title and hint fade every 15 s while something plays.**
    - Where: `ui/LibraryScreen.kt:208`. `Crossfade(current)` compares the whole `EntryUi`, and its `progress`
      changes with each position save.
    - Fix: key the crossfade on `entry.id`.

14. **Low: Nytt rows add a play circle that mockup v12 doesn't have.**
    - Where: `ui/ListScreens.kt:139-141`. The locked mockup's row is a mini cover plus three lines.
    - Fix: drop the circle.

15. **Low: "Ikke lastet ned - trenger Wi-Fi" also shows for a stream that fails while on Wi-Fi.**
    - Where: `playback/PlaybackService.kt:615` (deviation 7). A 404 or 403 on Wi-Fi tells the kid to find Wi-Fi.
    - Fix: a second line for "can't play now" (one string, nb/en), or the neutral "Kan ikke spilles nå".

16. **Low: back from the player doesn't land on the playing entry.**
    - Where: `ui/MusicRoot.kt:72`. vibb's cat_carousel case 6, not ported, lands on the playing entry's category.
    - After playing from search or Nytt, the library shows whatever cover was centred before.
    - Fix: on back, select an index (and, if needed, a category) that shows the live entry.

17. **Low: `writeAtomic` uses a fixed temp name, so concurrent writers corrupt each other.**
    - Where: `data/Store.kt:81-89`.
    - `Sync.writeReport` runs from `SyncWorker` and from `DownloadWorker` at the same time
      (`sync/Downloads.kt:130`), and both write `.report.json.tmp`.
    - Fix: give each write a unique temp file (`createTempFile` in the same directory), or put a `Mutex` around
      the report.

18. **Low: the audio setup.**
    - `C.WAKE_MODE_NETWORK` (`playback/PlaybackService.kt:146`) holds a Wi-Fi lock while local copies play.
      Fix: set `WAKE_MODE_LOCAL` when the current item is a copy (part of the Jelly Star battery check).
    - `AUDIO_CONTENT_TYPE_MUSIC` (:144) for podcasts and stories makes ExoPlayer duck under a notification sound
      rather than pause, so words get lost. Fix: set `CONTENT_TYPE_SPEECH` per queue for non-music entries.

19. **Low: licensing and doc nits.**
    - NOTICE.md and the ported files credit vibb, but the MIT permission notice itself is in neither the repo nor
      the APK. `assets/licenses/` has only Apache-2.0 and OFL, and MIT asks for the notice in copies.
      - Fix: add vibb's LICENSE text, e.g. `music/app/src/main/assets/licenses/MIT-vibb.txt`, referenced from
        NOTICE.md.
      - This also covers the server's 1c port.
    - NOTICE.md says the music app's icon is "made by `scripts/material-symbols.sh`". The script doesn't write
      `ic_launcher_foreground.xml`, although that file's header attributes it correctly.
    - `src/debug/AndroidManifest.xml:4` and `DebugFeedReceiver.kt:18` name a `DebugFeedAbsentTest`, which doesn't
      exist. The check is `SourceScanTest` plus `checkReleaseHasNoDebugFeed`.

## Simplification

1. **The position "settle" is dead weight on ExoPlayer.**
   - The code: `settledPosition`, `holdAt`/`resumeTarget`/`resumeAt`/`userSeeked`, and the `KidPlayer.seekTo`
     override (`logic/NowPlaying.kt:24-28`, `playback/PlaybackService.kt:135-137,157,202-205,413-417`).
   - vibb needs it because mpv reports a stale position after a seek. ExoPlayer masks `currentPosition` to the seek
     target at once.
   - Drop it, and read `exo.currentPosition` directly.

2. **The "once per item per sync" machinery adds nothing.**
   - The machinery: the `sync_id` pref counter (`sync/Sync.kt:69-74`), the `sync` column, and `addItemError`'s
     per-sync rule (`logic/Report.kt`).
   - The `(entryId, itemKey)` primary key already keeps one row per item.
   - Keep the latest error per item (or the first until it clears, per L9) and drop the counter.

3. **Use one `CacheKeyFactory` per item instead of the global `hlsRoots` map** (`media/MediaCache.kt:41,51-58`).
   - Build the key from the item key plus the resource path without its query, relative to the playlist's directory
     when it is under it, else host plus path.
   - The download path creates its factory per `WantedDownload`. `GuardedMediaSourceFactory` creates one per
     `MediaItem` from its cache key (`customCacheKey` or `mediaId`).
   - This removes the mutable global, the registration calls, the collisions and the leak (L6).

4. **`playedFromTray` (`logic/NewItems.kt`) is unused in production.** `PlaybackService` calls `dao.deleteNews`, and
   only `NewItemsTest` calls `playedFromTray`. Use it, or delete it and test the DAO path's rule instead.

## Tests: what the ports cover, and the gaps that matter

A side pass compared every ported vibb test with its Kotlin port.
- These match vibb case for case: `prev_long_form`, `mpv_next_wrap`, `mpv_prev`, `offline_keep_all`, `cache_prune`
  1/2/4, `new_badge` 1-3/5-7, `now_playing` 1-3 and 8-11, and `resume_overlap` case 4.
- These production functions match vibb exactly: `recordPosition`, `resumeFrom`, `seekGate`, `rotateToBookmark`,
  `previousAction` and `nextAction`.

Gaps:
- `BookmarksTest` 2 and 3 exercise no production code: a map lookup, and a local variable in a loop. vibb's case 3
  (the top-level bookmark follows the last item) maps to `savePosition`, which nothing tests.
- The service wiring has no test at all. Two of the bugs above live there (L1, L2): `savePosition`, `markDone`,
  `queueEnded`, the AUTO-transition rules, and `onPlayerError`'s skip. Extract the decisions into pure functions
  ("what to write at an AUTO transition or at the queue end") and test them, including an item without a duration.
- vibb's `clear_state` at the queue end isn't ported or tested (L2).
- `new_badge` case 4: vibb clears NY per show on any play, while the port clears it per item. That follows 21 §4.5's
  tray on purpose, so it is fine, but the test name says "ports".
- `cat_carousel` case 6 is skipped (L16).
- `ui_carousel_slide` case 3 is tested as index arithmetic on target+1 only. On a touch screen, a tap on the cover
  sliding in plays it, which is reasonable, but untested.
- `now_playing` case 12's time bound on "playing" (vibb's 12 s grace) isn't ported. `showsPlaying` shows "playing"
  through a stalled stream until ExoPlayer gives up.
- The address guard is tested only as `isPrivateAddress`. Nothing tests that `PublicDns` and the network interceptor
  refuse a redirect or an IP literal to 127.0.0.1. A fake `Dns` and a local socket would do without new
  dependencies.
- Contract:
  - no test that an unknown field is tolerated (library, entry, file, listing, item);
  - no test that a malformed element prunes nothing (H1, M5).
- `testOptions.unitTests.isReturnDefaultValues = true` (`music/app/build.gradle.kts`) makes any Android call in a JVM
  test return 0 or null silently. The launcher doesn't set it. Drop it unless a test needs it.

## Deviations: verdicts

1. **Compose foundation only**: accepted. The carousel spec requires a `HorizontalPager`.
2. **Downloads with Media3's downloaders inside WorkManager, Room as the index, one HLS rendition**: accepted
   (QA #1/#2). It needs M1, M4, L6 and L7.
3. **The shuffle permutation as the stored queue**: accepted. The kid sees the same behaviour, and it survives a
   restart. A manual "next" at the last shuffled item wraps (vibb's rule) instead of 21 §4.2's "the queue ends after
   the last". That is fine.
4. **`artworkUri` to a ≤ 300 px JPEG, no episode art fetched**: accepted. Make the file off the main thread (L11).
5. **Bookmark and next kept even at depth 0**: accepted as written ("plus" in 21 §4.5 and 21b §6). A parent who
   picks "Ingen" may expect nothing on the phone (see the end).
6. **Queue end and wrap**:
   - The queue end hides the bar and drops the bookmark: accepted. It also has to drop the partial positions (L2).
   - "A tap on the loaded entry continues it": accepted for resume-on entries, rejected for resume-off ones (L4).
7. **Errors**:
   - The codes, once per item per sync, and at most 50: accepted.
   - "A 4xx waits for a new url": rejected (M1).
   - "The one message": rejected (L15).
8. **Address guard for NRK only**:
   - Accepted as an interim for step 2. The guard itself is right: the connected address is checked on every hop,
     with no TOCTOU.
   - RSS stays unguarded until the server sends `lan`, which needs a user decision (see the end). QA recommends
     adding it in step 3.
9. **`mobile_data` from the debug feed; "Ingen bibliotek" also when nothing can play**: accepted.
10. **A 20-dot window**: accepted. The mockup's equal dots are kept.

## Checked and fine

- **Address guard (21b §2.7)**:
  - `PublicDns` drops private answers.
  - The network interceptor checks `route().socketAddress`, the address actually connected (IP literals included),
    on every redirect hop.
  - `NO_PROXY` is set.
  - The public and LAN clients have different `Address` keys, so they never share pooled connections.
  - Downloads use the guarded HTTP factory directly.
  - `isPrivateAddress` matches 21b's ranges, including mapped and NAT64 addresses.
- **Release hygiene**:
  - The debug feed is only in `src/debug`, behind `DUMP`.
  - `checkReleaseHasNoDebugFeed` scans the manifest and dex, with a positive control on the debug APK.
  - The Google-services guard runs.
  - The main code has no logging, so no secrets reach logs.
  - Exported components are only the ones Media3 and the launcher need.
  - Custom commands are granted only to the app's own controller.
  - Remote `setMediaItems` can't inject URIs: the default `onAddMediaItems` and stripped local configurations.
- **Contract**:
  - The three shared files are byte-identical and compared by `ContractFilesTest`.
  - `ignoreUnknownKeys` is on.
  - `safeUrl` allows only http(s) without userinfo, at most 2000 characters.
  - Item keys are checked.
  - The report's `item_errors {entry, version, item, error}` ≤ 50, `downloads {entry, version, have, want,
    waiting}` ≤ 200 with `wifi`/`storage`/`roaming`, and `entry_errors` (`bad_listing`) match 21b §4.3. The report
    never holds positions.
- **Keep/prune core**:
  - It keeps the newest N, all for -1, none for 0, plus the bookmark and the next item in play order.
  - The bookmark is never deleted.
  - An entry without a listing keeps everything.
  - An unparsed library prunes nothing.
  - A `url: null` item without a copy is hidden, and a never-listed NRK/RSS entry is hidden.
  - A kept withdrawn copy plays from the stored URL and cache key, HLS variant included.
- **Resume, play order and queue**:
  - The natural order, and an explicit order reversing it.
  - Rotate to the bookmark, with the seek gated at 20 s.
  - A list, search or Nytt pick starts at its own position.
  - The stored queue comes back paused after a restart, and one tap continues it.
  - Positions are keyed by entry key + item key, never by URL.
  - Previous and next follow vibb exactly, long-form rule included.
- **NY/Nytt**:
  - The first listing acknowledges everything.
  - The tray holds at most 30, newest first, and is dropped with its entry.
  - An item leaves the tray when it starts by any path, and on "Fjern alle".
  - The notification is LOW, silent, `setNumber`, not ongoing, and cancelled when the tray is empty (apart from L5).
- **Player**:
  - ExoPlayer handles audio focus: a call is a transient suppression and resumes with a 3 s overlap.
  - Becoming-noisy pauses.
  - BT and notification prev/next go through `KidPlayer`, and the commands are advertised.
  - The sleep timer uses `elapsedRealtime`, fades over 10 s, pauses and saves, and a manual pause cancels it.
  - A failed stream of a listed URL goes to `item_errors`.
  - Nothing keeps the screen on.
- **FGS and jobs**:
  - The only FGS is the `mediaPlayback` one, which Media3 starts on playback.
  - The WorkManager jobs never call `setForeground`.
  - Constraints: UNMETERED, or NOT_ROAMING with mobile data, and storage not low.
  - The stream gate requires a physical transport and no roaming.
- **UI against mockup v12**:
  - The wrapping pager: 180 dp covers, 46 dp peeks (the scale plus translation works out), scale 1 → 0.733 and alpha
    1 → 0.45 by page offset.
  - The snap is a no-bounce spring of about 250 ms.
  - A neighbour tap turns from the target page, and a cover tap plays the landed entry.
  - "Remove animations" makes it jump, and a category change lands on index 0.
  - The dots are equal 8 dp, the current one peach.
  - The category tiles are single choice and scroll past four.
  - The header and buttons are 44 dp, and the list and Nytt rows at least 56/64 dp.
  - The seek line matches: a 4 dp track, a 26 dp thumb, 44 dp to touch.
  - The offline marks and "Alle lastet ned" / "N av M" are there.
  - Search: live hits, at most 12, "Fant ingenting".
  - The sheets match the mockup.
  - The edge-to-edge insets are applied once.
  - nb and en are complete with matching placeholders, and the escape scan passes.
  - At about 520 dp the library fits and the player cover shrinks, as specced.
- **CI (`music.yml`)**:
  - There is no trigger `paths:`; the diff-based `music-changes` filter includes the shared testdata.
  - The `music-ci` gate always runs.
  - It refuses tags off main.
  - versionCode is computed as the launcher's, an RC minus 1.
  - Signing happens only in the `release` environment, and the job checks:
    - the `RELEASE_CERT_SHA256` match and a single signer;
    - that the APK isn't debuggable;
    - the package name.
  - `makeLatest: false`.
  - Actions are pinned to the same SHAs as `launcher.yml`.
  - PRs use `pull_request` without secrets.
  - `launcher.yml` only triggers on `launcher-v*`.
- **Licensing**:
  - GPL-3.0 throughout.
  - The ported files' headers credit vibb (MIT).
  - Material Symbols: Apache-2.0 per-file headers, with the text in assets.
  - Nunito: OFL in assets.
  - NOTICE.md lists Media3, Room, WorkManager, Compose, OkHttp and kotlinx.serialization (apart from L19).

## Needs a user decision

1. **The LAN flag** (deviation 8): should the server add each entry's `lan` to the library entries, so the phone can
   guard public RSS feeds as well as NRK? It is a contract change in both directories. QA recommends yes, in step 3.
2. **Depth "Ingen" (0)**: should it still download the bookmarked episode and the next one (deviation 5, as 21/21b
   read today), or should 0 mean nothing on the phone?
3. **A cover tap on the playing entry when resume is off** (L4): start from the beginning as design 20 and the hint
   say (QA's reading), or continue?
