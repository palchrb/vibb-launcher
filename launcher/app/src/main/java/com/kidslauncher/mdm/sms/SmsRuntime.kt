package com.kidslauncher.mdm.sms

import android.app.role.RoleManager
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager
import android.util.Log
import androidx.preference.PreferenceManager
import com.kidslauncher.mdm.calls.CallPolicyState
import com.kidslauncher.mdm.calls.CallPolicyStore
import com.kidslauncher.mdm.calls.CallSystem
import com.kidslauncher.mdm.calls.ChannelMode
import com.kidslauncher.mdm.calls.PhoneNumbers
import com.kidslauncher.mdm.calls.smsMode
import com.kidslauncher.mdm.push.SyncRunner
import com.kidslauncher.mdm.server.MdmApi
import com.kidslauncher.mdm.server.ServerJson
import com.kidslauncher.mdm.server.cachedPolicy
import com.kidslauncher.mdm.server.CachedPolicy
import com.kidslauncher.mdm.server.dto.SmsBlockedCounts
import com.kidslauncher.mdm.server.dto.SmsHeldAnswer
import com.kidslauncher.mdm.server.dto.SmsHeldBody
import com.kidslauncher.mdm.server.dto.SmsHeldRefusal
import com.kidslauncher.mdm.server.dto.SmsHeldRow
import com.kidslauncher.mdm.server.dto.SmsHoldFull
import com.kidslauncher.mdm.server.dto.SmsOwnNumber
import com.kidslauncher.mdm.server.dto.SmsState
import com.kidslauncher.mdm.sms.engine.Carrier
import com.kidslauncher.mdm.sms.engine.HeldPost
import com.kidslauncher.mdm.sms.engine.MmsGate
import com.kidslauncher.mdm.sms.mms.MmsRuntime
import com.kidslauncher.mdm.sms.engine.IncomingSms
import com.kidslauncher.mdm.sms.engine.Received
import com.kidslauncher.mdm.sms.engine.SendCommand
import com.kidslauncher.mdm.sms.engine.SendResult
import com.kidslauncher.mdm.sms.engine.SenderClass
import com.kidslauncher.mdm.sms.engine.SmsEngine
import com.kidslauncher.mdm.sms.engine.SmsJudge
import com.kidslauncher.mdm.sms.store.SqliteMessageStore
import com.kidslauncher.mdm.timerules.TimeRulesStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

private const val LOG_TAG = "SmsRuntime"

/** How long the sync waits for the engine thread's work (it is never slow; this is a guard). */
private const val ENGINE_WAIT_S = 20L

/** Rows per `sms-held` post (the server's limit), and posts per sync. */
private const val POST_ROWS = 50
private const val POSTS_PER_SYNC = 10

/**
 * The message engine on the phone (design 77 §6.1): the engine over the SQLite store and the platform's SmsManager,
 * the rules in memory ([CallPolicyStore]), and its two threads (D7) - [receiveExecutor] for the receivers alone, so a
 * slow engine never delays a receive, and [engineExecutor] for everything else (the outbox, notifications, release,
 * expiry). An exception on either never reaches the main looper (§6.10): calls, screening, Home and enforcement never
 * depend on the SMS code. Nothing runs before the first unlock: the store is CE, and the platform holds messages until
 * then.
 */
object SmsRuntime {
    val receiveExecutor: java.util.concurrent.ExecutorService = Executors.newSingleThreadExecutor { Thread(it, "sms-receive") }
    val engineExecutor: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { Thread(it, "sms-engine") }

    /** The engine thread as a coroutine dispatcher (the screens' event stream, the summary's refresh). */
    private val engineDispatcher = engineExecutor.asCoroutineDispatcher()

    private val main = Handler(Looper.getMainLooper())

    /** The conversation whose thread is in front and resumed (design 77 §6.7): its new messages are read there, so they
     * get no notification. Set and cleared by `ThreadActivity`. */
    @Volatile
    var threadInFront: String? = null

    @Volatile
    private var engine: SmsEngine? = null

    @Volatile
    private var store: SqliteMessageStore? = null

