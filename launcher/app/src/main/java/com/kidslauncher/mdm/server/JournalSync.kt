package com.kidslauncher.mdm.server

import android.content.ContentResolver
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.util.Log
import com.kidslauncher.mdm.preferences.LauncherPreferences
import com.kidslauncher.mdm.server.dto.JournalEntryUpload
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File

private const val LOG_TAG = "JournalSync"
private const val JOURNAL_AUTHORITY = "com.kidsmdm.im.journal"

// Deliberately its own mutex, separate from MdmSyncWorker's syncMutex - a journal sync can involve
// downloading/uploading photo and video attachments, which may take a while, and there's no reason
// a slow journal sync should hold up the latency-sensitive policy/lock-decision sync (or vice
// versa). Still needed at all since this is triggered from the same two places (periodic timer,
// SSE push-nudge) with no coordination otherwise between them - same race-condition class already
// fixed once for performMdmSync, see that function's own doc comment.
private val journalSyncMutex = Mutex()

/**
 * Pulls new rows from kids-mdm-im's conversation journal `ContentProvider` (see this repo's
 * CLAUDE.md and the provider's own contract) and forwards them to kid-phone-server. Silently a
 * no-op if the provider isn't reachable at all - kids-mdm-im isn't installed, or this app isn't
 * signed with its matching certificate yet (`SecurityException` - see the manifest's
 * `com.kidsmdm.im.ACCESS_JOURNAL` permission) - so this is safe to call unconditionally from every
 * sync cycle even before that's set up, same as [checkForTrackedAppUpdates] tolerating a device
 * with nothing new to install.
 */
suspend fun performJournalSync(context: Context): Unit = journalSyncMutex.withLock {
    val mdm = LauncherPreferences.mdm()
    val serverUrl = mdm.serverUrl()
    val deviceToken = mdm.deviceToken()
    if (serverUrl.isNullOrBlank() || deviceToken.isNullOrBlank()) return@withLock
    val api = createMdmApi(serverUrl, deviceToken)

    var sinceId = mdm.journalSyncSinceId()

    while (true) {
        val entries = queryJournalEntries(context.contentResolver, sinceId)
        if (entries.isEmpty()) return@withLock

        try {
            val response = api.uploadJournalEntries(entries.map { it.toUpload() })
            if (!response.isSuccessful) {
                Log.w(LOG_TAG, "Journal batch upload failed: HTTP ${response.code()}")
                return@withLock
            }
        } catch (e: Exception) {
            Log.w(LOG_TAG, "Journal batch upload failed", e)
            return@withLock
        }

        // Best-effort per attachment, same as every other soft-fail report in this codebase - a
        // failed media upload doesn't block the cursor from advancing (the text row is already
        // safely on the server either way), it just means that one attachment stays unsynced.
        // Not retried on a later cycle since the cursor has already moved past it by then.
        for (entry in entries) {
            if (entry.entryType == "MEDIA" && entry.mediaPath != null) {
                uploadMedia(context, api, entry)
            }
        }

        sinceId = entries.maxOf { it.id } + 1
        mdm.journalSyncSinceId(sinceId)
    }
}

private suspend fun uploadMedia(context: Context, api: MdmApi, entry: JournalEntry) {
    // media_path is the messenger's own internal absolute file path, unreadable from this
    // (sandboxed) app directly - only the filename plus the provider's own /media/<fileName>
    // URI actually resolves, per the provider's contract.
    val fileName = File(entry.mediaPath!!).name
    val uri = Uri.parse("content://$JOURNAL_AUTHORITY/media/$fileName")
    try {
        val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: return
        val mediaType = (entry.mediaContentType ?: "application/octet-stream").toMediaType()
        val response = api.uploadJournalMedia(entry.id, bytes.toRequestBody(mediaType))
        if (!response.isSuccessful) {
            Log.w(LOG_TAG, "Journal media upload failed for entry ${entry.id}: HTTP ${response.code()}")
        }
    } catch (e: Exception) {
        Log.w(LOG_TAG, "Journal media upload failed for entry ${entry.id}", e)
    }
}

private data class JournalEntry(
    val id: Long,
    val threadId: Long,
    val recipientId: String,
    val displayName: String?,
    val direction: String,
    val entryType: String,
    val timestamp: Long,
    val body: String?,
    val mediaPath: String?,
    val mediaContentType: String?,
    val callType: String?,
    val callEvent: String?,
    val createdAt: Long,
) {
    fun toUpload() = JournalEntryUpload(
        remoteId = id,
        threadId = threadId,
        recipientId = recipientId,
        displayName = displayName,
        direction = direction,
        entryType = entryType,
        occurredAt = timestamp,
        body = body,
        mediaContentType = mediaContentType,
        callType = callType,
        callEvent = callEvent,
        deviceCreatedAt = createdAt,
    )
}

/** Up to 200 rows, oldest-first, `_id > sinceId` - exactly the provider's own contract, not
 * something this app controls (selection/sortOrder args are ignored server-of-the-provider-side). */
private fun queryJournalEntries(resolver: ContentResolver, sinceId: Long): List<JournalEntry> {
    val uri = Uri.parse("content://$JOURNAL_AUTHORITY/entries/$sinceId")
    return try {
        resolver.query(uri, null, null, null, null)?.use { it.toEntries() } ?: emptyList()
    } catch (e: Exception) {
        emptyList()
    }
}

private fun Cursor.toEntries(): List<JournalEntry> {
    val idCol = getColumnIndexOrThrow("_id")
    val threadIdCol = getColumnIndexOrThrow("thread_id")
    val recipientIdCol = getColumnIndexOrThrow("recipient_id")
    val displayNameCol = getColumnIndex("display_name")
    val directionCol = getColumnIndexOrThrow("direction")
    val entryTypeCol = getColumnIndexOrThrow("entry_type")
    val timestampCol = getColumnIndexOrThrow("timestamp")
    val bodyCol = getColumnIndex("body")
    val mediaPathCol = getColumnIndex("media_path")
    val mediaContentTypeCol = getColumnIndex("media_content_type")
    val callTypeCol = getColumnIndex("call_type")
    val callEventCol = getColumnIndex("call_event")
    val createdAtCol = getColumnIndexOrThrow("created_at")

    val entries = mutableListOf<JournalEntry>()
    while (moveToNext()) {
        entries.add(
            JournalEntry(
                id = getLong(idCol),
                threadId = getLong(threadIdCol),
                recipientId = getString(recipientIdCol),
                displayName = if (displayNameCol >= 0) getString(displayNameCol) else null,
                direction = getString(directionCol),
                entryType = getString(entryTypeCol),
                timestamp = getLong(timestampCol),
                body = if (bodyCol >= 0) getString(bodyCol) else null,
                mediaPath = if (mediaPathCol >= 0) getString(mediaPathCol) else null,
                mediaContentType = if (mediaContentTypeCol >= 0) getString(mediaContentTypeCol) else null,
                callType = if (callTypeCol >= 0) getString(callTypeCol) else null,
                callEvent = if (callEventCol >= 0) getString(callEventCol) else null,
                createdAt = getLong(createdAtCol),
            )
        )
    }
    return entries
}
