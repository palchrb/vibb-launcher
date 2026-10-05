package com.kidslauncher.mdm.ui.home

import com.kidslauncher.mdm.calls.RuleContact
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HomeModelTest {

    private fun app(label: String, pkg: String? = "org.$label") = GridApp("key-$label", label, pkg)

    @Test
    fun `columns are 3 unless the parent chose 4`() {
        assertEquals(3, gridColumns(null))
        assertEquals(3, gridColumns(3))
        assertEquals(4, gridColumns(4))
        assertEquals(3, gridColumns(5))
        assertEquals(3, gridColumns(0))
    }

    @Test
    fun `phone book first, apps by label, badges by package`() {
        val grid = homeGrid(
            listOf(app("vibb"), app("Element"), app("camera"), app("nopkg", null)),
            showPhoneBook = true,
            badges = mapOf("org.Element" to 3, "org.other" to 9),
        )
        assertEquals(GridTile.PhoneBook, grid[0])
        assertEquals(
            listOf("camera" to 0, "Element" to 3, "nopkg" to 0, "vibb" to 0),
            grid.drop(1).map { (it as GridTile.App).app.label to it.badge },
        )
    }

    @Test
    fun `no phone book tile when there is nothing to call`() {
        val grid = homeGrid(listOf(app("a")), showPhoneBook = false, badges = emptyMap())
        assertEquals(listOf(GridTile.App(app("a"), 0)), grid)
    }

    @Test
    fun `badge text`() {
        assertNull(badgeText(0))
        assertNull(badgeText(-1))
        assertEquals("1", badgeText(1))
        assertEquals("99", badgeText(99))
        assertEquals("99+", badgeText(100))
    }

    @Test
    fun `avatar initials and colours`() {
        val mamma = RuleContact(id = 7, name = " mamma", number = "+4790000001")
        val style = avatarStyle(mamma, isEmergency = false)
        assertEquals("M", style.text)
        assertEquals(AVATAR_PALETTE[7 % AVATAR_PALETTE.size], style.colors)
        // Same id, same colour on every sync.
        assertEquals(style, avatarStyle(mamma.copy(name = "Mamma"), false))
        // A whole code point, not half a surrogate pair.
        assertEquals("😀", avatarStyle(mamma.copy(name = "😀 Smile"), false).text)
        assertEquals("Ø", avatarStyle(mamma.copy(name = "øyvind"), false).text)
        assertEquals("01", avatarStyle(mamma.copy(name = ""), false).text)
        assertEquals("?", avatarStyle(mamma.copy(name = "", number = ""), false).text)
        assertEquals(AVATAR_PALETTE[3], avatarStyle(mamma.copy(id = -3), false).colors)
    }

    @Test
    fun `emergency numbers are red with the number`() {
        val style = avatarStyle(RuleContact(id = 1, name = "Nødnummer", number = "112"), isEmergency = true)
        assertEquals("112", style.text)
        assertEquals(EMERGENCY_AVATAR, style.colors)
    }
}
