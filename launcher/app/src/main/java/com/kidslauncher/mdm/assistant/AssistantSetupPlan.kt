package com.kidslauncher.mdm.assistant

import com.kidslauncher.mdm.R
import com.kidslauncher.mdm.server.SystemPageAccess
import kotlinx.serialization.Serializable

/*
 * Design 98 §3: "Fullfør oppsett" in the PIN-gated Settings - the rows' states, the default-apps window's snapshot and
 * comparison, the Wi-Fi step's end, the setup notification's count. Pure (`AssistantSetupTest`).
 */

/** §3.2: the default-apps window lasts at most this long. */
const val ASSISTANT_PICK_MS = 5 * 60_000L

/** §3.5: the Wi-Fi step lasts at most this long. */
const val WIFI_STEP_MS = 5 * 60_000L

/** The "Digital assistent" row's states: chosen, or [SystemPageAccess]'s. */
enum class AssistantRow { CHOSEN, CAN_OPEN, PAUSE_FIRST, WAIT, NO_PAGE }

fun assistantRowState(roleHeld: Boolean, access: SystemPageAccess): AssistantRow = when {
    roleHeld -> AssistantRow.CHOSEN
    else -> when (access) {
        SystemPageAccess.CAN_OPEN -> AssistantRow.CAN_OPEN
        SystemPageAccess.PAUSE_FIRST -> AssistantRow.PAUSE_FIRST
        SystemPageAccess.WAIT -> AssistantRow.WAIT
        SystemPageAccess.NO_PAGE -> AssistantRow.NO_PAGE
    }
}

fun assistantRowText(row: AssistantRow): Int = when (row) {
    AssistantRow.CHOSEN -> R.string.settings_assistant_chosen
    AssistantRow.CAN_OPEN -> R.string.settings_assistant_open
    AssistantRow.PAUSE_FIRST -> R.string.settings_assistant_pause_first
    AssistantRow.WAIT -> R.string.settings_assistant_wait
    AssistantRow.NO_PAGE -> R.string.settings_assistant_no_page
}

/** §3.3: the row shows while the parent's bit 64 is on or the role is held. */
fun assistantRowShown(bit: Boolean, roleHeld: Boolean): Boolean = bit || roleHeld

/** The "Wi-Fi i flymodus" row's states (§3.5), shown once the role is held. */
enum class WifiApmRow { KEPT, TURNS_OFF, NOT_SET, CAN_START, PAUSE_FIRST }

fun wifiApmRowState(wifiKept: Boolean?, windowOpen: Boolean): WifiApmRow = when (wifiKept) {
    true -> WifiApmRow.KEPT
    false -> WifiApmRow.TURNS_OFF
    null -> if (windowOpen) WifiApmRow.CAN_START else WifiApmRow.PAUSE_FIRST
}

fun wifiApmRowText(row: WifiApmRow): Int = when (row) {
    WifiApmRow.KEPT -> R.string.settings_wifi_apm_kept
    WifiApmRow.TURNS_OFF -> R.string.settings_wifi_apm_turns_off
    WifiApmRow.NOT_SET -> R.string.settings_wifi_apm_not_set
    WifiApmRow.CAN_START -> R.string.settings_wifi_apm_start
    WifiApmRow.PAUSE_FIRST -> R.string.settings_wifi_apm_pause_first
}

/** D10: the "Samtalefilter" row, while calls are managed and either call-screening role isn't ours. */
fun screeningRowShown(callsManaged: Boolean, screeningHeld: Boolean, redirectionHeld: Boolean): Boolean =
    callsManaged && !(screeningHeld && redirectionHeld)

/**
 * §6.2's rule, the steps a phone needs and hasn't done: Bakgrunnstilkobling always; Endre systeminnstillinger with
 * bit 16 or the clock format in use; Digital assistent with bit 64; Wi-Fi i flymodus with bit 64 and the role (done
 * once set up either way); Samtalefilter (D10) while calls are managed.
 */
fun setupStepsLeft(
    batteryExempt: Boolean?,
    writeSettingsNeeded: Boolean,
    writeSettingsGranted: Boolean?,
    airplaneBit: Boolean,
    roleHeld: Boolean,
    wifiKept: Boolean?,
    screeningNeeded: Boolean,
): Int = listOf(
    batteryExempt == false,
    writeSettingsNeeded && writeSettingsGranted == false,
    airplaneBit && !roleHeld,
    airplaneBit && roleHeld && wifiKept == null,
    screeningNeeded,
).count { it }

/** §3.2: who holds each exclusive role Vibb can read, at the window's start and end. [browser] the package `https`
 * resolves to (`null` unknown, `android` the chooser). */
@Serializable
data class DefaultAppsSnapshot(
    val home: Boolean,
    val dialer: Boolean,
    val sms: Boolean,
    val callScreening: Boolean,
    val callRedirection: Boolean,
    val browser: String?,
)

/**
 * §3.2: the roles the window moved that Vibb can't take back (`default_apps_changed`): call screening, call redirection
 * and the browser, and HOME when the re-seat didn't restore it. The dialer and SMS roles are taken back by the
 * device-owner calls of the same apply.
 */
fun movedRoles(before: DefaultAppsSnapshot, after: DefaultAppsSnapshot): List<String> = listOfNotNull(
    "home".takeIf { before.home && !after.home },
    "call_screening".takeIf { before.callScreening && !after.callScreening },
    "call_redirection".takeIf { before.callRedirection && !after.callRedirection },
    "browser".takeIf { before.browser != null && after.browser != null && before.browser != after.browser },
)

/** §3.2: the window closes for good once it is no longer active (the role became ours, the page answered, 5 minutes,
 * the pause's end, a reboot) - and the comparison runs then. */
fun windowCloses(stored: Boolean, active: Boolean, pauseOpen: Boolean): Boolean = stored && (!active || !pauseOpen)
