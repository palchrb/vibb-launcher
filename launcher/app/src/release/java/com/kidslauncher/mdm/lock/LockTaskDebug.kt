package com.kidslauncher.mdm.lock

import android.content.Context
import com.kidslauncher.mdm.server.LockTaskSetting

/**
 * Release build: no lock-task override. The debug build's version (src/debug) applies the design 16d
 * emulator hook; here the computed setting is written as it is. Never add anything to this file.
 */
internal object LockTaskDebug {
    @Suppress("UNUSED_PARAMETER")
    fun adjust(context: Context, setting: LockTaskSetting): LockTaskSetting = setting
}
