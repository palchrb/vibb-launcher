# QA 28 design review - the music card follows a listening session; Play in the drawer during a window (2026-10-13)

Scope: `docs/design/28-music-card-session.md` at `425e7f96` (branch `design-28`, on `main` `fff8c287`), read against the
root and launcher `CLAUDE.md`, designs 07 §4, 09 B4, 21 §3, 24b, 25 §1.1/25b-G and 26a §3.2, and checked against the code
it builds on: `launcher/.../music/` (`MusicSessions`, `MusicMedia`, `MusicCalls`, `MusicBridgeService` with
`MusicNowPlaying`/`MusicForeground`, `MusicHomeCard`, `MusicLockPanel`, `MusicMediaPlan`, `VolumeCap`, `MusicStore`),
`ui/home/MusicCard.kt`, `lock/LockGlance.kt`, `lock/GlancePanel.kt`, `ui/HomeActivity.kt`, `Application.kt`,
`server/RestrictionsPause.kt` (`BootClock`), `server/OfflineOverride.kt`, `server/WindowRelock.kt`,
`server/EnforcementPlan.kt`, `server/AppEnforcer.kt`, `play/`, `apps/AppFilter.kt`, the tests named in §6
(`MusicMediaPlanTest`, `LockMediaGuardTest`, `DebugHookAbsentTest`, `AppFilterTest`, `PlayPolicyTest`) and
`app/build.gradle.kts`'s `CheckDebugHook`; on the music side `bridge/Bridge.kt` (`LiveBridge`),
`playback/PlaybackService.kt` (`reportNowPlaying`, `saveTick`, `onDestroy`, the device kinds),
`playback/LocalRenderer.kt`, `sonos/SonosRenderer.kt`, `sonos/SonosController.kt`, `sonos/SonosSession.kt`. Line
numbers are `425e7f96`'s. QA wrote no product code.

**Verdict: buildable after a short fix round. No High.** The shape is right and small enough: one pure rule for both
surfaces, persisted elapsed+boot-count stamps, a baseline plus boot grace for the headset, one expiry callback. The
claims about the existing code mostly hold (verified: the `setNowPlaying` cadence and its Sonos predicate, `onDestroy`'s
forced pause, `LiveBridge`'s re-send on a rebind, `playerGone` through the `LiveBridge` token linked on every connect,
the `MusicMediaPlanTest` adjacency pin, `VolumeCap`'s device callback, `sessionPlay`'s mapping, the 25b-G tile code,
`glanceTile`/`resumeNow`'s `tileUp`, `overrideActive` -> `appsManaged = false` -> no kiosk, install mode's NOT_NEEDED,
`playPackageLaunchable`'s single main caller). **The lock is no weaker:** the window only narrows 25b-G's "any live
session" and never adds the snapshot, so the tile still has only the session's transport controls and
`LockMediaGuardTest`'s call set stays as it is. Two Mediums: Part B's refresh and its device checks rest on the Play
Store being suspended, which on the target phones (GMS: Play is the package verifier) never happens (M1); and "a new
audio port id = a fresh headset" turns every Bluetooth blip or codec re-negotiation into a 10-minute card (M2). The
Lows are spec edits. Counts: 0 High, 2 Medium, 8 Low.

## Medium

1. **M1: Part B's refresh, and checks B2/B3, assume the Play Store is suspended outside a window - on a GMS phone it
   never is.** §5 "Refresh" says the window's apply unsuspends Play -> `onPackagesUnsuspended` -> `loadApps()`, and at
   the end "`isMdmSuspended` hides a suspended Play whatever the window flag says, so the order ... doesn't matter".
   Design 09 B4 (`docs/design/09-sms-and-fixes.md:130-145`): suspension is refused for the required **verifier**, which
   is the Play Store on GMS phones - the Jelly Star and the "Android 16 Google Play emulator" §8 names.
   `AppEnforcer.kt:405-418` logs the refusal and records it (`PlayRuntime.recordStoreSuspendable`, status
   `play_store_suspendable`); the server's card says "Play is blocked only while kiosk is on". So on the target there is
   no Play suspend/unsuspend edge at all:
   - The drawer and Home re-filter only because *other* packages change suspension in the same apply
     (`Application.kt:105-116`, one callback per package since `AppEnforcer` suspends one package per call). On a
     managed phone whose installed apps are all allowlisted, or whose only edge is the camera lock's
     (`CameraLock.ownChange` skips it), nothing reloads: Play shows only at Home's next resume (`resumeContent` ->
     `render()` -> `refreshApps()`, `HomeActivity.kt:460-470`), and after "Lock again" from Home's own banner (Home
     stays resumed) it **stays on Home's grid after the window**. With `kioskDesired` off, an unsuspendable Play in
     the grid opens: the drawer filter is the only gate there.
   - The "order doesn't matter" argument is false there; it holds only because the window flags are written before the
     apply (`RestrictionsPause.start` -> `WindowRelock.opened { lift }` then `reapply`; `lockAgain` clears before
     `check`, `WindowRelock.kt:313-325`) - say so instead.
   - §8 B3 "The market link shows 'The Play Store is closed'" fails on the GMS emulator: `PlayLinkBlockedActivity`
     forwards whenever Play isn't suspended (`PlayLinkBlockedActivity.kt:50-57`), i.e. always there; kiosk then blocks
     the start. B2 "within about a second, without reopening" passes only by the accident of other packages' callbacks.

   **Fix:** drive the re-filter from the window's own edges, not Play's suspension. `WindowRelock` already notifies on
   open (`:167`), relock (`:304`) and "Lock again" (`:325`); Home's `windowListener` (`HomeActivity.kt:611`) renders
   only the banner - make it `{ renderWindowBanner(); refreshApps() }` (or `render()`). The drawer re-filters when it
   opens (`AppsRecyclerAdapter.kt:97`); one open across the edge may stay stale until closed - accept and say so, or
   call `Application.reloadApps()` from the same notification. Rewrite §5 "Suspension in a window" / "Refresh" /
   "Link blocker" for both cases (`play_store_suspendable` true / false). §8 B3: on a phone with
   `play_store_suspendable = false` the link reaches Play and the kiosk blocks it (kiosk off: Play opens - the B4
   status quo, unchanged by design 28); B2/B3 add "with every installed app allowlisted" so the check doesn't pass by
   accident. A source test: Home's window listener re-filters the apps.

