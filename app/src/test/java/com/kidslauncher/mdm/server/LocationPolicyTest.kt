package com.kidslauncher.mdm.server

import com.kidslauncher.mdm.server.dto.LocationPolicy
import org.junit.Assert.assertEquals
import org.junit.Test

class LocationPolicyTest {
    private val minute = 60_000L

    @Test
    fun `off never touches location, even on request`() {
        val off = LocationPolicy("off", 30)
        assertEquals(LocationAction.NONE, locationAction(off, forced = false, sinceLastFreshMs = Long.MAX_VALUE))
        assertEquals(LocationAction.NONE, locationAction(off, forced = true, sinceLastFreshMs = 0))
    }

    @Test
    fun `on request takes a fresh fix only for a locate or ring command`() {
        val onRequest = LocationPolicy("on_request", 30)
        assertEquals(LocationAction.NONE, locationAction(onRequest, false, Long.MAX_VALUE))
        assertEquals(LocationAction.FRESH, locationAction(onRequest, true, 0))
        assertEquals("unknown modes count as on request", LocationAction.NONE, locationAction(LocationPolicy("weird", 5), false, Long.MAX_VALUE))
    }

    @Test
    fun `interval takes a fresh fix when due, the cached one otherwise`() {
        val every30 = LocationPolicy("interval", 30)
        assertEquals(LocationAction.CACHED, locationAction(every30, false, 29 * minute))
        assertEquals(LocationAction.FRESH, locationAction(every30, false, 30 * minute))
        assertEquals(LocationAction.FRESH, locationAction(every30, true, 0))
        assertEquals("clock went back", LocationAction.FRESH, locationAction(every30, false, -1))
        assertEquals("at least 10 minutes", LocationAction.CACHED, locationAction(LocationPolicy("interval", 1), false, 9 * minute))
    }

    @Test
    fun `an older server keeps the old 10-minute behaviour`() {
        assertEquals(LocationAction.CACHED, locationAction(null, false, 5 * minute))
        assertEquals(LocationAction.FRESH, locationAction(null, false, 10 * minute))
        assertEquals(LocationAction.FRESH, locationAction(null, true, 0))
    }

    @Test
    fun `the locate answer says what happened`() {
        assertEquals(false to "location is off for this device", locateResultMessage(LocationAction.NONE, null, null))
        assertEquals(false to "no location fix", locateResultMessage(LocationAction.FRESH, null, null))
        assertEquals(true to "fix ±12 m, 3 s old", locateResultMessage(LocationAction.FRESH, 12.7f, 3))
        assertEquals(true to "fix, 0 s old", locateResultMessage(LocationAction.FRESH, null, -4))
    }
}
