package com.kidslauncher.mdm.setup

import android.Manifest
import android.app.NotificationManager
import android.app.admin.DevicePolicyManager
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import android.net.ConnectivityManager
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager
import androidx.core.content.ContextCompat
import com.kidslauncher.mdm.assistant.AirplaneRuntime
import com.kidslauncher.mdm.assistant.AssistantSetup
import com.kidslauncher.mdm.badges.BadgeStore
import com.kidslauncher.mdm.calls.CallSystem
import com.kidslauncher.mdm.map.MapLinksRuntime
import com.kidslauncher.mdm.map.mapLinksOn
import com.kidslauncher.mdm.map.playLinksDone
import com.kidslauncher.mdm.map.vibbLinksDone
import com.kidslauncher.mdm.notify.NotificationChannelsReader
import com.kidslauncher.mdm.notify.channelsOrange
import com.kidslauncher.mdm.server.BatteryExemption
import com.kidslauncher.mdm.server.CachedPolicy
import com.kidslauncher.mdm.server.FingerprintEnrol
import com.kidslauncher.mdm.server.MobileNetworkRuntime
import com.kidslauncher.mdm.server.RestrictionsPause
import com.kidslauncher.mdm.server.WriteSettingsAccess
import com.kidslauncher.mdm.server.cachedPolicy
import com.kidslauncher.mdm.server.clockFormatChoiceOf
import com.kidslauncher.mdm.server.controllablePackages
import com.kidslauncher.mdm.server.dto.PolicyResponse
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

/** The report's readings, in the contract's key order. */
private val REPORT_KEYS = listOf("device_owner", "child_pin", "mobile_data", "location", "data_saver", "full_screen_intent", "fingerprint", "location_private")

/**
 * Design 114 §4.1: the status report's `setup` - only the readings no other field carries ([readings], each `true`,
 * `false` or `null` when Android refused the read; a key left out when it doesn't apply on this phone) and, while the
 * setup hold lasts, its wall-clock end and the time left by the elapsed clock. Pinned by `setup_steps.json`'s `report`
 * and `report_no_hold`.
 */
data class SetupReport(
    val readings: Map<String, Boolean?>,
    val holdUntilMs: Long? = null,
    val holdLeftMs: Long? = null,
) {
    fun json(): JsonObject = buildJsonObject {
        for (key in REPORT_KEYS) {
            if (readings.containsKey(key)) put(key, JsonPrimitive(readings[key]))
        }
        if (holdUntilMs != null && holdLeftMs != null) {
            put("hold_until_ms", JsonPrimitive(holdUntilMs))
            put("hold_left_ms", JsonPrimitive(holdLeftMs))
        }
    }
}

/**
 * §2.2's "Reading" column, read live on the phone ([readings]), the need rule's inputs from the cached policy
 * ([inputs]), the ticks ([ticked]) and the shared rule over them ([missing], [count]). Off the main thread: the reads
 * ask Android's services (roles, telephony, the channels).
 */
object SetupReadings {
    fun deviceOwner(context: Context): Boolean = try {
        context.getSystemService(DevicePolicyManager::class.java)?.isDeviceOwnerApp(context.packageName) == true
    } catch (e: Exception) {
        false
    }

    /** Design 114b: Android's credential now; `null` when it can't be read (a throw is `null` too, by [read]). */
    private fun deviceSecure(context: Context): Boolean? =
        context.getSystemService(android.app.KeyguardManager::class.java)?.isDeviceSecure

    private fun read(block: () -> Boolean?): Boolean? = try {
        block()
    } catch (e: Exception) {
        null
    }

    /** Row 5 (QA L4): the user's own mobile-data switch on the default data subscription; absent without one. */
    private fun mobileData(context: Context, into: MutableMap<String, Boolean?>) {
        if (SubscriptionManager.getDefaultDataSubscriptionId() == SubscriptionManager.INVALID_SUBSCRIPTION_ID) return
        into["mobile_data"] = try {
            MobileNetworkRuntime.dataTelephony(context)?.isDataEnabledForReason(TelephonyManager.DATA_ENABLED_REASON_USER)
        } catch (e: SecurityException) {
            // READ_BASIC_PHONE_STATE is a normal permission (granted at install on 14-17); refused anyway: unreadable.
            null
        } catch (e: Exception) {
            null
        }
    }

