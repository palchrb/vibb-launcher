package me.vibb.music.ui

import android.app.Application
import android.bluetooth.BluetoothA2dp
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.net.ConnectivityManager
import android.os.SystemClock
import android.net.Network
import android.net.NetworkCapabilities
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import me.vibb.music.data.NowPlayingRow
import me.vibb.music.data.Snapshot
import me.vibb.music.data.Store
import me.vibb.music.data.FavouriteRow
import me.vibb.music.logic.CarouselStart
import me.vibb.music.logic.canFavourite
import me.vibb.music.logic.FAVOURITES_CATEGORY
import me.vibb.music.logic.effectiveCategory
import me.vibb.music.logic.flipInEffect
import me.vibb.music.logic.newestFirst
import me.vibb.music.logic.shownNewestFirst
import me.vibb.music.model.Entry
import me.vibb.music.sync.ListArtWants
import me.vibb.music.logic.shownEntries
import me.vibb.music.logic.tapCategory
import me.vibb.music.bluetooth.BluetoothAudio
import me.vibb.music.bluetooth.BluetoothLink
import me.vibb.music.bluetooth.ConnectEvent
import me.vibb.music.bluetooth.HeadsetAction
import me.vibb.music.bluetooth.HeadsetConnect
import me.vibb.music.bluetooth.HeadsetLine
import me.vibb.music.bluetooth.HeadsetMove
import me.vibb.music.bluetooth.HeadsetRow
import me.vibb.music.bluetooth.PairedAudio
import me.vibb.music.bluetooth.connectStep
import me.vibb.music.bluetooth.headsetTapPlan
import me.vibb.music.playback.Output
import me.vibb.music.playback.OutputKind
import me.vibb.music.playback.PlayerHub
import me.vibb.music.sync.NewNotifier
import me.vibb.music.sync.netCaps
import me.vibb.music.sync.netCapsOf
import me.vibb.music.logic.NetCaps

enum class Screen { LIBRARY, PLAYER, LIST, NEWS, SEARCH }

enum class SheetKind { SLEEP, OUTPUT }

/**
 * The entry keys holding a listed heart (design r12 §3.1, `Snapshot.favouritesOrder`): "Favoritter" shows while there is
 * one - [shownEntries]/[effectiveCategory]'s favourites (the entries list under the chip is unused: one tile).
 */
fun favouritesShown(snap: Snapshot): Set<String> = snap.favouritesOrder().map { it.entryKey }.toSet()

/** The screens' state: the store's snapshot, the stored now-playing row, and where the kid is. */
/** Design 53 §2.12: a group block's slider sends at most this often while dragging (and once at the release). */
const val VOLUME_SEND_MS = 150L

class MusicViewModel(app: Application) : AndroidViewModel(app) {
    private val store = Store.get(app)

    /**
     * The screens' snapshot for the renderer that plays (design 26b §3.2, §5.6): the shown output a Sonos
     * room - every Storytel book, so the carousel, the list and a stored room queue (`nowView` over the
     * stored row) keep the books that aren't on the phone - else the phone's view.
     */
    val snapshot: StateFlow<Snapshot?> = combine(store.snapshots(), PlayerHub.state.map { it.output.kind == OutputKind.SONOS }.distinctUntilChanged()) { snap, onRoom ->
        snap.forRenderer(onRoom)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /**
     * The stored row as read ([nowRow]'s null is also "none stored"): null until the first read, then the read - the row
     * and its "known" in one value, so the launch splash never sees "known" before the row itself (Q-10).
     */
    val nowRowRead: StateFlow<RowRead?> = store.db.nowPlayingFlow().map { RowRead(it) }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** One read of the stored now-playing row ([row] null: none stored). */
    data class RowRead(val row: NowPlayingRow?)

    /** The carousel's one start on the last played tile has run ([startOn]; the launch splash waits for it, Q-10). */
    var startSettled by mutableStateOf(false)

    /**
     * The screens' first content frame was shown (qa-music-splash-b Q-1): a configuration change keeps it, so its new
     * activity shows no launch splash; a new process or activity (a restored task after a process death) shows it again.
     */
    var contentShown by mutableStateOf(false)

    /** The phone's last Spotify outcome (design 26c §4.4, §5.8): every Spotify tile's account line reads it. */
    val spotifyOutcome: StateFlow<String?> = store.spotifyOutcomes().map { it?.outcome }.flowOn(Dispatchers.IO)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /**
     * Moves at every change of the default network (design 50 §1.4): the screens have no network callback of their
     * own, so the tiles read [netNow] again when this or the snapshot moves, and when the library (re)appears.
     */
    var netVersion by mutableIntStateOf(0)
        private set

    /**
     * The default network's callback (code QA 6): `onCapabilitiesChanged` also fires for signal strength and bandwidth,
     * every few seconds on cell - only a change of what the gate reads ([netCapsOf]) moves [netVersion].
     */
    private val netWatch = object : ConnectivityManager.NetworkCallback() {
        @Volatile private var last: NetCaps? = null

        override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
            val now = netCapsOf(networkCapabilities)
            if (now != last) {
                last = now
                bumpNet()
            }
        }

        override fun onLost(network: Network) {
            last = null
            bumpNet()
        }
    }

