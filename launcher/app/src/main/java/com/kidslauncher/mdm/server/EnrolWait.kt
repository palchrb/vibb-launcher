package com.kidslauncher.mdm.server

import android.content.Context
import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext

/**
 * Design 129b D11: the one waiting enrolment of the process. The enrolment's caller ("Koble til", the QR's apply and
 * its retry, Settings' "Enroll now" and scanner, "Ny oppsettkode") got [EnrollWaiting]: this keeps trying the same code
 * with the same attempt (the key book's, [DeviceKeys.enrolAttempt]) at the server's pace through [enrolWaitStep] - each
 * try under [QrExtras]' mutex and never cancelled once sent - until it enrols (with a key, or exempt), the code is
 * refused, or 45 minutes since the first waiting answer. A transient answer keeps the schedule. Each try makes a key when
 * the phone has none ([Enrollment.enroll]). [state] is what the screens show: "Venter på en forelder." and the number.
 * A wait that ends without an enrolment (stopped, run out, or replaced by another code's) forgets that code's enrolment
 * key, so the three-key cap isn't eaten by an abandoned code (code QA L4).
 */
object EnrolWait {
    private const val LOG_TAG = "EnrolWait"

    /** What the enrolment screens show. */
    sealed interface State {
        /** "Venter på en forelder." with "Kode: [number]". */
        data class Waiting(val number: String) : State

        /** The 45-minute stop or a refused code: "Koden er utløpt. Be om en ny." (L7). */
        data object RanOut : State

        /** Enrolled after waiting: the dialogs close as after any enrolment. */
        data object Enrolled : State
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val current = MutableStateFlow<State?>(null)
    val state: StateFlow<State?> = current

    private var job: Job? = null
    private var generation = 0L
    private var codeHash: String? = null

    /** Whether a waiting enrolment runs. */
    @Synchronized
    fun running(): Boolean = job?.isActive == true

    /** Whether the running wait is for [code] (code QA M2: Settings and "Ny oppsettkode" send nothing again then). */
    @Synchronized
    fun waitingFor(code: String): Boolean = job?.isActive == true && codeHash == qrCodeHash(code)

    /** The screens' lines for [state] (`null` when nothing waits). */
    fun lines(context: Context): String? = when (val s = current.value) {
        is State.Waiting -> context.getString(com.kidslauncher.mdm.R.string.enrol_key_wait) + "\n" +
            context.getString(com.kidslauncher.mdm.R.string.enrol_key_number, shownNumber(s.number))
        State.RanOut -> context.getString(com.kidslauncher.mdm.R.string.enrol_code_ran_out)
        State.Enrolled, null -> null
    }

    /** A screen showed [State.RanOut] or [State.Enrolled]: the fields are back. */
    fun clearEnded() {
        current.compareAndSet(State.RanOut, null)
        current.compareAndSet(State.Enrolled, null)
    }

    /**
     * Starts (or restarts, for another code) the waiting after [first] for [code]. [tryOnce] is one enrolment try with
     * the same code; [onEnd] hears the last result (success, or the code refused or run out) - the QR's marker.
     */
    fun start(
        context: Context,
        code: String,
        first: EnrollWaiting,
        tryOnce: suspend () -> Result<Unit>,
        onEnd: suspend (Result<Unit>) -> Unit = {},
    ) {
        val app = context.applicationContext
        synchronized(this) {
            job?.cancel()
            val mine = ++generation
            codeHash = qrCodeHash(code)
            current.value = State.Waiting(first.number)
            job = scope.launch { run(app, mine, code, first, tryOnce, onEnd) }
        }
    }

    /** Only the current wait writes the state (code QA L1: a replaced one's late answer changes nothing). */
    private fun publish(mine: Long, value: State?): Boolean = synchronized(this) {
        if (mine != generation) return false
        current.value = value
        true
    }

    private suspend fun run(
        app: Context,
        mine: Long,
        code: String,
        first: EnrollWaiting,
        tryOnce: suspend () -> Result<Unit>,
        onEnd: suspend (Result<Unit>) -> Unit,
    ) {
        val firstAt = SystemClock.elapsedRealtime()
        var answer = "waiting"
        var pace: Long? = first.retryAfterS
        var last: Result<Unit> = Result.failure(first)
        try {
            while (true) {
                val step = enrolWaitStep(answer, pace, SystemClock.elapsedRealtime() - firstAt, hasKey = true)
                if (step.action != "retry") break
                delay(step.delayMs ?: (ENROL_WAIT_DEFAULT_S * 1000))
                last = QrExtras.underLock { tryOnce() }
                // Replaced by another code's wait meanwhile: this one's answer is no longer the screens'.
                coroutineContext.ensureActive()
                val failure = last.exceptionOrNull()
                answer = when {
                    last.isSuccess -> "enrolled"
                    failure is EnrollWaiting -> {
                        pace = failure.retryAfterS
                        publish(mine, State.Waiting(failure.number))
                        "waiting"
                    }
                    failure is EnrollFailure && failure.refused -> "code_refused"
                    else -> "transient"
                }
            }
        } finally {
            if (!last.isSuccess) withContext(NonCancellable) { forgetKey(app, code) }
        }
        if (last.isSuccess) {
            Log.i(LOG_TAG, "Enrolled after waiting")
            if (!publish(mine, State.Enrolled)) return
            com.kidslauncher.mdm.push.SyncRunner.request(app, "enrolled")
        } else {
            Log.w(LOG_TAG, "The waiting enrolment stopped ($answer)")
            if (!publish(mine, State.RanOut)) return
            if (answer != "code_refused") last = Result.failure(EnrollFailure(refused = true, "the code ran out"))
        }
        try {
            onEnd(last)
        } catch (e: Exception) {
            Log.w(LOG_TAG, "After the waiting enrolment: ${e.javaClass.simpleName}")
        }
    }

    /** Code QA L4: an abandoned code's enrolment key goes. */
    private fun forgetKey(app: Context, code: String) {
        try {
            DeviceKeys.forgetEnrolKey(app, b64u(enrolChallenge(code)))
        } catch (e: Exception) {
            Log.w(LOG_TAG, "The abandoned code's key wasn't forgotten: ${e.javaClass.simpleName}")
        }
    }
}
