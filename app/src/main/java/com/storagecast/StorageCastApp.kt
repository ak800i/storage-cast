package com.storagecast

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import com.google.android.gms.cast.framework.CastContext

class StorageCastApp : Application() {

    companion object {
        const val CHANNEL_MEDIA_SERVER = "media_server"
    }

    override fun onCreate() {
        super.onCreate()
        try {
            CastContext.getSharedInstance(this)
        } catch (e: Exception) {
            // Cast not available on this device
        }
        createNotificationChannel()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_MEDIA_SERVER,
                getString(R.string.notification_channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.notification_channel_description)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }
}
