package com.kidslauncher.mdm.lock

import android.content.Context

/**
 * Debug build only (design 32 step 2, L5): the keyguard cover's switch, in its own CE prefs file -
 * set by `KeyguardCoverDebugReceiver`, read by [KeyguardCover] (its apply and its process-start load,
 * never on the main thread). It survives restarts until switched off. The release build has a
 * `KeyguardCoverDebug` whose [enabled] is always false (src/release) and none of this.
 */
internal object KeyguardCoverDebug {
    private const val PREFS = "keyguard_cover_debug"
    private const val KEY_ON = "on"

    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun enabled(context: Context): Boolean = prefs(context).getBoolean(KEY_ON, false)

    /** Stores the switch, synchronously. */
    fun store(context: Context, on: Boolean): Boolean = prefs(context).edit().putBoolean(KEY_ON, on).commit()
}