    /** Row 17: absent without a sensor. */
    private fun fingerprint(context: Context, into: MutableMap<String, Boolean?>) {
        if (!FingerprintEnrol.hasSensor(context)) return
        into["fingerprint"] = FingerprintEnrol.enrolled(context)
    }

    /** Design 125: Google's location accuracy off - Android's `network` provider off while Location is on (no network
     * provider at all counts as off). `null` with Location off: nothing to read then. */
    private fun locationPrivate(context: Context): Boolean? {
        val lm = context.getSystemService(LocationManager::class.java) ?: return null
        if (!lm.isLocationEnabled) return null
        return !lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
    }

    /** The readings only the `setup` report carries (§4.1). */
    private fun ownReadings(context: Context): Map<String, Boolean?> = buildMap {
        put("device_owner", deviceOwner(context))
        val cached = policy()
        put("child_pin", childPinDone(read { deviceSecure(context) }, cached?.kidLock?.keyguard, policyKnown = cached != null))
        mobileData(context, this)
        put("location", read { ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED })
        put("data_saver", read {
            context.getSystemService(ConnectivityManager::class.java)
                ?.let { it.restrictBackgroundStatus != ConnectivityManager.RESTRICT_BACKGROUND_STATUS_ENABLED }
        })
        put("full_screen_intent", read { context.getSystemService(NotificationManager::class.java)?.canUseFullScreenIntent() })
        fingerprint(context, this)
        put("location_private", read { locationPrivate(context) })
    }

    /** The report's `setup` (§4.1). */
    fun report(context: Context): SetupReport = SetupReport(
        ownReadings(context),
        holdUntilMs = RestrictionsPause.holdUntilMs(),
        holdLeftMs = RestrictionsPause.holdLeftMs(),
    )

    private fun policy(): PolicyResponse? = (cachedPolicy() as? CachedPolicy.Ok)?.policy

    /** Every step's reading (§2.2), from the same sources the report's other fields use. */
    fun readings(context: Context, policy: PolicyResponse? = policy()): Map<String, Boolean?> = buildMap {
        putAll(ownReadings(context))
        put("notification_access", read { BadgeStore.accessGranted(context) })
        put("background", BatteryExemption.exempt(context))
        NotificationChannelsReader.read(context)?.let { channels ->
            val call = policy?.callPolicy
            put("vibb_notifications", !channelsOrange(channels, call?.smsMode == "managed", call?.callsMode == "managed"))
        }
        put("write_settings", WriteSettingsAccess.granted(context))
        put("assistant", read { AirplaneRuntime.roleHeld(context) })
        put("wifi_airplane", read { AirplaneRuntime.wifiKept(context) != null })
        val callFilter = read { CallSystem.screeningRoleHeld(context) && CallSystem.redirectionRoleHeld(context) }
        put("call_filter", callFilter)
        // Design 114b B: the same roles, offered before calls are managed (the server reads it from `call_state` too).
        put("call_filter_early", callFilter)
        put("default_apps", read { AssistantSetup.defaultAppsChanged(context).isEmpty() })
        // Rows 15-16: design 110's two steps, read as its `map_links` report reads them (`play_services_links`,
        // `vibb_hosts`), so the server's reading of that report says the same.
        put("map_play_links", read { playLinksDone(MapLinksRuntime.holderLinksAllowed(context)) })
        put("map_vibb_links", read { vibbLinksDone(MapLinksRuntime.vibbHosts(context)) })
        // Design 119: the report's `login_browser.browser_role`, as the server reads it.
        put("vibb_browser", read { com.kidslauncher.mdm.logins.LinkGateRuntime.roleHeld(context) })
        // Design 139 §5.3: the app-op's own reading (`null` unreadable), as the report's `time_state.usage_access`.
        put("usage_access", com.kidslauncher.mdm.timerules.UsageAccess.granted(context))
    }

