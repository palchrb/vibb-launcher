package com.kidslauncher.mdm.server

import org.junit.Assert.assertEquals
import org.junit.Test

/** Find My Device's ring (design 18, QA #2): the alarm stream only, the first ring's volumes kept. */
class FindMyDeviceRingTest {

    @Test
    fun `the ring raises the alarm stream only`() {
        // AudioManager.STREAM_ALARM; never RING (2), NOTIFICATION (5) or MUSIC (3).
        assertEquals(listOf(4), RING_RAISED_STREAMS)
    }

    @Test
    fun `a second ring keeps the first ring's saved volumes`() {
        val first = ringVolumesToRestore(emptyMap(), mapOf(STREAM_ALARM_ID to 3))
        assertEquals(mapOf(STREAM_ALARM_ID to 3), first)
        // The second ring reads the raised maximum - restoring that would leave the alarm at full volume.
        assertEquals(first, ringVolumesToRestore(first, mapOf(STREAM_ALARM_ID to 7)))
        // After a restore (cleared) the next ring saves again.
        assertEquals(mapOf(STREAM_ALARM_ID to 5), ringVolumesToRestore(emptyMap(), mapOf(STREAM_ALARM_ID to 5)))
    }
}
