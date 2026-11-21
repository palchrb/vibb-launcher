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
 *
 * Ours, not vibb's (the user, 2026-11-21: "Vi bør jo bare vise høyttalere som faktisk er på det aktuelle wifi"):
 * each topology is recorded with the Wi-Fi network it answered on ([networkKey]), and the sheet shows only that
 * household's rooms while the phone is on that network ([roomsOn]); a household's groups replace its own groups
 * only; players not seen for [PRUNE_AGE_S] go at the next topology, but never the household the phone last played on.
 */

private val CACHE_JSON = Json { ignoreUnknownKeys = true; explicitNulls = false; encodeDefaults = true }

@Serializable
data class CachedPlayer(
    val ip: String,
    /** [SONOS_PORT] on a real speaker; the debug feed sets another for the emulator's fake. */
    val port: Int = SONOS_PORT,
    val name: String? = null,
    /** Wall-clock seconds, as vibb's `time.time()`; the report's age rule only ([SpeakerCache.reportable]), never a play. */
    @SerialName("seen_at") val seenAt: Double? = null,
    /**
     * The household this player was last seen in (P-L2): the key every player of one topology shares - the one its
     * players already had, else its smallest uid. Never reported; null in a cache from before it.
     */
    val household: String? = null,
)

@Serializable
data class CachedGroup(val coordinator: String, val members: List<String>)

/**
 * A Wi-Fi network ([SpeakerCache.networkKey]): every household whose topology answered on it -> when it last did (two
 * households on one LAN - an S1/S2 split, a shared building network - are both its).
 */
@Serializable
data class CachedNetwork(val households: Map<String, Double> = emptyMap())

/**
 * `sonos.json`: `{players: {uid: {ip, port, name, seen_at, household}}, groups: [...], fetched_at, topology_at, played,
 * networks: {key: {households: {household: seen_at}}}}`. [groups] are every household's last groups (each topology
 * replaces its own).
 */
@Serializable
data class SpeakerDoc(
    val players: Map<String, CachedPlayer> = emptyMap(),
    val groups: List<CachedGroup> = emptyList(),
    @SerialName("fetched_at") val fetchedAt: Double? = null,
    @SerialName("topology_at") val topologyAt: Double? = null,
    /** The room the phone last played on (renderer.json's uid when it was a room; P-L2's evidence of the home). */
    val played: String? = null,
    /** The Wi-Fi networks a topology answered on, by [SpeakerCache.networkKey]: the sheet's households there. */
    val networks: Map<String, CachedNetwork> = emptyMap(),
)

/**
 * The Wi-Fi connection the phone is on: [handle] is this one connection's (`Network.getNetworkHandle()`: a reconnect
 * is a new one - the rows' fresh mark), [key] the network's identity across reconnects ([SpeakerCache.networkKey];
 * null when it has no IPv4 subnet - no household is ever shown or recorded there). Null as a whole: no Wi-Fi.
 */
data class WifiLink(val handle: Long, val key: String?)

