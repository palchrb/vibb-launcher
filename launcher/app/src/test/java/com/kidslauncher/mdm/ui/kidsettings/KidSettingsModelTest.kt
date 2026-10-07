package com.kidslauncher.mdm.ui.kidsettings

import com.kidslauncher.mdm.server.CachedPolicy
import com.kidslauncher.mdm.server.dto.PolicyResponse
import com.kidslauncher.mdm.ui.wallpaper.NAVY
import com.kidslauncher.mdm.ui.wallpaper.Wallpaper
import com.kidslauncher.mdm.ui.wallpaper.WallpaperFill
import org.junit.Assert.assertEquals
import org.junit.Test

class KidSettingsModelTest {

    private fun ok(mask: Long) = CachedPolicy.Ok(PolicyResponse(allowlist = emptyList(), quickControlsMask = mask))

    @Test
    fun `switches follow the mask of an accepted policy`() {
        assertEquals(ControlsSection.Rows(wifi = true, bluetooth = true, brightness = true, sound = true), controlsSection(true, ok(15)))
        assertEquals(ControlsSection.Rows(wifi = true, bluetooth = true, brightness = true, sound = false), controlsSection(true, ok(7)))
        assertEquals(ControlsSection.Rows(wifi = true, bluetooth = false, brightness = false, sound = false), controlsSection(true, ok(1)))
        assertEquals(ControlsSection.Rows(wifi = false, bluetooth = true, brightness = false, sound = false), controlsSection(true, ok(2)))
        assertEquals(ControlsSection.Rows(wifi = false, bluetooth = false, brightness = true, sound = false), controlsSection(true, ok(4)))
        // The sound bit (design 18) alone is a card, not NoneEnabled (QA #6).
        assertEquals(ControlsSection.Rows(wifi = false, bluetooth = false, brightness = false, sound = true), controlsSection(true, ok(8)))
        // Unknown bits alone are nothing.
        assertEquals(ControlsSection.NoneEnabled, controlsSection(true, ok(16)))
        assertEquals(ControlsSection.NoneEnabled, controlsSection(true, ok(0)))
    }

    @Test
    fun `every reason for no switches is its own case`() {
        // Not the device owner wins over everything (also a second build beside the real one).
        assertEquals(ControlsSection.NotOwner, controlsSection(false, ok(7)))
        assertEquals(ControlsSection.NotOwner, controlsSection(false, CachedPolicy.Absent))
        // No accepted policy yet (or only the last enforced plan, which has no mask).
        assertEquals(ControlsSection.NoPolicyYet, controlsSection(true, CachedPolicy.Absent))
        // Corrupt cache: fail closed.
        assertEquals(ControlsSection.Unreadable, controlsSection(true, CachedPolicy.Corrupt("x")))
        // Each case says what it is in the log.
        val all = listOf(
            ControlsSection.NotOwner, ControlsSection.NoPolicyYet, ControlsSection.Unreadable,
            ControlsSection.NoneEnabled, ControlsSection.Rows(true, false, true, false),
        )
        assertEquals(all.size, all.map { it.describe() }.toSet().size)
    }

    @Test
    fun `picker shows the choices with the shown one selected, nothing when there is no choice`() {
        val forest = Wallpaper(2, WallpaperFill.Solid(0xFF1E4D3A.toInt()), "Forest", "forest")
        val tiles = wallpaperTiles(listOf(NAVY, forest), forest)
        assertEquals(listOf(WallpaperTile(NAVY, false), WallpaperTile(forest, true)), tiles)
        assertEquals(emptyList<WallpaperTile>(), wallpaperTiles(listOf(NAVY), NAVY))
        assertEquals(emptyList<WallpaperTile>(), wallpaperTiles(emptyList(), NAVY))
        val model = kidSettingsModel(true, ok(1), listOf(NAVY, forest), NAVY)
        assertEquals(ControlsSection.Rows(wifi = true, bluetooth = false, brightness = false, sound = false), model.controls)
        assertEquals(2, model.wallpapers.size)
    }
}
