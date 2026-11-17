package com.kidslauncher.mdm.push.up

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Design 108 §2.3: an app uninstalled (`PACKAGE_FULLY_REMOVED`) or its data cleared (`PACKAGE_DATA_CLEARED`, so its
 * tokens are gone) - its registrations removed and a `DELETE` each. Both are protected broadcasts only the system sends
 * and on Android's implicit-broadcast exception list, so a manifest receiver, `exported="false"`; not direct-boot-aware
 * (the engine checks the unlock anyway). The sync's reconcile is the backstop for a missed one (an app no longer
 * installed).
 */
class UpPackageReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_PACKAGE_FULLY_REMOVED && intent.action != Intent.ACTION_PACKAGE_DATA_CLEARED) return
        val pkg = intent.data?.schemeSpecificPart?.takeIf { it.isNotEmpty() } ?: return
        if (pkg == context.packageName) return
        val pending = goAsync()
        UpRuntime.packageGone(context, pkg) { pending.finish() }
    }
}
