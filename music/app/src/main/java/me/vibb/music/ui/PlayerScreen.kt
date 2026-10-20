package me.vibb.music.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.animation.Crossfade
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.shadow
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.layout.offset
import androidx.annotation.DrawableRes
import me.vibb.music.logic.ControlSlots
import me.vibb.music.logic.SKIP_BACK_MS
import me.vibb.music.logic.SKIP_FORWARD_MS
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import me.vibb.music.R
import me.vibb.music.logic.Kind
import me.vibb.music.logic.Repeat
import me.vibb.music.logic.kindOf
import me.vibb.music.model.PlayItem
import me.vibb.music.playback.Output
import me.vibb.music.playback.OutputKind
import me.vibb.music.playback.RendererState
import me.vibb.music.sonos.RoomsView

/**
 * The player (design 21 §4.2, `Main.dc.html` v12 `onPlayer`): back + entry title, the cover
 * (it shrinks to fit the Jelly Star), the item's marquee title, "Episode|Spor N av M", the seek
 * line, [shuffle] previous / play-pause / next [slot] (v15's compact sizes on a 320 dp phone, [controlRow]),
 * then Liste / Sov / "Spill av på". On a Sonos room, or with Spotify's web player, a quiet line under the entry name
 * says why it isn't playing what we sent it ([rendererState], design 26a §5.2, 26c §5.8); nothing acts on it but the
 * kid. No source is named: a Spotify entry's player looks like any other's (design 26c §4.3, step 8).
 */
