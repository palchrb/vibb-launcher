package com.kidslauncher.mdm.music

import com.kidslauncher.mdm.server.ServerJson
import com.kidslauncher.mdm.server.dto.StatusReportRequest
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The launcher's side of Vibb music (design 21 §2/§3, 21b §5): the bridge's contract with the music
 * app (the AIDL byte-identical, the signature permission), the policy and library as the launcher
 * reads them, the listings, covers and own files it fetches, the managed config, the caller check,
 * the update deferral and `music_state`.
 */
class MusicPlanTest {
    private fun module(path: String) = listOf(File(path), File("app/$path")).first { it.exists() }
    private fun repo(path: String) = listOf(File("../../$path"), File("../$path")).first { it.exists() }

    // The contract with music/.

    @Test
    fun `the bridge's AIDL is byte-identical in both apps`() {
        for (name in listOf("IMusicBridge.aidl", "IStorytelCallback.aidl")) {
            val ours = module("src/main/aidl/me/vibb/bridge/$name")
            val theirs = repo("music/app/src/main/aidl/me/vibb/bridge/$name")
            assertEquals("$name differs from music/'s copy", theirs.readText(), ours.readText())
        }
    }

    @Test
    fun `the bridge is behind a signature permission of the launcher's own`() {
        val manifest = module("src/main/AndroidManifest.xml").readText()
        val permission = manifest.substringAfter("android:name=\"\${applicationId}.permission.MUSIC_BRIDGE\"").substringBefore("/>")
        assertTrue(permission, permission.contains("android:protectionLevel=\"signature\""))
        val service = manifest.substringAfter("android:name=\"com.kidslauncher.mdm.music.MusicBridgeService\"").substringBefore("/>")
        assertTrue(service, service.contains("android:permission=\"\${applicationId}.permission.MUSIC_BRIDGE\""))
        // The music app asks for both build names' permission.
        val music = repo("music/app/src/main/AndroidManifest.xml").readText()
        assertTrue(music.contains("me.vibb.launcher.permission.MUSIC_BRIDGE") && music.contains("me.vibb.launcher.debug.permission.MUSIC_BRIDGE"))
    }

    @Test
    fun `only the music app's two build names may call it`() {
        assertTrue(callerAllowed(listOf("me.vibb.music")))
        assertTrue(callerAllowed(listOf("me.vibb.music.debug")))
        assertFalse(callerAllowed(listOf("com.example.other")))
        assertFalse(callerAllowed(null))
    }

    // What the launcher reads.

    private fun policyJson(version: kotlinx.serialization.json.JsonElement = JsonPrimitive("0123456789abcdef"), generation: kotlinx.serialization.json.JsonElement = JsonPrimitive(3)) =
        buildJsonObject {
            put("library_version", version); put("mobile_data", true); put("volume_cap_pct", 70); put("storytel_generation", generation)
        }

    @Test
    fun `the policy's music object - null means nothing ticked, anything malformed keeps what is there (M4)`() {
        assertEquals(MusicPolicy("0123456789abcdef", true, 70, 3), musicPolicy(policyJson()))
        assertEquals("JSON null: nothing ticked", MusicPolicy(null, true, 70, 3), musicPolicy(policyJson(version = JsonNull)))
        assertNull("a longer version", musicPolicy(policyJson(version = JsonPrimitive("0123456789abcdef0123"))))
        assertNull("a number", musicPolicy(policyJson(version = JsonPrimitive(5))))
        assertNull("a generation that isn't a number", musicPolicy(policyJson(generation = JsonPrimitive("x"))))
        assertNull("a negative generation", musicPolicy(policyJson(generation = JsonPrimitive(-1))))
        assertNull("library_version missing", musicPolicy(buildJsonObject { put("mobile_data", true); put("storytel_generation", 0) }))
        assertNull("an older server", musicPolicy(null))
        assertNull(musicPolicy(JsonPrimitive(5)))
    }

    @Test
    fun `the shared library snapshot as the launcher reads it`() {
        val head = libraryHead(repo("server/testdata/music_library.json").readText())!!
        assertEquals(3, head.covers.size)
        assertEquals(listOf(1L, 2L), head.files.map { it.id })
        assertTrue(head.files[1].missing)
        assertEquals("own entries have no listing", mapOf(1L to null, 2L to "0123456789abcdef"), head.listings)
        assertNull(libraryHead("{\"v\": 2}"))
    }

