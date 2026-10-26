# Code QA - branch `music-splash-b` (Round M1: the wordmark under the rings, the "no network" words)

- Branch `music-splash-b`, three commits over `main`'s `8d8ba426`: `0c4703d9` (the splash: the theme without
  `windowSplashScreenBrandingImage`, `ui/LaunchSplash.kt`, `MainActivity`'s one Box and `fadeSystemSplash`,
  `logic/Splash.kt`'s geometry, `SplashTest`, `scripts/music-splash.py`'s comments and `splash_wordmark.xml`'s header),
  `af1c9b77` (`logic/PlayFailure.kt`, `LocalRenderer.onPlayerError`'s log line, `RendererEvent.Error.noNetwork`,
  `PlaybackService.onError`, `PlayerHub.Message.NO_NETWORK`, `no_network` in both locales, `PlayFailureTest`,
  `GuardsTest`'s pins) and `6d323d52` (PLAN's Built note, `music/CLAUDE.md`, emulator.md §6u steps 12-16).
  `git diff 8d8ba426..6d323d52`: 20 files, music + docs only.
- Read: root, `music/` and `launcher/` CLAUDE.md; PLAN "Round M1" with the earlier Built notes and the incident's
  diagnostics; emulator.md §6u and §6m; the whole diff and the code around it (`MusicRoot`'s gate,
  `MusicViewModel.snapshot`/`nowRowRead`/`startSettled`, `Store.snapshots`, `PlaybackService.onError`,
  `MediaCache.errorCode`/`guarded`/`PublicDns`/`PrivateAddressException`, `redactUri`, the launcher's
  `DnsFilterEngine.blockedResponse`, `scripts/fake-audio.py`), and AOSP frameworks/base at `android14-release`,
  `android15-release` and `android16-release` (fetched from android.googlesource.com, cited below).
- Ran: the full music suite `testDebugUnitTest -PwarningsAsErrors=true`: **BUILD SUCCESSFUL, 1225 tests, 0 failures,
  0 skipped**. `python3 -I scripts/music-splash.py --selftest`: **ok** ("the splash drawables are the script's").
  Then 12 mutations (table below), each reverted; the tree is clean except for this file.
- The user's rules: no "Spotify" in a string (`SourceScanTest` green; the new strings are "Trenger internett" / "Needs
  the internet"); the DIDL and the Sonos code are untouched.

## Verdict on the two splash changes

**Dropping the held first frame: keep it** - for a cold start it is right, and simpler: the app's first frame *is*
the splash, so nothing is held, no ANR window, no `OnPreDrawListener` reading a flag every frame. AOSP shows why
nothing can show between the two splashes: with an exit listener set, the system splash goes only after the app's
first frame was drawn, and then not by removal but by a hand-over - `ActivityThread.createSplashScreen`
(android15-release `core/java/android/app/ActivityThread.java` 4545-4572) adds the copied `SplashScreenView` to the
app's `DecorView` on top of the content and hides the starting window's leash in the same frame
(`syncTransferSplashscreenViewTransaction`, `applyTransactionOnDraw`), and only then calls our listener
(`reportSplashscreenViewShown` -> `handOverSplashScreenView`). The app's first drawn frame already holds
`LaunchSplash`: `ComposeView` composes synchronously when it is attached in the first traversal (the lifecycle is
RESUMED by then), `animateFloatAsState` starts at its target 1f, and `painterResource` loads both vectors
synchronously. So the sequence is: starting window -> its copy over our identical first frame -> our 250 ms fade
reveals `LaunchSplash` -> its fade reveals the content composed under it. No plain night or white frame on that path.
**But** the instance flag that replaced the hold is wrong for a restored instance (Q-1, Medium): on the common
"process died in the background, the kid taps the tile" path the system splash now fades into a blank night screen -
the very artefact of the user's 2026-10-25 video.

