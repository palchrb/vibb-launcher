package me.vibb.music.sonos

import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.w3c.dom.Document
import org.w3c.dom.Element
import org.xml.sax.InputSource
import java.io.IOException
import java.io.StringReader
import java.net.Proxy
import java.util.concurrent.TimeUnit
import javax.xml.parsers.DocumentBuilderFactory

/*
 * The UPnP SOAP client: the dozen actions vibb reaches through SoCo (`pi/sonosd.py`; palchrb/vibb,
 * MIT License, Copyright (c) 2026 palchrb), written here because no GPL-compatible maintained UPnP
 * library exists (design 26 review #2). Design 26a §2.2. Android-free, and its sockets are bound to
 * no `Network` (§6.5: the launcher's always-on VPN refuses the bind; the LAN is on the default route).
 * Replies are parsed with javax.xml's DOM, which is in the JDK and in Android, so the JVM tests parse
 * for real (SonosFixturesTest).
 *
 * Design 54: the speaker's own queue as SoCo drives it (`soco/core.py` `play_mode`, `play_from_queue`, `queue_size`,
 * `get_queue`, `set_sleep_timer`, `get_sleep_timer`; `soco/plugins/sharelink.py`'s `AddURIToQueue` arguments; SoCo is
 * MIT License, Copyright (c) Rahim Sonawalla and individual contributors - NOTICE.md), reimplemented here: a Browse of
 * `Q:0` with all six arguments, the reply's DIDL read with the same DOM.
 *
 * No address guard: this is control traffic to the LAN (21b §2.7 is about audio origins). Every
 * URL is built with HttpUrl.Builder from the ip and port (SourceScanTest bans http literals).
 */

/** Everything the speaker side can throw. */
open class SonosException(message: String, cause: Throwable? = null) : IOException(message, cause)

/** The speaker answered with a UPnP fault (`<s:Fault>` ... `<errorCode>`): it said no. */
class UpnpFault(val code: Int?, val description: String?) : SonosException("UPnP fault ${code ?: "?"}${description?.let { " ($it)" } ?: ""}")

/** The speaker didn't answer, or not with SOAP (timeouts, refused connections, HTML). */
class SpeakerUnreachable(message: String, cause: Throwable? = null) : SonosException(message, cause)

/** No cached speaker has this uid, or the cached ip now answers with another one (`_speaker`). */
class UnknownUid(val uid: String) : SonosException("unknown speaker $uid")

/** The UPnP services vibb drives, and the queue's ContentDirectory (design 54 §0.2: SoCo's `get_queue`, `queue_size`). */
enum class SonosService(val controlPath: String, val serviceType: String) {
    AV_TRANSPORT("/MediaRenderer/AVTransport/Control", "urn:schemas-upnp-org:service:AVTransport:1"),
    RENDERING_CONTROL("/MediaRenderer/RenderingControl/Control", "urn:schemas-upnp-org:service:RenderingControl:1"),
    ZONE_GROUP_TOPOLOGY("/ZoneGroupTopology/Control", "urn:schemas-upnp-org:service:ZoneGroupTopology:1"),
    CONTENT_DIRECTORY("/MediaServer/ContentDirectory/Control", "urn:schemas-upnp-org:service:ContentDirectory:1"),
}

/** `AddURIToQueue`'s answer (design 54 §0.2); each null when the speaker's value isn't a number. */
data class AddResult(val firstTrack: Int?, val added: Int?, val newLength: Int?)

/** `Browse Q:0 BrowseMetadata` (SoCo's `queue_size`): the container's `childCount` and the answer's `UpdateID`. */
data class QueueMeta(val childCount: Int?, val updateId: String?)

/**
 * One page of `Browse Q:0 BrowseDirectChildren` (SoCo's `get_queue`): each item's `res` as the speaker gave it (null
 * for an item without one), `TotalMatches` and `UpdateID`. Never printed whole: the uris name the kid's tracks.
 */
data class QueuePage(val res: List<String?>, val total: Int?, val updateId: String?) {
    override fun toString(): String = "QueuePage(items=${res.size}, total=$total, updateId=$updateId)"
}

/** `GetMediaInfo`: the queue's `NrTracks` and the transport's `CurrentURI` (Spotify Connect's `x-sonos-vli:` is never ours). */
data class MediaInfo(val nrTracks: Int?, val currentUri: String?) {
    /** A Storytel play's `CurrentURI` is a signed URL, a queue track's names the kid's track: printed as the other holders print a uri. */
    override fun toString(): String = "MediaInfo(nrTracks=$nrTracks, currentUri=${Fields.shown(currentUri)})"
}

/** Every speaker listens here; only the debug feed (the emulator's fake speaker) sets another port. */
const val SONOS_PORT = 1400

