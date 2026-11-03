package com.kidslauncher.mdm.weather

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import com.kidslauncher.mdm.server.CachedPolicy
import com.kidslauncher.mdm.server.ServerJson
import com.kidslauncher.mdm.server.cachedPolicy
import com.kidslauncher.mdm.server.dto.PolicyResponse
import com.kidslauncher.mdm.server.dto.WeatherAnswer
import com.kidslauncher.mdm.server.dto.WeatherNow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * The lock's weather (design 75 §5.4): the last `ok` answer and when it was pulled and last tried - in memory, and in
 * one credential-encrypted preferences file `weather` (`answer_json`, `pulled_at_ms`, `tried_at_ms`) so a restart
 * keeps the line -, the policy's part ([WeatherPolicy]) and the card's last nowcast answer (memory only, reused
 * [WEATHER_NOW_REUSE_MS]). Main thread; listeners hear every change of the answer. A policy change clears what it must
 * ([weatherPolicyStep]) and may ask for a pull ([WeatherPull]). Never logs the place, a temperature or a time of day.
 */
object WeatherStore {
    private const val LOG_TAG = "WeatherStore"
    private const val PREFS = "weather"
    private const val KEY_ANSWER = "answer_json"
    private const val KEY_PULLED = "pulled_at_ms"
    private const val KEY_TRIED = "tried_at_ms"

    private val main = Handler(Looper.getMainLooper())
    private val listeners = mutableListOf<() -> Unit>()
    private var app: Context? = null

    @Volatile var answer: WeatherAnswer? = null
        private set
    @Volatile var pulledAtMs: Long? = null
        private set
    @Volatile var triedAtMs: Long? = null
        private set
    @Volatile var policy: WeatherPolicy? = null
        private set

    /** The card's last nowcast answer and when it came (elapsed realtime). */
    @Volatile var now: WeatherNow? = null
        private set
    @Volatile var nowAtElapsed: Long? = null
        private set

    val policyOn: Boolean get() = policy?.on == true

    fun addListener(listener: () -> Unit) { if (listener !in listeners) listeners += listener }
    fun removeListener(listener: () -> Unit) { listeners -= listener }

    private fun changed() = main.post { listeners.toList().forEach { runCatching { it() } } }

    /** Process start: the kept answer and the `Ok` cached policy (never over a sync's), off the main thread. */
    fun loadAtStart(context: Context) {
        app = context.applicationContext
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val prefs = prefs(context.applicationContext)
                val kept = prefs?.getString(KEY_ANSWER, null)?.let {
                    runCatching { ServerJson.decodeFromString(WeatherAnswer.serializer(), it) }.getOrNull()
                }
                if (answer == null && kept?.status == "ok") answer = kept
                if (pulledAtMs == null) pulledAtMs = prefs?.getLong(KEY_PULLED, 0L)?.takeIf { it > 0 }
                if (triedAtMs == null) triedAtMs = prefs?.getLong(KEY_TRIED, 0L)?.takeIf { it > 0 }
                val cached = (cachedPolicy() as? CachedPolicy.Ok)?.policy
                if (policy == null && cached != null) policy = policyOf(cached)
                // The switch went off while the process was gone: nothing of the last answer is kept.
                if (cached != null && policy?.on == false && answer != null) clear(context.applicationContext) else changed()
            } catch (e: Throwable) {
                Log.w(LOG_TAG, "Couldn't read the kept weather: ${e.javaClass.simpleName}")
            }
        }
    }

    fun policyOf(policy: PolicyResponse): WeatherPolicy =
        WeatherPolicy(policy.launcherUi?.lockWeather == true, policy.locationPolicy?.mode ?: "on_request")

    /** Every accepted sync's policy: what [weatherPolicyStep] says - nothing, clear, or clear and a pull due now
     * (returned). */
    fun policyChanged(context: Context, now: WeatherPolicy): Boolean {
        app = context.applicationContext
        val step = weatherPolicyStep(policy, now)
        policy = now
        if (step != WeatherPolicyStep.NONE) {
            Log.i(LOG_TAG, "Weather: the policy changed (${step.name.lowercase()})")
            clear(context)
        }
        return step == WeatherPolicyStep.CLEAR_AND_PULL
    }

    /** A pull's result ([weatherKeep]): the kept answer, the stamps, the prefs. */
    fun pulled(context: Context, result: WeatherPullResult, atMs: Long) {
        val kept = weatherKeep(answer, result)
        triedAtMs = atMs
        // A settled answer (`ok`, `off`, `no_position`) waits the hour; `unavailable` and a failure only the try gap.
        if (result is WeatherPullResult.Answered && result.answer.status in setOf("ok", "off", "no_position")) pulledAtMs = atMs
        val changedAnswer = kept !== answer
        answer = kept
        write(context)
        if (changedAnswer) changed()
    }

    /** The card's nowcast answer (memory only). */
    fun nowAnswered(answer: WeatherNow?) {
        now = answer
        nowAtElapsed = SystemClock.elapsedRealtime()
    }

    /** The kept nowcast answer while it is [WEATHER_NOW_REUSE_MS] fresh, else null. */
    fun freshNow(): WeatherNow? {
        val at = nowAtElapsed ?: return null
        return now?.takeIf { SystemClock.elapsedRealtime() - at < WEATHER_NOW_REUSE_MS }
    }

    /** The switch off, a location-mode change: nothing kept, a pull due. */
    fun clear(context: Context) {
        answer = null
        pulledAtMs = null
        triedAtMs = null
        now = null
        nowAtElapsed = null
        write(context)
        changed()
    }

    private fun write(context: Context) {
        try {
            val json = answer?.let { ServerJson.encodeToString(WeatherAnswer.serializer(), it) }
            prefs(context)?.edit()?.apply {
                if (json == null) remove(KEY_ANSWER) else putString(KEY_ANSWER, json)
                pulledAtMs?.let { putLong(KEY_PULLED, it) } ?: remove(KEY_PULLED)
                triedAtMs?.let { putLong(KEY_TRIED, it) } ?: remove(KEY_TRIED)
            }?.apply()
        } catch (e: Throwable) {
            Log.w(LOG_TAG, "Couldn't keep the weather: ${e.javaClass.simpleName}")
        }
    }

    /** Credential-encrypted: before the first unlock there is no weather (and nothing to show it on). */
    private fun prefs(context: Context) = try {
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    } catch (e: IllegalStateException) {
        null
    }
}
