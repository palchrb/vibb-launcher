package me.vibb.music.ui

import me.vibb.music.Fixtures.entry
import me.vibb.music.Fixtures.file
import me.vibb.music.Fixtures.items
import me.vibb.music.Fixtures.library
import me.vibb.music.Fixtures.snapshot
import me.vibb.music.logic.ItemRef
import me.vibb.music.logic.requestStands
import me.vibb.music.model.Source
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
}
