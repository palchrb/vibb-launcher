package com.kidslauncher.mdm.server

/*
 * Find My Device's remote wipe (security review M8) - pure, tested in WipePlanTest; LocateCommands
 * makes the call. Checked against AOSP 14/15's DevicePolicyManagerService: `wipeData(flags)` from
 * a device owner on the system user means "remove this user" for an app targeting API 34+
 * (EXPLICIT_WIPE_BEHAVIOUR) and is refused - "User 0 is a system user and cannot be removed" - so
 * the old `wipeData(0)` only ever logged that and the phone kept its data. `wipeDevice(flags)`
 * (API 34) is the factory reset. Our own DISALLOW_FACTORY_RESET doesn't stop it: AOSP lets the
 * admin that set the restriction wipe anyway.
 */

/** Which DevicePolicyManager call factory-resets the phone. */
enum class WipeCall { WIPE_DEVICE, WIPE_DATA }

/** API 34+: `wipeDevice` (`wipeData` would refuse, above); before 34 `wipeDevice` doesn't exist and
 * `wipeData` resets the device (unreachable with minSdk 34, kept so the rule is in one place). */
fun wipeCall(sdkInt: Int): WipeCall = if (sdkInt >= 34) WipeCall.WIPE_DEVICE else WipeCall.WIPE_DATA

/**
 * No flags: factory reset protection stays, so whoever finds a lost phone still needs the Google
 * account after the reset (`docs/setup/google-account.md`, `docs/design/qa-backup.md` Low 2 - useful
 * for a lost phone); no WIPE_EUICC (the SIM's eSIM profile is the parent's to cancel) and no
 * WIPE_EXTERNAL_STORAGE (none on the phones this runs on).
 */
const val WIPE_FLAGS = 0

/** What the wipe call did, for the command result. */
enum class WipeOutcome {
    /** The call returned: the reset is under way (normally it never returns - the phone reboots). */
    STARTED,

    /** The system went away under the call (it is shutting down for the reset): probably wiping. */
    GOING_DOWN,

    /** The platform refused: the phone is NOT wiped - reported so the parent knows. */
    FAILED,
}

/** [errorClass] the simple class name of what the call threw (`null` = it returned). A dying
 * system server shows up as `DeadSystemException`/`DeadObjectException` (often wrapped in a
 * `RuntimeException` whose name is passed as [causeClass]). */
fun wipeOutcome(errorClass: String?, causeClass: String? = null): WipeOutcome = when {
    errorClass == null -> WipeOutcome.STARTED
    listOf(errorClass, causeClass).any { it == "DeadSystemException" || it == "DeadObjectException" } -> WipeOutcome.GOING_DOWN
    else -> WipeOutcome.FAILED
}

/** The command result's text (the server shows it on the Locate page), at most 200 characters. */
fun wipeResultMessage(outcome: WipeOutcome, error: String? = null): String = when (outcome) {
    WipeOutcome.STARTED -> "wiping"
    WipeOutcome.GOING_DOWN -> "wiping (the system shut down during the call)"
    WipeOutcome.FAILED -> "wipe failed, the phone keeps its data: ${error ?: "unknown error"}"
}.take(200)
