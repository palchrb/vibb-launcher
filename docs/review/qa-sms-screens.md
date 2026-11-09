# Code QA: sms-screens (design 77 step 2, the kid's message screens)

Branch `sms-screens` (7b28e331, one commit on ae33ecce). Reviewer's branch `qa-sms-screens` = `sms-screens` + this file.
Design: `docs/design/77-sms-mms-client.md` rev 3 (§6.7, §6.8, §9 step 2, D4/D5, D11, D18); notes
`docs/notes/launcher/calls.md` "The kid's message screens, step 2", `docs/notes/launcher/time-rules.md`.

## Setup

- Worktree detached on 7b28e331. I read the whole diff and the code around it: `SmsEngine` (send, the outbox,
  `rulesChanged`, `conversations`, the deletes, the live flow), `SmsRules.decideSend`/`SmsJudge`, `SmsRuntime`
  (`engine`, `listen`, `ask`, `events`, `notifyShown`), `SmsNotifier`, `ComposeActivity`, `CallRules`/`smsMode`,
  `resolveMessageButton`, `DayActivity.redirectIfLocked`/`LockWatch`, `LockActivity.start`/rows, `NoTextActions`,
  `HomeGridAdapter`, the lock-and-glance and time-rules notes on lock task, Recents and own tasks.
- Launcher, targeted only (as briefed): `flock ~/build.lock ./gradlew --no-daemon --max-workers=2 testDebugUnitTest
  --tests 'com.kidslauncher.mdm.ui.messages.*'` with two throwaway scratch tests (not committed, deleted after):
  **BUILD SUCCESSFUL in 2m 13s**, every `ui.messages` test passing. Scratch 1 (M1): two `MessageActions.retry` calls
  with the same stale failed message -> `sent == [kommer, kommer]`. Scratch 2 (L1): a collector on a single-thread
  dispatcher (as `SmsRuntime.listen`'s) while the same thread runs 100 sends -> **64 of 100** events seen.
- No emulator.

## What was checked and holds

- **No free number anywhere.** "Ny melding" lists only `sms_out` contacts (`newMessageContacts`); `ThreadActivity` is
  not exported, its `EXTRA_NUMBER` comes from our own code only (a contact, "Ny melding", `ComposeActivity`'s
  `verdict.number`); every send, retry included, goes through `SmsRuntime.send` -> `SmsEngine.send` -> `decideSend`, and
  the outbox judges again. The compose state is shown, never trusted.
- **No links, no selection, no field menu** in the thread: `bubble_text` has no `autoLink`/`textIsSelectable`, nothing
  calls `Linkify`; `NoTextActions` on the field (no action modes, no long-click, `TextClassifier.NO_OP`); the only
  chooser is `PictureShare.share` (read grant only, our own `FileProvider`, `cache/share/` only), and
  `KidScreensEscapeTest` now guards `ui/messages`.
- **`smsto:`**: `ComposeActivity` opens a thread only on `decideSend` = Allow; the draft is never sent without a tap.
- **Time rule.** All three screens check at `onResume` (before `threadInFront`, `markRead` or `lockWatch`) and watch
  `lock_reason`/`lock_key` while in front, exactly as Dagen's (`LockWatch` with the new `check` parameter, default
  unchanged for Dagen). `messagesUsableUnder` matches the gate's `TimeRulesStore.callsBlockedNow` (budget keeps calls).
  Ways in under a rule: Recents, the notification (`getActivities` -> thread `onResume` redirects), the contact sheet
  (only from the phone book, itself only where calls are), `smsto:` (toast, then the thread redirects), the
  time-rule screen's row (`callsAllowed` only). While the PIN lock is LOCKED the lock task drops OVERVIEW, HOME and
  NOTIFICATIONS, so the `.messages` task can't be switched to. Nothing found that reaches a thread where calls aren't
  allowed.
- **Read-only in Av/Ikke styrt**: `composeState` returns `READ_ONLY` before asking the gate, so even an emergency number
  gets no bar; "Ny melding" is gone. The Home tile follows `messagesTileShown` exactly as §6.7 says.
