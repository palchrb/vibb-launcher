package me.vibb.music.sonos

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import java.io.File
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
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

/**
 * One picker row (`_sonos_choices`): [uid] is what a tap targets (a group's coordinator); [members]
 * are the uids grouped with it as the household's topology says, coordinator first - unknown ones
 * (no player row in the cache) included - and [uid] alone for a room on its own. The parent's room
 * scope allows a row only when it allows every one of them (design 26a §5.5): a member the phone
 * can't name is not allowed by any list of rooms. [names] are the known members' (uid, name) pairs,
 * coordinator first (design 53 §2.7: the group block's member lines).
 */
data class Room(val label: String, val uid: String, val names: List<Pair<String, String>>, val members: List<String> = listOf(uid))

/**
 * What the "Spill av på" sheet shows: [stale] = nobody answered, [scanning] = an SSDP round runs; [busy] = the
 * coordinators of other rows that play something else (design 53 §2.7, "Spiller noe annet": read when the sheet
 * opens and at a `groups` event while it is open, never on a timer).
 */
data class RoomsView(val rooms: List<Room>, val stale: Boolean, val scanning: Boolean, val busy: Set<String> = emptySet())

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
    fun seed(players: Collection<Zone>): SpeakerDoc = update { doc ->
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
         * gives no group row (its known rooms show alone). Every row's [Room.members] is the whole
         * group, unknown uids included, so the room scope sees them (§5.5; correction 2026-10-12).
         */
        fun rooms(doc: SpeakerDoc): List<Room> {
            val players = doc.players.entries.sortedBy { it.value.name.orEmpty() }
            val names = players.associate { it.key to (it.value.name.takeUnless { n -> n.isNullOrEmpty() } ?: "?") }
            val rows = mutableListOf<Room>()
            val absorbed = mutableSetOf<String>()
            val groupOf = mutableMapOf<String, List<String>>()
            for (g in doc.groups.filter { it.members.size > 1 }) {
                g.members.forEach { groupOf[it] = g.members }
                val members = g.members.filter { it in names }
                if (members.size < 2 || g.coordinator !in names) continue
                rows.add(Room(members.joinToString(" + ") { names.getValue(it) }, g.coordinator, members.map { it to names.getValue(it) }, g.members))
                absorbed.addAll(members)
            }
            for ((uid, _) in players) {
                if (uid !in absorbed) rows.add(Room(names.getValue(uid), uid, listOf(uid to names.getValue(uid)), groupOf[uid] ?: listOf(uid)))
            }
            return rows
        }
    }
}

