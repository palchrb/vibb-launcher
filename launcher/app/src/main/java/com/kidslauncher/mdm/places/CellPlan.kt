package com.kidslauncher.mdm.places

import com.kidslauncher.mdm.server.ServerJson
import com.kidslauncher.mdm.zones.distanceM
import kotlin.math.sqrt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.serialization.Serializable

/*
 * Apple's cell lookup (design 125b, step 3 of design 125) - the pure rules: which cells the phone reads (C1, C2) and
 * their wire form (C3), which it asks the server for (C4) and what it does with each answer (C5, the shared
 * `wifi_lookup/contract.json`'s `cell_phone_answers`), the Apple cell rows (C7: kind 5, keyed by the cell key with its
 * radio, a fixed 90 days), own against Apple, and the cell fix (C8: GrapheneOS's median reference and 1/r^2 weights, our more
 * conservative accuracy). GrapheneOS NetworkLocation (MIT, 474472e: `LocationReportingTask.kt`'s cell estimate) is the
 * model; its notice is in NOTICE.md. Tested in PlacesTest (`wifi_places.json`'s cell rows) and WifiLookupContractTest.
 */

/** At most this many cells read and asked (GrapheneOS's 12; contract `rules.cells_max`). */
const val CELLS_MAX = 12

/** No ask when this many read cells already have a position (GrapheneOS's `onlyCachedThreshold`). */
const val CELLS_POSITIONED_ENOUGH = 8

/** A cell whose time is older than this before the attempt's start is not used or asked (C2). */
const val CELL_READ_MAX_AGE_MS = 60_000L

/** An own cell row gives a position on this many occasions (D10). */
const val CELL_OCCASIONS = 3

/** An Apple cell row lives this long from receipt, a negative this long (contract `rules`; C7). */
const val APPLE_CELL_ROW_MIN = 90 * 24 * 60
const val APPLE_CELL_NEGATIVE_MIN = 30 * 24 * 60

/** A cells request's own timeout (C5): the server's fixed answer time through a proxy (contract
 * `rules.cell_answer_after_s`, 14 s) and the network's way there and back. */
const val APPLE_CELL_TIMEOUT_MS = 20_000L

/** At most this many Apple cell rows, the least recently seen out first (C7). */
const val APPLE_CELL_ROWS_MAX = 500

/** A tower's accuracy as the server keeps it: at most this (C6). */
const val APPLE_CELL_ACC_MAX_M = 50_000

const val PROVIDER_APPLE_CELL = "vibb_apple_cell"

/** The radios a cell's wire form names (C3). */
enum class CellRadio(val word: String, val areaMax: Long, val cidMax: Long) {
    GSM("gsm", 65_533, 65_534),
    WCDMA("wcdma", 65_533, 268_435_454),
    LTE("lte", 65_533, 268_435_454),
    NR("nr", 16_777_213, 68_719_476_734),
    ;

    companion object {
        fun of(word: String): CellRadio? = entries.firstOrNull { it.word == word }
    }
}

/** A cell's identity as the wire form spells it: MCC and MNC as the network gives them. */
data class CellWire(val radio: CellRadio, val mcc: String, val mnc: String, val area: Long, val cid: Long) {
    /** `"<radio>:<mcc>:<mnc>:<area>:<cid>"`. */
    val text: String get() = "${radio.word}:$mcc:$mnc:$area:$cid"

    /** The cell's id (`radio|mcc|mnc|area|cid`, MCC and MNC as numbers - C7's canonical form; code QA L3): the key of
     * own and Apple rows alike, so an LTE and a GSM cell with the same numbers are two rows, and two spellings of one
     * MNC one. */
    val id: String get() = "${radio.word}|${mcc.toInt()}|${mnc.toInt()}|$area|$cid"

    /** One cell however its MNC is spelled (C7's canonical form, the server's cache key). */
    val canonical: String get() = "${radio.word}:${mcc.toInt()}:${mnc.toInt()}:$area:$cid"
}

private fun digitsOnly(text: String) = text.isNotEmpty() && text.all { it in '0'..'9' }

/** A decimal without a sign, a space or a leading zero, at most 20 digits. */
private fun plainNumber(text: String): Long? =
    if (!digitsOnly(text) || text.length > 20 || text.startsWith("0")) null else text.toLongOrNull()