**The custom exit animation: keep it** - it is needed and correct. android15/16-release
`libs/WindowManager/Shell/res/values/config.xml:80-83`: `starting_window_exit_animation_type` 0 = "radial vanish +
slide up"; `SplashScreenExitAnimationUtils.java:169-174` shifts the first window from +`mMainWindowShiftLength` to 0,
`starting_surface_exit_animation_window_shift_length` = 20dp (`res/values/dimen.xml:396` in 15). With the app's own
splash in the first frame, the default exit would slide the rings 20 dp. The listener is registered in `onCreate`
before `setContent` (before the first draw, `MainActivity.kt:67`); `remove()` is guarded (`SplashScreenView.remove`,
android15 `SplashScreenView.java:529-566`: `mHasRemoved`, a null `mWindow`, a detached decor - no crash when the
activity finished or recreated mid-fade, the animator only keeps the old instance 250 ms longer); `doOnEnd` also runs
after a cancel and at once with "Remove animations"; `View.ALPHA` goes through `SplashScreenView.setAlpha`
(`:439-447`). What's missing is a pin (Q-5).

## Findings

**Q-1 (Medium) - a restored instance shows no app splash, so the system splash fades into a blank night screen.**
`MainActivity.kt:66` sets `contentShown = savedInstanceState != null`. That is true for a configuration change, but
also for an activity recreated after its process died (or after the activity alone was destroyed) - and that start
gets the full system splash: android15-release `PhoneStartingWindowTypeAlgorithm.java:82-84` returns
`STARTING_WINDOW_TYPE_SPLASH_SCREEN` whenever `!processRunning || newTask || (taskSwitch && !activityCreated)`, before
any snapshot. The ViewModel is new, so `MusicRoot` draws nothing until the first snapshot, the stored row and the
carousel's start are in (`MusicRoot.kt:96-98`), up to the 3 s cap - while `LaunchSplash` is gone at frame one.
**Scenario:** the kid presses Home in the player (the activity stays in its task, `singleTask`, its state saved at
`onStop`), Android kills the idle process an hour later, the kid taps the music tile: the rings come up (the system
splash), our exit listener fades them out over an empty night screen for ~0.9 s, then the library pops in with no
fade. Before this branch `holdSplash` held the system splash over exactly that. **Fix:** decide it by the ViewModel,
which survives a configuration change and dies with the process or the activity: e.g. `MusicViewModel.splashDone`
(set by `onContent`), `contentShown` initialised from it, and no `savedInstanceState` in it (`open(..., restored)`
keeps its own use). That also covers a font-scale change inside the first seconds, before the content was shown
(today that recreate drops the splash with nothing composed yet). Update `music/CLAUDE.md`'s "a recreated instance
shows none" and the KDoc at `MainActivity.kt:52-56`. **Test:** none in JVM beyond a source pin; emulator check 17
below.

**Q-2 (Medium) - the guard's private-address refusal says "Trenger internett".** `MediaCache.PrivateAddressException`
extends `UnknownHostException` (`MediaCache.kt:106`; thrown by `PublicDns.lookup` and the network interceptor,
`:94`, `:102`), and `noNetwork` matches any `UnknownHostException` in the chain (`PlayFailure.kt:20`). So 21b §2.7's
refusal of a public entry whose host is on a private address - a decision about the item, with the network fine - is
now "Trenger internett / Needs the internet" instead of "Kan ikke spilles nå". **Scenario (any self-hosted family):**
a parent adds the family's own podcast feed from their NAS (`http://192.168.1.20/...`) without ticking "lan"; the kid
taps it at home on Wi-Fi and reads "Trenger internett", and the parent goes looking at the router instead of the
entry's LAN switch. The KDoc ("Only that") and PLAN's Built note ("everything else keeps today's words") say the
opposite. **Fix:** `.any { it is UnknownHostException && it !is MediaCache.PrivateAddressException }` (or a
`noNetworkCause(e)` in `logic/` that names the exclusion), the KDoc and PLAN's note. **Test:** `PlayFailureTest` -
`PlaybackException(..., IOException(PrivateAddressException("nas.local")), 2001)` keeps `CANT_PLAY`. Emulator: §6u
step 16 gets the public-entry case (below).

