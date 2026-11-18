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

        /** A 400: the server says the key (or the body) is not valid. */
        data object Invalid : Registered

        /** Any other 4xx (401, 403, 408, 413, 429, a 409 without a body) or an answer that can't be read ([code] 0): not a
         * verdict on a key (L2) - a REGISTER treats it as [Invalid], a key post keeps its key pending. */
        data class Rejected(val code: Int) : Registered

        /** No network, a timeout or a 5xx: the connector retries. */
        data object Network : Registered
    }

    fun register(body: UpRegisterBody): Registered

    /** True when the server has no such registration any more (204). */
    fun unregister(reg: String): Boolean

    /** One pull: [acks] deleted, then the next batch; `null` when it failed (the acks are kept). [held]: the first run
     * after a hold (design 115 §2.5). */
    fun pull(acks: List<String>, held: Boolean = false): UpMessagesAnswer?
}

/** The phone side: the connector's broadcasts, the target app's state, and the raise-and-send. */
interface UpPhone {
    fun newEndpoint(pkg: String, token: String, endpoint: String, id: String)

    fun registrationFailed(pkg: String, token: String, reason: String)

    fun unregistered(pkg: String, token: String)

    fun appState(pkg: String): UpAppState

    /** The phone is in use (design 108b §3.3): interactive and not in Doze. A refresh waits for it. */
    fun inUse(): Boolean

    /** Raises [pkg] and sends [message] (a MESSAGE, or a NEW_ENDPOINT of design 108b: a ping, a refresh, the origin's
     * rewrite); the engine's [UpEngine.sent] is called once its broadcast went out. */
    fun deliver(pkg: String, message: RaiseToForeground.Message)

    /** Asks for a pull after [delayMs] (the acks of sends that happened after the last pull). */
    fun pullLater(delayMs: Long)

    /** Design 115: whether the apps have no network now (the VPN's sink), so the queue stays on the server. */
    fun hold(): UpHold

