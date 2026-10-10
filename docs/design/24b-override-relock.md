# 24b - Windows that lock again, a PIN vault, and safe mode blocked

This follows design 24 and is built by the same implementer after 24's step 1. Its inputs are
`docs/review/security-review-2026-10-10.md` (M9, L6, H4, "Status and decisions") and the user's decisions of
2026-10-10:

- **M9:** the parent PIN may still open the phone fully for a limited window. The phone then locks down again by
  itself on a local timer, the parent gets a clear "Lock again", and the PWA can end a pause or an override. What
  the kid changed meanwhile must not survive the relock.
- **L6:** keep only a Keystore-backed HMAC of the PIN hashes on the phone.
- **H4:** `DISALLOW_SAFE_BOOT` is on by default, but only after device check 6 passes on the Jelly Star.

[AOSP] marks something read in `android14/15/16-release` for this note. [device] marks what only the phone can
answer.

## 1. M9: windows that close themselves

### 1.1 Today (verified at 99d88c61)

There are three windows. They open different things:

| Window | Started by (design 24: inside `ParentPin.check`) | What opens | How it ends today |
|---|---|---|---|
| override | time-lock screen, 2 h | `appsManaged = false`: nothing hidden or suspended (Android Settings and browsers included); kiosk off; VPN filter off; sideloading allowed (`applySideloadRestriction`); `DISALLOW_CREATE_WINDOWS` and the screen timeout lifted | the next **successful** sync (`OfflineOverride.clear()`), or the 2 h check |
| pause | Settings, 2 h | the same | its 2 h check, or the Settings switch |
| install mode | Settings, 15 min | only the Play Store, unsuspended and pinned; kiosk stays, so Android Settings can't be reached | the boundary alarm, "End now", a reboot, or a time lock |

- **Nothing guarantees a re-apply when an override or pause expires.** `isActive()` self-clears only when something
  asks. The time-rule alarm wakes at the window's end only when a time policy exists (`nextBoundary`), and
  `reevaluateLockReasonFromCache` applies only if the lock reason or key changed. A window that ends with no lock
  in force stays open until the next sync pass (up to the 30/15 min backstop).
  - With airplane mode, that sync pass still runs `apply()` from the cache, so the phone does close within about
    15 min after the window.
  - Everything changed in Android Settings meanwhile stays.
- **Kept during every window** (hardening, never lifted):
  - `FACTORY_RESET`, `ADD_USER`, `MODIFY_ACCOUNTS`, `CONFIG_VPN`, `USB_FILE_TRANSFER`, `DEBUGGING_FEATURES`,
    `CONFIG_LOCATION`, `SET_WALLPAPER`, `CONFIG_LOCALE`;
  - `CONFIG_PRIVATE_DNS`, the call rules, and date/time while calls are managed or rules exist.
  - [AOSP] DPMS sets `DISALLOW_ADD_PRIVATE_PROFILE`, `_CLONE_PROFILE` and `_MANAGED_PROFILE` on a device-owner
    phone, so private space and work profiles are not a surface.
- **A new hole that breaks the window's rules.** `EnforcementPlan.neverRestrict` includes `inputMethodPackages()`,
  every *enabled* keyboard. So a keyboard sideloaded and enabled during a window is never hidden afterwards, and
  it sees the parent PIN typed later. §1.5 closes this.

### 1.2 The relock engine (`server/WindowRelock.kt`, pure rules in `WindowRelockPlan.kt`)

- **One marker.** Each starter (`OfflineOverride.activate`, `RestrictionsPause.start`, `PlayRuntime.startInstallMode`;
  since design 24 they are called only from `ParentPin.check`) first calls `WindowRelock.opened(context, kind,
  start)`. That commits CE prefs `window_relock` with:
  - `pending = true`, `kind` and `until`;
  - `device_secure_at_start` (`KeyguardManager.isDeviceSecure`).

  It then arms the timer and posts the notification (§1.3). The marker lives until a relock has finished.
