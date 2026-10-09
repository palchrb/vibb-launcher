package me.vibb.music.logic

import me.vibb.music.model.Entry
import me.vibb.music.model.Source
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress

/*
 * The phone's half of design 21b §2.7 (QA of step 1c, Low 14): the server checks a public entry's
 * hosts once, but the phone resolves them itself, so a rebinding name (public to the server,
 * private to the phone) could still send the phone to a LAN or tailnet address. For an item of a
 * public entry the app refuses to connect to a private address - checked on the address it
 * actually connects to, on every redirect hop (media/MediaCache.kt). Pure; AddressesTest.
 */

/**
 * Must [entry]'s audio come from public addresses only? NRK is always public-only (21b §2.7). An
 * RSS entry's class (`lan`, fixed at the server's first check) isn't in the library or listing
 * contract yet, so RSS entries aren't guarded until the server sends it.
 */
fun publicOnly(entry: Entry): Boolean = entry.source == Source.NRK

/**
 * 21b §2.7's ranges: 0/8, 10/8, 100.64/10 (CGNAT, the tailnet), 127/8, 169.254/16, 172.16/12,
 * 192.0.0/24, 192.168/16, 198.18/15, 224/3; `::`, `::1`, fc00::/7 (ULA, the tailnet's
 * fd7a:115c:a1e0::/48), fe80::/10, ff00::/8; ::ffff:0:0/96 and 64:ff9b::/96 by their IPv4.
 */
fun isPrivateAddress(address: InetAddress): Boolean {
    val b = address.address.map { it.toInt() and 0xFF }
    return when (address) {
        is Inet4Address -> privateV4(b)
        is Inet6Address -> privateV6(b)
        else -> true
    }
}

private fun privateV4(b: List<Int>): Boolean = when {
    b[0] == 0 || b[0] == 10 || b[0] == 127 -> true
    b[0] == 100 && b[1] in 64..127 -> true
    b[0] == 169 && b[1] == 254 -> true
    b[0] == 172 && b[1] in 16..31 -> true
    b[0] == 192 && b[1] == 0 && b[2] == 0 -> true
    b[0] == 192 && b[1] == 168 -> true
    b[0] == 198 && (b[1] == 18 || b[1] == 19) -> true
    b[0] >= 224 -> true
    else -> false
}

private fun privateV6(b: List<Int>): Boolean {
    if (b.all { it == 0 }) return true // ::
    if (b.take(15).all { it == 0 } && b[15] == 1) return true // ::1
    // ::ffff:0:0/96 (mapped) and 64:ff9b::/96 (NAT64) carry an IPv4 address.
    if (b.take(10).all { it == 0 } && b[10] == 0xFF && b[11] == 0xFF) return privateV4(b.takeLast(4))
    if (b[0] == 0x00 && b[1] == 0x64 && b[2] == 0xFF && b[3] == 0x9B && b.subList(4, 12).all { it == 0 }) return privateV4(b.takeLast(4))
    return (b[0] and 0xFE) == 0xFC || // fc00::/7
        (b[0] == 0xFE && (b[1] and 0xC0) == 0x80) || // fe80::/10
        b[0] == 0xFF // ff00::/8
}
