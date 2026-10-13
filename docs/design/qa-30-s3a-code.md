# QA 30 step 3a (code) - the music app reads the two mobile-data switches and the limit; audio_bytes/extra_bytes

Reviewer: code QA, 2026-10-13. Branch `design-30-s3a`, `777e4434..1c95b241` (one commit, `1c95b241`). Read against
`docs/design/30-music-streaming-data.md` (§1.1, §1.3, §1.4's Sonos paragraph, §1.6's upstream gate, §1.10, §5, §6,
§8, the Design QA section and the step 1, 2 and 3a status sections), `qa-30-design.md`, `qa-30-s1-code.md` (on
`design-30-s1`), and the root, music, launcher and server `CLAUDE.md`.

I also read the code around the step:
- music: `bridge/{Bridge,BridgeConfig}.kt`, `data/Store.kt`, `logic/{NetGate,Report}.kt`,
  `sync/{Downloads,Sync}.kt`, `playback/PlaybackService.kt` (`start`, `playable`, `playableFrom`, `onItemChanged`,
  `onError`), `MusicApp.kt` (the restrictions-changed receiver), `debugfeed/DebugFeedReceiver.kt`;
- launcher: `music/MusicPlan.kt` (`musicPolicy`, `restrictionsFor`, `musicState`), `music/MusicSync.kt`
  (`writeRestrictions`, `restrictionsBundle`, `nudge`);
- server: `music.rs` (`MusicState`, `sanitize_music_state`, `older_phone_note`, `storage_line`),
  `handlers/music.rs` `device_card`, `handlers/device_api.rs` (where `music_state` is stored), and the CI path
  filters in `launcher.yml` and `music.yml`.

Line numbers are `1c95b241`'s. No product code was changed. The mutations below were temporary, each was reverted
with `git checkout -- <dir>`, and `git status` was clean before this file was written.

**Verdict: ready to merge.** The switches are read as §1.3 says, and every gate caller listed in §5.2 asks the right
switch. The report fields go through all three parts and are clamped. The card's line and note now get their data.
Every mutation of the step's own rules went red but one, and that one is in the debug-only unmanaged path (L2). The
two Lows can go into step 3b's sitting. L1 is older than design 30 and isn't a 3a regression.

Counts: 0 High, 0 Medium, 2 Low, 2 Nits, plus notes.

## What I ran (under the shared build lock, `--no-daemon --max-workers=2`)

- **Unchanged `1c95b241`:**
  - music `testDebugUnitTest -PwarningsAsErrors=true`: **621 tests, 0 failures**;
  - server `cargo test`: **671 passed, 0 failed**.

  The implementer's launcher run (1079 tests) and the music `assembleRelease`/`lintDebug` weren't repeated. The
  launcher change is a key list and one test, and the mutation run below compiled and ran `MusicPlanTest` (33
  tests).
- **Mutation runs.** Each mutation was a temporary product-code edit, run against the test classes named, then
  reverted. Mutations that shared a run had disjoint target tests or assertion order, so each red test belongs to one
  mutation. Where they didn't, I ran them again separately.

  | Mutation | Tests run | Result |
  |---|---|---|
  | M1 `mobileSwitch`: a present non-Boolean falls back to `mobile_data` (`(values[key] as? Boolean) ?: ...`) | `BridgeTest`, `NetTest`, `ReportTest`, `GuardsTest`, `OfflineTest` | red: `an older launcher's mobile_data sets both switches ...` |
  | M2 `storageLimit`: any `Number` counts (a `Long` 5 -> 5) | (same run) | red: `the storage limit - 0 is none, junk is 2 GB` |
  | M4 `Store.unmanagedSwitch` without the old `mobile_data` fallback | (same run; no other test reads `Store`) | **survives** (L2) |
  | M6 `downloadAllowed` ignores its switch (`networkAllows(caps, true)`) | (same run) | red: `NetTest` `downloads follow the downloads switch only`, `downloads wait for Wi-Fi, storage or the end of roaming` |
  | M7 `stateReport` sends `extra_bytes` only when > 0 | (same run) | red: `ReportTest` both report tests, `BridgeTest` the fixture key test |
  | M8 `onError`'s offline message asks `store.downloadMobileData` | (same run) | red: `GuardsTest` `the stream switch is read live from the managed config` |
  | M5 `Downloads.enqueue`'s constraint swapped (`UNMETERED` with the switch, `NOT_ROAMING` without) | `GuardsTest`, alone | red: the same `GuardsTest` test |
  | Launcher: `musicState` without `extra_bytes` | `MusicPlanTest` | red: `music_state passes audio_bytes and extra_bytes`, `music_state end to end ...` |
  | S1 `device_card` passes `None` as `older_phone_note`'s `extra_bytes` | server `cargo test music` | red: `the_card_says_an_older_phone_uses_mobile_data_only_with_both` |
  | S4 `sanitize_music_state` drops `audio_bytes` | (same run) | red: `music_state_keeps_every_field_the_phone_sends`, `music_state_clamps_audio_and_extra_bytes` (its `audio_bytes` assert) |
  | S2 `extra_bytes` not clamped | `cargo test music`, with S3 | red: `music_state_clamps_audio_and_extra_bytes` |
  | S3 `device_card` passes `None, None` to `storage_line` | (same run) | red: `the_storage_line_shows_the_limit_and_the_extras` |

