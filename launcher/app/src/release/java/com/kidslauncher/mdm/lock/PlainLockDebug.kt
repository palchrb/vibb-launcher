package com.kidslauncher.mdm.lock

import android.content.Context

/**
 * Release build: no plain Android lock (design 59c is a debug-only prototype). The debug build's version
 * (src/debug) reads its switch and posts the curated lock rows; here it is always off and starts nothing.
 * Never add anything to this file.
 */
internal object PlainLockDebug {
    @Suppress("UNUSED_PARAMETER")
    fun enabled(context: Context): Boolean = false

    @Suppress("UNUSED_PARAMETER")
    fun start(context: Context) = Unit
}
