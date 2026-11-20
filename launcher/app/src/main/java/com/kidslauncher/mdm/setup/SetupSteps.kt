package com.kidslauncher.mdm.setup

import com.kidslauncher.mdm.R

/*
 * Design 114 §2: the phone's setup steps - one table, one need rule, shared with the server through
 * `setup_steps.json` (`SetupStepsTest`; the server's `setup_status::tests::the_contract`). Pure: the live readings come
 * from [SetupReadings], the policy's inputs and ticks from the caller.
 */

enum class StepNeed { REQUIRED, OPTIONAL }

/** Who decides done: the phone's own reading, the server's data (the Parent PIN, read here from the cached policy), or
 * the parent's tick. */
enum class StepReadBy { PHONE, SERVER, PARENT }

/** §2.3: when a step applies. */
enum class StepWhen { ALWAYS, CALLS_MANAGED, CALLS_UNMANAGED, WRITE_SETTINGS_USED, AIRPLANE, AIRPLANE_ROLE, ANDROID_LOCK, MAP_LINKS, LINK_GATE }

enum class StepSummary { COUNT, OWN_ROW }

enum class StepLevel { ACTION, ALARM }

/**
 * §2.2, in table order (the order everywhere). [pageActions] are the Android actions the flow's "Åpne" tries, in order
 * (`null`: no page on the phone - never a flow screen); [howRes] the screen's one line, `null` for the two steps that
 * are never screens and for the fingerprint (its name says it, code QA L8).
 */
