package com.kidslauncher.mdm.preferences.theme

import android.content.res.Resources
import com.kidslauncher.mdm.R
import com.kidslauncher.mdm.preferences.LauncherPreferences

enum class Background(val id: Int) {
    TRANSPARENT(R.style.backgroundWallpaper),
    SOLID(R.style.backgroundSolid),
    ;

    fun applyToTheme(theme: Resources.Theme) {
        var background = this

        // force a solid background when using the light theme
        if (LauncherPreferences.theme().colorTheme() == ColorTheme.LIGHT) {
            background = SOLID
        }
        theme.applyStyle(background.id, true)
    }
}
