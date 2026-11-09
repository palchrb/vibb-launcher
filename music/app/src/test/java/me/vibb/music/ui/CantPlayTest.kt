package me.vibb.music.ui

import me.vibb.music.R
import me.vibb.music.playback.OutputKind
import me.vibb.music.playback.RendererState
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * "Kan ikke spilles nå" on the player's cover (2026-11-09, mockup D; qa-music-cant-play): which states get the notice
 * and its words, which keep the quiet line, when play is greyed, the refusal held over a retry, what fits a box, when
 * the line under the entry name comes back, the carousel's page, and the greyed cover's colours. The cover's fit with
 * the real words is FitTest's.
 */
class CantPlayTest {
    @Test
    fun `the item's refusals get the notice, play greyed only where a tap can't help`() {
        val expected = mapOf(
            RendererState.SPOTIFY_ASK_ADULT to CantPlayNotice(R.string.cant_play_now, R.string.cant_play_ask_adult, true),
            RendererState.SPOTIFY_NO_LISTENING_TIME to CantPlayNotice(R.string.renderer_spotify_no_listening_time, R.string.cant_play_ask_adult, true),
            RendererState.SPOTIFY_NOT_ON_PHONE to CantPlayNotice(R.string.renderer_spotify_not_on_phone, R.string.cant_play_ask_adult, true),
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
        for ((state, notice) in expected) {
            assertEquals(state.name, notice, cantPlayNotice(state))
            assertEquals(state.name, notice.playGreyed, playOff(cantPlayNotice(state)))
        }
        for (state in line) assertNull(state.name, cantPlayNotice(state))
        assertNull(cantPlayNotice(null))
        assertFalse(playOff(null))
        // never a room's state: design 50 §1.4 dims nothing on a room
        for (state in expected.keys) assertEquals(state.name, false, state.remote)
    }

    /** M3: a tap on a refused item keeps the notice over its attempt, until the attempt plays or ends otherwise. */
    @Test
    fun `a refusal is held over its retry and ends with a play, another line or another run`() {
        val ask = RendererState.SPOTIFY_ASK_ADULT
        var held = holdRefusal(null, ask, webStarting = false, run = 6)
        assertEquals(HeldRefusal(ask, 6), held)
        // the tap: the web player clears its line and starts - the cover stays greyed
        held = holdRefusal(held, null, webStarting = true, run = 6)
        assertEquals(ask, shownState(held, null))
        // refused again: still the same notice, no flash in between
        held = holdRefusal(held, ask, webStarting = false, run = 6)
        assertEquals(ask, shownState(held, ask))
        // it plays after all (the adult fixed the account): the start is over, the line gone
        held = holdRefusal(held, null, webStarting = true, run = 6)
        held = holdRefusal(held, null, webStarting = false, run = 6)
        assertNull(held)
        assertNull(shownState(held, null))
        // the book's refusal and the phone without Widevine (2026-11-09) are held the same way
        val book = RendererState.SPOTIFY_NO_LISTENING_TIME
        val phone = RendererState.SPOTIFY_NOT_ON_PHONE
        for (state in listOf(book, phone)) {
            assertEquals(state.name, HeldRefusal(state, 6), holdRefusal(null, state, webStarting = false, run = 6))
            assertEquals(state.name, state, shownState(holdRefusal(HeldRefusal(state, 6), null, webStarting = true, run = 6), null))
        }
        // V2: only those that grey play - the network lines come before any start and are never held; a stale
        // "Trenger Wi-Fi" never stays over a good retry
        val notHeld = RendererState.entries.filter { cantPlayNotice(it) != null } - setOf(ask, book, phone)
        assertEquals(
            setOf(RendererState.SPOTIFY_NEEDS_WIFI, RendererState.SPOTIFY_NEEDS_INTERNET, RendererState.SPOTIFY_UNAVAILABLE),
            notHeld.toSet(),
        )
        for (state in notHeld) assertNull(state.name, holdRefusal(null, state, webStarting = false, run = 6))
        assertNull(holdRefusal(HeldRefusal(ask, 6), RendererState.SPOTIFY_NEEDS_WIFI, false, 6))
        // another line ends the hold
        assertNull(holdRefusal(HeldRefusal(ask, 6), RendererState.SPOTIFY_ELSEWHERE, false, 6))
        assertNull(holdRefusal(HeldRefusal(ask, 6), RendererState.UNREACHABLE, true, 6))
        // another run (a new entry or the favourites) never inherits it
        assertNull(holdRefusal(HeldRefusal(ask, 6), null, webStarting = true, run = 7))
        // nothing held without a refusal first
        assertNull(holdRefusal(null, null, webStarting = true, run = 6))
        // V1: a Sonos room's connect is not the web player's start - the hold ends when the kid picks a room
        assertTrue(webPlayerStarting(connecting = true, output = OutputKind.PHONE))
        assertTrue(webPlayerStarting(connecting = true, output = OutputKind.BLUETOOTH))
        assertFalse(webPlayerStarting(connecting = true, output = OutputKind.SONOS))
        assertFalse(webPlayerStarting(connecting = false, output = OutputKind.PHONE))
        assertNull(holdRefusal(HeldRefusal(ask, 6), null, webPlayerStarting(true, OutputKind.SONOS), 6))
    }

    /** N2: greyed play shows play, also while a held retry's attempt runs ([NowView.playing] follows it). */
    @Test
    fun `greyed play keeps the play glyph`() {
        val ask = cantPlayNotice(RendererState.SPOTIFY_ASK_ADULT)
        val wifi = cantPlayNotice(RendererState.SPOTIFY_NEEDS_WIFI)
        assertFalse(showsPause(playing = true, notice = ask))
        assertFalse(showsPause(playing = false, notice = ask))
        assertFalse(showsPause(playing = true, notice = cantPlayNotice(RendererState.SPOTIFY_NOT_ON_PHONE)))
        assertTrue(showsPause(playing = true, notice = wifi))
        assertTrue(showsPause(playing = true, notice = null))
        assertFalse(showsPause(playing = false, notice = null))
    }

    @Test
    fun `what fits the box - the disc goes first, then the help line, then the title`() {
        val title = 20.5f
        val help = 16.4f
        // all: 34 + 4 + 20.5 + 4 + 16.4 = 78.9
        assertEquals(NoticeFit(disc = true, title = true, help = true), noticeFit(79f, title, help))
        assertEquals(NoticeFit(disc = false, title = true, help = true), noticeFit(78f, title, help))
        assertEquals(NoticeFit(disc = false, title = true, help = false), noticeFit(40f, title, help))
        // no help line: the disc stays with the title while both fit
        assertEquals(NoticeFit(disc = true, title = true, help = false), noticeFit(60f, title, null))
        assertEquals(NoticeFit(disc = false, title = true, help = false), noticeFit(50f, title, null))
        // a title that needs more than its lines, or more than the box: the "!" alone
        assertEquals(NoticeFit(disc = true, title = false, help = false), noticeFit(200f, Float.POSITIVE_INFINITY, help))
        assertEquals(NoticeFit(disc = false, title = false, help = false), noticeFit(20f, title, null))
        // a help line that needs more than two lines goes; the disc stays with the title
        assertEquals(NoticeFit(disc = true, title = true, help = false), noticeFit(200f, title, Float.POSITIVE_INFINITY))
        assertFalse(noticeFit(200f, title, Float.POSITIVE_INFINITY).complete(hasHelp = true))
    }

    @Test
    fun `the line under the entry name comes back only while the notice can't say it all`() {
        val ask = RendererState.SPOTIFY_ASK_ADULT
        val book = RendererState.SPOTIFY_NO_LISTENING_TIME
        val all = NoticeFit(disc = true, title = true, help = true)
        val titleOnly = NoticeFit(disc = false, title = true, help = false)
        val mark = NoticeFit(disc = true, title = false, help = false)
        val wifi = RendererState.SPOTIFY_NEEDS_WIFI
        val phone = RendererState.SPOTIFY_NOT_ON_PHONE
        assertNull(headerLineText(null, null, null))
        // no notice: today's line
        assertEquals(R.string.renderer_taken_over, headerLineText(RendererState.TAKEN_OVER, null, null))
        // the notice says it all, or hasn't been measured yet: once only
        assertNull(headerLineText(ask, cantPlayNotice(ask), all))
        assertNull(headerLineText(ask, cantPlayNotice(ask), null))
        // the help line gone: "Spør en voksen" comes back under "Kan ikke spilles nå"
        assertEquals(R.string.renderer_spotify_ask_adult, headerLineText(ask, cantPlayNotice(ask), titleOnly))
        // ... and where the state's line is the title itself, the help comes back (N4: never lost)
        assertEquals(R.string.cant_play_ask_adult, headerLineText(book, cantPlayNotice(book), titleOnly))
        assertEquals(R.string.cant_play_ask_adult, headerLineText(phone, cantPlayNotice(phone), titleOnly))
        // the title gone: the state's line
        assertEquals(R.string.renderer_spotify_no_listening_time, headerLineText(book, cantPlayNotice(book), mark))
        assertEquals(R.string.renderer_spotify_needs_wifi, headerLineText(wifi, cantPlayNotice(wifi), mark))
        // no help to lose: the title says it all
        assertNull(headerLineText(wifi, cantPlayNotice(wifi), titleOnly))
    }

    /** L2: the page the pager rests on, while it shows the loaded item - one page, also with two items. */
    @Test
    fun `the carousel greys the resting page of the loaded item only`() {
        // two items: pages 10 and 12 are both item 0, the pager rests on 10
        assertTrue(noticePage(10, settledPage = 10, n = 2, index = 0))
        assertFalse(noticePage(12, settledPage = 10, n = 2, index = 0))
        assertFalse(noticePage(11, settledPage = 10, n = 2, index = 0))
        // a swipe settled on the next page before the player got there: no notice on it
        assertFalse(noticePage(11, settledPage = 11, n = 2, index = 0))
        assertTrue(noticePage(11, settledPage = 11, n = 2, index = 1))
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
}
