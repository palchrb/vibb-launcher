# QA 34 step 8 (code) - the keypad's tonal emergency button, the spacing rule, the key highlight, the glance clock

Reviewer: code QA, 2026-10-14. Branch `design-34-s8`, `cfd61bdf..2c4ac116` (one commit, launcher and docs). I read it
against `docs/design/34-launcher-polish.md`: P7, §5 step 8, §6 answer 4 ("dempet rød er fint"), §7 (M6, N1, N4), step
3's shared emergency resources and the new "Step 8 implementation status". I also read `qa-34-design.md` (M6, N1, N4),
the mockup sources `05-lock-keypad.html` and `05b-lock-keypad-large-text.html`, and `launcher/CLAUDE.md`.

In the code I read:
- the whole diff;
- `PinLockActivity`'s keypad (`buildKeypad`, `sizeKeys`, `keypadEnabled`/render), the emergency wiring (`:277`, `:304`)
  and the VoIP ring's wiring;
- `view_voip_ring.xml`, `view_lock_glance.xml`'s emergency link, `bg_pin_emergency*.xml`, `bg_pin_key.xml`,
  `bg_call_red.xml`, `WallpaperInk.LOCK_INKS` and the `PinLockActivity` manifest entry.

Line numbers are `2c4ac116`'s. No product code was changed. The mutations were temporary and were reverted, and
`git status` was clean before this file was written. I did not use the emulator.

**Verdict: merge; the three Lows fit in a short fix round.** The hard rules hold:
- **The emergency flow is the same.** It is the same `pin_emergency` view with the same `setOnClickListener`
  (`PinLockActivity.kt:304`, `EmergencyCall.confirm` → `emergencyFlowStarted`). The glance page's link still calls
  `binding.pinEmergency.performClick()` (`:277`). Only the background, the tint/text colour, the paddings, `minHeight`
  and two new ids changed. No code sets the view's visibility, margins or enabled state.
- **Nødsamtale is clearly recognisable, and it never collides with a key.** The label and glyph are `#FF8A8A` on
  `#3A1E24`, 6.65:1. They are the only red on the keypad stage, and the 112 avatar and the glance page's link use the
  same red, so it reads as "the emergency colour" throughout. The pill against the night is 1.29:1 (QA N4 accepted
  this, and the user said "dempet rød er fint"). The pill is an opaque field, so the text keeps 6.65:1 over a photo
  too. The column is one vertical `LinearLayout`, and the keypad (weight 1, no bottom margin) clips its own rows. A key
  can therefore never reach the button: the worst case is a key clipped at the 32 dp floor, never an overlap.
- **The lock's security is unchanged.** The diff touches no state, check, lock-task or window code. `FLAG_SECURE` and
  the keypad's disable path (`:685-686`, alpha 0.4 + `isEnabled`) are untouched.
- **It stays calm.** The saturated `#C92A2A` pill is gone from the keypad.

The Lows:
- **L1:** the digit cap is applied only when the key size changes. That leaves one real gap, and the test doesn't
  catch it (mutation m4b survived).
- **L2:** the VoIP ring screen's pill. I recommend it follows P7.
- **L3:** TalkBack doesn't call Nødsamtale a button. This is older than this step, but cheap to fix now.

Counts: **0 High, 0 Medium, 3 Low, 5 Nits.**

## What I ran

- `./gradlew --no-daemon --max-workers=2 assembleDebug testDebugUnitTest --rerun -PwarningsAsErrors=true` (from
  `launcher/`, under `~/build.lock`) passed. All 1199 tests passed, none were skipped, `PinLockLayoutTest` 13/13 and
  `KidPaletteTest` 8/8.
- I ran 10 mutations against `PinLockLayoutTest`, `KidPaletteTest` and `LockGlanceLayoutTest`: 9 were caught and 1
  survived. The table is at the end.
- I measured Nunito's metrics straight from the bundled TTFs (`res/font/nunito_{bold,extrabold}.ttf`; hhea, head, hmtx,
  cmap):

  | | ExtraBold (the label) | Bold |
  |---|---|---|
  | ascent + descent | 1.364 em | 1.364 em |
  | bbox | 1.06 / -0.283 em | 1.055 / -0.278 em |
  | "Emergency call" | 7.21 em (0.515 em/char) | 7.07 em |
  | "Nødsamtale" | 5.73 em (0.573 em/char) | 5.63 em |
  | "Foreldrekode" | 6.33 em | 6.18 em |

## Low

### L1. The digit cap is skipped when the keys end up at exactly 48 dp, and the test doesn't see the wiring

`PinLockActivity.kt:569`, `:588-599`.

