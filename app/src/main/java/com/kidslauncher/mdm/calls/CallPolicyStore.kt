package com.kidslauncher.mdm.calls

import android.content.Context
import android.content.SharedPreferences
import android.os.UserManager
import android.util.Log
import androidx.preference.PreferenceManager
import com.kidslauncher.mdm.R
import com.kidslauncher.mdm.server.decodeCached

private const val LOG_TAG = "CallPolicyStore"

/** DE SharedPreferences file and key of the boot call policy (BootCallPolicy.kt). */
private const val BOOT_PREFS = "boot_call_policy"
private const val BOOT_KEY = "boot_call_policy"

/**
 * The call rules in force, in memory: the screening, redirection and in-call services must answer
 * within Telecom's time budget, so they never decode JSON per call. Starts as
 * [CallPolicyState.UnknownFailClosed] (QA #10): until something has been read, nothing but
 * emergency calls (and the callback window) gets through - a crash before [refresh] fails closed,
 * not open.
 *
 * Unlocked, it reads the credential-encrypted (CE) policy cache through SharedPreferences directly
 * (not LauncherPreferences, which needs Application.onCreate to have got that far) and mirrors the
 * result to device-protected (DE) storage. Before the first unlock after a reboot only DE is
 * readable, so it reads that mirror ([bootPolicyState]: missing or unreadable fails closed) - task
 * 15, 02-calls.md "Direct boot". Refreshed by Application.onCreate (and again once the user
 * unlocks), by performMdmSync after every sync, and by each call service's onCreate.
 */
object CallPolicyStore {
    @Volatile
    var state: CallPolicyState = CallPolicyState.UnknownFailClosed
        private set

    @Volatile
    private var source = PolicySource.NONE

    /** The DE string this process last really committed (`null` after a failed write). Taken from
     * disk only once, on the first read in this process; never re-read from the prefs afterwards:
     * after a failed commit() their in-memory map already holds the new value (QA direct-boot #2). */
    private var committed: String? = null
    private var committedKnown = false

    /** `callState.bootPolicy`: "ok", "write_failed" or "unreadable" (the copy wouldn't make the
     * same decisions before unlock), "unknown" before the first unlocked refresh. */
    @Volatile
    var bootPolicyStatus: String = "unknown"
        private set

    /** Whether credential-encrypted storage is readable. If we can't tell, assume it isn't: the DE
     * path never throws on a locked phone, the CE path would. */
    fun userUnlocked(context: Context): Boolean = try {
        context.getSystemService(UserManager::class.java)?.isUserUnlocked == true
    } catch (e: Exception) {
        false
    }

    /** [ceReadable]: CE storage can be read - by default `isUserUnlocked()`; Application passes
     * true when a component that isn't direct-boot-aware is starting (then CE is unlocked even if
     * the user is still "unlocking"). */
    @Synchronized
    fun refresh(context: Context, ceReadable: Boolean = userUnlocked(context)) {
        val deJson = readBoot(context)
        val ce = if (ceReadable) readCe(context) else null
        if (!committedKnown && deJson != null) {
            committed = deJson.getOrNull()
            committedKnown = deJson.isSuccess
        }
        val plan = refreshPlan(ceReadable, ce, deJson?.getOrNull(), if (committedKnown) committed else null)
        val newState = plan.state
        if (newState == null) {
            Log.e(LOG_TAG, "Couldn't read the call rules, keeping $state")
            return
        }
        if (plan.source == PolicySource.BOOT && decodeBootPolicy(deJson?.getOrNull()) !is BootPolicyRead.Ok) {
            Log.w(LOG_TAG, "No usable boot call policy, failing closed until unlock")
        }
        state = newState
        source = plan.source
        if (plan.source != PolicySource.CE) return
        if (plan.repairManagedLast) repairManagedLast(context)
        plan.bootWrite?.let { writeBoot(context, it) }
        // After a CE refresh the copy on disk should be exactly what CE says; null = the write failed.
        bootPolicyStatus = when {
            committed == null -> "write_failed"
            bootCopyFaithful(newState, committed) -> "ok"
            else -> "unreadable"
        }
        if (bootPolicyStatus != "ok") Log.w(LOG_TAG, "Boot call policy: $bootPolicyStatus")
    }

    /** Loads the rules if nothing has been read yet, or only the boot copy while CE is readable now. */
    fun ensureLoaded(context: Context) {
        val current = source
        if (current == PolicySource.NONE || (current == PolicySource.BOOT && userUnlocked(context))) refresh(context)
    }

    /** `null` = the DE file couldn't even be opened. */
    private fun readBoot(context: Context): Result<String?>? = try {
        Result.success(bootPrefs(context).getString(BOOT_KEY, null))
    } catch (e: Exception) {
        Log.e(LOG_TAG, "Couldn't read the boot call policy", e)
        null
    }

    private fun readCe(context: Context): CeRead = try {
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        CeRead.Ok(
            decodeCached(prefs.getString(context.getString(R.string.settings_mdm_kid_mode_policy_key), null)),
            prefs.getBoolean(context.getString(R.string.settings_mdm_calls_managed_last_key), false),
            decodeCallRules(prefs.getString(context.getString(R.string.settings_mdm_last_call_rules_key), null)),
        )
    } catch (e: Exception) {
        Log.e(LOG_TAG, "Couldn't read the call rules", e)
        CeRead.Failed
    }

    private fun writeBoot(context: Context, json: String) {
        val ok = try {
            bootPrefs(context).edit().putString(BOOT_KEY, json).commit()
        } catch (e: Exception) {
            Log.e(LOG_TAG, "Couldn't write the boot call policy", e)
            false
        }
        // On failure what's on disk is unknown: null makes every later refresh write again.
        committed = if (ok) json else null
        committedKnown = true
    }

    /** The DE copy saw managed calls but CE lost `calls_managed_last` (preferences reset): put it
     * back, so policy acceptance (judgeFresh) treats the phone as managed too. */
    private fun repairManagedLast(context: Context) {
        try {
            PreferenceManager.getDefaultSharedPreferences(context).edit()
                .putBoolean(context.getString(R.string.settings_mdm_calls_managed_last_key), true).commit()
            Log.w(LOG_TAG, "Calls were managed per the boot copy but CE had forgotten it: failing closed")
        } catch (e: Exception) {
            Log.e(LOG_TAG, "Couldn't repair calls_managed_last", e)
        }
    }

    private fun bootPrefs(context: Context): SharedPreferences =
        context.createDeviceProtectedStorageContext().getSharedPreferences(BOOT_PREFS, Context.MODE_PRIVATE)

    /** For logs: the kind of state and where it came from, never numbers. */
    fun describe(): String = "${state.javaClass.simpleName} from $source"

    /** The default country code for normalising the other side of a call. */
    val defaultCc: String
        get() = (state as? CallPolicyState.Managed)?.rules?.defaultCc ?: "47"
}