/** C3: [text] as a cell's wire form, or `null` - the server's `cells::Cell::parse`, row for row (`cell_forms`). */
fun parseCellWire(text: String): CellWire? {
    val parts = text.split(":")
    if (parts.size != 5) return null
    val radio = CellRadio.of(parts[0]) ?: return null
    val (mcc, mnc) = parts[1] to parts[2]
    if (mcc.length != 3 || !digitsOnly(mcc) || mcc == "000" || mnc.length !in 2..3 || !digitsOnly(mnc)) return null
    val area = plainNumber(parts[3])?.takeIf { it in 1..radio.areaMax } ?: return null
    val cid = plainNumber(parts[4])?.takeIf { it in 1..radio.cidMax } ?: return null
    return CellWire(radio, mcc, mnc, area, cid)
}

/** One cell as the telephony read gave it (C1): the glue's `CellInfo` without Android types. [dbm] `null` when
 * unavailable; [area]/[cid] negative when unavailable. */
data class CellInput(
    val radio: CellRadio?,
    val mcc: String?,
    val mnc: String?,
    val area: Long,
    val cid: Long,
    val registered: Boolean,
    val dbm: Int?,
    val timestampMs: Long,
)

/** A usable cell of the read: its wire form, whether it is registered, its signal and time (elapsed). */
data class CellRead(val wire: CellWire, val registered: Boolean, val dbm: Int?, val timestampMs: Long)

/** What a read gave, with its counts for the log (counts only, never a cell). */
data class CellReading(val cells: List<CellRead>, val read: Int, val registered: Int, val nr: Int, val stale: Int)

/**
 * C2: the usable cells of [inputs] - a full identity (the wire form's ranges: UNAVAILABLE, null MCC or MNC, 0 and the
 * placeholder fills out; an NSA NR leg without NCI or TAC out), not older than [CELL_READ_MAX_AGE_MS] before
 * [startElapsedMs]; registered first, then by signal (an unavailable one weakest); one per canonical form (a cell on
 * both slots, two spellings of one MNC); at most [CELLS_MAX].
 */
fun cellReads(inputs: List<CellInput>, startElapsedMs: Long): CellReading {
    var stale = 0
    val usable = inputs.mapNotNull { c ->
        val radio = c.radio ?: return@mapNotNull null
        val wire = parseCellWire("${radio.word}:${c.mcc ?: return@mapNotNull null}:${c.mnc ?: return@mapNotNull null}:${c.area}:${c.cid}")
            ?: return@mapNotNull null
        if (c.timestampMs < startElapsedMs - CELL_READ_MAX_AGE_MS) {
            stale++
            return@mapNotNull null
        }
        CellRead(wire, c.registered, c.dbm, c.timestampMs)
    }
    val cells = usable
        .sortedWith(compareByDescending<CellRead> { it.registered }.thenByDescending { it.dbm ?: Int.MIN_VALUE })
        .distinctBy { it.wire.canonical }
        .take(CELLS_MAX)
    return CellReading(cells, inputs.size, cells.count { it.registered }, cells.count { it.wire.radio == CellRadio.NR }, stale)
}

/** A9 for cells (C7): a live Apple cell row - a position, not blocked, within its 90 days (a clock set back: not). */
fun PlaceRow.appleCellLive(nowMin: Int): Boolean =
    kind == RowKind.APPLE_CELL && blockedUntilMin == 0 && lastTeachMin > nowMin && lastTeachMin - nowMin <= APPLE_CELL_ROW_MIN + 24 * 60

/** An own cell row that gives a position: not blocked, taught on [CELL_OCCASIONS] occasions (D10). */
fun PlaceRow.cellTrusted(nowMin: Int): Boolean = kind == RowKind.CELL && !blocked(nowMin) && occasions >= CELL_OCCASIONS

/**
 * C7 (A9's rule for cells): the row that gives a cell's position, and whether it is Apple's - a trusted own row (an
 * untrusted one doesn't block Apple's), a live Apple row, or `null`. Both: the smaller range when they agree (centres
 * within both ranges; a tie: own), else own.
 */
fun chooseCellRow(own: PlaceRow?, apple: PlaceRow?, nowMin: Int): Pair<PlaceRow, Boolean>? {
    val o = own?.takeIf { it.cellTrusted(nowMin) }
    val a = apple?.takeIf { it.appleCellLive(nowMin) }
    return when {
        o != null && a != null ->
            if (a.rangeM < o.rangeM && distanceM(o.lat, o.lng, a.lat, a.lng) <= o.rangeM + a.rangeM) a to true else o to false
        o != null -> o to false
        a != null -> a to true
        else -> null
    }
}

