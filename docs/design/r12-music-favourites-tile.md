# Music round 12 - no number on a plain row, skip 15 s both ways, one "Favoritter" tile (proposal)

Asked 2026-10-24 by the user; all three are the user's decisions, not vibb's. Music app only: no server, launcher, PWA
or shared-contract change (§3.8). Three items, each with its own tests: §1 a row without its own picture in a pictured
list shows the series cover plain; §2 the skip pair is 15 s both ways; §3 under "Favoritter" one tile plays every
hearted item of every entry in one queue (option B of PLAN's "Music: what a heart means", replacing r8's option C).
Paths are under `music/app/src/main/java/me/vibb/music/` (`M/` below), tests under `music/app/src/test/java/me/vibb/music/`.
Line numbers are main at `ed588e91`.

The user's words:

1. A screenshot of a podcast's list: six rows, five with their own picture, one ("Fiskoteket", playing) with the series
   cover and "17" over it - "tallet gir mer usikkerhet enn det tilfører verdi".
2. Skip 15 s back and 15 s forward (today 15 back, 30 forward).
3. "vi bruker jo ikke sonos sin kø? vi holder køen selv, så ser ikke problemet med å spille miks av rss/podcast +
   spotify sanger så lenge vi har hver spotify playlink til hver sang ... på enhets høyttaler/bluetooth, det går fort i
   appen når jeg bytter mellom spotify og nrk podcasts (bortsett fra første gangen på en stund når spotify bruker noen
   sek på å logge på)".

## 0. What the code says against the asks (read 2026-10-24)

- **§1**: the number is not decided in `listRows` (`M/logic/LibraryView.kt:216`, it only carries `i + 1`) but in the
  composable: `ListRowView` (`M/ui/ListScreens.kt:191-216`) draws `Palette.scrim` + the number (or ✓) over a row's
  series cover when `pictures` and `artHash == null`. No test pins the overlay (only design 39's text, music/CLAUDE.md
  and emulator.md §6q 1-2 say "numbered series cover").
