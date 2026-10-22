package com.kidslauncher.mdm.lock

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.kidslauncher.mdm.server.AppEnforcer
import com.kidslauncher.mdm.server.currentPolicyDecision

/**
 * Debug build only (src/debug, docs/design/59-prototype.md): design 59's hybrid on or off. Exported for
 * `adb shell am broadcast -n ...`, guarded by DUMP (shell has it, apps don't):
 *
 * - `--es keyguard android`: stores the switch on ([HybridDebug]), then a full apply - the reset token is set
 *   while the phone has no credential; the next correct kid PIN on our keypad becomes Android's PIN.
 * - `--es keyguard own`: stores the switch off, clears Android's PIN with the token ([Hybrid59.rollback]), then
 *   a full apply - today's lock (59 §9 step 6). Works at any time, the keyguard up or not.
 * - no extra: just an apply and the report.
 *
 * logcat `Hybrid59` and the broadcast's result data say what is in force ([Hybrid59.describe]).
 */
class HybridDebugReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext
        val wanted = intent.getStringExtra(EXTRA_KEYGUARD)
        val pending = goAsync()
        // Off the main thread: prefs, binder calls, a full apply.
        Thread {
            val result = try {
                val stored = when (wanted) {
                    "android" -> HybridDebug.store(app, true)
                    "own" -> HybridDebug.store(app, false)
                    null -> true
                    else -> null
                }
                if (stored == null) {
                    "unknown keyguard '$wanted' (android|own)"
                } else {
                    Hybrid59.refresh(app)
                    if (wanted == "own") Hybrid59.rollback(app)
                    AppEnforcer.apply(app, currentPolicyDecision().policy)
                    "${wanted ?: "report"} -> ${Hybrid59.describe(app)}${if (!stored) " (the switch couldn't be stored)" else ""}"
                }
            } catch (e: Exception) {
                Log.w(HYBRID_TAG, "The switch failed", e)
                "failed: ${e.javaClass.simpleName}"
            }
            Log.i(HYBRID_TAG, result)
            pending.resultData = result
            pending.finish()
        }.start()
    }

    companion object {
        const val EXTRA_KEYGUARD = "keyguard"
    }
}
