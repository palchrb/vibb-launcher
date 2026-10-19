package com.kidslauncher.mdm.server

import android.content.Context
import android.util.Log
import com.kidslauncher.mdm.push.SyncRunner

private const val LOG_TAG = "InstallNow"
private const val PREFS = "install_now"
private const val KEY_RECORD = "record_v1"

/**
 * The parent's Install Now while it runs (design 37 §4.2; the rules are SelfUpdatePlan.kt's):
 * one [InstallNowRecord] in device-protected prefs `install_now`, every write one `commit()`.
 * Device-protected by the user's choice (design 37 Nit 1): it holds ids, a tag and versionCodes
 * only, and nothing reads it before the first unlock today (`SelfUpdate.init` runs from
 * `initRest`, which waits for the unlock - qa-37-design R-N1) - DE keeps it readable should that
 * change. Every failure goes through [fail], and the next sync's settle answers it, so one place
 * answers.
 */
object InstallNow {
    private fun prefs(context: Context) =
        context.applicationContext.createDeviceProtectedStorageContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    @Synchronized
    fun load(context: Context): InstallNowRecord? {
        val raw = try {
            prefs(context).getString(KEY_RECORD, null)
        } catch (e: Exception) {
            Log.w(LOG_TAG, "Install Now's record can't be read", e)
            null
        } ?: return null
        return try {
            ServerJson.decodeFromString(InstallNowRecord.serializer(), raw)
        } catch (e: Exception) {
            // Unreadable: no command runs (the server says "No answer" after 25 h).
            Log.w(LOG_TAG, "Install Now's record doesn't decode (${e.javaClass.simpleName})")
            null
        }
    }

    /** A record that hasn't failed yet: the download's override and the commit's gate. */
    fun active(context: Context): InstallNowRecord? = load(context)?.takeIf { it.failure == null }

    @Synchronized
    fun store(context: Context, record: InstallNowRecord) {
        val encoded = ServerJson.encodeToString(InstallNowRecord.serializer(), record)
        if (!prefs(context).edit().putString(KEY_RECORD, encoded).commit()) {
            Log.w(LOG_TAG, "Couldn't write Install Now's record")
        }
    }

    /** The command failed: kept with its reason (the first one counts) and answered by the sync
     * this asks for. */
    fun fail(context: Context, reason: String) {
        synchronized(this) {
            val record = load(context) ?: return
            if (record.failure != null) return
            Log.w(LOG_TAG, "Install Now ${record.commandId} failed: $reason")
            store(context, record.copy(failure = reason))
        }
        try {
            SyncRunner.request(context.applicationContext, "install_now")
        } catch (e: Exception) {
            Log.w(LOG_TAG, "Couldn't ask for the sync after a failure", e)
        }
    }

    /** The commit waits on a call: when that began, and whether it has gone on too long (R-L4). */
    fun callWait(context: Context, waitingOnCall: Boolean, nowElapsed: Long): Boolean {
        val expired = synchronized(this) {
            val record = load(context) ?: return false
            val since = callWaitSince(record.callWaitSinceElapsed, waitingOnCall, nowElapsed)
            if (since != record.callWaitSinceElapsed) store(context, record.copy(callWaitSinceElapsed = since))
            callWaitExpired(since, nowElapsed)
        }
        if (expired) fail(context, INSTALL_NOW_CALL_NEVER_ENDED)
        return expired
    }

    /** The settle answered [commandId]: cleared, unless a newer command was stored meanwhile. */
    @Synchronized
    fun clearCommand(context: Context, commandId: Long) {
        if (load(context)?.commandId == commandId) clear(context)
    }

    @Synchronized
    fun clear(context: Context) {
        try {
            if (!prefs(context).edit().clear().commit()) Log.w(LOG_TAG, "Couldn't clear Install Now's record")
        } catch (e: Exception) {
            Log.w(LOG_TAG, "Couldn't clear Install Now's record", e)
        }
    }
}
