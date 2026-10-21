# 59 - Android's own lock screen with a real PIN instead of Vibb's own PIN lock (assessment)

Asked 2026-10-21 (the user: should Vibb leave its own PIN lock - design 10 and everything built on it - for Android's
lock screen with a real Android PIN? What do we gain, and what control do we lose?). An assessment, not a plan of
record: nothing here is built. Launcher (`launcher/`, paths below under
`launcher/app/src/main/java/com/kidslauncher/mdm/` unless they say otherwise), with one policy/command change on the
server. The music app is untouched.

The user's own points, answered where they belong:
- "If it's only that phone numbers can call freely between power-on and the first unlock, that doesn't sound very
  risky." That isn't what happens: see §2.3. Calls are **not** free before the first unlock. The real cost of BFU
  lies elsewhere: no server contact, no Locate or wipe, and Fossify's alarms don't ring.
- "But control of notifications, and who may show them, is important." Android gives a device owner only
  all-or-nothing switches for the lock screen. Per app means our own glance page (§3). That decides between the
  options.
- "The music widget could probably become an Android-compliant widget that is allowed on the real lock screen too?"
  Not as a widget on a phone. Android's own media player already shows on the lock screen by default (§5).

How each claim was checked:
- **[aosp]**: AOSP `main`, read 2026-10-21 via `?format=TEXT`, at the file:line given. Short names:
  - `DPM` = `core/java/android/app/admin/DevicePolicyManager.java`
  - `DPMS` = `services/devicepolicy/.../DevicePolicyManagerService.java`
  - `wm/` = `services/core/java/com/android/server/wm/`
  - `SysUI/` = `packages/SystemUI/src/com/android/systemui/`
  - `NMS` = `services/core/.../notification/NotificationManagerService.java`
  - `LPU` = `core/java/com/android/internal/widget/LockPatternUtils.java`
  - `keystore2/` = `system/security/keystore2/src/`
  - Telecom = `packages/services/Telecomm/src/com/android/server/telecom/`

  Vendors (MediaTek on the Jelly Star, Pixel's SystemUI) may differ. Each SystemUI claim is also a device check.
- **[code]**: read in our sources at `main` `7f372f6e`.
- **[3rd]**: a third-party source. Treat it as likely, not certain.
- **[unverified]**: from memory or general knowledge, not re-read here. Each one is a check to do before relying on it.
- **[device]**: only an emulator or Jelly Star run can settle it.

## 0. Summary

| | A. Stay on our own lock (today + design 57 + design 32 step 3) | B. Full switch: Android's lock screen is the lock face | C. Hybrid: Android's secure keyguard is the gate, our glance page is its face |
|---|---|---|---|
| Notification control | Full: per app off / sender / text (design 25, 51) | **Lost.** Only "none" or "redacted for apps that ask for it" for all apps (§3) | **Kept**: our glance, as today. Android's own lock-screen notifications are switched off |
| Timer, missed calls, torch, music on the lock face | Ours | Android's (media player yes; timer and missed calls only as notifications, which are off) | Ours |
| Security at rest (P-L5) | CE storage is not bound to any secret | CE bound to the PIN (Gatekeeper/TEE throttled) | Same as B |
| Over the lock (S-M4, lock flash, escapes) | Our activity has to win every race. Design 57 still to build | Platform rule: only `showWhenLocked` windows | Same as B |
| Before the first unlock (BFU, after a reboot) | Doesn't exist: CE unlocks by itself | No server reach, Locate or wipe until the PIN is typed. Fossify alarms are silent. Calls stay screened (§2) | Same as B |
| Parent code, PIN changes, lockout | Ours, offline | Android's lockout. A parent code needs the reset token (§4.5) | Same as B, plus our "Foreldrekode" through the token |
| Effort | Design 57 (two releases plus a usage-access grant per phone), design 32 step 3, their device rounds | Medium. Most of the lock goes, but the glance work is lost | Medium: a migration round, a deletion round, 1-2 device rounds |
| Code | Grows (design 57) | About -6 k lines incl. tests (estimate) | About -4 k lines in `lock/`, about -2.4 k test lines (§6) |
| Risk | The escape race goes on forever: Android internals on every version, as design 56 lists | Low technically, high in product terms (the notification control the user wants is gone) | Medium. The `showWhenLocked` face, re-fronting after a non-`showWhenLocked` launch, the bouncer's look and BFU, all [device] |
| **Recommendation** | No longer the best path | **No** | **Yes**: prototype behind a debug switch first, as design 32 step 2 did. **Hold design 57's build until this is decided** |

The decisive facts:
1. With a credential, AOSP keeps every activity that isn't `showWhenLocked` invisible while the keyguard is locked,
   occluded or not (`wm/KeyguardController.java:384-386`, `:394-407`). Design 32's flash, S-M4 (WhatsApp's arrow into
   the chats) and the shade/Recents escapes all become platform guarantees. Design 32 §7.4 used this rule for the
   insecure cover. With a PIN it holds without the cover.
2. A `showWhenLocked` top activity occludes even a secure keyguard (`wm/KeyguardController.java:736-746`). So our
   glance page can be the face, and only Android's PIN pad changes.
3. Android has no per-app lock-screen switch a device owner can reach (§3). Only our glance keeps design 51.
4. BFU is real but narrow. Calls are screened from device-protected storage today. What is lost is everything that
   needs the server or CE: Locate, wipe, sync, tsnet, Fossify's alarms (§2).

## 1. What a real PIN changes, mechanically

- **Today** (design 10): screen lock "None" (`setKeyguardDisabled(true)`, `PinLockRuntime.configure`). CE storage
  unlocks itself at boot (`UserController.java:2172`, `unlockUserKeyIfUnsecured`), so BFU doesn't exist.
  `PinLockActivity` is an ordinary activity that has to be on top at every wake. The re-front loop, the lock task
  while LOCKED, `DISALLOW_CREATE_WINDOWS`, the camera lock, the SOS guess, the dark lock-up and the keyguard cover all
  exist to make it win.
