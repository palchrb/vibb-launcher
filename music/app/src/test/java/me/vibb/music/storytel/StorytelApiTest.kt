package me.vibb.music.storytel

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.runInterruptible
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.vibb.music.Fixtures
import me.vibb.music.bridge.StorytelLogin
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * The phone's Storytel client (design 26b §4.1), vibb `tests/storytel_api.py` 1, 4, 5, 10b, 11 and the
 * spec's new rows (the login URL, the JWT per generation, M7's classes, L6's deadline, L9's
 * toStrings). The HTTP seam is faked: nothing here touches the network. vibb's tests are MIT, Copyright
 * (c) 2026 palchrb.
 */
class StorytelApiTest {
    private fun fixture(name: String): JsonObject = Json.parseToJsonElement(Fixtures.resource("storytel/$name")).jsonObject

    private val request = fixture("login_request.json")
    private val email = request.getValue("email").jsonPrimitive.content
    private val password = request.getValue("password").jsonPrimitive.content
    private val pwd = request.getValue("pwd").jsonPrimitive.content

    private fun answer(name: String): StorytelResponse {
        val f = fixture(name)
        return StorytelResponse(
            f.getValue("status").jsonPrimitive.int,
            f["location"]?.jsonPrimitive?.contentOrNull,
            f["body"]?.toString()?.toByteArray() ?: ByteArray(0),
        )
    }

    private val ok = answer("login_ok.json")
    private val cdn = answer("asset_302.json")

    private class Store : CooldownStore {
        var stamp: Pair<Long, Long>? = null
        override fun refusedAt(generation: Long): Long? = stamp?.takeIf { it.first == generation }?.second
        override fun setRefused(generation: Long, wallMs: Long) {
            stamp = generation to wallMs
        }
    }

    /** A scripted Storytel: the login and the asset call answer from these, every request recorded. */
    private inner class Rig(
        var stored: StorytelLogin.Login? = StorytelLogin.Login(1, email, password),
        var login: () -> StorytelResponse = { ok },
        var asset: (StorytelRequest) -> StorytelResponse = { cdn },
    ) {
        var elapsed = 1_000_000L
        var wall = 1_760_000_000_000L
        val store = Store()
        val requests = CopyOnWriteArrayList<StorytelRequest>()
        val api = StorytelApi(
            { r ->
                requests += r
                if (r.url.encodedPath.endsWith("login.action")) login() else asset(r)
            },
            { stored }, store, { elapsed }, { wall },
        )
        val logins get() = requests.filter { it.url.encodedPath.endsWith("login.action") }
        val assets get() = requests.filter { it.url.encodedPath.startsWith("/assets/") }

        fun mint(cid: String = "160661", timeoutMs: Long = 15_000, deadlineMs: Long = elapsed + 60_000) = api.assetUrl(cid, timeoutMs, deadlineMs)
    }

    @Test
    fun `1 the login password AES is uppercase hex, pinned to vibb's vector and three more`() {
        val vectors = fixture("aes_vectors.json").getValue("vectors").jsonArray
        assertEquals(4, vectors.size)
        for (v in vectors) {
            val o = v.jsonObject
            assertEquals(o.getValue("hex").jsonPrimitive.content, StorytelApi.encryptPassword(o.getValue("password").jsonPrimitive.content))
        }
        assertEquals("AC6C4AAACBF142D09469371FC6BB8BE6", StorytelApi.encryptPassword("hemmelig"))
        assertEquals(pwd, StorytelApi.encryptPassword(password))
    }

    @Test
    fun `the login URL is exactly vibb's, its redirects followed, at most 20 s`() {
        val rig = Rig()
        assertTrue(rig.mint() is Mint.Url)
        val login = rig.logins.single()
        assertEquals("https://www.storytel.com/api/login.action?" + request.getValue("query").jsonPrimitive.content, login.url.toString())
        assertEquals("https://www.storytel.com/api/login.action?m=1&uid=k%40example.invalid&pwd=AC6C4AAACBF142D09469371FC6BB8BE6", login.url.toString())
        assertEquals("GET", login.method)
        assertTrue(login.follow)
        assertEquals(20_000, login.timeoutMs)
        assertTrue("no bearer on the login", login.headers.isEmpty())
    }

