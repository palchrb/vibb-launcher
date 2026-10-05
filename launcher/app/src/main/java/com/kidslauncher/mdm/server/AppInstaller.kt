package com.kidslauncher.mdm.server

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import android.util.Log
import java.io.File

private const val LOG_TAG = "AppInstaller"
const val APP_INSTALL_ACTION = "com.kidslauncher.mdm.APP_INSTALL_RESULT"
const val APP_INSTALL_APK_PATH_EXTRA = "apk_path"
const val APP_INSTALL_KEY_EXTRA = "install_key"
const val APP_INSTALL_NAME_EXTRA = "install_name"
const val APP_INSTALL_IS_LAUNCHER_EXTRA = "is_launcher"
const val APP_INSTALL_RELEASE_TAG_EXTRA = "release_tag"
const val APP_UNINSTALL_ACTION = "com.kidslauncher.mdm.APP_UNINSTALL_RESULT"
const val APP_UNINSTALL_PACKAGE_NAME_EXTRA = "package_name"

/**
 * Silently installs a downloaded APK for any tracked app - a third-party app (e.g. Tailscale) or
 * the launcher's own self-update, both go through this exact same function now - via Device
 * Owner's [PackageInstaller] privilege. [PackageInstaller.SessionParams.MODE_FULL_INSTALL]
 * determines install-vs-update purely from the APK's own embedded package name, so no special
 * handling is needed either way at this layer; the one place self-update genuinely differs is
 * [AppInstallReceiver] skipping its cache-file cleanup on success, since installing over yourself
 * risks the process dying before that line gets to run - see [isLauncher].
 */
object AppInstaller {

    /** [installKey] is [TrackedAppUpdate.id], stringified - an arbitrary-but-stable label for
     * [PackageInstaller.Session.openWrite]'s required "name" argument (which doesn't need to be a
     * real Android package name; Android determines the actually-installed package from the APK's
     * own signed manifest at commit time, not from this string). [displayName] is
     * [TrackedAppUpdate.name] - purely cosmetic, threaded through to [AppInstallReceiver] so it can
     * update the install-progress notification [MdmSyncWorker] shows under the same [installKey].
     * [isLauncher] is also threaded through to [AppInstallReceiver] so it can decide whether this
     * install is the launcher's own self-update without relying on a package-name string
     * comparison. */
    fun installSilently(
        context: Context,
        apkFile: File,
        installKey: String,
        displayName: String,
        isLauncher: Boolean,
        releaseTag: String,
    ) {
        val packageInstaller = context.packageManager.packageInstaller
        val params =
            PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            params.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
        }

        try {
            val sessionId = packageInstaller.createSession(params)
            packageInstaller.openSession(sessionId).use { session ->
                apkFile.inputStream().use { input ->
                    session.openWrite(installKey, 0, apkFile.length()).use { output ->
                        input.copyTo(output)
                        session.fsync(output)
                    }
                }

                // Explicit component, not an implicit action + setPackage() - AppInstallReceiver's
                // manifest entry declares no <intent-filter> (it's app-internal only, never meant
                // to be triggered by anything outside this PendingIntent), and Android has nothing
                // to match an implicit broadcast against without one. Confirmed live: the broadcast
                // was being silently dropped every time, so recordInstalled/recordFailed never once
                // fired - this, not the apply()-vs-commit() write timing, was the actual reason the
                // self-update loop never stopped. setAction() is kept only for readability/logging;
                // resolution here is entirely by component.
                val resultIntent = Intent(context, AppInstallReceiver::class.java)
                    .setAction(APP_INSTALL_ACTION)
                    .putExtra(APP_INSTALL_APK_PATH_EXTRA, apkFile.absolutePath)
                    .putExtra(APP_INSTALL_KEY_EXTRA, installKey)
                    .putExtra(APP_INSTALL_NAME_EXTRA, displayName)
                    .putExtra(APP_INSTALL_IS_LAUNCHER_EXTRA, isLauncher)
                    .putExtra(APP_INSTALL_RELEASE_TAG_EXTRA, releaseTag)
                val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        PendingIntent.FLAG_MUTABLE
                    } else {
                        0
                    }
                val pendingIntent =
                    PendingIntent.getBroadcast(context, sessionId, resultIntent, flags)
                session.commit(pendingIntent.intentSender)
            }
        } catch (e: Exception) {
            Log.w(LOG_TAG, "Failed to start silent install of $installKey", e)
            apkFile.delete()
        }
    }

    /** Silently uninstalls [packageName] - no confirmation dialog, same Device Owner privilege
     * class as [installSilently]. Fire-and-forget: [AppUninstallReceiver] only logs the result,
     * since the server confirms completion itself from the next status report (see
     * [PolicyResponse.packagesToUninstall]'s doc comment) rather than needing an explicit
     * client-side acknowledgement. Uninstalling an already-absent package fails harmlessly. */
    fun uninstallSilently(context: Context, packageName: String) {
        try {
            val resultIntent = Intent(context, AppUninstallReceiver::class.java)
                .setAction(APP_UNINSTALL_ACTION)
                .putExtra(APP_UNINSTALL_PACKAGE_NAME_EXTRA, packageName)
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    PendingIntent.FLAG_MUTABLE
                } else {
                    0
                }
            val pendingIntent = PendingIntent.getBroadcast(
                context, packageName.hashCode(), resultIntent, flags
            )
            context.packageManager.packageInstaller.uninstall(packageName, pendingIntent.intentSender)
        } catch (e: Exception) {
            Log.w(LOG_TAG, "Failed to start silent uninstall of $packageName", e)
        }
    }
}
