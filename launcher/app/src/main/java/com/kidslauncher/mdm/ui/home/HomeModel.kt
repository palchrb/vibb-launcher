package com.kidslauncher.mdm.ui.home

import com.kidslauncher.mdm.calls.CallPolicyState
import com.kidslauncher.mdm.calls.RuleContact
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt

/*
 * The home screen and phone book as data (design docs/design/05-ui-photos-i18n.md in the handy
 * workspace; sizes, colours and the Settings tile from 08-ui-polish.md) - pure, no Android
 * imports, tested in HomeModelTest. HomeActivity, the adapters and PhoneBookActivity only render
 * what these return.
 */

/** Columns of the app grid: 4 only when the parent chose 4, anything else 3 (the default). */
fun gridColumns(setting: Int?): Int = if (setting == 4) 4 else 3

/** An app the grid may show, as AppFilter left it (allowed, not suspended, not hidden). */
data class GridApp(val key: String, val label: String, val packageName: String?)

sealed interface GridTile {
    /** The phone book, first whenever calls are managed (or their rules are unknown). */
    data object PhoneBook : GridTile

    data class App(val app: GridApp, val badge: Int) : GridTile

    /** The kid's own settings (wallpaper, Wi-Fi, Bluetooth, brightness), always last. It has no
     * long-press menu - only [App] tiles do (QA 08 #10). */
    data object Settings : GridTile
}

/**
 * Whether Home shows the phone-book tile: whenever calls are managed or fail closed, even with
 * calls off and no emergency contact - the phone book then says so (design 08 §1). Only
 * unmanaged calls hide it. A Home contact implies the tile: Home contacts come from the same
 * managed rules ([com.kidslauncher.mdm.calls.phoneBookView]).
 */
fun showPhoneBookTile(state: CallPolicyState): Boolean = state !is CallPolicyState.Unmanaged

/**
 * The grid: the phone-book tile first (when [showPhoneBook], see [showPhoneBookTile]), then
 * [apps] by label (case-insensitive, stable), each with its unread count from [badges]
 * (0 = no badge), then the Settings tile.
 */
fun homeGrid(apps: List<GridApp>, showPhoneBook: Boolean, badges: Map<String, Int>): List<GridTile> {
    val tiles = mutableListOf<GridTile>()
    if (showPhoneBook) tiles += GridTile.PhoneBook
    apps.sortedBy { it.label.lowercase() }.mapTo(tiles) { app ->
        GridTile.App(app, app.packageName?.let { badges[it] } ?: 0)
    }
    tiles += GridTile.Settings
    return tiles
}

/** The mockup's content width (320 dp screen minus 2 × 16 dp padding). */
const val MOCKUP_CONTENT_DP = 288f
const val GRID_ROW_GAP_DP = 14
const val GRID_COLUMN_GAP_DP = 8
const val GRID_BADGE_DP = 24

/** Sizes of one grid tile, from the screen's content width. */
data class GridMetrics(
    val cellWidthDp: Float,
    val iconDp: Int,
    val labelSp: Float,
    val rowGapDp: Int = GRID_ROW_GAP_DP,
    val columnGapDp: Int = GRID_COLUMN_GAP_DP,
    val badgeDp: Int = GRID_BADGE_DP,
)

/**
 * Tile sizes scaled from the mockup (288 dp content, 60 dp icons, 13 sp labels): the icon is
 * 60 × width/288, between 48 and 76 dp, and always leaves 3 dp on each side of its cell; the
 * label is 13 sp × the same scale, between 12 and 15 sp.
 */
fun gridMetrics(contentWidthDp: Float, cols: Int): GridMetrics {
    val columns = max(1, cols)
    val width = max(0f, contentWidthDp)
    val cell = max(0f, (width - (columns - 1) * GRID_COLUMN_GAP_DP) / columns)
    val scale = width / MOCKUP_CONTENT_DP
    val icon = min((60 * scale).roundToInt().coerceIn(48, 76), floor(cell - 6).toInt())
    val label = (13 * scale).coerceIn(12f, 15f)
    return GridMetrics(cell, max(1, icon), label)
}

/** The Home contacts row: avatar size, the gap between avatars, and whether it scrolls. */
data class ContactRowLayout(val avatarDp: Int, val gapDp: Int, val scrolls: Boolean) {
    /** Width of one item (avatar plus a gap's worth of room for the name). */
    val itemDp: Int get() = avatarDp + gapDp
}

private fun rowFits(count: Int, avatar: Int, gap: Int, width: Float) =
    count * avatar + (count - 1) * gap <= width

/**
 * 1-3 contacts: 76 dp avatars 28 dp apart, centred (mockup); when that doesn't fit (or more
 * contacts): 64 dp, 16 dp apart, centred, if that fits; otherwise 64 dp in a horizontal scroll, spaced so
 * the next avatar is cut in half at the edge - the peek says "there is more".
 */
fun contactRow(count: Int, contentWidthDp: Float): ContactRowLayout {
    if (count <= 0) return ContactRowLayout(76, 28, false)
    if (count <= 3 && rowFits(count, 76, 28, contentWidthDp)) return ContactRowLayout(76, 28, false)
    if (rowFits(count, 64, 16, contentWidthDp)) return ContactRowLayout(64, 16, false)
    // k whole items (avatar + gap) plus half an avatar fill the width.
    val usable = contentWidthDp - 32
    val whole = max(1, floor(usable / (64 + 12)).toInt())
    val gap = max(12, floor((usable - whole * 64) / whole).toInt())
    return ContactRowLayout(64, gap, true)
}

