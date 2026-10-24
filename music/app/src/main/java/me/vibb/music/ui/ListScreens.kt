package me.vibb.music.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import me.vibb.music.R
import me.vibb.music.logic.Kind
import me.vibb.music.logic.ListRow
import me.vibb.music.logic.OfflineSummary
import me.vibb.music.logic.OrderWords
import me.vibb.music.logic.SearchHit
import me.vibb.music.logic.formatDuration
import me.vibb.music.logic.isSpotifyContext
import me.vibb.music.logic.kindOf
import me.vibb.music.logic.rowBadge
import me.vibb.music.model.Category
import me.vibb.music.model.Entry
import me.vibb.music.model.PlayItem

@Composable
private fun Header(backLabel: String, onBack: () -> Unit, title: String?, trailing: @Composable () -> Unit = { Box(Modifier.size(44.dp)) }) {
    Row(Modifier.fillMaxWidth().height(56.dp), verticalAlignment = Alignment.CenterVertically) {
        RoundButton(Icons.back, backLabel, onBack)
        Line(title.orEmpty(), 20, 800, Palette.white, Modifier.weight(1f), center = true)
        trailing()
    }
}

/** The words of the shown order (design 39 §4). */
fun orderWordsText(words: OrderWords): Int = when (words) {
    OrderWords.OLDEST_FIRST -> R.string.order_oldest_first
    OrderWords.NEWEST_FIRST -> R.string.order_newest_first
    OrderWords.FROM_BOOK_1 -> R.string.order_from_book_1
    OrderWords.FROM_LAST_BOOK -> R.string.order_from_last_book
    OrderWords.IN_ORDER -> R.string.order_in_order
    OrderWords.REVERSED -> R.string.order_reversed
}

/**
 * "Liste" (`onList`): the offline summary (none for Spotify, which streams only: [summary] null - no line, or for a
 * context the hint that the order is picked, design 26c §5.9; no source named, step 8) and the rows in the order they play.
 * Design 39: [order] the header's flip button (null: no button, the slot keeps the title centred) and its words on the
 * line; [pictures] a picture per row ([rowPictures]); while it does, [onVisible] hears the visible rows, settled 1 s.
 */
