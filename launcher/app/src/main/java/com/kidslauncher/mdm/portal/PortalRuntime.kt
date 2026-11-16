package com.kidslauncher.mdm.portal

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiInfo
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.kidslauncher.mdm.logins.CaptivePortalPolicy
import com.kidslauncher.mdm.logins.portalPolicyOf
import com.kidslauncher.mdm.preferences.LauncherPreferences
import com.kidslauncher.mdm.server.BootClock
import com.kidslauncher.mdm.server.RestrictionsPause
import com.kidslauncher.mdm.server.ServerUrlVerdict
import com.kidslauncher.mdm.server.currentPolicyDecision
import com.kidslauncher.mdm.server.serverUrlVerdict
import kotlinx.serialization.builtins.ListSerializer
import java.io.File
import java.time.LocalDate
import java.util.concurrent.CopyOnWriteArraySet

private const val LOG_TAG = "PortalRuntime"
private const val PREFS = "portal_budget"
private const val KEY_BUDGET = "budget"
private const val KEY_SESSIONS = "sessions"
private const val KEY_BOOT = "records_boot"

/** Android's own probe URL: the binderless start's first load, which the portal intercepts and redirects (§3.2). */
const val ANDROID_PROBE_URL = "http://connectivitycheck.gstatic.com/generate_204"

/** The window tells the main process its session is in (`sessions/<opened_ms>.json`): an explicit broadcast to the
 * non-exported [PortalSessionReceiver]. */
const val ACTION_SESSION_DONE = "com.kidslauncher.mdm.portal.SESSION_DONE"

/** Vibb One tells the window to close now (the PIN lock, Part A off, a pause's end): a broadcast to this package only,
 * which the window's receiver takes as not exported (R2-M4). */
const val ACTION_CLOSE_WINDOW = "com.kidslauncher.mdm.portal.CLOSE_WINDOW"

/**
 * Design 102 Part A in Vibb One's main process: the day's code-free budget ([PortalBudget], in this process's own
 * preferences), the sessions the window sent back (the report's last 10), the reopen's records, the snapshot each
 * opening starts from, the captive Wi-Fi Home and the Wi-Fi page show ([wifi]), and the report.
 */
object PortalRuntime {
    private val listeners = CopyOnWriteArraySet<() -> Unit>()

    /** The captive Wi-Fi now: its network, whether it is `CAPTIVE_PORTAL` and not `VALIDATED`, and its SSID. */
    data class WifiNow(val network: Network?, val captive: Boolean, val validated: Boolean, val ssid: String?)

    @Volatile
    private var wifiNow = WifiNow(null, captive = false, validated = false, ssid = null)

    fun wifi(): WifiNow = wifiNow

    @Volatile
    private var appContext: Context? = null

    /** The pause ended ([RestrictionsPause.clear]): a window opened in it closes (its snapshot's end is the fallback). */
    fun pauseEnded() {
        appContext?.let { closeWindow(it, "pause") }
    }

    fun addListener(listener: () -> Unit) { listeners += listener }
    fun removeListener(listener: () -> Unit) { listeners -= listener }

