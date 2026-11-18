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

    @Synchronized
    fun reset() {
        skipped.clear()
        shown.clear()
        pageMissing.clear()
    }
}
