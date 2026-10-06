# 12 - Missed-call notification

User report (emulator, 2026-10-06): tapping the "N missed calls" notification does nothing.

## Cause (checked on the emulator)

- The notification is **Telecom's own** (`com.android.server.telecom`, channel `TelecomMissedCalls`).
  Telecom posts it itself because the default dialer - us - has no receiver for
  `TelecomManager.ACTION_SHOW_MISSED_CALLS_NOTIFICATION`.
- Its tap intent is `ACTION_VIEW` with type `vnd.android.cursor.dir/calls` (the call log).
  `cmd package query-activities` finds no handler on the emulator, so the tap goes nowhere.
- On other phones the same intent may resolve to the system dialer's call log. That is the full
  dialer UI with a keypad, which is a kiosk escape. A chooser would be just as bad.
- What the kid needs is already in our app: the phone book's missed-call badges and the contact
  sheet's "1 tapt anrop i dag 13:05" (`MissedCallsRepo`).

## Design

1. **Take over the notification as default dialer.**
   - Add a receiver `calls/MissedCallReceiver` for `android.telecom.action.SHOW_MISSED_CALLS_NOTIFICATION`.
   - Telecom checks whether the default dialer has a receiver for this action. If it does, Telecom
     sends the broadcast instead of posting its own notification.
   - Extras: `EXTRA_NOTIFICATION_COUNT`; `EXTRA_NOTIFICATION_PHONE_NUMBER` only when the count is 1;
     `EXTRA_CLEAR_MISSED_CALLS_INTENT`/`EXTRA_CALL_BACK_INTENT` may be ignored. A count of 0 means
     clear.
   - Telecom sends it again at boot with the count of unread missed calls.
   - Verify against AOSP Telecom (`MissedCallNotifierImpl`) how the broadcast is sent, and protect
     the receiver with the matching permission. If the action isn't a protected broadcast, require
     a permission only Telecom holds.
   - Not direct-boot-aware: without an Android credential, CE storage unlocks at boot, and Telecom
     reads the call log after unlock anyway.
2. **Our notification.**
   - Channel `launcher:missed_calls` (DEFAULT importance, no full-screen intent), id 1006.
   - Created before each post, like the call channels.
   - The text is decided by a pure `missedCallNotice(count, number, summaries, names)` with a unit
     test:
     - one contact: "Tapt anrop fra Pappa" / "2 tapte anrop fra Pappa";
     - several contacts: "3 tapte anrop" with the names "Pappa, Mamma";
     - names unknown (call log unreadable, or a callback-window caller): "N tapte anrop" and no
       number;
     - nb + en strings.
   - The count comes from Telecom's extra, the names from `MissedCallsRepo.summaries` and the call rules.
   - Tap: with exactly one contact, `PhoneBookActivity` opens that contact's sheet (new extra);
     otherwise the phone book opens. Set auto-cancel.
   - No action buttons.
3. **Clearing.**
   - Opening a contact's sheet or calling back already calls `MissedCallsRepo.markSeen`.
   - After markSeen, recompute. With nothing unseen left, cancel ours and call
     `TelecomManager.cancelMissedCallsNotification()`, which the default dialer may call. It marks
     the call log's missed calls as read, so the boot re-send stays quiet.
   - When some calls are still unseen, update the text.
   - A broadcast count of 0 cancels ours.
4. **The call-log intent lands in our phone book.**
   - Add `VIEW` + `vnd.android.cursor.dir/calls` (`DEFAULT`) to `PhoneBookActivity`.
   - Register it with `addPersistentPreferredActivity` in `apply()`, next to the Play link blocker,
     so that neither the system dialer's call log nor a chooser can win.
   - Never clear persistent preferred activities (`PlayInvariantsTest`: clearing drops the HOME pin).
5. **Unchanged.**
   - Unmanaged calls: the role goes back to the system dialer, and Telecom delegates to it.
   - The notification rule never cancels our own package.
   - If Telecom ever posts its own notification again, it stays an essential category
     (`missed_call`).

## Checks

- Unit tests:
  - `missedCallNotice`;
  - the manifest has the receiver, its permission and `exported`;
  - the phone book filter;
  - strings in both languages (`TranslationsTest`).
- `scripts/smoke-test.sh`, after the unknown-number step:
  1. Ring the allowed contact and cancel it unanswered (`gsm cancel`).
  2. Expect our notification (pkg = `$PKG`, id 1006) and no `TelecomMissedCalls` record.
- Emulator, by hand:
  1. Tap the notification and confirm the contact sheet opens.
  2. Close the sheet; the notification is gone, and the call log's missed row has `new=0`
     (`content query --uri content://call_log/calls --projection number:type:new`).

## QA review (against AOSP main: Telecom `MissedCallNotifierImpl`, `TelecomServiceImpl`, `CallLogManager`; PM; DPMS)

1. **High - the log isn't marked read for us.** `cancelMissedCallsNotification` works for the default dialer without
   MODIFY_PHONE_STATE, but `clearMissedCalls` skips `markMissedCallsAsRead` when the dialer has the receiver, so
   every boot replays. First set `new=0, is_read=1` where `type=3 AND new=1 AND _id <=` the last id read.
