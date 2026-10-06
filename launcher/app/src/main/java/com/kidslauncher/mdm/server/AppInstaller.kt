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
/** What [AppInstaller.installSilently] did. */
enum class InstallStart {
    /** Committed - the result arrives at [AppInstallReceiver]. */
    COMMITTED,
    /** `beforeCommit` said not now: session abandoned, file kept, nothing to record. */
    DEFERRED,
    /** Writing or committing failed: session abandoned. */
    FAILED,
}

object AppInstaller {

    /** [installKey] is [TrackedAppUpdate.id], stringified - an arbitrary-but-stable label for
     * [PackageInstaller.Session.openWrite]'s required "name" argument (which doesn't need to be a
     * real Android package name; Android determines the actually-installed package from the APK's
     * own signed manifest at commit time, not from this string). [displayName] is
     * [TrackedAppUpdate.name] - purely cosmetic, threaded through to [AppInstallReceiver] so it can
     * update the install-progress notification [MdmSyncWorker] shows under the same [installKey].
     * [isLauncher] is also threaded through to [AppInstallReceiver] so it can decide whether this
     * install is the launcher's own self-update without relying on a package-name string
     * comparison.
     *
     * The launcher's own update (handy step 11) passes [beforeCommit]: right before `commit()` -
     * the point of no return (the kill follows seconds later, after verification) - it checks the
     * update window again and puts the update fence up with the session id; `false` = don't
     * commit now ([InstallStart.DEFERRED]: the session is abandoned, the file kept). If anything
     * throws after it ran and before the commit went through, [commitFailed] releases the fence.
     * A session that wasn't committed is abandoned; [deleteOnFailure] `false` keeps the file (the
     * launcher's pending APK). */
    fun installSilently(
        context: Context,
        apkFile: File,
        installKey: String,
        displayName: String,
        isLauncher: Boolean,
        releaseTag: String,
        beforeCommit: ((sessionId: Int) -> Boolean)? = null,
        commitFailed: (() -> Unit)? = null,
        deleteOnFailure: Boolean = true,
    ): InstallStart {
        val packageInstaller = context.packageManager.packageInstaller
        val params =
            PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            params.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
        }

        var sessionId = -1
        var fenceRan = false
        var committed = false
        var deferred = false
        try {
            sessionId = packageInstaller.createSession(params)
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
                if (beforeCommit != null) {
                    fenceRan = true
                    if (!beforeCommit(sessionId)) {
                        deferred = true
                        return@use
                    }
                }
                session.commit(pendingIntent.intentSender)
                committed = true
            }
            if (deferred) {
                Log.i(LOG_TAG, "Install of $installKey deferred right before the commit")
                abandon(packageInstaller, sessionId)
                return InstallStart.DEFERRED
            }
            return InstallStart.COMMITTED
        } catch (e: Exception) {
            Log.w(LOG_TAG, "Failed to start silent install of $installKey", e)
            if (committed) return InstallStart.COMMITTED
            if (fenceRan && !deferred) commitFailed?.invoke()
            if (sessionId >= 0) abandon(packageInstaller, sessionId)
            if (deleteOnFailure) apkFile.delete()
            return InstallStart.FAILED
        }
    }

    private fun abandon(packageInstaller: PackageInstaller, sessionId: Int) {
        try {
            packageInstaller.abandonSession(sessionId)
        } catch (e: Exception) {
            Log.w(LOG_TAG, "Couldn't abandon session $sessionId", e)
        }
    }

    /** A session that needs user action can't go anywhere for a device-owner install - abandoned
     * (qa-11-code #4), so it doesn't sit open. */
    fun abandonSession(context: Context, sessionId: Int) {
        if (sessionId >= 0) abandon(context.packageManager.packageInstaller, sessionId)
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
