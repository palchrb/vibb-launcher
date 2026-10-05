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
    private const val KEY_PARTIAL = "partial_key"

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(WallpaperStore.PREFS, Context.MODE_PRIVATE)

    fun record(context: Context): ApplyRecord {
        val p = prefs(context)
        return ApplyRecord(
            appliedKey = p.getString(KEY_APPLIED, null),
            appliedId = p.getInt(KEY_APPLIED_ID, 0),
            attemptKey = p.getString(KEY_ATTEMPT, null),
            attemptDay = p.getLong(KEY_ATTEMPT_DAY, -1),
            partialKey = p.getString(KEY_PARTIAL, null),
        )
    }

    private fun save(context: Context, r: ApplyRecord) = prefs(context).edit(commit = true) {
        putString(KEY_APPLIED, r.appliedKey)
        putInt(KEY_APPLIED_ID, r.appliedId)
        putString(KEY_ATTEMPT, r.attemptKey)
        putLong(KEY_ATTEMPT_DAY, r.attemptDay)
        putString(KEY_PARTIAL, r.partialKey)
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

    /** Whether Home can be transparent over the system wallpaper (it is ours and current).
     * A binder call: only on the store's thread ([WallpaperStore.State.systemShowsOurs]). */
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

    /**
     * Off the main thread only (renders and PNG-encodes a full-screen bitmap). [allowedImages]
     * are the photos the parent still allows and the phone still has: a photo of ours outside it
     * is replaced on every call until that worked, and when the replacement fails, reset.
     */
    @Synchronized
    fun applyIfNeeded(context: Context, home: Wallpaper, allowedImages: Set<String>) {
        val app = context.applicationContext
        val key = keyFor(app, home)
        val record = record(app)
        val today = LocalDate.now().toEpochDay()
        val revoked = revokedImageShows(record, allowedImages)
        when (val decision = wallpaperApplyPlan(managed(), canSet(app), key, record, systemId(app), today, revoked)) {
            is ApplyDecision.Skip -> Log.d(LOG_TAG, "Not applied: ${decision.reason}")
            ApplyDecision.Reset -> resetIfOurs(app)
            ApplyDecision.Apply -> {
                val outcome = setBoth(app, home)
                save(app, recordAttempt(record, key, outcome, today))
                if (outcome.complete) {
                    Log.i(LOG_TAG, "System wallpaper set ($key, id ${outcome.systemId})")
                } else {
                    Log.w(LOG_TAG, "setBitmap refused or failed for $key ($outcome) - not retried today")
                    // A photo the parent took away must not stay: navy, or cleared.
                    if (revoked) resetIfOurs(app)
                }
            }
        }
    }

    /**
     * Navy on both if something of ours may show (unmanaged, or a revoked photo the apply
     * couldn't replace); `WallpaperManager.clear` when that is refused. The record is only
     * emptied when it worked - otherwise this runs again on the next pass. Returns whether
     * nothing of ours can show any more (then AppEnforcer may lift `DISALLOW_SET_WALLPAPER`).
     * Never throws.
     */
    @Synchronized
    fun resetIfOurs(context: Context): Boolean {
        val app = context.applicationContext
        val record = try {
            record(app)
        } catch (t: Throwable) {
            Log.w(LOG_TAG, "Couldn't read the wallpaper record", t)
            return false
        }
        if (!record.oursMayShow) return true
        var worked = false
        try {
            val wm = WallpaperManager.getInstance(app)
            val navy = render(app, NAVY.fill, null, 64, 64)
            worked = wm.setBitmap(navy, null, true, WallpaperManager.FLAG_SYSTEM or WallpaperManager.FLAG_LOCK) != 0
            navy.recycle()
            if (!worked) {
                wm.clear(WallpaperManager.FLAG_SYSTEM or WallpaperManager.FLAG_LOCK)
                worked = true
            }
        } catch (t: Throwable) {
            Log.w(LOG_TAG, "Couldn't put navy back or clear the wallpaper - retried next pass", t)
        }
        try {
            save(app, recordReset(record, worked))
        } catch (t: Throwable) {
            Log.w(LOG_TAG, "Couldn't save the wallpaper record", t)
            return false
        }
        if (worked) Log.i(LOG_TAG, "Our wallpaper removed (navy or cleared)")
        return worked
    }

    /**
     * Sets the lock wallpaper first, then the home one (qa-08-code.md #2: whichever half goes
     * through is recorded, see [recordAttempt]). Never throws.
     */
    private fun setBoth(context: Context, home: Wallpaper): ApplyOutcome {
        val wm = WallpaperManager.getInstance(context)
        val lock = lockScreenFill(home)
        val (w, h) = screen(context)
        var lockOk = false
        var systemId = 0
        try {
            val homeBitmap = homeBitmap(context, home.fill, w, h) ?: return ApplyOutcome(0, false)
            if (lock == home.fill) {
                systemId = wm.setBitmap(homeBitmap, null, true, WallpaperManager.FLAG_SYSTEM or WallpaperManager.FLAG_LOCK)
                lockOk = systemId != 0
            } else {
                // Set the lock screen explicitly, or it would mirror the photo.
                val lockBitmap = render(context, lock, null, w / 2, h / 2)
                lockOk = wm.setBitmap(lockBitmap, null, true, WallpaperManager.FLAG_LOCK) != 0
                lockBitmap.recycle()
                if (lockOk) systemId = wm.setBitmap(homeBitmap, null, true, WallpaperManager.FLAG_SYSTEM)
            }
            homeBitmap.recycle()
        } catch (t: Throwable) {
            // SecurityException, IOException, OutOfMemoryError: logged, retried tomorrow.
            Log.w(LOG_TAG, "setBitmap failed", t)
        }
        return ApplyOutcome(systemId, lockOk)
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
