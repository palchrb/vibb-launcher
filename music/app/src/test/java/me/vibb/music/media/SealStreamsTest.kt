package me.vibb.music.media

import android.net.Uri
import androidx.media3.datasource.DataSink
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import me.vibb.music.logic.SealCipher
import me.vibb.music.logic.SealSelfCheck
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException

/**
 * Design 117 §4.2: the [SealDataSink] and [SealDataSource] over fake inner sinks and sources, with the DataSpec in
 * Media3's two shapes - an upstream open (`uriPositionOffset` 0, the sink's) and a span read (`uriPositionOffset` = the
 * span's start, the source's). The keys are a [BookKeys] over SealFakes.kt.
 */
class SealStreamsTest {
    private val key = "sealed:e7:111"

    /** A DataSpec without a constructor (it needs a real android.net.Uri), as ThroughSinkTest builds it. */
    private fun spec(key: String?, position: Long = 0, uriPositionOffset: Long = 0): DataSpec {
        val unsafe = Class.forName("sun.misc.Unsafe").getDeclaredField("theUnsafe").apply { isAccessible = true }.get(null)
        val s = unsafe.javaClass.getMethod("allocateInstance", Class::class.java).invoke(unsafe, DataSpec::class.java) as DataSpec
        fun set(name: String, value: Any?) = DataSpec::class.java.getDeclaredField(name).apply { isAccessible = true }.set(s, value)
        set("key", key)
        set("position", position)
        set("uriPositionOffset", uriPositionOffset)
        set("length", -1L)
        return s
    }

    /** The book's bytes on "disk", written at each open's position as `CacheDataSink` writes them. */
    private class Disk(size: Int) : DataSink {
        val bytes = ByteArray(size)
        var at = -1
        val opened = mutableListOf<Long>()

        override fun open(dataSpec: DataSpec) {
            at = dataSpec.position.toInt()
            opened += dataSpec.position
        }

        override fun write(buffer: ByteArray, offset: Int, length: Int) {
            System.arraycopy(buffer, offset, bytes, at, length)
            at += length
        }

        override fun close() {
            at = -1
        }
    }

    /** A span file of [disk] from [spanStart]: the `FileDataSource` a span read opens. */
    private class SpanFile(private val disk: ByteArray, private val spanStart: Int) : DataSource {
        private var at = 0

        override fun addTransferListener(transferListener: TransferListener) = Unit

        override fun open(dataSpec: DataSpec): Long {
            at = spanStart + dataSpec.position.toInt()
            return (disk.size - at).toLong()
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            if (at >= disk.size) return -1
            val n = minOf(length, disk.size - at)
            System.arraycopy(disk, at, buffer, offset, n)
            at += n
            return n
        }

        override fun getUri(): Uri? = null

        override fun close() = Unit
    }

    private fun writeAll(sink: DataSink, plain: ByteArray, from: Int, to: Int, chunk: Int) {
        var at = from
        while (at < to) {
            val n = minOf(chunk, to - at)
            val before = plain.copyOfRange(at, at + n)
            sink.write(plain, at, n)
            assertArrayEquals("the caller's buffer is never changed", before, plain.copyOfRange(at, at + n))
            at += n
        }
    }

    @Test
    fun `the sink seals from the first byte at the spec's position, a resume continues the same stream, and the source opens any span`() {
        val w = SealWorld()
        w.keys.prepare(key)
        val size = 300_000
        val plain = SealSelfCheck.plain(0, size)
        val disk = Disk(size)
        val sink = SealDataSink(disk, w.keys)
        sink.open(spec(key, 0))
        writeAll(sink, plain, 0, 123_457, 70_001) // more than the 64 KiB scratch per write
        sink.close()
        sink.open(spec(key, 123_457)) // the resume: Range from where the copy ends
        writeAll(sink, plain, 123_457, size, 9_999)
        sink.close()
        assertEquals(listOf(0L, 123_457L), disk.opened)
        // exactly one pass from 0 under the book's key: the offsets are the plain ones
        val whole = ByteArray(size).also { SealCipher(w.keys.keyFor(key), 0).update(plain, 0, size, it, 0) }
        assertArrayEquals(whole, disk.bytes)
        assertFalse("no plain bytes reached the disk", (0 until size step 1000).any { disk.bytes[it] == plain[it] && disk.bytes[it + 1] == plain[it + 1] })
        // the source: spans at 0 and at 5 MiB-like offsets, opened anywhere in them, read in odd chunks
        for ((spanStart, inFile) in listOf(0 to 0, 0 to 17, 100_000 to 0, 100_000 to 33_333, 262_144 to 15)) {
            val source = SealDataSource(SpanFile(disk.bytes, spanStart), w.keys)
            source.open(spec(key, inFile.toLong(), spanStart.toLong()))
            val from = spanStart + inFile
            val out = ByteArray(size - from)
            var got = 0
            while (got < out.size) {
                val n = source.read(out, got, minOf(4_097, out.size - got))
                if (n < 0) break
                got += n
            }
            source.close()
            assertArrayEquals("span $spanStart + $inFile", plain.copyOfRange(from, size), out)
        }
    }

    @Test
    fun `the sink refuses a span-shaped spec, a key that isn't sealed and a key with no record - and writes nothing`() {
        val w = SealWorld()
        w.keys.prepare(key)
        val disk = Disk(16)
        for (bad in listOf(spec(key, 0, uriPositionOffset = 5), spec("e7:111"), spec(null), spec("sealed:e7:999"))) {
            try {
                SealDataSink(disk, w.keys).open(bad)
                fail("refused: ${bad.key} ${bad.uriPositionOffset}")
            } catch (e: IOException) {
                assertTrue(disk.opened.isEmpty())
            }
        }
    }

    @Test
    fun `the source refuses a key that isn't sealed and a sealed key without a record - nothing Storytel is read plain`() {
        val w = SealWorld()
        for (bad in listOf("e7:111", "sealed:e7:999")) {
            try {
                SealDataSource(SpanFile(ByteArray(16), 0), w.keys).open(spec(bad))
                fail(bad)
            } catch (e: SealLostException) {
                // Lost: "Ikke lastet ned", never retried
            }
        }
        assertTrue(MediaCache.sealLost(IOException("load", SealLostException("x"))))
        assertFalse(MediaCache.sealLost(SealTransientException("x")))
    }

    @Test
    fun `the memo is keyed by the record - a re-minted record never meets the old key`() {
        val w = SealWorld()
        w.keys.prepare(key)
        val old = w.keys.keyFor(key)
        // the record is dropped with the empty content and the book starts over: a new record, a new key
        w.cache.removeCopy(key)
        w.keys.prepare(key)
        val new = w.keys.keyFor(key)
        assertNotSame(old, new)
        val plain = ByteArray(32)
        val a = ByteArray(32).also { SealCipher(old, 0).update(plain, 0, 32, it, 0) }
        val b = ByteArray(32).also { SealCipher(new, 0).update(plain, 0, 32, it, 0) }
        assertFalse(a.contentEquals(b))
        // and a new process reads the new record to the same key the memo holds
        val fresh = BookKeys(w.keystore, w.prefs, w.cache).keyFor(key)
        val c = ByteArray(32).also { SealCipher(fresh, 0).update(plain, 0, 32, it, 0) }
        assertArrayEquals(b, c)
    }
}