/** Each heard cell's chosen row ([chooseCellRow]). */
private fun positioned(cells: List<Heard>, rows: List<PlaceRow>, nowMin: Int): List<Pair<Heard, Pair<PlaceRow, Boolean>>> {
    val own = rows.filter { it.kind == RowKind.CELL }.associateBy { it.key }
    val apple = rows.filter { it.kind == RowKind.APPLE_CELL }.associateBy { it.key }
    return cells.filter { it.kind == RowKind.CELL }.distinctBy { it.key }
        .mapNotNull { h -> chooseCellRow(own[h.key], apple[h.key], nowMin)?.let { h to it } }
}

private fun median(values: List<Double>): Double {
    val s = values.sorted()
    return if (s.size % 2 == 1) s[s.size / 2] else (s[s.size / 2 - 1] + s[s.size / 2]) / 2
}

/**
 * C8: the cell fix from every read cell with a chosen row ([chooseCellRow]), registered or not. Three or more: a cell
 * further than its range from the per-axis median of the positions is dropped (GrapheneOS's reference); two must lie
 * within both ranges of each other, else none. The position weighted by `1 / range^2`; the accuracy
 * `max(smallest used range, weighted RMS distance from the fix)`, at least [CELL_RANGE_MIN_M], none over
 * [CELL_FIX_MAX_M]; the oldest used cell's time; `vibb_apple_cell` when any used position is Apple's. Never fresh.
 */
fun cellFix(cells: List<Heard>, rows: List<PlaceRow>, nowMin: Int): LearnedFix? {
    val all = positioned(cells, rows, nowMin)
    val used = when {
        all.isEmpty() -> return null
        all.size == 1 -> all
        all.size == 2 -> {
            val (a, b) = all.map { it.second.first }
            val d = distanceM(a.lat, a.lng, b.lat, b.lng)
            if (d <= a.rangeM && d <= b.rangeM) all else return null
        }
        else -> {
            val refLat = median(all.map { it.second.first.lat })
            val refLng = median(all.map { it.second.first.lng })
            all.filter { (_, chosen) -> distanceM(chosen.first.lat, chosen.first.lng, refLat, refLng) <= chosen.first.rangeM }
        }
    }
    if (used.isEmpty()) return null
    val weights = used.map { 1.0 / (it.second.first.rangeM.toDouble() * it.second.first.rangeM) }
    val total = weights.sum()
    val lat = used.indices.sumOf { used[it].second.first.lat * weights[it] } / total
    val lng = used.indices.sumOf { used[it].second.first.lng * weights[it] } / total
    val rms = sqrt(used.indices.sumOf { i -> weights[i] * distanceM(lat, lng, used[i].second.first.lat, used[i].second.first.lng).let { it * it } } / total)
    val accuracy = maxOf(used.minOf { it.second.first.rangeM.toDouble() }, rms, CELL_RANGE_MIN_M.toDouble())
    if (accuracy > CELL_FIX_MAX_M) return null
    val apple = used.count { it.second.second }
    val source = when (apple) {
        0 -> PlaceSource.OWN
        used.size -> PlaceSource.APPLE
        else -> PlaceSource.MIXED
    }
    return LearnedFix(
        lat, lng, accuracy, used.size, used.minOf { it.first.elapsedMs }, false, used.map { it.first.key }.toSet(),
        if (apple > 0) PROVIDER_APPLE_CELL else PROVIDER_CELL, source,
    )
}

/** C8's veto (D10): a registered cell with a chosen row (own trusted on two occasions, or live Apple) more than its range
 * + [CELL_VETO_SLACK_M] from [fix] rejects it. */
fun cellVetoes(cells: List<Heard>, rows: List<PlaceRow>, fix: LearnedFix, nowMin: Int): Boolean {
    val own = rows.filter { it.kind == RowKind.CELL }.associateBy { it.key }
    val apple = rows.filter { it.kind == RowKind.APPLE_CELL }.associateBy { it.key }
    return cells.filter { it.kind == RowKind.CELL && it.registered }.any { h ->
        val row = own[h.key]?.takeIf { it.trusted(nowMin) } ?: apple[h.key]?.takeIf { it.appleCellLive(nowMin) } ?: return@any false
        distanceM(row.lat, row.lng, fix.lat, fix.lng) > row.rangeM + CELL_VETO_SLACK_M
    }
}

/**
 * C4: the cells to ask for from [cells] (the read's usable ones, in the read's order): none when
 * [CELLS_POSITIONED_ENOUGH] of them already have a position; else each with no trusted own row, no live Apple cell row
 * and no negative or tombstone, at most [CELLS_MAX] - their wire forms.
 */
