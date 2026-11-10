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
   - a delete of a bubble takes its reaction rows.
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
     as a reaction, and dropped) leaves the SMS as a visible message in every case.
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
