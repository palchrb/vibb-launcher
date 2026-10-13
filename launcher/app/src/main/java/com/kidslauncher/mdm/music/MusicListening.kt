package com.kidslauncher.mdm.music

import android.content.Context
import android.content.SharedPreferences
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import com.kidslauncher.mdm.Application
import com.kidslauncher.mdm.server.BootClock

private const val LOG_TAG = "MusicListening"

private const val PLAYED_MS = "listen_played_ms"
private const val PLAYED_BOOT = "listen_played_boot"
private const val HEADSET_MS = "listen_headset_ms"
private const val HEADSET_BOOT = "listen_headset_boot"

/**
 * The listening window's stamps (design 28 §2, pure rules in MusicWindow.kt): the end of play, fed
 * by the bridge ([MusicNowPlaying.store] and `playerGone` call [observePlay]), and a fresh headset,
 * from the audio device callback ([init]). Both are `elapsedRealtime` + `BOOT_COUNT`, persisted in
 * [MusicStore.prefs] (CE), so a launcher restart inside a window keeps it and a reboot never does.
 *
 * The stamps load lazily under this object's lock on first use (QA L4): the bridge works even when
 * [init] never ran. Every writer runs after the first unlock; a read before it (CE storage locked)
 * sees nothing and loads later. The boot count is read once per process (QA L8). Listeners are
 * told on the main thread, only when a window may open: an end of play or a fresh headset - never
 * a playing stamp (every 15 s).
 */
object MusicListening {
    private val main = Handler(Looper.getMainLooper())
    /** Home's card and the lock's tile while shown (main thread). */
    private val listeners = mutableListOf<() -> Unit>()

    private var app: Context? = null
    private var loaded = false
    private var boot = -1
    private var played: BootStamp? = null
    private var headset: BootStamp? = null
    private var watch = HeadsetWatch(emptySet(), emptyMap())

    /**
     * From [MusicMedia.init] (process start, main thread), on its own: the devices connected now are
     * the baseline - read **before** registering, since AOSP calls `onAudioDevicesAdded` once at
     * registration with every current device -, then the callback.
     */
    fun init(context: Context) {
        try {
            val ctx = context.applicationContext
            synchronized(this) { app = ctx }
            val am = ctx.getSystemService(AudioManager::class.java) ?: return
            val baseline = am.getDevices(AudioManager.GET_DEVICES_OUTPUTS).map { it.id }
            synchronized(this) { watch = watch.copy(known = watch.known + baseline) }
            am.registerAudioDeviceCallback(
                object : AudioDeviceCallback() {
                    override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>?) {
                        devicesAdded(addedDevices.orEmpty().map(::audioOut))
                    }

                    override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>?) {
                        devicesRemoved(removedDevices.orEmpty().map(::audioOut))
                    }
                },
                main,
            )
        } catch (e: Exception) {
            Log.e(LOG_TAG, "Headset watch setup failed", e)
        }
    }

    private fun audioOut(device: AudioDeviceInfo) = AudioOut(device.id, device.type, device.isSink, device.address.orEmpty())

    /** The stamps now; none before they could be read (fails closed: only playing shows). */
    @Synchronized
    fun listening(): Listening = if (load(null)) Listening(played, headset) else Listening(null, null)

    /** `Settings.Global.BOOT_COUNT`, read once per process (-1 while unknown: every stamp counts as nothing). */
    @Synchronized
    fun bootCount(): Int {
        if (boot < 0) boot = BootClock.bootCount()
        return boot
    }

    /** A bridge observation (any thread): the end-of-play stamp (§2.1), persisted on a change, listeners on an end of play. */
    fun observePlay(context: Context, wasPlaying: Boolean, playing: Boolean) {
        val notify = synchronized(this) {
            if (!load(context)) return
            val previous = played
            val next = playedStampAfter(previous, wasPlaying, playing, now())
            if (next != previous) {
                played = next
                persist(PLAYED_MS, PLAYED_BOOT, next)
            }
            playStampNotifies(previous, next, playing)
        }
        if (notify) changed()
    }

    /** Devices that came (the callback, main thread): a fresh headset stamps, persists and tells the listeners. */
    fun devicesAdded(outs: List<AudioOut>) {
        val fresh = synchronized(this) {
            val now = now()
            val (next, connected) = headsetAdded(watch, outs, now.elapsedMs)
            watch = next
            if (connected && load(null)) {
                headset = now
                persist(HEADSET_MS, HEADSET_BOOT, now)
                Log.i(LOG_TAG, "Fresh headset (type ${outs.filter(::countsAsHeadset).map { it.type }})")
                true
            } else {
                false
            }
        }
        if (fresh) changed()
    }

    /** Devices that went: their ids leave the watch, and each headset's time is kept for the rejoin rule. */
    @Synchronized
    fun devicesRemoved(outs: List<AudioOut>) {
        watch = headsetRemoved(watch, outs, SystemClock.elapsedRealtime())
    }

    fun addListener(listener: () -> Unit) {
        listeners += listener
    }

    fun removeListener(listener: () -> Unit) {
        listeners -= listener
    }

    private fun changed() = main.post { listeners.toList().forEach { it() } }

    private fun now() = BootStamp(SystemClock.elapsedRealtime(), bootCount())

    /** The persisted stamps, once (under the lock); false while there is no context or CE storage can't be read yet. */
    private fun load(context: Context?): Boolean {
        if (loaded) return true
        val ctx = context?.applicationContext ?: app ?: Application.instance ?: return false
        app = ctx
        return try {
            val prefs = MusicStore.prefs(ctx)
            played = readStamp(prefs, PLAYED_MS, PLAYED_BOOT)
            headset = readStamp(prefs, HEADSET_MS, HEADSET_BOOT)
            bootCount()
            loaded = true
            true
        } catch (e: IllegalStateException) {
            false // CE storage before the first unlock
        }
    }

    /** A stamp, or null when either key is missing or of another type (counts as nothing). */
    private fun readStamp(prefs: SharedPreferences, msKey: String, bootKey: String): BootStamp? = try {
        if (prefs.contains(msKey) && prefs.contains(bootKey)) BootStamp(prefs.getLong(msKey, 0), prefs.getInt(bootKey, -1)) else null
    } catch (e: ClassCastException) {
        null
    }

    private fun persist(msKey: String, bootKey: String, stamp: BootStamp?) {
        val ctx = app ?: return
        try {
            MusicStore.prefs(ctx).edit().apply {
                if (stamp == null) {
                    remove(msKey); remove(bootKey)
                } else {
                    putLong(msKey, stamp.elapsedMs); putInt(bootKey, stamp.bootCount)
                }
            }.apply()
        } catch (e: Exception) {
            Log.w(LOG_TAG, "Couldn't keep the listening stamp", e)
        }
    }
}
