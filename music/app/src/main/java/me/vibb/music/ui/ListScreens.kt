package me.vibb.music.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import me.vibb.music.R
import me.vibb.music.logic.Kind
import me.vibb.music.logic.ListRow
import me.vibb.music.logic.OfflineSummary
import me.vibb.music.logic.SearchHit
import me.vibb.music.logic.formatDuration
import me.vibb.music.logic.isSpotifyContext
import me.vibb.music.logic.kindOf
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

/**
 * "Liste" (`onList`): the offline summary (none for Spotify, which streams only: [summary] null - no line, or for a
 * context the hint that the order is picked, design 26c §5.9; no source named, step 8) and the rows in the order they play.
 */
@Composable
fun ListScreen(entry: Entry, rows: List<ListRow>, summary: OfflineSummary?, onBack: () -> Unit, onPick: (PlayItem) -> Unit) {
    Column(Modifier.fillMaxSize().padding(start = 16.dp, end = 16.dp, top = 10.dp, bottom = 14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Header(stringResource(R.string.back_player), onBack, entry.name)
        if (summary != null) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally), verticalAlignment = Alignment.CenterVertically) {
                Glyph(Icons.downloadAll, 16.dp, Palette.peach)
                Line(
                    when (summary) {
                        OfflineSummary.All -> stringResource(R.string.all_downloaded)
                        is OfflineSummary.Some -> stringResource(R.string.some_downloaded, summary.have, summary.of)
                    },
                    12, 800, Palette.sub,
                )
            }
        } else if (isSpotifyContext(entry)) {
            // a context (design 26c §5.9): the order is picked for the kid, its one row is the track playing now
            Line(stringResource(R.string.hint_spotify_picks, kindText(kindOf(entry))), 12, 800, Palette.sub, Modifier.fillMaxWidth(), center = true)
        }
        val state = rememberLazyListState(initialFirstVisibleItemIndex = (rows.indexOfFirst { it.playing }).coerceAtLeast(0))
        LazyColumn(state = state, verticalArrangement = Arrangement.spacedBy(6.dp)) {
            items(rows, key = { it.item.key }) { row -> ListRowView(row, onPick) }
        }
    }
}

@Composable
private fun ListRowView(row: ListRow, onPick: (PlayItem) -> Unit) {
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
        Box(Modifier.size(30.dp).clip(CircleShape).background(if (row.playing) Palette.peach else Palette.track), contentAlignment = Alignment.Center) {
            Line(if (row.heard) "✓" else row.number.toString(), 13, 800, if (row.playing) Palette.bg else Palette.fg)
        }
        Column(Modifier.weight(1f)) {
            Line(row.item.title, 15, 800, Palette.white)
            Line(
                when {
                    row.playing -> playing
                    row.heard -> stringResource(R.string.row_heard, duration)
                    else -> duration
                },
                12, 700, Palette.sub,
            )
        }
        if (row.offline) {
            val label = stringResource(R.string.downloaded)
            Box(
                Modifier.size(26.dp).clip(CircleShape).border(2.dp, Palette.peach, CircleShape).semantics { contentDescription = label },
                contentAlignment = Alignment.Center,
            ) { Glyph(Icons.downloaded, 14.dp, Palette.peach) }
        }
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
