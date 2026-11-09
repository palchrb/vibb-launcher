# 79 - The kid's phone book as a list: favourites, missed calls, search, the kid's own pictures

Status: design note by the architect, 2026-11-09, on `main` at 23a92f3a, branch `design-79-phone-book`. **Nothing is
built.** Mockups: `docs/design/79-mockups.html` (the list with favourites and a missed call, search in use, Meldinger
with search, the contact card with the star and "Endre bilde", the kid choosing and framing a picture, the contact
request with a picture, the parent's view in the PWA).

The user's words (2026-11-09, on the contact-request mockup's list with a search pill):
- "Jeg liker listen!"
- Favourites: "Kanskje barnet skal få lov til å trykke stjerne på kontakter for en favoritt liste eller noe."
- Row actions: "liker de knappene for ring og melding, og om man trykker andre steder på kontakten så kommer kortet opp
  istedet".
- Missed calls: "Burde missed calls vises strukturert her på noe vis?"
- Search: "Vi burde ha en søkefunksjon både i kontaktliste og i sms? Gjenbruke den vi har i musikk appen?" and, on
  Meldinger's search: "På 3, tenkte egentlig bare kontakt navn!"
- Pictures: "Kanskje barnet burde kunne velge/ta et bilde som den kan styre per kontakt også, kanskje både i søknad og
  at hen kan sette og endre via kontakt kortet også?"

## 0. The shape

- **The phone book becomes a list** of solid night tiles on the kid's wallpaper, with a search pill under the header.
  Each row: the avatar (photo or initial, today's badge), the name, a second line only when there is something to say
  (a missed call, unread messages, "Nødnummer"), and two round buttons, **Ring** and **Melding**. A tap anywhere else
  opens today's contact card.
- **Groups, top to bottom:** Tapte anrop (until seen), Forespørsler (an answer to the kid's request, shown once),
  Favoritter (the kid's stars), Kontakter (everyone else, A-Å), Venter på svar (the kid's open requests). Each contact
  shows **once**. A group without rows has no header, and with no group above it "Kontakter" has none either.
- **Favourites are the kid's own**, set with a star on the contact card. They live on the phone, change nothing on
  Home, and the parent's "På Hjem-skjermen" changes nothing in them.
- **Search** in the phone book and in Meldinger: the music app's pill and rules in the launcher's Views. Names only,
  folded so "o" finds "ø". "Fant ingenting" when nothing matches; in the phone book it also offers "Be om ny kontakt"
  with the name already filled in.
- **The kid's own picture per contact** (later steps, separable): chosen or taken from the contact card or the contact
  request, framed with a drag and a pinch, re-encoded on the phone. It **wins on the kid's phone** over the parent's
  picture and **reaches the server**, so the parent sees it in the PWA, can remove it, and can switch the feature off
  for that phone.
- The contact request's **"+" stays in the header**. Its form, rows and answers fit into the list's groups.

## 1. What exists today (verified against the code, `main` 23a92f3a, and the named branches)

- **`calls/PhoneBookActivity.kt`** draws the wallpaper (`showsSystemWallpaper`, `WallpaperGround.apply(.., ownGround =
  true)`), then `include_kid_header` ("Telefonbok", back), an info line (`calls_off`, `calls_phone_book_empty`) and a
  `RecyclerView` with `GridLayoutManager(this, 3)` over `PhoneBookAdapter` (`item_kid_contact.xml`: a 64 dp avatar, the
  `KidBadge` at its top end, the name in `WholeWordTextView`, labels in `KidInk`). Tiles: `PhoneBookTile.Contact(contact,
  emergency, missed, unread)` and `PhoneBookTile.Emergency` (the fail-closed 112). `submit` calls
  `notifyDataSetChanged`; the photo, wallpaper, Element-missed and messages listeners each call `render()`.
- **What is listed:** `phoneBookView(state)` - the managed rules' `phoneBook` (`outbound`, the PWA's "I telefonboken"),
  in the parent's order (the server's `device_contacts.sort_order`); with calls off only the contacts whose number is
  an emergency number; with no readable rules the 112 tile.
