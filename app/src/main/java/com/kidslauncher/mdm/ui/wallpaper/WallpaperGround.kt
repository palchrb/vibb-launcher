package com.kidslauncher.mdm.ui.wallpaper

import android.app.Activity
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.view.View
import androidx.core.view.WindowCompat

/**
 * Puts the shown wallpaper behind a kid screen (Home, phone book, kid Settings): transparent
 * over Android's own wallpaper when that is ours and current (those screens' windows show the
 * system wallpaper, so no bitmap of ours is drawn at all), else drawn by us - a colour, the
 * 160° gradient or the one shared decoded photo (QA 08 #6). Then the ink's scrim, and the
 * status-bar icons in the ink's colour.
 */
object WallpaperGround {
    fun apply(activity: Activity, root: View): WallpaperStore.State {
        val state = WallpaperStore.state
        val ours = try {
            WallpaperApplier.systemShows(activity, state.current)
        } catch (e: Exception) {
            false
        }
        val scrim = state.ink.scrimArgb
        root.background = if (ours && (scrim ushr 24) == 0) {
            ColorDrawable(Color.TRANSPARENT)
        } else {
            WallpaperRender.GroundDrawable(if (ours) null else state.current.fill, state.bitmap, scrim)
        }
        WindowCompat.getInsetsController(activity.window, root).apply {
            isAppearanceLightStatusBars = state.ink.darkInk
            isAppearanceLightNavigationBars = state.ink.darkInk
        }
        return state
    }
}
