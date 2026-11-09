# Code QA: design 79 - the kid's phone book as one list (branch `phone-book-list`)

Reviewed: `phone-book-list` at 5386fe86 (six commits on `main` 4697f71d: 6b495cba, a8ad53ce, d3111285, d90383a1,
85742679, 5386fe86), against `docs/design/79-phone-book-list.md` (revision 3, approved with the emergency numbers at the
bottom) and its design QA `docs/review/qa-79-design.md`. Also read: `docs/notes/launcher/calls.md` "The phone book as
one list", `home-and-photos.md` "The kid's own contact pictures", `ui-polish.md` "The phone book's row", the emulator
run (`docs/testing/emulator.md` §6v "design 79"), and the code the diff touches or leans on: `CallPolicyStore`,
`CallRules.phoneBookView`, `MissedCallsRepo`/`MissedCalls.kt`, `ElementMissedCalls.kt`, `HomeActivity` (16c overlays,
missed calls, call card), `InCallActivity`, `MessagesScreens`, `ThreadActivity`'s picker, the manifest, the backup
rules and `share_paths.xml`.

This is a reading review: no Gradle run, no emulator. Every finding says whether it is **confirmed by reading** or
**suspected** (a path I could not rule out but did not run).

## Verdict

**Mergeable after M1-M3** (M3 needs the user's call on the fold's direction). Then L1, L3 and L10 should go in the same
fix round, because they are small and visible to the kid. The other Lows and the Nits can follow.

The security-relevant part is in good shape:
- **Decoding.** The picked or captured bytes are capped at 30 MB and decoded only in the isolated process, at most
  1280 px with EXIF orientation applied. The main process only crops and re-encodes its own JPEG (`Bitmap.compress`,
  no EXIF), at most 512 px and 150 KB.
- **Storage.** Files sit in CE storage only (`filesDir/kid_contact_photos`, `filesDir/contact_request_photos`,
  `cacheDir/request_photo_draft`), under random names (`k`/`d` + 32 hex) or the request's own hex id. Nothing a
  contact name, number or URI says reaches a path.
- **Nothing leaves the phone.** None of these directories is in `share_paths.xml`. Backup and device transfer exclude
  every domain. `ContactRequestBody` is unchanged, so the picture is never uploaded. No log line carries a name,
  number or bitmap.
- **No disk in a binding.** Avatar bindings read only the in-memory snapshot and the decoded cache. The snapshot is
  loaded after the unlock on the store's own thread. The in-call screen before the first unlock never reaches it
  (`callAvatar` gives the silhouette).
- **Pruning.** It runs only after `plan.source == CE` on a `Managed` state, against `rules.contacts` and the number. So
  calls off, `UnknownFailClosed` and unmanaged keep every picture, and a re-pointed contact loses its picture.
- **`KidPhotoActivity`.** It is not exported and not a free screen. It reads the picker's answer only through
  `PicturePick.picked`, and finishes when a sync takes the contact out of the phone book.

Emergency calling is intact in every state I traced:
- calls off;
- a time rule (the phone book is unreachable, as before);
- no readable rules (the 112 row, `EmergencyCall.confirm`);
- a call already live (Ring stays enabled for an emergency number);
- a DIAL `tel:` link.

`contactActions` is the card's logic moved unchanged; the card now also marks its Melding as seen, as §3.2 rule 3 asks.
`PicturePick`'s split leaves the SMS path's behaviour as it was (`ThreadActivity` is its only other caller; there is no
wallpaper caller).

**Counts: Critical 0, High 0, Medium 3, Low 10, Nit 6.**

## Medium

### M1. The "Bilde av …" sheet opened from Home's card survives the lock (design 16c) - confirmed by reading

`calls/ContactSheet.kt:124-133` (the `KidPhotoSheet.show(..)` result is dropped), `ui/HomeActivity.kt:951-968` (Home
tracks only the card), `ui/HomeActivity.kt:298-301` (`showNight`: "nothing of it can be seen or touched; an open
contact sheet closes").

**What happens:**
1. On Home, the kid long-presses Mamma, which opens the card.
2. The kid long-presses the photo, which opens "Bilde av Mamma" (a second `Dialog` owned by `HomeActivity`).
3. The screen goes off, or the lock engages.
4. `showNight()` runs `closeOverlays()`. That dismisses the card (tracked by `trackOverlay`) but not the photo sheet,
   which nothing tracks.

The sheet stays on Home's window over the night ground. The same happens on Home pressed on Home (`closeOverlays()`
at line 659) and at `onDestroy` (a leaked window).

