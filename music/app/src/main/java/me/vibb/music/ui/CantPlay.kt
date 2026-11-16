package me.vibb.music.ui

import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import me.vibb.music.R
import me.vibb.music.logic.carouselIndex
import me.vibb.music.playback.OutputKind
import me.vibb.music.playback.RendererState

/*
 * "Kan ikke spilles nå" on the player's cover (2026-11-09, the user's mockup D): when the loaded item can't play, the
 * cover is greyed and darkened and a calm notice sits on it - a small peach "!" disc, a bold line, a smaller line. Every
 * button stays where it is and keeps working (previous/next/±15 still skip past one broken track); play is greyed only
 * when a tap can't help. A visual change only: what a tap does is the renderer's, as before. A retry from a refusal
 * keeps the notice until the attempt plays or ends otherwise ([holdRefusal]: no flash back to colour per tap).
 */

/**
 * The notice on the cover for a renderer that can't play the loaded item: its bold [title], its smaller [help] line
 * (none: the title says it all), and whether play is greyed ([playGreyed]: only when a new tap can't help).
 */
data class CantPlayNotice(@StringRes val title: Int, @StringRes val help: Int?, val playGreyed: Boolean)

/**
 * The cover's notice for [state], or null for today's quiet line under the entry name.
 *
 * - The notice: the web player's refusals of the item on this phone - the account ("Kan ikke spilles nå" / "Spør en
 *   voksen om hjelp"), no listening time for the book, no Widevine, no Wi-Fi, no internet, the quota. Play is greyed
 *   for the account, the listening time and the phone without Widevine (the user, 2026-11-09: "play knapp skal være grå
 *   når den er i virker ikke modus!"): each waits on an adult or an update, a tap meanwhile ends the same way. The
 *   others keep play lit: a tap may work (the network back, the quota over).
 * - The line: a room's four (the speaker, not the item: grouped away, taken over, its session lost, not answering - a
 *   tap or the next poll may bring it back, and design 50 §1.4 never dims anything on a room), "Spilles et annet
 *   sted" (the account plays elsewhere: a tap takes it back) and "Stoppet - trykk spill" (a tap is the fix).
 */
fun cantPlayNotice(state: RendererState?): CantPlayNotice? = when (state) {
    null,
    RendererState.GROUPED_AWAY,
    RendererState.TAKEN_OVER,
    RendererState.LOST_SESSION,
    RendererState.UNREACHABLE,
    RendererState.SPOTIFY_ELSEWHERE,
    RendererState.SPOTIFY_STOPPED,
    -> null
    RendererState.SPOTIFY_ASK_ADULT -> CantPlayNotice(R.string.cant_play_now, R.string.cant_play_ask_adult, playGreyed = true)
    RendererState.SPOTIFY_NO_LISTENING_TIME ->
        CantPlayNotice(R.string.renderer_spotify_no_listening_time, R.string.cant_play_ask_adult, playGreyed = true)
    RendererState.SPOTIFY_NOT_ON_PHONE -> CantPlayNotice(R.string.renderer_spotify_not_on_phone, R.string.cant_play_ask_adult, playGreyed = true)
    RendererState.SPOTIFY_NEEDS_WIFI -> CantPlayNotice(R.string.renderer_spotify_needs_wifi, null, playGreyed = false)
    RendererState.SPOTIFY_NEEDS_INTERNET -> CantPlayNotice(R.string.renderer_spotify_needs_internet, null, playGreyed = false)
    RendererState.SPOTIFY_UNAVAILABLE -> CantPlayNotice(R.string.renderer_spotify_unavailable, null, playGreyed = false)
}

/** Play is greyed (the notice's own rule, [CantPlayNotice.playGreyed]); still a button with the same tap. */
fun playOff(notice: CantPlayNotice?): Boolean = notice?.playGreyed == true

/**
 * Play shows the pause glyph (and says "Pause"): while [playing] - but never greyed (qa-music-cant-play-2 N2: during a
 * held retry `playing` follows the attempt, and a grey pause would read as a working player). A tap does what it did.
 */