- **With an Android PIN**:
  - `setKeyguardDisabled(true)` returns false (`DPMS:15419-15422`), and `LPU.isLockScreenDisabled` is false whenever
    the user is secure (`LPU:767-770`). The keyguard is always there.
  - CE storage is encrypted with keys released only by the PIN, through the synthetic password, Gatekeeper and Weaver
    [unverified: the Jelly Star's TEE Gatekeeper/Weaver]. After every boot the phone is BFU until the PIN is typed.
  - `LOCK_TASK_FEATURE_KEYGUARD` must stay forced. Without it, lock task disables even a **secure** keyguard:
    - `wm/LockTaskController.java:919-963` acquires the system token;
    - `wm/KeyguardDisableHandler.java:101-106`: "The system can disable the keyguard for lock task mode even if the
      keyguard is secure".

    That was the original BFU deadlock (design 10 §2), and AFU it would bypass the PIN. Today the bit is forced on
    both sides (`LockTaskHelpers.kt`, `EnforcementPlan.kt`). A test must pin it before any credential is set.

## 2. Before the first unlock with a real PIN (question 1)

### 2.1 When BFU happens

Power-on, every reboot, a crash reboot, an empty battery, and the OS's own night-time update reboots
[unverified: whether the Jelly Star auto-reboots for OTAs]. Possibly also Play services' inactivity reboot after
three days locked [3rd/unverified]. It is **not** every screen-off: after the first unlock the phone is AFU until the
next boot, and CE stays readable while locked.

### 2.2 What runs before the first unlock

Only direct-boot-aware (DBA) components run, and they see only device-protected (DE) storage. Ours
(`AndroidManifest.xml:177`, `:295-350`) [code]:
- `KidCallScreeningService`, `KidCallRedirectionService`, `KidInCallService`, `InCallActivity`, `CallActionReceiver`
  and `BootCallReceiver`;
- `BootCoverActivity` (16b, its own process).

| Part | BFU with a PIN | Could it be DBA? | Cost / verdict |
|---|---|---|---|
| Incoming call screening | Runs. `CallPolicyStore` reads `boot_call_policy` and `boot_call_blocks` from DE (launcher `CLAUDE.md`, "Calls & SMS" / "Time rules"). Telecom queries in-call services with no direct-boot flags, so before unlock it finds only DBA ones, ours included (`Telecom InCallController.java:2433-2436`) [aosp]. Binding on a device: still open since task 15 [device] | Already | - |
| In-call UI | `InCallActivity` (DBA, `showWhenLocked`, `turnScreenOn`) over the keyguard. Avatar is the silhouette, as today before unlock (CE photos) | Already | - |
| Emergency calls | Android's bouncer has its own "Emergency call" button (the system emergency dialer). With lock task off before unlock, nothing restricts it | System | - |
| Outgoing calls | Only that emergency dialer (emergency numbers only). `DISALLOW_OUTGOING_CALLS` and `DISALLOW_SMS` are user restrictions the system keeps across reboots [unverified: persisted in DPMS policy files]. Suspended/hidden apps stay suspended (PackageManager state) [unverified] | - | - |
| Kiosk / lock task / time rules | Not running. Not needed: nothing behind the keyguard is reachable without the PIN, and our first start after the PIN starts them as today | No | Would bring back the BFU deadlock class (design 10 §2). Never |
| VPN filter (`KidVpnService`, not DBA, always-on without lockdown, `AppEnforcer.kt:1032-1083`) | Down. No app the kid could use runs BFU, so nothing is unfiltered for the kid. System/GMS traffic goes unfiltered, as with today's boot window | Possible: block list and upstream config in DE, the engine free of `LauncherPreferences` | **Not worth it**: medium work for no kid-facing gain |
| SSE, sync, status, commands | Down: the device token, server URL, policy cache and tsnet state are all CE | A narrow "BFU beacon" is possible (§2.4) | Medium-high |
| tsnet | Down (state dir in CE, node key) | Only by moving the node key to DE | **No**: the tailnet identity readable without the PIN |
| Location | No fix is taken (our code doesn't run) | Via the beacon | See §2.4 |
| Locate / ring / wipe from the PWA | **Impossible** until the PIN is typed. Today a thief's reboot changes nothing: CE unlocks, and we sync | Via the beacon (wipe BFU [unverified]) | The real loss |
| Power events (design 38) | The DE record of the start is written in direct boot already, and sent after the unlock | Already | - |
| Fossify Clock alarms | **Don't ring.** Fossify isn't DBA and re-arms on `BOOT_COMPLETED` only, which comes after the first unlock (FossifyOrg/Clock `main` `AndroidManifest.xml:7`, `:183`) [3rd: read 2026-10-21]. The system clock app (AOSP DeskClock, Google Clock) is DBA [unverified per phone] | Not ours | A real kid-facing regression after a night-time reboot. Question 5 |
| VoIP (Element X, WhatsApp) | No ring before the unlock if the app isn't DBA [unverified per app] | Not ours | Accept |

### 2.3 "Phone numbers can call freely": precisely

- **Incoming calls are not free.** They are screened by the same rules as at the last unlocked refresh:
  - the allowed numbers, calls on/off and the country code (`boot_call_policy`);
  - the no-calls windows (`boot_call_blocks`, missing = blocked).

  The differences from AFU:
  - lifts and the override are unknown, so a rule window blocks;
  - names aren't shown;
  - the copy is as old as the last unlocked refresh;
  - the callback window works only if our in-call service recorded the emergency call (DE `call_boot_state`).

  This is the same code that covers today's seconds between `LOCKED_BOOT_COMPLETED` and the automatic CE unlock.
  With a PIN that window just grows from seconds to "until the kid types the PIN".
- **Outgoing calls are not free.** The keyguard leaves only the emergency dialer.
- **The real "free" residual is the device check from task 15.** If Telecom doesn't bind our DBA screening/in-call
  services before unlock on the Jelly Star, an incoming call rings unscreened in the system in-call UI. That risk
  exists today in the boot window. A PIN makes it last longer, so the check becomes a precondition. It is quick:
  reboot, don't unlock, call from a blocked number (`02-calls.md`, "Direct boot").
- **What actually matters BFU** is not calls. It is: no server contact, no Locate, ring or wipe, no status, and
  Fossify's alarms (§2.2).

### 2.4 Making more of it DBA

- **A BFU beacon** (only if the user wants Locate/wipe after a reboot - question 1):
  - a DBA receiver on `LOCKED_BOOT_COMPLETED` posts "on, waiting for the PIN", a location fix and the battery, and
    fetches only `locate`/`ring`/`wipe` commands;
  - it uses a second, narrow device credential kept in DE and wrapped by a plain AndroidKeyStore key. Keystore
    super-encrypts only auth-bound or `UnlockedDeviceRequired` app keys (`keystore2/enforcements.rs:687-717`); a plain
    key works before the first unlock, while an auth-bound one answers `LOCKED` then (`keystore2/super_key.rs:675-693`);
  - it needs the server reachable without tsnet, i.e. a public https endpoint, which we don't have (S-M1);
  - new server endpoints and a scoped token;
  - cost: one design plus a round on both sides; risk: a DE credential and a second network path.

  Not recommended for the first step.
- **Alarms**: pick a DBA clock for the catalog, or accept (question 5).
- **Everything else**: no. More DE state is more readable without the PIN, which is exactly what we would be buying the
  PIN to stop.

## 3. Notifications on the real lock screen (question 2)

### 3.1 What a device owner can set

`setKeyguardDisabledFeatures` bits (`DPM:7273-7350`), all honoured only on a **secure** keyguard:

| Bit | Effect | Hybrid (C) | Full (B) |
|---|---|---|---|
| `KEYGUARD_DISABLE_SECURE_NOTIFICATIONS` | No notifications on the lock screen. SystemUI reads it per user (`SysUI/statusbar/NotificationLockscreenUserManagerImpl.java:507-519`) | **On** | The parent's only "off" |
| `KEYGUARD_DISABLE_UNREDACTED_NOTIFICATIONS` | Redacts only notifications that ask to be private: `needsRedaction` = channel private, sensitive, or (notification private **and** user redacting) (`NotificationLockscreenUserManagerImpl.java:658-694`). An app's `VISIBILITY_PUBLIC` notification still shows in full | n/a | Leaky |
| `KEYGUARD_DISABLE_TRUST_AGENTS` | No Smart Lock (trusted places, on-body) | **On** | **On** |
| `KEYGUARD_DISABLE_SECURE_CAMERA` | The camera gesture over a secure keyguard is refused (`SysUI/camera/CameraGestureHelper.kt:68-69`, `:155-165`) | **On** (already set today, `OwnPermissions.kt:66-76`) | **On** |
| `KEYGUARD_DISABLE_SHORTCUTS_ALL` | No lock-screen shortcuts (wallet, home controls, QR scanner, camera) | **On** | Parent's choice |
| `KEYGUARD_DISABLE_WIDGETS_ALL` | No glanceable hub (§5). The bit had no effect from 5.0 to 14 (`DPM:7276-7281`) | **On** | Parent's choice |
| `KEYGUARD_DISABLE_FINGERPRINT` / `_FACE` / `_BIOMETRICS` | Biometrics off | Parent's choice (question 4) | Same |

The user's own settings (`LOCK_SCREEN_SHOW_NOTIFICATIONS`, `LOCK_SCREEN_ALLOW_PRIVATE_NOTIFICATIONS`,
`MEDIA_CONTROLS_LOCK_SCREEN`) aren't on a device owner's `setSecureSetting` allowlist (`DPMS:716-723`: input method,
first-use hints, non-market apps, location mode). The kid can't reach Settings in the kiosk, and a window could
change them.

### 3.2 Per app: what a device owner can't do

- **Another app's lock-screen visibility.** That is a channel field, `lockscreenVisibility`:
  - only the owning app sets it at creation;
  - otherwise system/SystemUI/shell only (`NMS:4711-4716`, `updateNotificationChannelForPackage`);
  - or a **privileged** listener: one with a companion-device association, or the device's notification assistant
    (`NMS:6775-6787`, `verifyPrivilegedListener` `:6865-6880`).
- **Our listener** has neither. A device owner can't create a CDM association without the user's dialog
  [unverified].
- **Becoming the notification assistant** (adb at provisioning, like the listener) would technically allow per-channel
  `VISIBILITY_SECRET`/`PRIVATE`/`PUBLIC` for every app. That maps onto design 51's off / name only / full text. But:
  - it replaces the phone's own assistant (Android System Intelligence's smart replies, ranking, sensitive-content
    redaction);
  - it acts per channel, not per conversation;
  - apps can add new channels, which we would have to chase;
  - SystemUI still decides what "private" shows (app name, "Contents hidden").

  [unverified as a whole]. Not worth it next to our own glance.
