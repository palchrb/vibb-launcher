package com.kidslauncher.mdm.lock

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Design 59b (docs/design/59b-android-lock-face.md §2-§3): the face's loss rule - SystemUI's face over the glance is
 * covered again fast, only for an unexpected loss, at most twice per screen-on; Android open with `USER_PRESENT` is the
 * leave without Home; own mode's rows unchanged. Plus the one stamping point of our own starts and the glance page
 * held under Android's PIN pad.
 */
class AndroidLockFaceTest {
    private fun file(path: String) = listOf("src/$path", "app/src/$path").map(::File).first { it.exists() }
    private fun code(path: String) = file(path).readText()
        .replace(Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL), "")
        .replace(Regex("//[^\\n]*"), "")
    private val lock = "main/java/com/kidslauncher/mdm/lock"

    private val lostFront = RefrontInputs(
        locked = true, lockResumed = false, interactive = true, ourCall = false, telecomInCall = false,
        emergencyFlow = false, alarmRinging = false,
    )
    private val face = lostFront.copy(android = AndroidLoss.LOCKED)

    @Test
    fun `the loss input - locked, or open with USER_PRESENT, else own mode's backstop`() {
        assertEquals(AndroidLoss.LOCKED, androidLoss(face = true, deviceSecure = true, androidLocked = true, presentSinceLocked = false))
        assertEquals(AndroidLoss.LOCKED, androidLoss(true, true, androidLocked = true, presentSinceLocked = true))
        assertEquals(AndroidLoss.OPEN, androidLoss(true, true, androidLocked = false, presentSinceLocked = true))
        assertNull("open, no mark: today's re-front (the RELOCK backstop)", androidLoss(true, true, androidLocked = false, presentSinceLocked = false))
        assertNull("no credential: our keypad's rules", androidLoss(true, deviceSecure = false, androidLocked = true, presentSinceLocked = true))
        assertNull("own mode", androidLoss(face = false, deviceSecure = true, androidLocked = true, presentSinceLocked = true))
    }

    @Test
    fun `an unexpected loss is re-fronted with no user-leave hint - dark, exempt or ours is not`() {
        assertEquals(RefrontAction.Refront(2_000L, noUserAction = true), refrontAction(face, 0))
        assertEquals("dark: the lock-up and the sleep check own it (before the exemptions)", RefrontAction.Stop, refrontAction(face.copy(interactive = false, ourCall = true), 0))
        assertEquals(RefrontAction.Stop, refrontAction(face.copy(lockResumed = true), 0))
        assertEquals(RefrontAction.Stop, refrontAction(face.copy(locked = false), 0))
        // The exemptions in own mode's order - a VoIP call ringing or live yields (Q59b-2: never over a call screen).
        assertEquals(RefrontAction.Yield("call"), refrontAction(face.copy(ourCall = true, voipCall = true), 0))
        assertEquals(RefrontAction.Yield("system_call"), refrontAction(face.copy(telecomInCall = true), 0))
        assertEquals(RefrontAction.Yield("emergency"), refrontAction(face.copy(emergencyFlow = true), 0))
        assertEquals(RefrontAction.Yield("alarm"), refrontAction(face.copy(alarmRinging = true, voipCall = true), 0))
        assertEquals(RefrontAction.Yield("voip"), refrontAction(face.copy(voipCall = true), 0))
        assertEquals(RefrontAction.Yield("sos"), refrontAction(face.copy(sosFlow = true), 0))
        // Ours: the pad's ask, our own start, our own screen.
        assertEquals(RefrontAction.Stop, refrontAction(face.copy(androidOurs = true), 0))
        assertEquals("the exemption is named even when ours", RefrontAction.Yield("voip"), refrontAction(face.copy(androidOurs = true, voipCall = true), 0))
    }

    @Test
    fun `three losses with resumes between - Refront, Refront, Stop - and the count starts again at a screen-off or USER_PRESENT`() {
        var refronts = 0
        val outcomes = (1..3).map {
            // The resume in between zeroes the attempt (refrontAttempt), never the cap's count.
            val action = refrontAction(face.copy(androidRefronts = refronts), attempt = 0)
            if (action is RefrontAction.Refront && action.noUserAction) refronts++
            action
        }
        assertEquals(listOf(RefrontAction.Refront(2_000L, true), RefrontAction.Refront(2_000L, true), RefrontAction.Stop), outcomes)
        assertEquals(ANDROID_REFRONT_CAP, refronts)
        val runtime = code("$lock/PinLockRuntime.kt")
        val screenOff = runtime.substringAfter("if (intent.action == Intent.ACTION_SCREEN_OFF) {").substringBefore("dispatch(app, LockEvent.ScreenOff(")
        assertTrue(screenOff.contains("androidRefronts = 0"))
        val present = runtime.substringAfter("private val userPresentReceiver").substringBefore("\n    }\n")
        assertTrue(present.indexOf("androidRefronts = 0") in present.indexOf("AndroidLock.userPresent()") until present.indexOf("if (mode != LockMode.LOCKED) return"))
        assertEquals("zeroed nowhere else", 2, Regex("(?<!var )androidRefronts = 0").findAll(runtime).count())
        // Counted (and logged) only for the face's re-front; the flag reaches show().
        val check = runtime.substringAfter("private fun runRefrontCheck() {").substringBefore("\n    }\n")
        assertTrue(check.contains("if (action.noUserAction) Log.i(ANDROID_LOCK_TAG, \"re-front #\${++androidRefronts} (\$androidLossWhy)\")"))
        assertTrue(check.contains("show(context, ask = LockAsk.BOOT, noUserAction = action.noUserAction)"))
        assertTrue(check.contains("androidOurs = androidLossOurs(androidAskAt, androidAnsweredAt, ownStartAt, ownOthersResumed > 0, now),"))
        assertTrue(check.contains("androidRefronts = androidRefronts,"))
    }

    @Test
    fun `Android open with USER_PRESENT is the leave without Home - never a re-front, also during a yield (Q59b-6)`() {
        val open = lostFront.copy(android = AndroidLoss.OPEN)
        assertEquals(RefrontAction.AndroidLeave, refrontAction(open, 0))
        assertEquals(RefrontAction.AndroidLeave, refrontAction(open.copy(voipCall = true, androidOurs = true, androidRefronts = 9), 0))
        assertEquals(RefrontAction.Stop, refrontAction(open.copy(interactive = false), 0))
        val check = code("$lock/PinLockRuntime.kt").substringAfter("private fun runRefrontCheck() {").substringBefore("\n    }\n")
        val leave = check.substringAfter("RefrontAction.AndroidLeave -> {").substringBefore("\n            }")
        assertTrue(leave.contains("if (hook != null) hook() else dispatch(context, LockEvent.Unlocked(screenOffs, screenOffs))"))
        // The hook is the glance's USER_PRESENT path: an open ask first, LEAVE only on faceStep's verdict, one re-read.
        val activity = code("$lock/PinLockActivity.kt")
        val hook = activity.substringAfter("private val androidUnlockHook: () -> Unit = {").substringBefore("\n    }\n")
        assertTrue(hook.contains("presentReread = false"))
        val present = activity.substringAfter("private val presentLeave: Runnable = Runnable {").substringBefore("\n    }\n")
        assertTrue(present.contains("} else if (!presentReread) {\n                presentReread = true\n                handler.postDelayed(presentLeave, ANDROID_ANSWER_WAIT_MS)"))
    }

    @Test
    fun `own mode's rows are unchanged - never giving up, no NO_USER_ACTION, the dark cover row`() {
        assertEquals(RefrontAction.Refront(2_000L), refrontAction(lostFront, 0))
        assertEquals(RefrontAction.Refront(5_000L), refrontAction(lostFront.copy(androidRefronts = 99, androidOurs = true), 7))
        assertFalse((refrontAction(lostFront, 3) as RefrontAction.Refront).noUserAction)
        assertEquals(RefrontAction.Stop, refrontAction(lostFront.copy(interactive = false), 0))
        assertEquals(RefrontAction.DarkLockUp, refrontAction(lostFront.copy(interactive = false, coverOn = true), 0))
        assertEquals(RefrontAction.Yield("system_call"), refrontAction(lostFront.copy(interactive = false, coverOn = true, telecomInCall = true), 0))
        // The glance's start adds NO_USER_ACTION only when asked (the face's re-front): own mode's intent is unchanged.
        val show = code("$lock/PinLockRuntime.kt").substringAfter("    fun show(").substringBefore("\n    }\n")
        assertTrue(show.contains(".addFlags(if (noUserAction) Intent.FLAG_ACTIVITY_NO_USER_ACTION else 0)"))
        assertTrue(show.contains("noUserAction: Boolean = false,"))
        assertEquals(1, Regex("noUserAction = ").findAll(code("$lock/PinLockRuntime.kt")).count())
    }

    @Test
    fun `ours - the ask while open (bounded) and a second after its answer, our start for two seconds, our screens`() {
        val now = 1_000_000L
        assertTrue(androidLossOurs(askedAtMs = now - 500, answeredAtMs = null, ownStartAtMs = null, ownOthersResumed = false, nowMs = now))
        assertTrue("the pad's Emergency dialer", androidLossOurs(now - 60_000, null, null, false, now))
        assertFalse("a lost callback expires", androidLossOurs(now - ANDROID_ASK_OPEN_MS, null, null, false, now))
        assertTrue(androidLossOurs(null, now - 999, null, false, now))
        assertFalse(androidLossOurs(null, now - ANDROID_ASK_SETTLE_MS, null, false, now))
        assertTrue(androidLossOurs(null, null, now - (OWN_START_MS - 1), false, now))
        assertFalse(androidLossOurs(null, null, now - OWN_START_MS, false, now))
        assertTrue(androidLossOurs(null, null, null, true, now))
        assertFalse(androidLossOurs(null, null, null, false, now))
        assertFalse("a clock gone backwards counts nothing", androidLossOurs(now + 5, now + 5, now + 5, false, now))
        // The glance publishes its ask; a screen-off ends it.
        val activity = code("$lock/PinLockActivity.kt")
        assertTrue(activity.substringAfter("private fun askAndroid() {").substringBefore("\n    }\n").contains("PinLockRuntime.noteAndroidAsk(open = true)"))
        assertTrue(activity.substringAfter("private fun closeAsk() {").substringBefore("\n    }\n").contains("PinLockRuntime.noteAndroidAsk(open = false)"))
        val screenOff = code("$lock/PinLockRuntime.kt").substringAfter("if (intent.action == Intent.ACTION_SCREEN_OFF) {").substringBefore("dispatch(app, LockEvent.ScreenOff(")
        assertTrue(screenOff.contains("androidAskAt = null"))
    }

    /**
     * Q59b-8 (N2): one stamping point. In lock/ every `startActivity(` goes through [PinLockRuntime.startOwn] - except the
     * glance's own start in `show()` and the files that never start over a LOCKED glance's loss (HomeFront: the leave,
     * the dark lock-up, Home rooting lock task; GlanceOpener: the leave; the boot cover: its own process, before the
     * PIN). A call app's intent is stamped in `VoipCalls.send`; the time-rule screen (ui/) starts through `startOwn`.
     */
    @Test
    fun `no start of ours in lock bypasses noteOwnStart`() {
        val allowed = setOf("HomeFront.kt", "GlanceOpener.kt", "BootCover.kt", "BootCoverActivity.kt")
        val dir = file(lock)
        val starts = dir.listFiles()!!.filter { it.name.endsWith(".kt") && it.name !in allowed }
            .flatMap { f -> Regex("startActivity\\(").findAll(code("$lock/${f.name}")).map { f.name to it.range.first }.toList() }
        val runtime = code("$lock/PinLockRuntime.kt")
        assertEquals("only PinLockRuntime starts activities: $starts", setOf("PinLockRuntime.kt"), starts.map { it.first }.toSet())
        assertEquals(2, starts.size)
        val startOwn = runtime.substringAfter("fun startOwn(context: Context, intent: Intent, why: String) {").substringBefore("\n    }\n")
        assertTrue(startOwn.indexOf("noteOwnStart(why)") in 0 until startOwn.indexOf("context.startActivity(intent)"))
        val show = runtime.substringAfter("    fun show(").substringBefore("\n    }\n")
        val own = show.indexOf("context.startActivity(")
        assertTrue("the glance's own start", own >= 0 && show.indexOf("Intent(context, PinLockActivity::class.java)") in own until show.indexOf("started = true"))
        assertTrue(show.contains("startOwn(context, VoipWakeActivity.intent(context, WakeKind.VOIP), \"the VoIP wake\")"))
        assertTrue(show.contains("startOwn(context, VoipWakeActivity.intent(context, WakeKind.NOTICE), \"the notice wake\")"))
        assertTrue(runtime.contains("startOwn(context, com.kidslauncher.mdm.calls.InCallActivity.intent(context), \"the call screen\")"))
        // PendingIntents: VoipCalls' one send stamps before it sends; the glance's opener and the clock's broadcast don't.
        val send = code("$lock/VoipCalls.kt").substringAfter("private fun send(context: Context, intent: PendingIntent)").substringBefore("\n    }\n")
        assertTrue(send.indexOf("PinLockRuntime.noteOwnStart(") in 0 until send.indexOf("intent.send("))
        assertTrue(code("main/java/com/kidslauncher/mdm/ui/LockActivity.kt").contains("PinLockRuntime.startOwn(context, intent, \"the time-rule screen\")"))
        assertEquals(1, Regex("ownStartAt = ").findAll(runtime).count())
    }

    @Test
    fun `under Android's PIN pad the glance page stays drawn - the pad's success never shows the keypad stage's left-overs`() {
        assertTrue(glancePageShown(LockStage.GLANCE, androidPad = false))
        assertTrue(glancePageShown(LockStage.KEYPAD, androidPad = true))
        assertFalse("own mode's keypad", glancePageShown(LockStage.KEYPAD, androidPad = false))
        val panel = code("$lock/GlancePanel.kt")
        assertTrue(panel.substringAfter("private fun renderNow() {").contains("val shown = glancePageShown(state.stage, androidPad)"))
        assertTrue(panel.contains("val page = glancePageShown(state.stage, androidPad)"))
        val activity = code("$lock/PinLockActivity.kt")
        assertTrue(activity.substringAfter("private fun render() {").substringBefore("\n    }\n").contains("glance?.androidPad(keypadIsAndroids)"))
        // The face leaves as a plain fade; own mode's leave is unchanged.
        assertTrue(activity.substringAfter("private fun go(plan: LockLeave) {").substringBefore("\n    }\n")
            .contains("if (AndroidLock.faceOn) overrideActivityTransition(OVERRIDE_TRANSITION_CLOSE, 0, android.R.anim.fade_out)"))
    }
}
