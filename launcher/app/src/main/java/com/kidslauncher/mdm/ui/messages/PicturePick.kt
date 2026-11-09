package com.kidslauncher.mdm.ui.messages

import android.app.Activity
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.provider.MediaStore
import android.util.Log
import androidx.core.content.FileProvider
import com.kidslauncher.mdm.sms.SmsRuntime
import com.kidslauncher.mdm.sms.engine.PictureRef
import com.kidslauncher.mdm.sms.parse.MmsParseProtocol
import com.kidslauncher.mdm.sms.parse.MmsParser
import com.kidslauncher.mdm.sms.parse.PictureEncoder
import java.io.File
import java.security.SecureRandom

private const val LOG_TAG = "PicturePick"

/**
 * The compose bar's "+" (design 77 D11): where a picture to send comes from, and how it becomes one the engine holds.
 * This is the one kid-screen file that may open the system's photo picker or a camera (`KidScreensEscapeTest`).
 *
 * - **Velg bilde**: the system photo picker (`ACTION_PICK_IMAGES`): no storage permission, one picture, and a kiosk
 *   helper lock task already allows (`HelperKind.PHOTO_PICKER`).
 * - **Ta bilde**: the camera app the parent allows - `ACTION_IMAGE_CAPTURE` sent to a handler that is usable now
 *   (installed, not suspended, on the allowlist: the contact sheet's [com.kidslauncher.mdm.calls.ContactSheet.usablePackages]),
 *   writing to a file of ours through the share provider (a write grant for that one file). Not an in-app capture: no
 *   new dependency, no camera code in the Device Owner's process, and the parent's choices hold - an app they didn't
 *   allow, or whose camera they switched off (design 68), isn't offered or can't take it. With no usable camera only
 *   the picker is offered.
 *
 * Either way the main process never decodes the picked file: its bytes go to the isolated process (D8), its pixels come
 * back, and the main process encodes its own JPEG under the carrier's limit (EXIF and GPS gone, QA I5).
 */
object PicturePick {
    /** The camera's output files, in `cacheDir/capture/` (the share provider's `capture` path), each gone once read. */
    private const val CAPTURE_DIR = "capture"
    private const val CAPTURE_KEEP_MS = 60 * 60 * 1000L

    private val random = SecureRandom()

    /** The system photo picker, one image - sent to the system's own picker package, never to another app that declares
     * the same action (code QA M3); `null` when there is none. */
    fun pickIntent(context: Context): Intent? {
        val pick = Intent(MediaStore.ACTION_PICK_IMAGES).setType("image/*")
        val system = try {
            context.packageManager.queryIntentActivities(pick, PackageManager.MATCH_SYSTEM_ONLY)
                .firstOrNull { it.activityInfo?.applicationInfo?.let { info -> info.flags and ApplicationInfo.FLAG_SYSTEM != 0 } == true }
                ?.activityInfo?.packageName
        } catch (e: Exception) {
            null
        } ?: return null
        return pick.setPackage(system)
    }

    /** Where a picture to send comes from: the picker's answer (checked by [pickedUriAcceptable]), or our own camera
     * file (read as the file it is, never through a URI the answer could choose). */
    sealed interface Source {
        data class Picked(val uri: Uri) : Source

        data class Captured(val file: File) : Source
    }

    /** Code QA M3: the picker's answer is read only as a `content://` URI of another app - never a `file://`, never one
     * of our own providers (the launcher would read its own files on the answer's say-so). */
    fun picked(context: Context, uri: Uri?): Source? =
        uri?.takeIf { pickedUriAcceptable(it.scheme, it.authority, context.packageName) }?.let { Source.Picked(it) }

    /** The camera app the parent allows (a system one first), or `null`: then "Ta bilde" isn't offered. */
    fun cameraPackage(activity: Activity): String? {
        val usable = com.kidslauncher.mdm.calls.ContactSheet.usablePackages(activity)
        val handlers = try {
            activity.packageManager.queryIntentActivities(Intent(MediaStore.ACTION_IMAGE_CAPTURE), PackageManager.MATCH_DEFAULT_ONLY)
                .mapNotNull { it.activityInfo?.applicationInfo }
        } catch (e: Exception) {
            emptyList()
        }
        return handlers.filter { it.packageName != activity.packageName && usable(it.packageName) }
            .sortedByDescending { it.flags and ApplicationInfo.FLAG_SYSTEM != 0 }
            .firstOrNull()?.packageName
    }

