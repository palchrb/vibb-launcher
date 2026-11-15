package com.kidslauncher.mdm.timerules

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.kidslauncher.mdm.server.MdmApi
import com.kidslauncher.mdm.server.ServerJson
import kotlinx.serialization.builtins.ListSerializer
import java.time.Instant

private const val LOG_TAG = "PhonePauses"
private const val PREFS = "phone_lifts"

/** The gate's store (§4.4.3): only `ParentPin.kt` writes it ([addFromGate]). */
private const val KEY_ENTRIES = "phone_lifts_v1"

/** The server's side: only the post queue ([upload]) and the policy's reconcile ([policyAccepted]) write it. */
private const val KEY_SYNC = "phone_lifts_sync_v1"

/** The newest accepted policy's GET start and phone keys (the reconcile's input). */
private const val KEY_POLICY = "phone_lifts_policy_v1"

/**
 * Design 98 §4.4: the parent code's pauses on this phone - their windows (`phone_lifts_v1`), the server's side
 * (`phone_lifts_sync_v1`) and the newest policy's view. CE prefs (the time rules run after the first unlock), written
 * with `commit()`. A lift in the policy with a `phone_key` lifts only through the entry with that key, inside its own
 * window ([phonePauseLifts]).
 */
object PhonePauses {
    private fun prefs(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private val entriesSerializer = ListSerializer(PhonePause.serializer())
    private val syncSerializer = ListSerializer(PhonePauseSync.serializer())

    fun entries(context: Context): List<PhonePause> = try {
        prefs(context).getString(KEY_ENTRIES, null)?.let { ServerJson.decodeFromString(entriesSerializer, it) } ?: emptyList()
    } catch (e: Exception) {
        // Unreadable: nothing lifts (fail closed).
        emptyList()
    }

    fun syncs(context: Context): List<PhonePauseSync> = try {
        prefs(context).getString(KEY_SYNC, null)?.let { ServerJson.decodeFromString(syncSerializer, it) } ?: emptyList()
    } catch (e: Exception) {
        emptyList()
    }

    fun policyView(context: Context): PausePolicyView? = try {
        prefs(context).getString(KEY_POLICY, null)?.let { ServerJson.decodeFromString(PausePolicyView.serializer(), it) }
    } catch (e: Exception) {
        null
    }

    /** §4.4.1 step 3: the gate stores the pause (pruning the old ones - never one not yet posted, code QA L5). Only
     * `ParentPin.check` calls it. */
    @Synchronized
    fun addFromGate(context: Context, pause: PhonePause, nowMs: Long) {
        val posted = syncs(context).filter { it.posted }.mapTo(HashSet()) { it.key }
        val after = phonePausesAfterAdd(entries(context), pause, nowMs, posted)
        prefs(context).edit().putString(KEY_ENTRIES, ServerJson.encodeToString(entriesSerializer, after)).commit()
        // The sync records of pruned entries go with them.
        val keys = after.map { it.key }.toSet()
        saveSyncs(context, syncs(context).filter { it.key in keys })
    }

    @Synchronized
    private fun saveSyncs(context: Context, syncs: List<PhonePauseSync>) {
        prefs(context).edit().putString(KEY_SYNC, ServerJson.encodeToString(syncSerializer, syncs)).commit()
    }

    @Synchronized
    private fun updateSync(context: Context, key: String, change: (PhonePauseSync?) -> PhonePauseSync?) {
        val all = syncs(context)
        val next = change(all.firstOrNull { it.key == key }) ?: return
        saveSyncs(context, all.filter { it.key != key } + next)
    }

    /** Key -> the rules each entry lifts now. */
    fun lifting(context: Context, clocks: Clocks): Map<String, Set<Long>> {
        val syncs = syncs(context).associateBy { it.key }
        val view = policyView(context)
        return entries(context).associate { it.key to phonePauseLifts(it, syncs[it.key], view, clocks) }.filterValues { it.isNotEmpty() }
    }

    /** Every rule a parent-code pause lifts now. */
    fun liftedRuleIds(context: Context, clocks: Clocks): Set<Long> = lifting(context, clocks).values.flatten().toSet()

    /** The ends of the windows still lifting (the boundary alarm). */
    fun expiries(context: Context, clocks: Clocks): List<Instant> {
        val lifting = lifting(context, clocks)
        return entries(context).flatMap { e -> lifting[e.key]?.let { phonePauseExpiries(e, it, clocks) }.orEmpty() }
    }

    /** The policy's lift ids that a lifting phone pause stands behind (the report's `lifts_active`). */
    fun activeLiftIds(context: Context, lifts: List<Lift>, clocks: Clocks): List<Long> {
        val lifting = lifting(context, clocks)
        return lifts.filter { l -> l.phoneKey != null && l.ruleId != null && lifting[l.phoneKey]?.contains(l.ruleId) == true }.map { it.id }
    }

    /**
     * §4.4.4: after an accepted policy fetched with its GET started at [getStartedMs] - a policy carrying a key acks
     * it; the view is kept for [phonePauseLifts] (a policy after the ack without a rule's row ends that rule's pause).
     */
    fun policyAccepted(context: Context, policy: TimePolicy?, getStartedMs: Long) {
        val view = pausePolicyView(policy, getStartedMs)
        synchronized(this) {
            prefs(context).edit().putString(KEY_POLICY, ServerJson.encodeToString(PausePolicyView.serializer(), view)).commit()
            for (entry in entries(context)) updateSync(context, entry.key) { syncAfterPolicy(it, entry.key, view) }
        }
    }

    /**
     * §4.4.4: the queue, at the start of every sync before the policy GET - each pause not yet posted goes to
     * `POST /api/devices/rule-pause`; the answer acks it, ends it as final, or leaves it for the next sync.
     */
    suspend fun upload(context: Context, api: MdmApi) {
        val now = System.currentTimeMillis()
        val syncs = syncs(context).associateBy { it.key }
        for (entry in entries(context)) {
            if (syncs[entry.key]?.posted == true) continue
            val first = entry.rules.firstOrNull() ?: continue
            val request = RulePauseRequest(
                key = entry.key,
                minutes = entry.minutes,
                startedAtMs = first.untilWallMs - first.durationMs,
                rules = entry.rules.map { RulePauseRuleBody(it.id, it.untilWallMs) },
            )
            val (status, answer) = try {
                val response = api.postRulePause(request)
                val body = (response.body() ?: response.errorBody())?.use { it.string() }
                val parsed = runCatching { body?.let { ServerJson.decodeFromString(RulePauseAnswer.serializer(), it) } }.getOrNull()
                response.code() to parsed
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(LOG_TAG, "The pause's post failed: ${e.javaClass.simpleName}")
                null to null
            }
            val pastEnd = now > (entry.rules.maxOfOrNull { it.untilWallMs } ?: 0L)
            val action = rulePauseAction(status, answer, pastEnd)
            Log.i(LOG_TAG, "Pause posted: $status -> ${action.wire}")
            when (action) {
                RulePauseAction.ACKED -> updateSync(context, entry.key) {
                    PhonePauseSync(entry.key, posted = true, ackedAtWallMs = it?.ackedAtWallMs ?: System.currentTimeMillis(), liftIds = answer?.liftIds.orEmpty())
                }
                RulePauseAction.DONE -> updateSync(context, entry.key) {
                    PhonePauseSync(entry.key, posted = true, ackedAtWallMs = it?.ackedAtWallMs, liftIds = it?.liftIds.orEmpty())
                }
                RulePauseAction.RETRY -> Unit
            }
        }
    }
}
