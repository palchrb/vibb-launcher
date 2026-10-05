package com.kidslauncher.mdm.ui.settings

/*
 * Who may open the launcher's own Settings (server URL, enrollment, sync, "pause all
 * restrictions"). Pure, unit-tested in SettingsGateTest; SettingsActivity acts on it on every
 * onCreate/onStart, so every entry point (drawer, APPLICATION_PREFERENCES, explicit intents,
 * recents, restore after process death) passes through it. QA step 1 #12 / qa-security #5.
 */

enum class SettingsAccess {
    /** Setup: no policy has ever been applied, so there is nothing to protect yet. */
    OPEN,

    /** Ask for the offline-override PIN. */
    REQUIRE_PIN,

    /** Managed but the server has no PIN for this phone: Settings can't be opened at all. */
    REFUSE_NO_PIN,

    /** Too many wrong PINs: closed until the lockout ends. */
    REFUSE_LOCKED_OUT,
}

/**
 * Before the first policy is applied the phone is unrestricted anyway, and Settings is where the
 * parent enters the server URL, enrolls or scans the setup QR. Once a policy has been applied
 * (`policy_ever_applied` never goes back), Settings needs the PIN - and without one configured on
 * the server it stays shut, instead of upstream's "open to anyone" (the server URL could be
 * re-pointed and the pause switch reached).
 */
fun settingsAccess(policyEverApplied: Boolean, pinConfigured: Boolean, lockedOut: Boolean): SettingsAccess =
    when {
        !policyEverApplied -> SettingsAccess.OPEN
        !pinConfigured -> SettingsAccess.REFUSE_NO_PIN
        lockedOut -> SettingsAccess.REFUSE_LOCKED_OUT
        else -> SettingsAccess.REQUIRE_PIN
    }
