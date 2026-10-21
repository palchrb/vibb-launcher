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
    fun `a member the phone can't name never passes as allowed (correction 2026-10-12)`() {
        val gone = "RINCON_000E58A0B1C901400" // in the household's group, no player row in the cache
        val rooms = SpeakerCache.rooms(SpeakerDoc(players, listOf(CachedGroup(stua, listOf(stua, gone)), CachedGroup(kjokken, listOf(kjokken, bad, gone)))))
        assertEquals("Stua's group has one known room: no group row, Stua alone", listOf("Kjøkken + Bad", "Loft", "Stua"), rooms.map { it.label })
        // Stua alone on the sheet, but it sounds with the unknown room
        assertEquals(listOf(stua, gone), roomMembers(rooms, stua))
        assertFalse(groupAllowed(listOf(stua, kjokken, bad, loft), rooms, stua))
        assertFalse(groupAllowed(listOf(stua, kjokken, bad, loft), rooms, kjokken))
        assertEquals(listOf("Loft"), allowedRooms(view(rooms), listOf(stua, kjokken, bad, loft)).rooms.map { it.label })
        assertTrue(mustLeaveRoom(stua, rooms, listOf(stua)))
        assertTrue("every room (the default) still plays it", groupAllowed(null, rooms, stua))
        assertEquals(3, allowedRooms(view(rooms), null).rooms.size)
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

    // -- design 53 §2.8: the user's answer to Q4 - a room grouped in by hand is respected, whatever the scope --

    @Test
    fun `d53 Q4 - a forbidden room the kids grouped in is accepted - no leave, our row stays, the belt passes`() {
        val onlyStua = listOf(stua, bad, loft)
        val accepted = setOf(kjokken)
        assertTrue(sessionAllowed(onlyStua, stuaLeads, stua, accepted))
        assertFalse("no leave", mustLeaveRoom(stua, stuaLeads, onlyStua, accepted))
        // our own row stays in the sheet with the accepted room; another out-of-scope row doesn't come back
        val rows = allowedRooms(view(stuaLeads), onlyStua, ours = stua, accepted = accepted).rooms.map { it.label }
        assertEquals(listOf("Stua + Kjøkken", "Bad", "Loft"), rows)
        assertEquals(listOf("Bad", "Loft"), allowedRooms(view(kjokkenLeads), onlyStua, ours = stua, accepted = accepted).rooms.map { it.label })
        // everything the app starts or adds itself still needs every room allowed
        assertFalse(groupAllowed(onlyStua, stuaLeads, stua))
    }

    @Test
    fun `d53 Q4 - a room the app put there, our coordinator forbidden, or a room no longer accepted still leaves`() {
        // a parent's new scope forbids a member the app put there (not accepted): the session leaves, as before
        assertTrue(mustLeaveRoom(stua, stuaLeads, listOf(stua), accepted = emptySet()))
        // our coordinator itself forbidden: accepted members don't save it
        assertTrue(mustLeaveRoom(stua, stuaLeads, listOf(kjokken), accepted = setOf(kjokken)))
        // a follow onto an accepted room (the kid took Stua out by hand-Play): it plays on there
        assertFalse(mustLeaveRoom(kjokken, household(loft, bad), listOf(stua), accepted = setOf(kjokken)))
        // ... and onto a room neither allowed nor accepted: it leaves, as today
        assertTrue(mustLeaveRoom(kjokken, household(loft, bad), listOf(stua), accepted = setOf(bad)))
        // the existing cases with nothing accepted are unchanged
        assertTrue(sessionAllowed(null, stuaLeads, stua, emptySet()))
        assertEquals(groupAllowed(listOf(stua), stuaLeads, stua), sessionAllowed(listOf(stua), stuaLeads, stua, emptySet()))
    }

    @Test
    fun `d53 the grouping belts - offered only on a live session of ours, a join never to our row or outside the scope, a leave only of a member`() {
        val v = RoomsView(stuaLeads, stale = false, scanning = false)
        assertTrue(groupingOffered(v, stua, live = true))
        assertFalse("not live (stopped, ended, not started)", groupingOffered(v, stua, live = false))
        assertFalse("the phone renders", groupingOffered(v, null, live = true))
        assertFalse("stale", groupingOffered(v.copy(stale = true), stua, live = true))
        assertFalse("grouped away: our room sits in Kjøkken's row", groupingOffered(RoomsView(kjokkenLeads, false, false), stua, live = true))
        assertTrue(joinOffered(v, stua, true, null, bad))
        assertFalse("our own row", joinOffered(v, stua, true, null, stua))
        assertFalse("outside the scope", joinOffered(v, stua, true, listOf(stua, kjokken), bad))
        assertFalse("no such row", joinOffered(v, stua, true, null, "RINCON_000E58A0B1C901400"))
        assertTrue(leaveOffered(v, stua, true, kjokken))
        assertTrue("our own coordinator too", leaveOffered(v, stua, true, stua))
        assertFalse("not a member of our group (N2)", leaveOffered(v, stua, true, bad))
        assertFalse("a lone room has no −: Q2", leaveOffered(RoomsView(household(loft, bad), false, false), stua, true, stua))
    }
}
