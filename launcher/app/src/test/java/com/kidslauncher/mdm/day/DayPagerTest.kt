package com.kidslauncher.mdm.day

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/** Design 69 §2.1: the endless pager - position and day both ways, the anchor's move keeps the page's date, the
 * jump back's arrow points towards today. */
class DayPagerTest {
    private val today = LocalDate.of(2026, 10, 26)

    @Test
    fun `positions and days map both ways, a century out and back`() {
        assertEquals(today, dayAt(today, DAY_CENTER))
        assertEquals(today.plusDays(1), dayAt(today, DAY_CENTER + 1))
        assertEquals(today.minusDays(1), dayAt(today, DAY_CENTER - 1))
        for (day in listOf(today.plusYears(100), today.minusYears(100), today.plusDays(400), today.minusDays(400))) {
            val position = positionOf(today, day)
            assertTrue(position in 1 until Int.MAX_VALUE)
            assertEquals(day, dayAt(today, position))
        }
    }

    @Test
    fun `a new anchor keeps the shown date`() {
        val shown = today.plusDays(3)
        val position = positionOf(today, shown)
        val tomorrow = today.plusDays(1)
        assertEquals(shown, dayAt(tomorrow, positionOf(tomorrow, dayAt(today, position))))
        assertEquals(DAY_CENTER + 2, positionOf(tomorrow, shown))
    }

    @Test
    fun `the jump back points towards today`() {
        assertEquals(TodayJump.BACK_FROM_LATER, todayJump(1))
        assertEquals(TodayJump.BACK_FROM_LATER, todayJump(400))
        assertEquals(TodayJump.FORWARD_FROM_EARLIER, todayJump(-1))
        assertEquals(TodayJump.FORWARD_FROM_EARLIER, todayJump(-400))
        assertNull(todayJump(0))
    }
}
