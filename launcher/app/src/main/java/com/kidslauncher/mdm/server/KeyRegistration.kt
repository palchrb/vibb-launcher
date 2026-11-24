package com.kidslauncher.mdm.server

import android.content.Context
import android.os.SystemClock
import android.util.Log
import com.kidslauncher.mdm.preferences.LauncherPreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.Base64

/**
 * Design 129 §5.2: the one registration flow. No other code makes, adopts or deletes a key (the enrolment's own key
 * aside, §5.1). One run at a time in the process (a request while one runs makes it go round once more); step 1,
 * `GET /api/devices/key`, decides through [keyStep], and whatever a registration's POST did - an answer, an error, a
 * timeout - the run goes back to step 1, so a POST the server committed after the phone gave up is found and adopted.
 *
 * The launcher has no WorkManager (removed for the backstop alarm, `BackstopAlarm`): the run is a coroutine of its own,
 * asked for by the sync when the policy's `device_key` nudges ([nudge]), by a fresh enrolment and by another call's
 * `unknown_key` ([wake]); its backoff (a failed step, an empty attestation-key pool - `keys_unavailable` -, a refusal)
 * is kept in memory on elapsed realtime and only ever delays the next run.
 */
object KeyRegistration {
    private const val LOG_TAG = "KeyRegistration"

    /** A step 1 that keeps registering is still one run: at most this many rounds. */
    private const val ROUNDS = 3
    private const val FIRST_BACKOFF_MS = 60_000L
    private const val MAX_BACKOFF_MS = 6 * 60 * 60_000L

    /** A refused chain (a 400 word): the server's checks won't change by themselves soon. */
    private const val REFUSED_BACKOFF_MS = 24 * 60 * 60_000L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var running = false
    private var again = false

    @Volatile
    private var notBeforeElapsed = 0L

    @Volatile
    private var failures = 0

    /** Another call answered `unknown_key` (§4.4): only this flow acts on it. */
    fun wake() {
        DeviceCredentials.appContext()?.let { request(it, "unknown_key") }
    }

    @Volatile
    private var lastAskElapsed: Long? = null

    /** The sync's accepted policy: a run when its `device_key` asks for step 1 ([keyNudge]; a state that names no key
     * asks again only after an hour, code QA L9). */
    fun nudge(context: Context, policy: DeviceKeyPolicy?) {
        val book = KeyBook.read(context)
        val now = SystemClock.elapsedRealtime()
        if (keyNudge(policy, book.adopted?.keyId, DeviceCredentials.tokenStored(), book.lost, backoff(), lastAskElapsed?.let { now - it })) {
            lastAskElapsed = now
            request(context, "policy")
        }
    }

    /** Code QA H1: a lost key still asks step 1 now and then (the backoff paces it); a known key clears the mark. */
    fun askWhileLost(context: Context) {
        if (!backoff()) request(context, "lost")
    }

    fun backoff(): Boolean = SystemClock.elapsedRealtime() < notBeforeElapsed

    fun request(context: Context, reason: String) {
        val app = context.applicationContext ?: context
        synchronized(this) {
            if (running) {
                again = true
                return
            }
            running = true
            again = false
        }
        scope.launch {
            try {
                while (true) {
                    try {
                        run(app)
                    } catch (e: Exception) {
                        Log.w(LOG_TAG, "The registration run failed ($reason): ${e.javaClass.simpleName}")
                        failed()
                    }
                    synchronized(this@KeyRegistration) {
                        if (!again) {
                            running = false
                            return@launch
                        }
                        again = false
                    }
                }
            } finally {
                synchronized(this@KeyRegistration) { running = false }
            }
        }
    }

    private fun succeeded() {
        failures = 0
        notBeforeElapsed = 0L
    }

    private fun failed(minMs: Long = 0L) {
        failures++
        val wait = (FIRST_BACKOFF_MS shl (failures - 1).coerceAtMost(10)).coerceAtMost(MAX_BACKOFF_MS)
        notBeforeElapsed = SystemClock.elapsedRealtime() + maxOf(wait, minMs)
    }

    private suspend fun stepOne(api: MdmApi): KeyStepOne = try {
        val response = api.getKey()
        val text = (if (response.isSuccessful) response.body() else response.errorBody())?.use { it.string() }
        val answer = if (response.code() == 200 && text != null) {
            ServerJson.decodeFromString(DeviceKeyAnswer.serializer(), text)
        } else {
            null
        }
        KeyStepOne(response.code(), response.headers()[AUTH_REFUSAL_HEADER], answer)
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.w(LOG_TAG, "Step 1 had no answer: ${e.javaClass.simpleName}")
        KeyStepOne(0)
    }

