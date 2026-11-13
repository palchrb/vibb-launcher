package com.kidslauncher.mdm.server

import android.content.Context
import android.content.SharedPreferences
import com.kidslauncher.mdm.server.dto.StatusReportRequest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.ResponseBody
import retrofit2.Response
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.util.zip.GZIPOutputStream

/*
 * The heartbeat report (design 95 §3.5, step 95-T2; the contract `status_heartbeat.json`). The full report is ~8 KB;
 * between two idle syncs only a few fields move (the screen time, the battery, the certificate check's `seen.at_ms`,
 * the re-sent location). So, while the policy says `status_heartbeat`:
 * - the phone hashes its full report without the [HEARTBEAT_FIELDS] ([reportHash]) and sends the full report with
 *   that `hash`; the server keeps the body under it;
 * - while the hash is the one the server took last ([HeartbeatAck]) and that full report is under [FULL_REPORT_EVERY_MS]
 *   old, it sends only `{"heartbeat": true, "hash": ..., <the heartbeat fields>}` ([heartbeatBody]);
 * - the server expands it into the full report it stands for and runs it as one, or answers `{"full_report": true}`
 *   ([isFullReportAnswer]), and the phone sends the full report at once.
 * Any other field changing - an app installed, a call, a capability, a zone, a setting - changes the hash, so a full
 * report goes. A report over 1 KiB (every full one) is gzip'd while the policy says `status_gzip` ([encodeReport]); the
 * server inflates it inside its token check, within its body limit. An older server says neither: the report goes as before.
 */

const val STATUS_HEARTBEAT_CAPABILITY = "status_heartbeat_v1"

/** The report's fields a heartbeat carries and the hash leaves out (the server's `status_heartbeat::HEARTBEAT_FIELDS`,
 * by the contract). */
val HEARTBEAT_FIELDS: List<String> = listOf(
    "lock_reason", "battery", "time_state", "location", "extra_locations", "zone_watch", "server_tls", "data_roaming",
    "mobile_data",
)

/** A full report at least this often, whatever the hash (the server takes a kept body for 7 h). */
const val FULL_REPORT_EVERY_MS = 6 * 60 * 60 * 1000L

/** The report as the server reads it (ServerJson: snake_case, defaults left out). */
fun reportJson(report: StatusReportRequest): JsonObject =
    ServerJson.encodeToJsonElement(StatusReportRequest.serializer(), report).jsonObject

/** SHA-256 (hex) of the report's JSON without the heartbeat fields - the phone's name for the body; the server never
 * computes it. */
