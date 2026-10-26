# Starting an Element X call from the contact card (2026-10-26)

The user asked how big an upstream PR would be for Vibb One's contact card to start an Element X call in a given room
through an intent, and whether it is something Element would want to maintain. Read: `element-hq/element-x-android`
at `d2f96bcb` (2026-10-26), ruma's `matrix_uri.rs` and the Rust SDK's `bindings/matrix-sdk-ffi/src/ruma.rs` (main,
`2d6248ed`), the spec's appendix (v1.19), and the issue trackers listed in §5. Paths below are in the Element X tree.

## Summary

| Question | Answer |
|---|---|
| Can another app start (or join) a call in Element X today? | **No.** Only `MainActivity` is exported; its VIEW links open a room or a profile, never a call. `ElementCallActivity` is not exported and takes a `Parcelable` it can't get from another app. |
| Can another app open the room today? | **Yes**: `elementx://open/<session>/<room>` (what Vibb One uses since design 15) and `matrix:roomid/<id>`. `matrix:u/<mxid>?action=chat` opens the contact's **profile**, which has big "Call"/"Video" buttons when a DM exists. |
| Telecom (`placeCall`) route? | **No.** Element X registers no `PhoneAccount`/`ConnectionService` at all, and another app can't place a call on a self-managed account anyway. |
| Smallest upstream-worthy PR | `matrix:roomid/<id>?action=call`: the deep-link layer opens the room and shows a "Call Mamma? Voice / Video" confirmation; one tap starts it. **Size M**: ~120-160 lines of code, ~80-100 of tests, 9-12 files. |
| Chance upstream takes it | Moderate (~40-50 %) - the `matrix:` route is spec-shaped and reuses existing pieces, but `call` is not a spec action; expect "MSC first" or "needs design". No auto-start without a tap. |
| Meanwhile | A "Call" button for Element contacts that sends `matrix:roomid/<room>?action=call` (opens the room on today's Element X, forward-compatible) or the profile link (one big Call button). Two taps either way - the PR saves **no** tap, it gives a clearer screen. |

## 1. Today: Element X's exported entry points

- **One exported activity**: `app/src/main/AndroidManifest.xml` L54-165, `MainActivity` (`exported="true"`, singleTask).
  Its filters: LAUNCHER; VIEW `elementx://open` (L68-74, DEFAULT only - the notification link, not BROWSABLE but
  reachable by any app); the OAuth redirect (L78-85); `https://app|develop|staging.element.io` (L89-103);
  `https://mobile.element.io/element/` (L108-118, login provisioning); VIEW `matrix:` (L122-129, BROWSABLE);
  `element://user` and `element://room` (L133-142); SEND/SEND_MULTIPLE (L150-164); the share-target shortcuts
  (`res/xml/shortcuts.xml`). **No `matrix.to` filter** (removed on purpose, #6345 below).
- **Call components are internal**: `features/call/impl/src/main/AndroidManifest.xml` L31-56 - `ElementCallActivity`
  has no `exported` and no intent-filter (so not exported), `IncomingCallActivity`, `CallForegroundService` and
  `DeclineCallBroadcastReceiver` are `exported="false"`. `ElementCallActivity.setCallData` (L242-244) reads a
  `CallData` Parcelable (`EXTRA_CALL_TYPE`, built by `utils/IntentProvider.kt` L20-23) - a device owner can't start a
  non-exported activity of another app, and the Parcelable wouldn't unmarshal across apps. No `?call=` parameter, no
  `call.element.io` filter any more (`tools/adb/callLinkHttps.sh` is a leftover).
- **Routing**: `appnav/.../intent/IntentResolver.kt` L41-75 tries the `elementx://open` deep link
  (`libraries/deeplink/impl/.../DefaultDeeplinkParser.kt` L25-47: session, room, thread, event - nothing else), OAuth,
  the login link, then `PermalinkParser`, then share. `RootFlowNode.kt` L566-580 dispatches; a `RoomLink` attaches the
  room (`attachPermalinkData` L659-682), a `UserLink` the profile.
- **`matrix:` parsing** (`libraries/matrix/impl/.../permalink/DefaultPermalinkParser.kt` L44-91): the URI goes to the
  SDK's `parseMatrixEntityFrom`, which returns only `id` and `via` (FFI `ruma.rs` L142-158) - **the `action` is
  dropped**. ruma's `MatrixUri::parse` accepts any `action` value (`UriAction::from`: `join`, `chat`, else `_Custom`)
  but refuses any other unknown query key (`UnknownQueryItem`, `matrix_uri.rs` L487-497); matrix.to refuses any
  argument but `via` (L313-316). So **today `matrix:roomid/abc:example.org?action=call` opens the room** and the action
  is ignored; `?io.element.call=1` (a namespaced custom parameter, which the spec allows) would fail to parse and do
  nothing.
- **How a call starts inside the app**: the room's top bar `timeline/components/CallMenuItem.kt` L64-91 (voice icon only
  in DMs, video always, enabled by `canStartCall` = permission + Element Call available + not an empty DM,
  `RoomCallStatePresenter.kt` L58-66) -> `MessagesFlowNode.navigateToRoomCall` (L352, L680) ->
  `ElementCallEntryPoint.startCall(CallData(session, room, isAudioCall))` (`DefaultElementCallEntryPoint.kt` L43-56;
  the experimental `NativeCall` flag, `FeatureFlags.kt` L173-179, routes to the native MatrixRTC stack instead). One tap,
  no confirmation. Element Call is told start/join and DM/voice by its URL `intent` (`DefaultCallWidgetSettingsProvider.kt`
  L61-70: `START_CALL_DM_VOICE`, `JOIN_EXISTING_DM`, ...).
- **The profile's call buttons**: `UserProfileMainActionsSection.kt` L48-59 ("Call", "Video") when `canCall`
  (`UserProfilePresenter.kt` L67-84: a DM exists, Element Call available, call permission) -> `startCall(dmRoomId)`
  (`UserProfileFlowNode.kt` L87-95). This is what `matrix:u/<mxid>?action=chat` opens.
- **Shortcuts**: Element X pushes long-lived conversation shortcuts whose intent is the `elementx://open` link
  (`DefaultNotificationConversationService.kt` L115-129). Room-open only.
- **Incoming** is already fine for us: `ActiveCallManager.registerIncomingCall` (L136) ->
  `RingingCallNotificationCreator` (CallStyle `forIncomingCall` L154-155, full-screen intent L169) ->
  `IncomingCallActivity`; Vibb One's design 17 rings it over the PIN lock.
- **No Telecom**: no `ConnectionService`, `PhoneAccount`, `TelecomManager` or `androidx.core.telecom` anywhere in the
  tree. Even with one, a self-managed `PhoneAccount` can only be called by its own app, so `placeCall` from the
  launcher is not a route (candidate (c) is out; an L-sized PR that wouldn't serve us).

## 2. The PR: `action=call` on `matrix:` room links

**Candidates weighed.** (a) A `matrix:` action - the only one that is a client-neutral convention (any Matrix app, any
launcher, NFC tag or web page could use it), costs Element nothing new to document, and today's Element X already
degrades it to "open the room". (b) An exported `io.element.android.x.action.CALL` with a room extra - simplest, but an
app-specific API Element has never offered (every external-automation request, §5, went unanswered or "recorded"),
and it would need its own caller rules. (c) Telecom - not possible for outgoing (above). (a) it is.

**Behaviour.** `VIEW matrix:roomid/<id>[?via=..]&action=call` -> the same path as a room link (account picker when
there are several sessions) -> the room opens with a confirmation sheet: the room's avatar and name, "Voice call"
(DMs only, as the top bar) and "Video call", Cancel. "Join call" instead when one is ongoing (`RoomCallState.OnGoing`).
Not shown (just the room) when `canStartCall`/`canJoinCall` is false or the room isn't joined. **Never starts on its
own**: a `matrix:` link is BROWSABLE, so any web page could otherwise ring the user's contacts; the spec's own wording
for `join` ("should prompt for confirmation") is the precedent, and Element Web's #17262 asks for a confirmation even
on its own call button. A "trusted caller skips the sheet" variant (an Element-defined permission checked with API
34's `getLaunchedFromUid`, like Android's DIAL vs CALL) is a separate, much less likely follow-up - not part of the
first PR.

**Files** (estimate; prod lines in brackets):

1. `libraries/matrix/api/.../permalink/PermalinkData.kt` - `RoomLink.action: RoomLinkAction? = null` (`Call`) [+5]
2. `libraries/matrix/impl/.../permalink/DefaultPermalinkParser.kt` - for `matrix:` read `uri.getQueryParameter("action")`
   (the SDK drops it; no Rust change needed) [+8]
3. `appnav/.../RootFlowNode.kt` `attachPermalinkData` - pass the action on [+4]
4. `appnav/.../room/RoomNavigationTarget.kt` - `Root(.., callPrompt: Boolean = false)` [+2]
5. `appnav/.../room/joined/JoinedRoomLoadedFlowNode.kt` L325-336 - forward to `NavTarget.Messages` [+6]
6. `features/messages/api/.../MessagesEntryPoint.kt` - `InitialTarget.Messages(.., callPrompt)` [+2]
7. `features/messages/impl/...` `MessagesFlowNode`/`MessagesNode`, presenter state + events, the sheet in
   `MessagesView` (reusing `CallMenuItem`'s icons and `navigateToRoomCall`) [+70-110]
8. strings: as an external contributor a temporary resource file, integrated by the reviewer through Localazy
   (CONTRIBUTING L44) [+3 strings]
9. Tests: `DefaultPermalinkParserTest` (action read, unknown action ignored, matrix.to unaffected), `IntentResolverTest`,
   the messages presenter test (sheet shown only with `canStartCall`, cancel, voice/video -> `startCall`), a preview for
   the screenshot tests (recorded by a reviewer) [~80-100]
10. `tools/adb/deeplink_matrix.sh`-style helper for the call link [+3]

Total ~120-160 lines of code - under CONTRIBUTING's 200-line "large" mark (tests excluded) - and ~80-100 of tests.

**Review risk.** CONTRIBUTING L52-66: a feature needs an issue in **element-meta** first ("explain why users need it"),
platform parity (iOS/Web would be asked about), and maintainers "might not be willing" to keep a feature (L70). CLA
required (`docs/pull_request.md` L176). AI-led PRs are fine if the author owns the quality and writes the description.
The spec point is the real risk: `call` is not one of the spec's two actions (the grammar lists `join`/`chat`; custom
*parameters* are namespaced, custom actions aren't covered) - maintainers may want an MSC or an unstable value such as
`action=org.matrix.msc____.call`, which today's ruma still parses (`_Custom`). Track record for small external PRs is
good: `#4839` "Support matrix: links" (+31/-4, 4 files) merged in 2 days; recent ones - `#7867` (+41/-19, 1 day),
`#7574` share-sheet conversations (+54/-1, 2 days), `#7697` (+20/-7, 13 days), `#7551` (+89/-3, 8 days), `#7781`
(+113/-2, 5 days), `#7538` (+152/-5, 15 days); the outlier `#6477` multi-select polls (+259/-62, 49 files) took 5 months.

## 3. Meanwhile in handy, and after the PR

- **Today, no PR**: give an Element contact's card a "Call" (Ring) button next to Message, explicit to Element X:
  - `matrix:roomid/<learned room, no !>?action=call` (built like `elementRoomUri`, `calls/ElementRooms.kt`) - on today's
    Element X it opens the DM; the kid taps the top bar's voice icon. Forward-compatible: on a patched build the same
    URI shows the call sheet. Needs the learned room (design 15).
  - else `matrix:u/<mxid>?action=chat` (`elementChatUri`, already the Message fallback) - Element X's profile with a
    labelled "Call" button (`UserProfileMainActionsSection.kt` L48-59). Bigger target, works without a learned room,
    but the profile also shows Share/Block/verify.
  - Either is two taps (ours, then Element X's) - the same count the PR gives; the PR only replaces a small top-bar
    icon or a busy profile with a single "Call Mamma?" sheet. Copy should say "Ring i Element" so the kid expects the
    second tap.
- **After the PR**: no launcher change if we already send `?action=call`; if upstream picks an unstable action name,
  one constant changes. An older Element X just opens the room (fallback built in). Keep `openMessage`'s
  next-URI-on-throw list (room link, profile link, `element://user`).
- **Nothing for incoming**: design 17 already rings Element X's call over the lock.
- **Not done here**: whether `matrix:roomid/` with a percent-encoded local part opens on a device; the top bar's
  `canStartCall` for our DMs (Element Call must be available on our homeserver - it is, per the FluffyChat review §2).

## 4. Verdict

1. PR size **M**: ~120-160 lines of code + ~80-100 of tests, 9-12 files, no Rust/SDK change.
2. Chance: moderate (~40-50 %); higher if the element-meta issue gets a design nod first and lower if they ask for an MSC.
3. Recommended path: `matrix:` `action=call` with a confirmation sheet - never auto-start; no exported custom action, no Telecom.
4. Order: file the element-meta issue (below), wait for product/design feedback, then the PR; an MSC only if asked.
5. Handy meanwhile: ship the "Ring" button with the `?action=call` room link (profile link as fallback) - it works now.

Issue text for element-meta (general framing):

> **Start a call in a room from a `matrix:` link**
> Matrix URIs can say `action=join` and `action=chat`, but nothing lets a link, an NFC tag, a home-screen shortcut or
> another app say "call this room". Real cases: a family member who taps a contact on a simple launcher, an
> accessibility launcher with big "Call grandma" buttons, a team's "join the stand-up" link on a wiki.
> Proposal: Element X Android handles `matrix:roomid/<id>?action=call` (and `matrix:r/<alias>?action=call`) by opening
> the room and showing a confirmation sheet - voice (DMs) / video, or "Join" if a call is ongoing. It never starts a
> call without the user's tap, and does nothing extra when the user can't call there. Today's builds already open the
> room for such a link (the action is ignored), so links degrade gracefully. iOS and Web could map the same action.
> I'm willing to provide the Android PR (~150 lines + tests); happy to write an MSC for the `call` action if preferred.

## 5. Prior art upstream

Searched with `gh search issues --include-prs` in element-x-android, element-android, element-web, element-call,
element-meta and matrix-spec-proposals for: start call intent, call deep link, call from intent, intent start call room,
matrix: uri action call, launch call external app, tasker call, shortcut call, Telecom PhoneAccount, ConnectionService,
placeCall, call link room, plus deep link/intent/shortcut/automation/URI-scheme titles. **No issue or PR anywhere asks
for starting a call from outside the app**, and no MSC defines a call action or call link. The nearest:

| # | Title | State | Opened / closed | Last activity | What maintainers said |
|---|---|---|---|---|---|
| EXA #2110 | Element X does not support URI schemes | open | 2023-12-26 | 2025-03-22 | No maintainer reply; superseded in practice by #4839. |
| EXA #4800 / PR #4839 | Support `matrix:` URI schemes / feat: Support matrix: links | closed / merged | 2025-05-29 / 2025-06-09 | 2025-06-09 | External PR (+31/-4) merged in 2 days - the route our PR extends. |
| EXA #6344 / PR #6345 | Remove `matrix.to` intent filter | closed / merged | 2026-03-13 / 2026-03-16 | 2026-03-16 | bmarty: no assetlinks on matrix.to; `matrix:` is the supported app link. |
| EXA #1586 | Publish room shortcuts | open | 2023-10-16 | 2024-01-24 | "We would like to add this ... external contributions are welcome"; later partly done (#5180, #7574). |
| EXA #5200 | Add intent system to widget url creation | closed | 2025-08-21 / 2025-09-15 | 2025-09-15 | Internal: EX passes Element Call's `intent` (start/join, DM, voice). Our sheet maps onto it. |
| EXA #7422 | Deep Link Support | open | 2026-08-14 | 2026-08-14 | About opening *other* apps' links from messages; no reply. Not ours. |
| EA #884 | [Feature request] Sending message through Intent | open | 2020-01-21 | 2020-01-22 | bmarty: "quite good, but the core team will not be able to work on this ... we record the idea". |
| EA #3998 | Add Intents to Open Doors to Scripting | open | 2021-09-10 | 2021-09-10 | Only a clarifying question; never answered. |
| EA #7339 | Push-to-talk via intents or physical button | open | 2022-10-12 | 2022-11-09 | Labelled S-Minor/O-Uncommon; no maintainer reply. |
| EW #17262 | Call button should confirm action before starting a call | open | 2021-05-12 | 2021-11-28 | X-Needs-Design; Half-Shot/t3chguy: one call button that asks voice/video = the confirmation. Supports our sheet. |
| EC #2806 | Element Call link not opened by Element X on recent Android | closed | 2024-11-20 / 2024-11-27 | 2024-11-27 | toger5: call links are to give way to matrix.to/room links with verified accounts (guests excepted) - room links, not call URLs. |
| EC #2828, #3445, #3514 | Intent URL param; intents for DM; URL intents for voice calls | merged | 2024-12-18, 2025-08-07, 2025-09-25 | same | Element Call's client->widget contract (`start_call`, `join_existing`, `start_call_dm[_voice]`). Not an external entry point. |
| meta #3295 | Element X iOS: prepare images and files for a room from Apple Shortcuts | open | 2026-08-15 | 2026-08-15 | The only recent "external automation into a room" request; no reply yet. |
| MSC2312 (PR) | Matrix URI scheme proposal | merged | 2019-10-04 / 2021-04-04 | 2022-03-01 | Defines `join`/`chat` only (spec v1.2+). |
| MSC3780 (PR) | Knocking on `action=join` | open | 2022-04-20 | - | Precedent that action semantics change through an MSC. |

Element Web has no `?action=call`/`?call=` URL parameter that our searches found; Element X iOS was not checked. Not
reached: Element Web's URL-parameter docs and the Matrix spec repository's issues beyond the searches above.
