package com.kidslauncher.mdm.lock

/**
 * Debug build only (design 32 step 1 item 4, §5 H2): whether a lock-up skips the start of a lock that
 * is the top task already ([lockUpStarts]' NONE). [skipRestart] false re-starts it at every
 * screen-off, as before design 32 (`START_DELIVERED_TO_TOP`), to compare the flash with and without
 * that start. Set by `LockUpDebugReceiver`; memory only - a new process skips again. The release
 * build has a pass-through `LockUpDebug` (src/release) and none of this.
 */
internal object LockUpDebug {
    @Volatile
    var skipRestart = true

    /** [lockUpStarts]' plan, or the lock alone where it skipped a lock on top. */
    fun adjust(start: LockUpStart, showLock: Boolean): LockUpStart =
        if (!skipRestart && showLock && start == LockUpStart.NONE) LockUpStart.LOCK else start
}
