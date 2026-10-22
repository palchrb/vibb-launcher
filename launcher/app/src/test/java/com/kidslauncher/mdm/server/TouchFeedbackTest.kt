package com.kidslauncher.mdm.server

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Touch sounds and vibration on Kid Settings (design r9 §4): the row's state, and where the two settings are written. */
class TouchFeedbackTest {
    @Test
    fun `the row from the phone - missing is on, no vibrator no switch, read-only without the app-op`() {
        assertEquals(TouchRowState(sounds = true, vibration = true, writable = true), touchRowState(1, 1, hasVibrator = true, canWrite = true))
        assertEquals(TouchRowState(sounds = false, vibration = false, writable = true), touchRowState(0, 0, hasVibrator = true, canWrite = true))
        assertEquals("missing keys read as on, as Android reads them", TouchRowState(true, true, true), touchRowState(null, null, true, true))
        // Design 18: no vibrator, no vibration switch - whatever the key says.
        assertEquals(TouchRowState(sounds = false, vibration = null, writable = true), touchRowState(0, 1, hasVibrator = false, canWrite = true))
        // Without "Modify system settings" the phone's state, read-only.
        assertEquals(TouchRowState(sounds = true, vibration = false, writable = false), touchRowState(1, 0, hasVibrator = true, canWrite = false))
    }

    private fun main(path: String) = listOf("src/main/java/com/kidslauncher/mdm/$path", "app/src/main/java/com/kidslauncher/mdm/$path")
        .map(::File).first { it.exists() }

    private fun code(file: File) = file.readText().replace(Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL), "").replace(Regex("//[^\\n]*"), "")

    @Test
    fun `the two settings are written only by QuickControls' setters, suspended on IO, one at a time`() {
        val root = listOf("src/main/java", "app/src/main/java").map(::File).first { it.exists() }
        for (file in root.walk().filter { it.isFile && it.name.endsWith(".kt") && it.name != "QuickControls.kt" }) {
            val text = code(file)
            assertFalse(file.name, text.contains("Settings.System.putInt"))
            for (key in listOf("SOUND_EFFECTS_ENABLED", "HAPTIC_FEEDBACK_ENABLED", "DTMF_TONE_WHEN_DIALING")) assertFalse("${file.name}: $key", text.contains(key))
        }
        val quick = code(main("server/QuickControls.kt"))
        // One putInt, in the helper both setters call; the dial-pad tones are left alone.
        assertEquals(1, Regex("""Settings\.System\.putInt\(""").findAll(quick).count())
        val put = quick.substringAfter("private fun putTouch(").substringBefore("\n    }")
        assertTrue(put.contains("Settings.System.putInt("))
        assertEquals(2, Regex("""putTouch\(context, """).findAll(quick).count())
        assertTrue(quick.contains("private val HAPTIC_FEEDBACK_KEY = Settings.System.HAPTIC_FEEDBACK_ENABLED"))
        assertFalse(quick.contains("DTMF_TONE_WHEN_DIALING"))
        for (setter in listOf("setTouchSounds", "setTouchVibration")) {
            val body = quick.substringAfter("suspend fun $setter(").substringBefore("\n    }")
            assertTrue(setter, body.contains("withContext(Dispatchers.IO)"))
            // qa-r9-revision-check N2: a second tap waits for the first write - two can't finish out of order.
            assertTrue(setter, body.contains("touchWrites.withLock"))
        }
        // The click samples follow the sound setting, as Android's Settings does - only after a write that worked.
        val sounds = quick.substringAfter("suspend fun setTouchSounds(").substringBefore("\n    }")
        assertTrue(sounds.indexOf("putTouch(") < sounds.indexOf("loadSoundEffects()"))
        assertTrue(sounds.contains("if (written)"))
        // Kid Settings calls them from its lifecycle scope and reads the row again only after the last write.
        val activity = code(main("ui/kidsettings/KidSettingsActivity.kt"))
        val write = activity.substringAfter("private fun writeTouch(").substringBefore("\n    }")
        assertTrue(write.contains("lifecycleScope.launch"))
        assertTrue(write.contains("if (touchWrites == 0) section?.let { renderControls(it) }"))
        assertTrue(activity.substringAfter("private fun renderTouch(").contains("if (!wanted || touchWrites > 0) return wanted"))
    }
}
