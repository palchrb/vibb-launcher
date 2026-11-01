package me.vibb.music.media

import okhttp3.HttpUrl
import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/** An HTML answer is never audio (21b §2.5 `bad_media`): the player's content-type check and [HttpsFirst]'s, one test. */
fun htmlType(contentType: String): Boolean = contentType.lowercase().startsWith("text/html")

/**
 * A public entry's `http://` audio is asked for over `https://` first (security review 2026-10-21 S-L4): on a hostile
 * network a cleartext episode can be swapped in flight, and the swapped bytes stay as the copy. Same host, port 443,
 * the system's CAs. Anything but an audio answer (no TLS, a bad certificate, a refused or slow connect, an HTTP error,
 * an HTML page) falls
 * back to the URL as listed, and the host is then asked over http for [PLAIN_FOR_MS] without trying again, so a host
 * without https costs one try per process and day, never a wait at every open. What the player keys, stores and
 * compares stays the listed URL (`sameSource`, the rows); only the request goes another way. Never for `lan` entries,
 * Sonos or Storytel (their clients don't carry it).
 */
class HttpsFirst(private val nowMs: () -> Long = System::currentTimeMillis) : Interceptor {
    private val plainUntil = ConcurrentHashMap<String, Long>()

    override fun intercept(chain: Interceptor.Chain): Response =
        run(chain.request(), canceled = { chain.call().isCanceled() }) { request, tls ->
            (if (tls) chain.withConnectTimeout(HTTPS_CONNECT_MS, TimeUnit.MILLISECONDS) else chain).proceed(request)
        }

    /** [send] the https try (its second argument true) and, failing that, [request] as it is. */
    fun run(request: Request, canceled: () -> Boolean, send: (Request, Boolean) -> Response): Response {
        val upgraded = httpsUpgrade(request.url) ?: return send(request, false)
        val host = request.url.host
        if ((plainUntil[host] ?: 0L) > nowMs()) return send(request, false)
        val answer = try {
            send(request.newBuilder().url(upgraded).build(), true)
        } catch (e: IOException) {
            if (canceled()) throw e
            null
        }
        // an HTML page (a homepage redirect, a default site) is no answer: the player's content-type check would fail
        // the item for good, with no way back to the listed URL
        val html = answer?.header("Content-Type")?.let(::htmlType) == true
        if (answer != null && !html && (answer.isSuccessful || answer.code == 416)) return answer
        answer?.body?.close()
        plainUntil[host] = nowMs() + PLAIN_FOR_MS
        return send(request, false)
    }

    companion object {
        /** The https try's connect timeout: a host that drops port 443 holds an open this long at most, once. */
        const val HTTPS_CONNECT_MS = 4_000

        /** How long a host that failed over https is asked over http only. */
        const val PLAIN_FOR_MS = 24 * 3_600_000L

        /** [url] over https - for a plain `http://` URL on the default port only; null for anything else. */
        fun httpsUpgrade(url: HttpUrl): HttpUrl? =
            if (url.scheme == "http" && url.port == 80) url.newBuilder().scheme("https").port(443).build() else null
    }
}
