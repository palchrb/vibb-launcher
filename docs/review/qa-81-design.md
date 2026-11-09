# Design QA: design 81 - SMS reactions (`docs/design/81-sms-reactions.md`)

Checked against `design-81-sms-reactions` (7f445a69, on `main` 407f6a4a), and against the code it builds on:
- `main`: `sms/engine/SmsEngine.kt` (`receiveSms`, `show`, `judgeHeldForRules`, `releasePass`, `send`, `repeated`,
  `delete`, `deleteConversation`, `conversationOf`), `engine/MmsFlow.kt` (`admit`, `onRetrieved`, `move`, `TOLD_STATES`),
  `engine/Model.kt`, `engine/MessageStore.kt`, `store/SqliteMessageStore.kt` (`messages`, `unread`, `lastMessage`,
  `markReadUpTo`, `Helper.onUpgrade`, `VERSION_2`/`VERSION_3`), `SmsRuntime.kt` (`afterReceive`, `notifyShown`),
  `SmsNotifier.notify`/`cancel`, `ui/messages/ThreadActivity.kt` (`loadNow`, `show`, paging, `askDelete`, `bindBubble`),
  `MessagesModel.kt` (`threadItems`, `deliveryLine`, `lastLine`, `MessageActions`), `MessagesActivity.kt` (the list's
  read), `MessagesScreens.showConfirm`;
- `sms-glance` (2221bdd8, step 5): `SmsGlance.kt` (`smsGlanceRow`) and `SmsHome.refresh`; its store is still version 3;
- design 77 D7, D10-D12, D16, D18, §6.2-§6.7 and §9 step 7; `docs/review/qa-sms-mms.md` (N-V1, the v3 step).

