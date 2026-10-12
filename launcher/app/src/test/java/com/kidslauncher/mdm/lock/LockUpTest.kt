package com.kidslauncher.mdm.lock

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The lock coming up and staying up (bugs 25b-A and 25b-B, the emulator run after design 25):
 * Recents paused the lock without stopping it, so the stop-only re-front never brought it back; the
 * lock started at a screen-off played its open transition at the next screen-on, over Home's last
 * (unlocked) frame; the glance page drew a frame before its fit.
 */
class LockUpTest {
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

    @Test
    fun `a pause while LOCKED starts the re-front check - Recents only pauses the lock`() {
        assertTrue(pauseStartsRefront(locked = true, finishing = false))
        assertFalse("unlocked: Home or the opened app comes up", pauseStartsRefront(locked = false, finishing = false))
        assertFalse("finishing after the right code", pauseStartsRefront(locked = true, finishing = true))
        assertEquals(200L, PAUSE_REFRONT_MS)
        // The check itself is the one the stop runs: exempt screens still yield.
        val inputs = RefrontInputs(locked = true, lockResumed = false, interactive = true, ourCall = false,
            telecomInCall = false, emergencyFlow = false, alarmRinging = false)
        assertTrue(refrontAction(inputs, 0) is RefrontAction.Refront)
        assertTrue(refrontAction(inputs.copy(emergencyFlow = true), 0) is RefrontAction.Yield)
        assertTrue(refrontAction(inputs.copy(voipCall = true), 0) is RefrontAction.Yield)
        assertEquals(RefrontAction.Stop, refrontAction(inputs.copy(lockResumed = true), 0))
        assertEquals("the screen went off: nothing", RefrontAction.Stop, refrontAction(inputs.copy(interactive = false), 0))
        val runtime = code("java/com/kidslauncher/mdm/lock/PinLockRuntime.kt")
        val paused = body(runtime, "onLockPaused")
        assertTrue(paused.contains("if (!pauseStartsRefront(chromeLocked, finishing)) return"))
        assertTrue(paused.contains("handler.postDelayed(refrontCheck, PAUSE_REFRONT_MS)"))
        val activity = code("java/com/kidslauncher/mdm/lock/PinLockActivity.kt")
        assertTrue(activity.contains("PinLockRuntime.onLockPaused(isFinishing || finishingAfterUnlock)"))
        // A stop (a SystemUI restart, quickstep's Recents or app list, an app) re-fronts as quickly.
        assertTrue(body(runtime, "onLockStopped").contains("handler.postDelayed(refrontCheck, PAUSE_REFRONT_MS)"))
    }

    /** Bug 25b (the camera gesture): SystemUI's canCameraGestureBeLaunched returns false first for a
     * policy-disabled camera (AOSP 16 CameraGestureHelper.isCameraAllowedByAdmin, both the plain and
     * the "camera or wallet" double-press paths), so the gesture never starts a suspended camera -
     * which crashed SystemUI and let whatever came up show over the lock. */
    @Test
    fun `the cameras are off by policy while locked and while the gesture's camera is suspended`() {
        assertTrue(cameraPolicyDisabled(locked = true, gestureCameraSuspended = false))
        assertTrue(cameraPolicyDisabled(locked = true, gestureCameraSuspended = true))
        assertTrue("a time rule suspended it", cameraPolicyDisabled(locked = false, gestureCameraSuspended = true))
        assertFalse(cameraPolicyDisabled(locked = false, gestureCameraSuspended = false))
        val lock = code("java/com/kidslauncher/mdm/lock/CameraLock.kt")
        val sync = body(lock, "sync")
        // While LOCKED the policy goes on before anything is suspended; unlocked it follows after.
        val policyFirst = sync.indexOf("if (locked) syncCameraPolicy(app, dpm, admin, locked = true)")
        assertTrue(policyFirst >= 0)
        assertTrue(policyFirst < sync.indexOf("dpm.setPackagesSuspended(admin, step.packages.toTypedArray(), true)"))
        assertTrue(sync.contains("if (!locked) syncCameraPolicy(app, dpm, admin, locked = false)"))
        assertTrue(body(lock, "syncCameraPolicy").contains("dpm.setCameraDisabled(admin, disabled)"))
        // Every apply() re-syncs it (a time rule's suspension comes from apply()).
        assertTrue(body(lock, "refreshTargets").contains("onLockChanged(context)"))
        assertFalse(body(lock, "refreshTargets").contains("if (PinLockRuntime.chromeLocked)"))
        val admin = file("res/xml/device_admin.xml").readText()
        assertTrue(admin.contains("<disable-camera />"))
    }

