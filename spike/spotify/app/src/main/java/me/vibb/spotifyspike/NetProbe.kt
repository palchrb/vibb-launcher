// Vibb Spotify engine spike (docs/testing/spotify-spike.md).
// Copyright (C) 2026 palchrb. SPDX-License-Identifier: GPL-3.0-or-later

package me.vibb.spotifyspike

import android.content.Context
import android.net.TrafficStats
import android.os.Handler
import android.os.Looper
import android.os.Process
import java.io.File
import java.util.Locale
import java.util.concurrent.Executors

/**
 * Item 10b: does the Web Playback player cache audio? Once a minute while the player is loaded:
 * this app's network bytes (TrafficStats for our uid - the WebView's network stack runs in our
 * process; the Spotify app's traffic is its own uid's), the delta since the last line, an MB/hour
 * estimate while the player plays, and the WebView's disk cache, walked off the main thread.
 * Main thread only, apart from the walk.
 */
object NetProbe {
    private const val PERIOD_MS = 60_000L
    private const val MB = 1024.0 * 1024.0
    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()
    private var appContext: Context? = null
    private var lastRx = -1L
    private var lastTx = -1L
    private var lastAt = 0L
    private var running = false

    @Volatile
    private var cacheText = "webview cache: -"

    /** The latest totals, for the probe screen. */
    var line: String = "net: -"
        private set

    /** Set from the page's player_state_changed: the MB/hour estimate is only for playing minutes. */
    @Volatile
    var playing = false

    private val tick = object : Runnable {
        override fun run() {
            sample(log = true)
            main.postDelayed(this, PERIOD_MS)
        }
    }

    fun start(context: Context) {
        appContext = context.applicationContext
        if (running) return
        running = true
        lastRx = -1
        sample(log = true)
        main.postDelayed(tick, PERIOD_MS)
    }

    fun stop() {
        if (!running) return
        running = false
        main.removeCallbacks(tick)
        sample(log = true)
    }

    private fun mb(bytes: Long) = String.format(Locale.ROOT, "%.2f", bytes / MB)

    private fun sample(log: Boolean) {
        val uid = Process.myUid()
        val rx = TrafficStats.getUidRxBytes(uid)
        val tx = TrafficStats.getUidTxBytes(uid)
        val now = System.currentTimeMillis()
        if (rx == TrafficStats.UNSUPPORTED.toLong() || tx == TrafficStats.UNSUPPORTED.toLong()) {
            line = "net: TrafficStats unsupported for this uid"
            if (log) SpikeLog.log(line)
            return
        }
        val delta = if (lastRx < 0) "" else {
            val dRx = rx - lastRx
            val dTx = tx - lastTx
            val minutes = (now - lastAt) / 60_000.0
            val rate = if (playing && minutes > 0.5) {
                ", playing: ~${String.format(Locale.ROOT, "%.0f", dRx / MB / minutes * 60)} MB/hour"
            } else {
                if (playing) "" else ", not playing"
            }
            " (delta +${mb(dRx)}/+${mb(dTx)} in ${String.format(Locale.ROOT, "%.1f", minutes)} min$rate)"
        }
        lastRx = rx
        lastTx = tx
        lastAt = now
        line = "net: rx=${mb(rx)} MB tx=${mb(tx)} MB$delta\n$cacheText"
        if (log) SpikeLog.log("net: rx=${mb(rx)} tx=${mb(tx)} MB$delta")
        walkCache()
    }

    private fun walkCache() {
        val context = appContext ?: return
        io.execute {
            // WebView keeps its HTTP cache under the cache dir in current versions, and under
            // app_webview in older ones; both are measured.
            val dirs = listOf(
                "cache/WebView" to File(context.cacheDir, "WebView"),
                "app_webview/Default/Cache" to File(context.dataDir, "app_webview/Default/Cache"),
                "app_webview" to File(context.dataDir, "app_webview"),
            )
            val parts = dirs.map { (name, dir) -> "$name ${mb(size(dir))} MB" }
            val text = "webview cache: ${parts.joinToString(", ")}"
            cacheText = text
            SpikeLog.log(text)
        }
    }

    private fun size(dir: File): Long =
        if (!dir.exists()) 0 else dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
}
