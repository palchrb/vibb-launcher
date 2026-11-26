package com.kidslauncher.mdm

/**
 * The unlocked setup's two parts and their order (the rc.5 gate, `docs/testing/emulator.md` §6bv finding 2), apart from
 * Android so the order is tested (UnlockedSetupStepsTest). Main thread only.
 *
 * - The first part ([ensure]'s `first`: the call rules' CE refresh, the lock's decide, the preferences, the first-launch
 *   reset, the listeners, the channels, the rules' runtimes) runs once, at the first [ensure]. An exception from it is
 *   reported ([failed]) and the setup goes on: the second part runs all the same, as the lock's runtime always did
 *   after a failed `initRest` (it fails closed on what it can't read).
 * - The second part ([rules]: the time rules' boot re-check, the update fence, the PIN lock's runtime, the message
 *   engine) runs once: at once with `rulesNow` (a process started unlocked, an activity or a receiver about to be
 *   created), else it is handed to [schedule] (a service, a provider, the unlock broadcast) - and an [ensure] with
 *   `rulesNow` before the scheduled run runs it then; the scheduled run then does nothing.
 */
class UnlockedSetupSteps(
    private val rules: () -> Unit,
    private val schedule: (step: () -> Unit) -> Unit,
    private val failed: (Exception) -> Unit,
) {
    var firstDone = false
        private set
    var rulesDone = false
        private set

    /** The second part was handed to [schedule] (not run inside the first [ensure]). */
    var rulesScheduled = false
        private set

    fun ensure(rulesNow: Boolean, first: () -> Unit) {
        if (!firstDone) {
            firstDone = true
            try {
                first()
            } catch (e: Exception) {
                failed(e)
            }
            if (!rulesNow) {
                rulesScheduled = true
                schedule(::runRules)
            }
        }
        if (rulesNow) runRules()
    }

    /** The second part, once. */
    fun runRules() {
        if (rulesDone) return
        rulesDone = true
        rules()
    }
}
