package com.kidslauncher.mdm.appdisplay

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.LauncherApps
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.drawable.Drawable
import android.os.Build
import android.os.Process
import android.os.SystemClock
import android.util.DisplayMetrics
import android.util.Log
import androidx.core.content.res.ResourcesCompat
import com.kidslauncher.mdm.server.MdmApi
import com.kidslauncher.mdm.server.dto.APP_LOGO_MAX_BYTES
import com.kidslauncher.mdm.server.dto.APP_LOGO_SIZE_PX
import com.kidslauncher.mdm.ui.home.KidAvatars
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * Design 87 step 2: each installed app's own logo, drawn by the phone as "App logo" draws it
 * ([KidAvatars.drawOwnLogo], [APP_LOGO_SIZE_PX] px PNG), its hash in the status report (`installed_apps[].logo`) and
 * the file uploaded when the server's answer asks for it. The rules are the pure [AppLogoPlan.kt]; this file loads,
 * draws, stores and posts. The cache is `noBackupFilesDir/app_logos/` (`<hash>.png` and [LOGO_INDEX_FILE]). Nothing
 * here throws into the sync.
 */
object AppLogos {
    private const val LOG_TAG = "AppLogos"
    private const val DIR = "app_logos"
    private val PNG = "image/png".toMediaType()

    /** The cache's files and index are touched only under this (the sync runs one at a time anyway). */
    private val lock = Any()

    /** What [refresh] needs of one reported app, read with the report's own `PackageInfo`. */
    class Source(val packageName: String, val versionCode: Long, val lastUpdateTime: Long, val info: ApplicationInfo)

    private fun dir(context: Context): File = File(context.noBackupFilesDir, DIR).apply { mkdirs() }

    /**
     * Just before the status report (§3): drops the packages no longer in [sources], draws those whose key changed -
     * at most [LOGO_DRAW_BUDGET_MS] of drawing; the rest at the next sync - writes the index, deletes the files no
     * entry names, and returns package -> hash for the report. Any failure: what was cached before, or nothing.
     */
    fun refresh(context: Context, sources: List<Source>): Map<String, String> = try {
        synchronized(lock) {
            val dir = dir(context)
            val indexFile = File(dir, LOGO_INDEX_FILE)
            val before = decodeLogoIndex(indexFile.takeIf { it.isFile }?.readText())
            val files = hashesOnDisk(dir)
            val bySource = sources.associateBy { it.packageName }
            val current = sources.associate {
                it.packageName to AppLogoKey(it.versionCode, it.lastUpdateTime, Build.FINGERPRINT.orEmpty())
            }
            val plan = logoRefreshPlan(before, current, files)
            val apps = plan.index.apps.toMutableMap()
            val drawn = runWithin(plan.toDraw, LOGO_DRAW_BUDGET_MS, SystemClock::elapsedRealtime) { pkg ->
                val source = bySource.getValue(pkg)
                apps[pkg] = AppLogoEntry(current.getValue(pkg), draw(context, source, dir).hash)
            }
            val index = AppLogoIndex(apps)
            if (index != before) writeReplacing(indexFile) { it.write(encodeLogoIndex(index).toByteArray()) }
            unnamedLogoFiles(index, dir.list()?.toList().orEmpty()).forEach { File(dir, it).delete() }
            if (plan.toDraw.isNotEmpty()) Log.i(LOG_TAG, "Drew $drawn of ${plan.toDraw.size} app logos")
            reportedLogos(index, current, hashesOnDisk(dir))
        }
    } catch (e: Exception) {
        Log.w(LOG_TAG, "App logos not refreshed: ${e.javaClass.simpleName}")
        emptyMap()
    }

    /** A drawn logo's outcome: its [hash], `null` for a failed draw or a PNG over the limit. */
    private class Drawn(val hash: String?)

