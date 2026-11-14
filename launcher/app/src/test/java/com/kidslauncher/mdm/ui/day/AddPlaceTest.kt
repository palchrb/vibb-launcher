package com.kidslauncher.mdm.ui.day

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Dagen's floating "+" (S9 back to it, 2026-11-14) and the room the pages leave under their last card. */
class AddPlaceTest {
    @Test
    fun `the last card scrolls clear of the floating plus, and no room is kept without it`() {
        // The "+" ends ADD_BUTTON_DP + ADD_MARGIN_DP above the pager's bottom; the page's room clears it with air.
        assertTrue(pageBottomDp(shown = true) >= ADD_BUTTON_DP + ADD_MARGIN_DP + 16)
        assertEquals(96, pageBottomDp(shown = true))
        assertEquals(PAGE_AIR_DP, pageBottomDp(shown = false))
        assertTrue(pageBottomDp(shown = false) < ADD_BUTTON_DP + ADD_MARGIN_DP)
    }

    @Test
    fun `the constants are the layout's plus`() {
        val layout = java.io.File("src/main/res/layout/activity_day.xml").readText()
        val add = layout.substringAfter("android:id=\"@+id/day_add\"").substringBefore("/>")
        assertTrue(add.contains("android:layout_width=\"${ADD_BUTTON_DP}dp\""))
        assertTrue(add.contains("android:layout_height=\"${ADD_BUTTON_DP}dp\""))
        assertTrue(add.contains("android:layout_marginBottom=\"${ADD_MARGIN_DP}dp\""))
    }
}