2. **M2: "a reconnect (new id) is fresh" fires on every Bluetooth blip and codec re-negotiation.** §2.3 counts any
   counted sink whose `AudioDeviceInfo.getId()` isn't in `known`, and pins it with the test "a reconnect (new id) is
   fresh". The id is the audio port handle, new for every `AVAILABLE` in the audio policy, and that happens more often
   than "the kid put the buds on":
   - a link drop and auto-reconnect (buds at the edge of range, in a pocket, a cheap speaker's power save) - each one a
     removed + added pair with a new id;
   - an A2DP codec/config change: AudioService's config-change path ends in AudioPolicyManager's
     `handleDeviceConfigChange`, which toggles the device UNAVAILABLE -> AVAILABLE to re-read its configuration -
     `AudioManager` reports it as removed + added with a new port id (an AOSP reading; confirm on the Jelly Star, below);
   - the active-device switch between two connected BT sinks (buds and a speaker both bonded).
   Each shows the Home card and the lock's paused tile for 10 more minutes with nothing played - exactly the "fires at
   every BT blip" failure. None of it is reachable on the emulator: the debug hook (§4) bypasses `AudioDeviceCallback`
   entirely.

   **Fix:** a reconnect of the same device soon after it went is not fresh. The glue passes `AudioOut(id, type, sink,
   address)` (`getAddress()`: stable per BT device - anonymised to its last octets without `BLUETOOTH_CONNECT`, which
   is enough -, empty for wired, so wired keys by type); pure `headsetStep(known, recentlyRemoved, added, removed,
   nowElapsed)` keeps `type+address -> removedAtElapsed` and counts an added sink only when its key wasn't removed in
   the last `HEADSET_REJOIN_MS` (60 s is a reasonable default; list it in §9). Tests: "a re-add of the same device
   within 60 s is not fresh; after 60 s it is"; "a second device (another address) is fresh". §8 check 9 on the Jelly
   Star: walk out of range for ~10 s and back - no card; switch the BT codec in Developer options - no card; a call over
   the buds and its end - no card.

## Low

1. **L1: a stale `playing_at` after a reboot opens a window - "a reboot never does" isn't true.** The end-of-play stamp
   uses `wasPlaying = MusicNowPlaying.playing(context)` = `playing_at` present (`MusicBridgeService.kt:258`, `:297`;
   `MusicStore.kt:190`). A battery that dies while the kid listens (the usual night case) kills both processes: no
   `onDestroy` report, no `playerGone`, and `playing_at` stays set across the boot. The music app's first start after
   the boot reports `setNowPlaying(null)` from `PlaybackService.onCreate`'s `publish()` (`PlaybackService.kt:248/283`,
   `reportNowPlaying` with no queue, `:1278-1280`) or a paused restore - `playing = false` after `wasPlaying = true`
   -> a fresh stamp in the new boot -> the card for 10 minutes, contradicting §1's table ("After a reboot: none until
   something plays") and §2.2. §2.1 "Stale flags" admits "one 10-minute window" but the table and check 4 don't. **Fix:**
   the end-of-play stamp only when the previous `playedAt` is valid in this boot (same boot count, so it came from a
   playing report or session seen in this boot); `playedStampAfter`'s test gains "end of play after a reboot (previous
   stamp from another boot) keeps nothing". §8 check 4 gains "play, `adb reboot` while it plays: no card after boot,
   none after opening and closing the player".

