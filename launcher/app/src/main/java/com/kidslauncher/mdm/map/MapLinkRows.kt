package com.kidslauncher.mdm.map

/** One row of the PIN-gated Settings' "Kartlenker" group (§7.4): shown, and done. */
data class MapLinkRow(val shown: Boolean, val done: Boolean = false)

/** The group's three rows (§6.2, §6.3). */
data class MapLinkRows(val holder: MapLinkRow, val vibb: MapLinkRow, val back: MapLinkRow) {
    val shown: Boolean get() = holder.shown || vibb.shown || back.shown
}

/**
 * Which rows show: while the switch is [on], step 1 (the short-link holder's links off; done when they read off) and
 * step 2 (Vibb One's links ticked; done when every selectable host is); while it is off, only the row that gives the
 * holder its links back, and only while they read off (Q4). [holderAllowed] null = unreadable.
 */
fun mapLinkRows(on: Boolean, holderAllowed: Boolean?, hosts: VibbHosts): MapLinkRows = if (on) {
    MapLinkRows(
        holder = MapLinkRow(shown = true, done = holderAllowed == false),
        vibb = MapLinkRow(shown = true, done = hosts.declared > 0 && hosts.selected >= hosts.selectable),
        back = MapLinkRow(shown = false),
    )
} else {
    MapLinkRows(MapLinkRow(shown = false), MapLinkRow(shown = false), MapLinkRow(shown = holderAllowed == false))
}
