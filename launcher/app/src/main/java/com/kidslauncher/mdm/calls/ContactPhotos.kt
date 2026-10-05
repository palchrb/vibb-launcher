package com.kidslauncher.mdm.calls

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import android.os.UserManager
import android.util.Log
import android.util.LruCache
import androidx.core.content.edit
import com.kidslauncher.mdm.server.MdmApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest
import java.util.Collections
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.Executors

/**
 * Contact photos on the phone (design 05-ui-photos-i18n.md in the handy workspace). The server
 * sends each contact's photo as a SHA-256 hash in `call_policy`; [sync] (after every accepted
 * policy) downloads missing ones from `GET api/devices/contact-photos/{hash}`, checks the hash,
 * and deletes files no contact uses any more - decided by the pure [photoCachePlan]. A hash the
 * server answered 404 for is not asked for again while the policy still names it.
 *
 * Files live in `filesDir/contact_photos` - credential-encrypted storage. Nothing photo-related is
 * written to device-protected storage, and nothing is decoded before the first unlock.
 *
 * [cached] never touches the disk: the UI shows the initial and a photo appears once the
 * background decode ([photoBoundsOk] first, `Throwable` caught) has finished and the listeners
 * ran (QA step 5 #1).
 */
object ContactPhotos {
    private const val LOG_TAG = "ContactPhotos"
    private const val DIR = "contact_photos"
    private const val PREFS = "contact_photos"
    private const val NOT_FOUND = "not_found"

    /** Decoded photos, ≤ 1024×1024 each - a few MB at most. */
    private val memory = object : LruCache<String, Bitmap>(16 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.allocationByteCount
    }

    /** Hashes being decoded, or that failed to decode (until the next sync changes the cache). */
    private val pending = Collections.synchronizedSet(mutableSetOf<String>())
    private val failed = Collections.synchronizedSet(mutableSetOf<String>())
    private val decoder = Executors.newSingleThreadExecutor()
    private val syncLock = Mutex()

    private val listeners = CopyOnWriteArraySet<() -> Unit>()
    private val main = Handler(Looper.getMainLooper())

    /** Called on the main thread after [sync] changed the cache or a photo finished decoding. */
    fun addListener(listener: () -> Unit) = listeners.add(listener)
    fun removeListener(listener: () -> Unit) = listeners.remove(listener)

    private fun notifyListeners() = main.post { listeners.forEach { it() } }

    private fun dir(context: Context) = File(context.applicationContext.filesDir, DIR)

    /**
     * The decoded photo for [hash] if it's in memory; otherwise null, and a background decode is
     * started (the listeners run when it's ready). Safe on the main thread.
     */
    fun cached(context: Context, hash: String?): Bitmap? {
        if (hash == null || !isValidPhotoHash(hash)) return null
        memory.get(hash)?.let { return it }
        if (hash in failed || !pending.add(hash)) return null
        val app = context.applicationContext
        decoder.execute {
            val bitmap = decode(app, hash)
            pending.remove(hash)
            if (bitmap != null) {
                memory.put(hash, bitmap)
                notifyListeners()
            } else {
                failed.add(hash)
            }
        }
        return null
    }

    private fun decode(context: Context, hash: String): Bitmap? {
        if (context.getSystemService(UserManager::class.java)?.isUserUnlocked != true) return null
        val file = File(dir(context), "$hash.jpg")
        if (!file.isFile) return null
        return try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.path, bounds)
            if (!photoBoundsOk(bounds.outWidth, bounds.outHeight)) {
                Log.w(LOG_TAG, "Photo $hash declares ${bounds.outWidth}x${bounds.outHeight}, not decoded")
                return null
            }
            BitmapFactory.decodeFile(file.path)
        } catch (t: Throwable) {
            // OutOfMemoryError included: the initial is shown instead, Home keeps running.
            Log.w(LOG_TAG, "Couldn't decode photo $hash", t)
            null
        }
    }

    /** Brings the cache in line with the current call rules ([CallPolicyStore.state]).
     * Best effort: a failed download is retried on the next accepted sync, a 404 only when the
     * policy names a different photo. */
    suspend fun sync(context: Context, api: MdmApi) = withContext(Dispatchers.IO) {
        syncLock.withLock {
            val state = CallPolicyStore.state
            val wanted = wantedPhotoHashes(state)
            val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val notFound = prefs.getStringSet(NOT_FOUND, emptySet()).orEmpty()
            val dir = dir(context)
            val plan = photoCachePlan(
                wanted = wanted,
                cachedFiles = dir.list()?.toSet().orEmpty(),
                keepWhenUnknown = state is CallPolicyState.UnknownFailClosed,
                notFound = notFound,
            )
            var changed = false
            for (name in plan.delete) {
                if (File(dir, name).delete()) changed = true
                memory.remove(name.removeSuffix(".jpg"))
            }
            if (plan.download.isNotEmpty()) dir.mkdirs()
            val newlyNotFound = mutableSetOf<String>()
            for (hash in plan.download) {
                when (download(api, dir, hash)) {
                    Download.OK -> changed = true
                    Download.NOT_FOUND -> newlyNotFound += hash
                    Download.FAILED -> {}
                }
            }
            val remembered = if (state is CallPolicyState.UnknownFailClosed) {
                notFound + newlyNotFound
            } else {
                rememberNotFound(notFound, wanted, newlyNotFound)
            }
            if (remembered != notFound) prefs.edit { putStringSet(NOT_FOUND, remembered) }
            if (changed) {
                failed.clear()
                notifyListeners()
            }
        }
    }

    private enum class Download { OK, NOT_FOUND, FAILED }

    private suspend fun download(api: MdmApi, dir: File, hash: String): Download {
        // Unique per attempt, so nothing else can delete or overwrite it (QA step 5 #7).
        val tmp = File(dir, ".$hash.${System.nanoTime()}.tmp")
        return try {
            val response = api.getContactPhoto(hash)
            val body = response.body()
            if (response.code() == 404) {
                body?.close()
                Log.w(LOG_TAG, "Photo $hash: not on the server, not asked for again")
                return Download.NOT_FOUND
            }
            if (!response.isSuccessful || body == null) {
                Log.w(LOG_TAG, "Photo $hash: HTTP ${response.code()}")
                body?.close()
                return Download.FAILED
            }
            val bytes = body.use { it.byteStream().readNBytes(MAX_PHOTO_BYTES + 1) }
            if (bytes.size > MAX_PHOTO_BYTES) {
                Log.w(LOG_TAG, "Photo $hash is too big, ignored")
                return Download.FAILED
            }
            val sha = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
            if (!photoMatches(hash, sha)) {
                Log.w(LOG_TAG, "Photo $hash doesn't match its hash, ignored")
                return Download.FAILED
            }
            tmp.writeBytes(bytes)
            if (tmp.renameTo(File(dir, "$hash.jpg"))) Download.OK else Download.FAILED
        } catch (e: Exception) {
            Log.w(LOG_TAG, "Photo $hash: download failed", e)
            Download.FAILED
        } finally {
            tmp.delete()
        }
    }
}
