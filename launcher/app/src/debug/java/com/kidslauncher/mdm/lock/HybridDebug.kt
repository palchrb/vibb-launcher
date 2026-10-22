package com.kidslauncher.mdm.lock

import android.content.Context

/**
 * Debug build only (design 59's prototype, docs/design/59-prototype.md): the hybrid's switch, in its own CE
 * prefs file - set by `HybridDebugReceiver`, read by [Hybrid59.refresh] (an apply, the process start; never on
 * the main thread). It survives restarts until switched back. The release build has a `HybridDebug` whose
 * [enabled] is always false (src/release) and none of this.
 */
internal object HybridDebug {
    private const val PREFS = "hybrid59_debug"
    private const val KEY_ON = "on"

    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun enabled(context: Context): Boolean = prefs(context).getBoolean(KEY_ON, false)

    /** Stores the switch, synchronously. */
    fun store(context: Context, on: Boolean): Boolean = prefs(context).edit().putBoolean(KEY_ON, on).commit()
}
