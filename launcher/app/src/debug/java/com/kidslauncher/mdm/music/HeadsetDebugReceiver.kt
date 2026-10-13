package com.kidslauncher.mdm.music

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.media.AudioDeviceInfo
import android.os.SystemClock
import android.util.Log
import java.util.concurrent.atomic.AtomicInteger

/**
 * Debug build only (src/debug, design 28 §4): the emulator has no headset, so this connects a fake
 * Bluetooth A2DP sink to the listening window's headset watch - through the same step, boot grace
 * and rejoin rule as a real one. Each broadcast is its own device (QA L3): its own id, counting down
 * from -1000, and its own address `debug-<n>`, so a second broadcast in the same process is fresh
 * too. Reports the headset stamp afterwards (logcat `HeadsetDebug`, and the broadcast's result
 * data). Exported for `adb shell am broadcast -n ...` but guarded by DUMP (shell has it, apps don't).
 */
class HeadsetDebugReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val n = devices.incrementAndGet()
        val out = AudioOut(id = -999 - n, type = AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, sink = true, address = "debug-$n")
        MusicListening.devicesAdded(listOf(out))
        val summary = "added $out at uptime ${SystemClock.elapsedRealtime() / 1000} s -> " +
            "headsetAt=${MusicListening.listening().headsetAt}, boot ${MusicListening.bootCount()}"
        Log.i("HeadsetDebug", summary)
        resultData = summary
    }

    companion object {
        private val devices = AtomicInteger(0)
    }
}
