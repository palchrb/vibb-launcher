# 79 - The kid's phone book as a list: two layouts, search, favourites, the kid's own pictures

Status: design note by the architect, 2026-11-09, on `main` at 23a92f3a, branch `design-79-phone-book`. **Nothing is
built.** Mockups: `docs/design/79-mockups.html` (layout A with bottom tabs and layout B, one plain list, side by side;
search; Meldinger with search; the contact card with the star; the long-press picture sheet and the framing; the
contact request with a picture).

**Revision 2, 2026-11-09**, after the user's look at revision 1's mockups (b98cf66d). Changes:
- **No stacked groups in one list.** Revision 1's five groups (Tapte anrop, Forespørsler, Favoritter, Kontakter,
  Venter på svar) are gone. Two layouts to choose between instead: **A**, bottom tabs in the music app's look (Siste,
  Favoritter, Kontakter), and **B**, one plain A-Å list with search, missed calls only as the badge and the row's red
  line (§3, §4).
- **No camera chip on the avatar.** A long-press on the photo - on the contact card and in the request form - opens
  the "Bilde av …" sheet (Velg bilde / Ta bilde / Fjern bildet ditt), with a TalkBack action (§6.1).
- **The kid's pictures stay on the phone.** The server never sees them: revision 1's API, capability, contract, PWA
  part and per-phone switch are dropped (§6.4, §6.6).
- Revision 1's questions 1 (server or phone), 2 ("Bruk som kontaktbilde") and 4 (the request's picture) are settled:
  phone only, no such button, the request's picture waits on the phone.
- **The design QA of revision 1** (`docs/review/qa-79-design.md`, 43982b1f: 0 Critical, 2 High, 4 Medium, 12 Low, 10
  Nit) is folded in; §13 maps every finding to where it landed or why it is moot. The big ones: the row is designed
  against the Jelly Star's 288 dp of content at font scales 1.0, 1.3 and 2.0, with a short missed-call line that may
  wrap and the buttons on their own line from 1.3 (H1, §4.2); every avatar binding reads an in-memory snapshot only,
  never CE storage (H2, §6.4); favourites and pictures are pruned only after the unlock, against every contact on the
  phone (M1); the request picture's hand-over is one step with no files left behind (M3, §6.5).

The user's words (2026-11-09):
- On the contact-request mockup's list: "Jeg liker listen!"
- Favourites: "Kanskje barnet skal få lov til å trykke stjerne på kontakter for en favoritt liste eller noe."
- Row actions: "liker de knappene for ring og melding, og om man trykker andre steder på kontakten så kommer kortet opp
  istedet".
- Missed calls: "Burde missed calls vises strukturert her på noe vis?"
- Search: "Vi burde ha en søkefunksjon både i kontaktliste og i sms? Gjenbruke den vi har i musikk appen?" and, on
  Meldinger's search: "På 3, tenkte egentlig bare kontakt navn!"
- Pictures: "Kanskje barnet burde kunne velge/ta et bilde som den kan styre per kontakt også, kanskje både i søknad og
  at hen kan sette og endre via kontakt kortet også?"
- On revision 1: "På tlf liste så synes jeg det ble litt rotete med den mockup; hva med å legge tabs på bunnen, akkurat
  som musikkspiller istedet? Sist brukte som viser tidligere anrop og missed calls kanskje? Eventuelt bare missed
  calls. Og favoritter er en annen tab etc etc? Bare en ide men ble så knotete å dele opp i så mange lag nå synes jeg,
  da var forrige bedre. Lag noen nye mockups her. Litt usikker på om det er verdt det eller om vi bare skal beholde a
  til å på hele listen.. Glem et lite kamera på bilde, la det å holde inne bilde gi dialogen om å sette nytt bilde feks
  eller noe sånt? Server trenger ikke se bilde barnet selv legger til?"

## 0. The shape

- **The phone book becomes a list** of solid night tiles on the kid's wallpaper. Each row: the avatar (photo or
  initial, today's badge), the name, a second line only when there is something to say (a missed call in red, unread
  messages, "Nødnummer"), and two round buttons, **Ring** and **Melding**. A tap anywhere else opens today's contact
  card. This row is the same in both layouts.
- **Two layouts, for the user to choose (§3):**
  - **A - tabs at the bottom**, in the look of the music app's category tiles: **Siste** (recent calls, one row per
    person, missed ones in red), **Favoritter**, **Kontakter** (A-Å with search). Opens on Kontakter.
  - **B - one plain list**, A-Å with search, as the first mockup the user liked. Missed calls show only as the badge
    and the row's red line. Favourites are a star filter beside the search pill.
  - **Recommendation: B**, with A kept as the way to go if a call history turns out to be wanted. The user's doubt
    ("usikker på om det er verdt det") is fair and stays open (§12).
- **Search** in the phone book and in Meldinger: the music app's pill and rules in the launcher's Views. Names only,
  folded so "o" finds "ø". "Fant ingenting" when nothing matches; in the phone book it also offers "Be om å legge til
  «…»" into the contact request.
- **The kid's own picture per contact**: a long-press on the photo (card, request form) opens "Bilde av …"; chosen or
  taken, framed with a drag and a pinch, re-encoded on the phone. It wins on the kid's phone over the parent's picture
  and **stays on the phone**: no server, no PWA.
- The contact request's **"+" stays in the header**.

## 1. What exists today (verified against the code, `main` 23a92f3a, and the named branches)

- **`calls/PhoneBookActivity.kt`** draws the wallpaper (`showsSystemWallpaper`, `WallpaperGround.apply(.., ownGround =
  true)`), then `include_kid_header` ("Telefonbok", back), an info line (`calls_off`, `calls_phone_book_empty`) and a
  `RecyclerView` with `GridLayoutManager(this, 3)` over `PhoneBookAdapter` (`item_kid_contact.xml`: a 64 dp avatar, the
  `KidBadge` at its top end, the name in `WholeWordTextView`, labels in `KidInk`). Tiles: `PhoneBookTile.Contact(contact,
  emergency, missed, unread)` and `PhoneBookTile.Emergency` (the fail-closed 112). `submit` calls
  `notifyDataSetChanged`; the photo, wallpaper, Element-missed and messages listeners each call `render()`. A tile has a
  click and no long-press.
- **What is listed:** `phoneBookView(state)` - the managed rules' `phoneBook` (`outbound`, the PWA's "I telefonboken"),
  in the parent's order (`device_contacts.sort_order`); with calls off only the contacts whose number is an emergency
  number; with no readable rules the 112 tile. It is also the call-log viewer (`VIEW` of the call log, pinned) and the
  missed-call notification's target (`EXTRA_MISSED_CONTACT` opens that contact's card).