/**
 * The household behind the picker: one topology refresh when the sheet opens, the background SSDP
 * round when nobody answers (`pipewire`'s `rescan_in_background`: "the list fills itself"), at most
 * one round in flight. Only on Wi-Fi ([onWifi]); without it the cache is served stale and no round
 * starts (the cabin case, §6.4). [state] is what the sheet shows, published on every refresh and when
 * a round starts and ends ("the rows refresh when it ends", qa-26a-step1-code L3). Blocking: call
 * [refresh], [refreshTopology] and [scanNow] off the main thread.
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
    /** A room's `CurrentTransportState` within a time budget (design 53 §2.7's busy look); null when it won't say. */
    private val transportState: (ip: String, port: Int, timeoutMs: Long) -> String? = { _, _, _ -> null },
) {
    private val roundRunning = AtomicBoolean(false)
    private val views = MutableStateFlow(RoomsView(emptyList(), stale = false, scanning = false))

    /** The sheet's rows; empty until the first [view] or [refresh] (the file is never read on construction). */
    val state: StateFlow<RoomsView> = views.asStateFlow()

    val scanning: Boolean get() = roundRunning.get()

    /**
     * One topology refresh and the rows published - never a discovery round, nothing published when
     * nobody answers. The parent's room scope watches the household's groups with it while a
     * session plays on a room (design 26a §5.5, qa-26a-step3c-code M1). True when a speaker answered.
     */
    fun refreshQuietly(): Boolean = try {
        refreshTopology()
        view()
        true
    } catch (e: IOException) {
        log("rooms: the scope's look at the groups got no answer (${e.javaClass.simpleName})")
        false
    }

    /** The cache as it is (a plain read), published. */
    fun view(stale: Boolean = false): RoomsView =
        RoomsView(SpeakerCache.rooms(cache.load()), stale, scanning, views.value.busy).also { views.value = it }

    /**
     * "Spiller noe annet" (design 53 §2.7): every other row's coordinator - not [ours], not a row holding it - is
     * asked its `GetTransportInfo` in parallel, [BUSY_PER_MS] each, at most [BUSY_MAX] rows; the PLAYING ones are
     * published as [RoomsView.busy] (a row that doesn't answer is not busy). Called when the sheet opens, after its
     * topology refresh, and at a `groups` event while it is open - never on a timer. Only on Wi-Fi.
     */
    suspend fun lookBusy(ours: String?): Set<String> {
        if (!onWifi()) return emptySet()
        val doc = cache.load()
        val rows = SpeakerCache.rooms(doc).filter { it.uid != ours && (ours == null || ours !in it.members) }.take(BUSY_MAX)
        val found = ConcurrentHashMap.newKeySet<String>()
        withTimeoutOrNull(BUSY_TOTAL_MS) {
            coroutineScope {
                for (r in rows) {
                    val rec = doc.players[r.uid] ?: continue
                    launch(Dispatchers.IO) {
                        val state = try {
                            transportState(rec.ip, rec.port, BUSY_PER_MS)
                        } catch (_: IOException) {
                            null
                        }
                        if (state != null && TransportState.parse(state) == TransportState.PLAYING) found.add(r.uid)
                    }
                }
            }
        }
        val busy = found.toSet()
        views.update { it.copy(busy = busy) }
        return busy
    }

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

    /**
     * One SSDP round off the caller's thread; false when one already runs (or no Wi-Fi). The flag
     * is cleared when the job completes in any way - also when the scope was cancelled before the
     * round ever ran (qa-26a-step1-code L4) - and the rows are published when it ends.
     */
    fun rescanInBackground(): Boolean {
        if (!onWifi() || !roundRunning.compareAndSet(false, true)) return false
        views.update { it.copy(scanning = true) }
        val job = scope.launch {
            val answered = try {
                round().also { log("background scan: ${if (it) "the household answered" else "nobody answered"}") }
            } catch (e: Exception) {
                log("background scan failed (${e.javaClass.simpleName})")
                false
            }
            // the new rows, still stale if no household answered; scanning ends below
            views.value = RoomsView(SpeakerCache.rooms(cache.load()), stale = !answered && views.value.stale, scanning = true, busy = views.value.busy)
        }
        job.invokeOnCompletion {
            views.update { it.copy(scanning = false) }
            roundRunning.set(false)
        }
        return true
    }

    /**
     * `rescan`: SSDP for the speakers on this network, then the household from the first one whose
     * topology answers (SSDP alone learns neither uids nor groups). Merges into the cache.
     */
    fun scanNow(): SpeakerDoc {
        round()
        return cache.load()
    }

    /** One round; true when a household answered and was merged in. */
    private fun round(): Boolean {
        for (hit in ssdp()) {
            try {
                val household = Topology.parse(zoneGroupState(hit.ip, hit.port, PER_IP_MS)) ?: continue
                cache.applyTopology(household, scanned = true)
                return true
            } catch (e: IOException) {
                log("scan: ${e.javaClass.simpleName} from a speaker - trying the next")
            }
        }
        return false
    }

    companion object {
        const val PER_IP_MS = 2_000L
        const val TOTAL_MS = 6_000L

        /** The busy look (design 53 §2.7): 1 s a room, 2 s in all, at most 8 rows. */
        const val BUSY_PER_MS = 1_000L
        const val BUSY_TOTAL_MS = 2_000L
        const val BUSY_MAX = 8
    }
}

/** A speaker by uid, for the transport. */
fun interface SpeakerResolver {
    /**
     * [verify]: compare the uid at the cached ip (the play path only); a mismatch is [UnknownUid].
     * [budgetMs]: one time budget for every call of the returned speaker (SoapSpeaker), or null.
     */
    fun speaker(uid: String, verify: Boolean, budgetMs: Long?): SpeakerControl
}

/** `_speaker(uid)`: the cached ip, no discovery; an unreachable speaker is left to the poller. */
class CachedSpeakers(private val cache: SpeakerCache, private val soap: Soap) : SpeakerResolver {
    override fun speaker(uid: String, verify: Boolean, budgetMs: Long?): SpeakerControl {
        val rec = cache.resolve(uid) ?: throw UnknownUid(uid)
        if (verify) {
            val at = try {
                soap.uidOf(rec.ip, rec.port, budgetMs)
            } catch (_: SpeakerUnreachable) {
                null // unreachable: let the caller classify; no rescan here (the play path's failure does it)
            }
            if (at != null && at != uid) throw UnknownUid(uid)
        }
        return SoapSpeaker(soap, rec.ip, rec.port, uid, budgetMs)
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