    private suspend fun run(app: Context) {
        // One registration per run: a POST without an answer is looked for by step 1, never followed by another key at
        // once (the next run, after the backoff, may make one).
        var registered = false
        repeat(ROUNDS) {
            val url = LauncherPreferences.mdm().serverUrl()
            val credential = DeviceCredentials.forRegistration(app) ?: return
            if (url.isNullOrBlank()) return
            // This flow's own calls never wake it again.
            val api = createMdmApi(url, credential, onUnknownKey = {})
            val hw = DeviceKeys.hardware(app)
            val local = DeviceKeys.localSpki(app, hw)
            val book = KeyBook.read(app)
            val step1 = stepOne(api)
            val action = keyStep(
                KeyStepInput(
                    hasToken = credential.bearer != null,
                    local = local.values.toList(),
                    adopted = book.adopted?.alias?.let(local::get),
                    step1 = step1,
                    conflictReported = book.conflictReported,
                    backoff = backoff(),
                ),
            )
            when (action) {
                is KeyAction.Adopt -> {
                    val alias = local.entries.firstOrNull { it.value == action.spki }?.key
                    val answer = step1.answer
                    val keyId = answer?.keyId
                    if (alias == null || answer == null || keyId.isNullOrBlank()) {
                        failed()
                        return
                    }
                    // Code QA L3: the bearer goes only after the key has signed once.
                    if (action.dropToken && !signs(hw, alias)) {
                        Log.w(LOG_TAG, "Bound, but the key doesn't sign - the bearer stays")
                        failed()
                        return
                    }
                    DeviceKeys.adopt(app, alias, keyId, answer.state)
                    if (action.dropToken) DeviceCredentials.dropTokenForBoundKey()
                    Log.i(LOG_TAG, "Key adopted (${answer.state})")
                    // Code QA L6: the shutdown's prepared client carries the credential: built again.
                    PowerEvents.prepare(app)
                    succeeded()
                    return
                }
                KeyAction.Keep -> {
                    val state = step1.answer?.state
                    if ((state != null && book.adopted != null && book.adopted.state != state) || book.lost || book.suspicion != null) {
                        // The key is known: any suspicion of a lost key ends (code QA H1).
                        KeyBook.update(app) { it.copy(adopted = it.adopted?.let { a -> a.copy(state = state ?: a.state) }, lost = false, suspicion = null) }
                    }
                    succeeded()
                    return
                }
                KeyAction.ReportConflict -> {
                    val named = step1.answer?.spkiSha256
                    val reported = try {
                        api.postKeyConflict().let {
                            it.body()?.close()
                            it.errorBody()?.close()
                            it.code() in 200..299 || it.code() == 409
                        }
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        false
                    }
                    Log.w(LOG_TAG, "The server names a key this phone doesn't hold - reported: $reported")
                    if (reported) KeyBook.update(app) { it.copy(conflictReported = named) } else failed()
                    return
                }
                KeyAction.SuspectLost -> {
                    // Code QA H1: never "lost" on one answer (a server reloading its key index answers it too).
                    val (suspicion, lost) = lostVerdict(book.suspicion, SystemClock.elapsedRealtime(), BootClock.bootCount())
                    if (lost && !book.lost) Log.w(LOG_TAG, "The server no longer knows this phone's key - a new setup code")
                    KeyBook.update(app) { it.copy(suspicion = suspicion, lost = it.lost || lost) }
                    failed()
                    return
                }
                KeyAction.Wait -> {
                    if (step1.status != 200) failed()
                    return
                }
                KeyAction.Register -> {
                    if (registered) {
                        failed()
                        return
                    }
                    registered = true
                    if (!register(app, api)) return
                }
            }
        }
        // Still undecided after every round: later.
        failed()
    }

    private fun signs(hw: KeyHardware, alias: String): Boolean = try {
        hw.sign(alias, "vibb-probe".toByteArray(Charsets.UTF_8))
        true
    } catch (e: Exception) {
        false
    }

    /** §5.2 step 4: a challenge, a new key, the POST. `true`: back to step 1. */
    private suspend fun register(app: Context, api: MdmApi): Boolean {
        val challengeAnswer = try {
            val response = api.getKeyChallenge()
            val text = response.body()?.use { it.string() }
            if (!response.isSuccessful || text == null) null else ServerJson.decodeFromString(KeyChallengeAnswer.serializer(), text)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
        if (challengeAnswer == null) {
            failed()
            return false
        }
        val challenge = try {
            Base64.getUrlDecoder().decode(challengeAnswer.challenge)
        } catch (e: IllegalArgumentException) {
            failed()
            return false
        }
        val now = TrustedClock.nowMs()
        val made = try {
            DeviceKeys.make(app, challenge, now + challengeAnswer.expiresInS * 1000, now)
        } catch (e: KeyGenFailure) {
            // Design 129 M7: an empty pool is a retry with its own reason, never a downgrade.
            val reason = if (e.keysUnavailable) "keys_unavailable" else "keystore"
            Log.w(LOG_TAG, "No key made: $reason")
            DeviceKeys.refused(app, reason)
            failed()
            return false
        }
        if (made == null) {
            failed()
            return false
        }
        val response = try {
            val proof = DeviceKeys.proof(app, made.alias, challenge, ProofKind.REGISTER)
            api.postKey(KeyRegisterBody(challengeAnswer.challenge, made.chainB64(), proof))
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            // Whatever the POST's fate: back to step 1, the key kept (the server may still name it).
            Log.w(LOG_TAG, "The registration's POST had no answer: ${e.javaClass.simpleName}")
            return true
        }
        val text = (if (response.isSuccessful) response.body() else response.errorBody())?.use { it.string() }
        val refusal = text?.let {
            runCatching { ServerJson.decodeFromString(KeyRefusalAnswer.serializer(), it) }.getOrNull()
        }
        return when (response.code()) {
            200, 409 -> true
            400 -> {
                // Refused before anything was stored: this key is never named.
                val reason = refusal?.reason?.ifBlank { null } ?: "invalid"
                Log.w(LOG_TAG, "The registration was refused: $reason")
                DeviceKeys.forget(app, alias = made.alias)
                DeviceKeys.refused(app, reason)
                failed(if (reason == "challenge") 0L else REFUSED_BACKOFF_MS)
                false
            }
            503 -> {
                DeviceKeys.forget(app, alias = made.alias)
                DeviceKeys.refused(app, refusal?.reason ?: "unavailable")
                failed((refusal?.retryAfterS ?: 0L) * 1000)
                false
            }
            else -> true
        }
    }
}
