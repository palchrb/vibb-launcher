package com.kidslauncher.mdm.lock

import com.kidslauncher.mdm.server.ChromeWrite
import com.kidslauncher.mdm.server.chromeWriteOrder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Design 16c: Home never shows content while locked (emulator run 2026-10-07: contacts and apps
 * for ~20 s at boot), and the lock comes up in the same pass that brings Home forward.
 */
class HomeGateTest {
    private val ns = "http://schemas.android.com/apk/res/android"
    private fun file(path: String) = listOf("src/main/$path", "app/src/main/$path").map(::File).first { it.exists() }

    /** Code only: comments may name what the code must never do. */
    private fun code(path: String) = file(path).readText()
        .replace(Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL), "")
        .replace(Regex("//[^\\n]*"), "")

    /** The body of `fun <name>(` up to the next member at the class's indentation. */
    private fun body(text: String, name: String): String {
        val start = text.indexOf("fun $name(")
        assertTrue("fun $name not found", start >= 0)
        val rest = text.substring(start)
        val end = Regex("\\n    (override |private |internal |fun |val |var |/\\*\\*|companion)").find(rest, 1)?.range?.first ?: rest.length
        return rest.substring(0, end)
    }

    @Test
    fun `content only when the lock is decided and not LOCKED`() {
        for (pin in listOf(true, false)) {
            assertFalse(homeShowsContent(LockMode.LOCKED, pinActive = pin, known = true))
            assertTrue(homeShowsContent(LockMode.UNLOCKED, pinActive = pin, known = true))
            // DISABLED = the lock is inactive: no kid PIN, an Android credential, unmanaged.
            assertTrue(homeShowsContent(LockMode.DISABLED, pinActive = pin, known = true))
        }
    }

    @Test
    fun `not decided yet - hidden whenever a kid PIN is set`() {
        for (mode in LockMode.entries) {
            assertFalse(homeShowsContent(mode, pinActive = true, known = false))
            assertTrue(homeShowsContent(mode, pinActive = false, known = false))
        }
    }

    @Test
    fun `boot Home - our Home already up means no second start and the lock at once`() {
        var asked = 0
        val due = { asked++; true }
        assertEquals(BootHomeAction.NONE, bootHomeAction(firstStartOfBoot = false, homeShown = false, due = due))
        assertEquals(BootHomeAction.NONE, bootHomeAction(firstStartOfBoot = false, homeShown = true, due = due))
        assertEquals(BootHomeAction.MARK_DONE, bootHomeAction(firstStartOfBoot = true, homeShown = true, due = due))
        // The gate (it may decode the cached policy) is only read when it decides.
        assertEquals(0, asked)
        assertEquals(BootHomeAction.START, bootHomeAction(firstStartOfBoot = true, homeShown = false, due = due))
        assertEquals(BootHomeAction.NONE, bootHomeAction(firstStartOfBoot = true, homeShown = false) { false })
        assertEquals(1, asked)
        // No Home start (MARK_DONE, NONE): the process start shows the lock in the same pass - no
        // 1 s wait for a Home resume that never comes.
        assertEquals(
            LockStep(LockMode.LOCKED, showLock = true),
            step(LockMode.DISABLED, LockEvent.ProcessStart(active = true, interactive = true, homeFirst = false)),
        )
        assertEquals(
            LockStep(LockMode.LOCKED, showLockLater = true),
            step(LockMode.DISABLED, LockEvent.ProcessStart(active = true, interactive = true, homeFirst = true)),
        )
    }

    @Test
    fun `the night ground has nothing to touch - the breathing mark on the night ground, centred`() {
        val root = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }.newDocumentBuilder()
            .parse(file("res/layout/activity_home_night.xml")).documentElement
        val all = listOf(root) + root.getElementsByTagName("*").let { nodes -> (0 until nodes.length).map { nodes.item(it) as Element } }
        assertEquals(listOf("FrameLayout", "ImageView"), all.map { it.tagName })
        for (element in all) {
            for (attr in listOf("clickable", "longClickable", "focusable", "focusableInTouchMode", "onClick")) {
                assertFalse("${element.tagName} $attr", element.hasAttributeNS(ns, attr))
            }
        }
        assertEquals("@color/kid_ground", root.getAttributeNS(ns, "background"))
        val mark = all[1]
        assertEquals("@drawable/splash_vibb_breathe", mark.getAttributeNS(ns, "src"))
        assertEquals("center", mark.getAttributeNS(ns, "layout_gravity"))
        assertEquals("@+id/home_night", root.getAttributeNS(ns, "id"))
    }

    @Test
    fun `Home's touchables are gone while locked - the content exists only from the UNLOCKED edge`() {
        val home = code("java/com/kidslauncher/mdm/ui/HomeActivity.kt")
        // Inflated in one place, reached only through the gate.
        assertEquals(1, Regex("ActivityHomeBinding\\.inflate").findAll(home).count())
        assertTrue(body(home, "bindContent").contains("ActivityHomeBinding.inflate"))
        assertEquals(1, Regex("bindContent\\(\\)").findAll(home).count() - 1)
        assertTrue(body(home, "showContent").contains("bindContent()"))
        assertEquals(1, Regex("showContent\\(\\)").findAll(home).count() - 1)
        assertTrue(body(home, "gate").contains("if (contentAllowed()) showContent() else showNight()"))
        assertTrue(body(home, "contentAllowed").contains("homeShowsContent("))
        // onCreate wires nothing of the content.
        val onCreate = body(home, "onCreate")
        assertTrue(onCreate.contains("gate()"))
        for (banned in listOf("binding", "gridAdapter", "gestureDetector", "setContentView")) {
            assertFalse("onCreate: $banned", onCreate.contains(banned))
        }
        // The night ground takes the content off the window and closes what Home opened over it.
        val night = body(home, "showNight")
        assertTrue(night.contains("contentShown = false"))
        assertTrue(night.contains("setContentView(ground.root)"))
        assertTrue(night.contains("closeOverlays()"))
        // No swipe to the drawer or the kid's Settings on the night ground.
        val touch = home.substringAfter("override fun onTouchEvent(event: MotionEvent)").substringBefore("\n    }")
        assertTrue(touch, touch.contains("if (contentShown) gestureDetector.onTouchEvent(event)"))
        // Every render binds only while the content is shown.
        for (render in listOf("render", "renderCallParts", "renderGrid", "renderWallpaper", "renderCallCard", "refreshApps", "loadMissedCalls", "resumeContent")) {
            val first = body(home, render).lines().drop(1).first { it.isNotBlank() }.trim()
            assertTrue("$render: $first", first.startsWith("if (!contentShown"))
        }
        // While locked the resume shows the lock: no render and no policy decode before it.
        val resume = body(home, "onResume")
        val locked = resume.substringAfter("if (!gate()) {").substringBefore("\n            return\n        }")
        assertTrue(locked.contains("PinLockRuntime.show(this)"))
        assertTrue(locked.indexOf("reconcileKioskMode()") in 0 until locked.indexOf("PinLockRuntime.show(this)"))
        for (slow in listOf("reevaluateLockReasonFromCache", "render", "resumeContent", "loadMissedCalls", "promptForCallRoleIfNeeded")) {
            assertFalse("locked resume: $slow", locked.contains(slow))
        }
        assertTrue(resume.indexOf("if (!gate())") in 0 until resume.indexOf("reevaluateLockReasonFromCache"))
    }

    @Test
    fun `Home closes its contact sheet, long-press menu and rename dialog when the lock engages`() {
        val home = code("java/com/kidslauncher/mdm/ui/HomeActivity.kt")
        assertTrue(home.contains("HomeGridAdapter(this, ::trackOverlay)"))
        assertTrue(body(home, "showContactSheet").contains("trackOverlay { sheet.dismiss() }"))
        assertTrue(body(home, "onDestroy").contains("closeOverlays()"))
        val adapter = code("java/com/kidslauncher/mdm/ui/home/HomeGridAdapter.kt")
        assertTrue(adapter.contains("showAppContextMenu(activity, v, info, onOverlay)"))
        val menu = code("java/com/kidslauncher/mdm/ui/list/apps/ContextMenuActions.kt")
        val show = menu.substringAfter("fun showAppContextMenu(")
        assertTrue(show.contains("onOverlay { popup.dismiss() }"))
        assertTrue(show.contains("val dialog = appInfo.showRenameDialog(activity)"))
        assertTrue(show.contains("onOverlay { dialog.dismiss() }"))
    }

    @Test
    fun `the chrome writes - shade and overlays blocked first when locking, lifted last otherwise`() {
        assertEquals(listOf(ChromeWrite.STATUS_BAR, ChromeWrite.CREATE_WINDOWS, ChromeWrite.LOCK_TASK), chromeWriteOrder(locked = true))
        assertEquals(listOf(ChromeWrite.LOCK_TASK, ChromeWrite.STATUS_BAR, ChromeWrite.CREATE_WINDOWS), chromeWriteOrder(locked = false))
        val chrome = code("java/com/kidslauncher/mdm/lock/LockTaskChrome.kt")
        // Every pass writes in that order.
        val apply = chrome.substringAfter("private fun apply(").substringBefore("\n    private fun writeLockTask")
        assertTrue(apply.contains("for (write in chromeWriteOrder(locked))"))
        for (pass in listOf("applyNow", "applyFallback")) {
            assertTrue(pass, body(chrome, pass).contains("locked = locked)"))
        }
    }

    @Test
    fun `the chrome is in place before the lock resumes - no PackageManager work under the monitor`() {
        val chrome = code("java/com/kidslauncher/mdm/lock/LockTaskChrome.kt")
        // applyPlan resolves the helpers before taking the monitor; the newest lookup wins.
        val plan = body(chrome, "applyPlan")
        assertTrue(plan.indexOf("runCatching(pinLockHelpers)") in 0 until plan.indexOf("synchronized(this)"))
        assertTrue(plan.contains("ticket > helpersTicket"))
        assertFalse("applyPlan is not @Synchronized", chrome.substringBefore("fun applyPlan(").trimEnd().endsWith("@Synchronized"))
        // Inside the monitor only cached helpers.
        for (pass in listOf("applyNow", "applyFallback", "voipPackages", "lockHelpers", "apply")) {
            assertFalse(pass, body(chrome, pass).contains("AppEnforcer.resolve"))
        }
        val refresh = body(chrome, "refresh")
        assertTrue(refresh.indexOf("prefetchHelpers(context)") in 0 until refresh.indexOf("synchronized(this)"))
        assertTrue(refresh.contains("if (resolveMissing && "))

        // The LOCKED edge: the lock's start, then the chrome on the same (main) thread - the lock
        // resumes only after dispatch returns. Every other change runs on the chrome thread.
        val runtime = code("java/com/kidslauncher/mdm/lock/PinLockRuntime.kt")
        val dispatch = body(runtime, "dispatch")
        val show = dispatch.indexOf("if (result.showLock) show(context, wake = result.wake)")
        val now = dispatch.indexOf("if (lockedEdge) refreshChromeNow(context)")
        assertTrue(show in 0 until now)
        assertTrue(now < dispatch.indexOf("modeListeners"))
        assertTrue(dispatch.contains("if (!lockedEdge) refreshChrome(context)"))
        assertTrue(dispatch.contains("val lockedEdge = before != LockMode.LOCKED && result.mode == LockMode.LOCKED"))
        val sync = body(runtime, "refreshChromeNow")
        assertTrue(sync.contains("LockTaskChrome.refresh(app, resolveMissing = false)"))
        assertFalse(sync.contains("chromeExecutor"))
        assertTrue(sync.contains("if (LockTaskChrome.helpersMissing) refreshChrome(app)"))
        assertTrue(body(runtime, "refreshChrome").contains("chromeExecutor.execute"))
        // Only those two passes in the runtime.
        assertEquals(
            listOf("refreshChromeNow", "refreshChrome"),
            Regex("fun (\\w+)\\([^)]*\\)[^{]*\\{[^}]*LockTaskChrome\\.refresh\\(").findAll(runtime).map { it.groupValues[1] }.toList(),
        )
        // The lock still enters lock task when a later pass pinned it (kiosk off).
        val lock = code("java/com/kidslauncher/mdm/lock/PinLockActivity.kt")
        assertTrue(lock.contains("PinLockRuntime.addChromeListener(chromeListener)"))
        assertTrue(lock.contains("PinLockRuntime.removeChromeListener(chromeListener)"))
    }

    @Test
    fun `the lock mode is decided before any screen, and unreadable means LOCKED with the lock shown`() {
        val app = code("java/com/kidslauncher/mdm/Application.kt")
        val unlocked = body(app, "initUnlocked")
        assertTrue(unlocked.indexOf("PinLockRuntime.decide(this)") in 0 until unlocked.indexOf("LauncherPreferences.init("))

        // qa-16c-code #3: never a dark Home with no lock.
        var reported: Exception? = null
        assertTrue(lockActiveAtStart({ reported = it }) { throw IllegalStateException("CE prefs") })
        assertTrue(reported is IllegalStateException)
        assertFalse(lockActiveAtStart { false })
        assertTrue(lockActiveAtStart { true })
        assertEquals(
            LockStep(LockMode.LOCKED, showLock = true),
            step(LockMode.DISABLED, LockEvent.ProcessStart(active = lockActiveAtStart { error("unreadable") }, interactive = true)),
        )
        val runtime = code("java/com/kidslauncher/mdm/lock/PinLockRuntime.kt")
        val decide = body(runtime, "decide")
        assertTrue(decide.contains("startActive = lockActiveAtStart("))
        assertTrue(decide.indexOf("decided = true") > decide.indexOf("lockActiveAtStart("))
        // init survives the store: the receiver and ProcessStart always follow.
        val init = body(runtime, "init")
        assertTrue(init.indexOf("decide(app)") in 0 until init.indexOf("handler.post {"))
        val reads = init.substringBefore("ContextCompat.registerReceiver(")
        assertEquals(2, Regex("PinLockStore\\.").findAll(reads).count())
        assertEquals(2, Regex("catch \\(e: Exception\\)").findAll(reads).count())
        // The lock itself: an unreadable guard is armed, the parent code always opens it.
        val lock = code("java/com/kidslauncher/mdm/lock/PinLockActivity.kt")
        assertTrue(lock.contains("runCatching { PinLockStore.guard(this) }.getOrDefault(CrashGuard())"))
        assertTrue(body(runtime, "checkParentCode").contains("Backoff reset failed"))
    }
}