`buildKeypad` creates every key at `dp(MIN_DP)` (48 dp). `sizeKeys` returns early when no key changes size (`:589`),
and the digit size is set only inside that same `changed` pass (`:599`). If the measured keypad gives exactly
`dp(48)` px, the keys never change, so the digits keep their 30 sp at the current font scale. On the Jelly Star
(1.5×) that happens for a band of about 4 px of keypad height. With 3-button navigation at a large font scale, the real
(non-linear) keys sit near 48-49 dp, so the band is realistic. There a 2.0 digit is 38 dp on a 48 dp key, against a
cap of 28.8 dp. Its line box (1.364 em = 52 dp, `includeFontPadding=false`) is taller than the key, so `getVerticalOffset`
top-aligns it and the digit sits about 6 dp low. It isn't cut, but it isn't the rule either.

The same gate is why m4b survived: deleting the `setTextSize(..., digitPx)` line fails no test. The test checks only
that the string `PinKeypadLayout.digitTextPx(` appears in the file.

*Fix:* set the digit size outside the `changed` gate. Compute `digitPx` on every `sizeKeys` and set it on each
`TextView` key whose `textSize` differs. Equivalently, give the keys no initial size the layout can land on. Extend
the source pin to the call itself, `key.setTextSize(TypedValue.COMPLEX_UNIT_PX, digitPx)`, or better, move the
"size + digit" decision into a pure `PinKeypadLayout.keyStyle(keypadHeight, width, density, scaledSp)` and test that.

### L2. The VoIP ring screen's Nødsamtale should follow P7 (answering the implementer's open question)

`view_voip_ring.xml:136-164` (the `voip_ring_emergency` pill: `bg_pin_emergency` `#C92A2A`, white text, `minHeight`
44 dp, no wrap).

My recommendation is to follow P7 here too. There are three reasons:
- **The same lock, the same button.** The ring card is laid over the keypad stage of the same `PinLockActivity`. When
  a call rings, the kid sees the calm tonal pill turn into a saturated red one, and it turns back when the ring ends.
  P7's intent ("calm everywhere, emergency recognisable") and step 3's shared pair (avatar, glance link, keypad pill)
  point to one emergency look.
- **Decline vs emergency.** On the ring screen the red that matters is Avvis (`bg_call_red` `#E03131`, a 76 dp disc
  with a phone glyph). Right below it sits a second saturated red with the same phone glyph. That is the one place
  where a child under stress could mix up "decline" and "emergency". With the tonal pill there is one saturated red
  (the action, Avvis) and one labelled emergency control with a clearly different look.
- **The target.** The ring pill is still 44 dp, below P7's 48 dp minimum, and its label can't wrap.

*Fix:* in `view_voip_ring.xml` use `@drawable/bg_pin_emergency_tonal`, `tint`/`textColor` `@color/kid_emergency_text`,
`minHeight` 48 dp, the 6 dp vertical and 20 dp side padding and `gravity="center"` on the label (no line limit). Then
delete `bg_pin_emergency.xml`, which has no other user. The click wiring (`PinLockActivity.kt:292-294`) stays as it
is. Add the ring pill to the `KidPaletteTest` pill test, so both pills are read from their layouts. If the user wants
the ring screen to stay saturated, record that decision in the design doc instead of "a separate call".

### L3. TalkBack reads Nødsamtale without the button role

`activity_pin_lock.xml:77-112`.

The pill is a clickable `LinearLayout`. TalkBack merges the label ("Nødsamtale") and says "double-tap to activate",
but it announces no role, because the class name is `LinearLayout`. This is older than this step: the old pill was the
same. The step's own checklist does ask for the button role, though, and the same holds for the VoIP pill.

*Fix:* in `PinLockActivity.onCreate`, set an `AccessibilityDelegate` (or `ViewCompat.setAccessibilityDelegate`) on
`pinEmergency` and `voipRingEmergency` that sets `info.className = Button::class.java.name`. The click path doesn't
change. Pin it with a one-line source check in `PinLockLayoutTest`'s pill test.

## Nits

