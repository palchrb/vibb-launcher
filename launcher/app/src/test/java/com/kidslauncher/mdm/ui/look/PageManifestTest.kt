package com.kidslauncher.mdm.ui.look

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Design 78 §12.2 (revision 3): a page that tops a task of its own is two manifest entries, identical but for the
 * theme (R2), neither reachable from outside the process (R7); every activity declares `uiMode`, so the phone's theme
 * never restarts one (R8). Kalender's three pages in step 1, Meldinger's list and thread in step 2; the one-class pages
 * in Home's task (R9) and the notification's look-less entry (R7) since step 2.
 */
class PageManifestTest {
    private val ns = "http://schemas.android.com/apk/res/android"
    private fun file(path: String) = listOf("src/main/$path", "app/src/main/$path").map(::File).first { it.exists() }
    private val manifest = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
        .newDocumentBuilder().parse(file("AndroidManifest.xml"))

    private fun elements(tag: String): List<Element> =
        manifest.getElementsByTagName(tag).let { nodes -> (0 until nodes.length).map { nodes.item(it) as Element } }

    private fun name(activity: Element) = activity.getAttributeNS(ns, "name")

    /** Every attribute of [activity] but its name and its theme. */
    private fun rest(activity: Element): Map<String, String> =
        (0 until activity.attributes.length).map { activity.attributes.item(it) }
            .associate { it.nodeName to it.nodeValue } - "android:name" - "android:theme"

    /** The night class's entry to its light twin's. */
    private val pairs = mapOf(
        ".ui.day.DayActivity" to ".ui.day.LightDayActivity",
        ".ui.day.EventActivity" to ".ui.day.LightEventActivity",
        ".ui.day.EventEditActivity" to ".ui.day.LightEventEditActivity",
        ".ui.messages.MessagesActivity" to ".ui.messages.LightMessagesActivity",
        ".ui.messages.ThreadActivity" to ".ui.messages.LightThreadActivity",
    )

    /** The pages with one class, in Home's task (R9): the phone book and kid Settings' three lists. */
    private val oneClass = setOf(
        ".calls.PhoneBookActivity", ".ui.quickcontrols.WifiNetworksActivity", ".ui.quickcontrols.BluetoothDevicesActivity",
        ".ui.kidsettings.KidSoundPickerActivity",
    )

    @Test
    fun `a page is two entries, identical but for the theme, and neither is reachable from outside`() {
        val activities = elements("activity")
        fun on(theme: String) = activities.filter { it.getAttributeNS(ns, "theme") == theme }.map(::name).toSet()
        assertEquals(pairs.keys, on("@style/KidPageNightTheme"))
        assertEquals(pairs.values.toSet(), on("@style/KidPageLightTheme"))
        // The qualified theme itself is for a page with one class, in Home's task (R9).
        assertEquals(oneClass, on("@style/KidPageTheme"))
        for (page in oneClass) {
            val code = file("java/com/kidslauncher/mdm/${page.removePrefix(".").replace('.', '/')}.kt").readText()
            assertTrue(page, code.contains("\nclass ${page.substringAfterLast('.')} : KidPageActivity() {"))
        }
        for ((night, light) in pairs) {
            val twins = file("java/com/kidslauncher/mdm/${night.removePrefix(".").substringBeforeLast('.').replace('.', '/')}/LightPages.kt").readText()
            val nightEntry = activities.single { name(it) == night }
            val lightEntry = activities.single { name(it) == light }
            assertEquals(night, rest(nightEntry), rest(lightEntry))
            for (entry in listOf(nightEntry, lightEntry)) {
                assertEquals(name(entry), "false", entry.getAttributeNS(ns, "exported"))
                assertEquals("${name(entry)} has no filter", 0, entry.getElementsByTagName("*").length)
            }
            // The night class is the page (the look in attachBaseContext, PageColors, the registry); its twin is
            // that class and nothing more.
            val simple = night.substringAfterLast('.')
            val code = file("java/com/kidslauncher/mdm/${night.removePrefix(".").replace('.', '/')}.kt").readText()
            assertTrue(night, code.contains("\nopen class $simple : KidPageActivity() {"))
            assertTrue(light, twins.contains("\nclass Light$simple : $simple()\n"))
        }
        // No alias stands in for a page under a name of its own.
        assertTrue(elements("activity-alias").none { it.getAttributeNS(ns, "targetActivity") in pairs.keys + pairs.values + oneClass })
    }

    /** R7: what a notification names is the entry with no look - translucent (no starting window), a task of its own,
     * gone at once, and reachable from the launcher alone. */
    @Test
    fun `the message notification's entry has no look`() {
        val entry = elements("activity").single { name(it) == ".ui.messages.MessagesEntryActivity" }
        assertEquals("@android:style/Theme.Translucent.NoTitleBar", entry.getAttributeNS(ns, "theme"))
        for ((attribute, value) in listOf("exported" to "false", "noHistory" to "true", "excludeFromRecents" to "true", "taskAffinity" to "")) {
            assertTrue(attribute, entry.hasAttributeNS(ns, attribute))
            assertEquals(attribute, value, entry.getAttributeNS(ns, attribute))
        }
        assertEquals(0, entry.getElementsByTagName("*").length)
    }

    @Test
    fun `every activity handles uiMode itself`() {
        val activities = elements("activity")
        assertTrue(activities.size >= 26)
        for (activity in activities) {
            assertTrue(name(activity), "uiMode" in activity.getAttributeNS(ns, "configChanges").split('|'))
        }
        // The one plain Activity that had the application's AppCompat DayNight theme: a framework night theme now.
        val blocked = activities.single { name(it) == ".play.PlayLinkBlockedActivity" }
        assertEquals("@style/PlayLinkBlockedTheme", blocked.getAttributeNS(ns, "theme"))
        val styles = file("res/values/styles.xml").readText()
        assertTrue(styles.contains("""<style name="PlayLinkBlockedTheme" parent="@android:style/Theme.Material.NoActionBar">"""))
    }
}
