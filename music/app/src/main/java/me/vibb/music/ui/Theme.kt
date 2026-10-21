package me.vibb.music.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import me.vibb.music.R

/** The Vibb night palette of mockup v12 (`Main.dc.html`). */
object Palette {
    val bg = Color(0xFF0C0C14)
    val fg = Color(0xFFFBE4DC)
    val card = Color(0xFF1B1B28)
    val peach = Color(0xFFF0A884)
    val track = Color(0xFF2A2A3A)
    val selected = Color(0xFF2A2233)
    val sheetRow = Color(0xFF14141F)
    val white = Color.White
    val sub = Color(0xC7FBE4DC) // rgba(251,228,220,0.78)
    val dim = Color(0xB8FBE4DC) // 0.72
    val tileFg = Color(0xD9FBE4DC) // 0.85
    val ring = Color(0x40FBE4DC) // 0.25
    val dot = Color(0x59FBE4DC) // 0.35
    val chip = Color(0xB80C0C14) // rgba(12,12,20,0.72)
    val scrim = Color(0x8C000000) // rgba(0,0,0,0.55)
}

/** Nunito (OFL 1.1, assets/licenses/OFL.txt): 600 body, 700 labels, 800 names and titles. */
val Nunito = FontFamily(
    Font(R.font.nunito_semibold, FontWeight.W600),
    Font(R.font.nunito_bold, FontWeight.W700),
    Font(R.font.nunito_extrabold, FontWeight.W800),
)

fun text(size: TextUnit, weight: Int, color: Color) = TextStyle(
    color = color,
    fontSize = size,
    fontWeight = FontWeight(weight),
    fontFamily = Nunito,
)

/**
 * The mockup's line icons (24 px viewBox, as in `Main.dc.html`): drawn as Compose vectors so the
 * app ships no icon font. Circles and rectangles are written as paths.
 */
object Icons {
    private fun circle(cx: Float, cy: Float, r: Float) = "M${cx - r},${cy}a$r,$r 0 1,0 ${2 * r},0a$r,$r 0 1,0 ${-2 * r},0"

    private fun rect(x: Float, y: Float, w: Float, h: Float, r: Float) =
        "M${x + r},${y}h${w - 2 * r}a$r,$r 0 0 1 $r,${r}v${h - 2 * r}a$r,$r 0 0 1 ${-r},${r}h${-(w - 2 * r)}a$r,$r 0 0 1 ${-r},${-r}v${-(h - 2 * r)}a$r,$r 0 0 1 $r,${-r}z"

