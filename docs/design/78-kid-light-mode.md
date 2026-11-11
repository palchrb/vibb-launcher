# 78 - A light look for the kid's own screens: Mørk or Lys

Status: design note by the architect, 2026-11-09, on `main` at b9eff6b6, branch `design-78-light`. **Nothing is built.**
(Since 2026-11-10 step 1 is: "Step 1 built", below.)
Launcher only: no server, PWA or contract change. Mockups: `docs/design/78-mockups.html` (Meldinger and Kalender in
both looks side by side, the contact card - night in both -, the kid Settings row, the token table).
**Revision 2, 2026-11-09**, after the design QA (`docs/review/qa-78-design.md`: 0 Critical, 3 High, 7 Medium, 11 Low,
6 Nit) and the user's answers to the first six questions. Every finding is fixed, explained or dropped in §11, which
maps each one to where it landed. New in this revision:
- **no automatic switching**: the kid picks Mørk or Lys, nothing else (the user, below); Automatisk, its sun rule and
  its step are gone;
- every non-page screen is pinned to night as a constant (QA H1), and D2 says honestly what that changes;
- a change of look happens only at the kid's own tap in kid Settings, where no page can be on screen; it finishes every
  live page (H2, M3);
- nothing touches the choice before the first unlock (H3); the stored mode is re-applied at every unlocked start (M2);
- a throwaway **step 0** on the emulator and the Jelly Star decides go or no-go before anything is built (M7);
- **the contact card stays night** (the user, answer 4) - it is out of scope.

**Step 1 built, 2026-11-10** (branch `kid-look-1`): the mechanism, the tokens and Kalender, the kid Settings row in debug
builds only. Built and unit-tested; not yet seen running (`docs/testing/emulator.md` §6ah is its pass). Where the build
departs from this note or settles something it left open, a line marked "Step 1, 2026-11-10" says so at the place.
The code QA's fix round (`docs/review/qa-78-step1-code.md`) is marked "Fix round, 2026-11-10" the same way.