fun showsPause(playing: Boolean, notice: CantPlayNotice?): Boolean = playing && !playOff(notice)

/**
 * A refusal held over a retry (qa-music-cant-play M3): the web player clears its line at every start and sets it again
 * only when the start fails, so a tap on a refused item would turn the cover back to colour and play to peach for the
 * length of the attempt - a flash per tap, the blinking the approval ruled out. [state] is the last refusal of the run
 * [run] (`PlayerHub.State.entryId`: the queue the attempt is in).
 */
data class HeldRefusal(val state: RendererState, val run: Long?)

/**
 * The refusals a retry holds (qa-music-cant-play-2 V2): those that grey play - the account's, the book's and the phone
 * without Widevine (2026-11-09) - which arrive only after an attempt (the start clears the line first). The network
 * lines come from the gate before any start and never flash; held, a stale "Trenger Wi-Fi" would stay over a good
 * retry's connect.
 */
fun heldOverRetry(state: RendererState): Boolean = playOff(cantPlayNotice(state))

/**
 * The phone's web player is starting (qa-music-cant-play-2 V1): `PlayerHub.State.connecting` while the output isn't a
 * Sonos room - on a room `connecting` is the speaker's connect, a renderer the held refusal isn't of.
 */
fun webPlayerStarting(connecting: Boolean, output: OutputKind): Boolean = connecting && output != OutputKind.SONOS

/**
 * The next [HeldRefusal] from the renderer's [state], [webStarting] (the phone's web player is starting: the player's
 * `connecting` off a room - qa-music-cant-play-2 V1, never a Sonos room's connect, a renderer the refusal isn't of) and
 * the [run]: a [heldOverRetry] refusal is held; a moment without a line keeps it only while an attempt in the same run
 * is starting; a real playing state (the start over), another line, or another run ends it. Next and previous in the
 * run keep it too - an account's or a book's refusal is the run's, and the next item's start says otherwise by playing.
 */
fun holdRefusal(held: HeldRefusal?, state: RendererState?, webStarting: Boolean, run: Long?): HeldRefusal? = when {
    state != null -> if (heldOverRetry(state)) HeldRefusal(state, run) else null
    webStarting && held != null && held.run == run -> held
    else -> null
}

/** The state the player shows: the renderer's own, else the refusal held over its retry. */
fun shownState(held: HeldRefusal?, state: RendererState?): RendererState? = state ?: held?.state

/*
 * The notice in the cover's real box (qa-music-cant-play M1, M2). The cover is 224 dp at most on a 320 dp phone (more on
 * a wider one, S10's playerArtMax) and shrinks with the room
 * (a large font or display size; the carousel's pages down to PLAYER_ART_MIN_DP = 96): the text is set in dp, never the
 * font scale's sp (as SKIP_NUMBER_DP: a fixed box), the title may take three lines, and what doesn't fit goes - the
 * disc first, then the help line, then the title (the "!" alone). The overlay measures its own words in its own box
 * and asks [noticeFit]; the line under the entry name comes back while the notice can't say it all ([headerLineText]).
 */

/** The notice's sizes (dp): the disc, the gap between its parts, the title's, the help's and the "!"'s text. */
const val NOTICE_DISC_DP = 34f
const val NOTICE_GAP_DP = 4f
const val NOTICE_TITLE_DP = 15f
const val NOTICE_HELP_DP = 12f
const val NOTICE_MARK_DP = 17f
const val NOTICE_TITLE_LINES = 3
const val NOTICE_HELP_LINES = 2

/** The notice's padding in the cover (dp): at each side, above and below. */
const val NOTICE_PAD_X_DP = 6f
const val NOTICE_PAD_Y_DP = 8f

/** What of the notice shows in its box: the disc, the title, the help line. */
data class NoticeFit(val disc: Boolean, val title: Boolean, val help: Boolean) {
    /** Every word of the notice shows ([hasHelp]: the notice has a help line). */
    fun complete(hasHelp: Boolean): Boolean = title && (help || !hasHelp)
}

