package me.vibb.music.sonos

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import java.io.File
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean

/*
 * The uid -> ip cache, the picker's rows and the background SSDP round, ported from palchrb/vibb
 * `pi/sonosd.py` (`_load_cache`, `_save_cache`, `rescan`, `rescan_in_background`, `scanning`,
 * `refresh_topology`, `players_payload`, `_speaker`; and `pi/ui.py` `_sonos_choices`; MIT License,
 * Copyright (c) 2026 palchrb), reimplemented in Kotlin under the GPL-3.0 (or later) - see NOTICE.md.
 * Design 26a §2.1. Android-free; SpeakerCacheTest, TopologyTest.
 *
 * Discovery runs on a cache miss, the picker's "Se etter høyttalere", or a failed play - never on a
 * timer. Players MERGE (a scan that misses a speaker never deletes the row the kid aimed at); groups
 * REPLACE (a group list is a snapshot of one instant). Never an ip in the UI.
 */

private val CACHE_JSON = Json { ignoreUnknownKeys = true; explicitNulls = false; encodeDefaults = true }

@Serializable
data class CachedPlayer(
    val ip: String,
    /** [SONOS_PORT] on a real speaker; the debug feed sets another for the emulator's fake. */
    val port: Int = SONOS_PORT,
    val name: String? = null,
    /** Wall-clock seconds, as vibb's `time.time()` (display only, never a decision). */
    @SerialName("seen_at") val seenAt: Double? = null,
)

@Serializable
data class CachedGroup(val coordinator: String, val members: List<String>)

/** `sonos.json`: `{players: {uid: {ip, port, name, seen_at}}, groups: [...], fetched_at, topology_at}`. */
@Serializable
data class SpeakerDoc(
    val players: Map<String, CachedPlayer> = emptyMap(),
    val groups: List<CachedGroup> = emptyList(),
    @SerialName("fetched_at") val fetchedAt: Double? = null,
    @SerialName("topology_at") val topologyAt: Double? = null,
)

/** One picker row (`_sonos_choices`): [uid] is what a tap targets (a group's coordinator). */
data class Room(val label: String, val uid: String, val names: List<String>)

/** What the "Spill av på" sheet shows: [stale] = nobody answered, [scanning] = an SSDP round runs. */
data class RoomsView(val rooms: List<Room>, val stale: Boolean, val scanning: Boolean)

/** The file (`noBackupFilesDir/sonos.json`, SonosFiles.kt), read and written whole, tmp + rename. */
class SpeakerCache(private val file: File, private val wallS: () -> Double = { System.currentTimeMillis() / 1000.0 }) {
    private val lock = Any()

    /** The cache; a missing or garbage file is empty, a bad row is dropped (`_load_cache`). */
    fun load(): SpeakerDoc = synchronized(lock) { read() }

    /** The cached record of [uid], or null. */
    fun resolve(uid: String): CachedPlayer? = load().players[uid]

    /**
     * A topology (`refresh_topology`): its players merged in with fresh ip, port and name (DHCP moves
     * and renames heal themselves), the groups replaced. [scanned]: it came from an SSDP round
     * (`rescan` stamps `fetched_at`).
     */
    fun applyTopology(household: Household, scanned: Boolean = false): SpeakerDoc = update { doc ->
        val now = wallS()
        doc.copy(
            players = doc.players + household.players.mapValues { (_, p) -> CachedPlayer(p.ip, p.port, p.name, now) },
            groups = household.groups.map { CachedGroup(it.coordinator, it.members) },
            topologyAt = now,
            fetchedAt = if (scanned) now else doc.fetchedAt,
        )
    }

    /** Players merged in without touching the groups (the debug feed's `sonos.json`). */
    fun seed(players: Collection<Player>): SpeakerDoc = update { doc ->
        val now = wallS()
        doc.copy(players = doc.players + players.associate { it.uid to CachedPlayer(it.ip, it.port, it.name, now) }, fetchedAt = now)
    }

