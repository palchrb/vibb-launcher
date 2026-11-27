package com.kidslauncher.mdm.zones

import com.kidslauncher.mdm.server.ServerJson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The owner's report (2026-11-27): still from the last motion fire at 22:26, fresh fixes at 22:54 and 02:58, Locate said
 * "Hasn't moved since 02:58". [StillOrigin] says since when the phone is still - the fire, through fresh fixes, a fix on
 * the move or elsewhere, no trusted sensor, a process restart and a gap nothing watched - and the report sends its age.
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

    private fun StillOrigin.since() = reportedAgeMs(still = true, nowMs = capFix + minute)?.let { capFix + minute - it }

    @Test
    fun `the owner's evening keeps the fire's time and sends its age`() {
        val o = stillFromFire()
            .afterFreshFix(atFix, moving = false, place = Place.SAME, noSensor = false)
            .afterFreshFix(capFix, moving = false, place = Place.SAME, noSensor = false)
        assertEquals(fire, o.since())
        assertEquals(capFix + minute - fire, o.reportedAgeMs(still = true, nowMs = capFix + minute))
        assertNull(o.reportedAgeMs(still = false, nowMs = capFix))
        assertEquals("never a negative age", 0L, o.reportedAgeMs(still = true, nowMs = fire - minute))
    }

    @Test
    fun `a fix that isn't at the last fresh fix's place starts it, whatever the gate said`() {
        // The drive after the fire (code QA M1): the first fresh fix after it is elsewhere.
        assertEquals(atFix, stillFromFire().afterFreshFix(atFix, false, Place.APART, noSensor = false).since())
        // Unknown under STILL (code QA H2): no accuracy, or no fix to compare with.
        assertEquals(capFix, stillFromFire().afterFreshFix(capFix, false, Place.UNKNOWN, noSensor = false).since())
        // No trusted sensor (code QA H1: D7's distrust) - each fix starts it, so the end of the distrust brings back no
        // older time.
        val distrusted = stillFromFire().afterFreshFix(atFix, false, Place.SAME, noSensor = true)
        assertEquals(atFix, distrusted.since())
        assertEquals(atFix, distrusted.afterFreshFix(capFix, false, Place.SAME, noSensor = false).since())
    }

    @Test
    fun `a fire or a mark starts it again and nothing is reported until the trigger watches`() {
        val fired = stillFromFire().fired(capFix)
        assertNull(fired.since())
        assertEquals(capFix, fired.armed(capFix + 5_000).since())
        assertEquals(capFix, stillFromFire().marked(capFix).since())
    }

    @Test
    fun `an arm after a gap starts the time at the arm`() {
        // Without zones the trigger waits for the next sync: such a phone shows that sync, not the fire.
        assertEquals(atFix, StillOrigin().fired(fire).armed(atFix).since())
        assertEquals(fire, StillOrigin().fired(fire).armed(fire + STILL_REARM_SLACK_MS).sinceMs)
        assertEquals(fire + STILL_REARM_SLACK_MS + 1, StillOrigin().fired(fire).armed(fire + STILL_REARM_SLACK_MS + 1).sinceMs)
        // Disarmed (Regularly ended, the anchor stopped): the next arm starts it.
        val gap = stillFromFire().unwatched()
        assertNull(gap.since())
        assertEquals(capFix, gap.armed(capFix).since())
        assertNull(StillOrigin().since())
    }

    @Test
    fun `a fix on the move or a scan mismatch waits for a fix at rest, through fires and arms`() {
        val moving = stillFromFire().afterFreshFix(atFix, moving = true, place = Place.UNKNOWN, noSensor = false)
        assertNull(moving.since())
        assertEquals(capFix, moving.afterFreshFix(capFix, false, Place.SAME, noSensor = false).since())
        // Code QA M2: a fire and an arm before the fix at rest keep the wait.
        val fired = moving.fired(atFix + minute).armed(atFix + minute)
        assertNull(fired.since())
        assertEquals(capFix, fired.afterFreshFix(capFix, false, Place.SAME, noSensor = false).since())
        val mismatch = stillFromFire().mismatched(atFix)
        assertNull(mismatch.since())
        assertEquals(capFix, mismatch.afterFreshFix(capFix, false, Place.SAME, noSensor = false).since())
    }

    @Test
    fun `a restart keeps it only after a short gap and at the same place`() {
        val alive = stillFromFire().alive(atFix)
        val kept = ServerJson.decodeFromString(
            StillOrigin.serializer(),
            ServerJson.encodeToString(StillOrigin.serializer(), alive),
        ).afterRestart(atFix + 10 * minute).armed(atFix + 10 * minute)
        assertNull(kept.since())
        assertEquals(fire, kept.afterFreshFix(capFix, false, Place.SAME, noSensor = false).since())
        assertEquals(capFix, kept.afterFreshFix(capFix, false, Place.APART, noSensor = false).since())
        assertEquals(capFix, kept.afterFreshFix(capFix, false, Place.UNKNOWN, noSensor = false).since())
        // A fire after the restart is the new start.
        assertEquals(capFix, kept.fired(capFix).armed(capFix).since())
        // A long gap (code QA L2): the next arm starts it.
        val late = atFix + STILL_RESTART_GAP_MAX_MS + 1
        assertEquals(late, alive.afterRestart(late).armed(late).since())
        // An origin that wasn't watching when the process ended starts at the next arm.
        assertEquals(atFix, StillOrigin().fired(fire).alive(fire).afterRestart(fire + minute).armed(atFix).since())
    }

    @Test
    fun `the place against the last fresh fix`() {
        assertEquals(Place.SAME, placeAgainst(home, fix(59.9141, timeMs = capFix)))
        assertEquals(Place.APART, placeAgainst(home, away))
        // Code QA H2: a far fix is far with its real accuracy; a wide one near is the same place.
        assertEquals(Place.APART, placeAgainst(fix(59.9139, accuracy = 300f), away))
        assertEquals(Place.SAME, placeAgainst(fix(59.9139, accuracy = 900f), away))
        assertEquals(Place.UNKNOWN, placeAgainst(null, home))
        assertEquals(Place.UNKNOWN, placeAgainst(home, fix(59.9229, accuracy = null)))
        assertEquals(Place.UNKNOWN, placeAgainst(home, fix(59.9229, accuracy = Float.NaN)))
    }
}