/**
 * The client comes from [client] on every call, so a Wi-Fi reconnect (a new `Network`, a fresh
 * connection pool) needs a new provider value only, not a rebuilt Soap and everything holding it
 * (qa-26a-step1-code L2).
 */
class Soap(private val client: () -> OkHttpClient) {
    constructor(client: OkHttpClient) : this({ client })

    /**
     * One action: POST the envelope, return the response's child elements by local name. A fault
     * is [UpnpFault]; anything that isn't a SOAP answer is [SpeakerUnreachable]. [timeoutMs] caps the
     * whole call (the topology refresh's per-ip budget); otherwise the client's 2 s/5 s apply.
     */
    fun call(ip: String, port: Int, service: SonosService, action: String, args: List<Pair<String, String>>, timeoutMs: Long? = null): Map<String, String> {
        val request = Request.Builder()
            .url(url(ip, port, service.controlPath))
            .header("SOAPACTION", "\"${service.serviceType}#$action\"")
            .post(envelope(service, action, args).toByteArray(Charsets.UTF_8).toRequestBody(XML))
            .build()
        val (code, text) = execute(request, timeoutMs)
        return parseResponse(code, text, action)
    }

    /** A plain GET on the speaker's port (the device description, `/status/accounts`); 200 only. */
    fun get(ip: String, port: Int, path: String, timeoutMs: Long? = null): String {
        val (code, text) = execute(Request.Builder().url(url(ip, port, path)).get().build(), timeoutMs)
        if (code != 200) throw SpeakerUnreachable("HTTP $code for $path")
        return text
    }

    /** The uid at [ip] (`SoCo.uid`): the device description's UDN without its `uuid:`. */
    fun uidOf(ip: String, port: Int, timeoutMs: Long? = null): String =
        parseUdn(get(ip, port, "/xml/device_description.xml", timeoutMs)) ?: throw SpeakerUnreachable("no UDN at $ip")

    private fun execute(request: Request, timeoutMs: Long?): Pair<Int, String> {
        val call = client().newCall(request)
        if (timeoutMs != null) call.timeout().timeout(timeoutMs.coerceAtLeast(1), TimeUnit.MILLISECONDS)
        return try {
            call.execute().use { r -> r.code to (r.body?.string() ?: "") }
        } catch (e: IOException) {
            throw SpeakerUnreachable("${e.javaClass.simpleName}: ${e.message}", e)
        }
    }

    /**
     * `http://<ip>:<port><path>` for an IP literal only: a name would go to the phone's DNS (the
     * launcher's filter), never the LAN's, and a bad host or port is a [SpeakerUnreachable], never an IllegalArgumentException
     * escaping the failed-play path (qa-26a-step1-code L1).
     */
    private fun url(ip: String, port: Int, path: String): HttpUrl {
        if (!isIpLiteral(ip)) throw SpeakerUnreachable("not an ip address: $ip")
        return try {
            HttpUrl.Builder().scheme("http").host(ip).port(port).encodedPath(path).build()
        } catch (e: IllegalArgumentException) {
            throw SpeakerUnreachable("bad address $ip:$port", e)
        }
    }

