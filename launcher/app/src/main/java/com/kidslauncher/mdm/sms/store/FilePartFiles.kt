package com.kidslauncher.mdm.sms.store

import com.kidslauncher.mdm.sms.engine.PartFiles
import java.io.File
import java.io.InputStream
import java.security.MessageDigest

/**
 * The picture files (design 77 §6.4, D8): every file here is a JPEG the main process encoded itself - a received
 * picture from the isolated process's pixels, or one the kid picked - named by the SHA-256 of its bytes, in CE storage
 * (`files/sms/pictures/`, never backed up: `allowBackup="false"`). A name that isn't 64 lowercase hex is never opened, so
 * no path leaves the folder. Written to a temporary name and renamed, so a half-written file never has a hash's name.
 */
class FilePartFiles(private val dir: File) : PartFiles {
    override fun put(bytes: ByteArray): String {
        val sha = sha256(bytes)
        val file = File(dir.apply { mkdirs() }, sha)
        if (!file.exists() || file.length() != bytes.size.toLong()) {
            val part = File(dir, "$sha.part")
            part.writeBytes(bytes)
            if (!part.renameTo(file)) {
                part.delete()
                throw java.io.IOException("picture not stored")
            }
        } else {
            // Kept again: a pick of the same picture starts its day anew.
            file.setLastModified(System.currentTimeMillis())
        }
        return sha
    }

    override fun open(sha256: String): InputStream? = fileOf(sha256)?.takeIf { it.isFile }?.inputStream()

    override fun read(sha256: String): ByteArray? = fileOf(sha256)?.takeIf { it.isFile }?.readBytes()

    override fun size(sha256: String): Long? = fileOf(sha256)?.takeIf { it.isFile }?.length()

    override fun delete(sha256: String) {
        fileOf(sha256)?.delete()
    }

    override fun all(): Map<String, Long> = dir.listFiles().orEmpty()
        .onEach { if (it.name.endsWith(".part") && System.currentTimeMillis() - it.lastModified() > STALE_PART_MS) it.delete() }
        .filter { HASH.matches(it.name) }
        .associate { it.name to it.lastModified() }

    private fun fileOf(sha256: String): File? = if (HASH.matches(sha256)) File(dir, sha256) else null

    companion object {
        private val HASH = Regex("[0-9a-f]{64}")
        private const val STALE_PART_MS = 60 * 60 * 1000L

        fun sha256(bytes: ByteArray): String =
            MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }
}
