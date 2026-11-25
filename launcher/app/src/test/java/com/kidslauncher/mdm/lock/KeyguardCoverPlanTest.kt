package com.kidslauncher.mdm.lock

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The one keyguard writer (design 32 step 2's H2/M6, design 59's ANDROID): the mode, the features Android's lock adds and
 * takes out again, T following the screen timeout (H1), and the pins that keep the one writer. Design 132: the keyguard
 * cover (COVER) and our own lock's disabled keyguard (DISABLED) are gone - nothing writes `setKeyguardDisabled(true)`.
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

    @Test
    fun `keyguardMode - ANDROID with a credential on a managed phone, UNTOUCHED otherwise, never COVER or DISABLED (design 132)`() {
        assertEquals(KeyguardMode.ANDROID, keyguardMode(managed = true, deviceSecure = true))
        assertEquals(KeyguardMode.UNTOUCHED, keyguardMode(managed = true, deviceSecure = false))
        assertEquals(KeyguardMode.UNTOUCHED, keyguardMode(managed = false, deviceSecure = true))
        assertEquals(KeyguardMode.UNTOUCHED, keyguardMode(managed = false, deviceSecure = false))
        assertEquals(setOf(KeyguardMode.UNTOUCHED, KeyguardMode.ANDROID), KeyguardMode.entries.toSet())
    }

    @Test
    fun `off - exactly Android's lock's bits out, a parent's kept, SECURE_CAMERA by the managed rule`() {
        val (on, added) = keyguardDisabledFeatures(16, managed = true, coverAdded = 0, android = true)
        assertEquals(16 or 2 or ANDROID_KEYGUARD_FEATURES, on)
        assertEquals((16 or 2) to 0, keyguardDisabledFeatures(on, managed = true, coverAdded = added, android = false))
        // Unmanaged: minus SECURE_CAMERA too.
        assertEquals(16 to 0, keyguardDisabledFeatures(on, managed = false, coverAdded = added, android = false))
        // Never Android's lock: OwnPermissions' rule.
        assertEquals((16 or 2) to 0, keyguardDisabledFeatures(16, managed = true, coverAdded = 0, android = false))
        assertEquals(16 to 0, keyguardDisabledFeatures(16 or 2, managed = false, coverAdded = 0, android = false))
    }

    @Test
    fun `T is the effective timeout, 0 with none known`() {
        assertEquals(30_000L, maxTimeToLock(30_000L))
        assertEquals("Release: the phone's own value", 600_000L, maxTimeToLock(600_000L))
        assertEquals(0L, maxTimeToLock(null))
        assertEquals(0L, maxTimeToLock(0L))
    }

    @Test
    fun `growing writes the setting first, shrinking writes T first`() {
        assertEquals(TimeoutWriteOrder.SETTING_FIRST, timeoutWriteOrder(30_000L, 120_000L))
        assertEquals(TimeoutWriteOrder.T_FIRST, timeoutWriteOrder(120_000L, 15_000L))
        assertEquals(TimeoutWriteOrder.T_FIRST, timeoutWriteOrder(null, 15_000L))
        assertEquals(TimeoutWriteOrder.T_FIRST, timeoutWriteOrder(15_000L, 15_000L))
    }

    private class FakeDpm(var features: Int = 2, var t: Long = 0L, var disabled: Boolean = true) : KeyguardPolicy {
        val calls = mutableListOf<String>()
        override fun readFeatures(): Int {
            calls += "features?"
            return features
        }
        override fun writeFeatures(value: Int) {
            calls += "features=$value"
            features = value
        }
        override fun readMaxTimeToLock(): Long {
            calls += "t?"
            return t
        }
        override fun writeMaxTimeToLock(ms: Long) {
            calls += "t=$ms"
            t = ms
        }
        override fun writeDisabled(disabled: Boolean): Boolean {
            calls += "disabled=$disabled"
            this.disabled = disabled
            return true
        }
    }

    @Test
    fun `UNTOUCHED - setKeyguardDisabled(false), which undoes a keyguard an older build left disabled (G3)`() {
        val dpm = FakeDpm(features = 2, disabled = true)
        val w = writeKeyguard(dpm, KeyguardMode.UNTOUCHED, managed = true, coverAdded = 0, timeoutMs = { 30_000L }, storeCoverAdded = { true })
        assertEquals(KeyguardMode.UNTOUCHED, w.mode)
        assertFalse(dpm.disabled)
        assertEquals(false, w.disabled)
        assertEquals(listOf("disabled=false", "features?", "t?"), dpm.calls)
        // ANDROID too.
        val android = FakeDpm(disabled = true)
        writeKeyguard(android, KeyguardMode.ANDROID, managed = true, coverAdded = 0, timeoutMs = { 30_000L }, storeCoverAdded = { true })
        assertFalse(android.disabled)
        assertFalse(android.calls.contains("disabled=true"))
    }

    @Test
    fun `nothing in the launcher writes setKeyguardDisabled(true) any more (design 132)`() {
        for (f in mainSources) assertFalse(f.name, code(f).contains("setKeyguardDisabled(admin, true"))
        for (f in mainSources) assertFalse(f.name, Regex("""writeDisabled\(true""").containsMatchIn(code(f)))
    }

    @Test
    fun `the three keyguard setters are called only by the one writer`() {
        val callers = mainSources.filter { f ->
            val c = code(f)
            listOf(".setKeyguardDisabled(", ".setKeyguardDisabledFeatures(", ".setMaximumTimeToLock(").any { c.contains(it) }
        }.map { it.name }
        assertEquals(listOf("KeyguardCover.kt"), callers)
        val cover = code("$lock/KeyguardCover.kt")
        // nit 2: what threw is logged.
        assertTrue(body(cover, "write").contains("for ((what, e) in written.errors) Log.w(LOG_TAG, what, e)"))
        assertTrue(body(cover, "configure").contains("val wanted = keyguardMode(managed, deviceSecure)"))
        // configure's keyguard is the writer's, after the guard re-arm.
        val configure = body(code("$lock/PinLockRuntime.kt"), "configure")
        assertTrue(configure.contains("KeyguardCover.configure(app, dpm, admin, managed, kidPin, deviceSecure, guard.trippedAtMs != null)"))
        assertTrue(configure.indexOf("guardRearm(") < configure.indexOf("KeyguardCover.configure("))
        // AppEnforcer no longer writes the features itself.
        assertFalse(code("java/com/kidslauncher/mdm/server/AppEnforcer.kt").contains("applyKeyguardFeatures"))
    }

    @Test
    fun `the screen timeout's writes go through Android's lock's T, in the safe order`() {
        val timeout = code("java/com/kidslauncher/mdm/server/ScreenTimeout.kt")
        assertEquals(1, Regex("""setSystemSetting\(admin, Settings\.System\.SCREEN_OFF_TIMEOUT""").findAll(timeout).count())
        assertTrue(body(timeout, "write").contains("KeyguardCover.followTimeout(context, dpm, admin, currentMs(context), millis)"))
        assertTrue(timeout.contains("if (KeyguardCover.timeToLockFollows) {\n            ensureObserver(context.applicationContext)"))
        val follow = body(code("$lock/KeyguardCover.kt"), "followTimeout")
        assertTrue(follow.contains("if (!timeToLockFollows) return@synchronized writeSetting()"))
        assertTrue(follow.indexOf("TimeoutWriteOrder.T_FIRST) setT(") in 0 until follow.indexOf("val ok = writeSetting()"))
        assertTrue(follow.indexOf("val ok = writeSetting()") < follow.indexOf("setT(dpm, admin, ScreenTimeout.effectiveMs(app)"))
    }

    @Test
    fun `requestDismissKeyguard only for Android's PIN pad`() {
        val callers = mainSources.filter { code(it).contains("requestDismissKeyguard(") }.map { it.name }.sorted()
        assertEquals(listOf("BootCoverActivity.kt", "PinLockActivity.kt"), callers)
        // Design 59d step F: the boot cover asks once, behind its gate (BootCoverManifestTest pins the body).
        assertEquals(1, Regex("requestDismissKeyguard\\(").findAll(code("$lock/BootCoverActivity.kt")).count())
        val lockActivity = code("$lock/PinLockActivity.kt")
        // Design 132: only "Lås opp" (Android's PIN pad) - the keyguard cover's dismissal went.
        assertEquals(1, Regex("requestDismissKeyguard\\(").findAll(lockActivity).count())
        assertTrue(body(lockActivity, "askAndroid").contains("requestDismissKeyguard(this, askCallback(id, offs))"))
    }

    @Test
    fun `the VoIP call screen and the time-rule start go at once - nothing waits for a cover`() {
        val runtime = code("$lock/PinLockRuntime.kt")
        val dispatch = body(runtime, "dispatch")
        // L5: InCallActivity is showWhenLocked - at once, never held during a live call.
        assertTrue(dispatch.contains("if (result.showCall) showCall(context)\n"))
        assertTrue(dispatch.contains("if (result.showVoipCall && mode == LockMode.LOCKED && !ourCall() && VoipCalls.phase == VoipPhase.IN_CALL) {"))
        assertEquals(1, Regex("VoipCalls\\.reopenCall\\(").findAll(runtime).count())
        assertEquals(1, Regex("showCall\\(").findAll(dispatch).count())
        assertFalse(runtime.contains("whenUnlocked"))
        assertFalse(code("java/com/kidslauncher/mdm/timerules/TimeRulesRuntime.kt").contains("whenUnlocked"))
        val cover = code("$lock/KeyguardCover.kt")
        assertFalse(cover.contains("coverOn"))
        assertFalse(cover.contains("mustWait"))
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
