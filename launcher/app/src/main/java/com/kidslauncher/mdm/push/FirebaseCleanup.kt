package com.kidslauncher.mdm.push

import android.app.job.JobScheduler
import android.content.Context
import android.util.Log
import java.io.File

private const val LOG_TAG = "FirebaseCleanup"
private const val PREFS = "firebase_cleanup"
private const val DONE = "done_v1"

/**
 * Deletes what the FCM era left ([firebaseLeftovers]) - once: a marker in its own preferences
 * file is set only when everything found was deleted, so a failure is retried at the next process
 * start. Runs after the first unlock (CE storage) on its own background thread; never throws.
 */
object FirebaseCleanup {
    fun runOnceInBackground(context: Context) {
        val app = context.applicationContext
        Thread({
            try {
                runOnce(app)
            } catch (t: Throwable) {
                // Never into the uncaught-exception handler: it would end the call path too.
                Log.w(LOG_TAG, "Cleanup failed - retried at the next start", t)
            }
        }, "firebase-cleanup").start()
    }

    private fun runOnce(context: Context) {
        val marker = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (marker.getBoolean(DONE, false)) return
        var ok = true
        val prefsDir = File(context.dataDir, "shared_prefs")
        val dirs = listOf(context.filesDir, context.noBackupFilesDir)
        val datastoreDir = File(context.filesDir, "datastore")
        val leftovers = firebaseLeftovers(
            prefsFiles = prefsDir.list()?.toList().orEmpty(),
            files = dirs.flatMap { it.list()?.toList().orEmpty() },
            databases = context.databaseList()?.toList().orEmpty(),
            datastoreFiles = datastoreDir.list()?.toList().orEmpty(),
        )
        for (name in leftovers.prefs) {
            if (!context.deleteSharedPreferences(name)) ok = false
        }
        for (name in leftovers.files) {
            for (dir in dirs) {
                val file = File(dir, name)
                if (file.exists() && !file.deleteRecursively()) ok = false
            }
        }
        for (name in leftovers.datastore) {
            if (!File(datastoreDir, name).delete()) ok = false
        }
        // Only Firebase used DataStore here: an emptied directory goes too.
        if (leftovers.datastore.isNotEmpty() && datastoreDir.list()?.isEmpty() == true) datastoreDir.delete()
        for (name in leftovers.databases) {
            if (!context.deleteDatabase(name)) ok = false
        }
        // The data transport scheduled its uploads with JobScheduler; its service class is gone.
        var jobs = 0
        try {
            val scheduler = context.getSystemService(JobScheduler::class.java)
            scheduler?.allPendingJobs?.filter { isFirebaseJobService(it.service?.className) }?.forEach {
                scheduler.cancel(it.id)
                jobs++
            }
        } catch (e: Exception) {
            Log.w(LOG_TAG, "Couldn't cancel the data transport's jobs", e)
            ok = false
        }
        if (!leftovers.isEmpty || jobs > 0) {
            Log.i(LOG_TAG, "Deleted FCM leftovers: ${leftovers.prefs.size} preferences files, ${leftovers.files.size + leftovers.datastore.size} files, ${leftovers.databases.size} databases, $jobs jobs${if (ok) "" else " (some failed)"}")
        }
        if (ok) marker.edit().putBoolean(DONE, true).commit()
    }
}
