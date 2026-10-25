package com.kidslauncher.mdm.preferences

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Enrolment before Home's first start (PLAN fix backlog, qa-glance-topinset-code Q-3; design 59d §8.17). */
class FirstLaunchResetTest {
    private val main = listOf("src/main", "app/src/main").map(::File).first { it.isDirectory }
    private fun src(path: String) = File(main, "java/com/kidslauncher/mdm/$path").readText()

    @Test
    fun `the reset runs only on a phone that neither started Home nor enrolled`() {
        // A genuinely fresh install: no Home start, no enrolment - the defaults are set.
        assertTrue(firstLaunchResetDue(startedBefore = false, enrolled = false, tokenStored = false))
        // Enrolled before Home's first start (the QR's onProfileProvisioningComplete, or Settings
        // opened without Home): kept, whichever of the two writes is seen.
        // A sealed token that doesn't open (design r10) still counts as stored.
        assertFalse(firstLaunchResetDue(startedBefore = false, enrolled = true, tokenStored = true))
        assertFalse(firstLaunchResetDue(startedBefore = false, enrolled = true, tokenStored = false))
        assertFalse(firstLaunchResetDue(startedBefore = false, enrolled = false, tokenStored = true))
        // Home has started: never again, enrolled or not.
        assertFalse(firstLaunchResetDue(startedBefore = true, enrolled = false, tokenStored = false))
        assertFalse(firstLaunchResetDue(startedBefore = true, enrolled = true, tokenStored = true))
    }

    @Test
    fun `initRest asks the rule with what both enrolment paths write`() {
        val app = src("Application.kt")
        val initRest = app.substringAfter("private fun initRest()").substringBefore("\n    private fun ").substringBefore("\n    fun ")
        val call = "firstLaunchResetDue(startedBefore, mdm.enrolled(), com.kidslauncher.mdm.server.DeviceToken.present())"
        assertTrue(call in initRest)
        assertTrue("val startedBefore = LauncherPreferences.internal().started()" in initRest)
        // The reset is called once, only under the rule and nothing else (code QA Q-3: an extra `|| !startedBefore`
        // would bring the de-enrolment back).
        assertEquals(1, Regex("""resetPreferences\(""").findAll(initRest).count())
        assertTrue("        if ($call) {\n            resetPreferences(this)\n        } else if (!startedBefore) {" in initRest)
        // The enrolment's mark: the QR / provisioning path and Settings' "Enroll now" both go through
        // Enrollment.enroll, which writes the (sealed) token and the enrolled flag on success, so the next
        // process start keeps them.
        assertTrue("Enrollment.enroll(" in src("server/Provisioning.kt").substringAfter("fun applyProvisioningExtras("))
        assertTrue("Enrollment.enroll(" in src("ui/settings/launcher/SettingsFragmentLauncher.kt").substringAfter("private fun enrollWithServer("))
        val success = src("server/Enrollment.kt").substringAfter("isSuccessful")
        assertTrue("DeviceToken.set(" in success)
        assertTrue("mdm.enrolled(true)" in success)
        // started_before stays Home's to set (with its first-launch work).
        assertTrue("LauncherPreferences.internal().started(true)" in src("ui/HomeActivity.kt"))
        assertFalse("internal().started(true)" in src("server/Provisioning.kt"))
    }
}
