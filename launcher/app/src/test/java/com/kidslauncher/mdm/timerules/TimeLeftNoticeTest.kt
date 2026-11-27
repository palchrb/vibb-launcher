package com.kidslauncher.mdm.timerules

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Design r13 §2.2-2.3, design 100 §14.3: what the anchor's notification says ([timePart], [leftMinutes]) and the
 * glue's rules by source - one notification, the plain one first, the action's intent, the refreshes. */
class TimeLeftNoticeTest {
    private fun budget(usedMin: Long, budget: Int?, extra: Int = 0) = BudgetUse(usedMin * 60_000L, budget, extra)

    private fun part(budget: BudgetUse?, windowOpen: Boolean, state: RequestState) = timePart(budget, windowOpen, state, null, 0L)

    @Test
    fun `the table - no budget, the pause, time left, used up, an unreadable ledger, each request state`() {
        assertEquals(PLAIN_NOTICE, part(null, windowOpen = false, RequestState.Idle))
        assertEquals("no budget on today's weekday", PLAIN_NOTICE, part(budget(10, null), false, RequestState.Idle))
        assertEquals("the pause", PLAIN_NOTICE, part(budget(10, 90), windowOpen = true, RequestState.Idle))
        assertEquals(TimePart(TimeLeftText.Left(80), AskNote.None, AskPart.Action), part(budget(10, 90), false, RequestState.Idle))
        assertEquals("extra minutes count", TimeLeftText.Left(95), part(budget(10, 90, 15), false, RequestState.Idle).text)
        assertEquals(TimePart(TimeLeftText.UsedUp, AskNote.None, AskPart.Action), part(budget(90, 90), false, RequestState.Idle))
        assertEquals("over the budget", TimeLeftText.UsedUp, part(budget(120, 90), false, RequestState.Idle).text)
        assertEquals("a budget of 0", TimeLeftText.UsedUp, part(budget(0, 0), false, RequestState.Idle).text)
        // An unreadable ledger counts as used up (its use is more than any budget).
        val unreadable = BudgetUse(2L * MINUTES_PER_DAY * 60_000L, 90, 0)
        assertEquals(TimeLeftText.UsedUp, part(unreadable, false, RequestState.Idle).text)
        // The ask's part: the action while idle or answered under three; "asked" while open; nothing when capped.
        val left = budget(10, 90)
        assertEquals(AskPart.Asked(1234L), part(left, false, RequestState.Pending(1234L)).ask)
        assertEquals(AskPart.Asked(1234L), part(left, false, RequestState.Waiting(1234L)).ask)
        assertEquals(AskPart.Action, part(left, false, RequestState.Answered(true, "Pappa", 1, null, canAskAgain = true)).ask)
        assertEquals(AskPart.None, part(left, false, RequestState.Answered(true, "Pappa", 1, null, canAskAgain = false)).ask)
        assertEquals(AskPart.None, part(left, false, RequestState.Capped).ask)
        assertEquals("the title stays", TimeLeftText.Left(80), part(left, false, RequestState.Capped).text)
        // No budget today, or the pause: no note either (§3.1).
        assertEquals(AskNote.None, timePart(null, false, RequestState.Waiting(1), null, 2).note)
        assertEquals(AskNote.None, timePart(left, true, RequestState.Waiting(1), null, 2).note)
    }

    /** QA R2-L3: the shade's note changes by itself at the 15-second mark and at an answer's or a gift's 30 minutes -
     * the boundary alarm's instant (where r13's `openUntilMs` was). */
    @Test
    fun `the note's own changes are boundaries`() {
        assertEquals(1_000L + SENDING_OFFLINE_MS, noteChangeMs(RequestState.Pending(1_000), null, 2_000))
        assertNull("past it", noteChangeMs(RequestState.Pending(1_000), null, 1_000 + SENDING_OFFLINE_MS))
        assertNull(noteChangeMs(RequestState.Waiting(1_000), null, 2_000))
        assertEquals(5_000L + ANSWER_NOTE_SHADE_MS, noteChangeMs(RequestState.Answered(true, null, 5_000, null, true), null, 6_000))
        assertNull("an older server's answer has no instant", noteChangeMs(RequestState.Answered(false, null, 0, 15, true), null, 6_000))
        val gift = TimePolicy(lifts = listOf(Lift(id = 3, target = TARGET_BUDGET, minutes = 30, by = "Mamma", atMs = 5_000)))
        assertEquals(5_000L + ANSWER_NOTE_SHADE_MS, noteChangeMs(RequestState.Idle, gift, 6_000))
        assertNull(noteChangeMs(RequestState.Idle, null, 6_000))
    }

