package com.kidslauncher.mdm.places

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.location.LocationRequest
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.ScanResult
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.os.PowerManager
import android.os.SystemClock
import android.telephony.CellIdentityGsm
import android.telephony.CellIdentityLte
import android.telephony.CellIdentityNr
import android.telephony.CellIdentityWcdma
import android.telephony.CellInfo
import android.telephony.CellInfoGsm
import android.telephony.CellInfoLte
import android.telephony.CellInfoNr
import android.telephony.CellInfoWcdma
import android.telephony.TelephonyManager
import android.util.AtomicFile
import android.util.Log
import com.kidslauncher.mdm.BuildConfig
import com.kidslauncher.mdm.preferences.LauncherPreferences
import com.kidslauncher.mdm.server.BootClock
import com.kidslauncher.mdm.server.DeviceCredentials
import com.kidslauncher.mdm.server.LocateCommands
import com.kidslauncher.mdm.server.QuickControls
import com.kidslauncher.mdm.server.Secrets
import com.kidslauncher.mdm.server.ServerJson
import com.kidslauncher.mdm.server.TrustedClock
import com.kidslauncher.mdm.server.createMdmApi
import com.kidslauncher.mdm.server.currentPolicyDecision
import com.kidslauncher.mdm.zones.CONFIRM_DELAY_MS
import com.kidslauncher.mdm.zones.Motion
import com.kidslauncher.mdm.zones.MotionGate
import com.kidslauncher.mdm.zones.ZoneWatch
import com.kidslauncher.mdm.zones.distanceM
import com.kidslauncher.mdm.zones.withFixCheck
import java.io.File
import java.security.SecureRandom
import java.time.LocalDate
import java.util.Base64
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import javax.crypto.AEADBadTagException
import kotlin.coroutines.cancellation.CancellationException
import kotlin.coroutines.resume
import kotlin.math.abs
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.builtins.ListSerializer
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody

private const val LOG_TAG = "WifiPlaces"
private const val PREFS = "wifi_places"
private const val KEY_HMAC = "hmac_key_v1"
private const val AAD_HMAC = "wifi_places/hmac_key_v1"
private const val KEY_BUDGET = "teach_budget_v1"
private const val KEY_COUNTS = "loo_v1"
private const val KEY_LAST_GPS_TRY = "last_gps_try_ms"

/** Design 138 D7 (L2): the struck confirmations, in their own prefs file - a Wi-Fi places clear keeps them. */
private const val STRUCK_PREFS = "zone_links"
private const val KEY_STRUCK = "confirm_struck_v1"

/** Design 131: the last satellite fix ([LastGnss]), kept across a reboot for the jump gate and the satellite check. */
private const val KEY_LAST_GNSS = "last_gnss_v1"

/** Design 131 (code QA M1): the boot and the saved networks' count at the last teach - `boot:count`. */
private const val KEY_SAVED_NETWORKS = "saved_networks_v1"

/** Design 125 step 2: the policy's switch as the last fresh policy said, the wait after an answer, a 409's "off". */
private const val KEY_APPLE_ON = "apple_on"
private const val KEY_APPLE_WAIT = "apple_wait_until_ms"
private const val KEY_APPLE_OFF = "apple_off"

/** Design 125b C5: the cell wait - set only by a cells request's answers, so the cell brake never stops a Wi-Fi ask
 * and a Wi-Fi wait never stops a cell ask. A 409 from either is [KEY_APPLE_OFF] (the switch is one). */
private const val KEY_APPLE_CELL_WAIT = "apple_cell_wait_until_ms"
private const val FILE = "wifi_places_v1"
private const val AAD_TABLE = "wifi_places_v1"
private const val ACTION_TEACH = "com.kidslauncher.mdm.action.WIFI_PLACES_TEACH"

/** The teaching fix's alarm; not BackstopAlarm's 7, the fences' 8, the confirm's 9, the series' 10, the update fence's
 * 11 or the self-update's 12. */
private const val TEACH_REQUEST_CODE = 13

/** A scan waits this long for its results (D13). */
private const val SCAN_WAIT_MS = 8_000L

/** The heartbeat's, a crossing's and the teaching fix's GPS wait (D11, D14). */
private const val GPS_MS = 15_000L

/** `locate`'s long wait (D14). */
private const val LOCATE_MS = 60_000L

/** After the first fix the stream waits at most this much longer for one that teaches (code QA M3). */
private const val TEACH_WAIT_MS = 5_000L

/** The teaching fix holds the CPU at most this long: a scan, 15 s of GPS, a re-scan. */
private const val TEACH_WAKELOCK_MS = 45_000L

/** A cached serving cell older than this asks the modem once more (D10). */
private const val CELL_STALE_MS = 60_000L
private const val CELL_WAIT_MS = 3_000L

/** Design 134 D25: the location-info callback answers at once on registration; this long at most. */
private const val LINK_CALLBACK_MS = 1_000L

/**
 * Wi-Fi places, learned on the phone (design 125 step 1; rules in `PlacesPlan.kt`). With Google's location accuracy off
 * (the `network` provider off) a fresh-fix attempt scans Wi-Fi while GPS starts ([attempt], D13/D14): a learned fix
 * that stands alone is reported as `vibb_wifi`, every own GPS fix and every passive `gps` fix ([offerPassive]) teaches
 * the APs heard with it, and 2 min after a motion fire a teaching fix runs ([armTeach], D11). With the `network`
 * provider on nothing here scans or looks up (H1); only passive fixes teach, against the system's own cached results.
 *
 * Privacy (D18, D19): the table is one file in credential-encrypted storage, sealed whole under the secrets key
 * (`Secrets.box`, AAD `wifi_places_v1`); rows are keyed by HMAC under a per-install key, sealed under the same key in CE
 * prefs `wifi_places`; no SSID, BSSID or cell id is kept or logged. [clear] at location Off, unmanaged, a new
 * enrollment, a broken Keystore key and a table or key that doesn't open with a healthy one. A transient Keystore error
 * skips that lookup or teach and keeps the table.
 */
object WifiPlaces {
    /**
     * What a sync's attempt knows ([motion], [movedAtElapsedMs] - design 124's flags) and, for the heartbeat's 4-h cap
     * try with `motion` `STILL`, the [vouched] fix a zone link may renew (D12); while `motion` is `STILL`
     * ([appleStillMotion]), the [stillVouch] an Apple fix may only renew (design 125 A9, H2). The attempt answers in
     * [renewed] (the vouched fix re-sent with `still: true`) and [notFresh] (a 2-min fallback fix or a cell fix:
     * reported, never fresh, M2, design 125b C9.4). [lastFresh]: the last fresh fix, whatever the motion - a cell fix
     * inside its circle while it is under 4 h old is not reported (design 125b C9.3). Design 134: [heartbeat] - the
     * heartbeat's own `FIX`; [mayWithhold] - after a move an inconclusive try may be withheld (D4, `mayWithhold`); the
     * attempt answers in [inconclusive] (its last scan said nothing about the place, D2) and [withheld] (nothing
     * reported: the scan was inconclusive and no fresh fix came, D4). [vouchFix]: the last fresh fix, the fingerprint's
     * vouch (D9's veto, D11's distances); the attempt answers in [mismatch] (revision 3, D15: the cap try's conclusive
     * scan clearly isn't the print's place - `STILL` ends) and [material] (D8: what a print may be taken from).
     */
    class Ask(
        val motion: Motion,
        val movedAtElapsedMs: Long,
        val vouched: Location? = null,
        val stillVouch: Location? = null,
        val lastFresh: Location? = null,
        val heartbeat: Boolean = false,
        val mayWithhold: Boolean = false,
        val vouchFix: com.kidslauncher.mdm.zones.QueuedFix? = null,
        val stillCeilingPassed: Boolean = false,
    ) {
        @Volatile var mismatch: MismatchClause? = null

        /** D9.5: something in the attempt says elsewhere (a Wi-Fi fix outside the vouch, an impossible jump, a cell fix
         * past it + 2 km). */
        @Volatile var elsewhere = false

        @Volatile internal var material: PrintMaterial? = null

        @Volatile var renewed = false

        /** What renewed it: a zone link (D12), an Apple fix within the vouch (A9), or the connected AP (design 134
         * revision 4, D20). */
        @Volatile var renewedBy = Renewal.LINK

        /** Design 134 revision 4: the connected router's answer, once asked in this attempt (D20, D23). */
        @Volatile internal var witness: StillWitness? = null

        /** D20.1: the attempt's connected AP as a witness reads it - the vouch's link when its fix becomes the vouch. */
        @Volatile internal var linkAp: LinkAp? = null

        @Volatile var notFresh = false

        @Volatile var inconclusive: Inconclusive? = null

        @Volatile var withheld = false

        /** Design 138 D6: the attempt's Wi-Fi teach of a learned link, run at its end only when nothing disagreed. */
        @Volatile internal var teachLater: (() -> Unit)? = null
    }

    private val lock = Any()

    /** Guarded by [lock]: the open table, its keys, the clear generation (a clear during an attempt wins). */
    private var rows: List<PlaceRow>? = null
    private var keys: PlaceKeys? = null
    private var generation = 0

    /** The previous scan's levels by key (D9's stale guard), memory only. */
    @Volatile private var readings: Map<String, Reading> = emptyMap()

    /** The last learned fix of >= 3 networks, for D15's contradiction. Memory only. */
    @Volatile private var lastLearned: LearnedFix? = null

    /** Design 134 revision 4 (D20.1): the connected AP read at the vouch's fix - memory only, never stored or logged. A
     * new process starts `MOVED`, so none is lost that a still phone could use. */
    @Volatile private var vouchLink: VouchLink? = null

    /** Design 138 D7: strikes waiting for a second teaching fix (by elapsed realtime). Guarded by [lock]; memory only. */
    private var pendingStrikes: Map<StrikeKey, Long> = emptyMap()

    /** Our own attempts and teaching fixes running: a passive fix then is ours, taught by the attempt itself, and a
     * teaching alarm waits for the next fire. */
    private val ownTeaching = AtomicInteger(0)

    /** Scan refusals since the process started, by reason - counts only (code QA L6). Guarded by itself. */
    private val refusals = sortedMapOf<String, Int>()

    @Volatile private var moving: MovingSsids? = null

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val executor = Executors.newSingleThreadExecutor()

    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun file(context: Context) = AtomicFile(File(context.applicationContext.noBackupFilesDir, FILE))

    private fun moving(context: Context): MovingSsids = moving ?: MovingSsids.parse(
        context.assets.open("places/moving_ssids.json").use { it.readBytes().toString(Charsets.UTF_8) },
    ).also { moving = it }

    /** The `network` provider is off (Google's location accuracy off, or no GMS): Vibb may scan (D13, H1). */
    fun networkOff(lm: LocationManager): Boolean = try {
        !lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
    } catch (e: Exception) {
        false
    }

    // --- The store (D18, D19) ---

    /** A snapshot of the open table with its keys and generation, or `null` when the Keystore can't open it now. */
    private class Store(val rows: List<PlaceRow>, val keys: PlaceKeys, val generation: Int)

    private fun open(context: Context): Store? = synchronized(lock) {
        val k = keys ?: loadKey(context) ?: return null
        keys = k
        val r = rows ?: readTable(context) ?: return null
        rows = r
        Store(r, k, generation)
    }

    /** The install's HMAC key, sealed under the secrets key; a new one (and no table) when there is none. */
    private fun loadKey(context: Context): PlaceKeys? {
        val p = prefs(context)
        val stored = p.getString(KEY_HMAC, null)
        return try {
            if (stored != null) {
                PlaceKeys(Secrets.box.open(AAD_HMAC, Base64.getDecoder().decode(stored)))
            } else {
                // A table without its key is noise: dropped before the first key is made.
                file(context).delete()
                val secret = ByteArray(32).also { SecureRandom().nextBytes(it) }
                val sealed = Base64.getEncoder().encodeToString(Secrets.box.seal(AAD_HMAC, secret))
                if (p.edit().putString(KEY_HMAC, sealed).commit()) PlaceKeys(secret) else null
            }
        } catch (e: Exception) {
            notOpened(context, e)
            null
        }
    }