2. **L2: the second feed (the platform session stamping in `pick`/`update`/`onSessionDestroyed`) buys seconds, and its
   rationale is wrong.** §2.1 says the session's stamp matters on Sonos because "the bridge's older stamp would have
   hidden the card at once". The session holds `playWhenReady` only through "the first 3 failed polls" (26a §3.2 table,
   `docs/design/26a-sonos.md:257`; `SonosSession.kt:519/529`: `POLL_MS` 5 s, `STALL_POLLS` 3), and on a local renderer a
   PLAYING session implies `showsPlaying` (`LocalRenderer.kt:159`, `NowPlaying.kt:13`). So the bridge's stamp is at most
   ~15-45 s older than the session's flip: the window is ~9.5 minutes instead of 10, never "hidden at once". The feed
   costs three call sites, a kept app context in `MusicSessions` and a guard test, and stamps only while a surface is
   watched (after an `unwatch`, `pick` sees `previous = null`, `MusicSessions.kt:72-78`). Its one real use is a bridge
   that is not bound while a surface watches the pause (all three `LiveBridge` retries spent, `Bridge.kt:114`).
   **Fix:** drop it (the bridge + `playerGone` are the feed; a pause seen by the session before the bridge's report
   still lands inside the last 15 s playing stamp's window, and the report then moves it). If kept, give the real
   reason (an unbound bridge) instead. Either way §8 check 8's "10 min after that flip, not 10 min after the last good
   poll" is a difference of seconds over a 10-minute wait - not observable by hand; check only that the card goes about
   10 minutes after the player shows paused.

3. **L3: the debug hook's single id makes a second broadcast a no-op.** §4: `devicesAdded(listOf(AudioOut(id = <unused
   negative id>, ...)))`. The id joins `known` and is never removed, so in one launcher process only the first broadcast
   is fresh: §8 check 5 then check 6 (the same process) fails at check 6. **Fix:** a fresh id per broadcast (a
   decrementing counter from -1000), or `devicesRemoved` right after; with M2's rejoin rule, give each broadcast its own
   address too. Note for the implementer: `DebugHookAbsentTest` (`DebugHookAbsentTest.kt:31-32`) matches raw text,
   comments included - `MusicListening.kt` must not name `HeadsetDebugReceiver` in a KDoc.

4. **L4: `MusicListening`'s state must not depend on `MusicMedia.init` having run.** §4 puts `MusicListening.init` in
   `MusicMedia.init`'s single `try` after `VolumeCap.init`/`MusicCalls.init` (`MusicSessions.kt:255-269`), itself called
   late in `initRest` inside `initUnlocked`'s one `try` (`Application.kt:240-246`, `:360`). Anything that throws before
   it leaves the stamps unloaded and no callback registered, while the bridge (created after `initRest`, not after
   `MusicMedia.init`) still calls `observePlay` - which then works on an empty state and may persist over the stored
   one. **Fix:** load the stamps lazily under the object's lock on first use (`listening()`, `observePlay`,
   `devicesAdded`); `init` only reads the baseline and registers, in its own `try` (keeping the
   `VolumeCap.init(app)\n            MusicCalls.init(app)` adjacency `MusicMediaPlanTest.kt:212` pins).

5. **L5: Home's expiry relies on Home being stopped while the phone sleeps.** §2.4 posts a `Handler` delay (uptime) and
   reasons that "Home is stopped" in deep sleep. Home's card is otherwise re-rendered only on `setActive`
   (onStart/onStop/lock edges, `HomeActivity.kt:139`, `:394`, `:554`) and its listeners; `resumeContent` doesn't render
   it (`:460-470`). If Home is ever resumed-but-not-stopped across a sleep (no keyguard, no kid PIN), the runnable fires
   late by the sleep's length and the card outlives its window. The lock is safe (its `resume()` renders from the clock,
   `GlancePanel.kt:145-161`). **Fix:** `renderMusicCard()` in `resumeContent` (an active card re-renders,
   `MusicHomeCard.kt:43-45`) - one line, no reasoning about stop-at-sleep needed.

6. **L6: §5's claims about the Part B callers are wrong, and the rename breaks pinned tests.** "callers are positional"
   - `PlayPolicyTest.kt:135-139` names `installModeActive = ...` and `AppFilterTest.kt:28-29` names `installMode = ...`;
   `AppFilterTest.kt:36` pins the text `kidListable(packageName, blockedDialer, recentsProvider, installMode,
   emergencySos)`, which "the local becomes `playStore`" breaks. The call is at `AppFilter.kt:38`, not `:37`. **Fix:**
   either keep the parameter names (the doc comment carries the new meaning - least churn in an MIT file) or list the
   three test edits in step 1.

7. **L7: define the window's boundary and make the lock window both of its reads.** `musicShownUntil` and
   `musicRecheckInMs` must agree on the end: shown iff `now < end`, recheck `(end - now).coerceAtLeast(1)` - an
   inclusive end with a 0 ms recheck re-renders in a loop for that millisecond. Tests at exactly 10 min and at
   10 min - 1 ms. `MusicLockPanel.render()` recomputes the session itself (`MusicLockPanel.kt:87`), separately from
   `lookUp()` (`:68-73`); the §6 source scan ("`MusicLockPanel.kt` with `windowedSession(`") passes with only one of
   them windowed. Use one private `shown()` for both, and have the test count it there.

8. **L8: §9 is missing defaults the user should see, and one listed default is half true.**
   - Part B puts Play on **Home's grid** as well as in the drawer (Home's grid is `AppFilter`'s output,
     `HomeActivity.kt:640`); the user said "in the app drawer". Fine by the architecture ("the drawer shows nothing
     more than Home"), but list it for veto.
   - Play in a window is the whole store on the phone's Google account: the kid can install (new apps come out
     not allowlisted after the window and are reported; Play is a trusted installer for the relock,
     `WindowRelock.kt:566`) and buy, if the account has a payment method. Say it next to the decision.
   - "Hearing aids are not headsets": only ASHA ones (`TYPE_HEARING_AID`). LE Audio hearing aids enumerate as
     `TYPE_BLE_HEADSET` and will count; the launcher can't tell them apart without Bluetooth permissions. Document it.
   - M2's rejoin threshold (60 s) as a default.
   - Nit: `BOOT_COUNT` can't change within a process - read it once (or let `BootStamp` take `BootClock.bootCount()`
     once at `MusicListening` load) rather than a `Settings.Global` read per 1 s tick.

## §9 defaults

- **The lock never shows the snapshot:** agree - it is what keeps the tile to transport controls (`LockMediaGuardTest`).
- **Hearing aids and SCO-only not headsets, LE broadcast not a connection:** agree, with L8's LE Audio caveat written
  down.
- **Boot grace 2 min of elapsed time:** agree; the init baseline already covers everything connected before the first
  unlock, the grace only covers bonded devices reconnecting just after it.
- **A launcher restart inside the window brings the card back:** agree; it matches the snapshot's behaviour and
  check 3 tests it.
