# Step 10: own PIN lock screen + redesigned incoming/in-call screens

PLAN.md:213-221. S = kid-phone-server, L = kids-launcher-mdm (branch `handy`, paths under `app/src/main/java/com/kidslauncher/mdm/`).
01-09 guarantees stay (fail closed, emergency always works, override/pause never lift call rules). AOSP = `android14-release`
on android.googlesource.com, read 2026-10-05: DPM = `DevicePolicyManager.java`, UC = `am/UserController.java`, LTC = `wm/LockTaskController.java`.

## 1. Platform facts
| Fact | Source |
|---|---|
| `setKeyguardDisabled(true)` = screen lock "None"; **no effect while a PIN/pattern/password is set** (returns false); setting one later re-enables the keyguard; dismisses a showing keyguard (P+) | DPM:13072-13093 [verified: source] |
| With no credential, CE storage is unlocked automatically at boot (`unlockUserKeyIfUnsecured`) - there is no before-first-unlock state, only the seconds between `LOCKED_BOOT_COMPLETED` and unlock | UC:1877-1879 [verified: source]; timing on the Jelly Star [needs device test] |
| Lock-task features are re-applied live to the status bar (`updateLockTaskFeatures`); without `NOTIFICATIONS` the shade, notification icons and heads-up alerts are disabled; without `HOME`/`OVERVIEW` those buttons are | LTC:106-124, 861-875 [verified: source] |
| Emergency dialer / `ACTION_CALL_EMERGENCY` / system dialer may start in lock task only with `LOCK_TASK_FEATURE_KEYGUARD` | LTC:386-388, 455-470 [verified: source] |
| Removing a package from `setLockTaskPackages` clears its locked task (`performClearTaskForReuse`) - so the lock must **not** narrow the pinned packages (the kid's app would lose its state at every screen-off) | LTC:760-784 [verified: source] |
| `setStatusBarDisabled` blocks notifications and quick settings outside lock task only (registered but ineffective while pinned) | DPM:13101-13126 [verified: source] |
| No silent PIN removal: `resetPassword` throws `SecurityException` for a DO targeting O+ (R+); `resetPasswordWithToken(admin, null, token, 0)` clears the credential, but a token set while a credential exists becomes active only after a confirm-credential (`KeyguardManager.createConfirmDeviceCredentialIntent`); we set no password quality/complexity, so "null" is allowed | DPM:5927-5931, 5983-5987, 6099-6106 [verified: source]; L: no `setPasswordQuality`/`setRequiredPasswordComplexity` anywhere (grep) [verified: our code] |

## 2. The boot-deadlock question (L `CLAUDE.md:145,264`, `AppEnforcer.kt:715-733`)
The incident was: secure keyguard + kiosk suppressing it (no KEYGUARD feature) + a Home that can't run before CE unlock.
With no credential the first factor is gone: CE unlocks by itself at boot (UC:1877-1879), `HomeActivity` resolves, and our
lock is ordinary CE code - **the BFU state disappears**. Decisions:
- **Keep `LOCK_TASK_FEATURE_KEYGUARD` forced** (`LockTaskHelpers.kt:67-70`, `EnforcementPlan.kt:138`): it carries the
  emergency exemption (LTC:386) and is the safety net for a phone where a credential still exists or comes back.
- **Keep all direct-boot code unchanged** (02 "Direct boot (task 15)", `DirectBootComponents`, `boot_call_policy`,
  `boot_call_blocks`, `KidAppComponentFactory`): it still covers the boot window, unmigrated phones and a kid/parent who sets an
  Android PIN again. `PinLockActivity` is **not** direct-boot-aware (rule in L `CLAUDE.md:55` unchanged).
- **New risks, all "kid can't get in", none "phone dead"** (calls answerable, emergency, adb, server reach stay):
  (a) hash missing → lock off; hash unparseable → only the parent code unlocks, reported; (b) `PinLockActivity` crash loop →
  persisted crash guard like tsnet's (`CLAUDE.md:136`): 3 crashes in 2 min disable the lock until the next sync, reported;
  (c) re-front fighting a system screen → re-front at most 3×/min, never during a call or the emergency flow. The lock is a
  privacy screen, not an enforcement boundary: suspension, kiosk and call rules still enforce everything behind it.

## 3. When the lock is active, when it shows
Active (`PinLock.enabled`) only if: managed, policy carries a kid PIN, `!KeyguardManager.isDeviceSecure`, and
`setKeyguardDisabled(admin, true)` returned true (applied in `AppEnforcer.apply`, re-checked on every apply). An override or
pause does **not** switch it off (it is the kid's own lock). Unmanaged → `setKeyguardDisabled(false)`, lock off.

Pure `lock/PinLockState.kt` (DISABLED / LOCKED / UNLOCKED; events below), glue in `lock/PinLockRuntime.kt`:
- **Screen off** (receiver moves from `CommandListenerService.kt:97-103,260-276` into the runtime; same registration):
  → LOCKED and start `PinLockActivity` at once, so it is drawn before the next screen-on (no content flash
  [needs device test]). Not while a call is active (`OngoingCalls.calls` or `TelecomManager.isInCall`): the proximity
  sensor turns the screen off mid-call; then LOCKED is applied when the last call ends with the screen off.
- **Screen on / `USER_PRESENT`**: start the lock if LOCKED and not resumed (backstop). **Process start / boot**: LOCKED
  (no "unlocked" is persisted; a killed process fails closed); Home's `onResume` checks it before `redirectToLockScreenIfLocked`
  (`HomeActivity.kt:280-298`).
- **Over every activity, kiosk or not**: own task (`singleTask`, `taskAffinity=${applicationId}.pinlock`,
  `excludeFromRecents`, `showWhenLocked`); our package is always pinned, so it comes to front over an allowlisted app without
  clearing that app's task. `onStop` while LOCKED and no call/emergency flow → re-front after 500 ms (covers the camera
  gesture with an allowlisted camera - step 9 QA #14 - and other apps' full-screen intents). Back is swallowed. An alarm
  app's ring screen is covered too; its sound continues and the kid stops it after the PIN (question 4).
- **While LOCKED**: kiosk → features = plan features minus `NOTIFICATIONS | HOME | OVERVIEW` (pure
  `featuresWhileLocked(base, locked)` in `LockTaskHelpers.kt`, used by both the fast path and `computeEnforcementPlan`, so a
  sync can't undo it; `SYSTEM_INFO` stays - the mock shows Android's status line; KEYGUARD stays). Kiosk off →
  `setStatusBarDisabled(true)`. On unlock both are restored. Both are fast single DPM calls outside `apply()`'s lock.
- **Time-rule `LockActivity`**: always under the PIN lock. `LockActivity.start` (`LockActivity.kt:240-244`) and
  `TimeRulesRuntime.recheck` (`TimeRulesRuntime.kt:203-205`) do nothing while LOCKED; after a correct PIN the runtime calls
  `recheck`, which shows `LockActivity` if a rule is active. Kid Settings, phone book etc. are ordinary screens under it.
- **Screen time**: `screenTimeCounts` (`ScreenTime.kt:185-190`) gets `pinLocked`; `PinLockActivity` joins `FREE_SCREENS`
  (`ScreenTime.kt:172-177`); `ScreenTimeTracker.kt:70`'s keyguard test is now always false.

## 4. What stays reachable while locked
Only: the keypad, **Nødsamtale** (confirm "Ring 112?" → shared `calls/EmergencyCall.kt`, extracted from
`LockActivity.kt:80-106`, Telecom then `EmergencyDialer.open`; starts a 2-min "emergency flow" that suppresses re-front until
the call ends), **incoming calls** that our screening/rules allow (§6), Android's power menu (GLOBAL_ACTIONS as the server
sets it). Not: shade, quick settings, Home, recents, notifications' content, the phone book (outgoing calls need the PIN,
like any phone; emergency is the exception). A parent code link ("Foreldrekode", small text under Nødsamtale - **not in the
mock, question 1**) opens the existing override dialog (`LockActivity.kt:108-142`, `OfflineOverride.verifyPin`, its 5/15-min
lockout); a match unlocks the PIN lock only, without `activate()`.

