package com.kidslauncher.mdm.lock

import com.kidslauncher.mdm.calls.MissedCallNotice
import com.kidslauncher.mdm.calls.MissedContact
import com.kidslauncher.mdm.calls.NoticeStep
import com.kidslauncher.mdm.calls.noticeStep
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Launcher round 7 §3 (docs/design/r7-launcher-small-items.md §3.2): the notice wake's gate
 * ([noticeWakeAllowed]), its 15 s on-time ([noticeWakeOffDue]), the proximity rule, the missed-call
 * trigger (only the alerting post, on the main thread) and the glue by source.
 */
class LockWakeTest {
    private fun allowed(
        switchOn: Boolean = true,
        mode: LockMode = LockMode.LOCKED,
        interactive: Boolean = false,
        busy: NoticeBusy = NoticeBusy(),
        sinceLastLockUpMs: Long? = 60_000,
        sinceLastWakeMs: Long? = null,
        wakesThisHour: Int = 0,
        near: Boolean? = false,
    ) = noticeWakeAllowed(switchOn, mode, interactive, busy, sinceLastLockUpMs, sinceLastWakeMs, wakesThisHour, near)

    @Test
    fun `the gate - switch, LOCKED, dark`() {
        assertTrue(allowed())
        assertFalse("the switch off", allowed(switchOn = false))
        assertFalse(allowed(mode = LockMode.UNLOCKED))
        assertFalse(allowed(mode = LockMode.DISABLED))
        assertFalse("the screen is on", allowed(interactive = true))
    }

    @Test
    fun `every busy case keeps the screen dark`() {
        for (busy in listOf(
            NoticeBusy(ourCall = true), NoticeBusy(systemCall = true), NoticeBusy(voip = true),
            NoticeBusy(emergencyFlow = true), NoticeBusy(alarm = true), NoticeBusy(bootMark = true),
            NoticeBusy(sinceProcessStartMs = 0), NoticeBusy(sinceProcessStartMs = NOTICE_AFTER_START_MS - 1),
        )) {
            assertTrue(busy.toString(), busy.any)
            assertFalse(busy.toString(), allowed(busy = busy))
        }
        assertFalse(NoticeBusy(sinceProcessStartMs = NOTICE_AFTER_START_MS).any)
        assertTrue("2 minutes after the start", allowed(busy = NoticeBusy(sinceProcessStartMs = NOTICE_AFTER_START_MS)))
    }

    @Test
    fun `no wake within 2 s of a lock-up, at least 30 s apart, at most 20 an hour`() {
        assertFalse(allowed(sinceLastLockUpMs = 0))
        assertFalse(allowed(sinceLastLockUpMs = NOTICE_AFTER_LOCK_UP_MS - 1))
        assertTrue(allowed(sinceLastLockUpMs = NOTICE_AFTER_LOCK_UP_MS))
        assertTrue("no lock-up in this process", allowed(sinceLastLockUpMs = null))
        assertFalse(allowed(sinceLastWakeMs = WAKE_GAP_MS - 1))
        assertTrue(allowed(sinceLastWakeMs = WAKE_GAP_MS))
        assertTrue(allowed(wakesThisHour = WAKES_PER_HOUR - 1))
        assertFalse(allowed(wakesThisHour = WAKES_PER_HOUR))
        // The hour's list: older than an hour drops out, each wake adds one.
        val hour = 3_600_000L
        assertEquals(listOf(hour + 10, 2 * hour), wakesAfter(listOf(10L, hour + 10), 2 * hour))
        val wakes = (1..25).fold(emptyList<Long>()) { w, i -> wakesAfter(w, i * 60_000L) }
        assertEquals("one a minute: all 25 within the hour", 25, wakesInHour(wakes, 26 * 60_000L).size)
        assertEquals("an hour after minute 5", 20, wakesInHour(wakes, 65 * 60_000L).size)
    }

    @Test
    fun `proximity - near no wake, far wakes, no sensor or no answer wakes`() {
        assertFalse(allowed(near = true))
        assertTrue(allowed(near = false))
        assertTrue("no sensor, or the 300 ms passed", allowed(near = null))
        assertTrue(proximityNear(0f, 5f))
        assertFalse(proximityNear(5f, 5f))
        assertTrue("a sensor with a 1 cm range", proximityNear(0f, 1f))
        assertFalse(proximityNear(8f, 10f))
        assertTrue(proximityNear(3f, 10f))
        assertEquals(300L, PROXIMITY_BUDGET_MS)
    }