/** Grey for icons whose colour can't be found (the mockup's Settings tile). */
const val TILE_GREY: Int = 0xFF868E96.toInt()

/** WCAG relative luminance of an opaque ARGB colour. */
fun relativeLuminance(argb: Int): Double {
    fun channel(c: Int): Double {
        val v = c / 255.0
        return if (v <= 0.03928) v / 12.92 else ((v + 0.055) / 1.055).pow(2.4)
    }
    return 0.2126 * channel((argb shr 16) and 0xFF) +
        0.7152 * channel((argb shr 8) and 0xFF) +
        0.0722 * channel(argb and 0xFF)
}

/** WCAG contrast ratio of two luminances (order doesn't matter). */
fun contrastRatio(lumA: Double, lumB: Double): Double =
    (max(lumA, lumB) + 0.05) / (min(lumA, lumB) + 0.05)

private fun rgbToHsl(argb: Int): FloatArray {
    val r = ((argb shr 16) and 0xFF) / 255f
    val g = ((argb shr 8) and 0xFF) / 255f
    val b = (argb and 0xFF) / 255f
    val mx = maxOf(r, g, b)
    val mn = minOf(r, g, b)
    val l = (mx + mn) / 2
    if (mx == mn) return floatArrayOf(0f, 0f, l)
    val d = mx - mn
    val s = if (l > 0.5f) d / (2 - mx - mn) else d / (mx + mn)
    val h = when (mx) {
        r -> ((g - b) / d + (if (g < b) 6 else 0))
        g -> ((b - r) / d + 2)
        else -> ((r - g) / d + 4)
    } / 6
    return floatArrayOf(h, s, l)
}

private fun hslToRgb(h: Float, s: Float, l: Float): Int {
    fun hue(p: Float, q: Float, tIn: Float): Float {
        var t = tIn
        if (t < 0) t += 1
        if (t > 1) t -= 1
        return when {
            t < 1f / 6 -> p + (q - p) * 6 * t
            t < 1f / 2 -> q
            t < 2f / 3 -> p + (q - p) * (2f / 3 - t) * 6
            else -> p
        }
    }
    val (r, g, b) = if (s == 0f) {
        Triple(l, l, l)
    } else {
        val q = if (l < 0.5f) l * (1 + s) else l + s - l * s
        val p = 2 * l - q
        Triple(hue(p, q, h + 1f / 3), hue(p, q, h), hue(p, q, h - 1f / 3))
    }
    fun byte(v: Float) = (v * 255).roundToInt().coerceIn(0, 255)
    return (0xFF shl 24) or (byte(r) shl 16) or (byte(g) shl 8) or byte(b)
}

/**
 * The circle colour behind a white icon glyph, from the icon's own colour [seedArgb]: same hue,
 * saturation at most 0.75, and the HSL lightness lowered in 0.04 steps until white on it reaches
 * 3:1 (WCAG, a glyph is a large graphic) - the mockup's #0DBD8B / #7950F2 / #F76707 family.
 */
fun tileColor(seedArgb: Int): Int {
    val (h, sRaw, lRaw) = rgbToHsl(seedArgb)
    val s = min(sRaw, 0.75f)
    var l = lRaw
    var color = hslToRgb(h, s, l)
    while (contrastRatio(1.0, relativeLuminance(color)) < 3.0 && l > 0f) {
        l = max(0f, l - 0.04f)
        color = hslToRgb(h, s, l)
    }
    return color
}

/** Badge text: nothing for 0 or less, the count up to 99, then "99+". */
fun badgeText(count: Int): String? = when {
    count <= 0 -> null
    count > 99 -> "99+"
    else -> count.toString()
}

/** Background and ink of a round avatar without a photo (the mockup's palette). */
data class AvatarColors(val background: Long, val ink: Long)

val AVATAR_PALETTE = listOf(
    AvatarColors(0xFFF08C3A, 0xFF2B1606),
    AvatarColors(0xFF4DABF7, 0xFF08243A),
    AvatarColors(0xFFFCC419, 0xFF2E2304),
    AvatarColors(0xFF69DB7C, 0xFF0B2A10),
    AvatarColors(0xFFDA77F2, 0xFF2B0A33),
    AvatarColors(0xFFFF8787, 0xFF3A0A0A),
)

/** Emergency numbers are red with white ink, whatever their id. */
val EMERGENCY_AVATAR = AvatarColors(0xFFE03131, 0xFFFFFFFF)

data class AvatarStyle(val text: String, val colors: AvatarColors)

/**
 * A contact's placeholder: the first letter of the name (upper case; a whole surrogate pair or
 * other code point, not half of one) on a colour picked by contact id, so it stays the same
 * across syncs. Emergency numbers show the number itself on red ("112"). No name → the last two
 * digits of the number, or "?".
 */
fun avatarStyle(contact: RuleContact, isEmergency: Boolean): AvatarStyle {
    if (isEmergency) return AvatarStyle(contact.number.ifEmpty { "?" }, EMERGENCY_AVATAR)
    val name = contact.name.trim()
    val text = if (name.isNotEmpty()) {
        val end = name.offsetByCodePoints(0, 1)
        name.substring(0, end).uppercase()
    } else {
        contact.number.filter { it.isDigit() }.takeLast(2).ifEmpty { "?" }
    }
    val index = Math.floorMod(contact.id, AVATAR_PALETTE.size.toLong()).toInt()
    return AvatarStyle(text, AVATAR_PALETTE[index])
}
