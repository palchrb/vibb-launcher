# Design QA: design 79 - the kid's phone book as a list (`docs/design/79-phone-book-list.md`)

Checked against `design-79-phone-book` (b98cf66d, on `main` 23a92f3a), with `79-mockups.html`, and against the code:
- `main`: `calls/PhoneBookActivity.kt`, `PhoneBookAdapter.kt`, `ContactSheet.kt`, `MessageButtons.kt`, `ContactPhotos.kt`,
  `MissedCallsRepo.kt`, `MissedCalls.kt`, `CallPolicyStore.kt`, `CallRules.kt` (`phoneBookView`, `withTimeRule`,
  `secondCallAllowed`), `ElementRoomStore.kt`, `ui/home/KidAvatars.kt`, `ui/home/MissedCallText.kt`, `ui/NoTextActions.kt`,
  `ui/messages/MessagesActivity.kt` and `MessagesScreens.kt`, `colors_kid.xml`, the manifest (direct boot), and
  `KidScreensEscapeTest`;
- the server's `photos.rs` (`Store`, `process_limited`, `Shape`), `device_api::contact_photo`, `calls::view_photo`,
  `device_routes.rs`, and migration 0022 (`device_contacts`);
- `sms-contact-request` 58b7f07c and `sms-contact-request-kid-form-wip` 71f84c1a: `ContactRequests.kt`,
  `ContactRequestForm.kt`, the DTOs, and the WIP's `KidScreensEscapeTest` additions;
- `sms-mms` a2c53603: `ui/messages/PicturePick.kt`, `MmsParser`, `MmsParseProtocol`, `LockTaskHelpers.kt`, and its
  `KidScreensEscapeTest`;
- design 77 §6.7 and §14, design 78's scope table, and `docs/notes/launcher/` (`time-rules.md`, `ui-polish.md`,
  `home-and-photos.md`, `security.md`).

Contrast figures were recomputed. No build, no emulator.

**Verdict: a sound shape that matches what the user asked for.** It has one list, the row's buttons decided by the
card's own rules, names-only search with a fold that decides nothing, and the kid's pictures on the server under the
parent's switch. It opens no new way out of the kiosk:
- the row's Ring and Melding reach exactly what the card reaches;
- the search field opens nothing;
- "Be om å legge til «…»" only prefills a form the "+" already opens;
- the picture page reuses step 3b's picker, camera, `content://` check and isolated decode.

The kid's picture can't disguise anyone in a way that matters (see "Disguise" below).

Two things need a change before the build:
- **The row doesn't fit the user's own phone.** On the Jelly Star's 288 dp of content, the specified sizes leave about
  100 dp for the name and the missed-call line, so the "structured" missed line is cut (H1).
- **Every avatar binding would read CE storage.** That includes the in-call screen, which runs before the first unlock
  (H2).

The Mediums are state-machine gaps:
- favourites pruned against the wrong list (M1);
- a pending picture cleared by the wrong policy (M2);
- the request picture's hand-over (M3);
- a step order that lets the kid set pictures before the parent has the switch (M4).

Counts: **0 Critical, 2 High, 4 Medium, 12 Low, 10 Nit.**

## What holds (verified)

- **§1 is accurate.** The grid, its tiles and its listeners, `phoneBookView`, the card's rules, `ContactMissed` and its
  seen marks (CE `missed_calls_seen`, keyed by number), `ContactPhotos` (CE, the 16 MB LRU, no disk reads in `cached`),
  the avatar call sites, and Meldinger's rows and "Ny melding". There is no diacritic fold anywhere, and D17's fold is
  exact.
- **The row's buttons can't go further than the card.** `contactActions` is the card's inline logic moved out unchanged
  (`secondCallAllowed`, `opensOwnThread`, `resolveMessageButton` with `usablePackages`: installed, unsuspended, on the
  allowlist).
  - While calls are off (the parent's or a time rule's, `withTimeRule`), `phoneBookView` lists only emergency contacts,
    so no Ring for anyone else can appear.
  - Under a time rule the phone book is reachable only where calls are, as before.
  - `ContactSheet.show` has two callers (Home and the phone book) and none from the lock, so the card's new star and
    "Endre bilde" are never reachable from the lock.
