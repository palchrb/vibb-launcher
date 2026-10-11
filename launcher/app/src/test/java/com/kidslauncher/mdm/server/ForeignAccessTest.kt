package com.kidslauncher.mdm.server

import com.kidslauncher.mdm.server.dto.ForeignAccessReport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Design 24b's fix round (QA High 1 (b)): what makes the phone refuse the parent PIN. */
class ForeignAccessTest {
    private val system = setOf("com.google.android.gms", "com.android.talkback")
    private val installed = system + setOf("org.example.chat", "com.sideloaded.admin", "com.sideloaded.reader", OWN)

    private fun find(admins: List<String>, accessibility: List<String>, allowlist: List<String>?) =
        foreignAccess(admins, accessibility, allowlist, OWN) { pkg -> if (pkg in installed) pkg in system else null }

    @Test
    fun `a non-system admin or accessibility service outside the allowlist is found`() {
        assertEquals(
            ForeignAccessReport(listOf("com.sideloaded.admin"), listOf("com.sideloaded.reader")),
            find(listOf(OWN, "com.google.android.gms", "com.sideloaded.admin"), listOf("com.android.talkback", "com.sideloaded.reader"), listOf("org.example.chat")),
        )
    }

    @Test
    fun `ours, system apps, allowed apps and uninstalled leftovers are not`() {
        assertNull(find(listOf(OWN, "com.google.android.gms"), listOf("com.android.talkback", "org.example.chat", "com.gone.app"), listOf("org.example.chat")))
        // An allowed app's service is the parent's choice (the tick in the parent app).
        assertNull(find(listOf("com.sideloaded.admin"), emptyList(), listOf("com.sideloaded.admin")))
    }

    @Test
    fun `a relock-blocked app counts as outside the allowlist`() {
        val allowlist = effectiveAllowlist(listOf("org.example.chat", "com.sideloaded.admin"), setOf("com.sideloaded.admin"))
        assertEquals(ForeignAccessReport(listOf("com.sideloaded.admin"), emptyList()), find(listOf("com.sideloaded.admin"), emptyList(), allowlist))
    }

    @Test
    fun `nothing while apps are unmanaged - calls-only phones`() {
        assertNull(find(listOf("com.sideloaded.admin"), listOf("com.sideloaded.reader"), null))
    }

    private companion object {
        const val OWN = "me.vibb.launcher"
    }
}
