package com.kidslauncher.mdm.server

import android.os.BatteryManager.BATTERY_PLUGGED_AC
import android.os.BatteryManager.BATTERY_PLUGGED_USB
import android.os.BatteryManager.BATTERY_STATUS_CHARGING
import android.os.BatteryManager.BATTERY_STATUS_DISCHARGING
import android.os.BatteryManager.BATTERY_STATUS_FULL
import android.os.BatteryManager.BATTERY_STATUS_NOT_CHARGING
import com.kidslauncher.mdm.server.dto.BatteryReport
import com.kidslauncher.mdm.server.dto.StatusReportRequest
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Design 35 step 3: the battery reading, its place in the status report, and the receiver for
 * Android's battery-low edge. */
class BatteryReportTest {
    private fun of(level: Int, scale: Int = 100, status: Int = BATTERY_STATUS_DISCHARGING, plugged: Int = 0, low: Boolean = false) =
        BatteryReport.of(level, scale, status, plugged, low)

    @Test
    fun `the level is a rounded percent, clamped to 0-100`() {
        assertEquals(14, of(14)!!.level)
        assertEquals(50, of(1, scale = 2)!!.level)
        assertEquals(67, of(2, scale = 3)!!.level) // 66.7
        assertEquals(33, of(1, scale = 3)!!.level) // 33.3
        assertEquals(0, of(0)!!.level)
        assertEquals(100, of(150)!!.level)
        assertEquals(100, of(255, scale = 255)!!.level)
    }

    @Test
    fun `charging is plugged in, or a charging or full status`() {
        assertFalse(of(50)!!.charging)
        assertFalse(of(50, status = BATTERY_STATUS_NOT_CHARGING)!!.charging)
        assertTrue(of(50, plugged = BATTERY_PLUGGED_AC)!!.charging)
        assertTrue(of(50, status = BATTERY_STATUS_NOT_CHARGING, plugged = BATTERY_PLUGGED_USB)!!.charging)
        assertTrue(of(50, status = BATTERY_STATUS_CHARGING)!!.charging)
        assertTrue(of(100, status = BATTERY_STATUS_FULL)!!.charging)
    }

    @Test
    fun `low is Android's own flag`() {
        assertTrue(of(10, low = true)!!.low)
        assertFalse(of(10)!!.low)
        assertTrue(of(30, low = true)!!.low) // whatever the level says
    }

    @Test
    fun `a missing level or scale is no reading`() {
        assertNull(of(-1))
        assertNull(of(50, scale = -1))
        assertNull(of(50, scale = 0))
    }

    @Test
    fun `the report carries battery when there is a reading and leaves it out when not`() {
        fun encode(battery: BatteryReport?) = ServerJson.encodeToJsonElement(
            StatusReportRequest.serializer(),
            StatusReportRequest(lockReason = "NONE", kioskEngaged = true, battery = battery),
        ).jsonObject
        assertEquals(
            """{"level":14,"charging":true,"low":false}""",
            encode(of(14, plugged = BATTERY_PLUGGED_AC)).getValue("battery").toString(),
        )
        assertFalse("battery" in encode(null))
    }

    private val sources = listOf("src/main/java", "app/src/main/java").map(::File).first { it.isDirectory }

    private fun code(name: String): String = sources.walkTopDown().first { it.name == name }.readText()
        .lines()
        .filterNot { line -> line.trimStart().let { it.startsWith("//") || it.startsWith("*") || it.startsWith("/*") } }
        .joinToString("\n")

    @Test
    fun `every report reads the battery, and the low and okay edges request a sync from initRest`() {
        val sync = code("MdmSyncWorker.kt")
        assertTrue("battery = BatteryStatus.report(context)," in sync)
        val initRest = code("Application.kt").substringAfter("private fun initRest(").substringBefore("\n    }\n")
        assertTrue("BatteryStatus.watch(this)" in initRest)
        val status = code("BatteryStatus.kt")
        val watch = status.substringAfter("fun watch(app: Context)")
        assertTrue("registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))" in status)
        assertTrue("addAction(Intent.ACTION_BATTERY_LOW)" in watch)
        assertTrue("addAction(Intent.ACTION_BATTERY_OKAY)" in watch)
        assertTrue("ContextCompat.RECEIVER_NOT_EXPORTED" in watch)
        assertTrue("SyncRunner.request(app, BATTERY_SYNC_REASON)" in watch)
        assertEquals("battery", BATTERY_SYNC_REASON)
        // A receiver fault must never take the home process down: the request and the
        // registration are each caught.
        assertEquals(2, Regex("""catch \(e: Exception\)""").findAll(watch).count())
    }
}
