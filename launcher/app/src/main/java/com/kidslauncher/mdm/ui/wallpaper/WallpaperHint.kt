package com.kidslauncher.mdm.ui.wallpaper

import android.app.WallpaperColors
import android.app.WallpaperManager
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.kidslauncher.mdm.ui.home.supportsDarkText
import java.util.concurrent.Executors

/**
 * Android's own reading of the home wallpaper (PLAN "Home: contacts with a name pill, its colour from Android"):
 * `WallpaperColors.HINT_SUPPORTS_DARK_TEXT` of `getWallpaperColors(FLAG_SYSTEM)`, which picks the contacts' name pill
 * and ring ([com.kidslauncher.mdm.ui.home.namePillStyle]). Home [start]s it while shown and [stop]s it in `onStop`: an
 * `OnColorsChangedListener` follows every change of the wallpaper (ours, set by [WallpaperApplier], included), and
 * each start reads the current colours once on a background thread (a binder call that may make Android compute
 * them), since changes while Home was stopped weren't heard. All APIs are API 27-31, below minSdk 34; nothing differs
 * on Android 14-17.
 *
 * [supportsDarkText] is process-wide, so Home recreated keeps the last answer instead of starting white; `null` (not
 * read yet, or Android has no colours for the wallpaper) gives the white pill.
 */
object WallpaperHint {
    private const val LOG_TAG = "WallpaperHint"

    @Volatile
    var supportsDarkText: Boolean? = null
        private set

    private val main = Handler(Looper.getMainLooper())
    private val reader = Executors.newSingleThreadExecutor()
    private var onChange: (() -> Unit)? = null
    private var registered: WallpaperManager.OnColorsChangedListener? = null

    /** Follows the wallpaper until [stop]; [changed] runs on the main thread when the hint changes. Main thread only. */
    fun start(context: Context, changed: () -> Unit) {
        val app = context.applicationContext
        onChange = changed
        if (registered == null) {
            val listener = WallpaperManager.OnColorsChangedListener { colors, which ->
                if ((which and WallpaperManager.FLAG_SYSTEM) != 0) take(colors)
            }
            try {
                WallpaperManager.getInstance(app).addOnColorsChangedListener(listener, main)
                registered = listener
            } catch (e: Exception) {
                Log.w(LOG_TAG, "Couldn't follow the wallpaper's colours: ${e.javaClass.simpleName}")
            }
        }
        reader.execute {
            val colors = try {
                WallpaperManager.getInstance(app).getWallpaperColors(WallpaperManager.FLAG_SYSTEM)
            } catch (e: Exception) {
                Log.w(LOG_TAG, "Couldn't read the wallpaper's colours: ${e.javaClass.simpleName}")
                return@execute
            }
            main.post { take(colors) }
        }
    }

    /** Stops following the wallpaper (Home's `onStop`). Main thread only. */
    fun stop(context: Context) {
        onChange = null
        val listener = registered ?: return
        registered = null
        try {
            WallpaperManager.getInstance(context.applicationContext).removeOnColorsChangedListener(listener)
        } catch (e: Exception) {
            Log.w(LOG_TAG, "Couldn't stop following the wallpaper's colours: ${e.javaClass.simpleName}")
        }
    }

    private fun take(colors: WallpaperColors?) {
        val hint = supportsDarkText(colors?.colorHints)
        if (hint == supportsDarkText) return
        supportsDarkText = hint
        onChange?.invoke()
    }
}
