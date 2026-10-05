package com.kidslauncher.mdm.server

import android.content.ContentResolver
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.util.Log
import com.kidslauncher.mdm.preferences.LauncherPreferences
import com.kidslauncher.mdm.server.dto.BrowserHistoryUpload
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

private const val LOG_TAG = "BrowserHistorySync"
private const val HISTORY_AUTHORITY = "com.kidsmdm.browser.journal"

// Same "own mutex, own trigger points" rationale as journalSyncMutex in JournalSync.kt - this
// has no attachments to upload so it's cheaper per cycle, but there's still no reason to let it
// block (or be blocked by) the latency-sensitive policy sync or the unrelated IM journal sync.
private val browserHistorySyncMutex = Mutex()

/**
 * Pulls new rows from the kids-mdm-browser fork's history journal `ContentProvider` (same
 * contract shape as kids-mdm-im's conversation journal - see JournalSync.kt and this repo's
 * CLAUDE.md) and forwards them to kid-phone-server. Silently a no-op if the provider isn't
 * reachable - the browser fork isn't installed, or this app isn't signed with its matching
 * certificate yet (`SecurityException` - see the manifest's `com.kidsmdm.browser.ACCESS_JOURNAL`
 * permission) - so this is safe to call unconditionally from every sync cycle even before that's
 * set up.
 */
suspend fun performBrowserHistorySync(context: Context): Unit = browserHistorySyncMutex.withLock {
    val mdm = LauncherPreferences.mdm()
    val serverUrl = mdm.serverUrl()
    val deviceToken = mdm.deviceToken()
    if (serverUrl.isNullOrBlank() || deviceToken.isNullOrBlank()) return@withLock
    val api = createMdmApi(serverUrl, deviceToken)

    var sinceId = mdm.browserHistorySyncSinceId()

    while (true) {
        val entries = queryHistoryEntries(context.contentResolver, sinceId)
        if (entries.isEmpty()) return@withLock

        try {
            val response = api.uploadBrowserHistory(entries.map { it.toUpload() })
            if (!response.isSuccessful) {
                Log.w(LOG_TAG, "Browser history batch upload failed: HTTP ${response.code()}")
                return@withLock
            }
        } catch (e: Exception) {
            Log.w(LOG_TAG, "Browser history batch upload failed", e)
            return@withLock
        }

        sinceId = entries.maxOf { it.id } + 1
        mdm.browserHistorySyncSinceId(sinceId)
    }
}

private data class HistoryEntry(
    val id: Long,
    val url: String,
    val title: String?,
    val timestamp: Long,
    val createdAt: Long,
) {
    fun toUpload() = BrowserHistoryUpload(
        remoteId = id,
        url = url,
        title = title,
        visitedAt = timestamp,
        deviceCreatedAt = createdAt,
    )
}

/** Up to 200 rows, oldest-first, `_id > sinceId` - exactly the provider's own contract, not
 * something this app controls (selection/sortOrder args are ignored provider-side), same as
 * [JournalSync.kt]'s queryJournalEntries. */
private fun queryHistoryEntries(resolver: ContentResolver, sinceId: Long): List<HistoryEntry> {
    val uri = Uri.parse("content://$HISTORY_AUTHORITY/entries/$sinceId")
    return try {
        resolver.query(uri, null, null, null, null)?.use { it.toEntries() } ?: emptyList()
    } catch (e: Exception) {
        emptyList()
    }
}

private fun Cursor.toEntries(): List<HistoryEntry> {
    val idCol = getColumnIndexOrThrow("_id")
    val urlCol = getColumnIndexOrThrow("url")
    val titleCol = getColumnIndex("title")
    val timestampCol = getColumnIndexOrThrow("timestamp")
    val createdAtCol = getColumnIndexOrThrow("created_at")

    val entries = mutableListOf<HistoryEntry>()
    while (moveToNext()) {
        entries.add(
            HistoryEntry(
                id = getLong(idCol),
                url = getString(urlCol),
                title = if (titleCol >= 0) getString(titleCol) else null,
                timestamp = getLong(timestampCol),
                createdAt = getLong(createdAtCol),
            )
        )
    }
    return entries
}
