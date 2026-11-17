package com.kidslauncher.mdm.push.up

import com.kidslauncher.mdm.push.up.vendor.RaiseToForeground

/** A request from an app, read in full by [UpReceiver] before `goAsync()` (M1): nothing later touches the Intent. */
sealed interface UpRequest {
    /** REGISTER from [sender] (Android's shared identity, never an extra). */
    data class Register(val sender: String, val token: String, val message: String?, val vapid: String?) : UpRequest {
        override fun toString() = "Register(sender=$sender)"
    }

    data class Unregister(val sender: String, val token: String) : UpRequest {
        override fun toString() = "Unregister(sender=$sender)"
    }

    /** MESSAGE_ACK: [sender] is `null` when the connector didn't share its identity (it never does for this action). */
    data class Ack(val sender: String?, val token: String, val id: String) : UpRequest {
        override fun toString() = "Ack(sender=$sender)"
    }
}

/** The server side of the engine: the device API of design 108 §3.7, on the executor's thread (blocking). */
interface UpServer {
    sealed interface Registered {
        data class Endpoint(val endpoint: String) : Registered

        /** A 409's `refused` word. */
        data class Refused(val reason: String) : Registered

        /** A 400 (or an answer that can't be read). */
        data object Invalid : Registered

        /** No network, a timeout or a 5xx: the connector retries. */
        data object Network : Registered
    }

    fun register(body: UpRegisterBody): Registered

    /** True when the server has no such registration any more (204). */
    fun unregister(reg: String): Boolean

    /** One pull: [acks] deleted, then the next batch; `null` when it failed (the acks are kept). */
    fun pull(acks: List<String>): UpMessagesAnswer?
}

/** The phone side: the connector's broadcasts, the target app's state, and the raise-and-send. */
interface UpPhone {
    fun newEndpoint(pkg: String, token: String, endpoint: String, id: String)

    fun registrationFailed(pkg: String, token: String, reason: String)

    fun unregistered(pkg: String, token: String)

    fun appState(pkg: String): UpAppState

    /** Raises [pkg] and sends [message]; the engine's [UpEngine.sent] is called once its broadcast went out. */
    fun deliver(pkg: String, message: RaiseToForeground.Message)

    /** Asks for a pull after [delayMs] (the acks of sends that happened after the last pull). */
    fun pullLater(delayMs: Long)
}

/**
 * Design 108's distributor logic (§2, §5): REGISTER, UNREGISTER and MESSAGE_ACK; the pull and the delivery, an ack
 * only after the send (H1); the policy's reconcile and the origin's rewrite (M4); an app removed or its data cleared.
 * Runs on [UpRuntime]'s one executor only, so it holds no lock.
 *
 * **Before the first unlock nothing here touches the store (L6):** every entry checks [unlocked] first, and [store] is
 * a factory called only after it (`UpRuntimeLockedTest`) - the main process starts before the first unlock
 * (`BootCallReceiver`, `KidInCallService`), and the store is credential-encrypted.
 */
