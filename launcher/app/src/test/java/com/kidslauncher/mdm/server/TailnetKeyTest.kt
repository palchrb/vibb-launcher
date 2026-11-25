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
        assertTrue(
            "the key and the mark in one commit",
            "it.raw.putBoolean(TAILNET_JOINED_KEY, true)" in forget && "if (forget) TailnetAuthKey.remove(it)" in forget,
        )
        assertTrue("read sealed", "val storedKey = TailnetAuthKey.get()" in body)
    }

    private val prefs = ParentPinStoreTest.FakePrefs()
    private val sealed = SealedPrefs(SealedPrefsTest.FakeBox()) { _, _ -> }
    private val prefKey = "mdm.tailscale_auth_key"

    @Test
    fun `the key is kept sealed, read back, and a blank one deletes it`() {
        assertTrue(TailnetAuthKey.write(sealed, prefs, prefKey, " $key "))
        val stored = prefs.getString(prefKey, null)!!
        assertTrue(stored.startsWith(SealedPrefs.PREFIX))
        assertFalse(key in stored)
        assertEquals("a blob no key opens reads as none, kept", null, TailnetAuthKey.read(SealedPrefs(SealedPrefsTest.FakeBox()) { _, _ -> }, prefs, prefKey))
        assertEquals(stored, prefs.getString(prefKey, null))
        assertEquals(key, TailnetAuthKey.read(sealed, prefs, prefKey))
        assertTrue(TailnetAuthKey.write(sealed, prefs, prefKey, "  "))
        assertEquals(null, prefs.getString(prefKey, null))
        assertEquals(null, TailnetAuthKey.read(sealed, prefs, prefKey))
    }

    @Test
    fun `a plain key from an older build is deleted at its first read`() {
        prefs.edit().putString(prefKey, key).commit()
        assertEquals(null, TailnetAuthKey.read(sealed, prefs, prefKey))
        assertEquals(null, prefs.getString(prefKey, null))
    }

    @Test
    fun `the settings row masks the key`() {
        val src = listOf("src", "app/src").map(::File).first { File(it, "main/java").isDirectory }
        val settings = File(src, "main/java/com/kidslauncher/mdm/ui/settings/launcher/SettingsFragmentLauncher.kt").readText()
        val summary = settings.substringAfter("private fun tailscaleKeySummary(): String {").substringBefore("\n    }\n")
        assertTrue("val key = TailnetAuthKey.get()" in summary)
        assertTrue("maskedSecretSummary(key, " in summary)
        assertTrue("only the last four shown", "val tail = value.takeLast(4)" in settings)
    }

    /** Upstream 0d4201dab9: a failed connect builds a fresh tsnet.Server next time - but never on a first join. */
    @Test
    fun `a failed connect drops its server only once joined`() {
        assertTrue("joined: from the stored state, a fresh server", tailnetFreshAfterFailure(""))
        assertFalse("a first join keeps its server: the one-time key may be spent", tailnetFreshAfterFailure(key))
        val src = listOf("src", "app/src").map(::File).first { File(it, "main/java").isDirectory }
        val client = File(src, "main/java/com/kidslauncher/mdm/server/TsnetClient.kt").readText()
        val failure = client.substringAfter("Log.w(LOG_TAG, \"Failed to connect to tailnet\", e)").substringBefore("} catch (e: LinkageError)")
        assertTrue("if (tailnetFreshAfterFailure(authKey)) discard()" in failure)
        // Once: connect's own proxyChanged(before) tells the listener, discard() doesn't.
        val discard = client.substringAfter("private fun discard() {").substringBefore("\n    }\n")
        assertFalse("proxyChanged" in discard)
    }
}