    @Volatile
    private var listening = false

    /** D7: an MMS push committed as it came (step 3b parses it), within QA L4's bounds. On the receive executor. */
    fun keepPush(context: Context, row: com.kidslauncher.mdm.sms.engine.PendingPushRow): Boolean =
        (engine(context) ?: throw IllegalStateException("no store")).keepPush(row)

    /** The last hold's sync request (elapsed realtime), and whether a delayed one is queued ([heldSyncDelay]). */
    private var lastHeldSyncAt: Long? = null
    private var heldSyncQueued = false

    /** The engine, opened at its first use after the first unlock; `null` if the store can't be opened (recorded). */
    @Synchronized
    fun engine(context: Context): SmsEngine? {
        engine?.let { return it }
        if (!CallPolicyStore.userUnlocked(context)) return null
        val app = context.applicationContext
        return try {
            val opened = SqliteMessageStore(app)
            SmsEngine(
                opened, System::currentTimeMillis, { judge(app) }, PlatformRadio(app),
                mmsRadio = com.kidslauncher.mdm.sms.mms.PlatformMmsRadio(app),
                files = com.kidslauncher.mdm.sms.mms.MmsRuntime.files(app),
            ).also {
                store = opened
                engine = it
                listen(app)
                // QA N4: deleted means gone only with secure_delete on - read back, reported when it isn't.
                if (!opened.secureDeleteOn()) recordError(app, "store: secure_delete is off")
            }
        } catch (e: Exception) {
            recordError(app, "store: ${e.javaClass.simpleName}")
            null
        }
    }

    /** Process start (unlocked): the engine judges what it held for the rules, expires, and resumes the outbox. */
    fun start(context: Context) {
        val app = context.applicationContext
        onEngine(app) { engine ->
            engine.judgeHeldForRules().forEach { notifyShown(app, engine, it) }
            engine.expire()
            engine.sweepUncertain()
            engine.drainOutbox()
            // A release the last process didn't get to (it died between the policy and the release): the cached
            // policy's drops first, as at every accepted policy.
            val drops = (cachedPolicy() as? CachedPolicy.Ok)?.policy?.callPolicy?.smsHeldDrop.orEmpty()
            engine.releasePass(drops).forEach { notifyShown(app, engine, it) }
            // Step 3b: what MMS work the last process left (pushes, downloads, parses), and D14's data listener.
            engine.mms.retryWaiting(dataOn = false)
            MmsRuntime.listenForData(app)
            MmsRuntime.kick(app)
        }
    }

    private fun listen(app: Context) {
        if (listening) return
        listening = true
        // D15 and the conversations' canSend/name at every rule change (the release waits for an accepted policy:
        // the parent's deletions come first, policyAccepted). Home's tile and the screens hear of it after (SmsHome).
        CallPolicyStore.addListener {
            onEngine(app) { engine ->
                engine.judgeHeldForRules().forEach { notifyShown(app, engine, it) }
                engine.rulesChanged()
                // A push the rules couldn't judge (D15) waits in `pending_push`: judged now. An MMS that failed for an
                // unknown MCC/MNC is fetched once the policy's MMSC list knows the SIM (code QA L10).
                val retried = engine.mms.retryWaiting(dataOn = false)
                if (retried || engine.mms.pendingPushes().isNotEmpty()) MmsRuntime.kick(app)
            }
            SmsHome.refresh(app)
        }
        // Every event moves Home's badges (a message in, a read mark, a delete) - read on the engine thread. Collected
        // off it (code QA of step 2, L1): a collector on the emitting thread can't drain while that thread runs a burst.
        val opened = engine ?: return
        CoroutineScope(SupervisorJob() + kotlinx.coroutines.Dispatchers.Default).launch {
            opened.live.collect { SmsHome.refresh(app) }
        }
        SmsHome.refresh(app)
    }

