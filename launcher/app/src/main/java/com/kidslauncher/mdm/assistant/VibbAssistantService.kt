package com.kidslauncher.mdm.assistant

import android.service.voice.VoiceInteractionService
import android.service.voice.VoiceInteractionSession

/**
 * Design 98 §2.1: the Digital assistant's service. Vibb One holds the role only for Flymodus's route
 * ([AirplaneRoute]); it never reads the screen - the assist context and the screenshot are disabled in [onReady]
 * (measured M8: no data, structure, content or screenshot arrives), and the session reads nothing
 * (`AssistantInvariantsTest`).
 */
class VibbAssistantService : VoiceInteractionService() {
    override fun onReady() {
        super.onReady()
        setDisabledShowContext(VoiceInteractionSession.SHOW_WITH_ASSIST or VoiceInteractionSession.SHOW_WITH_SCREENSHOT)
        AirplaneRoute.serviceReady(this)
        // §3.2: the role just became ours - the default-apps window closes now.
        AssistantSetup.roleBecameOurs(applicationContext)
    }

    override fun onShutdown() {
        AirplaneRoute.serviceGone(this)
        // §3.6: the role moved away, or the service was unbound - one report.
        AssistantSetup.roleMaybeChanged(applicationContext)
        super.onShutdown()
    }
}