    init {
        runCatching { app.getSystemService(ConnectivityManager::class.java)?.registerDefaultNetworkCallback(netWatch) }
    }

    override fun onCleared() {
        runCatching { getApplication<Application>().getSystemService(ConnectivityManager::class.java)?.unregisterNetworkCallback(netWatch) }
        if (watching) {
            runCatching { getApplication<Application>().unregisterReceiver(a2dpWatch) }
            runCatching { getApplication<Application>().getSystemService(AudioManager::class.java)?.unregisterAudioDeviceCallback(routeWatch) }
        }
        a2dp?.let { bt.closeLater(it) }
        a2dp = null
    }

    // Design 52 (decision 3): "Spill av på"'s paired headsets. The state lives here, not in the sheet - a reopened
    // sheet reads it, and a "Koble til" outlives the sheet. Everything below runs on the main thread (the view
    // model's scope, the receiver and the audio callback), the Bluetooth calls in BluetoothLink on IO.

    private val bt = BluetoothLink(app)

    /** The A2DP proxy: opened at the sheet's open, held while the sheet is open or a call runs, closed after. */
    private var a2dp: BluetoothA2dp? = null
    private val a2dpLock = Mutex()
    private val refreshLock = Mutex()
    private var sheetOpen = false
    private var calls = 0
    private var watching = false

    /** The routed Bluetooth outputs: address -> `AudioDeviceInfo.id` (the `dev:` key). Memory only. */
    private var routedIds: Map<String, Int> = emptyMap()

    /** The paired audio devices not routed now, read while the sheet is open (empty without the grant). */
    var headsets by mutableStateOf<List<PairedAudio>>(emptyList())
        private set

    /** The open "Koble til" attempts and the failed ones (BluetoothAudio's reducer). */
    var headsetConnect by mutableStateOf(HeadsetConnect())
        private set

    /** A routed headset tapped on a Sonos room: its `dev:` key, for the screens to send as `OUTPUT` once. */
    private val outputAsks = Channel<String>(Channel.BUFFERED)
    val outputRequests: Flow<String> = outputAsks.receiveAsFlow()

    /** The sheet's headset rows. */
    fun headsetRows(): List<HeadsetRow> = BluetoothAudio.headsetRows(headsets, headsetConnect)

    /**
     * The sheet opened: without `BLUETOOTH_CONNECT` nothing (today's sheet); else the A2DP proxy, the watches and one
     * read. A failure line from before is not shown again (a closed sheet's failure was the player's hint).
     */
    fun outputSheetOpened() {
        sheetOpen = true
        // design 53 §2.7: a `groups` event looks at the other rooms again only while the sheet is open
        PlayerHub.sheetOpen.value = true
        headsetConnect = headsetConnect.copy(failed = emptySet())
        if (!bt.granted()) {
            headsets = emptyList()
            return
        }
        watch()
        viewModelScope.launch { refreshHeadsets() }
    }

    /** The sheet left: the proxy closes unless a call still runs; an open attempt goes on. */
    fun outputSheetClosed() {
        sheetOpen = false
        PlayerHub.sheetOpen.value = false
        // a drag cut off by the sheet closing never hands its base to the next one (qa-53b-code 14)
        volumeDrag = null
        headsets = emptyList()
        releaseA2dp()
    }

