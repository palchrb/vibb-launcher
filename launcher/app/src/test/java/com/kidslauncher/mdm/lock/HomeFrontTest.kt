package com.kidslauncher.mdm.lock

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Design 16, QA #1: every start of our Home is the typed HOME intent ([HomeFront]) - an explicit
 * component makes a STANDARD Home task next to the HOME-typed one. And the lock leaves through
 * Home (QA #5(a)): Home is started before the lock finishes.
 */
class HomeFrontTest {
    private val ns = "http://schemas.android.com/apk/res/android"
    private fun file(path: String) = listOf("src/main/$path", "app/src/main/$path").map(::File).first { it.exists() }

    /** Code only: comments may name what the code must never do. */
    private fun code(f: File) = f.readText()
        .replace(Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL), "")
        .replace(Regex("//[^\\n]*"), "")

    @Test
    fun `no explicit start of HomeActivity anywhere`() {
        val sources = file("java/com/kidslauncher/mdm").walk().filter { it.isFile && it.name.endsWith(".kt") }.toList()
        assertTrue(sources.size > 100)
        val banned = listOf(
            Regex("Intent\\([^()]*HomeActivity::class"),
            Regex("setClass(Name)?\\([^()]*HomeActivity"),
            Regex("setComponent\\([^;\\n]*HomeActivity"),
        )
        for (source in sources) {
            val text = code(source)
            for (pattern in banned) {
                pattern.find(text)?.let { fail("${source.name}: ${it.value} - use HomeFront (a typed HOME start)") }
            }
        }
    }

    @Test
    fun `HomeFront starts MAIN plus HOME only, restricted to our package, no component`() {
        val text = code(file("java/com/kidslauncher/mdm/lock/HomeFront.kt"))
        // No Home start once ACTION_SHUTDOWN armed the boot cover (qa-16b-code #3).
        assertTrue(text.substringAfter("fun bring(").contains("if (BootCover.shuttingDown)"))
        for (needed in listOf("Intent.ACTION_MAIN", "Intent.CATEGORY_HOME", "setPackage(context.packageName)", "FLAG_ACTIVITY_NEW_TASK")) {
            assertTrue(needed, text.contains(needed))
        }
        // Another category, a component or a class would make the start untyped (AR isHomeIntent).
        for (bannedToken in listOf("setComponent", "setClass", "ComponentName", "CATEGORY_DEFAULT", "CATEGORY_LAUNCHER", "HomeActivity")) {
            assertFalse(bannedToken, text.contains(bannedToken))
        }
    }

    @Test
    fun `our HOME activities are HomeActivity and the boot cover, which is off unless armed for a boot (16b)`() {
        val doc = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }.newDocumentBuilder().parse(file("AndroidManifest.xml"))
        val activities = doc.getElementsByTagName("activity").let { nodes -> (0 until nodes.length).map { nodes.item(it) as Element } } +
            doc.getElementsByTagName("activity-alias").let { nodes -> (0 until nodes.length).map { nodes.item(it) as Element } }
        val home = activities.filter { activity ->
            val categories = activity.getElementsByTagName("category")
            (0 until categories.length).any { (categories.item(it) as Element).getAttributeNS(ns, "name") == "android.intent.category.HOME" }
        }.map { it.getAttributeNS(ns, "name") }
        assertEquals(listOf(".ui.HomeActivity", ".lock.BootCoverActivity"), home)
        // The cover is disabled in the manifest and handed over (disabled) when our process starts
        // after the unlock, before the boot's typed Home start - so that resolves to HomeActivity.
        val cover = activities.first { it.getAttributeNS(ns, "name") == ".lock.BootCoverActivity" }
        assertEquals("false", cover.getAttributeNS(ns, "enabled"))
        val runtime = code(file("java/com/kidslauncher/mdm/lock/PinLockRuntime.kt"))
        val init = runtime.substringAfter("handler.post {")
        assertTrue(init.indexOf("BootCover.init(app") in 0 until init.indexOf("BootHome.startIfDue"))
    }

    @Test
    fun `the lock starts Home before it finishes`() {
        val text = code(file("java/com/kidslauncher/mdm/lock/PinLockActivity.kt"))
        val go = text.substringAfter("private fun go(plan: LockLeave)").substringBefore("\n    }")
        val home = go.indexOf("HomeFront.bring")
        val finish = go.indexOf("finishAndRemoveTask")
        assertTrue(go, home in 0 until finish)
        assertTrue("stop (as the root) before Home", go.indexOf("stopLockTaskQuietly") in 0 until home)
    }

    /** Design 59d step B (the rc.2 recording's 3 black Home frames): Home after the face's leave draws its own ground. */
    @Test
    fun `Home after the face's leave draws its own ground until it stops`() {
        assertEquals(com.kidslauncher.mdm.ui.wallpaper.GroundKind.FULL, com.kidslauncher.mdm.ui.wallpaper.groundKind(systemShowsOurs = true, scrimAlpha = 0, ownGround = true))
        assertEquals(com.kidslauncher.mdm.ui.wallpaper.GroundKind.TRANSPARENT, com.kidslauncher.mdm.ui.wallpaper.groundKind(systemShowsOurs = true, scrimAlpha = 0))
        assertEquals(com.kidslauncher.mdm.ui.wallpaper.GroundKind.SCRIM, com.kidslauncher.mdm.ui.wallpaper.groundKind(systemShowsOurs = true, scrimAlpha = 40))
        assertEquals(com.kidslauncher.mdm.ui.wallpaper.GroundKind.FULL, com.kidslauncher.mdm.ui.wallpaper.groundKind(systemShowsOurs = false, scrimAlpha = 0))
        val front = code(file("java/com/kidslauncher/mdm/lock/HomeFront.kt"))
        val bring = front.substringAfter("fun bring(")
        // R1-4: set only by a noAnimation start that went through, and it expires.
        val set = bring.indexOf("if (noAnimation) ownGroundSetAt = android.os.SystemClock.elapsedRealtime()")
        assertTrue("set after the start returned", set > bring.indexOf("context.startActivity(intent(context, animate, noAnimation, arrival))"))
        assertEquals(1, Regex("ownGroundSetAt = android").findAll(front).count())
        assertTrue(ownGroundDue(setAtMs = 10_000L, nowMs = 10_000L + OWN_GROUND_WINDOW_MS))
        assertFalse("a later, unrelated resume", ownGroundDue(setAtMs = 10_000L, nowMs = 10_001L + OWN_GROUND_WINDOW_MS))
        assertFalse("none set", ownGroundDue(setAtMs = null, nowMs = 10_000L))
        assertEquals(3_000L, OWN_GROUND_WINDOW_MS)
        // R1-3: the relock's queued Home after the unlock is unanimated as the face too.
        assertTrue(front.contains("KeyguardCover.afterAndroidUnlock(context, \"Home (\$why)\") { bring(it, why, noAnimation = AndroidLock.faceOn) }"))
        val home = code(file("java/com/kidslauncher/mdm/ui/HomeActivity.kt"))
        assertTrue(home.substringAfter("override fun onResume() {").substringBefore("startWork()").contains("if (HomeFront.takeOwnGroundDue()) ground = ground.copy(faceLeave = true)"))
        assertTrue(home.substringAfter("override fun onStop() {").substringBefore("\n    }\n").contains("ground = HomeGroundState()"))
        assertTrue(home.contains("WallpaperGround.apply(this, binding.root, ownGround = homeGroundDue(ground, now))"))
        // Until the stop, whatever else (the Home button's window is HomeGroundTest's).
        val faceLeave = com.kidslauncher.mdm.ui.home.HomeGroundState(faceLeave = true, leftFront = true, enterDone = true)
        assertTrue(com.kidslauncher.mdm.ui.home.homeGroundDue(faceLeave, nowMs = 1_000_000L))
    }

    /** Design 32 (QA M2): the lock-up's Home start has no open animation and is still typed HOME. */
    @Test
    fun `HomeFront without an animation adds only NO_ANIMATION`() {
        val text = code(file("java/com/kidslauncher/mdm/lock/HomeFront.kt"))
        val intent = text.substringAfter("fun intent(").substringBefore("fun bring(")
        assertTrue(intent, intent.startsWith("context: Context, animate: Boolean = true, noAnimation: Boolean = false, arrival: HomeArrival = HomeArrival.OURS): Intent ="))
        assertTrue(intent, intent.contains(".addFlags(if (animate && !noAnimation) Intent.FLAG_ACTIVITY_NEW_TASK else Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)"))
        assertEquals("one category, no component", 1, Regex("addCategory\\(").findAll(intent).count())
        assertTrue(text.contains("fun bring(\n        context: Context,\n        why: String,\n        animate: Boolean = true,\n        noAnimation: Boolean = false,\n        arrival: HomeArrival = HomeArrival.OURS,\n    ): Boolean"))
        val bring = text.substringAfter("fun bring(")
        assertTrue(bring.indexOf("if (animate) PinLockRuntime.endDarkLockUp()") in 0 until bring.indexOf("context.startActivity(intent(context, animate, noAnimation, arrival))"))
        // qa-32-s1-code L2: such a start from another thread runs on the main thread, where the
        // lock-up marks and starts in one pass - first thing, before the mark is ended.
        val hop = bring.indexOf("if (animate && Looper.myLooper() != Looper.getMainLooper()) {")
        assertTrue(bring, hop in 0 until bring.indexOf("PinLockRuntime.endDarkLockUp()"))
        // Design 59d step B (Q59d-7): noAnimation keeps the hop and the dark mark's end (only `animate` skips them).
        assertTrue(bring.substring(hop).substringBefore("return true").contains("main.post { bring(app, why, noAnimation = noAnimation, arrival = arrival) }"))
        // The two callers: the lock-up's `animate = false` (below) and the face's leave's `noAnimation`.
        val noAnim = file("java/com/kidslauncher/mdm").walk().filter { it.isFile && it.name.endsWith(".kt") }
            .filter { code(it).contains("noAnimation = ") && it.name != "HomeFront.kt" }.map { it.name }.toList()
        assertEquals(listOf("PinLockActivity.kt"), noAnim)
        assertTrue(code(file("java/com/kidslauncher/mdm/lock/PinLockActivity.kt")).contains("HomeFront.bring(this, \"the lock left\", noAnimation = AndroidLock.faceOn, arrival = arrival)"))
        assertTrue(text.contains("private val main = Handler(Looper.getMainLooper())"))
        // Only the lock-up starts Home unanimated - every other start ends its dark mark.
        val sources = file("java/com/kidslauncher/mdm").walk().filter { it.isFile && it.name.endsWith(".kt") }.toList()
        val unanimated = sources.filter { code(it).contains("animate = false") }.map { it.name }
        assertEquals(listOf("PinLockRuntime.kt"), unanimated)
        assertEquals(1, Regex("animate = false").findAll(code(file("java/com/kidslauncher/mdm/lock/PinLockRuntime.kt"))).count())
    }
}
