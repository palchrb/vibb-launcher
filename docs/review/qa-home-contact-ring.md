# Light code QA: home-contact-ring (Home's white ring and higher shadow on contact avatars, mockup A)

Branch `home-contact-ring` (cdb934fb) against main. Read-only review of `git diff main...HEAD` plus the code around it
(`item_kid_contact.xml`, `activity_home.xml`'s contacts scroll, `HomeActivity.renderContacts`, `PhoneBookAdapter`,
`KidAvatars`, `PressFeedback.attach/cellBackground`, `contactRow`/`contactItemPx`, `KidBadge`). No Gradle, no emulator
(as briefed).

**Result: 0 High, 0 Medium, 4 Low.** Nothing blocks the merge.

## What was checked and holds

- **The ring never shows outside Home.** `contact_ring` is `visibility="gone"` in the layout; `bindHomeRing` is called
  only from `HomeActivity.renderContacts`. `item_kid_contact` is inflated in two places only (Home's row, the phone
  book); the contact sheet and the glance rows use other layouts and never reference `contact_ring`.
- **Rebinding.** `bindHomeRing` sets both states (VISIBLE / GONE), so a rebind is symmetric. Home does not recycle
  (`renderContacts` removes all views and inflates new ones each time), so a contact turning emergency or back gets a
  fresh item with the right ring. The phone book's recycled holders never touch the ring, so it stays gone there.
- **Z-order.** The ring is the last child of the avatar, so it draws over the photo and the initial. Both badges are
  siblings after the avatar in the frame, now at the avatar's 4 dp (no outline, so no shadow of their own) and later in
  child order, so they draw above the avatar, its ring and its shadow. In the phone book the same code lifts the missed
  badge to 2 dp, which is `KidBadge`'s own value: no change there.
- **Shadow clipping.** `contact_frame` and the item keep `clipChildren=false`; the avatar sits 10 dp below the scroll
  view's top and the name's 6 dp margin plus the 4 dp bottom padding is below it, so nothing clips the shadow
  vertically. The name is a later sibling of the frame, so its text draws over the shadow's faint tail.
  Sideways: see L1.
- **Press.** The shade is the avatar's foreground (oval, full 76 dp), so it covers the ring; the scale is on the
  avatar, so ring and shadow scale together; the ripple/focus ring come from the cell (`cellBackground`) and are
  untouched. Nothing in `PressMotion` depends on the elevation.
- **TalkBack.** `contact_ring` is a plain `View` (not focusable, not clickable) with `importantForAccessibility="no"`;
  the item's `contentDescription` is unchanged.
- **Ring geometry.** An oval `GradientDrawable` insets its stroke by half its width, so the 3 dp ring lies inside the
  76 dp (no layout metric moves). The 64 dp (narrow/scrolling) row gets the same 3 dp; fine.

## Findings

### L1 - the notes' "at least 10 dp from the avatar" side room is too generous; ~6 dp at 64 dp avatars

`docs/notes/launcher/ui-polish.md` (the new "Home's contact ring" bullet) says the shadow is clipped by nothing but the
contacts scroll view, "at least 10 dp from the avatar at the narrowest fitting row". For 64 dp avatars the row fits when
`count*64 + (count-1)*16 <= content`, and `contactItemPx` then gives `min(80, content/count)`: at 4 contacts and 304 dp
content (a 336 dp screen) the item is 76 dp, so the avatar is 6 dp from the first/last item edge, which is the
`HorizontalScrollView`'s edge (it clips its children; nothing is centred past it). For 76 dp avatars the narrowest is
about 9.3 dp (3 contacts at 284 dp). A 4 dp shadow's sideways tail past ~6 dp is very faint, so this is cosmetic at
worst, only on the outermost avatars of a tightly fitting row.
Fix: correct the note to "about 6 dp (64 dp avatars, 4 contacts at 304 dp)" and say the faint tail may be cut there; or
leave the behaviour (no layout change is worth it for this).

### L2 - the new test leaves the change's two structural claims unpinned, and pins text instead of behaviour

`ContactBadgeLayoutTest.kt:36-51` (the new test).
- "The ring is the last child of `contact_avatar`" is what makes it draw over the initial, but the test checks only
  `ring.parentNode`. Moving the `<View>` above the `TextView` passes. Fix: assert it is the avatar's last element child.
- "The missed-call badge is lifted to the avatar's elevation" is the other half of the change and its reason (the
  avatar at 4 dp would otherwise draw over a 2 dp badge), but nothing pins
  `item.findViewById<View>(R.id.contact_badge)?.elevation = avatar.elevation`. `PressFeedbackTest`'s existing badge
  test only reads the 2 dp style and the call badge's lines. Fix: one more `src.contains(...)` there, in the
  existing style.
- The phone-book guard scans other `.kt` files for `bindHomeRing` only; `PhoneBookAdapter` showing the ring directly
  (`findViewById(R.id.contact_ring).visibility = ...`) is not caught. Fix: scan for `contact_ring` instead of
  `bindHomeRing` (the exclusions stay HomeActivity.kt and KidAvatars.kt).
- `assert(avatars.contains("ring.visibility = if (isEmergency) View.GONE else View.VISIBLE"))` and the exact
  `HomeActivity` call string break on any harmless refactor (`isVisible`, a reflowed line) without testing behaviour.
  `bindHomeRing` is a pure view toggle; a JVM test can't build a `View`, so source-pinning is this repo's idiom, but
  keep it to the call site and the emergency argument (`bindHomeRing(`...`, emergency)`), not the body's spelling.
The suite stays lean; these are edits to the one test and one existing assertion, no new test class.

### L3 - PLAN.md's queue entry is not updated on the branch

The branch touches no `PLAN.md`. The entry "Home's contacts ring (A) approved, in build" (commit 52aebb2b) should go to
"built, awaiting QA" in the merge commit (user rule: mark done in the merge commit or the push right after). Fix: do it
in the merge, with this QA's result.

### L4 - an older line in `ui-polish.md` is now half stale

The "Depth on the view" bullet still says the contact avatar has `elevation` 2 dp and the badges are lifted "to the same
2 dp"; the new bullet right under it corrects this for Home, but the first one reads as the whole truth. Fix: add
"(Home's avatars: 4 dp, below)" to the first bullet, or reword it to "2 dp, 4 dp on Home".

## Not a finding (design notes, no action)

- A white 95 % ring on a pale tonal initial disc has little edge of its own; the shadow carries it there. The photo on
  a busy wallpaper, the user's case, is what the ring fixes. `<solid transparent>` in `bg_kid_contact_ring.xml` is
  redundant but harmless.