    /** What the engine judges with now: the whole policy from memory, never decoded per message. */
    fun judge(context: Context): SmsJudge = SmsJudge(
        state = withDebugMmsc(CallPolicyStore.state),
        roleHeld = roleHeld(context),
        ready = true,
        timeBlocked = { TimeRulesStore.callsBlockedNow() },
        callbackWindowOpen = { CallSystem.callbackWindowUntil(context) != null },
        platformEmergency = CallSystem.platformEmergency(context),
        carrier = { subId -> carrier(context, subId) },
        simNumbers = {
            ownNumbers(context, (CallPolicyStore.state as? CallPolicyState.Managed)?.rules?.defaultCc ?: "47").map { it.slot to it.number }
        },
    )

    /** The debug build's fake MMS server for the emulator ([MmsDebug]; the release build's list is always empty). */
    private fun withDebugMmsc(state: CallPolicyState): CallPolicyState {
        val extra = MmsDebug.mmscRules()
        if (extra.isEmpty() || state !is CallPolicyState.Managed) return state
        return state.copy(rules = state.rules.copy(mmscHosts = state.rules.mmscHosts + extra))
    }

    /** Step 3b: a subscription's SIM operator (MCC/MNC, D10's table key) and the carrier's MMS maximum (D10 check 3);
     * unknown on any failure - an unknown operator fails closed. */
    private fun carrier(context: Context, subId: Int?): Carrier = try {
        val id = subId?.takeIf { it >= 0 } ?: SubscriptionManager.getDefaultSmsSubscriptionId()
        val base = context.getSystemService(TelephonyManager::class.java)
        val telephony = if (id >= 0) base.createForSubscriptionId(id) else base
        val sms = context.getSystemService(android.telephony.SmsManager::class.java).let { if (id >= 0) it.createForSubscriptionId(id) else it }
        val max = runCatching { sms.carrierConfigValues.getInt(android.telephony.SmsManager.MMS_CONFIG_MAX_MESSAGE_SIZE, 0) }.getOrDefault(0)
        Carrier(telephony.simOperator?.takeIf { it.isNotEmpty() }, max)
    } catch (e: Exception) {
        Carrier(null, 0)
    }

    fun roleHeld(context: Context): Boolean = CallSystem.roleHeld(context, RoleManager.ROLE_SMS)

    // ---- Receive (D7) ----

    /** On the receive executor: judge and commit, then [done] (the receiver's `finish()`), then the rest on the
     * engine thread. A throw is recorded and counted; the receiver still finishes. [startedAt] is the broadcast's
     * arrival (elapsed realtime): QA L7 measures D7's budget from it. */
    fun receive(context: Context, sms: IncomingSms, startedAt: Long, done: () -> Unit) {
        val app = context.applicationContext
        receiveExecutor.execute {
            var received: Received? = null
            try {
                // The rules must be in memory before judging: a cold start by the broadcast loads them first.
                CallPolicyStore.ensureLoaded(app)
                received = engine(app)?.receiveSms(sms)
                if (received == null) Log.e(LOG_TAG, "No engine for an incoming SMS - it is lost")
            } catch (t: Throwable) {
                recordError(app, "receive: ${t.javaClass.simpleName}")
            } finally {
                done()
            }
            val tookMs = SystemClock.elapsedRealtime() - startedAt
            val result = received ?: return@execute
            onEngine(app) { engine ->
                if (overReceiveBudget(tookMs)) {
                    // Only the time - never a sender or a text.
                    recordError(app, "receive took $tookMs ms (budget $RECEIVE_BUDGET_MS)")
                    engine.countSlowReceive()
                }
                afterReceive(app, engine, result)
            }
        }
    }

    private fun afterReceive(app: Context, engine: SmsEngine, received: Received) {
        when (received) {
            is Received.Shown -> notifyShown(app, engine, received)
            is Received.HeldForParent, is Received.Blocked -> requestHeldSync(app)
            Received.HeldForRules, Received.Duplicate -> Unit
        }
    }

    private fun notifyShown(app: Context, engine: SmsEngine, shown: Received.Shown) {
        // An MMS that turned out to be a group left the sender's conversation: its notification goes too (code QA L8).
        shown.movedFrom?.let { SmsNotifier.cancel(app, it) }
        // The kid is reading this thread: it marks the message read itself, no notification (§6.7).
        if (shown.conversation.id == threadInFront) return
        // A picture that waited and has now arrived is told again, quietly: the kid heard of it once (code QA L8).
        SmsNotifier.notify(app, engine, shown.conversation, shown.message, silent = TimeRulesStore.callsBlockedNow() || shown.again)
    }

