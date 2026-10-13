package com.kidslauncher.mdm.lock

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Design 32 step 2 (docs/design/32-lock-flash.md §8 step 2, §9): the keyguard cover behind the debug
 * switch - the mode, the safe write order with its fallback (H2), the features the cover clears again
 * (M6), T following the screen timeout (H1), the dismissal by state (M7), the screens that wait for the
 * unlock (M5), the dark lock-up after a call - and the pins that keep the one writer.
 */
class KeyguardCoverPlanTest {
    private fun file(path: String) = listOf("src/main/$path", "app/src/main/$path").map(::File).first { it.exists() }
    private fun code(f: File) = f.readText()
        .replace(Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL), "")
        .replace(Regex("//[^\\n]*"), "")
    private fun code(path: String) = code(file(path))
    private fun body(code: String, name: String): String {
        val start = code.indexOf("fun $name(")
        assertTrue("fun $name exists", start >= 0)
        val rest = code.substring(start)
        return rest.substring(0, Regex("""\n    }\n""").find(rest)?.range?.first ?: rest.length)
    }
    private val mainSources get() = file("java").walk().filter { it.isFile && it.extension == "kt" }.toList()
    private val lock = "java/com/kidslauncher/mdm/lock"

    // ---- keyguardMode ---------------------------------------------------------------------------

    @Test
    fun `COVER only with the switch, managed, a PIN, no credential, no VoIP and the guard not tripped`() {
        assertEquals(KeyguardMode.COVER, keyguardMode(true, true, false, coverWanted = true, VoipPhase.NONE, guardTripped = false))
        assertEquals("switch off: today's", KeyguardMode.DISABLED, keyguardMode(true, true, false, false, VoipPhase.NONE, false))
        assertEquals("VoIP ringing (M5)", KeyguardMode.DISABLED, keyguardMode(true, true, false, true, VoipPhase.RINGING, false))
        assertEquals("VoIP in call (M5)", KeyguardMode.DISABLED, keyguardMode(true, true, false, true, VoipPhase.IN_CALL, false))
        assertEquals("the crash guard (L8)", KeyguardMode.DISABLED, keyguardMode(true, true, false, true, VoipPhase.NONE, true))
        assertEquals("the guard without the switch: as today", KeyguardMode.DISABLED, keyguardMode(true, true, false, false, VoipPhase.NONE, true))
    }

    @Test
    fun `UNTOUCHED wherever today's code doesn't disable the keyguard, switch or not`() {
        for (cover in listOf(false, true)) {
            assertEquals(KeyguardMode.UNTOUCHED, keyguardMode(false, true, false, cover, VoipPhase.NONE, false))
            assertEquals(KeyguardMode.UNTOUCHED, keyguardMode(true, false, false, cover, VoipPhase.NONE, false))
            assertEquals("an Android credential", KeyguardMode.UNTOUCHED, keyguardMode(true, true, true, cover, VoipPhase.NONE, false))
        }
    }

    // ---- keyguardDisabledFeatures (M6) ------------------------------------------------------------

    @Test
    fun `cover on - ALL, and coverAdded is what wasn't set before`() {
        assertEquals(KEYGUARD_FEATURES_ALL to (KEYGUARD_FEATURES_ALL and (2 or 16).inv()), keyguardDisabledFeatures(2 or 16, true, cover = true, coverAdded = 0))
        // Already ALL from the cover's own write: what it added is kept, not lost.
        assertEquals(KEYGUARD_FEATURES_ALL to 0x1000, keyguardDisabledFeatures(KEYGUARD_FEATURES_ALL, true, cover = true, coverAdded = 0x1000))
    }

    @Test
    fun `cover off - exactly its bits out, a parent's kept, SECURE_CAMERA by the managed rule`() {
        val before = 2 or 16
        val (_, added) = keyguardDisabledFeatures(before, managed = true, cover = true, coverAdded = 0)
        assertEquals(before to 0, keyguardDisabledFeatures(KEYGUARD_FEATURES_ALL, managed = true, cover = false, coverAdded = added))
        // Managed: B2's camera bit comes back even if it was among the cover's.
        assertEquals((16 or 2) to 0, keyguardDisabledFeatures(KEYGUARD_FEATURES_ALL, managed = true, cover = false, coverAdded = KEYGUARD_FEATURES_ALL and 16.inv()))
        // Unmanaged: as today, minus SECURE_CAMERA, minus the cover's bits.
        assertEquals(16 to 0, keyguardDisabledFeatures(KEYGUARD_FEATURES_ALL, managed = false, cover = false, coverAdded = KEYGUARD_FEATURES_ALL and 16.inv()))
        // No cover ever: today's rule (OwnPermissions' keyguardDisabledFeatures).
        assertEquals((16 or 2) to 0, keyguardDisabledFeatures(16, managed = true, cover = false, coverAdded = 0))
        assertEquals(16 to 0, keyguardDisabledFeatures(16 or 2, managed = false, cover = false, coverAdded = 0))
    }

    // ---- T (H1) ------------------------------------------------------------------------------------

    @Test
    fun `T is the effective timeout under the cover, 0 otherwise`() {
        assertEquals(30_000L, maxTimeToLock(coverOn = true, effectiveTimeoutMs = 30_000L))
        assertEquals("Release: the phone's own value", 600_000L, maxTimeToLock(true, 600_000L))
        assertEquals(0L, maxTimeToLock(coverOn = false, effectiveTimeoutMs = 30_000L))
        assertEquals(0L, maxTimeToLock(true, null))
        assertEquals(0L, maxTimeToLock(true, 0L))
    }

    @Test
    fun `growing writes the setting first, shrinking writes T first`() {
        assertEquals(TimeoutWriteOrder.SETTING_FIRST, timeoutWriteOrder(30_000L, 120_000L))
        assertEquals(TimeoutWriteOrder.T_FIRST, timeoutWriteOrder(120_000L, 15_000L))
        assertEquals(TimeoutWriteOrder.T_FIRST, timeoutWriteOrder(null, 15_000L))
        assertEquals(TimeoutWriteOrder.T_FIRST, timeoutWriteOrder(15_000L, 15_000L))
    }

    // ---- writeKeyguard with a fake DPM (H2) -----------------------------------------------------------

    private class FakeDpm(
        var features: Int = 2,
        var t: Long = 0L,
        var disabled: Boolean = true,
        val failAt: String? = null,
        val readBackFeatures: Int? = null,
        val readBackT: Long? = null,
    ) : KeyguardPolicy {
        val calls = mutableListOf<String>()
        override fun readFeatures(): Int {
            calls += "features?"
            return features
        }
        override fun writeFeatures(value: Int) {
            calls += "features=$value"
            if (failAt == "setFeatures") throw SecurityException("no disable-keyguard-features")
            // A DPM that keeps something else than it was given (the read-back check).
            features = if (value == KEYGUARD_FEATURES_ALL) readBackFeatures ?: value else value
        }
        override fun readMaxTimeToLock(): Long {
            calls += "t?"
            return t
        }
        override fun writeMaxTimeToLock(ms: Long) {
            calls += "t=$ms"
            if (failAt == "setT" && ms != 0L) throw SecurityException("no force-lock")
            t = if (ms != 0L) readBackT ?: ms else ms
        }
        override fun writeDisabled(disabled: Boolean): Boolean {
            calls += "disabled=$disabled"
            if (failAt == "disabledFalse" && !disabled) throw IllegalStateException("refused")
            this.disabled = disabled
            return true
        }
    }

    private fun write(dpm: FakeDpm, mode: KeyguardMode = KeyguardMode.COVER, timeout: Long? = 30_000L, added: Int = 0, stored: MutableList<Int> = mutableListOf(), storeOk: Boolean = true) =
        writeKeyguard(dpm, mode, managed = true, coverAdded = added, timeoutMs = { timeout }) { stored += it; storeOk }

    @Test
    fun `COVER - features, read back, T, read back, then false`() {
        val dpm = FakeDpm()
        val stored = mutableListOf<Int>()
        val w = write(dpm, stored = stored)
        assertEquals(KeyguardMode.COVER, w.mode)
        assertTrue(w.lockMayRun)
        assertNull(w.coverFailed)
        assertEquals(listOf("features?", "features=$KEYGUARD_FEATURES_ALL", "features?", "t?", "t=30000", "t?", "disabled=false"), dpm.calls)
        assertEquals("coverAdded stored before the write", listOf(KEYGUARD_FEATURES_ALL and 2.inv()), stored)
        assertEquals(30_000L, dpm.t)
        assertFalse(dpm.disabled)
    }

    private fun assertFellBack(dpm: FakeDpm, w: KeyguardWritten, step: String) {
        assertEquals(KeyguardMode.DISABLED, w.mode)
        assertTrue("the PIN lock stays active (H2)", w.lockMayRun)
        assertEquals(step, w.coverFailed)
        assertTrue("today's disabled keyguard", dpm.disabled)
        assertFalse("never false after a failure", dpm.calls.contains("disabled=false"))
        assertEquals(0L, dpm.t)
    }

    @Test
    fun `a features write that throws or reads back wrong - DISABLED with the lock active`() {
        FakeDpm(failAt = "setFeatures").let { assertFellBack(it, write(it), "features") }
        val wrong = FakeDpm(readBackFeatures = 2)
        val w = write(wrong)
        assertFellBack(wrong, w, "features")
        assertEquals("the cover's bits taken out again", 2, wrong.features)
    }

    @Test
    fun `a max-time-to-lock failure, a missing timeout and a false failure - DISABLED with the lock active`() {
        FakeDpm(failAt = "setT").let { assertFellBack(it, write(it), "max_time_to_lock") }
        FakeDpm(readBackT = 5_000L).let { assertFellBack(it, write(it), "max_time_to_lock") }
        FakeDpm().let { assertFellBack(it, write(it, timeout = null), "timeout") }
        FakeDpm(failAt = "disabledFalse").let {
            val w = write(it)
            assertEquals(KeyguardMode.DISABLED, w.mode)
            assertTrue(w.lockMayRun)
            assertEquals("disabled", w.coverFailed)
            assertTrue(it.disabled)
            assertEquals(2, it.features)
        }
        // coverAdded can't be kept: nothing written at all but the fallback.
        FakeDpm().let {
            val w = write(it, storeOk = false)
            assertEquals("cover_added", w.coverFailed)
            assertFalse(it.calls.any { c -> c.startsWith("features=") })
            assertTrue(it.disabled)
        }
    }

    @Test
    fun `DISABLED - true first, the cover's bits and T out, as today without a cover`() {
        val dpm = FakeDpm(features = KEYGUARD_FEATURES_ALL, t = 30_000L, disabled = false)
        val stored = mutableListOf<Int>()
        val w = write(dpm, mode = KeyguardMode.DISABLED, added = KEYGUARD_FEATURES_ALL and (2 or 16).inv(), stored = stored)
        assertEquals("disabled=true", dpm.calls.first())
        assertEquals(2 or 16, dpm.features)
        assertEquals(0L, dpm.t)
        assertEquals(listOf(0), stored)
        assertTrue(w.lockMayRun)
        // Release after step 1: features already right, no T - only the true call writes.
        val plain = FakeDpm(features = 2, t = 0L)
        write(plain, mode = KeyguardMode.DISABLED)
        assertEquals(listOf("disabled=true", "features?", "t?"), plain.calls)
    }

    @Test
    fun `UNTOUCHED - false as today, the lock not allowed to run on it`() {
        val dpm = FakeDpm(features = 2)
        val w = write(dpm, mode = KeyguardMode.UNTOUCHED)
        assertEquals(KeyguardMode.UNTOUCHED, w.mode)
        assertFalse(w.lockMayRun)
        assertFalse(dpm.disabled)
        assertEquals(listOf("disabled=false", "features?", "t?"), dpm.calls)
    }

    // ---- the dismissal (M7) ----------------------------------------------------------------------------

    @Test
    fun `dismissDue - the cover, resumed, the screen on, locked, drawn, not asked yet`() {
        assertTrue(dismissDue(true, true, true, true, true, false))
        assertFalse(dismissDue(false, true, true, true, true, false))
        assertFalse(dismissDue(true, false, true, true, true, false))
        assertFalse("dark: the lock's sync draw at a lock-up", dismissDue(true, true, false, true, true, false))
        assertFalse("already unlocked", dismissDue(true, true, true, false, true, false))
        assertFalse("before the first frame", dismissDue(true, true, true, true, false, false))
        assertFalse("asked in this resume", dismissDue(true, true, true, true, true, true))
    }

    @Test
    fun `dismissFailed - by state a second after the request, with or without a callback`() {
        assertTrue(dismissFailed(requestedAtMs = 1_000L, nowMs = 2_000L, keyguardLocked = true))
        assertFalse("too early", dismissFailed(1_000L, 1_999L, true))
        assertFalse("unlocked", dismissFailed(1_000L, 5_000L, false))
        assertFalse("no request", dismissFailed(null, 5_000L, true))
    }

    @Test
    fun `the fallback at the 2nd failure, the re-arm after the fallback's wake`() {
        assertFalse(dismissFallback(1))
        assertTrue(dismissFallback(2))
        assertTrue(coverRearmDue(heldOff = true, interactive = true, lockResumed = true, wakePending = false))
        assertFalse("not held off", coverRearmDue(false, true, true, false))
        assertFalse("dark", coverRearmDue(true, false, true, false))
        assertFalse("the fallback's own wake", coverRearmDue(true, true, true, true))
        assertFalse(coverRearmDue(true, true, false, false))
    }

    @Test
    fun `other apps' screens wait while the cover's keyguard is locked`() {
        assertTrue(sendAfterUnlock(coverOn = true, keyguardLocked = true))
        assertFalse(sendAfterUnlock(coverOn = true, keyguardLocked = false))
        assertFalse("no cover: as today", sendAfterUnlock(coverOn = false, keyguardLocked = true))
    }

    @Test
    fun `Home's boot dismissal - only the switch, before the arm, no credential, while locked`() {
        assertTrue(homeBootDismissDue(switchOn = true, armed = false, deviceSecure = false, keyguardLocked = true))
        assertFalse(homeBootDismissDue(false, false, false, true))
        assertFalse(homeBootDismissDue(true, true, false, true))
        assertFalse("never the bouncer", homeBootDismissDue(true, false, true, true))
        assertFalse("SystemUI hasn't shown it yet", homeBootDismissDue(true, false, false, false))
    }

    // ---- the lock-up under the cover, the dark lock-up (M5) ----------------------------------------------

    @Test
    fun `under the cover the lock-up starts the lock alone`() {
        assertEquals(LockUpStart.LOCK, lockUpStarts(showLock = true, lockOnTop = false, callLive = false, interactive = false, coverOn = true))
        assertEquals(LockUpStart.NONE, lockUpStarts(showLock = true, lockOnTop = true, callLive = false, interactive = false, coverOn = true))
        assertEquals(LockUpStart.NONE, lockUpStarts(showLock = false, lockOnTop = false, callLive = false, interactive = false, coverOn = true))
        assertEquals(LockUpStart.HOME_THEN_LOCK, lockUpStarts(showLock = true, lockOnTop = false, callLive = false, interactive = false, coverOn = false))
    }

    private val dark = RefrontInputs(locked = true, lockResumed = false, interactive = false, ourCall = false, telecomInCall = false, emergencyFlow = false, alarmRinging = false)

    @Test
    fun `refrontAction - dark, LOCKED, not on top, nothing exempt, the cover on - DarkLockUp`() {
        assertEquals(RefrontAction.DarkLockUp, refrontAction(dark.copy(coverOn = true), 0))
        assertEquals("the cover off: as before", RefrontAction.Stop, refrontAction(dark, 0))
        assertEquals("the lock on top", RefrontAction.Stop, refrontAction(dark.copy(coverOn = true, lockOnTop = true), 0))
        assertEquals("the system dialer's call goes on: keep looking", RefrontAction.Yield("system_call"), refrontAction(dark.copy(coverOn = true, telecomInCall = true), 0))
        assertEquals(RefrontAction.Yield("voip"), refrontAction(dark.copy(coverOn = true, voipCall = true), 0))
        assertEquals(RefrontAction.Stop, refrontAction(dark.copy(coverOn = true, locked = false), 0))
        assertEquals(RefrontAction.Stop, refrontAction(dark.copy(coverOn = true, lockResumed = true), 0))
        assertTrue("the screen on: unchanged", refrontAction(dark.copy(coverOn = true, interactive = true), 0) is RefrontAction.Refront)
    }

    // ---- source pins ------------------------------------------------------------------------------------

    @Test
    fun `the three keyguard setters are called only by the one writer`() {
        val callers = mainSources.filter { f ->
            val c = code(f)
            listOf(".setKeyguardDisabled(", ".setKeyguardDisabledFeatures(", ".setMaximumTimeToLock(").any { c.contains(it) }
        }.map { it.name }
        assertEquals(listOf("KeyguardCover.kt"), callers)
        val cover = code("$lock/KeyguardCover.kt")
        // The shutdown (M4) and the crash guard (L8) route through disableNow.
        assertEquals(1, Regex("""\.setKeyguardDisabled\(admin, true\)""").findAll(cover).count())
        assertTrue(body(cover, "disableNow").contains("dpm.setKeyguardDisabled(admin, true)"))
        assertTrue(body(cover, "disableNow").contains("if (!coverOn) return"))
        assertTrue(body(cover, "onShutdown").contains("disableNow(context, \"shutdown\", synchronous = true)"))
        assertTrue(body(cover, "guardTripped").contains("disableNow(context, \"crash guard\", synchronous = false)"))
        assertTrue(body(code("$lock/BootCover.kt"), "onReceive").contains("KeyguardCover.onShutdown(app)"))
        assertTrue(body(code("$lock/PinLockRuntime.kt"), "guardTripped").contains("KeyguardCover.guardTripped(app)"))
        // configure's keyguard is the writer's, after the guard re-arm.
        val configure = body(code("$lock/PinLockRuntime.kt"), "configure")
        assertTrue(configure.contains("val keyguardDisabled = KeyguardCover.configure(app, dpm, admin, managed, newConfig != null, deviceSecure, guard.trippedAtMs != null)"))
        assertTrue(configure.indexOf("guardRearm(") < configure.indexOf("KeyguardCover.configure("))
        assertTrue(configure.contains("keyguardDisabled = keyguardDisabled"))
        // AppEnforcer no longer writes the features itself.
        assertFalse(code("java/com/kidslauncher/mdm/server/AppEnforcer.kt").contains("applyKeyguardFeatures"))
    }

    @Test
    fun `the screen timeout's writes go through the cover's T, in the safe order`() {
        val timeout = code("java/com/kidslauncher/mdm/server/ScreenTimeout.kt")
        assertEquals(1, Regex("""setSystemSetting\(admin, Settings\.System\.SCREEN_OFF_TIMEOUT""").findAll(timeout).count())
        assertTrue(body(timeout, "write").contains("KeyguardCover.followTimeout(context, dpm, admin, currentMs(context), millis)"))
        assertTrue(timeout.contains("if (KeyguardCover.coverOn) {\n            ensureObserver(context.applicationContext)"))
        val follow = body(code("$lock/KeyguardCover.kt"), "followTimeout")
        assertTrue(follow.contains("if (!coverOn) return@synchronized writeSetting()"))
        assertTrue(follow.indexOf("TimeoutWriteOrder.T_FIRST) setT(") in 0 until follow.indexOf("val ok = writeSetting()"))
        assertTrue(follow.indexOf("val ok = writeSetting()") < follow.indexOf("setT(app, dpm, admin, ScreenTimeout.effectiveMs(app)"))
    }

    @Test
    fun `requestDismissKeyguard only in the lock and Home's boot path`() {
        val callers = mainSources.filter { code(it).contains("requestDismissKeyguard(") }.map { it.name }.sorted()
        assertEquals(listOf("HomeActivity.kt", "PinLockActivity.kt"), callers)
        val home = code("java/com/kidslauncher/mdm/ui/HomeActivity.kt")
        assertTrue(body(home, "dismissBootKeyguard").contains("homeBootDismissDue(KeyguardCover.switchOn, KeyguardCover.armed, keyguard.isDeviceSecure, keyguard.isKeyguardLocked)"))
        val lockActivity = code("$lock/PinLockActivity.kt")
        assertEquals(1, Regex("requestDismissKeyguard\\(").findAll(lockActivity).count())
        assertTrue(body(lockActivity, "requestDismiss").contains("if (KeyguardCover.dismissRequested())"))
        // After the first frame since the resume, never from the resume itself.
        assertTrue(body(lockActivity, "onResume").contains("watchFirstFrame()"))
        assertTrue(body(lockActivity, "onPause").contains("stopKeyguardWatch()"))
        assertTrue(body(lockActivity, "watchFirstFrame").contains("if (!KeyguardCover.switchOn) return"))
        assertTrue(body(lockActivity, "firstFrame").contains("KeyguardCover.dismissDueNow(this, resumed, drawnSinceResume, dismissTried)"))
    }

    @Test
    fun `the call screens, the ring screen and the time-rule start wait for the unlock`() {
        val runtime = code("$lock/PinLockRuntime.kt")
        val dispatch = body(runtime, "dispatch")
        assertTrue(dispatch.contains("if (result.showCall) KeyguardCover.whenUnlocked(context, \"the call screen\") { showCall(it) }"))
        assertTrue(dispatch.contains("KeyguardCover.whenUnlocked(context, \"the VoIP call screen\") {"))
        assertEquals(1, Regex("VoipCalls\\.reopenCall\\(").findAll(runtime).count())
        assertEquals(1, Regex("showCall\\(").findAll(dispatch).count())
        val fsi = body(runtime, "sendVoipRingScreen")
        assertTrue(fsi.indexOf("if (KeyguardCover.mustWait(context)) return VOIP_FSI_SETTLE_MS") in 0 until fsi.indexOf("VoipCalls.showRingScreen(context)"))
        val recheck = body(code("java/com/kidslauncher/mdm/timerules/TimeRulesRuntime.kt"), "recheck")
        assertTrue(recheck.contains("KeyguardCover.whenUnlocked(context, \"the time-rule screen\") { LockActivity.start(it) }"))
        val cover = code("$lock/KeyguardCover.kt")
        assertTrue(body(cover, "mustWait").contains("sendAfterUnlock(coverOn, keyguardLocked(context))"))
    }

    @Test
    fun `the cover's runtime hooks`() {
        val runtime = code("$lock/PinLockRuntime.kt")
        assertTrue(body(runtime, "onLockResumed").contains("KeyguardCover.onLockResumed(context, interactiveNow)"))
        assertTrue(body(runtime, "onVoipPhase").contains("KeyguardCover.onVoipPhase(app, after, interactive(app))"))
        val refront = body(runtime, "runRefrontCheck")
        assertTrue(refront.contains("coverOn = KeyguardCover.coverOn"))
        assertTrue(refront.contains("lockOnTop = lockOnTop"))
        assertTrue(refront.substringAfter("RefrontAction.DarkLockUp -> {").contains("lockUp(context, LockUpStart.LOCK, LockAsk.AT_ONCE)"))
        val cover = code("$lock/KeyguardCover.kt")
        // M7.4: the re-arm and the VoIP phase are written only from a lock resume with the screen on.
        assertTrue(body(cover, "onVoipPhase").contains("if (!interactive || after == voipSeen) return"))
        assertTrue(body(cover, "onLockResumed").contains("coverRearmDue(heldOff = !armed, interactive = interactive, lockResumed = true, wakePending = wakePending)"))
        assertFalse(runtime.substringAfter("private val screenOffWork = Runnable {").substringBefore("\n    }\n").contains("KeyguardCover"))
        // The cover is written only while armed, and its fallback holds it off until the next re-arm.
        assertTrue(body(cover, "write").contains("coverWanted = switchOn && armed"))
        val fallback = body(cover, "fallback")
        assertTrue(fallback.contains("armed = false") && fallback.contains("wakePending = true"))
    }

    @Test
    fun `the call screens stay showWhenLocked`() {
        val manifest = file("AndroidManifest.xml").readText()
        for (name in listOf(".lock.VoipWakeActivity", ".calls.InCallActivity", ".lock.PinLockActivity")) {
            val decl = manifest.substringAfter("android:name=\"$name\"").substringBefore(">")
            assertTrue(name, decl.contains("android:showWhenLocked=\"true\""))
        }
    }
}
