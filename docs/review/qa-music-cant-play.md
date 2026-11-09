# Code QA: "Kan ikke spilles nå" on the player's cover (mockup D)

Branch `music-cant-play` (fa3834f8, eb7a0361, 2c636c00 on 1a41ecab), reviewed detached on 2026-11-09. Scope:
`ui/CantPlay.kt`, `PlayerScreen.kt`, `EpisodeCarousel.kt`, `Widgets.kt`, both `strings.xml`, `CantPlayTest`,
`SourceScanTest`, `FitTest`, `docs/notes/music/spotify.md`, `docs/testing/emulator.md`.

Counts: C 0, H 0, M 3, L 5, N 4.

Method: read the diff and the code it touches (`SpotifyRenderer.begin/fail`, `PlayerModel.RendererState`,
`MusicRoot`, the pager). Text fitting was computed from the shipped Nunito files (`res/font/nunito_*.ttf`, PIL
advances, line height 1.364 em, greedy word wrap, `maxLines = 2`) - not seen on a screen; the emulator was in use by
another session, so nothing was drawn. Test result: see the last section.

What holds up (checked, no finding): the split is one exhaustive `when`, so a new `RendererState` fails the build until
placed; the room's four plus `SPOTIFY_ELSEWHERE`/`SPOTIFY_STOPPED` give null and keep today's line; `LocalRenderer` and
`SonosRenderer` report null or `remote` states only, so local, podcast and room entries are never "can't play"; the
Spotify states only come from `SpotifyRenderer`, which the service swaps out on a mixed queue, so a local item after a
refused Spotify item is not greyed; no string says the source (`cant_play_*` are plain, `SourceScanTest` pins nb); no red
anywhere (peach disc at 22 %, white and `tileFg` text, over a cover of at most level 0.324, so contrast is high); the
colour matrix is the CSS `grayscale/brightness/contrast` chain (test checks it against the level function, and the CSS
maths is right: 0.8 c + 0.1); `coverGreyFilter` is a lazy singleton and `CantPlayNotice` is a plain data class of
`Int`/`Int?`/`Boolean`, so passing it down adds no recomposition beyond what `PlayerArt` already did (it takes `now`,
which changes every tick anyway); the neighbours in the carousel keep their colours because the check is
`carouselIndex(page, n) == now.index`.

## C - none

## H - none

## M - medium

### M1 - The notice truncates a key word at font scale 1.3, and is crowded at 150 dp

`CantPlay.kt:117` (6 dp side padding), `:128-129` (`maxLines = 2`, sp sizes 15 and 12).

The text is in `sp`, so at 1.3 the 15 sp title is 19.5 dp and the 12 sp help 15.6 dp, in 138 dp of width. From the
fonts (inner height 134 dp; the cover itself 150 dp):

| 150 dp cover | 1.0 | 1.3 |
|---|---|---|
| nb "Kan ikke spilles nå" + "Spør en voksen om hjelp" | 1 + 1 lines, 85 dp | 2 + 2 lines, 144 dp |
| en "Can't play right now" + "Ask a grown-up for help" | 2 + 1, 105 dp | 2 + 2, 144 dp |
| nb "Lydboka trenger lyttetid" + help | 2 + 1, 105 dp | **3 + 2 lines: the title is cut to "Lydboka / trenger…" and "lyttetid" is gone** |
| en "The book needs listening time" + help | 2 + 1 | **3 + 2: cut after "listening"** |
| nb "Virker ikke på telefonen" + help | 2 + 1 | 2 + 2, 144 dp |
| en "Not available right now" (no help) | 2, 85 dp | 2, 97 dp |

Scenario: the kid's phone is at font scale 1.3 (the scale the task names), the audiobook has no listening time. The
cover shows "Lydboka trenger…" and the one word that says what is wrong is cut by the ellipsis (TalkBack still reads
the whole description, so it is a sighted-kid problem). The 144 dp rows (the account, Widevine) exceed the 134 dp
inner height by 10 dp: the column overflows its padding and sits 3 dp from the cover's rounded edge on both sides; the
`Column` has no clip of its own, the cover's `clip(RoundedCornerShape(26))` is what stops it. It does not clip at 1.3,
but one more step does (1.5: 158 dp, over the 150 dp cover; the title cuts at 2 lines, the disc and help are clipped by
the corners). The KDoc's "fit one line each" is true at 1.0 for nb only; the en title already wraps at 1.0.

