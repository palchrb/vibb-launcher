# QA 34 step 3 (code) - soft avatars and the night contact sheet

Reviewer: code QA, 2026-10-14. Branch `design-34-s3`, `59c7fbef..d333159b` (one commit, 9 files, launcher and docs).
I read it against `docs/design/34-launcher-polish.md`:
- §2 (principles 1-3), P1's 112 sentence, P3 with its colour table, P7's pill note, §5 step 3;
- §7 (how M3, M4 and L7 were handled) and the new "Step 3 implementation status";
- `qa-34-design.md` M3, M4 and L7;
- mockups `01-home-after.png` and `03-contact-sheet-after.png`.

I also read every place a contact avatar is drawn and the code it leans on:
- `KidAvatars.bindContact`/`ring`, `HomeModel.avatarStyle`;
- `HomeActivity.renderContacts`/`renderCallCard`, `PhoneBookAdapter`, `ContactSheet`, `GlancePanel` (missed rows),
  `InCallActivity.renderAvatar`, `CallCard.callAvatar`;
- `sheet_contact.xml`, `bg_kid_sheet*.xml`, `bg_kid_badge_sheet.xml`, `bg_kid_button.xml`, `KidSheetButton`;
- the other users of `kid_sheet_ink` (`activity_home.xml:45`, `bg_home_window_lock_again.xml`) and of
  `kid_message_button` (`item_lock_app.xml`);
- the `#2B8A3E` literals (`bg_call_green.xml`, `bg_home_call_card.xml`).

Line numbers are `d333159b`'s. No product code was changed: the mutations below were temporary and are reverted, and
`git status` was clean before this file was written.

**Verdict: merge; L1 and L3 are a two-line colour change and one test, worth taking in a fix round before P4.**

What holds:
- **Palette.** The six soft discs and `AVATAR_INK` match P3 exactly. Ink on disc is 8.90-11.07:1 (I recomputed all
  six), and the discs are >= 10.2:1 against the ground.
- **Colour slot.** It is still `floorMod(contact.id, 6)`, the same index as before. An existing contact keeps its slot
  and only its hue changes. Nothing else reads `AVATAR_PALETTE`: a grep over the repo found only `HomeModel.kt` and
  its test, with no copy in `music/`, the server or the PWA.
- **Photos win.** That path (`KidAvatars.kt:46`) is unchanged.
- **The 112 rule.** Home's row (`HomeActivity.kt:825`), the sheet (`ContactSheet.kt:33`) and the phone book
  (`PhoneBookActivity.kt:175`) all decide "emergency" with `CallSystem.isEmergencyOutgoing`. That is the function the
  call path uses (`KidCallRedirectionService`, `KidInCallService.decideOutgoing`, `PhoneBookActivity.kt:200`). The
  no-rules tile passes `isEmergency = true` for `EMERGENCY_NUMBER`, as before. `avatarStyle` ignores the id once
  `isEmergency` is set.
- **Where the 112 avatar shows.** Only on Home's contacts row, in the phone book (including the no-rules tile) and on
  the sheet. The call screen and Home's call card show the peach silhouette for an emergency call (`callAvatar`,
  unchanged). The glance rows never pass `isEmergency`. So there is no ring-less 112 avatar on the lock or the call
  screens.
- **Sheet inks.**
  - On `#1C1A2A`: name 14.0:1, missed `#FF8A8A` 7.52:1, hint and Close 8.97:1.
  - On the tonal button: Message text 11.19:1, peach glyph 6.91:1.
  - The handle is 2.03:1.
  - I checked the test's alpha compositing by hand. `kid_core_dim` `#C7FBE4DC` over the lift gives `#CAB8B5`, and
    `#14FFFFFF` over it gives `#2E2C3B`. These are the values `over()` computes, so the test composites correctly.
