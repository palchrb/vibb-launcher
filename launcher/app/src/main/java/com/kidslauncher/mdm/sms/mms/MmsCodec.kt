package com.kidslauncher.mdm.sms.mms

import com.kidslauncher.mdm.sms.vendor.pdu.AcknowledgeInd
import com.kidslauncher.mdm.sms.vendor.pdu.CharacterSets
import com.kidslauncher.mdm.sms.vendor.pdu.EncodedStringValue
import com.kidslauncher.mdm.sms.vendor.pdu.GenericPdu
import com.kidslauncher.mdm.sms.vendor.pdu.MalformedPduException
import com.kidslauncher.mdm.sms.vendor.pdu.NotificationInd
import com.kidslauncher.mdm.sms.vendor.pdu.NotifyRespInd
import com.kidslauncher.mdm.sms.vendor.pdu.PduBody
import com.kidslauncher.mdm.sms.vendor.pdu.PduComposer
import com.kidslauncher.mdm.sms.vendor.pdu.PduHeaders
import com.kidslauncher.mdm.sms.vendor.pdu.PduParser
import com.kidslauncher.mdm.sms.vendor.pdu.PduPart
import com.kidslauncher.mdm.sms.vendor.pdu.RetrieveConf
import com.kidslauncher.mdm.sms.vendor.pdu.SendConf
import com.kidslauncher.mdm.sms.vendor.pdu.SendReq
import java.nio.charset.Charset

/**
 * Design 77 step 3a: the MMS PDU codec's one entry point, pure JVM. Parsing runs only in the isolated parse
 * process (D8, step 3b); composing runs in the main process on bytes it made itself (D11).
 *
 * Every parse returns [MmsParseResult]: never throws, whatever the bytes (the fuzz test holds that line). The
 * vendored parser underneath is `sms/vendor/pdu`, hardened per §8.1 (its README lists every change).
 */
object MmsCodec {
    /** A raw m-notification-ind is at most this long (D7: the push receiver commits at most 2 KB). */
    const val NOTIFICATION_MAX_BYTES = 2 * 1024

    /** An m-send-conf is a few header fields; anything longer is not one. */
    const val SEND_CONF_MAX_BYTES = 8 * 1024

    /** The input cap when the carrier gives none (§8.1). */
    const val DEFAULT_MAX_PDU_BYTES = PduParser.DEFAULT_MAX_PDU_BYTES

    /** No carrier value lifts the cap above this: a sanity ceiling, not a carrier figure. */
    const val MAX_PDU_BYTES_CEILING = 4 * 1024 * 1024

    const val MAX_PARTS = PduParser.MAX_PARTS
    const val MAX_HEADER_FIELDS = PduParser.MAX_HEADER_FIELDS
    const val MAX_DEPTH = PduParser.MAX_DEPTH

    /** A send-req names at most this many recipients (to, a group reply's participants; D12). */
    const val MAX_RECIPIENTS = 20

    // QA L2: header strings cross Binder to the main process (D8, about 1 MB a transaction), so each is capped here,
    // before it leaves the codec. Anything longer fails as TOO_LONG.
    /** One address (From, To, Cc), in characters. */
    const val MAX_ADDRESS_CHARS = 128

    /** To and Cc together. */
    const val MAX_ADDRESSES = MAX_RECIPIENTS * 2

    /** A received subject, in characters. */
    const val MAX_SUBJECT_CHARS = 1_000

    /** X-Mms-Content-Location, in characters. */
    const val MAX_CONTENT_LOCATION_CHARS = 1_024

    /** Transaction id, message id, message class, and a part's content type, name, location and id. */
    const val MAX_ID_CHARS = 256

    /** A text part's text as handed on (D8: 5,000 characters). */
    const val MAX_TEXT_CHARS = 5_000

    /** QA M1: a subject we send, in characters (many MMSCs cut at about 40). */
    const val MAX_SEND_SUBJECT_CHARS = 40

