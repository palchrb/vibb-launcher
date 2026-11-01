package me.vibb.music.media

import me.vibb.music.Fixtures.moduleFile
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import javax.net.ssl.SSLHandshakeException

/** Security review 2026-10-21 S-L4: a public entry's http enclosure is asked over https first, the listed URL the fallback. */
class HttpsFirstTest {
    private fun answer(r: Request, code: Int) =
        Response.Builder().request(r).protocol(Protocol.HTTP_1_1).code(code).message("x").body("".toResponseBody()).build()

    @Test
    fun `https first, http only when https fails - then that host over http for a day`() {
        var now = 0L
        val first = HttpsFirst { now }
        val sent = mutableListOf<String>()
        val ep = Request.Builder().url("http://feeds.example.invalid/ep1.mp3?x=1").header("Range", "bytes=100-").build()
        // a host with https: the https answer is the one used, the request's headers kept
        val ok = first.run(ep, { false }) { r, tls -> sent += "${r.url}|$tls|${r.header("Range")}"; answer(r, 206) }
        assertEquals("https://feeds.example.invalid/ep1.mp3?x=1", ok.request.url.toString())
        assertEquals(listOf("https://feeds.example.invalid/ep1.mp3?x=1|true|bytes=100-"), sent)
        // a host without: the TLS failure falls back to the listed URL, and the next open doesn't try again
        sent.clear()
        val plain = Request.Builder().url("http://old.example.invalid/ep.mp3").build()
        val send: (Request, Boolean) -> Response = { r, tls -> sent += "${r.url}|$tls"; if (tls) throw SSLHandshakeException("no") else answer(r, 200) }
        assertEquals("http://old.example.invalid/ep.mp3", first.run(plain, { false }, send).request.url.toString())
        first.run(plain, { false }, send)
        assertEquals(listOf("https://old.example.invalid/ep.mp3|true", "http://old.example.invalid/ep.mp3|false", "http://old.example.invalid/ep.mp3|false"), sent)
        // an HTTP error over https falls back too; a day later https is tried again
        sent.clear()
        now += HttpsFirst.PLAIN_FOR_MS + 1
        first.run(plain, { false }) { r, tls -> sent += "$tls"; answer(r, if (tls) 404 else 200) }
        assertEquals(listOf("true", "false"), sent)
        // never for https, another port or anything else
        assertNull(HttpsFirst.httpsUpgrade("https://a.example.invalid/x".toHttpUrl()))
        assertNull(HttpsFirst.httpsUpgrade("http://a.example.invalid:8080/x".toHttpUrl()))
    }

    @Test
    fun `only the public entries' audio client carries it - not lan, not Storytel's`() {
        val cache = moduleFile("src/main/java/me/vibb/music/media/MediaCache.kt").readText()
        assertTrue(cache.contains("private val enclosureClient: OkHttpClient by lazy { publicClient.newBuilder().addInterceptor(HttpsFirst()).build() }"))
        assertTrue(cache.contains("!followRedirects -> noRedirectClients.getValue(publicOnly)\n            publicOnly -> enclosureClient\n            else -> lanClient"))
        assertEquals("one use", 2, cache.split("enclosureClient").size - 1)
    }
}
