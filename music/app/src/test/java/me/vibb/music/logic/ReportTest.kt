package me.vibb.music.logic

import androidx.media3.common.PlaybackException
import androidx.media3.common.ParserException
import androidx.media3.datasource.HttpDataSource
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import me.vibb.music.media.MediaCache
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.SocketTimeoutException

/**
 * `item_errors` and the report (21 §1.3, 21b §4.3): the codes of a failed download or stream, at
 * most once per item per sync, at most 50; the report's fields. Kept in the app for step 3.
 */
class ReportTest {
    @Test
    fun `codes - an HTTP status, media that isn't audio, else network`() {
        assertEquals("http_404", itemErrorCode(404, false))
        assertEquals("http_403", itemErrorCode(403, true))
        assertEquals("bad_media", itemErrorCode(null, true))
        assertEquals("network", itemErrorCode(null, false))
        assertEquals("network", itemErrorCode(999, false))
    }

    /**
     * Media3's HTTP exceptions need a DataSpec, which needs a real android.net.Uri (a stub in JVM
     * tests), so they are allocated without a constructor and given the field the code reads.
     */
    private inline fun <reified T> allocate(): T {
        val unsafe = Class.forName("sun.misc.Unsafe").getDeclaredField("theUnsafe").apply { isAccessible = true }.get(null)
        return unsafe.javaClass.getMethod("allocateInstance", Class::class.java).invoke(unsafe, T::class.java) as T
    }

    private fun status(code: Int) = allocate<HttpDataSource.InvalidResponseCodeException>().also {
        HttpDataSource.InvalidResponseCodeException::class.java.getField("responseCode").apply { isAccessible = true }.setInt(it, code)
    }

    private fun html() = allocate<HttpDataSource.InvalidContentTypeException>()

    @Test
    fun `a download's failure maps to its code`() {
        assertEquals("http_410", MediaCache.errorCode(IOException(status(410))))
        assertEquals("bad_media", MediaCache.errorCode(html()))
        assertEquals("bad_media", MediaCache.errorCode(ParserException.createForMalformedManifest("not a playlist", null)))
        assertEquals("network", MediaCache.errorCode(SocketTimeoutException()))
        assertEquals("a private address refused (21b §2.7)", "network", MediaCache.errorCode(MediaCache.PrivateAddressException("evil.example")))
    }

    @Test
    fun `a stream's failure maps the same way through the player's exception`() {
        val stream = PlaybackException("x", status(403), PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS)
        assertEquals("http_403", MediaCache.errorCode(stream))
        val html = PlaybackException("x", html(), PlaybackException.ERROR_CODE_IO_UNSPECIFIED)
        assertEquals("bad_media", MediaCache.errorCode(html))
    }

    @Test
    fun `one row per item, the latest, at most 50`() {
        var log = addItemError(emptyList(), ItemError(1, "v1", "a", "http_404"))
        log = addItemError(log, ItemError(1, "v2", "a", "http_410"))
        assertEquals(listOf("http_410"), log.map { it.error })
        for (i in 0 until 60) log = addItemError(log, ItemError(2, "v", "i$i", "network"))
        assertEquals(ITEM_ERRORS_MAX, log.size)
        assertEquals("i59", log.last().item)
    }

