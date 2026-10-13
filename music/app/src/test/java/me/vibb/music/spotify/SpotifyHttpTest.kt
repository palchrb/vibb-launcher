package me.vibb.music.spotify

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.vibb.music.Fixtures
import me.vibb.music.bridge.TokenResult
import me.vibb.music.sonos.TinyHttp
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException
import java.net.Proxy

/**
 * [OkHttpSpotifyHttp] against loopback servers (design 26c §5.4): the bytes on the wire are
 * play_request.json's, a redirect is never followed (the token rides nowhere else), `Retry-After`
 * is read, the body capped, the call timed out; and a whole start through it.
 */
class SpotifyHttpTest {
    private val client = OkHttpClient.Builder().proxy(Proxy.NO_PROXY).build()
    private val http = OkHttpSpotifyHttp(client, "VibbMusic/test")
    private val fixture = Json.parseToJsonElement(Fixtures.resource("spotify/play_request.json")).jsonObject
    private val token = fixture["token"]!!.jsonPrimitive.content
    private val device = fixture["device_id"]!!.jsonPrimitive.content

    private fun headerOf(name: String) = fixture["headers"]!!.jsonObject[name]!!.jsonPrimitive.content

    @Test
    fun `the wire bytes are play_request json's`() {
        TinyHttp { TinyHttp.Response(204, "", "text/plain") }.use { server ->
            val api = SpotifyApi(http, "http://127.0.0.1:${server.port}".toHttpUrl()) { 0 }
            val uris = listOf("spotify:track:FAKEtrack0000000000001", "spotify:track:FAKEtrack0000000000002", "spotify:track:FAKEtrack0000000000003")
            assertEquals(PlayOutcome.Ok, api.play(token, device, PlayBody.Uris(uris, 42_000)))
            assertEquals(PlayOutcome.Ok, api.transfer(token, device))
            assertEquals(PlayOutcome.Ok, api.queue(token, device, "spotify:track:FAKEtrack0000000000004"))
            for ((i, name) in listOf("play", "transfer", "queue").withIndex()) {
                val want = fixture[name]!!.jsonObject
                val got = server.requests[i]
                val query = want["query"]!!.let { if (it is kotlinx.serialization.json.JsonNull) "" else "?" + it.jsonPrimitive.content }
                assertEquals(name, want["method"]!!.jsonPrimitive.content, got.method)
                assertEquals(name, want["path"]!!.jsonPrimitive.content + query, got.path)
                assertEquals(name, headerOf("authorization"), got.headers["authorization"])
                assertEquals(name, headerOf("accept"), got.headers["accept"])
                assertEquals(name, "VibbMusic/test", got.headers["user-agent"])
                val body = want["body"]!!.let { if (it is kotlinx.serialization.json.JsonNull) "" else it.jsonPrimitive.content }
                assertEquals(name, body, got.body)
                if (body.isNotEmpty()) assertEquals(name, headerOf("content-type"), got.headers["content-type"])
            }
            assertEquals("the queue add's empty POST", "0", server.requests[2].headers["content-length"])
        }
    }

    @Test
    fun `a redirect is never followed, Retry-After is read, the body capped`() {
        TinyHttp { TinyHttp.Response(204, "", "text/plain") }.use { elsewhere ->
            TinyHttp { req ->
                when (req.path) {
                    "/moved" -> TinyHttp.Response(302, "", "text/plain", mapOf("Location" to "http://127.0.0.1:${elsewhere.port}/x"))
                    "/busy" -> TinyHttp.Response(429, """{"error":{"status":429}}""", "application/json", mapOf("Retry-After" to "2"))
                    else -> TinyHttp.Response(200, "x".repeat(100_000), "text/plain")
                }
            }.use { server ->
                fun send(path: String) = http.send(
                    SpotifyRequest("PUT", "http://127.0.0.1:${server.port}$path".toHttpUrl(), listOf("Authorization" to "Bearer $token"), "{}", 5_000),
                )
                assertEquals(302, send("/moved").status)
                assertTrue("the bearer never rides elsewhere", elsewhere.requests.isEmpty())
                val busy = send("/busy")
                assertEquals(429, busy.status)
                assertEquals("2", busy.retryAfter)
                assertEquals(SpotifyResponse.BODY_MAX, send("/big").body.size)
                val request = SpotifyRequest("PUT", "http://127.0.0.1:${server.port}/v1/me/player/play?device_id=$device".toHttpUrl(),
                    listOf("Authorization" to "Bearer $token"), "{}", 5_000)
                assertTrue("printed without the token or the device", !request.toString().contains(token) && !request.toString().contains(device))
                assertTrue("a token answer's body isn't printed", !SpotifyResponse(200, null, "FAKE-ACCESS-9".toByteArray()).toString().contains("FAKE"))
            }
        }
    }

    @Test
    fun `the call timeout bounds a request, as an IOException`() {
        TinyHttp { Thread.sleep(3_000); TinyHttp.Response(204, "", "text/plain") }.use { server ->
            val started = System.nanoTime()
            try {
                http.send(SpotifyRequest("PUT", "http://127.0.0.1:${server.port}/slow".toHttpUrl(), emptyList(), "{}", 300))
                fail("no answer within the timeout")
            } catch (_: IOException) {
                assertTrue((System.nanoTime() - started) / 1_000_000 < 2_500)
            }
        }
    }

    @Test
    fun `a whole start over loopback - 404, the transfer, the play again`() {
        TinyHttp { req ->
            when {
                req.method == "PUT" && req.path == "/v1/me/player" -> TinyHttp.Response(204, "", "text/plain")
                req.method == "PUT" && req.path.startsWith("/v1/me/player/play") ->
                    if (TinyHttpCount.plays++ == 0) TinyHttp.Response(404, """{"error":{"status":404,"reason":"NO_ACTIVE_DEVICE"}}""", "application/json")
                    else TinyHttp.Response(204, "", "text/plain")
                else -> TinyHttp.Response(500, "", "text/plain")
            }
        }.use { server ->
            TinyHttpCount.plays = 0
            var clock = 0L
            val api = SpotifyApi(http, "http://127.0.0.1:${server.port}".toHttpUrl()) { clock }
            val result = runBlocking {
                api.start(device, PlayBody.Uris(listOf("spotify:track:FAKEtrack0000000000001"), 0), 25_000,
                    tokens = { TokenResult.Ok(token, 3_600_000, "2:1") }, awaitDevice = { clock += it; true })
            }
            assertEquals(StartResult.Sent, result)
            assertEquals(listOf("PUT", "PUT", "PUT"), server.requests.map { it.method })
            assertEquals(listOf("/v1/me/player/play?device_id=$device", "/v1/me/player", "/v1/me/player/play?device_id=$device"), server.requests.map { it.path })
        }
    }

    private object TinyHttpCount {
        @Volatile
        var plays = 0
    }
}
