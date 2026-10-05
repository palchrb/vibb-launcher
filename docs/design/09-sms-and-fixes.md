# Step 9: SMS allowlist (launcher as default SMS app) + fixes from emulator testing

S = kid-phone-server, L = kids-launcher-mdm (branch `handy`, Kotlin paths under `app/src/main/java/com/kidslauncher/mdm/`).
Builds on 02 (calls), 06 (time rules), 07 (Play), 08 (UI). Every guarantee of 01-08 stays: fail closed, override/pause never
lift call or SMS rules, emergency always works. Claims: [verified: source] (AOSP `main` on android.googlesource.com, read
2026-10-05, or our code) or [needs device test]. AOSP files: roles.xml = packages/modules/Permission
`PermissionController/res/xml/roles.xml`; DPMS = `DevicePolicyManagerService.java`; ISH = telephony `InboundSmsHandler.java`;
LTC = `wm/LockTaskController.java`; ASI = `wm/ActivityStartInterceptor.java`; CGH = SystemUI `camera/CameraGestureHelper.kt`.

## Part A - SMS only with approved contacts
### A1. Platform facts
| Fact | Source |
|---|---|
| `DevicePolicyManager.setDefaultSmsApplication(admin, pkg)` (API 29): device owner, no UI; calls `SmsApplication.setDefaultApplicationAsUser`, which waits up to 5 s for RoleManager and **returns silently** if the package isn't a valid SMS app - check `RoleManager.isRoleHeld(ROLE_SMS)` afterwards | DPMS:11642-11685, SmsApplication.java [verified]; works on the Jelly Star [needs device test] |
| Fallback: `RoleManager.createRequestRoleIntent(ROLE_SMS)`, once a day on Home like the dialer (`DialerRole.kt:27`) | [verified: API]; with DISALLOW_CONFIG_DEFAULT_APPS cleared first (`AppEnforcer.kt:459-460`) [needs device test] |
| ROLE_SMS needs: receiver `SMS_DELIVER` with `permission=BROADCAST_SMS`; receiver `WAP_PUSH_DELIVER` + mimeType `application/vnd.wap.mms-message` with `BROADCAST_WAP_PUSH`; service `RESPOND_VIA_MESSAGE` (`smsto:`) with `SEND_RESPOND_VIA_MESSAGE`; activity `SENDTO` `smsto:`. The role grants phone/contacts/sms/storage/mic/camera/notifications, app-op `write_sms`, and preferred activities for SENDTO `sms: smsto: mms: mmsto:` | roles.xml:305-389 [verified] |
| Only the default SMS app gets `SMS_DELIVER`; afterwards the platform re-broadcasts **`SMS_RECEIVED` to every app holding RECEIVE_SMS** - the default app can't stop it | ISH:1841-1852 [verified] |
| Before the first unlock an incoming SMS stays in the raw table; the system shows a content-free "New message" notification and delivers after unlock (MMS likewise) | ISH:1101-1110, 1180-1200, 1236-1255 [verified] |
| `DISALLOW_SMS` (DO) stops receiving and sending for everyone, the default app included | UserManager doc [verified, see 02 §1] |
| Role revocation only revokes permissions it granted and skips POLICY_FIXED ones; it revokes with `PackageManager.revokeRuntimePermission` (kills the process by default) | Permissions.java:479-510, 700-707 [verified]; kill on handback [needs device test] |
### A2. Model (pure, `calls/SmsRules.kt`, JVM-tested)
- `smsMode: OFF | CONTACTS | OPEN` per device. OPEN = today's "SMS on" (any SMS app, no filter, upstream-compatible); CONTACTS =
  our app is the SMS app and filters; OFF = `DISALLOW_SMS` as today (`EnforcementPlan.kt:142-156`).
- Per-contact flags: **reuse the call flags** (receive from `inbound`, send to `outbound`) - one mental model "who may reach
  him / whom he may reach" (question 1). `message_app` only picks which app the Message button opens.
- `decideIncomingSms(sender, state, windowOpen, rule)`: ALLOW if the normalized sender (`PhoneNumbers.normalize`, 02 §2.1)
  is an inbound contact, or a platform-confirmed emergency number, or the call callback window is open (`CallSystem.kt:117`;
  113/AMK may text a location link [needs verification]); BLOCK for alphanumeric senders ("Vipps", "ice"), withheld, unknown,
  `UnknownFailClosed`. `decideOutgoingSms(dest, state, rule)`: ALLOW emergency numbers always, outbound contacts unless the
  active time rule blocks calls (school: no messages in or out, 06 decision); BLOCK otherwise. Override/pause never ALLOW.
