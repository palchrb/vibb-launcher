package com.kidslauncher.mdm.push.up

import com.kidslauncher.mdm.push.up.vendor.RaiseToForeground

/** A request from an app, read in full by [UpReceiver] in `onReceive` (M1): nothing later touches the Intent. */
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
 * Runs on [UpRuntime]'s work executor only, so it holds no lock; the raise and its callbacks run on an executor of
 * their own, so a server call here never delays a send or the raise's timer (code QA H1). Every server call has a time
 * budget: a REGISTER at most one call plus one fast retry, the DELETEs [DELETES_BUDGET_MS] a run, a pull step one call.
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
    /** Monotonic ms for the time budgets. */
    private val ticks: () -> Long = { System.nanoTime() / 1_000_000 },
) {
    /** Keys whose broadcast was sent and that the server hasn't been told of yet (H1: only these are acknowledged). */
    private val acks = LinkedHashSet<String>()

    /** The `reg` of each message handed to [UpPhone.deliver] and neither sent nor failed yet. */
    private val delivering = HashMap<String, String>()

    /** When each registration's last message went out, written with the next pull. */
    private val deliveredAt = HashMap<String, Long>()

    /** MESSAGE_ACK's rate limit (code QA L3): when each token's ack was last read, and this minute's reads. */
    private val ackSeen = HashMap<String, Long>()
    private var ackMinute = -1L
    private var ackReads = 0

    private var pullRunning = false
    private var pullAgain = false

    /** A pull is running or trails (the runtime's wake lock, code QA L2). */
    val pullActive: Boolean get() = pullRunning

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
        val view = view()
        // Code QA L3: an app that may not register is refused from the cached policy, before the sealed store is read.
        if (!view.mayRegister(pkg)) {
            log("REGISTER of $pkg refused before the store")
            return phone.registrationFailed(pkg, request.token, UpRules.failedReason("not_allowed"))
        }
        val rows = rowsOrNull() ?: return phone.registrationFailed(pkg, request.token, UpRules.failedReason("network"))
        val store = store()
        val now = clock()
        when (val decision = UpRules.registerDecision(view, pkg, request.token, rows, store.churn(), now)) {
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
     * the server already knows, e.g. tombstoned behind a lost answer) gets one fresh pair - only when the first answer
     * came fast ([REGISTER_RETRY_WITHIN_MS]), so a REGISTER costs at most one slow call. */
    private fun call(row: UpRow, retryOnConflict: Boolean) {
        val api = server() ?: return phone.registrationFailed(row.pkg, row.token, UpRules.failedReason("network"))
        val started = ticks()
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
                if (answer.reason == "conflict" && retryOnConflict && ticks() - started < REGISTER_RETRY_WITHIN_MS) {
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

    /**
     * Only the app's liveness (§5.6, R2), written at most hourly unless it answers the ping. Code QA L3: ignored while
     * UP is off, and rate-limited before the sealed store is read - a token read within the hour is skipped, and at
     * most [ACK_READS_PER_MINUTE] reads a minute.
     */
    private fun ack(request: UpRequest.Ack) {
        if (!view().on) return
        val now = clock()
        ackSeen[request.token]?.let { if (now - it in 0 until UpRules.HOUR_MS) return }
        val minute = now / 60_000
        if (minute != ackMinute) {
            ackMinute = minute
            ackReads = 0
        }
        if (++ackReads > ACK_READS_PER_MINUTE) return
        if (ackSeen.size >= ACK_SEEN_MAX) ackSeen.clear()
        ackSeen[request.token] = now
        val rows = rowsOrNull() ?: return
        val row = UpRules.ackDecision(request.sender, request.token, rows) ?: return
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

    /** The queued `DELETE`s, in order, until one fails (no network: the next sync tries again) or the run's budget
     * ([DELETES_BUDGET_MS], at most [DELETES_PER_RUN]) is spent. */
    private fun retryDeletes() {
        val store = store()
        val queued = store.deletes()
        if (queued.isEmpty()) return
        val api = server() ?: return
        val started = ticks()
        val done = mutableSetOf<String>()
        for (reg in queued.take(DELETES_PER_RUN)) {
            if (ticks() - started >= DELETES_BUDGET_MS) break
            if (!api.unregister(reg)) break
            done += reg
        }
        if (done.isNotEmpty()) store.setDeletes(store.deletes().filter { it !in done })
    }

    /**
     * A fresh policy fetched at [requestStartMs] was accepted (design 108 §2.3, §5.5): the reconcile, the origin's
     * rewrite, the apps no longer installed, the queued `DELETE`s. [view] is that fresh policy's own (code QA M3: never
     * the cache read back, so a failed cache commit or a corrupt cache can't drop every row); [regs] its `regs`
     * (`null`: the key missing, an older server).
     *
     * A store that doesn't open changes nothing - for [UNREADABLE_GRACE_MS]; after that it is dropped and treated as
     * empty (code QA L4), so the reconcile deletes the server's rows and the apps' endpoints answer 410 (each app
     * registers anew at its next start). No parent action or UI is needed.
     */
    fun policyAccepted(view: UpView, regs: List<String>?, requestStartMs: Long) {
        if (!unlocked()) return
        val store = store()
        val now = clock()
        val rows = when (val read = store.read()) {
            is UpRead.Rows -> {
                store.clearUnreadable()
                read.rows
            }
            UpRead.Unreadable -> {
                val since = store.unreadableSince(now)
                if (now - since < UNREADABLE_GRACE_MS) {
                    log("The registrations don't open - nothing changed")
                    return
                }
                log("The registrations haven't opened for a day - dropped")
                if (!store.reset()) return
                emptyList()
            }
        }
        val reconcile = UpRules.reconcile(rows, view, regs.orEmpty(), requestStartMs, now)
        var keep = reconcile.keep
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
     * fetched, each message planned and handed to the raise. True when another step should follow; false when the pull
     * is over. Code QA M2: any exception ends the pull (then rethrown), so a fault never leaves it "running" for good.
     */
    fun pullStep(): Boolean = try {
        step()
    } catch (e: Exception) {
        finishPull(false)
        throw e
    }

    private fun step(): Boolean {
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
        var handled = 0
        for (message in answer.messages) {
            // Handed to the raise and not sent yet, or sent and not acknowledged yet: the server sends it again until the
            // ack, which is no reason to broadcast it twice.
            if (message.key in delivering || message.key in acks) continue
            handled++
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
        if (removed.isNotEmpty()) remove(removed.distinctBy { it.reg }, now)
        // Code QA M1: `more` loops only when this step handled something new; a batch still in the raise waits for its
        // sends ([sent] asks a pull 6 s later), never spins.
        val again = (answer.more && handled > 0) || pullAgain || acks.size > ACK_MAX
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

    /** The broadcast of [message] couldn't be sent (code QA M1): no ack, and the next pull may deliver it again. */
    fun failed(message: RaiseToForeground.Message) {
        delivering.remove(message.id)
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

        /** A conflict's retry only after a fast first answer: a REGISTER costs one slow call at most. */
        const val REGISTER_RETRY_WITHIN_MS = 5_000L

        /** One run of the queued DELETEs. */
        const val DELETES_BUDGET_MS = 20_000L
        const val DELETES_PER_RUN = 20

        /** A store that doesn't open is kept this long before it is dropped (code QA L4). */
        const val UNREADABLE_GRACE_MS = 24 * UpRules.HOUR_MS

        const val ACK_READS_PER_MINUTE = 30
        private const val ACK_SEEN_MAX = 500
        private const val DELIVERING_MAX = 500
    }
}