    private fun readTable(context: Context): List<PlaceRow>? {
        val f = file(context)
        if (!f.baseFile.exists()) return emptyList()
        return try {
            decodeTable(Secrets.box.open(AAD_TABLE, f.readFully())) ?: emptyList<PlaceRow>().also { Log.i(LOG_TAG, "table version dropped") }
        } catch (e: Exception) {
            notOpened(context, e)
            null
        }
    }

    /** D19 and code QA M2: a blob that can't be opened with a healthy key (a bad tag, a broken encoding) is cleared
     * (`unreadable`), a broken key clears (`keybroken`), anything else is transient - skipped, kept. Under [lock]. */
    private fun notOpened(context: Context, e: Exception) {
        when {
            e is AEADBadTagException || e is IllegalArgumentException -> clearLocked(context, "unreadable")
            Secrets.box.keyBroken() -> clearLocked(context, "keybroken")
            else -> Log.w(LOG_TAG, "table not opened now: ${e.javaClass.simpleName}")
        }
    }

    /** The table after [change] on a store opened at [generation]; written sealed (AtomicFile) only when its contents
     * changed (code QA L4), dropped when a clear came meanwhile. The table in memory follows only a written one, so a
     * failed write is tried again by the next save. `true` when the table on disk holds the change (or needed none). */
    private fun save(context: Context, generation: Int, change: (List<PlaceRow>) -> List<PlaceRow>): Boolean = synchronized(lock) {
        val current = rows ?: return false
        if (generation != this.generation) return false
        val next = change(current)
        if (next == current) return true
        val f = file(context)
        val sealed = try {
            Secrets.box.seal(AAD_TABLE, encodeTable(next))
        } catch (e: Exception) {
            Log.w(LOG_TAG, "table not sealed now: ${e.javaClass.simpleName}")
            return false
        }
        val out = try {
            f.startWrite()
        } catch (e: Exception) {
            Log.w(LOG_TAG, "table not written: ${e.javaClass.simpleName}")
            return false
        }
        try {
            out.write(sealed)
            f.finishWrite(out)
            rows = next
            true
        } catch (e: Exception) {
            f.failWrite(out)
            Log.w(LOG_TAG, "table not written: ${e.javaClass.simpleName}")
            false
        }
    }

    /**
     * D19: the table, its key and the counts go - location Off ([reason] `off`), unmanaged or a new enrollment
     * (`unenrol`), a broken Keystore key (`keybroken`), a blob a healthy key can't open (`unreadable`). No Keystore
     * call; cheap when there is nothing.
     */
    fun clear(context: Context, reason: String) = synchronized(lock) { clearLocked(context, reason) }

    private fun clearLocked(context: Context, reason: String) {
        val p = prefs(context)
        val f = file(context)
        val had = rows != null || keys != null || f.baseFile.exists() || p.all.isNotEmpty()
        generation++
        rows = null
        keys = null
        readings = emptyMap()
        vouchLink = null
        lastLearned = null
        // Design 134 P4: the fingerprint and its ring go with the table.
        StillPrints.clear(context, reason)
        pendingStrikes = emptyMap()
        // Design 138 D7 (L2): the struck ids outlive every clear but a new enrolment's.
        if (reason == "unenrol") struckPrefs(context).edit().clear().commit()
        if (!had) return
        f.delete()
        p.edit().clear().commit()
        Log.i(LOG_TAG, "cleared ($reason)")
    }

    // --- Reading the radio (D10, D13, D17) ---

    /** What one read of the radio heard: the Wi-Fi results, the connected network, the usable cells (design 125b C2:
     * registered first, at most 12) and, for a scan of an attempt, which results answer its request (design 134 D1,
     * D2; `null` without a scan); how the connection read (design 134 revision 4, [link]). Never stored. */
    private class Snapshot(
        val results: List<ScanAp>,
        val connected: Connected?,
        val cells: List<Heard>,
        val answer: ScanAnswer? = null,
        val request: ScanRequest? = null,
        val link: LinkRead = if (connected != null) LinkRead.READ else LinkRead.NONE,
    )

    /** A connection read: the connected network and how its BSSID read (design 134 revision 4, D25). */
    private class ConnRead(val connected: Connected?, val read: LinkRead)

    /** A scan's results and its request (design 134 D1). */
    private class Scan(val results: List<ScanAp>, val request: ScanRequest)

    /** Design 134 D8: a conclusive answer's usable results by their keys, the table's settled keys and the cells - what
     * a print may be taken from once the sync knows the fix. Memory only, for the attempt's sync. */
    internal class PrintMaterial(val heard: List<Heard>, val settled: Set<String>, val cells: List<Heard>)

    private fun wifi(context: Context) = context.applicationContext.getSystemService(WifiManager::class.java)

    /** Code QA L6: a scan refused - counts only, by reason. */
    private fun refused(reason: String) {
        val line = synchronized(refusals) {
            refusals[reason] = (refusals[reason] ?: 0) + 1
            refusals.entries.joinToString(" ") { "${it.key}=${it.value}" }
        }
        Log.i(LOG_TAG, "scan refusals $line")
    }

    /** The cached results. Debug builds only ([PlacesDebugHooks], design 131 §7, design 134 §7): a made-up scan, heard
     * now or the hook's age ago, in place of the radio's - the emulator's own Wi-Fi is open and hears no other network. */
    private fun cached(context: Context): List<ScanAp> = PlacesDebugHooks.madeUpResults()?.let { list ->
        val heardAt = SystemClock.elapsedRealtime() - PlacesDebugHooks.resultsAgeMs()
        list.map { it.copy(elapsedMs = heardAt) }
    } ?: try {
        wifi(context)?.scanResults.orEmpty().map(::scanAp)
    } catch (e: SecurityException) {
        refused("permission")
        emptyList()
    } catch (e: Exception) {
        emptyList()
    }

    private fun scanAp(r: ScanResult): ScanAp = ScanAp(
        bssid = r.BSSID.orEmpty(),
        ssid = r.wifiSsid?.toString()?.removeSurrounding("\""),
        mld = r.apMldMacAddress?.toString(),
        frequencyMhz = r.frequency,
        rssi = r.level,
        elapsedMs = r.timestamp / 1000,
    )

    /** D13: the cached results when D9's fresh rule holds (>= 3 usable within 30 s, after the last move); else one
     * `startScan` and its results within [SCAN_WAIT_MS] - refused or no answer: the cache (the 2-min fallback). Design
     * 134 D1: with the request's elapsed time and how it went, so the results that answer it can be told. */
    private suspend fun scanNow(context: Context, movedAtElapsedMs: Long): Scan {
        val now = SystemClock.elapsedRealtime()
        val before = cached(context)
        val list = moving(context)
        val fresh = before.count { now - it.elapsedMs in 0..SCAN_FRESH_MS && it.elapsedMs > movedAtElapsedMs && usableAp(it.bssid, it.ssid, list) }
        if (fresh >= SCAN_RESULTS_MIN) return Scan(before, ScanRequest(now, ScanAsk.CACHE))
        val wm = wifi(context) ?: return Scan(before, ScanRequest(now, ScanAsk.REFUSED))
        var ask = ScanAsk.ANSWERED
        val requestAt = SystemClock.elapsedRealtime()
        val updates = Channel<Unit>(Channel.CONFLATED)
        val callback = object : WifiManager.ScanResultsCallback() {
            override fun onScanResultsAvailable() {
                updates.trySend(Unit)
            }
        }
        try {
            wm.registerScanResultsCallback(executor, callback)
            // A debug build's hook may count the scan as throttled (design 134 §7); a release build always asks.
            @Suppress("DEPRECATION")
            when {
                PlacesDebugHooks.scanRefused() || !wm.startScan() -> {
                    val on = wm.isWifiEnabled
                    refused(if (on) "throttled" else "wifioff")
                    ask = if (on) ScanAsk.REFUSED else ScanAsk.WIFI_OFF
                }
                // Code QA M2: a callback whose results are all older than the request (another app's scan, a cache
                // refresh) isn't our answer - wait on within the 8 s for one that is. An empty cache after a callback is
                // an honest "nothing heard" (the cabin). A callback came but no answer in time: old, not unanswered.
                else -> {
                    var called = false
                    var answered = false
                    while (!answered) {
                        val left = requestAt + SCAN_WAIT_MS - SystemClock.elapsedRealtime()
                        if (left <= 0 || withTimeoutOrNull(left) { updates.receive() } == null) break
                        called = true
                        val heard = cached(context)
                        answered = heard.isEmpty() || heard.any { it.elapsedMs > requestAt }
                    }
                    if (!called) {
                        refused("unanswered")
                        ask = ScanAsk.UNANSWERED
                    }
                }
            }
        } catch (e: SecurityException) {
            refused("permission")
            ask = ScanAsk.REFUSED
        } catch (e: Exception) {
            refused("failed")
            ask = ScanAsk.REFUSED
        } finally {
            runCatching { wm.unregisterScanResultsCallback(callback) }
        }
        return Scan(cached(context), ScanRequest(requestAt, ask))
    }

    /** [info] as the connected network ([unmetered] from its network's capabilities); `null` without a readable BSSID. */
    private fun connectionOf(info: WifiInfo, unmetered: Boolean): Connected? {
        val bssid = connectedBssid(info.bssid) ?: return null
        val ssid = info.ssid?.removeSurrounding("\"")?.takeIf { it != WifiManager.UNKNOWN_SSID }
        // Design 138 (QA L7): Android quotes a UTF-8 name; an unquoted one is hex - never reported or confirmed.
        val hexName = ssid != null && info.ssid?.let { it.length >= 2 && it.startsWith("\"") && it.endsWith("\"") } != true
        val security = when (info.currentSecurityType) {
            WifiInfo.SECURITY_TYPE_PSK -> LinkSecurity.PSK
            WifiInfo.SECURITY_TYPE_SAE -> LinkSecurity.SAE
            WifiInfo.SECURITY_TYPE_EAP, WifiInfo.SECURITY_TYPE_EAP_WPA3_ENTERPRISE, WifiInfo.SECURITY_TYPE_EAP_WPA3_ENTERPRISE_192_BIT,
            WifiInfo.SECURITY_TYPE_PASSPOINT_R1_R2, WifiInfo.SECURITY_TYPE_PASSPOINT_R3,
            -> LinkSecurity.EAP
            else -> LinkSecurity.OTHER
        }
        val mld = try {
            info.apMldMacAddress?.toString()
        } catch (e: Exception) {
            null
        }
        return Connected(bssid, ssid, info.rssi, info.frequency, unmetered, security, info.networkId, mld, hexName)
    }

    /** The connection as `WifiManager.getConnectionInfo()` gives it (deprecated in 31, still served on 14-17 with fine
     * and background location and Location on - design 134 D25), and whether the active network carries Wi-Fi when it
     * gives no readable BSSID ([LinkRead.UNREADABLE]). */
    private fun readConnection(context: Context): ConnRead {
        PlacesDebugHooks.madeUpConnected()?.let { return ConnRead(it, LinkRead.READ) }
        return try {
            @Suppress("DEPRECATION")
            val info = wifi(context)?.connectionInfo
            val cm = context.getSystemService(ConnectivityManager::class.java)
            val caps = cm?.activeNetwork?.let { cm.getNetworkCapabilities(it) }
            val onWifi = caps != null && caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
            val unmetered = onWifi && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
            val c = info?.let { connectionOf(it, unmetered) }
            ConnRead(c, if (c != null) LinkRead.READ else if (onWifi) LinkRead.UNREADABLE else LinkRead.NONE)
        } catch (e: Exception) {
            ConnRead(null, LinkRead.NONE)
        }
    }

