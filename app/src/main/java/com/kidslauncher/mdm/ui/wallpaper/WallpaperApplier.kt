package com.kidslauncher.mdm.ui.wallpaper

import android.app.WallpaperManager
import android.app.admin.DevicePolicyManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.util.Log
import androidx.core.content.edit
import androidx.core.graphics.createBitmap
import com.kidslauncher.mdm.calls.CallPolicyState
import com.kidslauncher.mdm.calls.CallPolicyStore
import com.kidslauncher.mdm.server.CachedPolicy
import com.kidslauncher.mdm.server.cachedPolicy
import java.io.File
import java.time.LocalDate

/**
 * Puts the shown wallpaper on Android's own wallpaper (design 08 §3), so the recents and
 * swipe-up animations and the lock screen match Home - decided by the pure [wallpaperApplyPlan]:
 * only while managed, as device owner, only when the wanted one changed, never twice a day for
 * the same key, never on the main thread. Photos go on the home screen only; the lock screen gets
 * navy unless the parent ticked "also on the lock screen" (QA 08 #1). Android keeps its own copy
 * of whatever is set (in system storage, `/data/system/users/0/`), which is why the lock screen
 * is opt-in.
 *
 * `DISALLOW_SET_WALLPAPER` (hardening, set while managed) keeps other apps and Settings out; the
 * device owner is exempt (AOSP `WallpaperManagerService.isSetWallpaperAllowed`). When the phone
 * is unmanaged [resetIfOurs] puts navy back before that restriction is lifted.
 */
object WallpaperApplier {
    private const val LOG_TAG = "WallpaperApplier"
    private const val KEY_APPLIED = "applied_key"
    private const val KEY_APPLIED_ID = "applied_id"
    private const val KEY_ATTEMPT = "attempt_key"
    private const val KEY_ATTEMPT_DAY = "attempt_day"

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(WallpaperStore.PREFS, Context.MODE_PRIVATE)

    fun record(context: Context): ApplyRecord {
        val p = prefs(context)
        return ApplyRecord(
            appliedKey = p.getString(KEY_APPLIED, null),
            appliedId = p.getInt(KEY_APPLIED_ID, 0),
            attemptKey = p.getString(KEY_ATTEMPT, null),
            attemptDay = p.getLong(KEY_ATTEMPT_DAY, -1),
        )
    }

    private fun save(context: Context, r: ApplyRecord) = prefs(context).edit(commit = true) {
        putString(KEY_APPLIED, r.appliedKey)
        putInt(KEY_APPLIED_ID, r.appliedId)
        putString(KEY_ATTEMPT, r.attemptKey)
        putLong(KEY_ATTEMPT_DAY, r.attemptDay)
    }

    private fun screen(context: Context): Pair<Int, Int> {
        val metrics = context.resources.displayMetrics
        return minOf(metrics.widthPixels, metrics.heightPixels) to maxOf(metrics.widthPixels, metrics.heightPixels)
    }

    fun keyFor(context: Context, home: Wallpaper): String {
        val (w, h) = screen(context)
        return wallpaperKey(home, w, h)
    }

    private fun systemId(context: Context): Int = try {
        WallpaperManager.getInstance(context).getWallpaperId(WallpaperManager.FLAG_SYSTEM)
    } catch (e: Exception) {
        0
    }

    /** Whether Home can be transparent over the system wallpaper (it is ours and current). */
    fun systemShows(context: Context, home: Wallpaper): Boolean =
        systemShowsOurs(record(context), keyFor(context, home), systemId(context))

    private fun managed(): Boolean =
        (cachedPolicy() as? CachedPolicy.Ok)?.policy?.allowlist != null ||
            CallPolicyStore.state !is CallPolicyState.Unmanaged

    private fun canSet(context: Context): Boolean = try {
        val dpm = context.getSystemService(DevicePolicyManager::class.java)
        val wm = WallpaperManager.getInstance(context)
        dpm?.isDeviceOwnerApp(context.packageName) == true && wm.isWallpaperSupported && wm.isSetWallpaperAllowed
    } catch (e: Exception) {
        false
    }