    /** The group block's drag in progress (design 53 §2.12): its id (the service's base for "Begge"), the last send, the trailing one. */
    private var volumeDrag: Long? = null
    private var volumeSentAt = 0L
    private var volumeLater: Job? = null

    /**
     * A slider of the group block (design 53 §2.12): sent at most every [VOLUME_SEND_MS] while the finger moves, and
     * once at the release ([done]) - the latest value always goes out. A drag's id names the "Begge" drag's base.
     */
    fun roomVolume(uid: String, group: Boolean, volume: Int, done: Boolean, send: (String, Boolean, Int, Long) -> Unit) {
        val now = SystemClock.elapsedRealtime()
        val drag = volumeDrag ?: now.also { volumeDrag = it }
        volumeLater?.cancel()
        volumeLater = null
        val wait = VOLUME_SEND_MS - (now - volumeSentAt)
        if (done || wait <= 0) {
            volumeSentAt = now
            if (done) volumeDrag = null
            send(uid, group, volume, drag)
            return
        }
        volumeLater = viewModelScope.launch {
            delay(wait)
            volumeSentAt = SystemClock.elapsedRealtime()
            send(uid, group, volume, drag)
        }
    }

    /**
     * A headset row tapped (decision 3): connect, or make a connected one active; the renderer doesn't move and the
     * sheet stays open on "Kobler til…". [fromKey]: the Sonos room's output key when a room is the output (null on the
     * phone) - once the headset is routed, that room hands over to it if it is still the output (headsetTapPlan). A row
     * already "Kobler til…" ignores the tap.
     */
    fun headsetTapped(row: HeadsetRow, fromKey: String?) {
        if (row.line == HeadsetLine.CONNECTING || !bt.granted()) return
        val address = BluetoothAudio.key(row.address)
        // a tap on an attempt still open (a connected one reads "Bytt til") asks again but keeps its window
        val opens = address !in headsetConnect.attempts
        reduce(ConnectEvent.Tapped(row.address, SystemClock.elapsedRealtime(), fromKey, row.action == HeadsetAction.MAKE_ACTIVE))
        if (opens) {
            windows[address] = viewModelScope.launch {
                delay(BluetoothAudio.CONNECT_WINDOW_MS)
                windows.remove(address)
                reduce(ConnectEvent.Tick(SystemClock.elapsedRealtime()))
            }
        }
        calls++
        viewModelScope.launch {
            try {
                val proxy = a2dp()
                val asked = proxy != null && when (row.action) {
                    HeadsetAction.CONNECT -> bt.connect(proxy, row.address)
                    HeadsetAction.MAKE_ACTIVE -> bt.makeActive(proxy, row.address)
                }
                reduce(ConnectEvent.Answered(row.address, asked))
                if (sheetOpen) refreshHeadsets()
            } finally {
                calls--
                releaseA2dp()
            }
        }
    }

    /** Each open attempt's 10 s window, cancelled when the attempt ends sooner (main thread). */
    private val windows = mutableMapOf<String, Job>()

    private fun reduce(e: ConnectEvent) {
        val step = connectStep(headsetConnect, e)
        headsetConnect = step.state
        for (ended in windows.keys - step.state.attempts.keys) windows.remove(ended)?.cancel()
        // a failure after the kid closed the sheet: the player's one line for 3 s (nameless)
        if (step.failed.isNotEmpty() && !sheetOpen) PlayerHub.mutableMessages.tryEmit(PlayerHub.Message.HEADSET_NO_CONTACT)
        if (step.routed.isEmpty()) return
        for ((address, attempt) in step.routed) {
            if (headsetTapPlan(attempt.fromKey, PlayerHub.state.value.output.key, routedNow = true) == HeadsetMove.OUTPUT) {
                routedIds[address]?.let { outputAsks.trySend(Output.deviceKey(it)) }
            }
        }
        // routed: the connected row takes over and the sheet closes, as a pick does
        if (sheet == SheetKind.OUTPUT) sheet = null
    }

    private suspend fun a2dp(): BluetoothA2dp? = a2dpLock.withLock { a2dp ?: bt.openA2dp().also { a2dp = it } }

    private fun releaseA2dp() {
        if (sheetOpen || calls > 0) return
        val proxy = a2dp ?: return
        a2dp = null
        bt.closeLater(proxy)
    }

