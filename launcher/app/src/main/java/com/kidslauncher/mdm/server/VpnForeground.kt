package com.kidslauncher.mdm.server

/**
 * When [KidVpnService] holds its foreground state - and with it its notification (PLAN, "Fewer
 * persistent notifications", 2026-10-21). Pure, `QuietNotificationsTest`.
 *
 * An established VpnService is bound by the system: `Vpn.establish()` (AOSP
 * `services/core/java/com/android/server/connectivity/Vpn.java`) calls `bindServiceAsUser` with
 * `BIND_AUTO_CREATE | BIND_FOREGROUND_SERVICE` from system_server, which puts our process at
 * `PROCESS_STATE_BOUND_FOREGROUND_SERVICE` (OomAdjuster) - the same threshold the Doze/power-save
 * network rules let through (`NetworkPolicyManager.FOREGROUND_THRESHOLD_STATE`). And the always-on
 * designation restarts the service whenever its tunnel is gone (`Vpn.startAlwaysOnVpn`). So once
 * the tunnel is up **and** the always-on VPN is ours, the foreground state adds nothing but the
 * notification; the anchor ([CommandListenerService]) keeps the process foreground anyway.
 *
 * Everything else holds it, as before: the start itself (`startForeground` stays first in
 * `onCreate`, the API-34 rule), a failed `establish()`, a tunnel whose read loop died, a VPN that
 * isn't the always-on one yet (first launch before the first sync).
 */
fun vpnHoldsForeground(tunnelUp: Boolean, alwaysOnIsOurs: Boolean): Boolean = !(tunnelUp && alwaysOnIsOurs)

/** How [KidVpnService.start] starts the service. */
enum class VpnStartKind {
    /** An instance runs: `startService` - a started record skips the background check and never
     * carries `fgRequired`, so nothing has to be answered with `startForeground` (qa-lfix-notifs-code #2). */
    PLAIN,

    /** None runs: `startForegroundService` with [KidVpnService.EXTRA_FOREGROUND]; `onCreate`'s
     * `startForeground` answers it first. */
    FOREGROUND,
}

/** What [KidVpnService.settleForeground] does. */
enum class VpnSettle { NONE, ENTER, DROP }

/**
 * [KidVpnService]'s tunnel and foreground state, changed on the main thread only (`onRevoke` posts
 * there); [stopped] is also read by [KidVpnService.start] and `syncCustom`. [T] is the tunnel's
 * descriptor (a `ParcelFileDescriptor` there, any token in tests).
 *
 * A lost tunnel (its read loop ended) is retried inside the service, 5 s doubling to 5 min, the
 * back-off reset by a tunnel that held 10 min (qa-lfix-notifs-code #1), so the filter doesn't wait
 * for the next `apply()`.
 */
class VpnTunnelState<T : Any>(
    private val retryMinMs: Long = 5_000,
    private val retryMaxMs: Long = 5 * 60_000,
    private val healthyMs: Long = 10 * 60_000,
) {
    @Volatile
    var stopped = false
        private set

    var tunnel: T? = null
        private set

    var foreground = false
        private set

    private var establishedAtMs = 0L
    private var nextRetryMs = retryMinMs

    /** `startForeground` succeeded. */
    fun enteredForeground() {
        foreground = true
    }

    fun established(fd: T, nowMs: Long) {
        tunnel = fd
        establishedAtMs = nowMs
    }

    /**
     * Hold or drop ([vpnHoldsForeground]). [alwaysOn] is `VpnService.isAlwaysOn`; a throw counts as
     * not always-on - the notification never drops on a VPN we can't confirm. A [DROP] is taken at
     * once (`stopForeground` doesn't fail); an [ENTER] only after [enteredForeground].
     */
    fun settle(alwaysOn: () -> Boolean): VpnSettle {
        if (stopped) return VpnSettle.NONE
        val ours = try {
            alwaysOn()
        } catch (e: Exception) {
            false
        }
        val hold = vpnHoldsForeground(tunnelUp = tunnel != null, alwaysOnIsOurs = ours)
        return when {
            hold && !foreground -> VpnSettle.ENTER
            !hold && foreground -> {
                foreground = false
                VpnSettle.DROP
            }
            else -> VpnSettle.NONE
        }
    }

    /**
     * The read loop of [fd] ended. `null` when it is stale (another tunnel since) or the service
     * stopped - nothing to do; otherwise the tunnel is gone (the caller closes [fd] and settles) and
     * the delay before the first retry.
     */
    fun readLoopEnded(fd: T, nowMs: Long): Long? {
        if (stopped || tunnel !== fd) return null
        tunnel = null
        if (nowMs - establishedAtMs >= healthyMs) nextRetryMs = retryMinMs
        return takeRetryDelay()
    }

    /** A retry is due only while the service runs without a tunnel. */
    fun retryDue(): Boolean = !stopped && tunnel == null

    /** A retry that didn't bring the tunnel up: the delay before the next. */
    fun retryFailed(): Long = takeRetryDelay()

    /** The service stops: returns the tunnel to close. */
    fun stop(): T? {
        stopped = true
        foreground = false
        return tunnel.also { tunnel = null }
    }

    private fun takeRetryDelay(): Long = nextRetryMs.also { nextRetryMs = minOf(it * 2, retryMaxMs) }
}

/** [KidVpnService.start]'s kind: plain whenever an instance runs (`null` = none). */
fun vpnStartKind(running: VpnTunnelState<*>?): VpnStartKind =
    if (running != null && !running.stopped) VpnStartKind.PLAIN else VpnStartKind.FOREGROUND
