package com.kidslauncher.mdm.badges

import android.app.PendingIntent
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.kidslauncher.mdm.calls.MissedCallNotice
import com.kidslauncher.mdm.server.CachedPolicy
import com.kidslauncher.mdm.server.cachedPolicy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonElement
import java.util.concurrent.CopyOnWriteArraySet

private const val LOG_TAG = "GlanceStore"

/**
 * What the lock's glance page may show (design 25 §2.5) - **in memory only**, on the main thread:
 * per notification key what [GlanceReader] kept ([GlanceNotice], with its suspended flag) and the
 * notification's content intent (kept, never read); the "opened through the glance" marks; the
 * parent's [policy]. Nothing glance-related goes to any preferences file, DE or CE, the status
 * report, a DTO, the crash report or a log line beyond package names and counts
 * (LockGlanceGuardTest). Rebuilt from the active notifications at every listener connect, cleared
 * at disconnect; a policy change re-sweeps (switching the text off drops every text at once,
 * unticking an app its rows). A process restart forgets everything, the opened marks included.
 */
object GlanceStore {

    /** The parent's choice (`PolicyResponse.lock_glance`): unknown (missed calls only) until the
     * cache or a sync says otherwise. Written on the main thread. */
    @Volatile
    private var policyState = GlancePolicyState.START

    val policy: GlancePolicy get() = policyState.policy

    private val notices = LinkedHashMap<String, GlanceNotice>()
    private val intents = HashMap<String, PendingIntent>()
    private val opened = HashMap<GlanceConversation, Long>()
    private val listeners = CopyOnWriteArraySet<() -> Unit>()
    private val main = Handler(Looper.getMainLooper())

    /** [listener] runs on the main thread after every change of the rows' inputs. */
    fun addListener(listener: () -> Unit) = listeners.add(listener)
    fun removeListener(listener: () -> Unit) = listeners.remove(listener)

    private fun changed() {
        for (listener in listeners) {
            // A glance fault must never crash the process (QA Medium 2).
            try {
                listener()
            } catch (e: Throwable) {
                Log.w(LOG_TAG, "A glance listener failed: ${e.javaClass.simpleName}")
            }
        }
    }

    // ---- the reader's side (main thread) ----------------------------------------------------------

    fun put(notice: GlanceNotice, intent: PendingIntent?) {
        notices[notice.key] = notice
        if (intent != null) intents[notice.key] = intent else intents.remove(notice.key)
        changed()
    }

    /** The reader kept a notification with this key (before a post: an update, not a new one). */
    fun has(key: String): Boolean = key in notices

    /**
     * A post the reader kept (`GlanceReader.posted` only - never a sweep, a ranking update or a
     * policy re-read): [put], and when it [alerts] and the visible rows gained one
     * ([glanceWakes]), the lock may wake the screen (round 7 §3, `PinLockRuntime.noticeCame` -
     * nothing about the notice goes with it).
     */
    fun putPosted(notice: GlanceNotice, intent: PendingIntent?, alerts: Boolean) {
        val before = if (alerts) visibleConversations() else emptySet()
        put(notice, intent)
        if (alerts && glanceWakes(alerts, notice.suspended, before, visibleConversations())) {
            com.kidslauncher.mdm.lock.PinLockRuntime.noticeCame()
        }
    }

    private fun visibleConversations(): Set<GlanceConversation> =
        glanceRowConversations(glanceRows(notices.values, null, policy, opened))

    fun remove(key: String) {
        intents.remove(key)
        if (notices.remove(key) != null) changed()
    }

    /** A sweep (listener connect, policy change): everything the reader kept, nothing else. */
    fun replaceAll(entries: List<Pair<GlanceNotice, PendingIntent?>>) {
        notices.clear()
        intents.clear()
        for ((notice, intent) in entries) {
            notices[notice.key] = notice
            if (intent != null) intents[notice.key] = intent
        }
        changed()
    }

    /** A ranking update: the suspended flag of each kept notification, read again ([suspended]:
     * no ranking found counts as suspended - fail closed, QA Medium 1). */
    fun updateSuspended(suspended: (String) -> Boolean) {
        var any = false
        for ((key, notice) in notices.entries.toList()) {
            val now = suspended(key)
            if (now != notice.suspended) {
                notices[key] = notice.copy(suspended = now)
                any = true
            }
        }
        if (any) changed()
    }

