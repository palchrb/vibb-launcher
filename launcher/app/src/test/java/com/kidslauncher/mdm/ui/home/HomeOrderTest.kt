package com.kidslauncher.mdm.ui.home

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HomeOrderTest {

    /** [activity]: the launcher activity in the app's key - not part of its place in the order (code QA L4). */
    private fun app(name: String, activity: String = "Main", user: Int = 0) =
        GridTile.App(GridApp("{\"package\":\"org.$name\",\"activity\":\"$activity\"}", name, "org.$name", user), 0)

    private fun ids(tiles: List<GridTile>) = tiles.map(::tileOrderId)

    private val grid = listOf(GridTile.PhoneBook, GridTile.Day, app("a"), app("b"), app("c"), GridTile.Settings)

    @Test
    fun `no order is Home's own, a stored one arranges and anything new goes last before Settings`() {
        assertEquals(grid, arrangedTiles(grid, emptyList()))
        // A duplicate keeps its first place; Settings stays last whatever is stored.
        val order = listOf("app:org.c:0", "gone", "phone_book", "app:org.a:0", "settings", "app:org.c:0")
        assertEquals(
            listOf("app:org.c:0", "phone_book", "app:org.a:0", "day", "app:org.b:0", "settings"),
            ids(arrangedTiles(grid, order)),
        )
        // An app is placed by its package and profile: a renamed launcher activity keeps the place, another profile not.
        assertEquals("app:org.c:0", tileOrderId(app("c", activity = "Renamed")))
        assertEquals("app:org.c:10", tileOrderId(app("c", user = 10)))
    }

    @Test
    fun `a tile not shown keeps its place after the one it followed and comes back there`() {
        val order = listOf("phone_book", "app:org.b:0", "day", "app:org.a:0", "app:org.c:0")
        // A rule took b and Dagen away; the kid moves c first.
        val stored = storedOrder(order, listOf("app:org.c:0", "phone_book", "app:org.a:0", "settings"))
        assertEquals(listOf("app:org.c:0", "phone_book", "app:org.b:0", "day", "app:org.a:0"), stored)
        // Both back: in their places.
        assertEquals(
            listOf("app:org.c:0", "phone_book", "app:org.b:0", "day", "app:org.a:0", "settings"),
            ids(arrangedTiles(grid, stored)),
        )
        // One hidden before every shown tile stays first.
        assertEquals(listOf("x", "app:org.a:0"), storedOrder(listOf("x", "app:org.a:0"), listOf("app:org.a:0")))
    }

    @Test
    fun `steps stop at the edges and Settings never moves or is passed`() {
        assertNull(stepTarget(grid, 0, -1))
        assertEquals(1, stepTarget(grid, 0, 1))
        assertNull(stepTarget(grid, 4, 1))
        assertNull(stepTarget(grid, 5, -1))
        assertNull(stepTarget(grid, -1, 1))
        assertEquals(MoveBar(left = true, right = false, place = 5, count = 5), moveBar(grid, 4))
        assertEquals(listOf("app:org.a:0", "phone_book", "day"), ids(movedItem(grid, 2, 0)).take(3))
    }

    @Test
    fun `the stored text reads back, and unreadable text is no order`() {
        val order = listOf("app:org.x:0", "day")
        assertEquals(order, decodeOrder(encodeOrder(order)))
        assertEquals(emptyList<String>(), decodeOrder(null))
        assertEquals(emptyList<String>(), decodeOrder("not json"))
    }
}