    /** D10 (QA R2-L2): only the PWA's screen time is a gift - never a rule pause, design 93's twin or design 98's
     * parent-code pause; the newest one; the answer's name when the lift answered a request (R2-L10). */
    @Test
    fun `a gift is the PWA's newest screen-time lift`() {
        val budgetLift = Lift(id = 3, target = TARGET_BUDGET, minutes = 30, by = "Mamma", atMs = 5_000)
        assertEquals(budgetLift, givenLift(TimePolicy(lifts = listOf(budgetLift))))
        for (other in listOf(
            budgetLift.copy(target = TARGET_RULE),
            budgetLift.copy(id = -3),
            budgetLift.copy(phoneKey = "0123456789abcdef0123456789abcdef"),
            budgetLift.copy(atMs = 0),
        )) {
            assertNull("$other", givenLift(TimePolicy(lifts = listOf(other))))
        }
        val newer = budgetLift.copy(id = 4, atMs = 6_000, by = "Pappa")
        assertEquals(newer, givenLift(TimePolicy(lifts = listOf(newer, budgetLift))))
        val answered = TimePolicy(lifts = listOf(newer), answer = TimeAnswer("a".repeat(32), "yes", "Pappa (før)", 6_000, liftId = 4, minutes = 30))
        assertEquals("Pappa (før)", liftBy(newer, answered))
        assertNull("a blank name is none (D11)", liftBy(newer.copy(by = "  "), null))
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
    fun `one notification - the plain notice's builder and its action`() {
        val notice = source("server/AnchorNotice.kt")
        assertTrue(notice.contains("NotificationCompat.Builder(context, NOTIFICATION_CHANNEL_LISTENER)"))
        for (call in listOf(".setOnlyAlertOnce(true)", ".setSilent(true)", ".setShowWhen(false)", ".setOngoing(true)")) {
            assertTrue(call, notice.contains(call))
        }
        // The action asks for the unlock from Android's keyguard, only while the kid may ask; the note is the content
        // text, never a second action.
        assertTrue(notice.contains(".setAuthenticationRequired(true)"))
        assertEquals(1, Regex("addAction\\(").findAll(notice).count())
        assertTrue(notice.contains("if (time.ask == AskPart.Action) {"))
        assertTrue(notice.contains("noteText(context, time.note)?.let { builder.setContentText(it) }"))
        // The broadcast names its receiver; the receiver isn't exported.
        assertTrue(notice.contains("Intent(context, TimeAskReceiver::class.java).setAction(ACTION_ASK_TIME)"))
        assertTrue(notice.contains("PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT"))
        val manifest = file("AndroidManifest.xml").readText()
        val entry = manifest.substringAfter("android:name=\".timerules.TimeAskReceiver\"").substringBefore("/>")
        assertTrue(entry, entry.contains("android:exported=\"false\""))
        // Design 100 §2.2: AnchorNotice computes and hands over; it posts nothing.
        assertFalse(notice.contains(".notify("))
        assertTrue(notice.contains("AnchorNotification.notice(context, time)"))
        // Q-4: the tap arms the boundary alarm (the note's 15-second mark, QA R2-L3) before the sync.
        val ask = source("timerules/TimeAsk.kt")
        assertTrue(ask.indexOf("TimeRuleAlarm.schedule(app)") in 0 until ask.indexOf("SyncRunner.request(app, \"time_request\")"))
    }

    /** Design 100 §8.4: one request path - the notice's action, the Mer tid disc and the time-rule screen ask through
     * `TimeAsk.askNow`, and nothing else calls `TimeRequests.ask`. */
    @Test
    fun `one request path`() {
        assertTrue(source("timerules/TimeAskReceiver.kt").contains("TimeAsk.askNow(context)"))
        val shortcuts = source("shortcuts/ShortcutsRuntime.kt")
        assertTrue(shortcuts.substringAfter("private fun askForTime(app: Context)").contains("TimeAsk.askNow(app)"))
        assertTrue(source("ui/LockActivity.kt").contains("TimeAsk.askNow(this)"))
        val java = file("java/com/kidslauncher/mdm")
        val asking = java.walk().filter { it.isFile && it.extension == "kt" }
            .filter { Regex("""TimeRequests\.ask\(""").containsMatchIn(it.readText()) }
            .map { it.relativeTo(java).path }.toList()
        assertEquals(listOf("timerules/TimeAsk.kt"), asking)
    }

    /** Q100-H1, Q100-L3: the listeners (the time-rule screen) are told on the main thread, whatever thread the refresh
     * ran on, and also while the anchor isn't running - only the post needs it. */
    @Test
    fun `the listeners run on the main thread, the anchor or not`() {
        val notice = source("server/AnchorNotice.kt")
        val dispatch = body(notice, "private fun dispatch()")
        assertTrue(dispatch, dispatch.trimStart().startsWith("main.post {"))
        assertTrue(dispatch, dispatch.contains("for (listener in listeners)"))
        assertEquals(1, Regex("""for \(listener in listeners\)""").findAll(notice).count())
        assertTrue(notice.contains("private val main = android.os.Handler(android.os.Looper.getMainLooper())"))
        assertFalse(body(notice, "fun refreshNow(context: Context)").contains("isRunning"))
    }

    /** The body of `fun <signature>`: up to the closing brace at the function's indent. */
    private fun body(text: String, signature: String): String {
        val start = text.indexOf(signature)
        assertTrue("$signature not found", start >= 0)
        val indent = text.substring(text.lastIndexOf('\n', start) + 1, start)
        val open = text.indexOf("{\n", start) + 2
        return text.substring(open, text.indexOf("\n$indent}\n", open))
    }

    @Test
    fun `one poster - only it posts 1002, a change at most once a second, only while the anchor runs`() {
        val poster = source("server/AnchorNotification.kt")
        // No other notify of 1002 anywhere (design 100 §2.2).
        val java = file("java/com/kidslauncher/mdm")
        val posting = java.walk().filter { it.isFile && it.extension == "kt" }
            .filter { Regex("""notify\(\s*COMMAND_LISTENER_NOTIFICATION_ID""").containsMatchIn(it.readText()) }
            .map { it.relativeTo(java).path }.toList()
        assertEquals(listOf("server/AnchorNotification.kt"), posting)
        assertEquals(1, Regex("""\.notify\(""").findAll(poster).count())
        // The check and the notify are one step under the lock the anchor's onDestroy takes (Q100-L5).
        val post = body(poster, "private fun post(app: Context, view: AnchorView)")
        val locked = post.substringAfter("synchronized(lock) {\n")
        assertTrue(post, locked.trimStart().startsWith("if (!CommandListenerService.isRunning) return"))
        assertTrue(post, locked.indexOf(".notify(COMMAND_LISTENER_NOTIFICATION_ID, notification)") > 0)
        val service = source("server/CommandListenerService.kt")
        val onDestroy = body(service, "override fun onDestroy()")
        assertTrue(onDestroy, Regex("""synchronized\(AnchorNotification\.lock\) \{\s*if \(running === this\) running = null\s*\}""").containsMatchIn(onDestroy))
        // Only a changed view (the view, the locale, the channel) is posted.
        assertTrue(post, post.contains("val key = Key(view, locale(app), channelOf(app, view))"))
        assertTrue(post, post.contains("if (key == synchronized(lock) { posted }) return"))
        // M7: every build goes through ensuredBuild, which makes sure of the view's channel before it builds.
        assertEquals(1, Regex("""AnchorNotice\.build\(""").findAll(poster).count())
        assertEquals(1, Regex("""ShortcutsNotification\.build\(""").findAll(poster).count())
        val ensured = body(poster, "private fun ensuredBuild(context: Context, key: Key)")
        for (build in listOf("AnchorNotice.build(", "ShortcutsNotification.build(")) {
            assertTrue(build, ensured.indexOf("createListenerChannel(context)") in 0 until ensured.indexOf(build))
            assertTrue(build, ensured.indexOf("ShortcutsRuntime.ensureChannel(context, key.channel)") in 0 until ensured.indexOf(build))
        }
        // Q100-M1: no panel model (it throws) is the plain notice with the time, never nothing.
        val refresh = body(poster, "private fun refreshNow()")
        assertTrue(refresh, refresh.contains("post(app, anchorView(panelFor(app, time), time))"))
        val panelFor = poster.substringAfter("private fun panelFor(app: Context, time: TimePart) = try {").substringBefore("\n    }\n")
        assertTrue(panelFor, panelFor.contains("ShortcutsRuntime.panelFor(app, time.ask)"))
        assertTrue(panelFor, panelFor.contains("} catch (e: Exception) {") && panelFor.trimEnd().endsWith("null"))
        // Q100-L4/R2-L11: the trailing slot compares the channel and the view's key, and posts again once.
        val verify = body(poster, "private fun verify()")
        assertTrue(verify, verify.contains("up.channelId == key.channel && up.extras.getInt(EXTRA_VIEW_KEY) == key.hashCode()"))
        assertTrue(verify, verify.contains("if (repostedFor == key) return"))
    }

    @Test
    fun `the anchor's start - the poster's view literally first, from the last key, never the panel model`() {
        val service = source("server/CommandListenerService.kt")
        val onCreate = service.substringAfter("override fun onCreate() {").substringBefore("\n    }")
        val lines = onCreate.lines().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("//") }
        assertEquals("super.onCreate()", lines[0])
        assertEquals("startForeground(COMMAND_LISTENER_NOTIFICATION_ID, AnchorNotification.forStart(this))", lines[1])
        assertTrue(onCreate.indexOf("AnchorNotification.anchorStarted(this)") in 0 until onCreate.indexOf("AnchorNotice.refreshNow("))
        // Q100-L7: no panel model, no prefs-reading channel id; every exception falls back to the plain notice.
        val poster = source("server/AnchorNotification.kt")
        val forStart = body(poster, "fun forStart(context: Context): Notification")
        for (call in listOf("computePanel(", "channelId(", "currentChannelId(", "panelFor(")) {
            assertFalse(call, forStart.contains(call))
        }
        assertTrue(forStart, forStart.contains("} catch (e: Exception) {"))
        assertTrue(forStart, forStart.indexOf("createListenerChannel(app)") in 0 until forStart.indexOf("AnchorNotice.plain(app)"))
        // Code QA L5: the fallback carries the time part already cached, else the bare plain notice.
        assertTrue(forStart, forStart.indexOf("createListenerChannel(app)") in 0 until forStart.indexOf("AnchorNotice.plain(app, it)"))
        // Design 99's old panel cancelled at the start (M9).
        assertTrue(body(poster, "fun anchorStarted(context: Context)").contains("cancel(DESIGN_99_PANEL_ID)"))
    }

