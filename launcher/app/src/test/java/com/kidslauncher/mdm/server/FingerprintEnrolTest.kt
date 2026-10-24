package com.kidslauncher.mdm.server

import android.hardware.biometrics.BiometricManager
import com.kidslauncher.mdm.R
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

    private val ours = FingerprintLockState(owned = true, deviceSecure = true, pinRevKnown = true, requested = true)

    @Test
    fun `the row shows only while Android's PIN is the Child PIN Vibb set and wants, with a sensor`() {
        assertTrue(fingerprintRowShown(ours, hasSensor = true))
        assertFalse(fingerprintRowShown(ours, hasSensor = false))
        // Own mode, or a credential someone else set.
        assertFalse(fingerprintRowShown(ours.copy(owned = false), hasSensor = true))
        // OWN_KEYPAD: the credential removed by hand - hidden at once, before configure clears `owned`.
        assertFalse(fingerprintRowShown(ours.copy(deviceSecure = false), hasSensor = true))
        // Code QA Q-4: Android's PIN changed on the phone (59 §3.7, the rev unknown) - not the Child PIN.
        assertFalse(fingerprintRowShown(ours.copy(pinRevKnown = false), hasSensor = true))
        // Code QA Q-4: the rollback's escrow (own mode chosen, no token yet) - the next token clears it all.
        assertFalse(fingerprintRowShown(ours.copy(requested = false), hasSensor = true))
    }

    @Test
    fun `enrolled only from what canAuthenticate can tell, never a count`() {
        assertEquals(true, fingerprintEnrolled(BiometricManager.BIOMETRIC_SUCCESS))
        assertEquals(false, fingerprintEnrolled(BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED))
        assertNull(fingerprintEnrolled(BiometricManager.BIOMETRIC_ERROR_HW_UNAVAILABLE))
        assertNull(fingerprintEnrolled(BiometricManager.BIOMETRIC_ERROR_NO_HARDWARE))
        assertNull(fingerprintEnrolled(null))
        // Q-3: each answer has its own text.
        assertEquals(R.string.settings_mdm_fingerprint_enrolled, fingerprintStateText(true))
        assertEquals(R.string.settings_mdm_fingerprint_not_enrolled, fingerprintStateText(false))
        assertEquals(R.string.settings_mdm_fingerprint_unknown, fingerprintStateText(null))
        assertEquals(R.string.settings_mdm_fingerprint_open, fingerprintActionText(SystemPageAccess.CAN_OPEN))
        assertEquals(R.string.settings_mdm_fingerprint_pause_first, fingerprintActionText(SystemPageAccess.PAUSE_FIRST))
        assertEquals(R.string.settings_mdm_fingerprint_wait, fingerprintActionText(SystemPageAccess.WAIT))
        assertEquals(R.string.settings_mdm_fingerprint_no_page, fingerprintActionText(SystemPageAccess.NO_PAGE))
    }

    /** Code QA Q-1: `canAuthenticate` throws without USE_BIOMETRIC, and the row would always say "Unknown". */
    @Test
    fun `the manifest declares USE_BIOMETRIC for canAuthenticate`() {
        assertTrue("<uses-permission android:name=\"android.permission.USE_BIOMETRIC\" />" in src("AndroidManifest.xml"))
        assertTrue("canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG)" in src("java/com/kidslauncher/mdm/server/FingerprintEnrol.kt"))
    }

    @Test
    fun `the pages - the biometric enrolment, then the fingerprint one`() {
        val enrol = src("java/com/kidslauncher/mdm/server/FingerprintEnrol.kt")
        // Both through systemPageIntent (an unsuspended system page - during the pause).
        val biometric = enrol.indexOf("Intent(Settings.ACTION_BIOMETRIC_ENROLL).putExtra(")
        val fingerprint = enrol.indexOf("systemPageIntent(context, Intent(Settings.ACTION_FINGERPRINT_ENROLL))")
        assertTrue(biometric > 0 && fingerprint > biometric)
        assertTrue("Settings.EXTRA_BIOMETRIC_AUTHENTICATORS_ALLOWED,\n                BiometricManager.Authenticators.BIOMETRIC_STRONG," in enrol)
        assertTrue("PackageManager.FEATURE_FINGERPRINT" in enrol)
    }

    @Test
    fun `the row is hidden until the fragment decides, and decides on every resume`() {
        val prefs = src("res/xml/preferences.xml")
        val row = prefs.substringAfter("android:key=\"settings_mdm_fingerprint\"").substringBefore("/>")
        assertTrue("app:isPreferenceVisible=\"false\"" in row)
        assertTrue(prefs.indexOf("settings_mdm_write_settings\"") < prefs.indexOf("settings_mdm_fingerprint\""))
        val fragment = src("java/com/kidslauncher/mdm/ui/settings/launcher/SettingsFragmentLauncher.kt")
        assertTrue("com.kidslauncher.mdm.lock.AndroidLock.fingerprintLockState(context),\n            FingerprintEnrol.hasSensor(context)," in fragment)
        assertTrue("openFirstPage(pages, fingerprintLauncher)" in fragment)
        val rows = fragment.substringAfter("private fun updateSystemPageRows()").substringBefore("\n    }\n")
        assertTrue("updateFingerprintRow()" in rows)
        val update = fragment.substringAfter("private fun updateFingerprintRow()").substringBefore("\n    }\n")
        assertTrue("getString(fingerprintStateText(FingerprintEnrol.enrolled(context)))," in update)
        assertTrue("getString(fingerprintActionText(access))," in update)
        val lock = src("java/com/kidslauncher/mdm/lock/AndroidLock.kt").substringAfter("fun fingerprintLockState(")
        assertTrue("PinLockStore.androidPinRev(app) != null" in lock)
        assertTrue("PinLockStore.keyguardRequested(app)" in lock)
        assertTrue("owned = owned(app), deviceSecure = deviceSecure(app)," in lock)
    }
}
