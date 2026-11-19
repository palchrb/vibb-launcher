package com.kidslauncher.mdm.setup

/**
 * Design 114 §6.2.2 (QA M2): the flow's run - its "Senere"/"Hopp over" skips and the steps shown so far - kept for the
 * process, not in the activity, so Home, Android's page and back again return to the same screen with the skips kept.
 * The current screen is always the first of [flowScreens]: a done step leaves the run, a skipped one waits for the end.
 * A run from parent Settings starts afresh; the holder is dropped when the window ends.
 */
object SetupRun {
    private val skipped = linkedSetOf<String>()
    private val shown = linkedSetOf<String>()

    /** Android's page for a step threw ActivityNotFoundException on every intent: NO_PAGE for the run. */
    private val pageMissing = mutableSetOf<String>()

    @Synchronized
    fun skipped(): Set<String> = skipped.toSet()

    @Synchronized
    fun shown(): Set<String> = shown.toSet()

    @Synchronized
    fun pageMissing(): Set<String> = pageMissing.toSet()

    @Synchronized
    fun skip(key: String) {
        skipped += key
    }

    /** The end screen's row: back to that step. */
    @Synchronized
    fun unskip(key: String) {
        skipped -= key
    }

    @Synchronized
    fun markShown(key: String) {
        shown += key
    }

    @Synchronized
    fun markPageMissing(key: String) {
        pageMissing += key
    }

    /** When "Sett på pause i 2 t" in the flow took the PIN (elapsed ms): the pause's leave of lock task clears every
     * locked task, the flow's too, and Android starts Home - whose resume brings the flow back ([takeReturnAfterPause]). */
    private var pausedFromFlowAtMs: Long? = null

    @Synchronized
    fun markPausedFromFlow(nowElapsedMs: Long) {
        pausedFromFlowAtMs = nowElapsedMs
    }

    /** The parent closed the flow ("Ferdig", Back): nothing comes back. */
    @Synchronized
    fun clearReturnAfterPause() {
        pausedFromFlowAtMs = null
    }

    /** Home's resume: true once, within [FLOW_RETURN_MS] of the flow's pause and only while the window is open. */
    @Synchronized
    fun takeReturnAfterPause(nowElapsedMs: Long, windowOpen: Boolean): Boolean {
        val due = flowReturnDue(pausedFromFlowAtMs, nowElapsedMs, windowOpen)
        pausedFromFlowAtMs = null
        return due
    }

    @Synchronized
    fun reset() {
        skipped.clear()
        shown.clear()
        pageMissing.clear()
    }
}

/** How long the destroyed flow waits before it comes back (the pause's apply leaves lock task first). */
const val FLOW_RETURN_DELAY_MS = 1_500L

/** How long after the flow's own pause Home's resume still leads back to the flow (the gate saw ~1.3 s). */
const val FLOW_RETURN_MS = 15_000L

/** Whether Home's resume brings the flow back after its "Sett på pause i 2 t" (the gate of 2026-11-19, row 13). Pure. */
fun flowReturnDue(markedAtMs: Long?, nowElapsedMs: Long, windowOpen: Boolean): Boolean =
    markedAtMs != null && windowOpen && nowElapsedMs - markedAtMs in 0..FLOW_RETURN_MS
