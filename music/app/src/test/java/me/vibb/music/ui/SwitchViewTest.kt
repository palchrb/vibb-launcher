package me.vibb.music.ui

import me.vibb.music.Fixtures.art
import me.vibb.music.Fixtures.done
import me.vibb.music.Fixtures.entry
import me.vibb.music.Fixtures.file
import me.vibb.music.Fixtures.items
import me.vibb.music.Fixtures.library
import me.vibb.music.Fixtures.snapshot
import me.vibb.music.logic.ItemPosition
import me.vibb.music.logic.ItemRef
import me.vibb.music.logic.requestStands
import me.vibb.music.model.Source
import me.vibb.music.playback.Output
import me.vibb.music.playback.OutputKind
import me.vibb.music.playback.PlayerHub
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** What the player shows between a tap and the player's switch: what was tapped, never the old cover. */
class SwitchViewTest {
    private val coverA = "a".repeat(64)
    private val coverB = "b".repeat(64)
    private val a = entry(1).copy(cover = coverA)
    private val b = entry(2).copy(cover = coverB)
    private val snap = snapshot(
        library(listOf(a, b)),
        items(1, listOf("a1", "a2")) + items(2, listOf("b1", "b2", "b3")),
        bookmarks = mapOf(b.key to ("b2" to 60_000L)),
    )
    private val playingA = nowView(snap, PlayerHub.State(live = true, entryId = 1, keys = listOf("a2", "a1"), index = 0, playing = true), null)!!

    @Test
    fun `a cover tap on B shows B's cover and its bookmarked episode at once`() {
        val sw = playSwitch(snap, 2, null, playingA, 1_000)!!
        assertEquals(coverB, sw.view.cover)
        assertEquals("b2", sw.view.item.key)
        assertEquals("Title b2", sw.view.itemTitle)
        assertEquals(60_000L, sw.view.storedPosMs)
        assertTrue(sw.view.playing)
        // Held while the player still says A, dropped once it says B.
        assertTrue(requestStands(sw.request, playingA.ref, 1_100))
        assertFalse(requestStands(sw.request, ItemRef(2, "b2"), 1_200))
    }

    @Test
    fun `a pick from Nytt or search shows that episode`() {
        val sw = playSwitch(snap, 2, "b1", playingA, 1_000)!!
        assertEquals("b1", sw.view.item.key)
        assertEquals(coverB, sw.view.cover)
        assertNull(playSwitch(snap, 2, "gone", playingA, 1_000))
        assertNull(playSwitch(snap, 9, null, playingA, 1_000))
    }

    @Test
    fun `next shows the next track's own art`() {
        val own = entry(3, Source.OWN).copy(cover = coverA)
        val artB = "c".repeat(64)
        val files = listOf(file(31, 3), file(32, 3).copy(art = artB))
        val s = snapshot(library(listOf(own), files), ownCopies = setOf(31, 32))
        val now = nowView(s, PlayerHub.State(live = true, entryId = 3, keys = listOf("f31", "f32"), index = 0, playing = true), null)!!
        assertEquals(coverA, now.cover)
        val sw = skipSwitch(s, now, true, 0, 1_000)!!
        assertEquals("f32", sw.view.item.key)
        assertEquals(artB, sw.view.cover)
        assertTrue(requestStands(sw.request, now.ref, 1_000))
        // Previous well into the track restarts it: nothing to switch to.
        assertNull(skipSwitch(s, now.copy(index = 1), false, 60_000, 1_000))
    }

    @Test
    fun `shuffle picks the track - the entry's cover and no title until the player says`() {
        val own = entry(3, Source.OWN).copy(cover = coverA)
        val files = listOf(file(31, 3).copy(art = "c".repeat(64)), file(32, 3))
        val s = snapshot(library(listOf(own), files), ownCopies = setOf(31, 32), shuffle = setOf(own.key))
        val sw = playSwitch(s, 3, null, null, 1_000)!!
        assertFalse(sw.view.itemKnown)
        assertEquals(coverA, sw.view.cover)
        assertEquals("", sw.view.itemTitle)
        assertTrue(sw.view.shuffled)
    }

    // -- on a Sonos room (design 26a; the merge of main 2026-10-12) --

    /** B with b2 HLS (an RSS entry's HLS: no speaker plays it, `PlayBodies.speakerDropReason`). */
    private val roomSnap = snapshot(
        library(listOf(a, b)),
        items(1, listOf("a1", "a2")) + items(2, listOf("b1", "b2", "b3")).map { if (it.key == "b2") it.copy(hls = true) else it },
        positions = mapOf(b.key to mapOf("b1" to ItemPosition(90_000, false), "b3" to ItemPosition(90_000, false))),
        bookmarks = mapOf(b.key to ("b2" to 60_000L)),
    )

