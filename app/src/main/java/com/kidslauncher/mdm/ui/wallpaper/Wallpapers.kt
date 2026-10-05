package com.kidslauncher.mdm.ui.wallpaper

import com.kidslauncher.mdm.calls.isValidPhotoHash
import com.kidslauncher.mdm.calls.photoCachePlan
import com.kidslauncher.mdm.calls.PhotoCachePlan
import com.kidslauncher.mdm.server.dto.PolicyWallpaper

/*
 * Curated wallpapers on the phone (design 08-ui-polish.md §3, QA qa-08-design.md) - pure, no
 * Android imports, tested in WallpapersTest. The parent decides which wallpapers this phone may
 * use (`launcher_ui.wallpapers`); the kid's pick stays on the phone (CE prefs); what is shown is
 * [effectiveWallpaper]. Nothing here goes to device-protected storage.
 */

/** What a wallpaper looks like. Colours are opaque ARGB. */
sealed interface WallpaperFill {
    data class Solid(val argb: Int) : WallpaperFill

    /** Two colours at 160° (top-left to bottom-right, slightly steeper than diagonal). */
    data class Gradient(val from: Int, val to: Int) : WallpaperFill

    /** An uploaded photo, cached as `<hash>.jpg`. */
    data class Image(val hash: String) : WallpaperFill

    /** A stable description, part of the applied key ([wallpaperKey]). */
    fun describe(): String = when (this) {
        is Solid -> "solid:%08x".format(argb)
        is Gradient -> "gradient:%08x:%08x".format(from, to)
        is Image -> "image:$hash"
    }
}

data class Wallpaper(
    val id: Long,
    val fill: WallpaperFill,
    val label: String,
    val builtinKey: String? = null,
    /** Only for images: also on the lock screen (the parent opted in). */
    val lockScreen: Boolean = false,
)

const val NAVY_ARGB: Int = 0xFF14213D.toInt()

/** The launcher's own default: the built-in navy (also what an older server means). */
val NAVY = Wallpaper(1, WallpaperFill.Solid(NAVY_ARGB), "Navy", "navy")

/** `#RRGGBB` → opaque ARGB, or null. */
fun parseHexColour(text: String): Int? {
    if (text.length != 7 || text[0] != '#') return null
    val rgb = text.substring(1).toIntOrNull(16) ?: return null
    if (!text.substring(1).all { it.isDigit() || it.lowercaseChar() in 'a'..'f' }) return null
    return (0xFF shl 24) or rgb
}

/** The server's list as wallpapers, in order; an entry that doesn't check out is left out. */
fun parseWallpapers(list: List<PolicyWallpaper>): List<Wallpaper> = list.mapNotNull { w ->
    val fill = when (w.kind) {
        "color" -> w.colors.singleOrNull()?.let(::parseHexColour)?.let { WallpaperFill.Solid(it) }
        "gradient" -> if (w.colors.size == 2) {
            val from = parseHexColour(w.colors[0])
            val to = parseHexColour(w.colors[1])
            if (from != null && to != null) WallpaperFill.Gradient(from, to) else null
        } else {
            null
        }
        "image" -> w.image?.takeIf(::isValidPhotoHash)?.let { WallpaperFill.Image(it) }
        else -> null
    } ?: return@mapNotNull null
    Wallpaper(w.id, fill, w.label, w.builtinKey, lockScreen = w.lockScreen && fill is WallpaperFill.Image)
}

/** Whether a wallpaper can be shown now: colours always, images once their file is cached. */
fun usable(wallpaper: Wallpaper, cachedHashes: Set<String>): Boolean =
    when (val fill = wallpaper.fill) {
        is WallpaperFill.Image -> fill.hash in cachedHashes
        else -> true
    }

/**
 * What is shown: the kid's pick if the parent still allows it and it is usable, else the first
 * usable allowed one, else navy. A photo the parent took away is therefore replaced at once.
 */
fun effectiveWallpaper(allowed: List<Wallpaper>, pickedId: Long?, cachedHashes: Set<String>): Wallpaper {
    allowed.firstOrNull { it.id == pickedId && usable(it, cachedHashes) }?.let { return it }
    return allowed.firstOrNull { usable(it, cachedHashes) } ?: NAVY
}

/**
 * The lock screen: a photo only when the parent ticked "also on the lock screen" (anyone holding
 * the locked phone sees it, QA 08 #1); otherwise navy. Colours go on both.
 */
fun lockScreenFill(home: Wallpaper): WallpaperFill =
    if (home.fill is WallpaperFill.Image && !home.lockScreen) NAVY.fill else home.fill

/** The images this phone may cache: allowed images only - everything else is deleted. */
fun wantedWallpaperHashes(allowed: List<Wallpaper>): Set<String> =
    allowed.mapNotNullTo(mutableSetOf()) { (it.fill as? WallpaperFill.Image)?.hash }

/** Largest wallpaper file the phone accepts (the server sends ≤ 1080×2400 JPEGs, ≤ 3 MB). */
const val MAX_WALLPAPER_BYTES = 3 * 1024 * 1024
const val MAX_WALLPAPER_WIDTH = 1080
const val MAX_WALLPAPER_HEIGHT = 2400

/**
 * Download the allowed images that are missing, delete every other file (a photo the parent
 * took away, half-written temp files) - the contact photos' plan with the wallpaper list. Not
 * kept "while unknown": a wallpaper is cosmetic, and a private photo should not outlive its
 * permission.
 */
fun wallpaperCachePlan(allowed: List<Wallpaper>, cachedFiles: Set<String>, notFound: Set<String>): PhotoCachePlan =
    photoCachePlan(wantedWallpaperHashes(allowed), cachedFiles, keepWhenUnknown = false, notFound = notFound)

/** A decoded wallpaper is accepted only within the server's size (QA 08 #6). */
fun wallpaperBoundsOk(width: Int, height: Int): Boolean =
    width in 1..MAX_WALLPAPER_WIDTH && height in 1..MAX_WALLPAPER_HEIGHT

/**
 * `inSampleSize` for decoding a `width`×`height` image for a `targetW`×`targetH` window: the
 * largest power of two that keeps both sides at least the window's (it is centre-cropped after).
 */
fun wallpaperSampleSize(width: Int, height: Int, targetW: Int, targetH: Int): Int {
    var sample = 1
    while (width / (sample * 2) >= targetW && height / (sample * 2) >= targetH) sample *= 2
    return sample
}