- **The listener can cancel or snooze**. That also removes the notification from the shade after the unlock, so it
  isn't a lock-screen control.

### 3.3 Design 51 on Android's lock screen? No

| | Today / hybrid (our glance) | Android's lock screen (B) |
|---|---|---|
| Which apps show | Ticked apps only (`GlancePolicy.apps`) | All, or none |
| Per app "sender only" / "text" | Yes (design 51 `show_text`) | No. Only the app's own visibility choice, plus the global redaction bit |
| Missed calls with the contact's avatar | Yes (ours) | Our missed-call notification as one more notification, or nothing |
| Rows cleaned (bidi, length), memory only | Yes (`GlanceReader`, `LockGlanceGuardTest`) | SystemUI shows the app's own text |
| Opening a row after the code | Yes (`GlanceOpener`, only the app's own content intent) | Android's: tap -> bouncer -> the notification's own intent, any app's |
| The notice wake (round 7 §3) | Ours, gated | Android's "wake for notifications" / AOD, if the OEM offers it [unverified] |

So keeping notification control means keeping our glance. That rules out B.

## 4. The hybrid (question 3)

Android's secure keyguard is the gate: PIN, CE encryption, `showWhenLocked` VoIP for free. `PinLockActivity`, without
its keypad, stays the face: a `showWhenLocked` activity over the occluded keyguard, like `InCallActivity`.
`KEYGUARD_DISABLE_SECURE_NOTIFICATIONS` keeps Android's own rows off, so only our curated rows show. "Lås opp" calls
`KeyguardManager.requestDismissKeyguard`, and Android's bouncer asks for the PIN.

### 4.1 Can a third-party `showWhenLocked` activity be the lock's face?

- **Yes, by the rules.** Occlusion is decided by the top task's top activity: `canShowWhenLocked()` -> occluded,
  secure or not (`wm/KeyguardController.java:736-746`, "Only the top activity may control occluded").
  `PinLockActivity` is already `showWhenLocked` (`AndroidManifest.xml:165`). Background starts are allowed for us: a
  device owner and HOME (design 32 §3.7).
- **What can come over it**: only other `showWhenLocked` activities, which are exactly the lock-screen-safe ones:
  - our `InCallActivity`;
  - a VoIP app's call screen if it sets the flag (Element X does, in code: design 57 §1);
  - the emergency dialer, SOS, the alarm screen.

  Anything else that starts makes the keyguard un-occlude. The bouncer then guards it (`:394-407`): WhatsApp's arrow
  into the chats now asks for the PIN, by the platform.
- **`requestDismissKeyguard`** needs our activity `visibleIgnoringKeyguard` (`wm/KeyguardController.java:321-336`).
  On a secure keyguard it shows the bouncer. An insecure dismiss is allowed only without a credential (`:384-386`).

### 4.2 Re-front and lifecycle

- **No re-front loop.** It existed because anything could come over our lock. Now nothing unsafe can, so there is no
  race to win. The loop (`RefrontPolicy`), the SOS guess, the VoIP yields and the crash guard's "lock off" (S-L2) all
  go.
- **When the face isn't ours.** After a non-`showWhenLocked` launch, a call ending, a crash or BFU, SystemUI's own
  lock screen shows: the lock wallpaper, the clock, no notifications. That is a look issue, not a security one.
  - We put the glance back at the next `SCREEN_OFF` (started while dark, it occludes during the sleep, as design 32
    §7.4 found) and when our call or the emergency flow ends.
  - There is no clean public signal for "keyguard un-occluded". `KeyguardManager.addKeyguardLockedStateListener`
    needs a permission we likely can't hold [unverified]. So it is screen events plus our own activity lifecycle.
- **After the unlock.** The glance sees `isKeyguardLocked() == false` (or `USER_PRESENT`, or the dismiss callback's
  success), opens a row's pending target exactly as `GlanceOpener` does today, and leaves through Home. That is
  today's `lockLeave`.

### 4.3 Screen-on

- The glance is started at `SCREEN_OFF` if it isn't on top. The keyguard locks at sleep (the lock delay is 0 via
  `setMaximumTimeToLock` = the screen timeout, design 32 H1). With our activity on top it stays occluded.
