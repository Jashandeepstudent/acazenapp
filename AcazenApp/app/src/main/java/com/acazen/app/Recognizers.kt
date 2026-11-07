package com.acazen.app

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.speech.RecognitionService
import android.speech.SpeechRecognizer

/**
 * Picks the phone's REAL speech-recognition service for every SpeechRecognizer in the app.
 *
 * Why this exists: Acazen declares AcazenRecognitionService (an intentionally empty stub) only
 * because Android requires one before an app can hold the Assistant role. Once Acazen IS the
 * Assistant, the plain SpeechRecognizer.createSpeechRecognizer(context) can resolve to that
 * stub — which never answers — so the listening screen would hear nothing, time out, and hand
 * Acazen an empty question. Naming a real recognizer explicitly avoids that on every phone.
 *
 * (The matching <queries> entry in AndroidManifest.xml is what lets us see other apps'
 * recognition services on Android 11+.)
 */
object Recognizers {

    private const val GOOGLE_APP = "com.google.android.googlequicksearchbox"

    /** A recognition service that is not Acazen's own stub, preferring the Google app's. */
    @Suppress("DEPRECATION")
    fun findService(context: Context): ComponentName? {
        val found = try {
            context.packageManager.queryIntentServices(
                Intent(RecognitionService.SERVICE_INTERFACE), 0
            )
        } catch (e: Exception) {
            emptyList()
        }
        val real = found.mapNotNull { it.serviceInfo }
            .filter { it.packageName != context.packageName }
        val pick = real.firstOrNull { it.packageName == GOOGLE_APP } ?: real.firstOrNull()
        return pick?.let { ComponentName(it.packageName, it.name) }
    }

    /** A ready SpeechRecognizer bound to a real service, or null if this phone has none. */
    fun create(context: Context): SpeechRecognizer? {
        val service = findService(context) ?: return null
        return try {
            SpeechRecognizer.createSpeechRecognizer(context, service)
        } catch (e: Exception) {
            null
        }
    }
}
