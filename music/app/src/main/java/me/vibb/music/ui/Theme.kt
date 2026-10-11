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
    val list = stroked("list", 2.4f, "M8 6h12M8 12h12M8 18h12M4 6h.01M4 12h.01M4 18h.01")
    val moon = stroked("moon", 2.2f, "M20 14.5A8 8 0 0 1 9.5 4 8 8 0 1 0 20 14.5z")
    val speaker = stroked("speaker", 2.2f, "M11 5L6 9H3v6h3l5 4z", "M15.5 8.5a5 5 0 0 1 0 7M18.5 5.5a9 9 0 0 1 0 13")
    val speakerSmall = stroked("speakerSmall", 2.2f, "M11 5L6 9H3v6h3l5 4z", "M15.5 8.5a5 5 0 0 1 0 7")
    val headphones = stroked("headphones", 2.2f, "M4 15v-3a8 8 0 0 1 16 0v3", rect(3f, 14f, 4f, 6f, 1.5f), rect(17f, 14f, 4f, 6f, 1.5f))
    val check = stroked("check", 3f, "M5 12l5 5 9-10")
    val downloadAll = stroked("downloadAll", 2.4f, "M12 4v11M7 10l5 5 5-5M5 20h14")
    val downloaded = stroked("downloaded", 3f, "M12 5v10M7 11l5 5 5-5")
}
