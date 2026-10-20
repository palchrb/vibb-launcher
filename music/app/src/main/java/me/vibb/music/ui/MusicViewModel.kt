package me.vibb.music.ui

import android.app.Application
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
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
import me.vibb.music.logic.favouriteEntryKeys
import me.vibb.music.logic.effectiveCategory
import me.vibb.music.logic.flipInEffect
import me.vibb.music.logic.newestFirst
import me.vibb.music.logic.shownNewestFirst
import me.vibb.music.model.Entry
import me.vibb.music.sync.ListArtWants
import me.vibb.music.logic.shownEntries
import me.vibb.music.logic.tapCategory
import me.vibb.music.playback.OutputKind
import me.vibb.music.playback.PlayerHub
import me.vibb.music.sync.NewNotifier
import me.vibb.music.sync.netCaps
import me.vibb.music.sync.netCapsOf
import me.vibb.music.logic.NetCaps

enum class Screen { LIBRARY, PLAYER, LIST, NEWS, SEARCH }

enum class SheetKind { SLEEP, OUTPUT }

/** The screens' state: the store's snapshot, the stored now-playing row, and where the kid is. */
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
    val nowRow: StateFlow<NowPlayingRow?> = store.db.nowPlayingFlow().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

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
        val favourites = favouriteEntryKeys(snap.favourites)
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
     * chosen doesn't hold it (vibb's cat_carousel case 6).
     */
    fun backToLibrary(snap: Snapshot?, entryId: Long?) {
        carouselStart.moved()
        screen = Screen.LIBRARY
        val entries = snap?.entries ?: return
        val entry = entries.firstOrNull { it.id == entryId } ?: return
        val favourites = favouriteEntryKeys(snap.favourites)
        var shown = shownEntries(entries, effectiveCategory(entries, category, favourites), favourites)
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
     * snapshot brings it back to the screens. Never for a context's synthetic item ([canFavourite]).
     */
    fun setFavourite(entry: Entry, itemKey: String, on: Boolean) {
        if (!canFavourite(entry, itemKey)) return
        viewModelScope.launch(Dispatchers.IO) {
            if (on) store.db.insertFavourite(FavouriteRow(entry.key, itemKey)) else store.db.deleteFavourite(entry.key, itemKey)
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