    @Test
    fun `on a room a cover tap shows the item the speaker starts - the next it can play, from 0`() {
        val order = roomSnap.order(b).map { it.key }
        val phone = playSwitch(roomSnap, 2, null, playingA, 1_000)!!
        assertEquals("b2", phone.view.item.key)
        assertEquals(60_000L, phone.view.storedPosMs)
        val room = playSwitch(roomSnap, 2, null, playingA, 1_000, onRoom = true)!!
        val next = order[(order.indexOf("b2") + 1) % order.size]
        assertEquals(next, room.view.item.key)
        assertEquals("a moved start begins at 0 (PlayBodies.speakerPlan)", 0L, room.view.storedPosMs)
        assertFalse("the room's queue leaves b2 out", room.view.queue.any { it.key == "b2" })
        assertNull("a cover tap's request names no item", room.request.itemKey)
        assertFalse(requestStands(room.request, ItemRef(2, next), 1_100))
    }

    @Test
    fun `on a room a pick the speaker can't play shows the next it can, and the request expects that one`() {
        val order = roomSnap.order(b).map { it.key }
        val next = order[(order.indexOf("b2") + 1) % order.size]
        val sw = playSwitch(roomSnap, 2, "b2", playingA, 1_000, onRoom = true)!!
        assertEquals(next, sw.view.item.key)
        assertEquals(next, sw.request.itemKey)
        assertTrue(requestStands(sw.request, playingA.ref, 1_100))
        assertFalse(requestStands(sw.request, ItemRef(2, next), 1_200))
        // nothing a speaker can play: no switch (the service says "Kan ikke spilles på Sonos")
        val own = entry(3, Source.OWN)
        val s = snapshot(library(listOf(own), listOf(file(31, 3))), ownCopies = setOf(31))
        assertNull(playSwitch(s, 3, null, null, 1_000, onRoom = true))
        assertTrue(playSwitch(s, 3, null, null, 1_000) != null)
    }

    @Test
    fun `on a room next counts in the room's queue, and the number agrees with the player's once it is there`() {
        // The service's queue on a room: b2 left out (hub keys = Active.items).
        val keys = roomSnap.order(b).map { it.key }.filter { it != "b2" }
        val hub = PlayerHub.State(live = true, entryId = 2, keys = keys, index = 0, playing = true, output = Output("sonos:RINCON_000E58A0B1C201400", OutputKind.SONOS, "Stua"))
        val now = nowView(roomSnap, hub, null)!!
        val sw = skipSwitch(roomSnap, now, true, 0, 1_000, onRoom = true)!!
        assertEquals(keys[1], sw.view.item.key)
        assertEquals("a step on a playing room starts at 0", 0L, sw.view.storedPosMs)
        val there = nowView(roomSnap, hub.copy(index = 1), null)!!
        assertEquals(there.ref, ItemRef(2, sw.view.item.key))
        assertEquals(playerNumber(roomSnap, there), playerNumber(roomSnap, sw.view))
        // "N av M" counts the play order, b2 too
        assertEquals(roomSnap.order(b).size, playerNumber(roomSnap, sw.view).second)
        // paused, the room starts the item where the phone would (SonosController.stepTo's paused branch)
        val paused = skipSwitch(roomSnap, now.copy(playing = false), true, 0, 1_000, onRoom = true)!!
        assertEquals(90_000L, paused.view.storedPosMs)
        // on the phone the same step resumes the item's place, playing or not
        assertEquals(90_000L, skipSwitch(roomSnap, now, true, 0, 1_000)!!.view.storedPosMs)
    }

    // -- Storytel (design 26b §3.2, §3.5, §5.6; the merge of music-1b-sonos 2026-10-12) --

    private val books = listOf("160661", "160662", "160663", "160664", "160665")
    private val series = entry(7, Source.STORYTEL, target = "storytel:series:26175", cache = -1)

    /** Books 1 and 3 of five on the phone; the bookmark on book 2, heard on a room. */
    private val bookSnap = snapshot(
        library(listOf(a, series)),
        items(1, listOf("a1", "a2")) + items(7, books, gone = books.toSet()),
        downloads = listOf(done(7, "160661", url = "storytel:book:160661"), done(7, "160663", url = "storytel:book:160663")),
        bookmarks = mapOf(series.key to ("160662" to 120_000L)),
    )

