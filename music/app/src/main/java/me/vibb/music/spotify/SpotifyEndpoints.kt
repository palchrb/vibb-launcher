package me.vibb.music.spotify

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/*
 * Where the phone's Spotify talks to (design 26c §5.1, §9.3): the Web API's base, the Web Playback
 * SDK's script, and the one debug-only switch that points both at the fake (scripts/fake-spotify.py).
 * When Spotify moves a URL, it moves here and nowhere else. Pure.
 */

object SpotifyEndpoints {
    /** The Web API (the play, transfer and queue calls; design 26c §5.4). */
    val API: HttpUrl = "https://api.spotify.com".toHttpUrl()

    /** The Web Playback SDK, loaded at run time and never pinned or copied (design 26c §0.4, §7.2). */
    const val SDK_URL = "https://sdk.scdn.co/spotify-player.js"

    /**
     * The fake's https name inside the web player (§9.3): the page sees https only, and the
     * interceptor fetches every request to it from the debug base over the LAN client, so the
     * player's mixed-content block stays as it is. `.invalid` never resolves anywhere (RFC 2606).
     */
    const val FAKE_HOST = "fake-spotify.invalid"

    /**
     * The one debug-only switch (design 26c §9.3, 26b M2's way): null unless the debug feed's
     * `config.json` sets `spotify_base` (the emulator's fake on 10.0.2.2; only 10.0.2.2 or loopback is
     * taken, so a `config.json` left on a real phone never sends a token elsewhere). Only `src/debug`
     * assigns it (SourceScanTest); a release build has no feed, so it stays null there. It has exactly
     * six readers, all below and nowhere else: (a) [apiBase], (b) [apiOverLan], (c) [sdkUrl],
     * (d) [webHosts], (e) [webProxy] and (f) [debugTokens].
     */
    @Volatile
    var debugBase: String? = null

    /** (a) The Web API's base: Spotify's, or the fake's root (its `/v1/...`). */
    fun apiBase(): HttpUrl = parse(debugBase)?.newBuilder()?.encodedPath("/")?.build() ?: API

    /** (b) Whether the Web API calls (and [debugTokens]) go over MediaCache's LAN client instead of the guarded public one. */
    fun apiOverLan(): Boolean = parse(debugBase) != null

    /** (c) The SDK script the page loads: Spotify's, or the fake's stub under its https name. */
    fun sdkUrl(): String = if (parse(debugBase) != null) "https://$FAKE_HOST/sdk/spotify-player.js" else SDK_URL

    /** (d) Hosts the web player admits besides Spotify's own ([isSpotifyHost]): the fake's https name, or none. */
    fun webHosts(): Set<String> = if (parse(debugBase) != null) setOf(FAKE_HOST) else emptySet()

    /**
     * (e) Where the web player's request [url] to the fake's https name is fetched from: the same
     * path and query under the debug base; null for any other URL, and always without the switch.
     */
    fun webProxy(url: HttpUrl): HttpUrl? {
        val base = parse(debugBase) ?: return null
        if (url.scheme != "https" || url.host != FAKE_HOST || url.port != 443) return null
        if (url.username.isNotEmpty() || url.password.isNotEmpty()) return null
        return base.newBuilder().encodedPath(url.encodedPath).encodedQuery(url.encodedQuery).build()
    }

    /** (f) The fake's token route (`/fake/token?user=kid`), asked instead of the launcher for a run without one; null without the switch. */
    fun debugTokens(): HttpUrl? =
        parse(debugBase)?.newBuilder()?.encodedPath("/fake/token")?.addQueryParameter("user", "kid")?.build()

    /** The fake's homes only (qa-26b-step3-code L2): the emulator's host or loopback, plain http, no user info. */
    private val FAKE_HOMES = setOf("10.0.2.2", "127.0.0.1", "localhost", "::1")

    private fun parse(base: String?): HttpUrl? =
        base?.toHttpUrlOrNull()?.takeIf {
            it.scheme == "http" && it.host in FAKE_HOMES && it.username.isEmpty() && it.password.isEmpty()
        }
}
