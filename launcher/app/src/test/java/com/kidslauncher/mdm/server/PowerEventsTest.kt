package com.kidslauncher.mdm.server

import com.kidslauncher.mdm.server.dto.BatteryReport
import com.kidslauncher.mdm.server.dto.PowerOff
import com.kidslauncher.mdm.server.dto.PowerOn
import com.kidslauncher.mdm.server.dto.PowerReport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Design 38 §4: the phone's shutdown and start records - the restart flag, the boot record, the
 * kept shutdown resent with the next start (QA H1), the answer table, staleness, the prepared
 * client's key, the contract `power_report.json` (byte-identical with `server/testdata/`, which
 * the server's `power::a_launcher_report_raises_both` posts) and the wiring by source.
 */
class PowerEventsTest {
    private val contract = javaClass.classLoader!!.getResource("power_report.json")!!.readText()

    private val day = 24L * 60 * 60 * 1000
    private val now = 1_760_936_520_000L

    private fun off(atMs: Long = now - 3_600_000, boot: Int = 41) =
        PowerOff(atMs = atMs, bootCount = boot, battery = BatteryReport(3, charging = false, low = true), reboot = null)

    private fun on(atMs: Long = now - 60_000, boot: Int = 42) = PowerOn(atMs = atMs, bootCount = boot)

    @Test
    fun `the encoding is exactly the contract`() {
        val report = PowerReport(
            off = PowerOff(1_760_900_040_000, 41, BatteryReport(3, charging = false, low = true), reboot = false, reason = "userrequested"),
            on = PowerOn(1_760_936_520_000, 42, BatteryReport(11, charging = true, low = false)),
        )
        assertEquals(contract.trimEnd(), ServerJson.encodeToString(PowerReport.serializer(), report))
        // Unknowns are left out, never sent as null.
        assertEquals("""{"on":{"at_ms":5,"boot_count":1}}""", ServerJson.encodeToString(PowerReport.serializer(), PowerReport(on = PowerOn(5, 1))))
    }

    @Test
    fun `the contract is identical to the servers copy`() {
        // Gradle runs unit tests with the module directory (launcher/app/) as the working directory.
        val serverCopy = File("../../server/testdata/power_report.json")
        assertTrue("${serverCopy.absolutePath} not found", serverCopy.isFile)
        assertEquals("${serverCopy.path} differs from the launcher's copy", serverCopy.readText(), contract)
    }

    @Test
    fun `the restart flag is never false for unknown`() {
        assertEquals(true, rebootOf(userspaceOnly = true, requested = null))
        assertEquals(true, rebootOf(userspaceOnly = true, requested = "0userrequested"))
        assertEquals(true, rebootOf(userspaceOnly = false, requested = "1"))
        assertEquals(false, rebootOf(userspaceOnly = false, requested = "0userrequested"))
        for (unknown in listOf(null, "", "battery", " 1")) assertNull(unknown, rebootOf(false, unknown))
        assertEquals("userrequested", reasonOf("0userrequested"))
        assertEquals("battery", reasonOf("0battery"))
        assertEquals("thermal-shutdown", reasonOf("0thermal-shutdown"))
        for (none in listOf(null, "", "1", "0 ", "battery", "2battery")) assertNull(none, reasonOf(none))
        assertEquals(64, reasonOf("0" + "x".repeat(100))!!.length)
    }

    @Test
    fun `a boot is recorded only for a new boot after a known one with a known count`() {
        val b = BatteryReport(60, charging = true, low = false)
        assertEquals(PowerOn(now - 40_000, 42, b), bootRecord(true, true, 42, now, 40_000, b))
        assertNull("a restart of the process in the same boot", bootRecord(false, true, 42, now, 40_000, b))
        assertNull("the first start of this build", bootRecord(true, false, 42, now, 40_000, b))
        assertNull("an unknown count is new at every start", bootRecord(true, true, -1, now, 40_000, b))
        assertEquals(0, bootRecord(true, true, 0, now, 1, null)!!.bootCount)
    }

    @Test
    fun `the kept shutdown goes with the next start, an unsent one alone`() {
        assertNull(uploadBody(PowerRecord(), 42, now, 1_000))
        // Unsent alone: sent; sent alone: kept, not sent again.
        assertEquals(PowerReport(off = off()), uploadBody(PowerRecord(off = off()), 41, now, 1_000))
        assertNull(uploadBody(PowerRecord(off = off(), offSent = true), 41, now, 1_000))
        // A start with a kept shutdown of an earlier boot (sent or not): both.
        for (sent in listOf(true, false)) {
            assertEquals(PowerReport(off = off(), on = on()), uploadBody(PowerRecord(off = off(), offSent = sent, on = on()), 50, now, 1_000))
        }
        assertEquals(PowerReport(on = on()), uploadBody(PowerRecord(on = on()), 50, now, 1_000))
        // A shutdown of the start's own boot isn't paired: an unsent one alone, a sent one nothing.
        val sameBoot = off(boot = 42)
        assertEquals(PowerReport(off = sameBoot), uploadBody(PowerRecord(off = sameBoot, on = on()), 42, now, 1_000))
        assertNull(uploadBody(PowerRecord(off = sameBoot, offSent = true, on = on()), 42, now, 1_000))
        // The start's instant recomputed while it is still its boot (NTP moved the clock).
        assertEquals(now - 5_000, uploadBody(PowerRecord(on = on()), 42, now, 5_000)!!.on!!.atMs)
        assertEquals(on().atMs, uploadBody(PowerRecord(on = on()), 43, now, 5_000)!!.on!!.atMs)
    }

