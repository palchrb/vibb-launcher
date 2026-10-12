package com.kidslauncher.mdm.lock

import com.kidslauncher.mdm.server.LOCK_TASK_FEATURE_BLOCK_ACTIVITY_START_IN_TASK
import com.kidslauncher.mdm.server.lockTaskWhileLocked
import com.kidslauncher.mdm.server.sosNarrowedPackages
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * qa-25b-25f H1: the "sos" yield must never keep anything but the SOS app (or an emergency call)
 * over the lock. QA's bypass scenarios, each as the decision it now gets.
 */
class SosYieldTest {
    private val own = "me.vibb.launcher"
    private val sos = "com.android.emergency"
    private val element = "io.element.android.x"
    private val timerApp = "org.example.timer"
    private val recents = "com.google.android.apps.nexuslauncher"
    private val dialer = "com.android.dialer"
    private val helpers = setOf("com.android.phone", "com.android.server.telecom", dialer, "com.google.android.deskclock", sos)
    private val kiosk = setOf(own, element, timerApp, recents, dialer)
    private val features = 63 or LOCK_TASK_FEATURE_BLOCK_ACTIVITY_START_IN_TASK

    private val base = RefrontInputs(locked = true, lockResumed = false, interactive = true, ourCall = false,
        telecomInCall = false, emergencyFlow = false, alarmRinging = false)

    /** The list while the "sos" yield runs, kiosk on. */
    private fun narrowed(): Set<String> = lockTaskWhileLocked(
        kiosk, features, false, locked = true, ownPackage = own, lockHelpers = emptySet(),
        recentsPin = recents, sosNarrowed = sosNarrowedPackages(own, helpers, sos),
    ).packages!!

    private fun code(path: String) = listOf("src/main/$path", "app/src/main/$path").map(::File).first { it.exists() }.readText()
        .replace(Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL), "")
        .replace(Regex("//[^\\n]*"), "")

    private fun body(code: String, name: String): String {
        val start = code.indexOf("fun $name(")
        assertTrue("fun $name exists", start >= 0)
        val rest = code.substring(start)
        return rest.substring(0, Regex("""\n    }\n""").find(rest)?.range?.first ?: rest.length)
    }

    @Test
    fun `the narrowed list is the kiosk-off one - our package, the helpers, the SOS app`() {
        assertEquals(setOf(own) + helpers, narrowed())
        // Unknown helpers (not resolved yet): our package and the SOS app.
        assertEquals(setOf(own, sos), sosNarrowedPackages(own, null, sos))
        assertEquals(setOf(own), sosNarrowedPackages(own, null, null))
        assertFalse("never Play core", "com.google.android.gms" in sosNarrowedPackages(own, setOf("com.google.android.gms"), sos))
        // Not narrowed: the kiosk's list as it is (minus the recents pin, qa-16d-code #1).
        assertEquals(kiosk - recents, lockTaskWhileLocked(kiosk, features, false, true, own, emptySet(), recentsPin = recents).packages)
        // Unlocked, it has no effect.
        assertEquals(kiosk, lockTaskWhileLocked(kiosk, features, false, false, own, emptySet(), sosNarrowed = setOf(own, sos)).packages)
    }

    @Test
    fun `1 - Recents to an allowed app's thumbnail in the window - the app's task is closed`() {
        // The stop of the lock in the window marks SOS and the check yields to it ...
        assertTrue(sosGestureLikely(lastScreenOnAtMs = 10_000L, leftFrontAtMs = 10_900L, sosAppPresent = true))
        val action = refrontAction(base.copy(sosFlow = true), 0)
        assertEquals(RefrontAction.Yield("sos"), action)
        assertTrue(sosMarkKept(action))
        // ... but lock task now holds only the helpers: Element X's task goes (removeLockedTask).
        assertFalse(element in narrowed())
        // The pause before it (Recents itself) waits the grace only, then re-fronts.
        assertEquals(SOS_PAUSE_GRACE_MS, pauseRefrontDelayMs(10_000L, 10_200L))
        assertTrue(refrontAction(base, 0) is RefrontAction.Refront)
    }

    @Test
    fun `2 - a system-dialer call that woke the screen - its own yield, the mark ends, the lock comes back after it`() {
        val during = refrontAction(base.copy(telecomInCall = true, sosFlow = true), 0)
        assertEquals(RefrontAction.Yield("system_call"), during)
        assertFalse("the mark ends", sosMarkKept(during))
        // After the call the mark is gone (sosFlow false): re-front.
        assertTrue(refrontAction(base, 0) is RefrontAction.Refront)
    }

    @Test
    fun `3 - an allowed app's full-screen intent in the window - closed by the narrowed list`() {
        assertFalse(timerApp in narrowed())
        assertEquals(RefrontAction.Yield("sos"), refrontAction(base.copy(sosFlow = true), 0))
    }

    @Test
    fun `4 - the system clock's alarm in the window - an alarm yield, never SOS`() {
        val action = refrontAction(base.copy(alarmRinging = true, sosFlow = true), 0)
        assertEquals(RefrontAction.Yield("alarm"), action)
        assertFalse(sosMarkKept(action))
        assertEquals(null, alarmAfterResume(1_000L, "alarm"))
    }

    @Test
    fun `5 - quickstep after a SystemUI restart - the recents provider is not in the narrowed list`() {
        assertFalse(recents in narrowed())
        // VoIP and our own call screen are never SOS either.
        assertFalse(sosMarkKept(refrontAction(base.copy(voipCall = true, sosFlow = true), 0)))
        assertFalse(sosMarkKept(refrontAction(base.copy(ourCall = true, sosFlow = true), 0)))
    }

    @Test
    fun `the yield is short and ends when the lock is in front or the screen goes off`() {
        assertEquals(15_000L, SOS_FLOW_MS)
        assertFalse(sosMarkKept(refrontAction(base.copy(lockResumed = true, sosFlow = true), 0)))
        assertFalse(sosMarkKept(refrontAction(base.copy(interactive = false, sosFlow = true), 0)))
        assertFalse(sosFlowNow(untilMs = 25_000L, nowMs = 25_000L, ownScreenInFront = false))
    }

    @Test
    fun `the runtime narrows lock task with the yield and widens it when it ends`() {
        val runtime = code("java/com/kidslauncher/mdm/lock/PinLockRuntime.kt")
        val check = body(runtime, "runRefrontCheck")
        assertTrue(check.contains("val sos = sosMarkKept(action)"))
        assertTrue(check.contains("if (!sos) sosFlowUntilElapsed = 0L"))
        assertTrue(check.contains("narrowForSos(context, sos)"))
        assertTrue(body(runtime, "onLockResumed").contains("narrowForSos(context, false)"))
        assertTrue(body(runtime, "narrowForSos").contains("LockTaskChrome.sosYield(context.applicationContext, on)"))
        val chrome = code("java/com/kidslauncher/mdm/lock/LockTaskChrome.kt")
        assertTrue(chrome.contains("sosNarrowedPackages(context.packageName, helpers, PinLockRuntime.sosPackage)"))
        assertEquals("both passes narrow", 2, Regex("""sosNarrowed = sosNarrowed\(context, locked, kiosk(On)?\)""").findAll(chrome).count())
        // The helpers are cached with the kiosk on too.
        assertTrue(body(chrome, "applyPlan").contains("val resolved = runCatching(pinLockHelpers).getOrNull()"))
    }
}
