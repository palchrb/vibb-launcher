package me.vibb.music.bridge

import me.vibb.music.Fixtures.moduleFile
import me.vibb.music.model.LibraryParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import javax.crypto.AEADBadTagException
import javax.crypto.KeyGenerator

/**
 * The music app's side of the bridge (design 21 §2): the AIDL byte-identical with the launcher's,
 * the managed config, the signer check, the listings it asks for, and the Storytel login sealed at
 * rest.
 */
class BridgeTest {
    private fun repo(path: String) = listOf(File("../../$path"), File("../$path")).first { it.exists() }

    @Test
    fun `the bridge's AIDL is byte-identical in both apps`() {
        for (name in listOf("IMusicBridge.aidl", "IStorytelCallback.aidl")) {
            val ours = moduleFile("src/main/aidl/me/vibb/bridge/$name")
            val theirs = repo("launcher/app/src/main/aidl/me/vibb/bridge/$name")
            assertEquals("$name differs from launcher/'s copy", theirs.readText(), ours.readText())
        }
    }

    @Test
    fun `the managed config the launcher writes`() {
        val config = bridgeConfig(
            mapOf(
                "v" to 1, "managed" to true, "launcher_package" to "me.vibb.launcher", "language" to "nb",
                "mobile_data" to true, "library_version" to "0123456789abcdef", "storytel_generation" to 3L,
            ),
        )
        assertEquals(BridgeConfig(true, "me.vibb.launcher", "nb", true, "0123456789abcdef", 3), config)
        assertEquals("no config: unmanaged", BridgeConfig.UNMANAGED, bridgeConfig(emptyMap()))
        assertEquals(BridgeConfig.UNMANAGED, bridgeConfig(mapOf("v" to 2, "managed" to true)))
        assertNull("not a launcher", bridgeConfig(mapOf("v" to 1, "managed" to true, "launcher_package" to "com.evil")).launcherPackage)
        assertEquals("an unknown language follows the phone", "", bridgeConfig(mapOf("v" to 1, "managed" to true, "language" to "de")).language)
    }

    @Test
    fun `only a launcher signed like this app is bound - another signer is refused`() {
        assertTrue(launcherAllowed("me.vibb.launcher") { true })
        assertTrue(launcherAllowed("me.vibb.launcher.debug") { true })
        assertFalse("another signer", launcherAllowed("me.vibb.launcher") { false })
        assertFalse("not a launcher name", launcherAllowed("com.example.launcher") { true })
        assertFalse(launcherAllowed(null) { true })
    }

    @Test
    fun `nothing ticked empties the library, an unmanaged phone never does`() {
        assertTrue(nothingTicked(BridgeConfig(true, "me.vibb.launcher", "", false, "", 0)))
        assertFalse(nothingTicked(BridgeConfig(true, "me.vibb.launcher", "", false, "0123456789abcdef", 0)))
        assertFalse(nothingTicked(BridgeConfig.UNMANAGED.copy(libraryVersion = "")))
        assertTrue(LibraryParser.parse(EMPTY_LIBRARY)!!.entries.isEmpty())
    }

    @Test
    fun `listings asked for - changed ones, whatever their version, not never-listed ones`() {
        assertEquals(listOf(1L, 3L), listingsWanted(mapOf(1L to "a", 2L to null, 3L to "c"), mapOf(3L to "b")))
        assertEquals(emptyList<Long>(), listingsWanted(mapOf(1L to "a"), mapOf(1L to "a")))
    }

    @Test
    fun `the Storytel login is sealed, and opens only with its key`() {
        val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        val login = StorytelLogin.Login(4, "ella@example.org", "hemmelig: æøå")
        val sealed = StorytelLogin.seal(key, login)
        assertFalse("no plaintext at rest", String(sealed, Charsets.ISO_8859_1).contains("hemmelig"))
        assertEquals(login, StorytelLogin.open(key, sealed))
        val other = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        try {
            StorytelLogin.open(other, sealed)
            fail("opened with another key")
        } catch (e: AEADBadTagException) {
            // expected
        }
    }
}
