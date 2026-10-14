# QA of the launcher fixes after the emulator round (code)

Reviewed `emu-round-launcher-fixes` at a451cd3f (on a9ff636f): N7 `ui/WholeWordText.kt` with the tile and contact
layouts, N8 `lock/FontScaleRefresh.kt`, `PinLockActivity.onConfigurationChanged`, `GlancePanel.refit` and the glance
date's gravity, N9 `ClockFormat.textLocale`, N10 `EmergencyCall.confirm`, and the tests `WholeWordsTest`,
`LockFontScaleTest`, `ClockFormatTest` +1. Checked against `docs/testing/2026-10-14-emulator-round.md` (N7-N10, N14),
design 34's "Fixes after the emulator round" and `launcher/CLAUDE.md`. No emulator.

**Verdict: good to merge after M1 (a few lines in `WholeWordTextView` plus a test).** N8, N9 and N10 do what they say.
N7's rule is sound: no layout loop, the 75 % floor holds, and the ellipsis only touches the word that overflows. One
gap remains. A recycled Home tile can keep the previous label's fit, and then the letter split N7 fixed comes back.
Most of the view's own logic is pinned only by "this string is in the source" checks, so 6 of 10 mutations survive
(L2).

Build: `assembleDebug testDebugUnitTest --rerun -PwarningsAsErrors=true` ran under the build lock and passed. 1273
tests, 0 failures, 0 skipped.

## What holds

- **No layout loop or measure storm.** `fitWords` runs in `onMeasure` and returns at once while the
  `(text, width, basePx)` key is unchanged. Its own `setTextSize`/`transformationMethod` run under `adjusting`, so they
  do not reset `basePx`. The `requestLayout` that `setRawTextSize` raises during measure stops at the parent, which
  is already marked, and inside RecyclerView's layout it is deferred. The next pass hits the key. The cost is at most 6
  `StaticLayout`s per label per new text or width, which is fine for a grid of about 12 to 20 cells. The
  `WordEllipsis` data class compares by value, so the method is not swapped on every pass.
- **The line check.** `getLineWidth` leaves out trailing spaces and `getDesiredWidth(start, end)` includes them, so a
  line that ends in a space never reads as hyphenated. A drawn hyphen adds about 0.3 em, well above the 0.15 em
  threshold. Its own hyphen ("Anne-Marie"), a space and the text's end never count as a cut. `getLineWidth` is
  unsigned, so RTL is fine. Emoji (surrogates) are not word characters, so a break between emoji is never a cut. A
  label with no spaces goes the shrink, then ellipsis path.
- **Floor and ellipsis.** The sizes run evenly from the base to 0.75× the base (`sizes`). The ellipsis comes only at
  the floor and only for a word wider than a line. `text` stays the source.
- **Font scale and density.** `basePx` is the XML or caller sp in px. Home and the phone book are recreated on a
  font-scale change (no `fontScale` in their `configChanges`). The Home grid re-applies `labelSp` on every bind
  (`HomeGridAdapter.kt:106`), which resets `basePx`.
- **The phone book's names** (`contact_name`, `wrap_content`): `TextView.checkForRelayout` always calls
  `requestLayout` for a wrap_content width, so a rebound name is always fitted again (unlike M1). Home's contacts row is
  inflated fresh on every refresh.
- **N8.** The keys are `TextView`s themselves, so `root !in skip` really skips them. `sizeKeys` runs with the old
  height, and the keypad's layout listener (`PinLockActivity.kt:315`) catches the new height. The clock's size comes back
  from the `CLOCK_*_SP` constants in `refit`/`fitNow`. Nothing on the lock sets a text size in dp or px other than the
  keys. `deriveDimension` is API 34, and `minSdk` is 34. A density change is not followed, which is outside N8's scope.
  The crash guard and re-front are untouched: no `recreate`, no lifecycle call, and a `Throwable` is caught and logged.
  Dialogs already open over the lock (their own windows) keep their old size until they reopen, which is acceptable.
  **The `am crash` observation is not explained by this code, or by the code before it.** A fresh process inflates at
  the current scale. Keep the emulator re-check the design note asks for.
- **N9.** `ofPattern(pattern, textLocale)` takes only text fields from the locale. The hour cycle, separator and digits
  (the `DecimalStyle` stays STANDARD) are the system's, so the one-clock-format rule holds. On Android, java.time's
  AM/PM text comes from ICU's `DateFormatSymbols`, and CLDR nb gives the abbreviated "a.m."/"p.m.". That is the same
  as the JVM's CLDR provider the test runs on (not checked on a device; the emulator re-check covers it). Vibb sets its
  language with `LocaleManager.setApplicationLocales` (`LauncherLocales.kt:36`), so `applicationLocales` is the right
  source. "Follow the phone" gives an empty list, which falls back to the system locale.
