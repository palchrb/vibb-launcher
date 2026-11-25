package com.kidslauncher.mdm.server

/*
 * Why a catalog install failed, for the parent's Device › Apps (upstream review 2026-11-20,
 * docs/review/2026-11-20-upstream-review.md): the failure report's `reason` is a short code, never
 * Android's free text (it names paths and packages). The server keeps only this form and words it
 * (`install_failure.rs`). The contract is `install_failure.json`, in
 * `contracts/core/` (InstallFailureTest). Pure, no Android imports.
 */

/** The launcher's own reasons, before Android was asked. */
object InstallFailureKind {
    /** The download failed the server's hash again ([AppDownloads]' MAX_HASH_FAILURES). */
    const val DOWNLOAD_CORRUPT = "download_corrupt"
    /** The APK failed [catalogApkCheck] (another package or key, unreadable, no pins). */
    const val APK_REFUSED = "apk_refused"
    /** The install session couldn't be started. */
    const val NOT_STARTED = "not_started"

    val ALL = listOf(APK_REFUSED, DOWNLOAD_CORRUPT, NOT_STARTED)
}

/** The longest code sent (the server keeps none longer). */
const val INSTALL_FAILURE_MAX_CODE = 80

private val ANDROID_CODE = Regex("""\bINSTALL_(?:PARSE_)?FAILED_[A-Z0-9_]+""")

/** `PackageInstaller.STATUS_FAILURE_*` (public constants, literal here to stay Android-free) by name. */
private val STATUS_NAMES = mapOf(
    2 to "STATUS_FAILURE_BLOCKED",
    3 to "STATUS_FAILURE_ABORTED",
    4 to "STATUS_FAILURE_INVALID",
    5 to "STATUS_FAILURE_CONFLICT",
    6 to "STATUS_FAILURE_STORAGE",
    7 to "STATUS_FAILURE_INCOMPATIBLE",
    8 to "STATUS_FAILURE_TIMEOUT",
)

/**
 * A `PackageInstaller` result's reason: Android's `INSTALL_FAILED_*`/`INSTALL_PARSE_FAILED_*` code
 * name, the first such upper-case token anywhere in its status message (`EXTRA_STATUS_MESSAGE`,
 * "INSTALL_FAILED_OLDER_SDK: Requires ..."), else
 * the status's `STATUS_FAILURE_*` name, else none (the plain `STATUS_FAILURE`, a user action).
 */
fun installFailureReason(status: Int, message: String?): String? {
    message?.let { ANDROID_CODE.find(it) }?.value?.takeIf { it.length <= INSTALL_FAILURE_MAX_CODE }?.let { return it }
    return STATUS_NAMES[status]
}
