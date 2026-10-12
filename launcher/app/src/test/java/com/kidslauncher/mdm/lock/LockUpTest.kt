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
