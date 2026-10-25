package com.kidslauncher.mdm.timerules

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Design r13 §2.2-2.3: what the anchor's notification says ([timeLeftNotice], [leftMinutes]) and the glue's rules by
 * source - one notification, the plain one first, the action's intent, the refreshes. */
class TimeLeftNoticeTest {
    private fun budget(usedMin: Long, budget: Int?, extra: Int = 0) = BudgetUse(usedMin * 60_000L, budget, extra)

    @Test
    fun `the table - no budget, the pause, time left, used up, an unreadable ledger, each request state`() {
        assertEquals(PLAIN_NOTICE, timeLeftNotice(null, windowOpen = false, RequestState.Idle))
        assertEquals("no budget on today's weekday", PLAIN_NOTICE, timeLeftNotice(budget(10, null), false, RequestState.Idle))
        assertEquals("the pause", PLAIN_NOTICE, timeLeftNotice(budget(10, 90), windowOpen = true, RequestState.Idle))
        assertEquals(TimeLeftNotice(TimeLeftText.Left(80), AskPart.Action), timeLeftNotice(budget(10, 90), false, RequestState.Idle))
        assertEquals("extra minutes count", TimeLeftText.Left(95), timeLeftNotice(budget(10, 90, 15), false, RequestState.Idle).text)
        assertEquals(TimeLeftNotice(TimeLeftText.UsedUp, AskPart.Action), timeLeftNotice(budget(90, 90), false, RequestState.Idle))
        assertEquals("over the budget", TimeLeftText.UsedUp, timeLeftNotice(budget(120, 90), false, RequestState.Idle).text)
        assertEquals("a budget of 0", TimeLeftText.UsedUp, timeLeftNotice(budget(0, 0), false, RequestState.Idle).text)
        // An unreadable ledger counts as used up (its use is more than any budget).
        val unreadable = BudgetUse(2L * MINUTES_PER_DAY * 60_000L, 90, 0)
        assertEquals(TimeLeftText.UsedUp, timeLeftNotice(unreadable, false, RequestState.Idle).text)
        // The request's part: the action only while idle; "Spurt" as text; nothing while pending or capped.
        val left = budget(10, 90)
        assertEquals(AskPart.None, timeLeftNotice(left, false, RequestState.Pending).ask)
        assertEquals(AskPart.Asked(1234L), timeLeftNotice(left, false, RequestState.Asked(1234L)).ask)
        assertEquals(AskPart.None, timeLeftNotice(left, false, RequestState.Capped).ask)
        assertEquals("the title stays", TimeLeftText.Left(80), timeLeftNotice(left, false, RequestState.Capped).text)
    }

    @Test
    fun `minutes left round up - 1 min until the last second, never 0 min`() {
        assertEquals(1, leftMinutes(1))
        assertEquals(1, leftMinutes(60_000))
        assertEquals(2, leftMinutes(60_001))
        assertEquals(80, leftMinutes(80 * 60_000L))
        assertEquals(0, leftMinutes(0))
        assertEquals(0, leftMinutes(-5))
    }

    private fun file(path: String) = listOf("src/main/$path", "app/src/main/$path").map(::File).first { it.exists() }
    private fun source(path: String) = file("java/com/kidslauncher/mdm/$path").readText()

    @Test
    fun `one notification - the anchor's id and channel, the plain one literally first, the cache reset`() {
        val notice = source("server/AnchorNotice.kt")
        assertTrue(notice.contains("notify(COMMAND_LISTENER_NOTIFICATION_ID, build(context, notice))"))
        assertTrue(notice.contains("NotificationCompat.Builder(context, NOTIFICATION_CHANNEL_LISTENER)"))
        for (call in listOf(".setOnlyAlertOnce(true)", ".setSilent(true)", ".setShowWhen(false)", ".setOngoing(true)")) {
            assertTrue(call, notice.contains(call))
        }
        // The action asks for the unlock from Android's keyguard; "Spurt" is the content text, never a second action.
        assertTrue(notice.contains(".setAuthenticationRequired(true)"))
        assertEquals(1, Regex("addAction\\(").findAll(notice).count())
        assertTrue(notice.contains("is AskPart.Asked -> builder.setContentText("))
        // The broadcast names its receiver; the receiver isn't exported.
        assertTrue(notice.contains("Intent(context, TimeAskReceiver::class.java).setAction(ACTION_ASK_TIME)"))
        assertTrue(notice.contains("PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT"))
        val manifest = file("AndroidManifest.xml").readText()
        val entry = manifest.substringAfter("android:name=\".timerules.TimeAskReceiver\"").substringBefore("/>")
        assertTrue(entry, entry.contains("android:exported=\"false\""))
        // Posted only while the anchor runs, and only a changed notice or locale.
        assertTrue(notice.contains("if (!CommandListenerService.isRunning) return"))
        assertTrue(notice.contains("val key = notice to locale"))
        assertTrue(notice.contains("if (key == lastPosted) return"))
        // onCreate: the plain notification first and unconditionally, then the reset, then the refresh.
        val service = source("server/CommandListenerService.kt")
        val onCreate = service.substringAfter("override fun onCreate() {").substringBefore("\n    }")
        val lines = onCreate.lines().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("//") }
        assertEquals("super.onCreate()", lines[0])
        assertEquals("startForeground(COMMAND_LISTENER_NOTIFICATION_ID, AnchorNotice.plain(this))", lines[1])
        assertTrue(onCreate.indexOf("AnchorNotice.reset(this)") in 0 until onCreate.indexOf("AnchorNotice.refreshNow("))
        // Q-4: the tap arms the boundary alarm for the request's 30 minutes.
        val receiver = source("timerules/TimeAskReceiver.kt")
        assertTrue(receiver.indexOf("TimeRuleAlarm.schedule(app)") in 0 until receiver.indexOf("SyncRunner.request(app, \"time_request\")"))
    }

    @Test
    fun `the refreshes - every re-check with its snapshot, the minute tick while counting, the open request a boundary`() {
        val runtime = source("timerules/TimeRulesRuntime.kt")
        val recheck = runtime.substringAfter("fun recheck(").substringBefore("TimeRuleAlarm.schedule(context)")
        assertTrue(recheck, recheck.indexOf("reevaluateLockReasonFromCache(context, snap)") < recheck.indexOf("AnchorNotice.refresh(context, snap)"))
        val boundary = runtime.substringAfter("fun nextBoundary(context: Context)").substringBefore("\n    }")
        assertTrue(boundary, boundary.contains("TimeRequests.openUntilMs(context)"))
        val tracker = source("timerules/ScreenTimeTracker.kt")
        val update = tracker.substringAfter("fun update(context: Context) {")
        assertTrue(update, update.contains("handler.postDelayed(checkpoint, checkpointDelayMs(remaining))"))
        // Q-5: from the budget the tracker computed, never a snapshot of its own.
        assertTrue(update, update.contains("AnchorNotice.refresh(context, budget, policy, ledger.day, System.currentTimeMillis())"))
        assertFalse(update, update.contains("refreshNow") || update.contains("snapshot("))
        assertFalse(update.substringBefore("if (!appInUse(context)) return").contains("AnchorNotice"))
        val sync = source("server/MdmSyncWorker.kt")
        assertTrue(sync.indexOf("PowerEvents.upload(context, api)") < sync.indexOf("TimeRequests.upload(context, api)"))
    }
}
