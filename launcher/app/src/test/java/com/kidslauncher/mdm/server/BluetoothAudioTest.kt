package com.kidslauncher.mdm.server

import android.bluetooth.BluetoothProfile.STATE_CONNECTED
import android.bluetooth.BluetoothProfile.STATE_CONNECTING
import android.bluetooth.BluetoothProfile.STATE_DISCONNECTED
import android.bluetooth.BluetoothProfile.STATE_DISCONNECTING
import com.kidslauncher.mdm.ui.quickcontrols.BtRowActions
import com.kidslauncher.mdm.ui.quickcontrols.BtRowStatus
import com.kidslauncher.mdm.ui.quickcontrols.bluetoothRowActions
import com.kidslauncher.mdm.ui.quickcontrols.bluetoothRowStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Bluetooth headphones in Kid Settings (design 52): the pure rules and the glue's source pins. */
class BluetoothAudioTest {
    private val hid = "00001124-0000-1000-8000-00805f9b34fb"
    private val hfp = "0000111e-0000-1000-8000-00805f9b34fb"

    // The filter.

    @Test
    fun `the A2DP sink makes an audio device, ASCS alone an LE-only one, anything else other`() {
        assertEquals(BtKind.A2DP, btKind(listOf(hfp, A2DP_SINK_UUID)))
        assertEquals(BtKind.A2DP, btKind(listOf(A2DP_SINK_UUID.uppercase())))
        assertEquals(BtKind.A2DP, btKind(listOf(A2DP_SINK_UUID, LE_AUDIO_ASCS_UUID)))
        assertEquals(BtKind.LE_ONLY, btKind(listOf(LE_AUDIO_ASCS_UUID.uppercase())))
        assertEquals(BtKind.OTHER, btKind(listOf(hid)))
        assertEquals(BtKind.OTHER, btKind(emptyList()))
        assertTrue(hasLeAudio(listOf(LE_AUDIO_ASCS_UUID.uppercase())))
        assertFalse(hasLeAudio(listOf(A2DP_SINK_UUID)))
    }

    // The states.

    @Test
    fun `an audio device's state is A2DP's, not the ACL link`() {
        assertEquals(BtLink.CONNECTED, btLink(BtKind.A2DP, false, STATE_CONNECTED, aclConnected = false))
        assertEquals(BtLink.CONNECTING, btLink(BtKind.A2DP, false, STATE_CONNECTING, aclConnected = true))
        // Connected for calls only (HFP up, A2DP down): not connected for music.
        assertEquals(BtLink.OFF, btLink(BtKind.A2DP, false, STATE_DISCONNECTED, aclConnected = true))
        assertEquals(BtLink.OFF, btLink(BtKind.A2DP, false, STATE_DISCONNECTING, aclConnected = false))
        // With LE Audio too, the ACL link counts as connected.
        assertEquals(BtLink.CONNECTED, btLink(BtKind.A2DP, true, STATE_DISCONNECTED, aclConnected = true))
        assertEquals(BtLink.OFF, btLink(BtKind.A2DP, true, STATE_DISCONNECTED, aclConnected = false))
        // No proxy: the ACL link stands in.
        assertEquals(BtLink.CONNECTED, btLink(BtKind.A2DP, false, null, aclConnected = true))
        assertEquals(BtLink.OFF, btLink(BtKind.A2DP, false, null, aclConnected = false))
    }

    @Test
    fun `a non-audio or LE-only device keeps the ACL mark`() {
        for (kind in listOf(BtKind.OTHER, BtKind.LE_ONLY)) {
            assertEquals(BtLink.CONNECTED, btLink(kind, kind == BtKind.LE_ONLY, null, aclConnected = true))
            assertEquals(BtLink.OFF, btLink(kind, kind == BtKind.LE_ONLY, STATE_CONNECTED, aclConnected = false))
        }
    }

    // Routed-address matching and what a connect does.

    @Test
    fun `a tap connects, makes a connected but not routed device active, and leaves a routed one`() {
        val address = "AA:BB:CC:00:11:22"
        assertTrue(isRouted(address, setOf("aa:bb:cc:00:11:22")))
        assertFalse(isRouted(address, setOf("AA:BB:CC:00:11:23")))
        assertFalse(isRouted(address, emptySet()))
        assertEquals(BtTap.NONE, btTap(STATE_CONNECTED, routed = true))
        assertEquals(BtTap.MAKE_ACTIVE, btTap(STATE_CONNECTED, routed = false))
        for (state in listOf(STATE_DISCONNECTED, STATE_CONNECTING, STATE_DISCONNECTING, null)) {
            assertEquals(BtTap.CONNECT, btTap(state, routed = false))
        }
    }

    // The attempt.

