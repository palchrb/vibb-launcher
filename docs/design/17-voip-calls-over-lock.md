# 17 - VoIP calls over the PIN lock (Element X and other allowed messaging apps)

User report (emulator, 2026-10-06): an Element X voice or video call to the kid only rings once the phone is
unlocked. With the screen off, the kid must unlock first, and by then the call is usually gone. Phone calls
already ring over our lock (our InCallActivity is exempt), and VoIP calls must do the same.

## Facts (Element X develop source, checked)

- **No Telecom.** There is no ConnectionService and no MANAGE_OWN_CALLS, so `TelecomManager.isInCall` stays false,
  and our call screening, rules and call screen never see these calls. Known gap, launcher/CLAUDE.md "Calls & SMS".
- **Ringing** (`RingingCallNotificationCreator`, posted by `ActiveCallManager`):
  - one notification with fixed id `NotificationIdProvider.getForegroundServiceNotificationId(INCOMING_CALL)` and no
    tag;
  - `CATEGORY_CALL`, CallStyle `forIncomingCall`, ongoing + insistent, ringtone sound;
  - `setTimeoutAfter(ring duration)`, and `setFullScreenIntent(IncomingCallActivity, true)`;
  - the activity sets `FLAG_SHOW_WHEN_LOCKED | FLAG_KEEP_SCREEN_ON`.
- **No caller identity.** The caller `Person` has only a name and icon, no key. The sender's MXID is only inside the
  PendingIntent extras, which we can't read. So we **cannot verify who is calling**: a display name is spoofable.
- **In the call:** `ElementCallActivity` (task affinity `io.element.android.features.call`, picture-in-picture) plus
  `CallForegroundService` (`foregroundServiceType="microphone"`, channel `call_foreground_service_channel`). Audio
  mode is `MODE_IN_COMMUNICATION`.

## Why it fails today

