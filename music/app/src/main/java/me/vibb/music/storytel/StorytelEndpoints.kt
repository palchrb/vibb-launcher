package me.vibb.music.storytel

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl

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
 * refuses). Declared in step 1 and read by nothing yet; step 3 gives it exactly four readers (the
 * endpoints, the LAN client for the API, `publicOnly = false` for Storytel downloads, the minted-URL
 * check of §5.1), and only `src/debug` assigns it (SourceScanTest). A release build has no feed, so it
 * stays null there.
 */
object StorytelEndpoints {
    @Volatile
    var debugBase: String? = null
}
