package com.kidslauncher.mdm.lock

import android.app.ActivityOptions
import android.app.PendingIntent
import android.content.Context
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import com.kidslauncher.mdm.calls.CallPolicyStore
import com.kidslauncher.mdm.server.BootClock
import com.kidslauncher.mdm.server.WindowStart
import com.kidslauncher.mdm.timerules.ScreenTimeTracker

private const val LOG_TAG = "VoipCalls"

/**
 * One call-shaped notification of another app (design 17), in memory only: its [kind] from
 * category, channel and flags, and the intents the lock may send - the ringing notification's
 * full-screen intent and CallStyle decline action, the call service notification's content intent.
 * Never text; the key and the intents are never logged or reported.
 */
class VoipNotice(
    val key: String,
    val packageName: String,
    val kind: VoipNoticeKind,
    val fullScreen: PendingIntent? = null,
    val decline: PendingIntent? = null,
    val content: PendingIntent? = null,
)

/**
 * VoIP calls over the PIN lock (design 17, QA #12 with #1-#11): the glue around the pure
 * [voipExemption]. [com.kidslauncher.mdm.badges.VoipCallReader] feeds it the call-shaped
 * notifications from the notification listener; it keeps the exemption ([phase], [pinnedPackage]),
 * stores it (CE prefs `voip_call`, so a new process keeps the package pinned until the listener
 * reports again - QA #4), tells [LockTaskChrome] and [PinLockRuntime] about changes, rings
 * ([VoipRinger]) and sends the ring's intents from the visible lock: Answer = the full-screen
 * intent (Element's ring screen), Decline = the CallStyle decline action - never the ring's content
 * intent, which is Element's answer intent. Main thread.
 */
object VoipCalls {
    private const val PREFS = "voip_call"
    private const val KEY_PACKAGE = "package"
    private const val KEY_UNTIL = "until_wall"
    private const val KEY_ELAPSED = "elapsed_start"
    private const val KEY_BOOT = "boot"
    private const val KEY_FSI_DENIED = "fsi_denied"

    /** While a call rings or lives, the grace, the cap and the audio mode are looked at this often. */
    private const val POLL_MS = 2_000L

    private val handler = Handler(Looper.getMainLooper())
    private var appContext: Context? = null
    private var initialized = false
    private val notices = LinkedHashMap<String, VoipNotice>()
    private var listenerSeen = false
    private var unverifiedSinceElapsed = 0L
    private var record: VoipRecord? = null
    private val listeners = mutableListOf<() -> Unit>()

    @Volatile
    var phase: VoipPhase = VoipPhase.NONE
        private set

    /** The package kept on the kiosk-off lock-task list ([lockTaskWhileLocked]). */
    @Volatile
    var pinnedPackage: String? = null
        private set

    /** The ringing package (the card's app), while [phase] is RINGING. */
    @Volatile
    var ringingPackage: String? = null
        private set

    /** Packages whose last ring Android stripped of its full-screen intent (QA #11, reported). */
    @Volatile
    var fsiDenied: List<String> = emptyList()
        private set

    /** Identifies the current ring (silencing and Avvis hold for it only), `null` unless RINGING. */
    val ringId: Long?
        get() = record?.takeIf { phase == VoipPhase.RINGING }?.start?.elapsedStartMs

    /** A VoIP call rings or lives: no self-update commit, no Home at boot. */
    val liveCall: Boolean get() = pinnedPackage != null

    /** The lock steps aside (or is the ring screen): screen time counts (QA #9). */
    val exempt: Boolean get() = phase != VoipPhase.NONE

    fun addListener(listener: () -> Unit) { listeners += listener }
    fun removeListener(listener: () -> Unit) { listeners -= listener }

    /** From [PinLockRuntime.init], before the first chrome refresh: the stored exemption keeps its
     * package pinned until the listener reports (QA #4). No side effects yet. */
    fun init(context: Context) {
        if (initialized) return
        initialized = true
        val app = context.applicationContext
        appContext = app
        unverifiedSinceElapsed = SystemClock.elapsedRealtime()
        val prefs = prefs(app)
        record = prefs.getString(KEY_PACKAGE, null)?.let { pkg ->
            VoipRecord(pkg, WindowStart(prefs.getLong(KEY_UNTIL, 0L), prefs.getLong(KEY_ELAPSED, 0L), prefs.getInt(KEY_BOOT, -1)))
        }
        fsiDenied = prefs.getStringSet(KEY_FSI_DENIED, emptySet()).orEmpty().sorted()
        evaluate(app, sideEffects = false)
    }

