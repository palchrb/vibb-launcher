package me.vibb.music.ui

import me.vibb.music.R
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.vectorResource
import android.content.Context
import android.graphics.BitmapFactory
import android.os.SystemClock
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.vibb.music.data.Store
import me.vibb.music.logic.DecodedCover
import me.vibb.music.logic.MissingCover
import me.vibb.music.logic.coverShown
import me.vibb.music.logic.formatDuration
import me.vibb.music.logic.shouldDecode
import me.vibb.music.model.Category
import kotlin.math.abs

/** A tinted icon. */
@Composable
fun Glyph(icon: ImageVector, size: Dp, tint: Color, modifier: Modifier = Modifier) {
    Image(icon, contentDescription = null, modifier = modifier.size(size), colorFilter = ColorFilter.tint(tint))
}

/** A clickable that reads as a button to TalkBack. */
fun Modifier.button(label: String?, onClick: () -> Unit): Modifier =
    this.semantics { if (label != null) contentDescription = label }.clickable(role = Role.Button, onClick = onClick)

/**
 * The kid's heart (design 50 §1.5): Material Symbols `favorite`, peach when set, quiet when not; [size] is the touch
 * target, [glyph] the heart. TalkBack: "Favoritt", selected or not.
 */
@Composable
fun HeartButton(on: Boolean, size: Dp, glyph: Dp, onToggle: (Boolean) -> Unit) {
    val label = stringResource(R.string.favourite)
    Box(
        Modifier.requiredSize(size).clip(CircleShape).semantics { selected = on }.button(label) { onToggle(!on) },
        contentAlignment = Alignment.Center,
    ) {
        Glyph(ImageVector.vectorResource(R.drawable.player_glyph_favorite), glyph, if (on) Palette.peach else Palette.dim)
    }
}

/** The mockup's round button (44 dp card circle by default): always [size] square, never squeezed
 * into an oval - its row must leave it the room (Fit.kt). */
@Composable
fun RoundButton(
    icon: ImageVector,
    label: String?,
    onClick: () -> Unit,
    size: Dp = 44.dp,
    iconSize: Dp = 22.dp,
    bg: Color = Palette.card,
    fg: Color = Palette.fg,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier.requiredSize(size).clip(CircleShape).background(bg).button(label, onClick),
        contentAlignment = Alignment.Center,
    ) { Glyph(icon, iconSize, fg) }
}

/** A text line in the mockup's type (Nunito). */
@Composable
fun Line(text: String, size: Int, weight: Int, color: Color, modifier: Modifier = Modifier, maxLines: Int = 1, center: Boolean = false) {
    val style = text(size.sp, weight, color).let { if (center) it.copy(textAlign = TextAlign.Center) else it }
    BasicText(text, modifier, style = style, maxLines = maxLines, overflow = TextOverflow.Ellipsis)
}

/** Decoded covers, counted in bytes (QA L3: a 512 px picture is 1 MiB, and the pager composes five). */
private val bitmaps = object : LruCache<String, ImageBitmap>(16 * 1024 * 1024) {
    override fun sizeOf(key: String, value: ImageBitmap): Int = value.width * value.height * 4
}

/** What a cover slot holds: the image decoded for a hash, or that the file for one wasn't there. */
private data class CoverSlot(val decoded: DecodedCover<ImageBitmap>?, val missing: MissingCover?)

/**
 * A cover file (512 px JPEG) decoded off the main thread, cached. The state outlives a change of
 * [hash] (produceState remembers it unkeyed), so it holds the hash it was decoded for and shows only
 * for that one ([coverShown]): from podcast A to B the player showed A's cover on. A file that isn't
 * there yet is looked for again when a cover lands ([Store.coversVersion], QA M5).
 */
@Composable
fun coverBitmap(hash: String?, decodePx: Int? = null): ImageBitmap? {
    val context = LocalContext.current
    val version by Store.get(context).coversVersion.collectAsState()
    // A row's picture (design 39 §2.3) has its own slot and cache, keyed on hash and size.
    val key = hash?.let { if (decodePx == null) it else "$it@$decodePx" }
    val cached = key?.let { if (decodePx == null) bitmaps.get(it) else thumbs.get(it) }
    val state = produceState(CoverSlot(null, null), key, version) {
        if (hash == null || key == null || !shouldDecode(key, value.decoded, value.missing, version)) return@produceState
        val image = if (decodePx == null) loadCover(context, hash) else loadThumb(context, hash, decodePx)
        value = if (image != null) CoverSlot(DecodedCover(key, image), null) else value.copy(missing = MissingCover(key, version))
    }
    return coverShown(key, cached, state.value.decoded)
}

/** The list rows' pictures (design 39 §2.3), decoded small: a second cache beside [bitmaps], 2 MiB. */
private val thumbs = object : LruCache<String, ImageBitmap>(2 * 1024 * 1024) {
    override fun sizeOf(key: String, value: ImageBitmap): Int = value.width * value.height * 4
}

