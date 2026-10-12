package me.vibb.music.sonos

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import me.vibb.music.logic.storedUri
import me.vibb.music.model.Entry
import java.io.File

/*
 * Who makes sound, persisted: ported from palchrb/vibb `pi/vibb/renderer.py` (`read`, `write`,
 * `is_sonos`; MIT License, Copyright (c) 2026 palchrb), reimplemented in Kotlin under the GPL-3.0
 * (or later) - see NOTICE.md. Design 26a §4.1. Android-free; the file is
 * `noBackupFilesDir/renderer.json` (SonosFiles.kt); SpeakerCacheTest.
 *
 * Named apart from design 26a §3.3's `RendererState`, the player screen's grouped-away / taken-over
 * line (qa-26a-step1-code L5). The renderer axis is not the output axis: `output` is where OUR player
 * writes audio (the phone, a headset); the renderer is who plays (the phone, or a Sonos room).
 *
 * Design 26b §5.3 (QA M1): a Storytel play's [RendererChoice.uri] is the marker `storytel:book:<cid>`,
 * never the signed URL (vibb keeps signed URLs out of its state files). Every write of a uri goes
 * through [played], [followed] or [adopted], which go through `storedUri` (GuardsTest pins it).
 */

private val RENDERER_JSON = Json { ignoreUnknownKeys = true; explicitNulls = false; encodeDefaults = true }

@Serializable
data class RendererChoice(
    /** "local" or "sonos". */
    val renderer: String = LOCAL,
    val uid: String? = null,
    val name: String? = null,
    @SerialName("entry_id") val entryId: Long? = null,
    @SerialName("item_key") val itemKey: String? = null,
    /**
     * The BUILT uri (for `nrk_program` the `x-sonos-http` one): what adopt compares against - for a
     * Storytel book the marker `storytel:book:<cid>` (design 26b §5.3), never its signed URL.
     */
    val uri: String? = null,
    /** A sleep ramp's base volume while it runs (§5.2): the next play on [volumeUid] puts it back if the process died mid-ramp. */
    val volume: Int? = null,
    /** The room [volume] belongs to (qa-26a-step3a-code L1: never one room's level onto another). */
    @SerialName("volume_uid") val volumeUid: String? = null,
) {
    val sonos: Boolean get() = renderer == SONOS && !uid.isNullOrEmpty()

    /** A play of [itemKey] of [entry] sent as [uri] landed: what a later adopt compares against, through [storedUri]. */
    fun played(entry: Entry, itemKey: String, uri: String): RendererChoice =
        copy(entryId = entry.id, itemKey = itemKey, uri = storedUri(entry, itemKey, uri))

    /**
     * Migration follow (design 26a §4.8): the new room, and the uri its adopt went out with for
     * [itemKey] of [entry] through [storedUri] - the stored one kept when the entry isn't known.
     */
    fun followed(uid: String, name: String?, entry: Entry?, itemKey: String?, uri: String): RendererChoice =
        copy(uid = uid, name = name, uri = entry?.let { storedUri(it, itemKey, uri) } ?: this.uri)

    /**
     * Adopt at start (design 26a §4.7) found [itemKey] of [entry] playing as [liveUri]: the stored built
     * uri kept, or with none stored yet the live one - both through [storedUri], so a Storytel book keeps
     * its marker, never the live signed URL the session goes on with (design 26b §5.3, QA M1).
     */
    fun adopted(entry: Entry, itemKey: String, liveUri: String): RendererChoice =
        copy(itemKey = itemKey, uri = storedUri(entry, itemKey, uri?.takeIf { it.isNotEmpty() } ?: liveUri))

    companion object {
        const val LOCAL = "local"
        const val SONOS = "sonos"
        val LOCAL_CHOICE = RendererChoice()

        /**
         * `read`: anything that isn't sonos-with-a-uid (a missing file, garbage) is local - keeping a cut
         * ramp's volume and its room, which outlive a switch home and a restart.
         */
        fun read(file: File): RendererChoice {
            val s = runCatching { RENDERER_JSON.decodeFromString(serializer(), file.readText()) }.getOrNull()
            return if (s != null && s.sonos) s else local(s)
        }

        /** The phone renders; a cut ramp's volume and room are kept from [previous]. */
        fun local(previous: RendererChoice?): RendererChoice =
            if (previous?.volume != null && previous.volumeUid != null) LOCAL_CHOICE.copy(volume = previous.volume, volumeUid = previous.volumeUid) else LOCAL_CHOICE

        /** `write`: tmp + rename. */
        fun write(file: File, state: RendererChoice) {
            writeAtomic(file, RENDERER_JSON.encodeToString(serializer(), state).toByteArray())
        }
    }
}
