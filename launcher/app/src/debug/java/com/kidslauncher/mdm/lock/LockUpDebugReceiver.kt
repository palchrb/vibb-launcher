package com.kidslauncher.mdm.lock

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Debug build only (src/debug): design 32 §5's H2 experiment - does the needless re-start of a lock
 * that is on top at a screen-off cause the flash? Exported for `adb shell am broadcast -n ...`,
 * guarded by DUMP (shell has it, apps don't):
 *
 * `--ez skipRestart false` re-starts the lock at every screen-off as before design 32;
 * `--ez skipRestart true` (the default, and the release behaviour) skips it. Without the extra it only
 * reports. Logcat `LockUpDebug` and the broadcast's result data say what is in force.
 */
class LockUpDebugReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.hasExtra(EXTRA_SKIP_RESTART)) LockUpDebug.skipRestart = intent.getBooleanExtra(EXTRA_SKIP_RESTART, true)
        val summary = "skipRestart=${LockUpDebug.skipRestart}"
        Log.i(LOG_TAG, summary)
        resultData = summary
    }

    companion object {
        private const val LOG_TAG = "LockUpDebug"
        const val EXTRA_SKIP_RESTART = "skipRestart"
    }
}
