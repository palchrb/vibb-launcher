package com.kidslauncher.mdm.sms

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.kidslauncher.mdm.calls.MmscRule

/**
 * Debug build only (src/debug, design 77 step 3b; `checkReleaseHasNoDebugHook`, `DebugHookAbsentTest`): the emulator
 * can't receive an MMS push (its console's `sms pdu` delivers nothing on the AVD) and reaches no MMS server its SIM's
 * MMSC list names. So:
 * - `--es push_hex <m-notification-ind as hex>` commits that push exactly as `MmsPushReceiver` does (D7) - from there
 *   the real path runs: the isolated parse, D10's gate, the download;
 * - `--es mmsc_host <host or host:port> --es mccmnc <MCC/MNC>` puts a fake MMS server on this process's MMSC list,
 *   in that SIM's own row (design 83 §7; memory only, until the process dies) - `docs/testing/emulator.md` §6v's
 *   fake MMSC. Without it, the address is the parent's to allow on Device › Calls;
 * - `--es stand_in <the fake MMSC's send URL>` (or `off`) plays the platform's MMS service, which the AVD's carrier
 *   config switches off ([MmsDebug]).
 * Exported for `adb shell am broadcast -n ...`, guarded by DUMP (shell has it, apps don't).
 */
class MmsDebugReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext
        intent.getStringExtra("mmsc_host")?.let { host ->
            val mccmnc = intent.getStringExtra("mccmnc").orEmpty()
            MmsDebug.hosts = listOf(MmscRule(mccmnc, listOf(host)))
            Log.i(TAG, "Fake MMSC for $mccmnc: $host")
            resultData = "mmsc $mccmnc $host"
        }
        intent.getStringExtra("stand_in")?.let { url ->
            // "off" ends it; anything else is the fake MMSC's send URL.
            MmsDebug.standIn = url.takeUnless { it == "off" }
            Log.i(TAG, "Stand-in for the platform's MMS service: ${MmsDebug.standIn}")
            resultData = "stand_in ${MmsDebug.standIn}"
        }
        intent.getStringExtra("push_hex")?.let { hex ->
            val pdu = hex.trim().chunked(2).map { it.toInt(16).toByte() }.toByteArray()
            val pending = goAsync()
            SmsRuntime.receiveExecutor.execute {
                try {
                    val kept = MmsPushReceiver.commitPush(app, pdu, null)
                    Log.i(TAG, "Push of ${pdu.size} bytes kept: $kept")
                    if (kept) com.kidslauncher.mdm.sms.mms.MmsRuntime.kick(app)
                } finally {
                    pending.finish()
                }
            }
        }
    }

    private companion object {
        const val TAG = "MmsDebug"
    }
}