enum class SetupStep(
    val key: String,
    val need: StepNeed,
    val readBy: StepReadBy,
    val rule: StepWhen,
    val summary: StepSummary,
    val level: StepLevel,
    val features: List<String>,
    val pageActions: List<String>?,
    val nameRes: Int,
    val howRes: Int?,
) {
    PARENT_PIN(
        "parent_pin", StepNeed.REQUIRED, StepReadBy.SERVER, StepWhen.ALWAYS, StepSummary.OWN_ROW, StepLevel.ALARM,
        emptyList(), null, R.string.setup_step_parent_pin, null,
    ),
    DEVICE_OWNER(
        "device_owner", StepNeed.REQUIRED, StepReadBy.PHONE, StepWhen.ALWAYS, StepSummary.OWN_ROW, StepLevel.ALARM,
        emptyList(), null, R.string.setup_step_device_owner, null,
    ),
    // Design 114b A: the phone has a PIN. Set from the PWA (so the server knows it): a screen with no "Åpne", which
    // moves on by itself when the PIN lands.
    CHILD_PIN(
        "child_pin", StepNeed.REQUIRED, StepReadBy.PHONE, StepWhen.ALWAYS, StepSummary.COUNT, StepLevel.ACTION,
        emptyList(), null, R.string.setup_step_child_pin, R.string.setup_how_child_pin,
    ),
    LOCATION(
        "location", StepNeed.REQUIRED, StepReadBy.PHONE, StepWhen.ALWAYS, StepSummary.COUNT, StepLevel.ACTION,
        listOf("locate", "wifi_signin"), listOf("android.settings.APPLICATION_DETAILS_SETTINGS"),
        R.string.setup_step_location, R.string.setup_how_location,
    ),
    NOTIFICATION_ACCESS(
        "notification_access", StepNeed.REQUIRED, StepReadBy.PHONE, StepWhen.ALWAYS, StepSummary.COUNT, StepLevel.ACTION,
        listOf("badges", "glance", "music_card", "notification_filter"),
        listOf("android.settings.NOTIFICATION_LISTENER_DETAIL_SETTINGS", "android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS"),
        R.string.setup_step_notification_access, R.string.setup_how_notification_access,
    ),
    MOBILE_DATA(
        "mobile_data", StepNeed.REQUIRED, StepReadBy.PHONE, StepWhen.ALWAYS, StepSummary.COUNT, StepLevel.ACTION,
        listOf("mobile_choices"),
        listOf("android.settings.NETWORK_OPERATOR_SETTINGS", "android.settings.panel.action.INTERNET_CONNECTIVITY"),
        R.string.setup_step_mobile_data, R.string.setup_how_mobile_data,
    ),
    DATA_SAVER(
        "data_saver", StepNeed.REQUIRED, StepReadBy.PHONE, StepWhen.ALWAYS, StepSummary.COUNT, StepLevel.ACTION,
        listOf("mobile_choices", "app_push"), listOf("android.settings.IGNORE_BACKGROUND_DATA_RESTRICTIONS_SETTINGS"),
        R.string.setup_step_data_saver, R.string.setup_how_data_saver,
    ),
    BACKGROUND(
        "background", StepNeed.REQUIRED, StepReadBy.PHONE, StepWhen.ALWAYS, StepSummary.COUNT, StepLevel.ACTION,
        listOf("app_push"), listOf("android.settings.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS"),
        R.string.setup_step_background, R.string.setup_how_background,
    ),
    VIBB_NOTIFICATIONS(
        "vibb_notifications", StepNeed.REQUIRED, StepReadBy.PHONE, StepWhen.ALWAYS, StepSummary.COUNT, StepLevel.ACTION,
        emptyList(), listOf("android.settings.APP_NOTIFICATION_SETTINGS"),
        R.string.setup_step_vibb_notifications, R.string.setup_how_vibb_notifications,
    ),
    FULL_SCREEN_INTENT(
        "full_screen_intent", StepNeed.REQUIRED, StepReadBy.PHONE, StepWhen.CALLS_MANAGED, StepSummary.COUNT, StepLevel.ACTION,
        listOf("calls"), listOf("android.settings.MANAGE_APP_USE_FULL_SCREEN_INTENT"),
        R.string.setup_step_full_screen_intent, R.string.setup_how_full_screen_intent,
    ),
    WRITE_SETTINGS(
        "write_settings", StepNeed.REQUIRED, StepReadBy.PHONE, StepWhen.WRITE_SETTINGS_USED, StepSummary.COUNT, StepLevel.ACTION,
        listOf("touch", "clock_format"), listOf("android.settings.action.MANAGE_WRITE_SETTINGS"),
        R.string.setup_step_write_settings, R.string.setup_how_write_settings,
    ),
    ASSISTANT(
        "assistant", StepNeed.REQUIRED, StepReadBy.PHONE, StepWhen.AIRPLANE, StepSummary.COUNT, StepLevel.ACTION,
        listOf("airplane"), listOf("android.settings.VOICE_INPUT_SETTINGS"),
        R.string.setup_step_assistant, R.string.setup_how_assistant,
    ),
    WIFI_AIRPLANE(
        "wifi_airplane", StepNeed.REQUIRED, StepReadBy.PHONE, StepWhen.AIRPLANE_ROLE, StepSummary.COUNT, StepLevel.ACTION,
        listOf("airplane"), listOf("android.settings.panel.action.WIFI"),
        R.string.setup_step_wifi_airplane, R.string.setup_how_wifi_airplane,
    ),
    CALL_FILTER(
        "call_filter", StepNeed.REQUIRED, StepReadBy.PHONE, StepWhen.CALLS_MANAGED, StepSummary.COUNT, StepLevel.ACTION,
        listOf("calls"), listOf("android.app.role.action.REQUEST_ROLE"),
        R.string.setup_step_call_filter, R.string.setup_how_call_filter,
    ),
    // Design 114b B: the same two roles offered while calls aren't managed, so turning them on later needs nothing on
    // the phone. Optional: not managing calls is the parent's choice.
    CALL_FILTER_EARLY(
        "call_filter_early", StepNeed.OPTIONAL, StepReadBy.PHONE, StepWhen.CALLS_UNMANAGED, StepSummary.COUNT, StepLevel.ACTION,
        emptyList(), listOf("android.app.role.action.REQUEST_ROLE"),
        R.string.setup_step_call_filter_early, R.string.setup_how_call_filter_early,
    ),
    DEFAULT_APPS(
        "default_apps", StepNeed.REQUIRED, StepReadBy.PHONE, StepWhen.ALWAYS, StepSummary.COUNT, StepLevel.ACTION,
        emptyList(), listOf("android.settings.MANAGE_DEFAULT_APPS_SETTINGS"),
        R.string.setup_step_default_apps, R.string.setup_how_default_apps,
    ),
    // Design 119 §4.1: Vibb One as Android's browser while the link gate is on - the role request, its restriction
    // lifted while the dialog is open (`logins/BrowserRoleStep`).
    VIBB_BROWSER(
        "vibb_browser", StepNeed.REQUIRED, StepReadBy.PHONE, StepWhen.LINK_GATE, StepSummary.COUNT, StepLevel.ACTION,
        listOf("login_browser"), listOf("android.app.role.action.REQUEST_ROLE"),
        R.string.setup_step_vibb_browser, R.string.setup_how_vibb_browser,
    ),
    MAP_PLAY_LINKS(
        "map_play_links", StepNeed.REQUIRED, StepReadBy.PHONE, StepWhen.MAP_LINKS, StepSummary.COUNT, StepLevel.ACTION,
        listOf("map_links"), listOf("android.settings.APP_OPEN_BY_DEFAULT_SETTINGS"),
        R.string.setup_step_map_play_links, R.string.setup_how_map_play_links,
    ),
    MAP_VIBB_LINKS(
        "map_vibb_links", StepNeed.REQUIRED, StepReadBy.PHONE, StepWhen.MAP_LINKS, StepSummary.COUNT, StepLevel.ACTION,
        listOf("map_links"), listOf("android.settings.APP_OPEN_BY_DEFAULT_SETTINGS"),
        R.string.setup_step_map_vibb_links, R.string.setup_how_map_vibb_links,
    ),
    FINGERPRINT(
        "fingerprint", StepNeed.OPTIONAL, StepReadBy.PHONE, StepWhen.ANDROID_LOCK, StepSummary.COUNT, StepLevel.ACTION,
        emptyList(), listOf("android.settings.BIOMETRIC_ENROLL", "android.settings.FINGERPRINT_ENROLL"),
        // Code QA L8: no line - "Legg til et fingeravtrykk." only repeated the name; Åpne opens the enrolment itself.
        R.string.setup_step_fingerprint, null,
    ),
    VIBRATION(
        "vibration", StepNeed.OPTIONAL, StepReadBy.PARENT, StepWhen.ALWAYS, StepSummary.COUNT, StepLevel.ACTION,
        emptyList(), listOf("android.settings.SOUND_SETTINGS"), R.string.setup_step_vibration, R.string.setup_how_vibration,
    ),
    TOUCH_FEEDBACK(
        "touch_feedback", StepNeed.OPTIONAL, StepReadBy.PARENT, StepWhen.ALWAYS, StepSummary.COUNT, StepLevel.ACTION,
        emptyList(), listOf("android.settings.SOUND_SETTINGS"),
        R.string.setup_step_touch_feedback, R.string.setup_how_touch_feedback,
    ),
    LOCK_CLOCK(
        "lock_clock", StepNeed.OPTIONAL, StepReadBy.PARENT, StepWhen.ANDROID_LOCK, StepSummary.COUNT, StepLevel.ACTION,
        emptyList(), listOf("android.settings.DISPLAY_SETTINGS"), R.string.setup_step_lock_clock, R.string.setup_how_lock_clock,
    ),
    MEDIA_ON_LOCK(
        "media_on_lock", StepNeed.OPTIONAL, StepReadBy.PARENT, StepWhen.ANDROID_LOCK, StepSummary.COUNT, StepLevel.ACTION,
        emptyList(), listOf("android.settings.SOUND_SETTINGS"),
        R.string.setup_step_media_on_lock, R.string.setup_how_media_on_lock,
    ),
    MESSAGE_BANNERS(
        "message_banners", StepNeed.OPTIONAL, StepReadBy.PARENT, StepWhen.ALWAYS, StepSummary.COUNT, StepLevel.ACTION,
        emptyList(), listOf("android.settings.MANAGE_APPLICATIONS_SETTINGS"),
        R.string.setup_step_message_banners, R.string.setup_how_message_banners,
    ),
    ;

    /** Done in the PWA, not on the phone (design 114b: the Child PIN): a screen with its line and no "Åpne". */
    val inPwa: Boolean get() = this == CHILD_PIN

    /** Never a flow screen (§6.2.2, §6.2.3): the Parent PIN is the PWA's, Device Owner is the QR's. */
    val screen: Boolean get() = pageActions != null || inPwa

    companion object {
        fun of(key: String): SetupStep? = entries.firstOrNull { it.key == key }
    }
}