Fix: the repo's own precedent for a fixed box is dp text (`SKIP_NUMBER_DP.dp.toSp()`, FitTest "dp, never the font
scale's sp"): set the overlay's text in dp-derived sp, and let the title go to 3 lines. Or drop the disc (and then the
help) when the box is too short (`BoxWithConstraints`, a rule in `Fit.kt` with a `FitTest` row).

### M2 - Smaller covers have no fallback, and the line under the title is gone

`CantPlay.kt:112-130`, `EpisodeCarousel.kt:79-83`, `PlayerScreen.kt:150`.

The overlay is one fixed size (34 dp disc, two text lines, 142-195 dp tall) whatever the cover is, but the cover is
`min(224, room)` and the carousel only gives way below `PLAYER_ART_MIN_DP = 96`; the single cover has no floor. A
large font or display size is exactly what shrinks the cover (FitTest, "a large font or display size"). From the same
measurement: at a 120 dp cover the account's notice is 122 dp at 1.0 and 144 dp at 1.3 against 104 dp inside; at 96 dp
even "Needs Wi-Fi" is 85 dp against 80 dp. Because the line under the entry name is removed whenever a notice exists
(`PlayerScreen.kt:150`, `takeIf { notice == null }`), nothing else on screen says why it will not play: the disc and
help line are clipped, the title is cut in the middle.

Scenario: font scale 2.0 on the Jelly Star, a Spotify entry with no Premium. The cover is small; the kid sees a dark
square with part of a bold word, play is greyed, and the line that used to say "Spør en voksen" is gone.

Fix: the overlay decides from its own box what to show (full / title only / "!" only), and the entry-name line comes
back when the notice cannot show its title in full (one rule, in `Fit.kt`, tested at 96, 120, 150 and 224 dp, at 1.0,
1.3 and 2.0, with the font advances the way `FitTest` already holds them).

### M3 - A tap on a greyed play (or a skip from a refused item) turns the cover back to colour for the length of the attempt

`SpotifyRenderer.kt:1041` (`begin`: `line = null; ... changed()`), `:1299` (`fail`: `line = p.line`);
`CantPlay.kt:56-72`, `PlayerScreen.kt:213-219`.

Every start clears the renderer's line first and sets it again only when the start fails. So the notice is a function
of "the last attempt failed", not of "the item can't play". Account refused (`SPOTIFY_ASK_ADULT`): the kid taps the
greyed play, `begin` clears the line, the cover returns to colour, the notice disappears, play turns peach with a pause
glyph (`pwr = true`), the token ask and play call run (up to `SPOTIFY_START_BUDGET_MS` = 25 s; sub-second to a few
seconds when the answer is a plain refusal), `fail` sets the line, and everything greys again. The same for next and
previous. The mockup's idea is a calm, stable greyed cover; this is a flip for every tap, which is the "blinking" the
approval ruled out. (Network lines are not affected: `refuse(it)` runs before `line = null`.)

The task's question 4 is the same fact from the other side: keeping greyed play a working button is right (an adult
fixes the account in the PWA and nothing pushes that to the phone, so the kid's tap is the retry; a disabled button
would lock a fixed phone out), but "greyed" tells the kid the button is dead and a tap then flashes. It needs one of: a
short hold (keep the previous notice shown, still greyed, while a start that follows a refusal of the same kind is
`connecting`: `PlayerHub` has `connecting`), or the cover staying grey and the "Kobler til…" row (`timeRow`) saying that
something is happening. The note in `spotify.md` ("Greyed is a look only") should then name it.

## L - low

### L1 - Carousel: the live region lands on the merged play button and re-reads its label

`CantPlay.kt:117-120`, `EpisodeCarousel.kt:208-209`.

