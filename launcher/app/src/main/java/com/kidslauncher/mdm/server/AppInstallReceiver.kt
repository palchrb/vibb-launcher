package com.kidslauncher.mdm.server

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Handler
import android.os.Looper
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
        val sessionId = intent.getIntExtra(PackageInstaller.EXTRA_SESSION_ID, -1)
        val apkPath = intent.getStringExtra(APP_INSTALL_APK_PATH_EXTRA)
        val installKey = intent.getStringExtra(APP_INSTALL_KEY_EXTRA)
        val installName = intent.getStringExtra(APP_INSTALL_NAME_EXTRA) ?: installKey ?: "app"
        val isLauncher = intent.getBooleanExtra(APP_INSTALL_IS_LAUNCHER_EXTRA, false)
        val releaseTag = intent.getStringExtra(APP_INSTALL_RELEASE_TAG_EXTRA)
        val appId = installKey?.toLongOrNull()
        val app = context.applicationContext
        // Only a process restarted for this result (not the one that committed) brings Home back
        // after a failed self-update - the committing one never left it (qa-11-design.md #9).
        val restarted = !SelfUpdate.committedInThisProcess

        var failed = false
        // The launcher's own update keeps its verified APK after a transient failure (retried
        // after the backoff without a new download, qa-11-code #4).
        var keepFile = false
        when (status) {
            PackageInstaller.STATUS_SUCCESS -> {
                Log.i(LOG_TAG, "Installed $installKey successfully ($releaseTag)")
                if (installKey != null && releaseTag != null) {
                    TrackedAppUpdateState.recordInstalled(context, installKey, releaseTag)
                }
                appId?.let { notifyAppInstallResult(context, it, installName, success = true) }
                // Not deleted for the launcher's own self-update: the running process is about to
                // be replaced anyway (the new process's SelfUpdate.cleanup removes it).
                keepFile = isLauncher
            }
            else -> {
                failed = true
                if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
                    // Shouldn't happen as device owner with USER_ACTION_NOT_REQUIRED; nobody can
                    // act on it silently, so the session is abandoned rather than left open
                    // (qa-11-code #4) and the release counts as failed.
                    Log.w(LOG_TAG, "Install of $installKey requires user action unexpectedly: $message")
                    AppInstaller.abandonSession(context, sessionId)
                } else {
                    Log.w(LOG_TAG, "Install of $installKey failed: status=$status message=$message")
                }
                if (installKey != null && releaseTag != null) {
                    if (isLauncher && selfUpdateFailure(status) == SelfUpdateFailure.REFUSE_RELEASE) {
                        // The same APK would fail again: not retried until another release.
                        TrackedAppUpdateState.recordRefused(context, installKey, releaseTag)
                    } else {
                        // Remembering the failed tag stops the next sync from re-attempting the
                        // exact same doomed install every 2 minutes forever.
                        keepFile = isLauncher
                        TrackedAppUpdateState.recordFailed(context, installKey, releaseTag, keepPending = isLauncher)
                    }
                }
                appId?.let { notifyAppInstallResult(context, it, installName, success = false) }
            }
        }

        // The download record of this release is done either way (design 13 QA #4; the runner
        // removes it at the commit already - this covers a record written again meanwhile).
        if (appId != null && releaseTag != null) {
            try {
                AppDownloadStore.remove(context, appId, releaseTag)
            } catch (e: Exception) {
                Log.w(LOG_TAG, "Couldn't clear the download record of $installKey", e)
            }
        }

        // Any other app's file is always safe to clean up immediately. isLauncher comes from the
        // server (TrackedAppUpdate.isLauncher), not a packageName == context.packageName
        // comparison - a tracked app's package name is optional now (see kid-phone-server's
        // tracked_app_add.html) and can't be trusted for this.
        if (!keepFile) {
            apkPath?.let { File(it).delete() }
        }

        // One goAsync for everything that may wait: the update fence's check (every result is a
        // trigger; the fence's own session's result is its input - step 11) and the failure report.
        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val fenceSession = try {
                    UpdateFence.onInstallResult(app, sessionId, status)
                } catch (e: Exception) {
                    Log.w(LOG_TAG, "Update fence check after an install result failed", e)
                    false
                }
                if (isLauncher && status != PackageInstaller.STATUS_SUCCESS && restarted) {
                    // A failure after the kill restarted the old build for this broadcast: nothing
                    // else brings Home (and lock task) back.
                    Log.i(LOG_TAG, "Self-update failed after the kill (fence session: $fenceSession) - Home comes back")
                    Handler(Looper.getMainLooper()).post { SelfUpdate.bringHomeToFront(app, "a failed self-update") }
                }
                if (failed) appId?.let { reportInstallFailureToServer(app, it) }
                // A finished self-update's file (kept on success, see above) or a failed one's.
                if (isLauncher) SelfUpdate.cleanup(app)
            } finally {
                pendingResult.finish()
            }
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
    private suspend fun reportInstallFailureToServer(context: Context, trackedAppId: Long) {
        val mdm = LauncherPreferences.mdm()
        val serverUrl = mdm.serverUrl()
        val deviceToken = mdm.deviceToken()
        if (serverUrl.isNullOrBlank() || deviceToken.isNullOrBlank()) return
        try {
            createMdmApi(serverUrl, deviceToken)
                .reportInstallProgress(InstallProgressReport(trackedAppId, percent = 0, failed = true))
        } catch (e: Exception) {
            Log.w(LOG_TAG, "Failed to report install failure", e)
        }
    }
}
