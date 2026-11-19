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

}