    // ---- from the notification listener (main thread) ----------------------------------------

    /** The listener (re)connected: every active call-shaped notification. */
    fun replaceAll(context: Context, all: List<VoipNotice>) {
        notices.clear()
        all.forEach { notices[it.key] = it }
        listenerSeen = true
        evaluate(context)
    }

    /** A notification was posted or updated; [notice] `null` = not (or no longer) call-shaped. */
    fun posted(context: Context, key: String, notice: VoipNotice?) {
        if (notice == null) {
            if (notices.remove(key) == null) return
        } else {
            notices[key] = notice
        }
        evaluate(context)
    }

    fun removed(context: Context, key: String) {
        if (notices.remove(key) != null) evaluate(context)
    }

    /** The listener went away: the facts are unknown from now on (the stored record decides). */
    fun listenerLost(context: Context) {
        if (!listenerSeen && notices.isEmpty()) return
        notices.clear()
        listenerSeen = false
        unverifiedSinceElapsed = SystemClock.elapsedRealtime()
        evaluate(context)
    }

    private val poll = Runnable { appContext?.let { evaluate(it) } }

    private fun evaluate(context: Context, sideEffects: Boolean = true) {
        val app = context.applicationContext
        appContext = app
        handler.removeCallbacks(poll)
        val allowed = if (notices.values.any { it.kind == VoipNoticeKind.RINGING || it.kind == VoipNoticeKind.FSI_DENIED }) eligible(app) else emptySet()
        val ringing = notices.values.filter { it.kind == VoipNoticeKind.RINGING && it.packageName in allowed }.mapTo(sortedSetOf()) { it.packageName }
        val inCall = notices.values.filter { it.kind == VoipNoticeKind.IN_CALL }.mapTo(mutableSetOf()) { it.packageName }
        updateFsiDenied(app, ringing, notices.values.filter { it.kind == VoipNoticeKind.FSI_DENIED && it.packageName in allowed }.map { it.packageName })
        val verdict = voipExemption(
            record,
            VoipInputs(
                ringing = ringing,
                inCall = inCall,
                listenerSeen = listenerSeen,
                unverifiedSinceElapsedMs = unverifiedSinceElapsed,
                audioInCommunication = audioInCommunication(app),
                nowWallMs = System.currentTimeMillis(),
                nowElapsedMs = SystemClock.elapsedRealtime(),
                bootCount = BootClock.bootCount(),
            ),
        )
        val oldPhase = phase
        val oldPinned = pinnedPackage
        val newRecord = verdict.record
        if (newRecord?.packageName != record?.packageName || newRecord?.start != record?.start) store(app, newRecord)
        record = newRecord
        phase = verdict.phase
        pinnedPackage = verdict.pinned
        ringingPackage = newRecord?.packageName?.takeIf { verdict.phase == VoipPhase.RINGING }
        if (phase != VoipPhase.NONE || pinnedPackage != null) handler.postDelayed(poll, POLL_MS)
        if (oldPhase != phase || oldPinned != pinnedPackage) {
            // Package names only - never a key, a tag or anything from the notification's text.
            Log.i(LOG_TAG, "VoIP $oldPhase -> $phase, pinned ${pinnedPackage ?: "-"}")
        }
        if (!sideEffects) return
        if (oldPinned != pinnedPackage) {
            try {
                LockTaskChrome.refresh(app)
            } catch (e: Exception) {
                Log.w(LOG_TAG, "Lock chrome refresh failed", e)
            }
        }
        if (oldPhase != phase) {
            ScreenTimeTracker.update(app)
            PinLockRuntime.onVoipPhase(app, oldPhase, phase)
        }
        // Every pass (also the 2 s poll): a call, the emergency flow or an alarm that starts during
        // the ring stops it at once (qa-16-17-code #1).
        PinLockRuntime.syncVoipRinger(app)
        listeners.toList().forEach { it() }
    }

    /** [voipCandidates] of the call path's answer now, unsuspended (not allowlisted = suspended). */
    private fun eligible(context: Context): Set<String> {
        val candidates = try {
            voipCandidates(CallPolicyStore.effectiveState())
        } catch (e: Exception) {
            emptySet()
        }
        return candidates.filterTo(mutableSetOf()) { pkg ->
            try {
                !context.packageManager.isPackageSuspended(pkg)
            } catch (e: Exception) {
                false
            }
        }
    }

