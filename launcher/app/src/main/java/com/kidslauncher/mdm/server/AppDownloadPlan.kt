package com.kidslauncher.mdm.server

import kotlinx.serialization.Serializable
import java.security.MessageDigest

/*
 * Catalog downloads on the phone (design 13-app-downloads.md at the monorepo root, with its QA
 * review and the decisions after it): Wi-Fi only when the parent says so, never while roaming,
 * resumable, one at a time outside the sync. The rules are here, pure, no Android imports -
 * AppDownloadPlanTest. The runner (Android glue) is AppDownloads.kt.
 */

/** The launcher's own update waits for Wi-Fi this long from when the phone first saw the release,
 * then takes any non-roaming network (decision after QA review). Catalog apps have no grace. */
const val LAUNCHER_WIFI_GRACE_MS = 3 * 24 * 60 * 60_000L

/** Free space kept on top of what a download and its install session copy need. */
const val DOWNLOAD_SPACE_MARGIN_BYTES = 100L * 1024 * 1024

/**
 * What the default network offers - `ConnectivityManager.getNetworkCapabilities(activeNetwork)`:
 * our VPN's capabilities while it is up (they carry the underlying network's transport and, with
 * `setMetered(false)`, its meteredness), else the physical network's. `null` = no default network.
 */
data class NetCaps(
    val internet: Boolean,
    /** `NET_CAPABILITY_NOT_METERED`. */
    val notMetered: Boolean,
    /** `NET_CAPABILITY_NOT_ROAMING`. */
    val notRoaming: Boolean,
    /** Wi-Fi, cellular or ethernet among the transports. A VPN without an underlying network still
     * has INTERNET and NOT_ROAMING, but no physical transport (QA #1). */
    val physical: Boolean,
)

/** May a download run now - and if not, what it waits for. [wire] is the status report's state. */
enum class DownloadGate(val wire: String) {
    GO("downloading"),
    WAIT_NETWORK("waiting_network"),
    WAIT_WIFI("waiting_wifi"),
    WAIT_ROAMING("waiting_roaming"),
    WAIT_SPACE("waiting_space"),
}

/** When the launcher's own update may use any network ([LAUNCHER_WIFI_GRACE_MS] after the phone
 * first saw it); `null` for a catalog app - it waits for Wi-Fi as long as the switch is on. */
fun anyNetworkAtMs(isLauncher: Boolean, firstSeenMs: Long): Long? =
    if (isLauncher) firstSeenMs + LAUNCHER_WIFI_GRACE_MS else null

/**
 * The gate (design 13 §3, QA #1, decisions): a network with INTERNET and a physical transport;
 * never roaming, switch on or off; with [wifiOnly] also NOT_METERED - unless [anyNetworkAt] (the
 * launcher's grace end) has passed (a clock set back before the start of the wait doesn't count);
 * room for the rest of the download, a session copy of the whole file and [DOWNLOAD_SPACE_MARGIN_BYTES]
 * ([allocatableBytes] `null` = unknown: not checked; [totalBytes] `null` = not known before the
 * first response: checked once it is).
 */
fun downloadGate(
    caps: NetCaps?,
    wifiOnly: Boolean,
    anyNetworkAt: Long?,
    nowMs: Long,
    haveBytes: Long,
    totalBytes: Long?,
    allocatableBytes: Long?,
): DownloadGate {
    if (caps == null || !caps.internet || !caps.physical) return DownloadGate.WAIT_NETWORK
    if (!caps.notRoaming) return DownloadGate.WAIT_ROAMING
    val graceOver = anyNetworkAt != null && nowMs >= anyNetworkAt
    if (wifiOnly && !caps.notMetered && !graceOver) return DownloadGate.WAIT_WIFI
    if (allocatableBytes != null && totalBytes != null && !enoughSpace(haveBytes, totalBytes, allocatableBytes)) {
        return DownloadGate.WAIT_SPACE
    }
    return DownloadGate.GO
}

/** Remaining download + the session copy of the whole file + the margin fit in [allocatableBytes]. */
fun enoughSpace(haveBytes: Long, totalBytes: Long, allocatableBytes: Long): Boolean {
    val remaining = (totalBytes - haveBytes).coerceAtLeast(0)
    return allocatableBytes >= remaining + totalBytes + DOWNLOAD_SPACE_MARGIN_BYTES
}

/** The status report's `network`. */
fun networkLabel(caps: NetCaps?): String = when {
    caps == null || !caps.internet || !caps.physical -> "none"
    !caps.notRoaming -> "roaming"
    caps.notMetered -> "unmetered"
    else -> "metered"
}