    private fun update(change: (SpeakerDoc) -> SpeakerDoc): SpeakerDoc = synchronized(lock) {
        change(read()).also { writeAtomic(file, CACHE_JSON.encodeToString(SpeakerDoc.serializer(), it).toByteArray()) }
    }

    private fun read(): SpeakerDoc {
        val root = runCatching { CACHE_JSON.parseToJsonElement(file.readText()) }.getOrNull() as? JsonObject ?: return SpeakerDoc()
        val players = root["players"] as? JsonObject ?: return SpeakerDoc()
        return SpeakerDoc(
            players = players.mapNotNull { (uid, v) -> runCatching { uid to CACHE_JSON.decodeFromJsonElement<CachedPlayer>(v) }.getOrNull() }.toMap(),
            groups = (root["groups"] as? JsonArray).orEmpty().mapNotNull { runCatching { CACHE_JSON.decodeFromJsonElement<CachedGroup>(it) }.getOrNull() },
            fetchedAt = runCatching { root["fetched_at"]?.let { CACHE_JSON.decodeFromJsonElement<Double?>(it) } }.getOrNull(),
            topologyAt = runCatching { root["topology_at"]?.let { CACHE_JSON.decodeFromJsonElement<Double?>(it) } }.getOrNull(),
        )
    }

    companion object {
        /**
         * The picker's rows: `players_payload` (players by name, multi-member groups only) and then
         * `_sonos_choices`: a group of two or more known rooms is ONE row, "Kjøkken + Stua",
         * coordinator first, and selecting it targets the coordinator; its members don't repeat as
         * solo rows; a group left with fewer than two known rooms, or whose coordinator is unknown,
         * gives no row.
         */
        fun rooms(doc: SpeakerDoc): List<Room> {
            val players = doc.players.entries.sortedBy { it.value.name.orEmpty() }
            val names = players.associate { it.key to (it.value.name.takeUnless { n -> n.isNullOrEmpty() } ?: "?") }
            val rows = mutableListOf<Room>()
            val absorbed = mutableSetOf<String>()
            for (g in doc.groups.filter { it.members.size > 1 }) {
                val members = g.members.filter { it in names }
                if (members.size < 2 || g.coordinator !in names) continue
                rows.add(Room(members.joinToString(" + ") { names.getValue(it) }, g.coordinator, members.map { names.getValue(it) }))
                absorbed.addAll(members)
            }
            for ((uid, _) in players) if (uid !in absorbed) rows.add(Room(names.getValue(uid), uid, listOf(names.getValue(uid))))
            return rows
        }
    }
}

/**
 * The household behind the picker: one topology refresh when the sheet opens, the background SSDP
 * round when nobody answers (`pipewire`'s `rescan_in_background`: "the list fills itself"), at most
 * one round in flight. Only on Wi-Fi ([onWifi]); without it the cache is served stale and no round
 * starts (the cabin case, §6.4). Blocking: call off the main thread.
 */
