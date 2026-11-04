package me.vibb.music.playback

import me.vibb.music.Fixtures.moduleFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 2026-11-04: the playback notification is `VISIBILITY_SECRET` - the one Media3 builds and every one it hands back later
 * (the artwork's) - so a secure lock screen shows none of it. The service uses only the wrapped provider. Source scans,
 * as KidPlayerTest reads the service (the notification itself needs a device).
 */
class SecretMediaNotificationTest {
    private fun code(path: String) = moduleFile(path).readText()
        .replace(Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL), "")
        .replace(Regex("//[^\\n]*"), "")

    @Test
    fun `the returned and the changed notification are secret, with no public version`() {
        val provider = code("src/main/java/me/vibb/music/playback/SecretMediaNotification.kt")
        assertTrue(provider.contains("media.notification.visibility = Notification.VISIBILITY_SECRET"))
        assertTrue(provider.contains("media.notification.publicVersion = null"))
        assertTrue(provider.contains("): MediaNotification = secret(\n        inner.createNotification("))
        assertTrue(provider.contains("onNotificationChangedCallback.onNotificationChanged(secret(changed))"))
        assertTrue(provider.contains("inner.handleCustomCommand(session, action, extras)"))
    }

    @Test
    fun `the service sets only the wrapped provider`() {
        val service = code("src/main/java/me/vibb/music/playback/PlaybackService.kt")
        val set = Regex("""setMediaNotificationProvider\((\w+)\(""").findAll(service).map { it.groupValues[1] }.toList()
        assertEquals(listOf("SecretMediaNotification"), set)
    }
}
