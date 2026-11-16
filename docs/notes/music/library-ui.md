# Music notes: news, row fit, the library header, the quiet look

Moved from music/CLAUDE.md on 2026-11-01.

## Rules that are easy to break (continued): news, fit and the look of the library

- A new entry is news (design 29, not vibb): a key the phone never knew, once a play has been stored, puts one item
  (the first that can play; Storytel: book 1) in Nytt at its first listing, with a 24 h settle; until that first
  play every listing is only acknowledged (the baseline is open: no news at all); known keys only grow;
  an id that changes key is reset like a removed entry; the sync runs under `Sync.runLock`. The rules are the pure
  `newsBefore`/`newsAfter` in `logic/NewItems.kt` (`NewItemsTest`'s `d29` tests); `Sync` only runs them in
  `MusicDao.newsBeforeTx` (before the inbox loop) and `newsAfterTx` (after it, the tray read inside the
  transaction), and every other tray write is one statement or one transaction (`deleteNews`, `clearNews`,
  `pruneNews`). A `no_audio` on a tray item asks for a sync (`repickWanted`), which re-picks a new entry's item.
- Rows that must fit 320 dp (288 inside the sides) follow `ui/Fit.kt` (`FitTest` at 320/360/411 dp): the player's
  control row (v12's sizes where 332 dp fit, else mockup v15's 44/52/72/52/44 with 6 dp gaps, and below 288 dp - the
  270 dp class, screen-size fix S2 - `CONTROLS_NARROW`'s 44/48/56/48/44 with 4 dp gaps, reaching up to 12 dp into the
  screen's sides, `controlBleed`), the bottom bar's flex widths (S7: the output's name keeps its whole width when Liste
  and Sov have room to give it that, else it gives way at its share as before, `shrinkerMin`), the category tiles, the carousel, the bar's room label (a four-member group, design 53), the Sonos state
  lines and "Spill av på"'s group block and "+" rows (design 53: `joinRowTextDp`, `memberNameDp`, the sliders).
  `RoundButton` is `requiredSize`: give it the room, it never shrinks.
- **The player's height** (screen-size finding S10, 2026-11-14): the cover (or the episode carousel), the title and its
  number line, the seek line and the control row are one group between the header and the bottom bar, centred in that
  room (`Arrangement.spacedBy(12.dp, Alignment.CenterVertically)`): what is left on a tall phone goes above and below
  the group, never into one hole between the cover and its title. The cover takes what the rest of the group leaves
  (`weight(1f, fill = false)`; the box is only as tall as the picture) and grows with the width: `playerArtMax` is the
  width less the carousel's two 8 dp gaps and 36 dp peeks (so the neighbours look as on the Jelly Star), never under
  224 dp (320 dp phones and below keep their cover) nor over 320; a cover without art scales its glyph above 224
  (`playerGlyph`). The Jelly Star and the narrow class are room-limited as before.
- **The library's height** (design 34 P10 b, QA L1): the header, the category tiles and the now-playing bar keep their
  own height; the carousel fills the weighted slot between them (`BoxWithConstraints`). Its cover is
  `libraryFit(slotPx, linesPx, reservePx, density)`, in whole pixels with every fixed part rounded as Compose rounds a
  Dp (`libraryFixedPx`; at density 1.5 a float-dp model overflowed by 2 px) - min(180, the slot less the title and
  hint lines measured with a `TextMeasurer` at the font scale, less the fixed rest), floor `LIBRARY_COVER_MIN_DP` 64
  (120 until the emulator round's N6: Android 16's status bar grows with the font scale; below 120 dp the cover drops
  its kind chip, `coverShowsKind`); below the floor the slot scrolls (the `verticalScroll` is applied only then: it clips, and would cut the cover's
  shadow), so the tiles never slide under the bar. Without the bar its room (`nowBarRoomPx`) is still kept out of the
  cover, so the cover never changes size when the bar comes or goes. The progress line's room is always kept (the cover never
  resizes on a turn). The pager, its side padding, the pull and the cover glyph all take that cover (`carouselSide`,
  `carouselPeek`, `carouselPull`, `carouselGlyph`); `coverDecodePx` decodes at the player's largest cover on this
  screen (`playerArtMax` of its width: 224 dp on a 320 dp phone, 291 on a 411 dp one; one decoded bitmap per hash for
  both screens). No fixed `180.dp`/`184.dp` in `LibraryScreen` (`FitTest`).
- **Quiet look** (design 34 P10 a, c): an empty library, Nytt and search show `EmptyState` - a 104 dp night disc with
  the headphones/bell/search glyph in peach (decorative, no description: QA L7), the headline and, for the library,
  "Mamma eller pappa legger til musikk, podkaster og bøker. De dukker opp her av seg selv."; no animation, no button.
  Soft shadows only: 12 dp under the centred cover (v12), P2's 2 dp under a neighbour (following the page offset),
  the now-playing bar and the disc (`EmptyStateTest`, `FitTest`). The kid screens are always dark.
