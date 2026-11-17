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
) {
    companion object {
        /** Off without the key (an older server, design 108 §9 `older_server`) and without an origin. */
        fun of(policy: PolicyResponse?): UpView {
            val up = policy?.unifiedPush
            val origin = up?.origin?.takeIf { it.isNotBlank() }
            return UpView(up?.on == true && origin != null, origin, policy?.allowlist, up?.stopped.orEmpty())
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
     * a new endpoint if a valid registration exists"). */
    data class Known(val row: UpRow) : RegisterDecision

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
    fun registerDecision(view: UpView, pkg: String, token: String, rows: List<UpRow>, churn: Map<String, List<Long>>, nowMs: Long): RegisterDecision {
        if (!view.on) return RegisterDecision.Refuse("off")
        val same = rows.firstOrNull { it.token == token }
        if (same != null && same.pkg != pkg) return RegisterDecision.Refuse("token_taken")
        if (view.allowlist != null && pkg !in view.allowlist) return RegisterDecision.Refuse("not_allowed")
        if (pkg in view.stopped) return RegisterDecision.Refuse("stopped")
        if (same != null) return if (same.confirmed) RegisterDecision.Known(same) else RegisterDecision.Retry(same)
        if (rows.count { it.pkg == pkg } >= PER_APP || rows.size >= PER_PHONE) return RegisterDecision.Refuse("limit")
        val lastHour = churnWithin(churn, nowMs)
        if (lastHour[pkg].orEmpty().size >= NEW_PER_APP_HOUR || lastHour.values.sumOf { it.size } >= NEW_PER_PHONE_HOUR) {
            return RegisterDecision.Refuse("busy")
        }
        return RegisterDecision.New
    }

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

    /** A registration key: 16 random bytes, 32 lowercase hex. */
    fun newReg(): String = hex(16)

    /** A message id for `NEW_ENDPOINT`: 16 random bytes, hex. */
    fun newId(): String = hex(16)

    /** An endpoint secret: 20 random bytes (the spec's recommended 160 bits), base64url without padding: 27 chars. */
    fun newSecret(): String = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(20).also { random.nextBytes(it) })

    private fun hex(bytes: Int): String = ByteArray(bytes).also { random.nextBytes(it) }.joinToString("") { "%02x".format(it) }
}
