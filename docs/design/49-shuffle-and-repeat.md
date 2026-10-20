# 49 - Shuffle and repeat in the player's control row (proposal)

Asked 2026-10-20 ("en spesifikk shuffle knapp og repeat knapp ved siden av next/prev ... toggle for shuffle av og på
- og toggle for ikke repeat, repeat all eller repeat 1 sang"). Music app only. Builds on `logic/Queue.kt`
(`canShuffle`, `shuffledWithFirst`), the `shuffle` Room table (`ShuffleRow(entryKey, on)`), design 39's per-entry
flips (`order_flips`, the Room-5 pattern), `PlayerScreen`'s control row (`Fit.kt`: the two side slots of `row.side`
dp, today a shuffle button or a spacer), the renderers (`LocalRenderer`, `SonosController`, `SpotifyRenderer`).

## 0. Today

Shuffle exists for own files that don't resume (`canShuffle = source == OWN && !entry.resume`), toggled by
`Commands.SHUFFLE`/`THEN_SHUFFLE` and remembered per entry in `shuffle`; the button sits in the left side slot, the
right slot is a spacer. There is no repeat anywhere; a queue plays to its natural end (`clear_state`).

## 1. Decisions

1. **For which entries**: every `Kind.MUSIC` entry (`kindOf`: own music, Spotify playlists/albums/tracks) gets both
   toggles; podcasts and books (`Kind.PODCAST`, `Kind.AUDIOBOOK`, `Kind.OWN` that resumes) get design 50's skip buttons
   in the same slots instead. `canShuffle` becomes `kindOf(entry) == MUSIC` (a resuming own-music entry included - the
   user's framing: music shuffles). Shuffle stays hidden for a Spotify **context** entry whose target is an artist
   (Spotify picks there); a playlist/album context gets it (§1.4).
2. **The row**: left slot shuffle (`Icons.shuffle`, peach when on, the state as `stateDescription`), right slot repeat
   in three states off → all → one (`Icons.repeat`, `Icons.repeatOne` with the "1" badge from Material Symbols
   `repeat`/`repeat_one`; peach when not off). Both remembered per entry on the phone: `shuffle` as today, a new
   `repeat` Room table (`RepeatRow(entryKey, mode)`, Room 6 with an auto migration; never synced or reported), read into
   `Snapshot` like the flips.
3. **The phone's player** (`LocalRenderer`): shuffle as today (our order, `shuffledWithFirst`); repeat all =
   `Player.REPEAT_MODE_ALL`, repeat one = `REPEAT_MODE_ONE` on the ExoPlayer, so the natural end never clears the state
   (`clear_state` runs only when repeat is off); the now-playing bar and Home's card follow (the queue wraps).
4. **Spotify**: list mode - repeat all re-sends the window from the first track when the last ends (the tracker's end
   detection already knows the last item); repeat one re-sends the current track at its end; shuffle shuffles our
   list on the phone (the tracker's "ours" widens to any uri of our window). Context mode - Spotify's own API is set to
   the kid's choice: `PUT /me/player/shuffle?state=` and `PUT /me/player/repeat?state=off|context|track` (the calls
   exist, `SpotifyApi.shuffle`/`repeat`); today's "shuffle off, repeat off before the first play" becomes "set to the
   entry's remembered choice"; the tracker already takes every track of the context as ours; design 46's queue view
   follows what Spotify plays.
5. **Sonos**: shuffle as today's order rule in `SonosController` (vibb's); repeat all = the controller wraps at the end
   instead of stopping; repeat one = the controller re-plays the item at its end (one `Seek`/`Play`, no SOAP storm).
6. **Interaction with the flip** (design 39): the flip reverses the order; shuffle ignores it; repeat applies after
   either. The flip button stays hidden while shuffled (unchanged).
7. **Look**: Spotify's app for the two glyphs' meaning, vibb's player for everything else; no animation; the "1"
   badge is part of the glyph.

## 2. Tests (required)

`QueueTest`: `canShuffle` by kind, the repeat modes' wrap rules (pure `nextAction`/end handling with repeat), the flip
interaction. `OrderFlipTest` unchanged. Room: the auto migration 5 → 6 (schema in `app/schemas/`). `LocalRenderer`:
the ExoPlayer repeat mode set from the entry. `SpotifyRendererTest`: the context start sends the remembered
shuffle/repeat; list mode's repeat-all re-send at the end and repeat-one; `SpotifyTrackerTest`: "ours" with a shuffled
window. `SonosControllerTest`: wrap and re-play. `FitTest`: the row with two side buttons at 320 dp. `SourceScanTest`:
no new string says "Spotify".

## 3. Device checks

Emulator with the fakes (§6h Sonos, §6l Spotify) and own music: shuffle on/off, repeat all wraps, repeat one repeats,
both remembered per entry across a restart; the flip hidden while shuffled; a podcast shows the skip buttons instead.
