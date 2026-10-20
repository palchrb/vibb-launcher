package me.vibb.music.playback

import androidx.media3.common.Player
import me.vibb.music.Fixtures.entry
import me.vibb.music.Fixtures.library
import me.vibb.music.Fixtures.moduleFile
import me.vibb.music.Fixtures.snapshot
import me.vibb.music.logic.Repeat
import me.vibb.music.model.Source
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Design 49 §1.3-§1.4: only the app's own commands change shuffle and repeat (`KidPlayer` refuses the Player's set-mode
 * commands from the launcher, Bluetooth and the notification, so Room stays the truth), every queue hands its modes to
 * the renderer, and the phone's ExoPlayer repeats by its own repeat mode. `KidPlayer` is the service's inner class: its
 * source is read, as GuardsTest reads it.
 */
class KidPlayerTest {
    private fun code(path: String) = moduleFile(path).readText()
        .replace(Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL), "")
        .replace(Regex("//[^\\n]*"), "")

    private val service by lazy { code("src/main/java/me/vibb/music/playback/PlaybackService.kt") }

    @Test
    fun `both Player set-mode commands are refused - not offered, and a call does nothing`() {
        val kid = service.substringAfter("private inner class KidPlayer").substringBefore("private val callback")
        assertTrue(kid.contains(".removeAll(Player.COMMAND_SET_REPEAT_MODE, Player.COMMAND_SET_SHUFFLE_MODE)"))
        assertTrue(kid.contains("private val refused = listOf(Player.COMMAND_SET_REPEAT_MODE, Player.COMMAND_SET_SHUFFLE_MODE)"))
        assertTrue(kid.contains("override fun isCommandAvailable(command: Int): Boolean = command !in refused &&"))
        assertTrue(kid.contains("override fun setRepeatMode(repeatMode: Int) = Unit"))
        assertTrue(kid.contains("override fun setShuffleModeEnabled(shuffleModeEnabled: Boolean) = Unit"))
    }

    @Test
    fun `every queue hands its shuffle and repeat to the renderer, and the toggles are the app's commands`() {
        val loads = Regex("\\bactive = a\\n").findAll(service).count()
        assertEquals(3, loads)
        assertEquals("each one followed by applyModes", loads, Regex("\\bactive = a\\n\\s*applyModes\\(a\\)").findAll(service).count())
        assertTrue(service.contains("Commands.REPEAT -> toggleRepeat()"))
        assertTrue(service.contains("Commands.THEN_REPEAT -> toggleRepeat()"))
        assertTrue(service.contains("store.db.upsertRepeat(RepeatRow(entryKey, next.word))"))
        assertTrue("the move-on wraps only through moveOnIndex", service.split("moveOnFrom(a, index)").size == 3)
    }

    @Test
    fun `the phone's player repeats by ExoPlayer's repeat mode, set from the entry`() {
        assertEquals(Player.REPEAT_MODE_OFF, exoRepeatMode(Repeat.OFF))
        assertEquals(Player.REPEAT_MODE_ALL, exoRepeatMode(Repeat.ALL))
        assertEquals(Player.REPEAT_MODE_ONE, exoRepeatMode(Repeat.ONE))
        val local = code("src/main/java/me/vibb/music/playback/LocalRenderer.kt")
        assertTrue(local.contains("exo.repeatMode = exoRepeatMode(repeat)"))
        assertTrue("ExoPlayer's shuffle is never ours", !local.contains("shuffleModeEnabled"))
        // the entry's mode comes from Room through the snapshot, off where the entry has no repeat to offer
        val own = entry(1, Source.OWN, resume = false)
        val podcast = entry(2, Source.RSS)
        val snap = snapshot(library(listOf(own, podcast))).copy(repeat = mapOf(own.key to Repeat.ONE, podcast.key to Repeat.ALL))
        assertEquals(Repeat.ONE, snap.repeatOf(own))
        assertEquals("a podcast has the skip pair, never repeat", Repeat.OFF, snap.repeatOf(podcast))
    }
}
