package com.kidslauncher.mdm.music

import android.media.AudioDeviceInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** The listening window's pure rules (design 28 §2-§3). */
class MusicWindowTest {
    private val boot = 7
    private val start = 3_600_000L // an hour after boot: past the headset's boot grace
    private fun at(elapsedMs: Long, bootCount: Int = boot) = BootStamp(elapsedMs, bootCount)
    private val none = Listening(null, null)
    private fun played(elapsedMs: Long, bootCount: Int = boot) = Listening(at(elapsedMs, bootCount), null)
    private fun session(play: SessionPlay) = MusicSession("me.vibb.music", play, "Reven og månen", "Kapittel 3", 60_000, 1_000, 1f, 600_000)

    private fun bt(id: Int, address: String = "AA:BB", type: Int = AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, sink: Boolean = true) = AudioOut(id, type, sink, address)
    private val empty = HeadsetWatch(emptySet(), emptyMap())

    @Test
    fun `playing shows with no end`() {
        assertEquals(Long.MAX_VALUE, musicShownUntil(true, none, at(start)))
        assertEquals("long after a stop, still playing", Long.MAX_VALUE, musicShownUntil(true, played(0), at(start)))
        assertEquals("another boot changes nothing while playing", Long.MAX_VALUE, musicShownUntil(true, none, at(start, -1)))
    }

    @Test
    fun `a stop shows until 10 minutes after it - shown at 10 min - 1 ms, hidden at exactly 10 min`() {
        val end = start + MUSIC_LINGER_MS
        assertEquals(end, musicShownUntil(false, played(start), at(start)))
        assertEquals(end, musicShownUntil(false, played(start), at(end - 1)))
        assertNull(musicShownUntil(false, played(start), at(end)))
        assertNull(musicShownUntil(false, played(start), at(end + 1)))
        assertEquals("the recheck at 10 min - 1 ms is the last millisecond", 1L, musicRecheckInMs(end, end - 1))
    }

    @Test
    fun `a live paused session older than 10 minutes hides`() {
        val paused = session(SessionPlay.PAUSED)
        assertSame(paused, windowedSession(paused, played(start), at(start + MUSIC_LINGER_MS - 1)))
        assertNull(windowedSession(paused, played(start), at(start + MUSIC_LINGER_MS)))
        assertNull("no stamp at all", windowedSession(paused, none, at(start)))
    }

    @Test
    fun `a fresh headset shows for 10 minutes and the later window wins`() {
        val headset = Listening(null, at(start))
        assertEquals(start + MUSIC_LINGER_MS, musicShownUntil(false, headset, at(start + 1)))
        assertNull(musicShownUntil(false, headset, at(start + MUSIC_LINGER_MS)))
        val both = Listening(at(start), at(start + 120_000))
        assertEquals("the headset later", start + 120_000 + MUSIC_LINGER_MS, musicShownUntil(false, both, at(start + MUSIC_LINGER_MS)))
        val playedLater = Listening(at(start + 300_000), at(start))
        assertEquals("the end of play later", start + 300_000 + MUSIC_LINGER_MS, musicShownUntil(false, playedLater, at(start + 400_000)))
        val oldBootHeadset = Listening(at(start), at(start + 300_000, boot - 1))
        assertEquals("a later stamp from another boot doesn't win", start + MUSIC_LINGER_MS, musicShownUntil(false, oldBootHeadset, at(start + 400_000)))
    }

    @Test
    fun `a stamp from another boot, an unknown boot count or a future stamp counts as nothing`() {
        assertNull("another boot", musicShownUntil(false, played(start, boot - 1), at(start + 1)))
        assertNull("unknown on both sides", musicShownUntil(false, played(start, -1), at(start + 1, -1)))
        assertNull("the stamp's boot unknown", musicShownUntil(false, played(start, -1), at(start + 1)))
        assertNull("today's boot unknown", musicShownUntil(false, played(start), at(start + 1, -1)))
        assertNull("from the future (elapsed restarted)", musicShownUntil(false, played(start), at(start - 1)))
        assertEquals("the same moment counts", start + MUSIC_LINGER_MS, musicShownUntil(false, played(start), at(start)))
    }

    @Test
    fun `no stamp - only playing shows`() {
        assertNull(musicShownUntil(false, none, at(start)))
        assertEquals(Long.MAX_VALUE, musicShownUntil(true, none, at(start)))
    }

