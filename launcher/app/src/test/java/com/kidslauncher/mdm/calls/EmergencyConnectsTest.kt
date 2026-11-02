package com.kidslauncher.mdm.calls

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Design 35 step 3: one sync per connected emergency call, never before the first unlock, and
 * one reported instant per call. */
class EmergencyConnectsTest {
    private var unlocked = true
    private var syncs = 0
    private val recorded = mutableListOf<Long>()
    private val connects = EmergencyConnects(unlocked = { unlocked }, requestSync = { syncs++ })

    private fun connected(call: Any, connectTime: Long = 1_000, now: Long = 5_000, creationTime: Long = 0) =
        connects.connected(call, connectTime, creationTime, now) { recorded += it }

    @Test
    fun `a call syncs once over all its connected states, with one instant`() {
        val call = Any()
        connected(call) // active
        connected(call, now = 9_000) // held and active again
        connected(call, now = 60_000) // disconnected after connecting
        assertEquals(1, syncs)
        assertEquals(listOf(1_000L, 1_000L, 1_000L), recorded)
    }

    @Test
    fun `without a connect time the creation time, else the first sighting is the instant`() {
        val call = Any()
        connected(call, connectTime = 0, now = 5_000)
        connected(call, connectTime = 4_000, now = 7_000)
        assertEquals(listOf(5_000L, 5_000L), recorded)
        // Telecom's creation time survives a restart of our process mid-call (a new Call object).
        connected(Any(), connectTime = 0, creationTime = 800, now = 9_000)
        connected(Any(), connectTime = 0, creationTime = 800, now = 20_000)
        assertEquals(listOf(800L, 800L), recorded.takeLast(2))
    }

    @Test
    fun `the callback window runs from the last record, the report's instant from the call`() {
        val connect = 1_000_000L
        val hangUp = connect + 50 * 60_000L // a 50-minute call
        val (atConnect, first) = emergencyRecord(connect, 10, 4, connectedAtMs = connect)
        val (atHangUp, last) = emergencyRecord(hangUp, 3_000_010, 4, connectedAtMs = connect)
        assertEquals(connect + CALLBACK_WINDOW_MS, atConnect.untilWallMs)
        assertEquals(hangUp + CALLBACK_WINDOW_MS, atHangUp.untilWallMs) // an hour after the hang-up
        assertEquals(3_000_010L, atHangUp.elapsedStartMs)
        assertEquals(4, atHangUp.bootCount)
        // One value per call in the report, whichever record is stored.
        assertEquals(connect, reportedEmergencyAt(first, atConnect.untilWallMs))
        assertEquals(connect, reportedEmergencyAt(last, atHangUp.untilWallMs))
        // A record an older build wrote (no instant): its window's start, as before.
        assertEquals(hangUp, reportedEmergencyAt(0, atHangUp.untilWallMs))
    }

    /** E7's H1: the launcher records an emergency call when it places it (CallSystem.placeCall); when our in-call
     * service sees the same call connect, the placing's instant stays, so the server announces one call. */
    @Test
    fun `the launcher's record of placing a call keeps its instant through the call`() {
        val placed = 1_000_000L
        val created = placed + 40 // Telecom creates the call just after
        val connect = created + 6_000
        assertEquals(placed, emergencyCallInstant(storedAtMs = placed, createdAtMs = created, atMs = connect))
        // Telecom's creation time a little before our clock read, a slow device: still the same call.
        assertEquals(placed, emergencyCallInstant(placed, placed + PLACED_BEFORE_CREATED_MS, connect + PLACED_BEFORE_CREATED_MS))
        // An earlier emergency call's record, another call now: its own instant.
        val later = placed + 20 * 60_000L
        assertEquals(later + 5_000, emergencyCallInstant(placed, later, later + 5_000))
        // Nothing stored, or no creation time: the call's own instant (as before H1).
        assertEquals(connect, emergencyCallInstant(0, created, connect))
        assertEquals(connect, emergencyCallInstant(placed, 0, connect))
        // A stored instant after this call's own (a clock set back) is never kept.
        assertEquals(connect, emergencyCallInstant(connect + 1, created, connect))
    }

