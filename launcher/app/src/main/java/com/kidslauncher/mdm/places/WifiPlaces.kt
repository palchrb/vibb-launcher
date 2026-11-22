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
import com.kidslauncher.mdm.server.LocateCommands
import com.kidslauncher.mdm.server.QuickControls
import com.kidslauncher.mdm.server.Sealed
import com.kidslauncher.mdm.server.Secrets
import com.kidslauncher.mdm.server.ServerJson
import com.kidslauncher.mdm.server.currentPolicyDecision
import com.kidslauncher.mdm.zones.CONFIRM_DELAY_MS
import com.kidslauncher.mdm.zones.Motion
import com.kidslauncher.mdm.zones.ZoneWatch
import com.kidslauncher.mdm.zones.distanceM
import java.io.File
import java.security.SecureRandom
import java.time.LocalDate
import java.util.Base64
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.resume
import kotlin.math.abs
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.builtins.ListSerializer

private const val LOG_TAG = "WifiPlaces"
private const val PREFS = "wifi_places"
private const val KEY_HMAC = "hmac_key_v1"
private const val AAD_HMAC = "wifi_places/hmac_key_v1"
private const val KEY_BUDGET = "teach_budget_v1"
private const val KEY_COUNTS = "loo_v1"
private const val KEY_LAST_GPS_TRY = "last_gps_try_ms"
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

/** The teaching fix holds the CPU at most this long: a scan, 15 s of GPS, a re-scan. */
private const val TEACH_WAKELOCK_MS = 45_000L

/** A cached serving cell older than this asks the modem once more (D10). */
private const val CELL_STALE_MS = 60_000L
private const val CELL_WAIT_MS = 3_000L

/** A pairing of a passive fix (D2) - the only teacher without our stream. */
private const val PASSIVE_PAIR_MS = PAIR_SINGLE_MS

/**
 * Wi-Fi places, learned on the phone (design 125 step 1; rules in `PlacesPlan.kt`). With Google's location accuracy off
 * (the `network` provider off) a fresh-fix attempt scans Wi-Fi while GPS starts ([attempt], D13/D14): a learned fix
 * that stands alone is reported as `vibb_wifi`, every own GPS fix ([Path]) and every passive `gps` fix
 * ([offerPassive]) teaches the APs heard with it, and 2 min after a motion fire a teaching fix runs ([armTeach], D11).
 * With the `network` provider on nothing here scans or looks up (H1); only passive fixes teach, against the system's
 * own cached results.
 *
 * Privacy (D18, D19): the table is one file in credential-encrypted storage, sealed whole under the secrets key
 * (`SecretBox`, AAD `wifi_places_v1`); rows are keyed by HMAC under a per-install key kept sealed in CE prefs
 * `wifi_places`; no SSID, BSSID or cell id is kept or logged. [clear] at location Off, unmanaged, a new enrollment and
 * a broken Keystore key. A transient Keystore error skips that lookup or teach and keeps the table.
 */
object WifiPlaces {
    /** Which fresh-fix attempt (D14): the heartbeat's and a crossing's first fix and the low-battery fix; a crossing's
     * confirm; the parent's `locate`. */
    enum class Path { HEARTBEAT, CONFIRM, LOCATE }

    /**
     * What a sync's attempt knows ([motion], [movedAtElapsedMs] - design 124's flags) and, for the heartbeat's 4-h cap
     * try with `motion` `STILL`, the [vouched] fix a zone link may renew (D12). The attempt answers in [renewed] (the
     * vouched fix re-sent with `still: true`) and [notFresh] (a 2-min fallback fix: reported, never fresh, M2).
     */
    class Ask(val motion: Motion, val movedAtElapsedMs: Long, val vouched: Location? = null) {
        @Volatile var renewed = false

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

    /** Our own teaching attempts running: a passive fix then is ours, taught by the attempt itself. */
    private val ownTeaching = AtomicInteger(0)

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

