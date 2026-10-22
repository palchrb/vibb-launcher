package me.vibb.music.storytel

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.vibb.music.Fixtures
import me.vibb.music.bridge.JwtResult
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * The phone's Storytel client (design 26b §4.1; since design 58 §5.1 the asset call only - the JWT is the
 * server's, StorytelSessionTest), vibb `tests/storytel_api.py` 4 and the spec's rows (M7's classes, L6's
 * deadline, L9's toStrings). The HTTP seam is faked: nothing here touches the network. vibb's tests are
 * MIT, Copyright (c) 2026 palchrb.
 */
class StorytelApiTest {
    private fun answer(name: String): StorytelResponse {
        val f = Json.parseToJsonElement(Fixtures.resource("storytel/$name")).jsonObject
        return StorytelResponse(
            f.getValue("status").jsonPrimitive.int,
            f["location"]?.jsonPrimitive?.contentOrNull,
            f["body"]?.toString()?.toByteArray() ?: ByteArray(0),
        )
    }

    private val cdn = answer("asset_302.json")

    /** A scripted Storytel API: the asset call answers from [asset], every request recorded. */
    private inner class Rig(var asset: (StorytelRequest) -> StorytelResponse = { cdn }) {
        var elapsed = 1_000_000L
        val requests = CopyOnWriteArrayList<StorytelRequest>()
        val api = StorytelApi({ r -> requests += r; asset(r) }, { elapsed })

        fun mint(cid: String = "160661", timeoutMs: Long = 15_000, deadlineMs: Long = elapsed + 60_000, jwt: String = "FAKE-JWT-1") =
            runBlocking { api.assetUrl(cid, jwt, timeoutMs, deadlineMs) }
    }

    @Test
    fun `4 the asset 302 is captured, never followed - one request with the server's JWT, the CDN never fetched`() {
        val rig = Rig()
        val mint = rig.mint("160661", 6_000, rig.elapsed + 10_000, jwt = "FAKE-JWT-7")
        assertEquals(cdn.location, (mint as Mint.Url).url)
        val asset = rig.requests.single()
        assertEquals("https://api.storytel.net/assets/v2/consumables/160661/abook", asset.url.toString())
        assertEquals("GET", asset.method)
        assertFalse("the 302 must be captured, not followed", asset.follow)
        assertEquals(6_000, asset.timeoutMs)
        assertEquals(listOf("Authorization" to "Bearer FAKE-JWT-7", "Accept" to "*/*"), asset.headers)
        assertTrue("nothing may fetch the CDN url from inside a mint", rig.requests.none { it.url.host == "cdn.example.invalid" })
    }

    @Test
    fun `each asset status maps to its class (M7)`() {
        fun classOf(status: Int, location: String? = null): Mint = Rig(asset = { StorytelResponse(status, location, ByteArray(0)) }).mint()
        for (s in listOf(301, 302, 303, 307, 308)) assertEquals("$s without Location", Mint.NoAudio(s), classOf(s))
        assertEquals("a relative Location is no usable one", Mint.NoAudio(302), classOf(302, "/mp3/160661"))
        assertEquals(Mint.NoAudio(302), classOf(302, "ftp://cdn.example.invalid/x"))
        assertEquals(Mint.NoAudio(302), classOf(302, "https://user:pw@cdn.example.invalid/x"))
        assertEquals(Mint.NoAudio(302), classOf(302, "https://user@cdn.example.invalid/x"))
        assertEquals(Mint.NoAudio(302), classOf(302, ""))
        assertEquals("too long", Mint.NoAudio(302), classOf(302, "https://cdn.example.invalid/x?token=" + "A".repeat(StorytelApi.MINTED_URL_MAX)))
        // L3: what OkHttp and ExoPlayer take, java.net.URI may not; the URL comes back canonically encoded.
        assertEquals("https://cdn.example.invalid/a?consumableId=1&token=FAKE|SIG", (classOf(302, "https://cdn.example.invalid/a?consumableId=1&token=FAKE|SIG") as Mint.Url).url)
        assertEquals("https://cdn.example.invalid/a%20b.mp3?consumableId=1", (classOf(302, "https://cdn.example.invalid/a b.mp3?consumableId=1") as Mint.Url).url)
        assertTrue("a signed URL longer than a listing's 2000", classOf(302, "https://cdn.example.invalid/x?token=" + "A".repeat(3000)) is Mint.Url)
        for (s in listOf(403, 404, 410, 451)) assertEquals(Mint.NoAudio(s), classOf(s))
        for (s in listOf(401, 408, 429, 500, 502, 503, 504, 400, 418)) assertEquals(Mint.Failed(s), classOf(s))
        assertEquals("200 without a redirect", Mint.Failed(200), classOf(200))
        assertEquals("a Location counts only on a redirect", Mint.Failed(500), classOf(500, "https://cdn.example.invalid/x"))
        assertTrue(classOf(307, "http://cdn.example.invalid/x?consumableId=1&token=FAKE-SIG-2") is Mint.Url)

        assertEquals(Mint.Unreachable("unreachable"), Rig(asset = { throw IOException("reset") }).mint())
        assertEquals(Mint.Unreachable("unreachable"), Rig(asset = { throw IllegalStateException("bug") }).mint())

        val locked = answer("asset_locked.json")
        assertEquals(Mint.NoAudio(403), Rig(asset = { locked }).mint("999"))
    }

    @Test
    fun `a malformed consumable id is never requested`() {
        val rig = Rig()
        assertEquals(Mint.NoAudio(0), rig.mint("../../libraries/bookshelf"))
        assertEquals(Mint.NoAudio(0), rig.mint(""))
        assertTrue(rig.requests.isEmpty())
    }

    @Test
    fun `the deadline bounds the asset call`() {
        val rig = Rig()
        assertEquals(Mint.Unreachable("deadline"), rig.mint(deadlineMs = rig.elapsed))
        assertTrue("no time left: no request", rig.requests.isEmpty())
        rig.mint(timeoutMs = 6_000, deadlineMs = rig.elapsed + 4_000)
        assertEquals("the asset call gets what is left of the mint", 4_000, rig.requests.single().timeoutMs)

        val timedOut = Rig()
        timedOut.asset = { timedOut.elapsed += 6_000; throw IOException("timeout") }
        assertEquals(Mint.Unreachable("deadline"), timedOut.mint(timeoutMs = 6_000, deadlineMs = timedOut.elapsed + 6_000))
    }

    /**
     * The main session's review of step 3, Low 2 (design 58 §5.1: the HTTP send in `runInterruptible`): a
     * cancelled caller - a download job WorkManager stops - interrupts the request blocked in the seam
     * (OkHttp's Okio checks the flag at each read and write) and gets a CancellationException at once, never
     * the mint's answer.
     */
    @Test
    fun `a cancelled mint ends its blocked request at once`() = runBlocking {
        val inRequest = CountDownLatch(1)
        val interrupted = CopyOnWriteArrayList<String>()
        val api = StorytelApi({
            inRequest.countDown()
            try {
                CountDownLatch(1).await(30, TimeUnit.SECONDS)
            } catch (e: InterruptedException) {
                interrupted += "asset"
                throw e
            }
            cdn
        }, { 0 })
        val mint = async(Dispatchers.IO) { api.assetUrl("111", "FAKE-JWT-1", 15_000, 60_000) }
        assertTrue(inRequest.await(5, TimeUnit.SECONDS))
        val started = System.nanoTime()
        mint.cancel()
        assertTrue("the caller gets its cancellation", runCatching { mint.await() }.exceptionOrNull() is CancellationException)
        assertTrue("within a second, not at its deadline", System.nanoTime() - started < 1_000_000_000L)
        assertEquals(listOf("asset"), interrupted.toList())
    }

    @Test
    fun `secrets never reach a toString or a reason (L9)`() {
        val rig = Rig()
        val mint = rig.mint()
        val secrets = listOf("FAKE-JWT-1", "FAKE-SIG-1", "token=", "Bearer", "example.invalid/mp3")
        val printed = listOf(
            mint.toString(),
            rig.requests.joinToString(),
            cdn.toString(),
            listOf(Mint.LoginFailed("unreachable", 1), Mint.Unreachable("unreachable"), Mint.Refused(1), Mint.NotConfigured).joinToString(),
            StorytelSession.Jwt("FAKE-JWT-1", 1, 1, "1:00000000000000a1", 1).toString(),
        )
        for (text in printed) for (s in secrets + "00000000000000a1") assertFalse("'$text' holds '$s'", text.contains(s))
        assertTrue(rig.requests.first().toString().contains("/assets/v2/consumables/160661/abook"))
    }

    @Test
    fun `the endpoints can be pointed elsewhere, the asset path stays`() {
        val requests = mutableListOf<StorytelRequest>()
        val api = StorytelApi({ r -> requests += r; cdn }, { 0 }, Endpoints("http://127.0.0.1:8730".toHttpUrl()))
        assertTrue(runBlocking { api.assetUrl("111", "FAKE-JWT-1", 1_000, 10_000) } is Mint.Url)
        assertEquals("http://127.0.0.1:8730/assets/v2/consumables/111/abook", requests.last().url.toString())
    }

    /**
     * qa-26b-step3-code L5: the minted URL is OkHttp's canonical form - what `OkHttpDataSource.getUri()`
     * returns for the request - so `CacheDataSource` never writes it (a token) into `exo_redir`.
     */
    @Test
    fun `the minted URL is canonical, so the cache index never records it`() {
        val locations = listOf(
            "https://cdn.example.invalid/a?consumableId=1&token=FAKE|SIG",
            "https://cdn.example.invalid/a b.mp3?consumableId=1",
            "HTTPS://CDN.Example.Invalid:443/mp3/x.mp3?consumableId=160661&token=FAKE-SIG-1",
            "http://10.0.2.2:8730/cdn/111.wav?consumableId=111&token=FAKE-SIG-2",
            "https://cdn.example.invalid/%7Ex/../y.mp3?consumableId=1&token=a%2Fb+c",
            cdn.location!!,
        )
        for (location in locations) {
            val minted = StorytelApi.mintedUrl(location)!!
            assertEquals(location, minted, minted.toHttpUrl().toString())
        }
        assertEquals("https://cdn.example.invalid/mp3/x.mp3?consumableId=160661&token=FAKE-SIG-1", StorytelApi.mintedUrl(locations[2]))
    }

    /** Design 58 §5.6: the fake's session route, the debug switch only - the route's bodies, read as the bridge's. */
    @Test
    fun `the fake's session route - the stale session as a query, the route's shape read`() {
        val requests = mutableListOf<StorytelRequest>()
        var body = """{"status":"ok","jwt":"FAKE-JWT-2","expires_in_s":120,"session":"1:00000000000000a2"}"""
        val api = StorytelApi({ r -> requests += r; StorytelResponse(200, null, body.toByteArray()) }, { 5 })
        val url = "http://10.0.2.2:8730/fake/jwt".toHttpUrl()
        assertEquals(JwtResult.Ok("FAKE-JWT-2", 5 + 120_000, "1:00000000000000a2"), api.debugJwt(url, null))
        assertEquals("http://10.0.2.2:8730/fake/jwt", requests.last().url.toString())
        assertFalse(requests.last().follow)
        api.debugJwt(url, "1:00000000000000a1")
        assertEquals("1:00000000000000a1", requests.last().url.queryParameter("stale"))
        body = """{"status":"busy","retry_after_s":12}"""
        assertEquals(JwtResult.Busy(12_000), api.debugJwt(url, null))
        body = """{"status":"ok","jwt":"FAKE-JWT-2","expires_in_s":86401,"session":"1:00000000000000a2"}"""
        assertEquals("a life past a day is no answer", JwtResult.Unreachable("launcher"), api.debugJwt(url, null))
        for (junk in listOf("", "[]", "{", "null")) {
            body = junk
            assertEquals(junk, JwtResult.Unreachable("unreachable"), api.debugJwt(url, null))
        }
        assertEquals(JwtResult.Unreachable("unreachable"), StorytelApi({ throw IOException("down") }, { 0 }).debugJwt(url, null))
    }

    /** qa-26b-step3-code L2: the debug switch takes only the fake's homes, the emulator's host and loopback. */
    @Test
    fun `the debug base is taken only for 10_0_2_2 and loopback`() {
        val before = StorytelEndpoints.debugBase
        try {
            for (base in listOf("http://10.0.2.2:8730", "http://127.0.0.1:8730", "http://localhost:8730", "http://[::1]:8730")) {
                StorytelEndpoints.debugBase = base
                assertTrue(base, StorytelEndpoints.apiOverLan())
                assertFalse(base, StorytelEndpoints.publicDownloads())
                assertTrue(base, StorytelEndpoints.privateMintAllowed())
                assertEquals(base.toHttpUrl().newBuilder().encodedPath("/").build(), StorytelEndpoints.endpoints().api)
                assertEquals(base.toHttpUrl().newBuilder().encodedPath("/fake/jwt").build(), StorytelEndpoints.debugJwt())
            }
            StorytelEndpoints.debugBase = "http://10.0.2.2:8730/some/path"
            assertEquals("http://10.0.2.2:8730/", StorytelEndpoints.endpoints().api.toString())
            assertEquals("http://10.0.2.2:8730/fake/jwt", StorytelEndpoints.debugJwt().toString())
            for (base in listOf("http://192.168.1.20:8730", "http://evil.example.invalid", "https://10.0.2.2.example.invalid", "http://u:p@10.0.2.2:8730", "10.0.2.2:8730", "not a url", "", null)) {
                StorytelEndpoints.debugBase = base
                assertEquals("$base", Endpoints.STORYTEL, StorytelEndpoints.endpoints())
                assertFalse("$base", StorytelEndpoints.apiOverLan())
                assertTrue("$base", StorytelEndpoints.publicDownloads())
                assertFalse("$base", StorytelEndpoints.privateMintAllowed())
                assertNull("$base", StorytelEndpoints.debugJwt())
            }
        } finally {
            StorytelEndpoints.debugBase = before
        }
    }
}