    /** One read of the rows while the sheet is open (one at a time, so an older read never lands last). */
    private suspend fun refreshHeadsets() {
        refreshLock.withLock {
            try {
                val proxy = if (sheetOpen) a2dp() else null
                if (proxy != null && sheetOpen) {
                    val routed = bt.routed()
                    routedIds = routed
                    val paired = bt.pairedAudio(proxy, routed.keys)
                    if (sheetOpen) headsets = paired
                }
            } finally {
                releaseA2dp()
            }
        }
    }

    /** A2DP's state broadcast (protected, sent with `BLUETOOTH_CONNECT`): CONNECTING -> DISCONNECTED fails, CONNECTED ends "Kobler til…". */
    private val a2dpWatch = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val device = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java) ?: return
            val address = runCatching { device.address }.getOrNull() ?: return
            val previous = intent.getIntExtra(BluetoothProfile.EXTRA_PREVIOUS_STATE, -1)
            reduce(ConnectEvent.StateChanged(address, previous, intent.getIntExtra(BluetoothProfile.EXTRA_STATE, -1)))
            if (sheetOpen) viewModelScope.launch { refreshHeadsets() }
        }
    }

    /** The routed outputs changed: an open attempt's headset routed is its success. */
    private val routeWatch = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) = routesChanged()

        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) = routesChanged()
    }

    private fun routesChanged() {
        viewModelScope.launch {
            val routed = bt.routed()
            routedIds = routed
            reduce(ConnectEvent.Routed(routed.keys))
            if (sheetOpen) refreshHeadsets()
        }
    }

    /** The receiver and the audio callback, once (at the first granted sheet), until the view model is cleared. */
    private fun watch() {
        if (watching) return
        watching = true
        val app = getApplication<Application>()
        // exported: the Bluetooth stack (uid 1002) sends it, and BroadcastSkipPolicy's "Exported Denial" drops it for a
        // not-exported receiver; the action is a protected broadcast, so no other app can send it (qa-52-b-code)
        runCatching { app.registerReceiver(a2dpWatch, IntentFilter(BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED), Context.RECEIVER_EXPORTED) }
        runCatching { app.getSystemService(AudioManager::class.java)?.registerAudioDeviceCallback(routeWatch, null) }
    }

    /** Read the network again (a callback's thread posts to main: Compose state is written there). */
    fun bumpNet() {
        viewModelScope.launch(Dispatchers.Main) { netVersion++ }
    }

    /** What the network allows the tiles now (design 50 §1.4); [onRoom]: a Sonos room renders, nothing dims. */
    fun netNow(onRoom: Boolean): NetNow = NetNow(netCaps(getApplication()), store.streamMobileData, onRoom)

    var screen by mutableStateOf(Screen.LIBRARY)
    var sheet by mutableStateOf<SheetKind?>(null)
    var category by mutableStateOf<Long?>(null)
        private set

    /** The centred cover, as an index into the shown entries. */
    var carouselIndex by mutableIntStateOf(0)

    /** Moves when the carousel is turned from outside (back from the player), so it re-centres. */
    var carouselJumps by mutableIntStateOf(0)
        private set
    var query by mutableStateOf("")

    /** A tap the player hasn't shown yet, shown meanwhile (logic/Switch.kt). */
    var switching by mutableStateOf<Switch?>(null)

    /** A category tile: single choice, a re-tap or Alle shows all; the carousel starts at index 0. */
    fun pickCategory(tapped: Long?) {
        carouselStart.moved()
        category = tapCategory(category, tapped)
        carouselIndex = 0
    }

    /** Design 50 §1.3: the carousel's one start on the last played tile, never after the kid moved. */
    private val carouselStart = CarouselStart()

    /**
     * The first snapshot and the stored now-playing row are both in (design 50 §1.3): the carousel opens on that
     * entry's tile in the shown order - once, and never after the kid turned the carousel or came back from the player.
     */
    fun startOn(snap: Snapshot, row: NowPlayingRow) {
        val favourites = favouritesShown(snap)
        // design r12 §3.1: a stored favourites queue opens on the "Favoritter" chip and its one tile
        if (row.mix != null && favourites.isNotEmpty()) {
            carouselStart.first(emptyList(), null) ?: return
            category = FAVOURITES_CATEGORY
            carouselIndex = 0
            carouselJumps++
            return
        }
        val shown = shownEntries(snap.entries, effectiveCategory(snap.entries, category, favourites), favourites)
        val index = carouselStart.first(shown, row.entryId) ?: return
        carouselIndex = index
        carouselJumps++
    }

    /** The carousel landed on [index]: a turn by the kid when it moved (the pager reports its start page too). */
    fun turned(index: Int) {
        if (index != carouselIndex) carouselStart.moved()
        carouselIndex = index
    }

    /**
     * Back from the player: the library shows the playing entry, in its category when the one
     * chosen doesn't hold it (vibb's cat_carousel case 6) - a favourites queue ([favouritesQueue]) the "Favoritter"
     * chip and its one tile (design r12 §3.1), never an entry's tile there.
     */
    fun backToLibrary(snap: Snapshot?, entryId: Long?, favouritesQueue: Boolean = false) {
        carouselStart.moved()
        screen = Screen.LIBRARY
        val entries = snap?.entries ?: return
        val favourites = favouritesShown(snap)
        if (favouritesQueue && favourites.isNotEmpty()) {
            category = FAVOURITES_CATEGORY
            carouselIndex = 0
            carouselJumps++
            return
        }
        val entry = entries.firstOrNull { it.id == entryId } ?: return
        val selected = effectiveCategory(entries, category, favourites)
        var shown = if (selected == FAVOURITES_CATEGORY) emptyList() else shownEntries(entries, selected, favourites)
        if (entry !in shown) {
            category = entry.category?.takeIf { c -> entries.any { it.category == c } }
            shown = shownEntries(entries, effectiveCategory(entries, category, favourites), favourites)
        }
        carouselIndex = shown.indexOf(entry).coerceAtLeast(0)
        carouselJumps++
    }

    fun openSearch() {
        query = ""
        screen = Screen.SEARCH
    }

    /** Taps of the flip button, one at a time: each reads the row it flips (two quick taps flip twice). */
    private val flipLock = Mutex()

    /**
     * The list's flip button (design 39 §3): the row toggled on the store's side (so it always works, nothing need be
     * live), the stored queue with it; then [sent] (on the main thread) with the new shown direction - the screens
     * pass it to a live player as `FLIP_ORDER` only after the write (qa-39-design L9). [listed]: the entry's keys,
     * oldest first.
     */
    fun flip(entry: Entry, listed: List<String>, sent: (entryKey: String, newestFirst: Boolean) -> Unit) {
        viewModelScope.launch {
            val newest = flipLock.withLock {
                withContext(Dispatchers.IO) {
                    val parent = newestFirst(entry)
                    val flipped = !flipInEffect(parent, store.db.flip(entry.key)?.parentNewestFirst)
                    val newest = shownNewestFirst(parent, if (flipped) parent else null)
                    store.setFlip(entry, flipped, if (newest) listed.asReversed() else listed)
                    newest
                }
            }
            sent(entry.key, newest)
        }
    }

    /** The list's visible rows' pictures, [window] (`listArtWindow`), of [entryId] (design 39 §2.2). */
    fun listShown(entryId: Long, window: List<String?>) {
        viewModelScope.launch { ListArtWants.note(getApplication(), entryId, window) }
    }

    /**
     * A heart set or cleared (design 50 §1.5): the kid's own, on the phone only - written off the main thread, the
     * snapshot brings it back to the screens. Never for a context's synthetic item ([canFavourite]). A new heart carries
     * its time (design r12 §3.6: the favourites queue is newest first; the insert ignores a heart already set).
     */
    fun setFavourite(entry: Entry, itemKey: String, on: Boolean) {
        if (!canFavourite(entry, itemKey)) return
        val now = System.currentTimeMillis()
        viewModelScope.launch(Dispatchers.IO) {
            if (on) store.db.insertFavourite(FavouriteRow(entry.key, itemKey, now)) else store.db.deleteFavourite(entry.key, itemKey)
        }
    }

    /** "Fjern alle". */
    fun clearNews() {
        viewModelScope.launch(Dispatchers.IO) {
            store.db.clearNews()
            NewNotifier.update(getApplication(), store.snapshot())
        }
    }
}
