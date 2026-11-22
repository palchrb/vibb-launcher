package com.kidslauncher.mdm.ui.home

import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

/*
 * The kid's own order of Home's tiles (the user, 2026-11-20: "burde ikke press og hold på home screen gi barnet mulighet
 * til å flytte på de ulike ikonene?") - pure, tested in HomeOrderTest. The order is a list of [tileOrderId]s kept on
 * the phone only (`apps.home_order`, beside the kid's own names); HomeGridAdapter arranges, moves and stores.
 * Every tile moves but Innstillinger, which stays last and is never stored.
 */

/**
 * A tile's id in the stored order: an app by its package and profile (`app:<package>:<user>` - an update that renames
 * its launcher activity keeps its place, code QA L4), the others by [gridTileId]. Two launcher activities of one app
 * share it and stay side by side.
 */
fun tileOrderId(tile: GridTile): String =
    (tile as? GridTile.App)?.app?.let { app -> app.packageName?.let { "app:$it:${app.user}" } } ?: gridTileId(tile)

/**
 * [tiles] (in [homeGrid]'s order) arranged by the kid's [order]: the tiles it names in its order, then the ones it
 * doesn't name - a new app, or every tile before the kid's first move - in [homeGrid]'s order, then Settings.
 */
fun arrangedTiles(tiles: List<GridTile>, order: List<String>): List<GridTile> {
    val rank = HashMap<String, Int>()
    order.forEachIndexed { index, id -> rank.putIfAbsent(id, index) }
    val (settings, movable) = tiles.partition { it is GridTile.Settings }
    return movable.sortedBy { rank[tileOrderId(it)] ?: Int.MAX_VALUE } + settings
}

/**
 * The order to store once the kid arranged Home as [shown] (tile ids, Settings' dropped): [shown], with every id of the
 * old [order] that isn't shown now - an app a rule suspends or the parent takes off, one the kid hid, Dagen without a
 * calendar - put back right after the id it followed in [order] (first if none), so it comes back to its own place.
 * Nothing is ever dropped: an uninstalled app's id stays, a few bytes each.
 */
fun storedOrder(order: List<String>, shown: List<String>): List<String> {
    val visible = shown.filter { it != SETTINGS_TILE_ID }.distinct()
    val visibleSet = visible.toSet()
    val following = HashMap<String?, MutableList<String>>()
    var anchor: String? = null
    for (id in order.distinct()) {
        when {
            id == SETTINGS_TILE_ID -> {}
            id in visibleSet -> anchor = id
            else -> following.getOrPut(anchor) { mutableListOf() } += id
        }
    }
    return buildList {
        following[null]?.let(::addAll)
        for (id in visible) {
            add(id)
            following[id]?.let(::addAll)
        }
    }
}

/** Where one step ([step] -1 = left, +1 = right) takes the tile at [from]: `null` at an edge; Settings never moves and
 * nothing passes it. */
fun stepTarget(tiles: List<GridTile>, from: Int, step: Int): Int? {
    if (tiles.getOrNull(from).let { it == null || it is GridTile.Settings }) return null
    val to = from + step
    return to.takeIf { tiles.getOrNull(it).let { tile -> tile != null && tile !is GridTile.Settings } }
}

/** [list] with the item at [from] taken out and put at [to]. */
fun <T> movedItem(list: List<T>, from: Int, to: Int): List<T> =
    list.toMutableList().apply { add(to, removeAt(from)) }

/** The move bar while the tile at [index] moves: whether ◀ and ▶ step, and its [place] (1-based) among the [count]
 * tiles that move, for TalkBack. */
data class MoveBar(val left: Boolean, val right: Boolean, val place: Int, val count: Int)

fun moveBar(tiles: List<GridTile>, index: Int) = MoveBar(
    left = stepTarget(tiles, index, -1) != null,
    right = stepTarget(tiles, index, 1) != null,
    place = index + 1,
    count = tiles.count { it !is GridTile.Settings },
)

/** The stored order, a JSON list of ids; nothing or anything unreadable is no order (Home's own; no migration). */
fun decodeOrder(text: String?): List<String> =
    text?.let { runCatching { Json.decodeFromString(ORDER_SERIALIZER, it) }.getOrNull() }.orEmpty()

fun encodeOrder(order: List<String>): String = Json.encodeToString(ORDER_SERIALIZER, order)

private val ORDER_SERIALIZER = ListSerializer(String.serializer())

/** Settings' tile id ([gridTileId]): never moved, never stored. */
const val SETTINGS_TILE_ID = "settings"
