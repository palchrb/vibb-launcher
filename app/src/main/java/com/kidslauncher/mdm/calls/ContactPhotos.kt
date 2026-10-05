package com.kidslauncher.mdm.calls

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import android.os.UserManager
import android.util.Log
import android.util.LruCache
import com.kidslauncher.mdm.server.MdmApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.CopyOnWriteArraySet

/**
 * Contact photos on the phone (design 05-ui-photos-i18n.md in the handy workspace). The server
 * sends each contact's photo as a SHA-256 hash in `call_policy`; [sync] (after every accepted
 * policy) downloads missing ones from `GET api/devices/contact-photos/{hash}`, checks the hash,
 * and deletes files no contact uses any more - decided by the pure [photoCachePlan].
 *
 * Files live in `filesDir/contact_photos` - credential-encrypted storage. Nothing photo-related is
 * written to device-protected storage, and [bitmap] returns null before the first unlock (the
 * direct-boot call path shows names and numbers only).
 */
object ContactPhotos {
    private const val LOG_TAG = "ContactPhotos"
    private const val DIR = "contact_photos"

    /** Decoded photos, ≤ 512×512 each - a few MB at most. */
    private val memory = object : LruCache<String, Bitmap>(16 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.allocationByteCount
    }

    private val listeners = CopyOnWriteArraySet<() -> Unit>()
    private val main = Handler(Looper.getMainLooper())

    /** Called on the main thread after [sync] changed the cache. */
    fun addListener(listener: () -> Unit) = listeners.add(listener)
    fun removeListener(listener: () -> Unit) = listeners.remove(listener)

    private fun dir(context: Context) = File(context.applicationContext.filesDir, DIR)

    /** The cached photo for [hash], or null (none, not downloaded yet, or before first unlock). */
    fun bitmap(context: Context, hash: String?): Bitmap? {
        if (!isValidPhotoHash(hash) || hash == null) return null
        memory.get(hash)?.let { return it }
        if (context.getSystemService(UserManager::class.java)?.isUserUnlocked != true) return null
        val file = File(dir(context), "$hash.jpg")
        if (!file.isFile) return null
        return try {
            BitmapFactory.decodeFile(file.path)?.also { memory.put(hash, it) }
        } catch (e: Exception) {
            Log.w(LOG_TAG, "Couldn't decode a cached photo", e)
            null
        }
    }

    /** Brings the cache in line with the current call rules ([CallPolicyStore.state]).
     * Best effort: a failed download is retried on the next accepted sync. */
    suspend fun sync(context: Context, api: MdmApi) = withContext(Dispatchers.IO) {
        val state = CallPolicyStore.state
        val dir = dir(context)
        val files = dir.list()?.toSet().orEmpty()
        val plan = photoCachePlan(
            wanted = wantedPhotoHashes(state),
            cachedFiles = files,
            keepWhenUnknown = state is CallPolicyState.UnknownFailClosed,
        )
        var changed = false
        for (name in plan.delete) {
            if (File(dir, name).delete()) changed = true
            memory.remove(name.removeSuffix(".jpg"))
        }
        if (plan.download.isNotEmpty()) dir.mkdirs()
        for (hash in plan.download) {
            if (download(api, dir, hash)) changed = true
        }
        if (changed) main.post { listeners.forEach { it() } }
    }

    private suspend fun download(api: MdmApi, dir: File, hash: String): Boolean {
        val tmp = File(dir, ".$hash.tmp")
        return try {
            val response = api.getContactPhoto(hash)
            val body = response.body()
            if (!response.isSuccessful || body == null) {
                Log.w(LOG_TAG, "Photo $hash: HTTP ${response.code()}")
                body?.close()
                return false
            }
            val bytes = body.use { it.byteStream().readNBytes(MAX_PHOTO_BYTES + 1) }
            if (bytes.size > MAX_PHOTO_BYTES) {
                Log.w(LOG_TAG, "Photo $hash is too big, ignored")
                return false
            }
            val sha = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
            if (!photoMatches(hash, sha)) {
                Log.w(LOG_TAG, "Photo $hash doesn't match its hash, ignored")
                return false
            }
            tmp.writeBytes(bytes)
            tmp.renameTo(File(dir, "$hash.jpg"))
        } catch (e: Exception) {
            Log.w(LOG_TAG, "Photo $hash: download failed", e)
            false
        } finally {
            tmp.delete()
        }
    }
}
