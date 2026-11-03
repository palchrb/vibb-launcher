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

    /** Whether [loadAtStart]'s read got through (before the first unlock the credential-encrypted prefs throw). */
    @Volatile private var loaded = false

    /** Process start: the kept answer and the `Ok` cached policy (never over a sync's), off the main thread. Again at
     * the lock's next start while it hasn't got through (code QA L8: the first unlock after a boot). */
    fun loadAtStart(context: Context) {
        app = context.applicationContext
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val prefs = prefs(context.applicationContext) ?: return@launch
                val kept = prefs.getString(KEY_ANSWER, null)?.let {
                    runCatching { ServerJson.decodeFromString(WeatherAnswer.serializer(), it) }.getOrNull()
                }
                val cached = (cachedPolicy() as? CachedPolicy.Ok)?.policy
                synchronized(this@WeatherStore) {
                    if (answer == null && kept?.status == "ok" && generation == 0) answer = kept
                    if (pulledAtMs == null) pulledAtMs = prefs.getLong(KEY_PULLED, 0L).takeIf { it > 0 }
                    if (triedAtMs == null) triedAtMs = prefs.getLong(KEY_TRIED, 0L).takeIf { it > 0 }
                    if (policy == null && cached != null) policy = policyOf(cached)
                }
                loaded = true
                // The switch went off while the process was gone: nothing of the last answer is kept.
                if (cached != null && policy?.on == false && answer != null) clear(context.applicationContext) else changed()
            } catch (e: Throwable) {
                Log.w(LOG_TAG, "Couldn't read the kept weather: ${e.javaClass.simpleName}")
            }
        }
    }

    /** The lock's start (code QA L8): the cached policy's switch read now, on the calling thread, when no policy is
     * known yet - so the date is short from the first frame -, and the kept answer read again if it couldn't be. */
    fun ensureLoaded(context: Context) {
        try {
            if (policy == null) {
                val cached = (cachedPolicy() as? CachedPolicy.Ok)?.policy
                if (cached != null) synchronized(this) { if (policy == null) policy = policyOf(cached) }
            }
        } catch (e: Throwable) {
            Log.w(LOG_TAG, "Couldn't read the cached policy: ${e.javaClass.simpleName}")
        }
        if (!loaded) loadAtStart(context)
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

    /** Bumped by every [clear] (code QA L1): a pull that started before it keeps nothing it brought. */
    @Volatile var generation = 0
        private set

    /** A pull's result ([weatherKeep]): the kept answer, the stamps, the prefs - one step under the store's lock, and
     * only for a pull started at [startedAt] = the current [generation] (a clear since then, a policy step, wins). */
    fun pulled(context: Context, result: WeatherPullResult, atMs: Long, startedAt: Int) {
        val changedAnswer = synchronized(this) {
            if (startedAt != generation) return
            val kept = weatherKeep(answer, result)
            triedAtMs = atMs
            // A settled answer (`ok`, `off`, `no_position`) waits the hour; `unavailable` and a failure only the try gap.
            if (result is WeatherPullResult.Answered && result.answer.status in setOf("ok", "off", "no_position")) pulledAtMs = atMs
            val changed = kept !== answer
            answer = kept
            write(context)
            changed
        }
        if (changedAnswer) changed()
    }

    /** When the card's last nowcast ask started (elapsed realtime), and whether it still runs (code QA M1). */
    @Volatile var nowAskedAtElapsed: Long? = null
        private set
    @Volatile var nowAsking = false
        private set

    /** An ask starts: stamped now, whatever it brings ([weatherNowAsk] waits [WEATHER_NOW_REUSE_MS] from here). */
    fun nowAskStarted() {
        nowAskedAtElapsed = SystemClock.elapsedRealtime()
        nowAsking = true
    }

    /** An ask ended (or was cancelled): a good answer kept; a failure never replaces one (code QA M1). */
    fun nowAskEnded(answer: WeatherNow?) {
        nowAsking = false
        if (answer != null) {
            now = answer
            nowAtElapsed = SystemClock.elapsedRealtime()
        }
    }

    /** The kept nowcast answer while it is [WEATHER_NOW_REUSE_MS] fresh, else null. */
    fun freshNow(): WeatherNow? {
        val at = nowAtElapsed ?: return null
        return now?.takeIf { SystemClock.elapsedRealtime() - at < WEATHER_NOW_REUSE_MS }
    }

    /** The switch off, a location-mode change: nothing kept, a pull due; a pull in flight keeps nothing. */
    fun clear(context: Context) {
        synchronized(this) {
            generation++
            answer = null
            pulledAtMs = null
            triedAtMs = null
            now = null
            nowAtElapsed = null
            write(context)
        }
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
