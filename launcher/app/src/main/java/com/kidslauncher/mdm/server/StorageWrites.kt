package com.kidslauncher.mdm.server

/**
 * Whether the phone's storage is full as its own writes see it: the last write of the screen-time ledger or of the
 * accepted policy's cache failed (`commit()` returned false). Each kind is cleared by its own next successful write.
 * Reported as `time_state.storage_full`. In memory only - a full disk can't keep it - so a restart starts clear and the
 * next failed write sets it again. Pure (no Android), `StorageWritesTest`.
 */
object StorageWrites {
    enum class Kind { LEDGER, POLICY_CACHE }

    private val failing = java.util.concurrent.ConcurrentHashMap.newKeySet<Kind>()

    /** The outcome of one write of [kind]: `true` when it was the first failure since a success. */
    fun record(kind: Kind, ok: Boolean): Boolean = if (ok) {
        failing.remove(kind)
        false
    } else {
        failing.add(kind)
    }

    fun full(): Boolean = failing.isNotEmpty()

    /** Tests only. */
    internal fun reset() = failing.clear()
}
