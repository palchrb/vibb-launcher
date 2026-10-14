package com.kidslauncher.mdm.server

import com.kidslauncher.mdm.server.dto.LauncherUi
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

    /** qa-34-s6-code L2: no `launcher_ui` (the last-enforced fallback, an older server) is no choice -
     * never "system", which would put back the parent's 24 h. A `launcher_ui` without the key is. */
    @Test
    fun `no launcher_ui is no choice and never writes`() {
        assertNull(clockFormatChoiceOf(null))
        assertEquals(CLOCK_FORMAT_SYSTEM, clockFormatChoiceOf(LauncherUi()))
        assertEquals("24", clockFormatChoiceOf(LauncherUi(clockFormat = "24")))
        // Our 24 h in place: without a choice it stays; with "system" it goes.
        assertEquals(ClockFormatWrite.Keep, clockFormatWrite(clockFormatChoiceOf(null), "24", "24", null))
        assertEquals(ClockFormatWrite.Restore(null), clockFormatWrite(clockFormatChoiceOf(LauncherUi()), "24", "24", null))
        assertNull(clockFormatApplied(null, "24"))
    }

    @Test
    fun `24 h and 12 h write only when the phone differs`() {
        assertEquals(ClockFormatWrite.Put("24"), clockFormatWrite("24", null, null, null))
        assertEquals(ClockFormatWrite.Put("24"), clockFormatWrite("24", "12", "12", null))
        assertEquals(ClockFormatWrite.Put("12"), clockFormatWrite("12", "24", null, null))
        assertEquals(ClockFormatWrite.Keep, clockFormatWrite("24", "24", null, null))
        assertEquals(ClockFormatWrite.Keep, clockFormatWrite("12", "12", "12", "24"))
    }

    @Test
    fun `follow the phone puts back the phone's own value, once`() {
        // Nothing of ours: nothing written, whatever the phone has.
        for (current in listOf(null, "24", "12")) {
            assertEquals(ClockFormatWrite.Keep, clockFormatWrite(CLOCK_FORMAT_SYSTEM, current, null, null))
        }
        // Ours still in place: back to what was there before (unset, or a hand-set 12 h - N4).
        assertEquals(ClockFormatWrite.Restore(null), clockFormatWrite(CLOCK_FORMAT_SYSTEM, "24", "24", null))
        assertEquals(ClockFormatWrite.Restore("12"), clockFormatWrite(CLOCK_FORMAT_SYSTEM, "24", "24", "12"))
        // Changed by hand since (or already unset): forget ours, leave the setting alone.
        assertEquals(ClockFormatWrite.Forget, clockFormatWrite(CLOCK_FORMAT_SYSTEM, "12", "24", null))
        assertEquals(ClockFormatWrite.Forget, clockFormatWrite(CLOCK_FORMAT_SYSTEM, null, "24", "12"))
    }

    /** N4: the value remembered as the phone's own across our writes. */
    @Test
    fun `the phone's own value is remembered from before our first write`() {
        // First write: what was there (unset, or a hand-set value).
        assertNull(clockBeforeAfterPut(current = null, written = null, before = null))
        assertEquals("12", clockBeforeAfterPut(current = "12", written = null, before = null))
        // A later write over our own value keeps the original.
        assertEquals("12", clockBeforeAfterPut(current = "24", written = "24", before = "12"))
        assertNull(clockBeforeAfterPut(current = "24", written = "24", before = null))
        // Someone changed it by hand since our write: that is the phone's own value now.
        assertEquals("12", clockBeforeAfterPut(current = "12", written = "24", before = null))
        // Walk-through: 12 by hand, PWA 24, PWA 12, then Follow -> 12 again.
        var current: String? = "12"
        var written: String? = null
        var before: String? = null
        for (choice in listOf("24", "12", "24")) {
            val w = clockFormatWrite(choice, current, written, before)
            if (w is ClockFormatWrite.Put) {
                before = clockBeforeAfterPut(current, written, before)
                written = w.value
                current = w.value
            }
        }
        assertEquals(ClockFormatWrite.Restore("12"), clockFormatWrite(CLOCK_FORMAT_SYSTEM, current, written, before))
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

    private val main = listOf("src/main", "app/src/main").map(::File).first { it.isDirectory }
    private fun src(name: String) = File(main, "java/com/kidslauncher/mdm/server/$name").readText()

    /** Wired where the design says: every apply(), the status report, the permission declared. */
    @Test
    fun `wiring`() {
        assertTrue("ClockFormatSetting.apply(context, policy?.launcherUi)" in src("AppEnforcer.kt"))
        assertTrue("clockFormatApplied = ClockFormatSetting.report(context, decision.policy)" in src("MdmSyncWorker.kt"))
        assertTrue("android.permission.WRITE_SETTINGS" in File(main, "AndroidManifest.xml").readText())
        val setting = src("ClockFormatSetting.kt")
        assertTrue("Settings.System.canWrite(context)" in setting)
        // L2: the writer itself never reads past "no launcher_ui" into a default.
        assertTrue("val choice = clockFormatChoiceOf(ui)" in setting)
        assertFalse("ui ?: LauncherUi" in setting)
        assertFalse("UserRestriction" in setting)
        assertFalse("applyDateTimeLock" in setting)
    }

    /** L2: the write never lifts DISALLOW_CONFIG_DATE_TIME around it - `applyDateTimeLock` is called
     * once, before the write, with the plan's value, and nothing in `apply()` after the write touches
     * the date-time restriction. */
    @Test
    fun `the write never lifts the date-time restriction`() {
        val enforcer = src("AppEnforcer.kt")
        val calls = Regex("""(?<!fun )applyDateTimeLock\(""").findAll(enforcer).map { it.range.first }.toList()
        assertEquals("one call of applyDateTimeLock", 1, calls.size)
        val write = enforcer.indexOf("ClockFormatSetting.apply(")
        assertTrue(calls.single() < write)
        assertTrue(Regex("""applyDateTimeLock\(dpm, admin, plan\.lockDateTime\)""").containsMatchIn(enforcer))
        // From the write to the end of apply() - the next top-level member - no restriction call.
        val definition = enforcer.indexOf("private fun applyDateTimeLock(")
        val afterWrite = enforcer.substring(write, enforcer.indexOf("\n    fun ", write).takeIf { it > 0 } ?: definition)
        for (forbidden in listOf("DISALLOW_CONFIG_DATE_TIME", "applyDateTimeLock(", "setAutoTimeEnabled")) {
            assertFalse(forbidden, forbidden in afterWrite.substringBefore("private fun applyDateTimeLock("))
        }
        // The restriction itself is only spelled inside applyDateTimeLock's body.
        val outside = enforcer.substring(0, definition)
        assertFalse("DISALLOW_CONFIG_DATE_TIME" in outside)
    }
}
