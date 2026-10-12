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

/**
 * Whether a remote wipe would work now (QA of the launcher wave, Low 4), for the status report.
 * On API 35+ a device owner holds `MANAGE_DEVICE_POLICY_WIPE_DATA` (AOSP's DEVICE_OWNER_PERMISSIONS)
 * whatever its admin declares. On 34, without the policy-engine flag, `wipeDataWithReason` needs
 * the admin's `<wipe-data />` policy - and a device owner's `DeviceAdminInfo` is parsed at boot, so
 * after the update that added it, only a restart grants it ([policyGranted] =
 * `DevicePolicyManager.hasGrantedPolicy(admin, USES_POLICY_WIPE_DATA)`, `null` = unreadable).
 */
fun remoteWipeReady(sdkInt: Int, policyGranted: Boolean?): Boolean? = if (sdkInt >= 35) true else policyGranted

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
    /** The call returned: Android did NOT start the reset (QA of the launcher wave, Low 3). A reset
     * that starts never returns - `RecoverySystem.bootCommand` reboots into recovery and waits -,
     * while `DevicePolicyManagerService.forceWipeDeviceNoLock` catches a failure to write the
     * recovery command, logs it and returns normally. Reported as a failure. */
    RETURNED,

    /** The system went away under the call (it is shutting down for the reset): probably wiping. */
    GOING_DOWN,

    /** The platform refused: the phone is NOT wiped - reported so the parent knows. */
    FAILED,
}

/** The names a dying system server's binder failure arrives under: `RemoteException
 * .rethrowFromSystemServer` throws `DeadSystemRuntimeException` (API 33+; older ones wrapped a
 * `DeadSystemException` in a `RuntimeException`). */
private val SYSTEM_GOING_DOWN = setOf("DeadSystemRuntimeException", "DeadSystemException", "DeadObjectException")

/** [errorClass] the simple class name of what the call threw (`null` = it returned), [causeClass]
 * its cause's. */
fun wipeOutcome(errorClass: String?, causeClass: String? = null): WipeOutcome = when {
    errorClass == null -> WipeOutcome.RETURNED
    errorClass in SYSTEM_GOING_DOWN || causeClass in SYSTEM_GOING_DOWN -> WipeOutcome.GOING_DOWN
    else -> WipeOutcome.FAILED
}

/** Whether the result says the wipe is going through: only a system going down under the call. */
fun wipeSucceeded(outcome: WipeOutcome): Boolean = outcome == WipeOutcome.GOING_DOWN

/** The command result's text (the server shows it on the Locate page), at most 200 characters. */
fun wipeResultMessage(outcome: WipeOutcome, error: String? = null): String = when (outcome) {
    WipeOutcome.RETURNED -> "wipe failed, the phone keeps its data: the wipe call returned - Android didn't start the reset"
    WipeOutcome.GOING_DOWN -> "wiping (the system shut down during the call)"
    WipeOutcome.FAILED -> "wipe failed, the phone keeps its data: ${error ?: "unknown error"}"
}.take(200)