    private fun stroked(name: String, width: Float, vararg paths: String) = ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f).apply {
        for (d in paths) {
            addPath(
                PathParser().parsePathString(d).toNodes(),
                stroke = SolidColor(Color.White),
                strokeLineWidth = width,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            )
        }
    }.build()

    private fun filled(name: String, vararg paths: String) = ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f).apply {
        for (d in paths) addPath(PathParser().parsePathString(d).toNodes(), fill = SolidColor(Color.White))
    }.build()

    val back = stroked("back", 2.5f, "M15 18l-6-6 6-6")
    val search = stroked("search", 2.2f, circle(11f, 11f, 7f), "M20 20l-3.5-3.5")
    val bell = stroked("bell", 2.2f, "M6 8a6 6 0 0 1 12 0c0 7 3 9 3 9H3s3-2 3-9", "M10.3 21a1.9 1.9 0 0 0 3.4 0")
    val grid = stroked(
        "grid", 2.2f,
        rect(4f, 4f, 7f, 7f, 2f), rect(13f, 4f, 7f, 7f, 2f), rect(4f, 13f, 7f, 7f, 2f), rect(13f, 13f, 7f, 7f, 2f),
    )
    val play = filled("play", "M8 5v14l11-7z")
    val pause = filled("pause", rect(6f, 5f, 4f, 14f, 1f), rect(14f, 5f, 4f, 14f, 1f))
    val previous = filled("previous", "M7 5h2v14H7zM20 5v14L10 12z")
    val next = filled("next", "M15 5h2v14h-2zM4 5v14l10-7z")
    val shuffle = stroked("shuffle", 2.2f, "M16 3h5v5M4 20L21 3M21 16v5h-5M15 15l6 6M4 4l5 5")

    /**
     * Repeat and repeat one (design 49 §1.2, §1.11): the loop of two arrows Spotify's app uses for the meaning, drawn in
     * the row's stroked set beside [shuffle] (not the Material Symbols `repeat`/`repeat_one`: a filled glyph would stand
     * out in this row, as [roomSpeaker] says); repeat one carries its "1" inside the loop.
     */
    val repeat = stroked("repeat", 2.2f, "M17 2l4 4-4 4", "M3 11v-1a4 4 0 0 1 4-4h14", "M7 22l-4-4 4-4", "M21 13v1a4 4 0 0 1-4 4H3")
    val repeatOne = stroked(
        "repeatOne", 2.2f,
        "M17 2l4 4-4 4", "M3 11v-1a4 4 0 0 1 4-4h14", "M7 22l-4-4 4-4", "M21 13v1a4 4 0 0 1-4 4H3", "M10.6 10.4l1.6-1.2v5.6",
    )
    val list = stroked("list", 2.4f, "M8 6h12M8 12h12M8 18h12M4 6h.01M4 12h.01M4 18h.01")
    val moon = stroked("moon", 2.2f, "M20 14.5A8 8 0 0 1 9.5 4 8 8 0 1 0 20 14.5z")
    val speaker = stroked("speaker", 2.2f, "M11 5L6 9H3v6h3l5 4z", "M15.5 8.5a5 5 0 0 1 0 7M18.5 5.5a9 9 0 0 1 0 13")
    val speakerSmall = stroked("speakerSmall", 2.2f, "M11 5L6 9H3v6h3l5 4z", "M15.5 8.5a5 5 0 0 1 0 7")
    val headphones = stroked("headphones", 2.2f, "M4 15v-3a8 8 0 0 1 16 0v3", rect(3f, 14f, 4f, 6f, 1.5f), rect(17f, 14f, 4f, 6f, 1.5f))

    /** A Sonos room (design 26a §5.1): a speaker cabinet in the player's stroked set (not a Material Symbol: a filled glyph would stand out in this bar). */
    val roomSpeaker = stroked("roomSpeaker", 2.2f, rect(6f, 3f, 12f, 18f, 2.5f), circle(12f, 14f, 3.2f), "M12 7.4v.2")

    /**
     * Design 53 §2.7, §2.12: grouping in "Spill av på" - a group of rooms (two cabinets), the row's "+", a member's
     * "−" and a member's quiet volume line. Our own drawing of the approved sketch's SVGs, in the stroked set (GPL).
     */
    val roomGroup = stroked("roomGroup", 2f, rect(2f, 4f, 9f, 16f, 2.5f), rect(13f, 4f, 9f, 16f, 2.5f))
    val add = stroked("add", 2.4f, "M12 5v14M5 12h14")
    val remove = stroked("remove", 2.4f, "M5 12h14")
    val volume = stroked("volume", 1.9f, "M3.6 9.6h3.6l4.8-4.2v13.2L7.2 14.4H3.6z", "M15.6 9a4.2 4.2 0 0 1 0 6")
    val check = stroked("check", 3f, "M5 12l5 5 9-10")
    val downloadAll = stroked("downloadAll", 2.4f, "M12 4v11M7 10l5 5 5-5M5 20h14")
    val downloaded = stroked("downloaded", 3f, "M12 5v10M7 11l5 5 5-5")

    /** The list's order flip (design 39 §4): two stroked arrows, up on the left, down on the right. */
    val flipOrder = stroked("flipOrder", 2f, "M8 19V5M4 9l4-4 4 4", "M16 5v14M12 15l4 4 4-4")
}
