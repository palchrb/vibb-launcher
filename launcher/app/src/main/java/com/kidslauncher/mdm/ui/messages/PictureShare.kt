package com.kidslauncher.mdm.ui.messages

import android.app.Activity
import android.content.ClipData
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.provider.MediaStore
import android.util.Log
import androidx.core.content.FileProvider
import com.kidslauncher.mdm.R
import java.io.File
import java.io.InputStream
import java.security.SecureRandom

private const val LOG_TAG = "PictureShare"

/**
 * "Lagre" and "Del" on a received picture (design 77 §6.7; the user, 2026-11-09). What leaves the engine's store is
 * always the main process's own JPEG (D8: EXIF-free, re-encoded, never the raw MMS part) - [open] is the engine's
 * `openPart`, which hands out nothing else. Neither the file name nor anything in it says who sent it or when.
 *
 * This is the one kid-screen file that may open the system's share sheet (`KidScreensEscapeTest`): the sheet is a kiosk
 * helper already, and only apps the parent allows resolve in it (suspended and hidden apps don't).
 */
object PictureShare {
    /** `Pictures/Vibb One/` - the user's word for the app. */
    private const val ALBUM = "Pictures/Vibb One"

    /** The share copies' folder in the cache (`res/xml/share_paths.xml`), and how long a copy lives. */
    private const val SHARE_DIR = "share"
    const val SHARE_KEEP_MS = 60 * 60 * 1000L

    private val random = SecureRandom()

    /** A name with nothing in it: no sender, no time. */
    private fun name(): String = "Vibb-" + ByteArray(6).also { random.nextBytes(it) }.joinToString("") { "%02x".format(it.toInt() and 0xff) } + ".jpg"

    /** "Lagre": a copy through MediaStore (our own insert needs no storage permission), pending while it is written.
     * Returns whether it was saved. Not on the main thread. */
    fun save(context: Context, open: () -> InputStream?): Boolean {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, name())
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.RELATIVE_PATH, ALBUM)
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val uri = try {
            resolver.insert(MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), values)
        } catch (e: Exception) {
            Log.w(LOG_TAG, "No MediaStore row: ${e.javaClass.simpleName}")
            null
        } ?: return false
        val written = try {
            val input = open() ?: throw IllegalStateException("no picture")
            input.use { source -> resolver.openOutputStream(uri)?.use { source.copyTo(it) } ?: throw IllegalStateException("no output") }
            true
        } catch (e: Exception) {
            Log.w(LOG_TAG, "Saving the picture failed: ${e.javaClass.simpleName}")
            false
        }
        return try {
            if (written) {
                resolver.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null)
            } else {
                resolver.delete(uri, null, null)
            }
            written
        } catch (e: Exception) {
            false
        }
    }

    /** "Del", step one (not on the main thread): a copy in `cacheDir/share/` for the sheet; `null` if there is no
     * picture. Copies older than an hour go first ([prune]). */
    fun copyForShare(context: Context, open: () -> InputStream?): File? {
        prune(context)
        val dir = File(context.cacheDir, SHARE_DIR).apply { mkdirs() }
        val file = File(dir, name())
        return try {
            val input = open() ?: return null
            input.use { source -> file.outputStream().use { source.copyTo(it) } }
            file
        } catch (e: Exception) {
            Log.w(LOG_TAG, "Copying the picture failed: ${e.javaClass.simpleName}")
            file.delete()
            null
        }
    }

    /** "Del", step two (main thread): `ACTION_SEND` with a read grant on our `FileProvider` URI (not exported) - the
     * grant lasts as long as the receiving screen, the file an hour. */
    fun share(activity: Activity, file: File) {
        val uri = FileProvider.getUriForFile(activity, authority(activity), file)
        val send = Intent(Intent.ACTION_SEND)
            .setType("image/jpeg")
            .putExtra(Intent.EXTRA_STREAM, uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        // The chooser hands the grant on only through the clip.
        send.clipData = ClipData.newRawUri(null, uri)
        try {
            activity.startActivity(Intent.createChooser(send, activity.getString(R.string.picture_share_title)))
        } catch (e: Exception) {
            Log.w(LOG_TAG, "No share sheet: ${e.javaClass.simpleName}")
        }
    }

    /** The share copies older than [SHARE_KEEP_MS] go (at every share, every picture opened, every sync). */
    fun prune(context: Context, now: Long = System.currentTimeMillis()) {
        val dir = File(context.cacheDir, SHARE_DIR)
        dir.listFiles()?.forEach { if (now - it.lastModified() > SHARE_KEEP_MS) it.delete() }
    }

    fun authority(context: Context): String = context.packageName + ".share"
}