- **N1. "Measured" is the model, not the screen** (`34-launcher-polish.md:694-697`, `launcher/CLAUDE.md`'s P7 bullet).
  The model is a sound upper bound. Per line, 1.4 em ≥ Nunito's 1.364 em plus the include-pad top of ~0.05 em, and
  0.6 em/char ≥ the real 0.515-0.573. The scale is linear, which over-states 2.0 (15 sp → 30 dp, where Android 14
  gives ≈ 27 dp). But "the English 'Emergency call' wraps to two lines, the pill is 96 dp" is the model's output. In
  Nunito ExtraBold at a linear 2.0, "Emergency call" is 7.21 em × 30 dp = 216 dp, which fits the 222 dp the pill
  leaves it on one line. At the real non-linear size (≈ 195 dp) it fits easily. The emulator check will show one line,
  a pill of ≈ 50 dp and keys larger than 49/43. Say "modelled (upper bound)" in both docs, and keep the test's
  two-line assertion as what it is: a model check (`PinLockLayoutTest.kt:160`).
- **N2. The digit cap's rationale** (`34-launcher-polish.md:687-688`, `PinKeypadLayout.kt:30-31`). Mockup 05b doesn't
  draw 0.6: both mockups use `font-size: calc(var(--k) * .42)` at every scale. Also, at 2.0 the cap makes the digits
  *smaller* than at 1.0: 0.6 × 43 = 25.8 dp (modelled) or about 29 dp (real keys), against 30 dp at 1.0. They are
  still legible, about the label's size at that scale. With `includeFontPadding=false`, 0.7 still fits the line box
  (0.95 of the key) and keeps a 2.0 digit at ≥ 30 dp. Pick one and give the real reason in the comment.
- **N3. The highlight is 1 device px at 20 %; the mockup draws 1 dp at 6 %** (`PinKeyHighlight.kt:20`,
  `PinKeypadLayout.kt:34`; mockups: `box-shadow: inset 0 1px 0 rgba(255,255,255,.06)`). On the Jelly Star 1 px is
  0.67 dp, and on a 3× phone it is a hairline. It is drawn cheaply: one anti-aliased `drawArc` per key per frame, the
  `Paint`/`RectF` allocated once. It is the same in the pressed state (the selector under it changes) and dims with
  the keypad's 0.4 alpha when disabled. It never touches the digits. Over a bright photo it simply vanishes, which is
  harmless. Make the stroke `1 dp` (`strokeWidth = density`, passed in) and check the 20 % against the mockup's 6 % in
  the emulator pass. "Calm" may want less.
- **N4. The pill has no pressed state** (`bg_pin_emergency_tonal.xml`). The old pill had none either, but every other
  control on the lock answers a touch: the keys' selector, Foreldrekode's `selectableItemBackground`, and the glance
  link. A `<ripple>` in `kid_emergency_text` at ~20 % over the tonal shape costs nothing.
- **N5. Out of scope, noted for step 6/P6:** the glance page's "Nødsamtale" link (`view_lock_glance.xml:313-327`) is
  44 dp tall, the one emergency control below P7's 48 dp after this step.

## Mutations

Each mutation was run against `PinLockLayoutTest`, `KidPaletteTest` and `LockGlanceLayoutTest`, then reverted with git.

| # | Mutation | Result |
|---|---|---|
| m1 | the button's `layout_marginTop` 16 → 8 dp | caught (keys at 1.0 no longer 71/65; the spacing rule) |
| m2 | the pill's `minHeight` 48 → 44 dp | caught (keys at 1.0; the spacing rule) |
| m3 | `maxLines="1"` on `pin_emergency_label` | caught (the spacing rule's line-limit check) |
| m4 | `digitTextPx` returns the scaled size (cap removed) | caught (the highlight/digit test) |
| m4b | the `setTextSize(..., digitPx)` call in `sizeKeys` deleted | **survived** (L1) |
| m5 | the label's colour → `#7A3A44` (1.81:1 on the pill) | caught (pill test; `KidPaletteTest` ≥ 4.5:1) |
| m6 | the click listener moved to `pinEmergencyLabel` | caught (source pin) |
| m7 | `clickable="true"` removed from the pill | caught |
| m8 | the highlight drawn under `bg_pin_key` instead of over it | caught (source pin) |
| m9 | `DIGIT_SHARE` 0.6 → 0.9 | caught |

## Checked and fine

- **Contrast**:
  - label/glyph on the pill: 6.65:1;
  - the pill on `kid_ground`: 1.29:1 (asserted < 1.5);
  - `#FF8A8A` on the bare night: 8.58:1 (asserted, and in `LOCK_INKS` for the glance link).

  The glyph's `android:tint` is read on API 34+ (`minSdk` 34), so it works without AppCompat's `app:tint`.
- **Target and wrap**: the pill is at least 48 dp tall and about 152 dp wide in nb. In a `wrap_content` horizontal
  `LinearLayout` the label wraps against the parent's AT_MOST width (the icon is centred vertically). The pill's
  24 dp radius makes a true pill at 48 dp and rounds off when it grows. Foreldrekode is at least 44 dp.
- **The 16 dp gap is the least gap.** The keypad has no bottom margin, its rows are centred inside it, and
  `finalKeySizePx` sizes the keys to fit unless the 32 dp floor bites. Nothing else is in the column: the
  `PinLockLayoutTest` order check pins the five children, and no code changes margins or visibility.
- **The model**: 1.0 → 71/65, 1.3 → 69/63, 2.0 → 49/43 dp, never overflowing, always above the floor. The real values
  are larger, because Android 14 scales non-linearly and the strings are narrower than 0.6 em.
- **Config changes**: `PinLockActivity` handles `fontScale|density` itself. `sizeKeys` reads the current
  `displayMetrics` every time, and the lock is a new instance after each unlock, so a stale PX digit size can't
  outlive a font change in practice.
- **The glance clock**: `@font/nunito_bold` (700); `nunito.xml` maps 700 to it, and `GlancePanel` sets no typeface.
  The size and the fit are untouched.
- **Less code**: the change is small. `PinKeyHighlight` is 45 lines with no state beyond its paint, and the pure parts
  are in `PinKeypadLayout`. The tests pin behaviour from the layout and the drawable rather than from copies, except
  the source-string pins m4b shows the limit of.