    /** Step 3b: an MMS shown or fetched (from the MMS thread) - notified on the engine thread. */
    fun notifyShown(context: Context, shown: Received.Shown) {
        val app = context.applicationContext
        onEngine(app) { notifyShown(app, it, shown) }
    }

    /** D16 for an MMS held or blocked on the MMS thread: the same coalesced sync request as an SMS's. */
    fun requestHeldSyncSoon(context: Context) {
        val app = context.applicationContext
        onEngine(app) { requestHeldSync(app) }
    }

    /** [work] on the engine thread, waited for (never from the main thread or the engine thread itself); `null` without
     * an engine or after a throw (recorded). The MMS thread's way to the engine. */
    fun <T> call(context: Context, work: (SmsEngine) -> T): T? = awaitResult(context.applicationContext, work)

    /** D16: a hold asks for a sync so the parent hears at once - coalesced to one a minute (QA R2-M4). Engine thread. */
    private fun requestHeldSync(app: Context) {
        val now = SystemClock.elapsedRealtime()
        val delay = heldSyncDelay(lastHeldSyncAt, now)
        if (delay == 0L) {
            lastHeldSyncAt = now
            SyncRunner.request(app, SYNC_REASON_HELD)
        } else if (!heldSyncQueued) {
            heldSyncQueued = true
            engineExecutor.schedule({
                heldSyncQueued = false
                lastHeldSyncAt = SystemClock.elapsedRealtime()
                runCatching { SyncRunner.request(app, SYNC_REASON_HELD) }
            }, delay, TimeUnit.MILLISECONDS)
        }
    }

    // ---- Send ----

    /** Every front end's send (the notification's reply, respond-via-message, the kid's screens): the gate is in the
     * engine; the outbox runs on the engine thread. */
    fun send(context: Context, command: SendCommand): SendResult? {
        val app = context.applicationContext
        val engine = engine(app) ?: return null
        val result = engine.send(command)
        if (result is SendResult.Accepted) onEngine(app) { it.drainOutbox() }
        return result
    }

    fun onSent(context: Context, messageId: String, part: Int, total: Int, ok: Boolean, code: Int) =
        onEngine(context.applicationContext) { it.onSent(messageId, part, total, ok, code) }

    fun onDelivered(context: Context, messageId: String, ok: Boolean) =
        onEngine(context.applicationContext) { it.onDelivered(messageId, ok) }

    // ---- The sync ----

    /** At every accepted policy (D16): drops first, expiry, then release; waits for the engine thread. Never throws. */
    fun policyAccepted(context: Context, drops: List<String>) {
        val app = context.applicationContext
        await(app) { engine -> engine.releasePass(drops).forEach { notifyShown(app, engine, it) } }
        // A released MMS push (D16) is judged again and fetched in the next MMS pass.
        MmsRuntime.kick(app)
    }

    /** At every sync, reached or not: expiry, the uncertain sweep, the outbox, housekeeping. Never throws. */
    fun syncTick(context: Context) {
        val app = context.applicationContext
        await(app) { engine ->
            engine.expire()
            engine.sweepUncertain()
            engine.drainOutbox()
            engine.prune()
            // D14: each sync retries what waits for MMS data.
            engine.mms.retryWaiting(dataOn = false)
        }
        // §6.7: a shared picture's copy lives an hour; a download or send a process death left behind, a day.
        runCatching { com.kidslauncher.mdm.ui.messages.PictureShare.prune(app) }
        runCatching { com.kidslauncher.mdm.sms.mms.MmsFileProvider.prune(app) }
        MmsRuntime.kick(app)
    }

