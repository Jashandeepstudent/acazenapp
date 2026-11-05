package com.acazen.app

import android.content.Intent
import android.speech.RecognitionService

/**
 * A declared RecognitionService is one of the two things (alongside the VoiceInteractionService
 * declaration) RoleManager checks for before Acazen is eligible to hold ROLE_ASSISTANT — it does
 * NOT need to perform real speech-to-text itself. AOSP's own VoiceInteraction sample
 * (MainRecognitionService) ships the exact same kind of empty stub, by design: actual speech
 * capture for the hold-Power gesture happens inside AcazenInteractionSession using the normal
 * platform SpeechRecognizer client API (the same approach the old VoiceCommandActivity used),
 * not through this service. Leaving these callbacks empty is correct, not unfinished.
 */
class AcazenRecognitionService : RecognitionService() {
    override fun onStartListening(recognizerIntent: Intent?, listener: Callback?) {}
    override fun onCancel(listener: Callback?) {}
    override fun onStopListening(listener: Callback?) {}
}