    @Test
    fun `4 the asset 302 is captured, never followed - one request, the CDN never fetched`() {
        val rig = Rig()
        val mint = rig.mint("160661", 6_000, rig.elapsed + 10_000)
        assertEquals(cdn.location, (mint as Mint.Url).url)
        val asset = rig.assets.single()
        assertEquals("https://api.storytel.net/assets/v2/consumables/160661/abook", asset.url.toString())
        assertFalse("the 302 must be captured, not followed", asset.follow)
        assertEquals(6_000, asset.timeoutMs)
        assertEquals(listOf("Authorization" to "Bearer FAKE-JWT-1", "Accept" to "*/*"), asset.headers)
        assertEquals(2, rig.requests.size)
        assertTrue("nothing may fetch the CDN url from inside a mint", rig.requests.none { it.url.host == "cdn.example.invalid" })
    }

    @Test
    fun `5 a 401 re-logs in exactly once and retries once`() {
        var first = true
        val rig = Rig(asset = { if (first) { first = false; StorytelResponse(401, null, ByteArray(0)) } else cdn })
        assertTrue(rig.mint() is Mint.Url)
        assertEquals(2, rig.assets.size)
        assertEquals("the first login plus one re-login", 2, rig.logins.size)

        val always401 = Rig(asset = { StorytelResponse(401, null, ByteArray(0)) })
        assertEquals(Mint.Failed(401), always401.mint())
        assertEquals("never two re-logins", 2, always401.logins.size)
        assertEquals(2, always401.assets.size)
        // L4: the JWT a fresh login got and Storytel still refused is dropped: the next mint starts with a login.
        val before = always401.requests.size
        always401.mint()
        assertTrue(always401.requests[before].url.encodedPath.endsWith("login.action"))
    }

    @Test
    fun `the JWT is kept for an hour on the elapsed clock and per login generation`() {
        val rig = Rig()
        rig.mint(); rig.mint()
        assertEquals("one login for two mints", 1, rig.logins.size)
        rig.elapsed += StorytelApi.JWT_TTL_MS - 1
        rig.mint()
        assertEquals(1, rig.logins.size)
        rig.elapsed += 1
        rig.mint()
        assertEquals("expired after 1 h: a new login", 2, rig.logins.size)
        rig.wall += 365L * 24 * 3_600_000 // the wall clock never ages a JWT
        rig.mint()
        assertEquals(2, rig.logins.size)
        rig.stored = StorytelLogin.Login(2, email, "nytt-passord")
        rig.mint()
        assertEquals("a new login generation logs in again", 3, rig.logins.size)
        assertTrue(rig.logins.last().url.queryParameter("pwd") == StorytelApi.encryptPassword("nytt-passord"))
    }

    @Test
    fun `10b a refused login parks a 1 h cooldown, no request inside it, a new generation retries at once`() {
        val rig = Rig(login = { StorytelResponse(403, null, "no".toByteArray()) })
        val refused = rig.mint()
        assertEquals(Mint.Refused(rig.wall + StorytelApi.LOGIN_COOLDOWN_MS), refused)
        assertEquals(1L to rig.wall, rig.store.stamp)
        assertEquals("refused", rig.api.state())
        val n = rig.requests.size
        rig.wall += 30 * 60_000
        assertTrue(rig.mint() is Mint.Refused)
        assertEquals("the second attempt must not touch the network", n, rig.requests.size)
        assertTrue(rig.assets.isEmpty())

        rig.stored = StorytelLogin.Login(2, email, "fresh-pw")
        rig.login = { ok }
        assertEquals("ok", rig.api.state())
        assertTrue(rig.mint() is Mint.Url)
        assertEquals("a new password retries immediately", 2, rig.logins.size)
    }

    @Test
    fun `the cooldown ends after an hour, and a 5xx login counts as a refusal`() {
        val rig = Rig(login = { StorytelResponse(503, null, ByteArray(0)) })
        assertTrue(rig.mint() is Mint.Refused)
        rig.wall += StorytelApi.LOGIN_COOLDOWN_MS - 1
        assertTrue(rig.mint() is Mint.Refused)
        assertEquals(1, rig.logins.size)
        val back = rig.wall
        rig.wall = rig.store.stamp!!.second - 1
        assertEquals("a stamp in the future (the clock went back) is no cooldown", "ok", rig.api.state())
        rig.wall = back + 1
        rig.login = { ok }
        assertTrue(rig.mint() is Mint.Url)
        assertEquals(2, rig.logins.size)
    }

