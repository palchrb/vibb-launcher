package com.kidslauncher.mdm.calls

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
        val first = emergencyRecord(connect, 10, 4, atMs = connect)
        val last = emergencyRecord(hangUp, 3_000_010, 4, atMs = connect)
        assertEquals(connect + CALLBACK_WINDOW_MS, first.window.untilWallMs)
        assertEquals(hangUp + CALLBACK_WINDOW_MS, last.window.untilWallMs) // an hour after the hang-up
        assertEquals(3_000_010L, last.window.elapsedStartMs)
        assertEquals(4, last.window.bootCount)
        // One value per call in the report, whichever record is stored.
        assertEquals(connect, reportedEmergencyAt(first.atMs, first.window.untilWallMs))
        assertEquals(connect, reportedEmergencyAt(last.atMs, last.window.untilWallMs))
        // A record an older build wrote (no instant): its window's start, as before.
        assertEquals(hangUp, reportedEmergencyAt(0, last.window.untilWallMs))
    }

    /** H1 QA: a placing alone is provisional, the hour comes only with evidence that the call connected. */
    @Test
    fun `a placing opens the provisional window, a connect the hour`() {
        val now = 1_000_000L
        val placed = emergencyRecord(now, 10, 4, atMs = now, windowMs = PROVISIONAL_WINDOW_MS, placed = true)
        fun open(record: EmergencyRecord, afterMs: Long) =
            com.kidslauncher.mdm.server.timedWindowActive(record.window, now + afterMs, 10 + afterMs, 4, record.windowMs)
        assertTrue(open(placed, 9 * 60_000L))
        assertFalse("hung up while dialling: no hour for any caller", open(placed, 11 * 60_000L))
        val connected = emergencyRecord(now, 10, 4, atMs = now)
        assertTrue(open(connected, 59 * 60_000L))
        assertFalse(open(connected, 61 * 60_000L))
    }

    /** The in-call service's records of a call the launcher placed keep the placing's instant (the server
     * announces one call); only a placing's, never another call's (code QA L4). */
    @Test
    fun `only the launcher's placing of the same call keeps its instant`() {
        val placed = 1_000_000L
        val created = placed + 40 // Telecom creates the call during placeCall
        assertTrue(placingOf(storedPlaced = true, storedAtMs = placed, createdAtMs = created))
        assertTrue("a slow device", placingOf(true, placed, placed + PLACED_BEFORE_CREATED_MS))
        // Another emergency call seconds after a recorded one that wasn't a placing (our in-call record, a callback).
        assertFalse(placingOf(storedPlaced = false, storedAtMs = placed, createdAtMs = created))
        // A placing long before this call, or after it (a clock set back), or no creation time: another call.
        assertFalse(placingOf(true, placed, placed + PLACED_BEFORE_CREATED_MS + 1))
        assertFalse(placingOf(true, created + 1, created))
        assertFalse(placingOf(true, placed, 0))
    }

    @Test
    fun `the in-call service sees a call connected, ended without connecting, or nothing yet`() {
        assertEquals(EmergencySighting.CONNECTED, emergencySighting(active = true, disconnected = false, connectTimeMs = 1_000))
        assertEquals(EmergencySighting.CONNECTED, emergencySighting(active = false, disconnected = true, connectTimeMs = 1_000))
        assertEquals(EmergencySighting.ENDED_UNCONNECTED, emergencySighting(active = false, disconnected = true, connectTimeMs = 0))
        assertEquals(null, emergencySighting(active = false, disconnected = false, connectTimeMs = 0)) // dialling
    }

    @Test
    fun `the launcher records an emergency call it placed, provisional and watched`() {
        val sources = listOf("src/main/java", "app/src/main/java").map(::File).first { it.isDirectory }
        val system = sources.walkTopDown().first { it.name == "CallSystem.kt" }.readText()
        val place = system.substringAfter("fun placeCall(").substringBefore("\n    }\n")
        val placedAt = place.indexOf("val placedAtMs = System.currentTimeMillis()")
        val telecom = place.indexOf(".placeCall(Uri.fromParts(")
        val record = place.indexOf("if (Emergency.platformConfirms(number, platformEmergency(context))) emergencyPlaced(context, placedAtMs)")
        assertTrue(placedAt in 0 until telecom && telecom < record)
        val placed = system.substringAfter("fun emergencyPlaced(").substringBefore("\n    }\n")
        assertTrue("CallPrefs.recordEmergencyPlaced(context, placedAtMs)" in placed)
        assertTrue("PlacedCallWatch.start(context, placedAtMs)" in placed && "emergencySync(context)" in placed)
        // It never throws into placeCall: a false return would open the emergency dialer over the call.
        assertTrue(placed.substringAfter("{\n").trimStart().startsWith("try {") && "catch (e: Exception)" in placed)
        val prefs = system.substringAfter("object CallPrefs")
        assertTrue("store(context, placedAtMs, PROVISIONAL_WINDOW_MS, placed = true)" in prefs.substringAfter("fun recordEmergencyPlaced("))
        assertTrue("CALLBACK_WINDOW_MS" in prefs.substringAfter("fun recordEmergencyConnected(").substringBefore("\n    }\n"))
        assertTrue("PROVISIONAL_WINDOW_MS" in prefs.substringAfter("fun recordEmergencyCallback(").substringBefore("\n"))
        assertTrue("getLong(EMERGENCY_WINDOW_MS, CALLBACK_WINDOW_MS)" in prefs.substringAfter("fun recordedWindowUntil("))
        // The emergency dialer fallback (Telecom refused) is a placing too.
        val fallback = sources.walkTopDown().first { it.name == "EmergencyCall.kt" }.readText().substringAfter("fun callEmergency(")
        assertTrue("CallSystem.emergencyPlaced(activity, openedAtMs)" in fallback)
    }

    @Test
    fun `both incoming checks take Telecom's emergency callback mark on a SIM account, and record it`() {
        val sources = listOf("src/main/java", "app/src/main/java").map(::File).first { it.isDirectory }
        val verdict = sources.walkTopDown().first { it.name == "KidCallScreeningService.kt" }.readText()
            .substringAfter("fun incomingVerdict(")
        assertTrue("ecbm = details.hasProperty(Call.Details.PROPERTY_EMERGENCY_CALLBACK_MODE)" in verdict)
        assertTrue("CallSystem.simAccount(context, details.accountHandle)" in verdict)
        assertTrue("windowOpen = { CallSystem.callbackWindowUntil(context) != null }" in verdict)
        assertTrue("if (pass == CallbackPass.TELECOM_MARK) CallSystem.telecomMarkSeen(context)" in verdict)
        val system = sources.walkTopDown().first { it.name == "CallSystem.kt" }.readText()
        val seen = system.substringAfter("fun telecomMarkSeen(").substringBefore("\n    }\n")
        assertTrue("telecomMarkRecords(lastEmergencyCallMs(context, now), now)" in seen)
        assertTrue("CallPrefs.recordEmergencyCallback(context, now)" in seen && "emergencySync(context)" in seen)
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
            .substringAfter("private fun store(").substringBefore("\n    }\n")
        assertTrue("emergencyRecord(" in prefs && "System.currentTimeMillis()" in prefs)
        assertTrue("reportedEmergencyAt(" in sources.walkTopDown().first { it.name == "CallSystem.kt" }.readText())
        assertTrue("emergencyConnects.connected(call, details.connectTimeMillis, details.creationTimeMillis" in record)
        assertTrue("CallPrefs.recordEmergencyConnected(this, it, details.creationTimeMillis)" in record)
        // Ended without connecting: a launcher placing of it drops back; seeing the call ends the call-state watch.
        assertTrue("EmergencySighting.ENDED_UNCONNECTED -> CallPrefs.demotePlaced(this, details.creationTimeMillis)" in record)
        assertTrue(record.indexOf("PlacedCallWatch.stop()") in 0 until record.indexOf("when (sighting)"))
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