**Q-3 (Low) - the wordmark has no time of its own: on a fast start the two fades overlap and "vibb music" only
flickers.** The system splash's fade starts at the hand-over (`MainActivity.kt:85-90`, a few frames after our first
frame), the app splash's fade starts at `contentShown` (`LaunchSplash.kt:28`), and nothing orders them. When the
content is ready within ~250 ms of the first frame - a warm process (the `PlaybackService` alive with music paused, the
activity created anew), an empty library, a small library on a fast phone - both run at once: the rings (in both
layers) dim to ~75 % halfway and the wordmark, visible only through the fading system splash, peaks around 25 %
opacity, then the content. That is a flash, not "vibb music right under the rings", and the user's "nothing jumping at
the hand-over" reads it as one. Before the branch the wordmark sat in the system splash for the whole hold.
**Fix (pick one, the coordinator's call):** (a) the app splash fades no earlier than the system splash's exit end - a
`systemSplashGone` state set in `doOnEnd`, with a short fallback for a start that had no system splash (the snapshot
starting window: no listener call), or (b) when the content is ready before the hand-over, drop `LaunchSplash` without
a fade at the hand-over, so the system splash fades straight into the content. (a) keeps the wordmark on screen at
least the length of one fade; (b) is the less code. **Test:** a pure `splashFadeDue(contentShown, systemGone,
sinceFirstFrameMs)` in `logic/Splash.kt` if (a).

**Q-4 (Low) - `noNetwork` can't tell the parent's Web Filter from a DNS outage.** The launcher answers a blocked name
with NODATA (`launcher/.../server/DnsFilterEngine.kt:177-180`, `blockedResponse`; a custom AdGuard server's REFUSED
becomes the same, design 36 §5.4), which getaddrinfo turns into `UnknownHostException: ... No address associated with
hostname` - the same message as no network at all. The phone streams the enclosure's own host (the server only strips
tracking prefixes, `server/src/music_net.rs:263-290`), so an episode whose CDN host is on the parent's blocklist now
reads "Trenger internett" on a working network (before: "Kan ikke spilles nå"). Not fixable from the exception alone;
**Fix:** at least say it in `PlayFailure.kt`'s KDoc and PLAN's note as the accepted residual, or ask the user whether
"Trenger internett" should also need the active network not to be VALIDATED (which would also hide the incident's own
case - the filter's upstream down while Wi-Fi validates - so it is a real trade-off, the user's call).

**Q-5 (Low) - the exit listener and the app splash's geometry aren't pinned by any test.** Dropping `view.remove()`,
moving the wordmark by the rings' radius instead of `SPLASH_WORDMARK_BELOW_DP`, drawing the mark at 240 dp, and
showing the app splash on every recreate all leave the 1225 tests green (mutations 8-11). `SplashTest` pins the
constants against the theme and the drawables, but nothing says `LaunchSplash` uses them, centred, with no insets, or
that `MainActivity` registers the listener before `setContent` and removes the view. Without `remove()` the copied
view stays in the decor at alpha 0 for the activity's life, holding its parcelled icon bitmap (504 x 504 px ARGB, 1 MB,
at 420 dpi) - `remove()` is what recycles it (`SplashScreenView.java:534-542`). **Fix:** a source pin beside `GuardsTest`'s splash
(`MainActivity`: `splashScreen.setOnExitAnimationListener` before `setContent {`, `doOnEnd { view.remove() }`;
`LaunchSplash`: `fillMaxSize()` with no `windowInsetsPadding`, `requiredSize(SPLASH_MARK_DP.dp)` and
`offset(y = SPLASH_WORDMARK_BELOW_DP.dp)` both `align(Alignment.Center)`).