    /** Draws [source]'s logo and stores its file: [Drawn] with the hash, or without one when the draw failed or the
     * PNG is too large (that key isn't drawn again). */
    private fun draw(context: Context, source: Source, dir: File): Drawn {
        val drawable = try {
            iconOf(context, source) ?: return Drawn(null)
        } catch (e: Exception) {
            Log.w(LOG_TAG, "No icon for ${source.packageName}: ${e.javaClass.simpleName}")
            return Drawn(null)
        }
        return try {
            val png = try {
                val bitmap = KidAvatars.drawOwnLogo(context, drawable, APP_LOGO_SIZE_PX)
                ByteArrayOutputStream().use { out ->
                    bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
                    out.toByteArray()
                }.also { bitmap.recycle() }
            } catch (e: OutOfMemoryError) {
                // A drawable too large to draw: no logo for this key, the sync goes on.
                Log.w(LOG_TAG, "The logo of ${source.packageName} ran out of memory")
                return Drawn(null)
            }
            val hash = logoHashOf(png)
            if (hash == null) {
                Log.i(LOG_TAG, "The logo of ${source.packageName} is ${png.size} bytes, over $APP_LOGO_MAX_BYTES")
            } else {
                val file = File(dir, "$hash.png")
                if (!file.isFile) writeReplacing(file) { it.write(png) }
            }
            Drawn(hash)
        } catch (e: Exception) {
            Log.w(LOG_TAG, "The logo of ${source.packageName} didn't draw: ${e.javaClass.simpleName}")
            Drawn(null)
        }
    }

    /**
     * Home's drawable for the app (§2.1), in a fixed light configuration at 640 dpi (R2-M3): the launcher activity's
     * icon resource, else the application's, else Android's default app icon, loaded from the app's resources
     * ([android.content.pm.PackageManager.getResourcesForApplication] with that configuration - no code of the app is
     * loaded, and a hidden app's resources load the same) - so the phone's dark mode never changes the drawing.
     */
    private fun iconOf(context: Context, source: Source): Drawable? {
        val activity = try {
            context.getSystemService(LauncherApps::class.java)
                ?.getActivityList(source.packageName, Process.myUserHandle())?.firstOrNull()
        } catch (e: Exception) {
            null
        }
        val res = activity?.activityInfo?.iconResource?.takeIf { it != 0 }
            ?: source.info.icon.takeIf { it != 0 }
            ?: android.R.drawable.sym_def_app_icon
        val config = Configuration(context.resources.configuration).apply {
            uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or Configuration.UI_MODE_NIGHT_NO
            densityDpi = DisplayMetrics.DENSITY_XXXHIGH
        }
        val resources = context.packageManager.getResourcesForApplication(source.info, config)
        return ResourcesCompat.getDrawable(resources, res, null)
    }

    private fun hashesOnDisk(dir: File): Set<String> =
        dir.list().orEmpty().filter { it.endsWith(".png") }.map { it.removeSuffix(".png") }.filter(::isLogoHash).toSet()

    /**
     * The sync's upload step (§4.4), among the cosmetic steps after the report: each hash of [wanted] (the report's
     * answer, [wantedLogosOf]) that this phone drew ([logoUploadPlan]), one POST each, at most
     * [LOGO_UPLOAD_BUDGET_MS] in all and [LOGO_UPLOAD_CALL_MS] a call; any answer goes on, the first network error or
     * time out ends the step. Never retried within the sync, never throws.
     */
    suspend fun upload(context: Context, api: MdmApi, wanted: List<String>) {
        if (wanted.isEmpty()) return
        try {
            val dir = dir(context)
            val plan = synchronized(lock) { logoUploadPlan(wanted, hashesOnDisk(dir)) }
            val started = SystemClock.elapsedRealtime()
            var answered = 0
            for (hash in plan) {
                val timeout = uploadCallTimeoutMs(started, SystemClock.elapsedRealtime()) ?: break
                // Gone since the plan: nothing to send. Not the bytes its name says: deleted, so the next refresh draws
                // it again (its file is gone), and nothing is sent.
                val png = synchronized(lock) {
                    val file = File(dir, "$hash.png")
                    val bytes = file.takeIf { it.isFile }?.readBytes()
                    if (bytes != null && logoHashOf(bytes) != hash) {
                        file.delete()
                        Log.w(LOG_TAG, "Logo $hash didn't match its file: deleted, drawn again")
                        null
                    } else {
                        bytes
                    }
                } ?: continue
                val code = withTimeoutOrNull(timeout) {
                    try {
                        api.uploadAppLogo(hash, png.toRequestBody(PNG)).code()
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        null
                    }
                }
                if (!uploadGoesOn(code)) break
                answered++
                if (code !in 200..299) Log.i(LOG_TAG, "Logo $hash not taken (HTTP $code)")
            }
            Log.i(LOG_TAG, "Posted $answered of ${plan.size} wanted app logos")
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(LOG_TAG, "App logo uploads failed: ${e.javaClass.simpleName}")
        }
    }
}
