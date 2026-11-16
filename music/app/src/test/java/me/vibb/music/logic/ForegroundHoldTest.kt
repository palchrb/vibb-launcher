package me.vibb.music.logic

import me.vibb.music.Fixtures.moduleFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** compat-fix-1: a media button's foreground start always ends in the foreground, then cleanly (logic/ForegroundHold.kt). */
class ForegroundHoldTest {
    private val t0 = 1_000_000L

    @Test
    fun `nothing to resume stays silent and stops at once`() {
        for (why in listOf(Unresumable.NOTHING, Unresumable.SUSPENDED, Unresumable.NOT_ON_PHONE)) {
            val asked = holdResumptionAsked(holdStarted(t0))
            assertEquals(HoldVerdict.WAIT, holdVerdict(asked, HoldShown.PLACEHOLDER, playing = false, t0 + 100))
            val settled = holdResumptionSettled(asked, why, t0 + 150)
            assertEquals(why.name, HoldVerdict.RELEASE_STOP, holdVerdict(settled, HoldShown.PLACEHOLDER, playing = false, t0 + 150))
            // Media3 already took its (empty) notification away: still stopped.
            assertEquals(HoldVerdict.RELEASE_STOP, holdVerdict(settled, HoldShown.NONE, playing = false, t0 + 150))
        }
    }

    @Test
    fun `a resumed queue hands the foreground to Media3`() {
        val settled = holdResumptionSettled(holdResumptionAsked(holdStarted(t0)), null, t0 + 300)
        assertEquals(HoldVerdict.DONE, holdVerdict(settled, HoldShown.MEDIA, playing = true, t0 + 400))
        // Not playing yet: the player gets its grace, then the hold lets go - keeping a paused queue's notification.
        assertEquals(HoldVerdict.WAIT, holdVerdict(settled, HoldShown.PLACEHOLDER, playing = false, t0 + 300 + HOLD_ITEMS_GRACE_MS - 1))
        assertEquals(HoldVerdict.RELEASE_DETACH, holdVerdict(settled, HoldShown.MEDIA, playing = false, t0 + 300 + HOLD_ITEMS_GRACE_MS))
        assertEquals(HoldVerdict.RELEASE_STOP, holdVerdict(settled, HoldShown.PLACEHOLDER, playing = false, t0 + 300 + HOLD_ITEMS_GRACE_MS))
    }

    @Test
    fun `a room or Spotify gets time to start`() {
        for (why in listOf(Unresumable.ON_ROOM, Unresumable.ON_SPOTIFY)) {
            assertTrue(why.elsewhere)
            val settled = holdResumptionSettled(holdResumptionAsked(holdStarted(t0)), why, t0 + 200)
            assertEquals(HoldVerdict.WAIT, holdVerdict(settled, HoldShown.PLACEHOLDER, playing = false, t0 + 200 + HOLD_ELSEWHERE_GRACE_MS - 1))
            assertEquals(HoldVerdict.DONE, holdVerdict(settled, HoldShown.MEDIA, playing = true, t0 + 2_000))
            assertEquals(HoldVerdict.RELEASE_STOP, holdVerdict(settled, HoldShown.PLACEHOLDER, playing = false, t0 + 200 + HOLD_ELSEWHERE_GRACE_MS))
        }
    }

    @Test
    fun `a button that never asks for a resumption is let go after the first look`() {
        val hold = holdStarted(t0)
        assertEquals(HoldVerdict.WAIT, holdVerdict(hold, HoldShown.PLACEHOLDER, playing = false, t0 + HOLD_FIRST_LOOK_MS - 1))
        assertEquals(HoldVerdict.RELEASE_STOP, holdVerdict(hold, HoldShown.PLACEHOLDER, playing = false, t0 + HOLD_FIRST_LOOK_MS))
        // A second press looks again.
        val again = holdPressedAgain(hold, t0 + 1_500)
        assertEquals(HoldVerdict.WAIT, holdVerdict(again, HoldShown.PLACEHOLDER, playing = false, t0 + HOLD_FIRST_LOOK_MS))
        assertEquals(HoldVerdict.DONE, holdVerdict(null, HoldShown.NONE, playing = false, t0))
    }

    @Test
    fun `a resumption that never settles ends at the cap`() {
        val stuck = holdResumptionAsked(holdStarted(t0))
        assertEquals(HoldVerdict.WAIT, holdVerdict(stuck, HoldShown.PLACEHOLDER, playing = false, t0 + HOLD_MAX_MS - 1))
        assertEquals(HoldVerdict.RELEASE_STOP, holdVerdict(stuck, HoldShown.PLACEHOLDER, playing = false, t0 + HOLD_MAX_MS))
        // Two asked, one settled: still waiting for the other.
        val two = holdResumptionSettled(holdResumptionAsked(stuck), Unresumable.NOTHING, t0 + 10)
        assertEquals(1, two.pending)
        assertEquals(HoldVerdict.WAIT, holdVerdict(two, HoldShown.PLACEHOLDER, playing = false, t0 + 20))
    }

    @Test
    fun `the service holds on every media button and refuses resumption only with a reason`() {
        val service = moduleFile("src/main/java/me/vibb/music/playback/PlaybackService.kt").readText()
        assertTrue("if (intent?.action == Intent.ACTION_MEDIA_BUTTON) holdForeground()" in service)
        assertTrue("startForeground(MEDIA_NOTIFICATION_ID," in service)
        // QA L2: a press during the hold after Media3 left the foreground enters it again; L3: a detached placeholder
        // is cancelled at the stop.
        val press = service.substringAfter("private fun holdForeground() {").substringBefore("\n    }\n")
        assertTrue("if (!inForeground && !enterForeground()) return" in press)
        assertTrue(press.indexOf("enterForeground()") < press.indexOf("holdPressedAgain(current, now)"))
        val stop = service.substringAfter("HoldVerdict.RELEASE_STOP -> {").substringBefore("stopSelf()")
        assertTrue("if (placeholder) getSystemService(NotificationManager::class.java)?.cancel(MEDIA_NOTIFICATION_ID)" in stop)
        val resumption = service.substringAfter("override fun onPlaybackResumption(").substringBefore("// --- starting a queue ---")
        // Both returns hand the future to the hold: the refusal before the coroutine and the coroutine's.
        assertEquals(2, Regex("""\.also \{ holdOn\(it\) \}""").findAll(resumption).count())
        assertTrue("future.addListener({ resumptionSettled(future) }, mainExecutor)" in service)
        assertFalse("UnsupportedOperationException(" in resumption)
        assertEquals(Unresumable.entries.size, Unresumable.entries.count { "ResumptionRefused(Unresumable.${it.name})" in resumption })
    }
}