    @Test
    fun `the report has music_state's fields and never positions`() {
        val report = stateReport(
            versionCode = 7, libraryVersion = "aa485b7dd0d2a390", cacheBytes = 1234,
            entryErrors = listOf(EntryError(3, "bad_listing")),
            itemErrors = listOf(ItemError(2, "0123456789abcdef", "k1", "http_404")),
            downloads = listOf(DownloadState(2, "0123456789abcdef", 1, 3, Waiting.WIFI), DownloadState(1, "v", 2, 2, null)),
            storytel = "ok",
            sonosRooms = listOf(ReportedRoom("RINCON_000E58A0B1C201400", "Stua")),
        )
        assertEquals(
            setOf("version_code", "library_version", "cache_bytes", "downloads_waiting", "entry_errors", "item_errors", "downloads", "storytel", "spotify", "sonos_rooms"),
            report.keys,
        )
        assertEquals("design 26c §4.4: none until the player plays Spotify", "none", report["spotify"]!!.jsonPrimitive.content)
        assertEquals("ok", stateReport(1, null, 0, emptyList(), emptyList(), emptyList(), spotify = "ok")["spotify"]!!.jsonPrimitive.content)
        val room = (report["sonos_rooms"] as JsonArray).single().jsonObject
        assertEquals("uid and name only - never an ip", setOf("uid", "name"), room.keys)
        assertEquals("Stua", room["name"]!!.jsonPrimitive.content)
        assertEquals(JsonArray(emptyList()), stateReport(1, null, 0, emptyList(), emptyList(), emptyList())["sonos_rooms"])
        assertEquals("ok", report["storytel"]!!.jsonPrimitive.content)
        assertEquals(2, report["downloads_waiting"]!!.jsonPrimitive.int)
        val error = (report["item_errors"] as JsonArray).single().jsonObject
        assertEquals(setOf("entry", "version", "item", "error"), error.keys)
        assertEquals(2L, error["entry"]!!.jsonPrimitive.long)
        val downloads = report["downloads"] as JsonArray
        assertEquals("wifi", downloads[0].jsonObject["waiting"]!!.jsonPrimitive.content)
        assertEquals(JsonNull, downloads[1].jsonObject["waiting"])
        assertTrue(report.keys.none { it.contains("position") || it.contains("playing") })
    }

    /** Design 26c §4.4 (step 5b): the `spotify` word from spotify_state.json, an unplayable track as an item error. */
    @Test
    fun `the report's spotify is the stored outcome's word, and an unplayable track is an item error`() {
        val file = SpotifyOutcome("not_premium", 1_760_000_000_000).encode()
        val report = stateReport(
            1, "v", 0, emptyList(),
            listOf(ItemError(6, "11ee11ee11ee11ee", "1FakeTrackA00000000001", "unplayable")),
            emptyList(),
            spotify = SpotifyOutcome.reported(file),
        )
        assertEquals("not_premium", report["spotify"]!!.jsonPrimitive.content)
        val error = (report["item_errors"] as JsonArray).single().jsonObject
        assertEquals("unplayable", error["error"]!!.jsonPrimitive.content)
        assertEquals("the track's id, never a uri or a token", "1FakeTrackA00000000001", error["item"]!!.jsonPrimitive.content)
        for (word in SpotifyWords.ALL) {
            assertEquals(word, stateReport(1, null, 0, emptyList(), emptyList(), emptyList(), spotify = SpotifyOutcome.reported(SpotifyOutcome(word, 1).encode()))["spotify"]!!.jsonPrimitive.content)
        }
        assertEquals("no file: none", "none", stateReport(1, null, 0, emptyList(), emptyList(), emptyList(), spotify = SpotifyOutcome.reported(null))["spotify"]!!.jsonPrimitive.content)
        // the stored file holds the word and its time, nothing else
        assertEquals(setOf("outcome", "at"), kotlinx.serialization.json.Json.parseToJsonElement(file).jsonObject.keys)
        // one row per track, the latest (a track unplayable twice is one row)
        var log = addItemError(emptyList(), ItemError(6, "v", "t1", "unplayable"))
        log = addItemError(log, ItemError(6, "v", "t1", "unplayable"))
        assertEquals(1, log.size)
    }

