package com.kidslauncher.mdm.ui.home

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HomeOrderTest {

    private fun app(name: String) = GridTile.App(GridApp(name, name, "org.$name"), 0)

    private fun ids(tiles: List<GridTile>) = tiles.map(::gridTileId)

    private val grid = listOf(GridTile.PhoneBook, GridTile.Day, app("a"), app("b"), app("c"), GridTile.Settings)

    @Test
    fun `no order is Home's own, a stored one arranges and anything new goes last before Settings`() {
        assertEquals(grid, arrangedTiles(grid, emptyList()))
        val order = listOf("app:c", "gone", "phone_book", "app:a", "settings")
        assertEquals(
            listOf("app:c", "phone_book", "app:a", "day", "app:b", "settings"),
            ids(arrangedTiles(grid, order)),
        )
    }

    @Test
    fun `a tile not shown keeps its place after the one it followed and comes back there`() {
        val order = listOf("phone_book", "app:b", "day", "app:a", "app:c")
        // A rule took b and Dagen away; the kid moves c first.
        val stored = storedOrder(order, listOf("app:c", "phone_book", "app:a", "settings"))
        assertEquals(listOf("app:c", "phone_book", "app:b", "day", "app:a"), stored)
        // Both back: in their places.
        assertEquals(listOf("app:c", "phone_book", "app:b", "day", "app:a", "settings"), ids(arrangedTiles(grid, stored)))
        // One hidden before every shown tile stays first.
        assertEquals(listOf("x", "app:a"), storedOrder(listOf("x", "app:a"), listOf("app:a")))
    }

    @Test
    fun `steps stop at the edges and Settings never moves or is passed`() {
        assertNull(stepTarget(grid, 0, -1))
        assertEquals(1, stepTarget(grid, 0, 1))
        assertNull(stepTarget(grid, 4, 1))
        assertNull(stepTarget(grid, 5, -1))
        assertNull(stepTarget(grid, -1, 1))
        assertEquals(MoveBar(left = true, right = false, place = 5, count = 5), moveBar(grid, 4))
        assertEquals(listOf("app:a", "phone_book", "day"), ids(movedItem(grid, 2, 0)).take(3))
    }

    @Test
    fun `the stored text reads back, and unreadable text is no order`() {
        val order = listOf("app:{\"package\":\"x\",\"user\":0}", "day")
        assertEquals(order, decodeOrder(encodeOrder(order)))
        assertEquals(emptyList<String>(), decodeOrder(null))
        assertEquals(emptyList<String>(), decodeOrder("not json"))
    }
}
