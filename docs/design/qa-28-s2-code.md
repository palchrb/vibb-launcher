# QA 28 step 2 (code) - the listening window's rules, the stamp feed and the headset watch

Reviewer: code QA, 2026-10-13. Branch `design-28-s2` (`84e29547..f0b83ffd`, 1 commit), against
`docs/design/28-music-card-session.md` (§2-§4, §6, §7 step 2 and its "Step 2 implementation status"), its design QA
`qa-28-design.md`, the root and launcher `CLAUDE.md`, and the code the step builds on: `MusicBridgeService.kt`
(`MusicNowPlaying.store`/`playerGone`, the oneway AIDL), `MusicSessions.kt` (`MusicMedia.init`), `MusicStore.kt`,
`VolumeCap.kt`, `Application.kt` (`onCreate`/`initUnlocked`/`initRest`, the before-unlock path), `server/RestrictionsPause.kt`
(`BootClock`), `app/build.gradle.kts` (`CheckDebugHook`), the debug manifest and the vault-key receiver, and on the music
side `PlaybackService.reportNowPlaying` (who sends, and when). The AudioManager claims were read against AOSP's
`registerAudioDeviceCallback`/`broadcastDeviceListChange_sync`. No surface reads the window yet (step 3), so the APIs
were also judged as step 3's foundation. Line numbers are `f0b83ffd`'s. Nothing was changed in the implementation; the
mutation edits below were reverted before this commit.

**Verdict: good to build step 3 on. No High, no Medium, 4 Low.** The pure rules match §3 at every boundary I could
construct, the glue is thread-safe, the before-unlock path fails closed and retries, the bridge feed sits where §2.4
wants it, and the debug receiver can't reach a release build. The Lows are one untested line of glue that guards the
"a launcher restart is baseline" rule, an undocumented stale-flag case, one device check to add, and style.

## What I ran (under the shared build lock, with the stub `tsnet.aar`, removed afterwards)

- `testDebugUnitTest --tests 'com.kidslauncher.mdm.music.*' --tests '...lock.DebugHookAbsentTest'`: green, 76 tests.
- **Mutations** (one run, three temporary edits, then `git checkout`):
  - `playedStampAfter` without `previous.validAt(now)` (`MusicWindow.kt:56`): **red** (`an end of play after a
    reboot ...`).
  - `headsetAdded` without the boot grace (`MusicWindow.kt:98`): **red** (`connections in the first 2 minutes ...`).
  - `MusicListening.init` without the baseline seed (`MusicListening.kt:57`, the line replaced by a log): **green** -
    see L1.
  - Walked through by hand for the rest of `MusicWindow.kt` (each turns a named test red): `<` -> `<=` at the end, the
    `0 until` rejoin range, `out.sink`, each type in `HEADSET_TYPES`, the key without the address or without the type,
    `known` not growing, `removedAt` not pruned, `known - removed`, the `Long.MAX_VALUE` guard in `musicRecheckInMs`,
    `windowedSession` ignoring `playing`, `playStampNotifies` without `!playing`. The pure tests are strong.
- Full build: `assembleDebug assembleRelease testDebugUnitTest -PwarningsAsErrors=true`: BUILD SUCCESSFUL (5 min),
  1055 tests in 118 classes, 0 failures. `checkDebugHasDebugHook`: "hook present" (the new name included, since the
  positive control needs every name); `checkReleaseHasNoDebugHook`: "no debug hook". No new compiler warning.

## Checked and sound

- **Window boundaries.** Shown iff `now < end` and the recheck is `(end - now).coerceAtLeast(1)`: the two agree, and
  the tests sit at 10 min - 1 ms and exactly 10 min. The 60 s rejoin is `0 <= now - removedAt < 60 s` (fresh at exactly
  60 s), the grace `now >= 2 min` (fresh at exactly 2 min). A stamp counts only with a known, equal boot count and
  `elapsedMs <= now`. Reboot: a new `BOOT_COUNT` invalidates every stamp. Dead battery while playing (QA L1): the stale
  `playing_at` makes `wasPlaying` true, but the previous stamp is from the last boot, so nothing moves (tested). Launcher
  restart: the stamps are persisted (both keys in one `apply()`, read back only when both are there) and load lazily;
  the headset baseline is read again. Doze and sleep: every time is `elapsedRealtime`, which runs through suspend.
