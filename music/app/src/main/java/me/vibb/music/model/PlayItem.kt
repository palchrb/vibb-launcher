package me.vibb.music.model

/** What the app plays, one shape for a listed episode and an own file. */
data class PlayItem(
    val entryId: Long,
    /** The listing key, or [OwnFile.key] for an own file. */
    val key: String,
    val title: String,
    /** The source URL of a listed episode; null for an own file, or for a withdrawn episode. */
    val url: String?,
    val hls: Boolean,
    val durationMs: Long?,
    /** An own file's id (plays from the app's copy). */
    val fileId: Long? = null,
    /** The item's own art (a cover hash): an own file's embedded art, or an episode's picture
     * (design 27); null = the entry's cover. */
    val artHash: String? = null,
)

fun ListingItem.toPlayItem(entryId: Long) = PlayItem(entryId, key, title, url, hls, durationMs, artHash = art)

fun OwnFile.toPlayItem() = PlayItem(entry, key, title, null, false, durationMs, fileId = id, artHash = art)
