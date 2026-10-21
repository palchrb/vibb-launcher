package me.vibb.music.sonos

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/*
 * The speaker's local event socket, the pure half (design 53 §2.2): the messages, the handshake and
 * what each message means for the session. Ours (GPL-3.0 or later), written from the measurements of
 * `scripts/sonos-ws-probe.py` and `scripts/sonos-play-test.py` on the user's speakers (design 53 §0.1);
 * the sonos-websocket library was read for the handshake, not copied. vibb polls only (§6). Events
 * only: every command still goes over UPnP, and nothing here decides "ours", the advance or a
 * bookmark - an event re-bases the bar, confirms our own verb and wakes a poll. Android-free;
 * SonosEventsTest replays `src/test/resources/sonos/ws/`.
 */

/** A `playbackStatus`'s `playbackState` (`PLAYBACK_STATE_PLAYING` or `PLAYING`). */
enum class WsPlayback {
    BUFFERING,
    PLAYING,
    PAUSED,
    IDLE,
    ;

    /**
     * The UPnP state it stands for (§2.3): PLAYING, PAUSED and IDLE are states; BUFFERING is
     * TRANSITIONING - never a state of its own (G1-d).
     */
    val transport: TransportState
        get() = when (this) {
            PLAYING -> TransportState.PLAYING
            PAUSED -> TransportState.PAUSED_PLAYBACK
            IDLE -> TransportState.STOPPED
            BUFFERING -> TransportState.TRANSITIONING
        }

    companion object {
        fun parse(raw: String?): WsPlayback? {
            val s = raw?.removePrefix("PLAYBACK_STATE_") ?: return null
            return entries.firstOrNull { it.name == s }
        }
    }
}

/**
 * The item id of every DIDL we send (`Didl.kt`'s `<item id="vibb-1" ...>`, vibb's), which the speaker shows as
 * `metadataStatus`'s `container.id.objectId` for our own direct-URI plays (captured 2026-10-21, design 53-d). A hint
 * only: an absent or other id is no evidence, and UPnP's TrackURI stays the verdict (`SonosController.onSocket`).
 */
const val OUR_OBJECT_ID = "vibb-1"

/** One group of a `groups` message. */
data class WsGroup(val id: String, val coordinatorId: String, val playerIds: List<String>)

/** A `playbackStatus`'s `playModes` (design 54 §2.6: a mode we didn't set is followed, never corrected). */
data class WsPlayModes(val repeat: Boolean, val repeatOne: Boolean, val shuffle: Boolean, val crossfade: Boolean)

/**
 * A `metadataStatus`'s Spotify track (design 54 §2.5, §2.8, §2.12): its uri (`spotify:track:<id>`), name, artist and
 * duration - a cross-check of UPnP's, and the title of an unmapped track or a context's live one. Printed without
 * them (design 54 §2.14).
 */
data class WsTrack(val uri: String, val name: String?, val artist: String?, val durationMs: Long?) {
    override fun toString(): String = "WsTrack(${uri.substringBeforeLast(':')}, durationMs=$durationMs)"
}

/**
 * What the socket tells the controller, compact, stamped at receipt ([Playback.at]: the elapsed
 * clock). Never a URL: a `websocketUrl` is read for its player id only and never dialled.
 */
sealed interface SocketEvent {
    /**
     * A `playbackStatus` of the group we subscribed (also the `getPlaybackStatus` reply); [itemId] is the speaker's
     * queue item (a new one with every `SetAVTransportURI`; none at the natural end's IDLE) - design 53-d's "new item".
     * Design 54 §2.5: [queueVersion] (moves only when the queue is edited, §0.3), [playModes] and
     * [previousPositionMs] - cross-checks and wakes only; `previousItemId` is another id space and never read.
     */
    data class Playback(
        val groupId: String,
        val state: WsPlayback,
        val positionMs: Long?,
        val at: Long,
        val itemId: String? = null,
        val queueVersion: String? = null,
        val playModes: WsPlayModes? = null,
        val previousPositionMs: Long? = null,
    ) : SocketEvent

