package com.kidslauncher.mdm.push.up

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Design 108: the status report's capability; only to a launcher claiming it does the server send `unified_push`. */
const val UNIFIED_PUSH_CAPABILITY = "unified_push_v1"

/** The UnifiedPush Android spec's names (AND_3.1.0, https://unifiedpush.org/developers/spec/android/): only the action
 * and extra names are shared with the connector (Apache-2.0), which is not a dependency - the distributor side is ours,
 * implemented from the spec. */
object UpSpec {
    const val REGISTER = "org.unifiedpush.android.distributor.REGISTER"
    const val UNREGISTER = "org.unifiedpush.android.distributor.UNREGISTER"
    const val MESSAGE_ACK = "org.unifiedpush.android.distributor.MESSAGE_ACK"

    const val NEW_ENDPOINT = "org.unifiedpush.android.connector.NEW_ENDPOINT"
    const val REGISTRATION_FAILED = "org.unifiedpush.android.connector.REGISTRATION_FAILED"
    const val UNREGISTERED = "org.unifiedpush.android.connector.UNREGISTERED"
    const val MESSAGE = "org.unifiedpush.android.connector.MESSAGE"
    const val RAISE_TO_FOREGROUND = "org.unifiedpush.android.connector.RAISE_TO_FOREGROUND"

    const val EXTRA_TOKEN = "token"
    const val EXTRA_MESSAGE = "message"
    const val EXTRA_VAPID = "vapid"
    const val EXTRA_ID = "id"
    const val EXTRA_ENDPOINT = "endpoint"
    const val EXTRA_REASON = "reason"
    const val EXTRA_BYTES_MESSAGE = "bytesMessage"
    const val EXTRA_PI = "pi"

    /** The Link Activity's answer: an immutable broadcast PendingIntent to this action, from which the connector reads
     * our package (`creatorPackage`). */
    const val DUMMY_APP = "org.unifiedpush.dummy_app"

    const val REASON_ACTION_REQUIRED = "ACTION_REQUIRED"
    const val REASON_NETWORK = "NETWORK"

    /** The deep link the Link Activity answers: `unifiedpush://link`. */
    const val LINK_SCHEME = "unifiedpush"
    const val LINK_HOST = "link"
}

/** The policy's `unified_push` (design 108 §3.8; the shared contract `unified_push.json`'s `policy` rows): [on] = the
 * phone's switch and a public origin set; [origin] the current one (`null` unset, M4); [regs] the registration keys the
 * server holds; [stopped] the packages the parent stopped. Missing (an older server, or a policy fetched before the
 * first report claimed [UNIFIED_PUSH_CAPABILITY]) = off. */
@Serializable
data class UnifiedPushPolicy(
    val on: Boolean = false,
    val origin: String? = null,
    val regs: List<String> = emptyList(),
    val stopped: List<String> = emptyList(),
)

/** `POST /api/devices/up/registrations`: the phone's key [reg] (32 hex) and the endpoint [secret] (27 base64url) it
 * chose, so a retry is idempotent. The connection token never leaves the phone. */
@Serializable
data class UpRegisterBody(
    val reg: String,
    val secret: String,
    @SerialName("package") val pkg: String,
    val vapid: String? = null,
) {
    override fun toString() = "UpRegisterBody(reg=$reg, package=$pkg)"
}

@Serializable
data class UpEndpointAnswer(val endpoint: String) {
    override fun toString() = "UpEndpointAnswer(<redacted>)"
}

@Serializable
data class UpRefusalAnswer(val refused: String)

/** `POST /api/devices/up/messages`: the keys of the messages whose broadcast was sent (H1), at most [ACK_MAX]. */
@Serializable
data class UpAckBody(val ack: List<String>)

@Serializable
data class UpMessagesAnswer(val messages: List<UpWireMessage> = emptyList(), val more: Boolean = false)

/** One queued message: its random [key] (the MESSAGE `id` and the ack), its registration [reg], the sender's
 * [urgency] and the bytes as standard base64 ([body]; never logged). */
@Serializable
data class UpWireMessage(val key: String, val reg: String, val urgency: String = "normal", val body: String) {
    override fun toString() = "UpWireMessage(key=$key, reg=$reg, urgency=$urgency)"
}

/** The server's cap on one pull's acks (`store::ACK_MAX`). */
const val ACK_MAX = 200