**Step 2 built, 2026-11-11** (branch `kid-look-3`, on revision 3's mechanism): Meldinger, the phone book, kid Settings'
lists, the kept thread text, L5 settled, the row in every build. Built and unit-tested, not yet seen running
(`docs/testing/emulator.md` §6aj). §8's "Step 2, 2026-11-11" paragraph has what it is and where it departs.

**Addition, 2026-11-10 - the phone book is a page too.** Since the batch `sms-follow-book-ground` the phone book is no
longer on the wallpaper: it has Dagen's plain ground (`kid_ground`), on the user's wish ("bare lys/mørk bakgrunn akkurat
som for kalender"), and the user expects it to follow the same choice ("jeg regner med vi har samme lys/mørk modus som
kalender og sms?"). So the phone book (`activity_phone_book`, its search field, rows, empty state and request form) joins
Meldinger and Kalender in scope and takes the page tokens; where this note says the phone book shows the wallpaper or is
out (§2's table and the scope sentence below), read it as in. The caption in kid Settings names it too ("Gjelder
Meldinger, Kalender, Telefonbok og listene her i Innstillinger"). To settle in the build, with the user: the contact
card stays night by their answer 4, which was given while the phone book showed the wallpaper - over a Lys phone book it
is a night card on a paper page; show both in step 0 before choosing.

**Revision 3, 2026-11-11 - the mechanism changes (§12; proposed, nothing built, a spike first).** Step 1's two emulator
passes showed what the per-app night mode costs: the launcher's language lost at reboots, a change of look the system
refuses for a process's whole life, seconds on the main thread at boot. §12 replaces it: no per-app night mode at all;
each page that can top a task of its own gets two manifest activities, a night one and a light one, and the launcher
picks the class. D5, §5.3's first bullet and its "Decision", §5.4's steps 2 and 3, §5.5's first paragraph, §8's step 0
and §9's first two risks are read with §12.8 from here on; the tokens, the pages' own AppCompat mode, the night pin and
the kid Settings row stand.

The user's words (2026-11-09):
- On the own-bubble colour in the new message screens: "burde vi ha en mørk og en lys modus".
- On who decides: "kan ikke barnet selv få bestemme mørk/lys/følge telefonen i barnets settings meny? men da må det gi
  mening da, er vel ikke så mye mer enn meldinger og kalender den egentlig styrer, da bakgrunn settes andre steder?"
- On the first proposal (Mørk / Lys / Automatisk in kid Settings, Mørk the default): "Ja du kan sette igang".
- The answers to revision 1's questions: (1) the warm paper ground #F6F2EF; (2) the kid's own bubble the same peach in
  both looks; (4) the contact card **always night** - it sits over Home, which doesn't switch, and a white card would
  flash white on every tap; (5) nothing in the PWA; (6) Kalender and Meldinger leaving Recents at a change of look is
  OK. (Answer 3, the Automatisk fallback, is moot.)
- On Automatisk: "Egentlig er jeg ikke så opptatt av automatisk skifting av lys v mørk.. Her kan heller barnet få velge
  selv".

## 0. The shape

- The kid picks **Mørk** or **Lys** in kid Settings, in a new "Utseende" card under the background picker. **Mørk is
  the default**, and on Mørk the kid's pages look as today (D2 names the one exception: AppCompat's own ripples and
  controls on a phone whose system dark theme is off).
- The choice changes only the full-screen pages that paint their own solid ground: **Meldinger, Kalender (Dagen), and
  the Wi-Fi, Bluetooth and ringtone lists** opened from kid Settings (§2). Home, the phone book and kid Settings show
  the wallpaper; the contact card, the lock, the glance, the time-rule screen, the call screen and the emergency menu
  stay night.
- **No automatic switching** - no sunset rule, no clock: the look changes only when the kid taps the other choice.
- **Mechanism**: the light tokens are Android resources under `values-notnight/`; the launcher sets its own per-app
  night mode (`UiModeManager.setApplicationNightMode`; minSdk is 34) to the kid's choice; every non-page activity is
  pinned to night. **Hypothesis, checked by step 0 before anything is built**: Android uses the per-app night mode for a
  page's starting window and its bar icons, so a page opens in its look from the first frame.
- A change happens at the kid's tap in kid Settings, where no page can be on screen, and finishes every live page, so
  nothing can show a stale frame.
- **Stored on the phone only** (CE prefs, like the wallpaper pick), never read before the first unlock. The parent
  neither sees nor sets it: it is a preference, not a restriction.
- **"Følg telefonen" is dropped**: the kid can't reach Android's dark-theme switch in the kiosk, and Battery Saver
  turns it on by itself (D1).

## 1. What exists today (verified against the code)

- **One night palette**, `res/values/colors_kid.xml` (no `-night` or `-notnight` resource directory exists anywhere in
  the launcher): ground `kid_ground` #0C0C14, lift `kid_ground_lift` #1C1A2A, tile `kid_music_tile` #1B1B28, ink
  `kid_ink` #FFFFFF, `kid_core` #FBE4DC, `kid_core_dim` #C7FBE4DC, accent `kid_accent` #F0A884. `KidPaletteTest` parses
  that file and checks text pairs at >= 4.5:1 (WCAG, a translucent top composited first).
- **The bubbles** (design 77 step 2's fix round, branch `sms-screens`, not on `main` yet): `kid_message_out` = the accent
  peach, `kid_message_out_ink` #1B1B1F (8.7:1), `kid_message_out_dim` #5E4034 (4.7:1); `kid_message_in` #34324A (D2),
  white on it 12.3:1, `kid_message_in_dim` #A6A0B4 (4.9:1). `bg_message_selected_in` is a `kid_accent` ring,
  `bg_message_selected_out` a `kid_core` ring, `bg_message_picture` `kid_music_control`, `bg_messages_unread`
  `kid_accent`.
- **Themes** (`res/values/styles.xml`): `launcherBaseTheme` has parent `Theme.AppCompat.DayNight.NoActionBar`, so the
  bits AppCompat draws itself (the `?selectableItemBackground` ripple, `colorControlNormal`, the list divider, an
  unthemed switch, parent Settings' preference widgets) follow the phone's own dark-theme setting today; our colours
  don't, since they live in `values/` only. The base theme sets `android:textColor*` and `colorAccent` to the kid ink
  and accent, and `UIObject.modifyTheme` lays `colorThemeDefault` (the kid ground, ink, accent) and
  `Background.SOLID` over every `UIObjectActivity`'s theme on every `getTheme()`. The base theme's splash is
  `kid_ground` without an icon; `windowDisablePreview` is set, but Android ignores it for a launch from HOME - and our
  app **is** HOME, so Dagen and Meldinger (new tasks started from Home) open behind a `kid_ground` starting window
  today, invisible only because the page has the same ground. (Found by the QA in passing: `values-v29/styles.xml`
  redefines `launcherHomeTheme` without the `windowSplashScreen*` items, so on minSdk 34 Home's breathing splash is
  never used. Pre-existing, outside this design; for the main session. Fixed 2026-11-09: the copy was folded into
  `values/`, `SplashThemeTest` guards it.)
- **Theme references on pages**: `item_wifi_network.xml` and `item_bluetooth_device.xml` colour text and icons with
  `?android:attr/textColorSecondary` and their rows with `?attr/selectableItemBackground(Borderless)`.
- **No activity but `PinLockActivity` declares `uiMode`** in `configChanges`: Battery Saver's dark theme relaunches
  Home, the time-rule screen and even the call screen mid-call today. `PlayLinkBlockedActivity` is a plain `Activity`
  with no theme of its own (the application's `launcherBaseTheme`).
- **Direct boot**: the main process starts before the first unlock (`InCallActivity`, `KidInCallService`, the call
  screening and redirection services and `BootCallReceiver` are `directBootAware`). CE storage is first safe in
  `Application.initUnlocked()` (through `ensureUnlockedInit`: the component factory before a non-direct-boot component,
  and `ACTION_USER_UNLOCKED`).
- **Tasks**: Dagen's three screens (`taskAffinity .day`) and Meldinger's three (`.messages`, `PictureActivity`
  included) have tasks of their own, in Recents. The Wi-Fi, Bluetooth and sound-picker lists run in Home's task above
  kid Settings, so while kid Settings is in front none of them exists. Home is `singleTask` with `clearTaskOnLaunch`.
  Dagen opens on today after a minute away (`DAY_BACK_TO_TODAY_MS`). A thread's unsent text lives only in the field
  (saved instance state, no stored draft).
- **Hardcoded night** in the page drawables: ripples `#1FFFFFFF` (`bg_day_event`, `bg_day_event_now`,
  `bg_messages_button`, `bg_messages_delete`) and `#33FFFFFF` (`bg_kid_back_button`), the back arrow `ic_kid_back`
  white, the "+" glyph `ic_day_add` and the send glyph in `kid_ground` (it means "the ink on the peach", not "the
  ground"). `CalendarColor` has one ARGB table, read as `.argb` by the row dot, the legend's `DotSpan` and the event page.

## 2. Which screens

The rule: **a full-screen page that paints its own solid ground follows the choice; everything drawn over the
wallpaper or over Home, and everything that is night on purpose, stays night.**

| Screen | Today's ground | In or out | Why |
|---|---|---|---|
| Meldinger: list, "Ny melding", thread (`ui/messages`, `sms-screens`) | `kid_ground`, tiles, D/D2 bubbles | **In** | Its own solid ground; the bubbles prompted the request |
| Meldinger: a received picture (`PictureActivity`) | `kid_ground` | Out (night) | A photo is seen best on dark |
| Kalender: day view, event, "Ny avtale" (`activity_day`, `activity_event`, `activity_event_edit`) with its date/time pickers (`VibbPickerDialog`) | `kid_ground`, `kid_music_tile` rows | **In** | Its own solid ground; the user named it |
| Wi-Fi networks, Bluetooth devices, ringtone/message-sound picker (opened from kid Settings) | `kid_ground` | **In** | Solid-ground lists; the Wi-Fi password dialog (`KidAlertDialog`) follows its page |
| Contact card (`sheet_contact`, over Home and the phone book) | `kid_ground_lift` | Out (night) | The user, answer 4: it sits over Home, which doesn't switch, and a white card would flash white at every tap; design 34 P3's "the contact sheet is night, never a white surface" stays |
| Home (`activity_home`), its music and call cards | the wallpaper (`WallpaperGround`) | Out | The kid's background is the look there (ui-polish.md "Drawing") |
| Phone book (`activity_phone_book`) | the wallpaper | Out | As Home |
| The kid's contact picture (`KidPhotoActivity`, design 79 §5.2) and its "Bilde av …" sheet (`sheet_kid_photo`) | `kid_ground`, the sheet `kid_ground_lift` | Out (night) | A photo is framed best on dark, as `PictureActivity`; the sheet sits over the contact card, which stays night |
| Kid Settings (`activity_kid_settings`) | the wallpaper | Out | As Home; the new row shows a small preview instead (§7) |
| App drawer (`ui/list`, MIT) | `kid_ground` via `colorThemeDefault` | Out | Home's swipe-up: it belongs with Home; MIT code takes no new GPL code |
| PIN lock and its glance (`PinLockActivity`, `view_lock_glance`) | wallpaper under a black scrim, a black stand-in (design 32) | Out | Night on purpose; design 32's black-or-lock rule must not move |
| Time-rule screen (`LockActivity`) | `kid_ground` | Out | A calm night block screen, the glance's look (round L6) |
| Call screen (`InCallActivity`, `InCallTheme`) | the night gradient | Out | Night on purpose; it must never relaunch in a call |
| Emergency menu (`sheet_emergency`), the 112 confirms | `kid_ground_lift` | Out | Emergency looks the same everywhere, the lock included |
| Boot cover, VoIP wake, the "link blocked" notice, Legal, parent Settings | framework night / `kid_ground` | Out | Not the kid's pages, or seconds long |
| Vibb music (another app) | its own night | Out | Its own package; the per-app night mode never reaches it |

## 3. Decisions

**D1 - Two choices: Mørk and Lys.** No "Følg telefonen": in lock task, Quick Settings has no tiles at all on real
hardware, whatever the feature bits (`docs/notes/launcher/gotchas-and-history.md`, "LOCK_TASK_FEATURE_NOTIFICATIONS
does not bring back Quick Settings tiles"), and Android's Settings app is not on a managed phone's allowlist (the
server never adds it by itself, `device_api::call_and_text_roles` even refuses it). So the phone's dark theme is
whatever the parent left it at during setup - and Android's Battery Saver forces it on by itself (AOSP's
`UiModeManagerService` computes night while power-save is on). "Følg telefonen" would mean "your pages turn dark when
the battery is low", with nothing the kid can see or change. No "Automatisk" either: the user, 2026-11-09, "Her kan
heller barnet få velge selv".

**D2 - The default is Mørk, and what that changes is named.** The night page tokens are references to today's tokens
(`kid_page_ground` = `@color/kid_ground`, ...; two ripple tokens so `#1F` and `#33` stay apart), so our colours on
every page are today's; `KidPaletteTest` pins each reference. Every non-page activity is pinned to night (§5.3), so on
a phone whose system dark theme is **on**, nothing at all changes. On a phone whose system dark theme is **off** (the
likely state after setup), the bits AppCompat draws itself on every screen - the `?selectableItemBackground` ripple,
`colorControlNormal`, unthemed switches and dividers, parent Settings' preference widgets - take their night form
instead of their day form. That is what a night UI should have had all along (a dark ripple on the night ground is
invisible today), and step 1's before/after screenshots show exactly those bits and nothing else.

**D3 - The scope is §2's "In" rows.** The row's caption says what it covers ("Gjelder Meldinger, Kalender og listene
her i Innstillinger. Hjem og kontaktkortet er alltid som før."), and the row shows a small preview, so a tap in kid
Settings - which itself doesn't change - visibly does something. *Why:* the user's "er vel ikke så mye mer enn
meldinger og kalender den egentlig styrer".

**D4 - The light palette is "Vibb dag"** (§4): the warm paper ground #F6F2EF (the user, answer 1) with white tiles,
the night ink, the PWA's dark peach for accent text and its lighter peach fill for buttons, the kid's own bubbles the
same peach in both looks (answer 2). Calm and adult (the "Launcher look" rule): no new hue, no saturated surface.

**D5 - The mechanism is the per-app night mode plus `values-notnight` tokens, every other screen pinned to night**
(§5), provided step 0 confirms the starting window. The rejected alternatives are in §5.6.

**D6 - The look changes only at the kid's tap, and never while a page can be seen.** The tap is in kid Settings, so
no page is on screen then; the change finishes every live page and closes the page tasks (§5.4), so nothing comes back
through an old-look snapshot. *Why:* a relaunch in front of the kid is the flash the user doesn't want.

**D7 - The choice is the kid's and stays on the phone** (§6). No PWA control, no status-report key (the user, answer 5).

## 4. The palette

### 4.1 Page tokens

New file `res/values/colors_kid_page.xml` (the night, every value a reference to today's token or today's literal)
and `res/values-notnight/colors_kid_page.xml` (the day). Only page screens use `kid_page_*` (a scan test, §8); since
every non-page screen is pinned to night, a `kid_page_*` reached anywhere else is today's night value anyway. Two
colour groups are the same in both looks and stay unqualified in `colors_kid.xml`: `kid_on_accent` #0C0C14 (the glyph
and text on a peach fill; today's `kid_ground` in that role) and the own-bubble trio.

| Token | Role | Night (= today) | Day | Day contrast (on) |
|---|---|---|---|---|
| `kid_page_ground` | the page | `kid_ground` #0C0C14 | **#F6F2EF** | - |
| `kid_page_tile` | Dagen rows, list rows, the compose field, the event card | `kid_music_tile` #1B1B28 | **#FFFFFF** | 1.11:1 to the ground (iOS's grouped white-on-grey is the same) |
| `kid_page_edge` | a 1 dp hairline on the compose field (an input must be identifiable) | #00000000 | **#24000000** | the field's edge |
| `kid_page_lift` | the in-screen confirm, dialogs | `kid_ground_lift` #1C1A2A | **#FFFFFF** | the dim behind sets it off |
| `kid_page_control` | a picture's place in a bubble | `kid_music_control` #2A2A3A | **#FFFFFF** | stands off the received bubble (1.2:1) and the peach |
| `kid_page_ink` | titles, text | `kid_ink` #FFFFFF | **#1B1B1F** | 15.4 ground, 17.2 tile |
| `kid_page_core` | an event's description and place | `kid_core` #FBE4DC | **#1B1B1F** | as ink |
| `kid_page_dim` | second lines, times, captions | `kid_core_dim` #C7FBE4DC | **#5E5752** | 6.4 ground, 7.1 tile |
| `kid_page_accent` | start times, links ("Ny melding"), "Ukjent avsender", the unread dot, rings, the selected received bubble's ring | `kid_accent` #F0A884 | **#A64B25** (the PWA's `--tint`) | 5.2 ground, 5.8 tile, 4.6 received bubble; dot and ring 5.8 (>= 3:1) |
| `kid_page_accent_fill` | the "+", send, switch on | `kid_accent` #F0A884 | **#E8895E** (the PWA's `--tint-fill`) | `kid_on_accent` on it 7.6 |
| `kid_page_selected_out` | the selected own bubble's ring | `kid_core` #FBE4DC | **#1B1B1F** | 8.7 on the peach |
| `kid_page_button` | the tonal pill ("Avbryt", "Endre") | `kid_sheet_button` #14FFFFFF | **#12000000** | ink 14.7 |
| `kid_page_back` | the round back disc | `kid_back_button` #1FFFFFFF | **#0F000000** | the arrow is `kid_page_ink` |
| `kid_page_divider` | list dividers | `kid_divider` #1AFFFFFF | **#1A000000** | - |
| `kid_page_ripple` | the rows' and pills' ripple | #1FFFFFFF | **#14000000** | - |
| `kid_page_ripple_strong` | the back disc's ripple | #33FFFFFF | **#1F000000** | - |
| `kid_page_message_in` | a received bubble | `kid_message_in` #34324A | **#E8E4EE** | ink 13.7 |
| `kid_page_message_in_ink` | its text | `kid_ink` #FFFFFF | **#1B1B1F** | 13.7 |
| `kid_page_message_in_dim` | its time | `kid_message_in_dim` #A6A0B4 | **#5E5870** | 5.4 |
| `kid_page_alert` | "Ikke sendt - trykk ...", "Slett" in Dagen | `kid_missed_text` #FF8A8A | **#B3261E** | 5.9 ground, 6.5 tile |
| `kid_page_danger` / `_text` | the "Slett" pill | `kid_emergency_tonal` #3A1E24 / #FF8A8A | **#FBE3E1 / #A1241C** | 6.2 |
| (unqualified) `kid_message_out`, `_ink`, `_dim` | the kid's own bubble | #F0A884, #1B1B1F, #5E4034 | **the same** | 8.7, 4.7 |

`kid_page_day_mine` (the kid's own events' green start) removed 2026-11-09, the user: every start time is the accent.

Step 1, 2026-11-10: one more unqualified colour, `kid_on_accent_press` #33000000 - the press on a peach fill ("+",
"Lagre"), the same in both looks; it was a literal in those drawables. Two drawables keep a literal white because they
are masks that are only ever drawn tinted, `bg_day_dot` and `ic_kid_back`; `PageTokensScanTest` exempts them by name and
asserts each tint site. `kid_page_selected_out` on the peach is 1.6:1 in Mørk - today's `kid_core` ring, unchanged (D2);
`KidPaletteTest` holds it to 3:1 in Lys only.

The own bubble stays peach in both looks: "my messages are peach" is the one thing the kid learns, and the peach reads
on paper as well as on night (1.8:1 to the day ground as a shape; its text 8.7:1). The received bubble's day value
keeps D2's lilac hint so the two looks are one family. The tiles stay without an edge (1.11:1, like iOS's grouped
lists); only the compose field, an input, gets the hairline.

### 4.2 Kalender's colours

- Start times `kid_page_accent`, the kid's own too; all-day `kid_page_dim`; "Pågår nå"'s 1.5 dp ring
  `kid_page_accent` (5.8:1 to the white tile; it repeats the words, so colour isn't the only signal).
- **The calendar dots** get a day table beside the night one: `CalendarColor` gains `dayArgb`, each the same hue made
  deep enough for **>= 3:1 on the white tile and the ground** (WCAG 1.4.11 for a graphic): sky #3A76B0 (4.8),
  forest #3C8650 (4.5), sun #A87414 (4.1), berry #C24E48 (4.7), sea #2B8580 (4.4), lavender #9455A2 (5.2), sand
  #8C7650 (4.4), slate #6C6C7A (5.2), own #C2622C (4.1). **One resolver**, `CalendarColor.argb(context)` (by the
  context's night bit), is the only way `ui/day/` reads a dot colour: the row dot, the legend's `DotSpan` and the event
  page. The glance gets the night value through its pinned context. The parent's PWA swatches stay the night values.
- The "+" disc: `kid_page_accent_fill` with the glyph in `kid_on_accent`.

Step 1, 2026-11-10: `CalendarColor.argb` stays the night table as a property, and the lock's glance keeps reading it
directly (it is no page and never day) instead of going through `argb(context)`; the resolver is the only way in
`ui/day/` (`PageTokensScanTest`). The "+" disc is 2.3:1 to the paper ground in Lys - this note's value; its glyph is
what identifies it.

### 4.3 The contact card

Out of scope (the user, answer 4): it keeps `kid_ground_lift` and every colour of design 34 P3, and `KidPaletteTest`'s
"the contact sheet is night, never a white surface" stays as it is. As a dialog of a pinned (night) activity it
resolves night whatever the kid picks.

### 4.4 Dialogs and pickers on a page

`KidPageTheme` (§5.2) sets `alertDialogTheme` to `KidPageAlertDialog` - `KidAlertDialog` on page tokens - so the
Wi-Fi password dialog and Dagen's delete confirm follow the page. `VibbPickerDialog` gets a day twin in
`values-notnight/styles.xml` with parent `Theme.Material.Light.Dialog`; a qualified style replaces the whole style and
never merges with the unqualified one, so the twin names every item again: panel `kid_page_lift`, text
`kid_page_ink`, weekday row `kid_page_dim`, header and selection `kid_page_accent_fill` with `kid_on_accent`, and the
two picker styles (`PickerThemeTest` reads both files). Every other screen keeps `KidAlertDialog` (night).

Step 1, 2026-11-10: only `VibbPickerDialog` has a twin. `VibbDatePicker` and `VibbTimePicker` name page tokens only
(their parents are the framework's widget styles, which have no light form of their own to pick), so they resolve by
the look without one. The twin's parent differs from the night style's by design, so `SplashThemeTest`'s rule "a
qualified style keeps its parent" names it as its one exception. Not settled here: in Lys the framework date picker
draws today's number, while it is not the chosen day, in `colorControlActivated` - the peach fill, 2.6:1 on the white
panel; step 1's pass looks at it.

## 5. The mechanism

### 5.1 Resources

- `values/colors_kid_page.xml` (night) and `values-notnight/colors_kid_page.xml` (day) - the only qualified colours.
  `notnight` matches when the configuration's night bit is "no". Pages carry the kid's choice; every other activity is
  pinned to night (5.3), so only pages ever resolve the day file.
- `values/bools_kid_page.xml`: `kid_page_light_bars` false; `values-notnight`: true.
- Every page layout and drawable names `kid_page_*` (or `kid_on_accent` and the unqualified bubble colours); every
  literal or night token in them is replaced. Code that sets a colour on a page reads it through the activity's
  context (`getColor(R.color.kid_page_...)`), which resolves in the page's mode.

Step 1, 2026-11-10: what Kalender shares with screens that are not pages yet is on page tokens already - the header
(`include_kid_header`, `bg_kid_back_button`; the back arrow tinted `kid_page_ink` in the layout rather than by
`KidHeader` in step 2), `KidManageLink`, and `bg_day_event`, `bg_day_add`, `bg_day_save`, `ic_day_add`, which
Meldinger and the phone book also use. Those screens are pinned to night, where each page token is today's value, so
they draw as before.

### 5.2 The page theme and its colour overlay

- `KidPageTheme` (parent `launcherBaseTheme`), set in the manifest on the eight page activities (`DayActivity`,
  `EventActivity`, `EventEditActivity`, `MessagesActivity` - the list and "Ny melding" -, `ThreadActivity`,
  `WifiNetworksActivity`, `BluetoothDevicesActivity`, `KidSoundPickerActivity`): `windowBackground` and
  `windowSplashScreenBackground` = `@color/kid_page_ground`, `windowLightStatusBar`/`windowLightNavigationBar` =
  `@bool/kid_page_light_bars`, `enforceNavigationBarContrast` false, `alertDialogTheme` = `KidPageAlertDialog`.
- **`PageColors`**, a theme overlay applied by the page base class in `getTheme()` after `UIObject.modifyTheme`, on
  every call (`modifyTheme` re-applies `colorThemeDefault` each time): `android:colorBackground`, `android:textColor`,
  `textColorPrimary`, `textColorSecondary`, `textColorHint`, `colorAccent`, `colorPrimary`, `colorControlNormal`,
  `colorControlActivated` and `colorControlHighlight`, all `kid_page_*`. That is what colours the Wi-Fi and Bluetooth
  rows' `?android:attr/textColorSecondary` text and icons and their `?attr/selectableItemBackground` ripples.
  `KidPaletteTest` checks the overlay's text pairs in both files.

Step 1, 2026-11-10: `PageColors`' values - `colorBackground` and `colorPrimary` the ground; the four text colours and
`colorControlNormal` the ink (as `launcherBaseTheme`, whose three other text colours all point at `textColor`);
`colorAccent` and `colorControlActivated` `kid_page_accent`; `colorControlHighlight` `kid_page_ripple_strong`
(AppCompat's own dark ripple is the same 20 % white at night).

Fix round, 2026-11-10 (code QA M1): `enforceNavigationBarContrast` is `@bool/kid_page_nav_contrast` - true at night,
false in `values-notnight` - not false in both looks. Kalender had the default (true) before the step, so with 3-button
navigation the system's scrim behind the buttons stays in Mørk (D2: Mørk is today's) and is off only on the paper. The
window's two grounds are no difference in Mørk either, on a release build or any other: `launcherBaseTheme` already had
`windowSplashScreenBackground` = `kid_ground`, and Kalender's `windowBackground` was the framework's
`?android:attr/colorBackground`, which the base theme sets to `kid_ground` (`Theme.Material` and its Light twin both
define it so, and AppCompat 1.7.1's API 21+ themes add no window background of their own; the QA read #303030 there).
`KidPageTheme` names both as `kid_page_ground` so that Lys gets the paper. Read from the resources, not seen running.

### 5.3 Who sets the mode

- **The app's night mode** - `UiModeManager.setApplicationNightMode(MODE_NIGHT_YES | MODE_NIGHT_NO)`, never AUTO.
  The system keeps it per package (across reboots and updates), so the music app never sees it. **Step 0's
  hypothesis**: WM merges it into a page's starting window configuration (Shell draws the splash from it) and the
  splash window's bar ink, so a page is in its look from the first frame. That is the only reason the call exists.
- **A page's own mode** - the page base class sets AppCompat's local night mode to the **applied** mode in
  `attachBaseContext`, before `super.attachBaseContext`, so AppCompat's `attachBaseContext2` builds the override
  configuration from the start: no `recreate` and one `onCreate` per open (step 1's recording confirms it). It is a
  belt: if the system call were late or refused, the content is still right. A page does **not** declare `uiMode`.
- **Every other activity is pinned to night as a constant**: `UIObjectActivity`'s `attachBaseContext` sets AppCompat's
  local night mode to `MODE_NIGHT_YES` (the page base class overrides it), and so do `PinLockActivity` and
  `InCallActivity`, which extend `AppCompatActivity` directly. It reads nothing - there is no reliable public reading of
  the phone's own applied night bit once an app override exists, and a pin to the phone's mode would let shared
  resources (`include_kid_header`, `bg_kid_sheet`, the badges, `bg_kid_card`, `ic_kid_back`) resolve their day values
  on Home, kid Settings, the card or the emergency menu. Every one of them also declares `uiMode` in `configChanges`, so
  a change never relaunches it (and Battery Saver no longer relaunches the call screen). D2 names what the pin changes.
  `PlayLinkBlockedActivity` (a plain `Activity`) gets an explicit framework night theme like `BootCoverTheme`, so no
  AppCompat light window shows before its content; `BootCoverActivity` and the translucent ones already use framework
  themes that don't read the night bit.

Step 1, 2026-11-10: "every one of them" is every non-page activity in the manifest, the plain `Activity`s on framework
themes and the `:bootcover` one included (25 activities, 22 of them with `uiMode`): a rule without exceptions is the
one `PageManifestTest` can hold. `PlayLinkBlockedTheme` is `Theme.Material.NoActionBar` on `kid_ground` with no
preview, the fade, Nunito, the accent and a framework coloured button with night text, as `launcherBaseTheme` gave the
notice; its look is in step 1's pass.

Emulator pass, 2026-11-11 (`docs/testing/emulator.md` §6ah, "The run of 2026-11-11"): the app's night mode and the
launcher's own language (`LauncherLocales`, `LocaleManager`) are one per-package record in the system, and the two
calls get in each other's way. Seen on `jelly36`: after a reboot the launcher's language was gone (`get-app-locales`
`[]`, Home and the PIN lock in the system's language), and `setApplicationLocales` at Home's resume was refused by the
system server (`IllegalStateException: Can't change activity type once set`, under
`PackageConfigurationUpdaterImpl.updateConfig`) for as long as an activity in a task of the launcher's own - the PIN
lock, Kalender's page - was the process's newest; the first Home resume without one set it again. "The system keeps it
per package" above is true of the night mode for a boot and across reboots; of the languages only for a boot.

**The cause, 2026-11-11** - read in AOSP `frameworks/base` at tag `android-16.0.0_r1` (the line numbers of the seen
stack are that tag's), not seen running beyond the lines above:

- **The reboot** (`PackageConfigPersister.updateFromImpl`, the same lines from Android 13 to `main`). The record in
  memory is kept whole, so nothing is lost while the phone is up. The record that goes to disk is filled by
  `!updateNightMode(..) && !updateLocales(..) && !updateGender(..)`: the chain stops at the first value that changed,
  so a new write record of a package with a night mode gets the night mode alone, and `WriteProcessItem.saveToXml`
  writes only what it holds. The languages get into the file only when a language change comes within three seconds
  of another write of the package (`PersisterQueue.PRE_TASK_DELAY_MS`; a waiting write record is reused). And the
  first call of every boot counts as a change (the gender is an `int` 0 in the call and `null` in a record just read,
  `loadUserPackages`), so it writes the file again - without the languages. It is no race and no order of the two calls
  avoids it: with a night mode set, a file that has the languages is replaced by one without them at the next boot's
  first call. On this branch that is every phone (the unlocked start's call is unconditional); it shows only where the
  phone's language is not the launcher's. The file is read at the user's unlock (`ActivityTaskManagerService`'s
  `loadRecentTasksForUser`), and its values reach a process when it is added and an activity when its record is made
  (`updateConfigIfNeeded`) - a process started before the unlock gets none.
- **The refusal** (`ConfigurationContainer.applyAppSpecificConfig`, `WindowConfiguration.setActivityType`). Both calls
  go through `PackageConfigurationUpdaterImpl.commit`, which first applies the values to every process of the app
  (`updateConfig`) and only then stores them (`updateFromImpl`). A process's container copies its override
  configuration into a scratch object it keeps between calls, and that configuration is the one of the activity the
  process follows - the one added to it last that was not finishing then (`WindowProcessController`'s
  `updateActivityConfigurationListener`, looked at again only when an activity is added or removed). The scratch object
  keeps the activity type of the last call that got through, and a system-server `WindowConfiguration` refuses to go
  from one defined type to another. So a call is refused when the followed activity is of another type - home or
  standard - than at the last call that got through; activities in Home's task are home-type (`Task`'s
  `onDescendantActivityAdded`), the PIN lock, Kalender, Meldinger and the call screen standard. The night mode took the
  same path and got through because kid Settings is in Home's task. Refused, nothing is stored - neither in memory nor
  on disk - so a later process start does not have the value either.
- **Main has the refusal too, narrowly** (older than this design): the first language call of a process's life always
  gets through; a second change in the same life is refused while the followed activity's type is not the first one's
  (a parent changing the language twice, the second time with Kalender's task the newest). Main never loses the
  language at a reboot: it has no night mode, so its file has the languages.

**Decision, 2026-11-11** (inside the mechanism; `ui/LauncherLocale.kt`'s `LauncherLanguage` over a host,
`LauncherLocaleTest`):

- **The language is asked for again at every unlocked start**, unconditionally, right after the night mode
  (`Application.initUnlocked`): the system gets it back in memory before an activity of that process exists, whatever
  the file had. After the night mode because that is the one order in which both land in one write: every second boot
  starts from a file without the languages, the call brings them back and the file written then has both; the boot
  after starts with them and writes them away again. In a process started at the
  unlock both calls are its first and follow the same activity, so neither is refused. "Follow the phone" is asked for
  only while the system still holds a language. Nothing during a call, as every switch. The order - the night mode,
  then the language, before the rest of the setup - is load-bearing: `PageLookScanTest` holds it as a source scan
  (2026-11-11, the code QA's fix round). A refused ask here closes nothing and is left to Home's resume
  (`LauncherLocaleTest`).
- **A switch at Home's resume is asked for as before; refused, Kalender's and Meldinger's tasks are closed and it is
  asked for once more** (`languageClosesTask`). A page stopped behind Home is what the process follows for as long as
  its task lives, which is the refusal that was seen to last; removing a stopped task takes its activities out of the
  process at once, and the process then follows Home. "At once" holds for a stopped page only (the code QA's M1,
  2026-11-11, read in `WindowProcessController`, `Task` and `ActivityRecord`): a page the kid came Home straight from
  is paused and still visible in that resume, the removal only marks it finishing, and the system destroys it once
  Home has reported idle - so the second ask in the same resume is refused too. The tasks are closed only after a
  refusal, not before the first ask: in a process whose last call got through under a page, the page behind Home is
  what lets the call through, and closing it first would turn that into a refusal. A switch that got through at once
  leaves the page tasks alone, as on main.
- **A resume that ends refused asks one last time two seconds later** (`LANGUAGE_RETRY_DELAY_MS`, M1's fix), while
  Home is still in front and no call is live: one ask, no loop; Home's `onPause` drops it and the next resume has its
  own. Two seconds because the activity just left is destroyed after the transition to Home, when Home has reported
  idle - well under a second on a phone, and Android's own fallback for an idle report that never comes is ten
  seconds; an idle handler of the launcher's own in `onResume` would run before Android's and so be too early. Too
  early only means one more refusal, and the next resume asks again. Departure from the QA's wording, 2026-11-11: the
  retry follows every resume that ends refused, not only one that closed a task - the PIN lock just left is the same
  wait with nothing to close.
- **A close that did not help is not made again** (the code QA's M2): when the retry after a close was refused too,
  the process remembers that wanted language and later resumes ask without closing, so the kid's open page is not
  thrown away at every Home resume. An ask that gets through, or another wanted language, clears it. A retry that
  never ran (Home left within the two seconds) decides nothing, so the resume after it may close once more: with a
  standard-type activity that stays (below) and a kid who leaves Home within two seconds every time, the close can
  repeat. Held the other way, a language could stay stuck behind a page the kid opened again; the repeat was taken as
  the smaller cost.
- **What is still refused** (inferred from the source; its first two cases seen in the second pass, below): a call
  refused through the close and the retry leaves the system's value as it was, and every later Home resume asks again.
  Three cases. A standard-type activity of the launcher's outside the two page tasks that stays alive and was added to
  the process last - the phone book opened by a `tel:` link in another app's task, for one: nothing the launcher closes
  helps, the page tasks are closed once for nothing, and the change waits until that activity is gone. A process whose
  last call got through under a standard-type activity - started again under a page, the PIN lock or a call, or a call
  at Home's resume right after one of them - while Home is what it follows: every plain Home resume is refused, and the
  change gets through only at a resume right after the PIN lock, a page or a call (the first ask, under the activity not
  yet destroyed), or at the next process start (the unlocked start's ask); the first refusal also closes the page tasks,
  once, for nothing. And the PIN lock, a page or the call screen just left where the retry was dropped or ran too early:
  the next resume without such an activity gets through (the pass saw the refusals end at the first Home resume without
  one). The app cannot read which case it is in; a parent's change of language can so wait until the kid next unlocks.
  The screens stay in the old language meanwhile, never in a mix.
- **The application's own texts** (the code QA's L3; inferred when written - seen and fixed since, the second pass
  below): on the boot whose file has no languages, the language reaches the process after `Application.onCreate` has
  begun, so what is read from the application's context there - the notification channels' names
  (`createNotificationChannels`), an ongoing notification's text - may be in the phone's language until the next process
  start. To be looked for in the pass; if seen, the small fix is the channels once more on `ACTION_LOCALE_CHANGED`.
- **Costs**: a refused change closes Meldinger too, and until step 2 keeps a thread's unsent text (§5.4.4) a
  half-typed message is lost at it - a parent's change of language that was refused, rare. Every second boot the first
  activity may be created once more when the language comes back (Android's flag `expediteActivityLaunchOnColdStart`
  decides whether Home is attached before `Application.onCreate` ends; its state on a phone is not known). A process
  that was started before the unlock and has its first activity mid-launch when the files open
  (`KidAppComponentFactory`) may show that activity once in the phone's language before it is created again. If a pass
  shows either as a frame the kid sees, the next belt is the wanted language in each activity's `attachBaseContext`,
  beside the night mode's; it is not built, since nothing seen calls for it yet.
- **Open, for step 2** (inferred from the same source; seen in the second pass, below, on a natural route):
  `setApplicationNightMode` can be refused the same way. In a process whose last call got through under a
  standard-type activity, a change of look from kid Settings
  (home-type) is refused; so is a change that waited for Home's resume right after a page was left, in a process whose
  last call got through under Home. The pages still draw in the stored look (their own AppCompat mode); their starting
  windows keep the old one until the next process start. Step 1's release builds have no change of look (the row is
  debug-only), so it has to be settled before the row is shown. A gate on the app's side alone (ask only while no
  activity outside Home's task is alive in the process) does not settle it: a process that already holds the standard
  type refuses under Home for the rest of its life. What would: the gate together with never asking under a
  standard-type activity at all (the unlocked start's two calls included, which then wait for Home in a process started
  under a page, the PIN lock or a call - a cost to step 0's ground for that start's first window); or each
  activity's `attachBaseContext` carrying the language as it carries the night mode, so nothing the kid sees depends
  on a call that can be refused (texts from the application's own context - notifications, toasts - would still); or
  not using the system's per-app night mode, which gives up the starting window's ground and is another mechanism than
  this design's. None is built; the choice is the design's.

**The second pass, 2026-11-11** (`jelly36`, 9ca55e8e debug, seven cold boots; `docs/testing/emulator.md` §6ah, "The
second run of 2026-11-11") - what it showed about the cause and the fix. Seen there unless it says otherwise.

- **The fix holds.** After every boot the launcher had its language from the unlocked start's call on - four boots
  whose record had lost the languages, two that had them, Android's own lock screen with the process alive before the
  unlock included - with no refusal at the unlocked start and no launcher screen in the phone's language in any
  pictured frame. Home was created once more by Android on two of the four boots without the languages (the named
  cost), with no English frame pictured; on one of the two the screen was behind Android's own ANR dialog meanwhile.
- **The cause holds.** The control: over one boot the launcher (a night mode set) came back without its languages and
  the clock app (none set) with them. The boots did not strictly alternate once changes of look and language were made
  in between; the stored record cannot be read without root, so why is not known.
- **The refusal's model held every time it was tried**: every refusal and every pass followed "the followed
  activity's type against the scratch configuration's". Main's build refused a second change at Home straight from
  Kalender and let it through at the phone book and Back with the card still in Recents - the refusal is older than
  step 1, and narrower than "while the card lives".
- **Home straight from a page was repaired in the same resume** (one warning, the tasks closed, the language set a few
  milliseconds later; the page destroyed about 350 ms after): the removal took a paused page out of what the process
  follows at once on this image, against the code QA's reading of the source (M1). The late ask was seen to work in
  another case (a page left with Back after a close in vain).
- **"What is still refused" was seen, both cases**: with a standard-type task of the launcher's that stays, the
  language did not land for as long as the task lived and Kalender's card was closed once, not again; in a process
  holding the standard type, a plain Home resume was refused (late ask included) and Home straight from Kalender set
  it. **And a cost of "a close that did not help is not made again"**: once the lingering task was gone, Home straight
  from Kalender was still refused - the page is no longer closed for that language, so it stays the followed
  activity - until the kid left the page with Back (the late ask), the PIN lock or a process start.
- **A change of look can be refused, and on a natural route** (the "Open, for step 2" line; seen twice, on two boots
  and both lock screens). A process started at boot for a service makes the unlocked start's two calls with no
  activity. A parent's change of language that lands while the phone is locked is then set at the unlock's Home resume
  **at once, with the PIN lock still the followed activity** - this build's first ask - and that fixes the standard
  type for the process's life: every change of look from kid Settings is refused (`E PageLook: The app's night mode
  wasn't set`), the pages draw in the stored look and Kalender's starting window shows the old look for about 330 ms,
  until the next process start. In step 1's release builds there is no change of look, so what remains there is the
  language's half: in such a process a later change of language waits for a Home resume right after a page, the PIN
  lock or a call. **Proposed, not built** (it is the mechanism): no call of the launcher's is made while an activity
  outside Home's task is alive in its process. Home's resume then waits for the PIN lock or the call screen just left
  to be destroyed (the late ask's place) and closes a page's task before it asks, not after a refusal - the reason for
  "only after a refusal" above is a process that holds the standard type, which this rule never makes - and the
  unlocked start's two calls wait for Home in a process started under such an activity. That is the first way out
  named in "Open, for step 2" above, now with the route that makes it needed; its cost to step 0's ground for that
  start's first window stands, and the choice is the design's.
- **The application's own texts were in the phone's language** (L3) on three boots whose record had no languages, and
  on the boot where the process was alive before the unlock although the record had them: the channels the unlocked
  setup names, in the app's notification settings. **Fixed after the pass, not seen running**:
  `Application.onConfigurationChanged` names the channels again when the process's languages differ from the ones
  they were named in. Departure, 2026-11-11: not on `ACTION_LOCALE_CHANGED` as the line above proposed - that
  broadcast is the phone's language; the app's own languages reach the process as a configuration change (read).
- **The two calls' time on the main thread** at a loaded emulator boot: the look's up to 2.6 s, the language's up to
  1.7 s (2-300 ms otherwise). On one boot with Android's lock screen the launcher got an ANR 20 s into
  `KidVpnService`'s start (the whole unlocked setup 11.5 s, the two calls 1.5 s of it) and was started again; the
  second process was right. One sighting on a host where SystemUI and the phone app get ANRs at every boot; whether a
  phone's boot comes near it is not known.
- **Not settled by the pass**: Android's flag `expediteActivityLaunchOnColdStart` (not on the image), a release build,
  a real phone.

### 5.4 When the look changes

The applied mode lives in memory and in CE prefs (`kid_look.applied`). It changes in one place,
`PageLook.choose(mode)`, called by the kid's tap in kid Settings; it only ever runs after `initUnlocked()`:

1. **The guard**: kid Settings is in front, so no page is on screen - the lists sit above kid Settings in Home's task
   and are gone while it is in front, and Kalender's and Meldinger's tasks are behind. The page base class still keeps
   a counter of **started** activities of the page tasks (`onStart`/`onStop`; `PictureActivity` counts too) and pure
   `mayChange(pageTaskStarted)` checks it; if it were ever above zero the change would wait for Home's `onResume`. It
   costs a few lines and makes the rule testable rather than assumed.
2. **The change**: `commit()` the new `choice` and `applied` first; then finish every **live page** (a registry the
   page base class keeps) and `finishAndRemoveTask` the two page tasks (`ActivityManager.appTasks` whose root is
   `DayActivity` or `MessagesActivity`); then `setApplicationNightMode`.
3. **At every unlocked start** (`initUnlocked`): `setApplicationNightMode(applied ?: dark)` unconditionally, writing
   `applied = dark` first when it is absent (the first run after the update). Equal to the system's value it costs
   nothing; it repairs a value the system lost (it persists the package configuration asynchronously, so a reboot right
   after a change can drop it) or a crash between the two writes; and at a first run no page of this process exists
   yet. So a phone is on YES before any page can open, whatever the update window's screen state. Nothing reads
   `kid_look` before the first unlock: the main process runs direct-boot-aware call components then, and a CE read
   there would throw.
4. **What finishing costs**: Kalender loses nothing (it opens on today after a minute anyway); Meldinger reopens on its
   list. **A thread's unsent text is kept** per conversation (CE prefs, at most 1,600 characters, at most 50
   conversations - the oldest dropped -, cleared when sent or emptied, when the conversation is deleted, when its
   contact may no longer text, and when the phone goes unmanaged) and restored when the thread opens. Recents loses the
   Kalender and Meldinger cards at a change (the user, answer 6).

Step 1, 2026-11-10: three things this section left open. A tap on the look that is already applied stores the choice
only (it also withdraws a change that was waiting). The waiting change runs in `HomeActivity.onResume`
(`PageLook.onHomeResumed`, after the lock's dark return) **without asking `mayChange` again**: the page the kid just
left gets its `onStop` only after Home's `onResume`, so the count can still be one there, and Home in front is the rule
itself. And a page task is known by its base intent's component, `DayActivity` (the only activity started with
`NEW_TASK` into the `.day` affinity). Meldinger's task joins in step 2.

Fix round, 2026-11-10 (code QA M2, L1, L4): the rules are a pure class, `LookState`, over the host, so `PageLookTest`
runs the unlocked start, the tap and Home's resume themselves. At the unlocked start `applied` and `choice` are both
read before the system call - a change that was waiting for Home still waits after a process start. A system call that
fails is caught and logged in the host, at the start and in a change alike: the pages still draw in the stored look
(their own AppCompat mode), and only a starting window can be the other look's until the next unlocked start asks
again. Home's resume does not finish a change a process death cut short - `choice` equals `applied` from the change's
first write on -: a death between that write and the tasks' removal leaves Kalender's card in Recents with an old-look
snapshot until the next change, the page itself created again in the new look.

### 5.5 Status and navigation bars

A page draws edge to edge (targetSdk 36) under transparent bars; the icon ink comes from `kid_page_light_bars` in the
theme, so it is right from the starting window on (step 0 checks the splash's bars). `KidInsets` is unchanged. Home
and the wallpaper screens keep `WallpaperInk`'s choice. (The contact card's bar ink, QA M6, is moot: the card stays
night.)

Emulator pass, 2026-11-11 (measured on `jelly36`, §6ah): the late tint is not the theme's. A page gets a starting
window in the look's ground although `windowDisablePreview` is inherited, and with the launcher's process held that
window alone has white ink on the paper for 210-310 ms: the system's status bar follows the app transition late. From
Home's tile the white ink stands 255-498 ms on a cold open and 165-201 ms on a **warm** one (new beside step 0's cold
case); from the glance the ink is dark from the first light frame. No theme item changes it, and none was added.

### 5.6 Rejected

| Alternative | Why not |
|---|---|
| AppCompat's local night mode alone, per page (`values-night` tokens) | The starting window is drawn by the system from the app's configuration before our code runs: in Lys, a `kid_ground` frame before every page - the flash |
| Our own theme attributes (`?attr/kidPageGround`) with a day and a night theme | The same starting-window flash (the manifest theme is fixed), and every drawable and layout rewritten to attributes |
| `SplashScreen.setSplashScreenTheme` | Persisted for the whole package, so it would replace the PIN lock's black stand-in (design 32) |
| The phone's own dark theme (`UiModeManager.setNightMode`) | Needs a privileged permission, and would turn Android's own screens (dialer in a call, permission dialogs) too |
| Pinning the other screens to the phone's own mode | No public reading of it once an app override exists (`Resources.getSystem()` carries the override on 13+), and it would let shared resources resolve day on night screens (QA H1) |
| Recolouring views at runtime | Every adapter and drawable would need a second path; easy to miss one |
| Keeping page tasks behind and relaunching them when shown | Android shows the task's snapshot (the old look) until the relaunch draws - one frame of the other look |
| Revision 3, 2026-11-11: the per-app night mode kept, behind a gate - no call of the launcher's while an activity outside Home's task is alive in its process (§5.3, "The second pass") | It narrows the refusal and cannot close it: the launcher cannot see which activity the system has its process follow, a start can land between the check and the call, and one hit lasts for the process's life - the old look's starting window before every page. The record written without the languages, the two calls at every boot and Home created twice stay whatever the gate does (§12.5) |
| Revision 3: naming the starting window's theme per launch (`ActivityOptions`' splash-screen theme) | Hidden API (`KEY_SPLASH_SCREEN_THEME` is `@hide`; its public door is `SplashScreen.setSplashScreenTheme`, two rows up), and read only at a start - not when a task is brought forward again (§12.3) |
| Revision 3: one class per page on a translucent theme, so that no starting window is drawn at all | Read: a translucent theme gets no starting window. Inferred, not weighed further: the screen below stays visible and is not stopped while a page is up (Home or the lock keep running under Kalender, and §5.4's started count rests on `onStop`), and a slow open shows Home unchanged until the page's first frame instead of the look's ground at the tap. The one to look at if §12.7's spike fails |

Revision 3, 2026-11-11: the first two rows reject **one** manifest entry per page - "the manifest theme is fixed" is
true of an activity, not of a page. Two manifest activities per page, one per look, were not weighed in revisions 1
and 2; §12 takes them and drops the per-app night mode.

Android's own "force dark" doesn't touch a light page: it applies only while the configuration is night. Android 16's
force-invert accessibility option ("make more apps dark") is documented in AOSP as ignoring an app's `uiMode` override;
if a parent turned it on, Lys pages could be inverted. Step 0 and step 1 check it; nothing is built unless it shows.

## 6. Where the choice lives, and the parent

- CE shared prefs `kid_look`: `choice` (`dark` | `light`; absent or unknown = `dark`), `applied` (`dark` | `light`;
  equal to `choice` once a change has run) and the kept thread texts (§5.4). `commit()` on the kid's tap, like the
  wallpaper pick (`WallpaperStore`, `kid_wallpaper_id`). Read only from `initUnlocked()`, the pages and kid Settings (a
  source-scan test); never before the first unlock.
  Step 1, 2026-11-10: `HomeActivity` is a fourth caller, for the waiting change alone (§5.4); it is not
  direct-boot-aware, and `PageLookScanTest` names it. The kept thread texts are step 2's.
- **The parent sees and sets nothing** (the user, answer 5). *Why:* the standing rule is that every restriction is
  liftable; this is not a restriction but the kid's preference, like the ringtone. Nothing to lift, nothing to watch,
  no policy key, no status-report key, no contract file, no server or PWA change.

## 7. The kid Settings row

- A new section after the background section, before "Tilkobling, skjerm og lyd": heading **Utseende** /
  **Appearance** (`KidSettingsHeading`), a card (`bg_kid_card`) holding a two-way segmented control and a preview, and
  a caption under the card.
- **The control** is design 34 P9's sound control as it is - two segments, `SoundSegments`' track, radius and colours
  through `segmentColours`, each segment >= 48 dp: **Mørk** / **Dark** and **Lys** / **Light**. A `RadioGroup`;
  TalkBack reads the heading, the label and its state.
- **The preview** (decorative, `importantForAccessibility="no"`): a 56 dp strip with 12 dp corners in the chosen
  look's page ground, with a received bubble ("Hei! Kommer du?" / "Hi! Are you coming?") and an own one ("Ja, nå!" /
  "Yes, now!"), drawn through `PageLook.wrap(context, mode)` (a `ContextThemeWrapper` over a configuration context
  with that night bit) so it uses the real tokens. It is redrawn at the tap.
- **The caption** (12 sp, the wallpaper caption's style): "Gjelder Meldinger, Kalender og listene her i Innstillinger.
  Hjem og kontaktkortet er alltid som før." / "For Messages, Calendar and the lists here in Settings. Home and the
  contact card stay as they are."
- **Release builds show the row from step 2 on** (step 1 changes only Kalender, so its caption would not be true yet);
  debug builds show it in step 1 for testing.
- Step 1, 2026-11-10: the preview's container is `importantForAccessibility="noHideDescendants"` - with "no" alone
  TalkBack would still stop on the two bubble texts. The strip has bubble drawables of its own (`bg_look_preview_in`,
  `_out`, 12 sp text), not Meldinger's, which are step 2's; the received one ends in an ellipsis on a narrow screen.
  The caption is the 2026-11-10 addition's wording, the phone book named (the table below has the shipped strings
  since the fix round; the caption's own bullet above keeps revision 2's). It describes step 2 - in step 1 only
  Kalender follows the look - and is seen in debug builds only until then. The section is `gone` in the layout and
  shown, its listeners set, by `if (BuildConfig.DEBUG)`; what a release build does get in step 1 is the mechanism -
  the night pins, the unlocked-start call (always night, nothing can pick Lys), Kalender on `KidPageTheme`.
- Strings (en / nb), all new:

| Key | en | nb |
|---|---|---|
| `kid_settings_look` | Appearance | Utseende |
| `kid_settings_look_dark` | Dark | Mørk |
| `kid_settings_look_light` | Light | Lys |
| `kid_settings_look_caption` | For Messages, Calendar, the phone book and the lists here in Settings. Home and the contact card stay as they are. | Gjelder Meldinger, Kalender, Telefonbok og listene her i Innstillinger. Hjem og kontaktkortet er alltid som før. |
| `kid_settings_look_preview_in` | Hi! Are you coming? | Hei! Kommer du? |
| `kid_settings_look_preview_out` | Yes, now! | Ja, nå! |

## 8. Steps

Each step is one branch, code QA'd and merged before the next. Lean tests: pure functions, the palette, source scans.

**Step 0 - The go/no-go spike (throwaway branch, never merged). Needs the user for the Jelly Star.** On a branch
`spike-78`: `KidPageTheme` with one `values-notnight` ground and bar bool on `DayActivity`, the
`setApplicationNightMode` call from a debug button, the night pin on `UIObjectActivity`. A 60 fps screen recording of
Home -> Kalender, each look, from Home's tile and from the glance's row, on the Android 16 emulator **and on the Jelly
Star** (a debug build on the test phone, never the kid's - the user runs it or hands over the phone). Pass: not one
frame of the other look, and the status-bar icons dark on the light splash from its first frame. Also noted: what
Android 16's "make more apps dark" does to the light page. If it fails, nothing is built and the recordings go to the
user with the two leftover choices (a short night frame before a light page, or no light mode).

**Step 1 - The mechanism, the tokens and Kalender** (after step 0 passes). `ui/look/` (`PageLook`: choice/applied store,
`choose`, `mayChange`, the page base class with the started counter and the live-page registry, the
`attachBaseContext` modes, `wrap`), `colors_kid_page.xml` both files, the bools, `KidPageTheme`, `PageColors`,
`KidPageAlertDialog`, the day `VibbPickerDialog`; the manifest (pages on `KidPageTheme`, every other activity with
`uiMode`, `PlayLinkBlockedActivity`'s night theme); the night pin on `UIObjectActivity`, `PinLockActivity`,
`InCallActivity`; the unlocked-start re-apply in `initUnlocked`; Kalender's three screens and their drawables on page
tokens, `CalendarColor.dayArgb` and `argb(context)`; the kid Settings row, debug builds only. Tests: `KidPaletteTest`
reads `values-notnight` too - every §4 pair, `PageColors`' pairs, the dot table >= 3:1 on tile and ground, and each
night `kid_page_*` equal to today's token or literal; `PageLookTest` (prefs parse, absent/unknown = dark;
`mayChange`'s table with a picture in front and a translucent activity over a page; the pages a change finishes;
`choice` and `applied` written before the system call; the unlocked-start re-apply, first run included);
`PageManifestTest` (exactly the page activities on `KidPageTheme`, without `uiMode` - Kalender's three now, the eight
of §5.2 after step 2 -; every other activity with it); `PageTokensScanTest` (the page layouts, drawables and classes
name no night token, no literal colour, no `#..FFFFFF` ripple - an allowlist for `kid_on_accent` and the unqualified
bubble colours; `?android:attr/textColor*` allowed because `PageColors` sets it; no `.argb` read under `ui/day/`
outside `CalendarColor`); `PageLookScanTest` (`kid_look` touched only from `initUnlocked`, the pages and kid Settings;
every non-page `AppCompatActivity` pins night in `attachBaseContext`); `PickerThemeTest` both files. Emulator
(`docs/testing/emulator.md`): the step 0 recording again on the real code (`onCreate` once per open); a change from
kid Settings with Kalender behind (its task gone, the next open clean); before and after screenshots of Home, the
phone book, kid Settings, the drawer, parent Settings, the time-rule screen and the lock in the system's dark mode
(identical) and light mode (only D2's AppCompat bits differ); TalkBack on the row; font scale 1.3.

Step 1, 2026-11-10: built as listed. The phone book (the 2026-11-10 addition) is not in this list and stays night until
step 2. The emulator pass is written as steps (`docs/testing/emulator.md` §6ah) and has not been run: no emulator was
started in the build round.

Fix round, 2026-11-10: the code QA (`docs/review/qa-78-step1-code.md`: no High, three Medium, nine Low) and what each
finding became are in the review's "Fixes"; §5.2, §5.4 and §7 have its lines, and §6ah its additions.

Emulator pass, 2026-11-11: §6ah was run on `jelly36` as far as B.8 and stopped when the host killed the emulator for
memory. A's recordings, the change under the kiosk, the reboot and the update pass; one finding, the launcher's own
language (§5.3's line of this date); Kalender's screens in both looks, 3-button navigation, the screens that are not
pages, TalkBack and the font scale were not reached (§6ah, "The run of 2026-11-11").

The language's fix, 2026-11-11: read from Android's source and built inside the mechanism (§5.3, "The cause" and
"Decision"); its code QA (no High, two Medium, four Low) and the fix round - the late retry, the close that is not
repeated, the timed log line, the order under a scan test - are in the review's "The language fix's code QA".

**Step 2 - Meldinger and the lists** (after design 77 step 2 is merged). The three message screens on page tokens
(bubbles, selection rings, the picture's place, the unread dot, compose field with its hairline, send, the delete
confirm and its pills, the delivery line, the day separator), `PictureActivity` in the started counter, the kept
thread text with its lifecycle, the Meldinger task in the change; the Wi-Fi, Bluetooth and sound lists; `KidHeader`
tints the back arrow with `kid_page_ink` on a page; the row shown in release builds. Tests: the bubble pairs in
`KidPaletteTest`; `PageTokensScanTest` over `ui/messages`, `ui/quickcontrols`, `KidSoundPicker*`; `MessagesModelTest`
for the kept text (stored, restored, cleared on send, on delete, when the contact may no longer text, when unmanaged;
the 1,600-character and 50-conversation caps). Emulator: both looks of every screen in §2's "In" rows; a half-typed
text across a change.

For step 2, from step 1's code QA (L5, 2026-11-10; read, not seen): Home's resume runs a waiting change without asking
`mayChange` (§5.4), so while a change waits, a Home resume that falls between an open of Kalender and the page's
creation removes the task that was just opened along with the old one. Only a debug build can have a waiting change in
step 1; settle it before the row is shown in release builds.

**Step 2, 2026-11-11: built as listed, on revision 3's mechanism (§12), on branch `kid-look-3`.** Not seen running: no
emulator was started in the build; `docs/testing/emulator.md` §6aj is its pass.

- **Meldinger**: `MessagesActivity` and `ThreadActivity` are own-task pages, so each is two classes (R2; twins in
  `ui/messages/LightPages.kt`, rows `MESSAGES` and `THREAD` in `PageKind`); `MessagesScreens.listIntent` and
  `threadIntent` are the builders (R5); `closesTask` and `languageClosesTask` know Meldinger's task by either root.
  Their layouts, drawables and the thread's own colours are on page tokens: the received bubble, its ink and time
  (`kid_page_message_in*`), the selection rings (the received one `kid_page_accent`; the own one stays
  `kid_message_out_ink`, dark on the peach in both looks - design 77's fix round made it so after §4.1 was written, so
  `kid_page_selected_out` names nothing yet), the picture's place (`kid_page_control`), the unread dot, the compose
  field (`kid_page_tile` with the `kid_page_edge` hairline) and send (`bg_day_add`, the glyph `kid_on_accent`), the
  confirm (`kid_page_lift`) with its pills (`kid_page_button`, `kid_page_danger`/`_text`), the delivery line
  (`kid_page_dim`, `kid_page_alert`), the day separator, the reaction chip, panel and picker, the search field.
- **`PictureActivity`** stays night and one class (§2, §12.3: no twin), and counts itself in the started count.
- **The notification** names a look-less entry (R7): `MessagesEntryActivity`, translucent, `noHistory`,
  `excludeFromRecents`, not exported, `taskAffinity=""` - a task of its own, a departure from "as `ComposeActivity`
  is" (which has the default affinity): a notification's start comes with `NEW_TASK`, and the default affinity would
  bring Home's task forward under the translucent entry, Home's resume included. Inferred, not seen; §6aj looks.
- **The phone book and the three lists** are one-class pages on the qualified `KidPageTheme` (R9), their layouts on page
  tokens first (§12.7.2's leg B finding). They are live pages but not in the started count: they are no page task's.
- **What stays night over a Lys page** (§2): the contact card, "Bilde av …", the emergency menu, the 112 confirms and
  the phone book's emergency Ring confirm are built from `nightOver(activity)` - the activity with the night bit forced
  and `PageColors` laid on again - not from the page itself, whose theme attributes and shared page tokens would be
  the day's. The two confirms are AppCompat dialogs, which take the page's own night mode back from the activity behind
  their context (AppCompat 1.7.1's dialog delegate, read): `showNight` sets theirs to night before they are shown.
- **New tokens**, each night value today's: `kid_page_ink_dim`, `kid_page_card`, `kid_page_call_glyph` (#2B7340 in Lys),
  `kid_page_focus`, `kid_page_accent_edge` (#B3A64B25 in Lys, 3.2:1 on white) and `kid_page_field_edge`; the badge
  (`kid_badge` with a new unqualified `kid_badge_ink`, white, 4.98:1) is the same in both looks.
- **The kept thread text** (§5.4.4): in memory and in CE prefs - **`sms_drafts`, not `kid_look`** (a departure from §6:
  the texts are Meldinger's data, read and cleared by its screens; `kid_look` stays the look's alone, touched only by
  `PageLook`). The text and its draft `clientId`, at most 1,600 characters and 50 conversations; cleared when sent or
  emptied, when the conversation is deleted, when the kid may no longer text there (read-only, not allowed, a group
  not allowed, groups off - also for a conversation not opened again, at the list's load), and all of them while SMS
  is off or the phone unmanaged ("when the phone goes unmanaged" is held at every read, not at the moment of the
  change: nothing listens for it).
- **L5, settled**: a Home resume does not run a waiting change while a page's start is in flight - asked for through
  `PageKind.current()` within the last 5 s, no page created since (`PageRegistry.startInFlight`). The change then
  waits for the next resume. R6's check stays the route for anything that gets past it.
- **The row** is shown and listens in every build; its caption (the phone book named, 2026-11-10's wording) is true now.
- **`KidHeader`** needed nothing: the arrow's `kid_page_ink` tint is in `include_kid_header` since step 1.
- Tests as listed; `PageTokensScanTest` now reads its layouts from every page class's code and its drawables from
  those layouts, their styles and the classes, and holds the night sheets to `nightOver`.

Docs at each step: a new section in `docs/notes/launcher/ui-polish.md` ("The kid's look: Mørk or Lys", design 78), its
line in `launcher/CLAUDE.md`'s index, `docs/testing/emulator.md`.

Sizes, roughly: step 0 ~80 throwaway lines; step 1 ~650 lines of Kotlin and XML (most of it the token swap in
Kalender's resources); step 2 ~450.

## 9. Risks and what the devices must show

- **The starting window** (step 0): everything rests on WM merging the per-app night mode into the starting window's
  configuration and the splash's bar ink. Unproven until step 0's recordings; no step 1 before them.
- **OEM builds**: the Jelly Star is in step 0. A build that ignores `setApplicationNightMode` would leave only the
  starting window wrong (the pages are right through the `attachBaseContext` belt) - step 0 shows it.
- **D2's AppCompat bits** on a phone whose system dark theme is off: step 1's screenshots show them; anything beyond
  ripples, control tints, switches and dividers is a finding.
- **A crash or update restart** inside a page: the process restarts, `initUnlocked` re-applies the stored mode, the
  page relaunches in it - no change at a restart.
- **Android 16's force-invert option**: checked in steps 0 and 1.

## 10. For the user

No open design question. Step 0 needs the user: a debug build on the Jelly Star test phone (never the kid's phone) for
one recording in each look.

## 11. Revision 2: the design QA, finding by finding

| Finding | Outcome |
|---|---|
| H1 the pin to "the phone's own mode" | Fixed: every non-page activity pinned to `MODE_NIGHT_YES`, a constant, in `attachBaseContext` (`UIObjectActivity`, `PinLockActivity`, `InCallActivity`) (§5.3); D2 reworded - identical with the system dark theme on, only AppCompat's own bits take their night form with it off; step 1's screenshots in both system modes; the alternative in §5.6 |
| H2 a picture or translucent activity closes the Meldinger task | Fixed by construction and guarded: a change happens only at the kid's tap in kid Settings, where no page task can be started; `mayChange` still counts *started* activities of the page tasks, `PictureActivity` included (§5.4.1), with `PageLookTest` rows for a picture and a translucent activity |
| H3 CE read before the first unlock | Fixed: the start hook is `initUnlocked()` only (§5.4.3, §6); `PageLookScanTest` |
| M1 AppCompat recreates the page | Fixed: the local mode in `attachBaseContext`, before `super.attachBaseContext` (§5.3); step 1 checks one `onCreate` per open |
| M2 first run waits; `applied` drifts | Fixed: unconditional re-apply of `applied ?: dark` at every unlocked start, the prefs committed before the system call (§5.4.2, §5.4.3) |
| M3 pages in Home's task behind a change | Fixed: its triggers (the boundary, a page's `onPause`) are gone with Automatisk, and a live-page registry still finishes every live page at a change (§5.4.2) |
| M4 theme-attribute colours on pages | Fixed: `PageColors` overlay in the page's `getTheme()` after `modifyTheme` (§5.2); `KidPaletteTest` checks it |
| M5 the legend's dots | Fixed: one resolver `CalendarColor.argb(context)` for the row, the legend and the event page; a scan test (§4.2) |
| M6 the card's bar ink | Moot: the contact card stays night (the user, answer 4) |
| M7 go/no-go after ~600 lines | Fixed: step 0, a throwaway spike on the emulator and the Jelly Star (needs the user); §0 states the hypothesis |
| L1 stale or far fix | Dropped with Automatisk (the user: "Her kan heller barnet få velge selv") |
| L2 polar wording | Dropped with Automatisk |
| L3 07-19 in a Norwegian winter | Dropped with Automatisk |
| L4 "Automatisk" width | Dropped with Automatisk: two segments, as the sound control |
| L5 faint shapes | The compose field gets a 1 dp `kid_page_edge` hairline; the picture's place is white in day (§4.1). Tiles stay edgeless, like iOS's grouped lists |
| L6 unnamed `sms-screens` drawables | Named: `kid_page_accent` (received selection ring, unread dot), `kid_page_selected_out` (own selection ring), `kid_page_control` (the picture's place) (§4.1) |
| L7 the white card overturns design 34 | Moot: the card stays night; §2's rule reworded so Home's cards are out by rule |
| L8 `PlayLinkBlockedActivity` | Fixed: an explicit framework night theme (§5.3) |
| L9 step 1's caption untrue | Fixed: release builds show the row from step 2 on (§7) |
| L10 Android 16's force-invert | Added to steps 0 and 1 and §9 |
| L11 kept drafts' lifecycle | Fixed: caps and the four clears, with test rows (§5.4.4, step 2) |
| N1 "API 31+" | Fixed: "minSdk is 34" |
| N2 13.9 | Fixed: 13.7 (note and mockup) |
| N3 one ripple token for two values | Fixed: `kid_page_ripple` and `kid_page_ripple_strong` |
| N4 the strings | Dropped with Automatisk (the "døgnet" and "kl." strings are gone) |
| N5 kid Settings refresh | Dropped with Automatisk: the row changes only at the kid's own tap, which redraws it |
| N6 `values-v29` shadows Home's splash | Noted in §1 for the main session; §5.6's argument now rests on the PIN lock's black stand-in; §4.4 says a qualified style never merges |

## 12. Revision 3: the mechanism after step 1's passes

2026-11-11, by the architect, on `kid-look-1` at 67642f31 (branch `kid-look-mech`). Paper and source reading only:
nothing was built or run for this revision. **Nothing below is built, and nothing is until §12.7's spike has passed.**
Older sections are not rewritten; §12.8 says which of their lines this replaces.

**Revised 2026-11-11 after the design QA** (`docs/review/qa-78-design.md`, "Revision 3 (2026-11-11)": the mechanism
holds against the sources; 0 High, 2 Medium, 5 Low, 3 Nit). Every finding is taken as the QA's smallest change and none
is answered: M1, L2, L3 and L4 in §12.7 (the QA's S1-S4), with the spike as a run sheet in §12.7.1; M2 in R6; L1 in R4
and R5; L5 in §12.4; N1 in the marks below, R8, §12.6 and §12.9; N2 in R2; N3 in R2, R9, §12.4 and leg A7. The decision
is the same, and this round was paper too.

Where each claim comes from is marked:
**(seen)** on `jelly36` in step 1's two passes (`docs/testing/emulator.md` §6ah, `docs/review/qa-78-step1-code.md`);
**(code)** read in this branch's code; **(AOSP)** read in `frameworks/base` at tag `android-16.0.0_r1` - by the
architect, or by the design QA alone where it says "the QA's read";
**(AndroidX)** read in `AppCompatDelegateImpl`: for the first writing on `androidx-main`, and since in the sources of
AppCompat 1.7.1, the version the app builds with - by the design QA, and again by the architect for this revision;
**(inferred)** concluded from those and seen nowhere; **(from memory)** a command or a behaviour neither read nor run.
Nothing rests on one: §12.7 tries the commands first, and R6's rule holds either way.

### 12.1 Why

The per-app night mode (`UiModeManager.setApplicationNightMode`, D5) was taken for one thing: the ground of a page's
starting window (§5.3, first bullet). The two passes showed what else comes with it (§5.3, "The cause" and "The second
pass"):

1. With a night mode set, the package's record goes to disk without the launcher's languages at every boot's first
   call (AOSP), and the language was gone after reboots (seen). The fix asks for the language again at every unlocked
   start; it holds (seen), at the price of Home created twice on some boots (seen) and the channels named in the
   phone's language (seen; its fix built, not seen running).
2. Both calls are refused with `Can't change activity type once set` by the type of activity the process followed at
   its last call that got through (AOSP; every sighting followed the model). On a natural route - a parent's change of
   language set at the unlock with the PIN lock still followed - every later change of look was refused for the
   process's life, and Kalender's starting window showed the old look for about 330 ms (seen, twice).
3. The two calls ran on the main thread for up to 2.6 s and 1.7 s on loaded boots; one ANR (seen).

Every one of them comes from sharing one system record, and one refusable call path, with the launcher's language. The
starting window does not need that record.

### 12.2 The decision

**No per-app night mode. A page that can be the top of a task of the launcher's own has two manifest activities - a
night one and a light one - and the launcher's own code picks the class.**

- **R1. `setApplicationNightMode` is called nowhere.** `PageLookScanTest` holds it as a source scan over the whole main
  source set. The launcher's state in the system is then exactly main's: a record with the languages alone.
- **R2. Two activities per own-task page.** The class as it is (`DayActivity`, `EventActivity`, `EventEditActivity`) is
  the night one - and that choice is what makes an update from a released build safe: a phone on main has a `.day` card
  rooted at `DayActivity` and no stored look, and an absent look is the dark one (code). Beside each a subclass with an
  empty body (`LightDayActivity`, `LightEventActivity`, `LightEventEditActivity`) is the light one; the three classes
  become `open` for it. The twins live in `ui/day/` beside them: `DayTaskTest` holds that no activity outside `.ui.day.`
  has the `.day` affinity (code). The manifest entries of a pair are identical - launch mode, the `.day` affinity, not
  exported, `windowSoftInputMode`, `configChanges` - but for the theme. All three need a twin, not only the task's root:
  any of them can be the top of the task when it is brought forward after the process died, and that window is drawn
  from the top activity's theme (AOSP, §12.3). An `<activity-alias>` cannot do it: the parser reads an alias's own
  attributes - no theme among them - and makes the rest from its target (AOSP, `ParsedActivityUtils.java:264-328`); and
  an alias runs its target's class, so R4 could not read the look from it (inferred).
- **R3. Each variant's theme carries the window's items as literals.** `android:windowBackground`,
  `android:windowSplashScreenBackground`, `android:windowLightStatusBar`, `android:windowLightNavigationBar` and
  `android:enforceNavigationBarContrast` are set in a night theme and a light theme over a shared `KidPageTheme`, with
  **unqualified** colours: `kid_ground` (#0C0C14, already unqualified - code) and a new unqualified paper colour equal
  to `values-notnight`'s `kid_page_ground` (#F6F2EF; `KidPaletteTest` holds the two equal). Unqualified because the
  system draws the starting window in the activity's configuration, which without an app night mode carries the
  **phone's** night bit (AOSP, §12.3): a qualified colour there would follow the phone, not the look. Everything else
  stays as built: the page tokens in `values/` and `values-notnight/`, reached through the page's own AppCompat mode.
- **R4. A page's content follows its class.** `lookNightMode()` (the AppCompat local mode set in `attachBaseContext`,
  §5.3's second bullet) comes from the variant the class is, not from the stored look, so a window and its content can
  never disagree. One table in `ui/look/` pairs the classes; it answers "the class of this page in this look" and "the
  look of this class", and it is the only place that names a page class, night or light, as `::class` (R5's scan).
- **R5. One chooser.** Every start of a page goes through the pages' own intent builders (`DayActivity.intent`,
  `EventActivity.intent`, `EventEditActivity.newIntent` / `editIntent` - today the only ones that name the classes,
  code), which ask the table with the applied look. `PageLookScanTest` holds it from the names' side: **no page class,
  night or light, is named as `::class` anywhere in the main or the debug source set outside the table** - the builders,
  `closesTask` and `languageClosesTask` get their classes from it. The night name is the dangerous one: a later
  `Intent(this, EventActivity::class.java)` compiles, works in Mørk, and in Lys opens a night page that R6's check
  closes at every tap - a door that never opens in one look, the one way this design can take a page from the kid
  (inferred). An `is DayActivity` check stays allowed; it holds for a twin too. A start inside the task gets no starting
  window while the process runs (AOSP) and still picks the variant, for R4.
- **R6. A living page task is always of the stored look.** A change (§5.4's step 2) keeps what it does - finish the live
  pages, remove the page tasks - and turns the order round: the tasks are removed **first**, the new look is stored
  after. Step 1 stored first so that a process dying in between came back in the new look and the unlocked start
  repaired the system's value; there is no system value any more, and a death between the two now leaves no page task
  and the old look - the kid's tap lost, nothing mixed. It closes the leftover of step 1's code QA L4 (§5.4, "Fix
  round": an old-look card after a death between the write and the removal). `closesTask` and `languageClosesTask` name
  both roots of a pair, through the table.

  Two refusals keep the rule by not changing:

  - **The look is stored only when a second read of `ActivityManager.appTasks`, made after the removals, shows no page
    task.** A removal that failed for another reason than "already gone" (`closePageTasks` catches a `RuntimeException`
    per task and goes on - code) would otherwise leave an old-rooted task for the next open to stack the new class on
    (§12.3, "Two classes, one affinity"), where no check sees it. A removal that worked is out of that list when its
    call returns: `removeTask` takes the task out of the recent tasks in the same call, and `appTasks` is read from them
    (AOSP, `ActivityTaskSupervisor.java:1766-1843`, `RecentTasks.java:928-946`; `AppTaskImpl` between them not read -
    leg A5 shows it).
  - **The look changes in memory only when `commit()` returned true** (`store` drops the result today - code). On false
    the old values are committed again, so that the process's preferences object holds no look that `applied` and the
    disk do not, for a later write to carry out (from memory: a failed commit leaves its values in the process's map;
    `SharedPreferencesImpl` not read).

  After either refusal nothing has changed but the page tasks that closed: `E PageLook`, the kid's tap is lost - the
  choice goes back to the applied look, in the store too where a waiting one was written - the control shows the applied
  look, and the next open is in the old look. Nothing tries again on its own; a refused change runs only at a new tap.
  `PageLookTest` gets a row for each refusal.

  `KidPageActivity.onCreate` checks its class against the applied look (a one-class page, R9, has no row in the table
  and passes) and, if they differ, writes `E PageLook` and removes its task before any content is set. **That check is a
  route, not only an assertion**: the order above does not exclude every way to a page of the other class. Two stay
  reachable, both rare (inferred):

  1. **Power lost** - not a clean shutdown - between the look's commit and the system's delete of the removed task's
     file, which its writer does lazily, when its queue runs empty (AOSP, `TaskPersister.java:430-446`): the old card is
     back after the boot, with the new look stored.
  2. **L5's shape** (§8): a waiting change run by a Home resume between a page's start and its creation.

  What the kid sees on them: one starting window in the other look - the system's, drawn before any code of ours, the
  one frame the ground rule forbids - then the task closes and the screen below is back; the next tap makes a right
  task. Nothing strands and nothing loops: after the removal there is nothing left to trigger it again. On the first
  route opened from Home's tile instead of the card the kid gets the right page whichever way the system takes it: the
  restored task is either not found by the start - a new task, and the old card leaves Recents as the older entry of its
  affinity - or the new class is stacked on it, and the check closes the task when Back reaches the old page under it,
  where Back was leaving Kalender anyway (inferred; which of the two was not read). Removing the task is the answer and
  stays the only one: a softer one - a restart through the chooser - would hide the cause and is not added beside it.
- **R7. Nothing outside the launcher's process holds a variant's name.** A `PendingIntent`, an exported entry or a
  persistent preferred activity that names a variant would be started - starting window first - in the look of the day
  it was made. None exists today: no `PendingIntent` names a Dagen page, and no variant is exported (code). From step
  2 on (`SmsNotifier.openIntent` names Meldinger's list and thread, code) such an intent names a **look-less entry**
  instead - a translucent, `noHistory`, `excludeFromRecents` activity that starts the variant through R5, as
  `sms.ComposeActivity` already is for `sms:` links (code); a translucent theme gets no starting window (AOSP). If
  §12.7's leg C shows a frame of its own, the other sound means is to cancel and re-post at a change: a cancelled
  `PendingIntent` cannot fire. `PageLookScanTest` and `PageManifestTest` hold R7 (no `PendingIntent` is built from a
  page's intent builder; no variant is exported or named as a preferred activity).
- **R8. Pages declare `uiMode`.** Without an app night mode the phone's own dark-theme switch (schedule, Battery
  Saver) reaches every activity as a configuration change, and one that does not declare it is relaunched (AOSP,
  `ActivityRecord.shouldRelaunchLocked`). A page declares it like every other activity and stays as it is: AppCompat
  pins the resources to the local mode again and does not recreate an activity that handles `uiMode` (AndroidX,
  1.7.1's `AppCompatDelegateImpl.java:672-702, 2793-2815`; the same client path was seen on the non-page activities at
  step 1's changes of look). It reads the declared flags for the host's own class (AndroidX, `2945-2970`), so a twin's
  own manifest entry must declare `uiMode`: R2's "identical but for the theme" carries that. Not read: that the
  framework keeps AppCompat's override when it updates the activity's resources (`ResourcesManager`) - inferred, and
  leg A4's to show. `PageManifestTest`'s rule becomes "every activity declares `uiMode`".
- **R9. Pages in Home's task need one class.** An activity in Home's task is home-type and never gets a theme-drawn
  starting window (AOSP, `getStartingWindowType`); its first frame is its own. Step 2's lists (Wi-Fi, Bluetooth, sound)
  and the phone book opened from Home keep one manifest entry on the qualified `KidPageTheme` as step 1 built it, with
  the AppCompat mode from the applied look. Such a page has no row in the table and passes R6's check by the applied
  look. The phone book's other doors are §12.9's first open point.

What does not change: D1-D4, D6, D7; the tokens (§4); the pages' AppCompat mode in `attachBaseContext` and one
`onCreate` per open; `PageColors`; the dialogs and pickers; the night pin and `uiMode` of every non-page activity (its
reason stands: `values-notnight` exists, and a non-page screen must never resolve it); `PlayLinkBlockedTheme`; where the
choice lives (§6); the row and its strings (§7, en + nb as they are - this revision adds no string); the guard and the
waiting change (§5.4's step 1); what finishing costs (§5.4's step 4).

### 12.3 Is it sound - the cases, one by one

**How a starting window comes to be** (AOSP: `ActivityRecord.showStartingWindow`, `getStartingWindowType`,
`validateStartingWindowTheme`, `evaluateStartingWindowTheme`, `isSnapshotCompatible`; `Task.getStartingWindowInfo`;
Shell's `SplashscreenContentDrawer`, `SplashscreenWindowCreator`, `AbsSplashWindowCreator`; `SnapshotDrawerUtils`):

- Its theme is the launched activity's own manifest theme. Only two things replace it: a theme named in the start's
  options (hidden API) and the package-wide `SplashScreen.setSplashScreenTheme` (never called here, code; §5.6).
- A theme-drawn one ("splash") is made for a standard-type activity in a new task, with the process not running, or at
  a switch of task to an activity not yet created. A switch to a task with a snapshot whose top component is this
  activity shows the snapshot instead. None at all: a home-type activity; a start inside a task with the process
  running; a translucent or floating theme; and `windowDisablePreview` (which `launcherBaseTheme` sets, code) - but
  that last one is ignored when the start comes from the system, SystemUI or the home app, and the launcher is the
  home app, which is why pages got starting windows in step 0 and step 1 (seen).
- Its ground is the theme's `windowSplashScreenBackground` when set, read in a context of the package given the
  **activity's configuration** (`Task.getStartingWindowInfo` copies it in; `SplashscreenContentDrawer.createContext`
  re-creates the context when the night bits differ). The Shell's colour cache is keyed by the colour itself among
  others, so two themes of one package do not share an entry.
- Its bar ink is not the theme's `windowLightStatusBar`: the Shell sets light-bar appearance from the **lightness of
  the ground** (`SplashscreenWindowCreator`). A snapshot's window copies the bar appearance of the window it pictured.

A per-activity splash ground is nothing new in this app: the PIN lock's black stand-in (`PinLockTheme`, design 32) and
Home's (`launcherHomeTheme`) are drawn the same way from their own manifest themes (code; their sightings are those
designs', not re-checked here).

**The four cases** (the starting window's columns inferred from the reads above; the content's is step 1 as built - and
seen carrying a page alone: after the second pass's refused changes the pages drew in the stored look while the
system held the other one):

| Look | Phone's theme | Class, theme | Starting window's ground | Its bar ink | Content |
|---|---|---|---|---|---|
| Mørk | dark | `DayActivity`, night | `kid_ground`, a literal | white: the ground is dark | night |
| Mørk | light | `DayActivity`, night | the same literal - the phone's bit has nothing to pick | white | night, AppCompat's YES over the phone's bit |
| Lys | dark | `LightDayActivity`, light | the paper, a literal | dark: the ground is light | day, AppCompat's NO over the phone's bit |
| Lys | light | `LightDayActivity`, light | the paper | dark | day |

**The rest of the list:**

- **The status bar's late tint in Lys** (white ink on the paper for 165-500 ms, seen with the process held, so before
  any of our code). It is SystemUI's: `LightBarTransitionsController.setIconsDark` holds a change of tint while an app
  transition is pending and then animates it from the transition's start over its length (AOSP). The starting window
  already asks for dark ink under the kept mechanism; a light manifest theme asks for the same thing by the same route.
  **Neither mechanism changes it** (inferred); the spike measures it against step 1's numbers.
- **3-button navigation in Lys with the phone dark** (white buttons on the paper, seen; `mHasLightNavigationBar=true`,
  `mForceDarkForScrim=true`). SystemUI's own: `LightBarControllerImpl` draws the buttons light whenever its scrim
  forces dark, whatever the window asks (AOSP). The app's night mode never reached SystemUI and a manifest theme does
  not either: **unchanged by both** (inferred). It stays the code QA's open bar point for step 2.
- **Accessibility's force-invert.** `ViewRootImpl.determineForceDarkType` inverts a window whose own theme is light
  with a light background, "ignoring all developer opt-outs", when the system's force-invert state is on (AOSP). It
  reads the window context's theme, which is the same under both mechanisms: a Lys page is a light window either way.
  The switch was not found on the image (seen: a hand-written settings key did nothing). Unchanged, still open.
- **A page reopened from its Recents card, with a snapshot.** The snapshot is used only when its top component is the
  activity coming forward (AOSP); by R6 a card that exists is of the stored look - but for R6's two named routes - so
  its snapshot is too. A phone-theme flip does not make a snapshot stale - the page did not change with it, and nothing
  in the Shell's snapshot classes reads the snapshot's `uiMode` (AOSP, the QA's read).
- **A card reopened after the process died or after a reboot.** No snapshot fits or none is kept; the system draws a
  splash from the **top** activity's manifest theme (AOSP) - hence a twin for the event page and the editor (R2) - and
  the activity is created again as the same class. Right by R6, but for its two named routes. The branch taken is
  "a switch of task to an activity not yet created", not "no process": the pages run in the launcher's main process,
  with Home (code), which is up again before the kid can tap.
- **After a change of look.** The page tasks are gone (R6; seen for Kalender in the pass: its card closed, the next
  open clean), so there is no card and no snapshot of the old look to come back.
- **Two classes, one affinity.** `NEW_TASK` to a class finds a task whose root is that class, or else one with the
  same root affinity, and then puts the new activity **on top of it** (AOSP: `RootWindowContainer.FindTaskResult`,
  `ActivityStarter`'s "place the new activity on top of the current task"). So there is one `.day` task and one
  Recents card whatever the class - and a light class started while a night-rooted task lived would be stacked on it.
  R6 is what excludes that - its first refusal for the removal that fails; it is load-bearing, and the spike's leg A5
  looks for exactly this. A task whose top activity is finishing is skipped by the search
  (`RootWindowContainer.java:369-376`), so a start right after R6's removal makes a new task; and Recents cannot show
  two `.day` cards, since adding a task removes the older entry of the same affinity (`RecentTasks.java:1662-1722`;
  both AOSP, found by the QA). `singleTop` is per class and works as today inside a variant; no page is `singleTask`
  or `singleInstance` (code).
- **Starts inside a page** (day view -> event -> editor): no starting window with the process running (AOSP); the
  variant comes from R5.
- **Stale intents.** R7. Until step 2 there is none to go stale.
- **Whatever names a page's class** (code, a grep over `launcher/`, `music/`, `server/src/`, `scripts/` and the
  ProGuard files): the manifest; the intent builders; `closesTask` and `languageClosesTask` (both get the table);
  tests (`PageManifestTest`, `PageLookScanTest`, `PageLookTest`, `LauncherLocaleTest`, `DayTaskTest`,
  `LockGlanceGuardTest`, `TimeLockModelTest`). `KidAppComponentFactory` treats every class outside the direct-boot
  list as needing the unlocked setup, so a new class is safe by default. No deep link, no music-bridge name, no keep
  rule. An `is DayActivity` check still holds for a subclass.
- **The phone's theme flips while a page is open or stopped.** New under this revision: the change now reaches pages.
  R8: no relaunch (AOSP), resources pinned again (AndroidX), nothing redrawn in another look (inferred; leg A4).
- **Dialogs and pickers on a page.** Built from the activity's context, which AppCompat pins: `KidPageAlertDialog` and
  the day `VibbPickerDialog` resolve as built (code; seen in the pass). Unchanged.
- **The application's context.** It follows the phone's theme, as on main. Under the kept mechanism it followed the
  look. Page tokens must therefore only ever be read through a page's context or `PageLook.wrap`; today's Kotlin
  reads are a view's context in `DayListAdapter` and the preview's `wrap` (code). `PageTokensScanTest` gets the rule.
- **Step 2.** Meldinger's list and thread are own-task pages: twins, by R2. `PictureActivity` gets a twin only if it
  is drawn in the look; a night-only picture keeps one class on a literal night theme. The lists: R9. The
  notification: R7.

### 12.4 What of step 1 stays, changes and goes

| Part | Under revision 3 |
|---|---|
| Tokens and resources (`values/` and `values-notnight/` colours, `PageColors`, `KidPageAlertDialog`, the day picker) | Stay |
| `KidPageTheme` | Stays as built, qualified: R9's one-class pages use it as it is, the two `bools_kid_page.xml` with it. A night and a light theme over it set R3's five window items again, as literals, for the twins. One unqualified paper colour is added |
| `KidPageActivity` | Stays (the AppCompat mode, `PageColors`, the registry and the started count); `lookNightMode()` reads the class (R4); R6's check |
| `LookState` / `PageLook` | Stay: the store, `choose`, `mayChange`, the waiting change at Home's resume, `wrap`. Go: `LookHost.applyNightMode`, the system's value in `nightValues`, the unlocked start's call (it only reads the store now) and its timed log line. Changes: `change`'s order and its two refusals (R6), `store` handing back `commit()`'s result; the table (R4) |
| The manifest | Three more entries; `uiMode` on the six page entries |
| The pages in `ui/day/` | The three classes `open`, their twins beside them (R2); the intent builders one line each (R5) |
| The kid Settings row and the preview | Stay, debug-only until step 2 |
| The night pin and `uiMode` of the non-page activities, `PlayLinkBlockedTheme` | Stay |
| The language fix (`LauncherLanguage`: the unlocked start's ask, the repair at Home's resume, the late retry) | **Leaves this design.** Its design-78 reason is gone: with no night mode the write record always holds the languages (AOSP: `updateFromImpl` fills it with the first of night mode, languages and gender it holds; seen: the clock app, with no night mode, kept its languages over the boot that took the launcher's). What is left is main's own, older and narrower (seen on main: a second change of language in one process life refused under another type of activity; read: a process alive before the unlock never gets the record's values). The code stays as built until that is decided as its own item (§12.9) |
| The channels named again on a change of the process's languages | Stays with the language fix; it is the same "process without the record's values" case, which main has too |
| Tests | `PageManifestTest` (pairs identical but for the theme; every activity declares `uiMode`; its source check takes a twin's form, `: DayActivity()`, beside `: KidPageActivity()`), `PageLookScanTest` (R1; R5's scan over both names; R7; the "night mode before the language" order scan goes), `PageLookTest` (the order of a change, no system call; a row for each of R6's refusals; the table), `KidPaletteTest` (the literal paper equals the token), `SplashThemeTest` (each variant's splash ground and bar items are literals), `PageTokensScanTest` (no page token through the application's context), `DayTaskTest` (six names in `.ui.day.`; its "none outside `.ui.day.` has the affinity" stands because the twins live there), `LockGlanceGuardTest`, `TimeLockModelTest`, `LauncherLocaleTest` (the class names) |

No clean-up code: `kid-look-1` is unmerged and no released build ever set a night mode. A test device that ran step 1
carries one, and setting it back to "follow the phone" is not the same as never having set it **while the record
lives**: the record then holds a reset night mode, which still goes into the write record first and alone (AOSP,
`updateNightMode`). The record itself goes when the night mode is absent or reset, the languages are empty and the
gender is unset (AOSP, the QA's read: `PackageConfigPersister.java:198-207`), so emptying the app's languages after a
reset would clear it too (inferred). The step 1 build has no call that sets the mode back, so the plain instruction
stands: such a device is wiped, or the package uninstalled (the record goes with it - AOSP, `onPackageUninstall`); the
AVD is restored.

Size, roughly: 18 files, about 330 lines added and 200 removed, more than half of it in the tests. Production code:
about 105 added (three empty classes, three manifest entries, two small themes, the table, R6's two refusals) and 110
removed.

### 12.5 The kept mechanism with the gate, and why not

The implementer's proposal (§5.3, "The second pass"): no call of the launcher's - night mode or language - while an
activity outside Home's task is alive in its process; Home's resume waits for the PIN lock or call screen just left to
be destroyed and closes a page's task before it asks; the unlocked start's two calls wait for Home in a process
started under such an activity.

**It closes the route that was seen. It does not make a refused change of look impossible** (inferred from §5.3's
cause, which is AOSP read and held in every sighting):

- What decides is the activity the system has the process **follow** - the one added to it last that was not finishing
  then. The launcher sees its activities through lifecycle callbacks, which come after the system has added the record
  and before it has taken a finished one away (the pass saw a page destroyed about 350 ms after Home's resume).
  `ActivityManager.appTasks` shows tasks, not the followed activity.
- The check and the call are not one step. The PIN lock at a screen-off, the call screen at a ring, a notification's
  tap can add a standard-type activity in between, from outside the main thread's control.
- One miss is enough and lasts for the process's life: from then on every change is refused under Home, and every
  page's starting window is the old look's. The launcher's process lives for days. The only cure is a new process,
  and the launcher - the kiosk, the VPN, the calls - should not kill itself for a look.

So the gate turns "refused on a natural route" into "refused on a race", with the same result for the kid when it
hits and nothing the app can do about it. That is a weaker rule with a hole, not a fix.

What it costs: in a process started under the PIN lock, a page or a call, both calls wait for Home - on a boot whose
record lost the languages the PIN lock is in the phone's language again (the first pass's finding, back for that
window), and a page reopened from its card in such a process is drawn before the mode has been asked for. A page's task is
closed before every change of language at Home, not only after a refusal.

What stays whatever the gate does: the record written without the languages at every boot's first call; two system
calls on the main thread at every unlocked start (up to 2.6 s and 1.7 s, one ANR - seen); Home created twice on boots
without the languages; the channels' names; and every later per-app setting (a language feature, say) tied to the
look's call path.

### 12.6 Risks of the decision

- **A phone maker's Shell** (the Jelly Star's build) may draw starting windows otherwise. Shared with the kept
  mechanism, whose step 0 on the Jelly Star was never run either (the code QA's open list). This route is the plainer
  one - a literal `windowSplashScreenBackground` in a manifest theme is the documented splash API, and the app already
  rests on it for the PIN lock and Home.
- **Every door into an own-task page must go through the chooser.** Held by one table, the scans (R5's over both
  names, R7) and by there being few doors: three intent builders today. A door that gets past them names a night
  class, and R6's check then closes it in Lys at every tap - seen at once, never a night page left standing.
- **R6's check is a route on two rare paths** (a power cut before the removed task's file is deleted; L5's shape): one
  starting window in the other look, then the task closes. Named in R6, not closed.
- **Pages now get the phone's theme flips** (R8). The client's side is read in AppCompat 1.7.1; the framework's -
  that it keeps the override when it updates the activity's resources - is not. Leg A4 is the proof on the device.
- **The spike's log lines rest on two commands from memory** (§12.7). Its first step tries them, and A2 carries the
  proof where they do not print.
- **Twice the manifest entries for own-task pages**: three more now, two or three more in step 2. `PageManifestTest`
  holds each pair identical but for the theme, so they cannot drift.
- **The phone book's doors from outside** (§12.9) are read, not seen.
- **Two frames this revision does not improve**: the late status-bar tint and the white 3-button icons (§12.3). They
  are no worse; they are not solved.

### 12.7 The spike that must pass first

A throwaway branch off `kid-look-1` (`spike-78-twins`, never merged), a debug build on `jelly36` (480x854, 240 dpi,
Android 16) from a **restored clean AVD** - one that carries a night mode from the earlier passes proves nothing
(§12.4's last paragraph). The launcher's language is set to the one the phone does not have. §12.7.1 is the same spike
as a run sheet: this text gives the reasons, the sheet the order.

**What carries the proof**, heaviest first:

1. **The system's own lines about every starting window** (S1). Two debug log groups are on for the whole run. The
   server's `WM_DEBUG_STARTING_WINDOW` prints "Checking theme of starting window: 0x%x", "Creating
   SplashScreenStartingData" and "Creating SnapshotStartingData" (AOSP, the QA's read:
   `ActivityRecord.java:2235, 2348, 2359`). The Shell's `WM_SHELL_STARTING_WINDOW` prints "addSplashScreen for package:
   %s with theme: %s for task: %d, suggestType: %d", "addSplashScreen: creating context based on task Configuration %s"
   and "getWindowAttrs: window attributes color: %s, replace icon: %b" - the theme and the colour as hex without a
   prefix (AOSP, `SplashscreenContentDrawer.java:169-171, 198-200, 487-489`). The commands that switch them on are
   **from memory**, the QA's and not read: `wm logging enable-text WM_DEBUG_STARTING_WINDOW`, and for the Shell
   `dumpsys activity service SystemUIService WMShell protolog enable-text WM_SHELL_STARTING_WINDOW`. So the spike's
   first step on the image is to try them.
2. **The held process** (A2): the window the system drew, alone on the screen, with the launcher's process stopped.
3. **The recordings** (A1): the eye's check, and no more. In Lys the wrong frame would be a dark flat one inside the
   cross-fade out of dark Home, and an emulator's recording does not say which frames it missed (inferred) - so no row
   rests on a recording alone.

**If the image will not print the lines, A2 carries it.** The run then says so in its first line - both groups, one, or
none - the must-show below is judged from what does print, and every open it would have judged (A5's first open after a
change, each of A6's, leg C's two taps) is made once more under A2's rule: the launcher's process held where it is
alive, `dumpsys window windows` and a screenshot taken while the starting window stands. The spike does not wait for a
way to print them.

**The must-show on the lines**, for every open of a page in A1, A2, A5, A6 and leg C:

- an open that makes the task, or that opens a task whose activity is not created (after a reboot, after the process was
  killed): a splash - the theme logged is the variant's, one of the two resource ids of the spike's night and light
  theme read from the spike APK's resource table, and every colour logged for it is the look's literal, `ff0c0c14` in
  Mørk and `fff6f2ef` in Lys;
- an open that brings a living task forward: a snapshot - or, where the Shell has none, its solid-colour splash, with
  the theme and the colour as above (AOSP, `PhoneStartingWindowTypeAlgorithm.java:88-96`);
- never a snapshot where the task was not alive, and never the other variant's theme id or colour.

**Build, the smallest that answers** (about 120 lines; §12.7.1's step 1 has it file by file):

- the `setApplicationNightMode` call removed, and the unlocked start's language ask switched off (so that A7's boots
  show what the record alone keeps);
- the three light classes (empty subclasses), their manifest entries, the night and the light theme with R3's literals -
  both still inheriting `windowDisablePreview` from `launcherBaseTheme`, as the real build will - `uiMode` on all six
  entries, R4, R5, and R6's order with its second read;
- R6's check **as its log line alone** (S3): a page whose class is not the applied look's writes `E PageLook` in
  `onCreate` and does nothing else, so that A8's "no `E PageLook`" says over the whole run that no page of the wrong
  class was ever created. Without the line nothing in this build writes that tag once `applyNightMode` is gone (code).
  No tests;
- leg B: `KidSoundPickerActivity` (in Home's task) on step 1's qualified `KidPageTheme`, its AppCompat mode from the
  applied look - no tokens swapped, the window's ground is enough;
- leg C: a debug-only notification whose `PendingIntent` names a look-less entry (translucent, `noHistory`,
  `excludeFromRecents`) that starts `DayActivity.intent(...)`.

**Leg A - the own-task page.** "The page's area" is everything under the status bar. An open "makes the task" when no
`.day` task exists (right after a change of look, or the card swiped away) and "brings it forward" when the task is
behind Home.

- A2, **run first**. What must be true for a held process to mean anything, each checked and written down before the
  first hold (L4): the debug launcher is the home app (`cmd package resolve-activity` for the HOME intent names it) -
  where it is not, `windowDisablePreview` holds, a page gets no starting window at all, and every later leg passes
  without testing anything (AOSP: the item is ignored only for a start from the system, SystemUI or the home process);
  the spike's two themes inherit `windowDisablePreview`; the process held is the one that owns Home, by pid (the
  launcher also has `:bootcover` and the isolated `:mmsparse` - code); the hold is short enough that no ANR dialog is in
  the screenshot; and the events log shows no page created or resumed between the tap and the screenshot. Then, with the
  process held (SIGSTOP right after the tap, as in §6ah), in each look and each phone theme (`cmd uimode night yes`,
  `no`), one open that makes the task and one that brings it forward: eight holds. **Must show**:
  `dumpsys window windows` has a window of type `APPLICATION_STARTING` on the variant's record (`...LightDayActivity` in
  Lys, `...DayActivity` in Mørk) - titled `Splash Screen ...` where the task is made,
  `SnapshotStartingWindow for taskId=...` or the splash's title where it is brought forward (AOSP,
  `SplashscreenContentDrawer.java:274`, `TaskSnapshotWindow.java:65, 93`); and the screenshot's page area is the look's
  ground alone, or the page in its look where a snapshot is shown. **A hold without that window, or with the other look
  in it, stops the spike** before any recording is made.
- A1, the eye's check. Recorded with the device's `screenrecord` and stepped frame by frame (§6k's rule). Home's tile ->
  Kalender and the lock glance's row -> Kalender, in Mørk and Lys: the open that makes the task with
  `cmd uimode night yes` and with `no` (eight recordings), and the open that brings it forward with the phone's theme
  unlike the look (four) - a snapshot does not depend on the phone's theme unless the flip falls while the task is
  behind, which is A4's row. Twelve recordings. Each row **states its real frame count**: the frames the file itself
  holds (§6k's `-vsync 0`) in the second after the first frame that differs from the still before the tap; under thirty,
  the row is recorded once more and both counts stand in it. **Must show**: not one frame in which the page's area has
  the other look's ground; and the must-show on the lines for each of the twelve opens.
- A3. The events log over A1: **exactly one** `wm_on_create_called` for the page per open that makes the task, none on
  one that brings it forward, and **no** `wm_relaunch_activity` or `wm_relaunch_resume_activity`.
- A4. With Kalender in front, then stopped behind Home, then with the event page in front: `cmd uimode night` flipped
  both ways, and Battery Saver on and off. **Must show**: no relaunch line and no `wm_on_create_called` for a page;
  screenshots before and after identical - which shows only that nothing was relaunched, since a view keeps the colours
  it was inflated with (L2). What tests the pin is what is resolved **after** the flip, and it must be in the page's
  look: a date picker opened, another day opened, the list scrolled to rows not yet bound. And one **recording** per
  look of the bring-forward of a task that was behind Home across the flip - the change of configuration is delivered
  there - with no frame of the page's area in the phone's look.
- A5. A change of look in kid Settings with Kalender's task behind. **Must show**: the look changed, with no
  `E PageLook` (R6's second read found the removed task gone); `dumpsys activity recents` without a `.day` task right
  after; after the next open one `.day` task whose root and only activity is the new look's class; at no point both
  classes in one task or two `.day` cards; and on the lines a splash in the new variant's theme and colour for that
  open, no snapshot.
- A6. Reboot with Kalender's card in Recents and the event page on top of it, unlock, open the card - in each look. And
  once more per look **with the launcher's process killed instead of a reboot** (S4): the event page on top, the task
  behind Home, the process that owns Home killed with a signal, the way the hold stops it (`kill -9` on its pid; not
  `am force-stop`, which takes the tasks with it - from memory), then opened from the card, and in a second go from
  Home's tile. It is the nightly route on a real phone: the launcher's own update replaces the process under Kalender's
  living card. **Must show**: no frame of the other look's ground; the record created is the look's class - from the
  card the same class as before; and on the lines a splash in that class's theme and colour, no snapshot.
- A7. Two cold boots, one before and one after a change of look - A6's two reboots serve. With R1 a change makes no
  system call, so two boots say what four did (N3). **Must show** after each unlock: `cmd locale get-app-locales` for
  the debug package still the launcher's language, and Home's `wm_on_create_called` once with no relaunch line after it.
- A8. Logcat over the whole run: **no** `Can't change activity type once set`, and no `E PageLook` - which, with S3's
  line in the build, says that no page of the wrong class was created and no change was refused.
- Noted, not gating: the status bar's ink on the Lys starting window in milliseconds, against step 1's 165-498 ms (worse
  is a finding); 3-button navigation in Lys with the phone dark; force-invert if a switch is found.

**Leg B - a page in Home's task.** Lys with `cmd uimode night yes`, and Mørk with `no`: open the sound picker from kid
Settings. **Must show**: no `Splash Screen` window for it in `dumpsys window windows`; in the recording, no frame in
which its window has the other look's ground.

**Leg C - an intent made in the other look.** Post the notification in Mørk, change to Lys, tap it; and the other way
round. **Must show**: the variant created is the stored look's; no frame of the other look's ground; no black or empty
frame from the entry itself; the entry not in Recents; and on the lines the stored look's theme and colour for the
page's starting window. Not a leg: the same tap with the launcher's process gone. The entry then shows nothing until the
process is up (a translucent theme has no starting window - AOSP), where a direct intent would have shown the ground at
once; the launcher's process is all but always alive, and if the case turns up in the run the time from the tap to the
first frame is noted.

**Pass**: every "must show" of A and B. What a failure means:

- A2, A1, A5 or A6 - a starting window of the other look, a mixed task, two cards: the alternative is not built. The
  lines, dumps and recordings go to the user with what is left: §12.5's gate with its hole named, or §5.6's translucent
  row examined properly, or no light look.
- A3, A4, A7 or A8: the run is finished and comes back to the architect before any rework. A4 is R8's proof and A7 is
  R1's promise about the language; a failure there is a finding about the mechanism, not a detail of the rework.
- Leg B: the lists get twins too, with their own affinity question, and that comes back to the architect. It is step 2's
  shape that changes; the rework of Dagen's pages does not wait for it.
- Leg C is step 2's and does not gate (the design QA): a failed leg C moves R7 to cancel-and-re-post.
- A precondition of A2 that is not met, or log commands that do nothing, are the set-up's and not the mechanism's: the
  first is put right and the run starts again, the second falls under "A2 carries it".

Still owed after the spike, as before it: the same A1 recordings on the Jelly Star test phone (never the kid's phone; it
needs the user, §10), before step 2 shows the row in release builds.

#### 12.7.1 The run sheet

For whoever runs the spike; it needs nothing else from §12. `jelly36` only - no phone, and never the kid's. `P` is
`me.vibb.launcher.debug`. Every result is written down as it is made, with the file it rests on (log, dump, screenshot,
recording). No leg is made to pass by changing the build: a failed leg is the result.

1. **The build.** Branch `spike-78-twins` off `kid-look-1` at 67642f31; never merged, never pushed to `main`; the debug
   build only. Exactly this, and no test written, changed or run (several unit tests fail on it by design):
   - `ui/look/PageLook.kt`: `applyNightMode` and every call of it gone, so `setApplicationNightMode` is called nowhere.
     A table pairing `DayActivity`, `EventActivity`, `EventEditActivity` with `LightDayActivity`, `LightEventActivity`,
     `LightEventEditActivity`: the class for a page in a look, the look of a class. `closesTask` true for both roots.
     `change` in this order: finish the live pages; remove the page tasks; read `appTasks` again and, if a page task is
     still there, `Log.e("PageLook", ...)` and return; store; then `applied = mode`.
   - `ui/LauncherLocales.kt`: the unlocked start's language ask switched off (`atUnlockedStart` returns at once).
     Nothing else of it touched.
   - `ui/day/`: the three page classes `open`; the three light classes as empty subclasses beside them; the intent
     builders (`DayActivity.intent`, `EventActivity.intent`, `EventEditActivity.newIntent`, `editIntent`) take the class
     from the table with the applied look.
   - `ui/look/KidPageActivity.kt`: `lookNightMode()` from the table by the activity's own class (no row: the applied
     look); in `onCreate`, before any content, `Log.e("PageLook", ...)` when the class's look is not the applied one -
     the line alone, no removal.
   - `res/values/styles.xml`: `KidPageNightTheme` and `KidPageLightTheme`, both with `KidPageTheme` as parent (so
     `windowDisablePreview` is still inherited), each setting `android:windowBackground`,
     `android:windowSplashScreenBackground`, `android:windowLightStatusBar`, `android:windowLightNavigationBar` and
     `android:enforceNavigationBarContrast` as literals: the night one on `@color/kid_ground` (#0C0C14), `false`,
     `false`, `true`; the light one on a new colour in `values/` alone, #F6F2EF, `true`, `true`, `false`.
   - `AndroidManifest.xml`: the three `.ui.day.` entries on `KidPageNightTheme`; a copy of each for its light class on
     `KidPageLightTheme`; all six with `android:configChanges="orientation|screenSize|uiMode"`.
   - Leg B: `KidSoundPickerActivity` made a `KidPageActivity` on `KidPageTheme` (its manifest entry otherwise as it is).
   - Leg C, in the debug source set: an activity with a translucent theme, `noHistory`, `excludeFromRecents`, not
     exported, that starts `DayActivity.intent(...)` and finishes; and a debug hook that posts one notification whose
     `PendingIntent` names it.
2. **The set-up.** Build, then stop Gradle's daemons before the emulator starts (the first pass ran out of memory with
   both). Restore the clean `jelly36` AVD, install, provision as `docs/testing/emulator.md` does, set the launcher's
   language to one the phone does not have. Write down: `cmd package resolve-activity` for HOME names `P` (**if not:
   stop, it is the set-up**); the pid of the process that owns Home; the resource ids of the two themes (the build's
   `R.txt`, or `aapt2 dump resources` on the APK - either from memory). Start a logcat and an events log to files that
   run to the end, across the reboots.
3. **The lines.** Run the two enabling commands of §12.7 (again after every reboot). Open Kalender once and look for
   "Creating SplashScreenStartingData" and "addSplashScreen for package: " in logcat. Write down which print. Where one
   does not, try no further: from here on "A2 carries it" holds - every open of steps 6, 7 and 10 is made a second time
   with the process held (for a card in Recents: held before the tap) and judged as step 4 judges.
4. **A2, eight holds** (each look, each phone theme, making the task and bringing it forward): tap, SIGSTOP the Home pid
   at once (read it again after every reboot and kill), `dumpsys window windows`, a screenshot, SIGCONT. Pass: an
   `APPLICATION_STARTING` window on the look's class, the page area in the look's ground (or the page in its look on a
   snapshot), no page created or resumed in between, no ANR dialog. **One hold failing stops the spike here**; report
   it.
5. **A1 and A3, twelve recordings** (tile and glance row, Mørk and Lys: making the task with the phone dark and light;
   bringing it forward with the phone's theme unlike the look). Pass per row: no frame of the other look's ground in the
   page's area; the lines name the look's theme id and colour (`ff0c0c14` Mørk, `fff6f2ef` Lys), a snapshot only when
   the task was alive; the events log has one `wm_on_create_called` when the task is made, none when it is brought
   forward, no relaunch line. State each row's real frame count over the opening second; under thirty, record once more.
6. **A5, a change of look** with Kalender's task behind, both ways. Pass: the look changed; no `.day` task in
   `dumpsys activity recents` right after; the next open makes one `.day` task with the new look's class alone; the
   lines show a splash in the new theme and colour; never two classes in a task, never two cards.
7. **A6 and A7.** In each look, with the event page on top of Kalender's task: (a) reboot, unlock, open the card; (b)
   with the task behind Home, `kill -9` the Home pid (not `am force-stop`), open the card; (c) the same, opened from
   Home's tile. Pass: no frame of the other look's ground; the class created is the look's; the lines show a splash in
   its theme and colour and no snapshot. After each of the two reboots (a change of look lies between them):
   `cmd locale get-app-locales P` still the launcher's language, and Home created once with no relaunch line.
8. **A4, the phone's flips.** Kalender in front, then behind Home, then the event page in front: `cmd uimode night no`
   and `yes`, Battery Saver on and off. Pass: no create and no relaunch line for a page; a date picker, another day and
   newly scrolled rows after the flip are in the page's look; a recording of the bring-forward after a flip made behind
   Home has no frame in the phone's look.
9. **Leg B.** Lys with the phone dark and Mørk with it light: the sound picker from kid Settings. Pass: no
   `Splash Screen` window for it; no frame of the other look's ground.
10. **Leg C.** The notification posted in one look and tapped in the other, both ways. Pass: the class created is the
    stored look's; the lines name its theme and colour; no frame of the other look, no black or empty frame; the entry
    not in Recents. Does not gate.
11. **A8, over the whole logcat file.** Pass: no `Can't change activity type once set`, no `E PageLook`.
12. **Noted, not judged**: the milliseconds of white ink on the Lys starting window (step 1: 165-498); 3-button
    navigation in Lys with the phone dark; force-invert if a switch turns up.
13. **The report**: one row per step - pass or fail, and the file it rests on - and the first line says which log groups
    printed. What stops the spike on the way: step 2's home-app check and step 4. Everything else is run to the end and
    reported whole; the verdict is §12.7's "Pass" list, and the architect's to draw.

#### 12.7.2 The run

Run on 2026-11-11: branch `spike-78-twins` at `eb398122` (a throwaway, never merged), the debug APK with SHA-256
`00892561a28c20b69f04cbb784c436778807e7f15d4f9fc5a41cbc66f3fd54d3`, on a restored clean `jelly36` (480x854, Android 16,
build `BE2A.250530.026.D1`, gesture navigation, not rooted). **Every gating leg passed** (A1-A8), so step 1 is reworked
as §12.4 says. Nothing in §12.2 changed with the run. By the sheet's steps:

| Step | Result |
|---|---|
| 2, the set-up | HOME resolves to the debug launcher; `dumpsys uimode` holds no night mode for the app; the launcher's language `nb`, the phone's `en-US` |
| 3, the lines | Both log groups print, the server's and the Shell's |
| 4, A2 | Pass, eight holds of eight: with the process stopped the page's area is 100.0 % `F6F2EF` in Lys and 100.0 % `0C0C14` in Mørk, in both phone themes; no ANR dialog |
| 5, A1 and A3 | Pass, twelve recordings of twelve: one create of the look's class when the task is made; a bring-forward from the tile shows a snapshot with no create, one from the glance row a solid splash; no relaunch line |
| 6, A5 | Pass both ways: the `.day` task left Recents about 350-450 ms after the tap; the next open made one `.day` task with the new look's class alone; no `E PageLook` |
| 7, A6 and A7 | A6 passes on the routes that could be run (below). A7 passes: `nb` after both boots, Home created once, no relaunch line |
| 8, A4 | Pass: `cmd uimode night no` and `yes` and Battery Saver, in front of Home and behind it: no page created, no relaunch line, the pictures before and after identical; a picker and newly bound rows after the flip in the page's look |
| 9, leg B | No starting window either way. Mørk with the phone light: right. Lys with the phone dark: a finding (below) |
| 10, leg C (does not gate) | Pass both ways: `LightDayActivity` with theme `7f130134` and colour `fff6f2ef`; `DayActivity` with `7f130135` and `ff0c0c14`; the entry had no starting window |
| 11, A8 | Pass: 334,055 log lines, none with `Can't change activity type once set`, no `E PageLook` |

Over the whole pass the system drew 95 starting windows for a page, none against a rule: 59 splashes (29 in the light
theme, 30 in the night one) and 36 snapshots (20 light, 16 night).

**Step 7's card routes could not be run as written.** After each reboot the system restored the `.day` task, and it
left Recents about 5 s after the kiosk locked again - before a card could be opened. After `kill -9` the task stays in
`dumpsys activity recents`, but the overview shows no card for it. Run instead, in each look: from Home's tile, and with
`adb shell am task focus <id>` on the task that was still listed, then the tile again. Each time the look's class was
created again, with a splash in its theme and colour and no frame of the other look. A tap on the card itself was not
seen: `am task focus` stood in for it.

**Leg B's finding, and what step 1 does about it.** For leg B the spike made `KidSoundPickerActivity` a page on
`KidPageTheme`. In Lys with the phone dark it showed its night ground (97.6 % flat night, the largest paper share
0.2 %) under the light look's ink (the title `FF1B1B1F`, dark status-bar icons): the picker's layout paints
`@color/kid_ground`, a night token, and only the theme and the text followed the look. So the mechanism for a one-class
page in Home's task holds (no starting window, R9), and the layouts are what is missing. Step 1 builds nothing for it:
the sound picker stays what it is on `kid-look-1`, a non-page activity pinned to night with a night theme (§12.4's
"stays" row), so it is whole night in both looks and both phone themes. For step 2 the finding is §5.1's rule: a list
that becomes a page names page tokens in its layouts before it takes the page's theme.

**Noted, not gating.**

- The status bar's ink on the Lys starting window is white for about 125-345 ms over seven openings (step 1: 165-498).
- 3-button navigation in Lys with the phone dark: the buttons are white on the paper, all but invisible (§12.9 point
  4). Changing the navigation overlay relaunched the page in front - the system's own, and the pass's only relaunch
  line.
- No force-invert switch on the image.
- Whether a bring-forward shows a snapshot or a splash depends on whether the phone's theme changed while the task was
  behind; both are in the task's own look.
- The unlocked start without the two system calls (the spike had the language ask switched off, step 1 keeps it as
  built): the look 122 ms on the first start after the install, then 3 ms and 6 ms; the language 0 ms.
- A picker's buttons (`VibbPickerDialog`) have dark ink in Mørk in both phone themes: not an effect of a flip and not
  the spike's, noted for step 2's pass.
- 18 ANR lines of the system's at the boots, none the launcher's.

### 12.8 What this replaces

| Where | Read it as |
|---|---|
| D5 (§3) | The mechanism is two manifest activities per own-task page plus the `values-notnight` tokens; every other screen pinned to night |
| §0's and §5.3's hypothesis, §5.3's first bullet ("The app's night mode") | Gone (R1). The starting window's ground comes from the variant's manifest theme (R2, R3) |
| §5.3's second bullet, "A page does **not** declare `uiMode`" | It does (R8); the mode comes from the class (R4). It is no longer a belt under a system call: it is the content's look |
| §5.3's third bullet, "there is no reliable public reading ... once an app override exists" | No override exists now; the pin to night stays for the bullet's other reason, the shared resources |
| §5.3, "Decision, 2026-11-11", "Open, for step 2" and the second pass's "Proposed, not built" | The look no longer takes part: the third way out named there, "not using the system's per-app night mode", without giving up the starting window. The language's lines stand as the record of what was built; §12.4 and §12.9 say where they go |
| §5.4 step 2 ("then `setApplicationNightMode`", "`commit()` ... first") | R6: tasks first, the store after, no system call |
| §5.4 step 3 | The unlocked start reads the store and writes `applied = dark` when absent; no call. "A system call that fails is caught" (Fix round) has nothing left to catch |
| §5.5, "the icon ink comes from `kid_page_light_bars`" | From literal items in each variant's theme; the starting window's ink from its ground's lightness (§12.3) |
| §5.6, rows 1 and 2 | The note under the table |
| §8 step 0 | §12.7. §8 step 1's "the unlocked-start re-apply" and `PageManifestTest`'s "without `uiMode`" are replaced by §12.4's list, built as a rework of `kid-look-1` before it is merged |
| §8 step 2 | Adds: twins for Meldinger's own-task pages, both roots in `closesTask`, `SmsNotifier`'s intent through R7, the lists and the phone book on one class (R9), §12.9's phone-book check. L5's line (a Home resume between an open and the page's creation) stands |
| §9, "The starting window" and "OEM builds" | §12.6 |
| §10 | One recording per look on the Jelly Star is still owed (§12.7) |

### 12.9 Open points

1. **The phone book's doors from outside (step 2).** Opened from Home it is in Home's task (R9). Read, not seen: a
   `tel:` link in another app puts it in that app's task as standard-type, started by an application, so
   `windowDisablePreview` holds and there is no starting window (AOSP, `validateStartingWindowTheme`); a missed-call
   notification's tap and the call button come from SystemUI and the system with `NEW_TASK` and should land in Home's
   task by affinity (inferred). Step 2's pass must show `dumpsys window windows` without a `Splash Screen` for both
   doors with the look unlike the phone's theme. If one shows, that door gets R7's look-less entry - the persistent
   preferred activity for `DIAL` would then name the entry.
2. **The language fix, as its own item on main.** Decide there, not in this design: keep the unlocked start's ask
   everywhere, or only in a process that was alive before the unlock (the one case main has; it would give back "the
   first ask of a process's life always gets through" to every other process), and whether the close of Meldinger's
   task at a refused change is worth its price without the look's need. Its facts - the language of the PIN lock,
   Home and the channels on a boot where the process was alive before the unlock - are main's to take, on main; the
   spike's A7 no longer carries that note.
3. **The channels' names** in a process alive before the unlock: the fix is built and not seen running; it belongs to
   point 2.
4. **3-button navigation in Lys with the phone dark** and **the late status-bar tint**: SystemUI's (§12.3), open for
   step 2 on a real phone. Whether `enforceNavigationBarContrast` helps the first is not read.
5. **Force-invert**: no switch found on the image; unchanged.
6. **AppCompat 1.7.1's own source** for R8: **closed** - read in AndroidX 1.7.1 by the design QA, and again for this
   revision (R8's lines). A4 stays the proof on the device.
7. **A release build and the Jelly Star**: as in the code QA's open list.
