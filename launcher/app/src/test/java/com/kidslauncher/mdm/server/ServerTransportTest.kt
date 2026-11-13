package com.kidslauncher.mdm.server

import java.io.File
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ServerSocket
import java.net.SocketTimeoutException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import javax.xml.parsers.DocumentBuilderFactory
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.w3c.dom.Element

/** Security review S-M1: the server address rule (the shared vectors), the paths, and the cleartext exception. */
class ServerTransportTest {
    private val text = javaClass.classLoader!!.getResource("server_url_vectors.json")!!.readText()
    private val socks = Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", 1080))

    @Test
    fun `every vector has the server's verdict`() {
        val vectors = Json.parseToJsonElement(text).jsonObject.getValue("vectors").jsonArray
        assertTrue(vectors.size >= 50)
        for (v in vectors) {
            val url = v.jsonObject.getValue("url").jsonPrimitive.content
            val expected = when (val verdict = v.jsonObject.getValue("verdict").jsonPrimitive.content) {
                "https" -> ServerUrlVerdict.HTTPS
                "tailnet_http" -> ServerUrlVerdict.TAILNET_HTTP
                "refused" -> ServerUrlVerdict.REFUSED
                else -> error("unknown verdict $verdict")
            }
            assertEquals(url, expected, serverUrlVerdict(url))
        }
    }

    @Test
    fun `the vectors are identical to the server's copy`() {
        val serverCopy = File("../../server/testdata/server_url_vectors.json")
        assertTrue("${serverCopy.absolutePath} not found", serverCopy.isFile)
        assertEquals("${serverCopy.path} differs from the launcher's copy", serverCopy.readText(), text)
    }

    @Test
    fun `https goes direct or through tsnet as before`() {
        assertEquals(ServerPath.Direct("https://vibb.example.org/x/", socks), serverPath("https://vibb.example.org/x", socks, debug = false))
        assertEquals(ServerPath.Direct("https://vibb.example.org/", null), serverPath(" https://vibb.example.org/ ", null, debug = false))
    }

    @Test
    fun `a tailnet address goes only through tsnet, under the one cleartext name`() {
        assertEquals(ServerPath.Closed(ClosedWhy.TAILNET_DOWN), serverPath("http://100.64.0.1:3100", null, debug = false))
        assertEquals(ServerPath.Closed(ClosedWhy.TAILNET_DOWN), serverPath("http://100.64.0.1:3100", null, debug = true))
        assertEquals(
            ServerPath.Tailnet("http://$TAILNET_SERVER_HOST:3100/", "100.64.0.1:3100", InetAddress.getByName("100.64.0.1"), socks),
            serverPath("http://100.64.0.1:3100", socks, debug = false),
        )
        assertEquals(
            ServerPath.Tailnet("http://$TAILNET_SERVER_HOST/vibb/", "[fd7a:115c:a1e0::1]", InetAddress.getByName("fd7a:115c:a1e0::1"), socks),
            serverPath("http://[FD7A:115c:a1e0::1]/vibb", socks, debug = false),
        )
    }

    @Test
    fun `anything else is closed, except the emulator's host in a debug build`() {
        for (url in listOf("http://192.168.1.5:3100", "http://vibb.tail0000.ts.net", "http://100.64.0.1@evil.example", "", "nonsense")) {
            assertEquals(url, ServerPath.Closed(ClosedWhy.NOT_HTTPS), serverPath(url, socks, debug = false))
            assertEquals(url, ServerPath.Closed(ClosedWhy.NOT_HTTPS), serverPath(url, socks, debug = true))
        }
        assertEquals(ServerPath.Closed(ClosedWhy.NOT_HTTPS), serverPath("http://10.0.2.2:3100", null, debug = false))
        assertEquals(ServerPath.Direct("http://10.0.2.2:3100/", null), serverPath("http://10.0.2.2:3100", socks, debug = true))
        assertFalse(serverUrlUsable("http://10.0.2.2:3100", debug = false))
        assertTrue(serverUrlUsable("http://10.0.2.2:3100", debug = true))
        assertFalse(serverUrlUsable("http://10.0.2.20:3100", debug = true))
        assertTrue(serverUrlUsable("http://100.64.0.1", debug = false))
    }

