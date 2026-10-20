package me.vibb.music.bluetooth

import java.lang.reflect.InvocationTargetException

/*
 * Design 52 (Bluetooth headphones), the music app's half: the rules of "Spill av på"'s paired headset rows, pure and
 * Android-free (BluetoothAudioTest). The music app's own copy - the launcher's (Kid Settings) also classifies non-audio
 * devices, so the two are not byte-identical and there is no contract file for them (52-12). The Android side is
 * BluetoothLink.kt; the view model holds the state.
 *
 * Names and addresses never leave memory (decision 4): no holder here prints them (an explicit toString each), and no
 * log line in this package interpolates a value (SourceScanTest).
 */

/** A bonded device as the adapter gives it: its address (the row's key), its name, its service UUIDs. */
class Bonded(val address: String, val name: String?, val uuids: List<String>) {
    override fun toString(): String = "Bonded(${uuids.size} uuids)"
}

/** A paired audio device's A2DP state: OFF (disconnected, or anything else), CONNECTING, CONNECTED. */
enum class HeadsetState { OFF, CONNECTING, CONNECTED }

/** A paired audio device that is not the routed output: a "Koble til" / "Bytt til" row. */
data class PairedAudio(val address: String, val name: String?, val state: HeadsetState) {
    override fun toString(): String = "PairedAudio($state)"
}

/** What a tap on a headset row asks: A2DP `connect`, or `setActiveDevice` for one that is connected but not routed. */
enum class HeadsetAction { CONNECT, MAKE_ACTIVE }

/** The row's line: "Koble til", "Bytt til", "Kobler til…", "Fikk ikke kontakt med <name> - er den på?". */
enum class HeadsetLine { CONNECT, SWITCH, CONNECTING, NO_CONTACT }

/** One headset row of the sheet. */
data class HeadsetRow(val address: String, val name: String?, val line: HeadsetLine, val action: HeadsetAction) {
    override fun toString(): String = "HeadsetRow($line, $action)"
}

object BluetoothAudio {
    /** The A2DP sink's service UUID: a bonded device with it is an audio device. */
    const val A2DP_SINK_UUID = "0000110b-0000-1000-8000-00805f9b34fb"

    /**
     * LE Audio's ASCS UUID (`BluetoothUuid.LE_AUDIO` is not public). A device with it and without the A2DP sink is
     * LE-only: the sheet leaves it out (we don't connect LE, and a row without an action is a dead tap).
     */
    const val LE_AUDIO_UUID = "0000184e-0000-1000-8000-00805f9b34fb"

    /** `BluetoothProfile.STATE_*` (public constants, spelled here so these rules stay Android-free). */
    const val STATE_DISCONNECTED = 0
    const val STATE_CONNECTING = 1
    const val STATE_CONNECTED = 2

    /** How long a "Koble til" waits for the headset to be routed before "Fikk ikke kontakt" (decision 3). */
    const val CONNECT_WINDOW_MS = 10_000L

    /** How long the A2DP proxy is awaited (decision 1; its ready callback comes on main, so never by blocking). */
    const val PROXY_WAIT_MS = 2_000L

    /** The hidden `BluetoothA2dp` methods reached by reflection (design 52 §0) - these two only. */
    const val A2DP_CONNECT = "connect"
    const val A2DP_SET_ACTIVE = "setActiveDevice"

    /** One spelling for an address: addresses are compared upper case. */
    fun key(address: String): String = address.trim().uppercase()

    fun isAudio(uuids: List<String>): Boolean = uuids.any { it.trim().equals(A2DP_SINK_UUID, ignoreCase = true) }

    fun stateOf(a2dpState: Int?): HeadsetState = when (a2dpState) {
        STATE_CONNECTED -> HeadsetState.CONNECTED
        STATE_CONNECTING -> HeadsetState.CONNECTING
        else -> HeadsetState.OFF
    }

