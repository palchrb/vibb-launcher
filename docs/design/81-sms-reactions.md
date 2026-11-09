# 81 - SMS reactions: the iPhone's "Likte «…»" as a small emoji on the bubble, and the kid reacting back

Status: design note by the architect, 2026-11-09, on `main` at 407f6a4a, branch `design-81-sms-reactions`. **Nothing is
built.** Launcher only; no server, PWA or contract change (§6). Builds on design 77 (the message engine, steps 1-3b
merged) and needs a short capture first (§8 step 0).

**Revision 2, 2026-11-09**, after the design QA (`docs/review/qa-81-design.md`, 930ac367: 0 Critical, 1 High,
4 Medium, 9 Low, 8 Nit). Every finding is fixed or explained, mapped in §11. What changed:
- **Only a message holding nothing but the reaction text can become a reaction:** an SMS, or an MMS with exactly one
  text part and nothing else (H1).
- **An attached reaction is its own engine outcome and never notifies.** A notification it would touch is rebuilt from
  what is still unread, never cancelled (M1).
- **The engine filters reaction rows out of every message read and hangs the badges on the targets, once.** Front ends
  never fold (M2).
- **An emoji is a sequence from a pinned Unicode list, at most 16 UTF-16 units, compared without variation
  selectors (M3).**
- **The replace and remove rules are spelled out:** which bubble a removal names, failed rows ignored, emoji compared
  by key (M4).
- **Released held messages always show as text** (L6).
- **The open questions are merged into five** (§10).
- **Not changed here:** a pre-existing design 77 notification bug the QA found (N7) goes to step 5's fix round (§11).

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
- **Cut marker.** If iOS cuts, does it use `…` (U+2026) or `...`? And does an iPhone match a quote we cut (D8)?

## 2. Decisions

**D1. What is recognised.**
- **Recognised:** the iPhone's six classic Tapbacks, the iOS 18+ emoji form, and their removals, in en (with the
  en_GB/en_AU spellings `Emphasised` and `exclamation mark`) and nb. Each one is recognised on a quoted text and on
  `an image` / `et bilde`.
- **Stays plain text:**
  - stickers, every other noun (`an attachment`, `a Photo Message`, …) and the unquoted `Loved %@` forms;
  - any form whose emoji part fails D2's emoji rule;
  - Google's form, until a capture shows it is sent here and what it looks like. Then it is added as a row in the
    vectors file, with no other change.
- **Swedish and Danish** iPhone forms are in Apple's table too (e.g. `Gillade ”%@”`, `Syntes om “%@”`). They are not
  recognised in v1. Adding them later takes vector rows only.

**D2. Parsing is strict, whole-message, and only for a message that holds nothing else.**
- **Which messages are candidates (H1).** Only messages that hold nothing but text:
  - an SMS;
  - an MMS whose stored parts are exactly one `text/plain` part. SMIL isn't stored, so it doesn't count.

  Anything with a picture part (in any state), a video, audio or other part, or more than one text part, is an
  ordinary message whatever its text says. So a badge never swallows content.
- **Size.** A body over 2,000 UTF-16 units isn't parsed at all (N4). No Apple form quoting a real SMS comes near it.
- **The shape.** A body is a reaction only if it is exactly one of these:
  - a known prefix, an opening quote, the quoted text, and the matching closing quote as the very last character;
  - an exact noun form.

  For example: `Likte «` … `»`, or `Reagerte med ` + one emoji + ` på «` … `»`.
- **Quote pairs.** `“…”`, `"…"`, `«…»` and `”…”` are all accepted with every form, because carriers and old clients
  may change quotes. The exact-target rule (D3) is the safety.
- **What the quoted text may hold.** It may itself contain quotes or line breaks: only the last character must close
  it.
- **Normalising (L3).** The quoted text and every candidate bubble's body get the same normalisation before they are
  compared: CRLF → LF, then the outer whitespace is trimmed. Nothing else changes: no case folding, and no Unicode
  normalisation beyond what the platform already did.