    /** Design 115 §2.4: the "net" pull, 2 s after the last ask (each ask moves it), with its own wake lock; it checks
     * the hold again when it runs and tries once more 10 s later when only the wanted shape still holds. */
    fun pullAfterHold()
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
    /** This boot's count (`Settings.Global.BOOT_COUNT`): Q1's once-per-boot re-send of an outstanding ping. */
    private val boot: () -> Int = { 0 },
) {
    /** Keys whose broadcast was sent and that the server hasn't been told of yet (H1: only these are acknowledged). */
    private val acks = LinkedHashSet<String>()

    /** The `reg` of each message handed to [UpPhone.deliver] and neither sent nor failed yet. */
    private val delivering = HashMap<String, String>()

    /** When each registration's last message went out, written with the next pull. */
    private val deliveredAt = HashMap<String, Long>()

    /** MESSAGE_ACK's rate limit (code QA L3): when each token's ack was last read, and this minute's reads. */
    private val ackSeen = HashMap<String, Long>()
    /** The ids of the pings this process sent (M1): an answer to one skips the ack limits. */
    private val pingsOut = HashSet<String>()
    private var ackMinute = -1L
    private var ackReads = 0

    private var pullRunning = false
    private var pullAgain = false

    /** Design 115 §2.5 (QA M5, the one owner): a hold was seen since the last pull run that said so. Every step of the
     * next run posts `held`; cleared only when such a run ends with an answer. Memory only: a new process opens a new
     * stream generation, so its hand-outs never count the hold anyway. */
    @Volatile
    private var heldSeen = false

    /** A hold was seen and no pull run has said so yet (the runtime's settle after a shape change reads it). */
    val held: Boolean get() = heldSeen

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
        when (val decision = UpRules.registerDecision(view, pkg, request.token, rows, store.churn(), now, request.vapid)) {
            is RegisterDecision.Refuse -> {
                log("REGISTER of $pkg refused: ${decision.reason}")
                phone.registrationFailed(pkg, request.token, UpRules.failedReason(decision.reason))
            }
            is RegisterDecision.Known -> {
                val row = decision.row
                val message = UpRules.cutMessage(request.message)
                var updated = row
                if (message != null && message != row.message) updated = updated.copy(message = message)
                if (decision.pendingVapid != null) updated = updated.copy(vapidPending = decision.pendingVapid)
                if (updated != rows.first { it.reg == row.reg }) store.write(rows.map { if (it.reg == row.reg) updated else it })
                phone.newEndpoint(pkg, request.token, row.endpoint!!, UpRules.newId())
            }
            is RegisterDecision.Update -> {
                // H1: answered from the store at once (the old endpoint, which the old key still serves); the new key is
                // posted after, never as `REGISTRATION_FAILED` (connector 3.0.9 would drop the instance's keys on it).
                phone.newEndpoint(pkg, request.token, decision.row.endpoint!!, UpRules.newId())
                val message = UpRules.cutMessage(request.message)
                val pending = decision.row.copy(vapidPending = decision.vapid, keyTryMs = now, message = message ?: decision.row.message)
                if (store.write(rows.map { if (it.reg == pending.reg) pending else it })) postKey(pending)
            }
            is RegisterDecision.Retry -> {
                // A pending row is a server call anyway: retried with the newest key (design 108b §4.6).
                val row = decision.row
                call(request.vapid?.let { row.copy(vapid = it) } ?: row, retryOnConflict = true)
            }
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
                store.clearNote(row.pkg, NOTE_UNANSWERED)
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
            UpServer.Registered.Invalid, is UpServer.Registered.Rejected -> {
                store.write(others)
                phone.registrationFailed(row.pkg, row.token, UpRules.failedReason("invalid"))
            }
            // The row stays pending: the connector's retry sends the same reg and secret.
            UpServer.Registered.Network -> phone.registrationFailed(row.pkg, row.token, UpRules.failedReason("network"))
        }
    }

    /**
     * The pending key of a confirmed [row] posted with its `reg` and secret (design 108b §4.6): a 200 makes it the row's
     * key; a 400 (not a P-256 point) drops it, keeps the old key and notes it; no network, a 5xx, a 401/408/429 or anything else
     * keeps it pending for a later policy (L2). Never an answer to the app.
     */
    private fun postKey(row: UpRow) {
        val key = row.vapidPending ?: return
        val api = server() ?: return
        val answer = api.register(UpRegisterBody(row.reg, row.secret, row.pkg, key))
        val store = store()
        val rows = rowsOrNull() ?: return
        val current = rows.firstOrNull { it.reg == row.reg && it.vapidPending == key } ?: return
        val updated = when {
            answer is UpServer.Registered.Endpoint && UpRules.endpointValid(answer.endpoint, row.secret) -> {
                store.clearNote(row.pkg, NOTE_BAD_KEY)
                current.copy(vapid = key, vapidPending = null)
            }
            // L2: only a 400 says the key is no P-256 point; anything else (401, 408, 429, 5xx, no network) keeps it pending.
            answer is UpServer.Registered.Invalid -> {
                store.note(UpNote(row.pkg, NOTE_BAD_KEY, clock()))
                current.copy(vapidPending = null)
            }
            else -> return
        }
        store.write(rows.map { if (it.reg == updated.reg) updated else it })
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
        // M1: the answer to a ping this process sent is never dropped by the limits below.
        val pingAnswer = request.id in pingsOut
        if (!pingAnswer) {
            ackSeen[request.token]?.let { if (now - it in 0 until UpRules.HOUR_MS) return }
            val minute = now / 60_000
            if (minute != ackMinute) {
                ackMinute = minute
                ackReads = 0
            }
            if (++ackReads > ACK_READS_PER_MINUTE) return
        }
        if (ackSeen.size >= ACK_SEEN_MAX) ackSeen.clear()
        ackSeen[request.token] = now
        val rows = rowsOrNull() ?: return
        val row = UpRules.ackDecision(request.sender, request.token, rows) ?: return
        val answersPing = row.pingId != null && row.pingId == request.id
        // L1: a stamp ahead of the clock is rewritten as now, never skipped.
        val lastAck = row.lastAppAckMs?.takeIf { it <= now + UpRules.CLOCK_SLACK_MS }
        if (!answersPing && lastAck != null && now - lastAck < UpRules.HOUR_MS) return
        pingsOut.remove(request.id)
        store().write(
            rows.map {
                if (it.reg == row.reg) it.copy(lastAppAckMs = now, pingId = null, pingSentMs = null, pingLastSendMs = null, pingBoot = null) else it
            },
        )
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
        // L1: a stamp more than a day ahead of the clock is written back as now.
        keep = keep.map { UpRules.clamped(it, now) }
        // Design 108b: the liveness ping and the refresh, on the kept rows (the rewritten endpoints included): one
        // NEW_ENDPOINT per row (L2), the ping's id when a ping is due.
        val pass = if (view.on) {
            UpRules.pass(keep, view.refresh, boot(), now, phone.inUse(), held = phone.hold() != UpHold.NONE)
        } else {
            UpRules.UpPass(emptyList(), emptyList())
        }
        keep = applyPass(keep, pass)
        // L8: nothing is sent for a pass whose stamps couldn't be stored (it would send again at every sync and unlock).
        val stored = keep == rows || store.write(keep)
        val deletes = reconcile.drop.filter { it.deleteOnServer }.map { it.row.reg } + uninstalled.map { it.reg } +
            reconcile.deleteUnknown + pass.dead.map { it.reg }
        if (deletes.isNotEmpty()) store.setDeletes(store.deletes() + deletes)
        for (drop in reconcile.drop) if (drop.tellApp) phone.unregistered(drop.row.pkg, drop.row.token)
        if (stored) sendPass(pass, rewritten.filter { r -> pass.sends.none { it.row.reg == r.reg } })
        retryDeletes()
        if (view.on) retryKeys(now)
    }

    /** A fresh or unlocked phone in use again (the receiver's `USER_PRESENT`): the refreshes that waited, and anything
     * else a pass finds due. */
    fun userPresent() {
        if (!unlocked()) return
        val view = view()
        if (!view.on) return
        val rows = rowsOrNull() ?: return
        val now = clock()
        val clamped = rows.map { UpRules.clamped(it, now) }
        val pass = UpRules.pass(clamped, view.refresh, boot(), now, phone.inUse(), held = phone.hold() != UpHold.NONE)
        val after = applyPass(clamped, pass)
        if (pass.sends.isEmpty() && pass.dead.isEmpty() && after == rows) return
        val store = store()
        if (!store.write(after)) return
        if (pass.dead.isNotEmpty()) store.setDeletes(store.deletes() + pass.dead.map { it.reg })
        sendPass(pass, emptyList())
        if (pass.dead.isNotEmpty()) retryDeletes()
    }

    private fun applyPass(rows: List<UpRow>, pass: UpRules.UpPass): List<UpRow> {
        val updated = pass.sends.associate { it.row.reg to it.row }
        val dead = pass.dead.map { it.reg }.toSet()
        return rows.filter { it.reg !in dead }.map { updated[it.reg] ?: it }
    }

    /** The pass's broadcasts, through the raise: the sends, and the [rewritten] endpoints with a fresh id; the dead rows
     * told `UNREGISTERED` and noted. */
    private fun sendPass(pass: UpRules.UpPass, rewritten: List<UpRow>) {
        for (row in rewritten) raiseNewEndpoint(row, UpRules.newId())
        for (send in pass.sends) {
            log("NEW_ENDPOINT ${send.kind} for ${send.row.pkg}")
            if (send.kind != "refresh") {
                if (pingsOut.size > PINGS_OUT_MAX) pingsOut.clear()
                pingsOut += send.id
            }
            raiseNewEndpoint(send.row, send.id)
        }
        for (row in pass.dead) {
            log("${row.pkg} didn't answer - unregistered")
            store().note(UpNote(row.pkg, NOTE_UNANSWERED, clock()))
            phone.unregistered(row.pkg, row.token)
        }
    }

    private fun raiseNewEndpoint(row: UpRow, id: String) =
        phone.deliver(row.pkg, RaiseToForeground.Message.newEndpoint(row.token, row.endpoint!!, id))

    /** The pending keys due (design 108b §4.6; L4: a failed post is retried at the next policy, at least 5 minutes on - the hour of M4 bounds the REGISTER-driven calls), at most [DELETES_BUDGET_MS] of calls. */
    private fun retryKeys(now: Long) {
        val started = ticks()
        val due = (rowsOrNull() ?: return).filter { it.confirmed && it.vapidPending != null && UpRules.keyRetryDue(it, now) }
        for (row in due) {
            if (ticks() - started >= DELETES_BUDGET_MS) break
            val rows = rowsOrNull() ?: return
            val current = rows.firstOrNull { it.reg == row.reg } ?: continue
            val tried = current.copy(keyTryMs = now)
            if (!store().write(rows.map { if (it.reg == tried.reg) tried else it })) return
            postKey(tried)
        }
    }

    /**
     * Design 115: the runtime refused a pull for the hold before it took the wake lock, or (with [askPull]) a MESSAGE's
     * send found the phone held (QA M1 (c)): noted, and for a send the "net" pull asked - a hold that ends with no change
     * of shape (a moment's disagreement of the views) still gets its pull.
     */
    fun noteHeld(askPull: Boolean = false) {
        heldSeen = true
        if (askPull) phone.pullAfterHold()
    }

    /**
     * Design 115 §2.4: the VPN's shape changed (a tunnel, a decision, the service gone). A hold is noted; once any hold
     * was seen, the "net" pull is asked (QA M1 (b): not a transition judged at this one call - the pull checks again).
     */
    fun shapeChanged() {
        if (!unlocked() || !view().on) return
        if (phone.hold() != UpHold.NONE) heldSeen = true
        if (heldSeen) phone.pullAfterHold()
    }

    /** A pull was asked for (an `up` event, a reopen, a sync, the acks of late sends): one runs, one more trails. Held
     * (design 115), none runs and the messages stay on the server. */
    fun requestPull(reason: String): Boolean {
        if (!unlocked()) return false
        if (!view().on) return false
        val hold = phone.hold()
        if (hold != UpHold.NONE) {
            heldSeen = true
            log("Pull held (${hold.wire})")
            return false
        }
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
        // Design 115: held at a step (Wi-Fi dropped mid-pull) ends the pull; the acks wait for the first pull after it.
        val hold = phone.hold()
        if (hold != UpHold.NONE) {
            heldSeen = true
            log("Pull held (${hold.wire})")
            return finishPull(false)
        }
        val sending = acks.take(ACK_MAX)
        val held = heldSeen
        val answer = api.pull(sending, held)
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
        // QA M5: a run that said `held` and got its answers ends the mark; a failed call above keeps it for the next run.
        if (held && !again) heldSeen = false
        return finishPull(again)
    }

    private fun finishPull(again: Boolean): Boolean {
        pullAgain = false
        if (!again) pullRunning = false
        return again
    }

    /** The broadcast of [message] went out (H1): only now may its key be acknowledged; the ack rides the next pull. */
    fun sent(message: RaiseToForeground.Message) {
        // A NEW_ENDPOINT (a ping, a refresh, the rewrite) is no server message: nothing to acknowledge (design 108b §3.3).
        if (message.kind != RaiseToForeground.Kind.MESSAGE) return
        acks += message.id
        delivering.remove(message.id)?.let { deliveredAt[it] = clock() }
        phone.pullLater(ACK_FLUSH_MS)
    }

    /** The broadcast of [message] couldn't be sent (code QA M1): no ack, and the next pull may deliver it again. */
    fun failed(message: RaiseToForeground.Message) {
        if (message.kind != RaiseToForeground.Kind.MESSAGE) {
            lostNewEndpoint(message)
            return
        }
        delivering.remove(message.id)
    }

    /** A `NEW_ENDPOINT` that couldn't be sent (M1): its stamps were stored before the send, so the row is marked to go
     * again - a ping at the next pass (its boot cleared), a refresh when next listed (its stamp cleared). A throwing send
     * never counts as sent. */
    private fun lostNewEndpoint(message: RaiseToForeground.Message) {
        if (!unlocked()) return
        val rows = rowsOrNull() ?: return
        val row = rows.firstOrNull { it.token == message.token } ?: return
        val marked = if (row.pingId == message.id) row.copy(pingBoot = null) else row.copy(refreshMs = null)
        if (marked != row) store().write(rows.map { if (it.reg == row.reg) marked else it })
    }

    /** The debug hook's way to drop the in-memory ack limits of [token] (the gate ages stamps in the store; debug builds). */
    fun forgetAcks(token: String?) {
        if (token == null) ackSeen.clear() else ackSeen.remove(token)
        ackReads = 0
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
        private const val PINGS_OUT_MAX = 200
    }
}
