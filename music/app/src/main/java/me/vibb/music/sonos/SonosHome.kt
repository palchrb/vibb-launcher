package me.vibb.music.sonos

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import me.vibb.music.BuildConfig
import me.vibb.music.logic.logName
import me.vibb.music.logic.releaseLogLine
import okhttp3.OkHttpClient
import java.net.Inet4Address

/*
 * The household as the player service sees it (design 26a §2.1, §6.4, §6.5): the Wi-Fi network
 * (tracked with a NetworkCallback; a gate only - without Wi-Fi nothing is tried - never a socket
 * binding: the launcher's always-on VPN allows no bypass, so Android refuses `bindSocket` to another
 * network with EPERM, and unbound sockets reach the LAN anyway because the VPN routes only
 * 192.0.2.2/32), the one Soap client, the uid -> ip cache, the picker's rooms, the speakers by uid,
 * the NRK service serial and the migration probe, and renderer.json. One per PlaybackService.
 */
class SonosHome(context: Context) {
    private val app = context.applicationContext
    private val connectivity = app.getSystemService(ConnectivityManager::class.java)

    private val wifiNet = MutableStateFlow<Network?>(null)
    private val wifi: Network? get() = wifiNet.value

    /**
     * The Wi-Fi network (a gate, never a binding): the event socket closes at any change of it and opens
     * again on the new one (design 53 §2.2 - B's onAvailable may come before A's onLost).
     */
    val wifiNetwork: StateFlow<Network?> = wifiNet
    private var client: Pair<Network, OkHttpClient>? = null

