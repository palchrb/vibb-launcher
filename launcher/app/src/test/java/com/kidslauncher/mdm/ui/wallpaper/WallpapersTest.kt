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
    fun `59d K - as Android's face its lock wallpaper is the glance's own ground with its scrim`() {
        val photoGround = GlanceGround(hytta, 0x66000000)
        val skyGround = GlanceGround(sky, 0x1A000000)
        // The glance's ground (lockWallpaper's choice), whatever the opt-in: Home's photo too.
        assertEquals(hytta.fill, lockScreenFill(sky, androidFace = true, glance = photoGround))
        assertEquals(sky.fill, lockScreenFill(sky, androidFace = true, glance = skyGround))
        assertEquals(hytta.fill, lockScreenFill(sjo, androidFace = true, glance = photoGround))
        assertEquals(0x66000000, lockScreenScrim(androidFace = true, glance = photoGround))
        // The night mark only while no ground is known yet.
        assertEquals(WallpaperFill.NightMark, lockScreenFill(hytta, androidFace = true))
        assertEquals(0, lockScreenScrim(androidFace = true, glance = null))
        // Own-mode rows unchanged: design 08's rule, the opt-in deciding, no scrim - and the same keys.
        for (w in listOf(hytta, sjo, sky, NAVY)) {
            assertEquals(lockScreenFill(w), lockScreenFill(w, androidFace = false, glance = photoGround))
            assertEquals(wallpaperKey(w, 720, 1600), wallpaperKey(w, 720, 1600, androidFace = false, glance = photoGround))
        }
        assertEquals(0, lockScreenScrim(androidFace = false, glance = photoGround))
        assertEquals("night_mark", WallpaperFill.NightMark.describe())
    }

    /** Design 59d step K: Android's lock wallpaper is re-applied whenever the glance's ground changes, not only a hash. */
    @Test
    fun `59d K - the key follows the glance's ground, its scrim and the face, and the edges apply at once`() {
        val ground = GlanceGround(hytta, 0x66000000)
        val face = wallpaperKey(sky, 720, 1600, androidFace = true, glance = ground)
        assertTrue("the mode", face != wallpaperKey(sky, 720, 1600))
        assertTrue("another ground (the parent's lock wallpaper, the opt-in)", face != wallpaperKey(sky, 720, 1600, androidFace = true, glance = GlanceGround(sjo, 0x66000000)))
        assertTrue("another scrim", face != wallpaperKey(sky, 720, 1600, androidFace = true, glance = ground.copy(scrimArgb = 0x40000000)))
        assertTrue(face.contains("|lockscrim=66000000|"))
        // The photo on the lock still counts as ours for a revoked photo's reset.
        assertEquals(setOf(photo), imagesInKey(face))
        // The face's edges apply at once; every apply and the systemShows check pass the glance's ground.
        val androidLock = listOf("src/main/java/com/kidslauncher/mdm/lock/AndroidLock.kt", "app/src/main/java/com/kidslauncher/mdm/lock/AndroidLock.kt")
            .map { java.io.File(it) }.first { it.exists() }.readText()
        assertTrue(androidLock.substringAfter("fun face(").substringBefore("\n    }\n")
            .contains("if (face != was) com.kidslauncher.mdm.ui.wallpaper.WallpaperStore.faceChanged(app)"))
        val store = listOf("src/main/java/com/kidslauncher/mdm/ui/wallpaper/WallpaperStore.kt", "app/src/main/java/com/kidslauncher/mdm/ui/wallpaper/WallpaperStore.kt")
            .map { java.io.File(it) }.first { it.exists() }.readText()
        assertTrue(store.substringAfter("fun faceChanged(").substringBefore("\n    }\n").contains("applyAndRefresh(app)"))
        assertTrue(store.contains("WallpaperApplier.applyIfNeeded(app, state.current, allowedImages, glanceGround(state))"))
        assertTrue(store.contains("WallpaperApplier.systemShows(context, current, GlanceGround(lock, blackScrimArgb(lockScrimAlpha)))"))
        assertTrue(store.contains("lockScrimArgb = blackScrimArgb(lockScrimAlpha),"))
        val applier = listOf("src/main/java/com/kidslauncher/mdm/ui/wallpaper/WallpaperApplier.kt", "app/src/main/java/com/kidslauncher/mdm/ui/wallpaper/WallpaperApplier.kt")
            .map { java.io.File(it) }.first { it.exists() }.readText()
        assertTrue(applier.contains("return wallpaperKey(home, w, h, com.kidslauncher.mdm.lock.AndroidLock.faceOn, glance)"))
        val setBoth = applier.substringAfter("private fun setBoth(").substringBefore("\n    }\n")
        assertTrue(setBoth.contains("val lock = lockScreenFill(home, face, glance)"))
        assertTrue(setBoth.contains("if ((lockScrim ushr 24) != 0) Canvas(lockBitmap).drawColor(lockScrim)"))
        // R1-6: a lock photo that can't be decoded falls back to the night mark - Home's half is still set.
        val image = setBoth.substringAfter("is WallpaperFill.Image -> homeBitmap(context, lock, w, h) ?: run {").substringBefore("}")
        assertTrue(image, image.contains("render(context, WallpaperFill.NightMark, null, w, h)"))
        assertFalse(image, image.contains("return"))
        // The rc.2 run's R3: one fill on both is one set with both flags (the rollback) - SystemUI's ThemeOverlayController
        // labels such a set "home" (color_both 1); the face's lock-first, home-last pair keeps the palette on home's colours.
        assertTrue(setBoth.contains("if (lock == home.fill && lockScrim == 0) {\n                systemId = wm.setBitmap(homeBitmap, null, true, WallpaperManager.FLAG_SYSTEM or WallpaperManager.FLAG_LOCK)"))
        assertTrue(setBoth.indexOf("WallpaperManager.FLAG_LOCK) != 0") < setBoth.indexOf("WallpaperManager.FLAG_SYSTEM)\n"))
        // The glance's own background keeps the opt-in in both modes.
        assertEquals(sjo, lockWallpaper(emptyList(), null, sjo, emptySet()))
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
