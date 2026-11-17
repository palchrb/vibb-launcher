/*
 * Adapted from UnifiedPush's android-distributor,
 * distributor/src/main/java/org/unifiedpush/android/distributor/RaiseAppToForeground.kt at commit 6daaf14
 * (https://codeberg.org/UnifiedPush/android-distributor), by the UnifiedPush contributors, licensed under the Apache
 * License, Version 2.0 (http://www.apache.org/licenses/LICENSE-2.0; a copy ships as assets/licenses/Apache-2.0.txt).
 *
 * Changed here (design 108 §5.4), the loss fixes first (design QA H1):
 * - a `bindService` that returns false sends the message at once, without the raise (the original queued it whenever
 *   bindService was *called*, so a bind that never connects lost it);
 * - every unbind path - the 5 s timeout, onBindingDied, onNullBinding and onServiceDisconnected - flushes the queue by
 *   sending each message first, without the raise, and only then unbinds; nothing queued is ever cleared unsent (the
 *   original's unbind() did msgsQueue.clear());
 * - a message is reported to [RaiseToForeground]'s `onSent` only after its broadcast was sent, and Vibb One
 *   acknowledges its key to the server only from there, so a crash between the fetch and the send re-delivers instead
 *   of losing; a broadcast that couldn't be sent is reported to `onFailed`, so it can be delivered again;
 * - checkForeground() is gone: Vibb One's process always holds the anchor's foreground service, the precondition the
 *   original tested by scanning the running processes;
 * - hasRaiseToForegroundService() queries the right intent (the original's pre-33 branch queried ACTION_REGISTER); it
 *   runs on 34+ only, in [Platform.hasRaiseService];
 * - send() adds FLAG_INCLUDE_STOPPED_PACKAGES and the message's `id` extra, and an explicit action on every intent (in
 *   [Platform.send]);
 * - the executor is UpRuntime's raise executor: the binding's callbacks and the timeout run there, never behind a
 *   server call, so the state needs no lock;
 * - an app with no such service is sent the message without the raise and noted once;
 * - the Android calls sit behind [Platform], so the queue and its state machine are unit-tested with a fake binder
 *   (RaiseToForegroundTest).
 *
 * Distributed as part of Vibb One under the GNU GPL v3.0 or later (Apache-2.0 code may be included in a GPL-3.0 work).
 */
package com.kidslauncher.mdm.push.up.vendor

/**
 * Raises [app] to the foreground for 5 s while its MESSAGE broadcasts go out (the spec's "MUST raise the application
 * to the foreground importance for 5 seconds"): binding its `RAISE_TO_FOREGROUND` service from Vibb One's
 * foreground-service process lifts its process state, and lets it start a foreground service from the background. One
 * instance per app while bound; [onUnbound] then forgets it.
 */
class RaiseToForeground(
    private val app: String,
    private val platform: Platform,
    private val onSent: (Message) -> Unit,
    private val onUnbound: (RaiseToForeground) -> Unit,
    private val onFailed: (Message) -> Unit = {},
) {
    /** One MESSAGE: the connector's [token], the bytes, and the message's random key [id] (the extra and the ack). */
    class Message(val token: String, val content: ByteArray, val id: String)

    /** What the class needs of Android, on UpRuntime's executor. */
    interface Platform {
        /** [app] declares an exported `RAISE_TO_FOREGROUND` service. */
        fun hasRaiseService(app: String): Boolean

        /** Binds that service with `BIND_AUTO_CREATE`; its callbacks reach [connection] on the executor. False: not
         * bound (no such service, or not allowed). */
        fun bind(app: String, connection: RaiseToForeground): Boolean

        fun unbind(connection: RaiseToForeground)

        /** Sends one MESSAGE broadcast to [app]; throws when it couldn't. */
        fun send(app: String, message: Message)

        /** Runs [action] on the executor after [delayMs]. */
        fun schedule(delayMs: Long, action: () -> Unit): Cancellable

        /** [app] has no raise service: its messages go without the raise (noted once). */
        fun noRaiseService(app: String)
    }

    fun interface Cancellable {
        fun cancel()
    }

    private enum class Bound { BINDING, BOUND, UNBOUND }

    private var bound = Bound.UNBOUND
    private var timeout: Cancellable? = null
    private val queue = ArrayDeque<Message>()

    /** Raises [app] and sends [message]: at once while bound, queued while binding, and after a bind otherwise. */
    fun raiseAndSend(message: Message) {
        when (bound) {
            Bound.BOUND -> {
                delayUnbinding()
                send(message)
            }
            Bound.BINDING -> {
                delayUnbinding()
                queue.addLast(message)
            }
            Bound.UNBOUND -> {
                if (!platform.hasRaiseService(app)) {
                    platform.noRaiseService(app)
                    send(message)
                    return
                }
                val ok = try {
                    platform.bind(app, this)
                } catch (e: Exception) {
                    false
                }
                if (!ok) {
                    // H1: never queued behind a bind that can't connect.
                    send(message)
                    return
                }
                bound = Bound.BINDING
                queue.addLast(message)
                delayUnbinding()
            }
        }
    }

    fun onServiceConnected() {
        if (bound != Bound.BINDING) return
        bound = Bound.BOUND
        flush()
    }

    fun onServiceDisconnected() = unbind()

    fun onBindingDied() = unbind()

    fun onNullBinding() = unbind()

    /** Each new message pushes the unbind 5 s out. */
    private fun delayUnbinding() {
        timeout?.cancel()
        timeout = platform.schedule(RAISE_MS) { unbind() }
    }

    /** H1: every queued message is sent first, without the raise if it never connected; then the unbind. */
    private fun unbind() {
        if (bound == Bound.UNBOUND) return
        flush()
        timeout?.cancel()
        timeout = null
        bound = Bound.UNBOUND
        try {
            platform.unbind(this)
        } catch (e: Exception) {
            // Already gone with the binding.
        }
        onUnbound(this)
    }

    private fun flush() {
        while (queue.isNotEmpty()) send(queue.removeFirst())
    }

    /** Reported to `onSent` only after the broadcast went out, so only then acknowledged; a failed send goes to
     * `onFailed` (never acknowledged, delivered again by a later pull). */
    private fun send(message: Message) {
        val sent = try {
            platform.send(app, message)
            true
        } catch (e: Exception) {
            false
        }
        if (sent) onSent(message) else onFailed(message)
    }

    companion object {
        /** The raise's length after the last message (the spec's 5 seconds). */
        const val RAISE_MS = 5_000L
    }
}