- **Headset events.**
  - The registration replay: AOSP's `registerAudioDeviceCallback` posts `MSG_DEVICES_CALLBACK_REGISTERED` with
    `GET_DEVICES_ALL` to the given handler. `init` runs on main and registers on main, so the replay is handled after
    `init` returns and finds every output id in `known`. The inputs it also carries aren't in `known` but are never
    sinks. A device that came between the read and the registration is a real connection, as §2.3 says.
  - A BT blip or a codec change: removed then added with a new port id (as I read AOSP, one port update posts `REMOVED`
    before `ADDED`; worth seeing in §8 check 9's codec switch), the same `type/address` key within 60 s: not fresh. With an unchanged id it would be in neither
    `known` nor fresh either (the id left `known` at the removal, and the key is recent).
  - A second device: another address, fresh (tested, also "one fresh among re-adds").
  - Wired: keyed by type, so a replug within 60 s isn't fresh and another wired type is (tested).
  - SCO, ASHA hearing aids, LE broadcast, USB device/accessory, HDMI, dock, line, bus, submix, built-ins and every
    source: not counted (tested type by type; `minSdk` 34, so every constant exists).
- **Thread safety.** All state is under the object's monitor (`@Synchronized` and `synchronized(this)` are the same
  lock, and `bootCount()` re-enters it). The lazy `load` runs under it, so a binder-thread `observePlay` and a
  main-thread `listening()` can't both load or interleave a read with a write. `devicesAdded` loads before it sets
  `headset`, so a first load can't overwrite a new stamp. Listeners are added, removed and run on main (the
  `MusicNowPlaying` pattern). The callback and the debug receiver run on main; `observePlay` runs on binder threads
  (oneway `setNowPlaying`, the death recipient), and only posts.
- **Before the first unlock.** `MusicStore.prefs` throws `IllegalStateException` there (targetSdk >= O, CE context).
  `listening()` answers `Listening(null, null)` (only playing shows) and leaves `loaded` false, so the next call
  retries. That is also the right answer, not just a safe one: a launcher process before the first unlock means a
  reboot, so every stored stamp is from another boot. `observePlay` returns without a change (the next 15 s report
  repeats it). No writer can run before the unlock anyway: the callback is registered from `initRest`, the bridge
  isn't direct-boot-aware, and the debug receiver goes through the component factory's unlock init. If `BootClock.init`
  failed, `bootCount()` stays -1, every new stamp is -1 and counts as nothing (fail closed), and it is read again.
- **The bridge feed.** `observePlay` sits right before `changed()` in both `store` and `playerGone`, with `wasPlaying`
  read before `playing_at` is touched. An end of play stamps only on `wasPlaying && !playing` with a stamp valid in this
  boot; paused after paused, the music app's paused restore and a `playerGone` after a pause keep the stamp. The 15 s
  `refresh` reports move the stamp and notify no one. A queue's end (`setNowPlaying(null)` while playing) opens a
  window with nothing to show in it (no snapshot, a STOPPED session) - consistent with §1's table. Sonos and Storytel
  arrive through the same `setNowPlaying` with `playing = renderer.showsPlaying` (`PlaybackService.kt:1274`); nothing in
  the launcher distinguishes them.
- **The debug receiver.** Only in `src/debug` (class and manifest entry), exported and guarded by DUMP; in
  `CheckDebugHook`'s names (the release build fails if the name is in its manifest or dex, the debug build fails if it
  is missing); in `DebugHookAbsentTest`'s raw-text ban over `src/main` and `src/release` (comments included). Nothing
  in `src/main` names it (`grep HeadsetDebug`: no match). Each broadcast is its own id (-1000, -1001, ...) and address,
  and goes through `devicesAdded`, so the grace and the rejoin rule apply. `resultData` works: `am broadcast` is ordered.
