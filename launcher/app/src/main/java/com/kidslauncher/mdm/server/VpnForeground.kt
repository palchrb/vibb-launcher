package com.kidslauncher.mdm.server

/**
 * When [KidVpnService] holds its foreground state - and with it its notification (PLAN, "Fewer
 * persistent notifications", 2026-10-21). Pure, `VpnForegroundTest`.
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

