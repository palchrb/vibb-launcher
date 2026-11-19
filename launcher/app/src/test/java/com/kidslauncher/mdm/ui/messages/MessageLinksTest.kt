package com.kidslauncher.mdm.ui.messages

import com.kidslauncher.mdm.logins.freeBrowserOf
import com.kidslauncher.mdm.logins.linkTargetOf
import com.kidslauncher.mdm.map.MapLinks
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Design 110 Q1, code QA M1/L3, design 119 §5: a message's link opens only an allowed app that declares it (found per
 * package, so an unverified one too), never Chrome as the login browser, never Play services, nothing during a time
 * rule; a long run of punctuation is no slow bind. */
class MessageLinksTest {
    private val own = "me.vibb.launcher"
    private val allowlist = listOf("org.example.maps", "org.example.game", "org.example.browser")

    @Test
    fun `chrome is never allowed, whatever the role`() {
        // The login browser is never in Meldinger's set (design 119 §5): the allowlist and Vibb One only.
        assertEquals(setOf("org.example.maps", "org.example.game", "org.example.browser", own), messageLinkAllowed(allowlist, own))
        assertFalse("com.android.chrome" in messageLinkAllowed(allowlist, own)!!)
        assertNull(messageLinkAllowed(null, own))
        // Android's own answer is Chrome (the browser role's holder): not allowed, so the declaring app wins.
        val allowed = messageLinkAllowed(allowlist, own)
        assertEquals("org.example.maps", linkTargetOf("com.android.chrome", listOf("org.example.maps"), allowed, false, own, emptySet()))
        assertNull(linkTargetOf("com.android.chrome", emptyList(), allowed, false, own, emptySet()))
    }

    @Test
    fun `a link opens only an allowed app that declares it`() {
        val allowed = messageLinkAllowed(allowlist, own)
        val browsers = setOf("org.example.browser")
        // Rows: (Android's answer, the declaring packages in the allowlist's order) -> the app opened, or null.
        val rows = listOf(
            // An unverified app that declares the host is found while a browser exists (H1): Android's answer is the
            // browser role's holder (here Vibb One's gate), the per-package lookup finds Organic Maps.
            Triple(own, listOf("org.example.maps", "org.example.browser"), "org.example.maps"),
            Triple("android", listOf("org.example.game"), "org.example.game"),
            // An app approved for the host comes first.
            Triple("org.example.game", listOf("org.example.maps"), "org.example.game"),
            // Play services holds the host (a system-configured app link): never from Meldinger.
            Triple("com.google.android.gms", listOf("org.example.maps"), "org.example.maps"),
            Triple("com.google.android.gms", emptyList(), null),
            Triple("com.google.android.gsf", emptyList(), null),
            // A system app the parent never allowed.
            Triple("com.android.htmlviewer", emptyList(), null),
            // A full browser is no app for this link.
            Triple("org.example.browser", listOf("org.example.browser"), null),
            Triple(null, emptyList(), null),
        )
        for ((approved, declaring, want) in rows) {
            assertEquals("$approved $declaring", want, linkTargetOf(approved, declaring, allowed, locked = false, own = own, browsers = browsers))
        }
        // The free browser the parent ticked comes after; Chrome unticked never is one (it isn't allowed).
        assertEquals("org.example.browser", freeBrowserOf(listOf("org.example.browser"), allowed, own))
        assertNull(freeBrowserOf(listOf("com.android.chrome"), allowed, own))
        assertNull(freeBrowserOf(listOf(own), allowed, own))
        // A phone without an allowlist restricts nothing - but Play services still never.
        assertEquals("com.android.htmlviewer", linkTargetOf("com.android.htmlviewer", emptyList(), null, false, own, emptySet()))
        assertNull(linkTargetOf("com.google.android.gms", emptyList(), null, false, own, emptySet()))
        assertEquals("com.android.chrome", freeBrowserOf(listOf("com.android.chrome"), null, own))
        // During a time rule, nothing.
        assertNull(linkTargetOf("org.example.game", listOf("org.example.game"), allowed, locked = true, own = own, browsers = emptySet()))
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