- **The cut marker.** It is `…` (U+2026). `...` is accepted only if step 0 shows iOS uses it.
- **The emoji part of the iOS 18 form (M3):**
  - **Size.** At most 16 UTF-16 units.
  - **Text-style selector.** U+FE0E is dropped first.
  - **The list.** It must then be a sequence listed in a pinned Unicode `emoji-test.txt` (Unicode 16.0), with status
    `fully-qualified`, `minimally-qualified` or `unqualified`, never `component`.
  - **What the list keeps out:** long ZWJ chains, tag sequences other than the three RGI subdivision flags, a lone
    regional indicator, and a bare `1` or `#`. None of these are listed.
  - **Where the list lives.** A launcher asset, `assets/sms/emoji-16.0.txt`: code points and status only, about
    80 KB. It is made by a small script from Unicode's file, whose SHA-256 is pinned the way `yr-symbols.py` pins its
    source. It is read once and kept as a set.
  - **Stored and drawn** in the list's fully-qualified form.
  - **Compared by key:** the emoji without U+FE0F/U+FE0E. So `❤`, `❤︎` and `❤️` are one emoji, and `Removed a heart`
    removes any of them.
- **Natural sentences stay text (N3).** A friend who writes `Likte «filmen»` or `Lo av «det»` gets a reaction only if
  the thread has a bubble that says exactly `filmen` or `det`. The vectors carry such rows on purpose.

**D3. Finding the target bubble.**
- **Where to look.** In the conversation the reaction arrived in (one-to-one or group). The candidates are its newest
  100 bubbles:
  - only shown bubbles that aren't reactions themselves (the filter is in the store query, so reactions never shrink
    the window, M2);
  - only those with a `seq` lower than the reaction's own. An MMS keeps its push's `seq` while its text comes later, so
    without this a reaction could name a newer bubble (L1);
  - from both sides: iMessage lets anyone react to anyone's bubble, their own included.

  This is one indexed range query (`message_conversation`), inside D7's receive budget (design 77).
- **A text target:**
  - **One bubble matches exactly:** that bubble.
  - **Several bubbles say the same words (`Ok`):**
    - an add goes to the newest of them not written by the reactor, or to the newest of them if the reactor wrote
      them all;
    - a removal goes to the newest of them that carries this sender's reaction with that emoji (M4).
  - **None matches, the quote ends in `…`, and it has at least 10 code points before the `…`:** the bubbles whose body
    starts with the part before `…`. If they all have the same text, the same rules as above choose among them.
    Otherwise it is ambiguous.
  - **Otherwise** there is no target.
- **A picture target** (`an image` / `et bilde`, L2):
  - Count every image part, in any state, among the newest 10 candidates.
  - Exactly one picture message, whose picture is `AVAILABLE`, `PENDING` or `WAITING_DATA`: that bubble. An iPhone
    often reacts before our fetch is done, and the badge then sits on the placeholder.
  - None, more than one, or one that is `NOT_SHOWN`, `FAILED`, `TOO_LARGE` or `GONE`: no target.
- **A bubble with a picture and text** is one bubble here. An iPhone shows it as two, so either form names it.
- **No target, or an ambiguous one:** the message stays a plain text bubble, exactly as today. A reaction is never
  hidden unless it attached to something.

