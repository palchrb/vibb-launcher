package com.kidslauncher.mdm.ui.look

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Design 78 §5.2-5.3: exactly the pages are on `KidPageTheme` and are relaunched by a change of the night bit; every
 * other activity declares `uiMode`, so the app's night mode never relaunches it. Kalender's three in step 1.
 */
class PageManifestTest {
    private val ns = "http://schemas.android.com/apk/res/android"
    private fun file(path: String) = listOf("src/main/$path", "app/src/main/$path").map(::File).first { it.exists() }
    private fun activities(): List<Element> {
        val nodes = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
            .newDocumentBuilder().parse(file("AndroidManifest.xml")).getElementsByTagName("activity")
        return (0 until nodes.length).map { nodes.item(it) as Element }
    }

    private val pages = setOf(".ui.day.DayActivity", ".ui.day.EventActivity", ".ui.day.EventEditActivity")

    @Test
    fun `exactly the pages are on the page theme, without uiMode`() {
        val themed = activities().filter { it.getAttributeNS(ns, "theme") == "@style/KidPageTheme" }.map { it.getAttributeNS(ns, "name") }
        assertEquals(pages, themed.toSet())
        for (activity in activities().filter { it.getAttributeNS(ns, "name") in pages }) {
            val name = activity.getAttributeNS(ns, "name")
            assertTrue(name, "uiMode" !in activity.getAttributeNS(ns, "configChanges").split('|'))
            // The class is a page too: the applied look in attachBaseContext, PageColors, the registry.
            val code = file("java/com/kidslauncher/mdm/${name.removePrefix(".").replace('.', '/')}.kt").readText()
            assertTrue(name, code.contains(": KidPageActivity()"))
        }
    }

    @Test
    fun `every other activity handles uiMode itself`() {
        val others = activities().filter { it.getAttributeNS(ns, "name") !in pages }
        assertTrue(others.size >= 20)
        for (activity in others) {
            val name = activity.getAttributeNS(ns, "name")
            assertTrue(name, "uiMode" in activity.getAttributeNS(ns, "configChanges").split('|'))
        }
        // The one plain Activity that had the application's AppCompat DayNight theme: a framework night theme now.
        val blocked = others.single { it.getAttributeNS(ns, "name") == ".play.PlayLinkBlockedActivity" }
        assertEquals("@style/PlayLinkBlockedTheme", blocked.getAttributeNS(ns, "theme"))
        val styles = file("res/values/styles.xml").readText()
        assertTrue(styles.contains("""<style name="PlayLinkBlockedTheme" parent="@android:style/Theme.Material.NoActionBar">"""))
    }
}
