package com.acazen.app

import android.app.KeyguardManager
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity

/**
 * Dedicated, invisible "hop" activity for the "Hey Acazen" wake word.
 *
 * Why this exists: a plain startActivity() from a background Service is blocked by Android 10+
 * ("background activity start" restrictions), and it does nothing at all when the screen is off
 * or locked — which is exactly why saying "Hey Acazen" with the app closed never opened Acazen.
 * A foreground service that holds the microphone IS granted background-activity-start privileges
 * on Android 10+, and an activity declared with `showWhenLocked`/`turnScreenOn` can light the
 * screen straight from the keyguard. So WakeWordService launches THIS tiny activity, which:
 *
 *  1. Turns the screen on and shows over the lock screen (no secure-lock bypass — with a
 *     PIN/pattern/password the user still authenticates; Acazen simply comes up right after,
 *     instead of the launch being silently dropped like before).
 *  2. Hands off to MainActivity with ACTION_WAKE_WORD so the site opens in voice mode
 *     ("?voice=1"), whether the app was closed, backgrounded, or already open.
 *  3. Finishes itself immediately, so this hop never appears in Recents or stays on the back
 *     stack.
 */
class WakeLaunchActivity : ComponentActivity() {

    private var delegated = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Light the screen and show over the lock screen as early as possible.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                    WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
            )
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        // Dismiss an INSECURE keyguard (swipe-only lock / no lock). A secure keyguard (PIN,
        // pattern, password, biometric) cannot be dismissed programmatically without the
        // DISABLE_KEYGUARD privileged permission — deliberately not requested: apps that ask
        // for it are rejected from Google Play. With a secure lock, MainActivity comes up
        // right after the user unlocks normally.
        try {
            val km = getSystemService(KeyguardManager::class.java)
            if (km != null && !km.isKeyguardSecure) {
                @Suppress("DEPRECATION", "MissingPermission")
                km.newKeyguardLock("AcazenWake").disableKeyguard()
            }
        } catch (e: Exception) {
            // Best-effort only.
        }

        delegateToMain()
    }

    private fun delegateToMain() {
        if (delegated) return
        delegated = true
        try {
            val main = Intent(this, MainActivity::class.java).apply {
                action = MainActivity.ACTION_WAKE_WORD
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            startActivity(main)
        } catch (e: Exception) {
            // Last-resort fallback: drop the wake extras but at least open the app.
            try {
                startActivity(
                    Intent(this, MainActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                )
            } catch (ignored: Exception) {
            }
        }
        finish()
        // No open/close animation — this activity must feel invisible.
        overridePendingTransition(0, 0)
    }

    @Suppress("DEPRECATION")
    override fun finish() {
        super.finish()
        overridePendingTransition(0, 0)
    }
}
