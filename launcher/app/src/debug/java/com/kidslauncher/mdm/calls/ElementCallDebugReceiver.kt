package com.kidslauncher.mdm.calls

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Debug build only (src/debug, design 106 §8 step 9; `checkReleaseHasNoDebugHook`, `DebugHookAbsentTest`): the
 * emulator has no Element X to miss a call in, so this plays the notification listener's part for one room:
 * - `--es mxid <a phone-book contact's Element MXID> --es key <a made-up notification key>` posts a missed Element X
 *   call for that contact, as [com.kidslauncher.mdm.badges.BadgeListenerService] would after its reader judged one;
 * - `--es remove <the same key>` takes it away again, as when the kid opens the room in Element X.
 * Both go through [ElementMissedCallStore], so the badges and Siste's record follow the real path. Exported for
 * `adb shell am broadcast -n ...`, guarded by DUMP (shell has it, apps don't).
 */
class ElementCallDebugReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        ElementCallLogStore.attach(context)
        val key = intent.getStringExtra("key")
        val mxid = intent.getStringExtra("mxid")
        if (key != null && mxid != null) {
            ElementMissedCallStore.posted(ElementPost(key, mxid, System.currentTimeMillis()))
            Log.i(TAG, "Missed Element X call posted")
            resultData = "posted"
        }
        intent.getStringExtra("remove")?.let {
            ElementMissedCallStore.removed(it)
            Log.i(TAG, "Missed Element X call removed")
            resultData = "removed"
        }
    }

    private companion object {
        const val TAG = "ElementCallDebug"
    }
}
