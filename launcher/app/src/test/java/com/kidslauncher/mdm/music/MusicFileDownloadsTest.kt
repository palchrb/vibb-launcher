package com.kidslauncher.mdm.music

import okhttp3.Headers.Companion.headersOf
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Timeout
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import retrofit2.Call
import retrofit2.Callback
import retrofit2.Response
import java.io.IOException
import java.security.MessageDigest

/**
 * One own file through [OwnFileFetch] with a fake [MusicServer] (qa-21-step3-code's test gap):
 * design 13's responses (200, 206, 412, 416, 404, 5xx), the size and SHA-256, resuming across runs,
 * and the gate re-checked while the body is copied (H2).
 */
class MusicFileDownloadsTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val bytes = ByteArray(200_000) { (it % 251).toByte() }
    private val file = LibraryFile(5, bytes.size.toLong(), sha(bytes), false)

    private fun sha(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }

    /** A scripted server: each call answers the next response; it records the Range and If-Match it got. */
    private class FakeServer(vararg answers: () -> Response<ResponseBody>) : MusicServer {
        val queue = ArrayDeque(answers.toList())
        val asked = mutableListOf<Pair<String?, String?>>()
        override suspend fun library(ifNoneMatch: String?) = throw UnsupportedOperationException()
        override suspend fun listing(entryId: Long, ifNoneMatch: String?) = throw UnsupportedOperationException()
        override suspend fun cover(hash: String) = throw UnsupportedOperationException()
        override suspend fun storytel() = throw UnsupportedOperationException()
        override suspend fun spotifyToken() = throw UnsupportedOperationException()
        override suspend fun storytelToken(stale: String?) = throw UnsupportedOperationException()
        override fun file(id: Long, range: String?, ifMatch: String?): Call<ResponseBody> {
            asked += range to ifMatch
            val answer = queue.removeFirst()
            return object : Call<ResponseBody> {
                override fun execute(): Response<ResponseBody> = answer()
                override fun enqueue(callback: Callback<ResponseBody>) = throw UnsupportedOperationException()
                override fun isExecuted() = false
                override fun cancel() = Unit
                override fun isCanceled() = false
                override fun clone(): Call<ResponseBody> = this
                override fun request(): Request = Request.Builder().url("http://server/").build()
                override fun timeout(): Timeout = Timeout.NONE
            }
        }
    }

    private fun raw(code: Int, vararg headers: String) = okhttp3.Response.Builder()
        .code(code).message("x").protocol(Protocol.HTTP_1_1)
        .request(Request.Builder().url("http://server/").build())
        .headers(headersOf(*headers))
        .build()

    private fun ok(body: ByteArray, vararg headers: String): () -> Response<ResponseBody> =
        { Response.success(body.toResponseBody(), raw(200, *headers)) }

    private fun partialContent(body: ByteArray, from: Int, total: Int): () -> Response<ResponseBody> =
        { Response.success(body.toResponseBody(), raw(206, "Content-Range", "bytes $from-${total - 1}/$total")) }

    private fun error(code: Int, vararg headers: String): () -> Response<ResponseBody> =
        { Response.error(ByteArray(0).toResponseBody(), raw(code, *headers)) }

    private class State(var etag: String? = null, var total: Long? = null) : PartialState {
        override fun get() = etag to total
        override fun set(etag: String?, total: Long?) {
            this.etag = etag
            this.total = total
        }
    }

    private val partial get() = tmp.root.resolve("5.part")
    private val ready get() = tmp.root.resolve("5")

    private fun fetch(server: MusicServer, state: State, mayGo: () -> Boolean = { true }) =
        OwnFileFetch(server, partial, ready, state, mayGo, checkEveryBytes = 1)

    @Test
    fun `200 - the whole file, checked, is ready`() {
        val state = State()
        assertEquals(FetchResult.DONE, fetch(FakeServer(ok(bytes, "ETag", "\"e1\"")), state).run(file))
        assertArrayEquals(bytes, ready.readBytes())
        assertFalse(partial.exists())
        assertNull("the partial's state goes", state.etag)
    }

    @Test
    fun `the gate is asked while the body is copied - a move to mobile data stops it, the partial stays (H2)`() {
        val state = State()
        var asks = 0
        val result = fetch(FakeServer(ok(bytes, "ETag", "\"e1\"")), state) { ++asks < 2 }.run(file)
        assertEquals(FetchResult.PAUSED, result)
        assertTrue("some bytes kept", partial.length() in 1 until bytes.size)
        assertEquals("the ETag kept for the resume", "\"e1\"", state.etag)
        assertFalse(ready.exists())
    }

    @Test
    fun `a later run resumes with Range and the first ETag as If-Match`() {
        val have = 80_000
        partial.writeBytes(bytes.copyOf(have))
        val state = State("\"e1\"", bytes.size.toLong())
        val server = FakeServer(partialContent(bytes.copyOfRange(have, bytes.size), have, bytes.size))
        assertEquals(FetchResult.DONE, fetch(server, state).run(file))
        assertEquals(listOf("bytes=$have-" to "\"e1\""), server.asked)
        assertArrayEquals(bytes, ready.readBytes())
    }

    @Test
    fun `412 - the file changed - starts over at once`() {
        partial.writeBytes(bytes.copyOf(1_000))
        val state = State("\"old\"", bytes.size.toLong())
        val server = FakeServer(error(412), ok(bytes, "ETag", "\"new\""))
        assertEquals(FetchResult.DONE, fetch(server, state).run(file))
        assertEquals(listOf("bytes=1000-" to "\"old\"", null to null), server.asked)
    }

    @Test
    fun `416 for a partial that is the whole file completes it`() {
        partial.writeBytes(bytes)
        val state = State("\"e1\"", bytes.size.toLong())
        assertEquals(FetchResult.DONE, fetch(FakeServer(error(416, "Content-Range", "bytes */${bytes.size}")), state).run(file))
        assertArrayEquals(bytes, ready.readBytes())
    }

    @Test
    fun `404 drops the partial, a 5xx keeps it for the next trigger`() {
        partial.writeBytes(bytes.copyOf(1_000))
        assertEquals(FetchResult.FAILED, fetch(FakeServer(error(503)), State("\"e1\"", bytes.size.toLong())).run(file))
        assertEquals(1_000L, partial.length())
        assertEquals(FetchResult.GONE, fetch(FakeServer(error(404)), State("\"e1\"", bytes.size.toLong())).run(file))
        assertFalse(partial.exists())
    }

    @Test
    fun `bytes that don't match the library's SHA-256 are deleted`() {
        val wrong = bytes.copyOf().also { it[10] = 0x7f }
        assertEquals(FetchResult.MISMATCH, fetch(FakeServer(ok(wrong, "ETag", "\"e1\"")), State()).run(file))
        assertFalse(partial.exists() || ready.exists())
    }

    @Test
    fun `a network error is a failure, never a crash`() {
        val server = FakeServer({ throw IOException("reset") })
        assertEquals(FetchResult.FAILED, fetch(server, State()).run(file))
    }

    @Test
    fun `own files follow the downloads switch`() {
        // Design 30 §1.1: the gate's "Wi-Fi only" is the policy's downloads switch off, never the streaming one.
        fun music(stream: Boolean?, download: Boolean?, mobile: Boolean = false) = musicPolicy(
            kotlinx.serialization.json.buildJsonObject {
                put("library_version", kotlinx.serialization.json.JsonNull)
                put("mobile_data", kotlinx.serialization.json.JsonPrimitive(mobile))
                stream?.let { put("stream_mobile_data", kotlinx.serialization.json.JsonPrimitive(it)) }
                download?.let { put("download_mobile_data", kotlinx.serialization.json.JsonPrimitive(it)) }
                put("storytel_generation", kotlinx.serialization.json.JsonPrimitive(0))
            },
        )!!
        val metered = com.kidslauncher.mdm.server.NetCaps(internet = true, notMetered = false, notRoaming = true, physical = true)
        val roaming = metered.copy(notRoaming = false)
        val wifi = metered.copy(notMetered = true)
        fun gate(m: MusicPolicy?, caps: com.kidslauncher.mdm.server.NetCaps) = ownFileGate(caps, m, 0L, 0L, file.size, null)
        assertEquals("streaming alone waits for Wi-Fi", com.kidslauncher.mdm.server.DownloadGate.WAIT_WIFI, gate(music(true, false), metered))
        assertEquals("downloads alone go", com.kidslauncher.mdm.server.DownloadGate.GO, gate(music(false, true), metered))
        assertEquals("an older server's mobile_data", com.kidslauncher.mdm.server.DownloadGate.GO, gate(music(null, null, mobile = true), metered))
        assertEquals("never roaming", com.kidslauncher.mdm.server.DownloadGate.WAIT_ROAMING, gate(music(false, true), roaming))
        assertEquals("Wi-Fi always", com.kidslauncher.mdm.server.DownloadGate.GO, gate(music(false, false), wifi))
        assertEquals("no readable music object: Wi-Fi only", com.kidslauncher.mdm.server.DownloadGate.WAIT_WIFI, gate(null, metered))
        assertEquals("no readable music object on Wi-Fi", com.kidslauncher.mdm.server.DownloadGate.GO, gate(null, wifi))
        assertEquals(
            "room is still checked",
            com.kidslauncher.mdm.server.DownloadGate.WAIT_SPACE,
            ownFileGate(wifi, music(false, true), 0L, 0L, file.size, 1L),
        )
        // The glue is one call each, pinned whole (qa-30-s2-code L2): nothing between the cached policy and ownFileGate.
        fun source(path: String) = listOf(java.io.File(path), java.io.File("app/$path")).first { it.exists() }.readText()
        val gateSource = source("src/main/java/com/kidslauncher/mdm/music/MusicFileDownloads.kt").substringAfter("private fun gate(").substringBefore("\n    }\n")
        assertEquals(
            "return ownFileGate(AppDownloads.currentCaps(app), MusicSync.cachedMusicPolicy(), System.currentTimeMillis(), have, file.size, allocatable(app))",
            gateSource.lines().last().trim(),
        )
        val sync = source("src/main/java/com/kidslauncher/mdm/music/MusicSync.kt").substringAfter("fun cachedMusicPolicy(): MusicPolicy? = try {").substringBefore("\n    }\n")
        assertEquals("musicPolicy(currentPolicyDecision().policy?.music)\n    } catch (e: Exception) {\n        null", sync.trim())
    }
}