    companion object {
        private val XML = "text/xml; charset=\"utf-8\"".toMediaType()

        /**
         * The one client (vibb's keep-alive session, sonosd.py:101-117): pooled connections,
         * connect 2 s, read 5 s, no proxy, no redirects, the platform's own sockets (bound to no
         * network, design 26a §6.5).
         */
        fun client(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(2, TimeUnit.SECONDS)
            .readTimeout(5, TimeUnit.SECONDS)
            .proxy(Proxy.NO_PROXY)
            .followRedirects(false)
            .build()

        /** The standard envelope, each argument escaped once (SoCo's, like Python's `escape`). */
        fun envelope(service: SonosService, action: String, args: List<Pair<String, String>>): String {
            val body = args.joinToString("") { (k, v) -> "<$k>${xmlEscape(v)}</$k>" }
            return "<?xml version=\"1.0\" encoding=\"utf-8\"?>" +
                "<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\" s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\">" +
                "<s:Body><u:$action xmlns:u=\"${service.serviceType}\">$body</u:$action></s:Body></s:Envelope>"
        }

        /** A reply: the `<actionResponse>`'s children, or the fault it carries. */
        fun parseResponse(code: Int, text: String, action: String): Map<String, String> {
            val doc = runCatching { parseXml(text) }.getOrNull()
                ?: throw if (code == 200) SpeakerUnreachable("$action: no XML in the reply") else SpeakerUnreachable("$action: HTTP $code")
            val body = children(doc.documentElement).firstOrNull { it.localName == "Body" }
                ?: throw SpeakerUnreachable("$action: no SOAP body (HTTP $code)")
            val first = children(body).firstOrNull() ?: throw SpeakerUnreachable("$action: empty SOAP body")
            if (first.localName == "Fault") {
                val errorCode = descendant(first, "errorCode")?.textContent?.trim()?.toIntOrNull()
                throw UpnpFault(errorCode, descendant(first, "errorDescription")?.textContent?.trim())
            }
            if (code != 200) throw SpeakerUnreachable("$action: HTTP $code")
            return children(first).associate { (it.localName ?: it.nodeName) to it.textContent }
        }

        /** A Browse answer's `Result` (DIDL-Lite) as a page of the queue: every `item`'s `res`, in order. */
        fun parseQueuePage(fields: Map<String, String>): QueuePage {
            val items = didlRoot(fields)?.let { root -> children(root).filter { it.localName == "item" } }.orEmpty()
            val res = items.map { item -> children(item).firstOrNull { it.localName == "res" }?.textContent?.trim()?.takeIf { it.isNotEmpty() } }
            return QueuePage(res, fields["TotalMatches"]?.trim()?.toIntOrNull(), fields["UpdateID"]?.trim()?.takeIf { it.isNotEmpty() })
        }

        /** A `BrowseMetadata` answer for `Q:0`: the container's `childCount` (SoCo's `queue_size`) and `UpdateID`. */
        fun parseQueueMeta(fields: Map<String, String>): QueueMeta {
            val container = didlRoot(fields)?.let { root -> children(root).firstOrNull { it.localName == "container" } }
            return QueueMeta(container?.getAttribute("childCount")?.trim()?.toIntOrNull(), fields["UpdateID"]?.trim()?.takeIf { it.isNotEmpty() })
        }

        /** The DIDL-Lite of a Browse's `Result`; null for an empty one, [SpeakerUnreachable] for one that isn't XML. */
        private fun didlRoot(fields: Map<String, String>): Element? {
            val text = fields["Result"]?.trim().orEmpty()
            if (text.isEmpty()) return null
            return runCatching { parseXml(text).documentElement }.getOrNull() ?: throw SpeakerUnreachable("Browse: the Result isn't DIDL")
        }

        /** The device description's UDN, `uuid:` stripped. */
        fun parseUdn(xml: String): String? {
            val doc = runCatching { parseXml(xml) }.getOrNull() ?: return null
            val udn = descendant(doc.documentElement, "UDN")?.textContent?.trim() ?: return null
            return udn.removePrefix("uuid:").takeIf { it.isNotEmpty() }
        }
    }
}

/** A namespace-aware DOM of [text]; no DTDs, no external entities. */
fun parseXml(text: String): Document {
    val f = DocumentBuilderFactory.newInstance()
    f.isNamespaceAware = true
    f.isExpandEntityReferences = false
    // The JDK's parser takes these; Android's ignores DTDs anyway and throws on unknown features.
    runCatching { f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
    runCatching { f.setFeature("http://xml.org/sax/features/external-general-entities", false) }
    return f.newDocumentBuilder().parse(InputSource(StringReader(text)))
}

/** [e]'s element children. */
fun children(e: Element): List<Element> {
    val out = mutableListOf<Element>()
    val nodes = e.childNodes
    for (i in 0 until nodes.length) (nodes.item(i) as? Element)?.let(out::add)
    return out
}

/** The first element under [e] (depth first) with this local name. */
fun descendant(e: Element, localName: String): Element? {
    for (c in children(e)) {
        if ((c.localName ?: c.nodeName) == localName) return c
        descendant(c, localName)?.let { return it }
    }
    return null
}

/**
 * One speaker over SOAP: what the session polls ([SpeakerIo]) and what the transport commands.
 * [budgetMs]: every call of this instance shares one time budget from its creation (each gets what
 * is left of it; none left is [SpeakerUnreachable]) - the 1.5 s live classify of a switch home, the
 * suspension stop and the startup adopt (§4.6, §4.7, §6.1). Null: the client's timeouts.
 */
class SoapSpeaker(
    private val soap: Soap,
    override val ip: String,
    override val port: Int,
    override val uid: String,
    budgetMs: Long? = null,
) : SpeakerControl {
    private val deadline = budgetMs?.let { System.nanoTime() + it * 1_000_000 }

    private fun left(): Long? = deadline?.let {
        val ms = (it - System.nanoTime()) / 1_000_000
        if (ms <= 0) throw SpeakerUnreachable("time budget spent") else ms
    }

    private fun av(action: String, vararg args: Pair<String, String>) =
        soap.call(ip, port, SonosService.AV_TRANSPORT, action, listOf("InstanceID" to "0") + args, left())

    private fun cd(action: String, vararg args: Pair<String, String>) =
        soap.call(ip, port, SonosService.CONTENT_DIRECTORY, action, args.toList(), left())

    /** SoCo's Browse of the queue: all six arguments, `SortCriteria` too (a speaker may answer 402 without it, design 54 §0.2). */
    private fun browseQ0(flag: String, start: Int, count: Int) = cd(
        "Browse", "ObjectID" to "Q:0", "BrowseFlag" to flag, "Filter" to "*",
        "StartingIndex" to start.toString(), "RequestedCount" to count.toString(), "SortCriteria" to "",
    )

    private fun rc(action: String, vararg args: Pair<String, String>) =
        soap.call(ip, port, SonosService.RENDERING_CONTROL, action, listOf("InstanceID" to "0") + args, left())

    override fun positionInfo(): PositionInfo = av("GetPositionInfo").let {
        PositionInfo(it["Track"], it["TrackURI"], it["RelTime"], it["TrackDuration"], it["TrackMetaData"])
    }

    override fun transportInfo(): String? = av("GetTransportInfo")["CurrentTransportState"]

    /** `spk.group.coordinator.uid`: from this speaker's own view of the household. */
    override fun groupCoordinator(): String? =
        Topology.coordinatorOf(soap.call(ip, port, SonosService.ZONE_GROUP_TOPOLOGY, "GetZoneGroupState", emptyList(), left())["ZoneGroupState"].orEmpty(), uid)

    override fun volume(): Int = rc("GetVolume", "Channel" to "Master")["CurrentVolume"]?.trim()?.toIntOrNull()
        ?: throw SpeakerUnreachable("GetVolume: no CurrentVolume")

    override fun setVolume(volume: Int) {
        rc("SetVolume", "Channel" to "Master", "DesiredVolume" to volume.toString())
    }

    override fun mute(): Boolean = rc("GetMute", "Channel" to "Master")["CurrentMute"]?.trim() == "1"

    override fun setMute(mute: Boolean) {
        rc("SetMute", "Channel" to "Master", "DesiredMute" to if (mute) "1" else "0")
    }

    override fun removeAllTracksFromQueue() {
        av("RemoveAllTracksFromQueue")
    }

    override fun setAvTransportUri(uri: String, metadata: String) {
        av("SetAVTransportURI", "CurrentURI" to uri, "CurrentURIMetaData" to metadata)
    }

    override fun play() {
        av("Play", "Speed" to "1")
    }

    override fun pause() {
        av("Pause")
    }

    override fun stop() {
        av("Stop")
    }

    override fun seek(target: String) {
        av("Seek", "Unit" to "REL_TIME", "Target" to target)
    }

    override fun joinGroup(coordinator: String) {
        av("SetAVTransportURI", "CurrentURI" to rinconUri(coordinator), "CurrentURIMetaData" to "")
    }

    override fun becomeStandalone() {
        av("BecomeCoordinatorOfStandaloneGroup")
    }

    // -- design 54: the speaker's own queue (SoCo's `play_mode`, `add_share_link_to_queue`, `play_from_queue`,
    // `get_queue`, `queue_size`, `set_sleep_timer`/`get_sleep_timer`) --

    override fun setPlayMode(mode: String) {
        av("SetPlayMode", "NewPlayMode" to mode)
    }

    override fun playMode(): String? = av("GetTransportSettings")["PlayMode"]?.trim()

    override fun addUriToQueue(uri: String, metadata: String): AddResult = av(
        "AddURIToQueue", "EnqueuedURI" to uri, "EnqueuedURIMetaData" to metadata,
        "DesiredFirstTrackNumberEnqueued" to "0", "EnqueueAsNext" to "0",
    ).let { AddResult(it["FirstTrackNumberEnqueued"]?.trim()?.toIntOrNull(), it["NumTracksAdded"]?.trim()?.toIntOrNull(), it["NewQueueLength"]?.trim()?.toIntOrNull()) }

    override fun seekTrack(position: Int) {
        av("Seek", "Unit" to "TRACK_NR", "Target" to position.toString())
    }

    override fun browseQueue(start: Int, count: Int): QueuePage = Soap.parseQueuePage(browseQ0("BrowseDirectChildren", start, count))

    override fun queueMeta(): QueueMeta = Soap.parseQueueMeta(browseQ0("BrowseMetadata", 0, 1))

    override fun mediaInfo(): MediaInfo = av("GetMediaInfo").let { MediaInfo(it["NrTracks"]?.trim()?.toIntOrNull(), it["CurrentURI"]) }

    override fun configureSleepTimer(duration: String) {
        av("ConfigureSleepTimer", "NewSleepTimerDuration" to duration)
    }

    override fun remainingSleepTimer(): String? =
        av("GetRemainingSleepTimerDuration")["RemainingSleepTimerDuration"]?.trim()?.takeIf { it.isNotEmpty() }
}
