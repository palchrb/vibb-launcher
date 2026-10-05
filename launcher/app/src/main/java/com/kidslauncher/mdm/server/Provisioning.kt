package com.kidslauncher.mdm.server

import android.content.Context
import android.util.Log
import com.kidslauncher.mdm.preferences.LauncherPreferences
import com.kidslauncher.mdm.server.dto.EnrollRequest
import com.kidslauncher.mdm.server.dto.ProvisioningExtras

private const val LOG_TAG = "Provisioning"

/**
 * Saves the server URL/Tailscale key and enrolls in one shot from a [ProvisioningExtras] -
 * shared by [com.kidslauncher.mdm.server.MdmDeviceAdminReceiver.onProfileProvisioningComplete]
 * (Android's native zero-touch flow) and the in-app QR scanner (for devices like GrapheneOS where
 * that native flow has no trigger at all), so there is exactly one enroll code path regardless of
 * how Device Owner was granted - by this point it already has been, either way.
 *
 * Must be called off the main thread - [TsnetClient.connectFromPreferences] blocks (up to 30s)
 * establishing the tailnet connection before the enroll call runs. This is deliberate, not just a
 * blocking-call accident: a `server_url` on the `*.ts.net` domain is only resolvable *through*
 * that connection - [createMdmApi] silently falls back to the device's normal (public) DNS/network
 * path if tsnet hasn't connected yet, which can never resolve a MagicDNS hostname and previously
 * surfaced as a confusing "can't resolve host" failure on the very first scan, before tsnet had
 * any chance to come up at all.
 */
suspend fun applyProvisioningExtras(context: Context, extras: ProvisioningExtras): Result<Unit> {
    val mdm = LauncherPreferences.mdm()
    mdm.serverUrl(extras.serverUrl)
    if (extras.tailscaleAuthKey.isNotBlank()) {
        mdm.tailscaleAuthKey(extras.tailscaleAuthKey)
    }

    // No-ops immediately if no auth key is configured (nothing to connect) or a connection from
    // earlier already exists - safe to call unconditionally rather than checking either case here.
    val tailnetIp = TsnetClient.connectFromPreferences(context)
    if (extras.tailscaleAuthKey.isNotBlank() && tailnetIp == null && !TsnetClient.connected) {
        Log.w(LOG_TAG, "Tailnet connection failed or timed out before enroll attempt")
    }

    return try {
        val response = createMdmApi(extras.serverUrl).enroll(EnrollRequest(extras.enrollmentCode))
        val body = response.body()
        if (response.isSuccessful && body != null) {
            mdm.deviceToken(body.deviceToken)
            mdm.enrolled(true)
            Result.success(Unit)
        } else {
            Result.failure(Exception("HTTP ${response.code()}"))
        }
    } catch (e: Exception) {
        Result.failure(e)
    }
}