- **`WindowRelock.check(context, trigger)`** is synchronized and idempotent. It relocks when `pending` is set and
  `OfflineOverride.isActive()`, `RestrictionsPause.isActive()` and `PlayRuntime.installModeActive()` are all false.
  It is called from:
  - the timer: `setExactAndAllowWhileIdle(ELAPSED_REALTIME_WAKEUP, …)` at the earlier of the elapsed and wall-clock
    ends (`USE_EXACT_ALARM` is already held), re-armed at every process start while a window is open;
  - `Application.initRest` (a reboot ends every window, through `timedWindowActive`'s boot count);
  - screen on / `USER_PRESENT` (`PinLockRuntime`'s receiver);
  - the end of every `performMdmSync`, after `apply()` (a successful sync has just cleared the override);
  - "Lock again", the Settings pause switch turned off, and the PWA command (§1.3, §1.4). These first end every
    window.
- **What a relock does,** on an IO coroutine, under `WindowRelock`'s lock:
  1. `AppEnforcer.apply(currentPolicyDecision().policy)`, which brings back kiosk, hiding, the VPN, sideload blocking
     and the rest;
  2. the surface reset (§1.5);
  3. `reevaluateLockReasonFromCache`, then `LockActivity` if a lock is due, `TimeRuleAlarm.schedule`, and
     `HomeFront.bring` unless a call is live (as `onInstallModeEnded` does);
  4. cancel the notification, and store the relock report (§1.6);
  5. clear `pending`, only after steps 1-4. A process death midway re-runs everything at the next trigger.
- **Install mode** uses the same path (its alarm, "End now" and its end in `apply()` all call `check`). Its own
  notification merges into §1.3's.

### 1.3 "Lock again" on the phone

- **A notification:** channel `launcher:window` (LOW importance, silent), ongoing, one per open window kind, with a
  "Lock again" action (`WindowRelockReceiver`, not exported, `PendingIntent.FLAG_IMMUTABLE`).
  - [AOSP 14] `NotificationManagerService.canBeNonDismissible` keeps `FLAG_NO_DISMISS` for an active device owner,
    so the kid can't swipe it away.
  - During a window the kid *can* turn off our notifications in Android Settings. §1.6 then reports it, and the Home
    banner stays.
- **A Home banner:** `HomeActivity` shows a strip while any window is open: "Open until 18:40 · Lock again". It is our
  own UI, so it can't be dismissed or switched off. The PIN lock and the time lock keep their usual places above it.
- **No PIN.** "Lock again" only takes access away, so the kid may tap it too.
- **Texts (en / nb):**
  - override: "Open with the parent code until %1$s" / "Åpnet med foreldrekoden til %1$s";
  - pause: "All restrictions paused until %1$s" / "Alle begrensninger er satt på pause til %1$s";
  - install mode: today's text;
  - the action: "Lock again" / "Lås igjen".

### 1.4 The PWA command `end_windows`

- **Server:**
  - `POST /devices/{id}/end-windows` (`admin_routes`, behind `require_full_auth`) calls `queue_command(…,
    "end_windows")` and records the security event `device_command_queued`.
  - The device-page Status card shows a "Lock the phone again now" button when the latest status shows
    `restrictions_paused`, or an `install_mode.until_ms` in the future, and its capabilities include
    `window_relock_v1`.
  - Subtitle: "Ends the pause or Play install mode as soon as the phone gets this (usually within seconds). A
    parent-code opening on the time-lock screen already ends whenever the phone reaches this server."
- **Phone:** `dispatchPendingCommand` gets `"end_windows" -> WindowRelock.lockAgain(context, "command")`. The result
  is `"ended: pause, install mode"` or `"nothing was open"`.
  - The override needs no command: the sync that delivers the command has already cleared it.
  - An older launcher logs "Unknown pending command" and never answers. The capability gate hides the button for it.

### 1.5 What the kid could change during a window

These are the surfaces Android Settings opens during an override or a pause; install mode opens none of them.
"Prevent" means a device-owner setting that stays on while managed and is **never lifted by a window**: launcher-only
like `SET_WALLPAPER`, with no server switch (§8 Q1). The opening stays full for apps, Settings, kiosk and the
filter, as the user decided.

| Surface | Prevent | Reset at relock | Report (§1.6) |
|---|---|---|---|
| Wi-Fi proxy (manual or PAC, per network) | no: `DISALLOW_CONFIG_WIFI` would also block the kid's Wi-Fi screen | for every `WifiManager.getConfiguredNetworks()` entry with a proxy: `setHttpProxy(null)` + `updateNetwork`. [AOSP] `WifiConfigManager.canModifyNetwork`/`canModifyProxySettings` allow the device owner. This needs `ACCESS_FINE_LOCATION`, which we hold, and location on (`CONFIG_LOCATION`). | `proxies_cleared`, and `proxy_left` if `ConnectivityManager.getDefaultProxy()` is still set |
| Global proxy | needs adb or a device owner, so not reachable | `setRecommendedGlobalProxy(admin, null)`, only if one is set | `global_proxy_cleared` |
| User CA certificates | **`DISALLOW_CONFIG_CREDENTIALS`**. Installing one also needs an Android screen lock first. | `uninstallAllUserCaCerts(admin)` | `ca_certs_removed` (the count from `getInstalledCaCerts` before) |
| Android screen lock (the kid sets a PIN: handy's lock goes inactive, and the parent and the kid's lock are locked out) | no device-owner API for that | if `device_secure_at_start == false` and the phone is secure now: `resetPasswordWithToken(admin, null, token, 0)`. The token (32 random bytes, CE prefs, `commit()`) is set with `setResetPasswordToken` on a managed apply while the phone has no credential, which activates it at once. A credential that was there before the window stays. [device] | `credential_removed`, `credential_left` (`lock_state.inactive = android_credential` stays as today) |
| Accessibility services | **`setPermittedAccessibilityServices(admin, allowlist)`** while managed (system services are always allowed) | [AOSP] a hidden package's services leave `ENABLED_ACCESSIBILITY_SERVICES` (`AccessibilityManagerService.onPackageRemoved`; that hiding broadcasts the removal is [device]). An allowlisted app's service can't be switched off by the device owner. | `accessibility_enabled`: non-system services still on |
| Input methods | **`setPermittedInputMethods(admin, allowlist)`** while managed (system keyboards are always allowed). `inputMethods` in `neverRestrict` becomes *enabled ∩ (system ∪ allowlist)*, so a sideloaded keyboard is hidden. If no system keyboard is enabled, the current default is kept (the PIN dialogs need one) and reported. | if the default keyboard isn't permitted: `setSecureSetting(DEFAULT_INPUT_METHOD, <first enabled system IME>)` ([AOSP]: on the device-owner allowlist), then `apply()` hides it | `input_methods_kept` |
| Hotspot, USB and Bluetooth tethering | **`DISALLOW_CONFIG_TETHERING`** | [AOSP] setting it calls `untetherAll()` (`Tethering.UserRestrictionActionListener`) | - |
| Device admin apps | - | a non-allowlisted admin's package is hidden, and DPMS drops a removed package's admin [device] | `admins_enabled`: active admins other than ours |
| Sideloaded apps | sideloading stays lifted in the window | hidden by `apply()` (unchanged) | the existing `app_installed` security-log line |
| Our own notifications (calls, the window notification) | - | no app API can raise a channel the user lowered | `own_notifications_off` |
| Wi-Fi networks, Bluetooth pairings, app permissions of allowed apps, system accessibility services (TalkBack) | - | kept (legitimate, or system) | `accessibility_enabled` lists system services too |

**Order** (`WindowRelockPlan`, pure): the restrictions and permitted lists are set inside `apply()`, so they are in
place before any surface reset. Then come proxy, global proxy, CA, credential, keyboard default, and the report. A
step that throws is noted in `failures` and the rest still runs.

[AOSP 14-16] `setPermittedAccessibilityServices` and `setPermittedInputMethods` return false while an already-enabled
non-system service or keyboard is outside the list (`checkPackagesInPermittedListOrSystem`). `apply()` therefore
sets them *after* hiding, keeps the last list that succeeded, and reports a failure. They never throw away the
current state.

### 1.6 The relock report (status, API change)

- **Launcher:** `StatusReportRequest.relock: RelockReport? = null`, the last relock's findings, sent in every report
  until a new relock replaces it (a snapshot, no queue):

  ```json
  "relock": { "at_ms": 1760100000000, "trigger": "timer", "window": "override",
              "proxies_cleared": 1, "proxy_left": false, "global_proxy_cleared": false, "ca_certs_removed": 0,
              "credential_removed": false, "credential_left": false, "accessibility_enabled": ["com.example.app"],
              "input_methods_kept": [], "admins_enabled": [], "own_notifications_off": false, "failures": [] }
  ```

  - `trigger`: `timer | lock_again | command | sync | boot | screen_on | settings`.
  - `window`: `override | pause | install_mode`.
  - Lists are package names only, at most 8 each.
  - `failures` takes names from the fixed step set.
- **Server:**
  - `StatusReportRequest.relock: Option<serde_json::Value>` goes through `kiosk_escapes`-style
    `sanitize_relock` (known fields, valid package names, caps) into `device_status.relock_json` (a migration).
  - **Device page**, Status card, when `at_ms` is within 7 days and anything was found or left: "When the phone
    locked again at {time} ({trigger}), it removed {n} Wi-Fi proxy(ies), {n} certificate(s) and a screen lock the
    child had set. Still on: accessibility service {pkg} (an allowed app); the launcher's notifications are off."
    Only the parts that apply are shown.
  - **Security log:** `phone_relock_findings` when `at_ms` differs from the previous report and something was found.
- **Capability:** `window_relock_v1`.

## 2. L6: a PIN vault on the phone

- **Today.** `override_pin_hash` and `kid_lock.pin_hash` (PBKDF2-SHA256, 210k rounds) sit in plain CE storage, in
  three places:
  - mdm prefs `override_pin_hash`;
  - `pin_lock_state`'s `pin_hash`;
  - the cached policy blob `kid_mode_policy`.

  On top of that, the unkeyed fingerprints (`KidLockConfig.fingerprint`, design 24's `pin_fp` = SHA-256 of
  salt:hash) are offline oracles too.
- **Design (`server/PinVault.kt`, pure core `PinVaultCore` with an injected `Mac`):**
  - **The key.** A Keystore HMAC-SHA256 key `pin_vault_v1`: non-exportable, StrongBox when
    `FEATURE_STRONGBOX_KEYSTORE`, else TEE, no user authentication (handy's phones have no Android credential).
  - **`accept(fresh): PolicyResponse`** is the only reader of the server's hashes, called in `performMdmSync` before
    `storeAcceptedPolicy`.
    - It stores `tag = HMAC(K, pbkdf2HashBytes)` with the salt (and the kid PIN length).
    - It returns the policy **redacted**: `override_pin_hash = ""`, `kid_lock.pin_hash = ""`, salts and lengths kept.
    - Only the redacted policy is cached or passed on. A `null` hash (no PIN) clears its tag.
  - **Verify:** `HMAC(K, PBKDF2(pin, salt))` compared with `MessageDigest.isEqual`. The cost stays at ~0.5 s of
    PBKDF2.
  - **Fingerprints** become the first 16 hex characters of the tag. Design 24's `pin_fp` and `PinLockStore`'s
    `HASH_FP` use them, so a new PIN still resets the lockout and backoff.
  - **Readers:**
    - `ParentPin.check` calls `PinVault.verifyParent`. 24's `ParentPinCallersTest` rule becomes: `PinHash.verify(`
      only in `PinVault.kt`.
    - `PinLockRuntime.checkPin` calls `PinVault.verifyKid`.
    - `PinLockRuntime.configure` takes "lock on" from `policy.kidLock != null` and the PIN from the vault, never from
      `kidLock.pinHash`.
    - `OfflineOverride.isConfigured` reads the vault.
- **Migration** (first start of the new build, idempotent, each step committed):
  1. Create the key.
  2. Wrap the plain hashes from mdm prefs and `pin_lock_state` into tags.
  3. Re-encode the cached blob redacted.
  4. Delete the plain fields.

  Flash and FBE give no guarantee the old bytes are gone from the disk; the next PIN change makes them stale anyway.
- **Key failure** (`UnrecoverableKeyException`, a missing alias; one retry for transient Keystore errors):
  - `PinVault` marks `rewrap_needed`. The parent PIN answers `Unavailable` ("The code can't be checked until the
    phone reaches the server").
  - The kid PIN answers `PinResult.Unusable`, so only the parent code would open the lock, which is also unavailable.
  - The next successful sync (it runs under the lock) deletes the key, makes a new one and re-wraps both hashes from
    the fresh policy.
  - Offline, the phone keeps calls and the emergency button until then. This fails closed, never for good, and is
    rare. It's a device check (§7) and §8 Q3.
- **No contract change.** The server still sends the hashes, so `policy_json_keys_snapshot` doesn't change. A new
  `PolicyResponseCompatTest` case: a redacted cached blob decodes, and with the vault's tags the kid lock stays on
  and the parent PIN stays configured.
- **A longer parent PIN: recommend against requiring one** (the PIN is the parents' choice).
  - With the vault, the phone no longer allows offline cracking: every guess needs this phone's Keystore and 0.5 s
    of PBKDF2.
  - What remains is the server's own copy (DB and backups, review M2), where 6 digits fall quickly to a GPU.
  - So the PWA label from design 24 adds advice only: "8 or more digits are much harder to crack if the server is
    ever copied." No refusal.

## 3. H4: safe mode blocked by default

- **Gate.** The default flip is its own step (S4). It merges only after device check 6 in `docs/design/04-hardening.md`
  is ticked there, with the date and the build, on the Jelly Star. That needs adb allowed, using today's per-device
  "Block safe mode" switch. Until then nothing changes the default. The boot report (S3) ships before it.
- **The flip (S4):**
  - **Launcher:** `HardeningRestriction.SAFE_BOOT(true)`. It only matters when a server omits the switch, since
    today's server always sends it.
  - **Server, new devices:** `insert_device_with_policy` inserts `disallow_safe_boot = 1` explicitly. SQLite can't
    change the column default.
  - **Server, existing devices** (a migration):

    ```sql
    INSERT INTO security_events (event_type, detail)
      SELECT 'safe_boot_blocked_by_default', 'device ' || device_id FROM device_policy WHERE disallow_safe_boot = 0;
    UPDATE device_policy SET disallow_safe_boot = 1;
    ```

    Every device is flipped, because an explicit "off" can't be told apart from the old default (§8 Q4). The
    security log names each one.
  - **The hardening card:** "Block safe mode (on by default): safe mode starts the phone without the launcher, so no
    app, call or time rule applies there."
  - The first kid PIN's `saved_safe_boot` path stays (now a no-op).
  - The policy keys are unchanged.
- **Boots the launcher didn't see (S3, API change).**
  - `Application.onCreate` runs at every process start; `LOCKED_BOOT_COMPLETED` starts us in direct boot. It reads
    `BootClock.bootCount()` and compares it with DE prefs `boot_witness.last_boot`.
  - A jump greater than 1 adds `jump - 1` to the monotonic counter `unseen_count` and sets `last_at_ms = now`. Then
    `last_boot` is stored (`commit()`). An unknown count (-1) is skipped.
  - In safe mode our app isn't run, but system_server still counts the boot [device: check 6 confirms +1].
  - Status: `StatusReportRequest.unseenBoots: UnseenBootsReport? = null` -> `"unseen_boots": {"count": 1,
    "last_at_ms": 1760100000000}`. The server stores `device_status.unseen_boots_count` and
    `unseen_boots_last_at_ms`.
  - **Security log:** `boot_unseen` when the count rose since the previous report.
  - **Device page** (Status card, while `last_at_ms` is within 30 days): "The phone started {n} time(s) without the
    launcher running, last noticed {when}. In safe mode the launcher doesn't run, so no app, call or time rule
    applies." It continues with either:
    - "Turn on "Block safe mode" under Phone hardening."; or
    - "Safe mode is blocked, so this was probably a start cut short (battery) - ask your child if it repeats."

## 4. Contract fields (both directories in the same commit as their step)

| Step | Launcher | Server | Pinned by |
|---|---|---|---|
| S1b | `StatusReportRequest.relock` (`RelockReport`), capability `window_relock_v1`; `pending_command.command = "end_windows"` handled | `StatusReportRequest.relock`, `device_status.relock_json`, `POST /devices/{id}/end-windows` | `PolicyResponseCompatTest` "relock is reported under the server's keys" (exact literal of §1.6) and "an end_windows command decodes"; server `src/tests/window_relock.rs` posts the same literal |
| S2 | none (the vault redacts the cache) | none | `PolicyResponseCompatTest` "a redacted cache keeps the lock and the parent PIN" |
| S3 | `StatusReportRequest.unseenBoots` | `unseen_boots_count`, `unseen_boots_last_at_ms` | `PolicyResponseCompatTest` "unseen_boots is reported under the server's keys"; server `src/tests/hardening.rs` |
| S4 | `SAFE_BOOT(true)` | new devices and the migration | `HardeningTest`; server `a_new_device_starts_with_all_kid_settings_on` asserts `disallow_safe_boot = 1`; a migration test |

`policy_json_keys_snapshot` and the policy half of `PolicyResponseCompatTest` must not change in any step. Next to
these are 19b's `battery_optimization_exempt` and 24's `pin-events`/`parent_pin_events_v1`. Migrations take the
next free numbers.

## 5. Steps (after design 24 step 1; each green on its own)

1. **S1a, launcher:**
   - `WindowRelock` and its plan, the marker in the three starters, the timer and triggers;
   - "Lock again" (the notification and the Home banner, with install mode's notification merged in);
   - the prevent rows (`CONFIG_TETHERING` and `CONFIG_CREDENTIALS` as launcher-only `HardeningRestriction`s, plus the
     permitted accessibility and keyboard lists);
   - the IME fix in `neverRestrict`, and the resets;
   - the report recorded but not sent yet.
2. **S1b, both directories:** the `relock` status, `end_windows` (route, button, dispatch) and `window_relock_v1`.
3. **S2, launcher:** `PinVault`, its migration, and 24's `ParentPin` and `PinLockRuntime` moved onto it.
4. **S3, both directories:** the boot witness, `unseen_boots`, and the device-page warning.
5. **S4, both directories:** the safe-mode default flip, **only after check 6 passes**.
6. **Docs:** both `CLAUDE.md`s, design 04 (check 6 result, new default), PLAN, and the review's status table.

## 6. Tests

- **Launcher `WindowRelockPlanTest` (pure):**
  - relock is due only with `pending` set and no window active;
  - the step order;
  - a step failure doesn't stop the rest;
  - credential removal only when the phone wasn't secure at the window's start;
  - the IME rule: enabled ∩ (system ∪ allowlist), with the last keyboard kept when no system keyboard exists;
  - the timer time is the earlier of the two clocks.
- **Launcher `HardeningTest`:**
  - `CONFIG_TETHERING` and `CONFIG_CREDENTIALS` are on while managed, off unmanaged, and never lifted by an override;
  - after S4, `SAFE_BOOT` defaults on.
- **Launcher `EnforcementPlanTest`:**
  - under an override, apps, kiosk and Settings open as today (the user's M9 decision);
  - a sideloaded enabled keyboard is hidden;
  - a system keyboard and an allowlisted keyboard are not.
- **Launcher `WindowRelockCallersTest`** (source scan): every starter calls `WindowRelock.opened` before changing
  anything.
- **Launcher `PinVaultTest`** (JVM `Mac`):
  - wrap and verify;
  - redaction keeps `kid_lock` and the salts;
  - a `null` hash clears its tag;
  - a new hash changes the fingerprint;
  - migration: the plain fields gone, the tags present, the blob redacted, and a re-run is a no-op;
  - a key failure gives `Unavailable` and `rewrap_needed`.
- **Launcher `BootWitnessTest`:** a jump of 1 counts nothing, a jump of 3 counts 2, -1 is skipped, and the counter
  never decreases.
- **Launcher `PolicyResponseCompatTest`:** the rows of §4.
- **Server:**
  - **`window_relock::tests`:** sanitizing (unknown fields dropped, invalid packages dropped, caps) and every
    warning text.
  - **`src/tests/window_relock.rs`:**
    - the literal is stored;
    - the warning appears and expires after 7 days;
    - the security event fires on a new `at_ms` only;
    - `end-windows` queues the command and logs it;
    - the button follows the status and the capability.
  - **`src/tests/hardening.rs`:** `unseen_boots` stored, warned and logged, and both warning variants. After S4: new
    and migrated devices block safe mode, with one security event per flipped device.

## 7. Device checks (emulator, then the Jelly Star)

- **An override with the screen off and airplane mode on.** At the window's end the phone locks again within a
  minute, without a sync:
  - kiosk is on, apps are hidden and the VPN is back;
  - `dumpsys deviceidle`/`alarm` shows the exact alarm.
- **"Lock again"** from the notification, from the Home banner, and with the kid's notifications for the launcher
  turned off in Android Settings (the banner still works, and the report says `own_notifications_off`). The
  notification can't be swiped away.
- **PWA:** "Lock the phone again now" during a pause and during install mode ends them within seconds, and the
  result shows.
- **Surfaces, each during an override, then relock:**
  - a Wi-Fi proxy is cleared;
  - a CA certificate can't be installed (`no_config_credentials`), and one installed before 24b is removed;
  - an Android screen PIN set in the window is removed, and handy's lock is active again;
  - a sideloaded keyboard and accessibility service can't be enabled ("blocked by admin"), and an already-enabled
    one is gone after the relock;
  - hotspot can't start;
  - a sideloaded device admin is gone;
  - the report on the device page matches.
- **A reboot during a window:** after the boot it relocks once, with trigger `boot`.
- **PIN vault:**
  - the logcat line gives `KeyInfo.securityLevel` (StrongBox, TEE or software) on the Jelly Star;
  - after the upgrade, the prefs files hold no `pin_hash` (`run-as` on the debug build);
  - the kid PIN and the parent PIN still work offline;
  - deleting the key (debug-only hook) gives "can't be checked until…", and the next sync recovers.
- **Check 6 (design 04):**
  - safe mode is refused with `no_safe_boot`;
  - with the switch off, a safe-mode boot followed by a normal boot raises `unseen_boots.count` by 1 and shows the
    warning (this confirms `BOOT_COUNT` counts safe-mode boots).
- **After S4:** a new device and a migrated device both list `no_safe_boot` (`dumpsys user`).

## 8. Open questions (with recommendations)

1. **The never-lifted additions** (`CONFIG_TETHERING`, `CONFIG_CREDENTIALS`, and the accessibility and keyboard
   lists): launcher-only, or server switches?
   - **Recommend launcher-only now.** None of them is part of the parent's emergency use of a window. A switch can
     come if a parent asks (hotspot, an EAP Wi-Fi certificate).
   - **The cost:** a keyboard app the parent installed but didn't allowlist gets hidden, and the system keyboard
     takes over. The device page's app list shows it, and allowlisting brings it back.
2. **Removing an Android screen lock the kid set during a window.** **Recommend yes**, through the reset-password
   token. Only a lock that wasn't there when the window opened is removed, so a parent's own choice is kept. If the
   token can't be activated on the Jelly Star [device], fall back to report-only.
3. **The vault key fails.** **Recommend fail closed** for both PINs until the next sync re-wraps (rare; recovery is
   automatic once online). The alternative, the kid lock failing open, would undo its privacy purpose.
4. **The H4 migration flips every device**, including one a parent had explicitly set to off. **Recommend yes:**
   there's one install, the security log names each device, and the switch is one tap.
5. **Make the override a time-lock-only lift** (the review's original proposal)? **No:** the user decided to keep the
   full opening, and §1.5 makes it safe to close.

Not in scope: changing what a window opens, a server switch for each new restriction, notification-channel repair,
special app access (overlay, usage access) for allowed apps, and the server-side hash exposure (review M2).

## QA review (design)

QA, 2026-10-10, against bb8ba7dc (design 24's gate as on `pin-24`). Read:
- this design, design 24, the security review (M9, L6, H4 and the decisions), and `launcher/CLAUDE.md`;
- launcher: `OfflineOverride`, `RestrictionsPause`/`BootClock`, `EnforcementPlan`, `AppEnforcer` (`apply`,
  `inputMethodPackages`, the VPN, sideloading, hardening), `Hardening`, `PlayRuntime`, `TimeRuleAlarm`,
  `TimeRulesRuntime.nextBoundary`, `PinLockStore`/`KidLockConfig`, `PinLockRuntime.configure`/`checkPin`,
  `lockActivation`, `LocateCommands`, `MdmSyncWorker`, `ParentPin` (`pin-24`), and the two PIN dialog layouts;
- server: `locate.rs::queue_command`, `admin_auth::csrf_guard`, the Status card in `device_detail.html`, and
  `scroll-restore.js`.

**Verdict:** the engine has the right shape: one marker, an idempotent check, the reset after `apply()`, a snapshot
report, and a block where no reset works. §1.1 holds against the code.
- One High: sideloading stays open in windows, and an app sideloaded under an allowlisted name survives the relock,
  even as a permitted keyboard.
- The Mediums:
  - the threads `check` and `opened()` block;
  - a proxy reset that fails silently;
  - calls-only phones;
  - the parent PIN's dependence on a keyboard;
  - two vault problems.
- S1-S4 take install mode and the second alarm out of the engine, drop the global proxy and, optionally, the vault.

**§1.1 against the code: holds.**
- `nextBoundary` returns null without a time policy, so no alarm fires at a window's end.
- `reevaluateLockReasonFromCache` applies only on a changed key.
- `neverRestrict` holds every enabled keyboard.
- An offline sync still applies from the cache.

### High

1. **High (user decision) - an app sideloaded under an allowlisted package name survives the relock.**
   - The allowlist is by package name, and sideloading is lifted in a window (§1.5, "Sideloaded apps").
   - Scenario:
     1. During a pause the kid uninstalls an allowlisted non-system app (App info -> Uninstall).
     2. The kid sideloads any APK under that package name. Once the original is gone, no signature has to match. A
        name that is allowlisted but not installed works without step 1.
     3. At the relock, `apply()` leaves the app unhidden and pins it in kiosk.
     4. The allowlist is also the keyboard and accessibility permit list (§1.5), so the app can be an enabled
        keyboard. The next time the parent types the PIN into the Settings gate, the kid has it, and every later
        window is the kid's.
   - Review M10's server rule is a second way in: a missing catalog package name is learned from "the one new
     package", and the sync that ends an override reports it.
   - **Fix (recommended):** keep `DISALLOW_INSTALL_UNKNOWN_SOURCES`(`_GLOBALLY`) during windows: launcher-only and
     never lifted, like `CONFIG_CREDENTIALS`.
     - Play, install mode and our catalog installs don't go through it.
     - The cost: no on-phone sideload of a fixed launcher during a pause. The fix goes out through the server's
       self-update instead.
   - **Alternative, keeping sideloading:**
     - `opened()` snapshots the non-system packages (package -> signing-cert SHA-256).
     - The relock hides every non-system package that is new or re-signed and whose installer isn't the Play Store or
       us, whatever the allowlist says, and reports it (`sideloaded`).
     - The parent allows it on the device page.

### Medium

2. **Medium - `check` and `opened()` can block the main thread, and a relock can eat a new window's marker.**
   - **`check`:**
     - §1.2 makes `check` synchronized and runs the relock under the same lock.
     - `check` is called from `PinLockRuntime`'s screen-on/`USER_PRESENT` receiver, on the main thread.
     - A relock waits on `AppEnforcer.apply`'s monitor, which a running sync's `apply` can hold.
     - A screen-on during a timer relock can freeze the main thread past the ANR limit, and an ANR kills the call path.
   - **`opened()`:**
     - It runs inside `ParentPin.check` (`startWindow`), under `ParentPin`'s lock.
     - If it takes the relock lock, the dialogs' `lockoutRemainingMs` pre-check waits for a whole relock. That
       pre-check runs on the main thread; design 24 §2.3 accepted a wait of up to 0.5 s.
   - **The race.** The parent taps "Lock again", then starts a pause within the relock's seconds. Step 5 clears the
     pause's `pending`, so nothing relocks at its end.
   - **Fix:**
     - `check` only hands off to one IO worker (single flight) and returns.
     - `opened()` takes no relock lock. It commits the marker with `gen + 1`.
     - The relock clears `pending` only if `gen` hasn't changed. Test it.
3. **Medium - the proxy reset fails with location off, and the APN proxy isn't covered.**
   - **Location off.** `getConfiguredNetworks` needs location on and fine location granted (the Wi-Fi gotcha in
     `launcher/CLAUDE.md`).
     - A location policy "off" switches location off and never grants the permission (`LocateCommands`).
     - "Lock location" can be off too.
     - Either way the reset throws and the proxy stays.
   - **The APN proxy** (SIM -> Access point names -> Proxy) is the same surface for mobile data. A device owner can only
     replace the APNs wholesale (`addOverrideApn`).
   - **Why it matters:** a proxy resolves names itself, so a browser behind one bypasses the DNS filter.
   - **Fix:**
     - Around the reset, switch location on and grant fine location as `WifiNetworksActivity` does, then restore both.
       No fix is taken, so no indicator shows.
     - `proxy_left` reads `getLinkProperties(n).httpProxy` on every network, Wi-Fi and cellular.
     - The APN proxy is report-only, or `DISALLOW_CONFIG_MOBILE_NETWORKS` joins Q1's list.
4. **Medium - the permit lists and the two new restrictions also hit calls-only phones.**
   - "While managed" means `hardeningManaged`: an allowlist **or** managed calls.
   - Scenario: on a phone whose apps the parent left unmanaged, Settings is open all the time. Yet no keyboard or
     accessibility app from Play could be enabled, and hotspot is blocked with no switch.
   - **Fix:**
     - The two lists, `CONFIG_TETHERING` and `CONFIG_CREDENTIALS` apply only while `allowlist != null`.
     - Otherwise the lists are `null` (all allowed), also when unmanaged.
5. **Medium - the parent PIN depends on a keyboard the kid can take away.**
   - All five entries type into an `EditText` with `numberPassword` (`dialog_offline_override_pin`,
     `dialog_pin_parent_code`).
   - Scenario:
     1. In a window the kid disables the only system keyboard (App info -> Disable). Whether Settings allows that for
        the current IME is [device].
     2. After the relock no keyboard exists, and §1.5's fallback ("keep the current default") keeps nothing.
     3. The parent can't type the PIN on the phone, so can't reach Android Settings to undo it.
     4. `enableSystemApp` only re-installs a package for the user. It doesn't clear the user's disable [AOSP 14, check
        15/16]. No other device-owner call re-enables the keyboard.
   - This exists today too. 24b's IME rule is the place to close it.
   - An allowlisted third-party keyboard also sees every parent PIN.
   - **Fix:**
     - The four parent-PIN dialogs and "Parent code" get our own digit keypad. `PinKeypadLayout` fits: the parent PIN is
       6-10 digits.
     - The IME rule is then just enabled ∩ (system ∪ allowlist), and the fallback goes (S5).
     - Report the system apps disabled in the window (`apps_disabled`): keyboards, the dialer, the clock, cell
       broadcast, and anything with a launcher icon. None of it can be undone.
6. **Medium - the vault migration resets both lockouts.**
   - Design 24's `pin_fp` and `PinLockStore`'s `HASH_FP` hold SHA-256(salt:hash). After S2 the fingerprints come from
     the tag, so the first check after the update sees a "new PIN".
   - `forParentPin` then resets failures, level, lockout and decay, and `backoffForHash` resets the kid's backoff. A
     kid holding the parent PIN at a 24 h lockout gets 35 fresh tries at the update.
   - **Fix:** in the migration, while the plain hash is still there, rewrite each stored fingerprint that equals the old
     one to the new one, before the plain fields go. Test it.
7. **Medium - a vault key failure locks the kid out too (Q3).**
   - The kid PIN answers `Unusable` and the parent PIN `Unavailable`. `lockActivation` keeps the lock active
     (`BAD_HASH`), so nobody can open it until a sync.
   - With the Pi down, the phone is calls-only for as long as it's down. The only offline way out is a recovery wipe.
   - The kid PIN's hash protects little at rest. These phones have no Android credential, so CE is decrypted at every
     boot, and whoever extracts it already holds the phone.
   - **Fix:** keep the kid PIN out of the vault, as today. A key failure then only makes the parent PIN unavailable
     offline, like "no PIN set". Or drop the vault (S4).

### Low

8. **Low - `queue_command` (`locate.rs`) deletes every undelivered command before it inserts.**
   - "Lock again" deletes an undelivered `wipe`, `lock` or `locate`.
   - A "Lock" or "Update location now" pressed after it deletes `end_windows`, and the pause stays.
   - **Fix:** `end_windows` gets its own insert and is never deleted by another command. After dispatching it, the
     phone requests one more sync, so a second queued command follows within seconds.
9. **Low - after a credential removal the kid's lock stays off until something re-runs `configure`.**
   - §1.2 runs `apply()` (step 1) before the reset (step 2), so `configure` still sees `android_credential`.
   - It then relies on `onPasswordChanged` firing for a token reset [device]. If it doesn't, the phone has a swipe
     keyguard and no kid lock until the next apply.
   - **Fix:** remove the credential before step 1, or re-run `PinLockRuntime.configure` after a removal.
   - Also:
     - Set the token only while `!isResetPasswordTokenActive`, not on every apply.
     - Q2's text: a lock the parent sets during a window is removed too.
     - A kid-set credential plus a reboot leaves the phone before first unlock until the kid unlocks it, because the
       token is in CE. Accept that and say so.
10. **Low - the boot witness's first run.**
    - A missing `last_boot` (the first start of the S3 build) must count nothing. Otherwise every phone reports
      `BOOT_COUNT - 1` unseen boots and logs `boot_unseen`. Add the case to `BootWitnessTest`.
    - Write `last_boot` early in `onCreate` (wrapped, right after the call policy), so a crash later in `onCreate`
      doesn't turn the next boot into a jump.
    - Both warning variants should say "safe mode, or a start cut short (battery, a system update rolling back)".
      An OTA rollback and a start that dies before `LOCKED_BOOT_COMPLETED` jump too.
11. **Low - relock details.**
    - Keep `pending` when step 1 (`apply`) throws. Otherwise the phone stays open, with no marker, until the next sync.
    - While `pending` is set, `opened()` keeps `device_secure_at_start`. Scenario:
      1. During an override the kid sets an Android PIN.
      2. The parent starts a pause in the same unlocked session.
      3. `device_secure_at_start` becomes `true`, so the kid's lock is kept.
    - The report's `window` becomes `windows`, a list: an override and a pause can overlap, and so can install mode
      and a pause.
    - Store each step's findings as it completes. Then a re-run after a process death doesn't overwrite "1 certificate
      removed" with 0.
    - `HomeFront.bring` should skip whenever the self-update gate sees a live call (the VoIP exemption, `isInCall`,
      the emergency flow), not only on `OngoingCalls.hasLiveCall`.
    - The relock cancels the alarm.
    - In `performMdmSync`, run `check` right after the sync's `apply` and before the status report, so the report
      carries the relock. There it can skip its own step 1.
12. **Low - other persistent changes §1.5 doesn't name.** A device owner can reset none of them. Name them in §1.5 as
    report-only or accepted:
    - **DND automatic rules,** which can silence calls every night. `interruption_filter` is already reported: warn on
      the device page when it isn't `all`.
    - **Notification access for an allowlisted app.** Report the non-system `enabled_notification_listeners`.
    - **The rest:** user-disabled system apps (#5), screen pinning, overlay and usage access, and call forwarding (the
      accepted MMI risk).
13. **Low - PWA and texts.**
    - **The button:**
      - Its redirect goes back to `/devices/{id}` without a `#fragment`, so `scroll-restore.js` keeps the place.
      - Its notice renders inside the Status card (design 24 QA #8), not at the page top.
      - It is a plain form.
      - The existing "The pause ends by itself" line names the button.
    - **Texts:**
      - "a screen lock the child had set" -> "an Android screen lock the child had set".
      - §1.5 contradicts itself: the accessibility row reports "non-system services still on", while the last row says
        `accessibility_enabled` lists system services too.
14. **Low - vault claims and scans.**
    - "Every guess needs this phone's Keystore and 0.5 s of PBKDF2" isn't right. The salt is known, so the PBKDF2 can be
      precomputed off the phone. Only the HMAC needs this phone's Keystore, at milliseconds a call in a TEE. So a rooted
      phone falls in hours, and a file copy not at all. Say that.
    - The scan "`PinHash.verify(` only in `PinVault.kt`" isn't enough:
      - Today `ParentPin.cachedPin`, `OfflineOverride.isConfigured`, `PlayRuntime.canStart`,
        `KidLockConfig.usable`/`fingerprint` and `PinLockRuntime.configure` read the hash fields.
      - A redacted `""` gives `BAD_HASH` or "not configured".
      - Add a scan: `overridePinHash` and `kidLock.pinHash` are read only in `PinVault.kt` and the DTOs.

### Simplification

- **S1. Install mode stays out of `WindowRelock`.**
  - It opens no Settings surface: the kiosk stays.
  - Its ends already re-apply and bring Home: the alarm (`PlayRuntime.nextEdge`), "End now", a reboot, a time lock.
  - Its notification already has "End now".
  - `end_windows` calls `PlayRuntime.endInstallMode`.
  - This removes the notification merge and a `window` value.
- **S2. One alarm.**
  - `TimeRuleAlarm.schedule` already takes the override and pause ends, but only through `nextBoundary`, which needs a
    time policy.
  - Add the window ends unconditionally, at the earlier of the wall end and now plus the elapsed time left.
    `TimeRuleReceiver` already re-arms on a time change and at boot.
  - Call `WindowRelock.check` from `TimeRulesRuntime.recheck`.
  - No second alarm and no new request code.
- **S3. Drop the global-proxy row and `global_proxy_cleared`.** Only adb or a device owner can set a global proxy.
- **S4. Drop S2 (the vault), and record L6 as accepted.**
  - The reasons: no backup, adb is blocked, CE is decrypted at boot on these phones, and the server still sends the
    hash in every policy (M2).
  - If the vault stays: the parent hash only (#7), and TEE only. StrongBox adds latency and transient errors for no
    gain on an HMAC wrapper.
- **S5. With #5's keypad:** no IME fallback, and no `input_methods_kept`.

### Open questions

1. **Launcher-only: agree.**
   - The keyboard and accessibility blocks have a reason §8 doesn't state. The parent types the PIN during windows
     (the Settings gate, a pause, install mode), so a keyboard or service the kid enabled then would see it.
   - CA: keep the block. No emergency use needs it, and it avoids the screen-lock interplay. A CA installed on purpose
     before 24b (a school EAP network) goes at the first relock and can't come back, so check the phones before S1a
     ships.
   - Hotspot: setting the restriction untethers. So hotspot could stay open in windows and be set at the relock, which
     is the literal "opens fully". The block is fine too.
   - Add sideloading (#1).
   - The parent allows a keyboard or service by allowlisting its app. The app then also shows on Home if it has an
     icon. A sync ends an override, so do this under a pause.
2. **Yes**, with #9. Fall back to report-only if the token can't be activated.
3. **Fail closed for the parent PIN only.** Never put the kid PIN in the vault (#7). S4 is better still.
4. **Agree.**
5. **Agree.**

**Needs a user decision:**
- #1: block sideloading during windows (recommended), or use the snapshot rule.
- S4 / Q3: drop the vault, or keep it for the parent PIN only.
- Q1: hotspot blocked during windows, or open and untethered at the relock.
