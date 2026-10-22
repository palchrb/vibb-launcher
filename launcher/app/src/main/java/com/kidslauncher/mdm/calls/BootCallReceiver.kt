package com.kidslauncher.mdm.calls

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * `LOCKED_BOOT_COMPLETED`: the phone has booted but nobody has unlocked it yet (task 15). Only
 * gets the process up with the device-protected call policy loaded, so the screening service
 * answers from memory on the first call. Starts nothing else - Application.onCreate leaves the
 * rest of the app (Home, kiosk, enforcement, services, tsnet) alone until the user unlocks; see
 * the BFU lockout incidents in CLAUDE.md. The one exception (design 59 F3): with Android's lock as
 * the gate it starts the boot cover - direct-boot-aware, its own process, no lock task, never over
 * the keyguard - so the PIN's dismissal shows it instead of the stock launcher
 * ([com.kidslauncher.mdm.lock.BootCover.atLockedBoot], device-protected storage only).
 */
class BootCallReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_LOCKED_BOOT_COMPLETED) return
        CallPolicyStore.ensureLoaded(context)
        Log.i("BootCallReceiver", "Locked boot: call policy ${CallPolicyStore.describe()}")
        // After the call policy, so a fault here never delays screening. Never throws.
        com.kidslauncher.mdm.lock.BootCover.atLockedBoot(context)
    }
}
