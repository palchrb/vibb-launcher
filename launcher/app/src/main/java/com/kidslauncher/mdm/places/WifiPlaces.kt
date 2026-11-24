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
import android.net.NetworkCapabilities
import android.net.wifi.ScanResult
import android.net.wifi.WifiManager
import android.os.PowerManager
import android.os.SystemClock
import android.telephony.CellIdentityGsm
import android.telephony.CellIdentityLte
import android.telephony.CellIdentityNr
import android.telephony.CellIdentityWcdma
import android.telephony.CellInfo
import android.telephony.TelephonyManager
import android.util.AtomicFile
import android.util.Log
import com.kidslauncher.mdm.BuildConfig
import com.kidslauncher.mdm.preferences.LauncherPreferences
import com.kidslauncher.mdm.server.DeviceToken
import com.kidslauncher.mdm.server.LocateCommands
import com.kidslauncher.mdm.server.QuickControls
import com.kidslauncher.mdm.server.Secrets
import com.kidslauncher.mdm.server.ServerJson
import com.kidslauncher.mdm.server.createMdmApi
import com.kidslauncher.mdm.server.currentPolicyDecision
import com.kidslauncher.mdm.zones.CONFIRM_DELAY_MS
import com.kidslauncher.mdm.zones.Motion
import com.kidslauncher.mdm.zones.MotionGate
import com.kidslauncher.mdm.zones.ZoneWatch
import com.kidslauncher.mdm.zones.distanceM
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

