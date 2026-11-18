package com.kidslauncher.mdm.map

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.content.pm.verify.domain.DomainVerificationManager
import android.content.pm.verify.domain.DomainVerificationUserState
import android.net.Uri
import android.util.Log
import com.kidslauncher.mdm.server.MdmDeviceAdminReceiver
import com.kidslauncher.mdm.server.ServerJson
import com.kidslauncher.mdm.server.dto.PolicyResponse
import kotlinx.serialization.Serializable

private const val TAG = "MapLinks"

/** The policy's `map_links` (§7.5): the parent's switch. Missing = off. */
@Serializable
data class MapLinksPolicy(val on: Boolean = false)

/** The status report's `map_links` (§7.5, `map_links/contract.json`'s `report` rows): every key always sent. */
@Serializable
data class MapLinksReport(
    val target: String?,
    val targetState: String,
    val playServicesLinks: Boolean?,
    val vibbHosts: VibbHosts,
    val reach: MapReach,
)

/** Vibb One's declared link hosts: how many the user selected (or are verified), how many could be, how many there
 * are. A host another app holds verified can't be selected. */
@Serializable
data class VibbHosts(val selected: Int, val selectable: Int, val declared: Int)

/** Whether a link of each class reaches Vibb One now (Android's own resolution). */
@Serializable
data class MapReach(val short: Boolean, val google: Boolean, val apple: Boolean)

/** The map links' switch read from a policy: on only when the parent turned it on. */
fun mapLinksOn(policy: PolicyResponse?): Boolean = policy?.mapLinks?.on == true

/** Which target opens a map, by the states of the apps in [MapLinks.TARGETS] order: the first allowed, else the
 * first suspended, else the first hidden, else none. */
fun pickTarget(states: List<Pair<String, TargetState>>): Pair<String?, TargetState> {
    for (wanted in listOf(TargetState.ALLOWED, TargetState.SUSPENDED, TargetState.HIDDEN)) {
        states.firstOrNull { it.second == wanted }?.let { return it.first to it.second }
    }
    return null to TargetState.MISSING
}

/** The Android side of design 110: the handler's switch, the target, the steps' pages, the report. */
object MapLinksRuntime {

    /** The two filters of [MapLinkActivity] (manifest and persistent preferred activity alike, §7.1): every path on
     * the map hosts, `/maps…` on the general ones. Each its own filter: paths apply to every authority of one. */
    val ANY_PATH_HOSTS = listOf(
        "maps.app.goo.gl", "maps.google.com", "maps.google.no", "maps.google.se", "maps.google.dk", "maps.google.fi",
        "maps.google.de", "maps.google.co.uk", "maps.apple.com", "maps.apple",
    )
    val MAPS_PATH_HOSTS = listOf(
        "www.google.com", "google.com", "www.google.no", "www.google.se", "www.google.dk", "www.google.fi",
        "www.google.de", "www.google.co.uk", "goo.gl",
    )

    fun preferredFilters(): List<IntentFilter> = listOf(ANY_PATH_HOSTS to null, MAPS_PATH_HOSTS to "/maps").map { (hosts, path) ->
        IntentFilter(Intent.ACTION_VIEW).apply {
            addCategory(Intent.CATEGORY_DEFAULT)
            addCategory(Intent.CATEGORY_BROWSABLE)
            addDataScheme("http")
            addDataScheme("https")
            for (host in hosts) addDataAuthority(host, null)
            if (path != null) addDataPath(path, android.os.PatternMatcher.PATTERN_PREFIX)
        }
    }

    fun component(context: Context) = ComponentName(context, MapLinkActivity::class.java)

