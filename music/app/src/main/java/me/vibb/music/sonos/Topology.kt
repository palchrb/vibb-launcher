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

/**
 * A room (a zone player): [ip] and [port] from its `Location` (http://ip:port/xml/device_description.xml).
 * Not `Player`: Media3's `Player` will share this package's SonosRenderer (qa-26a-step1-code L5).
 */
data class Zone(val uid: String, val ip: String, val port: Int, val name: String?)

/** A group as the Sonos app made it: [members] coordinator first, visible rooms only. */
data class Group(val coordinator: String, val members: List<String>)

data class Household(val players: Map<String, Zone>, val groups: List<Group>)

object Topology {
    /**
     * `refresh_topology`'s rules: `Invisible="1"` members (bonded surrounds, subs) are no rooms; a
     * group whose coordinator isn't a visible member can't be offered and is skipped; members are
     * coordinator first, the rest in the speaker's order. Null when [zoneGroupState] isn't XML.
     */
    fun parse(zoneGroupState: String): Household? {
        val root = runCatching { parseXml(zoneGroupState).documentElement }.getOrNull() ?: return null
        val players = linkedMapOf<String, Zone>()
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

    private fun member(m: Element): Zone? {
        val uid = m.getAttribute("UUID").takeIf { it.isNotEmpty() } ?: return null
        val (ip, port) = location(m.getAttribute("Location")) ?: return null
        return Zone(uid, ip, port, m.getAttribute("ZoneName").takeIf { it.isNotEmpty() })
    }

    /**
     * `http://<ip>:<port>/...` -> (ip, port); the port defaults to [SONOS_PORT]. An IP literal only
     * (a speaker's Location always is one): a name would go to the phone's DNS (the launcher's filter),
     * never the LAN's, and an odd host cached here would fail every later call (qa-26a-step1-code L1).
     */
    fun location(location: String): Pair<String, Int>? {
        val m = Regex("^https?://([0-9.]+|\\[[0-9A-Fa-f:.]+])(?::([0-9]{1,5}))?(/.*)?$").find(location.trim()) ?: return null
        val port = m.groupValues[2].takeIf { it.isNotEmpty() }?.toInt() ?: SONOS_PORT
        val ip = m.groupValues[1].removePrefix("[").removeSuffix("]")
        if (port !in 1..65535 || !isIpLiteral(ip)) return null
        return ip to port
    }
}

/** A dotted-quad IPv4 or an IPv6 literal (no brackets): an address that needs no DNS. */
fun isIpLiteral(host: String): Boolean {
    val octets = host.split('.')
    if (octets.size == 4 && octets.all { Regex("[0-9]{1,3}").matches(it) && it.toInt() <= 255 }) return true
    return ':' in host && Regex("[0-9A-Fa-f:.]+").matches(host) && host.count { it == ':' } in 2..7
}