- **`MusicListening.init` inside `MusicMedia.init`'s try (the implementer's question): acceptable.** It is the place
  §4 prescribes (`MusicMediaPlanTest` pins it right after `MusicCalls.init(app)`). `init` catches its own exceptions,
  so it can't take the screen-off receiver below it down. If `VolumeCap.init` or `MusicCalls.init` throws first, the
  only loss is the headset watch for that process, which fails closed (no headset window; the end-of-play window still
  works, since the stamps load lazily - QA L4's point). A separate `try` in `initRest` would buy nothing that matters:
  a failing `VolumeCap.init` is the bigger problem and is logged.
- **As step 3's foundation.** `musicShownUntil`/`windowedSession`/`musicRecheckInMs` take `elapsedRealtime`, which both
  surfaces already use (`MusicHomeCard.kt:70`, `MusicLockPanel.kt:111`); `listening()` and `bootCount()` are cheap
  after the first load (no `Settings.Global` read per tick); the listener fires only when a window may open. Nothing
  in the API needs to change for step 3.

## Low

### L1. The line that makes a launcher restart "baseline" is untested
- **Where:** `MusicListening.kt:57` (`watch = watch.copy(known = watch.known + baseline)`);
  `MusicMediaPlanTest.kt:247-260` checks that `getDevices` comes before `registerAudioDeviceCallback`, not that its
  result reaches `known`.
- **Scenario:** a refactor drops or breaks the seed (the mutation above replaced it with a log line: all 76 tests stay
  green). Then AOSP's registration replay finds every connected device unknown. With buds connected, every launcher
  restart after the boot grace - a crash, a self-update, design 24b's relock restart - stamps a "fresh headset", and
  the card and the lock's tile show for 10 minutes with nothing played. That is §2.3's "a launcher process restart
  never stamps", and no test holds it.
- **Fix:** pin it in the same test: `assertTrue(init.contains("known = watch.known + baseline"))`. Or, sturdier, move
  the seed into a pure `headsetBaseline(watch, ids): HeadsetWatch` in `MusicWindow.kt` and test "the registration's
  replay of the baseline is never fresh, after the grace too" with it.

### L2. A stale `playing_at` in the same boot opens a window at the music app's next report
- **Where:** `MusicWindow.kt:56` (`wasPlaying && previous valid -> now`), fed by `MusicNowPlaying.playing`
  (`MusicStore.playingReportedAt`, no age limit).
- **Scenario:** §2.1 "Stale flags" says `playing_at` can stay set when both processes died while the launcher was down.
  It can also be set again after `playerGone`: a oneway 15 s "playing" report already queued when the music process is
  killed can be handled after the death notification (two binder objects, no order between them). Either way the
  previous stamp is valid in this boot, so the music app's next report (`setNowPlaying(null)` or its paused restore)
  counts as an end of play and opens a 10-minute window then - possibly hours after the music really stopped. The
  effect is small: `PlaybackService` starts only from a tap or a media command, so the kid has just touched the player
  or a headset button. The spec doesn't mention this case.
- **Fix:** say it in §2.1 "Stale flags" (accepted: the next report needs a tap or a media command). If it should
  not happen at all: count an end of play only while the previous stamp is younger than about a minute (the reports
  come every 15 s while playing), and otherwise keep it. That would also change a late Sonos stop detected after a long
  sleep, so it is the user's call; the doc line is enough.

### L3. §8 check 9 doesn't cover a late first unlock
- **Where:** spec §2.3 "Boot" and §8 check 9 ("Buds in the ears at boot give no card").
- **Scenario:** the grace counts from boot, but the watch registers only at the first unlock (`initRest`). The design
  assumes bonded buds reconnect before the unlock or within 2 minutes of boot. If a phone's Bluetooth stack connects
  A2DP only after the first unlock, and the kid unlocks more than 2 minutes after boot, the buds' connection after the
  registration is fresh and the card shows at the first unlock. I couldn't settle this from AOSP alone; it is a device
  question.
- **Fix:** step 3's §6j/§8 check 9 gains "reboot with the buds in, wait 3 minutes before the PIN, unlock: no card". If
  it fails, measure the grace from `max(boot, registration)` (connections within 2 minutes of the registration are
  baseline too) - a one-line change in the glue.

### L4. Style
- `app/build.gradle.kts:190-191`: the `CheckDebugHook` KDoc's edited line is 167 characters in a comment wrapped at
  about 100; and its two messages (`:226`, `:228`) still say "the 16d hook" / "the debug-only lock-task hook" for what
  are now four names. Rewrap, and say "debug hook" in both messages.
- `MusicMediaPlanTest.kt`: the two new source scans are in `MusicSuspensionGuardTest` (they need its `file`/`body`
  helpers). Fine, but the class name no longer describes them; a line in its KDoc ("and the design 28 feed pins") would
  help the next reader.

## Fixes (implementer, 2026-10-13, on `design-28-s2`)

| # | Fix | Test |
|---|---|---|
| L1 | The seed is the pure `headsetBaseline(watch, ids, nowElapsedMs)` in `MusicWindow.kt`; `MusicListening.init` calls it once, between `getDevices` and `registerAudioDeviceCallback`. | `MusicWindowTest` `devices at registration are the baseline, never fresh` (the replay after the grace too); `MusicMediaPlanTest`'s headset-watch pin checks the call and its place. Removing the line turns that pin red. |
| L2 | Not only documented: `playedStampAfter` counts an end of play only when the previous stamp is younger than `END_OF_PLAY_STAMP_MAX_AGE_MS` (60 s, against the 15 s refresh). Spec §2.1 (the rule and its cost), §3, §9. | `an end of play counts only within 60 s of the last playing report - a stale flag in the same boot keeps the stamp` (a pause between reports counts, 60 s - 1 ms counts, exactly 60 s and hours later don't). |
| L3 | Done in the rule, not left to the device check: `HeadsetWatch.freshFromMs` (default the boot grace) is moved by `headsetBaseline` to registration + 2 min, never earlier, and `headsetAdded` checks it. Spec §2.3 ("The registration"), §3, §9; §8 check 9 gains the late unlock, checks 5 and 7 the launcher process's own 2 minutes. | `connections in the first 2 minutes after the registration are baseline too - the later of boot and registration`. |
| L4 | `CheckDebugHook`'s KDoc rewrapped and naming all three hooks; both messages say "debug hook" and list the names. `MusicSuspensionGuardTest`'s KDoc names the design 28 pins it holds. | - |
