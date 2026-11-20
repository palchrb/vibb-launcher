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
  and Sov have room to give it that; a name cut anyway - a long Sonos room - takes all the rest once Liste and Sov
  have their content and 8 dp of air each, the air dropped below a 72 dp output, `barWidths`, 2026-11-20 - one line,
  ellipsized, its glyph kept, TalkBack reading "Spill av på: <name>"), the category tiles (`categoryRow`, 2026-11-20:
  a fifth each or the label's width, 4 dp apart, the usual circles where that fits - 44 dp up to a 320 dp screen, 48
  from 411, `categoryCircle`, grown by the width alone; else compact - 2 dp apart, 40 dp
  circles, 10 sp labels, each tile at least its label and 48 dp, the narrow ones raised to one width that fills the row;
  else, at a large font, the row scrolls snapping to a tile's start, a tile the edge cuts fades while it moves and is
  hidden at rest (`categoryTileAlpha`), a quiet chevron in the screen's side where more lie - a label is never shown
  cut), the carousel, the bar's room label (a four-member group, design 53), the Sonos state
  lines and "Spill av på"'s group block and "+" rows (design 53: `joinRowTextDp`, `memberNameDp`, the sliders).
  `RoundButton` is `requiredSize`: give it the room, it never shrinks.
- **The player's height** (screen-size finding S10, 2026-11-14): the cover (or the episode carousel), the title and its
  number line, the seek line and the control row are one group between the header and the bottom bar, centred in that
  room (`Arrangement.spacedBy(12.dp, Alignment.CenterVertically)`): what is left on a tall phone goes above and below
  the group, never into one hole between the cover and its title. The cover takes what the rest of the group leaves
  (`weight(1f, fill = false)`; the box is only as tall as the picture) and grows with the width: `playerArtMax` is the
  width less the carousel's two 8 dp gaps and 36 dp peeks (so the neighbours look as on the Jelly Star), never under
  224 dp (320 dp phones and below keep their cover) nor over 320; a cover without art scales its glyph above 224
  (`playerGlyph`). The Jelly Star and the narrow class are room-limited as before. The item's number line sits
  `PLAYER_NUMBER_GAP_DP` (8) under its title, the heart overlaid at its end at 44 dp with a 22 dp glyph (the Jelly
  Star, 2026-11-20; the list keeps 40/18); the title's marquee shows its tail and the next lap's start a third of the
  width apart (Compose's default spacing) - intended, not a paged row.
- **The library's height** (design 34 P10 b, QA L1): the header, the category tiles and the now-playing bar keep their
  own height; the carousel fills the weighted slot between them (`BoxWithConstraints`). Its cover is
  `libraryFit(slotPx, linesPx, reservePx, density, capDp)`, in whole pixels with every fixed part rounded as Compose rounds a
  Dp (`libraryFixedPx`; at density 1.5 a float-dp model overflowed by 2 px) - min(the width's cap, the slot less the title and
  hint lines measured with a `TextMeasurer` at the font scale, less the fixed rest, less `LIBRARY_AIR_DP` 4 above and
  below), floor `LIBRARY_COVER_MIN_DP` 64
  (120 until the emulator round's N6: Android 16's status bar grows with the font scale; below 120 dp the cover drops
  its kind chip, `coverShowsKind`); below the floor the slot scrolls (the `verticalScroll` is applied only then: it clips, and would cut the cover's
  shadow), so the tiles never slide under the bar. Without the bar its room (`nowBarRoomPx` of the bar this width would
  draw, its lines measured at its own sp) is still kept out of the cover, so the cover never changes size when the bar
  comes or goes. The carousel's progress line's room is always kept (the cover never resizes on a turn).
  The cap follows the width (the user, 2026-11-20, the approved mockup: "gjøre den spiller nå baren her litt høyere ...
  mye tomrom nå både over og under karusellen ... må være noe som skalerer bra også på større skjermer";
  `carouselCoverMax`): 68 % of the screen's width, no more than leaves each neighbour 24 dp of peek
  (`CAROUSEL_PEEK_MIN_DP`), never above the player's cover on that screen (`playerArtMax`, one decoded bitmap), never
  under v12's 180 (a phone under about 265 dp keeps it) - 217.6 dp on the Jelly Star, 279.5 on a 411 dp phone; the
  height that is there still limits it. When it does, the cover takes the slot less 4 dp of air above it and under the
  dots (on the Jelly Star 14 dp under the header's buttons, 12 over the category circles; the air gives way at the floor
  before the slot scrolls); the rest of a taller slot centres, as much above as below. With the bar on the Jelly Star
  (320 x 569 dp, no navigation bar) the cover is 215 dp at font 1.0, 197 at 1.3 (was 180); a 411 x 891 dp phone 279.5.
  **The now-playing bar** (`NowPlayingBar`, `nowBarSize` - grown by the width alone, 320 to 411 dp): a 52 dp cover
  (corner 12), the item's title at 15 sp ExtraBold and the entry's name at 12 sp, 2 dp apart, a 48 dp play button (glyph
  18), corner 20, 10 dp padding, 12 dp gaps - 72 dp tall on the Jelly Star; 56/14, 16 + 13 sp, 52/20, 22, 12, 14 - 80 dp
  from 411. Lines taller than its cover (a large font) keep 6 dp above and below (`NOW_BAR_PAD_MIN_DP`, a `heightIn(min)`
  around that padding), so at 2.0 it is about 7 dp taller than the old one. Its words are as before. A 3 dp peach line
  along its bottom edge (clipped to its corners) shows how far the item has come (`nowBarProgress` of `livePosition`, the
  player's own position, ticking only while the screen is up; none without a known length).
  The pager, its side padding, the pull and the cover glyph all take that cover (`carouselSide`,
  `carouselPeek`, `carouselPull`, `carouselGlyph`); `coverDecodePx` decodes at the player's largest cover on this
  screen (`playerArtMax` of its width: 224 dp on a 320 dp phone, 291 on a 411 dp one; one decoded bitmap per hash for
  both screens). No fixed `180.dp`/`184.dp` in `LibraryScreen` (`FitTest`). The room the cover at its cap leaves lets the
  title block breathe (`libraryBreath`, 2026-11-20: on the Jelly Star without a navigation bar the title, hint,
  progress line and dot sat 3 dp apart with 40-90 dp empty around them): up to two thirds of it, shared in proportion
  to the caps cover-title +8, title-hint +3, hint-progress +7, progress-dots +8 dp, each floored to whole pixels; the
  rest centres as before. A cover below its cap gets none (the room goes to the cover first); the favourites tile uses
  the same gaps, so nothing moves between the chips, and the bar's reserved room is out of it (N2).
- **Quiet look** (design 34 P10 a, c): an empty library, Nytt and search show `EmptyState` - a 104 dp night disc with
  the headphones/bell/search glyph in peach (decorative, no description: QA L7), the headline and, for the library,
  "Mamma eller pappa legger til musikk, podkaster og bøker. De dukker opp her av seg selv."; no animation, no button.
  Soft shadows only: 12 dp under the centred cover (v12), P2's 2 dp under a neighbour (following the page offset),
  the now-playing bar and the disc (`EmptyStateTest`, `FitTest`). The kid screens are always dark.
