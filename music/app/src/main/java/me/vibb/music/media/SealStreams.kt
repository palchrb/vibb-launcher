package me.vibb.music.media

import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSink
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import me.vibb.music.logic.PlainSinkRefusedException
import me.vibb.music.logic.SealCipher
import me.vibb.music.logic.isSealedKey
import me.vibb.music.logic.plainSinkRefuses
import java.io.IOException
import java.util.Objects

/*
 * The sealed book's streams (design 117 §4.2): the Storytel cache sink seals from the first byte, the cache-read source
 * opens in memory, both at the byte's own position, so the cache's offsets, the Range resume and every length rule stay
 * as they were. And the plain sinks' fail-closed guard (§4.5): a plain sink never stores a Storytel book.
 */

/**
 * The Storytel cache sink: [inner] (a `CacheDataSink`) receives only sealed bytes. It opens at `spec.position`, which
 * is where `CacheDataSink` writes (QA L2), and refuses a span-shaped spec (`uriPositionOffset != 0`), a key that isn't
 * sealed and a key without a readable record. Never in place: `TeeDataSource` hands it the reader's own buffer. The
 * cipher runs on across the 5 MiB file splits, which happen inside [inner].
 */
@OptIn(UnstableApi::class)
class SealDataSink(private val inner: DataSink, private val keys: SealKeys) : DataSink {
    private var cipher: SealCipher? = null
    private val scratch = ByteArray(SCRATCH)

    override fun open(dataSpec: DataSpec) {
        val key = dataSpec.key
        if (!isSealedKey(key)) throw IOException("the Storytel sink needs a sealed key")
        if (dataSpec.uriPositionOffset != 0L) throw IOException("the Storytel sink writes upstream reads only")
        cipher = SealCipher(keys.keyFor(key!!), dataSpec.position)
        inner.open(dataSpec)
    }

    override fun write(buffer: ByteArray, offset: Int, length: Int) {
        val c = cipher ?: throw IOException("the Storytel sink isn't open")
        var done = 0
        while (done < length) {
            val n = minOf(SCRATCH, length - done)
            c.update(buffer, offset + done, n, scratch, 0)
            inner.write(scratch, 0, n)
            done += n
        }
    }

    override fun close() {
        cipher = null
        inner.close()
    }

    companion object {
        const val SCRATCH = 64 * 1024
    }
}

/**
 * The Storytel cache-read source: a cached span ([upstream], a `FileDataSource`) opened in memory. Media3's
 * `CacheDataSource` gives a span as `uri` = its file, `uriPositionOffset` = the span's start, `position` = the offset in
 * it, so the book's position is their sum (as Media3's own `AesCipherDataSource`). A key that isn't sealed (a plain
 * copy from before) or without a readable record throws: nothing Storytel is ever read plain.
 */
@OptIn(UnstableApi::class)
class SealDataSource(private val upstream: DataSource, private val keys: SealKeys) : DataSource {
    private var cipher: SealCipher? = null

    override fun addTransferListener(transferListener: TransferListener) = upstream.addTransferListener(transferListener)

    override fun open(dataSpec: DataSpec): Long {
        val key = dataSpec.key
        if (!isSealedKey(key)) throw SealLostException("a Storytel book that isn't sealed")
        val bookKey = keys.keyFor(key!!)
        val length = upstream.open(dataSpec)
        cipher = SealCipher(bookKey, dataSpec.uriPositionOffset + dataSpec.position)
        return length
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        val c = cipher ?: throw IOException("the Storytel source isn't open")
        val n = upstream.read(buffer, offset, length)
        if (n > 0) c.update(buffer, offset, n, buffer, offset)
        return n
    }

    override fun getUri(): Uri? = upstream.uri

    override fun getResponseHeaders(): Map<String, List<String>> = upstream.responseHeaders

    override fun close() {
        cipher = null
        upstream.close()
    }
}

/** §4.5: a plain sink's open refuses a sealed key and a Storytel source id - the item fails, no plain book is stored. */
@OptIn(UnstableApi::class)
fun refusePlainBook(dataSpec: DataSpec) {
    if (plainSinkRefuses(dataSpec.key, Objects.toString(dataSpec.uri, null))) throw PlainSinkRefusedException()
}

/**
 * The plain factories' sink when nothing is written through (§4.5, QA L10): a tagless item, an NRK or RSS item with no
 * `ref`. It drops every write; Media3's default plain `CacheDataSink` is never made.
 */
@OptIn(UnstableApi::class)
class NoWriteSink : DataSink {
    override fun open(dataSpec: DataSpec) = refusePlainBook(dataSpec)

    override fun write(buffer: ByteArray, offset: Int, length: Int) = Unit

    override fun close() = Unit

    object Factory : DataSink.Factory {
        override fun createDataSink(): DataSink = NoWriteSink()
    }
}

/** The download factory's plain sink (NRK, RSS, HLS): Media3's `CacheDataSink` behind the guard (§4.5). */
@OptIn(UnstableApi::class)
class PlainCacheSink(private val inner: DataSink) : DataSink {
    override fun open(dataSpec: DataSpec) {
        refusePlainBook(dataSpec)
        inner.open(dataSpec)
    }

    override fun write(buffer: ByteArray, offset: Int, length: Int) = inner.write(buffer, offset, length)

    override fun close() = inner.close()
}
