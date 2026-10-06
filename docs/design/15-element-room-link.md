# 15 - Element X: the Message button opens the chat, not the profile

Found live (2026-10-06): `matrix:u/<mxid>?action=chat` opens the person's **profile**. In Element X (develop),
`DefaultPermalinkParser` makes every user link a `UserLink` (profile + "Message"); `action=chat` is ignored, and
no "open DM" intent exists. Room links (`matrix:roomid/…?via=`, `matrix:r/…`, matrix.to `!…`/`#…`,
`element://room/…`) become `RoomLink` and open the room once joined. `elementx://open/<sessionId>/<roomId>`
(exported VIEW, used by its notifications and shortcuts) opens the room in that session; sessionId = own MXID.

## Design

1. **Server, per device** (a room belongs to this kid's account): `device_contacts.message_room` (normalised
   `!id:server`, `!id` for room v12, or `#alias:server`) + `message_room_via` (JSON, <= 3 server names), and
   `device_policy.own_matrix_id` (`valid_matrix_id`). Calls page: the room field next to the Matrix ID, "This
   phone's Matrix ID" once; auto-save, no scroll jump.
2. **Normalising** (pure, server only, `calls::tests`). Accepts a bare ID or alias,
   `https://matrix.to/#/<id|alias>[/<event>][?via=…]` (percent-decoded once), `matrix:roomid/…`, `matrix:r/…`,
   `element://room/…`, `https://app.element.io/#/room/…`. Drops the event part; keeps <= 3 valid `via` hosts.
   400 (input kept): a user link (`@…`, `matrix:u/`: "That's a person's link - paste the chat's link"), a v12 ID
   without `via`, over 255 characters.
3. **Policy.** `call_policy.own_matrix_id` (str|null) and per contact `message_room` (str|null) +
   `message_room_via` (list), always present; compat both sides (`PolicyResponseCompatTest`,
   `policy_json_keys_snapshot`). `RuleContact` keeps them in `last_call_rules` (CE); the DE boot copy is unchanged.
   No shared vectors: only the server parses; the launcher re-checks the grammar (as `isMatrixId` mirrors
   `valid_matrix_id`).
4. **Launcher** (`resolveMessageButton`, `MessageButtonsTest`). `MessageIntent` gets a URI list; `openMessage` moves
   to the next only on ActivityNotFound/SecurityException.
   - `!` room ID + valid own MXID: `elementx://open/<own mxid>/<room id>` (encoded path segments), then the
     permalink.
   - Other rooms: `matrix:roomid/<id without !>?via=…` or `matrix:r/<alias without #>`, then `element://room/…`.
   - No room: today's user link (profile).
   - A wrong session or an unjoined room can't be detected from here: device check.
5. **PWA help:** "Copy the chat's link from your own Element. Element X: the room name at the top → Share. Element
   Web/Desktop: Settings → Advanced → Internal room ID. This only works for rooms you're in yourself."
6. **Only the contact sheet's Message button.** No in-call or other surface opens chats; `messagingAppPackages`
   (package level) is unchanged.

## Checks

Server: the normaliser table (each form, v12 with/without `via`, user link refused), POST 400s, snapshot.
Launcher: URI choice per case, encoding, fallback order, compat. Jelly Star with Element X as the kid: the elementx
link and the permalink each open the DM; no room opens the profile.

## Open questions

1. For chats you're not in (grandparent ↔ child): should we read Element's dynamic shortcuts as HOME
   (`LauncherApps.getShortcuts`)? Element creates one per room only after the kid sends a message there, and not
   with Element's PIN on. That would report room IDs and names to the server so you can pick one, which tells the
   server who the child chats with. Or should we copy the link from the kid's Element at setup instead?
2. `elementx://` is Element X's internal scheme, not a public API. Is it fine to use it first, with the permalink as
   the fallback?

## QA review (Element X develop: `DefaultDeeplinkParser`, `RootFlowNode`, `NotificationCreator`, `RoomDetailsView`)