@Composable
fun ListScreen(
    entry: Entry,
    category: Category?,
    rows: List<ListRow>,
    summary: OfflineSummary?,
    order: OrderUi?,
    pictures: Boolean,
    onFlip: () -> Unit,
    onVisible: (first: Int, last: Int) -> Unit,
    onBack: () -> Unit,
    onPick: (PlayItem) -> Unit,
    /** A row's heart tapped (design 50 §1.5): the item, the new state. */
    onFavourite: (PlayItem, Boolean) -> Unit,
    /** The header's title: the entry's name, or "Favoritter" for the favourites queue (design r12 §3.2). */
    title: String = entry.name,
    /** Each row's own entry and its category: a favourites queue's rows come from many - each sub-line names it. */
    entryOf: ((PlayItem) -> Entry)? = null,
    categoryOf: (PlayItem) -> Category? = { category },
) {
    Column(Modifier.fillMaxSize().padding(start = 16.dp, end = 16.dp, top = 10.dp, bottom = 14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        val words = order?.let { stringResource(orderWordsText(it.words)) }
        if (order != null) {
            Header(stringResource(R.string.back_player), onBack, title) {
                // the shuffle idiom: peach when flipped; TalkBack says "Snu rekkefølgen, nyeste først"
                RoundButton(
                    Icons.flipOrder, stringResource(R.string.flip_order), onFlip, 44.dp, 20.dp,
                    bg = if (order.flipped) Palette.peach else Palette.card,
                    fg = if (order.flipped) Palette.bg else Palette.fg,
                    modifier = Modifier.semantics {
                        selected = order.flipped
                        stateDescription = words.orEmpty()
                    },
                )
            }
        } else {
            Header(stringResource(R.string.back_player), onBack, title)
        }
        if (summary != null) {
            val summaryText = when (summary) {
                OfflineSummary.All -> stringResource(R.string.all_downloaded)
                is OfflineSummary.Some -> stringResource(R.string.some_downloaded, summary.have, summary.of)
            }
            val full = words?.let { stringResource(R.string.summary_with_order, summaryText, it) } ?: summaryText
            ListLine(summaryText, full, glyph = true)
        } else if (isSpotifyContext(entry)) {
            // a context (design 26c §5.9): the order is picked for the kid, its one row is the track playing now
            Line(stringResource(R.string.hint_spotify_picks, kindText(kindOf(entry))), 12, 800, Palette.sub, Modifier.fillMaxWidth(), center = true)
        } else if (words != null) {
            // Spotify's list mode: no summary, the order words alone
            Line(words, 12, 800, Palette.sub, Modifier.fillMaxWidth(), center = true)
        }
        val state = rememberLazyListState(initialFirstVisibleItemIndex = (rows.indexOfFirst { it.playing }).coerceAtLeast(0))
        // After a flip the keyed list would hold its first item: it goes to the playing row (the top when none plays).
        val shownNewest = order?.shownNewestFirst
        val currentRows by rememberUpdatedState(rows)
        var shownBefore by remember { mutableStateOf(shownNewest) }
        LaunchedEffect(shownNewest) {
            if (shownNewest == shownBefore) return@LaunchedEffect
            shownBefore = shownNewest
            state.scrollToItem(currentRows.indexOfFirst { it.playing }.coerceAtLeast(0))
        }
        if (pictures) {
            val visible by rememberUpdatedState(onVisible)
            // keyed on the direction too: after a flip the same index range holds other rows (qa-39-code N4)
            LaunchedEffect(state, entry.id, shownNewest) {
                snapshotFlow { state.layoutInfo.visibleItemsInfo.let { (it.firstOrNull()?.index ?: 0) to (it.lastOrNull()?.index ?: 0) } }
                    .distinctUntilChanged()
                    .collectLatest { (first, last) ->
                        delay(LIST_SETTLE_MS)
                        visible(first, last)
                    }
            }
        }
        LazyColumn(state = state, verticalArrangement = Arrangement.spacedBy(6.dp)) {
            // keyed by entry and item: two entries' items can share a key in a favourites queue (design r12 §0)
            items(rows, key = { pageKey(it.item) }) { row ->
                ListRowView(row, entryOf?.invoke(row.item) ?: entry, categoryOf(row.item), pictures, named = entryOf != null, onPick, onFavourite)
            }
        }
    }
}

/** The list's visible rows count once they stayed this long (a fling asks once, design 39 §2.2). */
const val LIST_SETTLE_MS = 1_000L

/**
 * The line under the list's header: [full] (the summary and the order words) when it fits at the font scale
 * ([orderWordsFit]), else [summary] alone - the button's look and state carry the order (design 39 §4).
 */
@Composable
private fun ListLine(summary: String, full: String, glyph: Boolean) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val measurer = rememberTextMeasurer()
        val density = LocalDensity.current
        val style = text(12.sp, 800, Palette.sub)
        val room = maxWidth.value
        val shown = remember(full, summary, room, density.density, density.fontScale) {
            if (full == summary) {
                full
            } else {
                val px = measurer.measure(full, style, maxLines = 1, softWrap = false).size.width
                if (orderWordsFit(px / density.density, glyph, room)) full else summary
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally), verticalAlignment = Alignment.CenterVertically) {
            if (glyph) Glyph(Icons.downloadAll, 16.dp, Palette.peach)
            Line(shown, 12, 800, Palette.sub)
        }
    }
}

