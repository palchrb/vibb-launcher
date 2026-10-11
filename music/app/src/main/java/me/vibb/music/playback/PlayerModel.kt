package me.vibb.music.playback

/*
 * The player's outputs and the renderer's state line, as the screens see them (design 26a §3.3).
 * Android-free (OutputKeyTest). App-internal: never in the bridge AIDL or bridge_contract.json.
 */

/** [SONOS]: a Sonos room (or a group by its coordinator) renders; the others are the phone's own outputs. */
enum class OutputKind { PHONE, BLUETOOTH, WIRED, SONOS }

/**
 * Where audio goes, by [key]: [PHONE_KEY], a connected device ([deviceKey], `AudioDeviceInfo.id`),
 * or a Sonos room ([sonosKey]: [SONOS_PREFIX] + the room's uid). Choosing a room is a play (design 26a
 * §3.3: `Commands.OUTPUT` to a Sonos key is gated like the play commands).
 */
data class Output(val key: String, val kind: OutputKind, val name: String?) {
    companion object {
        const val PHONE_KEY = "phone"
        const val DEVICE_PREFIX = "dev:"

        /** `sonos:<uid>`: a room, or a group by its coordinator. */
        const val SONOS_PREFIX = "sonos:"

        val PHONE = Output(PHONE_KEY, OutputKind.PHONE, null)

        /** The key of a connected output (`AudioDeviceInfo.id`). */
        fun deviceKey(id: Int): String = DEVICE_PREFIX + id

        /** The `AudioDeviceInfo.id` a [deviceKey] names; null for every other key. */
        fun deviceId(key: String): Int? = if (key.startsWith(DEVICE_PREFIX)) key.substring(DEVICE_PREFIX.length).toIntOrNull() else null

        /** The key of the Sonos room (or group coordinator) [uid]. */
        fun sonosKey(uid: String): String = SONOS_PREFIX + uid

        /** The room uid a [sonosKey] names; null for every other key (and for a bare prefix). */
        fun sonosUid(key: String?): String? =
            key?.takeIf { it.startsWith(SONOS_PREFIX) }?.substring(SONOS_PREFIX.length)?.trim()?.takeIf { it.isNotEmpty() }
    }
}

/**
 * Why a Sonos renderer isn't playing what we sent it (design 26a §4.8; vibb `daemon.py:2627-2632`,
 * in this precedence), the player's one line under the title. Null while nothing is wrong, and
 * always on the phone. Not `sonos.RendererChoice` (the persisted renderer.json record).
 */
enum class RendererState { GROUPED_AWAY, TAKEN_OVER, LOST_SESSION, UNREACHABLE }