    @Test
    fun `the launcher records an emergency call it placed, only when Telecom took it and the platform confirms it`() {
        val sources = listOf("src/main/java", "app/src/main/java").map(::File).first { it.isDirectory }
        val system = sources.walkTopDown().first { it.name == "CallSystem.kt" }.readText()
        val place = system.substringAfter("fun placeCall(").substringBefore("\n    }\n")
        val placedAt = place.indexOf("val placedAtMs = System.currentTimeMillis()")
        val telecom = place.indexOf(".placeCall(Uri.fromParts(")
        val record = place.indexOf("if (Emergency.platformConfirms(number, platformEmergency(context))) emergencyPlaced(context, placedAtMs)")
        assertTrue(placedAt in 0 until telecom && telecom < record)
        val placed = system.substringAfter("private fun emergencyPlaced(").substringBefore("\n    }\n")
        assertTrue("CallPrefs.recordEmergencyCall(context, placedAtMs)" in placed)
        assertTrue("if (CallPolicyStore.userUnlocked(context)) com.kidslauncher.mdm.push.SyncRunner.request(context, EMERGENCY_SYNC_REASON)" in placed)
        // It never throws into placeCall: a false return would open the emergency dialer over the call.
        assertTrue(placed.substringAfter("{\n").trimStart().startsWith("try {") && "catch (e: Exception)" in placed)
    }

    @Test
    fun `both incoming checks take Telecom's emergency callback mark on a SIM account`() {
        val sources = listOf("src/main/java", "app/src/main/java").map(::File).first { it.isDirectory }
        val verdict = sources.walkTopDown().first { it.name == "KidCallScreeningService.kt" }.readText()
            .substringAfter("fun incomingVerdict(")
        assertTrue("ecbm = details.hasProperty(Call.Details.PROPERTY_EMERGENCY_CALLBACK_MODE)" in verdict)
        assertTrue("CallSystem.simAccount(context, details.accountHandle)" in verdict)
        assertTrue("windowOpen = { CallSystem.callbackWindowUntil(context) != null }" in verdict)
        val service = sources.walkTopDown().first { it.name == "KidInCallService.kt" }.readText()
        assertTrue("incomingVerdict(this, call.details) == Verdict.BLOCK" in service)
    }

    @Test
    fun `another call syncs again, and a removed call starts over`() {
        val first = Any()
        connected(first)
        connected(Any(), connectTime = 2_000)
        assertEquals(2, syncs)
        connects.removed(first)
        connected(first, connectTime = 3_000)
        assertEquals(3, syncs)
        assertEquals(3_000L, recorded.last())
    }

    @Test
    fun `never before the first unlock, and at the next connected state after it`() {
        val call = Any()
        unlocked = false
        connected(call)
        connected(call)
        assertEquals(0, syncs)
        assertEquals(2, recorded.size) // the call's record never waits for the unlock
        unlocked = true
        connected(call)
        connected(call)
        assertEquals(1, syncs)
    }

    @Test
    fun `the in-call service records through it and requests the emergency sync`() {
        val sources = listOf("src/main/java", "app/src/main/java").map(::File).first { it.isDirectory }
        val service = sources.walkTopDown().first { it.name == "KidInCallService.kt" }.readText()
        val record = service.substringAfter("private fun recordEmergency(").substringBefore("\n    }\n")
        val prefs = sources.walkTopDown().first { it.name == "CallSystem.kt" }.readText()
            .substringAfter("fun recordEmergencyCall(").substringBefore("\n    }\n")
        assertTrue("emergencyRecord(" in prefs && "System.currentTimeMillis()" in prefs && "emergencyCallInstant(" in prefs)
        assertTrue("reportedEmergencyAt(" in sources.walkTopDown().first { it.name == "CallSystem.kt" }.readText())
        assertTrue("emergencyConnects.connected(call, details.connectTimeMillis, details.creationTimeMillis" in record)
        assertTrue("CallPrefs.recordEmergencyCall(this, it, details.creationTimeMillis)" in record)
        assertTrue("emergencyConnects.removed(call)" in service.substringAfter("override fun onCallRemoved("))
        assertTrue("unlocked = { CallPolicyStore.userUnlocked(this) }" in service)
        // Called directly and caught: a post could be dropped by onDestroy, a throw would reach the
        // main thread of the process that runs the in-call UI.
        val request = service.substringAfter("requestSync = {").substringBefore("\n    )\n")
        assertTrue("SyncRunner.request(this, EMERGENCY_SYNC_REASON)" in request)
        assertTrue("handler.post" !in request)
        assertEquals(1, Regex("""catch \(e: Exception\)""").findAll(request).count())
        assertEquals("emergency_call", EMERGENCY_SYNC_REASON)
    }
}