- **Emergency rows.** The confirm on the row's Ring (D7) is the right answer to the pocket-call risk where it costs the
  most. The card's Ring stays one step, which is right because the card is the second step.
- **Picking.** `PicturePick` (sms-mms) has:
  - the system picker pinned to the system package (`MATCH_SYSTEM_ONLY` + `FLAG_SYSTEM`);
  - the camera only as a usable, allowlisted handler, which also means not suspended under a time rule, so "Ta bilde"
    disappears under a rule that doesn't spare the camera;
  - `pickedUriAcceptable`;
  - the bytes capped at `PICK_MAX_BYTES` (30 MB) and decoded only in a fresh isolated process (`bindIsolatedService`,
    always declared, not tied to the SMS mode);
  - our own EXIF-free JPEG.

  `HelperKind.PHOTO_PICKER` is pinned unconditionally. The server re-encodes again (`process_limited`, `Shape::Square`,
  512 px, q85).
- **Server shape.** It follows existing patterns:
  - per-device rows with a cascade (`device_contacts` cascades on device and contact; FKs are on, `main.rs` `.foreign_keys(true)`);
  - a hash-addressed `Store` with a lock, prune, recovery and backups;
  - the serving route checking that the hash is named for **this** device, as `contact_photo` already does.

  `kid_photos` missing means off, so an older server never gets an upload.
- **"Every restriction stays liftable."** The only limit is the parent's switch, in both directions. The override and
  the pause rightly don't touch it.
- **The fold decides nothing** (D9). `SearchFoldTest` pins that `sms/` and `CallRules.kt` never call it. D17's sender
  names stay exact.
- **Contrast.** Recomputed (WCAG 2.x, the 8 % disc composited on #1B1B28):
  - `kid_call_glyph` #7FC28F on the button disc: 6.6:1;
  - peach #F0A884 on the tile: 8.6:1.

  The existing `kid_missed_text`, `kid_core_dim`, `kid_sheet_button` and `kid_emergency_*` tokens exist as named.
- **Disguise (asked).**
  - A kid's picture is set only by this phone's token, for a contact the parent already put in "I telefonboken". It
    is never sent to another phone and never changes a name, a number or any rule.
  - Calls and SMS are judged by number and the parent's switches, so a picture grants nothing.
  - "Mamma's photo on a stranger added later" needs the parent to add that stranger's number, under a name the parent
    types. The kid would be fooling only themselves, and the parent sees the picture beside the contact in the PWA.
  - The one real drift is a contact row the parent later re-points to another person; see L9.

  It doesn't matter beyond L9.

## High

**H1 - The row as specified doesn't fit the Jelly Star, and the missed-call line, the point of the user's question 3, is
cut.**
- The Jelly Star is 320x569 dp (ui-polish.md), so the page's content is 288 dp (Home's mockup width; §4.1 gives 16 dp
  sides).
- The row takes:
  - the tile padding (12 + 8 dp at the sides);
  - the avatar (48 dp) and its gap (~12 dp);
  - two 48 dp targets 4 dp apart (100 dp).
- That leaves **about 100 dp** for the text column. Nunito 800 at 16 sp averages about 9 dp a character, so roughly 11
  characters of name. The missed line at 13 sp 700 fits about 14 characters:
  - "1 tapt anrop i dag 14:02" (24 characters, about 165 dp) shows as "1 tapt anrop i…";
  - "2 tapte anrop i går 09:10 · 1 ulest melding" has no chance.
- At font scale 1.3 and 2.0, `WholeWordTextView` shrinks to 75 % and then ends each word in "…", so "Bestemor" becomes
  "Beste…" at 2.0.
- §4.2's "a 40-character name fits at 1.0" isn't true at these sizes.
- The mockup hides this: its phone is 300 px wide with a 40 px avatar and 34 px buttons, not the spec's 48/48.