    private fun loadKey(context: Context): PlaceKeys? {
        val p = prefs(context)
        return when (val sealed = Secrets.prefs.read(p, AAD_HMAC, KEY_HMAC)) {
            is Sealed.Value -> PlaceKeys(Base64.getDecoder().decode(sealed.plain))
            Sealed.Absent -> {
                // A table without its key is noise: dropped before the first key is made.
                file(context).delete()
                val secret = ByteArray(32).also { SecureRandom().nextBytes(it) }
                val saved = Secrets.prefs.edit(p) { it.putSealed(AAD_HMAC, KEY_HMAC, Base64.getEncoder().encodeToString(secret)) }
                if (saved) PlaceKeys(secret) else null
            }
            Sealed.Unreadable -> {
                if (Secrets.prefs.keyBroken()) clearLocked(context, "keybroken")
                null
            }
        }
    }

    private fun readTable(context: Context): List<PlaceRow>? {
        val f = file(context)
        if (!f.baseFile.exists()) return emptyList()
        return try {
            decodeTable(Secrets.box.open(AAD_TABLE, f.readFully())) ?: emptyList<PlaceRow>().also { Log.i(LOG_TAG, "table version dropped") }
        } catch (e: Exception) {
            if (Secrets.box.keyBroken()) clearLocked(context, "keybroken") else Log.w(LOG_TAG, "table unreadable now: ${e.javaClass.simpleName}")
            null
        }
    }

    /** The table after [change] on a store opened at [generation]; written sealed (AtomicFile), dropped when a clear
     * came meanwhile. A failed seal keeps the table in memory (the next write tries again). */
    private fun save(context: Context, generation: Int, change: (List<PlaceRow>) -> List<PlaceRow>): Unit = synchronized(lock) {
        val current = rows ?: return
        if (generation != this.generation) return
        val next = change(current)
        if (next === current) return
        rows = next
        val f = file(context)
        val sealed = try {
            Secrets.box.seal(AAD_TABLE, encodeTable(next))
        } catch (e: Exception) {
            Log.w(LOG_TAG, "table not sealed now: ${e.javaClass.simpleName}")
            return
        }
        val out = try {
            f.startWrite()
        } catch (e: Exception) {
            Log.w(LOG_TAG, "table not written: ${e.javaClass.simpleName}")
            return
        }
        try {
            out.write(sealed)
            f.finishWrite(out)
        } catch (e: Exception) {
            f.failWrite(out)
            Log.w(LOG_TAG, "table not written: ${e.javaClass.simpleName}")
        }
    }

