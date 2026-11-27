package me.vibb.music.debugfeed

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Process
import android.util.Log
import java.security.KeyStore

/*
 * Debug build only (src/debug; never in a release APK: `checkReleaseHasNoDebugFeed` and SourceScanTest). Design 117
 * §14 step 5, the emulator's only way to simulate a Keystore wipe: delete the current `music_books_<id>` key, then end
 * the process so no unwrapped book key stays in memory. The next play and the next sync find the key gone.
 *
 *   adb shell am broadcast -n me.vibb.music.debug/me.vibb.music.debugfeed.DebugSealReceiver
 *
 * docs/testing/emulator.md §6bz.
 */
class DebugSealReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = context.getSharedPreferences("music_seal", Context.MODE_PRIVATE).getString("seal_kek_id", null)
        if (id == null) {
            Log.i(TAG, "seal: no current key")
            return
        }
        runCatching { KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry("music_books_$id") }
            .onSuccess { Log.i(TAG, "seal: the current key deleted; the process ends") }
            .onFailure { Log.w(TAG, "seal: the delete failed", it) }
        Process.killProcess(Process.myPid())
    }

    private companion object {
        const val TAG = "VibbMusicDebugFeed"
    }
}
