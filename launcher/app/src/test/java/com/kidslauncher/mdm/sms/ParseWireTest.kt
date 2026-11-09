package com.kidslauncher.mdm.sms

import com.kidslauncher.mdm.sms.mms.MmsFileRoutes
import com.kidslauncher.mdm.sms.parse.ParseWire
import com.kidslauncher.mdm.sms.parse.WireIn
import com.kidslauncher.mdm.sms.parse.WireMalformed
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Design 77 D8, code QA of step 3b (tests 7 and 8): what the main process judges in the isolated parse's answer - each
 * string's length and each count before the value, a picture's size, the message's picture budget - and what the MMS
 * provider serves. Pure: a [WireIn] double stands in for Android's `Parcel`.
 */
class ParseWireTest {
    /** A wire as `Parcel` lays it out: an int is one slot, a string is its length slot and then its value slot. */
    private class FakeWire(vararg values: Any?) : WireIn {
        private val slots = values.toList()
        override var position = 0
            private set

        override fun seek(position: Int) {
            this.position = position
        }

        override fun readInt(): Int = when (val v = slots[position++]) {
            is Int -> v
            is String -> v.length
            null -> -1
            else -> error("not an int")
        }

        /** A string occupies its length slot; its value is read from the same slot. */
        override fun readString(): String? = slots[position++] as String?
    }

    private fun malformed(block: () -> Unit) {
        try {
            block()
            fail("accepted")
        } catch (e: WireMalformed) {
            // expected
        }
    }

    @Test
    fun `a string's length is judged before it is read`() {
        assertEquals("abc", ParseWire.readString(FakeWire("abc"), 3))
        malformed { ParseWire.readString(FakeWire("abcd"), 3) }
        malformed { ParseWire.readString(FakeWire(null), 3) }
        assertNull(ParseWire.readString(FakeWire(null), 3, nullable = true))
        malformed { ParseWire.readString(FakeWire(-7), 3, nullable = true) }
        // The position goes back to the length before the value is read.
        val wire = FakeWire("ab", 5)
        assertEquals("ab", ParseWire.readString(wire, 10))
        assertEquals(5, wire.readInt())
    }

    @Test
    fun `a count is within its range`() {
        assertEquals(0, ParseWire.readCount(FakeWire(0), 20))
        assertEquals(20, ParseWire.readCount(FakeWire(20), 20))
        malformed { ParseWire.readCount(FakeWire(21), 20) }
        malformed { ParseWire.readCount(FakeWire(-1), 20) }
        malformed { ParseWire.readCount(FakeWire(Int.MIN_VALUE), 20) }
    }

    @Test
    fun `a picture's size is within the cap, its bytes width x height x 4`() {
        assertEquals(1600 * 1200 * 4, ParseWire.pixelBytes(1600, 1200, 1600))
        malformed { ParseWire.pixelBytes(1601, 10, 1600) }
        malformed { ParseWire.pixelBytes(10, 0, 1600) }
        malformed { ParseWire.pixelBytes(-1, 10, 1600) }
        malformed { ParseWire.pixelBytes(Int.MAX_VALUE, Int.MAX_VALUE, Int.MAX_VALUE) }
    }

    @Test
    fun `one message's pictures are bounded as a whole (code QA M1)`() {
        val budget = ParseWire.PictureBudget()
        repeat(ParseWire.MAX_PICTURES) { assertTrue(budget.take(1000, 1000)) }
        assertFalse(budget.roomForOne())
        assertFalse(budget.take(1, 1))
        // Twenty flat 1,600 x 1,600 pictures: only the first three fit the pixels.
        val big = ParseWire.PictureBudget()
        val taken = (1..20).count { big.take(1600, 1600) }
        assertEquals((ParseWire.MAX_PIXELS / (1600L * 1600)).toInt(), taken)
        assertTrue(taken.toLong() * 1600 * 1600 * 4 <= 32_000_000L)
    }

    @Test
    fun `the MMS provider serves a download for writing and a send for reading, nothing else`() {
        val id = "01M70GC5QD1PKZ3RBFFDF8ZVFA"
        val token = "0123456789abcdef0123456789abcdef"
        assertEquals(MmsFileRoutes.Route.Download(id), MmsFileRoutes.route(listOf("in", id), "w"))
        assertEquals(MmsFileRoutes.Route.Send(token), MmsFileRoutes.route(listOf("out", token), "r"))
        for ((segments, mode) in listOf(
            listOf("in", id) to "r", listOf("in", id) to "rw", listOf("in", id) to "wt", listOf("out", token) to "w",
            listOf("out", id) to "r", listOf("in", token) to "w", listOf("in", "../x") to "w", listOf("in", id.lowercase()) to "w",
            listOf("in") to "w", listOf("in", id, "x") to "w", listOf("share", token) to "r", emptyList<String>() to "r",
        )) {
            assertEquals("$segments $mode", MmsFileRoutes.Route.Refused, MmsFileRoutes.route(segments, mode))
        }
    }
}
