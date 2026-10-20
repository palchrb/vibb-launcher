# 46 - A context playlist shows what is coming; the play arrow on every tile (proposal)

Asked 2026-10-20 (PLAN "Design 46", corrected the same day: development mode lists a playlist's items only to its
owner or a collaborator, so a context entry can show only what Spotify reveals without reading the playlist - the
queue). Music app (plus one Web API read, and a server scope change if §0b is answered yes). Builds on design 26c §5.9
(context mode, `contextItem`, `SpotifyTracker`'s "ours"), design 39 (the episode list), design 34 (the carousel's
badges), design 49 (Spotify-side shuffle/repeat), design 50 (dimmed tiles).

## 0. Today

A context entry (`spotify: "context"`: an artist, or a playlist no logged-in account may list) has one synthetic item
(`contextItem`, key `context`, titled by the live track once one played); its list screen shows that one row with
the hint "rekkefølgen velges for deg" (`ListScreens.kt:124`, `Hint.Picks`); the carousel's play mark and progress
line show only for a started entry that resumes and never for a context (`UiModel.kt:60`). The user: "rekkefølgen
følger min playlist helt perfekt" (shuffle is off, so a playlist context plays in the list's order); "i mitt hode
betyr [pilen] trykk på tile for å starte avspilling".

## 0b. Open: the re-login

The queue read (`GET /v1/me/player/queue`) needs the scope `user-read-playback-state`. The server leaves it out on
purpose (`server/src/spotify.rs:59-63`, 26c QA L2; `the_authorize_url_is_exact` asserts it absent), so reading the
queue is a server change and every Spotify account must log in again (a refresh keeps the scopes it was granted).
**The user decided (2026-10-20): the queue view goes on PLAN's long list ("Legg køvisning for neste 20 på
longlisten vår, så husker vi på den til senere"); the music round builds §1.4 only, and 46b (a reader account, no
code) is the road to the whole list. §1.1-§1.3 and the queue parts of §2/§3 stay here for that later round.**

- **Yes**: §1.1-§1.3 and §2's queue parts are built. The server adds the scope to its one scope set (`SCOPES`, `spotify.rs:62-63`;
  the server's `the_authorize_url_is_exact` changes in three places - the exact URL string, the absence assert
  `!SCOPES.contains("user-read-playback-state")` and the count 6 - and the PWA's login text; only `server/` pins the
  set).
  The accounts' `scopes` column (`music_spotify_accounts`) tells which accounts lack it; the Spotify group in
  Music › Settings shows "Log in again for the track list" on those, and the queue read is not tried for them.
- **No**: §1.1-§1.3 and the queue parts of §2/§3 are dropped; 46 is the play arrow (§1.4) alone, and the context list
  stays as today.

The play arrow (§1.4) does not depend on the answer and can go ahead on its own.

## 1. Decisions (if §0b is yes, except §1.4 and §1.5)

