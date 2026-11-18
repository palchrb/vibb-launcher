package com.kidslauncher.mdm.ui.messages

import com.kidslauncher.mdm.map.MapLinks
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Design 110 Q1, code QA M1/L3: a message's link opens only an app the parent allows, never Play services, and
 * nothing during a time rule; a long run of punctuation is no slow bind. */
class MessageLinksTest {
    private val allowed = setOf("org.example.browser", "com.android.chrome", "me.vibb.launcher")

    @Test
    fun `a link opens only an allowed app`() {
        // Rows: (Android's candidates, its own answer first) -> the app opened, or null for the calm note.
        val rows = listOf(
            listOf("org.example.browser") to "org.example.browser",
            listOf("android", "org.example.browser") to "org.example.browser",
            // Play services holds the host (a system-configured app link): never from Meldinger.
            listOf("com.google.android.gms", "org.example.browser") to "org.example.browser",
            listOf("com.google.android.gms") to null,
            listOf("com.google.android.gsf") to null,
            // A system app without an icon the parent never allowed.
            listOf("com.android.htmlviewer") to null,
            listOf("org.example.hidden", "com.android.chrome") to "com.android.chrome",
            emptyList<String>() to null,
        )
        for ((candidates, want) in rows) {
            assertEquals(candidates.toString(), want, messageLinkTarget(candidates, allowed, locked = false))
        }
        // A phone without an allowlist restricts nothing - but Play services still never.
        assertEquals("com.android.htmlviewer", messageLinkTarget(listOf("com.android.htmlviewer"), null, locked = false))
        assertEquals(null, messageLinkTarget(listOf("com.google.android.gms"), null, locked = false))
        // During a time rule, nothing.
        assertEquals(null, messageLinkTarget(listOf("org.example.browser"), allowed, locked = true))
    }

    @Test
    fun `a long run after a link is trimmed in one pass and capped`() {
        val text = "https://a.b/" + ")".repeat(50_000) + " ok"
        val started = System.nanoTime()
        val found = MapLinks.webLinks(text)
        assertTrue((System.nanoTime() - started) < 2_000_000_000L)
        assertEquals(1, found.size)
        assertTrue(found[0].last - found[0].first + 1 <= MapLinks.MAX_LINK)
        assertEquals("https://a.b/", text.substring(found[0].first, found[0].last + 1))
    }
}
