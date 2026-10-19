package com.kidslauncher.mdm.server

import java.io.File
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * "protect needs a descriptor" (launcher 0.29.0's dead VPN): Android's `Socket()` has no file
 * descriptor until a bind or connect, and `VpnService.protect(Socket)` reads it as it is (-1), so
 * protecting a fresh `Socket()` always failed and every upstream query was dropped. Every socket the
 * DNS engine protects is bound first ([protectedSocket]).
 */
class ProtectedSocketTest {
    private val src = listOf("src", "app/src").map(::File).first { File(it, "main/java").isDirectory }
    private fun source(name: String) = File(src, "main/java/com/kidslauncher/mdm/server/$name").readText()
    private fun code(text: String) = text.replace(Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL), "").replace(Regex("""//.*"""), "")

    @Test
    fun `protect sees a bound socket, before any connect`() {
        val seen = mutableListOf<Pair<Boolean, Boolean>>()
        protectedSocket({ seen += it.isBound to it.isConnected; true }).use { socket ->
            assertTrue(socket.isBound)
            assertFalse(socket.isConnected)
        }
        assertEquals(listOf(true to false), seen)
        // The factory OkHttp uses hands out the same.
        val factorySeen = mutableListOf<Boolean>()
        ProtectedSocketFactory { factorySeen += it.isBound; true }.createSocket().close()
        assertEquals(listOf(true), factorySeen)
    }

    @Test
    fun `a refused socket is closed and named`() {
        var refused: Socket? = null
        try {
            protectedSocket({ refused = it; false })
            fail("an unprotected socket was handed out")
        } catch (e: IOException) {
            assertTrue(e is NotProtectedException)
            assertEquals("NotProtectedException", e.javaClass.simpleName)
            assertTrue(refused!!.isClosed)
        }
    }

    @Test
    fun `the overloads with a local address bind once, then connect`() {
        ServerSocket(0, 16, InetAddress.getLoopbackAddress()).use { server ->
            val loopback = InetAddress.getLoopbackAddress()
            val seen = mutableListOf<InetSocketAddress?>()
            val factory = ProtectedSocketFactory { seen += it.localSocketAddress as? InetSocketAddress; true }
            factory.createSocket(loopback, server.localPort, loopback, 0).use { socket ->
                assertTrue(socket.isConnected)
                assertEquals(loopback, socket.localAddress)
            }
            factory.createSocket("127.0.0.1", server.localPort, loopback, 0).use { assertTrue(it.isConnected) }
            factory.createSocket(loopback, server.localPort).use { assertTrue(it.isConnected) }
            assertEquals(3, seen.size)
            assertEquals(loopback, seen[0]!!.address)
        }
    }

    @Test
    fun `no socket is protected without its bind`() {
        val transport = code(source("UpstreamTransport.kt"))
        // One plain Socket() in the file: protectedSocket's, bound before protect.
        assertEquals(1, Regex("""\bSocket\(\)""").findAll(transport).count())
        val body = transport.substringAfter("fun protectedSocket(").substringBefore("\n}\n")
        assertTrue(body.indexOf("socket.bind(local)") in 0 until body.indexOf("protect(socket)"))
        // DoT and the DoH factory both take it.
        assertTrue("protectedSocket(protect).use { plain ->" in transport.substringAfter("fun resolveDoT("))
        val factory = transport.substringAfter("class ProtectedSocketFactory(").substringBefore("\n}\n")
        assertTrue("override fun createSocket(): Socket = protectedSocket(protect)" in factory)
        assertFalse(Regex("""\bSocket\(\)""").containsMatchIn(factory))
        // Nothing else in the engine protects a socket of its own.
        for (name in listOf("KidVpnService.kt", "DnsFilterEngine.kt", "CustomDns.kt", "BootstrapDns.kt", "PublicResolver.kt", "UpstreamChooser.kt")) {
            assertFalse("$name makes its own socket", Regex("""\bSocket\(\)""").containsMatchIn(code(source(name))))
        }
    }
}
