package me.vibb.music.sonos

import me.vibb.music.model.Entry
import me.vibb.music.model.PlayItem
import me.vibb.music.model.Source
import java.io.File

/** Test helpers for the sonos package: fixtures, a clock, a recording speaker (no Android). */
object SonosFakes {
    /** A canned reply from src/test/resources/sonos/ (scripts/fake-sonos.py serves the same files). */
    fun fixture(name: String): String = javaClass.classLoader!!.getResource("sonos/$name")!!.readText()

    /** Every fixture file (the files only: `ws/`, the event socket's messages, is SonosEventsTest's). */
    fun fixtureNames(): List<String> =
        // !! on both: android.jar marks parentFile and listFiles() nullable, the JDK leaves them platform types
        File(javaClass.classLoader!!.getResource("sonos/device_description.xml")!!.toURI()).parentFile!!.listFiles()!!.filter { it.isFile }.map { it.name }.sorted()

    /** A reply's fields, as Soap returns them. */
    fun reply(name: String, action: String): Map<String, String> = Soap.parseResponse(200, fixture(name), action)

    fun position(name: String): PositionInfo = reply(name, "GetPositionInfo").let {
        PositionInfo(it["Track"], it["TrackURI"], it["RelTime"], it["TrackDuration"], it["TrackMetaData"])
    }

    fun transport(name: String): String? = reply(name, "GetTransportInfo")["CurrentTransportState"]

    fun zoneGroupState(name: String): String = reply(name, "GetZoneGroupState").getValue("ZoneGroupState")

    fun entry(
        id: Long = 1,
        source: Source = Source.RSS,
        target: String? = "https://podkast.example/feed.xml",
        name: String = "Barnas Supersommer",
        lan: Boolean = false,
    ) = Entry(id, "e$id", name, null, source, target, "auto", 5, true, null, "0123456789abcdef", id.toInt(), lan)

    fun item(
        key: String = "ep12",
        url: String? = "https://podkast.example/ep12.mp3",
        hls: Boolean = false,
        durationMs: Long? = 1_453_000,
        fileId: Long? = null,
        entryId: Long = 1,
    ) = PlayItem(entryId, key, "Sommerfuglen", url, hls, durationMs, fileId)
}

/** Elapsed time that moves only when told to, or when the code under test sleeps. */
class FakeClock(var now: Long = 1_000_000L) {
    val sleeps = mutableListOf<Long>()

    fun advance(ms: Long) {
        now += ms
    }

    val clock = SonosClock(elapsedMs = { now }, sleep = { ms ->
        sleeps.add(ms)
        now += ms
    })
}

/**
 * A speaker recording every command it gets (vibb's FakeSpk/FakeTransport): the poll reads come
 * from [position]/[transport], [failOn] makes one command throw.
 */
class FakeSpeaker(
    override val uid: String = "RINCON_TEST01400",
    var muted: Boolean = false,
    override val ip: String = "10.0.0.11",
    override val port: Int = SONOS_PORT,
) : SpeakerControl {
    val log = mutableListOf<String>()
    var position = PositionInfo("1", "", "0:00:00", "0:00:00", "")
    var transport: String? = "PLAYING"
    var coordinator: String? = null
    var vol = 30
    var failOn: Map<String, () -> Exception> = emptyMap()

    /** Seek faults, one per attempt (then success). */
    val seekFaults = ArrayDeque<Exception>()

    /** Several speakers' calls in the order they came, as "<uid> <entry>" (a group's start cap). */
    var shared: MutableList<String>? = null

    private fun record(entry: String, name: String = entry) {
        failOn[name]?.let { throw it() }
        log.add(entry)
        shared?.add("$uid $entry")
    }

    /** The commands only (what vibb's fakes logged): mute sets, set-uri, play, pause, stop, seek, volume. */
    val commands: List<String> get() = log.filter { !it.startsWith("get") && it != "clearqueue" }

    override fun positionInfo(): PositionInfo {
        record("getposition")
        return position
    }

    override fun transportInfo(): String? {
        record("gettransport")
        return transport
    }

    override fun groupCoordinator(): String? {
        record("getgroup")
        return coordinator
    }

    override fun volume(): Int {
        record("getvolume")
        return vol
    }

    override fun setVolume(volume: Int) {
        record("volume $volume", "volume")
        vol = volume
    }

    override fun mute(): Boolean {
        record("getmute")
        return muted
    }

    override fun setMute(mute: Boolean) {
        record("mute $mute", "mute")
        muted = mute
    }

    override fun removeAllTracksFromQueue() = record("clearqueue")

    var lastUri: String? = null
    var lastMeta: String? = null

    override fun setAvTransportUri(uri: String, metadata: String) {
        record("seturi")
        lastUri = uri
        lastMeta = metadata
    }

    override fun play() = record("play")

    override fun pause() = record("pause")

    override fun stop() = record("stop")

    override fun seek(target: String) {
        failOn["seek"]?.let { throw it() }
        seekFaults.removeFirstOrNull()?.let {
            log.add("seek $target refused")
            throw it
        }
        log.add("seek $target")
    }
}
