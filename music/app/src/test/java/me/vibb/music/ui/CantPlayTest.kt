package me.vibb.music.ui

import me.vibb.music.Fixtures.moduleFile
import me.vibb.music.R
import me.vibb.music.playback.RendererState
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * "Kan ikke spilles nå" on the player's cover (2026-11-09, mockup D): which states get the notice and its words, which
 * keep the quiet line, when play is greyed, and the greyed cover's colours.
 */
class CantPlayTest {
    @Test
    fun `the item's refusals get the notice, play greyed only where a tap can't help`() {
        val expected = mapOf(
            RendererState.SPOTIFY_ASK_ADULT to CantPlayNotice(R.string.cant_play_now, R.string.cant_play_ask_adult, true),
            RendererState.SPOTIFY_NO_LISTENING_TIME to CantPlayNotice(R.string.renderer_spotify_no_listening_time, R.string.cant_play_ask_adult, true),
            RendererState.SPOTIFY_NOT_ON_PHONE to CantPlayNotice(R.string.renderer_spotify_not_on_phone, R.string.cant_play_ask_adult, false),
            RendererState.SPOTIFY_NEEDS_WIFI to CantPlayNotice(R.string.renderer_spotify_needs_wifi, null, false),
            RendererState.SPOTIFY_NEEDS_INTERNET to CantPlayNotice(R.string.renderer_spotify_needs_internet, null, false),
            RendererState.SPOTIFY_UNAVAILABLE to CantPlayNotice(R.string.renderer_spotify_unavailable, null, false),
        )
        // every other state keeps today's line: a room's four, the account elsewhere, the stopped web player
        val line = setOf(
            RendererState.GROUPED_AWAY, RendererState.TAKEN_OVER, RendererState.LOST_SESSION, RendererState.UNREACHABLE,
            RendererState.SPOTIFY_ELSEWHERE, RendererState.SPOTIFY_STOPPED,
        )
        assertEquals(RendererState.entries.toSet(), expected.keys + line)
        for ((state, notice) in expected) assertEquals(state.name, notice, cantPlayNotice(state))
        for (state in line) assertNull(state.name, cantPlayNotice(state))
        assertNull(cantPlayNotice(null))
        // never a room's state: design 50 §1.4 dims nothing on a room
        for (state in expected.keys) assertEquals(state.name, false, state.remote)
    }

    @Test
    fun `the cover greys as the mockup's grayscale, 0,28 brightness and 0,8 contrast`() {
        assertEquals(0.324f, coverGreyLevel(1f, 1f, 1f), 1e-5f)
        assertEquals(0.1f, coverGreyLevel(0f, 0f, 0f), 1e-5f)
        // the matrix is the level, on all three channels, alpha kept (Android's translation in 0..255)
        val m = coverGreyMatrix()
        val (r, g, b) = Triple(0.94f, 0.66f, 0.52f)
        for (row in 0..2) {
            val out = (m[row * 5] * r + m[row * 5 + 1] * g + m[row * 5 + 2] * b) + m[row * 5 + 4] / 255f
            assertEquals(coverGreyLevel(r, g, b), out, 1e-5f)
        }
        assertArrayEquals(floatArrayOf(0f, 0f, 0f, 1f, 0f), m.copyOfRange(15, 20), 0f)
        val grey = coverGrey(Palette.peach.copy(alpha = 0.5f))
        assertEquals(grey.red, grey.blue, 1e-5f)
        assertEquals(0.5f, grey.alpha, 1e-2f)
    }

    /** The player wires it: the line only without a notice, the cover and play read it (a source check, no Compose test here). */
    @Test
    fun `the player shows the notice in the line's place and keeps every button`() {
        val screen = moduleFile("src/main/java/me/vibb/music/ui/PlayerScreen.kt").readText()
        assertTrue("the line gives way to the notice", screen.contains("rendererState?.takeIf { notice == null }"))
        assertTrue("the cover gets the notice", screen.contains("PlayerArt(now, notice,"))
        assertTrue("play greyed by the notice's rule", screen.contains("notice?.takeIf { it.playGreyed }"))
        // play is still a button with the same action: a visual change only
        assertTrue("play keeps its tap", screen.contains("onTogglePlay, row.play.dp, 32.dp,"))
        // the carousel greys the loaded item's pages only
        val carousel = moduleFile("src/main/java/me/vibb/music/ui/EpisodeCarousel.kt").readText()
        assertTrue(carousel.contains("notice?.takeIf { carouselIndex(page, n) == now.index }"))
    }
}
