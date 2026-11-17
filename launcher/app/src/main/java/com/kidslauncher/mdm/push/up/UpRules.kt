package com.kidslauncher.mdm.push.up

import com.kidslauncher.mdm.server.dto.PolicyResponse
import java.security.SecureRandom
import java.util.Base64

/**
 * What the cached policy says about app push (design 108 §3.8): [on] (the phone's switch and an origin), [origin], the
 * app [allowlist] (`null`: an unmanaged phone, where any app may register) and the parent's [stopped] packages.
 */
data class UpView(
    val on: Boolean,
    val origin: String?,
    val allowlist: List<String>?,
    val stopped: List<String>,
    /** The policy's `refresh` rows (design 108b): registrations the app should be asked to re-check. */
    val refresh: List<UpRefresh> = emptyList(),
) {
    companion object {
        /** Off without the key (an older server, design 108 §9 `older_server`) and without an origin. */
        fun of(policy: PolicyResponse?): UpView {
            val up = policy?.unifiedPush
            val origin = up?.origin?.takeIf { it.isNotBlank() }
            return UpView(up?.on == true && origin != null, origin, policy?.allowlist, up?.stopped.orEmpty(), up?.refresh.orEmpty())
        }
    }

    /** The package may hold registrations: on, on the allowlist (any on an unmanaged phone), not stopped. */
    fun mayRegister(pkg: String): Boolean = on && (allowlist == null || pkg in allowlist) && pkg !in stopped
}

/** A REGISTER's way, from [UpRules.registerDecision]. */
sealed interface RegisterDecision {
    /** `REGISTRATION_FAILED(ACTION_REQUIRED)`; [reason] for the log only (the server's words where they exist). */
    data class Refuse(val reason: String) : RegisterDecision

    /** A confirmed registration of this token: `NEW_ENDPOINT` from the store, no network (the spec's "SHOULD NOT create
     * a new endpoint if a valid registration exists"). [pendingVapid]: a different key the app sent, within the hour of
     * the last key call (M4) - kept as the row's pending key, posted by a later policy (design 108b §4.6). */
    data class Known(val row: UpRow, val pendingVapid: String? = null) : RegisterDecision

    /** A confirmed registration re-registered with **another** [vapid] key (design 108b §4.6, H1): `NEW_ENDPOINT` from
     * the store at once, the new key posted in the background. */
    data class Update(val row: UpRow, val vapid: String) : RegisterDecision

    /** A pending one (a lost answer, or no network): the server call again with the same `reg` and secret. */
    data class Retry(val row: UpRow) : RegisterDecision

    /** A new registration: a new `reg` and secret, counted against the churn limit. */
    data object New : RegisterDecision
}

/** A local row the policy's reconcile removes: [tellApp] sends `UNREGISTERED`, [deleteOnServer] queues a `DELETE`. */
data class UpDrop(val row: UpRow, val tellApp: Boolean, val deleteOnServer: Boolean)

/** The reconcile's outcome: the rows [keep]; the rows to [drop]; the server's `regs` this phone doesn't know at all
 * ([deleteUnknown], a `DELETE` each). */
data class UpReconcile(val keep: List<UpRow>, val drop: List<UpDrop>, val deleteUnknown: List<String>)

/**
 * Design 108's pure rules of the launcher's distributor (§2, §5.1, §5.5), tested by `UpRulesTest` from
 * `up_rules.json` and by `UnifiedPushContractTest`.
 */
object UpRules {
    /** 100 registrations per app and 300 per phone: below the spec's "SHOULD be over 1000" on purpose - a kid's phone
     * has a handful of accounts, and a runaway app must not mint thousands of public endpoints (§5.1). */
    const val PER_APP = 100
    const val PER_PHONE = 300

    /** The churn limit (L10, R1-SR L5): new registrations an hour per app and per phone, as the server counts. */
    const val NEW_PER_APP_HOUR = 20
    const val NEW_PER_PHONE_HOUR = 60
    const val HOUR_MS = 3_600_000L
    const val DAY_MS = 24 * HOUR_MS