- **The contact card** (`ContactSheet.show`, `sheet_contact.xml`, `KidSheetDialog`, night, edge to edge): photo with a
  ring (red for an emergency number), badge, name, the missed and unread lines (`KidAvatars.sheetLines`), **Ring**
  (off while another call is live, `secondCallAllowed`), **Melding** (our own thread when `opensOwnThread`, else
  `resolveMessageButton`'s app with "Meldingen åpnes i <app>", else no button), Lukk. Opening it marks the contact's
  missed calls seen (`MissedCallsRepo.markSeen`) and dismisses the missed-call notification. It opens from Home's
  contact row too.
- **Missed calls** (`MissedCallsRepo.contactMissed` -> `ContactMissed(phone, element)`): the call log of 14 days after
  the contact's seen mark (CE prefs `missed_calls_seen`) and after the last outgoing or answered call with that number;
  plus missed Element X calls (round L5). The badge is missed calls plus unread messages (`ContactBadge`, design 77 step
  2's fix round).
- **Photos** (`ContactPhotos`): the parent's picture per contact, `call_policy.contacts[].photo` (SHA-256), downloaded
  into `filesDir/contact_photos` (CE), decoded in the background with a 16 MB `LruCache` (full ARGB_8888 decodes, up to
  1024 px; the server sends at most 512 px). The server keeps one picture per contact on the global `contacts` row
  (`photo_hash`), shared by every phone with that contact, with the parent's crop (`photo-crop.js`,
  `docs/notes/server/launcher-ui.md`).
- **Avatars** (`KidAvatars.bindContact`) are bound on Home's row, the phone book, the card, the glance rows, the call
  screen and Home's call card, and Meldinger's rows. An emergency number never shows a photo: the red disc with its
  ring (`EMERGENCY_AVATAR`, design 34 P3).
- **Meldinger** (`ui/messages/MessagesActivity.kt`, `item_messages_row.xml`): the header with "Ny melding", the rows on
  `bg_day_event` (`kid_music_tile` #1B1B28, 18 dp corners, a white 12 % ripple), newest first; "Ny melding" swaps the
  list for the `sms_out` contacts. Its own solid ground (design 78 puts it in the light look).
- **Picking a picture** (design 77 step 3b, branch `sms-mms`, in its code-QA fix round): `ui/messages/PicturePick.kt` -
  the system photo picker sent to the system's own picker package, the camera only as the parent-allowed camera app
  (`cameraPackage`: installed, unsuspended, on the allowlist) writing to a file of ours through the share provider; the
  picker's answer accepted only as another app's `content://` URI (`pickedUriAcceptable`); the bytes decoded in the
  isolated parse process (`MmsParser.decodeImage`, EXIF orientation applied there, at most `PICK_MAX_PX` 1280 px);
  `PictureEncoder` writes our own JPEG (no EXIF). `KidScreensEscapeTest` lets `PicturePick.kt` alone name
  `ACTION_PICK_IMAGES` and `ACTION_IMAGE_CAPTURE`.
- **The contact request** (design 77 §14): branch `sms-contact-request` 58b7f07c has the API, the contract
  `contact_request.json`, the server and the phone's model and store (`calls/ContactRequests.kt`, CE prefs
  `contact_requests`); the WIP branch `sms-contact-request-kid-form-wip` 71f84c1a has the "+" in the header
  (`KidHeader`'s text action at 30 sp), the form in the grid's place (`ContactRequestForm`, name, number, an optional
  "Chat-ID", "Avbryt" / "Send til mamma og pappa") and the request rows above the grid. The request body is limited to
  1 KiB.
- **No diacritic fold exists.** D17's fold (`SmsSenders.fold`: NFC, then `lowercase(Locale.ROOT)`) is exact on
  purpose - it decides which sender names get through. Dagen's title rules (`TitleRules`) lowercase code point by code
  point and skip spaces. The music app's search (`music/.../logic/LibraryView.kt` `search`) is a plain
  `lowercase().contains`, at most 12 hits. None of them lets "o" find "ø".
- **Android backup is off** (`allowBackup="false"`).

## 2. Scope

**In:** the phone book screen (`PhoneBookActivity` and a new adapter and row), the contact card's star and "Endre
bilde", search in the phone book and Meldinger (its list and its "Ny melding" list), the kid's picture per contact on
the phone, the server, the policy and the PWA, the contact request's picture, and how the request's rows sit in the
list.

**Out:**
- Home: its contacts row stays the parent's "På Hjem-skjermen" list, in the parent's order. Home shows the kid's picture
  for a contact (it binds avatars the same way), nothing else changes.
- The lock glance, the call screen and notifications show the kid's picture through the same avatar binding; nothing
  else about them changes.
- Design 78's light look: the phone book stays on the wallpaper and out of 78's scope, as 78's table says. Meldinger's
  pill takes 78's page tokens when 78 lands (§5.4).
- Text-only contacts (`sms_out` without "I telefonboken"): the phone book stays the parent's "I telefonboken" list, as
  today. They are reached through Meldinger's "Ny melding".
- The music app's own search: it keeps its plain lowercase. Giving it the same fold is a small, separate item, not
  proposed here.

## 3. Decisions

**D1 - A list of solid tiles on the wallpaper.** The phone book keeps the wallpaper (ui-polish.md "Drawing", design
78's table). Every row, the search pill and the request rows are **solid** `kid_music_tile` (#1B1B28) tiles with 18 dp
corners, Meldinger's and Dagen's tile, so a row reads the same on navy, on a light photo and on a busy one. Only the
group headers and the info line are text on the wallpaper, drawn with `KidInk.label(.., dim = true)` as
`phone_book_info` is today (white or dark ink, scrim, shadow by `WallpaperInk`).

**D2 - Each contact shows once, in the first group that takes it.** Tapte anrop, then Favoritter, then Kontakter. A
favourite with a missed call sits in Tapte anrop until the call is seen, then returns to Favoritter. Showing contacts
twice (iOS's way) doubles a short list and makes TalkBack read the same person twice; with at most about 50 contacts
and search, once is enough.

**D3 - The rest is A-Å, not the parent's order.** A list of up to 50 people that the kid searches and scans reads best
in alphabetical order; the kid's own order is the stars. `java.text.Collator` in Vibb's language (nb puts Æ, Ø and Å
last), strength SECONDARY, ties by contact id. The parent's order stays on Home. **Asked in §11, question 3.**

**D4 - Favourites are the kid's, on the phone only, independent of Home.**
- A star on the contact card (top right) and a TalkBack action on the row. Starred contacts form "Favoritter", A-Å.
- Stored in CE prefs `phone_book_favourites` (`ids_v1`, contact ids), pruned to the phone book's ids at every
  `CallPolicyStore.refresh`. A contact with id 0 (a server too old to send ids) gets no star.
- **Home and favourites never touch each other:** starring doesn't put a contact on Home, and "På Hjem-skjermen"
  doesn't star. Home is the parent's choice of who is one tap away; favourites are the kid's shortcut inside the phone
  book. A contact can be both. Nothing is sent to the server: a star is a preference, not a rule (like the kid's hidden
  apps). Lost with a factory reset.
- They start empty; "Favoritter" appears with the first star. **Asked in §11, question 5.**

**D5 - Missed calls: a calm group at the top, a line on the row, today's badge.**
- **"Tapte anrop"** holds the contacts with missed calls that still count (phone and Element X, `ContactMissed`),
  newest first.
- The row's second line is today's sheet text for the phone's calls, `MissedCallText.describe` ("1 tapt anrop i dag
  14:02", "2 tapte anrop i går 09:10"), in `kid_missed_text` #FF8A8A (7.5:1 on the tile). With Element X calls too, the
  count is both and the time the newest; the card keeps the two separate lines.
- The avatar keeps design 34's badge (missed plus unread, the missed-call glyph), unchanged.
- **When a missed call counts as seen** - today's rule, plus the row's own buttons:
  - the kid opens the contact's card (today);
  - the kid taps **Ring** or **Melding** on the row or the card (new for the row and for Melding: both mean the kid
    dealt with that person);
  - the kid calls or talks with that number later (the call log rule, today);
  - it is older than 14 days (today).
- Opening the phone book alone does **not** mark anything seen (it still clears the notification, as today). The group
  is the reminder; it empties as the kid deals with each person.
- Unread messages make no group (Meldinger is their place). When a contact has unread messages and no missed call, the
  row's line says "1 ulest melding" (`contact_unread`) in `kid_core_dim`; with both, the missed line comes first and
  the unread count follows after " · ".

**D6 - The row's buttons follow the card's rules exactly.** One pure function decides both the card's buttons and the
row's (`contactActions`, §5.2), so a button shows on the row exactly when the card would show it:
- **Ring**: every phone-book contact while calls are on; while calls are off only emergency contacts are listed, and
  theirs shows. Dimmed and inert while another call is live, emergency numbers excepted (`secondCallAllowed`).
- **Melding**: our own thread (`opensOwnThread`: SMS "Styrt av Vibb", `sms_out`), else `resolveMessageButton`'s app
  (SMS app, Element X, Signal/Molly; installed, unsuspended, allowed). No button otherwise. Under a time rule the phone
  book is reachable only where calls are, as today, and the thread's compose bar says "Du kan ikke sende meldinger nå"
  where the gate says so (design 77 §6.7); the button itself follows the card.
- A row with neither button (a contact with no message app while calls are off - not listed then) still opens the
  card.

**D7 - Emergency contacts sit by name, with today's red disc.** The 48 dp red disc and its ring (2 dp at this size),
never a photo, never the kid's picture. The second line says "Nødnummer". Their **Ring** button is the tonal red pair
(`kid_emergency_tonal` disc, `kid_emergency_text` glyph, design 34 P7's pill colours) and **asks first**
("Ringe Politi?", the phone book's existing `calls_confirm_title` dialog in `KidAlertDialog`): a one-tap call from a
scrolling list is a pocket call waiting to happen, and an accidental 112 call costs more than an accidental call to
Mamma. The card's Ring stays as today (the card is already the second step). They can be starred. The fail-closed 112
row (no readable rules) is one row with the red disc, "Nødsamtale", no buttons; a tap opens `EmergencyCall.confirm` as
today's tile does.

**D8 - Search: the music app's look and behaviour, names only, folded.**
- **The pill** sits under the header and doesn't scroll: 44 dp high, 22 dp corners, `kid_music_tile`, an 18 dp
  magnifier glyph in `kid_core_dim`, the hint "Søk" (phone book) / "Søk etter navn" (Meldinger) in `kid_core_dim`, the
  text Nunito 700 16 sp `kid_ink`, a peach cursor, a 1.5 dp peach-50 % outline while focused, an "×" (48 dp target,
  "Tøm søket") while it holds text. `NoTextActions` on the field (no menus, no text classifier); input
  `textPersonName|textNoSuggestions`, IME action Search (closes the keyboard).
- **Live:** every keystroke filters (no debounce: 50 folded names take well under a millisecond). Groups dissolve while
  a query is typed: one flat list of hits, ranked - the name starts with the query, then a word in it does, then it
  contains it - each rank in the list's normal order. The matched part of the name is peach (#F0A884 on the tile,
  8.6:1), as the mockup marked it. Requests are not searched (nothing to call).
- **Empty:** "Fant ingenting" under a 72 dp tile-coloured disc with the magnifier in peach (the music app's
  `EmptyState`, smaller). In the phone book, while the kid may ask (`ContactRequestForm.canAsk`), a quiet button under
  it: "Be om å legge til «ol»" - it opens the request form with the query as the name (not when the query is all
  digits). Searching for a friend who isn't there is exactly when the kid wants to ask.
- **Back** with text in the pill clears it and closes the keyboard; the next Back leaves as today. The query is not
  kept across leaving the screen (the next visit starts with the full list), only across a rotation.
- **When it shows:** from 8 rows (`SEARCH_MIN_ROWS`: contacts in the phone book, conversations in Meldinger, contacts in
  "Ny melding"). Below that the whole list fits on one screen and a pill is noise. **Asked in §11, question 6.**
- **Names only, in both places.** Meldinger matches the row's shown title (the contact's name now, else the name the
  conversation last had - D18 -, else what the row shows), never message text (the user: "bare kontakt navn").
  **The phone book doesn't match the number either:** the kid sees no number anywhere in the phone book or on the card,
  so a hit on one would look like a mistake, and the kid knows people by name. Keeping both screens to names also keeps
  one rule.

**D9 - One search fold, for search only.** `ui/search/SearchFold.kt` (pure, new): NFC, `lowercase(Locale.ROOT)` (D17's
first two steps), then per code point NFD with the combining marks dropped, then the letters NFD can't split: ø -> o,
æ -> ae, œ -> oe, ß -> ss, đ -> d, ł -> l, þ -> th, ı -> i. Both the name and the query are folded, so "o" finds
"Ørjan" and "Bjørn", "a" finds "Åse", "ae" finds "Ærlig", "e" finds "Hélène". It keeps each folded character's place in
the original, so the highlight lands on the right letters. **It must never decide anything**: D17's sender names stay
exact (a looser fold there would let a "Vípps" through on the parent's "Vipps"), and the call rules match numbers, not names.
`SearchFoldTest` pins the table and that no file under `sms/` or `calls/CallRules.kt` calls it.

**D10 - The kid's picture per contact.**
- **Who wins:** on the kid's phone, **the kid's picture**, else the parent's, else the initial on its soft colour. It is
  the kid's phone book; a picture of Mamma the kid chose is the one the kid wants to see. The parent's picture stays
  what every other phone shows, and comes back the moment the kid's goes. Emergency numbers never show one (D7).
- **Where it lives: on the server, per phone and contact** (`device_contact_kid_photos`), not on the global contact row,
  so a kid's picture never reaches a sibling's phone. The phone uploads its own re-encoded JPEG; the server decodes and
  re-encodes it again with today's pipeline (`photos::process_limited`, square, at most 512 px, JPEG q85, no metadata),
  so nothing is ever stored as sent. The policy names it per contact; the phone downloads it like the parent's picture.
  This gives the parent sight and control, survives a factory reset and re-provisioning onto the same phone row, and
  keeps the server the owner of what the phone shows. The cheaper "phone only" variant is weighed in §6.6 and **asked
  in §11, question 1.**
- **The parent sees and decides** (PWA, Device › Calls & Contacts):
  - each contact's row shows "Bildet Sverre valgte" next to the parent's own, with **Fjern**;
  - a switch per phone, "Sverre kan velge egne kontaktbilder", **on by default**; off hides the kid's pictures on the
    phone (the parent's show), hides "Endre bilde" and the request form's picture, and keeps the pictures on the server
    - on again brings them back;
  - no push when the kid sets one (calm; it is a picture of someone the parent already allowed).
- **Every limit is liftable:** the switch is the only one and it is the parent's, both ways. It isn't an app
  restriction, so the override and the pause don't touch it (as design 14's names and the wallpaper list).
- **The kid sets it** from the contact card ("Endre bilde": the camera chip on the photo, or a tap on the photo) and
  from the contact request form. The kid can also take it away again ("Fjern bildet ditt").
- **Framing stays simple** (§6.3): the picture in a square under a circle, drag to move, pinch to zoom (1x to 3x),
  "Bruk bildet". No slider, no rotation, no filters. With TalkBack, "Bruk bildet" takes the centred square.
- **Picking reuses design 77 step 3b's `PicturePick`** unchanged in behaviour: the system photo picker, the
  parent-allowed camera only (none allowed: no "Ta bilde"), the `content://` check, the isolated decode with EXIF
  orientation, our own JPEG with no EXIF. No new escape: `PicturePick.kt` stays the one file that may name the picker
  or the camera (it moves to `ui/pictures/` so the phone book and Meldinger share it; the test's allowance moves with
  it).
- **The contact request's picture** (§6.5) stays on the phone until the contact arrives, then becomes the kid's picture
  for that contact through the same upload. No change to the request's API. **Asked in §11, question 4.**

**D11 - The "+" stays in the header.** The list design doesn't argue against it: the header is where the kid's eye
starts, the list scrolls under it, and "Fant ingenting"'s "Be om å legge til «…»" adds the second, well-timed way in.
It becomes a 40 dp filled peach disc with a dark "+" glyph (the mockup's; `kid_accent` / `kid_ground`, 9.9:1),
described "Be om ny kontakt". It shows only while the kid may ask (calls managed, as the WIP's `canAsk`).

## 4. The list

### 4.1 Layout, top to bottom

```
[‹]  Telefonbok                      (+)      include_kid_header; the "+" while the kid may ask
( 🔍  Søk                               )      the pill, from 8 contacts; fixed
  info line (calls off / empty)                as today, KidInk on the wallpaper
  TAPTE ANROP                                  header only with rows
  [Pappa   1 tapt anrop i dag 14:02  (☎)(✉)]
  FORESPØRSLER                                 answers shown once
  [Ola     Ikke lagt til                   ]
  FAVORITTER
  [Mamma                             (☎)(✉)]
  KONTAKTER                                    header only when a group is above
  [Bestemor                          (☎)(✉)]
  [Politi  Nødnummer                 (☎)   ]   red disc, red-tonal Ring that asks
  [Trener Kari                       (☎)   ]   no message app: no Melding
  VENTER PÅ SVAR
  [Lina    Venter på svar                  ]   outlined tile, nothing to tap
```

- The group headers: 13 sp Nunito 800, `KidInk` dim, 4 dp side inset, 14 dp above, 6 dp below; accessibility headings.
- The first group header sits 8 dp under the pill.
- The list has 16 dp side padding (the page's) and 24 dp bottom padding plus the navigation bar's inset; rows are 8 dp
  apart.
- The request answers (Forespørsler) sit high because they are news, shown once; the open requests (Venter på svar) sit
  low because nothing can be done with them.

### 4.2 Row anatomy (`item_phone_book_row.xml`)

- **Tile:** `bg_day_event` (#1B1B28, 18 dp, white 12 % ripple), min 64 dp, padding 10/12/8/12 dp.
- **Avatar:** 48 dp (Meldinger's size), `KidAvatars.bindContact` at 20 sp initials, the `KidBadge` at its top end
  lifted 2 dp, no white ring (Home's ring is for busy wallpapers; the row is on a solid tile). No press scale on the
  avatar: the row's press is its ripple.
- **Text:** the name, Nunito 800 16 sp `kid_ink`, one line, ellipsized (a 40-character name fits at 1.0; at 2.0 the
  row grows to two lines - `WholeWordTextView` for the name, as today, so a name is never cut between letters); the
  second line 13 sp 700, one line, ellipsized - absent when there is nothing to say (the name centres vertically).
- **Buttons:** 40 dp discs in 48 dp targets, 4 dp apart, right-aligned. **Ring**: `kid_sheet_button` (white 8 %) disc,
  the phone glyph in the new `kid_call_glyph` #7FC28F (6.5:1 on the disc over the tile). **Melding**: the same disc, the
  message glyph in `kid_accent` (6.9:1). Tonal, not filled: 50 green discs down a list would shout. The press is design 34's
  `PressMotion` (scale 0.94, 90/140 ms, nothing when animations are off) and an oval ripple; a 2 dp peach focus ring for
  keyboard focus. Dimmed to 40 % and inert while another call is live (D6).
- **Waiting rows:** the tile with a 1.5 dp peach-45 % outline, a 48 dp `kid_music_control` disc with the initial in
  peach (or the request's picture), "Venter på svar" in `kid_core_dim`; not clickable.

### 4.3 TalkBack

- A row is **one node** with role button: its description is the name, then the second line ("Pappa, 1 tapt anrop i
  dag 14:02"; "Mamma, 2 uleste meldinger"; "Politi, nødnummer"). Activating it opens the card.
- The two buttons are focusable for a keyboard but `importantForAccessibility="no"`; the row carries them as **custom
  actions**: "Ring Pappa", "Send melding til Pappa" (or "… i Element X"), "Legg til i favoritter" / "Fjern fra
  favoritter". Fifty contacts are fifty swipes, not a hundred and fifty.
- The headers are headings; the pill's field says "Søk i telefonboken" / "Søk etter navn i meldingene"; a search
  announces "3 treff" / "Fant ingenting" through a polite live region (a text view only TalkBack reads, updated at
  most once per 500 ms of typing; `announceForAccessibility` is deprecated on API 36).
- The card's star is a toggle ("Favoritt", state "på"/"av"); "Endre bilde av Mamma" is a button.

### 4.4 Press feedback, badges and colours (design 34)

The row's press is the tile's ripple (Dagen's, Meldinger's); the round buttons use `PressMotion` as Home's icons do. The
badge is `KidBadge` (`kid_badge` #B4532C, white 5.0:1), never animated. New colour: `kid_call_glyph` #7FC28F only.
`KidPaletteTest` gains the row: name, both line colours and the highlight on the tile; both glyphs on the button disc
over the tile (non-text, >= 3:1, both well above).

### 4.5 Under each state

- **Calls managed and on:** the full list.
- **Calls off (parent):** today's info line, the emergency contacts only, no groups, no search, no "+".
- **No readable rules:** the one 112 row, nothing else (today's tile).
- **Calls unmanaged:** no phone book (the tile is hidden on Home), as today.
- **A time rule:** the phone book is reachable only where the rule allows calls (the time-rule screen's "Telefonbok"
  row), as today; a rule beginning while it is in front brings the time-rule screen (today's redirect).
- **A call live:** Ring dimmed on every row but emergency ones.

### 4.6 Performance with about 50 contacts

- `LinearLayoutManager`, a `ListAdapter`-style diff over stable ids (`contact:<id>`, `header:<group>`,
  `request:<id>`), no item animator (as Home's grid), `setHasFixedSize`.
- **Photos rebind in place:** a decode finishing calls `notifyItemRangeChanged(.., PHOTO)` and only the avatars rebind
  (today every listener re-renders the whole screen, and every decode started another full bind).
- **`ContactPhotos` decodes as RGB_565** (the JPEGs are opaque): a 512 px picture is 0.5 MB instead of 1 MB, so the
  16 MB cache holds 32 instead of 16 - a screen of rows, Home's row and the card stay decoded together.
- The message app's usability (`usablePackages`) is asked once per render per package (at most four packages), not
  per row. The folded names are computed once per render; a keystroke only filters.
- The missed calls are read on IO, as today.

## 5. Search

### 5.1 The fold and the match (pure, `ui/search/`)

- `searchFold(text): Folded` - D9's steps, with `origin: IntArray` (each folded char's index in the original).
- `searchMatch(name, query): Match?` - `query` folded and trimmed; empty = no search; inner spaces kept ("ole m" finds
  "Ole Martin"). `Match(rank: PREFIX | WORD | INSIDE, span: IntRange in the original)`. A word starts after a space,
  "-" or "'". At most 40 characters of query are used.
- `searchRows(rows, query, titleOf)` - the hits ranked, stable within a rank.

### 5.2 The phone book's model (pure, `calls/PhoneBookList.kt`)

- `contactActions(contact, rules, emergency, callLive, ownThread, message): ContactActions(call, message)` - the card's
  inline decisions moved out of `ContactSheet.show` unchanged, used by the card and the row.
- `phoneBookItems(view, missed, unread, favourites, requests, query, collator, canAsk, isEmergency): List<PhoneBookItem>`
  with `Header(group)`, `Person(contact, emergency, missed, unread, favourite, match)`, `Request(row, picture)`,
  `EmergencyDialer`, `NothingFound(query, askName)`. D2's once-rule, D3's order, D5's group, the request groups, D8's
  dissolve and threshold.

### 5.3 The widget (`ui/search/KidSearchField.kt`, `view_kid_search.xml`)

One small View helper both screens include: binds the pill (focus outline, the "×", IME Search, `NoTextActions`), calls
back with the text, owns Back's "clear first" (`OnBackPressedCallback` enabled while text is in it), and saves the text
for a rotation only.

### 5.4 Meldinger

- The pill under the header in the list and in "Ny melding" (from 8 rows each), hint "Søk etter navn".
- Matches each row's shown title (`MessagesScreens.name(row.who)`), ranked as above; the matched part peach.
- "Fant ingenting" with the same disc; no request button here (Meldinger isn't where contacts are asked for).
- The pill takes `kid_music_tile` now, as the rows do. When design 78 lands, it takes 78's row tile token like the rows
  (whichever lands second maps it; 78's `KidPaletteTest` covers the pair).

## 6. The kid's picture

### 6.1 On the contact card

- **The star**: a 48 dp toggle at the sheet's top right (outline star in `kid_core_dim`, filled in `kid_accent` when
  on), in line with the handle. An emergency contact has it too (D7).
- **"Endre bilde"**: a 36 dp camera chip (`kid_ground_lift` disc, peach glyph, 1.5 dp `kid_sheet` ring so it reads on
  any photo) at the photo's bottom start - the badge keeps the bottom end - and the photo itself is the same button.
  Shown only while the switch is on (`call_policy.kid_photos`), never for an emergency number, never before the first
  unlock (it's CE anyway).
- After a change the card rebinds through `ContactPhotos`' listener (as today for a late decode).

### 6.2 The picture page (`calls/KidPhotoActivity.kt`, night, not on the wallpaper)

- Header "Bilde av Mamma" with back. The current picture large (200 dp circle). One card of rows (the time-rule
  screen's `item_lock_row` look): **Velg bilde** (the photo picker), **Ta bilde** (only with an allowed camera),
  **Fjern bildet ditt** (only while the kid's picture is set; then the parent's or the initial shows). Under the card,
  in `kid_core_dim`: "Mamma og pappa kan se bildet." - honest, short.
- A failed pick or decode: "Kunne ikke bruke bildet" on the page, nothing changes. A picture too large (over 30 MB)
  says the same.
- Reachable where the phone book is: it checks the time rule at resume and watches it (`LockWatch`, as Meldinger), and
  finishes when the switch goes off at a sync. Its own activity, so the result of the picker and the camera lands in
  one place whether the card was opened from the phone book or from Home.

### 6.3 Framing (`calls/PhotoCropView.kt`, pure `PhotoCrop.kt`)

- The decoded picture (at most 1280 px, from the isolated process) fills a square frame the width of the page less
  32 dp, covered (`centerCrop`); a black 55 % veil with the circle cut out shows what the avatar will show (a light
  1 dp ring on the circle's edge).
- One finger drags, two pinch: from 1x (cover) to `min(3, shortSide / 384)`, so the square always keeps at least 384
  px of the decoded picture - what the card's 132 dp circle needs at 3x. The picture never leaves the square (clamped
  after every move). No slider, no zoom buttons, no rotation (EXIF is already applied).
- "Bruk bildet" (peach pill) and "Avbryt" (quiet). The hint above: "Dra og knip for å velge utsnittet".
- The output: the square cut from the decoded pixels, scaled to at most 512 px, encoded by `PictureEncoder` (a new
  `forContact`: JPEG q85, no EXIF). It is the main process's own JPEG; the picked file is never decoded in the main
  process (design 77 D8, unchanged).

### 6.4 Storage, upload, what shows

- **Pending on the phone:** the JPEG goes to `filesDir/kid_photos/<random>.jpg` with a CE prefs record (`kid_photos`:
  contact id -> file, state `PENDING_UPLOAD` | `PENDING_REMOVE`). It shows at once.
- **The upload** (`PUT /api/devices/contacts/{contact_id}/kid-photo`, §7) runs in the sync after `ContactPhotos.sync`
  (asked for right away with `SyncRunner.request(.., "kid_photo")`). An accepted upload leaves the record waiting for
  the policy: once the policy names a `kid_photo` for the contact and that file is cached, the pending file and record
  go. `off`, `not_a_contact` and `emergency` drop the pending picture; `capped` keeps it for tomorrow; `invalid` drops
  it and the card says nothing (the page said it worked - an invalid JPEG of our own is a bug, logged).
- **"Fjern bildet ditt"**: `PENDING_REMOVE`, the parent's picture shows at once, `DELETE` at the next sync.
- **What shows** (pure `shownPhoto(contact, kidPhotosOn, pending, emergency)`): emergency - none; a pending upload -
  the pending file; a pending removal - the parent's; kid photos on and `kid_photo` set - that; else `photo`. Every
  avatar binding goes through it (`KidAvatars.bindContact`).
- **The cache:** `wantedPhotoHashes` takes `kid_photo` hashes too (while on), so `ContactPhotos.sync` downloads them
  and deletes the rest; pending files live in their own directory, which `ContactPhotos` never prunes.
- **Nothing in DE storage**; the call screen shows a photo only after the first unlock, as today.
- **Wipe and reset:** CE only, so a factory reset clears the phone; the server's copy comes back with the next policy
  when the phone is provisioned onto the same device row. A new device row in the PWA starts without them (as every
  per-phone setting). Stars don't come back (D4).

### 6.5 In the contact request

- The form gets a 72 dp circle at the top, "Legg til bilde (valgfritt)" under it; a tap opens the picture page's
  choices and framing (the same activity, in request mode: no "Fjern", the title "Bilde av <navn>" once a name is
  typed, else "Bilde").
- The picture stays on the phone with the request (`filesDir/contact_request_photos/<request id>.jpg`, named in the
  record) and shows on the "Venter på svar" row.
- When the request's contact arrives (the reconcile step already sees its number among the contacts), the picture
  becomes the kid's picture for that contact - a `PENDING_UPLOAD` record - unless the kid already set one. A decline,
  an expiry or the switch off deletes it.
- **No change to the request's API or contract**; the parent sees the picture on the contact's row once added. The
  variant where the picture also goes up with the request (so the PWA's request card shows who "Ola" is) needs a
  second upload route; §11, question 4.

### 6.6 Phone only, weighed and not chosen

Keeping the kid's pictures in CE storage on the phone alone would skip §7 entirely (no table, no route, no policy key,
no PWA): about half the work. It loses three things the user asked about: the parent couldn't see or remove a picture,
the switch would be a policy flag guarding something the server never sees, and a reset or a new phone would lose them.
The data model is chosen now because changing from phone-only to server later means a migration of what phones hold.

## 7. API and server (both directories in one commit, with the contract)

**The policy** (`call_policy`):
- `kid_photos: bool` - the switch (missing = off on the phone: an older server can't take the upload).
- `contacts[].kid_photo: string | null` - the SHA-256 of the kid's picture for that contact on this phone; always
  `null` while `kid_photos` is off and for an emergency number.
- Pinned by `policy_json_keys_snapshot` and the launcher's `PolicyResponseCompatTest`.

**The upload** - `PUT /api/devices/contacts/{contact_id}/kid-photo` behind `require_device_token`:
- body `image/jpeg`, at most 1 MiB (the route's own limit; the phone sends 50-150 KB);
- checks: the switch on; the contact on this phone with "I telefonboken"; not an emergency number
  (`emergency::is_emergency`); decodes within `photos` limits (`process_limited`, `Shape::Square`, 512 px, q85);
  at most 20 uploads a day in the phone's own day (`phone_zone`, one `BEGIN IMMEDIATE` as r13's counter);
- answers (HTTP 200): `{"accepted": true, "photo": "<sha256>"}`, or `{"accepted": false, "reason": "off" |
  "not_a_contact" | "emergency" | "invalid" | "capped"}`; 413 past the body limit; 401 as every device route.
- One picture per (phone, contact): a new one replaces the old, which is pruned. The phone is nudged
  (`command_notify`) so the policy follows.

**Removal** - `DELETE /api/devices/contacts/{contact_id}/kid-photo` -> `{"accepted": true}`, idempotent (also for a
contact without one).

**Serving** - `GET /api/devices/contact-photos/{hash}` also serves a kid picture that **this** phone's policy names
(else 404, as today).

**Storage:**
- Migration: `device_contact_kid_photos(device_id, contact_id, photo_hash, set_at, PRIMARY KEY(device_id, contact_id))`,
  removed with the contact's `device_contacts` row and with the device; `device_policy.kid_contact_photos` (1 by
  default); a counter table or column for the day's uploads.
- `photos::KID_CONTACT_PHOTOS` (`data/kid_contact_photos`), its own lock, `prune` and `recover_missing`; backups carry
  `kid_contact_photos/<hash>.jpg`; the restore and recovery tests as for the contact photos.
- The status report's `capabilities` gains `kid_contact_photos_v1`; the PWA shows the switch and the pictures only for
  a phone that claims it (as `sms_engine_v1`).

**The contract** - `server/testdata/kid_contact_photos.json`, byte-identical in `launcher/app/src/test/resources/`:
`capability`, `policy` (a `call_policy` with `kid_photos` on and two contacts, one with both pictures), `policy_off`,
`answers` (every upload answer and the removal's), `shown` (rows of switch, `photo`, `kid_photo`, pending state and
emergency -> the hash or file the phone shows; the server checks only that its policy rows produce the same `kid_photo`
values). The server's `kid_contact_photos::tests::the_contract` produces `policy`, `policy_off` and every answer; the
launcher's `KidPhotosTest` decodes them, runs `shown` and encodes nothing it doesn't send; both compare the copies.
Paths into both workflows' lists and the root `CLAUDE.md`.

**The PWA** (Device › Calls & Contacts; nb + en; every form returns to `?open=<id>#contact-<id>` with the scroll kept -
a save never jumps):
- the switch "{name} kan velge egne kontaktbilder" / "{name} can choose contact pictures", help: "Bildene vises bare på
  denne telefonen. Du ser dem her og kan fjerne dem." / "They show on this phone only. You see them here and can
  remove them.";
- in a contact's row: the kid's picture as a second 48 px circle, "Bildet {name} valgte" / "The picture {name} chose",
  **Fjern** (`POST /devices/{id}/contacts/{cid}/kid-photo/remove`);
- optional, **asked in §11, question 2**: "Bruk som kontaktbilde" - copies it into the contact's own picture (every
  phone with the contact gets it; the parent can re-crop it there).
- The Contacts group's privacy line covers them: kept without location or camera data, in the backups too.

## 8. For the contact request now (design 77 §14, being built on `sms-contact-request`)

What the request's kid screens should be, so nothing is built twice:
- **The "+"**: in the header, as D11 (a 40 dp peach disc with a "+" glyph instead of the 30 sp text action).
- **The form**: in the list's place, as the WIP has it (name, number, "Chat-ID (valgfritt)", "Send til mamma og pappa",
  "Avbryt"); give `ContactRequestForm.open(name: String? = null)` a prefilled name for "Fant ingenting"'s button. The
  picture circle comes with step 6 here.
- **After "Send"**: a calm confirmation in the form's place (mockup 3 of the request mockup: a peach check disc,
  "Sendt", "Mamma og pappa har fått beskjed. Du ser det i telefonboka når Ola er lagt til.", "Ferdig"), then the list.
  In a long list the new "Venter på svar" row is off screen, so the kid needs to see that it went.
- **The rows**: keep the WIP's rows above today's grid until step 2 of this design; step 2 moves them into the list's
  "Forespørsler" (answers, top) and "Venter på svar" (open, bottom) groups in the row's tile.

## 9. Steps (small, each with its tests)

1. **The fold and the list model** (pure, launcher; S). `ui/search/SearchFold.kt`, `SearchMatch`; `calls/PhoneBookList.kt`
   (`phoneBookItems`, `contactActions` moved out of `ContactSheet` with no behaviour change). Tests: `SearchFoldTest`
   (the table, "o"/"ø", "a"/"å", "ae"/"æ", "e"/"é", spans in the original, no caller under `sms/` or in `CallRules.kt`),
   `PhoneBookListTest` (groups and their order, once-rule, A-Å with nb's Æ Ø Å, the emergency and fail-closed rows,
   calls off, the threshold, the dissolve and rank), `ContactActionsTest` (the card's cases, unchanged answers).
2. **The phone book as a list** (launcher; M). `item_phone_book_row.xml`, `item_phone_book_header.xml`, the new adapter
   with the diff and the photo payload, `ContactPhotos` in RGB_565, the missed group and line, D5's new seen marks (the
   row's Ring and Melding, the card's Melding), D7's asking Ring, the request rows in their groups (after
   `sms-contact-request` is merged), TalkBack's node and actions. The "+" as D11. Tests: `PhoneBookListTest` grows,
   `KidPaletteTest` (the row and `kid_call_glyph`), `KidScreensEscapeTest` (the new files guarded), `MissedCallsTest`
   (seen by Melding), `WholeWordsTest`'s phone-book case for the row's width. Emulator: 3 and 50 contacts, font 1.0 /
   1.3 / 2.0, a light and a busy photo wallpaper, TalkBack, a call live.
3. **Search** (launcher; S-M). `KidSearchField` + `view_kid_search.xml` in the phone book and in Meldinger's list and
   "Ny melding"; the highlight, "Fant ingenting", the phone book's "Be om å legge til «…»" (needs the request form),
   Back clears. Tests: `KidSearchTest` (the Back order, the threshold, the prefill rule: not all digits),
   `KidScreensEscapeTest` (`NoTextActions` on the field), `MessagesModelTest` (title match only).
4. **Favourites** (launcher; S). The card's star (also from Home's card), CE prefs `phone_book_favourites` with the
   prune at refresh, the row's action. Tests: `FavouritesTest` (toggle, prune, id 0 refused), `PhoneBookListTest`'s
   group.
5. **The kid's pictures: API and server** (both directories, one commit; M). §7's migration, routes, store, backups,
   policy keys, capability, the contract and both sides' contract tests; the launcher's DTO fields, `MdmApi` calls,
   `wantedPhotoHashes`, the upload/remove in the sync (no screen yet), `shownPhoto` in `bindContact`. Tests: the contract
   on both sides, `policy_json_keys_snapshot`, `PolicyResponseCompatTest`, `PhotoCacheTest` (kid hashes wanted while on,
   dropped while off), the server's route tests (each answer, the day cap, another phone's contact, emergency, a
   PNG re-encoded, serving only this phone's), backup and recovery.
6. **The kid's pictures: the phone's screens** (launcher; M). Needs design 77 step 3b merged: `PicturePick` moved to
   `ui/pictures/` (the escape test's allowance with it), `PictureEncoder.forContact`; `KidPhotoActivity`, `PhotoCropView`
   and pure `PhotoCrop`, the card's chip and photo tap, the request form's circle and the hand-over at the contact's
   arrival. Tests: `PhotoCropTest` (cover, clamp, zoom cap, the cut), `KidPhotosTest` (the pending states, the
   request hand-over), `KidScreensEscapeTest`, `TranslationsTest`. Emulator: pick, the camera (emulator camera), a
   rotated JPEG, a 30 MB file, switch off at a sync with the page open, offline then online.
7. **The kid's pictures in the PWA** (server; S). The switch, the row's picture and Fjern, (if wanted) "Bruk som
   kontaktbilde". Tests: the page's markup and both forms' redirects, the switch's effect on the policy, nb + en keys.

Order and size: 1-4 are launcher-only and can follow each other right away (S, M, S-M, S); 5-7 wait for the user's
answer on question 1, and 6 waits for step 3b and the contact request's merge. Notes to update as each lands:
`docs/notes/launcher/calls.md` (the phone book), `home-and-photos.md` (photos), `ui-polish.md` (the row), the server's
`launcher-ui.md` and `calls-and-contacts.md`, the root `CLAUDE.md`'s contract paragraph and both workflows' paths
(step 5).

## 10. Risks and checks

- **Pocket calls from a row:** the row's Ring is one tap. Mitigated by the 48 dp targets well apart from the row's
  body, the press waiting `tapTimeout` in a scrolling list (design 34), and the confirm for emergency numbers. Device
  check: scroll fast through 50 rows on the Jelly Star and count accidental presses.
- **The highlight's indices** with folds that grow (æ -> ae): covered by the origin map and its test.
- **A picture of a person the kid took**, sent to the family's server: the same place the parent's pictures already
  are, re-encoded twice, never sent to another phone. The page says the parents can see it.
- **The isolated decode is design 77's**: when it is unavailable (the SMS code failing), the picture page says it
  couldn't use the picture; calls, the list and the card are untouched (design 77 §6.10).
- **Cache pressure:** with 50 photos plus kid photos the LRU still thrashes on a fast fling; the payload rebind keeps it
  to avatars. A frame-time check (`dumpsys gfxinfo`) at 50 photos on the Jelly Star.

## 11. Open questions for the user

1. **Barnets bilder: på serveren** (du ser dem i PWA-en, kan fjerne dem og slå det av; de overlever en tilbakestilling)
   **eller bare på telefonen** (halve jobben, men du ser ingenting)? Forslag: serveren.
2. **"Bruk som kontaktbilde"** i PWA-en - et barnebilde blir kontaktens vanlige bilde på alle telefoner. Vil du ha den?
3. **Resten av lista A-Å** (forslag) eller i din rekkefølge som i dag? Hjem beholder din rekkefølge uansett.
4. **Bildet i en forespørsel:** venter på telefonen til du har lagt til kontakten (forslag, ingen ny API), eller skal
   det vises på forespørselen i PWA-en så du ser hvem "Ola" er?
5. **Favoritter starter tomme** (forslag), eller med Hjem-kontaktene ferdig stjernemerket?
6. **Søkefeltet fra 8 kontakter/samtaler** (forslag), eller alltid?
