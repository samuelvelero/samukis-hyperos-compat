package xyz.samukis.hyperoscompat.receiver

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import xyz.samukis.hyperoscompat.inspector.NotificationLog
import xyz.samukis.hyperoscompat.island.MicMute
import xyz.samukis.hyperoscompat.service.IslandBridgeService

/** Island "mute" button for apps that do not expose one: toggles the system mic mute. */
class MuteReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val muted = MicMute.toggle(context)
        NotificationLog.event(context, "mic mute toggled -> muted=$muted")
        // HyperOS collapses the island after a button press; rebuild it with the new state.
        Handler(Looper.getMainLooper()).postDelayed({ IslandBridgeService.instance?.resync() }, 500)
    }

    companion object {
        fun pendingIntent(context: Context): PendingIntent = PendingIntent.getBroadcast(
            context, 1,
            Intent(context, MuteReceiver::class.java).setAction("xyz.samukis.hyperoscompat.TOGGLE_MUTE"),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }
}
