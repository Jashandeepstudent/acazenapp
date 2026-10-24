package com.acazen.app

import android.app.Application
import androidx.appcompat.app.AppCompatDelegate

class AcazenApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        // Acazen always shows the website's own (light) colors, even when the phone is in dark
        // mode. Set here, before any activity exists, so the WebView never sees a night config.
        AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO)
    }
}
