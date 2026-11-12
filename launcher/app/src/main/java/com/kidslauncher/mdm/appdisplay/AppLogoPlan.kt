package com.kidslauncher.mdm.appdisplay

import com.kidslauncher.mdm.server.ServerJson
import com.kidslauncher.mdm.server.dto.APP_LOGOS_MAX_WANTED
import com.kidslauncher.mdm.server.dto.APP_LOGO_MAX_BYTES
import com.kidslauncher.mdm.server.dto.InstalledApp
import com.kidslauncher.mdm.server.dto.WantedLogos
import kotlinx.serialization.Serializable
import java.io.File
import java.io.OutputStream
import java.security.MessageDigest

/*
 * Design 87 step 2 (`docs/design/87-app-logos.md` §3, §4.2, §4.4): the pure rules of the apps' logos on the phone -
 * the cache's key and index, which apps are drawn again, what the report carries, how the status answer is read and
 * what is uploaded, within which time. No Android imports: [AppLogos] does the drawing and the calls.
 */

/** Bumped when our drawing of a logo changes ([com.kidslauncher.mdm.ui.home.KidAvatars.drawOwnLogo], the size, the
 * configuration it is loaded in): every app is drawn again and reported under its new hash. */
const val LOGO_RENDER_VERSION = 1

/** The most drawing one sync spends (§3); what doesn't fit is drawn at the next. */
const val LOGO_DRAW_BUDGET_MS = 1_500L

/** All uploads of one sync (§4.4), and each call's own limit. */
const val LOGO_UPLOAD_BUDGET_MS = 20_000L
const val LOGO_UPLOAD_CALL_MS = 10_000L

/** The most of the status answer's body the phone reads (R2-L7); a longer body is unreadable, nothing wanted. */
const val WANTED_LOGOS_MAX_BODY = 16 * 1024

/** What an app's logo was drawn under (§3): an app update, a system update or a change to our own drawing draws it
 * again. No night bit - the drawing is made in a fixed light configuration (R2-M3). */
@Serializable
data class AppLogoKey(
    val versionCode: Long,
    val lastUpdateTime: Long,
    val fingerprint: String,
    val render: Int = LOGO_RENDER_VERSION,
)

/** One app in the cache's index: its [key] and the logo's [hash] - `null` when the draw failed or the PNG was over
 * [APP_LOGO_MAX_BYTES] (never reported; drawn again only when the key changes). */
@Serializable
data class AppLogoEntry(
    val key: AppLogoKey,
    val hash: String? = null,
)

/** The cache's `index.json`: package -> entry. */
@Serializable
data class AppLogoIndex(
    val apps: Map<String, AppLogoEntry> = emptyMap(),
)

/** The index from its file's text: an absent, unreadable or foreign one is empty (everything is drawn again, within
 * the budget). */
fun decodeLogoIndex(text: String?): AppLogoIndex = try {
    text?.let { ServerJson.decodeFromString(AppLogoIndex.serializer(), it) } ?: AppLogoIndex()
} catch (e: Exception) {
    AppLogoIndex()
}

fun encodeLogoIndex(index: AppLogoIndex): String = ServerJson.encodeToString(AppLogoIndex.serializer(), index)

/** What a sync does with the cache: [index] without the packages no longer installed, and the packages to draw, in
 * [current]'s order - those without an entry, whose key changed, or whose logo's file is gone from [files] (the hashes
 * on disk). A failed draw (no hash) under the same key is not drawn again. */
data class LogoRefreshPlan(val index: AppLogoIndex, val toDraw: List<String>)

fun logoRefreshPlan(index: AppLogoIndex, current: Map<String, AppLogoKey>, files: Set<String>): LogoRefreshPlan {
    val kept = index.apps.filterKeys { it in current }
    val toDraw = current.filter { (pkg, key) ->
        val entry = kept[pkg]
        entry == null || entry.key != key || (entry.hash != null && entry.hash !in files)
    }.keys.toList()
    return LogoRefreshPlan(AppLogoIndex(kept), toDraw)
}

/** The hash of a drawn logo's [png] - SHA-256, lowercase hex, the server's `app_logos::hash` - or `null` when the
 * PNG is over the upload limit (R2-L1: never reported). */
fun logoHashOf(png: ByteArray): String? = if (png.size > APP_LOGO_MAX_BYTES) null else sha256Hex(png)

fun sha256Hex(bytes: ByteArray): String =
    MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

