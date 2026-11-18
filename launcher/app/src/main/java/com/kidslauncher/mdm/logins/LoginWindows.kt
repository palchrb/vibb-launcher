package com.kidslauncher.mdm.logins

import com.kidslauncher.mdm.server.dto.PolicyResponse
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Design 102: the launcher's half of the shared contract `login_windows.json` (byte-identical with `server/testdata/`,
 * `LoginWindowsTest`): the policy's `captive_portal` (Part A, the Wi-Fi sign-in window) and `login_browser` (Part B,
 * Chrome only for logins), and the add field's entry rule the launcher also applies before Chrome sees a site. */

/** The one browser the role accepts (D8): anything else is `failed` with reason `package`, and nothing is unhidden. */
const val CHROME_PACKAGE = "com.android.chrome"

/** The policy's `captive_portal` (§6.1). Missing reads as these defaults: on, with the parent code
 * required (fail closed), 10 minutes an opening, no grant. No daily count (2026-11-18): an older server's
 * `day_openings`/`day_minutes` are ignored. */
@Serializable
data class CaptivePortalPolicy(
    val enabled: Boolean = true,
    val requireCode: Boolean = true,
    val minutes: Int = 10,
    /** The parent's "one more sign-in now" counter (D14): each step this phone hasn't used is one code-free opening. */
    val grant: Long = 0,
)

/** The policy's `login_browser` (§6.1): `null` while the role is off or Chrome is ticked as a normal app. */
@Serializable
data class LoginBrowserPolicy(
    @SerialName("package") val packageName: String = "",
    val hosts: List<String> = emptyList(),
)

/** The phone's `captive_portal`, the server's or the defaults. */
fun portalPolicyOf(policy: PolicyResponse?): CaptivePortalPolicy = policy?.captivePortal ?: CaptivePortalPolicy()

/** The phone's `login_browser`'s sites, each through [loginEntry] (an invalid one is dropped, never written to
 * Chrome); `null` while the role is off. */
fun loginHostsOf(policy: LoginBrowserPolicy?): List<String>? =
    policy?.hosts?.mapNotNull(::loginEntry)?.distinct()?.sorted()

private val PATH_CHARS = Regex("[A-Za-z0-9/._~%-]*")
private val LABEL = Regex("[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?")

/** An entry as the server's `logins::valid_entry` takes it (the contract's `entries`), or `null` for a refusal: a host
 * (lower case, ASCII, two labels or more, the last with a letter) with an optional leading dot, port and path. A pasted
 * `http(s)://` URL keeps its host and path; any other scheme, a `*`, a query without a pasted URL, a space or a
 * character outside the path's set is refused; a path of only `/` is dropped. */
fun loginEntry(input: String): String? {
    val text = input.trim()
    if (text.isEmpty() || text.length > 255 || '*' in text || text.any { it.code > 127 }) return null
    val lower = text.lowercase()
    val pasted = lower.startsWith("https://") || lower.startsWith("http://")
    val rest = if (pasted) {
        text.substring(text.indexOf("://") + 3).split('?', '#').first()
    } else {
        if ('?' in text || '#' in text || "://" in text) return null
        text
    }
    val slash = rest.indexOf('/')
    val authority = if (slash >= 0) rest.substring(0, slash) else rest
    val path = if (slash >= 0) rest.substring(slash) else ""
    val dot = authority.startsWith('.')
    val hostPort = authority.removePrefix(".")
    val colon = hostPort.indexOf(':')
    val host = (if (colon >= 0) hostPort.substring(0, colon) else hostPort).lowercase()
    val labels = host.split('.')
    if (labels.size < 2 || host.length > 253 || !labels.all { LABEL.matches(it) } ||
        labels.last().none { it in 'a'..'z' }
    ) return null
    val port = if (colon >= 0) {
        val digits = hostPort.substring(colon + 1)
        if (digits.isEmpty() || !digits.all { it in '0'..'9' }) return null
        val significant = digits.trimStart('0')
        if (significant.length > 5) return null
        val n = if (significant.isEmpty()) 0 else significant.toInt()
        if (n !in 1..65535) return null
        ":$n"
    } else ""
    if (!PATH_CHARS.matches(path)) return null
    return (if (dot) "." else "") + host + port + (if (path == "/") "" else path)
}
