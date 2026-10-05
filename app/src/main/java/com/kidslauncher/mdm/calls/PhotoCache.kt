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
fun photoCachePlan(wanted: Set<String>, cachedFiles: Set<String>, keepWhenUnknown: Boolean = false): PhotoCachePlan {
    val cached = cachedFiles.mapNotNullTo(mutableSetOf()) { name ->
        name.removeSuffix(".jpg").takeIf { name.endsWith(".jpg") && isValidPhotoHash(it) }
    }
    val delete = if (keepWhenUnknown) {
        emptySet()
    } else {
        cachedFiles.filterTo(mutableSetOf()) { name -> name.removeSuffix(".jpg") !in wanted || !name.endsWith(".jpg") }
    }
    return PhotoCachePlan(download = wanted - cached, delete = delete)
}

/** A downloaded photo is kept only if its SHA-256 matches the name it was asked for. */
fun photoMatches(expectedHash: String, actualSha256Hex: String): Boolean =
    isValidPhotoHash(expectedHash) && expectedHash == actualSha256Hex.lowercase()
