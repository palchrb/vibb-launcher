package com.kidslauncher.mdm.lock

import android.content.Context
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.kidslauncher.mdm.badges.ClockReader
import com.kidslauncher.mdm.server.SelfUpdate

private const val LOG_TAG = "ClockApp"

/**
 * The catalog's clock app (design 42 §1.1): Fossify Clock, known by its package and its release
 * signing certificate (the same APK on every phone - the catalog pins both, security review M10).
 * Only when both match does the lock read its timer ([ClockReader]) and let its alarm show over the
 * lock ([lockClockPackages]). [verified] is the cached answer for the main thread; [refresh] (off
 * the main thread) reads the installed package's signers again - at process start and whenever the
 * package is added, changed or removed.
 */
object ClockApp {
    const val PACKAGE = "org.fossify.clock"

    /** `apksigner verify --print-certs` of `clock-10-foss-release.apk` (GitHub FossifyOrg/Clock
     * 1.6.0, 2026-10-20): "C=IN, O=Fossify, CN=Naveen Singh". */
    const val SIGNER_SHA256 = "affdb124d3f4720c2f98dbca9eacba0514fba4306e20a2786c861c3c0d6ff292"

    @Volatile
    var verified = false
        private set

    /** Whether the installed [PACKAGE] carries the pinned signer - a PackageManager call, never on
     * the main thread. Not installed or unreadable: false. */
    fun installed(context: Context): Boolean = clockAppVerified(
        try {
            SelfUpdate.signers(
                context.packageManager.getPackageInfo(
                    PACKAGE,
                    PackageManager.PackageInfoFlags.of(PackageManager.GET_SIGNING_CERTIFICATES.toLong()),
                ).signingInfo,
            )
        } catch (e: Exception) {
            null
        },
    )

    /** Off the main thread: [verified] again; a lost verification drops the timer tile at once. */
    fun refresh(context: Context) {
        try {
            val now = installed(context)
            if (now != verified) Log.i(LOG_TAG, "Clock app $PACKAGE verified: $now")
            verified = now
            if (!now) Handler(Looper.getMainLooper()).post { ClockReader.disconnected() }
        } catch (e: Throwable) {
            Log.w(LOG_TAG, "Clock app check failed: ${e.javaClass.simpleName}")
        }
    }
}

/** [signers] (the installed package's, SHA-256 hex; `null` = not installed or unreadable) carry
 * [ClockApp.SIGNER_SHA256] - `CatalogApkCheck`'s rule: the pin among the signers or their lineage. */
fun clockAppVerified(signers: Set<String>?): Boolean =
    signers?.any { it.lowercase() == ClockApp.SIGNER_SHA256 } == true
