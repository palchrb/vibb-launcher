package com.kidslauncher.mdm.server

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import java.security.KeyStore

/**
 * Debug build only (src/debug, design 24b §7, design r10 §8): deletes Keystore keys for the key-loss device checks.
 * No extra (or `--es key vault`): the parent PIN vault's key - "can't be checked until the phone reaches the server",
 * and the next sync recovers. `--es key secrets`: `secrets_v1` (the device token, the kid PIN and the reset token
 * become unreadable: the lock is up `BAD_HASH`, the parent code opens it, Settings' enrolment takes a new code).
 * `--es key all`: both - the deadlock, where the parent-code dialogs offer "Ny oppsettkode". The kid's PIN opens the
 * lock only while `secrets_v1` is intact (design r10 reverses 24b's QA #7). Force-stop after it (the secrets are
 * memoised in the process). Exported for `adb shell am broadcast -n` but guarded by DUMP (shell has it, apps don't).
 */
class PinVaultKeyReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val aliases = when (intent.getStringExtra("key")) {
            "secrets" -> listOf(SECRETS_KEY_ALIAS)
            "all" -> listOf(PIN_VAULT_KEY_ALIAS, SECRETS_KEY_ALIAS)
            else -> listOf(PIN_VAULT_KEY_ALIAS)
        }
        val result = try {
            val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            aliases.forEach { store.deleteEntry(it) }
            "deleted ${aliases.joinToString()}"
        } catch (e: Exception) {
            "failed: ${e.javaClass.simpleName}"
        }
        Log.i("PinVaultDebug", result)
        resultData = result
    }
}
