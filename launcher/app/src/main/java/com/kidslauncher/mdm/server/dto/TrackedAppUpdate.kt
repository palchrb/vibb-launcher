package com.kidslauncher.mdm.server.dto

import kotlinx.serialization.Serializable

/** One entry from `GET /api/devices/apps` - an app tracked from a GitHub repo's Releases (see
 * kid-phone-server's `handlers::tracked_apps`), generalizing the launcher's own self-update to
 * arbitrary third-party apps. [downloadUrl] is per-entry (unlike the launcher's fixed download
 * endpoint) since there can be many tracked apps, each with its own cached APK. [id] is the
 * server's stable tracked-app id - what [MdmSyncWorker] actually keys install-state/caching off
 * of now, not [packageName] (typing a real Android package name is optional server-side these
 * days - see kid-phone-server's tracked_app_add.html - so it can no longer be trusted to be
 * present or unique). [isLauncher] replaces the old `packageName == BuildConfig.APPLICATION_ID`
 * string comparison for deciding whether an install is the launcher's own self-update. */
@Serializable
data class TrackedAppUpdate(
    val id: Long,
    /** Admin-facing catalog name (e.g. "Tailscale") - used for the install-progress
     * notification, since [packageName] can be blank now. */
    val name: String,
    val packageName: String,
    val releaseTag: String,
    val downloadUrl: String,
    val isLauncher: Boolean,
)