While LOCKED, `PinLockActivity` holds lock task and the re-front loop brings it back (exemptions: our call,
`isInCall`, the emergency flow, the clock alarm). The status bar is also disabled, and SystemUI may not launch a
full-screen intent then (step 10's open device check). With the kiosk off, the lock task list while LOCKED is only
our package and helpers, so Element's activities are blocked too.

## Design

1. **Detect a ringing VoIP call.** In `BadgeListenerService` (reading no text), trigger on a posted notification that is:
   - from a package in the contacts' messaging apps (`messagingAppPackages`), allowlisted and not suspended;
   - `CATEGORY_CALL` with a `fullScreenIntent`.

   The pure decision `voipRinging(pkg, category, hasFsi, policy, time state)` ignores it unless phone calls would be
   allowed now: calls managed with rules, no calls-off and no no-calls time rule. The override PIN and the pause
   don't change that.
2. **Yield while LOCKED.**
   - `RefrontPolicy` gets the exemption `VoipCall(pkg)`.
   - The lock task list while LOCKED gets `pkg` (through `LockTaskChrome` only, as an input of `lockTaskWhileLocked`).
   - Then we send the notification's full-screen intent ourselves, as HOME + device owner from the visible lock, with
     background-start opt-in, and wake the screen (our lock's `turnScreenOn` path). We don't depend on SystemUI.
3. **Exemption lifetime:**
   - It starts at the ringing notification.
   - It lasts while that notification exists, then while the audio mode is `IN_COMMUNICATION` and the top task
     belongs to `pkg`. Max 3 h, and a reboot ends it, using `timedWindowActive`.
   - When the call ends (notification removed and audio mode normal), LOCKED shows the lock again, the same as
     after a phone call.
   - Leaving Element's call screen for its chat list during the call stays exempt. This is a known, accepted gap: we
     can't see another app's activity name.
4. **Screen time:** unchanged. Another app's screen counts.
5. **Element's own PIN** isn't our concern.

## Accepted limit (tell the user)

Any Element X caller rings over the lock, not just contacts, because the notification doesn't say who it is. Who can
call is set on the Matrix side (homeserver vibb.me): restrict invites and DMs to known users. Our allowlist can't do it.

## Checks

- **Unit tests:** `voipRinging`, the exemption lifetime, `lockTaskWhileLocked` with a VoIP package.
- **Emulator:** screen off and LOCKED, call from another Element account.
  - Element's ringing screen shows over the lock and can be answered.
  - After hang-up the PIN lock is back.
  - A declined or timed-out call returns to the lock.
  - Kiosk on and off.

## QA review (2026-10-06)
Checked against `lock/`, `badges/`, `LockTaskHelpers.kt`, `timerules/ScreenTime.kt`, AOSP `android16-release` (LTC LockTaskController, AR ActivityRecord, BASC BackgroundActivityStartController, NAH NotificationAttentionHelper, DPMS) and Element X `develop` (`RingingCallNotificationCreator`, `CallForegroundService`, `ElementCallActivity`, call manifest).
1. **Critical - the ring is silent over the lock whatever we launch.** While LOCKED, `setStatusBarDisabled(true)` (DPMS:705-709) and the LOCKED lock-task features without NOTIFICATIONS (LTC:112-115) both set DISABLE_NOTIFICATION_ALERTS. NAH then suppresses every notification sound and vibration (NAH:1030-1066, "booleanState"). Element's ringtone is only its notification sound (`setSound` + INSISTENT, no player of its own), which is probably most of the report. Change: our lock plays the default ringtone + vibration itself (ringer mode/DND respected) while the ring exemption is active; never lift the shade flags.
2. **High - RefrontPolicy isn't the only thing that shows the lock.** `step()` shows it on ScreenOn/USER_PRESENT, ProcessStart, TimeRuleShown and RemoteLock; Home's onResume and `LockActivity.start` re-show it. IncomingCallActivity sets FLAG_TURN_SCREEN_ON, so its own wake fires our SCREEN_ON and puts the lock over it. VoIP must be a `step` input like `systemCall`, not just a RefrontPolicy exemption.
3. **High - BAL for sending Element's FSI.** For a PendingIntent *sender* AOSP checks only: visible window, foreground process, BAL/SAW permission, persistent system, companion (BASC:1146-1240). The HOME and device-owner exemptions are caller-only (BASC:1069/1091), and Element (target 35+, no creator opt-in) gives none (BASC:441-470). So: send only from a resumed, visible lock with `setPendingIntentBackgroundActivityStartMode(ALLOW_IF_VISIBLE)` on 36 (ALLOWED needs `@Suppress` under `-PwarningsAsErrors`). Wake first with *our own* activity start (turnScreenOn; DO-exempt as caller), then verify: a BAL block is silent, and a re-post with FLAG_CANCEL_CURRENT throws CanceledException. Never send `contentIntent`: it is Element's answerIntent and answers at once.
4. **High - lock-task list while LOCKED (kiosk off).** Adding pkg is safe. Removing it runs `updateLockTaskPackages` -> `performClearTaskForReuse` (LTC:765-789), which finishes ElementCallActivity; its onDestroy stops CallForegroundService and the call ends. Element cancels the ringing notification on answer *before* `setCallIsActive` starts its FGS, so a re-front/chrome pass in that gap kills the answered call. Change: exemption = ringing CALL notification **or** Element's call FGS notification (FLAG_FOREGROUND_SERVICE, channel `call_foreground_service_channel`, alive from `setCallIsActive` to `onDestroy`), with ~15 s grace; remove pkg only after that. Re-derive the exemption at process start (NLS active notifications) before the first `LockTaskChrome.refresh`, and add VoIP to SelfUpdate's `liveCall`: a crash or the night update would otherwise drop pkg mid-call. Kiosk on: list untouched (Element is allowlisted), only the yield.
5. **High - audio mode / "top task is pkg".** The top task isn't observable (no usage-stats appop for a DO), and any app can hold MODE_IN_COMMUNICATION (WebView/WebRTC often leaves it set), so it ORed into the lifetime is a 3 h hole. Use the FGS notification as the in-call signal; audio mode only as an extra AND. At the 3 h cap, re-front the lock but don't pull pkg mid-call (4), or document the hang-up.
6. **High - what the window opens.** With the kiosk off the LOCKED features have no BLOCK_ACTIVITY_START bit, so anything Element starts in its own task (links/Custom Tabs, attachment viewers, maps) runs over the lock. Kiosk on: every allowlisted app reachable by an Element intent. Set the block bit while VoIP-exempt with the kiosk off, and pin the permission controller then (mic/camera prompt). Document the kiosk-on surface next to "chat list".
7. **High - an existing hole of the same class.** The "system_call" yield uses `TelecomManager.isInCall`, which includes self-managed ConnectionService calls. Any allowlisted app with MANAGE_OWN_CALLS (a normal permission: Signal/Molly, WhatsApp, games) holds the lock open for as long as its "call" lasts, with no cap. Use `isInManagedCall` there; send self-managed apps through this design's gate, cap and exemption signal.
8. **Medium - the "calls allowed now" gate.** "Override/pause don't change it" contradicts the rule that they lift a time rule's call block (launcher CLAUDE.md "Calls & SMS"), and "calls managed with rules" excludes Unmanaged calls (phones with only apps managed + PIN lock). Use the phone path's answer: `CallPolicyStore.effectiveState()` + `callsBlockedFor` -> Unmanaged, or Managed with calls on and no blocking rule; never UnknownFailClosed. Element must be unsuspended (a time-rule lock suspends it unless usable).
9. **Medium - screen time.** `screenTimeCounts(..., pinLocked)` stops counting while LOCKED, so up to 3 h of Element over the lock is free, against §4. Pass `pinLocked && !voipExempt`.
10. **Medium - PiP.** PiP is refused in lock task (AR:3282-3299: RESUMED needs `!isCurrentAppLocked`), so there is no PiP over the lock, and none at all with the kiosk on. Kiosk off: a call in PiP, then screen-off -> the lock's `startLockTask` removes pinned tasks (LTC:676) -> Element's "Exiting PiP mode: Hangup the call". Device-check and document, or keep the lock re-front-only (no lock task) while Element's FGS exists.
11. **Medium - FSI permission.** On 14+ NMS drops `fullScreenIntent` when the app lacks USE_FULL_SCREEN_INTENT, so `hasFsi` is false and nothing rings over the lock. Detect that state (FLAG_FSI_REQUESTED_BUT_DENIED), report it, device-check a sideloaded Element.
12. **Simpler, recommended.** Don't auto-launch Element in the background. While the ringing CALL notification (or the FGS) of an allowed messaging app exists: kiosk off -> pin pkg (+ block bit, 6); `step`/RefrontPolicy treat it like `systemCall`. The lock plays the ringtone (1), wakes itself, and shows an "Element call - Answer / Decline" card: no caller name (that would be text). Answer sends the FSI (IncomingCallActivity) from the visible lock; Decline sends the CallStyle decline action. That keeps it user-initiated, BAL-clean, with no reliance on SystemUI. During the call, LockResumed re-sends the FGS `contentIntent` (ElementCallActivity), like `showCall`.
13. **Checks to add:** unit `voipExemption` (ring -> gap -> FGS -> end, grace, cap, process start); RefrontPolicy and `step` with VoIP; `isInManagedCall` yield; `lockTaskWhileLocked` keeps pkg during the grace. Emulator: ringtone audible while LOCKED, answer from screen-off and screen-on, no call loss in the ring->FGS gap, kill our process mid-call, decline/timeout back to the lock, kiosk on/off, PiP + screen-off (kiosk off).

## Decisions after QA review

- **User (2026-10-06): who may call the kid is enforced on the Matrix homeserver (vibb.me)**, not by the launcher.
- **Build QA #12 (the simpler design)** with #1-#11: while an allowed messaging app's ringing CALL notification exists,
  the PIN lock wakes itself, plays the default ringtone + vibration (ringer mode/DND respected) and shows a card
  "<app label> - Svar / Avvis" (app label incl. design 14's override; no caller name). Answer sends the FSI from the
  visible lock (`ALLOW_IF_VISIBLE`), Decline sends the CallStyle decline action; never the contentIntent.
- In-call signal = the app's call foreground-service notification (Element: FLAG_FOREGROUND_SERVICE, channel
  `call_foreground_service_channel`) with a 15 s grace after answer; audio mode only as an extra AND; 3 h cap re-fronts
  the lock but never pulls the package mid-call. The exemption is a `step` input like `systemCall`, re-derived at
  process start, and counted as a live call for the self-update gate.
- Kiosk off: pin the package + the block bit + the permission controller while exempt; never remove the package
  during the grace. While exempt the lock doesn't start lock task (avoids the PiP hang-up, #10).
- Gate = the phone path's answer (#8); screen time counts while exempt (#9); FSI-denied state is reported (#11).
- Fix the existing hole (#7): the system-call yield uses `isInManagedCall`; self-managed apps go through this gate.
