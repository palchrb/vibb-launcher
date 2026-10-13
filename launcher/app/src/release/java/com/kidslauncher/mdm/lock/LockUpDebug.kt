package com.kidslauncher.mdm.lock

/**
 * Release build: the lock-up is exactly [lockUpStarts]' plan (design 32 step 1). The debug build's
 * version (src/debug) can re-start a lock that is on top, for the §5 H2 experiment. Never add
 * anything to this file.
 */
internal object LockUpDebug {
    @Suppress("UNUSED_PARAMETER")
    fun adjust(start: LockUpStart, showLock: Boolean): LockUpStart = start
}
