package com.kidslauncher.mdm.server

import android.hardware.biometrics.BiometricManager
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Fingerprint enrolment without adb (the user, 2026-10-24; design 59d §8.4): the Settings row's rules. */
class FingerprintEnrolTest {
    private val main = listOf("src/main", "app/src/main").map(::File).first { it.isDirectory }
    private fun src(path: String) = File(main, path).readText()

    @Test
    fun `the row shows only with our Android credential and a sensor`() {
        assertTrue(fingerprintRowShown(credentialOurs = true, hasSensor = true))
        // Own mode, or a credential someone else set: Android's enrolment would ask for a PIN that isn't the kid's.
        assertFalse(fingerprintRowShown(credentialOurs = false, hasSensor = true))
        assertFalse(fingerprintRowShown(credentialOurs = true, hasSensor = false))
        assertFalse(fingerprintRowShown(credentialOurs = false, hasSensor = false))
    }

    @Test
    fun `enrolled only from what canAuthenticate can tell, never a count`() {
        assertEquals(true, fingerprintEnrolled(BiometricManager.BIOMETRIC_SUCCESS))
        assertEquals(false, fingerprintEnrolled(BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED))
        assertNull(fingerprintEnrolled(BiometricManager.BIOMETRIC_ERROR_HW_UNAVAILABLE))
        assertNull(fingerprintEnrolled(BiometricManager.BIOMETRIC_ERROR_NO_HARDWARE))
        assertNull(fingerprintEnrolled(null))
    }

    @Test
    fun `the page opens as the write-settings page does`() {
        assertEquals(FingerprintAccess.CAN_OPEN, fingerprintAccess(pageAvailable = true, windowOpen = true, pageMissing = false))
        assertEquals(FingerprintAccess.PAUSE_FIRST, fingerprintAccess(pageAvailable = false, windowOpen = false, pageMissing = false))
        assertEquals(FingerprintAccess.NO_PAGE, fingerprintAccess(pageAvailable = false, windowOpen = true, pageMissing = false))
        assertEquals(FingerprintAccess.NO_PAGE, fingerprintAccess(pageAvailable = true, windowOpen = true, pageMissing = true))
        val enrol = src("java/com/kidslauncher/mdm/server/FingerprintEnrol.kt")
        // The biometric enrolment for a strong biometric first, then the fingerprint one; both through
        // systemPageIntent (an unsuspended system page - during the pause).
        val biometric = enrol.indexOf("Intent(Settings.ACTION_BIOMETRIC_ENROLL).putExtra(")
        val fingerprint = enrol.indexOf("systemPageIntent(context, Intent(Settings.ACTION_FINGERPRINT_ENROLL))")
        assertTrue(biometric > 0 && fingerprint > biometric)
        assertTrue("Settings.EXTRA_BIOMETRIC_AUTHENTICATORS_ALLOWED,\n                BiometricManager.Authenticators.BIOMETRIC_STRONG," in enrol)
        assertTrue("PackageManager.FEATURE_FINGERPRINT" in enrol)
        assertTrue("canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG)" in enrol)
    }

    @Test
    fun `the row is hidden until the fragment decides, and decides on every resume`() {
        val prefs = src("res/xml/preferences.xml")
        val row = prefs.substringAfter("android:key=\"settings_mdm_fingerprint\"").substringBefore("/>")
        assertTrue("app:isPreferenceVisible=\"false\"" in row)
        assertTrue(prefs.indexOf("settings_mdm_write_settings\"") < prefs.indexOf("settings_mdm_fingerprint\""))
        val fragment = src("java/com/kidslauncher/mdm/ui/settings/launcher/SettingsFragmentLauncher.kt")
        assertTrue("com.kidslauncher.mdm.lock.AndroidLock.credentialOurs(context),\n            FingerprintEnrol.hasSensor(context)," in fragment)
        assertTrue("openFirstPage(pages, fingerprintLauncher)" in fragment)
        val resume = fragment.substringAfter("override fun onResume()").substringBefore("private fun openFirstPage")
        assertTrue("updateFingerprintRow()" in resume)
        assertTrue("return owned(app) && deviceSecure(app)" in src("java/com/kidslauncher/mdm/lock/AndroidLock.kt"))
    }
}
