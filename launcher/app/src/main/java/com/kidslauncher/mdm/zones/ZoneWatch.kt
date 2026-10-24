package com.kidslauncher.mdm.zones

import android.app.AlarmManager
import android.app.PendingIntent
import android.app.admin.DevicePolicyManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat
import com.kidslauncher.mdm.BuildConfig
import com.kidslauncher.mdm.push.SyncRunner
import com.kidslauncher.mdm.server.LEGACY_LOCATION_POLICY
import com.kidslauncher.mdm.calls.CallPolicyStore
import com.kidslauncher.mdm.calls.managed
import com.kidslauncher.mdm.server.QuickControls
import com.kidslauncher.mdm.server.hardeningManaged
import com.kidslauncher.mdm.server.ServerJson
import com.kidslauncher.mdm.server.currentPolicyDecision
import com.kidslauncher.mdm.server.dto.LocationReport
import com.kidslauncher.mdm.server.dto.PolicyResponse
import com.kidslauncher.mdm.server.dto.ZoneWatchReport
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.serialization.builtins.ListSerializer

private const val LOG_TAG = "ZoneWatch"
private const val PREFS = "zone_watch"
private const val KEY_FENCES = "fences_v1"
private const val KEY_QUEUE = "queue_v1"
private const val KEY_MOCK_IGNORED = "mock_ignored"
private const val KEY_MOCK_LAST = "mock_last_time_ms"
private const val KEY_LAST_BEAT = "last_beat_ms"
private const val ACTION_CONFIRM = "com.kidslauncher.mdm.action.ZONE_CONFIRM"

/** The crossing's confirming fix (§4.5); not BackstopAlarm's 7, the update fence's 11, the
 * self-update's 12 or the torch's 41. */
private const val CONFIRM_REQUEST_CODE = 9

/**
 * The phone's zone watch (design 64 step 2, `docs/design/64-safe-zones.md` §4; rules in
 * `ZonePlan.kt`): after every sync's `apply()` ([apply]) the platform fences ([ZoneFences]), the
 * passive feed ([PassiveFixes]) and the motion sensor ([MotionGate]) follow the enforced policy; a
 * fence broadcast ([onFence]) asks for a sync with a fresh fix now and another 2 min later
 * ([takeFreshWanted]); the fixes a report didn't get through wait in a queue for the next
 * ([afterReport]). The phone judges nothing: the server's two-fix rule on the fixes decides.
 *
 * State in CE prefs `zone_watch` (every write `commit()`): the fences we registered, the queue,
 * the running count of ignored mock fixes and the last heartbeat. Nothing here names a zone - the
 * policy carries none - and nothing but fixes leaves the phone.
 */
/** Which fresh fix a zone sync asks for (design 64 §4.4): a crossing's first fix is balanced, its
 * confirming one precise (GPS first). */
enum class FreshKind { BALANCED, PRECISE }

object ZoneWatch {
    /** 0 = none, 1 = [FreshKind.BALANCED], 2 = [FreshKind.PRECISE]; the stronger ask wins. */
    private val freshWanted = AtomicInteger(0)

    private fun want(kind: FreshKind) {
        val level = kind.ordinal + 1
        freshWanted.getAndUpdate { maxOf(it, level) }
    }

    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /**
     * The enforced policy's zone watch, after every sync's `apply()` (and so at the first sync of
     * every process start - decision 10): Regularly as device owner turns the passive feed on,
     * zones add the fences; anything else turns all of it off. The pause and the override change
     * nothing (decision 12: locating isn't a restriction). Never throws.
     */
    fun apply(context: Context, policy: PolicyResponse?) {
        val app = context.applicationContext
        try {
            val owner = app.getSystemService(DevicePolicyManager::class.java)?.isDeviceOwnerApp(app.packageName) == true
            CallPolicyStore.ensureLoaded(app)
            val managed = hardeningManaged(policy?.allowlist, CallPolicyStore.state.managed)
            val location = policy?.locationPolicy ?: LEGACY_LOCATION_POLICY
            val regularly = owner && managed && location.mode == "interval"
            val zones = policy?.locationPolicy?.zones.orEmpty()
            ZoneFences.sync(app, zones, watching = regularly)
            PassiveFixes.update(app, on = regularly)
            if (!regularly) MotionGate.stop(app) else MotionGate.watch(app, zones = zones.isNotEmpty())
            if (!regularly || zones.isEmpty()) ZoneSeries.stop(app)
        } catch (e: Exception) {
            Log.w(LOG_TAG, "Zone watch apply failed", e)
        }
    }

