package com.kidslauncher.mdm.calls

import android.content.Context
import android.util.Log
import androidx.preference.PreferenceManager
import com.kidslauncher.mdm.R
import com.kidslauncher.mdm.server.decodeCached

private const val LOG_TAG = "CallPolicyStore"

/**
 * The call rules in force, in memory: the screening, redirection and in-call services must answer
 * within Telecom's time budget, so they never decode JSON per call. Starts as
 * [CallPolicyState.UnknownFailClosed] (QA #10): until something has been read, nothing but
 * emergency calls (and the callback window) gets through - a crash before [refresh] fails closed,
 * not open.
 *
 * Reads SharedPreferences directly (not LauncherPreferences, which needs Application.onCreate to
 * have got that far). Refreshed by Application.onCreate, by performMdmSync after every sync, and by
 * each call service's onCreate.
 */
object CallPolicyStore {
    @Volatile
    var state: CallPolicyState = CallPolicyState.UnknownFailClosed
        private set

    @Volatile
    private var loaded = false

    fun refresh(context: Context) {
        try {
            val prefs = PreferenceManager.getDefaultSharedPreferences(context)
            val cached = decodeCached(prefs.getString(context.getString(R.string.settings_mdm_kid_mode_policy_key), null))
            val managedLast = prefs.getBoolean(context.getString(R.string.settings_mdm_calls_managed_last_key), false)
            val lastRules = decodeCallRules(prefs.getString(context.getString(R.string.settings_mdm_last_call_rules_key), null))
            state = callPolicyState(cached, managedLast, lastRules)
            loaded = true
        } catch (e: Exception) {
            Log.e(LOG_TAG, "Couldn't read the call rules, keeping $state", e)
        }
    }

    fun ensureLoaded(context: Context) {
        if (!loaded) refresh(context)
    }

    /** The default country code for normalising the other side of a call. */
    val defaultCc: String
        get() = (state as? CallPolicyState.Managed)?.rules?.defaultCc ?: "47"
}
