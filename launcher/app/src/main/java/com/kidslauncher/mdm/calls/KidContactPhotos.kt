package com.kidslauncher.mdm.calls

import android.content.Context
import android.os.UserManager
import android.util.Log
import com.kidslauncher.mdm.server.ServerJson
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The kid's own contact pictures on the phone (design 79 §5.4): files in `filesDir/kid_contact_photos/` (CE,
 * app-private) and their records in CE prefs `kid_contact_photos` (contact id -> [KidPhotoRecord]). Never in DE storage,
 * a status report, an upload or Android's backup (off).
 *
 * **An in-memory snapshot, never a disk read in a binding** (QA H2): [snapshot] is loaded on this store's IO thread
 * after the first unlock and is empty before it; a binding reads it and [ContactPhotos]' memory only ([recordFor]).
 * Every change - a picture set or removed, a load, a prune - tells [ContactPhotos]' listeners, which rebind in place.
 * All disk work runs on the store's own IO thread.
 */
object KidContactPhotos {
    private const val LOG_TAG = "KidContactPhotos"
    private const val PREFS = "kid_contact_photos"
    private const val RECORDS_KEY = "records_v1"
    const val DIR = "kid_contact_photos"
    private val RECORDS = MapSerializer(String.serializer(), KidPhotoRecord.serializer())

    private val io = Executors.newSingleThreadExecutor()
    private val loading = AtomicBoolean(false)

    @Volatile
    private var loaded = false

    /** Contact id -> its picture; empty before the first unlock and until loaded. */
    @Volatile
    var snapshot: Map<Long, KidPhotoRecord> = emptyMap()
        private set

    fun dir(context: Context): File = File(context.applicationContext.filesDir, DIR)

    /** [contact]'s picture from memory only (a flag check may start the load on the IO thread; no disk here). */
    fun recordFor(context: Context, contact: RuleContact): KidPhotoRecord? {
        ensureLoaded(context)
        return snapshot[contact.id]
    }

    /** Starts the load once, on the IO thread, after the first unlock (before it the snapshot stays empty). */
    fun ensureLoaded(context: Context) {
        if (loaded || !loading.compareAndSet(false, true)) return
        val app = context.applicationContext
        io.execute {
            try {
                if (!loaded && app.getSystemService(UserManager::class.java)?.isUserUnlocked == true) {
                    snapshot = read(app)
                    loaded = true
                    if (snapshot.isNotEmpty()) ContactPhotos.changed()
                }
            } finally {
                loading.set(false)
            }
        }
    }

    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun read(context: Context): Map<Long, KidPhotoRecord> = try {
        prefs(context).getString(RECORDS_KEY, null)
            ?.let { ServerJson.decodeFromString(RECORDS, it) }
            ?.mapNotNull { (id, record) -> id.toLongOrNull()?.let { it to record } }?.toMap()
            .orEmpty()
    } catch (e: Exception) {
        Log.w(LOG_TAG, "Unreadable kid pictures: ${e.javaClass.simpleName}")
        emptyMap()
    }

    private fun write(context: Context, records: Map<Long, KidPhotoRecord>): Boolean = try {
        prefs(context).edit().putString(RECORDS_KEY, ServerJson.encodeToString(RECORDS, records.mapKeys { it.key.toString() })).commit()
    } catch (e: Exception) {
        Log.w(LOG_TAG, "Couldn't keep the kid pictures: ${e.javaClass.simpleName}")
        false
    }

    /** Runs [change] on the IO thread against the records as stored (loaded first), then publishes the new snapshot. */
    private fun edit(context: Context, change: (Map<Long, KidPhotoRecord>) -> Map<Long, KidPhotoRecord>?, done: (Boolean) -> Unit = {}) {
        val app = context.applicationContext
        io.execute {
            // Code QA L6: the avatars rebind only when the published snapshot changed.
            val previous = snapshot
            val ok = try {
                if (app.getSystemService(UserManager::class.java)?.isUserUnlocked != true) {
                    false
                } else {
                    val before = read(app)
                    if (!loaded) {
                        snapshot = before
                        loaded = true
                    }
                    val after = change(before)
                    if (after == null || after == before) {
                        after != null
                    } else if (write(app, after)) {
                        snapshot = after
                        sweep(app, after)
                        true
                    } else {
                        false
                    }
                }
            } catch (e: Exception) {
                Log.w(LOG_TAG, "A kid picture change failed: ${e.javaClass.simpleName}")
                false
            }
            if (snapshot != previous) ContactPhotos.changed()
            android.os.Handler(android.os.Looper.getMainLooper()).post { done(ok) }
        }
    }

