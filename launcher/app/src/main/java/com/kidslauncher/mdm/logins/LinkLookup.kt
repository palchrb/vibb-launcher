package com.kidslauncher.mdm.logins

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.net.Uri

/**
 * Design 119 §4.3 step 5 (H1): what Android offers for a web link, read so that its web-link filter doesn't hide an
 * unverified app. A lookup without a package keeps only the browsers once one exists (Android 12+), so Organic Maps,
 * which declares `omaps.app` but isn't verified, is found only by asking each allowed package on its own
 * (`setPackage`: the domain filter doesn't run then). Off the main thread: one query per allowed package.
 */
object LinkLookup {
    /** A host no app declares: whoever takes it takes any web link - a full browser. */
    private val PROBE: Uri = Uri.parse("https://vibb-probe.invalid/")

    /** What [linkTargetOf] and [freeBrowserOf] read: Android's own answer with no package, the packages whose own
     * filters take the link, and the full browsers among them, each in the allowlist's order. */
    data class Found(val approved: String?, val declaring: List<String>, val browsers: List<String>)

    private val FLAGS = PackageManager.ResolveInfoFlags.of(PackageManager.MATCH_DEFAULT_ONLY.toLong())

    private fun usable(info: ApplicationInfo): Boolean = info.flags and ApplicationInfo.FLAG_SUSPENDED == 0 && info.enabled

    /** [view] a `VIEW` + `BROWSABLE` web intent; [allowlist] the policy's, in its order (`null`: an unmanaged phone,
     * where Android's own candidates are read instead). [own] is never asked. */
    fun find(context: Context, view: Intent, allowlist: List<String>?, own: String): Found {
        val pm = context.packageManager
        val approved = try {
            pm.resolveActivity(Intent(view).setPackage(null), FLAGS)?.activityInfo?.takeIf { usable(it.applicationInfo) }?.packageName
        } catch (e: Exception) {
            null
        }
        val asked = allowlist?.distinct()?.filter { it != own } ?: try {
            pm.queryIntentActivities(Intent(view).setPackage(null), FLAGS).map { it.activityInfo }
                .filter { usable(it.applicationInfo) }.map { it.packageName }.distinct().filter { it != own }
        } catch (e: Exception) {
            emptyList()
        }
        val declaring = asked.filter { takes(pm, Intent(view), it) }
        val browsers = declaring.filter { takes(pm, Intent(view).setData(PROBE), it) }
        return Found(approved, declaring, browsers)
    }

    /** [pkg] has a usable activity for [intent] (asked by package: no domain filter). */
    private fun takes(pm: PackageManager, intent: Intent, pkg: String): Boolean = try {
        pm.queryIntentActivities(intent.setPackage(pkg), FLAGS).any { usable(it.activityInfo.applicationInfo) }
    } catch (e: Exception) {
        false
    }
}
