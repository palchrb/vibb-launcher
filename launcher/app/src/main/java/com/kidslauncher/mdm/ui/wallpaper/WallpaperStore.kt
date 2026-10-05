package com.kidslauncher.mdm.ui.wallpaper

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import android.os.UserManager
import android.util.Log
import android.util.LruCache
import androidx.core.content.edit
import androidx.core.graphics.scale
import com.kidslauncher.mdm.calls.photoMatches
import com.kidslauncher.mdm.calls.rememberNotFound
import com.kidslauncher.mdm.server.CachedPolicy
import com.kidslauncher.mdm.server.MdmApi
import com.kidslauncher.mdm.server.cachedPolicy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.Executors

/**
 * The wallpaper on the phone (design 08-ui-polish.md §3, QA qa-08-design.md #2, #6): the
 * allowed list from the cached policy, the kid's pick (CE prefs `kid_wallpaper_id`), the cached
 * images in `filesDir/wallpapers` (credential-encrypted; nothing wallpaper-related goes to
 * device-protected storage, nothing is read before the first unlock), and - in memory - what is
 * shown ([current]), its ink ([ink]) and, for a photo, **one** process-wide decoded bitmap
 * (RGB_565, sampled for the screen, bounds-checked, `Throwable` caught).
 *
 * Everything that reads files or the policy runs on one background thread; the UI only reads
 * the in-memory state and is told by the listeners when it changed. [sync] runs after every
 * accepted policy: it downloads allowed images, deletes every image no longer allowed, then
 * re-applies the system wallpaper at once ([WallpaperApplier]) if the shown one changed.
 */
object WallpaperStore {
    private const val LOG_TAG = "WallpaperStore"
    private const val DIR = "wallpapers"
    const val PREFS = "wallpaper_state"
    private const val KEY_PICKED = "kid_wallpaper_id"
    private const val KEY_NOT_FOUND = "not_found"

    data class State(
        /** What the kid can pick: the allowed wallpapers that can be shown now. */
        val choices: List<Wallpaper>,
        val current: Wallpaper,
        val ink: InkChoice,
        /** The decoded photo when [current] is an image, else null. */
        val bitmap: Bitmap?,
        /** Android's wallpaper is ours and current, so the screens can be transparent over it -
         * worked out on the store's thread (a binder call), never during a render. */
        val systemShowsOurs: Boolean = false,
    )

    @Volatile
    var state: State = State(emptyList(), NAVY, inkForFill(NAVY.fill)!!, null)
        private set

    @Volatile
    private var loaded = false

    private val executor = Executors.newSingleThreadExecutor()

    /** Runs [block] on the store's thread. Everything is caught: an exception reaching the
     * crash handler would end the process, and with it the call path. */
    private fun onWorker(block: () -> Unit) = executor.execute {
        try {
            block()
        } catch (t: Throwable) {
            Log.w(LOG_TAG, "Wallpaper work failed", t)
        }
    }
    private val syncLock = Mutex()
    private val listeners = CopyOnWriteArraySet<() -> Unit>()
    private val main = Handler(Looper.getMainLooper())
    private val imageInks = HashMap<String, InkChoice>()

    /** The allowed photos the phone has - anything of ours outside it is revoked. */
    @Volatile
    private var allowedImages: Set<String> = emptySet()

    /**
     * Square tile-size renders of the allowed photos for the picker (qa-08-code.md #4): ~75 KB
     * each at xxhdpi, room for far more than a parent uploads, so nothing is evicted and decoded
     * again while the picker is open. [thumbsPending] keeps one decode per photo in flight,
     * [thumbsFailed] stops retrying one that can't be read.
     */
    private val thumbs = object : LruCache<String, Bitmap>(8 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.allocationByteCount
    }
    private val thumbsPending = java.util.Collections.synchronizedSet(mutableSetOf<String>())
    private val thumbsFailed = java.util.Collections.synchronizedSet(mutableSetOf<String>())
    private val thumbListeners = CopyOnWriteArraySet<() -> Unit>()

    fun addListener(listener: () -> Unit) = listeners.add(listener)
    fun removeListener(listener: () -> Unit) = listeners.remove(listener)
    private fun notifyListeners() = main.post { listeners.forEach { it() } }

    /** Told (on the main thread) when a new thumbnail is ready - the picker only. */
    fun addThumbnailListener(listener: () -> Unit) = thumbListeners.add(listener)
    fun removeThumbnailListener(listener: () -> Unit) = thumbListeners.remove(listener)

    fun dir(context: Context) = File(context.applicationContext.filesDir, DIR)

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Loads the state once per process (in the background); later calls do nothing. */
    fun ensureLoaded(context: Context) {
        if (loaded) return
        refreshAsync(context)
    }

    /** Recomputes the state in the background (after a pick, a sync or a policy change). */
    fun refreshAsync(context: Context, then: (() -> Unit)? = null) {
        val app = context.applicationContext
        onWorker {
            refreshNow(app)
            then?.invoke()
        }
    }