    @Test
    fun `the stamp moves on playing and on the end of play, not on paused after paused`() {
        val earlier = at(start)
        val now = at(start + 15_000)
        assertEquals("playing", now, playedStampAfter(earlier, wasPlaying = true, playing = true, now = now))
        assertEquals("playing from nothing", now, playedStampAfter(null, wasPlaying = false, playing = true, now = now))
        assertEquals("the end of play", now, playedStampAfter(earlier, wasPlaying = true, playing = false, now = now))
        assertSame("paused after paused", earlier, playedStampAfter(earlier, wasPlaying = false, playing = false, now = now))
        assertNull("the music app restoring itself paused, nothing before", playedStampAfter(null, wasPlaying = false, playing = false, now = now))
    }

    @Test
    fun `an end of play after a reboot (previous stamp from another boot or none) keeps nothing`() {
        // QA L1: playing_at survived a dead battery; the music app's first report after the boot says paused.
        val now = at(90_000)
        val fromLastBoot = at(start, boot - 1)
        val kept = playedStampAfter(fromLastBoot, wasPlaying = true, playing = false, now = now)
        assertSame(fromLastBoot, kept)
        assertNull(musicShownUntil(false, Listening(kept, null), now))
        val fromNothing = playedStampAfter(null, wasPlaying = true, playing = false, now = now)
        assertNull(fromNothing)
        assertNull(musicShownUntil(false, Listening(fromNothing, null), now))
        assertNull("an unknown boot count", playedStampAfter(null, wasPlaying = true, playing = false, now = at(90_000, -1)))
        assertNull("a future stamp is not valid either", musicShownUntil(false, Listening(playedStampAfter(at(start), true, false, now), null), now))
    }

    @Test
    fun `only an end of play that moved the stamp notifies`() {
        val earlier = at(start)
        val now = at(start + 15_000)
        assertTrue(playStampNotifies(earlier, now, playing = false))
        assertTrue(playStampNotifies(null, now, playing = false))
        assertFalse("a playing stamp, every 15 s", playStampNotifies(earlier, now, playing = true))
        assertFalse("paused after paused", playStampNotifies(earlier, earlier, playing = false))
        assertFalse("nothing at all", playStampNotifies(null, null, playing = false))
    }

    @Test
    fun `windowedSession keeps a playing session, a paused one inside the window, nothing else`() {
        val playing = session(SessionPlay.PLAYING)
        val paused = session(SessionPlay.PAUSED)
        assertSame(playing, windowedSession(playing, none, at(start)))
        assertSame(paused, windowedSession(paused, played(start), at(start + 1)))
        assertNull(windowedSession(paused, none, at(start)))
        assertNull(windowedSession(null, played(start), at(start + 1)))
    }

    @Test
    fun `headset types - wired, USB headset, A2DP, LE headset and speaker, not SCO, ASHA hearing aid, broadcast, USB device, HDMI, built-ins or sources`() {
        for (type in listOf(
            AudioDeviceInfo.TYPE_WIRED_HEADSET,
            AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
            AudioDeviceInfo.TYPE_USB_HEADSET,
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
            AudioDeviceInfo.TYPE_BLE_HEADSET,
            AudioDeviceInfo.TYPE_BLE_SPEAKER,
        )) {
            assertTrue("type $type", countsAsHeadset(bt(1, type = type)))
            assertFalse("type $type as a source", countsAsHeadset(bt(1, type = type, sink = false)))
        }
        for (type in listOf(
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
            AudioDeviceInfo.TYPE_HEARING_AID,
            AudioDeviceInfo.TYPE_BLE_BROADCAST,
            AudioDeviceInfo.TYPE_USB_DEVICE,
            AudioDeviceInfo.TYPE_USB_ACCESSORY,
            AudioDeviceInfo.TYPE_HDMI,
            AudioDeviceInfo.TYPE_HDMI_ARC,
            AudioDeviceInfo.TYPE_HDMI_EARC,
            AudioDeviceInfo.TYPE_DOCK,
            AudioDeviceInfo.TYPE_LINE_ANALOG,
            AudioDeviceInfo.TYPE_BUS,
            AudioDeviceInfo.TYPE_REMOTE_SUBMIX,
            AudioDeviceInfo.TYPE_BUILTIN_SPEAKER,
            AudioDeviceInfo.TYPE_BUILTIN_EARPIECE,
            AudioDeviceInfo.TYPE_BUILTIN_MIC,
            AudioDeviceInfo.TYPE_TELEPHONY,
        )) {
            assertFalse("type $type", countsAsHeadset(bt(1, type = type)))
        }
    }

    @Test
    fun `devices at registration are the baseline, never fresh`() {
        // AOSP calls onAudioDevicesAdded once at registration with every device read just before.
        val baseline = HeadsetWatch(setOf(1, 2), emptyMap())
        val (watch, fresh) = headsetAdded(baseline, listOf(bt(1), bt(2, type = AudioDeviceInfo.TYPE_BUILTIN_SPEAKER, address = "")), start)
        assertFalse(fresh)
        assertEquals(setOf(1, 2), watch.known)
        val (_, later) = headsetAdded(watch, listOf(bt(3)), start)
        assertTrue("one added after the read is a real connection", later)
        val (_, again) = headsetAdded(HeadsetWatch(setOf(3), emptyMap()), listOf(bt(3)), start)
        assertFalse("a known id twice", again)
    }

