package com.kidslauncher.mdm.apps

import com.kidslauncher.mdm.play.PLAY_STORE
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
    fun `Home and the drawer go through it with the resolved provider`() {
        val source = listOf("src/main", "app/src/main").map { File(it, "java/com/kidslauncher/mdm/apps/AppFilter.kt") }.first { it.exists() }.readText()
        assertTrue(source.contains("val recentsProvider = recentsPackage()"))
        assertTrue(source.contains("&& kidListable(packageName, blockedDialer, recentsProvider, installMode)"))
    }
}