Apple's table was spot-checked through applelocalization.com's API (iOS 18 and 26, `IMSharedUtilities.loctable`):
`Liked "%@"` → no `Likte «%@»`; `Liked an image` → `Likte et bilde`; `Emphasized "%@"` → en `Emphasized “%@”`, en_GB
and en_AU `Emphasised “%@”`, no `Satte utropstegn ved «%@»`; `Emphasized an image` → `Satte utropstegn ved et bilde`;
`Removed %@ from "%@"` → `Removed %@ from “%@”` / `Fjernet %@ fra «%@»`; `Removed a dislike from "%@"` →
`Misliker ikke lenger «%@»`. All match §1. (Apple's keys use straight quotes; the en values are curly, as the note says.)
No build, no device.

**Verdict: the right shape, and the core safety rule is sound for SMS.** Whole-message parsing plus an exact match
against a bubble the kid already sees means an attached SMS reaction can only hide text that is shown elsewhere in the
thread (or a prefix of it). The hold, the block and the group gate are untouched, and a reaction can't be dressed as the
kid's own. Server and contracts: rightly none.

The problems are at the edges the note didn't walk through:
- **An MMS can attach with more than its text** - a picture or other parts disappear into a badge, unseen and
  undeletable (H1).
- **The notification path** would notify an attached reaction with its raw text, and D9's "withdraw" takes real
  messages' notifications with it (M1).
- **"`messages()` returns every row, the front end folds"** leaks reactions into every reader that already exists: the
  list's last line, paging, the scroll, the "Sendt" line (M2).
- **"Exactly one emoji grapheme"** is unbounded (M3).
- **D4's state machine** (removal on duplicate texts, failed rows, emoji variants) leaves stray text and wrong badges
  (M4).

Counts: **0 Critical, 1 High, 4 Medium, 9 Low, 8 Nit.**

## What holds (verified)

- **The hold is not bypassed.** `show()` runs only for `IncomingVerdict.Allow`. A held body lives in `held`, never in
  `message`, until `releasePass` re-enters `show()`. `judgeHeldForRules` does the same. A blocked message stores no
  body. So D10's "matching runs only on a message the engine already let in" holds by construction, provided the parse
  sits inside `show()` and `MmsFlow.onRetrieved`, after `senderNow`, `sameSender` and the group rule, as §3 places it.
- **No spoofed kid reaction.** The kid's reactions are `Direction.OUT` rows, which only `send` makes. Every IN row has a
  sender (`show` uses `addressOf`, or `unknown:` + the cleaned raw address). A reaction is keyed by its sender, so a
  group member can replace or remove only their own reaction, never the kid's or another member's.
- **Text can't be hidden by SMS.** The body must be a known prefix + quote + exact closing quote at the very end, and
  the quote must equal (or, cut, be a prefix of) a shown non-reaction bubble in the same conversation. The only new
  information an attached SMS carries is its emoji (M3 bounds that) and which bubble it names.
- **The window's cost.** `message_conversation (conversation_id, seq)` exists, so "newest 100 of a conversation" is an
  index range. Equality short-circuits on length, and a prefix match is bounded by the quote's length. Even with
  maximal concatenated bodies this is milliseconds, well inside `RECEIVE_BUDGET_MS` (3 s). N4 adds a cheap cap anyway.
- **The send path.** A `ReactionCommand` through `send` keeps the gate (D5 of 77, every recipient for a group), the
  `clientId` dedup, the outbox's second gate in `drainOutbox`/`drainMms`, `SENDING` before the platform, and
  `UNCERTAIN`. A group reaction goes as a group MMS only when `canSend` holds (own number known, everyone textable).
- **Unread, Home and the glance.** `unread()` and `markReadUpTo` count `is_read = 0` IN rows, so storing attached rows
  `read = 1` keeps them out of the badge and of `SmsHome`'s unread. Step 5's `smsGlanceRow` takes the newest unread IN
  message, so an attached row never becomes the glance's line.
- **Wire forms out of `strings.xml`** (D8) is right. A translation pass would break iPhone parsing.
- **§6 "None".** The server never reads bodies, `sms-held` carries who and when, and `sms_state`'s counters already count
  a reaction as a message. No policy or report key is needed for what is proposed. (L9/N8 ask the user whether they want
  a switch; if yes, that one becomes a contract change.)

## High

**H1 - An MMS with a picture (or any non-text part, or several texts) can attach as a reaction; its content is then
hidden from the kid, and the kid can't delete it.** (D2, D3, D9, D5)
- *Mechanism.* `MmsFlow.onRetrieved` sets `body` to the text parts joined with `"\n"` and stores every part. D9 parses
  "the text" when it arrives. Nothing says a message with parts other than its text can't be a reaction.
- *Scenario.* An allowed contact (or a group member admitted by rule 5) sends an MMS with a photo and the text
  `Likte «Ok»`. The kid has an "Ok" bubble. The MMS attaches:
  - the photo is never shown, because it is folded into a 👍 badge;
  - the kid can't delete it, because D5 forbids deleting a reaction row on its own;
  - the picture file stays held (`partHashes()`), so the bridge (as an `m.reaction`, losing it) and the step-7 export
    (as a full MMS into Google Messages) carry content the kid never saw.

  Video and audio parts (stored as `Unsupported`) are hidden the same way. So are several text parts that join into
  reaction form: `Likte «Ok` + `»`.
- *Why High.* It breaks the note's own invariant ("never hidden unless attached" assumes the attached row holds nothing
  but the reaction), on the exact question the user asked. Any allowed sender can do it on purpose.
