package com.kidslauncher.mdm.lock

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
        // The night ground takes the content off the window and closes Home's contact sheet.
        val night = body(home, "showNight")
        assertTrue(night.contains("contentShown = false"))
        assertTrue(night.contains("setContentView(ground.root)"))
        assertTrue(night.contains("contactSheet?.dismiss()"))
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
    fun `the lock mode is decided before any screen and its chrome never runs on the main thread`() {
        val app = code("java/com/kidslauncher/mdm/Application.kt")
        val unlocked = body(app, "initUnlocked")
        assertTrue(unlocked.indexOf("PinLockRuntime.decide(this)") in 0 until unlocked.indexOf("LauncherPreferences.init("))
        val runtime = code("java/com/kidslauncher/mdm/lock/PinLockRuntime.kt")
        val init = body(runtime, "init")
        assertTrue(init.indexOf("decide(app)") in 0 until init.indexOf("handler.post {"))
        // The chrome only through refreshChrome, which runs it on its own thread.
        assertEquals(listOf("refreshChrome"), Regex("fun (\\w+)\\([^)]*\\)[^{]*\\{[^}]*LockTaskChrome\\.refresh\\(").findAll(runtime).map { it.groupValues[1] }.toList())
        assertTrue(body(runtime, "refreshChrome").contains("chromeExecutor.execute"))
        // The lock enters lock task once the chrome pinned it (kiosk off).
        val lock = code("java/com/kidslauncher/mdm/lock/PinLockActivity.kt")
        assertTrue(lock.contains("PinLockRuntime.addChromeListener(chromeListener)"))
        assertTrue(lock.contains("PinLockRuntime.removeChromeListener(chromeListener)"))
    }
}
