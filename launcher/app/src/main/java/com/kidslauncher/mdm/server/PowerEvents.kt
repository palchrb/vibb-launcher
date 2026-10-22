package com.kidslauncher.mdm.server

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.SystemClock
import android.util.Log
import com.kidslauncher.mdm.preferences.LauncherPreferences
import com.kidslauncher.mdm.server.dto.BatteryReport
import com.kidslauncher.mdm.server.dto.PowerOff
import com.kidslauncher.mdm.server.dto.PowerOn
import com.kidslauncher.mdm.server.dto.PowerReport
import java.util.concurrent.Executors

/*
 * The phone turned off and on (design 38, `docs/design/38-phone-off-on.md` §4): the shutdown seen
 * by `ACTION_SHUTDOWN` and this boot's start, written ahead in device-protected prefs
 * `power_events` and told to the server (`POST /api/devices/power`), which turns them into the
 * parent's "Anna was turned off" / "Anna restarted" / "Anna was turned on". The rules are pure;
 * [PowerEvents] is the glue.
 */

private const val LOG_TAG = "PowerEvents"

/** Status-report capability: this launcher reports its shutdowns and starts. */
const val POWER_CAPABILITY = "power_v1"

/** A kept `off` (sent or not) and an `on` older than this are dropped unsent ([stale]): an old
 * server's 404 ends there too. */
const val POWER_STALE_MS = 7L * 24 * 60 * 60 * 1000

/** How long the shutdown's POST may hold the broadcast (`goAsync`, ShutdownThread's shared 10 s). */
const val SHUTDOWN_POST_TIMEOUT_MS = 3_000L

/**
 * What the phone keeps (§4.1): at most one shutdown and one start; a new one replaces the old (an
 * unsent old one is then lost). [offSent]: the server took [off] - it is still kept, and sent again
 * with the next start's [on], until a later boot's start is accepted, so the server pairs them in
 * one body (qa-38-design H1).
 */
data class PowerRecord(val off: PowerOff? = null, val offSent: Boolean = false, val on: PowerOn? = null)

/**
 * The restart flag (§4.2): `true` for a userspace-only reboot, else from `sys.shutdown.requested`
 * (`"1…"` a restart, `"0…"` a power-off); anything else - unreadable included - is unknown
 * (`null`), never `false`: the server then holds the push for a restart's start.
 */
fun rebootOf(userspaceOnly: Boolean, requested: String?): Boolean? = when {
    userspaceOnly -> true
    requested == null -> null
    requested.startsWith("1") -> true
    requested.startsWith("0") -> false
    else -> null
}

/** The text after the digit of `sys.shutdown.requested` ("userrequested", "battery", ...), or
 * `null`. The server keeps only the words it knows. */
fun reasonOf(requested: String?): String? {
    if (requested == null || requested.isEmpty() || requested[0] !in "01") return null
    return requested.substring(1).take(64).takeIf { it.isNotBlank() }
}

/**
 * The start to record at a process start (§4.3): only the first start of a boot ([newBoot]) that
 * the witness can tell from the last one ([hadLastBoot]), with a known count - `-1` is "new" at
 * every start (qa-38-design M4). The instant is the boot's: wall clock minus elapsed realtime.
 */
fun bootRecord(
    newBoot: Boolean,
    hadLastBoot: Boolean,
    bootCount: Int,
    nowMs: Long,
    elapsedMs: Long,
    battery: BatteryReport?,
): PowerOn? {
    if (!newBoot || !hadLastBoot || bootCount < 0) return null
    return PowerOn(atMs = nowMs - elapsedMs, bootCount = bootCount, battery = battery)
}

/**
 * Design 59 §3.9: the boot's first CE start ([firstCeStart], `AndroidLock`'s boot-count test) stamps when the storage
 * unlocked ([nowMs]; in android mode the Child PIN typed) on this boot's start ([bootCount]) - only when it has none.
 */
fun withUnlock(record: PowerRecord, firstCeStart: Boolean, bootCount: Int, nowMs: Long): PowerRecord {
    val on = record.on ?: return record
    if (!firstCeStart || on.bootCount != bootCount || on.unlockedAtMs != null) return record
    return record.copy(on = on.copy(unlockedAtMs = nowMs))
}

