package me.vibb.music.spotify

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.vibb.music.Fixtures
import me.vibb.music.bridge.TokenResult
import me.vibb.music.logic.SpotifyProblem
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/**
 * The phone's Web API calls (design 26c §5.4 step 3, QA M5): the exact requests of play_request.json,
 * every answer of play_answers.json, and a start's retries - 401 once with a new token, 404 -> one
 * transfer (its 204 awaited, then the SDK's state) -> the play once more, a short `Retry-After` once -
 * over a fake [SpotifyHttp].
 */
class SpotifyApiTest {
    private val requests = Json.parseToJsonElement(Fixtures.resource("spotify/play_request.json")).jsonObject
    private val answers = Json.parseToJsonElement(Fixtures.resource("spotify/play_answers.json")).jsonObject
    private val token = requests["token"]!!.jsonPrimitive.content
    private val device = requests["device_id"]!!.jsonPrimitive.content
    private val base = "https://api.spotify.com".toHttpUrl()

    /** A fixture's string, or null for JSON null. */
    private fun JsonElement?.text(): String? = (this as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content

    /** A canned answer from play_answers.json. */
    private fun answer(name: String): SpotifyResponse {
        val a = answers[name]!!.jsonObject
        val body = a["body"]!!.let { if (it is JsonNull) "" else it.toString() }
        return SpotifyResponse(a["status"]!!.jsonPrimitive.content.toInt(), a["retry_after"].text(), body.toByteArray())
    }

    private class Script(vararg val answers: () -> SpotifyResponse) : SpotifyHttp {
        val sent = mutableListOf<SpotifyRequest>()
        override fun send(request: SpotifyRequest): SpotifyResponse {
            sent += request
            return answers.getOrNull(sent.size - 1)?.invoke() ?: error("an unexpected request ${sent.size}: $request")
        }
    }

    private var clock = 0L

    private fun api(http: SpotifyHttp) = SpotifyApi(http, base, { clock })

    private val uris = listOf("spotify:track:FAKEtrack0000000000001", "spotify:track:FAKEtrack0000000000002", "spotify:track:FAKEtrack0000000000003")
    private val body = PlayBody.Uris(uris, 42_000)

    private fun expect(name: String, r: SpotifyRequest) {
        val want = requests[name]!!.jsonObject
        assertEquals("$name method", want["method"]!!.jsonPrimitive.content, r.method)
        assertEquals("$name path", want["path"]!!.jsonPrimitive.content, r.url.encodedPath)
        assertEquals("$name query", want["query"].text(), r.url.encodedQuery)
        assertEquals("$name body", want["body"].text(), r.body)
        val headers = requests["headers"]!!.jsonObject
        assertEquals("$name: the bearer and Accept", listOf("Authorization" to headers["authorization"]!!.jsonPrimitive.content, "Accept" to headers["accept"]!!.jsonPrimitive.content), r.headers)
        assertEquals("api.spotify.com", r.url.host)
    }

    @Test
    fun `the exact requests - play, the context form, transfer, the queue add`() {
        val http = Script(*Array(9) { { answer("ok") } })
        val a = api(http)
        assertEquals(PlayOutcome.Ok, a.play(token, device, body))
        assertEquals(PlayOutcome.Ok, a.play(token, device, PlayBody.Context("spotify:playlist:FAKEplaylist0000000001", "spotify:track:FAKEtrack0000000000002", 42_000)))
        assertEquals(PlayOutcome.Ok, a.transfer(token, device))
        assertEquals(PlayOutcome.Ok, a.queue(token, device, "spotify:track:FAKEtrack0000000000004"))
        assertEquals(PlayOutcome.Ok, a.shuffle(token, device, false))
        assertEquals(PlayOutcome.Ok, a.repeat(token, device, "off"))
        expect("play", http.sent[0])
        expect("play_context", http.sent[1])
        expect("transfer", http.sent[2])
        expect("queue", http.sent[3])
        expect("shuffle", http.sent[4])
        expect("repeat", http.sent[5])
        // design 49 §1.5-§1.7: the wanted ones, as SpotifyModes words them
        assertEquals(PlayOutcome.Ok, a.shuffle(token, device, SpotifyModes(shuffle = true).shuffle))
        assertEquals(PlayOutcome.Ok, a.repeat(token, device, SpotifyModes(repeat = 1).repeatWord))
        assertEquals(PlayOutcome.Ok, a.repeat(token, device, SpotifyModes(repeat = 2).repeatWord))
        expect("shuffle_on", http.sent[6])
        expect("repeat_context", http.sent[7])
        expect("repeat_track", http.sent[8])
        assertEquals("an artist context: no offset, no position", """{"context_uri":"spotify:artist:FAKEartist000000000001"}""",
            PlayBody.Context("spotify:artist:FAKEartist000000000001", null, 5_000).json())
        assertEquals("the call's timeout", 10_000L, http.sent[0].timeoutMs)
        assertTrue("the request prints no token", !http.sent[0].toString().contains(token) && !http.sent[0].toString().contains(device))
    }

    @Test
    fun `every answer's outcome - a 403 is the account's, never the track's (M5)`() {
        val cases = mapOf(
            "ok" to PlayOutcome.Ok,
            "device_unknown" to PlayOutcome.DeviceUnknown,
            "unauthorized" to PlayOutcome.Unauthorized,
            "not_premium" to PlayOutcome.NotPremium,
            "refused" to PlayOutcome.Refused(null),
            "quota" to PlayOutcome.Quota,
            "rate_limited" to PlayOutcome.RateLimited(2_000),
            "unavailable" to PlayOutcome.Failed(503),
        )
        for ((name, want) in cases) assertEquals(name, want, SpotifyApi.classify(answer(name)))
        assertEquals(PlayOutcome.Ok, SpotifyApi.classify(SpotifyResponse(202, null, ByteArray(0))))
        assertEquals("a top-level reason too", PlayOutcome.NotPremium, SpotifyApi.classify(SpotifyResponse(403, null, """{"reason":"PREMIUM_REQUIRED"}""".toByteArray())))
        assertEquals("a reason is a fixed word", PlayOutcome.Refused("DEVICE_NOT_CONTROLLABLE"),
            SpotifyApi.classify(SpotifyResponse(403, null, """{"error":{"reason":"DEVICE_NOT_CONTROLLABLE"}}""".toByteArray())))
        assertEquals(PlayOutcome.Refused(null), SpotifyApi.classify(SpotifyResponse(403, null, """{"error":{"reason":"FAKE-ACCESS-1 x"}}""".toByteArray())))
        assertEquals(PlayOutcome.RateLimited(null), SpotifyApi.classify(SpotifyResponse(429, "Wed, 21 Oct 2015 07:28:00 GMT", ByteArray(0))))
        assertEquals(PlayOutcome.Failed(302), SpotifyApi.classify(SpotifyResponse(302, null, ByteArray(0))))
        assertEquals(PlayOutcome.Unreachable("network"), api { throw IOException("down") }.play(token, device, body))
        assertEquals(PlayOutcome.Unreachable("deadline"), api(Script()).play(token, device, body, timeoutMs = 0))
        for ((result, problem) in listOf(
            StartResult.NotPremium to SpotifyProblem.NOT_PREMIUM, StartResult.Refused to SpotifyProblem.REFUSED,
            StartResult.Quota to SpotifyProblem.QUOTA, StartResult.Unreachable("x") to SpotifyProblem.UNREACHABLE,
            StartResult.TokenRefused to SpotifyProblem.REFUSED,
            StartResult.NoToken(TokenResult.Reauthorize) to SpotifyProblem.REAUTHORIZE, StartResult.NoToken(TokenResult.Off) to SpotifyProblem.OFF,
            StartResult.NoToken(TokenResult.Busy(30_000)) to SpotifyProblem.UNREACHABLE, StartResult.NoToken(TokenResult.Unreachable) to SpotifyProblem.UNREACHABLE,
        )) {
            assertEquals("$result", problem, result.problem())
        }
        assertNull(StartResult.Sent.problem())
    }

    private class Tokens(vararg val results: TokenResult) {
        val asked = mutableListOf<Boolean>()
        suspend fun get(renew: Boolean): TokenResult {
            asked += renew
            return results.getOrElse(asked.size - 1) { results.last() }
        }
    }

    private val ok1 = TokenResult.Ok("FAKE-ACCESS-1", 3_600_000, "2:1")
    private val ok2 = TokenResult.Ok("FAKE-ACCESS-2", 3_600_000, "2:1")

    private fun start(http: SpotifyHttp, tokens: Tokens, settle: MutableList<Long> = mutableListOf(), slept: MutableList<Long> = mutableListOf()) = runBlocking {
        api(http).start(device, body, deadlineMs = 25_000, tokens = { tokens.get(it) }, awaitDevice = { settle += it; true }, sleep = { slept += it; clock += it })
    }

    @Test
    fun `a start - one play call, 204 is sent`() {
        val http = Script({ answer("ok") })
        val tokens = Tokens(ok1)
        assertEquals(StartResult.Sent, start(http, tokens))
        assertEquals(listOf(false), tokens.asked)
        assertEquals(1, http.sent.size)
    }

    @Test
    fun `a 401 asks for a new token once and plays once more`() {
        val http = Script({ answer("unauthorized") }, { answer("ok") })
        val tokens = Tokens(ok1, ok2)
        assertEquals(StartResult.Sent, start(http, tokens))
        assertEquals("the second ask renews", listOf(false, true), tokens.asked)
        assertEquals("Bearer FAKE-ACCESS-2", http.sent[1].headers.first { it.first == "Authorization" }.second)
        val twice = Script({ answer("unauthorized") }, { answer("unauthorized") })
        assertEquals("never a third play: Spotify refused the account's token (L4)", StartResult.TokenRefused, start(twice, Tokens(ok1, ok2)))
        assertEquals(2, twice.sent.size)
        assertEquals("no new token: its answer", StartResult.NoToken(TokenResult.Reauthorize), start(Script({ answer("unauthorized") }), Tokens(ok1, TokenResult.Reauthorize)))
        assertEquals("no token at all: no request", StartResult.NoToken(TokenResult.Off), start(Script(), Tokens(TokenResult.Off)))
    }

    @Test
    fun `a 404 transfers once, awaits its 204 and the SDK's state, then plays once more`() {
        val order = mutableListOf<String>()
        val http = object : SpotifyHttp {
            var n = 0
            override fun send(request: SpotifyRequest): SpotifyResponse {
                order += "${request.method} ${request.url.encodedPath}"
                return when (n++) {
                    0 -> answer("device_unknown")
                    1 -> answer("ok").also { order += "transfer 204" }
                    else -> answer("ok")
                }
            }
        }
        val settle = mutableListOf<Long>()
        val result = runBlocking {
            api(http).start(device, body, 25_000, tokens = { ok1 }, awaitDevice = { order += "await $it"; settle += it; true })
        }
        assertEquals(StartResult.Sent, result)
        assertEquals(
            listOf("PUT /v1/me/player/play", "PUT /v1/me/player", "transfer 204", "await 3000", "PUT /v1/me/player/play"),
            order,
        )
        val gone = Script({ answer("device_unknown") }, { answer("ok") }, { answer("device_unknown") })
        assertEquals("still unknown after the transfer", StartResult.Unreachable("device"), start(gone, Tokens(ok1)))
        assertEquals(3, gone.sent.size)
        val refused = Script({ answer("device_unknown") }, { answer("not_premium") })
        assertEquals("the transfer's own answer", StartResult.NotPremium, start(refused, Tokens(ok1)))
        // qa-26c-step6-code M3: the transfer's own 404 is the device's ("transfer"), never read as a context's refusal
        val unknown = Script({ answer("device_unknown") }, { answer("device_unknown") })
        assertEquals(StartResult.Unreachable("transfer"), start(unknown, Tokens(ok1)))
        assertEquals(SpotifyProblem.UNREACHABLE, StartResult.Unreachable("transfer").problem())
    }

    @Test
    fun `a 429 waits a Retry-After of at most 5 s once, else unreachable - and QUOTA_EXCEEDED is quota`() {
        val slept = mutableListOf<Long>()
        assertEquals(StartResult.Sent, start(Script({ answer("rate_limited") }, { answer("ok") }), Tokens(ok1), slept = slept))
        assertEquals(listOf(2_000L), slept)
        assertEquals("once", StartResult.Unreachable("rate_limited"), start(Script({ answer("rate_limited") }, { answer("rate_limited") }), Tokens(ok1)))
        val long = SpotifyResponse(429, "6", ByteArray(0))
        assertEquals("longer than 5 s", StartResult.Unreachable("rate_limited"), start(Script({ long }), Tokens(ok1)))
        assertEquals("no number", StartResult.Unreachable("rate_limited"), start(Script({ SpotifyResponse(429, null, ByteArray(0)) }), Tokens(ok1)))
        assertEquals(StartResult.Quota, start(Script({ answer("quota") }), Tokens(ok1)))
        assertEquals(StartResult.NotPremium, start(Script({ answer("not_premium") }, { answer("not_premium") }), Tokens(ok1)))
        assertEquals(StartResult.Refused, start(Script({ answer("refused") }), Tokens(ok1)))
        assertEquals(StartResult.Unreachable("http_503"), start(Script({ answer("unavailable") }), Tokens(ok1)))
    }

    @Test
    fun `the same token back after a 401 can't help - refused at once, an adult's matter (qa-26c-step4-code L4)`() {
        val http = Script({ answer("unauthorized") })
        val tokens = Tokens(ok1, ok1)
        assertEquals(StartResult.TokenRefused, start(http, tokens))
        assertEquals("no second play with the refused token", 1, http.sent.size)
        assertEquals(listOf(false, true), tokens.asked)
        assertEquals(SpotifyProblem.REFUSED, StartResult.TokenRefused.problem())
        assertEquals(me.vibb.music.logic.SpotifyLine.ASK_ADULT, StartResult.TokenRefused.problem()!!.line)
    }

    @Test
    fun `a 403 PREMIUM_REQUIRED is tried once more after 1 s before it counts (qa-26c-step4-code L7)`() {
        val slept = mutableListOf<Long>()
        assertEquals(StartResult.Sent, start(Script({ answer("not_premium") }, { answer("ok") }), Tokens(ok1), slept = slept))
        assertEquals(listOf(1_000L), slept)
        assertEquals("once", StartResult.NotPremium, start(Script({ answer("not_premium") }, { answer("not_premium") }), Tokens(ok1)))
        assertEquals("another 403 isn't retried", StartResult.Refused, start(Script({ answer("refused") }), Tokens(ok1)))
        clock = 24_500
        assertEquals("no room before the deadline", StartResult.NotPremium, start(Script({ answer("not_premium") }), Tokens(ok1)))
    }

    @Test
    fun `settings - shuffle and repeat off before the play, each awaited, sharing the start's retries (qa-26c-step4-code M1)`() {
        val http = Script({ answer("ok") }, { answer("ok") }, { answer("ok") })
        assertEquals(StartResult.Sent, runBlocking { api(http).start(device, body, 25_000, tokens = { ok1 }, awaitDevice = { true }, settings = SpotifyModes()) })
        assertEquals(listOf("/v1/me/player/shuffle", "/v1/me/player/repeat", "/v1/me/player/play"), http.sent.map { it.url.encodedPath })
        expect("shuffle", http.sent[0])
        expect("repeat", http.sent[1])
        assertEquals("without settings: the play alone", StartResult.Sent, start(Script({ answer("ok") }), Tokens(ok1)))
        // the device unknown at the first setting: one transfer, then the setting again, the other, the play
        val unknown = Script({ answer("device_unknown") }, { answer("ok") }, { answer("ok") }, { answer("ok") }, { answer("ok") })
        assertEquals(StartResult.Sent, runBlocking { api(unknown).start(device, body, 25_000, tokens = { ok1 }, awaitDevice = { true }, settings = SpotifyModes()) })
        assertEquals(
            listOf("/v1/me/player/shuffle", "/v1/me/player", "/v1/me/player/shuffle", "/v1/me/player/repeat", "/v1/me/player/play"),
            unknown.sent.map { it.url.encodedPath },
        )
        // a failed setting ends the start like a failed play: nothing plays shuffled
        val refused = Script({ answer("ok") }, { answer("refused") })
        assertEquals(StartResult.Refused, runBlocking { api(refused).start(device, body, 25_000, tokens = { ok1 }, awaitDevice = { true }, settings = SpotifyModes()) })
        assertEquals("no play after it", 2, refused.sent.size)
        val down = Script({ answer("unavailable") })
        assertEquals(StartResult.Unreachable("http_503"), runBlocking { api(down).start(device, body, 25_000, tokens = { ok1 }, awaitDevice = { true }, settings = SpotifyModes()) })
    }

    @Test
    fun `the start's deadline bounds every call`() {
        clock = 24_000
        val http = Script({ answer("ok") })
        assertEquals(StartResult.Sent, start(http, Tokens(ok1)))
        assertEquals("cut to what is left", 1_000L, http.sent[0].timeoutMs)
        clock = 25_000
        assertEquals(StartResult.Unreachable("deadline"), start(Script(), Tokens(ok1)))
        clock = 23_000
        assertEquals("a Retry-After past the deadline isn't waited", StartResult.Unreachable("rate_limited"), start(Script({ answer("rate_limited") }), Tokens(ok1)))
    }

    @Test
    fun `the fake's token route reads as the server's (debug switch)`() {
        val fixtures = Json.parseToJsonElement(Fixtures.resource("spotify/token_answers.json")).jsonObject
        fun body(name: String) = (fixtures[name]!!.jsonObject["body"] as JsonObject).toString()
        assertEquals(TokenResult.Ok("FAKE-ACCESS-1", 1_000 + 3_300_000, "2:1"), SpotifyApi.tokenAnswer(body("ok"), 1_000))
        assertEquals(TokenResult.Off, SpotifyApi.tokenAnswer(body("off"), 0))
        assertEquals(TokenResult.Reauthorize, SpotifyApi.tokenAnswer(body("reauthorize"), 0))
        assertEquals(TokenResult.Busy(30_000), SpotifyApi.tokenAnswer(body("busy"), 0))
        assertEquals(TokenResult.Unreachable, SpotifyApi.tokenAnswer(body("unreachable"), 0))
        assertEquals(TokenResult.Unreachable, SpotifyApi.tokenAnswer(body("key"), 0))
        assertEquals(TokenResult.Unreachable, SpotifyApi.tokenAnswer("<html>", 0))
        assertEquals("an ok without a token", TokenResult.Unreachable, SpotifyApi.tokenAnswer("""{"status":"ok","expires_in_s":3300,"account":"2:1"}""", 0))
        val http = Script({ SpotifyResponse(200, null, body("ok").toByteArray()) })
        clock = 5
        assertEquals(TokenResult.Ok("FAKE-ACCESS-1", 5 + 3_300_000, "2:1"), api(http).debugToken("http://10.0.2.2:8731/fake/token?user=kid".toHttpUrl()))
        assertEquals("GET", http.sent[0].method)
        assertTrue("no bearer to the fake's token route", http.sent[0].headers.none { it.first == "Authorization" })
        assertEquals(TokenResult.Unreachable, api { throw IOException("down") }.debugToken("http://10.0.2.2:8731/fake/token".toHttpUrl()))
    }
}