    /** The kid picked [id] in Settings: remembered, shown, and put on the system wallpaper. */
    fun pick(context: Context, id: Long) {
        val app = context.applicationContext
        onWorker {
            prefs(app).edit(commit = true) { putLong(KEY_PICKED, id) }
            refreshNow(app)
            applyAndRefresh(app)
        }
    }

    /** Puts the shown one on Android's wallpaper if needed, then refreshes [State.systemShowsOurs]. */
    private fun applyAndRefresh(app: Context) {
        WallpaperApplier.applyIfNeeded(app, state.current, allowedImages)
        refreshNow(app)
    }

    private fun refreshNow(context: Context) {
        if (context.getSystemService(UserManager::class.java)?.isUserUnlocked != true) return
        val allowed = parseWallpapers(
            (cachedPolicy() as? CachedPolicy.Ok)?.policy?.launcherUi?.wallpapers.orEmpty()
        )
        val prefs = prefs(context)
        val picked = if (prefs.contains(KEY_PICKED)) prefs.getLong(KEY_PICKED, 0) else null
        val cached = cachedHashes(context)
        var current = effectiveWallpaper(allowed, picked, cached)
        val old = state
        var bitmap: Bitmap? = null
        val fill = current.fill
        if (fill is WallpaperFill.Image) {
            bitmap = if ((old.current.fill as? WallpaperFill.Image)?.hash == fill.hash) old.bitmap else decode(context, fill.hash)
            if (bitmap == null) {
                // Unreadable after all: the next usable one, never a broken screen.
                current = effectiveWallpaper(allowed.filter { it.fill !is WallpaperFill.Image }, picked, emptySet())
            }
        }
        val ink = when (val f = current.fill) {
            is WallpaperFill.Image -> bitmap?.let { imageInk(f.hash, it) } ?: inkForFill(NAVY.fill)!!
            else -> inkForFill(f)!!
        }
        val choices = allowed.filter { usable(it, cached) }
        allowedImages = wantedWallpaperHashes(allowed) intersect cached
        val showsOurs = try {
            WallpaperApplier.systemShows(context, current)
        } catch (e: Exception) {
            false
        }
        val next = State(choices, current, ink, if (current.fill is WallpaperFill.Image) bitmap else null, showsOurs)
        loaded = true
        if (next != old) {
            state = next
            notifyListeners()
        }
    }

    private fun cachedHashes(context: Context): Set<String> =
        dir(context).list()?.mapNotNullTo(mutableSetOf()) { name ->
            name.removeSuffix(".jpg").takeIf { name.endsWith(".jpg") && com.kidslauncher.mdm.calls.isValidPhotoHash(it) }
        }.orEmpty()

    private fun screenSize(context: Context): Pair<Int, Int> {
        val metrics = context.resources.displayMetrics
        return minOf(metrics.widthPixels, metrics.heightPixels) to maxOf(metrics.widthPixels, metrics.heightPixels)
    }

