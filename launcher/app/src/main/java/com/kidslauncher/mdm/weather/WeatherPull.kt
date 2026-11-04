package com.kidslauncher.mdm.weather

import android.content.Context
import android.util.Log
import com.kidslauncher.mdm.preferences.LauncherPreferences
import com.kidslauncher.mdm.server.DeviceToken
import com.kidslauncher.mdm.server.ServerJson
import com.kidslauncher.mdm.server.createMdmApi
import com.kidslauncher.mdm.server.dto.PolicyResponse
import com.kidslauncher.mdm.server.dto.WeatherAnswer
import com.kidslauncher.mdm.server.dto.WeatherNow
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicBoolean

/** The launcher's capability (design 75 §3.6): the lock glance shows the weather. The server's Device › Weather says
 * the phone needs an update until a report names it. */
const val LOCK_WEATHER_CAPABILITY = "lock_weather_v1"

/**
 * The lock's weather pulls (design 75 §5.4), only ever to our own server's API client - never MET or yr
 * (`WeatherGuardTest`). When: at the end of every sync ([afterSync], before the self-update commit, inside the sync's
 * wake lock - about hourly with the 30-min backstop), at a screen-on ([atScreenOn]) and at once when the policy asks
 * ([WeatherStore.policyChanged]); always by [weatherPullDue], so never with the switch off. The R1 gap: a sync the
 * server nudged ([nudged]) pulls at most every [WEATHER_NUDGE_GAP_MS] - a zone's save or delete changes nothing in
 * the policy, only nudges. Limits (launcher QA M3): one pull in flight ([inFlight]), its own [WEATHER_PULL_TIMEOUT_MS]
 * call timeout, everything but `CancellationException` caught - a weather fault never fails a sync. Logs counts and
 * exception classes, never the place, a temperature, a time of day or a cell.
 */
object WeatherPull {
    private const val LOG_TAG = "WeatherPull"
    private val inFlight = AtomicBoolean(false)
    private val nudge = AtomicBoolean(false)

    /** A pull was asked while one ran (code QA L1: a policy step's pull is never dropped): one more when it ends. */
    private val again = AtomicBoolean(false)

    /** A pull is running. */
    fun running(): Boolean = inFlight.get()

    /** `push/SyncRunner`: the sync about to run was nudged by the server (SSE). */
    fun nudged() = nudge.set(true)

    /** Whether a pull is due now (screen-on, the lock's slot). */
    fun due(nowMs: Long = System.currentTimeMillis(), nudged: Boolean = false): Boolean =
        weatherPullDue(WeatherStore.policyOn, WeatherStore.pulledAtMs, WeatherStore.triedAtMs, nowMs, nudged)

    /** The end of a sync (`performMdmSync`, before the self-update commit): the policy's part first (a change may
     * clear the kept answer and ask for a pull), then a pull when due. Never throws but cancellation. */
    suspend fun afterSync(context: Context, policy: PolicyResponse?) {
        try {
            val asked = policy?.let { WeatherStore.policyChanged(context, WeatherStore.policyOf(it)) } == true
            // E8's M2: a nudge the 3-min gap holds back stays pending for the next screen-on or sync.
            val nudged = nudge.get()
            val ran = (asked || due(nudged = nudged)) && run { pull(context); true }
            nudge.set(weatherNudgeKept(nudge.get(), ran))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            Log.w(LOG_TAG, "Weather: after the sync failed (${e.javaClass.simpleName})")
        }
    }

    /** A screen-on (`PinLockRuntime`'s receiver): a pull in the background when due. */
    fun atScreenOn(context: Context) {
        try {
            if (!due(nudged = nudge.get())) return
            val app = context.applicationContext
            CoroutineScope(Dispatchers.IO).launch {
                pull(app)
                nudge.set(false)
            }
        } catch (e: Throwable) {
            Log.w(LOG_TAG, "Weather: the screen-on pull failed (${e.javaClass.simpleName})")
        }
    }

    /** One pull (if none is running): `GET /api/devices/weather`, its result kept by [WeatherStore.pulled]. Returns
     * whether it ran. */
    suspend fun pull(context: Context): Boolean {
        if (!inFlight.compareAndSet(false, true)) {
            again.set(true)
            return false
        }
        try {
            do {
                again.set(false)
                pullOnce(context)
            } while (again.get() && WeatherStore.policyOn)
            return true
        } finally {
            inFlight.set(false)
        }
    }

    private suspend fun pullOnce(context: Context) {
        try {
            val startedAt = WeatherStore.generation
            val result = fetch()
            WeatherStore.pulled(context.applicationContext, result, System.currentTimeMillis(), startedAt)
            when (result) {
                is WeatherPullResult.Answered -> {
                    Log.i(
                        LOG_TAG,
                        "Weather: pulled (${result.answer.status}, ${result.answer.hours.size} hours)",
                    )
                }
                WeatherPullResult.Failed -> Unit
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            Log.w(LOG_TAG, "Weather: pull failed (${e.javaClass.simpleName})")
        }
    }

    private suspend fun fetch(): WeatherPullResult {
        val api = api(WEATHER_PULL_TIMEOUT_MS) ?: return WeatherPullResult.Failed
        val body = try {
            withTimeoutOrNull(WEATHER_PULL_TIMEOUT_MS) {
                val response = api.weather()
                if (!response.isSuccessful) {
                    Log.w(LOG_TAG, "Weather: pull failed (HTTP ${response.code()})")
                    response.errorBody()?.close()
                    null
                } else {
                    response.body()?.use { it.string() }
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(LOG_TAG, "Weather: pull failed (${e.javaClass.simpleName})")
            null
        } ?: return WeatherPullResult.Failed
        return try {
            WeatherPullResult.Answered(ServerJson.decodeFromString(WeatherAnswer.serializer(), body))
        } catch (e: Exception) {
            Log.w(LOG_TAG, "Weather: the answer doesn't decode (${e.javaClass.simpleName})")
            WeatherPullResult.Failed
        }
    }

    /** The card's nowcast (D9): `GET /api/devices/weather/now` within [WEATHER_NOW_TIMEOUT_MS], or null. */
    suspend fun nowcast(): WeatherNow? = try {
        val api = withContext(Dispatchers.IO) { api(WEATHER_NOW_TIMEOUT_MS) }
        api?.let {
            withTimeoutOrNull(WEATHER_NOW_TIMEOUT_MS) {
                val response = it.weatherNow()
                if (!response.isSuccessful) {
                    response.errorBody()?.close()
                    null
                } else {
                    response.body()?.use { body -> ServerJson.decodeFromString(WeatherNow.serializer(), body.string()) }
                }
            }
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.w(LOG_TAG, "Weather: the nowcast failed (${e.javaClass.simpleName})")
        null
    }

    private fun api(timeoutMs: Long) = try {
        val url = LauncherPreferences.mdm().serverUrl()
        val token = DeviceToken.get()
        if (url.isNullOrBlank() || token.isNullOrBlank()) null else createMdmApi(url, token, callTimeoutMs = timeoutMs)
    } catch (e: Exception) {
        Log.w(LOG_TAG, "Weather: no API client (${e.javaClass.simpleName})")
        null
    }
}