    /** The listener lost access or was unbound: no message rows. */
    fun clearNotices() {
        val had = notices.isNotEmpty()
        notices.clear()
        intents.clear()
        if (had) changed()
    }

    // ---- the lock's side (main thread) ------------------------------------------------------------

    /** The rows now, newest first ([glanceRows]); [missed] is what our missed-call notification shows. */
    fun rows(missed: MissedCallNotice?): List<GlanceRow> = glanceRows(notices.values, missed, policy, opened)

    /** The newest content intent of [conversation] (§1.4: else the one captured at the tap). */
    fun contentIntent(conversation: GlanceConversation): PendingIntent? =
        notices.values
            .filter { it.packageName == conversation.packageName && it.conversationKey == conversation.conversationKey && it.key in intents }
            .maxByOrNull { it.postTimeMs }
            ?.let { intents[it.key] }

    /** The content intent of one notification (the row's newest), if still there. */
    fun contentIntent(notificationKey: String): PendingIntent? = intents[notificationKey]

    /** Opened through the glance (§2.6): the row is hidden until its conversation is posted again
     * with a post time newer than [postTimeMs]. Forgotten at a process restart. */
    fun markOpened(conversation: GlanceConversation, postTimeMs: Long) {
        opened[conversation] = maxOf(postTimeMs, opened[conversation] ?: Long.MIN_VALUE)
        changed()
    }

    /** Design r11 §3.3: every kept key of [conversation] posted up to [upToPostTimeMs] (the row's newest when the kid
     * dismissed it) - a newer post that came after the release isn't among them. Only clearable ones are kept. */
    fun keysOf(conversation: GlanceConversation, upToPostTimeMs: Long): List<String> =
        notices.values
            .filter { it.packageName == conversation.packageName && it.conversationKey == conversation.conversationKey && it.postTimeMs <= upToPostTimeMs }
            .map { it.key }

    /** Design r11 §3.3: a row dismissed on the lock - the same in-memory mark as [markOpened], hidden until a post newer
     * than [upToPostTimeMs], so it stays gone when a cancel is ignored or its removal comes late. */
    fun hide(conversation: GlanceConversation, upToPostTimeMs: Long) = markOpened(conversation, upToPostTimeMs)

    // ---- the policy -------------------------------------------------------------------------------

    /**
     * The parent's choice from `PolicyResponse.lockGlance` ([lockGlancePolicy]; [fresh]: a sync's
     * accepted policy, else the `Ok` cache). Any thread: posted to the main thread (QA Low 12). A
     * change re-sweeps the active notifications. The cache read at process start never overwrites a
     * fresh policy that arrived first.
     */
    fun refreshPolicy(element: JsonElement?, fresh: Boolean) {
        val next = try {
            lockGlancePolicy(element, fresh)
        } catch (e: Throwable) {
            Log.w(LOG_TAG, "Couldn't read the lock glance policy: ${e.javaClass.simpleName}")
            GlancePolicy.UNKNOWN
        }
        main.post {
            try {
                val before = policyState.policy
                policyState = nextGlancePolicy(policyState, next, fresh)
                if (policyState.policy == before) return@post
                val withText = next.apps.count { it.value }
                Log.i(LOG_TAG, "Lock glance policy: ${next.apps.size} apps, $withText with text")
                notices.clear()
                intents.clear()
                GlanceReader.resweep()
                changed()
            } catch (e: Throwable) {
                Log.w(LOG_TAG, "Couldn't apply the lock glance policy: ${e.javaClass.simpleName}")
            }
        }
    }

    /** Process start (`Application.initRest`): the `Ok` cached policy, decoded off the main thread;
     * anything else is unknown (missed calls only). */
    fun loadAtStart() {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                refreshPolicy((cachedPolicy() as? CachedPolicy.Ok)?.policy?.lockGlance, fresh = false)
            } catch (e: Throwable) {
                Log.w(LOG_TAG, "Couldn't read the cached lock glance policy: ${e.javaClass.simpleName}")
            }
        }
    }
}