    @Test
    fun `a closed client sends nothing`() {
        ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { listener ->
            listener.soTimeout = 300
            val path = serverPath("http://127.0.0.1:${listener.localPort}", null, debug = false)
            val client = OkHttpClient.Builder().toServer(path).build()
            try {
                client.newCall(Request.Builder().url("http://127.0.0.1:${listener.localPort}/api/devices/policy").build()).execute()
                fail("a closed path made a call")
            } catch (e: ServerPathClosed) {
                assertEquals(ClosedWhy.NOT_HTTPS, e.why)
            }
            try {
                listener.accept().close()
                fail("a closed path connected")
            } catch (expected: SocketTimeoutException) {
            }
        }
    }

    /** A one-shot SOCKS5 proxy (no auth) that answers one HTTP request: what the client asked of each. */
    @Test
    fun `the tailnet client asks tsnet's proxy for the address, with the real Host`() {
        ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { listener ->
            val seen = CompletableFuture.supplyAsync {
                listener.accept().use { s ->
                    val input = s.getInputStream()
                    val out = s.getOutputStream()
                    val greeting = input.readNBytes(2)
                    input.readNBytes(greeting[1].toInt())
                    out.write(byteArrayOf(5, 0))
                    val head = input.readNBytes(4)
                    val address = input.readNBytes(if (head[3].toInt() == 1) 4 else 16)
                    val port = input.readNBytes(2).let { ((it[0].toInt() and 0xFF) shl 8) or (it[1].toInt() and 0xFF) }
                    out.write(byteArrayOf(5, 0, 0, 1, 0, 0, 0, 0, 0, 0))
                    val request = StringBuilder()
                    while (!request.endsWith("\r\n\r\n")) request.append(input.read().toChar())
                    out.write("HTTP/1.1 200 OK\r\nContent-Length: 2\r\nConnection: close\r\n\r\nok".toByteArray())
                    out.flush()
                    Triple(head[3].toInt(), "${InetAddress.getByAddress(address).hostAddress}:$port", request.toString())
                }
            }
            val proxy = Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", listener.localPort))
            val path = serverPath("http://100.101.102.103:3100", proxy, debug = false)
            val client = OkHttpClient.Builder().toServer(path) { proxy }.build()
            val body = client.newCall(Request.Builder().url("${path.baseUrl}api/devices/policy").build()).execute().use { it.body!!.string() }
            assertEquals("ok", body)
            val (atyp, target, request) = seen.get(5, TimeUnit.SECONDS)
            assertEquals("an IP, never a name for the proxy to look up", 1, atyp)
            assertEquals("100.101.102.103:3100", target)
            assertTrue(request, request.startsWith("GET /api/devices/policy HTTP/1.1\r\n"))
            assertTrue(request, request.contains("\r\nHost: 100.101.102.103:3100\r\n"))
        }
    }

    @Test
    fun `a tailnet client refuses once tsnet's proxy changed`() {
        ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { listener ->
            listener.soTimeout = 300
            val proxy = Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", listener.localPort))
            val path = serverPath("http://100.101.102.103:3100", proxy, debug = false)
            for (now in listOf(null, socks)) {
                val client = OkHttpClient.Builder().toServer(path) { now }.retryOnConnectionFailure(false).build()
                try {
                    client.newCall(Request.Builder().url("${path.baseUrl}api/devices/policy").build()).execute()
                    fail("a stale proxy was used ($now)")
                } catch (expected: java.io.IOException) {
                }
            }
            try {
                listener.accept().close()
                fail("the old proxy port got a connection")
            } catch (expected: SocketTimeoutException) {
            }
        }
    }

    // The network security configs and the manifest (the working directory is launcher/app).

