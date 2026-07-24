package com.carijajan.app

import android.app.Application
import android.util.Log
import org.maplibre.android.MapLibre

class CariJajanApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        
        // Initialize MapLibre GL Native SDK at Application startup
        runCatching {
            MapLibre.getInstance(this)
            Log.d("CariJajanApp", "MapLibre initialized successfully")
        }.onFailure { t ->
            Log.e("CariJajanApp", "Failed to initialize MapLibre", t)
        }
    }
}