    @Test
    fun `a Storytel tap on the phone queues the books on the phone and numbers them in the series`() {
        val sw = playSwitch(bookSnap, 7, "160663", playingA, 1_000)!!
        assertEquals(listOf("160661", "160663"), sw.view.queue.map { it.key })
        assertEquals("Del 3 av 5: the series' reading order (26b §3.5, M5)", 3 to 5, playerNumber(bookSnap, sw.view))
        // the cover tap: the bookmark's book isn't on the phone, so the phone's queue starts elsewhere
        val cover = playSwitch(bookSnap, 7, null, playingA, 1_000)!!
        assertTrue(cover.view.queue.map { it.key }.all { it in setOf("160661", "160663") })
        // once the player is there, its number is the switch's
        val hub = PlayerHub.State(live = true, entryId = 7, keys = listOf("160661", "160663"), index = 1, playing = true)
        val there = nowView(bookSnap, hub, null)!!
        assertEquals(there.ref, sw.view.ref)
        assertEquals(playerNumber(bookSnap, there), playerNumber(bookSnap, sw.view))
    }

    @Test
    fun `a swipe to book 3 of 5 says 3 av 5 on the landing page and after arrival (qa-26a-merge27-code)`() {
        // Books 1 and 3 of five on the phone, each with its own cover (step 4a's art hash), so the
        // player shows the carousel; its pages are the phone's books, its numbers the series'.
        val withArt = snapshot(
            library(listOf(a, series.copy(cover = coverA))),
            items(1, listOf("a1", "a2")) + items(7, books, gone = books.toSet(), art = books.associateWith { art(it) }),
            downloads = listOf(done(7, "160661", url = "storytel:book:160661"), done(7, "160663", url = "storytel:book:160663")),
        )
        val hub = PlayerHub.State(live = true, entryId = 7, keys = listOf("160661", "160663"), index = 0, playing = true)
        val now = nowView(withArt, hub, null)!!
        assertTrue(now.carousel)
        assertEquals(1 to 5, playerNumber(withArt, now))
        // the landing page (PlayerScreen's numberOf = playerNumberOf over the view it shows)
        assertEquals(3, playerNumberOf(withArt, now, now.queue[1]))
        val sw = swipeSwitch(withArt, now, 1, now.ref, null, 1_000)!!
        assertEquals("160663", sw.view.item.key)
        assertEquals(3, playerNumberOf(withArt, sw.view, sw.view.item))
        assertEquals("the switch's view", 3 to 5, playerNumber(withArt, sw.view))
        val there = nowView(withArt, hub.copy(index = 1), null)!!
        assertEquals("after arrival", 3 to 5, playerNumber(withArt, there))
        assertEquals(3, playerNumberOf(withArt, there, there.item))
        // and back: book 1 from book 3's page
        assertEquals(1, playerNumberOf(withArt, there, there.queue[0]))
    }

    @Test
    fun `a Storytel tap on a room reads the room's view - no switch until 4b's filter, as the service's queue`() {
        // The speaker filter drops every Storytel book today (no url), as the service's queue does: no switch,
        // the service says "Kan ikke spilles på Sonos". The view is the room's (forRenderer(true), GuardsTest pins
        // it): with 4b's filter (26b §5.1) the same calls queue the whole series - 4b adds those assertions here
        // (every book in the queue, a cover tap at the bookmark's book 2 at 120 s, "Del 2 av 5", an entry with
        // nothing on the phone still switching on a room).
        assertNull(playSwitch(bookSnap, 7, "160663", playingA, 1_000, onRoom = true))
        assertNull(playSwitch(bookSnap, 7, null, playingA, 1_000, onRoom = true))
        val none = snapshot(library(listOf(series)), items(7, books, gone = books.toSet()))
        assertNull(playSwitch(none, 7, null, null, 1_000))
        assertNull(playSwitch(none, 7, null, null, 1_000, onRoom = true))
        // the phone's view is the phone's whatever snapshot the caller holds
        assertEquals(
            listOf("160661", "160663"),
            playSwitch(bookSnap.forRenderer(true), 7, "160663", playingA, 1_000)!!.view.queue.map { it.key },
        )
        // next on a playing room counts in the room's queue (the hub's keys), from 0; the number follows the series
        val hub = PlayerHub.State(live = true, entryId = 7, keys = books, index = 1, playing = true, output = Output("sonos:RINCON_000E58A0B1C201400", OutputKind.SONOS, "Stua"))
        val now = nowView(bookSnap, hub, null)!!
        assertEquals(2 to 5, playerNumber(bookSnap, now))
        val next = skipSwitch(bookSnap, now, true, 0, 1_000, onRoom = true)!!
        assertEquals("160663", next.view.item.key)
        assertEquals(0L, next.view.storedPosMs)
        assertEquals(3 to 5, playerNumber(bookSnap, next.view))
    }

    // The episode carousel's swipe (design 27 §3.2).

