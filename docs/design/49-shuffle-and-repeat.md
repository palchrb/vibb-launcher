# 49 - Shuffle and repeat in the player's control row (proposal)

Asked 2026-10-20 ("en spesifikk shuffle knapp og repeat knapp ved siden av next/prev ... toggle for shuffle av og på
- og toggle for ikke repeat, repeat all eller repeat 1 sang"). Music app only. Builds on `logic/Queue.kt`
(`canShuffle`, `shuffledWithFirst`), the `shuffle` Room table (`ShuffleRow(entryKey, on)`), design 39's per-entry
flips (`order_flips`, the Room-5 pattern), `PlayerScreen`'s control row (`Fit.kt`: the two side slots of `row.side`
dp, today a shuffle button or a spacer), the renderers (`LocalRenderer`, `SonosController`, `SpotifyRenderer`).
Design 50 ships in the same round and shares §1.1's predicate.

## 0. Today

Shuffle exists for own files that don't resume (`canShuffle = source == OWN && !entry.resume`, `Queue.kt:18`),
toggled by `Commands.SHUFFLE`/`THEN_SHUFFLE` and remembered per entry in `shuffle`; the button sits in the left side
slot, the right slot is a spacer. There is no repeat anywhere; a queue plays to its natural end (`clear_state`).
`kindOf` maps own files to `Kind.OWN`, never `MUSIC`.

## 1. Decisions

1. **One predicate for 49 and 50, `controlSlots(entry)`** (pure, in `Queue.kt`, tested): `SHUFFLE_REPEAT` for
   `Kind.MUSIC` (Spotify music) and for `Kind.OWN` with `resume == false` (own music keeps the shuffle it has today);
   `SKIP` (design 50's 15 s / 30 s pair) for `Kind.PODCAST`, `Kind.AUDIOBOOK` and `Kind.OWN` with `resume == true`.
   A Spotify artist context gets `SHUFFLE_REPEAT` with shuffle hidden (Spotify picks there; repeat only - the left
   slot is a spacer, as today's non-shuffle rows have). `canShuffle`
   follows the predicate. **This overrides 26c's "No shuffle" for Spotify music** (`26c-spotify.md` §4.2; music/
   CLAUDE.md "No shuffle (every Spotify entry resumes)"): the user's call, 2026-10-20; both texts change in the same
   commit. Design 50 §1.1 refers to this predicate.
2. **The row**: left slot shuffle (`Icons.shuffle`, peach when on, the state as `stateDescription`), right slot repeat
   in three states off -> all -> one (`Icons.repeat`, `Icons.repeatOne` with the "1" badge from Material Symbols
   `repeat`/`repeat_one`; peach when not off). Both remembered per entry on the phone: `shuffle` as today, a new
   `repeat` Room table (`RepeatRow(entryKey, mode)`, Room 6 with an auto migration, shared with design 50's
   `favourites` because the two ship in one round; never synced or reported), read into `Snapshot` like the flips.
   vibb has no repeat and its Spotify shuffle is Spotify's own (`shuffle_context`): the on-phone list shuffle and the
   repeat are the user's deviations.
3. **Commands and the session**: new `Commands.REPEAT` and `THEN_REPEAT` (a restored queue) beside `SHUFFLE`/
   `THEN_SHUFFLE`. `KidPlayer` refuses ExoPlayer's `COMMAND_SET_REPEAT_MODE`/`COMMAND_SET_SHUFFLE_MODE` from
   controllers (the launcher, Bluetooth, the notification): only our commands change the mode, so Room stays the
   truth.
4. **The phone's player** (`LocalRenderer`): shuffle as today (our order, `shuffledWithFirst`); repeat all =
   `Player.REPEAT_MODE_ALL`, repeat one = `REPEAT_MODE_ONE`, so the natural end never clears the state (`queueEnded`/
   `clear_state` runs only when repeat is off; turning repeat off lets the next natural end clear as today). The
   `REASON_REPEAT` transition is emitted as `auto = false` (`LocalRenderer.kt:78-79`): no done mark, no overlap seek;
   that is intended. The now-playing bar and Home's card follow (the queue wraps). The phone's move-on past an
   unplayable item (`onItemChanged`, `onError`) wraps only with repeat-all and with a one-lap guard: a full lap with
   nothing playable pauses ("Ikke lastet ned") and never loops; a pure function in `Queue.kt` (`wrap` parameter + the
   lap count), tested.
5. **Spotify, both modes**: every start carries the entry's wanted `(shuffle, repeat)`. `SdkState.reordered`
   (`SdkState.kt:51`) and the tracker's `ShuffleOrRepeat` outcome (`SpotifyTracker.kt:385-392`) become "differs from
   wanted", not "on", and the renderer sends `PUT shuffle`/`repeat` (`needSettings`) whenever a state differs from what
   we last set. That also covers the next list-mode play after a context with them on (the account setting persists).
6. **Spotify, list mode**: shuffle shuffles our window on the phone through `tracker.reorder` (`SpotifyRenderer.reorder`, :479,
   already re-feeds the tracker and marks the window stale - its stale "no shuffle for Spotify, §4.2" comment goes; the
   service's shuffle path must call `renderer.reorder` for Spotify as the flip does); "ours" is not widened - only our window's expected
   current/next stay ours. Repeat-one = Spotify's `repeat=track` (the SDK repeats, no re-send). Repeat-all = a gated
   `NewWindow` from the first track when the last ends, decided BEFORE `Ended`/`clear_state` (the end rule checks the
   repeat mode first), with no audible jump, and only for a play the kid wants now (`playWhenReady && playAllowed`, and never after a pause by the kid, the sleep
   timer or a call - a `PauseCause` other than the natural end).
7. **Spotify, context mode**: Spotify's own API is set to the wanted values (`PUT /me/player/shuffle?state=`,
   `PUT /me/player/repeat?state=off|context|track`; `SpotifyApi.shuffle`/`repeat` exist). With repeat on, the end rules
   never read a repeating track as the context's end: the tracker's `Ended` and `contextEnd` (N1, `SpotifyTracker.kt:
   39-44, 463-472`) require repeat off, and with `repeat=track` the same uri again is a repeat, not an end; with repeat
   on only Autoplay evidence (another context) ends it. Design 46's queue view is re-read after a shuffle/repeat
   change.
8. **Shuffle with resume** (every Spotify entry resumes; a resuming own entry has no shuffle by §1.1): shuffle changes
   the ORDER only. The cover tap still resumes the current item at its position, then continues in the shuffled order
   (`coverTapPlan` keeps the current item first: `shuffledWithFirst`), defined in `Queue.kt` with a `QueueTest`.
9. **Sonos**: shuffle as today's order rule in `SonosController` (vibb's). After STOPPED at the end the controller goes
   through `advance()` -> `playIndex(next, 0.0, ..., ADVANCE)` behind `mayPlay()` (`SonosController.kt:1099-1125`):
   repeat all is `next = 0` there instead of `endSession`/stop; repeat one is the same index through the same path (a
   new URI set + Play). The bookmark's regression guard accepts that 0 as a deliberate start.
