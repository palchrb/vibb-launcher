package com.kidslauncher.mdm.server

import android.util.Log
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Interceptor
import okhttp3.MediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okio.Buffer
import okio.BufferedSink
import okio.HashingSink
import okio.blackholeSink
import okio.buffer
import java.io.IOException
import java.security.SecureRandom

/**
 * Whether [request] goes to the server at [server]: the same scheme, host and port (security
 * review L8). Hosts are compared as OkHttp canonicalises them (lower case, IDN in punycode), ports
 * with the scheme's default filled in.
 */
fun sameOrigin(server: HttpUrl, request: HttpUrl): Boolean =
    server.scheme == request.scheme && server.host == request.host && server.port == request.port

/** The key couldn't sign and no bearer goes along (after `bound`): nothing is sent (design 129 §6.1). */
class KeyUnusable(cause: Throwable) : IOException("the device key couldn't sign", cause)

/** How a request is signed (seams for tests): the key's signature, the signature clock, the nonce. */
class RequestSigning(
    val sign: (alias: String, data: ByteArray) -> ByteArray,
    val now: () -> Long = SignatureClock::now,
    val nonce: () -> String = ::newNonce,
    val elapsed: () -> Long = SignatureClock::elapsed,
    val observe: (vibbTime: Long, sent: Long, received: Long) -> Unit = SignatureClock::observe,
    val log: (String) -> Unit = { Log.w("ServerAuth", it) },
)

private val random = SecureRandom()

/** 16 random bytes, Base64url without padding (§4.2). */
fun newNonce(): String = ByteArray(16).also(random::nextBytes).let(::b64u)

/** The body's SHA-256 (hex) as it will be sent, and a stand-in for a one-shot body (its bytes, read once). */
private fun hashBody(body: RequestBody?): Pair<String, RequestBody?> {
    if (body == null) return hexOf(sha256(ByteArray(0))) to null
    if (body.isOneShot()) {
        val buffer = Buffer()
        body.writeTo(buffer)
        val bytes = buffer.readByteArray()
        val copy = object : RequestBody() {
            override fun contentType(): MediaType? = body.contentType()
            override fun contentLength(): Long = bytes.size.toLong()
            override fun writeTo(sink: BufferedSink) {
                sink.write(bytes)
            }
        }
        return hexOf(sha256(bytes)) to copy
    }
    val hashing = HashingSink.sha256(blackholeSink())
    hashing.buffer().use { body.writeTo(it) }
    return hashing.hash.hex() to null
}

/**
 * The request as it leaves for the server (design 129 §4.2): to the server's own origin only ([sameOrigin], L8), the
 * bearer while the credential carries one, and for a key a `Vibb-Body-SHA256` and a fresh `Vibb-Signature` over the
 * method, the raw path and query from `/api/devices` on, the Content-Encoding and the body's hash. A key that can't
 * sign leaves the bearer alone (S1: the phone keeps working) or, with none, refuses ([KeyUnusable]).
 */
fun authorize(request: Request, server: HttpUrl?, credential: DeviceCredential, signing: RequestSigning): Request {
    if (server == null || !sameOrigin(server, request.url)) return request
    val builder = request.newBuilder()
    credential.bearer?.let { builder.header("Authorization", "Bearer $it") }
    if (credential !is DeviceCredential.Key) return builder.build()
    val target = signedTarget(request.url.encodedPath, request.url.encodedQuery) ?: return builder.build()
    try {
        val (bodyHash, copy) = hashBody(request.body)
        val t = signing.now()
        val nonce = signing.nonce()
        val signed = canonicalRequest(credential.keyId, t, nonce, request.method, target, request.header("Content-Encoding").orEmpty(), bodyHash)
        val signature = signing.sign(credential.alias, signed.toByteArray(Charsets.UTF_8))
        builder.header(BODY_SHA256_HEADER, bodyHash).header(SIGNATURE_HEADER, signatureHeader(credential.keyId, t, nonce, signature))
        if (copy != null) builder.method(request.method, copy)
        SignatureClock.signFailing = false
    } catch (e: IOException) {
        throw e
    } catch (e: Exception) {
        SignatureClock.signFailing = true
        if (credential.bearer == null) throw KeyUnusable(e)
        signing.log("The key didn't sign (${e.javaClass.simpleName}) - the bearer alone")
    }
    return builder.build()
}

/**
 * The signer, an OkHttp **network** interceptor: every attempt - OkHttp's own retries and [credentialRetry]'s - is signed
 * afresh, so a retry is never a replay. Each answer's `Vibb-Time` sets the signature clock's offset (§4.3).
 */
fun credentialSigner(baseUrl: String, credential: DeviceCredential, signing: RequestSigning): Interceptor {
    val server = baseUrl.toHttpUrlOrNull()
    return Interceptor { chain ->
        val request = authorize(chain.request(), server, credential, signing)
        if (credential !is DeviceCredential.Key) return@Interceptor chain.proceed(request)
        val sent = signing.elapsed()
        val response = chain.proceed(request)
        response.header(SIGNATURE_TIME_HEADER)?.trim()?.toLongOrNull()?.let { signing.observe(it, sent, signing.elapsed()) }
        response
    }
}

/**
 * §4.4, an application interceptor: a 401 `stale` (the offset already taken from its `Vibb-Time`) or `replay` goes once
 * more, signed afresh; `unknown_key` only wakes the registration flow - nothing here ever deletes a key.
 */
fun credentialRetry(credential: DeviceCredential, onUnknownKey: () -> Unit): Interceptor = Interceptor { chain ->
    val response = chain.proceed(chain.request())
    if (response.code != 401 || credential !is DeviceCredential.Key) return@Interceptor response
    when (refusalAction(response.header(AUTH_REFUSAL_HEADER))) {
        RefusalAction.TAKE_OFFSET_AND_RETRY_ONCE, RefusalAction.RETRY_ONCE_NEW_NONCE -> {
            response.close()
            chain.proceed(chain.request())
        }
        RefusalAction.ASK_REGISTRATION -> {
            onUnknownKey()
            response
        }
        RefusalAction.SILENT -> response
    }
}

/**
 * Design 129 §2.1: the credential on a server client - [credentialRetry] where the bearer interceptor was, and the
 * signer as a network interceptor. Only to the server's own origin ([baseUrl], security review L8): an absolute `@Url`
 * elsewhere gets neither the bearer nor a signature. `null` adds nothing (the enrolment's call).
 */
fun OkHttpClient.Builder.credentialInterceptor(
    baseUrl: String,
    credential: DeviceCredential?,
    signing: RequestSigning = RequestSigning(sign = ::keystoreSign),
    onUnknownKey: () -> Unit = KeyRegistration::wake,
): OkHttpClient.Builder {
    if (credential == null) return this
    addInterceptor(credentialRetry(credential, onUnknownKey))
    addNetworkInterceptor(credentialSigner(baseUrl, credential, signing))
    return this
}

/** The adopted key's signature, through the Keystore. */
private fun keystoreSign(alias: String, data: ByteArray): ByteArray {
    val context = DeviceCredentials.appContext() ?: throw IllegalStateException("no context")
    return DeviceKeys.hardware(context).sign(alias, data)
}
