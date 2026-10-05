# QA/security review: step 4 hardening (code)

Reviewed S `4f9f16c`/`36c981e`, L `9e129c4`/`c55e308`/`7325574`/`39eba5c` (branch `handy`) against `04-hardening.md`.
Tests re-run: server `cargo test` 65/65 pass; launcher `testDebugUnitTest` 165/165 pass. "Inferred" = Android
platform behaviour not checked on a device; it becomes a device-checklist item. L paths are under
`app/src/main/java/com/kidslauncher/mdm/`.

## Findings

1. **High - no emergency-call path at bedtime unless the phone has a secure lock screen.** `ui/LockActivity.kt:112`
   shows the Phone book only while calls are managed, and `calls/PhoneBookActivity.kt:76` lists 112 only when the rules
   are unknown. With calls unmanaged, or managed with known rules and no 112 contact, the only way to call 112 is
   the keyguard's Emergency button. Before step 4 a kid could still get to the system dialer through Recents. Now every
   escape is closed, so a phone with a swipe/None lock has no way to call 112 at bedtime. Fix: LockActivity always
   shows an Emergency button (`CallSystem.placeCall("112")` after a confirmation, as `emergencyRow` does) whatever the
   call state. Add "112 from LockActivity, calls unmanaged and managed" to device check 8.
2. **High (inferred) - the bedtime lock hides allowed apps every night, which can drop the kid's morning alarm.**
   `server/EnforcementPlan.kt:111` puts every controllable app in `suspend`, and `AppEnforcer.kt:202` also
   `setApplicationHidden`s each one. That includes the clock app, which is launchable. Hiding a package broadcasts
   PACKAGE_REMOVED, which drops its alarms and jobs (and probably widgets). The suspended clock can't show its alarm
   activity anyway. Bedtime usually ends after the alarm time. Fix: during the lock, suspend allowlisted apps without
   hiding them, and exempt the default alarm app (the resolver of `AlarmClock.ACTION_SHOW_ALARMS`) like the dialer. Add
   "alarm set inside bedtime rings" to the device checks.
3. **Medium (inferred) - the bedtime lock can suspend the keyboard, which blocks the parent's PIN.** The schedule
   suspension ignores the allowlist. If the IME package has a launcher icon (some OEM keyboards do, and Gboard does on
   some builds), it is hidden at bedtime. Then the unlock-code dialog in LockActivity and the Settings PIN gate can't
   take input, and the parent's offline path is gone until morning or a sync. Fix: add the enabled/default IME
   packages (`Settings.Secure.DEFAULT_INPUT_METHOD`) to `neverRestrict`, or use an in-app numeric keypad for PIN entry.
4. **Medium - a phone with no PIN, a lost server and adb blocked can only be recovered by a wipe.**
   `ui/settings/SettingsGate.kt:34` gives REFUSE_NO_PIN, and the debugging default is on (`Hardening.kt`, migration
   0023). Before step 4, Settings was open without a PIN, so the parent could re-point the server URL. Now, if the
   server/tailnet is gone and no PIN was ever set, there is no on-phone path back: no Settings, no adb, no override,
   and factory reset is blocked in Settings. Fix: on the device page, warn prominently when there is no PIN while
   managed (`device_detail.html:184` only says nothing), or refuse/warn when debugging is blocked without a PIN.
5. **Medium - existing phones lose adb on the first sync after the upgrade, and an older server has no switch.**
   Migration `0023_hardening.sql:15` sets `disallow_debugging_features = 1` on existing rows. A pre-0023 server sends
   no `hardening`, which the launcher reads as the defaults (`dto/HardeningPolicy.kt`). Deploying the launcher before
   the server therefore blocks adb with no way to clear it except unmanaging. The server UI also has no path to
   unmanage (it never NULLs `allowlist_json`), so check 2's "unmanaging clears all" can't be tested from the UI. This
   follows the user's decision, but the order is not written down. Fix: in 04 and the release notes, say "server first,
   then turn the switch off for test phones, then the launcher". Optionally default existing rows to 0 in the
   migration.