**Why it matters:** 16c's rule, after qa-16c-code #2, is that nothing of Home's content exists while LOCKED. Design 32
relies on Home's snapshot under the lock being plain black. A leftover "Bilde av Mamma" with Velg bilde / Ta bilde is
then on Home's window whenever Home shows before the lock's first frame. Its buttons start `KidPhotoActivity`, which
checks only the time rule, not the PIN lock. I did not reproduce a tap getting through in that window (suspected), but
the sheet staying open is certain.

**Fix:** keep the photo sheet's `Dialog` in `ContactSheet.show` and dismiss it in the card's `OnDismissListener`. Then
Home's existing `closeOverlays()` → card dismiss closes both. Add a line to `KidScreensEscapeTest` or a source check:
the `KidPhotoSheet.show` result is kept and dismissed with the card.

### M2. Every keystroke runs the whole render, with binder calls and prefs decodes on the main thread - confirmed by reading (cost not measured)

`calls/PhoneBookActivity.kt:140-144` (the search callback calls `render()`), `:329` and `:342`
(`CallSystem.isEmergencyOutgoing` per contact: `TelephonyManager.isEmergencyNumber`, a binder call), `:344`
(`ContactRequests.records`: a JSON decode), `:394-406` (`itemsOf`: `defaultSmsPackage`, `usablePackages`,
`ElementRoomStore.lookup` (a prefs read and decode), and `AppDisplay.shownLabel` per row with an app Melding - not
memoised per package). Also `WallpaperGround.apply` and `KidHeader.bind` per keystroke.

**What happens:** with 50 contacts, each character typed costs about 50 `isEmergencyNumber` binder calls, up to 50
label lookups and two prefs decodes, all on the main thread. Then a full diff runs.

Design §3.7 and design QA N7 say: "Computed once per render ... **A keystroke only filters.**" Meldinger does the same
on a smaller scale: `adapter.submit` rebinds every visible row, and `MessagesScreens.bindAvatar` calls
`isEmergencyOutgoing` per bind.

**Why it matters:** the target phone is the Jelly Star. A search box that lags behind the kid's typing is the first
thing a kid notices, and the design named this exact rule.

**Fix:** split `render()` in two:
- `refreshInputs()` - the view, the emergency set, unread, requests, the per-contact `ContactActions` and labels. It
  runs on resume and on each listener (policy, messages, requests, missed calls, wallpaper).
- `filter()` - only `phoneBookRows(..)` over the cached inputs, plus `submitList`. The search callback calls only this.

Memoise `AppDisplay.shownLabel` per package within a refresh.

### M3. The fold is symmetric: typing Å, Ø or Æ finds plain a, o and ae - confirmed by reading (and by the build's own test)

`ui/search/SearchFold.kt:80-89` and `:109` (the query goes through the same fold as the name);
`SearchFoldTest.kt:29` pins it: `searchMatch("Ola", "øl")` is a PREFIX hit.

**What happens:** in a phone book with Anna, Mamma, Pappa and Åse, typing "Å" folds the query to "a".
- **Prefix hits:** "Anna" and "Åse". "Anna" comes first, because nb's A-Å puts Å last within the rank.
- **Inside hits:** every name with an "a": Mamma, Pappa, ...

Typing "Ø" lists Ola, Oskar and every "o" name before Ørjan.

The review brief's rule is "Æ/Ø/Å must not fold to A/O". The design's §4.2 promises only the other direction ("o"
finds "Ørjan", "a" finds "Åse"), and says nothing about a typed "å". The build chose the symmetric reading and pinned
it.

**Why it matters:** a Norwegian kid who types the exact letter gets the wrong people first. Æ, Ø and Å are their own
letters in nb, and the collation already treats them that way.

