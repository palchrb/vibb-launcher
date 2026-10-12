package com.kidslauncher.mdm.server

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Security review M12: the Tailscale auth key is deleted once the phone is on the tailnet. */
class TailnetKeyTest {
    // Not a real key's shape (see the fake-secrets rule): only that it is non-blank matters.
    private val key = "TESTONLY-tailnet-key"

    @Test
    fun `a stored key brings the node up, then the stored state does`() {
        assertEquals(TailnetConnect.Up(key), tailnetConnect(key, joined = false, connected = false))
        assertEquals("a new key while joined is still tried", TailnetConnect.Up(key), tailnetConnect(key, joined = true, connected = false))
        assertEquals("after the first join: no key", TailnetConnect.Up(""), tailnetConnect(null, joined = true, connected = false))
        assertEquals(TailnetConnect.Up(""), tailnetConnect("  ", joined = true, connected = false))
        assertEquals("never joined, no key: nothing", TailnetConnect.Nothing, tailnetConnect(null, joined = false, connected = false))
        assertEquals(TailnetConnect.Nothing, tailnetConnect("", joined = false, connected = false))
        assertEquals(TailnetConnect.Connected, tailnetConnect(key, joined = false, connected = true))
        assertEquals(TailnetConnect.Connected, tailnetConnect(null, joined = true, connected = true))
    }

    @Test
    fun `the key goes once connected, never before`() {
        assertTrue(forgetTailnetKey(key, connected = true))
        assertFalse("a failed connect keeps it for the next try", forgetTailnetKey(key, connected = false))
        assertFalse("nothing to delete", forgetTailnetKey(null, connected = true))
        assertFalse(forgetTailnetKey("", connected = true))
    }

    /** The wiring: TsnetClient deletes the key only after a connect that returned an address, or when connected. */
    @Test
    fun `TsnetClient deletes the key after a successful connect`() {
        val src = listOf("src", "app/src").map(::File).first { File(it, "main/java").isDirectory }
        val client = File(src, "main/java/com/kidslauncher/mdm/server/TsnetClient.kt").readText()
        assertTrue(
            "QA Low 5: one connect at a time",
            "@Synchronized\n    fun connectFromPreferences(context: Context): String?" in client,
        )
        val body = client.substringAfter("fun connectFromPreferences(").substringBefore("fun joined(")
        assertTrue("if (ip != null) forgetKeyOnceJoined(context, storedKey)" in body)
        assertTrue("tailnetConnect(storedKey, prefs.getBoolean(TAILNET_JOINED_KEY, false), connected)" in body)
        val forget = client.substringAfter("private fun forgetKeyOnceJoined(").substringBefore("\n    }\n")
        assertTrue("only while connected", "if (!connected) return" in forget)
        assertTrue("the key and the mark in one commit", "edit.remove(LauncherPreferences.mdm().keys().tailscaleAuthKey())" in forget && "edit.commit()" in forget)
    }
}
