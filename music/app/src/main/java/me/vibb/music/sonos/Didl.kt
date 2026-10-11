package me.vibb.music.sonos

/*
 * The DIDL-Lite metadata a play hands the speaker, ported from palchrb/vibb `pi/sonosd.py`
 * (`DIDL_NS`, `didl()`; MIT License, Copyright (c) 2026 palchrb), reimplemented in Kotlin under the
 * GPL-3.0 (or later) - see NOTICE.md. Design 26a §2.3. Pure; DidlTest.
 *
 * Each value is escaped here exactly once; the SOAP layer escapes the whole string again when it
 * inlines it as the `CurrentURIMetaData` argument (Soap.kt). Two levels, once each - dropping either
 * is the classic "audio plays, metadata blank", and the `&amp;amp;` on the wire is correct.
 */

/** `DIDL_NS`. */
const val DIDL_NS = "xmlns=\"urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/\" " +
    "xmlns:dc=\"http://purl.org/dc/elements/1.1/\" " +
    "xmlns:upnp=\"urn:schemas-upnp-org:metadata-1-0/upnp/\" " +
    "xmlns:r=\"urn:schemas-rinconnetworks-com:metadata-1-0/\""

const val CLASS_MUSIC_TRACK = "object.item.audioItem.musicTrack"

/**
 * One DIDL-Lite item (`didl()`). [title] defaults to "Vibb"; [artist] goes out as both `dc:creator`
 * and `upnp:artist` (different Sonos controller versions read different ones); [art] must be an
 * http URL the speaker can reach; [desc] is the `cdudn` descriptor a service item (NRK) needs; the
 * `res` carries `duration` when [durationS] is non-zero, and [protocol] defaults to
 * `http-get:*:<mime>:*`. Empty strings count as absent, as in Python.
 */
fun didl(
    uri: String,
    title: String?,
    artist: String? = null,
    album: String? = null,
    art: String? = null,
    durationS: Double? = null,
    upnpClass: String = CLASS_MUSIC_TRACK,
    protocol: String? = null,
    desc: String? = null,
): String {
    val proto = protocol.takeUnless { it.isNullOrEmpty() } ?: "http-get:*:${mimeFor(uri)}:*"
    val dur = if (durationS != null && durationS != 0.0) " duration=\"${hms(durationS)}\"" else ""
    val tags = StringBuilder()
    tags.append("<dc:title>").append(xmlEscape(title.takeUnless { it.isNullOrEmpty() } ?: "Vibb")).append("</dc:title>")
    if (!artist.isNullOrEmpty()) {
        tags.append("<dc:creator>").append(xmlEscape(artist)).append("</dc:creator>")
        tags.append("<upnp:artist>").append(xmlEscape(artist)).append("</upnp:artist>")
    }
    if (!album.isNullOrEmpty()) tags.append("<upnp:album>").append(xmlEscape(album)).append("</upnp:album>")
    if (!art.isNullOrEmpty()) tags.append("<upnp:albumArtURI>").append(xmlEscape(art)).append("</upnp:albumArtURI>")
    tags.append("<upnp:class>").append(xmlEscape(upnpClass)).append("</upnp:class>")
    if (!desc.isNullOrEmpty()) {
        tags.append("<desc id=\"cdudn\" nameSpace=\"urn:schemas-rinconnetworks-com:metadata-1-0/\">").append(xmlEscape(desc)).append("</desc>")
    }
    tags.append("<res protocolInfo=\"").append(proto).append('"').append(dur).append('>').append(xmlEscape(uri)).append("</res>")
    return "<DIDL-Lite $DIDL_NS><item id=\"vibb-1\" parentID=\"-1\" restricted=\"true\">$tags</item></DIDL-Lite>"
}
