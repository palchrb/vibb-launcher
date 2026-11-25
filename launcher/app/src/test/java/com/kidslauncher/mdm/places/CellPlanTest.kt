package com.kidslauncher.mdm.places

import com.kidslauncher.mdm.zones.distanceM
import java.io.File
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Design 125b on the phone: the cell read (C2), the ask (C4), the Apple cell rows (C7), the cell fix (C8), the
 * leave-one-out counts (C11) and the cells-only flow of an attempt with no scan view (QA H1), as pure rules. */
class CellPlanTest {
    private val keys = PlaceKeys("test-key-not-a-secret".toByteArray())
    private val lat0 = 59.91
    private val lng0 = 10.75
    private fun north(m: Double) = lat0 + m / 111_195.08
    private val day = 1440
    private val now = 30_000_000

    private fun input(radio: CellRadio?, mnc: String? = "01", area: Long = 4711, cid: Long = 7, registered: Boolean = false, dbm: Int? = -90, at: Long = 100_000) =
        CellInput(radio, "001", mnc, area, cid, registered, dbm, at)

    private fun heard(wire: String, registered: Boolean = false, at: Long = 100_000): Heard {
        val w = parseCellWire(wire)!!
        val key = keys.cell(w.id)
        return Heard(key, key.take(16), key.take(16), Band.CELL, 0, at, kind = RowKind.CELL, wire = w.text, registered = registered)
    }

    private fun own(h: Heard, m: Double, range: Float = 1000f, occasions: Int = 3) =
        PlaceRow(kind = RowKind.CELL, key = h.key, band = Band.CELL, lat = north(m), lng = lng0, rangeM = range, occasions = occasions, lastSeenMin = now)

    private fun apple(h: Heard, m: Double, range: Float = 1000f) =
        PlaceRow(kind = RowKind.APPLE_CELL, key = h.key, band = Band.CELL, lat = north(m), lng = lng0, rangeM = range, lastTeachMin = now + APPLE_CELL_ROW_MIN, lastSeenMin = now)

    @Test
    fun `C2 - the read keeps full identities, registered first, one per cell, at most twelve`() {
        val start = 100_000L
        val reading = cellReads(
            listOf(
                input(CellRadio.LTE, cid = 1, dbm = -100),
                input(CellRadio.LTE, cid = 2, dbm = null),
                input(CellRadio.LTE, cid = 3, dbm = -80),
                input(CellRadio.LTE, cid = 4, registered = true, dbm = -110),
                input(CellRadio.LTE, cid = -1),
                input(CellRadio.LTE, mnc = null, cid = 5),
                input(CellRadio.NR, area = -1, cid = -1),
                input(CellRadio.LTE, cid = 268_435_455),
                input(CellRadio.LTE, area = 0, cid = 6),
                input(CellRadio.LTE, mnc = "001", cid = 3),
                input(null, cid = 8),
                input(CellRadio.LTE, cid = 9, at = start - 61_000),
            ),
            start,
        )
        assertEquals(listOf(4L, 3L, 1L, 2L), reading.cells.map { it.wire.cid })
        assertEquals(12, reading.read)
        assertEquals(1, reading.registered)
        assertEquals(1, reading.stale)
        // One cell on two slots, or two spellings of one MNC: once (cid 3 above).
        val many = cellReads((1..13L).map { input(CellRadio.GSM, area = 1, cid = it) }, start)
        assertEquals(CELLS_MAX, many.cells.size)
    }

    @Test
    fun `C3 - the wire form and its canonical spelling`() {
        val w = parseCellWire("lte:001:01:4711:12345678")!!
        assertEquals("lte|1|1|4711|12345678", w.id)
        assertEquals("lte:1:1:4711:12345678", w.canonical)
        assertEquals(w.canonical, parseCellWire("lte:001:001:4711:12345678")!!.canonical)
        assertNull(parseCellWire("lte:001:01:4711:268435455"))
        assertNotNull(parseCellWire("nr:001:01:16777213:68719476734"))
    }

    @Test
    fun `C7 - the cell key has the radio (code QA L3), and one key for two spellings`() {
        val lte = parseCellWire("lte:001:01:4711:1234")!!
        val gsm = parseCellWire("gsm:001:01:4711:1234")!!
        assertFalse(lte.id == gsm.id)
        assertFalse(keys.cell(lte.id) == keys.cell(gsm.id))
        assertEquals(lte.id, parseCellWire("lte:001:001:4711:1234")!!.id)
        // Apple's rows for the two are two rows; a heard GSM cell meets only its own.
        val stored = storeAppleCells(emptyList(), listOf(AppleCell("lte:001:01:4711:1234", lat0, lng0, 800)), listOf("gsm:001:01:4711:1234"), keys, now)
        assertEquals(2, stored.size)
        val g = heard("gsm:001:01:4711:1234")
        assertNull(cellFix(listOf(g), stored, now))
        assertNotNull(cellFix(listOf(heard("lte:001:01:4711:1234")), stored, now))
    }