/** What [SpeakerCache.networkKey] reads of the Wi-Fi network's `LinkProperties` (no location permission needed). */
data class WifiFacts(
    /** The link's addresses as `address/prefixLength` (`LinkAddress.toString()`); only the IPv4 ones count. */
    val addresses: List<String>,
    /** The default routes' gateways (literals); only the IPv4 ones count. */
    val gateways: List<String>,
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
data class RoomsView(
    /**
     * Every household's rooms the cache knows - what the session reads (its group's members, the label, the scope's
     * look); never what the sheet lists ([sheet]).
     */
    val rooms: List<Room>,
    /** Not fresh: no topology answered on this Wi-Fi connection yet, or the last look got no answer; no grouping then. */
    val stale: Boolean,
    val scanning: Boolean,
    val busy: Set<String> = emptySet(),
    /** A look on this connection got no answer (or no Wi-Fi): the rows' "svarer ikke". Not before a look ran (no blink). */
    val noAnswer: Boolean = stale,
    /** "Spill av på"'s rooms: only the household that answered on the Wi-Fi network the phone is on now ([SpeakerCache.roomsOn]). */
    val sheet: List<Room> = rooms,
) {
    /** The view the sheet and its belts read: the current network's rooms as [rooms]. */
    fun forSheet(): RoomsView = copy(rooms = sheet)
}

/** The file (`noBackupFilesDir/sonos.json`, SonosFiles.kt), read and written whole, tmp + rename. */
class SpeakerCache(private val file: File, private val wallS: () -> Double = { System.currentTimeMillis() / 1000.0 }) {
    private val lock = Any()

    /** The cache; a missing or garbage file is empty, a bad row is dropped (`_load_cache`). */
    fun load(): SpeakerDoc = synchronized(lock) { read() }

    /** The cached record of [uid], or null. */
    fun resolve(uid: String): CachedPlayer? = load().players[uid]

    /**
     * A topology (`refresh_topology`): its players merged in with fresh ip, port and name (DHCP moves
     * and renames heal themselves), its household's groups replaced (another household's kept). [scanned]: it came
     * from an SSDP round (`rescan` stamps `fetched_at`). [network]: the Wi-Fi network it answered on (null: not
     * known for sure - the network changed during the call); this household is one of that network's from now on.
     * Then the prune ([pruned]) - [scope] is the parent's room scope, kept whatever its age.
     */
    fun applyTopology(household: Household, scanned: Boolean = false, network: String? = null, scope: List<String>? = null): SpeakerDoc = update { doc ->
        val now = wallS()
        val key = householdKey(doc, household.players.keys)
        val others = doc.groups.filter { g -> g.coordinator !in household.players && doc.players[g.coordinator]?.household.let { it != null && it != key } }
        pruned(
            doc.copy(
                players = doc.players + household.players.mapValues { (_, p) -> CachedPlayer(p.ip, p.port, p.name, now, key) },
                groups = others + household.groups.map { CachedGroup(it.coordinator, it.members) },
                topologyAt = now,
                fetchedAt = if (scanned) now else doc.fetchedAt,
                networks = if (network != null && key != null) {
                    doc.networks + (network to CachedNetwork(doc.networks[network]?.households.orEmpty() + (key to now)))
                } else {
                    doc.networks
                },
            ),
            now,
            scope,
        )
    }

    /** Players merged in without touching the groups (the debug feed's `sonos.json`). */
    fun seed(players: Collection<Zone>): SpeakerDoc = update { doc ->
        val now = wallS()
        val key = householdKey(doc, players.map { it.uid })
        doc.copy(players = doc.players + players.associate { it.uid to CachedPlayer(it.ip, it.port, it.name, now, key) }, fetchedAt = now)
    }

    /** The phone plays on room [uid] (P-L2): written only when it changes. */
    fun markPlayed(uid: String) {
        if (load().played != uid) update { it.copy(played = uid) }
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
            played = runCatching { root["played"]?.let { CACHE_JSON.decodeFromJsonElement<String?>(it) } }.getOrNull(),
            networks = (root["networks"] as? JsonObject).orEmpty()
                .mapNotNull { (key, v) -> runCatching { key to CACHE_JSON.decodeFromJsonElement<CachedNetwork>(v) }.getOrNull() }.toMap(),
        )
    }

    companion object {
        /** P-L2: the window the home is counted in when the phone never played on a room (and, before any household key, what is reported). */
        const val REPORT_MAX_AGE_S = 30 * 86_400.0

        /** A player not seen for this long leaves the cache at the next topology (2026-11-21), and a network not answered on. */
        const val PRUNE_AGE_S = 30 * 86_400.0

        /**
         * The prune (2026-11-21): players not seen for [PRUNE_AGE_S] (or never stamped) go - except every room of the
         * parent's [scope] and its household, and the household of the room the phone last played on
         * ([SpeakerDoc.played]): P-L2's home, which [reportable] names however long the phone was away -, with the
         * groups they coordinated; a network forgets a household with no player left or that hasn't answered on it for
         * as long (a kept household's never), and a network with none left goes.
         */
        fun pruned(doc: SpeakerDoc, nowS: Double, scope: List<String>? = null): SpeakerDoc {
            val scoped = scope.orEmpty().toSet()
            val kept = (listOfNotNull(doc.played) + scoped).mapNotNull { doc.players[it]?.household }.toSet()
            fun recent(seenAt: Double?) = seenAt != null && nowS - seenAt <= PRUNE_AGE_S
            val players = doc.players.filter { (uid, p) -> uid in scoped || p.household in kept || recent(p.seenAt) }
            val households = players.values.mapNotNull { it.household }.toSet()
            return doc.copy(
                players = players,
                groups = doc.groups.filter { it.coordinator in players },
                networks = doc.networks
                    .mapValues { (_, n) -> CachedNetwork(n.households.filter { (h, at) -> h in households && (h in kept || recent(at)) }) }
                    .filterValues { it.households.isNotEmpty() },
            )
        }

        /**
         * The Wi-Fi network's identity (2026-11-21): the link's IPv4 subnets (`a.b.c.0/len`, not the phone's own
         * address, which a new lease may change) and its IPv4 default gateways, hashed - the same LAN after a
         * reconnect gives the same key, where `Network.getNetworkHandle()` is new at every connection. No location
         * permission (never the SSID or BSSID), and no IPv6 (a prefix the ISP may rotate, a router address that may
         * come after the first look). Null without an IPv4 subnet. Two LANs with the same subnet and gateway (a
         * router's defaults) share a key: the rows of the household that answered last there show, unconfirmed, until
         * the sheet's refresh asks - and the household that answers takes the key.
         */
        fun networkKey(facts: WifiFacts): String? {
            val subnets = facts.addresses.mapNotNull(::ipv4Subnet).toSortedSet()
            if (subnets.isEmpty()) return null
            val gateways = facts.gateways.mapNotNull { g -> ipv4Bytes(g)?.joinToString(".") { (it.toInt() and 0xff).toString() } }.toSortedSet()
            val text = subnets.joinToString(",") + "|" + gateways.joinToString(",")
            val digest = java.security.MessageDigest.getInstance("SHA-256").digest(text.toByteArray())
            return "w" + digest.take(8).joinToString("") { "%02x".format(it) }
        }

        /** `192.168.1.23/24` -> `192.168.1.0/24`; null for IPv6 or anything else. */
        internal fun ipv4Subnet(linkAddress: String): String? {
            val parts = linkAddress.split('/')
            if (parts.size != 2) return null
            val bits = parts[1].toIntOrNull()?.takeIf { it in 0..32 } ?: return null
            val bytes = ipv4Bytes(parts[0]) ?: return null
            val mask = if (bits == 0) 0L else (0xffffffffL shl (32 - bits)) and 0xffffffffL
            val value = bytes.fold(0L) { acc, b -> (acc shl 8) or (b.toLong() and 0xff) } and mask
            return (3 downTo 0).joinToString(".") { ((value shr (it * 8)) and 0xff).toString() } + "/" + bits
        }

        /** An IPv4 literal's four bytes (a dotted quad only, never a name lookup), else null. */
        private fun ipv4Bytes(literal: String): ByteArray? {
            val parts = literal.trim().split('.')
            if (parts.size != 4 || parts.any { p -> p.isEmpty() || p.length > 3 || !p.all(Char::isDigit) || p.toInt() > 255 }) return null
            return ByteArray(4) { parts[it].toInt().toByte() }
        }

        /** The households whose topology answered on [network] (none: off Wi-Fi, no IPv4 key, or none answered there yet). */
        fun householdsOn(doc: SpeakerDoc, network: String?): Set<String> = network?.let { doc.networks[it]?.households?.keys }.orEmpty()

        /**
         * "Spill av på"'s rooms on [network] (2026-11-21): [rooms] of its households' players and groups only. None off
         * Wi-Fi and none on a network where no topology answered yet - the sheet's opening refresh and "Se etter
         * høyttalere" find them; a friend's speakers never show at home, nor the home's anywhere else.
         */
        fun roomsOn(doc: SpeakerDoc, network: String?): List<Room> {
            val households = householdsOn(doc, network)
            if (households.isEmpty()) return emptyList()
            val players = doc.players.filterValues { it.household in households }
            return rooms(doc.copy(players = players, groups = doc.groups.filter { it.coordinator in players }))
        }

        /**
         * Is [uid] a room of another household than [network]'s (2026-11-21)? Then a switch to it is refused and the
         * startup look never asks it - as an unreachable room. Only when [confirmed] (a topology answered on this very
         * connection) and [uid] is a known room of a household that never answered on this network; never on an
         * unconfirmed mapping (two LANs may share a key), off Wi-Fi or for a uid the cache can't name - the speaker's
         * own answer decides then.
         */
        fun elsewhere(doc: SpeakerDoc, uid: String, network: String?, confirmed: Boolean): Boolean {
            if (!confirmed) return false
            val households = householdsOn(doc, network)
            val household = doc.players[uid]?.household ?: return false
            return households.isNotEmpty() && household !in households
        }

        /**
         * The sheet's marks on [link] (2026-11-21) as (stale, noAnswer): fresh only once a topology answered on this
         * very connection ([answeredOn], a `Network` handle) - a known household's rows show at once after a
         * reconnect, stale (no grouping) until the opening's refresh answers; "svarer ikke" only once a look on this
         * connection got no answer ([failedOn]). Off Wi-Fi both.
         */
        fun roomMarks(link: WifiLink?, answeredOn: Long?, failedOn: Long?): Pair<Boolean, Boolean> =
            if (link == null) true to true else (answeredOn != link.handle) to (failedOn == link.handle)

        /** The household key for a topology of [uids]: the key most of them already have (ties: the smallest), else their smallest uid. */
        internal fun householdKey(doc: SpeakerDoc, uids: Collection<String>): String? {
            val known = uids.mapNotNull { doc.players[it]?.household }
            if (known.isNotEmpty()) return known.groupingBy { it }.eachCount().entries.sortedWith(compareBy({ -it.value }, { it.key })).first().key
            return uids.minOrNull()
        }

        /**
         * The players the report names, uid -> name (security review 2026-10-21 P-L2: not every speaker the phone ever
         * found, on any network): the home household's only, a friend's speakers never. The home is the household of
         * the parent's scoped rooms ([scope] a non-empty list); without a scope (every room, or none allowed), or while no
         * scoped room has a household yet, the phone's own evidence - the household of the room it last played on
         * ([SpeakerDoc.played]), else the one with the most players seen in the last [REPORT_MAX_AGE_S]. The home's
         * players are named however old (the parent's sheet keeps its rooms), a scoped room always; only when no player
         * has a household at all (a cache from before the key) is it every player seen in that time.
         */
        fun reportable(doc: SpeakerDoc, scope: List<String>?, nowS: Double): Map<String, String?> {
            val scoped = scope.orEmpty().toSet()
            fun fresh(p: CachedPlayer) = p.seenAt != null && nowS - p.seenAt <= REPORT_MAX_AGE_S
            val home = scoped.mapNotNull { doc.players[it]?.household }.toSet().ifEmpty {
                setOfNotNull(
                    doc.played?.let { doc.players[it]?.household }
                        ?: doc.players.values.filter(::fresh).mapNotNull { it.household }.groupingBy { it }.eachCount().entries
                            .sortedWith(compareBy({ -it.value }, { it.key })).firstOrNull()?.key,
                )
            }
            return doc.players.filter { (uid, p) ->
                uid in scoped || if (home.isEmpty()) fresh(p) else p.household in home
            }.mapValues { it.value.name }
        }

        /**
         * The picker's rows: `players_payload` (players by name, multi-member groups only) and then
         * `_sonos_choices`: a group of two or more known rooms is ONE row, "Kjøkken + Stua",
         * coordinator first, and selecting it targets the coordinator; its members don't repeat as
         * solo rows; a group left with fewer than two known rooms, or whose coordinator is unknown,
         * gives no group row (its known rooms show alone). Every row's [Room.members] is the whole
         * group, unknown uids included, so the room scope sees them (§5.5; correction 2026-10-12).
         * Every household's (the session's view); the sheet's is [roomsOn].
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
 * one round in flight. Only on Wi-Fi ([wifi]); without it nothing is asked and no round starts, and the
 * sheet lists no rooms (2026-11-21; vibb's cabin case listed the home's stale, §6.4). [state] is what the sheet
 * shows, published on every refresh, when a round starts and ends ("the rows refresh when it ends",
 * qa-26a-step1-code L3) and when the Wi-Fi network changes ([view]). Its [RoomsView.sheet] is the household that
 * answered on the current network; every answer is recorded with the network it came on. Blocking: call
 * [refresh] and [refreshTopology] off the main thread.
 */
class RoomDirectory(
    private val cache: SpeakerCache,
    /** GetZoneGroupState's `ZoneGroupState` at a speaker, within a time budget. */
    private val zoneGroupState: (ip: String, port: Int, timeoutMs: Long) -> String,
    /** One SSDP round on Wi-Fi (SsdpScanner). */
    private val ssdp: () -> List<SsdpHit>,
    /** The Wi-Fi connection now, null without Wi-Fi (SonosHome's network callback). */
    private val wifi: () -> WifiLink?,
    /** Where the background round runs (an IO scope of the caller's). */
    private val scope: CoroutineScope,
    private val elapsedMs: () -> Long,
    private val log: (String) -> Unit = {},
    /** A room's `CurrentTransportState` within a time budget (design 53 §2.7's busy look); null when it won't say. */
    private val transportState: (ip: String, port: Int, timeoutMs: Long) -> String? = { _, _, _ -> null },
    /** The parent's room scope now (null = every room): the prune keeps its rooms and their households. */
    private val scoped: () -> List<String>? = { null },
) {
    private val roundRunning = AtomicBoolean(false)
    private val views = MutableStateFlow(RoomsView(emptyList(), stale = false, scanning = false))

    /** The sheet's rows; empty until the first [view] or [refresh] (the file is never read on construction). */
    val state: StateFlow<RoomsView> = views.asStateFlow()

    val scanning: Boolean get() = roundRunning.get()

    /** The Wi-Fi connection ([WifiLink.handle]) a topology last answered on, and the one the sheet's last look got none on. */
    @Volatile private var answeredOn: Long? = null

    @Volatile private var failedOn: Long? = null

    private fun onWifi(): Boolean = wifi() != null

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

    /**
     * The cache as it is (a plain read), published: every room, and the sheet's - the current network's household -
     * with its marks ([SpeakerCache.roomMarks]). Also at every change of the Wi-Fi network (SonosHome): a known
     * household shows at once on its network, nothing on another.
     */
    fun view(): RoomsView = compose(scanning).also { views.value = it }

    private fun compose(scanning: Boolean): RoomsView {
        val link = wifi()
        val doc = cache.load()
        val (stale, noAnswer) = SpeakerCache.roomMarks(link, answeredOn, failedOn)
        return RoomsView(SpeakerCache.rooms(doc), stale, scanning, views.value.busy, noAnswer, SpeakerCache.roomsOn(doc, link?.key))
    }

    /**
     * Is [uid] a room of another household than the current Wi-Fi network's ([SpeakerCache.elsewhere]: only once a
     * topology answered on this connection)? A switch to it is refused and the startup look doesn't ask it. Blocking
     * (a file read).
     */
    fun elsewhere(uid: String): Boolean {
        val link = wifi() ?: return false
        return SpeakerCache.elsewhere(cache.load(), uid, link.key, confirmed = answeredOn == link.handle)
    }

    /**
     * [household] answered while the phone was on [asked]: merged in, and recorded for that network only if the phone
     * is still on that very connection with the same key (a network change during the call leaves it unattributed and
     * not fresh).
     */
    private fun answered(asked: WifiLink, household: Household, scanned: Boolean): SpeakerDoc {
        val same = wifi() == asked
        val doc = cache.applyTopology(household, scanned, network = asked.key.takeIf { same }, scope = scoped())
        if (same) {
            answeredOn = asked.handle
            failedOn = null
        }
        return doc
    }

    /**
     * "Spiller noe annet" (design 53 §2.7): every other row's coordinator - not [ours], not a row holding it - is
     * asked its `GetTransportInfo` in parallel, [BUSY_PER_MS] each, at most [BUSY_MAX] rows; the PLAYING ones are
     * published as [RoomsView.busy] (a row that doesn't answer is not busy). Called when the sheet opens, after its
     * topology refresh, and at a `groups` event while it is open - never on a timer. Only on Wi-Fi, only the sheet's rows.
     */
    suspend fun lookBusy(ours: String?): Set<String> {
        val link = wifi()
        if (link == null) {
            clearBusy()
            return emptySet()
        }
        val doc = cache.load()
        val rows = SpeakerCache.roomsOn(doc, link.key).filter { it.uid != ours && (ours == null || ours !in it.members) }.take(BUSY_MAX)
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

    /** No busy lines (the sheet opening, before its new look: the last opening's are stale; qa-53b-code 9). */
    fun clearBusy() = views.update { it.copy(busy = emptySet()) }

    /**
     * `refresh_topology`: GetZoneGroupState against the cached speakers - the current network's household first,
     * then the rest, each in uid order - until one answers, [PER_IP_MS] each and [TOTAL_MS] in all (QA M7). The
     * answer is this network's household from now on. Throws when nobody answers.
     */
    fun refreshTopology(): SpeakerDoc {
        val link = wifi() ?: throw SpeakerUnreachable("no Wi-Fi")
        val start = elapsedMs()
        var last: Exception? = null
        val doc = cache.load()
        val here = SpeakerCache.householdsOn(doc, link.key)
        for ((_, rec) in doc.players.entries.sortedWith(compareBy({ here.isNotEmpty() && it.value.household !in here }, { it.key }))) {
            val left = TOTAL_MS - (elapsedMs() - start)
            if (left <= 0) break
            try {
                val household = Topology.parse(zoneGroupState(rec.ip, rec.port, minOf(PER_IP_MS, left)))
                    ?: throw SpeakerUnreachable("no topology from ${rec.ip}")
                return answered(link, household, scanned = false)
            } catch (e: IOException) {
                last = e
            }
        }
        throw last ?: SpeakerUnreachable("no cached speaker to ask")
    }

    /**
     * The sheet opening (`/players?fresh=1`): one topology refresh; nobody answering serves the
     * cache marked stale ("svarer ikke") at once and starts one background round (on Wi-Fi).
     */
    fun refresh(): RoomsView = try {
        refreshTopology()
        view()
    } catch (e: IOException) {
        log("rooms: nobody answered (${e.javaClass.simpleName}) - the cache, stale")
        answeredOn = null
        failedOn = wifi()?.handle
        if (onWifi()) rescanInBackground()
        view()
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
            // the new rows, still stale if no household answered on this connection; scanning ends below
            views.value = compose(scanning = true)
        }
        job.invokeOnCompletion {
            views.update { it.copy(scanning = false) }
            roundRunning.set(false)
        }
        return true
    }

    /**
     * One round: SSDP for the speakers on this network, then the household from the first one whose
     * topology answers (SSDP alone learns neither uids nor groups), merged into the cache. True when one did.
     */
    private fun round(): Boolean {
        val link = wifi() ?: return false
        for (hit in ssdp()) {
            try {
                val household = Topology.parse(zoneGroupState(hit.ip, hit.port, PER_IP_MS)) ?: continue
                answered(link, household, scanned = true)
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