**Fix (needs the user's yes):** a one-way fold.
- Fold the **name** as now.
- Fold the **query** by lowercase and NFC only, keeping æ, ø, å (and any letter with a diacritic) as typed.
- Match a query char against a name char when they are equal after lowercase, or when the query char is the plain
  letter the name char folds to.
- The highlight map is unchanged.

So "a" finds Åse and Anna, while "å" finds only Å names. Change the test row to `searchMatch("Ola", "øl") == null`, and
add "å" finds "Åse" but not "Anna".

## Low

### L1. A returning visit shows the last visit's list, tappable, and reorders it a moment later - suspected (timing)

`calls/PhoneBookActivity.kt:173-176` (a new visit sets `visitTop = null`) and `:340` (`render` returns before
`submitList` until the first load).

**What happens:** the kid comes back from a call or a thread. The adapter still holds the previous visit's rows: its
top, its red lines, its scroll. About 100-300 ms later the load completes. The rows are re-sorted (last visit's top
now in A-Å) and the list jumps to the top.

There is no item animator, so the views stay where they are and are rebound with other contacts. A Ring tapped in that
window resolves `currentItem()` at click time: the touch can land on Pappa's row and call whoever is bound there after
the rebind. That is the "reorders under the kid's finger" case §3.2 forbids.

**Fix:** at `onStop` (not for a configuration change), `adapter.submitList(emptyList())` and hide the list. A new visit
then starts empty, like the first one, and fills once. The emergency rows wait for the same IO load as on a first open
(fast; `contactMissed` returns at once unless calls are managed).

### L2. Out-of-order missed-call loads can bring back a red line or badge the kid just dealt with - suspected (race)

**Where:**
- `calls/PhoneBookActivity.kt:292-305`: unguarded `launch`es from `onResume`, the call-log observer, the Element X
  listener and the card's `onDone`.
- `ui/HomeActivity.kt:713-722`: Home's `onResume` load and the new seen-listener load (`:461`) are launched about one
  transition apart.

**What happens:** a load that started before a `markSeen` can finish after one that started after it, and `missed =
result` then takes the older answer.

In the phone book: open Pappa's card. `markSeen` runs, then `onDone` → load L2. The call-log observer's L1 is still
running from `MissedCallNotifier.dismiss` marking the log read. If L1 ends last, Pappa's row shows the red line and
badge again until the next load.

On Home: if the `onResume` load ends after the listener's load, Home's badge stays after the visit. That breaks §3.3's
"they agree".

**Fix:** a generation counter in both screens. Each launch takes `++generation`, and a result is applied only if its
generation is still the latest.

### L3. Request picture drafts can outlive the form - confirmed by reading

**Where:**
- `calls/PhoneBookActivity.kt:96-99`: a draft result is ignored when the form is closed, without deleting the file.
- `:147`: the sweep runs only in `onCreate`, and only for drafts older than a day.
- `calls/KidPhotoActivity.kt:122-124`: a draft is always still wanted.

**What happens:**
1. **Calls go off while the kid frames the request's picture.** `render()` closes the form. `KidPhotoActivity` still
   returns a draft, which `draftLauncher` drops on the floor.
2. **The phone book is destroyed with the form open and a draft.** For example, Home pressed with the form open: Home
   is `singleTask` and clears it.
3. **Process death while the kid is in the picker, with calls off by the time the activity is restored.**
   `ContactRequestForm.restore` needs `canAsk()`, so the saved draft is not adopted.

Each of these leaves `cacheDir/request_photo_draft/d….jpg`, a picture of a person. It goes only when a later phone book
`onCreate` runs more than 24 h after it was written. §5.5: "Pictures of people don't outlive the request."

**Fix:**
- In `draftLauncher`, delete the returned file when it isn't taken.
- In `onCreate`, delete every draft except the one the saved state names. With no saved state, delete them all, since
  no form can own one then.
- In `onDestroy` with `isFinishing`, drop the form's draft.
- `KidPhotoActivity` may also finish a draft pick when `ContactRequestForm.canAsk()` turns false.

### L4. `KidPhotoActivity` opens the picker or the camera before its time-rule check - confirmed by reading (reachability narrow)

`calls/KidPhotoActivity.kt:84-94` (`launch()` in `onCreate`) vs `:105-108` (`redirectIfLocked` only in `onResume`).

**What happens:** if the activity is ever started while a time rule without calls is in force, the system photo
picker is started first. `LockActivity` then comes from `onResume`, and the picker stays in the task under it. The
phone book is unreachable under such a rule, and Home is behind the time-rule screen, so the path is narrow. But §5.2
says "it checks the time rule at resume", and the picker is a helper that is never suspended.

**Fix:** in `onCreate`, before `launch()`, `if (MessagesScreens.redirectIfLocked(this)) { finish(); return }`. Or defer
the first `launch()` to the first `onResume` that passes the check. Consider adding `!MessagesScreens.usableNow(..)` to
`kidPhotoOffered`'s inputs so the long-press isn't even offered.

### L5. "Sendt - Mamma og pappa har fått beskjed" before anything was sent - confirmed by reading

`calls/ContactRequestForm.kt:147-160`, `values-nb/strings.xml:96-97`, `values/strings.xml:111-112`.

**What happens:** Send stores a PENDING record and asks for a sync, then shows the confirmation at once. Offline, or
against a server that answers `invalid`, `capped` or `known`, the parents have not been told. A request that never
uploads expires silently after 7 days (`reconcileContactRequests`), so the kid is never told otherwise. The wording
follows design §7, so this is a wording fix, not a design deviation.

**Fix:**
- Change the body to the future tense: "Mamma og pappa får beskjed. Du ser det i telefonboka når %1$s er lagt til."
  / "Mum and Dad will be told. …"
- Optionally make the waiting row say "Ikke sendt ennå" while PENDING. Then the "Venter på svar" line is true only
  once ASKED.

### L6. Every call-policy refresh rebinds every avatar on every screen - confirmed by reading

`calls/KidContactPhotos.kt:110`: `edit()` calls `ContactPhotos.changed()` unconditionally, also when nothing changed
or the change failed.

**What happens:** `afterRefresh` runs on every CE `CallPolicyStore.refresh`. That means every sync, and each call
service's `onCreate`, including the in-call service at an incoming call. Each refresh fires every `ContactPhotos`
listener, so:
- Home's `renderCallParts()` runs, which does `removeAllViews` on the contacts row and rebuilds the grid;
- the phone book rebinds all avatars;
- an open card rebinds.

**Fix:** call `ContactPhotos.changed()` only when the published snapshot actually changed (`after != before` and the
write succeeded, or the first load).

### L7. The picture cap counts pictures the phone book no longer shows, and says so only after the framing - confirmed by reading

`calls/KidPhotos.kt:74-75`, `calls/KidPhotoActivity.kt:209-210`.

**What happens:** `kidPhotoMayAdd` allows `records.size < min(phoneBook.size, 100)`, but §5.4 keeps the records of
contacts that left "I telefonboken" and stayed on the phone. Example:
1. The phone book has three contacts, each with a kid picture.
2. The parent moves Mamma to text-only and adds Bestemor.
3. The kid picks a picture for Bestemor, frames it, taps "Bruk bildet" and gets only "Kunne ikke bruke bildet". This
   is under the phone book's size.

**Fix:** cap against `min(rules.contacts.size, KID_PHOTOS_MAX)`, every contact on the phone, which is what the prune
keeps. Check `kidPhotoMayAdd` before opening the picker.

### L8. A kid picture that can't be decoded hides the parent's photo - suspected

`calls/KidPhotos.kt:48-53`, `calls/ContactPhotos.kt:82`.

**What happens:** `shownPhoto` returns `Kid(file)` whenever a valid record exists. If that file is missing or corrupt,
`cachedKid` fails and the key goes to `failed` for good, so the avatar shows the initial. This can follow a sweep after
an unreadable prefs read, or `copyThenDelete` half-done. The parent's photo would have been the better fallback (§5.4:
"else the parent's").