10. **Interaction with the flip** (design 39): the flip reverses the order; shuffle ignores it; repeat applies after
    either. The flip button stays hidden while shuffled (unchanged).
11. **Look**: Spotify's app for the two glyphs' meaning, vibb's player for everything else; no animation; the "1"
    badge is part of the glyph.

## 2. Tests (required)

`QueueTest`: `controlSlots` by kind and resume (own music keeps shuffle), the repeat modes' wrap rules and the
one-lap guard, shuffle with resume (current item first), the flip interaction. `OrderFlipTest` unchanged. Room: the
auto migration 5 -> 6 (schema in `app/schemas/`). `KidPlayerTest`: both Player commands refused. `LocalRenderer`:
the ExoPlayer repeat mode set from the entry. `SpotifyRendererTest`: every start sends the wanted shuffle/repeat
and a differing state is corrected; list mode's repeat-all `NewWindow` before `Ended`, repeat-one as `repeat=track`;
`SpotifyTrackerTest`: "ours" stays exact with a shuffled window (`reorder`), a repeat-track loop is no end (with the
fake's `--repeat track|context`). `SonosControllerTest`: wrap and re-play. `play_request.json` gets the on/context/
track shapes (the fake checks them, `fake-spotify.py:530, 644`). `FitTest`: the row with two side buttons at 320 dp.
`SourceScanTest`: no new string says "Spotify".

## 3. Device checks

Emulator with the fakes (§6h Sonos, §6l Spotify) and own music: shuffle on/off, repeat all wraps, repeat one repeats,
both remembered per entry across a restart; the flip hidden while shuffled; a Spotify playlist shuffled keeps the
cover tap's current item first; a podcast shows the skip buttons instead.

## Revision (2026-10-20, after qa-music-round-design.md)

- 49-1: §1.1 one shared `controlSlots` predicate (own music keeps shuffle; the user's override of 26c).
- 49-2: §1.5 wanted shuffle/repeat on every Spotify start; the tracker outcome is "differs from wanted".
- 49-3: §1.6 no widening of "ours"; list shuffle through `tracker.reorder`.
- 49-4: §1.8 shuffle with resume changes the order only; current item first.
- 49-5: §1.6 repeat-one = `repeat=track`; repeat-all = gated `NewWindow` before `Ended`.
- 49-6: §1.7 the end rules never read a repeating track as the end.
- 49-7: §1.4 the move-on wraps only with repeat-all and a one-lap guard; `REASON_REPEAT` stated.
- 49-8: §1.3 `REPEAT`/`THEN_REPEAT`; `KidPlayer` refuses the Player's set-mode commands.
- 49-9: §1.9 Sonos wrap and re-play through `advance()`.
- 49-10: §1.2 deviations from vibb marked; §2 `play_request.json` shapes.
- 49-11: §1.7 the "playlist/album context" wording removed; Room 6 shared with 50 (§1.2).
- Interactions: §1.7 the queue re-read after a shuffle/repeat change; the one predicate.

## Implementation status (2026-10-20, branch `music-r-a`)

Built as §1 says, in two commits: the Room 6 migration alone first (`repeat` and design 50's `favourites`, one
auto migration 5 -> 6, `schemas/6.json`, the DAO calls for both; design 50's implementer takes it), then the behaviour.

- **The predicate** (`logic/Queue.kt`): `ControlSlots`/`controlSlots(entry)` by `kindOf` and `resume`; `canShuffle` =
  `SHUFFLE_REPEAT` and not an artist context (`contextResumes`); `canRepeat` = `SHUFFLE_REPEAT`. `Repeat` (OFF/ALL/ONE,
  `next()`, the stored word, `of()` - an unknown word is off), `advanceIndex` (a natural end: off ends, all wraps, one
  again) and `moveOnIndex` (the move-on: wraps only with all, one lap, never back to the failed item). `coverTapPlan`
  with shuffle and resume: the bookmarked item first at its place, the rest a fresh permutation (none bookmarked: all
  of it from 0); `coverTapStart` shows that item, or none for a permutation.
- **Room and the snapshot**: `RepeatRow(entryKey, mode)` with the words `off`/`all`/`one`; `Snapshot.repeat` (entry key
  -> mode, only those not off) and `repeatOf(entry)` (off where the entry has no repeat to offer); `Store` reads it with
  the shuffle and the flips.
- **Commands**: `Commands.REPEAT` and `THEN_REPEAT` (a restored queue), the service's `toggleRepeat` (off -> all -> one,
  the row written, the renderer told, nothing started); `PlayerHub.State.repeat`. `KidPlayer` takes
  `COMMAND_SET_REPEAT_MODE`/`COMMAND_SET_SHUFFLE_MODE` out of the available commands, answers `isCommandAvailable`
  false for them, and `setRepeatMode`/`setShuffleModeEnabled` do nothing.
- **The renderers** get `Renderer.setModes(shuffled, repeat)`, called after each of the three `active = a` (a start, the
  playback resumption, a room's adopt) and at each toggle. The phone: `exo.repeatMode = exoRepeatMode(repeat)`; the
  service's two move-ons (`onItemChanged`'s and `onError`'s) go through `moveOnFrom` -> `moveOnIndex`. A room:
  `SonosController.repeat`, its `advance()` takes `advanceIndex` (the same gated `playIndex`, `deliberate = true` for a
  wrap or a replay so the regression guard takes the 0; the same item again sets no auto flag, so no done mark).
  Spotify: `SdkState.differs(wanted)` replaces `reordered`; `SpotifyModes` + `spotifyModes(context, shuffled, repeat)`
  (list mode: shuffle off, repeat one `track`, else off; a context: its own shuffle, `context`/`track`);
  `SpotifyApi.start(settings: SpotifyModes?)` sends exactly the wanted ones; the renderer keeps `lastSet` (null for a
  new device or after a differing state), so `needSettings` = "not known to be the wanted ones". The tracker's
  `setModes(wanted, wrap)`: a differing state is `ShuffleOrRepeat`; list mode's repeat all is `NewWindow(0, wrap =
  true)` at the list's end before `Ended` (the renderer reads it as the natural advance - the last item done - and
  plays only when the kid wants it now); with Spotify's repeat on a track that ran out and sits paused is
  `repeatHeld` (a look; still paused 2 s later: list mode plays the track again itself, a context shows paused), never
  the end; a context's Autoplay evidence still ends it.
- **The row** (`PlayerScreen`): shuffle left (as before, now with a state description "På"/"Av"), repeat right
  (`Icons.repeat`/`Icons.repeatOne`, peach when not off, `selected` and "Av"/"Alle"/"Én sang"); the slots stay
  spacers where `controlSlots` says SKIP - design 50's pair goes there.
- **Fixtures and the fake**: `play_request.json` has `shuffle_on`, `repeat_context` and `repeat_track`; the fake
  accepts only the states the fixture's shapes name (`setting_states`) and its selftest sends the three.
- **Tests** (942 in the music app, 19 new): `QueueTest`'s `d49` (the slots by kind and resume, shuffle and repeat by
  entry, the repeat words, the natural end, the move-on's wrap and one lap, shuffle with resume, the flip),
  `Room6Test`, `KidPlayerTest` (the refused commands, the modes after every `active = a`, `exoRepeatMode`,
  `repeatOf`), `SpotifyRendererTest`'s `d49` (the wanted settings at a start that needs them and not at a resume, a
  change while playing set at once from the current place, off -> all sends nothing, a state changed elsewhere
  corrected, a playlist context's `true`/`context`, repeat all's new window before `Ended` and not after the kid's
  pause), `SpotifyTrackerTest`'s `d49` (differs from wanted, the repeat-track loop and its stop, repeat all's wrap and
  Autoplay, a shuffled window's "ours", a context with repeat), `SonosControllerTest`'s `d49` (wrap and replay),
  `FitTest` (both side buttons at 320 dp), `SpotifyApiTest` (the three new shapes). `SourceScanTest` unchanged and
  green: no new string says the word.

**Deviations and choices** (the design QA file `docs/review/qa-music-round-design.md` was not in the tree; the
Revision list above was the reference):

1. **The glyphs are stroked, not Material Symbols** (§1.2 named `repeat`/`repeat_one`): the control row's icons are
   the mockup's stroked set (`Icons.shuffle` beside them), and a filled Material glyph would stand out there - the
   reason `Icons.roomSpeaker` gives. `Icons.repeat`/`repeatOne` are drawn in that set (two arrows in a loop, the "1"
   inside). Swap them for the Material Symbols through `scripts/material-symbols.sh` if the user wants those.
2. **"Every start carries the wanted shuffle/repeat"** (§1.5) is read as: the start knows them and sends them whenever
   they aren't known to be set - a new device, after the kid changed them, after a state said they differ - not two
   extra Web API calls before every play (the account setting persists; a change elsewhere shows in the next state and
   is corrected). That also covers the next list-mode play after a context with them on.
3. **A change while Spotify plays** goes out at once as a fresh start at the current place with the settings first
   (coalesced like a step, the flip's path: `positionMs()`, never from 0:00) - only when Spotify's side changes (repeat
   one in list mode, a context's shuffle or repeat); off <-> all in list mode is ours and sends nothing. Paused, the
   next play is a fresh start.
4. **A context with repeat on that Spotify leaves paused** (a stop under its repeat, confirmed by the 2 s look) shows
   paused, with no end and no `clear_state` - §1.7 says only Autoplay evidence ends it; list mode's repeat one in the
   same case plays the track again itself (gated).
5. **`KidPlayerTest`** reads the service's source (`KidPlayer` is an inner class of the service, as `GuardsTest` reads
   it); the LocalRenderer check is the pure `exoRepeatMode` plus the source line that sets it.
6. **Design 46's queue view** is not built (§0b), so §1.7's "re-read after a shuffle/repeat change" has nothing to
   re-read yet.

**Device checks owed** (§3, emulator with the fakes and own music): shuffle on/off and repeat all/one on own music
(the wrap at the queue's end, the same song again, both remembered per entry across a restart; the notification's and
a Bluetooth controller's repeat/shuffle do nothing), the flip hidden while shuffled; a Sonos room (§6h) wrapping and
replaying; Spotify (§6l, the fake's `--repeat track|context` and `--shuffle`): a shuffled playlist keeps the cover
tap's current item first, repeat one loops, repeat all starts the list again, a playlist context with Spotify's own
shuffle and repeat, a change while playing without an audible jump back; a podcast shows no shuffle/repeat (design
50's skip pair). On the Jelly Star with the real SDK (26c's QA checklist item 21): the real `repeat_mode` states around
a track repeat and a context's wrap, which `repeatHeld` and the tracker's end rules assume.
