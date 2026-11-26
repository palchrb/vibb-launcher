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

    /** What KidAppComponentFactory is about to create. */
    enum class Kind { ACTIVITY, SERVICE, RECEIVER, PROVIDER }

    /**
     * The rc.5 gate (§6bv finding 2): whether the unlocked setup's second part (the time rules' boot re-check, the update
     * fence, the PIN lock's runtime - `Application.initRules`) must have run before a component of [kind] is created in
     * a process that was alive before the unlock. An activity (a page reads the lock and the rules at its first resume)
     * and a receiver (it acts at once, often on the rules) get it first, as before; a service or a provider - the
     * always-on VPN, the notification listener, the system's provider installs at the unlock - only the first part, so
     * their creates no longer wait behind it on the main thread.
     */
    fun unlockedRulesBefore(kind: Kind): Boolean = kind == Kind.ACTIVITY || kind == Kind.RECEIVER
}