    /**
     * A `metadataStatus` of the group we subscribed: its `container.id.objectId` (design 53-d: our DIDL's item id,
     * [OUR_OBJECT_ID], for a play of ours; design 54: `spotify:playlist:<id>` for a share link's queue) - and, only for
     * a Spotify track (service 9 or a `spotify:` object id, design 54 §2.5), the current [track] and the [nextUri]. Never
     * a picture. Printed without ids or titles (design 54 §2.14).
     */
    data class Metadata(
        val groupId: String,
        val objectId: String?,
        val at: Long,
        val track: WsTrack? = null,
        val nextUri: String? = null,
    ) : SocketEvent {
        override fun toString(): String =
            "Metadata(groupId=$groupId, objectId=${objectId?.substringBeforeLast(':')}, at=$at, track=$track, next=${nextUri != null})"
    }

    /** A `groups` event after the handshake: the group holding our player, and every group. */
    data class Groups(val ourGroupId: String?, val ourCoordinatorId: String?, val members: List<String>, val groups: List<WsGroup>) : SocketEvent

    /** `groupCoordinatorChanged` `GROUP_STATUS_MOVED`: the group now coordinated by [playerId]. */
    data class Moved(val playerId: String?) : SocketEvent

    /** Healthy (open, our group's playback subscribed and its status read, live) or not. */
    data class Health(val healthy: Boolean) : SocketEvent
}

