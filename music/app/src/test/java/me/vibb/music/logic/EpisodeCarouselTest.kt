package me.vibb.music.logic

import me.vibb.music.Fixtures.art
import me.vibb.music.Fixtures.entry
import me.vibb.music.Fixtures.file
import me.vibb.music.model.PlayItem
import me.vibb.music.model.Source
import me.vibb.music.model.toPlayItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Now Playing carousel's rules (design 27 §3): when it shows, the picture for the session, the
 * pager's skip and follow, and the player's `SKIP_TO`.
 */
class EpisodeCarouselTest {
    private val cover = art("series")
    private fun episodes(n: Int, own: (Int) -> String? = { null }) = (1..n).map { PlayItem(1, "k$it", "Title $it", "https://cdn.example.org/$it.mp3", false, 600_000, artHash = own(it)) }

    // When it shows (the user's decisions; QA H4).

    @Test
    fun `every episode with its own picture - the carousel`() {
        assertTrue(showsCarousel(episodes(10) { art("p$it") }, cover))
    }

    @Test
    fun `a single episode with its own picture among 300 - the carousel, the rest show the series cover`() {
        assertTrue(showsCarousel(episodes(300) { if (it == 150) art("odd") else null }, cover))
    }

    @Test
    fun `no episode with its own picture - today's single cover`() {
        assertFalse(showsCarousel(episodes(300), cover))
        assertFalse("a cover-less feed without art", showsCarousel(episodes(5), null))
    }

    @Test
    fun `an album whose songs all carry one cover - today's single cover`() {
        val album = art("album")
        assertFalse(showsCarousel(episodes(12) { album }, null))
        assertFalse("the parent's cover is the same picture", showsCarousel(episodes(12) { album }, album))
        assertFalse("a parent cover no page shows doesn't count", showsCarousel(episodes(12) { album }, cover))
        assertTrue("one song without art shows the parent's cover: two", showsCarousel(episodes(12) { if (it == 3) null else album }, cover))
    }

    @Test
    fun `own files - a compilation with a cover per track gets it`() {
        val own = entry(3, Source.OWN)
        val tracks = (1L..4L).map { file(it, 3).copy(art = art("t$it")).toPlayItem() }
        assertTrue(showsCarousel(tracks, own.cover))
    }

    @Test
    fun `one item - never`() {
        assertFalse(showsCarousel(episodes(1) { art("x") }, cover))
        assertFalse(showsCarousel(emptyList(), cover))
    }

    // The session's and the launcher's picture (vibb `render_now`).

    @Test
    fun `art or cover - the own picture when the phone has it, else the series cover`() {
        val e = entry(1).copy(cover = cover)
        val own = episodes(1) { art("own") }.single()
        assertEquals(art("own"), artOrCover(own, e) { true })
        assertEquals("not on the phone yet", cover, artOrCover(own, e) { false })
        assertEquals("no picture of its own", cover, artOrCover(episodes(1).single(), e) { true })
        assertNull(artOrCover(episodes(1).single(), entry(1)) { true })
    }

    // The pager (virtual pages as the library's: page p shows index p mod n).

    private val n = 5
    private val start = carouselStartPage(n, 2) // index 2

    @Test
    fun `a settle on another page is one skip there, with its direction`() {
        assertEquals(SwipeSkip(3, true), swipeSkip(start, start + 1, n, 2))
        assertEquals(SwipeSkip(1, false), swipeSkip(start, start - 1, n, 2))
        assertEquals("a fling over three pages is one skip", SwipeSkip(0, true), swipeSkip(start, start + 3, n, 2))
        assertEquals("wrapping back from the first", SwipeSkip(4, false), swipeSkip(carouselStartPage(n, 0), carouselStartPage(n, 0) - 1, n, 0))
        assertNull("back where it was", swipeSkip(start, start, n, 2))
        assertNull("landed on the item shown", swipeSkip(start, start + 1, n, 3))
        assertNull("one item", swipeSkip(0, 1, 1, 0))
    }

    @Test
    fun `the pager follows the player - one page animated, further by a jump, the short way round`() {
        assertEquals(PagerFollow.Stay, pagerFollow(start, 2, n, scrolling = false, holdFrom = null))
        assertEquals("next button", PagerFollow.Animate(start + 1), pagerFollow(start, 3, n, false, null))
        assertEquals("previous", PagerFollow.Animate(start - 1), pagerFollow(start, 1, n, false, null))
        assertEquals("a list pick two away", PagerFollow.Jump(start + 2), pagerFollow(start, 4, n, false, null))
        assertEquals("the wrap: from the last to the first is one page on", PagerFollow.Animate(carouselStartPage(n, 4) + 1), pagerFollow(carouselStartPage(n, 4), 0, n, false, null))
    }

