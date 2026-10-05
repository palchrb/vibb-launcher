# Step 4: hardening

Last code step before device testing (PLAN phase 2, qa-security §5 P0 1/3/4, qa-step1-code #11/#12). Scope is
frozen: no features beyond the four items below. S = kid-phone-server, L = kids-launcher-mdm, branch `handy`.

## Design

**1. User restrictions while managed (S+L).** Eight per-device switches in `device_policy` (migration `0023`), always
sent as `hardening` with explicit booleans (`build_policy`; the key snapshot test pins them):

| Policy key | Android | Default |
|---|---|---|
| `disallow_factory_reset` | `DISALLOW_FACTORY_RESET` (Settings only; a recovery wipe still works) | on |
| `disallow_add_user` | `DISALLOW_ADD_USER` | on |
| `disallow_modify_accounts` | `DISALLOW_MODIFY_ACCOUNTS` | on |
| `disallow_config_vpn` | `DISALLOW_CONFIG_VPN` | on |
| `disallow_usb_file_transfer` | `DISALLOW_USB_FILE_TRANSFER` (MTP/PTP; not adb) | on |
| `disallow_debugging_features` | `DISALLOW_DEBUGGING_FEATURES` - **on = no adb**, i.e. no `adb install -r` fix-forward, no logcat, no `cmd role` | on |
| `disallow_safe_boot` | `DISALLOW_SAFE_BOOT` | **off** |
| `lock_location` | `DISALLOW_CONFIG_LOCATION` + `setLocationEnabled(true)` (Find my device) | on |

Already set (unchanged): `CONFIG_DATE_TIME` + auto time, `CONFIG_PRIVATE_DNS`, `INSTALL_UNKNOWN_SOURCES(_GLOBALLY)`,
`CONFIG_DEFAULT_APPS`, `OUTGOING_CALLS`, `SMS`.

- Pure `hardeningPlan(policy.hardening, managed)` (L `server/Hardening.kt`): managed = the enforced policy has an
  allowlist or calls are managed. Managed → each restriction as its switch says (a missing key or field = the
  default above); unmanaged or never-managed → all cleared (setup/adb provisioning work). `LastEnforcedPlan` keeps them.
- **Not lifted by the offline override or the pause** (second explicit exception to the launcher's "every
  restriction is liftable" rule, after calls): only an explicit server value or unmanaging lifts one. The override
  is for a broken policy/filter, not for adb or a factory reset. Date/time keeps its documented behaviour.
- **Debugging on by default** as asked; adb was upstream's recovery path for the boot-loop/BFU incidents. With it on,
  a crash-looping launcher can't sync the switch off, so recovery is a recovery-mode wipe + re-provisioning. Turn it
  off on the device page before any device test that needs adb (all of the 02/qa checklists do).
- **Safe boot off by default (decision, corrected after QA #6).** Safe mode disables the launcher (bypass vector #1).
  It is **not** a repair path: restrictions, suspension, blocked adb/factory reset and unknown sources all persist
  there - it only gives a usable phone without the launcher. Kept off only until device check 6 passes on the Jelly
  Star (untested OEM behaviour); then it can default on. Parent recovery paths: see the runbook below.

**2. Launcher Settings never open without the PIN (L, QA #12, qa-security #5).** Pure `settingsAccess(policyEverApplied,
pinConfigured, lockedOut)`: OPEN only before any policy was applied (setup: server URL, enroll, scan QR); otherwise
REQUIRE_PIN, or REFUSE when no PIN is configured on the server (toast "set an unlock code on the server") or the PIN
is locked out. Checked in `onCreate` and on every `onStart`, so every entry point (drawer, `APPLICATION_PREFERENCES`,
explicit intents, recents, restore after process death) hits it. Passing the gate lasts until `onStop` (not across a
configuration change): leaving Settings and coming back via recents asks again; content is `INVISIBLE` until
verified and recents screenshots are disabled.

**3. Schedule enforced by suspension (L, qa-security P0 #3 / vector #11).** While the schedule locks (bedtime or
outside screen time) and no override/pause is active, `computeEnforcementPlan(scheduleLocked = true)` suspends+hides
every controllable app except our own package and the system dialer (never suspended: emergency calls), whatever the
allowlist; kiosk pins only our package; call restrictions are computed exactly as without the lock (allowed calls
keep working: our dialer, phone book, in-call UI and screening are in our package). `AppEnforcer.apply` derives
`scheduleLocked` from the policy it enforces (`KidModeEnforcer.evaluate`), so sync, pause and override paths agree;
new installs are suspended during the lock too. Edges: `reevaluateLockReasonFromCache(context)` re-applies when the
reason changes; `CommandListenerService` re-checks every minute and on screen-on, then starts `LockActivity` (device
owners may start activities from the background). `apply` is serialised. LockActivity gets a "Phone book" button
while calls are managed (fail-closed: the emergency row). Calls unmanaged: only emergency calls during the lock. No
per-app "allowed at bedtime" list (scope frozen; Vibb later).

**4. Server.** Device page: a "Phone hardening" card, one auto-submitting form (`POST /devices/{id}/hardening`, like
the calls switches: a missing checkbox is off) with the adb/safe-boot risks spelled out; transaction-free single
`UPDATE`, 500 on DB error, nudge. Corrupt stored allowlist: a warning on the device page (the phone gets 500s and keeps
its cache; app checkboxes can't be trusted) - QA #11 server half. Tests via `TestApp` (defaults, explicit values in
the policy, form round trip, corrupt-allowlist warning).

Not in this step: `DISALLOW_USER_SWITCH`, a warning for allowlisted Android Settings, CI notes #9/#10 (GitHub).

## Runbook: deploy order and recovery (after QA step 4 #4/#5/#6)

1. **Server first.** Update the server (migration `0023` runs; existing devices get the defaults, i.e. USB debugging
   blocked). Before the new launcher reaches any phone, set the switches: on test phones turn **"Block USB debugging"
   off** for as long as adb work is planned (all device checklists use adb). A launcher on a pre-0023 server gets no
   `hardening` and applies the defaults, which only the server (or unmanaging) can lift - never update the launcher
   first. Set an unlock code (PIN) on every managed phone: without one the device page warns, the launcher's Settings
   don't open and there is no offline override.
2. **New phone:** create the device on the server, turn "Block USB debugging" off if adb work follows, grant
   `ROLE_CALL_REDIRECTION` with adb (01 §4 runbook) **before enrolling**, then enrol. Turn the switch back on at
   handover.
3. **Recovery** when the phone misbehaves: (a) phone still syncs → fix on the server: push a fixed launcher (catalog
   row, self-update), or switch "Block USB debugging" off and repair with `adb install -r` (higher versionCode);
   (b) server unreachable → the offline PIN lifts app restrictions and the schedule for 2 h (not calls/hardening) and
   opens the launcher's Settings to re-point the server URL; (c) launcher crash-loops before it can sync and adb is
   blocked → recovery-mode factory reset and re-provisioning (01 §4). Safe mode repairs nothing.

## Implementation status (2026-10-05)

Done on branch `handy` in both forks (not pushed). Every commit builds and passes its tests (intermediate launcher
commits checked in a separate worktree).

| Repo | Commit | What |
|---|---|---|
| S | `4f9f16c` | migration `0023_hardening.sql`, `models::Hardening` (flattened into `DevicePolicy`), `hardening` in `build_policy`, "Phone hardening" card + `POST /devices/{id}/hardening`, corrupt-allowlist warning, PIN text; `src/tests/hardening.rs` |
| S | `36c981e` | CLAUDE.md |
| L | `9e129c4` | schedule lock by suspension: `computeEnforcementPlan(scheduleLocked)`, `KidModeEnforcer.lockReasonNow`, `shouldSuspendNewPackage(scheduleLocked)`, `reevaluateLockReasonFromCache(context)` re-applies, minute/screen-on check in `CommandListenerService`, Phone book button on LockActivity, `apply` `@Synchronized`, override applies the enforced policy off the main thread |
| L | `c55e308` | `HardeningPolicy` DTO, pure `Hardening.kt`, `AppEnforcer.applyHardening`, switches in `LastEnforcedPlan` |
| L | `7325574` | pure `SettingsGate.kt`, `SettingsActivity` gate on every start, reset on stop, no recents screenshot |
| L | `39eba5c` | CLAUDE.md |

Tests: server 65 (`cargo test`, was 57), `cargo fmt --check` clean, clippy unchanged (23 warnings, all pre-existing).
Launcher 165 JVM unit tests (was 149): new `HardeningTest`, `SettingsGateTest`, schedule cases in
`EnforcementPlanTest`/`PolicyGateTest`/`KidModeEnforcerTest`. `assembleDebug` and `assembleRelease` build. The device
page was checked through the real router in tests, not in a browser.

Choices made where the brief left room:
- **Safe boot defaults off** (see design). **Debugging defaults on**, as asked - it kills adb on every existing phone
  once this launcher and server are deployed, unless the switch is turned off first.
- Hardening is "managed" when the enforced policy has an allowlist or calls are managed; an explicit `allowlist: null`
  with calls unmanaged clears it (the only server-side way besides the switches).
- During the lock, an allowlisted system dialer is not pinned and calls unmanaged get no Phone button (emergency only).
- Settings counts as "managed" once `policy_ever_applied` is set or a policy is cached; an enrolled phone that never
  got a policy keeps Settings open (it is unrestricted then anyway).

Fix round after `qa-step4-code.md` (S `f035f8e`, `068af82`; L `597dd06`, `c3267fc`, `b0750d3`, `2a28f9f`):
- #1 LockActivity always shows "Emergency call" (confirm → self-grant CALL_PHONE → Telecom 112, fallback: system
  dialer with 112 typed in), whatever the call state.
- #2 the lock suspends allowed apps without hiding them (`EnforcementPlan.hide` = only apps not allowed at all); the
  default alarm app (next alarm's creator, else the `SHOW_ALARMS` resolver) isn't suspended by the lock, isn't pinned,
  and still follows the allowlist.
- #3 enabled/default input methods are in `neverRestrict` (never suspended or hidden).
- #4 device-page warning for a managed phone without a PIN; #5/#6 runbook above, safe-mode text corrected (spec, card,
  CLAUDE.md, code comments; the comment in migration `0023` is left as is - editing it would change its checksum).
- #7 an allowlisted system dialer stays pinned during the lock while calls are unmanaged.
- #8 switched-off hardening is cleared at the start of `apply()`; #10 `AppEnforcer.lastEnforcedScheduleLock` makes
  the minute check re-apply until the lock is really enforced.
- Test that pre-0023 rows get the defaults (runs the old migrations, inserts, runs the rest).
- Still open: #9 (corrupt cache + unreadable `LastEnforcedPlan` → hardening defaults, i.e. adb blocked again; rare,
  and the server warning covers a corrupt server allowlist). Device checks added below (11-13).

Tests after the fix round: server 67, launcher 168.

Open: whether `DISALLOW_CONFIG_VPN` lets the always-on `KidVpnService` restart; whether `setLocationEnabled` works
with `DISALLOW_CONFIG_LOCATION` set; the schedule edge while the screen is off waits for screen-on (by design, battery).

Device checklist (Jelly Star, release build; follow the runbook above first; in addition to 01/02 checklists and qa-security §4):
1. Before enrolling: grant `ROLE_CALL_REDIRECTION` with adb, or switch "Block USB debugging" off on the device page.
   After the first sync with it on: `adb devices` shows nothing/unauthorized, Developer options is blocked; switching
   it off on the server brings adb back after a sync.
2. With the defaults, `adb shell dumpsys user` (debugging off first) lists `no_factory_reset`, `no_add_user`,
   `no_modify_accounts`, `no_config_vpn`, `no_usb_file_transfer`, `no_config_location`, `no_debugging_features`, and
   not `no_safe_boot`; each switch off on the server clears its restriction after a sync; unmanaging clears all.
3. Offline override and pause leave all of them set (`dumpsys user`), while apps are released.
4. USB to a laptop: no MTP/PTP; Settings → System → Reset is "blocked by admin"; add user/guest is not offered.
5. VPN: after a reboot and after filter off/on, `KidVpnService` is the always-on VPN and DNS filtering works with
   `no_config_vpn` set. Location is on and its tile/setting is blocked; Quick Controls → Wi-Fi networks still scans.
6. Safe boot on (only after 1-5 pass, with adb still allowed): long-press power-off → no safe-mode prompt; hardware
   key combo boots normally. Off again afterwards unless the build is trusted.
7. Bedtime starts while inside an allowed app: within a minute (screen on) the app is closed as "paused" and
   LockActivity shows; Recents and a notification tap don't open any app; a new install during bedtime stays suspended.
8. During bedtime: "Emergency call" on LockActivity reaches 112 (test mode) with calls unmanaged, managed, and with a
   swipe/None screen lock; Phone book button calls an allowed contact; an allowed contact's incoming call rings and the in-call
   screen works (also with LockActivity up); 112 from the lock screen's Emergency button connects (test mode, not 112).
9. Bedtime ends with the screen off: on screen-on apps are back within a minute; with the override PIN apps come back
   at once and re-lock after 2 h / on sync.
10. Launcher Settings: managed phone without a server PIN → toast, no Settings; with a PIN: drawer, App info →
    "Additional settings" (`APPLICATION_PREFERENCES`) and `am start -n …/.ui.settings.SettingsActivity` all ask; open
    Settings, press Home, return via Recents → asks again, and the Recents thumbnail shows nothing.
11. An alarm set for inside bedtime rings and can be dismissed; the clock app's widgets/alarms survive a night (it is
    suspended at most, never hidden, by the lock).
12. With the keyboard app having a launcher icon (check `pm list packages` + drawer), the unlock-code dialog and the
    Settings PIN gate take input during bedtime.
13. Calls unmanaged with the system dialer allowlisted, kiosk on, bedtime: an incoming call shows the system in-call UI.