- *Fix:*
  - A message is a reaction candidate only if it is an SMS, or an MMS whose parts are exactly one `text/plain` (plus
    SMIL, which isn't stored).
  - Anything with a picture part (any state), another media part, or more than one text part stays an ordinary
    message.
  - Add `parse`/`match` rows for "picture + reaction text" and "two text parts" → `null`, and an `SmsEngineTest` case.

## Medium

**M1 - The notification path: an attached reaction would be notified with its raw text, and D9's withdrawal takes real
messages' notifications with it.** (D6, D9, §3)
- *Today's mechanism.* `receiveSms` → `afterReceive` → `notifyShown` → `SmsNotifier.notify`. `notify` rebuilds the
  conversation's notification from its unread IN rows `.ifEmpty { listOf(message) }`.
- *If `show()` keeps returning `Received.Shown` for an attached row* (§3 only says "store the row as an attached
  reaction"): the attached row is `read = 1`, so the unread list is empty, and the fallback posts the reaction itself -
  "Likte «Ok»", with sound. D6's "posts no notification" is then false on the first build.
- *D9's "withdrawn the way a moved group MMS's is".* That is `SmsNotifier.cancel(conversationId)`, which cancels the
  whole conversation's notification.
  - Example: Mamma's SMS "Ring meg nå" is unread and notified. A reaction MMS from her that was told while waiting for
    data (`TOLD_STATES`) then attaches. The cancel removes "Ring meg nå" from the shade too.
  - For a group reaction, the row also moves out of the sender's one-to-one, and today's `movedFrom` cancel already
    removes that one-to-one's notification unconditionally. See N7.
- *Fix:*
  - Give the engine a distinct outcome: `Received.Attached(conversation, target)`, which is not `Shown`.
    `afterReceive` and the MMS thread's `notifyShown` don't notify for it.
  - Where a reaction MMS had been told, rebuild the conversation's notification from its remaining unread rows (call
    `notify` without the fallback). Cancel only when none remain.
  - Pin it with a test: "an attached reaction posts nothing, and an unread message's notification survives a
    reaction".

**M2 - "`messages()` keeps returning every row, front end 1 folds them" leaks reactions into every existing reader, and
the seam contradicts itself.** (§3 "The kid's view", D5, D6)
- *The list.* `MessagesActivity` reads each conversation's last message as `engine.messages(id, null, 1)`, not
  `lastMessage`. The newest row is the reaction, so the list shows "Likte «Ok»" as the last line. `lastLine()` gets one
  message and can't skip anything. D6 ("doesn't change its last line") fails.
- *Paging.* `ThreadActivity` loads `PAGE = 100` rows and loads more only on `dy < 0` at position 0. In a busy group
  (every iPhone tapback is a row), a page can be mostly attached rows. Then the few bubbles don't fill the screen, no
  scroll happens, and older messages can never be reached.
- *The scroll.* `show()` scrolls to the bottom when `messages.maxOf { seq }` grows. A reaction to an old bubble yanks a
  kid who is reading higher up down to the bottom. That breaks D6's "nothing moves".
- *"Sendt".* `threadItems`' `lastOut` is the newest OUT row. When that is the kid's reaction (hidden), the "Sendt" or
  "Levert" line disappears from the kid's last real message.
- *The window.* §3 resolves against `store.messages(conversation, null, 100)` "minus reaction rows". Filtering after
  the limit shrinks the window: 100 reactions leave no candidates at all.
- *Contradiction.* §3's `Message` gains `reactions: List<Reaction>` on a target, which the engine would have to fill. In
  the same section, the front end folds raw rows. Both can't be the seam.
- *Fix:* fold in the engine, once.
  - `store.messages()`/`lastMessage()` get `AND reaction_to IS NULL`, which gives the window, the list, paging and
    `lastSeq` in one place.
  - `engine.messages()` attaches each target's `reactions` from `store.reactions(targetIds)` (each person's newest
    effective row; see M4).
  - Events still carry reaction rows for the bridge (`MessageAdded` with `reaction`).
  - Front end 1 then needs no fold. `threadItems`, `deliveryLine` and the scroll see only bubbles. Its `Coalescer`
    reload picks up badges.
  - `MessagesModelTest` then tests the badge model, not a fold.