class RoomDirectory(
    private val cache: SpeakerCache,
    /** GetZoneGroupState's `ZoneGroupState` at a speaker, within a time budget. */
    private val zoneGroupState: (ip: String, port: Int, timeoutMs: Long) -> String,
    /** One SSDP round on Wi-Fi (SsdpScanner). */
    private val ssdp: () -> List<SsdpHit>,
    private val onWifi: () -> Boolean,
    /** Where the background round runs (an IO scope of the caller's). */
    private val scope: CoroutineScope,
    private val elapsedMs: () -> Long,
    private val log: (String) -> Unit = {},
) {
    private val roundRunning = AtomicBoolean(false)

    val scanning: Boolean get() = roundRunning.get()

    /** The cache as it is (a plain read). */
    fun view(stale: Boolean = false): RoomsView = RoomsView(SpeakerCache.rooms(cache.load()), stale, scanning)

    /**
     * `refresh_topology`: GetZoneGroupState against the cached speakers in uid order until one
     * answers, [PER_IP_MS] each and [TOTAL_MS] in all (QA M7). Throws when nobody answers.
     */
    fun refreshTopology(): SpeakerDoc {
        if (!onWifi()) throw SpeakerUnreachable("no Wi-Fi")
        val start = elapsedMs()
        var last: Exception? = null
        for ((_, rec) in cache.load().players.entries.sortedBy { it.key }) {
            val left = TOTAL_MS - (elapsedMs() - start)
            if (left <= 0) break
            try {
                val household = Topology.parse(zoneGroupState(rec.ip, rec.port, minOf(PER_IP_MS, left)))
                    ?: throw SpeakerUnreachable("no topology from ${rec.ip}")
                return cache.applyTopology(household)
            } catch (e: IOException) {
                last = e
            }
        }
        throw last ?: SpeakerUnreachable("no cached speaker to ask")
    }

    /**
     * The sheet opening (`/players?fresh=1`): one topology refresh; nobody answering serves the
     * cache marked stale at once and starts one background round (on Wi-Fi).
     */
    fun refresh(): RoomsView = try {
        refreshTopology()
        view()
    } catch (e: IOException) {
        log("rooms: nobody answered (${e.javaClass.simpleName}) - the cache, stale")
        if (onWifi()) rescanInBackground()
        view(stale = true)
    }

    /** One SSDP round off the caller's thread; false when one already runs (or no Wi-Fi). */
    fun rescanInBackground(): Boolean {
        if (!onWifi() || !roundRunning.compareAndSet(false, true)) return false
        scope.launch {
            try {
                val doc = scanNow()
                log("background scan: ${doc.players.size} speaker(s) cached")
            } catch (e: Exception) {
                log("background scan failed (${e.javaClass.simpleName})")
            } finally {
                roundRunning.set(false)
            }
        }
        return true
    }

    /**
     * `rescan`: SSDP for the speakers on this network, then the household from the first one whose
     * topology answers (SSDP alone learns neither uids nor groups). Merges into the cache.
     */
    fun scanNow(): SpeakerDoc {
        val hits = ssdp()
        for (hit in hits) {
            try {
                val household = Topology.parse(zoneGroupState(hit.ip, hit.port, PER_IP_MS)) ?: continue
                return cache.applyTopology(household, scanned = true)
            } catch (e: IOException) {
                log("scan: ${e.javaClass.simpleName} from a speaker - trying the next")
            }
        }
        return cache.load()
    }

    companion object {
        const val PER_IP_MS = 2_000L
        const val TOTAL_MS = 6_000L
    }
}

/** A speaker by uid, for the transport. */
fun interface SpeakerResolver {
    /** [verify]: compare the uid at the cached ip (the play path only); a mismatch is [UnknownUid]. */
    fun speaker(uid: String, verify: Boolean): SpeakerControl
}

/** `_speaker(uid)`: the cached ip, no discovery; an unreachable speaker is left to the poller. */
class CachedSpeakers(private val cache: SpeakerCache, private val soap: Soap) : SpeakerResolver {
    override fun speaker(uid: String, verify: Boolean): SpeakerControl {
        val rec = cache.resolve(uid) ?: throw UnknownUid(uid)
        if (verify) {
            val at = try {
                soap.uidOf(rec.ip, rec.port)
            } catch (_: SpeakerUnreachable) {
                null // unreachable: let the caller classify; no rescan here (the play path's failure does it)
            }
            if (at != null && at != uid) throw UnknownUid(uid)
        }
        return SoapSpeaker(soap, rec.ip, rec.port, uid)
    }
}

/** [bytes] to [file] through a temp file renamed over it (vibb's tmp + `os.replace`). */
internal fun writeAtomic(file: File, bytes: ByteArray) {
    file.parentFile?.mkdirs()
    val tmp = File.createTempFile(".${file.name}.", ".tmp", file.parentFile)
    try {
        tmp.writeBytes(bytes)
        if (!tmp.renameTo(file)) throw IOException("rename to $file failed")
    } finally {
        tmp.delete()
    }
}
