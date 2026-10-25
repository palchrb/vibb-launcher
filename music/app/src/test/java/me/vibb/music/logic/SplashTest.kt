package me.vibb.music.logic

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The launch splash stays until the first content frame, never past the cap (PLAN "Splash with the vibb logo"). */
class SplashTest {
    @Test
    fun `the splash stays until the content's first frame, at most the cap`() {
        assertTrue("no content yet", keepSplash(contentShown = false, sinceCreateMs = 900))
        assertFalse("the content composed", keepSplash(contentShown = true, sinceCreateMs = 900))
        assertTrue("just under the cap", keepSplash(contentShown = false, sinceCreateMs = SPLASH_CAP_MS - 1))
        assertFalse("a slow start never looks hung", keepSplash(contentShown = false, sinceCreateMs = SPLASH_CAP_MS))
    }
}
