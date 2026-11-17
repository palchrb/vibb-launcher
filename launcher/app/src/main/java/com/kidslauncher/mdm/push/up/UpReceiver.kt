package com.kidslauncher.mdm.push.up

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log

private const val LOG_TAG = "UpReceiver"

/** The extras of a request, as [UpRequests.read] sees them: [has] the key at all, [string] its value if it is a String
 * (`null` for another type). */
interface UpExtrasView {
    fun has(key: String): Boolean

    fun string(key: String): String?
}

/**
 * Reads an app's request (design 108 §5.1), pure (`UpReceiverReadTest`):
 * - the sender is Android's shared identity (`getSentFromPackage()`, API 34+, the connector's `FLAG_SHARE_IDENTITY`)
 *   only. **A deliberate deviation (L4):** never the `pi` extra, never AND_2's `application` extra - either would let
 *   any app name another app's package. So REGISTER and UNREGISTER without an identity are ignored; such a REGISTER is
 *   [Read.OldConnector], noted once in the PIN-gated diagnostics row.
 * - MESSAGE_ACK carries no identity (the connector sends it without the flag), so it is taken on its token alone
 *   ([UpRules.ackDecision]).
 * - the known extras are checked by the spec's rule ("Any request containing an extra that do not follow the
 *   specification MUST be ignored"): `token` a non-empty String of at most 100 bytes, `vapid` exactly 87 base64url,
 *   `id` at most 100 bytes, `message` a String (cut to 100 bytes, it is only shown); a known extra of another type
 *   ignores the request. **Unknown extras are ignored**, never a reason to refuse: the connector still sends AND_2's
 *   `features`, `application` and `pi`.
 */
object UpRequests {
    sealed interface Read {
        data class Request(val request: UpRequest) : Read

        /** A REGISTER with no shared identity: an AND_2-only connector (or a forged one) - noted by [pkgHint]'s
         * `application` extra for the diagnostics row only, never trusted. */
        data class OldConnector(val pkgHint: String?) : Read

        data object Ignored : Read
    }

    fun read(action: String?, sender: String?, extras: UpExtrasView): Read {
        val token = text(extras, UpSpec.EXTRA_TOKEN) ?: return Read.Ignored
        if (!UpRules.validToken(token.value)) return Read.Ignored
        return when (action) {
            UpSpec.REGISTER -> {
                val message = text(extras, UpSpec.EXTRA_MESSAGE) ?: return Read.Ignored
                val vapid = text(extras, UpSpec.EXTRA_VAPID) ?: return Read.Ignored
                if (vapid.value != null && !UpRules.validVapid(vapid.value)) return Read.Ignored
                if (sender.isNullOrEmpty()) {
                    val hint = if (extras.has("application")) extras.string("application") else null
                    return Read.OldConnector(hint?.takeIf { it.length <= 255 })
                }
                Read.Request(UpRequest.Register(sender, token.value!!, message.value, vapid.value))
            }
            UpSpec.UNREGISTER -> if (sender.isNullOrEmpty()) Read.Ignored else Read.Request(UpRequest.Unregister(sender, token.value!!))
            UpSpec.MESSAGE_ACK -> {
                val id = text(extras, UpSpec.EXTRA_ID) ?: return Read.Ignored
                if (!UpRules.validId(id.value)) return Read.Ignored
                Read.Request(UpRequest.Ack(sender?.takeIf { it.isNotEmpty() }, token.value!!, id.value!!))
            }
            else -> Read.Ignored
        }
    }

    /** A known extra: absent ([Text] of `null`), a String, or of another type (`null`: the request is ignored). */
    private fun text(extras: UpExtrasView, key: String): Text? {
        if (!extras.has(key)) return Text(null)
        return extras.string(key)?.let { Text(it) }
    }

    @JvmInline
    private value class Text(val value: String?)
}

/** [UpExtrasView] over a [Bundle] (`null`: no extras). Never throws: a bundle that can't be unparcelled reads empty. */
private class BundleExtras(private val bundle: Bundle?) : UpExtrasView {
    override fun has(key: String): Boolean = bundle?.containsKey(key) == true

    override fun string(key: String): String? = bundle?.getString(key)
}

/**
 * The distributor's exported receiver (design 108 §5.1): REGISTER, UNREGISTER and MESSAGE_ACK, nothing else. Installed
 * disabled; [UpRuntime.setComponentsEnabled] switches it with the policy's `unified_push.on` (while disabled,
 * `queryBroadcastReceivers` lists no Vibb One, so no app sees a distributor). The second deliberate exception to "every
 * receiver is `exported=false`", after the device-admin kind (`UpManifestTest`).
 *
 * **Read in `onReceive` (M1):** the sender's identity, the action and every extra are read here, synchronously, into
 * an immutable [UpRequest], which alone is handed to [UpRuntime]'s executor; `onReceive` then returns at once (no
 * `goAsync()`, code QA H1). Nothing on the executor touches the Intent or this receiver.
 */
class UpReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val sender = sentFromPackage
        val read = try {
            UpRequests.read(intent.action, sender, BundleExtras(intent.extras))
        } catch (e: Exception) {
            // A bundle that doesn't unparcel (a hostile Parcelable): ignored.
            Log.w(LOG_TAG, "A request that can't be read: ${e.javaClass.simpleName}")
            UpRequests.Read.Ignored
        }
        when (read) {
            UpRequests.Read.Ignored -> return
            is UpRequests.Read.OldConnector -> {
                Log.w(LOG_TAG, "A REGISTER without a shared identity, ignored (an AND_2-only connector?)")
                UpRuntime.noteOldConnector(context.applicationContext, read.pkgHint)
            }
            // Queued, never awaited (code QA H1): no goAsync() - a broadcast held across a server call would hold up
            // every other broadcast to this process (SMS_DELIVER, alarms) and risk a background ANR. The answer goes
            // to the app as a broadcast of its own; the anchor's foreground service keeps the process.
            is UpRequests.Read.Request -> UpRuntime.handle(context.applicationContext, read.request)
        }
    }
}
