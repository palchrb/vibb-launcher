package com.kidslauncher.mdm.server

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Interceptor

/**
 * Whether [request] goes to the server at [server]: the same scheme, host and port (security
 * review L8). Hosts are compared as OkHttp canonicalises them (lower case, IDN in punycode), ports
 * with the scheme's default filled in.
 */
fun sameOrigin(server: HttpUrl, request: HttpUrl): Boolean =
    server.scheme == request.scheme && server.host == request.host && server.port == request.port

/**
 * Adds the device token as `Authorization: Bearer ...` - only to requests for the server's own
 * origin ([sameOrigin] with [baseUrl]), never to an absolute URL elsewhere (a download URL or a
 * `@Url` the server's answer named). OkHttp drops the header itself on a redirect to another
 * host; this covers the first hop. A [baseUrl] that doesn't parse authorises nothing.
 */
fun bearerInterceptor(baseUrl: String, token: String): Interceptor {
    val server = baseUrl.toHttpUrlOrNull()
    return Interceptor { chain ->
        val request = chain.request()
        if (server != null && sameOrigin(server, request.url)) {
            chain.proceed(request.newBuilder().header("Authorization", "Bearer $token").build())
        } else {
            chain.proceed(request)
        }
    }
}
