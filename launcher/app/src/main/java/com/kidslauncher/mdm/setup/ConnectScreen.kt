package com.kidslauncher.mdm.setup

import com.kidslauncher.mdm.ui.settings.SettingsAccess
import com.kidslauncher.mdm.ui.settings.settingsAccess

/**
 * "Koble til" (2026-11-20, the user): a Device Owner phone with no enrolment - a QR whose extras never arrived, any
 * failed QR - leads from Home to a screen with the server address, the setup code and the enrol button. Pure: due only
 * while the phone is Device Owner, neither enrolled nor holding a token (an unreadable one counts, as the first-launch
 * reset's rule), and Settings itself is open without a PIN (no policy ever applied) - never wider than the Settings
 * gate ([settingsAccess]). The one exception: a phone whose enrolment witness says enrolled while its preferences say
 * never enrolled (the preferences lost or cut short) - Settings stays behind the parent code, and this screen, with its
 * emergency button, is the way back with a new code from the parent.
 */
fun connectScreenDue(
    deviceOwner: Boolean,
    enrolled: Boolean,
    tokenStored: Boolean,
    policyEverApplied: Boolean,
    witnessEnrolled: Boolean,
): Boolean = deviceOwner && !enrolled && !tokenStored &&
    (witnessEnrolled || settingsAccess(policyEverApplied, pinConfigured = false, lockedOut = false) == SettingsAccess.OPEN)

/** On a phone the enrolment's witness names a server for, Koble til only takes an address on that host (another
 * server is refused with the address rule's own wording); with no witnessed address, any address the rule takes. */
fun connectUrlAllowed(typed: String, witnessUrl: String?): Boolean {
    if (witnessUrl == null) return true
    val witnessed = urlHost(witnessUrl) ?: return false
    return urlHost(typed) == witnessed
}

private fun urlHost(url: String): String? = try {
    java.net.URI(url.trim()).host?.lowercase()?.trimEnd('.')?.takeIf { it.isNotEmpty() }
} catch (e: Exception) {
    null
}

/** The button's state: both fields filled and no enrolment running. */
fun connectButtonEnabled(serverUrl: String, code: String, busy: Boolean): Boolean =
    !busy && serverUrl.isNotBlank() && code.isNotBlank()