## 5. Kid PIN: storage, verification, rate limit
- S hashes with `security::hash_pin` (`security.rs:232-252`, PBKDF2-SHA256 210k, 16 B salt); L verifies with the same code,
  extracted from `OfflineOverride.kt:91-103,144-146` into `server/PinHash.kt` (`verify(pin, hashHex, saltHex)`), on a
  background thread with the keypad disabled (~0.5 s [needs device test]). Separate fields (`kid_pin_hash/salt`) and verifier:
  **the kid PIN never passes an override/Settings gate and the override PIN is never the kid PIN**: S rejects a kid PIN that
  verifies against the override hash and an override PIN that verifies against the kid hash (new `security::verify_pin`).
- Kid PIN 4 digits (mock: 4 dots; question 2); `kid_pin_length` is sent so the keypad submits at the last digit.
- Pure `lock/PinBackoff.kt`: attempts 1-4 free; then waits 30 s, 1 min, 2 min, 5 min, 15 min, 15 min... (cap); never wipes.
  CE prefs `pin_lock_state` (`commit()`): `failures`, backoff as a `BootClock` window (wall + elapsed + boot,
  `OfflineOverride.kt:46-61` pattern) - a clock change can't shorten it and a reboot restarts the full wait. Reset on success
  and when the hash changes (new PIN from the parent). UI: "Feil kode" + shake; during backoff "Prøv igjen om 0:28", keypad off.

