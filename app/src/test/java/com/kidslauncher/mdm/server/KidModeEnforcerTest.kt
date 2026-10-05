package com.kidslauncher.mdm.server

import com.kidslauncher.mdm.server.dto.PolicyResponse
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Calendar

class KidModeEnforcerTest {

    private fun calendarAt(dayOfWeek: Int, hour: Int, minute: Int): Calendar =
        Calendar.getInstance().apply {
            set(Calendar.DAY_OF_WEEK, dayOfWeek)
            set(Calendar.HOUR_OF_DAY, hour)
            set(Calendar.MINUTE, minute)
            set(Calendar.SECOND, 0)
        }

    @Test
    fun `an override or pause clears the lock, otherwise the schedule decides`() {
        val policy = PolicyResponse(bedtimeStartMinutes = 21 * 60, bedtimeEndMinutes = 7 * 60)
        val night = calendarAt(Calendar.MONDAY, 23, 0)
        assertEquals(LockReason.BEDTIME, KidModeEnforcer.lockReasonNow(policy, overrideActive = false, now = night))
        assertEquals(LockReason.NONE, KidModeEnforcer.lockReasonNow(policy, overrideActive = true, now = night))
        assertEquals(LockReason.NONE, KidModeEnforcer.lockReasonNow(null, overrideActive = false, now = night))
    }

    @Test
    fun `null policy is never locked`() {
        assertEquals(
            LockReason.NONE,
            KidModeEnforcer.evaluate(null, calendarAt(Calendar.MONDAY, 23, 0))
        )
    }

    @Test
    fun `policy with no schedule fields set is never locked`() {
        assertEquals(
            LockReason.NONE,
            KidModeEnforcer.evaluate(PolicyResponse(), calendarAt(Calendar.MONDAY, 23, 0))
        )
    }

    @Test
    fun `bedtime window wraps overnight and locks inside it`() {
        val policy = PolicyResponse(
            bedtimeStartMinutes = 21 * 60, // 21:00
            bedtimeEndMinutes = 7 * 60,    // 07:00
        )

        assertEquals(
            LockReason.BEDTIME,
            KidModeEnforcer.evaluate(policy, calendarAt(Calendar.MONDAY, 23, 0))
        )
        assertEquals(
            LockReason.BEDTIME,
            KidModeEnforcer.evaluate(policy, calendarAt(Calendar.MONDAY, 3, 0))
        )
        assertEquals(
            LockReason.BEDTIME,
            KidModeEnforcer.evaluate(policy, calendarAt(Calendar.MONDAY, 21, 0))
        )
        assertEquals(
            "bedtime end is exclusive",
            LockReason.NONE,
            KidModeEnforcer.evaluate(policy, calendarAt(Calendar.MONDAY, 7, 0))
        )
    }

    @Test
    fun `bedtime start equal to end means no restriction`() {
        val policy = PolicyResponse(
            bedtimeStartMinutes = 0,
            bedtimeEndMinutes = 0,
        )
        assertEquals(
            LockReason.NONE,
            KidModeEnforcer.evaluate(policy, calendarAt(Calendar.MONDAY, 0, 0))
        )
        assertEquals(
            LockReason.NONE,
            KidModeEnforcer.evaluate(policy, calendarAt(Calendar.MONDAY, 12, 0))
        )
    }

    @Test
    fun `weekday screen time window locks outside allowed hours`() {
        val policy = PolicyResponse(
            weekdayStartMinutes = 9 * 60,  // 09:00
            weekdayEndMinutes = 19 * 60,   // 19:00
        )

        assertEquals(
            LockReason.SCREEN_TIME,
            KidModeEnforcer.evaluate(policy, calendarAt(Calendar.TUESDAY, 8, 0))
        )
        assertEquals(
            LockReason.NONE,
            KidModeEnforcer.evaluate(policy, calendarAt(Calendar.TUESDAY, 12, 0))
        )
        assertEquals(
            LockReason.SCREEN_TIME,
            KidModeEnforcer.evaluate(policy, calendarAt(Calendar.TUESDAY, 19, 0))
        )
    }

    @Test
    fun `weekday start equal to end means always allowed`() {
        val policy = PolicyResponse(
            weekdayStartMinutes = 600,
            weekdayEndMinutes = 600,
        )
        assertEquals(
            LockReason.NONE,
            KidModeEnforcer.evaluate(policy, calendarAt(Calendar.TUESDAY, 3, 0))
        )
    }

    @Test
    fun `weekend uses weekend window not weekday window`() {
        val policy = PolicyResponse(
            weekdayStartMinutes = 9 * 60,
            weekdayEndMinutes = 19 * 60,
            weekendStartMinutes = 0,
            weekendEndMinutes = 0, // unrestricted on weekends
        )

        // Would be SCREEN_TIME under the weekday window, but it's a Saturday.
        assertEquals(
            LockReason.NONE,
            KidModeEnforcer.evaluate(policy, calendarAt(Calendar.SATURDAY, 3, 0))
        )
    }
}
