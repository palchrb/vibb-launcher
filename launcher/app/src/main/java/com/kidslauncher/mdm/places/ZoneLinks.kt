package com.kidslauncher.mdm.places

import com.kidslauncher.mdm.server.dto.PolicyZone
import com.kidslauncher.mdm.zones.distanceM

// Design 138 (docs/design/138-confirmed-zone-networks.md): a zone's Wi-Fi - the parent's confirmation, the learned
// link bound to its routers, the neighbours' agreement at every use, the cell veto and the strikes by router. Pure; the
// glue is in WifiPlaces.

/** D7: a second teaching fix at least this long after the first makes a strike ... */
const val STRIKE_GAP_MIN_MS = 60_000L

/** ... and at most this long; a pending strike older than this is forgotten. */
const val STRIKE_GAP_MAX_MS = 2 * 60 * 60_000L

/** D3: the report carries at most this many struck ids. */
const val STRUCK_MAX = 80

/**
 * The connected router as a zone link sees it (D1) - memory only, never stored or logged: the network key (the SSID's,
 * or the device's for a hidden one), the router's device key (its BSSID's first five octets, design 125's one router's
 * bands), its multi-link key when Android gives the AP's MLD address, the BSSID's first five octets as text (to leave a
 * scan result of the same router out, D8) and the security. `null` when not connected, metered or the BSSID unreadable.
 */
data class Router(
    val network: String,
    val device: String,
    val mld: String?,
    val prefix: String,
    val security: LinkSecurity,
    val named: Boolean,
) {
    /** The keys a router row or a confirmation may match. */
    val keys: List<String> get() = listOfNotNull(device, mld)

    /** A scan result of this router - its network (a guest name on the same device is its device's), its device or
     * its five octets (M3). */
    fun owns(h: Heard): Boolean = h.network == network || h.device == device || (mld != null && h.device == mld) ||
        (h.bssid.length >= prefix.length && h.bssid.startsWith(prefix))
}

fun routerOf(connected: Connected?, keys: PlaceKeys): Router? {
    if (connected == null || !connected.unmetered) return null
    val bssid = connectedBssid(connected.bssid) ?: return null
    val ssid = connected.ssid?.takeIf { it.isNotEmpty() }
    val device = keys.device(bssid, null)
    val mld = normalBssid(connected.mld)?.let { keys.device(bssid, it) }
    return Router(keys.network(ssid, device), device, mld, bssid.substring(0, 14), connected.security, ssid != null)
}

/** A parent's confirmation (D2) as the phone matches it: the server's row id, the zone's policy id, the network key, the
 * router's device key and its multi-link key. */
data class ConfirmedAp(val id: Long, val zoneId: Long, val network: String, val device: String, val mld: String?)

/** The policy's confirmations ([PolicyZone.networks]); a row whose BSSID doesn't parse or whose name is empty is none. */
fun confirmedAps(zones: List<PolicyZone>, keys: PlaceKeys): List<ConfirmedAp> = zones.flatMap { z ->
    z.networks.mapNotNull { n ->
        val b = connectedBssid(n.bssid)
        if (b == null || n.ssid.isEmpty()) null else ConfirmedAp(n.id, z.id, keys.network(n.ssid, ""), keys.device(b, null), normalBssid(n.mld)?.let { keys.device(b, it) })
    }
}

/** D1 (H1): the same router - its device key, or the same multi-link address known on both sides. */
fun sameRouter(device: String, mld: String?, otherDevice: String, otherMld: String?): Boolean =
    device == otherDevice || (mld != null && mld == otherMld)

/** What holds a zone link for the connection (D1). */
enum class LinkBy(val wire: String) { CONFIRMED("confirmed"), LEARNED("learned") }

/** D1: an unstruck confirmation of [zone] for [router] - a named PSK/SAE network (M4: no network id needed), the same
 * name and router. */
fun confirmedFor(router: Router, zone: LinkZone, confirmed: List<ConfirmedAp>, struck: Set<Long>): ConfirmedAp? {
    if (!router.named || (router.security != LinkSecurity.PSK && router.security != LinkSecurity.SAE)) return null
    return confirmed.firstOrNull {
        it.zoneId == zone.zone.id && it.id !in struck && it.network == router.network && sameRouter(it.device, it.mld, router.device, router.mld)
    }
}

/** D5: [router] is one of [zone]'s learned routers ([RowKind.LINK_AP], not blocked). */
fun routerLearned(rows: List<PlaceRow>, zone: LinkZone, router: Router, nowMin: Int): Boolean = rows.any {
    it.kind == RowKind.LINK_AP && it.key == zone.key && it.network == router.network && !it.blocked(nowMin) &&
        (it.device == router.device || it.device == router.mld)
}

