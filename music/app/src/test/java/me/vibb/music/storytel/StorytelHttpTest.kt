package me.vibb.music.storytel

import me.vibb.music.bridge.StorytelLogin
import me.vibb.music.sonos.TinyHttp
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException
import java.net.Proxy

/**
 * [OkHttpStorytelHttp] against loopback servers (design 26b §4.1, §7): vibb `storytel_api.py` 4 over
 * real sockets - the asset 302 isn't followed and the CDN server sees nothing - the User-Agent on every
 * request (QA L8), the body cap and the call timeout.
 */
class StorytelHttpTest {
    private val client = OkHttpClient.Builder().proxy(Proxy.NO_PROXY).build()
    private val http = OkHttpStorytelHttp(client, "VibbMusic/test")

    private fun get(url: String, follow: Boolean, timeoutMs: Long = 5_000, headers: List<Pair<String, String>> = emptyList()) =
        http.send(StorytelRequest("GET", url.toHttpUrl(), headers, follow, timeoutMs))

    @Test
    fun `follow false captures the 302 and the CDN server sees nothing`() {
        TinyHttp { TinyHttp.Response(200, "audio", "audio/mpeg") }.use { cdn ->
            val location = "http://127.0.0.1:${cdn.port}/mp3/160661.mp3?consumableId=160661&token=FAKE-SIG-1"
            TinyHttp { TinyHttp.Response(302, "", "text/plain", mapOf("Location" to location)) }.use { api ->
                val r = get("http://127.0.0.1:${api.port}/assets/v2/consumables/160661/abook", follow = false, headers = listOf("Authorization" to "Bearer FAKE-JWT-1"))
                assertEquals(302, r.status)
                assertEquals(location, r.location)
                assertEquals(1, api.requests.size)
                assertEquals("Bearer FAKE-JWT-1", api.requests.single().headers["authorization"])
                assertTrue("the bearer never rides to the CDN: nothing reaches it", cdn.requests.isEmpty())
            }
        }
    }

    @Test
    fun `follow true follows a redirect (the login, as vibb)`() {
        TinyHttp { req ->
            if (req.path.startsWith("/old")) {
                TinyHttp.Response(302, "", "text/plain", mapOf("Location" to "/api/login.action?m=1"))
            } else {
                TinyHttp.Response(200, """{"accountInfo":{"jwt":"FAKE-JWT-1"}}""", "application/json")
            }
        }.use { server ->
            val r = get("http://127.0.0.1:${server.port}/old", follow = true)
            assertEquals(200, r.status)
            assertEquals("""{"accountInfo":{"jwt":"FAKE-JWT-1"}}""", r.body.toString(Charsets.UTF_8))
            assertEquals(2, server.requests.size)
        }
    }

    @Test
    fun `the User-Agent goes on every request, the caller's headers with it`() {
        TinyHttp { TinyHttp.Response(204, "", "text/plain") }.use { server ->
            get("http://127.0.0.1:${server.port}/a", follow = true)
            get("http://127.0.0.1:${server.port}/b", follow = false, headers = listOf("Accept" to "*/*", "User-Agent" to "okhttp/x"))
            assertEquals(listOf("VibbMusic/test", "VibbMusic/test"), server.requests.map { it.headers["user-agent"] })
            assertEquals("*/*", server.requests[1].headers["accept"])
        }
    }

    @Test
    fun `the body is read up to 64 KB`() {
        TinyHttp { TinyHttp.Response(200, "x".repeat(100_000), "text/plain") }.use { server ->
            assertEquals(StorytelResponse.BODY_MAX, get("http://127.0.0.1:${server.port}/big", follow = true).body.size)
        }
    }

    @Test
    fun `the call timeout bounds a request, as an IOException`() {
        TinyHttp { Thread.sleep(3_000); TinyHttp.Response(200, "late", "text/plain") }.use { server ->
            val started = System.nanoTime()
            try {
                get("http://127.0.0.1:${server.port}/slow", follow = false, timeoutMs = 300)
                fail("no answer within the timeout")
            } catch (_: IOException) {
                assertTrue((System.nanoTime() - started) / 1_000_000 < 2_500)
            }
        }
    }

    @Test
    fun `a mint end to end - login, asset, the 302 captured, the CDN untouched`() {
        TinyHttp { TinyHttp.Response(200, "audio", "audio/mpeg") }.use { cdn ->
            val location = "http://127.0.0.1:${cdn.port}/mp3/111.mp3?consumableId=111&token=FAKE-SIG-1"
            TinyHttp { req ->
                when {
                    req.path.startsWith("/api/login.action") -> TinyHttp.Response(200, """{"accountInfo":{"jwt":"FAKE-JWT-1"}}""", "application/json")
                    req.headers["authorization"] == "Bearer FAKE-JWT-1" -> TinyHttp.Response(302, "", "text/plain", mapOf("Location" to location))
                    else -> TinyHttp.Response(401, "", "text/plain")
                }
            }.use { storytel ->
                val base = "http://127.0.0.1:${storytel.port}"
                var elapsed = 0L
                val api = StorytelApi(
                    http, { StorytelLogin.Login(1, "k@example.invalid", "hemmelig") },
                    object : CooldownStore {
                        override fun refusedAt(generation: Long): Long? = null
                        override fun setRefused(generation: Long, wallMs: Long) = fail("refused")
                    },
                    { elapsed }, { 0 }, Endpoints("$base/api/login.action".toHttpUrl(), base.toHttpUrl()),
                )
                val mint = api.assetUrl("111", 6_000, elapsed + 10_000)
                assertEquals(location, (mint as Mint.Url).url)
                assertEquals(listOf("/api/login.action?m=1&uid=k%40example.invalid&pwd=AC6C4AAACBF142D09469371FC6BB8BE6", "/assets/v2/consumables/111/abook"), storytel.requests.map { it.path })
                assertFalse("no bearer on the login", storytel.requests[0].headers.containsKey("authorization"))
                assertTrue(cdn.requests.isEmpty())
                elapsed += 1
                assertTrue(api.assetUrl("111", 6_000, elapsed + 10_000) is Mint.Url)
                assertEquals("the JWT is kept: no second login", 3, storytel.requests.size)
            }
        }
    }
}
