package com.kidslauncher.mdm.places

/**
 * Debug build only (src/debug; design 131 §7, design 134 §7): what the emulator gate's [PlacesDebugReceiver] hands the
 * Wi-Fi places and the motion gate - a made-up scan and connection, the made-up results' age ([scanAgeS]), a scan that
 * counts as throttled ([refuseScan]) and a fake significant-motion trigger ([fakeMotion]). Main code reaches them only
 * through the functions here; the release build's twin (src/release) answers "nothing" to each, and
 * `checkReleaseHasNoDebugHook` / `DebugHookAbsentTest` keep these members' names out of a release build.
 */
internal object PlacesDebugHooks {
    @Volatile var madeUpAps: List<ScanAp>? = null

    @Volatile var madeUpConnection: Connected? = null

    /** The made-up results were heard this many seconds before each read (0: heard now). */
    @Volatile var scanAgeS: Long = 0

    /** Every `startScan` counts as throttled while set. */
    @Volatile var refuseScan: Boolean = false

    /** `sensor` while the fake trigger stands in for the significant-motion sensor; `null` = the real one. */
    @Volatile var fakeMotion: String? = null

    private var trigger: (() -> Unit)? = null

    fun madeUpResults(): List<ScanAp>? = madeUpAps

    fun madeUpConnected(): Connected? = madeUpConnection

    fun resultsAgeMs(): Long = scanAgeS * 1000

    fun scanRefused(): Boolean = refuseScan

    fun motionSensor(): Boolean = fakeMotion != null

    /** The motion gate arms the fake trigger: [onFire] runs once at the next `--es motion fire`. */
    @Synchronized
    fun armTrigger(onFire: () -> Unit): Boolean {
        if (fakeMotion == null) return false
        trigger = onFire
        return true
    }

    @Synchronized
    fun disarmTrigger() {
        trigger = null
    }

    /** The fake trigger fires (one-shot, as the real sensor): `false` when nothing armed it. */
    fun fireTrigger(): Boolean {
        val fire = synchronized(this) { trigger.also { trigger = null } } ?: return false
        fire()
        return true
    }
}
