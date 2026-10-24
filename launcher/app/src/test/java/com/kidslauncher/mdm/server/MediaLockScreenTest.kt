package com.kidslauncher.mdm.server

import com.kidslauncher.mdm.R
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** PLAN "Android's lock screen shows the media card" (2): the pause screen's "Media on the lock screen" row. */
class MediaLockScreenTest {
    private val main = listOf("src/main", "app/src/main").map(::File).first { it.isDirectory }
    private fun src(path: String) = File(main, path).readText()

    /** Code only: comments may name what the code must never do. */
    private fun code(path: String) = src(path)
        .replace(Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL), "")
        .replace(Regex("//[^\\n]*"), "")

    @Test
    fun `the summary is the action line for every access state`() {
        assertEquals(R.string.settings_mdm_media_lock_open, mediaLockText(SystemPageAccess.CAN_OPEN))
        assertEquals(R.string.settings_mdm_media_lock_pause_first, mediaLockText(SystemPageAccess.PAUSE_FIRST))
        assertEquals(R.string.settings_mdm_media_lock_wait, mediaLockText(SystemPageAccess.WAIT))
        assertEquals(R.string.settings_mdm_media_lock_no_page, mediaLockText(SystemPageAccess.NO_PAGE))
    }

    /** qa-home-round-code Q-1: `media_controls_lock_screen` has no `@Readable` (added in Android 13, after S's bulk
     * annotation), so SettingsProvider refuses it to any app targeting S+ - a read would only ever throw. */
    @Test
    fun `the switch is never read - Android doesn't let an app read it`() {
        val glue = code("java/com/kidslauncher/mdm/server/MediaLockScreen.kt")
        assertFalse(glue.contains("Settings.Secure.get"))
        assertFalse(glue.contains("Settings.Secure.put"))
        assertFalse(glue.contains("media_controls_lock_screen"))
        assertFalse(glue.contains("contentResolver"))
        val fragment = code("java/com/kidslauncher/mdm/ui/settings/launcher/SettingsFragmentLauncher.kt")
        assertFalse(fragment.contains("media_controls_lock_screen"))
    }

    @Test
    fun `the page is Sound and vibration, through the battery row's resolution`() {
        val glue = code("java/com/kidslauncher/mdm/server/MediaLockScreen.kt")
        assertTrue(glue.contains("systemPageIntent(context, Intent(Settings.ACTION_SOUND_SETTINGS))"))
        assertEquals(1, Regex("""systemPageIntent\(""").findAll(glue).count())
    }

    @Test
    fun `the summary names the way to the Media page, in both languages`() {
        val en = src("res/values/strings.xml")
        val nb = src("res/values-nb/strings.xml")
        fun string(xml: String, name: String) = xml.substringAfter("<string name=\"$name\">").substringBefore("</string>")
        for (name in listOf("settings_mdm_media_lock_open", "settings_mdm_media_lock_pause_first", "settings_mdm_media_lock_no_page")) {
            assertTrue(name, string(nb, name).contains("Lyd og vibrasjon › Medier › Vis medier på låseskjermen av"))
            assertTrue(name, string(en, name).contains("Sound &amp; vibration › Media › Show media on lock screen off"))
        }
        assertEquals("Medier på låseskjermen", string(nb, "settings_mdm_media_lock"))
        // No state line: Android doesn't tell an app (Q-1).
        for (gone in listOf("settings_mdm_media_lock_on", "settings_mdm_media_lock_off", "settings_mdm_media_lock_unknown", "settings_mdm_media_lock_summary")) {
            assertFalse(gone, en.contains("\"$gone\"") || nb.contains("\"$gone\""))
        }
    }

    @Test
    fun `the row - always shown under Fingerprint, a tap always opens, read again with the others`() {
        val prefs = src("res/xml/preferences.xml")
        val row = prefs.substringAfter("android:key=\"settings_mdm_media_lock\"").substringBefore("/>")
        assertFalse("shown always, like the battery row", "isPreferenceVisible" in row)
        assertTrue(prefs.indexOf("settings_mdm_fingerprint\"") < prefs.indexOf("settings_mdm_media_lock\""))
        assertTrue(prefs.indexOf("settings_mdm_media_lock\"") < prefs.indexOf("settings_play_install_mode\""))
        val fragment = code("java/com/kidslauncher/mdm/ui/settings/launcher/SettingsFragmentLauncher.kt")
        val tap = fragment.substringAfter("findPreference<Preference>(MEDIA_LOCK)?.setOnPreferenceClickListener {").substringBefore("\n        }\n")
        assertTrue(tap, tap.contains("val pages = MediaLockScreen.pageIntents(requireContext())"))
        assertTrue(tap, tap.contains("if (pages.isNotEmpty()) mediaLockPageMissing = !openFirstPage(pages, mediaLockLauncher)"))
        assertFalse("no condition on a state it can't read", tap.contains("if (media"))
        val rows = fragment.substringAfter("private fun updateSystemPageRows()").substringBefore("\n    }\n")
        assertTrue("updateMediaLockRow()" in rows)
        val back = fragment.substringAfter("private val mediaLockLauncher").substringBefore("\n    }\n")
        assertTrue("updateMediaLockRow()" in back)
        val update = fragment.substringAfter("private fun updateMediaLockRow()").substringBefore("\n    }\n")
        assertTrue(update.contains("row.summary = getString(mediaLockText(pageAccess(MediaLockScreen.pageIntents(context), mediaLockPageMissing)))"))
    }
}