/**
 * A row's picture decoded off the main thread at [px] (`inSampleSize`, [thumbSampleSize]: the file is never decoded
 * whole first), cached in [thumbs] as `"$hash@$px"`; null when there is no file. No small session copy is made.
 */
private suspend fun loadThumb(context: Context, hash: String, px: Int): ImageBitmap? = thumbs.get("$hash@$px") ?: withContext(Dispatchers.IO) {
    val file = Store.get(context).coverFile(hash)
    if (!file.exists()) return@withContext null
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(file.path, bounds)
    val options = BitmapFactory.Options().apply { inSampleSize = thumbSampleSize(minOf(bounds.outWidth, bounds.outHeight), px) }
    BitmapFactory.decodeFile(file.path, options)?.asImageBitmap()?.also { thumbs.put("$hash@$px", it) }
}

/**
 * A cover from memory, else decoded (off the main thread) into it; null when there is no file.
 * Decoded at the largest size a cover is shown ([coverDecodePx]: the player's 224 dp), scaled while
 * decoding, never above the file's 512 px: at density 1.5 (the Jelly Star) a picture decodes at
 * 336 px, under half the memory of 512 (QA L3, code QA L12).
 */
private suspend fun loadCover(context: Context, hash: String): ImageBitmap? = bitmaps.get(hash) ?: withContext(Dispatchers.IO) {
    val file = Store.get(context).coverFile(hash)
    if (!file.exists()) return@withContext null
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(file.path, bounds)
    val side = minOf(bounds.outWidth, bounds.outHeight)
    val target = coverDecodePx(side, context.resources.displayMetrics.density)
    val options = BitmapFactory.Options().apply {
        if (target < side) {
            inScaled = true
            inDensity = side
            inTargetDensity = target
        }
    }
    BitmapFactory.decodeFile(file.path, options)?.asImageBitmap()?.also { bitmaps.put(hash, it) }
}

/** Decodes [hashes] ahead (the covers next and previous go to), so they show on their first frame. */
@Composable
fun PrefetchCovers(hashes: List<String>) {
    val context = LocalContext.current
    LaunchedEffect(hashes) { for (h in hashes) loadCover(context, h) }
}

/** A category's tile colour (the parent's pick; peach when unknown). */
fun categoryColor(category: Category?): Color =
    category?.color?.let { MusicGlyphs.COLORS[it] }?.let { Color(it) } ?: Palette.peach

/** A category's glyph (music_note when unknown). */
fun categoryGlyph(category: Category?): Int =
    category?.icon?.let { MusicGlyphs.ICONS[it] } ?: MusicGlyphs.ICONS.getValue("music_note")

/**
 * A cover: the image when there is one, else [fallback]'s (an episode's picture not on the phone yet,
 * or still decoding, shows the series cover - never the glyph, vibb `addf84eb`), else the mockup's
 * gradient in the category colour with the category glyph ([glyph] dp; 0 = none).
 */
@Composable
fun Cover(
    hash: String?,
    category: Category?,
    radius: Dp,
    glyph: Dp,
    modifier: Modifier = Modifier,
    fallback: String? = null,
    /** A list row's picture (design 39 §2.3): decoded for this size, in its own slot; null = the player's size. */
    decodeDp: Dp? = null,
    content: @Composable () -> Unit = {},
) {
    val decodePx = decodeDp?.let { with(LocalDensity.current) { it.roundToPx() } }
    val own = coverBitmap(hash, decodePx)
    val other = coverBitmap(fallback.takeIf { it != hash }, decodePx)
    val bitmap = own ?: other
    val color = categoryColor(category)
    Box(
        modifier.clip(RoundedCornerShape(radius))
            .background(Brush.linearGradient(listOf(color, lerp(color, Palette.bg, 0.8f)))),
        contentAlignment = Alignment.Center,
    ) {
        if (bitmap != null) {
            Image(bitmap, contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        } else if (glyph > 0.dp) {
            Image(painterResource(categoryGlyph(category)), contentDescription = null, modifier = Modifier.size(glyph), colorFilter = ColorFilter.tint(Palette.fg))
        }
        content()
    }
}

/**
 * The seek line (design 20: a thin 4 dp line, a 26 dp thumb, 44 dp to touch): the times follow
 * the finger, the seek happens on release, and the thumb stays where it was let go until the player
 * gets there (or a second passes). TalkBack sees a slider.
 */
@Composable
fun SeekBar(positionMs: Long, durationMs: Long?, label: String, onSeek: (Long) -> Unit) {
    var dragFrac by remember { mutableStateOf<Float?>(null) }
    var released by remember { mutableStateOf<Pair<Long, Long>?>(null) } // (target ms, when)
    val duration = durationMs?.takeIf { it > 0 }
    val held = released?.takeIf { (target, at) -> abs(positionMs - target) > 1_000 && SystemClock.elapsedRealtime() - at < 1_000 }?.first
    val shownMs = held ?: positionMs
    val frac = dragFrac ?: if (duration != null) (shownMs.toFloat() / duration).coerceIn(0f, 1f) else 0f
    val seek = { ms: Long ->
        released = ms to SystemClock.elapsedRealtime()
        onSeek(ms)
    }
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        BoxWithConstraints(
            Modifier.fillMaxWidth().height(44.dp)
                .semantics {
                    contentDescription = label
                    progressBarRangeInfo = ProgressBarRangeInfo(frac, 0f..1f)
                    if (duration != null) {
                        setProgress { f ->
                            seek((f.coerceIn(0f, 1f) * duration).toLong())
                            true
                        }
                    }
                }
                .pointerInput(duration) {
                    if (duration == null) return@pointerInput
                    detectHorizontalDragGestures(
                        onDragStart = { dragFrac = (it.x / size.width).coerceIn(0f, 1f) },
                        onDragEnd = { dragFrac?.let { f -> seek((f * duration).toLong()) }; dragFrac = null },
                        onDragCancel = { dragFrac = null },
                    ) { change, _ -> dragFrac = (change.position.x / size.width).coerceIn(0f, 1f) }
                }
                .pointerInput(duration) {
                    if (duration == null) return@pointerInput
                    detectTapGestures { seek(((it.x / size.width).coerceIn(0f, 1f) * duration).toLong()) }
                },
        ) {
            val width = maxWidth
            Box(Modifier.offset(y = 20.dp).fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)).background(Palette.track))
            Box(Modifier.offset(y = 20.dp).width(width * frac).height(4.dp).clip(RoundedCornerShape(2.dp)).background(Palette.peach))
            Box(
                Modifier.offset(x = (width - 26.dp) * frac, y = 9.dp).size(26.dp).clip(CircleShape)
                    .background(Palette.fg).border(5.dp, Palette.peach, CircleShape),
            )
        }
        Row(Modifier.fillMaxWidth()) {
            val shown = if (duration != null) (frac * duration).toLong() else positionMs
            Line(formatDuration(shown), 12, 700, Palette.sub)
            Spacer(Modifier.weight(1f))
            Line(formatDuration(duration), 12, 700, Palette.sub)
        }
    }
}