- At the wake our glance resumes and draws. The gap before its first frame is black or the glance's black stand-in.
  No other app can be visible (§4.1). This is design 32's requirement, met without the cover experiment.
- If the press beats the start (within about 100 ms of the screen-off), SystemUI's lock screen shows for a moment.
  That is design 32 §7.4's residual, now harmless: no app, no notifications.

### 4.4 Boot

- BFU, `PinLockActivity` can't run: it isn't DBA and reads CE. **The first face after a boot is Android's own lock
  screen**: the lock wallpaper we already set (`ui/wallpaper/WallpaperApplier.kt:179-186`, `FLAG_LOCK`) [code] and
  the bouncer. After the PIN, CE unlocks, our process starts and the kid is in. The glance first appears at the
  next screen-off.
- The boot mark (16e), and Home's night ground while locked (16c), lose their purpose: a secure keyguard hides Home.
  The boot cover (16b) may still be worth its keep for the seconds after the PIN, when a DBA stock launcher can be HOME
  [device].

### 4.5 The parent code and the kid's PIN

Android has one credential. Our "Foreldrekode" can't unlock a secure keyguard: there is no API that dismisses it
without the credential. A trust agent needs a privileged permission [unverified]. Three ways:
- **(a) Through the reset token (recommended).**
  - The glance keeps "Foreldrekode": our keypad, `ParentPin.check` and its lockout, offline, as today.
  - On a match, `resetPasswordWithToken(null)` clears the credential. That works even with the keyguard up
    (`DPM:6457-6458`). Then `requestDismissKeyguard` dismisses the now-insecure keyguard (`:384-386`).
  - At once after that, `resetPasswordWithToken(kidPin)` sets the PIN back while unlocked.
  - The phone has no credential for about a second, while unlocked.
  - Needs: the token active (§9), and the kid PIN in plaintext on the phone, wrapped by a Keystore key in CE (§4.6).
- **(b) The parent knows the kid's PIN.** The parent sets it in the PWA, so the parent types it at the bouncer. A
  forgotten PIN means the PWA's "new kid PIN" (online, after the first unlock).
- **(c) Both.**

