package com.kidslauncher.mdm.shortcuts

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * A tap on one of Snarveier's buttons (design 99 §2.1): our own explicit, immutable broadcast - not exported, as
 * `TimeAskReceiver`. It trusts nothing but the key: `goAsync()`, then [ShortcutsRuntime.tap] on the app's one tap
 * thread (Q99-L5), which builds the model again and passes [shortcutTapAllowed] before anything is done.
 */
class ShortcutsReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_SHORTCUT) return
        val key = intent.getStringExtra(EXTRA_SHORTCUT_KEY)
        val app = context.applicationContext
        val pending = goAsync()
        ShortcutsRuntime.taps.execute {
            try {
                ShortcutsRuntime.tap(app, key, window = false)
            } finally {
                pending.finish()
            }
        }
    }
}
