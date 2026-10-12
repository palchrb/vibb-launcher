package me.vibb.music.storytel

import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okio.Buffer
import java.io.IOException
import java.util.concurrent.TimeUnit

/*
 * The one HTTP seam of the Storytel client, ported from palchrb/vibb `pi/vibb/storytel.py`
 * (`_request`, `_NoRedirect`; MIT License, Copyright (c) 2026 palchrb), reimplemented in Kotlin under
 * the GPL-3.0 (or later) - see NOTICE.md. Design 26b §4.1. StorytelApiTest fakes [StorytelHttp];
 * StorytelHttpTest runs [OkHttpStorytelHttp] against loopback servers.
 *
 * Secrets (§0, QA L9): a request's URL can hold `pwd=` (the login) and its headers the bearer; a
 * response's body can hold the JWT and its Location the signed CDN URL. Every type here prints them
 * redacted, and nothing here logs.
 */

/** The one HTTP seam (vibb `_request`): blocking, any status returned, an IOException = unreachable. */
fun interface StorytelHttp {
    @Throws(IOException::class)
    fun send(request: StorytelRequest): StorytelResponse
}

/** One request. [follow] false captures a redirect (the asset call); [timeoutMs] bounds the whole call. */
data class StorytelRequest(
    val method: String,
    val url: HttpUrl,
    val headers: List<Pair<String, String>>,
    val follow: Boolean,
    val timeoutMs: Long,
) {
    /** The path only: the login's query holds the email and `pwd=`, a header the bearer. */
    override fun toString(): String =
        "StorytelRequest($method ${url.scheme}://${url.host}${url.encodedPath}, ${headers.size} headers, follow=$follow, timeoutMs=$timeoutMs)"
}

/** One answer, whatever its status. [body] is capped at [BODY_MAX] bytes. */
class StorytelResponse(val status: Int, val location: String?, val body: ByteArray) {
    /** The status only: the login's body holds the JWT, a Location the signed URL. */
    override fun toString(): String =
        "StorytelResponse($status, location=${if (location == null) "none" else "<redacted>"}, ${body.size} bytes)"

    companion object {
        const val BODY_MAX = 64 * 1024
    }
}

/**
 * [StorytelHttp] over OkHttp. [client] is a parameter (QA M2): the account passes the guarded
 * public-only client (`MediaCache.guarded`), the debug switch the LAN client, the tests a plain one.
 * [userAgent] goes on every request (QA L8: MediaCache sets `VibbMusic/<version>` on its data
 * source factory, not on the client, so without it the API would see `okhttp/x`). `follow = false`
 * turns off both redirect kinds, so a 302 is captured and the bearer never rides to the CDN (vibb
 * `_NoRedirect`). Never a logging interceptor.
 */
class OkHttpStorytelHttp(private val client: OkHttpClient, private val userAgent: String) : StorytelHttp {
    override fun send(request: StorytelRequest): StorytelResponse {
        val call = client.newBuilder()
            .followRedirects(request.follow)
            .followSslRedirects(request.follow)
            .callTimeout(request.timeoutMs.coerceAtLeast(1), TimeUnit.MILLISECONDS)
            .build()
        val builder = Request.Builder().url(request.url).method(request.method, null)
        for ((name, value) in request.headers) builder.header(name, value)
        builder.header("User-Agent", userAgent)
        call.newCall(builder.build()).execute().use { response ->
            val body = Buffer()
            response.body?.source()?.let { source ->
                while (body.size < StorytelResponse.BODY_MAX) {
                    if (source.read(body, StorytelResponse.BODY_MAX - body.size) == -1L) break
                }
            }
            return StorytelResponse(response.code, response.header("Location"), body.readByteArray())
        }
    }
}
