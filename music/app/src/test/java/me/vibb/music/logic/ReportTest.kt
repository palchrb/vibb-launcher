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
    fun `once per item per sync, the latest per item otherwise, at most 50`() {
        var log = addItemError(emptyList(), ItemError(1, "v1", "a", "http_404", sync = 1))
        log = addItemError(log, ItemError(1, "v1", "a", "network", sync = 1))
        assertEquals(listOf("http_404"), log.map { it.error })
        log = addItemError(log, ItemError(1, "v2", "a", "http_410", sync = 2))
        assertEquals(listOf("http_410"), log.map { it.error })
        for (i in 0 until 60) log = addItemError(log, ItemError(2, "v", "i$i", "network", sync = 2))
        assertEquals(ITEM_ERRORS_MAX, log.size)
        assertEquals("i59", log.last().item)
    }

    @Test
    fun `the report has music_state's fields and never positions`() {
        val report = stateReport(
            versionCode = 7, libraryVersion = "aa485b7dd0d2a390", cacheBytes = 1234,
            entryErrors = listOf(EntryError(3, "bad_listing")),
            itemErrors = listOf(ItemError(2, "0123456789abcdef", "k1", "http_404", 1)),
            downloads = listOf(DownloadState(2, "0123456789abcdef", 1, 3, Waiting.WIFI), DownloadState(1, "v", 2, 2, null)),
        )
        assertEquals(
            setOf("version_code", "library_version", "cache_bytes", "downloads_waiting", "entry_errors", "item_errors", "downloads"),
            report.keys,
        )
        assertEquals(2, report["downloads_waiting"]!!.jsonPrimitive.int)
        val error = (report["item_errors"] as JsonArray).single().jsonObject
        assertEquals(setOf("entry", "version", "item", "error"), error.keys)
        assertEquals(2L, error["entry"]!!.jsonPrimitive.long)
        val downloads = report["downloads"] as JsonArray
        assertEquals("wifi", downloads[0].jsonObject["waiting"]!!.jsonPrimitive.content)
        assertEquals(JsonNull, downloads[1].jsonObject["waiting"])
        assertTrue(report.keys.none { it.contains("position") || it.contains("playing") })
    }
}
