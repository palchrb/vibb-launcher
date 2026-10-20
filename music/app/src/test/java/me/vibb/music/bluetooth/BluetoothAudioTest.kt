package me.vibb.music.bluetooth

import me.vibb.music.bluetooth.BluetoothAudio.CONNECT_WINDOW_MS
import me.vibb.music.bluetooth.BluetoothAudio.STATE_CONNECTED
import me.vibb.music.bluetooth.BluetoothAudio.STATE_CONNECTING
import me.vibb.music.bluetooth.BluetoothAudio.STATE_DISCONNECTED
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.InvocationTargetException

/** Design 52's music half: the paired-headset filter, the rows, the connect reducer and the tap's plan. */
class BluetoothAudioTest {
    private val sink = BluetoothAudio.A2DP_SINK_UUID
    private val le = BluetoothAudio.LE_AUDIO_UUID
    private val hfp = "0000111e-0000-1000-8000-00805f9b34fb"

    private val buds = Bonded("aa:bb:cc:00:00:01", "Buds", listOf(hfp, sink.uppercase()))
    private val speaker = Bonded("AA:BB:CC:00:00:02", "Anna sin høyttaler", listOf(sink))
    private val watch = Bonded("AA:BB:CC:00:00:03", "Klokka", listOf(hfp))
    private val leOnly = Bonded("AA:BB:CC:00:00:04", "LE-propper", listOf(le))
    private val dual = Bonded("AA:BB:CC:00:00:05", null, listOf(le, sink))

    @Test
    fun `the filter - A2DP sink devices only, LE-only and non-audio left out, states from the proxy`() {
        val rows = BluetoothAudio.pairedAudioDevices(
            listOf(watch, buds, leOnly, speaker, dual),
            mapOf("AA:BB:CC:00:00:01" to STATE_CONNECTING, "aa:bb:cc:00:00:02" to STATE_CONNECTED, "AA:BB:CC:00:00:05" to 3),
            emptySet(),
        )
        assertEquals(
            listOf(
                PairedAudio("AA:BB:CC:00:00:02", "Anna sin høyttaler", HeadsetState.CONNECTED),
                PairedAudio("AA:BB:CC:00:00:01", "Buds", HeadsetState.CONNECTING),
                // no name sorts last; DISCONNECTING (3) is OFF
                PairedAudio("AA:BB:CC:00:00:05", null, HeadsetState.OFF),
            ),
            rows,
        )
    }

    @Test
    fun `the routed output is matched by address, any case, and left out`() {
        val rows = BluetoothAudio.pairedAudioDevices(listOf(buds, speaker), mapOf("AA:BB:CC:00:00:01" to STATE_CONNECTED, "AA:BB:CC:00:00:02" to STATE_CONNECTED), setOf("aa:bb:cc:00:00:01"))
        // the second connected sink is not the active one: it stays, "Bytt til"
        assertEquals(listOf("AA:BB:CC:00:00:02"), rows.map { it.address })
        assertEquals(HeadsetAction.MAKE_ACTIVE, BluetoothAudio.actionFor(rows.single().state))
        assertEquals(HeadsetAction.CONNECT, BluetoothAudio.actionFor(HeadsetState.OFF))
        assertEquals(HeadsetAction.CONNECT, BluetoothAudio.actionFor(HeadsetState.CONNECTING))
    }

    @Test
    fun `Bluetooth off or nothing bonded - no rows`() {
        assertTrue(BluetoothAudio.pairedAudioDevices(emptyList(), emptyMap(), emptySet()).isEmpty())
        assertTrue(BluetoothAudio.pairedAudioDevices(listOf(watch, leOnly), emptyMap(), emptySet()).isEmpty())
    }

    @Test
    fun `no holder prints a name or an address`() {
        val texts = listOf(
            buds.toString(),
            PairedAudio("AA:BB:CC:00:00:01", "Buds", HeadsetState.OFF).toString(),
            HeadsetRow("AA:BB:CC:00:00:01", "Buds", HeadsetLine.CONNECT, HeadsetAction.CONNECT).toString(),
            HeadsetConnect(mapOf("AA:BB:CC:00:00:01" to Attempt(1, false)), setOf("AA:BB:CC:00:00:02")).toString(),
            ConnectEvent.Tapped("AA:BB:CC:00:00:01", 1, false).toString(),
        )
        for (t in texts) assertFalse(t, t.contains("AA:BB") || t.contains("Buds"))
    }