- **§2**: the skip pair is design 50 §1.1's, **ours, not vibb's** (`M/logic/Transport.kt:53`, "the user's pick of
  2026-10-20"; vibb's player has no skip pair). The glyphs are Material Symbols `replay` and `forward_media`
  (`scripts/material-symbols.sh:65-66`, `PLAYER_GLYPHS` at 227) - **numberless** rings; the "15"/"30" is our own
  `BasicText` at a fixed 7 dp over them (`SkipButton`, `M/ui/PlayerScreen.kt:245-251`). So no 15 s glyph is needed
  (Material Symbols' numbered `forward_5/10/30`, `replay_5/10/30` have no 15 - irrelevant here): only the text changes.
  The media session has **no** seek back/forward: `KidPlayer` (`M/playback/PlaybackService.kt:489-530`) adds only
  next/previous, nothing sets `seekBackIncrementMs`/`seekForwardIncrementMs` (grep: none in `music/` or `launcher/`),
  so the notification, Bluetooth and Android Auto show previous/play/next and nothing changes there. The launcher's
  lock tile has previous/play/next only (`launcher/.../music/MusicLockPanel.kt:55-58`); the `controls` key it receives
  ("skip"/"music", `MusicBridgeService.kt:315`) is not read yet. Confirmed: no launcher change.
- **§3**:
  - **The service is one entry per queue.** `Active.entry` (`M/playback/PlaybackService.kt:157-194`) drives the
    renderer choice (`phoneRendererFor`: a Spotify entry the web player, every other the ExoPlayer), positions, copies,
    claims, art, modes and every re-plan. A queue mixing sources can't be one `Active`, and no renderer can hold a
    mixed queue (the web player plays only track uris, a room drops Spotify). §3.3's design layers the mix **above**
    `Active`: a run of one entry's hearted items at a time, exactly what today's renderers play.
  - **The switch between Spotify and a URL item exists today only between queues**: a tap on another entry ->
    `playEntry` (`:781`) -> `start` (`:921`) -> `usePhoneRenderer` (`:965`) -> `attach` (`:453`). That is the path the
    user finds fast; §3.4 reuses it per run.
  - **Hearts have no time**: `FavouriteRow(entryKey, itemKey)` (`M/data/MusicDb.kt:131-132`). "Last hearted first"
    needs Room 8 (§3.6).
  - **A Storytel book on the phone is never minted**: it plays from its copy only (`ItemTag.copyOnly`, design 26b
    §3-§4); the mint is a room's (per play, in the actor job) and the downloader's. The ask's "a Storytel book through
    its mint" is the room's path; on the phone a hearted book is an ExoPlayer item like an episode's copy.
  - **Spotify on a room is not built**: `PlayBodies.speakerDropReason` returns `"spotify"` (`M/sonos/PlayBody.kt:160`);
    design 54's share link is round 54-a2, not merged (54-a1, dormant, is). And design 54's path **does** use the
    speaker's queue (the share link fills it; 54 §0.1, §2.2) - the user's "we hold the queue ourselves" is true for
    URL and Storytel items (26a: one `SetAVTransportURI` per item, the app's own advance), not for Spotify on Sonos.
    So in this round a room's mix leaves Spotify out, as it leaves out own files (§3.5); §3.5 hands 54-a2 the rule.
  - **`playEntry`'s same-tile rule** compares the entry id only (`:784`): with a mix playing an item of entry X, a tap
    on X's own tile under "Alle" would unpause the mix's run instead of starting X. The mix needs `mix == null` there.
  - **`persistPlace` writes the entry's bookmark at every save** (`M/playback/LocalRenderer.kt:279-302`, all three
    renderers' `saveNow`); §3.2 decides the mix doesn't move bookmarks, so the place gets a no-bookmark form.
  - **A room's controller ignores "Til slutten" at the queue's last item** (`SonosController.advance`, `:1673-1710`:
    `holdAtEnd` is read only when there is a next item); at a run's end the service must hold the next run (§3.4).
  - **The list's lazy key is the item key** (`ListScreens.kt:156`): two entries' items can share a key in a mix.

## 1. No number on a row without its own picture

### 1.1 Behaviour

- In a pictured list (design 39 rule 1: `rowPictures` = `showsCarousel` over the rows shown) every row's picture is
  plain: its own picture, or - `artHash` null - **the series cover with nothing over it** (no scrim, no number, no ✓).
  Heard stays in the sub-line ("Hørt · 12:34", `row_heard`), playing in the ring and the sub-line, as today.
- **Long feeds before their art pass** (design 39 §1 decision 1, §6q 2: rows the server hasn't fetched yet, and every
  third channel-picture row): the same - the plain series cover. Decided the same way because it is the same row to
  the kid: a number on some covers and not others is the uncertainty the user named.
- A list with no pictures (`pictures` false: an NRK podcast sharing one cover, most own-file entries) keeps the number
  circles and ✓ exactly as today. The player's "Episode N av M" and the tile hints are untouched.

### 1.2 Code

- `M/logic/LibraryView.kt`, next to `listRows`: the decision as a pure function, so a test can pin it:
  ```kotlin
  /** A row's leading text (design 39 §4, r12 §1): none in a pictured list - a picture, its own or the series cover, is
   *  never written on; else ✓ for a heard row, else its number. */
  fun rowBadge(row: ListRow, pictures: Boolean): String? = when {
      pictures -> null
      row.heard -> "✓"
      else -> row.number.toString()
  }
  ```
  `listRows` itself is unchanged (`ListRow.number` stays for the plain lists).
- `M/ui/ListScreens.kt:202-216` `ListRowView`: `val number = rowBadge(row, pictures)`; the pictured branch calls
  `Cover(own ?: entry.cover, ..., fallback = entry.cover, decodeDp = 40.dp)` with no `content` (the `if (own == null)`
  scrim block goes) and its comment says "a row without its own picture shows the series cover plain (r12 §1)"; the
  number branch uses `number!!` (non-null there).
- Docs: music/CLAUDE.md's design 39 paragraph ("a row whose `artHash` is null shows the series cover with its number
  under `Palette.scrim`" -> "... the series cover plain, no number - r12 §1"), emulator.md §6q 1-2 ("the series cover
  with their number" -> "the plain series cover"). Design 39 stays as written (history); this note supersedes §1 rule
  1's number and §4's scrim.

### 1.3 Tests (JVM)

- `logic/LibraryViewTest`: `r12 - a pictured list's rows carry no number, own picture or not; a plain list keeps its
  numbers and ✓` - `rowBadge` over `listRows(...)` rows (heard, playing, plain) with `pictures` true -> all null;
  false -> "1", "✓", "3".

## 2. Skip 15 s both ways

### 2.1 Every place the two numbers appear

| Where | Today | r12 |
|---|---|---|
| `M/logic/Transport.kt:55-57` KDoc and constants | `"15 s tilbake" and "30 s fram"`, `SKIP_FORWARD_MS = 30_000L` | `"15 s tilbake" and "15 s fram" (design 50 §1.1, r12 §2)`, `15_000L` |
| `M/ui/PlayerScreen.kt:177` comment | `"15 s tilbake" / "30 s fram"` | `"15 s tilbake" / "15 s fram"` |
| `M/ui/PlayerScreen.kt:182, 203` `SkipButton`'s text | literal `"15"`, `"30"` | `skipSeconds(SKIP_BACK_MS)`, `skipSeconds(SKIP_FORWARD_MS)` (below), so the text can't drift from the step again |
| `res/values/strings.xml:67` `skip_forward` | `Forward 30 seconds` | `Forward 15 seconds` |
| `res/values-nb/strings.xml:65` `skip_forward` | `30 sekunder fram` | `15 sekunder fram` |
| `TransportTest.kt:95-111` (d50) | 90 s, 599 s cases built on 30 s | §2.3 |
| `FitTest.kt:61` comment | `"15"/"30"` | `"15"` (the width check is two digits either way: unchanged) |
| music/CLAUDE.md "The skip pair" | `"15 s tilbake" / "30 s fram"` | `"15 s tilbake" / "15 s fram"` |
| media session, notification, Bluetooth, Android Auto | no seek back/forward command, no increments | nothing (§0) |
| launcher lock tile | previous/play/next; `controls` unread | nothing (§0) |
| design 50 §1.1 | "30 s fram" | stays (history); this note supersedes it |

`skipBack`/`skip_back` ("15 sekunder tilbake") are already right. A Sonos room's settled seek and the web player's SDK
seek take whatever `skipWithin` returns: nothing room- or Spotify-specific changes.

### 2.2 Code

- `M/logic/Transport.kt`: `SKIP_FORWARD_MS = 15_000L`, the KDoc as above, and
  `fun skipSeconds(ms: Long): String = (ms / 1000).toString()` beside it (pure; the button's text).
- `M/ui/PlayerScreen.kt`: both `SkipButton` calls take `skipSeconds(...)`; the comment as above.
- The two strings.

### 2.3 Tests (JVM)

- `TransportTest`: the d50 cases renamed `d50 r12 - 15 s back and 15 s on stay inside the item`: `skipWithin(60_000,
  SKIP_FORWARD_MS, 600_000) == 75_000`; the boundary case moves to `skipWithin(590_000, SKIP_FORWARD_MS, 600_000) ==
  599_000`; the "already that close" and the context cases keep their values (179_000 from 170_000 still clamps).
  One new assert: `SKIP_FORWARD_MS == SKIP_BACK_MS` and `skipSeconds(SKIP_FORWARD_MS) == "15"`.

## 3. One "Favoritter" tile: every heart, all sources, one queue

### 3.1 Behaviour on the library

- **The chip** "Favoritter" (first after "Alle") shows while at least one heart is on an item the library still lists
  (`mixOrder` below non-empty) - as today in practice; with no hearts there is no chip, so "Ingen favoritter ennå" is
  never needed. Unhearting the last one while the chip is selected falls back to "Alle" (`effectiveCategory`, as today).
- **Under the chip: one tile**, never the entries holding hearts (r8's per-entry tiles go). Its cover (decision 1):
  **the heart on the calm card ground** - `Cover`'s no-picture gradient with `Palette.card` instead of a category colour
  and the `player_glyph_favorite` heart (the chip's glyph) in `Palette.peach` at `carouselGlyph(cover)` size. Why not a
  2x2 mosaic of the hearted covers: it changes with every heart (the tile would jump while the kid hearts things),
  decodes four pictures for one tile, and four busy covers in one square is the attention-seeking look the launcher-look
  rule rules out; the heart is the chip's own sign, quiet and stable. Same size, shadow and play mark as every tile; one
  page, no dots, no swipe.
- **Its two lines**: "Favoritter" (21 sp, as an entry's name) and the count, `plurals favourites_count`: nb "%d
  favoritt" / "%d favoritter", en "%d favourite" / "%d favourites" - every listed heart (`mixOrder`'s size), so the
  number is what the kid hearted, not what this output can play now. No progress line (its room stays reserved).
- **Dimmed** (design 50 §1.4's rule, `UNPLAYABLE_ALPHA`, no play mark, "Trenger Wi-Fi" in the count's place): on the
  phone when no item of the phone's view can play now; never on a room.
- **A tap plays the whole set** (`PLAY_FAVOURITES`, §3.7). The same-tile rule (vibb's tile press): a tap while the
  favourites queue is loaded unpauses it, never restarts it. Otherwise a new queue from the hearts of that moment.
- Hearts still show and toggle on every list row and beside the player's number, in every entry's own list.
- **Back from the player** while the favourites queue plays lands on the "Favoritter" chip and its tile
  (`backToLibrary`); the carousel's one start after a restart with a stored favourites queue does the same (`startOn`).

### 3.2 The queue

- **Membership**: every heart on an item its entry's listing names (own files: the parsed library), in an entry the
  library holds - `Snapshot.heartsListed`'s rule across entries. A heart on an item the listing dropped, or in an
  entry gone from the catalog, is out at once (it waits for `Sync`'s prune as today). A Spotify context's synthetic
  item never has a heart (`canFavourite`), so a context entry is never in it.
- **Order** (decision 2): **last hearted first**, by `favourites.heartedAt` (Room 8, §3.6). The hearts set before r12
  have `heartedAt` 0 and sort after every new one, among themselves by the library's entry order (`sort`, `id`, as
  `Snapshot.entries`), then the entry's listing (reading) order - deterministic, and the user's next hearts come first.
- **This renderer's view** (r8's rule per item): the phone holds what it shows (`Snapshot.visible`: a Storytel book only
  with its copy, an own file only with its copy, an NRK/RSS item with a url or a copy, a list-mode Spotify item); a room
  holds what a speaker can play (`PlayBodies.speakerPlayable`: every Storytel book, no own file, no Spotify, no
  withdrawn item, no non-series HLS, no private/tailnet literal on a public entry). The tile's count is not the view.
- **Fixed at the tap** (r8 §1.1): a heart set or cleared while it plays changes nothing in the playing queue; the
  current item plays on when its heart goes. The next tap that starts a new queue reads the hearts of that moment.
- **Shuffle** only when the kid turned it on for the favourites (its own row, `MIX_KEY`, §3.6): a fresh permutation at
  the tap. **Repeat** its own too (off -> all -> one). Toggled from the player (§3.7: the side slots follow the playing
  item; the toggle acts on the whole queue).
- **Where it starts**: the first item of the queue, at its own saved place when its entry resumes (`resumeFrom` of its
  position, `seekGate`), from 0 when it doesn't (r8's "where it starts" per item, with no bookmark: the queue has none).
  Every later item starts the same way when the queue reaches it (on the phone's own player, and on a room: a new run is
  a new start, §3.4).
- **Places** (decision 3): positions are saved per (entry key, item key) as for any queue, so a hearted episode heard
  half in the favourites is half-heard in its own list. The favourites queue **does not move the entries' bookmarks**
  (the next "Alle" tap on that podcast continues where the kid left the podcast, not where the favourites passed
  through) and **its end clears no place** (vibb's `clear_state` drops one entry's partials at that entry's end; a mix
  of many entries ending says nothing about them). Its end only clears the stored queue. Done marks as everywhere.
- **The player**: the header line is the playing item's entry name (as today, it follows the item); the number line is
  "Favoritt N av M" (`R.string.favourite_of`, nb "Favoritt %1$d av %2$d", en "Favourite %1$d of %2$d") over the queue;
  the carousel's pages are the queue, each with its own picture, else its own entry's cover; the heart beside the
  number is the playing item's (its own entry). The side slots follow the playing item's entry (`controlSlots`): the
  15 s pair on an episode or a book, shuffle/repeat on a song - which then act on the favourites queue.
- **"Liste"** shows the queue (shuffled or not, as it plays): one row per item with its picture - always the pictured
  form (§1: plain, no number) -, the title, the sub-line "<entry name> · 12:34" (playing/heard as today), its
  download mark and its heart. No order button, no offline summary. A pick of a row plays that item inside the queue
  (from its own place); a pick in any entry's own list, Nytt or search plays that entry as today and leaves the
  favourites queue.

### 3.3 The model: runs of one entry under a favourites queue

The service keeps a `MixQueue` beside `active`. At every moment `active` is an ordinary one-entry queue - **a run**: the
consecutive items of the favourites queue that belong to the playing item's entry (`segmentAt`), in the favourites
order, kept to exactly those keys by r8's scope (`Active.favourites` = the run's keys, so `activeFor`'s
`scoped(...)` guard and every carry r8 built - restore, adopt, phone <-> room - keep the run). The renderer plays the
run as it plays any queue (ExoPlayer's own advance and preloading within it, the web player's uri window, a room's
one-item-at-a-time advance); the run's natural end is the service's cue to start the next run. With "last hearted
first", hearts set in a row on one playlist or podcast form one run; shuffled, most runs are one item.

Why runs and not one item at a time: the web player then sends one `PUT /me/player/play` per run, not per track
(Spotify's own advance between tracks is gapless), and ExoPlayer keeps preloading the next episode of the same run.
Why not one mixed `Active`: every per-entry rule (§0) would need a per-item branch, and no renderer can take the mix.

Pure, in `M/logic/Mix.kt` (new, GPL; `MixTest`):

```kotlin
/** The favourites queue's own rows (shuffle, repeat): never an entry key (the server's `state_key` is [a-z0-9_-]+ or `own-<id>`). */
const val MIX_KEY = "vibb:favourites"

data class MixRef(val entryKey: String, val itemKey: String)

/** Every heart [entries] (library order) still list ([listed]: an entry's keys in reading order), newest heart first;
 *  [hearts]: entry key -> item key -> heartedAt (0 = before Room 8), ties by entry order, then reading order. */
fun mixOrder(entries: List<Entry>, listed: (Entry) -> List<String>, hearts: Map<String, Map<String, Long>>): List<MixRef>

/** This renderer's view of [order]: the refs it can hold ([holds]: the phone's `visible`, a room's speaker filter). */
fun mixView(order: List<MixRef>, holds: (MixRef) -> Boolean): List<MixRef>

/** A tap: the queue fixed now - [view] as it is, or shuffled ([random] for the tests); null when empty. */
fun mixTapQueue(view: List<MixRef>, shuffle: Boolean, random: Random = Random.Default): List<MixRef>?

/** The run around [index]: the consecutive refs of its entry, [from] its first index in [queue]. */
data class Run(val entryKey: String, val from: Int, val keys: List<String>)
fun segmentAt(queue: List<MixRef>, index: Int): Run

/** After [run]'s natural end: the next index, 0 with repeat ALL past the last, null = the queue's end. */
fun afterRun(queue: List<MixRef>, run: Run, repeat: Repeat): Int?

/** The first ref from [from] (forward, or back for previous) that can play now, one lap at most ([moveOnIndex]'s rule
 *  over the queue: wraps only with repeat ALL, never back to [origin]); null when none. */
fun mixPlayableFrom(size: Int, from: Int, forward: Boolean, repeat: Repeat, origin: Int?, playable: (Int) -> Boolean): Int?

/** Shuffle toggled with [current] playing: on - [current] first, the rest a permutation; off - [order] (the tap's
 *  unshuffled view) rotated to [current], refs no longer in [order] dropped but [current]. */
fun toggledMix(order: List<MixRef>, queue: List<MixRef>, current: MixRef, on: Boolean, random: Random = Random.Default): List<MixRef>

/** The stored queue (`now_playing.mix`): refs as "entryKey\u001FitemKey" joined by '\n'; a malformed line is dropped. */
fun encodeMix(queue: List<MixRef>): String
fun decodeMix(text: String?): List<MixRef>?

/** After a restart, a room switch or coming home: [stored] kept to this renderer's view ([holds]); the index of
 *  [current] when it survived (same = true: its stored place stands), else the next surviving ref after it (wrapping,
 *  same = false: its own place); null when nothing survived. */
data class MixRestore(val queue: List<MixRef>, val index: Int, val same: Boolean)
fun restoredMix(stored: List<MixRef>, current: MixRef?, holds: (MixRef) -> Boolean): MixRestore?

/** What a run's end does: the next run playing, held at its start (the sleep sheet's "Til slutten" fired there), or the end. */
sealed interface RunEnd { data class Next(val index: Int, val play: Boolean) : RunEnd; data object End : RunEnd }
fun runEnd(next: Int?, heldBySleep: Boolean): RunEnd

/** A web player line that ends Spotify for this favourites queue (§3.4): an adult's matter or a phone without Widevine
 *  - never the network's lines (the gate is checked first), "Spilles et annet sted" or "Stoppet - trykk spill". */
fun mixDropsSpotify(state: RendererState?): Boolean =
    state == RendererState.SPOTIFY_ASK_ADULT || state == RendererState.SPOTIFY_NOT_ON_PHONE || state == RendererState.SPOTIFY_UNAVAILABLE
```

`nextAction`/`previousAction` (`M/logic/Transport.kt`) are reused over the favourites queue's indexes (vibb's wrap,
the 5 s restart, the long-form rule by the playing item's duration).

### 3.4 The advance across sources

**Per item, on the phone** (the renderer is the run's, chosen by `start` -> `usePhoneRenderer`):

| Item | Plays through | Can't play now |
|---|---|---|
| NRK/RSS episode, NRK series (HLS) | `LocalRenderer` (ExoPlayer): its copy, else streamed through the gate, written through (design 30) | no copy and the streaming gate refuses: "Ikke lastet ned - trenger Wi-Fi", the queue moves on |
| Own file | `LocalRenderer` from its copy | not on the phone: not in the view |
| Storytel book | `LocalRenderer` from its copy only (`copyOnly`, no mint) | not downloaded: not in the phone's view |
| Spotify track/episode (list mode) | `SpotifyRenderer`: `PUT /me/player/play` with the run's uris at the item's place | the gate refuses (`spotifyPlayability`): skipped like a stream, "Ikke lastet ned - trenger Wi-Fi"; an account line: Spotify drops for this queue (below) |

**On a room** (the renderer stays the `SonosRenderer`; each run is a new `load`):

| Item | Plays through | Left out |
|---|---|---|
| NRK/RSS episode | `SetAVTransportURI` (vibb's `url` body; an NRK series item `nrk_program`), the controller's own advance inside the run | a private/tailnet literal on a public entry, non-series HLS, withdrawn |
| Storytel book | minted inside the play's actor job (6 s, 10 s in all; design 26b §5.1, 58's prefetch) | never |
| Own file | - | always ("the phone never serves", 26a) |
| Spotify | - (54-a2, below) | always in this round |

**Within a run** nothing changes: the renderer's own advance, `onItemChanged`'s move-on past an item that can't play,
`onError`'s move-on, repeat ONE (the run gets `setModes(false, mix.repeat == ONE ? ONE : OFF)`; repeat ALL and shuffle
are the queue's, never the run's). Within a run every rule a queue of that entry has today holds.

**At a run's end** - the renderer's `Ended` (ExoPlayer's queue end; the web player's list end, after its own Autoplay
belt; the room's `advance()` with no next, which sends Stop) - `queueEnded` (`:1731`) marks the last item done as today
and, with a favourites queue loaded, does **not** run `clear_state`: it hands over to `runEnded()`, launched on main
under `switching` with a token (the same `MixQueue` and `Active` still loaded, else nothing). `runEnded`:
`afterRun(...)`, then `mixPlayableFrom(...)` from there (the phone's `itemPlayable` per ref from the snapshot - its copy,
its url, the gate; on a room every ref of the view) - each skipped ref says "Ikke lastet ned - trenger Wi-Fi" once per
run -, then `startRun(index, play)`; `RunEnd.End` (no next with repeat off, or nothing playable in a lap) ->
`mixEnded()`: the stored queue cleared (`clearNowPlaying`, nothing else), `mix = null`, `active = null`,
`renderer.clear()`, `Resumable` false, `publish()`.

**`startRun(index, play, newChoice = false, posMs = null)`** builds the run's queue through the existing seam:
`QueuePlan(run.keys, index - run.from, posMs ?: startPos(entry, its position))` -> `queueFor(snap, rows, entry, plan,
shuffled = false, queueKind(renderer.kind, entry), favourites = run.keys.toSet())` -> `start(a, p, play, newChoice)`.
So **the transport switch between neighbours is exactly today's switch between entries**: `start` -> `usePhoneRenderer`
-> `attach` (the old renderer saved with force and paused, the web player `detached()`, ExoPlayer `standDown()` to IDLE
so it holds no focus) -> the new renderer's `load` + `play`. What the two players keep:

- **ExoPlayer** after a Spotify run starts: IDLE with no items (`standDown`); the next URL run's `load` prepares it again
  - a copy sounds at once, a stream after its first bytes (~0.2-1 s on Wi-Fi). Gapless is not required and not given.
- **The web player** after a URL run starts: detached and paused; its page (and the Connect device "Vibb") lives on for
  `SPOTIFY_HOST_IDLE_MS` (5 min) after the pause, then is destroyed (26c §5.4). So a Spotify run after a short URL run
  starts warm (the play call, ~0.3-1 s), and after a run longer than 5 minutes - a podcast episode - it starts cold (the
  page, the SDK script, `ready`, the token: the user's "noen sek ... første gangen på en stund"). Accepted; a pre-warm is
  §5's widening.
- The audio focus: the WebView's own (Chromium takes it when its audio starts), ExoPlayer's from its `load`; the
  stand-down releases ExoPlayer's - as when the kid switches entries today.

**Spotify's account lines**: a Spotify run whose start ends paused on `mixDropsSpotify(renderer.rendererState)` while the
kid wanted it playing (the service sees the web player's `Paused`/`StateChanged` with that state, a favourites queue
loaded and `renderer === spotify`): "Kan ikke spilles nå" once, every Spotify ref is unplayable for the rest of this
queue (`MixQueue.spotifyOut`), and the queue moves on as at a run's end. A new tap tries Spotify again. "Spilles et
annet sted" (the account took it elsewhere) and "Stoppet - trykk spill" pause as today; "Trenger internett" mid-run
pauses as today (every stream needs the network). An unplayable track (the SDK's `playback_error`) is `onError`'s
move-on inside the run; at the run's last track that move-on is the next run (below).

**Steps that leave the run** (`skipNext`/`skipPrevious` `:1213-1232`, `goTo` `:1245`, `skipTo` `:1205`, the move-ons in
`onItemChanged` `:1523-1534` and `onError` `:1666-1674`): with a favourites queue the target is counted over the queue
(`nextAction`/`previousAction` at the queue index), then `mixPlayableFrom` in the step's direction; inside the run it is
today's `renderer.seekTo` (`goTo`); outside it is `startRun(target, play = renderer.player.playWhenReady)` - a paused
player stays paused at the new item, a playing one plays (design 27's swipe semantics). A step outside the run needs IO
(the snapshot, the claims): from inside a command (`SKIP_TO`, `RESTORE`'s `THEN_*`) it runs in place; from `KidPlayer`
(the notification, the lock tile, a headset) it is launched under `switching` with `waitingMoves(+1/-1)` around it so the
screens' hold (`PlayerHub.State.waiting`) lasts until it has run. **The web player's synchronous move-on**
(`onError` stays synchronous for a Spotify item, `GuardsTest`): inside the run unchanged; at the run's last track there is
no synchronous step - the web player pauses (its `MoveOn` without a seek) and the launched `startRun` attaches the next
renderer. The implementer confirms that order in `SpotifyRendererTest` (an `Error("unplayable")` on the window's last
track with no step: paused, nothing sent).

**"Til slutten"** (design 50 §1.2): inside a run, the renderer's own pause at the item's end, as today. At a run's last
item: the phone (`pauseAtEndOfMediaItems`) and the web player (its own pause before the end) call `sleepAtEndReached`
-> with a favourites queue `runEnded(heldBySleep = true)`: the next run is loaded **paused at its start**
(`RunEnd.Next(play = false)`), the next favourite waits for a play, as a room's `holdAtEnd` does inside a queue
(`sleepAtEndReached` (`:1381`) runs first as today, then the run's hand-over; ExoPlayer may sit paused at the last
item's end without ever reaching `Ended` - the next run replaces it). A room at a run's last item sends Stop and `Ended`
with "Til slutten" still on (`advance()` has no next): `queueEnded`'s first line, `endSleepAtEnd(QUEUE_ENDED)`
(`:1732`), moves below the favourites branch, which reads `heldBySleep = sleepAtEnd`, calls `sleepAtEndReached()` (the
item-end pause's own event) and hands over held. **The minute timer** runs across runs: `attach` re-posts `sleepTick`,
the fade is the new renderer's.

**Calls** (26a §6.7): the call rule pauses the playing renderer; a run can't end while paused, so no switch happens in a
call. If one does (a run ended in the ring's first second), `callStep`'s "the renderer changed under the call" rule
already drops the replay: nothing restarts after the call - the kid presses play.

### 3.5 On a room (Sonos)

- **A tap on a room**: the queue is the room's view (§3.2), so own files and Spotify are left out with the existing log
  line (`queueFor`'s "`sonos: N item(s) a speaker can't play left out`", per run; one more line at the tap with the
  totals). Nothing left: "Kan ikke spilles på Sonos", nothing changes (`queueFor`'s path).
- **The advance**: each run is a `start(a, p)` with the room's renderer attached (`usePhoneRenderer` returns at once
  off the phone): `SonosRenderer.load` -> the controller's play of the run's first item (the hush, the settled seek
  for a place of 5 s or more, a Storytel mint in the job), its own advance inside the run, `advance()`'s Stop and
  `Ended` at the run's end, then the next run's play: one `Stop` + `SetAVTransportURI` + `Play` at each boundary - one
  more Stop than an advance inside an entry, about a second of silence. `renderer.json` (`RendererChoice`) follows the
  run's entry and item, as any play writes it.
- **Phone -> room** (`toRoom`, `:2222`): with a favourites queue, `restoredMix(mix.items, current, speaker filter)` -
  the current item (its place from the flush) or, when it is an own file or a Spotify track, the next speaker-playable
  ref from its own place - then `startRun` on the room. **Room -> phone** (`comeHome`, `:2311`): `restoredMix` over the
  phone's view from the hand-off's item; a Storytel book not on the phone is not in that view, so the queue goes on at
  the next favourite with "Ikke lastet ned - trenger Wi-Fi" once (deliberately unlike an entry's queue, which waits:
  a series must not skip a book, a favourites queue has no series). The queue's order is kept; the left-out items are
  back in the phone's view.
- **Adopt** after a restart on a room (`adoptedQueue`, `:2158`): the stored row is the run's (its entry, keys, scope), as
  today; with `now_playing.mix` the favourites queue comes back around it (`restoredMix` over the room's view).
- **Spotify on a room - hand-over to 54-a2** (not built in this round): once `speakerDropReason` lets Spotify through,
  a favourites run of Spotify items on a room goes as **one share link per track** (`ShareLink.TRACK`/`EPISODE`, a
  one-track speaker queue: `PlayBodies.share` with `listed = [uri]`, `index` 0 - the map is trivially trusted), never the
  entry's whole playlist; each Spotify ref is then its own run on a room. The next URL run's `SetAVTransportURI`
  replaces the transport (the speaker's queue stays as the source it left, 54 §2.9's park clears it only for a share
  link). 54-a2's tests must cover: a URL run after a share run (the classify drops the share knowledge), a share run
  after a URL run, the end of a one-track share (`Ended`, not a wrap). **Untested on real hardware** in this round: the
  boundary Stop + play between two entries on the user's speakers, a Storytel mint at a run boundary on a real room,
  and everything Spotify-on-Sonos; the emulator checks use `scripts/fake-sonos.py` (no event socket) only.

### 3.6 Data

- **Room 8**, one `AutoMigration(from = 7, to = 8)`:
  - `favourites.heartedAt INTEGER NOT NULL DEFAULT 0` - `FavouriteRow(entryKey, itemKey, @ColumnInfo(defaultValue = "0")
    val heartedAt: Long = 0)`. `MusicViewModel.setFavourite` (`:457`) writes `System.currentTimeMillis()`; the insert
    stays `IGNORE` (a set heart keeps its time).
  - `now_playing.mix TEXT` (nullable, `NowPlayingRow`'s **last** field, `= null`): `encodeMix` of the favourites queue;
    null = an entry's queue, as before. The row's other fields stay **the run's** (`entryId`, `keys` = the run's keys,
    `index` within the run, `posMs`, `shuffled` false, `favourites` = the run's scope), so Home's card, the bar, the
    flip's row rewrite (`flippedNowPlaying`: an unshuffled row of that entry - a run row: harmless, it reorders only
    within the run; the implementer makes it return null when `mix != null`) and every reader of an entry's row keep
    working. Exported `schemas/me.vibb.music.data.MusicDb/8.json`.
- The favourites queue's shuffle and repeat: the existing `shuffle` and `repeat` tables under `MIX_KEY` (never pruned:
  no prune touches those tables; `MIX_KEY` can't collide with an entry key).
- `Snapshot`: `favourites` stays; new `heartedAt: Map<String, Map<String, Long>>` from the same rows
  (`Store.snapshot`, `buildSnapshot`'s new parameter).
- **r8's option C goes** (its only entry point, a cover tap under "Favoritter", is gone): `Commands.ARG_FAVOURITES`,
  `playEntry`'s `favourites` parameter (`resumeLast`, `restartIfStale`, the bundle), `coverTapScope`, `favouritesScope`,
  `bookmarkIn`, `entryUi`'s and `playSwitch`'s `favourites`, `PlayerHub.State.favourites`, `NowView.favourites` and the
  pick-inside-the-scope rule in `playItem`/`playSwitch`. **Kept**: `Active.favourites` with `scoped` in `activeFor` and
  every carry (restore, adopt, `toRoom`, `comeHome`, `storedScope`, `queueEnded`'s scoped clear) - they now carry a run.
  A stored row from r8 (a C queue, `favourites` set, `mix` null) restores as it did: that entry's hearted items, ending
  as before.

### 3.7 Code

**Service** (`M/playback/PlaybackService.kt`):

- `internal class MixQueue(val order: List<MixRef>, var items: List<MixRef>, var index: Int, var shuffled: Boolean,
  var repeat: Repeat, var failRunFrom: Int? = null, var spotifyOut: Boolean = false)`, `private var mix: MixQueue?`;
  `Active` gains `val mix: MixQueue? = null` (the same object, for the row writers). Every queue that isn't a run
  (`playEntry`, `playItem` outside the queue, a restore of a row without `mix`) sets `mix = null`.
- `Commands.PLAY_FAVOURITES` (in `ALL`, `PLAYS`, `MOVES`) -> `playFavourites()`: the same-tile check
  (`mix != null && renderer.itemCount() > 0` -> `renderer.play()`); else `saveNow(force = true)` when something is
  loaded, `loadForRenderer()`, `mixOrder` -> `mixView` (the renderer's view) -> `mixTapQueue` (shuffle from `MIX_KEY`),
  empty -> `NOT_DOWNLOADED` on the phone / `CANT_PLAY_ON_SONOS` on a room and nothing changes; else
  `mix = MixQueue(...)`, `startRun(mixPlayableFrom(0) ..., play = true, newChoice = true)`.
- `Commands.PLAY_ITEM` gains `ARG_IN_FAVOURITES` (the favourites list's pick): with the ref in the loaded (else the
  stored) queue -> `startRun`/`goTo` at it, `newChoice = true`; else today's whole-entry pick.
- `playEntry` (`:781`): the same-tile branch also needs `mix == null` (§0). `resumeLast` (`:840`): with a favourites
  queue loaded, `renderer.play()` (no Spotify re-plan); else as today. `restartIfStale` (`:531`): with a favourites
  queue, the stale run's refs are skipped (`spotifyOut`-style: the run's entry is now a context, its track keys gone)
  and the queue moves on; never `playEntry`.
- `KidPlayer.hasNextMediaItem`/`hasPreviousMediaItem` (`:508-509`): with a favourites queue, its size > 1.
- `skipNext`/`skipPrevious`/`goTo`/`skipTo`/`onItemChanged`/`onError`: §3.4's "steps that leave the run";
  `queueEnded` and `sleepAtEndReached`: §3.4's run end; `toggleShuffle`/`toggleRepeat` (`:1265-1304`): with a favourites
  queue, `toggledMix` and the `MIX_KEY` rows - the playing item plays on, the renderer keeps it as the run's first
  (`renderer.reorder(index, the new run's following items)`, `a.items` to match); `flipOrder`: nothing with a
  favourites queue (no flip button there).
- The writers: `persistPlace` (`LocalRenderer.kt:279`) and `saveBookmark` (`:1781`) write the row with
  `mix = a.mix?.let { encodeMix(it.items) }` and, when `a.mix != null`, **no bookmark row** (`Place.bookmark`
  nullable, `writePlace` skips it). `SonosSuspension`'s standalone place (renderer.json's entry) may still write that
  entry's bookmark: accepted (the suspension of a room with the service gone), said in its KDoc.
- `restore()` (`:890`): a row with `mix` -> `decodeMix` -> `restoredMix(..., current = the row's run item, holds = this
  renderer's view)` -> the run at that index (`same`: the row's place, else the ref's own) -> `mix` set; a Storytel book
  not on the phone is not in the view (no `Restored.NotOnPhone` for a favourites row - §3.5's rule). `onPlaybackResumption`
  (`:613`) uses it as today.
- `publish()` (`:1833`): `PlayerHub.State` gains `favouritesQueue: List<MixRef>?`, `favouritesIndex: Int`,
  `favouritesShuffled`, `favouritesRepeat` (null/0/false/OFF without one); `entryId`/`keys`/`index` stay the run's (the
  Spotify tile line, the claims, the art). `reportNowPlaying` unchanged: the run's entry name and item - the launcher
  sees the playing item's entry and title.
- The art window (`wantArtSoon`, design 27/39's `nowArt`): the next `NOW_REACH` refs of the favourites queue, their own
  `artHash` (each item's picture comes with its own entry's art pass; nothing new on the server).

**Screens** (`M/ui/`, `M/logic/`):

- `MusicRoot.Library` (`:252-292`): under "Favoritter" `LibraryScreen` gets `favourites = FavouritesUi(count, dimmed,
  needsWifi)` and no entries; its carousel draws that one tile (§3.1); `onPlay` -> `playFavourites()`:
  `vm.switching = favouritesSwitch(...)`, `actions.playFavourites()`, the player. `Actions` gains `playFavourites()`;
  `playEntry(id)` loses its flag; `playItem(entryId, key, inFavourites = false)`.
- `UiModel.kt`: `NowView` gains `favourites: Boolean` (a favourites queue) and `entries: Map<Long, Entry>` (each page's
  entry; `entryOf(item)`); `queue` holds the queue's `PlayItem`s (each carries its `entryId`); `entry` is the playing
  item's. `nowView` (`:289`) builds it from `hub.favouritesQueue` (live) or `row.mix` (stored); `pagerKey` becomes the
  refs; `carousel` = more than one page and two different pictures (`artHash ?: its entry's cover`). `slots` stays
  `controlSlots(entry)`; `canShuffle`/`canRepeat` = that slot, and `shuffled`/`repeat` are the queue's.
  `playerNumber` (`:337`): the queue's index + 1 and size for a favourites view. `favouritesSwitch(snap, current, atMs,
  onRoom)`: the first ref and its place (shuffled: `itemKnown = false`, as a shuffled cover tap).
  `skipSwitch`/`swipeSwitch`/`toIndex` (`:260-287`): the target's own entry (its `resume`, its positions,
  `PlayRequest(target.entryId, key, ...)`).
- `EpisodeCarousel.kt:178, 199` and `PlayerScreen`'s heart and number: `entryOf(item)`'s cover and heart; the number
  text `favourite_of` for a favourites view.
- `ListScreens.kt`: the favourites rows (`favouritesRows(snap, now)`: each `ListRow` + its entry), the lazy key
  `"${item.entryId}/${item.key}"` (§0), `pictures = true`, the sub-line with the entry's name; no order UI.
- `MusicViewModel.backToLibrary`/`startOn` (`:386-417`): a favourites queue -> `category = FAVOURITES_CATEGORY`,
  index 0. `LibraryView.kt`'s `shownEntries`/`effectiveCategory` keep "Favoritter" by `mixOrder` non-empty (the
  entries list under it is unused).
- Strings: `favourites_count` (plurals), `favourite_of`; nothing names a source (26c step 8's `SourceScanTest`).

### 3.8 The launcher, the bridge, the server

- **Nothing changes in `bridge_contract.json`, the AIDL or `music_state`**: `setNowPlaying` keeps its keys - `entry`
  is the playing item's entry name, `item` its title, `controls` the playing item's pair - and the launcher's lock tile
  and Home card (previous/play/next, RESUME) work as on any queue; their previous/next reach `KidPlayer`, which steps
  the favourites queue (§3.4).
- **The server: nothing.** Hearts are phone-only (design 50 §1.5: never synced, reported or in the PWA); no listing,
  library or report field changes.

### 3.9 Edge cases

- **Zero hearts**: no chip (§3.1). Hearts on nothing this output can play (a room with only hearted own files and
  Spotify; a phone whose hearted books aren't downloaded): the tap says why, nothing changes (§3.7).
- **A heart removed while playing**: plays on; the queue keeps the item (fixed at the tap). **Added**: not in this queue.
- **A hearted item whose entry left the catalog**: out of the next queue at once (`mixOrder` reads the library);
  while it plays, it plays on as any queue's item; the queue's later refs of that entry are skipped by the run builder
  (no `activeFor` for a gone entry: `mixPlayableFrom` treats them as unplayable, no toast); the heart is pruned by
  `Sync` once every entry was read (`favouriteEntriesGone`).
- **A run's entry flips list -> context** (Spotify, design 26c §5.9) while queued: its track keys are no longer listed,
  so its refs drop at the next run build; playing, `restartIfStale`'s favourites branch moves on.
- **Sleep timer**: §3.4. **Calls**: §3.4.
- **The carousel's swipe over a mixed queue** (`SKIP_TO`, design 27): by (entry, key) in the queue; inside the run
  `goTo`, outside `startRun` with the play state kept; on a room "a swipe on a playing room starts from 0" as today
  (each run's start on a room is from 0 for a step, `stepTo`'s rule: the implementer passes `posMs = 0` on a playing
  room).
- **Resume after process death** (`RESTORE`, RESUME, a headset's play): §3.7 `restore()`. A stored Spotify run loads
  paused with no page and no network, as any stored Spotify queue (26c: "nothing starts by itself on Spotify either").
- **Positions** per (entry key, item key) as always; the entries' bookmarks untouched; the end clears nothing (§3.2).
- **The suspension** (a time rule): as today on the run's renderer; nothing of the favourites queue starts while
  suspended (`PLAY_FAVOURITES` is in `PLAYS`).
- **Two equal item keys in two entries**: refs and the list's keys carry the entry (§0, §3.7).

### 3.10 Tests (JVM, lean)

- `logic/MixTest` (new): `mixOrder` - newest heart first, the pre-Room-8 zeros after them in library then reading
  order, an unlisted heart and a gone entry's out; `mixView` - the phone's (a Storytel book and an own file only with a
  copy, Spotify in) and a room's (own files and Spotify out, every Storytel book in, a withdrawn item out);
  `mixTapQueue` - unshuffled as the view, shuffled a permutation (seeded), empty null; `segmentAt` - a run in the middle
  starts at its first ref, a lone ref is a run of one; `afterRun` - next, the wrap with repeat all, null at the end;
  `mixPlayableFrom` - skips the gated refs, one lap at most, never back to the origin; `toggledMix` both ways;
  `encodeMix`/`decodeMix` round trip and a malformed line dropped; `restoredMix` - the current ref survived (same), the
  next surviving one, nothing; `runEnd` with and without "Til slutten"; `mixDropsSpotify` - the three lines yes, the
  network's, "elsewhere" and "stopped" no.
- `logic/FavouritesTest`: the r8 cases of `favouritesScope`/`coverTapScope`/`bookmarkIn` and the service pins that name
  `ARG_FAVOURITES` go; the source pins become r12's: `queueEnded` hands a favourites queue to `runEnded` before any
  `clear_state` write; `persistPlace` and `saveBookmark` write no bookmark row for `a.mix != null` and store `mix`;
  `playEntry`'s same-tile branch reads `mix == null`; `KidPlayer.hasNextMediaItem` counts the favourites queue;
  `PLAY_FAVOURITES` is in `PLAYS` and `MOVES`. `GuardsTest`'s r8 pins (`:39-40`, `:78`, `:880`, `:975`) follow the
  removed flag; its `activeFor`/`queueFor` carries (`:639-640`, `:797-798`, `:894`, `:918`) stay.
- `ui/UiModelTest`: the three r8 cases go; new - `nowView` of a live and of a stored favourites queue (each page with
  its own entry, "N av M" over the queue, the slots of the playing item's entry); `favouritesSwitch` shows the first
  ref at its place; `skipSwitch` across a run boundary shows the next entry's item at its own place.
- `data/Room6Test`: `version = 7,` dropped; `r12 - 7 to 8 adds only favourites.heartedAt (NOT NULL, default 0) and
  now_playing.mix (nullable)`, every other table unchanged, `version = 8,` and `AutoMigration(from = 7, to = 8)`.
- `spotify/SpotifyRendererTest`: the one case of §3.4 (an unplayable last track with no synchronous step pauses and
  sends nothing more).
- §1.3, §2.3.

## 4. Emulator checks for the implementer

Debug builds of this branch, the debug feed (`docs/testing/emulator.md` §6f) or the bridge chain (§6l Part A1's setup);
a new emulator.md section **§6s** gets these. Logcat: `VibbSpotify`, `VibbSonos`, the service's lines.

1. **§1**: `scripts/fake-audio.py --feed 20` (§6q): the channel-picture rows and the not-yet-fetched rows show the plain
   series cover - no number, no ✓, no dark scrim; the playing one ringed; a heard row says "Hørt" in its sub-line. The
   plain listing (one picture): numbers and ✓ as before.
2. **§2**: a podcast episode at 1:00: "15" on both buttons; fram -> 1:15, tilbake -> 1:00; TalkBack reads "15 sekunder
   fram"; 10 s before the end, fram stops 1 s short of it (no next item). On the fake room (§6h): fram is a settled seek
   +15 s. On a Spotify show episode (fake-spotify `--print-listing` with an episode entry, if the fake has one; else skip).
3. **The tile**: heart, in this order, RSS episode 3 (`--feed 6`), a Spotify track 2 (fake-spotify, list mode,
   `--track-seconds 20`), an own file 1, RSS episode 5. "Favoritter" shows one tile, the heart on the card ground, "4
   favoritter"; no entry tiles under it. Tap: RSS 5 first, then own 1, Spotify 2, RSS 3 (newest heart first). The
   carousel has 4 pages with their own pictures; "Favoritt 1 av 4"; the header follows each item's entry.
4. **The switches**: seek near each item's end and let it advance: RSS -> own file (no renderer change), own file ->
   Spotify (logcat: the web player attached, its page made the first time - note the seconds to the tone), Spotify ->
   RSS (ExoPlayer back; the page stays), then within 5 minutes back to Spotify by previous (warm: under a second), and
   once more after 6 minutes on the RSS item (cold again).
5. **Steps across sources**: next/previous in the player, in the notification, `adb shell input keyevent
   KEYCODE_MEDIA_NEXT` / `MEDIA_PREVIOUS`, and the launcher's lock tile (debug launcher) - each crosses RSS <-> Spotify;
   paused, a carousel swipe to the Spotify page shows it at its place and stays paused, play plays it.
6. **Runs**: heart three tracks of the same Spotify list in a row: they play as one run - logcat shows one play call
   for the three, not three; shuffle on (on a song): the current plays on, the rest permuted, runs mostly one item.
7. **Can't play**: stream switch off, Wi-Fi off (`adb shell svc wifi disable`, mobile data on): the RSS streams and the
   Spotify track are skipped with "Ikke lastet ned - trenger Wi-Fi", the own file and a downloaded episode play. Wi-Fi
   on, fake-spotify `--not-premium`: the Spotify item shows "Spør en voksen", "Kan ikke spilles nå" once, the queue goes
   on and later Spotify refs are skipped; a new tap tries again. `--unplayable`: the track is skipped inside its run, or
   - last in its run - the next run starts.
8. **Storytel** (fake-storytel §6i): heart book 1 (downloaded) and book 2 (`--no-download`): the tile counts both; on the
   phone only book 1 plays (from its copy, no mint in the log); on the fake room both play (a mint per book).
9. **Hearts while it plays**: unheart the playing item - it plays on, the tile says 3; heart a new item - not in this
   queue; play something else, then the tile: the new heart first. "Alle" -> the RSS entry's tile: it continues where
   the RSS entry's own bookmark was, not where the favourites left it; the half-heard favourite episode shows its
   progress in its list.
10. **"Til slutten"** on the last item of a run: it pauses at the end; the player shows the next favourite at 0, paused;
    play plays it. The minute timer across an RSS -> Spotify switch fades on the web player.
11. **Restart**: pause on the Spotify item, `adb shell am force-stop me.vibb.music.debug`, open: the bar shows the
    Spotify item paused (no WebView in logcat); Home's RESUME plays it; next goes to the next favourite. Again paused
    on an RSS item, with the queue shuffled: the order comes back the same.
12. **The room** (fake-sonos §6h): "Spill av på" the fake room during the favourites queue: the room plays only the RSS
    and Storytel refs (logcat: the own file and Spotify left out), advances across entries by itself (fake-sonos's log:
    Stop + `SetAVTransportURI` per run); back to the phone: on at the room's item, the own file and Spotify back in the
    queue. Only own files and Spotify hearted: the room says "Kan ikke spilles på Sonos", the phone plays on.
13. **An entry removed** (the debug feed without it) while its favourite plays: plays on; its other favourites are
    skipped; after the sync the tile's count drops.
14. **Back and start**: back from the player during the favourites queue lands on "Favoritter" with its tile; after a
    restart with it stored, the library opens on "Favoritter".

## 5. Not in scope (possible widenings)

- Spotify on a room in the favourites queue (54-a2's hand-over, §3.5).
- Pre-warming the web player while a URL run's last ~30 s play when the next run is Spotify (no cold start after a long
  episode). "Nothing starts by itself" holds (the kid started the queue), but the page and the Connect device would
  exist before a Spotify play; its own small design.
- Hearted items in the downloads' keep set (a hearted old episode kept on the phone for offline favourites).
- A "same tile" tap that picks up hearts added since; a mosaic cover; a favourites queue per category.
- A gapless crossfade between runs.

## 6. Decisions for the user to confirm (defaults written in)

1. **The tile's cover**: the heart (the chip's glyph, peach) on the calm card ground - not a mosaic of the hearted
   covers (§3.1).
2. **The order**: last hearted first; the hearts set before this round (no time recorded) come after the new ones, in
   the library's order (§3.2).
3. **Places**: the favourites queue saves every item's own place but never moves an entry's bookmark, and its end
   clears no place; the player says "Favoritt N av M" and its side buttons follow the playing item (the 15 s pair on an
   episode or a book, shuffle/repeat on a song, acting on the whole queue) (§3.2).

## 7. Design-QA questions

1. The run model (§3.3): is a run's natural end the only cue the service needs, on all three renderers - ExoPlayer's
   `Ended` (and "Til slutten"'s pause at the last item, which may never reach `Ended`), the web player's list end after
   its Autoplay belt, the room's `advance()` Stop?
2. The cross-run step from `KidPlayer` is launched under `switching` (§3.4): any ordering hazard with a command already
   queued (a kid's tap during a run's end), beyond the token check? And the web player's synchronous move-on contract
   at a run's last track (`GuardsTest`): is "pause, then attach" safe?
3. Keeping r8's `Active.favourites` as the run's scope (§3.6): does any reader still treat a scoped queue as a C queue
   (the `queueEnded` scoped clear is unreachable for a run - is it everywhere)?
4. `persistPlace` without the bookmark (§3.7): every reader of an entry's bookmark (the tile's hint, Storytel's wait,
   `coverTapPlan`) - still right while a run of that entry plays?
5. The stored row being the run's (§3.6): Home's card, `flippedNowPlaying`, `CarouselStart`, `adoptPlan` - anything that
   would show or rebuild only the run where the favourites queue is meant?
6. Design 30's held set: the next run's first item is claimed only at its load (§3.4): can a sync between a run's end
   and the next load prune or evict it (a hearted played extra)? Is that acceptable (it then streams)?
7. On a room, the boundary is Stop + a new play (§3.5): does `endSession` at a run's end disarm anything the next run's
   play needs (the socket, the poll cadence, the scope watch)?
8. Removing C (§3.6): is anything of r8's still reachable (an r8 row's restore, `playItem`'s scope) that now needs a
   rule?

## 8. Size

- §1: XS - one pure function, the composable's branch, one test, three doc lines.
- §2: XS - one constant, one helper, two strings, the test edits, two doc lines.
- §3: L - `logic/Mix.kt` (~200 lines) and `MixTest` (~250), the service (~400 lines changed: the commands, the run end,
  the steps, the writers, restore/adopt/room switches, publish), the screens (~250: the tile, `NowView`/`nowView`, the
  switches, the list, the carousel/heart), Room 8 with its schema and test, C's removal (~-150 with its tests), music/CLAUDE.md
  and emulator.md §6s. Roughly 1,300-1,700 lines touched. Two commits on one branch is the natural cut: §1+§2 (shippable
  alone), then §3; one code QA over both.
