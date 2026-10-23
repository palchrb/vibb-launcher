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
/** What a kid screen's root draws ([groundKind]). */
enum class GroundKind {
    /** Nothing: Android's wallpaper (ours) shows through, no scrim. */
    TRANSPARENT,

    /** Only the ink's scrim over Android's wallpaper (ours). */
    SCRIM,

    /** The wallpaper drawn by us, with the scrim. */
    FULL,
}

/**
 * [systemShowsOurs]: Android's wallpaper is ours and current; [scrimAlpha]: the ink's scrim alpha. Design 59d step B
 * ([ownGround]): Home right after the face's unanimated leave draws the wallpaper itself until it stops - Android's
 * wallpaper window wasn't shown yet under it while the keyguard went away (3 black frames in the user's rc.2 recording).
 */
fun groundKind(systemShowsOurs: Boolean, scrimAlpha: Int, ownGround: Boolean = false): GroundKind = when {
    ownGround || !systemShowsOurs -> GroundKind.FULL
    scrimAlpha == 0 -> GroundKind.TRANSPARENT
    else -> GroundKind.SCRIM
}

object WallpaperGround {
    fun apply(activity: Activity, root: View, ownGround: Boolean = false): WallpaperStore.State {
        val state = WallpaperStore.state
        // Worked out on the store's thread - no binder call per render (qa-08-code.md #6).
        val scrim = state.ink.scrimArgb
        root.background = when (groundKind(state.systemShowsOurs, scrim ushr 24, ownGround)) {
            GroundKind.TRANSPARENT -> ColorDrawable(Color.TRANSPARENT)
            GroundKind.SCRIM -> WallpaperRender.GroundDrawable(null, state.bitmap, scrim)
            GroundKind.FULL -> WallpaperRender.GroundDrawable(state.current.fill, state.bitmap, scrim)
        }
        WindowCompat.getInsetsController(activity.window, root).apply {
            isAppearanceLightStatusBars = state.ink.darkInk
            isAppearanceLightNavigationBars = state.ink.darkInk
        }
        return state
    }
}
