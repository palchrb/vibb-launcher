# 81 - SMS reactions: the iPhone's "Likte «…»" as a small emoji on the bubble, and the kid reacting back

Status: design note by the architect, 2026-11-09, on `main` at 407f6a4a, branch `design-81-sms-reactions`. **Nothing is
built.** Launcher only; no server, PWA or contract change (§6). Builds on design 77 (the message engine, steps 1-3b
merged) and needs a short capture first (§8 step 0).

The user's words (2026-11-09, "Ja!" to the proposal): recognise the reaction texts iPhones (iMessage falling back to
SMS, "Tapback") and Google Messages send over SMS - e.g. `Likte «…»`, `Reagerte med 👍 på «…»`, `Liked "…"`,
`Reacted 👍 to "…"` - and show them as a small emoji on the bubble they name in the kid's Meldinger thread; unknown
forms stay plain text. The kid can react by a long press on a bubble, sent as plain SMS text in the same form, so it
shows right on the other end.

Markers:
- **[A]** Apple's own string table: `IMSharedUtilities.framework/IMSharedUtilities.loctable`, the strings iOS uses for
  a Tapback's text form, read for iOS 15.8, 16.7, 17.7, 18.7, 26.7 and 27.0 through applelocalization.com's read-only
  API (`/api/ios/{major}/search/advanced?c=key&o=equal&q=…&locale=en&locale=en_GB&locale=no`). Apple's Norwegian
  Bokmål locale is `no`. Identical in every one of those versions unless the table says otherwise.
- **[W]** seen on the wire: a real SMS shown in a public screenshot or report.
- **[C]** needs a capture from a real phone (step 0, §8). Nothing marked [C] is treated as confirmed.

## 1. The forms

### 1.1 What an iPhone sends: adding a reaction to a text

`%@` is the quoted text. English uses curly quotes `“ ”` (U+201C/U+201D), Norwegian `« »` (U+00AB/U+00BB).

| Tapback | Shown here as | en (US) | en_GB / en_AU where different | nb (`no`) | Source |
|---|---|---|---|---|---|
| Heart | ❤️ | `Loved “%@”` | | `Satte et hjerte ved «%@»` | [A] all; nb also [W] |
| Thumbs up | 👍 | `Liked “%@”` | | `Likte «%@»` | [A] |
| Thumbs down | 👎 | `Disliked “%@”` | | `Mislikte «%@»` | [A] |
| Ha ha | 😂 | `Laughed at “%@”` | | `Lo av «%@»` | [A] |
| !! | ‼️ | `Emphasized “%@”` | `Emphasised “%@”` | `Satte utropstegn ved «%@»` | [A] |
| ? | ❓ | `Questioned “%@”` | | `Satte spørsmålstegn ved «%@»` | [A] |
| Any emoji (iOS 18+) | that emoji | `Reacted %@ to “%@”` | | `Reagerte med %@ på «%@»` | [A] iOS 18, 26, 27; absent in 15-17 |
| A sticker (iOS 18, 26) | - (plain text) | `Reacted with a sticker to “%@”` | | `Reagerte med et klistremerke på «%@»` | [A] iOS 18, 26; not in the 27.0 table |

[W]: tek.no (2021-11-23) shows a real SMS reading `Satte et hjerte ved «Vanlig SMS»`, Apple's nb string exactly. The
QKSMS issue #1330 quotes `Liked "Let's eat ice cream"` with straight quotes. That was typed into a comment, so it
doesn't prove what goes on the wire. No public capture of the English curly quotes was found: **[C]**.

### 1.2 Removing one

| Removes | en (US) | en_GB where different | nb |
|---|---|---|---|
| ❤️ | `Removed a heart from “%@”` | | `Fjernet et hjerte fra «%@»` |
| 👍 | `Removed a like from “%@”` | | `Liker ikke lenger «%@»` |
| 👎 | `Removed a dislike from “%@”` | | `Misliker ikke lenger «%@»` |
| 😂 | `Removed a laugh from “%@”` | | `Fjernet latter fra «%@»` |
| ‼️ | `Removed an exclamation from “%@”` | `Removed an exclamation mark from “%@”` (iOS 15-26; 27.0 dropped "mark") | `Fjernet et utropstegn fra «%@»` |
| ❓ | `Removed a question mark from “%@”` | | `Fjernet et spørsmålstegn fra «%@»` |
| Any emoji (iOS 18+) | `Removed %@ from “%@”` | | `Fjernet %@ fra «%@»` |

