package com.kidslauncher.mdm.server

import android.app.PendingIntent
import android.app.admin.DeviceAdminInfo
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.location.Location
import android.location.LocationManager
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.os.Build
import android.os.CancellationSignal
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.preference.PreferenceManager
import com.kidslauncher.mdm.NOTIFICATION_CHANNEL_RING
import com.kidslauncher.mdm.R
import com.kidslauncher.mdm.RING_NOTIFICATION_ID
import com.kidslauncher.mdm.preferences.LauncherPreferences
import com.kidslauncher.mdm.zones.ZoneWatch
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable

private const val LOG_TAG = "LocateCommands"
private const val RING_DURATION_MS = 30_000L
private const val FRESH_LOCATION_TIMEOUT_MS = 15_000L

// An active fetch (requestFreshFix) shows Android's location-in-use indicator and visibly slows
// down the sync it runs in - confirmed live, reported as "Sync now takes longer and the green
// location dot shows up every time." How often one happens is the parent's location policy now
// (handy step 6: off / on request / every N >= 10 minutes - see locationAction); a `ring`/`locate`
// command ("Update location now") always asks for a fresh fix unless location is off.
private const val CACHED_LOCATION_PROVIDER = "kid_phone_cached"

// Android's location-in-use indicator fires on *any* LocationManager query that returns a fix -
// confirmed live, the passive getLastKnownLocation() fallback below was still triggering it (and
// still touching LocationManager) on every throttled-skip sync, even after gating the active fetch
// alone. This app now keeps its own copy of the last fix it obtained instead, so a skipped cycle
// can hand that back without calling into LocationManager at all.
// Design 64 step 2: the fix's own provider and mock flag ride along (an older cache has neither:
// the provider unknown, not mock), so a cached re-send reports where the fix came from.
@Serializable
private data class CachedFix(
    val latitude: Double,
    val longitude: Double,
    val accuracy: Float?,
    val timeMs: Long,
    val provider: String? = null,
    val mock: Boolean = false,
)

/**
 * Find My Device: locate/ring/lock/wipe, dispatched from [MdmSyncWorker] whenever the server's
 * `pendingCommand` is present - never a push, same 2-minute-polling tradeoff as everything else in
 * this app. Same "direct, silent Device-Owner API" style as [QuickControls].
 */
object LocateCommands {

    /**
     * Plain [android.location.LocationManager], never `FusedLocationProviderClient` - this app has
     * no Google Play Services anywhere (GrapheneOS has none). Self-grants the location permissions
     * first, and - separately - ensures the phone's system-wide Location toggle is on, since
     * `LocationManager` returns nothing at all while it's off, regardless of any permission held
     * (the same platform-level gate discovered tonight while building the WiFi network picker,
     * except there it was a surprising side effect of scanning APIs; here it's simply what the
     * Location toggle fundamentally controls). Unlike the WiFi picker's screen-scoped enable/restore,
     * this leaves Location on rather than toggling it back off - Find My Device is an ongoing
     * background feature, not a single screen visit, so there's nothing meaningful to "restore" to.
     */
    suspend fun currentLocation(
        context: Context,
        dpm: DevicePolicyManager,
        admin: ComponentName,
        action: LocationAction,
    ): Location? {
        // Off: no permission grants, no Location toggle, no LocationManager - nothing at all.
        if (action == LocationAction.NONE) return null
        // Not due: our own cached fix only, no LocationManager call (no indicator, no slow sync).
        if (action == LocationAction.CACHED) return loadCachedFix()

        QuickControls.selfGrantPermission(
            context,
            dpm,
            admin,
            android.Manifest.permission.ACCESS_FINE_LOCATION,
        )
        QuickControls.selfGrantPermission(
            context,
            dpm,
            admin,
            android.Manifest.permission.ACCESS_COARSE_LOCATION,
        )
        QuickControls.selfGrantPermission(
            context,
            dpm,
            admin,
            android.Manifest.permission.ACCESS_BACKGROUND_LOCATION,
        )

        // Diagnostic - unlike every other permission self-granted in this app,
        // ACCESS_BACKGROUND_LOCATION hasn't been confirmed to actually work via Device-Owner
        // self-grant. If it silently fails, this makes that visible in logs immediately instead of
        // another guessing cycle like tonight's WiFi/Bluetooth scan diagnosis.
        val backgroundGranted = ContextCompat.checkSelfPermission(
            context,
            android.Manifest.permission.ACCESS_BACKGROUND_LOCATION,
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        Log.i(LOG_TAG, "currentLocation: ACCESS_BACKGROUND_LOCATION granted=$backgroundGranted")

        if (!QuickControls.isLocationEnabled(context)) {
            QuickControls.setLocationEnabled(dpm, admin, true)
        }

        val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager

        // getLastKnownLocation is purely passive - it only ever returns a location if something
        // else on the device already requested a fresh fix recently. Confirmed live: with
        // permission granted and Location on, every provider still returned null forever because
        // nothing had actively triggered GPS/network on this device. requestFreshFix() below
        // actively asks for one; both this and the passive fallback below are only reached on a
        // due/forced cycle (a `ring`/`locate` command, the throttle window elapsing, or no cache
        // yet at all) - confirmed live that doing either on every single sync visibly slowed it
        // down and showed the location-in-use indicator every time, which isn't needed for a trail
        // that's meant to update periodically, not continuously. Written synchronously (commit(),
        // not the generated setter's apply()) for the same reason as TrackedAppUpdateState.save -
        // a due cycle often runs right before checkForTrackedAppUpdates triggers a self-update,
        // which SIGKILLs this process, and an async write can lose the race against that kill.
        saveLastActiveFetchAt(context, System.currentTimeMillis())
        // Design 64 step 2, decision 14: a release build ignores a mock fix (`Location.isMock`)
        // and counts it - the fresh one and every last-known candidate; the debug build takes it.
        val fresh = requestFreshFix(context, lm)?.takeIf { ZoneWatch.accept(context, it) } ?: try {
            val providers = mutableListOf(LocationManager.NETWORK_PROVIDER, LocationManager.GPS_PROVIDER)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                providers.add(LocationManager.FUSED_PROVIDER)
            }
            providers
                .mapNotNull { provider ->
                    try {
                        lm.getLastKnownLocation(provider)
                    } catch (e: Exception) {
                        null
                    }
                }
                .filter { ZoneWatch.accept(context, it) }
                .maxByOrNull { it.time }
        } catch (e: Exception) {
            Log.w(LOG_TAG, "Failed to read cached location", e)
            null
        }

        if (fresh != null) {
            saveCachedFix(context, fresh)
        }
        return fresh ?: loadCachedFix()
    }