/** The quick-control bits the need rule reads (`QuickControlFeature`'s values). */
private const val BIT_SOUND = 8L
private const val BIT_TOUCH = 16L
private const val BIT_AIRPLANE = 64L

/** The packages of design 110's map apps (110 §7.1's targets): `map_links` needs one installed. */
val MAP_APP_PACKAGES = setOf("app.organicmaps", "app.organicmaps.web", "app.comaps.fdroid", "app.comaps", "app.comaps.google", "app.comaps.huawei")

/** `map_links`'s map app: one of [MAP_APP_PACKAGES] among [packages], the report's installed packages (a hidden app
 * counts, as the server reads `installed_apps`). */
fun mapAppInstalled(packages: Collection<String>): Boolean = MAP_APP_PACKAGES.any { it in packages }

/**
 * §2.3's inputs: the policy's and, for [mapAppInstalled], the reported installed packages. [clockFormat] `"system"`,
 * `"24"` or `"12"`. A phone before its first policy has [NONE]: only the `always` steps apply.
 */
data class SetupInputs(
    val mask: Long,
    val clockFormat: String,
    val callsManaged: Boolean,
    val androidLock: Boolean,
    val mapLinksOn: Boolean,
    val mapAppInstalled: Boolean,
    /** Design 119: the link gate is on (the report's `login_browser.browser_role` isn't `null`). */
    val linkGate: Boolean = false,
) {
    companion object {
        val NONE = SetupInputs(0, "system", callsManaged = false, androidLock = false, mapLinksOn = false, mapAppInstalled = false)
    }
}

