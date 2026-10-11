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
    fun `a media notification never counts, the music app's N nye notification counts its number`() {
        // A paused player's notification is neither ongoing nor unread (design 21 QA #6).
        val paused = NotificationInfo("me.vibb.music", ongoing = false, clearable = true, groupSummary = false, number = 0, mediaSession = true)
        val playing = paused.copy(ongoing = true, clearable = false)
        val news = NotificationInfo("me.vibb.music", ongoing = false, clearable = true, groupSummary = false, number = 2)
        assertEquals(mapOf("me.vibb.music" to 2), badgeCounts(listOf(paused, news), "launcher"))
        assertEquals(mapOf("me.vibb.music" to 2), badgeCounts(listOf(playing, news), "launcher"))
        assertEquals(emptyMap<String, Int>(), badgeCounts(listOf(paused), "launcher"))
        // Any app's media notification, the same.
        assertEquals(emptyMap<String, Int>(), badgeCounts(listOf(paused.copy(packageName = "org.player", number = 4)), "launcher"))
    }

    @Test
    fun `the media check looks only at the media-session key`() {
        val file = listOf("src/main", "app/src/main").map { java.io.File("$it/java/com/kidslauncher/mdm/badges/MediaNotifications.kt") }.first { it.exists() }
        val code = file.readText().lines().filterNot { it.trim().startsWith("*") || it.trim().startsWith("/**") }.joinToString("\n")
        assertEquals(listOf("extras?.containsKey(Notification.EXTRA_MEDIA_SESSION)"), Regex("extras[^\\n]*?\\)").findAll(code).map { it.value }.toList())
    }

    @Test
    fun `nothing active, no badges`() {
        assertEquals(emptyMap<String, Int>(), badgeCounts(emptyList(), "launcher"))
    }
}
