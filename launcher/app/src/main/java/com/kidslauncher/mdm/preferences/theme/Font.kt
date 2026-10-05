package com.kidslauncher.mdm.preferences.theme

import android.content.Context
import android.content.res.Resources
import android.graphics.Typeface
import com.kidslauncher.mdm.R

enum class Font(val id: Int, val getTypeface: (Context) -> Typeface?) {
    SYSTEM_DEFAULT(
        R.style.fontSystemDefault,
        { _ -> Typeface.DEFAULT }),

    /** The kid UI's font (design 08-ui-polish.md), bundled in res/font (OFL, assets/licenses). */
    NUNITO(
        R.style.fontNunito,
        { context -> androidx.core.content.res.ResourcesCompat.getFont(context, R.font.nunito) }),
    ;

    fun applyToTheme(theme: Resources.Theme) {
        theme.applyStyle(id, true)
    }
}