    /** Design 26b §4.5 (QA M4): a Storytel entry's waiting, and the account's word. */
    @Test
    fun `a Storytel entry waits for the login, then the 1_5 GB floor, else the shared rule`() {
        val gb = 1_000_000_000L
        val states = listOf(DownloadState(7, "v", 0, 3, null), DownloadState(2, "v", 1, 3, null), DownloadState(9, "v", 3, 3, null))
        val storytel = setOf(7L, 9L)
        fun waits(shared: Waiting?, account: String, free: Long) = reportWaiting(states, storytel, shared, account, free).map { it.waiting }
        assertEquals(listOf(Waiting.LOGIN, null, null), waits(null, "none", 10 * gb))
        assertEquals(listOf(Waiting.LOGIN, Waiting.WIFI, null), waits(Waiting.WIFI, "refused", 10 * gb))
        assertEquals("storage between 0.5 and 1.5 GB", listOf(Waiting.STORAGE, null, null), waits(null, "ok", gb))
        assertEquals(listOf(Waiting.STORAGE, Waiting.STORAGE, null), waits(Waiting.STORAGE, "ok", gb / 4))
        assertEquals("else the shared rule", listOf(Waiting.ROAMING, Waiting.ROAMING, null), waits(Waiting.ROAMING, "ok", 10 * gb))
        assertEquals(listOf(null, null, null), waits(null, "ok", 10 * gb))
        for (word in listOf("none", "ok", "refused")) {
            val report = stateReport(1, null, 0, emptyList(), emptyList(), reportWaiting(states, storytel, null, word, 10 * gb), word)
            assertEquals(word, report["storytel"]!!.jsonPrimitive.content)
            if (word != "ok") assertEquals("login", (report["downloads"] as JsonArray)[0].jsonObject["waiting"]!!.jsonPrimitive.content)
        }
    }

    /** Design 26a §5.5: the rooms the speaker cache knows, as the server keeps them. */
    @Test
    fun `the reported rooms - Sonos uids only, names cut to 40, by name, at most 32`() {
        val rooms = reportedRooms(
            mapOf(
                "RINCON_000E58A0B1C301400" to "  Kjøkken\u0007  " + "ø".repeat(60),
                "RINCON_000E58A0B1C201400" to "Bad",
                "RINCON_bad" to "Lowercase",
                "192.168.1.20" to "An ip",
                "RINCON_STUA01400" to "Too short",
                "RINCON_000E58A0B1C401400" to null,
            ),
        )
        assertEquals(listOf("RINCON_000E58A0B1C401400", "RINCON_000E58A0B1C201400", "RINCON_000E58A0B1C301400"), rooms.map { it.uid })
        assertEquals("", rooms[0].name)
        assertEquals("Kjøkken " + "ø".repeat(32), rooms[2].name)
        assertEquals(SONOS_ROOM_NAME_MAX, rooms[2].name.codePointCount(0, rooms[2].name.length))
        // cut by characters, never inside one (a supplementary character counts once, as on the server)
        val emoji = reportedRooms(mapOf("RINCON_000E58A0B1C201400" to "\uD83D\uDD0A".repeat(45))).single().name
        assertEquals(SONOS_ROOM_NAME_MAX, emoji.codePointCount(0, emoji.length))
        // the server's cleaning, in its order (qa-26a-step3c-code L6; music_state_keeps_sonos_rooms_checked)
        assertEquals(" Stua  x", reportedRooms(mapOf("RINCON_000E58A0B1C201400" to "\u001C Stua \u0007 x\u2003")).single().name)
        // every White_Space character splits (Rust's split_whitespace); U+001C-U+001F (Java's isWhitespace) and U+200B don't
        val white = "\u0009\u000A\u000B\u000C\u000D\u0020\u0085\u00A0\u1680\u2000\u2005\u200A\u2028\u2029\u202F\u205F\u3000"
        for (c in white) assertEquals("U+%04X".format(c.code), "a b", reportedRooms(mapOf("RINCON_000E58A0B1C201400" to "a${c}b")).single().name)
        assertEquals("ab", reportedRooms(mapOf("RINCON_000E58A0B1C201400" to "a\u001Fb")).single().name)
        assertEquals("a\u200Bb", reportedRooms(mapOf("RINCON_000E58A0B1C201400" to "a\u200Bb")).single().name)
        val many = reportedRooms((0 until 50).associate { "RINCON_000E58A0B1C2%02d400".format(it) to "Rom %02d".format(it) })
        assertEquals(SONOS_ROOMS_MAX, many.size)
        assertEquals("Rom 00", many.first().name)
        assertEquals("Rom 31", many.last().name)
    }
}
