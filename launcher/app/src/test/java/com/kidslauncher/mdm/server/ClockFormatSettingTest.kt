package com.kidslauncher.mdm.server

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Design 34 P12: the policy's clock format and what the launcher writes into `TIME_12_24`. */
class ClockFormatSettingTest {
    @Test
    fun `the choice is read leniently`() {
        assertEquals("24", clockFormatChoice("24"))
        assertEquals("12", clockFormatChoice("12"))
        for (other in listOf(null, "system", "", "24h", "SYSTEM", "follow")) {
            assertEquals(other.toString(), CLOCK_FORMAT_SYSTEM, clockFormatChoice(other))
        }
    }

    @Test
    fun `24 h and 12 h write only when the phone differs`() {
        assertEquals(ClockFormatWrite.Put("24"), clockFormatWrite("24", null, null))
        assertEquals(ClockFormatWrite.Put("24"), clockFormatWrite("24", "12", "12"))
        assertEquals(ClockFormatWrite.Put("12"), clockFormatWrite("12", "24", null))
        assertEquals(ClockFormatWrite.Keep, clockFormatWrite("24", "24", null))
        assertEquals(ClockFormatWrite.Keep, clockFormatWrite("12", "12", "12"))
    }

    @Test
    fun `follow the phone clears only our own value, once`() {
        // Nothing of ours: nothing written, whatever the phone has.
        for (current in listOf(null, "24", "12")) {
            assertEquals(ClockFormatWrite.Keep, clockFormatWrite(CLOCK_FORMAT_SYSTEM, current, null))
        }
        // Ours still in place: back to the locale's default.
        assertEquals(ClockFormatWrite.Clear, clockFormatWrite(CLOCK_FORMAT_SYSTEM, "24", "24"))
        // Changed by hand since (or already unset): forget ours, leave the setting alone.
        assertEquals(ClockFormatWrite.Forget, clockFormatWrite(CLOCK_FORMAT_SYSTEM, "12", "24"))
        assertEquals(ClockFormatWrite.Forget, clockFormatWrite(CLOCK_FORMAT_SYSTEM, null, "24"))
    }

    @Test
    fun `applied is the read-back, null when following the phone`() {
        assertNull(clockFormatApplied(CLOCK_FORMAT_SYSTEM, "24"))
        assertNull(clockFormatApplied(CLOCK_FORMAT_SYSTEM, null))
        assertEquals(true, clockFormatApplied("24", "24"))
        assertEquals(false, clockFormatApplied("24", "12"))
        // No app-op, or a refused write: the setting never became the choice.
        assertEquals(false, clockFormatApplied("12", null))
    }

    /** Wired where the design says: every apply(), the status report, the permission declared; the
     * restriction is never lifted for the write (the design's device check decides). */
    @Test
    fun `wiring`() {
        val main = listOf("src/main", "app/src/main").map(::File).first { it.isDirectory }
        fun src(name: String) = File(main, "java/com/kidslauncher/mdm/server/$name").readText()
        assertTrue("ClockFormatSetting.apply(context, policy?.launcherUi)" in src("AppEnforcer.kt"))
        assertTrue("clockFormatApplied = ClockFormatSetting.report(context, decision.policy)" in src("MdmSyncWorker.kt"))
        assertTrue("android.permission.WRITE_SETTINGS" in File(main, "AndroidManifest.xml").readText())
        val setting = src("ClockFormatSetting.kt")
        assertTrue("Settings.System.canWrite(context)" in setting)
        assertFalse("DISALLOW_CONFIG_DATE_TIME)" in setting)
        assertFalse("clearUserRestriction" in setting)
    }
}
