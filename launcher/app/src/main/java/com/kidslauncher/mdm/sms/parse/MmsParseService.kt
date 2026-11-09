package com.kidslauncher.mdm.sms.parse

import android.app.Service
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.os.Binder
import android.os.IBinder
import android.os.Parcel
import android.os.ParcelFileDescriptor
import android.os.SharedMemory
import android.system.OsConstants
import com.kidslauncher.mdm.sms.mms.MmsCodec
import com.kidslauncher.mdm.sms.mms.MmsMessage
import com.kidslauncher.mdm.sms.mms.MmsParseResult
import com.kidslauncher.mdm.sms.mms.MmsPart
import java.nio.ByteBuffer

/**
 * Design 77 D8, §8.2: the only place an MMS PDU is parsed or a received picture decoded - an `isolatedProcess` service
 * (its own process, `:mmsparse`, not exported): no permissions, no network, no provider, none of our files. The
 * `Application` and the component factory do nothing in it (`Process.isIsolated()`), so no rules, preferences or
 * enforcement code run next to untrusted input. It reads its input from a pipe and answers with strings and raw pixels
 * ([MmsParseProtocol]); a codec or decoder crash kills only this process.
 *
 * Only this package calls `MmsCodec.parseNotification`, `parseRetrieveConf` and `parseSendConf`
 * (`MmsParseBoundaryTest`): they catch every `Throwable`, OutOfMemoryError included, which is right here alone.
 */
class MmsParseService : Service() {
    override fun onBind(intent: Intent?): IBinder = ParseBinder()

    private class ParseBinder : Binder() {
        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            if (code !in MmsParseProtocol.PARSE_NOTIFICATION..MmsParseProtocol.DECODE_IMAGE) return super.onTransact(code, data, reply, flags)
            data.enforceInterface(MmsParseProtocol.DESCRIPTOR)
            val out = reply ?: return true
            val pipe = data.readTypedObject(ParcelFileDescriptor.CREATOR) ?: return rejected(out)
            val size = data.readInt()
            val limit = when (code) {
                MmsParseProtocol.PARSE_NOTIFICATION -> MmsCodec.NOTIFICATION_MAX_BYTES
                MmsParseProtocol.PARSE_SEND_CONF -> MmsCodec.SEND_CONF_MAX_BYTES
                MmsParseProtocol.DECODE_IMAGE -> MmsParseProtocol.PICK_MAX_BYTES
                else -> MmsCodec.MAX_PDU_BYTES_CEILING
            }
            val bytes = read(pipe, size, limit) ?: return rejected(out)
            when (code) {
                MmsParseProtocol.PARSE_NOTIFICATION -> notification(bytes, out)
                MmsParseProtocol.PARSE_RETRIEVE -> retrieve(bytes, data.readInt(), data.readInt() != 0, out)
                MmsParseProtocol.PARSE_SEND_CONF -> sendConf(bytes, out)
                MmsParseProtocol.DECODE_IMAGE -> image(bytes, data.readInt(), out)
            }
            return true
        }

        /** Exactly [size] bytes from the pipe, at most [limit]; `null` when it is shorter or longer. */
        private fun read(pipe: ParcelFileDescriptor, size: Int, limit: Int): ByteArray? {
            if (size !in 0..limit) {
                pipe.close()
                return null
            }
            return ParcelFileDescriptor.AutoCloseInputStream(pipe).use { input ->
                val bytes = input.readNBytes(size)
                bytes.takeIf { it.size == size }
            }
        }

        private fun rejected(out: Parcel): Boolean {
            out.writeInt(MmsParseProtocol.STATUS_REJECTED)
            return true
        }

        private fun notification(bytes: ByteArray, out: Parcel) {
            val n = (MmsCodec.parseNotification(bytes) as? MmsParseResult.Ok)?.value ?: run {
                rejected(out)
                return
            }
            out.writeInt(MmsParseProtocol.STATUS_OK)
            out.writeString(n.transactionId)
            out.writeString(n.contentLocation)
            out.writeString(n.from?.raw)
            out.writeLong(n.messageSize)
            out.writeLong(n.expirySeconds)
            out.writeInt(n.mmsVersion)
        }

        private fun sendConf(bytes: ByteArray, out: Parcel) {
            val conf = (MmsCodec.parseSendConf(bytes) as? MmsParseResult.Ok)?.value ?: run {
                rejected(out)
                return
            }
            out.writeInt(MmsParseProtocol.STATUS_OK)
            out.writeInt(conf.responseStatus)
        }