    /** A fresh output file for the camera and its URI (write granted to [pkg] alone, for this capture). */
    fun captureIntent(context: Context, pkg: String): Pair<Intent, File> {
        prune(context)
        val dir = File(context.cacheDir, CAPTURE_DIR).apply { mkdirs() }
        val file = File(dir, "c" + ByteArray(8).also { random.nextBytes(it) }.joinToString("") { "%02x".format(it.toInt() and 0xff) } + ".jpg")
        val uri = FileProvider.getUriForFile(context, PictureShare.authority(context), file)
        val intent = Intent(MediaStore.ACTION_IMAGE_CAPTURE)
            .setPackage(pkg)
            .putExtra(MediaStore.EXTRA_OUTPUT, uri)
            // Write only: the camera fills our empty file and needs nothing else (code QA N5).
            .addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        intent.clipData = ClipData.newRawUri(null, uri)
        return intent to file
    }

    /** A picture ready to send: the engine's reference to our JPEG and a small preview of it. */
    class Prepared(val ref: PictureRef, val preview: Bitmap)

    /** Why a pick didn't become a picture. */
    enum class Problem { UNREADABLE, TOO_LARGE, UNAVAILABLE }

    /**
     * Not on the main thread, nor the engine's: the picked file's bytes (at most 30 MB, never decoded here) to the
     * isolated process, its pixels back, our own JPEG under the carrier's limit ([PictureEncoder.sendLimit]), held by
     * the engine until a send takes it.
     */
    fun prepare(context: Context, source: Source, subId: Int?): Pair<Prepared?, Problem?> = try {
        prepareOrThrow(context, source, subId)
    } catch (e: Exception) {
        // Verification V1: whatever fails here fails the pick calmly - never the `sms-picture` thread, which would take
        // the launcher (Home, the dialer, the Device Owner) with it.
        Log.w(LOG_TAG, "The picked picture couldn't be made ready: ${e.javaClass.simpleName}")
        null to Problem.UNREADABLE
    }

    private fun prepareOrThrow(context: Context, source: Source, subId: Int?): Pair<Prepared?, Problem?> {
        val app = context.applicationContext
        val bytes = try {
            when (source) {
                is Source.Picked -> app.contentResolver.openInputStream(source.uri)?.use { it.readNBytes(MmsParseProtocol.PICK_MAX_BYTES + 1) }
                is Source.Captured -> source.file.inputStream().use { it.readNBytes(MmsParseProtocol.PICK_MAX_BYTES + 1) }
            }
        } catch (e: Exception) {
            Log.w(LOG_TAG, "The picked picture couldn't be read: ${e.javaClass.simpleName}")
            null
        } ?: return null to Problem.UNREADABLE
        if (bytes.size > MmsParseProtocol.PICK_MAX_BYTES) return null to Problem.TOO_LARGE
        val pixels = when (val answer = MmsParser.decodeImage(app, bytes, MmsParseProtocol.PICK_MAX_PX)) {
            is MmsParser.Answer.Ok -> answer.value
            MmsParser.Answer.Unavailable -> return null to Problem.UNAVAILABLE
            else -> return null to Problem.UNREADABLE
        }
        try {
            // The conversation's own SIM, the one the send goes out on (code QA N3).
            val limit = PictureEncoder.sendLimit(SmsRuntime.judge(app).carrier(subId).maxMessageBytes)
            val encoded = PictureEncoder.forSending(pixels, limit) ?: return null to Problem.TOO_LARGE
            val ref = SmsRuntime.call(app) { it.putPicture(encoded.bytes, encoded.width, encoded.height) } ?: return null to Problem.UNAVAILABLE
            val scale = minOf(1f, PREVIEW_PX.toFloat() / maxOf(pixels.width, pixels.height))
            val preview = Bitmap.createScaledBitmap(pixels, maxOf(1, (pixels.width * scale).toInt()), maxOf(1, (pixels.height * scale).toInt()), true)
            return Prepared(ref, preview) to null
        } finally {
            pixels.recycle()
        }
    }

    /** Camera files older than an hour go (a capture that was never read). */
    fun prune(context: Context, now: Long = System.currentTimeMillis()) {
        File(context.cacheDir, CAPTURE_DIR).listFiles()?.forEach { if (now - it.lastModified() > CAPTURE_KEEP_MS) it.delete() }
    }

    private const val PREVIEW_PX = 320
}
