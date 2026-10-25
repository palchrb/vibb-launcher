# 24b - Windows that lock again, a parent-PIN vault, and safe mode blocked

This follows design 24 and is built by the same implementer after 24's step 1. Its inputs are
`docs/review/security-review-2026-10-10.md` (M9, L6, H4, "Status and decisions") and the user's decisions of
2026-10-10:

- **M9:** the parent PIN may still open the phone fully for a limited window, with nothing blocked. The phone then
  locks down again by itself on a local timer. The parent gets a clear "Lock again", and the PWA can end a pause or
  an override.
- **What survives a window:** at the relock, everything the device owner can reset is reset, including apps
  installed or re-signed during the window. Whatever can't be undone is reported.
- **L6:** a Keystore-backed HMAC for the **parent** PIN only. The kid's own PIN lock never depends on a Keystore key.
- **H4:** `DISALLOW_SAFE_BOOT` on by default, but only after device check 6 passes on the Jelly Star.

The body is revised after QA's design review (§ "QA review (design)", then "Decisions after QA review" at the end)
and again after the code review's fix round ("Decisions after the code review", `docs/design/qa-24b-code.md`).
[AOSP] marks something read in `android14/15/16-release`. [device] marks what only the phone can answer.

## 1. M9: windows that close themselves

### 1.1 Today (verified at 99d88c61, confirmed by QA)

| Window | Started by (design 24: inside `ParentPin.check`) | What opens | How it ends today |
|---|---|---|---|
| override | time-lock screen, 2 h | `appsManaged = false`: nothing hidden or suspended (Android Settings and browsers included); kiosk off; VPN filter off; sideloading allowed; `DISALLOW_CREATE_WINDOWS` and the screen timeout lifted | the next **successful** sync (`OfflineOverride.clear()`), or the 2 h check |
| pause | Settings, 2 h | the same | its 2 h check, or the Settings switch |
| install mode | Settings, 15 min | only the Play Store, unsuspended and pinned; kiosk stays, so Settings can't be reached | already relocks: the alarm (`PlayRuntime.nextEdge`), "End now", a reboot, a time lock - each re-applies and brings Home |

- **No guaranteed re-apply when an override or pause expires.**
  - `isActive()` self-clears only when something asks.
  - `TimeRuleAlarm` wakes at a window's end only through `nextBoundary`, which needs a time policy.
  - `reevaluateLockReasonFromCache` applies only on a changed lock key.
  - So a window that ends with no lock in force stays open until the next sync pass. With airplane mode on, that is
    the backstop's offline `apply()` from the cache, so up to ~15 min. Everything changed in Android Settings stays.
