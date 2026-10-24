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
    /** The queue's immediate branch (comments stripped): the rc.2 run's R4 drops a queued copy before the send. */
    private val sentAtOnce = Regex("if \\(!AndroidLock\\.refrontHeld\\(context\\)\\) \\{\\s*dropWaiting\\(context, key\\)\\s*send\\(context\\)\\s*return")

    private val lostFront = RefrontInputs(
        locked = true, lockResumed = false, interactive = true, ourCall = false, telecomInCall = false,
        emergencyFlow = false, alarmRinging = false,
    )
    /** A fresh loss (the glance's pause or stop scheduled the check) with Android locked under the face. */
    private val face = lostFront.copy(android = AndroidLoss.LOCKED, androidFreshLoss = true)

    @Test
    fun `the loss input - locked, open with USER_PRESENT, or open waiting for it`() {
        assertEquals(AndroidLoss.LOCKED, androidLoss(face = true, deviceSecure = true, androidLocked = true, presentSinceLocked = false))
        assertEquals(AndroidLoss.LOCKED, androidLoss(true, true, androidLocked = true, presentSinceLocked = true))
        assertEquals(AndroidLoss.OPEN, androidLoss(true, true, androidLocked = false, presentSinceLocked = true))
        assertEquals("open, no mark yet: wait for it (B-5)", AndroidLoss.WAIT, androidLoss(true, true, androidLocked = false, presentSinceLocked = false))
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
        // B-3: only a fresh pause or stop re-fronts - a yield's recheck or a re-front's follow-up never covers what the
        // platform has in front after it (Android's own Emergency dialer from the pad).
        assertEquals(RefrontAction.Stop, refrontAction(face.copy(androidFreshLoss = false), 0))
        assertEquals("the exemptions still yield on a recheck", RefrontAction.Yield("system_call"), refrontAction(face.copy(androidFreshLoss = false, telecomInCall = true), 0))
    }

    @Test
    fun `B-5 - Android open with no USER_PRESENT waits once for it, then own mode's backstop - never a re-front at once`() {
        val open = lostFront.copy(android = AndroidLoss.WAIT, androidFreshLoss = true)
        assertEquals(RefrontAction.AndroidWait, refrontAction(open, 0))
        assertEquals("before the exemptions too", RefrontAction.AndroidWait, refrontAction(open.copy(voipCall = true), 0))
        assertEquals(RefrontAction.Stop, refrontAction(open.copy(interactive = false), 0))
        // The wait is over: own mode's rows (the re-front whose resume WAITs and RELOCKs an open Android).
        assertEquals(RefrontAction.Refront(2_000L), refrontAction(open.copy(androidWaited = true), 0))
        assertEquals(RefrontAction.Yield("voip"), refrontAction(open.copy(androidWaited = true, voipCall = true), 0))
        val runtime = code("$lock/PinLockRuntime.kt")
        val check = runtime.substringAfter("private fun runRefrontCheck() {").substringBefore("\n    }\n")
        val wait = check.substringAfter("RefrontAction.AndroidWait -> {").substringBefore("\n            }")
        assertTrue(wait.contains("androidWaited = true") && wait.contains("handler.postDelayed(refrontCheck, ANDROID_ANSWER_WAIT_MS)"))
        assertTrue(check.contains("androidWaited = androidWaited,"))
        // A new loss, a resume, a screen-off and USER_PRESENT wait anew.
        assertTrue(runtime.substringAfter("private fun markAndroidLoss() {").substringBefore("\n    }\n").contains("androidWaited = false"))
        assertTrue(runtime.substringAfter("fun onLockResumed(").substringBefore("\n    }\n").contains("androidWaited = false"))
        assertTrue(runtime.substringAfter("if (intent.action == Intent.ACTION_SCREEN_OFF) {").substringBefore("dispatch(app, LockEvent.ScreenOff(").contains("androidWaited = false"))
        assertTrue(runtime.substringAfter("private val userPresentReceiver").substringBefore("\n    }\n").contains("androidWaited = false"))
    }

    @Test
    fun `B-3 - a fresh loss is marked only by the glance's pause and stop, and consumed by the check`() {
        val runtime = code("$lock/PinLockRuntime.kt")
        assertEquals("the pause and the stop", 2, Regex("markAndroidLoss\\(\\)\\n        handler.postDelayed\\(refrontCheck").findAll(runtime).count())
        assertEquals(1, Regex("androidLossPending = true").findAll(runtime).count())
        val check = runtime.substringAfter("private fun runRefrontCheck() {").substringBefore("\n    }\n")
        assertTrue(check.contains("androidFreshLoss = androidLossPending,\n            androidWaited = androidWaited,\n        )\n        androidLossPending = false"))
        assertTrue(runtime.substringAfter("fun onLockResumed(").substringBefore("\n    }\n").contains("androidLossPending = false"))
        // A cancelled ask (the pad's Emergency button) stays ours for the ask's window, from the ask.
        assertTrue(check.contains("androidLossOurs(androidAskAt ?: androidCancelledAskAt, androidAnsweredAt, ownStartAt, ownOthersResumed > 0, now)"))
        val note = runtime.substringAfter("fun noteAndroidAsk(").substringBefore("\n    }\n")
        assertTrue(note.contains("if (cancelled) androidCancelledAskAt = androidAskAt"))
        assertTrue("a new ask clears it", note.contains("androidAskAt = now\n            androidCancelledAskAt = null"))
        assertTrue(runtime.substringAfter("if (intent.action == Intent.ACTION_SCREEN_OFF) {").substringBefore("dispatch(app, LockEvent.ScreenOff(").contains("androidCancelledAskAt = null"))
        val activity = code("$lock/PinLockActivity.kt")
        assertTrue(activity.substringAfter("private fun androidAnswered(").substringBefore("\n    }\n").contains("if (current) closeAsk(cancelled = result == \"cancelled\")"))
        val now = 10_000_000L
        assertTrue("the dialer a minute after the cancel", androidLossOurs(now - 60_000, null, null, false, now))
        assertFalse(androidLossOurs(now - ANDROID_ASK_OPEN_MS, null, null, false, now))
    }

    @Test
    fun `B-2 - as the face a probable SOS is an exemption only - lock task isn't narrowed for it`() {
        assertEquals(RefrontAction.Yield("sos"), refrontAction(face.copy(sosFlow = true), 0))
        val check = code("$lock/PinLockRuntime.kt").substringAfter("private fun runRefrontCheck() {").substringBefore("\n    }\n")
        assertTrue(check.contains("narrowForSos(context, sos && inputs.android == null)"))
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
        assertTrue(activity.substringAfter("private fun closeAsk(").substringBefore("\n    }\n").contains("PinLockRuntime.noteAndroidAsk(open = false, cancelled = cancelled)"))
        val screenOff = code("$lock/PinLockRuntime.kt").substringAfter("if (intent.action == Intent.ACTION_SCREEN_OFF) {").substringBefore("dispatch(app, LockEvent.ScreenOff(")
        assertTrue(screenOff.contains("androidAskAt = null"))
    }

    /**
     * Q59b-8 (N2): one stamping point. In lock/ every `startActivity(` goes through [PinLockRuntime.startOwn] - except the
     * glance's own start in `show()` and the files that never start over a LOCKED glance's loss (HomeFront: the leave,
     * the dark lock-up, Home rooting lock task; GlanceOpener: the leave; the boot cover: its own process, before the
     * PIN). HomeFront's starts are the leave's, the dark lock-up's and the boot's; its starts after a policy edge go
     * through `bringAfterUnlock` (they wait for Android's unlock as the face - qa-59b-code B-1) and Home never roots lock
     * task over Android's locked keyguard (`lockTaskEntry(.., androidLocked)` -> START_SELF). A call app's intent is
     * stamped in `VoipCalls.send`; the time-rule screen (ui/) starts through `startOwn`, after Android's unlock.
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
        // B-6 (M10b): the one stamping point writes its stamp.
        assertTrue(runtime.substringAfter("fun noteOwnStart(why: String) {").substringBefore("\n    }\n").contains("ownStartAt = SystemClock.elapsedRealtime()"))
    }

    @Test
    fun `B-1 - as the face with Android locked no Home roots lock task, and Home and the time-rule screen wait for the unlock`() {
        assertEquals(LockTaskEntry.START_SELF, lockTaskEntry(false, true, kioskOn = true, sinceHomeAskedMs = null, fallbackDue = false, androidLocked = true))
        assertEquals("own mode's rows", LockTaskEntry.START_HOME, lockTaskEntry(false, true, kioskOn = true, sinceHomeAskedMs = null, fallbackDue = false))
        assertEquals(LockTaskEntry.NONE, lockTaskEntry(true, true, kioskOn = true, sinceHomeAskedMs = null, fallbackDue = false, androidLocked = true))
        val activity = code("$lock/PinLockActivity.kt")
        assertTrue(activity.contains("voipCall = voipHeldLockTask, androidLocked = androidLocked))"))
        assertTrue(activity.contains("val androidLocked = AndroidLock.refrontHeld(this)"))
        // The policy edges' Home starts go through the face's queue; the relock's time-rule screen too (LockActivity.start).
        val main = "main/java/com/kidslauncher/mdm"
        for ((path, call) in listOf(
            "$main/play/PlayRuntime.kt" to "HomeFront.bringAfterUnlock(app, \"install mode ended\")",
            "$main/server/SelfUpdate.kt" to "HomeFront.bringAfterUnlock(app, \"after \$why\")",
            "$main/server/WindowRelock.kt" to "HomeFront.bringAfterUnlock(app, \"the window locked again\")",
            "$main/server/AppEnforcer.kt" to "HomeFront.bringAfterUnlock(context, \"a role change\")",
        )) {
            val text = code(path)
            assertTrue(path, text.contains(call))
            assertFalse(path, text.contains("HomeFront.bring("))
        }
        assertTrue(code("$lock/HomeFront.kt").contains("KeyguardCover.afterAndroidUnlock(context, \"Home (\$why)\") { bring(it, why, noAnimation = AndroidLock.faceOn) }"))
        val start = code("$main/ui/LockActivity.kt").substringAfter("fun start(context: Context) {").substringBefore("\n        }\n")
        assertTrue(start.contains("KeyguardCover.afterAndroidUnlock(context, \"the time-rule screen\") { startNow(it) }"))
        // The queue waits only while the face's Android is locked, and runs when our lock leaves LOCKED.
        val cover = code("$lock/KeyguardCover.kt")
        assertTrue(cover.substringAfter("fun afterAndroidUnlock(").substringBefore("\n    }\n").contains(sentAtOnce))
        assertTrue(cover.substringAfter("fun runAfterUnlock(").substringBefore("\n    }\n").contains("if (waiting.isEmpty() || mustWait(context) || AndroidLock.refrontHeld(context)) return"))
        val dispatch = code("$lock/PinLockRuntime.kt").substringAfter("private fun dispatch(").substringBefore("\n    }\n")
        assertTrue(dispatch.contains("if (result.mode != LockMode.LOCKED) {\n                    val app = context.applicationContext\n                    handler.post { KeyguardCover.runAfterUnlock(app, \"our lock left LOCKED\") }"))
    }

    @Test
    fun `B-6 (M7b), 59d B - in the ASK stage our keys are hidden and take no digit`() {
        val activity = code("$lock/PinLockActivity.kt")
        assertTrue(activity.contains("private fun asking(): Boolean = glance?.state?.stage == LockStage.ASK"))
        val render = activity.substringAfter("private fun render() {").substringBefore("\n    }\n")
        assertTrue(render.contains("val ours = if (asking) View.INVISIBLE else View.VISIBLE"))
        assertTrue(render.contains("binding.pinKeypad.visibility = ours"))
        assertTrue(render.contains("binding.pinDots.visibility = ours"))
        assertTrue(render.contains("binding.pinParentCode.visibility = if (asking) View.GONE else View.VISIBLE"))
        assertTrue(activity.contains("private fun keypadEnabled() = !asking() && !checking && waitMs <= 0L && PinLockRuntime.pinUsable"))
        for (name in listOf("onDigit", "onDelete")) {
            assertTrue(name, activity.substringAfter("private fun $name(").substringBefore("\n    }\n").contains("if (!keypadEnabled()) return"))
        }
        assertFalse("keypadIsAndroids went", activity.contains("keypadIsAndroids"))
    }

    /** Design 59d step B (Q59d-6): the ASK stage asks from the glance page; only ASK asks, every other stage closes it. */
    @Test
    fun `59d B - the ASK stage asks for Android's pad, OWN_KEYPAD gives our keys, every return to GLANCE closes the ask`() {
        val activity = code("$lock/PinLockActivity.kt")
        val onStage = activity.substringAfter("private fun onStage() {").substringBefore("\n    }\n")
        assertTrue(onStage, onStage.contains("!asking() -> closeAsk()"))
        assertTrue(onStage, onStage.contains("!AndroidLock.faceOn -> glance?.ownKeypad()"))
        assertTrue(onStage, onStage.contains("else -> tapAsFace()"))
        val tap = activity.substringAfter("private fun tapAsFace() {").substringBefore("\n    }\n")
        assertTrue(tap, tap.substringAfter("if (step == FaceStep.OWN_KEYPAD) {").substringBefore("return").contains("glance?.ownKeypad()"))
        assertTrue(activity.contains("FaceStep.STAY -> if (askedAt == null && asking()) glance?.back()"))
        assertTrue(activity.contains("GlancePanel(this, frame, binding.root, ::onStage, face = { AndroidLock.faceOn })"))
        val panel = code("$lock/GlancePanel.kt")
        assertTrue(panel.contains("state = glanceStageStep(state, event, face())"))
        assertFalse("GlancePanel.androidPad went", panel.contains("androidPad"))
    }

    @Test
    fun `59d B - the page shows under the pad, only its ground at the leave`() {
        assertTrue(glancePageShown(LockStage.GLANCE))
        assertTrue("the page under Android's pad", glancePageShown(LockStage.ASK))
        assertFalse("our keypad", glancePageShown(LockStage.KEYPAD))
        assertTrue(glanceContentShown(leaving = false))
        assertFalse("the leave hides it", glanceContentShown(leaving = true))
        assertTrue(glanceLeaveHides(androidFace = true, inFront = true))
        assertFalse("stopped under an app", glanceLeaveHides(androidFace = true, inFront = false))
        assertFalse("own mode's leave unchanged", glanceLeaveHides(androidFace = false, inFront = true))
        val panel = code("$lock/GlancePanel.kt")
        // The root's children only - never the root (its ground), the decor view or the window (a row's PendingIntent).
        val show = panel.substringAfter("private fun showContent() {").substringBefore("\n    }\n")
        assertTrue(show, show.contains("val content = if (glanceContentShown(leaving)) View.VISIBLE else View.INVISIBLE"))
        assertTrue(show, show.contains("for (i in 0 until root.childCount) root.getChildAt(i).visibility = content"))
        assertFalse(panel.contains("decorView"))
        val render = panel.substringAfter("private fun renderNow() {").substringBefore("\n    }\n")
        assertTrue(render.contains("val shown = glancePageShown(state.stage)"))
        assertTrue(render.indexOf("showContent()") > render.indexOf("glance.root.visibility = if (shown) View.VISIBLE else View.GONE"))
        assertTrue(panel.contains("val page = glancePageShown(state.stage)"))
        // The next resume shows a content still hidden by a leave that didn't finish.
        assertTrue(panel.substringAfter("fun resume() = guarded(\"resume\") {").substringBefore("\n    }\n").contains("leaving = false"))
    }

    @Test
    fun `59d B - the face's leave hides the content first, then Home and the finish with no animation`() {
        val activity = code("$lock/PinLockActivity.kt")
        val leave = activity.substringAfter("private fun leave() {").substringBefore("\n    }\n")
        val hide = leave.indexOf("if (hides) glance?.leaving()")
        assertTrue(leave, leave.contains("val hides = glanceLeaveHides(AndroidLock.faceOn, inFront)"))
        assertTrue(leave, hide in 0 until leave.indexOf("go(lockLeave("))
        assertTrue(leave, leave.indexOf("if (hides && !isFinishing) glance?.leaveRefused()") > leave.indexOf("go(rootLeave)"))
        val go = activity.substringAfter("private fun go(plan: LockLeave) {").substringBefore("\n    }\n")
        assertTrue(go, go.contains("if (plan.homeFirst) HomeFront.bring(this, \"the lock left\", noAnimation = AndroidLock.faceOn, arrival = arrival)"))
        assertTrue(go, go.contains("if (AndroidLock.faceOn) overrideActivityTransition(OVERRIDE_TRANSITION_CLOSE, 0, 0)"))
        assertFalse("59b's close fade went", go.contains("fade_out"))
        // RC3-2: the glance shows the wallpaper exactly while it asks as the face - set before the leave's transition
        // collects it (the wallpaper-intra transition, not wallpaper_open_exit's slide), never cleared by the leave.
        val stage = activity.substringAfter("private fun onStage() {").substringBefore("\n    }\n")
        assertTrue(stage, stage.indexOf("if (!finishingAfterUnlock) showWallpaperBelow(asking() && AndroidLock.faceOn)") in 0 until stage.indexOf("when {"))
        assertTrue(leave.indexOf("finishingAfterUnlock = true") in 0 until hide)
        assertEquals(2, Regex("FLAG_SHOW_WALLPAPER\\)").findAll(activity).count())
    }

    @Test
    fun `R1 - as the face a pause in the SOS window is the mark, so a cold SOS screen is never re-fronted over`() {
        // The rc.2 run: the SOS screen drew 0.9-1.2 s after the glance's pause, past the pause's 700 ms check.
        assertTrue(sosMarkAtPause(androidFace = true, ours = false, lastScreenOnAtMs = 10_000L, pausedAtMs = 10_900L, sosAppPresent = true))
        assertFalse("own mode keeps its stop-only mark (Recents only pauses it)", sosMarkAtPause(false, false, 10_000L, 10_900L, true))
        assertFalse("the pad's ask or our own start", sosMarkAtPause(true, ours = true, lastScreenOnAtMs = 10_000L, pausedAtMs = 10_900L, sosAppPresent = true))
        assertFalse("outside the window", sosMarkAtPause(true, false, 10_000L, 10_001L + SOS_GESTURE_WINDOW_MS, true))
        assertFalse("no SOS app", sosMarkAtPause(true, false, 10_000L, 10_900L, false))
        assertFalse("no screen-on yet", sosMarkAtPause(true, false, null, 10_900L, true))
        // The marked loss yields "sos" at the pause's check, before the face's re-front.
        assertEquals(RefrontAction.Yield("sos"), refrontAction(face.copy(sosFlow = true), 0))
        // The glue: the pause marks before its check is scheduled; the stop's mark is own mode's, unchanged.
        val runtime = code("$lock/PinLockRuntime.kt")
        val paused = runtime.substringAfter("fun onLockPaused(").substringBefore("\n    }\n")
        val mark = paused.indexOf("if (sosMarkAtPause(AndroidLock.faceOn, ours, lastScreenOnElapsed, pausedNow, sosPackage != null)) noteProbableSos(pausedNow, logNow = false)")
        assertTrue(mark > paused.indexOf("if (!pauseStartsRefront(chromeLocked, finishing)) return"))
        assertTrue(mark < paused.indexOf("handler.postDelayed(refrontCheck"))
        assertTrue(paused.contains("val ours = androidLossOurs(androidAskAt ?: androidCancelledAskAt, androidAnsweredAt, ownStartAt, ownOthersResumed > 0, pausedNow)"))
        assertTrue(runtime.substringAfter("fun onLockStopped(").substringBefore("\n    }\n").contains("noteProbableSos(SystemClock.elapsedRealtime())"))
        assertTrue(runtime.substringAfter("private fun noteProbableSos(leftFrontAt: Long, logNow: Boolean = true) {").substringBefore("\n    }\n")
            .contains("if (!sosGestureLikely(lastScreenOnElapsed, leftFrontAt, sosAppPresent = sosPackage != null)) return"))
    }

    @Test
    fun `N1 - a pause's SOS mark is logged only when the pause's check still finds it`() {
        assertTrue("the check finds the mark", sosPauseMarkLogDue(unlogged = true, markHeld = true))
        assertFalse("the wake's pause/resume pair: the resume cleared it", sosPauseMarkLogDue(unlogged = true, markHeld = false))
        assertFalse("a stop's mark logged at once already", sosPauseMarkLogDue(unlogged = false, markHeld = true))
        val runtime = code("$lock/PinLockRuntime.kt")
        val note = runtime.substringAfter("private fun noteProbableSos(leftFrontAt: Long, logNow: Boolean = true) {").substringBefore("\n    }\n")
        assertTrue(note, note.contains("sosMarkUnlogged = !logNow"))
        assertTrue(note, note.contains("if (logNow) Log.i(LOG_TAG, SOS_MARK_LINE)"))
        assertEquals("one other log of the line", 2, Regex("Log\\.i\\(LOG_TAG, SOS_MARK_LINE\\)").findAll(runtime).count())
        val check = runtime.substringAfter("private fun runRefrontCheck() {").substringBefore("\n    }\n")
        val log = check.indexOf("if (sosPauseMarkLogDue(sosMarkUnlogged, sosFlowUntilElapsed > 0L)) Log.i(LOG_TAG, SOS_MARK_LINE)")
        assertTrue(check, log in 0 until check.indexOf("if (!sos) sosFlowUntilElapsed = 0L"))
        // The resume clears the mark and the unlogged flag together; the stop still logs at once (own mode's mark).
        val resumed = runtime.substringAfter("fun onLockResumed(").substringBefore("\n    }\n")
        assertTrue(resumed.indexOf("sosMarkUnlogged = false") > resumed.indexOf("sosFlowUntilElapsed = 0L"))
        assertTrue(runtime.substringAfter("fun onLockStopped(").substringBefore("\n    }\n").contains("noteProbableSos(SystemClock.elapsedRealtime())\n"))
    }

    @Test
    fun `R4 - a start sent at once drops its queued copy - one time-rule screen after the unlock`() {
        val cover = code("$lock/KeyguardCover.kt")
        val after = cover.substringAfter("fun afterAndroidUnlock(").substringBefore("\n    }\n")
        assertTrue(after.contains(sentAtOnce))
        val drop = cover.substringAfter("private fun dropWaiting(context: Context, key: String) {").substringBefore("\n    }\n")
        assertTrue(drop.contains("if (waiting.remove(key) != null)"))
        assertTrue("the queue is the main thread's", drop.contains("handler.post { dropWaiting(app, key) }"))
    }
}