**D4. One reaction per person per bubble** (iMessage's own rule), computed from the rows (M4).
- **A person's reaction on a bubble** is their newest add or removal row for it. A removal row cancels it.
- **The kid's own rows:**
  - `FAILED` rows are ignored, so a failed change or removal leaves the earlier reaction showing, as the iPhone still
    shows it;
  - `QUEUED`, `SENDING` and `UNCERTAIN` rows count, drawn as pending (dimmed). A late `SENT` still applies.
- **A new add from the same person** replaces their earlier reaction.
- **A removal** takes away that person's reaction only if the emoji's key matches (D2). A removal with nothing to
  remove stays plain text.

**D5. What is stored.** A reaction is its own `message` row, linked to its target. That keeps:
- the original text for the later export (design 77 §9 step 7 writes it back as the SMS it was);
- an id the later bridge can map to a Matrix `m.reaction` (a removal maps to that reaction's redaction).

The store goes to version 4 (or the next free version when this is built; step 5's `sms-glance` is still at 3), in
`VERSION_4`:
- `ALTER TABLE message ADD COLUMN reaction_to TEXT` (the target's id);
- `reaction_emoji TEXT` (the fully-qualified form);
- `reaction_key TEXT` (D2's key);
- `reaction_op TEXT` (`add`/`remove`);
- `CREATE INDEX message_reaction ON message (reaction_to)`.

How the rows behave:
- **Read and counts.** An attached row is stored `read = 1`. The store's message reads leave attached rows out (M2),
  so they never reach the unread count, the last line or `lastSeq`/`lastAt`.
- **Deleting.** Deleting a target deletes its reaction rows in the same transaction, with one `MessageDeleted` each
  (L8). Deleting a conversation deletes everything.
- **No row of its own for the kid to delete.** The kid changes their own reaction from the panel (D7), not from the
  badge. They can hide others' reactions on their screen only if Q4 says so.
- **A downgrade.** A debug downgrade from version 4 throws, because there is no `onDowngrade`, and a reinstall or
  cleared data is the way back. This is accepted for development builds (L7). Releases only go up.

**D6. What the kid sees.** Calm, in design 34's look; nothing moves or sounds.
- **The badge (N1).**
  - **Where:** a small rounded chip on the bubble's bottom edge, at the corner facing the middle of the screen: bottom
    right on an incoming (left-aligned) bubble, bottom left on the kid's own.
  - **Look:** tile surface `#34324A`, a 1 dp night outline, emoji at 14 sp, never wider than its bubble.
  - **Content:** up to three different emoji, then `+N` for the number of further different ones. In a one-to-one
    there are at most two.
  - **The kid's own reaction** has the peach outline, dimmed while pending (D4).
  - **Spacing:** a bubble with a chip gets a 10 dp bottom margin, so the chip never covers the next bubble or the
    delivery line.
  - There is no animation, and the badge itself isn't tappable.
- **TalkBack (N2).** The reactions are part of the bubble's own `contentDescription` (`bindBubble` already joins its
  parts), as "Reaksjoner: ❤️ fra Mamma, 👍 fra deg" ("Reactions: ❤️ from Mum, 👍 from you"). The words come from
  format strings in `values/` and `values-nb/`. The chip is `importantForAccessibility = no`.
- **Nothing else changes.**
  - An attached reaction posts no notification, adds nothing to the unread count, Home's badge or the lock glance, and
    doesn't move the conversation up the list or change its last line.
  - The thread doesn't scroll for it, because it isn't a bubble (M2).
  - A reaction that didn't attach is an ordinary message and behaves like one.

**D7. The kid reacts: a long press on an incoming bubble.**
- **The panel.** Today a long press opens the in-screen delete confirm (design 77's D18). It now opens a small
  in-screen panel in the same place:
  - a 3 × 2 grid of six emoji: ❤️ 👍 👎 😂 ‼️ ❓ (Q1), each a 56 dp cell. Three cells are 168 dp, which fits the Jelly
    Star's 288 dp width (design 79's H1); one row of six 48 dp targets would not;
  - under the grid, "Slett" ("Delete", today's `messages_delete`), which leads to today's confirm.
- **Choosing.**
  - The kid's current reaction is outlined in peach in the grid. Tapping that cell again removes it; tapping another
    replaces it (D4).
  - The panel closes on a choice, on Back, or on a tap outside it.
- **When the grid shows.** Only when the compose bar would show: `canSend`, the mode is `managed`, and no time rule
  blocks sending. Otherwise the long press goes straight to the delete confirm, as today.
- **Pictures.** On a picture-only bubble the grid shows only if the picture is `AVAILABLE` (L2).
- **The kid's own bubbles** keep today's long press, the delete confirm. iMessage allows reacting to your own bubble;
  it isn't offered here, which keeps the panel to one meaning.
- **TalkBack** gets one custom action per emoji ("Reager med ❤️", "React with ❤️") beside "Slett". Seven actions are
  well within `ViewCompat`'s 32.
- **If the send is refused** (a time rule started while the panel was open, say): the panel closes, nothing is
  stored, and the compose bar's existing line explains (L5).

**D8. What is sent.**
- **The form.** Apple's exact form from §1 in the launcher's language (`resolveLauncherLocale`, the parent's
  `launcher_ui.language` or the phone's): nb gets the nb form, anything else the en (US) form (Q3). The classic verbs
  are used, never `Reacted ❤️ to`, so older iPhones understand them too.
- **A picture without text** gets the `an image` / `et bilde` form.
- **Long texts (L4).**
  - The whole reaction is kept within 200 UTF-16 units, which is 3 UCS-2 parts of 67.
  - A longer quote is cut at a grapheme boundary (`BreakIterator.getCharacterInstance`) and ends in `…`.
  - If step 0 shows an iPhone matches a cut quote, the limit drops to one part: 67 units including the form and `…`.
  - Either way, a cut quote an iPhone can't match still shows there as readable text.
- **These strings are wire forms, not UI strings.** They live in a Kotlin table pinned by the vectors file (§7), never
  in `strings.xml`, so a translation pass can't "improve" them and break the iPhone's parsing.
- **It is an ordinary send.** `SendCommand` gains `reaction: ReactionCommand(target: MessageId, emoji, op, lang)`.
  The engine composes the body itself, so a later bridge sends the same form (L5):
  - **The body is composed first**, then `EMPTY` and `TOO_LARGE` are checked as for any send.
  - **The target must be** in that conversation, IN, `verdict = SHOWN`, not itself a reaction, and with text or an
    `AVAILABLE` picture. The emoji must be one of the six, and a removal must name the kid's current reaction.
  - **A repeated `clientId`** is the same message when the reaction fields (target, emoji, op) match. `repeated()`
    compares those, not the composed body.
  - **The rest is unchanged:** the gate (D5 of design 77), the outbox's second gate, `SENDING` before the platform,
    and `UNCERTAIN`. In a group it goes as a group MMS like any group reply (design 77's D12).
- **On our side the badge shows at once** as pending, and in full from `SENT`. A `FAILED` row drops out (D4): the
  earlier state shows again, and the grid shows it. A reaction isn't retried by itself and gets no "Ikke sendt" line.

**D9. Groups and MMS.**
- **When it is matched.** An incoming reaction in a group, or by MMS, is matched (D2, D3) when its text arrives with
  the fetch (`MmsFlow.onRetrieved`), after the sender and group checks, in the conversation it ended up in.
- **On the seam.** The row becomes a reaction with `MessageUpdated`, which means "re-read the row" (design 77 §6.2).
- **Notifications** follow D12.

**D10. Held and unknown senders.**
- **No change to holding or blocking.** A reaction from an unknown number is just a message: held (design 77's D16)
  with who and when, never what. Blocked senders are blocked as today.
- **Released messages always show as text (L6).** `releasePass` calls `show()` with `attach = false`. The parent let
  in what was held so the kid would see it, and a release that turned into a badge on an old bubble isn't what the
  parent approved. (A released number may reuse an existing conversation, for example a removed contact's history,
  design 77's D18. So it could otherwise attach.)
- **`judgeHeldForRules` may attach.** That is the rules becoming known, not a parent's decision.
- **Matching runs only on a message the engine already let in,** and never across conversations.

**D11. i18n.**
- The UI strings are in `values/` and `values-nb/`: the TalkBack action "Reager med {emoji}" / "React with {emoji}",
  the reactions list format, and "Slett" (which already exists).
- The wire forms follow D8.

**D12. Notifications (M1).**
- **Its own outcome.** The engine gives an attached reaction its own result, `Received.Attached(conversation,
  target)`, not `Received.Shown`. `afterReceive` and the MMS thread's `notifyShown` notify only `Shown`, so an
  attached reaction never reaches `SmsNotifier.notify` and its "unread or else this message" fallback.
- **Rebuilt, not cancelled.**
  - A reaction MMS can be told to the kid before its text arrives (`TOLD_STATES`: waiting for data). If it then
    attaches, the conversation's notification is rebuilt from its remaining unread messages, with no fallback.
  - It is cancelled only when none remain. A real unread message's line never disappears because a reaction attached.
- **Not fixed here (N7).** The same "rebuild, cancel only when empty" is also what design 77's group move needs: today
  `movedFrom` cancels the sender's one-to-one notification on every moved group MMS, even with unread messages left.
  That is a design 77 bug, older than this note (step 3b's code QA L8). It goes to step 5's fix round (§11).

## 3. Where it lives in the engine

| Piece | Kind | Does |
|---|---|---|
| `sms/engine/Reactions.kt` | pure | the forms table (D1); `candidate(row, parts)` (D2's H1 rule); `parse(body): ParsedReaction?` (emoji and key, op, `Quote(text, cut)` or `Image`); `format(emoji, op, target, lang): String` with the grapheme cut; `resolve(parsed, reactor, candidates, existing): String?` (D3, D4) |
| `sms/engine/Emoji.kt` | pure + asset | D2's pinned list, the 16-unit cap, the key |
| `SmsEngine.show(…, attach)` | engine | after the conversation is found and before the insert, in the same transaction: candidate check, parse, resolve against `store.messages(conversation, beforeSeq = own seq, 100)`; returns `Received.Attached` or `Received.Shown` (D12) |
| `SmsEngine.releasePass` / `judgeHeldForRules` | engine | `attach = false` / `true` (D10) |
| `MmsFlow.onRetrieved` | engine | the same once the text is in, after `senderNow`/`sameSender`/the group rule (D9); the notification rebuild (D12) |
| `SmsEngine.send`, `repeated` | engine | `ReactionCommand` (D8) |
| `SmsEngine.messages()` | engine | attaches each target's `reactions` from `store.reactions(targetIds)` (D4) |
| `MessageRow`, `MessageStore`, `SqliteMessageStore`, `FakeMessageStore` | store | the columns and `VERSION_4`; `messages()`, `lastMessage()` and `unread()` with `AND reaction_to IS NULL`; `reactions(targetIds)`; the cascade on delete. The fake keeps the same contract (design 77's QA L8 parity) |
| `Message` (the seam) | model | `reaction: ReactionRef?` (target, emoji, op) on a reaction row as events carry it; `reactions: List<Reaction>` (emoji, key, by: address or `null` for the kid, pending) on a bubble |
| `ThreadActivity`, `MessagesModel` | front end 1 | the chip (D6) from `Message.reactions`, the panel (D7). No fold: `threadItems`, `deliveryLine`, paging, the scroll and the list's last line see only bubbles (M2) |

- **One seam (M2).** The engine is the only place that filters and folds. `messages()` returns bubbles, each with its
  reactions. `MessagesActivity`'s `engine.messages(id, null, 1)` therefore keeps reading the newest real bubble, the
  thread's `PAGE = 100` is 100 bubbles, and `lastOut` keeps the "Sendt" line on the kid's last real message.
- **Events for the bridge (L8).** Events still carry reaction rows:
  - a reaction is `MessageAdded` with its `reaction`, which the bridge maps to `m.reaction`; a removal row redacts it;
  - an MMS that became a reaction after its `MessageAdded` comes as `MessageUpdated` (re-read). The bridge redacts the
    placeholder message it posted and sends the `m.reaction`;
  - the delete cascade emits one `MessageDeleted` per reaction row;
  - a remote removal of the kid's own reaction is a removal *send* (`ReactionCommand(op = remove)`), never `delete()`.
- **Front end 1** reloads on any event through its `Coalescer`, so badges follow.

## 4. Picture, history and export

- **Reacting to a picture** works both ways (D3, D8).
- **Pictures stripped by `mms` off** (D11 of design 77) are `NOT_SHOWN`, so a reaction to one finds no target and stays
  text.
- **The read-only history** (`off`, `unmanaged`) shows badges but no grid.
- **Export (step 7, later)** writes each reaction row back as its original SMS text.

## 5. Look

Design 34's night look, and calm per the launcher look rule: a small chip, no motion, no colour beyond the existing tile
surface and the one peach accent for the kid's own choice. The panel reuses the in-screen confirm's surface and spacing
(`MessagesScreens.showConfirm`), not a new style. Design 78's light mode, when built, gives the chip the light tile
surface; nothing here depends on it.

## 6. Server and contracts

None, unless the user asks for a parent switch (Q5).
- **The server never reads message bodies.** `sms-held` and `sms_state` are unchanged.
- **A reaction is counted as a received or sent message** by the existing counters.
- **No policy key, report key or contract file changes**, so nothing goes into both directories. A parent switch
  would be a `call_policy` key in `sms_contract.json`, in both directories in one commit.

## 7. Tests (lean)

- **`launcher/app/src/test/resources/sms_reactions.json`**, the launcher's alone like `sms_rules.json` (the server never
  sees bodies):
  - `forms`: every row of §1 with its language, op, emoji, noun and its source: `apple` + versions, `wire` when seen,
    `capture` when step 0 confirmed it;
  - `parse`: body → result or `null`. The negatives:
    - `Likte filmen`, a missing closing quote, a sticker, `Loved an attachment`;
    - a ZWJ chain of three emoji outside the list, a tag-smuggling flag, `1`, `#`;
    - a body over 2,000 units.

    `©` and `❤︎` parse and are normalised to `©️` and `❤️`;
  - `candidate` (H1): an SMS, and a one-text MMS → yes; a picture + `Likte «Ok»`, and two text parts
    `Likte «Ok` + `»` → no;
  - `match`:
    - equal texts, for an add (not the reactor's) and for a removal (the bubble carrying it);
    - a cut quote, an ambiguous cut, a bubble newer than the reaction (L1);
    - one picture, two pictures, a `NOT_SHOWN` picture, a `PENDING` picture;
    - a removal with nothing to remove;
    - a natural nb sentence with no matching bubble (N3);
  - `state` (D4): add, replace, remove, a `FAILED` change, an `UNCERTAIN` row, `❤` then `Removed a heart`;
  - `send`: language + emoji + op + target → the exact body, including the grapheme-safe cut.
  - Step 0's captured bodies are added as `parse` rows verbatim.
- **`SmsReactionsTest`** runs every row, and checks that every `send` body parses back to the same reaction. It also
  checks the emoji list's SHA-256.
- **`SmsEngineTest`**, on the fake store with SQLite's contract:
  - an attached reaction returns `Attached`, isn't unread, and isn't in `messages()`;
  - a target's `reactions`;
  - an unread message's notification input survives a reaction (D12);
  - a released held reaction shows as text (L6);
  - a target deleted with its reactions and their `MessageDeleted`s;
  - the send gate refusing a reaction, and a repeated `clientId`;
  - a group MMS reaction attaching when its text arrives, and a picture MMS with reaction text staying a message.
- **The store's version steps (L7).** No JVM test opens SQLite (no Robolectric), and versions 2 and 3 were checked by
  review only. A JVM test pins a hash of each earlier `VERSION_n` list (append-only) and checks
  `DB_VERSION == lists + 1`.
- **`MessagesModelTest`:** the chip model (three emoji and `+N`, the kid's pending one) and the TalkBack line.
- **The emulator loop (N6).** A `docs/testing/emulator.md` §6 entry, written in the build round:
  - `adb emu sms send` with each form;
  - `fake-mmsc.py` with a group text reaction and a picture plus reaction text;
  - the panel at 288 dp.

## 8. Steps and sizes

0. **Capture (S, with the user, no code beyond a debug build on the test phone - never the kid's).** The user's
   iPhone, set to nb and then to en, sends to the Jelly Star with our debug launcher:
   - each of the six Tapbacks on a short text, one iOS 18+ emoji reaction, a change, and a removal;
   - a reaction to a picture, and to a 300-character text;
   - a reaction in a group of three.
   We read the stored bodies from the debug build's `messages.db`. Then back the other way: our nb and en forms sent to
   the iPhone, with a full and a cut quote. Does it show them as Tapbacks, and does it read `Likte et bilde`? Once on
   each SIM we have, to see the quotes survive. If someone has a Norwegian Google Messages phone: does a reaction to an
   SMS send anything? The results go into the vectors file and may change D1, D2's cut marker and D8.
1. **Receive (M):**
   - `Reactions.kt`, `Emoji.kt` and the vectors;
   - store v4 with the filtered reads;
   - the engine's attach, replace and remove, `Attached`, the notification rebuild;
   - the chip, TalkBack, en + nb.
2. **Send (M):** the long-press panel, `ReactionCommand` through the gate, the pending and failed states.

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

Revision 1's Q3 (Swedish and Danish) and Q5 (reacting to one's own bubble) are now decisions with the proposed default
(D1, D7). The QA's Q6-Q9 are folded in below.

1. **Is 👎 among the kid's six?** Proposed yes: the six are exactly the iPhone's, so each shows right on an iPhone.
   Without it, five cells (3 + 2).
2. **Should a reaction to the kid's message notify?** Proposed no: no sound, no notification, no unread count. The
   badge is there next time the thread opens. A silent notification is the alternative.
3. **How the kid's reactions arrive on the other side** (with the QA's Q6 and Q7). Proposed:
   - **Language.** The nb form on an nb phone, unless step 0 shows iPhones don't read Norwegian forms. Then English to
     everyone, because the point is that they show right there. Receiving stays both languages.
   - **Android.** On an Android phone (most of them here, §1.4) the reaction arrives as the text `Likte «…»`. That
     reads as a natural sentence, so the grid gets no extra warning.
   - **Cost.** A reaction to a long text is up to 3 SMS, and in a group it is an MMS. Proposed acceptable. It drops to
     one SMS if step 0 shows an iPhone matches a cut quote (D8).
4. **An unkind reaction on the kid's bubble** (the QA's Q8). With iOS 18 a contact can put any emoji, such as 🖕, on
   the kid's message. Proposed: badges show any listed emoji, and the long-press panel on a bubble with incoming
   reactions also offers "Fjern reaksjonene" ("Remove the reactions"). That hides them on the kid's phone only (the
   rows are deleted, with `MessageDeleted`); the sender isn't told. The alternative: only the six classic emoji become
   badges, and iOS 18's others stay text.
5. **A parent switch for reactions?** (the QA's Q9) Proposed no. Reactions come only from senders the parent already
   allows, and they are messages like any other. A switch would be a `call_policy` key and a contract change in both
   directories (§6).

## 11. The design QA, finding by finding (`docs/review/qa-81-design.md`)

| Finding | What changed |
|---|---|
| H1 MMS with a picture or several texts could attach | D2: only an SMS or an MMS with exactly one text part is a candidate; `candidate` vectors and an engine test (§7) |
| M1 notification of an attached row; the withdrawal cancelled real messages | D12: `Received.Attached` never notifies; a told reaction MMS rebuilds the notification from what's unread, cancels only when empty |
| M2 reaction rows leaking into the list, paging, scroll, "Sendt"; two seams | §3 "One seam": the store's reads filter `reaction_to IS NULL`, `engine.messages()` attaches `reactions`; front ends never fold; the window is filtered in the query (D3) |
| M3 unbounded or invisible emoji | D2: a pinned Unicode 16.0 list, 16 UTF-16 units, U+FE0E dropped, stored fully-qualified, compared by a key without selectors |
| M4 removal on repeated texts, failed rows, emoji variants | D3: a removal names the equal bubble carrying the sender's reaction, an add prefers a bubble not by the reactor; D4: `FAILED` ignored, `UNCERTAIN` pending; keys compared |
| L1 window past the reaction's own seq | D3: candidates have `seq` lower than the reaction's |
| L2 "a message with a picture" undefined | D3: every image part in any state counts; it attaches only to an `AVAILABLE`/`PENDING`/`WAITING_DATA` one; D7: the grid only on an `AVAILABLE` picture |
| L3 normalise both sides, cut marker, units | D2: the same normalisation on both sides, `…` only (unless step 0), 10 code points |
| L4 200-character cut | D8: 200 UTF-16 units, a grapheme-boundary cut, one part if step 0 allows; cost in Q3 |
| L5 `ReactionCommand` edges | D8: compose before `EMPTY`, `repeated()` compares the reaction fields, the target checks; D7: a refused send closes the panel |
| L6 released held messages attaching | D10: `releasePass` shows as text (`attach = false`), the "new conversation" wording corrected |
| L7 no store version check exists | §7: a JVM test pins the `VERSION_n` lists and `DB_VERSION`; fake parity; D5: a debug downgrade throws, accepted |
| L8 bridge and export rows | §3 "Events for the bridge": placeholder redaction, one `MessageDeleted` per cascaded row, a remote removal is a send |
| L9 the kid can't remove an unkind reaction | Q4, with a proposal |
| N1 badge corner and count | D6: the corner facing the middle, three emoji then `+N`, 10 dp margin, never wider than the bubble |
| N2 TalkBack | D6: in the bubble's `contentDescription` with format strings; the chip not important for accessibility; D7: 7 actions |
| N3 natural nb sentences | D2 and §7: deliberate vector rows |
| N4 a cheap bound | D2: bodies over 2,000 UTF-16 units aren't parsed |
| N5 "by tapping it" | D5 and D7: the panel's cell, never the badge |
| N6 the emulator loop | §7: an emulator.md §6 entry in the build round |
| N7 the group move cancels the one-to-one's notification (pre-existing, design 77) | Not fixed in design 81: a design 77 bug (step 3b's code QA L8), for step 5's fix round with the same "rebuild, cancel only when empty" (D12) |
| N8 more questions | Q3 (Q6 + Q7), Q4 (Q8), Q5 (Q9) |