    /**
     * QA L3: the carrier's maximum as the cap. A value of 0 or below (a carrier config without one) means
     * [DEFAULT_MAX_PDU_BYTES], not a 1-byte cap; anything above [MAX_PDU_BYTES_CEILING] is clamped to it.
     */
    fun capOf(maxBytes: Int): Int = if (maxBytes <= 0) DEFAULT_MAX_PDU_BYTES else maxBytes.coerceAtMost(MAX_PDU_BYTES_CEILING)

    /** The m-notification-ind a WAP push carried (D10's checks run on it in the main process). */
    fun parseNotification(bytes: ByteArray): MmsParseResult<MmsNotification> =
        parse(bytes, NOTIFICATION_MAX_BYTES) { pdu ->
            val ind = pdu as? NotificationInd ?: return@parse MmsParseResult.Failed(MmsParseFailure.UNEXPECTED_TYPE)
            val size = ind.messageSize
            val expiry = ind.expiry
            if (size < 0 || expiry < 0) return@parse MmsParseResult.Failed(MmsParseFailure.REJECTED)
            val notification = MmsNotification(
                transactionId = latin1(ind.transactionId) ?: return@parse MmsParseResult.Failed(MmsParseFailure.REJECTED),
                contentLocation = latin1(ind.contentLocation) ?: return@parse MmsParseResult.Failed(MmsParseFailure.REJECTED),
                from = MmsAddress.from(ind.from),
                messageSize = size,
                expirySeconds = expiry,
                messageClass = latin1(ind.messageClass),
                subject = ind.subject?.string,
                mmsVersion = ind.mmsVersion,
            )
            if (notification.tooLong()) MmsParseResult.Failed(MmsParseFailure.TOO_LONG) else MmsParseResult.Ok(notification)
        }

    /**
     * The downloaded message (m-retrieve-conf). [maxBytes] is the carrier's maximum (D10), read by [capOf]: 0 or below
     * is [DEFAULT_MAX_PDU_BYTES], and it is never above [MAX_PDU_BYTES_CEILING].
     */
    fun parseRetrieveConf(bytes: ByteArray, maxBytes: Int = DEFAULT_MAX_PDU_BYTES): MmsParseResult<MmsMessage> =
        parse(bytes, capOf(maxBytes)) { pdu ->
            val conf = pdu as? RetrieveConf ?: return@parse MmsParseResult.Failed(MmsParseFailure.UNEXPECTED_TYPE)
            val date = conf.date
            if (date < 0) return@parse MmsParseResult.Failed(MmsParseFailure.REJECTED)
            val to = conf.to.orEmpty()
            val cc = conf.cc.orEmpty()
            // QA L2: the count is judged before the addresses are decoded.
            if (to.size + cc.size > MAX_ADDRESSES) return@parse MmsParseResult.Failed(MmsParseFailure.TOO_LONG)
            val message = MmsMessage(
                transactionId = latin1(conf.transactionId),
                messageId = latin1(conf.messageId),
                dateSeconds = date,
                from = MmsAddress.from(conf.from),
                to = to.mapNotNull { MmsAddress.from(it) },
                cc = cc.mapNotNull { MmsAddress.from(it) },
                subject = conf.subject?.string,
                retrieveStatus = conf.retrieveStatus.takeIf { it != 0 } ?: PduHeaders.RETRIEVE_STATUS_OK,
                contentType = latin1(conf.contentType).orEmpty(),
                parts = parts(conf.body),
            )
            if (message.tooLong()) MmsParseResult.Failed(MmsParseFailure.TOO_LONG) else MmsParseResult.Ok(message)
        }

