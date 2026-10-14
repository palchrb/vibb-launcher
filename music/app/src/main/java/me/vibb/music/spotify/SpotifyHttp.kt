package me.vibb.music.spotify

import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okio.Buffer
import java.io.IOException
import java.util.concurrent.TimeUnit

/*
 * The one HTTP seam of the phone's Spotify calls (design 26c §5.1, §5.4), built like the Storytel
 * client's (`StorytelHttp`): SpotifyApiTest fakes [SpotifyHttp], SpotifyHttpTest runs
 * [OkHttpSpotifyHttp] against loopback servers.
 *
 * Secrets (§7.1): a request's headers hold the account's access token, and a debug token answer's
 * body holds one too. Both types print them redacted, and nothing here logs.
 */

/** The one HTTP seam: blocking, any status returned, an IOException = unreachable. */
fun interface SpotifyHttp {
    @Throws(IOException::class)
    fun send(request: SpotifyRequest): SpotifyResponse
}

/** One request; [body] is JSON (null = none: an empty body for a PUT or POST). [timeoutMs] bounds the whole call. */
class SpotifyRequest(
    val method: String,
    val url: HttpUrl,
    val headers: List<Pair<String, String>>,
    val body: String?,
    val timeoutMs: Long,
) {
    /** The path only: a header holds the access token, the query the device id. */
    override fun toString(): String =
        "SpotifyRequest($method ${url.scheme}://${url.host}${url.encodedPath}, ${headers.size} headers, " +
            "${body?.length ?: 0} body chars, timeoutMs=$timeoutMs)"
}

/** One answer, whatever its status. [body] is capped at [BODY_MAX] bytes; [retryAfter] is the raw `Retry-After`. */
class SpotifyResponse(val status: Int, val retryAfter: String?, val body: ByteArray) {
    /** The status only: a token answer's body is a secret. */
    override fun toString(): String = "SpotifyResponse($status, retryAfter=$retryAfter, ${body.size} bytes)"

    companion object {
        const val BODY_MAX = 64 * 1024
    }
}

/**
 * [SpotifyHttp] over OkHttp. [client] is a parameter: the renderer passes the guarded public-only
 * client (`MediaCache.guarded`), the debug switch the LAN client (`SpotifyEndpoints.apiOverLan`), the
 * tests a plain one. [userAgent] goes on every request. Redirects are never followed - the Web API
 * answers its calls directly, and a redirect would only carry the account's token somewhere else.
 * Never a logging interceptor.
 */
class OkHttpSpotifyHttp(private val client: OkHttpClient, private val userAgent: String) : SpotifyHttp {
    override fun send(request: SpotifyRequest): SpotifyResponse {
        val call = client.newBuilder()
            .followRedirects(false)
            .followSslRedirects(false)
            .callTimeout(request.timeoutMs.coerceAtLeast(1), TimeUnit.MILLISECONDS)
            .build()
        val body = when {
            request.body != null -> request.body.toRequestBody(JSON)
            request.method == "PUT" || request.method == "POST" -> ByteArray(0).toRequestBody(null)
            else -> null
        }
        val builder = Request.Builder().url(request.url).method(request.method, body)
        for ((name, value) in request.headers) builder.header(name, value)
        builder.header("User-Agent", userAgent)
        call.newCall(builder.build()).execute().use { response ->
            val buffer = Buffer()
            response.body?.source()?.let { source ->
                while (buffer.size < SpotifyResponse.BODY_MAX) {
                    if (source.read(buffer, SpotifyResponse.BODY_MAX - buffer.size) == -1L) break
                }
            }
            return SpotifyResponse(response.code, response.header("Retry-After"), buffer.readByteArray())
        }
    }

    private companion object {
        val JSON = "application/json; charset=utf-8".toMediaType()
    }
}
