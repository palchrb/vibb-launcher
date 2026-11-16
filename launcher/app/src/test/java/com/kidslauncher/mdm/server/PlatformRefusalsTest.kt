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
        assertTrue(plannedOffLists(settings, planSuspend = setOf(settings), planHide = setOf(settings), refusedNow = emptySet()))
        assertTrue(plannedOffLists(settings, planSuspend = emptySet(), planHide = setOf(settings), refusedNow = emptySet()))
        // The pause: the plan releases it, so it shows although this boot's record holds a refusal.
        assertFalse(plannedOffLists(settings, planSuspend = emptySet(), planHide = emptySet(), refusedNow = setOf(settings)))
        // Before the first apply in this process the record stands in.
        assertTrue(plannedOffLists(settings, planSuspend = null, planHide = null, refusedNow = setOf(settings)))
        assertFalse(plannedOffLists("com.example.game", planSuspend = null, planHide = null, refusedNow = setOf(settings)))
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
    }
}
