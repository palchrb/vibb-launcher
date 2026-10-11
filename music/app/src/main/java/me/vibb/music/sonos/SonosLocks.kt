package me.vibb.music.sonos

import android.content.Context
import android.net.wifi.WifiManager
import android.os.Handler
import android.os.Looper
import android.os.PowerManager

/*
 * The CPU while a Sonos room plays for us (design 26a §6.3, QA H1). A foreground service keeps
 * network access in Doze but not the CPU awake, and a SimpleBasePlayer holds no wake lock
 * (ExoPlayer's WAKE_MODE_NETWORK is the phone's own); the poller - and with it every bookmark -
 * would stop when the CPU sleeps. So a PARTIAL wake lock (never a screen one: the auto-lock must
 * still end a player left on, SourceScanTest) is held while [SonosController.armedPlaying] says so;
 * it is what keeps the polls running in Doze, with the FGS's network access. The Wi-Fi lock beside
 * it is harmless but does little (qa-26a-step3b-code L5): WIFI_MODE_FULL_LOW_LATENCY is effective
 * only in the foreground with the screen on, and HIGH_PERF is a no-op since API 34. Main thread.
 *
 * Per owner (a renderer; qa L4): one room's release never drops another's hold. The wake lock is
 * taken for [TIMEOUT_MS] and renewed every [RENEW_MS] only when an owner re-asserted its hold since
 * the last renewal (the controller does on every refresh, at least every poll; qa L3): a missed
 * release can't keep the CPU awake longer than that.
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
    private val owners = mutableSetOf<Any>()
    private var held = false

    /** An owner said "hold" since the last renewal. */
    private var seen = false

    private val renew = object : Runnable {
        override fun run() {
            if (!held) return
            if (!seen) {
                SonosHome.log("sonos: nobody re-asserted the wake lock for ${RENEW_MS / 60_000} min - released")
                owners.clear()
                apply(false)
                return
            }
            seen = false
            acquireWake()
            handler.postDelayed(this, RENEW_MS)
        }
    }

    /** [owner] wants the locks ([want]: re-asserted on every refresh while true) or lets them go. */
    fun hold(owner: Any, want: Boolean) {
        if (want) {
            seen = true
            if (!owners.add(owner)) return
        } else if (!owners.remove(owner)) {
            return
        }
        apply(owners.isNotEmpty())
    }

    /** The service is going: every hold ends. */
    fun releaseAll() {
        owners.clear()
        apply(false)
    }

    private fun apply(want: Boolean) {
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