/**
 * Design 114b A: the `child_pin` reading - the phone is locked by the Child PIN. [keyguard] is the cached `kid_lock`'s
 * (`null`: no Child PIN stored), [policyKnown] whether a policy has landed.
 * - Before the first policy: Android's credential ([androidCredential], `null` unreadable) - nothing else is known.
 * - No Child PIN stored: not done, whatever PIN the phone has (code QA M2: one the kid or Android's setup set isn't the
 *   Child PIN - the parent is asked; it is set from the PWA so the server knows it).
 * - Vibb One's legacy own lock: done - the Child PIN is that lock's, which steps aside for an Android credential.
 * - Android's lock: Android's credential. A PIN changed on the phone is Lock Screen & PINs' own warning, not this.
 * The contract's `child_pin_readings` pin it (the server's `setup_status::child_pin_reading` runs the same rows).
 */
fun childPinDone(androidCredential: Boolean?, keyguard: String?, policyKnown: Boolean): Boolean? = when {
    !policyKnown -> androidCredential
    keyguard == null -> false
    keyguard != "android" -> true
    else -> androidCredential
}

/** §2.3: whether [step] applies. `airplane_role` also needs the assistant reading done. */
fun setupApplies(step: SetupStep, inputs: SetupInputs, readings: Map<String, Boolean?>): Boolean = when (step.rule) {
    StepWhen.ALWAYS -> true
    StepWhen.CALLS_MANAGED -> inputs.callsManaged
    // Design 114b: the exact opposite, so `call_filter_early` and `call_filter` never apply together.
    StepWhen.CALLS_UNMANAGED -> !inputs.callsManaged
    // The Sound bit too (the kid's ringtone picks write `Settings.System`), as the server's `write_settings_needed`.
    StepWhen.WRITE_SETTINGS_USED -> inputs.mask and (BIT_TOUCH or BIT_SOUND) != 0L || inputs.clockFormat != "system"
    StepWhen.AIRPLANE -> inputs.mask and BIT_AIRPLANE != 0L
    StepWhen.AIRPLANE_ROLE -> inputs.mask and BIT_AIRPLANE != 0L && readings[SetupStep.ASSISTANT.key] == true
    StepWhen.ANDROID_LOCK -> inputs.androidLock
    StepWhen.MAP_LINKS -> inputs.mapLinksOn && inputs.mapAppInstalled
    StepWhen.LINK_GATE -> inputs.linkGate
}

/**
 * §2.3: the missing steps, in table order. A step is missing when it applies and its reading is present and `false`
 * (an absent or `null` reading is never missing), or, for a `parent` step, when it isn't [ticked]. A tick of any other
 * step changes nothing.
 */
