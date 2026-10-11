# QA 21 step 4 code review - media surfaces, time rules, volume cap (2026-10-11)

Scope: branch `music-step4`, commits 8dd7ca8c (launcher + music code) and 2439ef80 (docs) on main e338700c (62 files,
~2.2k lines). Read against design 21 (§3, §6 step 4, the QA review and its decisions, "Step 4 implementation status"
and its readings), design 20 (§4's "Never shown" rule as context), the root, `launcher/` and `music/` CLAUDE.md, and
step 3's review (`qa-21-step3-code.md`; only what step 4 builds on is raised again). Platform behaviour was checked
against AOSP as far as it is known offline (lock task vs PiP, broadcast delivery to `RECEIVER_NOT_EXPORTED`,
`MediaSessionService`'s FGS allowlisting of a controller's target); what can only be seen on a phone is marked so.
Paths: `L/` = `launcher/app/src/main/java/com/kidslauncher/mdm/`, `M/` = `music/app/src/main/java/me/vibb/music/`.

Build (the worktree at 2439ef80, tests forced with `--rerun`):
- Launcher: `./gradlew testDebugUnitTest assembleDebug assembleRelease -PwarningsAsErrors=true` passed, 826 tests in
  98 classes, 0 failures (tsnet stub; `checkReleaseHasNoDebugHook` ran).
- Music: `./gradlew testDebugUnitTest assembleDebug assembleRelease lint -PwarningsAsErrors=true` passed, 126 tests,
  0 failures, lint clean; `checkReleaseHasNoDebugFeed` ran.
- `cmp`: both AIDL files and both `bridge_contract.json` are byte-identical; the AIDL is unchanged by step 4 and
  `player` is appended at the end of the contract.
- A probe test (deleted again) confirmed the budget lock's allowlist interplay and the snapshot rule (see "Checked and
  fine"). The prompt widths in Low 5 were measured from `nunito_bold.ttf`'s glyph advances (fontTools). No emulator
  or phone here: nothing ran on a device.

Counts: 0 High, 1 Medium, 7 Low.

## High

None.

## Medium

1. **Medium: a suspended music app can still be played from its paused session - "paused before suspension" is not
   "can't play while suspended".**
   - Where:
     - `L/server/AppEnforcer.kt:340-345`: `pauseBeforeSuspend` sends `transportControls.pause()` (a oneway call into
       the music app) and suspends at once, only on the suspend edge.
     - `L/music/MusicLockPanel.kt:111-115`, `:122`, `:126`: `usable` (`isPackageSuspended`) is read at lock-up and on
       session callbacks only; the ticker path and the buttons use the cached value. `L/music/MusicHomeCard.kt:31-32`,
       `:63` the same.
     - `L/music/MusicSessions.kt:141-152`: `playPause`/`next`/`previous` don't check suspension.
     - `M/MusicApp.kt:64-68`: `SuspendedReceiver` pauses once; nothing in the music app refuses a later play
       (`KidPlayer`, `M/playback/PlaybackService.kt:206-223`).
   - Scenario A (the launcher's own surface):
     - Bedtime at 20:00, the music app is not the rule's exempt app. The kid is looking at the PIN lock's media view
       with a story playing.
     - `apply()` pauses and suspends. The PAUSED state reaches the panel's callback either after the suspension
       (`usable` is re-read -> false -> keypad) or before it, a real race: the pause goes launcher -> system ->
       music app -> Media3 -> system -> launcher's main thread, while the apply thread goes straight on to
       `setPackagesSuspended`.
     - In the second order `usable` stays true. Nothing re-reads it: a suspension triggers no session callback. The
       media view stays (a pause keeps it) with its play button, and a tap sends `play()` to the suspended app's
       session.
     - It then plays on after the screen goes off. The next lock-up shows the keypad, but nothing pauses it again,
       because `pauseBeforeSuspend` runs only on the suspend edge.
   - Scenario B (no launcher surface at all): the kid in bed with wired or Bluetooth headphones presses play. The
     platform sends the media key to the last media-button session, which is the music app's paused one. Media3
     plays it.
   - Whether a suspended app is audible:
     - The platform's documented effects of suspension are activities, notifications, toasts, dialogs and ringing.
       Playback isn't among them, and the process isn't killed.
     - `MY_PACKAGE_SUSPENDED` exists so that an app stops its own work.
     - Step 4's device checks still list "does a suspended app's audio stop" as open, so this is not confirmed on a
       phone. If it does play, both scenarios break the bedtime rule silently.
   - Fix:
     - Music app (covers every path: the launcher, headset and BT buttons, any controller):
       - in `KidPlayer` (the `ForwardingPlayer` every controller goes through), refuse `play()` and
         `setPlayWhenReady(true)` while the app is suspended, and refuse `onPlaybackResumption` too;
       - "suspended" = `packageManager.isPackageSuspended()` (own package, API 29+), or a flag kept by
         `MY_PACKAGE_SUSPENDED`/`MY_PACKAGE_UNSUSPENDED` (both protected broadcasts that manifest receivers get);
       - a pure `playAllowed(suspended)` with a test, plus a source scan that `KidPlayer` uses it.
     - Launcher:
       - re-check `MusicSessions.usable(pkg)` inside `playPause`/`next`/`previous` before sending anything;
       - re-render both surfaces on `LauncherApps.Callback.onPackagesSuspended`/`onPackagesUnsuspended`, which
         `Application.launcherAppsCallback` already receives.
     - Pin the order in `AppEnforcer` (pause, then `setPackagesSuspended`) with a source-order scan. Today only the
       pure predicate is tested.
     - Device check: at a bedtime edge, the headset's play button and the lock's play button do nothing.

## Low

1. **The volume cap misses an output change without a device coming or going: the music app's own output picker.**
   - Where:
     - `L/music/VolumeCap.kt:47-64`: clamps on `VOLUME_CHANGED_ACTION` and on `AudioDeviceCallback` add/remove
       (`:82-85`) only.
     - `M/playback/PlaybackService.kt:559-565`: the picker routes by `exo.setPreferredAudioDevice`, explicit routing
       between outputs that are already connected.
   - Android keeps one `STREAM_MUSIC` index per output, and `setStreamVolume` (at `refresh()` and at every clamp)
     sets only the current output's.
   - Scenario:
     - BT headphones are connected and the story plays on them. The parent sets 60 %, so the BT index is clamped to
       9/15. The speaker's index stays at 15/15 from earlier videos.
     - The kid picks "Telefon" in the music app's output picker. The sound moves to the speaker by explicit
       routing: no device comes or goes and no volume changes, so no broadcast arrives.
     - The speaker plays at 100 % until the next volume key. The same happens with wired headphones at an old high
       index chosen while BT stays connected, and then it is loud in the kid's ears.
   - Fix:
     - Also clamp from `AudioManager.registerAudioPlaybackCallback`. `onPlaybackConfigChanged` fires when a player
       starts and when a playback's device changes (public API, no permission), so it also covers "playback started
       on an output whose index was never clamped". The hidden `android.media.STREAM_DEVICES_CHANGED_ACTION` would
       also do.
     - Device check: switch the picker with both outputs connected.

2. **Deviation 5: with the kiosk off, the player in front makes a picture-in-picture video free. So does browsing
   the app with nothing playing.**
   - Where: `L/timerules/ScreenTime.kt:191-199` (`!musicInFront` overrides everything), `M/ui/MainActivity.kt:76-84`.
   - Scenario:
     - Kiosk off, with an allowed browser or video app that supports PiP. The kid starts a video, swipes home so it
       goes to PiP, and opens Vibb music.
     - MainActivity is top resumed and not in multi-window, so no screen time counts while the video plays in its
       window.
     - QA step 6 #2 kept Home off `FREE_SCREENS` for exactly this reason (`ScreenTime.kt:169-171`, "Home is not on
       the list").
   - The reading is right that the existing free screens (phone book, kid Settings) share the hole. But nobody sits
     in those for an hour, and a kid can sit in the music app.
   - With the kiosk on, lock task refuses PiP (`checkEnterPictureInPictureState`), so the reading holds there.
   - Fix (the user's choice, see "Needs a user decision"): count unless the music app is playing:
     `musicInFront && playing`, with `playing` from the last `setNowPlaying` (`MusicStore.playingReportedAt`,
     cleared at the token's death).
     - A video with sound takes audio focus, and Media3 pauses the story, so the count resumes.
     - It matches the user's own words, "listening never counts", which the spec reworded as "player time".
     - It also stops an hour of browsing covers with nothing playing from being free.

3. **Opening the music app from Recents starts playback by itself after a snapshot tap.**
   - Where: `M/ui/MainActivity.kt:42` and `:51-59`; `backHome()` = `finish()` at `:124`.
   - Scenario:
     - The kid taps Home's snapshot card. MainActivity starts with `FLAG_ACTIVITY_NEW_TASK`, so the task's base
       intent carries `EXTRA_RESUME`.
     - The kid leaves with the app's own home button (`finish()`), or the phone reboots: recents persist, saved
       state doesn't.
     - Later the kid opens the music app from Recents. Android recreates the task's root from its base intent with
       `FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY` and no saved state, so `open(intent)` sends RESUME, and playback
       starts unasked (possibly a podcast from yesterday).
   - Fix:
     - Ignore `EXTRA_RESUME` when the intent has `FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY`.
     - Write it as a pure `resumeRequested(extra, flags)` with a test. Today the extra's handling is untested; only
       its name is pinned.

4. **Home's snapshot shows where the item last started or paused, not where it stopped, after a crash or a reboot
   mid-play.**
   - Where:
     - `M/playback/PlaybackService.kt:822-829`: `reportNowPlaying` is deduplicated on (entry, item, playing), so a
       position goes out only at play, pause and item changes.
     - The app saves its own position every 15 s (`:590-593`), and the launcher never hears of it.
     - `L/ui/home/MusicCard.kt:172-181` shows the snapshot's position.
   - Scenario:
     - A bedtime story's 18:30 chapter starts at 0:00, and the battery dies at 12:40.
     - In the morning Home shows "Kapittel 3 · 0:00 av 18:30", but the play button resumes at 12:40.
     - A force-stop or a low-memory kill while playing does the same. A pause and a normal service end are right.
   - Fix:
     - Send `setNowPlaying` with the 15 s position save while playing. Bypass the dedupe for the position only, and
       let the launcher skip rewriting an unchanged art file.
     - Or show the snapshot without times.

5. **On the keypad stage, a long prompt runs over the back arrow at 320 dp.**
   - Where: `launcher/app/src/main/res/layout/activity_pin_lock.xml:45-72`. The 48 dp arrow sits at `start` in a
     FrameLayout, and the prompt is a centred `wrap_content`.
   - Measured with Nunito Bold at 16 sp in the 288 dp row (320 dp minus 2 x 16 dp padding). The arrow needs 48 dp
     on each side:
     - "Bare foreldrekoden virker nå" is 213 dp wide, leaving 37 dp each side;
     - "Only the parent code works right now" is 283 dp wide, leaving 3 dp each side;
     - the wait text ("Prøv igjen om 1:30", 139 dp) and the plain prompt (142 dp) fit at font scale 1.0, but run
       over from about 1.4x.
   - What happens: the prompt is drawn over the arrow, and the arrow still takes the taps.
   - Fix:
     - Give `pin_prompt` symmetric `layout_marginStart/End="48dp"` so it wraps between the two, or use a
       horizontal row: arrow | weighted text | 48 dp spacer.
     - Have `PinLockLayoutTest` count two lines for the prompt on the media stage.

6. **The parent's warning for missing listener access still talks only about unread counts.**
   - Where: `server/templates/device_detail.html:386`.
   - The status already carries `notification_listener_enabled` (`BadgeStore.accessGranted`). The device page
     warns, but its text names only the badges.
   - Since step 11 and step 4, the same access also drives:
     - the notification filter;
     - the "Spilles nå" card and the lock's media view;
     - the launcher's own pause before a suspension.
   - The parent can't tell why the music card is missing.
   - Fix:
     - Name the music card and the lock's media view in that warning (a server template change; step 4 touched no
       server code).
     - Optional, so fewer surfaces are lost without access: show the snapshot card anyway. It needs no listener;
       its button only opens the player.
     - Longer term: the music app can append its platform session token to `setNowPlaying` (a Bundle key, an
       appended contract name). With it the launcher builds `MediaController(context, token)`, which needs no
       permission, and the media surfaces stop depending on the adb step.

7. **TalkBack announces the lock's media view itself as a button that does nothing.**
   - Where: `launcher/app/src/main/res/layout/view_lock_media.xml:13-14`. The root is `clickable` and `focusable`
     so that touches never fall through to the keypad, which is right.
   - TalkBack then treats the root as actionable: it groups the clock and date into it and offers "double-tap to
     activate", which does nothing.
   - Fix: keep the touch blocking and add `android:importantForAccessibility="no"` on the root (its children stay
     reachable). Add a line to the device checks' TalkBack pass.

## Tests: gaps that matter

- **A suspended app refuses to play** (Medium 1): a pure `playAllowed` and a source scan of `KidPlayer`. Also the
  `AppEnforcer` order: pause, then `setPackagesSuspended`.
- **`EXTRA_RESUME` from history** (Low 3): `resumeRequested`.
- **The cap on explicit routing** (Low 1): only a device check can show it. Add it to step 4's list.
- The pure rules are well covered: `MusicMediaPlanTest`, `MusicCardTest`, `LockMediaTest`, `LockMediaGuardTest`,
  `LockMediaLayoutTest`, the media stage in `PinLockLayoutTest`, `ScreenTimeTest`, `TimeRulesTest`,
  `BadgeCountsTest`, and the `player` contract on both sides. The glue (`MusicSessions`, `MusicLockPanel`,
  `MusicHomeCard`, `VolumeCap`, `MusicForeground`'s screen-off clear) is only reviewable or device-checkable, as
  the doc's device-check list already says.

## Deviations: verdicts

1. **The stage is decided at lock-up with the screen on (`lockStageDue`): accepted.** The emergency dialer or an
   alarm coming back keeps the kid's stage, and `screenOffs` is the lock's existing counter.
2. **The keypad stage keeps the prompt, Nødsamtale and Foreldrekode, and the media view has none of the latter:
   accepted.** The media view's emergency button is the lock's own (`performClick`: same confirm, same flow).
   Foreldrekode stays reachable through "Lås opp". The arrow's placement needs Low 5.
3. **A live card without a session activity opens the player without resuming: accepted.** The music app always
   sets one (`PlaybackService.kt:158-164`).
4. **The cap index (floor, at least 1 or the stream minimum, <= 0 or >= 100 = no cap, 1.5 s re-check, read from the
   cache at start): accepted.** The route coverage is incomplete (Low 1).
5. **PiP: accepted for the kiosk** (lock task refuses PiP). With the kiosk off the hole is real (Low 2, user
   decision).
6. **The install deferral stays on `setNowPlaying`: accepted.** It is listener-free, as QA #8 asked.

## Notification-listener access (the question asked)

- **The device owner can't grant it.** There is no DPM API. `setNotificationListenerAccessGranted` needs a
  privileged permission, and sideloaded apps get "restricted setting" (`launcher/CLAUDE.md:72`).
- **The existing badges already require it.** The adb provisioning path sets it next to `dpm set-device-owner`
  (`cmd notification allow_listener`, design 19b P0), so phones set up that way have it. QR-provisioned phones
  (19b P1) don't, unless the parent grants it in Settings.
- **The failure mode is graceful:**
  - `MusicSessions.watch` catches the `SecurityException`, and `access = false` means no card and no media view.
    The lock is exactly the old keypad.
  - The next surface show tries again.
  - `pauseBeforeSuspend` catches it, and the music app's `SuspendedReceiver` pauses instead.
  - Nothing crashes, and no surface half-shows.
- **The parent sees it:** `notification_listener_enabled` is in every status report, and the device page warns.
  But the warning's text needs Low 6.

## Checked and fine

**The lock's media view weakens nothing**
- Its glue only calls the session's play, pause, previous and next, switches the stages, and calls the lock's own
  emergency button. `LockMediaGuardTest` scans for intents, `PendingIntent`, `sessionActivity`, settings and
  notifications. The only transport calls in `MusicSessions` are play, pause, skipToNext and skipToPrevious.
- Touches can't reach the covered keypad: the root is clickable and full-size, and the Parent code and digits stay
  under it. TalkBack can't reach the keypad either (`IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS` while covered).
- The VoIP ring screen lies above it, and Back does nothing.
- Only the music packages' sessions are used, and the session's package is set by the system from the owner's uid.
- Stages:
  - the media view only when the session plays at an interactive lock-up;
  - a pause keeps it, and the session's end gives the keypad;
  - the keypad never jumps back by itself;
  - with a live session, the keypad gets the arrow and the compact row.
- Keys stay >= 52 dp at 320x569 dp with both bars at 48 dp. Compaction is skipped while the clock and date are gone,
  and comes back when the session ends.

**Home's card**
- It is active only between onStart and onStop with the content shown. The modeListener, `showNight`, onStart and
  onStop all call `renderMusicCard`. It never shows while LOCKED: Home's gate and the pure rule both prevent it.
- A time-rule lock covers Home with `LockActivity` (`redirectToLockScreenIfLocked`), so the card is inactive then.
- It ticks only while playing.
- The session activity is sent with `ALLOW_IF_VISIBLE` on 36 and the legacy `ALLOWED` on 34/35, from a visible
  Home.
- The snapshot opens the exported `me.vibb.music.ui.MainActivity`, whose class name is the same for the debug
  build. Its names are pinned in `bridge_contract.json` on both sides.
- The snapshot art is <= 64 KB, written atomically on the binder thread and decoded once per change.
- `MusicNowPlaying` listeners are added, removed and run on main; reports post to main.

**Time rules and screen time**
- Budget lock (probe test):
  - a non-allowlisted music app stays hidden, suspended and unpinned;
  - an allowlisted one is usable and pinned in kiosk;
  - with apps unmanaged it is left alone.
- `LockActivity` lists only launchable, unsuspended apps. A rule keeps exactly its own exempt apps
  (`TimeRulesTest`). `KidModeEnforcer.lockNow` (no `musicApps`) is used only for the reason, so the lock keys
  agree.
- The lease:
  - `MusicForeground` is `@Synchronized`, and `inFront` is volatile;
  - every change posts `ScreenTimeTracker.update` to main, which folds the running stretch and re-arms or cancels
    the budget timer;
  - it is cleared at every screen-off (its own receiver) and at the token's death;
  - `LiveBridge` re-sends the state on every (re)connection, and the music app sends false in onStop and on
    entering multi-window;
  - the app keeps no screen on (`SourceScanTest`), so the auto-lock bounds a player left in front.
- The pause before suspension:
  - it runs only on the suspend edge and only for the music packages;
  - a play from the launcher's controls may start the music app's FGS, because `MediaSessionService`
    temp-allowlists a session's app for a controller that may start one (the device owner may).

**Volume cap**
- `VOLUME_CHANGED_ACTION` comes from the system uid (`sendBroadcastToAll` clears the calling identity), so it
  reaches a `RECEIVER_NOT_EXPORTED` receiver. The launcher's FGS keeps it from being deferred.
- The cap only lowers: never to 0, never on a muted stream (index 0), never on fixed-volume devices. The launcher's
  own `setStreamVolume` re-broadcasts and finds the stream within the cap, so there is no loop.
- The override and the pause lift it. It is refreshed by every `apply()` and read from the cache at process start.
  `pct` is volatile, and the clamp runs on main.

**Badge and notification grant**
- `isMediaNotification` checks only the key's presence; Android 13+ Bundles unparcel values lazily.
- The listener itself still reads no extras (`ElementRoomsTest`).
- The music app's "N nye" notification sets `number`.
- `POST_NOTIFICATIONS` is declared, and the device owner's `setPermissionGrantState` applies to it.
- A new music install requests a sync at once (`L/Application.kt:68-69`), so the grant follows the install by
  seconds. The music app never asks the kid for it.

**Contracts, lifecycle and storage**
- AIDL unchanged and byte-identical; `bridge_contract.json` byte-identical with `player` appended; `MusicPlanTest`
  and `BridgeTest` check the names and that MainActivity is exported.
- `MusicMedia.init` runs in `initRest`, after the user unlock. Home is not direct-boot aware. So the CE prefs reads
  are safe.
- Process death and reboot:
  - the snapshot is in CE prefs;
  - the lease, the stage and the session view live in memory and reset to the safe value;
  - the cap is re-read from the cached policy.
- `MusicLockPanel.stop()` runs in onStop and onDestroy (idempotent). `MusicSessions` unwatches when no surface
  listens. The crash-guard path (`finish()` before `music` is made) is null-safe.

## Needs a user decision

- **Low 2: is browsing the music app with nothing playing free time?** The answer also decides the kiosk-off PiP
  hole. The user's answer was "listening never counts", and the spec implements "the player in front never counts".
  QA recommends counting unless the music app is playing. That closes PiP videos with sound and an idle hour in the
  app, and costs nothing while a story plays.

**Answered** (user, 2026-10-11): "listening never counts" - time in the music app counts unless music plays. Applied
in the fix round with every other finding (design 21, step 4's "Fix round after QA's code review").
