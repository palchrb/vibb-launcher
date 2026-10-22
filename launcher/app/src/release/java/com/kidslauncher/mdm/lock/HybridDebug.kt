package com.kidslauncher.mdm.lock

import android.content.Context

/**
 * Release build: no design 59 hybrid (its prototype is a debug-only switch). The debug build's version
 * (src/debug) reads its switch; here it is always off, so [Hybrid59] never migrates, never is the face and
 * the lock is today's. Never add anything to this file.
 */
internal object HybridDebug {
    @Suppress("UNUSED_PARAMETER")
    fun enabled(context: Context): Boolean = false
}
