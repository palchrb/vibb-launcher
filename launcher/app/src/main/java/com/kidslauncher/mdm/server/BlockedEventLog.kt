package com.kidslauncher.mdm.server

import android.content.Context
import androidx.preference.PreferenceManager
import com.kidslauncher.mdm.preferences.LauncherPreferences
import com.kidslauncher.mdm.server.dto.DnsEventReport
import java.time.Instant
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString

private const val DEDUP_WINDOW_MS = 10 * 60 * 1000L
private const val MAX_QUEUED = 200

@Serializable
private data class QueuedEvent(val domain: String, val category: String, val blockedAtEpochMs: Long)

/**
 * Records blocked-domain events for later reporting to the server (see [MdmSyncWorker]'s
 * drain-and-report step) - deduplicated so a single ad-heavy page loading dozens of identical
 * blocked tracker requests in a few seconds doesn't flood the admin log; the point is "things
 * worth a conversation," not a firehose. Persisted synchronously via `.commit()` (matching
 * [LocateCommands]'s `CachedFix` pattern, for the same reason - a sync cycle often runs right
 * before a self-update can SIGKILL this process, and an async write can lose that race).
 */
object BlockedEventLog {
    private val lastLoggedAt = HashMap<String, Long>()
    private val lock = Any()

    /** Called from [KidVpnService]'s packet-handling coroutines - safe to call concurrently. */
    fun record(context: Context, domain: String, category: String) {
        val now = System.currentTimeMillis()
        synchronized(lock) {
            val last = lastLoggedAt[domain]
            if (last != null && now - last < DEDUP_WINDOW_MS) return
            lastLoggedAt[domain] = now

            val queue = loadQueue(context).toMutableList()
            queue.add(QueuedEvent(domain, category, now))
            while (queue.size > MAX_QUEUED) queue.removeAt(0)
            saveQueue(context, queue)
        }
    }

    /** Everything queued since the last successful report - does NOT clear it, since [MdmSyncWorker]
     * must only call [clearReported] after the server call actually succeeds, so a failed report
     * doesn't silently lose events. */
    fun drain(context: Context): List<DnsEventReport> {
        return loadQueue(context).map {
            DnsEventReport(it.domain, it.category, Instant.ofEpochMilli(it.blockedAtEpochMs).toString())
        }
    }

    fun clearReported(context: Context, count: Int) {
        synchronized(lock) {
            val queue = loadQueue(context).toMutableList()
            repeat(minOf(count, queue.size)) { queue.removeAt(0) }
            saveQueue(context, queue)
        }
    }

    private fun loadQueue(context: Context): List<QueuedEvent> {
        val json = LauncherPreferences.mdm().blockedDnsEventQueueJson() ?: return emptyList()
        return try {
            ServerJson.decodeFromString<List<QueuedEvent>>(json)
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun saveQueue(context: Context, queue: List<QueuedEvent>) {
        val key = LauncherPreferences.mdm().keys().blockedDnsEventQueueJson()
        PreferenceManager.getDefaultSharedPreferences(context)
            .edit()
            .putString(key, ServerJson.encodeToString(queue))
            .commit()
    }
}
