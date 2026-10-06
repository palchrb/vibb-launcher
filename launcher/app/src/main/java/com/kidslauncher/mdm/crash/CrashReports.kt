package com.kidslauncher.mdm.crash

import android.content.Context
import android.util.Log
import com.kidslauncher.mdm.BuildConfig
import com.kidslauncher.mdm.server.MdmApi
import com.kidslauncher.mdm.server.ServerJson
import com.kidslauncher.mdm.server.dto.CrashReport
import com.kidslauncher.mdm.server.dto.CrashReportBatch
import kotlinx.serialization.builtins.ListSerializer

private const val LOG_TAG = "CrashReports"
private const val PREFS = "crash_reports"
private const val KEY_V1 = "v1"

/**
 * Stores crashes ([crashSignature]) in **device-protected** prefs - a crash before the first unlock
 * (the call path) is kept too; the trace has no personal data - and sends them with the next sync.
 */
object CrashReports {
    private fun prefs(context: Context) =
        context.createDeviceProtectedStorageContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun load(context: Context): List<CrashReport> = try {
        prefs(context).getString(KEY_V1, null)
            ?.let { ServerJson.decodeFromString(ListSerializer(CrashReport.serializer()), it) }
            .orEmpty()
    } catch (e: Exception) {
        emptyList()
    }

    private fun save(context: Context, list: List<CrashReport>) {
        prefs(context).edit()
            .putString(KEY_V1, ServerJson.encodeToString(ListSerializer(CrashReport.serializer()), list))
            .commit()
    }

    /** From the uncaught-exception handler, right before the process exits. Never throws. */
    fun record(context: Context, throwable: Throwable) {
        try {
            val signature = crashSignature(throwable)
            save(context, recordCrash(load(context), signature, System.currentTimeMillis(), BuildConfig.VERSION_CODE.toLong()))
            Log.e(LOG_TAG, "Crash ${signature.hash} recorded for the server", throwable)
        } catch (t: Throwable) {
            // Never let the record stop the exit.
        }
    }

    /** After the status report: sends what is stored; drops it once the server accepted it (a
     * server without the endpoint answers 404 and the reports wait, at most [MAX_STORED_CRASHES]). */
    suspend fun upload(context: Context, api: MdmApi) {
        val stored = load(context)
        if (stored.isEmpty()) return
        try {
            val response = api.sendCrashReports(CrashReportBatch(stored))
            if (response.isSuccessful) save(context, afterUpload(load(context), stored))
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(LOG_TAG, "Crash report upload failed", e)
        }
    }
}