@Composable
fun PlayerScreen(
    now: NowView,
    /** An item's number in the list's order (a Storytel book's in the series, [playerNumberOf]). */
    numberOf: (PlayItem) -> Int,
    of: Int,
    positionMs: Long,
    sleepMinutes: Int?,
    /** "Til slutten" is set (design 50 §1.2): the bar says "Sov · til slutten". */
    sleepAtEnd: Boolean,
    output: Output,
    rendererState: RendererState?,
    onBack: () -> Unit,
    onSeek: (Long) -> Unit,
    onShuffle: () -> Unit,
    /** The repeat button (design 49 §1.2): off -> all -> one. */
    onRepeat: () -> Unit,
    onPrevious: () -> Unit,
    onTogglePlay: () -> Unit,
    onNext: () -> Unit,
    /** The skip pair (design 50 §1.1): back ([SKIP_BACK_MS] as a negative step) or on, within the item. */
    onSkipBy: (deltaMs: Long) -> Unit,
    /** An item's heart (design 50 §1.5): set or not, null for none (a context's synthetic item). */
    favourite: (PlayItem) -> Boolean?,
    onFavourite: (PlayItem, Boolean) -> Unit,
    /** A swipe of the episode carousel (design 27): to queue index, in that direction. */
    onSkipTo: (index: Int, forward: Boolean) -> Unit,
    onList: () -> Unit,
    onSleep: () -> Unit,
    onOutput: () -> Unit,
) {
    Column(
        Modifier.fillMaxSize().padding(start = 16.dp, end = 16.dp, top = 10.dp, bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(Modifier.fillMaxWidth().height(56.dp), verticalAlignment = Alignment.CenterVertically) {
            RoundButton(Icons.back, stringResource(R.string.back_library), onBack)
            Column(Modifier.weight(1f).padding(end = 44.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Line(now.entry.name, 15, 800, Palette.sub, Modifier.fillMaxWidth(), center = true)
                rendererState?.let { Line(stringResource(rendererStateText(it)), 12, 700, Palette.sub, Modifier.fillMaxWidth(), center = true) }
            }
        }
        // The cover, or the episode carousel in its place (design 27 §3); the title and number under
        // it follow the page the carousel is landing on.
        var landing by remember(now.pagerKey) { mutableStateOf<Int?>(null) }
        PlayerArt(now, Modifier.weight(1f).fillMaxWidth(), onSkipTo, onTogglePlay) { landing = it }
        val shown = landing?.let { now.queue.getOrNull(it) }?.takeIf { now.carousel } ?: now.item
        Crossfade(shown.key, animationSpec = tween(if (animationsOn()) 200 else 0), label = "item") { key ->
            val item = now.queue.firstOrNull { it.key == key } ?: shown
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Line(if (item == now.item) now.itemTitle else item.title, 20, 800, Palette.white, Modifier.basicMarquee(iterations = Int.MAX_VALUE))
                val numberText = when (kindOf(now.entry)) {
                    Kind.OWN -> R.string.track_of
                    Kind.AUDIOBOOK -> R.string.part_of
                    Kind.PODCAST -> R.string.episode_of
                    Kind.MUSIC -> R.string.track_of
                }
                // a Spotify context has no number: Spotify picks the tracks (design 26c §5.9)
                val heart = favourite(item)
                // design 50 §1.5: the heart under the title, beside "Episode N av M"
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (showsNumber(now)) Line(stringResource(numberText, numberOf(item), of), 13, 700, Palette.sub)
                    heart?.let { on -> HeartButton(on, 32.dp, 18.dp) { onFavourite(item, it) } }
                }
            }
        }
        SeekBar(positionMs, now.durationMs, stringResource(R.string.seek), onSeek)
        // v12's sizes where they fit, v15's compact ones on a 320 dp phone: always round, play centred.
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val row = controlRow(maxWidth.value)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(row.gap.dp, Alignment.CenterHorizontally), verticalAlignment = Alignment.CenterVertically) {
                // design 50 §1.1: what resumes gets "15 s tilbake" / "30 s fram" in the side slots; music gets design
                // 49's shuffle (left) and repeat (right), peach when on - controlSlots decides (49 §1.1); a Spotify artist
                // context repeat only (Spotify picks)
                val skips = now.slots == ControlSlots.SKIP
                if (skips) {
                    SkipButton(R.drawable.player_glyph_replay, "15", stringResource(R.string.skip_back), row.side.dp) { onSkipBy(-SKIP_BACK_MS) }
                } else if (now.canShuffle) {
                    val state = stringResource(if (now.shuffled) R.string.shuffle_on else R.string.shuffle_off)
                    RoundButton(
                        Icons.shuffle, stringResource(R.string.shuffle), onShuffle, row.side.dp, 20.dp,
                        if (now.shuffled) Palette.peach else Palette.card, if (now.shuffled) Palette.bg else Palette.fg,
                        Modifier.semantics {
                            selected = now.shuffled
                            stateDescription = state
                        },
                    )
                } else {
                    Spacer(Modifier.requiredSize(row.side.dp))
                }
                RoundButton(Icons.previous, stringResource(R.string.previous), onPrevious, row.skip.dp, 24.dp)
                RoundButton(
                    if (now.playing) Icons.pause else Icons.play, stringResource(if (now.playing) R.string.pause else R.string.play),
                    onTogglePlay, row.play.dp, 32.dp, Palette.peach, Palette.bg,
                )
                RoundButton(Icons.next, stringResource(R.string.next), onNext, row.skip.dp, 24.dp)
                if (skips) {
                    SkipButton(R.drawable.player_glyph_forward_media, "30", stringResource(R.string.skip_forward), row.side.dp) { onSkipBy(SKIP_FORWARD_MS) }
                } else if (now.canRepeat) {
                    val on = now.repeat != Repeat.OFF
                    val state = stringResource(repeatState(now.repeat))
                    RoundButton(
                        if (now.repeat == Repeat.ONE) Icons.repeatOne else Icons.repeat, stringResource(R.string.repeat), onRepeat,
                        row.side.dp, 20.dp, if (on) Palette.peach else Palette.card, if (on) Palette.bg else Palette.fg,
                        Modifier.semantics {
                            selected = on
                            stateDescription = state
                        },
                    )
                } else {
                    Spacer(Modifier.requiredSize(row.side.dp))
                }
            }
        }
        // Liste and Sov are never cut ("Sov · 30 min"); the output's name gives way (the mockup's flex).
        FlexBar(listOf(1f, 1f, 1.2f), shrinks = listOf(false, false, true), gap = 8.dp) {
            BarButton(Icons.list, stringResource(R.string.list), null, Modifier, onClick = onList)
            val sleepLabel = when {
                sleepAtEnd -> stringResource(R.string.sleep_at_end_on)
                sleepMinutes != null -> stringResource(R.string.sleep_on, sleepMinutes)
                else -> stringResource(R.string.sleep)
            }
            BarButton(Icons.moon, sleepLabel, null, Modifier, onClick = onSleep)
            val name = outputName(output)
            val external = output.kind != OutputKind.PHONE
            BarButton(
                outputIcon(output), name, stringResource(R.string.output_label, name), Modifier,
                if (external) Palette.selected else Palette.card, if (external) Palette.peach else Palette.fg, onOutput,
            )
        }
    }
}

