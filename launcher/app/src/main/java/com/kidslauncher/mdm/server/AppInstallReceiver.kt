package com.kidslauncher.mdm.server

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.util.Log
import com.kidslauncher.mdm.notifyAppInstallResult
import com.kidslauncher.mdm.preferences.LauncherPreferences
import com.kidslauncher.mdm.server.dto.InstallProgressReport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.File

private const val LOG_TAG = "AppInstallReceiver"

/**
 * Receives the async result of [AppInstaller.installSilently], for every tracked app including
 * the launcher's own self-update. Manifest-registered (not dynamically) so delivery doesn't
 * depend on this app's process still being alive when the install actually completes - which
 * matters most for self-update: installing an update over the currently-running app can kill/
 * replace the process mid-flight, so on success this deliberately skips deleting the cache file
 * when the installed package is this app itself, rather than risk racing that replacement. Any
 * other package is always safe to clean up immediately regardless of outcome, since this app's
 * own process is never the one being replaced.
 */
class AppInstallReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val status =
            intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
        val apkPath = intent.getStringExtra(APP_INSTALL_APK_PATH_EXTRA)
        val installKey = intent.getStringExtra(APP_INSTALL_KEY_EXTRA)
        val installName = intent.getStringExtra(APP_INSTALL_NAME_EXTRA) ?: installKey ?: "app"
        val isLauncher = intent.getBooleanExtra(APP_INSTALL_IS_LAUNCHER_EXTRA, false)
        val releaseTag = intent.getStringExtra(APP_INSTALL_RELEASE_TAG_EXTRA)
        val appId = installKey?.toLongOrNull()

        when (status) {
            PackageInstaller.STATUS_SUCCESS -> {
                Log.i(LOG_TAG, "Installed $installKey successfully ($releaseTag)")
                if (installKey != null && releaseTag != null) {
                    TrackedAppUpdateState.recordInstalled(context, installKey, releaseTag)
                }
                appId?.let { notifyAppInstallResult(context, it, installName, success = true) }
            }
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                // Shouldn't happen as device owner with USER_ACTION_NOT_REQUIRED, but if it does
                // there's nothing this receiver can silently do about it - just log for
                // visibility rather than leaving the downloaded file behind unexplained. Leaves
                // the "Installing..." notification as-is - this isn't a resolved failure.
                Log.w(LOG_TAG, "Install of $installKey requires user action unexpectedly: $message")
            }
            else -> {
                Log.w(LOG_TAG, "Install of $installKey failed: status=$status message=$message")
                // Remembering the failed tag stops the next sync from re-attempting the exact
                // same doomed install every 2 minutes forever.
                if (installKey != null && releaseTag != null) {
                    TrackedAppUpdateState.recordFailed(context, installKey, releaseTag)
                }
                appId?.let { notifyAppInstallResult(context, it, installName, success = false) }
                appId?.let { reportInstallFailureToServer(context, it) }
            }
        }

        // Not deleted on success for the launcher's own self-update: the running process is about
        // to be replaced anyway, and there's a real chance this callback never gets to finish
        // running before that happens. Any other app's file is always safe to clean up
        // immediately. isLauncher comes from the server (TrackedAppUpdate.isLauncher), not a
        // packageName == context.packageName comparison - a tracked app's package name is optional
        // now (see kid-phone-server's tracked_app_add.html) and can't be trusted for this.
        if (status != PackageInstaller.STATUS_SUCCESS || !isLauncher) {
            apkPath?.let { File(it).delete() }
        }
    }

    /**
     * Best-effort report so the admin site shows "Install failed" instead of silence - previously
     * a real `PackageInstaller` failure only ever produced a client-local, permanently-sticky
     * "don't retry this release" marker ([TrackedAppUpdateState.recordFailed]) with nothing
     * surfaced server-side, which looked from the admin's side exactly like the request never left
     * the device. `goAsync()` keeps this receiver's process alive long enough for the network call
     * to finish - same reasoning as [MdmDeviceAdminReceiver.onProfileProvisioningComplete].
     */
    private fun reportInstallFailureToServer(context: Context, trackedAppId: Long) {
        val mdm = LauncherPreferences.mdm()
        val serverUrl = mdm.serverUrl()
        val deviceToken = mdm.deviceToken()
        if (serverUrl.isNullOrBlank() || deviceToken.isNullOrBlank()) return

        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                createMdmApi(serverUrl, deviceToken)
                    .reportInstallProgress(InstallProgressReport(trackedAppId, percent = 0, failed = true))
            } catch (e: Exception) {
                Log.w(LOG_TAG, "Failed to report install failure", e)
            } finally {
                pendingResult.finish()
            }
        }
    }
}
