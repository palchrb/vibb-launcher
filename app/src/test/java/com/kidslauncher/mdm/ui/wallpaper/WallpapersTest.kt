package com.kidslauncher.mdm.ui.wallpaper

import com.kidslauncher.mdm.calls.PhotoCachePlan
import com.kidslauncher.mdm.server.dto.PolicyWallpaper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WallpapersTest {

    private val photo = "ab".repeat(32)
    private val other = "cd".repeat(32)

    private val server = listOf(
        PolicyWallpaper(1, "color", listOf("#14213D"), null, "Navy", "navy"),
        PolicyWallpaper(5, "gradient", listOf("#1C7ED6", "#14213D"), null, "Sky", "sky"),
        PolicyWallpaper(7, "image", emptyList(), photo, "Hytta", null, lockScreen = false),
        PolicyWallpaper(8, "image", emptyList(), other, "Sjø", null, lockScreen = true),
    )
    private val allowed = parseWallpapers(server)
    private val navy = allowed[0]
    private val sky = allowed[1]
    private val hytta = allowed[2]
    private val sjo = allowed[3]

    @Test
    fun `server list is parsed and checked`() {
        assertEquals(WallpaperFill.Solid(0xFF14213D.toInt()), navy.fill)
        assertEquals(WallpaperFill.Gradient(0xFF1C7ED6.toInt(), 0xFF14213D.toInt()), sky.fill)
        assertEquals(WallpaperFill.Image(photo), hytta.fill)
        assertEquals(4, allowed.size)
        val bad = listOf(
            PolicyWallpaper(2, "color", listOf("#14213D", "#000000")),
            PolicyWallpaper(3, "color", listOf("14213D")),
            PolicyWallpaper(3, "color", listOf("#+14213")),
            PolicyWallpaper(4, "gradient", listOf("#1C7ED6")),
            PolicyWallpaper(6, "image", emptyList(), null),
            PolicyWallpaper(6, "image", emptyList(), "../../etc"),
            PolicyWallpaper(9, "plaid", listOf("#000000")),
        )
        assertEquals(emptyList<Wallpaper>(), parseWallpapers(bad))
        // A colour never claims the lock-screen flag.
        assertFalse(parseWallpapers(listOf(PolicyWallpaper(1, "color", listOf("#14213D"), lockScreen = true)))[0].lockScreen)
        assertNull(parseHexColour("#14213"))
        assertEquals(0xFFF4F1EA.toInt(), parseHexColour("#f4f1ea"))
    }

    @Test
    fun `the pick wins while allowed and usable, else the first usable, else navy`() {
        val cached = setOf(photo)
        assertEquals(hytta, effectiveWallpaper(allowed, 7, cached))
        assertEquals(sky, effectiveWallpaper(allowed, 5, cached))
        // Not downloaded yet: the first usable one.
        assertEquals(navy, effectiveWallpaper(allowed, 8, cached))
        // The parent took the photo away: replaced at once.
        assertEquals(navy, effectiveWallpaper(allowed - hytta, 7, cached))
        assertEquals(sky, effectiveWallpaper(listOf(hytta, sky), 7, emptySet()))
        // Nothing picked yet: the parent's first.
        assertEquals(navy, effectiveWallpaper(allowed, null, cached))
        // An older server (no list) or only uncached photos: navy.
        assertEquals(NAVY, effectiveWallpaper(emptyList(), 7, cached))
        assertEquals(NAVY, effectiveWallpaper(listOf(sjo), 8, emptySet()))
    }

    @Test
    fun `photos go on the lock screen only when the parent said so`() {
        assertEquals(NAVY.fill, lockScreenFill(hytta))
        assertEquals(sjo.fill, lockScreenFill(sjo))
        assertEquals(sky.fill, lockScreenFill(sky))
    }

    @Test
    fun `cache keeps only allowed photos`() {
        val files = setOf("$photo.jpg", "$other.jpg", "${"ef".repeat(32)}.jpg", ".x.tmp")
        val plan = wallpaperCachePlan(listOf(navy, hytta), files, emptySet())
        assertEquals(PhotoCachePlan(download = emptySet(), delete = setOf("$other.jpg", "${"ef".repeat(32)}.jpg", ".x.tmp")), plan)
        assertEquals(setOf(other), wallpaperCachePlan(allowed, setOf("$photo.jpg"), emptySet()).download)
        // A 404 isn't asked for again.
        assertEquals(emptySet<String>(), wallpaperCachePlan(allowed, setOf("$photo.jpg"), setOf(other)).download)
        // Nothing allowed (e.g. no readable policy): every photo goes.
        assertEquals(setOf("$photo.jpg"), wallpaperCachePlan(emptyList(), setOf("$photo.jpg"), emptySet()).delete)
    }

    @Test
    fun `decoding is bounded and sampled for the screen`() {
        assertTrue(wallpaperBoundsOk(1080, 2400))
        assertFalse(wallpaperBoundsOk(1081, 2400))
        assertFalse(wallpaperBoundsOk(1080, 2401))
        assertFalse(wallpaperBoundsOk(0, 10))
        assertEquals(1, wallpaperSampleSize(1080, 2400, 1080, 2400))
        assertEquals(2, wallpaperSampleSize(1080, 2400, 480, 1066))
        assertEquals(1, wallpaperSampleSize(1080, 2400, 720, 1600))
        assertEquals(16, wallpaperSampleSize(1080, 2400, 64, 64))
    }
}
