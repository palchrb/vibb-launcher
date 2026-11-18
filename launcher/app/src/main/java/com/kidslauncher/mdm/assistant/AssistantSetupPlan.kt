package com.kidslauncher.mdm.assistant

import kotlinx.serialization.Serializable

/*
 * Design 98 §3: the default-apps window's snapshot and comparison and the Wi-Fi step's end (the steps themselves are
 * design 114's setup flow, `setup/SetupSteps.kt`). Pure (`AssistantSetupTest`).
 */

/** §3.2: the default-apps window lasts at most this long. */
const val ASSISTANT_PICK_MS = 5 * 60_000L

/** §3.5: the Wi-Fi step lasts at most this long. */
const val WIFI_STEP_MS = 5 * 60_000L

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

/**
 * Design 114 §2.2.1 (QA H1): the [stored] moved roles still moved - `home` while Vibb doesn't hold HOME, `browser` while
 * `https` resolves elsewhere than [before]'s, `dialer` and `sms` while not held as [before] held them. Call screening
 * and redirection leave (row 13 reads them). Without [before] (a list stored by an older build) only `home` can be read
 * back; the browser stays listed.
 */
fun stillMoved(stored: List<String>, before: DefaultAppsSnapshot?, now: DefaultAppsSnapshot): List<String> = stored.filter { role ->
    when (role) {
        "home" -> !now.home
        "browser" -> before?.browser == null || now.browser != before.browser
        "dialer" -> before == null || before.dialer != now.dialer
        "sms" -> before == null || before.sms != now.sms
        else -> false
    }
}
