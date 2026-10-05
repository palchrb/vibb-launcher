package com.kidslauncher.mdm.server.dto

import kotlinx.serialization.Serializable

/** Body for one entry in a `POST /api/devices/journal` batch - see server::models::JournalEntryUpload.
 * Field names mirror the kids-mdm-im provider's own column names (see JournalSync.kt) except
 * [occurredAt]/[deviceCreatedAt], which are the provider's `timestamp`/`created_at` columns
 * renamed to avoid reading as "when this was uploaded." */
@Serializable
data class JournalEntryUpload(
    val remoteId: Long,
    val threadId: Long,
    val recipientId: String,
    val displayName: String? = null,
    val direction: String,
    val entryType: String,
    val occurredAt: Long,
    val body: String? = null,
    val mediaContentType: String? = null,
    val callType: String? = null,
    val callEvent: String? = null,
    val deviceCreatedAt: Long,
)
