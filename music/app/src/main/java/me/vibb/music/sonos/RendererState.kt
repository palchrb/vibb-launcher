package me.vibb.music.sonos

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/*
 * Who makes sound, persisted: ported from palchrb/vibb `pi/vibb/renderer.py` (`read`, `write`,
 * `is_sonos`; MIT License, Copyright (c) 2026 palchrb), reimplemented in Kotlin under the GPL-3.0
 * (or later) - see NOTICE.md. Design 26a §4.1. Android-free; the file is
 * `noBackupFilesDir/renderer.json` (SonosFiles.kt); SpeakerCacheTest.
 *
 * The renderer axis is not the output axis: `output` is where OUR player writes audio (the phone,
 * a headset); the renderer is who plays (the phone, or a Sonos room it controls).
 */

private val RENDERER_JSON = Json { ignoreUnknownKeys = true; explicitNulls = false; encodeDefaults = true }

@Serializable
data class RendererState(
    /** "local" or "sonos". */
    val renderer: String = LOCAL,
    val uid: String? = null,
    val name: String? = null,
    @SerialName("entry_id") val entryId: Long? = null,
    @SerialName("item_key") val itemKey: String? = null,
    /** The BUILT uri (for `nrk_program` the `x-sonos-http` one): what adopt compares against. */
    val uri: String? = null,
    /** The last volume read from the speaker: the next play restores it after a cut sleep ramp (§5.2). */
    val volume: Int? = null,
) {
    val sonos: Boolean get() = renderer == SONOS && !uid.isNullOrEmpty()

    companion object {
        const val LOCAL = "local"
        const val SONOS = "sonos"
        val LOCAL_STATE = RendererState()

        /** `read`: anything that isn't sonos-with-a-uid (a missing file, garbage) is local. */
        fun read(file: File): RendererState {
            val s = runCatching { RENDERER_JSON.decodeFromString(serializer(), file.readText()) }.getOrNull()
            return if (s != null && s.sonos) s else LOCAL_STATE
        }

        /** `write`: tmp + rename. */
        fun write(file: File, state: RendererState) {
            writeAtomic(file, RENDERER_JSON.encodeToString(serializer(), state).toByteArray())
        }
    }
}
