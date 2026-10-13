package me.vibb.music.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

/** "Spill av på"'s keys (design 26a §3.3): the phone, a connected device, a Sonos room (step 3). */
class OutputKeyTest {
    @Test
    fun `the phone has its own key`() {
        assertEquals("phone", Output.PHONE.key)
        assertEquals(OutputKind.PHONE, Output.PHONE.kind)
        assertNull(Output.deviceId(Output.PHONE_KEY))
    }

    @Test
    fun `a device key carries its AudioDeviceInfo id and reads back`() {
        assertEquals("dev:7", Output.deviceKey(7))
        for (id in listOf(0, 7, 4711, Int.MAX_VALUE)) assertEquals(id, Output.deviceId(Output.deviceKey(id)))
        // A non-canonical key still names its device; the service stores deviceKey(id) (qa-26a-step2-code L6).
        for (key in listOf("dev:07", "dev:+7")) assertEquals("dev:7", Output.deviceId(key)?.let { Output.deviceKey(it) })
    }

    @Test
    fun `no other key names a device`() {
        for (key in listOf("", "dev:", "dev:x", "dev:7a", "dev: 7", "7", "-1", "phone", "sonos:RINCON_000E58A0123401400", "DEV:7")) {
            assertNull(key, Output.deviceId(key))
        }
    }

    @Test
    fun `the three key spaces never overlap`() {
        val spaces = listOf(Output.PHONE_KEY, Output.DEVICE_PREFIX, Output.SONOS_PREFIX)
        for (a in spaces) for (b in spaces) if (a != b) assertFalse("$a / $b", a.startsWith(b))
    }

    @Test
    fun `the kinds the sheet and the bar must know`() {
        // A new kind needs its glyph, name and subtitle in PlayerScreen.kt (exhaustive whens there).
        assertEquals(listOf(OutputKind.PHONE, OutputKind.BLUETOOTH, OutputKind.WIRED, OutputKind.SONOS), OutputKind.entries.toList())
        assertEquals(
            listOf(
                RendererState.GROUPED_AWAY, RendererState.TAKEN_OVER, RendererState.LOST_SESSION, RendererState.UNREACHABLE,
                // Spotify's web player (design 26c §5.8): the phone's, not a room's - `callStep` counts them as paused
                RendererState.SPOTIFY_NEEDS_WIFI, RendererState.SPOTIFY_NEEDS_INTERNET, RendererState.SPOTIFY_UNAVAILABLE,
                RendererState.SPOTIFY_ASK_ADULT, RendererState.SPOTIFY_NOT_ON_PHONE, RendererState.SPOTIFY_ELSEWHERE,
                RendererState.SPOTIFY_STOPPED,
            ),
            RendererState.entries.toList(),
        )
        assertEquals("remote: the four Sonos lines only", RendererState.entries.take(4), RendererState.entries.filter { it.remote })
        assertEquals(listOf(RendererKind.LOCAL, RendererKind.SPOTIFY), RendererKind.entries.filter { it.onPhone })
        assertNull(PlayerHub.State().rendererState)
    }
}
