package com.kidslauncher.mdm.server

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import java.security.KeyStore

/**
 * Debug build only (src/debug, design 24b §7): deletes the parent PIN vault's Keystore key, so the
 * device check sees "can't be checked until the phone reaches the server" while the kid's PIN still
 * opens the lock, and the next sync recover. Exported for `adb shell am broadcast -n` but guarded by
 * DUMP (shell has it, apps don't).
 */
class PinVaultKeyReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val result = try {
            KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry(PIN_VAULT_KEY_ALIAS)
            "deleted $PIN_VAULT_KEY_ALIAS"
        } catch (e: Exception) {
            "failed: ${e.javaClass.simpleName}"
        }
        Log.i("PinVaultDebug", result)
        resultData = result
    }
}