/** A shutdown replaces the kept one, not yet sent. */
fun withShutdown(record: PowerRecord, off: PowerOff): PowerRecord = record.copy(off = off, offSent = false)

/** A start replaces the kept one. */
fun withBoot(record: PowerRecord, on: PowerOn): PowerRecord = record.copy(on = on)

/** [record] without what is older than [POWER_STALE_MS] at [nowMs]. */
fun stale(record: PowerRecord, nowMs: Long): PowerRecord {
    val old = { atMs: Long -> nowMs - atMs > POWER_STALE_MS }
    val off = record.off?.takeUnless { old(it.atMs) }
    return PowerRecord(off = off, offSent = off != null && record.offSent, on = record.on?.takeUnless { old(it.atMs) })
}

/**
 * The sync's body (§4.4): with a start, the start and any kept shutdown of an earlier boot (sent
 * or not); else an unsent shutdown alone; else nothing. A kept shutdown of the start's own boot or
 * later isn't paired (the server would drop it): then only an unsent shutdown goes, and the start
 * waits for the next boot's to replace it. The start's instant is recomputed while this is still
 * its boot ([bootCountNow]) - NTP may have moved the wall clock since.
 */
fun uploadBody(record: PowerRecord, bootCountNow: Int, nowMs: Long, elapsedMs: Long): PowerReport? {
    val off = record.off
    val on = record.on?.let { if (it.bootCount == bootCountNow) it.copy(atMs = nowMs - elapsedMs) else it }
    return when {
        on != null && (off == null || off.bootCount < on.bootCount) -> PowerReport(off = off, on = on)
        off != null && !record.offSent -> PowerReport(off = off)
        else -> null
    }
}

/** The same shutdown: its instant and boot. */
private fun sameOff(a: PowerOff?, b: PowerOff?) = a != null && b != null && a.atMs == b.atMs && a.bootCount == b.bootCount

/** The same start: its boot (the instant sent may be recomputed). */
private fun sameOn(a: PowerOn?, b: PowerOn?) = a != null && b != null && a.bootCount == b.bootCount

/**
 * A 2xx for [sent] (§4.4): the sent shutdown is marked sent; with a start, the start and any kept
 * shutdown of an earlier boot go (the pair is told). Compared by instant and boot, so a newer
 * record written meanwhile (the shutdown receiver races the sync) stays.
 */
fun clearSent(record: PowerRecord, sent: PowerReport): PowerRecord {
    val marked = if (sameOff(record.off, sent.off)) record.copy(offSent = true) else record
    val on = sent.on ?: return marked
    val off = marked.off?.takeUnless { it.bootCount < on.bootCount }
    return PowerRecord(off = off, offSent = off != null && marked.offSent, on = marked.on?.takeUnless { sameOn(it, on) })
}

/** A refusal for good (another 4xx): what was sent goes - retrying won't fix the body. */
fun dropSent(record: PowerRecord, sent: PowerReport): PowerRecord {
    val off = record.off?.takeUnless { sameOff(it, sent.off) }
    return PowerRecord(off = off, offSent = off != null && record.offSent, on = record.on?.takeUnless { sameOn(it, sent.on) })
}

/** The server's answer ([code], `null` = none) by [pinEventsLeaveQueue], reused as is (QA L5):
 * 2xx -> [clearSent]; 401/404/408/429, 5xx and no answer keep everything; any other 4xx ->
 * [dropSent]. */
fun afterAnswer(record: PowerRecord, sent: PowerReport, code: Int?): PowerRecord = when {
    !pinEventsLeaveQueue(code) -> record
    code != null && code in 200..299 -> clearSent(record, sent)
    else -> dropSent(record, sent)
}

/** What the prepared shutdown client was built for (QA N1): `createMdmApi` reads the tsnet proxy
 * only when it is built. `null` = no server or token (not enrolled). */
data class ClientKey(val baseUrl: String, val token: String, val proxy: String?)

fun clientKeyOf(baseUrl: String?, token: String?, proxy: String?): ClientKey? =
    if (baseUrl.isNullOrBlank() || token.isNullOrBlank()) null else ClientKey(baseUrl, token, proxy)

