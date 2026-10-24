package com.kidslauncher.mdm.server

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.biometrics.BiometricManager
import android.provider.Settings
import com.kidslauncher.mdm.R

/*
 * Fingerprint enrolment without adb (the user, 2026-10-24; design 59d §8.4: the parent enrols it again during a
 * pause after an OWN_KEYPAD reset). The PIN-gated Settings' "Fingerprint" row, next to "Modify system settings",
 * opens Android's own enrolment - `ACTION_BIOMETRIC_ENROLL` for a strong biometric, else `ACTION_FINGERPRINT_ENROLL`
 * - on the same path as the battery row and [WriteSettingsAccess]: only an unsuspended system page, i.e. during
 * "Pause all restrictions" on a managed phone (the pause unhides Android's Settings and ends the kiosk). Android asks
 * for the device credential first - the Child PIN in android mode - and runs its own UI. Only shown while Android's
 * PIN is the Child PIN Vibb set and still wants ([fingerprintRowShown]) on a phone with a fingerprint sensor.
 */

/** Android's lock as the "Fingerprint" row sees it ([com.kidslauncher.mdm.lock.AndroidLock.fingerprintLockState]). */
data class FingerprintLockState(val owned: Boolean, val deviceSecure: Boolean, val pinRevKnown: Boolean, val requested: Boolean)

/**
 * The "Fingerprint" row is shown only while Android's PIN is the Child PIN Vibb set and still wants (code QA Q-4):
 * we own the live credential ([FingerprintLockState.owned] and [FingerprintLockState.deviceSecure]), its rev is known
 * (a PIN changed on the phone makes it null - Android would ask for a PIN the parent may not know, 59 §3.7), and
 * android mode is requested (in the rollback's escrow the next token clears the credential, and the fingerprint with
 * it - RC3-6, so an enrolment there is lost) - on a phone with a fingerprint sensor. Own mode never shows it.
 */
fun fingerprintRowShown(lock: FingerprintLockState, hasSensor: Boolean): Boolean =
    lock.owned && lock.deviceSecure && lock.pinRevKnown && lock.requested && hasSensor

/** What `BiometricManager.canAuthenticate(BIOMETRIC_STRONG)` tells about enrolment: true for
 * `BIOMETRIC_SUCCESS`, false for `BIOMETRIC_ERROR_NONE_ENROLLED`, null for anything else (never a count). It answers
 * for any strong biometric; on a phone whose only one is the fingerprint (the Jelly Star) that is the fingerprint. */
fun fingerprintEnrolled(canAuthenticate: Int?): Boolean? = when (canAuthenticate) {
    BiometricManager.BIOMETRIC_SUCCESS -> true
    BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED -> false
    else -> null
}

/** The row's state line: registered / not registered / unknown (code QA Q-3: pinned by test). */
fun fingerprintStateText(enrolled: Boolean?): Int = when (enrolled) {
    true -> R.string.settings_mdm_fingerprint_enrolled
    false -> R.string.settings_mdm_fingerprint_not_enrolled
    null -> R.string.settings_mdm_fingerprint_unknown
}

/** The row's action line for [SystemPageAccess]. */
fun fingerprintActionText(access: SystemPageAccess): Int = when (access) {
    SystemPageAccess.CAN_OPEN -> R.string.settings_mdm_fingerprint_open
    SystemPageAccess.PAUSE_FIRST -> R.string.settings_mdm_fingerprint_pause_first
    SystemPageAccess.WAIT -> R.string.settings_mdm_fingerprint_wait
    SystemPageAccess.NO_PAGE -> R.string.settings_mdm_fingerprint_no_page
}

object FingerprintEnrol {
    fun hasSensor(context: Context): Boolean =
        runCatching { context.packageManager.hasSystemFeature(PackageManager.FEATURE_FINGERPRINT) }.getOrDefault(false)

    /** [fingerprintEnrolled] of `canAuthenticate(BIOMETRIC_STRONG)` (needs `USE_BIOMETRIC`, code QA Q-1); null when
     * unreadable. */
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
