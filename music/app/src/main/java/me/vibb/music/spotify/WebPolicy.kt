package me.vibb.music.spotify

import kotlinx.serialization.json.JsonPrimitive
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/*
 * What the phone's one web player may load (design 26c §5.2, §7.2), decided here and applied by the
 * Android host in step 5a (`shouldInterceptRequest` for every frame, the service-worker client too):
 * our two assets on our origin, https to Spotify's hosts ([isSpotifyHost]), and - with the debug
 * switch only - the fake's https name fetched from the debug base. Everything else is refused. Also
 * the page itself: its CSP, the one substitution in its template, and the names of the page bridge
 * both sides spell ([SpotifyPage]; PlayerJsTest holds `player.js` to them). Pure; WebPolicyTest.
 */

/** What the host does with one request of the page. */
sealed interface WebDecision {
    /** Answer it from the app's assets at [path] (`spotify/player.html`, `spotify/player.js`). */
    data class Asset(val path: String) : WebDecision

    /** Let it through (`null` from the interceptor): https to one of Spotify's hosts, or an in-memory `blob:`/`data:`. */
    data object Pass : WebDecision

    /** Fetch it from [url] over the LAN client and answer with that (the debug switch's fake only). */
    data class Proxy(val url: HttpUrl) : WebDecision

    /** Our origin, but no asset of ours: an empty 404. */
    data object NotFound : WebDecision

    /** Anything else: an empty 403, and the host counted (debug log: the host only, never the URL). */
    data object Block : WebDecision
}

/**
 * The request allowlist. [extraHosts] and [proxy] are `SpotifyEndpoints.webHosts()` and
 * `SpotifyEndpoints::webProxy` ([current]); the defaults are a release build's (none).
 */
class WebPolicy(
    private val extraHosts: Set<String> = emptySet(),
    private val proxy: (HttpUrl) -> HttpUrl? = { null },
) {
    /** The decision for [url] (any scheme; as the request names it). */
    fun decide(url: String): WebDecision {
        val scheme = url.substringBefore(':', "").lowercase()
        if (scheme == "blob" || scheme == "data") return WebDecision.Pass // in memory: the SDK's media source, an inline picture
        val parsed = url.toHttpUrlOrNull() ?: return WebDecision.Block
        if (parsed.scheme != "https" || parsed.port != 443 || parsed.username.isNotEmpty() || parsed.password.isNotEmpty()) {
            return WebDecision.Block // http (mixed content) and odd ports never pass
        }
        val host = parsed.host
        if (host == SpotifyPage.ORIGIN_HOST) {
            return SpotifyPage.ASSETS[parsed.encodedPath]?.let { WebDecision.Asset(it) } ?: WebDecision.NotFound
        }
        if (host in extraHosts) return proxy(parsed)?.let { WebDecision.Proxy(it) } ?: WebDecision.Block
        return if (isSpotifyHost(host)) WebDecision.Pass else WebDecision.Block
    }

    companion object {
        /** The policy of this build and run: Spotify's hosts, plus the fake's https name with the debug switch. */
        fun current(): WebPolicy = WebPolicy(SpotifyEndpoints.webHosts(), SpotifyEndpoints::webProxy)
    }
}

/**
 * The page and its bridge (design 26c §5.2, §5.3): `assets/spotify/player.html` loaded under
 * [BASE_URL] (the spike's secure origin; no `androidx.webkit`), which loads `player.js` from the same
 * origin and the SDK from `SpotifyEndpoints.sdkUrl()`. The names below are the contract between
 * `player.js` and the step 5a host (PlayerJsTest checks the script against them).
 */
object SpotifyPage {
    /** Our origin's host: a name Android's web player keeps for an app's own content (never on the network). */
    const val ORIGIN_HOST = "appassets.androidplatform.net"

    /** `loadDataWithBaseURL`'s base: the page's relative `player.js` resolves under it. */
    const val BASE_URL = "https://$ORIGIN_HOST/spotify/"

    /** Our two assets by the path the page requests them at. */
    val ASSETS = mapOf("/spotify/player.html" to "spotify/player.html", "/spotify/player.js" to "spotify/player.js")