## 6. Calls with the lock (and the redesign)
- Incoming allowed call: `KidInCallService.showUi` (`KidInCallService.kt:120-135`) already starts `InCallActivity` directly
  (own task `.call`, `showWhenLocked`, `turnScreenOn`, manifest `AndroidManifest.xml:208-217`) - it lands on top of the PIN lock.
  The `CallStyle` full-screen intent (`CallNotifications.kt:22-37`) stays as the backup; its heads-up is suppressed while
  locked (no NOTIFICATIONS feature / status bar disabled), which is fine. When the last call ends `InCallActivity` finishes
  and the lock task is in front again; if the call was answered from LOCKED the state stays LOCKED (no PIN bypass via a call).
  While LOCKED the in-call screen shows nothing that leads elsewhere (no contact sheet, no keypad).
- Emergency calls: still shown by the preloaded dialer (02 §1); re-front suppressed while `isInCall`.
- **IncomingCall.dc.html** (`activity_in_call.xml` incoming state, `InCallActivity.render` `InCallActivity.kt:63-96` split into
  `renderIncoming`/`renderActive`): gradient #1C3A6B→#14213D (70 %), "Ringer deg …" 15sp/700/α .8, 140 dp avatar
  (`KidAvatars` contact photo, else the placeholder figure on #DCE7F2) with a 4 dp #4DABF7 ring, name 30sp/800 (one line,
  ellipsized; unknown → the number), spacer, Avvis (#E03131, handset rotated 135°) and Svar (#2B8A3E) 76 dp circles with
  14sp/700 labels, spaced around. Nunito as in 08. Replaces today's blue rectangle buttons (screenshot `images/3.png`).
- **InCall.dc.html** (active/dialing/holding): timer (or "Ringer …"/"Venter"/"Avsluttet") where the status was, same avatar
  and name; Høyttaler + Demp 60 dp circles (white α .16; **on = white fill, navy icon** - the mock shows only "off"), 40 dp
  gap; Legg på 76 dp red, centred, 14 dp below. Proximity/endpoint logic unchanged. nb + en strings, content descriptions.

## 7. Remote lock, migration, parent text
- **Remote lock** (`locate.rs:249-263`, command `lock` → `MdmSyncWorker.kt:291-294` → `LocateCommands.kt:402-410`): when the
  PIN lock is active, `PinLockRuntime.lockNow()` (LOCKED, show the lock, apply features) then `dpm.lockNow()` (screen off; with
  no credential it only sleeps [needs device test]); result message "locked (handy lock)" vs "locked (Android)". Locate page
  text (`device_locate.html:82-83`): "Locks with the kid's PIN; calls from allowed contacts and 112 still work."
- **Migration** (phones already enrolled with an Android PIN): the launcher can't remove it unattended (§1). Until it's gone
  our lock stays off (no double lock) and `lock_state.inactive = "android_credential"` makes the device page show:
  "Remove the Android screen lock to switch to handy's lock". Runbook (`docs/testing/emulator.md` + 04 runbook): parent
  enters the override code (kiosk and Settings released, `EnforcementPlan.kt:74,169` [verified: our code]), Settings → Security → Screen lock → None; or with debugging on
  `adb shell locksettings clear --old <PIN>`; next apply sets `setKeyguardDisabled(true)`. Automatic alternative (token +
  confirm-credential screen from our PIN-gated Settings) is possible but the confirm screen is Settings' activity, blocked by
  the kiosk app block - not worth it for a handful of phones (question 3). New phones: no Android PIN at provisioning.
- **Privacy text** (device page, Screen lock card, nb + en): "Handy's lock replaces Android's screen lock. It keeps people
  around him out, but the phone no longer has an Android code: its storage is encrypted with a key that doesn't depend on
  a code, so someone who steals it and has forensic tools may read photos and messages. Apps that require a screen lock
  (some bank/ID apps, Google Wallet, passkeys) may refuse to work [needs device test]. Fingerprint unlock isn't available.
  Lock and wipe on the Locate page still work."

## 8. Server (S)
- `0030_kid_lock.sql`: `device_policy.kid_pin_hash/kid_pin_salt` (TEXT), `kid_pin_length` (INT), `device_status.lock_state_json` (capped).
- Device form (`devices.rs:1300-1350`, template `device_detail.html:320-324` pattern): `new_kid_pin` (exactly 4 digits),
  `clear_kid_pin`; same keep-on-invalid rule; cross-check with the override PIN (§5, flash an error instead of saving);
  security events `kid_pin_changed`/`kid_pin_cleared`. Screen lock card with the privacy text and the status below.
- Policy (`device_api.rs:230-231`, `models.rs:403-404`): `kid_lock: {pin_hash, pin_salt, pin_length} | null`. A launcher
  without `pin_lock_v1` capability ignores it. Absent/null = lock off (privacy feature; fail-open is the safe side here).
- Status `lock_state: {active, inactive: null|no_pin|android_credential|keyguard_not_disabled|crash_guard|bad_hash, locked,
  failures, backoff_until_ms}`; the page warns on every `inactive` but `no_pin`. No unlock times (privacy). Cap. `pin_lock_v1`.

## 9. Tests, screenshots, device checks
JVM (L): `PinLockStateTest` (every event × state; screen-off during a call; call answered from LOCKED ends LOCKED; process
start LOCKED; remote lock; config change to DISABLED), `PinBackoffTest` (schedule, cap, clock back/forward, reboot restarts the
wait, reset on new hash), `PinHashTest` (shared vector with S `security.rs` test: fixed salt), `LockTaskHelpersTest`
(`featuresWhileLocked` keeps KEYGUARD/SYSTEM_INFO/BLOCK bit, drops NOTIFICATIONS/HOME/OVERVIEW), `ScreenTimeTest` (`pinLocked`),
`DirectBootComponentsTest` unchanged, `PolicyResponseCompatTest` (cache without `kid_lock`). S `tests/step10.rs`: set/clear,
4-digit validation, override/kid cross-rejection both ways, policy JSON snapshot, status stored + capped, warnings.
Screenshots (emulator, 320×568 dp + 411 dp, nb + en): [ ] lock empty/2 digits/wrong/backoff; [ ] lock over an allowlisted
app (kiosk) and over Home (kiosk off), shade pulled; [ ] incoming named+photo, unknown, long name; [ ] in-call timer, speaker
on, muted, dialing, holding; [ ] incoming over the PIN lock.

Device checks (Jelly Star, release) [needs device test]: 1. Fresh phone, no Android PIN: `setKeyguardDisabled` true, reboot →
Home + our lock within seconds, no `FallbackHome` hang, `CommandListenerService` up (adb). 2. Screen off/on 20× in an
allowlisted app: lock before any frame of the app; app state kept. 3. Pull-down, Home, recents, power-button camera gesture
while locked: nothing reachable (kiosk on and off). 4. Allowed contact calls while locked: answer, speaker, mute, hang up →
back on the lock; unknown caller rejected. 5. Nødsamtale → emergency test mode (`cmd phone emergency-number-test-mode`, never
112) connects, no re-front fight. 6. 5 wrong PINs → 30 s wait survives clock change and reboot. 7. Locate → Lock with the
kid in an app: lock shows, screen off. 8. Phone with an Android PIN: our lock off, device page warns; runbook removes it;
next sync enables ours. 9. Bedtime rule active: PIN first, then the bedtime screen; screen time not counted on the lock.
10. Kill the process while unlocked: next screen-on is locked. 11. Bank/ID app with no screen lock (privacy text claim).

## 10. Questions for the user
1. Parent-code link on the lock (not in the mock)? 2. Kid PIN fixed at 4 digits? 3. Migration by runbook (recommended) or
automatic confirm-credential flow? 4. Alarm ring screen hidden behind the lock until the PIN - acceptable?
