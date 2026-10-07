# QA 16b code review (commit 3025503b, 2026-10-07)

Checked against the 16 doc (QA #7-#12, decisions) and AOSP android16 PMS/CE/PAH/PolicyEnforcerCallbacks. **No soft-brick path found**: manifest-disabled by default; only ACTION_SHUTDOWN enables, and only while wanted + DO; a disabled cover's PPA is skipped (not in the query, CE `findPersistentPreferredActivity`), HomeActivity's PPA is never cleared or replaced (distinct filter, PAH appends; same package, so ROLE_HOME stays); the cover and the main process (before `BootHome`) both disable it after the unlock, so even an uncounted (native/pre-onCreate) crash loop ends at the unlock; `:bootcover` runs nothing of `Application.onCreate`, `instance` stays null, no provider of ours is installed there, DE prefs only, theme/AVD/colour are APK resources. Server: migration 0045 default 0, always sent, snapshot test, `#kiosk-escapes` redirect + scroll-restore (no jump). Worktree of HEAD: the six touched launcher test classes, server `cargo test` (250) and `cargo fmt --check` pass.

1. **Medium - the hand-over doesn't finish the cover.** `BootCoverActivity.kt:52` only disables (DONT_KILL_APP). AMS removes a disabled activity when it handles the PACKAGE_CHANGED broadcast (`cleanupDisabledPackageComponentsLocked`), and PMS `setEnabledSettings` defers DONT_KILL_APP broadcasts 1 s - 10 s (`BROADCAST_DELAY_DURING_STARTUP`) in the first 60 s after systemReady, i.e. at every credential-less boot. Where A starts no Home (calls-only managed: `bringHomeAtBoot` needs allowlist, kiosk or PIN lock; a live call) the mark stays ~11 s after the unlock, not 1 s; "AMS finishes it" (16 doc :94, :182) is wrong. Fix: after the disable, `finish()` once `getComponentEnabledSetting` reads DISABLED (as AOSP FallbackHome does) - the system re-resolves HOME at once to HomeActivity, HOME-typed; time it in the device check.
2. **Medium - the crash guard is per boot and invisible.** `BootCover.kt:79` re-arms at every shutdown, so a cover that always crashes crashes twice ("keeps stopping" dialog) at every boot before A+B, and nobody learns it. Crashes before `onCreate` (`BootCoverActivity.kt:56`: factory, `Application`, `attach`) aren't counted. Fix: install the counter in `Application.onCreate`'s `:bootcover` branch (`Application.kt:129`); the shutdown receiver doesn't arm while DE `boot_cover_guard` holds a trip (sticky until the switch goes off and on); report it (5).
3. **Low - arm durability.** The shutdown enable (`BootCover.kt:101`) lacks `PackageManager.SYNCHRONOUS`: AOSP flushes it in `pm.shutdown()` after the broadcast (PMS `shutdown()`), OEM shutdown paths may not - add it (minSdk 34). A `HomeFront.bring` after ACTION_SHUTDOWN (the lock's re-front loop) can resolve to the armed cover, which disarms itself after 1 s: skip `HomeFront.bring` once the shutdown receiver ran (fail-safe either way).
4. **Low - `onPause` order** (`BootCoverActivity.kt:85-86`): `removeCallbacksAndMessages` runs before `logo?.stop()`, whose `onAnimationEnd` posts the next breath 700 ms later - the AVD restarts while paused/under Home. Stop first, or check a resumed flag in `loop`.
5. **Low - server: no compat or status for the switch.** A pre-16b launcher ignores `boot_cover` silently and the card shows nothing, so "Test it on this phone first" relies on watching the phone. Fix: a capability (e.g. `boot_cover_v1`) with the "update the launcher" warning, and a reported state (armed at the last shutdown, handed over by, guard tripped) on the card.
6. **Low - docs/comments.** `adb reboot` goes through init's `sys.powerctl`, not ShutdownThread, so it sends no ACTION_SHUTDOWN: the 16b checks need the power menu or `adb shell svc power reboot` (16 doc :198, `docs/testing/emulator.md`). `HomeFront.kt:16` still says "our single HOME activity"; `styles.xml:44` the PIN lock's comment now heads `BootCoverTheme`.

Verdict: safe to merge with the switch off; fix 1 and 2 before switching it on on a phone.

## Resolution (2026-10-07)

1. **Fixed.** `BootCoverActivity.handOverNow`: disable, then `finish()` as soon as `getComponentEnabledSetting`
   reads disabled (retried every 200 ms otherwise), like FallbackHome - the system resolves HOME again at once.
2. **Fixed.** The counter is `lock/BootCoverGuard.install`, called first in `Application.onCreate`'s `:bootcover`
   branch (the only thing that branch does). The record (pure `CoverRecord`, `encodeCoverRecord`/`decodeCoverRecord`)
   is one AtomicFile in device-protected storage, read fresh by both processes (no SharedPreferences: their per-process
   caches would overwrite each other); an unreadable file counts as tripped. The 2nd crash in a boot sets `tripped`,
   which stays: `bootCoverEnabled(SHUTDOWN, wanted, guardTripped)` doesn't arm, and only the switch going off
   (`BootCover.applyPolicy` with `wanted = false` deletes the record) and on again re-arms it. Reported as
   `boot_cover.tripped` (5).
3. **Fixed.** The shutdown enable uses `DONT_KILL_APP | SYNCHRONOUS`; `BootCover.shuttingDown` is set first in the
   receiver and `HomeFront.bring` starts no Home after it.
4. **Fixed.** `onPause` stops the AVD before dropping the callbacks, and the breath loop only restarts while resumed.
5. **Fixed.** Capability `boot_cover_v1`; status `boot_cover` {`wanted`, `tripped`, `last_armed_at_ms`,
   `last_shown_at_ms`, `last_handover` ("cover"/"launcher"), `last_handover_at_ms`} (always sent; server
   `kiosk_escapes::BootCoverState`, migration 0046 `device_status.boot_cover_json`, known fields only, the hand-over
   word checked, times clamped). The card says "update the launcher" for an older one, shows the last start, and
   warns about a tripped guard or an arm that wasn't shown at the next start.
6. **Fixed.** The 16 doc and `docs/testing/emulator.md` say `adb reboot` sends no `ACTION_SHUTDOWN` (power menu or
   `adb shell svc power reboot`; or `adb shell am broadcast` can't send the protected broadcast - use a real
   shutdown); `HomeFront`'s comment and the `styles.xml` comment order are fixed.
