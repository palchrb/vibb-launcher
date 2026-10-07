package com.kidslauncher.mdm.lock

import android.content.Context
import android.util.Log
import com.kidslauncher.mdm.server.LockTaskSetting

/**
 * Debug build only: the stored lock-task override ([LockTaskOverride]) in its own prefs file, and
 * [adjust], which [LockTaskChrome] calls on every pass right before it writes. The release build has
 * a no-op `LockTaskDebug` (src/release) and none of this.
 */
internal object LockTaskDebug {
    private const val LOG_TAG = "LockTaskDebug"
    private const val PREFS = "lock_task_debug_override"
    private const val KEY_CLEAR = "clear_features"
    private const val KEY_ADD = "add_features"
    private const val KEY_PACKAGES = "extra_lock_task_packages"

    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun load(context: Context): LockTaskOverride? {
        val prefs = prefs(context)
        return lockTaskOverride(prefs.getInt(KEY_CLEAR, 0), prefs.getInt(KEY_ADD, 0), prefs.getStringSet(KEY_PACKAGES, emptySet()).orEmpty().joinToString(","))
    }

    /** Stores [override] (`null` = reset), synchronously. */
    fun store(context: Context, override: LockTaskOverride?): Boolean {
        val editor = prefs(context).edit().clear()
        if (override != null) {
            editor.putInt(KEY_CLEAR, override.clearFeatures).putInt(KEY_ADD, override.addFeatures)
                .putStringSet(KEY_PACKAGES, override.extraPackages)
        }
        return editor.commit()
    }

    /** The computed setting with the stored override on top ([applyLockTaskOverride]). */
    fun adjust(context: Context, setting: LockTaskSetting): LockTaskSetting {
        val override = try {
            load(context)
        } catch (e: Exception) {
            Log.w(LOG_TAG, "Unreadable override - ignored", e)
            null
        } ?: return setting
        val adjusted = applyLockTaskOverride(setting, override)
        Log.i(
            LOG_TAG,
            "Override $override: features ${describeLockTaskFeatures(setting.features)} -> " +
                "${describeLockTaskFeatures(adjusted.features)}, packages ${adjusted.packages?.sorted() ?: "none (unpinned)"}",
        )
        return adjusted
    }
}