**Q-6 (Low) - emulator §6u step 16's first way doesn't make a name fail.** "The launcher's Web Filter on a custom DNS
server that doesn't answer (§6m)" sends every query to the parent's backup while the primary is unhealthy (design 36,
`UpstreamChooser`; the PWA prefills `https://1.1.1.3/dns-query`), so the names resolve and the episode plays. **Fix:**
a primary **and** a backup that don't answer (e.g. `tls://192.0.2.1` for both - TEST-NET-1, never routed), or keep only
the `--advertise nohost.invalid` way, which works (`.invalid` never resolves; fake-audio's entry is `lan`, so the
LAN client's plain `UnknownHostException`).

**Q-7 (Low) - step 15's overlay command may leave gesture navigation on.** The package is right - android15-release
`packages/overlays/NavigationBarMode3ButtonOverlay/AndroidManifest.xml`: `com.android.internal.systemui.navbar.threebutton`,
category `com.android.internal.navigation_bar_mode`, priority 1 - but the gestural overlay has the same category and
the same priority 1, so a plain `cmd overlay enable` of the three-button one leaves which wins to the order.
**Fix:** `adb shell cmd overlay enable-exclusive --category com.android.internal.systemui.navbar.threebutton`, and
check `adb shell settings get secure navigation_mode` reads 0. (Verified against the AOSP source, not against the
installed system images' overlay list - no emulator was started.)

**Q-8 (Nit) - `Splash.kt`'s KDoc says "clipped to the view's circle" and "unscaled".** The clip is the device's icon
mask (`MaskBackgroundDrawable` clips to `config_icon_mask`, android15 `SplashscreenIconDrawableFactory.java:173-223`),
and the drawing isn't unscaled: `ImmobileIconDrawable` rasterises the icon into a `starting_surface_default_icon_size`
= 108 dp bitmap and draws it scaled by 192/108 (`:110-138`; `loadInDetail` is false for `windowSplashScreenAnimatedIcon`,
`SplashscreenContentDrawer.java:612-616`) - the *geometry* is the 288 dp canvas, but the system splash's rings are a
1.78x upscale, softer than the app's vector. Worth one sentence (the cross-fade sharpens the rings; nobody should chase
it as a scale step). For the mask: every AOSP icon-shape overlay keeps the rings whole - computed from their
`config_icon_mask` paths (android11-release `packages/overlays/IconShape*Overlay`), the smallest inner radius is
pebble's 48.1 / vessel's 48.0 of 50 = 92 dp against the rings' 84.6 dp.

**Q-9 (Nit) - "24 dp under the rings" is measured from the ring's widest point, not its lowest.** `SPLASH_RINGS_RADIUS_DP`
and `SplashTest:42` take the outer ring's largest `hypot` (25.46 viewport units -> 84.63 dp), but the hand-drawn
polyline's lowest point is y = 25.02 (-> 83.24 dp with the stroke), so the visible gap from the rings' bottom ink to
"vibb music" is 25.4 dp. **Fix:** measure `max(y)` for "under" (and keep the radius for the clip check), or say "from
the rings' outer radius" in the docs.

**Q-10 (Nit) - `noNetwork` walks the cause chain unbounded.** `PlayFailure.kt:20` has no `take`, `failureLine` has
`take(8)` (`:34`). `initCause` refuses only self-causation; a cycle A -> B -> A (two wrapping layers that each set the
other) makes `.any` loop forever on the main thread, an ANR. **Fix:** one shared `causes(e) = generateSequence(e) {
it.cause }.take(8)` for both.

**Q-11 (Nit) - the log call itself is unpinned.** `failureLine` is tested, but `LocalRenderer.onPlayerError` could log
anything: mutation 12 (logging `error` raw ahead of the line - its `toString` carries Media3's and OkHttp's messages)
stays green. **Fix:** pin `Log.w(LOG_TAG, failureLine(error.errorCodeName, error.errorCode, error))` next to the emit
line `GuardsTest:1101` pins, and that the file has no other `Log.` taking `error`.

**Q-12 (Nit) - step 12's "don't move a pixel" is a pixel too strict at some densities.** The system's foreground
bounds truncate (`AdaptiveForegroundDrawable.updateLayerBounds`, `(int) (bounds.width() / (DEFAULT_VIEW_PORT_SCALE *
2))` and `cX = width / 2`, `SplashscreenIconDrawableFactory.java:254-262`). Worked through (`mIconSize` and
`mDefaultIconSize` by `getDimensionPixelSize`, `mFinalIconSize *= 1.2f` truncated, `SplashscreenContentDrawer.java:323-337,
614`): 320/420/480/640 dpi land exactly on 288 dp; 240 dpi (the test AVDs' `wm density 240`) draws the canvas at 430.2
px instead of 432 (the rings' edge 0.35 dp, half a pixel, inside); 400/560 dpi likewise; 440 dpi (S = 297, odd) also
centres it 0.9 px up and left. Say "at most a pixel" in step 12.

Not findings, checked:
- **AOSP's numbers** - `starting_surface_icon_size` 160dp and `starting_surface_default_icon_size` 108dp
  (`core/res/res/values/dimens.xml` 993/995 in 14, 1018/1020 in 15, 1214/1216 in 16); the no-background factor is
  `NO_BACKGROUND_SCALE = 192f / 160` in 14 (`SplashscreenContentDrawer.java:128`) and the float dimen
  `splash_icon_no_background_scale_factor` 1.2 in 15 and 16 (Shell `dimen.xml:590` / `:671`), applied when
  `mIconBgColor` is transparent or the theme colour (`:612-614`); a non-`Animatable`, non-adaptive icon is wrapped in
  `AdaptiveForegroundDrawable` (`SplashscreenIconDrawableFactory.java:79-85`), whose bounds are `1 / DEFAULT_VIEW_PORT_SCALE`
  = 1.5 x the view (`DEFAULT_VIEW_PORT_SCALE = 1f / (1 + 2 * 1/4)`, `:175-176`). 160 x 1.2 x 1.5 = 288 dp; the copy the
  app gets is the same F x F bitmap (`SplashScreenViewParcelable.copyDrawable`, `SplashScreenView.java:750-768`), centred
  in the same window. The implementer's geometry holds; the old "240/160" was the with-background case.
- **The drawables and the script** - `splash_vibb_mark.xml` is 288 dp, translate 144/144, group scale 3.3645, rings 0.94,
  stroke 2.6: (25.46 + 1.3) x 0.94 x 3.3645 = 84.63 dp; `splash_wordmark.xml`'s ink spans y 28.85..51.14 = 22.29 dp,
  centred at 40.00 in the 80 dp box (absolute M/L/Q/Z only, so `SplashTest`'s y regex reads absolute coordinates).
  `SplashTest` reads the real theme and both drawables (mutations 1-3 and a regenerated drawable break it), and the
  selftest says the committed drawables are the script's.
- **Edge to edge** - the starting window has `setFitInsetsTypes(0)`, `FLAG_LAYOUT_IN_SCREEN`, and (targetSdk 36,
  enforced) cutout mode ALWAYS (`SplashscreenContentDrawer.java:226-263`); the app's `enableEdgeToEdge` content view
  spans the same window; `LaunchSplash` sits in the root Box, outside `MusicRoot`'s `windowInsetsPadding`. The starting
  window is a plain `FrameLayout` added by WindowManager (no `PhoneWindow`, no contrast scrim), its bars light-iconed
  for a dark background (`SplashscreenWindowCreator.java:209-221`); the app's `SystemBarStyle.dark(TRANSPARENT)` gives
  light icons and no enforced contrast. Same bars before and after; portrait-locked, so no rotation recreate (a tablet
  with Android 16's ignored orientation recreates like a font-scale change).
- **The fade over the content** - `MusicRoot` composes under the splash from the first frame; the heavy first content
  composition happens in the frame `contentReady` turns true, under the opaque splash, and the fade starts on the next
  frame, so a slow composition delays the fade's start, never stalls it midway. Touches pass through the splash (no
  pointer input) - fine during the 250 ms.
- **The cap and the empty library** - `contentReady` and `capped` are unchanged; `!entries` is ready at once. Note: the
  cap now counts from the first composition and only once a snapshot exists - a snapshot that never comes keeps the
  splash up (before: a blank screen after 3 s). `Store.snapshots()` always emits (every source emits on collection), so
  this is a wording point only: `SPLASH_CAP_MS`'s KDoc already says so.
- **The breathing** - the reasoning holds: `AnimatedVectorDrawable.reverse()` is `@hide` (absent from the android-36
  `android.jar`; `javap` shows only `start`, `reset`, `registerAnimationCallback`), and the launcher's AVD is one inhale.
  For the record, if the user wants it back: `music-splash.py` could write an AVD whose `objectAnimator`s carry
  `android:repeatCount="infinite"` and `android:repeatMode="reverse"` - a breath in and out on the RenderThread, shown
  through `AndroidView { ImageView }`, no new dependency (PLAN's "a drawable with its exhale").
- **C** - the log line cuts query, fragment and user info of every `scheme://` URL in every cause's message (nested
  causes too: the regex runs per message), so a minted Storytel URL's `token=` can't reach it through a message;
  Media3's HTTP exceptions carry no URI in their messages (the `DataSpec` is a field), OkHttp's name only the host.
  Order offline -> NO_NETWORK -> CANT_PLAY as claimed; both locales have `no_network`; `onError`'s item error, the
  fail-run lap and the move-on are unchanged (only the emitted message differs); `GuardsTest`'s three pins moved with
  the signatures.

## Mutations

| # | Mutation | Caught by |
|---|---|---|
| 1 | `SPLASH_MARK_DP` 160 x 1.2 x 1.5 -> 160 x 1.5 (240 dp) | `SplashTest` "the first frame's mark is the system splash's" |
| 2 | `SPLASH_WORDMARK_GAP_DP` 24 -> 16 | `SplashTest` "vibb music sits 24 dp under the rings" |
| 3 | `windowSplashScreenBrandingImage` back in the theme | `SplashTest` "the first frame's mark is the system splash's" |
| 4 | `SPLASH_CAP_MS` 3 000 -> 5 000 | `SplashTest` "the cap is a backstop" (a value inside 1-4 s passes by design) |
| 5 | `noNetwork` also on `ConnectException` | `PlayFailureTest` "an HTTP 403, a decoder error and a server that is down keep today's words" |
| 6 | the log line's URL cutting removed (`{ it.value }`) | `PlayFailureTest` "the log line names the code and every cause, never a URL's query" |
| 7 | `failureMessage`: NO_NETWORK before offline | `PlayFailureTest` "a name that can't be looked up is the no-network words" (the offline assertion) |
| 8 | `doOnEnd { view.remove() }` -> `doOnEnd { }` | **escaped** (Q-5) |
| 9 | the wordmark offset by `SPLASH_RINGS_RADIUS_DP` | **escaped** (Q-5) |
| 10 | `contentShown = false` on every create | **escaped** (Q-1, Q-5) |
| 11 | the mark drawn at `requiredSize(240.dp)` | **escaped** (Q-5) |
| 12 | `onPlayerError` logs `error` raw ahead of the line | **escaped** (Q-11) |

8-12 ran together in one full suite run (1225 green); 1-7 one at a time against their test class.

## Suite

`flock ~/build.lock ./gradlew --no-daemon --max-workers=2 -PwarningsAsErrors=true testDebugUnitTest` from `music/`:
BUILD SUCCESSFUL, 1225 tests, 0 failures, 0 errors, 0 skipped. `python3 -I scripts/music-splash.py --selftest`: ok.

## Counts

0 High, 2 Medium (Q-1, Q-2), 5 Low (Q-3 to Q-7), 5 Nit (Q-8 to Q-12).

## Emulator checks to add (§6u)

- **17. A restored instance** (Q-1): open the player, Home, `adb shell am kill me.vibb.music.debug` (a background
  kill keeps the task and its saved state), tap the tile: the system splash's rings, then "vibb music" under them, then
  the library fading in - no plain night frame (record at 24 fps as step 12). The same with "Don't keep activities"
  on and the music paused in the service (a warm process, a new activity).
- **18. A warm start's two fades** (Q-3): with music paused (the service alive) and "Don't keep activities" on, tap the
  tile and step the recording: whatever Q-3's fix chose - the wordmark held at full strength, or the system splash
  fading straight into the library - never a faint wordmark flash.
- **Step 16 additions** (Q-2, Q-6): a **public** RSS entry (not `lan`) pointing at fake-audio on 10.0.2.2 - "Kan ikke
  spilles nå", and the log's `PrivateAddressException`; the custom-DNS way with both servers unreachable (`tls://192.0.2.1`).
- **Step 15** (Q-7): `cmd overlay enable-exclusive --category com.android.internal.systemui.navbar.threebutton`, and the
  bottom 48 dp of the frames before and after the hand-over compared (the navigation bar's ground unchanged).
- **Step 12** (Q-8, Q-12): at 240 dpi allow a pixel on the rings' top edge, and note the softer rings before the fade.

## Fix round (2026-10-26, the implementer)

Every finding fixed; nothing skipped. Full music suite `testDebugUnitTest -PwarningsAsErrors=true`: 1229 tests, 0
failures, 0 skipped (1225 + 4 new tests). `python3 -I scripts/music-splash.py --selftest`: ok.

- **Q-1** -> `MusicViewModel.contentShown` (set by `onContent`) decides: `MainActivity` reads `val splashOwed =
  !vm.contentShown` before `setContent` and draws `LaunchSplash` only then. A configuration change keeps the view model
  (no splash); a restored task after a process death, or a new activity, has a new one (the splash again). No
  `savedInstanceState` in it (`open(..., restored)` keeps its own). `music/CLAUDE.md` and the KDoc follow; pinned by
  `SplashTest`'s glue test; emulator §6u step 17.
- **Q-2** -> `noNetwork` excludes `MediaCache.PrivateAddressException` (the app's only other `UnknownHostException`
  subclass); `PlayFailureTest` row (`IOException(PrivateAddressException("nas.local"))` keeps `CANT_PLAY`); KDoc and
  PLAN's note; step 16's public-entry case.
- **Q-3** -> option (a): `logic/Splash.kt` `SystemSplash` (UNKNOWN/FADING/GONE) and the pure `splashFadeDue(contentShown,
  system, handOverWaitOver)`: the app splash fades only once the content is shown **and** the system splash's own fade
  has ended, or no hand-over came within `SPLASH_HANDOVER_WAIT_MS` (500 ms from the app splash's first composition).
  "At once" for a start without a system splash isn't knowable: the hand-over comes asynchronously a few frames after
  the first draw, so 500 ms is the shortest wait that doesn't misread a late hand-over (such starts are rare: every cold
  or warm start with a new activity gets the splash). `SplashTest` pins the table; step 18.
- **Q-4** -> the accepted residual in one line each in `PlayFailure.kt`'s KDoc, `music/CLAUDE.md` and PLAN's Built note.
- **Q-5** -> the listener's body is the pure `systemSplashExit(fade, remove, state)`: FADING, the fade, then at its end
  (a cancel's too) always `remove()` and GONE in a `finally`; `SplashTest` drives it (also a `remove` that throws).
  A source pin: `fadeSystemSplash()` before `setContent {`, the listener calling `systemSplashExit` with
  `ObjectAnimator.ofFloat(view, View.ALPHA, 0f)`, `doOnEnd { onEnd() }`, `remove = view::remove`; `LaunchSplash` on
  `fillMaxSize()` with no insets or padding, the mark at `requiredSize(SPLASH_MARK_DP.dp)` and the wordmark at
  `offset(y = SPLASH_WORDMARK_BELOW_DP.dp)`, both `align(Alignment.Center)`, the fade from `splashFadeDue`. Mutations
  8-11 now break it.
- **Q-6** -> step 16: both the primary and the backup unreachable (`tls://192.0.2.1`), the `.invalid` way kept.
- **Q-7** -> step 15: `cmd overlay enable-exclusive --category com.android.internal.systemui.navbar.threebutton`, then
  `settings get secure navigation_mode` = 0, and the bottom 48 dp compared.
- **Q-8** -> `SPLASH_MARK_DP`'s KDoc: the device's icon mask, the 108 dp bitmap drawn 1.78x (the fade sharpens the rings,
  not a scale step); `SPLASH_RINGS_RADIUS_DP`'s: inside every AOSP mask (pebble's 92 dp). The clip check in `SplashTest`
  is now `< 92 dp`.
- **Q-9** -> measured from the lowest point: new `SPLASH_RINGS_BOTTOM_DP` 83.24 (the outer ring's max y 25.02 plus half
  the stroke, x 0.94 x 3.3645), and `SPLASH_WORDMARK_BELOW_DP` = it + 24 + ink/2 (the ink now starts 107.2 dp under the
  centre, was 108.6); `SplashTest` reads both the radius (clip) and the lowest point (the gap) from the drawable and says
  which is which. PLAN, CLAUDE and step 12 follow.
- **Q-10** -> one `causes(e)` (`take(8)`) for `noNetwork` and `failureLine`; `PlayFailureTest` "a cause cycle ends the
  walk" (A -> B -> A: false, and an 8-cause line).
- **Q-11** -> `GuardsTest` pins `Log.w(LOG_TAG, failureLine(error.errorCodeName, error.errorCode, error))` and that it is
  `LocalRenderer.kt`'s only `Log.` call (mutation 12 breaks it).
- **Q-12** -> step 12 allows a pixel (the 240/440 dpi truncation) and names the sharpening.
