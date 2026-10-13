# QA 26c step 4 code review - the music app's Android-free Spotify core and the fake Spotify

Scope: branch `music-3-spotify`, `726f533..a10e7fe` (one commit, 44 files: `music/.../spotify/{SdkState,SpotifyApi,
SpotifyEndpoints,SpotifyHosts,SpotifyHttp,SpotifyTracker,TokenCache,WebPolicy}.kt`, `logic/Spotify.kt`,
`assets/spotify/player.{html,js}`, `src/test/resources/spotify/`, nine test classes, `scripts/fake-spotify.py`,
`.github/workflows/music.yml`, root `CLAUDE.md`, the design's status), read in full against root and `music/CLAUDE.md`,
design 26c (§0.4, §1.1, §4, §5.1-§5.5, §5.8, §5.9, §6.2, §7.2, §9, §10's music rows, §11 step 4, the design QA fix
round and every status section to "Step 4 implementation status" with its 9 deviations and 6 spec findings),
`qa-26c-step3-code.md`, `qa-26b-step2a-code.md`/`qa-26b-step2b-code.md` (the template), `spotify-spike.md` §7b and the
spike's `web-playback.html`/`WebPlayer.kt` (branch `spotify-spike`). Line numbers are `a10e7fe`'s.

Sources read today (2026-10-13), developer.spotify.com: the Web Playback SDK reference (`WebPlaybackState`: `context.uri`
"can be null", `disallows`, `paused`, `position`, `repeat_mode` 0/1/2, `shuffle`, `track_window` with `current_track`,
`previous_tracks`, `next_tracks` "number can vary"; `WebPlaybackTrack` has no `linked_from` and no `duration_ms` in the
documented sample; `duration` appears only in the `player_state_changed` example; `getOAuthToken` "every time you run
connect or when a user's access token has expired (maximum of 60 minutes)"; `activateElement` "to keep the playing
state during transfer from other applications"), Start/Resume Playback (`device_id` a query parameter, body
`context_uri`/`uris`/`offset` - "only available when context_uri corresponds to an album or playlist" -/`position_ms`;
responses **204, 401, 403, 429 only**), Add Item to Playback Queue (`POST /me/player/queue?uri=&device_id=`, 204/401/
403/429), Toggle Playback Shuffle and Set Repeat Mode (`PUT /me/player/shuffle?state=`, `/repeat?state=`, both with
`device_id`, "the order of execution is not guaranteed"), Transfer Playback. The reasons `NO_ACTIVE_DEVICE` and
`PREMIUM_REQUIRED` are on none of those pages; community reports (community.spotify.com, via search; the forum itself
isn't reachable from here) show a play call's **404 `NO_ACTIVE_DEVICE`** ("Player command failed: No active device
found") and 403 `PREMIUM_REQUIRED`, and July 2026 reports of **intermittent 403 `PREMIUM_REQUIRED` that an immediate
retry cured**, and of an ordered `uris` play that sometimes starts at another uri of the array.

Runs, here (the worktree, JDK 21, `music/local.properties`):
- `./gradlew assembleDebug assembleRelease testDebugUnitTest lintDebug -PwarningsAsErrors=true` BUILD SUCCESSFUL; then
  `testDebugUnitTest lintDebug -PwarningsAsErrors=true --rerun-tasks` BUILD SUCCESSFUL: **679 of 679** JVM tests fresh
  (624 before), lint 0 errors / 39 warnings, none in a file step 4 touched.
- `python3 -I scripts/fake-spotify.py --selftest` "FAKE SPOTIFY SELFTEST OK" (8 parts); `fake-storytel.py` and
  `fake-sonos.py --selftest` OK.
- Mutations, each reverted: `UNPLAYABLE_STOP = 4` -> `SpotifyTrackerTest` red (2); the prompt retry off -> `TokenCacheTest`
  red; the "same token" check removed -> `TokenCacheTest` red; `initialization_error` and `account_error` swapped in
  `player.js` -> **everything green** (L5).
- Counter-examples written as JVM tests against the tracker (a scratch `QaCounterTest`, not committed; the five tests are
  quoted under H1, M1, L1 and L2): all five **red** on `a10e7fe`, with the events quoted below.
- `git diff --stat`: no Gradle file, no `verification-metadata.xml`, no shared contract file (AIDL, `bridge_contract.json`,
  `music_*.json`, `spotify_hosts.json`) touched; no `import android` in a step-4 main file; `SPOTIFY_PLAYABLE` still
  `false`; nothing in `main/` outside `spotify/` uses the new classes yet. `git status` clean before this file.

**Verdict: not ready; a fix round first.** Counts: **1 High, 1 Medium, 8 Low.** Most of the core is right: the Web API
requests match the docs byte for byte, 403 is never an item error, the token lives in one header and redacted holders,
the debug switch has its six readers each behind the fake-homes parse, the page has no inline code and the CSP §5.2
asks for, `SdkState` reads the documented state defensively, the token cache shares one ask, keeps a late answer and
can't loop on a same-token answer in practice, and the fake is stdlib-only and its selftest really checks the shared
fixtures and the stub-vs-`player.js` parity. The High is in the tracker's one heuristic that §5.4 left open: **any later
uri of ours is read as "Spotify skipped everything in between"**. Two ordinary sequences break it - our own queue add
left in Spotify's queue after the kid picks another track, and a stale state after a "previous" - and give false
`unplayable` item errors (the parent's card, the list's "Kan ikke spilles"), stop the renderer after three, or pause
our own track. The Medium is the account's shuffle and repeat, which nothing reads or turns off although our list
playing in order depends on both being off.

## Findings

### H1 (High) - a later uri of ours is always read as Spotify skipping the tracks between; two common sequences make that false

`SpotifyTracker.onOurs` (`SpotifyTracker.kt:245-258`) takes any uri of the window past the expected next as ours
(`laterOfOurs`, `:380-384`) and `advance` (`:278-292`) then reports **every** item between as `Unplayable`
(`:281`, with `unplayable(index)` too when the current one played < 3 s), the third in a row `stop` (`:307-313`). At a
start, `onStarting` (`:229-241`) does the same when the state plays and names the sent uri in its `prev` - the guard the
status (deviation 6b) says keeps "a stale state of what played before" out. Neither holds:

1. **Our own queue add, left in Spotify's queue** (§5.4 "Past the window"). On a queue longer than the window the
   renderer adds item `known + 1` with `POST /me/player/queue`. Spotify's play queue (the "Next in queue" of every
   Spotify app) is not part of the `uris` context and survives a new play [device check: QA checklist, below]; `played()`
   (`:145-157`) forgets that anything was added. The kid then picks an earlier track, so the new window contains the
   added item; after the picked track Spotify plays the leftover add, and the tracker reads the window's tracks before
   it as skipped:
   ```kotlin
   val t = SpotifyTracker().apply { load((0..9).map { track(it) }, 0) }
   t.played(0, 4)                                            // a window of five
   t.onState(state(0, 0), 0); t.onState(state(1, 0, prev = listOf(0)), 20_000)
   t.onState(state(2, 0, prev = listOf(0, 1)), 40_000); t.onState(state(3, 0, prev = listOf(0, 1, 2)), 60_000)
   t.onState(state(4, 0, prev = listOf(1, 2, 3)), 80_000)    // [ItemChanged 4 auto, QueueAdd 5]
   t.queued(5)                                               // Spotify's queue holds item 5
   t.played(1, 5)                                            // the kid picks item 1: a window 1..5
   t.onState(state(1, 0, prev = listOf(2, 3, 4)), 90_000)
   t.onState(state(5, 0, prev = listOf(3, 4, 1)), 110_000)   // Spotify plays its queue first
   // built: [Unplayable(2), Unplayable(3), Unplayable(4, stop=true)] - three false item errors and a stop
   ```
   With the real sizes (a 60-track playlist, window 50, a pick after track 50 started) it is up to 46 `Unplayable`
   events in one state, all recorded as `item_errors` (`onError`'s Spotify row, §5.10) and shown as "Kan ikke spilles".
2. **A stale state after a step back.** "Previous" on track 2 sends a window from item 1 (`played(1, ..)`); the page's 5 s
   tick of the old track 2 arrives before the play took effect, and its `prev` names item 1 - by history, not by a skip:
   ```kotlin
   t.played(0, 5); t.onState(state(0, 0), 0); t.onState(state(1, 0, prev = listOf(0)), 20_000)
   t.onState(state(2, 0, prev = listOf(0, 1)), 40_000)
   t.played(1, 5)                                            // "previous"
   t.onState(state(2, 3_000, prev = listOf(0, 1)), 43_100)   // stale: built [Unplayable(1), Started(2)]
   t.onState(state(1, 0, prev = listOf(0)), 43_400)          // the real start: built [NotOurs(playing)] - ours paused
   ```
   The same for a pick of any of the last three tracks played. (The status' mutation list has "a transfer-in read as an
   end", none for a stale state at a step back.)

Both sequences need nothing unusual - a playlist over 50 tracks and a pick, or a "previous" within 5 s of a tick - and
the community report of an ordered `uris` play sometimes starting at another uri of the array is a third way in at a
start. The cost is wrong data the parent sees, a list that stops, and our own track paused - the opposite of what the
ownership rule is for.

Required change (code and §5.4's text; the tests below, plus the existing sequences, green):
- **A skip needs evidence.** Items count as skipped by Spotify only when each is named in the state's `prev` and none of
  them was added to Spotify's queue by us; anything else that names a later uri of ours is not a skip: no `Unplayable`.
- **Our leftover add is recognised.** The tracker keeps the uris it asked to add (`QueueAdd` ... `queued`) across
  `played()` until one of them is seen playing; a state naming one that isn't the expected next is that leftover -
  `NotOurs(playing)` (pause it) and `NewWindow(index + 1)` when the current item ran out, else `NewWindow(index)` (one
  `PUT`: the add was consumed by playing). Alternatively drop the add and accept §5.4's gap at each window's end - the
  design's choice to make, but the add's leftover must then not exist.
- **A start passes over the pre-play state.** `played()` records the uri of the last state seen before it; in `STARTING`
  a state with that uri is stale unless it is the sent uri at a position within a few seconds of the sent `position_ms`
  (which also fixes L1), and a later uri is a start-skip only when it isn't that uri and the skipped items are in `prev`.
- Tests: the two sequences above as `SpotifyTrackerTest` cases (no `Unplayable`; the second gives `Started 1` and no
  `NotOurs`), and an `sdk_states/` sequence for each (`queue_leftover`, `step_back_stale`); keep `unplayable.json`
  green (its skip names the skipped uris in `prev`).
- The fake's stub models Spotify's queue: an add goes after the current track and survives a new `play`
  (`fake-spotify.py:772`, `_command`'s `queue` branch, and its `play` branch, which today replaces the whole queue), so
  §6j part A shows the leftover; the selftest checks it.
- QA checklist: an item 20 [device check] - a playlist over 50 tracks, let track 50 start (the add), pick track 3, let it
  play out: the next track is 4 (or a short pause and 4), no "Kan ikke spilles" anywhere; the debug log shows whether
  Spotify played the leftover add.

### M1 (Medium) - the account's shuffle and repeat are neither read nor turned off

§0.4 decides "shuffle off for Spotify", but only for the phone's own `canShuffle`; nothing turns Spotify's shuffle off.
The SDK's state has `shuffle` and `repeat_mode` (0 off, 1 context, 2 track; the reference above), `SdkState` drops both
(`SdkState.kt:63`, `KEYS`; `player.js:59-76`, `compact`), `SpotifyApi` has no shuffle or repeat call, and the fake's stub
always posts `shuffle: false, repeat_mode: 0` (`fake-spotify.py:827-828`). Shuffle and repeat are the account's player
state, set from any of the kid's Spotify apps [device check: whether a new SDK device inherits them; Spotify's apps keep
both across contexts]. With shuffle on, the window plays in Spotify's order:
```kotlin
t.played(0, 4); t.onState(state(0, 0), 0)
t.onState(state(3, 0, prev = listOf(0)), 20_000)      // built [Unplayable(1), Unplayable(2), ItemChanged(3 auto)]
t.onState(state(1, 0, prev = listOf(0, 3)), 40_000)   // built [NotOurs(playing), NewWindow(4)] - our own track paused
```
After H1's fix the first state is no longer a skip, but every out-of-order track is still "not ours" - a pause and a new
window after each track, re-shuffled each time. With repeat 2 the current track loops forever and the list never moves
(each loop reads as our own track at 0:00, so it is saved and never ends); with repeat 1 the window's end loops to its
first uri (read as not ours plus an end - safe, but only by luck).
Required change: `SdkState` reads `shuffle` (a boolean) and `repeat` (0..2) and `player.js` posts them (both sides of
`KEYS`, `PlayerJsTest`); `SpotifyApi.shuffle(token, deviceId, false)` and `repeat(token, deviceId, "off")`
(`PUT /v1/me/player/shuffle?state=false&device_id=`, `PUT /v1/me/player/repeat?state=off&device_id=`, 204 awaited before
the play - the Player API doesn't order calls) sent once per device session before its first play and again whenever a
state says either is on; the tracker reads no skip, end or foreign track from a state with shuffle on or repeat 2, and
emits one event asking the renderer to correct them (then a play from the current index). §5.4 gets a bullet; the fake
gets `--shuffle`/`--repeat` (the stub shuffles until the shuffle call arrives); `play_request.json` the two requests.
The two calls cost two Web API requests per device session (the developer account's shared quota, §1.1). QA checklist:
shuffle and repeat on in the kid's Spotify app, then play on the phone - in order, once [device check].

### L1 (Low) - a stale state of the same track at a restart is taken as the start's measurement

`onStarting` (`SpotifyTracker.kt:223-227`) accepts any playing state of the sent uri as `Started`, and `observe`
records its position. A restart of the playing track ("previous" deep in it, sent as a window at 0 - `skipTarget`) whose
first state is the old tick gives `measured` = 125 000 ms for a track now at 0:01 (scratch test "a stale state of the
same uri at a restart is no measurement": built `125000`). It lasts until the next post (at most 5 s), but a save in that
window writes a position of the previous play - "never a guess in Room" is about exactly this kind of value. Fix: with
H1's start rule (the pre-play uri is stale unless it is near the sent `position_ms`); a test.

### L2 (Low) - a state that names no track pauses our own playback

`player.js:67` posts `uri: null` when `track_window.current_track` is missing; `SdkState` keeps such a post
(`SdkState.kt:80-94` needs only `paused` and `position`), and in `OURS` it matches nothing, so `onOurs` emits
`NotOurs(playing)` and goes `LOST` (scratch test "a state without a track does not pause ours": built
`[NotOurs(playing=true)]`). Whether the real SDK posts a track-less active state around a track change or a buffering
moment isn't documented [device check, §6j part B]; if it does, every such moment pauses the kid's music. A state with
no `uri` and no `name` is evidence of nothing. Fix: pass it over in `STARTING` and `OURS` (the 30 s hold covers a run of
them; a run past it is §5.4's "no state for 30 s"); a test.

### L3 (Low) - "the same token back" is detected by its expiry, not by the token

`TokenCache.keep` (`TokenCache.kt:156-161`) counts an `Ok` as a refresh when `result.expiresAtMs > old.expiresAtMs`.
The server answers its kept token with `expires_in_s` floored to whole seconds (`music_api.rs:371`) and the phone stamps
`now + expires_in` when the answer arrives (`tokenResult`), so the same token asked twice can come back with an expiry
later by up to a second plus the difference in latency, and reads as refreshed: `refreshFailedAt` clears, `prefetchDue`
is true again at once. In practice the server's own 10-minute margin equals the prefetch's, so this costs one extra
ask, not a loop - but the deviation's claim rests on luck. Fix: `result.token != old.token || result.account !=
old.account` (keeping the later expiry for a same token); a test with a same token one second "later".

### L4 (Low) - after a 401 the renewed token is the same one, so the retry can't help and the line says "internett"

`SpotifyApi.start` renews once after a 401 (`SpotifyApi.kt:178-185`) through `TokenCache.invalidate` + `get`, but the
token route can't say "this one was rejected": the server's `Accounts::mint` has a `rejected` argument
(`spotify.rs:1537-1541`) that the phone route never passes, and it hands out its kept token while 10 minutes are left.
So a token Spotify refused (access removed at spotify.com/account/apps, a revoked grant) comes back unchanged, the second
play is 401 again, and the start is `Unreachable("unauthorized")` - "Spotify - trenger internett" and the stored word
`unreachable` for up to 50 minutes, until the server's own refresh reaches `invalid_grant` and `reauthorize`. Not
fixable inside step 4 (the route is the contract). Fix: say it in §5.4/§5.8 (the 401 row: one renewal, which helps only
when the server already refreshed), and note for a later contract change: an optional `{"rejected": true}` on the token
route through a new appended bridge method.

### L5 (Low) - `PlayerJsTest` pins the event names and the error kinds as two sets, not which reports which

`PlayerJsTest.kt:73-77` checks the set of `addListener` names and the set of `report('<kind>'` words separately. Swapping
`initialization_error` and `account_error`'s kinds (`player.js:112`, `:114`) stays green - here, by mutation - and a
phone without Widevine would then read "not Premium" on every tile and the parent's card. Fix: assert the pairs (each
`addListener('<x>_error', ... report('<x>'`, and `autoplay_failed` -> `autoplay`).

### L6 (Low) - the page's CSP is new relative to the spike, and a violation would be silent

The spike's `web-playback.html` ran the real SDK with no CSP at all. `player.html:11` now applies §5.2's policy to our
frame (`default-src 'none'`, `script-src` our origin and the SDK's, `frame-src` the SDK's, `connect-src https: wss:`,
`media-src https: blob:`, `img-src https: data:`). It looks right for what the SDK's loader does in our frame (its script,
its iframe on `sdk.scdn.co`; the audio and EME are in Spotify's iframe, under its own policy), but if the real loader
ever needs another directive (a worker, an inline style attribute, another frame host), `ready` never comes and the kid
reads "trenger internett" with nothing in any log. Fix: a `securitypolicyviolation` listener in `player.js` reporting
`error('csp', <violatedDirective + blocked origin>)` (a new `ERROR_KINDS` word, debug log only, no problem/line), and
§6j part B's list names "no CSP line in the debug log".

### L7 (Low) - one 403 decides "not Premium" / "refused" for every tile

`classify` (`SpotifyApi.kt:247-254`) and `finish` turn the first 403 into `NotPremium`/`Refused`, which the renderer
stores (`spotify_state.json`) and every Spotify tile then shows as "spør en voksen" until a play succeeds; the parent's
card says no Premium. Spotify's forum has July 2026 reports of intermittent 403 `PREMIUM_REQUIRED` on Premium accounts
that an immediate retry cured. Anecdotal, so optional: one retry of a 403 after about 1 s, within the start's deadline,
before it counts (a `SpotifyApiTest` row). If not taken, §5.4's 403 row says why.

### L8 (Low) - the spec body still says what step 4 deliberately doesn't build

As after step 3 (qa-26c-step3-code L6): §5.3's post (`26c-spotify.md:777`) lacks `context` (deviation 1) and, after M1,
`shuffle`/`repeat`; §5.2 says "the only substitution" (deviation 2: two slots of one value); §5.1's `WebPolicy` row
(`:707`) names `Asset`/`Pass`/`Block` only (deviation 3: `NotFound`, `Proxy`, `blob:`/`data:`); §5.5's `get(minLeftMs,
deadline)` (`:883`, deviation 5) and its "a same token is no refresh"; §5.4's "Spotify skipping past one of our uris"
(`:869`) needs H1's rule, and the end rule's carried position (deviation 6a) and 6c-6h belong in its bullets; §9.2's
fixture list (deviation 8a) and the stub's GET protocol (8b). Fix: amend those lines (text only).

## The implementer's deviations (9, all judged)

1. **`context` in the post and `SdkState.contextUri`**: accept. §5.9 needs it, and the post's key list is pinned now on
   both sides; the documented `context.uri` "can be null" maps to null (`player.js:74`, `SdkState.kt:93`).
2. **Two slots of one value in the template**: accept. `SpotifyPage.html` derives the origin from the one URL (port kept
   only when not 443), refuses a non-https URL, user info or a query, escapes both into attributes; the release page's
   CSP equals §5.2's string exactly (`PlayerJsTest`), the debug one admits only `https://fake-spotify.invalid`.
3. **`NotFound`, `Proxy(url)`, `blob:`/`data:` pass**: accept. `data:` and `blob:` are in-memory (nothing leaves the
   phone), the CSP lets them only into `img-src`/`media-src` (never `script-src` or `frame-src`), and
   `shouldOverrideUrlLoading` refuses every navigation (§5.2), so neither can carry a script or a page. `Proxy` exists
   only when `webHosts()` is non-empty, i.e. with the switch (`WebPolicyTest`).
4. **`start` as a suspend function with its waits passed in; `runInterruptible(IO)`**: accept. Every blocking call runs
   in `runInterruptible(Dispatchers.IO)`, each cut to the deadline, and the OkHttp `callTimeout` bounds what an interrupt
   can't (a socket read).
5. **`get(deadlineMs, minLeftMs, atStart)`; the prefetch's pure half; "same token = failed"**: accept, with L3.
   `reauthorize`/`busy`/`unreachable` during a refresh keep the old token until it expires (right: the SDK keeps
   playing); `busy`'s wait (step 3's `expiresInMs`) holds both `get` and the prefetch timer (`nextPrefetchAtMs` includes
   `busyUntil`); `off` clears everything; two asks at once share one `Deferred` (tested), and an ask whose waiter gave up
   is kept (tested). The token is in memory only, `Ok.toString()` redacted, the log callback gets fixed words.
6. **The tracker's readings:**
   - a (the end with the last playing position carried forward, end detection only): accept - `measured` stays as
     received; the carry is bounded by §5.4's 30 s hold, and only follows a playing state.
   - b (a later uri = skipped tracks; the `prev` guard at a start): **reject as built** - H1.
   - c (relinking on the sent track too; an episode by its name alone): accept. The name-only match needs `type ==
     "episode"`, the listing's duration within 2 s, and only the current or the expected next item; the server lists an
     episode by its name (`spotify.rs:2271-2273`), so the artist-joined title can never match an episode. A foreign
     episode with the same name and a duration within 2 s is the only false match - acceptable.
   - d (Autoplay after a played-out window: `NotOurs` + `NewWindow`, after the queue `NotOurs` + `Ended`): accept; the
     pause comes before the new window in the event order (`onOurs:255-256`).
   - e (an idle player pauses every playing state): accept - §5.7's accepted moment, and "nothing starts by itself".
   - f (the count restarts on the kid's own play, not the renderer's move-on; one per item): accept (tested both ways,
     and by mutation).
   - g (a playing state under our pending pause passed over; a paused state under 1 s is 0): accept.
   - h (no measurement until a state of ours): accept, with L1 for a stale state of the same uri.
7. **`SpotifyLine.UNAVAILABLE`; `no_widevine`'s tile "spør en voksen"; `busy` = unreachable; `spotifyNetworkLine`**:
   accept. §5.8 offers two tile phrases and `no_widevine` needs an adult (a WebView update, or a family list blocking
   Widevine provisioning); the loaded tile still says "virker ikke på denne telefonen". Wi-Fi vs internet follows
   `streamAllowed`'s own inputs, so the line never disagrees with the gate.
8. **The fake** (2a's fixture names; GETs with the fake token in the query; `/fake/as`, `--print-library`, `--tracks`,
   `--not-premium` both ways, timers from the first play; the stub checked as text): accept. The token in a query is a
   `FAKE-ACCESS-<n>` to the fake's own routes, produced only by the stub, which only the debug proxy can load; the real
   SDK and `player.js` never put a token in a URL (`PlayerJsTest` bans `fetch(`, `http`, `location.` in `player.js`).
   Step 5a's proxy must log the host only, never the proxied URL (it carries that query) - owed below.
9. **`SourceScanTest`'s readers name 5a's files**: accept. The scan is real for what it reads: one declaration and six
   `parse(debugBase)` reads in `SpotifyEndpoints.kt`, no other main file names `debugBase`, each reader's callers in
   `src/main/java` limited to the named file. Two blind spots, fine for now: a caller through
   `import ...SpotifyEndpoints.apiBase` or `with(SpotifyEndpoints)` isn't seen, and the assignment check is
   `all { == "DebugSpotify.kt" }`, vacuously true until 5a adds the file - 5a should make it `== listOf("DebugSpotify.kt")`.

## The spec findings (6)

All six are right: the post needed `context`; one slot couldn't serve both the script tag and the debug CSP; the
literal end rule misses the end with 5 s posts; §9.3's proxy can't carry a body or decide CORS (5a must forward `Range`
and return the status, `Content-Type`, `Content-Range`, `Accept-Ranges`, `Access-Control-Allow-Origin`); `sdk_states/`
can't be recorded without a browser; §9.2's fixture names were 2a's. One addition to the "synthetic" note: recordings
from the fake's stub would only prove the stub (it is ours too); the evidence for the real shapes is §6j part B - the
debug build should log each compact post there (fixed keys, no token), and a few real sequences go into `sdk_states/`.

## Checked and fine

- **The play request** (`SpotifyApi.kt:122-136`, `PlayBody.json`): `PUT {base}/v1/me/player/play?device_id=<id>`, body
  `{"uris":[...],"position_ms":n}` (no `offset`: documented only for a context), the context form with `offset.uri` and
  `position_ms` only with an offset; transfer `{"device_ids":[id],"play":false}`; the queue add `POST
  .../queue?uri=&device_id=` with an empty body; `play_request.json` is compared byte for byte on the wire
  (`SpotifyHttpTest`) and accepted by the fake. Device id always a query parameter.
- **Outcomes**: any 404 is `DeviceUnknown` by status, not reason, so the undocumented `NO_ACTIVE_DEVICE` (the community's
  word, matching `play_answers.json`) can't matter; one transfer, its 204 awaited, then `awaitDevice` (≤ 3 s) and one
  more play; 401 once; 403 `PREMIUM_REQUIRED` -> not premium, any other 403 -> refused, never an item error (no
  `Unplayable` path from `SpotifyApi` at all); 429 `QUOTA_EXCEEDED` -> quota, else one `Retry-After` ≤ 5 s inside the
  deadline; everything else `Unreachable` with a fixed word. `reason` is read only as `[A-Z_]{1,40}`.
- **HTTP**: redirects never followed (both flags, pinned), no interceptor, 64 KB body cap, `callTimeout` per call,
  `SpotifyRequest`/`SpotifyResponse` print no header and no body; `Authorization`/`Bearer` only in `StorytelApi.kt` and
  `SpotifyApi.kt` (`SourceScanTest`, real).
- **`SdkState`** against the reference: null -> `Inactive`; a missing `current_track` -> null fields (L2 for what the
  tracker does with it); `next_tracks`/`previous_tracks` empty -> `[]`, at most 3; `position`/`duration` numbers (a
  string, NaN or negative refused, clamped to a day; `duration` 0 -> null); `type` only `track`/`episode`/`ad`; uris by
  grammar (`spotify:local:` -> null); names and artists capped. `restrictions`/`disallows` are not needed by any rule.
  The 8 KB cap counts UTF-16 characters - 5a's bridge cap must count the same way (owed). `listingTitle`/`cutTitle`
  equal the server's `track_title` + `cut_title` (Unicode White_Space split, `Cc` dropped, 200 code points).
- **`WebPolicy`**: our origin only for the two asset paths (else 404), https on 443 without user info to an
  `isSpotifyHost` host, `blob:`/`data:`, the fake's name only via the proxy with the switch; http, other ports and hosts
  blocked; `isSpotifyHost` equals the server's and the launcher's rule and its constants equal the shared file
  (`SpotifyHostsTest`). `SpotifyPage.call` quotes every string as JSON (U+2028/2029 too) and refuses an unknown entry
  point.
- **The debug switch**: `parse` takes only `http` on `10.0.2.2`, `127.0.0.1`, `localhost` or `::1` without user info
  (Storytel's set); `webProxy` refuses anything but `https://fake-spotify.invalid:443`; `debugTokens()` is the only
  builder of `debugToken`'s URL and null without the switch (whether 5a passes only that is 5a's to show). Nothing
  assigns `debugBase` yet.
- **`player.js`/`player.html`**: GPL headers, no third-party code (the SDK only by URL, unpinned), no inline script or
  handler, `player.js` before the SDK; the bridge's five methods and ten entry points match `SpotifyPage`; the token goes
  only to `getOAuthToken`'s callback, never the DOM, storage, a URL or a log (`PlayerJsTest`'s bans); `enableMediaSession`
  absent; every event and error wired; the 5 s tick only while playing; `activateElement` at connect and resume (with
  §5.2's `mediaPlaybackRequiresUserGesture = false` it changes nothing about a foreign transfer, which §5.7 accepts
  either way).
- **`logic/Spotify.kt`**: `spotifyPlayability` is `streamAllowed` and never `GONE`/`LOCAL`; `contextItem` (key `context`,
  no url, no duration); `playWindow` (empty outside the queue; the 50th item is the last of a window from 0; a queue of
  exactly 50 never adds), `nextQueueAdd` (one ahead, none after the last); §5.8's table row by row; `SpotifyOutcome`
  round trip, unknown words never stored, written only on change.
- **The fake**: stdlib only, runs under `-I`; serves fixtures by fixed name from `server/testdata/spotify/` and the music
  resources in place (no copies to drift) and audio only for `[A-Za-z0-9]{1,64}` ids - no path from a request reaches the
  file system; binds `127.0.0.1` by default; every value fake (`FAKE-ACCESS-<n>`, `FAKE-REFRESH-<n>`, `FAKE-CODE-<n>`);
  the selftest checks PKCE against RFC 7636's vector, rotation and refusals, every route against its fixture, the play
  body against `play_request.json`, the stub against every method and event `player.js` uses.
- **CI and docs**: `music.yml` runs the selftest after the build and watches `server/testdata/spotify/` and
  `scripts/fake-spotify.py`; `launcher.yml` needs nothing new (no file its tests read changed); root `CLAUDE.md`'s
  `scripts/` and CI lines are accurate.

## Owed by step 5a (not findings; from this review)

- The debug proxy logs the host only (the stub's query carries a fake token) and forwards `Range` and the five headers
  above; the bridge's `state(json)` cap counts what `SdkState.MAX_BYTES` counts.
- The token's deadline at a start leaves room for the play call (`get(startDeadline - SPOTIFY_CALL_MS, ..)` or similar):
  a first ask that times out at 10 s plus the 5 s retry and a second ask can otherwise spend the whole 25 s.
- `SourceScanTest`'s assignment check becomes an exact list once `DebugSpotify.kt` exists (deviation 9).

## Fix-round checklist

- [ ] H1: the skip reading needs `prev` evidence and never covers our own adds; leftover adds tracked across `played()`
      (pause + one new window); a start passes over the pre-play uri; the two counter-examples and two `sdk_states/`
      sequences; the stub keeps Spotify's queue across a new play; QA checklist item 20.
- [ ] M1: `shuffle`/`repeat` in `player.js`'s post and `SdkState`; `SpotifyApi.shuffle`/`repeat` before the first play
      of a device session and on a state that shows either on; the tracker reads nothing from a shuffled or
      repeat-track state; fake `--shuffle`/`--repeat`, `play_request.json`, tests; §5.4 and the QA checklist.
- [ ] L1: a stale same-uri state at a restart measures nothing (with H1's start rule); a test.
- [ ] L2: a track-less state is passed over in `STARTING`/`OURS`; a test.
- [ ] L3: "the same token" by the token, not the expiry; a test one second later.
- [ ] L4: §5.4/§5.8 say what a 401 can and can't recover; the contract note.
- [ ] L5: `PlayerJsTest` pins each event-to-kind pair.
- [ ] L6: a `securitypolicyviolation` report (`csp`, debug log only); §6j part B's line.
- [ ] L7: optionally one 403 retry after ~1 s; else §5.4 says why not.
- [ ] L8: §5.1, §5.2, §5.3, §5.4, §5.5, §9.2 brought in line with the deviations and with H1/M1.
- [ ] The music suite with `--rerun-tasks`, the three selftests.

No question for the user: shuffle off is already decided (§0.4); M1 only makes Spotify honour it.
