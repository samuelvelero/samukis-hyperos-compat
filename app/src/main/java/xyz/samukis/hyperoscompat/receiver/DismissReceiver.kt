package xyz.samukis.hyperoscompat.receiver

import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import xyz.samukis.hyperoscompat.island.IslandPoster

class DismissReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        context.getSystemService(NotificationManager::class.java).cancel(IslandPoster.DEMO_ID)
    }
}