    /**
     * D19: the table, its key and the counts go - location Off ([reason] `off`), unmanaged or a new enrollment
     * (`unenrol`), a broken Keystore key (`keybroken`). No Keystore call; cheap when there is nothing.
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

    private fun cached(context: Context): List<ScanAp> = try {
        wifi(context)?.scanResults.orEmpty().map(::scanAp)
    } catch (e: SecurityException) {
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

    /** D13: the cached results when D9's fresh rule holds (>= 3 within 30 s, after the last move); else one
     * `startScan` and its results within [SCAN_WAIT_MS] - throttled or no answer: the cache (the 2-min fallback). */
    private suspend fun scanNow(context: Context, movedAtElapsedMs: Long): List<ScanAp> {
        val now = SystemClock.elapsedRealtime()
        val before = cached(context)
        if (before.count { now - it.elapsedMs in 0..SCAN_FRESH_MS && it.elapsedMs > movedAtElapsedMs } >= SCAN_RESULTS_MIN) return before
        val wm = wifi(context) ?: return before
        val done = CompletableDeferred<Unit>()
        val callback = object : WifiManager.ScanResultsCallback() {
            override fun onScanResultsAvailable() {
                done.complete(Unit)
            }
        }
        val got = try {
            wm.registerScanResultsCallback(executor, callback)
            @Suppress("DEPRECATION")
            wm.startScan() && withTimeoutOrNull(SCAN_WAIT_MS) { done.await() } != null
        } catch (e: Exception) {
            false
        } finally {
            runCatching { wm.unregisterScanResultsCallback(callback) }
        }
        if (!got) Log.i(LOG_TAG, "scan throttled or unanswered: the cached results")
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

    private suspend fun snapshot(context: Context, keys: PlaceKeys?, movedAtElapsedMs: Long): Snapshot = coroutineScope {
        val cell = async { keys?.let { servingCell(context, it) } }
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
        Log.i(
            LOG_TAG,
            "scan usable=${h.heard.size} trusted=$trusted networks=${fix?.networks ?: 0} acc=${fix?.accuracyM?.toInt() ?: "-"}" +
                (if (view?.fresh == false) " fallback" else ""),
        )
        if (fix != null) {
            if (fix.networks >= 3) lastLearned = fix
            save(context, store.generation) { touchSeen(it, fix.keys, now) }
        }
        return fix
    }

    /** D10: the serving cell's own fix, after GPS and Wi-Fi gave nothing. */
    private fun cellOnly(context: Context, store: Store?, snap: Snapshot): Location? {
        store ?: return null
        val fix = cellFix(snap.cell, store.rows, nowMin()) ?: return null
        save(context, store.generation) { touchSeen(it, fix.keys, nowMin()) }
        return location(fix)
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

    private fun teaching(fixes: List<Location>): List<TeachFix> = fixes.map(::teachFix).filter { teaches(it, release = !BuildConfig.DEBUG) }

    private fun linkZones(keys: PlaceKeys): List<LinkZone> = ZoneWatch.watchedZones().map { LinkZone(it, keys.zone(it)) }

    /**
     * One teach (D2-D7, D12): the leave-one-out count and D15's contradiction first, against the table as it was; then
     * the sightings paired within [windowMs] ([pairings]), the zone links at the best teacher, the table saved.
     */
    private fun teachWith(context: Context, store: Store, snap: Snapshot, teachers: List<TeachFix>, windowMs: Long) {
        if (teachers.isEmpty()) return
        val h = hearing(context, store, snap, remember = false)
        val best = teachers.minBy { it.accuracyM ?: Float.MAX_VALUE }
        val now = nowMin()
        val today = today()
        val zones = linkZones(store.keys)
        val connectedNet = h.heard.firstOrNull { it.connected }?.network
        // D16: what the table as it was said for the same scan; a zone-link renewal's class by the connected network.
        val counts = mutableListOf<(DayCounts) -> DayCounts>()
        leaveOneOut(learnedFix(ScanView(h.heard, true), store.rows, now), best.lat, best.lng)?.let { (cls, hit) -> counts += { c -> c.loo(cls, hit) } }
        zones.firstOrNull { z -> store.rows.any { it.key == z.key && it.network == connectedNet && it.linked(now) } }?.let { z ->
            val inside = distanceM(best.lat, best.lng, z.zone.latitude, z.zone.longitude) <= z.zone.radiusM
            counts += { c -> c.loo(LooClass.ZONE, inside) }
        }
        if (learnedContradiction(lastLearned, best.lat, best.lng, best.elapsedMs)) {
            Log.w(LOG_TAG, "learned contradiction")
            counts += { c -> c.copy(contradictions = c.contradictions + 1) }
        }
        if (counts.isNotEmpty()) count(context, today) { counts.fold(it) { c, f -> f(c) } }
        val heard = Hearing(h.heard + listOfNotNull(snap.cell), h.stale, h.readings)
        val sightings = pairings(heard, teachers, windowMs)
        var outcome: TeachOutcome? = null
        save(context, store.generation) { rows ->
            val taught = teach(rows, sightings, now, today).also { outcome = it }
            val acc = (best.accuracyM ?: TEACH_ACCURACY_MAX_M).toDouble()
            teachLinks(taught.rows, zones, best.lat, best.lng, acc, connectedNet, h.heard.map { it.network }.toSet(), now, today)
        }
        outcome?.let { Log.i(LOG_TAG, "taught aps=${it.taught} new=${it.new} moved=${it.moved}" + if (h.stale) " stale" else "") }
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
     * +-30 s - never a scan of ours. Skipped while one of our own attempts teaches (its stream teaches it). On the
     * passive feed's executor; never throws.
     */
    fun offerPassive(context: Context, location: Location) {
        try {
            if (ownTeaching.get() > 0) return
            val fix = teaching(listOf(location))
            if (fix.isEmpty()) return
            val store = open(context) ?: return
            val results = cached(context)
            if (results.isEmpty()) return
            teachWith(context, store, Snapshot(results, connected(context), null), fix, PASSIVE_PAIR_MS)
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
    }

    /**
     * One GPS try of this design (D2, D11, D14): the stream within [boundMs] ([shortMs] first; [stopEarly] ends it
     * there); at the first fix - or, [untilTeaches], the first that teaches - a teaching fix with no result of [first]
     * within [PAIR_STREAM_MS] of the stream takes one more scan while the stream runs on (D2's re-scan), then teaches.
     * The most accurate fix [accept] takes is returned.
     */
    private suspend fun gpsTry(
        context: Context,
        lm: LocationManager,
        store: Store?,
        first: Deferred<Snapshot>?,
        boundMs: Long,
        accept: (Location) -> Boolean,
        shortMs: Long = boundMs,
        stopEarly: suspend () -> Boolean = { false },
        untilTeaches: Boolean = false,
    ): Location? {
        prefs(context).edit().putLong(KEY_LAST_GPS_TRY, System.currentTimeMillis()).commit()
        val stream = GpsStream(lm)
        if (!stream.start(context, boundMs)) return null
        try {
            suspend fun waitFix(ms: Long): Boolean = withTimeoutOrNull(ms) {
                if (untilTeaches) {
                    while (teaching(stream.fixes.toList()).isEmpty()) delay(250)
                } else {
                    stream.first.await()
                }
            } != null
            val got = waitFix(shortMs) || (shortMs < boundMs && !stopEarly() && waitFix(boundMs - shortMs))
            if (got && store != null && first != null && teaching(stream.fixes.toList()).isNotEmpty()) {
                var snap = first.await()
                val streamMs = stream.fixes.toList().map { it.elapsedRealtimeNanos / 1_000_000 }
                if (snap.results.none { r -> streamMs.any { abs(it - r.elapsedMs) <= PAIR_STREAM_MS } } && networkOff(lm)) {
                    // Every cached result counts as before the move: the platform scans now.
                    snap = Snapshot(scanNow(context, SystemClock.elapsedRealtime()), snap.connected, snap.cell)
                }
                teachWith(context, store, snap, teaching(stream.fixes.toList()), PAIR_STREAM_MS)
            }
        } finally {
            stream.stop()
        }
        return stream.fixes.toList().filter(accept).minByOrNull { if (it.hasAccuracy()) it.accuracy else Float.MAX_VALUE }
    }

    // --- The attempt (D13, D14) ---

    /**
     * A fresh-fix attempt with the `network` provider off (D14), inside `currentLocation`'s Location window:
     * - [Path.HEARTBEAT]: after a move (`MOVED`, no sensor) or with the daily refresh due, scan and GPS together, the
     *   better kept (GPS at equal or better accuracy; the learned fix only when it stands alone); otherwise the learned
     *   fix when it stands alone, else D12's renewal of a still vouch, else GPS;
     * - [Path.CONFIRM]: GPS first, then the learned fix;
     * - [Path.LOCATE]: GPS for 60 s, 20 s when a learned fix of >= 3 networks within 60 m is in hand; the better kept;
     * then a cell fix. Every fix through [accept] (the mock rule). Never throws.
     */
    suspend fun attempt(context: Context, lm: LocationManager, path: Path, ask: Ask, accept: (Location) -> Boolean): Location? {
        val app = context.applicationContext
        ownTeaching.incrementAndGet()
        try {
            val store = try {
                open(app)
            } catch (e: Exception) {
                Log.w(LOG_TAG, "table not opened: ${e.javaClass.simpleName}")
                null
            }
            val movedSinceFresh = ask.motion != Motion.STILL
            val afterMove = ask.motion == Motion.MOVED || ask.motion == Motion.NO_SENSOR
            val refresh = refreshDue(prefs(app).getLong(KEY_LAST_GPS_TRY, 0L), System.currentTimeMillis())
            return coroutineScope {
                // Without the table (a Keystore error) nothing is scanned, used or taught: GPS as before.
                val snap = async { if (store == null) Snapshot(emptyList(), null, null) else snapshot(app, store.keys, ask.movedAtElapsedMs) }
                suspend fun learned(): LearnedFix? = store?.let { lookup(app, it, snap.await(), ask.movedAtElapsedMs) }
                    ?.takeIf { standsAlone(it, movedSinceFresh) }
                fun asLocation(fix: LearnedFix): Location? = location(fix).takeIf(accept)?.also { ask.notFresh = !fix.fresh }
                fun better(gps: Location?, fix: LearnedFix?): Location? =
                    if (fix == null || (gps != null && gpsWins(fix.accuracyM, if (gps.hasAccuracy()) gps.accuracy else null))) gps else asLocation(fix) ?: gps
                val (kept, how) = when (path) {
                    Path.HEARTBEAT -> if (afterMove || refresh) {
                        better(gpsTry(app, lm, store, snap, GPS_MS, accept), learned()) to "together"
                    } else {
                        val fix = learned()
                        when {
                            fix != null -> asLocation(fix) to "wifi"
                            renews(app, store, snap.await(), ask) -> ask.vouched.also { ask.renewed = true } to "renew"
                            else -> gpsTry(app, lm, store, snap, GPS_MS, accept) to "gps"
                        }
                    }
                    Path.CONFIRM -> (gpsTry(app, lm, store, snap, GPS_MS, accept) ?: learned()?.let(::asLocation)) to "gps-first"
                    Path.LOCATE -> {
                        val early = async { learned() }
                        val gps = gpsTry(
                            app, lm, store, snap, LOCATE_MS, accept, shortMs = LOCATE_SHORT_MS,
                            stopEarly = { early.await()?.let { it.networks >= 3 && it.accuracyM <= LOCATE_SHORT_ACCURACY_M } == true },
                        )
                        better(gps, early.await()) to "locate"
                    }
                }
                val result = kept ?: cellOnly(app, store, snap.await())
                Log.i(LOG_TAG, "attempt path=$how result=${result?.provider ?: "none"}")
                result
            }
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

    /** A motion fire: the teaching fix [CONFIRM_DELAY_MS] later (a later fire moves it on - it runs at the stop). */
    fun armTeach(context: Context) {
        try {
            val alarms = context.getSystemService(AlarmManager::class.java) ?: return
            val at = SystemClock.elapsedRealtime() + CONFIRM_DELAY_MS
            if (alarms.canScheduleExactAlarms()) {
                alarms.setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, at, teachIntent(context))
            } else {
                alarms.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, at, teachIntent(context))
            }
        } catch (e: Exception) {
            Log.w(LOG_TAG, "teaching fix not armed: ${e.javaClass.simpleName}")
        }
    }

    /**
     * D11's teaching fix: only on a Regularly phone (`ZoneWatch.stillOn`), with Location on and the `network` provider
     * off, at most [TEACH_PER_DAY] a local day - a scan and 15 s of GPS that teach; on a zones phone the fix goes to the
     * kept slot, otherwise it is not reported. Not a heartbeat try: design 124's flags don't hear it.
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
                val policy = currentPolicyDecision().policy
                val lm = app.getSystemService(LocationManager::class.java) ?: return@launch
                if (!ZoneWatch.stillOn(app, policy) || !QuickControls.isLocationEnabled(app) || !networkOff(lm)) return@launch
                if (!spendTeach(app)) return@launch
                val store = open(app) ?: return@launch
                ownTeaching.incrementAndGet()
                try {
                    val snap = scope.async { snapshot(app, store.keys, 0L) }
                    val fix = gpsTry(app, lm, store, snap, GPS_MS, { ZoneWatch.accept(app, it) }, untilTeaches = true)
                    Log.i(LOG_TAG, "teaching fix ${if (fix != null) "taken" else "none"}")
                    if (fix != null && ZoneWatch.zonesOn(app, policy)) LocateCommands.keepFix(app, fix)
                } finally {
                    ownTeaching.decrementAndGet()
                }
            } catch (e: Exception) {
                Log.w(LOG_TAG, "teaching fix failed: ${e.javaClass.simpleName}")
            } finally {
                runCatching { wake?.release() }
            }
        }
    }

    private fun spendTeach(context: Context): Boolean = synchronized(lock) {
        val p = prefs(context)
        val day = LocalDate.now().toString()
        val budget = try {
            p.getString(KEY_BUDGET, null)?.let { ServerJson.decodeFromString(TeachBudget.serializer(), it) }
        } catch (e: Exception) {
            null
        }
        if (teachesLeft(budget, day) <= 0) return false
        p.edit().putString(KEY_BUDGET, ServerJson.encodeToString(TeachBudget.serializer(), budgetAfterTeach(budget, day))).commit()
        true
    }
}

/** The teaching fix's alarm - our own explicit PendingIntent only (not exported). */
class WifiPlacesTeachReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_TEACH) return
        WifiPlaces.onTeachAlarm(context)
    }
}