    @Test
    fun `the answer table is pinEventsLeaveQueue's`() {
        val record = PowerRecord(off = off(), on = on())
        val body = PowerReport(off = off(), on = on())
        for (keep in listOf(null, 401, 404, 408, 429, 500, 503)) assertEquals("$keep", record, afterAnswer(record, body, keep))
        for (ok in listOf(200, 204)) assertEquals("$ok", PowerRecord(), afterAnswer(record, body, ok))
        for (drop in listOf(400, 413, 422)) assertEquals("$drop", PowerRecord(), afterAnswer(record, body, drop))
        // A 2xx for the shutdown alone marks it sent, keeps it for the next start's body.
        val alone = PowerRecord(off = off())
        assertEquals(PowerRecord(off = off(), offSent = true), afterAnswer(alone, PowerReport(off = off()), 204))
        assertEquals(PowerRecord(), afterAnswer(alone, PowerReport(off = off()), 400))
    }

    @Test
    fun `an answer keeps what was written meanwhile`() {
        // The shutdown receiver wrote a newer shutdown (this boot's) while the sync's POST ran.
        val newer = off(atMs = now - 1_000, boot = 42)
        val record = PowerRecord(off = newer, on = on())
        val body = PowerReport(off = off(), on = on())
        assertEquals(PowerRecord(off = newer), clearSent(record, body))
        assertEquals(PowerRecord(off = newer), dropSent(record, body))
        // A newer start (another boot) stays too; the sent start is matched by its boot, not its
        // recomputed instant.
        val nextBoot = on(boot = 43)
        assertEquals(PowerRecord(on = nextBoot), clearSent(PowerRecord(on = nextBoot), PowerReport(on = on())))
        assertEquals(PowerRecord(), clearSent(PowerRecord(on = on()), PowerReport(on = on(atMs = 7))))
        // A shutdown marked sent only when it is still the one sent.
        assertEquals(PowerRecord(off = newer), clearSent(PowerRecord(off = newer), PowerReport(off = off())))
    }

    @Test
    fun `seven days end a kept shutdown and a start`() {
        val old = now - POWER_STALE_MS - 1
        assertEquals(PowerRecord(), stale(PowerRecord(off = off(atMs = old), offSent = true, on = on(atMs = old)), now))
        val fresh = PowerRecord(off = off(atMs = now - POWER_STALE_MS), offSent = true, on = on())
        assertEquals(fresh, stale(fresh, now))
        assertEquals(PowerRecord(on = on()), stale(PowerRecord(off = off(atMs = now - 8 * day), offSent = true, on = on()), now))
        assertEquals(7 * day, POWER_STALE_MS)
    }

    @Test
    fun `a shutdown and a boot replace the kept ones`() {
        val record = PowerRecord(off = off(), offSent = true, on = on())
        assertEquals(PowerRecord(off = off(boot = 42), offSent = false, on = on()), withShutdown(record, off(boot = 42)))
        assertEquals(PowerRecord(off = off(), offSent = true, on = on(boot = 43)), withBoot(record, on(boot = 43)))
    }

    @Test
    fun `the record round-trips through the prefs' values`() {
        val full = PowerRecord(
            off = PowerOff(5, 41, BatteryReport(3, charging = false, low = true), reboot = false, reason = "battery"),
            offSent = true,
            on = PowerOn(9, 42, BatteryReport(60, charging = true, low = false)),
        )
        for (record in listOf(full, PowerRecord(), PowerRecord(off = off()), PowerRecord(on = PowerOn(9, 42)))) {
            assertEquals(record, decodePowerRecord(encodePowerRecord(record)))
        }
        // Unknowns stay unknown: no reboot, no reason, no battery keys.
        val keys = encodePowerRecord(PowerRecord(off = PowerOff(5, 41))).keys
        assertEquals(setOf(PowerKeys.OFF_AT, PowerKeys.OFF_BOOT, PowerKeys.OFF_SENT), keys)
        // A part without its instant is none; a sent mark without a shutdown is nothing.
        assertEquals(PowerRecord(), decodePowerRecord(mapOf(PowerKeys.OFF_BOOT to 41, PowerKeys.OFF_SENT to true)))
    }

