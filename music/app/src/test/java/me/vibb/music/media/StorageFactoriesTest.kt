package me.vibb.music.media

import android.net.Uri
import androidx.media3.datasource.DataSink
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.CacheKeyFactory
import me.vibb.music.logic.ItemRef
import me.vibb.music.logic.PlainSinkRefusedException
import me.vibb.music.logic.plainSinkRefuses
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.lang.reflect.Proxy

/**
 * Design 117 §4.5 (QA M5(a), (b), L10): the Storytel factories write only through the [SealDataSink] and read only
 * through the [SealDataSource] (by reflection on what Media3 builds); every plain sink refuses a sealed key and a
 * Storytel source id; a sealed key goes to the Storytel factory by its tag or its custom key; a tagless item, and an
 * NRK item with no ref, get no write-through.
 */
class StorageFactoriesTest {
    /** A cache nothing may call while the factories are only built. */
    private val cache = Proxy.newProxyInstance(Cache::class.java.classLoader, arrayOf(Cache::class.java)) { _, m, _ -> fail("cache.${m.name}"); null } as Cache

    private val upstream = DataSource.Factory {
        object : DataSource {
            override fun addTransferListener(transferListener: TransferListener) = Unit
            override fun open(dataSpec: DataSpec): Long = 0
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int = -1
            override fun getUri(): Uri? = null
            override fun close() = Unit
        }
    }

    private fun field(o: Any, name: String): Any? = o.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(o)

    private val keys = SealKeys { throw SealTransientException("not read here") }

    @Test
    fun `the Storytel download factory writes only through the SealDataSink and reads only through the SealDataSource`() {
        val download = MediaCache.storytelFactory(cache, upstream, CacheKeyFactory { "sealed:e7:111" }, keys).createDataSourceForDownloading()
        val tee = field(download, "cacheWriteDataSource")!!
        assertTrue("the write path is the sealing sink", field(tee, "dataSink") is SealDataSink)
        assertTrue(field(download, "cacheReadDataSource") is SealDataSource)
        val play = MediaCache.storytelFactory(cache, null, CacheKeyFactory { "sealed:e7:111" }, keys).createDataSource()
        assertNull("the player's copy-only path has no write path at all", field(play, "cacheWriteDataSource"))
        assertTrue(field(play, "cacheReadDataSource") is SealDataSource)
    }

    private fun spec(key: String?): DataSpec {
        val unsafe = Class.forName("sun.misc.Unsafe").getDeclaredField("theUnsafe").apply { isAccessible = true }.get(null)
        val s = unsafe.javaClass.getMethod("allocateInstance", Class::class.java).invoke(unsafe, DataSpec::class.java) as DataSpec
        DataSpec::class.java.getDeclaredField("key").apply { isAccessible = true }.set(s, key)
        DataSpec::class.java.getDeclaredField("length").apply { isAccessible = true }.set(s, -1L)
        return s
    }

    @Test
    fun `every plain sink refuses a sealed key, and the rule refuses a Storytel source id`() {
        var opened = 0
        val inner = object : DataSink {
            override fun open(dataSpec: DataSpec) {
                opened++
            }
            override fun write(buffer: ByteArray, offset: Int, length: Int) = fail("a plain write")
            override fun close() = Unit
        }
        val sinks = listOf<DataSink>(ThroughSink(cache, { true }, { fail("no row for a Storytel book") }), NoWriteSink(), PlainCacheSink(inner))
        for (sink in sinks) {
            try {
                sink.open(spec("sealed:e7:111"))
                fail("${sink.javaClass.simpleName} took a sealed key")
            } catch (e: PlainSinkRefusedException) {
                // the item fails rather than storing a plain book
            }
        }
        assertEquals(0, opened)
        assertTrue(plainSinkRefuses("e7:111", "storytel:book:111"))
        assertTrue(plainSinkRefuses("sealed:e7:111", "https://cdn.example.org/a.mp3"))
        assertFalse(plainSinkRefuses("e1:k1", "https://cdn.example.org/k1.mp3"))
        NoWriteSink().open(spec("e1:k1"))
        PlainCacheSink(inner).open(spec("e1:k1"))
        assertEquals("a plain key passes the guard", 1, opened)
    }

    @Test
    fun `a sealed key goes to the Storytel factory by its tag or its custom key, and plain items get an explicit sink`() {
        assertEquals(ItemRoute.STORYTEL, itemRoute(null, "sealed:e7:111", "storytel:book:111"))
        assertEquals("a sealed custom key alone", ItemRoute.STORYTEL, itemRoute(null, "sealed:e7:111", "null"))
        assertEquals("a Storytel source id alone", ItemRoute.STORYTEL, itemRoute(null, "e7:111", "storytel:book:111"))
        assertEquals(ItemRoute.STORYTEL, itemRoute(ItemTag("e7:111", false, true, copyOnly = true), "e7:111", "storytel:book:111"))
        assertEquals(ItemRoute.STORYTEL, itemRoute(ItemTag("sealed:e7:111", false, true), null, "null"))
        assertEquals(ItemRoute.LOCAL, itemRoute(ItemTag(null, false, false), null, "file:///own/1"))
        assertEquals(ItemRoute.UNCACHED, itemRoute(ItemTag("e1:k1", false, true, uncached = true), null, "https://cdn.example.org/k1.mp3"))
        // a tagless item and an NRK item with no ref: the plain factory with NoWriteSink (no write-through)
        assertEquals(ItemRoute.PLAIN, itemRoute(null, "e1:k1", "https://cdn.example.org/k1.mp3"))
        assertFalse(writesThrough(null))
        val nrk = ItemTag("e2:n1", true, true, through = true)
        assertEquals(ItemRoute.PLAIN, itemRoute(nrk, "e2:n1", "https://nrk.example.org/n1.m3u8"))
        assertFalse(writesThrough(nrk))
        assertTrue(writesThrough(nrk.copy(ref = ItemRef(2, "n1"))))
    }
}
