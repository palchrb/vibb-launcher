package com.kidslauncher.mdm.ui.home

import com.kidslauncher.mdm.calls.RuleContact

/*
 * The home screen and phone book as data (design docs/design/05-ui-photos-i18n.md in the handy
 * workspace) - pure, no Android imports, tested in HomeModelTest. HomeActivity, the adapters and
 * PhoneBookActivity only render what these return.
 */

/** Columns of the app grid: 4 only when the parent chose 4, anything else 3 (the default). */
fun gridColumns(setting: Int?): Int = if (setting == 4) 4 else 3

/** An app the grid may show, as AppFilter left it (allowed, not suspended, not hidden). */
data class GridApp(val key: String, val label: String, val packageName: String?)

sealed interface GridTile {
    /** The phone book, always first when there is anything to call. */
    data object PhoneBook : GridTile

    data class App(val app: GridApp, val badge: Int) : GridTile
}

/**
 * The grid: the phone-book tile first (when [showPhoneBook] - calls managed and something to
 * call, see `phoneBookView`), then [apps] by label (case-insensitive, stable), each with its unread
 * count from [badges] (0 = no badge).
 */
fun homeGrid(apps: List<GridApp>, showPhoneBook: Boolean, badges: Map<String, Int>): List<GridTile> {
    val tiles = mutableListOf<GridTile>()
    if (showPhoneBook) tiles += GridTile.PhoneBook
    apps.sortedBy { it.label.lowercase() }.mapTo(tiles) { app ->
        GridTile.App(app, app.packageName?.let { badges[it] } ?: 0)
    }
    return tiles
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