@Composable
private fun ListRowView(
    row: ListRow,
    entry: Entry,
    category: Category?,
    pictures: Boolean,
    /** The sub-line names the row's entry first (the favourites list, design r12 §3.2: "<entry> · 12:34"). */
    named: Boolean,
    onPick: (PlayItem) -> Unit,
    onFavourite: (PlayItem, Boolean) -> Unit,
) {
    val duration = formatDuration(row.item.durationMs)
    // a Spotify context's one row has no duration (design 26c §5.9): "Spilles nå" alone, no trailing dot
    val playing = stringResource(R.string.row_playing, duration).removeSuffix(" · ")
    Row(
        Modifier.fillMaxWidth().heightIn(min = 56.dp).clip(RoundedCornerShape(16.dp))
            .background(if (row.playing) Palette.selected else Palette.card)
            .button(null) { onPick(row.item) }.padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        val number = rowBadge(row, pictures)
        if (pictures) {
            // design 39 §4: the row's own picture (decorative), the playing one ringed; a row without its own picture
            // shows the series cover plain (r12 §1) - nothing is written on a picture, so no cover carries a number
            val own = row.item.artHash
            val ring = if (row.playing) Modifier.border(2.dp, Palette.peach, RoundedCornerShape(10.dp)) else Modifier
            Cover(own ?: entry.cover, category, 10.dp, 0.dp, ring.size(40.dp), fallback = entry.cover, decodeDp = 40.dp)
        } else {
            Box(Modifier.size(30.dp).clip(CircleShape).background(if (row.playing) Palette.peach else Palette.track), contentAlignment = Alignment.Center) {
                Line(number!!, 13, 800, if (row.playing) Palette.bg else Palette.fg)
            }
        }
        Column(Modifier.weight(1f)) {
            Line(row.item.title, 15, 800, Palette.white)
            val sub = when {
                row.playing -> playing
                row.heard -> stringResource(R.string.row_heard, duration)
                else -> duration
            }
            Line(if (named) listOf(entry.name, sub).filter { it.isNotEmpty() }.joinToString(" · ") else sub, 12, 700, Palette.sub)
        }
        if (row.offline) {
            val label = stringResource(R.string.downloaded)
            Box(
                Modifier.size(26.dp).clip(CircleShape).border(2.dp, Palette.peach, CircleShape).semantics { contentDescription = label },
                contentAlignment = Alignment.Center,
            ) { Glyph(Icons.downloaded, 14.dp, Palette.peach) }
        }
        // design 50 §1.5: the small trailing heart, peach when set (none on a context's one row)
        row.favourite?.let { on -> HeartButton(on, 40.dp, 18.dp) { onFavourite(row.item, it) } }
    }
}

/** Nytt offers "Fjern alle" only with [rows] new items to clear (N11). */
fun showsClearAll(rows: Int): Boolean = rows > 0

/** One row of Nytt. */
data class NewsRowUi(val entry: Entry, val category: Category?, val item: PlayItem, val kind: Kind)

/**
 * "Nytt" (`onNews`): the new items (a mini cover and three lines, as the mockup), "Fjern alle" - only while there is
 * something to clear ([showsClearAll]; emulator round 2026-10-14 N11) -, a tap plays and removes.
 */
