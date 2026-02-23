package com.openclaw.assistant

import android.app.Application
import android.util.Log
import com.openclaw.assistant.gateway.GatewayClient

class OpenClawApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        try {
            // Defensive bootstrap: never crash app process during Application startup.
            GatewayClient.getInstance(this)
        } catch (t: Throwable) {
            Log.e("OpenClawApplication", "Gateway init failed at startup", t)
            // Intentionally swallow to keep UI launchable for diagnostics.
        }
    }
}
