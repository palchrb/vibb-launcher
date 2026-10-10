package com.kidslauncher.mdm.music

import com.kidslauncher.mdm.server.ServerJson
import com.kidslauncher.mdm.server.dto.StatusReportRequest
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

    @Test
    fun `the policy's music object, read leniently`() {
        val policy = musicPolicy(buildJsonObject {
            put("library_version", "0123456789abcdef"); put("mobile_data", true); put("volume_cap_pct", 70); put("storytel_generation", 3)
        })!!
        assertEquals(MusicPolicy("0123456789abcdef", true, 70, 3), policy)
        assertEquals(MusicPolicy(null, false, null, 0), musicPolicy(buildJsonObject { put("library_version", JsonPrimitive(null as String?)) }))
        assertNull("an older server", musicPolicy(null))
        assertNull(musicPolicy(JsonPrimitive(5)))
        assertNull("not a version", musicPolicy(buildJsonObject { put("library_version", "x") })!!.libraryVersion)
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
    fun `own files - one ahead of the handover, never one handed over or missing`() {
        assertEquals(1L, nextOwnFile(head, emptyMap(), emptySet())?.id)
        assertNull("one waits for the music app", nextOwnFile(head, emptyMap(), setOf(1L)))
        assertEquals(3L, nextOwnFile(head, mapOf(1L to "a".repeat(64)), emptySet())?.id)
        assertEquals("a new hash is a new file", 1L, nextOwnFile(head, mapOf(1L to "f".repeat(64)), emptySet())?.id)
        assertNull(nextOwnFile(head, mapOf(1L to "a".repeat(64), 3L to "c".repeat(64)), emptySet()))
        assertEquals(setOf(9L, 3L), readyFilesToDelete(head, mapOf(1L to "a".repeat(64), 9L to "x", 3L to "d".repeat(64))))
    }

    // The managed config.

    @Test
    fun `the managed config carries the keys the music app reads`() {
        val r = restrictionsFor(MusicPolicy(null, true, 60, 4), "me.vibb.launcher", "nb")
        assertEquals(
            mapOf("v" to 1, "managed" to true, "launcher_package" to "me.vibb.launcher", "language" to "nb", "mobile_data" to true, "library_version" to "", "storytel_generation" to 4L),
            r,
        )
    }

    // Updates and status.

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
}
