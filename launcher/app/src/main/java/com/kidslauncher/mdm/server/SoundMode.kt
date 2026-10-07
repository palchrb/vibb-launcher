package com.kidslauncher.mdm.server

import com.kidslauncher.mdm.lock.INTERRUPTION_FILTER_ALARMS
import com.kidslauncher.mdm.lock.INTERRUPTION_FILTER_ALL
import com.kidslauncher.mdm.lock.INTERRUPTION_FILTER_NONE
import com.kidslauncher.mdm.lock.INTERRUPTION_FILTER_PRIORITY
import com.kidslauncher.mdm.lock.RINGER_MODE_NORMAL
import com.kidslauncher.mdm.lock.RINGER_MODE_SILENT
import com.kidslauncher.mdm.lock.RINGER_MODE_VIBRATE

/*
 * Sound mode on the kid's Settings page (design 18, docs/design/18-sound-mode.md at the monorepo
 * root) - pure, no Android imports, tested in SoundModeTest. QuickControls reads the phone and
 * acts; KidSettingsActivity renders.
 *
 * Two choices only (decision after QA #1): Sound = RINGER_MODE_NORMAL and "Silent (vibrate)" =
 * RINGER_MODE_VIBRATE. An app's RINGER_MODE_SILENT is Android's Do Not Disturb (ZenModeHelper's
 * ringer delegate turns on a manual DND), and an app's Normal/Vibrate while any DND is on ends it
 * and every active automatic rule (bedtime, schedules) - so the row never offers Silent and is
 * read-only while the interruption filter isn't ALL. Normal <-> Vibrate never touches DND and needs
 * no notification-policy access.
 */

/** The row's two choices. */
enum class SoundChoice { SOUND, SILENT_VIBRATE }

/** Why the row shows a line under the choices. */
enum class SoundNote {
    /** Do Not Disturb is on (any filter but ALL): the row is read-only, a tap would end it. */
    DND_ON,

    /** The phone is fully silent (the volume panel's ringer button): neither choice is selected. */
    FULLY_SILENT,

    /** The state can't be read, or leaving Silent would need notification-policy access we don't
     * have (defensive - the device owner always has it, QA #3): read-only, the volume keys work. */
    USE_VOLUME_KEYS,
}

/**
 * What the row shows: the selected choice (`null` while fully silent or unknown), whether a tap
 * may change it, and an optional note.
 */
data class SoundRowState(val selected: SoundChoice?, val enabled: Boolean, val note: SoundNote?)

/** For the log (tag `KidSettings`), so a hidden or read-only row can be told apart on a device. */
fun SoundRowState?.describeSoundRow(): String =
    if (this == null) "sound row hidden (no vibrator)" else "sound selected=$selected enabled=$enabled note=$note"

/**
 * The row from the phone's state. `null` = no row: on a phone without a vibrator AudioService
 * turns Vibrate into Silent, i.e. DND (QA #7). [ringerMode] is `AudioManager.getRingerMode()`
 * (-1 when unreadable), [interruptionFilter] `NotificationManager.getCurrentInterruptionFilter()`
 * (0 = unknown), [policyAccess] `isNotificationPolicyAccessGranted()` - only needed to leave
 * Silent (`wouldToggleZenMode`: the external mode crosses Silent).
 */
fun soundRowState(ringerMode: Int, interruptionFilter: Int, hasVibrator: Boolean, policyAccess: Boolean): SoundRowState? {
    if (!hasVibrator) return null
    val selected = when (ringerMode) {
        RINGER_MODE_NORMAL -> SoundChoice.SOUND
        RINGER_MODE_VIBRATE -> SoundChoice.SILENT_VIBRATE
        else -> null
    }
    return when {
        // A parent's, bedtime's or the kid's own DND: never ended from here.
        interruptionFilter in DND_FILTERS -> SoundRowState(selected, enabled = false, note = SoundNote.DND_ON)
        // Unknown filter: a tap might end a DND we can't see.
        interruptionFilter != INTERRUPTION_FILTER_ALL -> SoundRowState(selected, enabled = false, note = SoundNote.USE_VOLUME_KEYS)
        selected != null -> SoundRowState(selected, enabled = true, note = null)
        ringerMode == RINGER_MODE_SILENT && policyAccess -> SoundRowState(null, enabled = true, note = SoundNote.FULLY_SILENT)
        else -> SoundRowState(null, enabled = false, note = SoundNote.USE_VOLUME_KEYS)
    }
}

private val DND_FILTERS = setOf(INTERRUPTION_FILTER_PRIORITY, INTERRUPTION_FILTER_NONE, INTERRUPTION_FILTER_ALARMS)

/**
 * The ringer mode a tap on [choice] sets, or `null` for nothing (no row, read-only, or already
 * selected). Never RINGER_MODE_SILENT. Evaluated on a fresh [soundRowState] at the tap, so a DND
 * that began after the render is never ended.
 */
fun soundTapMode(state: SoundRowState?, choice: SoundChoice): Int? {
    if (state == null || !state.enabled || state.selected == choice) return null
    return when (choice) {
        SoundChoice.SOUND -> RINGER_MODE_NORMAL
        SoundChoice.SILENT_VIBRATE -> RINGER_MODE_VIBRATE
    }
}

/** Status report `ringer_mode`: `null` when unknown (left out of the JSON). */
fun ringerModeName(ringerMode: Int): String? = when (ringerMode) {
    RINGER_MODE_NORMAL -> "normal"
    RINGER_MODE_VIBRATE -> "vibrate"
    RINGER_MODE_SILENT -> "silent"
    else -> null
}

/** Status report `interruption_filter` (Do Not Disturb): `null` when unknown (left out). */
fun interruptionFilterName(filter: Int): String? = when (filter) {
    INTERRUPTION_FILTER_ALL -> "all"
    INTERRUPTION_FILTER_PRIORITY -> "priority"
    INTERRUPTION_FILTER_NONE -> "none"
    INTERRUPTION_FILTER_ALARMS -> "alarms"
    else -> null
}