    /** The MMSC's answer to a send (the platform hands it back after `sendMultimediaMessage`). */
    fun parseSendConf(bytes: ByteArray): MmsParseResult<MmsSendConf> =
        parse(bytes, SEND_CONF_MAX_BYTES) { pdu ->
            val conf = pdu as? SendConf ?: return@parse MmsParseResult.Failed(MmsParseFailure.UNEXPECTED_TYPE)
            val sendConf = MmsSendConf(
                transactionId = latin1(conf.transactionId),
                messageId = latin1(conf.messageId),
                responseStatus = conf.responseStatus,
            )
            val long = listOf(sendConf.transactionId, sendConf.messageId).any { it.over(MAX_ID_CHARS) }
            if (long) MmsParseResult.Failed(MmsParseFailure.TOO_LONG) else MmsParseResult.Ok(sendConf)
        }

    /**
     * An m-send-req with a SMIL first part (multipart/related, as AOSP Messaging sends), then [parts] in order.
     * [to] are numbers as the engine normalised them (`+` and digits); [maxBytes] is the carrier's maximum (read by
     * [capOf]), and the whole PDU must fit it. [subject] has no control characters and at most
     * [MAX_SEND_SUBJECT_CHARS]; [transactionId] is 1-64 printable ASCII without space (QA M1: a NUL in either would
     * end the Text-string early and let the octets after it read as headers of their own, a Bcc among them).
     */
    fun composeSendReq(
        to: List<String>,
        parts: List<MmsOutgoingPart>,
        maxBytes: Int = DEFAULT_MAX_PDU_BYTES,
        subject: String? = null,
        transactionId: String? = null,
        nowSeconds: Long = System.currentTimeMillis() / 1000,
    ): MmsComposeResult {
        if (to.isEmpty()) return MmsComposeResult.Failed(MmsComposeFailure.NO_RECIPIENTS)
        if (to.size > MAX_RECIPIENTS) return MmsComposeResult.Failed(MmsComposeFailure.TOO_MANY_RECIPIENTS)
        if (to.any { !RECIPIENT.matches(it) }) return MmsComposeResult.Failed(MmsComposeFailure.BAD_RECIPIENT)
        if (subject != null && !subjectOk(subject)) return MmsComposeResult.Failed(MmsComposeFailure.BAD_SUBJECT)
        if (transactionId != null && !TRANSACTION_ID.matches(transactionId)) {
            return MmsComposeResult.Failed(MmsComposeFailure.BAD_TRANSACTION_ID)
        }
        if (parts.isEmpty()) return MmsComposeResult.Failed(MmsComposeFailure.NO_PARTS)
        if (parts.size + 1 > MAX_PARTS) return MmsComposeResult.Failed(MmsComposeFailure.TOO_MANY_PARTS)
        if (parts.any { it.data.isEmpty() || it.contentType !in MmsOutgoingPart.SENDABLE }) {
            return MmsComposeResult.Failed(MmsComposeFailure.BAD_PART)
        }
        val cap = capOf(maxBytes)
        if (parts.sumOf { it.data.size.toLong() } > cap) return MmsComposeResult.Failed(MmsComposeFailure.TOO_LARGE)

        val bytes = try {
            val req = SendReq()
            transactionId?.let { req.transactionId = it.toByteArray(Charsets.ISO_8859_1) }
            to.forEach { req.addTo(EncodedStringValue(it)) }
            subject?.takeIf { it.isNotEmpty() }?.let { req.subject = EncodedStringValue(CharacterSets.UTF_8, it) }
            req.date = nowSeconds
            req.messageClass = PduHeaders.MESSAGE_CLASS_PERSONAL_STR.toByteArray(Charsets.ISO_8859_1)
            req.expiry = SEND_EXPIRY_SECONDS
            req.priority = PduHeaders.PRIORITY_NORMAL
            req.deliveryReport = PduHeaders.VALUE_NO
            req.readReport = PduHeaders.VALUE_NO
            req.body = body(parts)
            PduComposer(req).make()
        } catch (e: Exception) {
            null
        } ?: return MmsComposeResult.Failed(MmsComposeFailure.COMPOSER_ERROR)
        if (bytes.size > cap) return MmsComposeResult.Failed(MmsComposeFailure.TOO_LARGE)
        return MmsComposeResult.Ok(bytes)
    }

