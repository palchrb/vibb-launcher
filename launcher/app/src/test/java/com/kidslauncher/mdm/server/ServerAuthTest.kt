package com.kidslauncher.mdm.server

import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Security review L8: the device token goes to the server's own origin only. */
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

    /** The header each request leaves with, through a client whose last interceptor answers itself. */
    private fun authorizationSent(baseUrl: String, url: String): String? {
        var seen: Request? = null
        val answer = Interceptor { chain ->
            seen = chain.request()
            Response.Builder()
                .request(chain.request())
                .protocol(Protocol.HTTP_1_1)
                .code(204)
                .message("No Content")
                .body("".toResponseBody(null))
                .build()
        }
        val client = OkHttpClient.Builder()
            .addInterceptor(bearerInterceptor(baseUrl, "TESTONLY-device-token"))
            .addInterceptor(answer)
            .build()
        client.newCall(Request.Builder().url(url).build()).execute().close()
        return seen!!.header("Authorization")
    }

    @Test
    fun `the bearer goes to the server only`() {
        val base = "https://kidphone.example-tailnet.ts.net/"
        assertEquals(
            "Bearer TESTONLY-device-token",
            authorizationSent(base, "https://kidphone.example-tailnet.ts.net/api/devices/apps/5/download"),
        )
        assertNull(authorizationSent(base, "https://objects.githubusercontent.com/release.apk"))
        assertNull(authorizationSent(base, "http://kidphone.example-tailnet.ts.net/api/devices/policy"))
        // A server under a path keeps its origin: any path on it gets the header.
        assertEquals(
            "Bearer TESTONLY-device-token",
            authorizationSent("http://100.64.0.7:3100/prefix/", "http://100.64.0.7:3100/api/devices/policy"),
        )
        assertNull(authorizationSent("not a url", "https://kidphone.example-tailnet.ts.net/api/devices/policy"))
    }
}
