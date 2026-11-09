# Music notes: Spotify (design 26c)

Moved from music/CLAUDE.md on 2026-11-01.

## Rules that are easy to break (continued): Spotify

- **Spotify is the third renderer, the web player** (design 26c §5, §5.10): a Spotify entry on a phone output gets
  `SpotifyRenderer` (`phoneRendererFor`; made once per service and kept; `local.standDown()` when it attaches), every
  other entry the `LocalRenderer`, a room its `SonosRenderer` - a room's queue drops Spotify (`speakerDropReason`
  `spotify`, "Kan ikke spilles på Sonos"; Spotify on Sonos is 26c's later step 9). `RendererKind.onPhone` (LOCAL,
  SPOTIFY) replaces `=== local` at every site `GuardsTest` lists. The sound is local audio (noisy, the volume cap,
  the 10 s sleep fade through `vibb.volume`; no hush). **The audio focus is the WebView's own** (26c §5.6 as the
  emulator check §6l A answered it, F2): Chromium's media session takes it when the page's audio starts, pauses on a
  loss, ducks, and plays again after a transient loss - the renderer requests none (a request of its own was taken by
  its own WebView at every play) and reads those pauses (`spotifyPauseCause`: not ours is `FAULT`) and resumes
  (`spotifySelfResumeShown`) from the page's states. A pause the WebView made while the kid wanted it playing is kept
  as `heldByPage` (no 5-minute idle close; the call rule's `callStep` counts it as playing only within
  `SPOTIFY_HELD_RECENT_MS` (5 s) of its start - `heldRecently`, so `CALL_RELEASE` plays again whichever of `CALL_HOLD` and
  the ring's pause came first, but another app that took the audio for good is never started again by a later call's
  end: qa-26c-emu-music-code M1), and a pause asked during it (`askedPaused`: the kid,
  the sleep timer, the call rule) pauses Chromium's own resume at once; during a call (`inCall`: the launcher's, or a
  system call by the audio mode - `spotifyInCall`, and `callOn` lets the mode drive the call rule on the web player as on
  a room) no start goes out and the player shows paused (qa-26c-6l-fixes-code H1, M1, L2). The session never says playing
  while nothing plays (`pwr && !ended`); the launcher sends `CALL_HOLD` at every call's begin instead (below). **The session's `SpotifyPlayer` calls the renderer only qualified**
  (`this@SpotifyRenderer.play()`): unqualified, `play()` is the SimpleBasePlayer's own and recursed (F1;
  `SpotifyRendererTest` drives it through Media3's `Player` API with the test-only `android.os.Looper`,
  `android.util.Pair` and `SparseBooleanArray` in `src/test/java/android/`). Those stand-ins shadow the mockable
  android.jar for every JVM test of the module (the jar is last on the test classpath): a new one there needs its reason
  in its header, and must keep the mockable jar's answers except where a test needs the platform's. **List mode plays our list**: `PUT /me/player/play` with
  the track uris of a window of at most 50 from the current item and `position_ms` (the resume place, so nothing
  sounds from 0:00), past the window the next item added with `POST /me/player/queue` one track ahead, the account's
  shuffle and repeat set to the entry's wanted ones first (`spotifyModes`: a device session's first play, after the
  kid changed them, and whenever a state says they differ - design 49 §1.5). Pause, resume and
  seek are the SDK's (no Web API call); steps within 300 ms coalesce into one play; Spotify's own advance sends
  nothing (the natural-advance seek is the phone's own player's only, `resumesOnAdvance`).
