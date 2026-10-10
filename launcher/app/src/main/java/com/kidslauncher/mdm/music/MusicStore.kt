package com.kidslauncher.mdm.music

import android.content.Context
import android.content.pm.PackageManager
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import java.io.File
import java.io.IOException

/**
 * What the launcher holds for the music app (design 21 §3, 21b §5): the library and the listings
 * (filesDir/music), covers, own files downloaded and not yet handed over (noBackupFilesDir/music_files,
 * never backed up), and small state in the `music_bridge` prefs: the files handed over (id -> SHA-256),
 * the ready files' hashes, partial downloads' ETag and size, the music app's last report, and what
 * plays (for Home and the lock in step 4; never sent to the server).
 */
object MusicStore {
    private val json = Json { ignoreUnknownKeys = true }
    private val longMap = MapSerializer(Long.serializer(), String.serializer())

    fun dir(context: Context) = File(context.filesDir, "music").apply { mkdirs() }
    fun libraryFile(context: Context) = File(dir(context), "library.json")
    fun itemsDir(context: Context) = File(dir(context), "items").apply { mkdirs() }
    fun listingFile(context: Context, entryId: Long) = File(itemsDir(context), "$entryId.json")
    fun coversDir(context: Context) = File(dir(context), "covers").apply { mkdirs() }
    fun coverFile(context: Context, hash: String) = File(coversDir(context), hash)
    fun filesDir(context: Context) = File(context.noBackupFilesDir, "music_files").apply { mkdirs() }
    fun readyFile(context: Context, id: Long) = File(filesDir(context), id.toString())
    fun partialFile(context: Context, id: Long) = File(filesDir(context), "$id.part")
    fun nowPlayingArt(context: Context) = File(dir(context), "now_playing_art.jpg")

    fun prefs(context: Context) = context.applicationContext.getSharedPreferences("music_bridge", Context.MODE_PRIVATE)

    /** The installed music app (release first), or null. */
    fun installedPackage(context: Context): String? = MUSIC_PACKAGES.firstOrNull { isInstalled(context, it) }

    fun isInstalled(context: Context, pkg: String): Boolean = try {
        context.packageManager.getPackageInfo(pkg, 0)
        true
    } catch (e: PackageManager.NameNotFoundException) {
        false
    }

    fun versionCode(context: Context, pkg: String): Long? = try {
        context.packageManager.getPackageInfo(pkg, 0).longVersionCode
    } catch (e: PackageManager.NameNotFoundException) {
        null
    }

    @Synchronized
    fun head(context: Context): LibraryHead? = runCatching { libraryFile(context).readText() }.getOrNull()?.let(::libraryHead)

    /** Entry id -> the version of the listing held. */
    fun listings(context: Context): Map<Long, String> = itemsDir(context).listFiles().orEmpty().mapNotNull { f ->
        val id = f.name.removeSuffix(".json").toLongOrNull() ?: return@mapNotNull null
        val version = runCatching { listingVersionOf(f.readText()) }.getOrNull() ?: return@mapNotNull null
        id to version
    }.toMap()

    private fun map(context: Context, key: String): Map<Long, String> =
        prefs(context).getString(key, null)?.let { runCatching { json.decodeFromString(longMap, it) }.getOrNull() }.orEmpty()

    private fun putMap(context: Context, key: String, value: Map<Long, String>) {
        prefs(context).edit().putString(key, json.encodeToString(longMap, value)).commit()
    }

    /** Files the music app holds (id -> SHA-256): never downloaded again. */
    @Synchronized
    fun handedOver(context: Context): Map<Long, String> = map(context, "handed")

    @Synchronized
    fun setHandedOver(context: Context, value: Map<Long, String>) = putMap(context, "handed", value)

    /** Downloaded files waiting for the music app (id -> SHA-256, checked). */
    @Synchronized
    fun ready(context: Context): Map<Long, String> = map(context, "ready").filterKeys { readyFile(context, it).isFile }

    @Synchronized
    fun setReady(context: Context, value: Map<Long, String>) = putMap(context, "ready", value)

    /** A partial download's first ETag and size (design 13: resumable only with them). */
    @Synchronized
    fun partialState(context: Context, id: Long): Pair<String?, Long?> {
        val p = prefs(context)
        return p.getString("etag_$id", null) to p.getLong("total_$id", -1).takeIf { it >= 0 }
    }

    @Synchronized
    fun setPartialState(context: Context, id: Long, etag: String?, total: Long?) {
        val e = prefs(context).edit()
        if (etag == null) e.remove("etag_$id") else e.putString("etag_$id", etag)
        if (total == null) e.remove("total_$id") else e.putLong("total_$id", total)
        e.commit()
    }

    /** The music app's last report (`reportState`) and when it came. */
    fun report(context: Context): Pair<JsonObject?, Long?> {
        val p = prefs(context)
        val obj = p.getString("report", null)?.let { runCatching { json.parseToJsonElement(it) as? JsonObject }.getOrNull() }
        return obj to p.getLong("report_at", -1).takeIf { it >= 0 }
    }

    fun setReport(context: Context, report: String, atMs: Long) {
        prefs(context).edit().putString("report", report).putLong("report_at", atMs).apply()
    }

    /** When the last `setNowPlaying` said "playing" (null = not playing). */
    fun playingSince(context: Context): Long? = prefs(context).getLong("playing_since", -1).takeIf { it >= 0 }

    /** Write [bytes] to [file] atomically (a temp file of its own, renamed over it). */
    fun writeAtomic(file: File, bytes: ByteArray) {
        file.parentFile?.mkdirs()
        val tmp = File.createTempFile(".${file.name}.", ".tmp", file.parentFile)
        try {
            tmp.writeBytes(bytes)
            if (!tmp.renameTo(file)) throw IOException("rename to $file failed")
        } finally {
            tmp.delete()
        }
    }
}
