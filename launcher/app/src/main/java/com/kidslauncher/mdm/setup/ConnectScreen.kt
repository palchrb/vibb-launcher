package com.kidslauncher.mdm.setup

import com.kidslauncher.mdm.ui.settings.SettingsAccess
import com.kidslauncher.mdm.ui.settings.settingsAccess

/**
 * "Koble til" (2026-11-20, the user): a Device Owner phone with no enrolment - a QR whose extras never arrived, any
 * failed QR - leads from Home to a screen with the server address, the setup code and the enrol button. Pure: due only
 * while the phone is Device Owner, neither enrolled nor holding a token (an unreadable one counts, as the first-launch
 * reset's rule), and Settings itself is open without a PIN (no policy ever applied) - never wider than the Settings
 * gate ([settingsAccess]). The one exception: a phone whose enrolment witness says managed while its preferences say
 * never enrolled (the preferences lost or cut short) - Settings stays behind the parent code, and this screen, with its
 * emergency button, is the way back with a new code from the parent.
 */
fun connectScreenDue(
    deviceOwner: Boolean,
    enrolled: Boolean,
    tokenStored: Boolean,
    policyEverApplied: Boolean,
    witnessManaged: Boolean,
): Boolean = deviceOwner && !enrolled && !tokenStored &&
    (witnessManaged || settingsAccess(policyEverApplied, pinConfigured = false, lockedOut = false) == SettingsAccess.OPEN)

/** The button's state: both fields filled and no enrolment running. */
fun connectButtonEnabled(serverUrl: String, code: String, busy: Boolean): Boolean =
    !busy && serverUrl.isNotBlank() && code.isNotBlank()