- **Kept during every window** (hardening, never lifted):
  - `FACTORY_RESET`, `ADD_USER`, `MODIFY_ACCOUNTS`, `CONFIG_VPN`, `USB_FILE_TRANSFER`, `DEBUGGING_FEATURES`,
    `CONFIG_LOCATION`, `SET_WALLPAPER`, `CONFIG_LOCALE`;
  - `CONFIG_PRIVATE_DNS`, the call rules, and date/time while calls are managed or rules exist.
  - [AOSP] DPMS sets `DISALLOW_ADD_PRIVATE_PROFILE`, `_CLONE_PROFILE` and `_MANAGED_PROFILE` on a device-owner phone.

  24b adds nothing to this list (the user's decision 1: a window is completely open).
- **Two holes today, both closed below:**
  - `EnforcementPlan.neverRestrict` includes every *enabled* keyboard, so a keyboard the kid sideloads and enables in
    a window is never hidden afterwards (§1.5).
  - The allowlist is by package name. An app sideloaded under an allowlisted name survives the relock (QA #1, §1.6).

### 1.2 The relock engine (`server/WindowRelock.kt`, pure rules in `WindowRelockPlan.kt`)

Install mode stays out of the engine (QA S1). It opens no Settings surface, it already relocks, and `end_windows`
calls `PlayRuntime.endInstallMode`.

- **The marker** (CE prefs `window_relock`, every write with `commit()`).
  - `OfflineOverride.activate` and `RestrictionsPause.start` (since design 24 called only from `ParentPin.check`) call
    `WindowRelock.opened(context, kind, start)` **before** they lift anything. It takes **no** relock lock (QA #2),
    and it commits:
    - `pending = true`, `gen = gen + 1`, `windows += kind`;
    - while no window was already pending, the start snapshot (QA #11): `device_secure_at_start`, the packages
      snapshot (§1.6) and the disabled system packages.
  - A second window opened while one is pending keeps the first snapshot. Taking the snapshot costs
    ~100-300 ms [device] inside `ParentPin`'s lock. That's within design 24's accepted main-thread wait.
- **`WindowRelock.check(context, trigger)` never blocks.** It hands off to one IO worker (single flight, a
  `Mutex.tryLock` or a conflated channel) and returns at once (QA #2).
- **The worker relocks** when `pending` is set and neither `OfflineOverride.isActive()` nor
  `RestrictionsPause.isActive()` is. It first reads `gen`. It clears `pending` (and the snapshot) only if `gen` hasn't
  changed by the end, so a window opened during a relock keeps its marker.
- **Triggers:**
  - **The timer.** One alarm (QA S2): `TimeRulesRuntime.nextBoundary` adds the override and pause ends
    unconditionally, also without a time policy. Each end is the earlier of the wall end and now plus the elapsed time
    left. `TimeRuleReceiver` already re-arms at a time change and at boot, and `TimeRulesRuntime.recheck` calls
    `WindowRelock.check`.
  - `Application.initRest`, because a reboot ends every window.
  - Screen on / `USER_PRESENT`.
  - `performMdmSync`, right after its `apply()` and **before** the status report, so the report carries the relock.
    There the worker skips step 3 below (QA #11).
  - "Lock again", the Settings pause switch turned off, and `end_windows` (§1.3, §1.4). These first end both windows.
- **What a relock does** (`WindowRelockPlan.steps`). Each step's findings are committed as the step completes, so a
  re-run after a process death adds to them rather than overwriting (QA #11).
  1. **Remove an Android screen lock** set during the window (§1.5). This comes before `apply()`, so
     `PinLockRuntime.configure` in step 3 sees no credential and the kid's lock comes back (QA #9).
  2. **Signer check** (§1.6). It writes the `relock_blocked` set that `apply()` then hides.
  3. **`AppEnforcer.apply(currentPolicyDecision().policy)`.** This brings back kiosk, hiding (now with `relock_blocked`),
    the VPN, sideload blocking, `DISALLOW_CONFIG_TETHERING` (it untethers), and the permitted accessibility and keyboard
    lists (§1.5). If this step throws, `pending` stays set and the next trigger re-runs everything (QA #11).
  4. **The other resets** (§1.5): the Wi-Fi proxies, the user CA certificates, our notification permission.
  5. **Detections** for the report (§1.5, §1.7).
  6. **Finish:**
     - `reevaluateLockReasonFromCache`, then `LockActivity` if due, and `TimeRuleAlarm.schedule` (the window's end
       leaves the alarm);
     - `HomeFront.bring` unless the self-update gate's live-call check sees a call (ours, Telecom's `isInCall`, a
       VoIP call, the emergency flow) (QA #11);
     - cancel the notification;
     - clear `pending` if `gen` is unchanged.

### 1.3 "Lock again" on the phone

- **A notification:** channel `launcher:window` (LOW importance, silent), ongoing, one per open window, with a
  "Lock again" action (`WindowRelockReceiver`, not exported, `FLAG_IMMUTABLE`).
  - [AOSP 14] `NotificationManagerService.canBeNonDismissible` keeps `FLAG_NO_DISMISS` for an active device owner.
  - Install mode keeps its own notification with "End now".
- **A Home banner:** `HomeActivity` shows "Open until 18:40 · Lock again" while a window is open. It is our own UI, so
  it can't be dismissed or switched off. If the kid turns off our notifications in a window, the banner remains, and
  step 4 re-grants `POST_NOTIFICATIONS` at the relock.
- **No PIN.** "Lock again" only takes access away.
- **Texts (en / nb):**
  - override: "Open with the parent code until %1$s" / "Åpnet med foreldrekoden til %1$s";
  - pause: "All restrictions paused until %1$s" / "Alle begrensninger er satt på pause til %1$s";
  - the action: "Lock again" / "Lås igjen".

### 1.4 The PWA commands `end_windows`, `allow_blocked` and `start_pause`

- **Server:**
  - Routes in `admin_routes` (`require_full_auth`, plain forms): `POST /devices/{id}/end-windows` and
    `POST /devices/{id}/allow-blocked`. Each records the security event `device_command_queued`.
  - **These two commands use their own insert** (QA #8). Another command never deletes them, and they delete
    nothing. `queue_command`'s "replace the undelivered one" stays for ring/lock/locate/wipe. After dispatching one of
    these, the phone requests one more sync, so a command queued behind it follows within seconds.
  - **The redirect** goes to `/devices/{id}` with no `#fragment` (`scroll-restore.js` keeps the place). The notice
    renders inside the Status card, not at the page top (design 24 QA #8).
  - **"Lock the phone again now"** shows in the Status card when the latest status shows `restrictions_paused` or an
    `install_mode.until_ms` in the future, and its capabilities include `window_relock_v1`.
    - The existing "The pause ends by itself" line becomes: "All restrictions are paused on the phone (someone entered
      the parent PIN in its Settings). The pause ends by itself, or press "Lock the phone again now"."
    - Subtitle: "Ends the pause or Play install mode as soon as the phone gets this (usually within seconds). A
      parent-code opening on the time-lock screen already ends whenever the phone reaches this server."
  - **"Allow these apps"** shows next to the blocked list (§1.7). It goes once the phone answered one pressed after
    the relock (code review Low 10).
  - **"Pause restrictions for 2 hours"** (`POST /devices/{id}/start-pause`, `start_pause`, the same own insert; fix
    round, High 1): the phone's own 2-hour pause, started without the parent PIN on the phone. Shown while the
    latest status has no pause.
  - **The answers** (code review Low 10): the newest of each of the three commands shows for a week under the
    buttons - when it was pressed and the phone's answer, or "no answer from the phone yet".
- **Phone (`dispatchPendingCommand`):**
  - `"end_windows"` runs `WindowRelock.lockAgain(context, "command")` and `PlayRuntime.endInstallMode`. The result is
    `"ended: pause, install mode"` or `"nothing was open"`.
  - `"allow_blocked"` clears `relock_blocked` - except an app signed with another key than the catalog's pin (§1.6) -
    and re-applies. The result is `"allowed: <n> apps"`.
  - `"start_pause"` runs `RestrictionsPause.start` with a fresh 2-hour `WindowStart` (the marker first, as always).
    The result is `"paused for 2 hours"`.
  - An older launcher logs "Unknown pending command". The capability gate hides both buttons for it.

### 1.5 What the kid could change during a window

These are the surfaces an override or pause opens. Install mode opens none. Nothing is blocked while a window is
open but the accessibility list (the user's decision on the code review's High 1). Everything listed here is reset at
the relock, or reported.

**The lists below apply only while apps are managed** (`allowlist != null`): QA #4. On a calls-only or unmanaged phone
they are `null`/off, as today.

| Surface | Reset at relock | Report (§1.7) |
|---|---|---|
| Wi-Fi proxy (manual or PAC, per network) | Fix round (Low 4): only a proxy set or changed since the first window's start (its snapshot keeps each saved network's proxy by SSID; no snapshot = every one). For every such `WifiManager.getConfiguredNetworks()` entry: `setHttpProxy(null)` + `updateNetwork` ([AOSP] `canModifyNetwork`/`canModifyProxySettings` allow the device owner). It needs location on and fine location granted, so around the reset location is switched on and the permission self-granted (`QuickControls.selfGrantPermission`, as `WifiNetworksActivity` does), then both are restored. No fix is taken, so no indicator shows (QA #3). A refused `updateNetwork` is the step's failure. | `proxies_cleared`, and `proxy_left` from `getLinkProperties(n).httpProxy` on every network but a Wi-Fi one whose proxy was kept or just cleared (Low 8) |
| Mobile-data (APN) proxy | none: a device owner can only replace APNs wholesale | `proxy_left` (cellular) |
| User CA certificates | Fix round (Low 4): `uninstallCaCert(admin, cert)` for each certificate added since the first window's start (by SHA-256; no snapshot = every one), then `getInstalledCaCerts` read again (Low 9). Certificates for Wi-Fi (EAP) live in the Wi-Fi keystore and aren't touched (QA question 1). | `ca_certs_removed` (gone after the re-read); one still there is the step's failure |
| Android screen lock (handy's lock goes inactive; the parent can't open the phone) | step 1: if `device_secure_at_start == false` and the phone is secure now, `resetPasswordWithToken(admin, null, token, 0)`. The token is 32 random bytes in CE prefs (`commit()`), set with `setResetPasswordToken` only while `!isResetPasswordTokenActive` and the phone has no credential, which activates it at once (QA #9). Any lock set during a window is removed, a parent's too. [device] | `credential_removed`, `credential_left` |
| Accessibility services | `setPermittedAccessibilityServices(admin, allowlist)` - since the fix round also in windows (High 1 (a)): only an allowlisted app's service can be turned on, ever; ticking the app in the PWA allows it. Set in step 3, after hiding. A hidden package's services leave `ENABLED_ACCESSIBILITY_SERVICES` ([AOSP] `onPackageRemoved`; that hiding broadcasts it is [device]). | `accessibility_enabled`: non-system services still on |
| Input methods | Outside windows: `setPermittedInputMethods(admin, allowlist)`. In windows: `null`. `neverRestrict`'s keyboards become *enabled ∩ (system ∪ allowlist)*, so a sideloaded one is hidden. No fallback is needed: the parent PIN no longer types through a keyboard (§1.8, QA #5/S5). | - |
| Hotspot, USB and Bluetooth tethering | `DISALLOW_CONFIG_TETHERING` outside windows, lifted in windows. Setting it at the relock calls `untetherAll()` ([AOSP] `Tethering.UserRestrictionActionListener`). | - |
| Apps installed, updated or re-signed in the window | §1.6 | `blocked` |
| Device admin apps | none: [AOSP] an app with an active device admin can't be hidden, suspended or uninstalled, and a device owner can't deactivate another app's admin (corrected in the fix round - this row said it was hidden). The backstop (High 1 (b), §1.8): while one of a non-system app outside the allowlist is active, the phone refuses the parent PIN and the PWA shows how to remove it. | `admins_enabled`: active admins other than ours; `foreign_access` in every status |
| Our own notifications | `fixOwnPermission(POST_NOTIFICATIONS)` (a device owner can grant its own permission). A channel the user lowered can't be raised. | `own_notifications_off` (a channel at `IMPORTANCE_NONE`) |
| System apps disabled in the window (keyboards, dialer, clock, cell broadcast, anything) | none: `enableSystemApp` doesn't clear a user's disable [AOSP 14] | `apps_disabled`: newly disabled since the start snapshot (QA #5) |
| Notification access for an allowlisted app | none (a non-allowlisted app is hidden) | `notification_listeners`: non-system listeners other than ours (`NotificationManagerCompat.getEnabledListenerPackages`) [device] (QA #12) |
| Do Not Disturb automatic rules | none | already in the status (`interruption_filter`). The device page warns when it isn't `all` (QA #12). |
| Accepted, not reported | Wi-Fi networks, Bluetooth pairings, app permissions, screen pinning, overlay and usage access for allowed apps, call forwarding (the accepted MMI risk), system accessibility services (TalkBack) | - |

**Order.** Steps 1-2, then `apply()` (restrictions and lists set after hiding: [AOSP 14-16] the `setPermitted*` calls
return false while an enabled non-system service is outside the list, so `apply()` keeps the last list that succeeded
and reports a failure). Then the resets and detections. A step that throws goes into `failures`, and the rest still
runs.

### 1.6 Apps installed, updated or re-signed during a window (QA #1, the user's answer 2)

Sideloading stays allowed in windows. The relock closes the "same name, different app" case:

- **The start snapshot** (in `opened()`): for every non-system package, `packageName -> (signing-cert SHA-256 set,
  lastUpdateTime)`, from `getPackageInfo(GET_SIGNING_CERTIFICATES)`.
- **At the relock (step 2)**, for every non-system package:
  - new since the snapshot;
  - or with a changed `lastUpdateTime`;
  - or with a changed signer.
- **The expected signer** is, in order:
  - M10's pinned signer for a catalog app (`signer_sha256` in the sync's app list, kept on the phone);
  - otherwise the snapshot's signer.
- **The rule** (pure `relockBlockDecision`; fix round, Medium 2: whatever the allowlist says, so ticking the app's name
  doesn't show it):
  - **Changed signer** (vs the expected one): **blocked**.
  - **New with no expected signer**, and the installer (`getInstallSourceInfo().installingPackageName`) is not the Play
    Store or us: **blocked**.
  - **New or updated with the expected signer, or new from Play or us:** fine. A new app that isn't allowlisted is
    hidden by `apply()` as always (the Apps card and `app_installed` show it).
  - **No snapshot** (an adopted window, or it couldn't be taken; Low 2): every package installed since the window's
    start (kept as `start_ms`, an adopted window's as early as it could have begun) from another installer, or with
    another key than its pin, is blocked. A signer check that throws keeps the marker and the snapshot.
- **`relock_blocked`** (CE prefs, `package -> cert`): `computeEnforcementPlan` treats these as not allowlisted. They
  are hidden, never pinned, and not in the keyboard or accessibility lists.
  - An entry clears when the package is uninstalled.
  - An entry clears when the installed signer equals the expected one again (the real app reinstalled from Play during
    a later pause).
  - All entries clear on `allow_blocked` (§1.4) - except an app signed with another key than the catalog's pin, which
    stays blocked until uninstalled (the PWA says so).
  - **Outside windows** (Low 3), an install or update re-checks the one package: a block clears once it is back to
    its expected signer (or came from Play or us), and one installed just now from elsewhere or with another key than
    its pin is blocked. An app "Allow these apps" let out stays out.
  - **Every block reaches the PWA** (fix of 2026-10-12): each status report carries `relock_blocks`, every current
    block - a relock's or the install check's - with its app's signing key now, why (`outside_play`, `other_key`,
    `catalog_key`, or `unknown` for one from before) and where and when it was set. The Status card lists them and
    shows "Allow these apps" while one can be let out.
- **M10's server rule** (learning a catalog name from "the one new package") must ignore packages the phone reports
  as `blocked`. That's noted for M10's server part.
- **Limits:**
  - Outside windows no package can be replaced: Settings is hidden, sideloading is blocked, and Play keeps the signer.
  - A system app's update must keep its signer (Android).

### 1.7 The relock report (status, API change)

- **Launcher:** `StatusReportRequest.relock: RelockReport? = null`. It is the last relock's findings, sent in every
  report until a newer relock replaces it:

  ```json
  "relock": { "at_ms": 1760100000000, "trigger": "timer", "windows": ["override"],
              "proxies_cleared": 1, "proxy_left": false, "ca_certs_removed": 0,
              "credential_removed": false, "credential_left": false,
              "blocked": ["com.example.app"],
              "blocked_signers": {"com.example.app": "3f2a…c0de (64 hex)"},
              "accessibility_enabled": [], "admins_enabled": [],
              "apps_disabled": [], "notification_listeners": [], "own_notifications_off": false,
              "failures": [] }
  ```

  - `blocked_signers` (fix round, Medium 2): each blocked app's signing key, SHA-256 lower-case hex; the PWA shows it
    and compares it with the catalog's pin.
  - `foreign_access` (fix round, High 1 (b)) is its own status field, sent in every report while something is found:
    `{"admins": [...], "accessibility": [...]}` (§1.8).

  - `trigger`: `timer | lock_again | command | sync | boot | screen_on | settings`.
  - `windows`: `override`, `pause`, or both, since they can overlap (QA #11).
  - Lists are valid package names only, at most 8 each.
  - `failures` takes names from the fixed step set.
- **Server:**
  - `StatusReportRequest.relock: Option<serde_json::Value>` goes through `sanitize_relock` (known fields, valid package
    names, caps) into `device_status.relock_json` (a migration).
  - The device page's Status card shows a warning when `at_ms` is within 7 days and anything was found:
    - "When the phone locked again at {time} ({trigger}), it removed {n} Wi-Fi proxy(ies), {n} certificate(s) and an
      Android screen lock the child had set."
    - "Blocked: {app} (signing key {short}) - installed or replaced from outside Play while it was open, or signed with
      another key, so ticking it under Apps doesn't show it." For an app with another key than the catalog's: "... so it
      stays blocked: uninstall it." Otherwise: "If one is yours and its key is the one you expect, tick it under Apps
      and press "Allow these apps"."
    - "Still on: accessibility service {pkg}; notification access {pkg}; a proxy on a network (mobile data or Wi-Fi)."
    - "Turned off and can't be turned back on remotely: {apps} - turn them on in the phone's Settings during a pause."
    - "The launcher's notifications are off."

    Only the parts that apply are shown.
  - The Status card also warns when `interruption_filter` isn't `all`: "Do Not Disturb is on - calls may not ring."
  - **Security log:** `phone_relock_findings` when `at_ms` differs from the previous report and something was found.
- **Capability:** `window_relock_v1`.

### 1.8 The parent PIN on our own keypad (QA #5, the user's answer 4)

- **The change:** the four `dialog_offline_override_pin` entries and the PIN lock's "Parent code" type on our own
  digit keypad (`PinKeypadLayout`, as the kid's lock does) with a dot row. No `EditText`, no keyboard.
  - The keypad accepts up to 32 digits, because PINs saved before design 24's 10-digit bound can be longer.
  - Design 24's in-dialog error line, `TooShort` and the lockout texts stay.
- **Why:** the PIN can't depend on a keyboard the kid can disable, and no keyboard the kid enabled in a window ever
  sees it. This also removes §1.5's keyboard fallback (QA S5).
- **The dialogs' windows** get `FLAG_SECURE` (no screenshots, no screen recording). The keypad views get
  `setAccessibilityDataSensitive(YES)` (API 34).
  - That stops ordinary accessibility services, but [AOSP] any service can declare `isAccessibilityTool`.
  - So, since the fix round (the user's "both" on the code review's High 1):
    - **(a)** the permitted accessibility list stays the allowlist in windows too: a service of an app the parent
      didn't tick can't be turned on, in a window or outside one;
    - **(b) the backstop** (`ForeignAccess`): an active device admin other than ours, or an enabled accessibility
      service, of a non-system app outside the allowlist (relock-blocked apps count as outside) makes the phone refuse
      the parent PIN in all five entries - checked right before the keypad shows (`ParentPin.status`) and again
      before the verify (`ParentPin.check`), nothing counted. The dialogs say to use the parent app. It is checked
      after a relock, at every sync (`foreign_access` in the status) and at a process start; a new finding asks for a
      sync. The PIN works again as soon as a check finds nothing.
    - **The PWA** shows a red warning naming what was found, with the steps: 1. "Pause restrictions for 2 hours"
      (`start_pause`, no PIN on the phone); 2. on the phone, turn the admin and the service off and uninstall the
      app; 3. "Lock the phone again now"; 4. change the parent PIN. The security log gets `phone_foreign_access` when
      it appears, changes or goes.

## 2. L6: a vault for the parent PIN only

- **Today.** `override_pin_hash` (PBKDF2-SHA256, 210k rounds) is in plain CE storage, in mdm prefs and in the cached
  policy blob. Design 24's `pin_fp` (SHA-256 of salt:hash) is an offline oracle too.
- **Scope (the user's answer 3).** The parent PIN only.
  - The kid's PIN lock keeps its PBKDF2 hash in `pin_lock_state` and its unkeyed `HASH_FP`, and never depends on a
    Keystore key (QA #7).
  - So a vault key failure only makes the parent PIN unavailable until the next sync. The kid's lock keeps working.
- **Design (`server/PinVault.kt`, pure core `PinVaultCore` with an injected `Mac`):**
  - **The key.** Keystore HMAC-SHA256 key `pin_vault_v1`: non-exportable, TEE (no StrongBox: QA S4), no user
    authentication.
  - **`accept(fresh): PolicyResponse`**, the only reader of `override_pin_hash`:
    - It stores `tag = HMAC(K, pbkdf2HashBytes)` with the salt.
    - It returns the policy with `override_pin_hash = ""`. Only that redacted policy is cached or passed on.
    - A `null` hash (no PIN) clears the tag.
  - **Verify:** `HMAC(K, PBKDF2(pin, salt))` compared with `MessageDigest.isEqual`.
  - **Fingerprint:** design 24's `pin_fp` becomes the first 16 hex characters of the tag.
  - **Readers move to the vault** (QA #14): `ParentPin.check`/`cachedPin`, `OfflineOverride.isConfigured` and
    `PlayRuntime.canStart` (through `isConfigured`).
- **What it protects, honestly (QA #14).**
  - The salt is stored in the clear, so PBKDF2 for all 10^6 six-digit PINs can be precomputed off the phone. Only the
    HMAC needs this phone's Keystore, at milliseconds a call in a TEE.
  - So a **copy of the files** (a backup, an extraction without the TEE) gives nothing.
  - A **rooted, running phone** can still try every PIN, in roughly an hour.
  - The server keeps its own copy (review M2).
- **Migration** (first start of the new build, idempotent, each step committed):
  1. Create the key.
  2. Wrap the plain hash into a tag.
  3. **Rewrite `pin_fp`** from the old fingerprint to the new one. This happens only where the stored value equals the
     old fingerprint, computed while the plain hash still exists, so the lockout, level and decay carry over (QA #6).
  4. Re-encode the cached blob redacted.
  5. Delete the plain fields.

  Flash and FBE give no guarantee the old bytes leave the disk.
- **Key failure** (`UnrecoverableKeyException`, a missing alias; one retry for transient Keystore errors):
  - `rewrap_needed` is set, and the parent PIN answers `Unavailable` ("The code can't be checked until the phone
    reaches the server").
  - The next successful sync deletes the key, makes a new one, and re-wraps from the fresh policy.
  - The kid's lock is untouched.
- **No contract change.** The server still sends the hash, so `policy_json_keys_snapshot` doesn't change.
  `PolicyResponseCompatTest` gets "a cache with a redacted `override_pin_hash` keeps the parent PIN configured".
- **A longer parent PIN: recommend against requiring one** (the parents' choice). The PWA label from design 24 adds
  advice only: "8 or more digits are much harder to crack if the server is ever copied."

## 3. H4: safe mode blocked by default

- **Gate.** The default flip is its own step (S4). It merges only after device check 6 in `docs/design/04-hardening.md`
  is ticked there, with the date and the build, on the Jelly Star (adb allowed, using today's per-device "Block safe
  mode" switch). The boot report (S3) ships before it.
- **The flip (S4):**
  - **Launcher:** `HardeningRestriction.SAFE_BOOT(true)`. It only matters when a server omits the switch.
  - **Server, new devices:** `insert_device_with_policy` inserts `disallow_safe_boot = 1` explicitly.
  - **Server, existing devices** (a migration):

    ```sql
    INSERT INTO security_events (event_type, detail)
      SELECT 'safe_boot_blocked_by_default', 'device ' || device_id FROM device_policy WHERE disallow_safe_boot = 0;
    UPDATE device_policy SET disallow_safe_boot = 1;
    ```

  - **The hardening card:** "Block safe mode (on by default): safe mode starts the phone without the launcher, so no
    app, call or time rule applies there."
  - The first kid PIN's `saved_safe_boot` path stays (now a no-op).
  - The policy keys are unchanged.
- **Boots the launcher didn't see (S3, API change).**
  - `Application.onCreate` runs at every process start (`LOCKED_BOOT_COMPLETED` starts us in direct boot). Early,
    wrapped and right after the call-policy setup (QA #10), it reads `BootClock.bootCount()` and compares it with DE
    prefs `boot_witness.last_boot`.
  - **A missing `last_boot`** (the first start of the S3 build) counts nothing and only stores the value (QA #10).
  - **A jump greater than 1** adds `jump - 1` to the monotonic `unseen_count` and sets `last_at_ms = now`. Then
    `last_boot` is stored (`commit()`). An unknown count (-1) is skipped.
  - In safe mode our app isn't run, but system_server still counts the boot [device: check 6 confirms +1].
  - **Status:** `StatusReportRequest.unseenBoots: UnseenBootsReport? = null` -> `"unseen_boots": {"count": 1,
    "last_at_ms": 1760100000000}`. The server stores `device_status.unseen_boots_count` and
    `unseen_boots_last_at_ms`.
  - **Security log:** `boot_unseen` when the count rose since the previous report.
  - **Device page** (Status card, while `last_at_ms` is within 30 days): "The phone started {n} time(s) without the
    launcher running, last noticed {when}. That happens in safe mode, which skips every app, call and time rule, or
    with a start cut short (battery, a system update rolling back)." It continues with either:
    - "Turn on "Block safe mode" under Phone hardening."; or
    - "Safe mode is blocked, so it was probably a start cut short - ask your child if it repeats."

## 4. Contract fields (both directories in the same commit as their step)

| Step | Launcher | Server | Pinned by |
|---|---|---|---|
| S1b | `StatusReportRequest.relock` (`RelockReport`), capability `window_relock_v1`; `pending_command.command` `end_windows` / `allow_blocked` handled | `StatusReportRequest.relock`, `device_status.relock_json`, the two routes, the commands' own insert | `PolicyResponseCompatTest` "relock is reported under the server's keys" (exact literal of §1.7) and "the two commands decode"; server `src/tests/window_relock.rs` posts the same literal |
| S2 | none (the cache is redacted) | none | `PolicyResponseCompatTest` "a redacted override hash keeps the parent PIN configured" |
| S3 | `StatusReportRequest.unseenBoots` | `unseen_boots_count`, `unseen_boots_last_at_ms` | `PolicyResponseCompatTest` "unseen_boots is reported under the server's keys"; server `src/tests/hardening.rs` |
| S4 | `SAFE_BOOT(true)` | new devices and the migration | `HardeningTest`; server `a_new_device_starts_with_all_kid_settings_on` asserts `disallow_safe_boot = 1`; a migration test |

`policy_json_keys_snapshot` and the policy half of `PolicyResponseCompatTest` must not change in any step. Next to
these are 19b's `battery_optimization_exempt` and 24's `pin-events`/`parent_pin_events_v1`. Migrations take the
next free numbers.

## 5. Steps (after design 24 step 1; each green on its own)

1. **S1a, launcher:**
   - `WindowRelock` and its plan, with the marker, `gen`, the snapshot and the triggers;
   - the window ends in `nextBoundary`;
   - "Lock again" (notification and Home banner);
   - `DISALLOW_CONFIG_TETHERING` and the permitted lists outside windows while apps are managed;
   - the keyboard rule in `neverRestrict`, the signer check and `relock_blocked`, and the resets;
   - the keypad in the parent-PIN dialogs, with `FLAG_SECURE` and data-sensitive views;
   - the report recorded but not sent yet.
2. **S1b, both directories:** the `relock` status, `end_windows`/`allow_blocked` (routes, buttons, dispatch, the
   commands' own insert), the DND and relock warnings, and `window_relock_v1`.
3. **S2, launcher:** `PinVault` (parent only), its migration with the `pin_fp` rewrite, and the readers moved.
4. **S3, both directories:** the boot witness, `unseen_boots`, and the warning.
5. **S4, both directories:** the safe-mode default flip, **only after check 6 passes**.
6. **Docs:** both `CLAUDE.md`s, design 04 (check 6 and the new default), PLAN, and the review's status table.

## 6. Tests

- **Launcher `WindowRelockPlanTest` (pure):**
  - relock is due only with `pending` set and no window active;
  - `pending` is kept when `gen` changed, and kept when `apply` throws;
  - the snapshot is kept across overlapping windows;
  - the step order (credential before `apply`);
  - findings add up across a re-run;
  - a step failure doesn't stop the rest;
  - credential removal only when the phone wasn't secure at the first window's start;
  - the keyboard rule: enabled ∩ (system ∪ allowlist);
  - the lists and the tethering restriction only while `allowlist != null` and no window is open - the accessibility
    list also in windows (fix round).
- **Launcher `RelockBlockTest` (pure `relockBlockDecision`):**
  - **blocked:** allowlisted re-signed, and allowlisted new from the package installer;
  - **not blocked:** allowlisted new from Play or us, allowlisted updated with the same signer;
  - non-allowlisted new is reported only;
  - a block clears when the package is uninstalled or the signer is back, and on `allow_blocked`;
  - M10's expected signer wins over the snapshot's.
- **Launcher `EnforcementPlanTest`:**
  - under an override, apps, kiosk and Settings open as today;
  - a sideloaded enabled keyboard is hidden outside windows;
  - `relock_blocked` packages are hidden and never pinned.
- **Launcher `HardeningTest`:** `CONFIG_TETHERING` is on outside windows with an allowlist, off in windows, and off on
  calls-only phones. After S4, `SAFE_BOOT` defaults on.
- **Launcher `WindowRelockCallersTest`** (source scan):
  - both starters call `WindowRelock.opened` before lifting anything;
  - `check` is never awaited;
  - the parent-PIN dialogs have no `EditText`.
- **Launcher `PinVaultTest`** (JVM `Mac`):
  - wrap and verify;
  - redaction keeps the salt;
  - a `null` hash clears the tag;
  - a new hash changes the fingerprint;
  - the migration rewrites a matching `pin_fp` (the lockout carries over) and leaves a non-matching one alone;
  - a re-run is a no-op;
  - a key failure gives `Unavailable`, and the kid PIN still verifies.
- **Launcher `PinVaultCallersTest`:** `overridePinHash` is read only in `PinVault.kt` and the DTOs (QA #14).
- **Launcher `BootWitnessTest`:**
  - a first run counts nothing;
  - a jump of 1 counts nothing and a jump of 3 counts 2;
  - -1 is skipped;
  - the counter never decreases.
- **Launcher `PolicyResponseCompatTest`:** the rows of §4.
- **Server:**
  - **`window_relock::tests`:** sanitizing and every warning text.
  - **`src/tests/window_relock.rs`:**
    - the literal is stored;
    - the warnings appear and expire;
    - the security event fires on a new `at_ms` only;
    - both routes queue their command and log it;
    - another command doesn't delete them, and they don't delete others;
    - the buttons follow the status and the capability;
    - the redirect has no fragment, and the notice renders in the Status card;
    - the DND warning.
  - **`src/tests/hardening.rs`:** `unseen_boots` stored, warned and logged. After S4: new and migrated devices block
    safe mode, with one security event per flipped device.

## 7. Device checks (emulator, then the Jelly Star)

- **An override with the screen off and airplane mode on:** at the window's end the phone locks again within a
  minute, without a sync, and also with no time rules set (one alarm).
- **"Lock again"** from the notification and from the Home banner; the notification can't be swiped away. With our
  notifications turned off in a window, they are back after the relock.
- **PWA:** "Lock the phone again now" during a pause and during install mode, and a "Lock" pressed right after it:
  both arrive.
- **Surfaces in an override, then relock:**
  - a Wi-Fi proxy is cleared with location off and the permission not granted;
  - an APN proxy shows as left;
  - a CA certificate is removed;
  - an Android screen PIN set in the window is removed, and handy's lock is active again right away;
  - a sideloaded keyboard is gone; a sideloaded accessibility service can't be turned on in the window (Settings shows
    it restricted);
  - hotspot is off;
  - a sideloaded app made a device admin in the window: it stays (the platform keeps it), the phone refuses the parent
    PIN in all five entries, the PWA shows the warning, and after its steps (a PWA pause, deactivate and uninstall,
    lock again) the PIN works again;
  - a disabled system keyboard is reported, and the parent PIN still works on our keypad.
- **The same-name case:**
  - in a pause, uninstall an allowlisted app and sideload another APK under its name: after the relock it is hidden
    and shown as blocked;
  - "Allow these apps" brings it back;
  - reinstalling the real app from Play in a later pause clears the block.
- **A CA certificate and a Wi-Fi proxy set before the window** stay; ones added in it go.
- **A reboot during a window:** after the boot, one relock with trigger `boot`. A launcher crash or update restart
  after a window's end relocks as `timer` and brings Home.
- **PIN vault:**
  - after the upgrade, the prefs hold no `override_pin_hash` (`run-as`, debug build);
  - a lockout running before the upgrade continues;
  - deleting the key (debug-only hook) gives "can't be checked until…" while the kid's PIN still opens the lock, and
    the next sync recovers. Since design r10 the kid's PIN opens it only while `secrets_v1` is intact (QA #7 reversed:
    r10 §5); deleting both keys is r10 §8's check 5.
- **Check 6 (design 04):** safe mode is refused with `no_safe_boot`. With the switch off, a safe-mode boot followed by
  a normal boot raises `unseen_boots.count` by 1 and shows the warning. A first boot of the S3 build reports 0.
- **After S4:** new and migrated devices list `no_safe_boot` (`dumpsys user`).

## 8. Open questions (with recommendations)

1. **`DISALLOW_CONFIG_TETHERING` outside windows** (needed so the relock's "hotspot off" holds): this also takes
   hotspot away outside windows on phones with managed apps.
   - Reachable today only through Quick Settings in kiosk, if the shade opens [device].
   - **Recommend keeping it.** The alternative is setting it only for a moment at the relock, which races
     `Tethering`'s listener.
2. **The keyboard and accessibility lists outside windows** hide a third-party keyboard the parent installed but
   didn't allowlist. **Recommend this; allowlisting brings it back.** The parent's own PIN no longer needs a keyboard
   (§1.8).
3. **Removing a screen lock set during a window**, the parent's own included: **recommend yes.** Report-only if the
   token can't be activated on the Jelly Star.
4. **The H4 migration flips every device:** **recommend yes**, with the security log naming each one.

Not in scope: blocking anything during a window, a server switch for each new restriction, notification-channel
repair, the APN proxy reset, and the server-side hash exposure (review M2).

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

## Decisions after QA review

The user's answers (2026-10-10):
1. **No blocks during a window.** At the relock everything the device owner can reset is reset (hotspot off, user CA
   certificates removed, proxies cleared, the accessibility and keyboard lists back to the allowlist, an Android
   screen lock set in the window removed). The rest goes in `relock`.
2. **Sideloading stays allowed in a window.** At the relock, packages installed, updated or re-signed in the window
   that aren't allowlisted with their expected signer are hidden and reported (M10's locked signer once it lands).
   The coordinator is confirming this reading with the user.
3. **The vault holds the parent PIN only.** The kid's lock never depends on a Keystore key.
4. **QA #5:** our own keypad in every parent-PIN dialog.

One line per finding:
1. **Changed per the user (answer 2).** Not QA's recommended block, but its alternative, tightened. The start snapshot
   plus a signer check at the relock blocks an allowlisted name that comes back re-signed, or new from a
   non-Play/non-launcher installer. `relock_blocked` is hidden until uninstalled, back to the expected signer, or
   allowed from the PWA (`allow_blocked`). M10's server learning must ignore blocked packages (§1.6).
2. **Accepted.** `check` hands off to a single-flight IO worker. `opened()` takes no relock lock and bumps `gen`, and
   `pending` clears only if `gen` didn't change (§1.2, tested).
3. **Accepted.** Location is switched on and fine location self-granted around the Wi-Fi proxy reset, then restored.
   `proxy_left` reads every network's `LinkProperties`. The APN proxy is report-only: per answer 1, no
   `DISALLOW_CONFIG_MOBILE_NETWORKS`.
4. **Accepted.** The permit lists and `CONFIG_TETHERING` apply only while `allowlist != null` (§1.5).
   `CONFIG_CREDENTIALS` is gone (answer 1).
5. **Accepted (answer 4).** Our keypad in all five parent-PIN entries, plus `FLAG_SECURE` and data-sensitive views
   (§1.8). Newly disabled system apps are reported as `apps_disabled`. Not quite solved: an accessibility service
   enabled in a window can claim `isAccessibilityTool` and see a PIN typed in that same window. That is accepted, by
   answer 1.
6. **Accepted.** The migration rewrites a matching `pin_fp` while the plain hash still exists. Only the parent's
   fingerprint is involved, since the kid's stays unkeyed (§2).
7. **Accepted (answer 3).** The kid PIN stays out of the vault. A key failure only makes the parent PIN unavailable
   until a sync.
8. **Accepted.** `end_windows` and `allow_blocked` have their own insert, neither deleting nor deleted by other
   commands. The phone syncs once more after dispatching one.
9. **Accepted.** The credential goes before `apply()`. The token is set only while `!isResetPasswordTokenActive`. Q3's
   text covers a parent-set lock too. A kid-set lock plus a reboot (BFU until unlocked) is accepted and stated.
10. **Accepted.** The first run counts nothing, `last_boot` is written early in `onCreate`, and both warning variants
    name a start cut short (battery, OTA rollback).
11. **Accepted, all of it:**
    - `pending` is kept when `apply` throws;
    - the first window's snapshot is kept;
    - `windows` is a list;
    - findings are committed per step;
    - `HomeFront.bring` uses the self-update gate's call check;
    - the relock re-schedules the alarm;
    - the sync runs `check` before its report, skipping its own `apply`.
12. **Accepted.** §1.5 names DND rules (a device-page warning from `interruption_filter`), allowlisted apps'
    notification access (reported), disabled system apps (#5), and the accepted rest.
13. **Accepted.** No `#fragment` in the redirect, the notice in the Status card, a plain form, the pause line naming
    the button, and the two texts fixed.
14. **Accepted.** The vault's claim is restated: copies give nothing, and a rooted running phone still falls in about
    an hour. Added the scan "`overridePinHash` only in `PinVault.kt` and the DTOs". The readers are moved.
- **S1, accepted.** Install mode is out of the engine. `end_windows` calls `PlayRuntime.endInstallMode`.
- **S2, accepted.** One alarm: the window ends are in `nextBoundary` without a time policy, and `recheck` calls
  `check`.
- **S3, accepted.** The global-proxy row is dropped.
- **S4, rejected per the user (answer 3).** The vault is kept for the parent PIN only, on TEE with no StrongBox.
- **S5, accepted.** No keyboard fallback and no `input_methods_kept`.
- **QA's open questions:**
  - Q1 is superseded by answer 1. Nothing is blocked in windows. Hotspot is open in windows and untethered at the
    relock. CA certificates are removed at the relock. There's a device check that no phone relies on one (dropped
    in the fix round: only certificates added in a window go, and no phone relies on one - see below).
  - Q2-Q5 are agreed: Q3 is narrowed to the parent PIN, and Q2/Q4 are this note's Q3/Q4.

## Decisions after the code review (2026-10-11)

The code review is `docs/design/qa-24b-code.md` (1 High, 2 Medium, 12 Low). The user's answers:
1. **High 1 (a device admin shields a sideloaded app and its accessibility service): "both".**
   - **(a)** The permitted accessibility list stays in force during windows - the only block a window keeps. An
     accessibility app can be turned on only after the parent allows it in the PWA; the tick under Apps is that
     switch (no separate one was needed: the list is the allowlist). Everything else stays unblocked in windows.
   - **(b)** The backstop of §1.8: an active device admin other than ours, or an enabled non-system accessibility
     service, outside the allowlist makes the phone refuse the parent PIN in all five entries until nothing unknown
     is seen; the PWA warns in red with the steps, and can start the pause itself (`start_pause`, new: there was no
     remote pause).
   - §1.5's device-admin row was wrong ("hidden, and DPMS drops a removed package's admin"): [AOSP] such an app can't
     be hidden, suspended or uninstalled. Corrected.
2. **Medium 2:** M10's pins (`signer_sha256`) feed the signer check; "Allow these apps" shows each app's key; an app
   whose key differs from the catalog's pin or the window start's stays blocked even if ticked, and one with another
   key than the pin even after "Allow these apps"; the PWA says why and no longer invites ticking.
3. **Low 4:** only the CA certificates and proxies added during the window go (a start snapshot,
   `uninstallCaCert` per certificate), on calls-only phones by the same rule.
4. **QA question 1:** EAP Wi-Fi certificates are unaffected (they live in the Wi-Fi keystore, not the user CA
   store). The user confirmed that no phone uses a school or work network with its own CA, so the "check before S1a
   ships" item is dropped.
5. **Medium 1 and the other Lows:** as QA suggested (implementation status, "Fix round").

## Open questions: resolved (2026-10-10)
These follow from the user's earlier decisions (allowlist and kiosk, no Android screen lock, safe mode blocked by
default), so the architect's recommendations are taken as the answers:
1. Hotspot stays blocked outside windows on phones with managed apps: yes.
2. A keyboard the parent installed but didn't allowlist is hidden: yes. The PWA says how to allow it.
3. Any Android screen lock set during a window is removed at relock, including the parent's own: yes. The phone uses
   the launcher's PIN lock, not Android's.
4. Safe mode flips to blocked on every existing device once check 6 passes: yes (H4).
The user can overturn any of these.

## Implementation status (2026-10-11)

Built on branch `relock-24b` as §5's steps: S1a (launcher), S1b (both), S2 (launcher, plus the PWA's advice line),
S3 (both), docs. S4, the safe-mode default, is built on its own branch `relock-24b-s4` on top and **merges only after
device check 6** (design 04) is ticked there. On main `2481de32`: launcher
`testDebugUnitTest assembleDebug assembleRelease -PwarningsAsErrors=true` green (848 tests); server `cargo test`
(497 tests), `fmt --check` and `clippy --all-targets` green with no new warnings. The PWA was checked in headless
Chromium at 360 px, light and dark: no horizontal scroll, the warnings and buttons in the Status card, and after
"Lock the phone again now" or "Allow these apps" the page comes back at the same place with the notice right above
the buttons.

**Deviations** (none changes a rule of the design):

1. **Migration numbers:** taken after the final rebase onto main as the next free ones after main's 0062:
   `0063_window_relock.sql`, `0064_unseen_boots.sql`; S4's is `0065_safe_boot_default.sql` on its branch (renumber it
   if main moves first).
2. **`opened()` lifts the window itself.** The starters pass their flag writes as a lambda that runs under the
   marker's lock right after the marker's commit. Otherwise a relock starting in the snapshot's ~300 ms could see
   the new marker with no window open, clear it, and leave the window without one.
3. **Adoption:** a window an older build opened (no marker) gets one at the next check or "Lock again" and relocks
   without a start snapshot (no signer check, no credential removal).
4. **The sync's relock** ~~skips `apply()` only when steps 1-2 changed nothing~~ - gone in the fix round (Medium 1):
   step 3 always runs, also in the sync, whose own apply may have run with the window still open.
5. **Home after a relock** is not brought to the front at a process start (`boot`): the boot brings Home itself
   (design 16).
6. **Signers** match when the installed set shares a certificate with the expected one (a key rotation keeps the old
   one in its history), as the self-update's `signerMatches`. A block with no known signer also clears when the app
   comes back from Play or us.
7. **`relock.blocked`** lists only the blocked apps (since the fix round, Medium 2): a new app from Play that isn't
   allowlisted is hidden by `apply()` and shown by the Apps card and `app_installed`, not here. "Allow these apps"
   shows while a blocked app isn't one with another key than the catalog's pin.
8. **The location permission** self-granted for the proxy reset stays granted (revoking a runtime permission kills
   our process); its policy state goes back. Location itself is switched off again.
9. **`failures`** names: `credential, signer_check, apply, permitted_lists, proxies, ca_certs, notifications,
   detections, finish`.
10. **The tethering and list tests** live in `EnforcementPlanTest` (they are plan fields, lifted in windows, not
    hardening switches).
11. **Do Not Disturb:** design 18's existing line ("Do Not Disturb at the last sync: on (...) - calls don't ring")
    is shown in the warning style instead of a second line.
12. **The vault** also carries a lockout over at a re-wrap of the same PIN (same salt: the server makes a new one
    for every save), not only at the migration; a new PIN still resets it. Its cache redaction and the sync's cache
    write share one lock, so a newer policy is never overwritten. A key failure undoes the counted try, like a
    failed commit.
13. **Debug-only hook** `PinVaultKeyReceiver` (src/debug, DUMP) deletes the vault key for §7's check; the release
    build check (`checkReleaseHasNoDebugHook`) covers it.

**Device checks (emulator, then the Jelly Star):** §7 as written. For S4: check 6 in design 04 first.

### Fix round (2026-10-11)

Every finding of `docs/design/qa-24b-code.md` is fixed, rebased onto main (after the security wave's QA fixes):
- **High 1:** (a) the accessibility list in windows (`EnforcementPlan.permittedAccessibility` follows the allowlist,
  not the window); (b) `ForeignAccess` and the PIN refusal in `ParentPin.status`/`check`, the status field
  `foreign_access` (`device_status.foreign_access_json`, in 0063), the red warning, `phone_foreign_access`, and
  `start_pause` ("Pause restrictions for 2 hours") with its own insert. `ParentPinCallersTest` lets `MdmSyncWorker`
  start a pause for it.
- **Medium 1:** the relock always applies. **Medium 2:** the catalog pins on the phone (`rememberCatalogPins` from the
  sync's app list), blocks whatever the allowlist, `blocked_signers` in the report (both sides), "Allow these apps"
  keeps a pin mismatch, the PWA texts.
- **Lows:** 1 the vault keeps its tag on a failed wrap; 2 a throwing signer check keeps the marker, and the
  no-snapshot rule with `start_ms`; 3 the install check outside windows (with the let-out record); 4 only what the
  window added goes (CA certificates by SHA-256, Wi-Fi proxies by SSID); 5 the alarm in `opened()`; 6 `boot` only in
  a new boot (`BootWitness.newBoot`), else `timer`; 7 the notification only as device owner; 8 `proxy_left` wording
  and Wi-Fi settle; 9 the certificates read again, leftovers a failure; 10 the commands' answers on the Status card,
  "Allow these apps" gone once answered; 11 the vault migration on IO from `initRest`; 12
  `device_policy.disallow_safe_boot_set_at_ms` (in 0064) for the unseen-boots advice.
- **Device checks added to §7:** the foreign-admin backstop end to end, an accessibility service refused in a window,
  certificates and proxies from before a window kept, a crash restart relocking as `timer`.
- **Follow-up (2026-10-12, found on the Android 16 emulator):** the debug music app, installed with adb outside any
  window, was blocked by the install check and stayed hidden although ticked, and the PWA showed nothing: only the
  last relock's report reached the server, and a block kept from an earlier relock was never reported again. Fixed:
  the status field `relock_blocks` (above, `device_status.relock_blocks_json`, migration 0067), the Status card's
  list with "Allow these apps", `phone_app_blocked` in the security log. The install check also skips a static
  shared library's versioned name (Chrome's `trichromelibrary_<version>`) without a warning.
- **Green on main `7e930b24`:** launcher `testDebugUnitTest assembleDebug assembleRelease -PwarningsAsErrors=true`
  (893 tests), server `cargo test` (537; 538 with S4), `fmt --check`, `clippy --all-targets` with no new warnings,
  the same on `relock-24b-s4`. The PWA in headless Chromium at 360 px, light and dark: no horizontal scroll, the red
  warning with its steps, the answers under the buttons, and after "Lock the phone again now", "Allow these apps"
  or "Pause restrictions for 2 hours" the page comes back at the same place with the notice in view.
