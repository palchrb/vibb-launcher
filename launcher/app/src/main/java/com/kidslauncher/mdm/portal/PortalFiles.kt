package com.kidslauncher.mdm.portal

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/*
 * Design 102 §3.1: what passes between Vibb One's main process and the window's `:portal` process - files in
 * `noBackupFilesDir/portal/` (never in a backup, `allowBackup="false"`), never preferences (multi-process
 * SharedPreferences aren't coherent, Q102-M2):
 * - `state.json`, the snapshot the main process writes before it starts the window ([PortalSnapshot]);
 * - `sessions/<opened_ms>.json`, the window's session ([PortalSessionFile]), written at its start and at its end - "a
 *   window is open" is read from it, so one window at a time holds across a main-process crash (R3-L5);
 * - `revisit.json`, the reopen's records ([RevisitRecord], main process only).
 */

internal val PortalJson = Json { ignoreUnknownKeys = true; encodeDefaults = true }

/** The snapshot for one opening: no code, no approval state (the main process approved before the start). */
@Serializable
data class PortalSnapshot(
    val openedMs: Long,
    /** [PortalKind.wire]. */
    val kind: String,
    /** `system`, `vibb` or `revisit`. */
    val start: String,
    /** `code`, `grant`, `pause` or `none`. */
    val approved: String,
    /** The first URL to load: Android's portal URL, the probe URL (the binderless start) or the recorded page. */
    val url: String,
    /** A reopen keeps to this site alone. */
    val recordedSite: String? = null,
    /** The network's name, when Android gave it. */
    val network: String? = null,
    val userAgent: String? = null,
    val minutes: Int,
    /** The running PIN pause's end (wall ms), `null` without one: the fallback when Vibb One's "close now" is lost. */
    val pauseEndMs: Long? = null,
    /** The filter's lists apply (the phone's filter on, no pause). */
    val filterOn: Boolean,
    /** `classifyDomain`'s inputs: the compiled list's file, this phone plays Spotify. The secure-DNS hosts come from the
     * APK's asset. The parent's allowed domains are already out of the compiled list (the server leaves them out). */
    val blocklistPath: String? = null,
    val spotify: Boolean = false,
    /** The phone's own server as one operator of the https check (R3-L4): a public `https://` address only. */
    val serverCheckUrl: String? = null,
)

/** The window's session, as it goes back (§6.2's session, plus whether it ended). */
@Serializable
data class PortalSessionFile(
    val openedMs: Long,
    val closedMs: Long? = null,
    val network: String? = null,
    val firstHost: String? = null,
    /** The first page's site, for the reopen's record (only a working sign-in's is kept). */
    val firstSite: String? = null,
    /** The first page's URL, for the reopen; never logged, never reported. */
    val firstUrl: String? = null,
    val outcome: String? = null,
    val visited: List<String> = emptyList(),
    val visitedCount: Int = 0,
    val blockedHosts: List<String> = emptyList(),
    val blockedFrames: List<String> = emptyList(),
    val start: String,
    val approved: String,
    val kind: String,
    /** A window opened in a pause runs until the pause's end, not its minutes (code QA M2): until then it is open. */
    val pauseEndMs: Long? = null,
)

/** The files' places. */
object PortalFiles {
    fun dir(noBackup: File): File = File(noBackup, "portal")
    fun snapshot(noBackup: File): File = File(dir(noBackup), "state.json")
    fun sessions(noBackup: File): File = File(dir(noBackup), "sessions")
    fun session(noBackup: File, openedMs: Long): File = File(sessions(noBackup), "$openedMs.json")
    fun revisit(noBackup: File): File = File(dir(noBackup), "revisit.json")

    /** Through a temp file and a rename: a kill mid-write leaves the old file, never a torn one. */
    fun write(file: File, text: String) {
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, "${file.name}.tmp")
        tmp.writeText(text)
        if (!tmp.renameTo(file)) {
            file.writeText(text)
            tmp.delete()
        }
    }

    fun readSnapshot(noBackup: File): PortalSnapshot? = runCatching {
        PortalJson.decodeFromString(PortalSnapshot.serializer(), snapshot(noBackup).readText())
    }.getOrNull()

    fun writeSession(noBackup: File, session: PortalSessionFile) =
        write(session(noBackup, session.openedMs), PortalJson.encodeToString(PortalSessionFile.serializer(), session))

    fun readSessions(noBackup: File): List<PortalSessionFile> =
        (sessions(noBackup).listFiles { f -> f.name.endsWith(".json") } ?: emptyArray()).mapNotNull { f ->
            runCatching { PortalJson.decodeFromString(PortalSessionFile.serializer(), f.readText()) }.getOrNull()
        }.sortedBy { it.openedMs }
}

/** A session file still open: not closed, and within its minutes - or a pause window's end - and a minute's grace (a
 * window the system killed without its end leaves an open file; past that it counts as closed, R3-L5). A window opened
 * in a pause has no 10 minutes while the pause lasts (code QA M2): its slot isn't freed for a second window then. */
fun sessionOpen(session: PortalSessionFile, nowMs: Long, minutes: Int): Boolean {
    if (session.closedMs != null || nowMs < session.openedMs) return false
    val until = maxOf(session.openedMs + minutes * 60_000L, session.pauseEndMs ?: 0L) + 60_000L
    return nowMs <= until
}
