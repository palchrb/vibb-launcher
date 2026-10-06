package com.kidslauncher.mdm.calls

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** User requests after the emulator run: the call screens' avatar and Home's ongoing-call card. */
class CallCardTest {

    @Test
    fun `a known contact gets their Home avatar on the call screen`() {
        assertEquals(CallAvatar.CONTACT, callAvatar(knownContact = true, emergency = false, unlocked = true))
    }

    @Test
    fun `the silhouette for unknown or withheld numbers, emergency numbers and before the first unlock`() {
        assertEquals(CallAvatar.SILHOUETTE, callAvatar(knownContact = false, emergency = false, unlocked = true))
        assertEquals(CallAvatar.SILHOUETTE, callAvatar(knownContact = true, emergency = true, unlocked = true))
        assertEquals(CallAvatar.SILHOUETTE, callAvatar(knownContact = false, emergency = true, unlocked = true))
        assertEquals(CallAvatar.SILHOUETTE, callAvatar(knownContact = true, emergency = false, unlocked = false))
    }

    @Test
    fun `the card shows while any call is live and goes when it ends`() {
        assertNull(ongoingCallCard(liveCall = false, contactName = "Mamma", emergency = false, connectTimeMs = 1_000L, nowMs = 5_000L))
        assertEquals(OngoingCallCard("Mamma", null), ongoingCallCard(true, "Mamma", false, 0L, 5_000L))
    }

    @Test
    fun `the time runs from the connect time, whole seconds`() {
        assertEquals(OngoingCallCard("Mamma", 65L), ongoingCallCard(true, "Mamma", false, 10_000L, 75_999L))
        // A clock that went back never shows a negative time.
        assertEquals(OngoingCallCard("Mamma", 0L), ongoingCallCard(true, "Mamma", false, 10_000L, 5_000L))
    }

    @Test
    fun `unknown callers, blank names and emergency calls get no name - Call in progress`() {
        assertEquals(OngoingCallCard(null, null), ongoingCallCard(true, null, false, 0L, 0L))
        assertEquals(OngoingCallCard(null, null), ongoingCallCard(true, "  ", false, 0L, 0L))
        assertEquals(OngoingCallCard(null, 3L), ongoingCallCard(true, "112", true, 1_000L, 4_000L))
        assertEquals(OngoingCallCard("Pappa", null), ongoingCallCard(true, " Pappa ", false, 0L, 0L))
    }

    @Test
    fun `the emergency verdict is checked once per number`() {
        val cache = EmergencyVerdictCache()
        var checks = 0
        val check: (String) -> Boolean = { checks++; it == "112" }
        assertEquals(false, cache.isEmergency(null, check))
        assertEquals(0, checks)
        repeat(5) { assertEquals(true, cache.isEmergency("112", check)) }
        assertEquals(1, checks)
        assertEquals(false, cache.isEmergency("+4791234567", check))
        assertEquals(false, cache.isEmergency("+4791234567", check))
        assertEquals(2, checks)
    }
}