/** 64 lowercase hex characters (the server's `photos::is_valid_hash`). */
fun isLogoHash(text: String): Boolean = text.length == 64 && text.all { it in '0'..'9' || it in 'a'..'f' }

/** The logos the report carries (§3): package -> hash for each app whose entry was drawn under its [current] key,
 * has a hash and whose file is there ([files], the hashes on disk). Anything else is absent - "nothing to say this
 * time", never "no logo". */
fun reportedLogos(index: AppLogoIndex, current: Map<String, AppLogoKey>, files: Set<String>): Map<String, String> =
    index.apps.mapNotNull { (pkg, entry) ->
        val hash = entry.hash
        if (hash != null && current[pkg] == entry.key && hash in files) pkg to hash else null
    }.toMap()

/** [apps] with each reported [logos]' hash in `logo`. */
fun withLogos(apps: List<InstalledApp>, logos: Map<String, String>): List<InstalledApp> =
    apps.map { app -> logos[app.packageName]?.let { app.copy(logo = it) } ?: app }

/** The cache's files no index entry names (a removed package's, an app's old logo) and stray temp files: deleted. */
fun unnamedLogoFiles(index: AppLogoIndex, fileNames: Collection<String>): List<String> {
    val named = index.apps.values.mapNotNullTo(HashSet()) { it.hash?.let { hash -> "$hash.png" } }
    return fileNames.filter { it != LOGO_INDEX_FILE && it !in named }
}

const val LOGO_INDEX_FILE = "index.json"

/**
 * The status answer as the phone reads it (R2-L7): the wanted hashes of a `200` [code]'s [body] (the first
 * [WANTED_LOGOS_MAX_BODY] + 1 bytes read, so a longer one shows). A `204`, any other code, an empty, too long or
 * unreadable body is nothing wanted. A newer server's other keys are ignored.
 */
fun wantedLogosOf(code: Int, body: ByteArray?): List<String> {
    if (code != 200 || body == null || body.isEmpty() || body.size > WANTED_LOGOS_MAX_BODY) return emptyList()
    return try {
        ServerJson.decodeFromString(WantedLogos.serializer(), body.decodeToString()).wantedLogos
    } catch (e: Exception) {
        emptyList()
    }
}

/** What is uploaded (§4.2): of the [wanted] list at most [APP_LOGOS_MAX_WANTED] entries, and of those only a valid
 * hash this phone has drawn ([cached]) - once each. A hostile server can make the phone send only its own logos. */
fun logoUploadPlan(wanted: List<String>, cached: Set<String>): List<String> =
    wanted.take(APP_LOGOS_MAX_WANTED).filter { isLogoHash(it) && it in cached }.distinct()

/** After one upload: any answer (`204`, a `409`, a `422`, ...) goes on to the next - "not this time" for that one; no
 * answer (a network error, the call's time out) ends the step (§4.4). */
fun uploadGoesOn(code: Int?): Boolean = code != null

/** The next call's limit when the step started at [startedMs] and it is [nowMs] (one monotonic clock): at most
 * [LOGO_UPLOAD_CALL_MS], at most what is left of [LOGO_UPLOAD_BUDGET_MS]; `null` when nothing is left. */
fun uploadCallTimeoutMs(startedMs: Long, nowMs: Long): Long? {
    val left = LOGO_UPLOAD_BUDGET_MS - (nowMs - startedMs)
    return if (left <= 0) null else minOf(LOGO_UPLOAD_CALL_MS, left)
}

/** Runs [step] on [items] in order while less than [budgetMs] has passed on [nowMs] since the start (checked before
 * each); returns how many ran. The drawing's budget (§3). */
fun <T> runWithin(items: List<T>, budgetMs: Long, nowMs: () -> Long, step: (T) -> Unit): Int {
    val started = nowMs()
    var done = 0
    for (item in items) {
        if (nowMs() - started >= budgetMs) break
        step(item)
        done++
    }
    return done
}

/** Writes [target] through a temp file beside it and a rename (R2-L10): a write that fails or is killed midway leaves
 * the old file as it was. Throws on failure, with the temp file removed. */
fun writeReplacing(target: File, write: (OutputStream) -> Unit) {
    val temp = File(target.parentFile, "${target.name}.tmp")
    try {
        java.io.FileOutputStream(temp).use { out ->
            write(out)
            out.flush()
            out.fd.sync()
        }
        if (!temp.renameTo(target)) throw java.io.IOException("rename")
    } catch (e: Exception) {
        temp.delete()
        throw e
    }
}