    /**
     * Posts the rows the server hasn't seen (§7): at most [POST_ROWS] a post. A 2xx marks them reported (also when the
     * server answers `capped`: it will never store them); a 400 names the row it refused - that row is given up
     * (marked) so one bad row never stops the rest. Anything else is tried again at the next sync. Never throws.
     */
    suspend fun postHeld(context: Context, api: MdmApi) {
        val app = context.applicationContext
        try {
            repeat(POSTS_PER_SYNC) {
                val rows = awaitResult(app) { it.unreported(POST_ROWS) }.orEmpty()
                if (rows.isEmpty()) return
                val response = api.sendSmsHeld(SmsHeldBody(rows.map(::rowOf)))
                val text = response.body()?.use { it.string() } ?: response.errorBody()?.use { it.string() }
                when {
                    response.isSuccessful -> {
                        val answer = text?.let { runCatching { ServerJson.decodeFromString(SmsHeldAnswer.serializer(), it) }.getOrNull() }
                        if (answer?.capped == true) Log.w(LOG_TAG, "The server holds no more rows of this phone (capped)")
                        await(app) { it.markReported(rows) }
                    }
                    response.code() == 400 -> {
                        val refusal = text?.let { runCatching { ServerJson.decodeFromString(SmsHeldRefusal.serializer(), it) }.getOrNull() }
                        val bad = refusal?.refused?.let(rows::getOrNull)
                        Log.w(LOG_TAG, "The server refused a held row: ${refusal?.reason}")
                        if (bad == null) return
                        await(app) { it.markReported(listOf(bad)) }
                    }
                    else -> return
                }
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(LOG_TAG, "Posting the held senders failed: ${e.javaClass.simpleName}")
        }
    }

    private fun rowOf(post: HeldPost) =
        SmsHeldRow(post.id, post.at, post.kind, post.senderClass, post.sender, post.expiresAt, post.state)

    /** `sms_state` (§7); `null` before the first unlock. */
    fun report(context: Context): SmsState? {
        val app = context.applicationContext
        if (!CallPolicyStore.userUnlocked(app)) return null
        val counts = awaitResult(app) { it.counts() }
        val counters = counts?.counters.orEmpty()
        fun count(key: String) = counters[key] ?: 0L
        fun blocked(key: String) = count("${SmsEngine.COUNT_BLOCKED}.$key")
        val state = CallPolicyStore.state
        return SmsState(
            roleHeld = roleHeld(app),
            modeApplied = state.smsMode?.wire ?: "unknown",
            received = count(SmsEngine.COUNT_RECEIVED),
            sent = count(SmsEngine.COUNT_SENT),
            blocked = SmsBlockedCounts(
                number = blocked(SenderClass.NUMBER.wire),
                short = blocked(SenderClass.SHORT.wire),
                alphanumeric = blocked(SenderClass.ALPHANUMERIC.wire),
                malformed = blocked(SenderClass.MALFORMED.wire),
                empty = blocked(SenderClass.EMPTY.wire),
                group = blocked(SenderClass.GROUP.wire),
                badLocation = blocked(SenderClass.BAD_LOCATION.wire),
                overCap = blocked(SmsEngine.REASON_OVER_CAP),
            ),
            held = count(SmsEngine.COUNT_HELD),
            released = count(SmsEngine.COUNT_RELEASED),
            expired = count(SmsEngine.COUNT_EXPIRED),
            parseFailures = count(SmsEngine.COUNT_PARSE_FAILURES),
            mmsWaiting = counts?.mmsWaiting?.toLong() ?: 0,
            mmsDataAllowed = mmsDataAllowed(app),
            mmscKnown = mmscKnown(app, state),
            lastError = SmsPrefs.lastError(app),
            ownNumbers = ownNumbers(app, (state as? CallPolicyState.Managed)?.rules?.defaultCc ?: "47"),
            holdFull = SmsHoldFull(counts?.numbersFull == true, counts?.namesFull == true),
        )
    }

    /**
     * D14 (code QA L7): whether MMS can be fetched now. Mobile data on, or - with it off - the last fetch that ran with
     * data off worked (the per-SIM "MMS-meldinger" setting, or a carrier that allows MMS data always: neither can be read
     * by an app, `isDataEnabledForApn(TYPE_MMS)` and the mobile-data policies being system APIs, so the phone learns it
     * from what the platform did). A fetch that failed for want of data with data off says `false`.
     */
    private fun mmsDataAllowed(context: Context): Boolean =
        MmsRuntime.mobileDataOn(context) || SmsPrefs.mmsWithoutData(context) == true

    /** D10: whether the SMS SIM's MCC/MNC is on the policy's MMSC list (the rules in memory - the same table the
     * download gate reads). */
    private fun mmscKnown(context: Context, state: CallPolicyState): Boolean =
        state is CallPolicyState.Managed && MmsGate.known(carrier(context, null).operator, state.rules.mmscHosts)

    /** D12: each active SIM's own number as it knows it (normalised; `null` when it doesn't). */
    private fun ownNumbers(context: Context, defaultCc: String): List<SmsOwnNumber> = try {
        val subscriptions = context.getSystemService(SubscriptionManager::class.java)
        subscriptions?.activeSubscriptionInfoList.orEmpty().map { info ->
            val raw = runCatching { subscriptions?.getPhoneNumber(info.subscriptionId) }.getOrNull()
            SmsOwnNumber(info.simSlotIndex + 1, raw?.takeIf { it.isNotBlank() }?.let { PhoneNumbers.normalize(it, defaultCc)?.value })
        }.sortedBy { it.slot }
    } catch (e: SecurityException) {
        emptyList()
    }

    // ---- The kid's screens (design 77 §6.7) ----

    /** [work] on the engine thread, then [then] on the main thread with its result - `null` without an engine (before
     * the first unlock, a store that can't be opened) or after a throw (recorded). */
    fun <T> ask(context: Context, work: (SmsEngine) -> T, then: (T?) -> Unit) {
        val app = context.applicationContext
        engineExecutor.execute {
            val result = try {
                engine(app)?.let(work)
            } catch (t: Throwable) {
                recordError(app, "screen: ${t.javaClass.simpleName}")
                null
            }
            main.post { then(result) }
        }
    }

    /** The engine's live events for a screen (the engine opened on its own thread); empty without an engine. */
    fun events(context: Context): Flow<com.kidslauncher.mdm.sms.engine.EngineEvent> {
        val app = context.applicationContext
        return flow {
            val opened = withContext(engineDispatcher) { engine(app) } ?: return@flow
            emitAll(opened.live)
        }
    }

    // ---- Threads ----

    /** Runs [work] on the engine thread with the engine; a throw is recorded, never rethrown. */
    fun onEngine(app: Context, work: (SmsEngine) -> Unit) {
        engineExecutor.execute {
            try {
                engine(app)?.let(work)
            } catch (t: Throwable) {
                recordError(app, "engine: ${t.javaClass.simpleName}")
            }
        }
    }

    private fun await(app: Context, work: (SmsEngine) -> Unit) {
        awaitResult(app) { work(it) }
    }

    private fun <T> awaitResult(app: Context, work: (SmsEngine) -> T): T? = try {
        engineExecutor.submit(Callable { engine(app)?.let(work) }).get(ENGINE_WAIT_S, TimeUnit.SECONDS)
    } catch (e: Exception) {
        recordError(app, "engine: ${(e.cause ?: e).javaClass.simpleName}")
        null
    }

    /** `sms_state.last_error` (300 characters) and the log - never message content. */
    fun recordError(context: Context, error: String) {
        Log.e(LOG_TAG, "SMS engine: $error")
        runCatching { SmsPrefs.lastError(context, error.take(300)) }
    }

    const val SYNC_REASON_HELD = "sms_held"
}

/** The SMS role's prefs (CE): whether we took it, whom from, the last error. */
object SmsPrefs {
    private const val TAKEN_BY_US = "mdm.sms.role_taken_by_us"
    private const val PREVIOUS_HOLDER = "mdm.sms.role_previous"
    private const val LAST_ERROR = "mdm.sms.last_error"
    private const val ROLE_SIGNALLED = "mdm.sms.role_signalled"
    private const val DENIED_PERMISSIONS = "mdm.sms.denied_permissions"