- **The contact card** (`ContactSheet.show`, `sheet_contact.xml`, `KidSheetDialog`, night, edge to edge): photo with a
  ring (red for an emergency number), badge, name, the missed and unread lines (`KidAvatars.sheetLines`), **Ring**
  (off while another call is live, `secondCallAllowed`), **Melding** (our own thread when `opensOwnThread`, else
  `resolveMessageButton`'s app with "Meldingen åpnes i <app>", else no button), Lukk. Opening it marks the contact's
  missed calls seen (`MissedCallsRepo.markSeen`) and dismisses the missed-call notification. It opens from Home's
  contact row too (a tap or a long-press on Home's avatar). Its photo (`sheet_photo`) has no listener.
- **Missed calls** (`MissedCallsRepo`): the call log of 14 days (`READ_CALL_LOG`, self-granted while calls are managed),
  rows typed `MISSED`, `OUTGOING`, `INCOMING_ANSWERED`, `OTHER` (rejected and blocked, never shown); a missed call
  counts after the contact's seen mark (CE prefs `missed_calls_seen`) and after the last outgoing or answered call with
  that number; plus missed Element X calls from their notifications (round L5, `ContactMissed(phone, element)`). The
  badge is missed calls plus unread messages (`ContactBadge`).
- **Photos** (`ContactPhotos`): the parent's picture per contact, `call_policy.contacts[].photo` (SHA-256), downloaded
  into `filesDir/contact_photos` (CE), decoded in the background with a 16 MB `LruCache` (full ARGB_8888 decodes; the
  server sends at most 512 px). The server keeps one picture per contact on the global `contacts` row, shared by every
  phone with that contact.
- **Avatars** (`KidAvatars.bindContact`) are bound on Home's row, the phone book, the card, the glance rows, the call
  screen and Home's call card, and Meldinger's rows. An emergency number never shows a photo: the red disc with its
  ring (design 34 P3).
- **Meldinger** (`ui/messages/MessagesActivity.kt`, `item_messages_row.xml`): rows on `bg_day_event` (`kid_music_tile`
  #1B1B28, 18 dp corners, a white 12 % ripple), newest first; "Ny melding" swaps the list for the `sms_out` contacts.
  Its own solid ground (design 78 puts it in the light look).
- **The music app's bottom** (`music/.../ui/LibraryScreen.kt`): the music app has **no tab bar**. Its bottom is the row
  of **category tiles** (`CategoryTiles`: "Alle", "Favoritter", the categories - each a 44 dp disc with a 20 dp glyph
  and an 11 sp 800 label under it, 4 dp apart, at least 64 dp tall; the chosen one filled - peach for "Alle", the
  category's colour otherwise - with a 3 dp `fg` ring and a white label, the others `card` #1B1B28 with a 1 dp ring at
  25 % and a dim label; `semantics { selected }`) and, under it, the now-playing bar. Layout A copies that row.
- **Picking a picture** (design 77 step 3b, branch `sms-mms`, in its code-QA fix round): `ui/messages/PicturePick.kt` -
  the system photo picker sent to the system's own picker package, the camera only as the parent-allowed camera app
  (`cameraPackage`), writing to a file of ours through the share provider; the picker's answer accepted only as another
  app's `content://` URI (`pickedUriAcceptable`); the bytes decoded in the isolated parse process
  (`MmsParser.decodeImage`, EXIF orientation applied there, at most `PICK_MAX_PX` 1280 px); `PictureEncoder` writes our
  own JPEG (no EXIF). `KidScreensEscapeTest` lets `PicturePick.kt` alone name `ACTION_PICK_IMAGES` and
  `ACTION_IMAGE_CAPTURE`.
- **The contact request** (design 77 §14): branch `sms-contact-request` 58b7f07c has the API, the contract, the server
  and the phone's model (`calls/ContactRequests.kt`, CE prefs `contact_requests`); the WIP branch
  `sms-contact-request-kid-form-wip` 71f84c1a has the "+" in the header, the form in the grid's place
  (`ContactRequestForm`) and the request rows above the grid.
- **No diacritic fold exists.** D17's fold (`SmsSenders.fold`: NFC, then `lowercase(Locale.ROOT)`) is exact on
  purpose - it decides which sender names get through. Dagen's title rules lowercase code point by code point. The
  music app's search is a plain `lowercase().contains`. None lets "o" find "ø".
- **Android backup is off** (`allowBackup="false"`).

## 2. Scope

**In:** the phone book screen in one of the two layouts, the contact card's star and long-press, search in the phone
book and Meldinger, the kid's picture per contact (on the phone), the contact request's picture, and where the
request's rows sit.

**Out:**
- Home: its contacts row stays the parent's "På Hjem-skjermen" list, in the parent's order, with its tap and long-press
  as today. It shows the kid's picture (it binds avatars the same way); nothing else changes.
- The lock glance, the call screen and notifications show the kid's picture through the same binding; nothing else.
- Design 78's light look: the phone book stays on the wallpaper and out of 78's scope. Meldinger's pill takes 78's page
  tokens when 78 lands (§5.4).
- Text-only contacts (`sms_out` without "I telefonboken"): not in the phone book, as today; Meldinger's "Ny melding"
  reaches them.
- The server, the PWA and every contract: **no change** in revision 2.
- The music app's own search keeps its plain lowercase; giving it the fold is a small separate item, not proposed.

## 3. The two layouts

Both use the same row (§4.2), the same search (§5), the same card and the same picture flow (§6). They differ in where
missed calls and favourites live.

### 3.1 Layout A - tabs at the bottom

```
[‹]  Telefonbok                      (+)
( 🔍  Søk                          )          only on Kontakter, from 8 contacts
  [Bestemor                     (☎)(✉)]
  [Mamma                        (☎)(✉)]
  [Ola                          (☎)(✉)]
  ...
  ( ◷ )        ( ★ )        (👤)              the music app's category tiles, at the bottom
  Siste      Favoritter   Kontakter
```

- **The tab row** is the music app's `CategoryTiles` in Views: three tiles evenly spread across the content width, each
  a 44 dp disc with a 20 dp glyph and an 11 sp Nunito 800 label (the clock, the star, the person glyphs - Material
  Symbols, Apache-2.0, through `scripts/material-symbols.sh`). The chosen tile: filled `kid_accent` with a `kid_ground`
  glyph and a 3 dp `kid_core` ring, the label `kid_ink`; the others `kid_music_tile` with a 1 dp ring at 25 % and a
  `kid_core_dim` glyph and label. It sits on its own solid strip (`kid_ground`, a 1 dp `kid_divider` hairline on top,
  8 dp top padding plus the navigation bar's inset), so the labels read on any wallpaper and no row shows through. TalkBack: a tab list (`CollectionInfo` 1 x 3, each
  tile `selected`), "Siste, fane, 1 av 3, 2 tapte anrop".
- **Siste: recent calls, recommended over missed calls only.** A missed-calls-only tab is empty almost every time it
  is opened, and an empty tab teaches the kid not to look. Recent calls are useful every day ("ring tilbake den jeg
  nettopp snakket med") and still show every missed call, in red.
  - **One row per person**, newest first, from the 14 days of call log already read and the missed Element X calls;
    only phone-book contacts (strangers, rejected and blocked calls never show, as today).
  - The row's second line says the latest call in §4.2's short form: "2 tapte anrop 14:02" (red, `kid_missed_text`),
    "Ringte deg i går" (answered), "Du ringte 13:10" (outgoing). The same Ring and Melding buttons.
  - **Seen:** opening Siste marks the shown missed calls seen (the phone's call-log convention), so the tile's badge,
    the avatars' badges and Home's badges clear; the rows keep their red line as history until a newer call replaces
    it. The card, Ring and Melding still mark seen as in §4.3.
  - The tile carries the unseen missed count as `KidBadge` on its disc.
- **Favoritter**: the starred contacts that are in the phone book now (filtered at render with `rules.phoneBook`; a star
  on a contact the parent took out of "I telefonboken" for a while stays stored and shows again when it returns, QA
  M1), A-Å; empty: "Trykk på stjernen på et kontaktkort for å legge noen her." in
  `KidInk` dim.
- **Kontakter**: everyone in the phone book, A-Å, the search pill on top (from 8 contacts), the contact request's
  answer once on top and open requests at the end (§8).
- **Opens on Kontakter**, every time - except the missed-call notification and the call-log intent, which open
  **Siste** (the notification's one-contact case still opens that contact's card over it, as today). No remembered
  tab: the screen starts the same way each time.
- **"+"** in the header on every tab while the kid may ask (`Managed && callsEnabled`, QA L2); **search** only on
  Kontakter (Siste and Favoritter are short).
- **The list follows the keyboard**: the page is edge to edge, so the list's bottom padding takes the IME inset
  (`WindowInsetsCompat.Type.ime()`; `adjustResize` alone does nothing on API 35+, QA L3), and the tab row hides while
  the keyboard is up.
- **Calls off / no readable rules:** no tab row. The emergency contacts, or the one 112 row, as in §4.4.
- **Swipes:** no swipe between tabs (the music app's tiles don't swipe either, and a sideways swipe on a list of
  call buttons invites mis-taps).

### 3.2 Layout B - one plain list

```
[‹]  Telefonbok                      (+)
( 🔍  Søk                    ) (☆)            the star filter beside the pill
  [Ola - Ikke lagt til                  ]     a request answer, once
  [Bestemor                     (☎)(✉)]
  [Mamma ²  2 uleste meldinger  (☎)(✉)]
  [Pappa ¹  Tapt anrop 14:02    (☎)(✉)]       red line + badge, in its A-Å place
  [Politi   Nødnummer           (☎)   ]
  ...
  Venter på svar
  [Lina     Venter på svar              ]
```

- Everyone in one A-Å list. A missed call is the badge plus the row's red line, in the contact's own place; no group,
  nothing moves. Seen as today plus the row's buttons (§4.3).
- **Favourites are a star filter**, not a group: a 44 dp round button beside the search pill (outline star; filled
  peach while on). On, the list shows only the starred contacts, with one quiet line on top - "Viser favoritter · Vis
  alle" - so nobody goes missing by surprise. Not remembered: each visit starts with everyone. With no stars yet the
  button is not shown (the star on the card is how they start). Leaving favourites out of B altogether is the other
  choice (§12).
- The search pill from 8 contacts, as in A. With fewer than 8 contacts and a star, the star button stands alone at the
  right end of the pill's line.
- The request's answer shows once as the first row (it is news); open requests at the very end under one small
  "Venter på svar" label - the one label in B, because those rows can't be called.

### 3.3 Recommendation, and the doubt

**B.** It is what the user liked first, it adds no layer, it is the smaller build (one screen, no tab state, no new
log view), and with up to about 50 contacts plus search everything is one scroll or a few letters away. The missed call
stays visible where the kid looks for the person. The star filter gives favourites without a second place to look.

**A is worth it only if the kid wants a call history.** Its real gain is Siste - "who called, who did I call" - which
no screen has today; the tabs themselves add a tap and a place to look. If the user wants Siste, A is a clean way to
add it, and B's list becomes A's Kontakter tab unchanged (the steps keep that door open, §9).

The user's doubt is fair: B is close to "keep A-Å on the whole list", which is also what B is. §12 asks.

## 4. The list's parts (both layouts)

### 4.1 Ground

The phone book keeps the wallpaper (ui-polish.md "Drawing", design 78's table). Every row, the search pill and A's tab
strip are **solid** (`kid_music_tile` #1B1B28 rows and pill, 18 dp corners), so they read the same on navy, a light
photo and a busy one. Only the info line, B's favourites line and labels are text on the wallpaper, drawn with
`KidInk.label(.., dim = true)` as `phone_book_info` is today.

### 4.2 Row anatomy (`item_phone_book_row.xml`)

Designed against the **Jelly Star** (320 x 569 dp): 288 dp of content inside the page's 16 dp sides (QA H1). The
mockups are drawn at these sizes, 1 px = 1 dp at font scale 1.0.

- **Tile:** `bg_day_event` (#1B1B28, 18 dp, white 12 % ripple), min 64 dp, padding: start 10 dp, top/bottom 8 dp,
  end 0 (the buttons' targets reach the tile's edge); rows 8 dp apart.
- **Avatar:** 44 dp, `KidAvatars.bindContact` at 18 sp initials, the `KidBadge` at its top end lifted 2 dp, no white
  ring (Home's ring is for busy wallpapers; the row is a solid tile). No press scale on the avatar and **no long-press
  on the row**: the row's press is its ripple, and a row only opens the card.
- **Buttons:** two 38 dp discs, each centred in a 48 dp target, the targets touching (no gap): 96 dp. **Ring**:
  `kid_sheet_button` (white 8 %) disc, the phone glyph in the new `kid_call_glyph` #7FC28F (6.5:1 on the disc over the
  tile). **Melding**: the same disc, the message glyph in `kid_accent` (6.9:1). Tonal, not filled: 50 green discs down
  a list would shout. Press: design 34's `PressMotion` (visual only) and an oval ripple; a 2 dp peach focus ring for a
  keyboard. Dimmed to 40 % and inert while another call is live (emergency excepted).
- **The text column** is then 288 - 10 - 44 - 10 (gap) - 96 = **128 dp** at font scale 1.0: about 14 characters of
  the name at Nunito 800 16 sp, about 18 of the second line at 13 sp 700.
- **Text:** the name, Nunito 800 16 sp `kid_ink`, `WholeWordTextView` (never cut between letters), at most 2 lines;
  the second line 13 sp 700, **at most 2 lines** (it wraps; it is never cut to one), absent when there is nothing to
  say (the name centres vertically).
- **The second line is short** (the full sentence stays on the card and in TalkBack), in order:
  - "Nødnummer" (emergency);
  - else the missed calls in `kid_missed_text` #FF8A8A (7.5:1 on the tile): "Tapt anrop 14:02" today, "Tapt anrop i
    går", "Tapt anrop 3. nov." earlier; "2 tapte anrop 14:02" for more (the badge has the count too) - with Element X
    calls the count is both and the time the newest. The longest, "12 tapte anrop i går", is 20 characters: one line at
    1.0 on most names' rows, two at worst;
  - then " · 1 ulest" (`contact_unread_short`, `kid_core_dim`) when there are unread messages, or alone "1 ulest
    melding";
  - else nothing.
  - In A's Siste the line is the latest call instead (§3.1), in the same short form: "Ringte deg i går", "Du ringte
    13:10".
- **Large text: the buttons get their own line.** Pure `rowLayout(contentDp, fontScale)`: **inline** at font scale
  below 1.3 with a text column of at least 120 dp; else **stacked** - the avatar and the text use the whole width
  (288 - 10 - 44 - 10 - 10 = 214 dp), and Ring and Melding sit on a line under the text, from the text's start. At 1.3
  "Bestemor" and "2 tapte anrop i går" fit one line each; at 2.0 the name may take two lines and shrink (WholeWords'
  75 % floor) but is never cut between letters. `WholeWordsTest` gains the phone-book row at 288 dp and 1.0 / 1.3 /
  2.0 in nb and en: no missed-call line cut, the names of the test set whole.
- **Waiting rows** (open requests): the tile with a 1.5 dp peach-45 % outline, a `kid_music_control` disc with the
  initial in peach (or the request's picture), "Venter på svar"; not clickable.

### 4.3 The buttons follow the card's rules, and what marks a missed call seen

- One pure function decides the card's buttons and the row's (`contactActions`, §5.2), so a button shows on the row
  exactly when the card would show it:
  - **Ring**: every phone-book contact while calls are on; while calls are off only emergency contacts are listed, and
    theirs shows. Off while another call is live, emergency numbers excepted (`secondCallAllowed`).
  - **Melding**: our own thread (`opensOwnThread`), else `resolveMessageButton`'s app (SMS app, Element X,
    Signal/Molly; installed, unsuspended, allowed); else no button.
- **Seen:** today's rule (the card opened, a later call with the number, 14 days) plus **the row's Ring or Melding and
  the card's Melding** (each means the kid dealt with that person). In A, opening Siste too (§3.1). Opening the phone
  book alone doesn't (it still clears the notification, as today).

### 4.4 Emergency contacts

By name, with today's red disc and ring (2 dp at 48 dp), never a photo, never the kid's picture; the line says
"Nødnummer". Their row **Ring** is the tonal red pair (`kid_emergency_tonal` disc, `kid_emergency_text` glyph) and
**asks first** ("Ringe Politi?", the existing `calls_confirm_title` dialog): a one-tap call from a scrolling list is a
pocket call waiting to happen, and an accidental 112 costs more than an accidental call to Mamma. The card's Ring stays
as today (the card is already the second step). They can be starred. The fail-closed 112 row (no readable rules) is
one row with the red disc and "Nødsamtale", no buttons; a tap opens `EmergencyCall.confirm` as today's tile.

### 4.5 Under each state

- **Calls managed and on:** the list (A: the tabs).
- **Calls off (parent, or a time rule's copy):** today's info line, the emergency contacts only; no tabs, no search, no
  star filter, no "+" and no "Be om å legge til" (one rule for both: `canAsk = Managed && callsEnabled`, QA L2 - the
  WIP's `canAsk` is only `Managed`).
- **No readable rules:** the one 112 row.
- **Calls unmanaged:** no phone book (the tile is hidden on Home), as today.
- **A time rule:** reachable only where the rule allows calls, as today; a rule beginning while it is in front brings
  the time-rule screen.

### 4.6 TalkBack

- A row is **one node**, role button: the name, then the second line ("Pappa, 1 tapt anrop i dag 14:02"). Activating
  it opens the card. The two buttons are focusable for a keyboard but `importantForAccessibility="no"`; the row carries
  **custom actions** "Ring Pappa", "Send melding til Pappa" (or "… i Element X"), "Legg til i favoritter" / "Fjern fra
  favoritter". Fifty contacts are fifty swipes, not a hundred and fifty.
- A's tab row is a tab list (§3.1). B's star filter is a toggle ("Vis bare favoritter", "på"/"av").
- A search announces "3 treff" / "Fant ingenting" through a polite live region on a **visible** view (TalkBack ignores
  INVISIBLE and GONE, QA N7): the "Fant ingenting" text itself, and while there are hits a small count line under the
  pill ("3 treff", 12 sp `KidInk` dim), updated at most once per 500 ms of typing (`announceForAccessibility` is
  deprecated on API 36).
- The row's description is the full sentence, not the short line: "Pappa, 1 tapt anrop i dag 14:02, 1 ulest melding".
- A Melding to Element X or Signal leaves Vibb; the row's action says so ("Send melding til Pappa i Element X"). A
  sighted kid gets no cue on the disc (QA N6): accepted - the card says "Meldingen åpnes i …", and a glyph per app on
  50 discs would be noise.

### 4.7 Performance with about 50 contacts

- `LinearLayoutManager`, a diff over stable ids (`contact:<id>`, `request:<id>`, `call:<number>` in Siste), no item
  animator (as Home's grid). A's tabs swap the list's content in one adapter; no `ViewPager`.
- **Photos rebind in place:** a decode finishing calls `notifyItemRangeChanged(.., PHOTO)`, and only avatars rebind
  (today every listener re-renders the screen and every decode starts another full bind).
- **List-sized thumbnails** (QA N8, instead of revision 1's RGB_565, which bands skin tones on the card's 132 dp
  circle): `ContactPhotos` decodes a row-sized copy (at most 160 px, ARGB_8888, under 0.1 MB, `inSampleSize` then a
  scale) for the rows, Home's row and the glance rows, and the full square only for the card and the call screen. Each
  size has its own cache key (`<hash>@160`), so 50 thumbnails take about 5 MB of the 16 MB cache.
- Computed once per render, not per row (QA N7): `CallSystem.isEmergencyOutgoing` per contact (a binder call, twice
  per contact per render today), `defaultSmsPackage`, `ElementRoomStore.lookup`, the message apps' usability (at most
  four packages) and the folded names. The `Collator` is kept per locale, not made per render. A keystroke only
  filters. The call log is read on IO, as today (A's Siste uses the same read).

### 4.8 Favourites (both layouts)

- **Set** with the star on the contact card (a 48 dp toggle at the sheet's top right, outline `kid_core_dim`, filled
  `kid_accent` when on; TalkBack "Favoritt", "på"/"av") and with the row's TalkBack action. The card shows the star
  only for a contact in `rules.phoneBook` - Home's card can open a contact the phone book never lists (QA M1) - and for
  a contact with an id (id 0, a server too old to send ids, gets none).
- **Stored** in CE prefs `phone_book_favourites` (`ids_v1`, contact ids), read into memory on IO after the unlock
  (the list renders without stars until then - it can't open before the unlock anyway).
- **Pruned** only on a CE refresh of a `Managed` state (never `UnknownFailClosed`, never before the unlock - the
  pattern of `ElementRoomStore.prune`, after `plan.source != CE` returns), against **every contact on the phone**
  (`rules.contacts` ids), not the phone book: calls off or a contact taken out of "I telefonboken" for a while keeps
  its star; a contact removed from the phone drops it. Shown filtered by `rules.phoneBook` at render.
- **Independent of Home** in both directions: starring doesn't put a contact on Home, "På Hjem-skjermen" doesn't star.
  Nothing is sent to the server. Lost with a factory reset. They start empty.

## 5. Search

### 5.1 The rules (the music app's, in Views)

- **The pill** sits under the header and doesn't scroll: 44 dp, 22 dp corners, `kid_music_tile`, an 18 dp magnifier in
  `kid_core_dim`, the hint "Søk" (phone book) / "Søk etter navn" (Meldinger), the text Nunito 700 16 sp `kid_ink`, a
  peach cursor, a 1.5 dp peach-50 % outline while focused, an "×" (48 dp target, "Tøm søket") while it holds text.
  `NoTextActions` on the field and `importantForAutofill="no"` (an autofill chip opens the autofill service's own UI,
  QA L3; every other kid text field already sets it); input `text|textNoSuggestions` (not `textPersonName`, an
  autofill hint); IME action Search closes the keyboard. **It never takes focus by itself**: the screen opens with the
  keyboard down; a tap on the pill brings it.
- **Live:** every keystroke filters. Hits are ranked - the name starts with the query, then a word in it does, then it
  contains it - each rank **A-Å** (QA N2); the matched part of the name in peach (8.6:1 on the tile).
- **Open requests are searched too** (QA L1): a waiting request whose name matches shows as its waiting row (no
  actions) among the hits, so searching "Lina" while Lina is waiting finds her instead of offering to ask again. In B
  with the star filter on, search looks in the favourites only (the line on top says so).
- **Empty:** "Fant ingenting" under a 72 dp tile-coloured disc with the magnifier in peach (the music app's
  `EmptyState`, smaller). In the phone book, while the kid may ask (`canAsk`, §4.5) and only when nothing matches -
  requests included - and the query has at least two letters and isn't all digits: a quiet button "Be om å legge til
  «Lina»" (en "Ask to add “Lina”"), the request form with the query as the name.
- **The form over a search** (QA N4): the pill hides while the form is up; Back closes the form first, then clears the
  query, then leaves; after "Send" the query is cleared.
- **Back** with text in the pill clears it and closes the keyboard; the next Back leaves. The query is kept across a
  rotation only.
- **When it shows:** from 8 rows (`SEARCH_MIN_ROWS`). Below that the list fits one screen (§12). Once shown, the pill
  stays while it holds text, even if a sync takes the list under 8 (QA N3).
- **Names only, in both places.** Meldinger matches the row's shown title (the contact's name now, else the name the
  conversation last had, else what the row shows), never message text (the user: "bare kontakt navn"). The phone book
  doesn't match the number either: the kid sees no number anywhere in the phone book or on the card, so a hit on one
  would look like a mistake. In Meldinger a conversation with a bare number (`Who.Number`) shows digits as its title,
  so digits match there - harmless, and still "what the row shows" (QA N5).

### 5.2 The fold, the match and the model (pure)

- `ui/search/SearchFold.kt` - built **from the shown string itself, code point by code point** (QA L11: a
  whole-string NFC or lowercase first can change the length - a decomposed "Björn" pasted from macOS, "İ" -> "i̇" -
  and the span would index another string): for each code point, `lowercase(Locale.ROOT)`, NFD, the combining marks
  dropped, then the letters NFD can't split: ø -> o, æ -> ae, œ -> oe, ß -> ss, đ -> d, ł -> l, þ -> th, ı -> i. Each
  folded char records the index of the code point it came from; a combining mark in the shown text folds to nothing and
  belongs to the letter before it. Both sides are folded ("o" finds "Ørjan", "a" finds "Åse", "e" finds "Hélène"). The
  span is clamped to the text's length before `setSpan`. **It never decides anything**: D17's sender names stay exact
  (a looser fold would let a "Vípps" through on the parent's "Vipps"), and the call rules match numbers.
  `SearchFoldTest` pins the table and that nothing under `sms/` or in `CallRules.kt` calls it.
- `searchMatch(name, query): Match?` - `Match(rank: PREFIX | WORD | INSIDE, span)`; a word starts after a space, "-"
  or "'"; inner spaces kept ("ole m" finds "Ole Martin"); at most 40 characters of query.
- `calls/PhoneBookList.kt` - `contactActions(..)` (the card's inline decisions moved out of `ContactSheet.show`
  unchanged), and `phoneBookRows(view, missed, unread, favourites, requests, query, filter, collator, canAsk)` for B and
  A's Kontakter; for A also `favouriteRows` and `recentRows(log, elementMissed, contacts, seen)`.
- **Order: A-Å**, `java.text.Collator` in Vibb's language (nb puts Æ, Ø and Å last), strength SECONDARY, ties by id.
  The parent's order stays on Home (§12).

### 5.3 The widget (`ui/search/KidSearchField.kt`, `view_kid_search.xml`)

One small View helper both screens include: binds the pill, calls back with the text, owns Back's "clear first", saves
the text for a rotation only.

### 5.4 Meldinger

The pill under the header in the list and in "Ny melding" (from 8 rows each), hint "Søk etter navn", the same rank and
highlight, "Fant ingenting" without a request button. It takes `kid_music_tile` now, as the rows do; when design 78
lands it takes 78's row tile token like the rows.

## 6. The kid's picture

### 6.1 The long-press (no camera chip)

- **On the contact card:** a long-press on the photo (`sheet_photo`, the 132 dp circle) opens the **"Bilde av Mamma"**
  sheet. Haptic `LONG_PRESS` feedback; the photo dims 10 % while held (design 34's press shade, no scale). A plain tap
  on the photo does nothing, as today.
- **In the request form:** a long-press on the picture circle opens the same sheet ("Bilde av Lina", or "Bilde" before
  a name is typed). There a **tap** opens it too: the empty circle is a field ("Legg til bilde (valgfritt)" under it),
  and a field is tapped.
- **TalkBack:** the card's photo gets a custom action "Endre bilde av Mamma" (it is `importantForAccessibility="no"`
  today; it becomes a node with that action and the description "Bilde av Mamma"); the form's circle is a button
  "Legg til bilde" / "Endre bilde".
- **No clash with what exists:**
  - the card's photo has no listener today, and the card has no gesture of its own but the outside tap that closes it;
  - Home's long-press on a contact avatar opens the card, and the finger that did it is still down when the card
    appears: a new window gets no part of a gesture that began in another, so that long-press can't run on into the
    card's photo (the photo needs its own DOWN);
  - the phone book's rows get no long-press at all (§4.2), so nothing in the list competes;
  - no drag starts from the card or the form;
  - offered only for a contact in `rules.phoneBook` (as the star, §4.8); not for an emergency contact, nor before the
    first unlock (CE storage), nor while the camera and the picker are both unusable - then the long-press does
    nothing and the TalkBack action isn't there.
- **The form's circle and the escape test** (QA M3): the WIP's `the contact request only asks` forbids `startActivity`
  and `Intent(` in `ContactRequestForm.kt`. The form keeps that: its circle calls back into `PhoneBookActivity`
  (`onPicture`), which shows the sheet and starts `KidPhotoActivity`; the form file stays free of intents.
- **Discoverability:** long-press is hidden by nature. The card's hint line under the buttons gains nothing; instead
  the "Bilde av …" sheet is also the first thing the kid meets in the request form, where the circle is tapped, so the
  kid learns that pictures are changed by holding them. Whether that is enough is a device check with the kids.

### 6.2 The "Bilde av …" sheet (`KidSheetDialog`, over the card)

- The title "Bilde av Mamma", then rows in the time-rule card's look: **Velg bilde** (the photo picker), **Ta bilde**
  (only with an allowed camera), **Fjern bildet ditt** (only while the kid's picture is set; then the parent's picture
  or the initial shows again), and Avbryt.
- Under the rows, in `kid_core_dim`: "Bildet blir bare på denne telefonen." - honest, short.
- Velg bilde and Ta bilde start `calls/KidPhotoActivity` (night, not on the wallpaper), which launches the picker or
  the camera at once through `PicturePick` and, with a picture back, shows the framing. Its own activity, so the
  picker's result lands in one place whether the card came from the phone book or from Home. It checks the time rule
  at resume and watches it (`LockWatch`), and a failed pick says "Kunne ikke bruke bildet" and changes nothing.
- **Its guards** (QA L12): it finishes when, at a sync, the contact leaves the phone book or its number becomes an
  emergency number, as well as under a time rule without calls; it stays out of `FREE_SCREENS` (picking and the camera
  are screen time); it reads the picker's answer only as `PicturePick.picked(this, result.data?.data)` and never
  `Uri.fromFile` (the pin `ThreadActivity` has, QA L4). It and the "Bilde av …" sheet are night, like `PictureActivity`
  - design 78's scope table gets a row "out (night)" for them when either lands.
- **`PicturePick` is split** (QA L5): its `prepare` is SMS-bound (the carrier's limit, the engine's `putPicture`, the
  pixels recycled). The shared part becomes `PicturePick.decode(context, source): Pair<Bitmap?, Problem?>` (read,
  the 30 MB cap, the isolated decode); the SMS `prepare` sits on top of it unchanged; the contact path is `decode`,
  then the framing, then `PictureEncoder.forContact`. The escape test's pins move with the file to
  `ui/pictures/PicturePick.kt`.

### 6.3 Framing (`calls/PhotoCropView.kt`, pure `PhotoCrop.kt`)

- The decoded picture (at most 1280 px, from the isolated process) fills a square frame the page's width less 32 dp,
  covered; a black 55 % veil with the circle cut out shows what the avatar will show.
- One finger drags, two pinch: from 1x (cover) to `max(1, min(3, shortSide / 384))`, so the square keeps at least 384
  px of the decoded picture - what the card's 132 dp circle needs at 3x - while it can. A picture whose short side is
  under 384 px (a small screenshot) is used at 1x, not refused, and is never enlarged: the stored square is its short
  side (QA L6). Clamped after every move. No slider, no zoom buttons, no rotation (EXIF is already applied).
- "Bruk bildet" (peach pill) and "Avbryt"; the hint "Dra og knip for å velge utsnittet". With TalkBack "Bruk bildet"
  takes the centred square.
- The output: the square cut from the decoded pixels, scaled to at most 512 px, encoded by `PictureEncoder` (a new
  `forContact`: JPEG, quality stepped down from 85 until it is at most 150 KB, no EXIF). The picked file is never
  decoded in the main process (design 77 D8).

### 6.4 Storage: the phone only

- **Where:** `filesDir/kid_contact_photos/<random>.jpg` (CE, app-private), and CE prefs `kid_contact_photos`: contact
  id -> {file, the contact's number when it was set, set at}. Never in DE storage, never in the status report, never
  uploaded, never in Android's backup (off).
- **Caps:** one picture per contact; at most 150 KB a file (the encoder's steps); at most as many files as the phone
  book has contacts, and never more than 100 (`KID_PHOTOS_MAX`) - a set beyond it refuses with "Kunne ikke bruke
  bildet" (the phone book's own size makes this unreachable in practice; it bounds a bug). About 15 MB at the worst.
- **Deleted with the contact** (QA M1's rule, as favourites): only on a CE refresh of a `Managed` state, never on
  `UnknownFailClosed` and never before the unlock, a record whose contact is no longer **on the phone**
  (`rules.contacts`) - or whose **number changed** (the parent re-pointed the row to someone else, QA L9) - goes with
  its file. A contact taken out of "I telefonboken" but still on the phone keeps it (it shows in Meldinger; the kid
  gets the long-press back when the contact returns). "Fjern bildet ditt" deletes at once. A file without a record (a
  crash between the two writes) is swept at the same pass. All on the store's own IO thread.
- **An in-memory snapshot, never a disk read in a binding** (QA H2): `KidContactPhotos` holds the records in memory,
  loaded on IO after the unlock (`Application.initUnlocked`'s tail) and **empty before it**; a change (set, remove,
  prune, the load itself) fires `ContactPhotos`' listeners, which rebind in place. `KidAvatars.bindContact` never reads
  prefs or files: it asks the snapshot and `ContactPhotos.cached`, which decodes on its background thread - the kid's
  JPEG under the key `kid:<file name>` (the file name is random, so it can't collide with a SHA-256 hash, and a new
  picture is a new key, never a stale bitmap). The in-call screen (`directBootAware`) before the first unlock sees an
  empty snapshot and an empty cache: the initial, exactly as today. A source check next to `ContactPhotos`' "no disk in
  `cached`" pins that `KidAvatars`, `KidContactPhotos.shown` and `bindContact` open no prefs and no file.
- **What shows** (pure `shownPhoto(contact, kidPhoto, emergency)`): emergency - none; the kid's picture if there is
  one; else the parent's (`photo`); else the initial. Every avatar binding goes through it, so the kid's picture shows
  on Home, the glance rows, the call screen and Meldinger too.
- **Wipe and reprovisioning:** the pictures are lost, as everything in CE storage. That is fine: they are the kid's
  decoration, the parent's pictures come back with the policy, and nothing depends on them.

### 6.5 In the contact request

- The form's circle (§6.1) opens the same sheet (with "Fjern bildet" only for the form's own draft), the framing as
  above.
- **Before "Send" the request has no id** (QA M3): the framed picture is a draft, `cacheDir/request_photo_draft/
  <random>.jpg`, its name kept in the form's saved state (the camera can cost the launcher its activity). "Send" renames
  it to `filesDir/contact_request_photos/<request id>.jpg` and names it in the request's record in the same write;
  Avbryt, Back out of the form and a refused form delete it; drafts older than a day are swept when the phone book
  opens.
- The picture shows on the "Venter på svar" row.
- **The hand-over is one step with the reconcile** (QA M3): `reconcileContactRequests` (pure) returns the new list
  **and** the hand-overs (request id -> contact id). For each, the file moves into the kid's pictures (§6.4) first; only
  then is the list written without the record. A crash between leaves the file named by a record that is still there,
  so the next reconcile does it again.
- **Who gets it:** only a contact that arrives **in the phone book** (`outbound`), and only if the kid hasn't set a
  picture for it already. A contact the parent added text-only, a request answered `added` under **another** number
  (no contact id to match), `declined`, `known`, `capped`, "not sent" and the 7-day expiry: the picture is deleted.
  Every kid picture stays one the kid can see and take away on a card.
- **No leftovers:** at each reconcile (CE, after the unlock) the `contact_request_photos/` directory is swept against
  the records - a file no record names goes. `dropShownContactRequests` and the expiry delete their files in the same
  pass. These are pictures of people; they don't outlive the request.
- **No change to the request's API or contract.** The parent doesn't see the picture; the request card in the PWA
  stays name and number.

### 6.6 Not a restriction, and not a safety issue

- **Nothing here is a restriction**, so nothing needs lifting: no switch, no limit the parent sets. The only fixed rules
  are display rules - an emergency contact never shows a picture (so 112 looks the same everywhere, design 34 P3) and
  the caps above (they bound storage, not the kid). The time rules gate the page as they gate the phone book.
- **The parent can't see or remove the kid's pictures.** Accepted by the user ("Server trenger ikke se bilde barnet
  selv legger til?"): they come from the kid's own gallery or the parent-allowed camera, and they show only on the
  kid's own phone.
- **A picture can't change who a contact is.** Calls and messages are decided by the number; the name stays the
  parent's (the kid can't rename a contact); unknown and withheld callers get the silhouette (`callAvatar`), never a
  kid's picture; an emergency number never shows one. A stranger can't set a picture - only someone holding the
  unlocked phone, inside the phone book. The worst case is a prank: a friend with the kid's phone puts Mamma's face on
  "Ola". The name under it still says Ola, and the kid can take it away in two taps. Noted, not designed against.
- **The lock glance and the call screen over the lock** show the kid's picture where they show the parent's today -
  the same audience (anyone holding the phone) for a picture the kid chose to put there.

## 7. API and server

**None.** Revision 1's routes, table, store, policy keys, capability, contract and PWA part are dropped. The contact
request's API is unchanged.

## 8. For the contact request now (design 77 §14, being built on `sms-contact-request`)

- **The "+"**: in the header, a 40 dp filled peach disc with a dark "+" glyph (`kid_accent` / `kid_ground`, 9.9:1),
  described "Be om ny kontakt", while the kid may ask - **`canAsk = Managed && callsEnabled`** (QA L2; the WIP has
  `Managed` only, which is true with calls off). The same rule gates "Be om å legge til". In A on every tab.
- **The form**: in the list's place, as the WIP has it; give `ContactRequestForm.open(name: String? = null)` a
  prefilled name for "Fant ingenting"'s button. The picture circle comes with step 6 here, as a callback into
  `PhoneBookActivity` (§6.1) - the form file stays free of intents.
- **After "Send"**: a calm confirmation in the form's place (the request mockup's screen 3: a peach check disc, "Sendt",
  "Mamma og pappa har fått beskjed. Du ser det i telefonboka når Lina er lagt til.", "Ferdig"), then the list. In a long
  list the new waiting row is off screen, so the kid needs to see that it went.
- **The rows**: keep the WIP's rows above today's grid until step 2 here; step 2 puts the answer (once) at the top of
  the list (B) or of Kontakter (A), and the open requests at the end under "Venter på svar".
- **The reconcile** should return the hand-overs as well as the list (§6.5); worth shaping that way now, so step 6
  doesn't change its signature later.

## 9. Steps (small, each with its tests)

1. **The fold and the model** (pure, launcher; S). `SearchFold`, `searchMatch`, `rowLayout`, `PhoneBookList`
   (`contactActions` moved out of `ContactSheet` with no behaviour change, `phoneBookRows`; for A also
   `favouriteRows`, `recentRows`). Tests: `SearchFoldTest` (the table, spans on the shown string, a decomposed "Björn",
   "İ", the clamp, no caller under `sms/` or in `CallRules.kt`), `PhoneBookListTest` (A-Å with nb's Æ Ø Å, the
   emergency and fail-closed rows, calls off, the threshold and its stickiness, rank, waiting requests among the hits,
   the button only from two letters with nothing matching, the star filter; A's recent rows: one per person, the latest
   call, strangers and blocked out), `ContactActionsTest` (the card's cases, unchanged answers).
2. **The phone book as a list** (launcher; **M+**, QA N10: the bulk of the work; B, or A's Kontakter tab without the
   tabs). The row at 288 dp with `rowLayout`'s stacked form, the short missed line, the adapter with the photo payload,
   the list-sized thumbnails, the new seen marks (the row's Ring and Melding, the card's Melding), the asking emergency
   Ring, the request rows (after `sms-contact-request` is merged), TalkBack's node, actions and full sentences, the "+"
   with `canAsk`. Tests: `WholeWordsTest` (the row at 288 dp, 1.0 / 1.3 / 2.0, nb and en), `KidPaletteTest` (the row,
   `kid_call_glyph`), `KidScreensEscapeTest` (below), `MissedCallsTest` (seen by Melding), `TranslationsTest`.
   Emulator: 3 and 50 contacts, font 1.0 / 1.3 / 2.0 at the Jelly Star's 320 dp width, a light and a busy photo
   wallpaper, TalkBack, a call live.
3. **Search** (launcher; S-M). `KidSearchField` in the phone book and in Meldinger's list and "Ny melding"; the
   highlight, the live count, "Fant ingenting", "Be om å legge til «…»", the Back order with the form, the IME inset.
   Tests: `KidSearchTest` (Back order, threshold, prefill rules), `KidScreensEscapeTest` (`NoTextActions` and
   `importantForAutofill="no"` on the field, no autofocus), `MessagesModelTest` (title match only), `TranslationsTest`
   (the "treff" plural).
4. **Favourites** (launcher; S). §4.8: the card's star (also from Home's card, phone-book contacts only), the in-memory
   set, the prune; B's star filter and its line, or A's Favoritter tab. Tests: `FavouritesTest` (calls off keeps the
   stars, Unknown keeps them, a removed contact drops, nothing before the unlock, id 0 none), `PhoneBookListTest`'s
   filter, `TranslationsTest`.
5. **Only for A: the tabs and Siste** (launcher; M). The tile row, Siste's rows and seen-on-open, the notification and
   the call-log intent opening Siste, the tile's badge, the row hidden under the keyboard. Tests:
   `PhoneBookListTest`'s recent rows, `MissedCallsTest` (seen on opening Siste), `KidPaletteTest` (the tiles),
   TalkBack's tab list on the emulator.
6. **The kid's pictures** (launcher; M). Needs design 77 step 3b merged: `PicturePick` moved to `ui/pictures/` and
   split (`decode`, §6.2), `PictureEncoder.forContact`; the card's long-press and TalkBack action, the "Bilde av …"
   sheet, `KidPhotoActivity`, `PhotoCropView` and pure `PhotoCrop`, `KidContactPhotos` (the CE store, its in-memory
   snapshot, caps and prune), `shownPhoto` in `bindContact`, the request form's circle, the draft and the hand-over
   (after the request is merged). Tests: `PhotoCropTest` (cover, clamp, zoom cap, a picture under 384 px at 1x, the
   cut), `KidPhotosTest` (the snapshot empty before the unlock, prune on removal and on a number change but not on calls
   off or Unknown, the caps, the orphan sweeps, the draft's rename, every hand-over and every delete of §6.5, emergency
   never), the source check (no prefs or file in `bindContact`), `KidScreensEscapeTest`, `TranslationsTest`. Emulator:
   pick, the emulator camera, a rotated JPEG, a 30 MB file, a contact removed at a sync with its picture, an incoming
   call after a reboot before the unlock (the in-call screen, no crash), Home's long-press then the card's photo.

**`KidScreensEscapeTest`, named** (QA L4; today `calls/` has only `PhoneBookActivity.kt` and `PhoneBookAdapter.kt`):
add `ContactSheet.kt` (it starts other apps - its targets pinned to `CallSystem.placeCall`, the thread and
`openMessage`), `PhoneBookList.kt`, the new adapter, `KidPhotoActivity.kt`, `PhotoCropView.kt`, everything under
`ui/search/` and `ui/pictures/`; move the picker/camera allowance to `ui/pictures/PicturePick.kt`; pin
`KidPhotoActivity`'s `PicturePick.picked(this, result.data?.data)` and no `Uri.fromFile`; keep the WIP's "the contact
request only asks" for `ContactRequestForm.kt` unchanged.

1-4 are launcher-only and follow each other right away; 5 only if the user picks A; 6 waits for step 3b and the contact
request. Notes to update as each lands: `docs/notes/launcher/calls.md` (the phone book), `home-and-photos.md` (photos),
`ui-polish.md` (the row). No server, PWA or contract change, so no root `CLAUDE.md` or workflow change.

## 10. Risks and checks

- **Pocket calls from a row:** one tap. What stops a click while scrolling is the `RecyclerView` intercepting the touch
  during a fling; `PressMotion` is visual only (its listener always returns false), so it is no guard (QA L7). The
  confirm covers emergency numbers. Device check: fling through 50 rows on the Jelly Star and count accidental presses;
  if any, the row's buttons ignore a click within 300 ms of the list leaving `SCROLL_STATE_SETTLING`.
- **The long-press is undiscoverable** on the card (§6.1). Device check with the kids; if it isn't found, a one-time
  hint line on the card ("Hold inne bildet for å bytte det") is the fallback, not the chip.
- **The highlight's indices** with folds that grow (æ -> ae) or shrink (a combining mark): the per-code-point map and
  the clamp, tested.
- **The isolated decode is design 77's**: when it is unavailable, the picture flow says it couldn't use the picture;
  calls, the list and the card are untouched (design 77 §6.10).
- **Cache pressure** with 50 photos: thumbnails and the payload rebind; a `dumpsys gfxinfo` check at 50 photos on the
  Jelly Star.

## 11. Words (nb / en)

Every new string in `values-nb/strings.xml` and `values/strings.xml` (QA L10); English uses “…”, Norwegian «…».

| Key (sketch) | nb | en |
|---|---|---|
| `phone_book_search` | Søk | Search |
| `messages_search` | Søk etter navn | Search names |
| `search_clear` | Tøm søket | Clear search |
| `search_nothing_found` | Fant ingenting | Nothing found |
| `search_hits` (plural) | %d treff | %d result / %d results |
| `phone_book_ask_named` | Be om å legge til «%1$s» | Ask to add “%1$s” |
| `phone_book_tab_recent` / `_favourites` / `_contacts` (A) | Siste / Favoritter / Kontakter | Recent / Favourites / Contacts |
| `phone_book_favourites_filter` (B) | Vis bare favoritter | Show favourites only |
| `phone_book_favourites_line` (B) | Viser favoritter · Vis alle | Showing favourites · Show all |
| `phone_book_favourites_empty` (A) | Trykk på stjernen på et kontaktkort for å legge noen her. | Tap the star on a contact card to add someone here. |
| `phone_book_waiting` | Venter på svar | Waiting for an answer |
| `phone_book_emergency_line` | Nødnummer | Emergency number |
| `missed_short` (plural) | Tapt anrop %1$s / %2$d tapte anrop %1$s | Missed call %1$s / %2$d missed calls %1$s |
| `recent_in_short` / `recent_out_short` (A) | Ringte deg %1$s / Du ringte %1$s | Called you %1$s / You called %1$s |
| `contact_unread_short` (plural) | %d ulest / %d uleste | %d unread |
| `phone_book_action_call` | Ring %1$s | Call %1$s |
| `phone_book_action_message` / `_in` | Send melding til %1$s / … i %2$s | Message %1$s / … in %2$s |
| `favourite_add` / `favourite_remove` / `favourite_state` | Legg til i favoritter / Fjern fra favoritter / Favoritt | Add to favourites / Remove from favourites / Favourite |
| `kid_photo_title` | Bilde av %1$s | Picture of %1$s |
| `kid_photo_pick` / `kid_photo_take` / `kid_photo_remove` | Velg bilde / Ta bilde / Fjern bildet ditt | Choose a picture / Take a picture / Remove your picture |
| `kid_photo_change_action` | Endre bilde av %1$s | Change picture of %1$s |
| `kid_photo_local_note` | Bildet blir bare på denne telefonen. | The picture stays on this phone only. |
| `kid_photo_frame_hint` | Dra og knip for å velge utsnittet | Drag and pinch to frame it |
| `kid_photo_use` | Bruk bildet | Use this picture |
| `kid_photo_failed` | Kunne ikke bruke bildet | Couldn't use that picture |
| `contact_request_photo` | Legg til bilde (valgfritt) | Add a picture (optional) |

## 12. Open questions for the user

1. **A (faner nederst: Siste, Favoritter, Kontakter) eller B (én A-Å-liste med søk)?** Forslag: B. A er verdt det bare
   hvis barnet skal ha en anropsliste.
2. **Hvis A: «Siste» som alle anrop (inn, ut og tapte, én rad per person)** (forslag) **eller bare tapte anrop?**
3. **Hvis B: favoritter som et stjernefilter ved søket** (forslag) **eller ingen favoritter i B?**
4. **Lista A-Å** (forslag) **eller i din rekkefølge som i dag?** Hjem beholder din rekkefølge uansett.
5. **Søkefeltet fra 8 kontakter/samtaler** (forslag) **eller alltid?**

## 13. Revision 2: the design QA, finding by finding

`docs/review/qa-79-design.md` (43982b1f, on revision 1 b98cf66d). **Moot** means the part it was about is gone with
revision 2's phone-only pictures or its layouts.

| Finding | Where it landed |
|---|---|
| H1 the row doesn't fit 288 dp | Fixed, §4.2: the row designed at 288 dp - 44 dp avatar, two 48 dp targets touching (96 dp), a 128 dp text column; the short missed line ("Tapt anrop 14:02") that may wrap to two lines; the buttons on their own line from font scale 1.3 (`rowLayout`); `WholeWordsTest` at 288 dp, 1.0/1.3/2.0; the mockups drawn at 320 dp with these sizes, one row stacked at 1.3 |
| H2 avatar bindings would read CE | Fixed, §6.4: `KidContactPhotos` is an in-memory snapshot loaded on IO after the unlock, empty before; `bindContact` reads memory and `ContactPhotos.cached` only; decode under `kid:<file>`; a source check |
| M1 favourites pruned against the wrong list, and before the unlock | Fixed, §4.8 (and the same rule for pictures, §6.4): only a CE refresh of `Managed`, against `rules.contacts`; filtered by `rules.phoneBook` at render; the star and the long-press only for phone-book contacts |
| M2 the pending upload's states | Moot: no upload, no server hash (§6.4, §7) |
| M3 the request picture's hand-over | Fixed, §6.5: one step with the reconcile (it returns the hand-overs), only to a phone-book contact, deleted for `added` under another number and every other outcome, the directory swept at each reconcile, a draft file before the id kept in the saved state, the form's circle as a callback so its escape pin stands (§6.1) |
| M4 the kid sets pictures before the parent's switch exists | Moot: no server part, no switch |
| L1 the request button for a name already waiting | Fixed, §5.1: open requests are searched and shown; the button only when nothing matches and from two letters |
| L2 the "+" under calls off | Fixed, §4.5 and §8: `canAsk = Managed && callsEnabled` for the "+" and the button |
| L3 autofill, focus, the IME inset | Fixed, §5.1 and §3.1: `importantForAutofill="no"`, no `textPersonName`, no autofocus, the IME inset on the list |
| L4 the escape test's files, named | Fixed, §9: the named list, the moved allowance, the `KidPhotoActivity` pin |
| L5 `PicturePick.prepare` is SMS-bound | Fixed, §6.2: `decode` split out; SMS `prepare` on top; the contact path decode -> frame -> `forContact` |
| L6 the zoom cap under 1 | Fixed, §6.3: `max(1, …)`; a small picture used at 1x, never enlarged |
| L7 the pocket-call guard misattributed | Fixed, §10: the fling intercept is the guard; the 300 ms rule if the device check counts presses |
| L8 server details | Moot: no server part |
| L9 a re-pointed contact keeps the old picture | Fixed on the phone, §6.4: a number change drops the kid's picture |
| L10 the en strings | Fixed, §11's table; `TranslationsTest` in steps 2, 3, 4 and 6 |
| L11 the fold's origin map | Fixed, §5.2: built per code point from the shown string, the clamp, the test rows |
| L12 `KidPhotoActivity`'s guards | Fixed, §6.2: finishes on leaving the phone book or turning emergency, out of `FREE_SCREENS`, night; design 78's table gets the row |
| N1 the pill shown under 8 rows in the mockups | Fixed: every mockup with a pill has 8 or more rows (the list runs past the screen's edge) |
| N2 the order while searching | Fixed, §5.1: A-Å within each rank |
| N3 the pill under 8 after a sync | Fixed, §5.1: it stays while it holds text |
| N4 the form over a search | Fixed, §5.1 |
| N5 digits in Meldinger | Said, §5.1 |
| N6 no cue for a Melding that leaves Vibb | Accepted, §4.6: the card and TalkBack say it; no per-app glyph |
| N7 per-render work; a visible live region | Fixed, §4.7 and §4.6 |
| N8 RGB_565 banding | Taken, §4.7: list-sized ARGB_8888 thumbnails instead |
| N9 `shown` in the shared contract | Moot: no contract |
| N10 default on; emergency disagreement; step 2's size | Default on and the server's emergency answer are moot; step 2 is M+ (§9) |
