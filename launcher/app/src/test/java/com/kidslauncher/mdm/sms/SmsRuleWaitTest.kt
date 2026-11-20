package com.kidslauncher.mdm.sms

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** The user, 2026-11-20: Meldinger posts nothing under a time rule without calls; each waiting conversation comes once,
 * quietly, when calls are allowed again (SmsRuleWait.kt). */
class SmsRuleWaitTest {
    @Test
    fun `a message or reaction under a rule without calls waits, otherwise as before`() {
        assertEquals(SmsPost.WAIT, smsPost(callsBlocked = true, again = false))
        assertEquals("a picture told again waits too", SmsPost.WAIT, smsPost(callsBlocked = true, again = true))
        assertEquals(SmsPost.QUIET, smsPost(callsBlocked = false, again = true))
        assertEquals(SmsPost.ALERT, smsPost(callsBlocked = false, again = false))
    }

    @Test
    fun `the waiting set gains the conversation and the one a group MMS left`() {
        assertEquals(setOf("C1"), smsWaitingAfter(emptySet(), listOf("C1", null)))
        assertEquals(setOf("C1", "C2"), smsWaitingAfter(setOf("C1"), listOf("C1", "C2")))
        assertEquals("the thread in front adds nothing", setOf("C1"), smsWaitingAfter(setOf("C1"), listOf(null)))
    }

    @Test
    fun `the release posts each waiting conversation once, only when calls are allowed, never the thread in front`() {
        assertEquals(emptyList<String>(), smsReleased(setOf("C1", "C2"), callsBlocked = true, inFront = null))
        assertEquals(listOf("C1", "C2"), smsReleased(setOf("C2", "C1"), callsBlocked = false, inFront = null))
        assertEquals(listOf("C2"), smsReleased(setOf("C1", "C2"), callsBlocked = false, inFront = "C1"))
        assertEquals(emptyList<String>(), smsReleased(emptySet(), callsBlocked = false, inFront = null))
    }

    private fun code(path: String): String =
        listOf("src/main/java/com/kidslauncher/mdm/$path", "app/src/main/java/com/kidslauncher/mdm/$path").map(::File).first { it.isFile }.readText()

    private fun body(text: String, start: String): String = text.substringAfter(start).substringBefore("\n    }\n")

    @Test
    fun `the wait comes before any post, and the release is quiet - no wake, no buzz`() {
        val runtime = code("sms/SmsRuntime.kt")
        for (name in listOf("private fun notifyShown(", "private fun notifyAttached(")) {
            val b = body(runtime, name)
            val wait = b.indexOf("== SmsPost.WAIT")
            assertTrue(name, wait >= 0 && wait < b.indexOf("SmsNotifier."))
            assertTrue(name, b.substringAfter("== SmsPost.WAIT").substringBefore("return").contains("waitForRule(app, "))
        }
        val release = body(runtime, "fun releaseAfterRule(")
        assertTrue(release.contains("if (blocked) return@execute"))
        assertTrue(release.indexOf("SmsPrefs.ruleWaiting(app, emptySet())") < release.indexOf("SmsNotifier.afterMove(app, engine, id)"))
        // The release's post is afterMove's: the silent channel and no wake.
        val notifier = code("sms/SmsNotifier.kt")
        assertTrue(body(notifier, "fun afterMove(").contains("notify(context, engine, row, newest, silent = true, wake = false)"))
        assertEquals(NOTIFICATION_CHANNEL_MESSAGES_SILENT, smsChannel(silent = true, reaction = false, passesFilter = true))
        // Every place the lock is decided releases.
        val waiting = code("timerules/RuleWaitingNotices.kt")
        assertTrue(waiting.contains("SmsRuntime.releaseAfterRule(context)") && waiting.contains("MissedCallNotifier.releaseAfterRule(context)"))
        assertTrue(code("timerules/TimeRulesRuntime.kt").contains("RuleWaitingNotices.release(context)"))
        assertTrue(code("server/AppEnforcer.kt").contains("RuleWaitingNotices.release(context)"))
        assertFalse(runtime.contains("silent = TimeRulesStore.callsBlockedNow()"))
        // The missed-call notification: every pass's step goes through the rule; the release's alerts for nothing.
        val missed = code("calls/MissedCallNotifier.kt")
        assertTrue(missed.contains("val step = noticeStepUnderRule(planned, blocked, release)"))
        assertTrue(missed.contains("val blocked = !release && com.kidslauncher.mdm.timerules.TimeRulesStore.callsBlockedNow()"))
        assertTrue(body(missed, "fun releaseAfterRule(").contains("recomputeNow(app, mayPost = true, mayClear = false, release = true)"))
        // Code QA H1: the wait and its alerted bound in one commit.
        val wait = missed.substringAfter("if (noticeWaits(planned, blocked)) {").substringBefore(".commit()")
        assertTrue(wait, wait.contains(".putBoolean(KEY_RULE_WAITING, true)") && wait.contains(".putLong(KEY_ALERTED_UP_TO, alertedAfterWait("))
        // Code QA L2: a release whose post failed waits again, on both sides.
        assertTrue(missed.contains("} else if (release) {"))
        assertTrue(body(runtime, "fun releaseAfterRule(").contains("if (failed.isNotEmpty()) SmsPrefs.ruleWaiting(app, smsWaitingAfter(SmsPrefs.ruleWaiting(app), failed))"))
        // Code QA L1: a setup that ran before the user was unlocked releases at the unlock.
        val application = code("Application.kt")
        assertTrue(application.contains("if (!com.kidslauncher.mdm.calls.CallPolicyStore.userUnlocked(this)) releaseWaitingAtUnlock()"))
        assertTrue(body(application, "private fun releaseWaitingAtUnlock()").contains("RuleWaitingNotices.release(app)"))
    }
}