    /** What a preferences reset keeps (`resetPreferences`): without them the role is never handed back (QA L3) and a
     * grant we took away never given back (L8). */
    val KEPT_ON_RESET = listOf(TAKEN_BY_US, PREVIOUS_HOLDER, DENIED_PERMISSIONS)

    private fun prefs(context: Context) = PreferenceManager.getDefaultSharedPreferences(context)

    /** QA L8: the other apps' SMS permissions we denied that were granted before ("package|permission"). */
    fun deniedPermissions(context: Context): Set<String> = prefs(context).getStringSet(DENIED_PERMISSIONS, null).orEmpty().toSet()

    fun deniedPermissions(context: Context, value: Set<String>) {
        if (value != deniedPermissions(context)) prefs(context).edit().putStringSet(DENIED_PERMISSIONS, value).commit()
    }

    fun takenByUs(context: Context) = prefs(context).getBoolean(TAKEN_BY_US, false)

    fun takenByUs(context: Context, value: Boolean) {
        prefs(context).edit().putBoolean(TAKEN_BY_US, value).commit()
    }

    fun previousHolder(context: Context): String? = prefs(context).getString(PREVIOUS_HOLDER, null)

    fun previousHolder(context: Context, value: String?) {
        prefs(context).edit().putString(PREVIOUS_HOLDER, value).commit()
    }