/**
 * What fits [innerHeightDp] (the cover less [NOTICE_PAD_Y_DP] twice): [titleHeightDp] is the title's height at the
 * box's width in at most [NOTICE_TITLE_LINES] lines (infinite: it needs more), [helpHeightDp] the help line's in at
 * most [NOTICE_HELP_LINES] (null: the notice has none; infinite: it needs more). All of it, else without the disc,
 * else the title alone (with the disc when no help line can show anyway), else the disc alone ("!") when it fits.
 */
fun noticeFit(innerHeightDp: Float, titleHeightDp: Float, helpHeightDp: Float?): NoticeFit {
    fun fits(vararg parts: Float) = parts.sum() + NOTICE_GAP_DP * (parts.size - 1) <= innerHeightDp
    val t = titleHeightDp
    val h = helpHeightDp
    return when {
        h != null && fits(NOTICE_DISC_DP, t, h) -> NoticeFit(disc = true, title = true, help = true)
        h != null && fits(t, h) -> NoticeFit(disc = false, title = true, help = true)
        (h == null || h.isInfinite()) && fits(NOTICE_DISC_DP, t) -> NoticeFit(disc = true, title = true, help = false)
        fits(t) -> NoticeFit(disc = false, title = true, help = false)
        else -> NoticeFit(disc = innerHeightDp >= NOTICE_DISC_DP, title = false, help = false)
    }
}

/**
 * The quiet line under the entry name for [state], as a string, or null for none: always without a notice (the state's
 * line); with one only while the notice can't say it all ([fit]: the overlay's last measure, null before it - taken as
 * whole). The title gone: the state's line. The help line gone: the state's line where it says more than the title
 * ("Spør en voksen" under "Kan ikke spilles nå"), else the help itself (qa-music-cant-play-2 N4: "Spør en voksen om
 * hjelp" under "Lydboka trenger lyttetid" on a 96 dp page, never lost).
 */
fun headerLineText(state: RendererState?, notice: CantPlayNotice?, fit: NoticeFit?): Int? = when {
    state == null -> null
    notice == null -> rendererStateText(state)
    fit == null || fit.complete(notice.help != null) -> null
    !fit.title -> rendererStateText(state)
    rendererStateText(state) != notice.title -> rendererStateText(state)
    else -> notice.help
}

/**
 * The carousel's page that carries the notice (qa-music-cant-play L2): the page the pager rests on ([settledPage]),
 * while it shows the loaded item ([index] of [n]) - not every page of that item (with two items the pager composes the
 * loaded one twice), and never a neighbour mid-swipe.
 */
fun noticePage(page: Int, settledPage: Int, n: Int, index: Int): Boolean = page == settledPage && carouselIndex(page, n) == index

/**
 * The greyed cover's level (0..1) of a colour [r], [g], [b] (0..1): the mockup's CSS `grayscale(1) brightness(.28)
 * contrast(.8)` - the luminance, times 0.28, then 0.8 around the middle. White ends at 0.324, black at 0.1.
 */
fun coverGreyLevel(r: Float, g: Float, b: Float): Float =
    COVER_CONTRAST * (COVER_BRIGHTNESS * (LUMA_R * r + LUMA_G * g + LUMA_B * b)) + (1f - COVER_CONTRAST) / 2f

const val COVER_BRIGHTNESS = 0.28f
const val COVER_CONTRAST = 0.8f
private const val LUMA_R = 0.2126f
private const val LUMA_G = 0.7152f
private const val LUMA_B = 0.0722f

/** [coverGreyLevel] as a colour matrix (the translation in 0..255, as Android's), alpha kept. */
fun coverGreyMatrix(): FloatArray {
    val k = COVER_CONTRAST * COVER_BRIGHTNESS
    val offset = (1f - COVER_CONTRAST) / 2f * 255f
    val row = floatArrayOf(k * LUMA_R, k * LUMA_G, k * LUMA_B, 0f, offset)
    return row + row + row + floatArrayOf(0f, 0f, 0f, 1f, 0f)
}

