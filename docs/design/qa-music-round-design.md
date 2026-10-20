# QA - the music round's design notes 46, 49, 50

Reviewed on `main` at `7c4f2014` (the notes' own commit). Read against music/CLAUDE.md's "Rules that are easy to
break", PLAN "Design 46/49/50" and the code. Paths below are under `music/app/src/main/java/me/vibb/music/` unless
they say otherwise.

## Design 46 - the context queue view, the play arrow on every tile

**46-1 High - the queue read needs a scope the accounts deliberately don't hold.** The note says "the scope
`user-read-playback-state` the accounts already hold" and "no server or launcher change". The server's one scope set
leaves it out on purpose ("No `user-read-playback-state`: the phone reads its state from the SDK",
`server/src/spotify.rs:59-63`; `the_authorize_url_is_exact` asserts it is absent, `:2873-2874`, 26c QA L2).
`GET /v1/me/player/queue` needs it. So it is a server change (SCOPES, the test, the PWA's login text), and every
logged-in account must log in again: a refresh keeps the scopes it was granted (the server stores them,
`music_spotify_accounts.scopes`, `spotify.rs:1483-1498`, so it can tell which accounts lack it). Also, a 403 from the
queue call must never touch the account word: `SpotifyApi.classify` makes every 403 `Refused`
(`spotify/SpotifyApi.kt:303-306`), and through the play path that becomes "Spør en voksen" on every Spotify tile.
Fix: the user decides whether to add the scope, given the re-login cost. If yes: add a server step (scope, test,
re-login hint for accounts whose stored `scopes` lack it), and give the phone's read its own outcome type that never
feeds `spotify_state.json` or the report.

**46-2 High - a tap on an artist's row can't start "from that track".** §1.3 sends `offset.uri` for every context.
Only album and playlist contexts take an offset. The code already says so: "no offset is documented for an artist: it
starts where Spotify starts it" (`logic/Spotify.kt:85-90`, `contextResumes`). For an artist the tap would get a 4xx
or play from Spotify's start, not the row. Fix: an artist's rows are display only (no tap, no button semantics),
and only a playlist's rows play. Or the user decides on another behaviour.

**46-3 Medium - there is no picture-by-URL path.** Every picture the app shows is a file by hash, delivered by the
launcher (`ui/Widgets.kt:131-160` `coverBitmap`/`loadThumb` read `Store.coverFile(hash)`; `sync/ArtPlan.kt:157-166`
asks the launcher via `LiveBridge.wantArt`). "The existing cover slot by url" and "the small-covers LRU" (`thumbs`,
keyed by hash) don't take a URL. On the host question: `i.scdn.co` is on the list (suffix `scdn.co`,
`spotify/SpotifyHosts.kt:15`, `server/testdata/spotify_hosts.json`), so the launcher's DNS filter lets it through on
a phone with Spotify on. `WebPolicy` is the WebView's alone and does not apply. The guarded client
(`media/MediaCache.kt:89-97`) takes any public host. Fix: specify the new loader. It uses the guarded client, only
under `streamAllowed`, has a byte cap and bounded decode (`thumbSampleSize`), and keeps nothing but memory (its own
LRU keyed by URL). With the debug switch the fixtures' image URLs are null, so no seventh `debugBase` reader comes
in. Or leave the pictures out of the first step: PLAN didn't ask for them.

**46-4 Medium - the answer may not fit the HTTP seam.** `SpotifyResponse.BODY_MAX` is 64 KiB, and anything longer
is silently cut (`spotify/SpotifyHttp.kt:47, 76-78`). The queue returns 21 full track objects. With
`available_markets` on each track and album that can pass 64 KiB, and a truncated body fails the parse every time.
Fix: a larger cap for this call only, or a streaming parse of the fields used. Make the `queue` fixture realistic
in size (markets included).

**46-5 Medium - the tap and the rows need their own command and keys.** A list row calls `onPick(PlayItem)` →
`PLAY_ITEM` by item key (`ui/ListScreens.kt:155`, `playback/PlayerHub.kt:14-60`). A queue row is no `PlayItem` of the
snapshot (the context has one item, `context`). The rows are keyed `it.item.key`, but the queue can name the same
uri twice (repeat context, a track twice in a playlist), and a `LazyColumn` with duplicate keys throws. Fix: a play
command (or a `PLAY_ITEM` argument) carrying the offset uri. It goes in `PLAYS` and `MOVES`, passes `playAllowed`,
`refuseSpotify` and the step coalescing, and records the tapped track as the live track. Rows are keyed by position.

**46-6 Medium - not every queue row is the context.** Spotify plays its queue before the context, so our list
mode's leftover add (`LeftoverAdd`, `spotify/SpotifyTracker.kt:419-435`) shows first. With repeat off, the tail can
be Autoplay. A tap on either sends an offset that isn't in the playlist, which gives "one more play from the top"
(music/CLAUDE.md, context mode). Fix: drop the uris the tracker knows as leftover adds. State that rows past the
context's end can't be told apart, and that a tap there plays the playlist from the top.

**46-7 Low - name clash.** `SpotifyApi.queue()` already exists: the POST add, `spotify/SpotifyApi.kt:144-146`. Fix:
call the read something like `upcoming()`.

**46-8 Low - the fake serves a static queue.** §2 has `fake-spotify.py` answer from a fixture. The emulator check in
§3 (the tap, the refresh after a track change) then proves nothing. The fake knows what it handed the stub device
(`context_tracks` plus `index`, `scripts/fake-spotify.py:589-611`). Fix: answer from that, rotated to the stub's
current track, honouring `--shuffle`/`--repeat`, plus the 429 fixture, and add the route to the header's route list
and `--selftest`.

**46-9 Low - loose ends.** "The same shape the server's listing gives a track" is wrong: the server gives art hashes,
not URLs. The hint says "de neste N" where PLAN says "de neste 20" (pick one). After a restart or the 5-minute release
there is no page and no answer, so the list falls back to today's one row: say so.

**46-10 Low - the play arrow on every tile** (the user's call, PLAN) is fine. `CoverBadges` draws the disc only when
`progress != null` (`ui/LibraryScreen.kt:354`), so the badge needs its own condition. For its interaction with 50's
dimmed tiles, see 50-3.

**Verdict 46: revise.** 46-1 needs the user's decision and a server step before anything else. 46-2 changes §1.3.
The play-arrow part (§1.4) can go ahead on its own.

## Design 49 - shuffle and repeat

**49-1 High - the kind split is wrong, and drops today's shuffle.** `kindOf` maps own files to `Kind.OWN`, never
`MUSIC` (`logic/LibraryView.kt:23-30`). So "every `Kind.MUSIC` entry (own music, …)" and
`canShuffle = kindOf(entry) == MUSIC` take shuffle away from own music, the one place it exists today
(`logic/Queue.kt:18`). 50 §1.1 splits `Kind.OWN` by resume; 49 says "a resuming own-music entry included". PLAN says
"Resume entries keep shuffle hidden as today", yet every Spotify entry is resume on and 26c decided "No shuffle" for
it (`docs/design/26c-spotify.md:147, 714`; music/CLAUDE.md "No shuffle (every Spotify entry resumes)"). Fix: one pure
predicate in `Queue.kt`, used by both notes. Shuffle/repeat go to `Kind.MUSIC` and `Kind.OWN && !resume`; the skip
pair goes to everything else. Ask the user two things: a resuming own entry (shuffle or skip?), and whether Spotify
shuffle overturns 26c's decision. Change music/CLAUDE.md and 26c §4.2 in the same commit.

**49-2 High - the tracker undoes Spotify-side shuffle/repeat at the first state.** `SdkState.reordered` is
`shuffle || repeat != 0` (`spotify/SdkState.kt:50-51`). In both modes such a state is read for nothing and becomes
`ShuffleOrRepeat` (`spotify/SpotifyTracker.kt:385-392`, before the context branch). The renderer then turns both off
and plays again (`spotify/SpotifyRenderer.kt:333, 1386-1388`). So 49 §1.4's context mode does not work as written,
and "the tracker already takes every track of the context as ours" isn't so while the flag is set. Fix: each start
carries the wanted `(shuffle, repeat)`. `reordered` becomes "differs from wanted", and `needSettings` sends the wanted
values whenever the last values set differ. That also covers the next list-mode play after a context with them on
(the account setting persists): without it, that play starts shuffled and is then corrected audibly.

**49-3 Medium - list mode: don't widen "ours".** The shuffled window is sent in our order, and `reorder` already
re-feeds the tracker (`tracker.reorder`, `spotify/SpotifyRenderer.kt:479-494`), so current/expected-next stays
exact. "Any uri of our window" would map a stale or leftover state of another window track to an index. That breaks
"Only our tracks are ours". Fix: keep the rule. `reorder` must stop being a no-op for Spotify (26c's "no shuffle for
Spotify" comment at `:471`).

**49-4 Medium - shuffle with resume is undefined.** `coverTapPlan` ignores `shuffle` when `resume`
(`logic/Queue.kt:37-42`). Every Spotify entry resumes, so the toggle would change nothing on a cover tap. Fix: define
it in `Queue.kt`. For example, the bookmarked item first at its place, then a permutation of the rest (or the stored
shuffled queue), with a `QueueTest`.

**49-5 Medium - Spotify list-mode repeat by re-send.** Repeat-one: at a track's end Spotify's own advance already
plays the next window track (the window holds up to 50). The kid hears a jump, and the service gets
`ItemChanged(auto)` with its done mark before the re-send. Repeat-all: the re-send comes after `Ended`, after which
every playing state is paused, and the service runs `queueEnded` (`clear_state`, `playback/PlaybackService.kt:1521-1540`).
Fix: repeat-one = `repeat=track` on a list play (taken as wanted per 49-2), or a one-track window. Repeat-all = the
renderer's `NewWindow` from index 0 at the window's end, never emitted as `Ended`, and only with a play the kid wants
now (`playWhenReady && playAllowed`, the network gate). Never after a pause from the kid, the sleep timer or a call.

**49-6 Medium - context mode with repeat=track and the end-on-evidence rules.** The paused state at the track's
duration (N1, `SpotifyTracker.kt:39-44`) and `contextEnd` ("our live track paused at 0 after it ran out", `:463-472`)
can read a repeat-track loop as the context's end. That drops the live track (`clear_state`) and pauses every later
state. Fix: under `repeat != off` the end rules need Autoplay evidence (another context) only. Test it with the
fake's `--repeat track|context`.

**49-7 Medium - the phone's move-on doesn't wrap.** `onItemChanged` moves past an unplayable item with
`wrap = false` (`playback/PlaybackService.kt:1320`), and `onError` only looks forward (`:1459`). With repeat-all an
unplayable last item pauses instead of wrapping. A plain wrap with every item unplayable (streams, offline) loops
forever. Also, `REPEAT_MODE_ONE`'s transition is `REASON_REPEAT`, which `LocalRenderer` emits as `auto = false`
(`playback/LocalRenderer.kt:78-79`): no done mark and no overlap seek. That is fine, but say it. Fix: the move-on
follows the repeat mode with a one-lap guard (a full lap with nothing playable pauses, "Ikke lastet ned"). Make it
pure in `Queue.kt` and test it. Under repeat-all/one `queueEnded` never runs (positions kept), and turning repeat off
lets the next natural end `clear_state` as today.

**49-8 Medium - commands and the session.** There is no `REPEAT` command and no `THEN_REPEAT` for a restored queue
(`playback/PlayerHub.kt:14-60`; shuffle has `THEN_SHUFFLE`). `KidPlayer` forwards ExoPlayer's
`COMMAND_SET_REPEAT_MODE`/`COMMAND_SET_SHUFFLE_MODE` to every controller (`playback/PlaybackService.kt:444-466`). The
launcher, AVRCP or the notification could set ExoPlayer's mode past Room and past the Sonos/Spotify renderers. Fix:
add a `REPEAT` custom command (+ `THEN_REPEAT`), and have `KidPlayer` remove both Player commands (or route them to
the service's toggles).

**49-9 Low - Sonos.** After STOPPED at the end, the controller goes through `advance()` → `playIndex(next, 0.0, …,
ADVANCE)` behind `mayPlay()` (`sonos/SonosController.kt:1099-1125`). Repeat-all is `next = 0` there instead of
`endSession`/stop. Repeat-one is the same index through the same path (a new URI set + Play, not "a Seek/Play"), and
the bookmark's regression guard must accept that 0 as a deliberate start.

**49-10 Low - vibb and the contract files.** vibb has no repeat, and vibb's Spotify shuffle is Spotify's own
(`shuffle_context`, 26c:170). The note's on-phone list shuffle and the repeat are deviations: mark them as the
user's. `play_request.json` pins the shuffle/repeat requests as off only (the fake checks shapes,
`fake-spotify.py:530, 644`), so it needs the on/context/track shapes.

**49-11 Low - details.** "a playlist/album context": an album is always list mode, and a context is an artist or a
playlist. Room 6 is claimed by both 49 and 50 (see 50-6).

**Verdict 49: revise.** 49-1 needs the user's answer on the kind/resume split, and 49-2 is a precondition for every
Spotify-side setting. With those and the Mediums fixed the design is sound.

## Design 50 - skip buttons, "Til slutten", the carousel's start, dimmed tiles, favourites

**50-1 Medium - "Til slutten" is specified in the wrong place and doesn't fit the timer's shape.** The timer lives in
the service (`setSleep`/`sleepTick`/`sleepEnded`, `playback/PlaybackService.kt:1166-1199`), not `NowPlaying.kt`. The
`SLEEP` command takes `ARG_MINUTES` with `<= 0` = off, and `PlayerHub.State` holds `sleepMinutes: Int?`
(`PlayerHub.kt:111-113`), so a sentinel needs its own argument and state field. The fade is the renderer's
`fadeMs`: 10 s on the phone and on Spotify, 3 s on a room (`sonos/SonosRenderer.kt:218`), not always `SLEEP_FADE_MS`.
A fade timed from the position races the natural advance (a stall, a seek, a speaker's clock), so the next item can
start. Fix: the service pauses at the item's end through the renderer. On the phone that is ExoPlayer's
`setPauseAtEndOfMediaItems`. On a room, `advance()` holds at the next item at 0, as its `!mayPlay` branch does. On
Spotify, the tracker's end with nothing re-sent. The fade starts at `duration − fadeMs` from the live position,
recomputed after a seek. It beats repeat-one (49). For an item with no duration (live, or a context before its first
state) the choice is hidden. The minute timer survives a manual step. Clearing this one on a step is a new rule;
moving it to the new item is closer to "finish what plays". Ask the user, or mark it as their call.

**50-2 Medium - dimming: no network flow, and the line isn't there.** The screens never read the network: no
`NetCaps` in `ui/`, and the snapshot is `store.snapshots()` + the output (`ui/MusicViewModel.kt:50-52`), so
"recomputed on the network callback the snapshot already observes" has nothing to hook onto. The "quiet line already
in place" exists only for Spotify (`spotifyTileState`, `ui/UiModel.kt:80-84`). `CarouselTitle` shows state *or* hint
(`ui/LibraryScreen.kt:318`), and "Ikke lastet ned" is a toast (`PlayerHub.Message.NOT_DOWNLOADED`), not a tile line.
A second line would change `libraryFit`'s two measured lines (`:203`) and make the cover jump. On the phone a
Storytel book or an own file without a copy is already hidden (`data/Snapshot.kt:117-125`), so the real cases are
NRK/RSS without copies and Spotify. On a room the phone's gate isn't the rule (`playable()` →
`itemPlayable(renderer.kind, …)`, `PlaybackService.kt:893-898`), but Spotify can't play there at all. Fix: a
connectivity flow in the ViewModel. `unplayableNow` comes from `itemPlayable` in the shown output's view. The line
replaces the hint, with the account line first (`ASK_ADULT` beats "Trenger Wi-Fi"). The 60 % multiplies the pager's
neighbour alpha (`:282`). 46's arrow is hidden on a dimmed tile, since "tap to play" would mislead. Consider dimming
an `ASK_ADULT` tile too: that is also a tile "man vet man ikke får spilt av".

**50-3 Medium - favourites keyed by entry id.** Every per-entry kid state is keyed by the entry key (positions,
bookmarks, shuffle, flips: `data/MusicDb.kt:70-118`; "Positions are keyed by (entry key, item key)"). An id changes
on a re-import and the key doesn't. Fix: `favourites(entryKey, itemKey)`. Prune only on a parsed listing and only
what the library no longer names: a hidden or withdrawn item kept with its copy keeps its heart. No heart for a
context's synthetic item, whose key is `context`, not a track. "Favoritter" is a pseudo-category that
`tapCategory`/`effectiveCategory`/`backToLibrary` must understand: a sentinel that no server id can be.

**50-4 Low - scope.** The "Bare favoritter" filter row and "Favoritter with one entry opens it" are not in PLAN.
Keep them only if the user agrees. Say what a cover tap under Favoritter plays: the entry as usual, not a
favourites queue.

**50-5 Low - the carousel's start.** `MusicViewModel.kt:78` is `pickCategory`'s reset, not a load hook. The pager
reads `initialPage` once (`ui/LibraryScreen.kt:195`), and `nowRow` starts null (`MusicViewModel.kt:53`). Fix: set the
index once, when the first snapshot and the row's first emission are both in. Bump `carouselJumps`, and never set it
after the kid turned the carousel or came back from the player (`backToLibrary` wins).

**50-6 Low - Room and the bridge.** Room 6 is "49's and 50's, one migration" only if both ship in one step. Otherwise
the second is 7. Name the order. For the lock tile, `now_playing` carries no kind (`bridge_contract.json`:
`entry, item, position_ms, duration_ms, playing, art`). Send the decision (`controls: "skip"|"steps"`), not the kind,
so the launcher doesn't copy the resume rule. The field is appended in both apps in one commit, as the note says.

**50-7 Low - layout.** The sleep sheet's options are a 2x2 grid (15/30/45/Av, `ui/PlayerScreen.kt:233-234`): "Til
slutten" goes in a full-width row. "Sov · til slutten" plus the time must fit at 320 dp, since `FlexBar` never cuts
Sov (FitTest). The 15/30 inside a 44 dp side button with a 20 dp glyph needs a fixed size, not scaled sp. A context
item has no listing duration (`contextItem`, `logic/Spotify.kt:102-103`): the clamp uses the live state's duration.

**Verdict 50: approve with fixes.** Nothing in 50 alone is High. Its control slots depend on 49-1's predicate. Fix
50-1 to 50-3 in the note before implementation.

## Interactions (cross-note)

- **Slots by kind:** 49-1. One predicate. Today `Kind.OWN && !resume` would get neither pair under 49's wording.
- **46's queue vs 49's Spotify-side shuffle:** with shuffle on, the queue is Spotify's shuffled next. The hint
  "spillelistens rekkefølge" is then false: it should follow the shuffle state. Read the queue again after a
  shuffle/repeat toggle, not only after a track change. A tap re-shuffles the rest, as Spotify does.
- **Autoplay after a context:** with repeat off, as today (another context → not ours, the end). With repeat context
  or track there is no Autoplay, and the end rules must not fire (49-6).
- **50's favourites vs prune/Nytt:** no conflict with Nytt (`news` is per item, separate). Prune as in 50-3.
- **50's dimming vs `spotifyTileState`:** one line, with the account line first (50-2).