/**
 * A side slot's skip button (design 50 §1.1): the Material Symbols `replay`/`forward_media` glyph at 20 dp in the
 * [size] round button, our own [seconds] drawn in its ring at a fixed size - dp, not the font scale's sp, so it always
 * fits the glyph.
 */
@Composable
private fun SkipButton(@DrawableRes glyph: Int, seconds: String, label: String, size: Dp, onClick: () -> Unit) {
    Box(Modifier.requiredSize(size).clip(CircleShape).background(Palette.card).button(label, onClick), contentAlignment = Alignment.Center) {
        Glyph(ImageVector.vectorResource(glyph), SKIP_GLYPH_DP.dp, Palette.fg)
        val number = with(LocalDensity.current) { SKIP_NUMBER_DP.dp.toSp() }
        BasicText(seconds, Modifier.offset(y = SKIP_NUMBER_DROP_DP.dp), style = text(number, 800, Palette.fg))
    }
}

/** The skip glyph (dp), its number's fixed size and how far below the button's centre the ring's centre is (dp). */
const val SKIP_GLYPH_DP = 20
const val SKIP_NUMBER_DP = 7
const val SKIP_NUMBER_DROP_DP = 1

/**
 * A row of [weights] sharing the width, each child at least as wide as its content unless it
 * [shrinks] ([flexWidths]), [gap] apart; as tall as the tallest child.
 */
@Composable
private fun FlexBar(weights: List<Float>, shrinks: List<Boolean>, gap: Dp, content: @Composable () -> Unit) {
    Layout(content, Modifier.fillMaxWidth()) { measurables, constraints ->
        val gapPx = gap.roundToPx()
        val available = (constraints.maxWidth - gapPx * (measurables.size - 1)).coerceAtLeast(0)
        val mins = measurables.mapIndexed { i, m -> if (shrinks[i]) 0 else m.maxIntrinsicWidth(constraints.maxHeight) }
        val widths = flexWidths(available, weights, mins)
        val placeables = measurables.mapIndexed { i, m -> m.measure(Constraints.fixedWidth(widths[i])) }
        val height = placeables.maxOfOrNull { it.height } ?: 0
        layout(constraints.maxWidth, height) {
            var x = 0
            for (p in placeables) {
                p.placeRelative(x, (height - p.height) / 2)
                x += p.width + gapPx
            }
        }
    }
}