    /** Design 108b §2: a registration whose app acknowledged nothing for 23 days gets the spec's ping; a ping unanswered
     * 7 more days (and an hour since its last send, Q1) unregisters it. */
    const val PING_AFTER_MS = 23 * DAY_MS
    const val DEAD_AFTER_MS = 7 * DAY_MS

    /** A refresh (§3.3) at most a day apart, counted from an attempt actually made; a key-update call (§4.6) an hour. */
    const val REFRESH_EVERY_MS = DAY_MS
    const val KEY_CALL_EVERY_MS = HOUR_MS

    /** A failed key post is tried again at the next accepted policy at least this long after (L4); a REGISTER-driven call
     * keeps [KEY_CALL_EVERY_MS] (M4). */
    const val KEY_RETRY_MS = 5 * 60_000L

    /** An outstanding ping is sent again every day, besides the boot's re-send (M1, 108b Q1's refinement). */
    const val PING_RESEND_MS = DAY_MS
    const val CLOCK_SLACK_MS = DAY_MS

    /** The spec's 100 bytes for the token, the description and a message id; a VAPID key is 87 base64url. */
    const val TEXT_MAX_BYTES = 100
    const val VAPID_LENGTH = 87

    /** A pending registration no REGISTER retried within a day is dropped (its `DELETE` queued: the server may have
     * made the row behind a lost answer). */
    const val PENDING_MAX_MS = 24 * HOUR_MS

    private val ENDPOINT = Regex("^https://[^/]+/up/[A-Za-z0-9_-]{27}$")
    private val random = SecureRandom()

    fun validToken(token: String?): Boolean = !token.isNullOrEmpty() && token.toByteArray(Charsets.UTF_8).size <= TEXT_MAX_BYTES

    fun validId(id: String?): Boolean = validToken(id)

    fun validVapid(vapid: String): Boolean =
        vapid.length == VAPID_LENGTH && vapid.all { it in 'A'..'Z' || it in 'a'..'z' || it in '0'..'9' || it == '-' || it == '_' }

    /** The app's description, cut to 100 UTF-8 bytes on a character boundary (an MXID may be longer); blank = none. It
     * is shown only in the PIN-gated diagnostics row, so it is cut rather than refused. */
    fun cutMessage(message: String?): String? {
        val text = message?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        if (text.toByteArray(Charsets.UTF_8).size <= TEXT_MAX_BYTES) return text
        val out = StringBuilder()
        var bytes = 0
        var i = 0
        while (i < text.length) {
            val cp = text.codePointAt(i)
            val size = String(Character.toChars(cp)).toByteArray(Charsets.UTF_8).size
            if (bytes + size > TEXT_MAX_BYTES) break
            out.appendCodePoint(cp)
            bytes += size
            i += Character.charCount(cp)
        }
        return out.toString()
    }

    /**
     * Who may register (§5.1), in order: UP on; a token not held by another package (the spec's
     * `REGISTRATION_FAILED`); on the allowlist (any app on an unmanaged phone); not stopped; a known confirmed token is
     * answered from the store, a pending one retried; under the caps (100 per app, 300 per phone); under the churn
     * limit (20 new an hour per app, 60 per phone; [churn] holds each app's new registrations' wall times).
     */
    fun registerDecision(
        view: UpView,
        pkg: String,
        token: String,
        rows: List<UpRow>,
        churn: Map<String, List<Long>>,
        nowMs: Long,
        vapid: String? = null,
    ): RegisterDecision {
        if (!view.on) return RegisterDecision.Refuse("off")
        val same = rows.firstOrNull { it.token == token }
        if (same != null && same.pkg != pkg) return RegisterDecision.Refuse("token_taken")
        if (view.allowlist != null && pkg !in view.allowlist) return RegisterDecision.Refuse("not_allowed")
        if (pkg in view.stopped) return RegisterDecision.Refuse("stopped")
        if (same != null) return if (same.confirmed) knownOrUpdate(same, vapid, nowMs) else RegisterDecision.Retry(same)
        if (rows.count { it.pkg == pkg } >= PER_APP || rows.size >= PER_PHONE) return RegisterDecision.Refuse("limit")
        val lastHour = churnWithin(churn, nowMs)
        if (lastHour[pkg].orEmpty().size >= NEW_PER_APP_HOUR || lastHour.values.sumOf { it.size } >= NEW_PER_PHONE_HOUR) {
            return RegisterDecision.Refuse("busy")
        }
        return RegisterDecision.New
    }

