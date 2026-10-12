package me.vibb.music.storytel

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/*
 * Storytel's hosts, ported from palchrb/vibb `pi/vibb/storytel.py` (the CONSTANTS block:
 * `LOGIN_URL`, `API`; MIT License, Copyright (c) 2026 palchrb), reimplemented in Kotlin under the
 * GPL-3.0 (or later) - see NOTICE.md. Design 26b §4.1, §4.6.
 *
 * UPSTREAM: storytel-player, tag 1.3.0 (github.com/debba/storytel-player, MIT, debba),
 * server/storytelApi.ts. When Storytel moves a URL, it moves here (and the paths in StorytelApi's
 * constants), nowhere else - vibb's rule.
 */

/** Where the login and the API live: [Endpoints.STORYTEL], or a test's loopback servers. */
data class Endpoints(
    /** `LOGIN_URL`: GET with `m`, `uid`, `pwd`. */
    val login: HttpUrl,
    /** `API`: the asset call's base. */
    val api: HttpUrl,
) {
    companion object {
        /** The real hosts (vibb, verified against a live account 2026-08-14). */
        val STORYTEL = Endpoints(
            "https://www.storytel.com/api/login.action".toHttpUrl(),
            "https://api.storytel.net".toHttpUrl(),
        )
    }
}

/**
 * The one debug-only switch (design 26b §4.6, QA M2): null unless the debug feed's `config.json` sets
 * `storytel_base` (the emulator's fake Storytel on 10.0.2.2, a private address the guarded client
 * refuses). Only `src/debug` assigns it (SourceScanTest); a release build has no feed, so it stays null
 * there. It has exactly four readers, all below and nowhere else: (a) [endpoints], (b) [apiOverLan],
 * (c) [publicDownloads] and (d) [privateMintAllowed]. Nothing caches what they answer except
 * `StorytelAccount`, which keeps its one `StorytelApi` for as long as (a) and (b) answer the same.
 */
object StorytelEndpoints {
    @Volatile
    var debugBase: String? = null

    /** (a) Where the login and the API live: the real hosts, or the fake's `/api/login.action` and its root. */
    fun endpoints(): Endpoints {
        val base = parse(debugBase) ?: return Endpoints.STORYTEL
        return Endpoints(base.newBuilder().encodedPath("/api/login.action").build(), base.newBuilder().encodedPath("/").build())
    }

    /** (b) Whether `StorytelAccount` talks to the API over MediaCache's LAN client instead of the guarded public one. */
    fun apiOverLan(): Boolean = parse(debugBase) != null

    /** (c) Whether a Storytel download goes through the guarded public-only client (always, but for the fake). */
    fun publicDownloads(): Boolean = parse(debugBase) == null

    /** (d) Whether design 26b §5.1's minted-URL check on the Sonos path lets a private literal through (step 4b). */
    fun privateMintAllowed(): Boolean = parse(debugBase) != null

    /** An http(s) base with a host, else none (a typo in the feed falls back to Storytel's hosts everywhere at once). */
    private fun parse(base: String?): HttpUrl? =
        base?.toHttpUrlOrNull()?.takeIf { it.host.isNotEmpty() && it.username.isEmpty() && it.password.isEmpty() }
}