Decide the row against 288 dp and the three font scales before the build:
- **Make the missed line short.** The group header already says "Tapte anrop", so the line can be the time and count
  alone ("i dag 14:02 · 2", with TalkBack keeping the full sentence). In "Favoritter"/"Kontakter" the unread line is
  "1 ulest".
- **Let the second line wrap** to two lines.
- **Narrow the buttons.** Use 40 dp discs whose 48 dp targets overlap the tile's padding, with no gap between the two
  targets.
- **Stack at large text.** From font scale 1.3, or whenever the text column would be under about 120 dp, put Ring and
  Melding on their own line under the name.

Add the phone-book row to `WholeWordsTest` at 288 dp and 1.0/1.3/2.0, and redraw the mockup at 320 dp with the spec's
sizes.

**H2 - Every avatar binding would read CE storage, and one of them runs before the first unlock.**
- §6.4 makes every avatar go through `shownPhoto(contact, kidPhotosOn, pending, emergency)` in
  `KidAvatars.bindContact`.
- `pending` is the CE prefs record `kid_photos` (contact id -> file, state).
- `bindContact` is called from `InCallActivity` (`directBootAware="true"`) and from `GlancePanel`.
- Before the first unlock, reading CE `SharedPreferences` throws `IllegalStateException`, which would crash the in-call
  screen on an incoming call after a reboot.
- Even after unlock, a prefs read per bind is a main-thread disk read for each of 50 rows, the card, Home's row and
  Meldinger's rows.
- Today `ContactPhotos.cached` deliberately never touches the disk.

The design should say:
- the pending records live in an in-memory snapshot, loaded on IO after the unlock (with `ContactPhotos`' listener
  firing when it changes), and empty before it;
- `bindContact` never reads prefs or files;
- the pending JPEG is decoded by the same background decoder, under a key that can't collide with a hash, e.g.
  `kid:<file>`.

Add a source check next to `ContactPhotos`' "no disk in `cached`" rule.

## Medium

