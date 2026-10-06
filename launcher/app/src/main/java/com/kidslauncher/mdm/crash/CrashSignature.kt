package com.kidslauncher.mdm.crash

import com.kidslauncher.mdm.server.dto.CrashReport
import java.security.MessageDigest

/*
 * Crash reports without personal data (cleanup round 2026-10-06), replacing upstream's
 * ReportCrashActivity (a "copy the crash log" screen opened from a crash notification). The phone
 * never offers share, email or a browser for a crash: it records the crash and the next sync sends
 * it to the server, which shows it on the device page. Pure, tested in CrashSignatureTest.
 */

/** Distinct crashes kept on the phone until the server has them (oldest dropped first). */
const val MAX_STORED_CRASHES = 5
/** Frames per exception (the thrown one and each cause). */
const val MAX_FRAMES = 12
/** Causes followed below the thrown exception. */
const val MAX_CAUSES = 4
/** The trace is cut here (the server caps it again). */
const val MAX_TRACE_CHARS = 3000

data class CrashSignature(val hash: String, val trace: String)

/**
 * The trace: for the thrown exception and up to [MAX_CAUSES] causes, the class name and up to
 * [MAX_FRAMES] frames (`at class.method(File.kt:123)`). Never the exception **message** - it can
 * carry phone numbers, names, URLs, file paths or server text. Suppressed exceptions are left out.
 *
 * [CrashSignature.hash]: the first 16 hex chars of SHA-256 over the same chain without line numbers
 * and file names, so a crash keeps its hash across builds that only moved lines.
 */
fun crashSignature(throwable: Throwable): CrashSignature {
    val trace = StringBuilder()
    val identity = StringBuilder()
    var current: Throwable? = throwable
    var depth = 0
    val seen = HashSet<Throwable>()
    while (current != null && depth <= MAX_CAUSES && seen.add(current)) {
        val name = current.javaClass.name
        trace.append(if (depth == 0) name else "Caused by: $name").append('\n')
        identity.append(name).append('\n')
        val frames = current.stackTrace
        for (frame in frames.take(MAX_FRAMES)) {
            val where = if (frame.fileName != null && frame.lineNumber >= 0) "${frame.fileName}:${frame.lineNumber}" else frame.fileName ?: "Unknown Source"
            trace.append("    at ").append(frame.className).append('.').append(frame.methodName).append('(').append(where).append(")\n")
            identity.append(frame.className).append('.').append(frame.methodName).append('\n')
        }
        if (frames.size > MAX_FRAMES) trace.append("    ... ").append(frames.size - MAX_FRAMES).append(" more\n")
        current = current.cause
        depth++
    }
    val digest = MessageDigest.getInstance("SHA-256").digest(identity.toString().toByteArray(Charsets.UTF_8))
    val hash = digest.joinToString("") { "%02x".format(it) }.take(16)
    return CrashSignature(hash, trace.toString().take(MAX_TRACE_CHARS))
}

/** Adds one crash: the same hash counts up (newest trace and build), a new one is added; at most
 * [MAX_STORED_CRASHES], the one seen longest ago dropped first. Newest first. */
fun recordCrash(stored: List<CrashReport>, signature: CrashSignature, nowMs: Long, appVersionCode: Long): List<CrashReport> {
    val existing = stored.firstOrNull { it.hash == signature.hash }
    val updated = if (existing != null) {
        existing.copy(
            trace = signature.trace,
            count = (existing.count.toLong() + 1).coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
            lastAtMs = nowMs,
            appVersionCode = appVersionCode,
        )
    } else {
        CrashReport(signature.hash, signature.trace, 1, nowMs, nowMs, appVersionCode)
    }
    return (listOf(updated) + stored.filter { it.hash != signature.hash })
        .sortedByDescending { it.lastAtMs }
        .take(MAX_STORED_CRASHES)
}

/** What stays after the server accepted [sent]: only entries that changed since (a crash recorded
 * by a process that started meanwhile). */
fun afterUpload(stored: List<CrashReport>, sent: List<CrashReport>): List<CrashReport> = stored.filter { it !in sent }
