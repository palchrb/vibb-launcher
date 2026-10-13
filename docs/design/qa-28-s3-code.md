# QA 28 step 3 (code) - Home's card and the lock's tile follow the listening window

Reviewer: code QA, 2026-10-13. Branch `design-28-s3` (`486c3e57..466417c2`, 1 commit). Checked against
`docs/design/28-music-card-session.md` (§1, §2.4, §2.5, §3, §4, §6, §7 step 3, §8, and its step 2 and step 3 status
sections), its design QA `qa-28-design.md`, `qa-28-s2-code.md`, the root and launcher `CLAUDE.md`, and the code the
step builds on:
- `MusicWindow.kt`, `MusicListening.kt` and `MusicBridgeService.kt` (`store`/`playerGone`);
- `MusicSessions.kt` (`playPause`/`next`/`previous`/`usable`, listener dispatch);
- `lock/GlancePanel.kt` (`start`/`resumeNow`/`renderNow`/`fitNow`/`apply`/`stop`) and `lock/LockGlance.kt`
  (`glanceTicks`);
- `view_lock_glance.xml` (every click on the tile);
- `HomeActivity.kt` (`onStart`/`onResume`/`resumeContent`/`onStop`/`showNight`/`modeListener`) and
  `lock/PinLockState.kt` (a process start locks);
- on the music side, `SonosLocks`/`LocalRenderer` (the wake locks behind the 15 s reports).

Line numbers are `466417c2`'s. Nothing in the implementation was changed. The mutation edits below were reverted
before this commit.

**Verdict: good to merge. No High, no Medium, 3 Low.** Both surfaces read the window from one pure rule at the right
place. The lock never gets the snapshot and opens nothing. The runnables and listeners are balanced across every
lifecycle edge. The boundary can't loop. The lows:
- the lock's half of the rule is held only by source text, and two plausible regressions pass every test;
- §6j's crash checks describe Home coming back where the PIN lock comes up first;
- the lock tile's own expiry and refit, the one new on-screen path, has no device check.

## What I ran (under the shared build lock; the stub `tsnet.aar` copied in for the release build and removed afterwards)

- Focused tests: `testDebugUnitTest` with `MusicCardTest`, `MusicSuspensionGuardTest`, `LockMediaGuardTest`,
  `MusicWindowTest`, `LockGlanceTest` and `DebugHookAbsentTest`. Green (56 tests).
- **Mutations** (temporary edits, then `git checkout`):
  - `musicCard` gates with `musicShownUntil(true, ...)` instead of `live?.playing == true` (`MusicCard.kt:55`), plus
    `resumeContent` without `renderMusicCard()` (`HomeActivity.kt:468-469`). **Red**, 5 tests:
    - `the card shows only while playing or inside the window`;
    - `a paused live session outside the window hides the card - never the snapshot instead`;
    - `hideAt is the window's end while paused and null while playing`;
    - `no live session - the last snapshot, always paused`;
    - `Home renders the card at every resume`.
  - `MusicLockPanel.shown()` with `hideAt = null` (`:97`) and `lookUp` without `if (started)` (`:82`). **Green.** The
    lock's tile would then never go by itself while the lock is on screen. See L1.
  - `MusicLockPanel.shown()` falling back to the unwindowed session: `windowedSession(...) ?: shownSession(...)` (`:96`).
    **Green.** That is a paused tile on the lock for as long as the session lives: exactly the pre-28 behaviour the spec
    removes ("No fallback outside the window"). See L1.
  - Walked through by hand (each turns a named test red):
    - the `until` check moved below the live branch (the snapshot ungated): `the snapshot, no window`;
    - `hideAt` without `takeIf { it != Long.MAX_VALUE }`: `hideAt ... null while playing`;
    - the expiry not removed in Home's `render`, or the listener left out of `setActive`: the source pins in
      `Home's card and the lock tile read the same window`;
    - `MusicNowPlaying`/`snapshot`/`openPlayer`/`EXTRA_RESUME` in the panel: `the lock's tile is a live session only`.
