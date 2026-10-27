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
