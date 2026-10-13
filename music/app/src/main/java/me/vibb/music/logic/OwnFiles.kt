package me.vibb.music.logic

/*
 * An own file's copy after an id reuse (design 29 §6 step 4; ours, not vibb: the Pi has no copies).
 * After a server restore, or on a new server, an own-file id can name another file. The phone keeps
 * the SHA-256 it checked when the copy arrived (`own/<id>.sha256`, Inbox) and compares it with the
 * library's: a copy of another file is fetched again, so the kid never hears the old audio under the
 * new title. Pure; OwnFilesTest.
 */

private val SHA256 = Regex("[0-9a-f]{64}")

/** What to do with a held copy: keep it, fetch the library's file again, or hash it first (no record yet). */
enum class OwnCopyVerdict { Keep, Refetch, Hash }

/**
 * A held copy judged against the library's [libSha]. [heldSha]: the hash recorded for the copy, null
 * when there is none (a copy held before design 29) or it doesn't read as one - then the copy is hashed
 * once, its record written, and judged again.
 */
fun ownCopyVerdict(libSha: String, heldSha: String?): OwnCopyVerdict = when {
    heldSha == null || !SHA256.matches(heldSha) -> OwnCopyVerdict.Hash
    heldSha == libSha -> OwnCopyVerdict.Keep
    else -> OwnCopyVerdict.Refetch
}

/**
 * Is the held copy the library's file? [recorded]: the copy's record; without one, [hash] reads the
 * copy's SHA-256 (null: unreadable, so it is fetched again) and [record] writes it, so a copy is
 * hashed once.
 */
fun keepOwnCopy(libSha: String, recorded: String?, hash: () -> String?, record: (String) -> Unit): Boolean =
    when (ownCopyVerdict(libSha, recorded)) {
        OwnCopyVerdict.Keep -> true
        OwnCopyVerdict.Refetch -> false
        OwnCopyVerdict.Hash -> hash()?.let { sha ->
            record(sha)
            ownCopyVerdict(libSha, sha) == OwnCopyVerdict.Keep
        } ?: false
    }
