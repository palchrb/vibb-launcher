package me.vibb.music.bridge

import me.vibb.music.Fixtures.moduleFile
import me.vibb.music.model.LibraryParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
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
                "mobile_data" to true, "library_version" to "0123456789abcdef", "storytel_generation" to 3,
                "volume_cap_pct" to 70,
            ),
        )
        assertEquals(BridgeConfig(true, "me.vibb.launcher", "nb", true, "0123456789abcdef", 3, volumeCapPct = 70), config)
        assertEquals("no config: unmanaged", BridgeConfig.UNMANAGED, bridgeConfig(emptyMap()))
        assertEquals(BridgeConfig.UNMANAGED, bridgeConfig(mapOf("v" to 2, "managed" to true)))
        assertNull("not a launcher", bridgeConfig(mapOf("v" to 1, "managed" to true, "launcher_package" to "com.evil")).launcherPackage)
        assertEquals("an unknown language follows the phone", "", bridgeConfig(mapOf("v" to 1, "managed" to true, "language" to "de")).language)
    }

    @Test
    fun `the volume cap in force - 1 to 99 percent caps, 0 or anything else is none (design 26a §5_3)`() {
        fun cap(value: Any?) = bridgeConfig(mapOf("v" to 1, "managed" to true, "volume_cap_pct" to value)).volumeCapPct
        assertEquals(60, cap(60))
        assertEquals(99, cap(99))
        assertNull("0 = none", cap(0))
        assertNull("100 caps nothing", cap(100))
        assertNull("not an Int", cap("60"))
        assertNull("absent (an older launcher)", bridgeConfig(mapOf("v" to 1, "managed" to true)).volumeCapPct)
        assertNull("unmanaged", bridgeConfig(mapOf("volume_cap_pct" to 60)).volumeCapPct)
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

    private val contract by lazy {
        me.vibb.music.model.JSON.parseToJsonElement(me.vibb.music.Fixtures.resource("bridge_contract.json")).jsonObject
    }

    private fun strings(key: String) = (contract[key] as JsonArray).map { (it as JsonPrimitive).content }

    @Test
    fun `the bridge's names match the shared contract`() {
        assertEquals("the launcher's copy", repo("launcher/app/src/test/resources/bridge_contract.json").readText(), me.vibb.music.Fixtures.resource("bridge_contract.json"))
        assertEquals((contract["bridge_service"] as JsonPrimitive).content, BRIDGE_SERVICE)
        assertEquals((contract["action_bridge_changed"] as JsonPrimitive).content, ACTION_BRIDGE_CHANGED)
        assertEquals(strings("launcher_packages"), LAUNCHER_PACKAGES)
        val manifest = moduleFile("src/main/AndroidManifest.xml").readText()
        for (pkg in LAUNCHER_PACKAGES) assertTrue(manifest.contains(pkg + (contract["permission_suffix"] as JsonPrimitive).content))
        assertTrue(manifest.contains("android:name=\"" + (contract["action_bridge_changed"] as JsonPrimitive).content))
        // The managed config: these keys with these types are what bridgeConfig reads.
        val types = (contract["restrictions"] as JsonObject).mapValues { (it.value as JsonPrimitive).content }
        val keys = listOf(
            RestrictionKeys.V, RestrictionKeys.MANAGED, RestrictionKeys.LAUNCHER_PACKAGE, RestrictionKeys.LANGUAGE,
            RestrictionKeys.MOBILE_DATA, RestrictionKeys.LIBRARY_VERSION, RestrictionKeys.STORYTEL_GENERATION,
            RestrictionKeys.VOLUME_CAP_PCT,
        )
        assertEquals(types.keys, keys.toSet())
        val sample: Map<String, Any> = types.mapValues { (key, type) ->
            when (type) {
                "int" -> if (key == RestrictionKeys.V) 1 else 7
                "boolean" -> true
                else -> if (key == RestrictionKeys.LAUNCHER_PACKAGE) "me.vibb.launcher" else "nb"
            }
        }
        val read = bridgeConfig(sample)
        assertTrue(read.managed && read.mobileData && read.launcherPackage == "me.vibb.launcher" && read.storytelGeneration == 7L && read.language == "nb")
        assertEquals(7, read.volumeCapPct)
        assertEquals(strings("report_state"), listOf(REPORT_KEY, HELD_FILES_KEY, HELD_SHA256_KEY))
        assertEquals(
            strings("now_playing"),
            listOf(NowPlayingKeys.ENTRY, NowPlayingKeys.ITEM, NowPlayingKeys.POSITION_MS, NowPlayingKeys.DURATION_MS, NowPlayingKeys.PLAYING, NowPlayingKeys.ART),
        )
        // The launcher's Home card opens the player and continues (design 21 step 4).
        val player = (contract["player"] as JsonObject).mapValues { (it.value as JsonPrimitive).content }
        assertEquals(player["activity"], me.vibb.music.ui.MainActivity::class.java.name)
        assertEquals(player["extra_open"], me.vibb.music.ui.MainActivity.EXTRA_OPEN)
        assertEquals(player["open_player"], me.vibb.music.ui.MainActivity.OPEN_PLAYER)
        assertEquals(player["extra_resume"], me.vibb.music.ui.MainActivity.EXTRA_RESUME)
        assertTrue("the player is exported", Regex("android:name=\"\\.ui\\.MainActivity\"\\s+android:exported=\"true\"").containsMatchIn(manifest))
    }

    @Test
    fun `the report's keys are the shared music_state fixture's, less the launcher's own`() {
        val fixture = me.vibb.music.model.JSON.parseToJsonElement(repo("server/testdata/music_state.json").readText()).jsonObject
        val report = me.vibb.music.logic.stateReport(1, "0123456789abcdef", 0, emptyList(), emptyList(), emptyList(), "ok")
        assertEquals(fixture.keys - setOf("package", "bridge"), report.keys)
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
