package com.kidslauncher.mdm.server

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.biometrics.BiometricManager
import android.provider.Settings

/*
 * Fingerprint enrolment without adb (the user, 2026-10-24; design 59d §8.4: the parent enrols it again during a
 * pause after an OWN_KEYPAD reset). The PIN-gated Settings' "Fingerprint" row, next to "Modify system settings",
 * opens Android's own enrolment - `ACTION_BIOMETRIC_ENROLL` for a strong biometric, else `ACTION_FINGERPRINT_ENROLL`
 * - on the same path as the battery row and [WriteSettingsAccess]: only an unsuspended system page, i.e. during
 * "Pause all restrictions" on a managed phone (the pause unhides Android's Settings and ends the kiosk). Android asks
 * for the device credential first - the Child PIN in android mode - and runs its own UI. Only shown while Android's
 * credential is ours ([com.kidslauncher.mdm.lock.AndroidLock.credentialOurs]) on a phone with a fingerprint sensor.
 */

/** The "Fingerprint" row is shown: Android's credential is ours and the phone has a fingerprint sensor. Own mode
 * (no Android credential) and a credential someone else set never show it. */
fun fingerprintRowShown(credentialOurs: Boolean, hasSensor: Boolean): Boolean = credentialOurs && hasSensor

/** What `BiometricManager.canAuthenticate(BIOMETRIC_STRONG)` tells about enrolment: true for
 * `BIOMETRIC_SUCCESS`, false for `BIOMETRIC_ERROR_NONE_ENROLLED`, null for anything else (never a count). */
fun fingerprintEnrolled(canAuthenticate: Int?): Boolean? = when (canAuthenticate) {
    BiometricManager.BIOMETRIC_SUCCESS -> true
    BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED -> false
    else -> null
}

/** Whether the row can open Android's enrolment now. */
enum class FingerprintAccess { CAN_OPEN, PAUSE_FIRST, NO_PAGE }

/** As [writeSettingsRowState]'s page part: [pageMissing] (every start threw ActivityNotFoundException) or, during
 * the pause or the override ([windowOpen]), nothing resolving means the phone has no such page. */
fun fingerprintAccess(pageAvailable: Boolean, windowOpen: Boolean, pageMissing: Boolean): FingerprintAccess = when {
    pageMissing -> FingerprintAccess.NO_PAGE
    pageAvailable -> FingerprintAccess.CAN_OPEN
    windowOpen -> FingerprintAccess.NO_PAGE
    else -> FingerprintAccess.PAUSE_FIRST
}

object FingerprintEnrol {
    fun hasSensor(context: Context): Boolean =
        runCatching { context.packageManager.hasSystemFeature(PackageManager.FEATURE_FINGERPRINT) }.getOrDefault(false)

    /** [fingerprintEnrolled] of `canAuthenticate(BIOMETRIC_STRONG)`; null when unreadable. */
    fun enrolled(context: Context): Boolean? = fingerprintEnrolled(
        runCatching {
            context.getSystemService(BiometricManager::class.java)
                ?.canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG)
        }.getOrNull(),
    )

    /** Android's enrolment pages to try, in order, each explicit to a system package ([systemPageIntent]): the
     * biometric enrolment for a strong biometric, then the fingerprint one. Empty outside a pause on a managed
     * phone. Started without NEW_TASK, from the activity. */
    @Suppress("DEPRECATION") // ACTION_FINGERPRINT_ENROLL: only the fallback when the biometric one isn't there.
    fun pageIntents(context: Context): List<Intent> = listOfNotNull(
        systemPageIntent(
            context,
            Intent(Settings.ACTION_BIOMETRIC_ENROLL).putExtra(
                Settings.EXTRA_BIOMETRIC_AUTHENTICATORS_ALLOWED,
                BiometricManager.Authenticators.BIOMETRIC_STRONG,
            ),
        ),
        systemPageIntent(context, Intent(Settings.ACTION_FINGERPRINT_ENROLL)),
    )
}