/** D8's exception: the router's own Wi-Fi rows have moved or are blocked (design 125). */
fun routerMoved(rows: List<PlaceRow>, router: Router, nowMin: Int): Boolean = rows.any {
    it.kind == RowKind.WIFI && (it.device == router.device || it.device == router.mld) && (it.moves > 0 || it.blocked(nowMin))
}

/** A placed neighbour's position and range (D8's one-neighbour case). */
data class Placed(val lat: Double, val lng: Double, val rangeM: Double)

/** D8: what the scan says without the connected router. */
sealed interface Rest {
    /** The neighbours make a fix of their own. */
    data class Fix(val fix: LearnedFix) : Rest

    /** No fix, and exactly one network of the neighbours placed (its rows). */
    data class One(val places: List<Placed>) : Rest

    /** Nothing else placed: no result with a trusted own row or a live Apple row. */
    data object Nothing : Rest

    /** Two or more networks placed, and still no fix. */
    data object NoFix : Rest
}

/** D8 (M3): [view] without [router]'s results (its network, its device, its five octets) against [rows]. */
fun restWithout(view: ScanView, rows: List<PlaceRow>, nowMin: Int, router: Router): Rest {
    val others = view.heard.filter { it.kind == RowKind.WIFI && !router.owns(it) }
    learnedFix(ScanView(others, view.fresh), rows, nowMin)?.let { return Rest.Fix(it) }
    val own = rows.asSequence().filter { it.kind == RowKind.WIFI }.associateBy { it.key }
    val apple = rows.asSequence().filter { it.kind == RowKind.APPLE }.associateBy { it.key }
    val placed = others.mapNotNull { h -> chooseRow(h, own[h.key], apple[h.key], nowMin)?.let { h.network to it.first } }
    return when (placed.map { it.first }.toSet().size) {
        0 -> Rest.Nothing
        1 -> Rest.One(placed.map { (_, row) -> Placed(row.lat, row.lng, row.rangeM.toDouble()) })
        else -> Rest.NoFix
    }
}

/** D9: the registered cell is placed further from [zone] than its range + the zone's radius + [accuracyM] + 1 km
 * (design 134's [cellElsewhere]); unknown, no SIM: no veto. */
fun cellAway(cells: List<Heard>, rows: List<PlaceRow>, nowMin: Int, zone: LinkZone, accuracyM: Double): Boolean =
    cellElsewhere(cells, rows, nowMin, VouchPoint(nowMin, zone.zone.latitude, zone.zone.longitude, (zone.zone.radiusM + accuracyM).toFloat()))

/** The connection's link to [zone] (D1), before any fix: a confirmation, or a learned link (design 131's binding,
 * [learnedHolds]) with [router] among its routers. */
fun zoneLink(router: Router, conn: LinkConn?, zone: LinkZone, confirmed: List<ConfirmedAp>, struck: Set<Long>, rows: List<PlaceRow>, nowMin: Int, apOwn: Boolean): LinkBy? = when {
    confirmedFor(router, zone, confirmed, struck) != null -> LinkBy.CONFIRMED
    learnedHolds(conn, zone, rows, nowMin, apOwn) && routerLearned(rows, zone, router, nowMin) -> LinkBy.LEARNED
    else -> null
}

/** Design 131's learned binding for one zone: the linked [RowKind.LINK] row of the connection's network under its
 * binding, and the security part ([linkSecurityHolds]). */
fun learnedHolds(conn: LinkConn?, zone: LinkZone, rows: List<PlaceRow>, nowMin: Int, apOwn: Boolean): Boolean {
    conn ?: return false
    if (!linkSecurityHolds(conn, apOwn)) return false
    return rows.any { it.kind == RowKind.LINK && it.key == zone.key && it.network == conn.network && it.device == conn.binding && it.linked(nowMin) }
}

/**
 * D8: the link check of a Wi-Fi [fix] - for some zone, a link ([zoneLink]) with the fix's centre inside (`d <= r`) for a
 * confirmation (M1) or surely inside (`d + a <= r`) for a learned link; no cell veto ([away]); and the neighbours
 * agreeing without the router ([rest]): their fix's centre inside; a confirmation also stands with nothing else placed,
 * or one placed network whose rows all lie within the radius + their range - unless the router's own rows moved
 * ([routerMoved]). A learned link has no exception. `null`: no link.
 */
