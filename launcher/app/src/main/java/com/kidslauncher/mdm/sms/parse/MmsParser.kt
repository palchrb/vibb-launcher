package com.kidslauncher.mdm.sms.parse

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.graphics.Bitmap
import android.os.IBinder
import android.os.Parcel
import android.os.ParcelFileDescriptor
import android.os.SharedMemory
import android.util.Log
import com.kidslauncher.mdm.sms.mms.MmsAddress
import com.kidslauncher.mdm.sms.mms.MmsCodec
import com.kidslauncher.mdm.sms.mms.MmsNotification
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

private const val LOG_TAG = "MmsParser"

/**
 * The main process's side of the isolated parse (design 77 D8): binds [MmsParseService], hands it the bytes through a
 * pipe, and reads its answer field by field with every length and count checked before the value
 * ([MmsParseProtocol]). Pictures come back as pixels: `width * height * 4 == size` is checked, the bitmap is built with
 * `copyPixelsFromBuffer` (no parser), and only then does the main process encode its own JPEG ([MmsPictures]). Every
 * call blocks: never on the main thread.
 *
 * [Answer.Crashed] means the isolated process died, hung or answered out of protocol - the caller counts it against the
 * message (§6.10: three deaths fail it); [Answer.Unavailable] means the service couldn't be reached at all (not the
 * message's fault).
 */
object MmsParser {
    sealed interface Answer<out T> {
        data class Ok<T>(val value: T) : Answer<T>
        data object Rejected : Answer<Nothing>
        data object Crashed : Answer<Nothing>
        data object Unavailable : Answer<Nothing>
    }

    /** One part of a fetched message as handed back: pixels (to encode), or why there are none. */
    sealed interface Media {
        class Picture(val bitmap: Bitmap) : Media
        data object NotShown : Media
        data object Undecodable : Media
        data class Other(val mime: String) : Media
    }

    class Retrieved(
        val from: MmsAddress?,
        val to: List<MmsAddress>,
        val cc: List<MmsAddress>,
        val transactionId: String?,
        val retrieveStatus: Int,
        val texts: List<String>,
        val media: List<Media>,
    )

    private const val BIND_WAIT_S = 10L
    private const val CALL_WAIT_S = 30L

    private val bindThread = Executors.newSingleThreadExecutor { Thread(it, "mms-parse-bind") }
    private val calls = Executors.newCachedThreadPool { Thread(it, "mms-parse-call") }

    fun parseNotification(context: Context, pdu: ByteArray): Answer<MmsNotification> =
        call(context, MmsParseProtocol.PARSE_NOTIFICATION, pdu, { }) { reply ->
            val transactionId = MmsParseProtocol.readString(reply, MmsCodec.MAX_ID_CHARS)!!
            val location = MmsParseProtocol.readString(reply, MmsCodec.MAX_CONTENT_LOCATION_CHARS)!!
            val from = MmsParseProtocol.readString(reply, MmsCodec.MAX_ADDRESS_CHARS, nullable = true)
            val size = reply.readLong()
            val expiry = reply.readLong()
            val version = reply.readInt()
            MmsNotification(transactionId, location, from?.let(::MmsAddress), size, expiry, null, null, version)
        }

    fun parseSendConf(context: Context, pdu: ByteArray): Answer<Int> =
        call(context, MmsParseProtocol.PARSE_SEND_CONF, pdu, { }) { reply -> reply.readInt() }

    /** A fetched message; [pictures] off (D11) decodes none. */
    fun parseRetrieve(context: Context, pdu: ByteArray, maxBytes: Int, pictures: Boolean): Answer<Retrieved> =
        call(context, MmsParseProtocol.PARSE_RETRIEVE, pdu, { it.writeInt(maxBytes); it.writeInt(if (pictures) 1 else 0) }) { reply ->
            val from = MmsParseProtocol.readString(reply, MmsCodec.MAX_ADDRESS_CHARS, nullable = true)
            val toCount = MmsParseProtocol.readCount(reply, MmsCodec.MAX_ADDRESSES)
            val to = List(toCount) { MmsAddress(MmsParseProtocol.readString(reply, MmsCodec.MAX_ADDRESS_CHARS)!!) }
            val ccCount = MmsParseProtocol.readCount(reply, MmsCodec.MAX_ADDRESSES - toCount)
            val cc = List(ccCount) { MmsAddress(MmsParseProtocol.readString(reply, MmsCodec.MAX_ADDRESS_CHARS)!!) }
            val transactionId = MmsParseProtocol.readString(reply, MmsCodec.MAX_ID_CHARS, nullable = true)
            val status = reply.readInt()
            val texts = List(MmsParseProtocol.readCount(reply, MmsCodec.MAX_PARTS)) { MmsParseProtocol.readString(reply, MmsParseProtocol.TEXT_MAX_CHARS)!! }
            val count = MmsParseProtocol.readCount(reply, MmsCodec.MAX_PARTS)
            val media = mutableListOf<Media>()
            try {
                repeat(count) {
                    media += when (reply.readInt()) {
                        MmsParseProtocol.PART_PICTURE -> Media.Picture(pixels(reply, MmsParseProtocol.PICTURE_MAX_PX))
                        MmsParseProtocol.PART_NOT_SHOWN -> Media.NotShown
                        MmsParseProtocol.PART_UNDECODABLE -> Media.Undecodable
                        MmsParseProtocol.PART_OTHER -> Media.Other(MmsParseProtocol.readString(reply, MmsParseProtocol.MIME_MAX_CHARS)!!)
                        else -> throw MmsParseProtocol.Malformed("part kind")
                    }
                }
            } catch (e: Exception) {
                media.forEach { (it as? Media.Picture)?.bitmap?.recycle() }
                throw e
            }
            Retrieved(from?.let(::MmsAddress), to, cc, transactionId, status, texts, media)
        }

