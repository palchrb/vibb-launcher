package me.vibb.music.ui

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import me.vibb.music.data.NowPlayingRow
import me.vibb.music.data.Snapshot
import me.vibb.music.data.Store
import me.vibb.music.logic.effectiveCategory
import me.vibb.music.logic.shownEntries
import me.vibb.music.logic.tapCategory
import me.vibb.music.sync.NewNotifier

enum class Screen { LIBRARY, PLAYER, LIST, NEWS, SEARCH }

enum class SheetKind { SLEEP, OUTPUT }

/** The screens' state: the store's snapshot, the stored now-playing row, and where the kid is. */
class MusicViewModel(app: Application) : AndroidViewModel(app) {
    private val store = Store.get(app)

    val snapshot: StateFlow<Snapshot?> = store.snapshots().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    val nowRow: StateFlow<NowPlayingRow?> = store.db.nowPlayingFlow().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

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
        category = tapCategory(category, tapped)
        carouselIndex = 0
    }

    /**
     * Back from the player: the library shows the playing entry, in its category when the one
     * chosen doesn't hold it (vibb's cat_carousel case 6).
     */
    fun backToLibrary(snap: Snapshot?, entryId: Long?) {
        screen = Screen.LIBRARY
        val entries = snap?.entries ?: return
        val entry = entries.firstOrNull { it.id == entryId } ?: return
        var shown = shownEntries(entries, effectiveCategory(entries, category))
        if (entry !in shown) {
            category = entry.category?.takeIf { c -> entries.any { it.category == c } }
            shown = shownEntries(entries, effectiveCategory(entries, category))
        }
        carouselIndex = shown.indexOf(entry).coerceAtLeast(0)
        carouselJumps++
    }

    fun openSearch() {
        query = ""
        screen = Screen.SEARCH
    }

    /** "Fjern alle". */
    fun clearNews() {
        viewModelScope.launch(Dispatchers.IO) {
            store.db.clearNews()
            NewNotifier.update(getApplication(), store.snapshot())
        }
    }
}
