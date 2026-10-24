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
        assertEquals(ControlsSection.Rows(wifi = true, bluetooth = true, brightness = true, sound = true, touch = true), controlsSection(true, ok(31)))
        assertEquals(ControlsSection.Rows(wifi = true, bluetooth = true, brightness = true, sound = true, touch = false), controlsSection(true, ok(15)))
        assertEquals(ControlsSection.Rows(wifi = true, bluetooth = true, brightness = true, sound = false, touch = false), controlsSection(true, ok(7)))
        assertEquals(ControlsSection.Rows(wifi = true, bluetooth = false, brightness = false, sound = false, touch = false), controlsSection(true, ok(1)))
        assertEquals(ControlsSection.Rows(wifi = false, bluetooth = true, brightness = false, sound = false, touch = false), controlsSection(true, ok(2)))
        assertEquals(ControlsSection.Rows(wifi = false, bluetooth = false, brightness = true, sound = false, touch = false), controlsSection(true, ok(4)))
        // The sound bit (design 18) alone is a card, not NoneEnabled (QA #6); so is the touch bit (design r9 §4).
        assertEquals(ControlsSection.Rows(wifi = false, bluetooth = false, brightness = false, sound = true, touch = false), controlsSection(true, ok(8)))
        assertEquals(ControlsSection.Rows(wifi = false, bluetooth = false, brightness = false, sound = false, touch = true), controlsSection(true, ok(16)))
        // Unknown bits alone are nothing.
        assertEquals(ControlsSection.NoneEnabled, controlsSection(true, ok(32)))
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
            ControlsSection.NoneEnabled, ControlsSection.Rows(true, false, true, false, true),
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
        assertEquals(ControlsSection.Rows(wifi = true, bluetooth = false, brightness = false, sound = false, touch = false), model.controls)
        assertEquals(2, model.wallpapers.size)
    }

    /** Design 64 step 2 (the user's decision 6): the calm line while the phone shares its location. */
    @Test
    fun `the location line shows while location isn't off`() {
        fun located(mode: String?) = CachedPolicy.Ok(
            PolicyResponse(allowlist = emptyList(), locationPolicy = mode?.let { com.kidslauncher.mdm.server.dto.LocationPolicy(it, 30) }),
        )
        assertEquals(true, sharesLocation(true, located("interval")))
        assertEquals(true, sharesLocation(true, located("on_request")))
        // An older server's policy without location_policy: the old every-10-minutes trail.
        assertEquals(true, sharesLocation(true, located(null)))
        assertEquals(false, sharesLocation(true, located("off")))
        assertEquals(false, sharesLocation(false, located("interval")))
        assertEquals(false, sharesLocation(true, CachedPolicy.Absent))
        assertEquals(false, sharesLocation(true, CachedPolicy.Corrupt("x")))
        assertEquals(true, kidSettingsModel(true, located("interval"), listOf(NAVY), NAVY).sharesLocation)
        // The words, calm and the same in both languages' structure; no control on the line.
        val layout = java.io.File("src/main/res/layout/activity_kid_settings.xml").readText()
        val line = Regex("""<TextView\s+android:id="@\+id/kid_settings_location_line"[^>]*/>""").find(layout)!!.value
        assertEquals(false, line.contains("clickable") || line.contains("onClick"))
        val en = java.io.File("src/main/res/values/strings.xml").readText()
        val nb = java.io.File("src/main/res/values-nb/strings.xml").readText()
        assertEquals(true, en.contains(">This phone shares where it is with mum and dad.</string>"))
        assertEquals(true, nb.contains(">Telefonen deler hvor den er med mamma og pappa.</string>"))
    }
}
