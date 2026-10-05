package com.kidslauncher.mdm.apps

import android.content.Context
import android.graphics.drawable.Drawable
import android.os.UserHandle
import android.util.Log
import com.kidslauncher.mdm.Application
import com.kidslauncher.mdm.actions.AppAction
import com.kidslauncher.mdm.preferences.LauncherPreferences

/**
 * This interface is implemented by [DetailedAppInfo]
 */
sealed interface AbstractDetailedAppInfo {
    fun getRawInfo(): AbstractAppInfo
    fun getLabel(): String
    fun getIcon(context: Context): Drawable
    fun getUser(context: Context): UserHandle
    fun getAction(): AppAction


    fun getCustomLabel(context: Context): String {
        val map = (context.applicationContext as? Application)?.getCustomAppNames()
        return map?.get(getRawInfo()) ?: getLabel()
    }


    fun setCustomLabel(label: CharSequence?) {
        Log.i("Launcher", "Setting custom label for ${this.getRawInfo()} to ${label}.")
        val map = LauncherPreferences.apps().customNames() ?: HashMap<AbstractAppInfo, String>()

        if (label.isNullOrEmpty()) {
            map.remove(getRawInfo())
        } else {
            map[getRawInfo()] = label.toString()
        }
        LauncherPreferences.apps().customNames(map)
    }

}