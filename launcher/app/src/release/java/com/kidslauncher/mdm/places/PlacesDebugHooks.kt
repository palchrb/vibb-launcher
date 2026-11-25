package com.kidslauncher.mdm.places

/**
 * Release build: no made-up scan, connection or motion trigger (design 134 §7). The debug build's version (src/debug)
 * holds the emulator gate's hooks; here every answer is "nothing". Never add anything to this file.
 */
internal object PlacesDebugHooks {
    fun madeUpResults(): List<ScanAp>? = null

    fun madeUpConnected(): Connected? = null

    fun resultsAgeMs(): Long = 0L

    fun scanRefused(): Boolean = false

    fun motionSensor(): Boolean = false

    @Suppress("UNUSED_PARAMETER")
    fun armTrigger(onFire: () -> Unit): Boolean = false

    fun disarmTrigger() {}
}