2. **High - count vs seen model; one case never clears.** Telecom counts since its last reset (non-contacts, calls
   already returned). `summaries` uses `rules.contacts`, but only `outbound` ones are shown: an inbound-only contact
   is never "seen", so §3 never clears, and "one contact → sheet" offers Call to a contact the kid may not call.
   Count and names from `summaries` within `phoneBookView(effectiveState())`; the extras are only a trigger.
3. **Medium - simpler clearing.** Drop "recompute after markSeen / update the text": phone book or any contact sheet
   opened, or a tap → cancel ours + #1. Recompute per broadcast and after our call ends (`onCallRemoved`, after the
   async log write); empty → cancel + #1. Badges keep the seen model; they may outlive the notification.
4. **Medium - the boot re-send is a burst**: one broadcast per unread missed row (no date limit), counts 1..N, any
   order. `goAsync` + one coalesced recompute. Count 0 only cancels ours, never calls Telecom (it answers with 0).
5. **Medium - direct boot.** Before the first unlock Telecom finds no non-DBA receiver and posts `TelecomMissedCalls`;
   later its cancel returns before removing it, so both show. Keep non-DBA; §4 is the backstop; fix the smoke test.
6. **Medium - the pin can lose.** Safe next to HOME/Play (DPM keys each filter), but `chooseBestActivity` returns the
   top match on differing priorities *before* reading persistent preferred activities: a privileged dialer whose
   call-log filter has priority > 0 wins. Own `<intent-filter>`, extend `PlayInvariantsTest`, Jelly Star check
   `cmd package resolve-activity -a android.intent.action.VIEW -t vnd.android.cursor.dir/calls`. The pin is
   permanent, so while calls are unmanaged the phone book passes the intent to the system dialer (Play-link style).
7. **Low - receiver.** Protected broadcast, receiver permission READ_PHONE_STATE: `exported="true"`, no
   `android:permission` (as AOSP Dialer), check the action. Without READ_PHONE_STATE the call is lost silently.
8. **Low - lock, kiosk, rule.** LOCKED has no shade or heads-up; keep DEFAULT, no FSI. Take the contact extra only for
   a `phoneBookView` number, in `onNewIntent` too, once. The auto-cancel rule never touches ours; cancel on hand-back.

## Decisions after QA review

