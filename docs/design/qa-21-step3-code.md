# QA 21 step 3 code review - the bridge, the courier, own files, updates (2026-10-10)

Scope: branch `music-step2`, step 3's commits 1dec4420 (launcher + music code) and 95336c34 (CI filters, docs), rebased
on main c6706834. Read against design 21 (§2, §3, §6 step 3, the QA review and its decisions, "Step 3 implementation
status" and its deviations), 21b §4/§5 with its decisions, design 22 §4 (`ServerConnection`, L1), the root,
`launcher/` and `music/` CLAUDE.md, and step 2's review (`qa-21-step2-code.md`; only what step 3 breaks there is
raised again). Platform claims were checked against AOSP sources (`UserManagerService` android15-release,
`DevicePolicyManagerService` android16-release, tower-http 0.7 `ServeFile`, Retrofit's built-in converters).
Paths: `L/` = `launcher/app/src/main/java/com/kidslauncher/mdm/`, `M/` = `music/app/src/main/java/me/vibb/music/`.

Build: a detached worktree of 95336c34.
- Launcher: `./gradlew --no-daemon testDebugUnitTest assembleDebug assembleRelease -PwarningsAsErrors=true` passed,
  743 tests (11 in `MusicPlanTest`), 0 failures (tsnet stub).
- Music: the same command passed, 123 tests (6 in `BridgeTest`), 0 failures; `checkReleaseHasNoGoogleServices` and
  `checkReleaseHasNoDebugFeed` ran, and the release APK is 4.7 MB.
- Server: `cargo test` passed, 432 tests (no server change in step 3).
- The AIDL files are byte-identical (`cmp`). No emulator here: nothing ran on a device.

Counts: 2 High, 5 Medium, 13 Low, 3 Simplification.

## High

1. **High: the managed config is never stored, so the music app never sees the launcher and the whole bridge is
   dead on a real phone.**
   - Where:
     - `L/music/MusicPlan.kt:135`: `storytel_generation` is a `Long`.
     - `L/music/MusicSync.kt:188` puts it with `putLong`, and `:173` passes the bundle to
       `dpm.setApplicationRestrictions`.
   - What the platform does with it:
     - A device owner (not the DMRH) takes DPMS's legacy path, `UserManager.setApplicationRestrictions`. The android16
       DPMS says so: "DMRH caller uses policy engine, others still use legacy code path".
     - `UserManagerService.writeBundle` (unchanged since Android 7) writes Boolean, Integer, String, Bundle,
       Parcelable[] and String[]. Anything else falls into the last branch, `(String[]) value`, and throws a
       `ClassCastException`.
     - `writeApplicationRestrictionsLAr` catches it, calls `failWrite` and logs "Error writing application
       restrictions list". Nothing is written.
     - `setApplicationRestrictions` still returns normally to the launcher and still broadcasts
       `ACTION_APPLICATION_RESTRICTIONS_CHANGED`.
   - What follows:
     - The music app reads `{}` (`M/bridge/Bridge.kt:32-38`), which means `UNMANAGED`.
     - `BridgeSync.pull` returns before binding (`BridgeSync.kt:30`), and `LiveBridge` and `push` find no launcher.
     - So there is no library, no listing, no own file, no Storytel login, no language and no mobile-data switch.
     - The device card shows "The launcher can't reach the music app (unbound)".
     - In the launcher, `sameBundle({}, desired)` (`MusicSync.kt:171`) is false at every sync. It writes again,
       which sends the restrictions broadcast plus a `BRIDGE_CHANGED` nudge, so every launcher sync starts a music
       sync.
   - Why the JVM tests can't see it:
     - `restrictionsFor` returns a Kotlin map, and `MusicPlanTest:119` pins `"storytel_generation" to 4L`.
     - `BridgeTest:37` feeds `3L` straight into `bridgeConfig`.
   - Fix:
     - Write the generation as an `Int`: it is a small counter, so clamp it to `Int.MAX_VALUE`. Or write it as a
       String. `bridgeConfig` already reads an `Int`.
     - Add a test that every value `restrictionsFor` produces is a Boolean, Int or String (the types the platform
       stores), and make `BridgeTest`'s fixture use those types.
     - Add a device check: after a sync, `dpm.getApplicationRestrictions` equals what was written.

2. **High: an own-file download started on Wi-Fi carries on over mobile data or while roaming.**
   - Where:
     - `L/music/MusicFileDownloads.kt:67` checks `downloadGate` once, before each file. The copy at `:133-136` then
       runs to the end of the body.
     - Nothing ever cancels the `Call`, although `MusicServer.kt:33` says "a blocking call, so the runner can cancel
       it".
     - `AppDownloads` does cancel: `startWatching` and `onNetwork` (`AppDownloads.kt:333-376`) cancel the in-flight
       call when the gate stops being GO.
   - Scenario:
     - A 600 MB audiobook (own files go up to 1 GB, server `MAX_FILE_BYTES`) starts on the home Wi-Fi in the morning.
     - The kid leaves the house, and Android moves to mobile data.
     - Over tsnet, today's only mode, OkHttp talks to the local SOCKS proxy. The WireGuard tunnel roams to the
       cellular path, and the TCP stream inside it survives.
     - So the download continues on mobile data with the parent's music mobile-data switch off. Abroad it continues
       while roaming, which the gate says never happens.
   - Deviation 1's "stops when the network stops qualifying" holds only when the socket breaks.
   - Fix:
     - Re-check the gate inside the copy: re-read `currentCaps`, the switch and the room about every 1 MB or every
       few seconds. When it isn't GO, stop, keep the partial and its ETag, and let the next trigger resume.
     - Or register a network callback for the run and cancel the `Call`, as `AppDownloads` does.
     - Renew the wake lock in the same loop (Low 1).
     - Test the loop's stop decision with a fake `MusicServer` body and a switching caps source.

## Medium

1. **Medium: a lost `releaseFile` blocks every later own file, for good.**
   - Where:
     - `L/music/MusicPlan.kt:116-117`: `nextOwnFile` returns null while any ready file exists.
     - `L/music/MusicBridgeService.kt:97-110`: `reportState` replaces the handed-over marks but leaves `ready` alone.
     - `M/bridge/BridgeSync.kt:75`: a file the app already holds is never opened or released again.
     - `L/music/MusicSync.kt:114-118`: `readyFilesToDelete` removes only unlisted or rehashed files.
   - Scenario:
     - The music app copies file A from the fd, which takes seconds to a minute.
     - Meanwhile the launcher's process dies: its own night update, an OOM kill or a crash.
     - The copy completes from the open fd. `releaseFile` then hits a dead binder, and `withBridge` swallows the
       `DeadObjectException`.
     - From the next sync on, A is in the app's `copies` and is skipped, and the report marks A as handed over.
     - But `ready` still holds A, so `nextOwnFile` is null for ever. No further own file downloads, and nothing
       reports it.
   - Deviation 3 says the report means "a stale 'ready' file can't block the one-ahead rule". The code doesn't do
     that.
   - Fix:
     - In `reportState`, delete the ready files whose (id, sha) the app reports as held, and drop them from `ready`.
     - Or have `nextOwnFile` ignore ready files that are handed over with the same hash, and have the sync delete
       them.
     - Test: a ready id that is handed over -> the next file.

2. **Medium: a deferred music update ends the whole download run, so every catalog download behind it waits, the
   launcher's own update included.**
   - Where:
     - `L/server/AppDownloads.kt:445`: `Outcome.STOP, Outcome.DEFER -> break`.
     - `:695-698`: the DEFER itself.
     - `L/server/AppDownloadPlan.kt:242`: `downloadOrder` puts every other app first and the launcher last.
   - Scenario:
     - The music app's update finishes downloading while the kid listens to an audiobook at bedtime. `install`
       returns DEFER, and the run stops.
     - Every later trigger picks the same record first: complete file -> `finish` -> hash -> DEFER -> break.
     - So no other app's download or install progresses, and neither does the launcher's own update download. That
       lasts as long as the music plays, up to the 6 h stale rule, so the self-update can miss its night window.
   - 21 §3 wants only the music app's install to wait.
   - Fix:
     - On DEFER, leave that record for the rest of the run: keep a `deferred` set that `next` skips, and continue.
       The pause's request retries it.
     - Test: two records, the music one deferred and the other one installing.

3. **Medium: `listingVersion` parses every listing on every call, so a music sync costs O(N²) JSON parsing in the
   HOME app's process.**
   - Where:
     - `L/music/MusicBridgeService.kt:122-125` calls `MusicStore.listings` (`MusicStore.kt:56-60`). That reads and
       fully parses every `items/<id>.json` (`listingVersionOf` -> `parseToJsonElement`) to answer one entry.
     - `M/bridge/BridgeSync.kt:55` calls it once per NRK/RSS entry in every music sync.
     - `MusicSync.fetchAll` (`:90`) parses all of them again at every launcher sync.
   - Scale:
     - Listings are up to 1 MB each (server `MAX_LISTING_BYTES`).
     - 30 entries of about 100 KB each make 3 MB per scan. 30 scans make 90 MB of JSON trees per music sync.
     - 100 entries of about 300 KB each make 3 GB per music sync.
     - The own-file chain runs one music sync per file (handover -> next download -> nudge -> sync), which multiplies
       this.
   - The cost: CPU, battery and GC churn in the launcher, which is the kid's Home.
   - Fix:
     - Keep an index from entry id to version, written next to each listing (`MusicSync` is the only writer), for
       example `items/index.json` or a prefs map.
     - Answer `listingVersion`, `listings()` and `listingsToDelete` from it.
     - Or put the version in the file name.

4. **Medium: a malformed `library_version` reads as "nothing ticked" and wipes the kid's downloads. A malformed
   `storytel_generation` deletes the login.**
   - Where:
     - `L/music/MusicPlan.kt:56`: anything but 16 hex becomes null.
     - `L/music/MusicSync.kt:63-66`: null deletes the library.
     - `:94-98`: with no head, it deletes the covers, the ready files and the partials.
     - `MusicPlan.kt:134`: null is written as `""` in the managed config.
     - `M/bridge/BridgeSync.kt:42-44`: `""` applies `EMPTY_LIBRARY`.
     - `M/sync/Sync.kt` then deletes every own copy, download, listing and Nytt row.
     - `MusicPlan.kt:59` does the same for `storytel_generation`: a non-number becomes 0, and the app deletes its
       login.
   - Scenario:
     - A later server sends a longer version or another type. The server and the launcher update at different times,
       and the launcher's update waits for its window. This is step 2's H1 class.
     - Every phone then deletes the kid's downloads.
     - That includes copies of NRK episodes the source has withdrawn (21b decision 2: kept, as on the Pi) and own
       files the server lists as `missing` (QA #3: the only copy left).
   - Per 21 §1.3, only JSON `null` means nothing ticked.
   - Fix:
     - Tell null apart from malformed. A present, non-null value that doesn't parse makes `musicPolicy` return null
       for the whole object, which means "keep what is there", as for an older server. Do the same for the
       generation.
     - Test both cases.

5. **Medium: the music courier's downloads run inside the sync lock, ahead of a parent's next lock or ring.**
   - Where:
     - `L/server/MdmSyncWorker.kt:72`: `syncMutex.withLock` covers the whole sync.
     - `:272`: `MusicSync` runs inside it, before `reportBlockedDnsEvents` and `commitPendingSelfUpdateIfDue` (`:282`).
     - `SyncRunner` coalesces requests that arrive during a run into one more run, and stops a run after
       `SYNC_TIMEOUT_MS` (10 min).
     - `MusicServer` has a 30 s read timeout per request and no call timeout.
   - Scenario:
     - Take the first fetch after the music app is installed: the library, every listing (up to 200, each up to
       1 MB) and every cover. Or take a big batch of new episodes over a slow tsnet path (a DERP relay, poor mobile
       coverage).
     - A `lock` or `ring` that arrives by SSE meanwhile is applied only after this fetch ends.
     - A fetch that runs past 10 minutes cancels the sync, so the self-update commit and the DNS events wait for the
       next one.
   - Wallpapers and contact photos sit in the same place, but they are a few MB. The courier can be tens of MB. 21 §3
     put `MusicSync` there, so the spec needs the same change.
   - Fix:
     - Keep the managed-config write (no network) in the sync.
     - Hand the fetch to its own one-at-a-time runner after the lock is released, as `AppDownloads` and
       `MusicFileDownloads` do.
     - Or give the fetch a time budget per sync (for example, no new request after 60 s), with the rest next sync.

## Low

1. **The own-file runner lacks two more of design 13's rules.**
   - A size or hash mismatch (`MusicFileDownloads.kt:172-179`) deletes the file, and the next trigger downloads it
     whole again, without limit. Files go one at a time in library order, so one server file whose bytes no longer
     match its SHA-256 (bit rot on the Pi's SD card) costs up to 1 GB at every sync and blocks every file after it.
     - Fix: count mismatches per (id, sha). After 2 (`MAX_HASH_FAILURES`), skip that file for a day and go on with
       the next.
   - The wake lock is taken for 10 min per file (`:72`) and never renewed. 1 GB at 10 Mbit/s takes 14 min.
     - Fix: renew it in the copy loop, every 60 s like `AppDownloads`.

2. **The "playing" flag that holds music updates back lives too long, or not long enough.**
   - Where: `MusicBridgeService.kt:188-200`, and `MusicPlan.kt:148-149`.
   - `playing_since` is set at the first "playing" and never refreshed: item changes keep it. After 6 h of
     continuous play (sleep music without a timer), the music update installs mid-play.
   - A player killed while playing (a crash, the OOM killer) never says "paused", so music updates wait 6 h.
   - Fix:
     - Measure staleness from the last `setNowPlaying` (`np_at`).
     - Clear "playing" when the `LiveBridge` token dies. `MusicForeground` already links that token.

3. **`PlaybackService.onDestroy` sends `setNowPlaying(null)` and wipes the snapshot that step 4's Home card shows.**
   - Where: `M/playback/PlaybackService.kt:186`.
   - 21 §4.6 clears it only at a queue's end, and §3 shows the last snapshot paused, "back at once after a restart".
   - Media3's default `onTaskRemoved` stops a paused service. `onDestroy` then sends null, and the launcher deletes
     the snapshot (`MusicBridgeService.kt:191-192`).
   - Fix: in `onDestroy`, send the current snapshot with `playing = false`, or nothing. Keep null for a queue's end.

4. **`LiveBridge` never retries a bind that didn't happen.**
   - Where:
     - `M/bridge/Bridge.kt:149`: `bind` returns when the config names no trusted launcher.
     - `:161-165`: there is one rebind, 2 s after `onBindingDied`, and a failed `bindService` isn't tried again.
     - Since `acquire` binds only on the 0 -> 1 step, `LiveBridge` stays unbound while the player service lives.
   - Scenarios:
     - The app starts before the launcher has written its config (the first open after a catalog install, Low 10).
     - A launcher update takes longer than 2 s.
   - Then `setNowPlaying` and `setForeground` never arrive. Updates aren't deferred, and in step 4 there is no
     screen-time lease and no Home card.
   - Fix:
     - Rebind on the restrictions-changed broadcast (`MusicApp` already receives it).
     - Retry a failed bind with a backoff (2 s, 10 s, 60 s) while `users > 0`.

5. **A deferred install leaves "Installing <app>" on screen while the music plays.**
   - `AppDownloads.kt:514` posts the ongoing notification before the download. The DEFER return (`:695-698`) never
     cancels it, so it stays in the kid's shade for the whole listening session.
   - Fix: call `notifyAppInstallResult(..., success = true)` before returning DEFER.

6. **A nudge cancels a running music sync.**
   - Where:
     - `M/sync/Sync.kt:60`: the nudge uses `REPLACE`, which cancels running work too.
     - Step 3 nudges whenever an own file is ready (`MusicFileDownloads.kt:182`). That is seconds after the running
       sync released the previous file, so during a chain of small files the sync is cancelled after its bridge
       part, and its download planning and report don't run.
     - `runCatching` around `pull` and `push` (`:76`, `:193`) swallows the `CancellationException`, so a cancelled
       sync carries on until its next suspension point.
   - Fix:
     - Use `APPEND_OR_REPLACE` while a sync runs (as `Downloads.enqueue` does).
     - Use try/catch that rethrows `CancellationException`.

7. **Any app on the phone can pause the music, or keep the music app from syncing.**
   - Where: `M/MusicApp.kt:50-65` and the manifest (`:57-73`).
   - `SuspendedReceiver` is exported and acts on any explicit intent, whatever its action.
   - `BRIDGE_CHANGED` can be sent by anyone. With `REPLACE`, one every 10 s pushes the 15 s settle out for ever.
   - Fix:
     - `SuspendedReceiver`: `exported="false"`. The system still delivers `MY_PACKAGE_SUSPENDED`. Or check the
       protected action.
     - Nudge: the launcher sends with `BroadcastOptions.setShareIdentityEnabled(true)`, and the receiver checks
       `sentFromPackage` against the config's launcher (API 34 is minSdk). Plus Low 6's append.

8. **The library and listing GETs lack `@Streaming` (`MusicServer.kt:38-42`).**
   - Retrofit's buffering converter reads the whole body into memory before `read(it, MAX)` applies the 4 MB / 2 MB
     cap. So the cap guards the disk, not the HOME app's heap.
   - Fix: `@Streaming` on both, as `cover` and `file` have.

9. **One network error aborts the rest of the music fetch and loses its nudge.**
   - Where: `MusicSync.kt:41-50`, `fetchAll`.
   - A timeout or reset on one listing skips the remaining listings and all covers for this sync.
   - `changed`, already true for what was written, is lost, so no nudge goes out.
   - 21b §5 retries per listing.
   - Fix: catch `IOException` per request (log and continue), and return `changed` from what was written.

10. **The first run after a catalog install waits for the next backstop sync, and for the kid to open the app.**
    - `Application.kt:54-70` requests a sync on a new package only when it is blocked, and `MusicSync` runs only
      with a music app installed (`MusicSync.kt:38`). So the library fetch and the managed config wait for the next
      sync, up to 30 min.
    - The nudge (`:208`) has no `FLAG_INCLUDE_STOPPED_PACKAGES`. A just-installed, never-opened app doesn't get it,
      so the own-file chain stops at the first file until the kid opens the app.
    - Fix:
      - Request a sync when a `MUSIC_PACKAGES` package is added.
      - Add the flag to the nudge.
      - Device check on 14/15.

11. **A fresh process can read the wrong mobile-data switch.**
    - `Store.restrictedMobileData` is null until `MusicApp.applyConfig`'s coroutine has run.
    - A download job or a stream in a cold process can read the debug pref (false). The job then re-enqueues with
      NOT_METERED although the parent allows mobile data, until the next sync.
    - Fix: Simplification 2.

12. **The launcher keeps its music files after the music app is uninstalled.**
    - `MusicSync.kt:38` returns before any cleanup. `filesDir/music`, the `music_bridge` prefs and a ready own file
      in `noBackupFilesDir/music_files` (up to 1 GB) stay.
    - Fix: delete them once when no music app is installed.

13. **Every phone without the music app says "The phone hasn't reported on music yet."**
    - The launcher now sends `music_v1` from every phone and no `music_state` without the music app
      (`MusicBridgeService.kt:250-254`).
    - The server's card (`handlers/music.rs`, `None if capable`) then shows that line.
    - Fix (server): "Music app not installed" when `installed_apps` lacks it.

## Simplification

1. **Drop `wantFiles`.**
   - Deviation 3's `held_files` already replace the handed-over marks at every report, which also clears the marks
     for files the app lacks.
   - Remove `wantFiles` from both apps and from the AIDL. Nothing is released yet, so the transaction codes may
     still move.

2. **Read `mobile_data` live.**
   - `Store.restrictedMobileData`, `MusicApp.applyConfig` and the first lines of `BridgeSync.pull` keep a copy of one
     restriction, with two writers and a start-up race (Low 11).
   - Read it in the `mobileData` getter (one `RestrictionsManager` call). Apply the language in one place.

3. **Ask for a download run on a pause only when an install waits.**
   - `MusicNowPlaying.store` starts the anchor service's download run at every pause (`:207`).
   - Set a flag when DEFER happens, and request the run only while it is set.

## Tests: gaps that matter

- **The managed config's value types** (High 1): no test checks that `restrictionsFor` yields only Boolean, Int or
  String, and the two sides' fixtures use a `Long`.
- **`MusicFileDownloads`' wiring** with a fake `MusicServer`: 200/206/412/416/404, the hash, resume across runs, and
  the in-flight gate (High 2). Only design 13's pure rules are tested.
- **A handed-over ready file** (Medium 1), **DEFER lets the next record run** (Medium 2), **malformed versus null**
  in `musicPolicy` (Medium 4).
- **A source scan** that every `IMusicBridge` override calls `check()` first. Today only the pure `callerAllowed` is
  tested, so a method appended later could skip it.
- **The cross-app constants** in one test:
  - `BRIDGE_SERVICE` (the launcher's class name), `ACTION_BRIDGE_CHANGED`, `MUSIC_PACKAGES`/`LAUNCHER_PACKAGES`;
  - the restriction keys;
  - the Bundle keys of `reportState` (`report`, `held_files`, `held_sha256`) and `setNowPlaying`.

  Each is spelled twice and compared nowhere. Extend the path filters to whatever file the test reads.
- **`music_state` end to end**: one shared fixture (for example `server/testdata/music_state.json`). The music app's
  `stateReport` keys must be in it, the launcher's `musicState` must keep them, and the server's
  `sanitize_music_state` must keep them all. Today each side pins its own hand-written list.

## Deviations: verdicts

1. **Own-file runner of its own: accepted.**
   - Design 22 L1 replaces one interface, and music files need no installs or release tags.
   - It dropped three of design 13's rules it claims to use: the in-flight gate (High 2), the hash-failure limit and
     the wake-lock renewal (Low 1).
   - Waiting for the next sync when the catalog runs is fine.
2. **`MusicServer`: accepted.**
   - Three call sites build it from `createMusicServer`, and L1 changes that one function.
   - Paths are relative, so the bearer never leaves the server's host. OkHttp drops `Authorization` on a cross-host
     redirect, and L1 adds `followRedirects(false)`.
3. **`held_files` in the report: accepted.** It is the better mechanism and makes `wantFiles` redundant
   (Simplification 1). Its claim about stale ready files isn't true yet (Medium 1).
4. **A nudge only on change: accepted.** Until High 1 is fixed, `restrictionsChanged` is true at every sync.
5. **Nothing ticked -> `""` -> an empty library: accepted** as the parent's explicit choice. Only for a JSON `null`,
   never for a value the launcher can't parse (Medium 4).
6. **The managed config written in `MusicSync`, not `apply()`: accepted.** One writer, and `apply()` stays
   untouched. Fix the value type (High 1).
7. **`setForeground`/`setNowPlaying` stored now, read in step 4: accepted.** Fix Lows 2-4 before step 4 builds on
   them.
8. **The local aarch64 `aidl`: fine.** It isn't in the repo, and CI is unaffected.
- Also: `listingVersion`/`openListing` are appended per 21b §5, and the version compare is `!=` (QA #7, 21b #20).

## Checked and fine

**The bridge's access control and what a malicious app can do**
- The permission is `${applicationId}.permission.MUSIC_BRIDGE`, at signature level, defined by the launcher and
  required by the exported service.
- The music app requests both build names' permission and has `<queries>` for both launchers. It binds only the
  config's launcher, and only after `hasSigningCertificate` (SHA-256 of all its own signers, lineage-aware).
- `check()` runs first in all 12 methods. `getCallingUid` is valid in oneway calls too. The caller must be one of
  the two music names.
- Debug and release can't pair:
  - CI and local debug builds have debug keys, so `me.vibb.music.debug` never gets a release launcher's signature
    permission, and `sameSigner` refuses the other way too.
  - The release launcher's `callerAllowed` accepting `.debug` would matter only for an APK signed with the release
    key.
- Another app can't define the permission first: the launcher is installed at provisioning, and Android refuses a
  duplicate definition from another signer.
- The rollout order (launcher before any `music-v*`) means the definer is always installed before the music app.
  Worth one device check: install the music app first, then the step-3 launcher.
- A malicious app can't bind. It can reach only the two receivers (Low 7) and the step-2 media session.

**Files, fds, the Storytel login and Bundles**
- Read-only pfds, and inputs can't escape their directories: the cover hash matches the regex, and ids are `Long`s.
- An AIDL return value is written with `PARCELABLE_WRITE_RETURN_VALUE`, which closes the launcher's fd. The music app
  closes its side through `AutoCloseInputStream`.
- An fd opened before a rename keeps reading the old file, which stays consistent.
- The Storytel login:
  - the launcher fetches it only when the generations differ (`!=`), answers through the checked caller's callback,
    keeps it in memory only, and logs only exception class names;
  - a 404 or generation 0 deletes it on the phone;
  - the phone keeps it under AES-GCM with a Keystore key, a random IV and AAD `storytel-v1`, in noBackupFilesDir,
    never logged;
  - it is never in the managed config, a status report or a pref.
  - A 503 answers nothing, and the app waits its 20 s timeout. That is acceptable.
- Bundles come only from a same-signer caller:
  - strings are cut to 200/300 characters, and art is dropped above 64 KB;
  - `held_files`/`held_sha256` must have equal lengths;
  - the 1 MB binder limit bounds the report string; the app sends at most 50 errors and 200 download rows.
  - `held_files` costs about 144 bytes per file, so the oneway buffer only becomes a limit past roughly 3,000 own
    files (about 15 GB on the phone).
- An unexpected `RuntimeException` in a stub is logged by `Binder`/JNI and doesn't crash the launcher.

**Threading and the courier's fetches**
- The binder methods run on binder threads, never main. Compound updates of `ready` happen only while no download
  runs, because of the one-ahead rule. Oneway calls to one binder are serialized. `LiveBridge` stays on the main
  thread with oneway calls.
- The library and the listings:
  - the `If-None-Match` quoting matches the server's `"<version>"`, and a 304 is handled;
  - gzip is transparent, and the caps count decoded bytes;
  - `v == 1` is required;
  - any listing version is stored (21b QA #20), and listings the library no longer names are deleted;
  - covers are hash-checked before they are stored;
  - writes are atomic (temp file + rename), and a stale temp in `covers/` is swept.
- QA #3: the launcher never downloads a `missing` file and keeps its ready copy. The music app keeps copies of files
  still named, and a refused library or listing keeps the old one.
- Design 13's response rules are applied correctly in `MusicFileDownloads`: 200 truncates, a 206 at the partial's end
  appends, 412 restarts, 416 counts as complete or restarts, 404 stops (the server then marks the file `missing`, so
  the next library skips it), and a 5xx retries at the next trigger. tower-http 0.7 sends a strong ETag and honours
  `If-Match`. The room check is design 13's (twice the file plus 100 MB), which is right for the copy the music app
  makes.

**Status, the sync hooks and updates**
- `music_state`:
  - only known keys are copied, and the server sanitizes them again;
  - `music_v1` is in `STATUS_CAPABILITIES`;
  - a malformed `music_state` drops only itself on the server (`Option<Value>` -> `sanitize_music_state` -> `None`),
    never the status report;
  - `version_code` is the package manager's;
  - `item_errors` and `downloads` match 21b §4.3 and the server's structs.
- The `MdmSyncWorker` hooks are wrapped, rethrow `CancellationException` and never touch policy acceptance.
  `PolicyResponse.music` stays raw and nullable, and an older server (`music: null`) leaves the config alone.
- The fallback policy has no `music` object, so it leaves the config alone. A phone that has never had a policy
  clears it.
- DEFER applies only to the music packages. The launcher's own update goes through `storeSelfUpdate` with no gate,
  as 21 §3 says. A bound service only disconnects on the launcher's update; `withBridge` turns a dead binder into a
  `RemoteException`.
- The restrictions-changed receiver is `RECEIVER_NOT_EXPORTED`, and the system's broadcast still arrives (system
  uid). `MY_PACKAGE_SUSPENDED` is a protected broadcast delivered to manifest receivers.
- Language: `resolveLauncherLocale` gives `nb`/`en`/`""`, and the app accepts exactly those.

**CI**
- `launcher.yml` builds on exactly the music files `MusicPlanTest` reads: both AIDL files, the music manifest and the
  two snapshots.
- `music.yml` builds on the launcher's AIDL, which `BridgeTest` reads.
- The gate jobs are unchanged.

## Needs a user decision

None.
