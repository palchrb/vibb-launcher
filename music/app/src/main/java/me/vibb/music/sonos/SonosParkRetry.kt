package me.vibb.music.sonos

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import java.util.concurrent.TimeUnit

/*
 * Design 54 §2.9 item 3 (R2, R3): a missed bedtime park is retried. When the suspension's park ended with no answer
 * (no Wi-Fi at the edge, a Stop that failed or came late) and renderer.json still names a room, the unique work
 * `sonos-park-retry` runs the standalone path again ([SonosSuspension.standalone]) on the network's return and with a
 * 5-minute backoff, for at most [RETRY_FOR_MS] after the edge, only while the app is still suspended; it ends at the
 * first definite verdict. For every kind (harmless for a `url` play). Whether Android runs a suspended package's work at
 * all is the emulator check R5 step 7 on Android 14 and 17 and [D16+]; if it doesn't, the speaker's own sleep timer and
 * NORMAL still bound the session (§2.9). Ours, not vibb's (vibb has no time rule). GPL-3.0 (or later).
 */
object SonosParkRetry {
    /** The unique work's name. */
    const val WORK = "sonos-park-retry"

    /** The retries end this long after the edge (§2.9 item 3). */
    const val RETRY_FOR_MS = 2 * 60 * 60_000L

    /** The backoff between tries (WorkManager's, linear from this). */
    const val BACKOFF_MIN = 5L

    internal const val KEY_EDGE = "edge_ms"

    /** A park that got no answer at [edgeMs] (the wall clock): the retry, once - a later edge's call keeps the first. */
    fun schedule(context: Context, edgeMs: Long = System.currentTimeMillis()) {
        val request = OneTimeWorkRequestBuilder<Worker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.LINEAR, BACKOFF_MIN, TimeUnit.MINUTES)
            .setInputData(workDataOf(KEY_EDGE to edgeMs))
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(WORK, ExistingWorkPolicy.KEEP, request)
        SonosHome.log("sonos: the bedtime park got no answer - retried when the network is back ($WORK)")
    }

    /** What one try decides (pure; SonosLifecycleTest): stop trying, or try again later. */
    internal fun retryAgain(verdict: RoomVerdict?, suspended: Boolean, nowMs: Long, edgeMs: Long): Boolean =
        suspended && nowMs - edgeMs < RETRY_FOR_MS && (verdict == null || verdict == RoomVerdict.NO_ANSWER)

    class Worker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
        override suspend fun doWork(): Result {
            val edge = inputData.getLong(KEY_EDGE, System.currentTimeMillis())
            val suspended = try {
                applicationContext.packageManager.isPackageSuspended
            } catch (_: Exception) {
                false
            }
            // the app is no longer suspended (the rule ended, a lift): nothing to park - the next start's look decides
            if (!retryAgain(null, suspended, System.currentTimeMillis(), edge)) return Result.success()
            val verdict = SonosSuspension.standalone(applicationContext, retry = false)
            SonosHome.log("sonos: the park's retry - $verdict")
            return if (retryAgain(verdict, suspended, System.currentTimeMillis(), edge)) Result.retry() else Result.success()
        }
    }
}