    @Test
    fun `the woken screen goes off at 15 s untouched, never after a touch`() {
        fun due(
            touched: Boolean = false, interactive: Boolean = true, locked: Boolean = true, resumed: Boolean = true,
            busy: Boolean = false, dialog: Boolean = false, exploring: Boolean = false, now: Long = 1_000 + NOTICE_WAKE_ON_MS,
        ) = noticeWakeOffDue(1_000, touched, interactive, locked, resumed, busy, dialog, exploring, now)
        assertEquals(15_000L, NOTICE_WAKE_ON_MS)
        assertTrue(due())
        assertFalse("early", due(now = 1_000 + NOTICE_WAKE_ON_MS - 1))
        assertFalse("touched: the phone's own timeout", due(touched = true))
        assertFalse("already off", due(interactive = false))
        assertFalse("unlocked meanwhile", due(locked = false))
        assertFalse("something over the lock", due(resumed = false))
        assertFalse("a call came", due(busy = true))
        // qa-l7-b-code 2: touches in the lock's own dialogs and TalkBack's never reach onUserInteraction.
        assertFalse("Foreldrekode or the emergency confirm is open", due(dialog = true))
        assertFalse("touch exploration is on", due(exploring = true))
        assertFalse("no wake on", noticeWakeOffDue(null, false, true, true, true, false, false, false, 99_999))
        // qa-l7-b-code 3: the wake's own screen-on keeps the 15 s, a later one (the kid's power press) ends them.
        assertFalse(noticeWakeEndsAtScreenOn(1_000, 1_000 + NOTICE_WAKE_SCREEN_ON_GRACE_MS))
        assertTrue(noticeWakeEndsAtScreenOn(1_000, 1_001 + NOTICE_WAKE_SCREEN_ON_GRACE_MS))
        assertFalse("no wake on", noticeWakeEndsAtScreenOn(null, 99_999))
    }

    /** Design 59d step E (the user's Q2): as Android's face no 15 s end - the phone's own timeout, the fingerprint kept. */
    @Test
    fun `as the face the notice wake has no 15 s end, own mode keeps its lockNow`() {
        val due = 1_000 + NOTICE_WAKE_ON_MS
        assertFalse(noticeWakeOffDue(1_000, false, true, true, true, false, false, false, due, face = true))
        assertFalse(noticeWakeOffDue(1_000, false, true, true, true, false, false, false, due + 600_000, face = true))
        assertTrue("own mode", noticeWakeOffDue(1_000, false, true, true, true, false, false, false, due, face = false))
        val runtime = listOf("src/main/java/com/kidslauncher/mdm/lock/PinLockRuntime.kt", "app/src/main/java/com/kidslauncher/mdm/lock/PinLockRuntime.kt")
            .map { File(it) }.first { it.exists() }.readText()
        val off = runtime.substringAfter("private fun noticeWakeOffCheck() {").substringBefore("\n    }\n")
        assertTrue(off, off.contains("face = AndroidLock.faceOn,"))
    }

    @Test
    fun `a missed call wakes only for the alerting post - not a boot re-send, relearn or an update after a call`() {
        val one = MissedCallNotice(1, listOf(MissedContact("+4790000002", "Pappa", 1, 100)), "+4790000002", 100, 7)
        val two = one.copy(count = 2, newestId = 9)
        // A new missed call: alerts.
        assertEquals(NoticeStep.Post(one, alert = true), noticeStep(one, true, mayPost = true, shown = false, lastPosted = null, unreadUpToId = 7, alertedUpToId = 0, newestLoggedId = 7))
        // The boot re-send: already alerted for it.
        assertEquals(NoticeStep.Post(one, alert = false), noticeStep(one, true, mayPost = true, shown = false, lastPosted = null, unreadUpToId = 7, alertedUpToId = 7, newestLoggedId = 7))
        // Relearn (a process start's pass): only what is shown, never anew.
        assertEquals(NoticeStep.Post(one, alert = false), noticeStep(one, true, mayPost = false, shown = true, lastPosted = null, unreadUpToId = 7, alertedUpToId = 7, newestLoggedId = 7, mayClear = false))
        // After our call: an update of what is shown.
        assertEquals(NoticeStep.Post(two.copy(newestId = 7), alert = false), noticeStep(two.copy(newestId = 7), true, mayPost = false, shown = true, lastPosted = one, unreadUpToId = 7, alertedUpToId = 7, newestLoggedId = 7))

        val notifier = code("calls/MissedCallNotifier.kt")
        val post = notifier.substringAfter("is NoticeStep.Post -> {").substringBefore("\n    }\n")
        val wake = "if (step.alert) main.post { com.kidslauncher.mdm.lock.PinLockRuntime.noticeCame() }"
        assertTrue(post, post.contains(wake))
        assertTrue("only after a post that worked", post.indexOf("if (post(context, step.notice, step.alert, unread.newestId)) {") in 0 until post.indexOf(wake))
        assertEquals("the one trigger in the notifier", 1, Regex("""noticeCame\(""").findAll(notifier).count())
    }

