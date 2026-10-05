package com.kidslauncher.mdm.server.dto

import kotlinx.serialization.Serializable

/** Body for one entry in a `POST /api/devices/browser-history` batch - see
 * server::models::BrowserHistoryUpload. Field names mirror the browser fork's own journal
 * provider column names (see BrowserHistorySync.kt) except [visitedAt]/[deviceCreatedAt], which
 * are the provider's `timestamp`/`created_at` columns renamed to avoid reading as "when this was
 * uploaded." */
@Serializable
data class BrowserHistoryUpload(
    val remoteId: Long,
    val url: String,
    val title: String? = null,
    val visitedAt: Long,
    val deviceCreatedAt: Long,
)