    @Test
    fun `the failure words are the classes only - the cause of a reflected call, never its message`() {
        val words = BluetoothAudio.failureWords("connect", InvocationTargetException(SecurityException("AA:BB:CC:00:00:01 Buds", IllegalStateException("Buds"))))
        assertEquals("connect failed: java.lang.SecurityException (cause java.lang.IllegalStateException)", words)
        assertEquals("connect failed: java.lang.NoSuchMethodException", BluetoothAudio.failureWords("connect", NoSuchMethodException("connect")))
    }

    // The rows (decision 3).

    private val off = PairedAudio("AA:BB:CC:00:00:01", "Buds", HeadsetState.OFF)
    private val on = PairedAudio("AA:BB:CC:00:00:02", "Høyttaler", HeadsetState.CONNECTED)
    private val busy = PairedAudio("AA:BB:CC:00:00:03", "Busy", HeadsetState.CONNECTING)

    private fun lines(c: HeadsetConnect) = BluetoothAudio.headsetRows(listOf(off, on, busy), c).map { it.line }

    @Test
    fun `rows - Koble til, Bytt til for a connected-not-active one, Kobler til while connecting`() {
        assertEquals(listOf(HeadsetLine.CONNECT, HeadsetLine.SWITCH, HeadsetLine.CONNECTING), lines(HeadsetConnect()))
        assertEquals(listOf(HeadsetAction.CONNECT, HeadsetAction.MAKE_ACTIVE, HeadsetAction.CONNECT), BluetoothAudio.headsetRows(listOf(off, on, busy), HeadsetConnect()).map { it.action })
    }

    @Test
    fun `rows - Kobler til after a tap, then the failure line, then Koble til again on a new tap`() {
        var s = connectStep(HeadsetConnect(), ConnectEvent.Tapped(off.address, 0, false)).state
        assertEquals(HeadsetLine.CONNECTING, lines(s)[0])
        // the tap on "Bytt til" reads "Kobler til…" too
        s = connectStep(s, ConnectEvent.Tapped(on.address, 0, false)).state
        assertEquals(HeadsetLine.CONNECTING, lines(s)[1])
        s = connectStep(s, ConnectEvent.Tick(CONNECT_WINDOW_MS)).state
        assertEquals(listOf(HeadsetLine.NO_CONTACT, HeadsetLine.NO_CONTACT, HeadsetLine.CONNECTING), lines(s))
        s = connectStep(s, ConnectEvent.Tapped(off.address, CONNECT_WINDOW_MS + 1, false)).state
        assertEquals(HeadsetLine.CONNECTING, lines(s)[0])
    }

    // The reducer (decision 3, 52-5).

    private val a = "AA:BB:CC:00:00:01"

    @Test
    fun `an immediate false fails at once - a true waits`() {
        val open = connectStep(HeadsetConnect(), ConnectEvent.Tapped(a.lowercase(), 100, false)).state
        assertEquals(setOf(a), open.attempts.keys)
        assertEquals(open, connectStep(open, ConnectEvent.Answered(a, asked = true)).state)
        val step = connectStep(open, ConnectEvent.Answered(a, asked = false))
        assertEquals(HeadsetConnect(failed = setOf(a)), step.state)
        assertEquals(setOf(a), step.failed)
    }

    @Test
    fun `CONNECTING to DISCONNECTED fails at once, other edges and other devices don't`() {
        val open = connectStep(HeadsetConnect(), ConnectEvent.Tapped(a, 100, false)).state
        assertEquals(open, connectStep(open, ConnectEvent.StateChanged(a, STATE_DISCONNECTED, STATE_CONNECTING)).state)
        assertEquals(open, connectStep(open, ConnectEvent.StateChanged("AA:BB:CC:00:00:09", STATE_CONNECTING, STATE_DISCONNECTED)).state)
        assertEquals(open, connectStep(open, ConnectEvent.StateChanged(a, 3, STATE_DISCONNECTED)).state)
        val step = connectStep(open, ConnectEvent.StateChanged(a, STATE_CONNECTING, STATE_DISCONNECTED))
        assertEquals(setOf(a), step.failed)
        assertTrue(step.state.attempts.isEmpty())
        // without an open attempt the edge fails nothing (a headset that tried by itself)
        assertTrue(connectStep(HeadsetConnect(), ConnectEvent.StateChanged(a, STATE_CONNECTING, STATE_DISCONNECTED)).failed.isEmpty())
    }

