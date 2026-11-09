# 79 - The kid's phone book as a list: missed calls first, search, the kid's own pictures

Status: design note by the architect, 2026-11-09, on `main` at 23a92f3a, branch `design-79-phone-book`. **As built**
(2026-11-09, branch `phone-book-list`, steps 1-4 and the contact request's screen): `docs/notes/launcher/calls.md`,
"The phone book as one list (design 79)"; `docs/notes/launcher/home-and-photos.md`, "The kid's own contact pictures
(design 79 §5)"; `docs/notes/launcher/ui-polish.md`, "The phone book's row (design 79 §3.4)"; the emulator run in
`docs/testing/emulator.md` §6v "design 79". Where the build decided what this note left open, calls.md's section says
so. Mockups: `docs/design/79-mockups.html` (the list with missed calls first, large text, search, "Fant ingenting",
Meldinger with search, the contact card's long-press, the "Bilde av …" sheet and the framing, the contact request with
a picture).

**Approved 2026-11-09 ("Bygg 79") with one change: the emergency numbers go to the bottom of the list, not A-Å among
the contacts** (§3.1; the user: "ikke legg nødnr på toppen, man skal jo i 99.99% av tilfellene ha tak i noen andre..
Legg den på bunnen"). The sketch, the rules and the mockups follow it.

**Revision 3, 2026-11-09: the user's decisions. Nothing is open.**
- **One list, no filtering but search** (the user: "Jeg likte ikke tabs eller noe av det egentlig. La oss kjøre at vi
  ikke har filtrering utenom søk"). Revision 2's layout A (tabs) is dropped, and so are favourites altogether: no star
  on the card, no filter, no stored stars.
- **Unseen missed calls first, then everyone A-Å** ("Når det er usette missed calls så kommer de med missed calls først
  i lista, så a-å"). The contacts with unseen missed calls lead the list, newest first, with the red line and the
  badge; then every contact A-Å (the parent's order stays on Home).
- **A missed call is seen once the phone book has shown it** ("Etter at de har vært sett så går det bort neste gang man
  går inn i kontaktlisten"): it leaves the top **the next time the phone book opens**, never while the kid is looking
  (§3.2). Opening the card, calling back and the row's buttons still count as today.
- **Search from 8 rows**, names only, in the phone book and Meldinger.
- **The kid's pictures stay on the phone**; a long-press on the photo changes it (revision 2, unchanged).

**Revision 2** (efccd200): two layouts offered, the camera chip replaced by a long-press, the pictures phone-only, and
the design QA of revision 1 (`docs/review/qa-79-design.md`, 43982b1f: 0 Critical, 2 High, 4 Medium, 12 Low, 10 Nit)
folded in - §12 maps every finding. **Revision 1** (b98cf66d): the list with five groups, the pictures on the server.

The user's words (2026-11-09):
- On the contact-request mockup's list: "Jeg liker listen!"
- Row actions: "liker de knappene for ring og melding, og om man trykker andre steder på kontakten så kommer kortet opp
  istedet".
- Missed calls: "Burde missed calls vises strukturert her på noe vis?"
- Search: "Vi burde ha en søkefunksjon både i kontaktliste og i sms? Gjenbruke den vi har i musikk appen?" and, on
  Meldinger's search: "På 3, tenkte egentlig bare kontakt navn!"
- Pictures: "Kanskje barnet burde kunne velge/ta et bilde som den kan styre per kontakt også, kanskje både i søknad og
  at hen kan sette og endre via kontakt kortet også?"
- On revision 1: "… ble så knotete å dele opp i så mange lag nå synes jeg, da var forrige bedre … Glem et lite kamera
  på bilde, la det å holde inne bilde gi dialogen om å sette nytt bilde feks eller noe sånt? Server trenger ikke se
  bilde barnet selv legger til?"
- On revision 2: "Jeg likte ikke tabs eller noe av det egentlig. La oss kjøre at vi ikke har filtrering utenom søk. Når
  det er usette missed calls så kommer de med missed calls først i lista, så a-å. Etter at de har vært sett så går det
  bort neste gang man går inn i kontaktlisten?"

## 0. The shape

- **The phone book becomes one list** of solid night tiles on the kid's wallpaper, a search pill under the header (from
  8 contacts), the contact request's "+" in the header.
- **Order:** the contacts with unseen missed calls first, newest call first; then everyone else A-Å; the emergency
  numbers last (the user's change at approval, §3.1). No headers, no groups, no filter. The request's answer shows
  once at the very top; open requests sit after the contacts under one small "Venter på svar" label, before the
  emergency numbers.
- **Each row:** the avatar (photo or initial, today's badge), the name, a second line only when there is something to
  say (a missed call in red, unread messages, "Nødnummer"), and two round buttons, **Ring** and **Melding**. A tap
  anywhere else opens today's contact card.
- **Seen:** a missed call shown at the top counts as seen when the kid leaves the phone book; the next visit has it in
  its A-Å place without the red line. The badges on Home and in the list follow the same mark.
- **Search** in the phone book and Meldinger: the music app's pill and rules in Views; names only; folded so "o" finds
  "ø"; "Fant ingenting", and in the phone book "Be om å legge til «…»".
- **The kid's own picture per contact:** a long-press on the photo (card, request form) opens "Bilde av …"; chosen or
  taken, framed with a drag and a pinch, re-encoded on the phone, kept on the phone only. It wins over the parent's
  picture on the kid's phone.

## 1. What exists today (verified against the code, `main` 23a92f3a, and the named branches)

- **`calls/PhoneBookActivity.kt`** draws the wallpaper (`showsSystemWallpaper`, `WallpaperGround.apply(.., ownGround =
  true)`), then `include_kid_header` ("Telefonbok", back), an info line (`calls_off`, `calls_phone_book_empty`) and a
  `RecyclerView` with `GridLayoutManager(this, 3)` over `PhoneBookAdapter` (`item_kid_contact.xml`: a 64 dp avatar, the
  `KidBadge` at its top end, the name in `WholeWordTextView`, labels in `KidInk`). `submit` calls
  `notifyDataSetChanged`; the photo, wallpaper, Element-missed and messages listeners each call `render()`. A tile has a
  click and no long-press. `onResume` calls `MissedCallNotifier.dismiss` (our missed-call notification goes, the
  call log's unread missed rows are marked read).
- **What is listed:** `phoneBookView(state)` - the managed rules' `phoneBook` (`outbound`, the PWA's "I telefonboken"),
  in the parent's order; with calls off only the contacts whose number is an emergency number; with no readable rules
  the 112 tile. It is also the call-log viewer and the missed-call notification's target (`EXTRA_MISSED_CONTACT` opens
  that contact's card).
- **The contact card** (`ContactSheet.show`, `sheet_contact.xml`, `KidSheetDialog`): photo with a ring (red for an
  emergency number), badge, name, the missed and unread lines, **Ring** (off while another call is live), **Melding**
  (our own thread when `opensOwnThread`, else `resolveMessageButton`'s app with "Meldingen åpnes i <app>", else no
  button), Lukk. Opening it marks the contact seen (`MissedCallsRepo.markSeen(number)`, the time now) and dismisses the
  notification. It opens from Home's contact row too (a tap or a long-press). Its photo has no listener.
- **Missed calls and "seen"** (`MissedCallsRepo`, `MissedCalls.kt`): the call log of 14 days; a missed call counts
  after the contact's **seen mark** (CE prefs `missed_calls_seen`, number -> time) and after the last outgoing or
  answered call with that number; plus missed Element X calls (counted after the same mark). The seen marks drive
  **every badge** - Home's contact avatars, the phone book, the card (`ContactBadge`: missed plus unread messages).
- **The notification and the lock glance:** our missed-call notification (design 12) and the glance's missed-call rows
  (design 25, r11) count after `noticeMarks(seen, dismissed)` - the seen mark or the row's "Fjern" on the lock. Opening
  the phone book cancels the notification and marks the log read (above), so the glance's missed rows go then.
- **Photos** (`ContactPhotos`): the parent's picture per contact (`call_policy.contacts[].photo`, a SHA-256), cached in
  `filesDir/contact_photos` (CE), decoded in the background into a 16 MB `LruCache` (full ARGB_8888, the server sends at
  most 512 px); `cached()` never touches the disk. Bound by `KidAvatars.bindContact` on Home, the phone book, the card,
  the glance, the call screen (direct-boot aware) and Meldinger. An emergency number never shows a photo.
- **Meldinger** (`ui/messages/`): rows on `bg_day_event` (`kid_music_tile` #1B1B28, 18 dp, white 12 % ripple), newest
  first; "Ny melding" lists the `sms_out` contacts. Its own solid ground (design 78's light look applies to it).
- **The music app's search** (`music/.../ui/ListScreens.kt` `SearchScreen`, `logic/LibraryView.kt` `search`): a 44 dp
  pill (`Palette.card`, 22 dp corners, an 18 dp magnifier, 16 sp 700), live hits by a plain `lowercase().contains`, at
  most 12, "Fant ingenting" as `EmptyState`.
- **Picking a picture** (design 77 step 3b, branch `sms-mms`, in its code-QA fix round): `ui/messages/PicturePick.kt` -
  the system photo picker pinned to the system's picker package, the camera only as the parent-allowed camera app,
  `pickedUriAcceptable`, the bytes (at most 30 MB) decoded in the isolated parse process with EXIF orientation applied
  (at most 1280 px), our own EXIF-free JPEG (`PictureEncoder`). `KidScreensEscapeTest` lets `PicturePick.kt` alone name
  `ACTION_PICK_IMAGES` and `ACTION_IMAGE_CAPTURE`.
- **The contact request** (design 77 §14): branch `sms-contact-request` 58b7f07c (API, contract, server, the phone's
  model `calls/ContactRequests.kt`), WIP branch `sms-contact-request-kid-form-wip` 71f84c1a (the "+", the form in the
  grid's place, the request rows above the grid).
- **No diacritic fold exists.** D17's fold (`SmsSenders.fold`) is exact on purpose; Dagen's title rules lowercase per
  code point; the music app's search is a plain lowercase.
- **Android backup is off** (`allowBackup="false"`).

## 2. Scope

**In:** the phone book screen, the card's long-press, search in the phone book and Meldinger, the new "seen" moment,
the kid's picture per contact (on the phone), the contact request's picture, where the request's rows sit.

**Out:**
- Home: its contacts row stays the parent's "På Hjem-skjermen" list, in the parent's order, with its tap and long-press.
  It shows the kid's picture (it binds avatars the same way); its badge follows the seen mark as today (§3.3).
- The lock glance, the call screen and notifications: unchanged, except the kid's picture through the same binding.
- Favourites: none (the user, revision 3).
- Design 78's light look: the phone book stays on the wallpaper, out of 78's scope; Meldinger's pill takes 78's page
  tokens when 78 lands.
- Text-only contacts (`sms_out` without "I telefonboken"): not in the phone book, as today; Meldinger reaches them.
- The server, the PWA and every contract: no change.
- The music app's own search keeps its plain lowercase.

## 3. The list

### 3.1 Layout

```
[‹]  Telefonbok                      (+)
( 🔍  Søk                          )          from 8 contacts
  [Kim     Ikke lagt til                ]     a request answer, once
  [Pappa ² 2 tapte anrop 14:02  (☎)(✉)]      unseen missed calls first, newest first
  [Ola ¹   Tapt anrop i går     (☎)(✉)]
  [Anna                         (☎)(✉)]      then everyone else A-Å
  [Bestemor                     (☎)(✉)]
  [Mamma ² 2 uleste meldinger   (☎)(✉)]
  ...
  Venter på svar
  [Lina    Venter på svar               ]
  [Ambulanse  Nødnummer         (☎)   ]      the emergency numbers last, A-Å among themselves
```

- **The emergency numbers go last** (the user, approving the build, 2026-11-09: "ikke legg nødnr på toppen, man skal jo
  i 99.99% av tilfellene ha tak i noen andre.. Legg den på bunnen"): after "Venter på svar", A-Å among themselves, in
  the same look with the asking Ring (§3.5). An emergency number with an unseen missed call sits in the top like any
  other contact (that is what the top is). With calls off only they are listed, A-Å, as before. While searching they
  are the last hits too (§4.1).
- **No headers** but "Venter på svar" (those rows can't be called). The red line says why a contact is at the top.
- A contact shows once: at the top while its missed call is unseen, else in its A-Å place (an emergency number: at the
  end).
- **A-Å:** `java.text.Collator` in Vibb's language (nb puts Æ, Ø and Å last), strength SECONDARY, ties by contact id;
  the `Collator` kept per locale.
- **The ground:** the phone book keeps the wallpaper. Every row and the pill are solid `kid_music_tile` tiles, so they
  read on navy, a light photo and a busy one. Only the info line, the "Venter på svar" label and the live count are
  text on the wallpaper, in `KidInk` dim.

### 3.2 The top, and when a missed call is seen

**The top** holds the contacts with an unseen missed call - phone or Element X, counted as today after the seen mark -
newest call first.

**The visit's order is fixed when the phone book opens** (`onStart`, from the missed calls as loaded then; kept across a
rotation in the saved state):
- nothing leaves the top while the phone book is in front, so no row jumps under the kid's finger;
- a contact whose **new** missed call arrives during the visit joins the top (news may appear; nothing leaves);
- a contact the kid deals with during the visit (the card, Ring, Melding) stays where it is, its red line and badge
  gone at once (it is seen, below) - the only case where a row at the top has no red line, and only until the kid
  leaves.

**Seen.** A missed call counts as seen when any of these happens:
1. **The phone book showed it.** Its row was on screen, at the top, for at least 1 s (`PHONE_BOOK_SEEN_MS`) while the
   phone book was resumed and focused - a phone book that flashed under the time-rule screen or was left at once doesn't
   count. The mark is written **when the kid leaves the phone book** (`onStop`, not for a configuration change), for
   each contact whose top row was shown so, **up to the newest missed call that row showed** (`markSeen(number,
   upToMs)` - a call that came after it still counts; the mark only ever moves forward, so a card opened during the
   visit, which marked "now", is never undone). The next visit lists the contact A-Å, without the red line or the
   badge.
2. The kid opens the contact's card (today; the mark at that moment).
3. The kid taps **Ring** or **Melding** on the row, or **Melding** on the card (new: each means the kid dealt with that
   person; the card's Ring already does today).
4. A later outgoing or answered call with the number (today's call-log rule), or 14 days (today).

"Shown on screen" is the rows the `RecyclerView` laid out inside its visible bounds at some point of the visit
(tracked by contact id from the layout manager's visible range after each layout and scroll); with many missed calls a
row below the fold the kid never scrolled to stays at the top for the next visit. A search typed at once doesn't hide
it: the pill never takes focus by itself, so the list's first frame is the unfiltered top.

### 3.3 How this sits with Home's badge, the notification and the lock glance

One seen mark serves them all, so they never disagree about a contact; they only differ in **when** they look at it:
- **Home's contact badges and the phone book's badges** read the seen marks (today). While the kid is in the phone
  book nothing changes; when the kid leaves it, the marks are written (rule 1) and Home's badge for those contacts is
  gone at the same moment the row leaves the top. They agree.
- **The missed-call notification and the lock glance's missed rows** go when the phone book **opens** - today's
  `MissedCallNotifier.dismiss` in `onResume`, unchanged. They are the "you have been told" signals; the phone book's
  top is "not yet dealt with this visit". So the notification and the glance row leave first, then the badge and the
  top after the visit. Nothing shows a missed call the phone book has already let go of: the glance counts after
  `noticeMarks(seen, dismissed)`, and seen only moves forward.
- A missed call **dismissed on the lock** ("Fjern" on its glance row, r11) is not seen for the badges (today: "Pappa 1"
  stays). It still leads the phone book's list, and the visit makes it seen. Unchanged rule, now with a natural end.
- A missed call that arrives **while the phone book is open**: our notification posts as today (the kid may be
  elsewhere in a moment), the contact joins the top (§3.2), and leaving after 1 s on screen makes it seen.

### 3.4 Row anatomy (`item_phone_book_row.xml`)

Designed against the **Jelly Star** (320 x 569 dp): 288 dp of content inside the page's 16 dp sides (QA H1). The
mockups are drawn at these sizes, 1 px = 1 dp at font scale 1.0.

- **Tile:** `bg_day_event` (#1B1B28, 18 dp, white 12 % ripple), min 64 dp; padding start 10 dp, top/bottom 8 dp, end 0
  (the buttons' targets reach the tile's edge); rows 8 dp apart.
- **Avatar:** 44 dp, `KidAvatars.bindContact` at 18 sp initials, the `KidBadge` at its top end lifted 2 dp, no white
  ring. No press scale and **no long-press on the row**: the row's press is its ripple; a row only opens the card.
- **Buttons:** two 38 dp discs, each centred in a 48 dp target, the targets touching (96 dp). **Ring**:
  `kid_sheet_button` (white 8 %) disc, the phone glyph in the new `kid_call_glyph` #7FC28F (6.5:1 on the disc over the
  tile). **Melding**: the same disc, the message glyph in `kid_accent` (6.9:1). Tonal, not filled: 50 green discs down a
  list would shout. Press: design 34's `PressMotion` (visual only) and an oval ripple; a 2 dp peach focus ring for a
  keyboard. Dimmed to 40 % and inert while another call is live (emergency excepted).
- **The text column** is 288 - 10 - 44 - 10 (gap) - 96 = **128 dp** at font scale 1.0: about 14 characters of the name
  (Nunito 800 16 sp), about 18 of the second line (13 sp 700).
- **Text:** the name, `kid_ink`, `WholeWordTextView` (never cut between letters), at most 2 lines; the second line at
  most 2 lines (it wraps; it is never cut to one), absent when there is nothing to say (the name centres vertically).
- **The second line is short** (the full sentence stays on the card and in TalkBack):
  - "Nødnummer" (emergency);
  - else unseen missed calls in `kid_missed_text` #FF8A8A (7.5:1 on the tile): "Tapt anrop 14:02" today, "Tapt anrop i
    går", "Tapt anrop 3. nov." earlier, "2 tapte anrop 14:02" for more (the badge has the count too); with Element X
    calls the count is both and the time the newest. The longest, "12 tapte anrop i går", is 20 characters;
  - then " · 1 ulest" (`contact_unread_short`, `kid_core_dim`) for unread messages, or alone "1 ulest melding";
  - else nothing.
- **Large text: the buttons get their own line.** Pure `rowLayout(contentDp, fontScale)`: **inline** below font scale
  1.3 with a text column of at least 120 dp; else **stacked** - the avatar and the text use the whole width (214 dp),
  Ring and Melding on a line under the text, from the text's start. `WholeWordsTest` gains the row at 288 dp, 1.0 / 1.3
  / 2.0, nb and en: no missed-call line cut, the test set's names whole.
- **Request rows:** the answer (once, at the top) on the tile with a `kid_music_control` disc and the initial; open
  requests at the end with a 1.5 dp peach-45 % outline and the request's picture or initial, "Venter på svar"; not
  clickable.

### 3.5 Buttons, emergency contacts, states

- **One pure function decides the card's buttons and the row's** (`contactActions`, the card's inline logic moved out
  of `ContactSheet.show` unchanged): **Ring** for every phone-book contact while calls are on (while calls are off only
  emergency contacts are listed, and theirs shows), off while another call is live (emergency excepted); **Melding** to
  our own thread (`opensOwnThread`), else `resolveMessageButton`'s app, else no button.
- **Emergency contacts** sit at the end of the list (§3.1), A-Å among themselves, with today's red disc and ring (3 dp,
  as on Home, the card and the glance - code QA (e), kept), never a photo, the line "Nødnummer".
  Their row **Ring** is the tonal red pair (`kid_emergency_tonal` disc, `kid_emergency_text` glyph) and **asks first**
  ("Ringe Ambulanse?", the existing `calls_confirm_title` dialog): a one-tap 112 from a scrolling list costs more than a
  call to Mamma. The card's Ring stays as today. The fail-closed 112 row (no readable rules): the red disc,
  "Nødsamtale", no buttons, a tap opens `EmergencyCall.confirm`.
- **Calls managed and on:** the list. **Calls off** (the parent's, or a time rule's copy): today's info line, the
  emergency contacts only, no search, no "+", no "Be om å legge til" (`canAsk = Managed && callsEnabled`, QA L2).
  **No readable rules:** the one 112 row. **Unmanaged:** no phone book, as today. **A time rule:** reachable only where
  calls are, as today; a rule beginning while it is in front brings the time-rule screen.
- **The keyboard:** the page is edge to edge, so the list's bottom padding takes the IME inset
  (`WindowInsetsCompat.Type.ime()`; `adjustResize` alone does nothing on API 35+, QA L3).

### 3.6 TalkBack

- A row is **one node**, role button: the full sentence - "Pappa, 2 tapte anrop i dag 14:02, 1 ulest melding";
  "Ambulanse, nødnummer". Activating it opens the card. The two buttons are focusable for a keyboard but
  `importantForAccessibility="no"`; the row carries **custom actions** "Ring Pappa" and "Send melding til Pappa" (or
  "… i Element X" - the one cue that it leaves Vibb; a sighted kid gets it on the card, QA N6, accepted). Fifty contacts
  are fifty swipes, not a hundred and fifty.
- A search announces "3 treff" / "Fant ingenting" through a polite live region on a **visible** view (QA N7): the
  "Fant ingenting" text, or a small count line under the pill ("3 treff", 12 sp `KidInk` dim), at most once per 500 ms
  of typing (`announceForAccessibility` is deprecated on API 36).
- TalkBack users get the same "seen" rule: a row counts as shown when it was on screen, whether or not focused.

### 3.7 Performance with about 50 contacts

- `LinearLayoutManager`, a diff over stable ids (`contact:<id>`, `request:<id>`), no item animator (as Home's grid).
- **Photos rebind in place:** a decode finishing calls `notifyItemRangeChanged(.., PHOTO)`; only avatars rebind.
- **List-sized thumbnails** (QA N8): `ContactPhotos` decodes a row-sized copy (at most 160 px, ARGB_8888, under 0.1 MB)
  for the rows, Home's row and the glance rows, and the full square only for the card and the call screen; each size its
  own cache key (`<hash>@160`), so 50 thumbnails take about 5 MB of the 16 MB cache.
- Computed once per render (QA N7): `CallSystem.isEmergencyOutgoing` per contact, `defaultSmsPackage`,
  `ElementRoomStore.lookup`, the message apps' usability (at most four packages) and their labels. A keystroke only
  filters (as built after code QA M2: `render()` gathers these inputs at resume and from the listeners, the search's
  keystroke runs `filter()` over them - no binder call, prefs decode or label lookup per character; Meldinger the
  same, its emergency check asked once per number per render). The search's match is one way (§4.2). The call log is read on IO, as today. The visible-range bookkeeping of §3.2 is a set of ids, updated after a
  layout or a scroll.

## 4. Search

### 4.1 The rules (the music app's, in Views)

- **The pill** under the header, not scrolling: 44 dp, 22 dp corners, `kid_music_tile`, an 18 dp magnifier in
  `kid_core_dim`, the hint "Søk" (phone book) / "Søk etter navn" (Meldinger), the text Nunito 700 16 sp `kid_ink`, a
  peach cursor, a 1.5 dp peach-50 % outline while focused, an "×" (48 dp target, "Tøm søket") while it holds text.
  `NoTextActions` and `importantForAutofill="no"` on the field (QA L3); input `text|textNoSuggestions` (not
  `textPersonName`, an autofill hint); IME action Search closes the keyboard. **It never takes focus by itself.**
- **Live:** every keystroke filters. While a query is typed the top dissolves: one list of hits, ranked - the name
  starts with the query, then a word in it does, then it contains it - each rank A-Å (QA N2); the matched part in peach
  (8.6:1 on the tile). Open requests are searched too and show as their waiting rows (QA L1), after the contacts'
  hits; the emergency numbers' hits come last, as in the list.
- **Empty:** "Fant ingenting" under a 72 dp tile-coloured disc with the magnifier in peach. In the phone book, while
  `canAsk`, only when nothing matches (requests included), with at least two letters and not all digits: a quiet button
  "Be om å legge til «Lina»" (en "Ask to add “Lina”"), the request form with the query as the name.
- **The form over a search** (QA N4): the pill hides while the form is up; Back closes the form, then clears the query,
  then leaves; after "Send" the query is cleared.
- **Back** (as built after code QA L10): with the keyboard up, Back only closes it (stock Android, wherever the focus
  is); then the form, then the pill's text, then the screen leaves. Meldinger: the keyboard, the delete confirm, the
  text, "Ny melding", leave. The query is kept across a rotation only: leaving the screen clears it.
- **From 8 rows** (`SEARCH_MIN_ROWS`: contacts in the phone book, conversations in Meldinger, contacts in "Ny
  melding"). Once shown, it stays while it holds text, even if a sync takes the list under 8 (QA N3).
- **Names only, in both places.** Meldinger matches the row's shown title, never message text (the user: "bare kontakt
  navn"); a conversation with a bare number shows digits as its title, so digits match there (QA N5). The phone book
  doesn't match numbers: the kid sees no number anywhere in it, so a hit on one would look like a mistake.
- Searching doesn't change "seen": the rule counts the rows shown at the top, and the first frame is always unfiltered.

### 4.2 The fold, the match, the model (pure)

- `ui/search/SearchFold.kt` - built from the shown string itself, code point by code point (QA L11): for each code
  point `lowercase(Locale.ROOT)`, NFD, the combining marks dropped, then the letters NFD can't split: ø -> o, æ -> ae,
  œ -> oe, ß -> ss, đ -> d, ł -> l, þ -> th, ı -> i. Each folded char records the index of the code point it came from
  (a combining mark folds to nothing and belongs to the letter before); the span is clamped before `setSpan`. "o" finds
  "Ørjan", "a" finds "Åse", "e" finds "Hélène". **One way** (code QA M3, decided 2026-11-09): only the **name** is
  folded; the query is lowercased and normalised but keeps Æ, Ø, Å and every marked letter as typed. A query letter
  matches a name letter that is the same after lowercase, and a plain query letter also matches what the name's letter
  folds to. So "o" finds Ola, Ørjan and Østby, but "ø" finds only Ø names and "å" never finds "Anna"; "é" finds only
  é. The same match serves Meldinger. **It never decides anything**: D17's sender names stay exact (a looser
  fold would let a "Vípps" through on the parent's "Vipps"); `SearchFoldTest` pins that nothing under `sms/` or in
  `CallRules.kt` calls it.
- `searchMatch(name, query): Match?` - `Match(rank: PREFIX | WORD | INSIDE, span)`; a word starts after a space, "-"
  or "'"; inner spaces kept; at most 40 characters of query.
- `calls/PhoneBookList.kt` - `contactActions(..)`; `phoneBookRows(view, missed, unread, visitTop, requests, query,
  collator, canAsk)` (the top from the visit's frozen set plus newcomers, the rest A-Å, the request rows, the dissolve
  and the threshold); `seenOnLeave(shown, topAtShow): Map<number, upToMs>` for rule 1.

### 4.3 The widget and Meldinger

- `ui/search/KidSearchField.kt` + `view_kid_search.xml`: one small View helper both screens include (the pill, the
  callback, Back's "clear first", the rotation state).
- **Meldinger:** the pill under the header in the list and in "Ny melding", hint "Søk etter navn", the same rank,
  highlight and "Fant ingenting" (no request button). It takes `kid_music_tile` now, as the rows do; when design 78
  lands it takes 78's row tile token like the rows.

## 5. The kid's picture

### 5.1 The long-press

- **On the contact card:** a long-press on the photo (`sheet_photo`, 132 dp) opens the **"Bilde av Mamma"** sheet;
  haptic `LONG_PRESS`, the photo dims 10 % while held. A plain tap on the photo does nothing, as today.
- **In the request form:** a long-press on the picture circle opens the same sheet; there a **tap** opens it too (the
  empty circle is a field, "Legg til bilde (valgfritt)").
- **TalkBack:** the card's photo becomes a node "Bilde av Mamma" with the custom action "Endre bilde av Mamma"; the
  form's circle is a button "Legg til bilde" / "Endre bilde".
- **No clash with what exists:** the card's photo has no listener today and the card has no gesture but the outside
  tap; Home's long-press opens the card with the finger still down, and a new window gets no part of a gesture begun in
  another, so it can't run on into the photo; the list's rows get no long-press; nothing drags from the card or form.
- **Offered** only for a contact in `rules.phoneBook`, not for an emergency number, not before the first unlock, not
  while the camera and the picker are both unusable (then the long-press does nothing and the action isn't there).
- **The form keeps its escape pin** (QA M3): `ContactRequestForm.kt` stays free of intents; its circle calls back into
  `PhoneBookActivity` (`onPicture`), which shows the sheet and starts `KidPhotoActivity`.
- **Discoverability** is a device check with the kids; the fallback is a one-time hint line on the card ("Hold inne
  bildet for å bytte det"), not a chip.

### 5.2 The "Bilde av …" sheet and `KidPhotoActivity`

- A `KidSheetDialog` over the card: the title, rows in the time-rule card's look - **Velg bilde** (the photo picker),
  **Ta bilde** (only with an allowed camera), **Fjern bildet ditt** (only while the kid's picture is set; the parent's
  picture or the initial returns) - "Bildet blir bare på denne telefonen." in `kid_core_dim`, Avbryt.
- Velg bilde and Ta bilde start `calls/KidPhotoActivity` (night, not on the wallpaper), which launches the picker or the
  camera at once through `PicturePick` and shows the framing with a picture back. Its own activity, so the result lands
  in one place whether the card came from the phone book or Home. A failed pick says "Kunne ikke bruke bildet".
- **Its guards** (QA L12): it checks the time rule at resume and watches it (`LockWatch`); it finishes when, at a sync,
  the contact leaves the phone book or becomes an emergency number; it stays out of `FREE_SCREENS`; it reads the
  picker's answer only as `PicturePick.picked(this, result.data?.data)`, never `Uri.fromFile`. It and the sheet are
  night like `PictureActivity`; design 78's scope table gets a row "out (night)" when either lands.
- **`PicturePick` is split** (QA L5): a shared `PicturePick.decode(context, source): Pair<Bitmap?, Problem?>` (read,
  the 30 MB cap, the isolated decode); the SMS `prepare` on top of it, unchanged; the contact path decode -> framing ->
  `PictureEncoder.forContact`. It moves to `ui/pictures/PicturePick.kt` with its escape pins.

### 5.3 Framing (`calls/PhotoCropView.kt`, pure `PhotoCrop.kt`)

- The decoded picture fills a square frame the page's width less 32 dp, covered; a black 55 % veil with the circle cut
  out shows what the avatar will show.
- One finger drags, two pinch: from 1x (cover) to `max(1, min(3, shortSide / 384))` (QA L6); a picture under 384 px is
  used at 1x, never enlarged. Clamped after every move. No slider, no zoom buttons, no rotation.
- "Bruk bildet" (peach) and "Avbryt"; the hint "Dra og knip for å velge utsnittet". With TalkBack "Bruk bildet" takes
  the centred square.
- The output: the square scaled to at most 512 px, `PictureEncoder.forContact` (JPEG, quality stepped down from 85 to
  at most 150 KB, no EXIF). The picked file is never decoded in the main process (design 77 D8).

### 5.4 Storage: the phone only

- **Where:** `filesDir/kid_contact_photos/<random>.jpg` (CE, app-private) and CE prefs `kid_contact_photos`: contact id
  -> {file, the contact's number when set, set at}. Never in DE storage, the status report, an upload or Android's
  backup (off).
- **Caps:** one per contact; at most 150 KB a file; at most as many files as the phone book has contacts and never more
  than 100 (`KID_PHOTOS_MAX`, bounding a bug) - about 15 MB at the worst.
- **Deleted with the contact** (QA M1's rule): only on a CE refresh of a `Managed` state (never `UnknownFailClosed`,
  never before the unlock - `ElementRoomStore.prune`'s pattern), a record whose contact is no longer **on the phone**
  (`rules.contacts`), or whose **number changed** (the parent re-pointed the row, QA L9), goes with its file. A contact
  out of "I telefonboken" but still on the phone keeps it (it shows in Meldinger). "Fjern bildet ditt" deletes at once.
  A file no record names is swept at the same pass. All on the store's own IO thread.
- **An in-memory snapshot, never a disk read in a binding** (QA H2): `KidContactPhotos` holds the records in memory,
  loaded on IO after the unlock and **empty before it**; a change fires `ContactPhotos`' listeners, which rebind in
  place. `KidAvatars.bindContact` reads the snapshot and `ContactPhotos.cached` only; the kid's JPEG decodes on
  `ContactPhotos`' background thread under the key `kid:<file name>` (random, so no collision with a SHA-256 hash and
  never a stale bitmap). The in-call screen before the first unlock sees an empty snapshot and an empty cache: the
  initial, as today. A source check next to `ContactPhotos`' "no disk in `cached`" pins it.
- **What shows** (pure `shownPhoto(contact, kidPhoto, emergency)`): emergency - none; the kid's picture; else the
  parent's; else the initial. Every avatar binding goes through it.
- **Wipe and reprovisioning:** the pictures are lost, as everything in CE storage. That is fine: they are the kid's
  decoration, the parent's pictures come back with the policy, nothing depends on them.

### 5.5 In the contact request

- The form's circle opens the same sheet ("Fjern bildet" only for the form's own draft) and the framing.
- **Before "Send" the request has no id** (QA M3): the picture is a draft, `cacheDir/request_photo_draft/<random>.jpg`,
  its name in the form's saved state (the camera can cost the launcher its activity). "Send" renames it to
  `filesDir/contact_request_photos/<request id>.jpg` and names it in the request's record in the same write; Avbryt,
  Back out of the form and a refused form delete it; drafts older than a day are swept when the phone book opens.
- It shows on the "Venter på svar" row.
- **The hand-over is one step with the reconcile:** `reconcileContactRequests` (pure) returns the new list **and** the
  hand-overs (request id -> contact id). Each file moves into the kid's pictures first; only then is the list written
  without the record. A crash between leaves the record, so the next reconcile does it again.
- **Who gets it:** only a contact that arrives **in the phone book**, and only if the kid hasn't set a picture for it
  already. A contact added text-only, a request `added` under **another** number, `declined`, `known`, `capped`, "not
  sent" and the 7-day expiry: the picture is deleted. Every kid picture stays one the kid can see and remove on a card.
- **No leftovers:** at each reconcile (CE, after the unlock) `contact_request_photos/` is swept against the records;
  `dropShownContactRequests` and the expiry delete their files in the same pass. Pictures of people don't outlive the
  request.
- No change to the request's API or contract; the parent doesn't see the picture.

### 5.6 Not a restriction, and not a safety issue

- **Nothing here is a restriction**, so nothing needs lifting: no switch, no limit the parent sets. The fixed rules are
  display rules (an emergency number never shows a picture, design 34 P3) and the storage caps. The time rules gate the
  page as they gate the phone book.
- **The parent can't see or remove the kid's pictures** - the user's choice: they come from the kid's own gallery or
  the parent-allowed camera and show only on the kid's own phone.
- **A picture can't change who a contact is.** Calls and messages are decided by the number; the name stays the
  parent's; unknown and withheld callers get the silhouette (`callAvatar`); an emergency number never shows one. A
  stranger can't set a picture - only someone holding the unlocked phone, inside the phone book. The worst case is a
  prank (Mamma's face on "Ola"): the name under it still says Ola, and the kid removes it in two taps. A re-pointed
  contact drops its picture (§5.4).
- **The lock glance and the call screen over the lock** show the kid's picture where they show the parent's today - the
  same audience, for a picture the kid chose to put there.

## 6. API and server

**None.** The contact request's API is unchanged.

## 7. For the contact request now (design 77 §14, on `sms-contact-request`)

- **The "+"**: in the header, a 40 dp filled peach disc with a dark "+" glyph (`kid_accent` / `kid_ground`, 9.9:1),
  described "Be om ny kontakt", while **`canAsk = Managed && callsEnabled`** (QA L2; the WIP has `Managed` only, true
  with calls off). The same rule gates "Be om å legge til".
- **The form**: in the list's place, as the WIP has it; `ContactRequestForm.open(name: String? = null)` for "Fant
  ingenting"'s prefill. The picture circle comes with step 4 here, as a callback into `PhoneBookActivity`.
- **After "Send"**: a calm confirmation in the form's place (a peach check disc, "Sendt", "Mamma og pappa får
  beskjed (code QA L5: future tense - the request goes with the next sync). Du ser det i telefonboka når Lina er lagt til.", "Ferdig"), then the list - in a long list the new waiting
  row is off screen.
- **The rows**: the WIP's rows above today's grid until step 2 here; then the answer once at the top and the open
  requests at the end under "Venter på svar".
- **The reconcile** should return the hand-overs as well as the list (§5.5), so step 4 doesn't change its signature.

## 8. Steps (small, each with its tests)

1. **The fold and the model** (pure, launcher; S). `SearchFold`, `searchMatch`, `rowLayout`, `PhoneBookList`
   (`contactActions` moved out of `ContactSheet` with no behaviour change, `phoneBookRows`, `seenOnLeave`). Tests:
   `SearchFoldTest` (the table, spans on the shown string, a decomposed "Björn", "İ", the clamp, no caller under `sms/`
   or in `CallRules.kt`); `PhoneBookListTest` (unseen missed first newest first, then A-Å with nb's Æ Ø Å; the frozen
   top - nothing leaves during a visit, a newcomer joins; the emergency and fail-closed rows; calls off; the threshold
   and its stickiness; rank; waiting requests among the hits; the button only from two letters with nothing matching;
   `seenOnLeave`: only rows shown at the top for 1 s, up to the shown call, a newer call still counts);
   `ContactActionsTest` (the card's cases, unchanged answers).
2. **The phone book as a list** (launcher; M+, the bulk of the work). The row at 288 dp with `rowLayout`'s stacked
   form, the short missed line, the visit's frozen order and the visible-range bookkeeping, the seen marks at `onStop`
   (`MissedCallsRepo.markSeen(number, upToMs)`), the row's Ring and Melding and the card's Melding marking seen, the
   adapter with the photo payload, the thumbnails, the asking emergency Ring, the request rows (after
   `sms-contact-request` is merged), TalkBack's node, sentence and actions, the "+" with `canAsk`, the IME inset. Tests:
   `WholeWordsTest` (the row at 288 dp, 1.0 / 1.3 / 2.0, nb and en), `KidPaletteTest` (the row, `kid_call_glyph`),
   `KidScreensEscapeTest` (below), `MissedCallsTest` (a mark up to a time; seen by Melding), `MissedCallNoticeTest`
   (the glance still counts after `noticeMarks`), `TranslationsTest`. Emulator: 3 and 50 contacts, two missed calls,
   leave and come back (the top gone, Home's badge gone at the same moment), a missed call during a visit, font 1.0 /
   1.3 / 2.0 at 320 dp, a light and a busy photo wallpaper, TalkBack, a call live.
3. **Search** (launcher; S-M). `KidSearchField` in the phone book and in Meldinger's list and "Ny melding"; the
   highlight, the live count, "Fant ingenting", "Be om å legge til «…»", the Back order with the form. Tests:
   `KidSearchTest` (Back order, threshold, prefill rules), `KidScreensEscapeTest` (`NoTextActions`,
   `importantForAutofill="no"`, no autofocus), `MessagesModelTest` (title match only), `TranslationsTest` (the "treff"
   plural).
4. **The kid's pictures** (launcher; M). Needs design 77 step 3b merged and the contact request merged. `PicturePick`
   moved to `ui/pictures/` and split, `PictureEncoder.forContact`; the card's long-press and TalkBack action, the "Bilde
   av …" sheet, `KidPhotoActivity`, `PhotoCropView` and pure `PhotoCrop`, `KidContactPhotos` (the CE store, its
   snapshot, caps and prune), `shownPhoto` in `bindContact`, the request form's circle, the draft and the hand-over.
   Tests: `PhotoCropTest` (cover, clamp, the zoom cap, under 384 px at 1x, the cut), `KidPhotosTest` (the snapshot empty
   before the unlock, prune on removal and on a number change but not on calls off or Unknown, the caps, the orphan
   sweeps, the draft's rename, every hand-over and delete of §5.5, emergency never), the source check,
   `KidScreensEscapeTest`, `TranslationsTest`. Emulator: pick, the emulator camera, a rotated JPEG, a 30 MB file, a
   contact removed at a sync, an incoming call after a reboot before the unlock (no crash), Home's long-press then the
   card's photo.

**`KidScreensEscapeTest`, named** (QA L4): add `ContactSheet.kt` (its targets pinned to `CallSystem.placeCall`, the
thread and `openMessage`), `PhoneBookList.kt`, the new adapter, `KidPhotoActivity.kt`, `PhotoCropView.kt`, everything
under `ui/search/` and `ui/pictures/`; move the picker/camera allowance to `ui/pictures/PicturePick.kt`; pin
`KidPhotoActivity`'s `PicturePick.picked(this, result.data?.data)` and no `Uri.fromFile`; keep the WIP's "the contact
request only asks" for `ContactRequestForm.kt`.

Steps 1-3 are launcher-only and follow each other right away; 4 waits for step 3b and the contact request. Notes to
update as each lands: `docs/notes/launcher/calls.md` (the phone book, the new seen moment), `home-and-photos.md`
(photos, the badges' seen mark), `ui-polish.md` (the row). No server, PWA or contract change.

## 9. Risks and checks

- **Pocket calls from a row:** one tap. The `RecyclerView` intercepting a touch during a fling is the guard;
  `PressMotion` is visual only (QA L7). The confirm covers emergency numbers. Device check: fling through 50 rows on the
  Jelly Star and count accidental presses; if any, the row's buttons ignore a click within 300 ms of the list leaving
  `SCROLL_STATE_SETTLING`.
- **"Seen" on a visit the kid didn't read:** a glance into the phone book for over a second sets the top seen. That is
  the user's rule; the notification and the lock glance already went at the open, so the badge and the top are the
  last reminders and go with the visit. Device check: does a kid who opens the phone book to call Mamma still notice
  Pappa's missed call at the top? (It is the first row, in red.)
- **The long-press is undiscoverable** (§5.1): the device check with the kids, the hint-line fallback.
- **The highlight's indices** with folds that grow or shrink: the per-code-point map and the clamp, tested.
- **The isolated decode is design 77's**: unavailable, the picture flow says it couldn't use the picture; calls, the
  list and the card are untouched.
- **Cache pressure** with 50 photos: thumbnails and the payload rebind; a `dumpsys gfxinfo` check on the Jelly Star.

## 10. Words (nb / en)

Every new string in `values-nb/strings.xml` and `values/strings.xml` (QA L10); English uses “…”, Norwegian «…».

| Key (sketch) | nb | en |
|---|---|---|
| `phone_book_search` | Søk | Search |
| `messages_search` | Søk etter navn | Search names |
| `search_clear` | Tøm søket | Clear search |
| `search_nothing_found` | Fant ingenting | Nothing found |
| `search_hits` (plural) | %d treff | %d result / %d results |
| `phone_book_ask_named` | Be om å legge til «%1$s» | Ask to add “%1$s” |
| `phone_book_waiting` | Venter på svar | Waiting for an answer |
| `phone_book_emergency_line` | Nødnummer | Emergency number |
| `missed_short` (plural) | Tapt anrop %1$s / %2$d tapte anrop %1$s | Missed call %1$s / %2$d missed calls %1$s |
| `contact_unread_short` (plural) | %d ulest / %d uleste | %d unread |
| `phone_book_action_call` | Ring %1$s | Call %1$s |
| `phone_book_action_message` / `_in` | Send melding til %1$s / … i %2$s | Message %1$s / … in %2$s |
| `kid_photo_title` | Bilde av %1$s | Picture of %1$s |
| `kid_photo_pick` / `kid_photo_take` / `kid_photo_remove` | Velg bilde / Ta bilde / Fjern bildet ditt | Choose a picture / Take a picture / Remove your picture |
| `kid_photo_change_action` | Endre bilde av %1$s | Change picture of %1$s |
| `kid_photo_local_note` | Bildet blir bare på denne telefonen. | The picture stays on this phone only. |
| `kid_photo_frame_hint` | Dra og knip for å velge utsnittet | Drag and pinch to frame it |
| `kid_photo_use` | Bruk bildet | Use this picture |
| `kid_photo_failed` | Kunne ikke bruke bildet | Couldn't use that picture |
| `contact_request_photo` | Legg til bilde (valgfritt) | Add a picture (optional) |

## 11. Open questions

None. The user's choices are in the header.

## 12. The design QA of revision 1, finding by finding

`docs/review/qa-79-design.md` (43982b1f, on revision 1 b98cf66d). **Moot** means the part it was about is gone: the
pictures are phone-only (revision 2), and there are no groups, tabs or favourites (revision 3).

| Finding | Where it landed |
|---|---|
| H1 the row doesn't fit 288 dp | Fixed, §3.4: the row at 288 dp - 44 dp avatar, two touching 48 dp targets, a 128 dp text column; the short missed line that may wrap; buttons on their own line from font scale 1.3 (`rowLayout`); `WholeWordsTest`; the mockups at 320 dp |
| H2 avatar bindings would read CE | Fixed, §5.4: an in-memory snapshot loaded after the unlock, empty before; `bindContact` reads memory only; decode under `kid:<file>`; a source check |
| M1 favourites pruned against the wrong list, before the unlock | Favourites are gone (revision 3); the rule it asked for governs the kid's pictures, §5.4: only a CE refresh of `Managed`, against `rules.contacts`; the long-press only for phone-book contacts |
| M2 the pending upload's states | Moot: no upload |
| M3 the request picture's hand-over | Fixed, §5.5 and §5.1: one step with the reconcile, only to a phone-book contact, deleted on every other outcome, the directory swept, a draft before the id, the form's circle as a callback |
| M4 pictures before the parent's switch | Moot: no server part, no switch |
| L1 the request button for a name already waiting | Fixed, §4.1 |
| L2 the "+" under calls off | Fixed, §3.5 and §7: `canAsk = Managed && callsEnabled` |
| L3 autofill, focus, the IME inset | Fixed, §4.1 and §3.5 |
| L4 the escape test's files, named | Fixed, §8 |
| L5 `PicturePick.prepare` is SMS-bound | Fixed, §5.2: `decode` split out |
| L6 the zoom cap under 1 | Fixed, §5.3 |
| L7 the pocket-call guard misattributed | Fixed, §9 |
| L8 server details | Moot: no server part |
| L9 a re-pointed contact keeps the old picture | Fixed on the phone, §5.4 |
| L10 the en strings | Fixed, §10; `TranslationsTest` in every step |
| L11 the fold's origin map | Fixed, §4.2 |
| L12 `KidPhotoActivity`'s guards | Fixed, §5.2 |
| N1 the pill under 8 rows in the mockups | Fixed: every mockup with a pill has 8 or more rows (the list runs past the screen) |
| N2 the order while searching | Fixed, §4.1: A-Å within each rank |
| N3 the pill under 8 after a sync | Fixed, §4.1 |
| N4 the form over a search | Fixed, §4.1 |
| N5 digits in Meldinger | Said, §4.1 |
| N6 no cue for a Melding that leaves Vibb | Accepted, §3.6 |
| N7 per-render work; a visible live region | Fixed, §3.7 and §3.6 |
| N8 RGB_565 banding | Taken, §3.7: list-sized ARGB_8888 thumbnails |
| N9 `shown` in the shared contract | Moot: no contract |
| N10 default on; emergency disagreement; step 2's size | The first two moot; step 2 is M+ (§8) |
