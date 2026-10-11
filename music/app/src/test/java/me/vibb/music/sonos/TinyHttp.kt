package me.vibb.music.sonos

import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.InputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.SocketException
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.concurrent.thread

/**
 * A one-request-per-connection HTTP server on loopback for the SOAP tests. Plain sockets: unit tests
 * compile against android.jar, which has no com.sun.net.httpserver, and the module has no
 * MockWebServer.
 */
class TinyHttp(private val handler: (Request) -> Response) : Closeable {
    data class Request(val method: String, val path: String, val headers: Map<String, String>, val body: String)

    data class Response(val code: Int, val body: String, val contentType: String = "text/xml; charset=\"utf-8\"")

    private val server = ServerSocket(0, 50, InetAddress.getLoopbackAddress())
    val port: Int get() = server.localPort
    val requests = CopyOnWriteArrayList<Request>()

    init {
        thread(isDaemon = true, name = "tiny-http") {
            while (!server.isClosed) {
                val socket = try {
                    server.accept()
                } catch (_: SocketException) {
                    break
                }
                socket.use { s ->
                    val input = BufferedInputStream(s.getInputStream())
                    val head = readHead(input) ?: return@use
                    val lines = head.split("\r\n")
                    val (method, path) = lines[0].split(" ").let { it[0] to it[1] }
                    val headers = lines.drop(1).filter { ':' in it }.associate { it.substringBefore(':').trim().lowercase() to it.substringAfter(':').trim() }
                    val length = headers["content-length"]?.toIntOrNull() ?: 0
                    val body = ByteArray(length).also { var n = 0; while (n < length) n += input.read(it, n, length - n) }
                    val request = Request(method, path, headers, String(body, Charsets.UTF_8))
                    requests.add(request)
                    val response = handler(request)
                    val bytes = response.body.toByteArray(Charsets.UTF_8)
                    val out = s.getOutputStream()
                    out.write(
                        ("HTTP/1.1 ${response.code} X\r\nContent-Type: ${response.contentType}\r\n" +
                            "Content-Length: ${bytes.size}\r\nConnection: close\r\n\r\n").toByteArray(Charsets.US_ASCII),
                    )
                    out.write(bytes)
                    out.flush()
                }
            }
        }
    }

    private fun readHead(input: InputStream): String? {
        val buf = ByteArrayOutputStream()
        var last4 = 0
        while (true) {
            val b = input.read()
            if (b < 0) return null
            buf.write(b)
            last4 = (last4 shl 8) or b
            if (last4 == 0x0D0A0D0A) return buf.toString(Charsets.US_ASCII.name()).trimEnd()
        }
    }

    override fun close() = server.close()
}