fun reportHash(report: JsonObject): String {
    val rest = JsonObject(report.filterKeys { it !in HEARTBEAT_FIELDS })
    return MessageDigest.getInstance("SHA-256").digest(rest.toString().toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
}

/** The full report with its [hash]. */
fun fullBody(report: JsonObject, hash: String): JsonObject = JsonObject(report + ("hash" to JsonPrimitive(hash)))

/** The heartbeat for [report]: the marker, the [hash] and the heartbeat fields the report has. */
fun heartbeatBody(report: JsonObject, hash: String): JsonObject = JsonObject(
    linkedMapOf<String, kotlinx.serialization.json.JsonElement>("heartbeat" to JsonPrimitive(true), "hash" to JsonPrimitive(hash)) +
        report.filterKeys { it in HEARTBEAT_FIELDS },
)

/** The server's `{"full_report": true}`: the heartbeat wasn't taken, a full report goes now. */
fun isFullReportAnswer(code: Int, body: ByteArray?): Boolean {
    if (code != 200 || body == null || body.isEmpty()) return false
    return try {
        ServerJson.parseToJsonElement(body.decodeToString()).jsonObject["full_report"]?.jsonPrimitive?.booleanOrNull == true
    } catch (e: Exception) {
        false
    }
}

/** The body to post: [json] as UTF-8, gzip'd when [gzip] and over 1 KiB (a small heartbeat gains nothing); the second value
 * is the `Content-Encoding` header (`null`: none). */
fun encodeReport(json: JsonObject, gzip: Boolean): Pair<RequestBody, String?> {
    val bytes = json.toString().toByteArray(Charsets.UTF_8)
    val type = "application/json".toMediaType()
    if (!gzip || bytes.size <= 1024) return bytes.toRequestBody(type) to null
    val out = ByteArrayOutputStream()
    GZIPOutputStream(out).use { it.write(bytes) }
    return out.toByteArray().toRequestBody(type) to "gzip"
}

/** The full report the server took last: its [hash], when ([atMs], wall clock) and for whom ([owner]: the server and
 * the token, as a hash). */
data class HeartbeatAck(val hash: String, val atMs: Long, val owner: String)

/** Whether [hash] may go as a heartbeat now. */
fun heartbeatDue(policySays: Boolean, ack: HeartbeatAck?, hash: String, owner: String, nowMs: Long): Boolean =
    policySays && ack != null && ack.hash == hash && ack.owner == owner && nowMs - ack.atMs in 0 until FULL_REPORT_EVERY_MS

/** [HeartbeatAck] in prefs `status_heartbeat` (not secret: hashes). */
class HeartbeatAckStore(private val prefs: SharedPreferences) {
    companion object {
        const val PREFS_NAME = "status_heartbeat"
        private const val HASH = "hash"
        private const val AT_MS = "at_ms"
        private const val OWNER = "owner"

        fun of(context: Context) =
            HeartbeatAckStore(context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE))

        /** The owner key for [serverUrl] and [token]. */
        fun ownerOf(serverUrl: String, token: String): String = MessageDigest.getInstance("SHA-256")
            .digest("$serverUrl\n$token".toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    }

    fun get(): HeartbeatAck? {
        val hash = prefs.getString(HASH, null) ?: return null
        val owner = prefs.getString(OWNER, null) ?: return null
        return HeartbeatAck(hash, prefs.getLong(AT_MS, 0L), owner)
    }

    fun set(ack: HeartbeatAck) {
        prefs.edit().putString(HASH, ack.hash).putLong(AT_MS, ack.atMs).putString(OWNER, ack.owner).commit()
    }

    fun clear() {
        if (prefs.contains(HASH)) prefs.edit().clear().commit()
    }
}

/** What a report's post came back with: the answer's [code], its body (bounded by [read]) and the raw response. */
class SentReport(val code: Int, val isSuccessful: Boolean, val body: ByteArray?, val raw: okhttp3.Response)

/**
 * Sends [report]: a heartbeat when [heartbeatDue], else (or when the server asks for it) the full report - with its
 * hash while [heartbeat] (the policy's `status_heartbeat`), gzip'd while [gzip]. [post] is `MdmApi.sendStatus`; [read]
 * reads an answer's body (bounded) and closes it. Throws what [post] throws.
 */
suspend fun sendReport(
    report: StatusReportRequest,
    heartbeat: Boolean,
    gzip: Boolean,
    owner: String,
    acks: HeartbeatAckStore,
    nowMs: () -> Long,
    read: (ResponseBody?) -> ByteArray?,
    post: suspend (RequestBody, String?) -> Response<ResponseBody>,
): SentReport {
    val json = reportJson(report)
    val hash = reportHash(json)
    if (heartbeatDue(heartbeat, acks.get(), hash, owner, nowMs())) {
        val (body, encoding) = encodeReport(heartbeatBody(json, hash), gzip)
        val answer = post(body, encoding)
        val bytes = read(answer.body() ?: answer.errorBody())
        if (!isFullReportAnswer(answer.code(), bytes)) {
            // A taken heartbeat keeps the ack (and its time: the full report's). One refused (4xx) drops it, so the
            // next report is a full one; a 5xx or no answer leaves it for the next.
            if (answer.code() in 400..499) acks.clear()
            return SentReport(answer.code(), answer.isSuccessful, bytes, answer.raw())
        }
        acks.clear()
    }
    val (body, encoding) = encodeReport(if (heartbeat) fullBody(json, hash) else json, gzip)
    val answer = post(body, encoding)
    val bytes = read(answer.body() ?: answer.errorBody())
    when {
        !answer.isSuccessful -> Unit
        heartbeat -> acks.set(HeartbeatAck(hash, nowMs(), owner))
        else -> acks.clear()
    }
    return SentReport(answer.code(), answer.isSuccessful, bytes, answer.raw())
}
