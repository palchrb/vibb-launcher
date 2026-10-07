package com.kidslauncher.mdm.push

/*
 * Hosts Play services needs for FCM (handy step 7, QA #10) - for other apps' FCM (Element X)
 * since design 19; our own nudges come over the SSE stream. The on-device DNS filter
 * (KidVpnService/DnsFilterEngine) never blocks them, whatever a blocklist says - ad lists often
 * carry Firebase hosts, and a blocked mtalk connection silently stops every push of every app.
 * The server drops them (and their parents) from the delivered blocklist too; this is the second
 * gate.
 * Telemetry is blocked by exact host only, never by these names. Pure, tested in FcmHostsTest.
 */

private val EXACT = setOf(
    "fcm.googleapis.com",
    "fcmtoken.googleapis.com",
    "fcmregistrations.googleapis.com",
    "firebaseinstallations.googleapis.com",
    "android.apis.google.com",
    "android.googleapis.com",
    "android.clients.google.com",
    "mtalk.google.com",
)

/** mtalk.google.com, mtalk4.google.com, alt3-mtalk.google.com, mtalk-staging.google.com, ... */
private val MTALK = Regex("""^(alt\d+-)?mtalk\d*(-staging)?\.google\.com$""")

fun isFcmHost(domainRaw: String): Boolean {
    val domain = domainRaw.trimEnd('.').lowercase()
    return domain in EXACT || MTALK.matches(domain)
}