    /**
     * Design 134 D25: [readConnection], and when it gave no readable BSSID on Wi-Fi, one `NetworkCallback` with
     * `FLAG_INCLUDE_LOCATION_INFO` (API 31; `getNetworkCapabilities()` alone always redacts the `WifiInfo` in its
     * `TransportInfo`) for the Wi-Fi network, up to [LINK_CALLBACK_MS], then unregistered.
     */
    private suspend fun connectedNow(context: Context): ConnRead {
        val sync = readConnection(context)
        if (sync.read != LinkRead.UNREADABLE) return sync
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return sync
        val got = CompletableDeferred<Connected>()
        val callback = object : ConnectivityManager.NetworkCallback(ConnectivityManager.NetworkCallback.FLAG_INCLUDE_LOCATION_INFO) {
            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                val info = caps.transportInfo as? WifiInfo ?: return
                connectionOf(info, caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED))?.let { got.complete(it) }
            }
        }
        return try {
            cm.registerNetworkCallback(NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI).build(), callback)
            val c = withTimeoutOrNull(LINK_CALLBACK_MS) { got.await() }
            Log.i(LOG_TAG, "connection read by callback " + if (c != null) "yes" else "no")
            c?.let { ConnRead(it, LinkRead.READ) } ?: sync
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            sync
        } finally {
            runCatching { cm.unregisterNetworkCallback(callback) }
        }
    }

    /**
     * Design 125b C1, C2: the usable cells of every slot. `getAllCellInfo()` gives the cached cells of all slots; when
     * the newest registered one is over a minute old, one `requestCellInfoUpdate` per active subscription (the slots'
     * own, [com.kidslauncher.mdm.server.MobileNetworkRuntime.activeSubscriptionIds]), up to [CELL_WAIT_MS], and the
     * answers first. No SIM, airplane mode, Location off: an empty list, an ordinary miss. The fine location permission
     * is self-granted by `currentLocation`; a refusal or an invalid subscription is caught. Logs counts only.
     */
    @Suppress("MissingPermission")
    private suspend fun readCells(context: Context, keys: PlaceKeys): List<Heard> {
        val start = SystemClock.elapsedRealtime()
        val tm = context.getSystemService(TelephonyManager::class.java) ?: return emptyList()
        var infos = try {
            tm.allCellInfo.orEmpty()
        } catch (e: Exception) {
            emptyList()
        }
        val newest = infos.filter { it.isRegistered }.maxOfOrNull { it.timestampMillis }
        if (newest == null || start - newest > CELL_STALE_MS) {
            val subs = com.kidslauncher.mdm.server.MobileNetworkRuntime.activeSubscriptionIds(context)
            val fresh = withTimeoutOrNull(CELL_WAIT_MS) {
                coroutineScope {
                    subs.map { sub ->
                        async {
                            suspendCancellableCoroutine<List<CellInfo>> { cont ->
                                try {
                                    tm.createForSubscriptionId(sub).requestCellInfoUpdate(
                                        executor,
                                        object : TelephonyManager.CellInfoCallback() {
                                            override fun onCellInfo(cellInfo: MutableList<CellInfo>) {
                                                if (cont.isActive) cont.resume(cellInfo.toList())
                                            }

                                            override fun onError(errorCode: Int, detail: Throwable?) {
                                                if (cont.isActive) cont.resume(emptyList())
                                            }
                                        },
                                    )
                                } catch (e: Exception) {
                                    if (cont.isActive) cont.resume(emptyList())
                                }
                            }
                        }
                    }.flatMap { it.await() }
                }
            }.orEmpty()
            val again = try {
                tm.allCellInfo.orEmpty()
            } catch (e: Exception) {
                emptyList()
            }
            infos = fresh + again
        }
        val reading = cellReads(infos.map(::cellInput), start)
        Log.i(LOG_TAG, "cells read=${reading.read} usable=${reading.cells.size} registered=${reading.registered} nr=${reading.nr} stale=${reading.stale}")
        return reading.cells.map { c ->
            val key = keys.cell(c.wire.id)
            Heard(key, key.take(16), key.take(16), Band.CELL, 0, c.timestampMs, kind = RowKind.CELL, wire = c.wire.text, registered = c.registered)
        }
    }

    /** A `CellInfo` as the pure read takes it ([cellReads]): the identity's parts, `-1` for an unavailable one. An NSA NR
     * leg comes without NCI or TAC and so is no cell; TD-SCDMA and CDMA aren't read. */
    private fun cellInput(c: CellInfo): CellInput {
        val un = CellInfo.UNAVAILABLE
        fun int(n: Int) = if (n == un) -1L else n.toLong()
        val dbm = try {
            when (c) {
                is CellInfoGsm -> c.cellSignalStrength.dbm
                is CellInfoWcdma -> c.cellSignalStrength.dbm
                is CellInfoLte -> c.cellSignalStrength.dbm
                is CellInfoNr -> c.cellSignalStrength.dbm
                else -> un
            }.takeIf { it != un }
        } catch (e: Exception) {
            null
        }
        val base = CellInput(null, null, null, -1, -1, c.isRegistered, dbm, c.timestampMillis)
        return when (val id = c.cellIdentity) {
            is CellIdentityLte -> base.copy(radio = CellRadio.LTE, mcc = id.mccString, mnc = id.mncString, area = int(id.tac), cid = int(id.ci))
            is CellIdentityNr -> base.copy(
                radio = CellRadio.NR, mcc = id.mccString, mnc = id.mncString, area = int(id.tac),
                cid = if (id.nci == CellInfo.UNAVAILABLE_LONG) -1 else id.nci,
            )
            is CellIdentityWcdma -> base.copy(radio = CellRadio.WCDMA, mcc = id.mccString, mnc = id.mncString, area = int(id.lac), cid = int(id.cid))
            is CellIdentityGsm -> base.copy(radio = CellRadio.GSM, mcc = id.mccString, mnc = id.mncString, area = int(id.lac), cid = int(id.cid))
            else -> base
        }
    }

    private suspend fun snapshot(context: Context, keys: PlaceKeys, movedAtElapsedMs: Long): Snapshot = coroutineScope {
        val cells = async { readCells(context, keys) }
        val scan = scanNow(context, movedAtElapsedMs)
        answered(scan, connectedNow(context), cells.await(), movedAtElapsedMs)
    }

    /** Design 134 D1, D2: [scan] as a snapshot with the results that answer its request; the connection read after the
     * scan (a live BSSID must be among the answer). */
    private fun answered(scan: Scan, conn: ConnRead, cells: List<Heard>, movedAtElapsedMs: Long): Snapshot {
        val connected = conn.connected
        val answer = scanAnswer(scan.results, scan.request, connectedBssid(connected?.bssid), movedAtElapsedMs, SystemClock.elapsedRealtime())
        // Code QA L2: how many results answered the request, and how it was asked; a time base that isn't boottime shows.
        Log.i(LOG_TAG, "scan answer ${answer.answering.size}/${scan.results.size} ask=${scan.request.ask.wire}" + (answer.inconclusive?.let { " ${it.wire}" } ?: ""))
        return Snapshot(scan.results, connected, cells, answer, scan.request, conn.read)
    }

    /** Design 134 D3: one more scan for [first]'s attempt, its cells kept. */
    private suspend fun rescan(context: Context, first: Snapshot, movedAtElapsedMs: Long): Snapshot =
        answered(scanNow(context, movedAtElapsedMs), connectedNow(context), first.cells, movedAtElapsedMs)

    private fun hearing(context: Context, store: Store, snap: Snapshot, remember: Boolean): Hearing {
        val h = hear(snap.results, snap.connected, store.keys, moving(context), readings, SystemClock.elapsedRealtime())
        if (remember) readings = h.readings
        return h
    }

    // --- The lookup (D9, D10) ---

    private fun nowMin() = (System.currentTimeMillis() / 60_000).toInt()

    private fun today() = LocalDate.now().toEpochDay().toInt()

    /** D9 and D10's veto on [snap]: the learned fix, or `null`. Touches `lastSeen` of the rows it used. Design 131: a
     * result heard on another band than its row's marks the attempt's [disagreement]. */
    private fun lookup(context: Context, store: Store, snap: Snapshot, movedAtElapsedMs: Long, disagreement: Disagreement? = null): LearnedFix? {
        val h = hearing(context, store, snap, remember = true)
        val view = scanView(h, SystemClock.elapsedRealtime(), movedAtElapsedMs)
        val now = nowMin()
        if (view != null && bandDisagrees(view, store.rows, now)) {
            Log.w(LOG_TAG, "networks heard on another band")
            disagreement?.mark()
        }
        val fix = view?.let { learnedFix(it, store.rows, now) }?.takeUnless { cellVetoes(snap.cells, store.rows, it, now) }
        val table = store.rows.associateBy { it.id }
        val trusted = view?.heard.orEmpty().count { table[it.key]?.trusted(now) == true }
        val line = "scan usable=${h.heard.size} trusted=$trusted networks=${fix?.networks ?: 0} acc=${fix?.accuracyM?.toInt() ?: "-"}"
        Log.i(LOG_TAG, line + if (view?.fresh == false) " fallback" else "")
        if (fix != null) {
            if (fix.networks >= 3) lastLearned = fix
            save(context, store.generation) { touchSeen(it, fix.keys, now) }
        }
        return fix
    }

    /** D10: the serving cell's own fix, after GPS and Wi-Fi gave nothing - reported only by design 125b C9
     * ([cellReport]: never while still, not over a recent fine fix it agrees with, a `locate` always), never fresh. */
    private fun cellOnly(context: Context, opened: Store?, snap: Snapshot, ask: Ask, locate: Boolean, accept: (Location) -> Boolean): Location? {
        opened ?: return null
        // Design 125b C8 (QA H1): the table as it is now - with the rows the cell ask just stored.
        val store = open(context) ?: return null
        val fix = cellFix(snap.cells, store.rows, nowMin()) ?: return null
        save(context, store.generation) { touchSeen(it, fix.keys, nowMin()) }
        // Design 134 D9.5: a cell fix past the vouch's accuracy + 2 km says elsewhere.
        ask.vouchFix?.let { v -> if (distanceM(v.latitude, v.longitude, fix.lat, fix.lng) > fix.accuracyM + (v.accuracy ?: 0f) + CELL_VETO_SLACK_M) ask.elsewhere = true }
        // Code QA L4: the fixes' ages by elapsed realtime, never the wall clock (none set: another boot's fix, unknown).
        fun seen(l: Location?) = l?.let {
            SeenFix(it.latitude, it.longitude, if (it.hasAccuracy()) it.accuracy.toDouble() else 0.0, (it.elapsedRealtimeNanos / 1_000_000).takeIf { e -> e > 0 })
        }
        val verdict = cellReport(fix, appleStillMotion(ask.motion), seen(ask.stillVouch), seen(ask.lastFresh), SystemClock.elapsedRealtime(), locate)
        Log.i(LOG_TAG, "cell fix acc=${fix.accuracyM.toInt()} $verdict")
        return when (verdict) {
            CellVerdict.REPORT -> location(fix).takeIf(accept)?.also { ask.notFresh = true }
            CellVerdict.WITHHOLD -> null
            CellVerdict.CONTRADICTS -> {
                count(context, today()) { c -> c.copy(cellContradictions = c.cellContradictions + 1) }
                null
            }
        }
    }

    // --- The Apple lookup through the family's server (design 125 step 2, A3, A4, A9) ---

    /**
     * Each fresh policy's switch (the sync, after it is stored): on ends a 409's "no ask until a policy says on"; off
     * stops the asks at once and drops every Apple row on every off policy (no write when there are none), the waits
     * cleared only once the table is written (code QA L1). Never throws.
     */
    fun policyAccepted(context: Context, on: Boolean) {
        try {
            val p = prefs(context)
            if (on) {
                if (!p.getBoolean(KEY_APPLE_ON, false) || p.contains(KEY_APPLE_OFF)) {
                    p.edit().putBoolean(KEY_APPLE_ON, true).remove(KEY_APPLE_OFF).commit()
                }
                return
            }
            if (p.getBoolean(KEY_APPLE_ON, false)) p.edit().putBoolean(KEY_APPLE_ON, false).commit()
            val store = open(context) ?: return
            if (!save(context, store.generation, ::dropApple)) return
            if (p.contains(KEY_APPLE_ON) || p.contains(KEY_APPLE_OFF) || p.contains(KEY_APPLE_WAIT) || p.contains(KEY_APPLE_CELL_WAIT)) {
                p.edit().remove(KEY_APPLE_ON).remove(KEY_APPLE_OFF).remove(KEY_APPLE_WAIT).remove(KEY_APPLE_CELL_WAIT).commit()
                Log.i(LOG_TAG, "apple rows dropped (switch off)")
            }
        } catch (e: Exception) {
            Log.w(LOG_TAG, "apple switch not applied: ${e.javaClass.simpleName}")
        }
    }

    /** A3: the switch on, no 409 in force, and a network up (offline: no ask and nothing changed). Each kind's own wait:
     * [appleMayAskNow]. */
    private fun appleAllowed(context: Context): Boolean {
        val p = prefs(context)
        if (!p.getBoolean(KEY_APPLE_ON, false) || p.getBoolean(KEY_APPLE_OFF, false)) return false
        return try {
            val cm = context.getSystemService(ConnectivityManager::class.java)
            cm?.activeNetwork?.let { cm.getNetworkCapabilities(it) }?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
        } catch (e: Exception) {
            false
        }
    }

    /** The wait in [waitKey] (the Wi-Fi's or the cells', design 125b C5) is over. */
    private fun appleMayAskNow(context: Context, waitKey: String): Boolean {
        val p = prefs(context)
        return appleMayAsk(System.currentTimeMillis(), p.getLong(waitKey, 0L), p.getBoolean(KEY_APPLE_OFF, false))
    }

    /**
     * Design 125b C4, C5: the read's usable cells with no position ([cellAsk]; none when eight have one) asked of the
     * server in a request of their own, at most [APPLE_CELL_TIMEOUT_MS] (the server's fixed answer time and the way); the
     * answer's rows stored (C7: kind 5), the cell wait or "off" kept.
     * `true` when rows were stored. Runs beside the Wi-Fi ask, with or without a scan view (QA H1).
     */
    private suspend fun askAppleCells(context: Context, store: Store, snap: Snapshot): Boolean {
        if (!appleMayAskNow(context, KEY_APPLE_CELL_WAIT)) return false
        val now = nowMin()
        val asking = cellAsk(snap.cells, store.rows, now)
        if (asking.isEmpty()) return false
        val body = ServerJson.encodeToString(WifiLookupCellsRequest.serializer(), WifiLookupCellsRequest(asking))
        val (status, answer) = withTimeoutOrNull(APPLE_CELL_TIMEOUT_MS) { postLookup(context, body, APPLE_CELL_TIMEOUT_MS) } ?: (0 to null)
        val p = prefs(context)
        val step = appleCellStep(status, answer)
        val outcome = if (step is CellStep.Use) "got=${step.cells.size} unknown=${step.unknown.size}" else "step=${step.javaClass.simpleName}"
        Log.i(LOG_TAG, "apple cells asked=${asking.size} status=$status $outcome")
        when (step) {
            is CellStep.Use -> {
                p.edit().remove(KEY_APPLE_CELL_WAIT).commit()
                save(context, store.generation) { storeAppleCells(it, step.cells, step.unknown, store.keys, now) }
                return step.cells.isNotEmpty()
            }
            is CellStep.Wait -> p.edit().putLong(KEY_APPLE_CELL_WAIT, System.currentTimeMillis() + step.ms).commit()
            CellStep.Off -> {
                p.edit().putBoolean(KEY_APPLE_OFF, true).commit()
                com.kidslauncher.mdm.push.SyncRunner.request(context.applicationContext, "wifi_lookup_off")
            }
        }
        return false
    }

    /**
     * A3, A4: a learned miss's usable BSSIDs (none with a trusted own row, a live Apple row or a tombstone) asked of
     * the server, at most 15 s; the answer's rows stored (A9), its wait or "off" kept. `true` when rows were stored.
     */
    private suspend fun askApple(context: Context, store: Store, snap: Snapshot, movedAtElapsedMs: Long): Boolean {
        if (!appleMayAskNow(context, KEY_APPLE_WAIT)) return false
        val view = scanView(hearing(context, store, snap, remember = false), SystemClock.elapsedRealtime(), movedAtElapsedMs) ?: return false
        val now = nowMin()
        val asking = appleAsk(view.heard, store.rows, now)
        if (asking.isEmpty()) return false
        val request = ServerJson.encodeToString(WifiLookupRequest.serializer(), WifiLookupRequest(asking))
        val (status, body) = withTimeoutOrNull(APPLE_TIMEOUT_MS) { postLookup(context, request, APPLE_TIMEOUT_MS) } ?: (0 to null)
        val p = prefs(context)
        val step = appleStep(status, body)
        val outcome = if (step is AppleStep.Use) "got=${step.aps.size} unknown=${step.unknown.size}" else "step=${step.javaClass.simpleName}"
        Log.i(LOG_TAG, "apple asked=${asking.size} status=$status $outcome")
        when (step) {
            is AppleStep.Use -> {
                // A partial answer's wait (code QA M1): the server couldn't ask Apple for the rest.
                if (step.waitMs > 0) {
                    p.edit().putLong(KEY_APPLE_WAIT, System.currentTimeMillis() + step.waitMs).commit()
                } else {
                    p.edit().remove(KEY_APPLE_WAIT).commit()
                }
                save(context, store.generation) { storeApple(it, step.aps, step.unknown, store.keys, now) }
                return step.aps.isNotEmpty()
            }
            is AppleStep.Wait -> p.edit().putLong(KEY_APPLE_WAIT, System.currentTimeMillis() + step.ms).commit()
            AppleStep.Off -> {
                p.edit().putBoolean(KEY_APPLE_OFF, true).commit()
                com.kidslauncher.mdm.push.SyncRunner.request(context.applicationContext, "wifi_lookup_off")
            }
        }
        return false
    }

    /** The server's answer to [json] (a BSSIDs or a cells body) within [timeoutMs]: its status and body (0 = no answer). */
    private suspend fun postLookup(context: Context, json: String, timeoutMs: Long): Pair<Int, String?> = try {
        val url = LauncherPreferences.mdm().serverUrl()
        val token = DeviceCredentials.current(context)
        if (url.isNullOrBlank() || token == null) {
            0 to null
        } else {
            val body = json.toRequestBody("application/json".toMediaType())
            val response = createMdmApi(url, token, callTimeoutMs = timeoutMs).wifiLookup(body)
            response.code() to (if (response.isSuccessful) response.body() else response.errorBody())?.use { it.string() }
        }
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.w(LOG_TAG, "apple ask failed: ${e.javaClass.simpleName}")
        0 to null
    }

    /** [fix] as a platform fix, with what confirmed it (design 131: none for the Wi-Fi alone and a cell fix). */
    private fun location(fix: LearnedFix, check: FixCheck? = null): Location = Location(fix.provider).apply {
        latitude = fix.lat
        longitude = fix.lng
        accuracy = fix.accuracyM.toFloat()
        elapsedRealtimeNanos = fix.elapsedMs * 1_000_000
        time = System.currentTimeMillis() - (SystemClock.elapsedRealtime() - fix.elapsedMs)
    }.withFixCheck(check?.wire)

    /**
     * Design 131 (code QA M1): Android may number its saved networks anew at a boot, so the first teach after one may
     * rebind a zone link of the same class ([rebindAllowed]: exactly one saved network has the connected name and the
     * count is the last boot's). Every teach keeps this boot's count. The Device Owner reads the saved networks
     * (`getConfiguredNetworks`, inside the Location window); unreadable: no rebind.
     */
    @Suppress("DEPRECATION", "MissingPermission")
    private fun rebindAfterBoot(context: Context, ssid: String?): Boolean {
        if (ssid.isNullOrEmpty()) return false
        val configs = try {
            wifi(context)?.configuredNetworks
        } catch (e: Exception) {
            null
        } ?: return false
        val boot = BootClock.bootCount()
        val p = prefs(context)
        val stored = p.getString(KEY_SAVED_NETWORKS, null)?.split(':')?.mapNotNull { it.toIntOrNull() }?.takeIf { it.size == 2 }
        val (count, named) = savedNetworkCounts(configs.map { it.SSID?.removeSurrounding("\"") to it.networkId }, ssid)
        val now = "$boot:$count"
        if (p.getString(KEY_SAVED_NETWORKS, null) != now) p.edit().putString(KEY_SAVED_NETWORKS, now).commit()
        return rebindAllowed(stored?.get(0), stored?.get(1), boot, count, named)
    }

    // --- Design 131: the last satellite fix ---

    private fun lastGnss(context: Context): LastGnss? = try {
        prefs(context).getString(KEY_LAST_GNSS, null)?.let { ServerJson.decodeFromString(LastGnss.serializer(), it) }
    } catch (e: Exception) {
        null
    }

    /** Every satellite fix the phone sees ([lastGnssWith]): the jump gate's and the satellite check's last one, kept
     * across a reboot with its time on the decisions' clock. */
    private fun noteGnss(context: Context, fixes: List<Location>) {
        if (fixes.isEmpty()) return
        synchronized(lock) {
            val nowTrusted = TrustedClock.nowMs()
            val nowElapsed = SystemClock.elapsedRealtime()
            val boot = BootClock.bootCount()
            val before = lastGnss(context)
            val after = fixes.map(::teachFix).fold(before) { prev, g -> lastGnssWith(prev, g, nowTrusted - (nowElapsed - g.elapsedMs), boot, release) }
            if (after != null && after != before) {
                prefs(context).edit().putString(KEY_LAST_GNSS, ServerJson.encodeToString(LastGnss.serializer(), after)).commit()
            }
        }
    }

    // --- Teaching (D1-D3, D12, D15, D16) ---

    private fun teachFix(l: Location) = TeachFix(
        lat = l.latitude,
        lng = l.longitude,
        accuracyM = if (l.hasAccuracy()) l.accuracy else null,
        speedMps = if (l.hasSpeed()) l.speed else null,
        elapsedMs = l.elapsedRealtimeNanos / 1_000_000,
        provider = l.provider,
        satellites = l.extras?.getInt("satellites", -1)?.takeIf { it >= 0 },
        mock = l.isMock,
    )

    private val release = !BuildConfig.DEBUG

    /**
     * A GPS fix's signal for the log, `satellites/maxCn0/meanCn0@accuracy` from the platform's extras ("-" when absent;
     * never the coordinates): the Jelly's real values indoors and out before a C/N0 floor for teaching is set
     * (2026-11-23, an indoor fix of 5 satellites at 25/22 dB-Hz taught APs 30-40 m off).
     */
    fun signal(l: Location): String {
        fun extra(key: String) = l.extras?.getInt(key, -1)?.takeIf { it >= 0 }?.toString() ?: "-"
        val acc = if (l.hasAccuracy()) l.accuracy.toInt().toString() else "-"
        return "${extra("satellites")}/${extra("maxCn0")}/${extra("meanCn0")}@$acc"
    }

    private fun teaching(fixes: List<Location>): List<TeachFix> = fixes.map(::teachFix).filter { teaches(it, release) }

    private fun linkZones(keys: PlaceKeys): List<LinkZone> = ZoneWatch.watchedZones().map { LinkZone(it, keys.zone(it)) }

    /**
     * One teach (D2-D7, D12, code QA M1): the sightings paired within [windowMs] ([pairings]) from results newer than the
     * last motion fire ([movedAtElapsedMs]); nothing paired - nothing at all. The leave-one-out count, D15's
     * contradiction and the zone links' strikes read only the results paired with the best teacher ([pairedWith]),
     * against the table as it was; then the table and the links are saved. True when something was taught.
     */
    private fun teachWith(
        context: Context,
        store: Store,
        snap: Snapshot,
        teachers: List<TeachFix>,
        windowMs: Long,
        movedAtElapsedMs: Long,
        disagreement: Disagreement? = null,
    ): Boolean {
        if (teachers.isEmpty()) return false
        val h = hearing(context, store, snap, remember = false)
        val after = h.heard.filter { it.elapsedMs > movedAtElapsedMs }
        val cells = snap.cells.filter { it.elapsedMs > movedAtElapsedMs }
        val sightings = pairings(Hearing(after + cells, h.stale, h.readings), teachers, windowMs)
        if (sightings.isEmpty()) return false
        val best = teachers.minBy { it.accuracyM ?: Float.MAX_VALUE }
        val paired = pairedWith(after, best, windowMs, movedAtElapsedMs)
        val now = nowMin()
        val today = today()
        val zones = linkZones(store.keys)
        // The connected network is now's, whatever the pairing: the link's inside occasion - by its name and under its
        // binding (design 131: a mesh node or a 6 GHz BSSID on it counts; saved anew, it learns again).
        val conn = linkConn(snap.connected, store.keys)
        val connectedNet = conn?.network
        val router = routerOf(snap.connected, store.keys)
        val confirmed = confirmedAps(ZoneWatch.watchedZones(), store.keys)
        val rebind = conn != null && rebindAfterBoot(context, snap.connected?.ssid)
        // D16: what the table as it was said for the same results; a zone-link renewal's class by the connected network.
        val counts = mutableListOf<(DayCounts) -> DayCounts>()
        // D16 by class, and design 125's by source with the error: the same paired results, the table as it was.
        val before = learnedFix(ScanView(paired, true), store.rows, now)
        leaveOneOut(before, best.lat, best.lng)?.let { (cls, hit) -> counts += { c -> c.loo(cls, hit) } }
        before?.let { fix ->
            val err = distanceM(fix.lat, fix.lng, best.lat, best.lng)
            counts += { c -> c.looSource(fix.source, err <= fix.accuracyM, err) }
        }
        // Design 125b C11: the cell fix of the cells paired with the best teacher, the table as it was, by source.
        val pairedCells = cells.filter { abs(it.elapsedMs - best.elapsedMs) <= maxOf(windowMs, PAIR_SINGLE_MS) }
        cellFix(pairedCells, store.rows, now)?.let { fix ->
            val err = distanceM(fix.lat, fix.lng, best.lat, best.lng)
            counts += { c -> c.looCell(fix.source, err <= fix.accuracyM, err) }
        }
        val struckNow = struck(context)
        zones.firstOrNull { z -> router != null && zoneLink(router, conn, z, confirmed, struckNow, store.rows, now, apOwn = false) != null }?.let { z ->
            val inside = distanceM(best.lat, best.lng, z.zone.latitude, z.zone.longitude) <= z.zone.radiusM
            counts += { c -> c.loo(LooClass.ZONE, inside) }
        }
        if (paired.isNotEmpty() && learnedContradiction(lastLearned, best.lat, best.lng, best.elapsedMs)) {
            Log.w(LOG_TAG, "learned contradiction")
            counts += { c -> c.copy(contradictions = c.contradictions + 1) }
            // Design 131: within an attempt one disagreement for all its rules; outside one (a passive fix) its own.
            if (disagreement != null) disagreement.mark() else ZoneWatch.countWifiDisagreed(context, 1)
        }
        if (counts.isNotEmpty()) count(context, today) { counts.fold(it) { c, f -> f(c) } }
        var outcome: TeachOutcome? = null
        val acc = (best.accuracyM ?: TEACH_ACCURACY_MAX_M).toDouble()
        // Design 138 D7: the routers this teach places far outside - a strike once a second teaching fix agrees.
        val candidates = strikeCandidates(
            zones, best.lat, best.lng, acc, router,
            paired.filter { it.kind == RowKind.WIFI && it.bssid.isNotEmpty() }.map { listOf(it.device, store.keys.device(it.bssid, null)).distinct() to it.band },
            confirmed, store.rows, now,
        )
        // QA L4: a router seen inside its zone by this teach drops its pending strike.
        val inside = insideKeys(zones, best.lat, best.lng, acc, router, paired.filter { it.kind == RowKind.WIFI && it.bssid.isNotEmpty() }.map { listOf(it.device, store.keys.device(it.bssid, null)).distinct() })
        val struckKeys = synchronized(lock) {
            val (hit, next) = strikeStep(pendingStrikes, candidates, best.elapsedMs, inside)
            pendingStrikes = next
            hit
        }
        // QA L3: the confirmations' ids from the strikes alone, outside the save.
        val struckIds = applyStrikes(emptyList(), zones, confirmed, struckKeys, now).second
        save(context, store.generation) { rows ->
            val taught = teach(rows, sightings, now, today).also { outcome = it }
            // Design 125 A9: an own teach beyond an Apple row's reach, or a trusted own row against it, tombstones it.
            val apple = reconcileAppleCells(appleCellTaught(reconcileApple(appleTaught(taught.rows, sightings, now), now), sightings, now), now)
            val linked = teachLinks(apple, zones, best.lat, best.lng, acc, connectedNet, now, today, conn?.binding ?: "", rebind, router?.keys.orEmpty())
            applyStrikes(linked, zones, confirmed, struckKeys, now).first
        }
        outcome?.let { Log.i(LOG_TAG, "taught aps=${it.taught} new=${it.new} moved=${it.moved}") }
        if (struckKeys.isNotEmpty()) {
            if (struckIds.isNotEmpty()) addStruck(context, struckIds)
            Log.w(LOG_TAG, "zone link struck by=" + if (struckIds.isNotEmpty()) "confirmed" else "learned")
        }
        return true
    }

    // --- Design 138: a zone's Wi-Fi ---

    private fun struckPrefs(context: Context) = context.applicationContext.getSharedPreferences(STRUCK_PREFS, Context.MODE_PRIVATE)

    /** D7 (L2): the confirmations this phone struck - kept apart from the Wi-Fi places prefs, so a clear keeps them. */
    fun struck(context: Context): Set<Long> = try {
        struckPrefs(context).getString(KEY_STRUCK, null)?.split(',')?.mapNotNull { it.toLongOrNull() }?.toSet().orEmpty()
    } catch (e: Exception) {
        emptySet()
    }

    private fun addStruck(context: Context, ids: Set<Long>) = synchronized(lock) {
        val all = (struck(context) + ids).sorted().takeLast(STRUCK_MAX)
        struckPrefs(context).edit().putString(KEY_STRUCK, all.joinToString(",")).commit()
    }

    /** D3: the report's struck ids - those the policy still carries; the rest are dropped here (the server's block
     * reached the policy). */
    fun struckForReport(context: Context): List<Long> = synchronized(lock) {
        // QA L2: read, filter and write under one lock, so a strike recorded meanwhile is never lost.
        val zones = ZoneWatch.watchedZones()
        val all = struck(context)
        if (all.isEmpty()) return@synchronized emptyList()
        val carried = struckInPolicy(all, zones)
        if (carried.size != all.size && zones.isNotEmpty()) struckPrefs(context).edit().putString(KEY_STRUCK, carried.joinToString(",")).commit()
        carried
    }

    /** Design 138 rev 3 (D15): the single-router fix on [snap] - its level the answering scan's entry for the connected
     * BSSID, else the -85 dBm bound; the continuity gate on the cached fix; logged with its reason, never a name or an
     * address. */
    private fun routerOnlyFix(context: Context, store: Store, snap: Snapshot, ask: Ask): LearnedFix? {
        val router = routerOf(snap.connected, store.keys) ?: return null
        val rows = open(context)?.rows ?: store.rows
        val now = nowMin()
        val zones = linkZones(store.keys)
        val confirmed = confirmedAps(ZoneWatch.watchedZones(), store.keys)
        if (confirmed.isEmpty()) return null
        // The level only from the answering scan: WifiInfo.getRssi() is stale with the screen off (the -85 dBm bound then).
        val bssid = connectedBssid(snap.connected?.bssid)
        val scanned = knownRssi(snap.answer?.answering.orEmpty().filter { normalBssid(it.bssid) == bssid }.maxOfOrNull { it.rssi })
        val heard = hearing(context, store, snap, remember = false).heard.filter { it.elapsedMs > ask.movedAtElapsedMs || it.connected }
        // The continuity gate: the phone's last reported fix, any class.
        val last = LocateCommands.cachedQueued()?.let { it.latitude to it.longitude }
        val nowElapsed = SystemClock.elapsedRealtime()
        val gnss = lastGnss(context)
        val answer = routerFix(
            router, scanned, heard, snap.answer?.conclusive == true, zones, confirmed, struck(context), rows, now, nowElapsed, last,
            away = { z -> cellAway(snap.cells, rows, now, z, routerAccuracyM(scanned ?: ROUTER_LEVEL_UNKNOWN_DBM)) },
            impossible = { f -> jumpGate(f, gnss, TrustedClock.nowMs(), nowElapsed, BootClock.bootCount()) == Jump.IMPOSSIBLE },
        )
        Log.i(LOG_TAG, "router fix " + answer.reason + (answer.place?.let { " at=${it.wire}" } ?: "") + (answer.fix?.let { " acc=${it.accuracyM.toInt()}" } ?: ""))
        return answer.fix
    }

    /** D3: the report's `wifi` while the policy has zones and Location is on - the connection read through design 134
     * D25's [connectedNow] (the callback when the first read is redacted). Logs a reason, never a name or address. */
    suspend fun reportWifi(context: Context): com.kidslauncher.mdm.server.dto.ZoneWifiReport? {
        val app = context.applicationContext
        val zonesOn = ZoneWatch.watchedZones().isNotEmpty() && QuickControls.isLocationEnabled(app)
        if (!zonesOn) return null
        return try {
            val read = connectedNow(app)
            val c = read.connected
            val report = zoneWifiOf(c, true)
            val reason = when {
                report != null -> null
                c == null -> if (read.read == LinkRead.UNREADABLE) "unreadable" else "none"
                !c.unmetered -> "metered"
                c.security != LinkSecurity.PSK && c.security != LinkSecurity.SAE -> "security"
                else -> "hidden"
            }
            Log.i(LOG_TAG, "zone wifi report " + (reason?.let { "no reason=$it" } ?: "yes") + " netid=" + if ((c?.networkId ?: -1) >= 0) "known" else "none")
            report
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
    }

    /** D6: a Wi-Fi fix's teach of the learned links - the zones where [wifiOccasion] holds, under design 131's binding. */
    private fun teachFromWifi(context: Context, snap: Snapshot, fix: LearnedFix, check: FixCheck?, step: WifiStep, rest: Rest?) {
        val store = open(context) ?: return
        val conn = linkConn(snap.connected, store.keys) ?: return
        val router = routerOf(snap.connected, store.keys) ?: return
        if (!linkSecurityHolds(conn, apOwn = conn.apKey != null && conn.apKey in fix.keys)) return
        val now = nowMin()
        val zones = linkZones(store.keys)
        val only = zones.filter { z -> wifiOccasion(fix, step, check, false, rest, z, cellAway(snap.cells, store.rows, now, z, fix.accuracyM)) }.map { it.key }.toSet()
        if (only.isEmpty()) return
        val rebind = rebindAfterBoot(context, snap.connected?.ssid)
        save(context, store.generation) { rows ->
            teachLinks(rows, zones, fix.lat, fix.lng, fix.accuracyM, conn.network, now, today(), conn.binding, rebind, router.keys, only)
        }
        val row = open(context)?.rows?.firstOrNull { it.kind == RowKind.LINK && it.key in only && it.network == conn.network }
        Log.i(LOG_TAG, "link taught by=wifi occasions=${row?.occasions ?: 0} days=${row?.days ?: 0}")
    }

    /** A9 (H2): a still phone's Apple fix outside its vouch - an Apple contradiction (the by-source counts are
     * teachWith's alone, on the paired results: code QA L2). */
    private fun appleContradiction(context: Context) {
        Log.w(LOG_TAG, "apple contradiction")
        count(context, today()) { c -> c.copy(appleContradictions = c.appleContradictions + 1) }
    }

    private fun count(context: Context, today: Int, change: (DayCounts) -> DayCounts) = synchronized(lock) {
        val p = prefs(context)
        val serializer = ListSerializer(DayCounts.serializer())
        val before = try {
            p.getString(KEY_COUNTS, null)?.let { ServerJson.decodeFromString(serializer, it) }.orEmpty()
        } catch (e: Exception) {
            emptyList()
        }
        val after = countsWith(before, today, change)
        p.edit().putString(KEY_COUNTS, ServerJson.encodeToString(serializer, after)).commit()
        Log.i(LOG_TAG, countsLine(after))
        Log.i(LOG_TAG, cellCountsLine(after))
    }

    /**
     * D3: a passive fix (any app's, ours too) that passes D1 teaches against the system's cached results within
     * +-30 s, after the last motion fire - never a scan of ours. Skipped while one of our own attempts runs (it teaches
     * its stream itself). On the passive feed's executor; never throws.
     */
    fun offerPassive(context: Context, location: Location) {
        try {
            noteGnss(context, listOf(location))
            if (ownTeaching.get() > 0) return
            val fix = teaching(listOf(location))
            if (fix.isEmpty()) return
            val store = open(context) ?: return
            val results = cached(context)
            if (results.isEmpty()) return
            Log.i(LOG_TAG, "passive teach sig=${signal(location)}")
            // Design 138 D7 (L1): the connection through the callback too, so a connected router can strike here - on the
            // passive feed's executor, at most LINK_CALLBACK_MS.
            val conn = kotlinx.coroutines.runBlocking { connectedNow(context) }.connected
            teachWith(context, store, Snapshot(results, conn, emptyList()), fix, PAIR_SINGLE_MS, MotionGate.snapshot(context).movedAtElapsedMs)
        } catch (e: Exception) {
            Log.w(LOG_TAG, "passive teach failed: ${e.javaClass.simpleName}")
        }
    }

    // --- GPS, as a stream (D2) ---

    /** Every fix of one GPS request at 1 s - the stream a scan's results are paired against (within [PAIR_STREAM_MS]). */
    private class GpsStream(private val lm: LocationManager) : LocationListener {
        val fixes: MutableList<Location> = java.util.Collections.synchronizedList(mutableListOf())
        val first = CompletableDeferred<Unit>()

        override fun onLocationChanged(location: Location) {
            fixes += location
            first.complete(Unit)
        }

        @Suppress("MissingPermission") // self-granted by currentLocation; a refusal is caught
        fun start(context: Context, boundMs: Long): Boolean = try {
            val request = LocationRequest.Builder(1_000L).setQuality(LocationRequest.QUALITY_HIGH_ACCURACY).setDurationMillis(boundMs + SCAN_WAIT_MS).build()
            lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, request, context.mainExecutor, this)
            true
        } catch (e: Exception) {
            Log.w(LOG_TAG, "GPS refused: ${e.javaClass.simpleName}")
            false
        }

        fun stop() = runCatching { lm.removeUpdates(this) }

        fun all(): List<Location> = fixes.toList()
    }

    /** A GPS try's best accepted fix and whether it taught. */
    private class GpsTry(val fix: Location?, val taught: Boolean)

    /**
     * One GPS try of this design (D2, D11, D14): the stream within [boundMs] ([shortMs] first; [stopEarly] ends it
     * there). After the first fix - or, [untilTeaches], from the start - it runs on until a fix that teaches, at most
     * [TEACH_WAIT_MS] past the first (code QA M3) and within the bound, ended early by a fix over 3 m/s (L1). A teaching
     * fix with no result of [first] within [PAIR_STREAM_MS] of the stream takes one more scan while the stream runs on
     * (D2's re-scan), then teaches. The most accurate fix [accept] takes is returned; the refusals are logged as counts.
     */
    private suspend fun gpsTry(
        context: Context,
        lm: LocationManager,
        store: Store?,
        first: Deferred<Snapshot>?,
        movedAtElapsedMs: Long,
        boundMs: Long,
        accept: (Location) -> Boolean,
        shortMs: Long = boundMs,
        stopEarly: suspend () -> Boolean = { false },
        untilTeaches: Boolean = false,
        disagreement: Disagreement? = null,
    ): GpsTry {
        prefs(context).edit().putLong(KEY_LAST_GPS_TRY, System.currentTimeMillis()).commit()
        val stream = GpsStream(lm)
        if (!stream.start(context, boundMs)) return GpsTry(null, false)
        val start = SystemClock.elapsedRealtime()
        var taught = false
        try {
            suspend fun teachingOrFast(ms: Long): Boolean = withTimeoutOrNull(ms) {
                while (true) {
                    val all = stream.all().map(::teachFix)
                    if (all.any { teaches(it, release) } || all.any(::tooFastToTeach)) break
                    delay(250)
                }
            } != null
            suspend fun waitFix(ms: Long): Boolean = if (untilTeaches) teachingOrFast(ms) else withTimeoutOrNull(ms) { stream.first.await() } != null
            val got = waitFix(shortMs) || (shortMs < boundMs && !stopEarly() && waitFix(boundMs - shortMs))
            if (got && !untilTeaches && teaching(stream.all()).isEmpty()) {
                teachingOrFast(minOf(TEACH_WAIT_MS, boundMs - (SystemClock.elapsedRealtime() - start)).coerceAtLeast(0))
            }
            if (store != null && first != null && teaching(stream.all()).isNotEmpty()) {
                var snap = first.await()
                val streamMs = stream.all().map { it.elapsedRealtimeNanos / 1_000_000 }
                if (snap.results.none { r -> streamMs.any { abs(it - r.elapsedMs) <= PAIR_STREAM_MS } } && networkOff(lm)) {
                    // Every cached result counts as before the move: the platform scans now.
                    snap = Snapshot(scanNow(context, SystemClock.elapsedRealtime()).results, snap.connected, snap.cells)
                }
                taught = teachWith(context, store, snap, teaching(stream.all()), PAIR_STREAM_MS, movedAtElapsedMs, disagreement)
            }
        } finally {
            stream.stop()
        }
        val all = stream.all()
        noteGnss(context, all)
        val refused = all.mapNotNull { teachRefusal(teachFix(it), release) }.groupingBy { it }.eachCount()
        if (all.isNotEmpty()) {
            Log.i(
                LOG_TAG,
                "gps fixes=${all.size} taught=$taught" + refused.entries.joinToString("") { (reason, n) -> " $reason=$n" } +
                    " sig=" + all.takeLast(SIGNAL_LOG_FIXES).joinToString(",", transform = ::signal),
            )
        }
        return GpsTry(all.filter(accept).minByOrNull { if (it.hasAccuracy()) it.accuracy else Float.MAX_VALUE }, taught)
    }

    // --- The attempt (D13, D14) ---

    /**
     * A fresh-fix attempt with the `network` provider off (D14, [attemptPlan]), inside `currentLocation`'s Location
     * window: [AttemptPlan.TOGETHER] scans and runs GPS together and keeps the better ([keepBetter]: good satellites far
     * away overrule, else by accuracy; the learned fix only when it stands alone); [AttemptPlan.LEARNED_FIRST] the
     * learned fix when it stands alone and [wifiVerdict] lets it, else D12's renewal of a still vouch, else GPS;
     * [AttemptPlan.GPS_FIRST] GPS, then the learned fix; [AttemptPlan.LOCATE] GPS for 60 s, 20 s when a link-checked fix
     * within 60 m is in hand ([locateStopsShort]), the better kept; then a cell fix. Design 131: every Wi-Fi fix carries
     * what confirmed it ([FixCheck], in the fix's extras), an unchecked one is never fresh, and the attempt's
     * disagreements raise `zone_watch.wifi_disagreed` once. Every fix through [accept] (the mock rule). Never throws but
     * a cancellation.
     */
    suspend fun attempt(context: Context, lm: LocationManager, path: AttemptPath, ask: Ask, accept: (Location) -> Boolean): Location? {
        val app = context.applicationContext
        ownTeaching.incrementAndGet()
        val disagreement = Disagreement()
        try {
            val store = open(app)
            // Design 134 D14: the print in force (dropped at a boot or after 72 h); without a trusted sensor it describes
            // nothing the heartbeat could use.
            var print = if (store != null) StillPrints.current(app) else null
            if (print != null && ask.motion == Motion.NO_SENSOR) {
                StillPrints.drop(app, if (ZoneWatch.motionTrusted(app)) DropReason.NO_SENSOR else DropReason.DISTRUST)
                print = null
            }
            val movedSinceFresh = ask.motion != Motion.STILL
            val refresh = refreshDue(prefs(app).getLong(KEY_LAST_GPS_TRY, 0L), System.currentTimeMillis())
            val plan = attemptPlan(path, ask.motion, refresh)
            return coroutineScope {
                // Without the table (a Keystore error) nothing is scanned, used or taught: GPS as before.
                val snap = async { if (store == null) Snapshot(emptyList(), null, emptyList()) else snapshot(app, store.keys, ask.movedAtElapsedMs) }
                suspend fun learned(on: Snapshot? = null): LearnedFix? = store?.let { lookup(app, it, on ?: snap.await(), ask.movedAtElapsedMs, disagreement) }
                    ?.takeIf { standsAlone(it, movedSinceFresh) }
                suspend fun gps(boundMs: Long, shortMs: Long = boundMs, stopEarly: suspend () -> Boolean = { false }) =
                    gpsTry(app, lm, store, snap, ask.movedAtElapsedMs, boundMs, accept, shortMs, stopEarly, disagreement = disagreement).fix
                // Design 125 A3: on a learned miss, the Apple lookup beside GPS - then the table, with its answer, again.
                // Design 125b C4: the cells in a request of their own beside it (a missing scan view is a miss too), never
                // while still but for a `locate` (code QA L5); their rows give the cell fix at the attempt's end
                // ([cellOnly] reads the table again). Code QA M3: the cells' ask runs apart ([askBeside], in [scope]: its
                // answer is stored even when the attempt kept a finer fix and ended) - the Wi-Fi fix and `stopEarly` never
                // wait for its fixed answer time; it is awaited only before [cellOnly].
                val cellAsk = java.util.concurrent.atomic.AtomicReference<Deferred<Boolean>?>(null)
                val askCells = cellAskAllowed(appleStillMotion(ask.motion), plan == AttemptPlan.LOCATE)
                suspend fun appleFix(on: Snapshot? = null): LearnedFix? {
                    if (store == null || !appleAllowed(app)) return null
                    val s = on ?: snap.await()
                    val (wifiAsked, cells) = askBeside(scope, if (askCells) ({ askAppleCells(app, store, s) }) else null) {
                        askApple(app, store, s, ask.movedAtElapsedMs)
                    }
                    if (cellAsk.get() == null) cellAsk.set(cells)
                    if (!wifiAsked) return null
                    return open(app)?.let { lookup(app, it, s, ask.movedAtElapsedMs, disagreement) }?.takeIf { standsAlone(it, movedSinceFresh) }
                }
                // Design 138 rev 3: connected to a confirmed router and no other Wi-Fi fix - the router's own fix.
                suspend fun routerOnly(on: Snapshot? = null): LearnedFix? = store?.let { routerOnlyFix(app, it, on ?: snap.await(), ask) }
                // Design 131: what confirmed the Wi-Fi fix, and what may become of it before GPS has a say.
                suspend fun judge(fix: LearnedFix?, on: Snapshot? = null): Judged? = fix?.let { judged(app, store, on ?: snap.await(), it, ask, disagreement) }
                fun report(j: Judged, check: FixCheck?): Location? =
                    location(j.fix, check).takeIf(accept)?.also { ask.notFresh = !wifiFresh(j.fix, check) }
                // A9 (H2): still means still - the vouch renewed, never replaced; with its own check (never a better one).
                fun renew(): Location? = ask.stillVouch.also {
                    ask.renewed = true
                    ask.renewedBy = Renewal.APPLE
                }
                // The order after the judging is the pure [attemptKeep]'s, every plan's.
                fun keep(gps: Location?, j: Judged?): Location? {
                    val k = attemptKeep(plan, j?.verdict?.step, j?.fix, j?.check, gps?.let(::teachFix), release)
                    if (k.disagreed) {
                        Log.w(LOG_TAG, "satellites overruled a Wi-Fi fix")
                        disagreement.mark()
                    }
                    return when (k.kept) {
                        Kept.GPS -> gps
                        Kept.WIFI -> j?.let { report(it, k.check) } ?: gps
                        Kept.RENEW -> renew() ?: gps
                        Kept.NONE -> null
                    }
                }
                // Design 134 D3: the snapshot whose answer the attempt ends with (the second scan's when it ran).
                var last: Snapshot? = null
                val kept = when (plan) {
                    // The lookup runs on the first scan beside GPS (code QA L5): independent of what the GPS teaches.
                    AttemptPlan.TOGETHER -> {
                        val early = async { judge(learned() ?: appleFix() ?: routerOnly()) }
                        val first = keep(gps(GPS_MS), early.await())
                        val s = snap.await()
                        // Design 134 D3: after a move, an inconclusive first scan and no fresh fix - one more scan when a
                        // fresh one could decide (the table's trusted networks, or the Apple lookup).
                        val freshFirst = first != null && !ask.notFresh && !ask.renewed
                        val appleMay = appleAllowed(app) && appleMayAskNow(app, KEY_APPLE_WAIT)
                        val afterMove = ask.heartbeat && ask.motion == Motion.MOVED
                        if (store != null && secondScanWorth(afterMove, s.answer, freshFirst, trustedNetworkCount(store.rows, nowMin()), appleMay)) {
                            delay(secondScanWaitMs(s.request?.atElapsedMs ?: 0L, SystemClock.elapsedRealtime()))
                            val again = rescan(app, s, ask.movedAtElapsedMs)
                            last = again
                            Log.i(LOG_TAG, "second scan " + (again.answer?.inconclusive?.wire ?: "conclusive"))
                            keep(null, judge(learned(again) ?: appleFix(again) ?: routerOnly(again), again)) ?: first
                        } else {
                            first
                        }
                    }
                    AttemptPlan.LEARNED_FIRST -> {
                        val judged = judge(learned())
                        when {
                            judged != null && !runsGps(plan, judged.verdict.step) -> keep(null, judged)
                            judged == null && renews(app, store, snap.await(), ask) -> ask.vouched.also { ask.renewed = true }
                            // Design 134 revision 4 (D20, D24): beside the zone link, the connected router - no zone needed.
                            judged == null && store != null && witness(app, store, snap.await(), ask) == StillWitness.RENEWS -> ask.vouched.also {
                                ask.renewed = true
                                ask.renewedBy = Renewal.CONNECTED
                            }
                            // Not standing alone, refused, or nothing: GPS (and Apple beside it on a miss) - TOGETHER's way.
                            else -> {
                                // Design 138 rev 3: the single-router fix after the Apple ask (its answers judged too).
                                val early = async { judged ?: judge(appleFix() ?: routerOnly()) }
                                keep(gps(GPS_MS), early.await())
                            }
                        }
                    }
                    AttemptPlan.GPS_FIRST -> {
                        val early = async { judge(learned() ?: appleFix() ?: routerOnly()) }
                        val satellites = gps(GPS_MS)
                        keep(satellites, early.await())
                    }
                    AttemptPlan.LOCATE -> {
                        val early = async { judge(learned() ?: appleFix() ?: routerOnly()) }
                        val fix = gps(LOCATE_MS, LOCATE_SHORT_MS) {
                            early.await()?.let { it.verdict.step == WifiStep.USE && locateStopsShort(it.fix, it.check) } == true
                        }
                        keep(fix, early.await())
                    }
                }
                // Design 134 D2, D4: the attempt's last scan said nothing and no fresh fix came - after a move nothing is
                // reported (no cell fix, no unchecked or fallback fix, no last-known), and the flags stay as they were.
                val ended = last ?: snap.await()
                // Design 134 revision 4 (D20.1): the connected AP this attempt read - the vouch's link if its fix becomes one.
                ask.linkAp = store?.let { linkAp(ended.link, ended.connected, it.keys, ended.answer?.answering.orEmpty()).ap }
                // Code QA M1: a renewal in this attempt (a zone link, an Apple fix within the vouch) vetoes a mismatch.
                ask.inconclusive = ended.answer?.inconclusive
                if (withheld(ask.mayWithhold, ask.inconclusive, kept != null && !ask.notFresh && !ask.renewed)) {
                    ask.withheld = true
                    Log.i(LOG_TAG, "attempt path=$plan result=withheld reason=${ask.inconclusive?.wire}")
                    shadow(app, store, print, ask, ended, null)
                    return@coroutineScope null
                }
                val result = kept ?: run {
                    cellAsk.get()?.await()
                    cellOnly(app, store, snap.await(), ask, plan == AttemptPlan.LOCATE, accept)
                }
                shadow(app, store, print, ask, ended, result)
                Log.i(LOG_TAG, "attempt path=$plan result=${if (ask.renewed) "renewed" else result?.provider ?: "none"}")
                result
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(LOG_TAG, "attempt failed: ${e.javaClass.simpleName}")
            return null
        } finally {
            // Design 138 D6: a Wi-Fi teach only from an attempt in which nothing disagreed.
            ask.teachLater?.takeIf { !disagreement.seen && ask.inconclusive == null && !ask.withheld }?.let { runCatching { it() } }
            ownTeaching.decrementAndGet()
            // Design 131: however many rules saw it, one rise an attempt.
            ZoneWatch.countWifiDisagreed(app, disagreement.rise())
        }
    }

    /**
     * Design 134 at an attempt's end, with its last scan [s]: a conclusive answer's material for a print (D8); then, with
     * a print in force and the heartbeat's own `FIX` - after a move (`MOVED`) the shadow verdict or an inconclusive
     * event (D10; it never acts, D6); while `STILL` (a cap try or retry) revision 3's mismatch (D15: only with no fresh
     * fix; recorded, D19) or, at a renewal where the rule held, the print grown (D8, QA M5). The still tier records no
     * verdict. Never throws into the attempt.
     */
    private fun shadow(context: Context, store: Store?, print: StillPrint?, ask: Ask, s: Snapshot, result: Location?) {
        try {
            store ?: return
            val answer = s.answer ?: return
            val now = nowMin()
            val material = if (answer.conclusive) {
                val heard = hear(answer.answering, null, store.keys, moving(context), emptyMap(), SystemClock.elapsedRealtime()).heard
                PrintMaterial(heard, settledKeys(store.rows, now), s.cells).also { ask.material = it }
            } else {
                null
            }
            if (print == null || !ask.heartbeat) return
            val at = EventAt(now, SystemClock.elapsedRealtime(), BootClock.bootCount())
            val fresh = result != null && !ask.notFresh && !ask.renewed
            when (ask.motion) {
                Motion.MOVED -> {
                    val event = if (material == null) {
                        inconclusiveEvent(print, answer.inconclusive ?: return, at)
                    } else {
                        // D9.5: a fresh fix of this attempt outside both accuracies of the vouch says elsewhere too.
                        val v = ask.vouchFix
                        val far = fresh && v != null &&
                            distanceM(v.latitude, v.longitude, result.latitude, result.longitude) > (v.accuracy ?: 0f) + (if (result.hasAccuracy()) result.accuracy else 0f)
                        verdictEvent(print, shadowVerdict(print, printScan(material.heard, material.settled), ask.elsewhere || far), at)
                    }
                    StillPrints.record(context, event)
                }
                Motion.STILL -> {
                    material ?: return
                    val scan = printScan(material.heard, material.settled)
                    // D15 (the owner's refinement): the registered cell counts only by a position the table already holds
                    // (an own cell row, or an Apple cell row from design 125b's answers) - never a lookup for this.
                    val vouch = ask.vouchFix?.let { VouchPoint((it.timeMs / 60_000).toInt(), it.latitude, it.longitude, it.accuracy ?: 0f) }
                    val cellAway = !fresh && cellElsewhere(material.cells, open(context)?.rows ?: store.rows, now, vouch)
                    val clause = if (fresh) null else printMismatch(print, scan, cellAway)
                    if (clause != null) {
                        // Code QA M1 (D16): a renewal in the same attempt is positive evidence of the place - it vetoes the
                        // mismatch, which is still recorded (flagged) for the owner's record; so does the connected, bound,
                        // settled AP of the vouch (revision 4, D23), whatever its level.
                        val vetoed = mismatchVetoed(ask.renewed, if (ask.renewed) null else witness(context, store, s, ask))
                        if (!vetoed) ask.mismatch = clause
                        StillPrints.record(context, mismatchEvent(print, clause, scan, at, vetoed = vetoed))
                    } else if (ask.renewed && shadowVerdict(print, scan, veto = false).would) {
                        StillPrints.grow(context, print, grownPrint(print, scan.devices))
                    }
                }
                else -> {}
            }
        } catch (e: Exception) {
            Log.w(LOG_TAG, "fingerprint skipped: ${e.javaClass.simpleName}")
        }
    }

    /**
     * Design 134 after an attempt's sync: [fresh] - the genuinely fresh fix that is now the vouch (`null` none) - judges
     * the ring's pending events (D11: `gps` or a checked Wi-Fi fix, the distances from the vouch before it, [ask]'s
     * `vouchFix`), then replaces the print: the old one dropped, a new one taken when [printRefusal] allows (D8). [moving]:
     * the fix was on the move. Never throws.
     */
    fun afterFreshFix(context: Context, ask: Ask, fresh: com.kidslauncher.mdm.zones.QueuedFix, moving: Boolean) {
        try {
            val app = context.applicationContext
            val nowMin = nowMin()
            val outcome = fresh.provider == "gps" || (fresh.provider in PRINT_WIFI_PROVIDERS && fresh.check != null)
            val acc = fresh.accuracy
            if (outcome && acc != null) {
                val vouch = ask.vouchFix?.let { VouchPoint((it.timeMs / 60_000).toInt(), it.latitude, it.longitude, it.accuracy ?: 0f) }
                StillPrints.afterFix(app, OutcomeFix(fresh.latitude, fresh.longitude, acc, nowMin, BootClock.bootCount(), MotionGate.snapshot(app).movedAtElapsedMs), vouch)
            }
            // Revision 4 (D20.1): the connected AP read with the new vouch, in memory; none on the move or not connected.
            vouchLink = vouchLinkOf(fresh.timeMs, BootClock.bootCount(), ask.linkAp, moving)
            Log.i(LOG_TAG, "vouch link " + if (vouchLink != null) "read level=" + (if (ask.linkAp?.fresh == true) "scan" else "link") else "none")
            val old = StillPrints.current(app)
            if (old != null && old.fixTimeMs != fresh.timeMs) StillPrints.drop(app, DropReason.REPLACED)
            val material = ask.material
            val refusal = printRefusal(fresh.provider, fresh.check, acc, moving, fresh.mock, release, material != null)
            val elapsed = fresh.elapsedMs
            // Without a trusted sensor no heartbeat could use a print (D14): none is taken.
            val sensor = ask.motion != Motion.NO_SENSOR
            val taken = if (sensor && refusal == null && material != null && elapsed != null) {
                takePrint(fresh.timeMs, elapsed, BootClock.bootCount(), SystemClock.elapsedRealtime(), nowMin, material.heard, material.settled)
            } else {
                null
            }
            if (taken != null) {
                StillPrints.take(app, taken)
            } else if (sensor && material != null) {
                Log.i("StillPrint", "not taken (${(refusal ?: PrintRefusal.PAIR).wire})")
            }
        } catch (e: Exception) {
            Log.w(LOG_TAG, "fingerprint skipped: ${e.javaClass.simpleName}")
        }
    }

    /** A Wi-Fi fix with what confirmed it and its verdict (design 131). */
    private class Judged(val fix: LearnedFix, val check: FixCheck?, val verdict: WifiVerdict)

    /**
     * Design 131: [fix]'s check - the link ([linkConfirms]: the connection now, the table as it is now) or the last
     * satellite fix of this boot ([gnssConfirms]) - and [wifiVerdict] with the still vouch and the jump from the last
     * satellite fix ([jumpGate]). A disagreement marks [disagreement]; an Apple fix refused while still counts as before.
     */
    private fun judged(context: Context, store: Store?, snap: Snapshot, fix: LearnedFix, ask: Ask, disagreement: Disagreement): Judged {
        val now = nowMin()
        val nowElapsed = SystemClock.elapsedRealtime()
        val boot = BootClock.bootCount()
        val rows = open(context)?.rows ?: store?.rows.orEmpty()
        val conn = store?.let { linkConn(snap.connected, it.keys) }
        val router = store?.let { routerOf(snap.connected, it.keys) }
        val zones = store?.let { linkZones(it.keys) }.orEmpty()
        val gnss = lastGnss(context)
        val gnssNow = gnss?.takeIf { it.bootCount == boot }?.let { TeachFix(it.lat, it.lng, it.accuracyM.toFloat(), it.speedMps, it.elapsedMs, "gps", null, false) }
        // Design 138 D8: what the same scan says without the connected router.
        val rest = if (store != null && router != null && !fix.router) {
            scanView(hearing(context, store, snap, remember = false), nowElapsed, ask.movedAtElapsedMs)?.let { restWithout(it, rows, now, router) }
        } else {
            null
        }
        val confirmed = store?.let { confirmedAps(ZoneWatch.watchedZones(), it.keys) }.orEmpty()
        // Design 138 rev 3: the single-router fix passed its own vetoes (routerFix); every other fix the link check.
        val by = if (fix.router) LinkBy.CONFIRMED else linkCheck(fix, router, conn, rows, zones, now, confirmed, struck(context), rest) { z -> cellAway(snap.cells, rows, now, z, fix.accuracyM) }
        if (by == LinkBy.LEARNED && store != null && router != null) {
            zones.forEach { z -> save(context, store.generation) { touchRouter(it, z, router, now) } }
        }
        if (router != null && zones.isNotEmpty()) {
            Log.i(LOG_TAG, "zone link " + (by?.wire ?: "none") + " rest=" + when (rest) {
                is Rest.Fix -> "fix"
                is Rest.One -> "one"
                Rest.Nothing -> "nothing"
                Rest.NoFix -> "nofix"
                null -> "unknown"
            })
        }
        val check = when {
            by != null -> FixCheck.LINK
            gnssConfirms(gnssNow, fix, release) -> FixCheck.GNSS
            else -> null
        }
        val vouch = ask.stillVouch?.let { Vouched(it.latitude, it.longitude, if (it.hasAccuracy()) it.accuracy.toDouble() else 0.0) }
        val jump = jumpGate(fix, gnss, TrustedClock.nowMs(), nowElapsed, boot)
        val verdict = wifiVerdict(fix, check, ask.motion, vouch, jump)
        // Design 134 D9.5: a Wi-Fi fix outside both accuracies of the vouch, or an impossible jump, says elsewhere.
        val v = ask.vouchFix
        if (jump == Jump.IMPOSSIBLE || (v != null && distanceM(v.latitude, v.longitude, fix.lat, fix.lng) > fix.accuracyM + (v.accuracy ?: 0f))) ask.elsewhere = true
        Log.i(LOG_TAG, "wifi fix check=${check?.wire ?: "none"} step=${verdict.step}")
        // Design 138 D6: the learned link's teach from this fix, at the attempt's end when nothing disagreed.
        if (store != null && router != null && !fix.router) ask.teachLater = { teachFromWifi(context, snap, fix, check, verdict.step, rest) }
        if (verdict.disagreed) {
            disagreement.mark()
            if (fix.source != PlaceSource.OWN && appleStillMotion(ask.motion)) appleContradiction(context)
        }
        return Judged(fix, check, verdict)
    }

    /** D12: the cap try may renew the vouch - motion `STILL`, no refresh due, the vouched fix surely inside a zone whose
     * link the connected unmetered network holds, under the binding it was linked with (design 131). */
    private fun renews(context: Context, store: Store?, snap: Snapshot, ask: Ask): Boolean {
        val vouched = ask.vouched ?: return false
        store ?: return false
        val conn = linkConn(snap.connected, store.keys)
        val refresh = refreshDue(prefs(context).getLong(KEY_LAST_GPS_TRY, 0L), System.currentTimeMillis())
        val v = Vouched(vouched.latitude, vouched.longitude, if (vouched.hasAccuracy()) vouched.accuracy.toDouble() else Double.MAX_VALUE)
        val router = routerOf(snap.connected, store.keys)
        val confirmed = confirmedAps(ZoneWatch.watchedZones(), store.keys)
        return zoneRenews(store.rows, linkZones(store.keys), v, router, conn, ask.motion == Motion.STILL, refresh, nowMin(), confirmed, struck(context))
    }

    /**
     * Design 134 revision 4 (D20): the connected router's answer for [ask] on [snap]'s connection - once an attempt, logged
     * with its reason when it doesn't renew (levels only, never the BSSID). The vouch is [Ask.vouched] (the cap try's).
     */
    private fun witness(context: Context, store: Store, snap: Snapshot, ask: Ask): StillWitness {
        ask.witness?.let { return it }
        val now = linkAp(snap.link, snap.connected, store.keys, snap.answer?.answering.orEmpty())
        val link = vouchLink
        val w = stillWitness(
            ask.motion, ask.vouched?.time, BootClock.bootCount(), link, now, open(context)?.rows ?: store.rows, nowMin(), ask.stillCeilingPassed,
        )
        ask.witness = w
        // Code QA H1: whether the level is the answering scan's (fresh) or the connection's (stale while the screen is off).
        val ap = now.ap
        val rssi = ap?.rssi
        val level = if (ap == null || rssi == null || link == null) {
            ""
        } else {
            val screen = if (context.getSystemService(PowerManager::class.java)?.isInteractive == true) "on" else "off"
            " level=${if (ap.fresh) "scan" else "link"} screen=$screen dbm=$rssi vouch=${link.rssi} diff=${rssi - link.rssi}"
        }
        Log.i(LOG_TAG, "connected witness " + (if (w == StillWitness.RENEWS) "renews" else "no reason=${w.wire}") + level)
        return w
    }

    // --- The teaching fix (D11) ---

    private fun teachIntent(context: Context): PendingIntent = PendingIntent.getBroadcast(
        context,
        TEACH_REQUEST_CODE,
        Intent(context, WifiPlacesTeachReceiver::class.java).setAction(ACTION_TEACH),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    /** The teaching fix may run: a Regularly phone, Location on and the `network` provider off (D11, code QA L2). */
    private fun teachAllowed(context: Context, lm: LocationManager): Boolean =
        ZoneWatch.stillOn(context, currentPolicyDecision().policy) && QuickControls.isLocationEnabled(context) && networkOff(lm)

    /** A motion fire: the teaching fix [CONFIRM_DELAY_MS] later when it may run (a later fire moves it on - it runs at
     * the stop). Off the sensor callback's thread: the check reads the policy. */
    fun armTeach(context: Context) {
        val app = context.applicationContext
        scope.launch {
            try {
                val lm = app.getSystemService(LocationManager::class.java) ?: return@launch
                if (!teachAllowed(app, lm)) return@launch
                val alarms = app.getSystemService(AlarmManager::class.java) ?: return@launch
                val at = SystemClock.elapsedRealtime() + CONFIRM_DELAY_MS
                if (alarms.canScheduleExactAlarms()) {
                    alarms.setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, at, teachIntent(app))
                } else {
                    alarms.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, at, teachIntent(app))
                }
            } catch (e: Exception) {
                Log.w(LOG_TAG, "teaching fix not armed: ${e.javaClass.simpleName}")
            }
        }
    }

    /**
     * D11's teaching fix: when it may run ([teachAllowed]), no attempt of ours runs (L3) and the day's budget isn't
     * spent - a scan and the GPS stream until a fix teaches (or 15 s, or one over 3 m/s); a fix that taught spends one of
     * [TEACH_PER_DAY] (L1). On a zones phone the fix goes to the kept slot, otherwise it is not reported. Not a heartbeat
     * try: design 124's flags don't hear it.
     *
     * No `goAsync` (code QA L8): the work outlives the broadcast on our own scope, and that is safe here - the process is
     * kept by the anchor's foreground service, and the partial wake lock (taken before the receiver returns) keeps the
     * CPU for the scan and the GPS through Doze; a process death just loses this one teach.
     */
    internal fun onTeachAlarm(context: Context) {
        val app = context.applicationContext
        val wake = try {
            app.getSystemService(PowerManager::class.java)?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "kidslauncher:wifi_places")
                ?.apply { setReferenceCounted(false); acquire(TEACH_WAKELOCK_MS) }
        } catch (e: Exception) {
            null
        }
        scope.launch {
            try {
                val lm = app.getSystemService(LocationManager::class.java) ?: return@launch
                if (!teachAllowed(app, lm) || ownTeaching.get() > 0 || teachesLeft(budget(app), LocalDate.now().toString()) <= 0) return@launch
                val store = open(app) ?: return@launch
                ownTeaching.incrementAndGet()
                try {
                    val snap = async { snapshot(app, store.keys, 0L) }
                    val moved = MotionGate.snapshot(app).movedAtElapsedMs
                    val run = gpsTry(app, lm, store, snap, moved, GPS_MS, { ZoneWatch.accept(app, it) }, untilTeaches = true)
                    snap.cancel()
                    if (run.taught) spendTeach(app)
                    Log.i(LOG_TAG, "teaching fix ${if (run.taught) "taught" else "none"}")
                    if (run.fix != null && ZoneWatch.zonesOn(app, currentPolicyDecision().policy)) LocateCommands.keepFix(app, run.fix)
                } finally {
                    ownTeaching.decrementAndGet()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(LOG_TAG, "teaching fix failed: ${e.javaClass.simpleName}")
            } finally {
                runCatching { wake?.release() }
            }
        }
    }

    private fun budget(context: Context): TeachBudget? = try {
        prefs(context).getString(KEY_BUDGET, null)?.let { ServerJson.decodeFromString(TeachBudget.serializer(), it) }
    } catch (e: Exception) {
        null
    }

    private fun spendTeach(context: Context) = synchronized(lock) {
        val after = budgetAfterTeach(budget(context), LocalDate.now().toString())
        prefs(context).edit().putString(KEY_BUDGET, ServerJson.encodeToString(TeachBudget.serializer(), after)).commit()
    }
}

/** The teaching fix's alarm - our own explicit PendingIntent only (not exported). */
class WifiPlacesTeachReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_TEACH) return
        WifiPlaces.onTeachAlarm(context)
    }
}
