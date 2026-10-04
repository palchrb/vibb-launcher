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

    private enum class Source { NONE, BOOT, CE }

    @Volatile
    private var source = Source.NONE

    /** Whether credential-encrypted storage is readable. If we can't tell, assume it isn't: the DE
     * path never throws on a locked phone, the CE path would. */
    fun userUnlocked(context: Context): Boolean = try {
        context.getSystemService(UserManager::class.java)?.isUserUnlocked == true
    } catch (e: Exception) {
        false
    }

    @Synchronized
    fun refresh(context: Context) {
        if (userUnlocked(context)) refreshFromCe(context) else refreshFromBoot(context)
    }

    /** Loads the rules if nothing has been read yet, or only the boot copy while CE is readable now. */
    fun ensureLoaded(context: Context) {
        val current = source
        if (current == Source.NONE || (current == Source.BOOT && userUnlocked(context))) refresh(context)
    }

    private fun refreshFromCe(context: Context) {
        val derived = try {
            val prefs = PreferenceManager.getDefaultSharedPreferences(context)
            val cached = decodeCached(prefs.getString(context.getString(R.string.settings_mdm_kid_mode_policy_key), null))
            val managedLast = prefs.getBoolean(context.getString(R.string.settings_mdm_calls_managed_last_key), false)
            val lastRules = decodeCallRules(prefs.getString(context.getString(R.string.settings_mdm_last_call_rules_key), null))
            callPolicyState(cached, managedLast, lastRules)
        } catch (e: Exception) {
            // Nothing mirrored either: the DE copy keeps the last rules we could read.
            Log.e(LOG_TAG, "Couldn't read the call rules, keeping $state", e)
            return
        }
        state = derived
        source = Source.CE
        mirrorToBoot(context, derived)
    }

    /** Keeps the DE copy equal to what CE says; written only when it differs, synchronously (a
     * reboot may follow). */
    private fun mirrorToBoot(context: Context, derived: CallPolicyState) {
        try {
            val prefs = bootPrefs(context)
            val rewrite = bootPolicyRewrite(prefs.getString(BOOT_KEY, null), derived) ?: return
            if (!prefs.edit().putString(BOOT_KEY, rewrite).commit()) Log.w(LOG_TAG, "Couldn't write the boot call policy")
        } catch (e: Exception) {
            Log.e(LOG_TAG, "Couldn't write the boot call policy", e)
        }
    }

    private fun refreshFromBoot(context: Context) {
        val read = try {
            decodeBootPolicy(bootPrefs(context).getString(BOOT_KEY, null))
        } catch (e: Exception) {
            BootPolicyRead.Corrupt(e.javaClass.simpleName)
        }
        if (read !is BootPolicyRead.Ok) Log.w(LOG_TAG, "No usable boot call policy ($read), failing closed until unlock")
        state = bootPolicyState(read)
        source = Source.BOOT
    }

    private fun bootPrefs(context: Context): SharedPreferences =
        context.createDeviceProtectedStorageContext().getSharedPreferences(BOOT_PREFS, Context.MODE_PRIVATE)

    /** For logs: the kind of state and where it came from, never numbers. */
    fun describe(): String = "${state.javaClass.simpleName} from $source"

    /** The default country code for normalising the other side of a call. */
    val defaultCc: String
        get() = (state as? CallPolicyState.Managed)?.rules?.defaultCc ?: "47"
}
