package me.vibb.music.spotify

import android.content.Context
import android.net.wifi.WifiManager
import android.os.Handler
import android.os.Looper
import android.os.PowerManager

/*
 * The CPU and Wi-Fi while Spotify plays on the phone (design 26c §5.7, beside SonosLocks). The web
 * player's audio is the WebView's, not ExoPlayer's, so nothing holds Media3's WAKE_MODE_NETWORK for it:
 * while a start runs or the renderer shows playing, a PARTIAL wake lock (never a screen one: the
 * auto-lock must still end a player left on, SourceScanTest) and a Wi-Fi lock are held, each for at most
 * [TIMEOUT_MS] and renewed by the service's 15 s save tick ([renew], through the renderer's saveNow); a
 * missed release can't keep the phone awake longer than that. Released on every pause, end, error,
 * "elsewhere", detach and destroy. The Wi-Fi lock is WIFI_MODE_FULL_LOW_LATENCY as SonosLocks' (HIGH_PERF,
 * which §5.7 names after Media3's WifiLockManager, is deprecated and a no-op since API 34 - the app's
 * minSdk). The spike's no-lock pass was the emulator; the Jelly Star's run decides whether the wake lock
 * stays (§14 item 6). Main thread.
 */
class SpotifyLocks(context: Context, private val log: (String) -> Unit) {
    private val app = context.applicationContext
    private val wake: PowerManager.WakeLock? = runCatching {
        app.getSystemService(PowerManager::class.java)?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "vibb:spotify")?.apply { setReferenceCounted(false) }
    }.getOrNull()
    private val wifi: WifiManager.WifiLock? = runCatching {
        app.getSystemService(WifiManager::class.java)?.createWifiLock(WifiManager.WIFI_MODE_FULL_LOW_LATENCY, "vibb:spotify")?.apply { setReferenceCounted(false) }
    }.getOrNull()
    private val handler = Handler(Looper.getMainLooper())
    private var held = false

    /** The Wi-Fi lock has no timeout of its own: this releases both when nothing renewed them. */
    private val expire = Runnable {
        if (held) {
            log("spotify: the locks weren't renewed for ${TIMEOUT_MS / 60_000} min - released")
            hold(false)
        }
    }

    /** Hold both locks ([want]) or let them go; holding again renews them. */
    fun hold(want: Boolean) {
        if (want) {
            if (!held) log("spotify: wake and Wi-Fi locks held")
            held = true
            acquire()
            return
        }
        if (!held) return
        held = false
        handler.removeCallbacks(expire)
        try {
            if (wake?.isHeld == true) wake.release()
            if (wifi?.isHeld == true) wifi.release()
        } catch (e: RuntimeException) {
            log("spotify: a lock not released (${e.javaClass.simpleName})")
        }
        log("spotify: wake and Wi-Fi locks released")
    }

    /** The 15 s save tick: the timeouts start again while held. */
    fun renew() {
        if (held) acquire()
    }

    private fun acquire() {
        try {
            wake?.acquire(TIMEOUT_MS)
            if (wifi?.isHeld == false) wifi.acquire()
        } catch (e: RuntimeException) {
            log("spotify: a lock not taken (${e.javaClass.simpleName})")
        }
        handler.removeCallbacks(expire)
        handler.postDelayed(expire, TIMEOUT_MS)
    }

    private companion object {
        /** §5.7: each lock at most 30 minutes without a renewal. */
        const val TIMEOUT_MS = 30 * 60_000L
    }
}
