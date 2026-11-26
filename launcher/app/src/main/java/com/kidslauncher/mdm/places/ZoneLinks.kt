package com.kidslauncher.mdm.places

import com.kidslauncher.mdm.server.dto.PolicyZone
import com.kidslauncher.mdm.zones.distanceM
import kotlin.math.sqrt

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
    fun owns(h: Heard): Boolean = h.network == network || ownsAddress(h)

    /** A scan result of this router by its address alone - its device, its multi-link key or its five octets, never
     * the name (round 2 L2: Apple's position for the router comes only from its own addresses). */
    fun ownsAddress(h: Heard): Boolean = h.device == device || (mld != null && h.device == mld) ||
        (h.bssid.length >= prefix.length && h.bssid.startsWith(prefix))
}

fun routerOf(connected: Connected?, keys: PlaceKeys): Router? {
    if (connected == null || !connected.unmetered) return null
    val bssid = connectedBssid(connected.bssid) ?: return null
    val ssid = connected.ssid?.takeIf { it.isNotEmpty() }
    val device = keys.device(bssid, null)
    // QA L5: a zero or redacted multi-link address is none.
    val mld = connectedBssid(connected.mld)?.let { keys.device(bssid, it) }
    return Router(keys.network(ssid, device), device, mld, bssid.substring(0, 14), connected.security, ssid != null && !connected.hexName)
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

/** D5: [router] is one of [zone]'s learned routers ([RowKind.LINK_AP], not blocked) - by its device keys alone (QA M2:
 * the `LINK` row binds the network, so the router's other name on the same device holds its own link). */
fun routerLearned(rows: List<PlaceRow>, zone: LinkZone, router: Router, nowMin: Int): Boolean = rows.any {
    it.kind == RowKind.LINK_AP && it.key == zone.key && !it.blocked(nowMin) && (it.device == router.device || it.device == router.mld)
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

/** D8 (M3): [view] without [router]'s results (its network, its device, its five octets) against [rows] - their centre
 * by the complete-link cluster, without the majority rule or the accuracy cap (QA M1: only the centre is judged). */
fun restWithout(view: ScanView, rows: List<PlaceRow>, nowMin: Int, router: Router): Rest {
    val others = view.heard.filter { it.kind == RowKind.WIFI && !router.owns(it) }
    learnedFix(ScanView(others, view.fresh), rows, nowMin, centreOnly = true)?.let { return Rest.Fix(it) }
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
 * [STRIKE_GAP_MAX_MS] before strikes; a new one waits; a router this teach saw inside its zone ([cleared], QA L4) drops
 * its pending strike. Returns the strikes and the pending map after. */
fun strikeStep(
    pending: Map<StrikeKey, Long>,
    candidates: Set<StrikeKey>,
    nowElapsedMs: Long,
    cleared: Set<StrikeKey> = emptySet(),
): Pair<Set<StrikeKey>, Map<StrikeKey, Long>> {
    val next = pending.filter { (k, at) -> nowElapsedMs - at in 0..STRIKE_GAP_MAX_MS && k !in cleared }.toMutableMap()
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

/** QA L4: the routers a GPS teach surely inside a zone sees - connected ([router]) or heard ([heard], their keys). */
fun insideKeys(zones: List<LinkZone>, lat: Double, lng: Double, accuracyM: Double, router: Router?, heard: List<List<String>>): Set<StrikeKey> {
    val out = mutableSetOf<StrikeKey>()
    for (z in zones) {
        if (distanceM(lat, lng, z.zone.latitude, z.zone.longitude) + accuracyM > z.zone.radiusM) continue
        router?.keys?.forEach { out += StrikeKey(z.key, it) }
        heard.flatten().forEach { out += StrikeKey(z.key, it) }
    }
    return out
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
    if (!zonesOn || connected == null || !connected.unmetered || connected.hexName) return null
    if (connected.security != LinkSecurity.PSK && connected.security != LinkSecurity.SAE) return null
    val bssid = connectedBssid(connected.bssid) ?: return null
    val ssid = connected.ssid?.takeIf { it.isNotEmpty() && it.toByteArray(Charsets.UTF_8).size <= 32 && it.none(Char::isISOControl) } ?: return null
    // QA L5: a zero or redacted multi-link address drops the address, never the report.
    return com.kidslauncher.mdm.server.dto.ZoneWifiReport(ssid, bssid, connectedBssid(connected.mld))
}

// --- The single-router fix (rev 3, the owner's "A" of 2026-11-26, with the location review's must-changes) ---

/** The level's part of the single-router fix's accuracy: 25 m at -45 dBm or stronger, 50 m at -65, 150 m at -85 or
 * weaker, straight lines between (the same room, a few walls, the edge of the router's reach). A level the answering
 * scan didn't give counts as -85: `WifiInfo.getRssi()` is stale with the screen off. */
fun routerAccuracyM(rssi: Int): Double = when {
    rssi >= -45 -> 25.0
    rssi >= -65 -> 25.0 + (-45 - rssi) * (25.0 / 20.0)
    rssi >= -85 -> 50.0 + (-65 - rssi) * (100.0 / 20.0)
    else -> 150.0
}

/** The bound for a level the answering scan didn't give. */
const val ROUTER_LEVEL_UNKNOWN_DBM = -85

/** Where the single-router fix put the router. */
enum class RouterPlace(val wire: String) { OWN("own"), APPLE("apple"), ZONE("zone") }

/** The single-router fix, or why none ([reason], the log's word). */
data class RouterAnswer(val fix: LearnedFix?, val reason: String, val place: RouterPlace? = null)

/**
 * The single-router fix: connected to a **confirmed** router ([confirmedFor]; never a learned link) with no other Wi-Fi
 * fix standing, a `link` fix at the router's own position - its own trusted Wi-Fi rows, else Apple's row for one of its
 * addresses heard, else the zone's centre - with the accuracy `sqrt(pos^2 + level^2)`: `pos` half the row's range, or
 * the zone's radius at its centre; `level` [routerAccuracyM] of the answering scan's level for the connected BSSID
 * ([scannedRssi]), else of [ROUTER_LEVEL_UNKNOWN_DBM].
 *
 * Only while the continuity gate holds ([gate], [continuityHolds]) - it keeps a known "inside", it never brings a phone
 * home after it was seen elsewhere. An [unchecked] Wi-Fi fix in hand whose centre is outside the zone vetoes it. None when anything contradicts: the router's rows
 * moved or blocked, a cell placed away ([away]), the neighbours' own fix with its centre outside, two or more placed
 * networks outside the zone's radius plus their range, any other placed network farther from the router's position
 * than both ranges, a conclusive scan ([conclusive]) of three or more other networks none of them placed near the zone,
 * or an impossible jump ([impossible]). It never teaches and never strikes.
 */
fun routerFix(
    router: Router?,
    scannedRssi: Int?,
    heard: List<Heard>,
    conclusive: Boolean,
    zones: List<LinkZone>,
    confirmed: List<ConfirmedAp>,
    struck: Set<Long>,
    rows: List<PlaceRow>,
    nowMin: Int,
    nowElapsedMs: Long,
    gate: Gate,
    away: (LinkZone) -> Boolean,
    impossible: (LearnedFix) -> Boolean = { false },
    unchecked: LearnedFix? = null,
): RouterAnswer {
    router ?: return RouterAnswer(null, "none")
    val zone = zones.firstOrNull { confirmedFor(router, it, confirmed, struck) != null } ?: return RouterAnswer(null, "unconfirmed")
    val r = zone.zone.radiusM.toDouble()
    fun fromZone(lat: Double, lng: Double) = distanceM(lat, lng, zone.zone.latitude, zone.zone.longitude)
    if (!continuityHolds(gate, zone)) return RouterAnswer(null, "continuity")
    if (routerMoved(rows, router, nowMin)) return RouterAnswer(null, "moved")
    if (away(zone)) return RouterAnswer(null, "cell")
    val wifi = heard.filter { it.kind == RowKind.WIFI }
    val others = wifi.filter { !router.owns(it) }
    val rest = restWithout(ScanView(wifi, true), rows, nowMin, router)
    if (rest is Rest.Fix && fromZone(rest.fix.lat, rest.fix.lng) > r) return RouterAnswer(null, "others")
    // Round 2 L1: an unchecked Wi-Fi fix in hand is the neighbours' verdict too - its centre outside the zone vetoes.
    if (unchecked != null && fromZone(unchecked.lat, unchecked.lng) > r) return RouterAnswer(null, "others")
    val own = rows.filter { it.kind == RowKind.WIFI && (it.device == router.device || it.device == router.mld) && it.trusted(nowMin) }
    val apple = rows.filter { it.kind == RowKind.APPLE }.associateBy { it.key }
    val ownRows = rows.filter { it.kind == RowKind.WIFI }.associateBy { it.key }
    val placed = others.mapNotNull { h -> chooseRow(h, ownRows[h.key], apple[h.key], nowMin)?.first?.let { h.network to it } }
    val outside = placed.filter { (_, row) -> fromZone(row.lat, row.lng) > r + row.rangeM }.map { it.first }.toSet()
    if (outside.size >= 2) return RouterAnswer(null, "outside")
    val near = placed.any { (_, row) -> fromZone(row.lat, row.lng) <= r + row.rangeM }
    if (conclusive && others.map { it.network }.toSet().size >= 3 && !near) return RouterAnswer(null, "unplaced")
    // Round 2 M3: the first source whose centre lies within the zone's radius - a router fix never lands outside its own
    // zone (no false "left", no gate lock-out); Apple's only by the router's own addresses (L2).
    val ownQuad = own.takeIf { it.isNotEmpty() }?.let {
        val w = it.sumOf { o -> o.weight.toDouble() }.coerceAtLeast(1e-6)
        Quad(it.sumOf { o -> o.lat * o.weight } / w, it.sumOf { o -> o.lng * o.weight } / w, it.maxOf { o -> o.rangeM.toDouble() }, RouterPlace.OWN)
    }
    val appleQuad = wifi.filter { router.ownsAddress(it) }.firstNotNullOfOrNull { h -> chooseRow(h, null, apple[h.key], nowMin)?.first }
        ?.let { Quad(it.lat, it.lng, it.rangeM.toDouble(), RouterPlace.APPLE) }
    // Verification QA L2 (b): own rows placing the router outside the zone by more than their range contradict.
    if (ownQuad != null && fromZone(ownQuad.lat, ownQuad.lng) > r + ownQuad.range) return RouterAnswer(null, "outside", RouterPlace.OWN)
    val (lat, lng, range, place) = listOfNotNull(ownQuad, appleQuad).firstOrNull { fromZone(it.lat, it.lng) <= r }
        ?: Quad(zone.zone.latitude, zone.zone.longitude, r, RouterPlace.ZONE)
    if (placed.any { (_, row) -> distanceM(row.lat, row.lng, lat, lng) > row.rangeM + range }) return RouterAnswer(null, "disagree", place)
    val pos = if (place == RouterPlace.ZONE) r else 0.5 * range
    val level = routerAccuracyM(knownRssi(scannedRssi) ?: ROUTER_LEVEL_UNKNOWN_DBM)
    val fix = LearnedFix(lat, lng, sqrt(pos * pos + level * level), 1, nowElapsedMs, true, emptySet(), PROVIDER_WIFI, PlaceSource.OWN, router = true)
    if (impossible(fix)) return RouterAnswer(null, "jump", place)
    return RouterAnswer(fix, "fix", place)
}

/** A candidate witness of the continuity gate (round 2 M2): a fix the phone knows - its place, accuracy, time, its own
 * boot and elapsed time in it (when known), and whether it is `link`-checked. */
data class GateFix(
    val lat: Double,
    val lng: Double,
    val accuracyM: Double?,
    val timeMs: Long,
    val bootCount: Int,
    val elapsedMs: Long? = null,
    val link: Boolean = false,
)

/** The gate's inputs: the candidates (the known fix - kept or cached - and the last satellite fix), the boot now and
 * the last shutdown's length before this boot (`PowerEvents`, `null` unknown). */
data class Gate(val witnesses: List<GateFix>, val bootNow: Int, val offGapMs: Long?)

/** A boot between the witness and now holds the gate only when the phone was off at most this long. */
const val GATE_OFF_MAX_MS = 10 * 60_000L

/** Verification QA L1: the candidates newest first - this boot's by elapsed time, ahead of every other boot's, those by
 * their time. */
fun gateOrder(witnesses: List<GateFix>, bootNow: Int): List<GateFix> = witnesses.sortedWith(
    compareByDescending<GateFix> { it.bootCount == bootNow && it.elapsedMs != null }
        .thenByDescending { if (it.bootCount == bootNow && it.elapsedMs != null) it.elapsedMs else it.timeMs },
)

/**
 * Round 2 M2 with the verification QA's H1: the continuity gate holds for [zone] when the newest candidate that says
 * something says inside. A `link`-checked fix with its centre inside says inside whatever its accuracy (the
 * single-router fix keeps the gate it came through); a fine fix (accuracy at most the radius) says inside or outside by
 * its centre; a coarse one (over the radius, a cell fix) says outside only when surely outside, else nothing - the next
 * older candidate decides. The inside witness of another boot holds only for the boot right before this one, after at
 * most [GATE_OFF_MAX_MS] off (a plain restart; not "off at home, on somewhere else").
 */
fun continuityHolds(gate: Gate, zone: LinkZone): Boolean {
    val r = zone.zone.radiusM.toDouble()
    for (w in gateOrder(gate.witnesses, gate.bootNow)) {
        val acc = w.accuracyM?.takeIf { it.isFinite() && it >= 0 } ?: continue
        val d = distanceM(w.lat, w.lng, zone.zone.latitude, zone.zone.longitude)
        val inside = when {
            w.link && d <= r -> true
            acc <= r -> d <= r
            d - acc > r -> false
            else -> continue
        }
        if (!inside) return false
        return w.bootCount == gate.bootNow ||
            (w.bootCount == gate.bootNow - 1 && gate.offGapMs != null && gate.offGapMs in 0..GATE_OFF_MAX_MS)
    }
    return false
}

/** Verification QA M1: the shutdown before this boot's length, computed at the boot - the wall clock now less the time
 * since the boot less the shutdown's instant - only for a shutdown of the boot right before ([offBoot] == [bootNow] -
 * 1); else `null`. */
fun bootOffGapMs(offAtMs: Long?, offBoot: Int?, bootNow: Int, nowMs: Long, elapsedMs: Long): Long? =
    if (offAtMs == null || offBoot == null || offBoot != bootNow - 1) null else (nowMs - elapsedMs - offAtMs).takeIf { it >= 0 }

/** Verification QA L2 (a): the single-router fix may take the place of the judged fix - none, or one without a check,
 * and never a renewal of the still vouch (A9 and the print keep it). */
fun routerMayReplace(check: FixCheck?, step: WifiStep?): Boolean = check == null && step != WifiStep.RENEW

/** Round 2 L6: the level for the single-router fix - the connected BSSID's own entry in the answering scan, else none
 * (the -85 dBm bound then). */
fun answeringLevel(answering: List<ScanAp>, connected: Connected?): Int? {
    val bssid = connectedBssid(connected?.bssid) ?: return null
    return knownRssi(answering.filter { normalBssid(it.bssid) == bssid }.maxOfOrNull { it.rssi })
}

/** Round 2 L6: what the single-router fix hears - results after the last motion fire, and the connection itself. */
fun heardSince(heard: List<Heard>, movedAtElapsedMs: Long): List<Heard> = heard.filter { it.elapsedMs > movedAtElapsedMs || it.connected }

private data class Quad(val lat: Double, val lng: Double, val range: Double, val place: RouterPlace)


/** D3, D7 (L2): the struck ids the policy still carries, at most [STRUCK_MAX]. */
fun struckInPolicy(struck: Set<Long>, zones: List<PolicyZone>): List<Long> {
    val carried = zones.flatMap { z -> z.networks.map { it.id } }.toSet()
    return struck.filter { it in carried }.sorted().take(STRUCK_MAX)
}
