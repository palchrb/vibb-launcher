package com.kidslauncher.mdm.ui.home

import android.graphics.Bitmap
import android.graphics.drawable.Drawable
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import com.kidslauncher.mdm.apps.AppInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** What one background pass drew: the packages whose icon is in the cache now, and those that failed. */
data class IconPass(val drawn: Set<String>, val failed: Set<String>)

/**
 * One pass over [packages] with [draw] (true = drawn, false or a throw = failed). Pure: it touches no
 * shared state, so the caller merges [IconPass.failed] on the main thread (qa-34-s7-code N4).
 */
fun iconPass(packages: Collection<String>, draw: (String) -> Boolean): IconPass {
    val drawn = LinkedHashSet<String>()
    val failed = LinkedHashSet<String>()
    for (pkg in packages) {
        val ok = try {
            draw(pkg)
        } catch (e: Throwable) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            false
        }
        if (ok) drawn += pkg else failed += pkg
    }
    return IconPass(drawn, failed)
}

/**
 * Home's tone icons for a screen that shows a few apps - the lock's glance page and the time-rule
 * screen (design 25, design 34 P6; qa-34-s7-code N4: one loader instead of two copies). [cached] is a
 * main-thread cache lookup that notes a missing icon; [load] draws the noted ones with
 * [KidAvatars.renderAppIcon] on `Dispatchers.Default` and hands the drawn packages to `onDrawn` on
 * the main thread. Every set here is touched on the main thread only (the background pass returns
 * its failures), and the job catches everything: an icon fault never reaches the lock's crash guard.
 */
class AppIconLoader(private val activity: ComponentActivity, val sizePx: Int, private val logTag: String) {
    private val missing = LinkedHashSet<String>()
    private val failed = HashSet<String>()
    private var job: Job? = null

    /** Something was noted missing since the last [load]. */
    val hasMissing: Boolean get() = missing.isNotEmpty()

    private fun key(packageName: String): Pair<String, () -> Drawable> {
        val app = activity.application as? com.kidslauncher.mdm.Application
        val info = app?.apps?.value?.firstOrNull { (it.getRawInfo() as? AppInfo)?.packageName == packageName }
        return if (info != null) {
            info.getRawInfo().serialize() to { info.getIcon(activity) }
        } else {
            AppInfo(packageName, null).serialize() to { activity.packageManager.getApplicationIcon(packageName) }
        }
    }

    /** An already drawn icon, or null - then it is noted for [load] unless it failed before. Main thread, no work. */
    fun cached(packageName: String): Bitmap? {
        val bitmap = KidAvatars.cachedAppIcon(activity, key(packageName).first, sizePx)
        if (bitmap == null && packageName !in failed) missing += packageName
        return bitmap
    }

    /** One background job draws the noted icons; [onDrawn] gets the drawn packages on the main thread. */
    fun load(onDrawn: (Set<String>) -> Unit) {
        if (job?.isActive == true || missing.isEmpty()) return
        val keys = missing.associateWith { key(it) }
        missing.clear()
        job = activity.lifecycleScope.launch {
            try {
                val pass = withContext(Dispatchers.Default) {
                    iconPass(keys.keys) { pkg ->
                        val key = keys.getValue(pkg)
                        try {
                            KidAvatars.renderAppIcon(activity, key.first, key.second, sizePx)
                            true
                        } catch (e: Throwable) {
                            Log.w(logTag, "Couldn't draw the icon of $pkg: ${e.javaClass.simpleName}")
                            false
                        }
                    }
                }
                failed += pass.failed
                if (pass.drawn.isNotEmpty()) onDrawn(pass.drawn)
            } catch (e: Throwable) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                Log.w(logTag, "Icons failed: ${e.javaClass.simpleName}")
            }
        }
    }

    /** Stops a running pass (onStop); what it didn't draw is noted again at the next [cached]. */
    fun cancel() {
        job?.cancel()
        job = null
    }
}