@Composable
fun NewsScreen(rows: List<NewsRowUi>, onBack: () -> Unit, onClear: () -> Unit, onPick: (NewsRowUi) -> Unit) {
    Column(Modifier.fillMaxSize().padding(start = 16.dp, end = 16.dp, top = 10.dp, bottom = 14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (showsClearAll(rows.size)) {
            Header(stringResource(R.string.back_library), onBack, stringResource(R.string.news_title)) {
                Box(Modifier.height(44.dp).clip(RoundedCornerShape(22.dp)).button(null, onClear).padding(horizontal = 10.dp), contentAlignment = Alignment.Center) {
                    Line(stringResource(R.string.clear_all), 14, 800, Palette.peach)
                }
            }
        } else {
            // nothing to clear: the header's default trailing slot keeps the title centred
            Header(stringResource(R.string.back_library), onBack, stringResource(R.string.news_title))
        }
        if (rows.isEmpty()) {
            // design 34 P10 a: the disc with the bell and today's words, in the room under the header
            EmptyState(Icons.bell, stringResource(R.string.nothing_new), null, Modifier.weight(1f).fillMaxWidth())
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                items(rows, key = { "${it.entry.id}/${it.item.key}" }) { row ->
                    Row(
                        Modifier.fillMaxWidth().heightIn(min = 64.dp).clip(RoundedCornerShape(16.dp)).background(Palette.card)
                            .button(null) { onPick(row) }.padding(horizontal = 10.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Cover(row.item.artHash ?: row.entry.cover, row.category, 12.dp, 0.dp, Modifier.size(48.dp), fallback = row.entry.cover)
                        Column(Modifier.weight(1f)) {
                            val label = when (row.kind) {
                                Kind.OWN -> R.string.new_track
                                Kind.AUDIOBOOK -> R.string.new_book
                                Kind.PODCAST -> R.string.new_episode
                                Kind.MUSIC -> R.string.new_track
                            }
                            Line(stringResource(label), 12, 800, Palette.peach)
                            Line(row.item.title, 15, 800, Palette.white)
                            Line(row.entry.name, 12, 700, Palette.sub)
                        }
                    }
                }
            }
        }
    }
}

/** Search (`onSearch`): live hits over entry and item titles; "Fant ingenting". */
@Composable
fun SearchScreen(
    query: String,
    hits: List<SearchHit>,
    categoryOf: (Entry) -> Category?,
    onQuery: (String) -> Unit,
    onBack: () -> Unit,
    onPick: (SearchHit) -> Unit,
) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    Column(Modifier.fillMaxSize().padding(start = 16.dp, end = 16.dp, top = 10.dp, bottom = 14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(Modifier.fillMaxWidth().height(56.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            RoundButton(Icons.back, stringResource(R.string.back_library), onBack)
            Row(
                Modifier.weight(1f).height(44.dp).clip(RoundedCornerShape(22.dp)).background(Palette.card).padding(horizontal = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Glyph(Icons.search, 18.dp, Palette.sub)
                val label = stringResource(R.string.search_label)
                Box(Modifier.weight(1f)) {
                    if (query.isEmpty()) Line(stringResource(R.string.search), 16, 700, Palette.sub)
                    BasicTextField(
                        query, onQuery,
                        Modifier.fillMaxWidth().focusRequester(focus).semantics { contentDescription = label },
                        textStyle = text(16.sp, 700, Palette.white),
                        singleLine = true,
                        cursorBrush = SolidColor(Palette.peach),
                    )
                }
            }
        }
        if (query.isNotBlank() && hits.isEmpty()) {
            // design 34 P10 a: the disc with the search glyph and today's words, from the top (above the keyboard)
            EmptyState(Icons.search, stringResource(R.string.nothing_found), null, Modifier.weight(1f).fillMaxWidth(), centred = false)
        }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            items(hits, key = { h -> if (h is SearchHit.ItemHit) "${h.entry.id}/${h.item.key}" else "${h.entry.id}" }) { hit ->
                val kind = kindText(kindOf(hit.entry))
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 60.dp).clip(RoundedCornerShape(16.dp)).background(Palette.card)
                        .button(null) { onPick(hit) }.padding(horizontal = 10.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    val art = (hit as? SearchHit.ItemHit)?.item?.artHash ?: hit.entry.cover
                    Cover(art, categoryOf(hit.entry), 12.dp, 0.dp, Modifier.size(44.dp), fallback = hit.entry.cover)
                    Column(Modifier.weight(1f)) {
                        when (hit) {
                            is SearchHit.EntryHit -> {
                                Line(hit.entry.name, 15, 800, Palette.white)
                                Line(kind, 12, 700, Palette.sub)
                            }
                            is SearchHit.ItemHit -> {
                                Line(hit.item.title, 15, 800, Palette.white)
                                Line(stringResource(R.string.search_item_sub, hit.entry.name, kind), 12, 700, Palette.sub)
                            }
                        }
                    }
                }
            }
        }
    }
}