- Full build: `assembleDebug assembleRelease testDebugUnitTest -PwarningsAsErrors=true`. BUILD SUCCESSFUL. 1063 tests
  in 118 classes, 0 failures (the step status's count). Debug and unsigned release APKs built. No new compiler warning.

## Checked and sound

- **Home hides at the window's end while it stays visible.**
  - `render` posts one `expiry` at `musicRecheckInMs(card.hideAtElapsedMs, now)`, with `now` the value `musicCard` was
    given, whenever the card shows and doesn't tick (`MusicHomeCard.kt:96-100`; `musicCardTicks` is `card.playing`).
  - The Handler fires on uptime, which never runs ahead of `elapsedRealtime`. So at the callback `now >= end`, the card
    is null and nothing is reposted: no loop at the boundary. `coerceAtLeast(1)` only matters for an end already
    reached.
  - It comes back on a fresh headset (the `MusicListening` listener), on play (the sessions listener) and on an end of
    play (`MusicListening` and `MusicNowPlaying`, both `render()`).
- **Never shown outside the window, never hidden while playing.**
  - The gate sits right after the locked/access/usable checks and before both branches (`MusicCard.kt:55`), so a paused
    or stopped session and the snapshot are all windowed.
  - `musicShownUntil(playing = true)` is `Long.MAX_VALUE` whatever the stamps are: no stamps, another boot, an unbound
    bridge. `sessionPlay` already maps buffering, connecting and skipping to PLAYING.
  - `open()`/`playPause()` act on `shown`, which is null whenever the card is gone.
  - A pause on the card itself keeps it. While playing, the bridge refreshes `playedAt` every 15 s, so at the pause the
    window runs at least 9 min 45 s before the end-of-play stamp moves it to 10.
- **The lock.**
  - `shown()` is `windowedSession(shownSession(...))`: a live session only. Nothing in the panel names the snapshot
    (`LockMediaGuardTest`, comments stripped).
  - The tile's views have no click of their own (`view_lock_glance.xml`: `lock_media_tile`/`lock_media_compact` are
    plain layouts). The four buttons call only `MusicSessions.playPause`/`next`/`previous`, which are transport
    controls (`controls.play()` only when the app is usable). A tap racing the expiry reaches a GONE view, or at worst
    plays the session; nothing opens.
  - The `expiry` runs `changed`, which is `lookUp` + `onChange`, i.e. `GlancePanel.render` -> `renderNow` with
    `fitFor = 0` and `fitNow`. So the page refits without the tile; on the keypad stage the fit waits for the way back.
    The fresh-headset listener takes the same path.
  - `render()` re-reading the window between `lookUp` and the fit can't strand a tile: both run in the same main-thread
    pass, and a window that ends between them was already posted with at least 1 ms.
- **Handler lifecycle.**
  - Home: `setActive(false)` and every `render` remove both runnables. `render` returns before posting when inactive.
    The listeners are added only on the `false -> true` edge (`on == active` guard) and removed on the way back.
    `showNight` (LOCKED edge) and `onStop` both deactivate. A locale recreation goes through `onStop`.
  - Lock: `lookUp` removes before it posts and posts only while started. `stop` removes the expiry and the ticker before
    its guard. `start`/`stop` add and remove the listener once (`started`). `lookUp` runs twice per change (`changed`,
    then `renderNow`), each time remove-then-post, so there is never a second copy.
  - Screen-off stops both activities (the display sleeps them), which removes everything. The next `onStart` reads the
    clock again.
- **`resumeContent` after a sleep.** `renderMusicCard()` after `render()` (`HomeActivity.kt:468-469`) re-renders an
  active card from `elapsedRealtime`. With the PIN lock active, Home is stopped at every screen-off anyway, and its
  `onStart` renders too. The extra render on resume (and the one in `modeListener` before `resumeContent`) is harmless.