    @Test
    fun `the shared listing snapshot's version`() {
        assertEquals("33e4fb66e77b8d7f", listingVersionOf(repo("server/testdata/music_listing.json").readText()))
        assertNull(listingVersionOf("{\"v\":9,\"version\":\"x\"}"))
    }

    // Listings, own files.

    private val head = LibraryHead(
        "v", setOf("c"),
        listOf(LibraryFile(1, 10, "a".repeat(64), false), LibraryFile(2, 20, "b".repeat(64), true), LibraryFile(3, 30, "c".repeat(64), false)),
        mapOf(10L to "1111111111111111", 11L to null, 12L to "2222222222222222"),
    )

    @Test
    fun `listings - changed ones are fetched (any version), never-listed ones not, unnamed ones deleted`() {
        assertEquals(listOf(10L, 12L), listingsToFetch(head, emptyMap()))
        assertEquals(listOf(12L), listingsToFetch(head, mapOf(10L to "1111111111111111", 12L to "9999999999999999")))
        assertEquals(setOf(99L), listingsToDelete(head, setOf(10L, 99L)))
        assertEquals("no library: all go", setOf(10L), listingsToDelete(null, setOf(10L)))
    }

    @Test
    fun `own files - one ahead of the handover, never one handed over, missing or failing its hash`() {
        val a = "a".repeat(64)
        assertEquals(1L, nextOwnFile(head, emptyMap(), emptyMap())?.id)
        assertNull("one waits for the music app", nextOwnFile(head, emptyMap(), mapOf(1L to a)))
        assertEquals(3L, nextOwnFile(head, mapOf(1L to a), emptyMap())?.id)
        assertEquals("a new hash is a new file", 1L, nextOwnFile(head, mapOf(1L to "f".repeat(64)), emptyMap())?.id)
        assertNull(nextOwnFile(head, mapOf(1L to a, 3L to "c".repeat(64)), emptyMap()))
        assertEquals("a file failing its hash waits, the next goes", 3L, nextOwnFile(head, emptyMap(), emptyMap(), skip = setOf(1L))?.id)
        assertEquals(setOf(9L, 3L), readyFilesToDelete(head, mapOf(1L to a, 9L to "x", 3L to "d".repeat(64))))
    }

    @Test
    fun `a ready file the music app already holds blocks nothing and is deleted (M1 - a lost releaseFile)`() {
        val a = "a".repeat(64)
        val handed = mapOf(1L to a)
        assertEquals(3L, nextOwnFile(head, handed, mapOf(1L to a))?.id)
        assertEquals(setOf(1L), readyFilesToDelete(head, mapOf(1L to a), handed))
    }

    @Test
    fun `a file that failed its hash twice waits a day`() {
        val now = 1_800_000_000_000L
        assertFalse(hashFailureSkips(1, now, now))
        assertTrue(hashFailureSkips(2, now - 60_000, now))
        assertFalse(hashFailureSkips(2, now - OWN_FILE_HASH_BACKOFF_MS, now))
    }

    // The managed config.

    @Test
    fun `the managed config carries the keys the music app reads`() {
        val r = restrictionsFor(MusicPolicy(null, true, 60, 4), "me.vibb.launcher", "nb", 60)
        assertEquals(
            mapOf(
                "v" to 1, "managed" to true, "launcher_package" to "me.vibb.launcher", "language" to "nb", "mobile_data" to true, "library_version" to "",
                "storytel_generation" to 4, "volume_cap_pct" to 60,
            ),
            r,
        )
    }

    @Test
    fun `the managed config carries the volume cap in force - 0 when none, lifted with the override (design 26a §5_3)`() {
        val policy = MusicPolicy(null, true, 70, 4)
        assertEquals(70, restrictionsFor(policy, "p", "", capPctInForce(policy.volumeCapPct, managed = true, overrideActive = false))["volume_cap_pct"])
        assertEquals("the override or the pause lifts it", 0, restrictionsFor(policy, "p", "", capPctInForce(policy.volumeCapPct, managed = true, overrideActive = true))["volume_cap_pct"])
        assertEquals("unmanaged", 0, restrictionsFor(policy, "p", "", capPctInForce(policy.volumeCapPct, managed = false, overrideActive = false))["volume_cap_pct"])
        assertEquals("off", 0, volumeCapRestriction(null))
        assertEquals("100 % caps nothing", 0, volumeCapRestriction(100))
        assertEquals("never a mute: 0 and below are none", 0, volumeCapRestriction(-5))
        assertEquals(MusicRestrictions.VOLUME_CAP_PCT, "volume_cap_pct")
    }