/** The prepared client is rebuilt (or dropped) whenever the base URL, the token or the proxy differ. */
fun rebuildNeeded(current: ClientKey?, wanted: ClientKey?): Boolean = current != wanted

/** The record's prefs keys (§4.1). Absent = unknown. */
internal object PowerKeys {
    const val OFF_AT = "off_at_ms"
    const val OFF_BOOT = "off_boot_count"
    const val OFF_LEVEL = "off_level"
    const val OFF_CHARGING = "off_charging"
    const val OFF_LOW = "off_low"
    const val OFF_REBOOT = "off_reboot"
    const val OFF_REASON = "off_reason"
    const val OFF_SENT = "off_sent"
    const val ON_AT = "on_at_ms"
    const val ON_BOOT = "on_boot_count"
    const val ON_LEVEL = "on_level"
    const val ON_CHARGING = "on_charging"
    const val ON_LOW = "on_low"
    const val ON_UNLOCKED = "on_unlocked_at_ms"
}

/** The record from the prefs' values ([values] as `SharedPreferences.getAll`); a part missing its
 * instant or boot is none. */
fun decodePowerRecord(values: Map<String, Any?>): PowerRecord {
    fun long(key: String) = (values[key] as? Number)?.toLong()
    fun int(key: String) = (values[key] as? Number)?.toInt()
    fun bool(key: String) = values[key] as? Boolean
    fun battery(level: String, charging: String, low: String): BatteryReport? {
        val l = int(level) ?: return null
        return BatteryReport(level = l, charging = bool(charging) == true, low = bool(low) == true)
    }
    val off = run {
        val at = long(PowerKeys.OFF_AT) ?: return@run null
        val boot = int(PowerKeys.OFF_BOOT) ?: return@run null
        PowerOff(
            atMs = at,
            bootCount = boot,
            battery = battery(PowerKeys.OFF_LEVEL, PowerKeys.OFF_CHARGING, PowerKeys.OFF_LOW),
            reboot = bool(PowerKeys.OFF_REBOOT),
            reason = values[PowerKeys.OFF_REASON] as? String,
        )
    }
    val on = run {
        val at = long(PowerKeys.ON_AT) ?: return@run null
        val boot = int(PowerKeys.ON_BOOT) ?: return@run null
        PowerOn(
            atMs = at,
            bootCount = boot,
            battery = battery(PowerKeys.ON_LEVEL, PowerKeys.ON_CHARGING, PowerKeys.ON_LOW),
            unlockedAtMs = long(PowerKeys.ON_UNLOCKED),
        )
    }
    return PowerRecord(off = off, offSent = off != null && bool(PowerKeys.OFF_SENT) == true, on = on)
}

/** The prefs' values for [record] (what [decodePowerRecord] reads back), unknowns left out. */
fun encodePowerRecord(record: PowerRecord): Map<String, Any> = buildMap {
    record.off?.let { off ->
        put(PowerKeys.OFF_AT, off.atMs)
        put(PowerKeys.OFF_BOOT, off.bootCount)
        off.battery?.let {
            put(PowerKeys.OFF_LEVEL, it.level)
            put(PowerKeys.OFF_CHARGING, it.charging)
            put(PowerKeys.OFF_LOW, it.low)
        }
        off.reboot?.let { put(PowerKeys.OFF_REBOOT, it) }
        off.reason?.let { put(PowerKeys.OFF_REASON, it) }
        put(PowerKeys.OFF_SENT, record.offSent)
    }
    record.on?.let { on ->
        put(PowerKeys.ON_AT, on.atMs)
        put(PowerKeys.ON_BOOT, on.bootCount)
        on.battery?.let {
            put(PowerKeys.ON_LEVEL, it.level)
            put(PowerKeys.ON_CHARGING, it.charging)
            put(PowerKeys.ON_LOW, it.low)
        }
        on.unlockedAtMs?.let { put(PowerKeys.ON_UNLOCKED, it) }
    }
}

/**
 * The glue (§4): device-protected prefs `power_events` (the start is written in direct boot;
 * nothing about the user or the PIN in them), every read-compare-write under [lock] with one
 * `commit()` (the shutdown receiver on the main thread races the sync's thread, QA L3).
 */
object PowerEvents {
    private const val PREFS = "power_events"

