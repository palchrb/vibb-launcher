package me.vibb.music.logic

import me.vibb.music.model.Entry
import me.vibb.music.model.Source

/*
 * Spotify's rules on the phone (design 26c §4). Step 3 (the contract) brings only the library's
 * side: a Spotify entry parses, its items are streamed - never fetched, never kept - and, until
 * the player can play them (step 5b), shown nowhere. Pure; SpotifyRulesTest.
 */

/**
 * Whether Spotify items show and play (design 26c §11 step 3, 26b step 1's way): false until step 5b
 * builds the renderer and flips it, so a library with Spotify entries parses and shows nothing, and
 * a Spotify item never joins Nytt while it can't be played (its first listings are only
 * acknowledged, so nothing old floods the tray at the flip). [me.vibb.music.data.Snapshot] and the
 * news rules take it as their default.
 */
val SPOTIFY_PLAYABLE: Boolean = false

/** A Spotify entry: everything it lists is streamed by the web player (no url, no copy). */
fun isSpotify(entry: Entry): Boolean = entry.source == Source.SPOTIFY
