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

    private fun connected(call: Any, connectTime: Long = 1_000, now: Long = 5_000) =
        connects.connected(call, connectTime, now) { recorded += it }

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
    fun `without a connect time the first sighting is the instant`() {
        val call = Any()
        connected(call, connectTime = 0, now = 5_000)
        connected(call, connectTime = 4_000, now = 7_000)
        assertEquals(listOf(5_000L, 5_000L), recorded)
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
        assertTrue("emergencyConnects.connected(call, call.details.connectTimeMillis" in record)
        assertTrue("CallPrefs.recordEmergencyConnected(this, it)" in record)
        assertTrue("emergencyConnects.removed(call)" in service.substringAfter("override fun onCallRemoved("))
        assertTrue("unlocked = { CallPolicyStore.userUnlocked(this) }" in service)
        assertTrue("SyncRunner.request(this, EMERGENCY_SYNC_REASON)" in service)
        assertEquals("emergency_call", EMERGENCY_SYNC_REASON)
    }
}