// ---- resuming --------------------------------------------------------------------------------

/** What to do with a download response (design 13 §5). */
enum class ResumeAction {
    /** 206 starting exactly where the partial file ends: append. */
    APPEND,
    /** 200: the whole file (an older server, or no partial): write it from the start. */
    TRUNCATE,
    /** The file changed (412), the server's range doesn't fit our partial, or a 416 for a partial
     * that isn't the whole file: delete the partial and start over at once (no backoff). */
    RESTART,
    /** 416 for a partial that already is the whole file: verify and install. */
    COMPLETE,
    /** 404: no longer this phone's, or the file is gone on the server - drop it until the list
     * says otherwise. */
    GONE,
    /** Anything else (5xx, ...): try again later, keep the partial. */
    RETRY,
}

/** `Content-Range: bytes 10-99/100` -> (10, 100); a 416's star form (`bytes` star `/100`) ->
 * (null, 100); else `null`. */
fun parseContentRange(header: String?): Pair<Long?, Long?>? {
    val value = header?.trim()?.removePrefix("bytes")?.trim() ?: return null
    val slash = value.indexOf('/')
    if (slash < 0) return null
    val range = value.substring(0, slash).trim()
    val total = value.substring(slash + 1).trim().takeIf { it != "*" }?.toLongOrNull()
    val start = if (range == "*") null else range.substringBefore('-').trim().toLongOrNull() ?: return null
    return start to total
}

/**
 * [haveBytes] the partial file's length (we sent `Range: bytes=<have>-` with the first `ETag` as
 * `If-Match` when it was > 0), [knownTotal] the size an earlier response gave, [contentRange] the
 * response's `Content-Range`.
 */
fun resumeAction(code: Int, haveBytes: Long, knownTotal: Long?, contentRange: String?): ResumeAction = when (code) {
    200 -> ResumeAction.TRUNCATE
    206 -> {
        val start = parseContentRange(contentRange)?.first
        if (haveBytes > 0 && start == haveBytes) ResumeAction.APPEND else ResumeAction.RESTART
    }
    412 -> ResumeAction.RESTART
    416 -> {
        val total = parseContentRange(contentRange)?.second ?: knownTotal
        if (haveBytes > 0 && total == haveBytes) ResumeAction.COMPLETE else ResumeAction.RESTART
    }
    404 -> ResumeAction.GONE
    else -> ResumeAction.RETRY
}

/**
 * The server's `X-Release-Tag` for [tag] (kid-phone-server `device_api::release_tag_header`):
 * visible ASCII except `%` as is, every other byte percent-encoded. Compared as encoded strings.
 */
fun releaseTagHeader(tag: String): String {
    val out = StringBuilder(tag.length)
    for (byte in tag.toByteArray(Charsets.UTF_8)) {
        val b = byte.toInt() and 0xff
        if (b in 0x21..0x7e && b != '%'.code) out.append(b.toChar()) else out.append('%').append("%02X".format(b))
    }
    return out.toString()
}

/** A response's release tag doesn't match the one asked for (QA #2: a sync replaced the file).
 * No header (an older server) can't be checked - the hash and the install's signature check
 * still are. */
fun releaseTagMismatch(headerValue: String?, expectedTag: String): Boolean =
    headerValue != null && headerValue != releaseTagHeader(expectedTag)

// ---- files and records -------------------------------------------------------------------------

/**
 * One download the phone wants (CE prefs `app_downloads`, written with `commit()`): the release
 * from `GET /api/devices/apps`, the first response's `ETag` and size (resume), and when the phone
 * first saw this release (the launcher's grace, the device page's "since"). How often a finished
 * file failed its hash is kept per release in `TrackedAppUpdateState` (qa-13-code #4: a dropped
 * and re-created record must not reset it).
 */
@Serializable
data class DownloadRecord(
    val appId: Long,
    val tag: String,
    val name: String = "",
    val isLauncher: Boolean = false,
    val downloadUrl: String = "",
    /** SHA-256 the server lists (`null` = not known yet: only the install's own checks). */
    val sha256: String? = null,
    val etag: String? = null,
    val total: Long? = null,
    val firstSeenMs: Long = 0,
)

/** The partial file of [appId]'s release [tag]: `<appId>-<16 hex of SHA-256(tag)>.part` - stable
 * across attempts and processes, a new tag a new file. */
fun partialFileName(appId: Long, tag: String): String {
    val digest = MessageDigest.getInstance("SHA-256").digest(tag.toByteArray(Charsets.UTF_8))
    return "$appId-${digest.joinToString("") { "%02x".format(it) }.take(16)}.part"
}

