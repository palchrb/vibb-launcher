package com.kidslauncher.mdm.sms.mms

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.util.Log
import java.io.File
import java.io.FileNotFoundException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

private const val LOG_TAG = "MmsFileProvider"

/**
 * Design 77 D10, §6.1, QA L3: where the platform's MMS service writes a download and reads a send - not exported,
 * `grantUriPermissions`: the platform gets one URI per request (granted for that request by the telephony broker, revoked
 * by us on the result, [revoke]).
 *
 * - `in/<message id>`, write only: [openFile] hands out the write end of a pipe; a reader thread copies at most the
 *   request's cap ([expect]) into `no_backup/mms/in/<id>.part` and renames it once the writer is done. Past the cap it
 *   stops and deletes the file - the writer's next write fails. That is our byte cap (the platform's own client still
 *   buffers whatever the MMSC sends, inside `com.android.phone`; the host check, D10, keeps that the carrier's server).
 * - `out/<token>`, read only: a send-req or an answer we composed, in `no_backup/mms/out/`.
 *
 * Every other path, and any other mode, is refused.
 */
class MmsFileProvider : ContentProvider() {
    override fun onCreate(): Boolean = true

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        val context = context ?: throw FileNotFoundException("no context")
        return when (val route = MmsFileRoutes.route(uri.pathSegments, mode)) {
            is MmsFileRoutes.Route.Download -> incoming(context, route.messageId)
            is MmsFileRoutes.Route.Send -> {
                val file = File(dir(context, OUT), route.token).takeIf { it.isFile } ?: throw FileNotFoundException("no such send")
                ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
            }
            MmsFileRoutes.Route.Refused -> throw FileNotFoundException("refused")
        }
    }

    private fun incoming(context: Context, id: String): ParcelFileDescriptor {
        val cap = caps[id] ?: MmsCodec.MAX_PDU_BYTES_CEILING
        val (read, write) = ParcelFileDescriptor.createPipe()
        val done = CountDownLatch(1)
        readers[id] = done
        Thread({
            val dir = dir(context, IN)
            val part = File(dir, "$id.part")
            var total = 0L
            var ok = false
            try {
                ParcelFileDescriptor.AutoCloseInputStream(read).use { input ->
                    part.outputStream().use { output ->
                        val buffer = ByteArray(16 * 1024)
                        while (true) {
                            val n = input.read(buffer)
                            if (n < 0) break
                            total += n
                            if (total > cap) {
                                Log.w(LOG_TAG, "A download over its cap ($cap bytes) - stopped")
                                // Code QA L5: said, so the result makes it "too large" for good, not a retry.
                                File(dir, "$id$OVER").createNewFile()
                                throw java.io.IOException("over the cap")
                            }
                            output.write(buffer, 0, n)
                        }
                    }
                }
                ok = part.renameTo(File(dir, id))
            } catch (e: Exception) {
                Log.w(LOG_TAG, "A download wasn't kept: ${e.javaClass.simpleName}")
            } finally {
                if (!ok) part.delete()
                done.countDown()
            }
        }, "mms-download").start()
        return write
    }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null

    override fun getType(uri: Uri): String? = null

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0

    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0

    companion object {
        private const val IN = MmsFileRoutes.IN
        private const val OUT = MmsFileRoutes.OUT
        private const val OVER = ".over"
        private val MESSAGE_ID = MmsFileRoutes.MESSAGE_ID
        private val TOKEN = MmsFileRoutes.TOKEN

        private val caps = ConcurrentHashMap<String, Int>()
        private val readers = ConcurrentHashMap<String, CountDownLatch>()

        fun authority(context: Context): String = context.packageName + ".mms"

        private fun dir(context: Context, kind: String): File = File(File(context.noBackupFilesDir, "mms"), kind).apply { mkdirs() }

        /** A download's URI, its cap remembered for [openFile] (a process death forgets it: the ceiling then). */
        fun expect(context: Context, messageId: String, cap: Int): Uri {
            require(MESSAGE_ID.matches(messageId))
            caps[messageId] = cap
            File(dir(context, IN), messageId).delete()
            File(dir(context, IN), "$messageId$OVER").delete()
            return Uri.Builder().scheme("content").authority(authority(context)).appendPath(IN).appendPath(messageId).build()
        }

        /** A composed PDU (a send or an answer) stored under a fresh token, and its URI. */
        fun outgoing(context: Context, token: String, pdu: ByteArray): Uri {
            require(TOKEN.matches(token))
            File(dir(context, OUT), token).writeBytes(pdu)
            return Uri.Builder().scheme("content").authority(authority(context)).appendPath(OUT).appendPath(token).build()
        }

        /** The download's bytes once the platform reported success - waiting briefly for our reader to drain the pipe;
         * `null` if there is no complete file (over the cap, or a process death in between). */
        fun downloaded(context: Context, messageId: String): File? {
            if (!MESSAGE_ID.matches(messageId)) return null
            readers.remove(messageId)?.await(10, TimeUnit.SECONDS)
            caps.remove(messageId)
            return File(dir(context, IN), messageId).takeIf { it.isFile }
        }

        fun deleteDownload(context: Context, messageId: String) {
            if (!MESSAGE_ID.matches(messageId)) return
            File(dir(context, IN), messageId).delete()
            File(dir(context, IN), "$messageId$OVER").delete()
        }

        /** A failed download's reader is let finish (so [overCap] is known). */
        fun settled(messageId: String) {
            readers.remove(messageId)?.await(10, TimeUnit.SECONDS)
            caps.remove(messageId)
        }

        /** Code QA L5: whether the download ran past its cap (its reader stopped it). */
        fun overCap(context: Context, messageId: String): Boolean =
            MESSAGE_ID.matches(messageId) && File(dir(context, IN), "$messageId$OVER").exists()

        fun deleteOutgoing(context: Context, token: String) {
            if (TOKEN.matches(token)) File(dir(context, OUT), token).delete()
        }

        /** QA L3: the request's grant ends with its result. */
        fun revoke(context: Context, kind: String, name: String) {
            val uri = Uri.Builder().scheme("content").authority(authority(context)).appendPath(kind).appendPath(name).build()
            runCatching {
                context.revokeUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            }
        }

        fun revokeDownload(context: Context, messageId: String) = revoke(context, IN, messageId)

        fun revokeOutgoing(context: Context, token: String) = revoke(context, OUT, token)

        /** Downloads and sends a process death left behind: older than a day, they go. */
        fun prune(context: Context, now: Long = System.currentTimeMillis()) {
            for (kind in listOf(IN, OUT)) {
                dir(context, kind).listFiles()?.forEach { if (now - it.lastModified() > DAY_MS) it.delete() }
            }
        }

        private const val DAY_MS = 24 * 60 * 60 * 1000L
    }
}

/**
 * What [MmsFileProvider] serves, as a pure rule (code QA test 8): `in/<message id>` for writing only (a download),
 * `out/<token>` for reading only (a send or an answer); anything else - another path, another mode, a name that isn't
 * the engine's ULID or 32 hex - is refused. The decoded segments must match whole, so no traversal gets through.
 */
object MmsFileRoutes {
    const val IN = "in"
    const val OUT = "out"

    /** A message id: the engine's ULID. */
    val MESSAGE_ID = Regex("[0-9A-Z]{26}")

    /** An outgoing file's token: 32 lowercase hex. */
    val TOKEN = Regex("[0-9a-f]{32}")

    sealed interface Route {
        data class Download(val messageId: String) : Route
        data class Send(val token: String) : Route
        data object Refused : Route
    }

    fun route(segments: List<String>, mode: String): Route {
        if (segments.size != 2) return Route.Refused
        val (kind, name) = segments
        return when {
            kind == IN && MESSAGE_ID.matches(name) && mode == "w" -> Route.Download(name)
            kind == OUT && TOKEN.matches(name) && mode == "r" -> Route.Send(name)
            else -> Route.Refused
        }
    }
}