**M3 - "Exactly one emoji grapheme" admits unbounded and invisible payloads.** (D1, D2, D6)
- *What UAX #29 calls one grapheme:*
  - an arbitrarily long ZWJ chain (`👍‍👍‍👍‍…` is one cluster by GB11);
  - an emoji tag sequence (🏴 + any number of TAG characters U+E0020-E007F + U+E007F). The tags can spell ASCII
    invisibly ("ASCII smuggling");
  - `1`, `#`, `©`, `™` (Emoji=Yes);
  - a lone regional indicator;
  - `❤︎` (U+FE0E, text style).
- *Scenario.* An allowed contact sends `Reacted 🏴<200 tag characters> to “Ok”`. It attaches as a black-flag badge. The
  row holds hidden text the kid can't see or delete (D5), and the bridge later forwards it as an `m.reaction` key.
- *Second scenario.* A 300-emoji ZWJ chain renders as 300 glyphs and blows the chip past the bubble (no layout cap in
  D6).
- *Fix:*
  - "Emoji" means a fully-qualified RGI sequence from a pinned `emoji-test.txt`, or `EmojiCompat`'s
    `getEmojiMatch == EMOJI_SUPPORTED` with an explicit RGI list in the vectors file.
  - Cap it at 16 UTF-16 units. Reject tag sequences other than the RGI subdivision flags.
  - The comparison key strips U+FE0F/U+FE0E (M4); the stored and rendered form adds U+FE0F.
  - Add `parse` rows: a ZWJ chain of 3 non-RGI emoji, a tag-smuggling flag, `1`, `©`, `❤︎` → `null` (or normalised).

**M4 - D4's state machine: removals on repeated texts, failed rows and emoji variants leave stray text and wrong
badges.** (D3, D4, D8)
- *Removal on a repeated text.* D3 resolves a quote to the *newest* equal bubble. Mamma ❤️'d the older of two "Ok"
  bubbles, then sends `Fjernet et hjerte fra «Ok»`. It resolves to the newest "Ok", which has nothing to remove, so it
  stays as a visible text bubble while the old ❤️ stays.
  - *Fix:* a removal resolves among the equal bubbles that carry *this sender's* matching reaction (the newest such).
  - An add on a repeated text keeps "newest", but prefers a bubble not written by the reactor (iMessage users mostly
    react to the other side). Say so, and add a `match` row for each.
- *Failed rows.* `reactions(targetIds)` takes "each person's newest row".
  - The kid had 👍 (SENT) and changes it to ❤️, which goes FAILED: the badge vanishes (D8) although the iPhone still
    shows 👍.
  - The same happens for a failed removal: the badge goes, the iPhone keeps the reaction.
  - *Fix:* FAILED rows are ignored when the effective reaction is computed. UNCERTAIN shows as the pending (dimmed)
    state until it settles, since a late `SENT` "still applies".
- *Emoji variants.* "Removes only if it is the emoji it names" compares strings.
  - Example: `Reacted ❤ to` (no U+FE0F) then `Removed a heart from` (a classic ❤️). This fails and stays text, and the
    badge counts ❤ and ❤️ as two different emoji.
  - *Fix:* compare on the M3 key.

## Low

**L1 - The candidate window must end at the reaction's own `seq`.** (D3, §3 paging claim)
- *The case.* An MMS that isn't moved keeps its push's `seq` (`admit`). Its text can arrive hours later (D14). By then
  the kid may have sent a newer "Ok", and "newest equal" picks a bubble newer than the reaction.
- *Effect.* §3's "a reaction is always newer than its target" is false, and a reaction can name a message sent after
  it.
- *Fix:* candidates are `seq < row.seq`. A moved group MMS gets a fresh seq, so it is unaffected.

**L2 - "A message with a picture" needs a definition.** (D3, D8, §4)
- *Which part states count?* PENDING, WAITING_DATA, NOT_SHOWN, FAILED or only AVAILABLE?
- *The case.* A contact with "Bilder i meldinger" switched off after an earlier picture: the old picture is AVAILABLE,
  and the new one is NOT_SHOWN (D11 keeps a placeholder part). "Liked an image" for the new one attaches to the old one.
  An iPhone often reacts while our fetch is still PENDING, too.