1. **The queue read**: `SpotifyApi.upcoming()` (new; `queue()` is the existing POST add) is `GET /v1/me/player/queue`.
   It returns `currently_playing` and up to 20 `queue` tracks. The renderer asks it once after each start, each track
   change of a context (the SDK's `player_state_changed` with a new uri) and each shuffle/repeat change (design 49),
   never on a timer. The answer is held in memory only (`SpotifyRenderer`, dropped at `detached()`/release and
   after a restart or the 5-minute release: then there is no answer and the list falls back to today's one row).
   Kept per track: `{uri, name, artists, duration_ms}` only.
   - **Its own outcome.** A failed queue read (429/5xx/network/403 missing scope) leaves the last answer in place and
     NEVER sets the account word: `SpotifyApi.classify`'s 403 -> `Refused` does not apply to this call, which returns
     a separate outcome type that never feeds `spotify_state.json` or the report.
   - **Size.** The endpoint has no `fields` parameter and returns full track objects (markets included), more than
     `SpotifyResponse.BODY_MAX` (64 KiB, a longer body is silently cut). This one call reads up to `BODY_MAX_QUEUE`
     (256 KiB) and parses only the four fields; a cut body is no update (the last answer stays). The `queue` fixture is
     realistic in size, markets included.
2. **The list screen of a context entry**: the playing track first (marked as the playing row), then the queue's
   rows in order, numbered from the playing one. A row is a `QueueRow` (not a `PlayItem`: it is no item of the
   snapshot), keyed by position + uri in the `LazyColumn` (the queue may name a uri twice). A row shows number,
   "name — artists" and duration; no picture of its own (the app loads covers only by hash from the launcher), the
   entry's cover as the fallback picture if the list already draws one. No "N av M", no flip button (unchanged), no
   download circles (nothing is kept).
   - **Hint**: "spillelistens rekkefølge · de neste N" for a playlist, N counting only the context's tracks; with
     Spotify-side shuffle on (design 49) "tilfeldig rekkefølge · de neste N"; "rekkefølgen velges for deg" stays for
     an artist.
   - **Not of the context**: rows the tracker knows as our own leftover list-mode add (`LeftoverAdd`) or as Autoplay
     are shown dimmed, without a tap. Rows past the context's end that the tracker can't tell apart are the only
     case where a tap plays the playlist from the top; say so in the code.
3. **A tap on a queue row** (a playlist or album context only): the new gated command `Commands.PLAY_CONTEXT_AT`
   (argument: the row's uri), added to `PLAYS`, `MOVES` and `Commands.ALL`, through `refuseSpotify`/`playAllowed` and
   the renderer's start, coalesced like a step. It sends the play
   call with `offset.uri` (what a resume does today, `PlayBody.Context(ctx, offsetUri, 0)`) and records the tapped
   track as the live track; the tracker already takes every track of the context as ours. With shuffle on, Spotify
   re-shuffles the rest, as in its own app. **An artist context's rows are display-only** (no tap, no button
   semantics): `offset.uri` works for album and playlist only (`contextResumes`, `logic/Spotify.kt:85-90`). A tap on
   the playing row does nothing. Without network or on a refused gate the tap answers with the quiet line, as today.
4. **The play arrow means "tap to play", on every tile** (a deviation from vibb, the user's call): the main carousel's
   cover draws the peach play disc on every tile; `CoverBadges` draws its disc only when `progress != null`
   (`LibraryScreen.kt:354`), so the badge gets its own condition. It is hidden on a dimmed tile (design 50 §1.4):
   "tap to play" would mislead. The progress line and the hint alone say "started"; the progress line stays off for a
   context (no list to measure).
5. **Scope**: list-mode entries are untouched; the player screen is untouched; the stored queue (`NowPlayingRow`) for a
   context stays the one synthetic item - the queue view is display + a start offset, never a local queue.

## 2. Tests (required)

`SpotifyApiTest`: the queue call's request and the parse of `play_answers.json`'s new `queue` fixture (20 tracks with
markets, `currently_playing` null, a 429, a cut body, a 403 that leaves the account word alone).
`fake-spotify.py` answers `GET /v1/me/player/queue` from what it handed the stub device (`context_tracks` plus
`index`, rotated to the stub's current track, honouring `--shuffle`/`--repeat`), plus the 429 fixture; the route goes
into the header's route list and `--selftest`. `SpotifyRendererTest`: the read after a start, a track change and a
shuffle/repeat change, not on a timer, the failure keeps the last answer, dropped at detach. `UiModelTest`/
`LibraryViewTest`: the context list's rows, numbering and duplicate uris, the hint words (playlist, shuffled,
artist), dimmed leftover rows, an artist's rows without a tap, the play mark on every tile and not on a dimmed one,
no progress for a context. `CommandsTest`: `PLAY_CONTEXT_AT` gated and coalesced. `SpotifyTrackerTest` unchanged. If
§0b is yes, the server's scope test and the Settings re-login line. `SourceScanTest`: no new kid-screen string says
"Spotify".

## 3. Device checks

Emulator with the fake (§6l): a context's list shows the playing track and the next 20, a tap on a playlist row
starts there and the list refreshes, an artist's rows don't react, the hint words; the real SDK on the emulator with
the user's account (after the re-login): the same on a parent-owned playlist, and an artist.

## Revision (2026-10-20, after qa-music-round-design.md)

- 46-1: new §0b (the user decides the re-login); §1.1 conditional, the read has its own outcome and never sets the account word.
- 46-2: §1.3 an artist context's rows are display-only; only playlist/album rows start from the track.
- 46-3: §1.2 no pictures on queue rows; the entry's cover as fallback.
- 46-4: §1.1 `BODY_MAX_QUEUE` 256 KiB, four fields kept, a cut body is no update; realistic fixture.
- 46-5: §1.2-§1.3 `QueueRow` keyed by position + uri; `Commands.PLAY_CONTEXT_AT`, gated and coalesced.
- 46-6: §1.2 leftover add and Autoplay rows dimmed without a tap; the hint counts only the context's tracks.
- 46-7: §1.1 the read is `upcoming()`.
- 46-8: §2 the fake answers from the stub's context, rotated, with the route in the header and `--selftest`.
- 46-9: §1.1 the shape no longer claims to be the server's; "de neste N" chosen; the restart fallback stated.
- 46-10: §1.4 the badge has its own condition and is hidden on a dimmed tile.
- Interactions: §1.1/§1.2 re-read after a shuffle/repeat change; the hint follows Spotify-side shuffle.

## Implementation status (2026-10-20, branch `music-r-b`)

- **§1.4 built**: the peach play disc on every carousel tile (`showsPlayMark(e) = !e.unplayableNow`,
  `LibraryScreen.CoverBadges`), its own condition, no longer `progress != null`; hidden on a tile design 50 §1.4 dims.
  The progress line still shows only for a started resume entry and never for a context. Tests: `UiModelTest`'s
  `d46 - the play arrow on every tile, never on a dimmed one`.
- **§1.1-§1.3 and the queue parts of §2/§3 are parked** (§0b, the user's call: the queue view is on PLAN's long
  list). No server, scope, `SpotifyApi` or fake change was made. §1.5's scope note holds.