## The questions in the brief

- **The music app's reading against the launcher's writing.**
  - The launcher's `restrictionsFor` (`MusicPlan.kt:226-238`) writes the two switches as Kotlin `Boolean`s and
    `storage_limit_gb` as an `Int` (`storageLimitRestriction`, 0 = none).
  - `restrictionsBundle` (`MusicSync.kt:131-140`) puts only `Boolean`, `Int` and `String`, and throws on anything
    else, so no `Long` can be written.
  - Launchers from before step 2 write only `mobile_data`, as a `Boolean`. Then `mobileSwitch` (`BridgeConfig.kt:109`)
    finds neither key and falls back to it for both switches, and the limit reads absent = 2 GB.
  - A step 2+ launcher with an older server fills both switches from `mobile_data` itself (`musicPolicy`) and writes
    `storage_limit_gb` = 2.
  - Every case in §1.3's table holds. A present non-Boolean is off (`== true`) and a `Long` limit is junk = 2 GB.
    Both are exactly §1.3's rule, and both are pinned (M1, M2).
- **The debug feed on an unmanaged phone.**
  - `Store` reads the feed's prefs only when `Bridge.config` isn't managed.
  - A switch the feed never set falls back to the old `mobile_data` pref, so an emulator fed before this step keeps
    its setting.
  - In `DebugFeedReceiver.kt:76-82`, a bare `mobile_data` sets both, and a key of its own is applied after it and wins.
  - `storage_limit_gb` 0 is stored as 0 and read back as none. A negative or > 1000 value reads 2.
  - It works, but nothing pins it (L2).
- **`NetGate` and its callers.** `streamAllowed`/`downloadAllowed` are one private formula with internet, a physical
  transport, not roaming, and NOT_METERED unless the switch is on. Every caller is listed below.
  - Streaming switch:
    - `playable` (`PlaybackService.kt:703`), used by `start` (`:672`), `onItemChanged` (`:1056`) and `onError`'s
      next item;
    - `playableFrom` (`:711-714`);
    - `onError`'s offline message (`:1166`).
  - Downloads switch:
    - `Downloads.enqueue`'s constraint (`Downloads.kt:77`: `NOT_ROAMING` with the switch, `UNMETERED` without);
    - the worker's per-item check and its two re-enqueues (`:149-151`, `:166`);
    - the sync's two enqueues and the report's `waiting` (`Sync.kt:185-187`, `:244`).
  - `GuardsTest` pins that the player never names the downloads switch and that the sync and the worker never name
    the streaming one.
  - Storytel books go through the same worker, so they follow the downloads switch. Roaming refuses both.
- **Does the job follow a changed switch?** Mostly, yes. The launcher nudges after any restrictions change
  (`MusicSync.kt:51`, `:87`), and the music app also nudges on `ACTION_APPLICATION_RESTRICTIONS_CHANGED`
  (`MusicApp.kt:38-41`). The sync then calls `Downloads.enqueue` with the new switch whenever something is wanted. A
  waiting job is `REPLACE`d with the new constraint, so it starts at once when the switch goes on, and waits for Wi-Fi
  when it goes off. A **running** job is only appended to. That is L1.
- **The report fields across the three parts.**
  - `server/testdata/music_state.json` has both fields. The music app keeps **no copy** of it: `ContractFilesTest`
    covers the library, listing and icons fixtures only. `BridgeTest` and `MusicPlanTest` read the server's file
    directly (`repo("server/testdata/music_state.json")`).
  - Both CI path filters already name it (`launcher.yml:72`, `music.yml:64`).
  - The server keeps and clamps both fields like `cache_bytes` (`music.rs:1615-1616`).
  - The launcher's `musicState` key list passes both, 0 included (`takeIf { it !is JsonNull }`).
  - `device_card` passes the report's values to `storage_line` (`handlers/music.rs:3560`) and to `older_phone_note`
    (`:3619`).
  - Each of these is pinned (the launcher mutation, S1-S4, M7).
