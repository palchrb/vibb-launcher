package com.kidslauncher.mdm.server

import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** Security review L8: the device credential goes to the server's own origin only. Design 129 §4: the key's
 * signature over the canonical form, afresh on every attempt; `stale`/`replay` once more, `unknown_key` wakes the
 * registration flow and nothing else. */
class ServerAuthTest {
    private val server = "https://kidphone.example-tailnet.ts.net/".toHttpUrl()

    @Test
    fun `same origin is scheme, host and port`() {
        assertTrue(sameOrigin(server, "https://kidphone.example-tailnet.ts.net/api/devices/policy".toHttpUrl()))
        assertTrue("default port spelled out", sameOrigin(server, "https://kidphone.example-tailnet.ts.net:443/x".toHttpUrl()))
        assertTrue("host case", sameOrigin(server, "https://KidPhone.Example-Tailnet.ts.net/x".toHttpUrl()))
        assertFalse("http", sameOrigin(server, "http://kidphone.example-tailnet.ts.net/x".toHttpUrl()))
        assertFalse("other port", sameOrigin(server, "https://kidphone.example-tailnet.ts.net:8443/x".toHttpUrl()))
        assertFalse("other host", sameOrigin(server, "https://github.com/palchrb/vibb-launcher/releases".toHttpUrl()))
        assertFalse("a subdomain", sameOrigin(server, "https://evil.kidphone.example-tailnet.ts.net/x".toHttpUrl()))
        assertFalse("a suffix", sameOrigin(server, "https://kidphone.example-tailnet.ts.net.evil.example/x".toHttpUrl()))
    }

    private class Seen {
        val requests = mutableListOf<Request>()
        val bodies = mutableListOf<String>()
    }

    /** A client whose last interceptor answers itself ([answers] in turn, the last one repeated); the signer runs as an
     * application interceptor after the retry, as the network interceptor runs after it in OkHttp. */
    private fun client(
        baseUrl: String,
        credential: DeviceCredential,
        seen: Seen,
        signing: RequestSigning = fixedSigning(),
        answers: List<Pair<Int, String?>> = listOf(204 to null),
        onUnknownKey: () -> Unit = {},
    ): OkHttpClient {
        val answer = Interceptor { chain ->
            val request = chain.request()
            seen.requests += request
            seen.bodies += request.body?.let { body -> okio.Buffer().also { body.writeTo(it) }.readUtf8() }.orEmpty()
            val (code, auth) = answers[minOf(seen.requests.size - 1, answers.size - 1)]
            Response.Builder()
                .request(request)
                .protocol(Protocol.HTTP_1_1)
                .code(code)
                .message("answer")
                .header(SIGNATURE_TIME_HEADER, "1790000000000")
                .apply { if (auth != null) header(AUTH_REFUSAL_HEADER, auth) }
                .body("".toResponseBody(null))
                .build()
        }
        return OkHttpClient.Builder()
            .addInterceptor(credentialRetry(credential, onUnknownKey))
            .addInterceptor(credentialSigner(baseUrl, credential, signing))
            .addInterceptor(answer)
            .build()
    }

    private val signedBytes = mutableListOf<String>()
    private var nonces = 0
    private val observed = mutableListOf<Long>()

    private fun fixedSigning(fail: Boolean = false) = RequestSigning(
        sign = { _, data ->
            if (fail) throw IllegalStateException("keystore gone")
            signedBytes += String(data, Charsets.UTF_8)
            byteArrayOf(1, 2, 3)
        },
        now = { 1_790_000_000_000L },
        nonce = { "nonce${nonces++}" },
        elapsed = { 0L },
        observe = { vibbTime, _, _ -> observed += vibbTime },
        log = {},
    )

    private fun call(client: OkHttpClient, url: String, body: String? = null, encoding: String? = null) {
        val builder = Request.Builder().url(url)
        if (encoding != null) builder.header("Content-Encoding", encoding)
        if (body != null) builder.post(body.toRequestBody("application/json".toMediaType()))
        client.newCall(builder.build()).execute().close()
    }

    @Test
    fun `the bearer goes to the server only`() {
        val base = "https://kidphone.example-tailnet.ts.net/"
        fun sent(baseUrl: String, url: String): String? {
            val seen = Seen()
            call(client(baseUrl, DeviceCredential.Bearer("TESTONLY-device-token"), seen), url)
            return seen.requests.single().header("Authorization")
        }
        assertEquals("Bearer TESTONLY-device-token", sent(base, "https://kidphone.example-tailnet.ts.net/api/devices/apps/5/download"))
        assertNull(sent(base, "https://objects.githubusercontent.com/release.apk"))
        assertNull(sent(base, "http://kidphone.example-tailnet.ts.net/api/devices/policy"))
        // A server under a path keeps its origin: any path on it gets the header.
        assertEquals("Bearer TESTONLY-device-token", sent("http://100.64.0.7:3100/prefix/", "http://100.64.0.7:3100/api/devices/policy"))
        assertNull(sent("not a url", "https://kidphone.example-tailnet.ts.net/api/devices/policy"))
    }