    /** A ring with its full-screen intent clears a package; one without it (denied) adds it. */
    private fun updateFsiDenied(context: Context, ringing: Set<String>, denied: List<String>) {
        val next = (fsiDenied.toSet() - ringing + denied).sorted()
        if (next == fsiDenied) return
        fsiDenied = next
        if (denied.isNotEmpty()) Log.w(LOG_TAG, "No full-screen intent for $denied: their calls can't ring over the lock")
        prefs(context).edit().putStringSet(KEY_FSI_DENIED, next.toSet()).apply()
    }

    private fun audioInCommunication(context: Context): Boolean = try {
        context.getSystemService(AudioManager::class.java)?.mode == AudioManager.MODE_IN_COMMUNICATION
    } catch (e: Exception) {
        false
    }

    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun store(context: Context, r: VoipRecord?) {
        val editor = prefs(context).edit()
        if (r == null) {
            editor.remove(KEY_PACKAGE).remove(KEY_UNTIL).remove(KEY_ELAPSED).remove(KEY_BOOT)
        } else {
            editor.putString(KEY_PACKAGE, r.packageName).putLong(KEY_UNTIL, r.start.untilWallMs)
                .putLong(KEY_ELAPSED, r.start.elapsedStartMs).putInt(KEY_BOOT, r.start.bootCount)
        }
        editor.apply()
    }

    // ---- the lock's actions (sent from the visible lock) ----------------------------------------

    private fun ringingNotice(): VoipNotice? {
        val pkg = ringingPackage ?: return null
        return notices.values.lastOrNull { it.kind == VoipNoticeKind.RINGING && it.packageName == pkg }
    }

    /** The card's app label (the app's own; design 14's override goes here once built). */
    fun ringingLabel(context: Context): String? {
        val pkg = ringingPackage ?: return null
        return try {
            val pm = context.packageManager
            pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
        } catch (e: Exception) {
            pkg
        }
    }

    /** Answer on the card: the ring's full-screen intent (the app's ring screen) - only ever that. */
    fun answer(context: Context): Boolean {
        val intent = ringingNotice()?.fullScreen ?: return false
        return send(context, intent, startsActivity = true)
    }

    /** Decline on the card: the CallStyle decline action. */
    fun decline(context: Context): Boolean {
        val intent = ringingNotice()?.decline ?: return false
        return send(context, intent, startsActivity = false)
    }

    /** The lock resumed during the call: the app's call screen again (its call service
     * notification's content intent), like our call screen ([LockStep.showVoipCall]). */
    fun reopenCall(context: Context): Boolean {
        val pkg = record?.packageName ?: return false
        val intent = notices.values.lastOrNull { it.kind == VoipNoticeKind.IN_CALL && it.packageName == pkg }?.content ?: return false
        return send(context, intent, startsActivity = true)
    }

    /**
     * From our visible, resumed lock (QA #3): a PendingIntent's *sender* may start an activity in
     * the background only with a visible window and its opt-in - the device-owner and HOME
     * exemptions are the caller's, not the sender's. A refused start is silent; a re-posted
     * notification's cancelled intent throws (then `false`: the card says so, the next post brings
     * the new one).
     */
    private fun send(context: Context, intent: PendingIntent, startsActivity: Boolean): Boolean = try {
        intent.send(context, 0, null, null, null, null, if (startsActivity) visibleSenderOptions() else null)
        true
    } catch (e: PendingIntent.CanceledException) {
        Log.w(LOG_TAG, "The call app's intent was cancelled")
        false
    } catch (e: Exception) {
        Log.w(LOG_TAG, "Couldn't send the call app's intent: ${e.javaClass.simpleName}")
        false
    }

    private fun visibleSenderOptions(): Bundle {
        val options = ActivityOptions.makeBasic()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.BAKLAVA) {
            options.setPendingIntentBackgroundActivityStartMode(ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOW_IF_VISIBLE)
        } else {
            allowLegacy(options)
        }
        return options.toBundle()
    }

    /** API 34/35 have no ALLOW_IF_VISIBLE; ALLOWED is the same opt-in there (deprecated in 36). */
    @Suppress("DEPRECATION")
    private fun allowLegacy(options: ActivityOptions) {
        options.setPendingIntentBackgroundActivityStartMode(ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED)
    }
}
