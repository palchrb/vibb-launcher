package me.vibb.music.media

import androidx.media3.common.C
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.cache.Cache
import androidx.media3.exoplayer.source.LoadEventInfo
import androidx.media3.exoplayer.source.MediaLoadData
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException
import java.lang.reflect.Proxy

/**
 * The glue of design 30 §1.4 and §1.6 around Media3: the [ThroughSink] (decides at every open, makes
 * the row before its first write, a failure is a decline) and the [LengthGuard] (another total length
 * at a resume is a synthetic 416). The rules themselves are OfflineTest's.
 */
class ThroughSinkTest {
    /**
     * A DataSpec without a constructor (it needs a real android.net.Uri, a stub in JVM tests), with the
     * fields the code reads.
     */
    private fun spec(key: String?, position: Long = 0, length: Long = -1, flags: Int = DataSpec.FLAG_DONT_CACHE_IF_LENGTH_UNKNOWN): DataSpec {
        val unsafe = Class.forName("sun.misc.Unsafe").getDeclaredField("theUnsafe").apply { isAccessible = true }.get(null)
        val s = unsafe.javaClass.getMethod("allocateInstance", Class::class.java).invoke(unsafe, DataSpec::class.java) as DataSpec
        fun set(name: String, value: Any?) = DataSpec::class.java.getDeclaredField(name).apply { isAccessible = true }.set(s, value)
        set("key", key)
        set("position", position)
        set("length", length)
        set("flags", flags)
        return s
    }

    private inline fun <reified T> allocate(): T {
        val unsafe = Class.forName("sun.misc.Unsafe").getDeclaredField("theUnsafe").apply { isAccessible = true }.get(null)
        return unsafe.javaClass.getMethod("allocateInstance", Class::class.java).invoke(unsafe, T::class.java) as T
    }

    /** No cache call is expected: an unknown-length spec makes Media3's sink a no-op. */
    private val cache = Proxy.newProxyInstance(Cache::class.java.classLoader, arrayOf(Cache::class.java)) { _, m, _ -> fail("cache.${m.name}"); null } as Cache

    @Test
    fun `the sink makes the row once, before its first write, and only when it writes`() {
        var writes = true
        var rows = 0
        val sink = ThroughSink(cache, { writes }, { rows++ })
        writes = false
        sink.open(spec("e1:k1"))
        sink.write(ByteArray(4), 0, 4)
        sink.close()
        assertEquals("declined: no row, nothing written", 0, rows)
        writes = true
        sink.open(spec("e1:k1"))
        sink.close()
        sink.open(spec("e1:k1", position = 1000))
        sink.close()
        assertEquals("one row per sink", 1, rows)
    }

    @Test
    fun `a failure deciding or making the row is a decline, never a playback error`() {
        val throwing = ThroughSink(cache, { error("library") }, { fail("no row without a decision") })
        throwing.open(spec("e1:k1"))
        throwing.write(ByteArray(4), 0, 4)
        throwing.close()
        var asked = 0
        val noRow = ThroughSink(cache, { true }, { asked++; error("database") })
        noRow.open(spec("e1:k1"))
        noRow.write(ByteArray(4), 0, 4)
        noRow.close()
        noRow.open(spec("e1:k1"))
        assertEquals("the row is tried again at the next open", 2, asked)
    }

    /** An http source answering [status] with [headers] and the body's [length]. */
    private fun http(status: Int, headers: Map<String, List<String>>, length: Long = 500): Pair<HttpDataSource, MutableList<String>> {
        val calls = mutableListOf<String>()
        val source = Proxy.newProxyInstance(HttpDataSource::class.java.classLoader, arrayOf(HttpDataSource::class.java)) { _, m, _ ->
            calls += m.name
            when (m.name) {
                "open" -> length
                "getResponseCode" -> status
                "getResponseHeaders" -> headers
                else -> null
            }
        } as HttpDataSource
        return source to calls
    }

    @Test
    fun `the length guard - another total at a resume is a 416, the job's and the player's path`() {
        val stored = mapOf("e1:k1" to 1000L)
        val (changed, calls) = http(206, mapOf("content-range" to listOf("bytes 500-1199/1200")))
        try {
            LengthGuard(changed) { stored[it] }.open(spec("e1:k1", position = 500))
            fail("a changed source must not be read on")
        } catch (e: HttpDataSource.InvalidResponseCodeException) {
            assertEquals(416, e.responseCode)
            assertEquals("http_416", MediaCache.errorCode(e))
            assertTrue("its cause names it (qa-30-s3b-code L1)", MediaCache.sourceChanged(e))
            assertTrue("the response is closed", "close" in calls)
        }
        val (same, _) = http(206, mapOf("Content-Range" to listOf("bytes 500-999/1000")))
        assertEquals(500L, LengthGuard(same) { stored[it] }.open(spec("e1:k1", position = 500)))
        val (fromZero, _) = http(200, mapOf("Content-Length" to listOf("1200")), length = 1200)
        assertEquals("from 0 nothing is mixed", 1200L, LengthGuard(fromZero) { stored[it] }.open(spec("e1:k1")))
        val (unknown, _) = http(206, mapOf("Content-Range" to listOf("bytes 500-1199/1200")))
        assertEquals("no stored length: no verdict", 500L, LengthGuard(unknown) { null }.open(spec("e1:k1", position = 500)))
        val (noKey, _) = http(206, mapOf("Content-Range" to listOf("bytes 500-1199/1200")))
        assertEquals("not through the cache (an uncached item)", 500L, LengthGuard(noKey) { stored[it] }.open(spec(null, position = 500)))
    }

    /** qa-30-s3b-code L1: a changed source fails at once; anything else is retried as Media3 does. */
    @Test
    fun `the player doesn't retry a changed source, and retries everything else as Media3 does`() {
        val (changed, _) = http(206, mapOf("Content-Range" to listOf("bytes 500-1199/1200")))
        val e = try {
            LengthGuard(changed) { 1000L }.open(spec("e1:k1", position = 500))
            null
        } catch (e: HttpDataSource.InvalidResponseCodeException) {
            e
        }!!
        fun info(error: IOException, count: Int = 1) = LoadErrorHandlingPolicy.LoadErrorInfo(allocate<LoadEventInfo>(), allocate<MediaLoadData>(), error, count)
        val policy = SourceChangedPolicy()
        assertEquals(C.TIME_UNSET, policy.getRetryDelayMsFor(info(e)))
        assertEquals(C.TIME_UNSET, policy.getRetryDelayMsFor(info(IOException(e))))
        val other = IOException("a dropped connection")
        assertNotEquals(C.TIME_UNSET, policy.getRetryDelayMsFor(info(other, 2)))
        assertEquals(DefaultLoadErrorHandlingPolicy().getRetryDelayMsFor(info(other, 2)), policy.getRetryDelayMsFor(info(other, 2)))
        assertFalse(MediaCache.sourceChanged(other))
        // The player's media sources take it.
        val local = me.vibb.music.Fixtures.moduleFile("src/main/java/me/vibb/music/playback/LocalRenderer.kt").readText()
        assertTrue(local.contains(".setMediaSourceFactory(ItemMediaSourceFactory(context).setLoadErrorHandlingPolicy(SourceChangedPolicy()))"))
    }
}
