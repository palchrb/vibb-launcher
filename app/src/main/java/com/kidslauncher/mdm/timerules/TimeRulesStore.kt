package com.kidslauncher.mdm.timerules

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.preference.PreferenceManager
import com.kidslauncher.mdm.R
import com.kidslauncher.mdm.server.KidModeEnforcer
import com.kidslauncher.mdm.server.LastEnforcedPlan
import com.kidslauncher.mdm.server.choosePolicy
import com.kidslauncher.mdm.server.decodeCached
import java.time.LocalDateTime

private const val LOG_TAG = "TimeRulesStore"

/** Same DE file as the boot call policy (CallPolicyStore). */
private const val BOOT_PREFS = "boot_call_policy"
private const val BOOT_BLOCKS_KEY = "boot_call_blocks"

/**
 * The time rules the call path needs, in memory: whether a no-calls rule (school) is in force
 * right now. Refreshed together with [com.kidslauncher.mdm.calls.CallPolicyStore] (process start,
 * every accepted sync, each call service's onCreate). Unlocked it reads the CE policy cache (or the
 * last-enforced plan, as `currentPolicyDecision` would) and mirrors the no-calls windows to DE
 * ([BootCallBlocks]); before the first unlock it reads that mirror - missing or unreadable blocks
 * non-emergency calls. Lifts and the override PIN are only known unlocked: [liveDecider], set by
 * [TimeRulesRuntime.init], answers with them; without it the windows alone decide (restrictive).
 */
object TimeRulesStore {
    private enum class Source { NONE, CE, BOOT }

    @Volatile
    private var source = Source.NONE

    /** CE: the enforced policy's time rules (`null` = no policy, e.g. a never-managed phone). */
    @Volatile
    var policy: TimePolicy? = null
        private set

    @Volatile
    private var bootBlocks: BootCallBlocks? = null

    /** Lifts- and override-aware answer, available once the unlocked setup has run. */
    @Volatile
    var liveDecider: (() -> Boolean)? = null

    private var committed: String? = null

    @Synchronized
    fun refresh(context: Context, ceReadable: Boolean) {
        if (!ceReadable) {
            bootBlocks = try {
                decodeBootCallBlocks(bootPrefs(context).getString(BOOT_BLOCKS_KEY, null))
            } catch (e: Exception) {
                Log.e(LOG_TAG, "Couldn't read the boot call blocks", e)
                null
            }
            if (bootBlocks == null) Log.w(LOG_TAG, "No usable boot call blocks: only emergency calls until unlock")
            source = Source.BOOT
            return
        }
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        val decision = choosePolicy(
            null,
            decodeCached(prefs.getString(context.getString(R.string.settings_mdm_kid_mode_policy_key), null)),
            prefs.getBoolean(context.getString(R.string.settings_mdm_policy_ever_applied_key), false),
            LastEnforcedPlan.decode(prefs.getString(context.getString(R.string.settings_mdm_last_enforced_plan_key), null)),
        )
        policy = KidModeEnforcer.timePolicyOf(decision.policy)
        source = Source.CE
        writeBoot(context, encodeBootCallBlocks(bootCallBlocksOf(policy)))
    }

    private fun writeBoot(context: Context, json: String) {
        try {
            val prefs = bootPrefs(context)
            if (committed == null) committed = prefs.getString(BOOT_BLOCKS_KEY, null)
            if (committed == json) return
            committed = if (prefs.edit().putString(BOOT_BLOCKS_KEY, json).commit()) json else null
        } catch (e: Exception) {
            Log.e(LOG_TAG, "Couldn't write the boot call blocks", e)
            committed = null
        }
    }

    /** Whether a rule that allows no calls is in force now. Any doubt blocks (emergency calls and
     * the callback window always pass anyway). */
    fun callsBlockedNow(): Boolean = try {
        when (source) {
            Source.CE -> liveDecider?.invoke() ?: callsBlockedAt(policy, LocalDateTime.now(), false, RuleLifts())
            Source.BOOT -> bootBlocks?.blockedAt(LocalDateTime.now()) ?: true
            Source.NONE -> true
        }
    } catch (e: Exception) {
        Log.e(LOG_TAG, "Couldn't evaluate the time rules for a call", e)
        true
    }

    private fun bootPrefs(context: Context): SharedPreferences =
        context.createDeviceProtectedStorageContext().getSharedPreferences(BOOT_PREFS, Context.MODE_PRIVATE)
}
