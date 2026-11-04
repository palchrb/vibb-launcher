package com.kidslauncher.mdm.ui.kidsettings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** The kid's ringtone and message sound (SoundPicks.kt): the rows' state, the picker's list and its tap rule. */
class SoundPicksTest {

    private val bell = PickerSound("content://media/internal/audio/media/34?title=Classic%20Bell&canonical=1", "Classic Bell")
    private val luna = PickerSound("content://media/internal/audio/media/40?title=Luna&canonical=1", "Luna")
    private val shared = PickerSound("content://media/external_primary/audio/media/7?title=Mamma&canonical=1", "Mamma")

    @Test
    fun `the rows come with the sound bit, writable only with the app-op - the ringer mode is no input`() {
        assertNull(soundPicksState(wanted = false, canWrite = true))
        assertEquals(SoundPicksState(writable = true), soundPicksState(wanted = true, canWrite = true))
        assertEquals(SoundPicksState(writable = false), soundPicksState(wanted = true, canWrite = false))
    }

    @Test
    fun `a row's value - the title, a canonical URI's title, none set, or unnamed`() {
        assertEquals(SoundValue.Named("Classic Bell"), soundValue(bell.uri, "Classic Bell"))
        assertEquals(SoundValue.Named("Classic Bell"), soundValue(bell.uri, null))
        assertEquals(SoundValue.NoSound, soundValue(null, null))
        assertEquals(SoundValue.NoSound, soundValue("", "x"))
        assertEquals(SoundValue.Unnamed, soundValue("content://media/internal/audio/media/34", null))
    }

    @Test
    fun `the picker lists the built-in sounds once, checks the phone's, and never offers None`() {
        val listed = listOf(bell, shared, luna, bell.copy(uri = "content://media/internal/audio/media/34"), PickerSound("", "Ingen"))
        val rows = pickerRows(listed, lead = null, current = "content://0@media/internal/audio/media/40")
        assertEquals(listOf(bell, luna), rows.map { it.sound })
        assertEquals(listOf(false, true), rows.map { it.checked })
        // None set: nothing checked, and still no "None" row.
        val none = pickerRows(listed, lead = null, current = null)
        assertEquals(listOf(bell, luna), none.map { it.sound })
        assertTrue(none.none { it.checked || it.sound.uri.isBlank() })
    }

    @Test
    fun `a parent's sound from a file leads the list while the picker is open`() {
        val lead = leadSound(listOf(bell, luna), shared.uri, "Mamma")
        assertEquals(shared, lead)
        assertNull(leadSound(listOf(bell, luna), luna.uri, "Luna"))
        assertNull(leadSound(listOf(bell, luna), null, null))
        assertNull(leadSound(listOf(bell, luna), "content://media/external_primary/audio/media/9", null))
        // The kid picks Luna: the parent's sound stays, unchecked.
        val rows = pickerRows(listOf(bell, luna), lead, luna.uri)
        assertEquals(listOf(shared, bell, luna), rows.map { it.sound })
        assertEquals(listOf(false, false, true), rows.map { it.checked })
    }

    @Test
    fun `a tap is always heard and written only when it isn't the phone's sound`() {
        assertFalse(pickerTapWrites("content://media/internal/audio/media/34", bell.uri))
        assertTrue(pickerTapWrites(bell.uri, luna.uri))
        assertTrue(pickerTapWrites(null, bell.uri))
        assertTrue(isBuiltInSound("content://0@media/internal/audio/media/1"))
        assertFalse(isBuiltInSound(shared.uri))
        assertFalse(isBuiltInSound("file:///sdcard/Ringtones/a.ogg"))
    }

    private fun code(path: String) = listOf(File("src/main/java/com/kidslauncher/mdm/$path"), File("app/src/main/java/com/kidslauncher/mdm/$path"))
        .first { it.exists() }.readText()
        .replace(Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL), "").replace(Regex("//[^\\n]*"), "")

    @Test
    fun `the sounds are written only by KidSounds, on IO, one at a time - and no vibration key`() {
        val sounds = code("ui/kidsettings/KidSounds.kt")
        assertEquals(1, Regex("""setActualDefaultRingtoneUri\(""").findAll(sounds).count())
        val set = sounds.substringAfter("suspend fun set(").substringBefore("class Preview")
        assertTrue(set.contains("withContext(Dispatchers.IO)") && set.contains("writes.withLock"))
        // The preview is media, so it plays while the phone is in Lydløs.
        assertTrue(sounds.contains("setUsage(AudioAttributes.USAGE_MEDIA)"))
        for (path in listOf("ui/kidsettings/KidSettingsActivity.kt", "ui/kidsettings/KidSoundPickerActivity.kt", "server/QuickControls.kt")) {
            assertFalse(path, code(path).contains("setActualDefaultRingtoneUri"))
        }
        for (key in listOf("VIBRATE_WHEN_RINGING", "VIBRATION_INTENSITY", "ACTION_RINGTONE_PICKER")) {
            assertFalse(key, sounds.contains(key) || code("ui/kidsettings/KidSoundPickerActivity.kt").contains(key))
        }
        // Leaving stops the sound.
        assertTrue(code("ui/kidsettings/KidSoundPickerActivity.kt").substringAfter("override fun onStop()").contains("preview.stop()"))
    }

    @Test
    fun `the picker is not exported`() {
        val manifest = listOf(File("src/main/AndroidManifest.xml"), File("app/src/main/AndroidManifest.xml")).first { it.exists() }.readText()
        val entry = manifest.substringAfter("android:name=\".ui.kidsettings.KidSoundPickerActivity\"").substringBefore("/>")
        assertTrue(entry.contains("android:exported=\"false\""))
        assertFalse(entry.contains("intent-filter"))
    }
}