    private fun cachedFix(): CachedFix? {
        val raw = LauncherPreferences.mdm().cachedLocationJson() ?: return null
        return try {
            ServerJson.decodeFromString<CachedFix>(raw)
        } catch (e: Exception) {
            Log.w(LOG_TAG, "Failed to decode cached location fix", e)
            null
        }
    }

    /** The cached fix as a [Location] with its own provider (the old marker provider for a fix
     * cached before design 64 step 2) and mock flag. */
    private fun loadCachedFix(): Location? {
        val cached = cachedFix() ?: return null
        return Location(cached.provider ?: CACHED_LOCATION_PROVIDER).apply {
            latitude = cached.latitude
            longitude = cached.longitude
            cached.accuracy?.let { accuracy = it }
            time = cached.timeMs
            isMock = cached.mock
        }
    }

    /** When the cached fix was taken (its own time), `null` without one - for the passive feed
     * ("newer than the cached fix") and the heartbeat ("a kept fix younger than the interval"). */
    fun cachedFixTimeMs(): Long? = cachedFix()?.timeMs

    /** Design 64 step 2: a passive fix [com.kidslauncher.mdm.zones.passiveFixWorth] kept replaces
     * the cached fix - only when it is still newer (the sync's own fix may have landed meanwhile).
     * Never sent on its own: the next report's `CACHED` fix is it. */
    @Synchronized
    fun keepFix(context: Context, location: Location) {
        val cached = cachedFixTimeMs()
        if (cached != null && location.time <= cached) return
        saveCachedFix(context, location)
    }

    /** The provider a report names for [location] - its own, never the old cache marker. */
    fun providerOf(location: Location): String? = location.provider?.takeIf { it != CACHED_LOCATION_PROVIDER }

    // Both writes below commit() synchronously rather than going through the generated
    // apply()-based setters - see the call site's comment on why an async write here can lose the
    // race against a same-cycle self-update's SIGKILL.
    @Synchronized
    private fun saveCachedFix(context: Context, location: Location) {
        val cached = CachedFix(
            latitude = location.latitude,
            longitude = location.longitude,
            accuracy = if (location.hasAccuracy()) location.accuracy else null,
            timeMs = location.time,
            provider = providerOf(location),
            mock = location.isMock,
        )
        val key = LauncherPreferences.mdm().keys().cachedLocationJson()
        PreferenceManager.getDefaultSharedPreferences(context)
            .edit()
            .putString(key, ServerJson.encodeToString(cached))
            .commit()
    }

    private fun saveLastActiveFetchAt(context: Context, timeMs: Long) {
        val key = LauncherPreferences.mdm().keys().lastActiveLocationFetchAtMs()
        PreferenceManager.getDefaultSharedPreferences(context)
            .edit()
            .putLong(key, timeMs)
            .commit()
    }