/** What the sync wants downloaded now: one entry per app (the server's id), with its release. */
data class WantedDownload(
    val appId: Long,
    val tag: String,
    val name: String,
    val isLauncher: Boolean,
    val downloadUrl: String,
    val sha256: String?,
)

/**
 * The records after a successful list fetch: exactly the wanted releases - a record of the same
 * release is kept (ETag, size, first seen; the list's URL, name and hash refreshed),
 * a new release gets a fresh record seen [nowMs]; everything else (another tag, deselected,
 * installed, withdrawn) is dropped.
 */
fun reconcileRecords(current: List<DownloadRecord>, wanted: List<WantedDownload>, nowMs: Long): List<DownloadRecord> =
    wanted.distinctBy { it.appId }.map { w ->
        val same = current.firstOrNull { it.appId == w.appId && it.tag == w.tag }
        same?.copy(name = w.name, isLauncher = w.isLauncher, downloadUrl = w.downloadUrl, sha256 = w.sha256)
            ?: DownloadRecord(
                appId = w.appId, tag = w.tag, name = w.name, isLauncher = w.isLauncher,
                downloadUrl = w.downloadUrl, sha256 = w.sha256, firstSeenMs = nowMs,
            )
    }

/**
 * Files in the downloads directory to delete: everything that isn't the partial of a record -
 * never [activeFile] (the runner's current download; it notices a dropped record itself, QA #4).
 */
fun sweepFiles(files: List<String>, records: List<DownloadRecord>, activeFile: String?): List<String> {
    val keep = records.mapTo(mutableSetOf()) { partialFileName(it.appId, it.tag) }
    return files.filter { it !in keep && it != activeFile }
}

/** Records whose release is installed already (the sync's list may be unknown - process start, a
 * failed fetch: only this, never "not advertised", QA #4). */
fun installedRecords(records: List<DownloadRecord>, installedTags: Map<Long, String?>): List<DownloadRecord> =
    records.filter { installedTags[it.appId] == it.tag }

/** The cache files older builds downloaded into (`tracked_app_<id>_<nanos>.apk`) - deleted once
 * an hour old (an older build's install may still read one right after an update). */
const val LEGACY_FILE_AGE_MS = 60 * 60_000L

fun legacyCacheFilesToDelete(files: List<Pair<String, Long>>, nowMs: Long): List<String> =
    files.filter { (name, modifiedMs) ->
        name.startsWith("tracked_app_") && name.endsWith(".apk") && nowMs - modifiedMs >= LEGACY_FILE_AGE_MS
    }.map { it.first }

/** The order the runner takes GO downloads in: catalog apps first, our own update last. */
fun downloadOrder(records: List<DownloadRecord>): List<DownloadRecord> = records.sortedBy { it.isLauncher }

/** A finished file that failed its hash this often waits out the failed-release backoff instead
 * of being downloaded again at once (design 13 §5 allows one immediate restart). Counted per
 * release, across records and backoffs (qa-13-code #4). */
const val MAX_HASH_FAILURES = 2

/**
 * Whether a release may still be downloaded (qa-13-code #3) - checked under the record lock when
 * the sync reconciles and by the runner before each attempt, against the install state read then:
 * not when it is installed or refused, not while an install of the app is between commit and
 * result ([attemptTimeoutMs]), and not our own update when it already waits as the pending APK.
 */
fun releaseStillWanted(
    tag: String,
    isLauncher: Boolean,
    state: TrackedAppState?,
    pendingTag: String?,
    nowMs: Long,
    attemptTimeoutMs: Long,
): Boolean {
    if (state != null) {
        if (tag == state.lastInstalledTag || tag == state.refusedTag) return false
        val attempt = state.attemptStartedAtMs
        if (attempt != null && nowMs - attempt in 0 until attemptTimeoutMs) return false
    }
    return !(isLauncher && pendingTag == tag)
}

/**
 * Whether our own update's commit in this process may still be running (qa-13-code #1): the
 * process committed it and the launcher's attempt is in flight. A failed commit whose result came
 * back to this process ends the attempt, so catalog installs go on.
 */
fun selfUpdateCommitting(committedInThisProcess: Boolean, launcherAttemptStartedAtMs: Long?, nowMs: Long, attemptTimeoutMs: Long): Boolean =
    committedInThisProcess && launcherAttemptStartedAtMs != null && nowMs - launcherAttemptStartedAtMs in 0 until attemptTimeoutMs