**Fix:** in `cachedShown`, when the `kid:` key is in `failed`, fall back to the parent's hash. Alternatively,
`KidContactPhotos` drops a record whose file is gone, at load.

### L9. A request's answer can be marked "shown" while the list is hidden - confirmed by reading (narrow)

`calls/PhoneBookActivity.kt:365`: `answersShown()` runs whenever the computed rows hold an answer, including while
the request form is open and the list is `GONE`.

**What happens:** an answer that arrives at a sync while the form is up is marked shown. If the kid then leaves from the
form or "Sendt" (Home, not Ferdig), `left()` drops it unseen.

**Fix:** mark answers shown only when `binding.phoneBookList` is visible, the activity is resumed and the answer row
was laid out. `noteSightings` already has the visible rows, so the check can sit there.

### L10. (b) On Android 16, Back with the keyboard up clears the search - confirmed by the builder's note; I would fix it

`calls/PhoneBookActivity.kt:64-72`, `ui/messages/MessagesActivity.kt` (`back`).

**What happens:** a kid who types "Ma" and presses Back to see the hits loses the query and the hits in one press.
That is not the familiar Android behaviour ("Back closes the keyboard"). The design's order "Back clears the search"
was written for the keyboard-down case.

**Fix:** in both callbacks, if `ViewCompat.getRootWindowInsets(root)?.isVisible(WindowInsetsCompat.Type.ime())`, only
hide the keyboard and return. The next Back clears, and the one after leaves.