**Kid PIN changed by the parent** (PWA): the phone needs the new PIN in plaintext once, for
`resetPasswordWithToken(pin)`. Today the server keeps only a PBKDF2 hash (`server/src/kid_lock.rs`). Options:
- a one-shot command carrying the PIN over the tailnet + bearer, deleted on the receipt (command receipts L12 exist);
- or encrypted to a phone key (design 60's hardware key pair, ECIES), so no plaintext rests on the server while the
  phone is offline.

Question 3.

**The kid changing the PIN.** Settings' screen-lock page is blocked by the kiosk app block, but reachable in a parent
window (24b: "opens the phone completely"). `onPasswordChanged` (`<limit-password/>` is declared) tells us. Design
24b's relock step 1 today *removes* a credential set in a window. In C it would instead put the parent's PIN back,
which also needs the plaintext.

**BFU and a forgotten PIN** is the hard case. The token is in CE and our process isn't running, so nothing can reset
it remotely. Only (b), or a factory reset (recovery wipe, then FRP with the Google account) gets the phone back. That
is why (b) should be part of any choice: the parent always knows the current PIN.

### 4.6 Lockout and backoff

| | Ours today (`PinBackoff`) | Android |
|---|---|---|
| Free tries | 4, then 30 s/1/2/5/15 min | 5: AOSP's reference Gatekeeper waits 30 s after the 5th and 10th failure, after every one from the 11th, doubling every 10 from the 30th, 1 day from the 140th (`system/gatekeeper/gatekeeper.cpp:307-324`). The Jelly Star's TEE Gatekeeper/Weaver may differ [device] |
| Cap | 15 min a try (S-L6: weeks for a 4-digit PIN) | 1 day a try, enforced in the TEE, across reboots |
| Parent override | Parent code, offline | Only (a)'s token reset, which gives a fresh credential and likely a fresh failure record (`gatekeeper.cpp:96-101` at enroll) [device]. Or the parent typing the kid's PIN, subject to the same waits |
| Wipe after N | Never | Never unless `setMaximumFailedPasswordsForWipe` is set. Don't set it |

### 4.7 Lock task and `LOCK_TASK_FEATURE_KEYGUARD`

- **Keep lock task while LOCKED** as today (`LockTaskChrome.lockTaskWhileLocked`): kiosk on, the plan's features minus
  NOTIFICATIONS/HOME/OVERVIEW; kiosk off, our own lock task with KEYGUARD|GLOBAL_ACTIONS|SYSTEM_INFO.
  - Lock task's status-bar flags are global `StatusBarManager.disable` flags (`wm/LockTaskController.java:886-913`,
    `:999-1013`). So the shade and QS stay disabled over the occluded keyguard too [device: that SystemUI's keyguard
    shade honours them].
  - This is the cheapest guard against QS over the lock (§4.8).
- **KEYGUARD stays forced** (§1).
- The emergency exemption (`wm/LockTaskController.java:386-389`) keeps the bouncer's Emergency button working in lock
  task.

### 4.8 Quick Settings from the keyguard

- **`setStatusBarDisabled`**: AOSP `main` no longer refuses it on a secure phone (`DPMS:15443-15497` has no
  `isSecure` check). Its own documentation says it "does not have any effect while on the lock screen", and inside
  lock task it is only recorded (`DPM:13964-13977`). Either way it isn't the tool: lock task's flags are (§4.7).
- **Without lock task over the keyguard** (BFU, or if we dropped it): SystemUI's locked QS lets some tiles act without
  the PIN and asks for it on others; which ones is OEM-specific [unverified]. Airplane mode is the one that matters (it
  stops Locate). `DISALLOW_AIRPLANE_MODE` exists but is off by default (`Hardening.kt:30`, travel). `DISALLOW_CONFIG_WIFI`,
  `DISALLOW_BLUETOOTH`, `DISALLOW_CONFIG_LOCATION` and `DISALLOW_CONFIG_TETHERING` block their tiles wherever we set
  them [unverified per tile].
- BFU QS is reachable by whoever holds the phone. That is also true of every Android phone.

### 4.9 The camera gesture, Emergency SOS, fingerprint

- **Camera**: `KEYGUARD_DISABLE_SECURE_CAMERA` refuses the gesture while the keyguard is showing and secure
  (`CameraGestureHelper.kt:155-165`). "Showing" includes occluded [unverified]. `CameraLock`'s suspension at every
  lock/unlock (design 25b, 11b) can go. `setCameraDisabled` is no longer needed while locked.
