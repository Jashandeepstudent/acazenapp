package com.acazen.app

import android.os.Bundle
import android.service.voice.VoiceInteractionSession
import android.service.voice.VoiceInteractionSessionService

/**
 * Required wiring between the system and AcazenInteractionSession: every time the assist
 * gesture fires (hold-Power, etc.), the framework asks this service for a fresh session.
 * Deliberately minimal — matches the shape of AOSP's own VoiceInteraction sample
 * (MainInteractionSessionService), which is exactly this one-method pass-through.
 */
class AcazenInteractionSessionService : VoiceInteractionSessionService() {
    override fun onNewSession(args: Bundle?): VoiceInteractionSession =
        AcazenInteractionSession(this)
}