- **The other users of the changed colours are unaffected.** `kid_sheet_ink` (Home's window banner) keeps
  `#1B1B1F`, and `kid_message_button` (the time-rule screen) keeps `#1864AB`. `bg_kid_badge_sheet` now rings the badge
  in the lift, which is right on a night sheet.
- **Message's glyph tint.** `app:drawableTint` takes effect: the sheet is only inflated from `UIObjectActivity`, an
  `AppCompatActivity`, so it is AppCompat inflation. The vector is a white stroke, so `SRC_IN` gives exactly the peach.
- **TalkBack is unchanged.** The photo, initial and ring stay `importantForAccessibility="no"`. The glyph is a compound
  drawable. The texts and their order are as before.
- **Behaviour is untouched.** The sheet's buttons, `ContactSheet`, `tel:` handling and gestures are all unchanged, and
  nothing new is on the lock.

## What I ran

- From `launcher/`: `./gradlew --no-daemon --max-workers=2 assembleDebug testDebugUnitTest -PwarningsAsErrors=true`.
  Result: BUILD SUCCESSFUL, 1133 tests in 120 classes, 0 failures.
- Contrast was recomputed independently (WCAG 2.x relative luminance, alpha composited) for every pair above, the
  112 pair and the call greens.

### Mutations (each applied alone or as noted, the relevant tests run, then reverted)

| # | Mutation | Tests | Result |
|---|---|---|---|
| 1 | `AVATAR_INK` → `#8A8A96` (~2:1 on the discs) | `HomeModelTest` | killed (`contact avatars are soft discs…`) |
| 2 | colour slot by `name.hashCode()` instead of id | `HomeModelTest` | killed (`a contact keeps its colour`, `avatar initials and colours`) |
| 3 | 112 only for `id >= 0` in `avatarStyle` | `HomeModelTest` | killed (`emergency numbers are the tonal red…`) |
| 4 | `avatarRingColour` always the disc colour | `HomeModelTest` | killed |
| 5 | `kid_sheet_muted` back to `#495057` | `KidPaletteTest` | killed (`every text on the contact sheet…`) |
| 6 | `kid_sheet_button` → `#80FFFFFF` | `KidPaletteTest` | killed (the quiet-field bound) |
| 7 | `sheet_name` `textColor` back to `kid_sheet_ink` (invisible on the night sheet) | all | **survived** (L3) |
| 8 | `kid_call_button` → `#37B24D` (white 2.75:1) | all | **survived** (L1) |
| 9 | Home's row passes `isEmergency = false` (112 drawn as a pastel "1" disc, `HomeActivity.kt:830`) | all | survived (Android glue, pre-existing, accepted) |
| 10 | photo never used (`val bitmap: Bitmap? = null`, `KidAvatars.kt:46`) | all | survived (Android glue, pre-existing, accepted) |

Mutations 7-10 were run together in one full `testDebugUnitTest`. It passed, so each of them survives on its own.
Survivors 9 and 10 are view glue that no test reaches without Robolectric, and the step didn't touch them. I'm not
asking for tests there.

## Low

### L1. Ring's white label is 4.37:1 on `kid_call_button`

`colors_kid.xml:32` keeps `#2B8A3E`, and white on it is 4.37:1. The label is 17 sp extra-bold, which is under WCAG's
large-text size (14 pt bold ≈ 18.7 sp), so §2 principle 3 asks for 4.5:1. This is the main action of the sheet the
step redesigned for contrast, and nothing tests it (mutation 8). The design's "Ring stays call green" doesn't stop a
slightly deeper call green.

*Fix:* `kid_call_button` `#29833B`.
- White on it is 4.77:1.
- Against the sheet it is 3.58:1, and against the ground 4.08:1.
- It reads as the same green.

The one-line test to add to `every text on the contact sheet…`:
`assertTrue(contrast("kid_ink", "kid_call_button") >= 4.5)`.

The resource is used only by the sheet, so nothing else moves. Two literals carry the same green:
- `bg_home_call_card.xml`: Home's call card has a white 17 sp title on it at 4.37:1, and a 14 sp hint at alpha 0.9
  at 3.88:1.
- `bg_call_green.xml`: the answer button, glyph only.

Pointing both at `@color/kid_call_button` keeps one green and lifts the card title to 4.77:1. The hint needs its
`alpha="0.9"` dropped to pass. That part is pre-existing and optional for this step.

### L2. The 112 disc is only visible on the sheet, where the ring was added

Answer to "decision 1: right?": **yes on the sheet.** The dark `#3A1E24` disc is 1.13:1 on the lift; the
implementation status says ~1.3. A 4 dp `#FF8A8A` ring on a 132 dp frame is a soft salmon, not garish, and keeps the
emergency tile a clear round target.

On Home's contacts row and in the phone book, the same disc sits on `kid_ground` at 1.29:1 with no ring. What
identifies it there is the "112" in `#FF8A8A` (8.58:1 on the ground) and the label below. That is the reasoning P7
accepts for the lock's pill ("its text and glyph identify it"), so it is in line with the design. Two things are
still off:
- The emergency tile is the one 76 dp target whose edge you can't see. On Home it carries a green call badge that
  then appears to float.
- The three surfaces now differ: ring on the sheet, none elsewhere.

*Fix:* in the emulator pass the step still owes, look at Home over the dark ground and a dark wallpaper image, and at
the phone book's no-rules tile. If the "112" reads as loose text, give the emergency disc the same ring everywhere:
1. In `bindContact`, for an emergency number, set a stroked oval background instead of tinting `bg_kid_circle`:
   solid `#3A1E24` with a 2-3 dp `#FF8A8A` stroke.
2. `ring()`'s special case and `avatarRingColour` can then go, since the sheet's ring would match the disc. That is
   less code.

If it reads fine, record the decision in the doc and leave it.

### L3. Nothing pins which colours the sheet's layout uses

`KidPaletteTest` checks the resources (`kid_core`, `kid_sheet_muted`, …) but not that `sheet_contact.xml` uses them.
Mutation 7 put the name back on `kid_sheet_ink`, which is invisible on the night sheet, and every test passed. That
is exactly QA M4's original failure.

*Fix:* in `KidPaletteTest`, parse `sheet_contact.xml` with the same DOM code. For every element with
`android:textColor` or `app:drawableTint`, assert the colour composited over its background reaches 4.5:1:
- the background is its `android:backgroundTint` composited over `kid_sheet`, or `kid_sheet` itself;
- the default text colour of `KidSheetButton` is `kid_ink`.

That covers the name, missed, Message, hint and Close, and with L1 also Ring. It is about 15 lines and replaces the
hand-kept list in the current test.

## Nits

- **N1.** The implementation status (`34-launcher-polish.md:371`) says the 112 disc is "~1.3:1 on the lift". It is
  1.13:1 on the lift (1.29:1 on the ground).
- **N2.** `KidPaletteTest.contrast()` (line 31) silently drops alpha (`takeLast(6)`), while the new `over()` handles
  it. Have `contrast()` composite its first argument over the second, or assert both are opaque, so the next
  translucent ink isn't measured as opaque by accident.
- **N3.** `EMERGENCY_AVATAR` (`HomeModel.kt:222`) hard-codes `#FF8A8A`, which is also `kid_emergency_text`, and
  `#3A1E24`, which P7's pill will use. Its KDoc says "like the lock's emergency pill", but the pill isn't built yet.
  When P7 lands, give the two one source, or a test that they match, so the 112 avatar and the pill can't drift.
- **N4.** `launcher/CLAUDE.md` says every `bindContact` surface "gets them", which is true for the pastel discs. Add
  half a sentence saying the 112 avatar shows only on Home's row, the phone book and the sheet: the call screens use
  the silhouette for emergency calls, and the glance rows never pass `isEmergency`. That saves the next reader the
  search I did.

## Counts

0 High, 0 Medium, 3 Low, 4 Nits.