    /** Files no record names go (a picture replaced or removed, a crash between the file and the record). */
    private fun sweep(context: Context, records: Map<Long, KidPhotoRecord>) {
        val dir = dir(context)
        for (name in kidPhotoOrphans(records.values, dir.list()?.toList().orEmpty())) File(dir, name).delete()
    }

    /**
     * Sets [contact]'s picture to [jpeg] (our own JPEG, at most [KID_PHOTO_MAX_BYTES]): a new random file, the record,
     * the old file swept. Refused past the caps ([kidPhotoMayAdd], [contactsOnPhone]). [done] on the main thread.
     */
    fun set(context: Context, contact: RuleContact, contactsOnPhone: Int, jpeg: ByteArray, done: (Boolean) -> Unit) {
        if (jpeg.size > KID_PHOTO_MAX_BYTES) return done(false)
        val app = context.applicationContext
        edit(app, { records ->
            if (!kidPhotoMayAdd(records, contact.id, contactsOnPhone)) return@edit null
            val name = newKidPhotoFileName()
            val dir = dir(app).apply { mkdirs() }
            val tmp = File(dir, ".$name.tmp")
            tmp.writeBytes(jpeg)
            if (!tmp.renameTo(File(dir, name))) {
                tmp.delete()
                return@edit null
            }
            records + (contact.id to KidPhotoRecord(name, contact.number, System.currentTimeMillis()))
        }, done)
    }

    /** "Fjern bildet ditt": the record and its file go at once; the parent's picture or the initial returns. */
    fun remove(context: Context, contactId: Long, done: (Boolean) -> Unit = {}) =
        edit(context, { records -> records - contactId }, done)

    /**
     * Design 79 §5.5: a request's picture handed to the contact that arrived for it - moved into the kid's pictures
     * unless the kid has set one for that contact already (then it stays where it is, and the request's sweep deletes
     * it). Synchronous, on the caller's thread (the sync's), before the request's record goes. Returns whether it moved.
     */
    fun adopt(context: Context, contactId: Long, number: String, file: File): Boolean {
        if (!file.isFile || file.length() > KID_PHOTO_MAX_BYTES) return false
        val app = context.applicationContext
        val result = java.util.concurrent.FutureTask {
            val records = read(app)
            if (contactId in records || records.size >= KID_PHOTOS_MAX) return@FutureTask false
            val name = newKidPhotoFileName()
            val dir = dir(app).apply { mkdirs() }
            if (!file.renameTo(File(dir, name)) && !copyThenDelete(file, File(dir, name))) return@FutureTask false
            val after = records + (contactId to KidPhotoRecord(name, number, System.currentTimeMillis()))
            if (!write(app, after)) {
                File(dir, name).delete()
                return@FutureTask false
            }
            snapshot = after
            loaded = true
            ContactPhotos.changed()
            true
        }
        io.execute(result)
        return try {
            result.get()
        } catch (e: Exception) {
            Log.w(LOG_TAG, "A request's picture couldn't be handed over: ${e.javaClass.simpleName}")
            false
        }
    }

    private fun copyThenDelete(from: File, to: File): Boolean = try {
        from.copyTo(to, overwrite = false)
        from.delete()
        true
    } catch (e: Exception) {
        false
    }

    /** After a refresh of the call rules (from [CallPolicyStore.refresh], CE only - so also at the unlock, from
     * `Application`'s refresh: the snapshot is loaded before Home's first binding, code QA N4): [kidPhotosAfterRefresh]
     * on the IO thread, the files of the dropped records swept. */
    fun afterRefresh(context: Context, state: CallPolicyState) {
        val unlocked = context.getSystemService(UserManager::class.java)?.isUserUnlocked == true
        if (!unlocked) return
        edit(context, { records -> kidPhotosAfterRefresh(records, state, ceReadable = true) ?: records })
    }
}