    @Test
    fun `the 10 s window fails an attempt that never connected, a connected one ends quietly`() {
        var s = connectStep(HeadsetConnect(), ConnectEvent.Tapped(a, 1_000, true)).state
        s = connectStep(s, ConnectEvent.Tapped("AA:BB:CC:00:00:02", 5_000, true)).state
        s = connectStep(s, ConnectEvent.StateChanged("AA:BB:CC:00:00:02", STATE_CONNECTING, STATE_CONNECTED)).state
        assertEquals(s, connectStep(s, ConnectEvent.Tick(1_000 + CONNECT_WINDOW_MS - 1)).state)
        val first = connectStep(s, ConnectEvent.Tick(1_000 + CONNECT_WINDOW_MS))
        assertEquals(setOf(a), first.failed)
        assertEquals(setOf("AA:BB:CC:00:00:02"), first.state.attempts.keys)
        val second = connectStep(first.state, ConnectEvent.Tick(5_000 + CONNECT_WINDOW_MS))
        assertTrue(second.failed.isEmpty())
        assertEquals(HeadsetConnect(failed = setOf(a)), second.state)
    }

    @Test
    fun `CONNECTED ends Kobler til without a failure, and the row becomes Bytt til`() {
        val open = connectStep(HeadsetConnect(), ConnectEvent.Tapped(a, 0, false)).state
        val s = connectStep(open, ConnectEvent.StateChanged(a, STATE_CONNECTING, STATE_CONNECTED)).state
        assertTrue(s.attempts.getValue(a).connected)
        val row = BluetoothAudio.headsetRows(listOf(PairedAudio(a, "Buds", HeadsetState.CONNECTED)), s).single()
        assertEquals(HeadsetLine.SWITCH, row.line)
        // a late false for a connected attempt is no failure
        assertTrue(connectStep(s, ConnectEvent.Answered(a, false)).failed.isEmpty())
    }

    @Test
    fun `routed ends the attempt as a success, carrying whether a room played at the tap`() {
        var s = connectStep(HeadsetConnect(), ConnectEvent.Tapped(a, 0, onRoom = true)).state
        s = connectStep(s, ConnectEvent.Routed(setOf("AA:BB:CC:00:00:07"))).state
        assertEquals(setOf(a), s.attempts.keys)
        val step = connectStep(s, ConnectEvent.Routed(setOf(a.lowercase(), "AA:BB:CC:00:00:07")))
        assertEquals(Attempt(0, onRoom = true), step.routed.getValue(a))
        assertEquals(HeadsetConnect(), step.state)
        assertTrue(step.failed.isEmpty())
    }

    @Test
    fun `a late success clears the failure line and moves no room`() {
        var s = connectStep(HeadsetConnect(), ConnectEvent.Tapped(a, 0, onRoom = true)).state
        s = connectStep(s, ConnectEvent.Tick(CONNECT_WINDOW_MS)).state
        assertEquals(setOf(a), s.failed)
        val connected = connectStep(s, ConnectEvent.StateChanged(a, STATE_CONNECTING, STATE_CONNECTED))
        assertEquals(HeadsetConnect(), connected.state)
        val routed = connectStep(s, ConnectEvent.Routed(setOf(a)))
        assertEquals(HeadsetConnect(), routed.state)
        assertTrue("no open attempt: nothing to hand a room over", routed.routed.isEmpty())
    }

    @Test
    fun `a second tap on an open attempt changes nothing`() {
        val s = connectStep(HeadsetConnect(), ConnectEvent.Tapped(a, 0, onRoom = false)).state
        assertEquals(s, connectStep(s, ConnectEvent.Tapped(a, 5_000, onRoom = true)).state)
    }

    // The tap's plan (decision 3, 52-3).

    @Test
    fun `headsetTapPlan - on a room nothing moves at the tap, OUTPUT once routed, nothing on failure`() {
        assertEquals("the tap", HeadsetMove.NONE, headsetTapPlan(onRoom = true, routedNow = false))
        assertEquals("routed", HeadsetMove.OUTPUT, headsetTapPlan(onRoom = true, routedNow = true))
        assertEquals("the phone: system routing takes it", HeadsetMove.NONE, headsetTapPlan(onRoom = false, routedNow = true))
        assertEquals(HeadsetMove.NONE, headsetTapPlan(onRoom = false, routedNow = false))
        // a failure never reaches routed: the reducer's failed attempts carry no plan
        val failed = connectStep(connectStep(HeadsetConnect(), ConnectEvent.Tapped(a, 0, true)).state, ConnectEvent.Answered(a, false))
        assertTrue(failed.routed.isEmpty())
    }
}