    private fun cleartextDomains(path: String): List<String> {
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(File(path))
        val base = doc.getElementsByTagName("base-config").item(0) as Element
        assertEquals(path, "false", base.getAttribute("cleartextTrafficPermitted"))
        val certificates = base.getElementsByTagName("certificates")
        assertEquals(path, 1, certificates.length)
        assertEquals(path, "system", (certificates.item(0) as Element).getAttribute("src"))
        val configs = doc.getElementsByTagName("domain-config")
        assertEquals(path, 1, configs.length)
        val config = configs.item(0) as Element
        assertEquals(path, "true", config.getAttribute("cleartextTrafficPermitted"))
        val domains = config.getElementsByTagName("domain")
        return (0 until domains.length).map {
            val domain = domains.item(it) as Element
            assertEquals(path, "false", domain.getAttribute("includeSubdomains"))
            domain.textContent.trim()
        }
    }

    @Test
    fun `cleartext is off but for the tailnet name, and the emulator's host in debug`() {
        assertEquals(listOf(TAILNET_SERVER_HOST), cleartextDomains("src/main/res/xml/network_security_config.xml"))
        assertEquals(listOf(TAILNET_SERVER_HOST, EMULATOR_HOST), cleartextDomains("src/debug/res/xml/network_security_config.xml"))
        assertTrue(TAILNET_SERVER_HOST.endsWith(".invalid"))
        // Design 76 (qa I-3): the release config never trusts a user CA; the debug one does, under debug-overrides
        // only (mitmproxy on the emulator), so the certificate check is what refuses there.
        val release = File("src/main/res/xml/network_security_config.xml").readText()
        assertFalse(release.contains("debug-overrides"))
        assertFalse(release.contains("src=\"user\""))
        val debug = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(File("src/debug/res/xml/network_security_config.xml"))
        val overrides = debug.getElementsByTagName("debug-overrides")
        assertEquals(1, overrides.length)
        val trusted = (overrides.item(0) as Element).getElementsByTagName("certificates")
        assertEquals(listOf("system", "user"), (0 until trusted.length).map { (trusted.item(it) as Element).getAttribute("src") })
        val manifest = File("src/main/AndroidManifest.xml").readText()
        assertTrue(manifest.contains("android:networkSecurityConfig=\"@xml/network_security_config\""))
        assertFalse(manifest.contains("usesCleartextTraffic"))
    }

    @Test
    fun `the server clients share one base client per path and flag`() {
        // Design 95 §3.5: the same path and flag give the same base (one pool, one TLS context); a change builds anew.
        val path = serverPath("https://vibb.example", null, debug = false)
        val off = ServerPinning(enforce = false)
        val base = ServerClients.base(path, off)
        assertTrue(base === ServerClients.base(path, ServerPinning(enforce = false)))
        val child = base.newBuilder().readTimeout(30, TimeUnit.SECONDS).build()
        assertTrue(child.connectionPool === base.connectionPool)
        val on = ServerClients.base(path, ServerPinning(enforce = true))
        assertFalse(on === base)
        assertFalse(ServerClients.base(serverPath("https://other.example", null, debug = false), off) === on)
        assertFalse(ServerClients.base(serverPath("https://vibb.example", socks, debug = false), off) === base)
    }

    @Test
    fun `every server client takes its way from the transport`() {
        val main = File("src/main/java/com/kidslauncher/mdm")
        // Design 95 §3.5: each is a child of the one base client, which alone is built with `.toServer(`.
        for (name in listOf("server/MdmApi.kt", "server/CommandListenerService.kt", "music/MusicServer.kt")) {
            val text = File(main, name).readText()
            assertTrue(name, text.contains("ServerClients.base(path).newBuilder()"))
            assertFalse(name, text.contains("OkHttpClient.Builder()"))
        }
        assertTrue(File(main, "server/ServerTransport.kt").readText().contains("OkHttpClient.Builder().toServer(path, pinning).build()"))
        val proxyUsers = main.walk().filter { it.isFile && it.extension == "kt" && it.readText().contains("TsnetClient.proxy()") }
            .map { it.name }.toSet()
        assertEquals(setOf("ServerTransport.kt"), proxyUsers)
    }
}