    /**
     * Actively requests a fresh fix via the modern one-shot [LocationManager.getCurrentLocation]
     * API (no Google Play Services needed, unlike `FusedLocationProviderClient`), bridged into a
     * suspend call since [MdmSyncWorker] already runs in a coroutine. Bounded by
     * [FRESH_LOCATION_TIMEOUT_MS] so a GPS fix that can't be acquired (e.g. deep indoors) can never
     * stall a sync cycle - `getCurrentLocation` is documented to call back with `null` on its own
     * internal timeout, but this is a second, app-level bound just in case that's ever longer.
     */
    private suspend fun requestFreshFix(context: Context, lm: LocationManager): Location? {
        val provider = when {
            lm.isProviderEnabled(LocationManager.GPS_PROVIDER) -> LocationManager.GPS_PROVIDER
            lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER) -> LocationManager.NETWORK_PROVIDER
            else -> return null
        }
        return withTimeoutOrNull(FRESH_LOCATION_TIMEOUT_MS) {
            suspendCancellableCoroutine { cont ->
                val cancellationSignal = CancellationSignal()
                cont.invokeOnCancellation { cancellationSignal.cancel() }
                try {
                    lm.getCurrentLocation(provider, cancellationSignal, context.mainExecutor) { location ->
                        if (cont.isActive) cont.resume(location)
                    }
                } catch (e: Exception) {
                    Log.w(LOG_TAG, "Failed to request fresh location fix", e)
                    if (cont.isActive) cont.resume(null)
                }
            }
        }
    }

    @Volatile
    private var ringPlayer: MediaPlayer? = null

    /** The volumes the first ring of a run saved ([ringVolumesToRestore]); cleared on restore. */
    private val restoredStreams = mutableMapOf<Int, Int>()
    private val ringHandler = Handler(Looper.getMainLooper())
    private var ringTimeoutRunnable: Runnable? = null

    /**
     * Raises the alarm stream to its maximum and loops the device's own configured alarm sound for
     * [RING_DURATION_MS] (or until [stopRingAndRestore] is called first - via the notification's
     * "Stop Ringing" action, or a `stop_ring` command from the server). The alarm stream plays
     * through silent and Do Not Disturb by design, the same reason a real alarm clock still rings
     * then. Never the ringer mode or the ringer-affected streams (RING/NOTIFICATION): as the device
     * owner a ringer-mode change works and ends any DND or bedtime mode, restoring Silent turns on
     * a manual DND, and those streams set the ringer mode themselves - restoring 0 left the phone
     * on Vibrate (design 18, QA #2; [RING_RAISED_STREAMS]). A second `ring` while one plays keeps
     * the first one's saved volumes. Uses whatever alarm sound the phone already has configured
     * rather than bundling a new audio asset.
     */
    fun ring(context: Context) {
        try {
            // A second ring starts its own full duration.
            ringTimeoutRunnable?.let { ringHandler.removeCallbacks(it) }
            ringTimeoutRunnable = null
            stopRingPlayback()
            val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

            val current = RING_RAISED_STREAMS.associateWith { audioManager.getStreamVolume(it) }
            val toRestore = ringVolumesToRestore(restoredStreams.toMap(), current)
            restoredStreams.clear()
            restoredStreams.putAll(toRestore)
            RING_RAISED_STREAMS.forEach { stream ->
                try {
                    audioManager.setStreamVolume(stream, audioManager.getStreamMaxVolume(stream), 0)
                } catch (e: Exception) {
                    Log.w(LOG_TAG, "Failed to raise stream $stream", e)
                }
            }

            val alarmUri = RingtoneManager.getActualDefaultRingtoneUri(context, RingtoneManager.TYPE_ALARM)
                ?: RingtoneManager.getValidRingtoneUri(context)

            val player = MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build(),
                )
                setDataSource(context, alarmUri)
                isLooping = true
                prepare()
                start()
            }
            ringPlayer = player

            showStopRingNotification(context)

            val timeout = Runnable { stopRingAndRestore(context) }
            ringTimeoutRunnable = timeout
            ringHandler.postDelayed(timeout, RING_DURATION_MS)
        } catch (e: Exception) {
            Log.w(LOG_TAG, "Failed to ring device", e)
        }
    }

    /**
     * Stops playback, restores the volumes [ring] raised, and cancels the
     * notification - called either by [RING_DURATION_MS] naturally elapsing, the notification's
     * "Stop Ringing" action ([StopRingReceiver]), or a `stop_ring` command from the server
     * ([MdmSyncWorker]'s dispatch). Safe to call more than once (e.g. both the timeout and a manual
     * stop racing) - each step is independently guarded.
     */
    fun stopRingAndRestore(context: Context) {
        ringTimeoutRunnable?.let { ringHandler.removeCallbacks(it) }
        ringTimeoutRunnable = null
        stopRingPlayback()

        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        restoredStreams.forEach { (stream, volume) ->
            try {
                audioManager.setStreamVolume(stream, volume, 0)
            } catch (e: Exception) {
                // Best-effort restore - harmless if it fails.
            }
        }
        restoredStreams.clear()

        cancelStopRingNotification(context)
    }

    private fun stopRingPlayback() {
        try {
            ringPlayer?.stop()
            ringPlayer?.release()
        } catch (e: Exception) {
            // Already stopped/released - harmless.
        } finally {
            ringPlayer = null
        }
    }

    /**
     * A HIGH-importance notification (its own channel, see [NOTIFICATION_CHANNEL_RING]) with a
     * "Stop Ringing" action - the whole reason this exists is to give the kid an obvious,
     * immediate way to silence the alarm once they unlock the device, rather than it just blaring
     * with no visible control. `setOngoing(true)` so it can't be swiped away by accident while the
     * ring is still active - it's cancelled properly by [cancelStopRingNotification] once the ring
     * actually stops, whether via timeout, the notification's own button, or a server command.
     */
    private fun showStopRingNotification(context: Context) {
        val stopIntent = Intent(context, StopRingReceiver::class.java)
        val stopPendingIntent = PendingIntent.getBroadcast(
            context,
            0,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val notification = NotificationCompat.Builder(context, NOTIFICATION_CHANNEL_RING)
            .setSmallIcon(R.drawable.baseline_settings_24)
            .setContentTitle(context.getString(R.string.notification_ring_title))
            .setContentText(context.getString(R.string.notification_ring_text))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOngoing(true)
            .setAutoCancel(false)
            .addAction(
                R.drawable.baseline_close_24,
                context.getString(R.string.notification_ring_stop_action),
                stopPendingIntent,
            )
            .build()

        try {
            NotificationManagerCompat.from(context).notify(RING_NOTIFICATION_ID, notification)
        } catch (e: SecurityException) {
            Log.w(LOG_TAG, "Failed to show stop-ring notification (missing POST_NOTIFICATIONS?)", e)
        }
    }

    private fun cancelStopRingNotification(context: Context) {
        try {
            NotificationManagerCompat.from(context).cancel(RING_NOTIFICATION_ID)
        } catch (e: Exception) {
            // Harmless - notification may already be gone.
        }
    }

    fun lock(dpm: DevicePolicyManager, admin: ComponentName): Boolean {
        return try {
            dpm.lockNow()
            true
        } catch (e: Exception) {
            Log.w(LOG_TAG, "Failed to lock device", e)
            false
        }
    }

    /** [remoteWipeReady] for this phone; `null` when we aren't device owner or it can't be read. */
    fun remoteWipeReady(context: Context): Boolean? = try {
        val dpm = context.getSystemService(DevicePolicyManager::class.java)
        if (dpm == null || !dpm.isDeviceOwnerApp(context.packageName)) {
            null
        } else {
            val admin = ComponentName(context, MdmDeviceAdminReceiver::class.java)
            remoteWipeReady(Build.VERSION.SDK_INT, dpm.hasGrantedPolicy(admin, DeviceAdminInfo.USES_POLICY_WIPE_DATA))
        }
    } catch (e: Exception) {
        Log.w(LOG_TAG, "Couldn't read the wipe policy", e)
        null
    }

    /**
     * Factory-resets the phone (security review M8: `wipeDevice` on API 34+, [wipeCall]). Irreversible;
     * a reset that starts never returns - the phone reboots into it. A call that returns or throws
     * didn't wipe (but a dying system server, [WipeOutcome.GOING_DOWN]), and the result says so, so
     * the parent never believes a lost phone is erased when it isn't ([wipeOutcome], QA Low 3).
     */
    fun wipe(dpm: DevicePolicyManager): Pair<WipeOutcome, String> = try {
        when (wipeCall(Build.VERSION.SDK_INT)) {
            WipeCall.WIPE_DEVICE -> dpm.wipeDevice(WIPE_FLAGS)
            WipeCall.WIPE_DATA -> dpm.wipeData(WIPE_FLAGS)
        }
        WipeOutcome.RETURNED to wipeResultMessage(WipeOutcome.RETURNED)
    } catch (e: Exception) {
        Log.e(LOG_TAG, "Failed to wipe device", e)
        val outcome = wipeOutcome(e.javaClass.simpleName, e.cause?.javaClass?.simpleName)
        outcome to wipeResultMessage(outcome, "${e.javaClass.simpleName}: ${e.message}")
    }
}