fun cellAsk(cells: List<Heard>, rows: List<PlaceRow>, nowMin: Int): List<String> {
    val usable = cells.filter { it.kind == RowKind.CELL && it.wire.isNotEmpty() }
    if (positioned(usable, rows, nowMin).size >= CELLS_POSITIONED_ENOUGH) return emptyList()
    val own = rows.filter { it.kind == RowKind.CELL }.associateBy { it.key }
    val apple = rows.filter { it.kind == RowKind.APPLE_CELL }.associateBy { it.key }
    return usable.asSequence()
        .filter { own[it.key]?.cellTrusted(nowMin) != true }
        .filter { h -> apple[h.key]?.let { it.appleCellLive(nowMin) || it.blocked(nowMin) } != true }
        .map { it.wire }
        .distinct()
        .take(CELLS_MAX)
        .toList()
}

/** Code QA L5: the cells are asked only where their fix may be reported - never while the phone is still
 * ([appleStillMotion]: C9.2 withholds the cell fix then), unless it is the parent's `locate`. */
fun cellAskAllowed(still: Boolean, locate: Boolean): Boolean = locate || !still

/**
 * C4 (code QA M3): the cells' ask ([cells], `null` when none is wanted) started apart in [scope], then the Wi-Fi ask
 * ([wifi]) run - its answer never waits for the cells' fixed answer time. The cells' [Deferred] is awaited only before
 * the cell fix, and only when nothing finer was kept.
 */
suspend fun <T> askBeside(scope: CoroutineScope, cells: (suspend () -> Boolean)?, wifi: suspend () -> T): Pair<T, Deferred<Boolean>?> {
    val started = cells?.let { ask -> scope.async { ask() } }
    return wifi() to started
}

/** `POST /api/devices/wifi-lookup`'s cells body (C5). */
@Serializable
data class WifiLookupCellsRequest(val cells: List<String>)

/** One row of a cells 200: the asked string and Apple's position - no age (C5). */
@Serializable
data class AppleCell(val cell: String, val lat: Double, val lng: Double, val accM: Int)

@Serializable
data class CellLookupAnswer(val cells: List<AppleCell>, val unknown: List<String> = emptyList())

/** What the phone does with a cells answer (C5): only the cell wait is set, but a 409 is the one switch's. */
sealed interface CellStep {
    data class Use(val cells: List<AppleCell>, val unknown: List<String>) : CellStep

    /** No cell ask for [ms]. */
    data class Wait(val ms: Long) : CellStep

    /** No ask of either kind until a policy says on. */
    data object Off : CellStep
}

@Serializable
private data class CellRefusal(val retryAfterS: Long? = null)

/** C5: the phone's action for a cells answer's [status] (0: a transport failure or the timeout) and [body]: 200 the rows;
 * 409 off; 503 or 429 with `retry_after_s` that wait (at most a day); anything else 15 min. */
fun appleCellStep(status: Int, body: String?): CellStep {
    fun retry() = runCatching { ServerJson.decodeFromString(CellRefusal.serializer(), body!!).retryAfterS }.getOrNull()
        ?.takeIf { it > 0 }?.let { minOf(it * 1000L, APPLE_WAIT_MAX_MS) }
    return when (status) {
        200 -> runCatching { ServerJson.decodeFromString(CellLookupAnswer.serializer(), body!!) }
            .map { CellStep.Use(it.cells, it.unknown) as CellStep }
            .getOrDefault(CellStep.Wait(APPLE_WAIT_MS))
        409 -> CellStep.Off
        429, 503 -> CellStep.Wait(retry() ?: APPLE_WAIT_MS)
        else -> CellStep.Wait(APPLE_WAIT_MS)
    }
}

/**
 * C7: a cells answer into the table at [nowMin]: each placed cell a kind-5 row keyed by step 1's cell key, its range
 * `max(500 m, acc_m)`, live [APPLE_CELL_ROW_MIN] (no age in the answer); each unknown cell a negative blocked
 * [APPLE_CELL_NEGATIVE_MIN]. A string that isn't a wire form, a position out of range or an accuracy past
 * [APPLE_CELL_ACC_MAX_M] is left out; neither replaces a row still blocking. Then [reconcileAppleCells] and [prune].
 */
