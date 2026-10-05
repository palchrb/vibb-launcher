package com.kidslauncher.mdm.server

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.kidslauncher.mdm.preferences.LauncherPreferences

/**
 * Restores this app's background services immediately after it's updated in place - a self-update
 * via [AppInstaller], or an `adb install -r` sideload - without waiting for the next physical
 * unlock. Found live (2026-08-15): this app isn't direct-boot-aware, and a killed pinned-Home-app
 * process isn't eagerly relaunched by the OS while the device sits at the keyguard, so without
 * this, [CommandListenerService] (and with it the UnifiedPush relay, the SSE command-push
 * connection, and the periodic sync backstop) simply stayed dead until someone next unlocked the
 * phone - a real reachability gap for a device this project's whole point is being able to reach.
 *
 * `android.intent.action.MY_PACKAGE_REPLACED` is a protected broadcast (only the OS can send it),
 * delivered specifically to the just-updated app - and unlike an ordinary implicit broadcast,
 * Android will cold-start this app's process to deliver it even while the device is locked, as
 * long as this isn't a pre-first-unlock boot (i.e. CE storage is already available, which it is
 * for any package-replace event by definition - the OS couldn't have just replaced the APK
 * otherwise). `android:exported="false"` is safe here despite that, matching this codebase's
 * default: this is a system broadcast the OS delivers directly to receivers in the updated app,
 * not something another app could send to trigger it.
 *
 * Deliberately does NOT touch [TsnetClient] here - see [Application.onCreate]'s own doc comment
 * on why that connection only ever starts from [HomeActivity]'s first `onResume()`, never from a
 * non-UI entry point, given tsnet's real native-crash history on this project. That means tailnet
 * reachability (and so kid-phone-server sync) still waits for the next actual unlock after an
 * update; only the tsnet-independent parts - most notably [UnifiedPushRelay], which deliberately
 * never goes through tsnet's proxy - come back immediately via this receiver.
 */
class PackageReplacedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        CommandListenerService.start(context)
        if (LauncherPreferences.mdm().vpnFilterEnabled()) {
            KidVpnService.start(context)
        }
    }
}
