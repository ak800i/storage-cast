package com.storagecast

import android.app.Application
import com.google.android.gms.cast.framework.CastContext

class StorageCastApp : Application() {
    override fun onCreate() {
        super.onCreate()
        try {
            CastContext.getSharedInstance(this)
        } catch (e: Exception) {
            // Cast not available on this device
        }
    }
}
