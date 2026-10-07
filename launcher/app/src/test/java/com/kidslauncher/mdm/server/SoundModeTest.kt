package com.kidslauncher.mdm.server

import com.kidslauncher.mdm.lock.INTERRUPTION_FILTER_ALARMS
import com.kidslauncher.mdm.lock.INTERRUPTION_FILTER_ALL
import com.kidslauncher.mdm.lock.INTERRUPTION_FILTER_NONE
import com.kidslauncher.mdm.lock.INTERRUPTION_FILTER_PRIORITY
import com.kidslauncher.mdm.lock.RINGER_MODE_NORMAL
import com.kidslauncher.mdm.lock.RINGER_MODE_SILENT
import com.kidslauncher.mdm.lock.RINGER_MODE_VIBRATE
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

/** The kid's sound row (design 18, decisions after QA): Sound / Silent (vibrate), never Silent. */
class SoundModeTest {

    private val modes = listOf(RINGER_MODE_SILENT, RINGER_MODE_VIBRATE, RINGER_MODE_NORMAL, -1, 7)
    private val filters = listOf(0, INTERRUPTION_FILTER_ALL, INTERRUPTION_FILTER_PRIORITY, INTERRUPTION_FILTER_NONE, INTERRUPTION_FILTER_ALARMS)

    @Test
    fun `the selected choice follows the ringer mode`() {
        assertEquals(
            SoundRowState(SoundChoice.SOUND, enabled = true, note = null),
            soundRowState(RINGER_MODE_NORMAL, INTERRUPTION_FILTER_ALL, hasVibrator = true, policyAccess = true),
        )
        assertEquals(
            SoundRowState(SoundChoice.SILENT_VIBRATE, enabled = true, note = null),
            soundRowState(RINGER_MODE_VIBRATE, INTERRUPTION_FILTER_ALL, hasVibrator = true, policyAccess = true),
        )
        // Normal <-> Vibrate never needs notification-policy access.
        assertEquals(
            SoundRowState(SoundChoice.SOUND, enabled = true, note = null),
            soundRowState(RINGER_MODE_NORMAL, INTERRUPTION_FILTER_ALL, hasVibrator = true, policyAccess = false),
        )
    }

    @Test
    fun `no vibrator, no row - Vibrate would become Silent, i e DND (QA 7)`() {
        for (mode in modes) for (filter in filters) for (access in listOf(true, false)) {
            assertNull(soundRowState(mode, filter, hasVibrator = false, policyAccess = access))
        }
    }

    @Test
    fun `read-only while Do Not Disturb is on, showing what is set`() {
        for (filter in listOf(INTERRUPTION_FILTER_PRIORITY, INTERRUPTION_FILTER_NONE, INTERRUPTION_FILTER_ALARMS)) {
            assertEquals(
                SoundRowState(SoundChoice.SOUND, enabled = false, note = SoundNote.DND_ON),
                soundRowState(RINGER_MODE_NORMAL, filter, hasVibrator = true, policyAccess = true),
            )
            assertEquals(
                SoundRowState(null, enabled = false, note = SoundNote.DND_ON),
                soundRowState(RINGER_MODE_SILENT, filter, hasVibrator = true, policyAccess = true),
            )
        }
        // An unknown filter might hide a DND: read-only too.
        assertEquals(
            SoundRowState(SoundChoice.SILENT_VIBRATE, enabled = false, note = SoundNote.USE_VOLUME_KEYS),
            soundRowState(RINGER_MODE_VIBRATE, 0, hasVibrator = true, policyAccess = true),
        )
    }

    @Test
    fun `fully silent - nothing selected, and leaving it needs policy access (QA 3)`() {
        assertEquals(
            SoundRowState(null, enabled = true, note = SoundNote.FULLY_SILENT),
            soundRowState(RINGER_MODE_SILENT, INTERRUPTION_FILTER_ALL, hasVibrator = true, policyAccess = true),
        )
        // Defensive: without access a tap out of Silent would throw - read-only, the volume keys work.
        assertEquals(
            SoundRowState(null, enabled = false, note = SoundNote.USE_VOLUME_KEYS),
            soundRowState(RINGER_MODE_SILENT, INTERRUPTION_FILTER_ALL, hasVibrator = true, policyAccess = false),
        )
        // An unreadable ringer mode is read-only as well.
        assertEquals(
            SoundRowState(null, enabled = false, note = SoundNote.USE_VOLUME_KEYS),
            soundRowState(-1, INTERRUPTION_FILTER_ALL, hasVibrator = true, policyAccess = true),
        )
    }

