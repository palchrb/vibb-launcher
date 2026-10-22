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
    fun `59b - as Android's face the lock wallpaper is the night ground with the mark, the photo opt-in kept`() {
        assertEquals(WallpaperFill.NightMark, lockScreenFill(hytta, androidFace = true))
        assertEquals(WallpaperFill.NightMark, lockScreenFill(sky, androidFace = true))
        assertEquals(WallpaperFill.NightMark, lockScreenFill(NAVY, androidFace = true))
        assertEquals("the parent's lock-photo opt-in wins", sjo.fill, lockScreenFill(sjo, androidFace = true))
        // Not the face: design 08's rule (the default).
        for (w in listOf(hytta, sjo, sky, NAVY)) assertEquals(lockScreenFill(w), lockScreenFill(w, androidFace = false))
        // The key carries the lock fill: the face's edges and a rollback re-apply by themselves.
        assertEquals("night_mark", WallpaperFill.NightMark.describe())
        assertTrue(wallpaperKey(sky, 720, 1600, androidFace = true) != wallpaperKey(sky, 720, 1600))
        assertEquals(wallpaperKey(sjo, 720, 1600), wallpaperKey(sjo, 720, 1600, androidFace = true))
        val applier = listOf("src/main/java/com/kidslauncher/mdm/ui/wallpaper/WallpaperApplier.kt", "app/src/main/java/com/kidslauncher/mdm/ui/wallpaper/WallpaperApplier.kt")
            .map { java.io.File(it) }.first { it.exists() }.readText()
        assertTrue(applier.contains("return wallpaperKey(home, w, h, com.kidslauncher.mdm.lock.AndroidLock.faceOn)"))
        assertTrue(applier.contains("val lock = lockScreenFill(home, com.kidslauncher.mdm.lock.AndroidLock.faceOn)"))
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

    /** Design 25, "Lock background": Home's by default, the parent's choice when it can be shown. */
    @Test
    fun `the lock shows Home's wallpaper unless the parent chose one the phone can show`() {
        val cached = setOf(photo)
        assertEquals(hytta, lockWallpaper(allowed, lockId = null, home = hytta, cachedHashes = cached))
        assertEquals(sky, lockWallpaper(allowed, lockId = 5, home = hytta, cachedHashes = cached))
        assertEquals("a photo with no 'also on the lock screen' tick is still Home's", hytta,
            lockWallpaper(allowed, null, hytta, cached))
        assertEquals("not downloaded yet: Home's", navy, lockWallpaper(allowed, 8, navy, cached))
        assertEquals(sjo, lockWallpaper(allowed, 8, navy, cached + other))
        assertEquals("unticked (not in the list): Home's", sky, lockWallpaper(allowed, 99, sky, cached))
        assertEquals(navy, lockWallpaper(emptyList(), 5, navy, cached))
    }

    /** qa-25b-25f: a separate lock photo that can't be decoded falls back to Home's, scrim included. */
    @Test
    fun `an unreadable lock photo falls back to Home's`() {
        assertEquals(sky, lockAfterDecode(sjo, decoded = false, home = sky))
        assertEquals(sjo, lockAfterDecode(sjo, decoded = true, home = sky))
        assertEquals("colours need no decode", navy, lockAfterDecode(navy, decoded = false, home = sky))
        val store = listOf("src/main", "app/src/main").map { java.io.File(it, "java/com/kidslauncher/mdm/ui/wallpaper/WallpaperStore.kt") }.first { it.exists() }.readText()
        // The scrim is worked out for the wallpaper actually shown (after the fallback).
        assertTrue(store.indexOf("lock = lockAfterDecode(lock, decoded = lockBitmap != null, home = current)") in 0 until store.indexOf("val lockScrimAlpha = when (val f = lock.fill)"))
    }
}
