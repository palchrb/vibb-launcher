package com.kidslauncher.mdm.lock

import android.content.Context

/**
 * Release build: no keyguard cover (design 32 step 2 is a debug-only device experiment). The debug
 * build's version (src/debug) reads its switch; here it is always off, so the keyguard is written as
 * after step 1. Never add anything to this file.
 */
internal object KeyguardCoverDebug {
    @Suppress("UNUSED_PARAMETER")
    fun enabled(context: Context): Boolean = false
}
