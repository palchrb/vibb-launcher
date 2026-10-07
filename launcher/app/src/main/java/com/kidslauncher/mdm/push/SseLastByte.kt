package com.kidslauncher.mdm.push

import okhttp3.Interceptor
import okhttp3.MediaType
import okhttp3.Response
import okhttp3.ResponseBody
import okio.Buffer
import okio.BufferedSource
import okio.ForwardingSource
import okio.buffer

/*
 * When did the SSE stream last receive anything (design 19 hole 1)? okhttp-sse hands its listener
 * only events (`onEvent`/`onRetryChange`) - the server's keepalive comments never reach it - so
 * `CommandListenerService` adds [LastByteInterceptor] as a network interceptor: it calls back for
 * the response headers and for every body read that returns data, and the service stamps
 * `SystemClock.elapsedRealtime()` (counts deep sleep, unlike the read timeout's watchdog).
 * No Android imports; tested in SseLastByteTest, also against okhttp-sse's own response handling.
 */

class LastByteInterceptor(private val onBytes: () -> Unit) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val response = chain.proceed(chain.request())
        onBytes()
        val body = response.body ?: return response
        return response.newBuilder().body(LastByteBody(body, onBytes)).build()
    }
}

/**
 * [delegate] with [onBytes] called whenever a read returns data. [contentType] and
 * [contentLength] are passed through: okhttp-sse 4.12 fails any body that isn't
 * `text/event-stream` ("Invalid content-type"), so a wrapper without them would turn every connect
 * into a retry loop (QA #2a).
 */
class LastByteBody(private val delegate: ResponseBody, private val onBytes: () -> Unit) : ResponseBody() {
    private val source: BufferedSource by lazy {
        object : ForwardingSource(delegate.source()) {
            override fun read(sink: Buffer, byteCount: Long): Long {
                val read = super.read(sink, byteCount)
                if (read > 0) onBytes()
                return read
            }
        }.buffer()
    }

    override fun contentType(): MediaType? = delegate.contentType()

    override fun contentLength(): Long = delegate.contentLength()

    override fun source(): BufferedSource = source
}
