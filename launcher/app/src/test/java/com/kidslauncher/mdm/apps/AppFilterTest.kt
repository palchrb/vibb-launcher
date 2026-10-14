package com.kidslauncher.mdm.apps

import com.kidslauncher.mdm.play.PLAY_STORE
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** What Home and the drawer may list (design 16d: the recents provider never, like Play outside install mode). */
class AppFilterTest {
    private val recents = "com.oem.launcher"
    private val dialer = "com.android.dialer"

    @Test
    fun `the recents provider is never listed - enforcement leaves it unhidden, so the list keeps it off`() {
        for (installMode in listOf(false, true)) {
            assertFalse(AppFilter.kidListable(recents, null, recents, installMode))
            assertFalse(AppFilter.kidListable(recents, dialer, recents, installMode))
        }
        assertTrue(AppFilter.kidListable("org.example.game", dialer, recents, false))
        // Unknown provider: nothing extra is left out.
        assertTrue(AppFilter.kidListable(recents, null, null, false))
    }

    @Test
    fun `the blocked dialer and Play keep their rules`() {
        assertFalse(AppFilter.kidListable(dialer, dialer, recents, false))
        assertFalse(AppFilter.kidListable(PLAY_STORE, null, recents, installMode = false))
        assertTrue(AppFilter.kidListable(PLAY_STORE, null, recents, installMode = true))
    }

    @Test
    fun `Vibb One itself is never listed, release or debug - every other app still is`() {
        // Design 34 P5: the drawer listed "Kids Launcher [debug]".
        for (own in listOf("me.vibb.launcher", "me.vibb.launcher.debug")) {
            for (installMode in listOf(false, true)) {
                assertFalse(own, AppFilter.kidListable(own, null, null, installMode))
                assertFalse(own, AppFilter.kidListable(own, dialer, recents, installMode))
            }
        }
        for (other in listOf("me.vibb.music", "me.vibb.music.debug", "org.example.game", "me.vibb.launcherx", "com.kidslauncher.mdm")) {
            assertTrue(other, AppFilter.kidListable(other, dialer, recents, false))
        }
        assertTrue(AppFilter.kidListable(null, dialer, recents, false))
    }

    @Test
    fun `the own ids are the build's application ids`() {
        val gradle = listOf("build.gradle.kts", "app/build.gradle.kts").map(::File).first { it.exists() }.readText()
        val id = Regex("""applicationId\s*=\s*"([^"]+)"""").find(gradle)!!.groupValues[1]
        val suffix = Regex("""applicationIdSuffix\s*=\s*"([^"]+)"""").find(gradle)!!.groupValues[1]
        assertEquals(setOf(id, id + suffix), AppFilter.OWN_PACKAGES)
    }

    @Test
    fun `Home and the drawer go through it with the resolved provider`() {
        val source = listOf("src/main", "app/src/main").map { File(it, "java/com/kidslauncher/mdm/apps/AppFilter.kt") }.first { it.exists() }.readText()
        assertTrue(source.contains("val recentsProvider = recentsPackage()"))
        assertTrue(source.contains("&& kidListable(packageName, blockedDialer, recentsProvider, installMode, emergencySos)"))
    }
}
