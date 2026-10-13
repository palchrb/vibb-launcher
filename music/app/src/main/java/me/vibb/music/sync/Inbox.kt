package me.vibb.music.sync

import me.vibb.music.data.Store
import me.vibb.music.model.LibraryParser
import me.vibb.music.model.OwnFile
import java.io.File
import java.io.InputStream
import java.security.MessageDigest

/**
 * What the launcher hands over (design 21 §2; step 3's bridge, step 2's debug feed): the library,
 * listings, covers and own files, each checked before it lands. The sync applies them.
 */
object Inbox {
    private const val MAX_COVER_BYTES = 10L * 1024 * 1024
    private const val MAX_FILE_BYTES = 1L shl 30

    /** A new library; refused (false) unless it is a `v: 1` library. */
    fun putLibrary(store: Store, text: String): Boolean {
        if (LibraryParser.parse(text) == null) return false
        Store.writeAtomic(store.libraryFile, text.toByteArray())
        store.reloadLibrary()
        return true
    }

    /** A listing for [entryId], applied (or reported as `bad_listing`) by the next sync. */
    fun putListing(store: Store, entryId: Long, text: String) {
        Store.writeAtomic(File(store.inboxDir, "$entryId.json"), text.toByteArray())
    }

    /** A cover (or an episode's picture, design 27), stored under its hash only when the bytes hash to it. */
    fun putCover(store: Store, hash: String, input: InputStream): Boolean =
        copyChecked(input, store.coverFile(hash), MAX_COVER_BYTES) { size, sha -> size > 0 && sha == hash }
            .also { if (it) store.coversChanged() }

    /**
     * An own file's copy: size and SHA-256 must match the library (21 §2 "Own files"). The checked
     * SHA-256 is recorded beside it (design 29 step 4), so a reused file id is caught later.
     */
    fun putOwnFile(store: Store, file: OwnFile, input: InputStream): Boolean =
        copyChecked(input, store.ownFile(file.id), MAX_FILE_BYTES) { size, sha -> size == file.size && sha == file.sha256 }
            .also { if (it) store.recordOwnSha(file.id, file.sha256) }

    /** [file]'s SHA-256 (lowercase hex), or null when it can't be read. */
    fun sha256(file: File): String? = runCatching {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                digest.update(buf, 0, n)
            }
        }
        hex(digest)
    }.getOrNull()

    private fun hex(digest: MessageDigest): String = digest.digest().joinToString("") { "%02x".format(it) }

    private fun copyChecked(input: InputStream, dest: File, max: Long, ok: (Long, String) -> Boolean): Boolean {
        dest.parentFile?.mkdirs()
        val part = File(dest.parentFile, ".${dest.name}.part")
        val digest = MessageDigest.getInstance("SHA-256")
        var size = 0L
        try {
            part.outputStream().use { out ->
                val buf = ByteArray(64 * 1024)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    size += n
                    if (size > max) return false
                    digest.update(buf, 0, n)
                    out.write(buf, 0, n)
                }
            }
            return ok(size, hex(digest)) && part.renameTo(dest)
        } finally {
            part.delete()
        }
    }
}