    /**
     * The sheet's paired audio devices (decision 1): bonded with the A2DP sink UUID, their state from the A2DP proxy's
     * [a2dpStates] (by address), and only those whose address is not among [routedAddresses] (the routed outputs'
     * `AudioDeviceInfo` addresses). Sorted by name, then address, so the rows don't jump between reads.
     */
    fun pairedAudioDevices(bonded: List<Bonded>, a2dpStates: Map<String, Int>, routedAddresses: Set<String>): List<PairedAudio> {
        val routed = routedAddresses.map(::key).toSet()
        val states = a2dpStates.mapKeys { key(it.key) }
        return bonded.asSequence()
            .filter { isAudio(it.uuids) }
            .map { PairedAudio(key(it.address), it.name?.trim()?.takeIf { n -> n.isNotEmpty() }, stateOf(states[key(it.address)])) }
            .filter { it.address !in routed }
            .distinctBy { it.address }
            .sortedWith(compareBy<PairedAudio>({ it.name == null }, { it.name?.lowercase() }, { it.address }))
            .toList()
    }

    /** A tap on a connected-but-not-routed device makes it active; every other state connects (decision 1). */
    fun actionFor(state: HeadsetState): HeadsetAction = if (state == HeadsetState.CONNECTED) HeadsetAction.MAKE_ACTIVE else HeadsetAction.CONNECT

    /**
     * The rows (decision 3): an open attempt not yet CONNECTED reads "Kobler til…", a failed one "Fikk ikke kontakt",
     * a device the proxy has CONNECTING "Kobler til…", CONNECTED (not routed) "Bytt til", else "Koble til".
     */
    fun headsetRows(paired: List<PairedAudio>, connect: HeadsetConnect): List<HeadsetRow> = paired.map { p ->
        val attempt = connect.attempts[p.address]
        val line = when {
            attempt != null && !attempt.connected -> HeadsetLine.CONNECTING
            p.address in connect.failed -> HeadsetLine.NO_CONTACT
            p.state == HeadsetState.CONNECTING -> HeadsetLine.CONNECTING
            p.state == HeadsetState.CONNECTED -> HeadsetLine.SWITCH
            else -> HeadsetLine.CONNECT
        }
        HeadsetRow(p.address, p.name, line, actionFor(p.state))
    }

    /**
     * A failed Bluetooth call in words for the log: the thrown class and its cause's class (the target of an
     * [InvocationTargetException] - a SecurityException over the binder), never a message, a device, a name or an
     * address (decision 4).
     */
    fun failureWords(op: String, t: Throwable): String {
        val thrown = (t as? InvocationTargetException)?.targetException ?: t
        val cause = thrown.cause?.javaClass?.name
        return op + " failed: " + thrown.javaClass.name + (if (cause != null) " (cause " + cause + ")" else "")
    }
}

/** What happens to a playing room when a tapped headset is routed (decision 3, 52-3). */
enum class HeadsetMove { NONE, OUTPUT }

/**
 * A headset tap's plan (decision 3): the tap itself only connects - the renderer never moves at the tap. When the
 * headset is then routed while its attempt is open and the tap was made on a Sonos room ([onRoom]), the app sends
 * `OUTPUT` with the headset's `dev:` key once (the usual `toPhone` + `setOutput`). A failure ([routedNow] false) moves
 * nothing; on the phone renderer the system routing already takes the sound (`onAudioDevicesAdded`).
 */
fun headsetTapPlan(onRoom: Boolean, routedNow: Boolean): HeadsetMove = if (onRoom && routedNow) HeadsetMove.OUTPUT else HeadsetMove.NONE

/** An open attempt: when it began, whether a Sonos room was the output at the tap, whether A2DP said CONNECTED. */
data class Attempt(val startedAt: Long, val onRoom: Boolean, val connected: Boolean = false)

/**
 * The view model's connect state (decision 3): the open attempts by address ("Kobler til…") and the addresses whose
 * last attempt failed. Outlives the sheet; a reopened sheet reads it.
 */
