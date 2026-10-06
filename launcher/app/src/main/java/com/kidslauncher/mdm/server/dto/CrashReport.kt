package com.kidslauncher.mdm.server.dto

import kotlinx.serialization.Serializable

/**
 * One launcher crash for `POST /api/devices/crashes` (cleanup 2026-10-06): the stack-trace hash,
 * a short trace of exception class names and frames - never an exception message - how often it
 * happened and when (phone clock), and the build. See [com.kidslauncher.mdm.crash.crashSignature].
 */
@Serializable
data class CrashReport(
    val hash: String,
    val trace: String,
    val count: Int,
    val firstAtMs: Long,
    val lastAtMs: Long,
    val appVersionCode: Long,
)

@Serializable
data class CrashReportBatch(val crashes: List<CrashReport>)