    private fun decode(context: Context, hash: String): Bitmap? {
        val file = File(dir(context), "$hash.jpg")
        if (!file.isFile) return null
        return try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.path, bounds)
            if (!wallpaperBoundsOk(bounds.outWidth, bounds.outHeight)) {
                Log.w(LOG_TAG, "Wallpaper $hash declares ${bounds.outWidth}x${bounds.outHeight}, not decoded")
                return null
            }
            val (w, h) = screenSize(context)
            val options = BitmapFactory.Options().apply {
                inPreferredConfig = Bitmap.Config.RGB_565
                inSampleSize = wallpaperSampleSize(bounds.outWidth, bounds.outHeight, w, h)
            }
            BitmapFactory.decodeFile(file.path, options)
        } catch (t: Throwable) {
            // OutOfMemoryError included: Home falls back to a colour and keeps running.
            Log.w(LOG_TAG, "Couldn't decode wallpaper $hash", t)
            null
        }
    }

    /** The ink of a photo, once per hash: luminance of a 32×57 render. */
    private fun imageInk(hash: String, bitmap: Bitmap): InkChoice = synchronized(imageInks) {
        imageInks.getOrPut(hash) {
            val small = bitmap.scale(32, 57)
            val pixels = IntArray(32 * 57)
            small.getPixels(pixels, 0, 32, 0, 0, 32, 57)
            if (small !== bitmap) small.recycle()
            inkForImage(luminanceStats(pixels))
        }
    }

    /** A square render of an allowed photo for the picker, or null - then it is decoded in the
     * background once, and the thumbnail listeners run when it is there. Safe on the main thread. */
    fun thumbnail(context: Context, hash: String, sizePx: Int): Bitmap? {
        thumbs.get(hash)?.let { return it }
        if (hash in thumbsFailed || !thumbsPending.add(hash)) return null
        val app = context.applicationContext
        onWorker {
            try {
                val bitmap = decodeThumbnail(app, hash, sizePx)
                if (bitmap == null) {
                    thumbsFailed += hash
                } else if (thumbs.get(hash) == null) {
                    thumbs.put(hash, bitmap)
                    main.post { thumbListeners.forEach { it() } }
                }
            } finally {
                thumbsPending.remove(hash)
            }
        }
        return null
    }

    private fun decodeThumbnail(context: Context, hash: String, sizePx: Int): Bitmap? = try {
        val file = File(dir(context), "$hash.jpg")
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        if (!wallpaperBoundsOk(bounds.outWidth, bounds.outHeight)) {
            null
        } else {
            BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply {
                inPreferredConfig = Bitmap.Config.RGB_565
                inSampleSize = wallpaperSampleSize(bounds.outWidth, bounds.outHeight, sizePx, sizePx)
            })?.let { sampled ->
                // Down to the tile: a centred square of sizePx.
                val side = minOf(sampled.width, sampled.height)
                val square = Bitmap.createBitmap(sampled, (sampled.width - side) / 2, (sampled.height - side) / 2, side, side)
                val out = if (side > sizePx) square.scale(sizePx, sizePx) else square
                if (square !== sampled && square !== out) square.recycle()
                if (sampled !== out) sampled.recycle()
                out
            }
        }
    } catch (t: Throwable) {
        Log.w(LOG_TAG, "Couldn't decode the thumbnail of $hash", t)
        null
    }

    /**
     * After every accepted sync (off the main thread): download the allowed images that are
     * missing, delete every image no longer allowed (QA 08 #2), recompute what is shown and put
     * it on the system wallpaper right away when it changed - so a photo the parent took away
     * is replaced within this sync, not at the next Home resume.
     */
    suspend fun sync(context: Context, api: MdmApi) = withContext(Dispatchers.IO) {
        syncLock.withLock {
            val app = context.applicationContext
            val allowed = parseWallpapers(
                (cachedPolicy() as? CachedPolicy.Ok)?.policy?.launcherUi?.wallpapers.orEmpty()
            )
            val prefs = prefs(app)
            val notFound = prefs.getStringSet(KEY_NOT_FOUND, emptySet()).orEmpty()
            val dir = dir(app)
            val plan = wallpaperCachePlan(allowed, dir.list()?.toSet().orEmpty(), notFound)
            for (name in plan.delete) {
                File(dir, name).delete()
                thumbs.remove(name.removeSuffix(".jpg"))
            }
            thumbsFailed.clear()
            if (plan.download.isNotEmpty()) dir.mkdirs()
            val newlyNotFound = mutableSetOf<String>()
            for (hash in plan.download) {
                if (download(api, dir, hash) == Download.NOT_FOUND) newlyNotFound += hash
            }
            val remembered = rememberNotFound(notFound, wantedWallpaperHashes(allowed), newlyNotFound)
            if (remembered != notFound) prefs.edit { putStringSet(KEY_NOT_FOUND, remembered) }
            // On the store's own thread, so a pick and a sync never interleave.
            val done = kotlinx.coroutines.CompletableDeferred<Unit>()
            executor.execute {
                try {
                    refreshNow(app)
                    applyAndRefresh(app)
                } catch (t: Throwable) {
                    Log.w(LOG_TAG, "Wallpaper refresh after sync failed", t)
                } finally {
                    done.complete(Unit)
                }
            }
            done.await()
        }
    }

    private enum class Download { OK, NOT_FOUND, FAILED }

    private suspend fun download(api: MdmApi, dir: File, hash: String): Download {
        val tmp = File(dir, ".$hash.${System.nanoTime()}.tmp")
        return try {
            val response = api.getWallpaper(hash)
            val body = response.body()
            if (response.code() == 404) {
                body?.close()
                Log.w(LOG_TAG, "Wallpaper $hash: not allowed or not on the server, not asked for again")
                return Download.NOT_FOUND
            }
            if (!response.isSuccessful || body == null) {
                Log.w(LOG_TAG, "Wallpaper $hash: HTTP ${response.code()}")
                body?.close()
                return Download.FAILED
            }
            val bytes = body.use { it.byteStream().readNBytes(MAX_WALLPAPER_BYTES + 1) }
            if (bytes.size > MAX_WALLPAPER_BYTES) {
                Log.w(LOG_TAG, "Wallpaper $hash is too big, ignored")
                return Download.FAILED
            }
            val sha = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
            if (!photoMatches(hash, sha)) {
                Log.w(LOG_TAG, "Wallpaper $hash doesn't match its hash, ignored")
                return Download.FAILED
            }
            tmp.writeBytes(bytes)
            if (tmp.renameTo(File(dir, "$hash.jpg"))) Download.OK else Download.FAILED
        } catch (e: Exception) {
            Log.w(LOG_TAG, "Wallpaper $hash: download failed", e)
            Download.FAILED
        } finally {
            tmp.delete()
        }
    }
}
