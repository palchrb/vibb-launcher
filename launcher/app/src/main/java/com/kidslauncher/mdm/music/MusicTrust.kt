package com.kidslauncher.mdm.music

import android.content.Context
import android.content.pm.PackageManager
import com.kidslauncher.mdm.BuildConfig

/**
 * Security review S-L8: the music app by its name **and** our signer ([musicTrusted]) - the glue the music code asks
 * before a grant, the managed config, the nudge, the budget exemption and a bridge call. A PackageManager call each
 * time (no cache to go stale across an install); any thread.
 */
object MusicTrust {
    /** [pkg] is installed and signed by the launcher's own signer. */
    fun sameSigner(context: Context, pkg: String): Boolean = try {
        context.packageManager.checkSignatures(context.packageName, pkg) == PackageManager.SIGNATURE_MATCH
    } catch (e: Exception) {
        false
    }

    /** [pkg]'s signer against ours: `true` the same, `false` another (or unsigned), `null` the package unknown
     * (`SIGNATURE_UNKNOWN_PACKAGE`) or the check failed - the report's `other_signer` says nothing then. */
    fun signerMatches(context: Context, pkg: String): Boolean? = try {
        when (context.packageManager.checkSignatures(context.packageName, pkg)) {
            PackageManager.SIGNATURE_MATCH -> true
            PackageManager.SIGNATURE_UNKNOWN_PACKAGE -> null
            else -> false
        }
    } catch (e: Exception) {
        null
    }

    fun trusted(context: Context, pkg: String?): Boolean = musicTrusted(pkg, BuildConfig.DEBUG) { sameSigner(context, it) }

    /** The installed, trusted music apps, release first. */
    fun installed(context: Context): List<String> = trustedMusicNames(BuildConfig.DEBUG).filter { trusted(context, it) }

    /** The bridge's caller check ([callerAllowed]). */
    fun callerAllowed(context: Context, callerPackages: List<String>?): Boolean =
        com.kidslauncher.mdm.music.callerAllowed(callerPackages, BuildConfig.DEBUG) { sameSigner(context, it) }
}
