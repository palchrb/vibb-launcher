# Code QA: the evening weather card (`evening-card`, bb7233bc)

Reviewed 2026-11-04 against the approved mockup `kveldskort.html` (board 2), design 75 §14, PLAN's queue entry and
`git diff main...origin/evening-card`. Run: `cargo test weather` (28/0), launcher `testDebugUnitTest --tests '*Weather*'`
(green). Both `weather.json` copies are byte-identical (`cmp`), and the server's `the_contract` /
`the_contract_is_identical_to_the_launchers_copy` pass without `WEATHER_CONTRACT_WRITE`, so the written output matches
what the code computes. Not run: the emulator (the layout findings are from reading the XML and the bind code).

Result: 0 H / 1 M / 3 L / 3 nit.

## M

**M1. Half of the approved item is not built: the words.** PLAN.md line 88 (the approved entry) and the mockup's board 3 and
note 3 say the glance line's words "cover the rest of the evening first and tomorrow only when the evening is dry"
("Regn fra kl. 21", else "Regn i morgen"). The diff touches only the card. `weather::words` still switches to
tomorrow's `[07:00, 18:00)` window at 18:00 (`word_day`, `server/src/weather.rs:280`), design §14 does not mention the
words and does not say they were left out. Result at 19:04 with rain due at 21:00: the open card says "I kveld" with a
rain symbol, the closed line says nothing (or tomorrow's words). Either build it (server-side `words` for
`[now hour, midnight)` first, then tomorrow's when dry; the `from`/`today` forms exist, the contract's `words` rows gain
evening cases; the launcher needs nothing new) or write the deferral into §14 and keep the PLAN item open for it. The
user approved the mockup with that board in it.

## L

**L1. "I kveld" symbol looks past midnight.** `span` takes the symbol from the start hour's `next_6_hours`
(`weather.rs:481`). For `tonight` that window is `[start, start+6h)`, so at 19:00 it reaches 01:00 and at 23:30 it is the
23:00-05:00 outlook, while the mm are summed only to midnight. A rain symbol with no mm (hidden under 0.1) at 23:30
because rain starts at 03:00 reads wrong, and the "Morgen" column then repeats the same hours. Not reachable at 18:00
(exactly the old evening). Cheap fix: in `tonight`, when less than ~5 h remain use the start hour's `next_1_hours`
symbol (`Hour.sym`) instead of `sym6`, or always for starts after 18:00. Add a case to `tonight_runs_from_18_to_midnight`
(sym6 differing from sym at 23:00). The spec asked for "the same column rule", so this is a judgment call, but it is a
real wrong-looking case on the card's main time of use.

**L2. The foot can clip at larger fonts.** `weather_card_foot` is `maxLines=1`, weight 1, no ellipsize, beside a
wrap-content dark text (`view_lock_weather_card.xml`, foot row). "I morgen" adds 9 characters: "I morgen -12° / -5°"
(about 125 dp at 13 sp bold) beside "Mørkt hele dagen" or "Lyst hele natta" (about 100 dp) plus the 8 dp margin is near
the 300 dp content width of a 360 dp phone at font 1.3, and over it at 1.5 and above; the text is cut hard. Allow two
lines (`maxLines=2`) or ellipsize, and check at font 1.0/1.3/2.0 on the emulator (the owed §6x checks).

