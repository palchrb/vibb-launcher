package com.kidslauncher.mdm.appdisplay

import android.content.Context
import android.util.Log
import com.kidslauncher.mdm.server.CachedPolicy
import com.kidslauncher.mdm.server.cachedPolicy
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/*
 * How an app shows on the kid's launcher (design 14-app-display.md at the monorepo root, with its QA
 * review and decisions): the parent's name, icon and tile colour from `launcher_ui.app_display` - the
 * server resolves the catalog default against the phone's own choice, so this is one list. The rules
 * are the pure functions here (AppDisplayTest); [AppDisplay] holds the map in memory.
 */

/** A label is at most this many characters (the server's `MAX_LABEL_CHARS`). */
const val MAX_DISPLAY_LABEL_CHARS = 20

/** The colour key meaning "the app's own colour". */
const val AUTO_COLOR = "auto"

/** The parent's choice for one package: [label]/[icon] `null` = the app's own; [color] [AUTO_COLOR]
 * or a key of [AppGlyphs.COLORS] (only used with an icon). */
data class AppDisplayEntry(val label: String?, val icon: String?, val color: String = AUTO_COLOR)

/** Android's package name grammar (the server's `valid_package_name`). */
fun isValidPackageName(name: String): Boolean =
    name.length <= 255 && name.split('.').let { segments ->
        segments.size >= 2 && segments.all { seg ->
            seg.isNotEmpty() && seg[0].isLetter() && seg[0].code < 128 &&
                seg.all { (it.isLetterOrDigit() && it.code < 128) || it == '_' }
        }
    }

/** A usable label: trimmed, 1-20 characters (code points), no control characters; else `null`. */
fun cleanDisplayLabel(raw: String?): String? {
    val label = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    if (label.codePointCount(0, label.length) > MAX_DISPLAY_LABEL_CHARS) return null
    if (label.codePoints().anyMatch { Character.isISOControl(it) }) return null
    return label
}

/** A string field of a JSON object, `null` for anything else (a number, `null`, an object). */
private fun JsonObject.string(key: String): String? =
    (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

/**
 * `launcher_ui.app_display` read field by field (QA #1): whatever the server sends - a newer
 * server's unknown icon, a bad label, `null`s, wrong types, not even a list - never fails the
 * policy and only drops what can't be used: an unknown icon keeps the label, a bad label keeps the
 * icon, an unknown or missing colour is [AUTO_COLOR]; an entry without a valid package or with
 * neither a label nor an icon is left out; the first entry of a package wins.
 */
fun appDisplayMap(
    element: JsonElement?,
    icons: Set<String> = AppGlyphs.ICONS.keys,
    colors: Set<String> = AppGlyphs.COLORS.keys,
): Map<String, AppDisplayEntry> {
    val list = element as? JsonArray ?: return emptyMap()
    val map = LinkedHashMap<String, AppDisplayEntry>()
    for (item in list) {
        val obj = item as? JsonObject ?: continue
        val pkg = obj.string("package_name")?.takeIf { isValidPackageName(it) } ?: continue
        if (pkg in map) continue
        val label = cleanDisplayLabel(obj.string("label"))
        val icon = obj.string("icon")?.takeIf { it in icons }
        if (label == null && icon == null) continue
        val color = obj.string("color")?.takeIf { it in colors } ?: AUTO_COLOR
        map[pkg] = AppDisplayEntry(label, icon, if (icon == null) AUTO_COLOR else color)
    }
    return map
}

/** The name an app shows: the parent's, else the kid's rename, else the app's own. */
fun displayLabel(parent: String?, kidRename: String?, appLabel: String): String =
    parent ?: kidRename?.takeIf { it.isNotBlank() } ?: appLabel

/** The kid's long-press Rename is offered only for an app the parent hasn't named. Their stored
 * rename is kept and shows again when the parent clears the name (QA #6). */
fun kidMayRename(entry: AppDisplayEntry?): Boolean = entry?.label == null

/**
 * The rendered-icon cache key (QA #2: one key for the lookup and the render): app, size and
 * density, plus the glyph and colour when the parent picked an icon - a changed choice is a new
 * key. `auto` keeps the app in the key (its own colour).
 */
fun appIconKey(key: String, sizePx: Int, densityDpi: Int, entry: AppDisplayEntry?): String {
    val base = "$key|$sizePx|$densityDpi"
    val icon = entry?.icon ?: return base
    return "$base|g=$icon|c=${entry.color}"
}

/**
 * The parent's names and icons in memory: loaded from the cached policy at process start and after
 * every accepted sync (QA #2); a change reloads the app list, so Home and the drawer re-render and
 * re-sort with it.
 */
object AppDisplay {
    @Volatile
    var map: Map<String, AppDisplayEntry> = emptyMap()
        private set

    fun entry(packageName: String?): AppDisplayEntry? = packageName?.let { map[it] }

    fun label(packageName: String?): String? = entry(packageName)?.label

    /** Reads the cached policy (`Ok` only - a corrupt or missing cache shows the apps' own names);
     * with [reload] a change reloads the app list. Returns true when the map changed. */
    fun refresh(context: Context, reload: Boolean = true): Boolean {
        val next = try {
            (cachedPolicy() as? CachedPolicy.Ok)?.policy?.launcherUi?.appDisplay
                .let { appDisplayMap(it) }
        } catch (e: Exception) {
            Log.w("AppDisplay", "Couldn't read the app names", e)
            return false
        }
        if (next == map) return false
        map = next
        if (reload) (context.applicationContext as? com.kidslauncher.mdm.Application)?.reloadApps()
        return true
    }
}