- *Fix:*
  - Uniqueness counts every image part in any state among the newest 10 *non-reaction* rows.
  - It attaches only when that unique one is AVAILABLE or PENDING/WAITING_DATA. The badge then shows on the
    placeholder bubble.
  - The kid's grid offers the image form only for an AVAILABLE picture (L5).

**L3 - Normalise both sides, and pin the cut marker.** (D2, D3)
- *Both sides.* D2 normalises only the reaction body (CRLF → LF, trimmed). A stored target may hold CRLF (an SMS from a
  Windows-ish gateway) or a trailing space the iPhone trims when quoting. Apply the same normalisation to the candidate
  body before comparing.
- *The cut marker.* "Ends in `…`": accept `...` too only if step 0 shows it.
- *Units.* "At least 10 characters" should say code points, not UTF-16 units.

**L4 - The 200-character cut: units, boundaries, and cost.** (D8)
- *Units and boundaries.* 3 UCS-2 parts hold 3 × 67 = 201 UTF-16 units, and an emoji in the quote is two units. Say
  "UTF-16 units". Cut at a grapheme boundary (`BreakIterator.getCharacterInstance`), never inside a surrogate pair or a
  ZWJ sequence.
- *Cost.* A reaction to a long text is 3 SMS, and a group reaction is an MMS. A kid tapping hearts on long messages
  sends paid parts.
  - *Fix:* if step 0 shows an iPhone matches a cut quote, cut to one part (≤ 67 units including the form and `…`).
  - Otherwise keep 200, and add the cost to §10 (N8).

**L5 - `ReactionCommand` through `send` has unstated edges.** (D8, §3)
- *`repeated()`.* It compares `row.body == command.body`. With an engine-composed body, a repeat with the same
  `clientId` would be refused as `DUPLICATE_CLIENT_ID`. Compare the reaction fields instead.
- *`EMPTY`.* It is checked before the body is composed, so compose first.
- *Target checks.* Add to "IN, in that conversation": `verdict = SHOWN`, not itself a reaction row, and an AVAILABLE
  picture for the image form.
- *The refused path.* What does the panel do on `Refused(TIME_RULE)`, for example when a rule starts while the grid is
  open? Proposed: the grid closes, nothing is stored, and today's compose line explains.

**L6 - Released held messages: D10's "new conversation" is wrong, and they shouldn't attach.** (D10)
- *The claim.* `releasePass` → `show` → `conversationFor(address)` reuses an existing one-to-one, for example a removed
  contact's history (D18 keeps it). So a released reaction can attach to an old bubble.
- *The problem.* The parent released what was held so the kid would see it. A release that turns into a badge on a
  weeks-old bubble isn't what the parent saw approved.
- *Fix:* `show()` gets `attach = false` for `releasePass`. Released messages always show as text. `judgeHeldForRules`
  (rules unknown, not a parent decision) may attach.

**L7 - "The check every earlier version step had" doesn't exist.** (§7)
- *Today.* `VERSION_2` and `VERSION_3` were verified by code review (qa-sms-mms N-V1), not by a test. Nothing
  JVM-side opens `SqliteMessageStore`, and there is no Robolectric.
- *Fix:* name a check.
  - A JVM test that the `VERSION_n` lists are append-only (a pinned hash of each earlier list) and that `DB_VERSION`
    equals their count + 1.
  - The in-memory fake's `reactions()` and the delete cascade get the same `SmsEngineTest` coverage as SQLite's (the QA
    L8 parity pattern).
  - Also say what a debug downgrade does: there is no `onDowngrade`, so SQLite throws. That is acceptable, but write it
    down.