    @Test
    fun `a swipe shows the picked episode at once, with its saved place`() {
        val e = entry(5).copy(cover = coverA)
        val s = snapshot(
            library(listOf(e)),
            items(5, listOf("e1", "e2", "e3"), art = mapOf("e1" to coverB)),
            positions = mapOf(e.key to mapOf("e1" to me.vibb.music.logic.ItemPosition(90_000, false))),
        )
        val now = nowView(s, PlayerHub.State(live = true, entryId = 5, keys = listOf("e3", "e2", "e1"), index = 0, playing = false), null)!!
        assertTrue(now.carousel)
        val sw = swipeSwitch(s, now, 2, now.ref, null, 1_000)!!
        assertEquals("e1", sw.view.item.key)
        assertEquals(coverB, sw.view.cover)
        assertEquals(coverA, sw.view.fallback)
        assertEquals("the seek bar shows its saved place", 90_000L, sw.view.storedPosMs)
        assertFalse("paused stays paused", sw.view.playing)
        assertTrue(requestStands(sw.request, now.ref, 1_100))
        assertNull("the item shown", swipeSwitch(s, now, 0, now.ref, null, 1_000))
        assertNull(swipeSwitch(s, now, 7, now.ref, null, 1_000))
        // A quick second swipe while the first stands: the player passing through e1 doesn't end it.
        val second = swipeSwitch(s, sw.view, 1, now.ref, sw.request, 1_200)!!
        assertTrue(requestStands(second.request, ItemRef(5, "e1"), 1_300))
    }

    @Test
    fun `the pager starts over on a new key list - another queue, or an item dropped mid-play (QA M7 b)`() {
        val e = entry(5)
        val s = snapshot(library(listOf(e)), items(5, listOf("e1", "e2", "e3")))
        val hub = PlayerHub.State(live = true, entryId = 5, keys = listOf("e3", "e2", "e1"), index = 0, playing = true)
        val now = nowView(s, hub, null)!!
        assertEquals("pausing changes nothing", now.pagerKey, nowView(s, hub.copy(playing = false, index = 1), null)!!.pagerKey)
        val dropped = snapshot(library(listOf(e)), items(5, listOf("e1", "e3")))
        assertTrue(now.pagerKey != nowView(dropped, hub, null)!!.pagerKey)
        assertTrue(now.pagerKey != nowView(s, hub.copy(keys = listOf("e1", "e2", "e3")), null)!!.pagerKey)
        assertFalse("no own art: no carousel", now.carousel)
    }

    @Test
    fun `a swipe on a room - the pages are the room's queue, from 0 while it plays, the number counts the play order`() {
        // The merge of design 27 into design 26a: e2 is an RSS entry's HLS (no speaker plays it), so the
        // service's queue on a room - the hub's keys, the carousel's pages - leaves it out.
        val e = entry(5).copy(cover = coverA)
        val rows = items(5, listOf("e1", "e2", "e3"), art = mapOf("e1" to coverB)).map { if (it.key == "e2") it.copy(hls = true) else it }
        val s = snapshot(library(listOf(e)), rows, positions = mapOf(e.key to mapOf("e3" to ItemPosition(90_000, false))))
        val room = Output("sonos:RINCON_000E58A0B1C201400", OutputKind.SONOS, "Stua")
        val hub = PlayerHub.State(live = true, entryId = 5, keys = listOf("e1", "e3"), index = 0, playing = true, output = room)
        val now = nowView(s, hub, null)!!
        assertTrue("own art on a page: the carousel, on a room too", now.carousel)
        val sw = swipeSwitch(s, now, 1, now.ref, null, 1_000, onRoom = true)!!
        assertEquals("e3", sw.view.item.key)
        assertEquals("a swipe on a playing room starts at 0 (stepTo)", 0L, sw.view.storedPosMs)
        assertEquals("paused, where the phone would", 90_000L, swipeSwitch(s, now.copy(playing = false), 1, now.ref, null, 1_000, onRoom = true)!!.view.storedPosMs)
        assertEquals("on the phone: its place", 90_000L, swipeSwitch(s, now, 1, now.ref, null, 1_000)!!.view.storedPosMs)
        // the landing page's "N av M": the play order, e2 counted
        val order = s.order(e).map { it.key }
        assertEquals(3, order.size)
        assertEquals(order.indexOf("e3") + 1, playerNumberOf(s, sw.view, sw.view.item))
        assertEquals(order.indexOf("e3") + 1 to 3, playerNumber(s, sw.view))
        assertEquals(playerNumber(s, nowView(s, hub.copy(index = 1), null)!!), playerNumber(s, sw.view))
        assertEquals(order.indexOf("e1") + 1, playerNumberOf(s, now, now.queue[0]))
    }
}