    @Test
    fun `every apply edge pushes a changed cap to the music app`() {
        val cap = module("src/main/java/com/kidslauncher/mdm/music/VolumeCap.kt").readText()
        val refresh = cap.substringAfter("fun refresh(").substringBefore("\n    }\n")
        assertTrue(refresh.contains("MusicSync.capChanged(app, policy)"))
        val sync = module("src/main/java/com/kidslauncher/mdm/music/MusicSync.kt").readText()
        assertTrue(sync.contains("restrictionsFor(music, app.packageName, resolveLauncherLocale(policy.launcherUi?.language), VolumeCap.pctInForce)"))
    }

    @Test
    fun `the managed config holds only types the platform stores - Boolean, Int, String (H1)`() {
        for (policy in listOf(MusicPolicy("0123456789abcdef", false, null, 0), MusicPolicy(null, true, 60, Long.MAX_VALUE))) {
            for ((key, value) in restrictionsFor(policy, "me.vibb.launcher", "", policy.volumeCapPct)) {
                assertTrue("$key is a ${value.javaClass.simpleName}", restrictionValueStorable(value))
            }
        }
        assertEquals("a huge generation is clamped", Int.MAX_VALUE, restrictionsFor(MusicPolicy(null, true, 60, Long.MAX_VALUE), "p", "", 60)["storytel_generation"])
        assertFalse(restrictionValueStorable(4L))
        assertFalse(restrictionValueStorable(1.5))
    }

    // Updates and status.

    @Test
    fun `a deferred music update skips its record for the run, the next one goes (M2)`() {
        val music = com.kidslauncher.mdm.server.DownloadRecord(appId = 7, tag = "music-v1.0.0")
        val game = com.kidslauncher.mdm.server.DownloadRecord(appId = 8, tag = "v2")
        val launcher = com.kidslauncher.mdm.server.DownloadRecord(appId = 1, tag = "launcher-v1", isLauncher = true)
        val gates = mapOf(7L to com.kidslauncher.mdm.server.DownloadGate.GO, 8L to com.kidslauncher.mdm.server.DownloadGate.GO, 1L to com.kidslauncher.mdm.server.DownloadGate.GO)
        val all = listOf(music, game, launcher)
        assertEquals(7L, com.kidslauncher.mdm.server.nextDownload(all, gates, emptySet())?.appId)
        assertEquals(8L, com.kidslauncher.mdm.server.nextDownload(all, gates, setOf(7L))?.appId)
        assertEquals("our own update still comes", 1L, com.kidslauncher.mdm.server.nextDownload(all, gates, setOf(7L, 8L))?.appId)
        assertNull(com.kidslauncher.mdm.server.nextDownload(listOf(music), gates, setOf(7L)))
    }

    @Test
    fun `a music app update waits while it plays, a stale playing flag does not hold it`() {
        val now = 1_800_000_000_000L
        assertTrue(installDeferredForPlayback("me.vibb.music", now - 60_000, now))
        assertFalse(installDeferredForPlayback("me.vibb.music", null, now))
        assertFalse(installDeferredForPlayback("com.example.game", now - 60_000, now))
        assertFalse(installDeferredForPlayback("me.vibb.music", now - PLAYING_STALE_MS, now))
    }

    @Test
    fun `music_state - the app's known fields plus the package, version and bridge`() {
        val now = 1_800_000_000_000L
        val report = buildJsonObject {
            put("library_version", "0123456789abcdef"); put("cache_bytes", 5); put("position_ms", 999); put("storytel", "ok")
        }
        val state = musicState("me.vibb.music", 1000000, report, now - 60_000, now)!!
        assertEquals("bound", state["bridge"]!!.let { (it as JsonPrimitive).content })
        assertEquals(setOf("package", "version_code", "bridge", "library_version", "cache_bytes", "storytel"), state.keys)
        assertEquals("unbound", (musicState("me.vibb.music", 1, null, null, now)!!["bridge"] as JsonPrimitive).content)
        assertEquals("unbound", (musicState("me.vibb.music", 1, report, now - REPORT_STALE_MS, now)!!["bridge"] as JsonPrimitive).content)
        assertNull("no music app", musicState(null, null, report, now, now))
        val json = ServerJson.parseToJsonElement(
            ServerJson.encodeToString(StatusReportRequest.serializer(), StatusReportRequest(lockReason = "NONE", kioskEngaged = true, musicState = state)),
        ).jsonObject
        assertEquals("me.vibb.music", (json["music_state"]!!.jsonObject["package"] as JsonPrimitive).content)
        assertTrue(MUSIC_CAPABILITY in com.kidslauncher.mdm.server.STATUS_CAPABILITIES)
    }

