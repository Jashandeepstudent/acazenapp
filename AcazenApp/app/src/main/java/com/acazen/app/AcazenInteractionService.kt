package com.acazen.app

import android.os.Bundle
import android.service.voice.VoiceInteractionService

/**
 * Acazen's top-level voice-interaction entry point: this is what makes Acazen a selectable
 * "Digital assistant app" (RoleManager.ROLE_ASSISTANT) and — once selected — what the system
 * actually invokes for the native HOLD-Power-button gesture (and long-press-Home / nav-bar
 * assist swipe, depending on the device).
 *
 * ── Why this file exists (replacing the old DoublePressPowerService) ──────────────────────────
 * The previous approach tried to catch KEYCODE_POWER in an AccessibilityService's onKeyEvent().
 * That can never work: Android's window-manager policy (PhoneWindowManager
 * .interceptKeyBeforeQueueing) strips ACTION_PASS_TO_USER from the Power key before input ever
 * reaches the app-dispatch pipeline that onKeyEvent() reads from — a deliberate platform
 * security boundary, not an OEM bug, so onKeyEvent(KEYCODE_POWER) is simply never called, on any
 * Android version or skin. The ONLY sanctioned way for a regular app to be the target of the
 * system's power-button-hold-for-assistant gesture is to become the device's registered
 * Assistant: hold android.app.role.RoleManager.ROLE_ASSISTANT, backed by a real
 * VoiceInteractionService (this class) + VoiceInteractionSessionService
 * (AcazenInteractionSessionService) + a stub RecognitionService (AcazenRecognitionService,
 * required only to satisfy role eligibility — the actual speech capture happens inside the
 * session UI with the normal platform SpeechRecognizer, exactly like the old
 * VoiceCommandActivity did).
 *
 * This service intentionally does NOT run an always-on hotword detector: Acazen has no "wake
 * word" anymore (removed in 2.12.0 for battery/privacy) and nothing here listens in the
 * background. onReady() is a required lifecycle hook with nothing extra to set up; all real
 * work happens on-demand inside AcazenInteractionSession when the system starts a session
 * (gesture-triggered, or via showSession() from the lock-screen hook below).
 */
class AcazenInteractionService : VoiceInteractionService() {

    override fun onReady() {
        super.onReady()
        // Nothing to start: no hotword model, no background listening. The system calls
        // startSession()/onLaunchVoiceAssistFromKeyguard() on its own when the user triggers the
        // assist gesture (hold-Power, etc.) — AcazenInteractionSessionService.onNewSession()
        // handles it from there.
    }

    /**
     * Called when the user triggers the assist affordance from the LOCK SCREEN specifically
     * (only if res/xml/acazen_voice_interaction_service.xml sets
     * supportsLaunchVoiceAssistFromKeyguard="true", which it does). Per the platform contract,
     * a valid implementation must itself start a session that can draw over the keyguard —
     * showSession() does that for us, because AcazenInteractionSession.onShow() unconditionally
     * requests FLAG_SHOW_WHEN_LOCKED/FLAG_TURN_SCREEN_ON on its window, matching what
     * VoiceCommandActivity used to request directly as an Activity.
     */
    override fun onLaunchVoiceAssistFromKeyguard() {
        try {
            showSession(Bundle().apply { putBoolean(ARG_FROM_KEYGUARD, true) }, 0)
        } catch (e: Exception) {
            // Non-fatal: if the session can't be started this way on some OEM build, the
            // ordinary gesture path (startSession from a running session request) still works
            // once the phone is unlocked.
        }
    }

    companion object {
        /** Bundle key, reserved for AcazenInteractionSession to branch on if it ever needs
         *  different copy/behavior for a keyguard-triggered session; not read today because the
         *  keyguard window flags are applied unconditionally regardless of trigger source. */
        const val ARG_FROM_KEYGUARD = "acazen.from_keyguard"
    }
}
