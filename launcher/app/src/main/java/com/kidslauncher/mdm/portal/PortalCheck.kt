package com.kidslauncher.mdm.portal

import android.net.Network
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.InetAddress
import java.net.Proxy
import java.util.concurrent.TimeUnit

/** Google's and Cloudflare's 204s: fixed constants, never from the policy (R2-M3). */
const val CHECK_GOOGLE = "https://www.gstatic.com/generate_204"
const val CHECK_CLOUDFLARE = "https://cp.cloudflare.com/generate_204"

/**
 * Vibb's "the network works" check (design 102 §3.3, `portalPasses`): on the window's bound [network] - its socket
 * factory and its own DNS -, the system's trust, no proxy, no redirects, a few seconds each. The phone's own server is
 * asked plainly ([serverUrl], a public `https://` address only): no bearer, no design-76 pinner, not through
 * `ServerTransport` (R3-L4). Its lookups never go through the window's filter, and it never touches the tunnel (Vibb is
 * excluded from its own VPN).
 */
class PortalCheck(network: Network, private val serverUrl: String?) {
    private val client = OkHttpClient.Builder()
        .proxy(Proxy.NO_PROXY)
        .socketFactory(network.socketFactory)
        .dns(object : Dns {
            override fun lookup(hostname: String): List<InetAddress> = network.getAllByName(hostname).toList()
        })
        .followRedirects(false)
        .followSslRedirects(false)
        .retryOnConnectionFailure(false)
        .connectTimeout(4, TimeUnit.SECONDS)
        .callTimeout(6, TimeUnit.SECONDS)
        .build()

    /** One answer, or `null` (no network, TLS refused, a timeout). Only https URLs are asked. */
    private fun ask(url: String): CheckAnswer? = try {
        require(url.startsWith("https://"))
        client.newCall(Request.Builder().url(url).header("Cache-Control", "no-cache").build()).execute().use { response ->
            val body = response.body?.bytes() ?: ByteArray(0)
            CheckAnswer(response.code, body.isEmpty())
        }
    } catch (e: Exception) {
        null
    }

    /** The check, in turn: the server only when the first two don't settle it. Off the main thread. */
    fun passes(): Boolean {
        val google = ask(CHECK_GOOGLE)
        val cloudflare = ask(CHECK_CLOUDFLARE)
        if (portalPasses(google, cloudflare, null)) return true
        val server = serverUrl?.takeIf { (google?.status == 204) != (cloudflare?.status == 204) }?.let(::ask)
        return portalPasses(google, cloudflare, server)
    }
}
