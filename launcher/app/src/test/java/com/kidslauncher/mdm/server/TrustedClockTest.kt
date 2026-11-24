package com.kidslauncher.mdm.server

import com.kidslauncher.mdm.timerules.Clocks
import com.kidslauncher.mdm.timerules.ScreenTimeLedger
import com.kidslauncher.mdm.timerules.accrue
import com.kidslauncher.mdm.timerules.observe
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.longOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** The decisions' clock (time rules keep their own clock): every row of the launcher's own `clock_rules.json`, and the
 * screen-time ledger on it - a reboot offline, a clock moved before it, the reset taken back. */
class TrustedClockTest {
    private val rules = Json.parseToJsonElement(javaClass.classLoader!!.getResource("clock_rules.json")!!.readText()).jsonObject
    private val lenient = Json { ignoreUnknownKeys = true }

    private fun rows(name: String) = rules[name]!!.jsonArray.map { it.jsonObject }
    private fun JsonObject.long(key: String) = this[key]!!.jsonPrimitive.long
    private fun JsonObject.longOrNull(key: String) = (this[key] as? JsonElement)?.takeIf { it != JsonNull }?.jsonPrimitive?.longOrNull
    private fun JsonObject.int(key: String) = this[key]!!.jsonPrimitive.int
    private fun JsonObject.string(key: String) = this[key]!!.jsonPrimitive.content
    private fun state(row: JsonObject) = lenient.decodeFromJsonElement(TrustedClockState.serializer(), row["state"]!!)

    @Test
    fun `a server answer anchors only when it can be trusted`() {
        for (row in rows("server_anchor")) {
            val anchor = serverAnchor(row.longOrNull("date"), row.long("e0"), row.long("e1"), 7, row["trusted"]!!.jsonPrimitive.boolean, row["off"]!!.jsonPrimitive.boolean)
            val want = row["anchor"]!!.takeIf { it != JsonNull }?.jsonObject
            if (want == null) {
                assertNull(row.string("note"), anchor)
            } else {
                assertEquals(row.string("note"), ClockAnchor("server", want.long("ms"), want.long("elapsed"), 7), anchor)
            }
        }
        for (row in rows("connections")) {
            assertEquals(row.toString(), row["anchors"]!!.jsonPrimitive.boolean,
                anchorConnection(PinConnection.valueOf(row.string("connection")), row["debug"]!!.jsonPrimitive.boolean, row["enforced"]!!.jsonPrimitive.boolean))
        }
    }

    @Test
    fun `the time within a boot and across a reboot`() {
        for (row in rows("read")) {
            val read = readClock(state(row), row.long("wall"), row.long("elapsed"), row.int("boot"))
            val note = row.string("note")
            assertEquals(note, row.long("ms"), read.ms)
            assertEquals(note, row.string("trust"), read.trust.wire)
            assertEquals(note, row["carried"]!!.jsonPrimitive.boolean, read.state != null)
            read.state?.let { assertEquals(note, ClockAnchor("carried", read.ms, row.long("elapsed"), row.int("boot")), it.anchor) }
        }
    }

    @Test
    fun `a stronger or newer anchor replaces the one in force`() {
        for (row in rows("take")) {
            val anchor = lenient.decodeFromJsonElement(ClockAnchor.serializer(), row["anchor"]!!)
            val taken = takeAnchor(state(row), anchor, row.long("wall"), row.long("elapsed"), row.int("boot"))
            val note = row.string("note")
            assertEquals(note, row.string("inForce"), taken.state.anchor!!.kind)
            assertEquals(note, row.long("lastStrong"), taken.state.lastStrongMs)
            assertNotNull(note, taken.state.checkpointMs)
        }
    }

    @Test
    fun `a clock that lost its time takes the boot's first forward set`() {
        for (row in rows("time_set")) {
            val next = timeSetAnchor(state(row), row.long("wall"), row.long("elapsed"), row.int("boot"))
            val want = row.longOrNull("anchor")
            assertEquals(row.string("note"), want, next?.anchor?.ms)
            next?.let {
                assertEquals(row.string("note"), "carried", it.anchor!!.kind)
                assertEquals(row.string("note"), false, it.carriedOpen)
                assertNull("only the first set", timeSetAnchor(it, row.long("wall") + 3_600_000, row.long("elapsed") + 1, row.int("boot")))
            }
        }
        // The read that carries over a reboot opens it only when the wall clock is behind the checkpoint.
        val before = TrustedClockState(ClockAnchor("server", 1_764_000_000_000, 5_000_000, 7), checkpointMs = 1_764_000_000_000)
        assertEquals(true, readClock(before, 1_000_000_000_000, 9_000, 8).state!!.carriedOpen)
        assertEquals(false, readClock(before, 1_764_000_100_000, 9_000, 8).state!!.carriedOpen)
    }