    /** The Wi-Fi network's identity ([SpeakerCache.networkKey], from its `LinkProperties`), set before [wifiNet]. */
    private val wifiKey = MutableStateFlow<Pair<Network, String?>?>(null)

    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            wifiKey.value = network to keyOf(connectivity?.getLinkProperties(network))
            wifiNet.value = network
        }

        override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) {
            if (wifiNet.value == network || wifiKey.value?.first == network) wifiKey.value = network to keyOf(linkProperties)
        }

        override fun onLost(network: Network) {
            wifiNet.compareAndSet(network, null)
        }
    }

    /**
     * The Wi-Fi connection now (2026-11-21): this connection's `Network.getNetworkHandle()` (new at every reconnect:
     * the sheet's fresh mark) and the LAN's key (the same after a reconnect: which household the sheet shows). No
     * location permission - never the SSID or BSSID.
     */
    fun link(): WifiLink? {
        val network = wifiNet.value ?: return null
        return WifiLink(network.networkHandle, wifiKey.value?.takeIf { it.first == network }?.second)
    }

    /** Where the background SSDP round runs (RoomDirectory). */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    init {
        try {
            connectivity?.registerNetworkCallback(NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI).build(), callback)
        } catch (e: RuntimeException) {
            Log.w(TAG, "no Wi-Fi tracking (${e.javaClass.simpleName}): Sonos stays unreachable")
        }
    }

    /**
     * Wi-Fi is known: true at once when it is, else after the network callback's first answer
     * (registering it delivers the current network within a moment), false after [timeoutMs].
     * A start's adopt and the suspension receiver ask this before their one classify.
     */
    suspend fun awaitWifi(timeoutMs: Long): Boolean =
        wifi != null || withTimeoutOrNull(timeoutMs) { wifiNet.first { it != null } } != null

    /**
     * The client while Wi-Fi is up (none is [SpeakerUnreachable]): unbound sockets on the default
     * route (§6.5), a fresh pool for each Wi-Fi network so no connection outlives the one it was
     * made on.
     */
    @Synchronized
    private fun client(): OkHttpClient {
        val network = wifi ?: throw SpeakerUnreachable("no Wi-Fi")
        client?.takeIf { it.first == network }?.let { return it.second }
        return Soap.client().also { client = network to it }
    }

    val soap = Soap { client() }

    /**
     * The event socket's own client for [ip], the cached ip of [uid] (design 53 §2.2, 53-c): this network's SOAP client
     * trusting the pinned Sonos root only, the certificate naming [uid].
     */
    fun socketClient(ip: String, uid: String): OkHttpClient =
        // named: two Strings, and swapped every speaker would be refused (udn mismatch) - SourceScanTest's d53 pins it
        speakerSocketClient(client(), ip = ip, uid = uid, log = ::log, certLog = if (BuildConfig.DEBUG) ::log else null)
    val cache = SpeakerCache(SonosFiles.cache(app))

    val rooms = RoomDirectory(
        cache,
        zoneGroupState = { ip, port, timeoutMs ->
            soap.call(ip, port, SonosService.ZONE_GROUP_TOPOLOGY, "GetZoneGroupState", emptyList(), timeoutMs)["ZoneGroupState"].orEmpty()
        },
        ssdp = { if (wifi != null) SsdpScanner(app).scan() else emptyList() },
        wifi = ::link,
        scope = scope,
        elapsedMs = SystemClock::elapsedRealtime,
        log = ::log,
        // design 53 §2.7's busy look: each other row's coordinator's transport
        transportState = { ip, port, timeoutMs ->
            soap.call(ip, port, SonosService.AV_TRANSPORT, "GetTransportInfo", listOf("InstanceID" to "0"), timeoutMs)["CurrentTransportState"]
        },
        scoped = { roomScope },
    )
    val speakers = CachedSpeakers(cache, soap)

    /** The parent's room scope as the service last read it (null = every room): the cache's prune keeps those rooms. */
    @Volatile var roomScope: List<String>? = null

    init {
        // the sheet follows the Wi-Fi network (2026-11-21): a known household at once on its network, none elsewhere -
        // the rows published again, the cache read as it is (never a topology call: discovery is never on a timer)
        scope.launch {
            combine(wifiNet, wifiKey) { _, _ -> link() }.distinctUntilChanged().drop(1).collect {
                try {
                    rooms.view()
                } catch (e: RuntimeException) {
                    log("rooms: not published at a network change (${e.javaClass.simpleName})")
                }
            }
        }
    }

    /** The grouping verbs on other rooms (design 53 §2.6): one at a time, on IO, never in a room's actor. */
    val verbs = GroupVerbs(speakers, ::log)
    val nrk = NrkService({ ip, port -> soap.get(ip, port, "/status/accounts") }, ::log)
    val probe = HouseholdProbe(rooms, soap)

    /** renderer.json (design 26a §4.1). Small: read and written on the caller's thread. */
    fun choice(): RendererChoice = RendererChoice.read(SonosFiles.renderer(app))

    fun write(choice: RendererChoice) {
        try {
            RendererChoice.write(SonosFiles.renderer(app), choice)
            // the home's evidence for the report (P-L2): the room the phone plays on
            choice.uid?.takeIf { choice.sonos }?.let { uid -> runCatching { cache.markPlayed(uid) } }
        } catch (e: java.io.IOException) {
            log("renderer.json not written (${e.javaClass.simpleName})")
        }
    }

    /** What the picker calls the room [uid] (a group's joined label), else the cached name. */
    fun label(uid: String): String? = rooms.state.value.rooms.firstOrNull { it.uid == uid }?.label ?: cache.resolve(uid)?.name

    fun release() {
        try {
            connectivity?.unregisterNetworkCallback(callback)
        } catch (_: RuntimeException) {
        }
        scope.cancel()
    }

    companion object {
        const val TAG = "VibbSonos"

        /** The LAN's key from the Wi-Fi network's link: its IPv4 subnets and default gateways ([SpeakerCache.networkKey]). */
        fun keyOf(lp: LinkProperties?): String? = lp?.let { p ->
            SpeakerCache.networkKey(
                WifiFacts(
                    addresses = p.linkAddresses.filter { it.address is Inet4Address }.map { "${it.address.hostAddress}/${it.prefixLength}" },
                    gateways = p.routes.filter { it.isDefaultRoute && it.gateway is Inet4Address }.mapNotNull { it.gateway?.hostAddress },
                ),
            )
        }

        /** Every Sonos line: as it is in a debug build, through [releaseLogLine] in a release one (P-L1). */
        fun log(message: String) {
            Log.i(TAG, if (BuildConfig.DEBUG) message else releaseLogLine(message))
        }

        /** A room's name or a title in a line (P-L1): only a debug build logs it. */
        fun name(name: String?): String = logName(name, BuildConfig.DEBUG)

        /** The room hand-over's timing lines ([HandOverTiming]: step names, kinds, counts and positions only). */
        fun handOver(message: String) {
            Log.i(HandOverTiming.TAG, message)
        }
    }
}
