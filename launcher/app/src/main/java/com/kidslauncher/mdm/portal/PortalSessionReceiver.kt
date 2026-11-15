package com.kidslauncher.mdm.portal

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** The window's session is in (design 102 §3.1): folded into the report and the budget, and a sync asked for so the
 * parent sees it. Not exported: only this app's `:portal` process sends it. */
class PortalSessionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_SESSION_DONE) return
        val pending = goAsync()
        val app = context.applicationContext
        Thread {
            try {
                PortalRuntime.fold(app)
                com.kidslauncher.mdm.push.SyncRunner.request(app, "portal_session")
            } catch (e: Exception) {
                android.util.Log.w("PortalSessionReceiver", "Couldn't fold the session: ${e.javaClass.simpleName}")
            } finally {
                pending.finish()
            }
        }.start()
    }
}