    @Test
    fun `the alarm is on elapsed realtime and never loops`() {
        for (row in rows("alarm")) {
            val edges = row["edges"]!!.jsonArray.map { AlarmEdge(it.jsonObject.long("edge"), it.jsonObject.long("now")) }
            assertEquals(row.string("note"), row.longOrNull("deadline"), alarmDeadline(edges, row.long("elapsed")))
        }
    }

    @Test
    fun `the time since the last check is whole hours`() {
        for (row in rows("unanchored")) {
            assertEquals(row.toString(), row.longOrNull("ms"), unanchoredMs(TrustedClockState(lastStrongMs = row.longOrNull("lastStrong")), row.long("now")))
        }
    }

    @Test
    fun `ten small steps of the wall clock move nothing under any trust`() {
        for (kind in listOf("server", "gnss", "carried")) {
            val state = TrustedClockState(anchor = ClockAnchor(kind, 1_000_000_000L, 0, 4), checkpointMs = 1_000_000_000L)
            for (step in 1..10) {
                val read = readClock(state, 1_000_000_000L + step * 4 * 60_000L, step * 1_000L, 4)
                assertEquals(1_000_000_000L + step * 1_000L, read.ms)
            }
        }
    }

    @Test
    fun `a wall-clock change over five minutes asks for a sync`() {
        assertEquals(false, skewChangeAsksSync(0, 300_000))
        assertEquals(true, skewChangeAsksSync(0, 300_001))
        assertEquals(true, skewChangeAsksSync(93_600_000, 0))
    }

    // --- The ledger on the decisions' clock ---

    private val zone = ZoneId.of("Europe/Oslo")
    private fun at(day: String, hour: Int) = LocalDate.parse(day).atTime(hour, 0).atZone(zone).toInstant().toEpochMilli()

    @Test
    fun `a reboot offline after a clock moved ahead resets the day, and the next check takes it back`() {
        // Monday 20:00, 50 minutes used.
        val used = observe(ScreenTimeLedger(), Clocks(at("2026-11-23", 20), 1_000, 7, ClockTrust.SERVER), zone).ledger
            .copy(usedMs = 50 * 60_000L)
        // Rebooted offline with the clock 26 hours ahead (carried): Tuesday, a fresh day - never a phone locked out.
        val carried = observe(used, Clocks(at("2026-11-24", 22), 5_000, 8, ClockTrust.CARRIED), zone)
        assertEquals("2026-11-24", carried.ledger.day)
        assertEquals(0L, carried.ledger.usedMs)
        assertEquals("2026-11-23", carried.ledger.previous?.day)
        // Ten minutes counted on the carried day.
        val counted = accrue(carried.ledger, 5_000, Clocks(at("2026-11-24", 22) + 600_000, 605_000, 8, ClockTrust.CARRIED), zone)
        assertEquals(10 * 60_000L, counted.usedMs)
        // The server's time says Monday 20:12: Monday comes back with its 50 minutes and the 10 since.
        val back = observe(counted, Clocks(at("2026-11-23", 20) + 720_000, 700_000, 8, ClockTrust.SERVER), zone)
        assertEquals("2026-11-23", back.ledger.day)
        assertEquals(60 * 60_000L, back.ledger.usedMs)
        assertNull(back.ledger.previous)
        assertEquals(false, back.newDayByClock)
    }

    @Test
    fun `a carried reset the check agrees with is final`() {
        val used = ScreenTimeLedger(day = "2026-11-23", usedMs = 50 * 60_000L, anchorWallMs = at("2026-11-23", 20), anchorElapsedMs = 1_000, bootCount = 7)
        val carried = observe(used, Clocks(at("2026-11-24", 7), 5_000, 8, ClockTrust.CARRIED), zone).ledger
        val checked = observe(carried, Clocks(at("2026-11-24", 7) + 60_000, 65_000, 8, ClockTrust.GNSS), zone).ledger
        assertEquals("2026-11-24", checked.day)
        assertEquals(0L, checked.usedMs)
        assertNull(checked.previous)
    }

    @Test
    fun `within a boot the wall clock moves no day on the decisions' clock`() {
        // The ledger takes the trusted time as given: a day ahead only when the trusted time is.
        val today = ScreenTimeLedger(day = "2026-11-23", usedMs = 30 * 60_000L)
        val same = observe(today, Clocks(at("2026-11-23", 23), 9_000, 7, ClockTrust.SERVER), zone).ledger
        assertEquals("2026-11-23", same.day)
        assertEquals(30 * 60_000L, same.usedMs)
        val tomorrow = observe(same, Clocks(at("2026-11-24", 0) + 60_000, 3_669_000, 7, ClockTrust.SERVER), zone)
        assertEquals("2026-11-24", tomorrow.ledger.day)
        assertNull("a reset on a checked time keeps nothing to take back", tomorrow.ledger.previous)
        assertEquals(Instant.ofEpochMilli(at("2026-11-24", 0) + 60_000), Instant.ofEpochMilli(tomorrow.trustedNowMs))
    }
}
