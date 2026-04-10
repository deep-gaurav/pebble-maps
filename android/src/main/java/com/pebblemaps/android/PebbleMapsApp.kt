package com.pebblemaps.android

import android.app.Application
import com.pebblemaps.android.di.appModule
import org.koin.android.ext.koin.androidContext
import org.koin.android.ext.koin.androidLogger
import org.koin.core.context.startKoin
import org.osmdroid.config.Configuration

class PebbleMapsApp : Application() {
    override fun onCreate() {
        super.onCreate()
        
        // Initialize OSMDroid configuration
        Configuration.getInstance().apply {
            userAgentValue = packageName
            load(this@PebbleMapsApp, getSharedPreferences("osmdroid", MODE_PRIVATE))
        }
        
        startKoin {
            androidLogger()
            androidContext(this@PebbleMapsApp)
            modules(appModule)
        }
    }
}