    /**
     * The m-notifyresp-ind answering a notification (D10: only for allowed messages; [status] says which). The
     * [transactionId] is the notification's, held to the same rule as [composeSendReq]'s (QA M1).
     */
    fun composeNotifyResp(
        transactionId: String,
        status: MmsNotifyStatus,
        mmsVersion: Int = PduHeaders.CURRENT_MMS_VERSION,
    ): MmsComposeResult =
        compose(transactionId) { NotifyRespInd(mmsVersion, transactionId.toByteArray(Charsets.ISO_8859_1), status.octet) }

    /** The m-acknowledge-ind after a downloaded message. */
    fun composeAcknowledge(transactionId: String, mmsVersion: Int = PduHeaders.CURRENT_MMS_VERSION): MmsComposeResult =
        compose(transactionId) { AcknowledgeInd(mmsVersion, transactionId.toByteArray(Charsets.ISO_8859_1)) }

    private const val SEND_EXPIRY_SECONDS = 7L * 24 * 60 * 60
    private val RECIPIENT = Regex("""\+?[0-9]{1,20}""")

    /** QA M1: printable ASCII without space, as a WSP Text-string can carry it with no quoting and no NUL. */
    private val TRANSACTION_ID = Regex("""[\x21-\x7E]{1,64}""")

    /** QA M1: no C0 or C1 control character (NUL among them) and no DEL, within the length. */
    private fun subjectOk(subject: String): Boolean =
        subject.length <= MAX_SEND_SUBJECT_CHARS && subject.none { Character.isISOControl(it) }

    private fun String?.over(max: Int): Boolean = this != null && length > max

    private fun MmsAddress?.overLong(): Boolean = this != null && raw.length > MAX_ADDRESS_CHARS

    private fun MmsNotification.tooLong(): Boolean =
        transactionId.over(MAX_ID_CHARS) || contentLocation.over(MAX_CONTENT_LOCATION_CHARS) || from.overLong() ||
            messageClass.over(MAX_ID_CHARS) || subject.over(MAX_SUBJECT_CHARS)

    private fun MmsMessage.tooLong(): Boolean =
        transactionId.over(MAX_ID_CHARS) || messageId.over(MAX_ID_CHARS) || from.overLong() ||
            to.any { it.overLong() } || cc.any { it.overLong() } || subject.over(MAX_SUBJECT_CHARS) ||
            contentType.over(MAX_ID_CHARS) ||
            parts.any { p -> listOf(p.contentType, p.name, p.contentLocation, p.contentId).any { it.over(MAX_ID_CHARS) } }

    private inline fun <T> parse(
        bytes: ByteArray,
        maxBytes: Int,
        map: (GenericPdu) -> MmsParseResult<T>,
    ): MmsParseResult<T> =
        try {
            // Checked here too, so an oversized input never reaches the parser at all.
            if (bytes.size > maxBytes) {
                MmsParseResult.Failed(MmsParseFailure.TOO_LARGE)
            } else {
                // Content-Disposition parsed, as AOSP Messaging's default.
                val pdu = PduParser(bytes, true, maxBytes).parseOrThrow()
                if (pdu == null) MmsParseResult.Failed(MmsParseFailure.REJECTED) else map(pdu)
            }
        } catch (e: MalformedPduException) {
            MmsParseResult.Failed(MmsParseFailure.of(e.reason))
        } catch (t: Throwable) {
            // A codec bug, never a verdict on the bytes: the fuzz test fails on this. Errors (OOM, stack) included:
            // in the isolated process the caller reports the message as not shown (D8).
            MmsParseResult.Failed(MmsParseFailure.INTERNAL)
        }

