package me.vibb.music.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import me.vibb.music.R
import me.vibb.music.logic.Kind
import me.vibb.music.logic.kindOf
import me.vibb.music.playback.Output
import me.vibb.music.playback.OutputKind

/**
 * The player (design 21 §4.2, `Main.dc.html` v12 `onPlayer`): back + entry title, the cover
 * (it shrinks to fit the Jelly Star), the item's marquee title, "Episode|Spor N av M", the seek
 * line, [shuffle] previous / play-pause / next [slot] (v15's compact sizes on a 320 dp phone, [controlRow]),
 * then Liste / Sov / "Spill av på".
 */
@Composable
fun PlayerScreen(
    now: NowView,
    /** The item's number in the list's order. */
    number: Int,
    of: Int,
    positionMs: Long,
    sleepMinutes: Int?,
    output: Output,
    onBack: () -> Unit,
    onSeek: (Long) -> Unit,
    onShuffle: () -> Unit,
    onPrevious: () -> Unit,
    onTogglePlay: () -> Unit,
    onNext: () -> Unit,
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
            Line(now.entry.name, 15, 800, Palette.sub, Modifier.weight(1f).padding(end = 44.dp), center = true)
        }
        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            Cover(
                now.item.artHash ?: now.entry.cover, now.category, 26.dp, 104.dp,
                Modifier.sizeIn(maxWidth = 224.dp, maxHeight = 224.dp).aspectRatio(1f).shadow(12.dp, RoundedCornerShape(26.dp)),
            )
        }
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Line(now.item.title, 20, 800, Palette.white, Modifier.basicMarquee(iterations = Int.MAX_VALUE))
            Line(stringResource(if (kindOf(now.entry) == Kind.OWN) R.string.track_of else R.string.episode_of, number, of), 13, 700, Palette.sub)
        }
        SeekBar(positionMs, now.durationMs, stringResource(R.string.seek), onSeek)
        // v12's sizes where they fit, v15's compact ones on a 320 dp phone: always round, play centred.
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val row = controlRow(maxWidth.value)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(row.gap.dp, Alignment.CenterHorizontally), verticalAlignment = Alignment.CenterVertically) {
                if (now.canShuffle) {
                    RoundButton(
                        Icons.shuffle, stringResource(R.string.shuffle), onShuffle, row.side.dp, 20.dp,
                        if (now.shuffled) Palette.peach else Palette.card, if (now.shuffled) Palette.bg else Palette.fg,
                        Modifier.semantics { selected = now.shuffled },
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
                Spacer(Modifier.requiredSize(row.side.dp))
            }
        }
        // Liste and Sov are never cut ("Sov · 30 min"); the output's name gives way (the mockup's flex).
        FlexBar(listOf(1f, 1f, 1.2f), shrinks = listOf(false, false, true), gap = 8.dp) {
            BarButton(Icons.list, stringResource(R.string.list), null, Modifier, onClick = onList)
            BarButton(Icons.moon, sleepMinutes?.let { stringResource(R.string.sleep_on, it) } ?: stringResource(R.string.sleep), null, Modifier, onClick = onSleep)
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

fun outputIcon(output: Output): ImageVector = if (output.kind == OutputKind.PHONE) Icons.speaker else Icons.headphones

@Composable
fun outputName(output: Output): String = when (output.kind) {
    OutputKind.PHONE -> stringResource(R.string.output_phone)
    OutputKind.BLUETOOTH -> output.name ?: stringResource(R.string.output_bluetooth)
    OutputKind.WIRED -> output.name ?: stringResource(R.string.output_wired)
}

/** "Sov om": 15 / 30 / 45 min / Av. */
@Composable
fun SleepSheet(current: Int?, onPick: (Int) -> Unit, onDismiss: () -> Unit) {
    Sheet(stringResource(R.string.sleep_title), onDismiss, 12.dp) {
        val options = listOf(15, 30, 45, 0)
        for (row in options.chunked(2)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                for (m in row) {
                    val on = (current ?: 0) == m
                    Box(
                        Modifier.weight(1f).height(52.dp).clip(RoundedCornerShape(16.dp)).background(if (on) Palette.peach else Palette.track)
                            .semantics { selected = on }.button(null) { onPick(m) },
                        contentAlignment = Alignment.Center,
                    ) {
                        Line(if (m == 0) stringResource(R.string.sleep_off) else stringResource(R.string.minutes, m), 16, 800, if (on) Palette.bg else Palette.fg)
                    }
                }
            }
        }
    }
}

/** "Spill av på": the phone and the connected Bluetooth or wired outputs (Sonos is phase 1b). */
@Composable
fun OutputSheet(outputs: List<Output>, current: Output, onPick: (Output) -> Unit, onDismiss: () -> Unit) {
    Sheet(stringResource(R.string.output), onDismiss, 8.dp) {
        for (o in outputs) {
            val on = o.id == current.id
            Row(
                Modifier.fillMaxWidth().heightIn(min = 52.dp).clip(RoundedCornerShape(16.dp)).background(if (on) Palette.selected else Palette.sheetRow)
                    .semantics { selected = on }.button(null) { onPick(o) }.padding(horizontal = 14.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Box(Modifier.size(34.dp).clip(CircleShape).background(if (on) Palette.peach else Palette.track), contentAlignment = Alignment.Center) {
                    Glyph(if (o.kind == OutputKind.PHONE) Icons.speakerSmall else Icons.headphones, 18.dp, if (on) Palette.bg else Palette.fg)
                }
                Column(Modifier.weight(1f)) {
                    Line(outputName(o), 15, 800, Palette.white)
                    Line(
                        stringResource(
                            when (o.kind) {
                                OutputKind.PHONE -> R.string.output_phone_sub
                                OutputKind.BLUETOOTH -> R.string.output_bluetooth_sub
                                OutputKind.WIRED -> R.string.output_wired_sub
                            },
                        ),
                        12, 700, Palette.sub,
                    )
                }
                if (on) Glyph(Icons.check, 22.dp, Palette.peach)
            }
        }
        Spacer(Modifier.width(0.dp))
    }
}
