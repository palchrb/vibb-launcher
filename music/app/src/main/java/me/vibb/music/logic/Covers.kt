package me.vibb.music.logic

/*
 * Which image a cover slot shows. Pure; CoversTest. A slot (the player's cover, the now-playing
 * bar, a carousel page, a list row) stays in the composition while the hash it shows changes: the
 * player goes from podcast A to podcast B, a carousel page gets another category's entry. What the
 * slot decoded belongs to the hash it was decoded for and is never shown for another one.
 */

/** A cover file decoded for [hash]. */
data class DecodedCover<T : Any>(val hash: String, val image: T)

/** The slot still has to decode [hash]'s file: what it holds is for no hash or for another one. */
fun needsDecode(hash: String?, decoded: DecodedCover<*>?): Boolean = hash != null && decoded?.hash != hash

/**
 * What a slot for [hash] shows: the image in memory for that hash ([cached]), else the one decoded
 * for that hash, else nothing (the category's gradient) - never the image of the hash it showed before.
 */
fun <T : Any> coverShown(hash: String?, cached: T?, decoded: DecodedCover<T>?): T? {
    if (hash == null) return null
    return cached ?: decoded?.takeIf { it.hash == hash }?.image
}

/** A slot found no file for [hash] while the covers were at [version] (`Store.coversVersion`). */
data class MissingCover(val hash: String, val version: Long)

/**
 * Should a slot try to decode [hash] now? When what it holds is for another hash, unless it already
 * found no file for this hash at this covers [version]: a picture that lands while its slot is on
 * screen moves the version and shows (QA M5), and a missing file isn't looked for on every frame.
 */
fun shouldDecode(hash: String?, decoded: DecodedCover<*>?, missing: MissingCover?, version: Long): Boolean =
    needsDecode(hash, decoded) && missing != MissingCover(hash!!, version)
