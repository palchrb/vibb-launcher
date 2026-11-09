package me.vibb.music.ui

import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import me.vibb.music.R
import me.vibb.music.playback.RendererState

/*
 * "Kan ikke spilles nå" on the player's cover (2026-11-09, the user's mockup D): when the loaded item can't play, the
 * cover is greyed and darkened and a calm notice sits on it - a small peach "!" disc, a bold line, a smaller line. Every
 * button stays where it is and keeps working (previous/next/±15 still skip past one broken track); play is greyed only
 * when a tap can't help. A visual change only: what a tap does is the renderer's, as before.
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
 *   for the account and the listening time only: both wait on an adult, a tap meanwhile ends the same way. The others
 *   keep play lit: a tap may work (the network back, a new web player for Widevine, the quota over).
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
    RendererState.SPOTIFY_NOT_ON_PHONE -> CantPlayNotice(R.string.renderer_spotify_not_on_phone, R.string.cant_play_ask_adult, playGreyed = false)
    RendererState.SPOTIFY_NEEDS_WIFI -> CantPlayNotice(R.string.renderer_spotify_needs_wifi, null, playGreyed = false)
    RendererState.SPOTIFY_NEEDS_INTERNET -> CantPlayNotice(R.string.renderer_spotify_needs_internet, null, playGreyed = false)
    RendererState.SPOTIFY_UNAVAILABLE -> CantPlayNotice(R.string.renderer_spotify_unavailable, null, playGreyed = false)
}

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

/**
 * The notice over a greyed cover: the "!" disc in peach at low alpha, [notice]'s bold title, its smaller help line.
 * TalkBack reads the two lines as the cover's description and, as a live region, says them when they appear. Sized for
 * the 150 dp cover of a player with a shuffle row (emulator check, 2026-11-09): 6 dp at the sides leave 138 dp, where
 * "Kan ikke spilles nå" (15 sp, ~132 dp) and "Spør en voksen om hjelp" (12 sp, ~137 dp) fit one line each; a longer
 * line wraps to two.
 */
@Composable
fun CantPlayOverlay(notice: CantPlayNotice, modifier: Modifier = Modifier) {
    val title = stringResource(notice.title)
    val help = notice.help?.let { stringResource(it) }
    val spoken = listOfNotNull(title, help).joinToString(". ")
    Column(
        modifier.fillMaxSize().padding(horizontal = 6.dp, vertical = 8.dp).clearAndSetSemantics {
            contentDescription = spoken
            liveRegion = LiveRegionMode.Polite
        },
        verticalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(Modifier.size(34.dp).clip(CircleShape).background(Palette.peach.copy(alpha = 0.22f)), contentAlignment = Alignment.Center) {
            BasicText("!", style = text(17.sp, 800, Palette.peach))
        }
        Spacer(Modifier.height(2.dp))
        Line(title, 15, 800, Palette.white, maxLines = 2, center = true)
        help?.let { Line(it, 12, 700, Palette.tileFg, maxLines = 2, center = true) }
    }
}
