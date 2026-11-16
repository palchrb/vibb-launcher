# 106 - Two badges on the contacts, and "Siste" in the phone book

Round `a5-b2`, 2026-11-16. Launcher only; no server change. **Status: design.** PLAN: "Missed calls and messages on
Home's contacts, and a call log in the phone book" (approved 2026-11-16, mockups A5 and B2 in the board
`contact-badges/board.html`). Every decision below the line "Decided by the user" stands; this note settles the rest.
The Q list at the end holds the choices with a cost the kid or the parent can see.

**Decided by the user.**
- **A5.** Missed calls get the top-right pill: rust, the phone-x glyph, their own count. Unread messages get a teal pill
  with the chat glyph at the bottom right, where the green call badge was. The green call badge goes. The same two
  pills are used on Home, in the phone book's rows and on the contact sheet. Sent SMS never count anywhere.
- **B2.** A "Kontakter | Siste" switch at the top of the phone book. Siste is its own list, grouped by day, with
  incoming, outgoing and missed calls; missed calls are red, and consecutive calls from one contact are merged. Siste
  has a badge for unseen missed calls. A row opens the contact sheet, never a call. The book always opens on
  Kontakter, also when it comes back into focus. Only Kontakter has search. Blocked calls are never shown. Element X
  calls are shown.

## 1. Today (checked against the code)

- **One pill, a sum.** `ContactBadge(missed, unread)` (`HomeModel.kt:159`) has `count = missed + unread` and shows the
  missed-call glyph when `missed > 0`. `KidAvatars.bindContactBadge` draws that one pill on Home
  (`HomeActivity.kt:1007`), on the phone book's row (`PhoneBookAdapter.kt:191`, count only, no glyph) and on the
  sheet (`ContactSheet.kt:68`). So one missed call and one SMS read as "phone-x 2". The sheet's lines
  (`KidAvatars.sheetLines`) already say both in words, and so do TalkBack (`contactDescription`) and the row's
  sentence.
- **Sent SMS don't count today either.** The unread count is `direction = 'IN' AND is_read = 0`
  (`SqliteMessageStore.unread`), through `unreadByNumber` and `SmsHome.unreadFrom`. The user's question came from the
  sum next to the phone glyph. A5 removes the sum, and the rule is pinned in §6.
- **The green call badge** (`contact_call_badge`, `bindCallBadge`, `kid_call_badge`) is drawn on Home only. Tapping
  the avatar opens the sheet, not a call.
- **Missed calls.** `MissedCallsRepo` reads 14 days of the call log (`MISSED_CALL_LOG_WINDOW_MS`, at most 500 rows,
  only while `canReadCallLog`: READ_CALL_LOG, unlocked, calls managed). `unseenMissedCalls` counts a contact's missed
  calls after the later of its seen mark (`missed_calls_seen`) and any outgoing or answered call with it.
  Missed Element X calls come from `ElementMissedCallStore`. That store is memory only and holds a call while Element
  X's notification has the `missed_call` category (round L5). `contactMissed` joins the two sources; Home, the
  phone book and the sheet all read that one map.
- **What clears what.**
  - The avatar's missed count goes when the contact is seen: the sheet opens, Ring or Melding is tapped, or a top row
    in the phone book was on screen for 1 s and the kid then leaves (`TopSightings`, design 79 §3.2).
  - Our missed-call notification and the glance's missed-call row go when the phone book resumes, when any sheet
    opens, or on a tap or swipe. Each of these marks the log rows read (`new = 0`) and calls
    `cancelMissedCallsNotification`.
- **No call log list exists.** Answered and outgoing calls are shown nowhere.

## 2. A5: two pills

### 2.1 The model

`ContactBadge` keeps its two numbers and loses `count` and `callGlyph`:
```kotlin
data class ContactBadge(val missed: Int, val unread: Int) {
    val missedText: String? get() = badgeText(missed)   // null hides the rust pill
    val unreadText: String? get() = badgeText(unread)   // null hides the teal pill
}
```
`missed` is `ContactMissed.count`, which counts phone calls and Element X calls together, as today. `unread` is
`SmsHome.unreadFrom`, unchanged. Nothing adds the two numbers anymore.
`KidAvatars.bindContactBadge(missedView, unreadView, badge, size)` binds both pills. `size` is one of HOME, ROW or
SHEET, and it sets the glyph's bounds in code: the drawables are 24 dp, which is why the row dropped its glyph in
design 79.

