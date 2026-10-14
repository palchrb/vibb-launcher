package me.vibb.music.spotify

import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.util.concurrent.TimeUnit

/*
 * The debug switch's proxy fetch (design 26c §9.3; `SpotifyWebHost`'s interceptor turns the answer into a
 * WebResourceResponse): `https://fake-spotify.invalid/<path>` fetched from the debug base over the LAN client, GET only,
 * `Range` forwarded. Its answer passes on the status, the content type and the headers the page's media element and
 * fetches need - `Content-Length` among them (emulator round 2026-10-14 N4: without it a seek past the buffer, a start at
 * a stored place or a long stream stalled while the WebView asked the same `Range` again every second or two). A
 * streamed body has no overall deadline: the client keeps the read timeout (a stalled read fails) but never a call
 * timeout, which bounded the whole body at 45 s. Never in a release (no debug base there). Pure (OkHttp, no Android):
 * DebugProxyTest serves a slow, long and a seeked answer from a local socket.
 */
internal object DebugProxy {
    /** A read that waits this long fails (the stub's long-poll answers within 20 s). */
    const val READ_TIMEOUT_S = 45L

    /** The answers' headers passed back (qa-26c-step4-code: the tone's seek and the stub's fetches need them; N4). */
    val HEADERS = listOf("Content-Length", "Content-Range", "Accept-Ranges", "Access-Control-Allow-Origin")

    /** What the interceptor answers the page with. */
    class Answer(
        val status: Int,
        val reason: String,
        val mime: String,
        val charset: String?,
        val headers: Map<String, String>,
        val body: InputStream,
    ) {
        override fun toString(): String = "Answer($status, $mime, ${headers.keys})"
    }

    /** [base] for the proxy: no redirects followed, the read timeout, and no call timeout (a stream may last minutes). */
    fun client(base: OkHttpClient): OkHttpClient = base.newBuilder()
        .followRedirects(false).followSslRedirects(false)
        .callTimeout(0, TimeUnit.SECONDS)
        .readTimeout(READ_TIMEOUT_S, TimeUnit.SECONDS)
        .build()

    /**
     * One GET of [url] through [client] with the page's [range]; null when the answer is neither a success nor an error
     * (a redirect - never followed: the interceptor answers 502). An IOException is the caller's (also a 502).
     */
    fun fetch(client: OkHttpClient, url: HttpUrl, range: String?): Answer? {
        val b = Request.Builder().url(url).get()
        range?.let { b.header("Range", it) }
        val response = client.newCall(b.build()).execute()
        val status = response.code
        if (status !in 200..299 && status !in 400..599) {
            response.close()
            return null
        }
        val type = response.header("Content-Type") ?: "application/octet-stream"
        val mime = type.substringBefore(';').trim()
        val charset = CHARSET.find(type)?.groupValues?.get(1)?.trim()
        // OkHttp drops Content-Length itself when it unzipped the body (the length would be the zipped one's)
        val headers = HEADERS.mapNotNull { h -> response.header(h)?.let { h to it } }.toMap()
        val reason = response.message.ifBlank { if (status < 400) "OK" else "Error" }
        return Answer(status, reason, mime, charset, headers, response.body?.byteStream() ?: ByteArrayInputStream(ByteArray(0)))
    }

    private val CHARSET = Regex("charset=([^;]+)", RegexOption.IGNORE_CASE)
}
