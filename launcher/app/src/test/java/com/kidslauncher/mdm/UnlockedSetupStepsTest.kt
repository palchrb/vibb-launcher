package com.kidslauncher.mdm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The rc.5 gate (§6bv finding 2): the unlocked setup's two parts, each once, in order. */
class UnlockedSetupStepsTest {
    private val log = mutableListOf<String>()
    private val scheduled = mutableListOf<() -> Unit>()
    private val failures = mutableListOf<Exception>()
    private val steps = UnlockedSetupSteps(
        rules = { log += "rules" },
        schedule = { scheduled += it },
        failed = { failures += it },
    )

    @Test
    fun `a process started unlocked runs both parts at once, nothing scheduled`() {
        steps.ensure(rulesNow = true) { log += "first" }
        assertEquals(listOf("first", "rules"), log)
        assertTrue(scheduled.isEmpty())
        assertFalse(steps.rulesScheduled)
    }

    @Test
    fun `a service, a provider or the unlock broadcast schedules the second part, which then runs once`() {
        steps.ensure(rulesNow = false) { log += "first" }
        assertEquals(listOf("first"), log)
        assertEquals(1, scheduled.size)
        assertTrue(steps.rulesScheduled)
        // A second service before it: nothing more.
        steps.ensure(rulesNow = false) { log += "first again" }
        assertEquals(1, scheduled.size)
        // The idle moment and the fallback both fire: it runs once.
        scheduled.single()()
        scheduled.single()()
        assertEquals(listOf("first", "rules"), log)
    }

    @Test
    fun `an activity or a receiver before the scheduled run runs it at once, the scheduled run then does nothing`() {
        steps.ensure(rulesNow = false) { log += "first" }
        steps.ensure(rulesNow = true) { log += "first again" }
        assertEquals(listOf("first", "rules"), log)
        scheduled.single()()
        assertEquals(listOf("first", "rules"), log)
    }

    @Test
    fun `the second part runs even when the first part throws`() {
        steps.ensure(rulesNow = false) { throw IllegalStateException("initRest") }
        assertEquals(1, failures.size)
        scheduled.single()()
        assertEquals(listOf("rules"), log)
        val now = UnlockedSetupSteps(rules = { log += "rules now" }, schedule = { scheduled += it }, failed = { failures += it })
        now.ensure(rulesNow = true) { throw IllegalStateException("initRest") }
        assertEquals(listOf("rules", "rules now"), log)
    }
}