    @Test
    fun `200 without a JWT and an unreachable login fail the login without a cooldown`() {
        val noJwt = answer("login_no_jwt.json")
        val rig = Rig(login = { noJwt })
        assertEquals(Mint.LoginFailed("no jwt"), rig.mint())
        assertNull("no cooldown", rig.store.stamp)
        assertEquals("ok", rig.api.state())
        assertEquals(Mint.LoginFailed("no jwt"), rig.mint())
        assertEquals("each mint tries the login again (the run stops on the first)", 2, rig.logins.size)
        assertTrue(rig.assets.isEmpty())

        for (body in listOf("", "{", "[]", """{"accountInfo":{"jwt":""}}""", """{"accountInfo":{"jwt":5}}""", """{"accountInfo":null}""")) {
            assertEquals(body, Mint.LoginFailed("no jwt"), Rig(login = { StorytelResponse(200, null, body.toByteArray()) }).mint())
        }

        val unwritable = Rig(login = { StorytelResponse(403, null, ByteArray(0)) })
        val store = object : CooldownStore {
            override fun refusedAt(generation: Long): Long? = null
            override fun setRefused(generation: Long, wallMs: Long) = throw IOException("disk full")
        }
        val api = StorytelApi({ unwritable.login() }, { StorytelLogin.Login(1, email, password) }, store, { 0 }, { 5 })
        assertEquals("L1: a failed cooldown write never throws out of a mint", Mint.Refused(5 + StorytelApi.LOGIN_COOLDOWN_MS), api.assetUrl("1", 1_000, 10_000))

        val down = Rig(login = { throw IOException("unreachable") })
        assertEquals(Mint.LoginFailed("unreachable"), down.mint())
        assertNull(down.store.stamp)
    }

