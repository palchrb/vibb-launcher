package me.vibb.music.logic

import android.media.AudioManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The music app's call backstop on a Sonos room (design 26a §6.7): pause if we play, resume only what we paused. */
class CallPauseTest {
    private fun run(steps: List<Triple<Boolean, Boolean, Boolean>>, mayPlay: Boolean = true): List<CallAction> {
        var state = CallPause()
        return steps.map { (inCall, playing, paused) ->
            val (next, action) = callPauseStep(state, inCall, playing, paused, mayPlay)
            state = next
            action
        }
    }

    @Test
    fun `a system call rings or is up - never MODE_IN_COMMUNICATION alone`() {
        assertTrue(callMode(AudioManager.MODE_IN_CALL))
        assertTrue(callMode(AudioManager.MODE_RINGTONE))
        assertFalse("any app can hold it (design 17 QA #5)", callMode(AudioManager.MODE_IN_COMMUNICATION))
        assertFalse(callMode(AudioManager.MODE_NORMAL))
    }

    @Test
    fun `playing when the call starts - paused, and played again when it ends`() {
        val actions = run(listOf(Triple(true, true, false), Triple(true, false, true), Triple(false, false, true)))
        assertEquals(listOf(CallAction.PAUSE, CallAction.NONE, CallAction.PLAY), actions)
    }

    @Test
    fun `a pause made before the call is untouched`() {
        assertEquals(listOf(CallAction.NONE, CallAction.NONE), run(listOf(Triple(true, false, true), Triple(false, false, true))))
    }

    @Test
    fun `the kid's own play and pause during the call stay theirs`() {
        val actions = run(listOf(Triple(true, true, false), Triple(true, false, true), Triple(true, true, false), Triple(true, false, true), Triple(false, false, true)))
        assertEquals(listOf(CallAction.PAUSE, CallAction.NONE, CallAction.NONE, CallAction.NONE, CallAction.NONE), actions)
    }

    @Test
    fun `our pause not yet shown is not the kid's play`() {
        // the player still reads playing right after our pause went out
        val actions = run(listOf(Triple(true, true, false), Triple(true, true, false), Triple(true, false, true), Triple(false, false, true)))
        assertEquals(listOf(CallAction.PAUSE, CallAction.NONE, CallAction.NONE, CallAction.PLAY), actions)
    }

    @Test
    fun `nothing resumes while playing isn't allowed, or when it isn't paused any more`() {
        assertEquals(CallAction.NONE, run(listOf(Triple(true, true, false), Triple(true, false, true), Triple(false, false, true)), mayPlay = false).last())
        assertEquals("taken over, stopped: not ours to resume", CallAction.NONE, run(listOf(Triple(true, true, false), Triple(true, false, true), Triple(false, false, false))).last())
    }

    @Test
    fun `a release never plays after the sleep timer ended during the hold (qa-26a-step3b-code H1)`() {
        var state = CallPause()
        state = callPauseStep(state, inCall = true, playing = true, paused = false, mayPlay = true).first
        state = callPauseStep(state, inCall = true, playing = false, paused = true, mayPlay = true).first
        state = callTimerEnded(state)
        assertEquals(CallAction.NONE, callPauseStep(state, inCall = false, playing = false, paused = true, mayPlay = true).second)
        assertEquals("a timer ending outside a hold changes nothing", CallPause(inCall = true), callTimerEnded(CallPause(inCall = true)))
    }
}