    @Test
    fun `the prepared client is rebuilt when the base URL, the token or the proxy change`() {
        val key = clientKeyOf("https://kidphone.example", "FAKE-TOKEN", null)!!
        assertFalse(rebuildNeeded(key, clientKeyOf("https://kidphone.example", "FAKE-TOKEN", null)))
        assertTrue("the proxy came up", rebuildNeeded(key, clientKeyOf("https://kidphone.example", "FAKE-TOKEN", "127.0.0.1:1055")))
        assertTrue(rebuildNeeded(key, clientKeyOf("https://other.example", "FAKE-TOKEN", null)))
        assertTrue(rebuildNeeded(key, clientKeyOf("https://kidphone.example", "FAKE-TOKEN-2", null)))
        assertNull(clientKeyOf("https://kidphone.example", "", null))
        assertNull(clientKeyOf(null, "FAKE-TOKEN", null))
        assertTrue("unenrolled: dropped", rebuildNeeded(key, null))
        assertFalse(rebuildNeeded(null, null))
        assertTrue(POWER_CAPABILITY in STATUS_CAPABILITIES)
    }

    private val sources = listOf("src/main/java", "app/src/main/java").map(::File).first { it.isDirectory }

    private fun code(name: String): String = sources.walkTopDown().first { it.name == name }.readText()

    private fun body(code: String, start: String): String = code.substringAfter(start).substringBefore("\n    }\n")

    @Test
    fun `one shutdown receiver, the cover's writes first, each part caught`() {
        val all = sources.walkTopDown().filter { it.extension == "kt" }.joinToString("\n") { it.readText() }
        assertEquals("the process's one ACTION_SHUTDOWN receiver", 1, Regex("""IntentFilter\(Intent\.ACTION_SHUTDOWN\)""").findAll(all).count())
        val receiver = code("BootCover.kt").substringAfter("private val shutdownReceiver").substringBefore("\n    }\n")
        val cover = receiver.indexOf("coverAtShutdown(app)")
        val power = receiver.indexOf("PowerEvents.onShutdown(app, intent, this)")
        assertTrue(cover in 0 until power)
        // Each in its own try/catch.
        assertEquals(2, Regex("""try \{""").findAll(receiver).count())
        assertTrue(receiver.substring(cover, power).contains("} catch (e: Exception)"))
        assertTrue(receiver.substring(power).contains("} catch (e: Exception)"))
        // The record committed before goAsync; finish() in a finally, also when the executor refuses.
        val shutdown = body(code("PowerEvents.kt"), "fun onShutdown(")
        assertTrue(shutdown.indexOf("update(app) { withShutdown(it, off) }") in 0 until shutdown.indexOf("receiver.goAsync()"))
        assertTrue(Regex("""\} finally \{\s+pending\.finish\(\)""").containsMatchIn(shutdown))
        assertEquals(2, Regex("""pending\.finish\(\)""").findAll(shutdown).count())
        assertTrue("SHUTDOWN_POST_TIMEOUT_MS" in body(code("PowerEvents.kt"), "fun prepare("))
        assertEquals(3_000L, SHUTDOWN_POST_TIMEOUT_MS)
        assertTrue("callTimeout(it, TimeUnit.MILLISECONDS)" in code("MdmApi.kt"))
    }

    @Test
    fun `the boot record, the upload and the prepared client are wired`() {
        val app = code("Application.kt")
        val onCreate = app.substringAfter("override fun onCreate()").substringBefore("\n    }\n")
        // Main process only (after the boot cover's early return), right after the witness.
        assertTrue(onCreate.indexOf("return\n") < onCreate.indexOf("PowerEvents.recordBoot(this)"))
        assertTrue(onCreate.indexOf("BootWitness.record(this)") < onCreate.indexOf("PowerEvents.recordBoot(this)"))
        assertTrue("PowerEvents.init(this)" in app.substringAfter("private fun initRest("))
        val sync = code("MdmSyncWorker.kt")
        assertTrue(sync.indexOf("TsnetClient.connectFromPreferences(context)") < sync.indexOf("PowerEvents.prepare(context)"))
        assertTrue(sync.indexOf("ParentPinEvents.upload(context, api)") < sync.indexOf("PowerEvents.upload(context, api)"))
        // The proxy coming up or going rebuilds the client (QA N1).
        val tsnet = code("TsnetClient.kt")
        assertTrue("proxyChanged(before)" in tsnet.substringAfter("fun connect(").substringBefore("\n    }\n"))
        assertTrue("proxyChanged(before)" in tsnet.substringAfter("fun close(").substringBefore("\n    }\n"))
        assertTrue("TsnetClient.proxyListener = { prepareAsync(app) }" in code("PowerEvents.kt"))
        assertTrue("hadLastBoot = before.lastBoot != null" in code("BootWitness.kt"))
    }
}