    /**
     * Not managed any more, or a new enrollment (design 64 step 2, the coordinator's decision 3):
     * every fence of ours removed, the passive feed, the motion sensor and the series stopped, the
     * queue emptied - nothing of the old setup keeps watching or waits to be sent. Cheap when there
     * is nothing (no record, nothing running). Never throws.
     */
    fun release(context: Context) {
        val app = context.applicationContext
        try {
            ZoneFences.sync(app, emptyList(), watching = false)
            PassiveFixes.stop(app)
            MotionGate.stop(app)
            ZoneSeries.stop(app)
            clearQueue(app)
        } catch (e: Exception) {
            Log.w(LOG_TAG, "Zone watch release failed", e)
        }
    }

    /** The zones the enforced policy watches now (Regularly only); none otherwise. */
    fun watchedZones(): List<com.kidslauncher.mdm.server.dto.PolicyZone> =
        currentPolicyDecision().policy?.locationPolicy?.takeIf { it.mode == "interval" }?.zones.orEmpty()

    /** A fence broadcast ([ZoneAlertReceiver]): a crossing asks for a fix now and another in
     * [CONFIRM_DELAY_MS]; an id the current policy doesn't carry and the registration's echo are
     * dropped (§4.2). */
    fun onFence(context: Context, zoneId: Long, entering: Boolean) {
        val app = context.applicationContext
        val zones = watchedZones()
        val record = fenceRecord(app)
        val since = if (record == null) -1L else SystemClock.elapsedRealtime() - record.registeredAtElapsed
        val verdict = fenceVerdict(zoneId, zones, entering, since)
        Log.i(LOG_TAG, "Fence of zone $zoneId (entering=$entering): $verdict")
        if (verdict != FenceVerdict.CROSSING) return
        want(FreshKind.BALANCED)
        SyncRunner.request(app, "zone")
        armConfirm(app)
    }

    /** The confirming fix's alarm fired ([ZoneConfirmReceiver]): the precise path, GPS first. */
    internal fun onConfirm(context: Context) {
        want(FreshKind.PRECISE)
        SyncRunner.request(context.applicationContext, "zone_confirm")
    }

    /** Which fresh fix this sync takes for a crossing, `null` for none - read once, by the sync's
     * location step (a sync that runs meanwhile takes it; a run already past that step leaves it
     * for the coalesced next run). */
    fun takeFreshWanted(): FreshKind? = when (freshWanted.getAndSet(0)) {
        1 -> FreshKind.BALANCED
        2 -> FreshKind.PRECISE
        else -> null
    }