fun linkCheck(
    fix: LearnedFix,
    router: Router?,
    conn: LinkConn?,
    rows: List<PlaceRow>,
    zones: List<LinkZone>,
    nowMin: Int,
    confirmed: List<ConfirmedAp>,
    struck: Set<Long>,
    rest: Rest?,
    away: (LinkZone) -> Boolean,
): LinkBy? {
    router ?: return null
    val apOwn = conn?.apKey != null && conn.apKey in fix.keys
    for (z in zones) {
        val r = z.zone.radiusM.toDouble()
        val d = distanceM(fix.lat, fix.lng, z.zone.latitude, z.zone.longitude)
        val by = when {
            d <= r && confirmedFor(router, z, confirmed, struck) != null -> LinkBy.CONFIRMED
            d + fix.accuracyM <= r && learnedHolds(conn, z, rows, nowMin, apOwn) && routerLearned(rows, z, router, nowMin) -> LinkBy.LEARNED
            else -> null
        } ?: continue
        if (away(z)) continue
        val agrees = when (rest) {
            is Rest.Fix -> distanceM(rest.fix.lat, rest.fix.lng, z.zone.latitude, z.zone.longitude) <= r
            is Rest.One -> by == LinkBy.CONFIRMED && !routerMoved(rows, router, nowMin) &&
                rest.places.all { distanceM(it.lat, it.lng, z.zone.latitude, z.zone.longitude) <= r + it.rangeM }
            Rest.Nothing -> by == LinkBy.CONFIRMED && !routerMoved(rows, router, nowMin)
            Rest.NoFix, null -> false
        }
        if (agrees) return by
    }
    return null
}

/** D12's renewal of a still vouch ([linkRenews]'s rule) through either source: the vouch surely inside a zone the
 * connection holds a link to. */
fun zoneRenews(
    rows: List<PlaceRow>,
    zones: List<LinkZone>,
    vouched: Vouched?,
    router: Router?,
    conn: LinkConn?,
    still: Boolean,
    refreshDue: Boolean,
    nowMin: Int,
    confirmed: List<ConfirmedAp>,
    struck: Set<Long>,
): Boolean {
    if (!still || refreshDue || vouched == null || router == null) return false
    return zones.any { z ->
        distanceM(vouched.lat, vouched.lng, z.zone.latitude, z.zone.longitude) + vouched.accuracyM <= z.zone.radiusM &&
            zoneLink(router, conn, z, confirmed, struck, rows, nowMin, apOwn = false) != null
    }
}

/**
 * D6: a Wi-Fi fix teaches the learned link of [zone] - fresh, not refused, not itself link-checked, no disagreement in
 * the attempt, no cell veto, surely inside, and the neighbours' fix without the router ([rest]) surely inside too.
 */
fun wifiOccasion(fix: LearnedFix, step: WifiStep, check: FixCheck?, disagreed: Boolean, rest: Rest?, zone: LinkZone, away: Boolean): Boolean {
    if (!fix.fresh || step == WifiStep.REFUSE || check == FixCheck.LINK || disagreed || away) return false
    fun surely(lat: Double, lng: Double, a: Double) = distanceM(lat, lng, zone.zone.latitude, zone.zone.longitude) + a <= zone.zone.radiusM
    return surely(fix.lat, fix.lng, fix.accuracyM) && rest is Rest.Fix && surely(rest.fix.lat, rest.fix.lng, rest.fix.accuracyM)
}

/** A router a strike may name: the zone's key and the router's key. */
data class StrikeKey(val zone: String, val router: String)

/**
 * D7 at a GPS teach ([lat], [lng], [accuracyM] of teaching quality): the zones' routers - their confirmations' and
 * learned rows' keys - that this teach places far outside: [router] connected and `d - a >= r + 200 m`, or a result
 * paired with the teacher ([heard]: each its keys and band) and `d - a >= r +` the band's reach.
 */
fun strikeCandidates(
    zones: List<LinkZone>,
    lat: Double,
    lng: Double,
    accuracyM: Double,
    router: Router?,
    heard: List<Pair<List<String>, Band>>,
    confirmed: List<ConfirmedAp>,
    rows: List<PlaceRow>,
    nowMin: Int,
): Set<StrikeKey> {
    val out = mutableSetOf<StrikeKey>()
    for (z in zones) {
        val known = confirmed.filter { it.zoneId == z.zone.id }.flatMap { listOfNotNull(it.device, it.mld) }.toSet() +
            rows.filter { it.kind == RowKind.LINK_AP && it.key == z.key && !it.blocked(nowMin) }.map { it.device }
        if (known.isEmpty()) continue
        val beyond = distanceM(lat, lng, z.zone.latitude, z.zone.longitude) - accuracyM - z.zone.radiusM
        if (router != null && beyond >= LINK_OUTSIDE_SLACK_M) router.keys.filter { it in known }.forEach { out += StrikeKey(z.key, it) }
        for ((ks, band) in heard) {
            val reach = band.reachM.takeIf { it > 0 } ?: Band.G24.reachM
            if (beyond >= reach) ks.filter { it in known }.forEach { out += StrikeKey(z.key, it) }
        }
    }
    return out
}