    /** The template's placeholders: the SDK script's URL and its origin (the CSP admits that origin's scripts and frames). */
    const val SDK_URL_SLOT = "{{SDK_URL}}"
    const val SDK_ORIGIN_SLOT = "{{SDK_ORIGIN}}"

    /** The `addJavascriptInterface` object's name: the page's only way to the host. */
    const val BRIDGE = "VibbSpotify"

    /** The bridge's methods, page -> host (each validates and caps its inputs, and does nothing privileged). */
    val FROM_PAGE = listOf("ready", "notReady", "needToken", "state", "error")

    /** The page's entry points, host -> page (`evaluateJavascript`, every argument quoted by [call]). */
    val TO_PAGE = listOf("connect", "token", "pause", "resume", "seek", "next", "previous", "volume", "state", "disconnect")

    /** `error(kind, message)`'s kinds; `csp` (a CSP violation, qa-26c-step4-code L6) goes to the debug log only. */
    val ERROR_KINDS = listOf("initialization", "authentication", "account", "playback", "autoplay", "script", "csp")

    /** The player's name in the account's device list (Policy §VI: never "Spot..."). */
    const val PLAYER_NAME = "Vibb"

    /** A device id as `ready`/`notReady` may give it. */
    val DEVICE_ID = Regex("[A-Za-z0-9]{1,64}")

    /** `error`'s message is cut to this, and kept only in the debug log. */
    const val MESSAGE_MAX = 200

    /**
     * The page's CSP (§5.2) for an SDK served from [sdkOrigin]: scripts only from our origin and the
     * SDK's, frames only the SDK's (Spotify's iframe brings its own), connections and media over
     * https (and the SDK's `wss:` and `blob:`), pictures over https or inline.
     */
    fun csp(sdkOrigin: String): String =
        "default-src 'none'; script-src https://$ORIGIN_HOST $sdkOrigin; frame-src $sdkOrigin; " +
            "connect-src https: wss:; media-src https: blob:; img-src https: data:"

    /**
     * The page from its [template] (`player.html`) with the SDK at [sdkUrl] (an https URL): the
     * template's only substitution - the URL in the script tag, its origin in the CSP. Throws for a
     * template without both slots or an SDK URL that isn't plain https.
     */
    fun html(template: String, sdkUrl: String): String {
        val url = sdkUrl.toHttpUrl()
        require(url.scheme == "https" && url.username.isEmpty() && url.password.isEmpty() && url.query == null) { "the SDK is plain https" }
        require(template.contains(SDK_URL_SLOT) && template.contains(SDK_ORIGIN_SLOT)) { "the template has both slots" }
        val origin = if (url.port == 443) "https://${url.host}" else "https://${url.host}:${url.port}"
        return template.replace(SDK_URL_SLOT, attribute(url.toString())).replace(SDK_ORIGIN_SLOT, attribute(origin))
    }

    /**
     * One host -> page call as `evaluateJavascript` runs it: `vibb.<name>(<args>)` with every string a
     * quoted JSON literal (U+2028/U+2029 escaped too), a number as it is, null as `null`. [name] must be
     * one of [TO_PAGE].
     */
    fun call(name: String, vararg args: Any?): String {
        require(name in TO_PAGE) { "not a page entry point: $name" }
        return "vibb.$name(" + args.joinToString(",") { quote(it) } + ")"
    }

    private fun quote(arg: Any?): String = when (arg) {
        null -> "null"
        is Int, is Long -> arg.toString()
        is Double -> if (arg.isFinite()) arg.toString() else "null"
        is Float -> if (arg.isFinite()) arg.toString() else "null"
        is Boolean -> arg.toString()
        is String -> JsonPrimitive(arg).toString().replace("\u2028", "\\u2028").replace("\u2029", "\\u2029")
        else -> throw IllegalArgumentException("not a page argument: ${arg::class.simpleName}")
    }

    private fun attribute(s: String): String =
        s.replace("&", "&amp;").replace("\"", "&quot;").replace("<", "&lt;").replace(">", "&gt;")
}