## Nit

- **N1.** `res/xml/share_paths.xml:4` still names `ui.messages.PicturePick` (now `ui.pictures`).
- **N2.** Design 78's scope table has no "out (night)" row for `KidPhotoActivity` and the "Bilde av …" sheet. §5.2
  and design QA L12 ask for one when either lands.
- **N3.** The search's text survives leaving the phone book for a call or a thread and coming back, because the
  activity isn't destroyed. §4.1 says "Kept across a rotation only". Either clear the query at a non-config `onStop`,
  or keep it (arguably nicer after a call) and correct the sentence.
- **N4.** The first avatar binding after boot shows the parent's photo, then flips to the kid's once
  `KidContactPhotos` has loaded. That includes Home and an in-call screen right after unlock. Start
  `KidContactPhotos.ensureLoaded` from the unlock path (`Application`'s CE refresh).
- **N5.** `calls/ContactRequests.kt:331`: a leftover one-line KDoc ("The form's "Send": …") sits above `add`'s new
  KDoc.
- **N6.** `PhoneBookActivity.onStop` calls `markSeen` once per seen contact, and each call posts Home's seen listener.
  The result is N missed-call reloads on Home for one visit. Post once per batch (a `markSeen(context, Map)` overload).

## The design QA's findings, checked against the code

| Finding | Holds? |
|---|---|
| H1 the row at 288 dp | Holds by reading: `rowLayout` (inline below font scale 1.3 with 120 dp, else stacked), the short line wraps to 2 lines, `WholeWordsTest` has the row at 1.0/1.3/2.0 nb+en (not run here). |
| H2 no CE read in a binding | Holds: `bindContact` reads `KidContactPhotos.snapshot` and the decoded cache only; load on the store's IO thread after `isUserUnlocked`; the in-call screen before unlock never gets there; the source check is in `KidPhotosTest`. |
| M1 prune rule | Holds: only after `plan.source != CE` return, `Managed` only, against `rules.contacts` and the number; calls off/Unknown/unmanaged keep. |
| M2, M4, L8, N9 | Moot (no server part), as the design says. |
| M3 the request picture's hand-over | Holds in shape: `Reconciled.handOvers`, `adopt` before the list is written, only to a phone-book contact, other outcomes swept, a draft before the id, the form calls back (`onPicture`). Gaps: drafts can be orphaned (L3); the request-photo sweep runs only when `update` changes something (a crash between `moveDraft` and the write leaves a file until the next change - harmless, noted). |
| L1 the button for a name already waiting | Holds: waiting requests are hits; the button only with nothing matching and two letters. |
| L2 `canAsk = Managed && callsEnabled` | Holds, for the "+", "Be om å legge til", the search and the request rows. |
| L3 autofill, focus, IME inset | Holds: `importantForAutofill="no"`, `text|textNoSuggestions`, `NoTextActions`, the container takes first focus, `KidInsets.apply(.., ime = true)`. |
| L4 escape test, named | Holds: every named file is scanned; the picker allowance moved to `ui/pictures/PicturePick.kt`; `KidPhotoActivity`'s `PicturePick.picked(this, result.data?.data)` pinned; the form free of intents. |
| L5 `PicturePick` split | Holds: `decode` shared, `prepare` unchanged on top. |
| L6 zoom cap | Holds: `max(1, min(3, short/384))`, tested. |
| L7 pocket-call guard | As designed: no code guard, the device check is still open (not device-tested). |
| L9 re-pointed contact | Holds: the prune and `shownPhoto` both compare the number. |
| L10 en strings | Holds: the key sets of `values/` and `values-nb/` match (only the pre-existing `glance_separator` differs); English uses “…”. |
| L11 the fold's origin map | Holds: per code point, the span on the shown string, clamped; decomposed "Björn" and "İ" tested. |
| L12 `KidPhotoActivity`'s guards | Mostly: the policy listener finishes it, not a free screen, the picked answer pinned. But the time rule is checked after the picker is launched (L4), and design 78's table row is missing (N2). |
| N1 mockups with 8+ rows | Not checked (mockups not reviewed). |
| N2 order while searching | Holds: rank, then A-Å. |
| N3 sticky pill | Holds (`query.isNotEmpty() ||` rows ≥ 8). |
| N4 the form over a search | Holds: the pill hides, Back order form → search → leave, the query cleared after Send. |
| N5 digits in Meldinger | Holds and is said. |
| N6 no cue for a Melding leaving Vibb | Accepted; TalkBack's action says "… i Element X". |
| N7 per-render work, a visible live region | Live region holds (the count line and "Fant ingenting", both visible). Per-render work: computed once per render, but a keystroke is a render (M2). |
| N8 list-sized thumbnails | Holds: `<key>@160`, sampled then scaled, ARGB_8888. |
| N10 | Moot; step 2's size as said. |

## The five open points

- **(a) Request rows hidden while calls are off.** Keep it. With calls off, the phone book is the emergency numbers and
  an info line. Waiting rows would be noise the kid can't act on, and they would contradict the hidden "+". An answer
  waits for a visit where it can be read. The only loss is an answer that expires (7 days) while calls stay off, which
  is acceptable for a request.
- **(b) Android 16 Back clears the query with the keyboard up.** Fix it (L10). One Back should only close the keyboard,
  as everywhere else on Android. Clearing what the kid typed on the press they use to see the hits throws away their
  work.
- **(c) An incoming call's screen ends the visit.** Keep it. Rows the kid looked at for a full second before the call
  came are seen by the user's rule ("seen once the phone book has shown it"). A newer missed call still counts after
  the mark (`upToMs`), and the return starts a fresh visit at the top. Rows shown for under a second don't count, so a
  call that arrives the moment the phone book opens marks nothing.
- **(d) No hint for the long-press on the card's photo.** Ship without it and run §9's device check with the kids. The
  design's fallback, a one-time line "Hold inne bildet for å bytte det", is cheap to add if they don't find it. The
  request form's circle already has a tap and a caption, so the feature stays reachable.
- **(e) The emergency avatar keeps the 3 dp ring.** Keep it. It matches the card, Home and the glance, and 1 dp on a
  44 dp avatar isn't worth a second ring style. Correct §3.5's "2 dp at 44 dp" in the design's As-built note.

## Not checked

- **Not run.** No Gradle run: the new tests (`PhoneBookListTest`, `KidPhotosTest`, `PhotoCropTest`, `SearchFoldTest`,
  `KidSearchTest`, `WholeWordsTest`'s row, `KidPaletteTest`, `KidScreensEscapeTest`) were read, not run. No emulator
  or device: the Jelly Star fling count, TalkBack's custom actions, an incoming call before the first unlock, and M2's
  real cost per keystroke are unmeasured.
- **Not reviewed.** The mockups, the layouts' pixel values against §3.4, and the colour contrast figures.
- **Not re-reviewed (merged with their own QA).** `checkContactRequest` against the server's vectors (the shared
  `contact_request.json` check and the 7-day/cap rules are design 77 §14's, `qa-contact-request.md`); this branch only
  wires them. Also `MmsParser.decodeImage` and the isolated service (design 77 step 3b, `qa-sms-mms.md`).
- **The camera's write grant.** It is never revoked after the capture, the same as `ThreadActivity`'s (pre-existing,
  design 77 D11). The file is deleted once read.