fun storeAppleCells(rows: List<PlaceRow>, cells: List<AppleCell>, unknown: List<String>, keys: PlaceKeys, nowMin: Int): List<PlaceRow> {
    val byId = LinkedHashMap<String, PlaceRow>()
    rows.forEach { byId[it.id] = it }
    fun put(row: PlaceRow) {
        if (byId[row.id]?.blocked(nowMin) != true) byId[row.id] = row
    }
    for (c in cells) {
        val wire = parseCellWire(c.cell) ?: continue
        if (c.lat !in -90.0..90.0 || c.lng !in -180.0..180.0 || c.accM !in 1..APPLE_CELL_ACC_MAX_M) continue
        put(
            PlaceRow(
                kind = RowKind.APPLE_CELL, key = keys.cell(wire.id), band = Band.CELL, lat = c.lat, lng = c.lng,
                rangeM = maxOf(CELL_RANGE_MIN_M, c.accM.toFloat()), lastTeachMin = nowMin + APPLE_CELL_ROW_MIN, lastSeenMin = nowMin,
            ),
        )
    }
    for (raw in unknown) {
        val wire = parseCellWire(raw) ?: continue
        put(PlaceRow(kind = RowKind.APPLE_CELL, key = keys.cell(wire.id), band = Band.CELL, lastSeenMin = nowMin, blockedUntilMin = nowMin + APPLE_CELL_NEGATIVE_MIN))
    }
    return prune(reconcileAppleCells(byId.values.toList(), nowMin), nowMin)
}

private fun cellTombstone(row: PlaceRow, nowMin: Int) = row.copy(lat = 0.0, lng = 0.0, blockedUntilMin = nowMin + APPLE_ROW_MIN)

/** C7: a live Apple cell row that disagrees with a trusted own row of its cell (centres further apart than both ranges)
 * becomes a 90-day tombstone. */
fun reconcileAppleCells(rows: List<PlaceRow>, nowMin: Int): List<PlaceRow> {
    val own = rows.filter { it.cellTrusted(nowMin) }.associateBy { it.key }
    return rows.map { a ->
        val o = own[a.key]
        if (o != null && a.appleCellLive(nowMin) && distanceM(o.lat, o.lng, a.lat, a.lng) > o.rangeM + a.rangeM) cellTombstone(a, nowMin) else a
    }
}

/** C7: an own teach that puts a cell beyond its live Apple row's reach (D10's 20 km move) tombstones that row. */
fun appleCellTaught(rows: List<PlaceRow>, sightings: List<Sighting>, nowMin: Int): List<PlaceRow> {
    val taught = sightings.filter { it.heard.kind == RowKind.CELL }.associateBy { it.heard.key }
    if (taught.isEmpty()) return rows
    return rows.map { a ->
        val s = taught[a.key]
        if (s != null && a.appleCellLive(nowMin) && distanceM(a.lat, a.lng, s.lat, s.lng) - s.accEff > Band.CELL.reachM) cellTombstone(a, nowMin) else a
    }
}

/** C11 (D16's way for cells): the cell fix the table as it was gave for the cells paired with a GPS fix, by source. */
fun DayCounts.looCell(source: PlaceSource, hit: Boolean, errorM: Double): DayCounts {
    val h = if (hit) 1 else 0
    val e = errorM.toLong().coerceAtLeast(0)
    return when (source) {
        PlaceSource.OWN -> copy(cellHitOwn = cellHitOwn + h, cellNOwn = cellNOwn + 1, cellErrOwn = cellErrOwn + e)
        PlaceSource.APPLE -> copy(cellHitApple = cellHitApple + h, cellNApple = cellNApple + 1, cellErrApple = cellErrApple + e)
        PlaceSource.MIXED -> copy(cellHitMixed = cellHitMixed + h, cellNMixed = cellNMixed + 1, cellErrMixed = cellErrMixed + e)
    }
}

/** C11's log line: `loo cell own=3/3 apple=12/14 mixed=1/1 err own=800 apple=600 mixed=700`. */
fun cellCountsLine(days: List<DayCounts>): String {
    fun mean(err: Long, n: Int) = if (n == 0) "-" else (err / n).toString()
    val nOwn = days.sumOf { it.cellNOwn }
    val nApple = days.sumOf { it.cellNApple }
    val nMixed = days.sumOf { it.cellNMixed }
    return "loo cell own=${days.sumOf { it.cellHitOwn }}/$nOwn apple=${days.sumOf { it.cellHitApple }}/$nApple " +
        "mixed=${days.sumOf { it.cellHitMixed }}/$nMixed err own=${mean(days.sumOf { it.cellErrOwn }, nOwn)} " +
        "apple=${mean(days.sumOf { it.cellErrApple }, nApple)} mixed=${mean(days.sumOf { it.cellErrMixed }, nMixed)}"
}
