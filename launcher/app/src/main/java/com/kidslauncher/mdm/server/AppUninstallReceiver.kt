package com.kidslauncher.mdm.server

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.util.Log

private const val LOG_TAG = "AppUninstallReceiver"

/**
 * Receives the async result of [AppInstaller.uninstallSilently] - logging only. Unlike
 * [AppInstallReceiver], there's no local state to update here: the server infers a successful
 * uninstall itself from the next status report no longer listing the package (see
 * `PolicyResponse.packagesToUninstall`'s doc comment), so a failure here just means
 * [MdmSyncWorker] will see the same package in the list again next sync and retry - no separate
 * failure-tracking needed the way [TrackedAppUpdateState] does for installs.
 */
class AppUninstallReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val status =
            intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
        val packageName = intent.getStringExtra(APP_UNINSTALL_PACKAGE_NAME_EXTRA)

        if (status == PackageInstaller.STATUS_SUCCESS) {
            Log.i(LOG_TAG, "Uninstalled $packageName successfully")
        } else {
            Log.w(LOG_TAG, "Uninstall of $packageName failed: status=$status message=$message")
        }
    }
}
