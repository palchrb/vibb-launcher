package com.kidslauncher.mdm.push

import okhttp3.Call
import okhttp3.Connection
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okhttp3.sse.EventSource
import okhttp3.sse.EventSourceListener
import okhttp3.sse.EventSources
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Design 19 hole 1 (QA #2a): the stamping wrapper around the SSE response - run through
 * okhttp-sse's own response handling, which refuses a body that isn't `text/event-stream`.
 */
class SseLastByteTest {

    private val request = Request.Builder().url("http://server.example/api/devices/commands/stream").build()

    private fun response(body: String, type: String = "text/event-stream; charset=utf-8") = Response.Builder()
        .request(request)
        .protocol(Protocol.HTTP_1_1)
        .code(200)
        .message("OK")
        .body(body.toResponseBody(type.toMediaType()))
        .build()

    /** The one thing an interceptor needs from its chain; the rest isn't called. */
    private class FakeChain(private val request: Request, private val response: Response) : Interceptor.Chain {
        override fun request(): Request = request
        override fun proceed(request: Request): Response = response
        override fun connection(): Connection? = null
        override fun call(): Call = throw UnsupportedOperationException()
        override fun connectTimeoutMillis(): Int = 0
        override fun withConnectTimeout(timeout: Int, unit: TimeUnit): Interceptor.Chain = this
        override fun readTimeoutMillis(): Int = 0
        override fun withReadTimeout(timeout: Int, unit: TimeUnit): Interceptor.Chain = this
        override fun writeTimeoutMillis(): Int = 0
        override fun withWriteTimeout(timeout: Int, unit: TimeUnit): Interceptor.Chain = this
    }

    /** Called on OkHttp's thread; read after [done]. */
    private class Recorder : EventSourceListener() {
        @Volatile var opened = 0
        val events: MutableList<String> = java.util.Collections.synchronizedList(mutableListOf())
        @Volatile var closed = 0
        @Volatile var failure: Throwable? = null
        @Volatile var failed = 0
        val done = CountDownLatch(1)

        override fun onOpen(eventSource: EventSource, response: Response) { opened++ }
        override fun onEvent(eventSource: EventSource, id: String?, type: String?, data: String) { events += data }
        override fun onClosed(eventSource: EventSource) {
            closed++
            done.countDown()
        }
        override fun onFailure(eventSource: EventSource, t: Throwable?, response: Response?) {
            failed++
            failure = t
            done.countDown()
        }
    }

    /**
     * Through a real client and okhttp-sse's own EventSource: the stamping interceptor first, then
     * one that answers with [body] instead of the network (`EventSources.processResponse` can't be
     * used - 4.12 needs a real call there).
     */
    private fun run(body: String, type: String = "text/event-stream; charset=utf-8"): Pair<Recorder, Int> {
        val stamps = AtomicInteger()
        val recorder = Recorder()
        val client = OkHttpClient.Builder()
            .addInterceptor(LastByteInterceptor { stamps.incrementAndGet() })
            .addInterceptor { chain -> response(body, type).newBuilder().request(chain.request()).build() }
            .build()
        EventSources.createFactory(client).newEventSource(request, recorder)
        assertTrue("the stream ended", recorder.done.await(10, TimeUnit.SECONDS))
        client.dispatcher.executorService.shutdown()
        return recorder to stamps.get()
    }

    @Test
    fun `the wrapper keeps the content type, so okhttp-sse accepts the stream`() {
        val original = response("data: command\n\n")
        val body = LastByteInterceptor {}.intercept(FakeChain(request, original)).body!!
        assertEquals("text/event-stream; charset=utf-8", body.contentType().toString())
        assertEquals(original.body!!.contentLength(), body.contentLength())

        val (recorder, stamps) = run("data: command\n\n")
        assertNull(recorder.failure)
        assertEquals(0, recorder.failed)
        assertEquals(1, recorder.opened)
        assertEquals(listOf("command"), recorder.events.toList())
        assertEquals(1, recorder.closed)
        assertTrue("headers and the body read are stamped", stamps >= 2)
    }

    @Test
    fun `a keepalive comment never reaches the listener but is stamped`() {
        val (recorder, stamps) = run(":\n\n")
        assertEquals(emptyList<String>(), recorder.events.toList())
        assertEquals(1, recorder.opened)
        assertTrue("the comment's read is stamped", stamps >= 2)
    }

    @Test
    fun `without the event-stream type okhttp-sse refuses the stream (the check is real)`() {
        val (recorder, _) = run("data: command\n\n", "application/json")
        assertEquals(1, recorder.failed)
        assertEquals(0, recorder.opened)
        assertTrue(recorder.failure.toString(), recorder.failure?.message.orEmpty().contains("content-type"))
    }
}