    @Test
    fun `music_state end to end - every field of the shared fixture survives the launcher`() {
        val fixture = ServerJson.parseToJsonElement(repo("server/testdata/music_state.json").readText()).jsonObject
        val fromApp = kotlinx.serialization.json.JsonObject(fixture - setOf("package", "version_code", "bridge"))
        val now = 1_800_000_000_000L
        assertEquals(fixture, musicState("me.vibb.music", 1000000, fromApp, now - 1_000, now))
    }

    // The contract with the music app, spelled once (bridge_contract.json, byte-identical in both apps).

    private val contract by lazy {
        ServerJson.parseToJsonElement(javaClass.classLoader!!.getResource("bridge_contract.json")!!.readText()).jsonObject
    }

    private fun strings(key: String) = (contract[key] as kotlinx.serialization.json.JsonArray).map { (it as JsonPrimitive).content }

    @Test
    fun `the bridge's names match the shared contract`() {
        assertEquals(
            "the music app's copy",
            repo("music/app/src/test/resources/bridge_contract.json").readText(),
            javaClass.classLoader!!.getResource("bridge_contract.json")!!.readText(),
        )
        assertEquals((contract["bridge_service"] as JsonPrimitive).content, MusicBridgeService::class.java.name)
        assertEquals((contract["action_bridge_changed"] as JsonPrimitive).content, ACTION_BRIDGE_CHANGED)
        assertEquals(strings("music_packages"), MUSIC_PACKAGES)
        assertTrue(module("src/main/AndroidManifest.xml").readText().contains("\${applicationId}" + (contract["permission_suffix"] as JsonPrimitive).content))
        val types = (contract["restrictions"] as kotlinx.serialization.json.JsonObject).mapValues { (it.value as JsonPrimitive).content }
        val written = restrictionsFor(MusicPolicy(null, true, null, 1), "me.vibb.launcher", "nb", null)
        assertEquals(types.keys, written.keys)
        for ((key, value) in written) {
            val type = when (value) {
                is Int -> "int"
                is Boolean -> "boolean"
                is String -> "string"
                else -> "?"
            }
            assertEquals(key, types[key], type)
        }
        assertEquals(strings("report_state"), listOf(REPORT, HELD_FILES, HELD_SHA256))
        assertEquals(
            strings("now_playing"),
            listOf(MusicNowPlaying.ENTRY, MusicNowPlaying.ITEM, MusicNowPlaying.POSITION_MS, MusicNowPlaying.DURATION_MS, MusicNowPlaying.PLAYING, MusicNowPlaying.ART),
        )
        // The call rule's session commands (design 26a §6.7, qa-26a-step3b-code H1).
        val calls = (contract["call_commands"] as kotlinx.serialization.json.JsonObject).mapValues { (it.value as JsonPrimitive).content }
        assertEquals(calls["hold"], MUSIC_CALL_HOLD)
        assertEquals(calls["release"], MUSIC_CALL_RELEASE)
        // Home's card opens the player and continues (design 21 step 4).
        val player = (contract["player"] as kotlinx.serialization.json.JsonObject).mapValues { (it.value as JsonPrimitive).content }
        assertEquals(
            player,
            mapOf(
                "activity" to MUSIC_PLAYER_ACTIVITY,
                "extra_open" to MUSIC_EXTRA_OPEN,
                "open_player" to MUSIC_OPEN_PLAYER,
                "extra_resume" to MUSIC_EXTRA_RESUME,
            ),
        )
    }

    @Test
    fun `every bridge method checks its caller first`() {
        val source = module("src/main/java/com/kidslauncher/mdm/music/MusicBridgeService.kt").readText()
        val binder = source.substringAfter("object : IMusicBridge.Stub() {").substringBefore("\n    }\n")
        val methods = Regex("override fun (\\w+)\\([^)]*\\)[^{]*\\{\\s*([^\\n]*)").findAll(binder).toList()
        assertEquals("all of IMusicBridge's methods", 11, methods.size)
        for (m in methods) assertEquals("${m.groupValues[1]} must call check() first", "check()", m.groupValues[2].trim())
    }
}
