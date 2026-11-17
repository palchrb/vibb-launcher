package com.kidslauncher.mdm.push.up

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Debug build only (src/debug, design 108b §11 L11; `checkReleaseHasNoDebugHook`, `DebugHookAbsentTest`): the emulator gate
 * ages a registration without moving the system clock (the Device Owner's date lock, TLS and the time rules stay as they
 * are). `adb shell am broadcast -n me.vibb.launcher.debug/com.kidslauncher.mdm.push.up.UpDebugReceiver --es reg <reg>
 * --ei days <N> [--es field lastAppAckMs,confirmedAtMs,...]` moves those stamps of that row N days back in the store
 * (a missing `lastAppAckMs` reads as now first; `pingSentMs` moves `pingLastSendMs` with it; default: every stamp). The
 * next sync or unlock acts on them; the engine's in-memory ack limit of that token is forgotten too (L5). Exported for the shell, guarded by DUMP (shell has it, apps don't).
 */
class UpDebugReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val reg = intent.getStringExtra("reg") ?: return
        val days = intent.getIntExtra("days", 0)
        val fields = intent.getStringExtra("field")?.split(',')?.map { it.trim() }?.toSet() ?: ALL
        val back = days * UpRules.DAY_MS
        UpRuntime.withStore(context) { store ->
            val rows = (store.read() as? UpRead.Rows)?.rows ?: return@withStore
            val now = System.currentTimeMillis()
            fun moved(name: String, value: Long?): Long? = if (name in fields) value?.minus(back) else value
            store.write(
                rows.map { row ->
                    if (row.reg != reg) return@map row
                    row.copy(
                        lastAppAckMs = if ("lastAppAckMs" in fields) (row.lastAppAckMs ?: now) - back else row.lastAppAckMs,
                        confirmedAtMs = moved("confirmedAtMs", row.confirmedAtMs),
                        createdAtMs = if ("confirmedAtMs" in fields) row.createdAtMs - back else row.createdAtMs,
                        pingSentMs = moved("pingSentMs", row.pingSentMs),
                        pingLastSendMs = moved("pingSentMs", row.pingLastSendMs),
                        refreshMs = moved("refreshMs", row.refreshMs),
                        keyTryMs = moved("keyTryMs", row.keyTryMs),
                    )
                },
            )
            UpRuntime.forgetAcks(rows.firstOrNull { it.reg == reg }?.token)
            Log.i(TAG, "Backdated ${reg.take(8)} by $days days: ${fields.joinToString(",")}")
        }
        resultData = "backdate ${reg.take(8)} $days"
    }

    private companion object {
        const val TAG = "UpDebug"
        val ALL = setOf("lastAppAckMs", "confirmedAtMs", "pingSentMs", "refreshMs", "keyTryMs")
    }
}
