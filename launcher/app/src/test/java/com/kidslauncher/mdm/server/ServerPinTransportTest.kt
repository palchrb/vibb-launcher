package com.kidslauncher.mdm.server

import java.io.Closeable
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLServerSocket
import javax.net.ssl.X509TrustManager
import kotlin.concurrent.thread
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Design 76 §6: the real [toServer] against a local TLS server presenting [TestOnlyChain] (a test root, an
 * intermediate, a leaf for localhost and 127.0.0.1, made in memory; the JVM client trusts the test root as Android
 * trusts a system CA, and OkHttp's chain cleaner gives the validated chain as on a phone).
 */
class ServerPinTransportTest {
    private val root = TestOnlyChain.root.certificate
    private val rootPin = spkiPin(root)
    private val serverContext: SSLContext = TestOnlyChain.server.sslContext()
    private val trustManager: X509TrustManager = TestOnlyChain.client.trustManager
    private val clientSocketFactory = TestOnlyChain.client.sslSocketFactory()

    /** A TLS server on loopback: each connection's request heads (empty when none came), keep-alive answers. */
    private inner class TlsServer(private val answer: String = OK) : Closeable {
        private val socket = serverContext.serverSocketFactory.createServerSocket(0, 5, InetAddress.getLoopbackAddress()) as SSLServerSocket
        val port: Int get() = socket.localPort
        /** One entry per connection once it ends: its request heads. */
        val connections = LinkedBlockingQueue<List<String>>()

        init {
            thread(isDaemon = true) {
                while (!socket.isClosed) {
                    val s = try { socket.accept() } catch (e: Exception) { break }
                    thread(isDaemon = true) { serve(s) }
                }
            }
        }

        private fun serve(s: Socket) {
            val heads = CopyOnWriteArrayList<String>()
            try {
                s.use {
                    s.soTimeout = 5_000
                    val input = s.getInputStream()
                    val out = s.getOutputStream()
                    while (true) {
                        val head = readHead(input) ?: break
                        heads += head
                        out.write(answer.toByteArray())
                        out.flush()
                    }
                }
            } catch (e: Exception) {
                // The client hung up (a refused chain ends the handshake's connection).
            }
            connections.put(heads.toList())
        }

        override fun close() = socket.close()
    }

    private fun readHead(input: InputStream): String? {
        val head = StringBuilder()
        while (!head.endsWith("\r\n\r\n")) {
            val b = input.read()
            if (b < 0) return null
            head.append(b.toChar())
        }
        return head.toString()
    }

    private val outside = CopyOnWriteArrayList<Pair<String, Anchor>>()

    private fun client(base: String, enforce: Boolean, pins: List<String>, proxy: Proxy? = null): OkHttpClient {
        val path = ServerPath.Direct(base, proxy)
        val pinning = ServerPinning(enforce, pins) { kind, _, anchor -> outside += kind to anchor }
        return OkHttpClient.Builder().toServer(path, pinning = pinning)
            .addInterceptor(bearerInterceptor(base, "secret-token"))
            .sslSocketFactory(clientSocketFactory, trustManager)
            .retryOnConnectionFailure(false)
            .build()
    }

    private fun get(client: OkHttpClient, url: String) = client.newCall(Request.Builder().url(url).build()).execute()

    @Test
    fun `the check on and the root in the set passes, with the bearer, and pins exactly the host`() {
        TlsServer().use { server ->
            val base = "https://localhost:${server.port}/"
            val client = client(base, enforce = true, pins = listOf(rootPin))
            assertTrue(client.certificatePinner.findMatchingPins("localhost").isNotEmpty())
            get(client, "${base}api/devices/policy").use { assertEquals(200, it.code) }
            get(client, "${base}api/devices/status").use { assertEquals(200, it.code) }
            client.connectionPool.evictAll()
            val heads = server.connections.poll(5, TimeUnit.SECONDS)!!
            assertEquals(2, heads.size)
            assertTrue(heads[0], heads[0].startsWith("GET /api/devices/policy HTTP/1.1\r\n") && heads[0].contains("Authorization: Bearer secret-token"))
            assertEquals(emptyList<Pair<String, Anchor>>(), outside)
        }
    }

    @Test
    fun `the check on and the root outside the set fails before a request line, the bearer never sent`() {
        TlsServer().use { server ->
            val base = "https://localhost:${server.port}/"
            val client = client(base, enforce = true, pins = BUILTIN_PINS.map { it.pin })
            try {
                get(client, "${base}api/devices/policy").close()
                fail("a chain outside the set was taken")
            } catch (e: ServerPinRefused) {
                assertEquals(Anchor("Test-only Root", rootPin), e.anchor)
            }
            assertEquals(emptyList<String>(), server.connections.poll(5, TimeUnit.SECONDS))
            assertEquals(listOf(OutsideRow.REFUSED to Anchor("Test-only Root", rootPin)), outside.toList())
        }
    }

    @Test
    fun `the check off passes as today and keeps the chain as a sighting, once per connection`() {
        TlsServer().use { server ->
            val base = "https://localhost:${server.port}/"
            val client = client(base, enforce = false, pins = BUILTIN_PINS.map { it.pin })
            get(client, "${base}api/devices/policy").use { response ->
                assertEquals(200, response.code)
                // The sync's judgement of this answer: https outside the set.
                assertEquals(PinConnection.FAILED, pinConnection(ServerPath.Direct(base, null), response.handshake))
                val seen = seenOf(response.handshake, "wifi", 7)!!
                assertEquals(ServerTlsSeen(false, "Test-only Root", rootPin, "wifi", 7), seen)
                assertEquals(PinConnection.PASSED, pinConnection(ServerPath.Direct(base, null), response.handshake, listOf(rootPin)))
            }
            get(client, "${base}api/devices/status").use { assertEquals(200, it.code) }
            assertEquals(listOf(OutsideRow.SEEN to Anchor("Test-only Root", rootPin)), outside.toList())
        }
    }

    @Test
    fun `an IP literal host is pinned too`() {
        TlsServer().use { server ->
            val base = "https://127.0.0.1:${server.port}/"
            val pass = client(base, enforce = true, pins = listOf(rootPin))
            assertTrue(pass.certificatePinner.findMatchingPins("127.0.0.1").isNotEmpty())
            get(pass, "${base}api/devices/policy").use { assertEquals(200, it.code) }
            val refuse = client(base, enforce = true, pins = BUILTIN_PINS.map { it.pin })
            try {
                get(refuse, "${base}api/devices/policy").close()
                fail("an IP literal wasn't pinned")
            } catch (expected: ServerPinRefused) {
            }
        }
    }

    @Test
    fun `another origin is refused before a socket, and a redirect is not followed`() {
        ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { other ->
            other.soTimeout = 300
            TlsServer(answer = "HTTP/1.1 302 Found\r\nLocation: https://localhost:${other.localPort}/x\r\nContent-Length: 0\r\n\r\n").use { server ->
                val base = "https://localhost:${server.port}/"
                val client = client(base, enforce = false, pins = BUILTIN_PINS.map { it.pin })
                for (url in listOf("https://localhost:${other.localPort}/api/devices/apps/5/download", "http://localhost:${server.port}/x", "https://127.0.0.1:${server.port}/x")) {
                    try {
                        get(client, url).close()
                        fail("$url was asked")
                    } catch (expected: ServerOriginRefused) {
                    }
                }
                get(client, "${base}api/devices/apps/5/download").use { assertEquals(302, it.code) }
            }
            try {
                other.accept().close()
                fail("another origin got a connection")
            } catch (expected: SocketTimeoutException) {
            }
        }
    }

    @Test
    fun `a pinner that can't be built fails closed, before a socket`() {
        ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { listener ->
            listener.soTimeout = 300
            val base = "https://localhost:${listener.localPort}/"
            assertNull(serverPinner("localhost", listOf("not base64!")))
            val client = client(base, enforce = true, pins = listOf("not base64!"))
            try {
                get(client, "${base}api/devices/policy").close()
                fail("a broken pinner let a call through")
            } catch (e: ServerPinRefused) {
                assertNull(e.anchor)
            }
            try {
                listener.accept().close()
                fail("a broken pinner connected")
            } catch (expected: SocketTimeoutException) {
            }
        }
    }

    @Test
    fun `through tsnet's SOCKS proxy the check holds end to end`() {
        TlsServer().use { server ->
            Socks5Relay(server.port).use { relay ->
                val proxy = Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", relay.port))
                val base = "https://localhost:${server.port}/"
                get(client(base, enforce = true, pins = listOf(rootPin), proxy = proxy), "${base}api/devices/policy").use { assertEquals(200, it.code) }
                try {
                    get(client(base, enforce = true, pins = BUILTIN_PINS.map { it.pin }, proxy = proxy), "${base}api/devices/policy").close()
                    fail("the proxy path wasn't pinned")
                } catch (expected: ServerPinRefused) {
                }
                assertTrue(relay.targets.isNotEmpty())
                assertTrue(relay.targets.all { it.endsWith(":${server.port}") })
            }
        }
    }

    /** A SOCKS5 proxy (no auth) that relays every CONNECT to the TLS server on loopback, and notes the asked target. */
    private class Socks5Relay(private val targetPort: Int) : Closeable {
        private val listener = ServerSocket(0, 5, InetAddress.getLoopbackAddress())
        val port: Int get() = listener.localPort
        val targets = CopyOnWriteArrayList<String>()

        init {
            thread(isDaemon = true) {
                while (!listener.isClosed) {
                    val s = try { listener.accept() } catch (e: Exception) { break }
                    thread(isDaemon = true) { relay(s) }
                }
            }
        }

        private fun relay(s: Socket) {
            try {
                val input = s.getInputStream()
                val out = s.getOutputStream()
                val greeting = input.readNBytes(2)
                input.readNBytes(greeting[1].toInt())
                out.write(byteArrayOf(5, 0))
                val head = input.readNBytes(4)
                val host = when (head[3].toInt()) {
                    1 -> InetAddress.getByAddress(input.readNBytes(4)).hostAddress
                    3 -> String(input.readNBytes(input.read()))
                    else -> InetAddress.getByAddress(input.readNBytes(16)).hostAddress
                }
                val port = input.readNBytes(2).let { ((it[0].toInt() and 0xFF) shl 8) or (it[1].toInt() and 0xFF) }
                targets += "$host:$port"
                val upstream = Socket(InetAddress.getLoopbackAddress(), targetPort)
                out.write(byteArrayOf(5, 0, 0, 1, 0, 0, 0, 0, 0, 0))
                out.flush()
                thread(isDaemon = true) { pump(upstream.getInputStream(), out, s) }
                pump(input, upstream.getOutputStream(), upstream)
            } catch (e: Exception) {
                s.close()
            }
        }

        private fun pump(from: InputStream, to: OutputStream, closing: Socket) {
            try {
                from.copyTo(to)
            } catch (e: Exception) {
            } finally {
                closing.close()
            }
        }

        override fun close() = listener.close()
    }

    private companion object {
        const val OK = "HTTP/1.1 200 OK\r\nContent-Length: 2\r\n\r\nok"
    }
}
