package com.openclaw.assistant

import android.app.Application
import android.util.Log

class OpenClawApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        Log.w("OpenClawApplication", "DIAG BUILD: startup gateway init disabled")
        // Intentionally disabled for crash isolation.
    }
}
