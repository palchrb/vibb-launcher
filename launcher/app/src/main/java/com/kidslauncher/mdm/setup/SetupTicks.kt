package com.kidslauncher.mdm.setup

import android.content.Context
import android.util.Log
import com.kidslauncher.mdm.server.MdmApi
import kotlinx.serialization.Serializable

/** Design 114 §4.3: the policy's `setup` - the parent steps ticked (in the PWA or on the phone). */
@Serializable
data class SetupPolicy(val ticked: List<String> = emptyList())

/** §4.4: `POST /api/devices/setup/ticks`'s body (the contract's `tick.request`). */
@Serializable
data class SetupTickBody(val step: String)

/**
 * §4.4: the flow's "Gjort" on a parent step. The key goes into a small pending set at the tap, so the step leaves the
 * flow at once; the set is posted at the start of every sync (the tap asks for one) until an answer says done
 * ([tickAction]) or the policy carries the key. Cleared at an enrolment.
 */
object SetupTicks {
    private const val LOG_TAG = "SetupTicks"
    private const val PREFS = "setup_ticks"
    private const val KEY_PENDING = "pending"

    private fun prefs(context: Context) =
        context.applicationContext.createDeviceProtectedStorageContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun pending(context: Context): Set<String> = try {
        prefs(context).getStringSet(KEY_PENDING, null)?.toSet().orEmpty()
    } catch (e: Exception) {
        emptySet()
    }

    private fun store(context: Context, keys: Set<String>) {
        prefs(context).edit().putStringSet(KEY_PENDING, keys).commit()
    }

    /** "Gjort": only a parent step is ever ticked. */
    @Synchronized
    fun add(context: Context, step: SetupStep) {
        if (step.readBy != StepReadBy.PARENT) return
        store(context, pending(context) + step.key)
    }

    @Synchronized
    private fun remove(context: Context, keys: Set<String>) {
        if (keys.isEmpty()) return
        store(context, pending(context) - keys)
    }

    /** After an accepted policy: the keys it carries are done. */
    fun policyAccepted(context: Context, policy: SetupPolicy?) {
        remove(context, pending(context).intersect(policy?.ticked.orEmpty().toSet()))
    }

    fun clear(context: Context) {
        prefs(context).edit().clear().commit()
    }

    /** At the start of every sync, before the policy GET. */
    suspend fun upload(context: Context, api: MdmApi) {
        for (key in pending(context)) {
            val status = try {
                api.postSetupTick(SetupTickBody(key)).also { it.errorBody()?.close(); it.body()?.close() }.code()
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(LOG_TAG, "The tick's post failed: ${e.javaClass.simpleName}")
                null
            }
            val action = tickAction(status)
            Log.i(LOG_TAG, "Tick $key posted: $status -> $action")
            if (action == TickAction.DONE) remove(context, setOf(key))
        }
    }
}