1. **High - build the learned variant; drop §1-3 and §5.** Element X hides Share in DMs ("Share CTA should be hidden
   for DMs"), so §5's help fails for the main case, and `own_matrix_id: null` would break the snapshot's non-null
   `call_policy` check. No server, policy or PWA change. A later manual room overrides; until learned: profile.
2. **High - learning rule** (pure, tested): `sbn.packageName == io.element.android.x` (NMS ties it to the poster's
   uid; no signer pin: the button opens this package, so a fake one owns it anyway); `isGroupConversation == false`
   (excludes threads); room = `sbn.tag` in room-ID grammar (`!` + 43 base64url, or `!x:server`; no `|`, a thread tag);
   session = `EXTRA_MESSAGING_PERSON` key (`isMatrixId`); every non-null `sender_person` key equals one phone-book
   contact's Element MXID, else (a `mention-or-reply:` key, another sender) nothing is stored. Not `shortcutId`.
3. **High - privacy invariant.** `NotificationRule.kt` and the listener's KDoc say "no key or tag": rewrite for this
   reader. Own type `ElementDmFacts(tag, selfKey, senderKeys, group)` with an exact-field test; read only each bundle's
   `sender_person` (not `extractMessagingStyleFromNotification`, which copies text); never log; scan-test: no `text`.
4. **Medium - store the session per room, not once.** For an unknown session `setLatestSession` is a no-op and
   `attachSession` waits forever: Element stays on its room list, no exception, no fallback. Store `contact id ->
   (session, room)`, keyed with the contact's MXID; a newer notification replaces it (re-login, multi-account). Drop
   on each accepted rules update when the contact leaves the phone book, stops using Element or changes MXID.
5. **Medium - link and fallback.** Encode each segment fully (`uriEncode`, no `keep`): Element splits `encodedPath` on
   `/` before `URLDecoder`, so a raw `/` splits and a raw `+` becomes a space. Fall back to today's profile link on
   ActivityNotFound/SecurityException; a changed scheme fails silently, so add the elementx open to `smoke-test.sh`.

## Decisions after QA review

- **Build only the learned variant** (QA #1): no server, policy or PWA change; §1-3 and §5 are dropped. Until a room is
  learned, the Message button opens the profile as today.
- Learning rule, privacy invariant, link encoding and fallback exactly as QA #2, #3 and #5.
- **Storage (user, 2026-10-06, refined by QA #4):** the launcher stores only `contact -> (session MXID, room ID)` in CE
  prefs, keyed with the contact's Element MXID - nothing else (no names, no text, no senders that aren't contacts, no
  timestamps), nothing to the server, never logged. A newer notification replaces the pair; the pair is dropped when
  the contact leaves the phone book, stops using Element, or changes MXID.
- Add the elementx open to `scripts/smoke-test.sh` only as a manual/optional step (it needs a learned room on the
  emulator); the unit tests carry the rule.

## Implementation status (2026-10-06)

Built: the learned variant only, launcher-only - no server, policy or PWA change (§1-3 and §5 not built).

- **Rule** (pure, `calls/ElementRooms.kt`, `ElementRoomsTest`): `learnElementRoom(packageName, ElementDmFacts,
  CallPolicyState)` exactly as QA #2 - package `io.element.android.x`, managed rules, `group == false` (a missing
  flag isn't false), tag = room ID (`isElementRoomId`: `!` + 43 base64url, or `!opaque:server`; no `|`, `?`, `#`,
  `&`, spaces; <= 255), session = the messaging person's key (`isMatrixId`), every `sender_person` keyed and the same
  MXID, not the session, and an Element contact of `rules.phoneBook` (outbound - the contacts with a Message
  button). The kid's own messages have no `sender_person` and are skipped; a keyless person, a
  `mention-or-reply:` key, another sender or two contacts learn nothing. Never the shortcut id.
- **Reader** (`badges/ElementDmReader.kt`, called by `BadgeListenerService` for each posted notification and on
  connect): only Element X in our own user; reads `sbn.tag`, `EXTRA_MESSAGING_PERSON`'s key, each `EXTRA_MESSAGES`
  bundle's `sender_person` key (not `MessagingStyle`'s parser) and `EXTRA_IS_GROUP_CONVERSATION`. `ElementDmFacts`
  has exactly `tag`, `selfKey`, `senderKeys`, `group` (exact-field test); a source scan forbids text/title/name
  reads in the reader and the listener, any other extra or bundle key, and any `Log` in the reader, the rule and
  the store. The listener's and `NotificationRule.kt`'s privacy KDoc now name this reader.
- **Storage** (`calls/ElementRoomStore.kt`): own CE prefs file `element_rooms`, one JSON map contact MXID ->
  `{session, room}` (`ElementRoom`, exact-field test) - nothing else; a newer notification replaces the pair.
  `CallPolicyStore.refresh` prunes it on every CE refresh (after every accepted sync): managed -> only phone-book
  contacts still on Element with the same MXID; unmanaged -> empty; unknown rules -> unchanged. Never the DE copy,
  the status report or a log (a test checks `server/`, `push/` and `CallStateReport.kt` never mention it).
- **Button** (`resolveMessageButton(..., learnedRoom)`, `MessageButtonsTest`): `MessageIntent` holds a URI list;
  with a learned room `elementx://open/<session>/<room>` (`elementRoomUri`, each segment fully `uriEncode`d - the
  live link is a test vector) comes first, then today's `matrix:u/…?action=chat` and `element://user/…`.
  `ContactSheet.openMessage` moves on only when starting one throws (ActivityNotFound/SecurityException). An
  invalid stored pair gives no link. No new strings.
- **Smoke test**: optional step with `ELEMENT_SESSION` + `ELEMENT_ROOM` (starts the encoded link, checks Element X
  comes up, screenshot for "the DM, not the room list"), plus a printed manual step for the Message button
  (`docs/testing/emulator.md` §5b).

Device checks (not done here): on the Jelly Star with Element X as the kid, a DM from a phone-book contact makes
Message open that DM; before any DM it opens the profile; a contact removed or moved to another MXID falls back to
the profile after the next sync; Element X signed out (unknown session) - Element stays on its room list (known,
QA #4) until the next DM notification replaces the pair.
