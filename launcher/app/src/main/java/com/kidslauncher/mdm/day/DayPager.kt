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

/** Away from Dagen this long (stopped, or its saved state this old) and it opens on today again (the user,
 * 2026-11-01: a day swiped to earlier is not where Dagen should open later). */
const val DAY_BACK_TO_TODAY_MS = 60_000L

/** Whether Dagen opens on today after [awayMs] away; `null` = not away (first start, or a day asked for); a negative
 * span (the clock set back) counts as away. */
fun backToToday(awayMs: Long?): Boolean = awayMs != null && (awayMs >= DAY_BACK_TO_TODAY_MS || awayMs < 0)

/** The jump back to today: on a day after today it reads "← I dag", before it "I dag →" (the arrow towards today);
 * none on today. */
enum class TodayJump { BACK_FROM_LATER, FORWARD_FROM_EARLIER }

/** [TodayJump] for a day [offsetDays] from today (shown day - today). */
fun todayJump(offsetDays: Long): TodayJump? = when {
    offsetDays > 0 -> TodayJump.BACK_FROM_LATER
    offsetDays < 0 -> TodayJump.FORWARD_FROM_EARLIER
    else -> null
}