- **N10.** Only the theme changed. Confirm, then `onCalling`, then `call`, with `EmergencyDialer` as the fallback, all
  unchanged. `KidAlertDialog` is an overlay on `launcherBaseTheme` (the same base as `PinLockTheme`). It sets its own
  panel, ink and peach button colours, so it is the night panel in both system modes, as the phone book's confirm
  measured in step 5. The button order (N14's mis-taps: RING where AVBRYT is expected) is unchanged and outside this
  round.

## Findings

### M1 - A recycled Home tile keeps the previous label's fit: "Chro/me" can come back

`ui/WholeWordText.kt:108-112` fits only in `onMeasure`, and nothing asks for a measure when the text changes.
`tile_label` is `match_parent` wide and `wrap_content` high. For that case `TextView.setText` → `checkForRelayout`
only invalidates when the new layout's height equals the old one: no `requestLayout`, so no `onMeasure`. The bind's
other calls do not force one either. `KidInk.label` is colour and shadow only. `label.setTextSize(SP, labelSp)`
(`HomeGridAdapter.kt:106`) changes nothing when the size is already the base. The item root is re-measured because
`frame.layoutParams` is set, but the label's spec is unchanged, so `View.measure` skips its `onMeasure`.

`submit` → `notifyDataSetChanged()` without stable ids sends the holders through the pool (LIFO), so tiles do get
other labels on a refresh, and also on a scroll. Take a tile that showed a two-line label at the base size ("Phone
book" at 2.0), then gets a label that Android cuts into two lines ("Chrome" in en). It shows "Chro/me" with no
refit. The reverse case (a shrunk label rebound to a short one) is fixed by the size reset. Reasoned from AOSP's
`checkForRelayout` and `View.measure`; not reproduced (no Robolectric here, no emulator).

**Fix:** in `WholeWordTextView`, override `onTextChanged(text, start, lengthBefore, lengthAfter)`, and after `super`
run `if (!adjusting) { fittedFor = null; requestLayout() }`. `adjusting` keeps it quiet during
`setTransformationMethod`'s internal `setText`. It is also safe in the constructor, where the fields still hold their
defaults. Pin it with a source check next to the existing ones, or better, with the pure test in L2.

### L1 - CJK and Thai labels count every line break as a cut

`ui/WholeWordText.kt:34-41`: a cut is "letters on both sides, no hyphen". In Chinese or Japanese every break is
between two letters (`isLetterOrDigit` is true for ideographs). Thai breaks between letters by dictionary. So a
two-line CJK label always shrinks to the floor, and `ellipsizeWords` then treats the whole run as one "word" and
truncates it ("微信…" instead of a normal wrap). App names in these scripts only show up if the phone's language is
one of them or a parent installs such an app. Low.

**Fix:** a line end is a cut only if it is not a line-break opportunity:
`!hyphenated && !BreakIterator.getLineInstance(textLocale).isBoundary(lineEnd)` (`android.icu.text.BreakIterator` in
the view; keep the letter test as the pure fallback). nb and en behave as now.

### L2 - The view's own logic and two wirings are pinned only by source strings (6 mutations survive)

`WholeWordsTest` checks the rule against a model line breaker, which is good. But `cutsAt`, the key and the base reset
are covered only by "this string is in the source" checks, which miss changes next to the checked strings (M2, M2b,
M3, M3b). The same holds for `FontScaleRefresh.rescale`'s recursion (M4b) and for the marker locale's source
(`takeIf { !it.isEmpty }`, M6).

**Fix (less code than it sounds):**

- Move the decision out of `cutsAt` into a pure `WholeWords.cutLines(text, ends, lineWidths, desiredWidths, hyphenPx,
  shown)` and test it with a hyphenated line, a trailing space and a line past `maxLines`.
- Have `fitWords` take its key from a pure `fitKey(text, width, basePx)`.
- Add a `FontScaleRefresh` test with a two-level fake tree. `TextView` can't be built on the JVM without
  Robolectric, so make `rescale` take `children: (View) -> List<View>`, or pin the recursion line.
- For M6, test `chooseTextLocale(LocaleList)` with an empty list.

### N1 - The comment "the accessibility text stays whole" is inaccurate

`ui/WholeWordText.kt:86-87` (and design 34's N7 bullet). `TextView.getTextForAccessibility()` returns `mTransformed`,
so the label's own node would say "Melding…". It has no effect today: every tile and contact root is clickable and
carries the full name as its `contentDescription` (`HomeGridAdapter`, `HomeActivity.kt:852`, `PhoneBookAdapter`),
and TalkBack reads that. **Fix:** say so in the comment, or override `onInitializeAccessibilityNodeInfo` with
`info.text = text` if the label is ever focusable on its own.

### N2 - `textLocale` could be the context's own locale

`ui/ClockFormat.kt:126-133` makes a `LocaleManager` binder call on every render to learn Vibb's language. The context
already has it: since API 33, `context.resources.configuration.locales[0]` is the per-app locale, or the system's when
Vibb follows the phone. That is also the locale "Til" itself was resolved in, so the marker and the sentence can never
differ. **Fix:** `textLocale = context.resources.configuration.locales[0] ?: system`, which drops `chooseTextLocale`
and the try/catch.

## Mutations

Targeted runs of `WholeWordsTest`, `LockFontScaleTest`, `ClockFormatTest`, `*PinLock*` and `*Emergency*`. The source
was restored after each.

| # | Mutation | Result |
| --- | --- | --- |
| M1 | Shrink to 50 % instead of the 75 % floor (`sizes`) | killed (`WholeWordsTest` rule and pure parts) |
| M2 | The view never sees a cut (`(0 until 0).any`, mid-letter split allowed) | **survived** (L2) |
| M2b | Hyphen threshold `-1f` (every line counts as hyphenated, splits allowed) | **survived** (L2) |
| M3 | `setTextSize` no longer resets `basePx` (recycling without reset) | **survived** (L2) |
| M3b | The fit key ignores the text | **survived** (L2; the same staleness as M1) |
| M4 | Font-scale refresh without `sizeKeys` (keys not re-sized) | killed (`LockFontScaleTest`) |
| M4b | `rescale` without recursion (only the root, so no text follows) | **survived** (L2) |
| M4c | `rescale` no longer skips the keys | killed |
| M5 | 112 confirm back to `AlertDialogCustom` | killed |
| M6 | Marker locale always the system's (`takeIf { false }`) | **survived** (L2) |

4 killed, 6 survived.

## Counts

0 High, 1 Medium (M1), 2 Low (L1, L2), 2 Nits (N1, N2).