- `smsRoleAction(mode, held, takenByUs)` = `dialerRoleAction` shape (`DialerRole.kt:17-21`): CONTACTS → TAKE; otherwise
  RELEASE only a role we took. `restrictSms(mode, held)` = OFF, or fail-closed, or (CONTACTS && !held) - **no role, no SMS**.
- Emergency SMS: Norway has no general SMS-to-112; a registered emergency-SMS service for deaf/hard of hearing exists
  [needs verification: number, registration]. Policy: never block sending to an `isEmergencyNumber` destination; AML
  (system-sent location SMS during an emergency call) doesn't go through us; whether `DISALLOW_SMS` (mode OFF) blocks AML
  [needs device test] - if it does, document it on the calls page.
### A3. Launcher components (new package `sms/`, manifest entries exactly as roles.xml requires)
- `SmsDeliverReceiver` (`SMS_DELIVER`): `Telephony.Sms.Intents.getMessagesFromIntent`, join parts, `decideIncomingSms`.
  ALLOW → insert into `Telephony.Sms.Inbox` (read=0, seen=0) - **only accepted messages ever reach the provider** - then
  notify. BLOCK → nothing visible: no provider row, no notification; append `{sender, at, kind:"sms"}` to a CE queue
  (`SmsEventLog`, max 200, like `BlockedEventLog`) for the parent; the body is dropped (question 2).
- `MmsPushReceiver` (`WAP_PUSH_DELIVER`): v1 text-only - never downloads MMS. Pure `parseMmsFrom(pdu)` reads the From header
  of the m-notification-ind (unit-tested with captured PDUs). Allowed sender → a local "Picture message - can't be shown on
  this phone" row in our own table (not the provider); otherwise logged as `kind:"mms"`. MMS stays on the carrier server.
- `RespondViaMessageService` (`RESPOND_VIA_MESSAGE`, from the system in-call UI): `decideOutgoingSms`, then send.
- `ComposeActivity` (`SENDTO`/`VIEW` `sms: smsto: mms: mmsto:`; also persistent preferred via DPM like the Play link blocker,
  `AppEnforcer.kt:863-885`): allowed number → opens `ThreadActivity`; else "You can't send messages to this number".
  Prefilled `sms_body` kept as a draft, never sent without a tap.
- `SmsSender`: `getSystemService(SmsManager)`.`sendMultipartTextMessage` with sent PendingIntents; the default app must write
  its own Sent rows (outbox → sent/failed). Every send path re-checks `decideOutgoingSms`.
- Other apps can't go around us: extend `applyCallPermissions` (`AppEnforcer.kt:408-446`) to DENY `SEND_SMS`, `RECEIVE_SMS`,
  `READ_SMS`, `RECEIVE_MMS`, `RECEIVE_WAP_PUSH` for non-system packages while mode = CONTACTS or OFF (covers the
  `SMS_RECEIVED` rebroadcast). Self-grant the same + READ_CONTACTS policy-fixed (`QuickControls.kt:121-143`) so a role
  handback never kills our process (A1 last row); whether DPM may grant these hard-restricted permissions [needs device test].