    /**
     * A known, confirmed token (design 108b §4.6): a REGISTER without a key never drops a stored one (H1: Fedilab
     * registers with `null` when its fetch of the instance's key failed) and the same key changes nothing - both are
     * `Known`; **another** key is an `Update`, unless a key call went out within the hour (M4), when it is `Known` with
     * the key pending.
     */
    private fun knownOrUpdate(row: UpRow, vapid: String?, nowMs: Long): RegisterDecision = when {
        // L3: the stored key again while another is pending: the app's key is the stored one, the pending one is dropped.
        vapid == row.vapid && row.vapidPending != null -> RegisterDecision.Known(row.copy(vapidPending = null))
        vapid == null || vapid == row.vapid || vapid == row.vapidPending -> RegisterDecision.Known(row)
        keyCallDue(row, nowMs) -> RegisterDecision.Update(row, vapid)
        else -> RegisterDecision.Known(row, pendingVapid = vapid)
    }

    /** No key-update call for this row within the hour (M4). */
    fun keyCallDue(row: UpRow, nowMs: Long): Boolean {
        val last = stamp(row.keyTryMs, nowMs) ?: return true
        return nowMs - last >= KEY_CALL_EVERY_MS
    }

    /** A key post to retry from a policy (L4): none within [KEY_RETRY_MS]. */
    fun keyRetryDue(row: UpRow, nowMs: Long): Boolean {
        val last = stamp(row.keyTryMs, nowMs) ?: return true
        return nowMs - last >= KEY_RETRY_MS
    }

    /** [row] with every stamp more than a day ahead of [nowMs] set to now (L1): written back, so its age starts counting
     * instead of staying 0 until real time passes it. */
    fun clamped(row: UpRow, nowMs: Long): UpRow = row.copy(
        lastAppAckMs = stamp(row.lastAppAckMs, nowMs),
        pingSentMs = stamp(row.pingSentMs, nowMs),
        pingLastSendMs = stamp(row.pingLastSendMs, nowMs),
        refreshMs = stamp(row.refreshMs, nowMs),
        keyTryMs = stamp(row.keyTryMs, nowMs),
    )

    /** A stamp the rules read (design 108b L3): one more than a day after now (a clock that ran ahead) reads as now, so
     * a negative age never freezes a row; the engine writes it back as now ([clamped], L1). */
    fun stamp(value: Long?, nowMs: Long): Long? = value?.let { if (it > nowMs + CLOCK_SLACK_MS) nowMs else it }

    /** The churn record with only the last hour kept. */
    fun churnWithin(churn: Map<String, List<Long>>, nowMs: Long): Map<String, List<Long>> =
        churn.mapValues { (_, times) -> times.filter { it in (nowMs - HOUR_MS + 1)..nowMs } }.filterValues { it.isNotEmpty() }

    /** [churn] with one more new registration of [pkg] at [nowMs] (the last hour only). */
    fun churnAdd(churn: Map<String, List<Long>>, pkg: String, nowMs: Long): Map<String, List<Long>> {
        val kept = churnWithin(churn, nowMs)
        return kept + (pkg to (kept[pkg].orEmpty() + nowMs).takeLast(NEW_PER_PHONE_HOUR))
    }

    /** UNREGISTER (§5.1): only from the package that owns the token; anything else is ignored. */
    fun unregisterDecision(sender: String, token: String, rows: List<UpRow>): UpRow? =
        rows.firstOrNull { it.token == token && it.pkg == sender }

    /**
     * MESSAGE_ACK: the token's row, only feeding the app's liveness (§5.6). The connector sends this action **without**
     * a shared identity (`MessagingReceiver.mayAcknowledgeMessage`'s plain `sendBroadcast`), so a missing [sender] is
     * accepted on the token alone (the spec's first way: a token with enough entropy that no other app can guess it);
     * a sender that is present must own the token.
     */
    fun ackDecision(sender: String?, token: String, rows: List<UpRow>): UpRow? =
        rows.firstOrNull { it.token == token && (sender == null || it.pkg == sender) }