In the carousel the page is `button(label)` = clickable, which merges its children. The overlay's `liveRegion` and
`contentDescription` therefore merge into the page's node: TalkBack's live node is "Pause <title>, Can't play right
now. Ask a grown-up for help", and any change of that node (the label flips between "Play X" and "Pause X" when
`now.playing` changes, which `begin` does on each tap, see M3) is announced as a whole, not just the notice. In the
single cover (not clickable) the overlay is its own live node and says only the notice. Not wrong, but different in
the two layouts, and the note in `spotify.md` states the merge as intended. Suggestion: set `liveRegion` on the page's
`semantics` only when `refused != null`, with the notice as `stateDescription` (as play already does), and keep the
overlay's `clearAndSetSemantics` description for the single cover only.

### L2 - With two queue items the pager composes the loaded page twice

`EpisodeCarousel.kt:183` with `beyondViewportPageCount = 1` (`:174`) and `carouselPageCount`.

With n = 2 the pages p-2 and p+2 are the same item as p. They are off screen (alpha 0 beyond the neighbours), but the
test is on the index, so each carries an overlay, and a second composed live region. TalkBack ignores nodes outside the
viewport, so this is cosmetic, but the rule is "the page the pager is on", not "every page of that item"; testing
`page == pager.currentPage` (or `settledPage`) says what the comment says.

### L3 - `SPOTIFY_NOT_ON_PHONE` says "Ask a grown-up for help" and leaves play lit

`CantPlay.kt:68`. The note justifies lit play with "a new web player for Widevine", but the line itself tells the kid
the adult must act, and a tap on the same phone cannot gain Widevine; only an app or system update can. If play stays
lit by design, drop the help line for it; if the help line stays, grey play like `SPOTIFY_ASK_ADULT`. As written the
two signals disagree, and "greyed only when a tap can't help" does not describe the table (a tap cannot help here
either).

### L4 - Tests check source text where a function would do

`CantPlayTest.kt:57-68` (`the player shows the notice in the line's place`), `FitTest.kt:241`, `:325`.

The third `CantPlayTest` greps `PlayerScreen.kt` and `EpisodeCarousel.kt` for exact substrings
(`"notice?.takeIf { carouselIndex(page, n) == now.index }"`). It passes if the expression is in a comment, and fails on a
rename that keeps the behaviour. The two rules it protects are small pure functions waiting to be extracted: "the page
that shows the notice" (`page`, `n`, `now.index` -> Boolean, which would also fix L2) and "is play off" (`notice` ->
Boolean), and then they are tested by value like the others in the file. The `FitTest` edits are string edits for the
same reason. The first two tests (the table, the colour maths) do test behaviour.

### L5 - The new strings are not in the fit tests

`FitTest.kt:249-257` (`spotifyLines`, the width table of every `renderer_spotify_*` line). `cant_play_now` and
`cant_play_ask_adult` are not measured, so nothing pins that "Kan ikke spilles nå" (132 dp) and "Spør en voksen om
hjelp" (137 dp) fit 138 dp at 1.0, which the KDoc claims from an emulator check: a one-point change in padding or the
font breaks that silently. Add them to a cover-fit test with M1/M2's rule, with the width in 138 dp as the bound and the
height at 1.3.

## N - notes

- N1 - `CantPlay.kt:127`: `Spacer(Modifier.height(2.dp))` inside a `spacedBy(4.dp)` column makes a 10 dp gap, not the
  4 dp the arrangement suggests; fine as drawn (the emulator check), but the 142/144 dp heights above include it.
- N2 - `Widgets.kt:259`: `shade` is a lambda allocated per `Cover` composition (the list rows call it too). Trivial;
  `if (greyed) coverGrey(c) else c` inline, or `remember(greyed)`.
- N3 - `docs/notes/music/spotify.md`: says "TalkBack's state the notice's title" for play; for `SPOTIFY_NO_LISTENING_TIME`
  that makes play read "Play, button, The book needs listening time", which is right, but the same sentence also says
  the two lines merge into the carousel page's play label (L1): say which of the two layouts is meant.
- N4 - `PlayerScreen.kt:213-215`: `off`/`offState` are computed in the controls row on every recomposition (the position
  ticks); `offState` is read only when `off != null`, so the cost is nil. Noted because recomposition cost was asked.

## Answers to the five checks

1. State split and wiring: correct and exhaustive; the player, the single cover and the carousel read the same
   `notice`; the carousel greys the loaded index only. TalkBack: see L1, L2.
