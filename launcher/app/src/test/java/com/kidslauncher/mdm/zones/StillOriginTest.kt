package com.kidslauncher.mdm.zones

import com.kidslauncher.mdm.server.ServerJson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The owner's report (2026-11-27): still from the last motion fire at 22:26, fresh fixes at 22:54 and 02:58, Locate said
 * "Hasn't moved since 02:58". [StillOrigin] says since when the phone is still - the fire, through fresh fixes, a fix on
 * the move, a process restart and a gap nothing watched.
 */
class StillOriginTest {
    private val minute = 60_000L
    private val fire = 1_795_732_000_000L // 22:26
    private val atFix = fire + 28 * minute // 22:54
    private val capFix = fire + 272 * minute // 02:58

    private fun fix(lat: Double, accuracy: Float? = 20f, timeMs: Long = atFix) = QueuedFix(lat, 10.75, accuracy, timeMs)
    private val home = fix(59.9139)
    private val away = fix(59.9229) // about 1 km north

    /** Fired at 22:26, armed again at once by the zones' series. */
    private fun stillFromFire() = StillOrigin().fired(fire).armed(fire + 2_000)

    @Test
    fun `fresh fixes while still keep the fire's time`() {
        val o = stillFromFire()
            .afterFreshFix(atFix, moving = false, place = Place.SAME, stillAtTry = false)
            .afterFreshFix(capFix, moving = false, place = Place.SAME, stillAtTry = true)
        assertEquals(fire, o.reported(still = true))
        assertNull(o.reported(still = false))
        // After a fire the phone may be elsewhere: the fire's time stands.
        assertEquals(fire, stillFromFire().afterFreshFix(atFix, false, Place.APART, stillAtTry = false).reported(true))
        // Elsewhere with no fire since (design 124 D7's case: the sensor missed it): from that fix.
        assertEquals(capFix, stillFromFire().afterFreshFix(capFix, false, Place.APART, stillAtTry = true).reported(true))
    }

    @Test
    fun `a fire or a mark starts it again and nothing is reported until the trigger watches`() {
        val fired = stillFromFire().fired(capFix)
        assertNull(fired.reported(true))
        assertEquals(capFix, fired.armed(capFix + 5_000).reported(true))
        assertEquals(capFix, stillFromFire().marked(capFix).reported(true))
    }

    @Test
    fun `an arm after a gap starts the time at the arm`() {
        // Without zones the trigger waits for the next try: the phone may have moved on unheard.
        val late = StillOrigin().fired(fire).armed(atFix)
        assertEquals(atFix, late.reported(true))
        assertEquals(fire, StillOrigin().fired(fire).armed(fire + STILL_REARM_SLACK_MS).sinceMs)
        assertEquals(fire + STILL_REARM_SLACK_MS + 1, StillOrigin().fired(fire).armed(fire + STILL_REARM_SLACK_MS + 1).sinceMs)
        // Disarmed (Regularly ended, the anchor stopped): the next arm starts it.
        val gap = stillFromFire().unwatched()
        assertNull(gap.reported(true))
        assertEquals(capFix, gap.armed(capFix).reported(true))
        assertNull(StillOrigin().reported(true))
    }

    @Test
    fun `a fix on the move or a scan mismatch waits for a fix at rest`() {
        val moving = stillFromFire().afterFreshFix(atFix, moving = true, place = Place.UNKNOWN, stillAtTry = false)
        assertNull(moving.reported(true))
        assertEquals(capFix, moving.afterFreshFix(capFix, false, Place.SAME, stillAtTry = false).reported(true))
        val mismatch = stillFromFire().mismatched(atFix)
        assertNull(mismatch.reported(true))
        assertEquals(capFix, mismatch.afterFreshFix(capFix, false, Place.SAME, stillAtTry = false).reported(true))
    }

    @Test
    fun `a restart keeps it when the first fresh fix is at the same place`() {
        val kept = ServerJson.decodeFromString(
            StillOrigin.serializer(),
            ServerJson.encodeToString(StillOrigin.serializer(), stillFromFire()),
        ).afterRestart().armed(atFix)
        assertNull(kept.reported(true))
        assertEquals(fire, kept.afterFreshFix(capFix, false, Place.SAME, stillAtTry = false).reported(true))
        assertEquals(capFix, kept.afterFreshFix(capFix, false, Place.APART, stillAtTry = false).reported(true))
        assertEquals(capFix, kept.afterFreshFix(capFix, false, Place.UNKNOWN, stillAtTry = false).reported(true))
        // A fire after the restart is the new start.
        assertEquals(capFix, kept.fired(capFix).armed(capFix).reported(true))
        // An origin that wasn't watching when the process ended starts at the next arm.
        assertEquals(atFix, StillOrigin().fired(fire).afterRestart().armed(atFix).reported(true))
    }

    @Test
    fun `the place against the last fresh fix`() {
        assertEquals(Place.SAME, placeAgainst(home, fix(59.9141, timeMs = capFix)))
        assertEquals(Place.APART, placeAgainst(home, away))
        assertEquals(Place.UNKNOWN, placeAgainst(null, home))
        assertEquals(Place.UNKNOWN, placeAgainst(home, fix(59.9229, accuracy = null)))
        assertEquals(Place.UNKNOWN, placeAgainst(fix(59.9139, accuracy = 500f), away))
    }
}
