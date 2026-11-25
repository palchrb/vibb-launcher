package com.kidslauncher.mdm.day

import java.net.URI
import java.security.MessageDigest

/*
 * A calendar's identity on the wire (design 69 §4.1-4.2). Pure; CalendarReportTest.
 */

/** The calendar key: the first 16 hex of SHA-256 over `ACCOUNT_TYPE` NUL `ACCOUNT_NAME` NUL `Calendars.NAME` - the
 * feed or collection URL in ICSx5 and DAVx5, so renaming the display name keeps it. The account name (DAVx5: the login)
 * never leaves the phone; only this hash does. */
fun calendarKey(accountType: String?, accountName: String?, name: String?): String {
    val digest = MessageDigest.getInstance("SHA-256")
        .digest("${accountType.orEmpty()}\u0000${accountName.orEmpty()}\u0000${name.orEmpty()}".toByteArray(Charsets.UTF_8))
    return digest.take(8).joinToString("") { "%02x".format(it) }
}

private val SOURCE_SCHEMES = setOf("http", "https", "webcal", "webcals")
private val HOSTNAME = Regex("[a-z0-9.-]{1,253}")

/** The feed's host when `Calendars.NAME` is an http(s)/webcal URL - never its path, query, user or port (a feed URL
 * often carries a private token); anything else `null`. */
fun calendarSource(name: String?): String? {
    val uri = try {
        URI(name?.trim() ?: return null)
    } catch (e: Exception) {
        return null
    }
    if (uri.scheme?.lowercase() !in SOURCE_SCHEMES) return null
    return uri.host?.lowercase()?.trimEnd('.')?.takeIf { HOSTNAME.matches(it) }
}

/** The at signs an address may be written with: ASCII, fullwidth, small. */
private val AT_SIGNS = charArrayOf('@', '＠', '﹫')
private val TOKEN = Regex("\\S+")

/** How much of a name is read before the cut, so an address is masked whole. */
private const val NAME_SCAN_MAX = 1000

/** A calendar name for the status report, cleaned ([TitleRules.clean], at most 100 code points), with every token that
 * looks like an e-mail address (DAVx5 and Google name a calendar after the login) shortened to its first letter and
 * what follows the last at sign, `p…@example.org`, so an address never leaves the phone. [ownName]: the provider has a
 * display name of its own; without one (Dagen shows `Calendars.NAME`, maybe a feed URL with a token) the report sends
 * none. The phone's own screens keep the full name. */
fun reportedCalendarName(displayName: String, ownName: Boolean = true): String =
    if (!ownName) "" else TitleRules.clean(maskAddresses(TitleRules.clean(displayName, NAME_SCAN_MAX)), 100)

/** Every whitespace-separated token with an at sign that has text on both sides, masked; the rest as it is. */
fun maskAddresses(text: String): String = TOKEN.replace(text) { maskToken(it.value) }

private fun maskToken(token: String): String {
    val first = token.indexOfAny(AT_SIGNS)
    val last = token.lastIndexOfAny(AT_SIGNS)
    if (first <= 0 || last == token.length - 1) return token
    return token.substring(0, token.offsetByCodePoints(0, 1)) + "…@" + token.substring(last + 1)
}