    @Test
    fun `a re-add of the same device within 60 s is not fresh, after 60 s it is`() {
        val (connected, first) = headsetAdded(empty, listOf(bt(10)), start)
        assertTrue(first)
        val gone = headsetRemoved(connected, listOf(bt(10)), start + 5_000)
        assertFalse("its id left", 10 in gone.known)
        assertEquals(mapOf("${AudioDeviceInfo.TYPE_BLUETOOTH_A2DP}/AA:BB" to start + 5_000), gone.removedAt)
        assertFalse("a blip: back after 3 s with a new id", headsetAdded(gone, listOf(bt(11)), start + 8_000).second)
        assertFalse("at 60 s - 1 ms", headsetAdded(gone, listOf(bt(11)), start + 5_000 + HEADSET_REJOIN_MS - 1).second)
        val (back, fresh) = headsetAdded(gone, listOf(bt(11)), start + 5_000 + HEADSET_REJOIN_MS)
        assertTrue("at exactly 60 s", fresh)
        assertTrue("the old removal is dropped", back.removedAt.isEmpty())
        // a codec change: removed and added with a new id within milliseconds
        val codec = headsetRemoved(HeadsetWatch(setOf(11), emptyMap()), listOf(bt(11)), start)
        assertFalse(headsetAdded(codec, listOf(bt(12)), start + 20).second)
        // a removed source or a non-headset keeps no rejoin entry
        assertTrue(headsetRemoved(empty, listOf(bt(13, sink = false), bt(14, type = AudioDeviceInfo.TYPE_BLUETOOTH_SCO)), start).removedAt.isEmpty())
    }

    @Test
    fun `a second device (another address) is fresh`() {
        val gone = headsetRemoved(HeadsetWatch(setOf(10), emptyMap()), listOf(bt(10, address = "AA:BB")), start)
        assertTrue(headsetAdded(gone, listOf(bt(11, address = "CC:DD")), start + 1_000).second)
        assertTrue("the same address as another type", headsetAdded(gone, listOf(bt(11, type = AudioDeviceInfo.TYPE_BLE_HEADSET)), start + 1_000).second)
        assertTrue("one fresh among re-adds", headsetAdded(gone, listOf(bt(11), bt(12, address = "CC:DD")), start + 1_000).second)
    }

    @Test
    fun `a wired device keys by its type`() {
        val plug = AudioOut(20, AudioDeviceInfo.TYPE_WIRED_HEADPHONES, true, "")
        val gone = headsetRemoved(HeadsetWatch(setOf(20), emptyMap()), listOf(plug), start)
        assertEquals(setOf("${AudioDeviceInfo.TYPE_WIRED_HEADPHONES}/"), gone.removedAt.keys)
        assertFalse("replugged at once", headsetAdded(gone, listOf(plug.copy(id = 21)), start + 2_000).second)
        assertTrue("another wired type", headsetAdded(gone, listOf(AudioOut(22, AudioDeviceInfo.TYPE_WIRED_HEADSET, true, "")), start + 2_000).second)
    }

    @Test
    fun `connections in the first 2 minutes after boot are baseline`() {
        val (watch, fresh) = headsetAdded(empty, listOf(bt(1)), HEADSET_BOOT_GRACE_MS - 1)
        assertFalse(fresh)
        assertTrue("still joins the baseline", 1 in watch.known)
        assertFalse("not later either", headsetAdded(watch, listOf(bt(1)), HEADSET_BOOT_GRACE_MS + 1).second)
        assertTrue("from 2 min", headsetAdded(empty, listOf(bt(2)), HEADSET_BOOT_GRACE_MS).second)
    }

    @Test
    fun `the recheck delay is the time left and at least 1 ms, null while playing or without an end`() {
        assertEquals(MUSIC_LINGER_MS, musicRecheckInMs(start + MUSIC_LINGER_MS, start))
        assertEquals(1L, musicRecheckInMs(start, start))
        assertEquals(1L, musicRecheckInMs(start, start + 500))
        assertNull("playing", musicRecheckInMs(Long.MAX_VALUE, start))
        assertNull("hidden or no end", musicRecheckInMs(null, start))
        // the end and the recheck agree: recheck at the last shown millisecond lands on the hidden one
        val end = musicShownUntil(false, played(start), at(start + 1_000))!!
        assertNull(musicShownUntil(false, played(start), at(start + 1_000 + musicRecheckInMs(end, start + 1_000)!!)))
    }
}