/** D7 (M2): the strikes of a teach at [nowElapsedMs] - a candidate pending from a teach [STRIKE_GAP_MIN_MS] to
 * [STRIKE_GAP_MAX_MS] before strikes; a new one waits. Returns the strikes and the pending map after. */
fun strikeStep(pending: Map<StrikeKey, Long>, candidates: Set<StrikeKey>, nowElapsedMs: Long): Pair<Set<StrikeKey>, Map<StrikeKey, Long>> {
    val next = pending.filterValues { nowElapsedMs - it in 0..STRIKE_GAP_MAX_MS }.toMutableMap()
    val struck = mutableSetOf<StrikeKey>()
    for (c in candidates) {
        val first = next[c]
        when {
            first == null -> next[c] = nowElapsedMs
            nowElapsedMs - first >= STRIKE_GAP_MIN_MS -> {
                struck += c
                next.remove(c)
            }
        }
    }
    return struck to next
}

/** D7: what [struck] does - the confirmations' ids it names, and the learned routers blocked [BLOCK_MIN]. */
fun applyStrikes(rows: List<PlaceRow>, zones: List<LinkZone>, confirmed: List<ConfirmedAp>, struck: Set<StrikeKey>, nowMin: Int): Pair<List<PlaceRow>, Set<Long>> {
    if (struck.isEmpty()) return rows to emptySet()
    val keyOf = zones.associate { it.zone.id to it.key }
    val ids = confirmed.filter { ap ->
        val zk = keyOf[ap.zoneId] ?: return@filter false
        StrikeKey(zk, ap.device) in struck || (ap.mld != null && StrikeKey(zk, ap.mld) in struck)
    }.map { it.id }.toSet()
    val out = rows.map {
        if (it.kind == RowKind.LINK_AP && StrikeKey(it.key, it.device) in struck && !it.blocked(nowMin)) {
            it.copy(moves = 1, lastMoveMin = nowMin, blockedUntilMin = nowMin + BLOCK_MIN, lastSeenMin = nowMin)
        } else {
            it
        }
    }
    return out to ids
}

/** L6: a learned router in use keeps its `lastSeen` fresh, at most once a day; the same list when nothing changed. */
fun touchRouter(rows: List<PlaceRow>, zone: LinkZone, router: Router, nowMin: Int): List<PlaceRow> {
    fun stale(r: PlaceRow) = r.kind == RowKind.LINK_AP && r.key == zone.key && (r.device == router.device || r.device == router.mld) &&
        nowMin - r.lastSeenMin !in 0 until SEEN_STEP_MIN
    return if (rows.none(::stale)) rows else rows.map { if (stale(it)) it.copy(lastSeenMin = nowMin) else it }
}

/** D3: the report's `wifi` - a named PSK/SAE network, unmetered, a readable BSSID, while the policy has zones; else none. */
fun zoneWifiOf(connected: Connected?, zonesOn: Boolean): com.kidslauncher.mdm.server.dto.ZoneWifiReport? {
    if (!zonesOn || connected == null || !connected.unmetered) return null
    if (connected.security != LinkSecurity.PSK && connected.security != LinkSecurity.SAE) return null
    val bssid = connectedBssid(connected.bssid) ?: return null
    val ssid = connected.ssid?.takeIf { it.isNotEmpty() && it.toByteArray(Charsets.UTF_8).size <= 32 && it.none(Char::isISOControl) } ?: return null
    return com.kidslauncher.mdm.server.dto.ZoneWifiReport(ssid, bssid, normalBssid(connected.mld))
}

/** D3, D7 (L2): the struck ids the policy still carries, at most [STRUCK_MAX]. */
fun struckInPolicy(struck: Set<Long>, zones: List<PolicyZone>): List<Long> {
    val carried = zones.flatMap { z -> z.networks.map { it.id } }.toSet()
    return struck.filter { it in carried }.sorted().take(STRUCK_MAX)
}
