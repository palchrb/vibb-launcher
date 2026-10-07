package com.kidslauncher.mdm.calls

/**
 * The components that may run before the first unlock (manifest `directBootAware="true"`, task
 * 15). Every other component of ours can only be instantiated once credential-encrypted storage is
 * unlocked, so KidAppComponentFactory runs the deferred unlocked setup before creating one.
 * DirectBootComponentsTest keeps this in sync with the manifest.
 */
object DirectBootComponents {
    val CLASS_NAMES: Set<String> = setOf(
        "com.kidslauncher.mdm.calls.InCallActivity",
        "com.kidslauncher.mdm.calls.KidInCallService",
        "com.kidslauncher.mdm.calls.KidCallScreeningService",
        "com.kidslauncher.mdm.calls.KidCallRedirectionService",
        "com.kidslauncher.mdm.calls.CallActionReceiver",
        "com.kidslauncher.mdm.calls.BootCallReceiver",
        // Design 16b: the boot cover - own process `:bootcover`, no CE, no lock task, no services.
        "com.kidslauncher.mdm.lock.BootCoverActivity",
    )

    /** Whether instantiating [className] proves CE storage is readable. */
    fun needsUnlockedSetup(className: String): Boolean = className !in CLASS_NAMES
}