    /** The connector's reason for a refusal or failure (the contract's `failed_reason`): every refusal is
     * `ACTION_REQUIRED` (the spec reserves `INTERNAL_ERROR` for "try again directly", which would loop); no network or
     * a server error is `NETWORK` (the connector retries when the network is back). `TEMP_UNAVAILABLE` is never sent. */
    fun failedReason(case: String): String = when (case) {
        "network", "server_error" -> UpSpec.REASON_NETWORK
        else -> UpSpec.REASON_ACTION_REQUIRED
    }

    fun endpointFor(origin: String, secret: String): String = origin.trimEnd('/') + "/up/" + secret

    /** The server's answer is an endpoint of the contract's form ending in our own [secret]. */
    fun endpointValid(endpoint: String, secret: String): Boolean = ENDPOINT.matches(endpoint) && endpoint.endsWith("/up/$secret")

    /**
     * The policy's reconcile (§2.3), from a fresh policy fetched at [requestStartMs]:
     * - UP off (or the key missing, or no origin): every row dropped, `UNREGISTERED` to each app; the server deleted its
     *   rows itself.
     * - a stopped package, or one no longer on the allowlist: its rows dropped, `UNREGISTERED` (a hidden app gets no
     *   broadcast, which is fine); the server pruned them itself.
     * - a confirmed row missing from [regs]: dropped with `UNREGISTERED` **only** if the server confirmed it before the
     *   policy request was sent (the race rule: a registration made during the fetch survives).
     * - a pending row older than [PENDING_MAX_MS]: dropped quietly, its `DELETE` queued (the server may hold it behind a
     *   lost answer).
     * - a `reg` the server holds that no local row knows: its `DELETE` (a pending one is known).
     */
    fun reconcile(rows: List<UpRow>, view: UpView, regs: List<String>, requestStartMs: Long, nowMs: Long): UpReconcile {
        if (!view.on) return UpReconcile(emptyList(), rows.map { UpDrop(it, tellApp = true, deleteOnServer = false) }, emptyList())
        val keep = mutableListOf<UpRow>()
        val drop = mutableListOf<UpDrop>()
        val regSet = regs.toSet()
        for (row in rows) {
            when {
                !view.mayRegister(row.pkg) -> drop += UpDrop(row, tellApp = true, deleteOnServer = false)
                row.confirmed && row.reg !in regSet && (row.confirmedAtMs ?: Long.MAX_VALUE) < requestStartMs ->
                    drop += UpDrop(row, tellApp = true, deleteOnServer = false)
                !row.confirmed && nowMs - row.createdAtMs > PENDING_MAX_MS -> drop += UpDrop(row, tellApp = false, deleteOnServer = true)
                else -> keep += row
            }
        }
        val known = rows.map { it.reg }.toSet()
        return UpReconcile(keep, drop, regs.filter { it !in known })
    }

    /** The rows whose endpoint doesn't carry [origin] (M4): each with its endpoint rewritten, for a `NEW_ENDPOINT`. */
    fun rewrite(rows: List<UpRow>, origin: String): List<UpRow> = rows.filter { it.confirmed }.mapNotNull { row ->
        val wanted = endpointFor(origin, row.secret)
        if (row.endpoint == wanted) null else row.copy(endpoint = wanted)
    }

    /**
     * One `NEW_ENDPOINT` to send for [row] (already updated: stamps set): [kind] `ping` (the first, with a new id), `resend`
     * (the outstanding ping again after a boot, the same id) or `refresh` (§3.3, a new id). A row gets one per pass (L2).
     */
    data class UpSend(val row: UpRow, val id: String, val kind: String)

    /** What one pass decided: the [sends] and the rows [dead] (unanswered; `UNREGISTERED`, removed). */
    data class UpPass(val sends: List<UpSend>, val dead: List<UpRow>)

