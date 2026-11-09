package com.kidslauncher.mdm.ui.search

import com.kidslauncher.mdm.calls.SEARCH_MIN_ROWS
import com.kidslauncher.mdm.calls.askName
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Design 79 §4.1: the search's rules on a screen - the threshold, the Back order, the prefill, the live count. */
class KidSearchTest {

    @Test
    fun `the pill from 8 rows, sticky while it holds text, never where the screen can't search`() {
        assertFalse(searchShown(7, "", allowed = true, minRows = SEARCH_MIN_ROWS))
        assertTrue(searchShown(8, "", allowed = true, minRows = SEARCH_MIN_ROWS))
        assertTrue(searchShown(3, "o", allowed = true, minRows = SEARCH_MIN_ROWS))
        assertFalse(searchShown(50, "o", allowed = false, minRows = SEARCH_MIN_ROWS))
        assertEquals(8, SEARCH_MIN_ROWS)
    }

    @Test
    fun `Back - the form, then the search's text, then the phone book leaves`() {
        assertEquals(PhoneBookBack.CLOSE_FORM, phoneBookBack(keyboardUp = false, formOpen = true, query = "Lina"))
        assertEquals(PhoneBookBack.CLEAR_SEARCH, phoneBookBack(keyboardUp = false, formOpen = false, query = "Lina"))
        assertEquals(PhoneBookBack.LEAVE, phoneBookBack(keyboardUp = false, formOpen = false, query = ""))
        // Spaces are text: Back clears them too, before it leaves.
        assertEquals(PhoneBookBack.CLEAR_SEARCH, phoneBookBack(keyboardUp = false, formOpen = false, query = " "))
    }

    /** Code QA L10: with the keyboard up the first Back only closes it, whatever else is open (stock Android). */
    @Test
    fun `Back with the keyboard up only closes the keyboard`() {
        assertEquals(PhoneBookBack.HIDE_KEYBOARD, phoneBookBack(keyboardUp = true, formOpen = true, query = "Lina"))
        assertEquals(PhoneBookBack.HIDE_KEYBOARD, phoneBookBack(keyboardUp = true, formOpen = false, query = "Ma"))
        assertEquals(MessagesBack.HIDE_KEYBOARD, messagesBack(keyboardUp = true, deleting = false, query = "Ma", picking = true))
    }

    @Test
    fun `Back in Meldinger - the confirm, the search, Ny melding, then it leaves`() {
        assertEquals(MessagesBack.CANCEL_DELETE, messagesBack(keyboardUp = false, deleting = true, query = "a", picking = true))
        assertEquals(MessagesBack.CLEAR_SEARCH, messagesBack(keyboardUp = false, deleting = false, query = "a", picking = true))
        assertEquals(MessagesBack.STOP_PICKING, messagesBack(keyboardUp = false, deleting = false, query = "", picking = true))
        assertEquals(MessagesBack.LEAVE, messagesBack(keyboardUp = false, deleting = false, query = "", picking = false))
    }

    @Test
    fun `the prefill - the query as the name from two letters, never a number`() {
        assertEquals("Lina", askName("Lina"))
        assertEquals("Li", askName(" Li "))
        assertNull(askName("L"))
        assertNull(askName("41234567"))
        assertNull(askName("+47 4"))
        assertEquals("Ola 2", askName("Ola 2"))
        assertEquals(SEARCH_QUERY_MAX, askName("a".repeat(60))?.length)
    }

    @Test
    fun `the live count speaks at most every 500 ms`() {
        assertEquals(1_000L, searchAnnounceAt(null, 1_000))
        assertEquals(1_500L, searchAnnounceAt(1_000, 1_100))
        assertEquals(2_000L, searchAnnounceAt(1_000, 2_000))
    }
}
