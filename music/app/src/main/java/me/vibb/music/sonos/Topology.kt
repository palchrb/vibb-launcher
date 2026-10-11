package me.vibb.music.sonos

import org.w3c.dom.Element

/*
 * The household from one GetZoneGroupState, ported from palchrb/vibb `pi/sonosd.py`
 * (`refresh_topology`, `_speaker`'s uid; MIT License, Copyright (c) 2026 palchrb), reimplemented in
 * Kotlin under the GPL-3.0 (or later) - see NOTICE.md. Design 26a §2.1. Pure; TopologyTest.
 *
 * SoCo's `all_groups` reads the same XML under the hood; here it is done in the open. The state
 * comes with or without the `<ZoneGroupState><ZoneGroups>` wrapper (firmwares differ, QA L6).
 */

/** A room: [ip] and [port] from its `Location` (http://ip:port/xml/device_description.xml). */
data class Player(val uid: String, val ip: String, val port: Int, val name: String?)

/** A group as the Sonos app made it: [members] coordinator first, visible rooms only. */
data class Group(val coordinator: String, val members: List<String>)

data class Household(val players: Map<String, Player>, val groups: List<Group>)

object Topology {
    /**
     * `refresh_topology`'s rules: `Invisible="1"` members (bonded surrounds, subs) are no rooms; a
     * group whose coordinator isn't a visible member can't be offered and is skipped; members are
     * coordinator first, the rest in the speaker's order. Null when [zoneGroupState] isn't XML.
     */
    fun parse(zoneGroupState: String): Household? {
        val root = runCatching { parseXml(zoneGroupState).documentElement }.getOrNull() ?: return null
        val players = linkedMapOf<String, Player>()
        val groups = mutableListOf<Group>()
        for (g in zoneGroups(root)) {
            val coordinator = g.getAttribute("Coordinator").takeIf { it.isNotEmpty() } ?: continue
            val members = mutableListOf<String>()
            for (m in children(g).filter { it.localName == "ZoneGroupMember" || it.nodeName == "ZoneGroupMember" }) {
                if (m.getAttribute("Invisible") == "1") continue
                val player = member(m) ?: continue
                players[player.uid] = player
                members.add(player.uid)
            }
            if (coordinator !in members) continue
            groups.add(Group(coordinator, listOf(coordinator) + members.filter { it != coordinator }))
        }
        return Household(players, groups)
    }

    /** The coordinator of the group [uid] is in (`spk.group.coordinator.uid`), visible or not. */
    fun coordinatorOf(zoneGroupState: String, uid: String): String? {
        val root = runCatching { parseXml(zoneGroupState).documentElement }.getOrNull() ?: return null
        return zoneGroups(root).firstOrNull { g -> children(g).any { it.getAttribute("UUID") == uid } }
            ?.getAttribute("Coordinator")?.takeIf { it.isNotEmpty() }
    }

    private fun zoneGroups(root: Element): List<Element> {
        val nodes = root.getElementsByTagName("ZoneGroup")
        return (0 until nodes.length).mapNotNull { nodes.item(it) as? Element }
    }

    private fun member(m: Element): Player? {
        val uid = m.getAttribute("UUID").takeIf { it.isNotEmpty() } ?: return null
        val (ip, port) = location(m.getAttribute("Location")) ?: return null
        return Player(uid, ip, port, m.getAttribute("ZoneName").takeIf { it.isNotEmpty() })
    }

    /** `http://<ip>:<port>/...` -> (ip, port); the port defaults to [SONOS_PORT]. */
    fun location(location: String): Pair<String, Int>? {
        val m = Regex("^https?://([^/:\\[\\]]+|\\[[0-9A-Fa-f:.]+])(?::([0-9]{1,5}))?(/.*)?$").find(location.trim()) ?: return null
        val port = m.groupValues[2].takeIf { it.isNotEmpty() }?.toInt() ?: SONOS_PORT
        if (port !in 1..65535) return null
        return m.groupValues[1].removePrefix("[").removeSuffix("]") to port
    }
}