    private inline fun compose(transactionId: String, build: () -> GenericPdu): MmsComposeResult {
        if (!TRANSACTION_ID.matches(transactionId)) return MmsComposeResult.Failed(MmsComposeFailure.BAD_TRANSACTION_ID)
        val bytes = try {
            PduComposer(build()).make()
        } catch (e: Exception) {
            null
        }
        return bytes?.let { MmsComposeResult.Ok(it) } ?: MmsComposeResult.Failed(MmsComposeFailure.COMPOSER_ERROR)
    }

    private fun parts(body: PduBody?): List<MmsPart> {
        if (body == null) return emptyList()
        return (0 until body.partsNum).map { i ->
            val part = body.getPart(i)
            MmsPart(
                contentType = latin1(part.contentType).orEmpty(),
                charset = part.charset,
                name = latin1(part.name) ?: latin1(part.filename),
                contentLocation = latin1(part.contentLocation),
                contentId = latin1(part.contentId),
                data = part.data ?: ByteArray(0),
            )
        }
    }

    private fun body(parts: List<MmsOutgoingPart>): PduBody {
        val body = PduBody()
        val names = parts.mapIndexed { i, part -> part.fileName(i) }
        body.addPart(
            pduPart("application/smil", "smil", "smil.xml", smil(parts, names).toByteArray(Charsets.UTF_8), CharacterSets.UTF_8),
        )
        parts.forEachIndexed { i, part ->
            val charset = if (part.isText) CharacterSets.UTF_8 else 0
            body.addPart(pduPart(part.contentType, names[i].substringBefore('.'), names[i], part.data, charset))
        }
        return body
    }

    private fun pduPart(contentType: String, id: String, location: String, data: ByteArray, charset: Int): PduPart =
        PduPart().apply {
            setContentType(contentType.toByteArray(Charsets.ISO_8859_1))
            setContentId("<$id>".toByteArray(Charsets.ISO_8859_1))
            setContentLocation(location.toByteArray(Charsets.ISO_8859_1))
            if (charset != 0) setCharset(charset)
            setData(data)
        }

    /** A plain SMIL: one slide per part, picture above text. Only our own generated names go in (no escaping needed). */
    private fun smil(parts: List<MmsOutgoingPart>, names: List<String>): String = buildString {
        append("<smil><head><layout><root-layout/>")
        append("<region id=\"Image\" fit=\"meet\" top=\"0\" left=\"0\" height=\"80%\" width=\"100%\"/>")
        append("<region id=\"Text\" top=\"80%\" left=\"0\" height=\"20%\" width=\"100%\"/>")
        append("</layout></head><body>")
        parts.forEachIndexed { i, part ->
            val element = when {
                part.isText -> "<text src=\"${names[i]}\" region=\"Text\"/>"
                part.contentType.startsWith("image/") -> "<img src=\"${names[i]}\" region=\"Image\"/>"
                else -> "<ref src=\"${names[i]}\"/>"
            }
            append("<par dur=\"5000ms\">").append(element).append("</par>")
        }
        append("</body></smil>")
    }

    private fun latin1(bytes: ByteArray?): String? = bytes?.let { String(it, Charsets.ISO_8859_1) }
}

sealed interface MmsParseResult<out T> {
    data class Ok<T>(val value: T) : MmsParseResult<T>

    data class Failed(val reason: MmsParseFailure) : MmsParseResult<Nothing>
}

enum class MmsParseFailure {
    /** Longer than the cap the caller gave. */
    TOO_LARGE,

    /** The bytes end inside a value. */
    TRUNCATED,

    /** A length or integer beyond the remaining bytes or 32 bits. */
    BAD_LENGTH,
    TOO_MANY_PARTS,
    TOO_MANY_HEADERS,
    TOO_DEEP,

    /** An inconsistent structure: a part header that overruns its length, a part with no bytes, an empty multipart/alternative child. */
    BAD_STRUCTURE,

