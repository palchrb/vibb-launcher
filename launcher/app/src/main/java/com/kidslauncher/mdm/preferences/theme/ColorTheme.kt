package com.kidslauncher.mdm.preferences.theme

import android.content.Context
import android.content.res.Resources
import com.google.android.material.color.DynamicColors
import com.kidslauncher.mdm.R

enum class ColorTheme(
    private val id: Int,
    private val labelResource: Int,
    val isAvailable: () -> Boolean,
) {
    DEFAULT(
        R.style.colorThemeDefault,
        R.string.settings_theme_color_theme_item_default,
        { true },
    ),
    DARK(
        R.style.colorThemeDark,
        R.string.settings_theme_color_theme_item_dark,
        { true },
    ),
    LIGHT(
        R.style.colorThemeLight,
        R.string.settings_theme_color_theme_item_light,
        { true },
    ),
    GREEN(
        R.style.colorThemeGreen,
        R.string.settings_theme_color_theme_item_green,
        { true },
    ),
    AMBER(
        R.style.colorThemeAmber,
        R.string.settings_theme_color_theme_item_amber,
        { true },
    ),
    DYNAMIC(
        R.style.colorThemeDynamic,
        R.string.settings_theme_color_theme_item_dynamic,
        { DynamicColors.isDynamicColorAvailable() },
    ),
    ;

    fun applyToTheme(theme: Resources.Theme) {
        val colorTheme = if (this.isAvailable()) this else DEFAULT
        theme.applyStyle(colorTheme.id, true)
    }

    fun getLabel(context: Context): String {
        return context.getString(labelResource)
    }
}