    /** Every read-compare-write of the record. */
    private val lock = Any()

    /** The shutdown's POST runs here, never on the main thread. */
    private val shutdownExecutor = Executors.newSingleThreadExecutor { Thread(it, "power-shutdown").apply { isDaemon = true } }

    /** The prepared client is (re)built here, off the main thread. */
    private val prepareExecutor = Executors.newSingleThreadExecutor { Thread(it, "power-prepare").apply { isDaemon = true } }

    private class Prepared(val key: ClientKey, val api: MdmApi)

    /** The client the shutdown receiver uses: built in advance, so nothing is built inside the window. */
    @Volatile
    private var prepared: Prepared? = null

    private fun prefs(context: Context): SharedPreferences =
        context.createDeviceProtectedStorageContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun load(prefs: SharedPreferences): PowerRecord = decodePowerRecord(prefs.all)

    /** [change] of the record, committed when it changed; the record after it. */
    private fun update(context: Context, change: (PowerRecord) -> PowerRecord): PowerRecord = synchronized(lock) {
        val prefs = prefs(context)
        val before = load(prefs)
        val after = change(before)
        if (after != before) {
            val editor = prefs.edit().clear()
            for ((key, value) in encodePowerRecord(after)) {
                when (value) {
                    is Long -> editor.putLong(key, value)
                    is Int -> editor.putInt(key, value)
                    is Boolean -> editor.putBoolean(key, value)
                    is String -> editor.putString(key, value)
                }
            }
            if (!editor.commit()) Log.w(LOG_TAG, "Couldn't save the power record")
        }
        after
    }

    /**
     * From `Application.initRest` (main thread): the prepared client now (off the main thread) and
     * whenever the tsnet proxy comes up or goes ([TsnetClient.proxyListener]).
     */
    fun init(context: Context) {
        val app = context.applicationContext
        try {
            TsnetClient.proxyListener = { prepareAsync(app) }
            prepareAsync(app)
        } catch (e: Exception) {
            Log.w(LOG_TAG, "Power events init failed: ${e.javaClass.simpleName}")
        }
    }

    /** [prepare] on its own thread. Never throws. */
    fun prepareAsync(context: Context) {
        val app = context.applicationContext
        try {
            prepareExecutor.execute { prepare(app) }
        } catch (e: Exception) {
            Log.w(LOG_TAG, "Couldn't prepare the shutdown client: ${e.javaClass.simpleName}")
        }
    }

    /**
     * Builds the shutdown's client when the base URL, the token or the tsnet proxy differ from the
     * one it has (or drops it when not enrolled): `createMdmApi` with a 3 s call timeout. Off the
     * main thread only - from [prepareAsync] and after each sync's `connectFromPreferences`. Never
     * throws.
     */
    fun prepare(context: Context) {
        try {
            val mdm = LauncherPreferences.mdm()
            val wanted = clientKeyOf(mdm.serverUrl(), mdm.deviceToken(), TsnetClient.proxyAddress)
            synchronized(this) {
                if (!rebuildNeeded(prepared?.key, wanted)) return
                prepared = wanted?.let { Prepared(it, createMdmApi(it.baseUrl, it.token, callTimeoutMs = SHUTDOWN_POST_TIMEOUT_MS)) }
            }
        } catch (e: Exception) {
            Log.w(LOG_TAG, "Couldn't prepare the shutdown client: ${e.javaClass.simpleName}")
        }
    }

    /**
     * `ACTION_SHUTDOWN`, from `BootCover`'s receiver after its own writes (main thread, §4.2): the
     * shutdown is committed first (write-ahead), only then the broadcast is held (`goAsync`) for
     * the POST on its own thread - at most [SHUTDOWN_POST_TIMEOUT_MS] - and `finish()`ed in a
     * `finally`. Without a prepared client (not enrolled) nothing is sent; the record waits for the
     * next start's sync.
     */
    fun onShutdown(context: Context, intent: Intent, receiver: BroadcastReceiver) {
        val app = context.applicationContext
        val requested = shutdownRequested()
        val off = PowerOff(
            atMs = System.currentTimeMillis(),
            bootCount = BootClock.bootCount(),
            battery = BatteryStatus.report(app),
            reboot = rebootOf(intent.getBooleanExtra(Intent.EXTRA_SHUTDOWN_USERSPACE_ONLY, false), requested),
            reason = reasonOf(requested),
        )
        update(app) { withShutdown(it, off) }
        val api = prepared?.api ?: return
        val pending = receiver.goAsync()
        try {
            shutdownExecutor.execute {
                try {
                    send(app, api, PowerReport(off = off))
                } finally {
                    pending.finish()
                }
            }
        } catch (e: Exception) {
            pending.finish()
            Log.w(LOG_TAG, "Couldn't send the shutdown: ${e.javaClass.simpleName}")
        }
    }

