package com.kidslauncher.mdm.server

import com.kidslauncher.mdm.R
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** PLAN "Android's lock screen shows the media card" (2): the pause screen's "Media on the lock screen" row. */
class MediaLockScreenTest {
    private val main = listOf("src/main", "app/src/main").map(::File).first { it.isDirectory }
    private fun src(path: String) = File(main, path).readText()

    @Test
    fun `the switch read as SystemUI reads it - anything but 0 is on, unset is Android's default on`() {
        assertEquals("media_controls_lock_screen", MEDIA_CONTROLS_LOCK_SCREEN)
        assertEquals(1, MEDIA_CONTROLS_LOCK_SCREEN_DEFAULT)
        assertEquals(true, mediaOnLockScreen(1))
        assertEquals(true, mediaOnLockScreen(MEDIA_CONTROLS_LOCK_SCREEN_DEFAULT))
        assertEquals(true, mediaOnLockScreen(2))
        assertEquals(false, mediaOnLockScreen(0))
        assertNull("unreadable", mediaOnLockScreen(null))
        val glue = src("java/com/kidslauncher/mdm/server/MediaLockScreen.kt")
        assertTrue(glue.contains("Settings.Secure.getInt(context.contentResolver, MEDIA_CONTROLS_LOCK_SCREEN, MEDIA_CONTROLS_LOCK_SCREEN_DEFAULT)"))
        assertTrue("a throwing read is unknown", glue.contains("runCatching {"))
        // Read only: neither a device owner nor the music app may write it.
        assertFalse(glue.contains("putInt("))
    }

    @Test
    fun `the state line and the action line for every state`() {
        assertEquals(R.string.settings_mdm_media_lock_on, mediaLockStateText(true))
        assertEquals(R.string.settings_mdm_media_lock_off, mediaLockStateText(false))
        assertEquals(R.string.settings_mdm_media_lock_unknown, mediaLockStateText(null))
        for (shown in listOf(true, null)) {
            assertEquals(R.string.settings_mdm_media_lock_open, mediaLockActionText(shown, SystemPageAccess.CAN_OPEN))
            assertEquals(R.string.settings_mdm_media_lock_pause_first, mediaLockActionText(shown, SystemPageAccess.PAUSE_FIRST))
            assertEquals(R.string.settings_mdm_media_lock_wait, mediaLockActionText(shown, SystemPageAccess.WAIT))
            assertEquals(R.string.settings_mdm_media_lock_no_page, mediaLockActionText(shown, SystemPageAccess.NO_PAGE))
        }
        // Off: nothing left to do - the state alone.
        for (access in SystemPageAccess.entries) assertNull(mediaLockActionText(false, access))
        assertTrue(mediaLockTapOpens(true))
        assertTrue("an unreadable switch may still be on", mediaLockTapOpens(null))
        assertFalse(mediaLockTapOpens(false))
    }

    @Test
    fun `the page is Sound and vibration, through the battery row's resolution`() {
        val glue = src("java/com/kidslauncher/mdm/server/MediaLockScreen.kt")
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
        assertEquals("På - vises på Androids låseskjerm", string(nb, "settings_mdm_media_lock_on"))
        assertEquals("Av", string(nb, "settings_mdm_media_lock_off"))
    }

    @Test
    fun `the row - always shown under Fingerprint, the fragment's glue`() {
        val prefs = src("res/xml/preferences.xml")
        val row = prefs.substringAfter("android:key=\"settings_mdm_media_lock\"").substringBefore("/>")
        assertFalse("shown always, like the battery row", "isPreferenceVisible" in row)
        assertTrue(prefs.indexOf("settings_mdm_fingerprint\"") < prefs.indexOf("settings_mdm_media_lock\""))
        assertTrue(prefs.indexOf("settings_mdm_media_lock\"") < prefs.indexOf("settings_play_install_mode\""))
        val fragment = src("java/com/kidslauncher/mdm/ui/settings/launcher/SettingsFragmentLauncher.kt")
        val tap = fragment.substringAfter("findPreference<Preference>(MEDIA_LOCK)?.setOnPreferenceClickListener {").substringBefore("\n        }\n")
        assertTrue(tap, tap.contains("if (mediaLockTapOpens(MediaLockScreen.shown(context))) {"))
        assertTrue(tap, tap.contains("mediaLockPageMissing = !openFirstPage(pages, mediaLockLauncher)"))
        // Read again on resume, after the tap, when the pause starts and after each apply (updateSystemPageRows).
        val rows = fragment.substringAfter("private fun updateSystemPageRows()").substringBefore("\n    }\n")
        assertTrue("updateMediaLockRow()" in rows)
        val back = fragment.substringAfter("private val mediaLockLauncher").substringBefore("\n    }\n")
        assertTrue("updateMediaLockRow()" in back)
        val update = fragment.substringAfter("private fun updateMediaLockRow()").substringBefore("\n    }\n")
        assertTrue(update.contains("mediaLockActionText(shown, pageAccess(MediaLockScreen.pageIntents(context), mediaLockPageMissing))"))
    }
}
