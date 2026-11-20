package com.kidslauncher.mdm.server

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Debug build only (src/debug, design 122): the emulator has no headset to pair, so this drives the Bluetooth pairing
 * window through its own steps - `step` `arm` (the page's tap without `createBond`), `request` (the pairing request
 * with `variant`), `bond` (`state` `bonded` or `none`) - for `address` (a fake one is fine). The window's rules, its pin
 * and its close run as for a real request; Android's ordered delivery ahead of Settings is not simulated. Exported for
 * `adb shell am broadcast -n ...` but guarded by DUMP (shell has it, apps don't). Logcat `BtPairingDebug`.
 */
class BtPairingDebugReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext
        val step = intent.getStringExtra("step")
        val address = intent.getStringExtra("address") ?: "02:00:00:00:12:22"
        val pending = goAsync()
        Thread {
            try {
                val result = when (step) {
                    "arm" -> {
                        BtPairingWindow.arm(app, address)
                        "armed"
                    }
                    "request" -> BtPairingWindow.request(app, address, intent.getIntExtra("variant", BT_VARIANT_CONSENT)).name
                    "bond" -> {
                        val event = if (intent.getStringExtra("state") == "bonded") BtPairingEvent.BONDED else BtPairingEvent.BOND_NONE
                        BtPairingWindow.event(app, event, address)
                        event.name
                    }
                    else -> "unknown step"
                }
                Log.i("BtPairingDebug", "$step -> $result")
                pending.resultData = result
            } finally {
                pending.finish()
            }
        }.start()
    }
}
