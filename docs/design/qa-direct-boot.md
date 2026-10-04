# QA: direct-boot call path (task 15)

Reviewed `git diff 5fc7271^..handy` in kids-launcher-mdm (HEAD `51c534e`) against 02-calls.md "Direct boot (task 15)".
`testDebugUnitTest`: 135 tests, 0 failures. Merged debug manifest checked: only the six call components are
`directBootAware`, and `BootCallReceiver` is the only `LOCKED_BOOT_COMPLETED` receiver (no library components).
Items marked (inferred) are from reading the platform's behaviour, not from a device run.

**Verified OK (deadlock).** While the user is locked, `Application.onCreate` only reads DE prefs, runs `BootClock.init`
and registers the unlock hooks. Before unlock nothing starts lock task, kiosk, services or tsnet, and nothing touches
`LauncherPreferences`. Home and Lock aren't direct-boot-aware. `InCallActivity` never dismisses the keyguard and
finishes once no call is left. The system's `ACTION_USER_UNLOCKED` reaches a `RECEIVER_NOT_EXPORTED` receiver because
the system uid is allowed. A crash in a direct-boot-aware component can't stop the keyguard (SystemUI) or block unlock.
I found no path to a BFU lockout.

1. **should-fix (inferred): crash at first unlock when the process was started before unlock.**
   `Application.kt:174-201`. When `BootCallReceiver` or Telecom started the process before unlock, it's often still
   alive at unlock. Components that aren't direct-boot-aware can run in it before `initUnlocked`: the system starts
   always-on VPN `KidVpnService` on unlock (`KidVpnService.kt:151`), and so can `CommandListenerService.onCreate`
   (`:109`), `PackageReplacedReceiver:37`, `AppInstallReceiver:87` and `UnifiedPushRegistrationReceiver:42`. Two
   things trigger `initUnlocked`: the `ACTION_USER_UNLOCKED` broadcast and an activity pre-create hook. Neither is
   ordered before service or receiver delivery. The generated `LauncherPreferences.mdm()` then throws
   `IllegalStateException("not yet been initialized")`, which goes to the default handler and `exitProcess(1)`. That
   shows a crash notification and briefly takes the call services down. A second gap: PackageManager resolves
   non-aware components once the user is "unlocking", but `isUserUnlocked()` is still false then, so `runIfUnlocked`
   does nothing even from the activity hook. Not a lockout, because the process restarts unlocked and is clean.
   Device check 5 ("no crash notification") would catch it.
   **Fix:** trigger the deferred init from an `AppComponentFactory` (`instantiateService`/`Receiver`/`Activity` for any
   non-call component), or call one `ensureUnlockedInit()` first thing in every non-aware component. On that path,
   gate on "a non-aware component exists, so CE is readable", not on `isUserUnlocked()`.

2. **should-fix: a failed or unusable DE mirror is silent and isn't retried.** `CallPolicyStore.kt:79-83`.
   `SharedPreferencesImpl.commit()` updates its in-memory map before the disk write. After a failed `commit()`, the
   `bootPolicyRewrite` check at `:82` compares against the new value, returns null, and nothing is retried until the
   process restarts. A reboot in between leaves the old rules in DE. Example: the parent turns calls off, but the
   rules from before still apply before unlock. Separately, `BootCallPolicy.kt:97` rejects the whole file if any one
   number isn't a fixed point of `normalize`. Server output always is one. But an empty `RuleContact.number`
   (default `""`) or any future normalisation drift would make every boot fail closed, and that would only show up
   before unlock, as parents being rejected. Nothing reaches the status report.
   **Fix:** keep the last string that was really committed in a field and compare against that, not against
   `prefs.getString`. After writing, check that `bootPolicyState(decodeBootPolicy(written))` makes the same decisions
   as `derived`. Report `bootPolicy: ok|write_failed|unreadable` in `callState`.

3. **note (pre-existing CE fail-open, now kept in DE): a wiped CE reads as "unmanaged".** `resetPreferences`
   (`Application.kt:212`, `Preferences.kt:85`) calls `LauncherPreferences.clear()` (`Preferences.kt:91`, which is
   `edit().clear()` on the whole default file). That deletes the policy cache, `calls_managed_last` and
   `last_call_rules`, so `callPolicyState` returns `Unmanaged` (`CallPolicyStates.kt:61`). The next refresh writes
   `"mode":"unmanaged"` to DE, which allows every call before unlock. DE inherits the CE bug and has no ratchet of its
   own. Optional: only move DE from managed to unmanaged when the cached policy explicitly says `managed:false`.
   Better: fix the CE reset path, which also wipes enrolment.

4. **note (emergency, partly inferred): gaps in the callback window before unlock.** Before unlock, only the DE record
   can open the window (`CallSystem.kt:117-127`). (a) The record dies with the boot count
   (`RestrictionsPause.kt:35`). So if the phone reboots during or after a 112 call (battery dies), a PSAP callback
   before unlock is rejected; 02-calls.md doesn't mention this case. (b) The record only exists if our in-call
   service saw the call connect (the documented residual risk). Device check 4 should also record whether Telecom
   binds our `KidInCallService` for an emergency call placed from the keyguard before unlock. Mitigation to verify:
   let the backstop (`KidInCallService.kt:57`) allow calls carrying `Call.Details.PROPERTY_NETWORK_IDENTIFIED_EMERGENCY_CALL`
   or `PROPERTY_EMERGENCY_CALLBACK_MODE`, if the Jelly Star sets them. Outgoing emergency calls before unlock are
   fine: the platform check is ORed with the static list, and a fail-closed DE falls back to cc 47.

5. **note: the emergency record moved from CE to DE without migration** (`CallSystem.kt:181,189`). A window that is
   open when the update installs is lost. While unlocked the call log covers it, so this is negligible.

6. **note (attacker with the locked phone).** DE holds the mode, calls on/off, the country code, the allowed numbers
   (no names) and the emergency timestamps. Only our uid or root can read or write it. The release build isn't
   debuggable (no `run-as`) and adb shell can't read `/data/user_de/0/<pkg>`. Turning DE "unmanaged" (allow all
   before unlock) needs root, which is accepted. UI reachable before unlock: `InCallActivity` (not exported, no
   navigation, only during an allowed call) and `CallActionReceiver` (not exported, immutable PendingIntents).
   Nothing else.

7. **note (inferred):** when `InCallActivity` is the first activity after unlock in a process started before unlock,
   `onActivityPreCreated` runs the whole `initRest` (migrations, `loadApps`, service starts) on the main thread
   before the in-call screen draws. That delays answering a ringing call. Consider posting `initUnlocked`.

8. **should-fix (tests).** `BootCallPolicyTest` covers the codec well: shape, no names, version, corrupt and missing
   copies, round trip, rewrite only on change. Gaps:
   (a) Nothing tests `CallPolicyStore`'s routing: locked reads DE only; unlocked reads CE and mirrors; a CE read
   failure leaves the state and DE untouched; `ensureLoaded` upgrades from the boot copy to CE. Extract a pure
   `refreshPlan(unlocked, ceResult, deString)` and test it.
   (b) `BootCallPolicyTest.kt:122` passes `{ true }` as the callback-window lambda, so it says nothing about the
   window before unlock. Give `CallSystem.callbackWindowUntil` an `unlocked` seam and assert the call log isn't read
   while locked.
   (c) The round trip only covers `presentation=true`, `verificationFailed=false` and cc 47. Add short numbers, a
   failed verification and another cc.
   (d) No test for finding 2 (a CE state whose DE copy decodes as Corrupt).
