package com.kidslauncher.mdm.server

import android.content.Context
import android.os.SystemClock
import com.kidslauncher.mdm.lock.LockClocks
import com.kidslauncher.mdm.play.INSTALL_MODE_DURATION_MS
import com.kidslauncher.mdm.play.PlayRuntime
import com.kidslauncher.mdm.preferences.LauncherPreferences
import com.kidslauncher.mdm.server.dto.ParentPinEvent
import java.security.MessageDigest

/**
 * The one gate for the parent PIN (the offline override PIN) on the phone, design 24 §2: the
 * time lock's "Enter unlock code", the PIN lock's "Parent code", the Settings gate, "Pause all
 * restrictions" and Play install mode all go through [check]. The escalating lockout is inside it
 * (ParentPinLockout.kt), every try is counted - with its event - before PBKDF2 runs, and a
 * correct PIN starts its window (override, pause, install mode) here, after its `unlocked` event
 * is committed: no caller can start one without the gate, and no use goes unrecorded.
 *
 * One lock for everything ([check], [lockoutRemainingMs], the queue). [check] never takes another
 * lock while holding it; if anything is ever nested, this one comes first.
 */
object ParentPin {

    /** PBKDF2 (~0.5 s): never on the main thread. */
    @Synchronized
    fun check(context: Context, entry: ParentPinEntry, pin: String): ParentPinResult {
        val app = context.applicationContext
        val (hash, salt) = cachedPin() ?: return ParentPinResult.NotConfigured
        return checkParentPin(
            store = ParentPinStore.of(app),
            entry = entry,
            pin = pin,
            hashHex = hash,
            saltHex = salt,
            now = clocks(),
            newId = ::newPinEventId,
            storageFull = { storageFull(app) },
            windowStart = { windowDuration(entry)?.let { BootClock.windowStart(it) } },
            startWindow = { start -> if (start != null) startWindow(app, entry, start) },
        )
    }

    /** The lockout time left (0 = none, or no PIN). No hashing, but it may wait for a running
     * [check] - the dialogs' pre-checks accept that. */
    @Synchronized
    fun lockoutRemainingMs(context: Context): Long {
        val (hash, salt) = cachedPin() ?: return 0L
        return parentLockoutRemainingMs(ParentPinStore.of(context), parentPinFingerprint(hash, salt), clocks())
    }

    /** A snapshot of the queued events, for the upload. */
    @Synchronized
    fun pendingEvents(context: Context): List<ParentPinEvent> = ParentPinStore.of(context).queue()

    /** After the server took them: only the sent ids go, events added meanwhile stay (QA #11). */
    @Synchronized
    fun removeEvents(context: Context, ids: Set<String>) {
        val store = ParentPinStore.of(context)
        store.saveQueue(store.queue().filter { it.id !in ids })
    }

    /** Enrollment (a new device token): the events were about the old enrollment. The lockout
     * state stays - it is about the PIN. */
    @Synchronized
    fun clearEvents(context: Context) {
        ParentPinStore.of(context).saveQueue(emptyList())
    }

    private fun cachedPin(): Pair<String, String>? {
        val mdm = LauncherPreferences.mdm()
        val hash = mdm.overridePinHash()
        val salt = mdm.overridePinSalt()
        return if (hash != null && salt != null && PinHash.usable(hash, salt)) hash to salt else null
    }

    private fun clocks() = LockClocks(System.currentTimeMillis(), SystemClock.elapsedRealtime(), BootClock.bootCount())

    private fun storageFull(context: Context): Boolean = try {
        context.filesDir.usableSpace < 1024L * 1024L
    } catch (e: Exception) {
        false
    }

    private fun windowDuration(entry: ParentPinEntry): Long? = when (entry) {
        ParentPinEntry.OVERRIDE -> OVERRIDE_DURATION_MS
        ParentPinEntry.PAUSE -> RESTRICTIONS_PAUSE_DURATION_MS
        ParentPinEntry.INSTALL_MODE -> INSTALL_MODE_DURATION_MS
        ParentPinEntry.PIN_LOCK, ParentPinEntry.SETTINGS -> null
    }

