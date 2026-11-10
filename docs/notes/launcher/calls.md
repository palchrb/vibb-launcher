# Launcher notes: calls, SMS, missed calls, VoIP over the lock

Moved from launcher/CLAUDE.md on 2026-11-01.

## Calls & SMS (palchrb fork, design `docs/design/02-calls.md`, at the monorepo root)

The server's `call_policy` (always sent by our server with an explicit `managed`) turns the launcher into the phone app. Decisions are pure Kotlin in `calls/` (JVM-tested); the Android classes there only feed them and act:
- **Pure**: `PhoneNumbers.kt` (normalisation + `tel:`/`sip:` handle parsing; runs the server's shared `phone_vectors.json`, copy in `app/src/test/resources/`, must be identical to `../server/testdata/phone_vectors.json` - `PhoneNumbersTest` fails otherwise; change both together), `CallRules.kt` (`decideOutgoing`/`decideIncoming`, `Emergency`, `callbackWindowUntil`), `CallPolicyStates.kt` (`callPolicyState`, `callPrefsUpdate`), `DialerRole.kt` (take/release the role, prompt, default-apps lock), `MessageButtons.kt`. `PolicyGate.judgeFresh` and `computeEnforcementPlan` carry the call parts of acceptance and enforcement.
- **States**: `Unmanaged` (explicit `managed: false`, or no `call_policy` on a phone whose calls never were managed), `Managed(rules)`, `UnknownFailClosed` (emergency calls and the callback window only). A response **without** `call_policy` after managed calls is rejected whole (REJECT_SUSPECT); `calls_managed_last` and `last_call_rules` are written in the same `commit()` as the cache, only from an explicit `managed`, and a corrupt/missing cache falls back to those last rules before fail-closed. `CallPolicyStore` holds the state in memory (the services answer from memory within Telecom's budget), starts `UnknownFailClosed`, and is refreshed first thing in `Application.onCreate` (reads SharedPreferences directly, not `LauncherPreferences`), after every accepted sync, and by each call service's `onCreate`. Everything after it in `Application.onCreate` (`BootClock`, `LauncherPreferences.init`, the rest) is wrapped so an exception there no longer kills the process (QA #10: a dead process means unscreened calls); an uncaught exception elsewhere still does (global handler).
- **The override PIN and "pause all restrictions" never open calls** (decision after QA review): they lift app restrictions only; call rules, `DISALLOW_SMS`, `DISALLOW_OUTGOING_CALLS` (when calls are managed), the SMS-app suspension and the call-permission denials stay. This is the explicit exception to the "every restriction is liftable" standing rule below. They **do** lift a time rule's call block (school mode, step 6, user decision 2026-10-05) - back to the call rules, never beyond.
- **Dialer role**: while managed (or fail-closed) `AppEnforcer.applyDialerRole` takes it with `setDefaultDialerApplication` (no prompt); a failure is reported in `callState.lastError` and Home shows the system role prompt at most once a day. Unmanaged, a role we took (`CallPrefs.dialerRoleTakenByUs`) is handed back to the system dialer. `ROLE_CALL_REDIRECTION` has no device-owner API: grant it at provisioning (`adb shell cmd role add-role-holder android.app.role.CALL_REDIRECTION me.vibb.launcher`) (Home can't prompt for it: see below). The same for `ROLE_CALL_SCREENING` (security review L7, `... android.app.role.CALL_SCREENING ...`): Telecom binds one screening service besides the carrier's - the screening role holder's, else the default phone app's (`CallsManager.setUpCallFilterGraph`) - so holding it keeps incoming calls screened by us also when we aren't the phone app, and keeps another app from screening instead of us; reported as `callState.screeningRoleHeld`, prompted by Home only while we aren't the phone app (`rolePromptFor`, one prompt a day), and the server warns when calls are managed and neither the dialer nor the screening role is ours. As soon as the dialer role is held, `DISALLOW_CONFIG_DEFAULT_APPS` is set (cleared before the dialer role changes) - not waiting for the redirection role, which must be granted with adb before handover. The restriction stays on: on Android 14+ PermissionController refuses every exclusive role's request under it (AOSP `Role.getRestrictionIntentAsUser` opens "blocked by your admin"), so Home never prompts for redirection or screening while we hold the dialer role - lifting it for a prompt was tried and dropped (QA of cmd-expiry, Medium 2: the kid could hold the lift open and change default apps). Provisioning grants both with adb.
- **Outgoing**: `KidCallRedirectionService` cancels a not-allowed call before it's placed (the other phone never rings), and redirects an allowed call typed differently to the contact's stored E.164 number (`outgoingDialTarget` - dial what was checked); `KidInCallService` disconnects anything that slips through, and calls of unknown direction unless emergency or a contact allowed either way (`decideUnknownDirection`). Emergency numbers are always allowed (platform `isEmergencyNumber` ORed with the country's real numbers - 112/911 plus 110/113 for +47 - never 000/08/118). `DISALLOW_OUTGOING_CALLS` (emergency exempt) replaces our rules only when calls are off, the rules are unknown, or our dialer isn't in place; with calls unmanaged, step 1's rule (managed allowlist + dialer not allowlisted) still applies.
- **Incoming**: `KidCallScreeningService` rejects first (no ring); `KidInCallService` re-checks every incoming call (withheld numbers are never screened, screening fails open after 5 s). Withheld and failed-verification callers are blocked when managed. The **callback window** (60 min, anyone may call) opens only after a call the platform confirms as an emergency number that connected (call log `DURATION > 0`, or our in-call service seeing it active) - never from the static list. E7's H1 (Android 16 emulator): Telecom showed the lock's 112 call in the system dialer (`EmergencyInCallServiceConnection`), so our in-call service never saw it, and AOSP keeps emergency numbers out of the call log unless the carrier allows them - the window never opened and the callback was rejected. So **an emergency call the launcher places** (`CallSystem.emergencyPlaced`, from `placeCall` once Telecom took it: the lock's, the glance's and the time-rule screen's Emergency call, the emergency menu, an emergency contact; also the emergency-dialer fallback when Telecom refused, which can only place emergency calls) is recorded at once with a **provisional** window (`PROVISIONAL_WINDOW_MS`, 10 min: a tap and a hang-up while dialling or without signal gives no hour), and promoted to the hour only on evidence that it connected: our in-call service seeing it active, the call log's duration, or - the system-dialer case - a one-shot call-state listener (`PlacedCallWatch`, `TelephonyCallback.CallStateListener`, READ_PHONE_STATE self-granted while managed; pure `PlacedCallEvidence`) seeing off-hook for 20 s, counted from the first off-hook and re-checked until it promotes (E7b's M-C1: a timer armed at the placing fired just short and a steady call stayed provisional until the hang-up), then idle (re-recorded, so the hour runs from the hang-up). The listener isn't per call, so it isn't started when another call was on before the placing (`callBeforePlacing`: ours, or Telecom's `isInCall`) and a ringing report before off-hook stops it; it ends at the call's end, 30 s without a start, 3 h (re-recording a call still on), the next placing, or as soon as our in-call service sees the call (`onCallAdded`: then only its own connect promotes, so a call it sees end without connecting stays provisional). A process death ends it: the placing keeps what was recorded last. **An open window is never shortened** (`keepLaterWindow`): a later placing or mark record after a call that connected keeps that call's later end (end, length and elapsed start together) - a redial in bad coverage that never connects can't close the hour - while the instant and the placed flag are the new record's. The record carries its window length and whether its instant is the launcher's placing (`mdm.calls.emergency_window_ms`, `mdm.calls.emergency_placed`; a record from an older build counts as an hour); only a placing's instant is kept for the in-call service's records of the same call (`placingOf`, created within 5 s after it), so the server announces one call. Besides the window, an incoming call that Telecom itself marks as its emergency callback (`Call.Details.PROPERTY_EMERGENCY_CALLBACK_MODE`: Telecom's own record of the last outgoing emergency call on that account, however and wherever it was placed) always passes, but only on a SIM subscription's account (`TelephonyManager.getSubscriptionId(handle)`; no other app can register one, so no third-party connection service can claim the mark) - pure `emergencyCallbackPass`. Such a pass with no emergency call known from the last hour (an emergency call placed outside the launcher: Android's keyguard, Emergency SOS) is recorded with the provisional window and synced, so the parent hears of it (`telecomMarkSeen`, `telecomMarkRecords`). **Seen on the emulator only, to verify on the Jelly Star**: that the mark is set (E7: `PROPERTY_CHANGE ... ecbm`), how long Telecom's own callback window lasts (5 min is the AOSP default, but on the E7b emulator the mark still came more than 8 min after the last emergency call), and that Telecom then skips call screening (`Skipping call filtering ... ecm=true` - `KidInCallService`'s re-check is where the call is let through); whether a Norwegian GSM/LTE SIM's callbacks carry the mark is the open device check. Our own record of such a call is checked by wall clock, elapsed realtime and boot count (`timedWindowActive`), and date/time stay locked whenever calls are managed (call-log times are wall-clock). The status report carries the last emergency call, the window and `callLogReadable` (warned on the server: without the call log the window can't open).
- **UI**: `PhoneBookActivity` is the DIAL/`VIEW tel:`/`CALL_BUTTON` handler and the kid's phone book (outbound contacts, Call immediately, a Message button only when the chosen app - SMS while SMS is on, Element X with a Matrix ID, Signal/Molly - is installed, unsuspended and allowed). No free keypad, no built-in emergency buttons - but contacts whose number is an emergency number stay in the phone book and on Home with calls off or the rules unknown (`phoneBookView`), and with no readable rules at all the phone book shows one "Emergency call" row (112). Home shows "Phone book" plus contacts flagged Home (`ContactsHomeAdapter`, before the apps in a `ConcatAdapter`). `InCallActivity` (over the lock screen, own task, proximity wake lock) and a `CallStyle` notification with a full-screen intent; the service also starts the activity directly (device owner + HOME may start activities from the background). Telecom plays the ringtone (no `IN_CALL_SERVICE_RINGING`). The system dialer is kept off Home while calls are managed, never suspended or hidden (emergency calls always use it), never pinned in kiosk while calls are managed.
- **Permissions/SMS**: while managed, third-party apps' `CALL_PHONE`/`ANSWER_PHONE_CALLS` are denied (also right after an install). The denial greys such an app's whole Phone group, so (design 57 (d), the user's option 1 of 2026-10-21) those that also request `READ_PHONE_STATE` and are in the effective allowlist (every one while apps are unmanaged) get it GRANTED by policy - WhatsApp asks for it to know a carrier call is on (pure `phoneStateGrants`, the wanted state per requester, written only when it differs - `grantWrite`; `EnforcementPlanTest`; an install already allowlisted gets it at once in `enforceOnNewPackage`); every other third-party requester, and all of them once calls are unmanaged, goes back to DEFAULT (the fix cleared, no revoke: a revoke kills the app, a WhatsApp call with it). The override and the pause change none of it. And the launcher self-grants `READ_CONTACTS` (screening only sees non-contacts without it), `CALL_PHONE`, `READ_PHONE_STATE`, `READ_CALL_LOG`. SMS off sets `DISALLOW_SMS` and suspends the default SMS app, Google Messages, AOSP Messaging and STK whatever the allowlist says (RCS bypasses `DISALLOW_SMS`).
- **Status report**: `capabilities = ["call_policy_v1"]` and `callState` (state, both roles, default/system dialer, restrictions, default SMS app, last error, last emergency call, callback window).
- **Direct boot (task 15)**: only the call path is `directBootAware` - `KidCallScreeningService`, `KidCallRedirectionService`, `KidInCallService`, `InCallActivity`, `CallActionReceiver` and `BootCallReceiver` (`LOCKED_BOOT_COMPLETED`, loads the policy; in design 59's android mode it also starts the direct-boot-aware boot cover, `BootCover.atLockedBoot` - the 16b bullet). **Never mark `HomeActivity`, `LockActivity`, `PinLockActivity` or anything that starts lock task direct-boot-aware, and never start kiosk/enforcement/services/tsnet before unlock** (the BFU lockout incidents below). Before the first unlock CE storage can't be read, so `CallPolicyStore` reads `boot_call_policy` from device-protected storage: pure `BootCallPolicy.kt` (`BootCallPolicyTest`), strict versioned JSON with only mode, calls on/off, country code and the allowed numbers (no names, tokens, PIN hash - DE is readable before authentication); missing/corrupt/other version → `UnknownFailClosed`. Every unlocked `refresh` rewrites it (`commit()`) when it differs from the last string *really* committed (a failed commit is retried; the prefs' in-memory map can't be trusted after one), checks the copy decides like CE (`bootCopyFaithful`) and reports `callState.bootPolicy` (`ok`/`write_failed`/`unreadable`); numbers no caller could match are left out rather than making the copy unreadable. The decisions are the pure `refreshPlan` (`CallPolicyRefreshTest`). A DE copy that saw managed (or unknown) calls is a second witness: a CE that lost `calls_managed_last` (reset/wiped preferences) fails closed and gets the flag restored - only an explicit `managed: false` unmanages; `resetPreferences` also keeps `calls_managed_last`/`last_call_rules`. The emergency-call record (callback window) lives in DE prefs `call_boot_state`. `Application.onCreate` while locked does only the call policy + `BootClock` and defers `LauncherPreferences`/`initRest` (`ensureUnlockedInit`) to `ACTION_USER_UNLOCKED` (registered receiver) or - first, usually - `KidAppComponentFactory` (manifest `appComponentFactory`, fully qualified because of the `.debug` suffix, extends androidx's `CoreComponentFactory`), which runs it before creating any component not in `DirectBootComponents`: such a component existing proves CE is unlocked even while `isUserUnlocked()` is still false. **A new direct-boot-aware component must be added to `DirectBootComponents`** (`DirectBootComponentsTest` compares it with the manifest). Whether Telecom binds our services before unlock, and whether the call log is readable then, are device checks in `02-calls.md` ("Direct boot").
- **Known gaps (device checks / follow-ups)**: direct boot is untested on a device (see above); a PSAP callback before the first unlock is rejected unless the launcher or our in-call service recorded the emergency call or Telecom marks it as its emergency callback (the call log is not read while locked); an emergency call placed outside the launcher (Android's keyguard, Emergency SOS) that only the system dialer showed and the call log leaves out opens only Telecom's own short callback window, plus our provisional one from its first marked callback, never our hour; the emergency-dialer fallback opens the provisional window when the dialer opens, whether or not the kid then calls (reachable only when Telecom refused our own placing); whether VIEW `tel:` reaches our activity or the system keypad, whether the system keypad's MMI codes are stopped, and whether the role APIs work on the Jelly Star are all on the device checklist in `02-calls.md`. Element X / Element Call VoIP and other self-managed calling apps bypass Telecom screening entirely. Since design 17 an allowed messaging app's calls ring over the PIN lock (unscreened, see below), and a self-managed call no longer holds the lock open.

## Call screens, ongoing-call card, camera while locked (user requests after the emulator run, 2026-10-06)

- **Call screen avatar** (`InCallActivity`, pure `calls/CallCard.kt` `callAvatar`, `CallCardTest`): a known contact gets exactly the Home/phone-book avatar (`KidAvatars.bindContact`: the cached photo, else the initial on the contact's colour); the peach silhouette only for unknown/withheld numbers, emergency numbers and before the first unlock (CE storage).
- **Ongoing-call card on Home** (`HomeActivity.renderCallCard`, pure `ongoingCallCard`): while any call of ours is live (`OngoingCalls.hasLiveCall`: ringing, dialing, connecting, active, held) a full-width green card replaces the contacts row - avatar, "Samtale med <navn> · mm:ss" (live from `connectTimeMillis`; no time before it connects; "Samtale pågår" for unknown and emergency numbers), "Trykk for å gå tilbake" (`home_call_with`/`home_call_ongoing`/`home_call_back`, nb + en); a tap brings `InCallActivity` back; the row returns when the call ends. Ticks once a second only while shown and Home is started.
- **Camera while LOCKED** (`lock/CameraLock.kt`, pure `lock/CameraLockPlan.kt`, `CameraLockTest`): `KEYGUARD_DISABLE_SECURE_CAMERA` does nothing without an Android keyguard (step 10: screen lock "None") and the double-press gesture's secure setting isn't writable by a device owner, so the power-button gesture opened `com.android.camera2` for ~1 s over the lock. Now, while LOCKED, the controllable handlers of `STILL_IMAGE_CAMERA`(`_SECURE`)/`VIDEO_CAMERA` plus **system** `ACTION_IMAGE_CAPTURE` handlers (a chat app answering it must not go dark) are suspended - never ours, dialers, Telecom, emergency dialer, keyboards, lock/kiosk helpers or Play - and unsuspended at the unlock. Record before act (CE prefs `camera_lock`, keys pinned; in-memory `CameraLock.held` set before suspending; `apply()` reads it after each package's current state, so it never undoes the lock), a package enforcement already suspends is never claimed and never released (`AppEnforcer.lastPlanSuspend`), process start releases an old record unless LOCKED (then re-engages). One serial background thread, targets prefetched by `apply()`. [needs device test: the gesture now ends on the lock without a camera frame; a suspended camera's "app paused" dialog under lock task]
- **Fix round (qa-11b-code.md)**: (#1) a camera the lock holds counts as not suspended for the app lists (`AppFilter`, `LockActivity`; pure `suspendedForLists` - unless enforcement's last plan suspends it too) and the LauncherApps suspend/unsuspend callbacks skip `loadApps()` when only the lock's packages changed (`CameraLock.ownChange`, held or released < 10 s ago), so Home keeps the tile and doesn't reload at every screen-off/unlock. (#2) a release empties `held` before unsuspending and clears the record after; each pass reads `PinLockRuntime.mode` when it runs (not when queued); no suspension when the record commit fails; after a release a camera still suspended but not in `lastPlanSuspend` (or no plan yet) requests an `apply()` on its own coroutine, never on the camera thread (pure `applyAfterRelease`). (#3) of third-party apps only the gesture's default (`STILL_IMAGE_CAMERA_SECURE`/`STILL_IMAGE_CAMERA`, never the chooser `android`) is a target, every system camera handler still is (pure `cameraHandlers`; logged). (#4) Home's call card renders/ticks only between onStart and onStop (`started`), and Home and the call screen check `isEmergencyNumber` once per number (`EmergencyVerdictCache`). (#5) the emulator checks are in `docs/testing/emulator.md` §6d.

## Missed-call notification (handy step 12, design `docs/design/12-missed-calls.md`, at the monorepo root)

- **Ours, not Telecom's**: `calls/MissedCallReceiver` takes `TelecomManager.ACTION_SHOW_MISSED_CALLS_NOTIFICATION` (exported, no `android:permission` - protected broadcast with READ_PHONE_STATE; not direct-boot-aware), so Telecom sends us the broadcast instead of posting its own notification (its tap opened nothing, or the system dialer's call log with its keypad). The count is only a trigger; `calls/MissedCallNotifier` (one background thread, `goAsync`, queued requests run once) posts `launcher:missed_calls` (DEFAULT, no FSI) id 1006.
- **Content** (pure `calls/MissedCallNotice.kt`, `MissedCallNoticeTest`): missed calls of `phoneBookView(effectiveState())` contacts that still count for the badges **and** are unread in the call log (`new = 1`) up to the newest unread missed `_id` read before the log ("Tapt anrop fra Pappa" / "2 tapte anrop fra Pappa" / "3 tapte anrop" + "Pappa, Mamma"). Alerts only for an `_id` newer than any alerted (CE prefs `missed_call_notice`); boot re-sends and updates are silent. The tap opens the phone book (a call-log `VIEW` sent explicitly to it), or with one contact its sheet (`PhoneBookActivity.EXTRA_MISSED_CONTACT`, read once, only for a number in the view). Without managed rules (fail-closed, or unmanaged while a dialer role the parent granted by hand is still ours) a plain "N tapte anrop" from the log's unread missed rows (`plainMissedCallNotice`, ids and dates only - `canKeepMissedCalls`: managed **or** our dialer role); unmanaged, its tap is passed on to the system dialer.
- **Clearing**: the phone book resumed, any contact sheet, the tap or a swipe (delete intent -> `MissedCallDismissReceiver`, not exported; the post's own range) cancel ours, mark the covered rows read (`new = 0, is_read = 1` - Telecom skips that for a dialer with the receiver, else they come back at every boot) and call `cancelMissedCallsNotification()`; Telecom's count 0 only cancels ours, and not when ours shows calls newer than our last reset (`zeroCountCancels`, ids). Recomputed per broadcast and 3 s + 10 s after our call ends (update/clear only). Nothing is marked read automatically unless the rules are managed, readable and calls are on now (a no-calls time rule leaves only emergency contacts in the view). Cancelled - range forgotten - on the dialer-role hand-back. The phone book's pass-on reads `CallPrefs.systemDialer`, cached by `apply()` (no Telecom call on the main thread).
- **Call log -> phone book**: `PhoneBookActivity` has its own `VIEW` `vnd.android.cursor.dir/calls` filter and `AppEnforcer.enforceCallLogPin` makes it the persistent preferred activity in every `apply()` next to the Play link blocker - cleared only by `HomePin`'s re-seat, which adds every PPA of ours back in the same pass (design 59d step M, `PlayInvariantsTest`); while calls are unmanaged it passes the intent on to the system dialer. A privileged dialer whose call-log filter has priority > 0 would still win (device check).
- **Not device-tested**: the checklist in the 12 doc ("Implementation status"); the smoke test's "Missed call" step covers ours vs `TelecomMissedCalls` on the emulator.

## Element X: Message opens the DM (design 15, `docs/design/15-element-room-link.md`, at the monorepo root)

- **Learned, launcher-only** (no server/policy/PWA part): `matrix:u/…?action=chat` opens the profile in Element X, so the room is learned from Element X's own DM notification. Pure rule `calls/ElementRooms.kt` (`learnElementRoom`, `isElementRoomId`, `pruneElementRooms`, `elementRoomUri`, codec; `ElementRoomsTest`): package `io.element.android.x`, managed rules, `isGroupConversation == false`, tag = a room ID (no `|` thread tag), session = `EXTRA_MESSAGING_PERSON`'s key, every `sender_person` key = one phone-book (outbound) contact's Element MXID. Never the shortcut id.
- **Privacy**: `badges/ElementDmReader` is the listener's only reader past package/flags, only for Element X: `ElementDmFacts(tag, selfKey, senderKeys, group)` (exact-field test), each message bundle's `sender_person` only (never `MessagingStyle`'s parser), never text/titles/names (allowlist source scan: identifiers, Bundle reads on the named keys, `Person.key` only), never logged. `ElementRoomStore` keeps only contact MXID -> (session, room) in CE prefs `element_rooms` (`learn` re-checks the contact against the rules read under its lock); pruned on every CE `CallPolicyStore.refresh` (left the phone book, not on Element, changed MXID; unmanaged = all; unknown rules = kept); nothing goes to the server or DE storage.
- **The lock's glance** (2026-10-25): `elementGlanceContact` (pure, `ElementRoomsTest`) through `ElementDmReader.glanceContact` and the read-only `ElementRoomStore.rooms` - a DM row's photo by its learned room, a group row's by its newest sender's MXID; only the contact's id reaches the glance's memory.
- **Button**: `MessageIntent.uris` - with a learned room `elementx://open/<session>/<room>` (each segment fully `uriEncode`d) first, then the profile links; `openMessage` moves on only on a throw. An unknown session fails silently in Element (room list), so `smoke-test.sh` has an optional `ELEMENT_SESSION`/`ELEMENT_ROOM` step. Not device-tested: the checks in the 15 doc.

## Missed Element X calls on the contact card and the badges (round L5, 2026-11-02, branch `missed-voip-calls`)

- **What Element X posts** (its Android source, develop bb79064b, read 2026-11-02; since v0.4.15 - no device capture): an unanswered call cancels the ring (a fixed id, `CATEGORY_CALL`, CallStyle, no room on it) and posts or updates the room's MessagingStyle notification with `category = "missed_call"` - tag = the room ID (`<room>|<thread>` in a thread), each message bundle's `sender_person` key = the sender's MXID, `EXTRA_MESSAGING_PERSON`'s key = the kid's MXID, `isGroupConversation` false for a DM, shortcut `<session>-<room>`. The text still says "Incoming call" - never read. The category stays `missed_call` while the call's event is among the room's pending events and goes back to `message` when it drops out; opening the room in Element X cancels the notification. **With an app PIN in the kid's Element X every notification is one generic fallback (no tag, no category): nothing to read, no mark.**
- **Who** (pure `calls/ElementMissedCalls.kt`, `ElementMissedCallsTest`): `elementMissedCallContact(category, ElementDmFacts, state, rooms)` - `missed_call` (`glanceMissedCall`), managed rules, `group == false`; the contact whose learned DM room (design 15, `ElementRoomStore.rooms`) is the tag's room (a thread tag's room part; the same session when the facts name one), else the newest `sender_person` key that isn't the kid's own; either way a phone-book contact whose Message button is Element under that MXID (`elementContactIds`). Group rooms, a missing group flag, strangers and the PIN fallback mark nobody - the notification still counts in Element X's own app badge on Home, as before. `ElementDmReader.missedCallContact` reads it (the design 15 facts plus `notification.category`; `ElementRoomsTest`'s allowlist grew by those names), right after `learn`, so a room this very post teaches is known.
- **Memory only**: `ElementMissedCallStore` keeps notification key -> `ElementMissedCall(mxid, sinceMs)` (`afterElementPost`: a post without a contact - another app, a group, `message` again - drops the key; the same contact again keeps the time it turned into a missed call; `afterElementRemoval`; `afterElementSweep` at the listener's connect keeps what is still active; cleared at disconnect; judged again - the listener's sweep through `ElementMissedCallStore.rejudge` - when a `CallPolicyStore.refresh` changes the phone-book Element contacts, pure `elementMissedRejudgeDue`, so a contact added or moved to Element X after the call still gets the mark, from the notification's last post). `toString()` of `ElementMissedCall`/`ElementPost` names no MXID or key; ElementDmReader returns at once for a category other than `missed_call`. Fed by `BadgeListenerService` (`ElementPost(sbn.key, ElementDmReader.missedCallContact(this, sbn), sbn.postTime)`); nothing stored, logged or sent (scan test).
- **Shown** (`MissedCallsRepo.contactMissed`: `contactMissed(summaries(..), elementMissedSummaries(ElementMissedCallStore.calls, rules, seenMarks))` -> `ContactMissed(phone, element)`, `count` = both): the missed-call badge on Home's contact buttons and the phone book's avatars (and TalkBack's "Pappa, 1 missed call") count both; the contact sheet's missed line has the phone's own line, then "1 missed call in Element X today 13:05" / "1 tapt anrop i Element X i dag 13:05" (`missed_calls_in_app_at`, the app's shown label - design 14's name, the same the Message button's "Message opens in" names). Each room's notification is one call (several calls in one room can't be told apart without the text). Home and the phone book listen to the store and reload.
- **Cleared**: the notification goes away (read or swiped in Element X) or flips back to `message`, or the kid opens the contact's sheet (the phone book's own seen mark, `MissedCallsRepo.markSeen`: a room's call counts only when it turned into a missed call after the mark). Limit: a second call missed into a room whose notification still holds the first (after the kid opened the contact) isn't marked again - its time is the first's; Element X's own badge still shows it. After a process restart (or the listener's rebind) a call's time is its notification's last post, so a contact opened before it can show the mark again if the room had a newer message. Our missed-call notification (design 12) stays the phone's own calls only.
- **Not device-tested**: an unanswered 1:1 Element Call on the Jelly Star (PLAN Round L5).

## VoIP calls over the PIN lock (design 17, `docs/design/17-voip-calls-over-lock.md`, at the monorepo root)

- **What rings** (since launcher round 7 §1, `docs/design/r7-launcher-small-items.md`): an allowed app's ringing CALL notification with a full-screen intent (or an answerable incoming CallStyle) - **any allowed app**, while calls are managed and on or unmanaged, none while calls are off or unknown; unsuspended. Pure `voipCandidates(effective, voipAllowed, policyKnown)` on `CallPolicyStore.effectiveState()`: the gates as before, then `NotificationRuleRuntime.voipAllowed` - computed once per `apply()` in `refresh` by the pure `voipAllowedApps`: the **effective** allowlist (`WindowRelock.blockedPackages` out) + the contacts' messaging apps + a time lock's usable apps, minus `voipExcluded` (the SMS apps and the default SMS app, `ESSENTIAL_PACKAGES` + the resolved essentials - dialers, Telecom, emergency dialer, clock, keyboards -, our own family); `null` while apps are unmanaged. `VoipCalls.eligible` only reads it (no binder call, no raw allowlist). Fallbacks (never wider than before): no `apply()` yet in this process or apps unmanaged -> the contacts' messaging apps (never the SMS app) while managed, the known messengers (Element X, Signal/Molly) while unmanaged. **No ring budget** (the user: the lock is the kid's own PIN, so a faked ring gains the kid nothing; `VoipCallPlanTest` checks the file holds no per-hour constant). Any caller of such an app rings (no caller identity in the notification): strangers can ring through apps whose contacts Vibb doesn't manage - the accepted residual (design r7 §1.1.2). **`lock_state.voip_rang`**: `[{package, last_at_ms}]`, a ring recorded once (`voipRangDue`) when it is seen while LOCKED - one that began locked, or one during which the phone locked (`PinLockRuntime.syncVoipRinger` -> `VoipCalls.noteRingWhileLocked`, every VoIP pass and mode change); CE prefs `voip_call` `rang_v1` ("package=ms" strings), 7 days, 8 entries, newest first, pruned at each write and report (`voipRangEntries`); package names and times only, always sent (`PolicyResponseCompatTest`). The server shows "Calls from <app> · Rang over the lock · 14:02" on Lock Screen & PINs.
- **Reading** (`badges/VoipCallReader`, in `BadgeListenerService`): category, channel, flags and whether an FSI exists; it keeps the ring's FSI, CallStyle decline and answer actions (17b: `pickAnswerIntent`) and the call service notification's content intent - **never the ring's content intent as such, never a title or text** (`VoipCallGuardTest`).
- **Lifetime** (pure `voipExemption`, runtime `lock/VoipCalls`, CE prefs `voip_call`): RINGING while the ring exists; 15 s grace after it (answer gap); then the call's foreground-service notification AND audio `IN_COMMUNICATION`; 3 h cap re-fronts the lock but never unpins a live call; a reboot ends it; a new process keeps the stored package pinned for 15 s until the listener reports (ring end stored, no audio-only hold); an incoming CallStyle is never "the call". Polled every 2 s while active. Counts as a live call for the self-update gate and the update/boot Home.
- **The lock**: `step` takes `voip` like `systemCall` (never started over the call; `VoipRinging` shows it and starts `lock/VoipWakeActivity` - manifest `turnScreenOn`, the lock never sets the bit; `VoipEnded` brings it back; `LockResumed` during the call re-sends the call notification's content intent). **Another call (emergency included), the emergency flow and a ringing alarm always win**: `VoipRinging`/`VoipEnded` then only `recheck` (the re-front loop decides), `RefrontPolicy` checks `"voip"` last. Ring screen (`view_voip_ring.xml`, "Ringer deg i <app>", Avvis/Svar, Nødsamtale, Foreldrekode): Svar sends the answer action (17b), Avvis the decline action (always hides the card and silences this ring), both from the resumed lock with `ALLOW_IF_VISIBLE` (36) / `ALLOWED` (34/35); at most 2 min per ring. `VoipRinger` (default ringtone + vibration by ringer mode/consolidated DND - the lock's status-bar flags mute every notification sound) follows the pure `voipRingWanted` on every VoIP pass and mode change: RINGING, LOCKED, not silenced (screen-off on a ringing lock, Avvis), no other call, emergency flow or alarm.
- **17b, the app's own ring screen first**: the resumed lock (300 ms settled, screen on) sends the ring's FSI once per ring (`voipFsiDue`); the card only as the fallback (`voipRingUi`: no FSI - an FSI-denied ring with an answer intent now rings -, the send threw, the lock came back, or it never left the front within 1.5 s); Svar = the CallStyle answer action (`EXTRA_ANSWER_INTENT`, else - incoming CallStyle only - the one `key_action_priority` action that isn't the decline), silences the ring only after the answer action, same 1.5 s check; no send over the lock's own dialogs, the calls gate re-checked at the send (`sendRing`), a pause that isn't our wake activity's counts as the app's screen having come up (qa-17b-code); every VoIP send goes through `VoipCalls.send` behind `PinLockRuntime.voipCallOrEmergency` (our/Telecom call, emergency flow; the card and FSI also yield to an alarm, `voipOtherScreen`). Device checks: the 17 doc's "17b implementation status".
- **Kiosk off**: the package + permission controller are pinned with the app-block bit while pinned (`lockTaskWhileLocked(voipPackages)` - LockTaskChrome stays the only setter), and the lock starts no lock task while the call rings or lives (PiP hang-up; `lockTaskEntry(voipCall)`, re-entered when the phase ends). Kiosk on: the list is untouched.
- **Also**: the lock's system-call yield uses `isInManagedCall` (self-managed apps held it open with no cap); screen time counts while exempt (`screenTimeCounts(voipExempt)`); apps whose FSI Android dropped are reported (`lock_state.voip_fsi_denied`, device-page warning). Not device-tested: the 17 doc's "Implementation status".

## The emergency menu (design 73, `docs/design/73-emergency-menu.md`)

- **Every Nødsamtale** (the PIN lock's keypad and glance, its VoIP ring card, the time-rule screen, the phone book's
  fail-closed 112 tile) calls `EmergencyCall.confirm`: one number (the default, 112) is the old "Call 112?" dialog, more
  are `EmergencySheet` ("Call an emergency number", `sheet_emergency.xml` / `item_emergency_row.xml`, `KidSheetDialog`):
  the first real emergency number large and red on top (`kid_emergency_big`), the others in the parent's order, Cancel.
  One tap calls - the sheet is the confirm.
- **Pure** `calls/EmergencyMenu.kt` (`EmergencyMenuTest`): `emergencyMenu` cleans the server's list (normalised,
  no duplicates, at most 6, names clipped to 40; `null`/`[]` or a list without an emergency number gets 112 first),
  `emergencyRows` decides each row - `EMERGENCY` (`Emergency.isEmergencyOutgoing`: the platform ORed with the fixed
  list, shared as `emergency_numbers.json` with the server), else `decideOutgoing(.., isEmergency = false)` over
  `CallPolicyStore.effectiveState()` (`ALLOWED`/`BLOCKED`; `BLOCKED` too while another call is live) -, `emergencyTap`
  (emergency path: CALL_PHONE self-granted, Telecom, else `EmergencyDialer`; rules' path: `CallSystem.placeCall` only,
  as a phone-book call; a blocked row is dimmed and does nothing). While the phone is locked (`confirm(.., locked = true)` from the PIN lock,
  or `phoneLocked`: our lock LOCKED or Android's keyguard up) only real emergency numbers call: every other row is
  `LOCKED`, dimmed, "Lås opp for å ringe" (the user, 2026-11-01; design 73 §2). The rows
  scroll in `EmergencyRowsScroll`, capped to what the screen leaves after the title and Cancel.
- **Store:** `CallPolicyStore.emergencyMenuRaw`, read with the CE cache in `refresh` (`storedEmergencyMenu`: the cached
  policy's list, else the one in `last_call_rules` - `CallRules.emergencyMenu`); the DE boot copy is unchanged (no
  entry point runs before the first unlock).


## The message engine, step 1 (design 77, `docs/design/77-sms-mms-client.md`; branch `sms-engine`)

- **Per channel (D2):** `call_policy.calls_mode`/`sms_mode` (`off`/`managed`/`unmanaged`, `ChannelMode`) are read by
  `channelModes` - an older server's `managed`/`calls_enabled`/`sms_enabled` by D2's migration table (the contract's
  `migration` rows). `CallRules` carries both modes (rules stored by an older build read them from the old switches).
  `CallPolicyStore.state` is the whole policy (managed while either channel is: hardening, the update fence, the clock
  lock read it); **`CallPolicyStore.callsState`** (`forCalls()`: calls "Ikke styrt" reads as `Unmanaged`) is what every
  call-path reader asks - the services (`effectiveState()`), the dialer role, Home's contacts and phone book, the lock's
  phone-book row, the call log, the missed-call notifier, `call_state.state`. Once a phone has accepted a policy with
  the modes (`channel_modes_seen`, committed with the cache, kept across a preferences reset), a `call_policy` without
  them - or with a value that isn't one of the three - is rejected whole (`judgeFresh`). The DE boot copy is version 3
  (`calls_mode` and `sms_mode`, QA L2; a v1 or v2 copy reads as unknown, fail closed until the first unlock rewrites it).
- **The engine (`sms/engine/`, pure, JVM-tested):** `Model.kt` is §6.2's seam (ids, `Message`, `EngineEvent`,
  `SendCommand`, `MessageEngine`); `SmsSenderType` reads the TP-OA's type of number from every raw PDU (TON 101 =
  a sender name, H2); `SmsRules` is D4/D5 (no sender is let in for being an emergency number; the callback window lets
  anyone in, marked `unknownSender`; unknown rules with the role ours hold, D15); `SmsSenders` is D17's names (a letter,
  1-11 code points, NFC + `lowercase(Locale.ROOT)`) and the sender cleaned by its class before it leaves; `HoldPolicy`
  is D16's caps (30 numbers / 20 names, 10 per number / 3 per name, 1,600 characters, 24 h; 200 for the rules, 500
  blocked rows); `SmsEngine` runs it over `MessageStore`: receive, hold, release (drops first, then expiry, then the
  exact sender only), the outbox (gate again, `SENDING` committed before the platform has it, `UNCERTAIN` after 10
  minutes, never re-sent; a late result still applies), the idempotent send (`clientId`, the store's `insertOutgoing`),
  the event log with one `seq` counter, `ConversationChanged` on a rule change.
- **The store (`sms/store/SqliteMessageStore`):** our own SQLite (`messages.db`, CE, `secure_delete`, WAL checkpointed
  after every expiry, drop or delete pass); nothing is written to the Telephony provider. §6.6's tables (the `part`
  table, `pending_push` and the `mms` state machine are step 3b's - version 2 of the database, below).
- **The glue (`sms/`):** `SmsRuntime` - the engine opened at its first use after the first unlock, the receive executor
  (receivers only, D7) and the engine thread (everything else), the rules from memory, `sms_state`, the `sms-held` post
  (50 rows a post; a 2xx marks them, `capped` too; a 400 gives up the named row); `SmsDeliverReceiver` (classify, judge,
  commit, `finish()`, then the notification), `MmsPushReceiver` (step 1 only commits the raw push to `pending_push`),
  `SmsSentReceiver` (sent and delivery results, manifest-registered), `SmsReplyReceiver` (the notification's reply,
  `setAuthenticationRequired`, the notification's own `clientId`), `RespondViaMessageService`, `ComposeActivity` (a
  step 2: an allowed number opens its thread with any `sms_body` as a draft, else it says why), `SmsNotifier`
  (`MessagingStyle`, public version "Ny melding", silent under a time rule without calls; since step 2 the tap opens
  the thread).
- **The role and the permissions (`SmsRole.kt`, `AppEnforcer.applySmsRole`/`applySmsPermissions`):** taken in
  "Styrt av Vibb" before the plan (so `DISALLOW_SMS` stays off), handed back in "Ikke styrt" after the package loop
  to the holder before us; kept in "Av" and with unknown rules. `DISALLOW_SMS` is `smsRestricted`: "Av", or no role of
  ours in "Styrt av Vibb" or with unknown rules. The other SMS apps are suspended whenever SMS isn't "Ikke styrt".
  `DISALLOW_CONFIG_DEFAULT_APPS` covers either role (`lockDefaultAppsFor`). In "Styrt av Vibb" every other app's SMS
  permissions are denied, Play services' by the switch `DENY_PLAY_SERVICES_SMS` (on; step 0 checks what breaks).
  Our own SMS permissions are fixed by policy around every role change, and not released while SMS is managed.
- **Step 0 marks in the code:** the delivery reports (`REQUEST_DELIVERY_REPORTS`), `3gpp2` read as malformed,
  `DENY_PLAY_SERVICES_SMS`; whether a device owner may set the hard-restricted SMS permissions is logged when refused.
- **Fix round after the code QA (`docs/review/qa-sms-engine.md`):**
  - the other apps' SMS permissions are denied in "Styrt av Vibb" and with the rules unknown (`smsPermissionsDenied`,
    M1), and leaving the denial gives back a grant it took away: `GRANTED` then `DEFAULT`, from a record of what was
    granted before (`smsPermissionPass`, `mdm.sms.denied_permissions`, L8);
  - SMS "Ikke styrt" while the role is still ours (a hand-back pending or failed) keeps and shows every message,
    unfiltered (L3); the hand-back is retried at every apply, and `role_taken_by_us`, the previous holder and the
    denial record survive a preferences reset (`SmsPrefs.KEPT_ON_RESET`);
  - the notification's Reply carries both ids in its data URI (`replyData`/`replyIds`), mutable only for RemoteInput,
    and the open intent is told apart by its identifier - never a `hashCode` (L1);
  - a re-delivered SMS is stored once (`received_key`: a hash of sender, SMSC time and body, kept a day, L6);
  - every receive is timed from the broadcast's arrival; one over D7's 3 s is recorded (its time only) and counted
    (`slow_receives`), and `markRead` is one statement (L7);
  - MMS pushes: at most 100, 2 KB each, 7 days; more is counted as blocked `malformed` (L4);
  - `secure_delete` is read back after it is set, and `last_error` says so when it isn't (N4).

## The kid's message screens, step 2 (design 77 §6.7; branch `sms-screens`)

- **Pure model (`ui/messages/MessagesModel.kt`, `MessagesModelTest`):** `messagesTileShown` (Home's tile: SMS "Styrt av
  Vibb" with the role ours, or any history); `messagesUsableUnder(lock)` (a time rule: reachable where the rule allows
  calls, as the phone book; the used-up budget keeps calls); `composeState(judge, target)` - `OPEN`, `READ_ONLY` (SMS
  "Av"/"Ikke styrt": the history stays readable in Vibb One, no compose bar, also for an emergency number), `NOT_NOW` (a
  time rule without calls: "Du kan ikke sende meldinger nå"), `NOT_ALLOWED` (a sender name, a stranger, a contact
  without `sms_out`, a group), `UNAVAILABLE` (rules unknown, role not ours) - the gate's own answer
  (`SmsRules.decideSend`) shown before the kid types; the engine still decides every send. `newMessageContacts` ("Ny
  melding": only `sms_out` contacts, the parent's order, no free number), `listRows` (newest first by `seq`; the
  contact's name now, else the name the conversation last had, D18), `listTimeLabel` (the clock today, "I går", the
  weekday this week, else the date), `threadItems` (by `seq`, a day separator per day, the time under each run's last bubble - below), `deliveryLine` ("Sendt"/"Levert"/
  "Sender …" under the kid's last message; "Ikke sendt"/"Kanskje ikke sendt - trykk for å prøve igjen" wherever a
  failed or `UNCERTAIN` one is), `partViews` (step 3b's parts), `MessageActions` (D18's deletes; "Prøv igjen" sends the
  same message again - its text and its pictures - under `retry:<failed id>` and deletes the failed one only when the
  engine accepted a message with all of it; refused, it stays - "A failed picture's retry" below).
- **Screens (`ui/messages/`, XML views, Dagen's ground and header):** `MessagesActivity` (the list; "Ny melding" as the
  header's action while `composeState` would send; long-press or TalkBack's "Slett samtale" asks in the screen),
  `ThreadActivity` (bubbles - received in `kid_message_in` (#34324A, the user's D2), the kid's in `kid_message_out`
  (the accent peach, dark ink) - with day separators, "Ukjent avsender" over a callback-window message and, under a
  bubble, the one small line of its time and its delivery state (below; the one clock format);
  long-press or TalkBack's "Slett" asks "Slette meldingen?"; the compose bar or its line), `PictureActivity` (fit to
  screen, "Lagre" into `Pictures/Vibb One/` through MediaStore with a name that says nothing, "Del" through
  `PictureShare`: a copy in `cacheDir/share/` behind the not-exported `FileProvider` `${applicationId}.share`, deleted
  after an hour at every share, picture and sync). The delete confirm (`view_messages_confirm.xml`) is part of the
  screen, no system dialog; no trash, no undo. One task of their own (`.messages` affinity, as Dagen's), in Recents as
  "Meldinger" (the parent's name for it where there is one, set again at every render - `labelTask`); `MessagesScreens.startThread` puts the list under a thread opened from outside.
- **A thread follows its newest message (2026-11-10, the user; branch `sms-follow-book-ground`):** pure `threadFollows`,
  `threadAtEnd`, `threadEndGap`, `threadReads` (`MessagesModelTest`, which also pins the screen's wiring by its
  source); the activity only measures. The thread opens following; a scroll sets it to "the last bubble's bottom is
  within 8 dp of the list's end" (a tall picture counts only by its bottom), a layout can only turn it on (the end came
  into view: a delete left less than a screen); the kid's own send (an OUT message with a newer `seq`) turns it on and
  stops a list that still coasts. While it follows, every shown list goes to the last position and every layout of the
  list - a new or grown bubble, the keyboard, the compose bar's height - scrolls on by the exact gap left; scrolled up,
  nothing moves (a message in, a delivery line, an older page). **Read only when shown (code QA, 2026-11-10):** a read
  marks the thread read only while it is in front and follows (`threadReads`), so a message that comes in while the kid
  reads older ones stays unread - Home's badge and the list's dot show it if the kid leaves without going down - until
  the end is reached: turning following on (`follow`) reads once more, which marks it read and cancels its
  notification. Meanwhile the thread's "Ny melding" mark tells the kid (below); still no notification while the thread
  is in front (`SmsRuntime`) and no sound. The cause of the missed follows: the scroll hung on one
  `submitList`'s commit callback, which `AsyncListDiffer` never runs for a list overtaken by the next one - and a new
  message's read is followed at once by another (its own `markRead`, a delivery update), which didn't ask to scroll.
- **The "Ny melding" mark (2026-11-10, the user; branch `thread-new-mark`):** pure `threadNewMark(following, seenSeq,
  messages)` -> `NewMark` (`MessagesModelTest`, with the wiring's source pin). While the thread doesn't follow and a
  message has come in that the list's end hasn't been shown for, a small pill says "Ny melding" - "Nye meldinger" for
  more than one, no count - over the list's lower edge, centred, above the compose bar and so above the keyboard
  (`thread_new` in `thread_list_frame`, which now wraps the list and is what the emoji picker hides). Never for the
  kid's own message (a send goes to the end) and never for a reaction alone (no bubble, no `seq` among the messages).
  **What counts as shown:** `ThreadActivity.seenSeq`, the newest `seq` the end was shown with - set in one place, the
  latest list's commit callback in `show`, with the scroll to the end of a thread that follows (code QA L2: set by
  `show` itself, a message whose list the kid scrolled away from before it was in counted as seen, unshown), and reset
  by `take`; the mark counts the IN messages after it. Turning following on (`follow`) doesn't set it - `newestSeq` may
  tell of a list that isn't in yet - but takes the mark away at once (a thread that follows has none) and reads, and
  that read's list sets it; a kid who reaches the end by hand and leaves it before that read is in gets the mark once
  more for what was there, never a miss. In memory only: a rebuilt screen opens following, at its end, so nothing is
  owed; a screen that was only paused or stopped keeps it, and the first read after the return shows the mark for what
  came meanwhile. `take` stops a coasting list first (code QA L5): the old thread's fling would turn following off
  before the new thread's first list, and the mark would tell of its whole page. **A tap** closes "Slette meldingen?"
  and the reaction panel (their bubble leaves the screen; code QA L1), stops a coasting list, turns following on
  through `follow` (which reads the thread and cancels its notification, as reaching the end by hand does) and goes to
  the last position; `afterLayout` goes on to a tall bubble's bottom. The mark leaves whenever following turns on,
  however that came about. **The tap's focus (code QA L4; the emulator pass saw the keyboard's fall to the header's
  back button):** the mark goes, and a focus on a view that went falls to the window's first element - so `toEnd`
  takes along the focus the mark had, and only that. The keyboard's (`isFocused`: Enter on the mark) goes to the
  compose field while the bar shows, asked before the mark goes - the kid came for the newest message, and the answer
  is written there; the field takes focus without a layout to wait for -, else (the read-only line, a time rule) to
  the last bubble.
  TalkBack's (`isAccessibilityFocused`: the double-tap) goes to the last bubble, the message the tap was for
  (`ACTION_ACCESSIBILITY_FOCUS` on `bubble`, the row's one node). The last bubble gets it in `afterLayout`, at the
  first layout that has it (`endFocus`, `endSpoken`; reset by `take`). A finger's tap moves no focus (no keyboard
  opens), and neither does a mark that leaves because the kid scrolled to the end. Known: with the fade off (the
  system's animations off) and no compose bar, the keyboard's focus is on the header for the one frame until that
  layout; and a last bubble taller than the list is scrolled to its top by the keyboard's focus, as by a Tab onto it.
  **A drag that begins on the mark (the emulator pass's F1)** scrolls the list as one that begins beside it - before,
  the button over the list took the whole gesture, a dead strip of 105-158 dp where a thumb starts its way down.
  `dragsThroughMark`: the mark's touch listener hands every event of the gesture on to the list's own `onTouchEvent`,
  moved to the list's coordinates - what a list gets for a touch none of its rows takes; never its `dispatchTouchEvent`,
  which would press and long-press the bubble under the mark. Until the list drags (its own touch slop) the mark keeps
  its press and its tap; then the mark's press is cancelled (an `ACTION_CANCEL` to its own `onTouchEvent`) and the
  listener keeps the rest, so the lift taps nothing, and the list scrolls, flings and turns the mark off at the end
  through its scroll listener as for any scroll. A gesture that never dragged ends as a cancel for the list (no fling
  from a sloppy tap). A touch on the mark stops a coasting list, as one on a row. While the reaction panel is open
  the mark hands nothing on: a touch in the list is spent on closing the panel, and the mark's tap closes it too.
  `thread_list_frame` has `splitMotionEvents="false"`: a second finger is never the list's own gesture next to the one
  handed on. TalkBack's click and a keyboard's Enter don't pass the listener. Run on the emulator
  (`docs/testing/emulator.md` §6ac, the "Re-check" points); TalkBack's own click with accessibility focus alone on the
  mark, a second finger and the focus where there is no compose bar are the phone's to show.
  **`take()` keeps `submitList(emptyList())`** (code QA L5's question, decided 2026-11-10 after the emulator pass):
  `submitList(null)` would empty the list at once, where a finger still dragging the old thread's rows could turn
  following off before the new thread's first list - but no way in hands a new intent to a thread that was scrolled
  (the list, the notification and `smsto:` all build a new activity; only two row taps 30 ms apart reach `onNewIntent`,
  with a thread 0.1 s old). It becomes worth doing, with a check that the first list after `null` lands at the end,
  the day a way in starts a plain `threadIntent` over a living thread (design 82). **Look:** a 32 dp pill
  in a 48 dp touch target (`bg_message_new`: a received bubble's `kid_message_in` with the reaction chip's 1 dp
  `kid_ground` outline, 8 dp inside the target's top and bottom), Nunito bold 14 sp in `kid_core`, flat (no state
  animator); a 150 ms fade in and out, none with the system's animations off (`ValueAnimator.areAnimatorsEnabled`). No
  sound and no buzz of its own. **TalkBack:** a button, a polite live region touched only when the mark changes (it is
  heard as it comes, and once more if "Ny melding" becomes "Nye meldinger"), its action "gå til den nyeste meldingen";
  no fade under touch exploration, so the live region's event never finds the mark at alpha 0. The emulator pass, with
  what it found and what it could not show (TalkBack by touch among it), is `docs/testing/emulator.md` §6ac.
- **A message's time under its bubble, once per run (2026-11-10, the user; branch `bubble-time`):** no bubble carries its
  time inside any more. A **run** is consecutive messages of one side where each came at most 5 minutes (`RUN_GAP_MS`;
  exactly 5 minutes still goes on, counted from the message before, not the run's first) after the one before it, by the
  engine's time (`Message.at`, the instant the day separators use); the other side, a new day (its separator stands
  between) and a message timed before the one it follows (a clock set back) end it - pure `sameRun`, `bubbleTimed`,
  `ThreadItem.Bubble.timed` from `threadItems` (`MessagesModelTest`). Under the last bubble of every run, on the
  bubble's side, one small line in the delivery line's look (`bubble_line`: Nunito bold 12 sp in `kid_core_dim`, no
  colour of its own) says its clock time (`ClockFormat.formatClock`, the phone's 12/24 h). The kid's newest message
  always ends a run, so its delivery state and the time are one line, state first: "Sender … · 10:47", "Sendt · 10:47",
  "Levert · 10:47" (`messages_delivery_time`, two arguments). **A failed or uncertain send's line stays as it was,
  without a time, wherever the bubble is** - it is red, the tap to try again and already long ("Kanskje ikke sendt -
  trykk for å prøve igjen"), and nothing was sent at that time; a run that ends in one shows no time. A reaction's chip
  stays on its bubble, the line comes under it. **TalkBack as before:** every bubble's description keeps its time; the
  line is no node when it is a time alone, and with a delivery state it is the node it was and speaks the state alone
  (the time isn't heard twice). **What moves:** a bubble's line depends on the message after it alone, so an older page
  changes no row that is there; a message that goes on a run takes the line from the bubble before it, whose row gets
  one line lower (2 dp + the line's 24 dp at the standard text size). A thread that follows goes on to the last row's
  bottom, the line in it (`threadEndGap` measures the row). Scrolled up, a row that isn't laid out moves nothing; **the
  one case that moves what the kid looks at:** the last bubble is cut by the list's lower edge (the end more than 8 dp
  away, so the thread doesn't follow) and a message comes in on its run - that row is the layout's anchor by its bottom
  (`stackFromEnd`), so what stands over it comes down by the line's height. Measured on the emulator: 26 dp for each
  such message; the same kind of move as a reaction chip coming onto that row (the open Low), and no anchor was built
  for it. A long line (a large text size) wraps towards the bubble's side and never widens the row (`wrap_content` in
  the row's width, no `maxLines`). A picture with no text under it has lost its 6 dp bottom margin with the time. The
  line is clickable only as the tap to try again (the listener is set before `isClickable`: `setOnClickListener` makes
  a view clickable, with null too). The emulator pass, with its measures, the two faults it saw beside this (a failed
  picture's retry sent its text alone; the compose bar with a picture lay under the keyboard - both fixed since, "A
  failed picture's retry" and "The compose bar with a picture, over the keyboard" below) and what it could not show
  ("Sender …" on screen among it), is `docs/testing/emulator.md` §6ae.
- **The compose bar (QA N2):** the draft carries one `clientId`, kept (also across a rotation and, in memory only, while
  the kid looks elsewhere) until the engine accepted that text; two quick taps send the same id and the engine sends
  once. "2 SMS" over the field past one part (`SmsMessage.calculateLength`). No field menu (`NoTextActions`), bubbles
  never selectable or linked: nothing opens another app (`KidScreensEscapeTest`, which guards `ui/messages` and allows
  `Intent.createChooser` in `PictureShare.kt` alone).
- **The compose bar with a picture, over the keyboard (2026-11-10, found by the bubble-time emulator pass):** the bar
  with a picture is 234 px at 320 x 569 dp (the 96 dp preview over a field of one or two lines; 266, 299 and 331 px
  with three, four and five), and a `LinearLayout` offers a child measured after a weighted one (the list) the
  parent's whole height, not what is left - so a bar taller than the room between the header and the keyboard lay with
  its lower part, the field's last lines and the send button, under the keyboard. Seen on `handy36` (a 136 px status
  bar: 204 px of room, the bar's lower 36 px hidden with one line of text). **By the numbers for the Jelly Star's
  36 px status bar** (`jelly36`; the keyboard's top where `handy36` had it, 424 px from the screen's bottom - the
  phone's own keyboard may differ): 304 px of room; with one or two lines the bar fits and the list keeps 49 px, with
  three 17 px; with four the bar's bottom edge is 1 px under the keyboard; with five 33 px are - the field's last line,
  the one being typed, and near half the send button. Layout only (`activity_thread.xml`, no code, no strings):
  everything under the header is in one weighted `thread_body`, so the bar is offered exactly the room there is (less
  its margins), and the preview's row is the bar's one weighted child at 96 dp, its picture as high as the row - where
  the bar is taller than its room the row is lower by exactly what is missing and the field keeps its lines. **Measured
  on `jelly36`** (`docs/testing/emulator.md` §6ag; uiautomator bounds, Gboard's top at y = 429 of 854, 303 px of room),
  keyboard up, a picture attached: the preview is 144 x 144 px (96 x 96 dp) with one, two and three lines of text,
  144 x 127 px with four and 144 x 95 px with five (1 px less than computed - the field is 66, 67, 99, 131 and 163 px);
  the list keeps 48, 47, 15, 0 and 0 px; the field's bottom is at y = 408 and the send button's at 414 in all five,
  whole above the keyboard. Main's build in the five-line case: the field down to y = 457 and the send button to 463,
  34 px under the keyboard. With the keyboard down the preview is 144 x 144 px with one to five lines, as it was, and
  without a picture five lines leave the list 107 px with the keyboard up. The other panels under the header (the line,
  the picture choice, the react panel, the delete confirm, the emoji picker) are offered the same room now instead of
  the screen's height; their bounds, and their pixels, are main's. Still computed only, for `handy36`'s 136 px status
  bar: 96 x 62 dp with one or two lines, 96 x 40 dp with three, 96 x 18 dp with four (the 40 dp cross cut to the row),
  none with five. The Jelly Star's own keyboard height is not known from an emulator.
- **The engine's thread:** every read and write goes through `SmsRuntime.ask` (the engine thread, the answer posted to
  the main thread) and the screens reload on `SmsRuntime.events` (the engine's live flow). `SmsHome` holds what Home
  and the time-rule screen need (history, the unread total, the role), refreshed after every event, every rule change
  and at Home's start; its listeners also tell the screens of a rule change that touched no conversation. A thread in
  front (`SmsRuntime.threadInFront`) marks its messages read and gets no notification for them.
- **Ways in:** Home's tile (`GridTile.Messages`, after the phone book, the teal tone `kid_messages_tile`, the chat glyph,
  the unread total as its badge); the time-rule screen's "Meldinger" row (`LockRow.Messages`, where the rule allows
  calls and Home shows the tile; never opened by itself after an unlock); the notification's tap (the thread over the
  list, `PendingIntent.getActivities`, told apart by the thread intent's identifier); the contact sheet's Message button
  (`opensOwnThread`: an SMS contact with `sms_out` while SMS is "Styrt av Vibb", no "opens in" line - the other apps and
  the other modes keep `resolveMessageButton`); `ComposeActivity` (`smsto:` from another app). Under a time rule each
  screen checks at resume and watches the lock (`LockWatch` with its own check) - a rule without calls brings the
  time-rule screen.
- **Pictures (step 3b plugged in, below):** a bubble shows `partViews(message.parts)` - a picture's rounded place (70 % of the
  width, the picture glyph) until `engine.openPart` gives its bytes, then the thumbnail (`SmsPictures`, our own JPEG,
  decoded off the main thread); a tap opens `PictureActivity`. Since step 3b `Message.parts` comes from the `part`
  table and `openPart` gives the main process's own JPEG.
- **Fix round after the code QA (`docs/review/qa-sms-screens.md`):** "Prøv igjen" sends under `retry:<failed id>` (two
  taps send once) and only while the compose bar is open (since 2026-11-10 with the failed message's pictures -
  "A failed picture's retry" below); the contact avatars' badge is missed calls plus unread
  messages (`ContactBadge`, `SmsHome.unreadFrom`, Home, the phone book, the sheet); the notification lets the system add
  no contextual actions; the engine's live flow is a drop-oldest change signal collected off the engine thread, and the
  screens coalesce their reads (`Coalescer`, the thread reads its one conversation); the time-rule row and the tile
  follow `SmsHome` and the role; each refusal has its own words (`afterRefusal`); "Del" leaves out every target the
  parent didn't allow and the nearby share (`shareExclusions`); drafts never go into the saved state. The
  history-only tile keeps its name "Meldinger" (the user, 2026-11-09: "la være inntil videre").
- **Meldinger as an app, step 2 (design 82 D9, W1, W2):** the parent can give Meldinger a name, a glyph on a colour
  and keep it off Home - `docs/notes/launcher/home-and-photos.md`, "Meldinger's entry". The name
  (`AppDisplay.messagesName`) is spelled on Home's tile and its TalkBack text, the time-rule row, the lock keypad's
  prompt, the lock row's action (`glance_sms_action`, now `%1$s`), Meldinger's header and its task name; the
  notification channels' names and the notification's small icon stay. The launcher claims **`messages_app_v1`**
  (`MESSAGES_APP_CAPABILITY`, in `STATUS_CAPABILITIES`): it honours Off on the lock and reads the key's entry.
  **`sms_state.has_history`** (`SmsState.hasHistory`) tells the server the phone has threads to read - the engine's
  own read at report time, `SmsHome.summary.hasHistory` only when the engine does not answer - so the PWA lists
  Meldinger's row while the tile can linger outside "Styrt av Vibb".

## MMS pictures, step 3b (design 77 D8, D10-D12, D14, D16; branch `sms-mms`)

- **The isolated parse (D8, `sms/parse/`):** `MmsParseService` is an `isolatedProcess` service (`:mmsparse`, not
  exported, no permissions); `Application.onCreate` and `KidAppComponentFactory` return at once there
  (`Process.isIsolated()`). It is the only caller of `MmsCodec.parseNotification`/`parseRetrieveConf`/`parseSendConf`
  (they catch every `Throwable`, right there alone). Its wire is a hand-written Binder protocol (`MmsParseProtocol`):
  the input through a pipe, the answer field by field - every string's length and every count read and checked
  before the value (`readString`, `readCount`) - and pictures as RGBA_8888 pixels in a read-only `SharedMemory` with
  width and height (at most 1,600 px a side). `MmsParser` (the main process's side) checks `width * height * 4 ==
  size`, builds the bitmap with `copyPixelsFromBuffer`, and `PictureEncoder` encodes our own JPEG (quality 85; no EXIF).
  The service decodes with `ImageDecoder` (the header's size first: over 8,192 px a side or 40 MP is refused before
  decoding; the target size within the cap; software memory; EXIF orientation applied). A call that hangs 30 s, an
  answer out of protocol or a dead process is `Crashed`: three deaths on one push block it as malformed, on one fetched
  message fail it (`parse_failures`). `MmsParseBoundaryTest` pins the manifest entry, the two guards, the only caller,
  the length-first reads, and that the main process runs no decoder on what it didn't encode (none in `sms/mms`,
  `sms/engine`, `sms/store`, `PicturePick`; `MmsParser` builds bitmaps only from the checked pixels).
- **The state machine (§6.4, `sms/engine/MmsFlow.kt`, pure, `MmsEngineTest`):** the push committed (step 1's
  `pending_push`) -> `judgePush` with the isolated parse's notification: the transaction id answerable, D10's sender
  (`MmsGate.sender`: `+`/ASCII digits with `/TYPE=PLMN`; a hidden or missing `From` blocks `empty`, anything else
  `malformed`), D4 on the number (`SmsRules.decideIncoming`), then for an allowed sender alone the host
  (`MmsGate.location`: printable ASCII without whitespace, `http`/`https`, no userinfo, port absent/80/443/8080, the host
  - without case - on this SIM's MCC/MNC row; any other host blocks `bad_location`; an MCC/MNC with no row fails
  closed: shown at once as "Bildet kunne ikke hentes", never fetched) and the size (`MMS_CONFIG_MAX_MESSAGE_SIZE`,
  `MmsCodec.capOf`). Then the message with one placeholder part (PENDING) in the sender's conversation, `mms` row
  `NOTIFIED` -> `drainDownloads` (the host again, the expiry, `DOWNLOADING` and its try committed before the platform
  has it) -> `downloadMultimediaMessage` into `MmsFileProvider` -> `MmsResultReceiver` -> `DOWNLOADED` (committed, so a
  process death resumes at the parse) -> the isolated parse (pictures decoded only with the sender's `mms` on, D11) ->
  `onRetrieved`: the retrieve status (transient waits, 0xE2 is gone), D10's From again (equal, normalised, PLMN), D12,
  then the text (every `text/plain`, a top-level `multipart/alternative`'s first only) and the parts (our JPEGs
  `AVAILABLE` with their size, `NOT_SHOWN` with pictures off, `FAILED` for an undecodable picture, other types `FAILED`
  with their cleaned mime - their bytes never kept) and `MessageUpdated` with the parts. The MMSC gets its
  m-acknowledge-ind (through `sendMultimediaMessage`, AM's `MmsSender`). A push the rules can't judge stays in
  `pending_push` (D15) and is judged at the next rule change. A repeated push is known by its (transaction id,
  location) hash in `received_key` (QA L5).
- **Held pushes (D16):** a stranger's push is held unfetched (`held.push`, the number pool and caps); at an allow,
  `releasePass` puts it back into `pending_push` with its first receipt (`held_id`), and `judgePush` runs every D10
  check again before anything is fetched (`MmsEngineTest`: a released push whose host is no longer the SIM's is blocked).
- **The group rule (D12, `MmsGate.group`):** the own numbers are the parent's per slot, else the SIM's
  (`MmsGate.ownNumbers`); with none, a message to more than one address is blocked as `group` (one recipient is us).
  Every other address must be a contact with a text switch, and the sender must be let in by rule 5 (not the callback
  window). An accepted group's message moves to the group's conversation (keyed by its sorted participants;
  `MessageDeleted` from the sender's, `MessageAdded` in the group's). A reply goes to the stored participants as one
  MMS, only when the gate lets every one of them be texted (`ComposeState.GROUP_NOT_ALLOWED`: "Du kan bare svare når
  alle i samtalen er i telefonboka di"); a picture to a group needs every one's `mms`. In a group each received bubble
  names its writer; the notification is a group `MessagingStyle`.
- **D14:** the platform's "no MMS data"/"no data network"/"can't reach the MMS network" (and a throw) is `WAITING_DATA`
  ("Bilde venter - trenger mobildata"), tried again at each sync, at start, and when mobile data comes back (the public
  `UserMobileDataStateListener` and `DataConnectionStateListener` stand in for the design's `DataEnabledListener`, a
  system API); after five tries `FAILED` with "Bildet kunne ikke hentes - trykk for å prøve igjen" (the message's
  `failure` = `mms_retry`; the tap is `engine.mms.retryDownload`). A 404/410 or "message not found" is `GONE` ("Bildet
  er ikke lenger tilgjengelig", a new `PartState`). A download the platform never answered is given up after 15
  minutes. `sms_state.mms_waiting` counts the waiting ones; `mms_data_allowed` stays mobile data on (the per-SIM
  "MMS-meldinger" setting can't be read by an app - step 0).
- **`MmsFileProvider` (D10, QA L3):** `${applicationId}.mms`, not exported, `grantUriPermissions`. `in/<message id>` is
  write-only: a pipe whose reader thread stops at the request's cap (deleting the file) and renames the finished file;
  `out/<token>` is read-only (a send-req or an answer). The telephony broker grants the URI for the request; the result
  revokes it. Downloads and sends a process death left behind go after a day.
- **Pictures in the store (§6.4, `FilePartFiles`):** `files/sms/pictures/<sha256>` - every file our own JPEG, named by
  its hash, written to a temporary name and renamed; a name that isn't 64 hex is never opened. `openPart`/
  `openPartByHash` hand out only an `AVAILABLE` part's file. A delete (D18) or a dropped message deletes the files no
  part holds any more; an unsent pick goes after a day (`prune`).
- **Sending a picture (D11):** the compose bar's "+" (left of the field, the approved mockup; a tonal disc, quieter
  than the peach send) shows only while the bar is open and every recipient is a contact with `mms` and `sms_out`
  (`picturesAllowed`). With a usable camera it asks "Ta bilde" / "Velg bilde" in the screen (like the delete confirm),
  else it opens the photo picker at once. `PicturePick` is the one file that may name `ACTION_PICK_IMAGES` and
  `ACTION_IMAGE_CAPTURE` (`KidScreensEscapeTest`). **The camera is the camera app the parent allows**, not an in-app
  capture: `ACTION_IMAGE_CAPTURE` sent to a handler that is usable now (installed, not suspended, on the allowlist -
  `ContactSheet.usablePackages`, system first), writing one file of ours through the share provider's `capture/` path -
  no new dependency, no camera code in the Device Owner's process, and the parent's choices hold (an app they didn't
  allow isn't offered; design 68's camera switch stops it). The picked file's bytes (at most 30 MB) go to the isolated
  process (`DECODE_IMAGE`, at most 1,280 px), its pixels come back, and `PictureEncoder.forSending` encodes our JPEG
  under the limit (the carrier's maximum less room, never above 200 KB - FM #815; the quality steps down, then the
  size); `engine.putPicture` keeps it by hash and the preview shows above the field with a cross. `send` checks D11
  again (every recipient's `mms`, the picture held by the engine, the carrier's maximum) and stores an OUT message
  with its parts; the outbox (`drainMms`) gates every recipient again, composes the m-send-req with the vendored
  `PduComposer` from our files and hands it to `sendMultimediaMessage`; the result's m-send-conf is read in the isolated
  parse (a refusal there is `FAILED`).
- **A failed picture's retry (2026-11-10, found by the bubble-time emulator pass):** "Prøv igjen" sent `failed.body`
  alone - a failed picture with text went again as its text only and the failed message, picture and all, was deleted;
  a picture alone did nothing (an empty send, `Refusal.EMPTY`, no words). Now `MessageActions.retry` builds the send
  from the failed message's parts (`PictureRef` of each part's hash and size, in their order) with its text, as the
  first send did, and deletes the failed message only when the accepted message has the same text and the same hashes.
  **The bytes are there while the failed message is:** an outgoing picture is our own JPEG in `PartFiles`, named by its
  SHA-256; the `part` row of the message holds it, a file is deleted only when no part names it (`dropUnheld`,
  `prune`), and the retry's new message names the same hash before the failed one goes - so nothing is copied and
  nothing is lost. What can still stop it is the gate, as for any send: "Bilder i meldinger" switched off for the
  recipient since, the file gone all the same, the carrier's maximum lower now, or a part that isn't `AVAILABLE`. Then
  nothing is sent - never the text alone - and nothing is deleted: the failed message stays with its line and the toast
  is "Dette bildet kan ikke sendes" (`afterRetryRefusal` -> `AfterRefusal.PICTURE`, the existing
  `messages_picture_unusable`; a time rule, SMS off and the rest keep the send's own words). Two taps still send once
  (the engine's repeat rule compares the text and the hashes). Not changed: the link is offered by the text gate alone
  (`retryOffered`), so it also shows on a failed picture whose recipient lost "Bilder i meldinger" - the tap then says
  the line above; and a failed reaction's retry sends its words as before. `MessagesModelTest`, `MmsEngineTest`. Seen
  on `jelly36` against the fake MMSC, each case above by its POST and its rows: `docs/testing/emulator.md` §6ag (where
  a refused tap also leaves the line at "Ikke sendt", without the offer, until the thread is read again).
- **Debug-only emulator hook:** `src/debug/.../sms/MmsDebugReceiver` (DUMP-guarded, `checkReleaseHasNoDebugHook`,
  `DebugHookAbsentTest`): `--es push_hex` commits a push as `MmsPushReceiver` does (the AVD's console can't deliver
  one), `--es mmsc_host --es mccmnc` puts a fake MMS server on the list in memory, `--es stand_in <url>` plays the
  platform's MMS service (the AVD's carrier config has MMS off) through our own provider URI and result PendingIntents
  (`MmsDebug`; the release twin has none and stands in for nothing). `scripts/fake-mmsc.py` is the fake MMSC;
  `docs/testing/emulator.md` §6v, step 3b. The run found one screen bug, fixed: a tall picture bubble wasn't scrolled
  to its bottom after a send (`scrollToPosition` counts it shown once its top is).
- **Left for step 0 (the real phone):** the MMSC host table (`server/src/mmsc_hosts.rs`, empty: every MMS fails closed
  until then; `tests::the_table_is_well_formed` checks a new row's shape), each carrier's `From` and `X-Mms-Expiry`,
  the MMS maximum, whether the acknowledgement needs the content location as its URL (`MMS_CONFIG_NOTIFY_WAP_MMSC_ENABLED`),
  a real round trip, HEIF/AVIF in the isolated process, `pread` of the pixels' region on the Jelly Star's kernel
  (Android 13's ashmem - the emulator's android-36 ran memfd; a failing read only fails the picture, verification V1),
  the "MMS-meldinger" toggle, and the "Del" checks
  (`docs/review/qa-sms-screens.md`, "Step 3b").
- **Fix round after the code QA (`docs/review/qa-sms-mms.md`):**
  - one message's pictures are bounded as a whole (`ParseWire.PictureBudget`: four, 8 MP between them; past it "not
    shown", both sides), the main process keeps the pixels as descriptors and makes one bitmap at a time, and a parse's
    attempt is counted before it runs (`beginParse`), so a death of the main process while it handles the answer ends
    after three tries too (M1);
  - the sender is judged again (D4, as of now) before every fetch, before the parse and at its answer: a contact
    switched off, a removed one, or a callback-window stranger after the window closed gets nothing more fetched, and
    the waiting message goes (M2);
  - the photo picker is the system's own (`setPackage` of the system handler), and its answer is read only as another
    app's `content://` URI (`pickedUriAcceptable`); the camera's file is read as the file it is (M3);
  - the MMSC is answered only for a kept message, after every check (L1): an immediate fetch with an m-notifyresp-ind
    "retrieved", a released or retried (deferred) one with an m-acknowledge-ind; a push's identity is kept until its
    own expiry (at least a day, at most a week), blocked ones included (L2);
  - every parse runs in its own isolated process (`bindIsolatedService`, a fresh instance per call, L3); pixels are read
    with `pread`, never mapped (L4); a download past our cap is "too large" for good (a marker beside the file, L5);
    a parse service that can't be bound is counted and said in `last_error`, and nothing is lost meanwhile (L6);
  - `mms_data_allowed` is mobile data on, or a fetch that worked with data off (the per-SIM "MMS-meldinger" can't be
    read by an app; `SmsPrefs.mmsWithoutData`, L7);
  - a picture that waited is notified again, quietly, when it arrives; a message that moved to a group's conversation
    takes its notification from the sender's (L8);
  - a released MMS's held row stays held until its second judgement: "released" once the kid has it, "dropped" when it
    is blocked then (L9);
  - an MMS that failed for an unknown MCC/MNC is fetched once a policy's MMSC list knows the SIM (L10);
  - a fetched message that can't be read says "Meldingen kunne ikke vises" (N7); a pick the kid hasn't sent is never
    deleted with a message (N8); the send limit uses the conversation's own SIM and 10 KB of headroom (N3, N4); the
    camera gets a write grant only (N5).
  - **"Ikke styrt" with the role still ours** (N6): the rules aren't read then, so every MMS fails closed as
    "Bildet kunne ikke hentes" (no MMSC list) and groups fail D12 - SMS stays unfiltered (step 1's L3), MMS doesn't.
  - **D15's push pool** (deviation 7): a push the rules can't judge waits in `pending_push`, re-parsed in a fresh
    isolated process at each pass; its 100 rows are shared with strangers, so a flood while the rules are unknown can
    push out a contact's push (counted `malformed`). Accepted, as D15's 200 `HELD_RULES` rows for SMS.

## The kid's contact request: the model and the API client (design 77 §14; branch `sms-contact-request`)

A phone book and contacts feature, not an SMS one (the user, 2026-11-09): the kid asks the parents to add someone, and
a parent adds the contact in the PWA. The model and the API client came first; the kid's screen (the "+", the form,
the rows, the request's picture) came with design 79 (branch `phone-book-list`, below: "The phone book as one list").
The form calls `checkContactRequest`, then `ContactRequests.add` and `SyncRunner.request(.., "contact_request")`, and
the list shows `contactRequestRows`.
- **Pure model (`calls/ContactRequests.kt`, `ContactRequestsTest`):** `checkContactRequest` - the server's checks on
  the shared vectors of `contact_request.json` (the name through `TitleRules.clean`, 1-40 characters; the number
  through `PhoneNumbers.normalize`, never an emergency number - `Emergency.staticSet` or the platform's answer passed
  in; an optional Matrix ID through `isMatrixId`) and the phone's own (a number already a contact or in an open
  request; five open at most). `contactRequestAnswer` reads the server's answer (`accepted`, `capped`, `known`;
  `invalid`, another 4xx and an old server's 404 are "not sent"; 401/408/429/5xx/no answer retry).
  `reconcileContactRequests` at every accepted policy: a week-old request goes; an open one goes when its number is a
  contact (the contact arrived) or the policy's `call_policy.contact_requests` says `added`, and becomes "declined"
  (shown once) when it says `declined`. Since design 79 it returns `Reconciled(records, handOvers)`: the request's
  picture goes to a contact that arrived **in the phone book** (request id -> contact id), done before the list is
  written without the request. `contactRequestRows` / `markContactRequestsShown` /
  `dropShownContactRequests`: an answer shows once.
- **Store and sync:** `ContactRequests` keeps the records in credential-encrypted prefs (`contact_requests`: names and
  numbers), with listeners for a screen; `performMdmSync` reconciles after `SmsRuntime.policyAccepted` and uploads each
  PENDING request after the time request (`POST /api/devices/contact-request`, `MdmApi.sendContactRequest`,
  `ContactRequestBody`).
- **The picture stays on the phone** (design 79 revision 2, the user: "Server trenger ikke se bilde barnet selv legger
  til?"): the request's picture is never uploaded, `ContactRequestBody` is unchanged (`ContactRequestRecord.photo` is
  a flag of the phone's own, never in `body()`). The upload extension the contact request's QA planned
  (`docs/review/qa-contact-request.md`, "The photo extension plan") is not needed.
- **Code QA L5, done:** the form passes the platform's answer as `checkContactRequest`'s `isEmergency`
  (`CallSystem.isEmergencyOutgoing`, which asks Android's `TelephonyManager.isEmergencyNumber` and keeps the static
  list) - without it the phone would know only `Emergency.staticSet`, and a SIM's or a roaming country's own
  emergency number would pass the phone's check (the server's list is static too).
- **The upload's step is pure (code QA L4):** `recordsAfterUpload` (only the sent request moves, only while PENDING)
  and `uploadGoesOn` (a RETRY ends the upload), tested in `ContactRequestsTest`.

## Group texts switch and the learned own number (design 80 step 3, D13, §4.4; branch `calls-simpler`)

- **`call_policy.sms_groups`** ("Gruppemeldinger", D13): `CallPolicy.smsGroups`, `CallRules.smsGroups`, both
  defaulting to `true` - an older server's policy and rules stored before it keep today's behaviour (a deliberate
  exception to "absent denies": the key loosens nothing, and D12's members rule under it fails closed). Off:
  `MmsGate.group(..., groups = false)` turns every `Many` into `Blocked` (dropped as `group`, no allow path); a
  one-to-one MMS and every SMS are untouched. The engine refuses a send to a group (`Refusal.GROUPS_OFF`) and fails a
  queued group MMS at the outbox; a group thread's compose bar is `ComposeState.GROUPS_OFF`, "Gruppemeldinger er slått
  av" (`messages_groups_off`; channel-level states - read-only, unavailable - still win).
- **The capability `sms_groups_v1`** (`SMS_GROUPS_CAPABILITY`, in `STATUS_CAPABILITIES`): until a phone claims it the
  PWA draws the switch disabled ("Oppdater Vibb One først"), since an older engine would ignore an "off".
- **The learned own number** (§4.4, `sms/engine/OwnLearning.kt`, pure, `OwnLearningTest`): after D10's checks and the
  retrieve-conf's `From` matched the notification's, `MmsFlow.onRetrieved` learns "us" only when no own number comes
  from the parent or a SIM, no conflict is open, the sender is a contact with `sms_in` that isn't an emergency number
  (rule 5, SMS managed - not the callback window), and the message has exactly one `To` and no `Cc` - a `/TYPE=PLMN`
  full `+` number that isn't a short code, an emergency number, the sender or any contact. A later message meeting
  every condition with another `To` is the conflict: no number in use (groups dropped), both kept, nothing learned
  again. `OwnLearning.current` clears it on a change of the active SIMs (`SmsJudge.simSet`, the subscription ids,
  sorted), a number the parent stored, or a contact with the number; the cleared state is deleted from the store.
  `MmsGate.ownNumbers(parent, sim, learned)` takes it last, only while the parent's and the SIMs' are all missing.
  Kept in the engine's store (`own_learned`, one row; `messages.db` version 4) as `OwnLearned` JSON.
- **The report:** `sms_state.own_learned` (`SmsOwnLearned`: `number` - `null` during a conflict -, `from`, `at`,
  `conflict`), `null` for none, always sent (`MmsFlow.ownLearned()` on the engine thread). The shapes are
  `sms_contract.json`'s `report.sms_state.own_learned` and `report.own_learned_conflict`; the capability is
  `groups_capability`; `policy.call_policy.sms_groups` and `policy.groups_off` (`SmsContractTest`,
  `PolicyResponseCompatTest`).
- **The code QA's fix round** (`docs/review/qa-80-code.md` L2, L7): `simSet` "" (no subscription readable - a SIM
  loading, a modem restart) is "unknown", not a change: `MmsFlow.ownLearnedFor` keeps the stored state and uses none of
  it, and `OwnLearning.learn` learns nothing; only a readable set that differs clears it. A contact with `sms_in` that is
  an emergency number teaches nothing (pinned in `MmsEngineTest`), and nothing is learned while SMS isn't "Styrt av
  Vibb" (the mode check alone pinned, second pass V4). On the server side a learned number no longer blocks
  a contact: the parent can add it, and the phone then drops it (`OwnLearning.current`).
- Tests: `OwnLearningTest` (every condition, the conflict, what clears it, the order of the own numbers, D13 on
  `MmsGate.group`), `MmsEngineTest` (groups off end to end - the blocked group, the refused reply and the failed queued
  one; learning then a group judged with the learned number, the conflict, a SIM change; no learning from a stranger
  the callback window let in or while a SIM knows the number; a contact clears it), `MessagesModelTest` (the compose
  bar), `SmsContractTest`, `PolicyResponseCompatTest`.

## The phone book as one list (design 79; branch `phone-book-list`)

Design `docs/design/79-phone-book-list.md` (approved 2026-11-09 with one change: the emergency numbers at the bottom).
The grid of avatars became one list of solid night rows, with search, the kid's own contact pictures and the contact
request's screen.

**The ground (2026-11-10, the user; branch `sms-follow-book-ground`):** the phone book is on Dagen's plain night ground,
no longer the kid's wallpaper - the same resources as `activity_day.xml` (`@color/kid_ground` on the root, the header
`kid_ink`, the info line, "Venter på svar" and the count `kid_ink_dim`, the rows Dagen's `bg_day_event` tile) and the
theme's bars (no `showsSystemWallpaper`, light bar icons), so a later light look covers both by those resources. Gone
from the phone book: `WallpaperGround`/`KidInk`, the wallpaper listener and refresh, the adapter's `ink` and
`KidSearchField.countLine` (`HomeGroundTest`: only Home and kid Settings show the wallpaper). Nothing of design 79's
layout, sizes, order or behaviour moved. The card, the thread, the call screens and the picture screens were night already.

- **The order (pure `calls/PhoneBookList.kt`, `PhoneBookListTest`):** `phoneBookRows` - a request's answer once at the
  very top; the contacts with an unseen missed call, newest call first; everyone else A-Å (`listCollator`: the
  language's `Collator`, strength SECONDARY, kept per locale - nb puts Æ Ø Å last; ties by id); the kid's open requests
  under the one label "Venter på svar"; the **emergency numbers last**, A-Å among themselves (the user: "ikke legg nødnr
  på toppen ... Legg den på bunnen"). An emergency number with an unseen missed call sits in the top like anyone. With
  calls off only the emergency contacts are listed (no search, no "+", no requests); with no readable rules the one 112
  row ("Nødsamtale", the emergency menu). `canAsk` = managed and calls on (QA L2) gates the "+", the search, the request
  rows and "Be om å legge til".
- **The visit and "seen" (§3.2):** the visit's top is fixed by its first missed calls loaded (`visitTopAfter`: nothing
  leaves, a newcomer joins - a call-log observer while the phone book is started, and the Element X store's listener);
  the list waits for that first load, and a new visit starts at the list's top; a visit that ends empties the list
  (code QA L1), so a returning kid never taps a row of the last visit while the new one loads. Only the latest
  missed-call load is applied (`LatestOnly`, code QA L2; Home's loads too). A `CallPolicyStore` listener renders the list when a sync changes the rules mid-visit. `TopSightings` counts a top row with a
  red line as seen once it was at least half on screen for `PHONE_BOOK_SEEN_MS` (1 s, in all) while the phone book was
  resumed and focused (updated after every layout, scroll, resume, pause and focus change); at `onStop` (not a
  rotation) the visit's contacts get `MissedCallsRepo.markSeen(marks)` (one write, one word to Home - code QA N6) - up to
  the newest call the row showed;
  the mark only moves forward (`seenMarkAfter`). The card, the row's Ring and Melding and the card's Melding mark it
  seen at once. `MissedCallsRepo.addSeenListener` tells Home (resumed before the phone book stops), so Home's badge goes
  with the row. The notification and the lock glance's rows still go when the phone book **opens** (unchanged).
  An incoming call's screen over the phone book ends the visit (its `onStop`), so the call's return starts a new one.
- **The buttons:** `contactActions` - the card's logic moved out of `ContactSheet.show` unchanged (`ContactActionsTest`):
  Ring off while another call is live (an emergency number excepted), Melding to our own thread, else the app
  `resolveMessageButton` finds, else none. The row's Ring calls at once, but an emergency number's asks first ("Ringe
  Ambulanse?", `calls_confirm_title`).
- **The row, the adapter, TalkBack:** `PhoneBookAdapter` (a `ListAdapter` over stable keys, no item animator, the
  `PHOTO` payload rebinds only the avatars); `item_phone_book_row.xml` (`docs/notes/launcher/ui-polish.md`, "The phone
  book's row"); `rowLayout` - the buttons stacked under the text from font scale 1.3. A row is one node, role button,
  the full sentence ("Pappa, 2 tapte anrop i dag 14:02, 1 ulest melding"; "Ambulanse, nødnummer"), Ring and Melding
  its custom actions ("… i Element X" when Melding leaves Vibb); the two buttons are focusable for a keyboard only.
- **Search (`ui/search/`, `SearchFoldTest`, `KidSearchTest`):** `searchFold` (per code point: lowercase ROOT, NFD, marks
  dropped, ø æ œ ß đ ł þ ı; the peach highlight on the shown string) - for finding only, never deciding (nothing under
  `sms/` or in `CallRules.kt` calls it: D17's sender names stay exact); `searchMatch` (prefix, a word, inside) and
  `searchHits` (by rank, each A-Å). **One way** (code QA M3, decided): only the name folds; the query keeps Æ, Ø, Å and
  marked letters as typed - "o" finds Ola, Ørjan and Østby, "ø" finds only Ø names, "å" never finds Anna. A keystroke
  only filters (code QA M2): `render()` gathers the inputs (the view, the emergency set, unread, requests, every
  contact's buttons and app label - one lookup per package) at resume and from the listeners, the search runs
  `filter()` over them; Meldinger likewise, its emergency check asked once per number per render. The phone book's list goes to
  its top with `scrollToPositionWithOffset(0, 0)` (code QA, 2026-11-10): after a filter took the rows on screen away a
  plain `scrollToPosition(0)` lays out from the end, and a first hit taller than the list showed with its top cut. `KidSearchField` (`view_kid_search.xml`):
  the 44 dp pill, never focused by itself, no menus, autofill or suggestions, the count line "3 treff" (a polite live
  region, at most every 500 ms). From 8 rows, sticky while it holds text (`searchShown`). In the phone book while
  searching the top dissolves, waiting requests are hits too and the emergency numbers' hits come last; "Fant
  ingenting" and, from two letters with nothing matching, "Be om å legge til «Lina»" (the form, prefilled). Back
  (code QA L10): with the keyboard up only the keyboard closes; then the form, then the search's text, then leave
  (`phoneBookBack`); the query is cleared after Send and when the screen is left (kept across a rotation only). **Meldinger:** the
  same pill in the list and "Ny melding", the rows' shown titles only (`searchConversations`, `whoTitle`), never message
  text; Back: the keyboard, the delete confirm, the search, "Ny melding", then leave (`messagesBack`).
- **The contact request's screen (`ContactRequestForm`, from the parked WIP):** the header's "+" (a 40 dp peach disc
  laid over the shared header, which keeps room for it), the form in the list's place, a calm "Sendt" after Send
  ("Ferdig" back to the list; the line in the future tense, "Mamma og pappa får beskjed", code QA L5: the request goes
  with the next sync), an answer counts as shown only once its row was laid out on the visible list (code QA L9), and
  the shown ones are dropped when the phone book is left. The form's picture
  circle (tap or long-press) calls back into the phone book (`onPicture`); the form starts nothing itself
  (`KidScreensEscapeTest`, "the contact request only asks").
- **The kid's own pictures:** `docs/notes/launcher/home-and-photos.md`, "The kid's own contact pictures (design 79)".
- **Decided in the build (the design left it open):** the request rows (answers and waiting) show only while the kid
  can ask - under calls off an answer waits for a visit where it can be read; waiting rows newest first, without the
  number (the phone book shows no numbers); a row counts as on screen when at least half of it is; the list waits for
  the visit's first missed calls rather than reordering under the finger; "Be om å legge til" needs two letters (not
  two characters); while searching the waiting requests come after the contacts' hits and the emergency numbers' hits
  last; Meldinger's hits are ranked like the phone book's (A-Å within a rank), not newest first; the request's picture
  draft is deleted when the form closes without Send (Avbryt, Back, calls going off), when a returned draft finds the
  form closed, when the phone book finishes, and at its first resume every draft but the form's and none younger than an hour (code QA L3 and its second pass: never between a draft being written and the form taking it); the
  row's badge is the count alone. The code QA's open points, decided: (a) the request rows stay hidden while calls are
  off; (c) an incoming call's screen ends the visit; (d) no long-press hint for now; (e) the emergency avatar keeps
  its 3 dp ring.
- **Not device-tested yet:** the Jelly Star (fling through 50 rows and count accidental presses, §9; whether a kid
  finds the long-press, §5.1), TalkBack's custom actions (the sentence was read from the UI dump), an incoming call
  before the first unlock (the test AVD has no Android screen lock, so it never is "before the unlock"). The emulator
  run: `docs/testing/emulator.md` §6v "design 79".

## The lock glance's Meldinger row, step 5 (design 77 §6.8; branch `sms-glance`)

The glance's side (where the rows sit, the look, the tap, the swipe): `docs/notes/launcher/lock-and-glance.md`, "The
Meldinger rows". What was decided (the design left it open):

- **One row per conversation** with unread messages, like the missed-call rows (one per contact) and every app's
  conversation rows - no folding into "Mamma og 2 til": each row opens its own thread and can be swiped away on its
  own, and the glance's own limits (8 rows, "+ N til") hold many. A group (D12) is one row named by its members, its
  text "Pappa: ..." with the switch on. A missed call and a message from one contact are two rows.
- **Where:** Dagen's reminder row stays first (the one row with a deadline); then the rows newest first by time - the
  missed calls, the apps' conversations and ours together. A row's time is its newest unread message's.
- **Only accepted messages:** the rows are built from `engine.conversations()`, which holds only shown messages - a
  message held for the parent (D16) or for the rules (D15), and a blocked one, has no conversation, so a stranger never
  makes a row, not even its sender. A message the callback window let in (D4 rule 2) is accepted: it shows, its number
  as the name, "Ukjent avsender: ..." before the text with the switch on.
- **Modes and time rules - the engine's summary decides:** the row shows whenever the conversation has unread
  messages, the same count as Home's badge (`SmsHome`), in every mode: in "Av"/"Ikke styrt" no new message arrives,
  and what was left unread before still shows and opens the read-only thread. Under a time rule the row shows (the
  message was notified, silently, §6.7), and its tap takes the thread only where the rule allows calls - the message
  screens' own rule (`messagesUsableUnder`), else the plain keypad; from the lock with a rule in force it is handed to
  the time-rule screen, as every other row.
- **The level** (design 82 D4, `docs/design/82-meldinger-as-an-app.md`): the parent's choice for Meldinger on the
  lock is the policy's `lock_glance.sms` - `GlancePolicy.sms` (`badges/GlanceRows.kt`: `OFF`, `NAME`, `TEXT`, or
  `LEGACY` from a server without the key) - and one pure function gives it to both readers, `smsLockLevel(sms,
  callPolicyState)` (`sms/SmsGlance.kt`): the word as it is, whatever the call rules say; `LEGACY` is the rule before
  design 82 - the text only with managed rules whose `call_policy.sms_lock_text` says so, otherwise the name. **Off:
  no row** (pure `smsGlanceRows`: none, and no message read) **and no wake** (`smsGainsRow` takes the level: nothing
  to gain) **and no notice buzz** (`smsPostBuzzes` takes it too); the system notification, the badge and the thread are untouched, as for an app set to Off. A read of
  the engine that fails drops the lock's rows (`SmsHome.refresh`: they were built at the last good read's level) and
  keeps Home's tile and badges. The reading fails closed
  (`glanceSms`): another word or value is Off, a `lock_glance` that is no object is Off, a cache without the key or
  no policy yet is Off; only a missing `sms` in an object, or a fresh policy without `lock_glance`, is `LEGACY`. A
  changed level reaches the rows by `GlanceStore.smsLevelChanged` (set by `SmsRuntime.listen` to `SmsHome.refresh`;
  the store holds no Context), and the apps' notices are cleared and reswept only when `apps` changed - so the other
  rows never blank for it. At start-up the level is Off until the cached policy is applied. Tests: `SmsContractTest`
  (`sms_contract.json`'s `app.lock_read`, every row), `SmsGlanceTest`, `GlanceRowsTest`, `PolicyResponseCompatTest`.
- **The text:** only at Full text (`SmsLockLevel.TEXT`), read on the engine thread only then (`SmsHome.refresh`),
  cleaned as the glance's other texts (`cleanGlanceText`, 200 characters). At Name only the row never says more than
  the name and the count - not "Bilde" either.
- **When it goes:** the thread read (opening it marks the messages read: `Read` -> the summary's refresh), the
  message or the conversation deleted (`MessageDeleted`), and a swipe or "Fjern" on the lock (`SmsGlance.dismiss`:
  hidden in memory until the conversation's next message, our notification of it cancelled unless a newer message came during the drag, nothing marked read - Home's
  badge stays, as for a dismissed missed call).
- **The notice wake** (launcher round 7, the parent's `lock_wake_on_notification`; the PWA's line says "tapte anrop og
  meldinger" since this step): our SMS notification's post is a notice that came (`SmsNotifier.notify` ->
  `PinLockRuntime.noticeCame()` on the main thread, as the missed-call notice's), so the switch and `noticeWakeAllowed`
  decide the rest - switch off, nothing changes. Pure `smsPostAlerts`, round 7's conditions for an app's notice:
  - the post went through and is a message's own (a held or blocked message is never posted; the reply's "not sent"
    update, a group move's quiet re-post and a message read in the thread in front never wake);
  - not silent (a time rule without calls, a picture told again);
  - no Do Not Disturb at all, fail closed (code QA H1): a silent bedtime that allows calls has our own zen rule on,
    which lets no message through - the screen stays dark too;
  - notifications on, the channel at DEFAULT or above;
  - the rows gain a row (code QA M1, `smsGainsRow`): the conversation's only unread message, or the first one after its
    row was swiped away. Twelve messages from a cousin wake once, as twelve in one Element X room do.
- **A group MMS leaving a one-to-one conversation** (D12; step 3b's code QA L8): `SmsNotifier.afterMove` rebuilds that
  conversation's notification quietly from what is still unread there (`engine.notifiable` since design 81), and cancels
  it only when nothing is - before, a group's message took away the sender's own unread messages from the shade.

## SMS reactions (design 81 steps 1-3; branch `sms-reactions`)

The design note is `docs/design/81-sms-reactions.md` (its §12 lists where the build goes past it). Step 0, the capture
from a real iPhone, isn't done (the user: build without it): every form is Apple's string table, nothing is confirmed
on the wire but nb's heart.

- **The forms and the rules (`sms/engine/Reactions.kt`, pure):** `FORMS` is §1's table - en (US, with the en_GB
  `Emphasised` / `exclamation mark` read only) and nb, the six classic Tapbacks and the iOS 18 `{e}` form, a quoted text
  or `an image` / `et bilde`, adding and removing. `parse` is strict and whole-message: a known head, one of four quote
  pairs closed by the very last character, a body of at most 2,000 units; `candidate` is H1 (an MMS of exactly one text
  part and nothing else; an SMS always is); `resolve` is D3 over the conversation's newest 100 bubbles older than the
  reaction (equal texts: an add to the newest the reactor didn't write, a removal to the one carrying it; a cut quote
  with ten code points; a picture only when exactly one image part is among the newest ten and it is there,
  `AVAILABLE` - never an MMS still being fetched, whose placeholder could turn out to be anything: code QA M1);
  `state` is D4 (per person the newest add unless a later removal of the same key; the kid's failed rows ignored,
  queued/sending/uncertain pending); `format` is D8 (classic words for the six, the iOS 18 form otherwise, 200 units
  with a grapheme-safe cut). **The vectors** `launcher/app/src/test/resources/sms_reactions.json` (launcher only, as
  `sms_rules.json`) pin every form with its `source` (`apple` with the iOS versions, `wire`; a capture adds rows marked
  `capture`), the parse, H1, the matching, the state and the sent bodies (`SmsReactionsTest`, which also parses every
  sent body back).
- **The emoji list (`sms/engine/Emoji.kt`, `assets/sms/emoji-16.0.txt`):** Unicode 16.0's `emoji-test.txt` stripped by
  `scripts/emoji-list.py` (the source's SHA-256 pinned there; the asset's in the vectors). `canonical`: at most 16 units,
  U+FE0E dropped, a listed sequence, returned fully-qualified; `key` drops the selectors. Its `groups` (fully-qualified,
  no skin tones, no Component) are the picker's. `SmsRuntime.emoji` reads it once (at start, before a receive needs it);
  unreadable, the six still work (`Reactions.emoji`: they are constants). Its licence text is `LICENSE-UNICODE`.
- **The store (`messages.db` v5, on design 80's v4):** `reaction_to`, `reaction_emoji`, `reaction_key`, `reaction_op` and
  the `message_reaction` index. `messages()`, `lastMessage()` and `unread()` leave reaction rows out; `reactions(ids)`
  and `unreadReactions` read them; `markReadUpTo` marks the bubbles and the reactions on them that came later. The
  version steps are one list (`STEPS`) whose hashes `SqliteStoreVersionTest` pins (L7, append only).
- **The engine:** `show(…, attach)` matches inside the receive's transaction (`attachment`) and returns
  `Received.Attached` - an incoming add stored unread, a removal read; never for a released held message (D10).
  `MmsFlow.onRetrieved` does the same once the text is in, in the conversation the message ended up in, after every
  sender and group check (a group with texts off is dropped first, design 80 D13). A placeholder never carries a
  reaction, so a move, a drop or a retrieve has nothing to give back. `messages()` hangs `reactions` on each bubble;
  front ends never fold.
  `send` takes `SendCommand.reaction` (`ReactionCommand`): the body composed first, the target a shown received
  non-reaction bubble of the conversation with text or an available picture, the emoji listed, a removal only of the
  kid's own in effect - then the same gate, outbox and `UNCERTAIN` as any text (a group reaction is a group MMS, refused
  with `GROUPS_OFF` when group texts are off). `repeated` compares target, op and key. A delete takes the bubble's
  reactions with it, one `MessageDeleted` each; `removeReactions` ("Fjern reaksjonene") deletes the others' rows only.
- **The notification (`SmsNotifier`):** lines from `engine.notifiable` (the unread bubbles first, then unread reactions
  in effect in the lines left - a reaction never pushes a message out; "Reagerte med ❤️ på «…»" with the quote cut at
  40, or "på et bilde"; the public text "Ny reaksjon" only when every line is a reaction, `smsPublicIsReaction`). A
  reaction from someone else posts on **`launcher:messages_reactions`** (DEFAULT, no sound, one 40 ms buzz, no heads-up)
  - or on `launcher:messages_silent` under a time rule without calls or any Do Not Disturb (filter not `ALL`, fail
  closed); the pure `smsChannel` decides - and never wakes the lock. The pattern is frozen with the channel at its
  first creation: a new length needs a new channel id. 2026-11-10, the user: one short tap, 40 ms (it was 80; no release
  has created the channel yet, so the id stays) - the length is theirs to tune on the Jelly Star before that release.
  The emulator pass below ran with 80 ms. A removal, or a waiting picture that turned out to be a reaction, rebuilds the notification
  only while it is up (`afterReaction`).
- **The thread (`ThreadActivity`, models in `MessagesModel`):** the chip (`reactionChip`: three different emoji then
  "+N", the kid's own in a peach ring and dimmed while pending, `•` where `Paint.hasGlyph` says the font can't draw it)
  on the bubble's bottom edge at the corner facing the middle, with its own room below the bubble (its measured height
  less 5 dp, inside the bubble's padding - never over the text, and the bubble's time line comes under the chip) and
  the bubble never narrower than it; `hasGlyph` kept
  for the process;
  TalkBack reads "Reaksjoner: ❤️ fra Mamma, 👍 fra deg" in the bubble's description. A long press opens the panel
  (`reactPanel`: the 4 x 2 grid only where the compose bar is open and on a received bubble with text or an available
  picture - the six, "+", the kid's other reaction; "Fjern reaksjonene" when others reacted; "Slett"), or the delete
  confirm as before when there is nothing else; TalkBack gets the same as custom actions. The picker (`pickerItems`)
  takes the list's place: "Nylig brukt" (five, the six excluded, `ReactionRecents` in the launcher's own CE
  preferences, written only after an accepted add), the nine groups' strip and headings, five columns, only what the
  font draws.
- **On the emulator** (2026-11-10, `docs/review/qa-81-code.md` "Emulator pass", `docs/testing/emulator.md` §6aa): the
  v4 -> v5 upgrade and a fresh store alike, the SQL with reaction rows present, the chip, panel and picker at 288 dp,
  the channel and its 80 ms buzz (silent under Do Not Disturb and a time rule), the picture rule, a group reaction, the
  sent bytes. Not on a device yet: TalkBack running, the pending dim, whether the buzz is felt. Seen there, outside
  this design: on a kiosk phone every notification is muted while handy's lock is up (`featuresWhileLocked` removes
  NOTIFICATIONS) - for the user to decide.
