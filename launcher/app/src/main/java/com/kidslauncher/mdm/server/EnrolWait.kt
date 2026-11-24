package com.kidslauncher.mdm.server

import android.content.Context
import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * Design 129b D11: the one waiting enrolment of the process. The enrolment's caller ("Koble til", the QR's apply and
 * its retry, Settings' "Enroll now" and scanner, "Ny oppsettkode") got [EnrollWaiting]: this keeps trying the same code
 * with the same attempt (the key book's, [DeviceKeys.enrolAttempt]) at the server's pace through [enrolWaitStep] - each
 * try under [QrExtras]' mutex and never cancelled once sent - until it enrols (with a key, or exempt), the code is
 * refused, or 45 minutes since the first waiting answer. A transient answer keeps the schedule. Each try makes a key when
 * the phone has none ([Enrollment.enroll]). [state] is what the screens show: "Venter på en forelder." and the number.
 */
object EnrolWait {
    private const val LOG_TAG = "EnrolWait"

    /** What the enrolment screens show. */
    sealed interface State {
        /** "Venter på en forelder." with "Kode: [number]". */
        data class Waiting(val number: String) : State

        /** The 45-minute stop or a refused code: "Koden er utløpt. Be om en ny." (L7). */
        data object RanOut : State
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val current = MutableStateFlow<State?>(null)
    val state: StateFlow<State?> = current

    @Volatile
    private var job: Job? = null

    /** Whether a waiting enrolment runs. */
    fun running(): Boolean = job?.isActive == true

    /** The screens' lines for [state] (`null` when nothing waits). */
    fun lines(context: Context): String? = when (val s = current.value) {
        is State.Waiting -> context.getString(com.kidslauncher.mdm.R.string.enrol_key_wait) + "\n" +
            context.getString(com.kidslauncher.mdm.R.string.enrol_key_number, shownNumber(s.number))
        State.RanOut -> context.getString(com.kidslauncher.mdm.R.string.enrol_code_ran_out)
        null -> null
    }

    /** A screen showed [State.RanOut]: the fields are back. */
    fun clearRanOut() {
        current.compareAndSet(State.RanOut, null)
    }

    /**
     * Starts (or restarts, for another code) the waiting after [first]. [tryOnce] is one enrolment try with the same
     * code; [onEnd] hears the last result (success, or the code refused or run out) - the QR's marker, a sync.
     */
    fun start(
        context: Context,
        first: EnrollWaiting,
        tryOnce: suspend () -> Result<Unit>,
        onEnd: suspend (Result<Unit>) -> Unit = {},
    ) {
        val app = context.applicationContext
        current.value = State.Waiting(first.number)
        synchronized(this) {
            job?.cancel()
            job = scope.launch { run(app, first, tryOnce, onEnd) }
        }
    }

    private suspend fun run(
        app: Context,
        first: EnrollWaiting,
        tryOnce: suspend () -> Result<Unit>,
        onEnd: suspend (Result<Unit>) -> Unit,
    ) {
        val firstAt = SystemClock.elapsedRealtime()
        var answer = "waiting"
        var pace: Long? = first.retryAfterS
        var last: Result<Unit> = Result.failure(first)
        while (true) {
            val step = enrolWaitStep(answer, pace, SystemClock.elapsedRealtime() - firstAt, hasKey = true)
            if (step.action != "retry") break
            delay(step.delayMs ?: (ENROL_WAIT_DEFAULT_S * 1000))
            last = QrExtras.underLock { tryOnce() }
            val failure = last.exceptionOrNull()
            answer = when {
                last.isSuccess -> "enrolled"
                failure is EnrollWaiting -> {
                    pace = failure.retryAfterS
                    current.value = State.Waiting(failure.number)
                    "waiting"
                }
                failure is EnrollFailure && failure.refused -> "code_refused"
                else -> "transient"
            }
        }
        if (last.isSuccess) {
            Log.i(LOG_TAG, "Enrolled after waiting")
            current.value = null
            com.kidslauncher.mdm.push.SyncRunner.request(app, "enrolled")
        } else {
            Log.w(LOG_TAG, "The waiting enrolment stopped ($answer)")
            current.value = State.RanOut
            if (answer != "code_refused") last = Result.failure(EnrollFailure(refused = true, "the code ran out"))
        }
        try {
            onEnd(last)
        } catch (e: Exception) {
            Log.w(LOG_TAG, "After the waiting enrolment: ${e.javaClass.simpleName}")
        }
    }
}