    @Test
    fun `an attempt ends on connected, a refusal, A2DP giving up or the timeout`() {
        assertEquals(BtAttemptEnd.FAILURE, btAttemptStep(BtAttemptEvent.Called(asked = false)))
        assertNull(btAttemptStep(BtAttemptEvent.Called(asked = true)))
        assertEquals(BtAttemptEnd.SUCCESS, btAttemptStep(BtAttemptEvent.Read(BtLink.CONNECTED)))
        assertNull(btAttemptStep(BtAttemptEvent.Read(BtLink.CONNECTING)))
        assertNull(btAttemptStep(BtAttemptEvent.Read(BtLink.OFF)))
        assertNull(btAttemptStep(BtAttemptEvent.A2dp(STATE_DISCONNECTED, STATE_CONNECTING)))
        assertEquals(BtAttemptEnd.FAILURE, btAttemptStep(BtAttemptEvent.A2dp(STATE_CONNECTING, STATE_DISCONNECTED)))
        // A late success (A2DP's own timeout is longer than ours) clears the failure.
        assertEquals(BtAttemptEnd.SUCCESS, btAttemptStep(BtAttemptEvent.A2dp(STATE_CONNECTING, STATE_CONNECTED)))
        // Another device's sink dropping is no failure of this one's attempt.
        assertNull(btAttemptStep(BtAttemptEvent.A2dp(STATE_CONNECTED, STATE_DISCONNECTED)))
        assertEquals(BtAttemptEnd.FAILURE, btAttemptStep(BtAttemptEvent.Timeout))
        assertEquals(10_000L, BT_ATTEMPT_TIMEOUT_MS)
    }

    // Kid Settings' rows.

    private fun info(bonded: Boolean = true, audio: Boolean = true, state: BtLink = BtLink.OFF) =
        QuickControls.BluetoothDeviceInfo("Headset", "AA:BB:CC:00:11:22", bonded, audio, state)

    @Test
    fun `an audio device connects or disconnects by its state, anything else only forgets`() {
        assertEquals(BtRowActions(pair = false, connect = true, disconnect = false, forget = true), bluetoothRowActions(info(), busy = false))
        assertEquals(BtRowActions(pair = false, connect = false, disconnect = true, forget = true), bluetoothRowActions(info(state = BtLink.CONNECTED), busy = false))
        assertEquals(BtRowActions(pair = false, connect = false, disconnect = false, forget = true), bluetoothRowActions(info(state = BtLink.CONNECTING), busy = false))
        assertEquals(BtRowActions(pair = false, connect = false, disconnect = false, forget = true), bluetoothRowActions(info(), busy = true))
        // A watch connected by ACL: Forget only, and it still reads connected.
        val watch = info(audio = false, state = BtLink.CONNECTED)
        assertEquals(BtRowActions(pair = false, connect = false, disconnect = false, forget = true), bluetoothRowActions(watch, busy = false))
        assertEquals(BtRowStatus.CONNECTED, bluetoothRowStatus(watch, pairing = false, attempting = false, failed = false))
        // Not paired: the row pairs, once.
        assertEquals(BtRowActions(pair = true, connect = false, disconnect = false, forget = false), bluetoothRowActions(info(bonded = false), busy = false))
        assertEquals(BtRowActions(pair = false, connect = false, disconnect = false, forget = false), bluetoothRowActions(info(bonded = false), busy = true))
    }

    @Test
    fun `the status line`() {
        assertEquals(BtRowStatus.PAIRING, bluetoothRowStatus(info(bonded = false), pairing = true, attempting = false, failed = false))
        assertEquals(BtRowStatus.CONNECTING, bluetoothRowStatus(info(), pairing = false, attempting = true, failed = false))
        assertEquals(BtRowStatus.CONNECTING, bluetoothRowStatus(info(state = BtLink.CONNECTING), pairing = false, attempting = false, failed = false))
        assertEquals(BtRowStatus.NO_CONTACT, bluetoothRowStatus(info(), pairing = false, attempting = false, failed = true))
        assertEquals(BtRowStatus.CONNECTED, bluetoothRowStatus(info(state = BtLink.CONNECTED), pairing = false, attempting = false, failed = true))
        assertEquals(BtRowStatus.PAIRED, bluetoothRowStatus(info(), pairing = false, attempting = false, failed = false))
        assertEquals(BtRowStatus.NONE, bluetoothRowStatus(info(bonded = false), pairing = false, attempting = false, failed = false))
    }

    @Test
    fun `a row's toString names no device`() {
        val text = info().toString()
        assertFalse(text.contains("Headset"))
        assertFalse(text.contains("AA:BB"))
    }

    // The glue, by source.

    private fun src(path: String) =
        listOf(File("src/main/java/com/kidslauncher/mdm/$path"), File("app/src/main/java/com/kidslauncher/mdm/$path"))
            .first { it.exists() }.readText()

    private val audio by lazy { src("server/BluetoothAudio.kt") }
    private val quick by lazy { src("server/QuickControls.kt") }
    private val activity by lazy { src("ui/quickcontrols/BluetoothDevicesActivity.kt") }
    private val adapter by lazy { src("ui/quickcontrols/BluetoothDeviceAdapter.kt") }

