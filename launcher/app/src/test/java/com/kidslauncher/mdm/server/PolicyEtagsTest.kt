package com.kidslauncher.mdm.server

import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.Response

/** Design 95 §3.5 (step 95-T1): the kept raw policy body, `If-None-Match`, and a 304 decoded as the 200 it repeats. */
class PolicyEtagsTest {
    private val prefs = ParentPinStoreTest.FakePrefs()
    private val store = PolicyEtagStore(prefs, SealedPrefs(SealedPrefsTest.FakeBox()) { _, _ -> }) { true }
    private val owner = PolicyOwner.of(390009, "https://vibb.example/", "token-a")
    private val body = """{"allowlist": [], "kiosk_desired": true, "an_unknown_key": 1}"""
    private val serverError = FreshDecode.Failed("server error")

    private fun answer(code: Int, text: String = "", etag: String? = null): Response<ResponseBody> {
        val raw = okhttp3.Response.Builder()
            .request(Request.Builder().url("https://vibb.example/api/devices/policy").build())
            .protocol(Protocol.HTTP_1_1)
            .code(code)
            .message("x")
            .apply { etag?.let { header("ETag", it) } }
            .build()
        val content = text.toResponseBody("application/json".toMediaType())
        return if (code in 200..299) Response.success(content, raw) else Response.error(content, raw)
    }

    /** One fetch against [answers] in order; returns the result, the `If-None-Match` of each request and the served. */
    private fun fetch(vararg answers: Response<ResponseBody>): Triple<FreshDecode?, List<String?>, ServedPolicy?> {
        val sent = mutableListOf<String?>()
        var served: ServedPolicy? = null
        val result = runBlocking {
            fetchRevalidated({ sent += it; answers[sent.size - 1] }, store, owner, serverError, {}, { served = it })
        }
        return Triple(result, sent, served)
    }

    @Test
    fun `a 304 is the kept body, and nothing is sent until one is kept`() {
        // Nothing kept: no header; the 200's raw body and ETag come back for the sync to keep once accepted.
        val (first, sent, served) = fetch(answer(200, body, "W/\"abc\""))
        assertEquals(listOf<String?>(null), sent)
        assertTrue(first is FreshDecode.Ok)
        assertEquals(body, served!!.body)
        assertEquals("W/\"abc\"", served.etag)
        assertFalse(served.notModified)
        assertTrue(store.keep(owner, served.etag!!, served.body))

        // Kept: the header, and the 304 decodes exactly as the 200 did (the raw body, not a re-encoded copy).
        val (again, sentAgain, servedAgain) = fetch(answer(304))
        assertEquals(listOf<String?>("W/\"abc\""), sentAgain)
        assertEquals(first, again)
        assertEquals(body, servedAgain!!.body)
        assertTrue(servedAgain.notModified)
        // A 200 still wins, and a 5xx is the server error as before.
        assertTrue(fetch(answer(200, body, "W/\"def\"")).first is FreshDecode.Ok)
        assertSame(serverError, fetch(answer(500)).first)
    }

    @Test
    fun `a kept body is for this build, server and token only`() {
        assertTrue(store.keep(owner, "W/\"abc\"", body))
        assertEquals("W/\"abc\"", store.usable(owner)!!.etag)
        for (other in listOf(
            owner.copy(versionCode = 390010),
            owner.copy(serverUrl = "https://other.example/"),
            PolicyOwner.of(390009, "https://vibb.example/", "token-b"),
        )) {
            assertTrue(store.keep(owner, "W/\"abc\"", body))
            assertNull(store.usable(other))
            // ... and dropped for good, not only skipped.
            assertNull(store.usable(owner))
        }
        // The token itself is never stored, only its hash.
        assertTrue(store.keep(owner, "W/\"abc\"", body))
        assertFalse(prefs.map.values.joinToString().contains("token-a"))
        assertFalse(prefs.map.values.joinToString().contains("kiosk_desired"))
    }

    @Test
    fun `a 304 the kept body can't answer asks again without the header`() {
        assertTrue(store.keep(owner, "W/\"abc\"", "not json"))
        val (result, sent, _) = fetch(answer(304), answer(200, body, "W/\"def\""))
        assertEquals(listOf("W/\"abc\"", null), sent)
        assertTrue(result is FreshDecode.Ok)
        assertNull(store.usable(owner))
        // A 304 to a request without the header is no policy.
        assertNull(fetch(answer(304)).first)
    }

    @Test
    fun `only an accepted 200 with an ETag and without a command is kept`() {
        val plain = (decodeFresh(body) as FreshDecode.Ok).policy
        val withPin = """{"allowlist": [], "pending_command": {"id": 7, "command": "set_kid_pin", "pin": "1234"}}"""
        val command = (decodeFresh(withPin) as FreshDecode.Ok).policy
        assertEquals("1234", command.pendingCommand?.pin)

        assertEquals(KeptBodyStep.Keep("W/\"abc\"", body), keptBodyStep(ServedPolicy(body, "W/\"abc\"", false), plain))
        // The Child PIN's command (design 59 §1), and any other, is never kept - whatever was kept goes.
        assertEquals(KeptBodyStep.Drop, keptBodyStep(ServedPolicy(withPin, "W/\"abc\"", false), command))
        // An older server's 200 has no ETag.
        assertEquals(KeptBodyStep.Drop, keptBodyStep(ServedPolicy(body, null, false), plain))
        // A 304 is the kept body already.
        assertEquals(KeptBodyStep.Leave, keptBodyStep(ServedPolicy(body, "W/\"abc\"", true), plain))
    }
}