/** A colour greyed as the cover's picture ([coverGreyLevel]): the fallback gradient (its glyph is left out). */
fun coverGrey(color: Color): Color = coverGreyLevel(color.red, color.green, color.blue).let { Color(it, it, it, color.alpha) }

/** The greyed cover's picture filter. */
val coverGreyFilter: ColorFilter by lazy { ColorFilter.colorMatrix(ColorMatrix(coverGreyMatrix())) }

/** Play greyed (the mockup's `.play.off`): `Palette.track` behind it, its glyph white at 45 %. */
val PLAY_OFF_FG = Color(0x73FFFFFF)

/** The notice's words for TalkBack: the title, then the help line. */
@Composable
fun noticeSpoken(notice: CantPlayNotice): String =
    listOfNotNull(stringResource(notice.title), notice.help?.let { stringResource(it) }).joinToString(". ")

/**
 * The notice over a greyed cover: the "!" disc in peach at low alpha, [notice]'s bold title, its smaller help line - as
 * much as fits its box ([noticeFit]; [onFit] gets each new answer). [speak]: the single cover - the overlay is its own
 * node, TalkBack reads the notice and, a polite live region, says it when it appears. On a carousel page (a button,
 * which would merge it into its label) the page says it (`EpisodeCarousel`) and the overlay is silent.
 */
@Composable
fun CantPlayOverlay(notice: CantPlayNotice, speak: Boolean, onFit: (NoticeFit) -> Unit, modifier: Modifier = Modifier) {
    val title = stringResource(notice.title)
    val help = notice.help?.let { stringResource(it) }
    val spoken = noticeSpoken(notice)
    val density = LocalDensity.current
    val titleStyle = text(with(density) { NOTICE_TITLE_DP.dp.toSp() }, 800, Palette.white).copy(textAlign = TextAlign.Center)
    val helpStyle = text(with(density) { NOTICE_HELP_DP.dp.toSp() }, 700, Palette.tileFg).copy(textAlign = TextAlign.Center)
    val markStyle = text(with(density) { NOTICE_MARK_DP.dp.toSp() }, 800, Palette.peach)
    val measurer = rememberTextMeasurer()
    val report by rememberUpdatedState(onFit)
    BoxWithConstraints(
        modifier.fillMaxSize().padding(horizontal = NOTICE_PAD_X_DP.dp, vertical = NOTICE_PAD_Y_DP.dp).clearAndSetSemantics {
            if (speak) {
                contentDescription = spoken
                liveRegion = LiveRegionMode.Polite
            }
        },
        contentAlignment = Alignment.Center,
    ) {
        val width = Constraints(maxWidth = constraints.maxWidth)
        fun heightDp(words: String, style: TextStyle, lines: Int): Float {
            val r = measurer.measure(words, style, maxLines = lines, constraints = width)
            return if (r.hasVisualOverflow) Float.POSITIVE_INFINITY else r.size.height / density.density
        }
        val fit = noticeFit(
            maxHeight.value,
            heightDp(title, titleStyle, NOTICE_TITLE_LINES),
            help?.let { heightDp(it, helpStyle, NOTICE_HELP_LINES) },
        )
        LaunchedEffect(fit) { report(fit) }
        Column(
            verticalArrangement = Arrangement.spacedBy(NOTICE_GAP_DP.dp, Alignment.CenterVertically),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            if (fit.disc) {
                Box(Modifier.size(NOTICE_DISC_DP.dp).clip(CircleShape).background(Palette.peach.copy(alpha = 0.22f)), contentAlignment = Alignment.Center) {
                    BasicText("!", style = markStyle)
                }
            }
            if (fit.title) BasicText(title, style = titleStyle, maxLines = NOTICE_TITLE_LINES, overflow = TextOverflow.Ellipsis)
            if (fit.help && help != null) BasicText(help, style = helpStyle, maxLines = NOTICE_HELP_LINES, overflow = TextOverflow.Ellipsis)
        }
    }
}