- **Sonos.** A room fetches the URL over its own network (26a), so the phone's mobile data isn't involved. `playable`
  returns true on a Sonos renderer (`PlaybackService.kt:705`, "the phone's gate is the phone's", 26a §4.2), and the
  Sonos queue holds only what the room can play. The spec agrees: §1.1's stream row names the phone's
  `streamAllowed`/`playability`/upstream gate, and §1.4 says nothing passes through the phone for a room. The
  streaming switch correctly doesn't apply there, and the step leaves that alone.
- **Tests that pass without the code.** Only the unmanaged/debug path (L2). Everything else went red (see the table).

## Low

### L1. A running download keeps fetching its current item over mobile data after the parent turns the downloads switch off

- **Where.**
  - `Downloads.enqueue` (`Downloads.kt:84-87`) uses `APPEND_OR_REPLACE` while a job runs.
  - The worker checks `downloadWaiting` only **between** items (`:149`).
  - Nothing checks the gate during `Downloads.fetch`, and the running job's own constraint is `NOT_ROAMING`, which
    mobile data still meets.
- **Scenario.**
  1. Downloads may use mobile data. The kid is on 4G, and the job is fetching a 600 MB Storytel book.
  2. The parent sees the data use and turns "Downloads may use mobile data" off.
  3. The launcher writes the config and nudges, and the sync runs 15 s later. It appends a job with `UNMETERED`.
  4. The running job finishes the whole book over 4G before its next per-item check stops it.

  The card's text ("a Storytel book is often several hundred MB") makes this the switch's main use. The same was true
  of the one switch before design 30 (design 21's per-item check), so this is not a 3a regression. The spec's §1.6
  "the job has its own (`downloadWaiting`)" accepts per-item granularity without saying so.
- **Fix (small).** Give `Downloads.enqueue` a `stopRunning: Boolean = false` and have the sync pass
  `!store.downloadMobileData`. With it, a running job is `REPLACE`d, not appended: WorkManager stops it, and the new
  job waits for `UNMETERED`. On Wi-Fi it starts again at once and resumes the partial with Range (`CacheWriter`), so
  nothing is lost. The worker's own re-enqueues keep `APPEND_OR_REPLACE`, so it never cancels itself mid-report. Pin it
  in `GuardsTest`'s switch test. Alternatively, check `downloadWaiting` from `fetch`'s `ProgressListener` every few MB,
  as the launcher's `OwnFileFetch` does every 1 MB. Either way, say in §1.1 that a switch turned off stops a running
  download within the sync's settle.

### L2. The unmanaged (debug feed) reading is unpinned (M4 survives)

- **Where.**
  - `Store.unmanagedSwitch` (`Store.kt:87-88`) and `Store.storageLimitGb`'s unmanaged branch (`:77-84`). `GuardsTest`
    pins only the getter lines that call them, not their bodies.
  - `DebugFeedReceiver.kt:76-82`, where the order makes a key of its own win over a bare `mobile_data`. It is in the
    debug source set and no test reads it.
- **Scenario.** A later edit drops the `mobile_data` fallback (M4), or swaps the receiver's two blocks. An emulator
  that was fed `{"mobile_data": true}` before this step then silently streams and downloads on Wi-Fi only. §8 checks
  2-4 and 11 then fail on the emulator for a reason that has nothing to do with the code under test, and no unit test
  goes red. It is debug-only, so no kid's phone is affected.
- **Fix.** Reuse the pure rules on the prefs instead of a second copy:
  - `unmanagedSwitch(key) = mobileSwitch(prefs.all, key)`. The feed's prefs use the same key names, `mobile_data`
    included.
  - The unmanaged limit = `storageLimit(prefs.all)`.

  Pin both calls in `GuardsTest`'s switch test. Then `BridgeTest`'s existing cases cover the unmanaged rule too. For
  the receiver, pin that the `stream_mobile_data` line comes after the `mobile_data` block (`indexOf` order), as
  `GuardsTest` already does for other orderings.

## Nits

- **N1.** `Downloads.kt:49` (the file header) still says "NOT_METERED unless the parent's mobile-data switch is on".
  It should say the **downloads** switch, as `NetGate.kt`'s header does now.
- **N2.** `DebugFeedReceiver.kt:82` maps 0 to `null` (`takeIf { gb -> gb != 0 }`), and the setter maps `null` back to
  0 (`Store.kt:84`), so the round trip does nothing. `store.storageLimitGb = it` reads the same. Also, the end of
  `the_card_says_an_older_phone_...` (`tests/music.rs:1416-1426`) would stay green if the 3a report were refused (a
  refused `music_state` is stored as NULL, and no app report means no note). Asserting the 3a storage line in the same
  card would show that the report landed. S1 is red today, so this only guards against a future sanitize regression.

## Notes (no action in 3a)

- **The streaming switch is exact only from 3b.** The player asks the gate when an item starts (`start`,
  `onItemChanged`). Two things are still possible in 3a, as before design 30:
  - a stream that started on Wi-Fi keeps streaming over mobile data after Wi-Fi drops;
  - ExoPlayer preloads the next item's stream before `onItemChanged` refuses it.

  §1.6's `StreamGate` (3b) closes both. Until then, §8 check 3 holds at item starts only.
- **Step 1's fix round isn't on this branch.** `8f55d2b7` (qa-30-s1-code L1-L2: `gigabytes_up`, the old page's event
  pin) is on `design-30-s1` only. `git merge-tree` of `design-30-s1` into `1c95b241` merges the code cleanly and
  conflicts only in the spec's status sections. 3a's new card assertion ("3.2 GB of the 2 GB limit") is an exact
  3,200,000,000 bytes, so it reads the same with `gigabytes_up`. Merge `design-30-s1` before 3b, or at the merge to
  `main`.
- **Release order.** The older-phone note reads "no `extra_bytes`" as "an older phone". A launcher released with step
  2 but without 3a's `musicState` key list would drop the field and show the note on a phone that reads both switches.
  That is the same rule as qa-30-s1-code L3/L4: no `launcher-v*` tag from a commit with step 2 but not 3a.
- **The implementer's interpretations** (step 3a status, "Interpreted"). I agree with all six:
  - `audioBytes`/`extraBytes` as required parameters: the compiler finds every caller.
  - A present non-Boolean switch is off, and a `Long` limit is 2 GB: this is §1.3's music-app rule word for word, and
    the launcher can't write either (above).
  - The unmanaged fallback to the old pref: right for emulators fed before this step. It needs a pin (L2).
  - `Store.storageLimitGb` unused until 3b: fine, since 3a has nothing to evict.
  - A non-numeric `audio_bytes`/`extra_bytes` refuses the whole report: the same rule as `cache_bytes`, and the app
    always sends numbers.
  - In 3a the warning reads the whole audio cache as kept: true while `extra_bytes` is 0. The limit never touches the
    keep set, so the warning ("lower an entry's offline depth or raise the limit") is the right advice.

## Fixes (implementer, 2026-10-13, branch `design-30-s3a`)

| Finding | Fix | Test (red without the fix) |
|---|---|---|
| L1 a running download goes on over mobile data after the downloads switch turns off | `logic/NetGate.kt` `stopRunningDownload(caps, downloadMobileData)` = `!downloadAllowed(...)`. The sync passes it as `Downloads.enqueue(..., stopRunning)`, and `enqueue` then `REPLACE`s a running job instead of appending: WorkManager stops it, the new job waits for `UNMETERED`, and the partial resumes with Range. The worker's own re-enqueues never pass it. A refinement of the QA's `!store.downloadMobileData`: the job is stopped only where the network now forbids downloads, so a sync never interrupts a Wi-Fi download. §1.1 says so. | `NetTest` `a running download stops where the downloads switch now forbids it` (mutation: always `false` -> red); `GuardsTest` `the stream switch is read live from the managed config` pins both sync calls, `enqueue`'s `!stopRunning &&` and the worker without it (mutation: `enqueue` ignores `stopRunning` -> red) |
| L2 the unmanaged (debug feed) reading is unpinned | `Store.unmanagedSwitch(key)` = `mobileSwitch(prefs.all, key)` and the unmanaged limit = `storageLimit(prefs.all)`: the managed config's rules, which `BridgeTest` covers | `GuardsTest` (the same test) pins both calls and the feed's order: a bare `mobile_data` setting both, before the keys of their own. Your M4 (`prefs.getBoolean(key, false)`) -> red; the feed's blocks swapped -> red |
| N1 `Downloads.kt`'s header names the one old switch | names "downloads may use mobile data" (design 30 §1.1) | - |
| N2 the 0 -> null -> 0 round trip; the older-phone test can't tell a refused report | the feed stores `storage_limit_gb` as given (`store.storageLimitGb = it`, pinned); `the_card_says_an_older_phone_uses_mobile_data_only_with_both` asserts the 3a report's line ("Stored on the phone: 1.0 MB. Music downloads: 0.9 MB of the 2 GB limit.") next to the missing note | the server test itself |

Runs (under the build lock, `--no-daemon --max-workers=2`; cargo with its own target dir, `server-target-d30-3a`):
- music `assembleDebug assembleRelease testDebugUnitTest lintDebug -PwarningsAsErrors=true`: 622 tests, 0 failures;
- launcher `assembleDebug testDebugUnitTest -PwarningsAsErrors=true`: 1079 tests, 0 failures;
- server `cargo fmt --check` clean, `cargo clippy --all-targets` 13 warnings (none in this step's code, unchanged),
  `cargo test` 671 passed;
- the three mutations above, each in its own run of `GuardsTest` + `NetTest`, all red, then restored.
