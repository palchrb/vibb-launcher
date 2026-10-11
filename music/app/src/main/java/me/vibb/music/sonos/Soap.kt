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
import javax.net.SocketFactory
import javax.xml.parsers.DocumentBuilderFactory

/*
 * The UPnP SOAP client: the dozen actions vibb reaches through SoCo (`pi/sonosd.py`; palchrb/vibb,
 * MIT License, Copyright (c) 2026 palchrb), written here because no GPL-compatible maintained UPnP
 * library exists (design 26 review #2). Design 26a §2.2. Android-free: the Wi-Fi binding comes in
 * as a SocketFactory (`Network.socketFactory`). Replies are parsed with javax.xml's DOM, which is in
 * the JDK and in Android, so the JVM tests parse for real (SonosFixturesTest).
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

/** The three UPnP services vibb drives. */
enum class SonosService(val controlPath: String, val serviceType: String) {
    AV_TRANSPORT("/MediaRenderer/AVTransport/Control", "urn:schemas-upnp-org:service:AVTransport:1"),
    RENDERING_CONTROL("/MediaRenderer/RenderingControl/Control", "urn:schemas-upnp-org:service:RenderingControl:1"),
    ZONE_GROUP_TOPOLOGY("/ZoneGroupTopology/Control", "urn:schemas-upnp-org:service:ZoneGroupTopology:1"),
}

/** Every speaker listens here; only the debug feed (the emulator's fake speaker) sets another port. */
const val SONOS_PORT = 1400

class Soap(private val client: OkHttpClient) {
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
        val call = client.newCall(request)
        if (timeoutMs != null) call.timeout().timeout(timeoutMs.coerceAtLeast(1), TimeUnit.MILLISECONDS)
        return try {
            call.execute().use { r -> r.code to (r.body?.string() ?: "") }
        } catch (e: IOException) {
            throw SpeakerUnreachable("${e.javaClass.simpleName}: ${e.message}", e)
        }
    }

    private fun url(ip: String, port: Int, path: String): HttpUrl =
        HttpUrl.Builder().scheme("http").host(ip).port(port).encodedPath(path).build()

    companion object {
        private val XML = "text/xml; charset=\"utf-8\"".toMediaType()

        /**
         * The one client (vibb's keep-alive session, sonosd.py:101-117): pooled connections,
         * connect 2 s, read 5 s, no proxy, no redirects. [socketFactory] binds it to the Wi-Fi
         * network (design 26a §2.1, QA M7).
         */
        fun client(socketFactory: SocketFactory? = null): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(2, TimeUnit.SECONDS)
            .readTimeout(5, TimeUnit.SECONDS)
            .proxy(Proxy.NO_PROXY)
            .followRedirects(false)
            .apply { if (socketFactory != null) socketFactory(socketFactory) }
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

/** One speaker over SOAP: what the session polls ([SpeakerIo]) and what the transport commands. */
class SoapSpeaker(private val soap: Soap, override val ip: String, override val port: Int, override val uid: String) : SpeakerControl {
    private fun av(action: String, vararg args: Pair<String, String>) =
        soap.call(ip, port, SonosService.AV_TRANSPORT, action, listOf("InstanceID" to "0") + args)

    private fun rc(action: String, vararg args: Pair<String, String>) =
        soap.call(ip, port, SonosService.RENDERING_CONTROL, action, listOf("InstanceID" to "0") + args)

    override fun positionInfo(): PositionInfo = av("GetPositionInfo").let {
        PositionInfo(it["Track"], it["TrackURI"], it["RelTime"], it["TrackDuration"], it["TrackMetaData"])
    }

    override fun transportInfo(): String? = av("GetTransportInfo")["CurrentTransportState"]

    /** `spk.group.coordinator.uid`: from this speaker's own view of the household. */
    override fun groupCoordinator(): String? =
        Topology.coordinatorOf(soap.call(ip, port, SonosService.ZONE_GROUP_TOPOLOGY, "GetZoneGroupState", emptyList())["ZoneGroupState"].orEmpty(), uid)

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
}