**M1 - Favourites: what they are pruned against, and when.**
- D4 prunes "to the phone book's ids at every `CallPolicyStore.refresh`". "The phone book" is `phoneBookView`, and
  while calls are off (the parent's switch, `phoneBookView` line 382) it holds **only emergency contacts**. Built as
  worded, every star goes the first time the parent switches calls off for an evening.
- `refresh` also runs before the first unlock and in each call service's `onCreate`. A CE write there throws.
  `ElementRoomStore.prune` is the precedent, and it runs only after the `plan.source != CE` return.

Specify the following:
- Prune only on a CE refresh of a `Managed` state, never on `UnknownFailClosed`.
- Prune against `rules.contacts` ids (every contact on the phone), not the outbound list. A contact the parent takes out
  of "I telefonboken" for a while keeps its star, and the list simply doesn't show it.
- Filter at render with `rules.phoneBook`.
- The card shows the star only for a contact in `rules.phoneBook`. Home's card can open a Home-only contact, which the
  phone book never lists.

`FavouritesTest`: calls off keeps the stars, Unknown keeps them, a removed contact drops.

**M2 - The pending picture's states are cleared by the wrong policy.**
- §6.4 drops the pending file "once the policy names a `kid_photo` for the contact and that file is cached". The server
  re-encodes, so the hash never equals the phone's file.
- Worse, a picture A accepted as `hA`, followed by a new pick B (pending), is wiped when a policy naming `hA` arrives.
- `PENDING_REMOVE` has the mirror problem: after the DELETE, the next policy may still name the old hash, and the old
  picture flashes back.

Fix:
- Keep the accepted answer's `photo` hash in the record (`UPLOADED(hash)`).
- Clear the record only when the policy's `kid_photo` equals that hash and the file is cached.
- Clear a removal only once the policy says `null`.
- A newer pick replaces the record and the file.
- `shownPhoto` must also honour the switch for a pending file: off hides it, as D10 says off hides the kid's pictures.

Add these rows to the contract's `shown` (or to the launcher-only test, N9): "accepted, policy still old", "newer pick
over accepted", "removed, policy still old".

**M3 - The request picture's hand-over has gaps, and it leaves files behind.**
`reconcileContactRequests` (WIP) is pure and **drops** the record in the same pass that sees the number among the
contacts. The design needs to cover the following:
- **The hand-over and the drop must be one step.** Reconcile returns the hand-overs (request id -> contact id) as well
  as the new list. The move to `kid_photos/` happens before the record is written without it.
- **Contacts outside the phone book.** `contactNumbers` is every policy contact, but the upload accepts only "I
  telefonboken" contacts. A contact added text-only gets `not_a_contact` and loses the picture silently. Hand over only
  to a phone-book contact, else delete the picture.
- **`added` has no contact id.** A request the parent added under another number can't be handed over, so delete the
  picture. The same goes for `KNOWN`, `CAPPED`, `NOT_SENT` and the 7-day expiry.
- **Orphans.** `dropShownContactRequests`, expiry, the switch going off and a cancelled form must delete
  `contact_request_photos/` files. Add a sweep of that directory against the records at each reconcile. These are
  pictures of people, so they shouldn't outlive the request.
- **The form needs its picture before the request has an id.** The file is created at "Send". Keep a temp file while
  the form is open (and in the form's saved state, since the camera can cost the launcher its activity), and rename it
  at "Send".
- **The WIP's escape test.** `the contact request only asks` forbids `startActivity` and `Intent(` in
  `ContactRequestForm.kt`. The form's circle must call back into `PhoneBookActivity`, which starts `KidPhotoActivity`,
  or the test must allow exactly that one intent. Say which.

On the user's question 4: "phone only until the contact arrives" is sound for safety. The picture grants nothing, and
the parent sees it on the contact once added. The variant that shows it on the request would help the parent recognise
"Ola", but it needs its own route, retention for declined requests and a purge. The proposal is the cheaper and
calmer choice.

**M4 - The step order lets the kid set pictures before the parent can see, remove or switch them off.**
- Step 5's server sends `kid_photos` (on by default, `device_policy.kid_contact_photos = 1`).
- Step 6's launcher shows "Endre bilde" whenever it is on.
- Step 7, the PWA's switch, pictures and "Fjern", comes last.
- Launcher and server release separately, so a phone with step 6 against a server with 5 but not 7 lets the kid upload
  pictures the parent has no view of and no switch for. D10's "the parent sees and decides" holds only at the end.

Either:
- ship 5 and 7 as one server step (the switch, the row's picture and "Fjern" with the routes); or
- have step 5's policy send `kid_photos: false` until step 7 lands.

The second is simpler to review. Step 6 then needs a server with step 7.

## Low

**L1 - The request button appears again for a name that is already waiting.**
- Requests are not searched (D8), and "Venter på svar" disappears while a query is typed.
- Searching "Lina" while Lina is waiting therefore shows "Fant ingenting" with "Be om å legge til «Lina»". Mockup 1
  and mockup 3 show exactly this pair.
- The form then refuses the number as `NUMBER_KNOWN`.

Match open requests' names too, show them as their waiting rows (no actions), and offer the button only when nothing,
requests included, matches. Show the button only from two letters.

**L2 - The "+" and the button under calls off.**
- §4.5 says calls off shows no "+".
- The WIP's `canAsk()` is `effectiveState() is Managed`, which is true with calls off and under a no-calls rule's
  copy.

Pick one rule (`Managed && callsEnabled` matches §4.5) and use it for both the "+" and "Be om å legge til".

**L3 - Autofill on the search field.**
- `textPersonName` is an autofill hint.
- Every other kid text field sets `importantForAutofill="no"` (`activity_thread`, `activity_event_edit`, the dialogs).
- An autofill service's chip can open its own UI, which is a Google account surface on a GMS phone.

Add it to `view_kid_search.xml`, and pin both it and `NoTextActions.apply` in `KidScreensEscapeTest`. The field must not
take focus when the screen opens, so the keyboard doesn't pop up on every visit. The list must follow the IME inset:
edge to edge, `adjustResize` alone does nothing on API 35+.

**L4 - `KidScreensEscapeTest`'s coverage must be named, not "the new files guarded".**
- Today it guards only `calls/PhoneBookActivity.kt` and `PhoneBookAdapter.kt` in `calls/`. `ContactSheet.kt`, which
  starts other apps, is not guarded.
- Add `ContactSheet.kt`, `PhoneBookList.kt`, the new adapter, `KidPhotoActivity.kt`, `PhotoCropView.kt`, `ui/search/`
  and `ui/pictures/`.
- Move the allowance to `ui/pictures/PicturePick.kt`.
- Add the pin that `ThreadActivity` has (`PicturePick.picked(this, result.data?.data)`, no `Uri.fromFile`) for
  `KidPhotoActivity` too.

**L5 - `PicturePick` is reused "unchanged in behaviour", but its `prepare` is SMS-bound.**
`prepare` encodes for the carrier (`SmsRuntime.judge(..).carrier(subId)`), hands the JPEG to the engine (`putPicture`)
and recycles the pixels. The crop needs the decoded bitmap. State the split:
- a shared `decode(source): Pair<Bitmap?, Problem?>` (read, cap, isolated decode);
- the SMS `prepare` on top of it;
- the contact path: decode, then crop, then `PictureEncoder.forContact`.

Keep the escape test's pins on the moved file.

**L6 - The crop's zoom cap can fall below 1.** `min(3, shortSide / 384)` is under 1 for a picture whose short side is
under 384 px (a small screenshot or a panorama). Make it `max(1, min(3, shortSide / 384))`, and decide whether a picture
under 384 px is refused or simply used at 1x. `PhotoCropTest` gets that row.

**L7 - The pocket-call mitigation is misattributed.**
- §10 counts "the press waiting `tapTimeout`" as a guard.
- That is `PressMotion`, which is visual only: the listener always returns false. The click itself is plain
  `performClick`.
- What stops a click while scrolling is the `RecyclerView` intercepting a touch during a fling.

Keep the device check. If it counts accidental presses, add a real guard: the row's buttons ignore a click within about
300 ms of the list leaving `SCROLL_STATE_SETTLING`.

**L8 - Server details to pin.**
- **Schema.** Make the new table's foreign key `(device_id, contact_id) REFERENCES device_contacts ON DELETE CASCADE`,
  so the "removed with the row" claim is one constraint, not code.
- **Which contacts.** Say whether the policy names `kid_photo` for a contact that has left "I telefonboken" but stays
  on the phone (text-only), and for an emergency number. Proposal: only phone-book, non-emergency contacts, so the PWA's
  "Fjern" and the phone agree.
- **Who is nudged.** The PWA's "Fjern" and the switch nudge the phone (`command_notify`). The upload's nudge of the same
  phone is redundant: it can refresh after a 200.
- **Showing it in the PWA.** The PWA needs its own session-guarded image route for a kid picture (it is not in
  `photo_dir`, so `calls::view_photo` can't serve it). Use `private, no-store`, as the originals do, so a removed
  picture doesn't stay in the browser's immutable cache.
- **Retention while off.** Kept indefinitely, while the PWA still shows them with "Fjern". Say so in the switch's help
  text.

**L9 - A contact re-pointed to someone else keeps the old person's picture.**
- Contact ids are global and stable, and the parent can edit a contact's number ("Trener" from Kari to Per).
- The kid's picture of Kari would then show for Per's calls.

Have the server drop the `device_contact_kid_photos` rows when a contact's number changes. This is the only
"disguise" path that isn't the kid's own choice.

**L10 - i18n: the launcher's en strings are missing.**
- The note gives nb only for the kid screens: the group headers, "Søk", "Fant ingenting", "Be om å legge til «…»",
  "Bilde av …", "Velg bilde", "Ta bilde", "Fjern bildet ditt", "Mamma og pappa kan se bildet.", "Kunne ikke bruke
  bildet", "Bruk bildet", "Dra og knip …", the TalkBack actions, "3 treff" (a plural) and the live region.
- Add a nb/en table, as design 77 §6.9 did.
- `TranslationsTest` belongs in steps 2, 3 and 4 too, not only step 6.
- In English, use “…” rather than «…».

**L11 - The search fold's origin map.**
D9 first runs NFC and `lowercase(Locale.ROOT)` over the whole string, then folds per code point. Both whole-string
steps can change length before the origin is recorded:
- NFC on a decomposed name (a name pasted from macOS);
- "İ" -> "i̇".

The span would then index the NFC'd string, not the shown one, and `setSpan` throws past the end. Build the map from the
shown string itself:
- for each code point: lowercase, NFD, drop the combining marks, then the table;
- the whole-string NFC is redundant then, since both sides pass through NFD;
- clamp the span to the text's length.

`SearchFoldTest` gets a decomposed "Björn" and an "İ" row.

**L12 - `KidPhotoActivity`'s own guards.**
- It finishes when the contact leaves the phone book or becomes an emergency number at a sync, not only on the
  switch.
- It stays out of `FREE_SCREENS`: picking and the camera are screen time.
- Design 78's scope table gets a row for it (night, like `PictureActivity`).

## Nit

- **N1.** The mockups show the search pill with 6 contacts (list) and 6 conversations (Meldinger), under D8's own
  threshold of 8. Show 8 or more rows, or the list without a pill.
- **N2.** "Each rank in the list's normal order" (D8): say which order applies while the groups are dissolved. A-Å is
  simplest, and it is what the mockup shows.
- **N3.** Once the pill shows, keep it while it holds text, even if a sync takes the list under 8.
- **N4.** Opening the request form from "Fant ingenting":
  - Back order: form, then query, then leave.
  - After "Send", clear the query.
  - Hide the pill while the form is up.
- **N5.** Meldinger's rows for a bare number (`Who.Number`) show digits. "Names only" then matches digits on those rows.
  Harmless, but say it.
- **N6.** The row's Melding to Element X or Signal leaves Vibb with no cue for a sighted kid; the card says "Meldingen
  åpnes i …". Consider the app's small glyph on the disc, or accept it.
- **N7.** Per render, compute once:
  - `CallSystem.isEmergencyOutgoing` per contact (a binder call; today twice per contact per render);
  - `defaultSmsPackage`;
  - `ElementRoomStore.lookup` (a prefs read).

  The `Collator` instance should be kept, not made per render. The live region must be a visible view (TalkBack ignores
  INVISIBLE and GONE): use the "Fant ingenting"/count text itself.
- **N8.** RGB_565 halves memory but bands skin gradients on the card's 132 dp circle. An alternative that keeps
  ARGB_8888: decode a row-sized thumbnail (about 150 px, under 0.1 MB) for lists, and the full square only for the card
  and the call screen.
- **N9.** The contract's `shown` rows are the phone's rule alone. The server only re-checks `kid_photo`. They could
  live in a launcher-only file, as `sms_rules.json` does, which keeps the shared file to what crosses the wire.
- **N10.** Further notes:
  - **Default on.** "On by default" is a product choice the user hasn't been asked; consider adding it to §11. The
    parent's "Fjern" isn't sticky (the kid can set a new one at once, within 20 a day). That is fine with the switch as
    the real control, but say it in the help text.
  - **Emergency disagreement.** The phone (platform) and the server (`emergency::is_emergency`) can disagree on an
    emergency number. When the server answers `emergency` the pending picture vanishes without a word. Show "Kunne ikke
    bruke bildet" once.
  - **Step 2's size.** Step 2 is the bulk of the work. It is M+ rather than M: the adapter, the diff, the groups, the
    seen marks, the confirm, the request groups, TalkBack actions, the "+" and the RGB_565 change.
