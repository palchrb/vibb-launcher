package com.kidslauncher.mdm.assistant

import android.app.assist.AssistContent
import android.app.assist.AssistStructure
import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.provider.Settings
import android.service.voice.VoiceInteractionSession
import android.service.voice.VoiceInteractionSessionService
import android.util.Log

/** Design 98 §2.1: the assistant's session service - one [VibbSession] per show. */
class VibbSessionService : VoiceInteractionSessionService() {
    override fun onNewSession(args: Bundle?): VoiceInteractionSession = VibbSession(this)
}

/**
 * The session acts only on Vibb's own marker: [AirplaneRoute.EXTRA_ROUTE] with the nonce [AirplaneRoute] made for this
 * one request (used once). Any other show (the assist gesture, a stale request) hides at once and starts nothing (D9).
 * No window ([setUiEnabled] false), and every assist callback is empty: it reads nothing and logs nothing (§8).
 */
class VibbSession(service: VibbSessionService) : VoiceInteractionSession(service) {
    private var ours = false

    override fun onCreate() {
        super.onCreate()
        setUiEnabled(false)
    }

    override fun onShow(args: Bundle?, showFlags: Int) {
        super.onShow(args, showFlags)
        val component = AirplaneRoute.claim(args?.getString(AirplaneRoute.EXTRA_ROUTE))
        if (component == null) {
            hide()
            return
        }
        ours = true
        AirplaneRoute.sessionShown(this)
        try {
            startVoiceActivity(
                Intent(Settings.ACTION_VOICE_CONTROL_AIRPLANE_MODE)
                    .addCategory(Intent.CATEGORY_VOICE)
                    .setComponent(component)
                    .putExtra(Settings.EXTRA_AIRPLANE_MODE_ENABLED, true),
            )
        } catch (e: Exception) {
            Log.w("VibbSession", "The voice activity didn't start: ${e.javaClass.simpleName}")
            AirplaneRoute.sessionFailed()
            hide()
        }
    }

    override fun onTaskFinished(intent: Intent?, taskId: Int) {
        super.onTaskFinished(intent, taskId)
        if (ours) {
            AirplaneRoute.sessionFinished()
            hide()
        }
    }

    // §8: every assist callback empty - nothing is read, kept or logged.
    override fun onHandleAssist(state: AssistState) {}

    @Suppress("OVERRIDE_DEPRECATION")
    override fun onHandleAssist(data: Bundle?, structure: AssistStructure?, content: AssistContent?) {}

    @Suppress("OVERRIDE_DEPRECATION")
    override fun onHandleAssistSecondary(data: Bundle?, structure: AssistStructure?, content: AssistContent?, index: Int, count: Int) {}

    override fun onHandleScreenshot(screenshot: Bitmap?) {}
}