- **The compose clientId (QA N2)**: one id per draft, replaced only after `Accepted` for that id; a second tap before
  the answer repeats the id and the engine returns the same message. Also across a recreation (saved state + the
  EditText's own text) - a retap after a rotation sends nothing twice.
- **Engine thread**: every engine read and write in the screens goes through `SmsRuntime.ask` / `events`
  (`withContext(engineDispatcher)` to open); callbacks check `isDestroyed` where they touch views; `SmsHome` listeners
  are added in `onStart` and removed in `onStop` (both posted, FIFO); `LockWatch` is held by the page. No lasting leak
  found.
- **Notification**: one post path (`notifyShown`), suppressed when `threadInFront` matches; the thread's load marks
  read and cancels. The PendingIntent is keyed by the thread intent's identifier (the last intent of
  `getActivities`), immutable, explicit.
- **i18n**: every new string is in both `values/` and `values-nb/` (44 strings, one plural); no vendor names in the
  kid's strings.

## Findings

Counts: C 0, H 0, M 3, L 7, N 6.

### M1. "Prøv igjen" tapped twice sends the text twice

`launcher/app/src/main/java/com/kidslauncher/mdm/ui/messages/MessagesModel.kt:326-331`,
`launcher/app/src/main/java/com/kidslauncher/mdm/ui/messages/ThreadActivity.kt:338-346` and `:434-438`.

The retry link calls `retry(m)` with the bubble's `Message` as bound and a fresh `UUID` per tap. `MessageActions.retry`
checks `failed.delivery` on that captured (stale) object, never on the store. A kid who taps "Ikke sendt - trykk for å
prøve igjen" twice before the thread re-renders (the usual reaction to a failure) queues two engine tasks: the first
sends with id A and deletes the failed row, the second still sees `FAILED` on its copy, sends with id B (accepted, a
new message) and deletes nothing. Two SMS go to the contact, and two bubbles show. Confirmed by a scratch test: two
`retry` calls with the same stale message -> `sent == ["kommer", "kommer"]`. The commit's "double tap sends once"
covers the compose bar only.

