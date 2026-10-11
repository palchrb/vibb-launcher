package me.vibb.music.playback

import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import me.vibb.music.sonos.RendererChoice
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The startup gate (design 26a §4.7, QA H5; qa-26a-step3b-code L8): Home's RESUME, any command and
 * playback resumption wait until who plays is settled - at once on the phone, after the look at a
 * recorded room.
 */
class RendererGateTest {
    @Test
    fun `a RESUME sent before the renderer is settled waits for it`() = runBlocking {
        val gate = RendererGate()
        val ran = mutableListOf<String>()
        val resume = launch {
            gate.await()
            ran += "RESUME"
        }
        repeat(3) { yield() }
        assertTrue("nothing ran before the look", ran.isEmpty())
        assertFalse(gate.settled)
        ran += "adopted"
        gate.settle()
        resume.join()
        assertEquals(listOf("adopted", "RESUME"), ran)
        gate.settle() // settling twice is harmless
        assertTrue(gate.settled)
    }

    @Test
    fun `the start looks at a room only when renderer json names one`() {
        assertFalse(startupLook(RendererChoice.LOCAL_CHOICE))
        assertFalse("a room without its uid is the phone", startupLook(RendererChoice(RendererChoice.SONOS, uid = null)))
        assertTrue(startupLook(RendererChoice(RendererChoice.SONOS, "RINCON_KID01400", "Barnerom")))
        assertTrue("a recorded room with no built uri yet is looked at too (M1)", startupLook(RendererChoice(RendererChoice.SONOS, "RINCON_KID01400", "Barnerom", entryId = 1)))
    }
}
