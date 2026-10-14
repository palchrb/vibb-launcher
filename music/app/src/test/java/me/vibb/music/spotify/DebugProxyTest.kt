package me.vibb.music.spotify

import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.net.InetAddress
import java.net.Proxy
import java.net.ServerSocket
import java.net.SocketException
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * The debug switch's proxy (design 26c §9.3; emulator round 2026-10-14 N4): a seek past the buffer, a start at a stored
 * place and a long stream stalled behind it. Its answer now carries `Content-Length` with `Content-Range`, and a streamed
 * body has no call timeout - only the read timeout - so a tone that streams for minutes isn't cut. A local socket plays
 * the fake: a ranged WAV answered 206, its body sent slowly.
 */
class DebugProxyTest {
    /** One connection per request: [answer] gets the request head, writes the whole response itself. */
    private class Fake(private val answer: (head: String, out: java.io.OutputStream) -> Unit) : Closeable {
        private val server = ServerSocket(0, 50, InetAddress.getLoopbackAddress())
        val port get() = server.localPort
        val heads = java.util.concurrent.CopyOnWriteArrayList<String>()

        init {
            thread(isDaemon = true, name = "fake-proxy-target") {
                while (!server.isClosed) {
                    val s = try {
                        server.accept()
                    } catch (_: SocketException) {
                        break
                    }
                    thread(isDaemon = true) {
                        s.use {
                            try {
                                val input = BufferedInputStream(it.getInputStream())
                                val head = ByteArrayOutputStream()
                                var last4 = 0
                                while (true) {
                                    val b = input.read()
                                    if (b < 0) break
                                    head.write(b)
                                    last4 = (last4 shl 8) or b
                                    if (last4 == 0x0D0A0D0A) break
                                }
                                val text = head.toString(Charsets.US_ASCII.name())
                                heads += text
                                answer(text, it.getOutputStream())
                            } catch (_: java.io.IOException) {
                                // the client hung up
                            }
                        }
                    }
                }
            }
        }

        override fun close() = server.close()
    }

    private val total = 2_800_044

    /** A fake WAV's ranged answer: `bytes=<n>-` -> 206 with Content-Range and Content-Length, the body in [chunks] slow pieces. */
    private fun ranged(chunks: Int, pauseMs: Long) = Fake { head, out ->
        val from = Regex("Range: bytes=(\\d+)-", RegexOption.IGNORE_CASE).find(head)?.groupValues?.get(1)?.toInt() ?: 0
        val length = total - from
        out.write(
            ("HTTP/1.1 206 Partial Content\r\nContent-Type: audio/wav\r\nAccept-Ranges: bytes\r\n" +
                "Content-Range: bytes $from-${total - 1}/$total\r\nContent-Length: $length\r\nConnection: close\r\n\r\n").toByteArray(),
        )
        out.flush()
        val piece = length / chunks
        var sent = 0
        repeat(chunks) { i ->
            val n = if (i == chunks - 1) length - sent else piece
            out.write(ByteArray(n))
            out.flush()
            sent += n
            Thread.sleep(pauseMs)
        }
    }

    @Test
    fun `N4 - a seek's ranged answer passes Content-Length and Content-Range, the whole length readable`() {
        ranged(chunks = 4, pauseMs = 0).use { fake ->
            val client = DebugProxy.client(OkHttpClient.Builder().proxy(Proxy.NO_PROXY).build())
            val a = DebugProxy.fetch(client, "http://127.0.0.1:${fake.port}/fake/audio/FAKEtrack1.wav?token=FAKE-ACCESS-1".toHttpUrl(), "bytes=2505132-")!!
            assertEquals(206, a.status)
            assertEquals("audio/wav", a.mime)
            assertEquals("${total - 2_505_132}", a.headers["Content-Length"])
            assertEquals("bytes 2505132-2800043/2800044", a.headers["Content-Range"])
            assertEquals("bytes", a.headers["Accept-Ranges"])
            assertEquals("the body is the length it says", total - 2_505_132, a.body.use { it.readBytes().size })
            assertTrue("the range forwarded", fake.heads.single().contains("Range: bytes=2505132-"))
        }
    }

    @Test
    fun `N4 - a long stream has no call timeout - only a stalled read fails`() {
        val client = DebugProxy.client(OkHttpClient.Builder().proxy(Proxy.NO_PROXY).callTimeout(300, TimeUnit.MILLISECONDS).build())
        assertEquals("no overall deadline on a streamed body", 0, client.callTimeoutMillis)
        assertEquals(DebugProxy.READ_TIMEOUT_S * 1000, client.readTimeoutMillis.toLong())
        assertTrue(!client.followRedirects && !client.followSslRedirects)
        // a body streamed over a second (a minutes-long tone, scaled down): read whole through a client whose base had 300 ms
        ranged(chunks = 10, pauseMs = 120).use { fake ->
            val a = DebugProxy.fetch(client, "http://127.0.0.1:${fake.port}/fake/audio/x.wav".toHttpUrl(), null)!!
            assertEquals("${total}", a.headers["Content-Length"])
            assertEquals(total, a.body.use { it.readBytes().size })
        }
    }

    @Test
    fun `a redirect is never followed, an error passes through`() {
        Fake { _, out ->
            out.write("HTTP/1.1 302 Found\r\nLocation: http://example.invalid/\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
        }.use { fake ->
            val client = DebugProxy.client(OkHttpClient.Builder().proxy(Proxy.NO_PROXY).build())
            assertNull(DebugProxy.fetch(client, "http://127.0.0.1:${fake.port}/x".toHttpUrl(), null))
        }
        Fake { _, out ->
            out.write("HTTP/1.1 404 Not Found\r\nContent-Type: text/plain; charset=utf-8\r\nContent-Length: 2\r\nConnection: close\r\n\r\nno".toByteArray())
        }.use { fake ->
            val client = DebugProxy.client(OkHttpClient.Builder().proxy(Proxy.NO_PROXY).build())
            val a = DebugProxy.fetch(client, "http://127.0.0.1:${fake.port}/x".toHttpUrl(), null)!!
            assertEquals(404, a.status)
            assertEquals("text/plain", a.mime)
            assertEquals("utf-8", a.charset)
            assertEquals("Not Found", a.reason)
        }
    }
}