    @Test
    fun `never pulled from under the finger, nor while its own skip is pending`() {
        assertEquals("dragged or flung (QA M7 a)", PagerFollow.Stay, pagerFollow(start + 1, 2, n, scrolling = true, holdFrom = null))
        // It asked for index 3 while 2 showed: as long as 2 shows, it stays on 3.
        assertEquals(PagerFollow.Stay, pagerFollow(start + 1, 2, n, false, holdFrom = 2))
        // The player went elsewhere (an unplayable target skipped on to 4): it follows.
        assertEquals(PagerFollow.Animate(start + 2), pagerFollow(start + 1, 4, n, false, holdFrom = 2))
    }

    // SKIP_TO.

    @Test
    fun `skip to - only for the entry loaded, a key in the queue, not the item playing`() {
        val keys = listOf("k1", "k2", "k3")
        assertEquals(2, swipeTarget(true, keys, 0, "k3"))
        assertNull("a stale pager after Nytt switched entries (QA M7 c)", swipeTarget(false, keys, 0, "k3"))
        assertNull(swipeTarget(true, keys, 0, "gone"))
        assertNull(swipeTarget(true, keys, 2, "k3"))
        // A swipe back is the item before, whatever the position: never previous's restart.
        assertEquals(0, swipeTarget(true, keys, 1, "k1"))
    }

    @Test
    fun `an unplayable target skips on in the swipe's direction`() {
        val playable = setOf(0, 4)
        assertEquals(4, firstPlayable(5, 2, forward = true, wrap = true) { it in playable })
        assertEquals(0, firstPlayable(5, 2, forward = false, wrap = true) { it in playable })
        assertEquals(2, firstPlayable(5, 2, forward = true, wrap = true) { true })
        assertNull(firstPlayable(5, 2, forward = true, wrap = true) { false })
        assertNull("without wrap it stops at the end", firstPlayable(5, 3, forward = true, wrap = false) { it == 0 })
    }

    // The session's picture after the queue was built (code QA L7, L8).

    @Test
    fun `a pruned small copy is made again, and meanwhile the series cover shows`() {
        val e = entry(1).copy(cover = cover)
        val own = episodes(1) { art("own") }.single()
        val copies = mutableSetOf(cover)
        assertEquals("the copy went: remade", listOf(art("own")), smallArtMissing(listOf(own.artHash, cover)) { it in copies })
        assertEquals("meanwhile the series cover, never nothing", cover, artOrCover(own, e) { it in copies })
        copies += art("own")
        assertEquals(emptyList<String>(), smallArtMissing(listOf(own.artHash, cover, null)) { it in copies })
    }

    @Test
    fun `a listing applied during the queue gives the queued item its picture`() {
        val queue = episodes(3)
        val updated = withItemArt(queue, "k2", art("late"))
        assertEquals(art("late"), updated[1].artHash)
        assertEquals(queue[0], updated[0])
        assertEquals(queue, withItemArt(queue, "k2", null))
        val service = me.vibb.music.Fixtures.moduleFile("src/main/java/me/vibb/music/playback/PlaybackService.kt").readText()
        assertTrue(service.contains("store.db.itemArt(a.entry.id, queued.key)"))
    }

    @Test
    fun `two restores in a row load the queue once (code QA L10)`() {
        val service = me.vibb.music.Fixtures.moduleFile("src/main/java/me/vibb/music/playback/PlaybackService.kt").readText()
        // Since the merge into design 26a's branch every custom command runs under `switching`, one
        // at a time - RESTORE (with THEN_SKIP_TO) and SKIP_TO among them - which replaced main's own lock.
        val command = service.substringAfter("override fun onCustomCommand(").substringBefore("return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))")
        val locked = command.substringAfter("switching.withLock {")
        assertTrue(locked.contains("Commands.RESTORE -> restoreThen(args.getString(Commands.ARG_THEN), args.getLong(Commands.ARG_POSITION), args)"))
        assertTrue(locked.contains("Commands.SKIP_TO -> skipTo(args)"))
        assertTrue(service.contains("if (active == null || renderer.itemCount() == 0) {"))
    }
}