Fix: make the retry idempotent per failed message. Either derive the `clientId` from it (`"retry:" + failed.id`, so a
second tap is the engine's repeat), or re-read the row on the engine thread in `retry` and return `null` when it is
gone or no longer `FAILED`/`UNCERTAIN` (or both). Disable the link once tapped until the next load. Add a test with two
taps on the same stale message.

### M2. Contact avatars' badge (missed calls plus unread messages) is not built and not listed as left out

Design §6.7, "Contact avatars: the badge is missed calls plus unread messages." Nothing in the commit touches the
contact badges (`MissedCallsRepo.contactMissed`, Home's contact buttons, the phone book's avatars - `SmsHome` is read
only by Home's grid, the time-rule screen and the two message screens). The commit message, `calls.md` and the
emulator run don't mention it, so the step reads as complete while a §6.7 item is missing. A kid with a Home button
for Mamma sees no sign of her unread text there.

Fix: build it (per-contact unread from the engine, keyed like `contactMissed`, folded into `ContactMissed.count` and
its TalkBack words), or record it explicitly as deferred in PLAN's queue and in `calls.md`.

### M3. The message notification lets the system add "Open link" / "Call" / "Directions" / "Copy" chips

`launcher/app/src/main/java/com/kidslauncher/mdm/sms/SmsNotifier.kt:80-89` (step 1's code, but the screens' way in
and in this review's escape focus).

The screens take care that nothing in a message opens another app (no links, no classifier). The notification is a
`MessagingStyle`, `CATEGORY_MESSAGE` notification without `setAllowSystemGeneratedContextualActions(false)`. On
Android 10+ the notification assistant (Android System Intelligence / ExtServices) runs the TextClassifier over the
last message and adds contextual actions: a URL gets "Open" (browser), an address "Directions" (maps), a number "Call",
an e-mail address the mail app, a code "Copy". So a contact's (or a callback-window stranger's) text becomes a one-tap
`ACTION_VIEW` into whatever resolves - the very path the bubbles were built to avoid, also under a time rule (the
notification is posted silently then). Suspension and the allowlist still limit what opens, but non-suspendable system
handlers stay reachable. The reply action already has `setAllowGeneratedReplies(false)`; the notification-level switch
is missing.

Fix: `.setAllowSystemGeneratedContextualActions(false)` on the builder (and on `notSent`'s), plus a source test like
`KidScreensEscapeTest`'s that pins it.

### L1. A burst on the engine thread drops live events for every collector

`launcher/app/src/main/java/com/kidslauncher/mdm/sms/SmsRuntime.kt:151-154` (new in this commit).

`listen` collects `opened.live` on `engineDispatcher`, the engine's own single thread. The engine's
`MutableSharedFlow(extraBufferCapacity = 64)` with `tryEmit` drops when the slowest collector's buffer is full, and a
collector on the emitting thread cannot drain while that thread runs one task. So any engine task that emits more than
64 events (`deleteConversation` of a long thread - one `MessageDeleted` per message -, `rulesChanged` over many
conversations, a long outbox drain) drops the rest for **all** collectors, the screens' too. The scratch test saw 64
of 100 events from one engine-thread task. Today the screens mostly heal (each load reads everything), but a `MessageUpdated` lost this way leaves
"Sender …" until the next event, and the future bridge must not lean on `live`.

Fix: collect on another dispatcher (`Dispatchers.Default`) and only hop to the engine in `SmsHome.refresh` (which
already queues itself), or give the flow `onBufferOverflow = DROP_OLDEST` and make every consumer a "something
changed, reload" signal.

### L2. Every engine event reloads each open screen in full, with a platform IPC per conversation

`MessagesActivity.kt:117-126` (`load`), `ThreadActivity.kt:209-224`.

Each event (a burst included) queues a full `load()`: `engine.conversations()` computes last message, unread and
`canSend` for every conversation, and `canSend` calls `decideSend` -> `isEmergency` -> `TelephonyManager` once per
participant. The thread calls `conversations()` only to find its one conversation. Nothing coalesces, so deleting a
conversation of a few hundred messages queues up to that many full loads on the engine thread ahead of the outbox and
the notifications.

Fix: coalesce (a `pending` flag like `SmsHome.refresh`, or `conflate()` on the collected flow) and give the thread a
single-conversation read.

### L3. The time-rule screen's Meldinger row doesn't follow `SmsHome`

`launcher/app/src/main/java/com/kidslauncher/mdm/ui/LockActivity.kt:282`.

The row is computed from `SmsHome.tileShown()` at render, but `LockActivity` registers no `SmsHome` listener (it does
for `CalendarStore`, Kalender's code QA L2). The first message arriving under a rule (history appears), or the role
being granted, leaves the row missing until something else re-renders the card; the reverse leaves a row that opens an
empty list.

Fix: `SmsHome.addListener`/`removeListener` next to `calendarListener`, re-rendering the rows.

### L4. A role change doesn't refresh `SmsHome`

`launcher/app/src/main/java/com/kidslauncher/mdm/sms/SmsHome.kt:40-47`.

`roleHeld` is read only inside `refresh`, which runs on engine events, policy changes and Home's `onStart`. The role
arriving after `sms_mode` turns managed (granted asynchronously) leaves the tile hidden and the open thread's bar on
"Meldinger kan ikke sendes akkurat nå" until another event. Fix: call `SmsHome.refresh` where `SmsRole` learns the
outcome (and the thread re-renders through its listener).

### L5. "Trykk for å prøve igjen" and the refusal toast say the wrong thing in some states

`ThreadActivity.kt:330-335`, `:434-438`; `MessagesModel.kt:87-92`.

- The failed/uncertain line invites a tap in Av/Ikke styrt and in a conversation the kid may no longer text; the tap
  can only be refused. Show "Ikke sendt" without the invitation (and not clickable) unless `compose == OPEN`.
- `DUPLICATE_CLIENT_ID`, `EMPTY` and `TOO_LARGE` map to "Du kan ikke svare her". The duplicate is reachable: tap Send,
  edit the text before the answer (or after a recreation, where the old id comes back from the saved state) and tap
  again -> "Du kan ikke svare her", although the next tap sends. Give them their own words (or for the duplicate just
  take a new id and send again silently).

### L6. The share sheet reaches more than "apps the parent allows"

`launcher/app/src/main/java/com/kidslauncher/mdm/ui/messages/PictureShare.kt:94-107`.

§6.7 says only allowed apps can receive. The system chooser also offers targets that are never suspended: Quick Share
/ Nearby (GMS), Bluetooth, the chooser's own "Edit" (Markup) and "Copy". Under a time rule that allows calls the
picture screen is reachable while the other apps are suspended, so these become the way out. Dormant today
(`openPart` is `null`, no picture opens) - decide it before step 3b: either accept and say so in the design, or
restrict (`EXTRA_EXCLUDE_COMPONENTS` for the known system targets, or `Intent.EXTRA_CHOOSER_TARGETS` built from the
allowlist only).

### L7. Tests restate the pure functions but miss the wiring that carries the safety

`MessagesModelTest` is good on the pure model and the engine's dedup; the risky parts are untested:
- the retry double tap (M1);
- the screens' redirect: nothing pins that `MessagesActivity`, `ThreadActivity` and `PictureActivity` call
  `MessagesScreens.redirectIfLocked` first in `onResume` and start `LockWatch` with it (a source test as `DayTaskTest`
  does for Dagen);
- `ComposeActivity`: the `TIME_RULE` toast vs the refusal, the body from the extra and from `?body=`, a not-allowed
  number never opening a thread;
- the notification's target (`openIntent` -> `ThreadActivity` with the identifier) and `notifyShown`'s suppression;
- M3's switch, once added.

## Nits

- **N1** `SmsReceivers.kt:217-219`: `?body=` is read from the decoded scheme-specific part, so a body containing `&`
  is cut and `%26` can't help; `Intent.EXTRA_TEXT` (used by some apps for `SENDTO`) is ignored. Parse the raw
  (encoded) SSP and decode the value.
- **N2** `MessagesScreens.kt:105`: `TYPE_VIEW_FOCUSED` on a non-focused title doesn't move TalkBack focus; the panel's
  live region announces it, but focus stays on the bubble/row. `performAccessibilityAction(ACTION_ACCESSIBILITY_FOCUS)`
  on the title does.
- **N3** `MessagesActivity.kt:130-134`: `mayStart` judges only the first `sms_out` contact; an emergency-number contact
  first in the list is always `OPEN` (D5 rule 1), so "Ny melding" would show under a no-calls rule. Unreachable today
  (the screen redirects), but judge any non-emergency contact instead.
- **N4** `ThreadActivity.kt:350-354` ("in memory only, never on disk"): the field's text and the `clientId` also go
  into the saved-instance Bundle, which the system may persist across a process death. Reword, or set
  `android:saveEnabled="false"` on the field if it matters.
- **N5** `SmsHome.kt:46,51`: `pending` is cleared both at the start of the work and in the callback; the second can
  let a duplicate refresh in. Harmless; clear it once.
- **N6** In English the history tile reads "Messages" next to Google Messages' own "Messages" tile in Ikke styrt
  (emulator.md §6v notes it). Consider "Message history" / "Meldingslogg" for the read-only state.

## Fix round

On `sms-screens`, after this review was brought in; with it the user's bubble colours (the coordinator, 2026-11-09).

- **M1** - `MessageActions.retry` sends under an id derived from the failed message (`retryClientId` = `retry:<id>`), so a
  second tap on the same stale bubble is the engine's repeat (same message, nothing sent, the failed row already gone);
  a retry that fails again is a new message with a new id. The link also answers once until the next read
  (`ThreadActivity.retrying`). Test: `try again tapped twice on the same failed message sends once` (one SMS, one bubble).
- **M2** - built. `SmsHome.Summary.unreadByNumber` (pure `unreadByNumber`: one-to-one numbers with unread, never a sender
  name, a group or `unknown:`) feeds `ContactBadge(missed, unread)` on Home's contact buttons, the phone book's avatars
  and the contact sheet (`KidAvatars.bindContactBadge`: the missed-call glyph only with a missed call;
  `contactDescription`: "Mamma, 1 tapt anrop, 2 uleste meldinger", new plural `contact_unread`). Home re-renders its
  contacts and the phone book its grid on `SmsHome`'s changes. Tests: `unread by number feeds the contact avatars`,
  `a contact's badge counts missed calls and unread messages`.
- **M3** - `.setAllowSystemGeneratedContextualActions(false)` on the message notification and on `notSent`'s.
  `MessagesWiringTest` pins one per builder (the public version excepted).
- **L1** - the engine's live flow is `DROP_OLDEST` (a burst keeps the newest, `tryEmit` never fails; the flow is
  documented as a change signal, the record is `events(afterSeq)`), and `SmsRuntime.listen` collects on
  `Dispatchers.Default`, not the engine thread. Test: `a burst on the engine's thread still delivers its last event`
  (100 deletes with the collector unable to drain: the last `MessageDeleted` arrives).
- **L2** - both screens read through a `Coalescer` (one read at a time, one more for everything asked meanwhile; test
  `reloads are coalesced`), and the thread reads its one conversation (`SmsEngine.conversation(id)` /
  `conversationWith(address)`) instead of `conversations()`.
- **L3** - `LockActivity` listens to `SmsHome` (next to the calendar listener) and asks it for a refresh at start: the
  Meldinger row follows the history and the role.
- **L4** - `AppEnforcer.signalSmsRoleChange` refreshes `SmsHome` whenever the role it sees differs from the summary's;
  Home's tile and an open thread's compose bar follow through `SmsHome`'s listeners.
- **L5** - "trykk for å prøve igjen" (and the tap) only while the compose bar is open (`retryOffered`); elsewhere "Ikke
  sendt" / "Kanskje ikke sendt" without it, and the bubbles redraw when the compose state changes. A refusal has its own
  outcome (`afterRefusal`): the time rule, not allowed, unavailable, read-only, "Meldingen er for lang"; a taken draft
  id (an edit between two taps) gets a new id and is sent again once, silently; an empty text says nothing. Test:
  `each refusal has its own outcome`.
- **L6** - restricted now: "Del" passes `EXTRA_EXCLUDE_COMPONENTS` with every target `ACTION_SEND` resolves to whose
  package isn't usable (`ContactSheet.usablePackages`: installed, not suspended, on the allowlist), plus the system's
  nearby share (`nearby_sharing_component`, Quick Share) unless its package is (pure `shareExclusions`, tested); on an
  unmanaged phone nothing is left out. Left for the device (step 3b's first real picture, noted in `PictureShare`):
  the chooser's own "Copy" stays (our clipboard), and its "Edit" opens the system's image editor, which lock task's
  activity-start block stops while kiosk is on - check both on the Jelly Star then.
- **L7** - `MessagesWiringTest` (source, as `DayTaskTest`): each screen's `onResume` starts with
  `redirectIfLocked` and each watches the lock; the notification's target (`threadIntent` with the identifier,
  `getActivities`, immutable) and `notifyShown`'s suppression; `ComposeActivity` opens a thread only in the Allow branch
  and never sends; the retry goes through the gate without a fresh UUID. Behaviour tests for M1, M2, L1, L2, L5, L6 and
  N1 in `MessagesModelTest` and `HomeModelTest`.
- **N1** - pure `smsDraft`: `sms_body`, else `EXTRA_TEXT`, else `?body=` from the encoded scheme-specific part, decoded
  once ("&" survives as `%26`). Tested.
- **N2** - the confirm moves TalkBack's focus to its question (`ACTION_ACCESSIBILITY_FOCUS`, posted).
- **N3** - `mayStartNew` judges the first `sms_out` contact that isn't an emergency number. Tested.
- **N4** - the field has `saveEnabled="false"` and the client id no longer goes into the saved state: text and id stay in
  the process's memory (`drafts`), restored from there after a recreation.
- **N5** - `SmsHome.refresh` clears its flag as the read begins, and in the callback only when no read ran.
- **N6** - not changed, by the user's decision (2026-11-09: "la være inntil videre"): offered hiding the history-only
  tile, renaming it (e.g. "Meldinger (arkiv)") or leaving it, they chose to leave it. The tile, the time-rule row and the
  list's header keep reading "Meldinger" / "Messages" in every mode (a "Meldingslogg" label was built in this round and
  taken out again before the merge).
- **The user's bubble colours** - the kid's own: `kid_message_out` = the accent peach, its text `kid_message_out_ink`
  (= `kid_sheet_ink`, 8.7:1), its time `kid_message_out_dim` #5E4034 (4.7:1; the suggested #6B4A3C was 3.98:1, below
  4.5 for 11 sp); received: `kid_message_in` #34324A (D2), white text (12.3:1), its time `kid_message_in_dim` #A6A0B4
  (4.9:1; #9A94A8 was 4.2:1). Part lines and a picture's place take the bubble's dim tone. The delete ring is a 2.5 dp
  light `kid_core` outline on the peach bubble (peach on peach wouldn't show) and stays peach on the received one.
  `KidPaletteTest` keeps every pair at 4.5:1 or more.

Checks: launcher `assembleDebug testDebugUnitTest -PwarningsAsErrors=true` 2230/0 (`MessagesModelTest` 22,
`MessagesWiringTest` 4). A quick emulator look (`handy36`, the step 2 run's phone): Home's contact buttons carry the
unread messages without the missed-call glyph ("Mamma, 1 ulest melding"), and the thread shows the peach and lilac-grey
bubbles with their times and the light delete ring (screenshots `20-avatar-badge-nb.png`, `21-thread-colours-nb.png`,
`22-delete-ring-nb.png` in the session's `scratchpad/sms-screens/shots/`).