- **Only our tracks are ours** (`SpotifyTracker`, 26c §5.4): a state counts only when it names the uri we sent for the
  current item or the expected next one (by uri, the SDK's `linked`, or the server's title + a duration within 2 s);
  anything else - Autoplay after the list, another Spotify app transferring to "Vibb", our own queue add left in
  Spotify's queue, a stale state from before a start - is paused at once, never saved, never mapped to an index;
  after `Ended` every playing state is paused. Unplayable comes only from the SDK (a `playback_error`, or a skip the
  state's `prev` proves), three in a row stop; the play call's 403 is an account matter (`refused`/`not_premium`,
  "Spør en voksen"), never an item error. A `null` state counts only when a second look 2 s later confirms it:
  "Spilles et annet sted", the bookmark kept. `saveNow` writes only a measured position (the last state's
  of the current item, as received).
- **Audiobooks** (2026-11-09; the server lists a show of `type: "chapter"` items as the book's chapters, played as
  `spotify:episode:<id>`, its free "sample" chapter left out, and marks the library entry `audiobook`): at a start a
  playing state of a **later item of our window** is ours on evidence - `prev` proving the skip (the items between
  unplayable, H1), or it is the track of the last state before the call **played afresh** (the emulator's book:
  Spotify played the listed sample, from where an earlier try stopped, in place of the chapter; before, it waited
  10 s and stopped "trenger internett") - and the queue moves there (`Started(j)`). A later uri with neither (a cold
  start's state of the account's earlier session of the list, `start.json`) is passed over as before. What a start
  passes over: the old play's next and our adds by uri, the sent track away from the sent place (L1), and the last
  state before the call **carried on** (its track, paused or playing as it was, at its place moved on by the time
  since) until a fresh state comes. On an
  `audiobook` entry a fresh playing state of no item of ours at a start is Spotify playing something else in the
  book's place (its sample: no audiobook listening time on the account - Premium Family members need the plan
  manager's "Audiobooks+ for Plan Members"): `NotOurs` + `NoListeningTime`, paused, `SpotifyProblem.NO_LISTENING_TIME`
  - "Lydboka trenger lyttetid" / "The book needs listening time" on its tile only, no stored word. Any other entry's
  start still passes over a foreign state. `scripts/fake-spotify.py --audiobook --no-book-time` plays the book's
  sample in place of its chapters.
- **Nothing starts by itself on Spotify either**: the page - and with it the Connect device "Vibb" - exists only from
  the first Spotify play, never at a restore (a stored Spotify queue loads paused: no page, no network); a play the
  renderer would start on its own (`NewWindow`, `ShuffleOrRepeat`, the move-on after an unplayable, a step Spotify plays
  while paused) goes on only with a play the kid wants now (`playWhenReady`, `playAllowed`); a step the network gate
  refuses says the line and stays (`seekTo(index, ms)`'s belt; the service tells the screens `PlayerHub.Message.STAYED`,
  which ends the instant switch's hold with no toast - F3). The page is destroyed at every suspension (`suspended()`: the
  bookmark with force, the pause - no Connect device while suspended), 5 minutes after the last pause (the SDK's
  `disconnect()` first), when the renderer process dies, on the token's `off` and when the token's account changes.
  **A detached web player owns nothing**: its queue is null while another renderer plays (`queue = {
  active.takeIf { renderer === spotify } }`) and `detached()` pauses every playing state until its next `load`
  (qa-26c-step5b-code M1). `onError` stays synchronous on main for a Spotify item: the renderer reads the
  `seekTo`/`prepare`/`play` made inside its `Error("unplayable")` as its own move-on (`GuardsTest`).
- **Context mode** (26c §5.9; the library's `spotify: "context"`: an artist, a playlist no family account may list):
  the snapshot gives the entry one synthetic item (`contextItem`: key `context`, no url, no duration, titled by the
  live track once one played), so the tile, the stored queue and resume work. A start is `context_uri` - a playlist
  from `offset.uri` = the live track at its place (an offset no longer in it: one more play from the top), an artist
  from Spotify's start (no resume: `contextResumes`, its place shows 0). Next and previous are the SDK's, sent only
  while a track of our context is on the player. Every track of the context is ours (a `null` context only for our
  live track or the one named next); anything else is paused; the end only on evidence; no unplayable item, no
  "Spor N av M", never marked done (`keepsDuration`). The live track (uri and title) is
  `noBackupFilesDir/spotify_context.json` - the store's, written through the renderer's ports, pruned on a parsed
  library, dropped at the context's end.
- **Spotify streams under the streaming switch** (design 30's `stream_mobile_data`, never the downloads switch or the
  storage limit; `spotifyPlayability` = `streamAllowed`, never `GONE`; the web player's own `networkLine` reads the
  same switch). Refused, the queue is loaded paused at its place with "Trenger Wi-Fi" / "Trenger internett"
  (`refused(line)`), never `NOT_DOWNLOADED`, never another item. Nothing of Spotify is fetchable, kept, written
  through, a played extra or a claim: `KeepItem.fetchable` false, `downloadPlan` prunes any Spotify row,
  `markPlayed`/`updateHeld` only on the phone's own player, and a queue's claim follows **its** kind -
  `queueKind(renderer.kind, entry)` in `playEntry`, `playItem` and `restore`, then `claimsQueue` (never the attached
  renderer's kind, which is still the web player right after a Spotify play: qa-26c-merge-code M1).
- **The lines and the word**: the renderer's `RendererState` (`SPOTIFY_*`, `remote` false - `callStep` reads
  `remote != true`) is the player's quiet line and the loaded tile's; the account word (`ok`, `reauthorize`,
  `refused`, `not_premium`, `no_widevine`, `unreachable`, `quota`; `none` from the token's `off` and from `forget()`)
  is `noBackupFilesDir/spotify_state.json`, written only on a change, shown on **every** Spotify tile and sent as the
  report's `spotify`.
- **"Kan ikke spilles nå" on the player's cover** (2026-11-09, the user's mockup D, `ui/CantPlay.kt`): the quiet line
  under the entry name was too easy to miss ("Spør en voksen" small and grey while play looked normal). Now, when the
  loaded item can't play here, its cover is greyed and darkened (the mockup's CSS `grayscale(1) brightness(.28)
  contrast(.8)`: `coverGreyLevel`, the picture through `coverGreyMatrix`, the fallback gradient through `coverGrey`
  and its category glyph left out - it would cross the notice at the centre; in the carousel only the page the pager
  rests on while it shows the loaded item, `noticePage` - the neighbours keep their colours) and a calm notice sits on
  it - a 34 dp "!" disc in peach at 22 %, a bold line, a smaller line - and the line under the entry name goes (said
  once). `cantPlayNotice(state)` is the rule, one exhaustive `when` (`CantPlayTest`):
  - **The notice** (the web player refused the item on this phone): `SPOTIFY_ASK_ADULT` "Kan ikke spilles nå" / "Spør
    en voksen om hjelp"; `SPOTIFY_NO_LISTENING_TIME` "Lydboka trenger lyttetid" / "Spør en voksen om hjelp";
    `SPOTIFY_NOT_ON_PHONE` "Virker ikke på telefonen" / "Spør en voksen om hjelp"; `SPOTIFY_NEEDS_WIFI` "Trenger
    Wi-Fi", `SPOTIFY_NEEDS_INTERNET` "Trenger internett", `SPOTIFY_UNAVAILABLE` "Ikke tilgjengelig nå" (the title
    alone: no adult is needed). The titles are the lines' own strings; `cant_play_now` and `cant_play_ask_adult` are new.
  - **The line stays** for what isn't a refusal of the item: a room's four (`GROUPED_AWAY`, `TAKEN_OVER`,
    `LOST_SESSION`, `UNREACHABLE` - the speaker's state, not the item's; a tap or the next poll may bring it back, and
    design 50 §1.4 dims nothing on a room), `SPOTIFY_ELSEWHERE` (the account plays elsewhere: a tap takes it back) and
    `SPOTIFY_STOPPED` ("Stoppet - trykk spill": the tap is the fix).
  - **Play is greyed** (`playOff`: `Palette.track`, its glyph white at 45 %, the play button's TalkBack state the
    notice's title) only when a tap can't help: `SPOTIFY_ASK_ADULT` and `SPOTIFY_NO_LISTENING_TIME`, both waiting on an
    adult. It stays lit for Wi-Fi, internet, the quota and Widevine (a tap may work: the network back, the quota over,
    a new web player; `SPOTIFY_NOT_ON_PHONE`'s help line against lit play is with the user, qa-music-cant-play L3).
    Greyed is a look only: play is still a button and does what it did (behaviour stays vibb's and the renderer's) -
    nothing pushes an adult's fix in the PWA to the phone, so the kid's tap is the retry.
  - **A retry keeps the notice** (qa-music-cant-play M3): the web player clears its line at every start (`begin`) and
    sets it again only when the start fails, so the cover would flash back to colour and play to peach for each tap.
    `MusicRoot` holds the last refusal (`holdRefusal`, `HeldRefusal`: the state and its run, `PlayerHub.State.entryId`)
    while an attempt in the same run is on its way and shows it (`shownState`); a real playing state, another line or
    another run ends the hold. Only the account's and the book's (`heldOverRetry`: `SPOTIFY_ASK_ADULT`,
    `SPOTIFY_NO_LISTENING_TIME`, whose refusal comes after an attempt; the network lines come from the gate before any
    start and never flashed - held, a stale "Trenger Wi-Fi" would stay over a good retry), and only over the web
    player's start (`webPlayerStarting`: `connecting` off a Sonos room - on a room it is the speaker's connect;
    qa-music-cant-play-2 V1, V2). Next and previous in the run keep it too (an account's or a book's refusal is the
    run's; the next item's start says otherwise by playing). The time row reads the live state, so it says "Kobler
    til…" under the still greyed cover meanwhile, and greyed play keeps its play glyph (`showsPause`; a tap still does
    what it did - here it pauses the attempt).
  - **The notice fits its cover** (qa-music-cant-play M1, M2): the cover is 224 dp at most and shrinks (a shuffle row
    on the Jelly Star: 150 dp; a large font or display size; the carousel's pages down to 96 dp). The text is dp, not
    the font scale's sp (`NOTICE_TITLE_DP` 15, `NOTICE_HELP_DP` 12, as `SKIP_NUMBER_DP`: a fixed box), 6 dp at the
    sides, the title up to three lines, the help up to two. The overlay measures its words in its own box and
    `noticeFit` drops what doesn't fit - the disc, then the help line, then the title (the "!" alone); the player then
    brings the line under the entry name back (`headerLineText`: the title gone - the state's line; the help line gone -
    the state's line where it says more than the title, "Spør en voksen" under "Kan ikke spilles nå", else the help
    itself, "Spør en voksen om hjelp" under "Lydboka trenger lyttetid"). FitTest measures every notice in both languages
    at 224, 150, 120 and 96 dp: all of it from 150, title and help from 120. English says "Can't play now" (one line).
  - Every button stays where it was and keeps working - previous, next and ±15 skip past one broken track (a
    favourites queue's, say). No red, nothing blinking (the launcher-look rule).
  - TalkBack: on the single cover the overlay is its own node - the notice's two lines as its description and a polite
    live region, said when they appear. On a carousel page (a button, which merges its children) the overlay is silent
    and the page carries the notice as its state description and live region; its label stays its own ("Spill X").
- **No source on the kid's screens** (26c step 8, the user's decision of 2026-10-14): a Spotify entry is a tile like any
  other. No string value says "Spotify" (`SourceScanTest` pins both languages, the `ui/` sources' literals, and those of
  `playback/`, `bridge/` and `sync/` - the notification, the lock screen and Home's card - with two allowlisted non-kid
  literals; it also pins the session's metadata, `setNowPlaying` and the web player's live title to the entry's name and
  the titles; string *names* like `renderer_spotify_*` and `hint_spotify_picks` are code and stay). The kind is the
  content's: `kindOf` gives a Spotify show or episode `Kind.PODCAST` ("Podkast", episodes) and a playlist, album, track
  or artist `Kind.MUSIC` (`kind_music` "Musikk", tracks) - there is no `Kind.SPOTIFY`. The player has no source line
  under the title, "Liste" shows nothing in the offline summary's place (a context: "Musikk · rekkefølgen velges for
  deg"), and the web player's lines are plain ("Trenger Wi-Fi", "Trenger internett", "Ikke tilgjengelig nå", "Spør en
  voksen", "Virker ikke på telefonen", "Spilles et annet sted", "Stoppet - trykk spill"; the cover's notice: "Kan ikke
  spilles nå", "Spør en voksen om hjelp"). This is a stated deviation
  from Spotify's Developer Policy II (attribution where its content is shown; 26c §7.3): the attribution is the parent's
  PWA's only. Don't add the word, a logo or a source line back without the user.
- **The web player's WebView** (26c §5.2, §7.2): one WebView in the process, `SpotifyWebHost`'s, made with the
  application context by the service and **never attached to a window** (`SourceScanTest`: `WebView(`,
  `android.webkit` and `addJavascriptInterface` only in that file, no `addView`); JavaScript and DOM storage on, media
  without a gesture, file and content access off, no geolocation or multiple windows, `RENDERER_PRIORITY_IMPORTANT`,
  third-party cookies (the SDK's iframe), Safe Browsing and metrics off (the manifest), no `androidx.webkit`. Our page
  is `assets/spotify/player.html` under `https://appassets.androidplatform.net/spotify/` with a CSP; the SDK comes from
  `https://sdk.scdn.co/spotify-player.js` at every load, **never pinned and never copied** (Spotify's terms). Every
  request of every frame, and of service workers (`ServiceWorkerController`), passes `WebPolicy.decide`: our two
  assets, https on 443 to a host of the Spotify list, `blob:`/`data:`, else an empty 403 (counted; the host only, in
  the debug log). WebSockets and a redirect's target bypass it - bounded only by the launcher's DNS filter and the
  CSP. No navigation; the protected-media permission only, only to `sdk.scdn.co` or our origin. The bridge
  `VibbSpotify` exposes exactly `SpotifyPage.FROM_PAGE` (`ready`, `notReady`, `needToken`, `state` at most 8 KB,
  `error`), each validating and posting to main; calls into the page go through `SpotifyPage.call`, every argument
  quoted. The stores (web storage, the HTTP cache, cookies - awaited) are cleared on an account change, the token's
  `off` and a library without Spotify (`forget()`); the page's account (`"<id>:<generation>"`) is kept in
  `noBackupFilesDir/spotify_page_account`, so no token of another account reaches a page whose storage is the old one's.
- **The Spotify token** (26c §5.5, §6.1): from the launcher only - `getSpotifyToken` (appended to the AIDL, answered on
  `ISpotifyTokenCallback`, 10 s; the launcher asks `POST /api/devices/music/spotify/token` and caches nothing) ->
  `TokenResult`. `TokenCache` keeps it in memory only (never a file, Room, the report or a `toString`), hands out one
  with 5 minutes left, shares one call in flight, cools down (`reauthorize` 10 minutes, `busy` its wait), asks once
  more 5 s after an `unreachable` at a start (the server's 8 s budget), and while playing refreshes at 10 minutes left
  (again every 2 minutes until the old one expires), so the SDK's hourly `getOAuthToken` is answered from memory
  with the screen off. A 401 renews once; the same token back or a second 401 is `refused`. `Authorization`/`Bearer`
  appear only in `StorytelApi.kt` and `spotify/SpotifyApi.kt`; the Web API calls use the guarded public-only client.
- **Spotify secrets** (26c §7.1): the access token, the SDK's device id and the page's state never reach a log, Room,
  `music_state`, the report or a `toString`. **No `Log.` in `spotify/`** at all: the host, the locks and the renderer
  log through the function the service gives them, `spotifyLog` (`Log.d` - the service's one Spotify log line - under
  `spotifyDiagLogOn`: always in a debug build, in a release only while the parent's **diagnostic log** runs, Device ›
  Advanced's 15 minutes, 2026-11-01: the policy's `music.spotify_diag_until` -> the managed config's
  `spotify_diag_until` (`bridge_contract.json`) -> `PlayerHub.spotifyDiagUntilS`, set by `MusicApp`'s config edge; an
  end more than `SPOTIFY_DIAG_MAX_AHEAD_MS` ahead is junk), with fixed words only (hosts, statuses, kinds, item
  indexes, whole seconds; never a token, an account or device id, a uri, a title or a state JSON), the SDK's error text
  only in a debug build. The diagnostic lines (2026-11-01): a start (the page up or new, the device known) and why it
  isn't a resume, the play call that went out, every state as `SpotifyTracker.judged` reads it (playing or paused at
  N s; the current, next or a later item, not ours; a track from before the play call; how far from the sent place)
  and the mode it leaves with its events (`eventWord`), the first-state wait, the gap watch and the idle close, the
  page's calls by name (no argument; not `volume`), its load, `not_ready`, and the page's own `diag` lines (its
  visibility, a 5 s tick more than 2 s late - Chromium throttling it; `SpotifyPage.diagLine` passes only those fixed
  shapes). The page is never paused: no `onPause`/`pauseTimers` anywhere. **The page's console is a log channel too**:
  `onConsoleMessage` returns true always (a debug build notes the level and the source's host only). The token
  reaches the page only as a quoted `evaluateJavascript` argument. **The state recorder is debug-only**:
  `SpotifyRecording.recorder` is null in main and assigned only by `DebugSpotify.kt` while `config.json` says
  `"record_spotify_states": true` (one daemon thread, `debug-feed/sdk_states/<UTC>.jsonl`, 32 MiB a file, the page's
  compact states, its events and our calls by name - never a token or the SDK's text); `HostEvent.State`'s
  `toString` leaves the JSON out.
- **The Spotify debug switch** (26c §9.3, 26b's pattern): `SpotifyEndpoints.debugBase` is null unless the debug feed's
  `config.json` sets `spotify_base` (`http://10.0.2.2:8731`); only `DebugSpotify.kt` assigns it (the receiver and
  `DebugStartup`). Exactly six readers, all in `SpotifyEndpoints.kt`, each taking the base only for 10.0.2.2 or
  loopback: `apiBase()`, `apiOverLan()`, `sdkUrl()` (`https://fake-spotify.invalid/sdk/spotify-player.js`),
  `webHosts()`, `webProxy()` (the interceptor fetches `https://fake-spotify.invalid/<path>` from the base over the LAN
  client, `Range` forwarded, so the page sees https only and mixed content stays blocked) and `debugTokens()` (the
  fake's token route, only while the app is unmanaged; a managed phone's token comes through the bridge). With the
  switch set only a `FAKE-ACCESS-` token goes on (`debugSwitchToken`). The cleartext config doesn't widen the web
  player: `WebPolicy` refuses every http URL. The emulator loop is docs/testing/emulator.md §6l: A1 the whole chain
  (the PWA, the server with its loopback bases, the debug launcher, the managed music app), A2 the phone alone on the
  debug feed, B the real SDK with the user's account (the recorder, the DNS capture).
- **Owed for Spotify** (26c "Release"): §6l A ran once (2026-10-14, `docs/testing/2026-10-14-spotify-6l-a.md`; its
  fixes are 26c's "Fixes after the emulator check §6l A") - its re-run is steps 5b and 6's acceptance; part B, its recorded real `sdk_states/` and the DNS capture that replaces the provisional host list
  (one commit of the list, the constants and the comment: the three `spotify_hosts.json` copies, the six constants -
  this app's two in `SpotifyHosts.kt` among them - as 26c's "Release" names it); the Jelly Star checks of 26c's QA
  checklist (real Doze for 2.5 hours with the token refreshed ahead of expiry, the WebView's own audio focus with the real SDK
  (the fake's stub answered it, §5.6), the protected-media origin, episodes in a `uris` list - no switch is built: if the SDK refuses them, the server's parser
  must refuse shows and episodes -, the `context` field around track changes,
  Autoplay after a list and after a context, the leftover queue add, shuffle and repeat).
