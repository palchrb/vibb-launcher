package me.vibb.music.logic

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The launch splash stays until the first content frame, never past the cap (PLAN "Splash with the vibb logo"). */
class SplashTest {
    @Test
    fun `the splash stays until the content's first frame, at most the cap`() {
        // qa-music-r13-code Q-4: well below the 5 s input ANR of a window that never draws
        assertTrue("the cap is $SPLASH_CAP_MS ms", SPLASH_CAP_MS in 1_000L..4_000L)
        assertTrue("no content yet", keepSplash(contentShown = false, sinceCreateMs = 900))
        assertFalse("the content composed", keepSplash(contentShown = true, sinceCreateMs = 900))
        assertTrue("just under the cap", keepSplash(contentShown = false, sinceCreateMs = SPLASH_CAP_MS - 1))
        assertFalse("a slow start never looks hung", keepSplash(contentShown = false, sinceCreateMs = SPLASH_CAP_MS))
    }

    @Test
    fun `the first content frame waits for the stored row and the carousel's start tile (Q-10)`() {
        assertFalse("no snapshot", contentReady(snapshot = false, rowKnown = true, rowStored = false, startSettled = false, capped = true))
        assertFalse("the row not read yet", contentReady(snapshot = true, rowKnown = false, rowStored = false, startSettled = false, capped = false))
        assertTrue("no stored row: the library at tile 0 is right", contentReady(snapshot = true, rowKnown = true, rowStored = false, startSettled = false, capped = false))
        assertFalse("a stored row before the start tile", contentReady(snapshot = true, rowKnown = true, rowStored = true, startSettled = false, capped = false))
        assertTrue("on the last played tile", contentReady(snapshot = true, rowKnown = true, rowStored = true, startSettled = true, capped = false))
        assertTrue("the cap: whatever there is", contentReady(snapshot = true, rowKnown = false, rowStored = false, startSettled = false, capped = true))
    }
}
