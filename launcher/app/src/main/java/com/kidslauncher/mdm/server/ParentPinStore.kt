package com.kidslauncher.mdm.server

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.kidslauncher.mdm.lock.BackoffWindow
import com.kidslauncher.mdm.lock.LockClocks
import com.kidslauncher.mdm.preferences.LauncherPreferences
import com.kidslauncher.mdm.server.dto.ParentPinEvent
import kotlinx.serialization.builtins.ListSerializer

/** The pre-design-24 counter in the default prefs, read once by the migration and then zeroed. */
interface LegacyParentPinCounter {
    fun failedAttempts(): Int

    fun lockedUntilWallMs(): Long

    fun clear()
}

/**
 * The parent PIN's lockout state and event queue (design 24 §2.2): credential-encrypted prefs
 * `parent_pin_state` (never device-protected storage; excluded from backup like everything else;
 * a `level` key from the first build is ignored),
 * every write a synchronous `commit()` of every key, state and queue together. Used only under
 * [ParentPin]'s lock.
 *
 * A state that can't be read (a type clash, a value out of range, a missing key) is
 * [Loaded.Unreadable] - the caller fails closed with a 15-minute lockout and rewrites it (QA #2).
 * The queue is decoded on its own and leniently: unreadable means empty, never a PIN result.
 */
class ParentPinStore(
    private val prefs: SharedPreferences,
    private val legacy: LegacyParentPinCounter,
    val logError: (String, Throwable?) -> Unit,
) {
    sealed interface Loaded {
        val queue: List<ParentPinEvent>

        data class Ok(val state: ParentLockoutState, override val queue: List<ParentPinEvent>) : Loaded

        data class Unreadable(val error: Exception, override val queue: List<ParentPinEvent>) : Loaded
    }

    /** The stored state; the first load on a phone without one migrates the old counter
     * (design 24 §2.2) for [fingerprint], the PIN cached now. */
    fun load(now: LockClocks, fingerprint: String): Loaded {
        val queue = queue()
        return try {
            Loaded.Ok(if (prefs.contains(VERSION)) read() else migrate(now, fingerprint, queue), queue)
        } catch (e: Exception) {
            Loaded.Unreadable(e, queue)
        }
    }

    fun queue(): List<ParentPinEvent> = try {
        prefs.getString(EVENTS, null)?.let { ServerJson.decodeFromString(QUEUE, it) }.orEmpty()
    } catch (e: Exception) {
        logError("Parent PIN event queue unreadable - dropped", e)
        emptyList()
    }

    /** One commit of everything; false if it failed (memory may hold it anyway - the caller
     * writes the previous state back then, QA #3). */
    fun save(state: ParentLockoutState, queue: List<ParentPinEvent>): Boolean = try {
        val editor = prefs.edit()
            .putInt(VERSION, STATE_VERSION)
            .putInt(FAILURES, state.failures)
            .putString(PIN_FP, state.pinFingerprint)
            .putString(EVENTS, ServerJson.encodeToString(QUEUE, queue))
        putWindow(editor, state.lockout, LO_WALL, LO_ELAPSED, LO_BOOT, LO_DURATION)
        putWindow(editor, state.decay, DC_WALL, DC_ELAPSED, DC_BOOT, DC_DURATION)
        editor.commit()
    } catch (e: Exception) {
        false
    }

    /** Only the queue (the upload's removal, enrollment). */
    fun saveQueue(queue: List<ParentPinEvent>): Boolean = try {
        prefs.edit().putString(EVENTS, ServerJson.encodeToString(QUEUE, queue)).commit()
    } catch (e: Exception) {
        false
    }

    private fun migrate(now: LockClocks, fingerprint: String, queue: List<ParentPinEvent>): ParentLockoutState {
        val state = migrateParentLockout(legacy.failedAttempts(), legacy.lockedUntilWallMs(), now, fingerprint)
        // A crash between the two leaves only legacy keys that nothing reads any more.
        if (save(state, queue)) legacy.clear()
        return state
    }

    private fun read(): ParentLockoutState {
        val version = int(VERSION)
        require(version == STATE_VERSION) { "unknown version $version" }
        val failures = int(FAILURES)
        require(failures in 0 until PARENT_FREE_TRIES) { "failures $failures" }
        val fingerprint = prefs.getString(PIN_FP, null) ?: error("no $PIN_FP")
        return ParentLockoutState(
            failures = failures,
            lockout = window(LO_WALL, LO_ELAPSED, LO_BOOT, LO_DURATION, PARENT_LOCKOUT_CAP_MS),
            decay = window(DC_WALL, DC_ELAPSED, DC_BOOT, DC_DURATION, PARENT_LEVEL_MAX * PARENT_LEVEL_DECAY_MS),
            pinFingerprint = fingerprint,
        )
    }

    private fun window(wall: String, elapsed: String, boot: String, duration: String, max: Long): BackoffWindow? {
        val ms = long(duration)
        require(ms in 0L..max) { "$duration $ms" }
        val window = BackoffWindow(long(wall), long(elapsed), int(boot), ms)
        return if (ms > 0L) window else null
    }

    private fun int(key: String): Int {
        require(prefs.contains(key)) { "no $key" }
        return prefs.getInt(key, 0)
    }

    private fun long(key: String): Long {
        require(prefs.contains(key)) { "no $key" }
        return prefs.getLong(key, 0L)
    }

    private fun putWindow(editor: SharedPreferences.Editor, window: BackoffWindow?, wall: String, elapsed: String, boot: String, duration: String) {
        editor.putLong(wall, window?.wallStartMs ?: 0L)
            .putLong(elapsed, window?.elapsedStartMs ?: 0L)
            .putInt(boot, window?.bootCount ?: -1)
            .putLong(duration, window?.durationMs ?: 0L)
    }

    companion object {
        const val PREFS = "parent_pin_state"
        const val STATE_VERSION = 1

        const val VERSION = "version"
        const val FAILURES = "failures"
        const val LO_WALL = "lo_wall"
        const val LO_ELAPSED = "lo_elapsed"
        const val LO_BOOT = "lo_boot"
        const val LO_DURATION = "lo_duration"
        const val DC_WALL = "dc_wall"
        const val DC_ELAPSED = "dc_elapsed"
        const val DC_BOOT = "dc_boot"
        const val DC_DURATION = "dc_duration"
        const val PIN_FP = "pin_fp"
        const val EVENTS = "events_v1"

        private val QUEUE = ListSerializer(ParentPinEvent.serializer())

        fun of(context: Context): ParentPinStore = ParentPinStore(
            context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE),
            LauncherLegacyCounter,
        ) { message, error -> Log.e("ParentPin", message, error) }
    }
}

/** `offline_override_failed_attempts`/`offline_override_locked_until` - kept declared in
 * `LauncherPreferences$Config` only for this read (design 24 §2.2). */
private object LauncherLegacyCounter : LegacyParentPinCounter {
    override fun failedAttempts(): Int = LauncherPreferences.mdm().offlineOverrideFailedAttempts()

    override fun lockedUntilWallMs(): Long = LauncherPreferences.mdm().offlineOverrideLockedUntil()

    override fun clear() {
        val mdm = LauncherPreferences.mdm()
        mdm.offlineOverrideFailedAttempts(0)
        mdm.offlineOverrideLockedUntil(0)
    }
}