    @Test
    fun `C4 - no cells asked while still, but for a locate (code QA L5)`() {
        assertTrue(cellAskAllowed(still = false, locate = false))
        assertFalse(cellAskAllowed(still = true, locate = false))
        assertTrue(cellAskAllowed(still = true, locate = true))
    }

    @Test
    fun `C4 - the Wi-Fi answer never waits for the cells' (code QA M3)`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val cellsAnswer = CompletableDeferred<Boolean>()
        // The cells' ask held (the server's fixed answer time): the Wi-Fi ask's answer comes at once.
        val (wifi, cells) = withTimeout(2_000) { askBeside(scope, { cellsAnswer.await() }) { "wifi" } }
        assertEquals("wifi", wifi)
        assertFalse(cells!!.isCompleted)
        // Awaited before the cell fix, it answers once the server does.
        cellsAnswer.complete(true)
        assertTrue(withTimeout(2_000) { cells.await() })
        // No cells wanted: none started.
        assertNull(askBeside(scope, null) { 1 }.second)
        scope.cancel()
    }

    @Test
    fun `C7 - own against Apple per cell`() {
        val h = heard("lte:001:01:4711:7")
        // An untrusted own row doesn't block Apple's.
        assertEquals(true, chooseCellRow(own(h, 0.0, occasions = 2), apple(h, 100.0), now)!!.second)
        // Agreeing: the smaller range; a tie own.
        assertEquals(true, chooseCellRow(own(h, 0.0, 2000f), apple(h, 500.0, 800f), now)!!.second)
        assertEquals(false, chooseCellRow(own(h, 0.0, 800f), apple(h, 500.0, 800f), now)!!.second)
        // Disagreeing: own, and the Apple row becomes a tombstone.
        assertEquals(false, chooseCellRow(own(h, 0.0, 800f), apple(h, 5000.0, 600f), now)!!.second)
        val reconciled = reconcileAppleCells(listOf(own(h, 0.0, 800f), apple(h, 5000.0, 600f)), now)
        assertTrue(reconciled.single { it.kind == RowKind.APPLE_CELL }.blocked(now))
        // An own teach 25 km off tombstones the Apple row (D10's move).
        val taught = appleCellTaught(listOf(apple(h, 0.0)), listOf(Sighting(h, north(25_000.0), lng0, 10.0)), now)
        assertTrue(taught.single().blocked(now))
        assertNull(chooseCellRow(null, null, now))
    }

    @Test
    fun `C8 - the cell fix`() {
        val a = heard("lte:001:01:4711:1", registered = true, at = 5_000)
        val b = heard("lte:001:01:4711:2", at = 4_000)
        val c = heard("lte:001:01:4711:3", at = 6_000)
        // One cell: its position and range, never fresh.
        val one = cellFix(listOf(a), listOf(own(a, 0.0, 700f)), now)!!
        assertEquals(700.0, one.accuracyM, 0.0)
        assertEquals(PROVIDER_CELL, one.provider)
        assertFalse(one.fresh)
        // Two agreeing: the 1/r^2 mean, the oldest time; an Apple position makes it vibb_apple_cell.
        val two = cellFix(listOf(a, b), listOf(own(a, 0.0, 1000f), apple(b, 600.0, 1000f)), now)!!
        assertEquals(300.0, distanceM(lat0, lng0, two.lat, two.lng), 1.0)
        assertEquals(4_000L, two.elapsedMs)
        assertEquals(PROVIDER_APPLE_CELL, two.provider)
        assertEquals(PlaceSource.MIXED, two.source)
        assertTrue(two.accuracyM >= 1000.0)
        // Two that disagree (two SIMs' registered cells 3 km apart): none.
        assertNull(cellFix(listOf(a, b), listOf(own(a, 0.0, 1000f), own(b, 3000.0, 1000f)), now))
        // Three with a median outlier: dropped.
        val three = cellFix(listOf(a, b, c), listOf(own(a, 0.0), own(b, 400.0), own(c, 9000.0)), now)!!
        assertEquals(2, three.networks)
        assertTrue(distanceM(lat0, lng0, three.lat, three.lng) < 500)
        // Over 5 km: none.
        assertNull(cellFix(listOf(a), listOf(own(a, 0.0, 6000f)), now))
        // The veto reads Apple rows too, for a registered cell.
        val far = LearnedFix(north(5000.0), lng0, 40.0, 3, 0, true, emptySet())
        assertTrue(cellVetoes(listOf(a), listOf(apple(a, 0.0)), far, now))
        assertFalse(cellVetoes(listOf(b), listOf(apple(b, 0.0)), far, now))
    }

    @Test
    fun `C4 - which cells are asked`() {
        val cells = (1..10).map { heard("lte:001:01:4711:$it") }
        // None known: all, in the read's order.
        assertEquals(cells.map { it.wire }, cellAsk(cells, emptyList(), now))
        // A trusted own row, a live Apple row and a negative leave theirs out; an untrusted own row doesn't.
        val rows = listOf(
            own(cells[0], 0.0),
            own(cells[1], 0.0, occasions = 2),
            apple(cells[2], 0.0),
            PlaceRow(kind = RowKind.APPLE_CELL, key = cells[3].key, lastSeenMin = now, blockedUntilMin = now + APPLE_CELL_NEGATIVE_MIN),
        )
        assertEquals(cells.drop(1).map { it.wire }.filter { it != cells[2].wire && it != cells[3].wire }, cellAsk(cells, rows, now))
        // Eight with a position: no ask.
        assertEquals(emptyList<String>(), cellAsk(cells, cells.take(8).map { own(it, 0.0) }, now))
    }

    @Test
    fun `C7 - the answer into the table, its caps and the switch off`() {
        val stored = storeAppleCells(
            emptyList(),
            listOf(AppleCell("lte:001:01:4711:7", lat0, lng0, 120), AppleCell("lte:001:01:4711:8", 95.0, lng0, 800), AppleCell("x", lat0, lng0, 800)),
            listOf("gsm:001:01:1001:5001", "nonsense"),
            keys,
            now,
        )
        assertEquals(2, stored.size)
        val row = stored.single { it.blockedUntilMin == 0 }
        assertEquals(CELL_RANGE_MIN_M, row.rangeM)
        assertTrue(row.appleCellLive(now) && row.appleCellLive(now + APPLE_CELL_ROW_MIN - 1) && !row.appleCellLive(now + APPLE_CELL_ROW_MIN))
        assertTrue(stored.single { it.blockedUntilMin > 0 }.blocked(now + APPLE_CELL_NEGATIVE_MIN - 1))
        assertEquals(emptyList<PlaceRow>(), dropApple(stored))
        val many = storeAppleCells(emptyList(), (1..APPLE_CELL_ROWS_MAX + 5).map { AppleCell("lte:001:01:4711:$it", lat0, lng0, 900) }, emptyList(), keys, now)
        assertEquals(APPLE_CELL_ROWS_MAX, many.size)
        // The table keeps kind 5 rows (D18's layout).
        assertEquals(stored, decodeTable(encodeTable(stored)))
    }

    @Test
    fun `QA H1 - with no scan view the cells alone are asked, and the answer gives vibb_apple_cell in the same attempt`() {
        val cells = listOf(heard("lte:001:01:4711:12345678", registered = true), heard("nr:001:01:1048577:4294967297"))
        val asked = cellAsk(cells, emptyList(), now)
        assertEquals(listOf("lte:001:01:4711:12345678", "nr:001:01:1048577:4294967297"), asked)
        val answer = """{"cells": [{"cell": "lte:001:01:4711:12345678", "lat": 59.91, "lng": 10.75, "acc_m": 800},
            {"cell": "nr:001:01:1048577:4294967297", "lat": 59.912, "lng": 10.751, "acc_m": 350}], "unknown": []}"""
        val step = appleCellStep(200, answer) as CellStep.Use
        val table = storeAppleCells(emptyList(), step.cells, step.unknown, keys, now)
        val fix = cellFix(cells, table, now)!!
        assertEquals(PROVIDER_APPLE_CELL, fix.provider)
        assertEquals(PlaceSource.APPLE, fix.source)
        // ... and the next attempt asks for nothing.
        assertEquals(emptyList<String>(), cellAsk(cells, table, now))
    }

    @Test
    fun `C11 - the cell leave-one-out counts and their line`() {
        val days = countsWith(emptyList(), 20000) { it.looCell(PlaceSource.APPLE, true, 600.0).looCell(PlaceSource.OWN, false, 1200.0) }
        assertEquals("loo cell own=0/1 apple=1/1 mixed=0/0 err own=1200 apple=600 mixed=-", cellCountsLine(days))
    }

    @Test
    fun `source - the cell read's glue, counts only in its logs`() {
        val glue = File("src/main/java/com/kidslauncher/mdm/places/WifiPlaces.kt").readText()
        assertTrue(glue.contains("MobileNetworkRuntime.activeSubscriptionIds(context)"))
        assertTrue(glue.contains("tm.createForSubscriptionId(sub).requestCellInfoUpdate("))
        // The cells' ask runs apart beside the Wi-Fi's (code QA M3), with or without a scan view, never while still but
        // for a locate (L5); awaited only before the cell fix, which reads the table again.
        assertTrue(glue.contains("askBeside(scope, if (askCells) ({ askAppleCells(app, store, s) }) else null)"))
        assertTrue(glue.contains("cellAskAllowed(appleStillMotion(ask.motion), plan == AttemptPlan.LOCATE)"))
        assertTrue(glue.contains("var result = kept ?: run {\n                    cellAsk.get()?.await()\n                    cellOnly("))
        assertTrue(glue.contains("val store = open(context) ?: return null\n        val fix = cellFix(snap.cells, store.rows, nowMin())"))
        assertFalse(Regex("""Log\.[a-z]\(LOG_TAG, "[^"]*\$\{?(wire|asking|cells)\b(?!\.size)""").containsMatchIn(glue))
        assertFalse(File("src/main/java/com/kidslauncher/mdm/places/CellPlan.kt").readText().contains("Log."))
    }
}