    @Test
    fun `the refreshes - every re-check with its snapshot, the minute tick while counting, the note's change a boundary`() {
        val runtime = source("timerules/TimeRulesRuntime.kt")
        val recheck = runtime.substringAfter("fun recheck(").substringBefore("TimeRuleAlarm.schedule(context)")
        assertTrue(recheck, recheck.indexOf("reevaluateLockReasonFromCache(context, snap)") < recheck.indexOf("AnchorNotice.refresh(context, snap)"))
        val boundary = runtime.substringAfter("fun nextBoundary(context: Context)").substringBefore("\n    }")
        assertTrue(boundary, boundary.contains("TimeRequests.noteChangeMs(context, ledger(context, policy, clocks, zone()).day, policy, clocks.wallMs)"))
        assertFalse(boundary, boundary.contains("openUntilMs"))
        val tracker = source("timerules/ScreenTimeTracker.kt")
        val update = tracker.substringAfter("fun update(context: Context) {")
        assertTrue(update, update.contains("handler.postDelayed(checkpoint, checkpointDelayMs(remaining))"))
        // Q-5: from the budget the tracker computed, never a snapshot of its own.
        assertTrue(update, update.contains("AnchorNotice.refresh(context, budget, policy, ledger.day, com.kidslauncher.mdm.server.TrustedClock.nowMs())"))
        assertFalse(update, update.contains("refreshNow") || update.contains("snapshot("))
        // Design 139: the groups' fold before it notices nothing itself (its own refresh runs after the fold).
        assertTrue(update.contains("if (!appInUse(context)) {"))
        assertFalse(update.substringBefore("if (!appInUse(context)) {").contains("AnchorNotice.refresh("))
        val sync = source("server/MdmSyncWorker.kt")
        assertTrue(sync.indexOf("PowerEvents.upload(context, api)") < sync.indexOf("TimeRequests.upload(context, api)"))
    }
}