    /** The only callers of the three starters (ParentPinCallersTest). */
    private fun startWindow(context: Context, entry: ParentPinEntry, start: WindowStart) {
        when (entry) {
            ParentPinEntry.OVERRIDE -> OfflineOverride.activate(context, start)
            ParentPinEntry.PAUSE -> RestrictionsPause.start(context, start)
            ParentPinEntry.INSTALL_MODE -> PlayRuntime.startInstallMode(context, start)
            ParentPinEntry.PIN_LOCK, ParentPinEntry.SETTINGS -> Unit
        }
    }
}

/** Which parent PIN a state is about, without a second copy of the hash (the kid lock's
 * `KidLockConfig.fingerprint` recipe). */
internal fun parentPinFingerprint(hashHex: String, saltHex: String): String =
    MessageDigest.getInstance("SHA-256").digest("$saltHex:$hashHex".toByteArray())
        .take(8).joinToString("") { "%02x".format(it) }

/**
 * Load, reset for a new PIN, re-anchor after a reboot - and commit if that changed anything.
 * `null`: the state was unreadable and a committed 15-minute lockout replaces it (QA #2).
 */
internal fun prepareParentState(
    store: ParentPinStore,
    fingerprint: String,
    now: LockClocks,
): Pair<ParentLockoutState, List<ParentPinEvent>>? = when (val loaded = store.load(now, fingerprint)) {
    is ParentPinStore.Loaded.Unreadable -> {
        store.save(unreadableParentState(now, fingerprint), loaded.queue)
        store.logError("Parent PIN state unreadable - a 15-minute lockout replaces it", loaded.error)
        null
    }
    is ParentPinStore.Loaded.Ok -> {
        val state = refreshParentLockout(forParentPin(loaded.state, fingerprint), now)
        if (state != loaded.state) store.save(state, loaded.queue)
        state to loaded.queue
    }
}

internal fun parentLockoutRemainingMs(store: ParentPinStore, fingerprint: String, now: LockClocks): Long =
    prepareParentState(store, fingerprint, now)?.let { parentLockoutRemaining(it.first, now) } ?: PARENT_LOCKOUT_BASE_MS

/**
 * [ParentPin.check]'s steps (design 24 §2.1), against [store] - JVM-tested in ParentPinStoreTest:
 * load and refresh; locked out (nothing counted, no event); too short (not counted); count first,
 * state and event in one commit (a failed commit restores the previous state, records nothing and
 * says so); PBKDF2; on a match back to the pre-attempt state with no failures - the level and its
 * decay stay - and the event becomes `unlocked`, committed before [startWindow] runs.
 */
internal fun checkParentPin(
    store: ParentPinStore,
    entry: ParentPinEntry,
    pin: String,
    hashHex: String,
    saltHex: String,
    now: LockClocks,
    newId: () -> String,
    storageFull: () -> Boolean,
    windowStart: () -> WindowStart?,
    startWindow: (WindowStart?) -> Unit,
): ParentPinResult {
    val (pre, queue) = prepareParentState(store, parentPinFingerprint(hashHex, saltHex), now)
        ?: return ParentPinResult.LockedOut(PARENT_LOCKOUT_BASE_MS)
    val remaining = parentLockoutRemaining(pre, now)
    if (remaining > 0L) return ParentPinResult.LockedOut(remaining)
    if (pin.length < PARENT_MIN_PIN_LENGTH) return ParentPinResult.TooShort

    val attempt = beginParentAttempt(pre, now, entry, newId())
    if (!store.save(attempt.state, enqueuePinEvent(queue, attempt.event))) {
        // commit() changes memory even when the disk write fails: put the old state back, so
        // pressing OK again can't escalate anything (QA #3).
        store.save(pre, queue)
        return ParentPinResult.Unavailable(storageFull())
    }
    if (!PinHash.verify(pin, hashHex, saltHex)) {
        return if (attempt.lockoutMs > 0L) ParentPinResult.LockedOut(attempt.lockoutMs) else wrongResult(attempt.state, now)
    }

    val start = windowStart()
    val used = ParentPinEvent(newId(), PIN_EVENT_UNLOCKED, entry.wire, now.wallMs, start?.untilWallMs, null, null)
    if (!store.save(pre.copy(failures = 0), enqueuePinEvent(queue, used))) {
        // The PIN was right: it works. The use stays in memory for the upload.
        store.logError("Couldn't commit a parent PIN use", null)
    }
    startWindow(start)
    return ParentPinResult.Ok
}