    private val main = listOf(File("src/main"), File("app/src/main")).first { it.exists() }
    private fun code(path: String) = File(main, "java/com/kidslauncher/mdm/$path").readText()
        .replace(Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL), "")
        .replace(Regex("//[^\\n]*"), "")

    @Test
    fun `the glue - one show with the wake, the switch from the cache and every sync, the touch, the screen-off`() {
        val runtime = code("lock/PinLockRuntime.kt")
        val came = runtime.substringAfter("fun noticeCame() {").substringBefore("\n    }\n")
        // The proximity read first, then the gate again with its answer, then the lock with the wake.
        assertTrue(came.indexOf("NoticeProximity.read(app, handler)") in 0 until came.indexOf("noticeAllowed(app, now, near)"))
        assertTrue(came.indexOf("noticeAllowed(app, now, near)") < came.indexOf("show(app, wake = WakeKind.NOTICE)"))
        assertTrue(came.contains("handler.postDelayed(noticeWakeOff, NOTICE_WAKE_ON_MS)"))
        // The 15 s are armed only for a start that went (qa-l7-b-code 3).
        val armed = came.substringAfter("if (show(app, wake = WakeKind.NOTICE)) {").substringBefore("}")
        assertTrue(armed, armed.contains("noticeWokeAt = now") && armed.contains("handler.postDelayed(noticeWakeOff, NOTICE_WAKE_ON_MS)"))
        assertTrue(runtime.contains("if (noticeWakeEndsAtScreenOn(noticeWokeAt, SystemClock.elapsedRealtime())) endNoticeWake()"))
        assertEquals("the one notice start", 1, Regex("""wake = WakeKind\.NOTICE""").findAll(runtime).count())
        // The wake window only ever starts inside show, right after the lock's own start.
        val show = runtime.substringAfter("    fun show(").substringBefore("\n    }\n")
        assertTrue(show.indexOf("Intent(context, PinLockActivity::class.java)") in 0 until show.indexOf("VoipWakeActivity.intent(context, WakeKind.NOTICE)"))
        assertEquals(2, Regex("""VoipWakeActivity\.intent\(""").findAll(runtime).count())
        val notice = show.substringAfter("WakeKind.NOTICE ->").substringBefore("\n")
        assertFalse("no ring bookkeeping for a notice", notice.contains("voipWake"))
        // The lock-up stamp, the screen-off's end, the switch.
        assertTrue(runtime.substringAfter("private fun lockUp(").substringBefore("\n    }\n").contains("lockUpAtElapsed = SystemClock.elapsedRealtime()"))
        assertTrue(runtime.contains("screenOffs++\n                endNoticeWake()"))
        assertTrue(runtime.contains("noticeWakeFromCache()"))
        assertTrue(code("server/MdmSyncWorker.kt").contains("com.kidslauncher.mdm.lock.PinLockRuntime.noticeWakeSwitch(freshPolicy.launcherUi)"))
        assertTrue(code("lock/PinLockActivity.kt").contains("override fun onUserInteraction() {\n        super.onUserInteraction()\n        PinLockRuntime.noticeWakeTouched()"))
        // The screen goes off through the device owner's lockNow, only when due.
        val off = runtime.substringAfter("private fun noticeWakeOffCheck() {").substringBefore("\n    }\n")
        assertTrue(off.indexOf("if (!due) return") in 0 until off.indexOf("lockNow()"))
        assertTrue(off.contains("dialogOpen = lockDialogsOpen > 0") && off.contains("touchExploration = touchExploration(app)"))
        val activity = code("lock/PinLockActivity.kt")
        assertEquals("opened, dismissed, destroyed", 3, Regex("""PinLockRuntime\.lockDialogsOpen = """).findAll(activity).count())
        // The proximity read's timeout goes with an early answer (qa-l7-b-code 6).
        assertTrue(code("lock/NoticeProximity.kt").contains("timeout?.let(handler::removeCallbacks)"))
    }
}
