package com.kidslauncher.mdm.lock

import android.app.admin.DevicePolicyManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Design 59's hybrid prototype (docs/design/59-prototype.md): the face, the gate's decisions, the keyguard's
 * HYBRID write and its rollback, and the pins that keep it behind the debug switch and the kid's way out of
 * the lock through Android's credential only.
 */
class HybridPlanTest {
    private fun file(path: String) = listOf("src/main/$path", "app/src/main/$path").map(::File).first { it.exists() }
    private fun code(path: String) = file(path).readText()
        .replace(Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL), "")
        .replace(Regex("//[^\\n]*"), "")
    private fun body(code: String, name: String): String {
        val start = code.indexOf("fun $name(")
        assertTrue("fun $name exists", start >= 0)
        val rest = code.substring(start)
        return rest.substring(0, Regex("""\n    }\n""").find(rest)?.range?.first ?: rest.length)
    }
    private val lock = "java/com/kidslauncher/mdm/lock"

    @Test
    fun `the features are 59 section 3_1's, fingerprint allowed`() {
        assertEquals(
            DevicePolicyManager.KEYGUARD_DISABLE_WIDGETS_ALL or DevicePolicyManager.KEYGUARD_DISABLE_SECURE_NOTIFICATIONS or
                DevicePolicyManager.KEYGUARD_DISABLE_TRUST_AGENTS or DevicePolicyManager.KEYGUARD_DISABLE_SHORTCUTS_ALL,
            HYBRID_KEYGUARD_FEATURES,
        )
        assertEquals(0, HYBRID_KEYGUARD_FEATURES and DevicePolicyManager.KEYGUARD_DISABLE_BIOMETRICS)
        assertEquals(0, HYBRID_KEYGUARD_FEATURES and DevicePolicyManager.KEYGUARD_DISABLE_UNREDACTED_NOTIFICATIONS)
    }

    @Test
    fun `the face needs the switch, managed, a kid PIN and a credential`() {
        assertTrue(hybridFace(true, true, true, true))
        for (i in 0 until 15) {
            val b = (0..3).map { ((i shr it) and 1) == 1 }
            assertFalse(b.toString(), hybridFace(b[0], b[1], b[2], b[3]))
        }
        assertTrue(hybridMigrationDue(true, true, false))
        assertFalse("a credential already", hybridMigrationDue(true, true, true))
        assertFalse("the switch off", hybridMigrationDue(false, true, false))
        assertFalse("unmanaged", hybridMigrationDue(true, false, false))
    }

    @Test
    fun `Lås opp - our keypad without a live credential, Android's PIN pad while locked, out only when Android is unlocked`() {
        assertEquals(AndroidUnlock.ASK_ANDROID, androidUnlockStep(face = true, deviceSecure = true, androidLocked = true))
        assertEquals(AndroidUnlock.UNLOCK_NOW, androidUnlockStep(face = true, deviceSecure = true, androidLocked = false))
        for (locked in listOf(true, false)) {
            assertEquals("the credential went", AndroidUnlock.OWN_KEYPAD, androidUnlockStep(face = true, deviceSecure = false, androidLocked = locked))
            assertEquals("no face", AndroidUnlock.OWN_KEYPAD, androidUnlockStep(face = false, deviceSecure = true, androidLocked = locked))
        }
        assertTrue(androidUnlockedUs(face = true, deviceSecure = true, androidLocked = false, locked = true, interactive = true))
        assertFalse("Android locked", androidUnlockedUs(true, true, androidLocked = true, locked = true, interactive = true))
        assertFalse("no credential: never past our keypad", androidUnlockedUs(true, deviceSecure = false, androidLocked = false, locked = true, interactive = true))
        assertFalse("no face", androidUnlockedUs(false, true, false, true, true))
        assertFalse("not LOCKED", androidUnlockedUs(true, true, false, locked = false, interactive = true))
        assertFalse("dark", androidUnlockedUs(true, true, false, true, interactive = false))
    }

    @Test
    fun `no re-front while Android guards, the camera lock off as the face`() {
        assertTrue(hybridRefrontHeld(face = true, androidLocked = true))
        assertFalse(hybridRefrontHeld(face = true, androidLocked = false))
        assertFalse(hybridRefrontHeld(face = false, androidLocked = true))
        val base = RefrontInputs(locked = true, lockResumed = false, interactive = true, ourCall = false, telecomInCall = false, emergencyFlow = false, alarmRinging = false)
        assertTrue(refrontAction(base, 0) is RefrontAction.Refront)
        assertEquals(RefrontAction.Stop, refrontAction(base.copy(androidGate = true), 0))
        assertEquals(RefrontAction.Stop, refrontAction(base.copy(androidGate = true, interactive = false, coverOn = true), 0))
        assertTrue(cameraLockEngaged(locked = true, face = false))
        assertFalse(cameraLockEngaged(locked = true, face = true))
        assertFalse(cameraLockEngaged(locked = false, face = false))
    }

    @Test
    fun `Recents is guarded by lock task - running, OVERVIEW and NOTIFICATIONS out, KEYGUARD in`() {
        // LockTaskChrome's kiosk-off LOCKED set: KEYGUARD | GLOBAL_ACTIONS | SYSTEM_INFO.
        assertTrue(recentsGuarded(true, 32 or 16 or 1))
        assertFalse("not in lock task", recentsGuarded(false, 32 or 16 or 1))
        assertFalse("OVERVIEW", recentsGuarded(true, 32 or 8))
        assertFalse("NOTIFICATIONS", recentsGuarded(true, 32 or 2))
        assertFalse("no KEYGUARD", recentsGuarded(true, 16))
        assertFalse("unreadable", recentsGuarded(true, null))
    }

    @Test
    fun `the rollback clears with the token, and only then`() {
        assertEquals(HybridRollback.CLEAR, hybridRollback(deviceSecure = true, tokenActive = true))
        assertEquals(HybridRollback.NO_TOKEN, hybridRollback(deviceSecure = true, tokenActive = false))
        assertEquals(HybridRollback.NOTHING, hybridRollback(deviceSecure = false, tokenActive = true))
    }

    // ---- the keyguard (KeyguardCoverPlan) ------------------------------------------------------------------------

    @Test
    fun `keyguardMode - HYBRID only with the switch and a credential, never the cover with the switch`() {
        assertEquals(KeyguardMode.HYBRID, keyguardMode(true, true, true, false, VoipPhase.NONE, false, hybrid = true))
        assertEquals("the crash guard: Android stays the gate", KeyguardMode.HYBRID, keyguardMode(true, true, true, true, VoipPhase.IN_CALL, true, hybrid = true))
        assertEquals("no credential yet: today's", KeyguardMode.DISABLED, keyguardMode(true, true, false, false, VoipPhase.NONE, false, hybrid = true))
        assertEquals("no cover with the hybrid switch", KeyguardMode.DISABLED, keyguardMode(true, true, false, true, VoipPhase.NONE, false, hybrid = true))
        assertEquals("switched off: as before", KeyguardMode.UNTOUCHED, keyguardMode(true, true, true, false, VoipPhase.NONE, false, hybrid = false))
        assertEquals(KeyguardMode.UNTOUCHED, keyguardMode(false, true, true, false, VoipPhase.NONE, false, hybrid = true))
        assertEquals(KeyguardMode.UNTOUCHED, keyguardMode(true, false, true, false, VoipPhase.NONE, false, hybrid = true))
    }

    @Test
    fun `the features - the hybrid's bits added and recorded, a parent's kept, and the switch back takes out exactly ours`() {
        val parent = 0x40 // REMOTE_INPUT, set by someone else
        val (on, added) = keyguardDisabledFeatures(parent, managed = true, cover = false, coverAdded = 0, hybrid = true)
        assertEquals(parent or HYBRID_KEYGUARD_FEATURES or 2, on)
        assertEquals(HYBRID_KEYGUARD_FEATURES, added)
        assertEquals("again: stable", on to added, keyguardDisabledFeatures(on, true, false, added, hybrid = true))
        val (off, after) = keyguardDisabledFeatures(on, managed = true, cover = false, coverAdded = added)
        assertEquals(parent or 2, off)
        assertEquals(0, after)
        // A bit the parent had already set isn't ours to clear.
        val (on2, added2) = keyguardDisabledFeatures(0x4, true, false, 0, hybrid = true)
        assertEquals(HYBRID_KEYGUARD_FEATURES and 0x4.inv(), added2)
        assertEquals(0x4 or 2, keyguardDisabledFeatures(on2, true, false, added2).first)
        // From the cover's ALL: the cover's bits out, the hybrid's in.
        val (fromCover, addedFromCover) = keyguardDisabledFeatures(KEYGUARD_FEATURES_ALL, true, false, KEYGUARD_FEATURES_ALL, hybrid = true)
        assertEquals(HYBRID_KEYGUARD_FEATURES or 2, fromCover)
        assertEquals(HYBRID_KEYGUARD_FEATURES, addedFromCover)
    }

    private class FakePolicy(var features: Int = 0, var t: Long = 0L, var disabled: Boolean? = null, val failFeatures: Boolean = false) : KeyguardPolicy {
        val writes = mutableListOf<String>()
        override fun readFeatures(): Int = features
        override fun writeFeatures(value: Int) {
            if (failFeatures) throw SecurityException("no")
            writes += "features"
            features = value
        }
        override fun readMaxTimeToLock(): Long = t
        override fun writeMaxTimeToLock(ms: Long) {
            writes += "t"
            t = ms
        }
        override fun writeDisabled(disabled: Boolean): Boolean {
            writes += "disabled=$disabled"
            this.disabled = disabled
            return true
        }
    }

    @Test
    fun `writeKeyguard HYBRID - keyguard on, features recorded before written, T, never cover_written`() {
        val p = FakePolicy()
        var stored = -1
        val flags = mutableListOf<Boolean>()
        val w = writeKeyguard(p, KeyguardMode.HYBRID, managed = true, coverAdded = 0, timeoutMs = { 30_000L },
            storeCoverAdded = { check("features" !in p.writes); stored = it; true }, storeWritten = { flags += it; true })
        assertEquals(KeyguardMode.HYBRID, w.mode)
        assertTrue(w.lockMayRun)
        assertEquals(null, w.coverFailed)
        assertEquals(listOf("disabled=false", "features", "t"), p.writes)
        assertEquals(HYBRID_KEYGUARD_FEATURES, stored)
        assertEquals(HYBRID_KEYGUARD_FEATURES or 2, p.features)
        assertEquals(30_000L, p.t)
        assertTrue("the cover's flag is never set", flags.isEmpty())
        // Back to DISABLED (the rollback's apply): exactly ours out, T 0.
        val back = writeKeyguard(p, KeyguardMode.DISABLED, true, stored, { 30_000L }, { stored = it; true })
        assertEquals(KeyguardMode.DISABLED, back.mode)
        assertEquals(2, p.features)
        assertEquals(0L, p.t)
        assertEquals(true, p.disabled)
    }

    @Test
    fun `writeKeyguard HYBRID - a failed step is reported, never a fallback to a disabled keyguard`() {
        val p = FakePolicy(failFeatures = true)
        val w = writeKeyguard(p, KeyguardMode.HYBRID, true, 0, { null }, { true })
        assertEquals(KeyguardMode.HYBRID, w.mode)
        assertEquals("features", w.coverFailed)
        assertEquals(false, p.disabled)
        assertEquals(2, w.errors.size)
        // The record isn't written without its store.
        val q = FakePolicy()
        val w2 = writeKeyguard(q, KeyguardMode.HYBRID, true, 0, { 30_000L }, { false })
        assertEquals("features", w2.coverFailed)
        assertEquals(0, q.features)
    }

    // ---- pins ----------------------------------------------------------------------------------------------

    @Test
    fun `the lock's way out as the face is Android's - the PIN pad's answer, or Android unlocked with a credential`() {
        val activity = code("$lock/PinLockActivity.kt")
        // Every unlock without our PIN check goes through Hybrid59's live gate.
        val ask = body(activity, "askAndroid")
        assertTrue(ask.contains("when (Hybrid59.unlockStep(this)) {"))
        assertTrue(ask.substringAfter("AndroidUnlock.UNLOCK_NOW -> {").substringBefore("AndroidUnlock.ASK_ANDROID").contains("unlock(PinLockRuntime.screenOffs)"))
        val answered = body(activity, "androidAnswered")
        assertTrue(answered.contains("val gone = result == \"succeeded\" || (result == \"error\" && Hybrid59.unlockStep(this) == AndroidUnlock.UNLOCK_NOW)"))
        assertTrue(answered.contains("if (gone && PinLockRuntime.mode == LockMode.LOCKED && PinLockRuntime.screenOffs == offs) unlock(offs) else glance?.back()"))
        assertTrue(activity.substringAfter("private val androidUnlockFallback = Runnable {").substringBefore("\n    }\n")
            .contains("Hybrid59.unlockedUs(this, PinLockRuntime.mode == LockMode.LOCKED)"))
        assertTrue(body(activity, "resumeAsFace").contains("if (Hybrid59.unlockedUs(this, PinLockRuntime.mode == LockMode.LOCKED)) {"))
        assertTrue(body(activity, "resumeAsFace").startsWith("fun resumeAsFace(): Boolean {\n        if (!Hybrid59.faceOn) return false\n"))
        // The runtime's USER_PRESENT: the same gate.
        val runtime = code("$lock/PinLockRuntime.kt")
        val present = runtime.substringAfter("if (intent.action == Intent.ACTION_USER_PRESENT && Hybrid59.faceOn) {").substringBefore("dispatch(app, LockEvent.ScreenOn(")
        assertTrue(present, present.contains("if (Hybrid59.unlockedUs(app, locked = mode == LockMode.LOCKED)) {"))
        // The live checks: no credential or unreadable = our keypad; unreadable lock state = locked.
        val hybrid = code("$lock/Hybrid59.kt")
        assertTrue(body(hybrid, "deviceSecure").contains("} catch (e: Exception) {\n        false"))
        assertTrue(body(hybrid, "androidLocked").contains("km == null || km.isDeviceLocked || km.isKeyguardLocked"))
        assertTrue(body(hybrid, "androidLocked").contains("} catch (e: Exception) {\n        true"))
        assertTrue(body(hybrid, "unlockedUs").contains("faceOn && locked && androidUnlockedUs(true, deviceSecure(context), androidLocked(context), locked, interactive(context))"))
        // The runtime's hybrid inputs, each gated by the face: the re-front, the camera lock, the lock's activation.
        assertTrue(body(runtime, "runRefrontCheck").contains("androidGate = chromeLocked && !lockResumed && Hybrid59.refrontHeld(context),"))
        assertTrue(body(hybrid, "refrontHeld").contains("faceOn && hybridRefrontHeld(true, androidLocked(context))"))
        assertTrue(code("$lock/CameraLock.kt").contains("val locked = cameraLockEngaged(PinLockRuntime.chromeLocked, Hybrid59.faceOn)"))
        val configure = body(runtime, "configure")
        assertTrue(configure.contains("val androidGate = Hybrid59.configured(app, dpm, admin, managed, newConfig != null, deviceSecure)"))
        assertTrue(configure.contains("androidGate = androidGate,"))
    }

    @Test
    fun `the credential is written only through WindowRelock's token, from the migration and the rollback`() {
        val relock = code("java/com/kidslauncher/mdm/server/WindowRelock.kt")
        assertEquals(2, Regex("resetPasswordWithToken\\(").findAll(relock).count())
        assertTrue(body(relock, "resetCredential").contains("if (!resetTokenActive(app)) return false"))
        val callers = file("java").walk().filter { it.isFile && it.extension == "kt" }
            .filter { it.readText().contains("WindowRelock.resetCredential(") }.map { it.name }.toList()
        assertEquals(listOf("Hybrid59.kt"), callers)
        val hybrid = code("$lock/Hybrid59.kt")
        assertEquals(2, Regex("WindowRelock\\.resetCredential\\(").findAll(hybrid).count())
        assertTrue(body(hybrid, "migrateIfDue").contains("WindowRelock.resetCredential(app, pin)"))
        assertTrue(body(hybrid, "rollback").contains("WindowRelock.resetCredential(app, null)"))
        // The kid PIN reaches it only from a right check on our keypad.
        val activity = code("$lock/PinLockActivity.kt")
        assertEquals(1, Regex("Hybrid59\\.migrateIfDue\\(").findAll(activity).count())
        assertTrue(activity.contains("if (it == PinLockRuntime.PinResult.Ok) Hybrid59.migrateIfDue(this@PinLockActivity, pin)"))
        // No new token copy: the token is WindowRelockStore's only.
        assertFalse(hybrid.contains("token()"))
    }

    @Test
    fun `lock task's KEYGUARD stays forced - without it lock task would disable even a secure keyguard (59 section 1)`() {
        val helpers = code("java/com/kidslauncher/mdm/server/LockTaskHelpers.kt")
        assertTrue(helpers.contains("or LOCK_TASK_FEATURE_KEYGUARD"))
        assertTrue((com.kidslauncher.mdm.server.lockTaskFeatures(0L, blockActivityStart = false) and com.kidslauncher.mdm.server.LOCK_TASK_FEATURE_KEYGUARD) != 0)
    }
}
