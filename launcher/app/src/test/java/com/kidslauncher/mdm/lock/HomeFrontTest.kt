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
        assertTrue(init.indexOf("BootCover.init(app)") in 0 until init.indexOf("BootHome.startIfDue"))
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
}