    private fun code(text: String) = text
        .replace(Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL), "")
        .replace(Regex("//[^\\n]*"), "")

    @Test
    fun `connect and disconnect reflect the A2DP and HEADSET proxies, never BluetoothDevice`() {
        val code = code(audio)
        for (call in listOf(
            "BluetoothA2dp::class.java.getMethod(\"connect\", BluetoothDevice::class.java)",
            "BluetoothA2dp::class.java.getMethod(\"disconnect\", BluetoothDevice::class.java)",
            "BluetoothA2dp::class.java.getMethod(\"setActiveDevice\", BluetoothDevice::class.java)",
            "BluetoothHeadset::class.java.getMethod(\"disconnect\", BluetoothDevice::class.java)",
        )) {
            assertTrue(call, code.contains(call))
        }
        assertFalse(code.contains("isConnected"))
        assertFalse(code.contains("BluetoothLeAudio"))
        for (text in listOf(code, code(quick))) {
            assertFalse(text.contains("getMethod(\"connect\")"))
            assertFalse(text.contains("getMethod(\"disconnect\")"))
        }
        // isConnected stays, for non-audio devices' mark.
        assertTrue(code(quick).contains("getMethod(\"isConnected\")"))
        // Connect and disconnect are suspend: never called on the main thread.
        assertTrue(quick.contains("suspend fun connectBluetoothDevice("))
        assertTrue(quick.contains("suspend fun disconnectBluetoothDevice("))
        assertTrue(quick.contains("suspend fun bondedBluetoothDevices("))
    }

    @Test
    fun `nothing blocks a thread for the proxy`() {
        for (text in listOf(audio, quick, activity, adapter)) {
            val code = code(text)
            for (banned in listOf("CountDownLatch", "runBlocking", ".await(")) {
                assertFalse(banned, code.contains(banned))
            }
        }
        assertTrue(audio.contains("suspendCancellableCoroutine"))
        assertTrue(audio.contains("withTimeoutOrNull(PROXY_WAIT_MS)"))
    }

    /** Each `Log.x(...)` call's text, by balanced parentheses. */
    private fun logCalls(text: String): List<String> =
        Regex("Log\\.[a-z]\\(").findAll(code(text)).map { m ->
            val source = code(text)
            var depth = 0
            var end = m.range.last
            while (end < source.length) {
                when (source[end]) {
                    '(' -> depth++
                    ')' -> if (--depth == 0) break
                }
                end++
            }
            source.substring(m.range.first, end + 1)
        }.toList()

    private fun interpolations(call: String): List<String> =
        Regex("\\$\\{([^}]*)\\}|\\$([A-Za-z_][A-Za-z0-9_]*)").findAll(call)
            .map { it.groupValues[1].ifEmpty { it.groupValues[2] } }.toList()

    @Test
    fun `no log line names a device or an address`() {
        val audioLogs = logCalls(audio)
        assertTrue(audioLogs.size >= 5)
        for (call in audioLogs) {
            for (value in interpolations(call)) {
                assertTrue("$call interpolates $value", value in setOf("failure(e)", "what", "profile"))
            }
            // Only the classes: never the exception (its message may name the device).
            assertFalse(call, call.trimEnd(')').trimEnd().endsWith(", e"))
        }
        val quickLogs = logCalls(quick)
        assertTrue(quickLogs.size >= 10)
        for (call in quickLogs) {
            for (value in interpolations(call)) {
                assertFalse("$call interpolates $value", Regex("(?i)address|device|name|uuid").containsMatchIn(value))
            }
        }
        // The Bluetooth lines that touch a device log the classes only.
        for (call in quickLogs.filter { Regex("Pairing failed|Forget failed|bonded devices").containsMatchIn(it) }) {
            assertTrue(call, call.contains("BluetoothAudio.failure(e)"))
        }
        assertTrue(logCalls(activity).isEmpty())
        assertTrue(logCalls(adapter).isEmpty())
    }

    @Test
    fun `the page grants BLUETOOTH_CONNECT before its first list read and follows the broadcasts`() {
        val onCreate = activity.substringAfter("override fun onCreate(").substringBefore("override fun onStart(")
        assertTrue(onCreate.contains("QuickControls.grantBluetoothConnectIfNeeded(this, dpm, admin)"))
        // The list is first read in onStart (the header's Scan button only binds a lambda here).
        assertFalse(onCreate.contains("\n        scan()"))
        assertFalse(onCreate.contains("loadBonded()"))
        assertTrue(activity.substringAfter("override fun onStart(").substringBefore("override fun onStop(").contains("scan()"))
        assertTrue(quick.contains("internal fun grantBluetoothConnectIfNeeded("))
        for (action in listOf(
            "BluetoothDevice.ACTION_BOND_STATE_CHANGED",
            "BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED",
            "BluetoothDevice.ACTION_ACL_CONNECTED",
            "BluetoothDevice.ACTION_ACL_DISCONNECTED",
        )) {
            assertTrue(action, activity.contains("addAction($action)"))
        }
        // One session per start, closed on stop.
        assertTrue(activity.substringAfter("override fun onStart(").contains("session = BluetoothAudio.Session(this)"))
        assertTrue(activity.substringAfter("override fun onStop(").substringBefore("override fun").contains("session?.close()"))
    }
}
