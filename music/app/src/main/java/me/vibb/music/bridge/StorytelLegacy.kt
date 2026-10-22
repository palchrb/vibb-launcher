package me.vibb.music.bridge

import android.content.Context
import android.util.Log
import me.vibb.music.BuildConfig
import java.io.File
import java.security.KeyStore
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The old Storytel login goes (design 58 §5.5, QA 58-15): until design 58 the phone kept the family's
 * login, sealed under the Keystore key `vibb_storytel` in `noBackupFilesDir/storytel.bin`, and the
 * refused-login cooldown in `storytel_refused.json`. The phone now uses the server's JWT in memory only,
 * so all of it is deleted - once per process, from the sync's report (`Sync.writeReport`, on IO; every
 * sync reaches it, the update's own too), never on the mint path. Never throws; never logs a path or its
 * content - a debug build says one fixed line when something was there.
 */
object StorytelLegacy {
    private const val ALIAS = "vibb_storytel"
    private const val TAG = "StorytelLegacy"

    /** What the old login left in noBackupFilesDir, the tmp files of an interrupted write too. */
    val FILES = listOf("storytel.bin", "storytel.bin.tmp", "storytel_refused.json", "storytel_refused.json.tmp")

    private val done = AtomicBoolean(false)

    /** Once per process: the files and the Keystore key. */
    fun purge(context: Context) {
        purgeOnce(done, context.noBackupFilesDir, ::deleteKey)
    }

    /** [purge] behind [flag]: the first call deletes, every later one does nothing. Whether anything was there. */
    internal fun purgeOnce(flag: AtomicBoolean, dir: File, deleteKey: () -> Boolean): Boolean {
        if (!flag.compareAndSet(false, true)) return false
        val files = FILES.count { name -> runCatching { File(dir, name).let { it.isFile && it.delete() } }.getOrDefault(false) }
        val key = runCatching(deleteKey).getOrDefault(false)
        val any = files > 0 || key
        if (any && BuildConfig.DEBUG) Log.i(TAG, "storytel: the old login deleted")
        return any
    }

    /** The Keystore entry, when there is one: whether it was. */
    private fun deleteKey(): Boolean {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        if (!ks.containsAlias(ALIAS)) return false
        ks.deleteEntry(ALIAS)
        return true
    }
}