All [A]. None of them has been seen on the wire: [C]. That includes whether iOS sends a removal over SMS at all.

### 1.3 A reaction to a picture or another attachment

The target is a noun instead of a quote:
- **A picture:** `Loved an image`, `Liked an image`, `Disliked an image`, `Laughed at an image`, `Emphasized an image`
  (en_GB `Emphasised an image`), `Questioned an image`. In nb: `Satte et hjerte ved et bilde`, `Likte et bilde`,
  `Mislikte et bilde`, `Lo av et bilde`, `Satte utropstegn ved et bilde`, `Satte spørsmålstegn ved et bilde`.
- **Removing it:** `Removed a heart from an image` etc. In nb: `Fjernet et hjerte fra et bilde`,
  `Liker ikke lenger bildet`, `Misliker ikke lenger bildet`, `Fjernet latter fra et bilde`,
  `Fjernet et utropstegn fra et bilde`, `Fjernet et spørsmålstegn fra et bilde`. The two "ikke lenger" ones say
  "bildet", not "et bilde".
- **The emoji form:** `Reacted %@ to an image` / `Reagerte med %@ på et bilde` and `Removed %@ from an image` /
  `Fjernet %@ fra et bilde` are in the iOS 18 and 26 tables, but not in 27.0's.
- **Other nouns** also exist: `an attachment` / `et vedlegg`, `a Photo Message` / `en bildemelding`, `a movie`,
  `an audio message`, `a location`, `a contact`, `an event`, Wallet and Digital Touch.

All [A]. Which noun iOS picks for a picture that came by MMS is [C]. AirMessage issue #50 guessed `an image` and
`a video`, but `a video` isn't in Apple's table.

### 1.4 Google Messages

- **The format.** 9to5Google (2022-09-29/30) says an SMS reaction from Google Messages reaches a recipient without
  RCS as the emoji followed by the message it names, in quotes: "[thumbs up emoji] to [original message]". The
  article shows no exact characters, so whether there is a "Reacted" word, which quotes are used, and whether the
  wording is localised are all **[C]**.
- **The emoji set.** Google offers seven: 👍 😍 😂 😮 😥 😠 👎.
- **Not offered in Norway.** Google's own help page (answer 9827088, en and no) says: "Reactions for SMS and MMS
  messages are available in the USA, France, Canada, and Australia." Its "Show iPhone reactions as emoji" is "only
  available in the U.S. on text messages sent in English".
- **So** a Norwegian Google Messages user most likely sends nothing over SMS when reacting to the kid's message (D1).

### 1.5 What only a capture can tell (step 0)

- **Quotes.** Are the English quotes curly on the wire? Does a carrier's forced 7-bit encoding (Android's
  `Sms7BitEncodingTranslator`) turn `“ ” « »` into `"` or `<< >>` on Telia, Telenor or Ice? Neither curly quotes nor
  `« »` are in GSM-7, so a reaction always goes as UCS-2, 70 characters per SMS.
- **Long messages.** Does iOS quote a long message in full or cut it with `…`? Reports say "the entire message";
  none mention a cut.
- **Order and duplicates.** Does iOS send a removal, or a removal plus an add when the reaction is changed?
- **What iOS 16+ understands.** iOS 16 and later shows incoming reaction texts as Tapbacks (9to5mac, 2022-06-07).
  Does it read our Norwegian forms, or only English? Does it read `Likte et bilde`?

## 2. Decisions

**D1. What is recognised.**
- **Recognised:** the iPhone's six classic Tapbacks, the iOS 18+ emoji form, and their removals, in en (with the
  en_GB/en_AU spellings `Emphasised` and `exclamation mark`) and nb. Each one is recognised on a quoted text and on
  `an image` / `et bilde`.