class UpEngine(
    private val unlocked: () -> Boolean,
    private val store: () -> UpStore,
    private val view: () -> UpView,
    private val server: () -> UpServer?,
    private val phone: UpPhone,
    private val clock: () -> Long = System::currentTimeMillis,
    private val log: (String) -> Unit = {},
) {
    /** Keys whose broadcast was sent and that the server hasn't been told of yet (H1: only these are acknowledged). */
    private val acks = LinkedHashSet<String>()

    /** The `reg` of each message handed to [UpPhone.deliver] and not yet sent - for its delivery time. */
    private val delivering = HashMap<String, String>()

    /** When each registration's last message went out, written with the next pull. */
    private val deliveredAt = HashMap<String, Long>()

    private var pullRunning = false
    private var pullAgain = false

    /** An app's request, from [UpReceiver]. */
    fun handle(request: UpRequest) {
        if (!unlocked()) return
        when (request) {
            is UpRequest.Register -> register(request)
            is UpRequest.Unregister -> unregister(request)
            is UpRequest.Ack -> ack(request)
        }
    }

    private fun rowsOrNull(): List<UpRow>? = when (val read = store().read()) {
        is UpRead.Rows -> read.rows
        UpRead.Unreadable -> {
            log("The registrations don't open - nothing changed")
            null
        }
    }

    private fun register(request: UpRequest.Register) {
        val pkg = request.sender
        val rows = rowsOrNull() ?: return phone.registrationFailed(pkg, request.token, UpRules.failedReason("network"))
        val store = store()
        val now = clock()
        when (val decision = UpRules.registerDecision(view(), pkg, request.token, rows, store.churn(), now)) {
            is RegisterDecision.Refuse -> {
                log("REGISTER of $pkg refused: ${decision.reason}")
                phone.registrationFailed(pkg, request.token, UpRules.failedReason(decision.reason))
            }
            is RegisterDecision.Known -> {
                val row = decision.row
                val message = UpRules.cutMessage(request.message)
                if (message != null && message != row.message) store.write(rows.map { if (it.reg == row.reg) it.copy(message = message) else it })
                phone.newEndpoint(pkg, request.token, row.endpoint!!, UpRules.newId())
            }
            is RegisterDecision.Retry -> call(decision.row, retryOnConflict = true)
            RegisterDecision.New -> {
                val row = newRow(request, now)
                if (!store.write(rows + row)) return phone.registrationFailed(pkg, request.token, UpRules.failedReason("network"))
                store.setChurn(UpRules.churnAdd(store.churn(), pkg, now))
                call(row, retryOnConflict = true)
            }
        }
    }

    private fun newRow(request: UpRequest.Register, now: Long) = UpRow(
        reg = UpRules.newReg(),
        pkg = request.sender,
        token = request.token,
        secret = UpRules.newSecret(),
        createdAtMs = now,
        vapid = request.vapid,
        message = UpRules.cutMessage(request.message),
    )

    /** The server call of a pending [row]: confirmed and `NEW_ENDPOINT`, or the refusal; a conflict (a `reg` or secret
     * the server already knows, e.g. tombstoned behind a lost answer) gets one fresh pair. */
    private fun call(row: UpRow, retryOnConflict: Boolean) {
        val api = server() ?: return phone.registrationFailed(row.pkg, row.token, UpRules.failedReason("network"))
        val answer = api.register(UpRegisterBody(row.reg, row.secret, row.pkg, row.vapid))
        val store = store()
        val rows = rowsOrNull() ?: return phone.registrationFailed(row.pkg, row.token, UpRules.failedReason("network"))
        val others = rows.filter { it.reg != row.reg }
        when (answer) {
            is UpServer.Registered.Endpoint -> {
                if (!UpRules.endpointValid(answer.endpoint, row.secret)) {
                    store.write(others)
                    store.setDeletes(store.deletes() + row.reg)
                    log("REGISTER of ${row.pkg}: the server's endpoint isn't one")
                    return phone.registrationFailed(row.pkg, row.token, UpRules.failedReason("invalid"))
                }
                val confirmed = row.copy(endpoint = answer.endpoint, state = UP_CONFIRMED, confirmedAtMs = clock())
                if (!store.write(others + confirmed)) return phone.registrationFailed(row.pkg, row.token, UpRules.failedReason("network"))
                phone.newEndpoint(row.pkg, row.token, answer.endpoint, UpRules.newId())
            }
            is UpServer.Registered.Refused -> {
                store.write(others)
                if (answer.reason == "conflict" && retryOnConflict) {
                    val fresh = row.copy(reg = UpRules.newReg(), secret = UpRules.newSecret(), createdAtMs = clock())
                    if (store.write(others + fresh)) return call(fresh, retryOnConflict = false)
                }
                log("REGISTER of ${row.pkg} refused by the server: ${answer.reason}")
                phone.registrationFailed(row.pkg, row.token, UpRules.failedReason(answer.reason))
            }
            UpServer.Registered.Invalid -> {
                store.write(others)
                phone.registrationFailed(row.pkg, row.token, UpRules.failedReason("invalid"))
            }
            // The row stays pending: the connector's retry sends the same reg and secret.
            UpServer.Registered.Network -> phone.registrationFailed(row.pkg, row.token, UpRules.failedReason("network"))
        }
    }

    private fun unregister(request: UpRequest.Unregister) {
        val rows = rowsOrNull() ?: return
        val row = UpRules.unregisterDecision(request.sender, request.token, rows) ?: return
        remove(listOf(row), rows)
        phone.unregistered(row.pkg, row.token)
    }

    /** Only the app's liveness (§5.6, R2), written at most hourly unless it answers the ping. */
    private fun ack(request: UpRequest.Ack) {
        val rows = rowsOrNull() ?: return
        val row = UpRules.ackDecision(request.sender, request.token, rows) ?: return
        val now = clock()
        val answersPing = row.pingId != null && row.pingId == request.id
        if (!answersPing && row.lastAppAckMs != null && now - row.lastAppAckMs < UpRules.HOUR_MS) return
        store().write(rows.map { if (it.reg == row.reg) it.copy(lastAppAckMs = now, pingId = null, pingSentMs = null) else it })
    }

    /** [pkg] was uninstalled or its data cleared (its tokens are gone): its rows removed, a `DELETE` each, nothing sent. */
    fun packageGone(pkg: String) {
        if (!unlocked()) return
        val rows = rowsOrNull() ?: return
        val gone = rows.filter { it.pkg == pkg }
        if (gone.isNotEmpty()) remove(gone, rows)
    }

    /** Rows out of the store, their `DELETE`s queued and tried. */
    private fun remove(gone: List<UpRow>, rows: List<UpRow>) {
        val store = store()
        val regs = gone.map { it.reg }.toSet()
        store.write(rows.filter { it.reg !in regs })
        store.setDeletes(store.deletes() + regs)
        retryDeletes()
    }

    /** The queued `DELETE`s, in order, until one fails (no network: the next sync tries again). */
    private fun retryDeletes() {
        val store = store()
        val queued = store.deletes()
        if (queued.isEmpty()) return
        val api = server() ?: return
        val done = mutableSetOf<String>()
        for (reg in queued) {
            if (!api.unregister(reg)) break
            done += reg
        }
        if (done.isNotEmpty()) store.setDeletes(store.deletes().filter { it !in done })
    }

    /**
     * A fresh policy fetched at [requestStartMs] was accepted (design 108 §2.3, §5.5): the reconcile, the origin's
     * rewrite, the apps no longer installed, the queued `DELETE`s, then a pull while UP is on. [regs] is the policy's
     * `regs` (`null`: the key missing, an older server).
     */
    fun policyAccepted(regs: List<String>?, requestStartMs: Long) {
        if (!unlocked()) return
        val rows = rowsOrNull() ?: return
        val view = view()
        val now = clock()
        val reconcile = UpRules.reconcile(rows, view, regs.orEmpty(), requestStartMs, now)
        var keep = reconcile.keep
        val store = store()
        // The backstop for a missed PACKAGE_FULLY_REMOVED: an app no longer installed (a hidden one is).
        val uninstalled = keep.filter { phone.appState(it.pkg) == UpAppState.UNINSTALLED }
        keep = keep - uninstalled.toSet()
        val origin = view.origin
        val rewritten = if (view.on && origin != null) UpRules.rewrite(keep, origin) else emptyList()
        if (rewritten.isNotEmpty()) {
            val byReg = rewritten.associateBy { it.reg }
            keep = keep.map { byReg[it.reg] ?: it }
        }
        if (keep != rows) store.write(keep)
        val deletes = reconcile.drop.filter { it.deleteOnServer }.map { it.row.reg } + uninstalled.map { it.reg } + reconcile.deleteUnknown
        if (deletes.isNotEmpty()) store.setDeletes(store.deletes() + deletes)
        for (drop in reconcile.drop) if (drop.tellApp) phone.unregistered(drop.row.pkg, drop.row.token)
        for (row in rewritten) phone.newEndpoint(row.pkg, row.token, row.endpoint!!, UpRules.newId())
        retryDeletes()
    }

    /** A pull was asked for (an `up` event, a reopen, a sync, the acks of late sends): one runs, one more trails. */
    fun requestPull(reason: String): Boolean {
        if (!unlocked()) return false
        if (!view().on) return false
        if (pullRunning) {
            pullAgain = true
            return false
        }
        log("Pull for $reason")
        pullRunning = true
        return true
    }

    /**
     * One step of the pull (§5.5), after [requestPull] said to run: the sent keys acknowledged and the next batch
     * fetched, each message planned and handed to the raise. True when another step should follow (more, or a request
     * that came meanwhile); false when the pull is over.
     */
    fun pullStep(): Boolean {
        val rows = if (unlocked() && view().on) rowsOrNull() else null
        if (rows == null) return finishPull(false)
        val api = server() ?: return finishPull(false)
        // A send that never happened leaves its entry; the map stays small whatever happens.
        if (delivering.size > DELIVERING_MAX) delivering.clear()
        persistDelivered(rows)
        val sending = acks.take(ACK_MAX)
        val answer = api.pull(sending)
        if (answer == null) return finishPull(false)
        acks.removeAll(sending.toSet())
        val now = rowsOrNull() ?: return finishPull(false)
        val removed = mutableListOf<UpRow>()
        for (message in answer.messages) {
            // Handed to the raise and not sent yet, or sent and not acknowledged yet: the server sends it again until the
            // ack, which is no reason to broadcast it twice.
            if (message.key in delivering || message.key in acks) continue
            when (val plan = UpDelivery.plan(message, now - removed.toSet()) { phone.appState(it) }) {
                is UpPlan.Deliver -> {
                    val bytes = try {
                        java.util.Base64.getDecoder().decode(message.body)
                    } catch (e: IllegalArgumentException) {
                        null
                    }
                    if (bytes == null || bytes.isEmpty()) {
                        acks += message.key
                        continue
                    }
                    delivering[message.key] = plan.row.reg
                    phone.deliver(plan.row.pkg, RaiseToForeground.Message(plan.row.token, bytes, message.key))
                }
                is UpPlan.Drop -> {
                    acks += message.key
                    plan.removeRow?.let { removed += it }
                }
            }
        }
        if (removed.isNotEmpty()) remove(removed, now)
        val again = answer.more || pullAgain || acks.size > ACK_MAX
        return finishPull(again)
    }

    private fun finishPull(again: Boolean): Boolean {
        pullAgain = false
        if (!again) pullRunning = false
        return again
    }

    /** The broadcast of [message] went out (H1): only now may its key be acknowledged; the ack rides the next pull. */
    fun sent(message: RaiseToForeground.Message) {
        acks += message.id
        delivering.remove(message.id)?.let { deliveredAt[it] = clock() }
        phone.pullLater(ACK_FLUSH_MS)
    }

    private fun persistDelivered(rows: List<UpRow>) {
        if (deliveredAt.isEmpty()) return
        val times = HashMap(deliveredAt)
        deliveredAt.clear()
        if (rows.none { it.reg in times }) return
        store().write(rows.map { row -> times[row.reg]?.let { row.copy(lastDeliveredMs = it) } ?: row })
    }

    /** The keys waiting to be acknowledged (tests). */
    fun pendingAcks(): List<String> = acks.toList()

    companion object {
        /** The sends' acks are flushed after the raise's 5 s (one request for a burst). */
        const val ACK_FLUSH_MS = 6_000L

        private const val DELIVERING_MAX = 500
    }
}
