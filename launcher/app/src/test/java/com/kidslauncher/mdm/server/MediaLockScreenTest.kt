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

}