    /** The handler on or off (§7.1: installed disabled; switched only by a policy carrying the parent's choice). */
    fun setEnabled(context: Context, on: Boolean) {
        val state = if (on) PackageManager.COMPONENT_ENABLED_STATE_ENABLED else PackageManager.COMPONENT_ENABLED_STATE_DISABLED
        try {
            val pm = context.packageManager
            if (pm.getComponentEnabledSetting(component(context)) != state) {
                pm.setComponentEnabledSetting(component(context), state, PackageManager.DONT_KILL_APP)
                Log.i(TAG, "The map link handler ${if (on) "enabled" else "disabled"}")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Couldn't switch the map link handler: ${e.javaClass.simpleName}")
        }
    }

    fun enabled(context: Context): Boolean = try {
        context.packageManager.getComponentEnabledSetting(component(context)) == PackageManager.COMPONENT_ENABLED_STATE_ENABLED
    } catch (e: Exception) {
        false
    }

    /** The target app and its state now. */
    fun target(context: Context): Pair<String?, TargetState> {
        val pm = context.packageManager
        val dpm = context.getSystemService(DevicePolicyManager::class.java)
        val admin = ComponentName(context, MdmDeviceAdminReceiver::class.java)
        val owner = try { dpm?.isDeviceOwnerApp(context.packageName) == true } catch (e: Exception) { false }
        val states = MapLinks.TARGETS.mapNotNull { pkg ->
            val info = try {
                pm.getApplicationInfo(pkg, PackageManager.ApplicationInfoFlags.of(PackageManager.MATCH_UNINSTALLED_PACKAGES.toLong()))
            } catch (e: PackageManager.NameNotFoundException) {
                return@mapNotNull null
            }
            if (info.flags and ApplicationInfo.FLAG_INSTALLED == 0) return@mapNotNull null
            val hidden = owner && try { dpm.isApplicationHidden(admin, pkg) } catch (e: Exception) { false }
            val state = when {
                hidden -> TargetState.HIDDEN
                !info.enabled -> TargetState.HIDDEN
                info.flags and ApplicationInfo.FLAG_SUSPENDED != 0 -> TargetState.SUSPENDED
                else -> TargetState.ALLOWED
            }
            pkg to state
        }
        return pickTarget(states)
    }

    /** The target's name in the kid's words: CoMaps when it is the target, else Organic Maps. */
    fun targetName(target: String?): String = if (target?.startsWith("app.comaps") == true) "CoMaps" else "Organic Maps"

    private fun domains(context: Context): DomainVerificationManager? =
        context.getSystemService(DomainVerificationManager::class.java)

    private fun userState(context: Context, pkg: String): DomainVerificationUserState? = try {
        domains(context)?.getDomainVerificationUserState(pkg)
    } catch (e: Exception) {
        null
    }

    /** Whether [MapLinkForms.SHORT_LINK_HOLDER] may open its links (step 1 turns it off); null when unreadable. */
    fun holderLinksAllowed(context: Context): Boolean? = userState(context, MapLinkForms.SHORT_LINK_HOLDER)?.isLinkHandlingAllowed

    /** Which app Android sends a plain tap on [url] to (null: none, or a chooser). */
    private fun resolvesTo(context: Context, url: String): String? = try {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).addCategory(Intent.CATEGORY_BROWSABLE)
        context.packageManager.resolveActivity(intent, PackageManager.ResolveInfoFlags.of(PackageManager.MATCH_DEFAULT_ONLY.toLong()))
            ?.activityInfo?.packageName
    } catch (e: Exception) {
        null
    }

    /** A link to [host] that its filter takes (for the reach and the selectable check). */
    private fun probe(host: String) = if (host in MAPS_PATH_HOSTS) "https://$host/maps/probe" else "https://$host/probe"

    fun vibbHosts(context: Context): VibbHosts {
        val ours = userState(context, context.packageName)?.hostToStateMap ?: return VibbHosts(0, 0, 0)
        val selected = ours.count { it.value != DomainVerificationUserState.DOMAIN_STATE_NONE }
        val selectable = ours.count { (host, state) ->
            if (state != DomainVerificationUserState.DOMAIN_STATE_NONE) return@count true
            val other = resolvesTo(context, probe(host))?.takeIf { it != context.packageName && it != "android" }
            other == null || userState(context, other)?.hostToStateMap?.get(host) != DomainVerificationUserState.DOMAIN_STATE_VERIFIED
        }
        return VibbHosts(selected, selectable, ours.size)
    }

    fun reach(context: Context): MapReach {
        fun reaches(cls: String) = MapLinkForms.REACH_PROBES[cls]?.let { resolvesTo(context, it) } == context.packageName
        return MapReach(short = reaches("short"), google = reaches("google"), apple = reaches("apple"))
    }

    /** The report (§7.5); never throws. */
    fun report(context: Context): MapLinksReport? = try {
        val (target, state) = target(context)
        MapLinksReport(target, state.wire, holderLinksAllowed(context), vibbHosts(context), reach(context))
    } catch (e: Exception) {
        Log.w(TAG, "The map links' report failed: ${e.javaClass.simpleName}")
        null
    }

    /** The browser a link opens in during a pause (§7.2), named so the link never loops back to us: the default
     * browser, else any app in the browser category. */
    fun browser(context: Context): String? {
        val pm = context.packageManager
        val own = context.packageName
        val byDefault = resolvesTo(context, "https://example.com/")?.takeIf { it != own && it != "android" }
        if (byDefault != null) return byDefault
        return try {
            val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_APP_BROWSER)
            pm.queryIntentActivities(intent, PackageManager.ResolveInfoFlags.of(0))
                .map { it.activityInfo.packageName }.firstOrNull { it != own }
        } catch (e: Exception) {
            null
        }
    }

    /** The policy's switch from the cached policy (Meldinger's links ask this). */
    fun on(context: Context): Boolean = enabled(context)

    internal fun decodeAnswer(body: String?): MapResolveAnswer? = try {
        body?.let { ServerJson.decodeFromString(MapResolveAnswer.serializer(), it) }
    } catch (e: Exception) {
        null
    }
}
