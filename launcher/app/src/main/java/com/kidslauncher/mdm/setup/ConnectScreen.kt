package com.kidslauncher.mdm.setup

import com.kidslauncher.mdm.ui.settings.SettingsAccess
import com.kidslauncher.mdm.ui.settings.settingsAccess

/**
 * "Koble til" (2026-11-20, the user): a Device Owner phone with no enrolment - a QR whose extras never arrived, any
 * failed QR - leads from Home to a screen with the server address, the setup code and the enrol button. Pure: due only
 * while the phone is Device Owner, neither enrolled nor holding a token (an unreadable one counts, as the first-launch
 * reset's rule), and Settings itself is open without a PIN (no policy ever applied) - never wider than the Settings
 * gate ([settingsAccess]).
 */
fun connectScreenDue(deviceOwner: Boolean, enrolled: Boolean, tokenStored: Boolean, policyEverApplied: Boolean): Boolean =
    deviceOwner && !enrolled && !tokenStored &&
        settingsAccess(policyEverApplied, pinConfigured = false, lockedOut = false) == SettingsAccess.OPEN

/** The button's state: both fields filled and no enrolment running. */
fun connectButtonEnabled(serverUrl: String, code: String, busy: Boolean): Boolean =
    !busy && serverUrl.isNotBlank() && code.isNotBlank()