    @Test
    fun `a tap sets Normal or Vibrate only - never Silent, never on a read-only or hidden row`() {
        for (mode in modes) for (filter in filters) for (vibrator in listOf(true, false)) for (access in listOf(true, false)) {
            val state = soundRowState(mode, filter, vibrator, access)
            for (choice in SoundChoice.entries) {
                val set = soundTapMode(state, choice)
                assertNotEquals(RINGER_MODE_SILENT, set)
                if (state == null || !state.enabled) assertNull("$state $choice", set)
                if (filter != INTERRUPTION_FILTER_ALL) assertNull("never ends a DND: $state", set)
            }
        }
        val sound = soundRowState(RINGER_MODE_NORMAL, INTERRUPTION_FILTER_ALL, true, true)
        assertNull("already selected", soundTapMode(sound, SoundChoice.SOUND))
        assertEquals(RINGER_MODE_VIBRATE, soundTapMode(sound, SoundChoice.SILENT_VIBRATE))
        val vibrate = soundRowState(RINGER_MODE_VIBRATE, INTERRUPTION_FILTER_ALL, true, true)
        assertEquals(RINGER_MODE_NORMAL, soundTapMode(vibrate, SoundChoice.SOUND))
        val silent = soundRowState(RINGER_MODE_SILENT, INTERRUPTION_FILTER_ALL, true, true)
        assertEquals(RINGER_MODE_NORMAL, soundTapMode(silent, SoundChoice.SOUND))
        assertEquals(RINGER_MODE_VIBRATE, soundTapMode(silent, SoundChoice.SILENT_VIBRATE))
    }

    @Test
    fun `status names are the server's, unknown is null`() {
        assertEquals("normal", ringerModeName(RINGER_MODE_NORMAL))
        assertEquals("vibrate", ringerModeName(RINGER_MODE_VIBRATE))
        assertEquals("silent", ringerModeName(RINGER_MODE_SILENT))
        assertNull(ringerModeName(-1))
        assertEquals("all", interruptionFilterName(INTERRUPTION_FILTER_ALL))
        assertEquals("priority", interruptionFilterName(INTERRUPTION_FILTER_PRIORITY))
        assertEquals("none", interruptionFilterName(INTERRUPTION_FILTER_NONE))
        assertEquals("alarms", interruptionFilterName(INTERRUPTION_FILTER_ALARMS))
        assertNull(interruptionFilterName(0))
    }

    @Test
    fun `each row state says what it is in the log`() {
        val states = modes.flatMap { m -> filters.flatMap { f -> listOf(true, false).map { soundRowState(m, f, it, true) } } }.toSet()
        assertEquals(states.size, states.map { it.describeSoundRow() }.toSet().size)
    }

    private fun code(f: File) = f.readText()
        .replace(Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL), "")
        .replace(Regex("//[^\\n]*"), "")

    /** Design 18: never set DND or an interruption filter; the ringer mode is set in exactly one
     * place (the sound row's tap) - Find My Device's ring no longer touches it (QA #2). */
    @Test
    fun `the launcher never sets DND, and sets the ringer mode only from the sound row`() {
        val root = listOf("src/main/java/com/kidslauncher/mdm", "app/src/main/java/com/kidslauncher/mdm").map(::File).first { it.exists() }
        val sources = root.walk().filter { it.isFile && it.name.endsWith(".kt") }.toList()
        assertTrue(sources.size > 50)
        val ringerSetters = mutableListOf<String>()
        for (file in sources) {
            val text = code(file)
            for (banned in listOf("setInterruptionFilter", "setNotificationPolicy", "addAutomaticZenRule", "setZenMode")) {
                if (text.contains(banned)) fail("${file.name} references $banned")
            }
            if (Regex("""\.notificationPolicy\s*=[^=]""").containsMatchIn(text)) fail("${file.name} sets the notification policy")
            if (Regex("""\.ringerMode\s*=[^=]|setRingerMode\(""").containsMatchIn(text)) ringerSetters += file.name
        }
        assertEquals(listOf("QuickControls.kt"), ringerSetters)
        val locate = code(sources.first { it.name == "LocateCommands.kt" })
        for (stream in listOf("STREAM_RING", "STREAM_NOTIFICATION", "STREAM_MUSIC")) {
            assertTrue("Find My Device raises the alarm stream only: $stream", !locate.contains(stream))
        }
    }
}
