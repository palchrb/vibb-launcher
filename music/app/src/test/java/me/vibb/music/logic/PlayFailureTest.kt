package me.vibb.music.logic

import androidx.media3.common.ParserException
import androidx.media3.common.PlaybackException
import androidx.media3.datasource.HttpDataSource
import me.vibb.music.media.MediaCache
import me.vibb.music.playback.PlayerHub.Message
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/** A failed item's words and log line (PLAN "Round M1", the incident's diagnostics (1) and (2)). */
class PlayFailureTest {
    /** Media3's HTTP exceptions need a real android.net.Uri (ReportTest's note): allocated without a constructor. */
    private fun status403(): HttpDataSource.InvalidResponseCodeException {
        val unsafe = Class.forName("sun.misc.Unsafe").getDeclaredField("theUnsafe").apply { isAccessible = true }.get(null)
        val e = unsafe.javaClass.getMethod("allocateInstance", Class::class.java)
            .invoke(unsafe, HttpDataSource.InvalidResponseCodeException::class.java) as HttpDataSource.InvalidResponseCodeException
        HttpDataSource.InvalidResponseCodeException::class.java.getField("responseCode").apply { isAccessible = true }.setInt(e, 403)
        return e
    }

    private fun words(error: Throwable, offline: Boolean = false) = failureMessage(offline, noNetwork(error))

    @Test
    fun `a name that can't be looked up is the no-network words, through Media3's wrapping`() {
        val dns = UnknownHostException("Unable to resolve host \"feed.example\": No address associated with hostname")
        val stream = PlaybackException("Source error", IOException(dns), PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED)
        assertEquals(Message.NO_NETWORK, words(stream))
        assertEquals("the streaming gate's words first", Message.NOT_DOWNLOADED, words(stream, offline = true))
    }

    @Test
    fun `an HTTP 403, a decoder error and a server that is down keep today's words`() {
        assertEquals(Message.CANT_PLAY, words(PlaybackException("x", status403(), PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS)))
        assertEquals(Message.CANT_PLAY, words(PlaybackException("x", IllegalStateException("codec"), PlaybackException.ERROR_CODE_DECODING_FAILED)))
        assertEquals(Message.CANT_PLAY, words(PlaybackException("x", ParserException.createForMalformedContainer("not audio", null), PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED)))
        assertFalse("refused: the server, not the network", noNetwork(ConnectException("Failed to connect")))
        assertFalse("slow: the server or the network", noNetwork(SocketTimeoutException("timeout")))
        // Q-2: the guard's refusal of a public entry on a private address is about the item, not the network
        val nas = PlaybackException("x", IOException(MediaCache.PrivateAddressException("nas.local")), PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED)
        assertEquals(Message.CANT_PLAY, words(nas))
    }

    @Test
    fun `a cause cycle ends the walk (Q-10)`() {
        val a = IOException("a")
        val b = IOException("b")
        a.initCause(b)
        b.initCause(a)
        assertFalse(noNetwork(a))
        assertEquals(8, failureLine("X", 0, a).split(" <- ").size)
    }

    @Test
    fun `the log line names the code and every cause, never a URL's query`() {
        val dns = UnknownHostException("Unable to resolve host \"cdn.example\"")
        val minted = IOException("fetch https://user:pw@cdn.example/book/1.mp3?token=SECRET failed", dns)
        val line = failureLine("ERROR_CODE_IO_NETWORK_CONNECTION_FAILED", 2001, PlaybackException("Source error", minted, 2001))
        assertTrue(line, line.startsWith("Playback failed: ERROR_CODE_IO_NETWORK_CONNECTION_FAILED (2001): androidx.media3.common.PlaybackException: Source error <- java.io.IOException: "))
        assertTrue(line, line.endsWith(" <- java.net.UnknownHostException: Unable to resolve host \"cdn.example\""))
        assertTrue(line, line.contains("https://cdn.example/book/1.mp3 failed"))
        assertFalse(line, line.contains("SECRET") || line.contains("pw@"))
    }
}
