package me.vibb.music.spotify

/*
 * Spotify's hosts (design 26c §6.2, §7.2): the music app's web player loads nothing else
 * (`WebPolicy`), and the launcher's DNS filter never blocks them on a phone with Spotify on. The list
 * is server/testdata/spotify_hosts.json's, byte-identical in this app's and the launcher's test
 * resources (ContractFilesTest, MusicPlanTest); SpotifyHostsTest checks these constants against the
 * music copy, and the rule is the server's `spotify::is_spotify_host` and the launcher's
 * `isSpotifyHost`. PROVISIONAL until the spike's DNS capture replaces it, before any tag of design
 * 26c's release: then the three JSON copies (with their comment) and the three sides' constants change
 * in one commit (26c "Release"). Pure.
 */

/** A host equal to or under one of these is Spotify's. */
val SPOTIFY_HOST_SUFFIXES = listOf("spotify.com", "scdn.co", "spotifycdn.com", "spotifycdn.net", "pscdn.co")

/** Third-party CDN hosts of Spotify's audio: taken by exact name only, never their parent. */
val SPOTIFY_EXACT_HOSTS = listOf("audio-ak-spotify-com.akamaized.net", "audio-akp-quic-spotify-com.akamaized.net")

/** Is [hostRaw] (any case, a trailing dot allowed) one of Spotify's hosts? */
fun isSpotifyHost(hostRaw: String): Boolean {
    val host = hostRaw.trimEnd('.').lowercase()
    if (host in SPOTIFY_EXACT_HOSTS) return true
    return SPOTIFY_HOST_SUFFIXES.any { suffix ->
        host == suffix || (host.endsWith(".$suffix") && host.length > suffix.length + 1)
    }
}