    /**
     * Design 108b §2, the 30-day rule, for the confirmed rows ([boot] is this boot's count, for Q1's re-send): no ping out
     * and 23 days since the later of the app's last ack and the confirmation: a ping. A ping out and sent in another boot:
     * sent again once, and every day while it is out (the same id; the phone being off, or one lost send or ack, never loses
     * a live app, M1). A ping out for 7 days since the first send
     * **and** an hour since the last send: dead. Stamps that lie more than a day ahead read as now (L3).
     */
    fun liveness(rows: List<UpRow>, boot: Int, nowMs: Long): UpPass {
        val sends = mutableListOf<UpSend>()
        val dead = mutableListOf<UpRow>()
        for (row in rows) {
            if (!row.confirmed) continue
            val pingId = row.pingId
            if (pingId == null) {
                val since = maxOf(stamp(row.lastAppAckMs, nowMs) ?: 0L, stamp(row.confirmedAtMs ?: row.createdAtMs, nowMs) ?: 0L)
                if (nowMs - since < PING_AFTER_MS) continue
                val id = newId()
                sends += UpSend(row.copy(pingId = id, pingSentMs = nowMs, pingLastSendMs = nowMs, pingBoot = boot), id, "ping")
                continue
            }
            val first = stamp(row.pingSentMs, nowMs) ?: nowMs
            var last = stamp(row.pingLastSendMs ?: row.pingSentMs, nowMs) ?: nowMs
            var current = row
            if (row.pingBoot != boot || nowMs - last >= PING_RESEND_MS) {
                last = nowMs
                current = row.copy(pingLastSendMs = nowMs, pingBoot = boot)
                sends += UpSend(current, pingId, "resend")
            }
            if (nowMs - first >= DEAD_AFTER_MS && nowMs - last >= HOUR_MS) {
                dead += row
                sends.removeAll { it.row.reg == row.reg }
            }
        }
        return UpPass(sends, dead)
    }

    /**
     * Design 108b §3.3: the confirmed rows the policy lists in [refresh] whose last refresh is null or a day old, while
     * [inUse] (the phone is interactive and not idle: the app's own discovery needs the network). A row never listed, a
     * pending one and a `reg` the phone doesn't hold are never refreshed.
     */
    fun refreshes(rows: List<UpRow>, refresh: List<UpRefresh>, nowMs: Long, inUse: Boolean): List<UpSend> {
        if (!inUse || refresh.isEmpty()) return emptyList()
        val listed = refresh.map { it.reg }.toSet()
        return rows.filter { it.confirmed && it.reg in listed && refreshDue(it, nowMs) }
            .map { UpSend(it.copy(refreshMs = nowMs), newId(), "refresh") }
    }

    private fun refreshDue(row: UpRow, nowMs: Long): Boolean {
        val last = stamp(row.refreshMs, nowMs) ?: return true
        return nowMs - last >= REFRESH_EVERY_MS
    }

    /** [liveness] and [refreshes] in one pass, one `NEW_ENDPOINT` per row (L2): a refresh due with a ping (or its re-send)
     * rides on it - the ping's id - and counts as done. */
    fun pass(rows: List<UpRow>, refresh: List<UpRefresh>, boot: Int, nowMs: Long, inUse: Boolean): UpPass {
        val live = liveness(rows, boot, nowMs)
        val deadRegs = live.dead.map { it.reg }.toSet()
        val sends = live.sends.toMutableList()
        for (send in refreshes(rows.filter { it.reg !in deadRegs }, refresh, nowMs, inUse)) {
            val at = sends.indexOfFirst { it.row.reg == send.row.reg }
            if (at >= 0) sends[at] = sends[at].copy(row = sends[at].row.copy(refreshMs = nowMs)) else sends += send
        }
        return UpPass(sends, live.dead)
    }

    /** A registration key: 16 random bytes, 32 lowercase hex. */
    fun newReg(): String = hex(16)

    /** A message id for `NEW_ENDPOINT`: 16 random bytes, hex. */
    fun newId(): String = hex(16)

    /** An endpoint secret: 20 random bytes (the spec's recommended 160 bits), base64url without padding: 27 chars. */
    fun newSecret(): String = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(20).also { random.nextBytes(it) })

    private fun hex(bytes: Int): String = ByteArray(bytes).also { random.nextBytes(it) }.joinToString("") { "%02x".format(it) }
}