    /** Off the main thread only (renders and PNG-encodes a full-screen bitmap). */
    @Synchronized
    fun applyIfNeeded(context: Context, home: Wallpaper) {
        val app = context.applicationContext
        val key = keyFor(app, home)
        val record = record(app)
        val today = LocalDate.now().toEpochDay()
        when (val decision = wallpaperApplyPlan(managed(), canSet(app), key, record, systemId(app), today)) {
            is ApplyDecision.Skip -> Log.d(LOG_TAG, "Not applied: ${decision.reason}")
            ApplyDecision.Reset -> resetIfOurs(app)
            ApplyDecision.Apply -> {
                val id = setBoth(app, home)
                save(app, recordAttempt(record, key, id, today))
                if (id == 0) {
                    Log.w(LOG_TAG, "setBitmap refused or failed for $key - not retried today")
                } else {
                    Log.i(LOG_TAG, "System wallpaper set ($key, id $id)")
                }
            }
        }
    }

    /**
     * Unmanaged (or about to be): navy on both, once, if ours is applied - called by AppEnforcer
     * before it lifts `DISALLOW_SET_WALLPAPER`, and by [applyIfNeeded].
     */
    @Synchronized
    fun resetIfOurs(context: Context) {
        val app = context.applicationContext
        if (record(app).appliedKey == null) return
        try {
            val wm = WallpaperManager.getInstance(app)
            val navy = render(app, NAVY.fill, null, 64, 64)
            wm.setBitmap(navy, null, true, WallpaperManager.FLAG_SYSTEM or WallpaperManager.FLAG_LOCK)
            navy.recycle()
            Log.i(LOG_TAG, "Phone unmanaged: system wallpaper back to navy")
        } catch (t: Throwable) {
            Log.w(LOG_TAG, "Couldn't put navy back", t)
        }
        save(app, ApplyRecord())
    }

    /** Sets the home and lock wallpapers; the system one's id, 0 when refused or failed. */
    private fun setBoth(context: Context, home: Wallpaper): Int {
        val wm = WallpaperManager.getInstance(context)
        val lock = lockScreenFill(home)
        val (w, h) = screen(context)
        return try {
            val homeBitmap = homeBitmap(context, home.fill, w, h) ?: return 0
            val id = if (lock == home.fill) {
                wm.setBitmap(homeBitmap, null, true, WallpaperManager.FLAG_SYSTEM or WallpaperManager.FLAG_LOCK)
            } else {
                val systemId = wm.setBitmap(homeBitmap, null, true, WallpaperManager.FLAG_SYSTEM)
                // Set the lock screen explicitly, or it would mirror the photo.
                val lockBitmap = render(context, lock, null, w / 2, h / 2)
                val lockId = wm.setBitmap(lockBitmap, null, true, WallpaperManager.FLAG_LOCK)
                lockBitmap.recycle()
                if (lockId == 0) 0 else systemId
            }
            homeBitmap.recycle()
            id
        } catch (t: Throwable) {
            // SecurityException, IOException, OutOfMemoryError: logged, retried tomorrow.
            Log.w(LOG_TAG, "setBitmap failed", t)
            0
        }
    }

    private fun homeBitmap(context: Context, fill: WallpaperFill, w: Int, h: Int): Bitmap? = when (fill) {
        // A colour scales without loss; a gradient at half size is smooth enough.
        is WallpaperFill.Solid -> render(context, fill, null, 64, 64)
        is WallpaperFill.Gradient -> render(context, fill, null, w / 2, h / 2)
        is WallpaperFill.Image -> {
            val file = File(WallpaperStore.dir(context), "${fill.hash}.jpg")
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.path, bounds)
            if (!wallpaperBoundsOk(bounds.outWidth, bounds.outHeight)) {
                null
            } else {
                val photo = BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply {
                    inPreferredConfig = Bitmap.Config.RGB_565
                    inSampleSize = wallpaperSampleSize(bounds.outWidth, bounds.outHeight, w, h)
                })
                photo?.let {
                    val out = render(context, fill, it, w, h, Bitmap.Config.RGB_565)
                    it.recycle()
                    out
                }
            }
        }
    }

    private fun render(
        context: Context,
        fill: WallpaperFill,
        photo: Bitmap?,
        w: Int,
        h: Int,
        config: Bitmap.Config = Bitmap.Config.ARGB_8888,
    ): Bitmap {
        val bitmap = createBitmap(w.coerceAtLeast(1), h.coerceAtLeast(1), config)
        WallpaperRender.draw(Canvas(bitmap), Rect(0, 0, bitmap.width, bitmap.height), fill, photo, Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG))
        return bitmap
    }
}