    /** D11: a picture the kid picked, decoded in the isolated process to at most [maxPx] a side. */
    fun decodeImage(context: Context, bytes: ByteArray, maxPx: Int): Answer<Bitmap> =
        call(context, MmsParseProtocol.DECODE_IMAGE, bytes, { it.writeInt(maxPx) }) { reply -> pixels(reply, maxPx) }

    /** D8: width and height within the cap, the shared region exactly `width * height * 4`, then the bitmap from the
     * buffer - the main process never runs a decoder on what came from the other side. */
    private fun pixels(reply: Parcel, maxPx: Int): Bitmap {
        val width = reply.readInt()
        val height = reply.readInt()
        if (width !in 1..maxPx || height !in 1..maxPx) throw MmsParseProtocol.Malformed("size $width x $height")
        val memory = reply.readTypedObject(SharedMemory.CREATOR) ?: throw MmsParseProtocol.Malformed("no pixels")
        memory.use {
            if (it.size.toLong() != width.toLong() * height * 4) throw MmsParseProtocol.Malformed("pixel bytes")
            val buffer = it.mapReadOnly()
            try {
                val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                bitmap.copyPixelsFromBuffer(buffer)
                return bitmap
            } finally {
                SharedMemory.unmap(buffer)
            }
        }
    }

    /** Binds, hands [input] over a pipe (written on its own thread), waits at most [CALL_WAIT_S] for the answer, reads
     * it with [read], unbinds. */
    private fun <T> call(context: Context, code: Int, input: ByteArray, extra: (Parcel) -> Unit, read: (Parcel) -> T): Answer<T> {
        val app = context.applicationContext
        val connection = Connection()
        val bound = try {
            app.bindService(Intent(app, MmsParseService::class.java), android.content.Context.BIND_AUTO_CREATE, bindThread, connection)
        } catch (e: Exception) {
            false
        }
        if (!bound) return Answer.Unavailable.also { runCatching { app.unbindService(connection) } }
        try {
            val binder = connection.await() ?: return Answer.Unavailable
            val task = calls.submit<Answer<T>> { transact(binder, code, input, extra, read) }
            return try {
                task.get(CALL_WAIT_S, TimeUnit.SECONDS)
            } catch (e: TimeoutException) {
                task.cancel(true)
                Log.w(LOG_TAG, "The parse service didn't answer in time")
                Answer.Crashed
            }
        } finally {
            runCatching { app.unbindService(connection) }
        }
    }

    private fun <T> transact(binder: IBinder, code: Int, input: ByteArray, extra: (Parcel) -> Unit, read: (Parcel) -> T): Answer<T> {
        val pipe = ParcelFileDescriptor.createPipe()
        val writer = Thread({
            try {
                ParcelFileDescriptor.AutoCloseOutputStream(pipe[1]).use { it.write(input) }
            } catch (e: java.io.IOException) {
                // The other side stopped reading (a refused size): its answer says so.
            }
        }, "mms-parse-pipe")
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        try {
            data.writeInterfaceToken(MmsParseProtocol.DESCRIPTOR)
            data.writeTypedObject(pipe[0], 0)
            data.writeInt(input.size)
            extra(data)
            writer.start()
            if (!binder.transact(code, data, reply, 0)) return Answer.Crashed
            return when (reply.readInt()) {
                MmsParseProtocol.STATUS_OK -> Answer.Ok(read(reply))
                MmsParseProtocol.STATUS_REJECTED -> Answer.Rejected
                else -> Answer.Crashed
            }
        } catch (e: android.os.RemoteException) {
            // DeadObjectException: the isolated process died on these bytes.
            Log.w(LOG_TAG, "The parse service died: ${e.javaClass.simpleName}")
            return Answer.Crashed
        } catch (e: MmsParseProtocol.Malformed) {
            Log.w(LOG_TAG, "An answer out of protocol: ${e.message}")
            return Answer.Crashed
        } catch (e: RuntimeException) {
            Log.w(LOG_TAG, "An answer that couldn't be read: ${e.javaClass.simpleName}")
            return Answer.Crashed
        } finally {
            pipe[0].close()
            data.recycle()
            reply.recycle()
            writer.join(1000)
        }
    }

    private class Connection : ServiceConnection {
        private val latch = CountDownLatch(1)

        @Volatile
        private var binder: IBinder? = null

        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            binder = service
            latch.countDown()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            binder = null
        }

        override fun onNullBinding(name: ComponentName?) {
            latch.countDown()
        }

        fun await(): IBinder? = if (latch.await(BIND_WAIT_S, TimeUnit.SECONDS)) binder else null
    }
}
