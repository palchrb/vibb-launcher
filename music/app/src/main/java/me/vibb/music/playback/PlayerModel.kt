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
 * Why a renderer isn't playing what we sent it, the player's one line under the title. Null while
 * nothing is wrong, and always for the phone's own player. Not `sonos.RendererChoice` (the persisted
 * renderer.json record).
 *
 * - A Sonos room's four (design 26a §4.8; vibb `daemon.py:2627-2632`, in this precedence): [remote].
 * - Spotify's web player on the phone (design 26c §5.8, QA M6): why a start didn't happen or playback
 *   stopped - no Wi-Fi, no internet (and the quota's [SPOTIFY_UNAVAILABLE]), an adult's matter, no
 *   Widevine, the account playing elsewhere, the page's renderer gone. Not [remote]: the call rule
 *   (`callStep`) counts a paused Spotify renderer with one of these as paused. The names say whose
 *   renderer it is; the kid's lines never do (26c step 8: no source on the kid's screens).
 */
enum class RendererState(val remote: Boolean) {
    GROUPED_AWAY(true),
    TAKEN_OVER(true),
    LOST_SESSION(true),
    UNREACHABLE(true),

    /** "Trenger Wi-Fi": a network the gate refuses (mobile data not allowed, or roaming). */
    SPOTIFY_NEEDS_WIFI(false),

    /** "Trenger internett": offline, the SDK or `ready` not reached, the play call unreachable, no state for 30 s. */
    SPOTIFY_NEEDS_INTERNET(false),

    /** "Ikke tilgjengelig nå": the play call's 429 `QUOTA_EXCEEDED` (§5.8 gives it NEEDS_INTERNET's place with its own text). */
    SPOTIFY_UNAVAILABLE(false),

    /** "Spør en voksen": the login ended, the play refused, no Premium. */
    SPOTIFY_ASK_ADULT(false),

    /** "Virker ikke på telefonen": no EME/Widevine in the web player. */
    SPOTIFY_NOT_ON_PHONE(false),

    /** "Spilles et annet sted": a confirmed `null` state - the account plays on another device. */
    SPOTIFY_ELSEWHERE(false),

    /** "Stoppet - trykk spill": the web player's renderer process died. */
    SPOTIFY_STOPPED(false),
}
