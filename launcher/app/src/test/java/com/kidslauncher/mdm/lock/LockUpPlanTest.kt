package com.kidslauncher.mdm.lock

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Design 32 step 1 (docs/design/32-lock-flash.md §8, §9): the lock-up at a screen-off - Home under the
 * lock while dark, nothing when the lock is on top already, the lock alone with the screen on or a
 * call live - Home's resume while dark, the black stand-in, and the pins that keep them (QA L6).
 */
class LockUpPlanTest {
    private fun file(path: String) = listOf("src/main/$path", "app/src/main/$path").map(::File).first { it.exists() }
    private fun code(path: String) = file(path).readText()
        .replace(Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL), "")
        .replace(Regex("//[^\\n]*"), "")

    private fun body(code: String, name: String): String {
        val start = code.indexOf("fun $name(")
        assertTrue("fun $name exists", start >= 0)
        val rest = code.substring(start)
        return rest.substring(0, Regex("""\n    }\n""").find(rest)?.range?.first ?: rest.length)
    }

    private val runtime get() = code("java/com/kidslauncher/mdm/lock/PinLockRuntime.kt")

    // ---- lockUpStarts ---------------------------------------------------------------------------

    @Test
    fun `no lock shown - nothing started`() {
        for (onTop in listOf(false, true)) for (call in listOf(false, true)) for (interactive in listOf(false, true)) {
            assertEquals(LockUpStart.NONE, lockUpStarts(showLock = false, lockOnTop = onTop, callLive = call, interactive = interactive))
        }
    }

    @Test
    fun `the screen on again - the lock alone, never a visible Home (QA L1)`() {
        assertEquals(LockUpStart.LOCK, lockUpStarts(showLock = true, lockOnTop = false, callLive = false, interactive = true))
        assertEquals(LockUpStart.LOCK, lockUpStarts(showLock = true, lockOnTop = true, callLive = false, interactive = true))
        assertEquals(LockUpStart.LOCK, lockUpStarts(showLock = true, lockOnTop = false, callLive = true, interactive = true))
    }

    @Test
    fun `a live call - the lock alone, Home never covers a call`() {
        // Ours, Telecom's or a VoIP phase other than NONE - the runtime folds them into callLive.
        assertEquals(LockUpStart.LOCK, lockUpStarts(showLock = true, lockOnTop = false, callLive = true, interactive = false))
        assertEquals(LockUpStart.LOCK, lockUpStarts(showLock = true, lockOnTop = true, callLive = true, interactive = false))
    }

    @Test
    fun `the lock on top already - nothing (no START_DELIVERED_TO_TOP)`() {
        assertEquals(LockUpStart.NONE, lockUpStarts(showLock = true, lockOnTop = true, callLive = false, interactive = false))
    }

    @Test
    fun `dark, no call, the lock not on top - Home then the lock`() {
        assertEquals(LockUpStart.HOME_THEN_LOCK, lockUpStarts(showLock = true, lockOnTop = false, callLive = false, interactive = false))
        // A fresh process knows nothing about the top task: lockOnTop starts false.
        assertTrue(runtime.contains("private var lockOnTop = false"))
    }

    // ---- lockOnTopAfter -------------------------------------------------------------------------

    @Test
    fun `lockOnTop - set by the lock-up, kept by the sleep and by the same lock-up's backstop`() {
        assertTrue(lockOnTopAfter(false, LockTopEvent.LOCK_UP))
        assertTrue(lockOnTopAfter(true, LockTopEvent.LOCK_UP))
        assertTrue("the sleep's pause", lockOnTopAfter(true, LockTopEvent.PAUSE, interactive = false))
        assertTrue("the sleep's stop", lockOnTopAfter(true, LockTopEvent.STOP, interactive = false))
        assertTrue("the ScreenOn backstop of the same lock-up", lockOnTopAfter(true, LockTopEvent.START, sameLockUp = true))
        // Nothing sets it but a lock-up.
        assertFalse(lockOnTopAfter(false, LockTopEvent.START, sameLockUp = true))
        assertFalse(lockOnTopAfter(false, LockTopEvent.PAUSE, interactive = false))
    }

    @Test
    fun `lockOnTop - cleared by an interactive pause or stop, destroy, the unlock and every other start`() {
        assertFalse("Recents, an app, a yield", lockOnTopAfter(true, LockTopEvent.PAUSE, interactive = true))
        assertFalse(lockOnTopAfter(true, LockTopEvent.STOP, interactive = true))
        assertFalse(lockOnTopAfter(true, LockTopEvent.DESTROY))
        assertFalse(lockOnTopAfter(true, LockTopEvent.UNLOCKED))
        assertFalse("a re-front, TimeRuleShown, CallsEnded, VoIP, a process start", lockOnTopAfter(true, LockTopEvent.START, sameLockUp = false))
        assertFalse(lockOnTopAfter(true, LockTopEvent.START, interactive = true))
        assertFalse("a screen-on that finds the lock gone and starts nothing", lockOnTopAfter(true, LockTopEvent.SCREEN_ON_UNSHOWN))
    }

    /**
     * qa-32-s1-code M1: every way another window comes over a lock that is already sleep-stopped (no
     * pause or stop of the lock reports it) ends with the mark cleared, so the next screen-off starts
     * the lock again - never NONE with something else in front. [afterScreenOn] is `dispatch`'s
     * ScreenOn handling (pinned below): a start keeps the mark (the lock is on top again), no start
     * with the lock not resumed clears it.
     */
    @Test
    fun `lockOnTop - every cover over a sleep-stopped lock clears it before the next screen-off`() {
        fun afterScreenOn(current: Boolean, event: LockEvent.ScreenOn): Boolean {
            val showLock = step(LockMode.LOCKED, event).showLock
            return when {
                showLock -> lockOnTopAfter(current, LockTopEvent.START, sameLockUp = true)
                screenOnUnshown(event, showLock) -> lockOnTopAfter(current, LockTopEvent.SCREEN_ON_UNSHOWN)
                else -> current
            }
        }
        fun nextScreenOff(onTop: Boolean) = lockUpStarts(showLock = true, lockOnTop = onTop, callLive = false, interactive = false)

        // The system dialer's call rings from dark over the lock (our dialer role not held), the kid
        // stays in the dialer's UI after it: SCREEN_ON starts nothing - cleared, the next lock-up starts.
        val systemCall = afterScreenOn(true, LockEvent.ScreenOn(lockShowing = false, systemCall = true))
        assertFalse(systemCall)
        assertEquals(LockUpStart.HOME_THEN_LOCK, nextScreenOff(systemCall))
        // A VoIP call (in the call, or ringing - the ring's own start clears it too).
        assertFalse(afterScreenOn(true, LockEvent.ScreenOn(lockShowing = false, voip = VoipPhase.IN_CALL)))
        assertFalse(afterScreenOn(true, LockEvent.ScreenOn(lockShowing = false, voip = VoipPhase.RINGING)))
        assertTrue(step(LockMode.LOCKED, LockEvent.VoipRinging()).showLock)
        assertFalse("VoIP ring: its own start", lockOnTopAfter(true, LockTopEvent.START))
        // Our call, an alarm or another app's turn-screen-on activity: SCREEN_ON starts the lock over
        // it - on top again, kept; the call screen then comes over the resumed lock: an interactive pause.
        assertTrue(afterScreenOn(true, LockEvent.ScreenOn(lockShowing = false, ourCall = true)))
        assertTrue(afterScreenOn(true, LockEvent.ScreenOn(lockShowing = false)))
        assertFalse("our call screen over the resumed lock", lockOnTopAfter(true, LockTopEvent.PAUSE, interactive = true))
        // The time-rule screen started while dark (a rule's boundary alarm): TimeRuleShown starts the lock.
        assertTrue(step(LockMode.LOCKED, LockEvent.TimeRuleShown).showLock)
        assertFalse("TimeRuleShown", lockOnTopAfter(true, LockTopEvent.START))
        // Another Home start while dark (an update, a relock, the lock asking Home to root lock task):
        // Home's usual locked resume shows the lock - a start that isn't the lock-up's.
        assertFalse("Home's locked resume", lockOnTopAfter(true, LockTopEvent.START))
        // The lock resumed and on top at the wake: kept, the next screen-off skips the start.
        assertTrue(afterScreenOn(true, LockEvent.ScreenOn(lockShowing = true)))
        assertEquals(LockUpStart.NONE, nextScreenOff(afterScreenOn(true, LockEvent.ScreenOn(lockShowing = true))))
        // screenOnUnshown is only the ScreenOn event with the lock not resumed and nothing started.
        assertTrue(screenOnUnshown(LockEvent.ScreenOn(lockShowing = false, systemCall = true), showLock = false))
        assertFalse(screenOnUnshown(LockEvent.ScreenOn(lockShowing = true), showLock = false))
        assertFalse(screenOnUnshown(LockEvent.ScreenOn(lockShowing = false), showLock = true))
        assertFalse(screenOnUnshown(LockEvent.ScreenOff(), showLock = false))
        // dispatch applies it when nothing was started.
        val dispatch = body(runtime, "dispatch")
        val unshown = dispatch.indexOf("} else if (screenOnUnshown(event, result.showLock)) {")
        assertTrue(dispatch, unshown > dispatch.indexOf("} else if (result.showLock) {"))
        assertTrue(dispatch.substring(unshown).substringBefore("\n        }").contains("lockOnTop = lockOnTopAfter(lockOnTop, LockTopEvent.SCREEN_ON_UNSHOWN)"))
    }

    // ---- homeDarkResume -------------------------------------------------------------------------

    @Test
    fun `Home's dark resume - only after a dark lock-up, with the screen off, while LOCKED`() {
        assertTrue(homeDarkResume(darkLockUp = true, interactive = false, locked = true))
        assertFalse(homeDarkResume(darkLockUp = false, interactive = false, locked = true))
        assertFalse("the screen is on", homeDarkResume(darkLockUp = true, interactive = true, locked = true))
        assertFalse("not LOCKED", homeDarkResume(darkLockUp = true, interactive = false, locked = false))
    }

    // ---- source pins (QA L6) --------------------------------------------------------------------

    @Test
    fun `a screen-off's starts go through lockUp - Home first, unanimated, then the lock`() {
        val code = runtime
        val dispatch = body(code, "dispatch")
        val routed = dispatch.indexOf("if (event is LockEvent.ScreenOff) {")
        assertTrue(dispatch, routed >= 0)
        assertTrue(dispatch.substring(routed).substringBefore("} else if (result.showLock) {").contains("lockUpAtScreenOff(context, event, result)"))
        assertTrue(dispatch.contains("show(context, wake = result.wake, ask = lockAsk(event), sameLockUp = event is LockEvent.ScreenOn)"))
        val atScreenOff = body(code, "lockUpAtScreenOff")
        assertTrue(atScreenOff.contains("val callLive = event.ourCall || event.systemCall || event.voip != VoipPhase.NONE"))
        assertTrue(atScreenOff.contains("lockUpStarts(result.showLock, lockOnTop, callLive, interactive, cover && kg)"))
        assertTrue(atScreenOff.indexOf("lockUp(context, start, lockAsk(event))") in 0 until atScreenOff.indexOf("Log.i("))
        assertTrue(atScreenOff.contains("\"Lock-up at screen-off: \$start (call=\$callLive, interactive=\$interactive, cover=\$cover, kg=\$kg)\""))
        val lockUp = body(code, "lockUp")
        val home = lockUp.indexOf("HomeFront.bring(context, \"under the lock (a lock-up while dark)\", animate = false)")
        val show = lockUp.indexOf("show(context, ask = ask, fromLockUp = true)")
        assertTrue(lockUp, home >= 0 && show > home)
        assertTrue(lockUp.indexOf("darkLockUp = true") in 0 until home)
        assertTrue(lockUp.contains("if (start == LockUpStart.NONE) return"))
        assertTrue(lockUp.contains("lockOnTop = lockOnTopAfter(lockOnTop, LockTopEvent.LOCK_UP)"))
        // lockUp is the runtime's only Home start, and the receiver itself starts nothing.
        assertEquals(1, Regex("HomeFront\\.bring\\(").findAll(code).count())
        val off = code.substringAfter("if (intent.action == Intent.ACTION_SCREEN_OFF) {").substringBefore("} else {")
        assertFalse(off.contains("HomeFront"))
        assertFalse(off.contains("show("))
        assertEquals("only dispatch calls lockUpAtScreenOff", 2, Regex("lockUpAtScreenOff\\(").findAll(code).count())
        // Design 32 step 2: plus the re-front check's dark lock-up under the keyguard cover (the lock alone).
        assertEquals("lockUp's definition, the screen-off's call and the dark lock-up", 3, Regex("\\blockUp\\(context").findAll(code).count())
        assertTrue(code.contains("lockUp(context, LockUpStart.LOCK, LockAsk.AT_ONCE)"))
    }

    @Test
    fun `show clears lockOnTop itself unless the lock-up calls it`() {
        val show = body(runtime, "show")
        val guard = show.indexOf("if (!fromLockUp) {")
        val clear = show.indexOf("lockOnTop = lockOnTopAfter(lockOnTop, LockTopEvent.START, sameLockUp = sameLockUp)")
        assertTrue(show, guard >= 0 && clear > guard)
        assertEquals(show, -1, show.substring(guard, clear).indexOf("}"))
        assertTrue(clear < show.indexOf("bootMarkHolds("))
        assertEquals(1, Regex("fromLockUp = true").findAll(runtime).count())
        assertEquals(1, Regex("sameLockUp = event is LockEvent.ScreenOn").findAll(runtime).count())
    }

    /** qa-32-s1-code L4: a start that threw is no start - the lock-up marks no lock on top. */
    @Test
    fun `show reports a failed start as not started`() {
        val show = body(runtime, "show")
        val start = show.indexOf("Intent(context, PinLockActivity::class.java)")
        val started = show.indexOf("started = true")
        assertTrue(show, show.indexOf("var started = false") in 0 until start)
        assertTrue(show, started > start && started < show.indexOf("if (wake) {"))
        assertTrue(show.indexOf("catch (e: Exception)") > started)
        assertTrue(show.trimEnd().endsWith("return started"))
        assertFalse(show.contains("return true"))
        assertTrue(body(runtime, "lockUp").contains("if (show(context, ask = ask, fromLockUp = true)) lockOnTop = lockOnTopAfter(lockOnTop, LockTopEvent.LOCK_UP)"))
    }

    /** qa-32-s1-code L3: the leave's call guard, its VoIP term, the glance's Home below and the
     * fail-safe "unknown" answers. */
    @Test
    fun `the unlock never lands on Home over a live call - the wiring`() {
        val activity = code("java/com/kidslauncher/mdm/lock/PinLockActivity.kt")
        assertTrue(body(activity, "leave").contains("go(lockLeave(kioskOn, startedLockTask, lockTaskRunning(), PinLockRuntime.callLiveNow(this)))"))
        assertEquals(1, Regex("lockLeave\\(").findAll(body(activity, "leave")).count())
        val live = body(runtime, "callLiveNow")
        assertTrue(live, live.contains("ourCall() || telecomInCall(context.applicationContext) || VoipCalls.phase != VoipPhase.NONE"))
        // The glance's send check counts Home below exactly when the leave started it.
        assertTrue(body(activity, "go").contains("GlanceOpener.open(this, it, homeBelow = plan.homeFirst)"))
        val opener = code("java/com/kidslauncher/mdm/lock/GlanceOpener.kt")
        assertTrue(opener.contains("fun open(activity: Activity, pending: PendingOpen, homeBelow: Boolean) = guarded { run(activity, pending, GlanceOpenFrom.LOCK, homeBelow) }"))
        assertTrue(body(opener, "run").contains("watchSend(activity, pending, from, homeBelow)"))
        assertTrue(body(opener, "watchSend").contains("val confirmable = from == GlanceOpenFrom.TIME_RULE_SCREEN || homeBelow"))
        assertFalse(body(opener, "watchSend").contains("kioskOn"))
        // Unknown counts as interactive: the runtime clears lockOnTop, Home takes its usual path.
        assertTrue(runtime.contains("private fun interactiveOrUnknown(): Boolean = appContext?.let { interactive(it) } ?: true"))
        val home = code("java/com/kidslauncher/mdm/ui/HomeActivity.kt")
        assertTrue(home.contains("private fun interactive(): Boolean = getSystemService(PowerManager::class.java)?.isInteractive != false"))
    }

    @Test
    fun `lockOnTop follows the lock's pause, stop, destroy and the unlock`() {
        val rt = runtime
        assertTrue(body(rt, "onLockPaused").contains("lockOnTop = lockOnTopAfter(lockOnTop, LockTopEvent.PAUSE, interactive = interactiveOrUnknown())"))
        assertTrue(body(rt, "onLockStopped").contains("lockOnTop = lockOnTopAfter(lockOnTop, LockTopEvent.STOP, interactive = interactiveOrUnknown())"))
        assertTrue(body(rt, "onLockDestroyed").contains("lockOnTop = lockOnTopAfter(lockOnTop, LockTopEvent.DESTROY)"))
        assertTrue(body(rt, "dispatch").contains("if (result.mode != LockMode.LOCKED) lockOnTop = lockOnTopAfter(lockOnTop, LockTopEvent.UNLOCKED)"))
        assertTrue(body(code("java/com/kidslauncher/mdm/lock/PinLockActivity.kt"), "onDestroy").contains("PinLockRuntime.onLockDestroyed()"))
        // The dark mark ends at an interactive resume of the lock or Home.
        assertTrue(body(rt, "onLockResumed").contains("val interactiveNow = interactive(context)"))
        assertTrue(body(rt, "onLockResumed").contains("if (interactiveNow) darkLockUp = false"))
        assertTrue(body(rt, "onHomeResumed").contains("if (interactive) darkLockUp = false"))
    }

    @Test
    fun `Home's resume while dark - the night ground, nothing else, first`() {
        val home = code("java/com/kidslauncher/mdm/ui/HomeActivity.kt")
        assertTrue(body(home, "darkUnderLock").contains("homeDarkResume(PinLockRuntime.darkLockUp, interactive(), PinLockRuntime.mode == LockMode.LOCKED)"))
        val resume = body(home, "onResume")
        val branch = resume.indexOf("if (darkUnderLock()) {")
        assertTrue(resume, branch >= 0)
        val before = resume.substring(0, branch)
        assertEquals("super.onResume(), resumedNow only", listOf("super.onResume()", "resumedNow = true"),
            before.substringAfter("{").lines().map { it.trim() }.filter { it.isNotEmpty() })
        val dark = resume.substring(branch).substringAfter("{").substringBefore("}")
        // qa-32-s1-code L1: no lock task here - the lock's task is the focused one by then.
        assertEquals(listOf("showNight()", "return"), dark.lines().map { it.trim() }.filter { it.isNotEmpty() })
        // Everything else comes after it: the boot mark, the update fence, the time-rule screen, the lock.
        for (after in listOf("PinLockRuntime.onHomeResumed(interactive())", "UpdateFence.onFront", "redirectToLockScreenIfLocked()",
            "PinLockRuntime.show(", "PinLockRuntime.onHomeMarkUp()", "TsnetClient.connectFromPreferences")) {
            assertTrue(after, resume.indexOf(after) > branch)
        }
        // onStart skips its renders and listeners on the same condition; they come at the next
        // interactive start or resume.
        val start = body(home, "onStart")
        assertTrue(start, start.indexOf("if (darkUnderLock()) return") in 0 until start.indexOf("startWork()"))
        assertTrue(resume.indexOf("startWork()") > branch)
        val work = body(home, "startWork")
        assertTrue(work.contains("if (started) return"))
        for (part in listOf("BadgeStore.addListener(badgeListener)", "renderCallCard()", "renderMusicCard()", "registerContentObserver(")) {
            assertTrue(part, work.contains(part))
        }
    }

    @Test
    fun `the stand-in is black - theme, task descriptions, no screenshot of Home`() {
        val styles = file("res/values/styles.xml").readText()
        val theme = styles.substringAfter("<style name=\"PinLockTheme\"").substringBefore("</style>")
        for (item in listOf("android:windowBackground", "android:windowSplashScreenBackground", "android:statusBarColor", "android:navigationBarColor")) {
            assertTrue(item, theme.contains("<item name=\"$item\">@android:color/black</item>"))
        }
        val black = listOf(
            "ActivityManager.TaskDescription.Builder()",
            ".setBackgroundColor(Color.BLACK)",
            ".setStatusBarColor(Color.BLACK)",
            ".setNavigationBarColor(Color.BLACK)",
        )
        val lock = body(code("java/com/kidslauncher/mdm/lock/PinLockActivity.kt"), "onCreate")
        val description = lock.indexOf("setTaskDescription(")
        assertTrue(lock, description >= 0 && description < lock.indexOf("setContentView("))
        assertTrue(lock.indexOf("window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)") in 0 until lock.indexOf("setContentView("))
        for (part in black) assertTrue(part, lock.substring(description).contains(part))
        val home = body(code("java/com/kidslauncher/mdm/ui/HomeActivity.kt"), "onCreate")
        assertTrue(home.contains("setRecentsScreenshotEnabled(false)"))
        val homeDescription = home.indexOf("setTaskDescription(")
        assertTrue(home, homeDescription >= 0)
        for (part in black) assertTrue(part, home.substring(homeDescription).contains(part))
    }

    @Test
    fun `no cover window, no overlay permission, no accessibility service`() {
        // Design 32 §7.3: rejected - neither can be obtained by a device owner, and an overlay weakens
        // the lock's own overlay block.
        val sources = file("java/com/kidslauncher/mdm").walk().filter { it.isFile && it.extension == "kt" }.map { it.readText() }.toList()
        assertTrue(sources.size > 100)
        for (text in sources) {
            assertFalse(text.contains("TYPE_APPLICATION_OVERLAY"))
            // An own service (the DPM's permitted-services list names others' and stays).
            assertFalse(text.contains("android.accessibilityservice.AccessibilityService"))
            assertFalse(Regex(":\\s*AccessibilityService\\(").containsMatchIn(text))
        }
        val manifest = file("AndroidManifest.xml").readText()
        assertFalse(manifest.contains("SYSTEM_ALERT_WINDOW"))
        assertFalse(manifest.contains("BIND_ACCESSIBILITY_SERVICE"))
    }

    @Test
    fun `no comment in lock claims the lock is drawn before the screen-on`() {
        val dir = file("java/com/kidslauncher/mdm/lock")
        for (f in dir.walk().filter { it.isFile && it.extension == "kt" }) {
            val text = f.readText().replace(Regex("\\s+"), " ")
            assertFalse(f.name, text.contains("drawn before the next screen-on"))
            assertFalse(f.name, text.contains("drawn before a quick screen-on"))
        }
    }

    // ---- the lock started with the screen on: the double press (the user's mash log) ----------------

    @Test
    fun `the backstop's new-intent pause keeps lockOnTop, nothing else does`() {
        assertTrue(newIntentPauseKept(wasOnTop = true, backstopAtMs = 1_000L, nowMs = 1_040L))
        assertTrue(newIntentPauseKept(true, 1_000L, 1_000L + BACKSTOP_NEW_INTENT_MS))
        assertFalse("too late: something else paused it", newIntentPauseKept(true, 1_000L, 2_001L))
        assertFalse("no backstop start onto it", newIntentPauseKept(true, null, 1_040L))
        assertFalse("not on top before", newIntentPauseKept(false, 1_000L, 1_040L))
        // The new intent puts a held mark back, and never sets one by itself.
        assertTrue(lockOnTopAfter(false, LockTopEvent.NEW_INTENT, sameLockUp = true))
        assertFalse(lockOnTopAfter(false, LockTopEvent.NEW_INTENT, sameLockUp = false))
        assertTrue(lockOnTopAfter(true, LockTopEvent.NEW_INTENT, sameLockUp = false))
        // The sequence: backstop start (kept), interactive pause (cleared, held), new intent (back).
        val afterStart = lockOnTopAfter(true, LockTopEvent.START, sameLockUp = true)
        val afterPause = lockOnTopAfter(afterStart, LockTopEvent.PAUSE, interactive = true)
        assertFalse(afterPause)
        assertTrue(lockOnTopAfter(afterPause, LockTopEvent.NEW_INTENT, sameLockUp = newIntentPauseKept(afterStart, 0L, 30L)))
    }

    @Test
    fun `the new-intent wiring - set by the backstop's start, held by the pause, dropped by a stop or resume`() {
        val rt = runtime
        val show = body(rt, "show")
        assertTrue(show.contains("backstopOntoTopAt = if (sameLockUp && lockOnTop) SystemClock.elapsedRealtime() else null"))
        assertTrue(show.indexOf("backstopOntoTopAt =") < show.indexOf("lockOnTop = lockOnTopAfter(lockOnTop, LockTopEvent.START, sameLockUp = sameLockUp)"))
        assertTrue(body(rt, "onLockPaused").contains("newIntentPauseHeld = !lockOnTop && newIntentPauseKept(wasOnTop, backstopOntoTopAt, SystemClock.elapsedRealtime())"))
        assertTrue(body(rt, "onLockStopped").contains("newIntentPauseHeld = false"))
        assertTrue(body(rt, "onLockResumed").contains("newIntentPauseHeld = false"))
        assertTrue(body(rt, "onLockDestroyed").contains("newIntentPauseHeld = false"))
        assertTrue(body(rt, "onLockNewIntent").contains("lockOnTop = lockOnTopAfter(lockOnTop, LockTopEvent.NEW_INTENT, sameLockUp = newIntentPauseHeld)"))
        val activity = code("java/com/kidslauncher/mdm/lock/PinLockActivity.kt")
        assertTrue(body(activity, "onNewIntent").contains("PinLockRuntime.onLockNewIntent()"))
    }

    /**
     * qa-32-s2-code M1: an awake lock start (the double press) keeps its black splash and Shell's default
     * close of the app the kid was in (§12) - handy is the home process, so `windowDisablePreview` is
     * skipped, and no public option overrides a task change. So: the preview on (step 1's black splash
     * covers the old frame at once), no inert options.
     */
    @Test
    fun `the lock start keeps its black splash and passes no options`() {
        val show = body(runtime, "show")
        assertTrue(show.contains("Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_NO_ANIMATION),\n            )"))
        assertEquals(1, Regex("Intent\\(context, PinLockActivity::class.java\\)").findAll(runtime).count())
        assertFalse(runtime.contains("makeCustomAnimation"))
        assertFalse(runtime.contains("lockStartOptions"))
        val theme = file("res/values/styles.xml").readText().substringAfter("<style name=\"PinLockTheme\"").substringBefore("</style>")
        assertTrue(theme.contains("<item name=\"android:windowDisablePreview\">false</item>"))
        // The one place in the app that starts the lock.
        val starts = file("java").walk().filter { it.isFile && it.extension == "kt" }
            .filter { it.readText().contains("Intent(context, PinLockActivity::class.java)") }.map { it.name }.toList()
        assertEquals(listOf("PinLockRuntime.kt"), starts)
    }
}
