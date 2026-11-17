# QA (code): the phone book's rows with glyphs in the line (variant D)

Branch `phonebook-text`, commits 42895459e (text only, variant A) and 64aa4adbe (glyphs, variant D), against `main`
(`git diff main...phonebook-text`). Launcher only. Reviewed 2026-11-17 from the source; no Gradle run here (CI run
44065682206 green).

## Summary

The change does what was asked in the main.

- **The pills are gone from the rows only.** `bindContact` binds `ContactBadge(0, 0)`, so both pills are `GONE` on
  every Kontakter row. Home's tiles and the sheet still call `bindContactBadge` with the real counts. Siste is untouched.
- **The line is built as asked.** `rowLine` gives one line, ellipsized at the end. The missed part comes first: the
  phone-x glyph (`ic_kid_missed_call`, the pill's own), then the time of the newest unseen missed call, or the count
  when there are several, in `kid_page_line_missed`. Then an em space. Then the chat glyph (`ic_kid_badge_message`)
  and the unread count in `kid_page_line_unread`. Either part can appear alone, and with neither the line is `GONE`.
  "Nødnummer" still wins.
- **The glyphs follow the text.** Each glyph is a mutated copy of the drawable, tinted with the same colour as its
  text. It is sized at 1.1 × `line.textSize`, which already includes the font scale (and Android 14's non-linear
  scaling). So at 1.0 the glyph is about 14 dp, and at 2.0 about 27 dp.
- **The time follows the phone's one clock format.** It comes from `MissedCallText.shortWhen`, a pure extraction from
  `short`: the time from `ClockFormat.formatTime` today, "i går" / "yesterday" for yesterday, and "30. nov." / "Nov 30"
  before that, all in the system zone. A clock face drops AM/PM by design, and P12's note lists "the missed-call text"
  among the faces. So "10:19" without a marker is the documented rule, not a slip. The day change is handled: the
  activity re-binds on `ACTION_DATE_CHANGED` and `ACTION_TIMEZONE_CHANGED`.
- **Recycling is clean.** Every full bind sets `line.text` and its visibility, and the line is rebuilt from scratch
  each time. The `PHOTO` payload path touches only the avatar. The emergency dialer row hides the line. So a recycled
  row can't keep a stale glyph.
- **TalkBack reads the words, not the glyphs.** The text column is `noHideDescendants`, and the row's
  `contentDescription` is `sentence`: "Mamma, Tapt anrop 10:19, 1 melding" in nb and "Mamma, Missed call 10:19,
  1 message" in en. For several calls it reads "2 tapte anrop 14:02". The new plurals `contact_unread_line` are there
  in nb and en.
- **Contrast is fine in both looks.** In Lys, the rust `#A84A22` is 5.1:1 on the ground and 5.7:1 on a tile; the teal
  `#347470` is 4.9:1 and 5.4:1. In Mørk, `#F08F66` is 8.2:1 and 7.2:1, and `#6FC7C0` is 9.8:1 and 8.6:1.
  `KidPaletteTest` pins all four pairs.
- **A missed call from a number with no contact never reaches Kontakter.** The rows are contacts only; such a call
  shows in Siste.
- **Android 14-17.** `ImageSpan.ALIGN_CENTER` is API 29, below minSdk 34. Nothing in the change depends on the
  Android version.
- **RTL.** The line is digits and neutrals, plus "i går" / "yesterday". In a forced-RTL layout it mirrors as a whole,
  and each glyph still comes before its text in reading order. That is sane.

0 H, 1 M, 6 L.

## M1 - At font scale 2.0 the unread part is ellipsized away (en "yesterday", nb near the edge)

I measured the line with Nunito Bold's real advance widths. The glyph is 1.1 em, plus a thin space. The em space
falls back to 1 em because Nunito has no U+2003. The column is 128 dp below 1.3 and 214 dp from 1.3 (stacked). At 2.0,
13 sp is about 25 dp.

| line | 1.0 (128) | 1.3 (214) | 2.0 (214) |
|---|---|---|---|
| ☎ 10:19  💬 12 | 97 | 126 | 186 |
| ☎ 30. nov.  💬 12 | 110 | 143 | 211 |
| ☎ yesterday  💬 3 | 114 | 148 | **219** |
| ☎ yesterday  💬 12 | 122 | 158 | **234** |
| ☎ yesterday  💬 150 | **129** | 168 | **249** |

When it overflows, `ellipsize="end"` cuts from the end, and the end is the unread part. Everything up to the chat
glyph plus the "…" is already about 223 dp at 2.0. So the line reads "☎ yesterday …", and the unread messages vanish
from the row. Before this change the teal pill still showed them. nb "30. nov." with two unread digits is 3 dp short
of the edge, so a small metric difference tips it over.

The test that checked the row's second line was deleted with the old strings (`WholeWordsTest`), and nothing replaced
it. Nothing checks the new line's width.

**Fix (lean):**

- Allow two lines (`maxLines="2"`), with the one break at the em space.
- Inside each part, use U+FFFC as the image span's placeholder (instead of `" "`) and U+202F (narrow no-break space)
  instead of U+2009. Then a part never splits from its glyph, and the teal part wraps whole to a second line.
- Add the worst parts per language (nb "30. nov." / "i går" / a time, en "yesterday" / "Nov 30", and a 3-digit
  count) to `WholeWordsTest` at 1.0, 1.3 and 2.0, checking that each part fits the column whole.

## L

- **L1 - "Baseline-aligned" vs `ALIGN_CENTER`.** The brief says baseline. The code centres the glyph in the line box.
  For digits, centring is the better optical choice: the glyph's centre sits at about the digits' mid-height, and a
  baseline-aligned 1.1 em glyph would stand well above the cap height. Keep the code, but say "centred" in
  `calls.md`. A side effect: `DynamicDrawableSpan.getSize` raises the line's ascent to the glyph's 1.1 em, above
  Nunito's 1.011 em. A row with glyphs is therefore about 1 dp taller than a "Nødnummer" row. That is invisible, so
  it is noted only.
- **L2 - TalkBack no longer names Element X.** The old sentence used `KidAvatars.missedText`: "1 tapt anrop i
  Element X i dag 14:02". The new one uses `MissedCallText.short` on the summed count, so a missed Element X call
  reads as a plain "Tapt anrop 14:02". It matches the brief's sentence, and the sheet still says it in full. This is
  only so the drop is a known choice.
- **L3 - The unread count is not capped.** The pill shows "99+" (`badgeText`), but the line shows the raw count
  ("150"), which is also the widest case in M1. Consider `badgeText`'s cap here as well.
- **L4 - Stale words and names.**
  - `sentence`'s KDoc still says "Pappa, 2 tapte anrop i dag 14:02, 1 ulest melding". It is now "2 tapte anrop 14:02,
    1 melding".
  - The code comment, `calls.md` and `ui-polish.md` call this "variant A (with glyphs)". PLAN and the user call it
    variant D.
  - `ui-polish.md`'s row bullet still describes "two pills on it" as current. The new text that says otherwise comes
    after it.
- **L5 - Dead resources.** The plurals `contact_unread_short` (nb and en) now have no caller. The row's two pill views
  (`badge`, `unreadBadge`) stay in `item_phone_book_row.xml`, bound to zero each time. Drop both, or say in a comment
  that the views are kept on purpose.
- **L6 - No test of `rowLine`'s choices.** Nothing tests the time vs the count, the order, or that the gap appears
  only when both parts are there. `rowLine` needs a `Context`, and the launcher has no Robolectric. A pure helper,
  `rowLineParts(missedCount, unread)` returning `[missed(time|count), unread(count)]`, would make this a three-row
  unit test. This is optional; M1's width test matters more.
