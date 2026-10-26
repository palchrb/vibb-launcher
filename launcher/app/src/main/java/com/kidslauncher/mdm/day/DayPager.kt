package com.kidslauncher.mdm.day

import java.time.LocalDate
import java.time.temporal.ChronoUnit

/*
 * Dagen's endless pager (design 69 §2.1): a ViewPager2 over Int.MAX_VALUE positions, the day by an offset from an
 * anchor (today when the page was built). Pure; DayPagerTest.
 */

/** The anchor's position: about a billion days either way, never reached. */
const val DAY_CENTER = Int.MAX_VALUE / 2

fun dayAt(anchor: LocalDate, position: Int): LocalDate = anchor.plusDays((position - DAY_CENTER).toLong())

fun positionOf(anchor: LocalDate, day: LocalDate): Int {
    val offset = ChronoUnit.DAYS.between(anchor, day).coerceIn(-DAY_CENTER.toLong() + 1, DAY_CENTER.toLong() - 1)
    return DAY_CENTER + offset.toInt()
}

/** The jump back to today: on a day after today it reads "← I dag", before it "I dag →" (the arrow towards today);
 * none on today. */
enum class TodayJump { BACK_FROM_LATER, FORWARD_FROM_EARLIER }

/** [TodayJump] for a day [offsetDays] from today (shown day - today). */
fun todayJump(offsetDays: Long): TodayJump? = when {
    offsetDays > 0 -> TodayJump.BACK_FROM_LATER
    offsetDays < 0 -> TodayJump.FORWARD_FROM_EARLIER
    else -> null
}
