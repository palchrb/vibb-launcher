package me.vibb.music.logic

import me.vibb.music.model.OwnFile

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
 * The own files the report names as held (`BridgeSync.push`): every copy except one known to be another
 * file. A copy not judged yet (no record) counts as held, as before design 29, so the launcher never
 * fetches the whole library again before the next sync judges it (qa-29-code L3). [recorded]: a copy's record.
 */
fun heldOwnFiles(files: List<OwnFile>, copies: Set<Long>, recorded: (Long) -> String?): List<OwnFile> =
    files.filter { it.id in copies && ownCopyVerdict(it.sha256, recorded(it.id)) != OwnCopyVerdict.Refetch }

/** What `BridgeSync.pullFiles` does: delete [refetch] (held copies of another file), then fetch [fetch]. */
data class OwnFetchPlan(val refetch: List<Long>, val fetch: List<OwnFile>)

/** [files] against the [copies] held: a copy [kept] says is the library's file stays; every other file is fetched. */
fun ownFetchPlan(files: List<OwnFile>, copies: Set<Long>, kept: (OwnFile) -> Boolean): OwnFetchPlan {
    val refetch = files.filter { it.id in copies && !kept(it) }
    return OwnFetchPlan(refetch.map { it.id }, files.filter { it.id !in copies } + refetch)
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