fun setupMissing(inputs: SetupInputs, readings: Map<String, Boolean?>, ticked: Set<String>): List<SetupStep> =
    SetupStep.entries.filter { step ->
        setupApplies(step, inputs, readings) && when (step.readBy) {
            StepReadBy.PARENT -> step.key !in ticked
            else -> readings.containsKey(step.key) && readings[step.key] == false
        }
    }

/** N: the missing required steps the summary counts. */
fun setupCount(missing: List<SetupStep>): Int =
    missing.count { it.need == StepNeed.REQUIRED && it.summary == StepSummary.COUNT }

/** An own-row alarm step is missing (the Parent PIN, Device Owner). */
fun setupAlarm(missing: List<SetupStep>): Boolean =
    missing.any { it.summary == StepSummary.OWN_ROW && it.level == StepLevel.ALARM }

/** The summary's row: N > 0, and the phone isn't known not to be device owner (QA M4: then the QR redoes everything). */
fun setupRow(missing: List<SetupStep>, readings: Map<String, Boolean?>): Boolean =
    setupCount(missing) > 0 && readings[SetupStep.DEVICE_OWNER.key] != false

// ---- the flow (§6.2.2) --------------------------------------------------------------------------------------------

/** One screen of the flow. */
sealed interface FlowScreen {
    /** Re-entered outside a window: "Sett på pause i 2 t" first. */
    data object NotOpen : FlowScreen

    /** Nothing runnable yet (before the first policy): "Et øyeblikk …", its buttons after [FLOW_WAIT_BUTTONS_MS]. */
    data object Wait : FlowScreen

    data class Step(val step: SetupStep) : FlowScreen

    /** "Klar" at [left] = 0, else "{n} steg igjen" with [leftSteps] (the missing required steps, a tap goes back). */
    data class End(val left: Int, val leftSteps: List<SetupStep>) : FlowScreen
}

/** §6.2.2 (QA M3): the Wait screen's two buttons come after this long. */
const val FLOW_WAIT_BUTTONS_MS = 30_000L

/**
 * The run's screens in order: outside a window only [FlowScreen.NotOpen]; else the missing steps that are screens and
 * weren't put off ([skipped]) - before the first policy only the `always` ones (QA M3) -, then the end; with nothing
 * runnable before the first policy, [FlowScreen.Wait]. The current screen is the first; a step done meanwhile is gone
 * from [missing], so it leaves the run.
 */
fun flowScreens(missing: List<SetupStep>, skipped: Set<String>, windowOpen: Boolean, policyKnown: Boolean): List<FlowScreen> {
    if (!windowOpen) return listOf(FlowScreen.NotOpen)
    val runnable = missing.filter { it.screen && it.key !in skipped && (policyKnown || it.rule == StepWhen.ALWAYS) }
    if (runnable.isEmpty() && !policyKnown) return listOf(FlowScreen.Wait)
    val left = missing.filter { it.screen && it.need == StepNeed.REQUIRED && it.summary == StepSummary.COUNT }
    return runnable.map { FlowScreen.Step(it) } + FlowScreen.End(setupCount(missing), left)
}

enum class FlowButton { OPEN, LATER, SKIP, DID_IT }

/** §6.2.2's buttons: a required step "Åpne" and "Senere"; an optional one read by the phone "Åpne" and "Hopp over"; a
 * `parent` step also "Gjort". Without a page on this phone (NO_PAGE) only the way on. */
fun flowButtons(step: SetupStep, pageMissing: Boolean): List<FlowButton> {
    val on = if (step.need == StepNeed.REQUIRED) FlowButton.LATER else FlowButton.SKIP
    return when {
        // Design 114b: done in the PWA - no page to open here.
        pageMissing || step.inPwa -> listOf(on)
        step.readBy == StepReadBy.PARENT -> listOf(FlowButton.OPEN, FlowButton.DID_IT, FlowButton.SKIP)
        else -> listOf(FlowButton.OPEN, on)
    }
}

/** "2 av 7": [runKeys] every step shown as a screen in this run so far (the current one included), [remaining] the
 * run's step screens still ahead (the current first). */
fun flowProgress(runKeys: Set<String>, remaining: List<SetupStep>): Pair<Int, Int> {
    // Code QA M1: the steps ahead count too, so the line says how many are left.
    val keys = runKeys + remaining.map { it.key }
    val total = keys.size.coerceAtLeast(1)
    val done = total - remaining.size
    return (done + 1).coerceIn(1, total) to total
}