    /** Bug 25b-H: the SOS activity (pinned now) in front isn't covered again by the re-front. */
    @Test
    fun `a lock that leaves the front just after a screen-on yields to Emergency SOS`() {
        assertTrue(sosGestureLikely(lastScreenOnAtMs = 10_000L, leftFrontAtMs = 11_800L, sosAppPresent = true))
        assertTrue(sosGestureLikely(10_000L, 10_000L + SOS_GESTURE_WINDOW_MS, true))
        assertFalse("too late for five presses", sosGestureLikely(10_000L, 10_001L + SOS_GESTURE_WINDOW_MS, true))
        assertFalse("no SOS app", sosGestureLikely(10_000L, 11_000L, false))
        assertFalse("no screen-on yet", sosGestureLikely(null, 11_000L, true))
        assertFalse("before the screen-on", sosGestureLikely(10_000L, 9_000L, true))
        assertEquals(60_000L, SOS_FLOW_MS)
        val inputs = RefrontInputs(locked = true, lockResumed = false, interactive = true, ourCall = false,
            telecomInCall = false, emergencyFlow = false, alarmRinging = false, sosFlow = true)
        assertEquals(RefrontAction.Yield("sos"), refrontAction(inputs, 0))
        assertEquals("its emergency call", RefrontAction.Yield("system_call"), refrontAction(inputs.copy(telecomInCall = true), 0))
        assertEquals(RefrontAction.Stop, refrontAction(inputs.copy(lockResumed = true), 0))
        assertTrue(refrontAction(inputs.copy(sosFlow = false), 0) is RefrontAction.Refront)
        val runtime = code("java/com/kidslauncher/mdm/lock/PinLockRuntime.kt")
        for (name in listOf("onLockPaused", "onLockStopped")) {
            val b = body(runtime, name)
            assertTrue(name, b.indexOf("noteProbableSos()") in 0 until b.indexOf("handler.postDelayed(refrontCheck"))
        }
        assertTrue("the lock back in front ends it", body(runtime, "onLockResumed").contains("sosFlowUntilElapsed = 0L"))
        val note = body(runtime, "noteProbableSos")
        assertTrue(note.contains("!interactive(app)) return"))
        assertTrue(note.contains("sosAppPresent = emergencySosPackage(app) != null"))
        assertTrue(note.contains("sosFlowUntilElapsed = now + SOS_FLOW_MS"))
        assertTrue(runtime.contains("lastScreenOnElapsed = SystemClock.elapsedRealtime()"))
        assertTrue(body(runtime, "runRefrontCheck").contains("sosFlow = SystemClock.elapsedRealtime() < sosFlowUntilElapsed,"))
    }

    @Test
    fun `the lock comes up without an open animation`() {
        val show = body(code("java/com/kidslauncher/mdm/lock/PinLockRuntime.kt"), "show")
        assertTrue(show.contains("Intent.FLAG_ACTIVITY_NO_ANIMATION"))
        val onCreate = body(code("java/com/kidslauncher/mdm/lock/PinLockActivity.kt"), "onCreate")
        assertTrue(onCreate.indexOf("overrideActivityTransition(OVERRIDE_TRANSITION_OPEN, 0, 0)") in 0 until onCreate.indexOf("setContentView("))
        val styles = file("res/values/styles.xml").readText()
        val theme = styles.substringAfter("<style name=\"PinLockTheme\"").substringBefore("</style>")
        assertTrue(theme.contains("<item name=\"android:windowAnimationStyle\">@null</item>"))
    }

    @Test
    fun `the glance page never draws a frame before its fit`() {
        val panel = code("java/com/kidslauncher/mdm/lock/GlancePanel.kt")
        assertTrue(panel.contains("glance.root.viewTreeObserver.addOnPreDrawListener {"))
        assertTrue(panel.contains("if (!refitPending) return@addOnPreDrawListener true"))
        val fit = body(panel, "fitNow")
        assertTrue(fit.contains("refitPending = true"))
    }
}