- **Stays plain text:**
  - stickers, every other noun (`an attachment`, `a Photo Message`, …) and the unquoted `Loved %@` forms;
  - any form whose emoji part isn't exactly one emoji grapheme;
  - Google's form, until a capture shows it is sent here and what it looks like. Then it is added as a row in the
    vectors file, with no other change.
- **Swedish and Danish** iPhone forms are in Apple's table too (e.g. `Gillade ”%@”`, `Syntes om “%@”`) but aren't
  recognised in v1 (Q3).

**D2. Parsing is strict and whole-message.**
- **The shape.** A body is a reaction only if it is exactly a known prefix, an opening quote, the quoted text, and
  the matching closing quote at the very end (or the exact noun form). For example: `Likte «` … `»`, or
  `Reagerte med ` + one emoji + ` på «` … `»`.
- **Quote pairs.** `“…”`, `"…"`, `«…»` and `”…”` are all accepted with every form, because carriers and old clients
  may change quotes. The exact-target rule (D3) is the safety.
- **What the quoted text may hold.** It may itself contain quotes or line breaks: only the last character must close
  it.
- **What is compared.** Bodies are compared after CRLF → LF and trimming outer whitespace. Nothing else is changed:
  no case folding and no Unicode normalisation beyond what the platform already did.
- **What a human types stays text.** A friend who writes `Likte «filmen»` gets a reaction only if the thread has a
  bubble that says exactly `filmen`.

**D3. Finding the target bubble.**
- **Where to look.** In the conversation the reaction arrived in (one-to-one or group): its newest 100 shown messages
  that aren't reactions themselves, from both sides. iMessage lets anyone react to anyone's bubble, their own
  included. 100 is a short indexed query, inside D7's receive budget (design 77).
- **A text target:**
  - one bubble's body equals the quoted text exactly: that bubble;
  - several bubbles say the same words (`Ok`): the newest of them. The kid sees the same words either way;
  - none equal it, the quote ends in `…` and is at least 10 characters long: the bubbles whose body starts with the
    part before `…`. If they all have the same text, the newest one; otherwise it is ambiguous;
  - otherwise there is no target.
- **A picture target** (`an image` / `et bilde`): the only message with a picture among the conversation's newest 10.
  None, or more than one, means no target.
- **A bubble with a picture and text** is one bubble here. An iPhone shows it as two, so either form names it.
- **No target, or an ambiguous one:** the message stays a plain text bubble, exactly as today. A reaction is never
  hidden unless it attached to something.
- **A reaction that arrives before the bubble it names** (out-of-order SMS, rare) stays plain text.

