package com.kidslauncher.mdm.assistant

import android.content.Intent
import android.speech.RecognitionService
import android.speech.SpeechRecognizer

/**
 * Design 98 §2.1: the recognizer the assistant role requires (`VoiceInteractionServiceInfo` refuses a service without
 * one). A stub: every request answers [SpeechRecognizer.ERROR_CLIENT] at once. The launcher declares no RECORD_AUDIO.
 */
class VibbRecognizer : RecognitionService() {
    override fun onStartListening(recognizerIntent: Intent?, listener: Callback?) {
        try {
            listener?.error(SpeechRecognizer.ERROR_CLIENT)
        } catch (e: Exception) {
            // The caller went away.
        }
    }

    override fun onCancel(listener: Callback?) {}

    override fun onStopListening(listener: Callback?) {}
}
