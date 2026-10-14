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

/** Badge text: nothing for 0 or less, the count up to 99, then "99+". */
fun badgeText(count: Int): String? = when {
    count <= 0 -> null
    count > 99 -> "99+"
    else -> count.toString()
}

/** Background and ink of a round avatar without a photo (the mockup's palette). */
data class AvatarColors(val background: Long, val ink: Long)

/** The contacts' initial ink: a dark night blue, >= 8.9:1 on every [AVATAR_PALETTE] colour. */
const val AVATAR_INK = 0xFF1B1B28

/**
 * Soft, light discs with a dark initial (design 34 P3): peach, sky, sand, sage, lilac, rose -
 * people read differently from the apps' mid-dark tones, and nothing is saturated. The order is
 * the index [avatarStyle] picks by contact id; append, don't reorder (a contact keeps its colour).
 */
val AVATAR_PALETTE = listOf(
    AvatarColors(0xFFE8B49A, AVATAR_INK),
    AvatarColors(0xFF9FC3E3, AVATAR_INK),
    AvatarColors(0xFFE3CF9A, AVATAR_INK),
    AvatarColors(0xFFA9CDB0, AVATAR_INK),
    AvatarColors(0xFFC9B3DE, AVATAR_INK),
    AvatarColors(0xFFE6AFB4, AVATAR_INK),
)

/**
 * Emergency numbers, whatever their id: the tonal emergency red (design 34 P1/QA M3) - a dark red
 * disc (`kid_emergency_tonal` #3A1E24) with the number in `kid_emergency_text` #FF8A8A (6.6:1),
 * ringed in that red ink on every surface ([EMERGENCY_RING_DP], `KidAvatars.bindContact`): the
 * disc alone is ~1.1-1.3:1 on the night. One pair with P7's emergency pill - `KidPaletteTest`
 * keeps these equal to the two resources.
 */
val EMERGENCY_AVATAR = AvatarColors(0xFF3A1E24, 0xFFFF8A8A)

/** The red ring of the 112 avatar, in dp (qa-34-s3-code L2). */
const val EMERGENCY_RING_DP = 3f

/**
 * The contact sheet's ring around the avatar: the contact's colour, but the emergency red's ink
 * for an emergency number - in the disc's colour it would cover the disc's own red ring.
 */
fun avatarRingColour(colors: AvatarColors, isEmergency: Boolean): Long =
    if (isEmergency) colors.ink else colors.background

data class AvatarStyle(val text: String, val colors: AvatarColors)

/**
 * A contact's placeholder: the first letter of the name (upper case; a whole surrogate pair or
 * other code point, not half of one) on a colour picked by contact id, so it stays the same
 * across syncs. Emergency numbers show the number itself in the tonal red ("112"). No name →
 * the last two digits of the number, or "?".
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