    @Test
    fun `11 no login degrades to NotConfigured with no request`() {
        val rig = Rig(stored = null)
        assertEquals(Mint.NotConfigured, rig.mint())
        assertEquals("none", rig.api.state())
        assertTrue(rig.requests.isEmpty())

        val broken = StorytelApi({ error("no request") }, { throw IllegalStateException("keystore") }, Store(), { 0 }, { 0 })
        assertEquals("a login that can't be opened", Mint.NotConfigured, broken.assetUrl("1", 1_000, 10_000))
        assertEquals("none", broken.state())

        val cleared = Rig()
        assertTrue(cleared.mint() is Mint.Url)
        cleared.stored = null
        assertEquals("generation 0 deleted the login: the kept JWT is not used", Mint.NotConfigured, cleared.mint())
        assertEquals(1, cleared.assets.size)
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
        for (s in listOf(408, 429, 500, 502, 503, 504, 400, 418)) assertEquals(Mint.Failed(s), classOf(s))
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
    fun `the deadline bounds the whole mint, the login included`() {
        val rig = Rig()
        assertEquals(Mint.LoginFailed("deadline"), rig.mint(deadlineMs = rig.elapsed))
        assertTrue(rig.requests.isEmpty())

        val near = Rig()
        near.mint(timeoutMs = 6_000, deadlineMs = near.elapsed + 5_000)
        assertEquals("the login gets what is left, not 20 s", 5_000, near.logins.single().timeoutMs)
        assertEquals("the asset call too", 5_000, near.assets.single().timeoutMs)

        val slow = Rig()
        slow.login = { slow.elapsed += 9_000; ok }
        assertEquals("no time left for the asset call", Mint.Unreachable("deadline"), slow.mint(timeoutMs = 6_000, deadlineMs = slow.elapsed + 9_000))
        assertTrue(slow.assets.isEmpty())

        val timedOut = Rig()
        timedOut.login = { timedOut.elapsed += 10_000; throw IOException("timeout") }
        assertEquals(Mint.LoginFailed("deadline"), timedOut.mint(timeoutMs = 6_000, deadlineMs = timedOut.elapsed + 10_000))
    }

    @Test
    fun `the wait for another's login counts against the deadline, and its JWT serves the waiter (L6)`() {
        val inLogin = CountDownLatch(1)
        val release = CountDownLatch(1)
        val logins = CopyOnWriteArrayList<String>()
        val clock = { System.nanoTime() / 1_000_000 }
        val http = StorytelHttp { r ->
            if (r.url.encodedPath.endsWith("login.action")) {
                logins += "login"
                inLogin.countDown()
                release.await(10, TimeUnit.SECONDS)
                ok
            } else {
                cdn
            }
        }
        val api = StorytelApi(http, { StorytelLogin.Login(1, email, password) }, Store(), clock, { 0 })
        var downloadMint: Mint? = null
        val download = thread { downloadMint = api.assetUrl("111", 15_000, clock() + 60_000) }
        assertTrue(inLogin.await(5, TimeUnit.SECONDS))

        val started = clock()
        val sonos = api.assetUrl("222", 6_000, clock() + 300)
        assertEquals("the Sonos mint gives up at its deadline", Mint.LoginFailed("deadline"), sonos)
        assertTrue(clock() - started < 5_000)

        var waiterMint: Mint? = null
        val waiter = thread { waiterMint = api.assetUrl("333", 6_000, clock() + 10_000) }
        Thread.sleep(100)
        release.countDown()
        download.join(5_000)
        waiter.join(5_000)
        assertTrue(downloadMint is Mint.Url)
        assertTrue("the waiter got the lock after the login and used its JWT", waiterMint is Mint.Url)
        assertEquals("never two logins at once, nor one after the other", 1, logins.size)
    }

    /**
     * The main session's review of step 3, Low 2: `StorytelAccount.mint` is `runInterruptible(Dispatchers.IO) {
     * api.assetUrl(...) }` (GuardsTest pins the line); here the same construct over the JVM's StorytelApi. A
     * cancelled caller interrupts the thread: the wait for another's login ends at once (not at the 60 s
     * deadline), and so does a request blocked in an interruptible wait (OkHttp's Okio checks the flag at
     * each read and write); the caller gets a CancellationException, never the mint's answer, and no
     * cooldown is written for an interrupted login.
     */
    @Test
    fun `a cancelled mint interrupts its thread - the lock's wait and a blocked request end at once`() = runBlocking {
        val clock = { System.nanoTime() / 1_000_000 }
        val inLogin = CountDownLatch(1)
        val release = CountDownLatch(1)
        val interrupted = CopyOnWriteArrayList<String>()
        val http = StorytelHttp { r ->
            if (r.url.encodedPath.endsWith("login.action")) {
                inLogin.countDown()
                try {
                    release.await(30, TimeUnit.SECONDS)
                } catch (e: InterruptedException) {
                    interrupted += "login"
                    throw e
                }
                ok
            } else {
                cdn
            }
        }
        val store = Store()
        val api = StorytelApi(http, { StorytelLogin.Login(1, email, password) }, store, clock, { 0 })
        // A download job's mint holds the login lock, blocked in its request.
        val download = async(Dispatchers.IO) { runInterruptible(Dispatchers.IO) { api.assetUrl("111", 15_000, clock() + 60_000) } }
        assertTrue(inLogin.await(5, TimeUnit.SECONDS))
        // A second mint waits for the lock (its deadline 60 s away); cancelled, it stops waiting at once.
        val waiter = async(Dispatchers.IO) { runInterruptible(Dispatchers.IO) { api.assetUrl("222", 15_000, clock() + 60_000) } }
        Thread.sleep(100)
        var started = clock()
        waiter.cancel()
        assertTrue("the waiter is cancelled", runCatching { waiter.await() }.exceptionOrNull() is CancellationException)
        assertTrue("within a second, not at its deadline", clock() - started < 1_000)
        // The holder's request is blocked; cancelled, its thread is interrupted out of it.
        started = clock()
        download.cancel()
        assertTrue(runCatching { download.await() }.exceptionOrNull() is CancellationException)
        assertTrue(clock() - started < 1_000)
        assertEquals(listOf("login"), interrupted.toList())
        assertNull("an interrupted login is no refusal", store.stamp)
        // The lock is free again and the next mint logs in normally.
        release.countDown()
        assertTrue(runInterruptible(Dispatchers.IO) { api.assetUrl("333", 15_000, clock() + 10_000) } is Mint.Url)
    }

    @Test
    fun `a 401 whose token another mint already replaced takes the new one without a login`() {
        // The outer mint's 401 arrives after an inner (concurrent) mint has re-logged in: it uses JWT 2.
        var jwts = 0
        val seen = mutableListOf<String>()
        val rig = Rig()
        rig.login = { jwts++; StorytelResponse(200, null, """{"accountInfo":{"jwt":"FAKE-JWT-$jwts"}}""".toByteArray()) }
        var inner: Mint? = null
        rig.asset = { r ->
            val bearer = r.headers.first { it.first == "Authorization" }.second
            seen += bearer
            when {
                bearer != "Bearer FAKE-JWT-1" -> cdn
                seen.size == 1 -> StorytelResponse(401, null, ByteArray(0)).also { inner = rig.mint("222") }
                else -> StorytelResponse(401, null, ByteArray(0))
            }
        }
        assertTrue(rig.mint("111") is Mint.Url)
        assertTrue(inner is Mint.Url)
        assertEquals(listOf("Bearer FAKE-JWT-1", "Bearer FAKE-JWT-1", "Bearer FAKE-JWT-2", "Bearer FAKE-JWT-2"), seen)
        assertEquals("the first login and the inner mint's re-login, no third", 2, rig.logins.size)
    }

    @Test
    fun `secrets never reach a toString or a reason (L9)`() {
        val rig = Rig()
        val mint = rig.mint()
        val secrets = listOf(email, password, pwd, "FAKE-JWT-1", "FAKE-SIG-1", "token=", "pwd=", "Bearer", "example.invalid/mp3")
        val printed = listOf(
            mint.toString(),
            rig.requests.joinToString(),
            ok.toString(),
            cdn.toString(),
            StorytelLogin.Login(1, email, password).toString(),
            listOf(Mint.LoginFailed("no jwt"), Mint.Unreachable("unreachable"), Mint.Refused(1), Mint.NotConfigured).joinToString(),
        )
        for (text in printed) for (s in secrets) assertFalse("'$text' holds '$s'", text.contains(s))
        assertTrue(rig.requests.first().toString().contains("/api/login.action"))
        assertTrue(StorytelLogin.Login(7, email, password).toString().contains("generation=7"))
        assertSame(Mint.NotConfigured, Mint.NotConfigured)
    }

    @Test
    fun `the endpoints can be pointed elsewhere, the asset path stays`() {
        val endpoints = Endpoints("http://127.0.0.1:8730/api/login.action".toHttpUrl(), "http://127.0.0.1:8730".toHttpUrl())
        val requests = mutableListOf<StorytelRequest>()
        val api = StorytelApi({ r -> requests += r; if (r.url.encodedPath.endsWith("login.action")) ok else cdn }, { StorytelLogin.Login(1, email, password) }, Store(), { 0 }, { 0 }, endpoints)
        assertTrue(api.assetUrl("111", 1_000, 10_000) is Mint.Url)
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
            answer("asset_302.json").location!!,
        )
        for (location in locations) {
            val minted = StorytelApi.mintedUrl(location)!!
            assertEquals(location, minted, minted.toHttpUrl().toString())
        }
        assertEquals("https://cdn.example.invalid/mp3/x.mp3?consumableId=160661&token=FAKE-SIG-1", StorytelApi.mintedUrl(locations[2]))
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
                assertEquals(base.toHttpUrl().newBuilder().encodedPath("/api/login.action").build(), StorytelEndpoints.endpoints().login)
            }
            StorytelEndpoints.debugBase = "http://10.0.2.2:8730/some/path"
            assertEquals("http://10.0.2.2:8730/", StorytelEndpoints.endpoints().api.toString())
            assertEquals("http://10.0.2.2:8730/api/login.action", StorytelEndpoints.endpoints().login.toString())
            for (base in listOf("http://192.168.1.20:8730", "http://evil.example.invalid", "https://10.0.2.2.example.invalid", "http://u:p@10.0.2.2:8730", "10.0.2.2:8730", "not a url", "", null)) {
                StorytelEndpoints.debugBase = base
                assertEquals("$base", Endpoints.STORYTEL, StorytelEndpoints.endpoints())
                assertFalse("$base", StorytelEndpoints.apiOverLan())
                assertTrue("$base", StorytelEndpoints.publicDownloads())
                assertFalse("$base", StorytelEndpoints.privateMintAllowed())
            }
        } finally {
            StorytelEndpoints.debugBase = before
        }
    }
}