**L3. A missing middle part gives a 1 : 1 evening card.** The group weights follow the shown column count
(`WeatherLockPanel.kt:391-401`): with "I kveld" plus only one tomorrow column (a period without hours is dropped by
`weatherCard`'s `mapIndexedNotNull` when the day lacks that part, not when it is empty) the groups are 1 : 1, the lone
column is as wide as "I kveld" and the heading "I morgen" sits over one column. Harmless but the 1fr | 2fr of the mockup
is lost. Low value to fix (keep weights fixed 1 : 2 on the evening card and let the missing column be empty); at least
note it. `periods()` always returns all three parts, so on a current server this only happens with an unknown part.

## nit

- **N1.** Group 0 is `wrap_content` high and carries the raised-tone background, group 1 can be taller until the
  `card.root.post` pass sets the same `minLines` (`WeatherLockPanel.kt:403-407`): for one frame the highlight can be
  shorter than the divider. `layout_height="match_parent"` on `weather_group_0` (as the mockup's grid stretch) removes it.
- **N2.** The divider uses `kid_divider` (#1AFFFFFF, 10 %); the mockup's line is 12 %, and "den samme tydelige visuelle
  delingen" was the user's point. Fine as a token; check it reads on the emulator before leaving it.
- **N3.** `spokenCard` (TalkBack) has no unit test; the evening branch ("I kveld: ... I morgen. Morgen: ...") is only
  visible on a device. Reads right in the code (nb and en, sentences joined by `sentence()`, mm and degrees as the
  columns); owed to the emulator/TalkBack pass.

## Checked, no defect

- **`tonight` boundaries (`weather.rs:496`)**: None before 18:00 local, from 18:00 the start is the local start of the
  UTC hour containing `at`, clamped to 18:00, the end is the next local midnight. 17:59, 18:00, 19:04, 23:30, midnight
  and no hours are in `tonight_runs_from_18_to_midnight`. The clamp only matters in half-hour zones (the hours then
  start at :30, the filter `s >= start` still picks the right ones). DST: the span is built from naive local times like
  `periods`, so a transition day behaves as the periods do; Oslo's changes (02:00-03:00) are outside 18-24.
- **Middle temperature**: `start + (end - start) / 2` gives exactly 09/15/21 for the three periods (so the periods'
  vectors still pass) and 21:30 at 19:00, 23:30 at 23:00 as §14 says.
- **Missing or stale hours**: an absent start hour falls back to the first stored hour inside (as the periods do, code
  QA M1); no hour inside, or neither symbol nor degrees, gives None; entries are still left out for an hour the forecast
  lacks. `hour_floor(at)` is the same instant the entry is keyed on, so entry i>0 (judged at its start) and entry 0
  (judged at `now`) agree on the start hour.
- **Rain summing**: the hours whose start is in `[start, midnight)`, `None` as 0, rounded to 0.1, the 18:00 rain
  (0.4 mm) correctly excluded at 19:04 in the test.
- **Contract**: both copies identical; `tonight: null` on every non-evening entry, the 18:00 entry in `answer` and
  `answer_words` carries it; the launcher test pins which entries carry it and that every `tonight` symbol has its drawable
  and name.
- **Compatibility**: `v` stays 1. An old launcher: `ServerJson` has `ignoreUnknownKeys = true`, it keeps the
  tomorrow-only card. A new launcher on an old server: `tonight` defaults to null, plain tomorrow card (tested). A card
  that is today's after all (zone change): `weatherCard` ignores `tonight` (`takeIf { tomorrow }`, tested). An unknown
  symbol keeps the degrees; neither symbol nor degrees falls back to the plain card (tested).
- **Layout against the mockup**: 1 : 2 weights (the divider and its 3 dp margins sit outside the weights, the same as the
  grid gap), "I kveld" once with an empty part slot (empty `TextView` keeps one line, and the `minLines` pass counts it
  with the others), the raised tone on the whole group including its heading, the divider, "I morgen" over Morgen |
  Ettermiddag, no title (`weather_card_tomorrow` gone when `tonight != null`), the foot "I morgen 1° / 7°" with tomorrow's
  dark text. On a plain card the heads and the line are GONE, group weights 1 / 2 give the three equal columns, the old
  per-column highlight is kept (`c.current && !isTonight`). The bind key includes the card model, so a new `tonight`
  rebinds.
- **i18n**: `weather_card_tonight` and `weather_low_high_tomorrow` in `values` and `values-nb` (the only catalogs that
  carry the weather strings); no hardcoded text; the layout takes both headings from resources.
- **Look**: no new colour, no new drawable, existing tokens (`kid_ink`, `kid_core_dim`, `kid_divider`, `bg_weather_now`).
- **Tests**: lean and on the boundaries; the one server test and one launcher test cover what a regression would break.
  Docs (design §14, the two area notes) match the code. PLAN.md was not touched by the build (not in scope here): its
  entry should move to "built, awaiting QA" and keep M1's words part visible.
