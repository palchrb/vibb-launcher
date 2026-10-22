package com.kidslauncher.mdm.server.dto

import kotlinx.serialization.Serializable

/** The oldest undelivered remote command for this device, if any - see
 * [PolicyResponse.pendingCommand]. [command] is one of "ring" | "stop_ring" | "lock" | "wipe" |
 * "locate" | "end_windows" | "allow_blocked" | "start_pause" (design 24b) | "install_update" (design 37) |
 * "set_kid_pin" (design 59). [toString] never shows [pin]; the cached policy never holds it
 * ([com.kidslauncher.mdm.server.cacheablePolicy]). */
@Serializable
data class PendingCommand(
    val id: Long,
    val command: String,
    /** When the parent asked for it (the server's UTC "YYYY-MM-DD HH:MM:SS"): with [id] it names
     * the command uniquely, also after a restored server backup reuses an id or on another server
     * (QA of the launcher wave, Medium 1). `null` from an older server. */
    val requestedAt: String? = null,
    /** `allow_blocked` only: the apps the PWA's Status card listed, with the signing keys it
     * showed - only those are let out, each while its key is still that one (QA of cmd-expiry,
     * Low 1). `null` from an older server: every block goes, as before. */
    val allow: List<AllowedBlock>? = null,
    /** `install_update` only (design 37): the launcher release the parent's row showed
     * (`tag@asset_id`). The phone installs the newest release the list advertises when it acts;
     * this one is kept in the record for the log. `null` for every other command. */
    val releaseTag: String? = null,
    /** `set_kid_pin` only (design 59 §1): the Child PIN's digits, for Android's own lock. Never
     * stored, cached, logged or put in an answer. `null` for every other command, and when the
     * server holds no PIN it can send. */
    val pin: String? = null,
) {
    override fun toString(): String =
        "PendingCommand(id=$id, command=$command, requestedAt=$requestedAt, allow=$allow, releaseTag=$releaseTag, " +
            "pin=${if (pin == null) "null" else "<redacted>"})"
}

/** One app of [PendingCommand.allow]: [signer] the key shown (SHA-256, lower-case hex), `null` =
 * the phone couldn't read it then. */
@Serializable
data class AllowedBlock(
    val packageName: String,
    val signer: String? = null,
)
