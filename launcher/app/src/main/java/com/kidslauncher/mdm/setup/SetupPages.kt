package com.kidslauncher.mdm.setup

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import com.kidslauncher.mdm.R
import com.kidslauncher.mdm.assistant.AssistantSetup
import com.kidslauncher.mdm.badges.BadgeListenerService
import com.kidslauncher.mdm.notify.NotificationChannelsReader
import com.kidslauncher.mdm.server.BatteryExemption
import com.kidslauncher.mdm.server.FingerprintEnrol
import com.kidslauncher.mdm.server.MediaLockScreen
import com.kidslauncher.mdm.server.WriteSettingsAccess
import com.kidslauncher.mdm.server.systemPageIntent

/** The package whose links design 110's step 15 takes back from Play services. */
private const val PLAY_SERVICES = "com.google.android.gms"

/**
 * Row 5's pages in the order "Åpne" tries them. Android 16's SIM page has no Mobile data switch (the emulator gate of
 * 2026-11-19: "Use this SIM", Mobile network, Roaming, no Mobile data); the Internet panel has it on 14 and 16 alike, so
 * from 16 the panel comes first. Android 14 keeps the SIM page, which has the switch. Pure.
 */
internal fun mobileDataActions(actions: List<String>, sdk: Int): List<String> =
    if (sdk >= 36) actions.sortedBy { if (it == Settings.Panel.ACTION_INTERNET_CONNECTIVITY) 0 else 1 } else actions

/**
 * A step's line on the flow's screen. Android 16's "Open by default" page has "In the app" / "In your browser" instead
 * of 14's "Open supported links" switch (design 110, the emulator gate's finding 3; 15 unverified, the switch's words
 * kept there), so the two map steps say that from 16. Pure.
 */
internal fun setupHowRes(step: SetupStep, sdk: Int): Int? = when {
    sdk >= 36 && step == SetupStep.MAP_PLAY_LINKS -> R.string.setup_how_map_play_links_16
    sdk >= 36 && step == SetupStep.MAP_VIBB_LINKS -> R.string.setup_how_map_vibb_links_16
    else -> step.howRes
}

/**
 * Design 114 §6.2.2: the Android pages a step's "Åpne" tries, in order, each explicit to an unsuspended system package
 * (`systemPageIntent`) - so empty outside a window on a managed phone. The actions are the step's own
 * ([SetupStep.pageActions], pinned by the contract); the steps that had a row in parent Settings keep their builders.
 * The Wi-Fi panel (row 12) and the role requests (row 13) go through design 98's windows ([AssistantSetup]).
 */
object SetupPages {
    private fun ours(context: Context): Uri = Uri.parse("package:${context.packageName}")

    private fun page(context: Context, intent: Intent): Intent? = systemPageIntent(context, intent)

    fun intents(context: Context, step: SetupStep): List<Intent> {
        val actions = step.pageActions ?: return emptyList()
        return when (step) {
            SetupStep.PARENT_PIN, SetupStep.DEVICE_OWNER -> emptyList()
            SetupStep.NOTIFICATION_ACCESS -> listOfNotNull(
                page(
                    context,
                    Intent(actions[0]).putExtra(
                        Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME,
                        ComponentName(context, BadgeListenerService::class.java).flattenToString(),
                    ),
                ),
                page(context, Intent(actions[1])),
            )
            SetupStep.MOBILE_DATA -> mobileDataActions(actions, android.os.Build.VERSION.SDK_INT).mapNotNull { page(context, Intent(it)) }
            SetupStep.LOCATION, SetupStep.DATA_SAVER, SetupStep.FULL_SCREEN_INTENT, SetupStep.MAP_VIBB_LINKS ->
                listOfNotNull(page(context, Intent(actions[0], ours(context))))
            SetupStep.MAP_PLAY_LINKS -> listOfNotNull(page(context, Intent(actions[0], Uri.parse("package:$PLAY_SERVICES"))))
            SetupStep.BACKGROUND -> listOfNotNull(BatteryExemption.dialogIntent(context))
            SetupStep.VIBB_NOTIFICATIONS -> NotificationChannelsReader.pageIntents(context)
            SetupStep.WRITE_SETTINGS -> WriteSettingsAccess.pageIntents(context)
            SetupStep.ASSISTANT -> AssistantSetup.assistantPageIntents(context)
            // The panel isn't a Settings page: it opens once design 98's Wi-Fi step has turned airplane mode on.
            SetupStep.WIFI_AIRPLANE -> listOf(AssistantSetup.wifiPanelIntent())
            SetupStep.CALL_FILTER -> AssistantSetup.screeningRoleIntents(context)
            SetupStep.FINGERPRINT -> FingerprintEnrol.pageIntents(context)
            SetupStep.MEDIA_ON_LOCK -> MediaLockScreen.pageIntents(context)
            SetupStep.DEFAULT_APPS, SetupStep.VIBRATION, SetupStep.TOUCH_FEEDBACK, SetupStep.LOCK_CLOCK,
            SetupStep.MESSAGE_BANNERS,
            -> listOfNotNull(page(context, Intent(actions[0])))
        }
    }
}