    /** §2.3's inputs from [policy]; [SetupInputs.NONE] before the first one. */
    fun inputs(context: Context, policy: PolicyResponse? = policy()): SetupInputs {
        policy ?: return SetupInputs.NONE
        val clock = clockFormatChoiceOf(policy.launcherUi).takeIf { it == "24" || it == "12" } ?: "system"
        return SetupInputs(
            mask = policy.quickControlsMask,
            clockFormat = clock,
            callsManaged = policy.callPolicy?.callsMode.let { it != null && it != "unmanaged" },
            // Design 132: `android_lock` means a Child PIN is stored (Android's lock is the only lock).
            androidLock = policy.kidLock != null,
            mapLinksOn = mapLinksOn(policy),
            mapAppInstalled = mapAppInstalled(reportedPackages(context)),
            linkGate = com.kidslauncher.mdm.logins.LinkGateRuntime.enabled(context),
            locationInterval = policy.locationPolicy?.mode == "interval",
            appGroups = com.kidslauncher.mdm.timerules.groupsLimit(policy.timePolicy),
        )
    }

    /** The packages the report's `installed_apps` lists ([controllablePackages], a hidden app included), so the server
     * reads `map_links`'s need from the same set (code QA M2). */
    private fun reportedPackages(context: Context): Collection<String> = try {
        controllablePackages(context.packageManager)
    } catch (e: Exception) {
        emptyList()
    }

    /** The parent steps ticked: the policy's and the phone's own not yet carried (§4.4). */
    fun ticked(context: Context, policy: PolicyResponse? = policy()): Set<String> =
        policy?.setup?.ticked.orEmpty().toSet() + SetupTicks.pending(context)

    /** What is missing now, by the shared rule. */
    fun missing(context: Context): List<SetupStep> {
        val policy = policy()
        return setupMissing(inputs(context, policy), readings(context, policy), ticked(context, policy))
    }

    /** N, as the PWA counts it for the same report. */
    fun count(context: Context): Int = setupCount(missing(context))

    /** §4.1 (code QA L9): the listener connected or disconnected - a sync only when that differs from the last state
     * seen (kept across processes), so a rebind at every process start asks for nothing. */
    fun listenerChanged(context: Context, connected: Boolean) {
        val prefs = context.createDeviceProtectedStorageContext().getSharedPreferences("setup_readings", Context.MODE_PRIVATE)
        val seen = if (prefs.contains("listener")) prefs.getBoolean("listener", false) else null
        if (seen == connected) return
        prefs.edit().putBoolean("listener", connected).apply()
        com.kidslauncher.mdm.push.SyncRunner.request(context, SETUP_SYNC_REASON)
    }

    @Volatile
    private var watching = false

    /**
     * §4.1: Data Saver's switch (`ACTION_RESTRICT_BACKGROUND_CHANGED`, sent only to registered receivers) asks for a
     * report. `RECEIVER_EXPORTED` as design 107's watch: a protected broadcast, and the receiver only asks for a sync.
     */
    fun watch(context: Context) {
        if (watching) return
        watching = true
        ContextCompat.registerReceiver(
            context.applicationContext,
            object : android.content.BroadcastReceiver() {
                override fun onReceive(context: Context, intent: android.content.Intent) {
                    com.kidslauncher.mdm.push.SyncRunner.request(context.applicationContext, SETUP_SYNC_REASON)
                }
            },
            android.content.IntentFilter(ConnectivityManager.ACTION_RESTRICT_BACKGROUND_CHANGED),
            ContextCompat.RECEIVER_EXPORTED,
        )
    }

    /** Whether the first policy has landed (the flow's conditional rows wait for it, QA M3). */
    fun policyKnown(): Boolean = policy() != null
}