/** One message: `[header, body]`. */
class WsMessage(val header: JsonObject, val body: JsonObject) {
    fun str(key: String): String? = (header[key] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
    fun bodyStr(key: String): String? = (body[key] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
}

object SonosEvents {
    /** Messages above this are dropped unread (a household's `groups` is a few KB). */
    const val MAX_MESSAGE_CHARS = 64 * 1024

    private val JSON = Json { ignoreUnknownKeys = true }

    /** `[header, body]`, both objects, or null: junk, another shape, over [MAX_MESSAGE_CHARS]. */
    fun parse(text: String): WsMessage? {
        if (text.length > MAX_MESSAGE_CHARS) return null
        val root = runCatching { JSON.parseToJsonElement(text) }.getOrNull() as? JsonArray ?: return null
        if (root.size != 2) return null
        val header = root[0] as? JsonObject ?: return null
        val body = root[1] as? JsonObject ?: return null
        return WsMessage(header, body)
    }

    /** One command as text: `[header + cmdId, body]` (`sonos-ws-probe.py`'s `command`). */
    fun command(cmdId: String, namespace: String?, command: String?, idKey: String? = null, id: String? = null): String =
        buildJsonArray {
            add(
                buildJsonObject {
                    if (namespace != null) put("namespace", namespace)
                    if (command != null) put("command", command)
                    if (idKey != null && id != null) put(idKey, id)
                    put("cmdId", cmdId)
                },
            )
            add(JsonObject(emptyMap()))
        }.toString()

    /** A non-negative millisecond value of [body], or null. */
    private fun millis(body: JsonObject, key: String): Long? = (body[key] as? JsonPrimitive)?.longOrNull?.takeIf { it >= 0 }

    /** The string at [path] under [element] (objects only), or null. */
    private fun text(element: JsonElement?, vararg path: String): String? {
        var e = element
        for (k in path) e = (e as? JsonObject)?.get(k)
        return (e as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
    }

    /**
     * A queue item's track when it is Spotify's (design 54 §2.5): `track.id.serviceId` 9 or a `spotify:` object id; its
     * uri, `name`, `artist.name` and `durationMillis`. Anything else (another service, no id) is null.
     */
    private fun spotifyTrack(item: JsonElement?): WsTrack? {
        val track = (item as? JsonObject)?.get("track") as? JsonObject ?: return null
        val uri = text(track, "id", "objectId") ?: return null
        if (text(track, "id", "serviceId") != "9" && !uri.startsWith("spotify:")) return null
        return WsTrack(uri, text(track, "name"), text(track, "artist", "name"), millis(track, "durationMillis"))
    }

    /** The host of a player's `websocketUrl` (`wss`, then `<ip>:1443/...`), for matching only - never dialled. */
    fun wsHost(url: String?): String? {
        val rest = url?.takeIf { it.startsWith("wss:") }?.substringAfter("//", "")?.takeIf { it.isNotEmpty() } ?: return null
        return if (rest.startsWith("[")) rest.substringAfter('[').substringBefore(']') else rest.substringBefore('/').substringBefore(':')
    }

    private fun groupsOf(body: JsonObject): List<WsGroup> = (body["groups"] as? JsonArray).orEmpty().mapNotNull { g ->
        val o = g as? JsonObject ?: return@mapNotNull null
        val id = (o["id"] as? JsonPrimitive)?.contentOrNull ?: return@mapNotNull null
        val coordinator = (o["coordinatorId"] as? JsonPrimitive)?.contentOrNull.orEmpty()
        val players = (o["playerIds"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
        WsGroup(id, coordinator, players)
    }

    /** (id, websocketUrl) of each player. */
    private fun playersOf(body: JsonObject): List<Pair<String, String?>> = (body["players"] as? JsonArray).orEmpty().mapNotNull { p ->
        val o = p as? JsonObject ?: return@mapNotNull null
        val id = (o["id"] as? JsonPrimitive)?.contentOrNull ?: return@mapNotNull null
        id to (o["websocketUrl"] as? JsonPrimitive)?.contentOrNull
    }

    /** What one message asks of the socket: [commands] to send (in order) and [events] for the controller. */
    data class Step(val commands: List<String> = emptyList(), val events: List<SocketEvent> = emptyList())

    /**
     * The handshake and the subscriptions of one connection (`sonos-ws-probe.py`'s order): the empty
     * command -> the `householdId` -> `groups:1 getGroups` -> our player (the `players[]` entry whose
     * `id` is [uid], else whose `websocketUrl` host is the [ip] we dialled) -> the group it coordinates
     * -> `playback:1 subscribe` and `getPlaybackStatus` (its reply is an event), `playbackMetadata:1 subscribe`
     * (design 53-d: our DIDL's id as an early hint), then `groups:1 subscribe`. A later `groups` re-subscribes playback only for a group our player coordinates
     * whose id changed (grouped away it would only earn a `MOVED`); our player coordinating none, a
     * refused subscribe or a `MOVED` for it leaves the connection open for `groups` but not [healthy].
     * Not thread-safe: one connection's reader thread owns it.
     */
    class Plan(private val uid: String, private val ip: String) {
        private var n = 0
        private var household: String? = null
        private var player: String? = null
        private var groupsSeen = false

        /** The playback group we subscribed, its ack and its first status. */
        private var subscribed: String? = null
        private var acked = false
        private var status = false

        /** The player id the speaker gave our uid (null until `getGroups` answered). */
        val playerId: String? get() = player

        /** Our coordinator's playback is subscribed, acknowledged and its status read. */
        val healthy: Boolean get() = subscribed != null && acked && status

        private fun next(): String = "vibb-${++n}"

        /** The first command: an empty one, which answers with the household id. */
        fun start(): List<String> = listOf(command(next(), null, null))

        fun receive(m: WsMessage, at: Long): Step {
            val type = m.str("type")
            if (household == null) {
                val hh = m.str("householdId") ?: return Step()
                household = hh
                return Step(commands = listOf(command(next(), "groups:1", "getGroups", "householdId", hh)))
            }
            // a subscribe's answer is told by its `response` first (qa-53a-code 7). Captured 2026-10-21 (design 53 §5
            // check 1): an ack is `type: none` with an empty body, the status follows as its own `playbackStatus`; a MOVED
            // is the subscribe's own reply (`success: false`, `type: groupCoordinatorChanged`). Only the answer about OUR
            // playback subscription counts - one for another namespace or another group says nothing of ours, and must
            // never end it (the probe's --all subscribed every group: each other one answered MOVED)
            if (m.str("response") == "subscribe" && m.str("namespace") != "groups:1") {
                val gid = m.str("groupId")
                if (m.str("namespace") != "playback:1" || (gid != null && gid != subscribed)) return Step()
                val ok = (m.header["success"] as? JsonPrimitive)?.booleanOrNull != false
                if (ok) acked = true
                if (!ok) subscribed = null
                return when (type) {
                    "playbackStatus" -> playback(m, at)
                    "groupCoordinatorChanged" -> coordinatorChanged(m)
                    else -> Step()
                }
            }
            return when (type) {
                "groups" -> groups(m)
                "playbackStatus" -> playback(m, at)
                "metadataStatus" -> metadata(m, at)
                "groupCoordinatorChanged" -> coordinatorChanged(m)
                else -> Step()
            }
        }

        private fun groups(m: WsMessage): Step {
            val groups = groupsOf(m.body)
            if (player == null) {
                val players = playersOf(m.body)
                player = players.firstOrNull { it.first == uid }?.first ?: players.firstOrNull { wsHost(it.second) == ip }?.first
            }
            val me = player
            val mine = me?.let { p -> groups.firstOrNull { p in it.playerIds } }
            val commands = mutableListOf<String>()
            val coordinated = mine?.takeIf { it.coordinatorId == me }
            if (coordinated == null) {
                subscribed = null
            } else if (coordinated.id != subscribed) {
                subscribed = coordinated.id
                acked = false
                status = false
                commands += command(next(), "playback:1", "subscribe", "groupId", coordinated.id)
                commands += command(next(), "playback:1", "getPlaybackStatus", "groupId", coordinated.id)
                // design 53-d: the metadata only for our DIDL's item id (an early "ours" hint); its answers say nothing of health
                commands += command(next(), "playbackMetadata:1", "subscribe", "groupId", coordinated.id)
            }
            val first = !groupsSeen
            if (first) {
                groupsSeen = true
                commands += command(next(), "groups:1", "subscribe", "householdId", household)
            }
            val events = if (first) emptyList() else listOf(SocketEvent.Groups(mine?.id, mine?.coordinatorId, mine?.playerIds.orEmpty(), groups))
            return Step(commands, events)
        }

        private fun playback(m: WsMessage, at: Long): Step {
            val gid = m.str("groupId") ?: return Step()
            if (gid != subscribed) return Step() // another group's (none subscribed): not ours to read
            val state = WsPlayback.parse(m.bodyStr("playbackState")) ?: return Step()
            status = true
            val pos = millis(m.body, "positionMillis")
            val modes = (m.body["playModes"] as? JsonObject)?.let { o ->
                fun flag(k: String) = (o[k] as? JsonPrimitive)?.booleanOrNull == true
                WsPlayModes(flag("repeat"), flag("repeatOne"), flag("shuffle"), flag("crossfade"))
            }
            return Step(
                events = listOf(
                    SocketEvent.Playback(
                        gid, state, pos, at, m.bodyStr("itemId"),
                        queueVersion = m.bodyStr("queueVersion"), playModes = modes, previousPositionMs = millis(m.body, "previousPositionMillis"),
                    ),
                ),
            )
        }

        /**
         * Our group's `metadataStatus`: `container.id.objectId` (design 53-d), and for a Spotify track (design 54 §2.5)
         * `currentItem`'s uri, name, artist and duration and `nextItem`'s uri. Never a picture.
         */
        private fun metadata(m: WsMessage, at: Long): Step {
            val gid = m.str("groupId") ?: return Step()
            if (gid != subscribed || m.str("namespace") != "playbackMetadata:1") return Step()
            val id = text(m.body["container"], "id", "objectId")
            val current = spotifyTrack(m.body["currentItem"])
            val next = spotifyTrack(m.body["nextItem"])?.uri
            return Step(events = listOf(SocketEvent.Metadata(gid, id, at, current, next)))
        }

        private fun coordinatorChanged(m: WsMessage): Step {
            if (m.bodyStr("groupStatus") != "GROUP_STATUS_MOVED") return Step()
            val gid = m.str("groupId")
            if (gid == null || gid == subscribed) subscribed = null
            // the new coordinator's websocketUrl is never connected to: its player id only
            return Step(events = listOf(SocketEvent.Moved(m.bodyStr("playerId"))))
        }
    }
}