        /** D8: headers and texts as strings, each picture decoded to pixels - or, with pictures off (D11), only named. */
        private fun retrieve(bytes: ByteArray, maxBytes: Int, pictures: Boolean, out: Parcel) {
            val message = (MmsCodec.parseRetrieveConf(bytes, maxBytes) as? MmsParseResult.Ok)?.value ?: run {
                rejected(out)
                return
            }
            val shared = mutableListOf<SharedMemory>()
            try {
                out.writeInt(MmsParseProtocol.STATUS_OK)
                out.writeString(message.from?.raw)
                out.writeInt(message.to.size)
                message.to.forEach { out.writeString(it.raw) }
                out.writeInt(message.cc.size)
                message.cc.forEach { out.writeString(it.raw) }
                out.writeString(message.transactionId)
                out.writeInt(message.retrieveStatus)
                val texts = texts(message)
                out.writeInt(texts.size)
                texts.forEach { out.writeString(it) }
                val media = message.parts.filter { !it.isText && !it.isSmil }
                // Code QA M1: the message as a whole is bounded - past the budget a picture is "not shown".
                val budget = ParseWire.PictureBudget()
                out.writeInt(media.size)
                for (part in media) {
                    when {
                        !part.isImage -> {
                            out.writeInt(MmsParseProtocol.PART_OTHER)
                            out.writeString(part.mimeType.take(MmsParseProtocol.MIME_MAX_CHARS))
                        }
                        !pictures || !budget.roomForOne() -> out.writeInt(MmsParseProtocol.PART_NOT_SHOWN)
                        else -> {
                            val pixels = decode(part.data, MmsParseProtocol.PICTURE_MAX_PX)
                            when {
                                pixels == null -> out.writeInt(MmsParseProtocol.PART_UNDECODABLE)
                                !budget.take(pixels.width, pixels.height) -> {
                                    pixels.memory.close()
                                    out.writeInt(MmsParseProtocol.PART_NOT_SHOWN)
                                }
                                else -> {
                                    shared += pixels.memory
                                    out.writeInt(MmsParseProtocol.PART_PICTURE)
                                    out.writeInt(pixels.width)
                                    out.writeInt(pixels.height)
                                    // The region's descriptor alone (the main process reads it with pread, code QA L4).
                                    pixels.memory.writeToParcel(out, 0)
                                }
                            }
                        }
                    }
                }
            } finally {
                // The reply holds its own copies of the descriptors.
                shared.forEach { it.close() }
            }
        }

        /** The text parts as shown: a top-level `multipart/alternative` gives its first `text/plain` only (the codec's
         * README, "For step 3b"); otherwise every `text/plain`, each at most 5,000 characters (D8). */
        private fun texts(message: MmsMessage): List<String> {
            val plain = message.parts.filter(MmsPart::isText)
            val alternative = message.contentType.substringBefore(';').trim().equals("application/vnd.wap.multipart.alternative", ignoreCase = true) ||
                message.contentType.substringBefore(';').trim().equals("multipart/alternative", ignoreCase = true)
            return (if (alternative) plain.take(1) else plain).map { it.text(MmsParseProtocol.TEXT_MAX_CHARS) }
        }

        private fun image(bytes: ByteArray, maxPx: Int, out: Parcel) {
            val pixels = decode(bytes, maxPx.coerceIn(1, MmsParseProtocol.PICTURE_MAX_PX)) ?: run {
                rejected(out)
                return
            }
            try {
                out.writeInt(MmsParseProtocol.STATUS_OK)
                out.writeInt(pixels.width)
                out.writeInt(pixels.height)
                pixels.memory.writeToParcel(out, 0)
            } finally {
                pixels.memory.close()
            }
        }

        private class Pixels(val width: Int, val height: Int, val memory: SharedMemory)

        /**
         * §8.2: `ImageDecoder` with the size read from the header first - more than 8,192 px a side or 40 MP is refused
         * before anything is decoded - and a target size within [maxPx]; software memory; the EXIF orientation applied
         * by the decoder (the re-encode then carries none). The pixels go into a read-only shared region as RGBA_8888.
         * `null` for anything it can't or won't decode (a HEIF/AVIF that needs a codec this process can't reach shows as
         * not decodable, §8.2 [verify]).
         */
        private fun decode(bytes: ByteArray, maxPx: Int): Pixels? = try {
            val source = ImageDecoder.createSource(ByteBuffer.wrap(bytes))
            val decoded = ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
                val w = info.size.width
                val h = info.size.height
                require(w in 1..MmsParseProtocol.DECODE_MAX_SIDE && h in 1..MmsParseProtocol.DECODE_MAX_SIDE)
                require(w.toLong() * h <= MmsParseProtocol.DECODE_MAX_PIXELS)
                val scale = minOf(1.0, maxPx.toDouble() / maxOf(w, h))
                decoder.setTargetSize(maxOf(1, (w * scale).toInt()), maxOf(1, (h * scale).toInt()))
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            }
            val bitmap = if (decoded.config == Bitmap.Config.ARGB_8888) decoded else decoded.copy(Bitmap.Config.ARGB_8888, false)
            if (bitmap !== decoded) decoded.recycle()
            if (bitmap.width > maxPx || bitmap.height > maxPx) {
                bitmap.recycle()
                null
            } else {
                val size = bitmap.width * bitmap.height * 4
                val memory = SharedMemory.create("mms-pixels", size)
                val buffer = memory.mapReadWrite()
                try {
                    bitmap.copyPixelsToBuffer(buffer)
                } finally {
                    SharedMemory.unmap(buffer)
                }
                memory.setProtect(OsConstants.PROT_READ)
                Pixels(bitmap.width, bitmap.height, memory).also { bitmap.recycle() }
            }
        } catch (t: Throwable) {
            // OutOfMemoryError included: in this process a decoder failure is only "not decodable".
            null
        }
    }
}
