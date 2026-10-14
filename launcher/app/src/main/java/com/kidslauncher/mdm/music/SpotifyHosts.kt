package com.kidslauncher.mdm.music

/*
 * Spotify's hosts for the music app's web player (design 26c §6.2): the on-device DNS filter
 * (KidVpnService/DnsFilterEngine) never blocks them while the cached policy's `music.spotify` is
 * true - this phone plays Spotify - whatever a blocklist says (ad lists often carry
 * `spclient.wg.spotify.com`). Per phone, unlike FCM's global exemption (push/FcmHosts.kt): the
 * server drops them from that phone's delivered list too, and the parent's device page says so.
 * The list is server/testdata/spotify_hosts.json's (byte-identical in this app's and the music
 * app's test resources; SpotifyHostsTest, MusicPlanTest). PROVISIONAL until the spike's DNS capture
 * replaces it, before any tag of design 26c's release (its "Release" names the one commit: the three
 * JSON copies with their comment, these constants, the server's and the music app's). Pure.
 */

/** A domain equal to or under one of these is Spotify's. */
val SPOTIFY_HOST_SUFFIXES = listOf("spotify.com", "scdn.co", "spotifycdn.com", "spotifycdn.net", "pscdn.co")

/** Third-party CDN hosts of Spotify's audio: exempted by exact name only, never their parent. */
val SPOTIFY_EXACT_HOSTS = listOf("audio-ak-spotify-com.akamaized.net", "audio-akp-quic-spotify-com.akamaized.net")

/** Is [domainRaw] (any case, a trailing dot allowed) one of Spotify's hosts? */
fun isSpotifyHost(domainRaw: String): Boolean {
    val domain = domainRaw.trimEnd('.').lowercase()
    if (domain in SPOTIFY_EXACT_HOSTS) return true
    return SPOTIFY_HOST_SUFFIXES.any { suffix ->
        domain == suffix || (domain.endsWith(".$suffix") && domain.length > suffix.length + 1)
    }
}