@Composable
private fun BarButton(
    icon: ImageVector,
    label: String,
    description: String?,
    modifier: Modifier,
    bg: Color = Palette.card,
    fg: Color = Palette.fg,
    onClick: () -> Unit,
) {
    Row(
        modifier.height(48.dp).clip(RoundedCornerShape(16.dp)).background(bg).button(description ?: label, onClick).padding(horizontal = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Glyph(icon, 20.dp, fg)
        Line(label, 14, 800, fg)
    }
}

fun outputIcon(output: Output): ImageVector = when (output.kind) {
    OutputKind.PHONE -> Icons.speaker
    OutputKind.SONOS -> Icons.roomSpeaker
    OutputKind.BLUETOOTH, OutputKind.WIRED -> Icons.headphones
}

/** The repeat button's state for TalkBack (design 49 §1.2). */
fun repeatState(repeat: Repeat): Int = when (repeat) {
    Repeat.OFF -> R.string.repeat_off
    Repeat.ALL -> R.string.repeat_all
    Repeat.ONE -> R.string.repeat_one
}

/** The player's one line for a renderer that isn't playing what we sent it (design 26a §5.2; Spotify's, 26c §5.8). */
fun rendererStateText(state: RendererState): Int = when (state) {
    RendererState.GROUPED_AWAY -> R.string.renderer_grouped_away
    RendererState.TAKEN_OVER -> R.string.renderer_taken_over
    RendererState.LOST_SESSION -> R.string.renderer_lost_session
    RendererState.UNREACHABLE -> R.string.renderer_unreachable
    RendererState.SPOTIFY_NEEDS_WIFI -> R.string.renderer_spotify_needs_wifi
    RendererState.SPOTIFY_NEEDS_INTERNET -> R.string.renderer_spotify_needs_internet
    RendererState.SPOTIFY_UNAVAILABLE -> R.string.renderer_spotify_unavailable
    RendererState.SPOTIFY_ASK_ADULT -> R.string.renderer_spotify_ask_adult
    RendererState.SPOTIFY_NOT_ON_PHONE -> R.string.renderer_spotify_not_on_phone
    RendererState.SPOTIFY_ELSEWHERE -> R.string.renderer_spotify_elsewhere
    RendererState.SPOTIFY_STOPPED -> R.string.renderer_spotify_stopped
}

@Composable
fun outputName(output: Output): String = when (output.kind) {
    OutputKind.PHONE -> stringResource(R.string.output_phone)
    OutputKind.BLUETOOTH -> output.name ?: stringResource(R.string.output_bluetooth)
    OutputKind.WIRED -> output.name ?: stringResource(R.string.output_wired)
    OutputKind.SONOS -> output.name ?: stringResource(R.string.output_sonos)
}

/**
 * "Sov om": 15 / 30 / 45 min / Av, and "Til slutten" (design 50 §1.2) as a full-width row under them - only for an
 * item with a duration ([offerAtEnd]; live, or a context before its first state, has none).
 */
@Composable
fun SleepSheet(current: Int?, atEnd: Boolean, offerAtEnd: Boolean, onPick: (Int) -> Unit, onAtEnd: () -> Unit, onDismiss: () -> Unit) {
    Sheet(stringResource(R.string.sleep_title), onDismiss, 12.dp) {
        val options = listOf(15, 30, 45, 0)
        for (row in options.chunked(2)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                for (m in row) {
                    val on = !atEnd && (current ?: 0) == m
                    SleepOption(if (m == 0) stringResource(R.string.sleep_off) else stringResource(R.string.minutes, m), on, Modifier.weight(1f)) { onPick(m) }
                }
            }
        }
        if (offerAtEnd || atEnd) SleepOption(stringResource(R.string.sleep_at_end), atEnd, Modifier.fillMaxWidth(), onAtEnd)
    }
}

@Composable
private fun SleepOption(label: String, on: Boolean, modifier: Modifier, onClick: () -> Unit) {
    Box(
        modifier.height(52.dp).clip(RoundedCornerShape(16.dp)).background(if (on) Palette.peach else Palette.track)
            .semantics { selected = on }.button(null, onClick),
        contentAlignment = Alignment.Center,
    ) {
        Line(label, 16, 800, if (on) Palette.bg else Palette.fg)
    }
}