    /** From `Application.initRest`: drops the reopen's records of an earlier boot (R3-L3), and watches the Wi-Fi
     * networks for Home's line and the Wi-Fi page. Never throws. */
    fun start(context: Context) {
        val app = context.applicationContext
        appContext = app
        try {
            val prefs = prefs(app)
            val boot = BootClock.bootCount()
            if (prefs.getInt(KEY_BOOT, Int.MIN_VALUE) != boot) {
                PortalFiles.revisit(app.noBackupFilesDir).delete()
                prefs.edit().putInt(KEY_BOOT, boot).apply()
            }
        } catch (e: Exception) {
            Log.w(LOG_TAG, "Couldn't drop the old records: ${e.javaClass.simpleName}")
        }
        try {
            val cm = app.getSystemService(ConnectivityManager::class.java)
            val request = NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI).build()
            cm.registerNetworkCallback(request, object : ConnectivityManager.NetworkCallback(ConnectivityManager.NetworkCallback.FLAG_INCLUDE_LOCATION_INFO) {
                override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                    val captive = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_CAPTIVE_PORTAL)
                    val validated = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
                    val ssid = (caps.transportInfo as? WifiInfo)?.ssid?.let(::cleanSsid)
                    set(WifiNow(network, captive && !validated, validated, ssid))
                }

                override fun onLost(network: Network) {
                    if (wifiNow.network == network) set(WifiNow(null, captive = false, validated = false, ssid = null))
                }
            }, Handler(Looper.getMainLooper()))
        } catch (e: Exception) {
            Log.w(LOG_TAG, "Couldn't watch the Wi-Fi: ${e.javaClass.simpleName}")
        }
    }

    private fun set(now: WifiNow) {
        if (now == wifiNow) return
        wifiNow = now
        listeners.forEach { it() }
    }

    /** An SSID as Android gives it, quotes off; `null` when unknown (`<unknown ssid>`, no Location). */
    fun cleanSsid(raw: String?): String? {
        val s = raw?.trim()?.removeSurrounding("\"") ?: return null
        return s.takeIf { it.isNotEmpty() && it != UNKNOWN_SSID }
    }

    private const val UNKNOWN_SSID = "<unknown ssid>"

    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun policy(): CaptivePortalPolicy = portalPolicyOf(runCatching { currentPolicyDecision().policy }.getOrNull())

    /** The day's budget now (the date, the policy's grant), stored again when it moved. */
    @Synchronized
    fun budget(context: Context, policy: CaptivePortalPolicy = policy()): PortalBudget {
        val prefs = prefs(context)
        val stored = prefs.getString(KEY_BUDGET, null)?.let { runCatching { PortalJson.decodeFromString(PortalBudgetJson.serializer(), it).budget() }.getOrNull() }
        val budget = budgetToday(stored, LocalDate.now(), policy.grant)
        if (budget != stored) save(context, budget)
        return budget
    }

    private fun save(context: Context, budget: PortalBudget) {
        prefs(context).edit().putString(KEY_BUDGET, PortalJson.encodeToString(PortalBudgetJson.serializer(), PortalBudgetJson.of(budget))).commit()
    }

    /** An opening charged at the entry, before the start (a killed window still counts). */
    @Synchronized
    fun charge(context: Context, approved: String) {
        save(context, chargeOpening(budget(context), approved))
    }

    /** One window at a time (R3-L5): read from the session files, not from memory. */
    fun windowOpen(context: Context): Boolean {
        val minutes = policy().minutes
        val now = System.currentTimeMillis()
        return PortalFiles.readSessions(context.noBackupFilesDir).any { sessionOpen(it, now, minutes) }
    }

    /** The approval's inputs now (§3.2), but the network's and the check's, which the entry reads itself. */
    fun approveInput(context: Context, kind: PortalKind, networkOk: Boolean, passes: Boolean): ApproveInput {
        val policy = policy()
        val budget = budget(context, policy)
        return ApproveInput(
            kind = kind,
            networkOk = networkOk,
            passes = passes,
            locked = com.kidslauncher.mdm.lock.PinLockRuntime.chromeLocked,
            windowOpen = windowOpen(context),
            requireCode = policy.requireCode,
            pause = RestrictionsPause.isActive(),
            grantLeft = grantLeft(budget),
            budgetLeft = budgetLeft(budget, policy.dayOpenings, policy.dayMinutes, policy.minutes),
        )
    }

    /** The Wi-Fi page's row and Home's line for the connected Wi-Fi (§3.6). */
    fun row(context: Context): WifiPortalRow {
        val policy = policy()
        if (!policy.enabled) return WifiPortalRow.NONE
        val budget = budget(context, policy)
        val now = wifiNow
        return wifiRowState(
            partOn = true,
            captive = now.captive,
            requireCode = policy.requireCode,
            budgetLeft = budgetLeft(budget, policy.dayOpenings, policy.dayMinutes, policy.minutes),
            grantLeft = grantLeft(budget),
            revisit = now.network != null && !now.captive && revisitRecord(context, now) != null,
        )
    }

    /** Today's record matching the connected Wi-Fi ([revisitMatches]). */
    fun revisitRecord(context: Context, now: WifiNow = wifiNow): RevisitRecord? {
        val records = readRecords(context)
        return records.firstOrNull {
            revisitMatches(it, LocalDate.now().toString(), BootClock.bootCount(), now.network?.networkHandle, now.ssid)
        }
    }

    private fun readRecords(context: Context): List<RevisitRecord> = runCatching {
        PortalJson.decodeFromString(ListSerializer(RevisitRecordJson.serializer()), PortalFiles.revisit(context.noBackupFilesDir).readText())
            .map { it.record() }
            .filter { it.date == LocalDate.now().toString() }
    }.getOrDefault(emptyList())

    /** Part A off: every record goes (§3.8), and an open window closes. */
    fun partOff(context: Context) {
        PortalFiles.revisit(context.noBackupFilesDir).delete()
        closeWindow(context, "off")
    }

    /** Vibb One → the window: close now (R2-M4). Always sent (code QA M2: a pause window outlives its minutes): this
     * package only, to a receiver that isn't exported; no window running, nobody hears it. */
    fun closeWindow(context: Context, why: String) {
        try {
            context.sendBroadcast(Intent(ACTION_CLOSE_WINDOW).setPackage(context.packageName).putExtra("why", why))
        } catch (e: Exception) {
            Log.w(LOG_TAG, "Couldn't tell the window to close: ${e.javaClass.simpleName}")
        }
    }

    /** The snapshot for one opening, written before the window starts. */
    fun writeSnapshot(context: Context, snapshot: PortalSnapshot) {
        PortalFiles.write(PortalFiles.snapshot(context.noBackupFilesDir), PortalJson.encodeToString(PortalSnapshot.serializer(), snapshot))
    }

    /** The phone's own server as one operator of the https check: a public `https://` address only (R3-L4). */
    fun serverCheckUrl(): String? = runCatching { LauncherPreferences.mdm().serverUrl() }.getOrNull()
        ?.takeIf { serverUrlVerdict(it) == ServerUrlVerdict.HTTPS }

    /** The snapshot's fields this process knows: the filter, the pause, the lists, the server's check. */
    fun snapshotFor(
        context: Context,
        kind: PortalKind,
        start: String,
        approved: String,
        url: String,
        recordedSite: String?,
        network: String?,
        userAgent: String?,
    ): PortalSnapshot {
        val policy = policy()
        val budget = budget(context, policy)
        val decision = runCatching { currentPolicyDecision().policy }.getOrNull()
        val pause = RestrictionsPause.isActive()
        return PortalSnapshot(
            openedMs = System.currentTimeMillis(),
            kind = kind.wire,
            start = start,
            approved = approved,
            url = url,
            recordedSite = recordedSite,
            network = network,
            userAgent = userAgent,
            minutes = policy.minutes,
            budgetLeftMs = (budgetMs(budget, policy.dayMinutes, policy.minutes) - budget.ms).coerceAtLeast(0L),
            pauseEndMs = if (pause) LauncherPreferences.mdm().restrictionsPausedUntil() else null,
            filterOn = !pause && (decision?.vpnFilterEnabled ?: true),
            blocklistPath = File(context.filesDir, "dns_blocklist.tsv").absolutePath,
            spotify = runCatching { com.kidslauncher.mdm.music.musicSpotify(decision?.music) }.getOrDefault(false),
            serverCheckUrl = serverCheckUrl(),
        )
    }

    /**
     * The sessions the window sent back, folded in: each ended file into the report's last 10 and, for a code-free
     * opening, its time into the budget; a working sign-in's first page into the reopen's records (§3.8); a file that
     * never ended within its minutes counts the whole minutes (`lost`). Called by [PortalSessionReceiver] and before
     * each report.
     */
    @Synchronized
    fun fold(context: Context) {
        val noBackup = context.noBackupFilesDir
        val policy = policy()
        val now = System.currentTimeMillis()
        for (file in PortalFiles.readSessions(noBackup)) {
            if (sessionOpen(file, now, policy.minutes)) continue
            val ended = if (file.closedMs == null) {
                file.copy(closedMs = file.pauseEndMs ?: (file.openedMs + policy.minutes * 60_000L), outcome = file.outcome ?: "lost")
            } else {
                file
            }
            val codeFree = ended.approved == "none" || ended.approved == "grant"
            if (codeFree) save(context, chargeTime(budget(context, policy), (ended.closedMs!! - ended.openedMs)))
            addSession(context, ended)
            if (ended.kind == PortalKind.SIGN_IN.wire && (ended.outcome == "validated" || ended.outcome == "passes")) {
                keepRecord(context, ended)
            }
            PortalFiles.session(noBackup, file.openedMs).delete()
        }
    }

    private fun addSession(context: Context, ended: PortalSessionFile) {
        val list = sessions(context).filter { it.openedMs != ended.openedMs } + PortalSession(
            openedMs = ended.openedMs,
            closedMs = ended.closedMs,
            network = ended.network,
            firstHost = ended.firstHost,
            outcome = ended.outcome ?: "closed",
            visited = ended.visited.take(20),
            visitedCount = maxOf(ended.visitedCount, ended.visited.size),
            blockedHosts = ended.blockedHosts.take(10),
            blockedFrames = ended.blockedFrames.take(10),
            start = ended.start,
            approved = ended.approved,
        )
        prefs(context).edit().putString(KEY_SESSIONS, PortalJson.encodeToString(ListSerializer(PortalSession.serializer()), list.takeLast(10))).commit()
    }

    private fun sessions(context: Context): List<PortalSession> = runCatching {
        PortalJson.decodeFromString(ListSerializer(PortalSession.serializer()), prefs(context).getString(KEY_SESSIONS, "[]")!!)
    }.getOrDefault(emptyList())

    /** The record (§3.8): the first page's URL and only its site, the network's identity read now (the session's end),
     * with the boot count; never logged. */
    private fun keepRecord(context: Context, ended: PortalSessionFile) {
        val url = ended.firstUrl ?: return
        val site = ended.firstSite ?: return
        val now = wifiNow
        val record = RevisitRecord(
            url = url,
            site = site,
            ssid = now.ssid ?: ended.network,
            handle = now.network?.networkHandle ?: return,
            bootCount = BootClock.bootCount(),
            date = LocalDate.now().toString(),
        )
        val records = readRecords(context).filter { it.handle != record.handle && (it.ssid == null || it.ssid != record.ssid) } + record
        PortalFiles.write(
            PortalFiles.revisit(context.noBackupFilesDir),
            PortalJson.encodeToString(ListSerializer(RevisitRecordJson.serializer()), records.map(RevisitRecordJson::of)),
        )
    }

    /** Who handles Android's sign-in intent now: Vibb's entry, Android's own app (Part A off), or another. */
    fun handler(context: Context): String = try {
        val resolved = context.packageManager.resolveActivity(Intent(ConnectivityManager.ACTION_CAPTIVE_PORTAL_SIGN_IN), PackageManager.MATCH_DEFAULT_ONLY)
        when (resolved?.activityInfo?.packageName) {
            context.packageName -> "vibb"
            null -> "other"
            else -> if (resolved.activityInfo.packageName.contains("captiveportallogin")) "system" else "other"
        }
    } catch (e: Exception) {
        "other"
    }

    /** The report's `captive_portal` (§6.2); `null` when it can't be read. */
    fun report(context: Context): CaptivePortalReport? = try {
        fold(context)
        val budget = budget(context)
        CaptivePortalReport(
            handler = handler(context),
            parentCodeSet = com.kidslauncher.mdm.server.ParentPin.status(context).configured,
            today = PortalToday(openings = budget.openings, ms = budget.ms, grantUsed = budget.grantUsed),
            sessions = sessions(context),
        )
    } catch (e: Exception) {
        Log.w(LOG_TAG, "The report failed: ${e.javaClass.simpleName}")
        null
    }

    /** The entry component, enabled with Part A (off: Android's own handler is the only one; `DONT_KILL_APP`). */
    fun setEntryEnabled(context: Context, on: Boolean) {
        val component = ComponentName(context, CaptivePortalEntry::class.java)
        val state = if (on) PackageManager.COMPONENT_ENABLED_STATE_ENABLED else PackageManager.COMPONENT_ENABLED_STATE_DISABLED
        try {
            if (context.packageManager.getComponentEnabledSetting(component) != state) {
                context.packageManager.setComponentEnabledSetting(component, state, PackageManager.DONT_KILL_APP)
                if (!on) partOff(context)
            }
        } catch (e: Exception) {
            Log.w(LOG_TAG, "Couldn't switch the sign-in entry: ${e.javaClass.simpleName}")
        }
    }
}

/** [PortalBudget] as stored. */
@kotlinx.serialization.Serializable
internal data class PortalBudgetJson(
    val date: String,
    val openings: Int,
    val ms: Long,
    val grantUsed: Int,
    val grantOpenings: Int,
    val grantSeen: Long,
) {
    fun budget() = PortalBudget(date, openings, ms, grantUsed, grantOpenings, grantSeen)

    companion object {
        fun of(b: PortalBudget) = PortalBudgetJson(b.date, b.openings, b.ms, b.grantUsed, b.grantOpenings, b.grantSeen)
    }
}

/** [RevisitRecord] as stored in `revisit.json`. */
@kotlinx.serialization.Serializable
internal data class RevisitRecordJson(
    val url: String,
    val site: String,
    val ssid: String? = null,
    val handle: Long,
    val bootCount: Int,
    val date: String,
) {
    fun record() = RevisitRecord(url, site, ssid, handle, bootCount, date)

    companion object {
        fun of(r: RevisitRecord) = RevisitRecordJson(r.url, r.site, r.ssid, r.handle, r.bootCount, r.date)
    }
}
