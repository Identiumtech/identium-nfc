package com.identium.nfc

import android.app.Application
import com.google.android.material.color.DynamicColors

class IdentiumApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // Identium brand stays consistent — disable Material You dynamic theming.
        // (We still get day/night from the theme.)
        DynamicColors.applyToActivitiesIfAvailable(this) { _, _ -> false }
    }
}