    @Test
    fun `a key signs the canonical form, beside the bearer while it goes along`() {
        val seen = Seen()
        val key = DeviceCredential.Key("KEYID", "device_key_1", "TESTONLY-device-token")
        val body = """{"id":"00ff","asked_at_ms":1}"""
        call(client("https://kidphone.example/vibb/", key, seen), "https://kidphone.example/vibb/api/devices/time-request?x=%2F", body, "gzip")
        val request = seen.requests.single()
        assertEquals("Bearer TESTONLY-device-token", request.header("Authorization"))
        val hash = hexOf(sha256(body.toByteArray()))
        assertEquals(hash, request.header(BODY_SHA256_HEADER))
        assertEquals("v1;k=KEYID;t=1790000000000;n=nonce0;s=AQID", request.header(SIGNATURE_HEADER))
        assertEquals(
            listOf("vibb-req-v1\nKEYID\n1790000000000\nnonce0\nPOST\n/api/devices/time-request?x=%2F\ngzip\n$hash"),
            signedBytes,
        )
        assertEquals(body, seen.bodies.single())
        // The answer's Vibb-Time sets the signature clock.
        assertEquals(listOf(1_790_000_000_000L), observed)
        // Another origin gets neither; a path outside the device API is not signed.
        val elsewhere = Seen()
        call(client("https://kidphone.example/", key, elsewhere), "https://cdn.example/api/devices/x")
        assertNull(elsewhere.requests.single().header(SIGNATURE_HEADER))
        assertNull(elsewhere.requests.single().header("Authorization"))
        val notDevice = Seen()
        call(client("https://kidphone.example/", key, notDevice), "https://kidphone.example/healthz")
        assertNull(notDevice.requests.single().header(SIGNATURE_HEADER))
        // Bound: the signature alone.
        val bound = Seen()
        call(client("https://kidphone.example/", DeviceCredential.Key("KEYID", "device_key_1", null), bound), "https://kidphone.example/api/devices/policy")
        assertNull(bound.requests.single().header("Authorization"))
        assertTrue(bound.requests.single().header(SIGNATURE_HEADER)!!.startsWith("v1;k=KEYID;"))
        assertEquals(hexOf(sha256(ByteArray(0))), bound.requests.single().header(BODY_SHA256_HEADER))
    }

    @Test
    fun `a key that cant sign leaves the bearer alone, and with no bearer nothing is sent`() {
        val seen = Seen()
        call(client("https://kidphone.example/", DeviceCredential.Key("KEYID", "device_key_1", "TESTONLY-device-token"), seen, fixedSigning(fail = true)), "https://kidphone.example/api/devices/policy")
        assertEquals("Bearer TESTONLY-device-token", seen.requests.single().header("Authorization"))
        assertNull(seen.requests.single().header(SIGNATURE_HEADER))
        val none = Seen()
        assertThrows(KeyUnusable::class.java) {
            call(client("https://kidphone.example/", DeviceCredential.Key("KEYID", "device_key_1", null), none, fixedSigning(fail = true)), "https://kidphone.example/api/devices/policy")
        }
        assertTrue(none.requests.isEmpty())
        SignatureClock.signFailing = false
    }

    @Test
    fun `stale and replay go once more, signed afresh, and unknown_key only wakes the flow`() {
        val key = DeviceCredential.Key("KEYID", "device_key_1", "TESTONLY-device-token")
        for (reason in listOf("stale", "replay")) {
            val seen = Seen()
            call(client("https://kidphone.example/", key, seen, answers = listOf(401 to reason, 204 to null)), "https://kidphone.example/api/devices/policy")
            assertEquals(reason, 2, seen.requests.size)
            assertFalse(seen.requests[0].header(SIGNATURE_HEADER) == seen.requests[1].header(SIGNATURE_HEADER))
            // Only once: a second refusal is the answer.
            val twice = Seen()
            call(client("https://kidphone.example/", key, twice, answers = listOf(401 to reason)), "https://kidphone.example/api/devices/policy")
            assertEquals(2, twice.requests.size)
        }
        var woken = 0
        val seen = Seen()
        call(client("https://kidphone.example/", key, seen, answers = listOf(401 to "unknown_key")) { woken++ }, "https://kidphone.example/api/devices/policy")
        assertEquals(1, seen.requests.size)
        assertEquals(1, woken)
        // A bearer alone is never retried.
        val bearer = Seen()
        call(client("https://kidphone.example/", DeviceCredential.Bearer("t"), bearer, answers = listOf(401 to "stale")), "https://kidphone.example/api/devices/policy")
        assertEquals(1, bearer.requests.size)
    }

    @Test
    fun `a credential never prints its token`() {
        assertFalse("TESTONLY" in DeviceCredential.Bearer("TESTONLY-device-token").toString())
        assertFalse("TESTONLY" in DeviceCredential.Key("k", "a", "TESTONLY-device-token").toString())
    }
}
