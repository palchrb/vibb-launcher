package com.kidslauncher.mdm.music

import com.kidslauncher.mdm.server.ServerJson
import com.kidslauncher.mdm.server.dto.StatusReportRequest
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
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
    fun `only a music app's update nudges it`() {
        assertEquals("me.vibb.music", musicNudgeOnChange("me.vibb.music"))
        assertEquals("me.vibb.music.debug", musicNudgeOnChange("me.vibb.music.debug"))
        assertNull(musicNudgeOnChange("org.example.chat"))
        assertNull(musicNudgeOnChange(null))
    }

    @Test
    fun `the bridge's AIDL is byte-identical in both apps`() {
        for (name in listOf("IMusicBridge.aidl", "IStorytelCallback.aidl", "ISpotifyTokenCallback.aidl", "IStorytelTokenCallback.aidl")) {
            val ours = module("src/main/aidl/me/vibb/bridge/$name")
            val theirs = repo("music/app/src/main/aidl/me/vibb/bridge/$name")
            assertEquals("$name differs from music/'s copy", theirs.readText(), ours.readText())
        }
        // Appended, never reordered (the transaction codes an installed music app or launcher uses follow the
        // order; qa-26c-step3-code L2): the whole sequence, a new method added at the end.
        val methods = Regex("^\\s+(?:oneway )?\\S+ (\\w+)\\(", RegexOption.MULTILINE)
            .findAll(module("src/main/aidl/me/vibb/bridge/IMusicBridge.aidl").readText()).map { it.groupValues[1] }.toList()
        assertEquals(
            listOf(
                "version", "openLibrary", "openCover", "openFile", "releaseFile", "getStorytelLogin", "reportState",
                "setNowPlaying", "setForeground", "listingVersion", "openListing", "wantArt", "getSpotifyToken",
                "getStorytelToken",
            ),
            methods,
        )
    }

    @Test
    fun `Storytel's token answers are the server's and the music app's (design 58 §8)`() {
        val ours = javaClass.classLoader!!.getResource("storytel_token_answers.json")!!.readText()
        assertEquals("the music app's copy", repo("music/app/src/test/resources/storytel_token_answers.json").readText(), ours)
        assertEquals("the server's copy", repo("server/testdata/storytel_token_answers.json").readText(), ours)
        assertTrue("the courier is a capability", STORYTEL_JWT_CAPABILITY in com.kidslauncher.mdm.server.STATUS_CAPABILITIES)
    }

    @Test
    fun `Spotify's host list is the music app's and the server's (26c §8 item 5)`() {
        val ours = javaClass.classLoader!!.getResource("spotify_hosts.json")!!.readText()
        assertEquals("the music app's copy", repo("music/app/src/test/resources/spotify_hosts.json").readText(), ours)
        assertEquals("the server's copy", repo("server/testdata/spotify_hosts.json").readText(), ours)
    }

    @Test
    fun `the policy's spotify - true only as a JSON true, whatever else the music object holds (26c §6_2)`() {
        fun with(value: kotlinx.serialization.json.JsonElement) = buildJsonObject {
            put("library_version", JsonNull); put("mobile_data", false); put("storytel_generation", 0); put("spotify", value)
        }
        assertTrue(musicSpotify(with(JsonPrimitive(true))))
        assertFalse(musicSpotify(with(JsonPrimitive(false))))
        assertFalse("a string", musicSpotify(with(JsonPrimitive("true"))))
        assertFalse(musicSpotify(with(JsonNull)))
        assertFalse("an older server", musicSpotify(policyJson()))
        assertFalse(musicSpotify(null))
        assertFalse(musicSpotify(JsonPrimitive(true)))
        assertTrue(
            "read on its own: a malformed library_version doesn't drop it",
            musicSpotify(buildJsonObject { put("library_version", 5); put("spotify", true) }),
        )
        // The music object as before: `spotify` changes nothing the music sync reads.
        val withSpotify = buildJsonObject { policyJson().forEach { (k, v) -> put(k, v) }; put("spotify", true) }
        assertEquals(musicPolicy(policyJson()), musicPolicy(withSpotify))
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
        assertEquals(MusicPolicy("0123456789abcdef", true, true, 70, DEFAULT_STORAGE_LIMIT_GB, 3), musicPolicy(policyJson()))
        assertEquals("JSON null: nothing ticked", MusicPolicy(null, true, true, 70, DEFAULT_STORAGE_LIMIT_GB, 3), musicPolicy(policyJson(version = JsonNull)))
        assertNull("a longer version", musicPolicy(policyJson(version = JsonPrimitive("0123456789abcdef0123"))))
        assertNull("a number", musicPolicy(policyJson(version = JsonPrimitive(5))))
        assertNull("a generation that isn't a number", musicPolicy(policyJson(generation = JsonPrimitive("x"))))
        assertNull("a negative generation", musicPolicy(policyJson(generation = JsonPrimitive(-1))))
        assertNull("library_version missing", musicPolicy(buildJsonObject { put("mobile_data", true); put("storytel_generation", 0) }))
        assertNull("an older server", musicPolicy(null))
        assertNull(musicPolicy(JsonPrimitive(5)))
    }

    /** Design 30's music object as the server sends it from step 1 on (`policy_json_keys_snapshot`). */
    private fun policy30(
        mobile: Boolean = false,
        stream: kotlinx.serialization.json.JsonElement? = JsonPrimitive(false),
        download: kotlinx.serialization.json.JsonElement? = JsonPrimitive(false),
        limit: kotlinx.serialization.json.JsonElement? = JsonPrimitive(2),
    ) = buildJsonObject {
        put("library_version", JsonNull); put("mobile_data", mobile)
        stream?.let { put("stream_mobile_data", it) }
        download?.let { put("download_mobile_data", it) }
        put("volume_cap_pct", JsonNull)
        limit?.let { put("storage_limit_gb", it) }
        put("storytel_generation", 0)
    }

    @Test
    fun `the policy's music object - two switches, an older server's mobile_data for both, a malformed switch keeps what is there`() {
        assertEquals(MusicPolicy(null, false, false, null, 2, 0), musicPolicy(policy30()))
        val streamOnly = musicPolicy(policy30(stream = JsonPrimitive(true)))!!
        assertTrue(streamOnly.streamMobileData)
        assertFalse(streamOnly.downloadMobileData)
        assertFalse("the old key is both", streamOnly.mobileData)
        val downloadOnly = musicPolicy(policy30(download = JsonPrimitive(true)))!!
        assertFalse(downloadOnly.streamMobileData)
        assertTrue(downloadOnly.downloadMobileData)
        val both = musicPolicy(policy30(mobile = true, stream = JsonPrimitive(true), download = JsonPrimitive(true)))!!
        assertTrue(both.streamMobileData && both.downloadMobileData && both.mobileData)
        // The new keys win over mobile_data; mobile_data is only the fallback.
        assertEquals(MusicPolicy(null, false, false, null, 2, 0), musicPolicy(policy30(mobile = true)))
        // An older server: no new keys - both switches are its one switch, the limit 2 GB.
        assertEquals(MusicPolicy(null, true, true, null, 2, 0), musicPolicy(policy30(mobile = true, stream = null, download = null, limit = null)))
        assertEquals(MusicPolicy(null, false, false, null, 2, 0), musicPolicy(policy30(stream = null, download = null, limit = null)))
        assertEquals("one key missing falls back alone", MusicPolicy(null, true, false, null, 2, 0), musicPolicy(policy30(mobile = true, stream = null)))
        for (bad in listOf(JsonPrimitive("true"), JsonPrimitive(1), JsonNull, buildJsonObject { }, buildJsonArray { })) {
            assertNull("stream_mobile_data = $bad", musicPolicy(policy30(stream = bad)))
            assertNull("download_mobile_data = $bad", musicPolicy(policy30(download = bad)))
        }
        assertNull("mobile_data is still required", musicPolicy(kotlinx.serialization.json.JsonObject(policy30() - "mobile_data")))
    }

    @Test
    fun `the storage limit - absent is 2 GB, null is none, a stray value is 2 GB`() {
        assertEquals("an older server", DEFAULT_STORAGE_LIMIT_GB, musicPolicy(policy30(limit = null))!!.storageLimitGb)
        assertEquals(2, DEFAULT_STORAGE_LIMIT_GB)
        assertNull("no limit", musicPolicy(policy30(limit = JsonNull))!!.storageLimitGb)
        for (gb in listOf(1, 2, 5, 10, MAX_STORAGE_LIMIT_GB)) assertEquals(gb, musicPolicy(policy30(limit = JsonPrimitive(gb)))!!.storageLimitGb)
        assertEquals(1000, MAX_STORAGE_LIMIT_GB)
        // qa-30-s2-code L1: lenient like the server's storage_limit and the music app - a stray value
        // reads as the default and never refuses the object (the cap, the courier and Storytel ride on it).
        for (stray in listOf(
            JsonPrimitive(0), JsonPrimitive(-2), JsonPrimitive(1001), JsonPrimitive(2.5), JsonPrimitive(0.5), JsonPrimitive("2"), JsonPrimitive(true),
            JsonPrimitive(10_000_000_000L), buildJsonObject { }, buildJsonArray { },
        )) {
            val read = musicPolicy(policy30(limit = stray, stream = JsonPrimitive(true)))
            assertEquals("storage_limit_gb = $stray", MusicPolicy(null, true, false, null, DEFAULT_STORAGE_LIMIT_GB, 0), read)
            assertEquals(DEFAULT_STORAGE_LIMIT_GB, storageLimitGb(stray))
        }
        assertEquals(DEFAULT_STORAGE_LIMIT_GB, storageLimitGb(null))
        assertNull(storageLimitGb(JsonNull))
    }

    @Test
    fun `the shared library snapshot as the launcher reads it`() {
        val head = libraryHead(repo("server/testdata/music_library.json").readText())!!
        assertEquals(4, head.covers.size)
        assertEquals(listOf(1L, 2L), head.files.map { it.id })
        assertTrue(head.files[1].missing)
        // A Storytel entry's listing rides the courier like any other (design 26b §2.7), and so do
        // Spotify's - a list-mode playlist and a context-mode artist (design 26c §6.4): no launcher change.
        assertEquals(
            "own entries have no listing",
            mapOf(1L to null, 2L to "0123456789abcdef", 5L to "00ff00ff00ff00ff", 6L to "11ee11ee11ee11ee", 7L to "22dd22dd22dd22dd"),
            head.listings,
        )
        assertEquals(listOf(5L, 6L, 7L), listingsToFetch(head, mapOf(2L to "0123456789abcdef")))
        assertNull(libraryHead("{\"v\": 2}"))
    }

    @Test
    fun `the shared listing snapshot's version`() {
        assertEquals("a8146790d70b89a3", listingVersionOf(repo("server/testdata/music_listing.json").readText()))
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

    // Episode pictures (design 27 §2.2).

    private fun pic(n: Int) = "%064x".format(n)

    @Test
    fun `wantArt - only 64-hex hashes, each once, at most 500 now and 1000 in all`() {
        val w = wantArtLists(listOf(pic(1), "nope", null, pic(1), pic(2)), listOf(pic(2), pic(3), "A".repeat(64)))
        assertEquals(listOf(pic(1), pic(2)), w.now)
        assertEquals("now wins", listOf(pic(3)), w.later)
        val page = wantArtLists(listOf(pic(1)), (2..1000).map { pic(it) })
        assertEquals("a page of 999 pictures goes whole", 999, page.later.size)
        assertEquals(1000, WANT_ART_MAX)
        val big = wantArtLists((1..600).map { pic(it) }, (601..3000).map { pic(it) })
        assertEquals(WANT_ART_NOW_MAX, big.now.size)
        assertEquals(WANT_ART_MAX, big.now.size + big.later.size)
        assertEquals(WantArt.NONE, wantArtLists(null, null))
    }

    @Test
    fun `art gates - now is streaming's, later Wi-Fi only, never roaming (2026-10-19, qa-art-asap-code M1)`() {
        val wifi = com.kidslauncher.mdm.server.NetCaps(internet = true, notMetered = true, notRoaming = true, physical = true)
        val mobile = wifi.copy(notMetered = false)
        val roaming = mobile.copy(notRoaming = false)
        // (network, stream switch) -> (now, later)
        assertEquals("unmetered: both", ArtGates(now = true, later = true), artGates(wifi, false))
        assertEquals("metered, streaming on: now only", ArtGates(now = true, later = false), artGates(mobile, true))
        assertEquals("metered, streaming off: nothing", ArtGates(now = false, later = false), artGates(mobile, false))
        assertEquals("roaming: nothing, whatever the switch", ArtGates(now = false, later = false), artGates(roaming, true))
        assertEquals("no network", ArtGates(now = false, later = false), artGates(null, true))
        assertEquals("no physical transport", ArtGates(now = false, later = false), artGates(wifi.copy(physical = false), true))
        assertEquals("no internet", ArtGates(now = false, later = false), artGates(wifi.copy(internet = false), true))
        assertEquals("no readable music object: Wi-Fi only", ArtGates(now = false, later = false), artGatesOn(mobile, null))
        // The downloads switch opens nothing on mobile data: a want set stored on Wi-Fi fetches none of its bulk there.
        val both = musicPolicy(policy30(stream = JsonPrimitive(true), download = JsonPrimitive(true)))!!
        assertEquals("metered, both switches on: now only", ArtGates(now = true, later = false), artGatesOn(mobile, both))
        assertEquals("roaming, both switches on: nothing", ArtGates(now = false, later = false), artGatesOn(roaming, both))
        assertEquals(ArtGates(now = true, later = true), artGatesOn(wifi, musicPolicy(policy30())!!))
    }

    @Test
    fun `art to fetch - each list by its gate, now first, never held or backed off`() {
        val want = WantArt(listOf(pic(1), pic(2)), listOf(pic(3), pic(4)))
        val now = 1_800_000_000_000L
        fun fetch(gates: ArtGates = ArtGates(now = true, later = true), has: Set<String> = emptySet(), failures: Map<String, ArtFailure> = emptyMap(), max: Int = 50) =
            artToFetch(want, has, failures, gates, max, now)
        assertEquals("both, now first", listOf(pic(1), pic(2), pic(3), pic(4)), fetch())
        assertEquals("now only", listOf(pic(1), pic(2)), fetch(ArtGates(now = true, later = false)))
        assertEquals("later only", listOf(pic(3), pic(4)), fetch(ArtGates(now = false, later = true)))
        assertEquals("neither", emptyList<String>(), fetch(ArtGates(now = false, later = false)))
        assertEquals("held ones aren't fetched", listOf(pic(2), pic(4)), fetch(has = setOf(pic(1), pic(3))))
        assertEquals("at most max per run", listOf(pic(1), pic(2), pic(3)), fetch(max = 3))
        val failed = mapOf(pic(1) to ArtFailure(2, now - 60_000), pic(2) to ArtFailure(1, now - 60_000), pic(3) to ArtFailure(5, now - ART_BACKOFF_MS))
        assertEquals("two failures wait a day, one doesn't, a day later it is tried again", listOf(pic(2), pic(3), pic(4)), fetch(failures = failed))
    }

    @Test
    fun `art to fetch follows the streaming switch, the rest only on Wi-Fi`() {
        val want = WantArt(listOf(pic(1)), listOf(pic(2)))
        val now = 1_800_000_000_000L
        // artToFetchOn: the phone's network and the policy, as artFetchable passes them (qa-30-s2-code L2).
        val metered = com.kidslauncher.mdm.server.NetCaps(internet = true, notMetered = false, notRoaming = true, physical = true)
        fun fetch(music: MusicPolicy, caps: com.kidslauncher.mdm.server.NetCaps? = metered) = artToFetchOn(want, emptySet(), emptyMap(), caps, music, 50, now)
        val streamOnly = musicPolicy(policy30(stream = JsonPrimitive(true)))!!
        val downloadOnly = musicPolicy(policy30(download = JsonPrimitive(true)))!!
        val neither = musicPolicy(policy30())!!
        assertEquals("streaming alone: now only", listOf(pic(1)), fetch(streamOnly))
        assertEquals("downloads alone: nothing on mobile data (M1)", emptyList<String>(), fetch(downloadOnly))
        assertEquals("neither switch on mobile data: nothing", emptyList<String>(), fetch(neither))
        assertEquals("roaming: nothing", emptyList<String>(), fetch(downloadOnly, metered.copy(notRoaming = false)))
        assertEquals("Wi-Fi: both without either switch", listOf(pic(1), pic(2)), fetch(neither, metered.copy(notMetered = true)))
        assertEquals("no network: nothing", emptyList<String>(), fetch(downloadOnly, null))
        // The glue is one call, pinned whole: nothing between the policy and artToFetchOn.
        val courier = module("src/main/java/com/kidslauncher/mdm/music/MusicSync.kt").readText()
        val fetchable = courier.substringAfter("fun artFetchable(app: Context, want: WantArt, music: MusicPolicy): List<String> =").substringBefore("\n\n").trim()
        assertEquals(
            "artToFetchOn(want, MusicStore.coversDir(app).list().orEmpty().toSet(), MusicStore.artFailures(app), AppDownloads.currentCaps(app), music, ART_PER_RUN, System.currentTimeMillis())",
            fetchable,
        )
    }

    @Test
    fun `asap - the now pictures at once where streaming is allowed, never later, held or backed off`() {
        val want = WantArt((1..120).map { pic(it) }, listOf(pic(200), pic(201)))
        val now = 1_800_000_000_000L
        val wifi = com.kidslauncher.mdm.server.NetCaps(internet = true, notMetered = true, notRoaming = true, physical = true)
        val mobile = wifi.copy(notMetered = false)
        val stream = musicPolicy(policy30(stream = JsonPrimitive(true)))!!
        val neither = musicPolicy(policy30())!!
        assertEquals("Wi-Fi: the first round of now, the window first, no later", (1..ART_PER_RUN).map { pic(it) }, nowArtToFetch(want, emptySet(), emptyMap(), wifi, neither, now))
        assertEquals("mobile data with streaming on", (2..ART_PER_RUN + 1).map { pic(it) }, nowArtToFetch(want, setOf(pic(1)), emptyMap(), mobile, stream, now))
        assertEquals("mobile data with streaming off: nothing", emptyList<String>(), nowArtToFetch(want, emptySet(), emptyMap(), mobile, neither, now))
        assertEquals("roaming: nothing", emptyList<String>(), nowArtToFetch(want, emptySet(), emptyMap(), mobile.copy(notRoaming = false), stream, now))
        assertEquals("backed off", pic(2), nowArtToFetch(want, emptySet(), mapOf(pic(1) to ArtFailure(2, now - 1)), wifi, neither, now).first())
        // The glue: wantArt's arrival fetches them before it asks the courier, beside a running courier run.
        val bridge = module("src/main/java/com/kidslauncher/mdm/music/MusicBridgeService.kt").readText()
        val changed = bridge.substringAfter("private suspend fun wantArtChanged(").substringBefore("\n    }\n")
        assertTrue(changed, changed.indexOf("MusicCourier.fetchNowArt(app, music)") in 0 until changed.indexOf("MusicCourier.request(app, music)"))
        assertTrue(bridge.contains("scope.launch { wantArtChanged(app, want) }"))
        val sync = module("src/main/java/com/kidslauncher/mdm/music/MusicSync.kt").readText()
        assertTrue(sync.contains("val hashes = nowArtToFetch(MusicStore.wantArt(app), MusicStore.coversDir(app).list().orEmpty().toSet(), MusicStore.artFailures(app), AppDownloads.currentCaps(app), music, System.currentTimeMillis())"))
    }

    @Test
    fun `asap - the courier fetches in rounds of 100 while more are fetchable, nudging after each`() {
        val wanted = (1..250).map { pic(it) }.toMutableList()
        val has = mutableSetOf<String>()
        var unmetered = true
        val rounds = mutableListOf<Int>()
        var nudges = 0
        fun next(): List<String> = if (unmetered) wanted.filter { it !in has }.take(ART_PER_RUN) else emptyList()
        val stored = kotlinx.coroutines.runBlocking {
            fetchArtPaced(::next, { has += it; true }, { nudges++; rounds += has.size })
        }
        assertEquals(PacedArt(250, more = false), stored)
        assertEquals("three rounds: 100, 100, 50", listOf(100, 200, 250), rounds)
        assertEquals(3, nudges)
        // A metered network ends it between rounds.
        has.clear()
        val ended = kotlinx.coroutines.runBlocking { fetchArtPaced(::next, { has += it; if (has.size == 100) unmetered = false; true }) }
        assertEquals(PacedArt(100, more = false), ended)
        // A round that stores nothing (the server down) ends it, never a loop.
        unmetered = true
        var tries = 0
        val none = kotlinx.coroutines.runBlocking { fetchArtPaced(::next, { tries++; null }) }
        assertEquals(PacedArt(0, more = false), none)
        assertEquals("one round tried", ART_PER_RUN, tries)
        // The glue: syncArt is the paced fetch over artFetchable.
        val sync = module("src/main/java/com/kidslauncher/mdm/music/MusicSync.kt").readText()
        assertTrue(sync.contains("next = { artFetchable(app, MusicStore.wantArt(app), music) },"))
        assertTrue(sync.contains("roundDone = { MusicSync.nudge(app) },"))
        assertEquals(100, ART_PER_RUN)
    }

    /** qa-art-asap-code M2: a run stops after its rounds with more left, and the courier runs again. */
    @Test
    fun `asap - a run fetches at most three rounds, then the courier runs again`() {
        val wanted = (1..1000).map { pic(it) }
        val has = mutableSetOf<String>()
        fun next(): List<String> = wanted.filter { it !in has }.take(ART_PER_RUN)
        val first = kotlinx.coroutines.runBlocking { fetchArtPaced(::next, { has += it; true }) }
        assertEquals(PacedArt(ART_PER_RUN * ART_ROUNDS_PER_RUN, more = true), first)
        val runs = mutableListOf(first)
        while (runs.last().more) runs += kotlinx.coroutines.runBlocking { fetchArtPaced(::next, { has += it; true }) }
        assertEquals("1000 pictures over 4 runs: 300, 300, 300, 100", listOf(300, 300, 300, 100), runs.map { it.stored })
        assertEquals(1000, has.size)
        // The glue: more left re-queues the courier, which fetches the library and the listings first, then the art.
        val sync = module("src/main/java/com/kidslauncher/mdm/music/MusicSync.kt").readText()
        val art = sync.substringAfter("private suspend fun syncArt(").substringBefore("\n    }\n")
        assertTrue(art, art.contains("if (paced.more) runAgain(music)"))
        assertTrue(sync.contains("synchronized(this) { if (again == null) again = music }"))
        val fetchAll = sync.substringAfter("private suspend fun fetchAll(").substringBefore("\n    }\n")
        assertTrue(fetchAll.indexOf("fetchLibrary(") in 0 until fetchAll.indexOf("syncArt(app, server, music)"))
    }

    /** qa-art-asap-code L5: the now fetch and the courier never GET one picture twice at once. */
    @Test
    fun `asap - a picture in flight isn't fetched again beside it`() {
        val flight = ArtInFlight()
        assertTrue(flight.start(pic(1)))
        assertFalse("the second fetch leaves it", flight.start(pic(1)))
        assertTrue("another picture goes", flight.start(pic(2)))
        flight.done(pic(1))
        assertTrue("done: it may go again", flight.start(pic(1)))
        val sync = module("src/main/java/com/kidslauncher/mdm/music/MusicSync.kt").readText()
        val fetch = sync.substringAfter("private suspend fun fetchArt(").substringBefore("\n    }\n")
        assertTrue(fetch, fetch.contains("if (!inFlight.start(hash)) return null"))
        assertTrue(fetch, fetch.contains("inFlight.done(hash)"))
    }

    @Test
    fun `art to delete - keeps library covers and the want set`() {
        val want = WantArt(listOf(pic(1)), listOf(pic(2)))
        assertEquals(sortedSetOf(pic(4), "junk"), artToDelete(setOf(pic(1), pic(2), pic(3), pic(4), "junk"), setOf(pic(3)), want))
        assertEquals("nothing wanted: only covers stay", sortedSetOf(pic(1), pic(2)), artToDelete(setOf(pic(1), pic(2), pic(3)), setOf(pic(3)), WantArt.NONE))
        val many = wantArtLists((1..500).map { pic(it) }, (501..1000).map { pic(it) })
        assertEquals("every wanted picture of a 1000-hash set is kept", emptySet<String>(), artToDelete(many.all, emptySet(), many))
        // qa-art-asap-code L6: off Wi-Fi the bulk fetched on Wi-Fi stays until the music app copies it.
        val now = 1_800_000_000_000L
        val times = mapOf(pic(5) to now - 60_000, pic(6) to now - ART_HOLD_MS - 1)
        val held = artHeld(setOf(pic(5), pic(6)), unmetered = false, nowMs = now) { times.getValue(it) }
        assertEquals("young ones only", setOf(pic(5)), held)
        assertEquals(sortedSetOf(pic(6)), artToDelete(setOf(pic(5), pic(6)), emptySet(), WantArt.NONE, held))
        assertEquals("on Wi-Fi nothing is held", emptySet<String>(), artHeld(setOf(pic(5)), unmetered = true, nowMs = now) { times.getValue(it) })
    }

    @Test
    fun `syncCovers no longer deletes wanted art, and the want set goes with the library`() {
        val sync = module("src/main/java/com/kidslauncher/mdm/music/MusicSync.kt").readText()
        val covers = sync.substringAfter("private suspend fun syncCovers(").substringBefore("\n    }\n")
        assertTrue(covers, covers.contains("artToDelete(present, head.covers, MusicStore.wantArt(app), heldArt(app, present))"))
        assertFalse(covers, covers.contains("!in head.covers }.forEach { it.delete() }"))
        assertTrue("syncArt runs after syncCovers", sync.indexOf("syncArt(app, server, music)") > sync.indexOf("syncCovers(app, server, head) ||"))
        val noLibrary = sync.substringAfter("if (head == null) {").substringBefore("return changed")
        assertTrue(noLibrary.contains("MusicStore.clearWantArt(app)"))
        val bridge = module("src/main/java/com/kidslauncher/mdm/music/MusicBridgeService.kt").readText()
        assertTrue("a run only when something is fetchable now (M6)", bridge.contains("if (MusicCourier.artFetchable(app, want, music).isNotEmpty()) MusicCourier.request(app, music)"))
    }

    @Test
    fun `the held nudge - once per set of held now pictures, never a loop`() {
        val want = WantArt(listOf(pic(1), pic(2)), listOf(pic(3)))
        assertEquals(setOf(pic(1)), heldNudge(want, emptySet()) { it == pic(1) })
        assertNull("the same set again: no nudge", heldNudge(want, setOf(pic(1))) { it == pic(1) })
        assertNull("nothing held", heldNudge(want, emptySet()) { false })
        assertNull("a held later picture doesn't count", heldNudge(want, emptySet()) { it == pic(3) })
        assertEquals(setOf(pic(1), pic(2)), heldNudge(want, setOf(pic(1))) { true })
    }

    @Test
    fun `a 5xx is no failure of the picture, a 4xx is (code QA L11)`() {
        assertEquals(true, artAnswer(200))
        assertEquals(false, artAnswer(404))
        assertEquals(false, artAnswer(410))
        assertNull(artAnswer(502))
        assertNull(artAnswer(503))
        val sync = module("src/main/java/com/kidslauncher/mdm/music/MusicSync.kt").readText()
        assertTrue(sync.contains("val answer = artAnswer(response.code())"))
    }

    @Test
    fun `the want set is stored in the binder call, so the newest arrives last (code QA L9)`() {
        val bridge = module("src/main/java/com/kidslauncher/mdm/music/MusicBridgeService.kt").readText()
        val call = bridge.substringAfter("override fun wantArt(").substringBefore("\n        }\n")
        assertTrue(call, call.indexOf("MusicStore.setWantArt(app, want)") in 0 until call.indexOf("scope.launch"))
        assertFalse(bridge.substringAfter("private suspend fun wantArtChanged(").substringBefore("\n    }\n").contains("setWantArt"))
    }

    @Test
    fun `the want set's caps are the shared contract's`() {
        val caps = (contract["want_art"] as kotlinx.serialization.json.JsonObject).mapValues { (it.value as JsonPrimitive).content.toInt() }
        assertEquals(mapOf("max" to WANT_ART_MAX, "now_max" to WANT_ART_NOW_MAX), caps)
    }

    // The managed config.

    @Test
    fun `the managed config carries the keys the music app reads`() {
        val r = restrictionsFor(MusicPolicy(null, true, true, 60, 5, 4), "me.vibb.launcher", "nb", 60)
        assertEquals(
            mapOf(
                "v" to 1, "managed" to true, "launcher_package" to "me.vibb.launcher", "language" to "nb", "mobile_data" to true, "library_version" to "",
                "storytel_generation" to 4, "volume_cap_pct" to 60, "stream_mobile_data" to true, "download_mobile_data" to true, "storage_limit_gb" to 5,
            ),
            r,
        )
    }

    @Test
    fun `the managed config carries both switches, the limit and mobile_data as both`() {
        fun written(stream: Boolean, download: Boolean, limit: Int?) =
            restrictionsFor(MusicPolicy(null, stream, download, null, limit, 0), "p", "", null)
        for ((stream, download) in listOf(true to false, false to true, true to true, false to false)) {
            val r = written(stream, download, 2)
            assertEquals("$stream/$download", stream, r[MusicRestrictions.STREAM_MOBILE_DATA])
            assertEquals("$stream/$download", download, r[MusicRestrictions.DOWNLOAD_MOBILE_DATA])
            assertEquals("an older music app reads both: $stream/$download", stream && download, r[MusicRestrictions.MOBILE_DATA])
        }
        assertEquals(2, written(true, true, 2)[MusicRestrictions.STORAGE_LIMIT_GB])
        assertEquals(10, written(true, true, 10)[MusicRestrictions.STORAGE_LIMIT_GB])
        assertEquals("no limit is 0", 0, written(true, true, null)[MusicRestrictions.STORAGE_LIMIT_GB])
        assertEquals(0, storageLimitRestriction(null))
        assertEquals(1000, storageLimitRestriction(1000))
        assertEquals(
            listOf("stream_mobile_data", "download_mobile_data", "storage_limit_gb"),
            listOf(MusicRestrictions.STREAM_MOBILE_DATA, MusicRestrictions.DOWNLOAD_MOBILE_DATA, MusicRestrictions.STORAGE_LIMIT_GB),
        )
        // End to end from the policy: an older server's mobile_data reaches the new keys as both, the limit 2 GB.
        val old = restrictionsFor(musicPolicy(policy30(mobile = true, stream = null, download = null, limit = null))!!, "p", "", null)
        assertEquals(listOf(true, true, true, 2), listOf("mobile_data", "stream_mobile_data", "download_mobile_data", "storage_limit_gb").map { old[it] })
    }

    @Test
    fun `the managed config carries the volume cap in force - 0 when none, lifted with the override (design 26a §5_3)`() {
        val policy = MusicPolicy(null, true, true, 70, 2, 4)
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
        for (policy in listOf(MusicPolicy("0123456789abcdef", false, false, null, null, 0), MusicPolicy(null, true, true, 60, 1000, Long.MAX_VALUE))) {
            for ((key, value) in restrictionsFor(policy, "me.vibb.launcher", "", policy.volumeCapPct)) {
                assertTrue("$key is a ${value.javaClass.simpleName}", restrictionValueStorable(value))
            }
        }
        assertEquals("a huge generation is clamped", Int.MAX_VALUE, restrictionsFor(MusicPolicy(null, true, true, 60, 2, Long.MAX_VALUE), "p", "", 60)["storytel_generation"])
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
            put("library_version", "0123456789abcdef"); put("cache_bytes", 5); put("position_ms", 999); put("storytel", "ok"); put("spotify", "none")
            put("sonos_rooms", buildJsonArray {
                add(buildJsonObject { put("uid", "RINCON_000E58A0B1C201400"); put("name", "Stua") })
            })
            put("now_playing", "secret")
        }
        val state = musicState("me.vibb.music", 1000000, report, now - 60_000, now)!!
        assertEquals("bound", state["bridge"]!!.let { (it as JsonPrimitive).content })
        assertEquals(setOf("package", "version_code", "bridge", "library_version", "cache_bytes", "storytel", "spotify", "sonos_rooms"), state.keys)
        assertEquals("the rooms the music app knows pass as they are (design 26a §5.5)", report["sonos_rooms"], state["sonos_rooms"])
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

    /** Design 30 §1.3/§1.10 (M4): a step 3a music app's two fields pass, 0 included; an older app sends neither. */
    @Test
    fun `music_state passes audio_bytes and extra_bytes`() {
        val now = 1_800_000_000_000L
        val report = buildJsonObject { put("cache_bytes", 5); put("audio_bytes", 4); put("extra_bytes", 0) }
        val state = musicState("me.vibb.music", 1000000, report, now - 60_000, now)!!
        assertEquals(JsonPrimitive(4), state["audio_bytes"])
        assertEquals("0 is sent and passed: it marks a phone that reads both switches", JsonPrimitive(0), state["extra_bytes"])
        val older = musicState("me.vibb.music", 1000000, buildJsonObject { put("cache_bytes", 5) }, now - 60_000, now)!!
        assertFalse(older.containsKey("audio_bytes") || older.containsKey("extra_bytes"))
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
        val written = restrictionsFor(MusicPolicy(null, true, false, null, 2, 1), "me.vibb.launcher", "nb", null)
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
            listOf(MusicNowPlaying.ENTRY, MusicNowPlaying.ITEM, MusicNowPlaying.POSITION_MS, MusicNowPlaying.DURATION_MS, MusicNowPlaying.PLAYING, MusicNowPlaying.ART, MusicNowPlaying.CONTROLS),
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
        // The Spotify token's statuses and the launcher's wait (design 26c §6.1, §8 item 2).
        val spotify = contract["spotify_token"] as kotlinx.serialization.json.JsonObject
        assertEquals((spotify["statuses"] as kotlinx.serialization.json.JsonArray).map { (it as JsonPrimitive).content }, SPOTIFY_TOKEN_STATUSES)
        assertEquals((spotify["timeout_ms"] as JsonPrimitive).content.toLong(), SPOTIFY_TOKEN_TIMEOUT_MS)
        // The Storytel session's statuses and the launcher's wait (design 58 §3).
        val storytel = contract["storytel_token"] as kotlinx.serialization.json.JsonObject
        assertEquals((storytel["statuses"] as kotlinx.serialization.json.JsonArray).map { (it as JsonPrimitive).content }, STORYTEL_TOKEN_STATUSES)
        assertEquals((storytel["timeout_ms"] as JsonPrimitive).content.toLong(), STORYTEL_TOKEN_TIMEOUT_MS)
    }

    @Test
    fun `every bridge method checks its caller first`() {
        val source = module("src/main/java/com/kidslauncher/mdm/music/MusicBridgeService.kt").readText()
        val binder = source.substringAfter("object : IMusicBridge.Stub() {").substringBefore("\n    }\n")
        val methods = Regex("override fun (\\w+)\\([^)]*\\)[^{]*\\{\\s*([^\\n]*)").findAll(binder).toList()
        assertEquals("all of IMusicBridge's methods", 14, methods.size)
        for (m in methods) assertEquals("${m.groupValues[1]} must call check() first", "check()", m.groupValues[2].trim())
    }
}
