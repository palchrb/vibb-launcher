package me.vibb.music.sonos

import android.content.Context
import android.net.wifi.WifiManager
import android.os.Handler
import android.os.Looper
import android.os.PowerManager

/*
 * The CPU and Wi-Fi while a Sonos room plays for us (design 26a §6.3, QA H1). A foreground service
 * keeps network access in Doze but not the CPU awake, and a SimpleBasePlayer holds no wake lock
 * (ExoPlayer's WAKE_MODE_NETWORK is the phone's own); the poller - and with it every bookmark -
 * would stop when the CPU sleeps. So a PARTIAL wake lock (never a screen one: the auto-lock must
 * still end a player left on, SourceScanTest) and a low-latency Wi-Fi lock are held while
 * [SonosController.armedPlaying] says so. Main thread.
 */
class SonosLocks(context: Context) {
    private val app = context.applicationContext
    private val wake: PowerManager.WakeLock? = runCatching {
        app.getSystemService(PowerManager::class.java)?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "vibb:sonos")?.apply { setReferenceCounted(false) }
    }.getOrNull()
    private val wifi: WifiManager.WifiLock? = runCatching {
        app.getSystemService(WifiManager::class.java)?.createWifiLock(WifiManager.WIFI_MODE_FULL_LOW_LATENCY, "vibb:sonos")?.apply { setReferenceCounted(false) }
    }.getOrNull()
    private val handler = Handler(Looper.getMainLooper())
    private var held = false

    /** The wake lock is taken for [TIMEOUT_MS] and renewed every [RENEW_MS] while held: a missed release can't outlive it. */
    private val renew = object : Runnable {
        override fun run() {
            if (!held) return
            acquireWake()
            handler.postDelayed(this, RENEW_MS)
        }
    }

    fun hold(want: Boolean) {
        if (want == held) return
        held = want
        handler.removeCallbacks(renew)
        try {
            if (want) {
                acquireWake()
                wifi?.acquire()
                handler.postDelayed(renew, RENEW_MS)
            } else {
                if (wake?.isHeld == true) wake.release()
                if (wifi?.isHeld == true) wifi.release()
            }
        } catch (e: RuntimeException) {
            SonosHome.log("sonos: wake/Wi-Fi lock ${if (want) "not taken" else "not released"} (${e.javaClass.simpleName})")
        }
        SonosHome.log("sonos: wake and Wi-Fi locks ${if (want) "held" else "released"}")
    }

    private fun acquireWake() {
        wake?.acquire(TIMEOUT_MS)
    }

    private companion object {
        const val TIMEOUT_MS = 10 * 60_000L
        const val RENEW_MS = 5 * 60_000L
    }
}