data class HeadsetConnect(val attempts: Map<String, Attempt> = emptyMap(), val failed: Set<String> = emptySet()) {
    override fun toString(): String = "HeadsetConnect(${attempts.size} open, ${failed.size} failed)"
}

/** What moves the connect state. Plain classes: their default toString prints no address. */
sealed class ConnectEvent {
    /** A row tapped at [now] (`elapsedRealtime`); [onRoom]: a Sonos room is the output. */
    class Tapped(val address: String, val now: Long, val onRoom: Boolean) : ConnectEvent()

    /** The call's immediate result: [asked] false = refused or not asked (the failure line at once). */
    class Answered(val address: String, val asked: Boolean) : ConnectEvent()

    /** `BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED` for [address]. */
    class StateChanged(val address: String, val previous: Int, val state: Int) : ConnectEvent()

    /** The routed outputs' addresses now. */
    class Routed(val addresses: Set<String>) : ConnectEvent()

    /** The clock: attempts older than [BluetoothAudio.CONNECT_WINDOW_MS] end. */
    class Tick(val now: Long) : ConnectEvent()
}

/** A reduction: the new state, the addresses that failed just now, and the attempts that ended routed just now. */
class ConnectStep(val state: HeadsetConnect, val failed: Set<String> = emptySet(), val routed: Map<String, Attempt> = emptyMap())

/**
 * The pure reducer (decision 3, 52-5):
 * - a tap opens an attempt (a second tap on an open one changes nothing) and clears its failure;
 * - an immediate `false`, or CONNECTING -> DISCONNECTED for an open attempt, fails it at once;
 * - CONNECTED ends "Kobler til…" (the row becomes "Bytt til"; no failure at the window's end) and clears a failure;
 * - routed ends the attempt as a success (the room's `OUTPUT`, [headsetTapPlan]) and clears a failure - a late
 *   success after the failure line only clears it, it moves no room;
 * - the window's end fails an attempt that never reached CONNECTED.
 */
fun connectStep(s: HeadsetConnect, e: ConnectEvent): ConnectStep = when (e) {
    is ConnectEvent.Tapped -> {
        val a = BluetoothAudio.key(e.address)
        if (a in s.attempts) ConnectStep(s) else ConnectStep(HeadsetConnect(s.attempts + (a to Attempt(e.now, e.onRoom)), s.failed - a))
    }
    is ConnectEvent.Answered -> {
        val a = BluetoothAudio.key(e.address)
        val open = s.attempts[a]
        if (e.asked || open == null || open.connected) ConnectStep(s) else ConnectStep(HeadsetConnect(s.attempts - a, s.failed + a), failed = setOf(a))
    }
    is ConnectEvent.StateChanged -> {
        val a = BluetoothAudio.key(e.address)
        val open = s.attempts[a]
        when {
            e.state == BluetoothAudio.STATE_CONNECTED ->
                ConnectStep(HeadsetConnect(if (open != null) s.attempts + (a to open.copy(connected = true)) else s.attempts, s.failed - a))
            e.previous == BluetoothAudio.STATE_CONNECTING && e.state == BluetoothAudio.STATE_DISCONNECTED && open != null ->
                ConnectStep(HeadsetConnect(s.attempts - a, s.failed + a), failed = setOf(a))
            else -> ConnectStep(s)
        }
    }
    is ConnectEvent.Routed -> {
        val routed = e.addresses.map(BluetoothAudio::key).toSet()
        val ended = s.attempts.filterKeys { it in routed }
        ConnectStep(HeadsetConnect(s.attempts - ended.keys, s.failed - routed), routed = ended)
    }
    is ConnectEvent.Tick -> {
        val over = s.attempts.filterValues { e.now - it.startedAt >= BluetoothAudio.CONNECT_WINDOW_MS }
        val failed = over.filterValues { !it.connected }.keys
        ConnectStep(HeadsetConnect(s.attempts - over.keys, s.failed + failed), failed = failed)
    }
}
