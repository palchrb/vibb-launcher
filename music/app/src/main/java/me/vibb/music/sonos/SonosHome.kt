package me.vibb.music.sonos

import android.content.Context
import android.net.ConnectivityManager
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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient

/*
 * The household as the player service sees it (design 26a §2.1, §6.4, QA M7): the Wi-Fi network
 * (tracked with a NetworkCallback; every SOAP and SSDP socket is bound to it, and without it nothing
 * is tried), the one Soap client, the uid -> ip cache, the picker's rooms, the speakers by uid, the
 * NRK service serial and the migration probe, and renderer.json. One per PlaybackService.
 */
class SonosHome(context: Context) {
    private val app = context.applicationContext
    private val connectivity = app.getSystemService(ConnectivityManager::class.java)

    private val wifiNet = MutableStateFlow<Network?>(null)
    private val wifi: Network? get() = wifiNet.value
    private var client: Pair<Network, OkHttpClient>? = null

    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            wifiNet.value = network
        }

        override fun onLost(network: Network) {
            wifiNet.compareAndSet(network, null)
        }
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

    /** The client bound to the current Wi-Fi network; none (no Wi-Fi) is [SpeakerUnreachable]. */
    @Synchronized
    private fun client(): OkHttpClient {
        val network = wifi ?: throw SpeakerUnreachable("no Wi-Fi")
        client?.takeIf { it.first == network }?.let { return it.second }
        return Soap.client(network.socketFactory).also { client = network to it }
    }

    val soap = Soap { client() }
    val cache = SpeakerCache(SonosFiles.cache(app))

    val rooms = RoomDirectory(
        cache,
        zoneGroupState = { ip, port, timeoutMs ->
            soap.call(ip, port, SonosService.ZONE_GROUP_TOPOLOGY, "GetZoneGroupState", emptyList(), timeoutMs)["ZoneGroupState"].orEmpty()
        },
        ssdp = { wifi?.let { SsdpScanner(app).scan(it) }.orEmpty() },
        onWifi = { wifi != null },
        scope = scope,
        elapsedMs = SystemClock::elapsedRealtime,
        log = ::log,
    )
    val speakers = CachedSpeakers(cache, soap)
    val nrk = NrkService({ ip, port -> soap.get(ip, port, "/status/accounts") }, ::log)
    val probe = HouseholdProbe(rooms, soap)

    /** renderer.json (design 26a §4.1). Small: read and written on the caller's thread. */
    fun choice(): RendererChoice = RendererChoice.read(SonosFiles.renderer(app))

    fun write(choice: RendererChoice) {
        try {
            RendererChoice.write(SonosFiles.renderer(app), choice)
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

        fun log(message: String) {
            Log.i(TAG, message)
        }
    }
}