6. **Medium - the spec overstates safe mode as a recovery path.** Safe mode keeps the user restrictions and package
   suspension, so it has no adb, no Developer options, no factory reset from Settings, and Settings stays suspended if
   it isn't allowlisted. Unknown sources stay blocked too, so the launcher can't be replaced or fixed. Safe mode gives
   a usable phone, not a repair. With debugging blocked, any pre-render crash loop means a recovery wipe either way.
   Fix: correct `04-hardening.md` and the safe-boot card text. No code change.
7. **Low (inferred) - incoming calls may not show at bedtime in kiosk mode when the system dialer is the in-call UI.**
   `EnforcementPlan.kt:116` pins only our package during the lock. With calls unmanaged, or with our dialer role
   missing, the system dialer's in-call or emergency UI is not lock-task-permitted. Fix: keep `systemDialer` pinned
   when our dialer isn't active, or add the case to device check 8.
8. **Low - the hardening switches can only be cleared after the rest of `apply` succeeds.** `applyHardening` runs
   last (`AppEnforcer.kt:241`), and `performMdmSync` runs DNS refresh and command dispatch before `apply`
   (`MdmSyncWorker.kt:118-145`). Most steps catch their own errors, but any uncaught exception earlier skips the
   "debugging off" the parent sent to get adb back, which is exactly when it is needed. Fix: apply hardening in its own
   try block before the package loop, or at least clear restrictions first, in a `finally`.
9. **Low - a corrupt cache re-blocks adb that the parent had allowed.** `NOTHING_ALLOWED_FALLBACK`
   (`PolicyGate.kt:172`) has `hardening = null`, which means the defaults, so a corrupt cache plus an unreadable
   `LastEnforcedPlan` sets DISALLOW_DEBUGGING_FEATURES again. A corrupt server allowlist also returns 500, so the
   switch can't reach the phone until the list is repaired (the new page warning covers the parent side). Fix (optional):
   keep the last applied hardening in its own small pref for the fallback.
10. **Low - the schedule edge is recorded before it is enforced.** `reevaluateLockReasonFromCache` writes `lockReason`
    (`MdmSyncWorker.kt:536`) and only then launches `apply`. If that apply fails or the process dies, the next
    minute check sees "no change", so suspension waits for the 5-minute sync. Fix: write the reason only after a
    successful apply, or compare against a separate "enforced reason".

## Checked, no finding

- Fail-closed decoding: a missing `hardening` or field means the defaults, a wrong type fails the whole decode and the
  cache stays, and an old `LastEnforcedPlan` means the defaults (tests cover all three). Override and pause go through
  `currentPolicyDecision().policy`, and no `apply(null)` call is left. User restrictions and suspension persist
  across a reboot, and the lock edge is re-checked after unlock (the service is not direct-boot-aware, which is right).
- Settings gate: every entry point (`APPLICATION_PREFERENCES`, explicit intent, `openSettings`, recents) reaches
  `onStart`. The passed flag survives only `isChangingConfigurations` and can't be set from outside. Content is
  INVISIBLE (no touches or accessibility). The pause switch needs the PIN again. Instrumentation needs adb.
- Bedtime: new installs are suspended, kiosk pins only our package, the call restrictions don't change, `apply` is
  `@Synchronized` with every caller off the main thread, and the clock stays locked (`DISALLOW_CONFIG_DATE_TIME`).
- Server: the route is behind `require_full_auth` (tested). Missing checkbox means off, unknown device gives 404,
  a DB error gives 500 with no nudge, and every `DevicePolicy` query is `SELECT *`, so the flatten is safe. The
  snapshot pins the keys and the launcher decode test uses the same snake_case keys. Tests assert the right things;
  a test that pre-0023 rows get the defaults is missing (SQLite fills them, low risk).
