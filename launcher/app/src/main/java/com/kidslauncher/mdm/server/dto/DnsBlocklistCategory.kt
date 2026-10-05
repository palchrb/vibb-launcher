package com.kidslauncher.mdm.server.dto

import kotlinx.serialization.Serializable

/** One entry in the response from `GET /api/devices/dns-blocklist` - see
 * [com.kidslauncher.mdm.server.DnsFilterEngine]. Grouped by category rather than a flat
 * domain-to-category map, matching the server's own `DnsBlocklistCategory` - at ~100k+ domains a
 * flat JSON object would repeat far more per-entry overhead than writing the category name once
 * per group. */
@Serializable
data class DnsBlocklistCategory(
    val category: String,
    val domains: List<String>,
)
