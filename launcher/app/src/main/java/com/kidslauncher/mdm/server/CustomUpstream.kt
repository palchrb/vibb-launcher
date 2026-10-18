package com.kidslauncher.mdm.server

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * The parent's own DNS server and its backup (design 36 §4.1), read from the policy's raw
 * `dns_upstream`. Pure. Both targets are secrets (an AdGuard Home client id may be in either):
 * [toString] never shows them, and nothing may log them.
 */
data class CustomUpstream(val primary: UpstreamTarget, val backup: UpstreamTarget, val rev: Long) {
    override fun toString(): String = "CustomUpstream(${primary.kind}, rev=$rev, backup ${backup.kind})"
}

/** One server: DNS-over-HTTPS to [Doh.url], or DNS-over-TLS to [Dot.host]:[Dot.port]. */
sealed interface UpstreamTarget {
    val kind: String

    data class Doh(val url: String) : UpstreamTarget {
        override val kind get() = "doh"
        override fun toString(): String = "Doh(…)"
    }

    data class Dot(val host: String, val port: Int) : UpstreamTarget {
        override val kind get() = "dot"
        override fun toString(): String = "Dot(…)"
    }
}

/**
 * The policy's `dns_upstream`, checked again on the phone (design 36 §4.1): both targets usable -
 * kind `doh` with an `https://` URL OkHttp parses, or kind `dot` with a host and a port 1-65535 -,
 * a `rev`, a backup. Anything else (`null`, an older server, a future kind, a wrong type, a missing
 * or invalid backup) is `null`: the phone then forwards to the policy's `dns_upstream_provider`.
 */
fun customUpstreamFrom(element: JsonElement?): CustomUpstream? {
    val upstream = element as? JsonObject ?: return null
    val rev = upstream.number("rev")?.longOrNull ?: return null
    val primary = targetOf(upstream) ?: return null
    val backup = targetOf(upstream["backup"] as? JsonObject ?: return null) ?: return null
    return CustomUpstream(primary, backup, rev)
}

private fun targetOf(target: JsonObject): UpstreamTarget? = when (target.text("kind")) {
    "doh" -> target.text("url")
        ?.takeIf { it.startsWith("https://") && it.toHttpUrlOrNull() != null }
        ?.let { UpstreamTarget.Doh(it) }
    "dot" -> {
        val host = target.text("host")?.takeIf { it.isNotEmpty() }
        val port = target.number("port")?.intOrNull?.takeIf { it in 1..65535 }
        if (host != null && port != null) UpstreamTarget.Dot(host, port) else null
    }
    else -> null
}

/**
 * The pref `dns_upstream_custom` for an accepted policy's `dns_upstream` (design 36 §5.1): the raw
 * JSON when [customUpstreamFrom] takes it, else `null` (the pref is removed: the provider). Written
 * only with the accepted policy, in the same `commit()`; a decode failure or a rejected policy never
 * reaches it.
 */
fun customUpstreamPref(element: JsonElement?): String? =
    element?.takeIf { customUpstreamFrom(it) != null }?.let { ServerJson.encodeToString(JsonElement.serializer(), it) }

/** The servers in the pref, read back; anything unreadable is `null` (the provider). */
fun customUpstreamFromPref(raw: String?): CustomUpstream? =
    raw?.let { runCatching { customUpstreamFrom(ServerJson.parseToJsonElement(it)) }.getOrNull() }

/** A JSON string field, `null` for anything else. */
private fun JsonObject.text(key: String): String? =
    (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

/** A JSON number field (never a quoted one), `null` for anything else. */
private fun JsonObject.number(key: String): JsonPrimitive? =
    (this[key] as? JsonPrimitive)?.takeUnless { it.isString }
