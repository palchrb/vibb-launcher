# 46 - A context playlist shows what is coming; the play arrow on every tile (proposal)

Asked 2026-10-20 (PLAN "Design 46", corrected the same day: development mode lists a playlist's items only to its
owner or a collaborator, so a context entry can show only what Spotify reveals without reading the playlist - the
queue). Music app only (plus one Web API read); no server or launcher change. Builds on design 26c §5.9 (context mode,
`contextItem`, `SpotifyTracker`'s "ours"), design 39 (the episode list), design 34 (the carousel's badges).

## 0. Today

A context entry (`spotify: "context"`: an artist, or a playlist no logged-in account may list) has one synthetic item
(`contextItem`, key `context`, titled by the live track once one played); its list screen shows that one row with
the hint "rekkefølgen velges for deg" (`ListScreens.kt:124`, `Hint.Picks`); the carousel's play mark and progress
line show only for a started entry that resumes and never for a context (`UiModel.kt:60`). The user: "rekkefølgen
følger min playlist helt perfekt" (shuffle is off, so a playlist context plays in the list's order); "i mitt hode
betyr [pilen] trykk på tile for å starte avspilling".

## 1. Decisions

1. **The queue read**: `SpotifyApi.queue()` (new: `GET /v1/me/player/queue`, the scope `user-read-playback-state`
   the accounts already hold) returns `currently_playing` and up to 20 `queue` tracks. The renderer asks it once
   after each start and each track change of a context (the SDK's `player_state_changed` with a new uri), never on a
   timer; a 429/5xx/network failure leaves the last answer in place (the list says what it knew); the answer is
   held in memory only (`SpotifyRenderer`, dropped at `detached()`/release), capped at 20 tracks, each as
   `{uri, name, artists, duration_ms, album image url}` - the same shape the server's listing gives a track, so the
   row draws like a list-mode row (title "name — artists", the picture through the existing cover slot by url as
   `art_url` the art pass never sees: the row loads it through the public-only client, cached in the small-covers
   LRU, never kept - a context keeps nothing, 26c §5.10).
2. **The list screen of a context entry**: the playing track first (marked as the playing row), then the queue's
   tracks in order, numbered from the playing one; the hint becomes "spillelistens rekkefølge · de neste N" for a
   playlist and stays "rekkefølgen velges for deg" for an artist (the queue still shows what Spotify picked). No
   "N av M", no flip button (unchanged), no download circles (nothing is kept).
3. **A tap on a queue row** plays the context from that track: the same play call with `offset.uri` = the row's uri
   (what a resume does today, `PlayBody.Context(ctx, offsetUri, 0)`), coalesced like a step; the tracker's "ours"
   already takes every track of the context. A tap on the playing row does nothing. Without network or on a
   refused gate the tap answers with the quiet line, as today.
4. **The play arrow means "tap to play", on every tile** (a deviation from vibb, the user's call): `CoverBadges` draws
   the peach play disc on every cover in the main carousel; the progress line and the hint alone say "started". The
   context entry's `showProgress` rule is therefore moot for the mark; the progress line stays off for a context (no
   list to measure).
5. **Scope**: list-mode entries are untouched; the player screen is untouched; the stored queue (`NowPlayingRow`) for a
   context stays the one synthetic item - the queue view is display + a start offset, never a local queue.

## 2. Tests (required)

`SpotifyApiTest`: the queue call's request and the parse of `play_answers.json`'s new `queue` fixture (20 tracks,
`currently_playing` null, a 429); `fake-spotify.py` answers `GET /v1/me/player/queue` from the fixture (`--selftest`
extended). `SpotifyRendererTest`: the read after a start and after a track change, not on a timer, the failure keeps the
last answer, dropped at detach. `UiModelTest`/`LibraryViewTest`: the context list's rows and numbering, the hint words
(playlist vs artist), the play mark on every tile, no progress for a context. `SpotifyTrackerTest` unchanged (a tapped
track is a context start). `SourceScanTest`: no new kid-screen string says "Spotify".

## 3. Device checks

Emulator with the fake (§6l): a context's list shows the playing track and the next 20, a tap starts there, the hint
words; the real SDK on the emulator with the user's account: the same on a parent-owned playlist, and an artist.