All 8 findings accepted; they replace the matching parts of the design above.
- (#1) Before `cancelMissedCallsNotification`, mark the log read ourselves: `new=0, is_read=1` where `type=3 AND
  new=1 AND _id <=` the newest id we have shown (WRITE_CALL_LOG, which we hold since the cleanup round).
- (#2) Count and names come only from `MissedCallsRepo.summaries` filtered to `phoneBookView(effectiveState())`
  (contacts the kid sees and may call). Telecom's broadcast is only a trigger. Nothing left to show means ours is
  cancelled and the log is marked read (#1). The contact-sheet extra is only honoured for a number in that view.
- (#3) Simpler clearing: opening the phone book, opening any contact sheet, or tapping the notification cancels
  ours and marks the log read (#1). The per-contact badges keep their own seen model and may outlive the notification.
  Recompute on every broadcast and after our call ends (`onCallRemoved`, after the call log is written).
- (#4) The receiver uses `goAsync` and one coalesced recompute on a background thread (bursts at boot). A count of 0
  only cancels ours, never calls Telecom.
- (#5) The receiver stays non-direct-boot-aware. The call-log pin (#6) is the backstop for a Telecom notification
  posted before unlock. In the smoke test, our notification must appear, and a `TelecomMissedCalls` record only fails
  the check when it was posted after the step started.
- (#6) Own `<intent-filter>` on `PhoneBookActivity` plus the persistent preferred activity, never cleared (extend
  `PlayInvariantsTest`). While calls are unmanaged, the phone book forwards the intent explicitly to the system dialer
  (Play-link style). Device check on the Jelly Star: `cmd package resolve-activity -a android.intent.action.VIEW -t
  vnd.android.cursor.dir/calls` names us.
- (#7) Receiver `exported="true"` without `android:permission` (protected broadcast, as AOSP Dialer); verify the
  action string; we hold READ_PHONE_STATE.
- (#8) DEFAULT importance, no full-screen intent; the extra is read in `onCreate` and `onNewIntent`, once; ours is
  cancelled when the dialer role is handed back.

## Implementation status (2026-10-06)

Done on `master` (not pushed). L: `./gradlew testDebugUnitTest assembleDebug -PwarningsAsErrors=true` green (545 unit
tests, 17 new); `bash -n` and shellcheck clean on `scripts/smoke-test.sh`. No server change (no API change).

| Part | Commit | What |
|---|---|---|
| L | `10ca3859` | Pure `calls/MissedCallNotice.kt` (`missedCallNotice`, `noticeText`, `noticeStep`, `missedCallContact`, `isCallLogView`; `MissedCallNoticeTest`); `MissedCallsRepo` reads `_id`/`new`, `newestUnreadMissedId`, `markMissedRead`; `calls/MissedCallNotifier` (one background thread) and `MissedCallReceiver`; channel `launcher:missed_calls` + id 1006, nb/en strings; `PhoneBookActivity` call-log filter, unmanaged pass-on, `EXTRA_MISSED_CONTACT`; `AppEnforcer.enforceCallLogPin`, cancel on hand-back; `MissedCallManifestTest`, `PlayInvariantsTest` |
| scripts | `589a4b58` | `smoke-test.sh` "Missed call" step; `docs/testing/emulator.md` §5b (the check and the by-hand steps) |
| docs | (this commit) | this section, `launcher/CLAUDE.md` |

How the QA findings were met:
- **#1** Before `cancelMissedCallsNotification` the log is marked read ourselves: `new = 0, is_read = 1` where `type = 3
  AND new = 1 AND _id <=` the bound of the pass that posted. The bound is the newest unread missed `_id`, queried
  **before** the log is read, so every row up to it has been looked at (rows of non-contacts in that range are marked
  too - they are never shown). WRITE_CALL_LOG is checked first; without it nothing is marked (logged).
- **#2** `missedCallNotice` counts only `unseenMissedCalls` (the badge model) of `phoneBookView(effectiveState())`
  contacts, and of those only rows still unread (`new = 1`) up to the bound - the call log's `new` flag is the
  notification's own "dealt with" mark, separate from the badges' seen marks. Telecom's extras are only a trigger
  (the number extra is never read). Nothing to show: ours is cancelled and the log marked read (#1) - but only with
  managed, readable rules; fail-closed or unmanaged only cancels ours, so a failure never swallows missed calls.
  Calls off or a no-calls time rule leaves only emergency contacts in the view, so nothing is shown then (badges stay).
- **#3** `MissedCallNotifier.dismiss` from `PhoneBookActivity.onResume` (also the tap's landing) and
  `ContactSheet.show` (phone book and Home): cancel ours, mark read up to the shown bound (CE prefs
  `missed_call_notice`, `shown_up_to_id`), call Telecom. Recompute per broadcast and 3 s after `onCallRemoved`
  (Telecom writes the log asynchronously); the after-call pass only updates or clears a notification on screen,
  never posts one.
- **#4** `goAsync`, `finish()` after the pass that covers the broadcast; requests queued while one waits run once.
  Manifest broadcasts reach us one at a time, so a boot burst still runs several cheap passes - an identical notice
  isn't posted again (no rate-limit drops), and it alerts only for an `_id` newer than any alerted
  (`alerted_up_to_id`; a log whose ids went back below it counts as cleared). Re-posts are silent
  (`NotificationCompat.setSilent`; the platform builder has none). Count 0 only cancels ours, never calls Telecom - and
  within 5 s of our own `cancelMissedCallsNotification` it is taken as that call's echo and ignored, so it can't
  remove a newer post.
- **#5** The receiver is not direct-boot-aware; the after-call pass returns before unlock (no call log, no CE prefs).
  The smoke test fails on a `TelecomMissedCalls` record only when its `when` is after the step started.
- **#6** Own `<intent-filter>` (VIEW, DEFAULT, type only - so `content://call_log/calls` matches too) plus
  `addPersistentPreferredActivity` in every `apply()` right after the Play link blocker, never cleared
  (`PlayInvariantsTest` pins both). Unmanaged: the phone book passes a fresh `VIEW` of the type explicitly to the
  system dialer and finishes. `handleNumber` ignores call-log intents.
- **#7** `exported="true"`, no `android:permission`, action checked against the android-36 SDK constant
  (`MissedCallManifestTest`); READ_PHONE_STATE, READ/WRITE_CALL_LOG declared.
- **#8** DEFAULT importance, no full-screen intent, `CATEGORY_MISSED_CALL`, auto-cancel, no actions; the contact extra
  is read in `onCreate` (not after a recreation) and `onNewIntent`, removed, and honoured only for a number in
  `phoneBookView` (the sheet opens once the missed calls are loaded); ours is cancelled on the dialer-role hand-back.
  The notification rule never touches our package.

Known limits: swiping ours away isn't a dismissal (no delete intent) - those calls stay unread and come back,
silently, at the next boot. A Telecom notification posted before the first unlock stays until tapped (the tap lands
in the phone book via the pin).

Device checks [needs device test]:
1. Emulator, script: `smoke-test.sh` "Missed call" (ours, no `TelecomMissedCalls`).
2. Emulator, by hand (`docs/testing/emulator.md` §5b): tap -> the contact's sheet; closed -> notification gone and the
   row `new=0`; two contacts -> "N tapte anrop" with both names, the tap opens the phone book.
3. Reboot with ours not dismissed -> it comes back without a sound; after a dismissal -> nothing at boot.
4. Call back from the sheet or Home -> ours is gone within a few seconds.
5. Calls unmanaged (role handed back) -> ours gone; Telecom/the system dialer shows missed calls again.
6. Jelly Star: `cmd package resolve-activity -a android.intent.action.VIEW -t vnd.android.cursor.dir/calls` names us,
   and a missed call shows ours, not Telecom's.