    /**
     * Well-formed, but not a PDU we accept: a mandatory header missing, a header value out of its range, an
     * unsupported type or content type, a negative size or date. Malformed structure is [BAD_STRUCTURE] (QA L1).
     */
    REJECTED,

    /** A header string or the address count beyond the caps that keep D8's Binder hand-over small (QA L2). */
    TOO_LONG,

    /** A valid PDU of another type than asked for. */
    UNEXPECTED_TYPE,

    /** Anything else thrown: a codec bug. The fuzz test fails on it. */
    INTERNAL,
    ;

    companion object {
        fun of(reason: MalformedPduException.Reason): MmsParseFailure = when (reason) {
            MalformedPduException.Reason.TOO_LARGE -> TOO_LARGE
            MalformedPduException.Reason.TRUNCATED -> TRUNCATED
            MalformedPduException.Reason.BAD_LENGTH -> BAD_LENGTH
            MalformedPduException.Reason.TOO_MANY_PARTS -> TOO_MANY_PARTS
            MalformedPduException.Reason.TOO_MANY_HEADERS -> TOO_MANY_HEADERS
            MalformedPduException.Reason.TOO_DEEP -> TOO_DEEP
            MalformedPduException.Reason.BAD_STRUCTURE -> BAD_STRUCTURE
        }
    }
}

/**
 * An address as the PDU carried it, e.g. `+4700000000/TYPE=PLMN`. The parser keeps it whole (its H14), so D10 can
 * require `TYPE=PLMN`; [value] and [type] are the two sides of the last `/TYPE=`.
 */
data class MmsAddress(val raw: String) {
    val value: String get() = raw.substringBeforeLast(TYPE_MARK, raw)
    val type: String? get() = if (raw.contains(TYPE_MARK)) raw.substringAfterLast(TYPE_MARK) else null

    /**
     * The number when this is exactly `<+ and ASCII digits>/TYPE=PLMN`, else null: D10's sender rule's first half
     * (QA's note for step 3b). ASCII only: `Char.isDigit()` would also take Arabic-Indic and fullwidth digits, which an
     * address in the charset form can carry.
     */
    val plmnNumber: String? get() = value.takeIf { type == "PLMN" && PLMN_NUMBER.matches(it) }

    companion object {
        private const val TYPE_MARK = "/TYPE="
        private val PLMN_NUMBER = Regex("""\+?[0-9]{1,20}""")

        /** Null for a missing address and for the insert-address-token (a hidden sender). */
        internal fun from(value: EncodedStringValue?): MmsAddress? {
            val text = value?.string ?: return null
            if (text.isEmpty() || text == PduHeaders.FROM_INSERT_ADDRESS_TOKEN_STR) return null
            return MmsAddress(text)
        }
    }
}

data class MmsNotification(
    val transactionId: String,
    val contentLocation: String,
    /** Null when the push hides the sender (insert-address-token) or names none: D10 blocks both. */
    val from: MmsAddress?,
    val messageSize: Long,
    /** X-Mms-Expiry as seconds since the epoch (a relative expiry is made absolute at parse time). */
    val expirySeconds: Long,
    val messageClass: String?,
    val subject: String?,
    val mmsVersion: Int,
)

data class MmsMessage(
    val transactionId: String?,
    val messageId: String?,
    val dateSeconds: Long,
    val from: MmsAddress?,
    val to: List<MmsAddress>,
    val cc: List<MmsAddress>,
    val subject: String?,
    /** [PduHeaders.RETRIEVE_STATUS_OK] (0x80) or an error; on an error [parts] is empty. */
    val retrieveStatus: Int,
    val contentType: String,
    val parts: List<MmsPart>,
)

