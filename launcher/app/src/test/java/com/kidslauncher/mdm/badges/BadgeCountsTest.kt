package com.kidslauncher.mdm.badges

import org.junit.Assert.assertEquals
import org.junit.Test

class BadgeCountsTest {

    private fun n(pkg: String, number: Int = 0, ongoing: Boolean = false, clearable: Boolean = true, summary: Boolean = false) =
        NotificationInfo(pkg, ongoing, clearable, summary, number)

    @Test
    fun `counts notifications per app, using number when set`() {
        val counts = badgeCounts(
            listOf(n("element"), n("element"), n("signal", number = 5), n("mail", number = 0)),
            ownPackage = "launcher",
        )
        assertEquals(mapOf("element" to 2, "signal" to 5, "mail" to 1), counts)
    }

    @Test
    fun `ongoing, sticky, summaries and our own notifications don't count`() {
        val counts = badgeCounts(
            listOf(
                n("music", ongoing = true),
                n("vpn", clearable = false),
                n("element", summary = true, number = 7),
                n("element"),
                n("launcher", number = 3),
            ),
            ownPackage = "launcher",
        )
        assertEquals(mapOf("element" to 1), counts)
    }

    @Test
    fun `nothing active, no badges`() {
        assertEquals(emptyMap<String, Int>(), badgeCounts(emptyList(), "launcher"))
    }
}