### 2.2 The look

| | Home (76/64 dp avatar) | Phone book row (44 dp) | Sheet (132 dp) |
|---|---|---|---|
| Pill height, text | 24 dp, 14 sp (`KidBadge`) | 20 dp, 12 sp (`KidPageBadge`) | 30 dp, 15 sp |
| Glyph | 15 dp | 12 dp | 18 dp |
| Rust pill (missed) | `top\|end`, where it is today | `top\|end`, translation (10, -6) dp as today | `top\|end`, 6 dp in |
| Teal pill (unread) | `bottom\|end`, marginEnd 2 dp (the green badge's place) | `bottom\|end`, translation (10, 6) dp | `bottom\|end`, today's place |
| Ring | `kid_ground` | `kid_page_ground` | `kid_sheet` |

- **Rust** stays `kid_badge` #B4532C with white text (4.98:1). The glyph stays `ic_kid_missed_call`.
- **Teal** is a new `kid_message_badge`, equal to `kid_messages_tile` #347470 (white 5.4:1), the Meldinger tile's
  teal. Its glyph is a new `ic_kid_badge_message`: `ic_kid_message`'s bubble path in white, with a 3-unit stroke like
  the missed-call glyph's.
- **New drawables:** `bg_kid_message_badge`, `bg_kid_page_message_badge` and `bg_kid_message_badge_sheet`. They are
  the rust drawables' shapes with the teal fill.
- **Fit.** Neither pill grows outward past today's edges: each grows inward over the avatar. In a row, the pills span
  y -6 to 14 dp and 30 to 50 dp of the 44 dp avatar, so they never touch, and both stay inside the 64 dp row. With
  "99+" and the glyph, a row's pill is about 43 dp wide. That is as wide as the avatar, but it covers only the
  avatar's top or bottom edge.
- **Gone:** `contact_call_badge`, `bindCallBadge`, `bg_kid_call_badge`, `kid_call_badge`, the call-badge sizing in
  `KidAvatars.sizeContact` and its elevation lift in `PressFeedback.contact`. The teal pill takes the lift instead.
  `contactRoomDp` stays the same, because the teal pill's end edge is where the green badge's was.
- **TalkBack is unchanged.** It already reads "Pappa, 1 tapt anrop, 2 uleste meldinger"; the pills stay
  `importantForAccessibility="no"`.

## 3. B2: Siste

### 3.1 The data: one read, two views

`MissedCallsRepo.load(context, state)` replaces the phone book's `contactMissed` call. It does one `queryLog` and
returns `CallsSnapshot(missed: Map<String, ContactMissed>, recent: List<RecentDay>)`. Home keeps calling
`contactMissed`, and both go through the same query.
- **The query** gains `TYPE IN (INCOMING, OUTGOING, MISSED, ANSWERED_EXTERNALLY)`. Blocked spam from strangers can then
  no longer use up the 500 rows. No current reader uses the other types: `LoggedCallType.OTHER` never counted.
- **Permissions: nothing new.** READ_CALL_LOG and WRITE_CALL_LOG are self-granted by the Device Owner while calls are
  managed, and the dialer role grants them too (`docs/notes/launcher/calls.md`, "Permissions/SMS"). Siste reads only
  under `canReadCallLog`, the badges' own condition. When the log can't be read (`callLogReadable` false, which the
  server already warns about), Siste shows the Element X rows and otherwise its empty line.
- **Unmanaged calls, fail-closed and calls off.** There is no switch. These cases have no contacts to show, or only the
  emergency numbers: the phone book says "calls are off", or shows the one 112 row, as today. The call-log `VIEW` still
  goes to the system dialer while calls are unmanaged (`entryRoute`, unchanged).

### 3.2 Which calls show (pure `calls/RecentCalls.kt`)

A row is `RecentRow(contact, kind, source, count, atMs, unseen)`. `kind` is MISSED, IN or OUT. `source` is PHONE or
ELEMENT_X. `atMs` is the newest call's time.
- **Only contacts in the phone book** (`phoneBookView(effectiveState())`), matched as the badges match them:
  `PhoneNumbers.normalize(raw, defaultCc)` must equal `contact.number`. That excludes:
  - every non-contact: strangers screened out, a callback-window call from a stranger, and a 112 that isn't a contact;
  - a contact that has left the rules;
  - an inbound-only contact, who has no sheet to open (`phoneBook` lists only outbound contacts).
- **Kinds.** `INCOMING_TYPE` and `ANSWERED_EXTERNALLY_TYPE` show as IN, `OUTGOING_TYPE` as OUT, `MISSED_TYPE` as
  MISSED. Every other type is left out:
  - BLOCKED: our screening's rejections. The user decided blocked calls are never shown.
  - REJECTED: this is both the kid's own decline and `KidInCallService`'s backstop rejection of a caller the rules
    don't allow. The two can't be told apart afterwards, so both stay out (Q6).
  - VOICEMAIL and anything else.
- **Element X.** One row per record in §3.3, MISSED only, labelled with the app's shown name (Q2).
- **Merging.** After filtering, rows are sorted newest first. Adjacent rows with the same contact, kind, source and
  local day merge into one: `count` is the sum and `atMs` the newest. A merge never crosses a day heading or a
  different kind.
- **The window.** 14 days, the badges' own window (`MISSED_CALL_LOG_WINDOW_MS`), so every badge has its row (Q1).
- **`unseen`.** For a phone row, the row holds one of `unseenMissedCalls(...)[number]` (the badge's own rows). For an
  Element X row, its record is still open and newer than the seen mark (§3.3).
- **Days.** The group key is the local day (`ZoneId.systemDefault()`). Each group gets a label:
  - today: "I dag";
  - yesterday: "I går";
  - two to six days ago: the weekday ("Lørdag");
  - earlier: the weekday and the date (`getBestDateTimePattern(locale, "EEEEdMMM")`, capitalised).

  A row shows its time with `ClockFormat.formatTime` (P12), on every day.

### 3.3 Element X: a small record of our own

The launcher learns only **missed** Element X calls: a DM room's notification has the `missed_call` category, and the
contact comes from the learned room or `sender_person`. It never learns who an answered or outgoing Element X call was
with. Element X's ring has no room on it, and design 17's guard (`VoipCallGuardTest`) forbids reading the call
notification's person or title. So Siste shows missed Element X calls only (Q2).

`ElementMissedCallStore` forgets a call once the kid opens the room in Element X. To keep the row in Siste, the store
writes a record (Q3):
- **What is stored:** CE prefs `element_call_log`, versioned `v1`. Each record holds the contact's number (resolved
  through the rules when it is written, as `elementMissedSummaries` does), `atMs` (the call's `sinceMs`), a truncated
  SHA-256 of the notification key (so no room ID is stored) and `open`.
- **When records change** (pure `elementCallLogAfter(records, before, after, numberOf, nowMs)`, called from the store's
  `set`):
  - A key that is new to the store adds an open record, unless an open record with the same hash exists already. That
    case is the connect sweep after a process restart, and it is the same call.
  - A key that leaves the store, or whose contact changes, closes its record.
  - The connect sweep closes every open record whose key isn't active.
  - `cleared()` (the listener was unbound) closes nothing, because the next sweep decides.
- **Limits:**
  - Records are pruned at every write: none older than 14 days, at most 50.
  - At each `CallPolicyStore.refresh` (as `ElementRoomStore`), a record whose contact is no longer a phone-book Element
    contact is dropped. All records are dropped when calls are unmanaged.
  - Records stay in CE storage: never in DE storage, never logged, never sent. `toString` names neither the number nor
    the hash.
- **The known limit.** Suppose the process is dead while the kid opens the room in Element X and a second call is
  missed in the same room. The sweep then sees an open record with the same hash, so Siste gets one row, not two. The
  badge is unaffected: it reads the live store, as today.
- **No double rows.** If a future Element X logs its calls to the call log (a self-managed account with
  `EXTRA_LOG_SELF_MANAGED_CALLS`), those rows carry a non-phone address. They match no contact's number and can't
  appear twice.

### 3.4 Seen and unseen: one source for every surface

**The count.** The Siste badge is the sum of `ContactMissed.count` over the contacts in the view. The rust pills on
Home, the rows and the sheet use that same map, so the Siste badge always equals the sum of the rust pills in the
book.

**What clears the avatar's rust pill and the Siste badge** (both are cleared by the same per-contact seen mark):
- The sheet opens (from Kontakter, Siste, Home or the notification), or the kid taps Ring or Melding. As today.
- A red top row in Kontakter was on screen for 1 s, and the kid leaves. As today.
- **New:** a red, unseen row in Siste was on screen for 1 s (at least half of the row) while Siste was shown and the
  book was resumed and focused. Siste has its own `TopSightings` instance, keyed by contact. Each contact's mark goes
  up to the newest call its row showed (`seenMarkAfter` only moves forward). The marks are written when the kid
  leaves Siste: switching to Kontakter, or the visit's end (`onStop`, not a rotation). Opening Siste alone marks
  nothing (Q4).

A Siste row that has been seen stays red: Siste is history. The Siste tab's badge shows only while Kontakter is
selected (as in the mockup). After the switch back, it shows what is still unseen.

**What clears the notification.** As today, the phone book's resume clears it, on whichever tab. The notification
says "something happened, look"; opening the book is that look. It never shows a call that the badges don't (design
12's content rule, unchanged).

**Kontakter keeps its top.** Contacts with an unseen missed call still lead the list (design 79 §3.1). The book opens
on Kontakter, so this is where the kid lands. A contact that Siste marked seen keeps its place for the visit and loses
its red line.

### 3.5 The switch, the tab rule and Back

- **The switch** sits under the header, above the search pill. It shows only while calls are managed and on and the
  request form is closed: `canAsk`, the condition that already gates the "+" and the search.
  - **Shape:** 44 dp tall, the search pill's height. The track is `kid_page_tile`, fully rounded, 4 dp padding. In
    Lys the track also gets a `kid_page_edge` hairline, because white on the paper is 1.11:1.
  - **Halves:** two equal halves, each taking taps over the whole track height. The chosen half is filled
    `kid_page_accent_fill` with `kid_on_accent` text (9.9:1 in Mørk, 7.6:1 in Lys). The other half has no fill and
    `kid_page_ink_dim` text. Nunito 800, 16 sp. Labels wrap with the font scale; no ellipsis.
  - **Built as** P9's control (`docs/notes/launcher/ui-polish.md`): two `RadioButton`s in a `RadioGroup`, so TalkBack
    reads "Kontakter, valgt, 1 av 2".
  - **The Siste badge** is drawn inline after the word: a `ReplacementSpan` with a 20 dp rust pill and 12 sp white
    text, so the label wraps as one piece. The Siste button's description carries the count ("Siste, 2 tapte anrop").
- **Always Kontakter.** Every new visit starts on Kontakter, using design 79's visit boundary: `onStart` without
  `visitRestored`. That covers Home's tile, Recents, coming back from another app, from a call's screen or from the
  kid's picture screen (Q5), the missed-call notification and the call-log `VIEW` (Q7), and the time-rule screen's
  row. Only a rotation keeps Siste (saved state).
- **Search only in Kontakter.** On Siste the search pill and the "+" hide. Switching to Siste clears the query and
  closes the keyboard.
- **Back.** It goes through `phoneBookBack` in order: the keyboard, then the form, then the search's text, then
  **Siste back to Kontakter**, then leave (Q8). Meldinger already works this way ("Ny melding" back to the list). No
  gesture is removed.
- **The lists.** Siste is a second `RecyclerView` (`phone_book_recent`), shown instead of the contacts list. It starts
  at its top every time it is shown. Kontakter keeps its scroll within a visit.
- **Live updates.** The call-log observer, the Element X store's listener and the record listener each run the one
  `load`. That updates the rows, both lists and the badge. Only the latest load is applied (`LatestOnly`).

### 3.6 The look of Siste (night and light; the board's B2)

The page tokens are shared with the phone book's rows, so Mørk and Lys follow from `colors_kid_page.xml`, with no
literals.
- **Day label:** 13 sp, Nunito 800, `kid_page_ink_dim`, 4 dp in from the start, 6 dp above its card.
- **Day card:** `bg_day_event` (the row's tile, 18 dp corners), with its rows separated by 1 dp `kid_page_divider`
  lines. Cards are 10 dp apart.
- **Row** (`item_recent_call.xml`): min 56 dp; padding start 10, end 16, top and bottom 8.
  - A 36 dp avatar (`KidAvatars.bindContact`, `thumb = true`; an emergency contact keeps its red ring). No pills on
    Siste's avatars, since the row is the event.
  - The name: 16 sp, Nunito 800, `kid_page_ink`, never cut between letters (`WholeWordTextView`).
  - Under the name, a 13 dp glyph and a word, 13 sp, Nunito 700:
    - missed: `ic_kid_missed_call`, "Tapt anrop" / "2 tapte anrop", in `kid_page_alert` (#FF8A8A Mørk, #B3261E Lys);
    - incoming: a new `ic_kid_call_in` (an arrow down-left), "Ringte deg", in `kid_page_dim`;
    - outgoing: a new `ic_kid_call_out` (an arrow up-right), "Du ringte", in `kid_page_dim`;
    - a merged incoming or outgoing row adds " (2)";
    - an Element X row says "Tapt anrop i Element X" (the shown label).
  - The time at the end: 13 sp, Nunito 700, `kid_page_dim`.
  - The press is the tile's `kid_page_ripple`.
  - From font scale 1.3 the time moves under the word, as the rows' `rowLayout` rule does.
- **TalkBack:** a row is one node with role button: "Mamma, 2 tapte anrop, i dag 14:02". Tapping it opens the sheet.
- **Empty:** "Ingen anrop de siste 14 dagene", in `kid_page_ink_dim` like the info line.
- **A tap** opens `ContactSheet.show` for the row's contact with `missed[number]`, exactly as a Kontakter row does. The
  sheet is night in both looks. A row never calls.
- **An Element X row opens the same sheet.** It gets no "Ring via Element" button and no `matrix:...?action=call`
  link (the user, 2026-11-16). Those wait until Element X accepts a call intent. The sheet's Ring is the phone call
  and its Melding the contact's message app, as today.

### 3.7 Time rules

Nothing new. The book redirects to the time-rule screen unless the lock in force allows calls (`redirectIfLocked`,
`phoneBookUsableUnder`). It watches the lock while it is in front (`LockWatch`). While usable, Siste is usable too. A
rule that withdraws calls brings the time-rule screen back from either tab. When the book returns it starts a new
visit, so it opens on Kontakter. Under calls off, Kontakter shows only the emergency numbers and there is no switch.

## 4. Server

No change. The policy, the status report, the contract files and the PWA stay as they are; `callLogReadable` is
already reported. Siste and the badges are drawn from what the phone already has.

## 5. Android 14-17

- **The call log.** The `CallLog.Calls` columns and types used here (NUMBER, TYPE, DATE, `_ID`, NEW, IS_READ;
  INCOMING, OUTGOING, MISSED, ANSWERED_EXTERNALLY) are unchanged from API 34 through 37. Reading still needs
  READ_CALL_LOG, a hard-restricted permission that both our dialer role and the Device Owner's grant give. The
  missed-call broadcast and its protection are design 12's, unchanged.
- **VoIP rows.** Self-managed apps may log their calls to the call log (API 30+). Their non-phone addresses never
  match a contact (§3.3).
- **The badges are our own views.** They are not launcher notification dots, so their behaviour doesn't change with
  the version.
- **Android 15's redaction** of sensitive notification content applies only to text with an OTP. The Element X reader
  reads the category, the tag and the person keys, never text.
- **Edge to edge** (enforced at target 36) is already handled by the page (`KidInsets`).
- **Checked on:** Android 14 (compat34) and Android 16 (jelly36) at the emulator gate. Android 15 and 17 are checked
  on paper only, because the Android 17 emulator aborts on this VM (PLAN, "how to test Android 17"). No API above 34
  is used.

## 6. Strings (nb / en)

| Key | nb | en |
|---|---|---|
| `phone_book_tabs` (the group's name) | Velg liste | Choose list |
| `phone_book_tab_contacts` | Kontakter | Contacts |
| `phone_book_tab_recent` | Siste | Recent |
| `phone_book_recent_badge` (plural) | Siste, %d tapt anrop / Siste, %d tapte anrop | Recent, %d missed call / Recent, %d missed calls |
| `recent_today` / `recent_yesterday` | I dag / I går | Today / Yesterday |
| `recent_missed` (plural) | Tapt anrop / %d tapte anrop | Missed call / %d missed calls |
| `recent_missed_in_app` (plural) | Tapt anrop i %2$s / %1$d tapte anrop i %2$s | Missed call in %2$s / %1$d missed calls in %2$s |
| `recent_in` (plural) | Ringte deg / Ringte deg (%d) | Called you / Called you (%d) |
| `recent_out` (plural) | Du ringte / Du ringte (%d) | You called / You called (%d) |
| `recent_row` (TalkBack) | %1$s, %2$s, %3$s | %1$s, %2$s, %3$s |
| `recent_empty` | Ingen anrop de siste 14 dagene | No calls in the last 14 days |

The weekday and the date come from the locale. `contact_missed`, `contact_unread` and `missed_calls_in_app_at` are
reused.

## 7. Tests (lean)

- **`RecentCallsTest`** (new, pure). One table of log rows and records against a phone book of Emma, Jack, Lily,
  Mamma, Pappa and Ambulanse. It checks:
  - which rows show: non-contacts, BLOCKED, REJECTED and VOICEMAIL rows out; an inbound-only contact out; emergency
    contacts in;
  - the kinds;
  - the merge: adjacent rows only, the same kind and source, never across a day;
  - the day groups, in a fixed zone, with today, yesterday, a weekday and a date;
  - the 14-day cut;
  - `unseen` against `unseenMissedCalls`;
  - that the Siste badge equals the sum of `contactMissed` counts.
- **`ElementCallLogTest`** (new, pure): a new key records; a sweep with an open record doesn't record again; removal,
  a flip back to `message` and a changed contact each close; the 14-day and 50-record prune; dropped when calls are
  unmanaged or the contact leaves; `toString` names nothing.
- **`PhoneBookListTest`** (extended):
  - the tab at a visit's start is always Kontakter, and a rotation keeps it;
  - `phoneBookBack` with Siste;
  - the switch shows only with `canAsk` and no form;
  - the search hides and clears on Siste;
  - Siste's sightings mark up to the row's newest call.
- **`HomeModelTest`** (extended): `ContactBadge`'s two texts are independent; one missed call and three messages give
  "1" and "3", never "4".
- **Existing tests updated:**
  - `KidPaletteTest`: drop `kid_call_badge`; add the teal pill's white at 4.5:1 or more, and the switch's pairs in both
    looks.
  - `PageTokensScanTest`: the new layouts use page tokens only.
  - the tests that name `contact_call_badge`.

## 8. The emulator gate (jelly36, then compat34; `docs/testing/emulator.md` §6aw)

1. **Setup.** Seed the rules: Mamma (Home, Element X MXID), Pappa (Home), Emma, Jack, Lily, Ambulanse 113.
   `adb emu gsm call` Pappa, then `gsm cancel`. Expect:
   - Home: Pappa has the rust pill "1" at the top right with the glyph, and no green badge;
   - our notification shows.
2. **SMS.** `adb emu sms send` from Mamma: the teal pill "1" appears at Mamma's bottom right, and the rust pill doesn't
   change. The kid sends Pappa an SMS: nothing changes.
3. **Opening the book.** Open the phone book from Home's tile:
   - it shows Kontakter, with the Siste badge "1";
   - each row has its two pills;
   - the notification is gone.
4. **Making calls for Siste.** Ring Emma from her row. Answer a call from Mamma. Have Pappa call twice and cancel both.
   Have an unknown number call (it is blocked).
5. **Siste's contents.** Open Siste. Under "I dag":
   - Pappa "2 tapte anrop" in red, as one row;
   - Mamma "Ringte deg";
   - Emma "Du ringte";
   - no row for the unknown number.

   Take screenshots in Mørk and Lys, at font scale 1.0 and 2.0. Dump the TalkBack sentences (`uiautomator dump`).
6. **Seen.** Stay 1 s on Siste, then switch to Kontakter:
   - the Siste badge is gone;
   - Pappa's rust pill is gone, both in his row and on Home after Back;
   - Pappa's row in Kontakter keeps its place for the visit and loses its red line.
7. **A Siste row.** Tapping a Siste row opens the sheet, with no call. On the sheet, check the two pills' placement.
8. **Always Kontakter.**
   - Leave the book on Siste. Then Home, then the tile: Kontakter.
   - Leave it on Siste. Then Recents: Kontakter.
   - Leave it on Siste. Then Melding to another app, then Back: Kontakter.
   - On Siste, rotate: Siste stays.
   - Back from Siste: Kontakter. Back again: Home.
9. **Element X.** The debug-only `ElementCallDebugReceiver` (`src/debug`, as `MmsDebugReceiver`) posts a missed
   Element X call for Mamma:
   - Mamma gets the rust pill, and Siste gets "Tapt anrop i Element X";
   - after the receiver removes it, the row stays and the pill goes;
   - after `am force-stop` and a reopen, there is still one row.
10. **Time rules and calls off.**
    - A rule without calls: the book redirects. A rule that allows calls: Siste works.
    - The server turns calls off: no switch.
11. **On compat34:** steps 1-3, 5, 6 and 8.

## 9. Docs on build

- `docs/notes/launcher/calls.md`: a new section "Two badges and Siste (design 106)". Also update "Missed Element X
  calls" (the record), "The phone book as one list" (the pills, seen in Siste) and design 12's "Clearing".
- `docs/notes/launcher/ui-polish.md`, "The phone book's row": the two pills.
- `docs/testing/emulator.md` §6aw.
- PLAN: mark the item done in the merge commit.

## Q - choices with a visible cost

1. **How far back Siste goes.** I recommend **14 days**: the badges' own window, and one query for both. 30 days would
   need a second, wider read, and a kid's "who called me" is recent.
2. **Element X in Siste: missed calls only.** The launcher never learns who an answered or outgoing Element X call was
   with. Learning it would mean reading the call notification's person, which design 17's guard forbids. I recommend
   **missed only for now**; the rest would need a device capture first.
3. **A small record of missed Element X calls on the phone.** It holds the contact's number and the time for 14 days,
   in CE storage only, and nothing else. Round L5 stored nothing. Without the record, an Element X row leaves Siste
   the moment the kid opens the room in Element X. I recommend **keeping the record**.
4. **What counts as "seen" in Siste.** I recommend **a red row on screen for 1 s**, as Kontakter's rule. With this
   rule, a missed call below the fold keeps its badge. The alternative is that opening Siste marks everything seen,
   which is simpler but clears calls the kid never scrolled to.
5. **Kontakter after a call or the picture screen.** "Always Kontakter" uses design 79's visit boundary. So a callback
   placed from a Siste row's sheet returns to Kontakter when the call ends, and the long-press picture screen does the
   same. I recommend **yes, always**: one rule, the user's own word "alltid".
6. **Calls the kid declined are left out.** A declined call is logged as REJECTED, which is also our backstop's refusal
   of a caller the rules block. The cost: a declined call from Mamma doesn't show. I recommend **leaving REJECTED out**,
   since a blocked call must never show.
7. **The notification and the system call log open on Kontakter.** The rule says always. The contacts with unseen
   missed calls already lead Kontakter, and one caller opens their sheet directly. I recommend **Kontakter**. The
   alternative is Siste when there are several callers.
8. **Back on Siste goes to Kontakter first.** This is one more Back press to leave, as in Meldinger's "Ny melding". I
   recommend **yes**: the kid always comes back to where the book opens.
