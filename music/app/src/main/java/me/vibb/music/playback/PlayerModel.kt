package me.vibb.music.playback

/*
 * The player's outputs and the renderer's state line, as the screens see them (design 26a §3.3).
 * Android-free (OutputKeyTest). App-internal: never in the bridge AIDL or bridge_contract.json.
 */

/** [SONOS] is reserved for design 26a step 3: nothing produces it yet. */
enum class OutputKind { PHONE, BLUETOOTH, WIRED, SONOS }

/**
 * Where audio goes, by [key]: [PHONE_KEY], a connected device ([deviceKey], `AudioDeviceInfo.id`),
 * and from design 26a step 3 a Sonos room ([SONOS_PREFIX] + the room's uid).
 */
data class Output(val key: String, val kind: OutputKind, val name: String?) {
    companion object {
        const val PHONE_KEY = "phone"
        const val DEVICE_PREFIX = "dev:"

        /** Reserved: `sonos:<uid>` (a room, or a group by its coordinator), design 26a step 3. */
        const val SONOS_PREFIX = "sonos:"

        val PHONE = Output(PHONE_KEY, OutputKind.PHONE, null)

        /** The key of a connected output (`AudioDeviceInfo.id`). */
        fun deviceKey(id: Int): String = DEVICE_PREFIX + id

        /** The `AudioDeviceInfo.id` a [deviceKey] names; null for every other key. */
        fun deviceId(key: String): Int? = if (key.startsWith(DEVICE_PREFIX)) key.substring(DEVICE_PREFIX.length).toIntOrNull() else null
    }
}

/**
 * Why a Sonos renderer isn't playing what we sent it (design 26a §4.8; vibb `daemon.py:2627-2632`,
 * in this precedence), the player's one line under the title. Null while nothing is wrong, and
 * always on the phone. Not `sonos.RendererChoice` (the persisted renderer.json record).
 */
enum class RendererState { GROUPED_AWAY, TAKEN_OVER, LOST_SESSION, UNREACHABLE }