/** Design 125 step 2: the policy's switch as the last fresh policy said, the wait after an answer, a 409's "off". */
private const val KEY_APPLE_ON = "apple_on"
private const val KEY_APPLE_WAIT = "apple_wait_until_ms"
private const val KEY_APPLE_OFF = "apple_off"
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
     * inside its circle while it is under 4 h old is not reported (design 125b C9.3).
     */
    class Ask(
        val motion: Motion,
        val movedAtElapsedMs: Long,
        val vouched: Location? = null,
        val stillVouch: Location? = null,
        val lastFresh: Location? = null,
    ) {
        @Volatile var renewed = false

        /** The renewal came from an Apple fix within the vouch, not a zone link (A9). */
        @Volatile var renewedByApple = false

        @Volatile var notFresh = false
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
        lastLearned = null
        if (!had) return
        f.delete()
        p.edit().clear().commit()
        Log.i(LOG_TAG, "cleared ($reason)")
    }

    // --- Reading the radio (D10, D13, D17) ---

    /** What one read of the radio heard: the Wi-Fi results, the connected network, the serving cell. Never stored. */
    private class Snapshot(val results: List<ScanAp>, val connected: Connected?, val cell: Heard?)

    private fun wifi(context: Context) = context.applicationContext.getSystemService(WifiManager::class.java)

    /** Code QA L6: a scan refused - counts only, by reason. */
    private fun refused(reason: String) {
        val line = synchronized(refusals) {
            refusals[reason] = (refusals[reason] ?: 0) + 1
            refusals.entries.joinToString(" ") { "${it.key}=${it.value}" }
        }
        Log.i(LOG_TAG, "scan refusals $line")
    }

    private fun cached(context: Context): List<ScanAp> = try {
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
     * `startScan` and its results within [SCAN_WAIT_MS] - refused or no answer: the cache (the 2-min fallback). */
    private suspend fun scanNow(context: Context, movedAtElapsedMs: Long): List<ScanAp> {
        val now = SystemClock.elapsedRealtime()
        val before = cached(context)
        val list = moving(context)
        val fresh = before.count { now - it.elapsedMs in 0..SCAN_FRESH_MS && it.elapsedMs > movedAtElapsedMs && usableAp(it.bssid, it.ssid, list) }
        if (fresh >= SCAN_RESULTS_MIN) return before
        val wm = wifi(context) ?: return before
        val done = CompletableDeferred<Unit>()
        val callback = object : WifiManager.ScanResultsCallback() {
            override fun onScanResultsAvailable() {
                done.complete(Unit)
            }
        }
        try {
            wm.registerScanResultsCallback(executor, callback)
            @Suppress("DEPRECATION")
            when {
                !wm.startScan() -> refused(if (wm.isWifiEnabled) "throttled" else "wifioff")
                withTimeoutOrNull(SCAN_WAIT_MS) { done.await() } == null -> refused("unanswered")
            }
        } catch (e: SecurityException) {
            refused("permission")
        } catch (e: Exception) {
            refused("failed")
        } finally {
            runCatching { wm.unregisterScanResultsCallback(callback) }
        }
        return cached(context)
    }

    /** D4, D17: the connected network, with its metering from the active network's capabilities (merged through our VPN). */
    private fun connected(context: Context): Connected? = try {
        @Suppress("DEPRECATION")
        val info = wifi(context)?.connectionInfo
        val bssid = connectedBssid(info?.bssid)
        if (info == null || bssid == null) {
            null
        } else {
            val cm = context.getSystemService(ConnectivityManager::class.java)
            val caps = cm?.activeNetwork?.let { cm.getNetworkCapabilities(it) }
            val unmetered = caps != null && caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) &&
                caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
            val ssid = info.ssid?.removeSurrounding("\"")?.takeIf { it != WifiManager.UNKNOWN_SSID }
            Connected(bssid, ssid, info.rssi, info.frequency, unmetered)
        }
    } catch (e: Exception) {
        null
    }

    /** D10: the serving cell, asked once more when the cached one is over a minute old; `null` without a SIM. The fine
     * location permission is self-granted by `currentLocation`; a refusal is caught. */
    @Suppress("MissingPermission")
    private suspend fun servingCell(context: Context, keys: PlaceKeys): Heard? {
        val tm = context.getSystemService(TelephonyManager::class.java) ?: return null
        fun pick(cells: List<CellInfo>?) = cells.orEmpty().firstOrNull { it.isRegistered }
        var cell = try {
            pick(tm.allCellInfo)
        } catch (e: Exception) {
            null
        }
        if (cell == null || SystemClock.elapsedRealtime() - cell.timestampMillis > CELL_STALE_MS) {
            cell = withTimeoutOrNull(CELL_WAIT_MS) {
                suspendCancellableCoroutine { cont ->
                    try {
                        tm.requestCellInfoUpdate(
                            executor,
                            object : TelephonyManager.CellInfoCallback() {
                                override fun onCellInfo(cellInfo: MutableList<CellInfo>) {
                                    if (cont.isActive) cont.resume(pick(cellInfo))
                                }

                                override fun onError(errorCode: Int, detail: Throwable?) {
                                    if (cont.isActive) cont.resume(null)
                                }
                            },
                        )
                    } catch (e: Exception) {
                        if (cont.isActive) cont.resume(null)
                    }
                }
            } ?: cell
        }
        val c = cell ?: return null
        val id = cellId(c) ?: return null
        val key = keys.cell(id)
        return Heard(key, key.take(16), key.take(16), Band.CELL, 0, c.timestampMillis, kind = RowKind.CELL)
    }

    /** `mcc|mnc|area|cid` of an LTE, NR, WCDMA or GSM serving cell; `null` when any part is unavailable. */
    private fun cellId(c: CellInfo): String? {
        val un = CellInfo.UNAVAILABLE
        return when (val id = c.cellIdentity) {
            is CellIdentityLte -> if (id.tac == un || id.ci == un) null else listOf(id.mccString, id.mncString, id.tac, id.ci)
            is CellIdentityNr -> if (id.tac == un || id.nci == CellInfo.UNAVAILABLE_LONG) null else listOf(id.mccString, id.mncString, id.tac, id.nci)
            is CellIdentityWcdma -> if (id.lac == un || id.cid == un) null else listOf(id.mccString, id.mncString, id.lac, id.cid)
            is CellIdentityGsm -> if (id.lac == un || id.cid == un) null else listOf(id.mccString, id.mncString, id.lac, id.cid)
            else -> null
        }?.takeIf { it[0] != null && it[1] != null }?.joinToString("|")
    }

    private suspend fun snapshot(context: Context, keys: PlaceKeys, movedAtElapsedMs: Long): Snapshot = coroutineScope {
        val cell = async { servingCell(context, keys) }
        val results = scanNow(context, movedAtElapsedMs)
        Snapshot(results, connected(context), cell.await())
    }

    private fun hearing(context: Context, store: Store, snap: Snapshot, remember: Boolean): Hearing {
        val h = hear(snap.results, snap.connected, store.keys, moving(context), readings, SystemClock.elapsedRealtime())
        if (remember) readings = h.readings
        return h
    }

    // --- The lookup (D9, D10) ---

    private fun nowMin() = (System.currentTimeMillis() / 60_000).toInt()

    private fun today() = LocalDate.now().toEpochDay().toInt()

    /** D9 and D10's veto on [snap]: the learned fix, or `null`. Touches `lastSeen` of the rows it used. */
    private fun lookup(context: Context, store: Store, snap: Snapshot, movedAtElapsedMs: Long): LearnedFix? {
        val h = hearing(context, store, snap, remember = true)
        val view = scanView(h, SystemClock.elapsedRealtime(), movedAtElapsedMs)
        val now = nowMin()
        val fix = view?.let { learnedFix(it, store.rows, now) }?.takeUnless { cellVetoes(snap.cell, store.rows, it, now) }
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
    private fun cellOnly(context: Context, store: Store?, snap: Snapshot, ask: Ask, locate: Boolean, accept: (Location) -> Boolean): Location? {
        store ?: return null
        val fix = cellFix(snap.cell, store.rows, nowMin()) ?: return null
        save(context, store.generation) { touchSeen(it, fix.keys, nowMin()) }
        fun seen(l: Location?) = l?.let { SeenFix(it.latitude, it.longitude, if (it.hasAccuracy()) it.accuracy.toDouble() else 0.0, it.time) }
        val verdict = cellReport(fix, appleStillMotion(ask.motion), seen(ask.stillVouch), seen(ask.lastFresh), System.currentTimeMillis(), locate)
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
            if (p.contains(KEY_APPLE_ON) || p.contains(KEY_APPLE_OFF) || p.contains(KEY_APPLE_WAIT)) {
                p.edit().remove(KEY_APPLE_ON).remove(KEY_APPLE_OFF).remove(KEY_APPLE_WAIT).commit()
                Log.i(LOG_TAG, "apple rows dropped (switch off)")
            }
        } catch (e: Exception) {
            Log.w(LOG_TAG, "apple switch not applied: ${e.javaClass.simpleName}")
        }
    }

    /** A3: the switch on, no wait or 409 in force, and a network up (offline: no ask and nothing changed). */
    private fun appleAllowed(context: Context): Boolean {
        val p = prefs(context)
        if (!p.getBoolean(KEY_APPLE_ON, false)) return false
        if (!appleMayAsk(System.currentTimeMillis(), p.getLong(KEY_APPLE_WAIT, 0L), p.getBoolean(KEY_APPLE_OFF, false))) return false
        return try {
            val cm = context.getSystemService(ConnectivityManager::class.java)
            cm?.activeNetwork?.let { cm.getNetworkCapabilities(it) }?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
        } catch (e: Exception) {
            false
        }
    }

    /**
     * A3, A4: a learned miss's usable BSSIDs (none with a trusted own row, a live Apple row or a tombstone) asked of
     * the server, at most 15 s; the answer's rows stored (A9), its wait or "off" kept. `true` when rows were stored.
     */
    private suspend fun askApple(context: Context, store: Store, snap: Snapshot, movedAtElapsedMs: Long): Boolean {
        val view = scanView(hearing(context, store, snap, remember = false), SystemClock.elapsedRealtime(), movedAtElapsedMs) ?: return false
        val now = nowMin()
        val asking = appleAsk(view.heard, store.rows, now)
        if (asking.isEmpty()) return false
        val (status, body) = withTimeoutOrNull(APPLE_TIMEOUT_MS) { postLookup(asking) } ?: (0 to null)
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

    /** The server's answer: its status and body (0 = no answer). */
    private suspend fun postLookup(asking: List<String>): Pair<Int, String?> = try {
        val url = LauncherPreferences.mdm().serverUrl()
        val token = DeviceToken.get()
        if (url.isNullOrBlank() || token.isNullOrBlank()) {
            0 to null
        } else {
            val body = ServerJson.encodeToString(WifiLookupRequest.serializer(), WifiLookupRequest(asking))
                .toRequestBody("application/json".toMediaType())
            val response = createMdmApi(url, token, callTimeoutMs = APPLE_TIMEOUT_MS).wifiLookup(body)
            response.code() to (if (response.isSuccessful) response.body() else response.errorBody())?.use { it.string() }
        }
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.w(LOG_TAG, "apple ask failed: ${e.javaClass.simpleName}")
        0 to null
    }

    private fun location(fix: LearnedFix): Location = Location(fix.provider).apply {
        latitude = fix.lat
        longitude = fix.lng
        accuracy = fix.accuracyM.toFloat()
        elapsedRealtimeNanos = fix.elapsedMs * 1_000_000
        time = System.currentTimeMillis() - (SystemClock.elapsedRealtime() - fix.elapsedMs)
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
    private fun teachWith(context: Context, store: Store, snap: Snapshot, teachers: List<TeachFix>, windowMs: Long, movedAtElapsedMs: Long): Boolean {
        if (teachers.isEmpty()) return false
        val h = hearing(context, store, snap, remember = false)
        val after = h.heard.filter { it.elapsedMs > movedAtElapsedMs }
        val cell = snap.cell?.takeIf { it.elapsedMs > movedAtElapsedMs }
        val sightings = pairings(Hearing(after + listOfNotNull(cell), h.stale, h.readings), teachers, windowMs)
        if (sightings.isEmpty()) return false
        val best = teachers.minBy { it.accuracyM ?: Float.MAX_VALUE }
        val paired = pairedWith(after, best, windowMs, movedAtElapsedMs)
        val now = nowMin()
        val today = today()
        val zones = linkZones(store.keys)
        // The connected network is now's, whatever the pairing: the link's inside occasion.
        val connectedNet = h.heard.firstOrNull { it.connected }?.network
        // D16: what the table as it was said for the same results; a zone-link renewal's class by the connected network.
        val counts = mutableListOf<(DayCounts) -> DayCounts>()
        // D16 by class, and design 125's by source with the error: the same paired results, the table as it was.
        val before = learnedFix(ScanView(paired, true), store.rows, now)
        leaveOneOut(before, best.lat, best.lng)?.let { (cls, hit) -> counts += { c -> c.loo(cls, hit) } }
        before?.let { fix ->
            val err = distanceM(fix.lat, fix.lng, best.lat, best.lng)
            counts += { c -> c.looSource(fix.source, err <= fix.accuracyM, err) }
        }
        zones.firstOrNull { z -> store.rows.any { it.key == z.key && it.network == connectedNet && it.linked(now) } }?.let { z ->
            val inside = distanceM(best.lat, best.lng, z.zone.latitude, z.zone.longitude) <= z.zone.radiusM
            counts += { c -> c.loo(LooClass.ZONE, inside) }
        }
        if (paired.isNotEmpty() && learnedContradiction(lastLearned, best.lat, best.lng, best.elapsedMs)) {
            Log.w(LOG_TAG, "learned contradiction")
            counts += { c -> c.copy(contradictions = c.contradictions + 1) }
        }
        if (counts.isNotEmpty()) count(context, today) { counts.fold(it) { c, f -> f(c) } }
        var outcome: TeachOutcome? = null
        save(context, store.generation) { rows ->
            val taught = teach(rows, sightings, now, today).also { outcome = it }
            val acc = (best.accuracyM ?: TEACH_ACCURACY_MAX_M).toDouble()
            // Design 125 A9: an own teach beyond an Apple row's reach, or a trusted own row against it, tombstones it.
            val apple = reconcileApple(appleTaught(taught.rows, sightings, now), now)
            teachLinks(apple, zones, best.lat, best.lng, acc, connectedNet, paired.map { it.network }.toSet(), now, today)
        }
        outcome?.let { Log.i(LOG_TAG, "taught aps=${it.taught} new=${it.new} moved=${it.moved}") }
        return true
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
    }

    /**
     * D3: a passive fix (any app's, ours too) that passes D1 teaches against the system's cached results within
     * +-30 s, after the last motion fire - never a scan of ours. Skipped while one of our own attempts runs (it teaches
     * its stream itself). On the passive feed's executor; never throws.
     */
    fun offerPassive(context: Context, location: Location) {
        try {
            if (ownTeaching.get() > 0) return
            val fix = teaching(listOf(location))
            if (fix.isEmpty()) return
            val store = open(context) ?: return
            val results = cached(context)
            if (results.isEmpty()) return
            Log.i(LOG_TAG, "passive teach sig=${signal(location)}")
            teachWith(context, store, Snapshot(results, connected(context), null), fix, PAIR_SINGLE_MS, MotionGate.snapshot(context).movedAtElapsedMs)
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
                    snap = Snapshot(scanNow(context, SystemClock.elapsedRealtime()), snap.connected, snap.cell)
                }
                taught = teachWith(context, store, snap, teaching(stream.all()), PAIR_STREAM_MS, movedAtElapsedMs)
            }
        } finally {
            stream.stop()
        }
        val all = stream.all()
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
     * window: [AttemptPlan.TOGETHER] scans and runs GPS together and keeps the better (GPS at equal or better accuracy;
     * the learned fix only when it stands alone); [AttemptPlan.LEARNED_FIRST] the learned fix when it stands alone, else
     * D12's renewal of a still vouch, else GPS; [AttemptPlan.GPS_FIRST] GPS, then the learned fix; [AttemptPlan.LOCATE]
     * GPS for 60 s, 20 s when a learned fix of >= 3 networks within 60 m is in hand, the better kept; then a cell fix.
     * Every fix through [accept] (the mock rule). Never throws but a cancellation.
     */
    suspend fun attempt(context: Context, lm: LocationManager, path: AttemptPath, ask: Ask, accept: (Location) -> Boolean): Location? {
        val app = context.applicationContext
        ownTeaching.incrementAndGet()
        try {
            val store = open(app)
            val movedSinceFresh = ask.motion != Motion.STILL
            val refresh = refreshDue(prefs(app).getLong(KEY_LAST_GPS_TRY, 0L), System.currentTimeMillis())
            val plan = attemptPlan(path, ask.motion, refresh)
            return coroutineScope {
                // Without the table (a Keystore error) nothing is scanned, used or taught: GPS as before.
                val snap = async { if (store == null) Snapshot(emptyList(), null, null) else snapshot(app, store.keys, ask.movedAtElapsedMs) }
                suspend fun learned(): LearnedFix? = store?.let { lookup(app, it, snap.await(), ask.movedAtElapsedMs) }
                    ?.takeIf { standsAlone(it, movedSinceFresh) }
                suspend fun gps(boundMs: Long, shortMs: Long = boundMs, stopEarly: suspend () -> Boolean = { false }) =
                    gpsTry(app, lm, store, snap, ask.movedAtElapsedMs, boundMs, accept, shortMs, stopEarly).fix
                // Design 125 A3: on a learned miss, the Apple lookup beside GPS - then the table, with its answer, again.
                suspend fun appleFix(): LearnedFix? {
                    if (store == null || !appleAllowed(app)) return null
                    if (!askApple(app, store, snap.await(), ask.movedAtElapsedMs)) return null
                    return open(app)?.let { lookup(app, it, snap.await(), ask.movedAtElapsedMs) }?.takeIf { standsAlone(it, movedSinceFresh) }
                }
                val still = appleStillMotion(ask.motion)
                fun asLocation(fix: LearnedFix): Location? {
                    val vouch = ask.stillVouch?.let { Vouched(it.latitude, it.longitude, if (it.hasAccuracy()) it.accuracy.toDouble() else 0.0) }
                    return when (appleStill(fix, vouch, still)) {
                        StillVerdict.USE -> location(fix).takeIf(accept)?.also { ask.notFresh = !fix.fresh }
                        // A9 (H2): still means still - the vouch renewed, never replaced.
                        StillVerdict.RENEW -> ask.stillVouch.also {
                            ask.renewed = true
                            ask.renewedByApple = true
                        }
                        StillVerdict.CONTRADICTS -> {
                            appleContradiction(app)
                            null
                        }
                        // Still with nothing to renew: Apple's position alone moves nothing (code QA L3).
                        StillVerdict.WITHHOLD -> null
                    }
                }
                fun better(gps: Location?, fix: LearnedFix?): Location? =
                    if (fix == null || (gps != null && gpsWins(fix.accuracyM, if (gps.hasAccuracy()) gps.accuracy else null))) gps else asLocation(fix) ?: gps
                val kept = when (plan) {
                    // The lookup runs on the first scan beside GPS (code QA L5): independent of what the GPS teaches.
                    AttemptPlan.TOGETHER -> {
                        val early = async { learned() ?: appleFix() }
                        better(gps(GPS_MS), early.await())
                    }
                    AttemptPlan.LEARNED_FIRST -> {
                        val fix = learned()
                        when {
                            fix != null -> asLocation(fix)
                            renews(app, store, snap.await(), ask) -> ask.vouched.also { ask.renewed = true }
                            else -> {
                                val early = async { appleFix() }
                                better(gps(GPS_MS), early.await())
                            }
                        }
                    }
                    AttemptPlan.GPS_FIRST -> {
                        val early = async { learned() ?: appleFix() }
                        gps(GPS_MS) ?: early.await()?.let(::asLocation)
                    }
                    AttemptPlan.LOCATE -> {
                        val early = async { learned() ?: appleFix() }
                        val fix = gps(LOCATE_MS, LOCATE_SHORT_MS) { early.await()?.let { it.networks >= 3 && it.accuracyM <= LOCATE_SHORT_ACCURACY_M } == true }
                        better(fix, early.await())
                    }
                }
                val result = kept ?: cellOnly(app, store, snap.await(), ask, plan == AttemptPlan.LOCATE, accept)
                Log.i(LOG_TAG, "attempt path=$plan result=${if (ask.renewed) "renewed" else result?.provider ?: "none"}")
                result
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(LOG_TAG, "attempt failed: ${e.javaClass.simpleName}")
            return null
        } finally {
            ownTeaching.decrementAndGet()
        }
    }

    /** D12: the cap try may renew the vouch - motion `STILL`, no refresh due, the vouched fix surely inside a zone whose
     * link the connected unmetered network holds. */
    private fun renews(context: Context, store: Store?, snap: Snapshot, ask: Ask): Boolean {
        val vouched = ask.vouched ?: return false
        store ?: return false
        val h = hearing(context, store, snap, remember = false)
        val refresh = refreshDue(prefs(context).getLong(KEY_LAST_GPS_TRY, 0L), System.currentTimeMillis())
        val v = Vouched(vouched.latitude, vouched.longitude, if (vouched.hasAccuracy()) vouched.accuracy.toDouble() else Double.MAX_VALUE)
        return linkRenews(store.rows, linkZones(store.keys), v, h.heard.firstOrNull { it.connected }?.network, ask.motion == Motion.STILL, refresh, nowMin())
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
