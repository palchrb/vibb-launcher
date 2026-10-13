package me.vibb.music.spotify

import me.vibb.music.model.Entry
import me.vibb.music.model.Source

/*
 * A Spotify entry's target as the server stores it (design 26c §3.1, §4.1): `spotify:<kind>:<id>`,
 * canonical only - the phone never parses a share link (the server resolved it at add time). The
 * grammar is the server's `spotify::parse_target`, from palchrb/vibb `pi/vibb/spotify.py` (`URI_RE`;
 * MIT License, Copyright (c) 2026 palchrb), reimplemented in Kotlin under the GPL-3.0 (or later) -
 * see NOTICE.md. Pure; SpotifyRulesTest.
 */

/** The kinds of link an entry can be made of. */
enum class SpotifyKind(val wire: String) {
    PLAYLIST("playlist"), ALBUM("album"), TRACK("track"), ARTIST("artist"), SHOW("show"), EPISODE("episode");

    /** A show's and an episode entry's items are episodes; every other kind's are tracks. */
    val episodes: Boolean get() = this == SHOW || this == EPISODE
}

/** A canonical Spotify target. */
data class SpotifyRef(val kind: SpotifyKind, val id: String) {
    val target: String get() = "spotify:${kind.wire}:$id"
}

private val TARGET = Regex("^spotify:(playlist|album|track|artist|show|episode):([A-Za-z0-9]{1,64})$")

/** [text] as a canonical target, or null (an entry with any other target is hidden, its id kept). */
fun spotifyTarget(text: String?): SpotifyRef? {
    val m = TARGET.matchEntire(text ?: return null) ?: return null
    val kind = SpotifyKind.entries.first { it.wire == m.groupValues[1] }
    return SpotifyRef(kind, m.groupValues[2])
}

/** The mode the library gives a Spotify entry (`spotify`, design 26c §3.3): our list of its items. */
const val SPOTIFY_MODE_LIST = "list"

/** ...or Spotify's own order, no items (an artist; a playlist no account may list). */
const val SPOTIFY_MODE_CONTEXT = "context"

/** The modes a Spotify entry may have; any other value hides it (phase 1's lenient rule). */
val SPOTIFY_MODES = setOf(SPOTIFY_MODE_LIST, SPOTIFY_MODE_CONTEXT)

/**
 * The URI the web player plays item [key] of a [kind] entry by (design 26c §4.1, corrected after step
 * 2a): a show's and an episode entry's items are `spotify:episode:<key>`, every other kind's
 * `spotify:track:<key>`.
 */
fun uriOf(kind: SpotifyKind, key: String): String = if (kind.episodes) "spotify:episode:$key" else "spotify:track:$key"

/** [entry] is a Spotify show or episode: its items are episodes (the words "episode" and "Ny episode"). */
fun spotifyEpisodes(entry: Entry): Boolean =
    entry.source == Source.SPOTIFY && spotifyTarget(entry.target)?.kind?.episodes == true
