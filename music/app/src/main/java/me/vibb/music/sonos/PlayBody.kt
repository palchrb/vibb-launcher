package me.vibb.music.sonos

import me.vibb.music.logic.isPrivateAddress
import me.vibb.music.logic.publicOnly
import me.vibb.music.model.Entry
import me.vibb.music.model.PlayItem
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.net.InetAddress

/*
 * What a play hands the speaker, and which items a speaker can play at all, ported from palchrb/vibb
 * `pi/daemon.py` (`_sonos_body`, `sonos_start_target`'s filter; MIT License, Copyright (c) 2026
 * palchrb), reimplemented in Kotlin under the GPL-3.0 (or later) - see NOTICE.md. Design 26a §2.5,
 * §4.2. Pure; PlayBodyTest.
 */

/** One `/play` body (`tests/sonos_contract.py` PLAY_URL / PLAY_NRK). */
data class PlayBody(
    val uid: String,
    val kind: Kind,
    /** `url`: the audio; `nrk_program`: the radio.nrk.no series page (the speaker gets the built uri). */
    val uri: String,
    val title: String?,
    val album: String?,
    val art: String?,
    val durationS: Double?,
    val startS: Double,
    val artist: String? = null,
    val series: String? = null,
    val programId: String? = null,
)

private val NRK_SERIE = Regex("^https?://radio\\.nrk\\.no/serie/([a-z0-9_-]+)", RegexOption.IGNORE_CASE)

/** vibb's series slug from an entry's target (`re.match`, daemon.py:1275), or null. */
fun nrkSeriesSlug(target: String?): String? = NRK_SERIE.find(target.orEmpty())?.groupValues?.get(1)

object PlayBodies {
    /**
     * `_sonos_body` for [item] of [entry]: an NRK series item (the slug from the target, and the
     * item key is the program id for every NRK series listing, 26a §2.5) is `nrk_program`; anything
     * else is `url` with the item's own URL. [art] only when it is http (the phone's cover file is
     * unreachable to a speaker, which also resolves no `.local`). The listing's duration rides along:
     * an extension-less URL reads 0:00 on the speaker until it has buffered (daemon.py:1306-1313).
     * Null when the item has nothing a speaker could play ([speakerDropReason]).
     */
    fun build(entry: Entry, item: PlayItem, uid: String, startS: Double, art: String? = null, album: String? = entry.name): PlayBody? {
        if (speakerDropReason(entry, item) != null) return null
        val speakerArt = art?.takeIf { it.startsWith("http") }
        val durationS = item.durationMs?.let { it / 1000.0 }
        val slug = if (entry.nrkSerie) nrkSeriesSlug(entry.target) else null
        if (slug != null) {
            // speakerDropReason guarantees a program id (the item key) here
            return PlayBody(uid, Kind.NRK_PROGRAM, entry.target!!, item.title, album, speakerArt, durationS, startS, series = slug, programId = item.key)
        }
        return PlayBody(uid, Kind.URL, item.url!!, item.title, album, speakerArt, durationS, startS)
    }

    /**
     * The speaker-playable filter (`sonos_start_target`, 26a §4.2): why [item] can't go to a speaker,
     * or null when it can. Own files and withdrawn items (no URL) can't; an HLS item only as an NRK
     * series program; a public entry's item never from a private address literal (the speaker
     * fetches by itself, so the phone's address guard moves here, QA M7). Names aren't resolved.
     * The URL is read as OkHttp and ExoPlayer read it (`toHttpUrlOrNull`: an enclosure with an
     * unencoded space or `|` plays on the phone, so it goes to the speaker too; qa-26a-step1-code L6).
     * An NRK series item without a key (its program id) has nothing to hand the service and drops out
     * rather than falling through to its HLS manifest as a `url`.
     */
    fun speakerDropReason(entry: Entry, item: PlayItem): String? {
        if (item.fileId != null) return "own file"
        val url = item.url ?: return "withdrawn"
        val http = url.toHttpUrlOrNull() ?: return "not an http url"
        val nrkSeries = entry.nrkSerie && nrkSeriesSlug(entry.target) != null
        if (nrkSeries && item.key.isEmpty()) return "no program id"
        if (item.hls && !nrkSeries) return "hls"
        if (publicOnly(entry) && privateLiteral(http.host)) return "private address"
        return null
    }

    fun speakerPlayable(entry: Entry, item: PlayItem): Boolean = speakerDropReason(entry, item) == null

    /**
     * [host] (`HttpUrl.host`) is an IP literal in a private range (21b §2.7); a name is never looked
     * up. Numeric shorthand hosts (`10.1`, `167772161`) count as names here and pass (on the phone the
     * connect-time guard would catch them; a speaker would fetch them): accepted, since the server
     * checked the entry's hosts (21b §2.7) and the parent curates the library.
     */
    internal fun privateLiteral(host: String): Boolean {
        val bare = host.removePrefix("[").removeSuffix("]")
        if (':' in bare) {
            // HttpUrl has already validated an IPv6 literal: parsing it does no DNS.
            val address = runCatching { InetAddress.getByName(bare) }.getOrNull() ?: return true
            return isPrivateAddress(address)
        }
        val octets = bare.split('.')
        if (octets.size != 4 || octets.any { !Regex("[0-9]{1,3}").matches(it) || it.toInt() > 255 }) return false
        return isPrivateAddress(InetAddress.getByAddress(octets.map { it.toInt().toByte() }.toByteArray()))
    }
}