    /** The POST and its answer, on a worker thread. Never throws. */
    private fun send(context: Context, api: MdmApi, body: PowerReport) {
        val code = try {
            kotlinx.coroutines.runBlocking { api.sendPower(body).code() }
        } catch (e: Exception) {
            Log.w(LOG_TAG, "The power report didn't get through: ${e.javaClass.simpleName}")
            null
        }
        try {
            update(context) { afterAnswer(it, body, code) }
        } catch (e: Exception) {
            Log.w(LOG_TAG, "Couldn't keep the power report's answer: ${e.javaClass.simpleName}")
        }
    }

    /** `sys.shutdown.requested`, read by reflection - a bonus, never required (a hidden API whose
     * SELinux label may refuse us; expected unreadable on the Jelly Star). */
    private fun shutdownRequested(): String? = try {
        Class.forName("android.os.SystemProperties")
            .getMethod("get", String::class.java)
            .invoke(null, "sys.shutdown.requested") as? String
    } catch (e: Exception) {
        null
    }

    /**
     * Right after `BootWitness.record` in `Application.onCreate` (main process only, direct boot
     * too): this boot's start, when [bootRecord] says it is one. Never throws (the call path runs
     * in this process).
     */
    fun recordBoot(context: Context) {
        try {
            val on = bootRecord(
                newBoot = BootWitness.newBoot,
                hadLastBoot = BootWitness.hadLastBoot,
                bootCount = BootClock.bootCount(),
                nowMs = System.currentTimeMillis(),
                elapsedMs = SystemClock.elapsedRealtime(),
                battery = BatteryStatus.report(context),
            ) ?: return
            update(context) { withBoot(it, on) }
        } catch (e: Exception) {
            Log.w(LOG_TAG, "Couldn't record the boot: ${e.javaClass.simpleName}")
        }
    }

    /**
     * Right after `PinLockRuntime.decide` in `Application.initUnlocked` (main thread, before the anchor's first sync):
     * the boot's first CE start ([firstCeStart]) stamps this boot's start with the unlock ([withUnlock], design 59
     * §3.9) - "turned on 07:02 · unlocked 07:15". One `commit()` once a boot. Never throws.
     */
    fun recordUnlock(context: Context, firstCeStart: Boolean) {
        if (!firstCeStart) return
        try {
            val now = System.currentTimeMillis()
            val boot = BootClock.bootCount()
            update(context) { withUnlock(it, firstCeStart, boot, now) }
        } catch (e: Exception) {
            Log.w(LOG_TAG, "Couldn't record the unlock: ${e.javaClass.simpleName}")
        }
    }

    /**
     * In `performMdmSync` after `ParentPinEvents.upload` (§4.4): stale parts dropped, then one POST
     * when an unsent shutdown or a start waits ([uploadBody]), its answer by [afterAnswer]. A start
     * whose sync found no network is retried by the stream's first open (`sse_open`), else the
     * backstop.
     */
    suspend fun upload(context: Context, api: MdmApi) {
        val now = System.currentTimeMillis()
        val record = update(context) { stale(it, now) }
        val body = uploadBody(record, BootClock.bootCount(), now, SystemClock.elapsedRealtime()) ?: return
        val code = try {
            api.sendPower(body).code()
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(LOG_TAG, "The power report didn't get through: ${e.javaClass.simpleName}")
            null
        }
        if (code != null && code !in 200..299 && pinEventsLeaveQueue(code)) {
            Log.w(LOG_TAG, "The server refused the power report (HTTP $code) - dropped")
        }
        update(context) { afterAnswer(it, body, code) }
    }
}
