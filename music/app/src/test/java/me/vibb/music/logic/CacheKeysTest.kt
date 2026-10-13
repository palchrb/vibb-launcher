package me.vibb.music.logic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Design 30 §1.6 (L5): what counts as the same source, and which item a cache resource belongs to. */
class CacheKeysTest {
    @Test
    fun `the same source ignores the query of an http url, compares a source id whole`() {
        // A re-resolved NRK token, a podcast host's new tracking query, a fragment: the same file.
        assertTrue(sameSource("https://nrk.example.org/a/b/master.m3u8?token=1", "https://nrk.example.org/a/b/master.m3u8?token=2"))
        assertTrue(sameSource("https://cdn.example.org/ep.mp3", "https://cdn.example.org/ep.mp3?utm=x#t=10"))
        assertTrue("the scheme's case", sameSource("HTTPS://cdn.example.org/ep.mp3", "https://cdn.example.org/ep.mp3"))
        // Another path, host, port or scheme is another source.
        assertFalse(sameSource("https://cdn.example.org/ep.mp3", "https://cdn.example.org/ep2.mp3"))
        assertFalse(sameSource("https://cdn.example.org/ep.mp3", "https://cdn2.example.org/ep.mp3"))
        assertFalse(sameSource("https://cdn.example.org/ep.mp3", "https://cdn.example.org:8443/ep.mp3"))
        assertFalse(sameSource("http://cdn.example.org/ep.mp3", "https://cdn.example.org/ep.mp3"))
        assertFalse("the path is raw: its case counts", sameSource("https://cdn.example.org/Ep.mp3", "https://cdn.example.org/ep.mp3"))
        // A Storytel source id is compared whole; never the same as a URL.
        assertTrue(sameSource("storytel:book:111", "storytel:book:111"))
        assertFalse(sameSource("storytel:book:111", "storytel:book:111?x=1"))
        assertFalse(sameSource("storytel:book:111", "https://cdn.example.org/111"))
        assertFalse(sameSource("not a url", "not a url?"))
    }

    @Test
    fun `a cache resource belongs to its item's key or HLS prefix`() {
        val items = setOf("e1:a", "e2:b/c")
        assertEquals("e1:a", itemOfResource("e1:a", items))
        assertEquals("e1:a", itemOfResource(hlsResourceKey("e1:a", "https://x.example.org/p/master.m3u8", "https://x.example.org/p/v1/seg1.ts"), items))
        assertEquals("a key with a slash of its own", "e2:b/c", itemOfResource("hls:e2:b/c/seg1.ts", items))
        assertNull(itemOfResource("e1:ab", items))
        assertNull(itemOfResource("hls:e1:ab/seg1.ts", items))
        assertNull(itemOfResource("hls:e9:a/seg1.ts", items))
    }
}