/** A bottom sheet over a scrim (the mockup's "Sov om" and "Spill av på"); a tap on the scrim closes it. */
@Composable
fun Sheet(title: String, onDismiss: () -> Unit, gap: Dp, content: @Composable () -> Unit) {
    Box(Modifier.fillMaxSize()) {
        Box(
            Modifier.fillMaxSize().background(Palette.scrim)
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onDismiss),
        )
        Column(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                .clip(RoundedCornerShape(topStart = 26.dp, topEnd = 26.dp)).background(Palette.card)
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {}
                .padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 20.dp),
            verticalArrangement = Arrangement.spacedBy(gap),
        ) {
            Box(Modifier.align(Alignment.CenterHorizontally).width(40.dp).height(5.dp).clip(RoundedCornerShape(3.dp)).background(Palette.dot))
            Line(title, 18, 800, Palette.white, Modifier.align(Alignment.CenterHorizontally))
            content()
        }
    }
}

/** The empty states' disc and its glyph (design 34 P10 a), dp. */
const val EMPTY_DISC_DP = 104
const val EMPTY_GLYPH_DP = 40

/**
 * An empty state (design 34 P10 a): a [EMPTY_DISC_DP] dp night disc with [icon] in peach and P2's soft shadow, the
 * [headline] and an optional [body] under it, centred in [modifier]'s room ([centred]) or from its top (search, above
 * the keyboard). The disc is decorative (QA L7: no description, the headline is the text); TalkBack reads the texts as
 * one. It scrolls only when a large font makes it taller than its room; nothing in it moves on its own.
 */
@Composable
fun EmptyState(icon: ImageVector, headline: String, body: String?, modifier: Modifier = Modifier, centred: Boolean = true) {
    BoxWithConstraints(modifier) {
        // every caller gives it a weight (a bounded height); the guard keeps an unbounded one from throwing (N4)
        // (a scroll inside a scrolling parent throws too, so both go only on a bounded height)
        val scroll = rememberScrollState()
        val fill = if (constraints.hasBoundedHeight) Modifier.verticalScroll(scroll).heightIn(min = maxHeight) else Modifier
        Column(
            Modifier.fillMaxWidth().then(fill)
                .padding(horizontal = 16.dp, vertical = if (centred) 0.dp else 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = if (centred) Arrangement.Center else Arrangement.Top,
        ) {
            Box(
                Modifier.size(EMPTY_DISC_DP.dp).shadow(BAR_ELEVATION_DP.dp, CircleShape).background(Palette.card),
                contentAlignment = Alignment.Center,
            ) { Glyph(icon, EMPTY_GLYPH_DP.dp, Palette.peach) }
            Column(
                Modifier.fillMaxWidth().padding(top = 20.dp).semantics(mergeDescendants = true) {},
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Line(headline, 19, 800, Palette.fg, Modifier.fillMaxWidth(), maxLines = Int.MAX_VALUE, center = true)
                if (body != null) Line(body, 15, 700, Palette.dim, Modifier.fillMaxWidth(), maxLines = Int.MAX_VALUE, center = true)
            }
        }
    }
}
