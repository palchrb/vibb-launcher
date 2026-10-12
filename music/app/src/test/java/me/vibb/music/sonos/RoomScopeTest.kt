package me.vibb.music.sonos

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Design 26a §5.5: the parent's room scope - the sheet's rows, the refusal, the leave. A group needs
 * every member allowed (qa-26a-step3c-code H1, the main session's decision).
 */
class RoomScopeTest {
    private val stua = "RINCON_000E58A0B1C201400"
    private val kjokken = "RINCON_000E58A0B1C301400"
    private val bad = "RINCON_000E58A0B1C401400"
    private val loft = "RINCON_000E58A0B1C501400"

    private val players = mapOf(
        stua to CachedPlayer("192.168.1.20", name = "Stua"),
        kjokken to CachedPlayer("192.168.1.21", name = "Kjøkken"),
        bad to CachedPlayer("192.168.1.22", name = "Bad"),
        loft to CachedPlayer("192.168.1.23", name = "Loft"),
    )

    /** [coordinator] + [member] grouped; Bad and Loft alone. */
    private fun household(coordinator: String, member: String): List<Room> = SpeakerCache.rooms(
        SpeakerDoc(players, listOf(CachedGroup(coordinator, listOf(coordinator, member)), CachedGroup(bad, listOf(bad)), CachedGroup(loft, listOf(loft)))),
    )

    private val kjokkenLeads = household(kjokken, stua)
    private val stuaLeads = household(stua, kjokken)
    private fun view(rooms: List<Room>) = RoomsView(rooms, stale = true, scanning = false)

    @Test
    fun `every room by default - the sheet as it was`() {
        assertEquals(listOf("Kjøkken + Stua", "Bad", "Loft"), kjokkenLeads.map { it.label })
        assertEquals(listOf(kjokken, stua), kjokkenLeads[0].members)
        assertEquals(listOf(bad), kjokkenLeads[1].members)
        val v = view(kjokkenLeads)
        assertSame(v, allowedRooms(v, null))
        assertTrue(groupAllowed(null, kjokkenLeads, kjokken))
    }

    @Test
    fun `a group row needs every member allowed, whichever leads it`() {
        // the H1 scenario: only Stua allowed, Stua + Kjøkken grouped with Stua leading - no row, refused
        val onlyStua = listOf(stua, bad, loft)
        assertEquals(listOf("Bad", "Loft"), allowedRooms(view(stuaLeads), onlyStua).rooms.map { it.label })
        assertFalse(groupAllowed(onlyStua, stuaLeads, stua))
        // the same with Kjøkken leading
        assertEquals(listOf("Bad", "Loft"), allowedRooms(view(kjokkenLeads), onlyStua).rooms.map { it.label })
        assertFalse(groupAllowed(onlyStua, kjokkenLeads, kjokken))
        // Kjøkken leading, Stua forbidden: the coordinator alone isn't enough
        val noStua = listOf(kjokken, bad)
        assertEquals(listOf("Bad"), allowedRooms(view(kjokkenLeads), noStua).rooms.map { it.label })
        assertFalse(groupAllowed(noStua, kjokkenLeads, kjokken))
        // both allowed: the group row stays, the view's state lines too
        val both = allowedRooms(view(kjokkenLeads), listOf(kjokken, stua))
        assertEquals(listOf("Kjøkken + Stua"), both.rooms.map { it.label })
        assertTrue(both.stale)
        assertEquals(emptyList<Room>(), allowedRooms(view(kjokkenLeads), emptyList()).rooms)
    }

    @Test
    fun `a room outside the scope is refused - alone as the household groups it, or by its uid when unknown`() {
        assertTrue(groupAllowed(listOf(bad), kjokkenLeads, bad))
        assertFalse(groupAllowed(listOf(stua), kjokkenLeads, bad))
        assertFalse("none allowed", groupAllowed(emptyList(), kjokkenLeads, bad))
        // a uid with no row (the cache doesn't know it, or a member grouped away): its uid alone
        assertEquals(listOf("RINCON_000E58A0B1C901400"), roomMembers(kjokkenLeads, "RINCON_000E58A0B1C901400"))
        assertTrue(groupAllowed(listOf(stua), kjokkenLeads, stua))
        assertTrue(roomAllowed(listOf(stua), stua))
        assertFalse(roomAllowed(listOf(stua), kjokken))
    }

    @Test
    fun `a session leaves a room the scope or the household's grouping no longer allows`() {
        assertFalse("the phone renders", mustLeaveRoom(null, kjokkenLeads, emptyList()))
        assertFalse("every room", mustLeaveRoom(stua, stuaLeads, null))
        assertFalse("still allowed", mustLeaveRoom(bad, kjokkenLeads, listOf(bad)))
        assertTrue("dropped at a sync", mustLeaveRoom(bad, kjokkenLeads, listOf(stua)))
        // M1: the session on Stua (allowed) and an adult groups the forbidden Kjøkken into it
        val alone = household(loft, bad) // Stua and Kjøkken solo
        assertFalse(mustLeaveRoom(stua, alone, listOf(stua)))
        assertTrue(mustLeaveRoom(stua, stuaLeads, listOf(stua)))
        // M1: Stua leaves, the stream migrates to Kjøkken and the controller follows
        assertTrue(mustLeaveRoom(kjokken, alone, listOf(stua)))
    }
}
