package com.kidslauncher.mdm.ui.day

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Screen-size fix S9 and its code QA M2: where Dagen's pages leave room for the "+". */
class AddPlaceTest {
    @Test
    fun `the strip only where the page keeps about three rows, else the floating plus as before`() {
        // The Jelly Star in portrait: a pager of about 440 dp keeps 360 dp of list under the strip.
        assertEquals(AddPlace.STRIP, addPlace(shown = true, pagerDp = 440f))
        // Turned to landscape (about 190 dp of pager), or with the reminder row on top: the floating "+".
        assertEquals(AddPlace.FLOATING, addPlace(shown = true, pagerDp = 190f))
        assertEquals(AddPlace.FLOATING, addPlace(shown = true, pagerDp = DayActivity.ADD_CLEAR_DP + ADD_STRIP_MIN_LIST_DP - 1f))
        assertEquals(AddPlace.STRIP, addPlace(shown = true, pagerDp = (DayActivity.ADD_CLEAR_DP + ADD_STRIP_MIN_LIST_DP).toFloat()))
        // Not laid out yet: the strip, corrected at the first layout.
        assertEquals(AddPlace.STRIP, addPlace(shown = true, pagerDp = 0f))
        // No "+": no room kept; the one switch back to the floating "+" everywhere.
        assertEquals(AddPlace.NONE, addPlace(shown = false, pagerDp = 440f))
        assertEquals(AddPlace.FLOATING, addPlace(shown = true, pagerDp = 440f, strip = false))
        // The pages' bottom: the strip clears the "+" (56 dp, 20 dp margin, 4 dp air); the floating "+" the old 96 dp.
        assertEquals(80, pageBottomDp(AddPlace.STRIP))
        assertEquals(96, pageBottomDp(AddPlace.FLOATING))
        assertTrue(pageBottomDp(AddPlace.NONE) < pageBottomDp(AddPlace.STRIP))
    }
}
