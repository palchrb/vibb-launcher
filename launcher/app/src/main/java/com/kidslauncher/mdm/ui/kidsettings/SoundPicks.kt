package com.kidslauncher.mdm.ui.kidsettings

import java.net.URLDecoder

/*
 * The kid's ringtone and message sound in Kid Settings (PLAN, approved 2026-11-04; mockup "Barnets innstillinger med
 * lyder") - pure, no Android imports, tested in SoundPicksTest. KidSounds reads and writes the phone,
 * KidSettingsActivity and KidSoundPickerActivity render.
 *
 * Two rows under the sound row ("Ringetone", "Meldingslyd"), shown with the parent's sound bit
 * (`QuickControlFeature.SOUND`) - no new bit. Each opens our own picker: the phone's built-in sounds as radio rows, a
 * tap plays it (on the media stream, so it is heard while the phone is in Lydløs) and makes it the phone's default.
 * Never the system picker (its "add a sound" opens a file browser past the kiosk), no custom files, and no "None":
 * the kid must not silence the parents' calls (Lydløs exists). Written through the "Modify system settings" app-op
 * the touch row already needs; without it the rows show the phone's sounds, read-only. The ringer mode is no input:
 * the pickers work in Lydløs too.
 *
 * No vibration rows: Android 13+ (the launcher's minSdk is 34) decides a call's vibration from the hidden
 * `RING_VIBRATION_INTENSITY` alone (Telecom's `SystemSettingsUtil.isRingVibrationEnabled`; the public
 * `VIBRATE_WHEN_RINGING` is deprecated and read by nothing), and `NOTIFICATION_VIBRATION_INTENSITY` is hidden too -
 * SettingsProvider refuses a non-public System key to an unprivileged app (IllegalArgumentException).
 */

/** Which of the phone's two default sounds. */
enum class SoundKind { RINGTONE, MESSAGE }

/** The two rows as the phone is now: whether a tap may change them (`Settings.System.canWrite`). `null` = no rows
 * (the parent's sound bit is off). */
data class SoundPicksState(val writable: Boolean)

fun soundPicksState(wanted: Boolean, canWrite: Boolean): SoundPicksState? = if (wanted) SoundPicksState(canWrite) else null

/** What a row shows for the phone's current sound: its title, none set at all (an older choice; the picker offers
 * no way back to it), or a sound whose title can't be read. */
sealed interface SoundValue {
    data class Named(val title: String) : SoundValue
    data object NoSound : SoundValue
    data object Unnamed : SoundValue
}

/** The row's value from the stored setting ([uri]; `null`/blank = none) and the title read for it, if any - else the
 * `title` a canonical media URI carries. */
fun soundValue(uri: String?, readTitle: String?): SoundValue = when {
    uri.isNullOrBlank() -> SoundValue.NoSound
    !readTitle.isNullOrBlank() -> SoundValue.Named(readTitle)
    else -> uriTitle(uri)?.let { SoundValue.Named(it) } ?: SoundValue.Unnamed
}

/** One sound from the phone's list (RingtoneManager's cursor): its URI as the cursor gives it and its title. */
data class PickerSound(val uri: String, val title: String)

/** One radio row of the picker; [checked] is the phone's current default. */
data class PickerRow(val sound: PickerSound, val checked: Boolean)

/**
 * The picker's leading row: the phone's [current] sound when it isn't one of the built-in [sounds] (one the parent set
 * from a file), with its [currentTitle] (else a canonical URI's title), so the kid can go back to it. The picker takes
 * it once, when it opens, and keeps it while open. `null` when the current sound is built in, has no title, or none is
 * set.
 */
fun leadSound(sounds: List<PickerSound>, current: String?, currentTitle: String?): PickerSound? {
    if (current.isNullOrBlank() || builtIn(sounds).any { sameSound(it.uri, current) }) return null
    val title = currentTitle?.takeIf { it.isNotBlank() } ?: uriTitle(current) ?: return null
    return PickerSound(current, title)
}

/**
 * The picker's rows: the [lead] sound if any, then the phone's built-in sounds ([isBuiltInSound]; the cursor also
 * lists audio files on the shared storage - not offered) in the cursor's order, each once; the phone's [current] one
 * checked, none while none is set. Never a "None" row.
 */
fun pickerRows(sounds: List<PickerSound>, lead: PickerSound?, current: String?): List<PickerRow> =
    (listOfNotNull(lead) + builtIn(sounds)).map { PickerRow(it, sameSound(it.uri, current)) }

/** The built-in sounds with a URI and a title, each once (the first of two that name the same sound). */
private fun builtIn(sounds: List<PickerSound>): List<PickerSound> {
    val seen = HashSet<String>()
    return sounds.filter { it.uri.isNotBlank() && it.title.isNotBlank() && isBuiltInSound(it.uri) && seen.add(soundKey(it.uri)) }
}

/** A tap on [tapped]: always heard; written only when it isn't already the phone's [saved] sound. */
fun pickerTapWrites(saved: String?, tapped: String): Boolean = !sameSound(saved, tapped)

/** Two URIs name the same sound when they differ only by a canonical URI's query (`?title=…&canonical=1`), a fragment
 * or the user id Android puts before the authority (`content://0@media/…`). */
fun sameSound(a: String?, b: String?): Boolean = !a.isNullOrBlank() && !b.isNullOrBlank() && soundKey(a) == soundKey(b)

internal fun soundKey(uri: String): String {
    val bare = uri.substringBefore('#').substringBefore('?')
    val scheme = bare.substringBefore("://", missingDelimiterValue = "")
    if (scheme.isEmpty()) return bare
    val rest = bare.substringAfter("://")
    val authority = rest.substringBefore('/').substringAfter('@')
    val path = rest.substringAfter('/', missingDelimiterValue = "")
    return "$scheme://$authority/$path"
}

/** A sound on the phone's own (internal) media volume: `content://media/internal/…`. */
fun isBuiltInSound(uri: String): Boolean = soundKey(uri).startsWith("content://media/internal/")

/** The `title` parameter of a canonical media URI, decoded; `null` when there is none. */
internal fun uriTitle(uri: String): String? {
    val query = uri.substringBefore('#').substringAfter('?', missingDelimiterValue = "")
    val raw = query.split('&').firstOrNull { it.startsWith("title=") }?.substringAfter("title=") ?: return null
    return runCatching { URLDecoder.decode(raw, "UTF-8") }.getOrNull()?.takeIf { it.isNotBlank() }
}
