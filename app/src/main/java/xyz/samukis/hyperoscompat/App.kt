package xyz.samukis.hyperoscompat

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import com.google.android.material.color.DynamicColors
import xyz.samukis.hyperoscompat.swipe.SwipeSearch

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        DynamicColors.applyToActivitiesIfAvailable(this)
        // The listener service keeps this process alive, so Shizuku's binder always finds us.
        SwipeSearch.init(this)
        val channel = NotificationChannel(
            CHANNEL_ISLAND,
            getString(R.string.channel_island),
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply {
            description = getString(R.string.channel_island_desc)
            setSound(null, null)
            enableVibration(false)
            setShowBadge(false)
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    companion object {
        const val CHANNEL_ISLAND = "island_bridge"
    }
}