/** One part as received. [data] is the raw bytes, at most the input's size; nothing here decodes a picture. */
class MmsPart(
    val contentType: String,
    val charset: Int,
    val name: String?,
    val contentLocation: String?,
    val contentId: String?,
    val data: ByteArray,
) {
    /** The media type without parameters, lower case. */
    val mimeType: String get() = contentType.substringBefore(';').trim().lowercase()

    val isText: Boolean get() = mimeType == "text/plain"
    val isSmil: Boolean get() = mimeType == "application/smil"
    val isImage: Boolean get() = mimeType.startsWith("image/")

    /**
     * A text part's text in its charset (UTF-8 when none or unknown), at most [maxChars] characters (D8: 5,000). Only
     * the first `maxChars * 4` octets are decoded (QA N5), so a 4 MB part costs no 4 MB string.
     */
    fun text(maxChars: Int = MmsCodec.MAX_TEXT_CHARS): String {
        val name = runCatching { CharacterSets.getMimeName(charset) }.getOrNull()
        val decoder = name?.takeIf { it != CharacterSets.MIMENAME_ANY_CHARSET }
            ?.let { runCatching { Charset.forName(it) }.getOrNull() } ?: Charsets.UTF_8
        val octets = (maxChars.toLong() * 4).coerceAtMost(data.size.toLong()).toInt()
        val cut = octets < data.size
        var text = String(data, 0, octets, decoder)
        // A character cut in half at the octet limit decodes as U+FFFD: drop it.
        if (cut && text.endsWith('\uFFFD')) text = text.dropLast(1)
        return text.take(maxChars)
    }
}

data class MmsSendConf(val transactionId: String?, val messageId: String?, val responseStatus: Int) {
    val isOk: Boolean get() = responseStatus == PduHeaders.RESPONSE_STATUS_OK
}

/** A part the kid sends: the main process's own bytes (D8/D11: its re-encoded JPEG, or text as UTF-8). */
class MmsOutgoingPart(val contentType: String, val data: ByteArray) {
    internal val isText: Boolean get() = contentType == "text/plain"

    internal fun fileName(index: Int): String {
        val n = index.toString().padStart(3, '0')
        return when (contentType) {
            "text/plain" -> "text$n.txt"
            "image/jpeg" -> "image$n.jpg"
            "image/png" -> "image$n.png"
            "image/gif" -> "image$n.gif"
            else -> "part$n.dat"
        }
    }

    companion object {
        fun text(text: String): MmsOutgoingPart = MmsOutgoingPart("text/plain", text.toByteArray(Charsets.UTF_8))

        fun jpeg(bytes: ByteArray): MmsOutgoingPart = MmsOutgoingPart("image/jpeg", bytes)

        /** QA N3: the types a part may have; no SMIL or multipart from a caller (the codec writes the SMIL). */
        val SENDABLE: Set<String> = setOf("text/plain", "image/jpeg", "image/png", "image/gif")
    }
}

sealed interface MmsComposeResult {
    class Ok(val bytes: ByteArray) : MmsComposeResult

    data class Failed(val reason: MmsComposeFailure) : MmsComposeResult
}

enum class MmsComposeFailure {
    NO_RECIPIENTS,
    TOO_MANY_RECIPIENTS,
    BAD_RECIPIENT,
    NO_PARTS,
    TOO_MANY_PARTS,
    BAD_PART,
    TOO_LARGE,

    /** QA M1: a control character in the subject, or one longer than [MmsCodec.MAX_SEND_SUBJECT_CHARS]. */
    BAD_SUBJECT,

    /** QA M1: a transaction id outside `[\x21-\x7E]{1,64}`. */
    BAD_TRANSACTION_ID,
    COMPOSER_ERROR,
}

enum class MmsNotifyStatus(val octet: Int) {
    RETRIEVED(PduHeaders.STATUS_RETRIEVED),
    DEFERRED(PduHeaders.STATUS_DEFERRED),
    REJECTED(PduHeaders.STATUS_REJECTED),
}
