package me.vibb.music.logic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Design 53 §2.12 (the user's answer to Q1): a group's volume - together and per room, the parents' cap per room. */
class GroupVolumeTest {
    @Test
    fun `the group's value is the mean of its rooms`() {
        assertEquals("the sketch's board 4", 45, groupMean(listOf(55, 35)))
        assertEquals(51, groupMean(listOf(50, 51)))
        assertNull(groupMean(emptyList()))
    }

    @Test
    fun `a group move adds the same delta to every room from the drag's base, each clamped to 0-100 and the cap`() {
        val base = mapOf("stua" to 55, "kjokken" to 35)
        assertEquals("up 10", mapOf("stua" to 65, "kjokken" to 45), groupMove(base, 55, cap = null))
        assertEquals("with the cap 60 Stua never goes above it, Kjøkken still rises", mapOf("stua" to 60, "kjokken" to 45), groupMove(base, 55, cap = 60))
        assertEquals("dragging back within the drag restores the balance", base, groupMove(base, 45, cap = 60))
        assertEquals("a room at 0 stays at 0 on a lower", mapOf("stua" to 15, "kjokken" to 0), groupMove(base, 5, cap = null))
        assertEquals(mapOf("stua" to 100, "kjokken" to 90), groupMove(base, 100, cap = null))
    }

    @Test
    fun `a room at the cap stays there while the others rise, and a louder one set elsewhere is never pulled down by a raise`() {
        assertEquals(mapOf("stua" to 60, "kjokken" to 43), groupStep(mapOf("stua" to 60, "kjokken" to 40), 3, cap = 60))
        assertEquals("above the cap (the Sonos app's): a raise leaves it", mapOf("stua" to 80, "kjokken" to 43), groupStep(mapOf("stua" to 80, "kjokken" to 40), 3, cap = 60))
        assertEquals("a lower is our own set: clamped to the cap like every set of ours", mapOf("stua" to 60, "kjokken" to 37), groupStep(mapOf("stua" to 80, "kjokken" to 40), -3, cap = 60))
    }

    @Test
    fun `a key press is 3 to each room from its own volume`() {
        assertEquals(mapOf("stua" to 58, "kjokken" to 38), groupStep(mapOf("stua" to 55, "kjokken" to 35), 3, cap = null))
        assertEquals(mapOf("stua" to 52, "kjokken" to 0), groupStep(mapOf("stua" to 55, "kjokken" to 2), -3, cap = null))
    }

    @Test
    fun `one room alone is just that room - the controller keeps the lone room's own rule`() {
        assertEquals(mapOf("stua" to 40), groupMove(mapOf("stua" to 55), 40, cap = null))
        assertEquals("a cap outside 1-99 is none (as volumeTarget)", 100, roomMove(98, 3, cap = 100))
        assertEquals(emptyMap<String, Int>(), groupMove(emptyMap(), 40, cap = null))
    }
}
