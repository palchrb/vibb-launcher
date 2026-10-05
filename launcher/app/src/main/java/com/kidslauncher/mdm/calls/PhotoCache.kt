package com.kidslauncher.mdm.calls

/*
 * Which contact photos to download and which cached files to delete (design
 * 05-ui-photos-i18n.md) - pure, tested in PhotoCacheTest. ContactPhotos does the I/O, in CE storage
 * only (`filesDir/contact_photos`): nothing photo-related is mirrored to device-protected storage.
 */

/** The server's photo name: SHA-256 as 64 lowercase hex characters. Anything else is never used
 * as a file name or URL path. */
fun isValidPhotoHash(hash: String?): Boolean =
    hash != null && hash.length == 64 && hash.all { it in '0'..'9' || it in 'a'..'f' }

/** Largest photo the launcher accepts (the server sends ≤ 512 px JPEGs, far smaller). */
const val MAX_PHOTO_BYTES = 1024 * 1024

/** Photos the phone book and Home can show: managed rules only (unmanaged = no contacts). */
fun wantedPhotoHashes(state: CallPolicyState): Set<String> =
    (state as? CallPolicyState.Managed)?.rules?.contacts
        ?.mapNotNull { it.photo }?.filterTo(mutableSetOf(), ::isValidPhotoHash)
        ?: emptySet()

data class PhotoCachePlan(val download: Set<String>, val delete: Set<String>)

/**
 * [cachedFiles] are the file names in the cache directory. Download what's wanted and missing;
 * delete every file that isn't `<wanted hash>.jpg` (old photos, half-written temp files, junk).
 * With [keepWhenUnknown] (the call rules couldn't be read - fail closed) nothing is deleted, so a
 * temporary problem doesn't throw the photos away.
 */
fun photoCachePlan(
    wanted: Set<String>,
    cachedFiles: Set<String>,
    keepWhenUnknown: Boolean = false,
    notFound: Set<String> = emptySet(),
): PhotoCachePlan {
    val cached = cachedFiles.mapNotNullTo(mutableSetOf()) { name ->
        name.removeSuffix(".jpg").takeIf { name.endsWith(".jpg") && isValidPhotoHash(it) }
    }
    val delete = if (keepWhenUnknown) {
        emptySet()
    } else {
        cachedFiles.filterTo(mutableSetOf()) { name -> name.removeSuffix(".jpg") !in wanted || !name.endsWith(".jpg") }
    }
    return PhotoCachePlan(download = wanted - cached - notFound, delete = delete)
}

/**
 * Hashes the server answered 404 for are not asked for again while the policy still names them
 * (QA step 5 #4: a restored server without the file); a different hash in the policy is a new
 * photo and is fetched. Returns the set to remember after this sync.
 */
fun rememberNotFound(previous: Set<String>, wanted: Set<String>, newlyNotFound: Set<String>): Set<String> =
    (previous intersect wanted) + newlyNotFound

/** Largest side a cached photo may declare before it is decoded (the server sends ≤ 512 px). A
 * small file can still declare a huge bitmap, so the header is checked first (QA step 5 #1). */
const val MAX_PHOTO_SIDE = 1024

/** Decode only photos whose header says 1..[MAX_PHOTO_SIDE] px a side. */
fun photoBoundsOk(width: Int, height: Int): Boolean =
    width in 1..MAX_PHOTO_SIDE && height in 1..MAX_PHOTO_SIDE

/** A downloaded photo is kept only if its SHA-256 matches the name it was asked for. */
fun photoMatches(expectedHash: String, actualSha256Hex: String): Boolean =
    isValidPhotoHash(expectedHash) && expectedHash == actualSha256Hex.lowercase()
