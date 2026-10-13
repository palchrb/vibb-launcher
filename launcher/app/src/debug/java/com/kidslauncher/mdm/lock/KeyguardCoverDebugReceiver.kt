package com.kidslauncher.mdm.lock

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.kidslauncher.mdm.server.AppEnforcer
import com.kidslauncher.mdm.server.currentPolicyDecision

/**
 * Debug build only (src/debug, docs/testing/emulator.md §6k): design 32 step 2's keyguard cover on or
 * off. Exported for `adb shell am broadcast -n ...`, guarded by DUMP (shell has it, apps don't):
 *
 * `--ez on true|false` stores the switch ([KeyguardCoverDebug], survives restarts); `--ez skipDismiss
 * true` (the lock never dismisses the keyguard) and `--ez failDismiss true` (the lock pretends to - each
 * one counts as failed) are the failure checks' knobs, memory only, both reset by any broadcast without
 * them. Then a full apply runs (the one keyguard writer, [KeyguardCover]), and logcat `KeyguardCover`
 * and the broadcast's result data say what is in force: `on|off -> mode=... features=... maxTimeToLock=...`.
 */
class KeyguardCoverDebugReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext
        val on = if (intent.hasExtra(EXTRA_ON)) intent.getBooleanExtra(EXTRA_ON, false) else null
        val stored = on?.let { KeyguardCoverDebug.store(app, it) }
        KeyguardCover.dismissTest = when {
            intent.getBooleanExtra(EXTRA_FAIL_DISMISS, false) -> DismissTest.FAIL
            intent.getBooleanExtra(EXTRA_SKIP_DISMISS, false) -> DismissTest.SKIP
            else -> DismissTest.NONE
        }
        val pending = goAsync()
        // Off the main thread: a full apply (binder calls, the policy file).
        Thread {
            val result = try {
                AppEnforcer.apply(app, currentPolicyDecision().policy)
                KeyguardCover.describe()
            } catch (e: Exception) {
                Log.w(LOG_TAG, "Apply failed", e)
                "apply failed: ${e.javaClass.simpleName}"
            }
            val switch = if (KeyguardCoverDebug.enabled(app)) "on" else "off"
            val summary = "$switch -> $result${if (stored == false) " (the switch couldn't be stored)" else ""}"
            Log.i(LOG_TAG, summary)
            pending.resultData = summary
            pending.finish()
        }.start()
    }

    companion object {
        private const val LOG_TAG = "KeyguardCover"
        const val EXTRA_ON = "on"
        const val EXTRA_SKIP_DISMISS = "skipDismiss"
        const val EXTRA_FAIL_DISMISS = "failDismiss"
    }
}
