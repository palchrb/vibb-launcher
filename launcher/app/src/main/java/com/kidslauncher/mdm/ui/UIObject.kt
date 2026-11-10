package com.kidslauncher.mdm.ui

import android.app.Activity
import android.content.Context
import android.content.pm.ActivityInfo
import android.content.res.Resources
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import com.kidslauncher.mdm.preferences.LauncherPreferences
import com.kidslauncher.mdm.preferences.theme.Background
import com.kidslauncher.mdm.preferences.theme.Font

/**
 * An interface implemented by every [Activity], Fragment etc. in Launcher.
 * It handles themes and window flags - a useful abstraction as it is the same everywhere.
 */
interface UIObject {
    fun onCreate() {
        if (this !is Activity) {
            return
        }
        window.setFlags(0, 0) // clear flags

        if (!LauncherPreferences.display().rotateScreen()) {
            requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_NOSENSOR
        }
    }

    fun onStart() {
        setOnClicks()
        adjustLayout()
    }

    fun modifyTheme(theme: Resources.Theme): Resources.Theme {
        LauncherPreferences.theme().colorTheme().applyToTheme(theme)

        if (showsSystemWallpaper()) {
            Background.TRANSPARENT.applyToTheme(theme)
        } else {
            Background.SOLID.applyToTheme(theme)
        }

        Font.NUNITO.applyToTheme(theme)

        return theme
    }

    // fun applyTheme() { }
    fun setOnClicks() {}
    fun adjustLayout() {}

    fun isHomeScreen(): Boolean {
        return false
    }

    /** The window shows Android's wallpaper behind it: Home, and the kid screens that draw the
     * kid's wallpaper the same way (design 08, ui/wallpaper/WallpaperGround). */
    fun showsSystemWallpaper(): Boolean = isHomeScreen()

}

abstract class UIObjectActivity : AppCompatActivity(), UIObject {
    /** Design 78 §5.3: every screen is night as a constant, whatever the phone's dark theme and the kid's look say -
     * only a page ([com.kidslauncher.mdm.ui.look.KidPageActivity]) follows the look. Set before the base context is
     * attached, so AppCompat builds the configuration from the start and never recreates the activity for it. */
    override fun attachBaseContext(newBase: Context) {
        delegate.localNightMode = lookNightMode()
        super.attachBaseContext(newBase)
    }

    protected open fun lookNightMode(): Int = AppCompatDelegate.MODE_NIGHT_YES

    override fun onCreate(savedInstanceState: Bundle?) {
        super<AppCompatActivity>.onCreate(savedInstanceState)
        super<UIObject>.onCreate()
    }

    override fun onStart() {
        super<AppCompatActivity>.onStart()
        super<UIObject>.onStart()
    }

    override fun getTheme(): Resources.Theme? {
        return modifyTheme(super.getTheme())
    }
}