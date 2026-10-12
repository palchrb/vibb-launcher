package me.vibb.music.sonos

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** Design 26a §5.5: the parent's room scope - the sheet's rows, the refusal, the stop at a sync. */
class RoomScopeTest {
    private val stua = "RINCON_000E58A0B1C201400"
    private val kjokken = "RINCON_000E58A0B1C301400"
    private val bad = "RINCON_000E58A0B1C401400"
    private val loft = "RINCON_000E58A0B1C501400"

    /** Kjøkken + Stua grouped with Kjøkken the coordinator; Bad and Loft alone. */
    private val doc = SpeakerDoc(
        players = mapOf(
            stua to CachedPlayer("192.168.1.20", name = "Stua"),
            kjokken to CachedPlayer("192.168.1.21", name = "Kjøkken"),
            bad to CachedPlayer("192.168.1.22", name = "Bad"),
            loft to CachedPlayer("192.168.1.23", name = "Loft"),
        ),
        groups = listOf(CachedGroup(kjokken, listOf(kjokken, stua)), CachedGroup(bad, listOf(bad)), CachedGroup(loft, listOf(loft))),
    )
    private val view = RoomsView(SpeakerCache.rooms(doc), stale = true, scanning = false)

    @Test
    fun `every room by default - the sheet as it was`() {
        assertEquals(listOf("Kjøkken + Stua", "Bad", "Loft"), view.rooms.map { it.label })
        assertSame(view, allowedRooms(view, null))
        assertTrue(roomAllowed(null, stua))
    }

    @Test
    fun `the sheet lists only allowed rooms, a group only by its coordinator`() {
        val coordinator = allowedRooms(view, listOf(kjokken, bad))
        assertEquals(listOf("Kjøkken + Stua", "Bad"), coordinator.rooms.map { it.label })
        assertTrue("the view's state lines stay", coordinator.stale)
        // a member alone doesn't bring its group's row: the row targets the coordinator
        assertEquals(listOf("Loft"), allowedRooms(view, listOf(stua, loft)).rooms.map { it.label })
        assertEquals(emptyList<Room>(), allowedRooms(view, emptyList()).rooms)
    }

    @Test
    fun `a room outside the scope is refused`() {
        assertTrue(roomAllowed(listOf(stua), stua))
        assertFalse(roomAllowed(listOf(stua), kjokken))
        assertFalse("none allowed", roomAllowed(emptyList(), stua))
    }

    @Test
    fun `a session on a room the scope dropped leaves it at the next sync`() {
        assertFalse("the phone renders", mustLeaveRoom(null, emptyList()))
        assertFalse("every room", mustLeaveRoom(stua, null))
        assertFalse("still allowed", mustLeaveRoom(stua, listOf(stua, kjokken)))
        assertTrue(mustLeaveRoom(stua, listOf(kjokken)))
        assertTrue(mustLeaveRoom(stua, emptyList()))
    }
}
