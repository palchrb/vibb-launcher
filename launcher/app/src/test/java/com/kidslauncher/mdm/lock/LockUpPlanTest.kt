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
        assertTrue(atScreenOff.contains("lockUpStarts(result.showLock, lockOnTop, callLive, interactive)"))
        assertTrue(atScreenOff.indexOf("lockUp(context, start, lockAsk(event))") in 0 until atScreenOff.indexOf("Log.i("))
        assertTrue(atScreenOff.contains("\"Lock-up at screen-off: \$start (call=\$callLive, interactive=\$interactive)\""))
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
        assertEquals("lockUp's definition and its one call", 2, Regex("\\blockUp\\(context").findAll(code).count())
    }

    @Test
    fun `show clears lockOnTop itself unless the lock-up calls it`() {
        val show = body(runtime, "show")
        val clear = show.indexOf("if (!fromLockUp) lockOnTop = lockOnTopAfter(lockOnTop, LockTopEvent.START, sameLockUp = sameLockUp)")
        assertTrue(show, clear >= 0)
        assertTrue(clear < show.indexOf("bootMarkHolds("))
        assertEquals(1, Regex("fromLockUp = true").findAll(runtime).count())
        assertEquals(1, Regex("sameLockUp = event is LockEvent.ScreenOn").findAll(runtime).count())
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
        assertTrue(body(rt, "onLockResumed").contains("if (interactive(context)) darkLockUp = false"))
        assertTrue(body(rt, "onHomeResumed").contains("if (interactive) darkLockUp = false"))
    }

    @Test
    fun `Home's resume while dark - the night ground and lock task, nothing else, first`() {
        val home = code("java/com/kidslauncher/mdm/ui/HomeActivity.kt")
        assertTrue(body(home, "darkUnderLock").contains("homeDarkResume(PinLockRuntime.darkLockUp, interactive(), PinLockRuntime.mode == LockMode.LOCKED)"))
        val resume = body(home, "onResume")
        val branch = resume.indexOf("if (darkUnderLock()) {")
        assertTrue(resume, branch >= 0)
        val before = resume.substring(0, branch)
        assertEquals("super.onResume(), resumedNow only", listOf("super.onResume()", "resumedNow = true"),
            before.substringAfter("{").lines().map { it.trim() }.filter { it.isNotEmpty() })
        val dark = resume.substring(branch).substringAfter("{").substringBefore("}")
        assertEquals(listOf("showNight()", "reconcileKioskMode()", "return"), dark.lines().map { it.trim() }.filter { it.isNotEmpty() })
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
}
