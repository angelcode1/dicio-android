package org.stypox.dicio

import android.app.Application
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationManagerCompat
import dagger.hilt.android.HiltAndroidApp
import org.stypox.dicio.util.deletePartialFiles

// IMPORTANT NOTE: beware of this nasty bug related to allowBackup=true
// https://medium.com/p/924c91bafcac
@HiltAndroidApp
class App : Application() {
    override fun onCreate() {
        super.onCreate()

        // No download can be active before Application startup, so this is the safe point to clear
        // orphaned temp files left behind by a killed/interrupted process.
        deletePartialFiles(cacheDir)

        // Notification channels may be created before POST_NOTIFICATIONS is granted. Creating them
        // up front ensures later error/service notifications have a valid channel as soon as the
        // user grants permission.
        initNotificationChannels()
    }

    private fun initNotificationChannels() {
        NotificationManagerCompat.from(this).createNotificationChannelsCompat(
            listOf(
                NotificationChannelCompat.Builder(
                    getString(R.string.error_report_channel_id),
                    NotificationManagerCompat.IMPORTANCE_LOW
                )
                    .setName(getString(R.string.error_report_channel_name))
                    .setDescription(getString(R.string.error_report_channel_description))
                    .build()
            )
        )
    }
}