    private const val MMS_WITHOUT_DATA = "mdm.sms.mms_without_data"

    /** D14, code QA L7: whether an MMS fetch worked with mobile data off (`null`: none has run with data off yet). */
    fun mmsWithoutData(context: Context): Boolean? =
        if (prefs(context).contains(MMS_WITHOUT_DATA)) prefs(context).getBoolean(MMS_WITHOUT_DATA, false) else null

    /** A fetch that worked ([withoutData]: with mobile data off), or one that failed for want of data with it off. */
    fun mmsFetched(context: Context, withoutData: Boolean) {
        if (withoutData || mmsWithoutData(context) != null) prefs(context).edit().putBoolean(MMS_WITHOUT_DATA, withoutData).apply()
    }

    fun lastError(context: Context): String? = prefs(context).getString(LAST_ERROR, null)

    fun lastError(context: Context, value: String?) {
        if (value != lastError(context)) prefs(context).edit().putString(LAST_ERROR, value).apply()
    }

    /** The role as the server was last asked to hear about it (a change asks for a sync, as the call roles). */
    fun roleSignalled(context: Context): Boolean? = if (prefs(context).contains(ROLE_SIGNALLED)) prefs(context).getBoolean(ROLE_SIGNALLED, false) else null

    fun roleSignalled(context: Context, value: Boolean) {
        prefs(context).edit().putBoolean(ROLE_SIGNALLED, value).apply()
    }
}

/** The platform's sending (§6.5): `divideMessage` + `sendMultipartTextMessage` on the conversation's SIM, else the
 * default SMS subscription; one explicit sent intent per part and a delivery intent on the last ([SmsSentReceiver]).
 * Multipart send after Fossify Messages' `SmsSender.kt` (GPL-3.0), made distinct per message and part here. */
private class PlatformRadio(private val app: Context) : com.kidslauncher.mdm.sms.engine.SmsRadio {
    override fun send(messageId: String, address: String, body: String, subId: Int?, deliveryReport: Boolean): Int {
        val manager = app.getSystemService(android.telephony.SmsManager::class.java).let { base ->
            val id = subId?.takeIf { it >= 0 } ?: SubscriptionManager.getDefaultSmsSubscriptionId()
            if (id >= 0) base.createForSubscriptionId(id) else base
        }
        val parts = manager.divideMessage(body)
        val sent = ArrayList(parts.indices.map { SmsSentReceiver.sentIntent(app, messageId, it, parts.size) })
        val delivered = if (deliveryReport) {
            ArrayList(parts.indices.map { if (it == parts.lastIndex) SmsSentReceiver.deliveredIntent(app, messageId) else null })
        } else {
            null
        }
        manager.sendMultipartTextMessage(address, null, parts, sent, delivered)
        return parts.size
    }
}