- **`hideAt` in `shown()` (the implementer's question): correct and race-free.**
  - It is computed from the same `listening` and `now` as `windowedSession`. It is null exactly when the session is
    hidden or playing, matching `musicCard`'s `hideAt`.
  - `lookUp` reads it on the main thread right after its own `shown()`. The delay uses a fresh `elapsedRealtime`, which
    is at most a few ms later (shorter, at least 1).
  - `render()` overwrites it without posting, but nothing reads it there.
  - It is a second return value carried in a field, so the next edit could read it stale. L1's fix removes the field.
- **Sonos and Storytel.**
  - Both reach the launcher as the one media session and `setNowPlaying(playing = showsPlaying)`, so neither surface
    treats them differently.
  - The 15 s refresh that keeps `playedAt` young also runs in deep sleep: `LocalRenderer` sets an ExoPlayer wake mode,
    and a Sonos room holds `SonosLocks`' partial wake lock while it plays. A pause from the lock tile after a long screen
    off still finds a stamp under 60 s old, and the tile stays.
  - A stale speaker reads PLAYING and shows playing. A room taken over goes NONE/STOPPED/ERROR: Home falls back to the
    snapshot inside the window, the lock shows nothing, as §1 says.
- **Errors.** The new paths (the `MusicListening` post and the expiry) call `changed` outside `GlancePanel.guarded`, as
  the sessions listener always did. Everything under them catches its own failures: `usable`, `BootClock.bootCount`,
  the CE prefs load.
- **§6j commands on the Android 16 Google Play emulator.**
  - Component `me.vibb.launcher.debug/com.kidslauncher.mdm.music.HeadsetDebugReceiver`: the debug applicationId and
    the fully qualified class.
  - `am broadcast -n` needs no action. The receiver is exported with `android:permission="android.permission.DUMP"`,
    which the shell holds. `am broadcast` is ordered, so the result data prints.
  - `run-as $P cat shared_prefs/music_bridge.xml`: `MusicStore.prefs` is `getSharedPreferences("music_bridge")`,
    run-as starts in the app's data directory, and the debug build is debuggable.
  - `am crash <package>`, `svc power stayon true`, `/proc/uptime` and `am force-stop me.vibb.music.debug` all exist
    there.
  - The logcat example matches the receiver's summary line.
- **Docs.** The launcher `CLAUDE.md` bullets (the listening window, Home, the lock, the glance tile rule, the debug
  headset), design 21's step 4 pointers, design 25 §1.1's paragraph and the step 3 status all match the code. P1-P6
  are marked as step 1's.

## Low

### L1. The lock's half of the window is held only by source text: two regressions pass every test
- **Where:**
  - `MusicLockPanel.kt:92-99` (`shown()`, `hideAt`) and `:82` (the post).
  - The tests are `MusicMediaPlanTest.kt:269-294` and `LockGlanceTest.kt:270-286`. They pin strings: `windowedSession(`
    exactly once, `session = shown()`, the post line and the `Runnable { changed() }`.
- **Scenario:** the mutations above.
  - `windowedSession(...) ?: shownSession(...)` keeps every pinned string. The lock is then back to 25b-G: a paused
    tile for as long as the session lives, also at every lock-up hours later.
  - `hideAt = null` keeps every pinned string. The tile then never goes by itself while the lock stays on screen; only
    the next screen-off fixes it.

  Both are one-line edits a later refactor could make (the first is the old code path). Unlike Home's card, whose rule
  is the tested pure `musicCard`, the lock's combination of `shownSession` + `windowedSession` + `musicShownUntil` is
  glue that no behavioural test sees.
- **Fix:** give the lock the same kind of pure seam Home has, and drop the side-channel field.
  - In `MusicWindow.kt`: `data class WindowedSession(val session: MusicSession?, val hideAtElapsedMs: Long?)` and
    `fun lockTileSession(access: Boolean, usable: Boolean, session: MusicSession?, listening: Listening, now: BootStamp): WindowedSession`
    (`shownSession`, then `windowedSession`, then the end).
  - `shown()` returns it. `lookUp` posts `musicRecheckInMs(it.hideAtElapsedMs, now.elapsedMs)`, and the `hideAt` field
    goes.
  - `MusicWindowTest` gets "a paused session outside the window is no tile - never a fallback to the live session",
    "hideAt is the end while paused, null while playing or hidden", and "no access / not usable / STOPPED is no tile
    inside the window too".
  - The source pin then only needs `lockTileSession(` in `shown()` and `if (started)` in front of the post. The second
    is also unpinned today: without it a `lookUp` from a stopped panel would post an expiry that `stop` no longer
    removes. That can't happen now, since `renderNow` runs only while started.

### L2. §6j's crash checks expect Home where the PIN lock comes up first
- **Where:** `docs/testing/emulator.md:779-781` ("A launcher restart inside the window") and `:790-791` ("The graces").
  The same wording is in spec §8 checks 3 and 7.
- **Scenario:** `am crash $P` kills the launcher. The new process starts LOCKED (`PinLockState.kt:10`, "a new process
  starts LOCKED"), so the lock comes up with the screen on, not Home. In check 3 the tester sees the lock, not the card.
  In check 7 the 2 minutes count from the watch's registration, i.e. the process start, not from "Home coming back":
  - a tester who types the kid PIN first and sends the broadcast within 2 minutes of seeing Home can be past the grace;
  - a card then appears and reads as a failed check.

  The lock after a restart is also a check the spec's §1 table asks for ("The launcher restarted inside a window | back
  for the rest of the window | same") and §6j doesn't have.
- **Fix:** reword both checks.
  - Check 3: "... `adb shell am crash $P`. The PIN lock comes up (a new process locks). With the music app's session
    still live, its tile shows paused. Unlock: Home has the card. Both go at the original +10 min."
  - Check 7: "... `adb shell am crash $P` and send the broadcast within 2 min of the crash, before or after unlocking.
    The result's `headsetAt` stays as it was, and there is no card."
  - Copy the same into spec §8.

### L3. No device check for the lock tile's own expiry and refit
- **Where:** `docs/testing/emulator.md` §6j. "Paused, then 10 minutes" checks the lock only by locking and waking after
  the window, which is `lookUp` at a lock-up. The `expiry` -> `changed` -> refit path is new in this step (and is L1's
  untested half) and is never seen on a device.
- **Scenario:** a fault in that path is invisible to every check: no post, a post removed by a second `lookUp`, or a
  refit that leaves the tile's space. A kid looking at the lock past +10 min would still see the paused tile, or a gap
  where it was.
- **Fix:** add to "Paused, then 10 minutes": "Play again, pause, and at about +8 min lock and wake the screen: the tile
  shows paused. Leave the lock on screen (stay-on is set): at +10 min the tile goes by itself and the page refits (the
  rows or the clock move into its place, nothing scrolls)."

## Not findings (noted for the record)

- The launcher `CLAUDE.md` Home bullet reads "inside the listening window while it is paused, else from the last
  `setNowPlaying` snapshot". The next clause ("outside the window nothing") settles it, but "else, inside the window,
  from the snapshot" would read cleaner. Its lock bullet has two parentheticals in a row. Both are style only, so
  change them only if the file is touched for L2 anyway.
- §6j sits between §6i and §6b, like every section since §6c. That is the file's existing order, not this step's.

## Fixes (implementer, 2026-10-13, on `design-28-s3`)

| # | Fix | Test |
|---|---|---|
| L1 | Pure `WindowedSession(session, hideAtElapsedMs)` and `lockTileSession(access, usable, session, listening, now)` in `MusicWindow.kt` (`shownSession`, then `windowedSession`, then the end; null end while playing or hidden). `MusicLockPanel.shown(nowElapsedMs)` returns it; the `hideAt` field is gone. `lookUp` posts `musicRecheckInMs(tileNow.hideAtElapsedMs, now)` with the same `now`, behind `if (started)` (commented). The panel names no `windowedSession(`, `shownSession(` or `musicShownUntil(` itself. | `MusicWindowTest`: `the lock's tile - a paused session outside the window is no tile, never a fallback to the live session`, `... hideAt is the end while paused, null while playing or hidden`, `... no access, not usable, STOPPED or no session is no tile inside the window too`. `MusicSuspensionGuardTest`'s `Home's card and the lock tile read the same window`: `lockTileSession(` exactly once (in `shown()`), none of the three direct reads, `shown(now)`/`shown().session` in `lookUp`/`render`, one expiry post, behind `if (started)`. `LockMediaGuardTest` pins the `lockTileSession(...)` call. Mutations, each reverted: `?: shownSession(access, usable, session)` in `lockTileSession` - red (`... never a fallback to the live session`); `WindowedSession(shown, null)` - red (that test and `... hideAt is the end ...`); the post without `if (started)` - red (`Home's card and the lock tile read the same window`). |
| L2 | `emulator.md` §6j "A launcher restart inside the window" and "The graces", spec §8 checks 3 and 7: the PIN lock comes up first (a new process locks), the paused tile shows on it with the session live, then Home's card, both going at the original +10 min; the broadcast within 2 min **of the crash**, before or after unlocking, `headsetAt` unchanged, no card and no tile. | - |
| L3 | §6j "Paused, then 10 minutes" and spec §8 check 2: play, pause, at about +8 min lock and wake - the tile shows paused; with the lock left on screen it goes by itself at +10 min and the page refits. | - |

Also the two style notes on the launcher `CLAUDE.md` (Home's "else, inside the window, from the snapshot"; the lock
bullet without the stacked parentheticals), since L1 touched the lock bullet anyway. Full build after the fixes:
`assembleDebug assembleRelease testDebugUnitTest -PwarningsAsErrors=true` green with the stub `tsnet.aar` (removed
afterwards), 1066 tests in 118 classes, 0 failures; "hook present" / "no debug hook"; no new compiler warning.
