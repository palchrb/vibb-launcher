package com.kidslauncher.mdm.lock

/*
 * Design 16c: Home never shows content while locked. Pure, tested in HomeGateTest.
 *
 * Emulator run 2026-10-07: at boot the system started our HomeActivity before the PIN lock's
 * ProcessStart had run (the lock was still DISABLED in memory), so Home showed the contacts and
 * apps for about 20 s. Home now shows its content only when the lock allows it; otherwise the night
 * ground with the breathing Vibb mark, with nothing to touch.
 */

/**
 * Whether Home may show its content (contacts, apps, the call card, the Settings tile) and take
 * touches on it. [known]: [PinLockRuntime] has decided this process's lock mode ([lockMode]); then
 * only LOCKED hides it - UNLOCKED, or DISABLED (no kid PIN, an Android credential, unmanaged, the
 * crash guard) shows it. Not known yet: hidden whenever [pinActive] - the stored lock state or the
 * cached policy says a kid PIN is set.
 */
fun homeShowsContent(lockMode: LockMode, pinActive: Boolean, known: Boolean): Boolean =
    if (known) lockMode != LockMode.LOCKED else !pinActive

/** What the first process start of a boot does about Home (design 16 A, 16c). */
enum class BootHomeAction {
    /** Not the first start of this boot (or no boot count): nothing. */
    NONE,

    /** Our Home already came up in this process (the stock launcher's hand-over started it): no
     * second start - the boot is marked done and the lock goes up in the same pass. */
    MARK_DONE,

    /** Start Home (typed HOME); its resume shows the lock, [LOCK_FALLBACK_MS] is the backstop. */
    START,
}

/**
 * [firstStartOfBoot]: the boot count differs from the stored one; [homeShown]: HomeActivity has
 * resumed in this process; [due]: the gate ([com.kidslauncher.mdm.server.bringHomeAfterUpdate]) -
 * only evaluated when it matters (it may decode the cached policy).
 */
fun bootHomeAction(firstStartOfBoot: Boolean, homeShown: Boolean, due: () -> Boolean): BootHomeAction = when {
    !firstStartOfBoot -> BootHomeAction.NONE
    homeShown -> BootHomeAction.MARK_DONE
    due() -> BootHomeAction.START
    else -> BootHomeAction.NONE
}