- **Emergency SOS** (power ×5): the system's SOS app is `showWhenLocked` and comes over the glance. Keep it pinned in
  lock task (25b's `HelperKind.EMERGENCY_SOS`). The SOS *guess* (`sosGestureLikely`, the 15 s narrowing) goes, because
  nothing re-fronts against it.
- **Fingerprint for the kid**:
  - Enrollment is in Settings, so it happens in a parent window.
  - BFU and after `lockNow` the PIN is required (`lockNow` sets `STRONG_AUTH_REQUIRED_AFTER_DPM_LOCK_NOW`,
    `DPMS:6374-6375`), and after Android's idle timeout for biometrics (72 h) [unverified].
  - While our glance occludes the keyguard, a side or rear sensor isn't listened to. SystemUI listens while occluded
    only for UDFPS or an occluding app that asks (`SysUI/../keyguard/KeyguardUpdateMonitor.java:2958-2971`). It does
    listen with the screen off.
  - So "touch the sensor from dark" unlocks, and the glance leaves to Home. A press on the glance must go through "Lås
    opp" [device].
  - Parent's choice per phone (`KEYGUARD_DISABLE_FINGERPRINT`).

### 4.10 Remote lock

`dpm.lockNow()` now locks for real and requires the PIN, not a biometric (`DPMS:6374-6375`). `PinLockRuntime.lockNow`'s
own state goes.

`FLAG_EVICT_CREDENTIAL_ENCRYPTION_KEY` (a remote "back to BFU") is for a managed profile's owner only
(`DPMS:6350-6366`). A device owner can't evict the system user's CE key.

## 5. Music and the other tiles on the real lock screen (question 4)

- **Media controls**: phones on Android 14-16 show a MediaSession's player on the lock screen **by default**.
  - SystemUI places the player on the lock screen when `Settings.Secure.MEDIA_CONTROLS_LOCK_SCREEN` (default `true`)
    allows it (`SysUI/media/controls/ui/controller/MediaHierarchyManager.kt:125`, `:620-637`, `:1243`;
    `KeyguardMediaController.kt:197-210`).
  - The music app's Media3 session notification feeds it, with no work on our side.
  - A device owner can't switch it off (§3.1).
  - Whether `KEYGUARD_DISABLE_SECURE_NOTIFICATIONS` also hides the player: the placement code doesn't read it. The
    media data pipeline might filter [device].
  - In C the player shows only in the gaps (§4.2); our glance's tile stays the face.
- **Lock-screen widgets**: app widgets on the keyguard were removed in Android 5.0 (see the `DPM:7276-7281` comment).
  They come back only through the **glanceable hub** (communal):
  - AOSP ships it off: `config_glanceableHubEnabled` is `false` (`core/res/res/values/config.xml:2815`);
  - its first version "is targeted only to tablet" (`SysUI/communal/data/repository/CommunalSettingsRepository.kt:102-115`);
  - `KEYGUARD_DISABLE_WIDGETS_ALL` turns it off (`:142-153`, `:188-189`);
  - phones getting it in an Android 16 QPR (Pixel) [3rd/unverified]; the Jelly Star: unknown;
  - it is a separate panel the kid swipes to, not the lock's face.

  **Not a basis for the music tile.** An `AppWidgetProvider` in the music app would be a new surface for at most
  some phones.
- **Timer / stopwatch** (Fossify's notifications): Android shows them as ordinary notifications, so they are off with
  `SECURE_NOTIFICATIONS`. They remain on our glance only (`ClockReader`, design 42).
- **Missed calls**: a notification (ours, id 1006), so off on Android's lock screen. They remain on our glance.
- **Torch**: Android's lock-screen shortcut, if `KEYGUARD_DISABLE_SHORTCUTS_ALL` is off, or the QS tile. Our glance's
  button stays.
- **The next alarm**: SystemUI's keyguard clock area shows it on many builds [unverified]. Ours stays on the glance.

## 6. What we would delete or simplify (question 5)

`wc -l` on `main` `7f372f6e`. "Goes" = the whole file in C. "Shrinks" = an estimate of what goes from a file that
stays.

| Area | Files (main) | Lines | In C |
|---|---|---|---|
| Keyguard cover (design 32 step 2) | `lock/KeyguardCover.kt` 492, `KeyguardCoverPlan.kt` 290; debug `KeyguardCoverDebug(+Receiver)` 75 | 857 | Goes |
| Dark lock-up, Home under the lock (design 32 step 1) | `lock/LockUpPlan.kt` 128; debug `LockUpDebug(+Receiver)` 46; Home's `homeDarkResume`/`startWork`, `HomeFront`'s unanimated start | 174 + parts | Goes |
| Re-front loop, SOS guess, crash guard | `lock/RefrontPolicy.kt` 236; `PinLockRuntime` `noteProbableSos`/`narrowForSos`/`runRefrontCheck` (about 80) | 316 | Goes |
| Kid PIN keypad and backoff | `lock/PinBackoff.kt` 102, `PinKeypadLayout.kt` 91, `PinKeyHighlight.kt` 48; `PinLockStore.kt` 120 (shrinks to about 40) | 321 | Goes / shrinks |
| Camera lock (design 25b/11b) | `lock/CameraLock.kt` 247, `CameraLockPlan.kt` 132 | 379 | Goes (`KEYGUARD_DISABLE_SECURE_CAMERA`) |
| VoIP over the lock (designs 17/17b, r7 §1) | `lock/VoipCalls.kt` 416, `VoipCallPlan.kt` 448, `VoipRinger.kt` 101, `VoipWakeActivity.kt` 56, `badges/VoipCallReader.kt` 83, `res/layout/view_voip_ring.xml` 188 | 1 292 | Mostly goes. A small "VoIP call live" signal stays for the self-update gate, music's CALL_HOLD and screen time (12 files read `VoipCalls`): about -1 100 |
| Home's night ground while locked (16c) | `lock/HomeGate.kt` 45, `ui/home/NightGround.kt` 90 | 135 | Goes [device: Home is never visible under a secure keyguard] |
| Boot mark (16e) | `lock/BootMarkPlan.kt` 252 + `PinLockRuntime` 321-424 | about 355 | Probably goes |
| Lock runtime, activity, state | `PinLockRuntime.kt` 1 709, `PinLockActivity.kt` 893, `PinLockState.kt` 292, `LockTaskChrome.kt` 382 | 3 276 | Shrinks by about 1 400 (VoIP, lock-up, boot mark, PIN check, re-front; the glance, calls, notice wake and chrome stay) |
| Status-bar heal (16d) | `lock/StatusBarHealPlan.kt` 88 + triggers | 88+ | Keep at first: the lock-task flags still matter over the keyguard (§4.7) |
| **Main total** | | | **about -4 300 of `lock/`'s 9 435** (plus about 90 in `badges/`/`ui/`) |
| Tests | `KeyguardCoverPlanTest` 476, `LockUpPlanTest` 386, `LockUpTest` 221, `RefrontPolicyTest` 110, `SosYieldTest` 128, `PinBackoffTest` 110, `CameraLockTest` 94, `VoipCallPlanTest` 486, `VoipCallGuardTest` 105, `HomeGateTest` 253 | 2 369 | Go. Plus parts of `PinLockStateTest` 260, `PinLockLayoutTest` 365, `BootMarkPlanTest` 433 |
| **Not built, not needed** | Design 57 (usage access, `CallScreenWatch`, remembered call screens, two releases, a grant on every phone); design 32 step 3 (promoting the cover); their device rounds | - | Avoided |
| Server | `kid_lock.rs` (938 incl. tests): `voip_rang`/`voip_fsi_denied` notes, the kid PIN hash in the policy | - | Shrinks. A PIN-set command comes in (§4.5) |

**What stays**:
- the glance page (`LockGlance`, `GlancePanel`, `GlanceOpener`, `badges/Glance*`);
- the clock and music tiles, the torch, the notice wake;
- `InCallActivity` and the whole direct-boot call path;
- the parent PIN (`ParentPin`, vault, keypad);
- kiosk/lock task (`LockTaskChrome`, `LockTaskRoot`, `HomeFront`, `BootHome`, 16d);
- the update fence and the notification rule (design 11);
- `WindowRelock` (with step 1 inverted, §4.5).

## 7. What we gain in security (question 6)

- **P-L5, at rest.** CE storage is unreadable until the PIN after every boot. CE holds:
  - the device token, the cached policy and `pin_vault`;
  - the tsnet node key;
  - the reset token, which `WindowRelockStore` keeps in **plaintext** - against `DPM:6363-6369` ("NEVER store this
    token on device in plaintext"; P-L5 doesn't list it, so PLAN's Keystore item must);
  - every app's data, photos included (FBE).

  A thief's lab needs the PIN, rate-limited in the TEE (§4.6). The kid PIN's hash and salt leave the policy entirely:
  Android checks the PIN.

  Honest limits:
  - **AFU nothing changes**: CE keys stay in memory while locked.
  - A 4-digit PIN on a phone whose TEE can be exploited (cheap MediaTek) may be brute-forced offline [unverified for
    the Jelly Star].
  - Our background sync must run while locked, so the device token can't use an auth-bound or
    `UnlockedDeviceRequired` key. Its "binding to the PIN" is CE storage's, and only BFU. That matters for design 60:
    the hardware-bound token key stays a plain TEE/StrongBox key; the PIN adds BFU protection of its blob, not
    per-use auth.
- **S-M4 solved by the platform**: only `showWhenLocked` activities over the keyguard (§4.1). WhatsApp's arrow, a
  Decline that shows the chats, another allowed app started from the call: all hit the bouncer. Design 57 isn't
  needed.
- **Design 32's requirement** ("first frame = lock or black") holds by the same rule, without the cover experiment.
- **Fewer escapes**, each fail-closed by the platform instead of by our code winning a race:
  - the shade/QS/Recents over the lock (16d's status-bar desync becomes lower stakes);
  - the camera gesture;
  - other apps' full-screen intents and notification taps;
  - a crash of our process. Today the crash guard turns the lock **off** (S-L2). In C the keyguard stays;
  - safe mode: the keyguard still asks for the PIN, so a finder gains nothing. The kid, who knows the PIN, still
    escapes the kiosk, as today.
- **Remote lock** = `lockNow` for real (§4.10).
- **Platform features that need a screen lock start working**:
  - Theft Detection Lock and Remote Lock (GMS, design 56 §3.1);
  - bank/ID apps, wallets and passkeys that refuse a phone without a lock (design 10 §7's privacy text);
  - fingerprint.
- **Brute force**: S-L6 (a 15-min cap) is replaced by the TEE's schedule (§4.6).

## 8. What control we lose (C)

- **The PIN pad's look**: Android's bouncer, in the OEM's style, with an OK key for a 4-digit PIN. Auto-confirm needs
  6 digits or more (`LPU:127`, `MIN_AUTO_PIN_REQUIREMENT_LENGTH`). The kid PIN's 4-6 digits are within Android's
  minimum of 4 (`LPU:106`). A numeric PIN is stored as a PIN, so the bouncer shows a PIN pad (`DPMS:6014-6017`).
- **Lockout semantics** become Android's (§4.6). The parent code works only through the token (§4.5).
- **BFU**: no remote reach until the PIN (§2). A forgotten PIN after a reboot can't be reset remotely (§4.5).
- **The face is not always ours**: BFU and the gaps show SystemUI's lock screen (§4.2).
- **VoIP**: any allowed app whose call screen is `showWhenLocked` rings and talks over the lock as on any phone.
  There is no per-app "rings over the lock" set (S-M4's first fix option) - it isn't needed, because nothing beyond
  the call screen is reachable.
- **Fossify's alarms after a reboot** (§2.2).

## 9. Migration (question 7)

1. **The token.** `WindowRelock.ensureResetToken` sets it only when a window opens on a phone without a credential.
   For C it is set at every `apply()` while no credential exists: it activates at once with no credential
   (`DPM:6352-6355`) and survives later credential changes (`DPM:6360-6361`). An unactivated token is lost at reboot
   (`DPM:6357-6358`).
   - A field phone that already has a credential and no active token needs a confirm-credential, i.e. Settings, i.e.
     a parent window.
   - Wrap the token with a Keystore key (PLAN's Keystore item).
2. **The first Android PIN without a plaintext from the server**: the next correct kid PIN typed on **our** keypad is
   verified against the hash as today, and that same string goes to `resetPasswordWithToken(pin, token, 0)`.
   - The server sends only a per-phone switch (`kid_lock.keyguard: "android"`, missing = today) in the policy, pinned
     on both sides in one commit (`api-both-dirs`).
   - Then the keyguard is secure, `KeyguardCover` and `setKeyguardDisabled(true)` stop, and the features of §3.1 are
     set.
   - The kid sees nothing on that unlock. From the next screen-off: our glance, and "Lås opp" opens Android's PIN pad.
   - The PIN is also kept wrapped for §4.5 (a).
3. **Length**: 4-6 digits pass (`LPU:106`; no password quality or complexity is set, so `validateCredential` accepts it,
   `DPMS:6021-6031`). A server-side 6-digit default would get auto-confirm.
4. **What the kid sees**, in order:
   - one ordinary unlock;
   - then our glance as before, and Android's PIN pad after "Lås opp";
   - after a reboot, Android's own lock screen first;
   - fingerprint only if the parent enrolls one in a window.
5. **The server**:
   - the device page's Screen lock card and the design 10 privacy text are rewritten;
   - `lock_state.inactive = android_credential` becomes the normal state, not a warning;
   - the PIN-change command (§4.5);
   - remote lock's answer becomes "locked (Android)";
   - the BFU state is reported after the unlock: design 38's start record already says "turned on at", and "unlocked
     at" can join it.
6. **Rollback**:
   - the switch back to `own` -> `resetPasswordWithToken(null)` (the token is active) -> `setKeyguardDisabled(true)` ->
     today's lock;
   - the deletion of §6 waits one release after the last phone has migrated;
   - without an active token, the rollback is design 10's runbook (the parent clears the PIN in Settings, in a window).
7. **Order**:
   - (i) the BFU call device check (§2.3) and the Fossify decision;
   - (ii) a debug-switch prototype of C on the emulator: the glance as the face over a secure keyguard, the bouncer,
     the token-based parent code, the shade over the keyguard under lock task, the camera gesture, SOS, a VoIP call
     (Element X, WhatsApp);
   - (iii) the design note with QA;
   - (iv) the migration release;
   - (v) the Jelly Star;
   - (vi) the deletion round.

## 10. Recommendation (question 8)

| | A. Stay own lock | B. Full switch | C. Hybrid |
|---|---|---|---|
| **Control** (notifications, look, offline parent code) | Full | Poor: all-or-nothing notifications, no timer/missed-call tiles, the OEM's look | Full for notifications and tiles. The PIN pad is Android's. Parent code through the token |
| **Security** | Weakest at rest (P-L5). Over-the-lock safety depends on our code winning on every Android version (design 56 §3.1's internals list) | Strongest | Strongest (same gate as B) |
| **Effort** | Design 57's two releases + a grant per phone, design 32 step 3, recurring internals checks | Medium | Medium: prototype, migration, deletion, 1-2 device rounds |
| **Risk** | Ongoing: every new escape is ours to find | Low technically. High for the product | Medium, front-loaded: §4's [device] items. BFU reach and the alarms are accepted losses, not bugs |

**Recommendation: C, the hybrid, after a debug-switch prototype.** It keeps what the user named as important
(notification control, and the glance with its tiles) and hands the gate to the platform. That closes S-M4, the flash
and the escape class for good, and binds CE to the PIN. It deletes about 4 k lines of the lock and 2.4 k of tests
instead of adding design 57. **Pause design 57's build** (its prep release asks every phone for usage access) until
the user decides. Keep the Keystore item (PLAN), whichever way this goes.

**Questions for the user**:
1. **After a reboot the phone can't be located, rung or wiped until the kid types the PIN.** Is that acceptable?
   The alternative is a later "BFU beacon" (§2.4), which needs the server reachable outside the tailnet.
2. **The parent code**: keep "Foreldrekode" on the lock through the reset token (§4.5 a: the phone keeps the kid PIN
   wrapped, and clears and restores the Android PIN in about a second), or does the parent simply know the kid's PIN
   (b)? (b) is needed anyway for a forgotten PIN after a reboot.
3. **A new kid PIN from the PWA has to reach the phone once as the PIN itself.** Is a one-shot command over the
   tailnet, deleted on the receipt, enough? Or must it wait for design 60's phone key, encrypted? And is "the next
   correct unlock sets the Android PIN" right for the migration?
4. **Android's own PIN pad after "Lås opp"** (OEM look, OK key for 4 digits; 6 digits auto-confirm): acceptable? And
   fingerprint: allowed per phone (the parent enrolls in a window), or off?
5. **Alarms after a night-time reboot**: Fossify Clock isn't direct-boot-aware, so its alarm stays silent until the
   PIN. Should the catalog clock become a DBA one, or is that accepted?

## 11. Not verified here (device checks before relying on them)

- Telecom binding our DBA screening and in-call services before the first unlock on the Jelly Star (§2.3).
- That SystemUI's keyguard shade honours lock task's disable flags while occluded (§4.7).
- Whether `SECURE_NOTIFICATIONS` also hides the lock-screen media player (§5).
- Which locked-QS tiles act without the PIN on the Jelly Star (§4.8).
- The camera gesture's refusal while occluded (§4.9).
- Fingerprint from dark with the glance on top (§4.9).
- Android's real lockout schedule on the Jelly Star's TEE, and whether a token reset clears it (§4.6).
- Home never visible under the secure keyguard, which is what retiring 16c needs (§6).
- Whether Element X and WhatsApp set `showWhenLocked` on every call screen (Element X does in code, design 57 §1;
  WhatsApp's manifest has it only on `VoiceMessagingActivity`) and ring over a secure keyguard.
- Glanceable hub availability on phones (§5).
- Keystore keys usable before the first unlock for the beacon (§2.4: read in keystore2, not run).
- Persistence of user restrictions and suspensions across a reboot (§2.2: expected, not re-read).

Sources (read 2026-10-21):
- AOSP `main` via android.googlesource.com: the files named above, plus `system/gatekeeper/gatekeeper.cpp`,
  `system/security/keystore2/src/{enforcements,super_key}.rs`,
  `packages/services/Telecomm/.../InCallController.java` and `core/res/res/values/config.xml`.
- FossifyOrg/Clock `main` `app/src/main/AndroidManifest.xml` (GitHub).
- Our designs 10, 25, 32, 51, 56 and 57, `docs/review/security-review-2026-10-21-launcher.md` (P-L5, S-M4, S-L2,
  S-L6), and `launcher/CLAUDE.md`.