**L8 - The bridge/export seam needs three more rows.** (§3 Events, §4)
- *An MMS that becomes a reaction after its `MessageAdded`* (D9). The bridge has already posted a placeholder message,
  so its mapping must be "redact the placeholder, send `m.reaction`". `MessageUpdated` carries only delivery and parts;
  say it means "re-read the row".
- *The cascade (D5).* It emits one `MessageDeleted` per reaction row, so the bridge redacts each.
- *A REMOTE redaction of the kid's own reaction.* This is the kid removing it, i.e. a removal *send*, not `delete()`
  (which D5 forbids for the kid's screen).

**L9 - The kid can't remove an unkind incoming reaction.** (D5, D7)
- *The case.* With iOS 18's any-emoji form, a contact can put 🖕 or 💩 on the kid's bubble. The kid's only way to remove
  it is to delete their own message.
- *Fix (a question for the user, N8):* the long-press panel on a bubble with incoming reactions also offers "Fjern
  reaksjonene" ("Remove the reactions"). It is local only, and deletes those rows with `MessageDeleted`.
  - The alternative: show only the six classic emoji as badges, and iOS 18's others as text.

## Nits

- **N1 - The badge's place and count.**
  - "Lower outer corner" with "bottom right on an incoming bubble" is the *inner* corner for a left-aligned bubble.
    Pick one.
  - `❤️👍 2` contradicts "at most three different emoji, then a count". Say whether the count is the total or the
    rest.
  - Give the bubble a bottom margin so the chip never overlaps the next bubble or the time line, and cap the chip's
    width (M3).
- **N2 - TalkBack.**
  - The badge's words belong in the bubble's existing `contentDescription` (`bindBubble` joins its parts). The chip
    itself is `importantForAccessibility = no`, since it isn't tappable.
  - "❤️ fra Mamma, 👍 fra deg" needs a list format string in `values/` and `values-nb/`.
  - Seven custom actions per bubble are within `ViewCompat`'s 32.
- **N3 - Natural-sentence nb forms.** `Liker ikke lenger «…»`, `Misliker ikke lenger «…»` and `Lo av «…»` are things a
  person writes. The exact-bubble rule (and, for removals, an existing reaction) keeps a false positive rare. Add one
  `parse` + `match` row so it stays deliberate.
- **N4 - A cheap bound.** Don't parse bodies over 2,000 UTF-16 units: no Apple form quoting a real SMS is longer than the
  quote plus about 40.
- **N5 - Wording.** D5's "they remove their own reaction by tapping it (D7)" reads like the badge. It means the panel's
  cell, and D6 says the badge isn't tappable.
- **N6 - The emulator loop.** Add a §6 step to `docs/testing/emulator.md`:
  - `adb emu sms send` with each form;
  - `fake-mmsc.py` with a group text reaction and with H1's picture-plus-reaction;
  - the long-press panel at 288 dp.
- **N7 - Pre-existing, made frequent.** `notifyShown` cancels the sender's one-to-one notification on *every* moved
  group MMS (`movedFrom`), even when that conversation still has unread messages. That is design 77's code QA L8. Every
  iPhone group tapback would now trigger it. Fix it with M1's "rebuild, cancel only when empty", in this round or as a
  backlog item.
- **N8 - Open questions to add.**
  - **Q6.** To a non-iPhone (most Norwegian Android phones, per §1.4), the kid's reaction arrives as the text
    `Likte «…»`. Is that acceptable, or should the grid say "Vises som tekst på Android"?
  - **Q7.** Cost: a reaction is up to 3 SMS, a group reaction an MMS (L4).
  - **Q8.** Should the kid be able to remove others' reactions from their view (L9)? Or should only the six classic
    emoji show as badges?
  - **Q9.** Does the parent want a switch for reactions? If yes, that is a `call_policy` key and a contract change,
    which §6 would then have to carry.
  - Q1-Q5 are the right ones. Q4's fallback (English to everyone) is sensible.