    internal fun armConfirm(context: Context) {
        try {
            val alarms = context.getSystemService(AlarmManager::class.java) ?: return
            val pi = PendingIntent.getBroadcast(
                context,
                CONFIRM_REQUEST_CODE,
                Intent(context, ZoneConfirmReceiver::class.java).setAction(ACTION_CONFIRM),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            val at = SystemClock.elapsedRealtime() + CONFIRM_DELAY_MS
            if (alarms.canScheduleExactAlarms()) {
                alarms.setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, at, pi)
            } else {
                alarms.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, at, pi)
            }
        } catch (e: Exception) {
            Log.w(LOG_TAG, "Couldn't arm the confirming fix", e)
        }
    }

    // --- The heartbeat's stamp (§4.4) ---

    /** Wall time of the last heartbeat - a fix, or one the heartbeat skipped (0 = none yet). The
     * backstop alarm counts the next one from here. */
    fun lastBeatMs(context: Context): Long = prefs(context).getLong(KEY_LAST_BEAT, 0L)

    fun stampBeat(context: Context, nowMs: Long) {
        prefs(context).edit().putLong(KEY_LAST_BEAT, nowMs).commit()
    }

    // --- The mock rule (decision 14) ---

    /** A release build ignores a mock fix and counts it; the debug build takes it (the
     * emulator's test provider). True when [location] may be used. */
    fun accept(context: Context, location: Location): Boolean {
        val verdict = fixVerdict(isMock = location.isMock, acceptMock = BuildConfig.DEBUG)
        if (verdict == FixVerdict.MOCK) countMock(context, location.time)
        return verdict != FixVerdict.MOCK
    }

    /** One more mock fix ignored - once per fix: the same fix seen again (a last-known read after
     * the passive feed had it, two providers handing back one fix) counts once. */
    @Synchronized
    fun countMock(context: Context, timeMs: Long) {
        val p = prefs(context)
        if (p.getLong(KEY_MOCK_LAST, Long.MIN_VALUE) == timeMs) return
        val count = p.getLong(KEY_MOCK_IGNORED, 0L) + 1
        p.edit().putLong(KEY_MOCK_IGNORED, count).putLong(KEY_MOCK_LAST, timeMs).commit()
        // The first of a series, then every hundredth: a spoofing app sends one a second.
        if (count == 1L || count % 100 == 0L) Log.w(LOG_TAG, "Ignored a mock location ($count in all)")
    }

    // --- The fences' record ---

    @Synchronized
    internal fun fenceRecord(context: Context): FenceRecord? = try {
        prefs(context).getString(KEY_FENCES, null)?.let { ServerJson.decodeFromString(FenceRecord.serializer(), it) }
    } catch (e: Exception) {
        Log.w(LOG_TAG, "Fence record unreadable: ${e.javaClass.simpleName}")
        null
    }

    @Synchronized
    internal fun saveFenceRecord(context: Context, record: FenceRecord?) {
        val edit = prefs(context).edit()
        if (record == null) edit.remove(KEY_FENCES) else edit.putString(KEY_FENCES, ServerJson.encodeToString(FenceRecord.serializer(), record))
        if (!edit.commit()) Log.w(LOG_TAG, "Couldn't save the fence record")
    }

    // --- The queue (§4.5) ---

    private val queueSerializer = ListSerializer(QueuedFix.serializer())

    @Synchronized
    fun queue(context: Context): List<QueuedFix> = try {
        prefs(context).getString(KEY_QUEUE, null)?.let { ServerJson.decodeFromString(queueSerializer, it) }.orEmpty()
    } catch (e: Exception) {
        Log.w(LOG_TAG, "Queue unreadable: ${e.javaClass.simpleName}")
        emptyList()
    }

    /** A series check's crossing candidate (§4.5): waits for the report the caller asks for. */
    @Synchronized
    fun enqueue(context: Context, fix: QueuedFix) {
        val after = queueWith(queue(context), fix)
        if (!prefs(context).edit().putString(KEY_QUEUE, ServerJson.encodeToString(queueSerializer, after)).commit()) {
            Log.w(LOG_TAG, "Couldn't save the queue")
        }
    }

    @Synchronized
    private fun clearQueue(context: Context) {
        val p = prefs(context)
        if (p.contains(KEY_QUEUE)) p.edit().remove(KEY_QUEUE).commit()
    }

    /** After the status report: [queueAfterReport] with what it carried. */
    @Synchronized
    fun afterReport(context: Context, sent: List<QueuedFix>, location: QueuedFix?, accepted: Boolean, locationOff: Boolean) {
        val before = queue(context)
        val after = queueAfterReport(before, sent, location, accepted, locationOff)
        if (after == before) return
        val edit = prefs(context).edit()
        if (after.isEmpty()) edit.remove(KEY_QUEUE) else edit.putString(KEY_QUEUE, ServerJson.encodeToString(queueSerializer, after))
        if (!edit.commit()) Log.w(LOG_TAG, "Couldn't save the queue")
        if (after.size > before.size) Log.i(LOG_TAG, "The report didn't get through: ${after.size} fix(es) wait")
    }

    // --- The report ---

    /** `zone_watch` for the status report; `null` when not device owner. Counts and switches only. */
    fun report(context: Context): ZoneWatchReport? = try {
        val app = context.applicationContext
        if (app.getSystemService(DevicePolicyManager::class.java)?.isDeviceOwnerApp(app.packageName) != true) {
            null
        } else {
            ZoneWatchReport(
                fences = fenceRecord(app)?.ids?.size ?: 0,
                motionSensor = MotionGate.hasSensor(app),
                locationOn = QuickControls.isLocationEnabled(app),
                backgroundLocation = ContextCompat.checkSelfPermission(app, android.Manifest.permission.ACCESS_BACKGROUND_LOCATION) ==
                    PackageManager.PERMISSION_GRANTED,
                mockIgnored = prefs(app).getLong(KEY_MOCK_IGNORED, 0L),
            )
        }
    } catch (e: Exception) {
        Log.w(LOG_TAG, "Zone watch report failed", e)
        null
    }
}

/** A platform fix as the queue keeps it. [provider] is the fix's own (`gps`, `network`, `fused`). */
fun Location.toQueued(provider: String? = this.provider): QueuedFix = QueuedFix(
    latitude = latitude,
    longitude = longitude,
    accuracy = if (hasAccuracy()) accuracy else null,
    timeMs = time,
    provider = provider,
    mock = isMock,
)

/** A queued fix as the status report sends it. */
fun QueuedFix.toReport(): LocationReport = LocationReport(
    latitude = latitude,
    longitude = longitude,
    accuracyMeters = accuracy,
    capturedAt = Instant.ofEpochMilli(timeMs).toString(),
    provider = provider?.take(16),
    mock = mock,
)

/** The confirming fix's alarm - our own explicit PendingIntent only (not exported). */
class ZoneConfirmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_CONFIRM) return
        ZoneWatch.onConfirm(context)
    }
}
