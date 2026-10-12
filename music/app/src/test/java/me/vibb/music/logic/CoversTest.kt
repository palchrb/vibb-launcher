package me.vibb.music.logic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** A cover slot shows only what was decoded for the hash it shows now (the player kept podcast A's cover for B). */
class CoversTest {
    private val a = DecodedCover("hash-a", "image A")
    private val b = DecodedCover("hash-b", "image B")

    @Test
    fun `a slot that moves from A to B never shows A's image`() {
        assertEquals("image A", coverShown("hash-a", null, a))
        // B's hash, A's image still held: the gradient until B is decoded, and B is decoded.
        assertNull(coverShown("hash-b", null, a))
        assertTrue(needsDecode("hash-b", a))
        assertEquals("image B", coverShown("hash-b", null, b))
    }

    @Test
    fun `an image in memory for the hash shows at once`() {
        assertEquals("image B", coverShown("hash-b", "image B", a))
        assertEquals("image B", coverShown("hash-b", "image B", null))
    }

    @Test
    fun `no hash shows no image`() {
        assertNull(coverShown(null, null, a))
        assertFalse(needsDecode(null, a))
        assertFalse(needsDecode(null, null))
    }

    @Test
    fun `a slot decodes once per hash`() {
        assertTrue(needsDecode("hash-a", null))
        assertFalse(needsDecode("hash-a", a))
        // Back to A after B: A again.
        assertTrue(needsDecode("hash-a", b))
    }

    @Test
    fun `B's file missing leaves the gradient, not A`() {
        assertNull(coverShown("hash-b", null, a))
        assertNull(coverShown("hash-b", null, null))
    }
}
