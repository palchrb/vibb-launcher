package com.kidslauncher.mdm.ui.home

import com.kidslauncher.mdm.calls.CallPolicyState
import com.kidslauncher.mdm.calls.CallRules
import com.kidslauncher.mdm.calls.RuleContact
import com.kidslauncher.mdm.calls.phoneBookView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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
        assertEquals(GridTile.Settings, grid.last())
        assertEquals(
            listOf("camera" to 0, "Element" to 3, "nopkg" to 0, "vibb" to 0),
            grid.drop(1).dropLast(1).map { (it as GridTile.App).app.label to it.badge },
        )
    }

    @Test
    fun `no phone book tile when calls are unmanaged, settings always last`() {
        val grid = homeGrid(listOf(app("a")), showPhoneBook = false, badges = emptyMap())
        assertEquals(listOf(GridTile.App(app("a"), 0), GridTile.Settings), grid)
        assertEquals(listOf(GridTile.Settings), homeGrid(emptyList(), false, emptyMap()))
        assertEquals(listOf(GridTile.PhoneBook, GridTile.Settings), homeGrid(emptyList(), true, emptyMap()))
    }

    private val mamma = RuleContact(id = 1, name = "Mamma", number = "+4790000001", outbound = true, showOnHome = true)

    @Test
    fun `phone book tile whenever calls are managed or unknown`() {
        assertFalse(showPhoneBookTile(CallPolicyState.Unmanaged))
        assertTrue(showPhoneBookTile(CallPolicyState.UnknownFailClosed))
        // Calls off and no emergency contact: the phone book is empty but the tile stays
        // (the phone book says "calls off").
        val off = CallPolicyState.Managed(CallRules(callsEnabled = false, contacts = listOf(mamma)))
        assertTrue(phoneBookView(off) { false }.isEmpty)
        assertTrue(showPhoneBookTile(off))
        assertTrue(showPhoneBookTile(CallPolicyState.Managed(CallRules(callsEnabled = true))))
    }

    @Test
    fun `a home contact always comes with the phone book tile`() {
        val states = listOf(
            CallPolicyState.Unmanaged,
            CallPolicyState.UnknownFailClosed,
            CallPolicyState.Managed(CallRules(callsEnabled = true, contacts = listOf(mamma))),
            CallPolicyState.Managed(CallRules(callsEnabled = false, contacts = listOf(mamma))),
            CallPolicyState.Managed(CallRules(callsEnabled = false, contacts = listOf(mamma.copy(number = "112")))),
        )
        for (state in states) {
            for (emergency in listOf(true, false)) {
                val home = phoneBookView(state) { emergency }.home
                if (home.isNotEmpty()) assertTrue("$state", showPhoneBookTile(state))
            }
        }
    }

    @Test
    fun `grid metrics scale from the mockup`() {
        // The mockup: 288 dp content, 3 columns -> 60 dp icons, 13 sp labels.
        gridMetrics(288f, 3).let {
            assertEquals(60, it.iconDp)
            assertEquals(13f, it.labelSp, 0.001f)
            assertEquals(14, it.rowGapDp)
            assertEquals(8, it.columnGapDp)
            assertEquals(24, it.badgeDp)
            assertEquals((288f - 16) / 3, it.cellWidthDp, 0.001f)
        }
        // 4 columns at 288: cell 66 dp, icon still 60 (66 - 6).
        assertEquals(60, gridMetrics(288f, 4).iconDp)
        // Narrow: the 48 dp floor, but never wider than the cell minus 6.
        gridMetrics(240f, 3).let {
            assertEquals(50, it.iconDp)
            assertEquals(12f, it.labelSp, 0.001f)
        }
        assertEquals(48, gridMetrics(240f, 4).iconDp) // cell 54
        assertEquals(38, gridMetrics(200f, 4).iconDp) // cell 44: the cell wins over the floor
        // Wide: scaled, capped at 76 dp and 15 sp.
        gridMetrics(360f, 3).let {
            assertEquals(75, it.iconDp)
            assertEquals(15f, it.labelSp, 0.001f)
        }
        assertEquals(75, gridMetrics(360f, 4).iconDp) // cell 84
        assertEquals(76, gridMetrics(480f, 3).iconDp)
        // Every cell is at least a 48 dp touch target at the widths we support.
        for (w in listOf(240f, 288f, 360f)) for (c in 3..4) assertTrue(gridMetrics(w, c).cellWidthDp >= 48f)
    }

    @Test
    fun `contacts row`() {
        assertEquals(ContactRowLayout(76, 28, false), contactRow(1, 288f))
        assertEquals(ContactRowLayout(76, 28, false), contactRow(2, 288f))
        assertEquals(ContactRowLayout(76, 28, false), contactRow(3, 288f)) // 284 dp
        // 4 at 64 dp don't fit 288 dp (304): scroll, with a peek.
        contactRow(4, 288f).let {
            assertTrue(it.scrolls)
            assertEquals(64, it.avatarDp)
        }
        // 4 fit at 360 dp; 5 scroll with the 5th cut in half.
        assertEquals(ContactRowLayout(64, 16, false), contactRow(4, 360f))
        contactRow(5, 360f).let {
            assertTrue(it.scrolls)
            val whole = 4
            assertEquals(360f, whole * it.itemDp + 32f, it.gapDp.toFloat())
        }
        // 3 on a narrow screen fall back to 64 dp.
        assertEquals(ContactRowLayout(64, 16, false), contactRow(3, 240f))
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
    fun `emergency numbers are the tonal red with the number`() {
        val style = avatarStyle(RuleContact(id = 1, name = "Nødnummer", number = "112"), isEmergency = true)
        assertEquals("112", style.text)
        assertEquals(EMERGENCY_AVATAR, style.colors)
        // Design 34 P1/QA M3: the lock pill's tonal pair, #FF8A8A on #3A1E24 (6.6:1), whatever the id.
        assertEquals(AvatarColors(0xFF3A1E24, 0xFFFF8A8A), EMERGENCY_AVATAR)
        val ratio = contrastRatio(lum(EMERGENCY_AVATAR.ink), lum(EMERGENCY_AVATAR.background))
        assertTrue("112 ink: $ratio", ratio >= 6.6)
        for (id in listOf(0L, 3L, 7L, -1L)) {
            assertEquals(EMERGENCY_AVATAR, avatarStyle(RuleContact(id = id, name = "Mamma", number = "112"), true).colors)
        }
        // The sheet's ring is the red ink (its dark disc alone would vanish), a contact's its colour.
        assertEquals(0xFFFF8A8A, avatarRingColour(EMERGENCY_AVATAR, isEmergency = true))
        assertEquals(AVATAR_PALETTE[2].background, avatarRingColour(AVATAR_PALETTE[2], isEmergency = false))
    }

    @Test
    fun `contact avatars are soft discs with a dark initial`() {
        // Design 34 P3: six light pastels, one ink, the initial >= 8.9:1 on each (WCAG AA is 4.5).
        assertEquals(
            listOf(0xFFE8B49A, 0xFF9FC3E3, 0xFFE3CF9A, 0xFFA9CDB0, 0xFFC9B3DE, 0xFFE6AFB4),
            AVATAR_PALETTE.map { it.background },
        )
        val ground = lum(0xFF0C0C14)
        for (colors in AVATAR_PALETTE) {
            assertEquals(AVATAR_INK, colors.ink)
            val ink = contrastRatio(lum(colors.ink), lum(colors.background))
            assertTrue("ink on ${colors.background.toString(16)}: $ink", ink >= 8.9)
            // Light discs: never a dark field, clearly apart from the night ground.
            assertTrue(contrastRatio(lum(colors.background), ground) >= 10.0)
        }
        assertEquals(6, AVATAR_PALETTE.toSet().size)
    }

    @Test
    fun `a contact keeps its colour`() {
        val contacts = (-6L..12L).map { RuleContact(id = it, name = "N$it", number = "+479000000$it") }
        for (contact in contacts) {
            val colors = avatarStyle(contact, false).colors
            assertEquals(AVATAR_PALETTE[Math.floorMod(contact.id, AVATAR_PALETTE.size.toLong()).toInt()], colors)
            // Name, number and photo don't move it; only the id does.
            assertEquals(colors, avatarStyle(contact.copy(name = "Other", number = "+4791111111"), false).colors)
            assertTrue(colors in AVATAR_PALETTE)
        }
    }

    private fun lum(argb: Long): Double = relativeLuminance(argb.toInt())
}