2. Regressions: none found for the rooms, `SPOTIFY_ELSEWHERE`/`STOPPED`, local and podcast entries. The one new
   behaviour with a cost is M3 (the notice follows the last attempt). Recomposition: no new cost.
3. Text fitting: it does not clip at 150 dp x 1.3 (144 of 150 dp), but it is crowded and two long titles lose a word
   (M1); below 150 dp it clips and the fallback line is gone (M2).
4. Greyed play: keep it a button (the retry is the only way to learn the adult fixed it), but then do not let a retry
   flip the whole cover (M3); L3 for the one state where the table disagrees with itself.
5. Tests: the table and the colour tests are behavioural; the wiring test is a source grep (L4); no test measures the
   new strings or the notice's height (L5, M1, M2).

## Test run

`./gradlew --no-daemon --max-workers=2 testDebugUnitTest --tests CantPlayTest --tests SourceScanTest --tests FitTest
-PwarningsAsErrors=true` under `flock ~/build.lock`, run only after the emulator had gone: BUILD SUCCESSFUL (the
branch compiles with warnings as errors, and the three classes pass). The full suite was not run, as instructed.

## Fix round (2026-11-09, on `music-cant-play`)

- **M1** - fixed. The notice's text is dp (`NOTICE_TITLE_DP` 15, `NOTICE_HELP_DP` 12, the "!" 17; `dp.toSp()` as
  `SKIP_NUMBER_DP`), so the font scale no longer changes it; the title may take three lines, the help two. English
  `cant_play_now` is "Can't play now" (106 dp: one line from 120 dp). FitTest measures every notice in nb and en.
- **M2** - fixed. The overlay measures its words in its own box (`rememberTextMeasurer`, `BoxWithConstraints`) and
  `noticeFit` decides: all of it, else without the disc, else the title (with the disc when no help can show), else the
  "!" alone. It reports the answer up (`PlayerArt`'s `onNoticeFit`); `headerLine` brings the line under the entry name
  back when the title is gone, or the help line is gone and the line says more than the title ("Spør en voksen" under
  "Kan ikke spilles nå"). FitTest: at 224 and 150 dp everything shows, at 120 the title and help, at 96 what's dropped is
  said by the line (nb "Kan ikke spilles nå" at 96 dp: disc + title, "Spør en voksen" above; en "The book needs
  listening time" at 96 dp: the "!" and the line). The font scale is not a test axis: the text is dp, only the cover's
  size moves it.
- **M3** - fixed. `MusicRoot` holds the last refusal over its retry (`holdRefusal`: kept while `PlayerHub.State.connecting`
  in the same run, `entryId`; ended by a real playing state, another line or another run) and gives the player
  `shownState`; the cover stays greyed with its notice and play stays greyed (still a button) through the attempt. The
  time row reads the live state, so "Kobler til…" shows under the greyed cover meanwhile. Next and previous in the run
  keep it too (the account's or the book's refusal is the run's; a start that plays ends it). CantPlayTest walks the
  sequence.
- **L1** - fixed. On a carousel page the overlay is silent (`speak = false`) and the page carries the notice as its
  `stateDescription` and a polite live region; its label stays "Spill X"/"Pause X". The single cover keeps its own
  node (description + live region).
- **L2** - fixed. `noticePage(page, settledPage, n, index)`: the page the pager rests on, while it shows the loaded item;
  with two items the second composed page of that item has no overlay, and a neighbour mid-swipe never gets it.
- **L3** - left as it is, as the coordinator asked (with the user).
- **L4** - fixed. The source-grep test is gone; `playOff`, `noticePage`, `noticeFit`, `headerLine`, `holdRefusal` and
  `shownState` are pure and tested by value. FitTest's two existing source pins follow the code (its own pattern).
- **L5** - fixed: FitTest `the cover's notice fits ...` checks `cant_play_now` and `cant_play_ask_adult` are the measured
  strings in both languages and runs every notice at four sizes.
- **N1** - fixed: no Spacer; one 4 dp gap between the parts. **N2** - fixed: the gradient is greyed inline, no lambda.
  **N3** - fixed in `spotify.md` (which layout says what). **N4** - `off` is `playOff(notice)` and the state string is
  read only when play is off.
