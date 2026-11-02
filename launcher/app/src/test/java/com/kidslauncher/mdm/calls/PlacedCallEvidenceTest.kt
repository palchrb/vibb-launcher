package com.kidslauncher.mdm.calls

import com.kidslauncher.mdm.calls.PlacedCallEvidence.Step
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** H1 QA: the call state of an emergency call the launcher placed that only the system dialer shows. */
class PlacedCallEvidenceTest {
    private val evidence = PlacedCallEvidence()

    @Test
    fun `off-hook held long enough promotes once, and the hang-up re-records the hour`() {
        assertEquals(Step.NONE, evidence.idle(0)) // the registration's idle before the call starts
        assertEquals(Step.NONE, evidence.offhook(1_000))
        assertEquals(Step.NONE, evidence.check(1_000 + PLACED_CONNECT_EVIDENCE_MS - 1))
        assertEquals(Step.PROMOTE, evidence.check(1_000 + PLACED_CONNECT_EVIDENCE_MS))
        assertEquals(Step.NONE, evidence.check(1_000 + 2 * PLACED_CONNECT_EVIDENCE_MS))
        assertEquals(Step.PROMOTE_AND_STOP, evidence.idle(50 * 60_000L))
    }

    @Test
    fun `a call that ends while dialling keeps the provisional window`() {
        evidence.offhook(1_000)
        assertEquals(Step.STOP, evidence.idle(1_000 + 8_000))
    }

    @Test
    fun `a long call whose timer didn't run is promoted at its end`() {
        evidence.offhook(1_000)
        assertEquals(Step.PROMOTE_AND_STOP, evidence.idle(1_000 + PLACED_CONNECT_EVIDENCE_MS + 5_000))
    }

    @Test
    fun `ringing before our call started is someone else's call`() {
        assertEquals(Step.STOP, evidence.ringing(500))
    }

    @Test
    fun `the watch's own end re-records a call still on, and stops one that never started`() {
        assertEquals(Step.STOP, PlacedCallEvidence().finish(3 * 60 * 60_000L))
        evidence.offhook(1_000)
        assertEquals(Step.PROMOTE_AND_STOP, evidence.finish(3 * 60 * 60_000L))
    }

    @Test
    fun `the watch acts on the record only while it is current, and our in-call service ends it`() {
        val sources = listOf("src/main/java", "app/src/main/java").map(::File).first { it.isDirectory }
        val watch = sources.walkTopDown().first { it.name == "PlacedCallWatch.kt" }.readText().substringAfter("object PlacedCallWatch")
        assertTrue("if (current !== watch) return" in watch)
        assertTrue("PlacedCallEvidence.Step.PROMOTE -> CallPrefs.promotePlaced(app, placedAtMs)" in watch)
        assertTrue("TelephonyManager.CALL_STATE_RINGING -> evidence.ringing(now)" in watch)
        assertTrue("act(evidence.finish(SystemClock.elapsedRealtime())) }, MAX_WATCH_MS)" in watch)
        assertTrue("telephony.unregisterTelephonyCallback(watch.callback)" in watch.substringAfter("fun stop()"))
        assertTrue("handler.removeCallbacksAndMessages(null)" in watch.substringAfter("fun stop()"))
        // A promotion only touches the record while it is still that placing.
        val prefs = sources.walkTopDown().first { it.name == "CallSystem.kt" }.readText().substringAfter("fun promotePlaced(")
        assertTrue("getLong(EMERGENCY_CONNECTED_AT_MS, 0) == placedAtMs" in prefs.substringBefore("\n    }\n"))
    }
}
