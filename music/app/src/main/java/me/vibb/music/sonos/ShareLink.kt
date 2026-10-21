package me.vibb.music.sonos

/*
 * A Spotify link into the speaker's own queue, as SoCo's ShareLink plugin builds it (`soco/plugins/sharelink.py`:
 * `SpotifyShare.canonical_uri`, `extract`, `ShareClass.magic`, `ShareLinkPlugin.add_share_link_to_queue`'s
 * `EnqueuedURI` and metadata template; SoCo is MIT License, Copyright (c) Rahim Sonawalla and individual
 * contributors), reimplemented in Kotlin under the GPL-3.0 (or later) - see NOTICE.md. What palchrb/vibb's
 * `spotify_sharelink` sends through it (`pi/sonosd.py` `Session.play`; MIT License, Copyright (c) 2026 palchrb).
 * Design 54 §0.2, §2.2. Pure; ShareLinkTest compares it byte for byte with `src/test/resources/sonos/sharelink_*.xml`.
 *
 * The metadata is SoCo's template, field for field, with `dc:title` empty (vibb passes no `dc_title`): the user's
 * DIDL rule (music/CLAUDE.md, "Ikke endre noe i didl!!") holds for it the same way - nothing added. This file is
 * the only one that spells `x-rincon-cpcontainer` (SourceScanTest).
 */

/** SoCo's `magic()`: the `EnqueuedURI`'s prefix, the metadata item id's key and its `upnp:class`, per share type. */
enum class ShareType(val wire: String, val prefix: String, val key: String, val upnpClass: String) {
    ALBUM("album", "x-rincon-cpcontainer:1004206c", "00040000", "object.container.album.musicAlbum"),
    EPISODE("episode", "", "00032020", "object.item.audioItem.musicTrack"),
    TRACK("track", "", "00032020", "object.item.audioItem.musicTrack"),
    SHOW("show", "x-rincon-cpcontainer:1006206c", "1006206c", "object.container.playlistContainer"),
    PLAYLIST("playlist", "x-rincon-cpcontainer:1006206c", "1006206c", "object.container.playlistContainer"),
}

/**
 * The music service types SoCo tries a Spotify link with, in order: `SpotifyShare` (2311 = sid 9 x 256 + 7), then
 * `SpotifyUSShare` (3079 = sid 12) - the second only after the first answered with a UPnP fault (design 54 §0.2).
 */
val SHARE_SERVICES = listOf(2311, 3079)

/** `SpotifyShare.canonical_uri`'s pattern (`re.search`): no `artist` - an artist has no share type. */
private val SHARE_LINK = Regex("spotify.*[:/](album|episode|playlist|show|track)[:/](\\w+)")

/** One link as the speaker gets it. */
data class ShareLink(val type: ShareType, val canonical: String) {
    /** `extract`: every `:` of the canonical uri as `%3a` (lower case). */
    val encoded: String get() = canonical.replace(":", "%3a")

    /** `AddURIToQueue`'s `EnqueuedURI`: the type's prefix + the encoded uri (a track's and an episode's: no prefix). */
    val enqueuedUri: String get() = type.prefix + encoded

    /** `AddURIToQueue`'s `EnqueuedURIMetaData` for service type [sn] (SoCo's template, `dc:title` empty). */
    fun metadata(sn: Int): String =
        "<DIDL-Lite xmlns:dc=\"http://purl.org/dc/elements/1.1/\" xmlns:upnp=\"urn:schemas-upnp-org:metadata-1-0/upnp/\" " +
            "xmlns:r=\"urn:schemas-rinconnetworks-com:metadata-1-0/\" xmlns=\"urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/\">" +
            "<item id=\"${type.key}$encoded\" parentID=\"-1\" restricted=\"true\"><dc:title></dc:title>" +
            "<upnp:class>${type.upnpClass}</upnp:class>" +
            "<desc id=\"cdudn\" nameSpace=\"urn:schemas-rinconnetworks-com:metadata-1-0/\">SA_RINCON${sn}_X_#Svc$sn-0-Token</desc>" +
            "</item></DIDL-Lite>"

    /** Never the Spotify id in a line (design 54 §2.14): the type only. */
    override fun toString(): String = "ShareLink(${type.wire})"

    companion object {
        /** [text] (a canonical target or a share link) as SoCo reads it, or null: an artist, junk, another service. */
        fun of(text: String?): ShareLink? {
            val m = SHARE_LINK.find(text ?: return null) ?: return null
            val type = ShareType.entries.first { it.wire == m.groupValues[1] }
            return ShareLink(type, "spotify:${m.groupValues[1]}:${m.groupValues[2]}")
        }
    }
}

/** `EnqueuedURI` for [target], or null when SoCo wouldn't take it (an artist, junk). */
fun shareLinkUri(target: String?): String? = ShareLink.of(target)?.enqueuedUri

/** `EnqueuedURIMetaData` for [target] with service type [sn], or null when SoCo wouldn't take it. */
fun shareLinkMetadata(target: String?, sn: Int): String? = ShareLink.of(target)?.metadata(sn)
