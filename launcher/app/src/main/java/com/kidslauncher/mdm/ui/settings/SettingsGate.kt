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
/** A stop this soon after a row opened Android's page is that page coming up over Settings. */
const val OWN_PAGE_STOP_WINDOW_MS = 3_000L

/** How long a passed gate outlives a trip to Android's page that a row opened (E4's L3). */
const val OWN_PAGE_GATE_MS = 10 * 60_000L

/**
 * E4's L3: a passed gate stays open across Android's page a Settings row opened (`systemPageIntent`, reached only through
 * the gate) - at the stop ([atStop]) only when the stop comes within [OWN_PAGE_STOP_WINDOW_MS] of that open, and back at
 * the next start only within [OWN_PAGE_GATE_MS] of it. Any other stop (Home, Recents, the screen off) and anything later
 * asks for the code again. [openedAtElapsed]: the row's open (elapsed realtime), `null` for none.
 */
fun ownPageKeepsGate(openedAtElapsed: Long?, nowElapsed: Long, atStop: Boolean): Boolean {
    val since = nowElapsed - (openedAtElapsed ?: return false)
    return since >= 0 && since <= if (atStop) OWN_PAGE_STOP_WINDOW_MS else OWN_PAGE_GATE_MS
}

fun settingsAccess(policyEverApplied: Boolean, pinConfigured: Boolean, lockedOut: Boolean): SettingsAccess =
    when {
        !policyEverApplied -> SettingsAccess.OPEN
        !pinConfigured -> SettingsAccess.REFUSE_NO_PIN
        lockedOut -> SettingsAccess.REFUSE_LOCKED_OUT
        else -> SettingsAccess.REQUIRE_PIN
    }
