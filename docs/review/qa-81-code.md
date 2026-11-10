# Code QA: design 81 - SMS reactions (branch `sms-reactions`)

Reviewed: `sms-reactions` at cd759e0a, the change `65594813..cd759e0a` (ec7c6158 the engine, b92a5483 the thread,
cd759e0a the docs; 40 files, about 5,050 of the +7,604 lines the emoji asset), against
`docs/design/81-sms-reactions.md` (revision 4 and its §12 "As built"). Also read: `launcher/CLAUDE.md`, the "SMS"
sections of `docs/notes/launcher/calls.md`, `docs/testing/emulator.md` §6aa, and the code the diff leans on
(`MmsFlow`'s push, placeholder, move and drop; `SmsRuntime`'s notify paths; `SmsNotifier`; `ThreadActivity`'s load,
compose bar and Back; `composeState`). Design 80's merge below it (`65594813`) is under its own review: only how
reactions sit on it (store v4 -> v5, group texts off) is judged here.

One full launcher run and sixteen targeted mutants (below). No emulator: nothing here is checked on a device. Every
finding says whether it is **confirmed** (by reading, by a test run, or by a throwaway probe test, reverted) or
**suspected**.

## Verdict

**Mergeable after M1.** L1 and L2 belong in the same fix round (small, and L2 is the tests for M1's guards); L3 is
for the emulator pass to settle. The Nits can follow. One choice goes back to the user: the length of the buzz (§"Open
point for the user").

What holds, checked by reading and by the mutants:
- **The parse is strict and bounded.** At most 2,000 units, a fixed head, one of four quote pairs closed by the very
  last character, the iOS 18 emoji part at most 16 units and listed. Every loop is linear in the body (60 forms x
  `startsWith`, one `indexOf` per emoji form, one `normalise`); no regex; nothing allocated from sender input beyond
  one substring. A body that is not exactly one form is a message.
- **Who reacts is who sent it.** The conversation is the sender's (SMS) or the group of `m.from` after `sameSender`
  (MMS); a reaction's `by` is its row's own `sender`, and `state` keys every removal by that sender. A sender can't
  put a reaction "from" someone else on a bubble, nor take away another person's (mutant m4 is caught).
- **One bubble or none.** Exact text first; a cut quote only with ten code points and only when every hit says the
  same; a picture only when exactly one image part is among the newest ten; the window is bubbles older than the
  reaction's own `seq` (L1, mutant m13 caught). Ambiguous or nothing: a plain message.
- **Held and released.** A held reaction is held like any message; a released one is always text (`attach = false`,
  mutant m14 caught).
- **Counts and lines.** `messages()`, `lastMessage()` and `unread()` leave reaction rows out (in SQLite and in the
  fake), so the unread count, Home's badge, the list's last line and order, the lock glance and the thread's paging
  see bubbles only. `markReadUpTo`'s subquery marks the reactions on the read bubbles.
- **Sending goes through the text's gate.** `reactionToSend` checks the target (this conversation, IN, shown, not a
  reaction, text or an available picture), the emoji (listed) and a removal (the kid's own in effect); then the same
  `targetsOf`, `GROUPS_OFF`, `decideSend` (time rule included) and outbox as a text. A repeated `clientId` compares
  target, op and key. The panel shows the grid only where `composeState` is `OPEN`, closes when it changes, and the
  engine refuses anyway.
- **The store.** `onCreate` runs `SCHEMA` then every step, `onUpgrade` from 1..4 runs exactly the missing steps, so a
  fresh install and an upgrade reach the same schema; `SqliteStoreVersionTest` pins the steps (append only). The fake
  keeps the SQLite contract for every new read.
- **The notification** uses the message's own silent decision (`callsBlockedNow()`), adds Do Not Disturb fail-closed
  (`currentInterruptionFilter == ALL`, a missing service counts as on), never wakes (`wake = false`), posts nothing
  when the kid has read everything meanwhile, and rebuilds - never cancels - while something is unread
  (`afterReaction` only when the notification is up, so a swiped one stays away).

**The suite:** 2,412 tests, 0 failures, 0 errors, 0 skipped (counted from the JUnit XML reports of one full
`testDebugUnitTest -PwarningsAsErrors=true` run). The 20 new are `SmsReactionsTest` 7, `SmsEngineTest` 7,
`MmsEngineTest` 2, `MessagesModelTest` 3, `SqliteStoreVersionTest` 1 - the implementer's numbers hold.

**Counts: Critical 0, High 0, Medium 1, Low 3, Nit 7.**

## Medium

### M1. An MMS still being fetched counts as "a picture" for a reaction, and what it turns out to be is never checked - confirmed (probe test)

`sms/engine/MmsFlow.kt:787-790` (every incoming MMS placeholder is one `image/jpeg` part, `PENDING` or
`WAITING_DATA`, until it is parsed), `sms/engine/Reactions.kt:83, 207-209` (`PICTURE_TARGET` takes exactly those),
`sms/engine/MmsFlow.kt:510` (the retrieve attaches the row but never looks at reactions already on it),
`sms/engine/MmsFlow.kt:633` (a drop deletes them), `sms/engine/SmsEngine.kt:868-870` (the conversation delete only
walks bubbles).

D3 lets a picture reaction sit on a placeholder because an iPhone often reacts before our fetch is done. But every
incoming MMS has that placeholder, text-only ones included (a group text, an iPhone's MMS fallback), and with mobile
data off it can stay `WAITING_DATA` for hours (design 77 D14). So in that window any `Likte et bilde` / `Loved an
image` SMS from the same person attaches to the newest waiting MMS when it is the only "picture" among the newest
ten bubbles. What then happens depends on what the MMS turns out to be:

1. **Text only:** the 👍 sits on a text bubble it never named, and the notification line reads "Reagerte med 👍 på
   «Hei»" - misfiled, but visible.
2. **Itself a reaction** (for example `Likte «Ok»`, an MMS of one text): it attaches to "Ok", and the `Likte et bilde`
   SMS is now a reaction on a reaction row. `messages()` shows neither, so a real SMS from a contact is hidden for
   good with no badge anywhere; its notification line quotes the hidden reaction ("Reagerte med 👍 på «Likte
   «Ok»»"); and `deleteConversation` leaves the row behind (it walks bubbles, and the cascade is one level deep).
3. **Dropped** (its sender no longer allowed, a From mismatch, any group while group texts are off, design 80
   D13): `drop` deletes the SMS outright with a `MessageDeleted`. The kid never sees it, it isn't
   counted or held, and the parent's blocked log shows only the MMS. That is departure 8 in §12; it contradicts the
   note's own rule one bullet above it (the move: "a reaction never hides a message unless it names a bubble beside
   it").

The probe (appended to `MmsEngineTest`, run, reverted): the kid sends "Ok"; Mamma's MMS arrives and waits; Mamma's SMS
`Likte et bilde` comes back `Attached` to the placeholder; the MMS is retrieved as the single text `Likte «Ok»` and is
`Attached` to "Ok". Then `engine.messages()` is `[(Ok, [👍])]`, `notifiable` is `[(Likte «Ok», Ok), (Likte et bilde,
Likte «Ok»)]`, and after `deleteConversation` the `Likte et bilde` row is still in the store with its `reaction_to`.

Neither guard has a test: mutants m11 (no detach on the move) and m16 (no delete on the drop) both survive.

**Smallest fix:**
- In `onRetrieved`, inside its transaction: when the row attaches, or when the retrieved parts hold no image part
  (`content.parts.none { it is Picture || it is NotShown || it is Undecodable }`), call `engine.detachReactionsOn(row.id)`
  - the same rule as the move.
- In `drop`, `detachReactionsOn` instead of `deleteReactionsOn`.
- Two engine tests: the probe's sequence (the SMS ends as a plain message), and a drop keeping the reaction as text.
  Together with L2's tests they kill m11 and m16.

## Low

### L1. Reactions can push an unread message's line out of the notification - confirmed by reading

`sms/engine/SmsEngine.kt:251-261`: `notifiable` merges the unread bubbles and the unread reactions in effect and keeps
the newest five by `seq`. Mamma writes "Kan du ringe meg?", then five people in a group (or Mamma on five bubbles)
react: the notification shows the five reaction lines and the question is gone from the shade. D12 says "A real
unread message's line never disappears because of a reaction". The thread, the count and Home's badge still show it,
so it is only the shade.

**Fix:** fill the five lines with unread messages first and give reactions only what is left (or always keep the
newest unread message); one `SmsEngineTest` case.

### L2. The guards the note leans on most have no test: six mutants survive - confirmed (mutants)

- **Do Not Disturb (m7):** `SmsNotifier.kt:123` reduced to `val quiet = silent` passes every test. The user's "fully
  silent under Do Not Disturb" rests on that line alone.
- **Group texts off for a reaction (m6):** exempting reactions from the `GROUPS_OFF` check passes; no test sends a
  reaction into a group.
- **The told-before MMS (m10):** dropping `&& !m.notified` (a waiting picture the kid was told of buzzes again when it
  turns out to be a reaction) passes.
- **The group move's detach (m11) and the drop (m16):** see M1.
- **SQLite's filters (m12):** `unread()` without `reaction_to IS NULL` passes, as every `SqliteMessageStore` statement
  does (no JVM test opens SQLite; the fake has the filter). Known (L7 of the design QA); the emulator pass is the
  only check of the store's SQL, so it must look (below).

**Fix:** pull the channel choice into a pure function (`reactionChannel(silent, reaction, filterAll)`, next to
`smsPostAlerts`) with a test of its four cases; one `MmsEngineTest` case each for a group reaction refused with
`GROUPS_OFF` and for the told-before reaction's `notify = false`. m11 and m16 are M1's tests.

### L3. The chip on an incoming bubble may sit on the bubble's time - suspected (layout read, not drawn)

`res/layout/item_message_bubble.xml` (the chip `bottom|end` with a 10 dp end margin; the time `layout_gravity="end"`
at the bubble's bottom inside 8 dp of padding), `ThreadActivity.kt` `bindChip` (the bubble's bottom margin 10 dp).

On an incoming bubble D6 puts the chip at the bottom right - where the time is. The chip is about 21 dp high (14 sp
plus 2 dp padding); with the bubble raised 10 dp it reaches about 11 dp into the bubble, past the 8 dp padding, into
the bottom of "12:30". On the kid's own bubbles the chip is at the left and the time at the right, so only incoming
ones are at risk (and a narrow incoming bubble, where `minimumWidth` makes the bubble just chip-wide, most).

**Fix if the emulator shows it:** raise the bubble by the chip's height less a few dp (about 14-16 dp), or give the
time a bottom margin while a chip shows.

## Nit

- **N1.** `SmsRuntime.kt:144-150`, `SmsEngine.kt:274`: with the asset unreadable the list is `EMPTY`, and `canonical`
  refuses every emoji - so the kid can't send even the six (`NOT_ALLOWED`), while §12 and `calls.md` say "only the
  classic six are recognised". Receiving the six works. Let `canonical` accept `Reactions.SIX` when the list is empty,
  or say so in the note. (The asset is in the APK; this is about the doc and the fallback being honest.)
- **N2.** `SmsNotifier.kt:131`: the public version follows the post's cause, not its lines - "Ny reaksjon" when a
  reaction re-posts a notification that also holds unread messages, "Ny melding" when `afterReaction` rebuilds one
  that holds only reactions. Derive it from the lines (any message line: "Ny melding").
- **N3.** `Reactions.kt:162`: `candidate`'s `mms` parameter is only ever `true` outside the tests (the SMS path never
  calls it). `candidate(textParts, otherParts)` would say the rule without the dead branch.
- **N4.** `ThreadActivity.kt:831`: the picker adapter reuses the thread's `TYPE_DAY` / `TYPE_BUBBLE` for heading and
  emoji. Two constants of its own read better.
- **N5.** `ThreadActivity.kt:790`: every open of the picker runs `hasGlyph` over the ~1,900 emoji again (off the main
  thread, so only a moment of an empty grid on a slow phone). Keep the filtered list for the process, as the emoji
  list itself is kept.
- **N6.** `ThreadActivity.kt:824`: "Nylig brukt" is written before the send, so a refused reaction and a removal both
  move the emoji to the front. Write it on `Accepted` of an add.
- **N7.** `NOTICE.md`: the Unicode License v3 asks for "this copyright and permission notice" with the copies or in
  the documentation; the asset's header and NOTICE give the copyright and a link. Including the licence's short text
  once in NOTICE.md covers both Unicode entries (the CLDR one before it follows the same pattern).

## Open point for the user: the length of the buzz

**Is 80 ms likely to be felt?**
- **In the hand:** yes. Keyboard ticks are 10-30 ms and are felt.
- **In a pocket, a bag or on a table:** a single 80 ms pulse is easy to miss on a small phone. That holds most for an
  eccentric-mass motor, which needs a good part of that time to spin up. Android may also scale it by the hidden
  "notification vibration intensity".
- So "a shorter buzz than a message" is right. Whether 80 ms is felt at all where the kid keeps the phone needs the
  Jelly Star itself; the emulator can't tell.

**The channel is the right mechanism.** A channel's vibration obeys the ringer mode, Do Not Disturb and Android's
"Notification vibration" switch. A direct `Vibrator` call would bypass all three.

**Its cost:** the pattern is frozen when the channel is first made on a phone, and only a new channel id changes it
later. So:
- settle the value on the test phone before any build reaches the kid's phone;
- a reinstall or cleared data resets it on the test phone;
- try 80 ms against roughly 150 ms (or two short pulses, `0, 60, 80, 60`) in a pocket.

I would put this to the user as one question rather than change it in the code.

## The eleven departures in §12

| # | Departure | Judgement |
|---|---|---|
| 1 | The asset is ~104 KB with `f`/`m`/`u` statuses, components and the Component group out, the source's and the asset's SHA-256 pinned | Sound. The script fetches over HTTPS and writes nothing unless the source hash matches; it isn't in CI, and the JVM pin on the asset is enough (a `--selftest` would mean vendoring the 600 KB source). |
| 2 | An unreadable asset is recorded and only the classic six are recognised | Sound for receiving; sending then refuses the six too (N1). |
| 3 | The picker's group icons are D13's list, not each group's first emoji | Sound (D13 named them). |
| 4 | The picker takes the thread list's place | Sound: no new root layout, and the compose bar is covered anyway. |
| 5 | Back or ✕ closes the picker and the panel together | Sound: one state less, nothing lost (the long press reopens). |
| 6 | Choosing the kid's own current emoji in the picker takes it away | Sound: the same as its cell in the grid. |
| 7 | A group MMS's move detaches reactions on it, which become plain messages | Sound - and the rule M1 extends to the retrieve. |
| 8 | A waiting MMS that is dropped takes its reactions with it | **Change** (M1): detach, as 7, so the SMS stays a visible message. |
| 9 | The panel closes when the compose bar's state changes | Sound (L5 of the design QA); the engine's gate is the guard anyway. |
| 10 | The panel follows its bubble; the kid's current cell reads "Fjern din ❤️" | Sound. |
| 11 | The wire language is the `messages_reaction_language` resource (`en` / `nb`) | Sound: it is the language the launcher shows, with no second locale path; the comment in `values/` tells a translator it isn't a word. |

## Mutants

Each targeted class rerun, then `git checkout -- <file>`; a surviving mutant is a finding.

| # | Mutant | Tests run | Result |
|---|---|---|---|
| m1 | `candidate`: an MMS with a picture part may react (`otherParts == 0` dropped) | `MmsEngineTest` | killed |
| m2 | exact match becomes `contains` | `SmsReactionsTest` | killed |
| m3 | a cut quote two different bubbles start with is accepted (`distinct().size >= 1`) | `SmsReactionsTest` | killed |
| m4 | a removal of anyone's reaction (`it.by == reactor` dropped) | `SmsReactionsTest`, `SmsEngineTest` | killed |
| m6 | `GROUPS_OFF` skipped for a reaction | `MmsEngineTest`, `SmsEngineTest` | **survived** (L2) |
| m7 | Do Not Disturb ignored for a reaction's channel | `sms.*`, `ui.messages.*`, `badges.*` | **survived** (L2) |
| m8 | an SMS removal notifies | `SmsEngineTest` | killed |
| m9 | the 2,000-unit cap off by one | `SmsReactionsTest` | killed |
| m10 | a told-before MMS reaction notifies (`!m.notified` dropped) | `MmsEngineTest`, `SmsGlanceTest` | **survived** (L2) |
| m11 | the group move doesn't detach | `MmsEngineTest`, `SmsGlanceTest`, `SmsEngineTest` | **survived** (M1, L2) |
| m12 | SQLite `unread()` counts reactions | `sms.*` | **survived** (L2; no JVM test opens SQLite) |
| m13 | the candidate window ignores the reaction's own `seq` (L1) | `MmsEngineTest`, `SmsEngineTest` | killed |
| m14 | a released held message may attach | `SmsEngineTest` | killed |
| m15 | the kid may react to an outgoing bubble (`direction == IN` dropped from the send check) | `SmsEngineTest` | killed |
| m16 | a dropped MMS keeps the reactions on it | `MmsEngineTest` | **survived** (M1) |

(m5 was folded into m12.)

## What the emulator pass must check

1. **The store upgrade.** An existing debug install at v4 (design 80's), with messages, upgraded to v5; and a fresh
   install. In both: `PRAGMA user_version` is 5, `.schema message` is identical, and `message_reaction` exists.
2. **The SQL itself, which no JVM test runs (L2):**
   - an attached reaction leaves Home's badge, the list's unread dot, the last line and the order alone;
   - opening the thread marks a reaction on an older bubble read, and its notification goes (`markReadUpTo`'s
     subquery);
   - a delete of a bubble takes its reaction rows;
   - (m12, added in the fix round) SQLite's `unread()` and `lastMessage()` with reaction rows present: the count and
     the list's last line ignore them.
3. **The chip at 288 dp** (`jelly36`, or `wm density 240`):
   - on an incoming bubble against its time (L3), on a picture bubble, and on a narrow "Ok" bubble;
   - three emoji and "+N";
   - the kid's own dimmed while `QUEUED` and full from `SENT`;
   - the dot for an emoji the font lacks.
4. **The panel:**
   - the 4 x 2 grid fits;
   - a tap on the thread closes it, and so does Back;
   - TalkBack's focus and the bubble's custom actions, which read "Reager med ❤️" ... "Slett";
   - no grid in a group with group texts off, under a time rule, and in the read-only history (badges still shown);
   - the kid's own, failed and picture-waiting bubbles go straight to the delete confirm unless someone reacted.
5. **The picker:**
   - the time from "+" to a filled grid on the Jelly Star's density (N5);
   - the group strip jumps to its heading;
   - the headings are read as headings;
   - no empty boxes and no skin tones;
   - Back and ✕ close both.
6. **The notification:**
   - `dumpsys notification` shows the channel `launcher:messages_reactions` at importance 3, with no sound and the
     vibration `[0, 80]`;
   - an add posts there, with "Reagerte med 👍 på «…»" and the public "Ny reaksjon";
   - with `cmd notification set_dnd priority`, and under a time rule without calls, it posts on
     `launcher:messages_silent`;
   - no lock wake;
   - a removal rebuilds the notification only while it is up; a swiped one stays away;
   - L1's case.
7. **MMS with `fake-mmsc.py`:**
   - a group reaction attaches in the group's thread;
   - a picture with the same text stays a message;
   - after the fix, M1's sequence (an MMS waiting, then `Likte et bilde` by SMS, then the MMS retrieved as text,
     as a reaction, and dropped) leaves the SMS as a visible message in every case - since the fix round it never
     attaches to the waiting MMS at all; once a picture is fetched, the same words attach to it.
8. **The sent bytes:** the stored `OUT` body for nb and en, and a 300-character quote sent in at most three UCS-2
   parts.
9. **The buzz:** whether it is felt can only be judged on the Jelly Star itself (open point).

## Not checked

- Anything on a device or the emulator: the chip, panel and picker drawn, TalkBack, the channel, the vibration, the
  store upgrade, and SQLite running the new statements.
- What an iPhone sends and reads. Step 0 isn't done, and the forms are Apple's string tables, not captures. The note
  marks [W] only for nb's heart, and the code claims nothing more. The forms live in one table plus the vectors file,
  so a capture changes rows, not code.
- Android's `BreakIterator` on ZWJ sequences: the JVM's (JDK 17) doesn't treat them as one grapheme as ICU does, so
  the JVM test proves only surrogate safety.
- Design 80's own changes, `PLAN.md` and every note but the "SMS" sections of `calls.md`.
- A cost measurement of the asset's load (about 104 KB and ~3,800 lines, read once on the engine thread or the
  picker's background thread).

## Fix round (2026-11-10, branch `sms-reactions`)

Code in 9d258a04; the design note, the SMS note, the emulator list and this section in the docs commit after it. Every
finding was checked against the code first; none was refuted. The coordinator's decisions: every finding fixed now,
the buzz stays `0, 80` until the user answers (pattern and channel id side by side, one comment on the freeze), m12 goes
to the emulator pass.

| Finding | Commit | Fixed | Test |
|---|---|---|---|
| M1 | 9d258a04 | Confirmed (the probe's sequence). **Rule chosen: a picture reaction attaches only to a picture that is there (`AVAILABLE`)** - `PICTURE_TARGET` is `{AVAILABLE}`; every image part in any state still counts among the newest ten, so a picture beside one being fetched is ambiguous. Chosen over "detach when it turns out otherwise" because it can never hide a real message and is less code: a placeholder never carries a reaction, so the retrieve (a text, a reaction itself), the move, the drop and a conversation delete have nothing to give back, and the move's `detachReactionsOn` and the drop's `deleteReactionsOn` are gone (departure 8 is withdrawn: nothing to take or give back). The cost: an iPhone's reaction to a picture we haven't fetched yet stays the text "Likte et bilde" - visible, never lost. | `MmsEngineTest`: "a picture reaction never attaches to an MMS still being fetched, whatever it turns out to be" (text, a reaction itself, dropped; then a real picture takes it), "the conversation deleted while an MMS waits leaves nothing behind"; the vectors' match rows for `PENDING`, `WAITING_DATA` and a picture beside a placeholder. Re-allowing the placeholder states fails three tests. |
| L1 | 9d258a04 | `notifiable` takes the newest unread messages (up to five) first; reactions in effect fill only the lines left. | `SmsEngineTest` "reactions never push an unread message's line out of the notification" |
| L2 | 9d258a04 | m7: the channel is the pure `smsChannel(silent, reaction, passesFilter)` in `SmsGlance.kt`, which `SmsNotifier` calls. m6, m10: tests. m11, m16: their code is gone (M1); M1's tests cover the outcome. m12: on the emulator list below (no JVM test opens SQLite). | `SmsGlanceTest` "a reaction's channel - its own, silent under a time rule or Do Not Disturb - and the lock's text"; `MmsEngineTest` "a reaction into a group is refused while group texts are off, as a text is", "an MMS told of as a waiting picture only rebuilds when it turns out to be a reaction" |
| L3 | 9d258a04 | The bubble's bottom margin is the chip's measured height less 5 dp (`CHIP_OVERLAP_DP`, inside the bubble's 8 dp bottom padding), so the chip has its own room at any text size and never reaches the time or the delivery line. | The emulator pass (item 3) |
| N1 | 9d258a04 | `Reactions.emoji(raw, list)`: the list's rule, else the six by key - the parse and `reactionToSend` both use it, so the six are received and sent with the asset unreadable. | `SmsReactionsTest` "without the emoji list the six are still read, nothing else"; `SmsEngineTest` "without the emoji list the six are still received and sent, no other emoji" |
| N2 | 9d258a04 | The public text from the lines: `smsPublicIsReaction(lines)` - "Ny reaksjon" only when every line is a reaction. | `SmsGlanceTest` (as L2's) |
| N3 | 9d258a04 | `candidate(textParts, otherParts)`; the vectors' rows lost `mms`. | `SmsReactionsTest` "every candidate vector (H1)" |
| N4 | 9d258a04 | The picker's `TYPE_HEADING` / `TYPE_EMOJI`. | - |
| N5 | 9d258a04 | `hasGlyph` answers kept for the process (`GLYPHS`, a `ConcurrentHashMap` in the thread's companion, each thread with its own `Paint`); the picker's first pass stays off the main thread. | The emulator pass (item 5) |
| N6 | 9d258a04 | "Nylig brukt" is written after the send's answer, only for an accepted add (`recentAfterSend`). | `MessagesModelTest` "Nylig brukt learns only an emoji that was sent - not a refused send, not a removal" |
| N7 | 9d258a04 | `LICENSE-UNICODE` holds the Unicode License v3 text; both Unicode entries in `NOTICE.md` (CLDR's and the emoji list's) name it. | - |
| Open point | - | Unchanged: `0, 80` with the channel id, frozen at first creation (a new pattern needs a new id), until the user decides. | - |

**The suite after the fix round:** 2,421 tests, 0 failures, 0 errors, 0 skipped (the JUnit XML reports of one full
`assembleDebug testDebugUnitTest lintDebug -PwarningsAsErrors=true` run on 9d258a04; lint passes). Nine new:
`MmsEngineTest` 4, `SmsEngineTest` 2, `SmsReactionsTest` 1, `SmsGlanceTest` 1, `MessagesModelTest` 1.

Added to "What the emulator pass must check": item 2 also runs SQLite's `unread()` and `lastMessage()` with reaction
rows present (m12), and item 7's M1 sequence now expects the SMS as a plain message from the start (it never attaches
to a placeholder).

## Emulator pass (2026-11-10, `sms-reactions` after merging `origin/main` edeb78a6)

`handy36` (Android 16, `-wipe-data`, `wm density 240`: the Jelly Star's 320 x 569 dp), the debug launcher only, a
throwaway server built once from this branch (port 3109, its own data dir; the phone seeded in its database: SMS and
calls "Styrt av Vibb", nb, Child PIN, the lock's wake on, Mamma, Pappa and Bestemor with `sms_in`/`sms_out`/`mms`, the
own number `+4790000010` for groups), `scripts/fake-mmsc.py` with the debug hook's `mmsc_host`/`stand_in` for MMS
(§6v step 3b). Screenshots in the session's `scratchpad/sms-reactions/emu/shots/`. The steps are in
`docs/testing/emulator.md` §6aa.

| # | Check | What was done | What was seen |
|---|---|---|---|
| 1 | The store's upgrade, and a fresh store | main's build (edeb78a6, store v4) installed and enrolled, three SMS received; this branch's build installed over it (`install -r`). Later the store removed (from the app's own uid) and made again at the next open | Upgraded: `user_version` 5, `reaction_to`/`_emoji`/`_key`/`_op` added, `message_reaction` there, the three rows kept, the next reaction attached. Fresh: the same 19 objects, identical SQL, `user_version` 5. |
| 2 | The SQL no JVM test runs (m12 included) | Reactions attached to old bubbles; the lock glance, the list and the store read | Glance "2 nye meldinger fra Mamma, 2:56" with a 2:58 reaction present, later "3 nye meldinger ..., 3:14" with a 3:15 one; the list kept Mamma below Pappa with "Ok" as her last line. Opening the thread marked a reaction newer than every bubble read (`is_read` 1: `markReadUpTo`'s subquery). Deleting the kid's "Kommer kl 6" took its three reaction rows; "Fjern reaksjonene" took Mamma's row on the picture. |
| 3 | The chip at 288 dp (L3) | Incoming, the kid's own, a picture, two emoji with the kid's ring; again at font scale 1.3 | Below the bubble at the corner facing the middle, never over the time or "Levert", at 1.0 and 1.3; the kid's own in the peach ring; on the picture bubble's edge. **Not seen:** the dim while pending (the emulator's network sends at once), "+N" (needs four different people), the dot for a missing glyph (the Android 16 font has every emoji of the list). |
| 4 | The panel | Long presses on a received bubble, on one others reacted to, on the kid's own, in a group with group texts off | The 4 x 2 grid and "Slett" fit at 288 dp, the bubble ringed; "Fjern reaksjonene" only once someone else reacted; the eighth cell the kid's 👋 read "Fjern din 👋"; on the kid's own bubble only "Fjern reaksjonene" / "Slett"; group texts off: straight to "Slette meldingen?". The cells' descriptions read "Reager med ❤️" ... "Flere emojier" (uiautomator). **Not checked:** TalkBack running (its focus and the custom actions themselves), a tap on the thread closing the panel, the read-only history. |
| 5 | The picker | "+", the strip, a pick, a second opening | Filled within a second on the emulator; "Velg en reaksjon", ✕, the nine-icon strip (a tap jumps to "Mennesker og kropp"), headings, five columns, no skin tones, no empty boxes. 👋 picked: sent as `Reagerte med 👋 på «Kommer kl 5»`, and "Nylig brukt" then held 👋; 👋 picked again (the kid's current) sent `Fjernet 👋 fra «Kommer kl 5»`. **Found:** the second opening kept the first visit's scroll position, so "Nylig brukt" at the top was out of sight - fixed (the grid scrolls to the top once its lines are in; `MessagesWiringTest` "the reaction picker opens at the top, where Nylig brukt is"). Not re-run on the emulator after the fix. |
| 6 | The notification | Reactions from Mamma and Pappa with the kid on Home; then with `cmd notification set_dnd priority`; then under a time rule without calls (a device rule seeded for the day) | The channel: `launcher:messages_reactions`, importance 3, `mSound=null`, pattern `[0, 80]`, no DND bypass. An add posts there with "Reagerte med ❤️ på «Kommer kl 6»" and the public "Ny reaksjon"; the vibrator ran one 81 ms `NOTIFICATION` effect at full amplitude, no sound. Under Do Not Disturb and under the time rule the same post went to `launcher:messages_silent` (importance 2), no vibration. See the two notes below. |
| 7 | MMS with the fake MMSC | A push from Mamma waiting (`WAITING_DATA`: the MMSC listed, no stand-in); her `Likte et bilde` by SMS; then the stand-in, the picture fetched; `Loved an image`; a group MMS (to the kid and Bestemor) and the kid's 😂 into it | `Likte et bilde` stayed a plain message while the MMS waited (M1); once the picture was `AVAILABLE`, `Loved an image` attached to it. The kid's group reaction went as one send-req to both members with `Lo av «Hei fra den falske MMS-serveren 32»`. **Not checked:** an incoming group text reaction (the fake MMSC's text is fixed); `MmsEngineTest` covers it. |
| 8 | The sent bytes | 👍 on "Ok"; ❤️ on a 306-character message | `Likte «Ok»` (nb launcher), delivered. The long one: `Satte et hjerte ved «Husk at vi ... Vi kjø…»`, exactly 200 UTF-16 units, 3 parts, delivered. |
| 9 | The buzz | - | Whether 80 ms is felt in a pocket can't be judged on an emulator (the open point stays with the user). Seen here: Android's own "notification cooldown" attenuated a reaction that came 10 s after a message from the same app (amplitude 0.16); after a quiet minute it ran at full strength. |

**The suite after the pass's fix:** 2,424 tests, 0 failures, 0 errors, 0 skipped (one full `assembleDebug
testDebugUnitTest lintDebug -PwarningsAsErrors=true`; 2,423 after merging main, plus the picker's test).

Two notes from the pass:
- **The wake:** with the screen off a reaction left it off - but so did an ordinary message here (the lock's wake on
  notice didn't fire on this AVD), so the pass can't tell the two apart. The code posts a reaction with
  `wake = false` (`SmsGlanceTest` "the notifier's one wake comes after the post" pins it).
- **Outside this design - every notification is muted while handy's lock is up.** In kiosk, Android turns off every
  notification's sound and vibration (`mDisableNotificationEffects=true`, `DISABLE_NOTIFICATION_ALERTS` from
  `LockTaskController`) whenever the lock-task features lack NOTIFICATIONS. `featuresWhileLocked` removes NOTIFICATIONS
  while the PIN lock is LOCKED (design 10's "minus the shade/heads-up alerts"), so on a kiosk phone with its lock up -
  the usual state in a pocket - a message rings and buzzes not at all, a reaction neither. Seen here twice: locked,
  effects off; unlocked (features 127), effects on and the buzz played. Not changed in this round; it needs the user's
  decision (it may be the intended trade, but it reads as if messages stay silent on a locked phone).
- The seed first copied an older run's `lock_task_features = 0` (no NOTIFICATIONS even unlocked), which muted the first
  reactions; set to the server's default (63) the pass went on. A sync without killing the debug hook's memory: an SMS
  from a stranger; a sync at all: `run-as <package> kill <pid>` (a device owner can't be force-stopped) and Home.

## Verification (2026-11-10, `sms-reactions` at 84c43dfb)

Merged into `qa-81-code` as a fast-forward. I read:
- the launcher's main-code diff `c95dd85a..84c43dfb` in full;
- the tests it added;
- the merge's shared files (`MmsFlow.kt`, `OwnLearning.kt`, the end of `MmsEngineTest`; the store's steps are
  unchanged since the review, v4 is design 80's as merged and v5 is still the last step);
- the "Fix round" and "Emulator pass" sections above.

Targeted runs only. At 84c43dfb, `com.kidslauncher.mdm.sms.*`, `ui.messages.*` and `badges.*` run 326 tests, 0
failures, 0 errors, 0 skipped (JUnit XML). The implementer's full count, 2,424/0, isn't re-run here.

**Verdict:** every finding is fixed. One new Nit. Nothing in the fix round or the merge breaks a reaction path I
traced. None of the pass's unchecked items blocks the merge.

**Counts after the fix round: Critical 0, High 0, Medium 0, Low 0, Nit 1 (V1).**

### The findings

| Finding | Result | How it holds |
|---|---|---|
| M1 | **Fixed** | `PICTURE_TARGET = {AVAILABLE}` (`Reactions.kt:88`). Every image part still counts among the ten, so a placeholder beside a real picture makes the reaction ambiguous. A placeholder never has a body, so no text reaction names it either. The kid's send already required `AVAILABLE`. A row can't carry a reaction while it is a placeholder. The only things that move (the group move), drop (`drop`) or turn a row into a reaction (`onRetrieved`) all act on placeholders, so removing `detachReactionsOn` and the drop's cascade loses nothing. Reaction-on-reaction can't arise any more: targets come from the bubble query or from `reactionToSend`'s `reactionTo == null`, so `deleteConversation`'s one-level cascade is enough. Cost: a reaction to a picture we haven't fetched yet stays visible text. Re-allowing `PENDING`/`WAITING_DATA` fails three tests. Withdrawing departure 8 follows. |
| L1 | **Fixed** | `notifiable` takes up to five unread messages, then fills the remaining room with the newest reactions in effect, oldest first (`SmsEngine.kt:241-252`). Restoring the old merge fails the new `SmsEngineTest` case. |
| L2 | **Fixed** | m6 (group texts off) and m10 (told-before) are now killed by their `MmsEngineTest` cases. m7 is killed in the pure `smsChannel`. m11 and m16: their code is gone (M1). m12 survives as expected: it moved to the emulator pass, and that pass's item 2 shows the glance count, the list's order and last line, and `markReadUpTo`'s subquery with reaction rows present. The one remaining unit-test gap is in the glue: `SmsNotifier` handing `passesFilter(context)` to `smsChannel` survives as a mutant. The emulator's Do Not Disturb post on `messages_silent` (item 6) is its check. Acceptable. |
| L3 | **Fixed** (by the emulator only) | The bubble's bottom margin is the chip's measured height less 5 dp (`ThreadActivity.kt:1010`), so the overlap stays inside the 8 dp bottom padding at any font scale. The pass saw it clear of the time and "Levert" at font scale 1.0 and 1.3. A JVM test can't check layout. |
| N1 | **Fixed** | `Reactions.emoji` = the list's rule, else the six by key. It is used by both the parse and `reactionToSend`. Reverting the send path fails the new engine test. It now also accepts an unlisted variant whose key is one of the six (stored fully-qualified). That is harmless. |
| N2 | **Fixed** | `smsPublicIsReaction(shown)`: "Ny reaksjon" only when every line is a reaction; the `any` mutant is killed. |
| N3 | **Fixed** | `candidate(textParts, otherParts)`; the vectors were updated. |
| N4 | **Fixed** | `TYPE_HEADING` / `TYPE_EMOJI`. |
| N5 | **Fixed** | `GLYPHS` is a process-wide `ConcurrentHashMap`. Each thread uses its own `Paint` (the screen's on the main thread, a fresh one in the picker's background pass). |
| N6 | **Fixed** | `recentAfterSend` runs in the send's answer: only an accepted add. Its mutant is killed. The wiring (`fromPicker = true`) survives as a mutant; the pass's item 5 saw 👋 learned after an accepted send. |
| N7 | **Fixed** | `LICENSE-UNICODE` holds the licence text, and both Unicode entries in `NOTICE.md` name it. |
| Departure 8 | **Withdrawn** | Nothing hangs on a placeholder, so there is nothing for a drop to take. |
| Open point (the buzz) | Still open | `0, 80` stays, with the freeze noted at the constant. The pass found that the question is wider than the pattern; see below. |

### New

**V1 (Nit). A reaction may buzz without its line.**
- **Where:** `SmsRuntime.kt:304` with `SmsEngine.kt:241-244`.
- **What happens:** since L1's fill, five unread messages leave no room for a reaction's line. The reaction still
  posts on `messages_reactions`: the kid feels the short buzz and finds only the five messages, with the public "Ny
  melding".
- **Fix:** in `notifyAttached`, post with `reaction = true` only when the reaction is among `notifiable`'s lines;
  otherwise rebuild quietly (`afterReaction`).

Nothing else new. Checked and fine:
- **The scroll reset:** `submitList(items) { grid.scrollToPosition(0) }` runs its callback even when the list is
  unchanged, so a second opening scrolls too.
- **The L3 margin:** set on the holder's own params before layout, as before.
- **The merge's `OwnLearning` change** (design 80's L2: an unreadable SIM list keeps the stored state and uses none
  of it) doesn't touch reactions. For one message it gives the same result as the old clearing would have; judging it
  belongs to design 80's review.

### The mutants (verification)

| Mutant | Tests | Result |
|---|---|---|
| m6 again: `GROUPS_OFF` skipped for a reaction | `MmsEngineTest` | killed |
| m7 again: `smsChannel` ignores Do Not Disturb | `SmsGlanceTest` | killed |
| m7b: `SmsNotifier` passes `true` for the filter | `sms.*`, `ui.messages.*`, `badges.*` | **survived** (glue; the emulator's DND check covers it) |
| m10 again: `!m.notified` dropped | `MmsEngineTest` | killed |
| m12 again: SQLite `unread()` counts reactions | `sms.*` | **survived** (no JVM SQLite; the emulator's item 2) |
| `PICTURE_TARGET` back to `AVAILABLE`, `PENDING`, `WAITING_DATA` | `SmsReactionsTest`, `MmsEngineTest` | killed (3 tests) |
| `notifiable` back to the newest-five merge | `SmsEngineTest`, `SmsGlanceTest` | killed |
| `recentAfterSend` ignores the answer and the op | `MessagesModelTest` | killed |
| the picker's scroll reset removed | `MessagesWiringTest` | killed (a source-text test) |
| `smsPublicIsReaction`: `all` -> `any` | `SmsGlanceTest` | killed |
| the send path back to the list-only emoji rule | `SmsEngineTest` | killed |
| the picker's `fromPicker = true` dropped | `ui.messages.*` | **survived** (glue; the emulator's item 5 saw it learn) |

Every mutant was reverted with `git checkout -- <file>`, and `git status` was clean before this commit.

### The emulator pass, read against the nine checks

No row claims more than its steps can show. In particular:
- Item 2's "a reaction newer than every bubble marked read" can only come from `markReadUpTo`'s subquery.
- Item 1's fresh store was made by the app itself after the file was removed.
- Item 4's "descriptions" were read by uiautomator, and the pass says so rather than claiming TalkBack.

Not done from my list, but not claimed either:
- item 6's removal rebuild, the swiped notification staying away, and L1's case (the JVM test covers L1);
- item 3's pending dim, "+N" and the dot;
- item 7's incoming group reaction (covered by `MmsEngineTest`).

**Blockers among the unchecked items: none.**

| Not checked | Blocker? | Why |
|---|---|---|
| The lock wake (inconclusive) | No | The code posts with `wake = false`, and `SmsGlanceTest` pins that the wake comes only after a post that asks for it. On this AVD an ordinary message didn't wake either, so the pass can't separate the two. |
| TalkBack running | No | The labels are there (uiautomator). The custom actions are plain `ViewCompat` calls. It should be run once with the next TalkBack pass of the messages screens. |
| A tap on the thread closing the panel | No | Back closes it, and a choice closes it. A failure here would only leave the panel up. |
| The read-only history | No | `reactPanel`'s test pins "no grid unless the compose bar is `OPEN`". Badges come from the same `messages()` as any thread. |
| An incoming group reaction | No | `MmsEngineTest` covers it. The fake MMSC's text is fixed. |
| The picker fix not re-run | No | A one-line commit callback, pinned by a source test. Look at it in the next emulator session. |

### For the user (from the pass, outside this design)

**Kiosk mutes every notification while the PIN lock is up.**
- **What the pass saw:** `featuresWhileLocked` takes NOTIFICATIONS out while the lock is LOCKED. Android then turns
  off every notification's sound and vibration (`DISABLE_NOTIFICATION_ALERTS`).
- **Effect:** on the kid's phone with its lock up - the usual state in a pocket - a message neither rings nor buzzes,
  and a reaction doesn't buzz either.
- **For the buzz question:** whether 80 ms is felt in a pocket matters only if notifications may alert under the lock
  at all. The user should decide that first; it belongs to the lock (design 10), not to design 81.

**Android's "notification cooldown" is not a concern for the Jelly Star.** The pass saw it soften a reaction that
came 10 s after a message. Cooldown is an Android 15+ feature, and the Jelly Star runs Android 13/14, so the pass's
observation doesn't carry over to it.

### Not checked in this verification

- **Nothing on a device:** L3's layout, the panel, the picker and TalkBack rest on the emulator pass as written.
- **The full suite:** not re-run; only the three packages above.
- **Design 80's merged changes:** their own merits are not judged here.

### Second pass (V1)

| Finding | Fixed | Test |
|---|---|---|
| V1 | Confirmed. `SmsNotifier.notify` with `reaction = true` now posts on `launcher:messages_reactions` only when the reaction's own line is among `notifiable`'s (`smsReactionShown(lines, reactionId)`, pure, beside `smsChannel` in `SmsGlance.kt`); otherwise - five unread messages leave no room, or the kid read it meanwhile - it hands over to `afterReaction`: a quiet rebuild of a notification that is up (silent channel, no wake), or nothing. A removal ("Fjernet …") never reaches the post: `notifyAttached` sends `notify = false` to `afterReaction`, the same quiet rebuild. Design 81 §12 says so. | `SmsGlanceTest` "a reaction buzzes only when its line is on the notification" (four unread messages and a reaction: its line, the reactions channel; five: no line, a quiet rebuild; and the notifier's guard before the post, read from the source) |
