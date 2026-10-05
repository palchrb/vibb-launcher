package com.kidslauncher.mdm.server.dto

import kotlinx.serialization.Serializable

/** One blocked-domain event, sent in a batch to `POST /api/devices/dns-events` - see
 * [com.kidslauncher.mdm.server.BlockedEventLog]. [category] is whatever [com.kidslauncher.mdm.server.DnsFilterEngine]
 * matched the domain against (e.g. "Adult content", "Ads & tracking", "Custom"), so the admin log
 * can show why a domain was blocked, not just that it was. */
@Serializable
data class DnsEventReport(
    val domain: String,
    val category: String,
    val blockedAt: String,
)