- Google Messages/AOSP Messaging/STK (`KNOWN_SMS_PACKAGES`, `EnforcementPlan.kt:204-211`) suspended+hidden whenever mode !=
  OPEN (today only when off), override or not: kills the **RCS bypass** (DISALLOW_SMS/role don't cover RCS). Runbook: turn
  RCS chats off in Messages before enrolling so the number deregisters; otherwise contacts' RCS messages may be lost until
  their app falls back to SMS [needs device test].
- Direct boot: not needed - the platform holds SMS until unlock (A1). Components stay non-direct-boot-aware.
### A4. Kid UI (Views, Nunito, `KidAvatars`, wallpaper ink - matches 08)
- Home: `GridTile.Messages` after `PhoneBook` (`HomeModel.kt:22-60`) when mode = CONTACTS and the role is held; badge =
  unread total. Contact avatars: badge = missed calls + unread SMS (pure `contactBadge`), refreshed by a ContentObserver on
  `Telephony.Sms.CONTENT_URI` next to the call-log one (`HomeActivity.kt:274`).
- `MessagesActivity`: rows = phone-book contacts with inbound or outbound (same `phoneBookView`), sorted by last message,
  avatar, name (800), snippet (600), unread dot; style of `activity_phone_book.xml`/`item_kid_contact.xml`.
- `ThreadActivity` (singleTop, extra = contact id): bubbles (in = white/ink, out = tile colour), date separators, compose bar
  (hidden with "You can't send to X" if not outbound or the rule blocks), 1 SMS ≈ 160 chars counter. Marks read on open.
- Contact card Message (`ContactSheet.kt:67-97`): `resolveMessageButton` (`MessageButtons.kt:36-55`) gets `ownPackage`; when
  `messageApp=="sms"` and the default SMS package is ours → internal `ThreadActivity` intent (not the allowlist check).
  `messagingAppPackages` (`:62-73`) unchanged (our package is never suspended).
- Notifications: channel "Messages", `MessagingStyle` with contact name/photo, tap → ThreadActivity; no inline reply in v1.
  Our own notifications are already excluded from app badges (`BadgeCounts.kt:23-25`). During a time-rule lock: stored,
  notified silently, thread not reachable (LockActivity on top).
### A5. Server
- Migration `0029_sms.sql`: `device_policy.sms_mode TEXT NOT NULL DEFAULT 'contacts' CHECK IN ('off','contacts','open')`,
  backfilled from `sms_enabled` (0→off, 1→open: nothing changes until the parent picks "contacts"); `roles_changed_at TEXT`
  (B1); `device_sms_events(id, device_id, sender, kind, at, received_at)` + index, purged after 30 days.
- Wire (`CallPolicy`, `models.rs:443-449`): add `sms_mode`; keep `sms_enabled = (mode == 'open')` so an **old launcher fails
  closed** (contacts → SMS blocked). L: `smsMode` present wins, else `smsEnabled` → OPEN/OFF (`dto/CallPolicy.kt`).
- `POST /api/devices/sms-events` (like `dns-events`, `main.rs:541`); calls page: SMS radio (Off / Approved contacts / Anyone
  (no filter)), "Blocked messages" card (sender, time, SMS/MMS), device-page summary line.
- Status: `call_state.sms_role_held`, `sms_mode` (applied), capability `sms_policy_v1`; warnings in `call_warnings`
  (`calls.rs:183-300`): no capability → "update the launcher, SMS blocked meanwhile"; role not held → "SMS blocked until the
  launcher is the SMS app" (subject to B1's pending rule).
### A6. Handing the role back (lessons from the dialer)
Order in `apply()` when mode leaves CONTACTS or calls become unmanaged: (1) `DISALLOW_SMS` per the new mode; (2) the plan
unhides/unsuspends the previous holder (stored at TAKE as `sms_role_previous`; fallback: first installed `KNOWN_SMS_PACKAGES`);
(3) clear DISALLOW_CONFIG_DEFAULT_APPS, `setDefaultSmsApplication(prev)`, verify with `isRoleHeld`; (4) clear `takenByUs`;
(5) bring Home to front if it isn't (B2) and request a report (B1). Our policy-fixed permissions go back to DEFAULT on the next
`apply()`, never in the same pass. Messages accepted while managed stay in the provider, so Google Messages shows them.

## Part B - fixes from emulator testing (Android 16, debug)
### B1. Stale role/call-log warnings right after "manage calls" (S + L)
Cause: the page renders the last report, sent while unmanaged (`calls.rs:187-190`); DPM's dialer setter is synchronous
(waits ≤20 s, DPMS:11688-11712 [verified]) and the sync reports right after `apply()` (`MdmSyncWorker.kt:165, 196-215`), so the
next report is already correct. Fix: S sets `roles_changed_at = now` whenever `calls_managed` or `sms_mode` changes
(`calls.rs:360-372`); `call_warnings` treats a report with `reported_at < roles_changed_at` **or** whose `call_state.state`
disagrees with `calls_managed` as pending: role/call-log/SMS-role warnings are replaced by "Waiting for the phone to confirm
(last report HH:MM)", and the page auto-refreshes every 5 s for 2 min. L: `apply()` runs outside a sync too (pause, recheck,
Home's role prompt) - when `applyDialerRole`/`applySmsRole` did a TAKE/RELEASE or `isRoleHeld` changed since the last
report, call `SyncRunner.request(context, "role-changed")` (`push/SyncRunner.kt:44`, coalesced).
### B2. Camera in front of Home (kiosk LOCKED, HOME ours)
Analysis: `STILL_IMAGE_CAMERA` (not `_SECURE`) from a foreign uid is what SystemUI's camera gesture sends when the keyguard is
dismissible, as on a lock-less emulator (CGH:140-151, 90-135 [verified]); triggers: power double-press
(`GestureLauncherService`, setting `CAMERA_DOUBLE_TAP_POWER_GESTURE_DISABLED`), the lock-screen camera affordance, the
camera-lift sensor. LockTask only lets a new task start if its package is in `setLockTaskPackages` (LTC:380-400 [verified]),
so Camera2 was most likely allowlisted (or a not-allowlisted Camera would have been hidden, `EnforcementPlan.kt:155`). Second
suspect for "turned calls OFF": the dialer handback revokes role-granted permissions and may kill our HOME process
(A1 last row) [needs device test]. Diagnose first: `dumpsys activity activities | grep -B2 -A8 camera2`
(launchedFromPackage), `logcat -b events | grep -E "am_kill|wm_create_activity"`, `dumpsys device_policy | grep -A3
lockTask`. Mitigations:
- `dpm.setKeyguardDisabledFeatures(admin, KEYGUARD_DISABLE_SECURE_CAMERA)` always while managed - SystemUI refuses the gesture
  on a secure keyguard (CGH:155-165 [verified]). Parent "camera off" (PLAN) → `setCameraDisabled(true)` also stops the gesture
  everywhere (CGH:156).
- The gesture setting can't be set by a DO: `setSecureSetting` allows only DEFAULT_INPUT_METHOD, SKIP_FIRST_USE_HINTS,
  INSTALL_NON_MARKET_APPS, LOCATION_MODE (DPMS:716-723 [verified]) → runbook: Settings → Gestures → double-press power off
  before enrolling (Settings is unreachable later).
- Self-grant policy-fixed every permission we rely on (READ_CALL_LOG, READ_CONTACTS, CALL_PHONE, SMS) so role changes never
  kill us; after any role TAKE/RELEASE, if kiosk is on, `startActivity(HomeActivity, NEW_TASK|REORDER_TO_FRONT)` (DO + HOME
  may start from background, 02 §1).
- `LOCK_TASK_FEATURE_BLOCK_ACTIVITY_START_IN_TASK` (B4) also blocks non-allowlisted activities pushed by others.
### B3. Call-log reads while unmanaged (L)
`CallStateReport.build` (`CallStateReport.kt:37-39`) runs every sync and calls `lastEmergencyCallMs`/`callbackWindowUntil` →
`readOutgoingCalls` (`CallSystem.kt:94-111`) without a permission check, logging a SecurityException. Fix: `readOutgoingCalls`
returns null at once unless `checkSelfPermission(READ_CALL_LOG) == GRANTED` (pure `canReadCallLog(granted, unlocked)`);
`MissedCallsRepo.readLog` (`:33-58`) likewise; the emergency report then uses only our DE record. `call_log_readable` stays
reported (server warns only while managed, `calls.rs:278`).
### B4. Play Store can't be suspended (L + S)
Verified: suspension is refused for the required **verifier** (Play on GMS phones), installer, uninstaller, permission
controller, default dialer, device admins and protected packages (SuspendPackageHelper.java:528-570). Hiding Play is allowed
(PMS:5940-5993) but uninstalls it for the user (no updates, no FCM) - rejected (PLAN). The refusal is already logged
(`AppEnforcer.kt:284-294`). Replacement:
- **`LOCK_TASK_FEATURE_BLOCK_ACTIVITY_START_IN_TASK` (1<<6, public)**: while LOCKED, every activity start whose package isn't in
  the lock-task packages is replaced by the system's BlockedAppActivity - explicit intents into an allowed app's task too
  (ASI:364-380, LTC:419-431 [verified]). OR it into the features next to KEYGUARD (`EnforcementPlan.kt:136`).
- That path has **no emergency exemption** (unlike LTC:380-400), so lock-task packages gain pure `lockTaskHelpers`: system
  dialer, `com.android.phone` (emergency dialer), the cell-broadcast receiver (Nødvarsel), `android` (chooser/resolver),
  DocumentsUI and the photo picker (file/photo pickers in allowed apps); never Settings, Play, GMS. Unit-tested; emergency
  call + cell broadcast + pickers in kiosk [needs device test]. Cost: GMS sheets (account picker, Credential Manager) and Play
  in-app review/billing/update sheets are blocked in kiosk - intended, but check Vipps [needs device test].
- Keep: link blocker (never lifted), Play out of kiosk/Home, `setPackagesSuspended` where it works; install mode pins Play.
- Report `play_store_suspendable` (false when refused) in status; S "Push and Play" card: "Play can't be suspended on this
  phone - Play is blocked only while kiosk is on".
- **Residual risk**: kiosk off → Play UI reachable from allowed apps by explicit intent; Play notifications open Play when
  kiosk is off; with kiosk on, nothing known [needs device test with checklist 07 #7].

## Ordered tasks (`[local]` = cargo test / testDebugUnitTest)
| # | Repo | Task | Verify |
|---|---|---|---|
| 1 | L | B3 permission gate + `canReadCallLog` test | [local] |
| 2 | S | B1 `roles_changed_at`, pending rule, auto-refresh; tests: stale report → "waiting", newer → warnings | [local] |
| 3 | L | B1 report after role change outside a sync (pure `roleReportNeeded(prev, now)`) | [local] |
| 4 | L | B4 BLOCK_ACTIVITY_START_IN_TASK + `lockTaskHelpers` in `computeEnforcementPlan`; `play_store_suspendable` | [local] + device 1-3 |
| 5 | L | B2 keyguard camera feature, policy-fixed self-grants, Home to front after role change | device 4-5 |
| 6 | S | A5 migration, `sms_mode` wire + compat, events endpoint, page, warnings, tests | [local] |
| 7 | L | `SmsRules.kt` (+ `parseMmsFrom`) + tests (vectors from 02, alphanumeric, window, rule, fail-closed) | [local] |
| 8 | L | DTO `smsMode` + compat; plan: `restrictSms(mode, held)`, Messages hidden unless OPEN, SMS permission denials | [local] |
| 9 | L | Manifest + receivers/service/ComposeActivity/SmsSender/provider writes; role TAKE/RELEASE + prompt fallback | device 6-10 |
| 10 | L | MessagesActivity, ThreadActivity, Messages tile, badges, notifications, Message-button routing | device 8, 11 |
| 11 | L | Event queue → server; status `sms_role_held`/`sms_policy_v1` | device 12 |

## Device checklist (Jelly Star release + Android 16 emulator; B allowed in+out, C unknown, D inbound-only)
1. Kiosk on: allowed app opens Play via explicit intent, `market://`, `play.google.com` link, Play notification → blocked.
2. Kiosk on: 112-test (emergency test mode, never 112), keyguard emergency dialer, cell-broadcast test alert all appear.
3. Kiosk on: Element photo picker, share sheet, file picker work; Settings from an app is blocked.
4. Power double-press / lock-screen camera with PIN: no camera; reproduce the emulator case with the diagnostics of B2.
5. Calls managed ON→OFF→ON: no `am_kill` of our package, Home stays in front, page shows "waiting" then correct state.
6. Mode CONTACTS: `cmd role get-role-holders android.app.role.SMS` = us without a prompt; Messages suspended+hidden.
7. B texts → notification, thread, badge; C texts → nothing on the phone, parent log shows C; alphanumeric sender blocked.
8. Kid texts B (ok), D (compose hidden), C via an app's `smsto:` link (refused); `adb shell am start -a SENDTO` likewise.
9. Third-party app with SEND_SMS/RECEIVE_SMS shows "blocked by admin"; it never sees B's message.
10. MMS from B → placeholder; from C → logged only. Reboot, B texts before unlock → generic notification, delivered after.
11. School rule: send blocked, receive silent; budget used up: messaging works. Override PIN: C still blocked.
12. Mode → OPEN/unmanaged: Messages back and default, our process alive, accepted messages visible in Messages.

## Open questions for the user
1. SMS allowlist = the call in/out flags (proposed), or separate SMS flags per contact?
2. Blocked-SMS log for the parent: sender + time only (proposed), or also the text?
3. Alphanumeric senders (Vipps, bank, carrier "ice"): block all (proposed), or a parent-editable list of allowed sender names?
4. Is the BLOCK_ACTIVITY_START_IN_TASK cost (Google sign-in/Play sheets blocked in kiosk) acceptable, e.g. for Vipps?

## Decisions after QA review (qa-09-design.md), 2026-10-05

QA findings override this doc where they conflict (all P0/P1 items are binding). Product
defaults (proposed to the user, pending confirmation; easy to change):
- Separate per-contact SMS in/out flags (not reusing call flags).
- Blocked-SMS log shows sender and time; message text only if the parent turns it on.
- Named/alphanumeric senders blocked except a parent-edited sender allowlist, pre-seeded
  with Vipps and ice.
- The lock-task activity block stays on in kiosk (blocks Play UI and Google sign-in inside
  apps); updates/sign-in happen in PIN install mode; server off-switch per device.
- Emergency: explicit ACTION_EMERGENCY_DIAL to an allowlisted emergency dialer from every
  lock/bedtime/school screen; never ACTION_DIAL to an unpinned app.
