package me.vibb.music.storytel

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/*
 * Storytel's hosts, ported from palchrb/vibb `pi/vibb/storytel.py` (the CONSTANTS block: `API`; MIT
 * License, Copyright (c) 2026 palchrb), reimplemented in Kotlin under the GPL-3.0 (or later) - see
 * NOTICE.md. Design 26b §4.1, §4.6; design 58 §5.6.
 *
 * UPSTREAM: storytel-player, tag 1.3.0 (github.com/debba/storytel-player, MIT, debba),
 * server/storytelApi.ts. When Storytel moves a URL, it moves here (and the paths in StorytelApi's
 * constants), nowhere else - vibb's rule. The login's URL is the server's only (design 58).
 */

/** Where the API lives: [Endpoints.STORYTEL], or a test's loopback server. */
data class Endpoints(
    /** `API`: the asset call's base. */
    val api: HttpUrl,
) {
    companion object {
        /** The real host (vibb, verified against a live account 2026-08-14). */
        val STORYTEL = Endpoints("https://api.storytel.net".toHttpUrl())
    }
}

/**
 * The one debug-only switch (design 26b §4.6, QA M2): null unless the debug feed's `config.json` sets
 * `storytel_base` (the emulator's fake Storytel on 10.0.2.2, a private address the guarded client
 * refuses; only 10.0.2.2 or loopback is taken). Only `src/debug` assigns it (SourceScanTest); a release build has no feed, so it stays null
 * there. It has exactly five readers, all below and nowhere else: (a) [endpoints], (b) [apiOverLan],
 * (c) [publicDownloads], (d) [privateMintAllowed] and (e) [debugJwt]. Nothing caches what they answer
 * except `StorytelAccount`, which keeps its one `StorytelApi` and `StorytelSession` for as long as (a) and
 * (b) answer the same.
 */
object StorytelEndpoints {
    @Volatile
    var debugBase: String? = null

    /** (a) Where the API lives: the real host, or the fake's root. */
    fun endpoints(): Endpoints {
        val base = parse(debugBase) ?: return Endpoints.STORYTEL
        return Endpoints(base.newBuilder().encodedPath("/").build())
    }

    /** (b) Whether `StorytelAccount` talks to the API over MediaCache's LAN client instead of the guarded public one. */
    fun apiOverLan(): Boolean = parse(debugBase) != null

    /** (c) Whether a Storytel download goes through the guarded public-only client (always, but for the fake). */
    fun publicDownloads(): Boolean = parse(debugBase) == null

    /** (d) Whether design 26b §5.1's minted-URL check on the Sonos path lets a private literal through (step 4b). */
    fun privateMintAllowed(): Boolean = parse(debugBase) != null

    /**
     * (e) The fake's session route (`/fake/jwt`, design 58 §5.6), asked instead of the launcher only while the app
     * is unmanaged (a managed phone's JWT always comes through the bridge); null without the switch.
     */
    fun debugJwt(): HttpUrl? = parse(debugBase)?.newBuilder()?.encodedPath("/fake/jwt")?.build()

    /**
     * The fake's homes only: the emulator's host (10.0.2.2) or loopback (qa-26b-step3-code L2). A
     * `config.json` left behind on a real phone running a debug build must never send the family's JWT
     * over plain http to another host; a real phone reaches neither of these. Anything else - or a typo -
     * is none: Storytel's host everywhere at once.
     */
    private val FAKE_HOSTS = setOf("10.0.2.2", "127.0.0.1", "localhost", "::1")

    private fun parse(base: String?): HttpUrl? =
        base?.toHttpUrlOrNull()?.takeIf { it.host in FAKE_HOSTS && it.username.isEmpty() && it.password.isEmpty() }
}