**D4. One reaction per person per bubble** (iMessage's own rule).
- A new reaction from the same person replaces their earlier one.
- A removal takes away that person's reaction only if it is the emoji it names (`Removed a heart` → ❤️). A removal
  with nothing to remove stays plain text (D3's "never hidden unless attached").

**D5. What is stored.** A reaction is its own `message` row, linked to its target, so that:
- the original text stays for the later export (design 77 §9 step 7 writes it back as the SMS it was);
- the later bridge maps it to a Matrix `m.reaction`, and a removal to that reaction's redaction.

Store version 4 (or the next free version when this is built), in `VERSION_4`:
`ALTER TABLE message ADD COLUMN reaction_to TEXT` (the target's id), `reaction_emoji TEXT`,
`reaction_op TEXT` (`add`/`remove`), and `CREATE INDEX message_reaction ON message (reaction_to)`.
- **Hidden rows.** An attached row is stored `read = 1`. `unread`, `lastMessage` and the conversation's
  `lastSeq`/`lastAt` skip attached reactions.
- **Deleting.** Deleting a target (D18) deletes its reaction rows in the same transaction, and deleting a conversation
  deletes everything. The kid can't delete a reaction row on its own; they remove their own reaction by tapping it
  (D7).

**D6. What the kid sees.** Calm, in design 34's look; nothing moves or sounds.
- **The badge.** A small rounded chip on the bubble's lower outer corner (bottom right on an incoming bubble, bottom
  left on the kid's own): tile surface `#34324A` with a 1 dp night outline, emoji at 14 sp.
  - It holds at most three different emoji, then a count (`❤️👍 2`). The kid's own reaction has the peach outline.
  - There is no animation, and the badge isn't tappable.
  - TalkBack reads it as "❤️ fra Mamma, 👍 fra deg" ("❤️ from Mum, 👍 from you").
- **Nothing else changes.**
  - A reaction posts no notification, adds nothing to the unread count, Home's badge or the lock glance (step 5), and
    doesn't move the conversation up the list or change its last line (Q2).
  - A reaction that didn't attach is an ordinary message and behaves like one.

**D7. The kid reacts: a long press on an incoming bubble.**
- **The panel.** Today a long press opens the in-screen delete confirm (D18). It now opens a small in-screen panel
  in the same place:
  - a 3 × 2 grid of six emoji: ❤️ 👍 👎 😂 ‼️ ❓, each a 56 dp cell. Three cells are 168 dp, which fits the Jelly
    Star's 288 dp width (design 79's H1); one row of six 48 dp targets would not;
  - under the grid, "Slett" ("Delete", today's `messages_delete`), which leads to today's confirm.
- **Why these six.** They are exactly the iPhone's classic Tapbacks, so every iPhone since iOS 10 has a word for them
  (D8), and none of them is new to the kid.
- **Choosing.**
  - The kid's current reaction is outlined in peach. Tapping it again removes it; tapping another replaces it (D4).
  - The panel closes on a choice, on Back, or on a tap outside it.
- **When the grid shows.** Only when the compose bar would show: `canSend`, the mode is `managed`, and no time rule
  blocks sending. Otherwise the long press goes straight to the delete confirm, as today.
- **The kid's own bubbles** keep today's long press, the delete confirm (Q5).
- TalkBack gets one custom action per emoji ("Reager med ❤️", "React with ❤️") beside "Slett".

**D8. What is sent.**
- **The form.** Apple's exact form from §1 in the launcher's language (`resolveLauncherLocale`, the parent's
  `launcher_ui.language` or the phone's): nb gets the nb form, anything else the en (US) form. The classic verbs are
  used, never `Reacted ❤️ to`, so older iPhones understand them too.
- **A picture without text** gets the `an image` / `et bilde` form.
- **Long texts.**
  - The quote is the target's full text if the reaction then stays within 200 characters (3 UCS-2 SMS parts).
  - Otherwise it is the first characters up to that length, then `…`. Whether an iPhone matches a cut quote is [C];
    if it doesn't, the iPhone shows our text as text, which is still readable.
- **These strings are wire forms, not UI strings.** They live in a Kotlin table pinned by the vectors file (§7), never
  in `strings.xml`, so a translation pass can't "improve" them and break the iPhone's parsing.
- **It is an ordinary send.** `SendCommand` gains `reaction: ReactionCommand(target: MessageId, emoji, op, lang)`. The
  engine composes the body itself, so a later bridge sends the same form. Everything else is unchanged:
  - the gate (D5 of design 77) applies as for any send, the `clientId` dedup and outbox too;
  - in a group it goes as a group MMS like any group reply (D12).
- **On our side the badge shows at once.**
  - It is dimmed while `QUEUED`/`SENDING`, and full from `SENT`.
  - On `FAILED` or `UNCERTAIN` the badge goes and the panel shows nothing chosen. A reaction isn't retried by
    itself, and gets no "Ikke sendt" line.

**D9. Groups and MMS.**
- **An incoming reaction in a group** is an MMS whose text arrives with its fetch. It is matched (D3) when the text
  arrives, in the group's conversation:
  - the row becomes a reaction with `MessageUpdated`, as design 77 §6.2 already tells consumers to re-read;
  - its notification is withdrawn the way a moved group MMS's is (D12).
- **The badge** shows whose reaction it is only through TalkBack, as in D6. The members' names are already in the
  thread.

**D10. Held and unknown senders: no change.**
- **Held for the parent.** A reaction from an unknown number is just a message: held (D16) with who and when, never
  what. If the parent lets the sender in, it is released into a new conversation, finds no target there, and shows
  as plain text.
- **Blocked** senders are blocked as today.
- **Matching runs only on a message the engine already let in** (shown, released, or judged after `HELD_RULES`). It
  never looks across conversations.

**D11. i18n.**
- The UI strings (the TalkBack labels, "Reager med {emoji}" / "React with {emoji}"; "Slett" already
  exists) are in `values/` and `values-nb/`.
- The wire forms follow D8.

## 3. Where it lives in the engine

| Piece | Kind | Does |
|---|---|---|
| `sms/engine/Reactions.kt` | pure | the forms table (D1); `parse(body): ParsedReaction?` (emoji, op, `Quote(text, cut)` or `Image`); `format(emoji, op, target, lang): String`; `resolve(parsed, candidates): String?` (D3) |
| `SmsEngine.show()` | engine | after the conversation is found and before the insert, in the same transaction: parse, resolve against `store.messages(conversation, null, 100)` (minus reaction rows), store the row as an attached reaction or as a plain message |
| `SmsEngine.releasePass` / `judgeHeldForRules` | engine | already go through `show()`: nothing new |
| `MmsFlow` (an IN MMS's text arrives) | engine | the same parse and resolve on the group or one-to-one conversation (D9) |
| `SmsEngine.send` | engine | `ReactionCommand` checks: the target is in that conversation, IN, and has text or a picture; the emoji is one of the six; then `format` and the ordinary send path |
| `MessageRow`, `MessageStore`, `SqliteMessageStore` | store | the three columns, `VERSION_4`, `reactions(targetIds)` (each person's newest row per target, a removal cancelling), the skips in D5, the cascade on delete |
| `Message` (the seam) | model | `reaction: ReactionRef?` on a reaction row (target, emoji, op), `reactions: List<Reaction>` (emoji, by: address or `null` for the kid, pending) on a target |
| `MessagesModel.threadItems`, `lastLine` | front end 1 | fold reaction rows into their targets; the last line skips them |
| `ThreadActivity` | front end 1 | the badge (D6), the long-press panel (D7) |

- **Events.** A reaction row is `MessageAdded` like any message and carries its `reaction`. Front end 1 already
  reloads on events (`Coalescer`), so the target's badge follows. The bridge's mapping (design 77 §6.2's table) gains
  one row: a reaction is `m.reaction`, and a removal redacts it. No new event type.
- **The kid's view.** `messages()` keeps returning every row, and front end 1 folds them. Paging can't lose a badge:
  a reaction is always newer than its target, so a loaded target has all its reactions loaded.

## 4. Picture, history and export

- **Reacting to a picture** works both ways (D3, D8).
- **Pictures stripped by `mms` off** (D11 of design 77) have no picture to name: a reaction to one finds no target
  and stays text.
- **The read-only history** (`off`, `unmanaged`) shows badges but no panel grid.
- **Export (step 7, later)** writes each reaction row back as its original SMS text.

## 5. Look

Design 34's night look, and calm per the launcher look rule: a small chip, no motion, no colour beyond the existing
tile surface and the one peach accent for the kid's own choice. The panel reuses the in-screen confirm's surface and
spacing (`MessagesScreens.showConfirm`), not a new style. Design 78's light mode, when built, gives the chip the light
tile surface; nothing here depends on it.

## 6. Server and contracts

None.
- **The server never reads message bodies.** The held post (`sms-held`) and `sms_state` are unchanged.
- **No policy key, report key or contract file changes**, so nothing goes into both directories.
- **New counters?** A reaction is counted as a received or sent message like any other. A separate counter would be
  a `sms_state` change, which isn't proposed.

## 7. Tests (lean)

- **`launcher/app/src/test/resources/sms_reactions.json`**, the launcher's alone like `sms_rules.json` (the server
  never sees bodies):
  - `forms`: every row of §1 with its language, op, emoji, noun and its source: `apple` + versions, `wire` when seen,
    `capture` when step 0 confirmed it;
  - `parse`: body → result or `null`, with negatives (`Likte filmen`, a missing closing quote, two emoji, a sticker,
    `Loved an attachment`);
  - `match`: a small thread plus a reaction → the target's index or `null` (equal texts, a cut quote, ambiguity, one
    picture, two pictures, a removal with nothing to remove);
  - `send`: language + emoji + op + target → the exact body, including the cut at 200.
  - Step 0's captured bodies are added as `parse` rows verbatim.
- **`SmsReactionsTest`** runs every row, and checks that every `send` body parses back to the same reaction.
- **`SmsEngineTest`** (the in-memory fake store):
  - a reaction attaches and isn't unread;
  - a replace, then a removal;
  - a target deleted with its reactions;
  - a held sender's reaction released as text;
  - the send gate refusing a reaction like any message;
  - a group MMS reaction attaching when its text arrives.
- **`MessagesModelTest`:** the fold and the last line.
- **The store's version 4 upgrade** gets the check every earlier version step had.

## 8. Steps and sizes

0. **Capture (S, with the user, no code beyond a debug build on the test phone - never the kid's).** The user's
   iPhone, set to nb and then to en, sends to the Jelly Star with our debug launcher:
   - each of the six Tapbacks on a short text, one iOS 18+ emoji reaction, a change, and a removal;
   - a reaction to a picture, and to a 300-character text;
   - a reaction in a group of three.
   We read the stored bodies from the debug build's `messages.db`. Then back the other way: our nb and en forms sent
   to the iPhone. Does it show them as Tapbacks, and does it read `Likte et bilde`? Once on each SIM we have, to see
   the quotes survive. If someone has a Norwegian Google Messages phone: does a reaction to an SMS send anything? The
   results go into the vectors file and may change D1 and D8.
1. **Receive (M):** `Reactions.kt` and the vectors, store v4, the engine's attach, replace and remove, the badge, the
   fold, en + nb.
2. **Send (M):** the long-press panel, `ReactionCommand` through the gate, the pending and failed badge, TalkBack.

Steps 1 and 2 can ship in one round. Queued after design 77's open steps (5 and 6), as PLAN's entry says.

## 9. Sources

- Apple, `IMSharedUtilities.loctable` (iOS 15.8.8 to 27.0.1), via https://applelocalization.com (API at
  `/api/ios/{major}/search/advanced`, documented at `/skills/apple-localization/references/api.md`). Read 2026-11-09.
- tek.no, 2021-11-23, "Google gjør det enklere å motta iMessage-meldinger på Android-telefoner": the SMS
  `Satte et hjerte ved «Vanlig SMS»`.
- QKSMS issue #1330 (github.com/moezbhatti/qksms/issues/1330): `Liked "Let's eat ice cream"`, typed by a user.
- AirMessage issue #50 (github.com/airmessage/airmessage-android/issues/50): a proposed `^Loved (".*"|an image|a video)$`.
- 9to5Google, 2022-09-29 and 2022-09-30: Google's SMS reaction fallback "[emoji] to [original message]" in quotes; the
  seven emoji.
- Google Messages help, answer 9827088 (hl=en, hl=no): SMS/MMS reactions only in the USA, France, Canada and
  Australia; "Show iPhone reactions as emoji" only in the U.S., in English.
- 9to5mac, 2022-06-07: iOS 16 shows incoming reaction texts as Tapbacks.

## 10. Open for the user (Q1-Q5)

1. **Is 👎 in the kid's six?** Proposed yes: the six are exactly the iPhone's, so each shows right on an iPhone.
   Without it, five cells (3 + 2).
2. **Should a reaction to the kid's message notify?** Proposed no: no sound, no notification, no unread count. The
   badge is there next time the thread opens. A silent notification is the alternative.
3. **Swedish and Danish iPhones?** Apple's forms are in the same table, so they are cheap to add. Proposed: not now,
   only on a real need.
4. **What if step 0 shows iPhones don't read our Norwegian forms?** Proposed: then the kid's reactions are sent in
   English even on an nb phone, because the point is that they show right on the other end. Receiving stays both
   languages.
5. **May the kid react to their own messages?** iMessage allows it. Proposed no: the long press on the kid's own
   bubble stays the delete confirm only.