/**
 * "Spill av på" (design 26a §5.1): the phone and the connected Bluetooth or wired outputs, then the
 * Sonos rooms (a group is one row, "Kjøkken + Stua"; "Sonos · svarer ikke" while nobody answered),
 * then "Se etter høyttalere" ("Søker etter høyttalere…" while a round runs). Opening it asks for one
 * topology refresh ([onOpen]); picking a room is a play on it.
 */
@Composable
fun OutputSheet(
    outputs: List<Output>,
    rooms: RoomsView,
    current: Output,
    onOpen: () -> Unit,
    onPick: (Output) -> Unit,
    onRescan: () -> Unit,
    onDismiss: () -> Unit,
) {
    LaunchedEffect(Unit) { onOpen() }
    Sheet(stringResource(R.string.output), onDismiss, 8.dp) {
        for (o in outputs) {
            val sub = when (o.kind) {
                OutputKind.PHONE -> R.string.output_phone_sub
                OutputKind.BLUETOOTH -> R.string.output_bluetooth_sub
                OutputKind.WIRED -> R.string.output_wired_sub
                OutputKind.SONOS -> R.string.output_sonos
            }
            val glyph = when (o.kind) {
                OutputKind.PHONE -> Icons.speakerSmall
                OutputKind.SONOS -> Icons.roomSpeaker
                OutputKind.BLUETOOTH, OutputKind.WIRED -> Icons.headphones
            }
            SheetRow(glyph, outputName(o), stringResource(sub), o.key == current.key) { onPick(o) }
        }
        for (room in rooms.rooms) {
            val o = Output(Output.sonosKey(room.uid), OutputKind.SONOS, room.label)
            SheetRow(Icons.roomSpeaker, room.label, stringResource(if (rooms.stale) R.string.output_sonos_stale else R.string.output_sonos), o.key == current.key) { onPick(o) }
        }
        if (rooms.scanning) {
            Row(
                Modifier.fillMaxWidth().heightIn(min = 52.dp).padding(horizontal = 14.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Box(Modifier.size(34.dp), contentAlignment = Alignment.Center) { Spinner(20.dp, Palette.sub) }
                Line(stringResource(R.string.rooms_scanning), 15, 800, Palette.sub, Modifier.weight(1f))
            }
        } else {
            SheetRow(Icons.search, stringResource(R.string.rooms_rescan), null, selected = false, onClick = onRescan)
        }
        Spacer(Modifier.width(0.dp))
    }
}

@Composable
private fun SheetRow(glyph: ImageVector, title: String, subtitle: String?, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 52.dp).clip(RoundedCornerShape(16.dp)).background(if (selected) Palette.selected else Palette.sheetRow)
            .semantics { this.selected = selected }.button(null, onClick).padding(horizontal = 14.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(Modifier.size(34.dp).clip(CircleShape).background(if (selected) Palette.peach else Palette.track), contentAlignment = Alignment.Center) {
            Glyph(glyph, 18.dp, if (selected) Palette.bg else Palette.fg)
        }
        Column(Modifier.weight(1f)) {
            Line(title, 15, 800, Palette.white)
            subtitle?.let { Line(it, 12, 700, Palette.sub) }
        }
        if (selected) Glyph(Icons.check, 22.dp, Palette.peach)
    }
}

/** A turning arc (Compose foundation has no progress indicator; no Material here). */
@Composable
private fun Spinner(size: Dp, color: Color) {
    val turn by rememberInfiniteTransition(label = "spinner").animateFloat(
        0f, 360f, infiniteRepeatable(tween(900, easing = LinearEasing), RepeatMode.Restart), label = "turn",
    )
    Canvas(Modifier.size(size).rotate(turn)) {
        drawArc(color, 0f, 270f, useCenter = false, style = Stroke(width = 2.5.dp.toPx(), cap = StrokeCap.Round))
    }
}
