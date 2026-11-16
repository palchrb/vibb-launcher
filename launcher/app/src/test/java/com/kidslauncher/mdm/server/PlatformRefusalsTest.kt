package com.kidslauncher.mdm.server

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** compat-fix-1: Android 17 refuses to hide or suspend its Settings (exempt from suspension). */
class PlatformRefusalsTest {
    private val settings = "com.android.settings"
    private val record = PlatformRefusalRecord(bootCount = 7, fingerprint = "os/37.2", suspend = setOf(settings), hide = setOf(settings))

    @Test
    fun `a refusal holds for its boot and OS build only`() {
        assertEquals(record, refusalsNow(record, 7, "os/37.2"))
        // A reboot or an OS update asks again.
        assertEquals(PlatformRefusalRecord(8, "os/37.2"), refusalsNow(record, 8, "os/37.2"))
        assertEquals(PlatformRefusalRecord(7, "os/37.3"), refusalsNow(record, 7, "os/37.3"))
        // An unknown boot count never skips anything.
        assertEquals(PlatformRefusalRecord(-1, "os/37.2"), refusalsNow(record.copy(bootCount = -1), -1, "os/37.2"))
        assertEquals(PlatformRefusalRecord(7, "os/37.2"), refusalsNow(null, 7, "os/37.2"))
    }

    @Test
    fun `only a refused restriction is skipped`() {
        val refused = setOf(settings)
        assertFalse(restrictCallDue(want = true, current = false, settings, refused))
        assertTrue(restrictCallDue(want = true, current = false, "com.example.game", refused))
        // A release is always made, and nothing is called when the state already matches.
        assertTrue(restrictCallDue(want = false, current = true, settings, refused))
        assertFalse(restrictCallDue(want = true, current = true, "com.example.game", refused))
        assertFalse(restrictCallDue(want = false, current = false, settings, refused))
    }

    @Test
    fun `the lists follow the plan, not the platform`() {
        // Managed: the plan wants Settings off - off Home whatever Android did with it.
        assertTrue(plannedOffLists(settings, planSuspend = setOf(settings), planHide = setOf(settings), storedOff = null))
        assertTrue(plannedOffLists(settings, planSuspend = emptySet(), planHide = setOf(settings), storedOff = null))
        // The pause: the plan releases it, so it shows whatever was stored before.
        assertFalse(plannedOffLists(settings, planSuspend = emptySet(), planHide = emptySet(), storedOff = setOf(settings)))
        // QA M1, before the first apply in a process the stored plan stands in: after a reboot (17: Settings visible, the
        // last plan had it off) it stays off; after a process death in a pause (the plan stored then released it) it shows.
        assertTrue(plannedOffLists(settings, planSuspend = null, planHide = null, storedOff = setOf(settings)))
        assertFalse(plannedOffLists(settings, planSuspend = null, planHide = null, storedOff = setOf("com.example.game")))
        // Never stored (a new phone): nothing is held back.
        assertFalse(plannedOffLists(settings, planSuspend = null, planHide = null, storedOff = null))
    }

    @Test
    fun `the enforcer and the lists use the rules`() {
        val src = listOf(File("src/main/java/com/kidslauncher/mdm"), File("app/src/main/java/com/kidslauncher/mdm")).first { it.exists() }
        val enforcer = File(src, "server/AppEnforcer.kt").readText()
        assertTrue("restrictCallDue(shouldBeSuspended, currentlySuspended, packageName, refused.suspend)" in enforcer)
        assertTrue("restrictCallDue(shouldBeHidden, currentlyHidden, packageName, refused.hide)" in enforcer)
        assertTrue("PlatformRefusals.Call.SUSPEND" in enforcer && "PlatformRefusals.Call.HIDE" in enforcer)
        assertTrue("PlatformRefusals.plannedOff(context, packageName)" in File(src, "apps/AppFilter.kt").readText())
        assertTrue("plannedOff = PlatformRefusals.plannedOff(context, it.packageName)" in File(src, "server/BatteryExemption.kt").readText())
        // M1: every apply stores its plan; L1: a recorded refusal is skipped only while the op still holds.
        assertTrue("PlatformRefusals.storePlan(context, plan.suspend, plan.hide)" in enforcer)
        assertTrue("val refused = PlatformRefusals.stillExempt(context, PlatformRefusals.now(context))" in enforcer)
        // L7: only an exempt package is recorded - the op is read first.
        val refused = File(src, "server/PlatformRefusals.kt").readText().substringAfter("fun refused(").substringBefore("\n    }\n")
        assertTrue(refused.indexOf("if (!exemptFromSuspension(context, packageName)) return false") in 0 until refused.indexOf("val record = now(context)"))
        // L7: Home listens for a changed plan while started, and stops listening.
        val home = File(src, "ui/HomeActivity.kt").readText()
        assertTrue("com.kidslauncher.mdm.server.AppEnforcer.addAppliedListener(planListener)" in home)
        assertTrue("com.kidslauncher.mdm.server.AppEnforcer.removeAppliedListener(planListener)" in home)
        // L4: the PIN-gated Settings' top gear asks the plan too.
        assertTrue("com.kidslauncher.mdm.server.PlatformRefusals.plannedOff(this, target)" in File(src, "ui/settings/SettingsActivity.kt").readText())
    }
}
