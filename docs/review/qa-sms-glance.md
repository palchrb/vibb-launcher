# Code QA: sms-glance (design 77 step 5, the lock glance's Meldinger rows and the SMS notice wake)

Branch `sms-glance` (042b9643: 2221bdd8 the rows, 19fca102 a merge of main with calendar-fresh, 042b9643 the notice
wake). Reviewer's branch `qa-sms-glance` = `sms-glance` + this file. Design: `docs/design/77-sms-mms-client.md` §6.7,
§6.8; notes `docs/notes/launcher/calls.md` "The lock glance's Meldinger row, step 5",
`docs/notes/launcher/lock-and-glance.md` "The Meldinger rows" and "The notice wake"; launcher round 7 §3
(`docs/design/r7-launcher-small-items.md`).

## Counts

C 0, H 1, M 1, L 4, N 4.

## Setup

- I read the whole diff (`git diff origin/main...qa-sms-glance`; `origin/main` is ahead only by PLAN commits) and the
  code around it:
  - `SmsHome`, `SmsRuntime` (`notifyShown` and its callers: receive, `judgeHeldForRules`, `releasePass`, `start`,
    `MmsRuntime`'s pass and pushes), `SmsNotifier`, `SmsEngine` (`conversationFor`/`groupConversationFor`, ids, `at`);
  - `ThreadActivity` (`onResume`, `redirectIfLocked`, `show` for a deleted conversation), `MessagesScreens`;
  - `GlancePanel` (`start`/`stop`, `tap`, `shownRows`, `bindRows`, `commitDismiss`, `removeByAction`, `describe`, the
    other binds for view reuse), `GlanceOpener` (`run`, `facts`, `opens`), `LockGlance` (`glanceOpenStep`,
    `glanceUsableUnderLock`, `glancePrompt`);
  - `PinLockRuntime.noticeCame`/`noticeAllowed`, `NoticeWakePlan`, `GlanceWake.glanceAlerts`, `MissedCallNotifier`'s
    trigger, `ZenRuleRuntime.zenPolicy`, `RuleSilence`, `TimeRules` (`callsAllowed`);
  - the server's i18n for the wake switch (`device.lock.wake_sub`).
- Tests, targeted (as briefed), no emulator was running (`qemu-ga` is the VM's guest agent):
  `flock ~/build.lock ./gradlew --no-daemon --max-workers=2 testDebugUnitTest --tests 'com.kidslauncher.mdm.sms.*'
  --tests 'com.kidslauncher.mdm.lock.*' --tests 'com.kidslauncher.mdm.badges.*' --tests 'com.kidslauncher.mdm.ui.messages.*'`:
  **BUILD SUCCESSFUL in 2m 30s**: 709 tests in 54 classes, 0 failures, 0 skipped.
- No emulator run: no finding needed one.

## What was checked and holds

- **No text with the switch off.** `smsGlanceRow` reads a body only when `showText`. `picture` is
  `showText && text == null && ...`, so with the switch off there is no "Bilde". `line()` returns `null` without either,
  so the subtitle and the TalkBack text say only "Ny melding" / "3 nye meldinger" and the name. The switch is
  `smsLockText(CallPolicyStore.state)`: managed rules only; unknown and unmanaged mean off. It is read on the engine
  thread at each refresh. A change of the switch reaches the lock at once: the `CallPolicyStore` listener calls
  `SmsHome.refresh`, and `smsChanged` re-renders because the rows differ.
- **Cleaning.** The text goes through `cleanGlanceText(.., GLANCE_TEXT_MAX)`: controls and bidi overrides become a
  space, invisible marks are dropped, 200 characters at most, an empty text counts as none. The test checks U+202E.
  "Pappa: ..." and "Ukjent avsender: ..." are put around the already cleaned text. The title is the message screens'
  name: a contact's name, a number, or a sender name. A sender name is cleaned at intake (`SenderClean.of` ->
  `TitleRules.clean`) and shows only when the parent lists it (D17).
- **Held and blocked never show.** `Conversation.unread` counts only `verdict = 'SHOWN' AND direction = 'IN'`. A
  stranger's held message makes no row, not even its number (`SmsGlanceTest`). The callback window's "Ukjent avsender"
  row is an accepted message: with the switch off it shows the number and the count, the same as the message list.
- **The tap.**
  - `tap` takes the target only where `glanceUsableUnderLock` allows it (`Thread` -> `lock.callsAllowed`, the same rule
    as `messagesUsableUnder`).
  - The open happens only from `PinLockActivity.unlock` (the right PIN or the parent code). From the lock under a
    rule it is handed to the time-rule screen and checked again there.
  - `ThreadActivity.onResume` redirects once more (`redirectIfLocked`) before it marks anything read.
  - No path opens the thread without the code, or where the rule forbids it.
- **The right thread.** Conversation ids are ULIDs, never reused (`ids.ulid`). A conversation deleted between the tap
  and the unlock finishes the thread, and the list shows (`show`: `found == null && number == null`).
- **Lifecycle.**
  - `SmsHome.addListener` and `removeListener` are both posted to the main thread, so they stay in order across a
    fast stop and start. `smsChanged` checks `started`.
  - `SmsGlance.hidden` is touched only on the main thread (`renderNow`, `commitDismiss`).
  - No engine read happens on the main thread: the bind reads only memory (`MessagesScreens.rules()`, the
    `KidAvatars` cache).
  - A row dragged while a new message arrives: `commitDismiss` hides the row as it was bound, with the old
    `upToSeq`. The newer message then brings the row back, which is right because the kid never saw it (but see L2).
- **The order and the limits.** `glanceWithSms` sorts all rows by `timeMs`, the same as `glanceRows` itself. Dagen's
  reminder row stays first. SMS rows count toward `GLANCE_MAX_ROWS`, "+ N til" and the compact tile like any other
  row. `timeMs` is our receive time (`receivedAt`), never the SMSC's.
- **The merge with calendar-fresh** (19fca102): `tap`'s `calendar.refresh()` and `DayRowsCache` are untouched. The
  SMS branch sits beside them.
- **View reuse.** Every other bind resets what the SMS bind changes on a reused row view: `missedBadge` and `appMark`
  set the badge's background, padding, tint and image; `plainIcon` and `phoneGlyph` set the icon.
- **The wake's thread and sources.**
  - `noticeCame` is posted to the main looper.
  - Wakes only for posts that alert: `notSent` uses the silent channel and doesn't call it, a picture that arrives
    late is `again` (silent), and a time rule without calls is silent.
  - No wake for messages the kid never gets notified of: held or blocked messages are never posted, and a message
    read in the thread in front isn't either.
  - No double trigger: `GlanceReader` skips our notifications.
  - At process start (`judgeHeldForRules`, `releasePass` in `SmsRuntime.start`) the gate's 2-minute start busy
    applies. A parent's release of many held messages wakes the screen once (30 s gap).
- **i18n.** The 5 new keys (3 strings, 2 plurals) are in `values/` and `values-nb/` (the key sets are equal, except the existing
  untranslatable `glance_separator`). TalkBack reads one node, "3 nye meldinger fra Mamma, [text,] 14:05", with the
  click label "Lås opp og åpne Meldinger" and "Fjern" (`rowActions`).
- **The look.** The contact's avatar with an 18 dp teal badge, the missed-call badge's shape in Home's Meldinger
  colour, or the chat glyph on teal. Calm, nothing new that moves.

## Findings

### H1. The SMS wake ignores Do Not Disturb: a silent bedtime that allows calls lights the screen for every message

`smsPostAlerts` checks posted, silent, notifications on and the channel's importance. It never checks the interruption
filter. Round 7 §3.1.5 requires it for every notice (`glanceAlerts`: `passesFilter`, from
`ranking.matchesInterruptionFilter()`). The SMS post is silent only when `TimeRulesStore.callsBlockedNow()`. A silent
rule that allows calls is a common setup: a bedtime "silent, alarms still ring" that lets Mamma call. Under such a rule
our own `AutomaticZenRule` is on, with `allowMessages(PEOPLE_TYPE_NONE)`, `showPeeking(false)` and
`showInAmbientDisplay(false)`. So Android silences the message and doesn't light the screen. But `noticeCame` does.

**Scenario.**
- The parent has "Vekk skjermen ved varsler" on and a silent bedtime 21:00-07:00 with calls allowed.
- At 23:10 a friend's group chat starts.
- The phone makes no sound. But the lock lights up for 15 s, at most every 30 s and 20 times an hour, on the
  nightstand of a sleeping kid, with the text if `sms_lock_text` is on.
- The same happens in class under a silent school rule that allows calls, and under any other Do Not Disturb (the
  kid's own from the shade, a system mode).
- An app's notice in the same situation doesn't wake: its post fails `matchesInterruptionFilter`.

The missed-call trigger has no filter check either, but there the call has already rung (`allowCalls(ANYONE)`), so
this is new with SMS.

**Fix.**
- Give `smsPostAlerts` a `passesFilter` parameter and fail closed:
  `NotificationManager.currentInterruptionFilter == INTERRUPTION_FILTER_ALL`. An exact answer, the posted
  notification's own ranking through our listener, isn't needed: under any Do Not Disturb no wake is the safe side.
- Add its rows to `SmsGlanceTest`.
- Note it in the notes' notice-wake bullet.

### M1. Every message wakes the screen, against round 7's "the rows gain a row" rule

Round 7 §3.1.5: an app's notice wakes only when the visible rows **gain a row**. "A newer `timeMs` of an existing row
is an update, not a wake" (`glanceWakes`). The SMS trigger wakes for every alerting post, a newer message in a row
already on the lock included. The gate then lets 20 wakes an hour through, one per 30 s.

**Scenario.** A cousin sends twelve messages in 20 minutes. The screen lights twelve times. The same chat in Element X,
a ticked app, wakes it once. The notes don't mention the difference, and the parent's switch is the same switch.

**Fix.** Wake only when the conversation's row is new on the lock: the message is the conversation's only unread
(`unread == 1` after it, decided on the engine thread in `notify`), or the row was swiped away
(`SmsGlance.hidden` holds the conversation; checked in the posted main-thread runnable). Pass it to `smsPostAlerts` as
`gainsRow`, add a test, and write the rule in the notes.

### L1. The parent's wake switch still says "apps and missed calls"

`device.lock.wake_sub` reads "For the apps on the lock below and missed calls" ("For appene på låsen nedenfor og
tapte anrop"). Since 042b9643 the same switch also wakes for every SMS. A parent who turned it on for Element X now
gets SMS wakes they were never told about.

**Fix:** a server change: "... missed calls and messages" / "... tapte anrop og meldinger", in both i18n files. It can
ship with the next server round; the launcher side needs nothing.

### L2. A swipe cancels the conversation's whole notification, including a message newer than the row

`SmsGlance.dismiss` hides the row up to the bound row's `upToSeq`, which is right. But it then cancels
`SmsNotifier.cancel(conversationId)`: the whole notification, which may already show a newer message. The panel
defers renders while a finger is down (`rowsHeld`), so a message that arrived during the drag is in the notification
but not in the row.

**Scenario.** The kid drags Pappa's "Ny melding" row, and Pappa's second message arrives during the drag. The
notification goes. The row comes back as "2 nye meldinger", but the shade, after the unlock, has nothing from Pappa.
The missed-call dismiss keeps a newer call (`dismissContact(.., upToMs)`).

**Fix:** cancel only when `SmsHome.summary.glance`'s row for the conversation still has the swiped `upToSeq`. Otherwise
leave the notification; it already tells of the newer message.

### L3. The tests miss the leak guards' edges, and two of them grep source text

`SmsGlanceTest` never covers:
- a **picture** message with the switch off (the "never Bilde" rule) or on;
- a **group's** `sender`;
- `unknownSender` (the callback window);
- an SMS row in the 8-row cut and "+ N til".

`the wiring` and the second half of `the notice wake` assert exact source lines, for example
`"val showText = smsLockText(CallPolicyStore.state)"`. They pin the spelling, not the behaviour, and a reformat breaks
them.

**Fix:**
- Add the four engine-backed cases; the fake store already makes pictures (step 3b's tests).
- Keep one source check: that `noticeCame` is called only in `notify`, after the post.
- Drop the exact-line ones.

### L4. The notice wake is not device-checked

The emulator run (`docs/testing/emulator.md` §6v step 5) covers the rows, the text switch, the tap and the swipe. It
was done before 042b9643, and nothing checks the SMS wake on a device. Round 7 §3.3's device checks name the
missed-call wake only.

**Fix:** one emulator line once H1 and M1 are in:
- with the switch on and the screen off, `adb emu sms send` from Mamma lights the lock;
- a second message within 30 s doesn't;
- under a silent rule that allows calls, nothing lights.

### N1. Every SMS post now stamps `lastNoticeElapsed`, switch on or off

`noticeCame` stamps it before the gate (design 59d §8.16: a notice this recent makes a system Home start no
gesture of the kid's). SMS posts now count like app notices and missed calls. Consistent, and harmless; noted so
nobody is surprised by a quiet gesture ask after a message.

### N2. `smsChanged`'s comment overstates what renders nothing

A `GlanceSmsRow` carries the whole `Conversation` (`lastSeq`, `lastAt`, `canSend`). So an outgoing message in a
conversation with unread messages, or a rule change that moves `canSend`, re-renders the page once. That is harmless,
but the comment says "a delivery report, an outgoing message renders nothing". The rows could carry only what they
show, or the comment can say "a change that leaves no row as it was".

### N3. The refresh reads 20 messages of every unread conversation at every engine event

`SmsHome.refresh` does one `engine.messages(.., 20)` per unread conversation for every event (coalesced). It runs on
the engine thread and is small at a kid's scale. If it ever matters, read only the newest `GLANCE_MAX_ROWS + 1` by
`lastAt`, and take the rest's count from `unread`.

### N4. The design note's "As built" line and the notes don't name the wake's deviations

Once H1 and M1 are fixed, the notice-wake bullet in `lock-and-glance.md` and `calls.md` should list the SMS
conditions the way it lists the app's five: not silent, Do Not Disturb, gains a row, notifications on, the channel.
